package com.kgs.notes.nextcloud

import com.kgs.notes.engine.Source
import com.kgs.notes.engine.SourceCapabilities
import com.kgs.notes.engine.SourceDescriptor
import com.kgs.notes.engine.SourceId
import com.kgs.notes.engine.SourceConflictException
import com.kgs.notes.engine.SourceNote
import com.kgs.notes.engine.SourceNoteDraft
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotesCapabilities(
    val apiVersion: String,
)

data class NotesSettings(
    val fileSuffix: String,
)

data class RemoteNote(
    val id: Long,
    val etag: String,
    val title: String,
    val markdown: String,
    val category: String,
    val favorite: Boolean,
    val modifiedAt: Instant,
    val readOnly: Boolean,
)

interface NextcloudNotesApi {
    suspend fun capabilities(): NotesCapabilities

    suspend fun notes(): List<RemoteNote>

    suspend fun note(id: Long): RemoteNote

    suspend fun create(note: RemoteNoteDraft): RemoteNote

    suspend fun update(id: Long, expectedEtag: String, note: RemoteNoteDraft): RemoteNote

    suspend fun delete(id: Long)

    suspend fun updateSettings(fileSuffix: String): NotesSettings
}

data class RemoteNoteDraft(
    val title: String,
    val markdown: String,
    val category: String,
    val favorite: Boolean,
    val modifiedAt: Instant,
)

class UnsupportedNotesApi(
    val actualVersion: String,
    val minimumVersion: String,
) : IllegalStateException(
    "Nextcloud Notes API $actualVersion is unsupported; $minimumVersion or newer is required",
)

class NextcloudNotesSource(
    sourceName: String,
    private val api: NextcloudNotesApi,
    sourceId: SourceId = SourceId("nextcloud"),
) : Source {
    override val descriptor = SourceDescriptor(
        id = sourceId,
        name = sourceName,
        capabilities = SourceCapabilities(
            markdown = true,
            categories = true,
            favorites = true,
            attachments = true,
            writable = true,
        ),
    )

    private val mutableRemoteNotes = MutableStateFlow<List<RemoteNote>>(emptyList())
    val remoteNotes: StateFlow<List<RemoteNote>> = mutableRemoteNotes.asStateFlow()
    private val mutableNotes = MutableStateFlow<List<SourceNote>>(emptyList())
    override val notes: StateFlow<List<SourceNote>> = mutableNotes.asStateFlow()

    override suspend fun refresh() {
        val capabilities = api.capabilities()
        if (NotesApiVersion(capabilities.apiVersion) < MINIMUM_API_VERSION) {
            throw UnsupportedNotesApi(
                actualVersion = capabilities.apiVersion,
                minimumVersion = MINIMUM_API_VERSION.toString(),
            )
        }
        val remote = api.notes()
        mutableRemoteNotes.value = remote
        mutableNotes.value = remote.map { it.toSourceNote() }
    }

    override suspend fun createNote(note: SourceNoteDraft): SourceNote {
        val created = api.create(
            RemoteNoteDraft(
                title = note.title,
                markdown = note.markdown,
                category = note.category,
                favorite = note.favorite,
                modifiedAt = note.modifiedAt,
            ),
        )
        mutableRemoteNotes.value = mutableRemoteNotes.value + created
        val published = created.toSourceNote()
        mutableNotes.value = mutableNotes.value + published
        return published
    }

    override suspend fun updateNote(
        remoteId: String,
        expectedRevision: String,
        note: SourceNoteDraft,
    ): SourceNote {
        val id = remoteId.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid Nextcloud Note identity")
        val updated = try {
            api.update(
                id = id,
                expectedEtag = expectedRevision,
                note = RemoteNoteDraft(
                    title = note.title,
                    markdown = note.markdown,
                    category = note.category,
                    favorite = note.favorite,
                    modifiedAt = note.modifiedAt,
                ),
            )
        } catch (failure: NextcloudApiException) {
            if (failure.statusCode == 412) throw SourceConflictException()
            throw failure
        }
        mutableRemoteNotes.value = mutableRemoteNotes.value
            .filterNot { it.id == updated.id } + updated
        val published = updated.toSourceNote()
        mutableNotes.value = mutableNotes.value
            .filterNot { it.remoteId == published.remoteId } + published
        return published
    }

    override suspend fun deleteNote(remoteId: String, expectedRevision: String) {
        val id = remoteId.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid Nextcloud Note identity")
        val current = api.note(id)
        if (current.etag != expectedRevision) throw SourceConflictException()
        api.delete(id)
        mutableRemoteNotes.value = mutableRemoteNotes.value.filterNot { it.id == id }
        mutableNotes.value = mutableNotes.value.filterNot { it.remoteId == remoteId }
    }

    suspend fun setFileSuffix(fileSuffix: String): String {
        require(fileSuffix == ".md" || fileSuffix == ".txt")
        return api.updateSettings(fileSuffix).fileSuffix
    }

    private fun RemoteNote.toSourceNote(): SourceNote = SourceNote(
        remoteId = id.toString(),
        revision = etag,
        title = title,
        markdown = markdown,
        category = category,
        favorite = favorite,
        modifiedAt = modifiedAt,
        readOnly = readOnly,
    )

    private data class NotesApiVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) : Comparable<NotesApiVersion> {
        constructor(value: String) : this(
            major = value.part(0),
            minor = value.part(1),
            patch = value.part(2),
        )

        override fun compareTo(other: NotesApiVersion): Int = compareValuesBy(
            this,
            other,
            NotesApiVersion::major,
            NotesApiVersion::minor,
            NotesApiVersion::patch,
        )

        override fun toString(): String = if (patch == 0) "$major.$minor" else "$major.$minor.$patch"

        private companion object {
            fun String.part(index: Int): Int = split('.')
                .getOrNull(index)
                ?.takeWhile(Char::isDigit)
                ?.toIntOrNull()
                ?: 0
        }
    }

    private companion object {
        val MINIMUM_API_VERSION = NotesApiVersion("1.4")
    }
}
