package com.kgs.notes.engine

import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class SourceId(val value: String)

val LocalSourceId = SourceId("local")

data class SourceCapabilities(
    val markdown: Boolean,
    val categories: Boolean,
    val favorites: Boolean,
    val attachments: Boolean,
    val writable: Boolean,
)

data class SourceDescriptor(
    val id: SourceId,
    val name: String,
    val capabilities: SourceCapabilities,
)

data class SourceNote(
    val remoteId: String,
    val revision: String,
    val title: String,
    val markdown: String,
    val category: String,
    val favorite: Boolean,
    val modifiedAt: Instant,
    val readOnly: Boolean,
    /**
     * The Source could not read this Note's body (Nextcloud `error=true`). Its [markdown] and [readOnly]
     * are placeholders and must never replace a cached body or become a synchronized base.
     */
    val contentUnavailable: Boolean = false,
)

data class SourceNoteDraft(
    val title: String,
    val markdown: String,
    val category: String,
    val favorite: Boolean,
    val modifiedAt: Instant,
)

/** The Source rejected a write because its remote revision changed. */
class SourceConflictException : IllegalStateException("The Source Note changed remotely")

/** External synchronization seam. Implementations own protocol choreography. */
interface Source {
    val descriptor: SourceDescriptor
    val notes: StateFlow<List<SourceNote>>

    suspend fun refresh()

    suspend fun createNote(note: SourceNoteDraft): SourceNote

    suspend fun updateNote(
        remoteId: String,
        expectedRevision: String,
        note: SourceNoteDraft,
    ): SourceNote

    /**
     * Deletes only after the Source verifies the last known revision when its protocol permits.
     * A remote Note that is already absent counts as a successful deletion.
     */
    suspend fun deleteNote(remoteId: String, expectedRevision: String)

    /**
     * Changes only the Favorite flag, without sending content, title, Category or modification time.
     * Sources whose protocol permits it on read-only Notes (Nextcloud) implement this.
     */
    suspend fun updateFavorite(remoteId: String, favorite: Boolean): SourceNote =
        throw UnsupportedOperationException("This Source cannot change only the Favorite flag")
}
