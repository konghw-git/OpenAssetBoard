package com.assetboard.data

import android.content.Context
import androidx.core.content.edit
import com.assetboard.BuildConfig
import com.assetboard.data.sync.LEGACY_SYNC_SERVER_URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/**
 * How a card deck opens: the [CAROUSEL] or the one-per-row [LIST] of the "open in one tap" mode.
 * [stored] is what goes to disk, so renaming the entries cannot break a remembered choice.
 */
enum class DeckMode(val stored: String) { CAROUSEL("carousel"), LIST("list") }

/** Why the last backup did not happen, and when. */
data class BackupFailure(val reason: String, val at: Long)

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
    /** Look for a new version once a day on start (only while networking is on). */
    val autoUpdateCheck = bool(KEY_AUTO_UPDATE, true)
    /** Amounts read **** on the overview, wherever an amount is shown as such, and on the widget. */
    val hideAmounts = bool(KEY_HIDE_AMOUNTS, false)

    /** The self-hosted server the account page signs in to; a fork sets its own default. */
    private val _syncServerUrl = MutableStateFlow(
        when (val stored = prefs.getString(KEY_SYNC_SERVER, null)) {
            null -> BuildConfig.SYNC_BASE_URL
            // A carried-over preference must not point at the server that was moved away from.
            LEGACY_SYNC_SERVER_URL -> BuildConfig.SYNC_BASE_URL
            else -> stored
        },
    )
    val syncServerUrl: StateFlow<String> = _syncServerUrl.asStateFlow()

    fun setSyncServerUrl(url: String) {
        val value = url.trim()
        prefs.edit { putString(KEY_SYNC_SERVER, value) }
        _syncServerUrl.value = value
    }

    /**
     * Minutes between automatic syncs while signed in: 0 = off, 5 = only in the foreground
     * (WorkManager's floor is 15), 15/30/60 = also in the background. Default 15.
     */
    private val _syncIntervalMinutes = MutableStateFlow(
        prefs.getInt(KEY_SYNC_INTERVAL, 15).takeIf { it in SYNC_INTERVALS } ?: 15,
    )
    val syncIntervalMinutes: StateFlow<Int> = _syncIntervalMinutes.asStateFlow()

    fun setSyncInterval(minutes: Int) {
        val value = if (minutes in SYNC_INTERVALS) minutes else 15
        prefs.edit { putInt(KEY_SYNC_INTERVAL, value) }
        _syncIntervalMinutes.value = value
    }

    // ------------------------------------------------------------ AI assistant

    /**
     * The AI editor helpers' endpoint: an OpenAI-compatible chat API (Bailian by default). The key
     * lives only on this device — settings are not backed up, not synced, not in the repository —
     * and on disk it is encrypted with the same Keystore cipher as the session tokens, never in
     * the clear. A broken Keystore keeps it in memory only, like [SessionStore] does.
     */
    private val _aiApiKey = MutableStateFlow(loadAiApiKey())
    val aiApiKey: StateFlow<String> = _aiApiKey.asStateFlow()

    fun setAiApiKey(value: String) {
        _aiApiKey.value = value
        if (value.isEmpty()) {
            prefs.edit { remove(KEY_AI_API_KEY) }
            return
        }
        // A broken Keystore leaves the disk copy alone, like SessionStore does: deleting it
        // would lose a key an earlier run managed to store.
        encodeAiKey(value) { TokenCipher.encrypt(it) }?.let { stored ->
            prefs.edit { putString(KEY_AI_API_KEY, stored) }
        }
    }

    private fun loadAiApiKey(): String {
        val stored = prefs.getString(KEY_AI_API_KEY, null)
        val plain = decodeAiKey(stored) { TokenCipher.decrypt(it) }
        // A value written before the Keystore migration: use it once and move it to the
        // encrypted form. If the Keystore is broken the legacy value stays as it was —
        // never written by this version, and deleting it would lose the key outright.
        if (stored != null && plain.isNotEmpty() && !stored.startsWith(ENC_PREFIX)) {
            encodeAiKey(plain) { TokenCipher.encrypt(it) }?.let { enc ->
                prefs.edit { putString(KEY_AI_API_KEY, enc) }
            }
        }
        return plain
    }

    private val _aiBaseUrl = MutableStateFlow(prefs.getString(KEY_AI_BASE_URL, null) ?: DEFAULT_AI_BASE_URL)
    val aiBaseUrl: StateFlow<String> = _aiBaseUrl.asStateFlow()

    fun setAiBaseUrl(value: String) {
        prefs.edit { putString(KEY_AI_BASE_URL, value) }
        _aiBaseUrl.value = value
    }

    private val _aiModel = MutableStateFlow(prefs.getString(KEY_AI_MODEL, null) ?: DEFAULT_AI_MODEL)
    val aiModel: StateFlow<String> = _aiModel.asStateFlow()

    fun setAiModel(value: String) {
        prefs.edit { putString(KEY_AI_MODEL, value) }
        _aiModel.value = value
    }

    fun setBool(flow: MutableStateFlow<Boolean>, value: Boolean) {
        val key = when (flow) {
            networkEnabled -> KEY_NETWORK
            weatherEnabled -> KEY_WEATHER
            holidayUpdateEnabled -> KEY_HOLIDAY_UPDATE
            allRemindersAsAlarms -> KEY_ALL_ALARMS
            diaryReminderEnabled -> KEY_DIARY_REMINDER
            reminderGuideSeen -> KEY_GUIDE_SEEN
            autoUpdateCheck -> KEY_AUTO_UPDATE
            hideAmounts -> KEY_HIDE_AMOUNTS
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

    /** The way a deck ([DECK_LISTS], [DECK_TOPICS]) was last opened, or null while never chosen. */
    fun deckMode(deck: String): DeckMode? =
        prefs.getString(deck, null)?.let { stored -> DeckMode.entries.firstOrNull { it.stored == stored } }

    fun setDeckMode(deck: String, mode: DeckMode) {
        prefs.edit { putString(deck, mode.stored) }
    }

    fun string(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String?) = prefs.edit { putString(key, value) }
    fun long(key: String): Long = prefs.getLong(key, 0L)
    /** [now] writes it to disk before returning, for what must survive the process ending right after. */
    fun putLong(key: String, value: Long, now: Boolean = false) = prefs.edit(commit = now) { putLong(key, value) }

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

    /** A backup that worked; it also clears the last failure. */
    fun setLastBackupAt(at: Long) {
        prefs.edit {
            putLong(KEY_LAST_BACKUP, at)
            remove(KEY_BACKUP_ERROR)
            remove(KEY_BACKUP_ERROR_AT)
        }
        _lastBackupAt.value = at
        _backupError.value = null
    }

    private val _backupError = MutableStateFlow(
        prefs.getString(KEY_BACKUP_ERROR, null)?.let { BackupFailure(it, prefs.getLong(KEY_BACKUP_ERROR_AT, 0L)) },
    )
    /** The last backup that failed, until one works again. */
    val backupError: StateFlow<BackupFailure?> = _backupError.asStateFlow()

    fun setBackupError(reason: String, at: Long) {
        prefs.edit {
            putString(KEY_BACKUP_ERROR, reason)
            putLong(KEY_BACKUP_ERROR_AT, at)
        }
        _backupError.value = BackupFailure(reason, at)
    }

    companion object {
        /** Deck-mode memory keys; the two decks remember their choice independently. */
        const val DECK_LISTS = "deck_mode_lists"
        const val DECK_TOPICS = "deck_mode_topics"
        const val GLASS_CLEAR = "clear"
        const val GLASS_DEEP = "deep"
        /** Aliyun Bailian's OpenAI-compatible endpoint, and a DeepSeek model on it. */
        val SYNC_INTERVALS = setOf(0, 5, 15, 30, 60)
        const val DEFAULT_AI_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DEFAULT_AI_MODEL = "deepseek-v4.1-flash-1017"
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
        private const val KEY_AUTO_UPDATE = "auto_update_check"
        private const val KEY_RESCHEDULE = "reschedule_needed"
        private const val KEY_HIDE_AMOUNTS = "hide_amounts"
        private const val KEY_SYNC_SERVER = "sync_server_url"
        private const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        private const val KEY_BACKUP_ERROR = "backup_error"
        private const val KEY_BACKUP_ERROR_AT = "backup_error_at"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_MODEL = "ai_model"
    }
}

/** Values written since the Keystore migration carry this marker; anything else is legacy plaintext. */
private const val ENC_PREFIX = "enc:"

/**
 * The on-disk shape of the AI key, pure so the migration rules are testable without a Keystore.
 * [encrypt] returns null when the Keystore is unavailable, and then nothing is written at all:
 * the key lives in memory for this process rather than in the clear on disk.
 */
internal fun encodeAiKey(value: String, encrypt: (String) -> String?): String? =
    if (value.isEmpty()) null else encrypt(value)?.let { ENC_PREFIX + it }

internal fun decodeAiKey(stored: String?, decrypt: (String) -> String?): String = when {
    stored.isNullOrEmpty() -> ""
    !stored.startsWith(ENC_PREFIX) -> stored // legacy plaintext from before the migration
    else -> decrypt(stored.removePrefix(ENC_PREFIX)) ?: ""
}
