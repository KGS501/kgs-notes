package com.kgs.notes.design

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect

/** Fixed edge masks: their size does not grow with the text or its container. */
fun Modifier.kgsHorizontalTextFade(start: Dp = 0.dp, end: Dp = 22.dp): Modifier {
    if (start <= 0.dp && end <= 0.dp) return this
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val left = start.toPx().coerceIn(0f, size.width / 2f)
        val right = end.toPx().coerceIn(0f, size.width / 2f)
        if (left > 0f) drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = left),
            size = Size(left, size.height), blendMode = BlendMode.DstIn,
        )
        if (right > 0f) drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - right, endX = size.width),
            topLeft = Offset(size.width - right, 0f), size = Size(right, size.height), blendMode = BlendMode.DstIn,
        )
    }
}

/** The editor's plain, scrolling Title; fade only the edges hiding more text. */
@Composable
fun KgsNoteTitleField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
    height: Dp = 40.dp,
) {
    val field = rememberTextFieldState(value, initialSelection = TextRange.Zero)
    val scroll = rememberScrollState()
    val interactions = remember { MutableInteractionSource() }
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (value != field.text.toString()) field.edit {
            val retainedSelection = TextRange(selection.start.coerceAtMost(value.length), selection.end.coerceAtMost(value.length))
            replace(0, length, value)
            selection = retainedSelection
        }
    }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { text ->
            if (text != latestValue) latestChange(text)
        }
    }
    BasicTextField(
        state = field,
        lineLimits = TextFieldLineLimits.SingleLine,
        scrollState = scroll,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactions,
        decorator = TextFieldDecorator { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() }
        },
        modifier = modifier.height(height).kgsHorizontalTextFade(
            start = if (scroll.value > 0) 12.dp else 0.dp,
            end = if (scroll.value < scroll.maxValue) 24.dp else 0.dp,
        ),
    )
}
