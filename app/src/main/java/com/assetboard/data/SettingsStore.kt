package com.assetboard.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/** Per-device preferences and bookkeeping; none of it is user data, so none is backed up. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Chinese market convention by default: gains red, losses green. */
    /** The glass of todo and note cards: [GLASS_CLEAR] or [GLASS_DEEP]. */
    private val _glass = MutableStateFlow(prefs.getString(KEY_GLASS, null) ?: GLASS_CLEAR)
    val glass: StateFlow<String> = _glass.asStateFlow()

    fun setGlass(value: String) {
        prefs.edit { putString(KEY_GLASS, value) }
        _glass.value = value
    }

    private val _upIsRed = MutableStateFlow(prefs.getBoolean(KEY_UP_RED, true))
    val upIsRed: StateFlow<Boolean> = _upIsRed.asStateFlow()

    private val _backupTreeUri = MutableStateFlow(prefs.getString(KEY_BACKUP_URI, null))
    val backupTreeUri: StateFlow<String?> = _backupTreeUri.asStateFlow()

    private val _lastBackupAt = MutableStateFlow(prefs.getLong(KEY_LAST_BACKUP, 0L).takeIf { it > 0 })
    val lastBackupAt: StateFlow<Long?> = _lastBackupAt.asStateFlow()

    private fun bool(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    /** Master switch: when off the app makes no network request at all. */
    val networkEnabled = bool(KEY_NETWORK, true)
    val weatherEnabled = bool(KEY_WEATHER, true)
    val holidayUpdateEnabled = bool(KEY_HOLIDAY_UPDATE, true)
    /** Every reminder via setAlarmClock (status-bar alarm icon), for exact timing in deep doze. */
    val allRemindersAsAlarms = bool(KEY_ALL_ALARMS, false)
    val diaryReminderEnabled = bool(KEY_DIARY_REMINDER, true)
    /** Minutes after midnight. */
    val diaryReminderMinutes = MutableStateFlow(prefs.getInt(KEY_DIARY_REMINDER_AT, 22 * 60))
    val reminderGuideSeen = bool(KEY_GUIDE_SEEN, false)

    fun setBool(flow: MutableStateFlow<Boolean>, value: Boolean) {
        val key = when (flow) {
            networkEnabled -> KEY_NETWORK
            weatherEnabled -> KEY_WEATHER
            holidayUpdateEnabled -> KEY_HOLIDAY_UPDATE
            allRemindersAsAlarms -> KEY_ALL_ALARMS
            diaryReminderEnabled -> KEY_DIARY_REMINDER
            reminderGuideSeen -> KEY_GUIDE_SEEN
            else -> error("unknown setting")
        }
        prefs.edit { putBoolean(key, value) }
        flow.value = value
    }

    fun setDiaryReminderMinutes(minutes: Int) {
        prefs.edit { putInt(KEY_DIARY_REMINDER_AT, minutes) }
        diaryReminderMinutes.value = minutes
    }

    val weatherAllowed: Boolean get() = networkEnabled.value && weatherEnabled.value
    val holidayUpdateAllowed: Boolean get() = networkEnabled.value && holidayUpdateEnabled.value

    // ------------------------------------------------------------ device-local bookkeeping

    /** A restore committed but rebuilding the reminders failed; retried on the next start. */
    fun rescheduleNeeded(): Boolean = prefs.getBoolean(KEY_RESCHEDULE, false)
    fun setRescheduleNeeded(value: Boolean) = prefs.edit { putBoolean(KEY_RESCHEDULE, value) }

    fun string(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String?) = prefs.edit { putString(key, value) }
    fun long(key: String): Long = prefs.getLong(key, 0L)
    fun putLong(key: String, value: Long) = prefs.edit { putLong(key, value) }

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

    companion object {
        const val GLASS_CLEAR = "clear"
        const val GLASS_DEEP = "deep"
        private const val KEY_GLASS = "glass"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_UP_RED = "up_is_red"
        private const val KEY_BACKUP_URI = "backup_tree_uri"
        private const val KEY_LAST_BACKUP = "last_backup_at"
        private const val KEY_NETWORK = "network_enabled"
        private const val KEY_WEATHER = "weather_enabled"
        private const val KEY_HOLIDAY_UPDATE = "holiday_update_enabled"
        private const val KEY_ALL_ALARMS = "all_reminders_as_alarms"
        private const val KEY_DIARY_REMINDER = "diary_reminder_enabled"
        private const val KEY_DIARY_REMINDER_AT = "diary_reminder_minutes"
        private const val KEY_GUIDE_SEEN = "reminder_guide_seen"
        private const val KEY_RESCHEDULE = "reschedule_needed"
    }
}
