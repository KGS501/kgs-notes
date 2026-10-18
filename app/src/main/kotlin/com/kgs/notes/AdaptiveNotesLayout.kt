package com.kgs.notes

import android.graphics.Rect
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import com.kgs.notes.design.defaultEffectsSpec
import com.kgs.notes.design.fastEffectsSpec
import com.kgs.notes.design.fastSpatialSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.kgs.notes.design.KgsSheetRegion
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.design.LocalKgsSheetRegion
import com.kgs.notes.design.kgsSheetRegion
import com.kgs.notes.design.R.string as UiR
import com.kgs.notes.design.defaultSpatialSpec
import androidx.compose.ui.geometry.Rect as PaneRect

/** Window information is a UI input; it never becomes Note or Source metadata. */
data class NotesFold(val bounds: Rect, val horizontal: Boolean)

@Composable
internal fun rememberNotesFold(): NotesFold? {
    val activity = LocalActivity.current
    val fold by produceState<NotesFold?>(null, activity) {
        val host = activity ?: return@produceState
        val owner = host as? LifecycleOwner ?: return@produceState
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(host).windowLayoutInfo(host).collect { info ->
                value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                    it.isSeparating || it.state == FoldingFeature.State.HALF_OPENED || it.occlusionType == FoldingFeature.OcclusionType.FULL
                }?.let { NotesFold(Rect(it.bounds), it.orientation == FoldingFeature.Orientation.HORIZONTAL) }
            }
        }
    }
    return fold
}

/** Which screen edges a pane touches, so only those edges keep room for system bars. */
internal data class PaneEdges(val top: Boolean = true, val bottom: Boolean = true)

internal val LocalPaneEdges = staticCompositionLocalOf { PaneEdges() }

