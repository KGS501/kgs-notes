package com.kgs.notes

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a person is asked to save; a Note with managed attachments becomes a portable archive. */
data class NoteExportFile(val fileName: String, val mimeType: String, val archive: Boolean)

internal data class ExportedNote(
    val id: String,
    val sourceId: String,
    val title: String,
    val category: String,
    val favorite: Boolean,
    val markdown: String,
    val createdAt: String,
    val updatedAt: String,
)

internal class ExportedAttachment(
    val id: String,
    val displayName: String,
    val mediaType: String,
    /** The Markdown target exactly as the canonical body references it. */
    val target: String,
    val open: () -> InputStream?,
)

internal class IncompleteExportException(message: String) : Exception(message)

internal fun noteExportFile(title: String, hasAttachments: Boolean): NoteExportFile {
    val base = safeExportName(title)
    return if (hasAttachments) NoteExportFile("$base.zip", "application/zip", archive = true)
    else NoteExportFile("$base.md", "text/markdown", archive = false)
}

internal fun safeExportName(title: String): String {
    val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
    val points = cleaned.codePoints().toArray()
    val bounded = if (points.size <= 80) cleaned else String(points, 0, 80).trim()
    return bounded.ifBlank { "Note" }
}

/** The canonical Markdown is written byte for byte as UTF-8; nothing is re-rendered. */
internal fun writeNoteMarkdown(output: OutputStream, note: ExportedNote) {
    output.write(note.markdown.toByteArray(Charsets.UTF_8))
    output.flush()
}

/**
 * Writes the canonical Markdown and every managed attachment so each relative target
 * still resolves inside the archive. Missing attachment bytes fail the export instead of
 * silently producing a partial copy.
 */
internal fun writeNoteArchive(output: OutputStream, note: ExportedNote, attachments: List<ExportedAttachment>) {
    val layout = noteArchiveLayout(note, attachments.map(ExportedAttachment::target))
    val zip = ZipOutputStream(output)
    zip.putNextEntry(ZipEntry(layout.notePath))
    zip.write(note.markdown.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
    attachments.forEachIndexed { index, attachment ->
        val path = layout.attachmentPaths[index]
            ?: throw IncompleteExportException("Attachment ${attachment.id} has no portable location")
        val content = attachment.open() ?: throw IncompleteExportException("Attachment ${attachment.id} is missing")
        zip.putNextEntry(ZipEntry(path))
        content.use { it.copyTo(zip) }
        zip.closeEntry()
    }
    zip.putNextEntry(ZipEntry(ExportManifestName))
    zip.write(exportManifest(note, layout, attachments).toByteArray(Charsets.UTF_8))
    zip.closeEntry()
    zip.finish()
    output.flush()
}

internal data class NoteArchiveLayout(val notePath: String, val attachmentPaths: List<String?>)

internal fun noteArchiveLayout(note: ExportedNote, targets: List<String>): NoteArchiveLayout {
    // A managed target climbs from the Note's folder to the attachment vault at the Source root.
    val depth = targets.maxOfOrNull { target -> target.split('/').takeWhile { it == ".." }.size } ?: 0
    val categorySegments = note.category.split('/').map(::safeExportName).filter(String::isNotBlank)
    val folders = if (categorySegments.size == depth) categorySegments else List(depth) { "Note" }
    val noteFolder = folders.joinToString("/")
    val notePath = listOf(noteFolder, safeExportName(note.title) + ".md").filter(String::isNotEmpty).joinToString("/")
    return NoteArchiveLayout(notePath, targets.map { resolveInside(folders, it) })
}

private fun resolveInside(folder: List<String>, target: String): String? {
    if (target.isBlank() || target.startsWith('/') || "://" in target || '\\' in target) return null
    val segments = folder.toMutableList()
    for (segment in target.split('/')) when (segment) {
        "", "." -> Unit
        ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
        else -> segments += segment
    }
    return segments.joinToString("/").takeIf { it.isNotEmpty() && it != ExportManifestName }
}

private fun exportManifest(note: ExportedNote, layout: NoteArchiveLayout, attachments: List<ExportedAttachment>): String = buildString {
    append("{\n")
    append("  \"format\": \"kgs-notes-note-export\",\n  \"version\": 1,\n")
    append("  \"note\": {")
    append("\"id\": ").append(json(note.id)).append(", ")
    append("\"source\": ").append(json(note.sourceId)).append(", ")
    append("\"title\": ").append(json(note.title)).append(", ")
    append("\"category\": ").append(json(note.category)).append(", ")
    append("\"favorite\": ").append(note.favorite).append(", ")
    append("\"created\": ").append(json(note.createdAt)).append(", ")
    append("\"updated\": ").append(json(note.updatedAt)).append(", ")
    append("\"path\": ").append(json(layout.notePath)).append("},\n")
    append("  \"attachments\": [")
    attachments.forEachIndexed { index, attachment ->
        if (index > 0) append(", ")
        append("{\"id\": ").append(json(attachment.id))
        append(", \"name\": ").append(json(attachment.displayName))
        append(", \"mediaType\": ").append(json(attachment.mediaType))
        append(", \"target\": ").append(json(attachment.target))
        append(", \"path\": ").append(json(layout.attachmentPaths[index].orEmpty())).append('}')
    }
    append("]\n}\n")
}

private fun json(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
    append('"')
}

internal const val ExportManifestName = "kgs-note.json"
