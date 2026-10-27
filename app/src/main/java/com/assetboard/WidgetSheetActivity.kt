package com.assetboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.data.SettingsStore
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.GlassMode
import com.assetboard.ui.components.LocalGlassMode
import com.assetboard.ui.components.LocalHideAmounts
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.widget.SheetRequest
import com.assetboard.ui.widget.WidgetSheets

/**
 * What the widgets open over the home screen: a sheet to add or change a todo, a note or a
 * countdown without going into the app. Transparent itself, so the home screen stays behind the
 * sheet; it ends when the sheet is put away. A second tap on the widget while it is open swaps
 * what the sheet shows.
 */
class WidgetSheetActivity : ComponentActivity() {
    private var request by mutableStateOf<SheetRequest?>(null)
    /** Bumped for each new request, so the sheet for it starts fresh. */
    private var generation by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        request = requestOf(intent)
        if (request == null) {
            finish()
            return
        }
        val app = application as AssetBoardApp
        setContent {
            val session by app.sessionGeneration.collectAsStateWithLifecycle()
            val container = remember(session) { app.container }
            val theme by container.settings.themeMode.collectAsStateWithLifecycle()
            val upIsRed by container.settings.upIsRed.collectAsStateWithLifecycle()
            val glass by container.settings.glass.collectAsStateWithLifecycle()
            val hideAmounts by container.settings.hideAmounts.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalContainer provides container,
                LocalGlassMode provides if (glass == SettingsStore.GLASS_DEEP) GlassMode.DEEP else GlassMode.CLEAR,
                LocalHideAmounts provides hideAmounts,
            ) {
                AssetBoardTheme(theme, upIsRed) {
                    request?.let { r -> key(session, generation) { WidgetSheets(r, onClose = ::close) } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestOf(intent)?.let {
            request = it
            generation++
        }
    }

    private fun close() {
        finish()
        // The sheet has already slid away; no window animation on top of it.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun requestOf(intent: Intent?): SheetRequest? {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: return null
        return SheetRequest(mode, intent.getStringExtra(EXTRA_ID))
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ID = "id"
        const val TODO_ADD = "todo_add"
        const val TODO_EDIT = "todo_edit"
        const val NOTE_NEW = "note_new"
        const val NOTE_EDIT = "note_edit"
        const val COUNTDOWN_ADD = "countdown_add"
        const val COUNTDOWN_EDIT = "countdown_edit"
        const val COUNTDOWN_LIST = "countdown_list"
        const val WIDGET_GALLERY = "widget_gallery"

        /** Each target has a URI of its own, so the system never folds two pending intents into one. */
        fun intent(context: Context, mode: String, id: String? = null): Intent =
            Intent(context, WidgetSheetActivity::class.java)
                .setData("assetboard://widget/sheet/$mode/${id.orEmpty()}".toUri())
                .putExtra(EXTRA_MODE, mode)
                .apply { if (id != null) putExtra(EXTRA_ID, id) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
