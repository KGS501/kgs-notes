package com.kgs.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.editor.KgsMarkdownEditor
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSummary
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NotesActions(
    val onCreate: () -> Unit = {},
    val onSelect: (NoteId) -> Unit = {},
    val onContentChange: (String) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onCategoryChange: (String) -> Unit = {},
    val onCloseEditor: () -> Unit = {},
    val onMoveToTrash: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onDeletePermanently: () -> Unit = {},
    val onFilterChange: (LibraryFilter) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onEditorModeChange: (EditorMode) -> Unit = {},
)

@Composable
fun KgsNotesApp(viewModel: NotesViewModel) {
    val state by viewModel.state.collectAsState()
    KgsNotesScreen(
        state = state,
        actions = NotesActions(
            onCreate = viewModel::createNote,
            onSelect = viewModel::selectNote,
            onContentChange = viewModel::updateContent,
            onRename = viewModel::rename,
            onToggleFavorite = viewModel::toggleFavorite,
            onCategoryChange = viewModel::setCategory,
            onCloseEditor = viewModel::closeEditor,
            onMoveToTrash = viewModel::moveToTrash,
            onRestore = viewModel::restore,
            onDeletePermanently = viewModel::deletePermanently,
            onFilterChange = viewModel::setFilter,
            onQueryChange = viewModel::setQuery,
            onEditorModeChange = viewModel::setEditorMode,
        ),
    )
}

