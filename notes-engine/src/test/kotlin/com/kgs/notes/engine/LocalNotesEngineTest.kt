package com.kgs.notes.engine

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class LocalNotesEngineTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `saved Markdown remains canonical after the engine is reopened`() = runBlocking {
        val markdown = """
            # Weekend cabin

            - [x] Pack coffee
            - [ ] Bring the blue blanket 🌿
        """.trimIndent()
        val clock = Clock.fixed(Instant.parse("2026-08-22T08:00:00Z"), ZoneOffset.UTC)

        val firstEngine = LocalNotesEngine.open(root, clock)
        val noteId = firstEngine.createDraft()
        firstEngine.updateContent(noteId, markdown)
        firstEngine.close(noteId)

        val reopenedEngine = LocalNotesEngine.open(root, clock)
        val reopened = assertNotNull(reopenedEngine.note(noteId))
        assertEquals("Weekend cabin", reopened.title)
        assertEquals(markdown, reopened.markdown)

        val markdownFiles = Files.walk(root).use { paths ->
            paths.filter { it.extension == "md" }.toList()
        }
        assertEquals(1, markdownFiles.size)
        assertEquals(markdown, markdownFiles.single().readText())
    }

    @Test
    fun `closing an Empty Draft leaves no note or Trash entry`() = runBlocking {
        val engine = LocalNotesEngine.open(root)
        val noteId = engine.createDraft()

        engine.close(noteId)

        assertEquals(null, engine.note(noteId))
        assertTrue(engine.library.value.active.isEmpty())
        assertTrue(engine.library.value.trash.isEmpty())
        val durableFiles = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList()
        }
        assertTrue(durableFiles.isEmpty())
    }

    @Test
    fun `favorite and Trash state survive a restart and can be restored`() = runBlocking {
        val engine = LocalNotesEngine.open(root)
        val noteId = engine.createDraft()
        engine.updateContent(noteId, "# Field notes\n\nClouds over the ridge")
        engine.close(noteId)
        engine.toggleFavorite(noteId)
        engine.moveToTrash(noteId)

        val reopened = LocalNotesEngine.open(root)
        assertTrue(reopened.library.value.active.isEmpty())
        assertEquals(listOf(noteId), reopened.library.value.trash.map { it.id })

        reopened.restore(noteId)
        assertEquals(listOf(noteId), reopened.library.value.favorites.map { it.id })
        assertTrue(reopened.library.value.trash.isEmpty())
    }

    @Test
    fun `notes with the same title keep independent Markdown files`() = runBlocking {
        val engine = LocalNotesEngine.open(root)
        repeat(2) {
            val noteId = engine.createDraft()
            engine.updateContent(noteId, "# Reading list\n\nEntry ${it + 1}")
            engine.close(noteId)
        }

        val markdownFiles = Files.walk(root).use { paths ->
            paths.filter { it.extension == "md" }.map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("Reading list (2).md", "Reading list.md"), markdownFiles)
    }

    @Test
    fun `Local Source notes expose their Source and local-only synchronization state`() = runBlocking {
        val createdAt = Instant.parse("2026-08-22T08:00:00Z")
        val engine = LocalNotesEngine.open(
            root,
            Clock.fixed(createdAt, ZoneOffset.UTC),
        )
        val noteId = engine.createDraft()
        engine.updateContent(noteId, "# On this device")

        val note = assertNotNull(engine.note(noteId))
        val summary = engine.library.value.active.single()

        assertEquals(LocalSourceId, note.sourceId)
        assertEquals(NoteSyncState.LOCAL_SOURCE, note.syncState)
        assertEquals(createdAt, summary.createdAt)
        assertEquals(LocalSourceId, summary.sourceId)
        assertEquals(NoteSyncState.LOCAL_SOURCE, summary.syncState)
    }
}
