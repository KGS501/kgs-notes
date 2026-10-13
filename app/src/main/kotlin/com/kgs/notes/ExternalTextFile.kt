package com.kgs.notes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Android's file grant is the authority; a filename is never resolved to a filesystem path. */
internal data class ExternalFileRequest(val uri: Uri, val mimeType: String?, val flags: Int) {
    companion object {
        fun from(intent: Intent): ExternalFileRequest? {
            if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_EDIT) return null
            val uri = intent.data ?: return null
            // Never let an exported Activity read its own private files through a file:// intent.
            if (uri.scheme != "content") return null
            return ExternalFileRequest(uri, intent.type, intent.flags)
        }
    }
}

internal class ExternalFileProblem(val explanation: String) : Exception(explanation)

/** Encoding and line endings belong to the original file, not the Markdown renderer. */
internal data class ExternalTextFormat(
    val charsetName: String,
    val bom: ByteArray,
    val lineEnding: String,
) {
    fun encode(text: String): ByteArray {
        val encoder = Charset.forName(charsetName).newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val buffer = encoder.encode(java.nio.CharBuffer.wrap(normalized.replace("\n", lineEnding)))
        return bom + ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        fun decode(bytes: ByteArray): Pair<String, ExternalTextFormat> {
            val (charset, prefix) = when {
                bytes.startsWithBytes(0xef, 0xbb, 0xbf) -> "UTF-8" to 3
                bytes.startsWithBytes(0xff, 0xfe) -> "UTF-16LE" to 2
                bytes.startsWithBytes(0xfe, 0xff) -> "UTF-16BE" to 2
                else -> "UTF-8" to 0
            }
            val text = try {
                Charset.forName(charset).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, prefix, bytes.size - prefix)).toString()
            } catch (_: Exception) {
                throw ExternalFileProblem("This file isn't UTF-8 or Unicode text. Its contents were left unchanged.")
            }
            if ('\u0000' in text) throw ExternalFileProblem("This file doesn't contain supported text.")
            val lineEnding = when {
                "\r\n" in text -> "\r\n"
                '\r' in text && '\n' !in text -> "\r"
                else -> "\n"
            }
            return text.replace("\r\n", "\n").replace('\r', '\n') to
                ExternalTextFormat(charset, bytes.copyOfRange(0, prefix), lineEnding)
        }

        private fun ByteArray.startsWithBytes(vararg values: Int): Boolean =
            size >= values.size && values.indices.all { this[it].toInt() and 0xff == values[it] }
    }
}

internal data class OpenedExternalTextFile(
    val name: String,
    val bytes: ByteArray,
    val text: String,
    val format: ExternalTextFormat,
    val writable: Boolean,
    val plainText: Boolean,
)

internal class ExternalTextFiles(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val ownedGrants = java.util.concurrent.ConcurrentHashMap<Uri, Int>()

    fun open(request: ExternalFileRequest): OpenedExternalTextFile {
        if (request.uri.scheme != "content") throw ExternalFileProblem("Open a Markdown or text file from a file app.")
        if (request.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            val grants = request.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val existing = runCatching { persistedFlags(request.uri) }.getOrNull()
            // Only acquire ownership when the previous permission state is known.
            if (grants != 0 && existing != null) runCatching {
                resolver.takePersistableUriPermission(request.uri, grants)
                ownedGrants[request.uri] = (ownedGrants[request.uri] ?: 0) or (grants and existing.inv())
            }
        }
        val name = (runCatching {
            resolver.query(request.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() ?: request.uri.lastPathSegment?.takeIf {
            it.endsWith(".md", true) || it.endsWith(".txt", true)
        })?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
        val mime = (request.mimeType ?: runCatching { resolver.getType(request.uri) }.getOrNull())
            ?.substringBefore(';')?.lowercase()
        val extension = name?.substringAfterLast('.', "")?.lowercase()
        val namedText = extension == "md" || extension == "txt"
        val knownText = mime in setOf("text/plain", "text/markdown", "text/x-markdown", "application/markdown", "application/x-markdown")
        if (!namedText && !knownText) {
            throw ExternalFileProblem("KGS Notes can open .md and .txt files.")
        }
        val bytes = read(request.uri)
        val (text, format) = ExternalTextFormat.decode(bytes)
        val writeGranted = context.checkUriPermission(
            request.uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        val providerWritable = if (DocumentsContract.isDocumentUri(context, request.uri)) runCatching {
            resolver.query(request.uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (column >= 0 && cursor.moveToFirst()) cursor.getInt(column) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0 else true
            } ?: true
        }.getOrDefault(false) else true
        val displayName = name?.let {
            val points = it.codePoints().toArray()
            if (points.size <= 160) it else String(points, 0, 150) + "…." + extension
        }
            ?: if (mime == "text/plain") "Note.txt" else "Note.md"
        return OpenedExternalTextFile(displayName, bytes, text, format, writeGranted && providerWritable,
            plainText = extension == "txt" || (extension != "md" && mime == "text/plain"))
    }

    fun ownedGrantFlags(uri: Uri): Int = ownedGrants[uri] ?: 0

    fun restoreOwnedGrants(uri: Uri, flags: Int) {
        ownedGrants[uri] = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun release(uri: Uri) {
        val flags = ownedGrants.remove(uri) ?: 0
        if (flags != 0) runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun persistedFlags(uri: Uri): Int {
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return 0
        return (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    }

    fun read(uri: Uri): ByteArray = resolver.openInputStream(uri)?.use { input ->
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > MAX_EXTERNAL_FILE_BYTES) {
                throw ExternalFileProblem("This file is too large to edit safely on this device. It was left unchanged.")
            }
            result.write(buffer, 0, count)
        }
        result.toByteArray()
    } ?: throw ExternalFileProblem("The file app couldn't open this file. Try opening it again.")

    fun write(uri: Uri, expected: ByteArray, bytes: ByteArray) {
        if (!read(uri).contentEquals(expected)) {
            throw ExternalFileProblem("The original file changed elsewhere. Your edits are kept here; save a copy to KGS Notes to keep both versions.")
        }
        // 'w' is provider-dependent; 'wt' explicitly truncates when replacing a longer file.
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw ExternalFileProblem("The file app couldn't save this file. Your edits are kept here.")
        if (!read(uri).contentEquals(bytes)) {
            throw ExternalFileProblem("The file app didn't confirm the saved contents. Your edits are kept here; save a copy to KGS Notes.")
        }
    }
}

internal const val MAX_EXTERNAL_FILE_BYTES = 4 * 1024 * 1024
