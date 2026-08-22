package com.kgs.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.design.kgsClickable
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
import kotlin.math.roundToInt

data class NotesActions(
    val onCreate: () -> Unit = {},
    val onSelect: (NoteId) -> Unit = {},
    val onContentChange: (String) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onCategoryChange: (String) -> Unit = {},
    val onSourceChange: (SourceId) -> Unit = {},
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
    BackHandler(
        enabled = state.selectedNote == null && !drawerOpen && state.destination != LibraryDestination.NOTES,
    ) { actions.onDestinationChange(LibraryDestination.NOTES) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Scaffold(
        modifier = modifier.testTag("library-pane"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (state.destination == LibraryDestination.NOTES) {
                ExtendedFloatingActionButton(
                    onClick = actions.onCreate,
                    icon = { Icon(Icons.Default.Add, contentDescription = "New note") },
                    text = { Text("New note") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.padding(bottom = navigationBottom),
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
            LibraryHeader(
                destination = state.destination,
                onOpenDrawer = onOpenDrawer,
                onBackToNotes = { actions.onDestinationChange(LibraryDestination.NOTES) },
            )
            Spacer(Modifier.height(10.dp))
            if (state.destination == LibraryDestination.SETTINGS) {
                SettingsPane(Modifier.fillMaxSize())
                return@Column
            }
            if (state.destination == LibraryDestination.NOTES) {
                TextField(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    placeholder = { Text("Search notes") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
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
                    val sortStartShape = RoundedCornerShape(
                        topStart = 12.dp,
                        bottomStart = 12.dp,
                        topEnd = 3.dp,
                        bottomEnd = 3.dp,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = sortStartShape,
                        modifier = Modifier
                            .size(44.dp)
                            .kgsClickable(actions.onToggleSortDirection, sortStartShape)
                            .semantics {
                                contentDescription = if (state.ascending) "Ascending" else "Descending"
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) { SortDirectionGlyph(ascending = state.ascending) }
                    }
                    Spacer(Modifier.width(2.dp))
                    Box {
                        val sortEndShape = RoundedCornerShape(
                            topEnd = 12.dp,
                            bottomEnd = 12.dp,
                            topStart = 3.dp,
                            bottomStart = 3.dp,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = sortEndShape,
                            modifier = Modifier
                                .size(44.dp)
                                .kgsClickable({ sortMenuOpen = true }, sortEndShape),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                SortGlyph(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { contentDescription = "Sort notes" },
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                            shape = RoundedCornerShape(20.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
                            modifier = Modifier.animateItem(
                                placementSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = .72f,
                                    stiffness = 420f,
                                ),
                            ),
                        )
                    }
                    item { Spacer(Modifier.height(92.dp + navigationBottom)) }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    destination: LibraryDestination,
    onOpenDrawer: () -> Unit,
    onBackToNotes: () -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusTop + 5.dp),
    ) {
        ExpressiveIconButton(
            onClick = if (destination == LibraryDestination.NOTES) onOpenDrawer else onBackToNotes,
        ) {
            if (destination == LibraryDestination.NOTES) {
                Icon(Icons.Default.Menu, contentDescription = "Open menu")
            } else {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Notes")
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("K", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
        }
        Column {
            Text(
                when (destination) {
                    LibraryDestination.NOTES -> "Notes"
                    LibraryDestination.TRASH -> "Trash"
                    LibraryDestination.SETTINGS -> "Settings"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun ExpressiveIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .kgsClickable(onClick, CircleShape),
    ) { content() }
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
    val iconColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val buttonShape = RoundedCornerShape(11.dp)
    Box {
        Surface(
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = buttonShape,
            modifier = Modifier
                .size(44.dp)
                .kgsClickable(onClick, buttonShape)
                .semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (filter) {
                    LibraryFilter.ALL -> AllNotesGlyph(iconColor)
                    LibraryFilter.FAVORITES -> Icon(Icons.Default.FavoriteBorder, contentDescription = null, tint = iconColor)
                    LibraryFilter.LOCAL -> LocalNotesGlyph(iconColor)
                    LibraryFilter.SERVER -> CloudGlyph(iconColor)
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
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 150 else 125,
            easing = if (visible) motion.standard else motion.accelerate,
        ),
        label = "drawer slide",
    )
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val drawerWidth = 292.dp
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    val effectiveProgress = (progress + dragOffset / drawerWidthPx).coerceIn(0f, 1f)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        if (effectiveProgress > .01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .34f * effectiveProgress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        if (effectiveProgress > .001f) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
                shadowElevation = 0.dp,
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .offset {
                        IntOffset(
                            x = (-drawerWidthPx * (1f - effectiveProgress)).roundToInt(),
                            y = 0,
                        )
                    }
                    .pointerInput(visible) {
                        detectHorizontalDragGestures(
                            onDragCancel = { dragOffset = 0f },
                            onDragEnd = {
                                if (effectiveProgress < .72f) onDismiss()
                                dragOffset = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            dragOffset = (dragOffset + amount).coerceIn(-drawerWidthPx, 0f)
                        }
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = 18.dp,
                            top = statusTop + 18.dp,
                            end = 18.dp,
                            bottom = navigationBottom + 14.dp,
                        ),
                ) {
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
                        Spacer(Modifier.height(24.dp))
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

@Composable
private fun DrawerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .kgsClickable(onClick, shape),
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
        shape = RoundedCornerShape(14.dp),
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
    val shape = RoundedCornerShape(14.dp)
    Surface(
        color = container,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .testTag("note-card-${note.id.value}")
            .kgsClickable(onClick, shape),
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
    var categoryDialogOpen by remember { mutableStateOf(false) }
    var sourceDialogOpen by remember { mutableStateOf(false) }
    var actionsMenuOpen by remember { mutableStateOf(false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(modifier = Modifier.padding(start = 6.dp, top = statusTop + 2.dp, end = 6.dp, bottom = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (showBack) {
                ExpressiveIconButton(onClick = actions.onCloseEditor) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Notes",
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
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val fadeWidth = 30.dp.toPx()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to Color.White,
                                    ((size.width - fadeWidth) / size.width).coerceIn(0f, 1f) to Color.White,
                                    1f to Color.Transparent,
                                ),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
            SaveStatusIndicator(
                status = when {
                    state.saving -> EditorSaveStatus.SAVING
                    note.syncState == NoteSyncState.SYNCING -> EditorSaveStatus.SYNCING
                    note.syncState == NoteSyncState.SYNCED -> EditorSaveStatus.SYNCED
                    else -> EditorSaveStatus.SAVED_LOCALLY
                },
                onClick = { sourceDialogOpen = true },
            )
            if (note.state != NoteState.TRASHED) {
                val categoryShape = RoundedCornerShape(12.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .kgsClickable({ categoryDialogOpen = true }, categoryShape)
                        .semantics {
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
                Box {
                    ExpressiveIconButton(onClick = { actionsMenuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More note actions",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(
                        expanded = actionsMenuOpen,
                        onDismissRequest = { actionsMenuOpen = false },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(if (note.favorite) "Unfavourite" else "Favourite")
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
                            text = { Text("Delete") },
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

    if (categoryDialogOpen) {
        CategoryDialog(
            current = note.category,
            categories = state.categories,
            onSelect = {
                actions.onCategoryChange(it)
                categoryDialogOpen = false
            },
            onDismiss = { categoryDialogOpen = false },
        )
    }
    if (sourceDialogOpen) {
        SourceDialog(
            current = note.sourceId,
            sources = state.sources,
            onSelect = {
                actions.onSourceChange(it)
                sourceDialogOpen = false
            },
            onDismiss = { sourceDialogOpen = false },
        )
    }
}

@Composable
private fun CategoryDialog(
    current: String,
    categories: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val candidate = query.trim()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Text("Choose Category", style = MaterialTheme.typography.titleLarge)
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search Categories") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    colors = flatTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    item {
                        ModalChoiceRow(
                            label = "No Category",
                            selected = current.isBlank(),
                            onClick = { onSelect("") },
                            icon = {
                                CategoryIcon(false, MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                    }
                    items(
                        items = categories.filter { it.contains(candidate, ignoreCase = true) },
                        key = { it },
                    ) { category ->
                        ModalChoiceRow(
                            label = category,
                            selected = category == current,
                            onClick = { onSelect(category) },
                            icon = {
                                CategoryIcon(
                                    filled = category == current,
                                    color = if (category == current) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                        )
                    }
                }
                Button(
                    enabled = candidate.isNotEmpty() && categories.none { it.equals(candidate, ignoreCase = true) },
                    onClick = { onSelect(candidate) },
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (candidate.isBlank()) "New Category" else "Create “$candidate”")
                }
            }
        }
    }
}

@Composable
private fun SourceDialog(
    current: SourceId,
    sources: List<SourceChoice>,
    onSelect: (SourceId) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Text("Save Note to", style = MaterialTheme.typography.titleLarge)
                Text(
                    "The Working Copy stays available offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                sources.forEach { source ->
                    ModalChoiceRow(
                        label = source.name,
                        selected = source.id == current,
                        onClick = { onSelect(source.id) },
                        icon = {
                            if (source.id == LocalSourceId) {
                                LocalNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                CloudGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModalChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .kgsClickable(onClick, shape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun flatTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

private enum class EditorSaveStatus(val description: String) {
    SAVING("Saving"),
    SAVED_LOCALLY("Saved locally"),
    SYNCING("Saved locally, syncing"),
    SYNCED("Synced"),
}

@Composable
private fun SaveStatusIndicator(status: EditorSaveStatus, onClick: () -> Unit) {
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
    val glyphProgress by animateFloatAsState(
        targetValue = if (status == EditorSaveStatus.SAVING) 0f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = .68f, stiffness = 430f),
        label = "save status glyph",
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

    val shape = CircleShape
    Canvas(
        modifier = Modifier
            .size(38.dp)
            .kgsClickable(onClick, shape)
            .semantics { contentDescription = status.description }
            .padding(7.dp),
    ) {
        val stroke = size.minDimension * .105f
        val inset = stroke / 2
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
                val glyph = blue.copy(alpha = glyphProgress.coerceIn(0f, 1f))
                val scale = .72f + .28f * glyphProgress
                val left = size.width * (.5f - .19f * scale)
                val top = size.height * (.5f - .27f * scale)
                drawRoundRect(
                    glyph,
                    topLeft = Offset(left, top),
                    size = Size(size.width * .38f * scale, size.height * .54f * scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .07f),
                    style = Stroke(stroke * .78f),
                )
                drawCircle(glyph, stroke * .32f, Offset(size.width * .5f, size.height * (.5f + .19f * scale)))
            }
            EditorSaveStatus.SYNCING -> {
                drawLine(orange, Offset(size.width * .5f, size.height * .72f), Offset(size.width * .5f, size.height * .3f), stroke, StrokeCap.Round)
                drawLine(orange, Offset(size.width * .5f, size.height * .3f), Offset(size.width * .32f, size.height * .48f), stroke, StrokeCap.Round)
                drawLine(orange, Offset(size.width * .5f, size.height * .3f), Offset(size.width * .68f, size.height * .48f), stroke, StrokeCap.Round)
            }
            EditorSaveStatus.SYNCED -> {
                val glyph = green.copy(alpha = glyphProgress.coerceIn(0f, 1f))
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .27f, size.height * .64f)
                    cubicTo(size.width * .13f, size.height * .64f, size.width * .13f, size.height * .44f, size.width * .3f, size.height * .43f)
                    cubicTo(size.width * .36f, size.height * .22f, size.width * .66f, size.height * .24f, size.width * .7f, size.height * .46f)
                    cubicTo(size.width * .86f, size.height * .47f, size.width * .86f, size.height * .65f, size.width * .72f, size.height * .65f)
                }
                drawPath(path, glyph, style = Stroke(stroke * .78f, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun CategoryIcon(filled: Boolean, color: Color) {
    Canvas(Modifier.size(24.dp).padding(2.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .1f, size.height * .31f)
            quadraticTo(size.width * .1f, size.height * .2f, size.width * .22f, size.height * .2f)
            lineTo(size.width * .4f, size.height * .2f)
            quadraticTo(size.width * .47f, size.height * .2f, size.width * .52f, size.height * .28f)
            lineTo(size.width * .58f, size.height * .36f)
            lineTo(size.width * .82f, size.height * .36f)
            quadraticTo(size.width * .9f, size.height * .36f, size.width * .9f, size.height * .46f)
            lineTo(size.width * .9f, size.height * .75f)
            quadraticTo(size.width * .9f, size.height * .84f, size.width * .8f, size.height * .84f)
            lineTo(size.width * .2f, size.height * .84f)
            quadraticTo(size.width * .1f, size.height * .84f, size.width * .1f, size.height * .74f)
            close()
        }
        if (filled) {
            drawPath(path, color)
        } else {
            drawPath(
                path,
                color,
                style = Stroke(
                    width = size.minDimension * .1f,
                    cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
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
            val restoreShape = MaterialTheme.shapes.medium
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = restoreShape,
                modifier = Modifier.kgsClickable(actions.onRestore, restoreShape),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Restore", style = MaterialTheme.typography.labelLarge)
                }
            }
            val deleteShape = MaterialTheme.shapes.medium
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = deleteShape,
                modifier = Modifier.kgsClickable(actions.onDeletePermanently, deleteShape),
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