/** Status bar space for content at the top of its pane; none for a pane below a fold. */
@Composable
internal fun paneStatusTop(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * edgePresence(LocalPaneEdges.current.top, "status bar")

/** Navigation bar space for content at the bottom of its pane; none for a pane above a fold. */
@Composable
internal fun paneNavigationBottom(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() * edgePresence(LocalPaneEdges.current.bottom, "navigation bar")

/** Animates only a pane crossing the fold; system bar insets themselves apply immediately. */
@Composable
private fun edgePresence(touching: Boolean, label: String): Float =
    animateFloatAsState(if (touching) 1f else 0f, LocalKgsMotion.current.defaultSpatialSpec(), label = "Pane $label edge").value.coerceIn(0f, 1f)

internal enum class NotesPosture { SINGLE, SPLIT, BOOK, TABLETOP }

/** Where each pane sits; a hidden pane keeps its size just outside the window so it can slide in. */
internal data class PaneTargets(
    val posture: NotesPosture,
    val library: PaneRect,
    val editor: PaneRect,
    val libraryVisible: Boolean,
    val editorVisible: Boolean,
    val libraryEdges: PaneEdges,
    val editorEdges: PaneEdges,
    /** The keyboard covers the library below a tabletop fold, never the Note above it. */
    val editorAboveKeyboard: Boolean,
    val handle: PaneRect?,
)

internal fun paneTargets(
    width: Float,
    height: Float,
    fold: PaneRect?,
    horizontalFold: Boolean,
    noteOpen: Boolean,
    fullScreen: Boolean,
    splitFraction: Float,
    density: Float,
): PaneTargets {
    fun dp(value: Float) = value * density
    val tabletop = fold != null && horizontalFold && fold.top >= dp(180f) && height - fold.bottom >= dp(180f)
    val book = fold != null && !horizontalFold && fold.left >= dp(260f) && width - fold.right >= dp(260f)
    val posture = when {
        tabletop -> NotesPosture.TABLETOP
        book -> NotesPosture.BOOK
        width >= dp(600f) -> NotesPosture.SPLIT
        else -> NotesPosture.SINGLE
    }
    val full = PaneRect(0f, 0f, width, height)
    // Book posture always shows its two pages; full screen applies to the other postures.
    val single = (fullScreen && posture != NotesPosture.BOOK) || posture == NotesPosture.SINGLE
    val libraryWidth = when (posture) {
        NotesPosture.BOOK -> fold!!.left
        else -> (width * splitFraction).coerceIn(dp(MinLibraryWidth), (width - dp(MinEditorWidth)).coerceAtLeast(dp(MinLibraryWidth)))
    }
    // A hidden library waits beside the window in the direction it slides from.
    val hiddenLibrary = if (posture == NotesPosture.TABLETOP) {
        PaneRect(0f, height, width, height + (height - fold!!.bottom))
    } else PaneRect(-libraryWidth, 0f, 0f, height)
    val hiddenEditor = if (posture == NotesPosture.TABLETOP) PaneRect(0f, -fold!!.top, width, 0f)
        else PaneRect(width, 0f, width * 2, height)
    return when {
        single && noteOpen -> PaneTargets(posture, hiddenLibrary, full, libraryVisible = false, editorVisible = true,
            PaneEdges(), PaneEdges(), editorAboveKeyboard = false, handle = null)
        // With nothing open, the library uses the whole window instead of a placeholder pane.
        single || (posture != NotesPosture.BOOK && !noteOpen) -> PaneTargets(posture, full, hiddenEditor,
            libraryVisible = true, editorVisible = false, PaneEdges(), PaneEdges(), editorAboveKeyboard = false, handle = null)
        posture == NotesPosture.TABLETOP -> PaneTargets(posture,
            library = PaneRect(0f, fold!!.bottom, width, height),
            editor = PaneRect(0f, 0f, width, fold.top),
            libraryVisible = true, editorVisible = true,
            libraryEdges = PaneEdges(top = false, bottom = true), editorEdges = PaneEdges(top = true, bottom = false),
            editorAboveKeyboard = true, handle = null)
        posture == NotesPosture.BOOK -> PaneTargets(posture,
            library = PaneRect(0f, 0f, fold!!.left, height),
            editor = PaneRect(fold.right, 0f, width, height),
            libraryVisible = true, editorVisible = true, PaneEdges(), PaneEdges(), editorAboveKeyboard = false, handle = null)
        else -> {
            val gap = dp(SplitGap)
            PaneTargets(posture,
                library = PaneRect(0f, 0f, libraryWidth, height),
                editor = PaneRect(libraryWidth + gap, 0f, width, height),
                libraryVisible = true, editorVisible = true, PaneEdges(), PaneEdges(), editorAboveKeyboard = false,
                handle = PaneRect(libraryWidth, 0f, libraryWidth + gap, height))
        }
    }
}

private const val MinLibraryWidth = 280f
private const val MinEditorWidth = 340f
private const val SplitGap = 14f
internal const val DefaultSplitFraction = .38f

/** Live pane bounds: springs between postures, and follows a dragged divider without lag. */
@Composable
private fun animatedPaneRect(target: PaneRect, immediate: Boolean): Animatable<PaneRect, *> {
    val motion = LocalKgsMotion.current
    val bounds = remember { Animatable(target, PaneRect.VectorConverter) }
    LaunchedEffect(target, immediate) {
        if (immediate) bounds.snapTo(target) else bounds.animateTo(target, motion.defaultSpatialSpec())
    }
    return bounds
}

/**
 * Keep the live editor while relocating it between wide, book and tabletop panes.
 * Book posture shows two independent pages: each half is a library that opens its own Note,
 * and only the left page carries the menu.
 */
@Composable
internal fun AdaptiveNotesPanes(
    state: NotesUiState,
    /** (modifier, page that opens selected Notes, shows the menu) */
    library: @Composable (Modifier, NotePane, Boolean) -> Unit,
    /** (modifier, page, shows a back button, may expand to full screen) */
    editor: @Composable (Modifier, NotePane, Boolean, Boolean) -> Unit,
    onLibraryBesideEditor: (Boolean) -> Unit = {},
    onSplitFractionChange: (Float) -> Unit = {},
    onDualPagesChange: (Boolean) -> Unit = {},
    /** Where sheets from the library (left) pane and the editor (right or upper) pane open. */
    libraryRegion: KgsSheetRegion = remember { KgsSheetRegion() },
    editorRegion: KgsSheetRegion = remember { KgsSheetRegion() },
) {
    val currentLibrary by rememberUpdatedState(library)
    val currentEditor by rememberUpdatedState(editor)
    val libraryPane = remember { movableContentOf<Modifier, NotePane> { modifier, pane -> currentLibrary(modifier, pane, true) } }
    val pageLibrary = remember { movableContentOf<Modifier> { currentLibrary(it, NotePane.PRIMARY, false) } }
    val editorPane = remember {
        movableContentOf<Modifier, Boolean, Boolean> { modifier, back, expandable -> currentEditor(modifier, NotePane.PRIMARY, back, expandable) }
    }
    val pageEditor = remember { movableContentOf<Modifier> { currentEditor(it, NotePane.SECONDARY, true, false) } }
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(state.splitFraction) }
    if (!dragging) dragFraction = state.splitFraction
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { origin = it.positionInWindow() }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val fold = state.fold?.bounds?.let { bounds ->
            val margin = with(density) { 8.dp.toPx() }
            PaneRect(bounds.left - origin.x - margin, bounds.top - origin.y - margin,
                bounds.right - origin.x + margin, bounds.bottom - origin.y + margin)
        }
        val targets = paneTargets(width, height, fold, state.fold?.horizontal == true, state.paneNote(NotePane.PRIMARY) != null,
            state.editorLayout == EditorLayoutPreference.FULL_SCREEN, dragFraction, density.density)
        val dualPages = targets.posture == NotesPosture.BOOK && targets.libraryVisible && targets.editorVisible
        val besideEditor = targets.libraryVisible && targets.editorVisible
        LaunchedEffect(besideEditor) { onLibraryBesideEditor(besideEditor) }
        LaunchedEffect(dualPages) { onDualPagesChange(dualPages) }
        val libraryBounds = animatedPaneRect(targets.library, immediate = dragging)
        val editorBounds = animatedPaneRect(targets.editor, immediate = dragging)
        // Panes leave composition once they have slid out, and return before sliding in.
        val libraryMoving by remember(targets.library) { derivedStateOf { libraryBounds.value != targets.library } }
        val editorMoving by remember(targets.editor) { derivedStateOf { editorBounds.value != targets.editor } }
        val showLibrary = targets.libraryVisible || libraryMoving
        val showEditor = targets.editorVisible || editorMoving
        val layoutTag = when {
            !targets.libraryVisible || !targets.editorVisible -> "notes-full-screen-layout"
            targets.posture == NotesPosture.TABLETOP -> "notes-tabletop-layout"
            targets.posture == NotesPosture.BOOK -> "notes-book-layout"
            else -> "notes-split-layout"
        }
        Layout(
            modifier = Modifier.fillMaxSize().testTag(layoutTag),
            content = {
                if (showLibrary) Box(Modifier.layoutId(PaneSlot.LIBRARY).kgsSheetRegion(libraryRegion)
                    .then(if (targets.libraryVisible) Modifier else Modifier.clearAndSetSemantics {})) {
                    CompositionLocalProvider(LocalPaneEdges provides targets.libraryEdges,
                        LocalKgsSheetRegion provides libraryRegion.takeIf { besideEditor }) {
                        val paneModifier = Modifier.fillMaxSize().then(if (targets.libraryEdges.top) Modifier else Modifier.consumeWindowInsets(WindowInsets.statusBars))
                        if (dualPages) BookPage(state.paneNote(NotePane.SECONDARY) != null, "left") { open ->
                            if (open) pageEditor(Modifier.fillMaxSize()) else libraryPane(Modifier.fillMaxSize(), NotePane.SECONDARY)
                        } else libraryPane(paneModifier, NotePane.PRIMARY)
                    }
                }
                if (showEditor) Box(Modifier.layoutId(PaneSlot.EDITOR).kgsSheetRegion(editorRegion)
                    .then(if (targets.editorVisible) Modifier else Modifier.clearAndSetSemantics {})) {
                    CompositionLocalProvider(LocalPaneEdges provides targets.editorEdges,
                        LocalKgsSheetRegion provides editorRegion.takeIf { besideEditor }) {
                        if (dualPages) BookPage(state.paneNote(NotePane.PRIMARY) != null, "right") { open ->
                            if (open) editorPane(Modifier.fillMaxSize(), true, false) else pageLibrary(Modifier.fillMaxSize())
                        } else editorPane(
                            Modifier.fillMaxSize().then(
                                if (targets.editorAboveKeyboard) {
                                    Modifier.consumeWindowInsets(WindowInsets.ime).consumeWindowInsets(WindowInsets.navigationBars)
                                } else Modifier,
                            ),
                            !targets.libraryVisible,
                            true,
                        )
                    }
                }
                targets.handle?.let {
                    PaneDivider(
                        fraction = dragFraction,
                        modifier = Modifier.layoutId(PaneSlot.HANDLE),
                        onDrag = { delta ->
                            dragging = true
                            dragFraction = ((dragFraction * width + delta) / width).coerceIn(.2f, .8f)
                        },
                        onDragStopped = {
                            val settled = paneTargets(width, height, fold, false, true, false, dragFraction, density.density)
                            dragFraction = settled.library.width / width
                            dragging = false
                            onSplitFractionChange(dragFraction)
                        },
                        onReset = { onSplitFractionChange(DefaultSplitFraction) },
                        onAdjust = { fraction -> onSplitFractionChange(fraction.coerceIn(.2f, .8f)) },
                    )
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { measurable ->
                val bounds = when (measurable.layoutId) {
                    PaneSlot.LIBRARY -> libraryBounds.value
                    PaneSlot.EDITOR -> editorBounds.value
                    else -> targets.handle?.let { handle ->
                        val library = libraryBounds.value
                        PaneRect(library.right, 0f, library.right + handle.width, handle.height)
                    } ?: PaneRect.Zero
                }
                measurable.layoutId to (bounds to measurable.measure(Constraints.fixed(
                    bounds.width.toInt().coerceAtLeast(0), bounds.height.toInt().coerceAtLeast(0))))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEach { (_, placed) ->
                    val (bounds, placeable) = placed
                    placeable.place(bounds.left.toInt(), bounds.top.toInt())
                }
            }
        }
    }
}

private enum class PaneSlot { LIBRARY, EDITOR, HANDLE }

/** One page of book posture: its library, or the Note it opened, moving forward like a phone. */
@Composable
private fun BookPage(noteOpen: Boolean, side: String, content: @Composable (Boolean) -> Unit) {
    val motion = LocalKgsMotion.current
    AnimatedContent(
        targetState = noteOpen,
        transitionSpec = {
            val direction = if (targetState) 1 else -1
            (slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 8 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                (slideOutHorizontally(motion.fastSpatialSpec()) { -direction * it / 8 } + fadeOut(motion.fastEffectsSpec()))
        },
        label = "Book page",
        modifier = Modifier.fillMaxSize().testTag("book-page-$side"),
    ) { open -> content(open) }
}

/** A quiet handle between the panes; drag to share the width, double-tap to restore it. */
@Composable
private fun PaneDivider(
    fraction: Float,
    modifier: Modifier,
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    onReset: () -> Unit,
    onAdjust: (Float) -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val motion = LocalKgsMotion.current
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = dragged || hovered
    val handleHeight by animateDpAsState(if (active) 64.dp else 44.dp, motion.defaultSpatialSpec(), label = "Divider handle height")
    val handleWidth by animateDpAsState(if (dragged) 6.dp else 4.dp, motion.defaultSpatialSpec(), label = "Divider handle width")
    val color = if (active) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant
    val label = strings.getString(UiR.ui_resize_panes)
    Box(
        modifier
            .fillMaxHeight()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .draggable(
                state = rememberDraggableState(onDrag),
                orientation = Orientation.Horizontal,
                interactionSource = interaction,
                onDragStopped = { onDragStopped() },
            )
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, .2f..0.8f, steps = 11)
                setProgress { value -> onAdjust(value); true }
            }
            .testTag("pane-divider"),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(Modifier.size(handleWidth, handleHeight).background(color, RoundedCornerShape(50)))
    }
}

/** Expand reads as an arrow; collapse slides the library edge back into the window. */
@Composable
internal fun PaneLayoutIcon(fullScreen: Boolean, color: Color) {
    val motion = LocalKgsMotion.current
    val progress by animateFloatAsState(if (fullScreen) 1f else 0f, motion.defaultSpatialSpec(), label = "Pane layout icon")
    Canvas(Modifier.size(23.dp)) {
        val stroke = 1.6.dp.toPx()
        val left = 2.dp.toPx()
        drawRoundRect(color, Offset(left, 4.dp.toPx()), Size(size.width - 4.dp.toPx(), size.height - 8.dp.toPx()),
            CornerRadius(3.dp.toPx()), style = Stroke(1.7.dp.toPx()))
        // The divider travels from the frame's edge to its library position.
        val shown = progress.coerceIn(0f, 1f)
        if (shown > 0f) {
            val x = lerp(left + stroke, size.width * .42f, shown)
            drawLine(color.copy(alpha = color.alpha * shown), Offset(x, 5.dp.toPx()), Offset(x, size.height - 5.dp.toPx()), 1.5.dp.toPx())
        }
        val arrow = 1f - shown
        if (arrow > 0f) {
            val tint = color.copy(alpha = color.alpha * arrow)
            val scale = .6f + .4f * arrow
            val center = Offset(size.width * .535f, size.height * .465f)
            rotate(-15f * shown, center) {
                fun point(x: Float, y: Float) = Offset(center.x + (size.width * x - center.x) * scale, center.y + (size.height * y - center.y) * scale)
                drawLine(tint, point(.4f, .6f), point(.67f, .33f), stroke)
                drawLine(tint, point(.5f, .33f), point(.67f, .33f), stroke)
                drawLine(tint, point(.67f, .33f), point(.67f, .5f), stroke)
            }
        }
    }
}
