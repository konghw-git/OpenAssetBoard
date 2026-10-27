package com.assetboard.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeGateway : SyncGateway {
        var user = RemoteUser("a", "alice")
        var meta = RemoteMeta()
        var downloadText: String? = null
        val uploads = mutableListOf<Pair<Long, String>>()
        var logouts = 0
        var probeError: Exception? = null
        var probeCount = 0
        var loginError: Exception? = null
        private val loginCount = AtomicInteger()
        val loginCalls: Int get() = loginCount.get()
        /** A blocking first login, like an HttpURLConnection read that ignores cancellation. */
        var slowFirstLogin = false
        var loginGate: CompletableDeferred<Unit>? = null
        var changePasswordError: Exception? = null
        var changePasswordSession = ServerSession("new-access", "new-refresh", 900, RemoteUser("a", "alice"))
        val passwordChanges = mutableListOf<Triple<String, String, String>>()

        override suspend fun probe(serverUrl: String): Long {
            probeCount++
            probeError?.let { throw it }
            return 32
        }

        override suspend fun login(serverUrl: String, username: String, password: String): ServerSession {
            val call = loginCount.incrementAndGet()
            if (slowFirstLogin && call == 1) Thread.sleep(400)
            loginGate?.await()
            loginError?.let { throw it }
            return ServerSession("access-$call", "refresh-$call", 900, user)
        }

        override suspend fun refresh(serverUrl: String, refreshToken: String) =
            ServerSession("access2", "refresh2", 900, user)

        override suspend fun logout(serverUrl: String, refreshToken: String) {
            logouts++
        }

        override suspend fun changePassword(
            serverUrl: String,
            access: String,
            oldPassword: String,
            newPassword: String,
        ): ServerSession {
            passwordChanges += Triple(serverUrl, oldPassword, newPassword)
            changePasswordError?.let { throw it }
            return changePasswordSession
        }

        override suspend fun meta(serverUrl: String, access: String) = meta

        override suspend fun download(serverUrl: String, access: String) = downloadText

        override suspend fun upload(
            serverUrl: String,
            access: String,
            baseRevision: Long,
            snapshotJson: String,
            contentHash: String,
        ): Long {
            uploads += baseRevision to contentHash
            return baseRevision + 1
        }
    }

    private fun snapshotBody(): String =
        """{"formatVersion":14,"schemaVersion":13,"exportedAt":1,"groups":[{"id":"g1","name":"现金"}],""" +
            """"accounts":[],"snapshots":[],"valuations":[],"snapshotItems":[],"flows":[]}"""

    @Before
    fun clear() {
        context.getSharedPreferences("profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sessions", Context.MODE_PRIVATE).edit().clear().commit()
        for (id in listOf(Profile.LOCAL_ID, "p-a", "p-b")) SessionStore(context, id).clear()
        for (name in listOf("asset-board.db", Profile.LOCAL_DB, "asset-board-p-a.db", "asset-board-p-b.db")) {
            for (suffix in listOf("", "-wal", "-shm")) File(context.getDatabasePath(name).path + suffix).delete()
        }
    }

    private fun accountProfile(id: String = "p-a", account: String = "a", username: String = "alice") = Profile(
        id = id,
        dbFile = "asset-board-$id.db",
        accountId = account,
        lastAccountId = account,
        username = username,
        serverUrl = "https://example.com",
        createdAt = 1,
    )

    @Test
    fun loginBindsTheCurrentProfileAndUploadsIt() = runBlocking {
        val store = ProfileStore(context)
        val local = store.active()
        val fake = FakeGateway()
        var switched: Profile? = null
        val container = AppContainer(context, local, local.dbFile, store, { switched = it }, fake)
        try {
            container.account.login("https://example.com", "alice", "password")

            val saved = store.byId(local.id)!!
            assertEquals("a", saved.accountId)
            assertEquals("alice", saved.username)
            assertEquals("https://example.com", saved.serverUrl)
            assertEquals(1L, saved.lastRevision)
            assertTrue(container.account.state.value.signedIn)
            assertNull("the first account adopts the local database", switched)
            assertEquals(1, fake.uploads.size)
            assertEquals(0L, fake.uploads.single().first)
        } finally {
            container.close()
        }
    }

    @Test
    fun loggingIntoAnotherAccountCreatesItsOwnDatabase() = runBlocking {
        val store = ProfileStore(context)
        val a = store.save(accountProfile())
        store.setActive(a.id)
        val fake = FakeGateway().apply { user = RemoteUser("b", "bob") }
        var switched: Profile? = null
        val container = AppContainer(context, a, a.dbFile, store, { switched = it }, fake)
        try {
            container.account.login("https://example.com", "bob", "password")

            assertEquals("b", switched?.accountId)
            assertEquals("asset-board-p-b.db", switched?.dbFile)
            assertNotNull(store.findForAccount("b"))
            assertEquals("bob", store.findForAccount("b")?.username)
            assertTrue(SessionStore(context, "p-b").has())
        } finally {
            container.close()
        }
    }

    @Test
    fun logoutKeepsTheDatabaseAndRemembersTheAccount() = runBlocking {
        val store = ProfileStore(context)
        val local = store.active()
        val fake = FakeGateway()
        val container = AppContainer(context, local, local.dbFile, store, {}, fake)
        try {
            container.account.login("https://example.com", "alice", "password")
            container.account.logout()

            val saved = store.byId(local.id)!!
            assertNull(saved.accountId)
            assertEquals("a", saved.lastAccountId)
            assertEquals("alice", saved.username)
            assertFalse(SessionStore(context, local.id).has())
            assertFalse(container.account.state.value.signedIn)
            assertEquals(1, fake.logouts)
        } finally {
            container.close()
        }
    }

    @Test
    fun anEmptyProfileDownloadsTheRemoteSnapshot() = runBlocking {
        val store = ProfileStore(context)
        val profile = store.save(accountProfile())
        store.setActive(profile.id)
        val fake = FakeGateway().apply {
            meta = RemoteMeta(revision = 1, contentHash = "cloud", formatVersion = 14, schemaVersion = 13, size = 100)
            downloadText = snapshotBody()
        }
        val container = AppContainer(context, profile, profile.dbFile, store, {}, fake)
        try {
            container.account.login("https://example.com", "alice", "password")

            assertEquals(1, container.db.dao().groups().size)
            assertEquals("现金", container.db.dao().groups().single().name)
            val saved = store.byId(profile.id)!!
            assertEquals(1L, saved.lastRevision)
            assertEquals("cloud", saved.lastSyncedHash)
            assertTrue(fake.uploads.isEmpty())
        } finally {
            container.close()
        }
    }

    @Test
    fun anUnreachableServerIsReportedBeforeTheCredentialsAreSent() = runBlocking {
        val store = ProfileStore(context)
        val local = store.active()
        val fake = FakeGateway().apply {
            probeError = ApiException("连接服务器超时，请更换网络（Wi‑Fi 或移动数据）后重试", code = "network")
        }
        val container = AppContainer(context, local, local.dbFile, store, {}, fake)
        try {
            container.account.login("https://example.com", "alice", "password")

            val state = container.account.state.value
            assertEquals(0, fake.loginCalls)
            assertEquals(1, fake.probeCount)
            assertFalse(state.signedIn)
            assertFalse(state.loginBusy)
            assertEquals(false, state.check.ok)
            assertTrue(state.error!!.contains("更换网络"))
        } finally {
            container.close()
        }
    }

    @Test
    fun leavingTheLoginPageCancelsTheWaitingAttempt() = runBlocking {
        val store = ProfileStore(context)
        val local = store.active()
        val fake = FakeGateway().apply { loginGate = CompletableDeferred() }
        val container = AppContainer(context, local, local.dbFile, store, {}, fake)
        try {
            val job = launch { container.account.login("https://example.com", "alice", "password") }
            withTimeout(5_000) {
                while (fake.loginCalls == 0) delay(10)
            }
            assertTrue(container.account.state.value.loginBusy)

            container.account.cancelLogin()
            job.join()

            val state = container.account.state.value
            assertFalse("coming back must show the form, not a stale spinner", state.loginBusy)
            assertFalse(state.signedIn)
            assertNull(state.error)
        } finally {
            container.close()
        }
    }

    @Test
    fun aCancelledAttemptCannotOverwriteTheNextOne() = runBlocking {
        val store = ProfileStore(context)
        val local = store.active()
        val fake = FakeGateway().apply { slowFirstLogin = true }
        val container = AppContainer(context, local, local.dbFile, store, {}, fake)
        try {
            // The first request is still in a blocking read when the user cancels and retries.
            val first = launch(Dispatchers.IO) {
                container.account.login("https://example.com", "alice", "password")
            }
            withTimeout(5_000) { while (fake.loginCalls == 0) delay(10) }
            container.account.cancelLogin()

            val second = launch(Dispatchers.IO) {
                container.account.login("https://example.com", "alice", "password")
            }
            withTimeout(5_000) { while (!container.account.state.value.signedIn) delay(10) }
            second.join()
            first.join()

            // The first answer came back after cancel and must not replace the second session.
            assertEquals("refresh-2", SessionStore(context, local.id).load())
            assertTrue(container.account.state.value.signedIn)
        } finally {
            container.close()
        }
    }

    @Test
    fun aWrongPasswordEndsTheAttemptInsteadOfLeavingItBusy() = runBlocking {
        val store = ProfileStore(context)
        val local = store.active()
        val fake = FakeGateway().apply {
            loginError = ApiException("invalid credentials", status = 401, code = "invalid_credentials")
        }
        val container = AppContainer(context, local, local.dbFile, store, {}, fake)
        try {
            container.account.login("https://example.com", "alice", "wrong-password")

            val state = container.account.state.value
            assertFalse(state.loginBusy)
            assertFalse(state.signedIn)
            assertEquals("用户名或密码错误", state.error)
        } finally {
            container.close()
        }
    }

    @Test
    fun changingThePasswordSavesTheNewSessionAndStaysSignedIn() = runBlocking {
        val store = ProfileStore(context)
        val local = store.active()
        val fake = FakeGateway()
        val container = AppContainer(context, local, local.dbFile, store, {}, fake)
        try {
            container.account.login("https://example.com", "alice", "password")

            val failure = container.account.changePassword("old-password", "new-password-456")

            assertNull(failure)
            assertEquals(
                Triple("https://example.com", "old-password", "new-password-456"),
                fake.passwordChanges.single(),
            )
            assertEquals("new-refresh", SessionStore(context, local.id).load())
            assertTrue(container.account.state.value.signedIn)
            assertEquals("密码已修改，其他设备需要重新登录", container.account.state.value.message)
        } finally {
            container.close()
        }
    }

    @Test
    fun aWrongOldPasswordKeepsTheUserSignedIn() = runBlocking {
        val store = ProfileStore(context)
        val local = store.active()
        val fake = FakeGateway().apply {
            changePasswordError = ApiException("旧密码不正确", status = 401, code = "invalid_old_password")
        }
        val container = AppContainer(context, local, local.dbFile, store, {}, fake)
        try {
            container.account.login("https://example.com", "alice", "password")

            val failure = container.account.changePassword("not-the-old-one", "new-password-456")

            assertEquals("旧密码不正确", failure)
            assertTrue(container.account.state.value.signedIn)
            assertFalse(container.account.state.value.busy)
            assertNull(container.account.state.value.error)
        } finally {
            container.close()
        }
    }

    @Test
    fun aConflictCanKeepTheLocalCopy() = runBlocking {
        val store = ProfileStore(context)
        val profile = store.save(
            accountProfile().copy(lastRevision = 1, lastSyncedHash = "old"),
        )
        store.setActive(profile.id)
        val fake = FakeGateway().apply {
            meta = RemoteMeta(revision = 2, contentHash = "new", formatVersion = 14, schemaVersion = 13, size = 100)
        }
        val container = AppContainer(context, profile, profile.dbFile, store, {}, fake)
        try {
            runBlocking(container.gate.bound()) { container.repository.seedDemo() }
            container.account.login("https://example.com", "alice", "password")
            assertNotNull("both sides changed, so the user must choose", container.account.state.value.conflict)

            container.account.resolveConflict(ConflictChoice.KEEP_LOCAL)

            assertEquals(2L, fake.uploads.last().first)
            val saved = store.byId(profile.id)!!
            assertEquals(3L, saved.lastRevision)
            assertEquals(container.backup.exportForSync().contentHash, saved.lastSyncedHash)
            assertNull(container.account.state.value.conflict)
        } finally {
            container.close()
        }
    }
}