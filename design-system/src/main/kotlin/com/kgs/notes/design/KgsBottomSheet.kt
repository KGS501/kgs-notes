package com.kgs.notes.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

private class SheetEntry(val progress: () -> Float, val modal: Boolean, val docked: Boolean, val dismiss: () -> Unit) {
    var overlay by mutableStateOf<(@Composable () -> Unit)?>(null)
}
private class SheetRegistry {
    val entries = mutableStateListOf<SheetEntry>()
    val confirmations = mutableStateListOf<SheetEntry>()
}
private val LocalSheets = staticCompositionLocalOf<SheetRegistry?> { null }
val LocalKgsSheetContentHeight = staticCompositionLocalOf { 600.dp }

/** Child action panels expand inside the selection sheet, without another window. */
val LocalKgsInlineSheet = staticCompositionLocalOf { false }

/** Dismiss through the owners so their state stays closed after navigation. */
@Composable
fun rememberKgsDismissSheets(): () -> Unit {
    val registry = LocalSheets.current
    return remember(registry) {
        {
            registry?.confirmations?.toList()?.asReversed()?.forEach { it.dismiss() }
            registry?.entries?.toList()?.asReversed()?.forEach { it.dismiss() }
        }
    }
}

/** The toolbar reports the safe bottom edge for every sheet in an editor. */
class KgsSheetDock {
    var top by mutableFloatStateOf(0f)
        internal set
    var controlsBottom by mutableFloatStateOf(0f)
        internal set
}
val LocalKgsSheetDock = staticCompositionLocalOf<KgsSheetDock?> { null }

fun Modifier.kgsSheetDock(dock: KgsSheetDock?): Modifier =
    if (dock == null) this else onGloballyPositioned { dock.top = it.positionInWindow().y }

/** Header controls stay tappable above a docked sheet's outside-tap area. */
fun Modifier.kgsSheetControls(dock: KgsSheetDock?): Modifier =
    if (dock == null) this else onGloballyPositioned { dock.controlsBottom = it.positionInWindow().y + it.size.height }

/** A selection sheet retains its last menu while its handle collapses/reopens it. */
class KgsSheetExpansion {
    var expanded by mutableStateOf(false)
    var available by mutableStateOf(false)
    internal val reveal = Animatable(0f)
    internal var draggedProgress by mutableStateOf<Float?>(null)
    internal var panelHeight by mutableFloatStateOf(1f)
    internal val fraction get() = draggedProgress ?: reveal.value
}

@Composable
fun KgsExpandableSheetPanel(state: KgsSheetExpansion, content: @Composable () -> Unit) {
    val motion = LocalKgsMotion.current
    LaunchedEffect(state.expanded, state.available, state.draggedProgress == null) {
        if (state.draggedProgress == null) {
            state.reveal.animateTo(if (state.expanded && state.available) 1f else 0f, tween(300, easing = motion.standard))
        }
    }
    Box(Modifier.clipToBounds()
        .then(if (state.fraction <= .001f) Modifier.clearAndSetSemantics {} else Modifier)
        .layout { measurable, constraints ->
            val panel = measurable.measure(constraints)
            state.panelHeight = panel.height.toFloat().coerceAtLeast(1f)
            val height = (panel.height * state.fraction).roundToInt()
            layout(panel.width, height) { panel.place(0, height - panel.height) }
        }
        .graphicsLayer { alpha = state.fraction.coerceIn(0f, 1f) },
    ) { content() }
}

/** Observe empty-space taps while keeping the library fully interactive. */
@Composable
internal fun KgsSheetHost(content: @Composable () -> Unit) {
    val registry = remember { SheetRegistry() }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalSheets provides registry) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val blur = registry.confirmations.maxOfOrNull { it.progress() } ?: 0f
                renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && blur > .001f) {
                    val radius = with(density) { 10.dp.toPx() } * blur
                    BlurEffect(radius, radius, TileMode.Clamp)
                } else null
            }) {
                Box(Modifier.fillMaxSize().pointerInput(registry) {
                    // Observe only unhandled taps. Note clicks and scroll gestures
                    // continue to work behind the modeless Note selection sheet.
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        var moved = false
                        var handled = first.isConsumed
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull { it.id == first.id } ?: break
                            moved = moved || (change.position - first.position).getDistance() > viewConfiguration.touchSlop
                            handled = handled || change.isConsumed
                            if (change.changedToUpIgnoreConsumed()) {
                                if (!moved && !handled) registry.entries.lastOrNull()?.takeIf { !it.modal }?.dismiss?.invoke()
                                break
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }) { content() }
                registry.entries.forEach { entry ->
                    key(entry) {
                        entry.overlay?.let { overlay ->
                            Box(Modifier.fillMaxSize(), contentAlignment = if (entry.docked) Alignment.TopCenter else Alignment.BottomCenter) { overlay() }
                        }
                    }
                }
            }
        }
    }
}

