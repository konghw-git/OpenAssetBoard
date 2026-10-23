package com.assetboard.data

import android.app.Application
import com.assetboard.domain.health.Energy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What was eaten: kept as typed, any number a day, edits keep their time, a delete can be undone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FoodRepositoryTest {
    private val f = Fixture()

    @After fun tearDown() = f.close()

    private fun label(date: String, id: String? = null, name: String? = "燕麦奶", amount: Int = 500, energyCal: Long = 40_870) =
        FoodDraft(id, date(date), name, 100, amount, FoodUnit.ML, energyCal, inKj = true, proteinMg = 600, fatMg = 1_600, carbMg = 6_000, sodiumMg = 40)

    private fun portion(date: String, energyCal: Long = 650_000) =
        FoodDraft(null, date(date), "午饭", null, null, null, energyCal, inKj = false, proteinMg = null, fatMg = null, carbMg = null, sodiumMg = null)

    @Test fun aLabelIsKeptAsTyped() = f.run {
        val id = f.food.save(label("2026-10-20"))
        val e = f.db.foodDao().entry(id)!!
        assertEquals(100, e.per)
        assertEquals(500, e.amount)
        assertEquals(40_870L, e.energyCal)
        assertEquals(f.now, e.eatenAt)
        assertEquals(204_350L, Energy.energyOf(e))
    }

    @Test fun aPortionAndManyADay() = f.run {
        f.food.save(label("2026-10-20"))
        f.food.save(portion("2026-10-20"))
        f.food.save(label("2026-10-20", name = "燕麦奶"))
        assertEquals(3, f.food.onDate(date("2026-10-20")).size)
        assertEquals(650_000L, Energy.energyOf(f.food.onDate(date("2026-10-20")).single { it.name == "午饭" }))
    }

    @Test fun namesAreTrimmedAndBlankIsNone() = f.run {
        val a = f.food.save(label("2026-10-20", name = "  牛奶 "))
        val b = f.food.save(label("2026-10-20", name = "   "))
        assertEquals("牛奶", f.db.foodDao().entry(a)!!.name)
        assertNull(f.db.foodDao().entry(b)!!.name)
    }

    @Test fun editingKeepsItsTimeUnlessTheDayMoves() = f.run {
        val id = f.food.save(label("2026-10-20"))
        val first = f.db.foodDao().entry(id)!!
        f.setNow("2026-10-20", "18:00")
        f.food.save(label("2026-10-20", id = id, amount = 250))
        val edited = f.db.foodDao().entry(id)!!
        assertEquals(250, edited.amount)
        assertEquals(first.eatenAt, edited.eatenAt)
        assertEquals(first.createdAt, edited.createdAt)
        assertEquals(f.now, edited.updatedAt)
        f.food.save(label("2026-10-18", id = id))
        assertEquals(f.at("2026-10-18", "23:59:59"), f.db.foodDao().entry(id)!!.eatenAt)
        assertTrue(f.food.onDate(date("2026-10-20")).isEmpty())
    }

    @Test fun backFilledDaysSitAtTheEndOfThatDay() = f.run {
        val id = f.food.save(portion("2026-10-15"))
        assertEquals(f.at("2026-10-15", "23:59:59"), f.db.foodDao().entry(id)!!.eatenAt)
    }

    @Test fun rulesOnTheNumbers() = f.run {
        expectRule { f.food.save(label("2026-10-21")) } // the future
        expectRule { f.food.save(label("2026-10-20").copy(amount = null)) }
        expectRule { f.food.save(label("2026-10-20").copy(unit = null)) }
        expectRule { f.food.save(label("2026-10-20").copy(per = 0)) }
        expectRule { f.food.save(label("2026-10-20", amount = Energy.AMOUNT_MAX + 1)) }
        // 900 kcal per 100 g, eaten by the 100 kg: far past any meal.
        expectRule { f.food.save(label("2026-10-20", amount = Energy.AMOUNT_MAX, energyCal = 900_000)) }
        expectRule { f.food.save(portion("2026-10-20", energyCal = Energy.ENERGY_MAX_CAL + 1)) }
        expectRule { f.food.save(label("2026-10-20", name = "长".repeat(Energy.NAME_MAX + 1))) }
        expectRule { f.food.save(label("2026-10-20", id = "gone")) }
        assertTrue(f.db.foodDao().all().isEmpty())
    }

    @Test fun portionsAreCounted() = f.run {
        val eggs = portion("2026-10-20", energyCal = 70_000).copy(per = Energy.SERVING_PER, amount = 4, unit = FoodUnit.SERVING)
        val id = f.food.save(eggs)
        assertEquals(140_000L, Energy.energyOf(f.db.foodDao().entry(id)!!))
        expectRule { f.food.save(eggs.copy(amount = Energy.SERVINGS_MAX_HALVES + 1)) }
        expectRule { f.food.save(eggs.copy(amount = 0)) }
        // Portions are halves; any other count is not a portion.
        expectRule { f.food.save(eggs.copy(per = 1)) }
        expectRule { f.food.save(eggs.copy(unit = null)) }
    }

    @Test fun oneMoreCountsAnotherPortionInPlaceAndPutBackUndoesIt() = f.run {
        // A portion never counted becomes two of it.
        val lunch = f.food.save(portion("2026-10-20", energyCal = 650_000))
        val before = f.food.oneMore(lunch)
        val two = f.db.foodDao().entry(lunch)!!
        assertEquals(FoodUnit.SERVING, two.unit)
        assertEquals(2 * Energy.SERVING_PER, Energy.servingHalves(two))
        assertEquals(1_300_000L, Energy.energyOf(two))
        // A counted one takes one more, and its nutrients scale with it.
        val eggs = f.food.save(portion("2026-10-20", energyCal = 70_000).copy(name = "水煮蛋", per = Energy.SERVING_PER, amount = 4, unit = FoodUnit.SERVING, proteinMg = 6_000))
        f.food.oneMore(eggs)
        val three = f.db.foodDao().entry(eggs)!!
        assertEquals("3", Energy.servings(Energy.servingHalves(three)))
        assertEquals(210_000L, Energy.energyOf(three))
        assertEquals(18_000L, Energy.proteinOf(three))
        assertEquals(2, f.food.onDate(date("2026-10-20")).size)

        f.food.putBack(before)
        assertEquals(before, f.db.foodDao().entry(lunch))
        // A label's portion is its amount, not a count: it is recorded again instead.
        val milk = f.food.save(label("2026-10-20"))
        expectRule { f.food.oneMore(milk) }
    }

    @Test fun deleteHandsBackWhatAnUndoPutsBack() = f.run {
        val id = f.food.save(label("2026-10-20"))
        val before = f.db.foodDao().entry(id)!!
        val gone = f.food.delete(id)!!
        assertNull(f.db.foodDao().entry(id))
        assertNull(f.food.delete(id))
        f.food.restore(gone)
        f.food.restore(gone)
        assertEquals(listOf(before), f.db.foodDao().all())
    }
}
