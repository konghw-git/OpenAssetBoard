package com.assetboard.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.assetboard.data.Profile
import com.assetboard.data.ProfileStore
import com.assetboard.data.SessionStore
import com.assetboard.data.SettingsStore
import com.assetboard.data.backup.BackupManager
import com.assetboard.domain.AppClock
import com.assetboard.domain.LedgerException
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    /** The server's retained versions, loaded for the history sheet; newest first. */
    val revisions: List<RemoteMeta> = emptyList(),
    val historyBusy: Boolean = false,
)

/**
 * How a sync attempt ended. The quiet background worker comes back only for [RETRY] — a
 * transient connectivity or server failure; everything else is either done or pointless to
 * repeat (signed out, a snapshot that needs a newer app, a permanently racing upload).
 */
enum class SyncOutcome { DONE, SKIPPED, RETRY }

/**
 * Login, logout and snapshot sync for the active profile. The local database is always the
 * working copy; the server holds one full backup file per account. When both sides changed the
 * merge runs locally (MergeWriter) and the result is uploaded, so there is no conflict dialog:
 * record-level LWW decides, and only the repair count is reported.
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
    private val mergeWriter: MergeWriter = MergeWriter(backup),
) {
    @Volatile private var foreground = false
    @Volatile private var tickerStarted = false
    private var debounceJob: Job? = null
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

    /**
     * One sync attempt: read the remote metadata, then upload, download, or stop and ask.
     * [fromUser] marks the manual buttons; only they report "already running" instead of
     * silently stepping aside for the attempt that is holding the lock.
     */
    suspend fun syncNow(fromUser: Boolean = false): SyncOutcome = performSync(fromUser, waitForRunning = false)

    private suspend fun performSync(fromUser: Boolean, waitForRunning: Boolean): SyncOutcome {
        if (waitForRunning) mutex.lock()
        else if (!mutex.tryLock()) {
            if (fromUser) _state.update { it.copy(message = "正在同步") }
            return SyncOutcome.SKIPPED
        }
        try {
            val p = currentProfile()
            if (p.accountId == null) {
                _state.update { it.copy(signedIn = false, busy = false) }
                return SyncOutcome.SKIPPED
            }
            if (session.load() == null) {
                expire()
                return SyncOutcome.SKIPPED
            }
            if (!settings.networkEnabled.value) {
                _state.update { it.copy(error = "已关闭联网，无法同步") }
                return SyncOutcome.SKIPPED
            }
            if (!backup.canSync()) {
                _state.update { it.copy(error = "正在恢复数据，请稍后再同步") }
                return SyncOutcome.SKIPPED
            }
            _state.update { it.copy(busy = true, error = null, message = null) }
            ensureAccess(p)
            val server = p.serverUrl ?: settings.syncServerUrl.value
            val remote = api.meta(server, access!!)
            val local = backup.exportForSync()
            val pendingReplace = backup.pendingReplace()
            val decision = SyncDecision.decide(remote, p, local.contentHash, local.isEmpty)
            val action = if (remote.revision > 0 && decision != SyncAction.REMOTE_TOO_NEW && pendingReplace != null) {
                SyncAction.MERGE
            } else decision
            when (action) {
                SyncAction.UP_TO_DATE -> markSynced(p, remote.revision, remote.contentHash, "已是最新")
                SyncAction.UPLOAD -> upload(p, remote, local)
                SyncAction.DOWNLOAD -> download(p)
                SyncAction.MERGE -> mergeWithRemote(p)
                // Both directions are wrong here: the snapshot cannot be read, and uploading
                // would overwrite data from a newer app. The only way forward is an upgrade.
                SyncAction.REMOTE_TOO_NEW -> _state.update {
                    it.copy(busy = false, error = "云端数据来自更新版本的日盈，请先升级 App 再同步")
                }
            }
            if (action != SyncAction.REMOTE_TOO_NEW && pendingReplace != null) backup.clearPendingReplace(pendingReplace)
            return SyncOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handle(e)
            return if (transient(e)) SyncOutcome.RETRY else SyncOutcome.DONE
        } finally {
            _state.update { if (it.busy) it.copy(busy = false) else it }
            mutex.unlock()
        }
    }

    /** Uploads the local snapshot; a 409 means someone won the race, so merge and retry. */
    private suspend fun upload(p: Profile, remote: RemoteMeta, local: BackupManager.SyncSnapshot) {
        val server = p.serverUrl ?: settings.syncServerUrl.value
        try {
            val revision = api.upload(server, access!!, remote.revision, local.json, local.contentHash)
            markSynced(p, revision, local.contentHash, "已同步到云端（版本 $revision）")
        } catch (e: ApiException) {
            if (e.status != 409) throw e
            mergeWithRemote(p)
        }
    }

    /** The download-only case: the local side has no unsynced changes, so the remote body is applied. */
    private suspend fun download(p: Profile) {
        val snapshot = api.download(p.serverUrl ?: settings.syncServerUrl.value, access!!)
            ?: throw ApiException("云端还没有快照")
        // A replace would re-stamp every row; a merge with the local copy is the same data here
        // and keeps the remote versions intact for the next real merge.
        val result = mergeWriter.write(snapshot.text)
        if (result.snapshot.contentHash == snapshot.contentHash) {
            markSynced(p, snapshot.revision, snapshot.contentHash, mergeMessage(result.adjustments))
        } else {
            upload(p, RemoteMeta(revision = snapshot.revision, contentHash = snapshot.contentHash), result.snapshot)
        }
    }

    /**
     * Reads the whole remote body (not just the metadata), merges it into the local database and
     * uploads the result. The download's own X-Revision/X-Content-Hash are the If-Match base and
     * the "nothing to upload" test — the metadata read that preceded it may already be stale.
     * A 409 means another device uploaded in between: read the new body and merge again, bounded
     * so a permanently racing client cannot spin forever.
     */
    private suspend fun mergeWithRemote(p: Profile, depth: Int = 0) {
        val server = p.serverUrl ?: settings.syncServerUrl.value
        var lastConflict: ApiException? = null
        repeat(3) {
            val remote = api.download(server, access!!)
            if (remote == null) {
                if (depth >= 3) throw ApiException("云端状态变化太频繁，请稍后再同步", 409)
                upload(p, RemoteMeta(), backup.exportForSync())
                return
            }
            val result = mergeWriter.write(remote.text)
            if (result.snapshot.contentHash == remote.contentHash) {
                markSynced(p, remote.revision, remote.contentHash, mergeMessage(result.adjustments))
                return
            }
            try {
                val revision = api.upload(server, access!!, remote.revision, result.snapshot.json, result.snapshot.contentHash)
                markSynced(p, revision, result.snapshot.contentHash, mergeMessage(result.adjustments, uploaded = true))
                return
            } catch (e: ApiException) {
                if (e.status != 409) throw e
                lastConflict = e
            }
        }
        if (depth < 3) {
            mergeWithRemote(p, depth + 1)
            return
        }
        throw lastConflict ?: ApiException("云端更新太频繁，请稍后再同步", 409)
    }

    private fun mergeMessage(adjustments: Int, uploaded: Boolean = false): String = when {
        adjustments > 0 && uploaded -> "已合并并同步 · 调整 $adjustments 处"
        adjustments > 0 -> "已从云端合并 · 调整 $adjustments 处"
        uploaded -> "已合并并同步"
        else -> "已从云端同步"
    }

    /** Loads the retained versions for the history sheet. */
    suspend fun loadRevisions() = mutex.withLock {
        val p = currentProfile()
        if (p.accountId == null) return@withLock
        // History shares token rotation with sync, logout and password changes. Refresh tokens
        // are single-use; overlapping these operations would revoke this device's session.
        _state.update { it.copy(historyBusy = true, error = null) }
        try {
            ensureAccess(p)
            val list = api.revisions(p.serverUrl ?: settings.syncServerUrl.value, access!!)
            _state.update { it.copy(historyBusy = false, revisions = list) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handle(e)
        } finally {
            _state.update { it.copy(historyBusy = false) }
        }
    }

    /**
     * Restores one retained version. The restore is a replace (fresh stamps + tombstones for
     * rows it no longer has), so the intent reaches the cloud; a sync is started right after the
     * lock is released to make that happen.
     */
    suspend fun restoreRevision(revision: Long) {
        if (!mutex.tryLock()) {
            _state.update { it.copy(message = "正在同步") }
            return
        }
        var restored = false
        try {
            _state.update { it.copy(busy = true, historyBusy = true, error = null) }
            val p = currentProfile()
            if (p.accountId == null) {
                expire()
                return
            }
            ensureAccess(p)
            val snapshot = api.revision(p.serverUrl ?: settings.syncServerUrl.value, access!!, revision)
                ?: throw ApiException("这个历史版本已经无法找回")
            backup.restoreFromJson(snapshot.text)
            restored = true
            _state.update { it.copy(busy = false, historyBusy = false, revisions = emptyList(), message = "已恢复云端版本 $revision，正在同步…") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(historyBusy = false) }
            handle(e)
        } finally {
            _state.update { if (it.busy) it.copy(busy = false) else it }
            mutex.unlock()
        }
        if (restored) scope.launch { runCatching { syncNow() } }
    }

    /** On launch, schedule the quiet syncs and sync once after the screens have settled. */
    fun onAppStart() {
        scheduleBackground()
        startForegroundTicker()
        val p = currentProfile()
        if (p.accountId == null || !session.has()) return
        scope.launch {
            delay(6_000)
            runCatching { syncNow() }
        }
    }

    /** Back in the foreground: sync if the last attempt is more than half a minute old. */
    fun onForeground() {
        foreground = true
        startForegroundTicker()
        val p = currentProfile()
        if (p.accountId == null || !session.has()) return
        scope.launch {
            val last = p.lastSyncAt ?: 0L
            if (AppClock.millis() - last > FOREGROUND_SYNC_MIN_GAP_MS) runCatching { syncNow() }
        }
    }

    fun onBackground() {
        foreground = false
    }

    /**
     * An edit just committed: upload it after ten quiet seconds, so a burst of typing or a batch
     * of toggles is one snapshot instead of twenty.
     */
    fun noteLocalChange() {
        if (!isSignedIn()) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(LOCAL_CHANGE_DELAY_MS)
            // The timer may be cancelled by the next edit; an already-started sync must not be.
            scope.launch { runCatching { performSync(fromUser = false, waitForRunning = true) } }
        }
    }

    /** Applies the chosen interval to WorkManager (15 minutes is its floor; 5 is foreground only). */
    fun scheduleBackground() {
        val minutes = settings.syncIntervalMinutes.value
        val work = WorkManager.getInstance(context)
        if (minutes < 15) {
            work.cancelUniqueWork(BACKGROUND_SYNC)
            return
        }
        work.enqueueUniquePeriodicWork(
            BACKGROUND_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    private fun startForegroundTicker() {
        if (tickerStarted) return
        tickerStarted = true
        scope.launch {
            while (isActive) {
                delay(FOREGROUND_TICK_MS)
                if (!foreground) continue
                val minutes = settings.syncIntervalMinutes.value
                if (minutes <= 0) continue
                val p = currentProfile()
                if (p.accountId == null || !session.has()) continue
                val last = p.lastSyncAt ?: 0L
                if (AppClock.millis() - last < minutes * 60_000L) continue
                runCatching { syncNow() }
            }
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
                revision = revision, lastSyncAt = updated.lastSyncAt, message = message, error = null,
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

    /** Connectivity and server-side failures pass; an auth problem or a bad request does not. */
    private fun transient(e: Exception): Boolean =
        e is ApiException && (e.code == "network" || e.status >= 500)

    /** The refresh token no longer works: sign out locally but keep the data. */
    private fun expire() {
        session.clear()
        access = null
        accessUntil = 0
        val p = currentProfile()
        if (p.accountId != null) profiles.save(p.copy(accountId = null, lastAccountId = p.accountId))
        _state.update {
            it.copy(signedIn = false, busy = false, loginBusy = false, error = "登录已过期，请重新登录")
        }
    }

    private companion object {
        /** How long a successful probe stands before the next login asks the network again. */
        const val CHECK_CACHE_MS = 15_000L
        /** A failed probe is not reused for long: the user may have just changed networks. */
        const val CHECK_FAILURE_CACHE_MS = 3_000L
        /** An edit waits this long for the next one before it is uploaded. */
        const val LOCAL_CHANGE_DELAY_MS = 10_000L
        /** Coming back to the foreground does not sync more often than this. */
        const val FOREGROUND_SYNC_MIN_GAP_MS = 30_000L
        /** How often the foreground ticker looks at the configured interval. */
        const val FOREGROUND_TICK_MS = 30_000L
        const val BACKGROUND_SYNC = "periodic-sync"
    }
}
