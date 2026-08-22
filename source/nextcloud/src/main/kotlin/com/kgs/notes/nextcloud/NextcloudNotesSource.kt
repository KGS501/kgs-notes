package com.kgs.notes.nextcloud

import com.kgs.notes.engine.Source
import com.kgs.notes.engine.SourceCapabilities
import com.kgs.notes.engine.SourceDescriptor
import com.kgs.notes.engine.SourceId
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotesCapabilities(
    val apiVersion: String,
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
}

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

    override suspend fun refresh() {
        val capabilities = api.capabilities()
        if (NotesApiVersion(capabilities.apiVersion) < MINIMUM_API_VERSION) {
            throw UnsupportedNotesApi(
                actualVersion = capabilities.apiVersion,
                minimumVersion = MINIMUM_API_VERSION.toString(),
            )
        }
        mutableRemoteNotes.value = api.notes()
    }

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
