package com.assetboard.data

import android.content.Context
import com.assetboard.domain.AppClock
import com.assetboard.domain.LedgerException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

/** The last reachability probe for one server address. */
data class ServerCheck(
    val running: Boolean = false,
    val ok: Boolean? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
    val server: String = "",
    val at: Long = 0,
)

data class AccountState(
    val signedIn: Boolean = false,
    val username: String? = null,
    val serverUrl: String = "",
    /** A sync is running (upload, download, or a metadata read). */
    val busy: Boolean = false,
    /** A sign-in is running, including the reachability probe that comes before it. */
    val loginBusy: Boolean = false,
    val check: ServerCheck = ServerCheck(),
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
    private val checkMutex = Mutex()
    /** Every attempt gets a number; a cancelled or superseded one may not touch the state. */
    private val loginGeneration = AtomicLong(0)
    @Volatile private var loginJob: Job? = null
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
     * Checks that the address is really this app's server and measures the round trip. Cached for
     * a few seconds so a login right after a manual check does not wait twice; [force] is the
     * "检测连接" button, which must always ask the network again.
     */
    suspend fun checkServer(serverUrl: String, force: Boolean = false): Boolean {
        val server = serverUrl.trim().trimEnd('/')
        if (server.isBlank()) {
            _state.update { it.copy(check = ServerCheck(ok = false, error = "请先填写服务器地址", server = server)) }
            return false
        }
        if (!settings.networkEnabled.value) {
            _state.update { it.copy(check = ServerCheck(ok = false, error = "已关闭联网，无法连接服务器", server = server)) }
            return false
        }
        return checkMutex.withLock {
            val current = _state.value.check
            val now = AppClock.millis()
            // A failure is cached only briefly: switching networks and retrying must probe again.
            val cacheMs = if (current.ok == true) CHECK_CACHE_MS else CHECK_FAILURE_CACHE_MS
            if (!force && current.server == server && current.ok != null && current.at > 0 &&
                now - current.at < cacheMs
            ) {
                return@withLock current.ok == true
            }
            _state.update { it.copy(check = it.check.copy(running = true, ok = null, error = null, server = server)) }
            try {
                val latency = api.probe(server)
                _state.update {
                    it.copy(check = ServerCheck(ok = true, latencyMs = latency, server = server, at = AppClock.millis()))
                }
                true
            } catch (e: CancellationException) {
                _state.update { it.copy(check = it.check.copy(running = false)) }
                throw e
            } catch (e: Exception) {
                val message = (e as? LedgerException)?.message ?: e.message ?: "无法连接服务器"
                _state.update {
                    it.copy(check = ServerCheck(ok = false, error = message, server = server, at = AppClock.millis()))
                }
                false
            }
        }
    }

    /** The user left the login page or pressed cancel: stop waiting and leave the form idle. */
    fun cancelLogin() {
        // Supersede first: a request that is already in a blocking read must not come back
        // and sign the user in or turn the spinner off under the next attempt.
        loginGeneration.incrementAndGet()
        val job = loginJob
        loginJob = null
        job?.cancel()
        _state.update {
            it.copy(
                loginBusy = false,
                // A cancelled attempt leaves no error; a sync error that was already there stays.
                error = if (job != null) null else it.error,
                check = it.check.copy(running = false),
            )
        }
    }

    private fun isCurrentLogin(generation: Long): Boolean = loginGeneration.get() == generation

    /** The page went away while "检测连接" was running: drop the spinner, keep the last result. */
    fun cancelCheck() {
        _state.update { it.copy(check = it.check.copy(running = false)) }
    }

    /**
     * Signs in and decides which local database the account belongs to:
     * - this database, if it is this account or has never been bound;
     * - an existing database of this account, when switching back;
     * - a new database of its own, when this one belongs to another account.
     *
     * The reachability probe runs first, so an unreachable network fails in seconds with a
     * "change networks" message instead of a full request timeout.
     */
    suspend fun login(serverUrl: String, username: String, password: String) {
        val generation = loginGeneration.incrementAndGet()
        val self = currentCoroutineContext()[Job]
        loginJob = self
        _state.update {
            if (isCurrentLogin(generation)) {
                it.copy(loginBusy = true, error = null, message = null)
            } else {
                it
            }
        }
        // The page can be left between the call and this point; cancelLogin already reset the
        // form, so this attempt must not put the spinner back.
        if (!isCurrentLogin(generation)) return
        try {
            val server = serverUrl.trim().trimEnd('/')
            if (!settings.networkEnabled.value) {
                _state.update { it.copy(error = "已关闭联网，无法登录。请到「设置」里打开「允许联网」。") }
                return
            }
            if (!checkServer(server)) {
                // The probe's messages already say what to try (change networks, fix the
                // address, turn networking back on); do not stack another hint on top.
                if (isCurrentLogin(generation)) {
                    _state.update { it.copy(error = _state.value.check.error ?: "无法连接服务器") }
                }
                return
            }
            if (!isCurrentLogin(generation)) return
            val result = api.login(server, username, password)
            if (!isCurrentLogin(generation)) return
            // The credentials are accepted. From here on, a screen that goes away must not
            // cancel the profile switch or the first sync.
            loginJob = null
            val target = bind(result.user.id, result.user.username, server)
            profiles.save(target)
            SessionStore(context, target.id).save(result.refreshToken)
            access = result.accessToken
            accessUntil = AppClock.millis() + result.expiresIn * 1000

            if (target.id != profile.id) {
                _state.update {
                    it.copy(
                        loginBusy = false, signedIn = true, username = target.username,
                        serverUrl = server, error = null,
                    )
                }
                onSwitchProfile(target)
                return
            }
            _state.update {
                it.copy(
                    loginBusy = false, signedIn = true, username = target.username, serverUrl = server,
                    revision = target.lastRevision, lastSyncAt = target.lastSyncAt, error = null,
                    message = "已登录 ${target.username}",
                )
            }
            syncNow()
        } catch (e: CancellationException) {
            if (isCurrentLogin(generation)) _state.update { it.copy(loginBusy = false) }
            throw e
        } catch (e: Exception) {
            if (isCurrentLogin(generation)) {
                val message = when {
                    e is ApiException && e.status == 401 -> "用户名或密码错误"
                    else -> (e as? LedgerException)?.message ?: e.message ?: "登录失败"
                }
                _state.update { it.copy(loginBusy = false, error = message) }
            }
        } finally {
            // A cancelled or superseded attempt leaves the newer one's spinner and error alone.
            if (isCurrentLogin(generation)) {
                if (loginJob === self) loginJob = null
                _state.update { if (it.loginBusy) it.copy(loginBusy = false) else it }
            }
        }
    }

    /**
     * Replaces this account's password after proving the old one. The server ends every session;
     * this device receives and saves a new pair, so it stays signed in while other devices sign out.
     * Returns null on success, else the message to show beside the dialog's fields.
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): String? {
        if (!mutex.tryLock()) return "正在同步，等同步完成后再修改密码"
        try {
            val p = currentProfile()
            if (p.accountId == null || !session.has()) {
                expire()
                return "登录已过期，请重新登录"
            }
            if (!settings.networkEnabled.value) return "已关闭联网，无法修改密码"
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                ensureAccess(p)
                val result = api.changePassword(
                    p.serverUrl ?: settings.syncServerUrl.value, access!!, oldPassword, newPassword,
                )
                access = result.accessToken
                accessUntil = AppClock.millis() + result.expiresIn * 1000
                session.save(result.refreshToken)
                _state.update {
                    it.copy(
                        busy = false, signedIn = true, username = result.user.username,
                        error = null, message = "密码已修改，其他设备需要重新登录",
                    )
                }
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A wrong old password is not an expired session, and must not sign the user out.
                if (e is ApiException && e.status == 401 && e.code != "invalid_old_password") {
                    expire()
                    return "登录已过期，请重新登录"
                }
                return (e as? LedgerException)?.message ?: e.message ?: "修改密码失败"
            }
        } finally {
            _state.update { if (it.busy) it.copy(busy = false) else it }
            mutex.unlock()
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
        // Keep a two-minute margin: a snapshot transfer can outlive a token that looked fresh
        // when the sync started, and the server would answer 401 halfway through.
        if (access != null && now + 120_000 < accessUntil) return
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
        _state.update {
            it.copy(signedIn = false, busy = false, loginBusy = false, error = "登录已过期，请重新登录", conflict = null)
        }
    }

    private companion object {
        /** How long a successful probe stands before the next login asks the network again. */
        const val CHECK_CACHE_MS = 15_000L
        /** A failed probe is not reused for long: the user may have just changed networks. */
        const val CHECK_FAILURE_CACHE_MS = 3_000L
    }
}
