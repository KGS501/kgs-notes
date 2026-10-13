package com.kgs.notes.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** Shared one-line field for search, dialog input, Source setup, and Note titles. */
@Composable
fun KgsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    height: Dp = 50.dp,
    contentPadding: Dp = 14.dp,
    trailingContentPadding: Dp = contentPadding,
    focusBorderColor: Color = LocalKgsAccentColors.current.focus,
    showFocusBorder: Boolean = true,
    fadeEnd: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val motion = LocalKgsMotion.current
    val corner by animateDpAsState(
        targetValue = if (showFocusBorder && focused) 28.dp else 12.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "Text field focus shape",
    )
    val borderColor by animateColorAsState(
        targetValue = if (showFocusBorder && focused) focusBorderColor else Color.Transparent,
        animationSpec = motion.defaultEffectsSpec(),
        label = "Text field focus border",
    )
    val shape = RoundedCornerShape(corner)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().padding(start = contentPadding, end = trailingContentPadding),
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(Modifier.width(12.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(4.dp))
                    trailingIcon()
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(containerColor, shape)
            .then(if (showFocusBorder) Modifier.border(2.dp, borderColor, shape) else Modifier)
            .then(if (fadeEnd) Modifier.kgsHorizontalTextFade(end = 30.dp) else Modifier),
    )
}

@Composable
fun KgsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    KgsTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        containerColor = containerColor,
        interactionSource = interactionSource,
        focusBorderColor = LocalKgsAccentColors.current.focus,
        trailingContentPadding = 2.dp,
        trailingIcon = if (value.isNotEmpty()) {
            {
                val clearInteractions = remember { MutableInteractionSource() }
                val pressed by clearInteractions.collectIsVisuallyPressedAsState()
                val scale by animateFloatAsState(
                    if (pressed) .86f else 1f,
                    LocalKgsMotion.current.fastSpatialSpec(), label = "Clear search press",
                )
                KgsTooltip("Clear search") {
                    KgsExpressiveSurface(
                        onClick = {
                            onValueChange("")
                            focusManager.clearFocus()
                            keyboard?.hide()
                        },
                        showIndication = false,
                        interactionSource = clearInteractions,
                        color = Color.Transparent,
                        restingCorners = KgsCornerRadii(14.dp),
                        modifier = Modifier.size(40.dp).semantics { contentDescription = "Clear search" },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            })
                    }
                }
            }
        } else null,
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
