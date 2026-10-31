package com.assetboard.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.assetboard.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProfileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sessions", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("asset-board.db", Profile.LOCAL_DB, "asset-board-p-a.db")) {
            for (suffix in listOf("", "-wal", "-shm", ".migrating", ".migrating-wal", ".migrating-shm")) {
                File(context.getDatabasePath(name).path + suffix).delete()
            }
        }
    }

    @Test fun movesTheLegacyDatabaseToTheLocalProfile() {
        val legacy = context.getDatabasePath("asset-board.db")
        legacy.parentFile?.mkdirs()
        context.openOrCreateDatabase("asset-board.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE marker (value TEXT)")
            db.execSQL("INSERT INTO marker VALUES ('legacy rows')")
        }

        val profile = ProfileStore(context).active()

        assertEquals(Profile.LOCAL_ID, profile.id)
        assertEquals(Profile.LOCAL_DB, profile.dbFile)
        val local = context.getDatabasePath(Profile.LOCAL_DB)
        SQLiteDatabase.openDatabase(local.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM marker", null).use { cursor ->
                assertTrue("the copied database must be readable", cursor.moveToFirst())
                assertEquals("legacy rows", cursor.getString(0))
            }
        }
        assertFalse("the old name must be gone", legacy.exists())
    }

    @Test fun aFreshInstallStartsWithTheLocalProfile() {
        val profile = ProfileStore(context).active()
        assertEquals(Profile.LOCAL_ID, profile.id)
        assertEquals(Profile.LOCAL_DB, profile.dbFile)
        assertNull(profile.accountId)
    }

    @Test fun profilesCanBeSavedFoundAndRebound() {
        val store = ProfileStore(context)
        val local = store.active()
        val account = Profile(
            id = "p-a",
            dbFile = "asset-board-p-a.db",
            accountId = "a",
            lastAccountId = "a",
            username = "alice",
            serverUrl = "https://example.com",
            createdAt = 10,
        )
        store.save(account)
        store.setActive(account.id)

        assertEquals(account.id, store.active().id)
        assertEquals(account.id, store.findForAccount("a")?.id)

        // After logout the account id is cleared, but the database can still be found again.
        store.save(account.copy(accountId = null))
        assertEquals(account.id, store.findForAccount("a")?.id)
        assertNull(store.findForAccount("b"))
        assertEquals(local.id, store.byId(local.id)?.id)
    }

    @Test fun profilesPointingAtTheMovedServerFollowTheNewDefault() {
        val store = ProfileStore(context)
        store.save(
            Profile(
                id = "p-moved",
                dbFile = "asset-board-p-moved.db",
                username = "konghw",
                serverUrl = LEGACY_SYNC_SERVER_URL,
                createdAt = 20,
            ),
        )
        store.save(
            Profile(
                id = "p-other",
                dbFile = "asset-board-p-other.db",
                username = "alice",
                serverUrl = "https://example.com",
                createdAt = 21,
            ),
        )

        assertEquals(BuildConfig.SYNC_BASE_URL, ProfileStore(context).byId("p-moved")?.serverUrl)
        assertEquals("https://example.com", ProfileStore(context).byId("p-other")?.serverUrl)
    }

    @Test fun epochsOnlyMoveForward() {
        val store = ProfileStore(context)
        val first = store.nextProfileEpoch()
        val second = store.nextProfileEpoch()
        assertTrue("$second should be after $first", second > first)

        store.raiseProfileEpoch(second + 1_000_000)
        val third = store.nextProfileEpoch()
        assertTrue("$third should be after the raised counter", third > second + 1_000_000)
    }
}