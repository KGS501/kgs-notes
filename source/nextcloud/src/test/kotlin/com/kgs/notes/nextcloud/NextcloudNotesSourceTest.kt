package com.kgs.notes.nextcloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant
import kotlinx.coroutines.runBlocking

class NextcloudNotesSourceTest {
    @Test
    fun `stock servers must expose the conflict-safe Notes API before refresh`() = runBlocking {
        val source = NextcloudNotesSource(
            sourceName = "Home cloud",
            api = FakeNotesApi(apiVersion = "1.3"),
        )

        val error = assertFailsWith<UnsupportedNotesApi> { source.refresh() }

        assertEquals("1.4", error.minimumVersion)
    }

    @Test
    fun `a compatible stock server publishes its remote note snapshot`() = runBlocking {
        val remote = RemoteNote(
            id = 42,
            etag = "etag-42",
            title = "Field notes",
            markdown = "# Field notes",
            category = "Trips/Forest",
            favorite = true,
            modifiedAt = Instant.parse("2026-08-22T08:00:00Z"),
            readOnly = false,
        )
        val source = NextcloudNotesSource(
            sourceName = "Home cloud",
            api = FakeNotesApi(apiVersion = "1.4", remoteNotes = listOf(remote)),
        )

        source.refresh()

        assertEquals(listOf(remote), source.remoteNotes.value)
    }

    private class FakeNotesApi(
        private val apiVersion: String,
        private val remoteNotes: List<RemoteNote> = emptyList(),
    ) : NextcloudNotesApi {
        override suspend fun capabilities(): NotesCapabilities = NotesCapabilities(apiVersion)

        override suspend fun notes(): List<RemoteNote> = remoteNotes
    }
}
