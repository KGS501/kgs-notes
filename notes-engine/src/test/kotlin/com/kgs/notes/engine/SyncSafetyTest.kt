package com.kgs.notes.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir

/** Regression tests for lost responses, identity, metadata conflicts and permissions (#16 #17 #18 #19 #20 #21 #22 #24 #29 #30 #31). */
class SyncSafetyTest {
    @TempDir
    lateinit var root: Path

    /** Each loop iteration of a parameterized test uses its own store. */
    private var store: String = "store"

    private fun open() = ConnectedNotesEngine.open(root.resolve(store).resolve("local"), root.resolve(store).resolve("working"))

    private suspend fun connected(source: FakeSource): ConnectedNotesEngine = open().also {
        it.attachSource(source)
        it.refreshSources()
    }

    // ------------------------------------------------------------------ #17 metadata

    @Test
    fun `remote metadata changes survive a local body edit through refresh and through stale revision recovery`() = runBlocking {
        for (refreshFirst in listOf(true, false)) {
            store = "store-$refreshFirst"
            val source = FakeSource(SourceId("cloud-$refreshFirst"), listOf(remote("1", "Original", "body")))
            val engine = connected(source)
            val id = engine.library.value.active.single { it.sourceId == source.descriptor.id }.id
            engine.updateContent(id, "body with my edit")
            source.editRemotely("1") { it.copy(title = "Remote renamed", category = "Remote folder", favorite = true) }

            if (refreshFirst) engine.refreshSources()
            engine.synchronize(id)

            val server = source.server("1")
            assertEquals("body with my edit", server.markdown)
            assertEquals("Remote renamed", server.title)
            assertEquals("Remote folder", server.category)
            assertTrue(server.favorite)
            val local = assertNotNull(engine.note(id))
            assertEquals(NoteSyncState.SYNCED, local.syncState)
            assertEquals("Remote renamed", local.title)
            assertNull(local.attention)
        }
    }

    @Test
    fun `the same metadata field changed on both sides is a conflict that overwrites nothing`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Original", "body")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.rename(id, "My title")
        source.editRemotely("1") { it.copy(title = "Their title") }

        engine.synchronize(id)

