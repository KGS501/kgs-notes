package com.kgs.notes

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.engine.NoteId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.UUID

data class ExternalFileUiState(
    val sessionKey: String = "",
    val name: String = "",
    val text: String = "",
    val mode: EditorMode = EditorMode.RICH,
    val writable: Boolean = false,
    val loading: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val copying: Boolean = false,
    val closing: Boolean = false,
    val error: String? = null,
    val closeBlocked: Boolean = false,
)

/** A URI editor, deliberately outside NotesEngine and every Source's storage and sync. */
internal class ExternalFileSession(application: Application, private val scope: CoroutineScope) {
    private val files = ExternalTextFiles(application)
    private val checkpoint = AtomicFile(application.noBackupFilesDir.resolve("external-file-session.json"))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<ExternalFileUiState?>(null)
    val state: StateFlow<ExternalFileUiState?> = mutableState.asStateFlow()
    private var request: ExternalFileRequest? = null
    private var baseline = byteArrayOf()
    private var baselineText = ""
    private var format: ExternalTextFormat? = null
    var copyId: NoteId? = null
        private set
    private var copyText: String? = null
    private var copyTitle: String? = null
    private var saveGeneration = 0
    private var unreadableRecoveryPending = false
    private var sessionKey = UUID.randomUUID().toString()

    init {
        // Restore only temporary editing state; it never becomes a library Note implicitly.
        scope.launch {
            mutex.withLock {
                try { restoreCheckpoint() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    val preserved = preserveUnreadableCheckpoint()
                    unreadableRecoveryPending = !preserved
                    mutableState.value = ExternalFileUiState(sessionKey = sessionKey, error = if (preserved) {
                        "The previous file preview couldn't be restored. Its recovery copy was kept; open the original file again."
                    } else {
                        "The previous file preview couldn't be restored. Its recovery file was kept. Free up storage before opening another file."
                    })
                }
            }
        }
    }

