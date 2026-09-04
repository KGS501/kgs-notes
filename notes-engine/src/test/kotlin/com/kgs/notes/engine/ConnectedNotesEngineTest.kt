package com.kgs.notes.engine

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class ConnectedNotesEngineTest {
    @TempDir
    lateinit var root: Path

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
        assertTrue(root.resolve("working/base/${id.value}.md").isRegularFile())
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

        override suspend fun refresh() = Unit

        override suspend fun createNote(note: SourceNoteDraft): SourceNote = error("Not used")

        override suspend fun updateNote(
            remoteId: String,
            expectedRevision: String,
            note: SourceNoteDraft,
        ): SourceNote {
            if (conflictOnUpdate) throw SourceConflictException()
            lastExpectedRevision = expectedRevision
            lastDraft = note
            val updated = mutableNotes.value.single { it.remoteId == remoteId }.copy(
                revision = "after-edit",
                title = note.title,
                markdown = note.markdown,
                category = note.category,
                favorite = note.favorite,
                modifiedAt = note.modifiedAt,
            )
            mutableNotes.value = mutableNotes.value.filterNot { it.remoteId == remoteId } + updated
            return updated
        }

        override suspend fun deleteNote(remoteId: String, expectedRevision: String) {
            val current = mutableNotes.value.single { it.remoteId == remoteId }
            if (current.revision != expectedRevision) throw SourceConflictException()
            lastDeletedRevision = expectedRevision
            mutableNotes.value = mutableNotes.value.filterNot { it.remoteId == remoteId }
        }
    }
}
