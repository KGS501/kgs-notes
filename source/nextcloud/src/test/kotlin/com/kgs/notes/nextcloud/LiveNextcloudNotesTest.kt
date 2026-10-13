package com.kgs.notes.nextcloud

import com.kgs.notes.engine.ConnectedNotesEngine
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.NotesEngine
import com.kgs.notes.engine.SourceId
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Real Source/NotesEngine checks, restricted to the shared runner's loopback test accounts. */
class LiveNextcloudNotesTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `new Categories on an open Note persist through repeated updates and refresh`() = runBlocking {
        withServer { server ->
            val source = server.source()
            val engine = server.engine(source)
            val id = engine.createDraft(sourceId = source.descriptor.id)
            engine.updateContent(id, "Category fixture")
            engine.synchronize(id)
            engine.openEditingSession(id)
            for (category in listOf("Created/First", "Created/Second", "")) {
                engine.setCategory(id, category)
                engine.synchronize(id)
                assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
                assertEquals(category, server.api.notes().single().category)
                engine.refreshSources()
                assertEquals(category, engine.note(id)?.category)
            }
            engine.close(id)
            val reopened = server.engine(server.source())
            reopened.refreshSources()
            assertEquals("", reopened.library.value.active.single().category)
        }
    }

    @Test
    fun `a local Note moves to Nextcloud and back without changing identity or losing pending edits`() = runBlocking {
        withServer { server ->
            val source = server.source()
            val engine = server.engine(source)
            val id = engine.createDraft(category = "Transfer/Subcategory")
            engine.updateContent(id, "# Transfer fixture\n\nOriginal content")
            engine.rename(id, "Transfer Note")
            engine.toggleFavorite(id)
            val original = assertNotNull(engine.openEditingSession(id))

            assertEquals(id, engine.moveToSource(id, source.descriptor.id))
            val published = server.api.notes().single()
            assertEquals(original.markdown, published.markdown)
            assertEquals(original.title, published.title)
            assertEquals(original.category, published.category)
            assertEquals(original.favorite, published.favorite)
            assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)

            engine.updateContent(id, "# Transfer fixture\n\nPending device edit")
            assertEquals(id, engine.moveToSource(id, LocalSourceId))
            assertTrue(server.api.notes().isEmpty())
            engine.refreshSources()
            val moved = assertNotNull(engine.note(id))
            assertEquals(LocalSourceId, moved.sourceId)
            assertEquals(NoteSyncState.LOCAL_SOURCE, moved.syncState)
            assertEquals("# Transfer fixture\n\nPending device edit", moved.markdown)
            assertEquals(original.createdAt, moved.createdAt)
            assertEquals(1, engine.library.value.active.size)
            val reopened: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
            assertEquals(moved, reopened.note(id))
        }
    }

    @Test
    fun `an open editor cannot overwrite an unseen server edit after refresh`() = runBlocking {
        withServer { server ->
            val created = server.api.create(draft("Open editor fixture", "Displayed base"))
            val engine = server.engine(server.source())
            engine.refreshSources()
            val id = engine.library.value.active.single().id
            engine.openEditingSession(id)
            server.api.update(created.id, created.etag, draft("Open editor fixture", "Unseen remote edit"))
            engine.refreshSources()
            assertEquals("Displayed base", assertNotNull(engine.note(id)).markdown)
            engine.updateContent(id, "Typed during refresh")
            engine.synchronize(id)
            assertEquals(NoteSyncState.NEEDS_ATTENTION, assertNotNull(engine.note(id)).syncState)
            assertEquals("Typed during refresh", assertNotNull(engine.note(id)).markdown)
            assertEquals("Unseen remote edit", server.api.note(created.id).markdown)
        }
    }

    @Test
    fun `the real login flow keeps its authorization endpoints on HTTPS`() = runBlocking {
        withServer { server ->
            val login = HttpNextcloudLoginFlow(server.http)
            val challenge = login.begin(server.credentials.serverUrl)
            assertEquals("https", URI(challenge.loginUrl).scheme)
            assertEquals("https", URI(challenge.pollUrl).scheme)
            assertEquals(null, login.poll(challenge))
        }
    }

    @Test
    fun `edits download and upload and Trash Restore republish without duplicates`() = runBlocking {
        withServer { server ->
            val source = server.source()
            val engine = server.engine(source)
            val id = engine.createDraft(sourceId = source.descriptor.id)
            engine.updateContent(id, "# Synthetic Note\n\nInitial content")
            engine.rename(id, "Integration Note")
            engine.setCategory(id, "Integration/Subcategory")
            engine.toggleFavorite(id)
            engine.synchronize(id)
            val created = server.api.notes().single()
            assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
            assertEquals("Integration Note", created.title)
            assertEquals("Integration/Subcategory", created.category)
            assertTrue(created.favorite)

            engine.updateContent(id, "# Synthetic Note\n\nEdited on device")
            engine.synchronize(id)
            assertEquals("# Synthetic Note\n\nEdited on device", server.api.note(created.id).markdown)

            val latest = server.api.note(created.id)
            server.api.update(latest.id, latest.etag, draft("Edited remotely", "Downloaded content"))
            engine.refreshSources()
            val downloaded = assertNotNull(engine.note(id))
            assertEquals("Downloaded content", downloaded.markdown)
            assertEquals("Edited remotely", downloaded.title)
            assertEquals(NoteSyncState.SYNCED, downloaded.syncState)

            engine.moveToTrash(id)
            engine.synchronize(id)
            assertTrue(server.api.notes().isEmpty())
            assertEquals(NoteState.TRASHED, assertNotNull(engine.note(id)).state)
            assertEquals("Downloaded content", assertNotNull(engine.note(id)).markdown)
            engine.restore(id)
            engine.synchronize(id)
            engine.refreshSources()
            assertEquals(1, engine.library.value.active.size)
            assertEquals(1, server.api.notes().size)
            assertEquals("Downloaded content", server.api.notes().single().markdown)
            assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
            engine.deletePermanently(id)
            assertTrue(server.api.notes().isEmpty())
        }
    }

    @Test
    fun `conflicting server edits cannot overwrite local content or be deleted`() = runBlocking {
        withServer { server ->
            val created = server.api.create(draft("Conflict fixture", "Base content"))
            val engine = server.engine(server.source())
            engine.refreshSources()
            val id = engine.library.value.active.single().id
            server.api.update(created.id, created.etag, draft("Conflict fixture", "Remote edit"))
            engine.updateContent(id, "Local edit")
            engine.synchronize(id)
            assertEquals(NoteSyncState.NEEDS_ATTENTION, assertNotNull(engine.note(id)).syncState)
            assertEquals("Local edit", assertNotNull(engine.note(id)).markdown)
            assertEquals("Remote edit", server.api.note(created.id).markdown)

            engine.moveToTrash(id)
            engine.synchronize(id)
            assertEquals(NoteSyncState.NEEDS_ATTENTION, assertNotNull(engine.note(id)).syncState)
            assertEquals("Remote edit", server.api.note(created.id).markdown)
        }
    }

    @Test
    fun `new edits and overlapping sync requests preserve one remote identity`() = runBlocking {
        withServer { server ->
            val received = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val holdFirstCreate = AtomicBoolean(true)
            val http = server.http.newBuilder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().method == "POST" &&
                    chain.request().url.encodedPath.endsWith("/api/v1/notes") &&
                    holdFirstCreate.compareAndSet(true, false)
                ) {
                    received.complete(Unit)
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        response.close()
                        throw IOException("Test upload release timed out")
                    }
                }
                response
            }.build()
            val source = server.source(http)
            val engine = server.engine(source)
            val id = engine.createDraft(sourceId = source.descriptor.id)
            engine.updateContent(id, "First edit")
            val first = async { engine.synchronize(id) }
            try {
                withTimeout(30_000) { received.await() }
                engine.updateContent(id, "Newest edit while upload was running")
                val second = async { engine.synchronize(id) }
                release.countDown()
                first.await()
                second.await()
            } finally {
                release.countDown()
            }
            val remote = server.api.notes().single()
            assertEquals("Newest edit while upload was running", remote.markdown)
            assertEquals(remote.markdown, assertNotNull(engine.note(id)).markdown)
            assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
            engine.refreshSources()
            assertEquals(1, engine.library.value.active.size)
        }
    }

    @Test
    fun `offline changes survive reopening and converge when the server returns`() = runBlocking {
        withServer { server ->
            server.api.create(draft("Offline fixture", "Before outage"))
            val offline = AtomicBoolean(false)
            val http = server.http.newBuilder().addInterceptor { chain ->
                if (offline.get()) throw IOException("Controlled test outage")
                chain.proceed(chain.request())
            }.build()
            val source = server.source(http)
            val engine = server.engine(source)
            engine.refreshSources()
            val id = engine.library.value.active.single().id
            engine.updateContent(id, "Durable offline edit")
            offline.set(true)
            engine.synchronize(id)
            assertEquals(NoteSyncState.SAVED_LOCALLY, assertNotNull(engine.note(id)).syncState)
            val reopened = server.engine(server.source())
            reopened.refreshSources()
            assertEquals("Durable offline edit", assertNotNull(reopened.note(id)).markdown)
            reopened.synchronize(id)
            assertEquals("Durable offline edit", server.api.notes().single().markdown)
            assertEquals(NoteSyncState.SYNCED, assertNotNull(reopened.note(id)).syncState)
        }
    }

    @Test
    fun `chunked listings return every Note and Markdown settings are accepted`() = runBlocking {
        withServer { server ->
            val source = server.source()
            assertEquals(".md", source.setFileSuffix(".md"))
            val category = "kgs-paging-" + UUID.randomUUID()
            try {
                val ids = (1..105).map {
                    server.api.create(draft("Page fixture $it", "Synthetic item $it").copy(category = category)).id
                }.toSet()
                source.refresh()
                assertEquals(ids, source.notes.value.map { it.remoteId.toLong() }.toSet())
                assertEquals(105, source.notes.value.size)
            } finally {
                // Delete only this generated fixture folder through the real DAV
                // seam; single-Note API deletion is covered by the lifecycle test.
                val url = server.credentials.serverUrl.toHttpUrl().newBuilder()
                    .addPathSegments("remote.php/dav/files")
                    .addPathSegment(server.credentials.loginName)
                    .addPathSegment("Notes")
                    .addPathSegment(category)
                    .build()
                server.http.newCall(Request.Builder().url(url)
                    .header("Authorization", Credentials.basic(server.credentials.loginName, server.credentials.appPassword))
                    .delete().build()).execute().use { response ->
                    assertTrue(response.isSuccessful || response.code == 404)
                }
            }
        }
    }

    private suspend fun withServer(block: suspend (Server) -> Unit) {
        val base = System.getenv("KGS_TEST_NEXTCLOUD_HTTPS_URL")
        assumeTrue(base != null, "Needs the shared Nextcloud runner")
        val uri = URI(requireNotNull(base))
        require(uri.scheme == "https" && uri.host in setOf("localhost", "127.0.0.1"))
        val user = requireNotNull(System.getenv("KGS_TEST_NEXTCLOUD_USER"))
        require(user.startsWith("kgs-notes-test-"))
        val password = requireNotNull(System.getenv("KGS_TEST_NEXTCLOUD_PASSWORD"))
        val certificate = Files.newInputStream(Paths.get(requireNotNull(System.getenv("KGS_TEST_NEXTCLOUD_CA_FILE"))))
            .use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val server = Server(NextcloudCredentials(base, user, password), http)
        val original = server.api.notes().map { it.id }.toSet()
        try {
            block(server)
        } finally {
            server.api.notes().filterNot { it.id in original }.forEach { server.api.delete(it.id) }
        }
    }

    private inner class Server(val credentials: NextcloudCredentials, val http: OkHttpClient) {
        val api = HttpNextcloudNotesApi(credentials, http)
        fun source(client: OkHttpClient = http) = NextcloudNotesSource(
            "Integration Source", HttpNextcloudNotesApi(credentials, client), SourceId("integration"),
        )
        suspend fun engine(source: NextcloudNotesSource): NotesEngine =
            ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working")).also { it.attachSource(source) }
    }

    private fun draft(title: String, markdown: String) = RemoteNoteDraft(
        title, markdown, "", false, Instant.now(),
    )
}
