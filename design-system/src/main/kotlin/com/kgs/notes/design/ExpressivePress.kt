package com.kgs.notes.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The fallback press contract for custom-drawn KGS controls.
 *
 * The graphics layer owns both the visual shape and its clipping boundary, so a
 * bounded ripple can never reveal the rectangular layout node behind a rounded
 * control. Filled controls use [KgsExpressiveSurface] so their container can
 * participate in Material 3 Expressive's pressed-shape morph as well.
 */
@Composable
fun Modifier.kgsClickable(
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(14.dp),
    enabled: Boolean = true,
    showIndication: Boolean = true,
): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val hapticClick = rememberKgsHapticClick(onClick)
    return this
        .graphicsLayer {
            this.shape = shape
            clip = true
        }
        .combinedClickable(
            enabled = enabled,
            interactionSource = interactions,
            indication = if (showIndication) ripple(bounded = true) else null,
            onClick = hapticClick,
            onLongClick = {},
        )
}

@Immutable
data class KgsCornerRadii(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) {
    constructor(radius: Dp) : this(radius, radius, radius, radius)
}

/**
 * Material 3 Expressive press surface for app-owned controls.
 *
 * A press morphs the visible container toward an 8dp shape with the effects
 * spring while the bounded ripple remains inside that same animated outline.
 * Width changes for connected button groups are owned by their parent layout.
 */
@Composable
fun KgsExpressiveSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current,
    restingCorners: KgsCornerRadii = KgsCornerRadii(14.dp),
    pressedCorners: KgsCornerRadii = KgsCornerRadii(8.dp),
    enabled: Boolean = true,
    showIndication: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val hapticClick = rememberKgsHapticClick(onClick)
    val pressed by interactionSource.collectIsVisuallyPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = LocalKgsMotion.current.defaultEffectsSpec(),
        label = "KGS pressed shape",
    )
    fun corner(resting: Dp, target: Dp): Dp = resting + (target - resting) * progress
    val shape = RoundedCornerShape(
        topStart = corner(restingCorners.topStart, pressedCorners.topStart),
        topEnd = corner(restingCorners.topEnd, pressedCorners.topEnd),
        bottomEnd = corner(restingCorners.bottomEnd, pressedCorners.bottomEnd),
        bottomStart = corner(restingCorners.bottomStart, pressedCorners.bottomStart),
    )
    Box(
        contentAlignment = contentAlignment,
        modifier = modifier
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .background(color, shape)
            .combinedClickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = if (showIndication) ripple(bounded = true) else null,
                onClick = hapticClick,
                onLongClick = {},
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Retains a quick press long enough for the expressive shape/width spring to be
 * legible, matching Material's connected-button behavior without delaying the
 * action itself.
 */
@Composable
fun MutableInteractionSource.collectIsVisuallyPressedAsState(
    releaseHoldMillis: Long = 120L,
): State<Boolean> {
    val visible = remember(this) { mutableStateOf(false) }
    LaunchedEffect(this, releaseHoldMillis) {
        val activePresses = mutableSetOf<PressInteraction.Press>()
        var releaseJob: Job? = null
        interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    activePresses += interaction
                    releaseJob?.cancel()
                    visible.value = true
                }
                is PressInteraction.Release -> {
                    activePresses -= interaction.press
                    if (activePresses.isEmpty()) {
                        releaseJob = launch {
                            delay(releaseHoldMillis)
                            if (activePresses.isEmpty()) visible.value = false
                        }
                    }
                }
                is PressInteraction.Cancel -> {
                    activePresses -= interaction.press
                    if (activePresses.isEmpty()) {
                        releaseJob = launch {
                            delay(releaseHoldMillis)
                            if (activePresses.isEmpty()) visible.value = false
                        }
                    }
                }
            }
        }
    }
    return visible
}

/** Adds the same short, restrained feedback to every app-owned button action. */
@Composable
fun rememberKgsHapticClick(onClick: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val currentOnClick by rememberUpdatedState(onClick)
    return remember(haptics) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            currentOnClick()
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun KgsTooltip(
    label: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
