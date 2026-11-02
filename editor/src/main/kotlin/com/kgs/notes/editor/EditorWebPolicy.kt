package com.kgs.notes.editor

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI

/**
 * Navigation and resource rules for the Rich editor WebView, kept free of
 * Android types so they are unit-testable on the JVM.
 *
 * The editor origin hosts the native bridge. Only the fixed bundled editor
 * document may ever be a top-level page there; Managed Attachments are served
 * solely as image subresources with a type proven by their own bytes and a
 * sandboxing CSP, so no attachment can become a document on the bridge origin.
 */
internal object EditorWebPolicy {
    const val ASSET_HOST = "appassets.androidplatform.net"
    const val ASSET_ORIGIN = "https://$ASSET_HOST"
    const val EDITOR_PATH = "/assets/kgs-editor/index.html"
    const val EDITOR_URL = "$ASSET_ORIGIN$EDITOR_PATH"

    /** Must equal the bundled index.html meta policy; frame-ancestors is header-only. */
    const val EDITOR_DOCUMENT_CSP = "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; " +
        "style-src-attr 'none'; script-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; " +
        "base-uri 'none'; form-action 'none'"

    /** Attachment bytes can never run script or act as a document, even if opened directly. */
    const val ATTACHMENT_CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; sandbox"

    const val SVG = "image/svg+xml"

    /** The image formats the editor renders inline (editor.ts IMAGE_EXTENSIONS). */
    val IMAGE_TYPES: Map<String, String> = mapOf(
        "avif" to "image/avif",
        "gif" to "image/gif",
        "jpeg" to "image/jpeg",
        "jpg" to "image/jpeg",
        "png" to "image/png",
        "svg" to SVG,
        "webp" to "image/webp",
    )

    private const val ATTACHMENT_DIRECTORY = ".kgs-notes-attachments"
    private val ATTACHMENT_ID = Regex("[a-f0-9-]{36}")
    const val SNIFF_LENGTH = 1024

    data class AttachmentRequest(val id: String, val declaredType: String)

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    /** Exactly the bundled editor document; queries and other assets are refused. */
    fun isEditorDocument(url: String?): Boolean {
        val uri = parse(url ?: return false) ?: return false
        return uri.scheme == "https" && uri.rawAuthority == ASSET_HOST && uri.rawPath == EDITOR_PATH &&
            uri.rawQuery == null && uri.rawUserInfo == null
    }

    fun allowsTopLevelNavigation(url: String?): Boolean = isEditorDocument(url)

    /** A main-frame request is permitted only for the editor document itself. */
    fun allowsMainFrameRequest(url: String?): Boolean = isEditorDocument(url)

    fun attachmentRequest(url: String?): AttachmentRequest? {
        val uri = parse(url ?: return null) ?: return null
        if (uri.scheme != "https" || uri.rawAuthority != ASSET_HOST) return null
        val segments = (uri.rawPath ?: return null).split('/').filter { it.isNotEmpty() }
        val vaultIndex = segments.indexOf(ATTACHMENT_DIRECTORY)
        if (vaultIndex < 0 || vaultIndex + 2 >= segments.size) return null
        val storageName = segments[vaultIndex + 2]
        val id = storageName.substringBefore('.')
        if (!id.matches(ATTACHMENT_ID)) return null
        val extension = storageName.substringAfterLast('.', "").lowercase()
        val declaredType = IMAGE_TYPES[extension] ?: return null
        return AttachmentRequest(id, declaredType)
    }

    /** The image type proven by the file's own leading bytes, or null for anything else. */
    fun sniffImageType(head: ByteArray, length: Int = head.size): String? {
        fun at(offset: Int, vararg bytes: Int) =
            length >= offset + bytes.size && bytes.indices.all { head[offset + it].toInt() and 0xFF == bytes[it] }
        fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(4, "ftyp") && isAvif(head, length) -> "image/avif"
            isSvg(head, length) -> SVG
            else -> null
        }
    }

    private fun isAvif(head: ByteArray, length: Int): Boolean {
        val boxSize = if (length >= 4) ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF) else 0
        val end = minOf(length, boxSize.takeIf { it in 16..256 } ?: 0)
        // Major brand at 8, compatible brands from 16 in steps of four.
        return (listOf(8) + (16 until end step 4)).any { offset ->
            offset + 4 <= end && String(head, offset, 4, Charsets.US_ASCII).let { it == "avif" || it == "avis" }
        }
    }

    private fun isSvg(head: ByteArray, length: Int): Boolean {
        var text = String(head, 0, length, Charsets.UTF_8).removePrefix("﻿").trimStart()
        // Skip an XML declaration, comments and a doctype before the root element.
        while (true) {
            text = when {
                text.startsWith("<?xml") -> text.substringAfter("?>", "").trimStart()
                text.startsWith("<!--") -> text.substringAfter("-->", "").trimStart()
                text.startsWith("<!DOCTYPE", ignoreCase = true) -> text.substringAfter(">", "").trimStart()
                else -> break
            }
        }
        return Regex("^<svg[\\s>/]").containsMatchIn(text)
    }

    /**
     * Serve the content's real image type. SVG is only accepted when it was
     * declared as SVG (stored type when the app supplies one, else the
     * extension), so a raster-named file can never be reinterpreted as markup.
     */
    fun resolveAttachmentType(declaredType: String, storedType: String?, sniffedType: String?): String? {
        val declared = storedType?.substringBefore(';')?.trim()?.lowercase() ?: declaredType
        if (declared !in IMAGE_TYPES.values) return null
        val sniffed = sniffedType ?: return null
        if ((sniffed == SVG) != (declared == SVG)) return null
        return sniffed
    }

    fun attachmentHeaders(): Map<String, String> = mapOf(
        "Content-Security-Policy" to ATTACHMENT_CSP,
        "X-Content-Type-Options" to "nosniff",
        "Cross-Origin-Resource-Policy" to "same-origin",
        "Content-Disposition" to "inline",
        "Cache-Control" to "no-store",
    )

    fun editorDocumentHeaders(): Map<String, String> = mapOf(
        "Content-Security-Policy" to "$EDITOR_DOCUMENT_CSP; frame-ancestors 'none'",
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to "no-store",
    )

    fun deniedHeaders(): Map<String, String> = mapOf(
        "Content-Security-Policy" to "default-src 'none'; sandbox",
        "X-Content-Type-Options" to "nosniff",
    )

    /** A typed attachment stream, or null when the bytes are not an accepted image. */
    class TypedAttachment(val mediaType: String, val data: InputStream)

    fun typedAttachment(request: AttachmentRequest, storedType: String?, content: InputStream): TypedAttachment? {
        val buffered = BufferedInputStream(content, SNIFF_LENGTH * 2)
        return try {
            buffered.mark(SNIFF_LENGTH)
            val head = ByteArray(SNIFF_LENGTH)
            var length = 0
            while (length < head.size) {
                val read = buffered.read(head, length, head.size - length)
                if (read < 0) break
                length += read
            }
            buffered.reset()
            val type = resolveAttachmentType(request.declaredType, storedType, sniffImageType(head, length))
            if (type == null) { buffered.close(); null } else TypedAttachment(type, buffered)
        } catch (error: java.io.IOException) {
            runCatching { buffered.close() }
            null
        }
    }
}
