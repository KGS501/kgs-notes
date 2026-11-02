package com.kgs.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class NoteExportTest {
    private val markdown = "# Forest\r\n\nLiteral *markers* stay \\*as written\\*.\n\n![Pine](../../.kgs-notes-attachments/n1/a1.png)\n"
    private val note = ExportedNote("n1", "local", "Forest: day 1", "Trips/Forest", true, markdown,
        "2026-08-22T08:00:00Z", "2026-08-22T09:00:00Z")

    @Test
    fun markdownExportIsTheCanonicalBodyByteForByte() {
        val output = ByteArrayOutputStream()
        writeNoteMarkdown(output, note)
        assertArrayEquals(markdown.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertEquals(NoteExportFile("Forest_ day 1.md", "text/markdown", false), noteExportFile(note.title, false))
        assertEquals("Forest_ day 1.zip", noteExportFile(note.title, true).fileName)
    }

    @Test
    fun archiveKeepsEveryManagedTargetResolvable() {
        val image = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        writeNoteArchive(output, note, listOf(ExportedAttachment("a1", "Pine.png", "image/png",
            "../../.kgs-notes-attachments/n1/a1.png") { ByteArrayInputStream(image) }))
        val entries = unzip(output.toByteArray())
        assertEquals(markdown, String(entries.getValue("Trips/Forest/Forest_ day 1.md"), Charsets.UTF_8))
        assertArrayEquals(image, entries.getValue(".kgs-notes-attachments/n1/a1.png"))
        val manifest = String(entries.getValue(ExportManifestName), Charsets.UTF_8)
        assertTrue(manifest.contains("\"id\": \"n1\""))
        assertTrue(manifest.contains("\"favorite\": true"))
        assertTrue(!manifest.contains("password", ignoreCase = true))
    }

    @Test
    fun missingAttachmentBytesFailInsteadOfProducingAPartialArchive() {
        val result = runCatching {
            writeNoteArchive(ByteArrayOutputStream(), note, listOf(ExportedAttachment("a1", "Pine.png", "image/png",
                "../../.kgs-notes-attachments/n1/a1.png") { null }))
        }
        assertTrue(result.exceptionOrNull() is IncompleteExportException)
    }

    @Test
    fun everyTargetStaysInsideTheArchive() {
        // The Note is nested deeply enough for its deepest climb; nothing can leave the archive root.
        val layout = noteArchiveLayout(note.copy(category = ""), listOf("../../../etc/passwd", "/abs", "https://x/y", "..\\x"))
        assertEquals("Note/Note/Note/Forest_ day 1.md", layout.notePath)
        assertEquals(listOf("etc/passwd", null, null, null), layout.attachmentPaths)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }
}
