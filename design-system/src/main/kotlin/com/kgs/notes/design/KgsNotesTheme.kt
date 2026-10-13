package com.kgs.notes.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
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
    // Shared with KGS Calendar so both apps sit on the same cool blue canvas.
    background = Color(0xFFEDF6FF),
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

enum class NotesTheme(val label: String) {
    KGS_NOTES("KGS Notes"),
    BLUE("KGS Blue"),
}

/** Bright brand accents are separate from the muted toolbar and selection palette. */
data class KgsAccentColors(
    val focus: Color,
    val container: Color,
    val onContainer: Color,
    val fabContainer: Color = container,
    val fabContent: Color = Color.White,
)

private val YellowAccents = KgsAccentColors(
    focus = Color(0xFFFFC217),
    container = Color(0xFFFFC217),
    onContainer = Color(0xFF302300),
)

val LocalKgsAccentColors = staticCompositionLocalOf { YellowAccents }
val LocalKgsDarkTheme = staticCompositionLocalOf { false }

// Yellow marks the primary action and selection; supporting surfaces stay neutral.
private val YellowLightColors = LightColors.copy(
    primary = Color(0xFF946C00), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE08A), onPrimaryContainer = Color(0xFF302300),
    secondary = Color(0xFF2B7A86), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF0F1), onSecondaryContainer = Color(0xFF123B41),
    tertiary = Color(0xFF7567B4), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAE4F8), onTertiaryContainer = Color(0xFF342C4D),
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF20252B),
    surface = Color.White, onSurface = Color(0xFF20252B),
    surfaceVariant = Color(0xFFEFF1F4), onSurfaceVariant = Color(0xFF59636F),
    outline = Color(0xFFD6DBE1), outlineVariant = Color(0xFFE3E7EC),
    inverseSurface = Color(0xFF30343B), inverseOnSurface = Color(0xFFF5F6F8), inversePrimary = Color(0xFFFFD46A),
    surfaceTint = Color(0xFF946C00),
    surfaceDim = Color(0xFFE3E6EB), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF2F4F6), surfaceContainerHigh = Color(0xFFEBEEF1),
    surfaceContainerHighest = Color(0xFFE4E8EC),
)

private val YellowDarkColors = DarkColors.copy(
    primary = Color(0xFFF2C544), onPrimary = Color(0xFF3B2C00),
    primaryContainer = Color(0xFF554100), onPrimaryContainer = Color(0xFFFFE39A),
    secondary = Color(0xFF8ED7DE), onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF164B52), onSecondaryContainer = Color(0xFFD1F5F7),
    tertiary = Color(0xFFC9C0FF), onTertiary = Color(0xFF312A48),
    tertiaryContainer = Color(0xFF4E4569), onTertiaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF15181D), onBackground = Color(0xFFE8EAEF),
    surface = Color(0xFF1E2228), onSurface = Color(0xFFE8EAEF),
    surfaceVariant = Color(0xFF30353D), onSurfaceVariant = Color(0xFFC4CAD2),
    outline = Color(0xFF59616D), outlineVariant = Color(0xFF3C434E),
    inverseSurface = Color(0xFFE8EAEF), inverseOnSurface = Color(0xFF30343B), inversePrimary = Color(0xFF946C00),
    surfaceTint = Color(0xFFF2C544),
    surfaceDim = Color(0xFF15181D), surfaceBright = Color(0xFF343A44),
    surfaceContainerLowest = Color(0xFF101318), surfaceContainerLow = Color(0xFF1A1E24),
    surfaceContainer = Color(0xFF232830), surfaceContainerHigh = Color(0xFF2B3038),
    surfaceContainerHighest = Color(0xFF333943),
)

fun notesColorScheme(theme: NotesTheme, darkTheme: Boolean): ColorScheme = when (theme) {
    NotesTheme.KGS_NOTES -> if (darkTheme) YellowDarkColors else YellowLightColors
    NotesTheme.BLUE -> if (darkTheme) DarkColors else LightColors
}

@Composable
private fun animateScheme(target: ColorScheme): ColorScheme {
    @Composable fun color(value: Color) = animateColorAsState(value,
        LocalKgsMotion.current.defaultEffectsSpec(), label = "Theme color").value
    return target.copy(
        primary = color(target.primary),
        onPrimary = color(target.onPrimary),
        primaryContainer = color(target.primaryContainer),
        onPrimaryContainer = color(target.onPrimaryContainer),
        inversePrimary = color(target.inversePrimary),
        secondary = color(target.secondary),
        onSecondary = color(target.onSecondary),
        secondaryContainer = color(target.secondaryContainer),
        onSecondaryContainer = color(target.onSecondaryContainer),
        tertiary = color(target.tertiary),
        onTertiary = color(target.onTertiary),
        tertiaryContainer = color(target.tertiaryContainer),
        onTertiaryContainer = color(target.onTertiaryContainer),
        background = color(target.background),
        onBackground = color(target.onBackground),
        surface = color(target.surface),
        onSurface = color(target.onSurface),
        surfaceVariant = color(target.surfaceVariant),
        onSurfaceVariant = color(target.onSurfaceVariant),
        surfaceTint = color(target.surfaceTint),
        inverseSurface = color(target.inverseSurface),
        inverseOnSurface = color(target.inverseOnSurface),
        outline = color(target.outline),
        outlineVariant = color(target.outlineVariant),
        error = color(target.error),
        onError = color(target.onError),
        errorContainer = color(target.errorContainer),
        onErrorContainer = color(target.onErrorContainer),
        scrim = color(target.scrim),
        surfaceDim = color(target.surfaceDim),
        surfaceBright = color(target.surfaceBright),
        surfaceContainerLowest = color(target.surfaceContainerLowest),
        surfaceContainerLow = color(target.surfaceContainerLow),
        surfaceContainer = color(target.surfaceContainer),
        surfaceContainerHigh = color(target.surfaceContainerHigh),
        surfaceContainerHighest = color(target.surfaceContainerHighest),
    )
}

