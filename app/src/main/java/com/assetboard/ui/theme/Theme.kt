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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.data.ThemeMode

// Deep jewel green on cool mineral paper, brass for warmth, a sea-blue tertiary. The page itself
// carries colour (see Ambient); the cards over it are glass, so the neutrals stay quiet and let
// the fields behind them show.

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6353),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEAE0),
    onPrimaryContainer = Color(0xFF07302A),
    secondary = Color(0xFF8A6A33),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E6CC),
    onSecondaryContainer = Color(0xFF2E2108),
    tertiary = Color(0xFF4A6A98),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE6F7),
    onTertiaryContainer = Color(0xFF14243D),
    background = Color(0xFFEEF3F0),
    onBackground = Color(0xFF152824),
    surface = Color(0xFFEEF3F0),
    onSurface = Color(0xFF152824),
    surfaceVariant = Color(0xFFE2EAE5),
    onSurfaceVariant = Color(0xFF586A63),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FAF8),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8EFEB),
    surfaceContainerHighest = Color(0xFFDDE6E0),
    outline = Color(0xFF7D9087),
    outlineVariant = Color(0xFFD7E1DB),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD8C2),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF1E4D42),
    onPrimaryContainer = Color(0xFFC8F0E3),
    secondary = Color(0xFFE0C48E),
    onSecondary = Color(0xFF3F2E09),
    secondaryContainer = Color(0xFF574420),
    onSecondaryContainer = Color(0xFFF8E3BC),
    tertiary = Color(0xFFB2C9EF),
    onTertiary = Color(0xFF173055),
    tertiaryContainer = Color(0xFF2F466B),
    onTertiaryContainer = Color(0xFFD8E4FA),
    background = Color(0xFF0B1412),
    onBackground = Color(0xFFE7EEE9),
    surface = Color(0xFF0B1412),
    onSurface = Color(0xFFE7EEE9),
    surfaceVariant = Color(0xFF26302C),
    onSurfaceVariant = Color(0xFFA6B8AE),
    surfaceContainerLowest = Color(0xFF070D0B),
    surfaceContainerLow = Color(0xFF111C18),
    surfaceContainer = Color(0xFF17241F),
    surfaceContainerHigh = Color(0xFF1F2F29),
    surfaceContainerHighest = Color(0xFF283B33),
    outline = Color(0xFF7D8C85),
    outlineVariant = Color(0xFF2C3833),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * Colors Material does not model: gain/loss, the hero card (a three-stop gradient with a cool
 * light and a brass accent) and the categorical palette for groups.
 */
@Immutable
data class ExtraColors(
    val gain: Color,
    val loss: Color,
    val heroStart: Color,
    val heroMid: Color,
    val heroEnd: Color,
    /** The cool light that enters the hero card from one corner. */
    val heroGlow: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val onHeroAccent: Color,
    val groupPalette: List<Color>,
    val chartGrid: Color,
    /** The net worth card: clear glass lit from within by drifting fields of these warm colours. */
    val aurora: Aurora,
) {
    fun group(index: Int): Color = groupPalette[((index % groupPalette.size) + groupPalette.size) % groupPalette.size]
    fun signed(cents: Long): Color = if (cents >= 0) gain else loss
}

private val LightPalette = listOf(
    Color(0xFF2E7D67), Color(0xFF5478AE), Color(0xFFB8893A), Color(0xFFBE6652),
    Color(0xFF8470B3), Color(0xFF3E8E9E), Color(0xFFAE6485), Color(0xFF788A4A),
)
private val DarkPalette = listOf(
    Color(0xFF6CC9B0), Color(0xFF8FACE6), Color(0xFFEBC06A), Color(0xFFE8907E),
    Color(0xFFB7A0E2), Color(0xFF74CBDA), Color(0xFFE09FB9), Color(0xFFB2BE80),
)

/**
 * The warm glass of the net worth card: a translucent [wash] over the page, [fields] of amber,
 * coral, persimmon and rose drifting under it, ink dark enough to read on all of them, and a
 * [flame] gradient for the figure's currency sign and the card's one button.
 */
@Immutable
data class Aurora(
    val wash: List<Color>,
    val fields: List<Color>,
    val fieldAlpha: Float,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
    val flame: List<Color>,
    val onFlame: Color,
    /** The big figure's ink, deepening toward the warm end. */
    val figure: List<Color>,
    /** The small panes on the card and their lit edge. */
    val pane: Color,
    val paneRim: Color,
    val rim: Color,
    val shadow: Color,
)

private val LightAurora = Aurora(
    wash = listOf(Color(0xB3FFF6EF), Color(0x99FFEDE4), Color(0xA6FFF1EA)),
    fields = listOf(Color(0xFFFF9A3C), Color(0xFFFF5E4D), Color(0xFFFFC15A), Color(0xFFF2557A)),
    fieldAlpha = 0.42f,
    ink = Color(0xFF2E1A14),
    inkMuted = Color(0xFF7D5A4C),
    accent = Color(0xFFE8572A),
    flame = listOf(Color(0xFFFF8A3D), Color(0xFFF2453D)),
    onFlame = Color.White,
    figure = listOf(Color(0xFF2E1A14), Color(0xFF8E3016)),
    pane = Color(0x7AFFFFFF),
    paneRim = Color(0xE6FFFFFF),
    rim = Color(0xFFFFFFFF),
    shadow = Color(0x47E2603A),
)

