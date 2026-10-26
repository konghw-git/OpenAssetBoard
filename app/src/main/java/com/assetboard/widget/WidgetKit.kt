package com.assetboard.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.assetboard.AppContainer
import com.assetboard.AssetBoardApp
import com.assetboard.MainActivity
import com.assetboard.R
import com.assetboard.WidgetSheetActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal val Context.container: AppContainer get() = (applicationContext as AssetBoardApp).container

/**
 * The app's palette for the home screen, light and dark by the system's night mode (a launcher
 * draws in the system's theme, not the app's). Colours mirror ui/theme/Theme.kt.
 */
internal object WColors {
    val ink = ColorProvider(day = Color(0xFF152824), night = Color(0xFFE7EEE9))
    val muted = ColorProvider(day = Color(0xFF586A63), night = Color(0xFFA6B8AE))
    val faint = ColorProvider(day = Color(0xFF7D9087), night = Color(0xFF7D8C85))
    val primary = ColorProvider(day = Color(0xFF1E6353), night = Color(0xFF8FD8C2))
    val primarySoft = ColorProvider(day = Color(0x261E6353), night = Color(0x338FD8C2))
    val brass = ColorProvider(day = Color(0xFF8A6A33), night = Color(0xFFE0C48E))
    val alert = ColorProvider(day = Color(0xFFD23F31), night = Color(0xFFFF8A80))
    val warn = ColorProvider(day = Color(0xFFB0782A), night = Color(0xFFEBC06A))
    val flame = ColorProvider(day = Color(0xFFE8572A), night = Color(0xFFFFB27A))
    val white = ColorProvider(Color.White)
    val whiteMuted = ColorProvider(Color(0xD9FFFFFF))
    /** The warm glass of the net worth card. */
    val auroraInk = ColorProvider(day = Color(0xFF2E1A14), night = Color(0xFFFFF4EC))
    val auroraMuted = ColorProvider(day = Color(0xFF7D5A4C), night = Color(0xFFE6C3B1))
    val auroraFigure = ColorProvider(day = Color(0xFF5A2312), night = Color(0xFFFFE3CF))
    val auroraTrack = ColorProvider(day = Color(0x1F8E3016), night = Color(0x24FFE3CF))
    val gainRed = ColorProvider(day = Color(0xFFD23F31), night = Color(0xFFFF8A80))
    val gainGreen = ColorProvider(day = Color(0xFF23864D), night = Color(0xFF6FD39A))
    /** The categorical palette for groups, as in the app. */
    val groups = listOf(
        ColorProvider(Color(0xFF2E7D67), Color(0xFF6CC9B0)), ColorProvider(Color(0xFF5478AE), Color(0xFF8FACE6)),
        ColorProvider(Color(0xFFB8893A), Color(0xFFEBC06A)), ColorProvider(Color(0xFFBE6652), Color(0xFFE8907E)),
        ColorProvider(Color(0xFF8470B3), Color(0xFFB7A0E2)), ColorProvider(Color(0xFF3E8E9E), Color(0xFF74CBDA)),
        ColorProvider(Color(0xFFAE6485), Color(0xFFE09FB9)), ColorProvider(Color(0xFF788A4A), Color(0xFFB2BE80)),
    )

    fun group(index: Int) = groups[((index % groups.size) + groups.size) % groups.size]
}

/** Text in one call: the widgets use a handful of sizes and weights, nothing else. */
@Composable
internal fun WText(
    text: String,
    size: TextUnit,
    color: ColorProvider,
    modifier: GlanceModifier = GlanceModifier,
    bold: Boolean = false,
    medium: Boolean = false,
    maxLines: Int = 1,
    strike: Boolean = false,
    align: TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = when {
                bold -> FontWeight.Bold
                medium -> FontWeight.Medium
                else -> FontWeight.Normal
            },
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            textAlign = align,
        ),
    )
}

/**
 * The widget's body: glass drawn as a resource (so it has the launcher's corner on every
 * version, and its rim and colour fields), clipped to the launcher's radius where it can be.
 */
