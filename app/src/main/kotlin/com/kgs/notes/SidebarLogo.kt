package com.kgs.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kgs.notes.design.LocalKgsDarkTheme
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.design.defaultEffectsSpec

@Composable
internal fun SidebarLogo() {
    val lettering = animateColorAsState(
        if (LocalKgsDarkTheme.current) MaterialTheme.colorScheme.onSurface else Color.Black,
        LocalKgsMotion.current.defaultEffectsSpec(), label = "Sidebar logo lettering",
    ).value
    // Recolor black lettering while retaining the supplied yellow artwork and alpha.
    val r = lettering.red
    val g = lettering.green
    val b = lettering.blue
    val colors = ColorMatrix(floatArrayOf(
        1f - r, 0f, 0f, 0f, 255f * r,
        -g, 1f, 0f, 0f, 255f * g,
        -b, 0f, 1f, 0f, 255f * b,
        0f, 0f, 0f, 1f, 0f,
    ))
    Box(Modifier.fillMaxWidth().testTag("sidebar-branding"), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.kgs_logo_text),
            contentDescription = "KGS Notes logo",
            modifier = Modifier.width(72.dp),
            colorFilter = ColorFilter.colorMatrix(colors),
        )
    }
}
