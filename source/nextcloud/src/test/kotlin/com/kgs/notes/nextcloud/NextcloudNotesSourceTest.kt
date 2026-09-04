package com.kgs.notes.nextcloud

import com.kgs.notes.engine.SourceNoteDraft
import com.kgs.notes.engine.SourceConflictException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

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

    @Test
    fun `the authenticated HTTP adapter loads a stock Notes API snapshot`() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"ocs":{"data":{"capabilities":{"notes":{"api_version":["0.2","1.4"],"version":"4.11.0"}}}}}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    [{
                      "id":42,
                      "etag":"etag-42",
                      "readonly":false,
                      "modified":1787385600,
                      "title":"Field notes",
                      "category":"Trips/Forest",
                      "content":"# Field notes",
                      "favorite":true
                    }]
                    """.trimIndent(),
                ),
            )
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            val credentials = NextcloudCredentials(
                serverUrl = server.url("/cloud/").toString(),
                loginName = "river",
                appPassword = "app-secret",
            )
            val source = NextcloudNotesSource(
                sourceName = "Home cloud",
                api = HttpNextcloudNotesApi(credentials, client),
            )

            source.refresh()

            assertEquals(42, source.remoteNotes.value.single().id)
            assertEquals("etag-42", source.remoteNotes.value.single().etag)
            val capabilitiesRequest = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("/cloud/ocs/v2.php/cloud/capabilities?format=json", capabilitiesRequest?.path)
            assertEquals("true", capabilitiesRequest?.getHeader("OCS-APIRequest"))
            assertEquals(
                Credentials.basic("river", "app-secret"),
                capabilitiesRequest?.getHeader("Authorization"),
            )
            assertEquals(
                "/cloud/index.php/apps/notes/api/v1/notes?chunkSize=100",
                server.takeRequest(1, TimeUnit.SECONDS)?.path,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `publishing a Note adopts the identity and title confirmed by its Source`() = runBlocking {
        val api = FakeNotesApi(apiVersion = "1.4")
        val source = NextcloudNotesSource(sourceName = "Home cloud", api = api)

        val published = source.createNote(
            SourceNoteDraft(
                title = "Field: notes",
                markdown = "# Field notes",
                category = "Trips",
                favorite = false,
                modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
            ),
        )

        assertEquals("71", published.remoteId)
        assertEquals("Field notes", published.title)
        assertEquals("etag-created", published.revision)
    }

    @Test
    fun `updating a Note protects the known revision with If-Match`() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"id":42,"etag":"etag-new","readonly":false,"modified":1787558400,
                     "title":"Field notes","category":"Trips","content":"# Updated","favorite":true}
                    """.trimIndent(),
                ),
            )
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            val source = NextcloudNotesSource(
                sourceName = "Home cloud",
                api = HttpNextcloudNotesApi(
                    NextcloudCredentials(server.url("/cloud/").toString(), "river", "app-secret"),
                    client,
                ),
            )

            val updated = source.updateNote(
                remoteId = "42",
                expectedRevision = "etag-old",
                note = SourceNoteDraft(
                    title = "Field notes",
                    markdown = "# Updated",
                    category = "Trips",
                    favorite = true,
                    modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
                ),
            )

            assertEquals("etag-new", updated.revision)
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("PUT", request?.method)
            assertEquals("/cloud/index.php/apps/notes/api/v1/notes/42", request?.path)
            assertEquals("etag-old", request?.getHeader("If-Match"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `deleting first verifies the latest revision before using the stock API`() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"id":42,"etag":"known","readonly":false,"modified":1787558400,
                    "title":"Field notes","category":"","content":"# Saved in Trash","favorite":false}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200))
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            val source = NextcloudNotesSource(
                sourceName = "Home cloud",
                api = HttpNextcloudNotesApi(
                    NextcloudCredentials(server.url("/cloud/").toString(), "river", "app-secret"),
                    client,
                ),
            )

            source.deleteNote("42", "known")

            assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)?.method)
            assertEquals("DELETE", server.takeRequest(1, TimeUnit.SECONDS)?.method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Markdown preference updates the stock Notes setting for new files`() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("""{"notesPath":"Notes","fileSuffix":".md"}"""))
            start()
        }
        try {
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            val source = NextcloudNotesSource(
                sourceName = "Home cloud",
                api = HttpNextcloudNotesApi(
                    NextcloudCredentials(server.url("/cloud/").toString(), "river", "app-secret"),
                    client,
                ),
            )

            assertEquals(".md", source.setFileSuffix(".md"))

            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("PUT", request?.method)
            assertEquals("/cloud/index.php/apps/notes/api/v1/settings", request?.path)
            assertEquals(".md", request?.body?.readUtf8()?.let { body ->
                kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["fileSuffix"]?.jsonPrimitive?.content
            })
        } finally {
            server.shutdown()
        }
    }

    private class FakeNotesApi(
        private val apiVersion: String,
        private val remoteNotes: List<RemoteNote> = emptyList(),
    ) : NextcloudNotesApi {
        override suspend fun capabilities(): NotesCapabilities = NotesCapabilities(apiVersion)

        override suspend fun notes(): List<RemoteNote> = remoteNotes

        override suspend fun note(id: Long): RemoteNote = remoteNotes.single { it.id == id }

        override suspend fun create(note: RemoteNoteDraft): RemoteNote = RemoteNote(
            id = 71,
            etag = "etag-created",
            title = "Field notes",
            markdown = note.markdown,
            category = note.category,
            favorite = note.favorite,
            modifiedAt = note.modifiedAt,
            readOnly = false,
        )

        override suspend fun update(
            id: Long,
            expectedEtag: String,
            note: RemoteNoteDraft,
        ): RemoteNote = RemoteNote(
            id = id,
            etag = expectedEtag,
            title = note.title,
            markdown = note.markdown,
            category = note.category,
            favorite = note.favorite,
            modifiedAt = note.modifiedAt,
            readOnly = false,
        )

        override suspend fun delete(id: Long) = Unit

        override suspend fun updateSettings(fileSuffix: String) = NotesSettings(fileSuffix)
    }
}
