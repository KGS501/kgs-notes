package com.kgs.notes

import com.kgs.notes.design.R.string as UiR
import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import com.kgs.notes.design.NotesTheme
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kgs.notes.editor.DefaultMarkdownEditor
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.editor.MarkdownAttachment
import com.kgs.notes.editor.MarkdownSelection
import com.kgs.notes.engine.AttachmentImport
import com.kgs.notes.engine.AttachmentId
import com.kgs.notes.engine.LibrarySnapshot
import com.kgs.notes.engine.ConnectedNotesEngine
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.NotesEngine
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.SourceId
import com.kgs.notes.engine.SourceDescriptor
import com.kgs.notes.engine.SourceConflictException
import com.kgs.notes.nextcloud.HttpNextcloudLoginFlow
import com.kgs.notes.nextcloud.HttpNextcloudNotesApi
import com.kgs.notes.nextcloud.NextcloudApiException
import com.kgs.notes.nextcloud.NextcloudConnectionException
import com.kgs.notes.nextcloud.NextcloudCredentials
import com.kgs.notes.nextcloud.NextcloudLoginChallenge
import com.kgs.notes.nextcloud.NextcloudLoginFlow
import com.kgs.notes.nextcloud.NextcloudNotesSource
import com.kgs.notes.nextcloud.UnsupportedNotesApi
import com.kgs.notes.nextcloud.canonicalNextcloudServerAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

enum class LibraryFilter {
    ALL,
    FAVORITES,
    LOCAL,
    SERVER,
}

enum class LibrarySort {
    LAST_EDITED,
    LAST_CREATED,
    ALPHABETICAL,
}

enum class LibraryView { LIST, CARDS }
enum class EditorLayoutPreference { AUTOMATIC, SPLIT, FULL_SCREEN }

data class ManagedCategory(val sourceId: SourceId, val path: String, val noteCount: Int)

enum class LibraryDestination {
    NOTES,
    TRASH,
    SETTINGS,
}

enum class NoteSelectionOperation { FAVORITE, CATEGORY, MOVE, TRASH, RESTORE, DELETE }

enum class AttachmentMetadataPreference {
    ASK_EVERY_TIME,
    REMOVE_PRIVATE_METADATA,
    KEEP_ORIGINAL,
}

/** Whether KGS Notes follows the system's dark theme or keeps its own. */
enum class DarkModePreference(val labelRes: Int) {
    SYSTEM(UiR.ui_system),
    LIGHT(UiR.ui_light),
    DARK(UiR.ui_dark),
}

enum class NoteFormatPreference(val labelRes: Int, val nextcloudSuffix: String?) {
    MARKDOWN(UiR.ui_markdown_md, ".md"),
    TEXT(UiR.ui_text_txt, ".txt"),
    FOLLOW_SOURCE(UiR.ui_follow_source, null),
}

data class SourceChoice(
    val id: SourceId,
    val name: String,
    val visible: Boolean,
    val connected: Boolean = true,
    val supportsAttachments: Boolean = id == LocalSourceId,
    val writable: Boolean = true,
    val categories: Boolean = true,
)

/** In book posture the right page is primary, as the editor beside the library is elsewhere. */
enum class NotePane {
    PRIMARY,
    SECONDARY;

    val other: NotePane get() = if (this == PRIMARY) SECONDARY else PRIMARY
}

data class PaneNote(val note: Note, val editorMode: EditorMode)

/** One open Note with its own unsaved edits; book posture shows two pages at once. */
private class EditorSlot {
    val note = MutableStateFlow<Note?>(null)
    var contentSave: Job? = null
    var metadataSave: Job? = null
    var pendingTitle: String? = null
    var pendingCategory: String? = null
    var contentDirty = false
    var metadataDirty = false
    var editorMode: EditorMode? = null
}

enum class SourceConnectionPhase {
    READY,
    STARTING,
    WAITING_FOR_BROWSER,
    VERIFYING,
    CONNECTED,
    ERROR,
}

data class SourceConnectionUiState(
    val visible: Boolean = false,
    val phase: SourceConnectionPhase = SourceConnectionPhase.READY,
    val browserUrl: String? = null,
    val message: String? = null,
    val editingSourceId: SourceId? = null,
    val initialName: String = "Nextcloud",
    val initialServerUrl: String = "",
    val initialLoginName: String = "",
)

data class NotesUiState(
    val notes: List<NoteSummary> = emptyList(),
    val selectedNote: Note? = null,
    val categories: List<String> = emptyList(),
    val managedCategories: List<ManagedCategory> = emptyList(),
    val managingCategories: Boolean = false,
    val editorLayout: EditorLayoutPreference = EditorLayoutPreference.AUTOMATIC,
    /** The library's share of a wide window beside an open Note. */
    val splitFraction: Float = DefaultSplitFraction,
    val canExpandEditor: Boolean = false,
    val fold: NotesFold? = null,
    val sources: List<SourceChoice> = listOf(SourceChoice(LocalSourceId, "Local Source", true)),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.LAST_EDITED,
    val libraryView: LibraryView = LibraryView.LIST,
    val groupByDate: Boolean = false,
    val ascending: Boolean = false,
    val destination: LibraryDestination = LibraryDestination.NOTES,
    /** null shows every Category; an empty path shows Notes without a Category. */
    val libraryCategory: String? = null,
    val pendingSyncCount: Int = 0,
    val syncingSources: Boolean = false,
    val editorMode: EditorMode = EditorMode.RICH,
    /** The page editor actions apply to; [selectedNote] and [editorMode] describe it. */
    val activePane: NotePane = NotePane.PRIMARY,
    /** The Note open on the other page in book posture. */
    val otherPane: PaneNote? = null,
    val previewModes: Map<NoteId, EditorMode> = emptyMap(),
    val attachmentMetadataPreference: AttachmentMetadataPreference = AttachmentMetadataPreference.ASK_EVERY_TIME,
    val saving: Boolean = false,
    val changingSource: Boolean = false,
    val userMessage: String? = null,
    val defaultSourceId: SourceId = LocalSourceId,
    val sourceConnection: SourceConnectionUiState = SourceConnectionUiState(),
    val noteFormatPreference: NoteFormatPreference = NoteFormatPreference.MARKDOWN,
    val theme: NotesTheme = NotesTheme.KGS_NOTES,
    val darkMode: DarkModePreference = DarkModePreference.SYSTEM,
    /** The mode for new Notes and for Notes whose mode was never switched. */
    val defaultEditorMode: EditorMode = EditorMode.RICH,
    val externalFile: ExternalFileUiState? = null,
)

