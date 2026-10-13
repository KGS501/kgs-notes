package com.kgs.notes

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
enum class DarkModePreference(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class NoteFormatPreference(val label: String, val nextcloudSuffix: String?) {
    MARKDOWN("Markdown (.md)", ".md"),
    TEXT("Text (.txt)", ".txt"),
    FOLLOW_SOURCE("Follow Source", null),
}

data class SourceChoice(
    val id: SourceId,
    val name: String,
    val visible: Boolean,
    val connected: Boolean = true,
    val supportsAttachments: Boolean = id == LocalSourceId,
    val writable: Boolean = true,
)

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
    constructor(application: Application) : this(
        application = application,
        engine = ConnectedNotesEngine.open(
            localRoot = application.filesDir.resolve("local-source").toPath(),
            workingCopyRoot = application.noBackupFilesDir.resolve("working-copies").toPath(),
        ),
    )
    private val selected = MutableStateFlow<Note?>(null)
    private val controls = MutableStateFlow(
        UiControls(
            groupByDate = preferences.getBoolean(GROUP_BY_DATE_KEY, false),
            theme = preferences.getString(THEME_KEY, null)
                ?.let { stored -> NotesTheme.entries.firstOrNull { it.name == stored } }
                ?: NotesTheme.KGS_NOTES,
            darkMode = preferences.getString(DARK_MODE_KEY, null)
                ?.let { stored -> DarkModePreference.entries.firstOrNull { it.name == stored } }
                ?: DarkModePreference.SYSTEM,
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
    private var contentSave: Job? = null
    private var metadataSave: Job? = null
    private var pendingTitle: String? = null
    private var pendingCategory: String? = null
    private var contentDirty = false
    private var metadataDirty = false
    private var connectionJob: Job? = null
    private var foregroundRefreshJob: Job? = null
    private var manualSyncJob: Job? = null
    private val syncJobs = mutableMapOf<NoteId, Job>()
    private val nextcloudSources = linkedMapOf<SourceId, NextcloudNotesSource>()
    private val sourcesReady = CompletableDeferred<Unit>()
    private val markdownEditor = DefaultMarkdownEditor()
    private val externalFiles = ExternalFileSession(application, viewModelScope)

    private val libraryUi = combine(
        engine.library,
        engine.sources,
        selected,
        controls,
    ) { library, sourceDescriptors, selectedNote, controls ->
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
            categories = (library.active.map(NoteSummary::category) + listOfNotNull(selectedNote?.category))
                .filter(String::isNotBlank)
                .distinct(),
            sources = allSourceIds.map { id ->
                SourceChoice(
                    id = id,
                    name = descriptorsById[id]?.name
                        ?: if (id == LocalSourceId) "Local Source" else "Disconnected Source",
                    visible = id !in controls.hiddenSourceIds,
                    connected = id in descriptorsById,
                    supportsAttachments = descriptorsById[id]?.capabilities?.attachments == true,
                    writable = descriptorsById[id]?.capabilities?.writable == true,
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
            pendingSyncCount = library.active.count { note ->
                visible(note) && note.sourceId != LocalSourceId && note.syncState in pendingSyncStates
            },
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
                val id = selected.value?.id ?: return@collect
                val current = engine.note(id) ?: return@collect
                if (selected.value?.id == id) selected.value = current.withPendingDisplayMetadata()
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
        viewModelScope.launch {
            sourcesReady.await()
            flushSelectedContent()
            selected.value?.id?.let { engine.close(it) }
            val sourceId = controls.value.defaultSourceId.takeIf { preferred ->
                engine.sources.value.any { it.id == preferred }
            } ?: LocalSourceId
            val id = engine.createDraft(category = controls.value.libraryCategory.orEmpty(), sourceId = sourceId)
            selected.value = engine.openEditingSession(id)
            controls.update { it.copy(editorMode = it.defaultEditorMode) }
        }
    }

    fun selectNote(id: NoteId) {
        viewModelScope.launch {
            flushSelectedContent()
            selected.value?.id?.takeIf { it != id }?.let { engine.close(it) }
            selected.value = engine.openEditingSession(id)
            controls.update { it.copy(editorMode = previewModeFor(id)) }
        }
    }

    internal fun openExternalFile(request: ExternalFileRequest) {
        viewModelScope.launch {
            try {
                externalFiles.open(request, controls.value.defaultEditorMode) {
                    flushSelectedContent()
                    selected.value?.id?.let { engine.close(it) }
                    selected.value = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { controls.update { it.copy(userMessage = "The file couldn't be opened safely. Your current Note was kept.") } }
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
                flushSelectedContent()
                selected.value?.id?.let { engine.close(it) }
                selected.value = null
                val destination = engine.sources.value.firstOrNull { it.id == sourceId }
                require(destination?.capabilities?.writable == true) { "Choose a connected, writable Source" }
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
                selected.value = requireNotNull(engine.openEditingSession(finalId))
                controls.update { it.copy(editorMode = mode, destination = LibraryDestination.NOTES, libraryCategory = null) }
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
        contentSave?.cancel()
        contentSave = viewModelScope.launch {
            delay(CONTENT_SAVE_DELAY_MILLIS)
            saveCurrentContent()
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
        showSaving()
        viewModelScope.launch {
            engine.toggleFavorite(id)
            selected.value = engine.note(id)?.withPendingDisplayMetadata()
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
        showSaving()
        viewModelScope.launch {
            try {
                flushSelectedContent()
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
                val current = selected.value?.takeIf { it.id == requestedNoteId }
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
                selected.value = engine.note(requestedNoteId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                controls.update {
                    it.copy(
                        userMessage = if (failure.message?.contains("Keep original") == true) {
                            "This image format can only be added with Keep original."
                        } else {
                            "KGS Notes couldn't add this image."
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
                controls.update { it.copy(userMessage = "Reconnect this Source by adding its account again.") }
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
                    message = "Contacting Nextcloud…",
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
                            message = "Approve KGS Notes in your browser.",
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
                    message = "Checking Notes access…",
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
                    it.copy(userMessage = "Some Sources couldn't finish syncing. Your changes remain saved on this device.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controls.update { it.copy(userMessage = "KGS Notes couldn't finish syncing. Please try again.") }
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
                controls.update { it.copy(userMessage = failure.message ?: "Couldn't rename this Source.") }
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
                    it.copy(userMessage = failure.message ?: "Couldn't remove this Source.")
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
                    it.copy(userMessage = "Some Sources couldn't apply the new Note format.")
                }
            }
        }
    }

    fun changeSource(id: SourceId) {
        val current = selected.value ?: return
        if (current.sourceId == id || controls.value.movingNoteId != null) return
        controls.update { it.copy(movingNoteId = current.id) }
        viewModelScope.launch {
            try {
                flushSelectedContent()
                val movedId = engine.moveToSource(current.id, id)
                carryPreviewMode(current.id, movedId)
                if (selected.value?.id == current.id) {
                    selected.value = engine.openEditingSession(movedId)?.withPendingDisplayMetadata()
                }
                if (id != LocalSourceId) scheduleSynchronization(movedId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = when (failure) {
                    is SourceConflictException -> "The Note changed on the server. Its Source stayed the same, and your edits were kept."
                    is IllegalArgumentException -> failure.message ?: "KGS Notes couldn't change this Note's Source."
                    else -> "KGS Notes couldn't change this Note's Source. Your Note was kept."
                }
                controls.update { it.copy(userMessage = message) }
            } finally {
                controls.update { it.copy(movingNoteId = null) }
                refreshSavingState()
            }
        }
    }

    fun closeEditor() {
        viewModelScope.launch {
            flushSelectedContent()
            selected.value?.id?.let { engine.close(it) }
            selected.value = null
        }
    }

    fun moveToTrash() {
        val id = selected.value?.id ?: return
        viewModelScope.launch {
            flushSelectedContent()
            engine.moveToTrash(id)
            scheduleSynchronization(id)
            selected.value = null
        }
    }

    fun restore() {
        val id = selected.value?.id ?: return
        viewModelScope.launch {
            engine.restore(id)
            selected.value = engine.openEditingSession(id)
            scheduleSynchronization(id)
        }
    }

    fun deletePermanently() {
        val id = selected.value?.id ?: return
        viewModelScope.launch {
            engine.deletePermanently(id)
            preferences.edit().remove(previewModeKey(id)).apply()
            selected.value = null
        }
    }

    fun renameNote(id: NoteId, title: String) {
        viewModelScope.launch {
            runCatching { engine.rename(id, title) }
                .onSuccess { scheduleSynchronization(id) }
                .onFailure { controls.update { it.copy(userMessage = "KGS Notes couldn't rename this Note.") } }
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
                it.copy(userMessage = "$failures Note action${if (failures == 1) "" else "s"} couldn't be completed.")
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
        controls.update { it.copy(destination = destination, libraryCategory = if (destination == LibraryDestination.NOTES) null else it.libraryCategory) }
    }

    fun openCategory(category: String?) {
        controls.update { it.copy(destination = LibraryDestination.NOTES, libraryCategory = category, filter = LibraryFilter.ALL, query = "") }
    }

    fun flushPendingContent() {
        externalFiles.flush()
        if (selected.value == null) return
        contentSave?.cancel()
        metadataSave?.cancel()
        contentSave = viewModelScope.launch {
            saveCurrentContent()
            savePendingMetadata()
        }
    }

    private suspend fun flushSelectedContent() {
        contentSave?.cancel()
        metadataSave?.cancel()
        saveCurrentContent()
        savePendingMetadata()
    }

    private suspend fun saveCurrentContent() {
        if (!contentDirty) {
            refreshSavingState()
            return
        }
        val current = selected.value ?: return
        engine.updateContent(current.id, current.markdown)
        val displayed = selected.value
        contentDirty = displayed?.id == current.id && displayed.markdown != current.markdown
        selected.value = engine.note(current.id)
            ?.copy(markdown = if (contentDirty) displayed?.markdown.orEmpty() else current.markdown)
            ?.withPendingDisplayMetadata()
        refreshSavingState()
        scheduleSynchronization(current.id)
    }

    private fun scheduleMetadataSave(immediate: Boolean = false) {
        metadataSave?.cancel()
        metadataSave = viewModelScope.launch {
            if (!immediate) delay(METADATA_SAVE_DELAY_MILLIS)
            try {
                savePendingMetadata()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controls.update { it.copy(userMessage = "KGS Notes couldn't save this change. Please try again.") }
            }
        }
    }

    private suspend fun savePendingMetadata() {
        if (!metadataDirty) {
            refreshSavingState()
            return
        }
        val id = selected.value?.id ?: return
        val title = pendingTitle
        val category = pendingCategory
        if (title != null) engine.rename(id, title)
        if (category != null) engine.setCategory(id, category)
        if (pendingTitle == title) pendingTitle = null
        if (pendingCategory == category) pendingCategory = null
        metadataDirty = pendingTitle != null || pendingCategory != null
        selected.value = engine.note(id)?.withPendingDisplayMetadata()
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
                if (selected.value?.id == id) selected.value = after.withPendingDisplayMetadata()
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
                    message = "Checking Notes access…",
                ),
            )
        }
        val profile = accountStore.profileFor(sourceName, credentials)
        val editingSourceId = controls.value.sourceConnection.editingSourceId
        require(editingSourceId == null || editingSourceId == profile.id) {
            "The account belongs to a different Source"
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
                    message = "${profile.name} is connected.",
                ),
            )
        }
    }

    private fun showConnectionFailure(failure: Exception) {
        val message = when (failure) {
            is UnsupportedNotesApi -> "This server needs Nextcloud Notes API ${failure.minimumVersion} or newer."
            is NextcloudApiException -> if (failure.statusCode == 401) {
                "Nextcloud rejected these credentials."
            } else {
                "Nextcloud couldn't complete the request."
            }
            is NextcloudConnectionException -> when (failure.failure) {
                com.kgs.notes.nextcloud.NextcloudConnectionFailure.INSECURE_SERVER ->
                    "KGS Notes only connects over HTTPS."
                com.kgs.notes.nextcloud.NextcloudConnectionFailure.INVALID_SERVER_URL ->
                    "Enter a valid Nextcloud server address."
                else -> "KGS Notes couldn't connect to this server."
            }
            is IllegalArgumentException -> failure.message ?: "KGS Notes couldn't update this Source."
            else -> "KGS Notes couldn't connect to this server."
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
                it.copy(userMessage = "A Source connected, but its Note format couldn't be changed.")
            }
        }
    }

    private fun showSaving() {
        controls.update { it.copy(saving = true) }
    }

    private fun refreshSavingState() {
        controls.update { it.copy(saving = contentDirty || metadataDirty) }
    }

    private fun Note.withPendingDisplayMetadata(): Note = copy(
        markdown = selected.value
            ?.takeIf { it.id == id && contentDirty }
            ?.markdown
            ?: markdown,
        title = pendingTitle ?: title,
        category = pendingCategory ?: category,
    )

    private fun LibrarySnapshot.forFilter(filter: LibraryFilter): List<NoteSummary> = when (filter) {
        LibraryFilter.ALL -> active
        LibraryFilter.FAVORITES -> favorites
        LibraryFilter.LOCAL -> active.filter { note ->
            note.sourceId == LocalSourceId || note.syncState != NoteSyncState.SYNCED
        }
        LibraryFilter.SERVER -> active.filter { note ->
            note.sourceId != LocalSourceId && note.syncState == NoteSyncState.SYNCED
        }
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
    ) {
        fun comparator(): Comparator<NoteSummary> {
            val base = when (sort) {
                LibrarySort.LAST_EDITED -> compareBy(NoteSummary::updatedAt)
                LibrarySort.LAST_CREATED -> compareBy(NoteSummary::createdAt)
                LibrarySort.ALPHABETICAL -> compareBy { it.title.lowercase() }
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
