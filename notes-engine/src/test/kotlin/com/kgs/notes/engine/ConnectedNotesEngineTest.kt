package com.kgs.notes.engine

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import org.junit.jupiter.api.io.TempDir

class ConnectedNotesEngineTest {
    @Test
    fun `empty Categories are kept per Source and leave with their Source`() = runBlocking {
        val source = ControlledSource(SourceId("category-cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.createCategory(LocalSourceId, "Home")
        engine.createCategory(source.descriptor.id, "Work")

        val reopened: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        reopened.attachSource(source)
        assertEquals(setOf("Home"), reopened.library.value.categories[LocalSourceId])
        assertEquals(setOf("Work"), reopened.library.value.categories[source.descriptor.id])
        reopened.renameCategory(source.descriptor.id, "Work", "Office")
        assertEquals(setOf("Office"), reopened.library.value.categories[source.descriptor.id])
        assertTrue(source.notes.value.isEmpty())
        reopened.removeSource(source.descriptor.id)
        assertEquals(null, reopened.library.value.categories[source.descriptor.id])
        assertEquals(setOf("Home"), reopened.library.value.categories[LocalSourceId])
    }

    @Test
    fun `Categories created by another client keep their exact lookup path during management`() = runBlocking {
        val source = ControlledSource(SourceId("external-categories"), listOf(SourceNote(
            remoteId = "remote-1", revision = "r1", title = "Fixture", markdown = "Body",
            category = "Work: projects/Sub", favorite = false, modifiedAt = Instant.EPOCH, readOnly = false,
        )))
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id
        assertEquals(setOf(id), engine.renameCategory(source.descriptor.id, "Work: projects", "Office"))
        engine.synchronize(id)
        assertEquals("Office/Sub", source.notes.value.single().category)
        engine.deleteCategory(source.descriptor.id, "Office").forEach { engine.synchronize(it) }
        assertEquals("", source.notes.value.single().category)
    }

    @Test
    fun `Category operations queue ordinary conflict safe Source updates and preserve other Sources`() = runBlocking {
        val source = ControlledSource(SourceId("category-cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val remote = engine.createDraft(category = "Work/Sub", sourceId = source.descriptor.id)
        val local = engine.createDraft(category = "Work/Sub")
        engine.updateContent(remote, "Server body")
        engine.updateContent(local, "Local body")
        engine.synchronize(remote)
        engine.renameCategory(source.descriptor.id, "Work", "Office")
        assertEquals("Work/Sub", engine.note(local)?.category)
        assertEquals("Office/Sub", engine.note(remote)?.category)
        assertEquals(NoteSyncState.SAVED_LOCALLY, engine.note(remote)?.syncState)
        engine.synchronize(remote)
        assertEquals("Office/Sub", source.notes.value.single().category)
        engine.deleteCategory(source.descriptor.id, "Office")
        engine.synchronize(remote)
        assertEquals("", source.notes.value.single().category)
        assertEquals("Server body", source.notes.value.single().markdown)
        assertEquals("Work/Sub", engine.note(local)?.category)
        assertEquals(2, engine.library.value.active.size)
    }

    @Test
    fun `fresh storage and empty reopening expose a usable Local Source`() = runBlocking {
        repeat(2) {
            val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
            assertTrue(engine.library.value.active.isEmpty())
            assertTrue(engine.library.value.trash.isEmpty())
            assertEquals(LocalSourceId, engine.sources.value.single().id)
            assertTrue(engine.sources.value.single().capabilities.writable)
            val draft = engine.createDraft()
            engine.close(draft)
            assertTrue(engine.library.value.active.isEmpty())
        }
    }

    @TempDir
    lateinit var root: Path

    @Test
    fun `refresh attempts every Source when one is unavailable and preserves its Working Copies`() = runBlocking {
        val unavailable = ControlledSource(SourceId("offline"), emptyList())
        val available = ControlledSource(SourceId("online"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(unavailable)
        engine.attachSource(available)
        val offlineId = engine.createDraft(sourceId = unavailable.descriptor.id)
        engine.updateContent(offlineId, "Offline fixture")
        engine.synchronize(offlineId)
        val onlineId = engine.createDraft(sourceId = available.descriptor.id)
        engine.updateContent(onlineId, "Online fixture")
        engine.synchronize(onlineId)
        unavailable.beforeRefresh = { throw java.io.IOException("Unavailable fixture") }
        available.editRemotely("Updated remote fixture")

        assertFailsWith<java.io.IOException> { engine.refreshSources() }

        assertEquals("Offline fixture", assertNotNull(engine.note(offlineId)).markdown)
        assertEquals("Updated remote fixture", assertNotNull(engine.note(onlineId)).markdown)
    }

    @Test
    fun `cancelling a refresh stops before starting another Source`() = runBlocking {
        val first = ControlledSource(SourceId("cancelled"), emptyList()).apply {
            beforeRefresh = { throw kotlinx.coroutines.CancellationException("Cancelled fixture") }
        }
        var laterRefreshes = 0
        val later = ControlledSource(SourceId("later"), emptyList()).apply {
            beforeRefresh = { laterRefreshes++ }
        }
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(first)
        engine.attachSource(later)

        assertFailsWith<kotlinx.coroutines.CancellationException> { engine.refreshSources() }

        assertEquals(0, laterRefreshes)
    }

    @Test
    fun `pinning a Working Copy stays local and survives refresh and Source moves`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Pinned content")
        engine.synchronize(id)
        val before = assertNotNull(engine.note(id))
        engine.togglePin(id)
        assertTrue(engine.library.value.active.single().pinned)
        assertEquals(NoteSyncState.SYNCED, engine.note(id)?.syncState)
        assertEquals(before.updatedAt, engine.note(id)?.updatedAt)
        engine.refreshSources()
        assertTrue(assertNotNull(engine.note(id)).pinned)
        val reopened: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        reopened.attachSource(source)
        assertTrue(assertNotNull(reopened.note(id)).pinned)
        reopened.moveToSource(id, LocalSourceId)
        assertTrue(assertNotNull(reopened.note(id)).pinned)
        reopened.moveToSource(id, source.descriptor.id)
        assertTrue(assertNotNull(reopened.note(id)).pinned)
        assertEquals(false, source.notes.value.single().favorite)
    }

    @Test
    fun `edits while moving to local are retained before the Working Copy is removed`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Initial content")
        engine.synchronize(id)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.beforeWrite = { entered.complete(Unit); release.await() }

        val transfer = async { engine.moveToSource(id, LocalSourceId) }
        entered.await()
        assertEquals(source.descriptor.id, assertNotNull(engine.note(id)).sourceId)
        engine.updateContent(id, "Newer content")
        engine.rename(id, "Newer title")
        release.complete(Unit)
        assertEquals(id, transfer.await())

        val moved = assertNotNull(engine.note(id))
        assertEquals(LocalSourceId, moved.sourceId)
        assertEquals("Newer content", moved.markdown)
        assertEquals("Newer title", moved.title)
        assertTrue(source.notes.value.isEmpty())
        val reopened: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        assertEquals(moved, reopened.note(id))
    }

    @Test
    fun `cancelling an issued transfer retains the confirmed Note identity`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = ControlledSource(SourceId("cloud"), emptyList()).apply {
            beforeWrite = { entered.complete(Unit); release.await() }
        }
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft()
        engine.updateContent(id, "Preserved content")
        val transfer = launch { engine.moveToSource(id, source.descriptor.id) }
        entered.await()
        transfer.cancel()
        release.complete(Unit)
        transfer.join()

        val reopened: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        reopened.attachSource(source)
        reopened.refreshSources()
        assertEquals(source.descriptor.id, assertNotNull(reopened.note(id)).sourceId)
        assertEquals("Preserved content", assertNotNull(reopened.note(id)).markdown)
        assertEquals(listOf(id), reopened.library.value.active.map(NoteSummary::id))
        assertEquals(1, source.notes.value.size)
    }

    @Test
    fun `a failed publish leaves the original local Note intact`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList()).apply {
            beforeWrite = { throw IllegalStateException("Controlled outage") }
        }
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft()
        engine.updateContent(id, "Preserved local content")
        val original = engine.note(id)

        assertFailsWith<IllegalStateException> { engine.moveToSource(id, source.descriptor.id) }

        assertEquals(original, engine.note(id))
        assertEquals(listOf(id), engine.library.value.active.map(NoteSummary::id))
        assertTrue(source.notes.value.isEmpty())
    }

    @Test
    fun `edits while moving to the server stay pending under the same identity`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = ControlledSource(SourceId("cloud"), emptyList()).apply {
            beforeWrite = { entered.complete(Unit); release.await() }
        }
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft()
        engine.updateContent(id, "Initial content")
        val transfer = async { engine.moveToSource(id, source.descriptor.id) }
        entered.await()
        // The transfer intent is durable before the create is sent: the Note is listed under its destination.
        assertEquals(source.descriptor.id, assertNotNull(engine.note(id)).sourceId)
        assertEquals(NoteAttention.TRANSFER_PENDING, assertNotNull(engine.note(id)).attention)
        engine.updateContent(id, "Newer content")
        release.complete(Unit)
        assertEquals(id, transfer.await())

        assertEquals("Newer content", assertNotNull(engine.note(id)).markdown)
        assertEquals(NoteSyncState.SAVED_LOCALLY, assertNotNull(engine.note(id)).syncState)
        engine.synchronize(id)
        assertEquals("Newer content", source.notes.value.single().markdown)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
    }

