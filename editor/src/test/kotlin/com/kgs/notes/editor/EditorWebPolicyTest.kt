package com.kgs.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class EditorWebPolicyTest {
    private val id = "0123abcd-0123-4567-89ab-0123456789ab"
    private val attachment = "https://appassets.androidplatform.net/notes/.kgs-notes-attachments/note/$id.png"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
    private val svg = "<?xml version=\"1.0\"?>\n<!-- drawing -->\n<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x()</script></svg>"
        .toByteArray()

    @Test
    fun `only the fixed bundled editor document may be a top-level page`() {
        assertTrue(EditorWebPolicy.allowsTopLevelNavigation(EditorWebPolicy.EDITOR_URL))
        assertTrue(EditorWebPolicy.allowsTopLevelNavigation("${EditorWebPolicy.EDITOR_URL}#section"))
        listOf(
            attachment,
            "https://appassets.androidplatform.net/assets/licenses/editor-tiptap.txt",
            "https://appassets.androidplatform.net/assets/kgs-editor/assets/index.js",
            "${EditorWebPolicy.EDITOR_URL}?next=1",
            "https://appassets.androidplatform.net/assets/kgs-editor/../kgs-editor/index.html",
            "https://user@appassets.androidplatform.net/assets/kgs-editor/index.html",
            "https://appassets.androidplatform.net:444/assets/kgs-editor/index.html",
            "http://appassets.androidplatform.net/assets/kgs-editor/index.html",
            "https://example.org/assets/kgs-editor/index.html",
            "javascript:alert(1)",
            "file:///android_asset/kgs-editor/index.html",
            "not a url",
            null,
        ).forEach { url ->
            assertFalse("$url", EditorWebPolicy.allowsTopLevelNavigation(url))
            assertFalse("$url", EditorWebPolicy.allowsMainFrameRequest(url))
        }
    }

    @Test
    fun `managed attachment requests need a managed id and an inline image extension`() {
        assertEquals(EditorWebPolicy.AttachmentRequest(id, "image/png"), EditorWebPolicy.attachmentRequest(attachment))
        assertEquals("image/svg+xml", EditorWebPolicy.attachmentRequest(attachment.replace(".png", ".SVG"))?.declaredType)
        assertNull(EditorWebPolicy.attachmentRequest(attachment.replace(".png", ".html")))
        assertNull(EditorWebPolicy.attachmentRequest(attachment.replace(".png", ".pdf")))
        assertNull(EditorWebPolicy.attachmentRequest(attachment.replace(id, "not-an-id")))
        assertNull(EditorWebPolicy.attachmentRequest(attachment.replace("appassets.androidplatform.net", "example.org")))
    }

    @Test
    fun `served type comes from the attachment bytes and never turns raster names into markup`() {
        assertEquals("image/png", EditorWebPolicy.sniffImageType(png))
        assertEquals("image/jpeg", EditorWebPolicy.sniffImageType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", EditorWebPolicy.sniffImageType("GIF89a....".toByteArray()))
        assertEquals("image/webp", EditorWebPolicy.sniffImageType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("image/avif", EditorWebPolicy.sniffImageType(
            byteArrayOf(0, 0, 0, 0x1C) + "ftypavif\u0000\u0000\u0000\u0000avifmif1miaf".toByteArray()))
        assertEquals("image/svg+xml", EditorWebPolicy.sniffImageType(svg))
        assertNull(EditorWebPolicy.sniffImageType("<html><script>x()</script></html>".toByteArray()))
        assertNull(EditorWebPolicy.sniffImageType(byteArrayOf()))

        // A mislabelled raster still renders under its real type.
        assertEquals("image/png", EditorWebPolicy.resolveAttachmentType("image/jpeg", null, "image/png"))
        // Markup is accepted only when declared as SVG, and SVG names cannot carry other bytes.
        assertNull(EditorWebPolicy.resolveAttachmentType("image/png", null, "image/svg+xml"))
        assertNull(EditorWebPolicy.resolveAttachmentType("image/svg+xml", null, "image/png"))
        assertNull(EditorWebPolicy.resolveAttachmentType("image/png", null, null))
        // Stored metadata outranks the file name when the app provides it.
        assertNull(EditorWebPolicy.resolveAttachmentType("image/svg+xml", "image/png", "image/svg+xml"))
        assertNull(EditorWebPolicy.resolveAttachmentType("image/png", "text/html", "image/png"))
        assertEquals("image/png", EditorWebPolicy.resolveAttachmentType("image/svg+xml", "image/png; q=1", "image/png"))
    }

    @Test
    fun `typed attachments keep every byte and refuse unproven content`() {
        val request = EditorWebPolicy.AttachmentRequest(id, "image/png")
        val large = png + ByteArray(5000) { it.toByte() }
        val typed = EditorWebPolicy.typedAttachment(request, null, ByteArrayInputStream(large))!!
        assertEquals("image/png", typed.mediaType)
        assertTrue(large.contentEquals(typed.data.readBytes()))
        assertNull(EditorWebPolicy.typedAttachment(request, null, ByteArrayInputStream(svg)))
        assertNull(EditorWebPolicy.typedAttachment(request, null, ByteArrayInputStream("<html>".toByteArray())))
        val svgRequest = EditorWebPolicy.AttachmentRequest(id, "image/svg+xml")
        assertEquals("image/svg+xml", EditorWebPolicy.typedAttachment(svgRequest, null, ByteArrayInputStream(svg))?.mediaType)
    }

    @Test
    fun `attachment responses are sandboxed inert subresources`() {
        val headers = EditorWebPolicy.attachmentHeaders()
        val csp = headers.getValue("Content-Security-Policy")
        assertTrue(csp.split(';').map { it.trim() }.contains("sandbox"))
        assertTrue(csp.startsWith("default-src 'none'"))
        assertFalse(csp.contains("script-src"))
        assertEquals("nosniff", headers["X-Content-Type-Options"])
        assertEquals("same-origin", headers["Cross-Origin-Resource-Policy"])
        assertTrue(EditorWebPolicy.deniedHeaders().getValue("Content-Security-Policy").contains("sandbox"))
        assertTrue(EditorWebPolicy.editorDocumentHeaders().getValue("Content-Security-Policy").endsWith("frame-ancestors 'none'"))
    }

    @Test
    fun `editor document header policy matches the bundled meta policy`() {
        val html = File("src/main/assets/kgs-editor/index.html").readText()
        val meta = Regex("http-equiv=\"Content-Security-Policy\"\\s+content=\"([^\"]+)\"").find(html)!!.groupValues[1]
        assertEquals(meta, EditorWebPolicy.EDITOR_DOCUMENT_CSP)
    }
}
