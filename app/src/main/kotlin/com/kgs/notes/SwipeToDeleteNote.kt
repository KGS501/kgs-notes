package com.kgs.notes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Swiping right past the threshold moves the Note to Trash, which keeps it recoverable. */
@Composable
internal fun SwipeToDeleteNote(
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val action by rememberUpdatedState(onDelete)
    val haptics = LocalHapticFeedback.current
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    val maximumPull = with(LocalDensity.current) { 132.dp.toPx() }
    val intentDistance = with(LocalDensity.current) { 18.dp.toPx() }
    val threshold = if (measuredWidth > 0f) minOf(maximumPull, measuredWidth * .65f) else maximumPull
    var armed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var targetDisplacement by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    val travel by animateFloatAsState(
        targetDisplacement,
        spring(dampingRatio = if (armed) .72f else if (dragging) 1f else .85f,
            stiffness = if (dragging && !armed) 1100f else 550f),
        label = "Delete swipe elastic travel",
    )
    val exit = remember { Animatable(0f) }
    var exiting by remember { mutableStateOf(false) }
    val displacement = if (exiting) exit.value else travel
    val iconScale by animateFloatAsState(if (armed || leaving) 1.12f else 1f,
        spring(dampingRatio = .8f, stiffness = 500f), label = "Delete swipe threshold")
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        // The card finishes leaving before the library closes the gap behind it.
        exit.snapTo(travel)
        exiting = true
        exit.animateTo(measuredWidth, tween(180, easing = FastOutLinearInEasing))
        action()
        // Normally the Note is gone by now. If it stays, bring its card back.
        delay(1_500)
        exiting = false
        leaving = false
    }
    Box(modifier.onSizeChanged { measuredWidth = it.width.toFloat() }.pointerInput(enabled, threshold, intentDistance) {
        if (enabled) awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var accepted = false
            var pulsed = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                    if (!change.pressed) {
                        if (accepted) {
                            change.consume()
                            if (armed) leaving = true
                        }
                        break
                    }
                    val delta = change.position - down.position
                    if (!accepted) {
                        // Do not consume until a deliberate rightward gesture wins.
                        // Once scrolling wins, later sideways drift cannot arm a delete.
                        val slop = viewConfiguration.touchSlop
                        if (abs(delta.y) > slop && delta.x < abs(delta.y) * 1.8f) break
                        if (delta.x < -slop) break
                        accepted = delta.x >= maxOf(intentDistance, slop * 2f) && delta.x >= abs(delta.y) * 1.8f
                    }
                    if (accepted) {
                        change.consume()
                        dragging = true
                        val pull = delta.x.coerceAtLeast(0f)
                        armed = pull >= threshold
                        // Cling close to the resting edge, building tension. Crossing
                        // the threshold releases that tension into a spring toward
                        // the finger; extra travel remains elastic rather than linear.
                        targetDisplacement = if (!armed) {
                            val strain = pull / threshold
                            pull * .18f + threshold * .12f * strain * strain * strain
                        } else {
                            val extra = pull - threshold
                            threshold * .8f + .8f * extra / (1f + extra / (threshold * 1.5f))
                        }
                        if (armed && !pulsed) {
                            pulsed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else {
                        // Give the parent scroll gesture its turn before inspecting
                        // the next movement. A consumed scroll is never reclaimed.
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.any { it.id == down.id && it.isConsumed }) break
                    }
                }
            } finally {
                armed = false
                dragging = false
                targetDisplacement = 0f
            }
        }
    }) {
        if (displacement > .5f) {
            Box(Modifier.fillMaxSize().background(favoriteContainerColor(), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.CenterStart) {
                Icon(Icons.Default.Delete, contentDescription = null,
                    tint = if (com.kgs.notes.design.LocalKgsDarkTheme.current) Color(0xFFFF8A9A) else Color(0xFFD13B4B),
                    modifier = Modifier.padding(start = 12.dp).size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
            }
        }
        content(Modifier.fillMaxSize().graphicsLayer { translationX = displacement })
    }
}
