package com.kgs.notes.engine

import java.io.Writer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.Properties
import java.util.UUID
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
) : NotesEngine {
    private val metadataRoot = root.resolve(".kgs/notes")
    private val draftsRoot = root.resolve(".kgs/drafts")
    private val mutex = Mutex()
    private val notes = loadedNotes.toMutableMap()
    private val mutableLibrary = MutableStateFlow(snapshotOf(notes.values))

    override val library: StateFlow<LibrarySnapshot> = mutableLibrary.asStateFlow()

    override suspend fun createDraft(category: String): NoteId = mutex.withLock {
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

    override suspend fun note(id: NoteId): Note? = mutex.withLock {
        notes[id]?.toPublic()
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
        writeTextAtomically(contentPath(desiredPath), markdown)
        val updated = current.copy(
            title = title,
            state = state,
            updatedAt = clock.instant(),
            relativePath = desiredPath,
            markdown = markdown,
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
        writeTextAtomically(contentPath(desiredPath), current.markdown)
        val updated = current.copy(
            title = cleanTitle,
            titleFixed = true,
            state = if (current.state == NoteState.DRAFT) NoteState.ACTIVE else current.state,
            relativePath = desiredPath,
            updatedAt = clock.instant(),
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
        val desiredPath = if (current.state == NoteState.DRAFT) {
            current.relativePath
        } else {
            availableContentPath(current.title, normalized, current.id, current.relativePath)
        }
        writeTextAtomically(contentPath(desiredPath), current.markdown)
        val updated = current.copy(
            category = normalized,
            relativePath = desiredPath,
            updatedAt = clock.instant(),
        )
        persistMetadata(updated)
        if (desiredPath != current.relativePath) contentPath(current.relativePath).deleteIfExists()
        notes[id] = updated
        publish()
    }

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
        val removed = notes.remove(id) ?: return@withLock
        contentPath(removed.relativePath).deleteIfExists()
        metadataPath(id).deleteIfExists()
        publish()
    }

    override suspend fun close(id: NoteId) = mutex.withLock {
        val current = notes[id] ?: return@withLock
        if (current.state == NoteState.DRAFT && current.markdown.isBlank() && !current.titleFixed) {
            notes.remove(id)
            contentPath(current.relativePath).deleteIfExists()
            metadataPath(id).deleteIfExists()
        } else if (!current.titleFixed) {
            val closed = current.copy(titleFixed = true)
            persistMetadata(closed)
            notes[id] = closed
        }
        publish()
    }

    private suspend fun updateMetadata(id: NoteId, transform: (StoredNote) -> StoredNote) = mutex.withLock {
        val current = notes[id] ?: return@withLock
        val updated = transform(current)
        persistMetadata(updated)
        notes[id] = updated
        publish()
    }

    private fun publish() {
        mutableLibrary.value = snapshotOf(notes.values)
    }

    private fun persistMetadata(note: StoredNote) {
        val properties = Properties().apply {
            setProperty("schema", "1")
            setProperty("id", note.id.value)
            setProperty("title", note.title)
            setProperty("category", note.category)
            setProperty("favorite", note.favorite.toString())
            setProperty("state", note.state.name)
            setProperty("createdAt", note.createdAt.toString())
            setProperty("updatedAt", note.updatedAt.toString())
            setProperty("trashedAt", note.trashedAt?.toString().orEmpty())
            setProperty("titleFixed", note.titleFixed.toString())
            setProperty("relativePath", note.relativePath)
        }
        writePropertiesAtomically(metadataPath(note.id), properties)
    }

    private fun metadataPath(id: NoteId): Path = metadataRoot.resolve("${id.value}.properties")

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

    companion object {
        private const val DEFAULT_TITLE = "New note"
        private const val MAX_TITLE_CODE_POINTS = 80

        fun open(root: Path, clock: Clock = Clock.systemUTC()): LocalNotesEngine {
            val normalizedRoot = root.toAbsolutePath().normalize()
            normalizedRoot.createDirectories()
            normalizedRoot.resolve(".kgs/notes").createDirectories()
            normalizedRoot.resolve(".kgs/drafts").createDirectories()
            val loaded = loadNotes(normalizedRoot)
            return LocalNotesEngine(normalizedRoot, clock, loaded)
        }

        private fun loadNotes(root: Path): Map<NoteId, StoredNote> {
            val metadataRoot = root.resolve(".kgs/notes")
            if (!metadataRoot.exists()) return emptyMap()
            return Files.list(metadataRoot).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.extension == "properties" }
                    .map { path -> runCatching { readStoredNote(root, path) }.getOrNull() }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
                    .associateBy(StoredNote::id)
            }
        }

        private fun readStoredNote(root: Path, metadataPath: Path): StoredNote {
            val properties = Properties()
            Files.newBufferedReader(metadataPath).use(properties::load)
            require(properties.getProperty("schema") == "1")
            val id = NoteId(properties.getProperty("id") ?: metadataPath.nameWithoutExtension)
            val relativePath = requireNotNull(properties.getProperty("relativePath"))
            val contentPath = root.resolve(relativePath).normalize()
            require(contentPath.startsWith(root))
            return StoredNote(
                id = id,
                title = properties.getProperty("title", DEFAULT_TITLE),
                category = normalizeCategory(properties.getProperty("category", "")),
                favorite = properties.getProperty("favorite", "false").toBoolean(),
                state = NoteState.valueOf(properties.getProperty("state", NoteState.ACTIVE.name)),
                createdAt = Instant.parse(properties.getProperty("createdAt")),
                updatedAt = Instant.parse(properties.getProperty("updatedAt")),
                trashedAt = properties.getProperty("trashedAt").takeUnless(String?::isNullOrBlank)?.let(Instant::parse),
                titleFixed = properties.getProperty("titleFixed", "true").toBoolean(),
                relativePath = relativePath,
                markdown = if (contentPath.exists()) contentPath.readText() else "",
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

        private fun snapshotOf(notes: Collection<StoredNote>): LibrarySnapshot {
            fun summariesFor(state: NoteState) = notes
                .filter { it.state == state }
                .map(StoredNote::toSummary)
                .sortedByDescending(NoteSummary::updatedAt)

            return LibrarySnapshot(
                active = summariesFor(NoteState.ACTIVE),
                trash = summariesFor(NoteState.TRASHED),
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
) {
    fun toPublic(): Note = Note(
        id = id,
        sourceId = LocalSourceId,
        syncState = NoteSyncState.LOCAL_SOURCE,
        title = title,
        markdown = markdown,
        category = category,
        favorite = favorite,
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
        category = category,
        favorite = favorite,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
