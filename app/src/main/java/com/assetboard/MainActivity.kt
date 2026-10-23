package com.assetboard

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.data.ThemeMode
import com.assetboard.ui.AppNav
import com.assetboard.ui.LocalContainer
import com.assetboard.data.SettingsStore
import com.assetboard.ui.components.GlassMode
import com.assetboard.ui.components.LocalGlassMode
import com.assetboard.ui.components.LocalHideAmounts
import com.assetboard.ui.OpenRequest
import com.assetboard.ui.theme.AssetBoardTheme

class MainActivity : ComponentActivity() {
    private var open by mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) open = requestOf(intent)
        val container = (application as AssetBoardApp).container
        if (savedInstanceState == null) container.updater.checkAuto()
        setContent {
            val theme by container.settings.themeMode.collectAsStateWithLifecycle()
            val upIsRed by container.settings.upIsRed.collectAsStateWithLifecycle()
            val glass by container.settings.glass.collectAsStateWithLifecycle()
            val hideAmounts by container.settings.hideAmounts.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Keep status bar icons readable when the in-app theme differs from the system one.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CompositionLocalProvider(
                LocalContainer provides container,
                LocalGlassMode provides if (glass == SettingsStore.GLASS_DEEP) GlassMode.DEEP else GlassMode.CLEAR,
                LocalHideAmounts provides hideAmounts,
            ) {
                AssetBoardTheme(theme, upIsRed) {
                    AppNav(open = open, onOpenHandled = { open = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestOf(intent)?.let { open = it }
    }

    private fun requestOf(intent: Intent?): OpenRequest? = when (intent?.getStringExtra(EXTRA_OPEN)) {
        OPEN_STOCKTAKE -> OpenRequest.Stocktake
        OPEN_TODO -> intent.getStringExtra(EXTRA_ID)?.let { OpenRequest.Todo(it) }
        OPEN_TODO_TODAY -> OpenRequest.TodoToday
        OPEN_DIARY -> OpenRequest.Diary
        OPEN_QUICK_ADD -> OpenRequest.QuickAdd
        else -> null
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val EXTRA_ID = "id"
        const val OPEN_STOCKTAKE = "stocktake"
        const val OPEN_TODO = "todo"
        const val OPEN_TODO_TODAY = "todo_today"
        const val OPEN_DIARY = "diary"
        const val OPEN_QUICK_ADD = "quick_add"
    }
}