/** Content-sized sheet, flush to the screen edges, retained until its exit finishes. */
@Composable
fun KgsBottomSheet(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    modal: Boolean = true,
    focusable: Boolean = true,
    title: String = "Options",
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onHidden: () -> Unit = {},
    animateSizeChanges: Boolean = true,
    expansion: KgsSheetExpansion? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalKgsMotion.current
    if (LocalKgsInlineSheet.current) {
        if (expanded) {
            // The parent panel owns menu switches. This only animates changes
            // within an already open menu, such as the Category creation form.
            Column(modifier.fillMaxWidth()
                .animateContentSize(tween(300, easing = motion.standard), alignment = Alignment.BottomStart)
                .semantics { paneTitle = title }, content = content)
        }
        return
    }
    val dock = LocalKgsSheetDock.current
    val progress = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(expanded) }
    var drag by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableIntStateOf(1) }
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentHidden by rememberUpdatedState(onHidden)
    LaunchedEffect(expanded) {
        if (!expanded && !mounted) return@LaunchedEffect
        if (expanded) { mounted = true; drag = 0f }
        progress.animateTo(if (expanded) 1f else 0f, tween(if (expanded) 320 else 240, easing = motion.standard))
        if (!expanded) {
            expansion?.let {
                it.reveal.snapTo(0f)
                it.draggedProgress = null
                it.expanded = false
                it.available = false
            }
            mounted = false
            currentHidden()
        }
    }
    if (!mounted) return
    BackHandler(enabled = expanded && modal) { currentDismiss() }
    val registry = LocalSheets.current
    val entry = remember {
        SheetEntry({ progress.value * (1f - drag / sheetHeight).coerceIn(0f, 1f) }, modal, dock != null) { currentDismiss() }
    }
    DisposableEffect(registry, entry) {
        registry?.entries?.add(entry)
        onDispose { registry?.entries?.remove(entry) }
    }
    val dragReturn = remember { Animatable(0f) }
    var gestureDistance by remember { mutableFloatStateOf(0f) }
    var gestureStart by remember { mutableFloatStateOf(0f) }
    var gestureExpanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Non-focusable windows do not reliably receive IME insets themselves.
    // Read the editor's insets before entering the popup so its keyboard stays open.
    val parentKeyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val velocityThreshold = with(density) { 900.dp.toPx() }
    val collapseThreshold = with(density) { 28.dp.toPx() }
    val dockHeight = with(density) { (dock?.top ?: 0f).toDp() }
    val controlsHeight = with(density) { (dock?.controlsBottom ?: 0f).toDp() }
    val body: @Composable () -> Unit = {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(expanded) {
            if (!expanded && modal && focusable && dock == null) {
                focus.clearFocus()
                keyboard?.hide()
            }
        }
        BoxWithConstraints((if (dock != null) Modifier.fillMaxWidth().requiredHeight(dockHeight).clipToBounds() else if (modal) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .testTag("kgs-sheet-frame")
            .then(if (dock != null) Modifier else if (modal && focusable) Modifier.imePadding() else Modifier.padding(bottom = parentKeyboard))) {
            val navigation = if (dock != null) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val sheetTop = maxOf(top, controlsHeight)
            val availableHeight = (maxHeight - sheetTop - 8.dp).coerceAtLeast(0.dp)
            val contentHeight = (availableHeight - navigation - 36.dp).coerceAtLeast(0.dp)
            if (modal) {
                Box(Modifier.fillMaxSize().padding(top = controlsHeight).semantics { contentDescription = "Dismiss sheet" }
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null, onClick = { currentDismiss() }))
            }
            val sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
            Surface(
                color = containerColor,
                shape = sheetShape,
                shadowElevation = 0.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .heightIn(max = availableHeight)
                    .graphicsLayer {
                        translationY = (1f - progress.value) * sheetHeight + drag
                    }
                    .kgsSheetShadow(sheetShape) { entry.progress() }
                    .onSizeChanged { sheetHeight = it.height }
                    .then(modifier)
                    .consumeSurfaceTaps()
                    .semantics {
                        paneTitle = title
                        dismiss { currentDismiss(); true }
                    },
            ) {
                Column(Modifier.fillMaxWidth().testTag("kgs-sheet-body")
                    .then(if (animateSizeChanges) Modifier.animateContentSize(tween(300, easing = motion.standard), alignment = Alignment.BottomStart) else Modifier)
                    .padding(bottom = navigation)) {
                    Box(Modifier.fillMaxWidth().height(36.dp).testTag("kgs-sheet-handle")
                        .semantics { contentDescription = "Drag to collapse, expand or close sheet"; dismiss { currentDismiss(); true } }
                        .draggable(rememberDraggableState { delta ->
                            gestureDistance += delta
                            if (expansion != null && expansion.available) {
                                expansion.draggedProgress = (gestureStart - gestureDistance / expansion.panelHeight).coerceIn(0f, 1f)
                                drag = (gestureDistance - gestureStart * expansion.panelHeight).coerceAtLeast(0f)
                            } else drag = (drag + delta).coerceAtLeast(0f)
                        },
                            orientation = Orientation.Vertical,
                            onDragStarted = {
                                dragReturn.stop()
                                gestureDistance = 0f
                                gestureExpanded = expansion?.expanded == true
                                gestureStart = expansion?.fraction ?: 0f
                                expansion?.takeIf { it.available }?.let {
                                    it.reveal.stop()
                                    it.draggedProgress = gestureStart
                                }
                            },
                            onDragStopped = { velocity ->
                                val dismiss = velocity > velocityThreshold ||
                                    ((!gestureExpanded || expansion?.available != true) && drag > sheetHeight * .25f)
                                expansion?.let {
                                    it.reveal.snapTo(it.fraction)
                                    it.draggedProgress = null
                                    if (!dismiss && it.available) {
                                        it.expanded = when {
                                            gestureDistance > collapseThreshold -> false
                                            gestureDistance < -collapseThreshold -> true
                                            else -> gestureExpanded
                                        }
                                    }
                                }
                                if (dismiss) currentDismiss()
                                else {
                                    dragReturn.snapTo(drag)
                                    // Return both the panel and the surface to their anchors.
                                    dragReturn.animateTo(0f, motion.defaultSpatialSpec()) { drag = value.coerceAtLeast(0f) }
                                }
                            }), contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), RoundedCornerShape(2.dp)))
                    }
                    CompositionLocalProvider(LocalKgsSheetContentHeight provides contentHeight) {
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
                    }
                }
            }
        }
    }
    val latestBody by rememberUpdatedState(body)
    if (registry != null && (dock != null || !modal)) {
        DisposableEffect(entry) {
            entry.overlay = { latestBody() }
            onDispose { entry.overlay = null }
        }
        return
    }
    when {
        dock != null -> Popup(popupPositionProvider = remember { SheetWindowPosition(true) },
            properties = PopupProperties(focusable = false, dismissOnClickOutside = false,
                dismissOnBackPress = false, clippingEnabled = false), content = body)
        !modal -> Popup(popupPositionProvider = remember { SheetWindowPosition(false) },
            properties = PopupProperties(focusable = false, dismissOnClickOutside = false,
                dismissOnBackPress = false, clippingEnabled = false), content = body)
        !focusable -> Popup(popupPositionProvider = remember { SheetWindowPosition(true) }, onDismissRequest = { currentDismiss() },
            properties = PopupProperties(focusable = false, clippingEnabled = false), content = body)
        else -> Dialog(onDismissRequest = { currentDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setDimAmount(0f); window?.setWindowAnimations(0) }
            body()
        }
    }
}

