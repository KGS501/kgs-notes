package com.kgs.notes

import com.kgs.notes.design.R.string as UiR

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
internal class ExternalFileSession(private val application: Application, private val scope: CoroutineScope) {
    private val strings get() = application.localizedResources()
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
    /**
     * Writes that failed after the provider may have truncated. Until a later write is confirmed,
     * the provider is not a safe copy, even when the editor text equals the original again.
     */
    private var interruptedWrites: List<ByteArray> = emptyList()
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
                        strings.getString(UiR.ui_the_previous_file_preview_couldn_t_be_restored_its_recovery_copy_was_kept_open_the_or)
                    } else {
                        strings.getString(UiR.ui_the_previous_file_preview_couldn_t_be_restored_its_recovery_file_was_kept_free_up_sto)
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
        mutableState.value = current.copy(text = text, dirty = isDirty(text))
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
            if (!providerHoldsOwnContents(opened.bytes, baseline, interruptedWrites)) {
                throw ExternalFileProblem(UiR.ui_choose_the_original_file_with_the_same_contents_your_preview_was_kept_unchanged)
            }
            if (!opened.writable) throw ExternalFileProblem(UiR.ui_this_location_only_allows_reading_you_can_save_a_copy_to_kgs_notes_instead)
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
            mutableState.update { it?.copy(copying = false, error = strings.getString(UiR.ui_the_copy_couldn_t_be_completed_your_text_is_kept_here_try_again_or_choose_another_sou)) }
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
            if (!current.writable) throw ExternalFileProblem(UiR.ui_the_file_app_no_longer_allows_editing_save_a_copy_to_kgs_notes_to_keep_your_edits)
            val bytes = withContext(Dispatchers.IO) { codec.encode(current.text) }
            if (bytes.size > MAX_EXTERNAL_FILE_BYTES) throw ExternalFileProblem(UiR.ui_the_edited_file_is_too_large_to_save_safely_your_text_is_kept_here_save_a_copy_to_kgs)
            // Do not cancel in the middle of replacing a provider's contents.
            val interrupted = interruptedWrites
            withContext(NonCancellable + Dispatchers.IO) { files.write(target.uri, baseline, bytes, interrupted) }
            baseline = bytes
            baselineText = current.text
            interruptedWrites = emptyList()
            mutableState.update { latest -> latest?.copy(dirty = isDirty(latest.text), saving = false, error = null) }
            persistLocked()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            if (problem is UncertainExternalWrite) {
                // Two attempts cover a retry that failed before reading back what the first one left.
                interruptedWrites = (interruptedWrites + problem.attempted).takeLast(2)
            }
            mutableState.update { latest -> latest?.let { it.copy(saving = false, dirty = isDirty(it.text), error = problem.safeMessage(opening = false)) } }
            persistSafelyLocked()
            false
        }
    }

    private fun isDirty(text: String) = text != baselineText || interruptedWrites.isNotEmpty()

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
            mutableState.update { it?.copy(error = strings.getString(UiR.ui_temporary_recovery_couldn_t_be_saved_keep_this_preview_open_until_your_changes_are_sa)) }
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
        val savedInterrupted = interruptedWrites
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
                .put("interruptedWrites", org.json.JSONArray(savedInterrupted.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
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
        if (saved.optInt("version") != 1) throw ExternalFileProblem(UiR.ui_unsupported_recovery_format)
        val uri = Uri.parse(saved.getString("uri"))
        if (uri.scheme != "content") throw ExternalFileProblem(UiR.ui_unsupported_recovery_location)
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
        interruptedWrites = saved.optJSONArray("interruptedWrites")?.let { array ->
            List(array.length()) { Base64.decode(array.getString(it), Base64.NO_WRAP) }
        }.orEmpty()
        val reopened = withContext(Dispatchers.IO) { runCatching { files.open(request!!) }.getOrNull() }
        // Unsaved text wins over reloading; a revision check precedes any subsequent write.
        // A provider damaged by an interrupted write is never adopted as the new original.
        val reloadable = text == original && reopened != null && interruptedWrites.isEmpty()
        if (reloadable) {
            baseline = reopened.bytes
            baselineText = reopened.text
            format = reopened.format
        }
        mutableState.value = ExternalFileUiState(
            sessionKey = sessionKey,
            name = saved.getString("name"), text = if (reloadable) reopened.text else text,
            mode = EditorMode.entries.firstOrNull { it.name == saved.optString("mode") } ?: EditorMode.SOURCE,
            writable = reopened?.writable == true, dirty = !reloadable && isDirty(text),
            error = when {
                reopened == null -> strings.getString(UiR.ui_the_original_file_is_unavailable_your_preview_is_kept_here_save_a_copy_to_kgs_notes)
                interruptedWrites.isNotEmpty() -> strings.getString(UiR.ui_the_file_app_may_have_kept_only_part_of_this_file_your_full_text_is_kept_here)
                else -> null
            },
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
                    error = strings.getString(UiR.ui_the_previous_recovery_file_is_still_needed_free_up_storage_before_opening_another_fil)) }
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
        interruptedWrites = emptyList()
        copyId = null
        copyText = null
        copyTitle = null
        sessionKey = UUID.randomUUID().toString()
        mutableState.value = null
        return true
    }

    private fun Exception.safeMessage(opening: Boolean): String = (this as? ExternalFileProblem)?.let { strings.getString(it.explanationRes) }
        ?: if (opening) strings.getString(UiR.ui_this_file_couldn_t_be_opened_check_that_the_file_app_grants_access_then_try_again)
        else strings.getString(UiR.ui_the_original_file_couldn_t_be_saved_your_edits_are_kept_here_retry_or_save_a_copy_to)
}
