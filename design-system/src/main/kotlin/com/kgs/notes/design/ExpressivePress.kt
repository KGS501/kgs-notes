package com.kgs.notes.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The single press contract for KGS controls.
 *
 * The graphics layer owns both the visual shape and its clipping boundary, so a
 * bounded ripple can never reveal the rectangular layout node behind a rounded
 * control. The interruptible spring gives quick presses the responsive,
 * slightly elastic character used throughout KGS Notes.
 */
@Composable
fun Modifier.kgsClickable(
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(14.dp),
    enabled: Boolean = true,
): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = if (pressed) 0.82f else 0.62f,
            stiffness = if (pressed) 850f else 430f,
        ),
        label = "KGS press scale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.shape = shape
            clip = true
        }
        .clickable(
            enabled = enabled,
            interactionSource = interactions,
            indication = ripple(bounded = true),
            onClick = onClick,
        )
}
