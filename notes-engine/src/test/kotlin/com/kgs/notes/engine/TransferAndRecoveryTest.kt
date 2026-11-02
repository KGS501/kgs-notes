package com.kgs.notes.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir

/** Ownership transfers, Source removal, recovery inventory and attachment paths (#25 #27 #28 #32 #40 #43 #44). */
class TransferAndRecoveryTest {
    @TempDir
    lateinit var root: Path

    private val localRoot get() = root.resolve("local")
    private val workingRoot get() = root.resolve("working")

    private fun open() = ConnectedNotesEngine.open(localRoot, workingRoot)

    private suspend fun connected(source: FakeSource) = open().also {
        it.attachSource(source)
        it.refreshSources()
    }

    private suspend fun localNote(engine: NotesEngine, markdown: String, category: String = ""): NoteId {
        val id = engine.createDraft(category)
        engine.updateContent(id, markdown)
        engine.close(id)
        return id
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    // ------------------------------------------------------------------ #27 durable transfer intent

    @Test
    fun `a move whose create response is lost is identified instead of duplicated`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = localNote(engine, "Travel plans")
        source.loseNextResponse = true

        assertEquals(id, engine.moveToSource(id, source.descriptor.id))

        assertEquals(1, source.serverNotes.size)
        assertEquals(1, source.creates)
        val moved = assertNotNull(engine.note(id))
        assertEquals(source.descriptor.id, moved.sourceId)
        assertNull(moved.attention)
        assertEquals(listOf(id), engine.library.value.active.map(NoteSummary::id))
        val reopened = open().also { it.attachSource(source); it.refreshSources() }
        assertEquals(listOf(id), reopened.library.value.active.map(NoteSummary::id))
        assertEquals(source.descriptor.id, assertNotNull(reopened.note(id)).sourceId)
        assertTrue(Files.list(localRoot.resolve(".kgs/notes")).use { !it.findAny().isPresent })
    }

    @Test
    fun `an unconfirmed move stays pending across restart and finishes without a second create`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = localNote(engine, "Travel plans")
        source.loseNextResponse = true
        var dropped = false
        source.refreshGate = { if (source.creates > 0 && !dropped) { dropped = true; throw IOException("Offline after the request") } }

        assertFailsWith<TransferPendingException> { engine.moveToSource(id, source.descriptor.id) }

        val pending = assertNotNull(engine.note(id))
        assertEquals(source.descriptor.id, pending.sourceId)
        assertEquals(NoteAttention.TRANSFER_PENDING, pending.attention)
        assertEquals("Travel plans", pending.markdown)
        assertEquals(listOf(id), engine.library.value.active.map(NoteSummary::id))
        engine.updateContent(id, "Travel plans, edited while pending")
        assertFailsWith<IllegalStateException> { engine.removeSource(source.descriptor.id) }

        val reopened = open().also { it.attachSource(source) }
        assertEquals(NoteAttention.TRANSFER_PENDING, assertNotNull(reopened.note(id)).attention)
        assertEquals(listOf(id), reopened.library.value.active.map(NoteSummary::id))
        // Retrying the move cannot issue another create.
        assertEquals(id, reopened.moveToSource(id, source.descriptor.id))
        reopened.synchronize(id)

