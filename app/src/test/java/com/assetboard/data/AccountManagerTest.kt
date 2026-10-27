package com.assetboard.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import java.io.File
import kotlinx.coroutines.runBlocking
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

        override suspend fun login(serverUrl: String, username: String, password: String) =
            ServerSession("access", "refresh", 900, user)

        override suspend fun refresh(serverUrl: String, refreshToken: String) =
            ServerSession("access2", "refresh2", 900, user)

        override suspend fun logout(serverUrl: String, refreshToken: String) {
            logouts++
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