class NotesViewModel internal constructor(
    application: Application,
    private val engine: NotesEngine,
    private val preferences: SharedPreferences = application.getSharedPreferences(
        PREFERENCES_NAME,
        Application.MODE_PRIVATE,
    ),
    private val accountStore: NextcloudAccountStore = NextcloudAccountStore(
        application.noBackupFilesDir.resolve("nextcloud-accounts").toPath(),
    ),
    private val loginFlow: NextcloudLoginFlow = HttpNextcloudLoginFlow(),
) : AndroidViewModel(application) {
    private val strings get() = getApplication<Application>().localizedResources()
    constructor(application: Application) : this(
        application = application,
        engine = ConnectedNotesEngine.open(
            localRoot = application.filesDir.resolve("local-source").toPath(),
            workingCopyRoot = application.noBackupFilesDir.resolve("working-copies").toPath(),
        ),
    )
    private val primarySlot = EditorSlot()
    private val secondarySlot = EditorSlot()
    private val activePane = MutableStateFlow(NotePane.PRIMARY)
    /** The slot that editor actions apply to; book posture switches it as a person works on either page. */
    private val slot: EditorSlot get() = slotFor(activePane.value)
    private fun slotFor(pane: NotePane) = if (pane == NotePane.PRIMARY) primarySlot else secondarySlot
    private val selected: MutableStateFlow<Note?> get() = slot.note
    private val controls = MutableStateFlow(
        UiControls(
            groupByDate = preferences.getBoolean(GROUP_BY_DATE_KEY, false),
            theme = preferences.getString(THEME_KEY, null)
                ?.let { stored -> NotesTheme.entries.firstOrNull { it.name == stored } }
                ?: NotesTheme.KGS_NOTES,
            darkMode = preferences.getString(DARK_MODE_KEY, null)
                ?.let { stored -> DarkModePreference.entries.firstOrNull { it.name == stored } }
                ?: DarkModePreference.SYSTEM,
            editorLayout = preferences.getString(EDITOR_LAYOUT_KEY, null)
                ?.let { value -> EditorLayoutPreference.entries.firstOrNull { it.name == value } }
                ?: EditorLayoutPreference.AUTOMATIC,
            splitFraction = preferences.getFloat(SPLIT_FRACTION_KEY, DefaultSplitFraction),
            defaultEditorMode = preferences.getString(DEFAULT_EDITOR_MODE_KEY, null)
                ?.let { stored -> EditorMode.entries.firstOrNull { it.name == stored } }
                ?: EditorMode.RICH,
            libraryView = preferences.getString(LIBRARY_VIEW_KEY, null)
                ?.let { stored -> LibraryView.entries.firstOrNull { it.name == stored } }
                ?: LibraryView.LIST,
            attachmentMetadataPreference = preferences
                .getString(ATTACHMENT_METADATA_PREFERENCE_KEY, null)
                ?.let { stored ->
                    AttachmentMetadataPreference.entries.firstOrNull { it.name == stored }
                }
                ?: AttachmentMetadataPreference.ASK_EVERY_TIME,
            defaultSourceId = SourceId(
                preferences.getString(DEFAULT_SOURCE_KEY, LocalSourceId.value) ?: LocalSourceId.value,
            ),
            noteFormatPreference = preferences
                .getString(NOTE_FORMAT_PREFERENCE_KEY, null)
                ?.let { stored -> NoteFormatPreference.entries.firstOrNull { it.name == stored } }
                ?: NoteFormatPreference.MARKDOWN,
        ),
    )
    private var contentSave: Job?
        get() = slot.contentSave
        set(value) { slot.contentSave = value }
    private var pendingTitle: String?
        get() = slot.pendingTitle
        set(value) { slot.pendingTitle = value }
    private var pendingCategory: String?
        get() = slot.pendingCategory
        set(value) { slot.pendingCategory = value }
    private var contentDirty: Boolean
        get() = slot.contentDirty
        set(value) { slot.contentDirty = value }
    private var metadataDirty: Boolean
        get() = slot.metadataDirty
        set(value) { slot.metadataDirty = value }
    private var connectionJob: Job? = null
    private var foregroundRefreshJob: Job? = null
    private var manualSyncJob: Job? = null
    private val syncJobs = mutableMapOf<NoteId, Job>()
    private val nextcloudSources = linkedMapOf<SourceId, NextcloudNotesSource>()
    private val sourcesReady = CompletableDeferred<Unit>()
    private val markdownEditor = DefaultMarkdownEditor()
    private val externalFiles = ExternalFileSession(application, viewModelScope)

    private val openNotes = combine(primarySlot.note, secondarySlot.note, activePane) { primary, secondary, active ->
        Triple(primary, secondary, active)
    }

    private val libraryUi = combine(
        engine.library,
        engine.sources,
        openNotes,
        controls,
    ) { library, sourceDescriptors, (primaryNote, secondaryNote, active), controls ->
        val selectedNote = if (active == NotePane.PRIMARY) primaryNote else secondaryNote
        val otherNote = if (active == NotePane.PRIMARY) secondaryNote else primaryNote
        val descriptorsById = sourceDescriptors.associateBy(SourceDescriptor::id)
        val allSourceIds = buildList {
            sourceDescriptors.forEach { descriptor ->
                if (descriptor.id !in this) add(descriptor.id)
            }
            (library.active + library.trash).forEach { note ->
                if (note.sourceId !in this) add(note.sourceId)
            }
        }
        val visible: (NoteSummary) -> Boolean = { it.sourceId !in controls.hiddenSourceIds }
        val candidates = when (controls.destination) {
            LibraryDestination.NOTES -> library.forFilter(controls.filter)
            LibraryDestination.TRASH -> library.trash
            LibraryDestination.SETTINGS -> emptyList()
        }
        val notes = candidates.filter(visible).filter { note ->
            controls.destination != LibraryDestination.NOTES || controls.libraryCategory == null || note.category == controls.libraryCategory
        }.filter { note ->
            controls.query.isBlank() || listOf(note.title, note.snippet, note.category)
                .any { it.contains(controls.query, ignoreCase = true) }
        }.sortedWith(controls.comparator())
        NotesUiState(
            notes = notes,
            selectedNote = selectedNote,
            activePane = active,
            otherPane = otherNote?.let { note ->
                PaneNote(note, slotFor(active.other).editorMode ?: previewModeFor(note.id, controls.defaultEditorMode))
            },
            categories = (library.active.map(NoteSummary::category) + library.categories.values.flatten() +
                listOfNotNull(selectedNote?.category))
                .filter(String::isNotBlank)
                .distinct(),
            managedCategories = managedCategoryList(library),
            managingCategories = controls.managingCategories,
            editorLayout = controls.editorLayout,
            splitFraction = controls.splitFraction,
            sources = allSourceIds.map { id ->
                SourceChoice(
                    id = id,
                    name = if (id == LocalSourceId) strings.getString(UiR.ui_local_source) else descriptorsById[id]?.name
                        ?: strings.getString(UiR.ui_disconnected),
                    visible = id !in controls.hiddenSourceIds,
                    connected = id in descriptorsById,
                    supportsAttachments = descriptorsById[id]?.capabilities?.attachments == true,
                    writable = descriptorsById[id]?.capabilities?.writable == true,
                    categories = descriptorsById[id]?.capabilities?.categories == true,
                )
            },
            filter = controls.filter,
            query = controls.query,
            sort = controls.sort,
            libraryView = controls.libraryView,
            groupByDate = controls.groupByDate,
            ascending = controls.ascending,
            destination = controls.destination,
            libraryCategory = controls.libraryCategory,
            pendingSyncCount = library.active.count { note -> visible(note) && note.needsSyncNotice },
            syncingSources = controls.syncingSources,
            editorMode = controls.editorMode,
            previewModes = notes.associate { it.id to previewModeFor(it.id, controls.defaultEditorMode) },
            attachmentMetadataPreference = controls.attachmentMetadataPreference,
            saving = controls.saving,
            changingSource = controls.movingNoteId != null && controls.movingNoteId == selectedNote?.id,
            userMessage = controls.userMessage,
            defaultSourceId = controls.defaultSourceId,
            sourceConnection = controls.sourceConnection,
            noteFormatPreference = controls.noteFormatPreference,
            theme = controls.theme,
            darkMode = controls.darkMode,
            defaultEditorMode = controls.defaultEditorMode,
        )
    }

    // Typing in an External File does not rebuild or sort the entire Note library.
    val state: StateFlow<NotesUiState> = combine(libraryUi, externalFiles.state) { library, file ->
        library.copy(externalFile = file)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotesUiState(
            sources = listOf(SourceChoice(LocalSourceId, strings.getString(UiR.ui_local_source), true)),
            editorLayout = controls.value.editorLayout,
            theme = controls.value.theme,
            darkMode = controls.value.darkMode,
            defaultEditorMode = controls.value.defaultEditorMode,
            libraryView = controls.value.libraryView,
            groupByDate = controls.value.groupByDate,
            notes = engine.library.value.active,
            previewModes = engine.library.value.active.associate { it.id to previewModeFor(it.id) },
        ),
    )

    init {
        viewModelScope.launch {
            engine.library.collect {
                for (open in listOf(primarySlot, secondarySlot)) {
                    val id = open.note.value?.id ?: continue
                    val current = engine.note(id) ?: continue
                    if (open.note.value?.id == id) open.note.value = current.withPendingDisplayMetadata(open)
                }
            }
        }
        if (engine is ConnectedNotesEngine) {
            viewModelScope.launch {
                try {
                    restoreConnectedSources()
                } finally {
                    sourcesReady.complete(Unit)
                }
            }
        } else {
            sourcesReady.complete(Unit)
        }
    }

    fun createNote() {
        val s = slot
        viewModelScope.launch {
            sourcesReady.await()
            flushSelectedContent(s)
            s.note.value?.id?.let { engine.close(it) }
            val sourceId = controls.value.defaultSourceId.takeIf { preferred ->
                engine.sources.value.any { it.id == preferred }
            } ?: LocalSourceId
            val id = engine.createDraft(category = controls.value.libraryCategory.orEmpty(), sourceId = sourceId)
            s.note.value = engine.openEditingSession(id)
            setSlotEditorMode(s, controls.value.defaultEditorMode)
        }
    }

    fun selectNote(id: NoteId) {
        // One Editing Session per Note: a Note already open on the other page is focused there.
        val other = slotFor(activePane.value.other)
        if (other.note.value?.id == id) {
            activatePane(activePane.value.other)
            return
        }
        val s = slot
        viewModelScope.launch {
            flushSelectedContent(s)
            s.note.value?.id?.takeIf { it != id }?.let { engine.close(it) }
            s.note.value = engine.openEditingSession(id)
            setSlotEditorMode(s, previewModeFor(id))
        }
    }

    /** Book posture shows two pages; editor actions follow the page a person last worked on. */
    fun activatePane(pane: NotePane) {
        if (pane == activePane.value) return
        slot.editorMode = controls.value.editorMode
        activePane.value = pane
        val next = slot
        controls.update { current ->
            current.copy(
                editorMode = next.editorMode ?: next.note.value?.id?.let(::previewModeFor) ?: current.defaultEditorMode,
                saving = next.contentDirty || next.metadataDirty,
            )
        }
    }

    /** Leaving book posture keeps one page: the right one, or the left one when only it was open. */
    fun setDualPages(enabled: Boolean) {
        if (enabled || secondarySlot.note.value == null) {
            if (!enabled) activatePane(NotePane.PRIMARY)
            return
        }
        viewModelScope.launch {
            flushSelectedContent(secondarySlot)
            val leftNote = secondarySlot.note.value ?: return@launch
            if (primarySlot.note.value == null) {
                primarySlot.note.value = leftNote
                primarySlot.editorMode = if (activePane.value == NotePane.SECONDARY) controls.value.editorMode else secondarySlot.editorMode
            } else {
                engine.close(leftNote.id)
            }
            secondarySlot.note.value = null
            secondarySlot.editorMode = null
            activatePane(NotePane.PRIMARY)
        }
    }

    private fun setSlotEditorMode(s: EditorSlot, mode: EditorMode) {
        s.editorMode = mode
        if (s === slot) controls.update { it.copy(editorMode = mode) }
    }

    internal fun openExternalFile(request: ExternalFileRequest) {
        viewModelScope.launch {
            try {
                externalFiles.open(request, controls.value.defaultEditorMode) {
                    for (open in listOf(primarySlot, secondarySlot)) {
                        flushSelectedContent(open)
                        open.note.value?.id?.let { engine.close(it) }
                        open.note.value = null
                    }
                    activatePane(NotePane.PRIMARY)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { controls.update { it.copy(userMessage = strings.getString(UiR.ui_the_file_couldn_t_be_opened_safely_your_current_note_was_kept)) } }
        }
    }

    fun updateExternalText(sessionKey: String, text: String) = externalFiles.updateText(sessionKey, text)

    fun setExternalEditorMode(sessionKey: String, mode: EditorMode) = externalFiles.setMode(sessionKey, mode)

    fun retryExternalSave(sessionKey: String) = externalFiles.flush(sessionKey)

    fun dismissExternalCloseWarning(sessionKey: String) = externalFiles.dismissCloseWarning(sessionKey)

    fun grantExternalEditing(sessionKey: String, uri: Uri) {
        viewModelScope.launch {
            externalFiles.enableEditing(sessionKey, ExternalFileRequest(uri, null,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        }
    }

    fun closeExternalFile(sessionKey: String, discard: Boolean = false, onClosed: () -> Unit = {}) {
        viewModelScope.launch { if (externalFiles.close(sessionKey, discard)) onClosed() }
    }

    fun copyExternalFileToSource(sessionKey: String, sourceId: SourceId) {
        viewModelScope.launch {
            externalFiles.copyIntoNotes(sessionKey) { existingId, previousText, previousTitle, title, text, mode, rememberId ->
                if (sourceId != LocalSourceId) sourcesReady.await()
                val s = slot
                flushSelectedContent(s)
                s.note.value?.id?.let { engine.close(it) }
                s.note.value = null
                val destination = engine.sources.value.firstOrNull { it.id == sourceId }
                require(destination?.capabilities?.writable == true) { strings.getString(UiR.ui_choose_a_connected_writable_source) }
                // Retain the new identity before subsequent writes so retrying never duplicates a copy.
                val existing = existingId?.let { engine.note(it) }
                // An independently edited copy must survive a retry of the file operation.
                val reusable = existing?.takeIf { candidate ->
                    candidate.syncState != NoteSyncState.NEEDS_ATTENTION &&
                        (candidate.state == NoteState.DRAFT ||
                            ((candidate.markdown == previousText || candidate.markdown == text) &&
                                (previousTitle == null || candidate.title == previousTitle)))
                }
                val id = reusable?.id
                    ?: engine.createDraft(sourceId = sourceId).also { rememberId(it, null) }
                engine.updateContent(id, text)
                engine.rename(id, title.ifBlank { "Untitled Note" })
                rememberId(id, engine.note(id)?.title)
                engine.close(id)
                val finalId = if (engine.note(id)?.sourceId != sourceId) engine.moveToSource(id, sourceId) else id
                if (finalId != id) rememberId(finalId, engine.note(finalId)?.title)
                preferences.edit().putString(previewModeKey(finalId), mode.name).apply()
                s.note.value = requireNotNull(engine.openEditingSession(finalId))
                setSlotEditorMode(s, mode)
                controls.update { it.copy(destination = LibraryDestination.NOTES, libraryCategory = null) }
                if (sourceId != LocalSourceId) scheduleSynchronization(finalId)
            }
        }
    }

    fun updateContent(markdown: String) {
        val current = selected.value ?: return
        if (markdown == current.markdown) return
        selected.value = current.copy(markdown = markdown)
        contentDirty = true
        showSaving()
        val s = slot
        s.contentSave?.cancel()
        s.contentSave = viewModelScope.launch {
            delay(CONTENT_SAVE_DELAY_MILLIS)
            saveCurrentContent(s)
        }
    }

    fun rename(title: String) {
        val current = selected.value ?: return
        if (title == current.title) return
        selected.value = current.copy(title = title)
        pendingTitle = title
        metadataDirty = true
        showSaving()
        scheduleMetadataSave()
    }

    fun toggleFavorite() {
        val id = selected.value?.id ?: return
        val s = slot
        showSaving()
        viewModelScope.launch {
            engine.toggleFavorite(id)
            if (s.note.value?.id == id) s.note.value = engine.note(id)?.withPendingDisplayMetadata(s)
            refreshSavingState()
            scheduleSynchronization(id)
        }
    }

    fun setCategory(category: String) {
        val current = selected.value ?: return
        if (category == current.category) return
        pendingCategory = category
        selected.value = current.copy(category = category)
        metadataDirty = true
        showSaving()
        scheduleMetadataSave(immediate = true)
    }

    fun importImage(uri: Uri, removePrivateMetadata: Boolean) {
        val requestedNoteId = selected.value?.id ?: return
        val s = slot
        showSaving()
        viewModelScope.launch {
            try {
                flushSelectedContent(s)
                val prepared = withContext(Dispatchers.IO) {
                    prepareImageImport(getApplication(), uri, removePrivateMetadata)
                }
                val attachment = withContext(Dispatchers.IO) {
                    engine.importManagedAttachment(
                        noteId = requestedNoteId,
                        attachment = AttachmentImport(
                            displayName = prepared.displayName,
                            mediaType = prepared.mediaType,
                            openContent = { prepared.bytes.inputStream() },
                        ),
                    )
                }
                val current = s.note.value?.takeIf { it.id == requestedNoteId }
                    ?: return@launch
                val edit = markdownEditor.insertAttachment(
                    markdown = current.markdown,
                    selection = MarkdownSelection(current.markdown.length, current.markdown.length),
                    attachment = MarkdownAttachment(
                        displayName = attachment.displayName,
                        target = attachment.markdownTarget,
                        inlineImage = true,
                    ),
                )
                withContext(Dispatchers.IO) {
                    engine.updateContent(requestedNoteId, edit.markdown)
                }
                if (s.note.value?.id == requestedNoteId) s.note.value = engine.note(requestedNoteId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                controls.update {
                    it.copy(
                        userMessage = if (failure.message?.contains(strings.getString(UiR.ui_keep_original)) == true) {
                            strings.getString(UiR.ui_this_image_format_can_only_be_added_with_keep_original)
                        } else {
                            strings.getString(UiR.ui_kgs_notes_couldn_t_add_this_image)
                        },
                    )
                }
            } finally {
                refreshSavingState()
            }
        }
    }

    fun openManagedAttachment(id: String) = engine.openManagedAttachment(AttachmentId(id))

    fun clearUserMessage() {
        controls.update { it.copy(userMessage = null) }
    }

    fun showSourceConnection() {
        controls.update {
            it.copy(sourceConnection = SourceConnectionUiState(visible = true))
        }
    }

    fun editSourceConnection(id: SourceId) {
        viewModelScope.launch {
            val account = withContext(Dispatchers.IO) {
                accountStore.loadAll().firstOrNull { it.profile.id == id }
            }
            if (account == null) {
                controls.update { it.copy(userMessage = strings.getString(UiR.ui_reconnect_this_source_by_adding_its_account_again)) }
                showSourceConnection()
                return@launch
            }
            controls.update {
                it.copy(sourceConnection = SourceConnectionUiState(
                    visible = true,
                    editingSourceId = id,
                    initialName = account.profile.name,
                    initialServerUrl = account.credentials.serverUrl,
                    initialLoginName = account.credentials.loginName,
                ))
            }
        }
    }

    fun dismissSourceConnection() {
        connectionJob?.cancel()
        connectionJob = null
        controls.update { it.copy(sourceConnection = SourceConnectionUiState()) }
    }

    fun browserLaunchHandled() {
        controls.update { current ->
            current.copy(sourceConnection = current.sourceConnection.copy(browserUrl = null))
        }
    }

    fun connectWithBrowser(sourceName: String, serverUrl: String) {
        connectionJob?.cancel()
        controls.update {
            it.copy(
                sourceConnection = it.sourceConnection.copy(
                    visible = true,
                    phase = SourceConnectionPhase.STARTING,
                    message = strings.getString(UiR.ui_contacting_nextcloud),
                ),
            )
        }
        connectionJob = viewModelScope.launch {
            try {
                val challenge = loginFlow.begin(serverUrl)
                controls.update {
                    it.copy(
                        sourceConnection = it.sourceConnection.copy(
                            visible = true,
                            phase = SourceConnectionPhase.WAITING_FOR_BROWSER,
                            browserUrl = challenge.loginUrl,
                            message = strings.getString(UiR.ui_approve_kgs_notes_in_your_browser),
                        ),
                    )
                }
                val credentials = awaitCredentials(challenge)
                finishConnection(sourceName, credentials)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showConnectionFailure(failure)
            }
        }
    }

    fun connectWithAppPassword(
        sourceName: String,
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ) {
        connectionJob?.cancel()
        controls.update {
            it.copy(
                sourceConnection = it.sourceConnection.copy(
                    visible = true,
                    phase = SourceConnectionPhase.VERIFYING,
                    message = strings.getString(UiR.ui_checking_notes_access),
                ),
            )
        }
        connectionJob = viewModelScope.launch {
            try {
                finishConnection(
                    sourceName = sourceName,
                    credentials = NextcloudCredentials(
                        serverUrl = canonicalNextcloudServerAddress(serverUrl),
                        loginName = loginName,
                        appPassword = appPassword,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showConnectionFailure(failure)
            }
        }
    }

    fun refreshSources() {
        if (manualSyncJob?.isActive == true) return
        manualSyncJob = viewModelScope.launch {
            controls.update { it.copy(syncingSources = true) }
            try {
                sourcesReady.await()
                flushSelectedContent()
                val refreshed = refreshAndRetrySources(awaitWrites = true)
                val snapshot = engine.library.value
                val pending = (snapshot.active + snapshot.trash).any {
                    it.sourceId != LocalSourceId && it.syncState in pendingSyncStates
                }
                if (!refreshed || pending) controls.update {
                    it.copy(userMessage = strings.getString(UiR.ui_some_sources_couldn_t_finish_syncing_your_changes_remain_saved_on_this_device))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controls.update { it.copy(userMessage = strings.getString(UiR.ui_kgs_notes_couldn_t_finish_syncing_please_try_again)) }
            } finally {
                controls.update { it.copy(syncingSources = false) }
            }
        }
    }

    fun startForegroundSourceRefresh() {
        if (foregroundRefreshJob?.isActive == true) return
        foregroundRefreshJob = viewModelScope.launch {
            while (isActive) {
                refreshAndRetrySources()
                delay(SOURCE_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    fun stopForegroundSourceRefresh() {
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = null
    }

    fun setDefaultSource(id: SourceId) {
        preferences.edit().putString(DEFAULT_SOURCE_KEY, id.value).apply()
        controls.update { it.copy(defaultSourceId = id) }
    }

    private fun sourceFailureMessage(failure: Throwable, fallback: Int): String = when (failure.message) {
        "Source names must be unique" -> strings.getString(UiR.ui_source_names_must_be_unique)
        "Source is no longer connected" -> strings.getString(UiR.ui_source_is_no_longer_connected)
        else -> strings.getString(fallback)
    }

    fun renameSource(id: SourceId, name: String) {
        viewModelScope.launch {
            runCatching {
                val profile = accountStore.rename(id, name)
                val account = accountStore.loadAll().first { it.profile.id == id }
                val source = NextcloudNotesSource(
                    sourceName = profile.name,
                    api = HttpNextcloudNotesApi(account.credentials),
                    sourceId = id,
                )
                nextcloudSources[id] = source
                engine.attachSource(source)
                runCatching { engine.refreshSources() }
            }.onFailure { failure ->
                controls.update { it.copy(userMessage = sourceFailureMessage(failure, UiR.ui_couldn_t_rename_this_source)) }
            }
        }
    }

    fun removeSource(id: SourceId) {
        viewModelScope.launch {
            runCatching {
                require(id != LocalSourceId)
                engine.removeSource(id)
                accountStore.remove(id)
                nextcloudSources.remove(id)
                if (controls.value.defaultSourceId == id) setDefaultSource(LocalSourceId)
                controls.update { it.copy(hiddenSourceIds = it.hiddenSourceIds - id) }
            }.onFailure { failure ->
                controls.update {
                    it.copy(userMessage = sourceFailureMessage(failure, UiR.ui_couldn_t_remove_this_source))
                }
            }
        }
    }

    fun setTheme(theme: NotesTheme) {
        preferences.edit().putString(THEME_KEY, theme.name).apply()
        controls.update { it.copy(theme = theme) }
    }

    fun setDarkMode(mode: DarkModePreference) {
        preferences.edit().putString(DARK_MODE_KEY, mode.name).apply()
        controls.update { it.copy(darkMode = mode) }
    }

    fun setDefaultEditorMode(mode: EditorMode) {
        preferences.edit().putString(DEFAULT_EDITOR_MODE_KEY, mode.name).apply()
        controls.update { it.copy(defaultEditorMode = mode) }
    }

    fun setNoteFormatPreference(preference: NoteFormatPreference) {
        preferences.edit().putString(NOTE_FORMAT_PREFERENCE_KEY, preference.name).apply()
        controls.update { it.copy(noteFormatPreference = preference) }
        val suffix = preference.nextcloudSuffix ?: return
        viewModelScope.launch {
            var failed = false
            for (source in nextcloudSources.values) {
                if (runCatching { source.setFileSuffix(suffix) }.isFailure) {
                    failed = true
                }
            }
            if (failed) {
                controls.update {
                    it.copy(userMessage = strings.getString(UiR.ui_some_sources_couldn_t_apply_the_new_note_format))
                }
            }
        }
    }

    fun changeSource(id: SourceId) {
        val current = selected.value ?: return
        if (current.sourceId == id || controls.value.movingNoteId != null) return
        controls.update { it.copy(movingNoteId = current.id) }
        val s = slot
        viewModelScope.launch {
            try {
                flushSelectedContent(s)
                val movedId = engine.moveToSource(current.id, id)
                carryPreviewMode(current.id, movedId)
                if (s.note.value?.id == current.id) {
                    s.note.value = engine.openEditingSession(movedId)?.withPendingDisplayMetadata(s)
                }
                if (id != LocalSourceId) scheduleSynchronization(movedId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = when (failure) {
                    is SourceConflictException -> strings.getString(UiR.ui_the_note_changed_on_the_server_its_source_stayed_the_same_and_your_edits_were_kept)
                    is IllegalArgumentException -> failure.message ?: strings.getString(UiR.ui_kgs_notes_couldn_t_change_this_note_s_source)
                    else -> strings.getString(UiR.ui_kgs_notes_couldn_t_change_this_note_s_source_your_note_was_kept)
                }
                controls.update { it.copy(userMessage = message) }
            } finally {
                controls.update { it.copy(movingNoteId = null) }
                refreshSavingState()
            }
        }
    }

    fun closeEditor() {
        val s = slot
        viewModelScope.launch {
            flushSelectedContent(s)
            s.note.value?.id?.let { engine.close(it) }
            s.note.value = null
        }
    }

    fun moveToTrash() {
        val id = selected.value?.id ?: return
        val s = slot
        viewModelScope.launch {
            flushSelectedContent(s)
            engine.moveToTrash(id)
            scheduleSynchronization(id)
            if (s.note.value?.id == id) s.note.value = null
        }
    }

    fun restore() {
        val id = selected.value?.id ?: return
        val s = slot
        viewModelScope.launch {
            engine.restore(id)
            if (s.note.value?.id == id) s.note.value = engine.openEditingSession(id)
            scheduleSynchronization(id)
        }
    }

    fun deletePermanently() {
        val id = selected.value?.id ?: return
        val s = slot
        viewModelScope.launch {
            engine.deletePermanently(id)
            preferences.edit().remove(previewModeKey(id)).apply()
            if (s.note.value?.id == id) s.note.value = null
        }
    }

    fun renameNote(id: NoteId, title: String) {
        viewModelScope.launch {
            runCatching { engine.rename(id, title) }
                .onSuccess { scheduleSynchronization(id) }
                .onFailure { controls.update { it.copy(userMessage = strings.getString(UiR.ui_kgs_notes_couldn_t_rename_this_note)) } }
        }
    }

    fun applyToNotes(ids: Set<NoteId>, operation: NoteSelectionOperation, value: String = "") {
        viewModelScope.launch {
            var failures = 0
            ids.forEach { id ->
                runCatching {
                    val note = engine.note(id) ?: return@runCatching
                    when (operation) {
                        NoteSelectionOperation.FAVORITE -> {
                            val desiredFavorite = value != "remove"
                            if (note.favorite != desiredFavorite) engine.toggleFavorite(id)
                        }
                        NoteSelectionOperation.CATEGORY -> engine.setCategory(id, value)
                        NoteSelectionOperation.MOVE -> {
                            val movedId = engine.moveToSource(id, SourceId(value))
                            carryPreviewMode(id, movedId)
                        }
                        NoteSelectionOperation.TRASH -> engine.moveToTrash(id)
                        NoteSelectionOperation.RESTORE -> engine.restore(id)
                        NoteSelectionOperation.DELETE -> {
                            engine.deletePermanently(id)
                            preferences.edit().remove(previewModeKey(id)).apply()
                        }
                    }
                    if (operation in setOf(
                            NoteSelectionOperation.FAVORITE,
                            NoteSelectionOperation.CATEGORY,
                            NoteSelectionOperation.TRASH,
                            NoteSelectionOperation.RESTORE,
                        ) && note.sourceId != LocalSourceId
                    ) scheduleSynchronization(id)
                }.onFailure { failures++ }
            }
            if (failures > 0) controls.update {
                it.copy(userMessage = strings.getString(UiR.ui_some_note_actions_couldn_t_be_completed_your_notes_were_kept))
            }
        }
    }

    fun setFilter(value: LibraryFilter) {
        controls.update { it.copy(filter = value, destination = LibraryDestination.NOTES) }
    }

    fun setQuery(value: String) {
        controls.update { it.copy(query = value) }
    }

    fun setEditorMode(value: EditorMode) {
        selected.value?.id?.let { id ->
            preferences.edit().putString(previewModeKey(id), value.name).apply()
        }
        slot.editorMode = value
        controls.update { it.copy(editorMode = value) }
    }

    private fun previewModeFor(id: NoteId, fallback: EditorMode = controls.value.defaultEditorMode): EditorMode = preferences
        .getString(previewModeKey(id), null)
        ?.let { stored -> EditorMode.entries.firstOrNull { it.name == stored } }
        ?: fallback

    private fun previewModeKey(id: NoteId) = "$PREVIEW_MODE_KEY_PREFIX${id.value}"

    private fun carryPreviewMode(from: NoteId, to: NoteId) {
        if (from == to) return
        val mode = preferences.getString(previewModeKey(from), null) ?: return
        preferences.edit().putString(previewModeKey(to), mode).remove(previewModeKey(from)).apply()
    }

    fun setAttachmentMetadataPreference(value: AttachmentMetadataPreference) {
        preferences.edit().putString(ATTACHMENT_METADATA_PREFERENCE_KEY, value.name).apply()
        controls.update { it.copy(attachmentMetadataPreference = value) }
    }

    fun setSort(value: LibrarySort) {
        controls.update { it.copy(sort = value) }
    }

    fun setGroupByDate(enabled: Boolean) {
        preferences.edit().putBoolean(GROUP_BY_DATE_KEY, enabled).apply()
        controls.update { it.copy(groupByDate = enabled) }
    }

    fun toggleLibraryView() {
        val next = if (controls.value.libraryView == LibraryView.LIST) LibraryView.CARDS else LibraryView.LIST
        preferences.edit().putString(LIBRARY_VIEW_KEY, next.name).apply()
        controls.update { it.copy(libraryView = next) }
    }

    fun toggleSortDirection() {
        controls.update { it.copy(ascending = !it.ascending) }
    }

    fun toggleSourceVisibility(id: SourceId) {
        controls.update { current ->
            val hidden = if (id in current.hiddenSourceIds) {
                current.hiddenSourceIds - id
            } else {
                current.hiddenSourceIds + id
            }
            current.copy(hiddenSourceIds = hidden)
        }
    }

    fun openDestination(destination: LibraryDestination) {
        controls.update { it.copy(destination = destination,
            libraryCategory = if (destination == LibraryDestination.NOTES) null else it.libraryCategory) }
    }

    fun createCategory(sourceId: SourceId, name: String) {
        viewModelScope.launch {
            try {
                engine.createCategory(sourceId, name)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                controls.update { it.copy(userMessage = strings.getString(UiR.ui_some_category_changes_couldn_t_be_completed_your_notes_were_kept_you_can_retry)) }
            }
        }
    }

    fun renameCategory(sourceId: SourceId, category: String, newCategory: String) = manageCategory(sourceId, category, newCategory)
    fun deleteCategory(sourceId: SourceId, category: String) = manageCategory(sourceId, category, null)

    private fun manageCategory(sourceId: SourceId, category: String, replacement: String?) {
        if (controls.value.managingCategories) return
        controls.update { it.copy(managingCategories = true) }
        viewModelScope.launch {
            try {
                saveCurrentContent()
                savePendingMetadata()
                val target = replacement?.let { com.kgs.notes.engine.normalizedCategoryPath(it) }
                if (target == null) engine.deleteCategory(sourceId, category)
                else engine.renameCategory(sourceId, category, target)
                controls.update { current ->
                    val path = current.libraryCategory
                    current.copy(libraryCategory = if (path != null && (path == category || path.startsWith("$category/"))) {
                        if (target == null) "" else target + path.removePrefix(category)
                    } else path)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                controls.update { it.copy(userMessage = strings.getString(UiR.ui_some_category_changes_couldn_t_be_completed_your_notes_were_kept_you_can_retry)) }
            } finally {
                for (open in listOf(primarySlot, secondarySlot)) {
                    open.note.value?.id?.let { id -> open.note.value = engine.note(id)?.withPendingDisplayMetadata(open) }
                }
                (engine.library.value.active + engine.library.value.trash)
                    .filter { it.sourceId == sourceId && it.syncState == NoteSyncState.SAVED_LOCALLY }
                    .forEach { scheduleSynchronization(it.id) }
                controls.update { it.copy(managingCategories = false) }
            }
        }
    }

    fun setEditorLayout(value: EditorLayoutPreference) {
        preferences.edit().putString(EDITOR_LAYOUT_KEY, value.name).apply()
        controls.update { it.copy(editorLayout = value) }
    }

    fun setSplitFraction(value: Float) {
        preferences.edit().putFloat(SPLIT_FRACTION_KEY, value).apply()
        controls.update { it.copy(splitFraction = value) }
    }

    fun refreshLocale(locale: java.util.Locale) { controls.update { it.copy(localeTag = locale.toLanguageTag()) } }

    fun openCategory(category: String?) {
        controls.update { it.copy(destination = LibraryDestination.NOTES, libraryCategory = category, filter = LibraryFilter.ALL, query = "") }
    }

    fun flushPendingContent() {
        externalFiles.flush()
        for (open in listOf(primarySlot, secondarySlot)) {
            if (open.note.value == null) continue
            open.contentSave?.cancel()
            open.metadataSave?.cancel()
            open.contentSave = viewModelScope.launch {
                saveCurrentContent(open)
                savePendingMetadata(open)
            }
        }
    }

    private suspend fun flushSelectedContent(s: EditorSlot = slot) {
        s.contentSave?.cancel()
        s.metadataSave?.cancel()
        saveCurrentContent(s)
        savePendingMetadata(s)
    }

    private suspend fun saveCurrentContent(s: EditorSlot = slot) {
        if (!s.contentDirty) {
            refreshSavingState()
            return
        }
        val current = s.note.value ?: return
        engine.updateContent(current.id, current.markdown)
        val displayed = s.note.value
        s.contentDirty = displayed?.id == current.id && displayed.markdown != current.markdown
        s.note.value = engine.note(current.id)
            ?.copy(markdown = if (s.contentDirty) displayed?.markdown.orEmpty() else current.markdown)
            ?.withPendingDisplayMetadata(s)
        refreshSavingState()
        scheduleSynchronization(current.id)
    }

    private fun scheduleMetadataSave(immediate: Boolean = false) {
        val s = slot
        s.metadataSave?.cancel()
        s.metadataSave = viewModelScope.launch {
            if (!immediate) delay(METADATA_SAVE_DELAY_MILLIS)
            try {
                savePendingMetadata(s)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controls.update { it.copy(userMessage = strings.getString(UiR.ui_kgs_notes_couldn_t_save_this_change_please_try_again)) }
            }
        }
    }

    private suspend fun savePendingMetadata(s: EditorSlot = slot) {
        if (!s.metadataDirty) {
            refreshSavingState()
            return
        }
        val id = s.note.value?.id ?: return
        val title = s.pendingTitle
        val category = s.pendingCategory
        if (title != null) engine.rename(id, title)
        if (category != null) engine.setCategory(id, category)
        if (s.pendingTitle == title) s.pendingTitle = null
        if (s.pendingCategory == category) s.pendingCategory = null
        s.metadataDirty = s.pendingTitle != null || s.pendingCategory != null
        s.note.value = engine.note(id)?.withPendingDisplayMetadata(s)
        refreshSavingState()
        scheduleSynchronization(id)
    }

    private fun scheduleSynchronization(id: NoteId): Job? {
        val snapshot = engine.library.value
        val note = (snapshot.active + snapshot.trash).firstOrNull { it.id == id }
        if (note?.sourceId == null || note.sourceId == LocalSourceId) return null
        // An issued create must finish and retain its remote identity. Subsequent
        // edits are picked up by this job after the current request completes.
        syncJobs[id]?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
            while (isActive) {
                val before = engine.note(id) ?: break
                engine.synchronize(id)
                val after = engine.note(id) ?: break
                for (open in listOf(primarySlot, secondarySlot)) {
                    if (open.note.value?.id == id) open.note.value = after.withPendingDisplayMetadata(open)
                }
                // Stop after a network failure; foreground refresh retries it.
                // Continue when an edit arrived while the preceding upload ran.
                if (after.syncState != NoteSyncState.SAVED_LOCALLY || after == before) break
            }
        }.also { syncJobs[id] = it }
    }

    private suspend fun refreshAndRetrySources(awaitWrites: Boolean = false): Boolean {
        val refreshed = try {
            engine.refreshSources()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Durable pending changes can still be retried on connected Sources.
            false
        }
        val snapshot = engine.library.value
        val jobs = (snapshot.active + snapshot.trash)
            .filter { it.sourceId != LocalSourceId && it.syncState == NoteSyncState.SAVED_LOCALLY }
            .mapNotNull { scheduleSynchronization(it.id) }
        if (awaitWrites) (jobs + syncJobs.values.toList()).distinct().joinAll()
        return refreshed
    }

    private suspend fun restoreConnectedSources() {
        val accounts = withContext(Dispatchers.IO) { accountStore.loadAll() }
        accounts.forEach { account ->
            val source = account.toSource()
            nextcloudSources[source.descriptor.id] = source
            engine.attachSource(source)
        }
        if (accounts.isNotEmpty()) {
            refreshAndRetrySources()
            applyPreferredNoteFormat(nextcloudSources.values)
        }
    }

    private suspend fun awaitCredentials(challenge: NextcloudLoginChallenge): NextcloudCredentials {
        repeat(LOGIN_POLL_ATTEMPTS) {
            loginFlow.poll(challenge)?.let { return it }
            delay(LOGIN_POLL_INTERVAL_MILLIS)
        }
        throw NextcloudConnectionException(com.kgs.notes.nextcloud.NextcloudConnectionFailure.SERVER_REJECTED_REQUEST)
    }

    private suspend fun finishConnection(sourceName: String, credentials: NextcloudCredentials) {
        controls.update { current ->
            current.copy(
                sourceConnection = current.sourceConnection.copy(
                    phase = SourceConnectionPhase.VERIFYING,
                    browserUrl = null,
                    message = strings.getString(UiR.ui_checking_notes_access),
                ),
            )
        }
        val profile = accountStore.profileFor(sourceName, credentials)
        val editingSourceId = controls.value.sourceConnection.editingSourceId
        require(editingSourceId == null || editingSourceId == profile.id) {
            strings.getString(UiR.ui_the_account_belongs_to_a_different_source)
        }
        val source = NextcloudNotesSource(
            sourceName = profile.name,
            api = HttpNextcloudNotesApi(credentials),
            sourceId = profile.id,
        )
        source.refresh()
        applyPreferredNoteFormat(listOf(source))
        withContext(Dispatchers.IO) { accountStore.save(profile.name, credentials) }
        nextcloudSources[profile.id] = source
        engine.attachSource(source)
        refreshAndRetrySources()
        controls.update { current ->
            current.copy(
                sourceConnection = current.sourceConnection.copy(
                    visible = true,
                    phase = SourceConnectionPhase.CONNECTED,
                    message = strings.getString(UiR.ui_value_is_connected, profile.name),
                ),
            )
        }
    }

    private fun showConnectionFailure(failure: Exception) {
        val message = when (failure) {
            is UnsupportedNotesApi -> strings.getString(UiR.ui_this_server_needs_nextcloud_notes_api_value_or_newer, failure.minimumVersion)
            is NextcloudApiException -> if (failure.statusCode == 401) {
                strings.getString(UiR.ui_nextcloud_rejected_these_credentials)
            } else {
                strings.getString(UiR.ui_nextcloud_couldn_t_complete_the_request)
            }
            is NextcloudConnectionException -> when (failure.failure) {
                com.kgs.notes.nextcloud.NextcloudConnectionFailure.INSECURE_SERVER ->
                    strings.getString(UiR.ui_kgs_notes_only_connects_over_https)
                com.kgs.notes.nextcloud.NextcloudConnectionFailure.INVALID_SERVER_URL ->
                    strings.getString(UiR.ui_enter_a_valid_nextcloud_server_address)
                else -> strings.getString(UiR.ui_kgs_notes_couldn_t_connect_to_this_server)
            }
            is IllegalArgumentException -> failure.message ?: strings.getString(UiR.ui_kgs_notes_couldn_t_update_this_source)
            else -> strings.getString(UiR.ui_kgs_notes_couldn_t_connect_to_this_server)
        }
        controls.update { current ->
            current.copy(
                sourceConnection = current.sourceConnection.copy(
                    visible = true,
                    phase = SourceConnectionPhase.ERROR,
                    message = message,
                ),
            )
        }
    }

    private fun ConnectedNextcloudAccount.toSource() = NextcloudNotesSource(
        sourceName = profile.name,
        api = HttpNextcloudNotesApi(credentials),
        sourceId = profile.id,
    )

    private suspend fun applyPreferredNoteFormat(sources: Collection<NextcloudNotesSource>) {
        val suffix = controls.value.noteFormatPreference.nextcloudSuffix ?: return
        var failed = false
        for (source in sources) {
            if (runCatching { source.setFileSuffix(suffix) }.isFailure) {
                failed = true
            }
        }
        if (failed) {
            controls.update {
                it.copy(userMessage = strings.getString(UiR.ui_a_source_connected_but_its_note_format_couldn_t_be_changed))
            }
        }
    }

    private fun showSaving() {
        controls.update { it.copy(saving = true) }
    }

    private fun refreshSavingState() {
        controls.update { it.copy(saving = contentDirty || metadataDirty) }
    }

    private fun Note.withPendingDisplayMetadata(s: EditorSlot = slot): Note = copy(
        markdown = s.note.value
            ?.takeIf { it.id == id && s.contentDirty }
            ?.markdown
            ?: markdown,
        title = s.pendingTitle ?: title,
        category = s.pendingCategory ?: category,
    )

    private fun LibrarySnapshot.forFilter(filter: LibraryFilter): List<NoteSummary> = when (filter) {
        LibraryFilter.ALL -> active
        LibraryFilter.FAVORITES -> favorites
        // Notes move between these filters only when syncing actually stalls,
        // not while an ordinary edit is on its way to the Source.
        LibraryFilter.LOCAL -> active.filter { note -> note.sourceId == LocalSourceId || note.needsSyncNotice }
        LibraryFilter.SERVER -> active.filter { note -> note.sourceId != LocalSourceId && !note.needsSyncNotice }
    }

    private companion object {
        const val PREFERENCES_NAME = "kgs_notes_preferences"
        const val ATTACHMENT_METADATA_PREFERENCE_KEY = "attachment_metadata_preference"
        const val DEFAULT_SOURCE_KEY = "default_source"
        const val NOTE_FORMAT_PREFERENCE_KEY = "note_format_preference"
        const val PREVIEW_MODE_KEY_PREFIX = "preview_mode_"
        const val LIBRARY_VIEW_KEY = "library_view"
        const val GROUP_BY_DATE_KEY = "group_by_date"
        const val THEME_KEY = "app_theme"
        const val DARK_MODE_KEY = "dark_mode"
        const val DEFAULT_EDITOR_MODE_KEY = "default_editor_mode"
        const val EDITOR_LAYOUT_KEY = "editor_layout"
        const val SPLIT_FRACTION_KEY = "split_fraction"
        const val CONTENT_SAVE_DELAY_MILLIS = 120L
        const val METADATA_SAVE_DELAY_MILLIS = 300L
        const val LOGIN_POLL_INTERVAL_MILLIS = 1_000L
        const val LOGIN_POLL_ATTEMPTS = 1_200
        const val SOURCE_REFRESH_INTERVAL_MILLIS = 5_000L
    }

    private data class UiControls(
        val filter: LibraryFilter = LibraryFilter.ALL,
        val query: String = "",
        val sort: LibrarySort = LibrarySort.LAST_EDITED,
        val libraryView: LibraryView = LibraryView.LIST,
        val groupByDate: Boolean = false,
        val ascending: Boolean = false,
        val destination: LibraryDestination = LibraryDestination.NOTES,
        val libraryCategory: String? = null,
        val hiddenSourceIds: Set<SourceId> = emptySet(),
        val editorMode: EditorMode = EditorMode.RICH,
        val attachmentMetadataPreference: AttachmentMetadataPreference = AttachmentMetadataPreference.ASK_EVERY_TIME,
        val saving: Boolean = false,
        val syncingSources: Boolean = false,
        val movingNoteId: NoteId? = null,
        val userMessage: String? = null,
        val defaultSourceId: SourceId = LocalSourceId,
        val sourceConnection: SourceConnectionUiState = SourceConnectionUiState(),
        val noteFormatPreference: NoteFormatPreference = NoteFormatPreference.MARKDOWN,
        val theme: NotesTheme = NotesTheme.KGS_NOTES,
        val darkMode: DarkModePreference = DarkModePreference.SYSTEM,
        val defaultEditorMode: EditorMode = EditorMode.RICH,
        val localeTag: String = java.util.Locale.getDefault().toLanguageTag(),
        val managingCategories: Boolean = false,
        val editorLayout: EditorLayoutPreference = EditorLayoutPreference.AUTOMATIC,
        val splitFraction: Float = DefaultSplitFraction,
    ) {
        fun comparator(): Comparator<NoteSummary> {
            val base = when (sort) {
                LibrarySort.LAST_EDITED -> compareBy(NoteSummary::updatedAt)
                LibrarySort.LAST_CREATED -> compareBy(NoteSummary::createdAt)
                LibrarySort.ALPHABETICAL -> titleComparator(groupByDate, java.util.Locale.forLanguageTag(localeTag))
            }
            return if (ascending) base else base.reversed()
        }
    }

    private val pendingSyncStates = setOf(
        NoteSyncState.SAVED_LOCALLY,
        NoteSyncState.SYNCING,
        NoteSyncState.NEEDS_ATTENTION,
    )
}

/**
 * Pending changes on their way to a Source are normal while editing. Only a failed
 * attempt or a Needs Attention state tells the person that syncing has stalled.
 */
internal val NoteSummary.needsSyncNotice: Boolean
    get() = sourceId != LocalSourceId && (
        syncState == NoteSyncState.NEEDS_ATTENTION ||
            (syncFailed && syncState != NoteSyncState.SYNCED)
        )

internal fun NotesUiState.paneNote(pane: NotePane): Note? = if (pane == activePane) selectedNote else otherPane?.note

/** The state one page's editor shows, whether or not editor actions currently apply to it. */
internal fun NotesUiState.forPane(pane: NotePane): NotesUiState = if (pane == activePane) this else copy(
    selectedNote = otherPane?.note,
    editorMode = otherPane?.editorMode ?: editorMode,
    saving = false,
    changingSource = false,
)
