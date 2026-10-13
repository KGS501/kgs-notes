package com.kgs.notes.design

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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

/** Shared, full-width option row for app-owned bottom sheets. */
@Composable
fun KgsDropdownMenuItem(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    KgsExpressiveSurface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = contentColor,
        restingCorners = KgsCornerRadii(14.dp),
        showIndication = false,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .heightIn(min = 50.dp).testTag("dropdown-item-$text"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)
                .testTag("dropdown-leading-$text")
                .then(if (selected) Modifier.semantics { contentDescription = "Selected: $text" } else Modifier)) {
                if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                else CompositionLocalProvider(LocalContentColor provides contentColor) { icon() }
            }
            Text(text, color = contentColor, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
