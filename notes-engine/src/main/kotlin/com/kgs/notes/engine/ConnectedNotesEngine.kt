package com.kgs.notes.engine

import java.io.InputStream
import java.io.StringReader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns local Notes plus durable, offline-first Working Copies for external Sources.
 * Source implementations stay responsible for protocol choreography; this class never persists credentials.
 *
 * Durability rules:
 * - Each Working Copy is one record (schema 2) holding body, synchronized base and sync metadata; it is replaced
 *   atomically, so a crash leaves either the previous or the next complete generation.
 * - Every issued create/update is recorded before it is sent; such attempts are kept until the Source state is
 *   known, so a lost response is recognized as this device's own write instead of a conflict or a duplicate.
 * - A remote Note is only adopted as the result of an uncertain create when its identity is proven by the whole
 *   payload; otherwise the ambiguity is kept for manual recovery.
 * - Ownership transfers are recorded (transfer marker) before the first remote mutation; the source side stays
 *   as a hidden rollback copy until the destination confirms.
 *
 * Lock order: [transfers] → per-Source operation lock → per-Note ownership lock → [mutex] → Local engine lock.
 */
class ConnectedNotesEngine private constructor(
    private val local: LocalNotesEngine,
    private val workingCopyRoot: Path,
    private val clock: Clock,
    loadedWorkingCopies: Map<NoteId, WorkingCopy>,
    loadedUnreadable: List<UnreadableRecord>,
    private val quarantinedIds: Set<NoteId>,
) : NotesEngine {
    private val metadataRoot = workingCopyRoot.resolve("notes")
    private val contentRoot = workingCopyRoot.resolve("content")
    private val baseRoot = workingCopyRoot.resolve("base")
    private val recoveryRoot = workingCopyRoot.resolve(RECOVERY_DIRECTORY)
    private val mutex = Mutex()
    private val sourceOperations = ConcurrentHashMap<SourceId, Mutex>()
    private val ownership = ConcurrentHashMap<NoteId, Mutex>()
    private val transfers = Mutex()
    private val workingCopies = loadedWorkingCopies.toMutableMap()
    private val editingNotes = mutableSetOf<NoteId>()
    private val attachedSources = linkedMapOf<SourceId, Source>()
    /** Sources whose complete snapshot was reconciled in this process; creates need it to know pre-existing Notes. */
    private val refreshedSources: MutableSet<Source> = Collections.newSetFromMap(IdentityHashMap())
    /** When each Source's last complete snapshot was reconciled; throttles re-checks triggered by saves. */
    private val snapshotTimes = IdentityHashMap<Source, Instant>()
    /** External Sources cannot store an empty Category, so this device keeps it until removed. */
    private var retainedCategories: Map<SourceId, Set<String>> = loadRetainedCategories(workingCopyRoot)
    private val mutableLibrary = MutableStateFlow(snapshot())
    private val mutableSources = MutableStateFlow(local.sources.value)
    private var workingCopyRecords = loadedUnreadable
    private val mutableUnreadable = MutableStateFlow(local.unreadableRecords.value + workingCopyRecords)

    override val library: StateFlow<LibrarySnapshot> = mutableLibrary.asStateFlow()
    override val sources: StateFlow<List<SourceDescriptor>> = mutableSources.asStateFlow()
    override val unreadableRecords: StateFlow<List<UnreadableRecord>> = mutableUnreadable.asStateFlow()

    private fun sourceLock(id: SourceId) = sourceOperations.computeIfAbsent(id) { Mutex() }
    private fun ownershipLock(id: NoteId) = ownership.computeIfAbsent(id) { Mutex() }

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
                base = SyncBase.NONE,
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
        attachedSources[source.descriptor.id]?.let(refreshedSources::remove)
        attachedSources[source.descriptor.id] = source
        mutableSources.value = local.sources.value + attachedSources.values.map(Source::descriptor)
    }

    override suspend fun removeSource(id: SourceId) {
        require(id != LocalSourceId) { "The Local Source cannot be removed" }
        // Serialized with transfers and synchronization of this Source: none can be mid-flight below.
        transfers.withLock {
            sourceLock(id).withLock {
                mutex.withLock {
                    val copies = workingCopies.values.filter { it.sourceId == id }
                    check(copies.none { it.id in editingNotes }) { "Close this Source's Notes before removing it" }
                    check(copies.all(WorkingCopy::isSettled)) {
                        "Save, move, or export pending Notes before removing this Source"
                    }
                    copies.forEach { removeWorkingCopyLocked(it.id) }
                    attachedSources.remove(id)?.let(refreshedSources::remove)
                    retainCategories(retainedCategories - id)
                    mutableSources.value = local.sources.value + attachedSources.values.map(Source::descriptor)
                    publishLocked()
                }
            }
        }
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
                sourceLock(source.descriptor.id).withLock {
                    withContext(NonCancellable) { refreshAndReconcile(source) }
                }
                currentCoroutineContext().ensureActive()
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

    /** Callers hold this Source's operation lock. Throws when the Source cannot be read. */
    private suspend fun refreshAndReconcile(source: Source) {
        source.refresh()
        mutex.withLock {
            if (attachedSources[source.descriptor.id] !== source) return@withLock
            reconcile(source.descriptor.id, source.notes.value)
            refreshedSources += source
            snapshotTimes[source] = clock.instant()
            publishLocked()
        }
    }

    /** Re-checking a Note that waits for remote changes needs at most one snapshot per [RECHECK_INTERVAL_SECONDS]. */
    private suspend fun snapshotIsRecent(source: Source): Boolean = mutex.withLock {
        val at = snapshotTimes[source] ?: return@withLock false
        java.time.Duration.between(at, clock.instant()).seconds < RECHECK_INTERVAL_SECONDS
    }

    override suspend fun synchronize(id: NoteId) {
        val sourceId = mutex.withLock { workingCopies[id]?.sourceId } ?: return
        sourceLock(sourceId).withLock {
            // Finish an issued write and retain the returned remote identity even
            // if its caller is cancelled. A replacement create must not duplicate it.
            withContext(NonCancellable) { synchronizeRemote(id) }
        }
        currentCoroutineContext().ensureActive()
    }

    /** Callers hold this Note's Source operation lock. */
    private suspend fun synchronizeRemote(id: NoteId) {
        repeat(MAX_WRITE_ROUNDS) { round ->
            val start = mutex.withLock { workingCopies[id] } ?: return
            val source = mutex.withLock { attachedSources[start.sourceId] } ?: return
            // A Conflict or a missing remote Note can resolve remotely; look again before writing.
            if (round == 0 && (start.problem == NoteAttention.CONFLICT || start.problem == NoteAttention.REMOTE_MISSING ||
                    start.contentUnavailable) && !snapshotIsRecent(source)
            ) {
                if (!refreshOrMarkOffline(id, source)) return
            }
            if (!resolveUncertainCreate(id, source)) return
            val needsSnapshot = mutex.withLock {
                val current = workingCopies[id] ?: return
                current.remoteId.isBlank() && source !in refreshedSources && current.needsWrite()
            }
            if (needsSnapshot) {
                // Creates record which remote Notes already existed; that needs one complete snapshot first.
                if (!refreshOrMarkOffline(id, source)) return
                if (!resolveUncertainCreate(id, source)) return
            }
            val request = mutex.withLock { prepareWrite(id, source) } ?: return
            val result = try {
                Result.success(issueWrite(request))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            val failure = result.exceptionOrNull()
            if (failure is SourceConflictException) {
                // A rejected revision is only a Conflict when the Source changed the same fields.
                // Reconcile against a fresh snapshot (field by field, recognizing this device's own
                // unconfirmed writes) and retry with the advanced revision.
                val refreshed = try {
                    refreshAndReconcile(source)
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                val advanced = mutex.withLock {
                    val current = workingCopies[id] ?: return
                    when {
                        !refreshed -> {
                            putLocked(current.copy(syncState = current.syncState.settledAfterSync(), syncFailed = true))
                            return
                        }
                        current.syncState == NoteSyncState.NEEDS_ATTENTION || current.syncState == NoteSyncState.SYNCED -> return
                        current.revision == request.copy.revision && current.remoteId == request.copy.remoteId -> {
                            putLocked(current.copy(syncState = NoteSyncState.NEEDS_ATTENTION, problem = NoteAttention.CONFLICT))
                            return
                        }
                        else -> true
                    }
                }
                if (advanced) return@repeat
            }
            mutex.withLock { applyWriteResult(id, request, result) }
            completeTransfer(id)
            return
        }
    }

    /** Refreshes [source]; on failure marks the Note as waiting for the Source. Returns whether it succeeded. */
    private suspend fun refreshOrMarkOffline(id: NoteId, source: Source): Boolean = try {
        refreshAndReconcile(source)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        mutex.withLock {
            workingCopies[id]?.let { putLocked(it.copy(syncState = it.syncState.settledAfterSync(), syncFailed = true)) }
        }
        false
    }

    private fun prepareWrite(id: NoteId, source: Source): WriteRequest? {
        val current = workingCopies[id] ?: return null
        if (attachedSources[current.sourceId] !== source) return null
        if (current.problem == NoteAttention.UNCERTAIN_CREATE || current.contentUnavailable) return null
        if (current.problem == NoteAttention.CONFLICT || current.problem == NoteAttention.REMOTE_MISSING) return null
        if (current.state == NoteState.DRAFT && current.markdown.isBlank() && !current.titleFixed) return null
        if (current.syncState == NoteSyncState.SYNCED) return null
        if (current.state == NoteState.TRASHED && current.remoteId.isBlank() && !current.hasCreateAttempts()) {
            // Never published: Trash keeps it on this device only.
            putLocked(current.copy(syncState = NoteSyncState.SYNCED, pendingRemoteDeletion = false, syncFailed = false))
            return null
        }
        if (current.readOnly) {
            val favoriteOnly = current.remoteId.isNotBlank() && current.favorite != current.base.favorite
            val otherChanges = current.hasNonFavoriteChanges()
            if (!favoriteOnly) {
                putLocked(
                    if (otherChanges) {
                        current.copy(syncState = NoteSyncState.NEEDS_ATTENTION, problem = NoteAttention.READ_ONLY_CHANGES)
                    } else {
                        current.copy(syncState = NoteSyncState.SYNCED, problem = null, syncFailed = false)
                    },
                )
                return null
            }
            val syncing = current.copy(syncState = NoteSyncState.SYNCING)
            putLocked(syncing)
            return WriteRequest(source, syncing, WriteKind.FAVORITE, WriteAttempt.favoriteOnly(current.favorite))
        }
        val kind = when {
            current.pendingRemoteDeletion && current.remoteId.isNotBlank() -> WriteKind.DELETE
            current.remoteId.isBlank() -> WriteKind.CREATE
            else -> WriteKind.UPDATE
        }
        // Record what a write sends before issuing it. If its response is lost, later attempts
        // recognize the Source copy as this device's own write instead of publishing a duplicate
        // or reporting a Conflict. Earlier unconfirmed attempts stay until the Source state is known.
        val attempt = when (kind) {
            WriteKind.CREATE -> WriteAttempt.of(current, WriteKind.CREATE, knownRemoteIdsLocked(current.sourceId, source))
            WriteKind.UPDATE -> WriteAttempt.of(current, WriteKind.UPDATE, null)
            else -> null
        }
        val syncing = current.copy(
            syncState = NoteSyncState.SYNCING,
            attempts = if (attempt == null) current.attempts else (current.attempts.filterNot { it == attempt } + attempt).takeLast(MAX_ATTEMPTS),
        )
        putLocked(syncing)
        return WriteRequest(source, syncing, kind, attempt)
    }

    private fun knownRemoteIdsLocked(sourceId: SourceId, source: Source): Set<String> =
        source.notes.value.mapTo(linkedSetOf(), SourceNote::remoteId) +
            workingCopies.values.filter { it.sourceId == sourceId && it.remoteId.isNotBlank() }.map(WorkingCopy::remoteId)

    private suspend fun issueWrite(request: WriteRequest): SyncOutcome {
        val copy = request.copy
        return when (request.kind) {
            WriteKind.DELETE -> {
                request.source.deleteNote(remoteId = copy.remoteId, expectedRevision = copy.revision)
                SyncOutcome.Deleted
            }
            WriteKind.CREATE -> SyncOutcome.Upserted(request.source.createNote(copy.toSourceDraft()))
            WriteKind.UPDATE -> SyncOutcome.Upserted(
                request.source.updateNote(remoteId = copy.remoteId, expectedRevision = copy.revision, note = copy.toSourceDraft()),
            )
            WriteKind.FAVORITE -> SyncOutcome.Upserted(request.source.updateFavorite(copy.remoteId, copy.favorite))
        }
    }

    private fun applyWriteResult(id: NoteId, request: WriteRequest, result: Result<SyncOutcome>) {
        val current = workingCopies[id] ?: return
        val outcome = result.getOrElse { failure ->
            val safe = when {
                failure is SourceConflictException ->
                    current.copy(syncState = NoteSyncState.NEEDS_ATTENTION, problem = NoteAttention.CONFLICT)
                request.kind == WriteKind.FAVORITE && failure is UnsupportedOperationException ->
                    current.copy(syncState = NoteSyncState.NEEDS_ATTENTION, problem = NoteAttention.READ_ONLY_CHANGES)
                // The write may or may not have reached the Source: its attempt stays recorded.
                else -> current.copy(syncState = current.syncState.settledAfterSync(), syncFailed = true)
            }
            putLocked(safe)
            return
        }
        val synced = when (outcome) {
            is SyncOutcome.Upserted -> {
                val confirmed = outcome.note.let { note ->
                    // A write response whose body could not be read still confirms what was sent.
                    if (note.contentUnavailable) {
                        note.copy(markdown = request.copy.markdown, readOnly = current.readOnly, contentUnavailable = false)
                    } else note
                }
                when (val merged = merge(current, confirmed, request.attempt, confirmed = true)) {
                    is Merge.Conflicted -> merged.copy
                    is Merge.Merged -> merged.copy.copy(
                        titleFixed = current.titleFixed || request.kind != WriteKind.FAVORITE,
                    )
                }
            }
            SyncOutcome.Deleted -> current.copy(
                remoteId = "",
                revision = "",
                syncState = if (current.state == NoteState.TRASHED) NoteSyncState.SYNCED else NoteSyncState.SAVED_LOCALLY,
                pendingRemoteDeletion = false,
                syncFailed = false,
                attempts = emptyList(),
                problem = null,
                base = SyncBase.NONE,
            )
        }
        putLocked(synced)
    }

    /**
     * Looks for the Source copy of a create whose response never arrived. Returns false when the
     * outcome is still unknown (Source unreachable or identity ambiguous), so no create is issued blindly.
     * Callers hold this Source's operation lock.
     */
    private suspend fun resolveUncertainCreate(id: NoteId, source: Source): Boolean {
        val needed = mutex.withLock {
            val current = workingCopies[id] ?: return true
            current.remoteId.isBlank() && (current.hasCreateAttempts() || current.problem == NoteAttention.UNCERTAIN_CREATE)
        }
        if (!needed) return true
        val alreadyAmbiguous = mutex.withLock { workingCopies[id]?.problem == NoteAttention.UNCERTAIN_CREATE }
        if (alreadyAmbiguous == true && snapshotIsRecent(source)) return false
        if (!refreshOrMarkOffline(id, source)) return false
        return mutex.withLock {
            val current = workingCopies[id] ?: return@withLock true
            current.problem != NoteAttention.UNCERTAIN_CREATE
        }
    }

    // ---------------------------------------------------------------- reconciliation

    /** Applies a complete Source snapshot. Callers hold [mutex] and this Source's operation lock. */
    private suspend fun reconcile(sourceId: SourceId, remoteNotes: List<SourceNote>) {
        resolveCreatesLocked(sourceId, remoteNotes)
        val remoteIds = remoteNotes.mapTo(hashSetOf(), SourceNote::remoteId)
        remoteNotes.forEach { remote ->
            val id = stableWorkingCopyId(sourceId, remote.remoteId)
            val existing = workingCopies.values.firstOrNull { it.sourceId == sourceId && it.remoteId == remote.remoteId }
                ?: workingCopies[id]?.takeIf { it.sourceId == sourceId && it.remoteId.isBlank() && !it.hasCreateAttempts() }
            if (existing == null) {
                // Never overwrite quarantined evidence or another record (such as a Note moved to Local) owning this id.
                val newId = if (id in quarantinedIds || workingCopies.containsKey(id) || local.note(id) != null) {
                    NoteId(UUID.randomUUID().toString())
                } else id
                putLocked(
                    if (remote.contentUnavailable) WorkingCopy.placeholder(newId, sourceId, remote) else WorkingCopy.fromRemote(newId, sourceId, remote),
                    publish = false,
                )
                return@forEach
            }
            val reconciled = reconcileExisting(existing, remote)
            if (reconciled != existing) putLocked(reconciled, publish = false)
        }
        workingCopies.values
            .filter { it.sourceId == sourceId && it.remoteId.isNotBlank() && it.remoteId !in remoteIds }
            .toList()
            .forEach { existing ->
                when {
                    existing.transfer == Transfer.TO_LOCAL -> finishMoveToLocalLocked(existing.id)
                    // An authoritative absence confirms a pending deletion, including one whose response was lost.
                    existing.pendingRemoteDeletion -> putLocked(
                        existing.copy(
                            remoteId = "",
                            revision = "",
                            pendingRemoteDeletion = false,
                            syncState = if (existing.state == NoteState.TRASHED) NoteSyncState.SYNCED else NoteSyncState.SAVED_LOCALLY,
                            syncFailed = false,
                            attempts = emptyList(),
                            problem = null,
                            base = SyncBase.NONE,
                        ),
                        publish = false,
                    )
                    existing.syncState == NoteSyncState.SYNCED && existing.id !in editingNotes && !existing.hasLocalChanges() ->
                        putLocked(
                            existing.copy(
                                remoteId = "",
                                revision = "",
                                state = NoteState.TRASHED,
                                trashedAt = existing.trashedAt ?: clock.instant(),
                                pendingRemoteDeletion = false,
                            ),
                            publish = false,
                        )
                    existing.problem != NoteAttention.REMOTE_MISSING -> putLocked(
                        existing.copy(syncState = NoteSyncState.NEEDS_ATTENTION, problem = NoteAttention.REMOTE_MISSING),
                        publish = false,
                    )
                }
            }
        workingCopies.values.filter { it.sourceId == sourceId && it.transfer != null }.toList().forEach { copy ->
            when (copy.transfer) {
                Transfer.TO_EXTERNAL -> completeTransferLocked(copy.id)
                // The remote Note survived an interrupted move to Local: it stays authoritative.
                Transfer.TO_LOCAL -> if (copy.remoteId.isNotBlank() && copy.remoteId in remoteIds) {
                    discardShadowLocked(copy.id)
                    putLocked(copy.copy(transfer = null), publish = false)
                }
                null -> Unit
            }
        }
    }

    private fun reconcileExisting(existing: WorkingCopy, remote: SourceNote): WorkingCopy = when {
        // A read error never replaces the last good body, base, revision or permissions.
        remote.contentUnavailable -> if (existing.contentUnavailable) existing else existing.copy(contentUnavailable = true)
        existing.revision == remote.revision && !existing.contentUnavailable ->
            if (existing.problem == NoteAttention.REMOTE_MISSING) {
                existing.copy(problem = null, syncState = if (existing.hasLocalChanges()) NoteSyncState.SAVED_LOCALLY else NoteSyncState.SYNCED)
            } else existing
        existing.syncState == NoteSyncState.SYNCED && existing.id in editingNotes && !existing.contentUnavailable -> existing
        else -> when (val merged = merge(existing, remote, existing.attempts.lastOrNull { it.matches(remote) })) {
            is Merge.Conflicted -> merged.copy
            // An open editor shows its base; never swap the body underneath it.
            is Merge.Merged -> if (existing.id in editingNotes && merged.copy.markdown != existing.markdown) existing else merged.copy
        }
    }

    /**
     * Links unconfirmed creates of [sourceId] to the remote Note they produced, when that identity is proven
     * by the complete payload. Same-body Notes alone never prove identity. Callers hold [mutex].
     */
    private fun resolveCreatesLocked(sourceId: SourceId, remoteNotes: List<SourceNote>) {
        val pending = workingCopies.values.filter {
            it.sourceId == sourceId && it.remoteId.isBlank() &&
                (it.hasCreateAttempts() || it.problem == NoteAttention.UNCERTAIN_CREATE)
        }
        pending.forEach { copy ->
            val creates = copy.attempts.filter { it.kind == WriteKind.CREATE }
            // Remote Notes owned by another record cannot be this create, unless that record is a
            // clean, automatically imported candidate shown during an earlier ambiguity.
            val owners = workingCopies.values.filter { it.sourceId == sourceId && it.remoteId.isNotBlank() }
                .associateBy(WorkingCopy::remoteId)
            val eligible = remoteNotes.filter { remote ->
                val owner = owners[remote.remoteId]
                owner == null || (owner.id == stableWorkingCopyId(sourceId, remote.remoteId) && owner.isCleanImport() && owner.id !in editingNotes)
            }
            fun unknownBefore(remote: SourceNote, attempt: WriteAttempt) = attempt.knownRemoteIds?.contains(remote.remoteId) != true
            var strong = eligible.filter { remote -> creates.any { unknownBefore(remote, it) && it.matches(remote) } }
            val weak = eligible.filter { remote -> creates.any { unknownBefore(remote, it) && it.couldHaveProduced(remote) } }
            if (strong.size > 1) {
                strong = strong.filter { remote -> creates.any { it.matches(remote) && it.modifiedEpochSecond == remote.modifiedAt.epochSecond } }
                    .takeIf { it.size == 1 } ?: strong
            }
            when {
                strong.size == 1 -> {
                    val remote = strong.single()
                    owners[remote.remoteId]?.takeIf { it.id != copy.id }?.let { removeWorkingCopyLocked(it.id) }
                    val attempt = creates.last { it.matches(remote) }
                    val linked = when (val merged = merge(copy.copy(remoteId = remote.remoteId), remote, attempt)) {
                        is Merge.Merged -> merged.copy
                        is Merge.Conflicted -> merged.copy.copy(remoteId = remote.remoteId, revision = remote.revision)
                    }
                    putLocked(linked.copy(problem = linked.problem.takeUnless { it == NoteAttention.UNCERTAIN_CREATE }), publish = false)
                }
                strong.isEmpty() && weak.isEmpty() -> {
                    // The complete snapshot holds nothing this create could have produced: it never landed.
                    putLocked(
                        copy.copy(
                            attempts = copy.attempts.filterNot { it.kind == WriteKind.CREATE },
                            problem = copy.problem.takeUnless { it == NoteAttention.UNCERTAIN_CREATE },
                            syncState = if (copy.syncState == NoteSyncState.NEEDS_ATTENTION && copy.problem == NoteAttention.UNCERTAIN_CREATE) {
                                NoteSyncState.SAVED_LOCALLY
                            } else copy.syncState,
                        ),
                        publish = false,
                    )
                }
                else -> putLocked(
                    copy.copy(syncState = NoteSyncState.NEEDS_ATTENTION, problem = NoteAttention.UNCERTAIN_CREATE),
                    publish = false,
                )
            }
        }
    }

    /**
     * Three-way merge of [existing] with a newer [remote] snapshot against the last synchronized base.
     * When [own] is set, [remote] holds that write from this device: its fields are adopted unless they were
     * edited again locally, which keeps server adjustments (such as unique titles) and later local edits.
     */
    private fun merge(existing: WorkingCopy, remote: SourceNote, own: WriteAttempt?, confirmed: Boolean = false): Merge {
        val base = existing.base
        var conflict = false
        var remoteChanged = false
        fun <T> field(local: T, remoteValue: T, baseValue: T?, ownSent: Boolean?, localIsOwn: Boolean): T {
            // A confirmed response is this write's result even if the Source adjusted the value.
            if (ownSent == true || (confirmed && ownSent != null)) return if (localIsOwn) remoteValue else local
            return when {
                local == remoteValue -> remoteValue
                baseValue != null && remoteValue == baseValue -> local
                baseValue != null && local == baseValue -> { remoteChanged = true; remoteValue }
                else -> { conflict = true; remoteChanged = true; local }
            }
        }
        val ownMarkdown = own?.markdownSha256?.let { sha256(remote.markdown) == it }
        val markdown = field(existing.markdown, remote.markdown, base.markdown, ownMarkdown,
            own?.markdownSha256 == sha256(existing.markdown))
        val title = if (!existing.titleFixed && markdown == remote.markdown) {
            remote.title
        } else {
            field(existing.title, remote.title, base.title, own?.title?.let { titleMatches(it, remote.title) }, own?.title == existing.title)
        }
        val category = field(existing.category, remote.category, base.category, own?.category?.let { it == remote.category },
            own?.category == existing.category)
        val favorite = field(existing.favorite, remote.favorite, base.favorite, own?.favorite?.let { it == remote.favorite },
            own?.favorite == existing.favorite)
        // Another client changed a Note this device is deleting: keep it and ask instead.
        if (conflict || (existing.pendingRemoteDeletion && remoteChanged && own == null)) {
            return Merge.Conflicted(existing.copy(syncState = NoteSyncState.NEEDS_ATTENTION, problem = NoteAttention.CONFLICT))
        }
        val merged = existing.copy(
            remoteId = remote.remoteId,
            revision = remote.revision,
            title = title,
            markdown = markdown,
            category = category,
            favorite = favorite,
            readOnly = remote.readOnly,
            base = SyncBase(remote.markdown, remote.title, remote.category, remote.favorite),
            attempts = emptyList(),
            contentUnavailable = false,
            syncFailed = false,
            problem = null,
            state = if (existing.state == NoteState.DRAFT && (markdown.isNotBlank() || existing.titleFixed)) NoteState.ACTIVE else existing.state,
            // A create that landed after the Note was trashed must be deleted again.
            pendingRemoteDeletion = existing.pendingRemoteDeletion || (own?.kind == WriteKind.CREATE && existing.state == NoteState.TRASHED),
        )
        val pending = merged.hasLocalChanges()
        return Merge.Merged(
            merged.copy(
                updatedAt = if (pending) existing.updatedAt else remote.modifiedAt,
                syncState = when {
                    remote.readOnly && merged.hasNonFavoriteChanges() -> NoteSyncState.NEEDS_ATTENTION
                    pending -> NoteSyncState.SAVED_LOCALLY
                    else -> NoteSyncState.SYNCED
                },
                problem = if (remote.readOnly && merged.hasNonFavoriteChanges()) NoteAttention.READ_ONLY_CHANGES else null,
            ),
        )
    }

    // ---------------------------------------------------------------- transfers

    override suspend fun moveToSource(id: NoteId, sourceId: SourceId): NoteId = transfers.withLock {
        val initial = requireNotNull(note(id)) { "Cannot move a missing Note" }
        if (initial.sourceId == sourceId) return@withLock id
        require(initial.sourceId == LocalSourceId || sourceId == LocalSourceId) {
            "Move this Note to the Local Source before choosing another external Source"
        }
        if (initial.readOnly) throw ReadOnlyNoteException()
        require(initial.state != NoteState.TRASHED) { "Restore this Note before moving it" }
        val externalId = if (sourceId == LocalSourceId) initial.sourceId else sourceId
        sourceLock(externalId).withLock {
            val source = mutex.withLock {
                requireNotNull(attachedSources[externalId]) { "Connect this Source before moving the Note" }
            }
            require(source.descriptor.capabilities.writable) { "This Source is read-only" }
            // Retain the result of issued writes even when the caller leaves the editor.
            withContext(NonCancellable) {
                if (sourceId == LocalSourceId) moveToLocal(id, source) else moveToExternal(id, sourceId, source)
            }
        }.also { currentCoroutineContext().ensureActive() }
    }

    /** Callers hold the transfer lock and this Source's operation lock. */
    private suspend fun moveToExternal(id: NoteId, sourceId: SourceId, source: Source): NoteId {
        // Know which remote Notes already exist, so a lost create can be identified later.
        if (mutex.withLock { source !in refreshedSources }) refreshAndReconcile(source)
        val installed = ownershipLock(id).withLock {
            mutex.withLock {
                check(attachedSources[sourceId] === source) { "Connect this Source before moving the Note" }
                val original = requireNotNull(local.note(id)) { "Cannot move a missing Note" }
                require(original.state != NoteState.TRASHED) { "Restore this Note before moving it" }
                require(local.managedAttachments(id).isEmpty()) {
                    "Notes with Managed Attachments cannot move to this Source yet"
                }
                // Durable transfer intent: from here on the Note is owned by the destination (listed there as
                // TRANSFER_PENDING), imports into it are refused, and the Local original stays as rollback.
                val pending = WorkingCopy(
                    id = id,
                    sourceId = sourceId,
                    remoteId = "",
                    revision = "",
                    title = original.title,
                    markdown = original.markdown,
                    category = original.category,
                    favorite = original.favorite,
                    pinned = original.pinned,
                    state = if (original.state == NoteState.DRAFT) NoteState.ACTIVE else original.state,
                    createdAt = original.createdAt,
                    updatedAt = original.updatedAt,
                    trashedAt = null,
                    titleFixed = true,
                    readOnly = false,
                    syncState = NoteSyncState.SYNCING,
                    base = SyncBase.NONE,
                    pendingRemoteDeletion = false,
                    transfer = Transfer.TO_EXTERNAL,
                )
                val attempt = WriteAttempt.of(pending, WriteKind.CREATE, knownRemoteIdsLocked(sourceId, source))
                val recorded = pending.copy(attempts = listOf(attempt))
                putLocked(recorded)
                recorded
            }
        }
        val failure = try {
            val confirmed = source.createNote(installed.toSourceDraft())
            mutex.withLock { applyWriteResult(id, WriteRequest(source, installed, WriteKind.CREATE, installed.attempts.single()), Result.success(SyncOutcome.Upserted(confirmed))) }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }
        if (failure == null) {
            completeTransfer(id)
            return id
        }
        // Did the create land? Only a complete snapshot can tell.
        val refreshed = try {
            refreshAndReconcile(source)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        mutex.withLock {
            val after = workingCopies[id] ?: throw failure
            when {
                after.remoteId.isNotBlank() -> Unit
                refreshed && !after.hasCreateAttempts() && after.problem == null -> {
                    // Nothing reached the Source: roll back to the Local original, keeping edits made meanwhile.
                    if (after.toPublic().copy(syncState = installed.syncState) != installed.toPublic()) {
                        local.retainTransferredNote(after.toPublic().copy(sourceId = LocalSourceId, syncState = NoteSyncState.LOCAL_SOURCE))
                    }
                    removeWorkingCopyLocked(id)
                    publishLocked()
                    throw failure
                }
                else -> {
                    putLocked(after.copy(syncState = after.syncState.settledAfterSync(), syncFailed = true))
                    throw TransferPendingException(failure)
                }
            }
        }
        completeTransfer(id)
        return id
    }

    /** Callers hold the transfer lock and this Source's operation lock. */
    private suspend fun moveToLocal(id: NoteId, source: Source): NoteId {
        val original = mutex.withLock {
            val remote = requireNotNull(workingCopies[id]) { "Cannot move a missing Note" }
            if (remote.readOnly) throw ReadOnlyNoteException()
            check(remote.remoteId.isNotBlank() || (!remote.hasCreateAttempts() && remote.problem != NoteAttention.UNCERTAIN_CREATE)) {
                "The Source has not confirmed this Note yet; copy it to the Local Source instead"
            }
            // Durable intent first, then the hidden Local rollback copy.
            val marked = remote.copy(transfer = Transfer.TO_LOCAL)
            putLocked(marked)
            local.retainTransferredNote(marked.toLocalNote())
            marked
        }
        var current = original
        var deleted = current.remoteId.isBlank()
        var conflicted = false
        var rounds = 0
        while (!deleted && !conflicted) {
            mutex.withLock {
                workingCopies[id]?.takeIf { it.transfer != Transfer.TO_LOCAL }?.let { latest ->
                    putLocked(latest.copy(transfer = Transfer.TO_LOCAL))
                    local.retainTransferredNote(latest.toLocalNote())
                }
            }
            try {
                source.deleteNote(current.remoteId, current.revision)
                deleted = true
            } catch (_: SourceConflictException) {
                rounds += 1
                val refreshed = runCatching { refreshAndReconcile(source) }.isSuccess
                val after = mutex.withLock { workingCopies[id] }
                conflicted = !refreshed || after == null || rounds >= MAX_WRITE_ROUNDS ||
                    after.syncState == NoteSyncState.NEEDS_ATTENTION || after.revision == current.revision ||
                    after.base.markdown != current.base.markdown || after.base.title != current.base.title ||
                    after.base.category != current.base.category || after.base.favorite != current.base.favorite
                if (after != null) current = after
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Outcome unknown: the Working Copy stays authoritative with its marker; the next complete
                // snapshot either finishes the move (remote absent) or rolls it back (remote present).
                throw failure
            }
        }
        if (conflicted) {
            mutex.withLock {
                workingCopies[id]?.let { latest ->
                    discardShadowLocked(id)
                    putLocked(latest.copy(transfer = null))
                }
            }
            return keepBothSides(id, source, original)
        }
        mutex.withLock { finishMoveToLocalLocked(id) }
        return id
    }

    /** The remote copy is gone: the newest Working Copy generation becomes the Local Note. Callers hold [mutex]. */
    private suspend fun finishMoveToLocalLocked(id: NoteId) {
        val latest = workingCopies[id] ?: return
        local.retainTransferredNote(latest.toLocalNote())
        editingNotes.remove(id)
        removeWorkingCopyLocked(id)
        publishLocked()
    }

    /**
     * The Source changed concurrently. Keep both sides: the edits move to the Local Source as a new Note, and the
     * Source version stays where it is as an ordinary Working Copy. The Local copy is taken in the same critical
     * section that adopts the remote version, so edits saved while the refresh was awaited are included.
     */
    private suspend fun keepBothSides(id: NoteId, source: Source, original: WorkingCopy): NoteId {
        val refreshed = try {
            source.refresh()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return mutex.withLock {
            val current = workingCopies[id] ?: original
            val localId = NoteId(UUID.randomUUID().toString())
            local.retainTransferredNote(current.toLocalNote().copy(id = localId, state = NoteState.ACTIVE, trashedAt = null))
            val remote = if (refreshed && attachedSources[source.descriptor.id] === source) {
                source.notes.value.firstOrNull { it.remoteId == current.remoteId && current.remoteId.isNotBlank() }
            } else null
            if (workingCopies.containsKey(id) && remote != null && !remote.contentUnavailable) {
                putLocked(
                    current.copy(
                        revision = remote.revision,
                        title = remote.title,
                        markdown = remote.markdown,
                        category = remote.category,
                        favorite = remote.favorite,
                        readOnly = remote.readOnly,
                        updatedAt = remote.modifiedAt,
                        createdAt = original.createdAt,
                        base = SyncBase(remote.markdown, remote.title, remote.category, remote.favorite),
                        syncState = NoteSyncState.SYNCED,
                        problem = null,
                        attempts = emptyList(),
                        pendingRemoteDeletion = false,
                        state = NoteState.ACTIVE,
                        trashedAt = null,
                        syncFailed = false,
                        transfer = null,
                    ),
                )
            }
            editingNotes.remove(id)
            publishLocked()
            localId
        }
    }

    private suspend fun completeTransfer(id: NoteId) = mutex.withLock { completeTransferLocked(id) }

    /** Removes the Local rollback copy once the destination confirmed the Note. Callers hold [mutex]. */
    private suspend fun completeTransferLocked(id: NoteId) {
        val current = workingCopies[id] ?: return
        if (current.transfer != Transfer.TO_EXTERNAL || current.remoteId.isBlank()) return
        // Never delete attachment bytes; the rollback copy stays hidden until they are dealt with.
        if (local.managedAttachments(id).isNotEmpty()) return
        try {
            local.deletePermanently(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return // Retried on the next refresh or reopen; the marker keeps the rollback copy hidden.
        }
        putLocked(current.copy(transfer = null))
    }

    private suspend fun discardShadowLocked(id: NoteId) {
        if (local.note(id) != null && local.managedAttachments(id).isEmpty()) local.deletePermanently(id)
    }

    // ---------------------------------------------------------------- reading and editing

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
                if (current.readOnly) throw ReadOnlyNoteException()
                val title = if (current.titleFixed) current.title else automaticTitle(markdown)
                current.copy(
                    title = title,
                    markdown = markdown,
                    // Trash is an explicit intent that only restore() reverses, as for Local Notes.
                    state = when {
                        current.state == NoteState.TRASHED -> NoteState.TRASHED
                        markdown.isBlank() && !current.titleFixed -> NoteState.DRAFT
                        else -> NoteState.ACTIVE
                    },
                    updatedAt = clock.instant(),
                    syncState = current.syncState.afterLocalEdit(),
                )
            }
        ) return
        local.updateContent(id, markdown)
        publish()
    }

    override suspend fun rename(id: NoteId, title: String) {
        if (updateWorkingCopy(id) { current ->
                if (current.readOnly) throw ReadOnlyNoteException()
                current.copy(
                    title = normalizeTitle(title),
                    titleFixed = true,
                    state = if (current.state == NoteState.DRAFT) NoteState.ACTIVE else current.state,
                    updatedAt = clock.instant(),
                    syncState = current.syncState.afterLocalEdit(),
                )
            }
        ) return
        local.rename(id, title)
        publish()
    }

    override suspend fun setCategory(id: NoteId, category: String) {
        if (updateWorkingCopy(id) { current ->
                if (current.readOnly) throw ReadOnlyNoteException()
                current.copy(
                    category = normalizeCategory(category),
                    updatedAt = clock.instant(),
                    syncState = current.syncState.afterLocalEdit(),
                )
            }
        ) return
        local.setCategory(id, category)
        publish()
    }

    override suspend fun importManagedAttachment(
        noteId: NoteId,
        attachment: AttachmentImport,
    ): ManagedAttachment = ownershipLock(noteId).withLock {
        // Serialized with ownership transfers: a Note that is moving (or moved) to an external Source is refused.
        mutex.withLock {
            workingCopies[noteId]?.let { copy ->
                if (copy.readOnly) throw ReadOnlyNoteException()
                throw IllegalArgumentException("External Source attachments are not available yet")
            }
        }
        local.importManagedAttachment(noteId, attachment)
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
        // Permitted on read-only Notes: it synchronizes through a favorite-only request.
        if (updateWorkingCopy(id) { current ->
                current.copy(
                    favorite = !current.favorite,
                    updatedAt = clock.instant(),
                    syncState = current.syncState.afterLocalEdit(),
                )
            }
        ) return
        local.toggleFavorite(id)
        publish()
    }

    override suspend fun moveToTrash(id: NoteId) {
        if (updateWorkingCopy(id) { current ->
                if (current.readOnly) throw ReadOnlyNoteException()
                editingNotes.remove(id)
                current.copy(
                    state = NoteState.TRASHED,
                    trashedAt = clock.instant(),
                    updatedAt = clock.instant(),
                    syncState = current.syncState.afterLocalEdit(),
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
                    syncState = current.syncState.afterLocalEdit(),
                    pendingRemoteDeletion = false,
                )
            }
        ) return
        local.restore(id)
        publish()
    }

    override suspend fun deletePermanently(id: NoteId) {
        val external = mutex.withLock { workingCopies[id] }
        if (external != null) {
            if (external.readOnly && external.remoteId.isNotBlank()) throw ReadOnlyNoteException()
            val mayExistRemotely = external.remoteId.isNotBlank() || external.hasCreateAttempts() ||
                external.problem == NoteAttention.UNCERTAIN_CREATE
            if (mayExistRemotely) {
                // Durable tombstone first: whatever the Source answers, the intent survives a restart.
                updateWorkingCopy(id) { current ->
                    editingNotes.remove(id)
                    current.copy(
                        state = NoteState.TRASHED,
                        trashedAt = current.trashedAt ?: clock.instant(),
                        pendingRemoteDeletion = current.remoteId.isNotBlank(),
                        syncState = NoteSyncState.SAVED_LOCALLY,
                    )
                }
                synchronize(id)
                val afterDelete = mutex.withLock { workingCopies[id] }
                check(afterDelete == null || afterDelete.isConfirmedAbsentRemotely()) { "The Source did not confirm deletion" }
            }
        }
        val removed = mutex.withLock {
            val present = workingCopies[id] ?: return@withLock false
            // Remove any hidden Local transfer copy first, so a stop in between cannot resurrect it.
            if (local.note(id) != null) local.deletePermanently(id)
            editingNotes.remove(id)
            removeWorkingCopyLocked(present.id)
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
            if (current.state == NoteState.DRAFT && current.markdown.isBlank() && !current.titleFixed && current.attempts.isEmpty()) {
                removeWorkingCopyLocked(id)
            } else if (!current.titleFixed) {
                putLocked(current.copy(titleFixed = true), publish = false)
            }
            publishLocked()
            true
        }
        if (!handled) {
            local.close(id)
            publish()
        }
    }

    override suspend fun copyToLocal(id: NoteId, markdown: String?): NoteId {
        val external = mutex.withLock { workingCopies[id] }
        val copy = if (external != null) {
            local.copyNote(external.toPublic(), markdown ?: external.markdown, attachmentsOf = null)
        } else {
            local.copyToLocal(id, markdown)
        }
        publish()
        return copy
    }

    private suspend fun updateWorkingCopy(
        id: NoteId,
        transform: (WorkingCopy) -> WorkingCopy,
    ): Boolean = mutex.withLock {
        val current = workingCopies[id] ?: return@withLock false
        putLocked(transform(current))
        true
    }

    // ---------------------------------------------------------------- recovery inventory

    override suspend fun rescueUnreadableRecord(recordId: String): NoteId {
        if (!recordId.startsWith(WORKING_COPY_RECORD_PREFIX)) return local.rescueUnreadableRecord(recordId).also { publish(); publishUnreadable() }
        val record = requireNotNull(workingCopyRecords.firstOrNull { it.id == recordId }) { "This record is no longer listed" }
        val evidence = record.files.map { Paths.get(it) }
        val metadata = evidence.firstOrNull { it.extension == "properties" }?.let(::readEvidence)
        val content = evidence.firstOrNull { it.parent?.fileName?.toString() == "content" || it.fileName.toString() == "content.md" }
            ?.let(::readEvidence)
            ?: metadata?.let { text -> runCatching { Properties().apply { load(StringReader(text)) }.getProperty("markdown") }.getOrNull() }
        val markdown = content ?: buildString {
            append("Recovered record ").append(record.id).append(" (").append(record.reason.name).append(")\n\n")
            append(record.detail).append("\n\n~~~~~~properties\n").append(metadata ?: "(metadata unreadable)").append("\n~~~~~~\n")
        }
        val now = clock.instant()
        val shell = Note(NoteId(""), LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Recovered: " + (record.title ?: "note"),
            "", "", false, NoteState.ACTIVE, now, now)
        return local.copyNote(shell, markdown, attachmentsOf = null).also { publish() }
    }

    override suspend fun discardUnreadableRecord(recordId: String) {
        if (!recordId.startsWith(WORKING_COPY_RECORD_PREFIX)) {
            local.discardUnreadableRecord(recordId)
            publishUnreadable()
            return
        }
        mutex.withLock {
            val record = requireNotNull(workingCopyRecords.firstOrNull { it.id == recordId }) { "This record is no longer listed" }
            record.files.forEach { Files.deleteIfExists(Paths.get(it)) }
            val directory = recoveryRoot.resolve(recordId.removePrefix(WORKING_COPY_RECORD_PREFIX))
            if (directory.isDirectory()) {
                directory.resolve(RECOVERY_DESCRIPTOR).deleteIfExists()
                Files.list(directory).use { it.iterator().asSequence().toList() }.forEach { Files.deleteIfExists(it) }
                directory.deleteIfExists()
            }
            workingCopyRecords = workingCopyRecords.filterNot { it.id == recordId }
        }
        publishUnreadable()
    }

    private fun publishUnreadable() {
        mutableUnreadable.value = local.unreadableRecords.value + workingCopyRecords
    }

    // ---------------------------------------------------------------- persistence

    /** Persists one complete generation and publishes it. Callers hold [mutex]. */
    private fun putLocked(note: WorkingCopy, publish: Boolean = true) {
        persist(note)
        workingCopies[note.id] = note
        if (publish) publishLocked()
    }

    private fun removeWorkingCopyLocked(id: NoteId) {
        workingCopies.remove(id)
        metadataPath(id).deleteIfExists()
        contentPath(id).deleteIfExists()
        basePath(id).deleteIfExists()
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
        writePropertiesAtomically(metadataPath(note.id), note.toProperties())
        // Schema 1 kept body and base in separate files; the schema 2 record above supersedes them.
        contentPath(note.id).deleteIfExists()
        basePath(note.id).deleteIfExists()
    }

    private fun metadataPath(id: NoteId) = metadataRoot.resolve("${id.value}.properties")
    private fun contentPath(id: NoteId) = contentRoot.resolve("${id.value}.md")
    private fun basePath(id: NoteId) = baseRoot.resolve("${id.value}.md")

    private fun WorkingCopy.toLocalNote() = toPublic().copy(
        sourceId = LocalSourceId,
        syncState = NoteSyncState.LOCAL_SOURCE,
        readOnly = false,
        attention = null,
        syncFailed = false,
    )

    private data class WriteRequest(val source: Source, val copy: WorkingCopy, val kind: WriteKind, val attempt: WriteAttempt?)

    private sealed interface SyncOutcome {
        data class Upserted(val note: SourceNote) : SyncOutcome
        data object Deleted : SyncOutcome
    }

    private sealed interface Merge {
        data class Merged(val copy: WorkingCopy) : Merge
        data class Conflicted(val copy: WorkingCopy) : Merge
    }

    companion object {
        private const val DEFAULT_TITLE = "New note"
        private const val CATEGORIES_FILE = "categories.tsv"
        private const val RECOVERY_DIRECTORY = "recovery"
        private const val RECOVERY_DESCRIPTOR = "record.kgs-recovery"
        private const val WORKING_COPY_RECORD_PREFIX = "working-copy:"
        private const val MAX_TITLE_CODE_POINTS = 80
        private const val MAX_WRITE_ROUNDS = 3
        private const val MAX_ATTEMPTS = 16
        private const val RECHECK_INTERVAL_SECONDS = 30L

        private fun loadRetainedCategories(root: Path): Map<SourceId, Set<String>> {
            val file = root.resolve(CATEGORIES_FILE)
            if (!file.exists()) return emptyMap()
            return file.readText().lineSequence()
                .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() } }
                .groupBy({ SourceId(it[0]) }, { it[1] })
                .mapValues { (_, paths) -> paths.toSet() }
        }

        fun open(
            localRoot: Path,
            workingCopyRoot: Path,
            clock: Clock = Clock.systemUTC(),
        ): ConnectedNotesEngine {
            val normalizedWorkingRoot = workingCopyRoot.toAbsolutePath().normalize()
            normalizedWorkingRoot.resolve("notes").createDirectories()
            normalizedWorkingRoot.resolve("content").createDirectories()
            normalizedWorkingRoot.resolve("base").createDirectories()
            val local = LocalNotesEngine.open(localRoot, clock)
            val unreadable = mutableListOf<UnreadableRecord>()
            val quarantined = mutableSetOf<NoteId>()
            val loaded = loadWorkingCopies(normalizedWorkingRoot, unreadable, quarantined).toMutableMap()
            recoverTransfersAtOpen(local, normalizedWorkingRoot, loaded)
            return ConnectedNotesEngine(
                local = local,
                workingCopyRoot = normalizedWorkingRoot,
                clock = clock,
                loadedWorkingCopies = loaded,
                loadedUnreadable = unreadable.sortedBy { it.id },
                quarantinedIds = quarantined,
            )
        }

        /**
         * Settles Local/Working Copy pairs that share a NoteId after an interrupted transfer, before anyone can
         * observe them: a confirmed destination drops its rollback copy; an older version without a marker never
         * silently becomes canonical again.
         */
        private fun recoverTransfersAtOpen(local: LocalNotesEngine, root: Path, loaded: MutableMap<NoteId, WorkingCopy>) {
            loaded.values.toList().forEach { copy ->
                val shadow = local.noteAtOpen(copy.id)
                fun clearMarker() {
                    val cleared = copy.copy(transfer = null)
                    runCatching { writePropertiesAtomically(root.resolve("notes/${copy.id.value}.properties"), cleared.toProperties()) }
                        .onSuccess { loaded[copy.id] = cleared }
                }
                when {
                    copy.transfer == Transfer.TO_EXTERNAL && copy.remoteId.isNotBlank() -> {
                        if (shadow != null && !local.hasAttachmentsAtOpen(copy.id)) runCatching { local.discardAtOpen(copy.id) }
                        if (local.noteAtOpen(copy.id) == null) clearMarker()
                    }
                    copy.transfer == Transfer.TO_EXTERNAL -> Unit // Unconfirmed: keep the rollback copy hidden.
                    copy.transfer == Transfer.TO_LOCAL -> if (shadow == null) clearMarker() // Resolved by the next snapshot.
                    shadow != null -> {
                        // A record from an earlier version. The Working Copy was created from the Local Note (or
                        // vice versa) and edits after that went to the Working Copy, so an identical or older
                        // shadow is redundant. Anything else stays as an independent Local Note.
                        val redundant = !local.hasAttachmentsAtOpen(copy.id) && (
                            (shadow.markdown == copy.markdown && shadow.title == copy.title) || !shadow.updatedAt.isAfter(copy.updatedAt)
                            )
                        runCatching { if (redundant) local.discardAtOpen(copy.id) else local.rekeyAtOpen(copy.id) }
                    }
                }
            }
        }

        private fun loadWorkingCopies(
            root: Path,
            unreadable: MutableList<UnreadableRecord>,
            quarantined: MutableSet<NoteId>,
        ): Map<NoteId, WorkingCopy> {
            val metadataRoot = root.resolve("notes")
            val recoveryRoot = root.resolve(RECOVERY_DIRECTORY)
            val loaded = linkedMapOf<NoteId, WorkingCopy>()
            if (metadataRoot.exists()) {
                Files.list(metadataRoot).use { paths ->
                    paths.iterator().asSequence().filter { it.isRegularFile() && it.extension == "properties" }.sorted().toList()
                }.forEach { path ->
                    try {
                        val copy = readWorkingCopy(root, path)
                        loaded[copy.id] = copy
                    } catch (failure: RecordLoadFailure) {
                        val stem = path.nameWithoutExtension
                        quarantined += NoteId(stem)
                        failure.properties?.getProperty("id")?.let { quarantined += NoteId(it) }
                        unreadable += quarantineWorkingCopy(root, path, failure)
                    }
                }
            }
            if (recoveryRoot.isDirectory()) {
                Files.list(recoveryRoot).use { dirs -> dirs.iterator().asSequence().filter { it.isDirectory() }.sorted().toList() }
                    .forEach { directory ->
                        val known = unreadable.any { it.id == WORKING_COPY_RECORD_PREFIX + directory.fileName }
                        if (!known) readRecoveryRecord(directory)?.let { record ->
                            unreadable += record
                            record.noteId?.let(quarantined::add)
                        }
                    }
            }
            return loaded
        }

        /**
         * Moves the evidence of an unreadable Working Copy (byte-identical rename) out of the live store, so a
         * refresh importing the same remote Note cannot overwrite it, and describes it for the recovery inventory.
         */
        private fun quarantineWorkingCopy(root: Path, metadataPath: Path, failure: RecordLoadFailure): UnreadableRecord {
            val stem = metadataPath.nameWithoutExtension
            val sourceFiles = listOf(metadataPath, root.resolve("content/$stem.md"), root.resolve("base/$stem.md")).filter(Path::exists)
            val properties = failure.properties
            val hasInlineContent = properties?.getProperty("markdown") != null
            val describe = { files: List<Path>, id: String ->
                UnreadableRecord(
                    id = WORKING_COPY_RECORD_PREFIX + id,
                    kind = UnreadableRecordKind.WORKING_COPY,
                    reason = failure.reason,
                    noteId = NoteId(properties?.getProperty("id") ?: stem),
                    sourceId = properties?.getProperty("sourceId")?.takeIf(String::isNotBlank)?.let(::SourceId),
                    title = properties?.getProperty("title")?.takeIf(String::isNotBlank),
                    detail = "${metadataPath.fileName}: ${failure.message.orEmpty()}",
                    files = files.map { it.toAbsolutePath().toString() },
                    hasContent = hasInlineContent || files.any { it.parent?.fileName?.toString() == "content" || it.fileName.toString() == "content.md" },
                )
            }
            val directoryName = "$stem-${UUID.randomUUID().toString().take(8)}"
            val directory = root.resolve(RECOVERY_DIRECTORY).resolve(directoryName)
            return try {
                directory.createDirectories()
                val moved = sourceFiles.map { file ->
                    val name = when (file.parent.fileName.toString()) {
                        "content" -> "content.md"
                        "base" -> "base.md"
                        else -> file.fileName.toString()
                    }
                    val target = directory.resolve(name)
                    Files.move(file, target)
                    target
                }
                val record = describe(moved, directoryName)
                runCatching { writePropertiesAtomically(directory.resolve(RECOVERY_DESCRIPTOR), record.toProperties()) }
                record
            } catch (_: Exception) {
                // Cannot move it (for example, no permission): list it in place; its id stays quarantined.
                runCatching { directory.deleteIfExists() }
                describe(sourceFiles, "notes/$stem")
            }
        }

        private fun readRecoveryRecord(directory: Path): UnreadableRecord? = runCatching {
            val descriptor = Properties().apply {
                Files.newBufferedReader(directory.resolve(RECOVERY_DESCRIPTOR), StandardCharsets.UTF_8).use(::load)
            }
            val files = Files.list(directory).use { paths ->
                paths.iterator().asSequence().filter { it.fileName.toString() != RECOVERY_DESCRIPTOR }.sorted().toList()
            }
            UnreadableRecord(
                id = WORKING_COPY_RECORD_PREFIX + directory.fileName,
                kind = UnreadableRecordKind.WORKING_COPY,
                reason = UnreadableReason.valueOf(descriptor.getProperty("reason")),
                noteId = descriptor.getProperty("noteId")?.takeIf(String::isNotBlank)?.let(::NoteId),
                sourceId = descriptor.getProperty("sourceId")?.takeIf(String::isNotBlank)?.let(::SourceId),
                title = descriptor.getProperty("title")?.takeIf(String::isNotBlank),
                detail = descriptor.getProperty("detail").orEmpty(),
                files = files.map { it.toAbsolutePath().toString() },
                hasContent = descriptor.getProperty("hasContent", "false").toBoolean(),
            )
        }.getOrElse {
            // Even without our descriptor the preserved files stay discoverable.
            val files = runCatching { Files.list(directory).use { it.iterator().asSequence().sorted().toList() } }.getOrDefault(emptyList())
            UnreadableRecord(
                id = WORKING_COPY_RECORD_PREFIX + directory.fileName,
                kind = UnreadableRecordKind.WORKING_COPY,
                reason = UnreadableReason.MALFORMED_METADATA,
                noteId = null,
                sourceId = null,
                title = null,
                detail = "Preserved files in ${directory.fileName}",
                files = files.map { it.toAbsolutePath().toString() },
                hasContent = files.any { it.fileName.toString() == "content.md" },
            )
        }

        private fun UnreadableRecord.toProperties() = Properties().apply {
            setProperty("reason", reason.name)
            setProperty("noteId", noteId?.value.orEmpty())
            setProperty("sourceId", sourceId?.value.orEmpty())
            setProperty("title", title.orEmpty())
            setProperty("detail", detail)
            setProperty("hasContent", hasContent.toString())
        }

        private fun readEvidence(path: Path): String? = runCatching { String(Files.readAllBytes(path), StandardCharsets.UTF_8) }.getOrNull()

        private fun readWorkingCopy(root: Path, metadataPath: Path): WorkingCopy {
            val properties = Properties()
            try {
                Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8).use(properties::load)
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw RecordLoadFailure(UnreadableReason.MALFORMED_METADATA, "Malformed text: ${failure.message.orEmpty()}")
            } catch (failure: java.io.IOException) {
                throw RecordLoadFailure(UnreadableReason.INACCESSIBLE, "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
            } catch (failure: IllegalArgumentException) {
                throw RecordLoadFailure(UnreadableReason.MALFORMED_METADATA, "Unreadable properties: ${failure.message.orEmpty()}")
            }
            val schema = properties.getProperty("schema")?.trim()
            val version = schema?.toIntOrNull()
            if (version == null || version < 1) {
                throw RecordLoadFailure(UnreadableReason.MALFORMED_METADATA, "schema=${schema ?: "(missing)"}", properties)
            }
            if (version > ConnectedNotesEngineSchema) throw RecordLoadFailure(UnreadableReason.UNSUPPORTED_VERSION, "schema=$schema", properties)
            val id = NoteId(properties.getProperty("id") ?: metadataPath.nameWithoutExtension)
            val legacyContent = root.resolve("content/${id.value}.md")
            val legacyBase = root.resolve("base/${id.value}.md")
            val markdown: String
            val baseMarkdown: String?
            if (version == 1) {
                if (!legacyContent.exists()) {
                    throw RecordLoadFailure(UnreadableReason.MISSING_CONTENT, "content/${id.value}.md is missing", properties)
                }
                try {
                    markdown = legacyContent.readText()
                    baseMarkdown = legacyBase.takeIf(Path::exists)?.readText()
                } catch (failure: java.io.IOException) {
                    throw RecordLoadFailure(UnreadableReason.INACCESSIBLE, "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}", properties)
                }
            } else {
                markdown = properties.getProperty("markdown")
                    ?: throw RecordLoadFailure(UnreadableReason.MISSING_CONTENT, "markdown is missing", properties)
                baseMarkdown = properties.getProperty("baseMarkdown")
            }
            return try {
                val stored = NoteSyncState.valueOf(properties.getProperty("syncState", NoteSyncState.SAVED_LOCALLY.name))
                    .let { if (it == NoteSyncState.SYNCING) NoteSyncState.SAVED_LOCALLY else it }
                val title = properties.getProperty("title", DEFAULT_TITLE)
                // External Categories are kept verbatim; Local naming rules apply only when moving into Local.
                val category = properties.getProperty("category", "")
                val favorite = properties.getProperty("favorite", "false").toBoolean()
                val attempts = if (version == 1) {
                    listOfNotNull(properties.getProperty("uncertainWriteMarkdownSha256")?.let { hash ->
                        WriteAttempt(
                            kind = if (properties.getProperty("remoteId").isNullOrBlank()) WriteKind.CREATE else WriteKind.UPDATE,
                            title = properties.getProperty("uncertainWriteTitle", ""),
                            category = properties.getProperty("uncertainWriteCategory", ""),
                            favorite = null,
                            markdownSha256 = hash,
                            modifiedEpochSecond = null,
                            knownRemoteIds = null,
                        )
                    })
                } else {
                    (0 until properties.getProperty("attempts", "0").toInt()).map { WriteAttempt.read(properties, "attempt.$it.") }
                }
                // Schema 1 wrote body, base and flags separately. A body that differs from its base was never
                // confirmed, whatever a stale SYNCED flag says.
                val syncState = if (version == 1 && stored == NoteSyncState.SYNCED && markdown != (baseMarkdown ?: "")) {
                    NoteSyncState.SAVED_LOCALLY
                } else stored
                val base = if (version == 1) {
                    if (syncState == NoteSyncState.SYNCED) SyncBase(markdown, title, category, favorite)
                    else SyncBase(baseMarkdown, null, null, null)
                } else {
                    SyncBase(
                        baseMarkdown,
                        properties.getProperty("baseTitle"),
                        properties.getProperty("baseCategory"),
                        properties.getProperty("baseFavorite")?.toBoolean(),
                    )
                }
                WorkingCopy(
                    id = id,
                    sourceId = SourceId(requireNotNull(properties.getProperty("sourceId")) { "sourceId is missing" }),
                    remoteId = properties.getProperty("remoteId", ""),
                    revision = properties.getProperty("revision", ""),
                    title = title,
                    markdown = markdown,
                    category = category,
                    favorite = favorite,
                    pinned = properties.getProperty("pinned", "false").toBoolean(),
                    state = NoteState.valueOf(properties.getProperty("state", NoteState.ACTIVE.name)),
                    createdAt = Instant.parse(properties.getProperty("createdAt")),
                    updatedAt = Instant.parse(properties.getProperty("updatedAt")),
                    trashedAt = properties.getProperty("trashedAt").takeUnless(String?::isNullOrBlank)?.let(Instant::parse),
                    titleFixed = properties.getProperty("titleFixed", "true").toBoolean(),
                    readOnly = properties.getProperty("readOnly", "false").toBoolean(),
                    syncState = syncState,
                    base = base,
                    pendingRemoteDeletion = properties.getProperty("pendingRemoteDeletion", "false").toBoolean(),
                    syncFailed = properties.getProperty("syncFailed", "false").toBoolean(),
                    attempts = attempts,
                    problem = properties.getProperty("problem")?.takeIf(String::isNotBlank)?.let(NoteAttention::valueOf),
                    contentUnavailable = properties.getProperty("contentUnavailable", "false").toBoolean(),
                    transfer = properties.getProperty("transfer")?.takeIf(String::isNotBlank)?.let(Transfer::valueOf),
                )
            } catch (failure: RecordLoadFailure) {
                throw failure
            } catch (failure: Exception) {
                throw RecordLoadFailure(UnreadableReason.MALFORMED_METADATA, "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}", properties)
            }
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
                    StandardCharsets.UTF_8,
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

private enum class WriteKind { CREATE, UPDATE, DELETE, FAVORITE }

/** Which side holds the authoritative copy while an ownership transfer is unfinished. */
private enum class Transfer {
    /** Moving Local → external: the Working Copy is authoritative; the Local Note is a hidden rollback copy until confirmed. */
    TO_EXTERNAL,
    /** Moving external → Local: the Working Copy stays authoritative until the remote deletion is confirmed. */
    TO_LOCAL,
}

/** The last state confirmed by the Source; null fields are unknown (records from schema 1). */
private data class SyncBase(
    val markdown: String?,
    val title: String?,
    val category: String?,
    val favorite: Boolean?,
) {
    companion object {
        val NONE = SyncBase(null, null, null, null)
    }
}

private fun NoteSyncState.afterLocalEdit() =
    if (this == NoteSyncState.NEEDS_ATTENTION) NoteSyncState.NEEDS_ATTENTION else NoteSyncState.SAVED_LOCALLY

private fun NoteSyncState.settledAfterSync() = if (this == NoteSyncState.SYNCING) NoteSyncState.SAVED_LOCALLY else this

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
    val base: SyncBase,
    val pendingRemoteDeletion: Boolean,
    val pinned: Boolean = false,
    val syncFailed: Boolean = false,
    /** Writes issued since the last confirmed Source state whose responses are unconfirmed. */
    val attempts: List<WriteAttempt> = emptyList(),
    /** A recorded reason this Note needs the user; nothing was overwritten. */
    val problem: NoteAttention? = null,
    /** The Source last reported a body read error; the cached body is kept and writes pause. */
    val contentUnavailable: Boolean = false,
    val transfer: Transfer? = null,
) {
    fun hasCreateAttempts() = attempts.any { it.kind == WriteKind.CREATE }

    fun hasNonFavoriteChanges() = markdown != base.markdown || title != base.title || category != base.category ||
        pendingRemoteDeletion

    fun hasLocalChanges() = hasNonFavoriteChanges() || favorite != base.favorite || remoteId.isBlank()

    fun needsWrite() = syncState != NoteSyncState.SYNCED

    fun isCleanImport() = syncState == NoteSyncState.SYNCED && problem == null && attempts.isEmpty() && transfer == null &&
        !hasLocalChanges() && state == NoteState.ACTIVE

    fun isSettled() = syncState == NoteSyncState.SYNCED && !pendingRemoteDeletion && transfer == null &&
        attempts.isEmpty() && problem == null

    /** Nothing of this Note can still exist remotely as far as the Source confirmed. */
    fun isConfirmedAbsentRemotely() = remoteId.isBlank() && !pendingRemoteDeletion && !hasCreateAttempts() &&
        problem != NoteAttention.UNCERTAIN_CREATE

    val attention: NoteAttention?
        get() = problem
            ?: NoteAttention.TRANSFER_PENDING.takeIf { transfer == Transfer.TO_EXTERNAL && remoteId.isBlank() }
            ?: NoteAttention.REMOTE_CONTENT_UNAVAILABLE.takeIf { contentUnavailable }
            ?: NoteAttention.READ_ONLY_CHANGES.takeIf { readOnly && remoteId.isNotBlank() && hasNonFavoriteChanges() }

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
        readOnly = readOnly,
        attention = attention,
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
        readOnly = readOnly,
        attention = attention,
    )

    fun toSourceDraft() = SourceNoteDraft(
        title = title,
        markdown = markdown,
        category = category,
        favorite = favorite,
        modifiedAt = updatedAt,
    )

    fun toProperties() = Properties().apply {
        setProperty("schema", ConnectedNotesEngineSchema.toString())
        setProperty("id", id.value)
        setProperty("sourceId", sourceId.value)
        setProperty("remoteId", remoteId)
        setProperty("revision", revision)
        setProperty("title", title)
        setProperty("markdown", markdown)
        setProperty("category", category)
        setProperty("favorite", favorite.toString())
        setProperty("pinned", pinned.toString())
        setProperty("state", state.name)
        setProperty("createdAt", createdAt.toString())
        setProperty("updatedAt", updatedAt.toString())
        setProperty("trashedAt", trashedAt?.toString().orEmpty())
        setProperty("titleFixed", titleFixed.toString())
        setProperty("readOnly", readOnly.toString())
        setProperty("syncState", syncState.name)
        setProperty("pendingRemoteDeletion", pendingRemoteDeletion.toString())
        setProperty("syncFailed", syncFailed.toString())
        base.markdown?.let { setProperty("baseMarkdown", it) }
        base.title?.let { setProperty("baseTitle", it) }
        base.category?.let { setProperty("baseCategory", it) }
        base.favorite?.let { setProperty("baseFavorite", it.toString()) }
        problem?.let { setProperty("problem", it.name) }
        setProperty("contentUnavailable", contentUnavailable.toString())
        transfer?.let { setProperty("transfer", it.name) }
        setProperty("attempts", attempts.size.toString())
        attempts.forEachIndexed { index, attempt -> attempt.write(this, "attempt.$index.") }
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
            base = SyncBase(remote.markdown, remote.title, remote.category, remote.favorite),
            pendingRemoteDeletion = false,
        )

        /** A remote Note first seen while its body cannot be read: listed, but nothing is adopted as a base. */
        fun placeholder(id: NoteId, sourceId: SourceId, remote: SourceNote) = fromRemote(id, sourceId, remote).copy(
            revision = "",
            markdown = "",
            readOnly = false,
            base = SyncBase("", remote.title, remote.category, remote.favorite),
            contentUnavailable = true,
        )
    }
}