@Composable
fun KgsNotesScreen(
    state: NotesUiState,
    actions: NotesActions,
    modifier: Modifier = Modifier,
) {
    val motion = LocalKgsMotion.current
    BackHandler(enabled = state.selectedNote != null, onBack = actions.onCloseEditor)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        val twoPane = maxWidth >= 760.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                LibraryPane(
                    state = state,
                    actions = actions,
                    selectedId = state.selectedNote?.id,
                    modifier = Modifier
                        .widthIn(min = 330.dp, max = 410.dp)
                        .fillMaxHeight(),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                AnimatedContent(
                    targetState = state.selectedNote?.id,
                    transitionSpec = {
                        fadeIn(tween(motion.mediumMillis)) togetherWith
                            fadeOut(tween(motion.shortMillis))
                    },
                    label = "selected note",
                    modifier = Modifier.weight(1f),
                ) { noteId ->
                    val note = state.selectedNote?.takeIf { it.id == noteId }
                    if (noteId == null || note == null) {
                        NoSelection(Modifier.fillMaxSize())
                    } else {
                        EditorPane(state, note, actions, showBack = false, Modifier.fillMaxSize())
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = state.selectedNote?.id,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally(tween(motion.mediumMillis, easing = motion.emphasized)) { it / 4 } +
                            fadeIn(tween(motion.mediumMillis)) togetherWith
                            slideOutHorizontally(tween(motion.shortMillis, easing = motion.accelerate)) { -it / 5 } +
                            fadeOut(tween(motion.shortMillis))
                    } else {
                        slideInHorizontally(tween(motion.mediumMillis, easing = motion.emphasized)) { -it / 5 } +
                            fadeIn(tween(motion.mediumMillis)) togetherWith
                            slideOutHorizontally(tween(motion.shortMillis, easing = motion.accelerate)) { it / 4 } +
                            fadeOut(tween(motion.shortMillis))
                    }
                },
                label = "library editor navigation",
                modifier = Modifier.fillMaxSize(),
            ) { noteId ->
                val note = state.selectedNote?.takeIf { it.id == noteId }
                if (noteId == null || note == null) {
                    LibraryPane(state, actions, selectedId = null, Modifier.fillMaxSize())
                } else {
                    EditorPane(state, note, actions, showBack = true, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun LibraryPane(
    state: NotesUiState,
    actions: NotesActions,
    selectedId: NoteId?,
    modifier: Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = "New note") },
                text = { Text("New note") },
                shape = MaterialTheme.shapes.large,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
        ) {
            LibraryHeader()
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                placeholder = { Text("Search notes") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { actions.onFilterChange(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.notes.isEmpty()) {
                EmptyLibrary(
                    filter = state.filter,
                    query = state.query,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("empty-library"),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.notes, key = { it.id.value }) { note ->
                        NoteCard(
                            note = note,
                            selected = note.id == selectedId,
                            onClick = { actions.onSelect(note.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item { Spacer(Modifier.height(92.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 18.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(50.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("K", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            }
        }
        Column {
            Text("Notes", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Local Source",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .35f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(17.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (note.favorite) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (note.snippet.isNotBlank()) {
                Text(
                    text = note.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (note.category.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ) {
                        Text(
                            note.category,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    note.updatedAt.atZone(ZoneId.systemDefault()).format(noteDateFormat),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    filter: LibraryFilter,
    query: String,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✎", style = MaterialTheme.typography.displaySmall)
                }
            }
            Text(
                text = when {
                    query.isNotBlank() -> "No matching notes"
                    filter == LibraryFilter.FAVORITES -> "No favorites yet"
                    filter == LibraryFilter.TRASH -> "Trash is empty"
                    else -> "A quiet place for your thoughts"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when {
                    query.isNotBlank() -> "Try another search term."
                    filter == LibraryFilter.TRASH -> "Deleted notes stay recoverable here."
                    else -> "Create a note and it will be stored as portable Markdown."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorPane(
    state: NotesUiState,
    note: Note,
    actions: NotesActions,
    showBack: Boolean,
    modifier: Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        EditorHeader(state, note, actions, showBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (note.state == NoteState.TRASHED) {
            TrashedNote(note, actions, Modifier.fillMaxSize())
        } else {
            KgsMarkdownEditor(
                markdown = note.markdown,
                requestedMode = state.editorMode,
                onMarkdownChange = actions.onContentChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun EditorHeader(
    state: NotesUiState,
    note: Note,
    actions: NotesActions,
    showBack: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showBack) {
                IconButton(onClick = actions.onCloseEditor) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to notes",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            BasicTextField(
                value = note.title,
                onValueChange = actions.onRename,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = state.saving,
                enter = fadeIn() + scaleIn(initialScale = .9f),
                exit = fadeOut() + scaleOut(targetScale = .9f),
            ) {
                Text(
                    "Saving…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!state.saving) {
                Text(
                    "Saved locally",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (note.state != NoteState.TRASHED) {
                IconButton(onClick = actions.onToggleFavorite) {
                    Icon(
                        imageVector = if (note.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (note.favorite) "Remove favorite" else "Add favorite",
                        tint = if (note.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = actions.onMoveToTrash) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Move to Trash",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (note.state != NoteState.TRASHED) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = state.editorMode == EditorMode.RICH,
                    onClick = { actions.onEditorModeChange(EditorMode.RICH) },
                    label = { Text("Rich") },
                )
                FilterChip(
                    selected = state.editorMode == EditorMode.SOURCE,
                    onClick = { actions.onEditorModeChange(EditorMode.SOURCE) },
                    label = { Text("Source") },
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ) {
                    BasicTextField(
                        value = note.category,
                        onValueChange = actions.onCategoryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { field ->
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                if (note.category.isBlank()) {
                                    Text(
                                        "Category",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                field()
                            }
                        },
                        modifier = Modifier.widthIn(min = 96.dp, max = 160.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashedNote(note: Note, actions: NotesActions, modifier: Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier.padding(24.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                note.markdown.ifBlank { "This note is empty." },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(18.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.clickable(onClick = actions.onRestore),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Restore", style = MaterialTheme.typography.labelLarge)
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.clickable(onClick = actions.onDeletePermanently),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text("Delete permanently", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun NoSelection(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Select a note", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your editor will open here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val LibraryFilter.label: String
    get() = when (this) {
        LibraryFilter.ALL -> "All"
        LibraryFilter.FAVORITES -> "Favorites"
        LibraryFilter.TRASH -> "Trash"
    }

private val noteDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
