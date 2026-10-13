package com.kgs.notes

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val NoteCardFooterHeight = 42.dp

/** One keyed, virtualized collection for both layouts; content never enters a fullscreen overlay. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun NoteCollection(
    notes: List<NoteSummary>,
    view: LibraryView,
    previewModes: Map<NoteId, EditorMode>,
    scrollOffset: MutableFloatState,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    groupByDate: Boolean = false,
    dateByCreated: Boolean = false,
    onScroll: () -> Unit = {},
    item: @Composable (NoteSummary, Modifier) -> Unit,
) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                zone = ZoneId.systemDefault()
                today = LocalDate.now(zone)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(zone) {
        while (isActive) {
            val now = ZonedDateTime.now(zone)
            today = now.toLocalDate()
            delay(Duration.between(now, today.plusDays(1).atStartOfDay(zone)).toMillis().coerceAtLeast(1L))
        }
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 128)
    val typography = MaterialTheme.typography
    val linkColor = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val progress = animateFloatAsState(
        if (view == LibraryView.CARDS) 1f else 0f,
        // Card bounds settle without overshoot; text stays at its actual font size.
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "Note layout bounds",
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.roundToPx() }
        val viewportHeight = with(density) { maxHeight.roundToPx() }
        val gap = with(density) { 10.dp.roundToPx() }
        val cardWidth = ((width - gap) / 2).coerceAtLeast(1)
        val padding = with(density) { 34.dp.roundToPx() }
        val cache = remember(width, density, typography, linkColor) {
            mutableMapOf<NoteId, Pair<List<Any>, Pair<Int, Int>>>()
        }
        val heights = remember(notes, previewModes, width, density, typography, linkColor) {
            cache.keys.retainAll(notes.map { it.id }.toSet())
            notes.map { note ->
                val pending = note.syncState in setOf(NoteSyncState.SAVED_LOCALLY, NoteSyncState.SYNCING, NoteSyncState.NEEDS_ATTENTION)
                val mode = previewModes[note.id] ?: EditorMode.RICH
                val key = listOf(note.title, note.previewMarkdown, note.snippet, mode, pending)
                cache[note.id]?.takeIf { it.first == key }?.let { return@map it.second }
                val preview = noteCardPreview(note.previewMarkdown.ifBlank { note.snippet }, mode, linkColor)
                fun height(itemWidth: Int, cards: Boolean): Int {
                    val contentWidth = (itemWidth - padding).coerceAtLeast(1)
                    val title = textMeasurer.measure(note.title, typography.titleMedium,
                        maxLines = 1, softWrap = false,
                        constraints = Constraints(maxWidth = (itemWidth - with(density) { 17.dp.roundToPx() }).coerceAtLeast(1)))
                    val body = if (preview.isEmpty()) 0 else textMeasurer.measure(preview, typography.bodyMedium,
                        maxLines = if (cards) 14 else 2, constraints = Constraints(maxWidth = contentWidth)).size.height
                    val chrome = with(density) {
                        17.dp.roundToPx() + title.size.height + NoteCardFooterHeight.roundToPx()
                    }
                    return if (cards) min(chrome + body, max((itemWidth * 1.85f).roundToInt(), chrome)) else chrome + body
                }
                (height(width, false) to height(cardWidth, true)).also { cache[note.id] = key to it }
            }
        }
        val targets = remember(notes, heights, width, cardWidth, gap, groupByDate, dateByCreated, today, zone, density, typography) {
            val result = mutableListOf<CollectionTarget>()
            val topInset = with(density) { 14.dp.toPx() }
            var listTop = topInset
            var cardTop = topInset
            val groups = buildList {
                if (!groupByDate) add(notes to null)
                else {
                    // Disjoint local calendar ranges, never elapsed 24-hour periods.
                    // Keep the caller's chosen sort within each section.
                    val buckets = notes.groupBy { note ->
                        val date = (if (dateByCreated) note.createdAt else note.updatedAt).atZone(zone).toLocalDate()
                        when {
                            date >= today -> 0 to today
                            date == today.minusDays(1) -> 1 to today.minusDays(1)
                            date >= today.minusDays(6) -> 2 to today.minusDays(6)
                            date >= today.minusDays(29) -> 3 to today.minusDays(29)
                            else -> 4 to date.withDayOfMonth(1)
                        }
                    }
                    buckets.entries.sortedWith(compareBy<Map.Entry<Pair<Int, LocalDate>, List<NoteSummary>>> { it.key.first }
                        .thenByDescending { it.key.second }).forEach { (bucket, group) ->
                        val date = bucket.second
                        val label = when (bucket.first) {
                            0 -> "Today"
                            1 -> "Yesterday"
                            2 -> "Last 7 Days"
                            3 -> "Last 30 Days"
                            else -> date.format(DateTimeFormatter.ofPattern(
                                if (date.year == today.year) "MMMM" else "MMMM yyyy", Locale.getDefault()))
                        }
                        add(group to label)
                    }
                }
            }
            val heightsById = notes.mapIndexed { index, note -> note.id to heights[index] }.toMap()
            groups.forEach { (group, heading) ->
                if (group.isEmpty()) return@forEach
                if (heading != null) {
                    val headingTextHeight = textMeasurer.measure(heading, typography.titleLarge,
                        constraints = Constraints(maxWidth = width.coerceAtLeast(1))).size.height
                    val headingHeight = headingTextHeight + with(density) { 4.dp.toPx() }
                    result += CollectionTarget("heading:$heading", null, heading,
                        CardBounds(0f, listTop, width.toFloat(), headingHeight),
                        CardBounds(0f, cardTop, width.toFloat(), headingHeight))
                    listTop += headingHeight + with(density) { 4.dp.toPx() }
                    cardTop += headingHeight + with(density) { 4.dp.toPx() }
                }
                val lanes = floatArrayOf(cardTop, cardTop)
                group.forEachIndexed { index, note ->
                    val (listHeight, cardHeight) = heightsById.getValue(note.id)
                    val lane = index % 2
                    result += CollectionTarget(note.id.value, note, null,
                        CardBounds(0f, listTop, width.toFloat(), listHeight.toFloat()),
                        CardBounds((lane * (cardWidth + gap)).toFloat(), lanes[lane], cardWidth.toFloat(), cardHeight.toFloat()))
                    listTop += listHeight + gap
                    lanes[lane] += cardHeight + gap
                }
                cardTop = lanes.max()
            }
            result
        }
        val items = remember { mutableStateMapOf<String, CollectionCardMotion>() }
        // One position clock and one opacity clock for the entire reflow, rather
        // than hundreds of per-card animation coroutines and snapshot writes.
        val reflowProgress = remember { mutableFloatStateOf(1f) }
        val opacityProgress = remember { mutableFloatStateOf(1f) }
        val initialized = remember { booleanArrayOf(false) }
        // Keep departing items in the provider immediately, before the animation
        // effect runs. Re-entering items must never appear twice under one key.
        val targetKeys = targets.mapTo(hashSetOf()) { it.key }
        val entries = targets.mapNotNull { items[it.key] } + items.values.filter { it.target.key !in targetKeys }
        val listBounds = targets.map { it.list }
        val cardBounds = targets.map { it.cards }
        val targetBounds = if (view == LibraryView.LIST) listBounds else cardBounds
        val maximum = ((targetBounds.maxOfOrNull { it.bottom } ?: 0f) +
            with(density) { bottomPadding.toPx() } - viewportHeight).coerceAtLeast(0f)
        val motion = remember { CollectionMotion(view, scrollOffset.floatValue) }
        if (motion.view != view) {
            val fraction = progress.value.coerceIn(0f, 1f)
            val currentOffset = motion.offset(fraction, scrollOffset.floatValue)
            val anchor = targets.indices.firstOrNull {
                val bounds = listBounds[it].interpolate(cardBounds[it], fraction)
                bounds.bottom > currentOffset && bounds.top < currentOffset + viewportHeight
            }
            val destinationOffset = if (scrollOffset.floatValue <= .5f) 0f else
                (anchor?.let { targetBounds[it].top } ?: 0f).coerceIn(0f, maximum)
            motion.retarget(view, fraction, currentOffset, destinationOffset)
            // Resolve the anchor before the first new frame, rather than moving a
            // second scroll container after its shared transition has started.
            scrollOffset.floatValue = destinationOffset
        }
        if (motion.maximum != maximum) {
            motion.maximum = maximum
        }
        LaunchedEffect(targets, maximum) {
            val previousOffset = scrollOffset.floatValue
            val nextOffset = previousOffset.coerceIn(0f, maximum)
            if (nextOffset != previousOffset) {
                // Apply the scroll clamp and coordinate correction together, so
                // visible cards never disappear between a query and its reflow.
                items.values.forEach { it.shiftBy(nextOffset - previousOffset) }
                scrollOffset.floatValue = nextOffset
            }
            val wanted = targets.mapTo(hashSetOf()) { it.key }
            val previousProgress = reflowProgress.floatValue
            val previousOpacity = opacityProgress.floatValue
            targets.forEach { target ->
                val current = items[target.key]
                if (current == null) items[target.key] = CollectionCardMotion(target, if (initialized[0]) 0f else 1f)
                else current.retarget(target, previousProgress, previousOpacity)
            }
            items.values.filter { it.target.key !in wanted }.forEach { card ->
                card.depart(previousProgress, previousOpacity)
            }
            initialized[0] = true
            val moving = items.values.any { it.fromList != it.target.list || it.fromCards != it.target.cards }
            val fading = items.values.any { it.fromAlpha != it.targetAlpha }
            reflowProgress.floatValue = if (moving) 0f else 1f
            opacityProgress.floatValue = if (fading) 0f else 1f
            coroutineScope {
                listOf(
                    launch { if (moving) animate(0f, 1f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ -> reflowProgress.floatValue = value } },
                    launch { if (fading) animate(0f, 1f, animationSpec = tween(180)) { value, _ -> opacityProgress.floatValue = value } },
                ).joinAll()
            }
            items.values.filterNot { it.removing }.forEach { it.settle() }
            items.values.filter { it.removing }.forEach { items.remove(it.target.key) }
        }
        val maximumNow = rememberUpdatedState(maximum)
        val notifyScroll = rememberUpdatedState(onScroll)
        val scrolling = rememberScrollableState { delta ->
            val previous = scrollOffset.floatValue
            scrollOffset.floatValue = (previous + delta).coerceIn(0f, maximumNow.value)
            (scrollOffset.floatValue - previous).also { consumed ->
                if (consumed != 0f) notifyScroll.value()
            }
        }
        val indices = entries.mapIndexed { index, card -> card.target.key to index }.toMap()
        val provider = object : LazyLayoutItemProvider {
            override val itemCount = entries.size
            override fun getKey(index: Int): Any = entries[index].target.key
            override fun getIndex(key: Any): Int = indices[key] ?: -1
            @Composable
            override fun Item(index: Int, key: Any) {
                val card = entries[index]
                val itemModifier = Modifier.graphicsLayer { alpha = card.opacity(opacityProgress.floatValue) }
                    .semantics { traversalIndex = index.toFloat() }
                val note = card.target.note
                if (note != null) item(note, itemModifier)
                else Text(card.target.heading.orEmpty(), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = itemModifier.semantics { heading() }.testTag("notes-section-${card.target.heading}"))
            }
        }
        LazyLayout(
            itemProvider = { provider },
            modifier = Modifier.fillMaxSize().clipToBounds()
                .testTag(if (view == LibraryView.LIST) "notes-list" else "notes-cards")
                .scrollable(scrolling, Orientation.Vertical, reverseDirection = true)
                .semantics {
                    isTraversalGroup = true
                    collectionInfo = if (view == LibraryView.CARDS) CollectionInfo((notes.size + 1) / 2, 2) else CollectionInfo(notes.size, 1)
                    verticalScrollAxisRange = ScrollAxisRange({ scrollOffset.floatValue }, { maximum })
                    scrollBy { _, y -> scope.launch { scrolling.scroll { scrollBy(y) } }; true }
                    scrollToIndex { index ->
                        if (index !in notes.indices) false else {
                            notifyScroll.value()
                            scrollOffset.floatValue = targets.first { it.note?.id == notes[index].id }.let {
                                (if (view == LibraryView.LIST) it.list else it.cards).top
                            }.coerceIn(0f, maximum)
                            true
                        }
                    }
                },
        ) { constraints ->
            // Width/height changes need measurement. Position-only reflows (such
            // as deletion) read their clock in placement and move cached layers.
            val fraction = progress.value.coerceIn(0f, 1f)
            val offset = motion.offset(fraction, scrollOffset.floatValue)
            val overscan = 64.dp.toPx()
            val visible = entries.indices.mapNotNull { index ->
                val card = entries[index]
                val start = card.fromList.interpolate(card.fromCards, fraction)
                val end = card.target.list.interpolate(card.target.cards, fraction)
                // Keep both ends of a moving card composed until the reflow
                // finishes, without forcing a new measurement on every frame.
                if (max(start.bottom, end.bottom) < offset - overscan || min(start.top, end.top) > offset + constraints.maxHeight + overscan) null
                else {
                    val bounds = if (start.width == end.width && start.height == end.height) end
                        else start.interpolate(end, reflowProgress.floatValue)
                    index to measure(index, Constraints.fixed(bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1)))
                }
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val placementProgress = reflowProgress.floatValue
                visible.forEach { (index, placeables) ->
                    val card = entries[index]
                    val bounds = card.listBounds(placementProgress).interpolate(card.cardBounds(placementProgress), fraction)
                    placeables.forEach { it.placeRelativeWithLayer(bounds.left.roundToInt(), (bounds.top - offset).roundToInt()) }
                }
            }
        }
    }
}

private data class CollectionTarget(
    val key: String, val note: NoteSummary?, val heading: String?, val list: CardBounds, val cards: CardBounds,
)

/** Retains departing items until they fade, and retargets from their current bounds on fast searches. */
private class CollectionCardMotion(initialTarget: CollectionTarget, initialAlpha: Float) {
    var target by mutableStateOf(initialTarget)
    var fromList by mutableStateOf(initialTarget.list)
    var fromCards by mutableStateOf(initialTarget.cards)
    var removing by mutableStateOf(false)
    var fromAlpha = initialAlpha
    val targetAlpha: Float get() = if (removing) 0f else 1f
    fun listBounds(progress: Float) = fromList.interpolate(target.list, progress)
    fun cardBounds(progress: Float) = fromCards.interpolate(target.cards, progress)
    fun opacity(progress: Float) = mix(fromAlpha, targetAlpha, progress)
    fun shiftBy(delta: Float) {
        fromList = fromList.copy(top = fromList.top + delta)
        fromCards = fromCards.copy(top = fromCards.top + delta)
        target = target.copy(list = target.list.copy(top = target.list.top + delta),
            cards = target.cards.copy(top = target.cards.top + delta))
    }
    fun retarget(next: CollectionTarget, progress: Float, opacityProgress: Float) {
        fromList = listBounds(progress)
        fromCards = cardBounds(progress)
        fromAlpha = opacity(opacityProgress)
        target = next
        removing = false
    }
    fun depart(progress: Float, opacityProgress: Float) {
        fromList = listBounds(progress)
        fromCards = cardBounds(progress)
        fromAlpha = opacity(opacityProgress)
        // Departing cards fade in place; only the survivors fill the gap.
        target = target.copy(list = fromList, cards = fromCards)
        removing = true
    }
    fun settle() {
        fromList = target.list
        fromCards = target.cards
        fromAlpha = targetAlpha
    }
}

private data class CardBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    val bottom: Float get() = top + height
    fun interpolate(other: CardBounds, progress: Float) = CardBounds(
        mix(left, other.left, progress), mix(top, other.top, progress),
        mix(width, other.width, progress), mix(height, other.height, progress),
    )
}

private fun mix(from: Float, to: Float, progress: Float) = from + (to - from) * progress

private class CollectionMotion(var view: LibraryView, initialOffset: Float) {
    var maximum: Float = Float.NaN
    private var listOffset = initialOffset
    private var cardOffset = initialOffset
    private var targetOffset = initialOffset
    fun offset(progress: Float, scrollOffset: Float) = mix(listOffset, cardOffset, progress) + scrollOffset - targetOffset
    fun retarget(next: LibraryView, progress: Float, current: Float, destination: Float) {
        if (next == LibraryView.CARDS) {
            cardOffset = destination
            listOffset = if (progress < .999f) (current - destination * progress) / (1f - progress) else current
        } else {
            listOffset = destination
            cardOffset = if (progress > .001f) (current - destination * (1f - progress)) / progress else current
        }
        targetOffset = destination
        view = next
    }
}
