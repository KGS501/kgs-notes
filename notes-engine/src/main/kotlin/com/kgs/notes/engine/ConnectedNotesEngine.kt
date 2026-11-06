package com.kgs.notes.engine

import java.io.InputStream
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Owns local Notes plus durable, offline-first Working Copies for external Sources.
 * Source implementations stay responsible for protocol choreography; this class never persists credentials.
 */
class ConnectedNotesEngine private constructor(
    private val local: LocalNotesEngine,
    private val workingCopyRoot: Path,
    private val clock: Clock,
    loadedWorkingCopies: Map<NoteId, WorkingCopy>,
) : NotesEngine {
    private val metadataRoot = workingCopyRoot.resolve("notes")
    private val contentRoot = workingCopyRoot.resolve("content")
    private val baseRoot = workingCopyRoot.resolve("base")
    private val mutex = Mutex()
    private val sourceOperations = ConcurrentHashMap<SourceId, Mutex>()
    private val transfers = Mutex()
    private val workingCopies = loadedWorkingCopies.toMutableMap()
    private val editingNotes = mutableSetOf<NoteId>()
    private val attachedSources = linkedMapOf<SourceId, Source>()
    /** External Sources cannot store an empty Category, so this device keeps it until removed. */
    private var retainedCategories: Map<SourceId, Set<String>> = loadRetainedCategories(workingCopyRoot)
    private val mutableLibrary = MutableStateFlow(snapshot())
    private val mutableSources = MutableStateFlow(local.sources.value)

    override val library: StateFlow<LibrarySnapshot> = mutableLibrary.asStateFlow()
    override val sources: StateFlow<List<SourceDescriptor>> = mutableSources.asStateFlow()

    override suspend fun createDraft(category: String, sourceId: SourceId): NoteId {
        if (sourceId == LocalSourceId) {
            val id = local.createDraft(category, sourceId)
            publish()
            return id
        }
        return mutex.withLock {
            requireNotNull(attachedSources[sourceId]) { "The selected Source is not connected" }
            val now = clock.instant()
            val id = NoteId(UUID.randomUUID().toString())
            val workingCopy = WorkingCopy(
                id = id,
                sourceId = sourceId,
                remoteId = "",
                revision = "",
                title = DEFAULT_TITLE,
                markdown = "",
                category = normalizeCategory(category),
                favorite = false,
                state = NoteState.DRAFT,
                createdAt = now,
                updatedAt = now,
                trashedAt = null,
                titleFixed = false,
                readOnly = false,
                syncState = NoteSyncState.SAVED_LOCALLY,
                baseMarkdown = "",
                pendingRemoteDeletion = false,
            )
            persist(workingCopy)
            workingCopies[id] = workingCopy
            publishLocked()
            id
        }
    }

    override suspend fun attachSource(source: Source) = mutex.withLock {
        require(source.descriptor.id != LocalSourceId) { "Local Source is owned by the engine" }
        attachedSources[source.descriptor.id] = source
        mutableSources.value = local.sources.value + attachedSources.values.map(Source::descriptor)
    }

    override suspend fun removeSource(id: SourceId) = mutex.withLock {
        require(id != LocalSourceId) { "The Local Source cannot be removed" }
        val copies = workingCopies.values.filter { it.sourceId == id }
        check(copies.none { it.id in editingNotes }) { "Close this Source's Notes before removing it" }
        check(copies.all { it.syncState == NoteSyncState.SYNCED && !it.pendingRemoteDeletion }) {
            "Save, move, or export pending Notes before removing this Source"
        }
        copies.forEach { copy ->
            metadataPath(copy.id).deleteIfExists()
            contentPath(copy.id).deleteIfExists()
            basePath(copy.id).deleteIfExists()
            workingCopies.remove(copy.id)
        }
        attachedSources.remove(id)
        retainCategories(retainedCategories - id)
        mutableSources.value = local.sources.value + attachedSources.values.map(Source::descriptor)
        publishLocked()
    }

    override suspend fun createCategory(sourceId: SourceId, category: String): String {
        if (sourceId == LocalSourceId) return local.createCategory(sourceId, category).also { publish() }
        val normalized = normalizedCategoryPath(category)
        mutex.withLock {
            val source = requireNotNull(attachedSources[sourceId]) { "Reconnect this Source to manage its Categories" }
            require(source.descriptor.capabilities.categories) { "This Source cannot change Categories" }
            retainCategories(retainedCategories + (sourceId to (retainedCategories[sourceId].orEmpty() + normalized)))
            publishLocked()
        }
        return normalized
    }

    override suspend fun renameCategory(sourceId: SourceId, category: String, newCategory: String): Set<NoteId> {
        val target = normalizedCategoryPath(newCategory)
        if (sourceId == LocalSourceId) return local.renameCategory(sourceId, category, target).also { publish() }
        return replaceCategoryAssignments(sourceId, category, target).also { replaceRetainedCategory(sourceId, category, target) }
    }

    override suspend fun deleteCategory(sourceId: SourceId, category: String): Set<NoteId> {
        if (sourceId == LocalSourceId) return local.deleteCategory(sourceId, category).also { publish() }
        return replaceCategoryAssignments(sourceId, category, "").also { replaceRetainedCategory(sourceId, category, "") }
    }

    private suspend fun replaceRetainedCategory(sourceId: SourceId, category: String, target: String) = mutex.withLock {
        val replaced = retainedCategories[sourceId].orEmpty().replacingCategory(category, target)
        retainCategories(if (replaced.isEmpty()) retainedCategories - sourceId else retainedCategories + (sourceId to replaced))
        publishLocked()
    }

    private fun retainCategories(categories: Map<SourceId, Set<String>>) {
        if (categories == retainedCategories) return
        writeTextAtomically(
            workingCopyRoot.resolve(CATEGORIES_FILE),
            categories.flatMap { (sourceId, paths) -> paths.sorted().map { "${sourceId.value}\t$it" } }.joinToString("\n"),
        )
        retainedCategories = categories
    }

    override suspend fun refreshSources() {
        val connected = mutex.withLock { attachedSources.values.toList() }
        var firstFailure: Exception? = null
        connected.forEach { source ->
            try {
                sourceOperations.computeIfAbsent(source.descriptor.id) { Mutex() }.withLock {
                    source.refresh()
                    mutex.withLock {
                        if (attachedSources[source.descriptor.id] !== source) return@withLock
                        reconcile(source.descriptor.id, source.notes.value)
                        publishLocked()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // One unavailable Source must not block the other Sources, and
                // its durable Working Copies remain intact for the next retry.
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    override suspend fun synchronize(id: NoteId) {
        val sourceId = mutex.withLock { workingCopies[id]?.sourceId } ?: return
        sourceOperations.computeIfAbsent(sourceId) { Mutex() }.withLock {
            // Finish an issued write and retain the returned remote identity even
            // if its caller is cancelled. A replacement create must not duplicate it.
            withContext(NonCancellable) { synchronizeRemote(id) }
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun synchronizeRemote(id: NoteId) {
        if (!reconcileUncertainWrite(id)) return
        val request = mutex.withLock {
            val current = workingCopies[id] ?: return
            val source = attachedSources[current.sourceId] ?: return
            if (current.syncState == NoteSyncState.SYNCED ||
                (current.state == NoteState.DRAFT && current.markdown.isBlank() && !current.titleFixed)
            ) return
            if (current.state == NoteState.TRASHED && current.remoteId.isBlank()) {
                val retained = current.copy(syncState = NoteSyncState.SYNCED, pendingRemoteDeletion = false)
                persist(retained)
                workingCopies[id] = retained
                publishLocked()
                return
            }
            if (current.readOnly) {
                val blocked = current.copy(syncState = NoteSyncState.NEEDS_ATTENTION)
                persist(blocked)
                workingCopies[id] = blocked
                publishLocked()
                return
            }
            val syncing = current.copy(
                syncState = NoteSyncState.SYNCING,
                // Record what a write sends before issuing it. If its response is lost,
                // later attempts recognize the Source copy as this device's own write
                // instead of publishing a duplicate or reporting a Conflict.
                uncertainWrite = if (current.pendingRemoteDeletion) current.uncertainWrite else WriteAttempt.of(current),
            )
            persist(syncing)
            workingCopies[id] = syncing
            publishLocked()
            SyncRequest(source, syncing)
        }

        val result = runCatching { issueWrite(request.source, request.workingCopy) }
            .recoverCatching { failure ->
                if (failure !is SourceConflictException) throw failure
                recoverStaleRevision(request.source, request.workingCopy) ?: throw failure
            }

        mutex.withLock {
            val current = workingCopies[id] ?: return@withLock
            result.fold(
                onSuccess = { outcome ->
                    val synced = when (outcome) {
                        is SyncOutcome.Upserted -> if (
                            current.toSourceDraft() == request.workingCopy.toSourceDraft() &&
                            current.state == request.workingCopy.state &&
                            current.pendingRemoteDeletion == request.workingCopy.pendingRemoteDeletion
                        ) {
                            current.adopt(outcome.note, syncState = NoteSyncState.SYNCED)
                                .copy(titleFixed = true)
                        } else {
                            current.copy(
                                remoteId = outcome.note.remoteId,
                                revision = outcome.note.revision,
                                baseMarkdown = outcome.note.markdown,
                                readOnly = outcome.note.readOnly,
                                titleFixed = true,
                                pendingRemoteDeletion = current.pendingRemoteDeletion || current.state == NoteState.TRASHED,
                                syncState = NoteSyncState.SAVED_LOCALLY,
                                uncertainWrite = null,
                                syncFailed = false,
                            )
                        }
                        SyncOutcome.Deleted -> current.copy(
                            remoteId = "",
                            revision = "",
                            syncState = if (current.state == NoteState.TRASHED) {
                                NoteSyncState.SYNCED
                            } else {
                                NoteSyncState.SAVED_LOCALLY
                            },
                            pendingRemoteDeletion = false,
                            syncFailed = false,
                        )
                    }
                    persist(synced)
                    workingCopies[id] = synced
                },
                onFailure = { failure ->
                    val state = if (failure is SourceConflictException) {
                        NoteSyncState.NEEDS_ATTENTION
                    } else {
                        NoteSyncState.SAVED_LOCALLY
                    }
                    val safe = current.copy(
                        syncState = state,
                        syncFailed = failure !is SourceConflictException,
                    )
                    persist(safe)
                    workingCopies[id] = safe
                },
            )
            publishLocked()
        }
    }

    private suspend fun issueWrite(source: Source, copy: WorkingCopy): SyncOutcome =
        if (copy.pendingRemoteDeletion) {
            if (copy.remoteId.isNotBlank()) {
                source.deleteNote(remoteId = copy.remoteId, expectedRevision = copy.revision)
            }
            SyncOutcome.Deleted
        } else if (copy.remoteId.isBlank()) {
            SyncOutcome.Upserted(source.createNote(copy.toSourceDraft()))
        } else {
            SyncOutcome.Upserted(
                source.updateNote(
                    remoteId = copy.remoteId,
                    expectedRevision = copy.revision,
                    note = copy.toSourceDraft(),
                ),
            )
        }

    /**
     * Confirm a previous write before replacing its durable fingerprint. Otherwise
     * a retry containing newer edits can mistake our own lost update for a Conflict.
     * Returns false when the Source cannot be reached; never retry a create blindly.
     * Callers hold this Source's operation lock.
     */
    private suspend fun reconcileUncertainWrite(id: NoteId): Boolean {
        val source = mutex.withLock {
            val current = workingCopies[id] ?: return true
            if (current.uncertainWrite == null) return true
            attachedSources[current.sourceId] ?: return true
        }
        try {
            source.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            updateWorkingCopy(id) { current ->
                current.copy(
                    syncState = if (current.syncState == NoteSyncState.SYNCING) NoteSyncState.SAVED_LOCALLY else current.syncState,
                    syncFailed = true,
                )
            }
            return false
        }
        mutex.withLock {
            if (attachedSources[source.descriptor.id] !== source) return@withLock
            reconcile(source.descriptor.id, source.notes.value)
            publishLocked()
        }
        return true
    }

    /**
     * A rejected revision is only a Conflict when the Source content changed. A write
     * whose response was lost, or a revision bump without edits, continues safely.
     */
    private suspend fun recoverStaleRevision(source: Source, copy: WorkingCopy): SyncOutcome? {
        if (copy.remoteId.isBlank()) return null
        source.refresh()
        val remote = source.notes.value.firstOrNull { it.remoteId == copy.remoteId } ?: return null
        if (remote.revision == copy.revision) return null
        return when {
            copy.pendingRemoteDeletion -> if (remote.markdown == copy.baseMarkdown || remote.markdown == copy.markdown) {
                issueWrite(source, copy.copy(revision = remote.revision))
            } else null
            remote.matches(copy) -> SyncOutcome.Upserted(remote)
            copy.uncertainWrite?.matches(remote) == true -> issueWrite(source, copy.copy(revision = remote.revision))
            remote.markdown == copy.baseMarkdown && remote.matchesMetadata(copy) ->
                issueWrite(source, copy.copy(revision = remote.revision))
            else -> null
        }
    }

    override suspend fun moveToSource(id: NoteId, sourceId: SourceId): NoteId = transfers.withLock {
        val initial = requireNotNull(note(id)) { "Cannot move a missing Note" }
        if (initial.sourceId == sourceId) return@withLock id
        require(initial.sourceId == LocalSourceId || sourceId == LocalSourceId) {
            "Move this Note to the Local Source before choosing another external Source"
        }
        require(initial.state != NoteState.TRASHED) { "Restore this Note before moving it" }
        val externalId = if (sourceId == LocalSourceId) initial.sourceId else sourceId
        sourceOperations.computeIfAbsent(externalId) { Mutex() }.withLock {
            val source = mutex.withLock {
                requireNotNull(attachedSources[externalId]) { "Connect this Source before moving the Note" }
            }
            require(source.descriptor.capabilities.writable) { "This Source is read-only" }
            // Retain the result of issued writes even when the caller leaves the editor.
            withContext(NonCancellable) movement@{
                val original = requireNotNull(note(id)) { "Cannot move a missing Note" }
                if (sourceId == LocalSourceId) {
                    val remote = mutex.withLock { requireNotNull(workingCopies[id]) }
                    require(!remote.readOnly) { "This Note is read-only" }
                    local.retainTransferredNote(original)
                    try {
                        if (remote.remoteId.isNotBlank()) {
                            try {
                                source.deleteNote(remote.remoteId, remote.revision)
                            } catch (conflict: SourceConflictException) {
                                recoverStaleRevision(source, remote.copy(pendingRemoteDeletion = true))
                                    ?: throw conflict
                            }
                        }
                    } catch (conflict: SourceConflictException) {
                        // The Source changed concurrently. Keep both sides: the edits
                        // move to the Local Source as a new Note, and the Source version
                        // stays where it is as an ordinary Working Copy.
                        local.deletePermanently(id)
                        return@movement keepBothSides(id, source, original)
                    } catch (failure: Exception) {
                        // The original Working Copy is still durable and authoritative.
                        // Do not queue a deletion after a rejected or uncertain transfer.
                        local.deletePermanently(id)
                        throw failure
                    }
                    mutex.withLock {
                        val latest = requireNotNull(workingCopies[id])
                        local.retainTransferredNote(latest.toPublic())
                        removeWorkingCopyLocked(id)
                        publishLocked()
                    }
                } else {
                    require(local.managedAttachments(id).isEmpty()) {
                        "Notes with Managed Attachments cannot move to this Source yet"
                    }
                    val confirmed = source.createNote(SourceNoteDraft(
                        title = original.title,
                        markdown = original.markdown,
                        category = original.category,
                        favorite = original.favorite,
                        modifiedAt = original.updatedAt,
                    ))
                    mutex.withLock {
                        val latest = requireNotNull(local.note(id))
                        var transferred = WorkingCopy.fromRemote(id, sourceId, confirmed)
                            .copy(createdAt = original.createdAt, pinned = original.pinned)
                        if (latest != original) {
                            transferred = transferred.copy(
                                title = latest.title,
                                markdown = latest.markdown,
                                category = latest.category,
                                favorite = latest.favorite,
                                pinned = latest.pinned,
                                state = latest.state,
                                updatedAt = latest.updatedAt,
                                trashedAt = latest.trashedAt,
                                syncState = NoteSyncState.SAVED_LOCALLY,
                                pendingRemoteDeletion = latest.state == NoteState.TRASHED,
                            )
                        }
                        persist(transferred)
                        workingCopies[id] = transferred
                        local.deletePermanently(id)
                        publishLocked()
                    }
                }
                id
            }
        }.also { currentCoroutineContext().ensureActive() }
    }

    private suspend fun keepBothSides(id: NoteId, source: Source, original: Note): NoteId {
        val edits = mutex.withLock { requireNotNull(workingCopies[id]) }.toPublic()
        val localId = NoteId(UUID.randomUUID().toString())
        local.retainTransferredNote(edits.copy(id = localId, sourceId = LocalSourceId, syncState = NoteSyncState.LOCAL_SOURCE))
        val refreshed = runCatching { source.refresh() }.isSuccess
        mutex.withLock {
            val current = workingCopies[id]
            val remote = source.notes.value.firstOrNull { it.remoteId == current?.remoteId }
            if (current != null && refreshed && remote != null) {
                val adopted = current.adopt(remote, NoteSyncState.SYNCED).copy(createdAt = original.createdAt)
                persist(adopted)
                workingCopies[id] = adopted
            }
            editingNotes.remove(id)
            publishLocked()
        }
        return localId
    }

    private fun removeWorkingCopyLocked(id: NoteId) {
        workingCopies.remove(id)
        metadataPath(id).deleteIfExists()
        contentPath(id).deleteIfExists()
        basePath(id).deleteIfExists()
    }

    override suspend fun note(id: NoteId): Note? = mutex.withLock {
        workingCopies[id]?.toPublic()
    } ?: local.note(id)

    override suspend fun openEditingSession(id: NoteId): Note? = mutex.withLock {
        val current = workingCopies[id]?.toPublic() ?: local.note(id) ?: return@withLock null
        if (current.state != NoteState.TRASHED) editingNotes += id
        current
    }

    override suspend fun updateContent(id: NoteId, markdown: String) {
        if (updateWorkingCopy(id) { current ->
                val title = if (current.titleFixed) current.title else automaticTitle(markdown)
                current.copy(
                    title = title,
                    markdown = markdown,
                    state = if (markdown.isBlank() && !current.titleFixed) NoteState.DRAFT else NoteState.ACTIVE,
                    updatedAt = clock.instant(),
                    syncState = NoteSyncState.SAVED_LOCALLY,
                )
            }
        ) return
        local.updateContent(id, markdown)
        publish()
    }

    override suspend fun rename(id: NoteId, title: String) {
        if (updateWorkingCopy(id) { current ->
                current.copy(
                    title = normalizeTitle(title),
                    titleFixed = true,
                    state = if (current.state == NoteState.DRAFT) NoteState.ACTIVE else current.state,
                    updatedAt = clock.instant(),
                    syncState = NoteSyncState.SAVED_LOCALLY,
                )
            }
        ) return
        local.rename(id, title)
        publish()
    }

    override suspend fun setCategory(id: NoteId, category: String) {
        if (updateWorkingCopy(id) { current ->
                current.copy(
                    category = normalizeCategory(category),
                    updatedAt = clock.instant(),
                    syncState = NoteSyncState.SAVED_LOCALLY,
                )
            }
        ) return
        local.setCategory(id, category)
        publish()
    }

    override suspend fun importManagedAttachment(
        noteId: NoteId,
        attachment: AttachmentImport,
    ): ManagedAttachment {
        require(mutex.withLock { noteId !in workingCopies }) {
            "External Source attachments are not available yet"
        }
        return local.importManagedAttachment(noteId, attachment)
    }

    override suspend fun managedAttachments(noteId: NoteId): List<ManagedAttachment> =
        if (mutex.withLock { noteId in workingCopies }) emptyList() else local.managedAttachments(noteId)

    override fun openManagedAttachment(id: AttachmentId): InputStream? = local.openManagedAttachment(id)

    override suspend fun togglePin(id: NoteId) {
        if (updateWorkingCopy(id) { it.copy(pinned = !it.pinned) }) return
        local.togglePin(id)
        publish()
    }

    override suspend fun toggleFavorite(id: NoteId) {
        if (updateWorkingCopy(id) { current ->
                current.copy(
                    favorite = !current.favorite,
                    updatedAt = clock.instant(),
                    syncState = NoteSyncState.SAVED_LOCALLY,
                )
            }
        ) return
        local.toggleFavorite(id)
        publish()
    }

    override suspend fun moveToTrash(id: NoteId) {
        if (updateWorkingCopy(id) { current ->
                editingNotes.remove(id)
                current.copy(
                    state = NoteState.TRASHED,
                    trashedAt = clock.instant(),
                    updatedAt = clock.instant(),
                    syncState = NoteSyncState.SAVED_LOCALLY,
                    pendingRemoteDeletion = current.remoteId.isNotBlank(),
                )
            }
        ) return
        local.moveToTrash(id)
        publish()
    }

    override suspend fun restore(id: NoteId) {
        if (updateWorkingCopy(id) { current ->
                current.copy(
                    state = NoteState.ACTIVE,
                    trashedAt = null,
                    updatedAt = clock.instant(),
                    syncState = NoteSyncState.SAVED_LOCALLY,
                    pendingRemoteDeletion = false,
                )
            }
        ) return
        local.restore(id)
        publish()
    }

    override suspend fun deletePermanently(id: NoteId) {
        val external = mutex.withLock { workingCopies[id] }
        if (external != null && external.remoteId.isNotBlank()) {
            updateWorkingCopy(id) { current ->
                current.copy(
                    state = NoteState.TRASHED,
                    trashedAt = current.trashedAt ?: clock.instant(),
                    pendingRemoteDeletion = true,
                    syncState = NoteSyncState.SAVED_LOCALLY,
                )
            }
            synchronize(id)
            val afterDelete = mutex.withLock { workingCopies[id] }
            check(afterDelete?.remoteId.isNullOrBlank() && afterDelete?.pendingRemoteDeletion != true) {
                "The Source did not confirm deletion"
            }
        }
        val removed = mutex.withLock {
            val present = workingCopies.remove(id) ?: return@withLock false
            editingNotes.remove(id)
            metadataPath(present.id).deleteIfExists()
            contentPath(present.id).deleteIfExists()
            basePath(present.id).deleteIfExists()
            publishLocked()
            true
        }
        if (!removed) {
            local.deletePermanently(id)
            publish()
        }
    }

    override suspend fun close(id: NoteId) {
        val handled = mutex.withLock {
            editingNotes.remove(id)
            val current = workingCopies[id] ?: return@withLock false
            if (current.state == NoteState.DRAFT && current.markdown.isBlank() && !current.titleFixed) {
                workingCopies.remove(id)
                metadataPath(id).deleteIfExists()
                contentPath(id).deleteIfExists()
                basePath(id).deleteIfExists()
            } else if (!current.titleFixed) {
                val fixed = current.copy(titleFixed = true)
                persist(fixed)
                workingCopies[id] = fixed
            }
            publishLocked()
            true
        }
        if (!handled) {
            local.close(id)
            publish()
        }
    }

    private suspend fun updateWorkingCopy(
        id: NoteId,
        transform: (WorkingCopy) -> WorkingCopy,
    ): Boolean = mutex.withLock {
        val current = workingCopies[id] ?: return@withLock false
        val updated = transform(current)
        persist(updated)
        workingCopies[id] = updated
        publishLocked()
        true
    }

    private fun reconcile(sourceId: SourceId, remoteNotes: List<SourceNote>) {
        val remoteIds = remoteNotes.mapTo(hashSetOf(), SourceNote::remoteId)
        remoteNotes.forEach { remote ->
            val id = stableWorkingCopyId(sourceId, remote.remoteId)
            val existing = workingCopies.values.firstOrNull {
                it.sourceId == sourceId && it.remoteId == remote.remoteId
            } ?: workingCopies[id]
            val uncertainCreate = if (existing == null) {
                workingCopies.values.firstOrNull {
                    it.sourceId == sourceId && it.remoteId.isBlank() && it.uncertainWrite?.matches(remote) == true
                }
            } else null
            val reconciled = when {
                uncertainCreate != null -> uncertainCreate.link(remote)
                existing == null -> WorkingCopy.fromRemote(id, sourceId, remote)
                existing.revision == remote.revision -> existing
                existing.syncState == NoteSyncState.SYNCED && existing.id in editingNotes -> existing
                existing.syncState == NoteSyncState.SYNCED -> existing.adopt(remote, NoteSyncState.SYNCED)
                // The Source holds this device's own latest write, confirmed late.
                remote.matches(existing) && !existing.pendingRemoteDeletion ->
                    existing.adopt(remote, NoteSyncState.SYNCED).copy(titleFixed = existing.titleFixed)
                // A lost write or a revision bump without content changes: keep the
                // pending edits and send them against the new revision.
                // This also clears a Conflict that was only ever caused by such a write.
                remote.markdown == existing.baseMarkdown || remote.matchesContent(existing) ||
                    existing.uncertainWrite?.matches(remote) == true -> existing.copy(
                    revision = remote.revision,
                    baseMarkdown = remote.markdown,
                    readOnly = remote.readOnly,
                    syncState = NoteSyncState.SAVED_LOCALLY,
                    uncertainWrite = null,
                )
                else -> existing.copy(syncState = NoteSyncState.NEEDS_ATTENTION)
            }
            persist(reconciled)
            workingCopies[reconciled.id] = reconciled
        }
        workingCopies.values
            .filter { it.sourceId == sourceId && it.remoteId.isNotBlank() && it.remoteId !in remoteIds }
            .toList()
            .forEach { existing ->
                val reconciled = if (existing.syncState == NoteSyncState.SYNCED && existing.id !in editingNotes) {
                    existing.copy(
                        remoteId = "",
                        revision = "",
                        state = NoteState.TRASHED,
                        trashedAt = existing.trashedAt ?: clock.instant(),
                        pendingRemoteDeletion = false,
                    )
                } else {
                    existing.copy(syncState = NoteSyncState.NEEDS_ATTENTION)
                }
                persist(reconciled)
                workingCopies[reconciled.id] = reconciled
            }
    }

    private fun publish() {
        mutableLibrary.value = snapshot()
    }

    private fun publishLocked() {
        mutableLibrary.value = snapshot()
    }

    private fun snapshot(): LibrarySnapshot {
        val external = workingCopies.values
        val localSnapshot = local.library.value
        fun externalFor(state: NoteState) = external
            .filter { it.state == state }
            .map(WorkingCopy::toSummary)
        return LibrarySnapshot(
            active = (localSnapshot.active.filterNot { it.id in workingCopies } + externalFor(NoteState.ACTIVE))
                .sortedByDescending(NoteSummary::updatedAt),
            trash = (localSnapshot.trash.filterNot { it.id in workingCopies } + externalFor(NoteState.TRASHED))
                .sortedByDescending(NoteSummary::updatedAt),
            categories = localSnapshot.categories + retainedCategories,
        )
    }

    private fun persist(note: WorkingCopy) {
        writeTextAtomically(contentPath(note.id), note.markdown)
        writeTextAtomically(basePath(note.id), note.baseMarkdown)
        val properties = Properties().apply {
            setProperty("schema", "1")
            setProperty("id", note.id.value)
            setProperty("sourceId", note.sourceId.value)
            setProperty("remoteId", note.remoteId)
            setProperty("revision", note.revision)
            setProperty("title", note.title)
            setProperty("category", note.category)
            setProperty("favorite", note.favorite.toString())
            setProperty("pinned", note.pinned.toString())
            setProperty("state", note.state.name)
            setProperty("createdAt", note.createdAt.toString())
            setProperty("updatedAt", note.updatedAt.toString())
            setProperty("trashedAt", note.trashedAt?.toString().orEmpty())
            setProperty("titleFixed", note.titleFixed.toString())
            setProperty("readOnly", note.readOnly.toString())
            setProperty("syncState", note.syncState.name)
            setProperty("pendingRemoteDeletion", note.pendingRemoteDeletion.toString())
            setProperty("syncFailed", note.syncFailed.toString())
            note.uncertainWrite?.let { attempt ->
                setProperty("uncertainWriteTitle", attempt.title)
                setProperty("uncertainWriteCategory", attempt.category)
                setProperty("uncertainWriteMarkdownSha256", attempt.markdownSha256)
            }
        }
        writePropertiesAtomically(metadataPath(note.id), properties)
    }

    private fun metadataPath(id: NoteId) = metadataRoot.resolve("${id.value}.properties")
    private fun contentPath(id: NoteId) = contentRoot.resolve("${id.value}.md")
    private fun basePath(id: NoteId) = baseRoot.resolve("${id.value}.md")

    private fun SourceNote.matchesContent(copy: WorkingCopy) = markdown == copy.markdown

    private fun SourceNote.matchesMetadata(copy: WorkingCopy) =
        title == copy.title && category == copy.category && favorite == copy.favorite

    private fun SourceNote.matches(copy: WorkingCopy) = matchesContent(copy) && matchesMetadata(copy)

    private data class SyncRequest(val source: Source, val workingCopy: WorkingCopy)
    private sealed interface SyncOutcome {
        data class Upserted(val note: SourceNote) : SyncOutcome
        data object Deleted : SyncOutcome
    }

    companion object {
        private const val DEFAULT_TITLE = "New note"
        private const val CATEGORIES_FILE = "categories.tsv"

        private fun loadRetainedCategories(root: Path): Map<SourceId, Set<String>> {
            val file = root.resolve(CATEGORIES_FILE)
            if (!file.exists()) return emptyMap()
            return file.readText().lineSequence()
                .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() } }
                .groupBy({ SourceId(it[0]) }, { it[1] })
                .mapValues { (_, paths) -> paths.toSet() }
        }
        private const val MAX_TITLE_CODE_POINTS = 80

        fun open(
            localRoot: Path,
            workingCopyRoot: Path,
            clock: Clock = Clock.systemUTC(),
        ): ConnectedNotesEngine {
            val normalizedWorkingRoot = workingCopyRoot.toAbsolutePath().normalize()
            normalizedWorkingRoot.resolve("notes").createDirectories()
            normalizedWorkingRoot.resolve("content").createDirectories()
            normalizedWorkingRoot.resolve("base").createDirectories()
            return ConnectedNotesEngine(
                local = LocalNotesEngine.open(localRoot, clock),
                workingCopyRoot = normalizedWorkingRoot,
                clock = clock,
                loadedWorkingCopies = loadWorkingCopies(normalizedWorkingRoot),
            )
        }

        private fun loadWorkingCopies(root: Path): Map<NoteId, WorkingCopy> {
            val metadataRoot = root.resolve("notes")
            if (!metadataRoot.exists()) return emptyMap()
            return Files.list(metadataRoot).use { paths ->
                paths.iterator().asSequence().filter { it.isRegularFile() && it.extension == "properties" }
                    .map { path -> runCatching { readWorkingCopy(root, path) }.getOrNull() }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
                    .associateBy(WorkingCopy::id)
            }
        }

        private fun readWorkingCopy(root: Path, metadataPath: Path): WorkingCopy {
            val properties = Properties()
            Files.newBufferedReader(metadataPath).use(properties::load)
            require(properties.getProperty("schema") == "1")
            val id = NoteId(properties.getProperty("id") ?: metadataPath.nameWithoutExtension)
            return WorkingCopy(
                id = id,
                sourceId = SourceId(requireNotNull(properties.getProperty("sourceId"))),
                remoteId = properties.getProperty("remoteId", ""),
                revision = properties.getProperty("revision", ""),
                title = properties.getProperty("title", DEFAULT_TITLE),
                markdown = root.resolve("content/${id.value}.md").takeIf(Path::exists)?.readText().orEmpty(),
                category = normalizeCategory(properties.getProperty("category", "")),
                favorite = properties.getProperty("favorite", "false").toBoolean(),
                pinned = properties.getProperty("pinned", "false").toBoolean(),
                state = NoteState.valueOf(properties.getProperty("state", NoteState.ACTIVE.name)),
                createdAt = Instant.parse(properties.getProperty("createdAt")),
                updatedAt = Instant.parse(properties.getProperty("updatedAt")),
                trashedAt = properties.getProperty("trashedAt").takeUnless(String?::isNullOrBlank)?.let(Instant::parse),
                titleFixed = properties.getProperty("titleFixed", "true").toBoolean(),
                readOnly = properties.getProperty("readOnly", "false").toBoolean(),
                syncState = NoteSyncState.valueOf(
                    properties.getProperty("syncState", NoteSyncState.SAVED_LOCALLY.name),
                ).let { state ->
                    if (state == NoteSyncState.SYNCING) NoteSyncState.SAVED_LOCALLY else state
                },
                baseMarkdown = root.resolve("base/${id.value}.md").takeIf(Path::exists)?.readText().orEmpty(),
                pendingRemoteDeletion = properties.getProperty("pendingRemoteDeletion", "false").toBoolean(),
                syncFailed = properties.getProperty("syncFailed", "false").toBoolean(),
                uncertainWrite = properties.getProperty("uncertainWriteMarkdownSha256")?.let { hash ->
                    WriteAttempt(
                        title = properties.getProperty("uncertainWriteTitle", ""),
                        category = properties.getProperty("uncertainWriteCategory", ""),
                        markdownSha256 = hash,
                    )
                },
            )
        }

        private fun stableWorkingCopyId(sourceId: SourceId, remoteId: String): NoteId = NoteId(
            UUID.nameUUIDFromBytes(
                "${sourceId.value}\n$remoteId".toByteArray(StandardCharsets.UTF_8),
            ).toString(),
        )

        private fun automaticTitle(markdown: String): String {
            val firstLine = markdown.lineSequence().firstOrNull { it.isNotBlank() } ?: return DEFAULT_TITLE
            val clean = firstLine
                .replace(Regex("^\\s{0,3}(#{1,6}|>|[-+*]|\\d+[.)])\\s+"), "")
                .replace(Regex("^\\[[ xX]]\\s+"), "")
                .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_~`]+"), "")
                .trim()
            return normalizeTitle(clean.ifBlank { DEFAULT_TITLE })
        }

        private fun normalizeTitle(value: String): String {
            val singleLine = value.lineSequence().firstOrNull().orEmpty().trim().ifBlank { DEFAULT_TITLE }
            val points = singleLine.codePoints().toArray()
            return if (points.size <= MAX_TITLE_CODE_POINTS) singleLine else String(points, 0, MAX_TITLE_CODE_POINTS)
        }

        private fun normalizeCategory(value: String): String = value
            .replace('\\', '/')
            .split('/')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { part ->
                part.replace(Regex("[\\p{Cc}\\\\/:*?\"<>|]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim(' ', '.')
            }
            .filter(String::isNotBlank)
            .joinToString("/")

        private fun writeTextAtomically(path: Path, content: String) {
            path.parent.createDirectories()
            val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
            try {
                Files.newBufferedWriter(
                    temporary,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { writer -> writer.write(content) }
                moveAtomically(temporary, path)
            } finally {
                temporary.deleteIfExists()
            }
        }

        private fun writePropertiesAtomically(path: Path, properties: Properties) {
            path.parent.createDirectories()
            val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
            try {
                Files.newBufferedWriter(
                    temporary,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { writer: Writer -> properties.store(writer, null) }
                moveAtomically(temporary, path)
            } finally {
                temporary.deleteIfExists()
            }
        }

        private fun moveAtomically(from: Path, to: Path) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

private data class WorkingCopy(
    val id: NoteId,
    val sourceId: SourceId,
    val remoteId: String,
    val revision: String,
    val title: String,
    val markdown: String,
    val category: String,
    val favorite: Boolean,
    val state: NoteState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val trashedAt: Instant?,
    val titleFixed: Boolean,
    val readOnly: Boolean,
    val syncState: NoteSyncState,
    val baseMarkdown: String,
    val pendingRemoteDeletion: Boolean,
    val pinned: Boolean = false,
    val syncFailed: Boolean = false,
    /** What an issued write sent while its Source response is still unconfirmed. */
    val uncertainWrite: WriteAttempt? = null,
) {
    fun toPublic() = Note(
        id = id,
        sourceId = sourceId,
        syncState = syncState,
        title = title,
        markdown = markdown,
        category = category,
        favorite = favorite,
        pinned = pinned,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt,
        trashedAt = trashedAt,
        syncFailed = syncFailed,
    )

    fun toSummary() = NoteSummary(
        id = id,
        sourceId = sourceId,
        syncState = syncState,
        title = title,
        snippet = markdown.lineSequence()
            .dropWhile(String::isBlank)
            .drop(1)
            .firstOrNull(String::isNotBlank)
            ?.replace(Regex("[*_~`#>\\[\\]]"), "")
            ?.trim()
            .orEmpty(),
        previewMarkdown = notePreviewMarkdown(markdown, title),
        category = category,
        favorite = favorite,
        pinned = pinned,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncFailed = syncFailed,
    )

    fun toSourceDraft() = SourceNoteDraft(
        title = title,
        markdown = markdown,
        category = category,
        favorite = favorite,
        modifiedAt = updatedAt,
    )

    fun adopt(remote: SourceNote, syncState: NoteSyncState) = copy(
        remoteId = remote.remoteId,
        revision = remote.revision,
        title = remote.title,
        markdown = remote.markdown,
        category = remote.category,
        favorite = remote.favorite,
        updatedAt = remote.modifiedAt,
        readOnly = remote.readOnly,
        syncState = syncState,
        baseMarkdown = remote.markdown,
        pendingRemoteDeletion = false,
        state = if (state == NoteState.TRASHED) state else NoteState.ACTIVE,
        syncFailed = false,
        uncertainWrite = null,
    )

    /** Links an unconfirmed create to the Source Note it produced, keeping later edits pending. */
    fun link(remote: SourceNote): WorkingCopy {
        val unchanged = remote.markdown == markdown && remote.category == category && remote.favorite == favorite &&
            state != NoteState.TRASHED
        return if (unchanged) {
            adopt(remote, NoteSyncState.SYNCED).copy(titleFixed = titleFixed, updatedAt = updatedAt)
        } else {
            copy(
                remoteId = remote.remoteId,
                revision = remote.revision,
                baseMarkdown = remote.markdown,
                readOnly = remote.readOnly,
                uncertainWrite = null,
                pendingRemoteDeletion = state == NoteState.TRASHED,
                syncState = NoteSyncState.SAVED_LOCALLY,
            )
        }
    }

    companion object {
        fun fromRemote(id: NoteId, sourceId: SourceId, remote: SourceNote) = WorkingCopy(
            id = id,
            sourceId = sourceId,
            remoteId = remote.remoteId,
            revision = remote.revision,
            title = remote.title,
            markdown = remote.markdown,
            category = remote.category,
            favorite = remote.favorite,
            state = NoteState.ACTIVE,
            createdAt = remote.modifiedAt,
            updatedAt = remote.modifiedAt,
            trashedAt = null,
            titleFixed = true,
            readOnly = remote.readOnly,
            syncState = NoteSyncState.SYNCED,
            baseMarkdown = remote.markdown,
            pendingRemoteDeletion = false,
        )
    }
}

private data class WriteAttempt(
    val title: String,
    val category: String,
    val markdownSha256: String,
) {
    /** Sources may rename a create to keep titles unique, for example "Title (2)". */
    fun matches(remote: SourceNote): Boolean {
        if (sha256(remote.markdown) != markdownSha256) return false
        if (remote.category != category) return false
        return remote.title == title ||
            remote.title.matches(Regex(Regex.escape(title) + " \\(\\d+\\)")) ||
            remote.markdown.isNotBlank()
    }

    companion object {
        fun of(copy: WorkingCopy) = WriteAttempt(copy.title, copy.category, sha256(copy.markdown))

        private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