private const val ConnectedNotesEngineSchema = 2

/** What one issued write sent; null fields were not part of it. */
private data class WriteAttempt(
    val kind: WriteKind,
    val title: String?,
    val category: String?,
    val favorite: Boolean?,
    val markdownSha256: String?,
    val modifiedEpochSecond: Long?,
    /** For creates: the remote Notes that already existed, which therefore cannot be its result. */
    val knownRemoteIds: Set<String>?,
) {
    /** The whole payload is present in [remote]; Sources may rename a create to keep titles unique ("Title (2)"). */
    fun matches(remote: SourceNote): Boolean {
        if (remote.contentUnavailable) return false
        if (markdownSha256 != null && sha256(remote.markdown) != markdownSha256) return false
        if (category != null && remote.category != category) return false
        if (favorite != null && remote.favorite != favorite) return false
        return title == null || titleMatches(title, remote.title)
    }

    /** Weak evidence that a create could have produced [remote] after the Source adjusted it. Never enough to adopt. */
    fun couldHaveProduced(remote: SourceNote): Boolean =
        remote.contentUnavailable || ((markdownSha256 == null || sha256(remote.markdown) == markdownSha256) &&
            (category == null || remote.category == category))

    fun write(properties: Properties, prefix: String) {
        properties.setProperty(prefix + "kind", kind.name)
        title?.let { properties.setProperty(prefix + "title", it) }
        category?.let { properties.setProperty(prefix + "category", it) }
        favorite?.let { properties.setProperty(prefix + "favorite", it.toString()) }
        markdownSha256?.let { properties.setProperty(prefix + "markdownSha256", it) }
        modifiedEpochSecond?.let { properties.setProperty(prefix + "modified", it.toString()) }
        knownRemoteIds?.let { properties.setProperty(prefix + "knownRemoteIds", it.joinToString("\n")) }
    }

    companion object {
        fun of(copy: WorkingCopy, kind: WriteKind, knownRemoteIds: Set<String>?) = WriteAttempt(
            kind = kind,
            title = copy.title,
            category = copy.category,
            favorite = copy.favorite,
            markdownSha256 = sha256(copy.markdown),
            modifiedEpochSecond = copy.updatedAt.epochSecond,
            knownRemoteIds = knownRemoteIds,
        )

        fun favoriteOnly(favorite: Boolean) = WriteAttempt(WriteKind.FAVORITE, null, null, favorite, null, null, null)

        fun read(properties: Properties, prefix: String) = WriteAttempt(
            kind = WriteKind.valueOf(properties.getProperty(prefix + "kind")),
            title = properties.getProperty(prefix + "title"),
            category = properties.getProperty(prefix + "category"),
            favorite = properties.getProperty(prefix + "favorite")?.toBoolean(),
            markdownSha256 = properties.getProperty(prefix + "markdownSha256"),
            modifiedEpochSecond = properties.getProperty(prefix + "modified")?.toLong(),
            knownRemoteIds = properties.getProperty(prefix + "knownRemoteIds")?.split('\n')?.filter(String::isNotEmpty)?.toSet(),
        )
    }
}

private fun titleMatches(sent: String, remote: String) =
    remote == sent || remote.matches(Regex(Regex.escape(sent) + " \\(\\d+\\)"))

private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