        assertEquals(1, source.creates)
        assertEquals(listOf("Travel plans, edited while pending"), source.serverNotes.map(SourceNote::markdown))
        val done = assertNotNull(reopened.note(id))
        assertNull(done.attention)
        assertEquals(NoteSyncState.SYNCED, done.syncState)
        val again = open()
        assertEquals(listOf(id), again.library.value.active.map(NoteSummary::id))
        assertEquals(source.descriptor.id, assertNotNull(again.note(id)).sourceId)
    }

    @Test
    fun `a move that failed before reaching the Source is rolled back to the untouched Local Note`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(SourceNote("1", "r0", "Other", "Travel plans", "", false, Instant.EPOCH, false)))
        val engine = connected(source)
        val id = localNote(engine, "Travel plans")
        val original = engine.note(id)
        source.failBeforeNextWrite = true

        assertFailsWith<IOException> { engine.moveToSource(id, source.descriptor.id) }

        assertEquals(original, engine.note(id))
        assertEquals(LocalSourceId, assertNotNull(engine.note(id)).sourceId)
        assertEquals(1, source.serverNotes.size)
        assertEquals(original, open().note(id))
        // A later retry publishes it once; the unrelated same-body Note stays untouched.
        engine.moveToSource(id, source.descriptor.id)
        assertEquals(2, source.serverNotes.size)
        assertEquals("Other", source.server("1").title)
    }

    @Test
    fun `a move renamed by the server keeps the server title`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = localNote(engine, "Idea")
        source.renameCreatesTo = { "$it (2)" }
        source.loseNextResponse = true

        engine.moveToSource(id, source.descriptor.id)

        assertEquals("Idea (2)", assertNotNull(engine.note(id)).title)
        assertEquals(1, source.serverNotes.size)
    }

    // ------------------------------------------------------------------ #25 attachments during transfer

    @Test
    fun `attachment imports are refused while a Note moves and Notes with attachments are not moved`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = localNote(engine, "Attachment-free")
        source.writeGate = { entered.complete(Unit); release.await() }
        val transfer = async { engine.moveToSource(id, source.descriptor.id) }
        entered.await()

        assertFailsWith<IllegalArgumentException> {
            engine.importManagedAttachment(id, AttachmentImport("photo.png", "image/png") { png.inputStream() })
        }
        release.complete(Unit)
        transfer.await()
        engine.synchronize(id)
        assertFalse(source.serverNotes.single().markdown.contains(".kgs-notes-attachments"))

        // Import first, then move: the move is refused and the bytes survive, also after restart.
        source.writeGate = null
        val withImage = localNote(engine, "With image")
        val attachment = engine.importManagedAttachment(withImage, AttachmentImport("photo.png", "image/png") { png.inputStream() })
        engine.updateContent(withImage, "With image\n\n![photo](${attachment.markdownTarget})")
        assertFailsWith<IllegalArgumentException> { engine.moveToSource(withImage, source.descriptor.id) }
        val reopened = open()
        assertEquals(LocalSourceId, assertNotNull(reopened.note(withImage)).sourceId)
        assertContentEquals(png, assertNotNull(reopened.openManagedAttachment(attachment.id)).use { it.readBytes() })
        assertEquals(1, source.serverNotes.size)
    }

    // ------------------------------------------------------------------ #28 interrupted cleanup

    @Test
    fun `a failed Local cleanup after a confirmed move cannot resurrect the old copy`() = runBlocking {
        for (reopenBeforeDelete in listOf(true, false)) {
            val store = root.resolve("case-$reopenBeforeDelete")
            val source = FakeSource(SourceId("cloud-$reopenBeforeDelete"))
            var engine: NotesEngine = ConnectedNotesEngine.open(store.resolve("local"), store.resolve("working")).also {
                it.attachSource(source); it.refreshSources()
            }
            val id = localNote(engine, "Moved once")
            withoutWriteAccess(store.resolve("local/.kgs/notes")) {
                assertEquals(id, engine.moveToSource(id, source.descriptor.id))
            }
            // The fault really left the old Local record behind, hidden behind the confirmed Working Copy.
            assertTrue(store.resolve("local/.kgs/notes/${id.value}.properties").exists())
            assertEquals(listOf(id), engine.library.value.active.map(NoteSummary::id))
            if (reopenBeforeDelete) {
                engine = ConnectedNotesEngine.open(store.resolve("local"), store.resolve("working")).also { it.attachSource(source) }
                assertEquals(listOf(id), engine.library.value.active.map(NoteSummary::id))
            }

            engine.moveToTrash(id)
            engine.deletePermanently(id)

            assertTrue(source.serverNotes.isEmpty())
            assertTrue(engine.library.value.active.isEmpty() && engine.library.value.trash.isEmpty())
            val again = ConnectedNotesEngine.open(store.resolve("local"), store.resolve("working"))
            assertTrue(again.library.value.active.isEmpty() && again.library.value.trash.isEmpty())
            assertNull(again.note(id))
        }
    }

    @Test
    fun `an unmarked Local remnant left by an earlier version is settled at open`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = localNote(engine, "Old version remnant")
        val saved = Files.list(localRoot.resolve(".kgs/notes")).use { it.iterator().asSequence().toList() }
            .associateWith { it.readBytes() } + mapOf(localRoot.resolve("Old version remnant.md").let { it to it.readBytes() })
        engine.moveToSource(id, source.descriptor.id)
        saved.forEach { (path, bytes) -> Files.write(path, bytes) } // what an interrupted cleanup used to leave

        val reopened = open().also { it.attachSource(source) }
        assertEquals(listOf(id), reopened.library.value.active.map(NoteSummary::id))
        reopened.moveToTrash(id)
        reopened.deletePermanently(id)
        assertNull(open().note(id))
        assertTrue(open().library.value.active.isEmpty())
    }

    // ------------------------------------------------------------------ #40 conflict copy

    @Test
    fun `edits saved while a conflicting move refreshes the remote copy are kept in the Local copy`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(SourceNote("1", "r0", "Draft", "Base", "", false, Instant.EPOCH, false)))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "Base plus my edit")
        source.editRemotely("1") { it.copy(markdown = "Base plus their edit") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var refreshesDuringMove = 0
        source.refreshGate = {
            if (++refreshesDuringMove == 2) { entered.complete(Unit); release.await() }
        }

        val move = async { engine.moveToSource(id, LocalSourceId) }
        entered.await()
        engine.updateContent(id, "Base plus my edit, and the newest saved line")
        engine.rename(id, "Newest title")
        release.complete(Unit)
        val localId = move.await()

        assertNotEquals(id, localId)
        val reopened = open().also { it.attachSource(source) }
        val local = assertNotNull(reopened.note(localId))
        assertEquals(LocalSourceId, local.sourceId)
        assertEquals("Base plus my edit, and the newest saved line", local.markdown)
        assertEquals("Newest title", local.title)
        val server = assertNotNull(reopened.note(id))
        assertEquals("Base plus their edit", server.markdown)
        assertEquals(NoteSyncState.SYNCED, server.syncState)
        assertEquals(listOf("Base plus their edit"), source.serverNotes.map(SourceNote::markdown))
    }

    // ------------------------------------------------------------------ #43 Source removal during transfer

    @Test
    fun `removing a Source waits for an in-flight move and refuses while its result is pending`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = localNote(engine, "Moving")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.writeGate = { entered.complete(Unit); release.await() }
        val move = async { engine.moveToSource(id, source.descriptor.id) }
        entered.await()
        val removal = async { runCatching { engine.removeSource(source.descriptor.id) } }
        repeat(5) { yield() }
        assertFalse(removal.isCompleted)
        engine.updateContent(id, "Moving, edited during the request")
        release.complete(Unit)
        move.await()

        assertTrue(removal.await().exceptionOrNull() is IllegalStateException)
        assertTrue(engine.sources.value.any { it.id == source.descriptor.id })
        source.writeGate = null
        val reopened = open().also { it.attachSource(source) }
        assertEquals(source.descriptor.id, assertNotNull(reopened.note(id)).sourceId)
        reopened.synchronize(id)
        assertEquals("Moving, edited during the request", source.serverNotes.single().markdown)
        reopened.removeSource(source.descriptor.id)
        assertTrue(reopened.library.value.active.isEmpty())
    }

    @Test
    fun `a cancelled move still completes before the Source can be removed consistently`() = runBlocking {
        val source = FakeSource(SourceId("cloud"))
        val engine = connected(source)
        val id = localNote(engine, "Moving")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.writeGate = { entered.complete(Unit); release.await() }
        val move = launch { engine.moveToSource(id, source.descriptor.id) }
        entered.await()
        move.cancel()
        val removal = async { engine.removeSource(source.descriptor.id) }
        repeat(5) { yield() }
        assertFalse(removal.isCompleted)
        release.complete(Unit)
        move.join()
        removal.await()

        assertEquals(1, source.serverNotes.size)
        assertTrue(engine.sources.value.none { it.id == source.descriptor.id })
        val reopened = open()
        assertTrue(reopened.library.value.active.isEmpty())
        assertNull(reopened.note(id))
        // The account's Note is recoverable from the Source itself.
        reopened.attachSource(source)
        reopened.refreshSources()
        assertEquals(listOf("Moving"), reopened.library.value.active.map(NoteSummary::title))
    }

    // ------------------------------------------------------------------ #32 recovery inventory

    @Test
    fun `unreadable Local records are listed with a diagnosis, rescued without touching evidence and only removed on request`() = runBlocking {
        val engine = open()
        val healthy = localNote(engine, "Healthy note")
        val malformed = localNote(engine, "Malformed state")
        val future = localNote(engine, "Future schema")
        val missing = localNote(engine, "Missing body")
        val withImage = localNote(engine, "With image")
        val attachment = engine.importManagedAttachment(withImage, AttachmentImport("photo.png", "image/png") { png.inputStream() })
        val inaccessible = localNote(engine, "Inaccessible")
        fun meta(id: NoteId) = localRoot.resolve(".kgs/notes/${id.value}.properties")
        meta(malformed).writeText(meta(malformed).readText().replace("state=ACTIVE", "state=INVALID_STATE"))
        meta(future).writeText(meta(future).readText().replace("schema=1", "schema=7"))
        Files.delete(localRoot.resolve("Missing body.md"))
        Files.list(localRoot.resolve(".kgs-notes-attachments/${withImage.value}")).use { it.iterator().asSequence().toList() }.forEach(Files::delete)
        Files.setPosixFilePermissions(meta(inaccessible), PosixFilePermissions.fromString("---------"))
        val malformedEvidence = Files.readAllBytes(localRoot.resolve("Malformed state.md"))
        val malformedMetadata = Files.readAllBytes(meta(malformed))

        try {
            val reopened = open()
            assertEquals("Healthy note", assertNotNull(reopened.note(healthy)).markdown)
            assertNotNull(reopened.note(withImage))
            val records = reopened.unreadableRecords.value.associateBy { it.noteId to it.kind }
            assertEquals(5, records.size)
            val malformedRecord = assertNotNull(records[malformed to UnreadableRecordKind.LOCAL_NOTE])
            assertEquals(UnreadableReason.MALFORMED_METADATA, malformedRecord.reason)
            assertTrue(malformedRecord.hasContent)
            assertEquals("Malformed state", malformedRecord.title)
            assertEquals(UnreadableReason.UNSUPPORTED_VERSION, records[future to UnreadableRecordKind.LOCAL_NOTE]?.reason)
            val missingRecord = assertNotNull(records[missing to UnreadableRecordKind.LOCAL_NOTE])
            assertEquals(UnreadableReason.MISSING_CONTENT, missingRecord.reason)
            assertFalse(missingRecord.hasContent)
            assertEquals(UnreadableReason.MISSING_CONTENT, records[withImage to UnreadableRecordKind.LOCAL_ATTACHMENT]?.reason)
            assertEquals(UnreadableReason.INACCESSIBLE, records.values.single { it.reason == UnreadableReason.INACCESSIBLE }.reason)
            assertNull(reopened.note(malformed))

            val first = reopened.rescueUnreadableRecord(malformedRecord.id)
            val second = reopened.rescueUnreadableRecord(malformedRecord.id)
            assertNotEquals(first, second)
            assertEquals("Malformed state", assertNotNull(reopened.note(first)).markdown)
            assertEquals("Malformed state", assertNotNull(reopened.note(second)).markdown)
            assertContentEquals(malformedEvidence, Files.readAllBytes(localRoot.resolve("Malformed state.md")))
            assertContentEquals(malformedMetadata, Files.readAllBytes(meta(malformed)))
            val rescuedMissing = reopened.rescueUnreadableRecord(missingRecord.id)
            assertTrue(assertNotNull(reopened.note(rescuedMissing)).markdown.contains("relativePath="))

            reopened.discardUnreadableRecord(records.getValue(future to UnreadableRecordKind.LOCAL_NOTE).id)
            assertFalse(meta(future).exists())
            assertEquals(4, reopened.unreadableRecords.value.size)

            val again = open()
            assertEquals(4, again.unreadableRecords.value.size)
            assertTrue(again.unreadableRecords.value.any { it.id == malformedRecord.id })
            assertEquals(2, again.library.value.active.count { it.title.startsWith("Recovered: Malformed state") })
            assertNotNull(again.managedAttachments(withImage)) // the Note itself still opens
            assertNull(again.openManagedAttachment(attachment.id))
        } finally {
            Files.setPosixFilePermissions(meta(inaccessible), PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun `an unreadable Working Copy is quarantined so a refresh cannot overwrite its evidence`() = runBlocking {
        val source = FakeSource(SourceId("cloud"), listOf(SourceNote("1", "r0", "Remote", "server body", "", false, Instant.EPOCH, false)))
        val engine = connected(source)
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "unsynced offline body")
        val record = workingRoot.resolve("notes/${id.value}.properties")
        record.writeText(record.readText().replace("state=ACTIVE", "state=BOGUS"))
        val evidence = record.readBytes()

        val reopened = open().also { it.attachSource(source) }
        val unreadable = reopened.unreadableRecords.value.single()
        assertEquals(UnreadableRecordKind.WORKING_COPY, unreadable.kind)
        assertEquals(UnreadableReason.MALFORMED_METADATA, unreadable.reason)
        assertEquals(id, unreadable.noteId)
        assertTrue(unreadable.hasContent)
        assertFalse(record.exists())
        reopened.refreshSources()
        // The remote Note is listed again, under another identity; the evidence is byte-identical.
        val listed = reopened.library.value.active.single()
        assertNotEquals(id, listed.id)
        val preserved = Paths.get(unreadable.files.single { it.endsWith(".properties") })
        assertContentEquals(evidence, preserved.readBytes())

        val again = open().also { it.attachSource(source) }
        assertEquals(listOf(unreadable.id), again.unreadableRecords.value.map(UnreadableRecord::id))
        val rescued = again.rescueUnreadableRecord(unreadable.id)
        assertEquals("unsynced offline body", assertNotNull(again.note(rescued)).markdown)
        assertEquals(LocalSourceId, assertNotNull(again.note(rescued)).sourceId)
        assertContentEquals(evidence, preserved.readBytes())
        again.discardUnreadableRecord(unreadable.id)
        assertFalse(preserved.exists())
        assertTrue(open().unreadableRecords.value.isEmpty())
    }

    // ------------------------------------------------------------------ #44 attachment paths

    @Test
    fun `renaming a restored Note after Category changes in Trash keeps attachment targets resolvable`() = runBlocking {
        for (change in listOf("delete", "deeper", "shallower")) {
            val store = root.resolve("case-$change")
            var engine: NotesEngine = LocalNotesEngine.open(store)
            val id = engine.createDraft(category = if (change == "shallower") "A/B/C/D" else "Trips/Forest")
            val attachment = engine.importManagedAttachment(id, AttachmentImport("photo.png", "image/png") { png.inputStream() })
            engine.updateContent(id, "# Walk\n\n![photo](${attachment.markdownTarget})\n")
            engine.close(id)
            engine.moveToTrash(id)
            when (change) {
                "delete" -> engine.deleteCategory(LocalSourceId, "Trips")
                "deeper" -> engine.renameCategory(LocalSourceId, "Trips", "One/Two/Three")
                else -> engine.renameCategory(LocalSourceId, "A", "Z")
            }
            engine.restore(id)
            engine.rename(id, "Renamed")
            engine = LocalNotesEngine.open(store)

            val note = assertNotNull(engine.note(id))
            val file = Files.walk(store).use { paths -> paths.iterator().asSequence().single { it.name == "Renamed.md" } }
            val target = Regex("!\\[photo]\\(([^)]+)\\)").find(file.readText())!!.groupValues[1]
            val resolved = file.parent.resolve(target).normalize()
            assertTrue(resolved.isRegularFile(), "$change: $target from ${store.relativize(file)}")
            assertContentEquals(png, resolved.readBytes())
            assertEquals(file.readText(), note.markdown)
            assertContentEquals(png, assertNotNull(engine.openManagedAttachment(attachment.id)).use { it.readBytes() })
        }
    }

    @Test
    fun `copying a Local Note duplicates its attachments with resolvable targets and leaves the original untouched`() = runBlocking {
        val engine = open()
        val id = engine.createDraft(category = "Trips/Forest")
        val attachment = engine.importManagedAttachment(id, AttachmentImport("photo.png", "image/png") { png.inputStream() })
        engine.updateContent(id, "# Walk\n\n![photo](${attachment.markdownTarget})\n")
        engine.toggleFavorite(id)
        engine.close(id)
        val original = assertNotNull(engine.note(id))

        val copy = engine.copyToLocal(id, "# Walk, unsaved draft\n\n![photo](${attachment.markdownTarget})\n")
        engine.moveToTrash(id)
        engine.deletePermanently(id)

        val reopened = open()
        val copied = assertNotNull(reopened.note(copy))
        assertTrue(copied.favorite)
        assertEquals(original.createdAt, copied.createdAt)
        assertEquals("Trips/Forest", copied.category)
        val copiedAttachment = reopened.managedAttachments(copy).single()
        assertNotEquals(attachment.id, copiedAttachment.id)
        assertTrue(copied.markdown.startsWith("# Walk, unsaved draft"))
        assertTrue(copied.markdown.contains(copiedAttachment.markdownTarget))
        assertContentEquals(png, assertNotNull(reopened.openManagedAttachment(copiedAttachment.id)).use { it.readBytes() })
    }
}