private val DarkAurora = Aurora(
    wash = listOf(Color(0x8C2A1712), Color(0x80231014), Color(0x8C2B1A10)),
    fields = listOf(Color(0xFFFF8A3D), Color(0xFFE5483E), Color(0xFFFFB347), Color(0xFFD94A78)),
    fieldAlpha = 0.34f,
    ink = Color(0xFFFFF4EC),
    inkMuted = Color(0xFFE6C3B1),
    accent = Color(0xFFFFB27A),
    flame = listOf(Color(0xFFFF9A4D), Color(0xFFF0503F)),
    onFlame = Color.White,
    figure = listOf(Color(0xFFFFF6EF), Color(0xFFFFC7A0)),
    pane = Color(0x17FFFFFF),
    paneRim = Color(0x40FFE2CF),
    rim = Color(0x80FFD9C2),
    shadow = Color(0x73000000),
)

private fun extraColors(dark: Boolean, upIsRed: Boolean): ExtraColors {
    val red = if (dark) Color(0xFFFF8A80) else Color(0xFFD23F31)
    val green = if (dark) Color(0xFF6FD39A) else Color(0xFF23864D)
    return ExtraColors(
        gain = if (upIsRed) red else green,
        loss = if (upIsRed) green else red,
        heroStart = if (dark) Color(0xFF17352D) else Color(0xFF10372E),
        heroMid = if (dark) Color(0xFF123027) else Color(0xFF1B5547),
        heroEnd = if (dark) Color(0xFF0C1F1A) else Color(0xFF2A7560),
        heroGlow = if (dark) Color(0xFF3E8F7A) else Color(0xFF5FC2A4),
        onHero = Color(0xFFF5FAF6),
        onHeroMuted = Color(0xFFCFE2D8),
        onHeroAccent = Color(0xFFEBD197),
        groupPalette = if (dark) DarkPalette else LightPalette,
        chartGrid = if (dark) Color(0xFF283B33) else Color(0xFFD7E1DB),
        aurora = if (dark) DarkAurora else LightAurora,
    )
}

private val LocalExtraColors = staticCompositionLocalOf { extraColors(dark = false, upIsRed = true) }

/** Tabular figures keep columns of amounts aligned. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

/**
 * Every line's glyphs centred in its line height, the space above and below kept. The default
 * splits the extra space by the font's ascent and descent, so with a system font whose metrics
 * differ from the emulator's Chinese text can sit off the middle of its row.
 */
private val CenteredLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun Typography.centered(): Typography = copy(
    displayLarge = displayLarge.copy(lineHeightStyle = CenteredLines),
    displayMedium = displayMedium.copy(lineHeightStyle = CenteredLines),
    displaySmall = displaySmall.copy(lineHeightStyle = CenteredLines),
    headlineLarge = headlineLarge.copy(lineHeightStyle = CenteredLines),
    headlineMedium = headlineMedium.copy(lineHeightStyle = CenteredLines),
    headlineSmall = headlineSmall.copy(lineHeightStyle = CenteredLines),
    titleLarge = titleLarge.copy(lineHeightStyle = CenteredLines),
    titleMedium = titleMedium.copy(lineHeightStyle = CenteredLines),
    titleSmall = titleSmall.copy(lineHeightStyle = CenteredLines),
    bodyLarge = bodyLarge.copy(lineHeightStyle = CenteredLines),
    bodyMedium = bodyMedium.copy(lineHeightStyle = CenteredLines),
    bodySmall = bodySmall.copy(lineHeightStyle = CenteredLines),
    labelLarge = labelLarge.copy(lineHeightStyle = CenteredLines),
    labelMedium = labelMedium.copy(lineHeightStyle = CenteredLines),
    labelSmall = labelSmall.copy(lineHeightStyle = CenteredLines),
)

// Headlines sit tight and heavy, labels firm; body stays airy. Big figures are tabular throughout.
private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, fontFeatureSettings = "tnum"),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum"),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp, fontFeatureSettings = "tnum"),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    ).centered()
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
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

/**
 * Content laid on the warm glass card ([Aurora]): the hero's ink colours become the card's, so
 * the shared hero pieces inside read dark on light glass and light on dark.
 */
@Composable
fun OnAurora(content: @Composable () -> Unit) {
    val extra = LocalExtraColors.current
    val a = extra.aurora
    val on = remember(extra) { extra.copy(onHero = a.ink, onHeroMuted = a.inkMuted, onHeroAccent = a.accent, heroStart = a.flame.last()) }
    CompositionLocalProvider(LocalExtraColors provides on, LocalContentColor provides a.ink, content = content)
}
