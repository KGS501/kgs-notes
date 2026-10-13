package com.kgs.notes

import android.app.Application
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.kgs.notes.engine.ConnectedNotesEngine
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.Source
import com.kgs.notes.engine.SourceCapabilities
import com.kgs.notes.engine.SourceDescriptor
import com.kgs.notes.engine.SourceId
import com.kgs.notes.engine.SourceNote
import com.kgs.notes.engine.SourceNoteDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Real engine storage, controlled Android file and Source adapters, assertions through UI/engine seams. */
@SdkSuppress(minSdkVersion = 29) // ContentResolver.wrap is an Android test adapter introduced in API 29.
class ExternalFileFlowTest {
    @get:Rule val compose = createComposeRule()
    private val viewModels = ViewModelStore()

    @After fun closeViewModels() { compose.runOnIdle { viewModels.clear() } }

    @Test
    fun unchangedPreviewDoesNotWriteOrCreateANote() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "Milk\r\nEggs\r\n".toByteArray()
        val fixture = openFile(bytes)
        compose.onNodeWithTag("source-editor").assertIsNotFocused().assertTextContains("Milk\nEggs\n")
        compose.onNodeWithContentDescription("Close file").performClick()
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null }
        assertArrayEquals(bytes, fixture.provider.file.readBytes())
        assertEquals(0, fixture.provider.writes.get())
        assertTrue(fixture.engine.library.value.active.isEmpty())
    }

    @Test
    fun editingWritesBackWithOriginalEncodingAndLineEndingsWithoutImporting() {
        val bom = byteArrayOf(0xff.toByte(), 0xfe.toByte())
        val fixture = openFile(bom + "Long original text\r\nSecond line\r\n".toByteArray(Charsets.UTF_16LE))
        compose.onNodeWithTag("source-editor").performClick().performTextReplacement("Short\n")
        val expected = bom + "Short\r\n".toByteArray(Charsets.UTF_16LE)
        compose.waitUntil(5_000) { fixture.provider.file.readBytes().contentEquals(expected) }
        assertTrue(fixture.engine.library.value.active.isEmpty())
        compose.onNodeWithContentDescription("Close file").performClick()
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null }
        assertArrayEquals(expected, fixture.provider.file.readBytes())
    }

    @Test
    fun immediateCloseIncludesTheLastTextFieldEdit() {
        val fixture = openFile("Before".toByteArray())
        val setText = compose.onNodeWithTag("source-editor").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val close = compose.onNodeWithContentDescription("Close file").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { setText(AnnotatedString("Last keystrokes")); close() }
        compose.waitUntil(5_000) { fixture.previewCloses.get() == 1 }
        assertEquals("Last keystrokes", fixture.provider.file.readText())
        assertTrue(fixture.engine.library.value.active.isEmpty())
    }

    @Test
    fun immediateCopyIncludesTheLastTextFieldEdit() {
        val fixture = openFile("Before".toByteArray())
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        val setText = compose.onNodeWithTag("source-editor").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val save = compose.onNodeWithTag("single-choice-Local Source").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { setText(AnnotatedString("Last keystrokes")); save() }
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null && fixture.engine.library.value.active.size == 1 }
        assertEquals("Last keystrokes", runBlocking { fixture.engine.note(fixture.engine.library.value.active.single().id) }!!.markdown)
        assertEquals("Last keystrokes", fixture.provider.file.readText())
    }

    @Test
    fun changedOriginalIsNotOverwrittenAndUnsavedTextCanBeCopiedIntoLocalSource() {
        val fixture = openFile("Original".toByteArray())
        fixture.provider.file.writeText("Changed elsewhere")
        compose.onNodeWithTag("source-editor").performClick().performTextReplacement("My edits")
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile?.error != null }
        assertEquals("Changed elsewhere", fixture.provider.file.readText())
        compose.onNodeWithContentDescription("Close file").performClick()
        compose.onNodeWithText("Keep your changes").assertExists()
        compose.onNodeWithText("Save to KGS Notes").performClick()
        compose.onNodeWithTag("single-choice-Local Source").performClick()
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null && fixture.viewModel.state.value.selectedNote != null }
        val note = runBlocking { fixture.engine.note(fixture.engine.library.value.active.single().id) }!!
        assertEquals("Fixture", note.title)
        assertEquals("My edits", note.markdown)
        assertEquals(LocalSourceId, note.sourceId)
        assertEquals("Changed elsewhere", fixture.provider.file.readText())
    }

    @Test
    fun readOnlyPreviewCanBeCopiedToAServerWithoutMutatingOriginalFile() {
        val server = RecordingSource()
        val fixture = openFile("Original".toByteArray(), writable = false, source = server)
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        compose.onNodeWithTag("single-choice-Test Source").performClick()
        compose.waitUntil(5_000) { server.notes.value.size == 1 && fixture.viewModel.state.value.externalFile == null }
        assertEquals("Original", server.notes.value.single().markdown)
        assertEquals(server.descriptor.id, fixture.engine.library.value.active.single().sourceId)
        assertEquals(0, fixture.provider.writes.get())
        assertEquals("Original", fixture.provider.file.readText())
    }

    @Test
    fun readOnlyMarkdownRendersRichPreviewWithoutChangingItsSourceText() {
        val text = "# Preview heading\n\nAn **important** Note.\n"
        val fixture = openFile(text.toByteArray(), writable = false, name = "Fixture.md")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source-editor").assertDoesNotExist()
        compose.onNodeWithTag("editor-toolbar").assertDoesNotExist()
        assertEquals(0, fixture.provider.writes.get())
        assertTrue(fixture.engine.library.value.active.isEmpty())
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        compose.onNodeWithTag("single-choice-Local Source").performClick()
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null && fixture.engine.library.value.active.size == 1 }
        assertEquals(text, runBlocking { fixture.engine.note(fixture.engine.library.value.active.single().id) }!!.markdown)
        assertEquals(text, fixture.provider.file.readText())
    }

    @Test
    fun mixedMarkdownKeepsRichPreviewAndCopiesItsOriginalSourceExactly() {
        val text = "# Reference\n\n<https://example.org/reference>\n\n" +
            "| Item | Link |\n| --- | --- |\n| **First** | <https://example.org/item> |\n\n" +
            "<details><summary>Details</summary>Preserved HTML.</details>\n\n" +
            "<!-- retained comment -->\n\nText with reference[^one].\n\n[^one]: Footnote.\n\n" +
            "```html\n<div>Code example</div>\n```\n"
        val fixture = openFile(text.toByteArray(), writable = false, name = "Fixture.md")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source-editor").assertDoesNotExist()
        compose.onNodeWithText("Raw HTML is not safe in Rich Mode yet").assertDoesNotExist()
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        compose.onNodeWithTag("single-choice-Local Source").performClick()
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null && fixture.engine.library.value.active.size == 1 }
        assertEquals(text, runBlocking { fixture.engine.note(fixture.engine.library.value.active.single().id) }!!.markdown)
        assertEquals(text, fixture.provider.file.readText())
        assertEquals(0, fixture.provider.writes.get())
    }

    @Test
    fun failedProviderSaveKeepsPreviewOpenUntilExplicitDiscard() {
        val fixture = openFile("Original".toByteArray())
        fixture.provider.failWrites = true
        compose.onNodeWithTag("source-editor").performClick().performTextReplacement("Unsaved edits")
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile?.error != null }
        compose.onNodeWithContentDescription("Close file").performClick()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithTag("source-editor").assertTextContains("Unsaved edits")
        compose.onNodeWithContentDescription("Close file").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null }
        assertTrue(fixture.engine.library.value.active.isEmpty())
        assertEquals("Original", fixture.provider.file.readText())
    }

    @Test
    fun repeatedSaveTapsCreateOnlyOneIndependentNote() {
        val fixture = openFile("Original".toByteArray())
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        compose.onNodeWithTag("single-choice-Local Source").performSemanticsAction(SemanticsActions.OnClick) { click ->
            click()
            click()
        }
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null && fixture.engine.library.value.active.size == 1 }
        assertEquals(1, fixture.engine.library.value.active.size)
        assertEquals(0, fixture.provider.writes.get())
    }

    @Test
    fun lostFileAccessAfterSessionRecreationRetainsUnsavedTextForAnExplicitCopy() {
        val fixture = openFile("Original".toByteArray())
        fixture.provider.failWrites = true
        compose.onNodeWithTag("source-editor").performClick().performTextReplacement("Recover these edits")
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile?.error != null }
        val oldJob = fixture.viewModel.viewModelScope.coroutineContext[Job]!!
        compose.runOnIdle { viewModels.clear() }
        runBlocking { oldJob.join() } // Finish any non-cancellable recovery write before recreating.
        fixture.application.writable = false
        compose.runOnIdle {
            fixture.shown.value = NotesViewModel(fixture.application, fixture.engine, fixture.preferences)
            viewModels.put("recovered", fixture.shown.value)
        }
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile?.text == "Recover these edits" }
        compose.onNodeWithTag("source-editor").assertTextContains("Recover these edits")
        assertTrue(fixture.engine.library.value.active.isEmpty())
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        compose.onNodeWithTag("single-choice-Local Source").performClick()
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile == null && fixture.engine.library.value.active.size == 1 }
        assertEquals("Recover these edits", runBlocking { fixture.engine.note(fixture.engine.library.value.active.single().id) }!!.markdown)
        assertEquals("Original", fixture.provider.file.readText())
    }

    @Test
    fun oldSaveAndCloseActionsCannotAffectAnotherPreviewWithTheSameFilename() {
        val fixture = openFile("First file".toByteArray())
        val oldClose = compose.onNodeWithContentDescription("Close file").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        val oldSave = compose.onNodeWithTag("single-choice-Local Source").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val second = fixture.provider.file.parentFile!!.resolve("second/Fixture.txt").apply {
            parentFile!!.mkdirs(); writeText("Second file")
        }
        val uri = fixture.provider.addFile(second)
        val previousKey = fixture.viewModel.state.value.externalFile!!.sessionKey
        compose.runOnIdle {
            fixture.viewModel.openExternalFile(ExternalFileRequest.from(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain"))!!)
        }
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile?.let { it.sessionKey != previousKey && it.text == "Second file" } == true }
        compose.onNodeWithTag("source-dialog").assertDoesNotExist()
        compose.runOnIdle { oldSave(); oldClose() }
        compose.waitForIdle()
        compose.onNodeWithTag("source-editor").assertTextContains("Second file")
        assertTrue(fixture.engine.library.value.active.isEmpty())
        assertEquals(0, fixture.previewCloses.get())
        assertEquals("First file", fixture.provider.file.readText())
        assertEquals("Second file", second.readText())
    }

    @Test
    fun delayedPickerResultCannotRetargetANewerFile() {
        val fixture = openFile("Same contents".toByteArray(), writable = false)
        compose.onNodeWithText("Edit original file…").performClick()
        val firstPicker = fixture.picker.pendingRequest!!
        val second = fixture.provider.file.parentFile!!.resolve("second/Fixture.txt").apply {
            parentFile!!.mkdirs(); writeText("Same contents")
        }
        val secondUri = fixture.provider.addFile(second)
        val previousKey = fixture.viewModel.state.value.externalFile!!.sessionKey
        compose.runOnIdle {
            fixture.viewModel.openExternalFile(ExternalFileRequest.from(
                Intent(Intent.ACTION_VIEW).setDataAndType(secondUri, "text/plain"))!!)
        }
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile?.sessionKey?.let { it != previousKey } == true }
        fixture.application.writable = true
        compose.runOnIdle { fixture.picker.dispatchResult(firstPicker, Activity.RESULT_OK, Intent().setData(fixture.provider.uri)) }
        compose.waitForIdle()
        assertEquals(false, fixture.viewModel.state.value.externalFile!!.writable)
        // A new, correctly scoped permission selection still permits editing the second file.
        compose.onNodeWithText("Edit original file…").performClick()
        compose.runOnIdle { fixture.picker.dispatchResult(fixture.picker.pendingRequest!!, Activity.RESULT_OK, Intent().setData(secondUri)) }
        compose.waitUntil(5_000) { fixture.viewModel.state.value.externalFile?.writable == true }
        compose.onNodeWithTag("source-editor").performClick().performTextReplacement("Edit second file")
        compose.waitUntil(5_000) { second.readText() == "Edit second file" }
        assertEquals("Same contents", fixture.provider.file.readText())
        assertTrue(fixture.engine.library.value.active.isEmpty())
    }

    @Test
    fun androidResolvesMarkdownPlainTextAndMislabelledNamedFilesToKgsNotes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            "content://file-app/42" to "text/markdown",
            "content://file-app/42" to "text/plain",
            "content://file-app/42" to "application/octet-stream",
            "content://file-app/notes/test.note.md" to "application/unknown",
            "content://file-app/notes/test.note.txt" to "application/unknown",
            "content://file-app/notes/test.md" to null,
            "content://file-app/notes/test.txt" to null,
        ).forEach { (uri, mime) ->
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).setPackage(context.packageName)
            assertTrue(context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == MainActivity::class.java.name })
        }
    }

    @Test
    fun androidDoesNotResolvePrivateFilePathsOrWebLinksToTheFileEditor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("file:///data/private.md", "https://example.invalid/note.md").forEach { uri ->
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "text/markdown").setPackage(context.packageName)
            assertTrue(context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty())
        }
    }

    private fun openFile(bytes: ByteArray, writable: Boolean = true, source: Source? = null, name: String = "Fixture.txt"): Fixture {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "external-behavior-").toFile()
        val provider = FileProvider(root.resolve(name).apply { writeBytes(bytes) })
        provider.attachInfo(base, ProviderInfo().apply { authority = "external-test"; exported = false })
        val application = FileApplication(base, root.resolve("recovery").apply { mkdirs() }, ContentResolver.wrap(provider), writable)
        val engine = ConnectedNotesEngine.open(root.resolve("local").toPath(), root.resolve("working").toPath())
        if (source != null) runBlocking { engine.attachSource(source) }
        val preferences = base.getSharedPreferences(root.name, Context.MODE_PRIVATE)
        lateinit var viewModel: NotesViewModel
        compose.runOnIdle {
            viewModel = NotesViewModel(application, engine, preferences)
            viewModels.put(root.name, viewModel)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(provider.uri, if (name.endsWith(".md")) "text/markdown" else "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            viewModel.openExternalFile(ExternalFileRequest.from(intent)!!)
        }
        val shown = mutableStateOf(viewModel)
        val picker = PickerRegistry()
        val pickerOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        val previewCloses = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerOwner) {
                KgsNotesApp(shown.value, onExternalPreviewClosed = { previewCloses.incrementAndGet() })
            }
        }
        compose.waitUntil(5_000) { viewModel.state.value.externalFile?.name == name }
        return Fixture(provider, engine, shown, application, preferences, picker, previewCloses)
    }

    private data class Fixture(val provider: FileProvider, val engine: ConnectedNotesEngine, val shown: MutableState<NotesViewModel>,
        val application: FileApplication, val preferences: android.content.SharedPreferences,
        val picker: PickerRegistry, val previewCloses: AtomicInteger) {
        val viewModel get() = shown.value
    }

    private class FileApplication(base: Context, private val recovery: File, private val resolver: ContentResolver, @Volatile var writable: Boolean) : Application() {
        init { attachBaseContext(base) }
        override fun getNoBackupFilesDir(): File = recovery
        override fun getContentResolver(): ContentResolver = resolver
        override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int =
            if (modeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 || writable) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    private class FileProvider(val file: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://external-test/42")
        private val files = java.util.concurrent.ConcurrentHashMap<Uri, File>().apply { put(uri, file) }
        val writes = AtomicInteger()
        @Volatile var failWrites = false
        override fun onCreate() = true
        fun addFile(file: File): Uri = Uri.parse("content://external-test/${files.size + 42}").also { files[it] = file }
        override fun getType(uri: Uri) = if (files.getValue(uri).name.endsWith(".md")) "text/markdown" else "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            // Some file apps return extra columns despite the requested projection.
            MatrixCursor(arrayOf("_id", OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(42, files.getValue(uri).name)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if ('w' in mode) {
                if (failWrites) throw FileNotFoundException("Controlled provider write failure")
                writes.incrementAndGet()
            }
            return ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.parseMode(mode))
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private class PickerRegistry : ActivityResultRegistry() {
        var pendingRequest: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            pendingRequest = requestCode
        }
    }

    private class RecordingSource : Source {
        override val descriptor = SourceDescriptor(SourceId("external-test-server"), "Test Source",
            SourceCapabilities(markdown = true, categories = true, favorites = true, attachments = false, writable = true))
        override val notes = MutableStateFlow<List<SourceNote>>(emptyList())
        override suspend fun refresh() {}
        override suspend fun createNote(note: SourceNoteDraft): SourceNote {
            val saved = SourceNote("remote", "1", note.title, note.markdown, note.category, note.favorite, note.modifiedAt, false)
            notes.value = listOf(saved)
            return saved
        }
        override suspend fun updateNote(remoteId: String, expectedRevision: String, note: SourceNoteDraft): SourceNote = createNote(note)
        override suspend fun deleteNote(remoteId: String, expectedRevision: String) { notes.value = emptyList() }
    }
}
