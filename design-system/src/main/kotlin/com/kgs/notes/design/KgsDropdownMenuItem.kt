package com.kgs.notes.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The common visual contract for KGS dropdown rows.
 *
 * Every option owns the same leading slot. Selecting an option replaces its
 * normal glyph with the Material check, keeping both the text and icon columns
 * stable while the row gains its blue active container.
 */
@Composable
fun KgsDropdownMenuItem(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val hapticClick = rememberKgsHapticClick(onClick)
    DropdownMenuItem(
        text = { Text(text, color = contentColor) },
        leadingIcon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("dropdown-leading-$text")
                    .then(
                        if (selected) {
                            Modifier.semantics {
                                contentDescription = "Selected: $text"
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        icon()
                    }
                }
            }
        },
        onClick = hapticClick,
        modifier = modifier
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(13.dp),
            )
            .testTag("dropdown-item-$text"),
    )
}
