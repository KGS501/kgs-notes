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

    /** Deletes only after the Source verifies the last known revision when its protocol permits. */
    suspend fun deleteNote(remoteId: String, expectedRevision: String)
}
