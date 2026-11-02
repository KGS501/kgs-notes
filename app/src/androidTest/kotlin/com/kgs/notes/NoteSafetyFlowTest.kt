package com.kgs.notes

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kgs.notes.engine.AttachmentImport
import com.kgs.notes.engine.ConnectedNotesEngine
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.ManagedAttachment
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NotesEngine
import com.kgs.notes.engine.SourceId
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import com.kgs.notes.engine.SourceNoteDraft
import com.kgs.notes.engine.SourceNote
import com.kgs.notes.engine.SourceDescriptor
import com.kgs.notes.engine.SourceCapabilities
import com.kgs.notes.engine.NoteState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipInputStream

/**
 * Real engine storage behind a controllable wrapper: saves, imports, Category changes and
 * moves can be held or failed at exact points while the ViewModel keeps receiving edits.
 */
@SdkSuppress(minSdkVersion = 29) // ContentResolver.wrap is an Android test adapter introduced in API 29.
class NoteSafetyFlowTest {
    private val viewModels = ViewModelStore()
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun close() {
        collectors.cancel()
        main { viewModels.clear() }
    }

    // #8: storage errors during autosave and explicit flushes.

    @Test
    fun autosaveStorageFailureKeepsTheDraftBlocksNavigationAndRetryPersistsIt() {
        val f = fixture()
        val first = f.note("# First\n\nSaved")
        val second = f.note("# Second\n\nOther")
        f.open(first)
        f.engine.failContent = true
        val draft = "# First\n\nSaved\n\nUnsaved draft"
        main { f.vm.updateContent(draft) }
        waitUntil { f.vm.state.value.saveFailed }
        assertEquals(draft, f.vm.state.value.selectedNote!!.markdown)
        main { f.vm.selectNote(second) }
        settle()
        assertEquals("A draft that could not be saved keeps its page", first, f.vm.state.value.selectedNote!!.id)
        main { f.vm.closeEditor() }
        settle()
        assertEquals(first, f.vm.state.value.selectedNote!!.id)
        f.engine.failContent = false
        main { f.vm.retrySave() }
        waitUntil { !f.vm.state.value.saveFailed && !f.vm.state.value.saving }
        assertEquals(draft, f.stored(first).markdown)
        main { f.vm.selectNote(second) }
        waitUntil { f.vm.state.value.selectedNote?.id == second }
        assertEquals(draft, f.reopenedNote(first).markdown)
    }

    @Test
    fun explicitFlushFailureDoesNotCrashAndDiscardIsExplicit() {
        val f = fixture()
        val first = f.note("# First\n\nSaved")
        f.open(first)
        f.engine.failContent = true
        main { f.vm.updateContent("# First\n\nEdited") }
        main { f.vm.flushPendingContent() }
        waitUntil { f.vm.state.value.saveFailed }
        assertEquals("# First\n\nEdited", f.vm.state.value.selectedNote!!.markdown)
        main { f.vm.discardUnsavedChanges() }
        waitUntil { !f.vm.state.value.saveFailed && f.vm.state.value.selectedNote?.markdown == "# First\n\nSaved" }
        main { f.vm.closeEditor() }
        waitUntil { f.vm.state.value.selectedNote == null }
        assertEquals("# First\n\nSaved", f.stored(first).markdown)
    }

    @Test
    fun unconfirmedPermanentDeletionKeepsTheTrashedNoteWithoutCrashing() {
        val f = fixture()
        val trashed = f.note("# Old\n\nKeep me")
        runBlocking { f.inner.moveToTrash(trashed) }
        main { f.vm.openDestination(LibraryDestination.TRASH) }
        f.open(trashed)
        f.engine.failDelete = true
        main { f.vm.deletePermanently() }
        waitUntil { f.vm.state.value.userMessage != null }
        assertEquals(trashed, f.vm.state.value.selectedNote?.id)
        assertEquals("# Old\n\nKeep me", f.stored(trashed).markdown)
    }

    // #7: typing while an image is imported.

