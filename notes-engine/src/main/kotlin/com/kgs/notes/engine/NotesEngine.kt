package com.kgs.notes.engine

import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class NoteId(val value: String)

enum class NoteState {
    DRAFT,
    ACTIVE,
    TRASHED,
}

enum class NoteSyncState {
    LOCAL_SOURCE,
    SAVED_LOCALLY,
    SYNCING,
    SYNCED,
    NEEDS_ATTENTION,
}

data class Note(
    val id: NoteId,
    val sourceId: SourceId,
    val syncState: NoteSyncState,
    val title: String,
    val markdown: String,
    val category: String,
    val favorite: Boolean,
    val state: NoteState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val trashedAt: Instant? = null,
)

data class NoteSummary(
    val id: NoteId,
    val sourceId: SourceId,
    val syncState: NoteSyncState,
    val title: String,
    val snippet: String,
    val category: String,
    val favorite: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class LibrarySnapshot(
    val active: List<NoteSummary> = emptyList(),
    val trash: List<NoteSummary> = emptyList(),
) {
    val favorites: List<NoteSummary> get() = active.filter(NoteSummary::favorite)
}

@JvmInline
value class AttachmentId(val value: String)

class AttachmentImport(
    val displayName: String,
    val mediaType: String,
    val openContent: () -> InputStream,
)

data class ManagedAttachment(
    val id: AttachmentId,
    val noteId: NoteId,
    val displayName: String,
    val mediaType: String,
    val markdownTarget: String,
    val createdAt: Instant,
)

interface NotesEngine {
    val library: StateFlow<LibrarySnapshot>
    val sources: StateFlow<List<SourceDescriptor>>

    suspend fun createDraft(
        category: String = "",
        sourceId: SourceId = LocalSourceId,
    ): NoteId

    suspend fun attachSource(source: Source)

    suspend fun refreshSources()

    suspend fun synchronize(id: NoteId)

    /** Moves the durable Note ownership to another Source and returns its resulting local identity. */
    suspend fun moveToSource(id: NoteId, sourceId: SourceId): NoteId

    suspend fun note(id: NoteId): Note?

    suspend fun updateContent(id: NoteId, markdown: String)

    suspend fun rename(id: NoteId, title: String)

    suspend fun setCategory(id: NoteId, category: String)

    suspend fun importManagedAttachment(
        noteId: NoteId,
        attachment: AttachmentImport,
    ): ManagedAttachment

    suspend fun managedAttachments(noteId: NoteId): List<ManagedAttachment>

    fun openManagedAttachment(id: AttachmentId): InputStream?

    suspend fun toggleFavorite(id: NoteId)

    suspend fun moveToTrash(id: NoteId)

    suspend fun restore(id: NoteId)

    suspend fun deletePermanently(id: NoteId)

    suspend fun close(id: NoteId)
}
