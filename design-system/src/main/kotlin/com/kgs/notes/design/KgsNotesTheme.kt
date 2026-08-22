package com.kgs.notes.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563A8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF12375F),
    secondary = Color(0xFF2B7A86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F1F3),
    onSecondaryContainer = Color(0xFF123B41),
    tertiary = Color(0xFF7567B4),
    background = Color(0xFFF4F8FD),
    onBackground = Color(0xFF17202A),
    surface = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8EEF5),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F7FB),
    surfaceContainer = Color(0xFFEEF3F8),
    surfaceContainerHigh = Color(0xFFE8EEF5),
    surfaceContainerHighest = Color(0xFFE1E9F2),
    onSurface = Color(0xFF17202A),
    surfaceVariant = Color(0xFFEDF3FA),
    onSurfaceVariant = Color(0xFF526173),
    outline = Color(0xFFD5E0EC),
    outlineVariant = Color(0xFFE4EBF3),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BCAFF),
    onPrimary = Color(0xFF003257),
    primaryContainer = Color(0xFF1F4A75),
    onPrimaryContainer = Color(0xFFD8E9FF),
    secondary = Color(0xFF8ED7DE),
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF164B52),
    onSecondaryContainer = Color(0xFFD1F5F7),
    tertiary = Color(0xFFC9C0FF),
    background = Color(0xFF101923),
    onBackground = Color(0xFFE6EEF7),
    surface = Color(0xFF172230),
    surfaceDim = Color(0xFF101923),
    surfaceBright = Color(0xFF273646),
    surfaceContainerLowest = Color(0xFF0C151E),
    surfaceContainerLow = Color(0xFF14202B),
    surfaceContainer = Color(0xFF192633),
    surfaceContainerHigh = Color(0xFF1F2D3B),
    surfaceContainerHighest = Color(0xFF263645),
    onSurface = Color(0xFFE6EEF7),
    surfaceVariant = Color(0xFF223243),
    onSurfaceVariant = Color(0xFFCEDAE8),
    outline = Color(0xFF41566B),
    outlineVariant = Color(0xFF293C4E),
    error = Color(0xFFFFB4AB),
)

data class KgsMotion(
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f),
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val shortMillis: Int = 150,
    val mediumMillis: Int = 300,
    val longMillis: Int = 450,
)

val LocalKgsMotion = staticCompositionLocalOf { KgsMotion() }

private val KgsTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.35).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val KgsShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun KgsNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalKgsMotion provides KgsMotion()) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = KgsTypography,
            shapes = KgsShapes,
            content = content,
        )
    }
}