/** Popup sheets use screen edges, independent of the button's position. */
private class SheetWindowPosition(private val fullScreen: Boolean) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ) = IntOffset(0, if (fullScreen) 0 else windowSize.height - popupContentSize.height)
}

/** Padding consumes taps without turning the whole surface into one accessibility control. */
private fun Modifier.consumeSurfaceTaps(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.filter { it.changedToUpIgnoreConsumed() && !it.isConsumed }.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** Destructive confirmations float centrally; only these dialogs blur the background. */
@Composable
fun KgsConfirmationDialog(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String = "Confirm deletion",
    content: @Composable () -> Unit,
) {
    val registry = LocalSheets.current
    val motion = LocalKgsMotion.current
    val progress = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(expanded) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(expanded) {
        if (!expanded && !mounted) return@LaunchedEffect
        if (expanded) mounted = true
        progress.animateTo(if (expanded) 1f else 0f, tween(if (expanded) 260 else 200, easing = motion.standard))
        if (!expanded) mounted = false
    }
    if (!mounted) return
    val entry = remember { SheetEntry({ progress.value }, true, false) { dismiss() } }
    DisposableEffect(registry, entry) {
        registry?.confirmations?.add(entry)
        onDispose { registry?.confirmations?.remove(entry) }
    }
    Dialog(onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f); window?.setWindowAnimations(0) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
                .background(Color.Black.copy(alpha = .14f))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = { dismiss() }))
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 380.dp)
                    .graphicsLayer {
                        alpha = progress.value
                        scaleX = .96f + .04f * progress.value
                        scaleY = scaleX
                    }
                    .kgsMenuShadow(RoundedCornerShape(26.dp))
                    .testTag("kgs-confirmation-dialog")
                    .semantics { paneTitle = title }
                    .consumeSurfaceTaps(),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }
}

/** Shared layout for confirmations and text-entry sheets. */
@Composable
fun KgsSheetDialog(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    onHidden: () -> Unit = {},
) {
    KgsBottomSheet(expanded, onDismissRequest, onHidden = onHidden) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            icon?.invoke()
            ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                dismissButton()
                confirmButton()
            }
        }
    }
}
