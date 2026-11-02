package com.kgs.notes.nextcloud

import com.kgs.notes.engine.ConnectedNotesEngine
import com.kgs.notes.engine.NoteAttention
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.ReadOnlyNoteException
import com.kgs.notes.engine.SourceConflictException
import com.kgs.notes.engine.SourceId
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.io.TempDir

/** Notes API protocol details: read errors (#29), absent deletions (#30) and favorite-only updates on read-only Notes (#31). */
class NextcloudProtocolTest {
    @TempDir
    lateinit var root: Path

    private val capabilities = """{"ocs":{"data":{"capabilities":{"notes":{"api_version":["1.3"]}}}}}"""

    private fun <T> withServer(block: suspend (MockWebServer, HttpNextcloudNotesApi) -> T): T = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTrust.sslSocketFactory(), false); start() }
        try {
            val client = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
            block(server, HttpNextcloudNotesApi(NextcloudCredentials(server.url("/").toString(), "test-user", "test-password"), client))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a content read error is reported as unavailable content, never as Markdown or permissions`() = withServer { server, api ->
        server.enqueue(MockResponse().setBody(capabilities))
        server.enqueue(MockResponse().setBody(
            """[{"id":1,"etag":"e2","title":"Locked","content":"Error: could not read file","category":"","modified":1787558400,""" +
                """"readonly":true,"error":true,"errorType":"File"},""" +
                """{"id":2,"etag":"e1","title":"Fine","content":"Readable","category":"","modified":1787558400,"readonly":false,"error":false}]""",
        ))
        val source = NextcloudNotesSource("Home cloud", api)

        source.refresh()

        val locked = source.notes.value.single { it.remoteId == "1" }
        assertTrue(locked.contentUnavailable)
        assertEquals("", locked.markdown)
        assertFalse(locked.readOnly)
        val fine = source.notes.value.single { it.remoteId == "2" }
        assertFalse(fine.contentUnavailable)
        assertEquals("Readable", fine.markdown)
    }

    @Test
    fun `deleting an already absent Note succeeds while other failures stay unconfirmed`() = withServer { server, api ->
        val source = NextcloudNotesSource("Home cloud", api)
        // GET before DELETE finds nothing: an earlier accepted DELETE whose response was lost.
        server.enqueue(MockResponse().setResponseCode(404))
        source.deleteNote("7", "e1")
        // DELETE itself finds nothing: another client deleted it in between.
        server.enqueue(MockResponse().setBody("""{"id":7,"etag":"e1","title":"T","content":"C","category":"","modified":1787558400}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        source.deleteNote("7", "e1")
        // Authentication and server failures are not confirmations.
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, assertFailsWith<NextcloudApiException> { source.deleteNote("7", "e1") }.statusCode)
        server.enqueue(MockResponse().setBody("""{"id":7,"etag":"e1","title":"T","content":"C","category":"","modified":1787558400}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(503, assertFailsWith<NextcloudApiException> { source.deleteNote("7", "e1") }.statusCode)
        // A Note that still exists keeps its revision protection.
        server.enqueue(MockResponse().setBody("""{"id":7,"etag":"newer","title":"T","content":"C","category":"","modified":1787558400}"""))
        assertFailsWith<SourceConflictException> { source.deleteNote("7", "e1") }
        val methods = (1..server.requestCount).map { server.takeRequest(1, TimeUnit.SECONDS)!!.method }
        assertEquals(listOf("GET", "GET", "DELETE", "GET", "GET", "DELETE", "GET"), methods)
    }

    @Test
    fun `a favorite-only update sends nothing but the favorite flag`() = withServer { server, api ->
        server.enqueue(MockResponse().setBody(
            """{"id":3,"etag":"e9","title":"Shared","content":"Their text","category":"Team","favorite":true,"modified":1787558400,"readonly":true}""",
        ))
        val source = NextcloudNotesSource("Home cloud", api)

        val updated = source.updateFavorite("3", true)

        assertTrue(updated.favorite)
        assertTrue(updated.readOnly)
        val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("PUT", request.method)
        assertEquals("/index.php/apps/notes/api/v1/notes/3", request.path)
        assertNull(request.getHeader("If-Match"))
        assertEquals(setOf("favorite"), Json.parseToJsonElement(request.body.readUtf8()).jsonObject.keys)
    }

    @Test
    fun `through the engine a read-only Nextcloud Note only ever receives a favorite-only request`() {
        val notes = mutableMapOf(
            3L to """{"id":3,"etag":"e1","title":"Shared","content":"Their text","category":"Team","favorite":false,"modified":1787558400,"readonly":true}""",
        )
        val requests = mutableListOf<String>()
        withServer { server, api ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    requests += "${request.method} ${request.path} $body"
                    return when {
                        request.path!!.startsWith("/ocs/") -> MockResponse().setBody(capabilities)
                        request.method == "GET" && request.path!!.startsWith("/index.php/apps/notes/api/v1/notes?") ->
                            MockResponse().setBody("[" + notes.values.joinToString(",") + "]")
                        request.method == "PUT" -> {
                            val favorite = Json.parseToJsonElement(body).jsonObject.getValue("favorite").toString().toBoolean()
                            notes[3] = notes.getValue(3).replace("\"favorite\":false", "\"favorite\":$favorite").replace("\"etag\":\"e1\"", "\"etag\":\"e2\"")
                            MockResponse().setBody(notes.getValue(3))
                        }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
            val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
            engine.attachSource(NextcloudNotesSource("Home cloud", api, SourceId("nextcloud")))
            engine.refreshSources()
            val id = engine.library.value.active.single().id
            assertTrue(engine.library.value.active.single().readOnly)
            assertFailsWith<ReadOnlyNoteException> { engine.updateContent(id, "mine") }

            engine.toggleFavorite(id)
            engine.synchronize(id)

            val note = assertNotNull(engine.note(id))
            assertTrue(note.favorite)
            assertEquals(NoteSyncState.SYNCED, note.syncState)
            assertNull(note.attention)
            assertEquals("Team", note.category)
            val writes = requests.filter { it.startsWith("PUT") || it.startsWith("POST") || it.startsWith("DELETE") }
            assertEquals(listOf("PUT /index.php/apps/notes/api/v1/notes/3 {\"favorite\":true}"), writes)
            // The read error case keeps the last good body through the real adapter, too.
            notes[3] = notes.getValue(3).replace("\"content\":\"Their text\"", "\"content\":\"Error\"").replace("\"readonly\":true", "\"readonly\":true,\"error\":true")
                .replace("\"etag\":\"e2\"", "\"etag\":\"e3\"")
            engine.refreshSources()
            assertEquals("Their text", assertNotNull(engine.note(id)).markdown)
            assertEquals(NoteAttention.REMOTE_CONTENT_UNAVAILABLE, assertNotNull(engine.note(id)).attention)
        }
    }
}
