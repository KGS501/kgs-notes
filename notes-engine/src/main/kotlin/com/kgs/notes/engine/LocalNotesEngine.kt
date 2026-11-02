package com.kgs.notes.engine

import java.io.Writer
import java.io.InputStream
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

class LocalNotesEngine private constructor(
    private val root: Path,
    private val clock: Clock,
    loadedNotes: Map<NoteId, StoredNote>,
    loadedAttachments: Map<AttachmentId, StoredAttachment>,
    loadedCategories: Set<String>,
    loadedUnreadable: List<UnreadableRecord>,
) : NotesEngine {
    private val metadataRoot = root.resolve(".kgs/notes")
    private val attachmentMetadataRoot = root.resolve(".kgs/attachments")
    private val attachmentVaultRoot = root.resolve(".kgs-notes-attachments")
    private val draftsRoot = root.resolve(".kgs/drafts")
    private val mutex = Mutex()
    private val notes = loadedNotes.toMutableMap()
    private val attachments = ConcurrentHashMap(loadedAttachments)
    private var retainedCategories: Set<String> = loadedCategories
    private val mutableLibrary = MutableStateFlow(snapshotOf(notes.values, retainedCategories))
    private val mutableSources = MutableStateFlow(listOf(LOCAL_SOURCE_DESCRIPTOR))
    private val mutableUnreadable = MutableStateFlow(loadedUnreadable)
    override val unreadableRecords: StateFlow<List<UnreadableRecord>> = mutableUnreadable.asStateFlow()

    override val library: StateFlow<LibrarySnapshot> = mutableLibrary.asStateFlow()
    override val sources: StateFlow<List<SourceDescriptor>> = mutableSources.asStateFlow()

    override suspend fun createDraft(category: String, sourceId: SourceId): NoteId = mutex.withLock {
        require(sourceId == LocalSourceId) { "LocalNotesEngine only owns the Local Source" }
        val now = clock.instant()
        val id = NoteId(UUID.randomUUID().toString())
        val relativePath = ".kgs/drafts/${id.value}.md"
        val stored = StoredNote(
            id = id,
            title = DEFAULT_TITLE,
            category = normalizeCategory(category),
            favorite = false,
            state = NoteState.DRAFT,
            createdAt = now,
            updatedAt = now,
            trashedAt = null,
            titleFixed = false,
            relativePath = relativePath,
            markdown = "",
        )
        writeTextAtomically(contentPath(relativePath), "")
        persistMetadata(stored)
        notes[id] = stored
        publish()
        id
    }

    override suspend fun attachSource(source: Source) {
        throw UnsupportedOperationException("Use ConnectedNotesEngine for external Sources")
    }

    override suspend fun removeSource(id: SourceId) {
        throw UnsupportedOperationException("Use ConnectedNotesEngine for external Sources")
    }

    override suspend fun refreshSources() = Unit

    override suspend fun synchronize(id: NoteId) = Unit

    override suspend fun moveToSource(id: NoteId, sourceId: SourceId): NoteId {
        require(sourceId == LocalSourceId) { "Use ConnectedNotesEngine for external Sources" }
        return id
    }

    override suspend fun note(id: NoteId): Note? = mutex.withLock {
        notes[id]?.toPublic()
    }

    /** Confirms a transferred Note as canonical local Markdown without changing its identity. */
    internal suspend fun retainTransferredNote(note: Note) = mutex.withLock {
        val previous = notes[note.id]
        val title = normalizeTitle(note.title)
        val category = normalizeCategory(note.category)
        val relativePath = availableContentPath(title, category, note.id, previous?.relativePath.orEmpty())
        val stored = StoredNote(
            id = note.id,
            title = title,
            category = category,
            favorite = note.favorite,
            pinned = note.pinned,
            state = note.state,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
            trashedAt = note.trashedAt,
            titleFixed = true,
            relativePath = relativePath,
            markdown = rewriteManagedAttachmentTargets(note.markdown, note.id, previous?.relativePath.orEmpty(), relativePath),
        )
        writeTextAtomically(contentPath(relativePath), stored.markdown)
        persistMetadata(stored)
        notes[note.id] = stored
        if (previous != null && previous.relativePath != relativePath) {
            contentPath(previous.relativePath).deleteIfExists()
        }
        publish()
    }

    override suspend fun updateContent(id: NoteId, markdown: String) = mutex.withLock {
        val current = notes[id] ?: return@withLock
        val title = if (current.titleFixed) current.title else automaticTitle(markdown)
        val state = when {
            current.state == NoteState.TRASHED -> NoteState.TRASHED
            markdown.isBlank() && !current.titleFixed -> NoteState.DRAFT
            else -> NoteState.ACTIVE
        }
        val desiredPath = if (state == NoteState.DRAFT) {
            current.relativePath
        } else {
            availableContentPath(title, current.category, current.id, current.relativePath)
        }
        val storedMarkdown = rewriteManagedAttachmentTargets(
            markdown = markdown,
            noteId = id,
            fromNotePath = current.relativePath,
            toNotePath = desiredPath,
        )
        writeTextAtomically(contentPath(desiredPath), storedMarkdown)
        val updated = current.copy(
            title = title,
            state = state,
            updatedAt = clock.instant(),
            relativePath = desiredPath,
            markdown = storedMarkdown,
        )
        persistMetadata(updated)
        if (desiredPath != current.relativePath) contentPath(current.relativePath).deleteIfExists()
        notes[id] = updated
        publish()
    }

    override suspend fun rename(id: NoteId, title: String) = mutex.withLock {
        val current = notes[id] ?: return@withLock
        val cleanTitle = normalizeTitle(title)
        val desiredPath = if (current.state == NoteState.DRAFT && current.markdown.isBlank()) {
            current.relativePath
        } else {
            availableContentPath(cleanTitle, current.category, current.id, current.relativePath)
        }
        // A rename can relocate the file (for example after a Category change made while in Trash),
        // so portable attachment targets must follow the file like they do for content updates.
        val storedMarkdown = rewriteManagedAttachmentTargets(
            markdown = current.markdown,
            noteId = id,
            fromNotePath = current.relativePath,
            toNotePath = desiredPath,
        )
        writeTextAtomically(contentPath(desiredPath), storedMarkdown)
        val updated = current.copy(
            title = cleanTitle,
            titleFixed = true,
            state = if (current.state == NoteState.DRAFT) NoteState.ACTIVE else current.state,
            relativePath = desiredPath,
            updatedAt = clock.instant(),
            markdown = storedMarkdown,
        )
        persistMetadata(updated)
        if (desiredPath != current.relativePath) contentPath(current.relativePath).deleteIfExists()
        notes[id] = updated
        publish()
    }

    override suspend fun setCategory(id: NoteId, category: String) = mutex.withLock {
        val current = notes[id] ?: return@withLock
        val normalized = normalizeCategory(category)
        if (current.category == normalized) return@withLock
        val desiredPath = if (current.state != NoteState.ACTIVE) {
            current.relativePath
        } else {
            availableContentPath(current.title, normalized, current.id, current.relativePath)
        }
        val storedMarkdown = rewriteManagedAttachmentTargets(
            markdown = current.markdown,
            noteId = id,
            fromNotePath = current.relativePath,
            toNotePath = desiredPath,
        )
        writeTextAtomically(contentPath(desiredPath), storedMarkdown)
        val updated = current.copy(
            category = normalized,
            relativePath = desiredPath,
            updatedAt = clock.instant(),
            markdown = storedMarkdown,
        )
        persistMetadata(updated)
        if (desiredPath != current.relativePath) contentPath(current.relativePath).deleteIfExists()
        notes[id] = updated
        publish()
    }

    override suspend fun importManagedAttachment(
        noteId: NoteId,
        attachment: AttachmentImport,
    ): ManagedAttachment = mutex.withLock {
        val note = requireNotNull(notes[noteId]) { "Cannot attach a file to a missing Note" }
        val id = AttachmentId(UUID.randomUUID().toString())
        val extension = safeAttachmentExtension(attachment.displayName, attachment.mediaType)
        val storageName = id.value + extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val relativePath = ".kgs-notes-attachments/${noteId.value}/$storageName"
        val stored = StoredAttachment(
            id = id,
            noteId = noteId,
            displayName = normalizeAttachmentDisplayName(attachment.displayName),
            mediaType = normalizeMediaType(attachment.mediaType),
            relativePath = relativePath,
            createdAt = clock.instant(),
        )

        attachment.openContent().use { content ->
            writeStreamAtomically(contentPath(relativePath), content)
        }
        persistAttachmentMetadata(stored)
        attachments[id] = stored
        stored.toPublic(markdownTarget(note.relativePath, relativePath))
    }

    override suspend fun managedAttachments(noteId: NoteId): List<ManagedAttachment> = mutex.withLock {
        val note = notes[noteId] ?: return@withLock emptyList()
        attachments.values
            .filter { it.noteId == noteId }
            .sortedBy(StoredAttachment::createdAt)
            .map { it.toPublic(markdownTarget(note.relativePath, it.relativePath)) }
    }

    override fun openManagedAttachment(id: AttachmentId): InputStream? {
        val stored = attachments[id] ?: return null
        val path = contentPath(stored.relativePath)
        return if (path.isRegularFile()) Files.newInputStream(path) else null
    }

    override suspend fun togglePin(id: NoteId) = updateMetadata(id) { it.copy(pinned = !it.pinned) }

    override suspend fun toggleFavorite(id: NoteId) = updateMetadata(id) { current ->
        current.copy(favorite = !current.favorite, updatedAt = clock.instant())
    }

    override suspend fun moveToTrash(id: NoteId) = updateMetadata(id) { current ->
        current.copy(state = NoteState.TRASHED, trashedAt = clock.instant(), updatedAt = clock.instant())
    }

    override suspend fun restore(id: NoteId) = updateMetadata(id) { current ->
        current.copy(state = NoteState.ACTIVE, trashedAt = null, updatedAt = clock.instant())
    }

    override suspend fun deletePermanently(id: NoteId) = mutex.withLock {
        val removed = notes[id] ?: return@withLock
        // Metadata first: an interrupted deletion leaves an orphan file, never a record without content,
        // and memory changes only after the durable record is gone.
        metadataPath(id).deleteIfExists()
        notes.remove(id)
        publish()
        contentPath(removed.relativePath).deleteIfExists()
        val removedAttachments = attachments.values.filter { it.noteId == id }
        removedAttachments.forEach { attachment ->
            contentPath(attachment.relativePath).deleteIfExists()
            attachmentMetadataPath(attachment.id).deleteIfExists()
            attachments.remove(attachment.id)
        }
        deleteEmptyDirectories(attachmentVaultRoot.resolve(id.value))
        publish()
    }

    override suspend fun close(id: NoteId) = mutex.withLock {
        val current = notes[id] ?: return@withLock
        if (current.state == NoteState.DRAFT && current.markdown.isBlank() && !current.titleFixed) {
            metadataPath(id).deleteIfExists()
            notes.remove(id)
            contentPath(current.relativePath).deleteIfExists()
        } else if (!current.titleFixed) {
            val closed = current.copy(titleFixed = true)
            persistMetadata(closed)
            notes[id] = closed
        }
        publish()
    }

    override suspend fun copyToLocal(id: NoteId, markdown: String?): NoteId {
        val note = requireNotNull(note(id)) { "Cannot copy a missing Note" }
        return copyNote(note, markdown ?: note.markdown, attachmentsOf = id)
    }

    /**
     * Creates an independent ACTIVE Local Note from [note]. Managed attachments of [attachmentsOf] are copied
     * and every relative target in [markdown] is rebased to the copy, so neither Note depends on the other.
     */
    internal suspend fun copyNote(note: Note, markdown: String, attachmentsOf: NoteId?): NoteId = mutex.withLock {
        val id = NoteId(UUID.randomUUID().toString())
        val title = normalizeTitle(note.title)
        val category = normalizeCategory(note.category)
        val relativePath = availableContentPath(title, category, id, "")
        val copies = attachments.values.filter { attachmentsOf != null && it.noteId == attachmentsOf }.sortedBy(StoredAttachment::createdAt)
        var content = markdown
        val written = mutableListOf<StoredAttachment>()
        try {
            copies.forEach { original ->
                val copyId = AttachmentId(UUID.randomUUID().toString())
                val extension = original.relativePath.substringAfterLast('/').substringAfter('.', "")
                val storageName = copyId.value + extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
                val copy = original.copy(id = copyId, noteId = id, relativePath = ".kgs-notes-attachments/${id.value}/$storageName")
                Files.newInputStream(contentPath(original.relativePath)).use { writeStreamAtomically(contentPath(copy.relativePath), it) }
                persistAttachmentMetadata(copy)
                written += copy
                content = replaceVaultTargets(content, original.relativePath, markdownTarget(relativePath, copy.relativePath))
            }
            val now = clock.instant()
            val stored = StoredNote(
                id = id,
                title = title,
                category = category,
                favorite = note.favorite,
                state = NoteState.ACTIVE,
                createdAt = note.createdAt,
                updatedAt = now,
                trashedAt = null,
                titleFixed = true,
                relativePath = relativePath,
                markdown = content,
            )
            writeTextAtomically(contentPath(relativePath), content)
            persistMetadata(stored)
            notes[id] = stored
        } catch (failure: Exception) {
            // Leave the original untouched and no half-made copy behind.
            written.forEach {
                contentPath(it.relativePath).deleteIfExists()
                attachmentMetadataPath(it.id).deleteIfExists()
            }
            deleteEmptyDirectories(attachmentVaultRoot.resolve(id.value))
            contentPath(relativePath).deleteIfExists()
            metadataPath(id).deleteIfExists()
            throw failure
        }
        written.forEach { attachments[it.id] = it }
        publish()
        id
    }

    override suspend fun rescueUnreadableRecord(recordId: String): NoteId {
        val record = requireNotNull(mutableUnreadable.value.firstOrNull { it.id == recordId }) { "This record is no longer listed" }
        val metadata = record.files.firstOrNull()?.let(::readEvidenceText)
        val title = "Recovered: " + (record.title ?: record.noteId?.value ?: "note")
        val now = clock.instant()
        val shell = Note(NoteId(""), LocalSourceId, NoteSyncState.LOCAL_SOURCE, title, "", "", false, NoteState.ACTIVE, now, now)
        return when (record.kind) {
            UnreadableRecordKind.LOCAL_ATTACHMENT -> {
                val bytesPath = record.files.drop(1).firstOrNull()?.let { java.nio.file.Paths.get(it) }
                if (record.hasContent && bytesPath != null) {
                    val noteId = copyNote(shell, "", attachmentsOf = null)
                    val imported = importManagedAttachment(noteId, AttachmentImport(
                        displayName = record.title ?: bytesPath.fileName.toString(),
                        mediaType = metadataProperty(metadata, "mediaType") ?: "application/octet-stream",
                    ) { Files.newInputStream(bytesPath) })
                    val image = imported.mediaType.startsWith("image/")
                    updateContent(noteId, (if (image) "!" else "") + "[${imported.displayName}](${imported.markdownTarget})\n")
                    noteId
                } else {
                    copyNote(shell, fencedEvidence(record, metadata), attachmentsOf = null)
                }
            }
            else -> {
                val content = if (record.hasContent) record.files.drop(1).firstOrNull()?.let(::readEvidenceText) else null
                copyNote(shell, content ?: fencedEvidence(record, metadata), attachmentsOf = null)
            }
        }
    }

    override suspend fun discardUnreadableRecord(recordId: String) {
        val record = requireNotNull(mutableUnreadable.value.firstOrNull { it.id == recordId }) { "This record is no longer listed" }
        mutex.withLock {
            record.files.forEach { Files.deleteIfExists(java.nio.file.Paths.get(it)) }
            mutableUnreadable.value = mutableUnreadable.value.filterNot { it.id == recordId }
        }
    }

    /** Non-suspending access used only while [ConnectedNotesEngine.open] recovers transfers, before the engine is shared. */
    internal fun noteAtOpen(id: NoteId): Note? = notes[id]?.toPublic()

    internal fun hasAttachmentsAtOpen(id: NoteId): Boolean = attachments.values.any { it.noteId == id }

    /** Removes a transfer shadow while opening; see [noteAtOpen]. */
    internal fun discardAtOpen(id: NoteId) {
        val removed = notes[id] ?: return
        metadataPath(id).deleteIfExists()
        notes.remove(id)
        contentPath(removed.relativePath).deleteIfExists()
        attachments.values.filter { it.noteId == id }.forEach { attachment ->
            contentPath(attachment.relativePath).deleteIfExists()
            attachmentMetadataPath(attachment.id).deleteIfExists()
            attachments.remove(attachment.id)
        }
        deleteEmptyDirectories(attachmentVaultRoot.resolve(id.value))
        publish()
    }

    /** Gives a Local Note that collides with a Working Copy its own identity while opening; see [noteAtOpen]. */
    internal fun rekeyAtOpen(id: NoteId): NoteId {
        val current = requireNotNull(notes[id])
        val newId = NoteId(UUID.randomUUID().toString())
        val moved = current.copy(id = newId)
        persistMetadata(moved)
        attachments.values.filter { it.noteId == id }.forEach { attachment ->
            val rekeyed = attachment.copy(noteId = newId)
            persistAttachmentMetadata(rekeyed)
            attachments[attachment.id] = rekeyed
        }
        metadataPath(id).deleteIfExists()
        notes.remove(id)
        notes[newId] = moved
        publish()
        return newId
    }

    private suspend fun updateMetadata(id: NoteId, transform: (StoredNote) -> StoredNote) = mutex.withLock {
        val current = notes[id] ?: return@withLock
        val updated = transform(current)
        persistMetadata(updated)
        notes[id] = updated
        publish()
    }

    override suspend fun createCategory(sourceId: SourceId, category: String): String {
        require(sourceId == LocalSourceId) { "LocalNotesEngine only owns the Local Source" }
        val normalized = normalizedCategoryPath(category)
        mutex.withLock {
            retainCategories(retainedCategories + normalized)
            publish()
        }
        return normalized
    }

    override suspend fun renameCategory(sourceId: SourceId, category: String, newCategory: String): Set<NoteId> {
        val target = normalizedCategoryPath(newCategory)
        val changed = replaceCategoryAssignments(sourceId, category, target)
        mutex.withLock {
            retainCategories(retainedCategories.replacingCategory(category, target))
            publish()
        }
        return changed
    }

    override suspend fun deleteCategory(sourceId: SourceId, category: String): Set<NoteId> {
        val changed = replaceCategoryAssignments(sourceId, category, "")
        mutex.withLock {
            retainCategories(retainedCategories.replacingCategory(category, ""))
            publish()
        }
        return changed
    }

    private fun retainCategories(categories: Set<String>) {
        if (categories == retainedCategories) return
        writeTextAtomically(root.resolve(CATEGORIES_PATH), categories.sorted().joinToString("\n"))
        retainedCategories = categories
    }

    private fun publish() {
        mutableLibrary.value = snapshotOf(notes.values, retainedCategories)
    }

    private fun persistMetadata(note: StoredNote) {
        val properties = Properties().apply {
            setProperty("schema", "1")
            setProperty("id", note.id.value)
            setProperty("title", note.title)
            setProperty("category", note.category)
            setProperty("favorite", note.favorite.toString())
            setProperty("pinned", note.pinned.toString())
            setProperty("state", note.state.name)
            setProperty("createdAt", note.createdAt.toString())
            setProperty("updatedAt", note.updatedAt.toString())
            setProperty("trashedAt", note.trashedAt?.toString().orEmpty())
            setProperty("titleFixed", note.titleFixed.toString())
            setProperty("relativePath", note.relativePath)
        }
        writePropertiesAtomically(metadataPath(note.id), properties)
    }

    private fun persistAttachmentMetadata(attachment: StoredAttachment) {
        val properties = Properties().apply {
            setProperty("schema", "1")
            setProperty("id", attachment.id.value)
            setProperty("noteId", attachment.noteId.value)
            setProperty("displayName", attachment.displayName)
            setProperty("mediaType", attachment.mediaType)
            setProperty("relativePath", attachment.relativePath)
            setProperty("createdAt", attachment.createdAt.toString())
        }
        writePropertiesAtomically(attachmentMetadataPath(attachment.id), properties)
    }

    private fun metadataPath(id: NoteId): Path = metadataRoot.resolve("${id.value}.properties")

    private fun attachmentMetadataPath(id: AttachmentId): Path =
        attachmentMetadataRoot.resolve("${id.value}.properties")

    private fun contentPath(relativePath: String): Path {
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Note content path left the Local Source" }
        return resolved
    }

    private fun availableContentPath(
        title: String,
        category: String,
        id: NoteId,
        currentPath: String,
    ): String {
        val directory = category.takeIf(String::isNotBlank)?.let(root::resolve) ?: root
        val stem = sanitizeFilename(title)
        var candidate = directory.resolve("$stem.md")
        var suffix = 2
        while (candidate.exists() && root.relativize(candidate).toString() != currentPath) {
            val ownedByThisNote = notes[id]?.relativePath == root.relativize(candidate).toString()
            if (ownedByThisNote) break
            candidate = directory.resolve("$stem ($suffix).md")
            suffix += 1
        }
        return root.relativize(candidate).toString()
    }

    private fun rewriteManagedAttachmentTargets(
        markdown: String,
        noteId: NoteId,
        fromNotePath: String,
        toNotePath: String,
    ): String {
        // Rewrite every relative spelling of each managed attachment, not only the one expected for
        // [fromNotePath]: this also repairs targets left stale by earlier relocations.
        return attachments.values
            .filter { it.noteId == noteId }
            .fold(markdown) { content, attachment ->
                replaceVaultTargets(content, attachment.relativePath, markdownTarget(toNotePath, attachment.relativePath))
            }
    }

    private fun replaceVaultTargets(markdown: String, vaultRelativePath: String, target: String): String =
        vaultTargetPattern(vaultRelativePath).replace(markdown) { target }

    private fun vaultTargetPattern(vaultRelativePath: String) =
        Regex("(?<![A-Za-z0-9_./%-])(?:\\./)?(?:\\.\\./)*" + Regex.escape(vaultRelativePath) + "(?![A-Za-z0-9_./%-])")

    private fun markdownTarget(noteRelativePath: String, attachmentRelativePath: String): String {
        val noteDirectory = root.resolve(noteRelativePath).normalize().parent
        val attachmentPath = contentPath(attachmentRelativePath)
        return noteDirectory.relativize(attachmentPath).joinToString("/") { it.toString() }
    }

    companion object {
        private const val DEFAULT_TITLE = "New note"
        private const val CATEGORIES_PATH = ".kgs/categories.txt"
        private const val MAX_TITLE_CODE_POINTS = 80
        private val LOCAL_SOURCE_DESCRIPTOR = SourceDescriptor(
            id = LocalSourceId,
            name = "Local Source",
            capabilities = SourceCapabilities(
                markdown = true,
                categories = true,
                favorites = true,
                attachments = true,
                writable = true,
            ),
        )

        fun open(root: Path, clock: Clock = Clock.systemUTC()): LocalNotesEngine {
            val normalizedRoot = root.toAbsolutePath().normalize()
            normalizedRoot.createDirectories()
            normalizedRoot.resolve(".kgs/notes").createDirectories()
            normalizedRoot.resolve(".kgs/drafts").createDirectories()
            normalizedRoot.resolve(".kgs/attachments").createDirectories()
            normalizedRoot.resolve(".kgs-notes-attachments").createDirectories()
            val unreadable = mutableListOf<UnreadableRecord>()
            val loaded = loadNotes(normalizedRoot, unreadable)
            val loadedAttachments = loadAttachments(normalizedRoot, unreadable)
            val categories = normalizedRoot.resolve(CATEGORIES_PATH).takeIf(Path::exists)?.readText()
                ?.lineSequence()?.filter(String::isNotBlank)?.toSet().orEmpty()
            return LocalNotesEngine(normalizedRoot, clock, loaded, loadedAttachments, categories, unreadable.sortedBy { it.id })
        }

        private fun loadNotes(root: Path, unreadable: MutableList<UnreadableRecord>): Map<NoteId, StoredNote> {
            val metadataRoot = root.resolve(".kgs/notes")
            if (!metadataRoot.exists()) return emptyMap()
            val loaded = linkedMapOf<NoteId, StoredNote>()
            listRecords(metadataRoot).forEach { path ->
                try {
                    val note = readStoredNote(root, path)
                    loaded[note.id] = note
                } catch (failure: RecordLoadFailure) {
                    unreadable += failure.toRecord(UnreadableRecordKind.LOCAL_NOTE, path, root)
                }
            }
            return loaded
        }

        private fun listRecords(directory: Path): List<Path> = Files.list(directory).use { paths ->
            paths.iterator().asSequence().filter { it.isRegularFile() && it.extension == "properties" }.sorted().toList()
        }

        /** Reads a record's metadata, classifying every failure instead of dropping the record silently. */
        private fun loadRecordProperties(metadataPath: Path): Properties {
            val properties = Properties()
            try {
                Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8).use(properties::load)
            } catch (failure: java.io.IOException) {
                // Malformed UTF-8 is a damaged record; any other I/O problem means the file is inaccessible.
                val reason = if (failure is java.nio.charset.CharacterCodingException) UnreadableReason.MALFORMED_METADATA else UnreadableReason.INACCESSIBLE
                throw RecordLoadFailure(reason, "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}", metadataPath = metadataPath)
            } catch (failure: IllegalArgumentException) {
                throw RecordLoadFailure(UnreadableReason.MALFORMED_METADATA, "Unreadable properties: ${failure.message.orEmpty()}", metadataPath = metadataPath)
            }
            val schema = properties.getProperty("schema")
            if (schema != "1") {
                val reason = if ((schema?.trim()?.toIntOrNull() ?: 0) > 1) UnreadableReason.UNSUPPORTED_VERSION else UnreadableReason.MALFORMED_METADATA
                throw RecordLoadFailure(reason, "schema=${schema ?: "(missing)"}", properties, metadataPath)
            }
            return properties
        }

        private fun <T> parsingRecord(properties: Properties, metadataPath: Path, block: () -> T): T = try {
            block()
        } catch (failure: RecordLoadFailure) {
            throw failure
        } catch (failure: Exception) {
            throw RecordLoadFailure(
                UnreadableReason.MALFORMED_METADATA,
                "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                properties,
                metadataPath,
            )
        }

        private fun readStoredNote(root: Path, metadataPath: Path): StoredNote {
            val properties = loadRecordProperties(metadataPath)
            val (relativePath, contentPath) = parsingRecord(properties, metadataPath) {
                val relativePath = requireNotNull(properties.getProperty("relativePath")) { "relativePath is missing" }
                val contentPath = root.resolve(relativePath).normalize()
                require(contentPath.startsWith(root)) { "relativePath leaves the Local Source" }
                relativePath to contentPath
            }
            val note = parsingRecord(properties, metadataPath) {
                StoredNote(
                    id = NoteId(properties.getProperty("id") ?: metadataPath.nameWithoutExtension),
                    title = properties.getProperty("title", DEFAULT_TITLE),
                    category = normalizeCategory(properties.getProperty("category", "")),
                    favorite = properties.getProperty("favorite", "false").toBoolean(),
                    pinned = properties.getProperty("pinned", "false").toBoolean(),
                    state = NoteState.valueOf(properties.getProperty("state", NoteState.ACTIVE.name)),
                    createdAt = Instant.parse(properties.getProperty("createdAt")),
                    updatedAt = Instant.parse(properties.getProperty("updatedAt")),
                    trashedAt = properties.getProperty("trashedAt").takeUnless(String?::isNullOrBlank)?.let(Instant::parse),
                    titleFixed = properties.getProperty("titleFixed", "true").toBoolean(),
                    relativePath = relativePath,
                    markdown = "",
                )
            }
            if (!contentPath.exists()) {
                throw RecordLoadFailure(UnreadableReason.MISSING_CONTENT, "Content file $relativePath is missing", properties, metadataPath)
            }
            val markdown = try {
                contentPath.readText()
            } catch (failure: java.io.IOException) {
                throw RecordLoadFailure(UnreadableReason.INACCESSIBLE, "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                    properties, metadataPath, contentPath)
            }
            return note.copy(markdown = markdown)
        }

        private fun loadAttachments(root: Path, unreadable: MutableList<UnreadableRecord>): Map<AttachmentId, StoredAttachment> {
            val metadataRoot = root.resolve(".kgs/attachments")
            if (!metadataRoot.exists()) return emptyMap()
            val loaded = linkedMapOf<AttachmentId, StoredAttachment>()
            listRecords(metadataRoot).forEach { path ->
                try {
                    val attachment = readStoredAttachment(root, path)
                    loaded[attachment.id] = attachment
                } catch (failure: RecordLoadFailure) {
                    unreadable += failure.toRecord(UnreadableRecordKind.LOCAL_ATTACHMENT, path, root)
                }
            }
            return loaded
        }

        private fun readStoredAttachment(root: Path, metadataPath: Path): StoredAttachment {
            val properties = loadRecordProperties(metadataPath)
            val attachment = parsingRecord(properties, metadataPath) {
                val relativePath = requireNotNull(properties.getProperty("relativePath")) { "relativePath is missing" }
                require(root.resolve(relativePath).normalize().startsWith(root)) { "relativePath leaves the Local Source" }
                StoredAttachment(
                    id = AttachmentId(properties.getProperty("id") ?: metadataPath.nameWithoutExtension),
                    noteId = NoteId(requireNotNull(properties.getProperty("noteId")) { "noteId is missing" }),
                    displayName = normalizeAttachmentDisplayName(properties.getProperty("displayName", "Attachment")),
                    mediaType = normalizeMediaType(properties.getProperty("mediaType", "application/octet-stream")),
                    relativePath = relativePath,
                    createdAt = Instant.parse(properties.getProperty("createdAt")),
                )
            }
            val contentPath = root.resolve(attachment.relativePath).normalize()
            if (!contentPath.isRegularFile()) {
                throw RecordLoadFailure(UnreadableReason.MISSING_CONTENT, "Attachment file ${attachment.relativePath} is missing",
                    properties, metadataPath)
            }
            if (!Files.isReadable(contentPath)) {
                throw RecordLoadFailure(UnreadableReason.INACCESSIBLE, "Attachment file ${attachment.relativePath} is not readable",
                    properties, metadataPath, contentPath)
            }
            return attachment
        }

        private fun readEvidenceText(path: String): String? = runCatching {
            // Malformed bytes are replaced, never rejected: the user gets whatever is still legible.
            String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8)
        }.getOrNull()

        private fun metadataProperty(metadata: String?, name: String): String? = metadata?.let {
            runCatching { Properties().apply { load(java.io.StringReader(it)) }.getProperty(name) }.getOrNull()
        }

        private fun fencedEvidence(record: UnreadableRecord, metadata: String?): String {
            val fence = "~~~~~~"
            return buildString {
                append("Recovered record ").append(record.id).append(" (").append(record.reason.name).append(")\n\n")
                append(record.detail).append("\n\n")
                append(fence).append("properties\n").append(metadata ?: "(metadata unreadable)").append('\n').append(fence).append('\n')
            }
        }

        private fun RecordLoadFailure.toRecord(kind: UnreadableRecordKind, metadataPath: Path, root: Path? = null): UnreadableRecord {
            val props = properties
            val localRoot = root ?: metadataPath.parent.parent.parent
            val content = contentPath ?: props?.getProperty("relativePath")
                ?.let { runCatching { localRoot.resolve(it).normalize() }.getOrNull() }
                ?.takeIf { it.startsWith(localRoot) && it.isRegularFile() }
            val readable = content != null && Files.isReadable(content)
            val title = when (kind) {
                UnreadableRecordKind.LOCAL_ATTACHMENT -> props?.getProperty("displayName")
                else -> props?.getProperty("title")
            }
            val noteId = when (kind) {
                UnreadableRecordKind.LOCAL_ATTACHMENT -> props?.getProperty("noteId")
                else -> props?.getProperty("id") ?: metadataPath.nameWithoutExtension
            }
            val prefix = if (kind == UnreadableRecordKind.LOCAL_ATTACHMENT) "local-attachment:" else "local-note:"
            return UnreadableRecord(
                id = prefix + metadataPath.fileName.toString(),
                kind = kind,
                reason = reason,
                noteId = noteId?.takeIf(String::isNotBlank)?.let(::NoteId),
                sourceId = LocalSourceId,
                title = title?.takeIf(String::isNotBlank),
                detail = "${metadataPath.fileName}: $message",
                files = listOfNotNull(metadataPath, content).map { it.toAbsolutePath().toString() },
                hasContent = readable,
            )
        }

        private fun automaticTitle(markdown: String): String {
            val firstLine = markdown.lineSequence().firstOrNull { it.isNotBlank() } ?: return DEFAULT_TITLE
            val withoutBlockMarkup = firstLine
                .replace(Regex("^\\s{0,3}(#{1,6}|>|[-+*]|\\d+[.)])\\s+"), "")
                .replace(Regex("^\\[[ xX]]\\s+"), "")
            val withoutInlineMarkup = withoutBlockMarkup
                .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_~`]+"), "")
                .trim()
            return normalizeTitle(withoutInlineMarkup.ifBlank { DEFAULT_TITLE })
        }

        private fun normalizeTitle(value: String): String {
            val singleLine = value.lineSequence().firstOrNull().orEmpty().trim().ifBlank { DEFAULT_TITLE }
            val codePoints = singleLine.codePoints().toArray()
            return if (codePoints.size <= MAX_TITLE_CODE_POINTS) {
                singleLine
            } else {
                String(codePoints, 0, MAX_TITLE_CODE_POINTS)
            }
        }

        private fun normalizeCategory(value: String): String = value
            .replace('\\', '/')
            .split('/')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { sanitizeFilename(it) }

        private fun sanitizeFilename(value: String): String {
            val sanitized = value
                .replace(Regex("[\\p{Cc}\\\\/:*?\"<>|]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '.')
            return sanitized.ifBlank { DEFAULT_TITLE }
        }

        private fun normalizeAttachmentDisplayName(value: String): String {
            val clean = value
                .replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
                .ifBlank { "Attachment" }
            val codePoints = clean.codePoints().toArray()
            return if (codePoints.size <= 160) clean else String(codePoints, 0, 160)
        }

        private fun normalizeMediaType(value: String): String = value
            .trim()
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }
            ?: "application/octet-stream"

        private fun safeAttachmentExtension(displayName: String, mediaType: String): String {
            val fromName = displayName.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            return fromName ?: when (normalizeMediaType(mediaType)) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> ""
            }
        }

        private fun snapshotOf(notes: Collection<StoredNote>, categories: Set<String>): LibrarySnapshot {
            fun summariesFor(state: NoteState) = notes
                .filter { it.state == state }
                .map(StoredNote::toSummary)
                .sortedByDescending(NoteSummary::updatedAt)

            return LibrarySnapshot(
                active = summariesFor(NoteState.ACTIVE),
                trash = summariesFor(NoteState.TRASHED),
                categories = if (categories.isEmpty()) emptyMap() else mapOf(LocalSourceId to categories),
            )
        }

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

        private fun writeStreamAtomically(path: Path, content: InputStream) {
            path.parent.createDirectories()
            val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
            try {
                Files.newOutputStream(
                    temporary,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use(content::copyTo)
                moveAtomically(temporary, path)
            } finally {
                temporary.deleteIfExists()
            }
        }

        private fun deleteEmptyDirectories(path: Path) {
            if (!path.exists()) return
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { candidate ->
                    if (Files.isDirectory(candidate) && Files.list(candidate).use { !it.findAny().isPresent }) {
                        candidate.deleteIfExists()
                    }
                }
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

/** Why one stored record could not be loaded; the record stays on disk untouched. */
internal class RecordLoadFailure(
    val reason: UnreadableReason,
    message: String,
    val properties: Properties? = null,
    val metadataPath: Path? = null,
    val contentPath: Path? = null,
) : Exception(message)

private data class StoredAttachment(
    val id: AttachmentId,
    val noteId: NoteId,
    val displayName: String,
    val mediaType: String,
    val relativePath: String,
    val createdAt: Instant,
) {
    fun toPublic(markdownTarget: String): ManagedAttachment = ManagedAttachment(
        id = id,
        noteId = noteId,
        displayName = displayName,
        mediaType = mediaType,
        markdownTarget = markdownTarget,
        createdAt = createdAt,
    )
}

private data class StoredNote(
    val id: NoteId,
    val title: String,
    val category: String,
    val favorite: Boolean,
    val state: NoteState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val trashedAt: Instant?,
    val titleFixed: Boolean,
    val relativePath: String,
    val markdown: String,
    val pinned: Boolean = false,
) {
    fun toPublic(): Note = Note(
        id = id,
        sourceId = LocalSourceId,
        syncState = NoteSyncState.LOCAL_SOURCE,
        title = title,
        markdown = markdown,
        category = category,
        favorite = favorite,
        pinned = pinned,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt,
        trashedAt = trashedAt,
    )

    fun toSummary(): NoteSummary = NoteSummary(
        id = id,
        sourceId = LocalSourceId,
        syncState = NoteSyncState.LOCAL_SOURCE,
        title = title,
        snippet = markdown
            .lineSequence()
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
    )
}