    suspend fun open(next: ExternalFileRequest, preferredMode: EditorMode, beforeOpen: suspend () -> Unit = {}) = mutex.withLock {
        beforeOpen()
        if (request?.uri == next.uri && mutableState.value?.name?.isNotBlank() == true) {
            try {
                val opened = withContext(Dispatchers.IO) { files.open(next) }
                val current = mutableState.value!!
                request = next
                if (!current.dirty) {
                    baseline = opened.bytes
                    baselineText = opened.text
                    format = opened.format
                }
                mutableState.value = current.copy(text = if (current.dirty) current.text else opened.text, writable = opened.writable)
                persistSafelyLocked()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { mutableState.update { it?.copy(error = problem.safeMessage(opening = true)) } }
            return@withLock
        }
        mutableState.update { it?.copy(closing = true) }
        if (mutableState.value != null && !flushLocked()) {
            mutableState.update { it?.copy(closeBlocked = true, closing = false) }
            return@withLock
        }
        if (!clearLocked()) return@withLock
        request = next
        mutableState.value = ExternalFileUiState(sessionKey = sessionKey, loading = true)
        try {
            val opened = withContext(Dispatchers.IO) { files.open(next) }
            baseline = opened.bytes
            baselineText = opened.text
            format = opened.format
            mutableState.value = ExternalFileUiState(
                sessionKey = sessionKey,
                name = opened.name,
                text = opened.text,
                writable = opened.writable,
                // Plain text keeps its literal appearance. Large files use the native editor.
                mode = if (opened.plainText || opened.text.length > 200_000) EditorMode.SOURCE else preferredMode,
            )
            persistSafelyLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            mutableState.value = ExternalFileUiState(sessionKey = sessionKey, error = problem.safeMessage(opening = true))
        }
    }

    fun updateText(expectedSessionKey: String, text: String) {
        val current = mutableState.value ?: return
        if (current.sessionKey != expectedSessionKey) return
        if (!current.writable || current.loading || current.copying || current.closing || current.text == text) return
        mutableState.value = current.copy(text = text, dirty = text != baselineText)
        val generation = ++saveGeneration
        // Coalesce recovery checkpoints independently of the longer file-write debounce.
        scope.launch {
            delay(80)
            mutex.withLock { if (generation == saveGeneration) persistSafelyLocked() }
            delay(370)
            mutex.withLock { if (generation == saveGeneration) saveLocked() }
        }
    }

    fun setMode(expectedSessionKey: String, mode: EditorMode) {
        if (mutableState.value?.sessionKey != expectedSessionKey) return
        mutableState.update { it?.copy(mode = mode) }
        scope.launch { mutex.withLock { persistSafelyLocked() } }
    }

    fun flush(expectedSessionKey: String? = null) {
        scope.launch { mutex.withLock {
            if (expectedSessionKey == null || mutableState.value?.sessionKey == expectedSessionKey) flushLocked()
        } }
    }

    fun dismissCloseWarning(expectedSessionKey: String) {
        mutableState.update { if (it?.sessionKey == expectedSessionKey) it.copy(closeBlocked = false) else it }
    }

    suspend fun enableEditing(expectedSessionKey: String, next: ExternalFileRequest) = mutex.withLock {
        val current = mutableState.value ?: return@withLock
        if (current.sessionKey != expectedSessionKey) return@withLock
        if (current.copying || current.loading) return@withLock
        try {
            val opened = withContext(Dispatchers.IO) { files.open(next) }
            if (!opened.bytes.contentEquals(baseline)) {
                throw ExternalFileProblem("Choose the original file with the same contents. Your preview was kept unchanged.")
            }
            if (!opened.writable) throw ExternalFileProblem("This location only allows reading. You can save a copy to KGS Notes instead.")
            val previousUri = request?.uri
            request = next
            mutableState.update { it?.copy(name = opened.name, writable = true, error = null) }
            persistLocked()
            if (previousUri != null && previousUri != next.uri) withContext(Dispatchers.IO) { files.release(previousUri) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { mutableState.update { it?.copy(error = problem.safeMessage(opening = true)) } }
        finally {
            if (request?.uri != next.uri) withContext(NonCancellable + Dispatchers.IO) { files.release(next.uri) }
            else persistSafelyLocked()
        }
    }

    suspend fun close(expectedSessionKey: String, discard: Boolean = false): Boolean = mutex.withLock {
        if (mutableState.value?.sessionKey != expectedSessionKey) return@withLock false
        if (mutableState.value?.copying == true) return@withLock false
        mutableState.update { it?.copy(closing = true) }
        if (!discard && !flushLocked()) {
            mutableState.update { it?.copy(closeBlocked = true, closing = false) }
            return@withLock false
        }
        clearLocked()
    }

    /** Serialize copying with file writes; the original remains an independent file. */
    suspend fun copyIntoNotes(expectedSessionKey: String, copy: suspend (NoteId?, String?, String?, String, String, EditorMode, suspend (NoteId, String?) -> Unit) -> Unit) = mutex.withLock {
        val current = mutableState.value ?: return@withLock
        if (current.sessionKey != expectedSessionKey) return@withLock
        if (current.loading || current.copying || current.name.isBlank()) return@withLock
        mutableState.update { it?.copy(copying = true, closeBlocked = false) }
        try {
            // A failed original-file write must not prevent an explicit rescue copy.
            saveLocked()
            persistLocked()
            copy(copyId, copyText, copyTitle, current.name.substringBeforeLast('.'), current.text, current.mode) { id, title ->
                copyId = id
                copyText = current.text
                copyTitle = title
                persistLocked()
            }
            check(clearLocked())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it?.copy(copying = false, error = "The copy couldn't be completed. Your text is kept here. Try again or choose another Source.") }
            persistSafelyLocked()
        }
    }

    private suspend fun saveLocked(): Boolean {
        val current = mutableState.value ?: return true
        if (!current.dirty) return true
        val target = request ?: return false
        val codec = format ?: return false
        mutableState.update { it?.copy(saving = true) }
        return try {
            persistLocked()
            if (!current.writable) throw ExternalFileProblem("The file app no longer allows editing. Save a copy to KGS Notes to keep your edits.")
            val bytes = withContext(Dispatchers.IO) { codec.encode(current.text) }
            if (bytes.size > MAX_EXTERNAL_FILE_BYTES) throw ExternalFileProblem("The edited file is too large to save safely. Your text is kept here; save a copy to KGS Notes.")
            // Do not cancel in the middle of replacing a provider's contents.
            withContext(NonCancellable + Dispatchers.IO) { files.write(target.uri, baseline, bytes) }
            baseline = bytes
            baselineText = current.text
            mutableState.update { latest -> latest?.copy(dirty = latest.text != current.text, saving = false, error = null) }
            persistLocked()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            mutableState.update { it?.copy(saving = false, error = problem.safeMessage(opening = false)) }
            persistSafelyLocked()
            false
        }
    }

    private suspend fun flushLocked(): Boolean {
        while (mutableState.value?.dirty == true) if (!saveLocked()) return false
        return true
    }

    private suspend fun persistSafelyLocked() {
        try {
            persistLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it?.copy(error = "Temporary recovery couldn't be saved. Keep this preview open until your changes are saved.") }
        }
    }

    private suspend fun persistLocked() {
        val current = mutableState.value ?: return
        val target = request ?: return
        if (current.loading || current.name.isBlank()) return
        val savedBaseline = baseline
        val savedCopyId = copyId
        val savedCopyText = copyText
        val savedCopyTitle = copyTitle
        val ownedGrants = files.ownedGrantFlags(target.uri)
        withContext(NonCancellable + Dispatchers.IO) {
            val value = JSONObject()
                .put("version", 1).put("uri", target.uri.toString())
                .put("sessionKey", sessionKey)
                .put("name", current.name).put("text", current.text).put("mode", current.mode.name)
                .put("baseline", Base64.encodeToString(savedBaseline, Base64.NO_WRAP))
                .put("copyId", savedCopyId?.value ?: JSONObject.NULL)
                .put("copyText", savedCopyText ?: JSONObject.NULL)
                .put("copyTitle", savedCopyTitle ?: JSONObject.NULL)
                .put("ownedGrants", ownedGrants)
            val stream = checkpoint.startWrite()
            try {
                stream.write(value.toString().toByteArray(Charsets.UTF_8))
                checkpoint.finishWrite(stream)
            } catch (failure: Exception) {
                checkpoint.failWrite(stream)
                throw failure
            }
        }
    }

    private suspend fun restoreCheckpoint() {
        val saved = withContext(Dispatchers.IO) {
            // openRead also restores AtomicFile's backup after an interrupted write.
            val bytes = try {
                checkpoint.openRead().use { it.readBytes() }
            } catch (missing: FileNotFoundException) {
                if (checkpoint.baseFile.exists()) throw missing else null
            }
            bytes?.let { JSONObject(it.toString(Charsets.UTF_8)) }
        } ?: return
        if (saved.optInt("version") != 1) throw ExternalFileProblem("Unsupported recovery format")
        val uri = Uri.parse(saved.getString("uri"))
        if (uri.scheme != "content") throw ExternalFileProblem("Unsupported recovery location")
        val bytes = Base64.decode(saved.getString("baseline"), Base64.NO_WRAP)
        val (original, codec) = ExternalTextFormat.decode(bytes)
        val text = saved.getString("text")
        sessionKey = saved.optString("sessionKey").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        request = ExternalFileRequest(uri, null, 0)
        files.restoreOwnedGrants(uri, saved.optInt("ownedGrants", 0))
        baseline = bytes
        baselineText = original
        format = codec
        copyId = saved.optString("copyId").takeIf { it.isNotBlank() && it != "null" }?.let(::NoteId)
        copyText = if (saved.isNull("copyText")) null else saved.getString("copyText")
        copyTitle = if (saved.isNull("copyTitle")) null else saved.getString("copyTitle")
        val reopened = withContext(Dispatchers.IO) { runCatching { files.open(request!!) }.getOrNull() }
        // Unsaved text wins over reloading; a revision check precedes any subsequent write.
        if (text == original && reopened != null) {
            baseline = reopened.bytes
            baselineText = reopened.text
            format = reopened.format
        }
        mutableState.value = ExternalFileUiState(
            sessionKey = sessionKey,
            name = saved.getString("name"), text = if (text == original && reopened != null) reopened.text else text,
            mode = EditorMode.entries.firstOrNull { it.name == saved.optString("mode") } ?: EditorMode.SOURCE,
            writable = reopened?.writable == true, dirty = text != original,
            error = if (reopened == null) "The original file is unavailable. Your preview is kept here; save a copy to KGS Notes." else null,
        )
    }

    private suspend fun preserveUnreadableCheckpoint(): Boolean = try {
        withContext(NonCancellable + Dispatchers.IO) {
            val directory = checkpoint.baseFile.parentFile!!.resolve("external-file-recovery")
            check(directory.isDirectory || directory.mkdirs())
            val preserved = AtomicFile(directory.resolve("${UUID.randomUUID()}.json"))
            val output = preserved.startWrite()
            try {
                checkpoint.openRead().use { input -> input.copyTo(output) }
                preserved.finishWrite(output)
            } catch (failure: Exception) {
                preserved.failWrite(output)
                throw failure
            }
            // Delete only after the recovery envelope has a durable preserved copy.
            checkpoint.delete()
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun clearLocked(): Boolean {
        if (unreadableRecoveryPending) {
            if (!preserveUnreadableCheckpoint()) {
                mutableState.update { it?.copy(closing = false,
                    error = "The previous recovery file is still needed. Free up storage before opening another file.") }
                return false
            }
            unreadableRecoveryPending = false
        }
        val previousUri = request?.uri
        withContext(NonCancellable + Dispatchers.IO) {
            checkpoint.delete()
            previousUri?.let(files::release)
        }
        ++saveGeneration
        request = null
        baseline = byteArrayOf()
        baselineText = ""
        format = null
        copyId = null
        copyText = null
        copyTitle = null
        sessionKey = UUID.randomUUID().toString()
        mutableState.value = null
        return true
    }

    private fun Exception.safeMessage(opening: Boolean): String = (this as? ExternalFileProblem)?.explanation
        ?: if (opening) "This file couldn't be opened. Check that the file app grants access, then try again."
        else "The original file couldn't be saved. Your edits are kept here; retry or save a copy to KGS Notes."
}
