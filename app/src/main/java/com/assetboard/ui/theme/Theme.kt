package com.assetboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.data.ThemeMode

// Warm paper neutrals with a deep green accent: quiet enough for a screen full of numbers.

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E5B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E9E1),
    onPrimaryContainer = Color(0xFF0A2E26),
    secondary = Color(0xFF8A6A33),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E5CB),
    onSecondaryContainer = Color(0xFF2E2108),
    tertiary = Color(0xFF4F6A93),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE5F5),
    onTertiaryContainer = Color(0xFF14243D),
    background = Color(0xFFF5F3EE),
    onBackground = Color(0xFF1D1C1A),
    surface = Color(0xFFF5F3EE),
    onSurface = Color(0xFF1D1C1A),
    surfaceVariant = Color(0xFFEAE6DE),
    onSurfaceVariant = Color(0xFF6B675F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFBF8),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0EDE6),
    surfaceContainerHighest = Color(0xFFE9E5DD),
    outline = Color(0xFFCFCAC0),
    outlineVariant = Color(0xFFE6E2D9),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD3BF),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF1F4D43),
    onPrimaryContainer = Color(0xFFC6EEE1),
    secondary = Color(0xFFDDC08A),
    onSecondary = Color(0xFF3F2E09),
    secondaryContainer = Color(0xFF574420),
    onSecondaryContainer = Color(0xFFF8E3BC),
    tertiary = Color(0xFFAFC6EC),
    onTertiary = Color(0xFF173055),
    tertiaryContainer = Color(0xFF2F466B),
    onTertiaryContainer = Color(0xFFD8E4FA),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE5E2DB),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE5E2DB),
    surfaceVariant = Color(0xFF2A2E2C),
    onSurfaceVariant = Color(0xFFA9A49B),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF171A19),
    surfaceContainer = Color(0xFF1B1F1D),
    surfaceContainerHigh = Color(0xFF242826),
    surfaceContainerHighest = Color(0xFF2E3230),
    outline = Color(0xFF454B48),
    outlineVariant = Color(0xFF323735),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Colors Material does not model: gain/loss, the hero card and the categorical palette for groups. */
@Immutable
data class ExtraColors(
    val gain: Color,
    val loss: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val groupPalette: List<Color>,
    val chartGrid: Color,
) {
    fun group(index: Int): Color = groupPalette[((index % groupPalette.size) + groupPalette.size) % groupPalette.size]
    fun signed(cents: Long): Color = if (cents >= 0) gain else loss
}

private val LightPalette = listOf(
    Color(0xFF2F7D6B), Color(0xFF5477B8), Color(0xFFD39B3A), Color(0xFFC4604F),
    Color(0xFF8467B5), Color(0xFF3E9FB0), Color(0xFFB86E8A), Color(0xFF7A8450),
)
private val DarkPalette = listOf(
    Color(0xFF6CC3AC), Color(0xFF8EA8DE), Color(0xFFE6BA66), Color(0xFFE38D7D),
    Color(0xFFB39BDB), Color(0xFF72C4D2), Color(0xFFDB9CB4), Color(0xFFADB77F),
)

private fun extraColors(dark: Boolean, upIsRed: Boolean): ExtraColors {
    val red = if (dark) Color(0xFFFF8A80) else Color(0xFFD23F31)
    val green = if (dark) Color(0xFF6FD39A) else Color(0xFF23864D)
    return ExtraColors(
        gain = if (upIsRed) red else green,
        loss = if (upIsRed) green else red,
        heroStart = if (dark) Color(0xFF1F4D43) else Color(0xFF1B5246),
        heroEnd = if (dark) Color(0xFF15302A) else Color(0xFF2F7D6B),
        onHero = Color(0xFFF7F5EF),
        onHeroMuted = Color(0xFFF7F5EF).copy(alpha = 0.72f),
        groupPalette = if (dark) DarkPalette else LightPalette,
        chartGrid = if (dark) Color(0xFF2E3330) else Color(0xFFE6E2D9),
    )
}

private val LocalExtraColors = staticCompositionLocalOf { extraColors(dark = false, upIsRed = true) }

/** Tabular figures keep columns of amounts aligned. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AssetBoardTheme(themeMode: ThemeMode, upIsRed: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalExtraColors provides extraColors(dark, upIsRed)) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

object AppTheme {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}
