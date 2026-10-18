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
import com.assetboard.ui.theme.AssetBoardTheme

class MainActivity : ComponentActivity() {
    private var openStocktake by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openStocktake = wantsStocktake(intent)
        val container = (application as AssetBoardApp).container
        setContent {
            val theme by container.settings.themeMode.collectAsStateWithLifecycle()
            val upIsRed by container.settings.upIsRed.collectAsStateWithLifecycle()
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
            CompositionLocalProvider(LocalContainer provides container) {
                AssetBoardTheme(theme, upIsRed) {
                    AppNav(openStocktake = openStocktake, onOpenHandled = { openStocktake = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (wantsStocktake(intent)) openStocktake = true
    }

    private fun wantsStocktake(intent: Intent?) = intent?.getStringExtra(EXTRA_OPEN) == OPEN_STOCKTAKE

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_STOCKTAKE = "stocktake"
    }
}
