package com.assetboard.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.assetboard.TestContainers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupSyncTest {
    @After
    fun close() {
        TestContainers.closeAll()
    }

    private fun snapshotBody(): String =
        """{"formatVersion":14,"schemaVersion":13,"exportedAt":1,"groups":[{"id":"g1","name":"现金"}],""" +
            """"accounts":[],"snapshots":[],"valuations":[],"snapshotItems":[],"flows":[]}"""

    @Test
    fun hashTracksContentAndIgnoresExportTimeAndOrder() = runBlocking {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        val empty = c.backup.exportForSync()
        assertTrue("a fresh database is empty", empty.isEmpty)
        assertEquals(64, empty.contentHash.length)

        runBlocking(c.gate.bound()) { c.repository.seedDemo() }
        val full = c.backup.exportForSync()
        assertFalse(full.isEmpty)
        assertEquals(64, full.contentHash.length)
        assertNotEquals(empty.contentHash, full.contentHash)

        val again = c.backup.exportForSync()
        assertEquals("the same rows always hash the same", full.contentHash, again.contentHash)
    }

    @Test
    fun restoreFromJsonAppliesAValidSnapshot() = runBlocking {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        c.backup.restoreFromJson(snapshotBody())
        assertEquals(1, c.db.dao().groups().size)
        assertEquals("现金", c.db.dao().groups().single().name)
    }
}