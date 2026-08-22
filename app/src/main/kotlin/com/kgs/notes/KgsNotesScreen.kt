package com.kgs.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.editor.KgsMarkdownEditor
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.SourceId
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
    val onSortChange: (LibrarySort) -> Unit = {},
    val onToggleSortDirection: () -> Unit = {},
    val onToggleSourceVisibility: (SourceId) -> Unit = {},
    val onDestinationChange: (LibraryDestination) -> Unit = {},
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
            onSortChange = viewModel::setSort,
            onToggleSortDirection = viewModel::toggleSortDirection,
            onToggleSourceVisibility = viewModel::toggleSourceVisibility,
            onDestinationChange = viewModel::openDestination,
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
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.selectedNote != null, onBack = actions.onCloseEditor)
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

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
                    onOpenDrawer = { drawerOpen = true },
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
                    targetState = state.selectedNote,
                    contentKey = { it?.id },
                    transitionSpec = {
                        fadeIn(tween(motion.mediumMillis)) togetherWith
                            fadeOut(tween(motion.shortMillis))
                    },
                    label = "selected note",
                    modifier = Modifier.weight(1f),
                ) { note ->
                    if (note == null) {
                        NoSelection(Modifier.fillMaxSize())
                    } else {
                        EditorPane(state, note, actions, showBack = false, Modifier.fillMaxSize())
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = state.selectedNote,
                contentKey = { it?.id },
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
            ) { note ->
                if (note == null) {
                    LibraryPane(
                        state = state,
                        actions = actions,
                        onOpenDrawer = { drawerOpen = true },
                        selectedId = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    EditorPane(state, note, actions, showBack = true, Modifier.fillMaxSize())
                }
            }
        }

        NotesDrawer(
            visible = drawerOpen,
            state = state,
            onDismiss = { drawerOpen = false },
            onToggleSource = actions.onToggleSourceVisibility,
            onDestination = { destination ->
                actions.onDestinationChange(destination)
                drawerOpen = false
            },
        )
    }
}

