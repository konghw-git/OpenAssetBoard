package com.assetboard.data

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One record a day, overwritten only on confirmation, and a profile that never refuses a young birthday. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HealthRepositoryTest {
    private val f = Fixture()

    @After fun tearDown() = f.close()

    private fun draft(date: String, g: Long, fat: Int? = null, id: String? = null) = MeasurementDraft(id, date(date), g, fat, null)

    @Test fun saveAndReadBack() = f.run {
        val id = f.health.save(draft("2026-10-20", 92_100, 2_580))
        val m = f.health.onDate(date("2026-10-20"))!!
        assertEquals(id, m.id)
        assertEquals(92_100L, m.weightG)
        assertEquals(2_580, m.bodyFatBp)
        assertEquals(f.now, m.measuredAt)
    }

    @Test fun weightOnlyIsEnough() = f.run {
        f.health.save(draft("2026-10-20", 92_100))
        assertNull(f.health.onDate(date("2026-10-20"))!!.bodyFatBp)
    }

    @Test fun aSecondRecordOnTheSameDayAsksFirst() = f.run {
        val first = f.health.save(draft("2026-10-20", 92_100))
        try {
            f.health.save(draft("2026-10-20", 91_900))
            fail("expected SameDayExists")
        } catch (e: SameDayExists) {
            assertEquals(date("2026-10-20"), e.date)
        }
        assertEquals(92_100L, f.health.onDate(date("2026-10-20"))!!.weightG)
        // Confirmed: the day keeps its row id, so the unique date index never collides.
        val again = f.health.save(draft("2026-10-20", 91_900, 2_500), overwrite = true)
        assertEquals(first, again)
        assertEquals(1, f.db.healthDao().all().size)
        assertEquals(91_900L, f.db.healthDao().all().single().weightG)
    }

    @Test fun movingAnEditOntoAnOccupiedDay() = f.run {
        val a = f.health.save(draft("2026-10-18", 92_500))
        val b = f.health.save(draft("2026-10-19", 92_300))
        expectRule { f.health.save(draft("2026-10-18", 92_000, id = b)) }
        f.health.save(draft("2026-10-18", 92_000, id = b), overwrite = true)
        val all = f.db.healthDao().all()
        assertEquals(listOf("2026-10-18"), all.map { it.date })
        assertEquals(a, all.single().id)
        assertEquals(92_000L, all.single().weightG)
    }

    @Test fun editingKeepsTheDayAndCanMoveIt() = f.run {
        val id = f.health.save(draft("2026-10-19", 92_300))
        f.health.save(draft("2026-10-19", 92_200, 2_500, id = id))
        assertEquals(92_200L, f.health.onDate(date("2026-10-19"))!!.weightG)
        f.health.save(draft("2026-10-15", 92_200, 2_500, id = id))
        assertNull(f.health.onDate(date("2026-10-19")))
        assertEquals(id, f.health.onDate(date("2026-10-15"))!!.id)
    }

    @Test fun backFilledDaysSitAtTheEndOfThatDay() = f.run {
        f.health.save(draft("2026-10-15", 92_500))
        assertEquals(f.at("2026-10-15", "23:59:59"), f.health.onDate(date("2026-10-15"))!!.measuredAt)
    }

    @Test fun rulesOnTheNumbers() = f.run {
        expectRule { f.health.save(draft("2026-10-21", 92_000)) } // the future
        expectRule { f.health.save(draft("2026-10-20", 19_999)) }
        expectRule { f.health.save(draft("2026-10-20", 300_001)) }
        expectRule { f.health.save(draft("2026-10-20", 92_000, 299)) }
        expectRule { f.health.save(draft("2026-10-20", 92_000, 7_001)) }
        assertTrue(f.db.healthDao().all().isEmpty())
    }

    @Test fun deleteLeavesTheDayEmpty() = f.run {
        val id = f.health.save(draft("2026-10-20", 92_100))
        f.health.delete(id)
        assertNull(f.health.onDate(date("2026-10-20")))
    }

    @Test fun profileIsOneRowAndAcceptsAYoungBirthday() = f.run {
        f.health.saveProfile(Sex.MALE, date("2001-08-02"), 1820, null)
        f.health.saveProfile(Sex.MALE, date("2015-05-05"), 1500, 45_000)
        val all = f.db.healthDao().allProfiles()
        assertEquals(1, all.size)
        assertEquals("2015-05-05", all.single().birthDate)
        expectRule { f.health.saveProfile(Sex.MALE, date("2026-10-21"), 1820, null) }
        expectRule { f.health.saveProfile(Sex.MALE, date("2001-08-02"), 900, null) }
        expectRule { f.health.saveProfile(Sex.MALE, date("2001-08-02"), 1820, 10_000) }
    }
}
