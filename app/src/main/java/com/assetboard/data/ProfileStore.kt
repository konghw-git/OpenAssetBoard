package com.assetboard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import com.assetboard.BuildConfig
import com.assetboard.domain.AppClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * One local database, optionally bound to one server account. The first profile is the install's
 * original database; logging into a different account later gives that account a database of its
 * own, so two accounts on one phone never see each other's rows.
 */
@Serializable
data class Profile(
    val id: String,
    /** The Room file, relative to the app's database directory. */
    val dbFile: String,
    /** The account this database is currently signed in as; null while signed out. */
    val accountId: String? = null,
    /** The last account it was signed in as, kept after logout to find this database again. */
    val lastAccountId: String? = null,
    val username: String? = null,
    val serverUrl: String? = null,
    val lastRevision: Long = 0,
    val lastSyncedHash: String? = null,
    val lastSyncAt: Long? = null,
    val createdAt: Long = 0,
) {
    companion object {
        const val LOCAL_ID = "local"
        const val LOCAL_DB = "asset-board-local.db"

        fun local(createdAt: Long = 0, dbFile: String = LOCAL_DB) = Profile(id = LOCAL_ID, dbFile = dbFile, createdAt = createdAt)
    }
}

/**
 * The profiles on this device and the one shown at launch, plus the monotonic counter every new
 * database's data epoch starts from. Its preferences are device-local and never backed up.
 */
class ProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Profile.serializer())

    @Synchronized
    fun profiles(): List<Profile> = load()

    @Synchronized
    fun byId(id: String): Profile? = load().firstOrNull { it.id == id }

    /** The database for [accountId], whether it is signed in or was logged out earlier. */
    @Synchronized
    fun findForAccount(accountId: String): Profile? {
        val list = load()
        return list.firstOrNull { it.accountId == accountId }
            ?: list.filter { it.accountId == null && it.lastAccountId == accountId }.maxByOrNull { it.createdAt }
    }

    @Synchronized
    fun active(): Profile {
        val list = load()
        if (list.isEmpty()) return createInitial()
        val id = prefs.getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.id == id } ?: list.first().also { setActive(it.id) }
    }

    @Synchronized
    fun setActive(id: String) {
        prefs.edit(commit = true) { putString(KEY_ACTIVE, id) }
    }

    @Synchronized
    fun save(profile: Profile): Profile {
        val list = load().toMutableList()
        val at = list.indexOfFirst { it.id == profile.id }
        if (at >= 0) list[at] = profile else list += profile
        persist(list)
        return profile
    }

    /** Every container gets an epoch no other database on this device has used. */
    @Synchronized
    fun nextProfileEpoch(): Long {
        val next = maxOf(prefs.getLong(KEY_EPOCH, 0L) + 1, AppClock.millis())
        prefs.edit(commit = true) { putLong(KEY_EPOCH, next) }
        return next
    }

    /** A restore inside a database can move its epoch past the counter; keep the counter ahead. */
    @Synchronized
    fun raiseProfileEpoch(atLeast: Long) {
        if (prefs.getLong(KEY_EPOCH, 0L) >= atLeast) return
        prefs.edit(commit = true) { putLong(KEY_EPOCH, atLeast) }
    }

    private fun load(): List<Profile> {
        val list = prefs.getString(KEY_PROFILES, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()
        if (BuildConfig.SYNC_BASE_URL == LEGACY_SYNC_SERVER_URL) return list
        var moved = false
        val migrated = list.map { profile ->
            if (profile.serverUrl == LEGACY_SYNC_SERVER_URL) {
                moved = true
                profile.copy(serverUrl = BuildConfig.SYNC_BASE_URL)
            } else {
                profile
            }
        }
        if (moved) persist(migrated)
        return migrated
    }

    private fun persist(list: List<Profile>) {
        prefs.edit(commit = true) { putString(KEY_PROFILES, json.encodeToString(serializer, list)) }
    }

    private fun createInitial(): Profile {
        val dbFile = if (prefs.getBoolean(KEY_MIGRATED, false)) {
            when {
                context.getDatabasePath(Profile.LOCAL_DB).exists() -> Profile.LOCAL_DB
                context.getDatabasePath("asset-board.db").exists() -> "asset-board.db"
                else -> Profile.LOCAL_DB
            }
        } else {
            migrateLegacy().also { prefs.edit(commit = true) { putBoolean(KEY_MIGRATED, true) } }
        }
        val profile = Profile.local(createdAt = AppClock.millis(), dbFile = dbFile)
        persist(listOf(profile))
        setActive(profile.id)
        return profile
    }

    /**
     * Moves the pre-account database to the local profile's name. Sides are copied too and put in
     * place before the main file, so a crash cannot leave a database without the WAL that carries
     * its newest commits. If anything fails the original name is kept and the app carries on. A
     * crash in the middle is retried next launch: the marker is only written after this returns.
     */
    private fun migrateLegacy(): String {
        val legacy = context.getDatabasePath("asset-board.db")
        val local = context.getDatabasePath(Profile.LOCAL_DB)
        if (!legacy.exists()) {
            // A completed migration deleted the legacy file; a partial local file from a crash
            // is still replaced only if the legacy is the source of truth.
            return Profile.LOCAL_DB
        }
        val tmp = File(local.path + ".migrating")
        val sides = listOf("-wal", "-shm", "-journal")
        fun removeAttempt() {
            for (suffix in listOf("") + sides) {
                File(tmp.path + suffix).delete()
                File(local.path + suffix).delete()
            }
        }
        // The marker says the previous run did not finish; drop whatever it left behind.
        removeAttempt()
        return try {
            local.parentFile?.mkdirs()
            copy(legacy, tmp)
            for (suffix in sides) copy(File(legacy.path + suffix), File(tmp.path + suffix))
            for (suffix in sides) {
                val from = File(tmp.path + suffix)
                val to = File(local.path + suffix)
                if (from.exists()) {
                    to.delete()
                    if (!from.renameTo(to)) throw IOException("无法移动 $from")
                }
            }
            if (!tmp.renameTo(local)) throw IOException("无法移动 $tmp")
            // Never delete the old file until the copy has proved it can be read.
            val healthy = runCatching {
                SQLiteDatabase.openDatabase(local.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("PRAGMA integrity_check", null).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "ok" }
                }
            }.getOrDefault(false)
            if (!healthy) throw IOException("复制后的数据库未通过完整性检查")
            for (suffix in listOf("") + sides) File(legacy.path + suffix).delete()
            Profile.LOCAL_DB
        } catch (e: Exception) {
            removeAttempt()
            "asset-board.db"
        }
    }

    private fun copy(from: File, to: File) {
        if (!from.exists()) return
        from.copyTo(to, overwrite = true)
        RandomAccessFile(to, "rw").use { it.fd.sync() }
    }

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active"
        private const val KEY_EPOCH = "profile_epoch"
        private const val KEY_MIGRATED = "legacy_migrated"
    }
}