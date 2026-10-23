package com.assetboard.ui.theme

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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.data.ThemeMode

// Mineral neutrals, ink green and restrained brass accents. Color carries meaning, not noise.

private val LightColors = lightColorScheme(
    primary = Color(0xFF246456),
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
    background = Color(0xFFF2F5F3),
    onBackground = Color(0xFF192D29),
    surface = Color(0xFFF2F5F3),
    onSurface = Color(0xFF192D29),
    surfaceVariant = Color(0xFFE4EBE7),
    onSurfaceVariant = Color(0xFF5C6D66),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAF8),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEBF0ED),
    surfaceContainerHighest = Color(0xFFDFE7E2),
    outline = Color(0xFF82948B),
    outlineVariant = Color(0xFFDCE5DF),
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
    background = Color(0xFF101917),
    onBackground = Color(0xFFE6EDE8),
    surface = Color(0xFF101917),
    onSurface = Color(0xFFE6EDE8),
    surfaceVariant = Color(0xFF2A2E2C),
    onSurfaceVariant = Color(0xFFA5B6AC),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF15201C),
    surfaceContainer = Color(0xFF1B2823),
    surfaceContainerHigh = Color(0xFF24332C),
    surfaceContainerHighest = Color(0xFF2D3E35),
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
    val onHeroAccent: Color,
    val groupPalette: List<Color>,
    val chartGrid: Color,
) {
    fun group(index: Int): Color = groupPalette[((index % groupPalette.size) + groupPalette.size) % groupPalette.size]
    fun signed(cents: Long): Color = if (cents >= 0) gain else loss
}

private val LightPalette = listOf(
    Color(0xFF347A66), Color(0xFF587BA3), Color(0xFFAD8543), Color(0xFFB76B59),
    Color(0xFF8474A7), Color(0xFF458895), Color(0xFFA56882), Color(0xFF7B8751),
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
        heroStart = if (dark) Color(0xFF203E34) else Color(0xFF163C32),
        heroEnd = if (dark) Color(0xFF152D25) else Color(0xFF2C6452),
        onHero = Color(0xFFF4F8F3),
        onHeroMuted = Color(0xFFD2E3D9),
        onHeroAccent = Color(0xFFE9CE91),
        groupPalette = if (dark) DarkPalette else LightPalette,
        chartGrid = if (dark) Color(0xFF2D3E35) else Color(0xFFDCE5DF),
    )
}

private val LocalExtraColors = staticCompositionLocalOf { extraColors(dark = false, upIsRed = true) }

/** Tabular figures keep columns of amounts aligned. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-1).sp, fontFeatureSettings = "tnum"),
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
    val colors = if (dark) DarkColors else LightColors
    // Transparent scaffolds and custom cards inherit this instead of Compose's default black.
    CompositionLocalProvider(LocalExtraColors provides extraColors(dark, upIsRed), LocalContentColor provides colors.onBackground) {
        MaterialTheme(
            colorScheme = colors,
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