    @Test
    fun textTypedDuringAnImageImportSurvivesWithTheImage() {
        val f = fixture()
        val id = f.note("# Trip\n\nBefore")
        f.open(id)
        val gate = CompletableDeferred<Unit>().also { f.engine.importGate = it }
        main { f.vm.importImage(f.imageUri, removePrivateMetadata = false) }
        waitUntil { f.engine.importStarted }
        main { f.vm.updateContent("# Trip\n\nBefore\n\nTyped while importing") }
        gate.complete(Unit)
        waitUntil { f.vm.state.value.selectedNote!!.markdown.contains(".kgs-notes-attachments") && !f.vm.state.value.saving }
        val stored = f.reopenedNote(id).markdown
        val target = runBlocking { f.inner.managedAttachments(id) }.single().markdownTarget
        assertTrue(stored, stored.contains("Typed while importing"))
        assertTrue(stored, stored.contains(target))
        assertEquals(stored, f.vm.state.value.selectedNote!!.markdown)
    }

    @Test
    fun anImageImportFinishingAfterNavigationBelongsToTheRequestedNote() {
        val f = fixture()
        val requested = f.note("# Requested\n\nText")
        val other = f.note("# Other\n\nUntouched")
        f.open(requested)
        val gate = CompletableDeferred<Unit>().also { f.engine.importGate = it }
        main { f.vm.importImage(f.imageUri, removePrivateMetadata = false) }
        waitUntil { f.engine.importStarted }
        f.open(other)
        gate.complete(Unit)
        waitUntil { f.stored(requested).markdown.contains(".kgs-notes-attachments") }
        assertEquals("# Other\n\nUntouched", f.stored(other).markdown)
        assertEquals("# Other\n\nUntouched", f.vm.state.value.selectedNote!!.markdown)
    }

    // #38: Category moves with dirty drafts that reference managed attachments.

    @Test
    fun draftTypedAroundACategoryMoveKeepsResolvableAttachmentTargets() {
        val f = fixture()
        val id = f.noteWithImage("Trips/Forest")
        f.open(id)
        val gate = CompletableDeferred<Unit>().also { f.engine.categoryGate = it }
        val shown = f.vm.state.value.selectedNote!!.markdown
        main {
            f.vm.updateContent("$shown\nTyped before the move")
            f.vm.setCategory("Archive")
        }
        waitUntil { f.engine.categoryStarted }
        main { f.vm.updateContent(f.vm.state.value.selectedNote!!.markdown + "\nTyped during the move") }
        gate.complete(Unit)
        waitUntil { !f.vm.state.value.saving && f.stored(id).category == "Archive" && f.stored(id).markdown.contains("during the move") }
        f.assertTargetsResolve(id, "Archive", "Typed before the move", "Typed during the move")
    }

    @Test
    fun bulkCategoryChangeRebasesTheDraftOpenOnTheOtherPage() {
        val f = fixture()
        val id = f.noteWithImage("Trips/Forest")
        main { f.vm.activatePane(NotePane.SECONDARY) }
        f.open(id)
        val gate = CompletableDeferred<Unit>().also { f.engine.categoryGate = it }
        main { f.vm.applyToNotes(setOf(id), NoteSelectionOperation.CATEGORY, "Archive") }
        waitUntil { f.engine.categoryStarted }
        main { f.vm.updateContent(f.vm.state.value.selectedNote!!.markdown + "\nTyped in the other page") }
        gate.complete(Unit)
        waitUntil { !f.vm.state.value.saving && f.stored(id).markdown.contains("other page") }
        f.assertTargetsResolve(id, "Archive", "Typed in the other page")
    }

    // #26: moves that return a replacement identity.

