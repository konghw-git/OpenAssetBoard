package com.assetboard.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/** A handful of per-device preferences; none of them is ledger data, so none is backed up. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Chinese market convention by default: gains red, losses green. */
    private val _upIsRed = MutableStateFlow(prefs.getBoolean(KEY_UP_RED, true))
    val upIsRed: StateFlow<Boolean> = _upIsRed.asStateFlow()

    private val _backupTreeUri = MutableStateFlow(prefs.getString(KEY_BACKUP_URI, null))
    val backupTreeUri: StateFlow<String?> = _backupTreeUri.asStateFlow()

    private val _lastBackupAt = MutableStateFlow(prefs.getLong(KEY_LAST_BACKUP, 0L).takeIf { it > 0 })
    val lastBackupAt: StateFlow<Long?> = _lastBackupAt.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    fun setUpIsRed(value: Boolean) {
        prefs.edit { putBoolean(KEY_UP_RED, value) }
        _upIsRed.value = value
    }

    fun setBackupTreeUri(uri: String?) {
        prefs.edit { putString(KEY_BACKUP_URI, uri) }
        _backupTreeUri.value = uri
    }

    fun setLastBackupAt(at: Long) {
        prefs.edit { putLong(KEY_LAST_BACKUP, at) }
        _lastBackupAt.value = at
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_UP_RED = "up_is_red"
        const val KEY_BACKUP_URI = "backup_tree_uri"
        const val KEY_LAST_BACKUP = "last_backup_at"
    }
}
