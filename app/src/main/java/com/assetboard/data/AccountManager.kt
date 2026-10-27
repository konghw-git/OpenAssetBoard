package com.assetboard.data

import android.content.Context
import com.assetboard.domain.AppClock
import com.assetboard.domain.LedgerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A remote snapshot that changed while this device also has changes. Never merged; the user picks. */
data class SyncConflict(val remote: RemoteMeta, val localExportedAt: Long)

data class AccountState(
    val signedIn: Boolean = false,
    val username: String? = null,
    val serverUrl: String = "",
    val busy: Boolean = false,
    val lastSyncAt: Long? = null,
    val revision: Long = 0,
    /** One-shot notice for a snackbar. */
    val message: String? = null,
    val error: String? = null,
    val conflict: SyncConflict? = null,
)

enum class ConflictChoice { KEEP_LOCAL, USE_REMOTE }

/**
 * Login, logout and snapshot sync for the active profile. The local database is always the
 * working copy; the server holds one full backup file per account, and a sync either uploads,
 * downloads, or stops and asks. There is deliberately no automatic merge.
 */
class AccountManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val profiles: ProfileStore,
    private val profile: Profile,
    private val backup: BackupManager,
    private val scope: CoroutineScope,
    private val onSwitchProfile: suspend (Profile) -> Unit,
    private val api: SyncGateway = SyncApi { settings.networkEnabled.value },
) {
    private val session = SessionStore(context, profile.id)
    private val mutex = Mutex()
    private var access: String? = null
    private var accessUntil = 0L

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    fun currentProfile(): Profile = profiles.byId(profile.id) ?: profile

    fun isSignedIn(): Boolean = currentProfile().accountId != null && session.has()

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun clearConflict() = _state.update { it.copy(conflict = null) }

    private fun initialState(): AccountState {
        val p = currentProfile()
        val token = session.load()
        return AccountState(
            signedIn = p.accountId != null && token != null,
            username = p.username,
            serverUrl = p.serverUrl ?: settings.syncServerUrl.value,
            lastSyncAt = p.lastSyncAt,
            revision = p.lastRevision,
            error = if (p.accountId != null && token == null) "登录已过期，请重新登录" else null,
        )
    }

    /**
     * Signs in and decides which local database the account belongs to:
     * - this database, if it is this account or has never been bound;
     * - an existing database of this account, when switching back;
     * - a new database of its own, when this one belongs to another account.
     */
    suspend fun login(serverUrl: String, username: String, password: String) {
        _state.update { it.copy(busy = true, error = null, message = null) }
        try {
            val server = serverUrl.trim().trimEnd('/')
            val result = api.login(server, username, password)
            val target = bind(result.user.id, result.user.username, server)
            profiles.save(target)
            SessionStore(context, target.id).save(result.refreshToken)
            access = result.accessToken
            accessUntil = AppClock.millis() + result.expiresIn * 1000

            if (target.id != profile.id) {
                _state.update { it.copy(busy = false, signedIn = true, username = target.username, serverUrl = server) }
                onSwitchProfile(target)
                return
            }
            _state.update {
                it.copy(
                    busy = false, signedIn = true, username = target.username, serverUrl = server,
                    revision = target.lastRevision, lastSyncAt = target.lastSyncAt, error = null,
                    message = "已登录 ${target.username}",
                )
            }
            syncNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when {
                e is ApiException && e.status == 401 -> "用户名或密码错误"
                else -> (e as? LedgerException)?.message ?: e.message ?: "登录失败"
            }
            _state.update { it.copy(busy = false, error = message) }
        }
    }

    private fun bind(accountId: String, username: String, serverUrl: String): Profile {
        val current = currentProfile()
        val canAdopt = current.accountId == accountId || current.lastAccountId == accountId ||
            (current.accountId == null && current.lastAccountId == null)
        if (canAdopt) {
            return current.copy(accountId = accountId, lastAccountId = accountId, username = username, serverUrl = serverUrl)
        }
        val existing = profiles.findForAccount(accountId)
        if (existing != null && existing.id != current.id) {
            return existing.copy(accountId = accountId, lastAccountId = accountId, username = username, serverUrl = serverUrl)
        }
        val id = "p-$accountId"
        return Profile(
            id = id,
            dbFile = "asset-board-$id.db",
            accountId = accountId,
            lastAccountId = accountId,
            username = username,
            serverUrl = serverUrl,
            createdAt = AppClock.millis(),
        )
    }

    /** Ends the session but keeps the local database; the next launch still opens it. */
    suspend fun logout() {
        _state.update { it.copy(busy = true, error = null) }
        mutex.withLock {
            val p = currentProfile()
            val token = session.load()
            // Best effort, and bounded: the local session must end even if the server is unreachable.
            if (token != null && p.serverUrl != null) {
                runCatching { withTimeoutOrNull(5_000) { api.logout(p.serverUrl, token) } }
            }
            session.clear()
            access = null
            accessUntil = 0
            profiles.save(p.copy(accountId = null, lastAccountId = p.accountId ?: p.lastAccountId))
            _state.value = initialState().copy(message = "已退出登录")
        }
    }

    suspend fun syncNow() {
        if (!mutex.tryLock()) return
        try {
            val p = currentProfile()
            if (p.accountId == null) {
                _state.update { it.copy(signedIn = false, busy = false) }
                return
            }
            if (session.load() == null) {
                expire()
                return
            }
            if (!settings.networkEnabled.value) {
                _state.update { it.copy(error = "已关闭联网，无法同步") }
                return
            }
            if (!backup.canSync()) {
                _state.update { it.copy(error = "正在恢复数据，请稍后再同步") }
                return
            }
            _state.update { it.copy(busy = true, error = null, message = null) }
            ensureAccess(p)
            val server = p.serverUrl ?: settings.syncServerUrl.value
            val remote = api.meta(server, access!!)
            val local = backup.exportForSync()
            when (SyncDecision.decide(remote, p, local.contentHash, local.isEmpty)) {
                SyncAction.UP_TO_DATE -> markSynced(p, remote.revision, remote.contentHash, "已是最新")
                SyncAction.UPLOAD -> upload(p, remote, local)
                SyncAction.DOWNLOAD -> download(p, remote)
                SyncAction.CONFLICT -> _state.update {
                    it.copy(busy = false, conflict = SyncConflict(remote, local.exportedAt))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handle(e)
        } finally {
            _state.update { if (it.busy) it.copy(busy = false) else it }
            mutex.unlock()
        }
    }

    private suspend fun upload(p: Profile, remote: RemoteMeta, local: BackupManager.SyncSnapshot) {
        val server = p.serverUrl ?: settings.syncServerUrl.value
        try {
            val revision = api.upload(server, access!!, remote.revision, local.json, local.contentHash)
            markSynced(p, revision, local.contentHash, "已同步到云端（版本 $revision）")
        } catch (e: ApiException) {
            if (e.status != 409) throw e
            // Someone uploaded between the metadata read and this write. Show the new remote
            // revision instead of retrying blindly; the next decision is the user's.
            val latest = api.meta(server, access!!)
            _state.update { it.copy(busy = false, conflict = SyncConflict(latest, local.exportedAt)) }
        }
    }

    private suspend fun download(p: Profile, remote: RemoteMeta) {
        val text = api.download(p.serverUrl ?: settings.syncServerUrl.value, access!!)
            ?: throw ApiException("云端还没有快照")
        backup.restoreFromJson(text)
        markSynced(p, remote.revision, remote.contentHash, "已从云端同步")
    }

    suspend fun resolveConflict(choice: ConflictChoice) {
        val conflict = _state.value.conflict ?: return
        if (!mutex.tryLock()) return
        try {
            _state.update { it.copy(busy = true, conflict = null, error = null) }
            val p = currentProfile()
            if (p.accountId == null) {
                expire()
                return
            }
            ensureAccess(p)
            when (choice) {
                ConflictChoice.KEEP_LOCAL -> upload(p, conflict.remote, backup.exportForSync())
                ConflictChoice.USE_REMOTE -> download(p, conflict.remote)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handle(e)
        } finally {
            _state.update { if (it.busy) it.copy(busy = false) else it }
            mutex.unlock()
        }
    }

    /** On launch, sync quietly if there is a session; a failure only shows in the account page. */
    fun onAppStart() {
        val p = currentProfile()
        if (p.accountId == null || !session.has()) return
        scope.launch {
            delay(6_000)
            runCatching { syncNow() }
        }
    }

    private suspend fun ensureAccess(p: Profile) {
        val now = AppClock.millis()
        if (access != null && now + 30_000 < accessUntil) return
        val refresh = session.load() ?: throw ApiException("登录已过期，请重新登录", 401)
        val result = api.refresh(p.serverUrl ?: settings.syncServerUrl.value, refresh)
        access = result.accessToken
        accessUntil = now + result.expiresIn * 1000
        session.save(result.refreshToken)
    }

    private fun markSynced(p: Profile, revision: Long, hash: String, message: String) {
        // Read the profile again: a logout can have cleared accountId while the sync was in
        // flight, and this must not sign it back in.
        val latest = profiles.byId(p.id) ?: p
        val updated = latest.copy(lastRevision = revision, lastSyncedHash = hash, lastSyncAt = AppClock.millis())
        profiles.save(updated)
        _state.update {
            it.copy(
                signedIn = updated.accountId != null && session.has(), busy = false,
                username = updated.username, serverUrl = updated.serverUrl ?: it.serverUrl,
                revision = revision, lastSyncAt = updated.lastSyncAt, message = message, error = null, conflict = null,
            )
        }
    }

    private fun handle(e: Exception) {
        if (e is ApiException && e.status == 401) {
            expire()
            return
        }
        _state.update {
            it.copy(
                busy = false,
                error = (e as? LedgerException)?.message ?: e.message ?: "同步失败",
            )
        }
    }

    /** The refresh token no longer works: sign out locally but keep the data. */
    private fun expire() {
        session.clear()
        access = null
        accessUntil = 0
        val p = currentProfile()
        if (p.accountId != null) profiles.save(p.copy(accountId = null, lastAccountId = p.accountId))
        _state.update { it.copy(signedIn = false, busy = false, error = "登录已过期，请重新登录", conflict = null) }
    }
}