    @Test
    fun draftTypedDuringABulkMoveFollowsTheReplacementNoteOnTheSecondPage() {
        val f = fixture()
        val original = f.note("# Shared\n\nA")
        main { f.vm.activatePane(NotePane.SECONDARY) }
        f.open(original)
        f.engine.replacementOnMove = true
        val gate = CompletableDeferred<Unit>().also { f.engine.moveGate = it }
        main { f.vm.applyToNotes(setOf(original), NoteSelectionOperation.MOVE, LocalSourceId.value) }
        waitUntil { f.engine.moveStarted }
        main { f.vm.updateContent("# Shared\n\nB typed during the move") }
        settle()
        assertEquals("No save may reach the original while it is moving", "# Shared\n\nA", f.stored(original).markdown)
        gate.complete(Unit)
        waitUntil { f.vm.state.value.selectedNote?.id != original && !f.vm.state.value.saving }
        val moved = f.vm.state.value.selectedNote!!.id
        waitUntil { f.stored(moved).markdown == "# Shared\n\nB typed during the move" }
        assertEquals("# Shared\n\nA", f.stored(original).markdown)
        assertEquals(NotePane.SECONDARY, f.vm.state.value.activePane)
    }

    @Test
    fun singleSourceChangeRetargetsThePrimaryPageWithItsNewestDraft() {
        val f = fixture()
        val original = f.note("# Shared\n\nA")
        f.open(original)
        f.engine.replacementOnMove = true
        val gate = CompletableDeferred<Unit>().also { f.engine.moveGate = it }
        main { f.vm.changeSource(SourceId("elsewhere")) }
        waitUntil { f.engine.moveStarted }
        main { f.vm.updateContent("# Shared\n\nB") }
        gate.complete(Unit)
        waitUntil { f.vm.state.value.selectedNote?.id != original && !f.vm.state.value.saving }
        val moved = f.vm.state.value.selectedNote!!.id
        waitUntil { f.stored(moved).markdown == "# Shared\n\nB" }
        assertEquals("# Shared\n\nA", f.stored(original).markdown)
    }

    // #33: permanent deletion from the adjacent library.

    @Test
    fun deletingAnOpenTrashedNoteFromTheLibraryClosesItsPage() {
        val f = fixture()
        val trashed = f.note("# Old\n\nBin")
        runBlocking { f.inner.moveToTrash(trashed) }
        main { f.vm.openDestination(LibraryDestination.TRASH) }
        f.open(trashed)
        main { f.vm.applyToNotes(setOf(trashed), NoteSelectionOperation.DELETE) }
        waitUntil { f.vm.state.value.selectedNote == null }
        assertNull(runBlocking { f.inner.note(trashed) })
        main { f.vm.restore() }
        settle()
        assertNull(f.vm.state.value.selectedNote)
    }

    @Test
    fun aCleanPageClosesWhenItsNoteIsDeletedElsewhere() {
        val f = fixture()
        val id = f.note("# Gone\n\nSoon")
        main { f.vm.activatePane(NotePane.SECONDARY) }
        f.open(id)
        runBlocking { f.inner.moveToTrash(id); f.inner.deletePermanently(id) }
        waitUntil { f.vm.state.value.selectedNote == null }
    }

    // #11: full-body search.

    @Test
    fun searchFindsTextBeyondThePreviewInActiveTrashAndDrafts() {
        val f = fixture()
        val shopping = f.note("# Shopping\n\nMilk\n\nEggs\n\n## Later\n\nOne kiwi, deep in the body")
        f.note("# Unrelated\n\nNothing here")
        val binned = f.note("# Old list\n\nFirst line\n\nPumpkin near the end")
        runBlocking { f.inner.moveToTrash(binned) }
        main { f.vm.setQuery("KIWI") }
        waitUntil { f.vm.state.value.notes.map { it.id } == listOf(shopping) }
        main { f.vm.setQuery("eggs kiwi") }
        waitUntil { f.vm.state.value.notes.map { it.id } == listOf(shopping) }
        main { f.vm.openDestination(LibraryDestination.TRASH); f.vm.setQuery("pumpkin") }
        waitUntil { f.vm.state.value.notes.map { it.id } == listOf(binned) }
        main { f.vm.openDestination(LibraryDestination.NOTES); f.vm.setQuery("") }
        f.open(shopping)
        main { f.vm.updateContent(f.vm.state.value.selectedNote!!.markdown + "\n\nZucchini") }
        main { f.vm.setQuery("zucchini") }
        waitUntil { f.vm.state.value.notes.map { it.id } == listOf(shopping) }
    }