data class KgsMotion(
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f),
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val shortMillis: Int = 150,
    val mediumMillis: Int = 300,
    val longMillis: Int = 450,
    val fastSpatialDamping: Float = 0.6f,
    val fastSpatialStiffness: Float = 800f,
    val defaultSpatialDamping: Float = 0.8f,
    val defaultSpatialStiffness: Float = 380f,
    val slowSpatialDamping: Float = 0.8f,
    val slowSpatialStiffness: Float = 200f,
    val fastEffectsDamping: Float = 1f,
    val fastEffectsStiffness: Float = 3_800f,
    val defaultEffectsDamping: Float = 1f,
    val defaultEffectsStiffness: Float = 1_600f,
    val slowEffectsDamping: Float = 1f,
    val slowEffectsStiffness: Float = 800f,
)

fun <T> KgsMotion.fastSpatialSpec(visibilityThreshold: T? = null): SpringSpec<T> = spring(
    dampingRatio = fastSpatialDamping,
    stiffness = fastSpatialStiffness,
    visibilityThreshold = visibilityThreshold,
)

fun <T> KgsMotion.defaultSpatialSpec(visibilityThreshold: T? = null): SpringSpec<T> = spring(
    dampingRatio = defaultSpatialDamping,
    stiffness = defaultSpatialStiffness,
    visibilityThreshold = visibilityThreshold,
)

fun <T> KgsMotion.slowSpatialSpec(visibilityThreshold: T? = null): SpringSpec<T> = spring(
    dampingRatio = slowSpatialDamping,
    stiffness = slowSpatialStiffness,
    visibilityThreshold = visibilityThreshold,
)

fun <T> KgsMotion.fastEffectsSpec(visibilityThreshold: T? = null): SpringSpec<T> = spring(
    dampingRatio = fastEffectsDamping,
    stiffness = fastEffectsStiffness,
    visibilityThreshold = visibilityThreshold,
)

fun <T> KgsMotion.defaultEffectsSpec(visibilityThreshold: T? = null): SpringSpec<T> = spring(
    dampingRatio = defaultEffectsDamping,
    stiffness = defaultEffectsStiffness,
    visibilityThreshold = visibilityThreshold,
)

fun <T> KgsMotion.slowEffectsSpec(visibilityThreshold: T? = null): SpringSpec<T> = spring(
    dampingRatio = slowEffectsDamping,
    stiffness = slowEffectsStiffness,
    visibilityThreshold = visibilityThreshold,
)

val LocalKgsMotion = staticCompositionLocalOf { KgsMotion() }

private val KgsTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.35).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 23.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
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
    theme: NotesTheme = NotesTheme.KGS_NOTES,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalKgsMotion provides KgsMotion()) {
        val targetColors = notesColorScheme(theme, darkTheme)
        val colors = animateScheme(targetColors)
        val targetAccents = if (theme == NotesTheme.KGS_NOTES) YellowAccents.copy(
            fabContent = if (darkTheme) YellowAccents.onContainer else Color.White,
        ) else KgsAccentColors(
            focus = targetColors.primary,
            container = targetColors.primaryContainer,
            onContainer = targetColors.onPrimaryContainer,
            fabContainer = if (darkTheme) targetColors.primary else targetColors.primaryContainer,
            fabContent = if (darkTheme) targetColors.onPrimary else Color.White,
        )
        @Composable fun accent(value: Color) = animateColorAsState(
            value, LocalKgsMotion.current.defaultEffectsSpec(), label = "Brand accent",
        ).value
        val accents = KgsAccentColors(
            focus = accent(targetAccents.focus),
            container = accent(targetAccents.container),
            onContainer = accent(targetAccents.onContainer),
            fabContainer = accent(targetAccents.fabContainer),
            fabContent = accent(targetAccents.fabContent),
        )
        CompositionLocalProvider(
            LocalKgsAccentColors provides accents,
            LocalKgsDarkTheme provides darkTheme,
            LocalContentColor provides colors.onSurface,
        ) {
            MaterialTheme(
                colorScheme = colors,
                typography = KgsTypography,
                shapes = KgsShapes,
                content = { KgsSheetHost(content) },
            )
        }
    }
}
