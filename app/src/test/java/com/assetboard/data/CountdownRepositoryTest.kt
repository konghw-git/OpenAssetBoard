package com.assetboard.data

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CountdownRepositoryTest {
    private val f = Fixture()
    private var changes = 0
    private val repo = CountdownRepository(f.db, f.gate, clock = { f.now }) { changes++ }

    @After fun tearDown() = f.close()

    private fun date(s: String) = LocalDate.parse(s)

    @Test fun savesEditsAndReportsEachWrite() = f.run {
        val id = repo.save(null, "  考研\n初试 ", date("2026-12-26"), yearly = false, pinned = false)
        assertEquals("考研 初试", repo.get(id)!!.title)
        repo.save(id, "考研", date("2026-12-27"), yearly = false, pinned = true)
        val c = repo.get(id)!!
        assertEquals("2026-12-27", c.date)
        assertTrue(c.pinned)
        assertEquals(2, changes)
    }

    @Test fun onlyOneIsPinned() = f.run {
        val a = repo.save(null, "A", date("2026-11-01"), yearly = false, pinned = true)
        val b = repo.save(null, "B", date("2026-11-02"), yearly = false, pinned = true)
        assertFalse(repo.get(a)!!.pinned)
        assertTrue(repo.get(b)!!.pinned)
        repo.setPinned(a, true)
        assertEquals(listOf(a), repo.all().filter { it.pinned }.map { it.id })
    }

    @Test fun aBlankOrLongTitleIsRefused() = f.run {
        expectRule { repo.save(null, "  ", date("2026-11-01"), yearly = false, pinned = false) }
        expectRule { repo.save(null, "长".repeat(41), date("2026-11-01"), yearly = false, pinned = false) }
        assertTrue(repo.all().isEmpty())
    }

    @Test fun deleteHandsTheRowBackForUndo() = f.run {
        val id = repo.save(null, "生日", date("2001-08-02"), yearly = true, pinned = true)
        val removed = repo.delete(id)!!
        assertNull(repo.get(id))
        assertNull(repo.delete(id))
        repo.restore(removed)
        assertEquals(removed, repo.get(id))
    }

    @Test fun anUndoneDeleteLosesThePinAnotherTookMeanwhile() = f.run {
        val id = repo.save(null, "A", date("2026-11-01"), yearly = false, pinned = true)
        val removed = repo.delete(id)!!
        repo.save(null, "B", date("2026-11-02"), yearly = false, pinned = true)
        repo.restore(removed)
        assertFalse(repo.get(id)!!.pinned)
        assertEquals(1, repo.all().count { it.pinned })
    }

    @Test fun theyAreBackedUpAndRestored() = f.run {
        f.settings.setBackupTreeUri(null)
        File(f.context.filesDir, "prerestore").deleteRecursively()
        repo.save(null, "生日", date("2001-08-02"), yearly = true, pinned = true)
        val json = f.backup.exportJson()
        repo.save(null, "之后加的", date("2026-11-01"), yearly = false, pinned = false)
        val file = File(f.context.cacheDir, "countdowns.json").apply { writeText(json) }
        f.backup.restore(android.net.Uri.fromFile(file))
        assertEquals(listOf("生日"), repo.all().map { it.title })
    }
}