    // #16: explicit Trash intent survives a save that lands around the Trash action, on an external Source.

    @Test
    fun aDraftSavedAroundTrashKeepsAnExternalNoteTrashedUntilRestore() {
        for (bulk in listOf(false, true)) for (typeAfterTrash in listOf(false, true)) {
            val f = fixture()
            val server = TrashTestSource()
            runBlocking { f.inner.attachSource(server); f.inner.refreshSources() }
            val id = f.inner.library.value.active.single { it.sourceId == server.descriptor.id }.id
            f.open(id)
            val case = "bulk=$bulk typeAfterTrash=$typeAfterTrash"
            main {
                if (!typeAfterTrash) f.vm.updateContent("Shared body typed before Trash")
                if (bulk) f.vm.applyToNotes(setOf(id), NoteSelectionOperation.TRASH) else f.vm.moveToTrash()
                // A keystroke whose debounce fires after the Trash request.
                if (typeAfterTrash) f.vm.updateContent("Shared body typed after Trash")
            }
            settle()
            waitUntil { f.stored(id).state == NoteState.TRASHED && server.notes.value.isEmpty() }
            settle()

            assertEquals(case, NoteState.TRASHED, f.stored(id).state)
            assertEquals(case, NoteState.TRASHED, f.reopenedNote(id).state)
            assertTrue(case, server.notes.value.isEmpty())
            assertEquals(case, 1, server.deletes)
            assertEquals(case, 0, server.creates)

            // Only Restore reverses it, republishing what was kept locally.
            runBlocking { f.inner.restore(id); f.inner.synchronize(id) }
            assertEquals(case, NoteState.ACTIVE, f.stored(id).state)
            assertEquals(case, f.stored(id).markdown, server.notes.value.single().markdown)
            main { viewModels.clear() }
        }
    }

    /** A minimal Notes server: one existing Note, creates and deletes counted. */
    private class TrashTestSource : Source {
        override val descriptor = SourceDescriptor(SourceId("trash-test-server"), "Test cloud",
            SourceCapabilities(markdown = true, categories = true, favorites = true, attachments = false, writable = true))
        override val notes = MutableStateFlow(listOf(
            SourceNote("1", "r1", "Shared", "Shared body", "", false, Instant.parse("2026-08-24T08:00:00Z"), false),
        ))
        @Volatile var creates = 0
        @Volatile var deletes = 0
        private var revision = 1
        override suspend fun refresh() {}
        override suspend fun createNote(note: SourceNoteDraft): SourceNote {
            creates++
            return SourceNote("c$creates", "r${++revision}", note.title, note.markdown, note.category, note.favorite, note.modifiedAt, false)
                .also { notes.value = notes.value + it }
        }
        override suspend fun updateNote(remoteId: String, expectedRevision: String, note: SourceNoteDraft): SourceNote {
            val current = notes.value.single { it.remoteId == remoteId }
            if (current.revision != expectedRevision) throw com.kgs.notes.engine.SourceConflictException()
            return current.copy(revision = "r${++revision}", title = note.title, markdown = note.markdown, category = note.category,
                favorite = note.favorite, modifiedAt = note.modifiedAt).also { updated ->
                notes.value = notes.value.map { if (it.remoteId == remoteId) updated else it }
            }
        }
        override suspend fun deleteNote(remoteId: String, expectedRevision: String) {
            if (notes.value.any { it.remoteId == remoteId }) deletes++
            notes.value = notes.value.filterNot { it.remoteId == remoteId }
        }
    }

    // #12: offline copy and export.

