package com.kgs.notes.engine

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

interface NotesEngine {
    val library: StateFlow<LibrarySnapshot>

    suspend fun createDraft(category: String = ""): NoteId

    suspend fun note(id: NoteId): Note?

    suspend fun updateContent(id: NoteId, markdown: String)

    suspend fun rename(id: NoteId, title: String)

    suspend fun setCategory(id: NoteId, category: String)

    suspend fun toggleFavorite(id: NoteId)

    suspend fun moveToTrash(id: NoteId)

    suspend fun restore(id: NoteId)

    suspend fun deletePermanently(id: NoteId)

    suspend fun close(id: NoteId)
}
