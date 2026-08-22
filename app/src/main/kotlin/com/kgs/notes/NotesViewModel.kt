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
import com.kgs.notes.engine.NotesEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryFilter {
    ALL,
    FAVORITES,
    TRASH,
}

data class NotesUiState(
    val notes: List<NoteSummary> = emptyList(),
    val selectedNote: Note? = null,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val query: String = "",
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
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val query = MutableStateFlow("")
    private val editorMode = MutableStateFlow(EditorMode.RICH)
    private val saving = MutableStateFlow(false)
    private var contentSave: Job? = null
    private var metadataSave: Job? = null
    private var pendingTitle: String? = null
    private var pendingCategory: String? = null

    private val controls = combine(
        filter,
        query,
        editorMode,
        saving,
    ) { currentFilter, currentQuery, currentEditorMode, isSaving ->
        UiControls(currentFilter, currentQuery, currentEditorMode, isSaving)
    }

    val state: StateFlow<NotesUiState> = combine(
        engine.library,
        selected,
        controls,
    ) { library, selectedNote, controls ->
        val notes = library.forFilter(controls.filter).filter { note ->
            controls.query.isBlank() || listOf(note.title, note.snippet, note.category)
                .any { it.contains(controls.query, ignoreCase = true) }
        }
        NotesUiState(
            notes = notes,
            selectedNote = selectedNote,
            filter = controls.filter,
            query = controls.query,
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
            editorMode.value = EditorMode.RICH
        }
    }

    fun selectNote(id: NoteId) {
        viewModelScope.launch {
            flushSelectedContent()
            selected.value = engine.note(id)
            editorMode.value = EditorMode.RICH
        }
    }

    fun updateContent(markdown: String) {
        val current = selected.value ?: return
        selected.value = current.copy(markdown = markdown)
        saving.value = true
        contentSave?.cancel()
        contentSave = viewModelScope.launch {
            delay(CONTENT_SAVE_DELAY_MILLIS)
            saveCurrentContent()
        }
    }

    fun rename(title: String) {
        val current = selected.value ?: return
        selected.value = current.copy(title = title)
        pendingTitle = title
        scheduleMetadataSave()
    }

    fun toggleFavorite() {
        val id = selected.value?.id ?: return
        viewModelScope.launch {
            engine.toggleFavorite(id)
            selected.value = engine.note(id)?.withPendingDisplayMetadata()
        }
    }

    fun setCategory(category: String) {
        val current = selected.value ?: return
        selected.value = current.copy(category = category)
        pendingCategory = category
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
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setEditorMode(value: EditorMode) {
        editorMode.value = value
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
        val current = selected.value ?: return
        engine.updateContent(current.id, current.markdown)
        selected.value = engine.note(current.id)?.withPendingDisplayMetadata()
        saving.value = false
    }

    private fun scheduleMetadataSave() {
        metadataSave?.cancel()
        metadataSave = viewModelScope.launch {
            delay(METADATA_SAVE_DELAY_MILLIS)
            savePendingMetadata()
        }
    }

    private suspend fun savePendingMetadata() {
        val id = selected.value?.id ?: return
        val title = pendingTitle
        val category = pendingCategory
        if (title != null) engine.rename(id, title)
        if (category != null) engine.setCategory(id, category)
        if (pendingTitle == title) pendingTitle = null
        if (pendingCategory == category) pendingCategory = null
        selected.value = engine.note(id)?.withPendingDisplayMetadata()
    }

    private fun Note.withPendingDisplayMetadata(): Note = copy(
        title = pendingTitle ?: title,
        category = pendingCategory ?: category,
    )

    private fun LibrarySnapshot.forFilter(filter: LibraryFilter): List<NoteSummary> = when (filter) {
        LibraryFilter.ALL -> active
        LibraryFilter.FAVORITES -> favorites
        LibraryFilter.TRASH -> trash
    }

    private companion object {
        const val CONTENT_SAVE_DELAY_MILLIS = 120L
        const val METADATA_SAVE_DELAY_MILLIS = 300L
    }

    private data class UiControls(
        val filter: LibraryFilter,
        val query: String,
        val editorMode: EditorMode,
        val saving: Boolean,
    )
}