    @Test
    fun exportWritesCanonicalMarkdownAndAnArchiveWithAttachments() {
        val f = fixture()
        val markdown = "# Plain\r\n\nLiteral \\*stars\\* and trailing spaces  \n"
        val plain = f.note(markdown)
        f.open(plain)
        main { f.vm.requestExport() }
        waitUntil { f.vm.state.value.exportRequest != null }
        val request = f.vm.state.value.exportRequest!!
        assertEquals("text/markdown", request.file.mimeType)
        val markdownFile = File(f.root, "export.md")
        main { f.vm.exportNote(request, Uri.fromFile(markdownFile)) }
        waitUntil { f.vm.state.value.userMessage == "The Note was exported." }
        assertArrayEquals(f.stored(plain).markdown.toByteArray(), markdownFile.readBytes())

        val withImage = f.noteWithImage("Trips/Forest")
        f.open(withImage)
        main { f.vm.clearUserMessage(); f.vm.requestExport() }
        waitUntil { f.vm.state.value.exportRequest?.noteId == withImage }
        val archiveRequest = f.vm.state.value.exportRequest!!
        assertTrue(archiveRequest.file.archive)
        val archive = File(f.root, "export.zip")
        main { f.vm.exportNote(archiveRequest, Uri.fromFile(archive)) }
        waitUntil { f.vm.state.value.userMessage == "The Note was exported." }
        val entries = unzip(archive.readBytes())
        val notePath = entries.keys.single { it.endsWith(".md") }
        val stored = f.stored(withImage).markdown
        assertEquals(stored, String(entries.getValue(notePath)))
        val target = runBlocking { f.inner.managedAttachments(withImage) }.single().markdownTarget
        val resolved = File("/x/" + notePath).parentFile!!.resolve(target).canonicalPath.removePrefix("/x/")
        assertArrayEquals(SmallPng, entries.getValue(resolved))
    }

    @Test
    fun exportWriteFailureIsReportedAndLeavesTheNoteUnchanged() {
        val f = fixture()
        val id = f.note("# Keep\n\nOriginal")
        f.open(id)
        main { f.vm.requestExport() }
        waitUntil { f.vm.state.value.exportRequest != null }
        val request = f.vm.state.value.exportRequest!!
        main { f.vm.exportNote(request, Uri.fromFile(File(f.root, "missing-folder/export.md"))) }
        waitUntil { f.vm.state.value.userMessage != null }
        assertNotEquals("The Note was exported.", f.vm.state.value.userMessage)
        assertEquals("# Keep\n\nOriginal", f.stored(id).markdown)
    }

    @Test
    fun copyToLocalRescuesADraftThatCouldNotBeSaved() {
        val f = fixture()
        val id = f.note("# Draft\n\nStored")
        f.open(id)
        f.engine.failContent = true
        main { f.vm.updateContent("# Draft\n\nOnly on screen") }
        waitUntil { f.vm.state.value.saveFailed }
        main { f.vm.copyNoteToLocal() }
        waitUntil { f.inner.library.value.active.size == 2 }
        val copy = f.inner.library.value.active.single { it.id != id }
        assertEquals(LocalSourceId, copy.sourceId)
        assertEquals("# Draft\n\nOnly on screen", f.stored(copy.id).markdown)
        assertEquals("# Draft\n\nStored", f.stored(id).markdown)
    }

    // #31: read-only Notes.

    @Test
    fun readOnlyNotesRefuseEditsAndBulkMutationsButKeepFavorites() {
        val f = fixture()
        val id = f.note("# Shared\n\nRead only")
        f.engine.readOnlyIds += id
        f.open(id)
        assertTrue(f.vm.state.value.selectedNote!!.readOnly)
        main {
            f.vm.updateContent("# Shared\n\nChanged")
            f.vm.rename("Changed")
            f.vm.setCategory("Elsewhere")
        }
        settle()
        assertEquals("# Shared\n\nRead only", f.vm.state.value.selectedNote!!.markdown)
        assertFalse(f.vm.state.value.saving)
        main { f.vm.toggleFavorite() }
        waitUntil { f.stored(id).favorite }
        main { f.vm.applyToNotes(setOf(id), NoteSelectionOperation.TRASH) }
        waitUntil { f.vm.state.value.userMessage == "Read-only Notes were left unchanged." }
        assertEquals("# Shared\n\nRead only", f.stored(id).markdown)
        assertFalse(f.inner.library.value.trash.any { it.id == id })
    }

    // #35: decoded image bounds.

