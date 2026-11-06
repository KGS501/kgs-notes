package com.kgs.notes.engine

import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    /** Device-local prominence; independent of the Source Favorite flag. */
    val pinned: Boolean = false,
    /** The latest attempt to reach this Note's Source failed; pending changes stay Saved Locally. */
    val syncFailed: Boolean = false,
    /** The Source marks this Note read-only (Nextcloud `readonly`); only Favorite can change. Local Notes are never read-only. */
    val readOnly: Boolean = false,
    /** Why this Note needs attention; null when there is nothing to explain. */
    val attention: NoteAttention? = null,
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
    /** Bounded canonical Markdown used only to render a library card preview. */
    val previewMarkdown: String = snippet,
    val pinned: Boolean = false,
    /** The latest attempt to reach this Note's Source failed; pending changes stay Saved Locally. */
    val syncFailed: Boolean = false,
    val readOnly: Boolean = false,
    val attention: NoteAttention? = null,
)

/** Explains a Note that the engine could not reconcile on its own. Nothing was overwritten. */
enum class NoteAttention {
    /** Local and remote both changed the same field since the last synchronized base. */
    CONFLICT,
    /** A create may have reached the Source but its remote identity cannot be proven. */
    UNCERTAIN_CREATE,
    /** Local changes exist but the Source marks the Note read-only; only Favorite can synchronize. */
    READ_ONLY_CHANGES,
    /** The Source could not read this Note's body; the last good body is kept and writes pause. */
    REMOTE_CONTENT_UNAVAILABLE,
    /** The remote Note disappeared while local changes were pending; they are kept. */
    REMOTE_MISSING,
    /** A move to this Source was recorded but the Source has not confirmed the create yet. */
    TRANSFER_PENDING,
}

/** The Note's Source does not permit this change. Editor drafts must be kept (offer [NotesEngine.copyToLocal]). */
class ReadOnlyNoteException : IllegalStateException("This Note is read-only")

/** A move was issued but its outcome is not confirmed; the Note stays listed under the destination, nothing is lost. */
class TransferPendingException(cause: Throwable? = null) :
    java.io.IOException("The Source has not confirmed this move yet", cause)

enum class UnreadableRecordKind { LOCAL_NOTE, LOCAL_ATTACHMENT, WORKING_COPY }

enum class UnreadableReason { UNSUPPORTED_VERSION, MALFORMED_METADATA, MISSING_CONTENT, INACCESSIBLE }

/** A stored record the engine could not load. Its evidence files are preserved untouched until explicitly discarded. */
data class UnreadableRecord(
    val id: String,
    val kind: UnreadableRecordKind,
    val reason: UnreadableReason,
    val noteId: NoteId?,
    val sourceId: SourceId?,
    val title: String?,
    val detail: String,
    val files: List<String>,
    val hasContent: Boolean,
)

data class LibrarySnapshot(
    val active: List<NoteSummary> = emptyList(),
    val trash: List<NoteSummary> = emptyList(),
    /** Categories each Source keeps even while no Note uses them. */
    val categories: Map<SourceId, Set<String>> = emptyMap(),
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

    /** Removes a Source from this device after all Working Copies are confirmed by it. */
    suspend fun removeSource(id: SourceId)

    /** Attempts every connected Source, retaining unavailable Working Copies; reports failures after the other Sources finish. */
    suspend fun refreshSources()

    suspend fun synchronize(id: NoteId)

    /** Moves durable Note ownership after destination confirmation, preserving its local identity. */
    suspend fun moveToSource(id: NoteId, sourceId: SourceId): NoteId

    suspend fun note(id: NoteId): Note?

    /** Keeps an editor's displayed base stable until [close], so external refreshes cannot hide concurrent changes. */
    suspend fun openEditingSession(id: NoteId): Note? = note(id)

    suspend fun updateContent(id: NoteId, markdown: String)

    suspend fun rename(id: NoteId, title: String)

    suspend fun setCategory(id: NoteId, category: String)

    /** Renames this Source's Category and descendants using ordinary durable Note updates. */
    suspend fun renameCategory(sourceId: SourceId, category: String, newCategory: String): Set<NoteId> =
        replaceCategoryAssignments(sourceId, category, normalizedCategoryPath(newCategory))

    /** Keeps an empty Category available in this Source and returns its normalized path. */
    suspend fun createCategory(sourceId: SourceId, category: String): String =
        throw UnsupportedOperationException("This Source cannot keep empty Categories")

    /** Clears Category assignments, including descendants, while keeping every Note. */
    suspend fun deleteCategory(sourceId: SourceId, category: String): Set<NoteId> =
        replaceCategoryAssignments(sourceId, category, "")

    suspend fun importManagedAttachment(
        noteId: NoteId,
        attachment: AttachmentImport,
    ): ManagedAttachment

    suspend fun managedAttachments(noteId: NoteId): List<ManagedAttachment>

    fun openManagedAttachment(id: AttachmentId): InputStream?

    suspend fun toggleFavorite(id: NoteId)

    suspend fun togglePin(id: NoteId) { throw UnsupportedOperationException("Pinning is unavailable") }

    suspend fun moveToTrash(id: NoteId)

    suspend fun restore(id: NoteId)

    suspend fun deletePermanently(id: NoteId)

    suspend fun close(id: NoteId)

    /**
     * Creates a new, independent Local Source Note from [id] (any Source, any state) without touching the
     * original or any remote. [markdown], when given, replaces the stored body (for rescuing an unsaved draft).
     */
    suspend fun copyToLocal(id: NoteId, markdown: String? = null): NoteId {
        val note = requireNotNull(note(id)) { "Cannot copy a missing Note" }
        val copy = createDraft(note.category, LocalSourceId)
        updateContent(copy, markdown ?: note.markdown)
        rename(copy, note.title)
        if (note.favorite) toggleFavorite(copy)
        close(copy)
        return copy
    }

    /** Records that could not be loaded; they are kept on disk and listed here instead of disappearing. */
    val unreadableRecords: StateFlow<List<UnreadableRecord>> get() = NO_UNREADABLE_RECORDS

    /** Saves the preserved content of an unreadable record as a new Local Note. Never modifies the evidence. */
    suspend fun rescueUnreadableRecord(recordId: String): NoteId =
        throw UnsupportedOperationException("Recovery is unavailable")

    /** Explicit user action: deletes the preserved evidence of an unreadable record. */
    suspend fun discardUnreadableRecord(recordId: String): Unit =
        throw UnsupportedOperationException("Recovery is unavailable")
}

private val NO_UNREADABLE_RECORDS: StateFlow<List<UnreadableRecord>> =
    MutableStateFlow<List<UnreadableRecord>>(emptyList()).asStateFlow()