@Composable
private fun LibraryPane(
    state: NotesUiState,
    actions: NotesActions,
    onOpenDrawer: () -> Unit = {},
    selectedId: NoteId?,
    modifier: Modifier,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.testTag("library-pane"),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (state.destination == LibraryDestination.NOTES) {
                ExtendedFloatingActionButton(
                    onClick = actions.onCreate,
                    icon = { Icon(Icons.Default.Add, contentDescription = "New note") },
                    text = { Text("New note") },
                    shape = RoundedCornerShape(18.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
        ) {
            LibraryHeader(state.destination, onOpenDrawer)
            Spacer(Modifier.height(18.dp))
            if (state.destination == LibraryDestination.SETTINGS) {
                SettingsPane(Modifier.fillMaxSize())
                return@Column
            }
            if (state.destination == LibraryDestination.NOTES) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    placeholder = { Text("Search notes") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LibraryFilter.entries.forEach { filter ->
                        LibraryFilterButton(
                            filter = filter,
                            selected = state.filter == filter,
                            pendingSyncCount = state.pendingSyncCount,
                            onClick = { actions.onFilterChange(filter) },
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                    ) {
                        IconButton(
                            onClick = actions.onToggleSortDirection,
                            modifier = Modifier.semantics {
                                contentDescription = if (state.ascending) "Ascending" else "Descending"
                            },
                        ) {
                            SortDirectionGlyph(ascending = state.ascending)
                        }
                    }
                    Box {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp),
                        ) {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                SortGlyph(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { contentDescription = "Sort notes" },
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            LibrarySort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label) },
                                    leadingIcon = {
                                        if (sort == state.sort) {
                                            Text("✓", color = MaterialTheme.colorScheme.primary)
                                        } else {
                                            Spacer(Modifier.size(20.dp))
                                        }
                                    },
                                    onClick = {
                                        actions.onSortChange(sort)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else {
                Spacer(Modifier.height(2.dp))
            }
            if (state.notes.isEmpty()) {
                EmptyLibrary(
                    filter = state.filter,
                    destination = state.destination,
                    query = state.query,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("empty-library"),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(9.dp),
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
private fun LibraryHeader(destination: LibraryDestination, onOpenDrawer: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "Open menu")
        }
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
            Text(
                when (destination) {
                    LibraryDestination.NOTES -> "Notes"
                    LibraryDestination.TRASH -> "Trash"
                    LibraryDestination.SETTINGS -> "Settings"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                if (destination == LibraryDestination.NOTES) "Your selected Sources" else "KGS Notes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryFilterButton(
    filter: LibraryFilter,
    selected: Boolean,
    pendingSyncCount: Int,
    onClick: () -> Unit,
) {
    val description = when (filter) {
        LibraryFilter.ALL -> "All notes"
        LibraryFilter.FAVORITES -> "Favorite notes"
        LibraryFilter.LOCAL -> if (pendingSyncCount > 0) {
            "Local notes, $pendingSyncCount awaiting sync"
        } else {
            "Local notes"
        }
        LibraryFilter.SERVER -> "Server notes"
    }
    Box {
        Surface(
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = RoundedCornerShape(13.dp),
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (filter) {
                    LibraryFilter.ALL -> AllNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                    LibraryFilter.FAVORITES -> Icon(Icons.Default.FavoriteBorder, contentDescription = null)
                    LibraryFilter.LOCAL -> LocalNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                    LibraryFilter.SERVER -> CloudGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (filter == LibraryFilter.LOCAL && pendingSyncCount > 0) {
            Surface(
                color = Color(0xFFF28C28),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        pendingSyncCount.coerceAtMost(99).toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SortDirectionGlyph(ascending: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val rotation by animateFloatAsState(
        targetValue = if (ascending) 180f else 0f,
        animationSpec = tween(240),
        label = "sort direction",
    )
    Canvas(Modifier.size(20.dp).rotate(rotation)) {
        val stroke = size.minDimension * .1f
        drawLine(color, Offset(size.width * .25f, size.height * .4f), Offset(size.width * .5f, size.height * .66f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .5f, size.height * .66f), Offset(size.width * .75f, size.height * .4f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun SortGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(21.dp)) {
        val stroke = size.minDimension * .09f
        listOf(.29f to .78f, .51f to .6f, .73f to .4f).forEach { (y, width) ->
            drawLine(
                color,
                Offset(size.width * .16f, size.height * y),
                Offset(size.width * width, size.height * y),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun AllNotesGlyph(color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .08f
        listOf(.28f, .5f, .72f).forEach { y ->
            drawLine(color, Offset(size.width * .2f, size.height * y), Offset(size.width * .8f, size.height * y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun LocalNotesGlyph(color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .08f
        drawRoundRect(
            color,
            topLeft = Offset(size.width * .25f, size.height * .08f),
            size = Size(size.width * .5f, size.height * .84f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .1f),
            style = Stroke(stroke),
        )
        drawCircle(color, stroke * .65f, Offset(size.width * .5f, size.height * .8f))
    }
}

@Composable
private fun CloudGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = size.minDimension * .085f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .22f, size.height * .7f)
            cubicTo(size.width * .06f, size.height * .7f, size.width * .06f, size.height * .44f, size.width * .25f, size.height * .43f)
            cubicTo(size.width * .32f, size.height * .18f, size.width * .68f, size.height * .18f, size.width * .74f, size.height * .45f)
            cubicTo(size.width * .94f, size.height * .46f, size.width * .95f, size.height * .7f, size.width * .78f, size.height * .7f)
            close()
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun NotesDrawer(
    visible: Boolean,
    state: NotesUiState,
    onDismiss: () -> Unit,
    onToggleSource: (SourceId) -> Unit,
    onDestination: (LibraryDestination) -> Unit,
) {
    val motion = LocalKgsMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(motion.shortMillis)),
        exit = fadeOut(tween(motion.shortMillis)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .34f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(tween(motion.mediumMillis, easing = motion.emphasized)) { -it },
                exit = slideOutHorizontally(tween(motion.shortMillis, easing = motion.accelerate)) { -it },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .width(292.dp)
                        .fillMaxHeight(),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = RoundedCornerShape(15.dp),
                                modifier = Modifier.size(46.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("K", fontWeight = FontWeight.Black, fontSize = 21.sp)
                                }
                            }
                            Column {
                                Text("KGS Notes", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Portable by design",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "Sources",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        state.sources.forEach { source ->
                            DrawerRow(
                                label = source.name,
                                selected = source.visible,
                                onClick = { onToggleSource(source.id) },
                                icon = {
                                    if (source.id == LocalSourceId) {
                                        LocalNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        CloudGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        DrawerRow(
                            label = "Trash",
                            selected = state.destination == LibraryDestination.TRASH,
                            onClick = { onDestination(LibraryDestination.TRASH) },
                            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        )
                        Spacer(Modifier.height(4.dp))
                        DrawerRow(
                            label = "Settings",
                            selected = state.destination == LibraryDestination.SETTINGS,
                            onClick = { onDestination(LibraryDestination.SETTINGS) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) { icon() }
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (selected) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
            }
        }
    }
}

@Composable
private fun SettingsPane(modifier: Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        SettingsCard("Default Source", "Local Source")
        SettingsCard("Note format", "Markdown (.md)")
        SettingsCard("Editor", "Rich Mode with Source Mode available")
    }
}

@Composable
private fun SettingsCard(title: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = RoundedCornerShape(18.dp),
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
                if (note.sourceId != LocalSourceId && note.syncState in pendingSyncStates) {
                    Spacer(Modifier.width(7.dp))
                    Surface(
                        color = Color(0xFFF28C28),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(10.dp)
                            .semantics { contentDescription = "Awaiting sync" },
                    ) {}
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
                        shape = RoundedCornerShape(8.dp),
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
    destination: LibraryDestination,
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
                    destination == LibraryDestination.TRASH -> "Trash is empty"
                    filter == LibraryFilter.FAVORITES -> "No favorites yet"
                    filter == LibraryFilter.LOCAL -> "No local notes"
                    filter == LibraryFilter.SERVER -> "No synced server notes"
                    else -> "A quiet place for your thoughts"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when {
                    query.isNotBlank() -> "Try another search term."
                    destination == LibraryDestination.TRASH -> "Deleted notes stay recoverable here."
                    filter == LibraryFilter.SERVER -> "Synced notes from connected Sources appear here."
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
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .testTag("note-editor"),
    ) {
        EditorHeader(state, note, actions, showBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (note.state == NoteState.TRASHED) {
            TrashedNote(note, actions, Modifier.fillMaxSize())
        } else {
            KgsMarkdownEditor(
                markdown = note.markdown,
                requestedMode = state.editorMode,
                onMarkdownChange = actions.onContentChange,
                onModeChange = actions.onEditorModeChange,
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
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var actionsMenuOpen by remember { mutableStateOf(false) }
    var creatingCategory by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
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
            SaveStatusIndicator(
                status = when {
                    state.saving -> EditorSaveStatus.SAVING
                    note.syncState == NoteSyncState.SYNCING -> EditorSaveStatus.SYNCING
                    note.syncState == NoteSyncState.SYNCED -> EditorSaveStatus.SYNCED
                    else -> EditorSaveStatus.SAVED_LOCALLY
                },
            )
            if (note.state != NoteState.TRASHED) {
                Box {
                    IconButton(
                        onClick = { categoryMenuOpen = true },
                        modifier = Modifier.semantics {
                            contentDescription = if (note.category.isBlank()) {
                                "Choose Category"
                            } else {
                                "Choose Category: ${note.category}"
                            }
                        },
                    ) {
                        CategoryIcon(
                            filled = note.category.isNotBlank(),
                            color = if (note.category.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false },
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("No Category") },
                            leadingIcon = {
                                CategoryIcon(
                                    filled = false,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                actions.onCategoryChange("")
                                categoryMenuOpen = false
                            },
                        )
                        state.categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                leadingIcon = {
                                    CategoryIcon(
                                        filled = category == note.category,
                                        color = if (category == note.category) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                                onClick = {
                                    actions.onCategoryChange(category)
                                    categoryMenuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("New Category") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                categoryMenuOpen = false
                                creatingCategory = true
                            },
                        )
                    }
                }
                Box {
                    IconButton(onClick = { actionsMenuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More note actions",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(
                        expanded = actionsMenuOpen,
                        onDismissRequest = { actionsMenuOpen = false },
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(if (note.favorite) "Remove from Favorites" else "Add to Favorites")
                            },
                            leadingIcon = {
                                Icon(
                                    if (note.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                actions.onToggleFavorite()
                                actionsMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to Trash") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                actions.onMoveToTrash()
                                actionsMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }

    if (creatingCategory) {
        AlertDialog(
            onDismissRequest = { creatingCategory = false },
            title = { Text("New Category") },
            text = {
                OutlinedTextField(
                    value = newCategory,
                    onValueChange = { newCategory = it },
                    label = { Text("Category name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = newCategory.trim().isNotEmpty(),
                    onClick = {
                        actions.onCategoryChange(newCategory.trim())
                        newCategory = ""
                        creatingCategory = false
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                Button(onClick = { creatingCategory = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp),
        )
    }
}

private enum class EditorSaveStatus(val description: String) {
    SAVING("Saving"),
    SAVED_LOCALLY("Saved locally"),
    SYNCING("Saved locally, syncing"),
    SYNCED("Synced"),
}

@Composable
private fun SaveStatusIndicator(status: EditorSaveStatus) {
    val blue = Color(0xFF2878D0)
    val orange = Color(0xFFF28C28)
    val green = Color(0xFF2E9B62)
    val color by animateColorAsState(
        targetValue = when (status) {
            EditorSaveStatus.SAVING -> orange
            EditorSaveStatus.SAVED_LOCALLY, EditorSaveStatus.SYNCING -> blue
            EditorSaveStatus.SYNCED -> green
        },
        animationSpec = tween(260),
        label = "save status color",
    )
    val fill by animateFloatAsState(
        targetValue = if (status == EditorSaveStatus.SAVED_LOCALLY || status == EditorSaveStatus.SYNCED) 1f else 0f,
        animationSpec = tween(260),
        label = "save status fill",
    )
    val sweep by animateFloatAsState(
        targetValue = if (status == EditorSaveStatus.SAVING) 270f else 360f,
        animationSpec = tween(260),
        label = "save status sweep",
    )
    val infinite = rememberInfiniteTransition(label = "saving rotation")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "saving rotation",
    )

    Canvas(
        modifier = Modifier
            .size(34.dp)
            .semantics { contentDescription = status.description }
            .padding(6.dp),
    ) {
        val stroke = size.minDimension * .12f
        val inset = stroke / 2
        if (fill > 0f) drawCircle(color = color.copy(alpha = fill))
        rotate(if (status == EditorSaveStatus.SAVING) rotation else 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        when (status) {
            EditorSaveStatus.SAVING -> Unit
            EditorSaveStatus.SAVED_LOCALLY -> {
                val glyph = Color.White.copy(alpha = fill)
                drawLine(glyph, Offset(size.width * .26f, size.height * .52f), Offset(size.width * .44f, size.height * .69f), stroke, StrokeCap.Round)
                drawLine(glyph, Offset(size.width * .44f, size.height * .69f), Offset(size.width * .75f, size.height * .34f), stroke, StrokeCap.Round)
            }
            EditorSaveStatus.SYNCING -> {
                drawLine(orange, Offset(size.width * .5f, size.height * .72f), Offset(size.width * .5f, size.height * .3f), stroke, StrokeCap.Round)
                drawLine(orange, Offset(size.width * .5f, size.height * .3f), Offset(size.width * .32f, size.height * .48f), stroke, StrokeCap.Round)
                drawLine(orange, Offset(size.width * .5f, size.height * .3f), Offset(size.width * .68f, size.height * .48f), stroke, StrokeCap.Round)
            }
            EditorSaveStatus.SYNCED -> {
                val glyph = Color.White.copy(alpha = fill)
                drawCircle(glyph, size.width * .17f, Offset(size.width * .38f, size.height * .56f))
                drawCircle(glyph, size.width * .21f, Offset(size.width * .55f, size.height * .47f))
                drawCircle(glyph, size.width * .14f, Offset(size.width * .7f, size.height * .58f))
                drawRect(glyph, Offset(size.width * .29f, size.height * .55f), Size(size.width * .5f, size.height * .17f))
            }
        }
    }
}

@Composable
private fun CategoryIcon(filled: Boolean, color: Color) {
    Canvas(Modifier.size(24.dp).padding(2.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .08f, size.height * .23f)
            lineTo(size.width * .4f, size.height * .23f)
            lineTo(size.width * .5f, size.height * .36f)
            lineTo(size.width * .92f, size.height * .36f)
            lineTo(size.width * .92f, size.height * .82f)
            lineTo(size.width * .08f, size.height * .82f)
            close()
        }
        if (filled) {
            drawPath(path, color)
        } else {
            drawPath(path, color, style = Stroke(width = size.minDimension * .09f, cap = StrokeCap.Round))
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

private val LibrarySort.label: String
    get() = when (this) {
        LibrarySort.LAST_EDITED -> "Last edited"
        LibrarySort.LAST_CREATED -> "Last created"
        LibrarySort.ALPHABETICAL -> "Alphabetical"
    }

private val pendingSyncStates = setOf(
    NoteSyncState.SAVED_LOCALLY,
    NoteSyncState.SYNCING,
    NoteSyncState.NEEDS_ATTENTION,
)

private val noteDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