    @Test
    fun metadataRemovalDownsamplesLargeImagesAndRefusesAbsurdHeaders() {
        val f = fixture()
        f.images.bytes = highlyCompressedPng(6000, 6000)
        val prepared = prepareImageImport(f.application, f.imageUri, removePrivateMetadata = true)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size, bounds)
        assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= ImageDecodeBudget.MAX_DECODED_PIXELS)
        assertEquals(bounds.outWidth, bounds.outHeight)

        f.images.bytes = pngHeaderOnly(60_000, 60_000)
        val refused = runCatching { prepareImageImport(f.application, f.imageUri, removePrivateMetadata = true) }
        assertTrue(refused.exceptionOrNull().toString(), refused.exceptionOrNull() is ImageImportProblem)
    }

    // Fixture.

    private class HookedEngine(val inner: ConnectedNotesEngine) : NotesEngine by inner {
        @Volatile var failContent = false
        @Volatile var failDelete = false
        @Volatile var importGate: CompletableDeferred<Unit>? = null
        @Volatile var importStarted = false
        @Volatile var categoryGate: CompletableDeferred<Unit>? = null
        @Volatile var categoryStarted = false
        @Volatile var moveGate: CompletableDeferred<Unit>? = null
        @Volatile var moveStarted = false
        @Volatile var replacementOnMove = false
        val readOnlyIds = mutableSetOf<NoteId>()

        override suspend fun note(id: NoteId): Note? = inner.note(id)?.let { if (id in readOnlyIds) it.copy(readOnly = true) else it }
        override suspend fun openEditingSession(id: NoteId): Note? = inner.openEditingSession(id)?.let { if (id in readOnlyIds) it.copy(readOnly = true) else it }

        override suspend fun updateContent(id: NoteId, markdown: String) {
            if (failContent) throw IOException("No space left on device")
            inner.updateContent(id, markdown)
        }

        override suspend fun importManagedAttachment(noteId: NoteId, attachment: AttachmentImport): ManagedAttachment {
            importStarted = true
            importGate?.await()
            return inner.importManagedAttachment(noteId, attachment)
        }

        override suspend fun setCategory(id: NoteId, category: String) {
            categoryStarted = true
            categoryGate?.await()
            inner.setCategory(id, category)
        }

        override suspend fun moveToSource(id: NoteId, sourceId: SourceId): NoteId {
            moveStarted = true
            moveGate?.await()
            if (!replacementOnMove) return inner.moveToSource(id, sourceId)
            // Like a conflicting move that keeps both sides: the persisted text goes to a new Note.
            val original = inner.note(id)!!
            val copy = inner.createDraft(original.category, LocalSourceId)
            inner.updateContent(copy, original.markdown)
            inner.close(copy)
            return copy
        }

        override suspend fun deletePermanently(id: NoteId) {
            if (failDelete) throw IllegalStateException("The Source did not confirm deletion")
            inner.deletePermanently(id)
        }
    }

    private class Fixture(val root: File, val inner: ConnectedNotesEngine, val engine: HookedEngine, val vm: NotesViewModel,
        val application: Application, val images: ImageProvider) {
        val imageUri: Uri = Uri.parse("content://image-test/pine.png")

        fun note(markdown: String, category: String = ""): NoteId = runBlocking {
            val id = inner.createDraft(category)
            inner.updateContent(id, markdown)
            inner.close(id)
            id
        }

        fun noteWithImage(category: String): NoteId = runBlocking {
            val id = note("# Trip\n\nIntro", category)
            val attachment = inner.importManagedAttachment(id, AttachmentImport("Pine.png", "image/png") { ByteArrayInputStream(SmallPng) })
            inner.updateContent(id, "# Trip\n\nIntro\n\n![Pine](${attachment.markdownTarget})\n")
            inner.close(id)
            id
        }

        fun open(id: NoteId) {
            main { vm.selectNote(id) }
            waitUntil { vm.state.value.selectedNote?.id == id }
        }

        fun stored(id: NoteId): Note = runBlocking { engine.note(id) }!!

        /** The Note as a freshly opened engine reads it from storage. */
        fun reopenedNote(id: NoteId): Note = runBlocking {
            ConnectedNotesEngine.open(root.resolve("local").toPath(), root.resolve("working").toPath()).note(id)
        }!!

        fun assertTargetsResolve(id: NoteId, category: String, vararg typed: String) {
            val stored = reopenedNote(id)
            typed.forEach { assertTrue(stored.markdown, stored.markdown.contains(it)) }
            val target = Regex("\\]\\(([^)]+)\\)").find(stored.markdown)!!.groupValues[1]
            assertFalse(stored.markdown, stored.markdown.contains("../../.kgs-notes-attachments") && category.count { it == '/' } == 0)
            val noteFolder = root.resolve("local").resolve(category)
            assertArrayEquals("$target must resolve from $category", SmallPng, noteFolder.resolve(target).canonicalFile.readBytes())
        }

    }

    private fun fixture(): Fixture {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "note-safety-").toFile()
        val images = ImageProvider().apply { attachInfo(base, ProviderInfo().apply { authority = "image-test"; exported = false }) }
        val application = TestApplication(base, root.resolve("private").apply { mkdirs() }, ContentResolver.wrap(images))
        val inner = ConnectedNotesEngine.open(root.resolve("local").toPath(), root.resolve("working").toPath())
        val engine = HookedEngine(inner)
        val preferences = base.getSharedPreferences(root.name, Context.MODE_PRIVATE)
        lateinit var vm: NotesViewModel
        main {
            vm = NotesViewModel(application, engine, preferences)
            viewModels.put(root.name, vm)
            // The UI state is shared while subscribed, as it is while the app is shown.
            collectors.launch { vm.state.collect {} }
        }
        return Fixture(root, inner, engine, vm, application, images)
    }

    private class TestApplication(base: Context, private val private: File, private val resolver: ContentResolver) : Application() {
        init { attachBaseContext(base) }
        override fun getNoBackupFilesDir(): File = private
        override fun getContentResolver(): ContentResolver = resolver
    }

    private class ImageProvider : ContentProvider() {
        @Volatile var bytes: ByteArray = SmallPng
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Pine.png")) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            // ContentResolver.wrap routes every URI here; export destinations are plain files, opened as
            // the platform resolver opens file URIs.
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                return ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.parseMode(mode))
            }
            val file = File.createTempFile("image", ".png", context!!.cacheDir).apply { writeBytes(bytes); deleteOnExit() }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        val SmallPng: ByteArray = highlyCompressedPng(2, 2)

        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

        fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + timeoutMillis
            while (true) {
                var satisfied = false
                main { satisfied = condition() }
                if (satisfied) return
                check(SystemClock.uptimeMillis() < end) { "Condition not reached in time" }
                Thread.sleep(20)
            }
        }

        /** Long enough for every debounce to have fired. */
        fun settle() {
            Thread.sleep(700)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        fun unzip(bytes: ByteArray): Map<String, ByteArray> {
            val result = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    result[entry.name] = zip.readBytes()
                }
            }
            return result
        }

        fun pngHeaderOnly(width: Int, height: Int): ByteArray = png(width, height, idat = null)

        fun highlyCompressedPng(width: Int, height: Int): ByteArray {
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed, Deflater(9)).use { zipped ->
                val row = ByteArray(1 + width * 4)
                repeat(height) { zipped.write(row) }
            }
            return png(width, height, compressed.toByteArray())
        }

        private fun png(width: Int, height: Int, idat: ByteArray?): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                val stream = DataOutputStream(out)
                stream.writeInt(data.size)
                val typed = type.toByteArray(Charsets.US_ASCII) + data
                stream.write(typed)
                stream.writeInt(CRC32().apply { update(typed) }.value.toInt())
            }
            chunk("IHDR", ByteArrayOutputStream().also { DataOutputStream(it).apply {
                writeInt(width); writeInt(height); writeByte(8); writeByte(6); writeByte(0); writeByte(0); writeByte(0)
            } }.toByteArray())
            if (idat != null) chunk("IDAT", idat)
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }
    }
}