        assertEquals("Their title", source.server("1").title)
        val local = assertNotNull(engine.note(id))
        assertEquals("My title", local.title)
        assertEquals(NoteSyncState.NEEDS_ATTENTION, local.syncState)
        assertEquals(NoteAttention.CONFLICT, local.attention)
        assertEquals(0, source.updates)
        val reopened = open().also { it.attachSource(source) }
        assertEquals(NoteAttention.CONFLICT, assertNotNull(reopened.note(id)).attention)
        reopened.synchronize(id)
        assertEquals("Their title", source.server("1").title)
    }

    @Test
    fun `saving a conflicted Note repeatedly does not fetch the whole Source each time`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Original", "base")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "mine")
        source.editRemotely("1") { it.copy(markdown = "theirs") }
        engine.synchronize(id)
        assertEquals(NoteAttention.CONFLICT, assertNotNull(engine.note(id)).attention)
        val refreshes = source.refreshes

        repeat(5) { engine.updateContent(id, "mine $it"); engine.synchronize(id) }

        assertEquals(refreshes, source.refreshes)
        assertEquals(0, source.updates)
        assertEquals("mine 4", assertNotNull(engine.note(id)).markdown)
        // An explicit refresh still re-evaluates it: once remote matches local, the Conflict clears.
        source.editRemotely("1") { it.copy(markdown = "mine 4") }
        engine.refreshSources()
        assertNull(assertNotNull(engine.note(id)).attention)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
    }

    @Test
    fun `a remote deletion racing a local Trash of a changed Note is reported instead of deleting their edit`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Plan", "base")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.moveToTrash(id)
        source.editRemotely("1") { it.copy(markdown = "their newer body") }

        engine.synchronize(id)

        assertEquals("their newer body", source.server("1").markdown)
        assertEquals(NoteAttention.CONFLICT, assertNotNull(engine.note(id)).attention)
    }

    // ------------------------------------------------------------------ #18 identity

    @Test
    fun `an uncertain create never adopts an unrelated Note with the same body`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Different title", "body")))
        val engine = connected(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "body")
        engine.close(id)
        source.failBeforeNextWrite = true
        engine.synchronize(id)
        engine.updateContent(id, "now different")

        engine.synchronize(id)

        assertEquals("body", source.server("1").markdown)
        assertEquals("Different title", source.server("1").title)
        assertEquals(2, source.serverNotes.size)
        assertEquals("now different", source.serverNotes.single { it.remoteId != "1" }.markdown)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
    }

    @Test
    fun `an ambiguous create is kept for manual recovery across restart and resolves when one candidate remains`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Shared idea")
        engine.close(id)
        source.loseNextResponse = true
        engine.synchronize(id) // committed as remote 1, response lost
        // Another client creates an indistinguishable Note before this device reconciles.
        source.addRemotely(source.serverNotes.single().copy(remoteId = "77", revision = "x"))

        engine.synchronize(id)

        val pending = assertNotNull(engine.note(id))
        assertEquals(NoteAttention.UNCERTAIN_CREATE, pending.attention)
        assertEquals(NoteSyncState.NEEDS_ATTENTION, pending.syncState)
        assertEquals(1, source.creates)
        assertEquals(0, source.updates)
        // Candidates are listed as their own Notes; nothing was merged or deleted.
        assertEquals(3, engine.library.value.active.size)

        val reopened = open().also { it.attachSource(source) }
        reopened.synchronize(id)
        assertEquals(NoteAttention.UNCERTAIN_CREATE, assertNotNull(reopened.note(id)).attention)
        assertEquals(1, source.creates)

        // The user removes the other client's duplicate on the server; the remaining candidate is proven.
        source.deleteRemotely("77")
        reopened.refreshSources()
        reopened.synchronize(id)
        val resolved = assertNotNull(reopened.note(id))
        assertNull(resolved.attention)
        assertEquals(NoteSyncState.SYNCED, resolved.syncState)
        reopened.refreshSources()
        assertEquals(listOf(id), reopened.library.value.active.map(NoteSummary::id))
        assertEquals(1, source.serverNotes.size)
    }

    @Test
    fun `a create renamed by the server for uniqueness is still recognized after a lost response`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Idea", "older and unrelated")))
        val engine = connected(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Idea")
        engine.close(id)
        source.renameCreatesTo = { "$it (2)" }
        source.loseNextResponse = true
        engine.synchronize(id)

        val reopened = open().also { it.attachSource(source) }
        reopened.synchronize(id)

        assertEquals(1, source.creates)
        assertEquals("Idea (2)", assertNotNull(reopened.note(id)).title)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(reopened.note(id)).syncState)
        assertEquals("older and unrelated", source.server("1").markdown)
    }

    @Test
    fun `a same body Note that appears with another title blocks adoption instead of being overwritten`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "body")
        engine.close(id)
        source.failBeforeNextWrite = true
        engine.synchronize(id)
        // Not known before the attempt: it might be this create renamed by the server.
        source.addRemotely(remote("9", "Different title", "body"))
        engine.updateContent(id, "now different")

        engine.synchronize(id)

        assertEquals("body", source.server("9").markdown)
        assertEquals(NoteAttention.UNCERTAIN_CREATE, assertNotNull(engine.note(id)).attention)
        assertEquals(0, source.updates)
        assertEquals("now different", assertNotNull(engine.note(id)).markdown)
        // The rescue path never touches the remote Note either.
        val copy = engine.copyToLocal(id)
        assertEquals("now different", assertNotNull(engine.note(copy)).markdown)
        assertEquals(LocalSourceId, assertNotNull(engine.note(copy)).sourceId)
    }

    // ------------------------------------------------------------------ #19 deletion of uncertain creates

    @Test
    fun `permanent deletion reconciles a lost create and deletes the remote Note it produced`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Short-lived")
        engine.close(id)
        source.loseNextResponse = true
        engine.synchronize(id)
        engine.moveToTrash(id)

        engine.deletePermanently(id)

        assertNull(engine.note(id))
        assertTrue(source.serverNotes.isEmpty())
        engine.refreshSources()
        assertTrue(engine.library.value.active.isEmpty() && engine.library.value.trash.isEmpty())
    }

    @Test
    fun `permanent deletion of an unconfirmed create keeps a durable tombstone while the Source is unavailable`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Short-lived")
        engine.close(id)
        source.loseNextResponse = true
        engine.synchronize(id)
        engine.moveToTrash(id)
        source.offline = true

        assertFailsWith<IllegalStateException> { engine.deletePermanently(id) }
        assertEquals(NoteState.TRASHED, assertNotNull(engine.note(id)).state)

        // Restart between Trash and deletion; the Source comes back.
        source.offline = false
        val reopened = open().also { it.attachSource(source) }
        assertEquals(NoteState.TRASHED, assertNotNull(reopened.note(id)).state)
        reopened.deletePermanently(id)
        assertNull(reopened.note(id))
        assertTrue(source.serverNotes.isEmpty())
    }

    @Test
    fun `permanent deletion of a create that never landed finishes without a remote request`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Never sent")
        engine.close(id)
        source.failBeforeNextWrite = true
        engine.synchronize(id)
        engine.moveToTrash(id)

        engine.deletePermanently(id)

        assertNull(engine.note(id))
        assertEquals(0, source.deletes)
        assertTrue(source.serverNotes.isEmpty())
    }

    // ------------------------------------------------------------------ #20 rename after lost create

    @Test
    fun `a rename after a lost create response is kept and uploaded, also after restart`() = runBlocking {
        for (restart in listOf(false, true)) {
            store = "store-$restart"
            val source = FakeSource(SourceId("cloud-$restart"))
            var engine = connected(source)
            val id = engine.createDraft(sourceId = source.descriptor.id)
            engine.updateContent(id, "body")
            engine.close(id)
            source.loseNextResponse = true
            engine.synchronize(id)
            engine.rename(id, "My new title")
            if (restart) engine = open().also { it.attachSource(source) }

            engine.synchronize(id)

            assertEquals("My new title", assertNotNull(engine.note(id)).title)
            assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
            assertEquals("My new title", source.serverNotes.single().title)
            assertEquals(1, source.creates)
        }
    }

    // ------------------------------------------------------------------ #21 retained uncertain writes

    @Test
    fun `a committed update with a lost response followed by another edit reaches the server without a conflict`() = runBlocking {
        for (restart in listOf(false, true)) {
            store = "store-$restart"
            val source = FakeSource(SourceId("cloud-$restart"), listOf(remote("1", "Notes", "A")))
            var engine = connected(source)
            val id = engine.library.value.active.single().id
            engine.updateContent(id, "B")
            source.loseNextResponse = true
            engine.synchronize(id)
            assertEquals("B", source.server("1").markdown)
            engine.updateContent(id, "C")
            if (restart) engine = open().also { it.attachSource(source) }

            engine.synchronize(id)

            assertEquals("C", source.server("1").markdown)
            assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
            assertEquals(1, source.serverNotes.size)
        }
    }

    @Test
    fun `a genuine concurrent edit after a lost update is still a conflict`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Notes", "A")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "B")
        source.loseNextResponse = true
        engine.synchronize(id)
        source.editRemotely("1") { it.copy(markdown = "X from another client") }
        engine.updateContent(id, "C")

        engine.synchronize(id)

        assertEquals("X from another client", source.server("1").markdown)
        assertEquals(NoteAttention.CONFLICT, assertNotNull(engine.note(id)).attention)
        assertEquals("C", assertNotNull(engine.note(id)).markdown)
    }

    // ------------------------------------------------------------------ #22 exact categories

    @Test
    fun `external Categories are kept verbatim across restart and unrelated edits`() = runBlocking {
        val categories = listOf("Work: projects/Sub", "  spaced  ", "a/b/c", "Q&A?*")
        val source = FakeSource(SourceId("cloud"), categories.mapIndexed { index, category ->
            remote("${index + 1}", "Note $index", "Body $index").copy(category = category)
        })
        connected(source)
        val reopened = open().also { it.attachSource(source); it.refreshSources() }
        reopened.library.value.active.forEach { summary ->
            reopened.toggleFavorite(summary.id)
            reopened.synchronize(summary.id)
        }

        assertEquals(categories.toSet(), source.serverNotes.map(SourceNote::category).toSet())
        assertTrue(source.serverNotes.all(SourceNote::favorite))
        assertEquals(categories.toSet(), open().library.value.active.map(NoteSummary::category).toSet())
    }

    // ------------------------------------------------------------------ #24 generations

    @Test
    fun `a failed record write leaves the previous complete generation and never a SYNCED new body`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Notes", "old")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        val notes = root.resolve(store).resolve("working/notes")
        withoutWriteAccess(notes) {
            assertFailsWith<IOException> { engine.updateContent(id, "my offline edit") }
        }
        assertEquals("old", assertNotNull(engine.note(id)).markdown)

        val reopened = open().also { it.attachSource(source) }
        val note = assertNotNull(reopened.note(id))
        assertEquals("old", note.markdown)
        assertEquals(NoteSyncState.SYNCED, note.syncState)
        // Nothing half-written survives next to the record.
        assertEquals(listOf("${id.value}.properties"), Files.list(notes).use { s -> s.iterator().asSequence().map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a schema 1 record whose body was replaced without its flags is recovered as unsynchronized`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Notes", "old")))
        val first = connected(source)
        val id = first.library.value.active.single().id
        // Recreate the split state an interrupted schema 1 write left behind: new body, old base, SYNCED flag.
        val working = root.resolve(store).resolve("working")
        working.resolve("notes/${id.value}.properties").writeText(
            """
            schema=1
            id=${id.value}
            sourceId=cloud
            remoteId=1
            revision=r0
            title=Notes
            category=
            favorite=false
            state=ACTIVE
            createdAt=2026-08-24T08:00:00Z
            updatedAt=2026-08-24T08:00:00Z
            trashedAt=
            titleFixed=true
            readOnly=false
            syncState=SYNCED
            pendingRemoteDeletion=false
            """.trimIndent(),
        )
        working.resolve("content/${id.value}.md").writeText("my offline edit")
        working.resolve("base/${id.value}.md").writeText("old")

        val reopened = open().also { it.attachSource(source) }
        assertEquals(NoteSyncState.SAVED_LOCALLY, assertNotNull(reopened.note(id)).syncState)
        // A newer server revision must not silently replace the unsynchronized body.
        source.editRemotely("1") { it.copy(markdown = "server newer") }
        reopened.refreshSources()
        assertEquals("my offline edit", assertNotNull(reopened.note(id)).markdown)
        assertEquals(NoteAttention.CONFLICT, assertNotNull(reopened.note(id)).attention)
        // The legacy files were migrated into one schema 2 record.
        assertFalse(working.resolve("content/${id.value}.md").exists())
        assertTrue(working.resolve("notes/${id.value}.properties").readText().contains("schema=2"))
    }

    @Test
    fun `a schema 1 record whose body equals its base migrates as synchronized`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Notes", "same")))
        val id = connected(source).library.value.active.single().id
        val working = root.resolve(store).resolve("working")
        working.resolve("notes/${id.value}.properties").writeText(
            "schema=1\nid=${id.value}\nsourceId=cloud\nremoteId=1\nrevision=r0\ntitle=Notes\ncategory=\nfavorite=false\n" +
                "state=ACTIVE\ncreatedAt=2026-08-24T08:00:00Z\nupdatedAt=2026-08-24T08:00:00Z\nsyncState=SYNCED\n",
        )
        working.resolve("content/${id.value}.md").writeText("same")
        working.resolve("base/${id.value}.md").writeText("same")

        val reopened = open().also { it.attachSource(source) }
        assertEquals(NoteSyncState.SYNCED, assertNotNull(reopened.note(id)).syncState)
        source.editRemotely("1") { it.copy(markdown = "server newer", title = "Renamed") }
        reopened.refreshSources()
        assertEquals("server newer", assertNotNull(reopened.note(id)).markdown)
        assertEquals("Renamed", assertNotNull(reopened.note(id)).title)
    }

    // ------------------------------------------------------------------ #29 content read errors

    @Test
    fun `a content read error keeps the last good body, pauses writes and recovers at the same revision`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Good", "last good body"), remote("2", "Other", "unaffected")))
        val engine = connected(source)
        val id = engine.library.value.active.single { it.title == "Good" }.id
        val other = engine.library.value.active.single { it.title == "Other" }.id
        source.editRemotely("1") {
            it.copy(revision = "r2", markdown = "Error: file locked", readOnly = true, contentUnavailable = true)
        }
        source.editRemotely("2") { it.copy(markdown = "unaffected, newer") }

        engine.refreshSources()

        val cached = assertNotNull(engine.note(id))
        assertEquals("last good body", cached.markdown)
        assertFalse(cached.readOnly)
        assertEquals(NoteAttention.REMOTE_CONTENT_UNAVAILABLE, cached.attention)
        assertEquals("unaffected, newer", assertNotNull(engine.note(other)).markdown)
        engine.toggleFavorite(id)
        engine.synchronize(id)
        assertEquals(0, source.updates + source.favoriteUpdates)

        val reopened = open().also { it.attachSource(source) }
        assertEquals("last good body", assertNotNull(reopened.note(id)).markdown)
        // Healthy again at the very same revision.
        source.editRemotely("1") { it.copy(revision = "r2", markdown = "real newer body", readOnly = false, contentUnavailable = false) }
        reopened.refreshSources()
        reopened.synchronize(id)
        val recovered = assertNotNull(reopened.note(id))
        assertEquals("real newer body", recovered.markdown)
        assertNull(recovered.attention)
        assertTrue(source.server("1").favorite)
        assertEquals(NoteSyncState.SYNCED, recovered.syncState)
    }

    @Test
    fun `a Note first seen with a read error is listed without adopting the error text`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(
            remote("1", "Locked", "Error: cannot read").copy(contentUnavailable = true, readOnly = true),
        ))
        val engine = connected(source)
        val summary = engine.library.value.active.single()
        assertEquals(NoteAttention.REMOTE_CONTENT_UNAVAILABLE, summary.attention)
        assertEquals("", assertNotNull(engine.note(summary.id)).markdown)
        source.editRemotely("1") { it.copy(markdown = "readable now", contentUnavailable = false, readOnly = false) }
        engine.refreshSources()
        assertEquals("readable now", assertNotNull(engine.note(summary.id)).markdown)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(summary.id)).syncState)
    }

    // ------------------------------------------------------------------ #30 lost deletion responses

    @Test
    fun `an accepted deletion whose response was lost is confirmed by the next snapshot and by retries`() = runBlocking {
        for (path in listOf("refresh", "retry", "permanent")) {
            store = "store-$path"
            val source = FakeSource(SourceId("cloud-$path"), listOf(remote("1", "Temporary", "Still recoverable")))
            val engine = connected(source)
            val id = engine.library.value.active.single().id
            engine.moveToTrash(id)
            source.loseNextResponse = true
            engine.synchronize(id)
            assertTrue(source.serverNotes.isEmpty())

            val reopened = open().also { it.attachSource(source) }
            when (path) {
                "refresh" -> reopened.refreshSources()
                "retry" -> reopened.synchronize(id)
                else -> reopened.deletePermanently(id)
            }

            if (path == "permanent") {
                assertNull(reopened.note(id))
            } else {
                val note = assertNotNull(reopened.note(id))
                assertEquals(NoteSyncState.SYNCED, note.syncState)
                assertEquals(NoteState.TRASHED, note.state)
                assertEquals("Still recoverable", note.markdown)
                reopened.deletePermanently(id)
                assertNull(reopened.note(id))
            }
            reopened.removeSource(source.descriptor.id)
        }
    }

    @Test
    fun `a deletion that cannot reach the Source stays pending`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Temporary", "Body")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.moveToTrash(id)
        source.offline = true
        assertFailsWith<IllegalStateException> { engine.deletePermanently(id) }
        assertNotNull(engine.note(id))
        assertFailsWith<IllegalStateException> { engine.removeSource(source.descriptor.id) }
        assertEquals(1, source.serverNotes.size)
    }

    // ------------------------------------------------------------------ #31 read-only Notes

    @Test
    fun `read-only Notes reject edits at the engine boundary and synchronize only Favorite`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Shared", "Their text").copy(readOnly = true)))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        assertTrue(engine.library.value.active.single().readOnly)
        assertTrue(assertNotNull(engine.note(id)).readOnly)

        assertFailsWith<ReadOnlyNoteException> { engine.updateContent(id, "mine") }
        assertFailsWith<ReadOnlyNoteException> { engine.rename(id, "mine") }
        assertFailsWith<ReadOnlyNoteException> { engine.setCategory(id, "Mine") }
        assertFailsWith<ReadOnlyNoteException> { engine.moveToTrash(id) }
        assertFailsWith<ReadOnlyNoteException> { engine.moveToSource(id, LocalSourceId) }
        assertFailsWith<ReadOnlyNoteException> { engine.deletePermanently(id) }
        assertFailsWith<ReadOnlyNoteException> {
            engine.importManagedAttachment(id, AttachmentImport("a.png", "image/png") { ByteArray(1).inputStream() })
        }
        assertTrue(engine.deleteCategory(source.descriptor.id, "Shared").isEmpty())

        engine.toggleFavorite(id)
        engine.togglePin(id)
        engine.synchronize(id)

        assertEquals(1, source.favoriteUpdates)
        assertEquals(0, source.updates)
        assertTrue(source.server("1").favorite)
        assertEquals("Their text", source.server("1").markdown)
        val synced = assertNotNull(engine.note(id))
        assertEquals(NoteSyncState.SYNCED, synced.syncState)
        assertNull(synced.attention)
        val reopened = open().also { it.attachSource(source); it.refreshSources() }
        assertTrue(assertNotNull(reopened.note(id)).favorite)
        assertTrue(assertNotNull(reopened.note(id)).readOnly)
        // Rescuing an editor draft never touches the shared Note.
        val copy = reopened.copyToLocal(id, "draft typed before the rejection")
        assertEquals("draft typed before the rejection", assertNotNull(reopened.note(copy)).markdown)
        assertFalse(assertNotNull(reopened.note(copy)).readOnly)
        assertEquals("Their text", source.server("1").markdown)
    }

    @Test
    fun `edits made before permissions changed stay preserved and only Favorite synchronizes`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Shared", "Their text")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "my pending edit")
        engine.toggleFavorite(id)
        source.editRemotely("1") { it.copy(readOnly = true) } // revision bump without content changes

        engine.refreshSources()
        engine.synchronize(id)

        val kept = assertNotNull(engine.note(id))
        assertEquals("my pending edit", kept.markdown)
        assertTrue(kept.readOnly)
        assertEquals(NoteAttention.READ_ONLY_CHANGES, kept.attention)
        assertEquals(NoteSyncState.NEEDS_ATTENTION, kept.syncState)
        assertEquals("Their text", source.server("1").markdown)
        assertTrue(source.server("1").favorite)
        assertEquals(0, source.updates)
        val reopened = open().also { it.attachSource(source) }
        assertEquals("my pending edit", assertNotNull(reopened.note(id)).markdown)
        assertEquals(NoteAttention.READ_ONLY_CHANGES, assertNotNull(reopened.note(id)).attention)
        assertFailsWith<IllegalStateException> { reopened.removeSource(source.descriptor.id) }
    }

    // ------------------------------------------------------------------ #16 Trash intent

    @Test
    fun `a late content save keeps an external Note in Trash and only restore reverses it`() = runBlocking {
        for (deleteFirst in listOf(true, false)) {
            store = "store-$deleteFirst"
            val source = FakeSource(SourceId("cloud-$deleteFirst"), listOf(remote("1", "Doomed", "body")))
            val engine = connected(source)
            val id = engine.library.value.active.single().id
            engine.moveToTrash(id)
            if (deleteFirst) engine.synchronize(id)
            engine.updateContent(id, "late debounced save")
            assertEquals(NoteState.TRASHED, assertNotNull(engine.note(id)).state)

            engine.synchronize(id)
            val reopened = open().also { it.attachSource(source) }
            reopened.synchronize(id)
            reopened.refreshSources()

            assertTrue(source.serverNotes.isEmpty())
            assertEquals(1, source.deletes)
            assertEquals(0, source.creates)
            assertEquals(NoteState.TRASHED, assertNotNull(reopened.note(id)).state)
            assertEquals("late debounced save", assertNotNull(reopened.note(id)).markdown)
            reopened.restore(id)
            reopened.synchronize(id)
            assertEquals("late debounced save", source.serverNotes.single().markdown)
        }
    }

    // ------------------------------------------------------------------ helpers

    // ------------------------------------------------------------------ review follow-ups

    private fun candidateId(source: FakeSource, remoteId: String) = NoteId(
        java.util.UUID.nameUUIDFromBytes("${source.descriptor.id.value}\n$remoteId".toByteArray(Charsets.UTF_8)).toString(),
    )

    /** A lost create plus an identical Note from another client: two candidates nobody can tell apart. */
    private suspend fun ambiguousCreate(): Triple<FakeSource, ConnectedNotesEngine, NoteId> {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "body")
        engine.close(id)
        source.loseNextResponse = true
        engine.synchronize(id)
        val created = source.serverNotes.single()
        source.addRemotely(created.copy(remoteId = "other", revision = "r-other"))
        engine.refreshSources()
        assertEquals(NoteAttention.UNCERTAIN_CREATE, assertNotNull(engine.note(id)).attention)
        return Triple(source, engine, id)
    }

    @Test
    fun `opening one ambiguous candidate never binds the uncertain create to the other one`() = runBlocking {
        val (source, engine, id) = ambiguousCreate()
        val created = source.serverNotes.first { it.remoteId != "other" }.remoteId
        for (opened in listOf(created, "other")) {
            engine.openEditingSession(candidateId(source, opened))
            engine.refreshSources()
            engine.synchronize(id)

            assertEquals(NoteAttention.UNCERTAIN_CREATE, assertNotNull(engine.note(id)).attention, "opened $opened")
            assertEquals(1, source.creates)
            assertEquals(2, source.serverNotes.size)
            assertEquals(0, source.updates)
            engine.close(candidateId(source, opened))
        }
    }

    @Test
    fun `opening every ambiguous candidate never makes the create look absent and retry it`() = runBlocking {
        val (source, engine, id) = ambiguousCreate()
        source.serverNotes.forEach { engine.openEditingSession(candidateId(source, it.remoteId)) }

        engine.refreshSources()
        engine.synchronize(id)

        assertEquals(NoteAttention.UNCERTAIN_CREATE, assertNotNull(engine.note(id)).attention)
        assertEquals(1, source.creates)
        assertEquals(2, source.serverNotes.size)
    }

    @Test
    fun `a permission change reaches an open editor and blocks further edits`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Shared", "Their text")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.openEditingSession(id)

        source.editRemotely("1") { it.copy(readOnly = true) }
        engine.refreshSources()

        assertTrue(assertNotNull(engine.note(id)).readOnly)
        assertFailsWith<ReadOnlyNoteException> { engine.updateContent(id, "my edit") }

        // Granted again while still open: editing works and syncs.
        source.editRemotely("1") { it.copy(readOnly = false) }
        engine.refreshSources()
        assertFalse(assertNotNull(engine.note(id)).readOnly)
        engine.updateContent(id, "my edit")
        engine.synchronize(id)
        assertEquals("my edit", source.server("1").markdown)
    }

    @Test
    fun `revoking permission while an open editor holds unsynced edits keeps them and asks for attention`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(remote("1", "Shared", "Their text")))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.openEditingSession(id)
        source.offline = true
        engine.updateContent(id, "my unsynced edit")
        runCatching { engine.synchronize(id) }
        source.offline = false

        source.editRemotely("1") { it.copy(readOnly = true, markdown = "Their newer text") }
        engine.refreshSources()

        val note = assertNotNull(engine.note(id))
        assertTrue(note.readOnly)
        assertEquals("my unsynced edit", note.markdown)
        assertTrue(note.attention == NoteAttention.READ_ONLY_CHANGES || note.attention == NoteAttention.CONFLICT, "attention=${note.attention}")
        assertEquals("Their newer text", source.server("1").markdown)
    }

    private fun remote(remoteId: String, title: String, markdown: String) = SourceNote(
        remoteId = remoteId,
        revision = "r0",
        title = title,
        markdown = markdown,
        category = "",
        favorite = false,
        modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
        readOnly = false,
    )
}

internal inline fun <T> withoutWriteAccess(directory: Path, block: () -> T): T {
    val original = Files.getPosixFilePermissions(directory)
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"))
    return try {
        block()
    } finally {
        Files.setPosixFilePermissions(directory, original)
    }
}

/**
 * A controllable Source: [serverNotes] is the server truth, [notes] the last snapshot this device saw.
 * Faults: [offline], [failBeforeNextWrite] (never reaches the server), [loseNextResponse] (applied, response lost),
 * [writeGate]/[refreshGate] to suspend inside a request.
 */
internal class FakeSource(
    sourceId: SourceId,
    initial: List<SourceNote> = emptyList(),
) : Source {
    override val descriptor = SourceDescriptor(sourceId, "Fake cloud", SourceCapabilities(true, true, true, false, true))
    private val server = MutableStateFlow(initial)
    private val visible = MutableStateFlow<List<SourceNote>>(emptyList())
    override val notes: StateFlow<List<SourceNote>> = visible
    val serverNotes: List<SourceNote> get() = server.value
    var offline = false
    var failBeforeNextWrite = false
    var loseNextResponse = false
    var renameCreatesTo: ((String) -> String)? = null
    var writeGate: (suspend () -> Unit)? = null
    var refreshGate: (suspend () -> Unit)? = null
    var creates = 0
    var updates = 0
    var deletes = 0
    var favoriteUpdates = 0
    var refreshes = 0
    private var nextId = 1000
    private var revisions = 0

    fun server(remoteId: String) = server.value.single { it.remoteId == remoteId }
    fun addRemotely(note: SourceNote) { server.value = server.value + note }
    fun deleteRemotely(remoteId: String) { server.value = server.value.filterNot { it.remoteId == remoteId } }
    fun editRemotely(remoteId: String, change: (SourceNote) -> SourceNote) {
        server.value = server.value.map { if (it.remoteId == remoteId) change(it).let { c -> if (c.revision == it.revision) c.copy(revision = nextRevision()) else c } else it }
    }

    private fun nextRevision() = "r${++revisions}"

    private suspend fun beforeWrite() {
        writeGate?.invoke()
        if (offline) throw IOException("Offline")
        if (failBeforeNextWrite) {
            failBeforeNextWrite = false
            throw IOException("Connection refused")
        }
    }

    private fun <T> respond(value: T): T {
        if (loseNextResponse) {
            loseNextResponse = false
            throw IOException("Connection reset after commit")
        }
        return value
    }

    private fun publish(note: SourceNote) {
        visible.value = visible.value.filterNot { it.remoteId == note.remoteId } + note
    }

    override suspend fun refresh() {
        refreshGate?.invoke()
        refreshes++
        if (offline) throw IOException("Offline")
        visible.value = server.value
    }

    override suspend fun createNote(note: SourceNoteDraft): SourceNote {
        beforeWrite()
        creates++
        val created = SourceNote(
            remoteId = (nextId++).toString(),
            revision = nextRevision(),
            title = renameCreatesTo?.invoke(note.title) ?: note.title,
            markdown = note.markdown,
            category = note.category,
            favorite = note.favorite,
            modifiedAt = note.modifiedAt,
            readOnly = false,
        )
        server.value = server.value + created
        publish(created)
        return respond(created)
    }

    override suspend fun updateNote(remoteId: String, expectedRevision: String, note: SourceNoteDraft): SourceNote {
        beforeWrite()
        val current = server.value.singleOrNull { it.remoteId == remoteId } ?: throw IOException("HTTP 404")
        if (current.revision != expectedRevision) throw SourceConflictException()
        check(!current.readOnly) { "HTTP 403" }
        updates++
        val updated = current.copy(
            revision = nextRevision(),
            title = note.title,
            markdown = note.markdown,
            category = note.category,
            favorite = note.favorite,
            modifiedAt = note.modifiedAt,
        )
        server.value = server.value.map { if (it.remoteId == remoteId) updated else it }
        publish(updated)
        return respond(updated)
    }

    override suspend fun deleteNote(remoteId: String, expectedRevision: String) {
        beforeWrite()
        val current = server.value.singleOrNull { it.remoteId == remoteId }
        if (current != null) {
            if (current.revision != expectedRevision) throw SourceConflictException()
            deletes++
            server.value = server.value.filterNot { it.remoteId == remoteId }
        }
        visible.value = visible.value.filterNot { it.remoteId == remoteId }
        respond(Unit)
    }

    override suspend fun updateFavorite(remoteId: String, favorite: Boolean): SourceNote {
        beforeWrite()
        val current = server.value.singleOrNull { it.remoteId == remoteId } ?: throw IOException("HTTP 404")
        favoriteUpdates++
        val updated = current.copy(favorite = favorite, revision = nextRevision())
        server.value = server.value.map { if (it.remoteId == remoteId) updated else it }
        publish(updated)
        return respond(updated)
    }
}