@Composable
internal fun WidgetFrame(
    background: Int = R.drawable.widget_glass,
    padding: Dp = 14.dp,
    onClick: Action? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(R.dimen.widget_radius)
        .background(ImageProvider(background))
    if (onClick != null) m = m.clickable(onClick)
    Column(m.padding(padding), content = content)
}

/** A round button with a gradient body and a white icon: the + of each widget. */
@Composable
internal fun RoundButton(icon: Int, description: String, body: Int, onClick: Action, size: Dp = 32.dp, iconSize: Dp = 18.dp) {
    Box(
        GlanceModifier.size(size).background(ImageProvider(body)).cornerRadius(size / 2).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(icon), description, GlanceModifier.size(iconSize), colorFilter = ColorFilter.tint(WColors.white))
    }
}

/** An icon tinted to [color]. */
@Composable
internal fun WIcon(icon: Int, color: ColorProvider, size: Dp, modifier: GlanceModifier = GlanceModifier, description: String? = null) {
    Image(ImageProvider(icon), description, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/** A small pill of text on a tinted chip. */
@Composable
internal fun Chip(text: String, color: ColorProvider, chip: Int, size: TextUnit = 11.sp, bold: Boolean = true) {
    Box(GlanceModifier.background(ImageProvider(chip)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        WText(text, size, color, bold = bold)
    }
}

/** A friendly empty state: a line and a hint, centred. */
@Composable
internal fun EmptyState(title: String, hint: String, modifier: GlanceModifier = GlanceModifier, onClick: Action? = null) {
    Column(
        (if (onClick != null) modifier.clickable(onClick) else modifier).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WText(title, 14.sp, WColors.ink, medium = true, align = TextAlign.Center)
        WText(hint, 12.sp, WColors.faint, align = TextAlign.Center, maxLines = 2)
    }
}

// ------------------------------------------------------------ opening things

/** The sheet over the home screen, for [mode] on [id]. Each target gets its own URI, so the system never merges two of them. */
internal fun sheetAction(context: Context, mode: String, id: String? = null): Action =
    actionStartActivity(WidgetSheetActivity.intent(context, mode, id))

/** The app itself, on [open] (one of MainActivity.OPEN_*). */
internal fun appAction(context: Context, open: String? = null, id: String? = null): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setData("assetboard://widget/app/${open ?: "home"}/${id.orEmpty()}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply {
                if (open != null) putExtra(MainActivity.EXTRA_OPEN, open)
                if (id != null) putExtra(MainActivity.EXTRA_ID, id)
            },
    )

// ------------------------------------------------------------ redrawing

/**
 * Redraws, coalesced: a note autosaving while it is typed writes every second, and each of
 * those would otherwise push a new picture to the launcher. Ticks of the same widget within
 * [WINDOW_MS] become one.
 */
object WidgetRefresh {
    private const val WINDOW_MS = 350L
    private val jobs = ConcurrentHashMap<String, Job>()

    fun request(context: Context, scope: CoroutineScope, widget: () -> GlanceAppWidget) {
        val key = widget().javaClass.name
        val app = context.applicationContext
        jobs.put(key, scope.launch {
            delay(WINDOW_MS)
            runCatching { widget().updateAll(app) }
        })?.cancel()
    }

    /** Every widget at once: a restore, the day turning, the eye. */
    suspend fun all(context: Context) {
        for (w in listOf(TodoWidget(), NoteWidget(), CountdownWidget(), NetWorthWidget())) runCatching { w.updateAll(context) }
    }
}

/**
 * Rows ticked or unticked on the home screen whose write has not landed yet, with the state they
 * are drawn in meanwhile: the circle answers the tap before the database does. In memory only; a
 * process that dies with a write in flight redraws from the database.
 */
object PendingTicks {
    private val _shown = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val shown: StateFlow<Map<String, Boolean>> = _shown.asStateFlow()

    fun put(id: String, done: Boolean) = _shown.update { it + (id to done) }
    fun remove(id: String) = _shown.update { it - id }
}
