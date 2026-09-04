package com.kgs.notes

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
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
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.NotesEngine
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.SourceId
import com.kgs.notes.engine.SourceDescriptor
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

enum class LibraryDestination {
    NOTES,
    TRASH,
    SETTINGS,
}

enum class AttachmentMetadataPreference {
    ASK_EVERY_TIME,
    REMOVE_PRIVATE_METADATA,
    KEEP_ORIGINAL,
}

enum class NoteFormatPreference(val label: String, val nextcloudSuffix: String?) {
    MARKDOWN("Markdown (.md)", ".md"),
    TEXT("Text (.txt)", ".txt"),
    FOLLOW_SOURCE("Follow Source", null),
}

data class SourceChoice(val id: SourceId, val name: String, val visible: Boolean)

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
)

data class NotesUiState(
    val notes: List<NoteSummary> = emptyList(),
    val selectedNote: Note? = null,
    val categories: List<String> = emptyList(),
    val sources: List<SourceChoice> = listOf(SourceChoice(LocalSourceId, "Local Source", true)),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.LAST_EDITED,
    val ascending: Boolean = false,
    val destination: LibraryDestination = LibraryDestination.NOTES,
    val pendingSyncCount: Int = 0,
    val editorMode: EditorMode = EditorMode.RICH,
    val attachmentMetadataPreference: AttachmentMetadataPreference = AttachmentMetadataPreference.ASK_EVERY_TIME,
    val saving: Boolean = false,
    val userMessage: String? = null,
    val defaultSourceId: SourceId = LocalSourceId,
    val sourceConnection: SourceConnectionUiState = SourceConnectionUiState(),
    val noteFormatPreference: NoteFormatPreference = NoteFormatPreference.MARKDOWN,
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
    private val syncJobs = mutableMapOf<NoteId, Job>()
    private val nextcloudSources = linkedMapOf<SourceId, NextcloudNotesSource>()
    private val sourcesReady = CompletableDeferred<Unit>()
    private val markdownEditor = DefaultMarkdownEditor()

    val state: StateFlow<NotesUiState> = combine(
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
            controls.query.isBlank() || listOf(note.title, note.snippet, note.category)
                .any { it.contains(controls.query, ignoreCase = true) }
        }.sortedWith(controls.comparator())
        NotesUiState(
            notes = notes,
            selectedNote = selectedNote,
            categories = library.active
                .map(NoteSummary::category)
                .filter(String::isNotBlank)
                .distinct(),
            sources = allSourceIds.map { id ->
                SourceChoice(
                    id = id,
                    name = descriptorsById[id]?.name
                        ?: if (id == LocalSourceId) "Local Source" else "Disconnected Source",
                    visible = id !in controls.hiddenSourceIds,
                )
            },
            filter = controls.filter,
            query = controls.query,
            sort = controls.sort,
            ascending = controls.ascending,
            destination = controls.destination,
            pendingSyncCount = library.active.count { note ->
                visible(note) && note.sourceId != LocalSourceId && note.syncState in pendingSyncStates
            },
            editorMode = controls.editorMode,
            attachmentMetadataPreference = controls.attachmentMetadataPreference,
            saving = controls.saving,
            userMessage = controls.userMessage,
            defaultSourceId = controls.defaultSourceId,
            sourceConnection = controls.sourceConnection,
            noteFormatPreference = controls.noteFormatPreference,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotesUiState(notes = engine.library.value.active),
    )

    init {
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
            val sourceId = controls.value.defaultSourceId.takeIf { preferred ->
                engine.sources.value.any { it.id == preferred }
            } ?: LocalSourceId
            val id = engine.createDraft(sourceId = sourceId)
            selected.value = engine.note(id)
            controls.update { it.copy(editorMode = EditorMode.RICH) }
        }
    }

    fun selectNote(id: NoteId) {
        viewModelScope.launch {
            flushSelectedContent()
            selected.value = engine.note(id)
            controls.update { it.copy(editorMode = EditorMode.RICH) }
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
        selected.value = current.copy(category = category)
        pendingCategory = category
        metadataDirty = true
        showSaving()
        scheduleMetadataSave()
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
                sourceConnection = SourceConnectionUiState(
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
                        sourceConnection = SourceConnectionUiState(
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
                sourceConnection = SourceConnectionUiState(
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
        viewModelScope.launch {
            runCatching { engine.refreshSources() }
        }
    }

    fun startForegroundSourceRefresh() {
        if (foregroundRefreshJob?.isActive == true) return
        foregroundRefreshJob = viewModelScope.launch {
            while (isActive) {
                runCatching { engine.refreshSources() }
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
        if (current.sourceId == id) return
        showSaving()
        viewModelScope.launch {
            try {
                flushSelectedContent()
                val movedId = engine.moveToSource(current.id, id)
                selected.value = engine.note(movedId)
                if (id != LocalSourceId) scheduleSynchronization(movedId)
            } catch (_: Exception) {
                controls.update { it.copy(userMessage = "KGS Notes couldn't change this Note's Source.") }
            } finally {
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
            selected.value = engine.note(id)
        }
    }

    fun deletePermanently() {
        val id = selected.value?.id ?: return
        viewModelScope.launch {
            engine.deletePermanently(id)
            selected.value = null
        }
    }

    fun setFilter(value: LibraryFilter) {
        controls.update { it.copy(filter = value, destination = LibraryDestination.NOTES) }
    }

    fun setQuery(value: String) {
        controls.update { it.copy(query = value) }
    }

    fun setEditorMode(value: EditorMode) {
        controls.update { it.copy(editorMode = value) }
    }

    fun setAttachmentMetadataPreference(value: AttachmentMetadataPreference) {
        preferences.edit().putString(ATTACHMENT_METADATA_PREFERENCE_KEY, value.name).apply()
        controls.update { it.copy(attachmentMetadataPreference = value) }
    }

    fun setSort(value: LibrarySort) {
        controls.update { it.copy(sort = value) }
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
        controls.update { it.copy(destination = destination) }
    }

    fun flushPendingContent() {
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

    private fun scheduleMetadataSave() {
        metadataSave?.cancel()
        metadataSave = viewModelScope.launch {
            delay(METADATA_SAVE_DELAY_MILLIS)
            savePendingMetadata()
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

    private fun scheduleSynchronization(id: NoteId) {
        val note = runCatching { engine.library.value.active.firstOrNull { it.id == id } }.getOrNull()
        if (note?.sourceId == null || note.sourceId == LocalSourceId) return
        syncJobs[id]?.cancel()
        syncJobs[id] = viewModelScope.launch {
            engine.synchronize(id)
            if (selected.value?.id == id) selected.value = engine.note(id)?.withPendingDisplayMetadata()
        }
    }

    private suspend fun restoreConnectedSources() {
        val accounts = withContext(Dispatchers.IO) { accountStore.loadAll() }
        accounts.forEach { account ->
            val source = account.toSource()
            nextcloudSources[source.descriptor.id] = source
            engine.attachSource(source)
        }
        if (accounts.isNotEmpty()) {
            runCatching { engine.refreshSources() }
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
        engine.refreshSources()
        controls.update { current ->
            current.copy(
                sourceConnection = SourceConnectionUiState(
                    visible = true,
                    phase = SourceConnectionPhase.CONNECTED,
                    message = "${profile.name} is connected.",
                ),
            )
        }
    }

    private fun showConnectionFailure(failure: Exception) {
        val message = when (failure) {
            is UnsupportedNotesApi -> "This server needs Nextcloud Notes API 1.4 or newer."
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
            else -> "KGS Notes couldn't connect to this server."
        }
        controls.update { current ->
            current.copy(
                sourceConnection = SourceConnectionUiState(
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
        val ascending: Boolean = false,
        val destination: LibraryDestination = LibraryDestination.NOTES,
        val hiddenSourceIds: Set<SourceId> = emptySet(),
        val editorMode: EditorMode = EditorMode.RICH,
        val attachmentMetadataPreference: AttachmentMetadataPreference = AttachmentMetadataPreference.ASK_EVERY_TIME,
        val saving: Boolean = false,
        val userMessage: String? = null,
        val defaultSourceId: SourceId = LocalSourceId,
        val sourceConnection: SourceConnectionUiState = SourceConnectionUiState(),
        val noteFormatPreference: NoteFormatPreference = NoteFormatPreference.MARKDOWN,
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