    @Test
    fun `a move to local during a remote change keeps the server Note and moves the pending edits`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Original content")
        engine.synchronize(id)
        engine.updateContent(id, "My pending edit")
        source.editRemotely("Changed on server")

        val movedId = engine.moveToSource(id, LocalSourceId)

        assertEquals(LocalSourceId, assertNotNull(engine.note(movedId)).sourceId)
        assertEquals("My pending edit", assertNotNull(engine.note(movedId)).markdown)
        assertEquals(source.descriptor.id, assertNotNull(engine.note(id)).sourceId)
        assertEquals("Changed on server", assertNotNull(engine.note(id)).markdown)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        assertEquals("Changed on server", source.notes.value.single().markdown)
        assertEquals(setOf(id, movedId), engine.library.value.active.map(NoteSummary::id).toSet())
    }

    @Test
    fun `moving a Note to the server and back keeps its identity content and metadata`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(category = "Trips")
        engine.updateContent(id, "# Transfer fixture\n\nKeep this content")
        engine.rename(id, "Travel")
        engine.toggleFavorite(id)
        val original = assertNotNull(engine.openEditingSession(id))

        assertEquals(id, engine.moveToSource(id, source.descriptor.id))
        assertEquals(source.descriptor.id, assertNotNull(engine.note(id)).sourceId)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        assertEquals(listOf(id), engine.library.value.active.map(NoteSummary::id))
        assertEquals(1, source.notes.value.size)

        engine.updateContent(id, "# Transfer fixture\n\nNew local edit")
        assertEquals(id, engine.moveToSource(id, LocalSourceId))
        val moved = assertNotNull(engine.note(id))
        assertEquals(LocalSourceId, moved.sourceId)
        assertEquals(NoteSyncState.LOCAL_SOURCE, moved.syncState)
        assertEquals("# Transfer fixture\n\nNew local edit", moved.markdown)
        assertEquals(original.title, moved.title)
        assertEquals(original.category, moved.category)
        assertEquals(original.favorite, moved.favorite)
        assertEquals(original.createdAt, moved.createdAt)
        assertTrue(source.notes.value.isEmpty())
        val reopened: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        assertEquals(moved, reopened.note(id))
        assertEquals(listOf(id), reopened.library.value.active.map(NoteSummary::id))
    }

    @Test
    fun `refresh cannot advance an open editor base past unseen remote changes`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Displayed base")
        engine.synchronize(id)
        engine.openEditingSession(id)
        source.editRemotely("External edit")
        engine.refreshSources()

        assertEquals("Displayed base", assertNotNull(engine.note(id)).markdown)
        engine.updateContent(id, "Typed before refresh finished")
        engine.synchronize(id)
        assertEquals(NoteSyncState.NEEDS_ATTENTION, assertNotNull(engine.note(id)).syncState)
        assertEquals("External edit", source.notes.value.single().markdown)
        assertEquals("Typed before refresh finished", assertNotNull(engine.note(id)).markdown)
    }

    @Test
    fun `closing an unchanged editor allows the next remote refresh`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Displayed base")
        engine.synchronize(id)
        engine.openEditingSession(id)
        source.editRemotely("External edit")
        engine.close(id)
        engine.refreshSources()

        assertEquals("External edit", assertNotNull(engine.note(id)).markdown)
    }

    @Test
    fun `trashing an unsynced Note never publishes it to the Source`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Keep only in Trash")
        engine.moveToTrash(id)
        engine.synchronize(id)

        assertTrue(source.notes.value.isEmpty())
        assertEquals(NoteState.TRASHED, assertNotNull(engine.note(id)).state)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
    }

    @Test
    fun `trashing during creation deletes the confirmed remote identity`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = ControlledSource(SourceId("cloud"), emptyList()).apply {
            beforeWrite = { entered.complete(Unit); release.await() }
        }
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Recoverable content")
        val upload = async { engine.synchronize(id) }
        entered.await()
        engine.moveToTrash(id)
        release.complete(Unit)
        upload.await()
        engine.synchronize(id)

        assertTrue(source.notes.value.isEmpty())
        assertEquals("Recoverable content", assertNotNull(engine.note(id)).markdown)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
    }

    @Test
    fun `cancelling an issued upload retains its identity across reopening`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = ControlledSource(SourceId("cloud"), emptyList()).apply {
            beforeWrite = { entered.complete(Unit); release.await() }
        }
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Uploaded content")
        val upload = launch { engine.synchronize(id) }
        entered.await()
        upload.cancel()
        release.complete(Unit)
        upload.join()
        assertTrue(upload.isCancelled)
        val reopened: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        reopened.attachSource(source)
        reopened.synchronize(id)

        assertEquals(1, source.notes.value.size)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(reopened.note(id)).syncState)
    }

    @Test
    fun `edits made during an upload remain pending instead of being replaced`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = ControlledSource(SourceId("cloud"), emptyList()).apply {
            beforeWrite = { entered.complete(Unit); release.await() }
        }
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "First edit")
        val upload = async { engine.synchronize(id) }
        entered.await()
        engine.updateContent(id, "Newer edit")
        engine.rename(id, "New title")
        engine.setCategory(id, "New category")
        engine.toggleFavorite(id)
        release.complete(Unit)
        upload.await()

        val pending = assertNotNull(engine.note(id))
        assertEquals("Newer edit", pending.markdown)
        assertEquals("New title", pending.title)
        assertEquals("New category", pending.category)
        assertTrue(pending.favorite)
        assertEquals(NoteSyncState.SAVED_LOCALLY, pending.syncState)
        engine.synchronize(id)
        assertEquals(1, source.notes.value.size)
        assertEquals("Newer edit", source.notes.value.single().markdown)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
    }

    @Test
    fun `overlapping sync requests create one remote Note`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = ControlledSource(SourceId("cloud"), emptyList()).apply {
            beforeWrite = { entered.complete(Unit); release.await() }
        }
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "First edit")
        val first = async { engine.synchronize(id) }
        entered.await()
        engine.updateContent(id, "Second edit")
        val second = async { engine.synchronize(id) }
        yield()
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, source.notes.value.size)
        assertEquals("Second edit", source.notes.value.single().markdown)
    }

    @Test
    fun `restoring during remote deletion leaves a pending Note that can be republished`() = runBlocking {
        val source = ControlledSource(SourceId("cloud"), emptyList())
        val engine: NotesEngine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = source.descriptor.id)
        engine.updateContent(id, "Keep this Note")
        engine.synchronize(id)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.beforeWrite = { entered.complete(Unit); release.await() }
        engine.moveToTrash(id)
        val deletion = async { engine.synchronize(id) }
        entered.await()
        engine.restore(id)
        release.complete(Unit)
        deletion.await()

        assertEquals(NoteState.ACTIVE, assertNotNull(engine.note(id)).state)
        assertEquals(NoteSyncState.SAVED_LOCALLY, assertNotNull(engine.note(id)).syncState)
        engine.synchronize(id)
        assertEquals("Keep this Note", source.notes.value.single().markdown)
    }

    @Test
    fun `removing a Source keeps remote Notes and refuses pending edits`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val remote = SourceNote(
            remoteId = "42",
            revision = "etag-42",
            title = "Field notes",
            markdown = "Clouds over the ridge",
            category = "",
            favorite = false,
            modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
            readOnly = false,
        )
        val source = ControlledSource(sourceId, listOf(remote))
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "Unsynced edit")

        assertFailsWith<IllegalStateException> { engine.removeSource(sourceId) }
        assertEquals("Unsynced edit", assertNotNull(engine.note(id)).markdown)

        engine.synchronize(id)
        engine.removeSource(sourceId)

        assertTrue(engine.library.value.active.isEmpty())
        assertTrue(engine.sources.value.none { it.id == sourceId })
        assertEquals("Unsynced edit", source.notes.value.single().markdown)
    }

    @Test
    fun `refreshing a Source creates a durable offline Working Copy`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val remote = SourceNote(
            remoteId = "42",
            revision = "etag-42",
            title = "Field notes",
            markdown = "# Field notes\n\nClouds over the ridge",
            category = "Trips/Forest",
            favorite = true,
            modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
            readOnly = false,
        )
        val source = ControlledSource(sourceId, listOf(remote))
        val clock = Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC)
        val engine: NotesEngine = ConnectedNotesEngine.open(
            localRoot = root.resolve("local"),
            workingCopyRoot = root.resolve("working"),
            clock = clock,
        )

        engine.attachSource(source)
        engine.refreshSources()
        engine.refreshSources()

        val summary = engine.library.value.active.single()
        assertEquals(sourceId, summary.sourceId)
        assertEquals(NoteSyncState.SYNCED, summary.syncState)
        assertEquals(remote.markdown, assertNotNull(engine.note(summary.id)).markdown)

        val reopened: NotesEngine = ConnectedNotesEngine.open(
            localRoot = root.resolve("local"),
            workingCopyRoot = root.resolve("working"),
            clock = clock,
        )
        val reopenedNote = assertNotNull(reopened.note(summary.id))
        assertEquals(remote.markdown, reopenedNote.markdown)
        assertEquals(sourceId, reopenedNote.sourceId)
    }

    @Test
    fun `editing a Working Copy saves locally then updates with its expected revision`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(
            sourceId,
            listOf(
                SourceNote(
                    remoteId = "7",
                    revision = "before-edit",
                    title = "Packing",
                    markdown = "- coat",
                    category = "Trips",
                    favorite = false,
                    modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
                    readOnly = false,
                ),
            ),
        )
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id

        engine.updateContent(id, "- coat\n- camera")

        assertEquals(NoteSyncState.SAVED_LOCALLY, assertNotNull(engine.note(id)).syncState)
        engine.synchronize(id)

        assertEquals("before-edit", source.lastExpectedRevision)
        assertEquals("- coat\n- camera", source.lastDraft?.markdown)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        assertEquals("after-edit", source.notes.value.single().revision)
    }

    @Test
    fun `a revision conflict keeps local Markdown and asks for attention`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(
            sourceId,
            listOf(
                SourceNote(
                    remoteId = "9",
                    revision = "old",
                    title = "Ideas",
                    markdown = "Remote base",
                    category = "",
                    favorite = false,
                    modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
                    readOnly = false,
                ),
            ),
        ).apply { conflictOnUpdate = true }
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "My offline edit")

        engine.synchronize(id)

        val preserved = assertNotNull(engine.note(id))
        assertEquals("My offline edit", preserved.markdown)
        assertEquals(NoteSyncState.NEEDS_ATTENTION, preserved.syncState)
        assertEquals(NoteAttention.CONFLICT, preserved.attention)
        // One record holds body, base and sync state (schema 2).
        assertTrue(root.resolve("working/notes/${id.value}.properties").isRegularFile())
        val reopened = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        assertEquals("My offline edit", assertNotNull(reopened.note(id)).markdown)
        assertEquals(NoteSyncState.NEEDS_ATTENTION, assertNotNull(reopened.note(id)).syncState)
    }

    @Test
    fun `Trash keeps a recoverable Working Copy after revision checked remote deletion`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(
            sourceId,
            listOf(
                SourceNote(
                    remoteId = "11",
                    revision = "known-before-delete",
                    title = "Temporary",
                    markdown = "Still recoverable",
                    category = "",
                    favorite = false,
                    modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
                    readOnly = false,
                ),
            ),
        )
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id

        engine.moveToTrash(id)
        engine.synchronize(id)

        assertEquals("known-before-delete", source.lastDeletedRevision)
        assertTrue(source.notes.value.isEmpty())
        assertEquals("Still recoverable", assertNotNull(engine.note(id)).markdown)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        assertEquals(listOf(id), engine.library.value.trash.map(NoteSummary::id))
    }

    @Test
    fun `a create whose response was lost is adopted after refresh instead of published twice`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(sourceId, emptyList())
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = sourceId)
        engine.updateContent(id, "Offline idea")
        engine.close(id)

        source.loseNextResponse = true
        engine.synchronize(id)

        assertEquals(NoteSyncState.SAVED_LOCALLY, assertNotNull(engine.note(id)).syncState)
        assertTrue(engine.library.value.active.single().syncFailed)
        engine.refreshSources()
        engine.synchronize(id)

        assertEquals(1, source.notes.value.size)
        assertEquals(listOf(id), engine.library.value.active.map(NoteSummary::id))
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        assertFalse(engine.library.value.active.single().syncFailed)
    }

    @Test
    fun `retrying a create whose response was lost uploads later edits to the same remote Note`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(sourceId, emptyList())
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        val id = engine.createDraft(sourceId = sourceId)
        engine.updateContent(id, "Offline idea")
        engine.close(id)
        source.loseNextResponse = true
        engine.synchronize(id)

        engine.updateContent(id, "Offline idea\nwith more detail")
        engine.synchronize(id)

        assertEquals(listOf("Offline idea\nwith more detail"), source.notes.value.map(SourceNote::markdown))
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        engine.refreshSources()
        assertEquals(listOf(id), engine.library.value.active.map(NoteSummary::id))
    }

    @Test
    fun `reopening keeps an uncertain create from publishing a second copy`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(sourceId, emptyList())
        val first = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        first.attachSource(source)
        val id = first.createDraft(sourceId = sourceId)
        first.updateContent(id, "Written on the train")
        first.close(id)
        source.loseNextResponse = true
        first.synchronize(id)

        val reopened = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        reopened.attachSource(source)
        reopened.synchronize(id)
        reopened.refreshSources()

        assertEquals(1, source.notes.value.size)
        assertEquals(listOf(id), reopened.library.value.active.map(NoteSummary::id))
    }

    @Test
    fun `an update whose response was lost is confirmed instead of reported as a conflict`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(sourceId, listOf(remoteNote("12", "Groceries", "- milk")))
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "- milk\n- bread")
        source.loseNextResponse = true
        engine.synchronize(id)

        engine.synchronize(id)

        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        engine.updateContent(id, "- milk\n- bread\n- eggs")
        engine.synchronize(id)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        assertEquals("- milk\n- bread\n- eggs", source.notes.value.single().markdown)
    }

    @Test
    fun `retrying a lost update with newer edits after restart keeps its confirmed identity`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(sourceId, emptyList())
        val first = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        first.attachSource(source)
        val id = first.createDraft(sourceId = sourceId)
        first.updateContent(id, "First paragraph")
        first.close(id)
        first.synchronize(id)
        first.updateContent(id, "First paragraph plus detail")
        source.loseNextResponse = true
        first.synchronize(id)
        first.updateContent(id, "First paragraph plus detail and final sentence")

        val reopened = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        reopened.attachSource(source)
        reopened.synchronize(id)

        assertEquals(NoteSyncState.SYNCED, assertNotNull(reopened.note(id)).syncState)
        assertEquals(listOf("First paragraph plus detail and final sentence"), source.notes.value.map(SourceNote::markdown))
        assertEquals(listOf(id), reopened.library.value.active.map(NoteSummary::id))
    }

    @Test
    fun `a lost update followed by more edits is not reported as a conflict on refresh`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(sourceId, listOf(remoteNote("12", "Groceries", "- milk")))
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "- milk\n- bread")
        source.loseNextResponse = true
        engine.synchronize(id)
        engine.updateContent(id, "- milk\n- bread\n- eggs")

        engine.refreshSources()
        engine.synchronize(id)

        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        assertEquals("- milk\n- bread\n- eggs", source.notes.value.single().markdown)
    }

    @Test
    fun `a new remote revision without remote edits keeps local edits pending`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(sourceId, listOf(remoteNote("13", "Plans", "Weekend")))
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "Weekend hike")
        source.touchRemotely()

        engine.refreshSources()
        assertEquals(NoteSyncState.SAVED_LOCALLY, assertNotNull(engine.note(id)).syncState)
        engine.synchronize(id)

        assertEquals(NoteSyncState.SYNCED, assertNotNull(engine.note(id)).syncState)
        assertEquals("Weekend hike", source.notes.value.single().markdown)
    }

    @Test
    fun `moving a conflicted Note to the Local Source keeps both versions`() = runBlocking {
        val sourceId = SourceId("nextcloud-home")
        val source = ControlledSource(sourceId, listOf(remoteNote("14", "Draft", "Base")))
        val engine = ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        engine.attachSource(source)
        engine.refreshSources()
        val id = engine.library.value.active.single().id
        engine.updateContent(id, "Base plus my edit")
        source.editRemotely("Base plus their edit")
        engine.refreshSources()
        assertEquals(NoteSyncState.NEEDS_ATTENTION, assertNotNull(engine.note(id)).syncState)

        val movedId = engine.moveToSource(id, LocalSourceId)

        val moved = assertNotNull(engine.note(movedId))
        assertEquals(LocalSourceId, moved.sourceId)
        assertEquals("Base plus my edit", moved.markdown)
        assertEquals(listOf("Base plus their edit"), source.notes.value.map(SourceNote::markdown))
        val remaining = engine.library.value.active.single { it.sourceId == sourceId }
        assertEquals(NoteSyncState.SYNCED, remaining.syncState)
        assertEquals("Base plus their edit", assertNotNull(engine.note(remaining.id)).markdown)
    }

    private fun remoteNote(remoteId: String, title: String, markdown: String) = SourceNote(
        remoteId = remoteId,
        revision = "initial",
        title = title,
        markdown = markdown,
        category = "",
        favorite = false,
        modifiedAt = Instant.parse("2026-08-24T08:00:00Z"),
        readOnly = false,
    )

    private class ControlledSource(
        sourceId: SourceId,
        initialNotes: List<SourceNote>,
    ) : Source {
        override val descriptor = SourceDescriptor(
            id = sourceId,
            name = "Home cloud",
            capabilities = SourceCapabilities(true, true, true, true, true),
        )
        private val mutableNotes = MutableStateFlow(initialNotes)
        override val notes: StateFlow<List<SourceNote>> = mutableNotes
        var lastExpectedRevision: String? = null
        var lastDraft: SourceNoteDraft? = null
        var conflictOnUpdate = false
        var lastDeletedRevision: String? = null
        var beforeWrite: suspend () -> Unit = {}
        var beforeRefresh: suspend () -> Unit = {}
        /** Applies the next write on the server, then loses its response like a dropped connection. */
        var loseNextResponse = false
        private var revisions = 0

        private fun nextRevision() = "server-${++revisions}"

        private fun <T> respond(value: T): T {
            if (loseNextResponse) {
                loseNextResponse = false
                throw java.io.IOException("Connection reset")
            }
            return value
        }

        fun touchRemotely() {
            mutableNotes.value = mutableNotes.value.map { it.copy(revision = nextRevision()) }
        }

        fun editRemotely(markdown: String) {
            mutableNotes.value = mutableNotes.value.map { it.copy(markdown = markdown, revision = "external-edit") }
        }

        override suspend fun refresh() = beforeRefresh()

        override suspend fun createNote(note: SourceNoteDraft): SourceNote {
            beforeWrite()
            val created = SourceNote(
                remoteId = (mutableNotes.value.size + 100).toString(),
                revision = "created",
                title = note.title,
                markdown = note.markdown,
                category = note.category,
                favorite = note.favorite,
                modifiedAt = note.modifiedAt,
                readOnly = false,
            )
            mutableNotes.value = mutableNotes.value + created
            return respond(created)
        }

        override suspend fun updateNote(
            remoteId: String,
            expectedRevision: String,
            note: SourceNoteDraft,
        ): SourceNote {
            beforeWrite()
            if (conflictOnUpdate || mutableNotes.value.single { it.remoteId == remoteId }.revision != expectedRevision) {
                throw SourceConflictException()
            }
            lastExpectedRevision = expectedRevision
            lastDraft = note
            val updated = mutableNotes.value.single { it.remoteId == remoteId }.copy(
                revision = if (loseNextResponse || expectedRevision.startsWith("server-")) nextRevision() else "after-edit",
                title = note.title,
                markdown = note.markdown,
                category = note.category,
                favorite = note.favorite,
                modifiedAt = note.modifiedAt,
            )
            mutableNotes.value = mutableNotes.value.filterNot { it.remoteId == remoteId } + updated
            return respond(updated)
        }

        override suspend fun deleteNote(remoteId: String, expectedRevision: String) {
            beforeWrite()
            // An already absent Note counts as deleted (Source contract).
            val current = mutableNotes.value.singleOrNull { it.remoteId == remoteId } ?: return respond(Unit)
            if (current.revision != expectedRevision) throw SourceConflictException()
            lastDeletedRevision = expectedRevision
            mutableNotes.value = mutableNotes.value.filterNot { it.remoteId == remoteId }
        }
    }
}
