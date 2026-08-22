package com.kgs.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.engine.LibrarySnapshot
import com.kgs.notes.engine.LocalNotesEngine
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.NotesEngine
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.SourceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

data class SourceChoice(val id: SourceId, val name: String, val visible: Boolean)

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
    val saving: Boolean = false,
)

class NotesViewModel internal constructor(
    application: Application,
    private val engine: NotesEngine,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        engine = LocalNotesEngine.open(application.filesDir.resolve("local-source").toPath()),
    )
    private val selected = MutableStateFlow<Note?>(null)
    private val controls = MutableStateFlow(UiControls())
    private var contentSave: Job? = null
    private var metadataSave: Job? = null
    private var pendingTitle: String? = null
    private var pendingCategory: String? = null
    private var contentDirty = false
    private var metadataDirty = false

    val state: StateFlow<NotesUiState> = combine(
        engine.library,
        selected,
        controls,
    ) { library, selectedNote, controls ->
        val allSourceIds = buildList {
            add(LocalSourceId)
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
                    name = if (id == LocalSourceId) "Local Source" else id.value,
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
            saving = controls.saving,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotesUiState(notes = engine.library.value.active),
    )

    fun createNote() {
        viewModelScope.launch {
            val id = engine.createDraft()
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
        const val CONTENT_SAVE_DELAY_MILLIS = 120L
        const val METADATA_SAVE_DELAY_MILLIS = 300L
    }

    private data class UiControls(
        val filter: LibraryFilter = LibraryFilter.ALL,
        val query: String = "",
        val sort: LibrarySort = LibrarySort.LAST_EDITED,
        val ascending: Boolean = false,
        val destination: LibraryDestination = LibraryDestination.NOTES,
        val hiddenSourceIds: Set<SourceId> = emptySet(),
        val editorMode: EditorMode = EditorMode.RICH,
        val saving: Boolean = false,
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
