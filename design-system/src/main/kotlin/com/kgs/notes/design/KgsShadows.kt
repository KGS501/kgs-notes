package com.kgs.notes.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Soft ambient separation around the entire popup, plus a subtle contact shadow. */
fun Modifier.kgsMenuShadow(shape: Shape): Modifier = this
    .dropShadow(shape, Shadow(radius = 22.dp, spread = 2.dp, color = Color.Black.copy(alpha = .12f)))
    .dropShadow(shape, Shadow(radius = 8.dp, color = Color.Black.copy(alpha = .08f), offset = DpOffset(0.dp, 2.dp)))

/** The bottom action bar needs separation above it as well as along its sides. */
fun Modifier.kgsActionBarShadow(shape: Shape): Modifier = this
    .dropShadow(shape, Shadow(radius = 28.dp, spread = 3.dp, color = Color.Black.copy(alpha = .14f), offset = DpOffset(0.dp, (-3).dp)))
    .dropShadow(shape, Shadow(radius = 8.dp, color = Color.Black.copy(alpha = .08f)))

/** Shape-aware top separation; the flush bottom edge never casts a shadow. */
fun Modifier.kgsSheetShadow(shape: Shape, opacity: () -> Float): Modifier = this
    // Compose caches a blurred shadow by its full value, alpha included. Animating that alpha
    // would blur a new sheet-sized bitmap on the main thread every frame, so the shadow stays
    // constant and rasterized once, and only this layer fades. ModulateAlpha avoids an
    // offscreen buffer, which would cut the soft edge off at the layer's bounds.
    .graphicsLayer {
        alpha = opacity().coerceIn(0f, 1f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    .drawWithContent {
        val margin = 32.dp.toPx()
        clipRect(left = -margin, top = -margin, right = size.width + margin, bottom = size.height) {
            this@drawWithContent.drawContent()
        }
    }
    .dropShadow(shape, Shadow(radius = 22.dp, spread = 1.dp, offset = DpOffset(0.dp, (-3).dp), color = Color.Black.copy(alpha = .17f)))

/** Drawn on a separate, unscaled layer during the New Note morph. */
fun Modifier.kgsNewNoteShadow(): Modifier = this.dropShadow(
    androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    Shadow(radius = 9.dp, spread = 1.dp, color = Color.Black.copy(alpha = .17f), offset = DpOffset(0.dp, 3.dp)),
)
