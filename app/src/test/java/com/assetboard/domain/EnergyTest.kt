package com.assetboard.domain

import com.assetboard.data.BodyMeasurement
import com.assetboard.data.FoodEntry
import com.assetboard.data.FoodUnit
import com.assetboard.data.HealthProfile
import com.assetboard.data.Sex
import com.assetboard.domain.health.Energy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Food energy against a real label (a 500 ml oat drink) and the burn against the S400 profile. */
class EnergyTest {
    private val profile = HealthProfile(sex = Sex.MALE, birthDate = "2001-08-02", heightMm = 1820, updatedAt = 0)
    private val records = listOf(
        BodyMeasurement("a", "2026-10-17", 0, 92_100, 2_580, null, 0, 0),
        BodyMeasurement("b", "2026-10-20", 0, 91_800, null, null, 0, 0),
    )

    private fun entry(
        date: String = "2026-10-20",
        name: String? = "燕麦奶",
        per: Int? = 100,
        amount: Int? = 500,
        energyCal: Long = 40_870,
        protein: Long? = 600,
        at: Long = 0,
        id: String = name + date + at,
    ) = FoodEntry(
        id = id, date = date, eatenAt = at, name = name, per = per, amount = amount, unit = per?.let { FoodUnit.ML },
        energyCal = energyCal, inKj = true, proteinMg = protein, fatMg = 1_600, carbMg = 6_000, sodiumMg = 40, createdAt = at, updatedAt = at,
    )

    @Test fun theLabelAddsUp() {
        // 每 100 ml: 171 kJ, 蛋白质 0.6 g, 脂肪 1.6 g, 碳水 6.0 g, 钠 40 mg; drank 500 ml.
        val cal = Energy.parseEnergy("171", kj = true)!!
        assertEquals(40_870L, cal)
        assertEquals("40.9", Energy.energyInput(cal, kj = false))
        assertEquals("171", Energy.energyInput(cal, kj = true))
        val e = entry(energyCal = cal, protein = Energy.parseGrams("0.6"))
        assertEquals(204_350L, Energy.energyOf(e))
        assertEquals("204", Energy.kcal(Energy.energyOf(e)))
        assertEquals("3", Energy.grams(Energy.proteinOf(e)!!))
        assertEquals("8", Energy.grams(Energy.fatOf(e)!!))
        assertEquals("30", Energy.grams(Energy.carbOf(e)!!))
        assertEquals(200L, Energy.sodiumOf(e))
        // The nutrients say the same as the energy: 0.6·4 + 1.6·9 + 6·4 = 40.8 kcal.
        assertEquals(40_800L, Energy.atwaterCal(600, 1_600, 6_000))
        assertFalse(Energy.disagrees(cal, 40_800))
    }

    @Test fun kilojoulesTakenForKilocaloriesAreFlagged() {
        assertTrue(Energy.disagrees(Energy.parseEnergy("171", kj = false)!!, 40_800))
        // Small labels round; a few kcal apart is not a disagreement.
        assertFalse(Energy.disagrees(48_000, 40_800))
    }

    @Test fun aPortionWithoutALabelIsTakenAsIs() {
        val e = entry(per = null, amount = null, energyCal = 650_000)
        assertEquals(650_000L, Energy.energyOf(e))
        assertEquals(600L, Energy.proteinOf(e))
    }

    @Test fun portionsAreCountedInHalves() {
        // 水煮蛋: 70 kcal and 6.5 g of protein each, two of them.
        val eggs = entry(per = Energy.SERVING_PER, amount = 4, energyCal = 70_000, protein = 6_500).copy(unit = FoodUnit.SERVING)
        assertEquals(140_000L, Energy.energyOf(eggs))
        assertEquals(13_000L, Energy.proteinOf(eggs))
        assertEquals(4, Energy.servingHalves(eggs))
        assertEquals("2", Energy.servings(4))
        assertEquals("0.5", Energy.servings(1))
        assertEquals("1.5", Energy.servings(3))
        // Half a bowl of rice is half its kcal.
        assertEquals(116_000L, Energy.energyOf(eggs.copy(amount = 1, energyCal = 232_000)))
        // An uncounted portion and a label are one portion.
        assertEquals(Energy.SERVING_PER, Energy.servingHalves(entry(per = null, amount = null)))
        assertEquals(Energy.SERVING_PER, Energy.servingHalves(entry()))
    }

    @Test fun scalingRoundsHalfUp() {
        assertEquals(1L, Energy.scaled(2, 3, 1))
        assertEquals(0L, Energy.scaled(1, 3, 1))
        assertEquals(12_261L, Energy.scaled(40_870, 100, 30))
    }

    @Test fun parsing() {
        assertEquals(40_900L, Energy.parseEnergy("40.9", kj = false))
        assertNull(Energy.parseEnergy("40.95", kj = false))
        assertNull(Energy.parseEnergy("", kj = true))
        assertEquals(600L, Energy.parseGrams("0.6"))
        assertEquals(650L, Energy.parseGrams("0.65"))
        assertNull(Energy.parseGrams("0.655"))
        assertEquals(40L, Energy.parseMilligrams("40"))
        assertNull(Energy.parseMilligrams("40.5"))
        assertEquals(500, Energy.parseAmount("500"))
        assertNull(Energy.parseAmount("2.5"))
    }

    @Test fun formatting() {
        assertEquals("1,240", Energy.kcal(1_240_000))
        assertEquals("0", Energy.kcal(499))
        assertEquals("1", Energy.kcal(500))
        assertEquals("0.6", Energy.grams(600))
        assertEquals("12.3", Energy.grams(12_345))
        assertEquals("123", Energy.grams(123_456))
        assertEquals("0", Energy.grams(0))
        assertEquals("0.65", Energy.gramsInput(650))
        assertEquals("1,234", Energy.milligrams(1_234))
    }

    @Test fun mifflinStJeor() {
        // 10·92.1 + 6.25·182 − 5·25 + 5 = 1938.5 kcal.
        assertEquals(1_938_500L, Energy.restingCal(Sex.MALE, 25, 92_100, 1820))
        assertEquals(1_772_500L, Energy.restingCal(Sex.FEMALE, 25, 92_100, 1820))
        val burn = Energy.burn(profile, records, LocalDate.parse("2026-10-18"))!!
        assertEquals("a", burn.weight.id)
        assertEquals(1_938_500L, burn.restingCal)
        assertEquals(2_326_200L, burn.totalCal)
    }

    @Test fun theDaysWeightIsTheLastOneUpToIt() {
        assertEquals("a", Energy.weightFor(records, LocalDate.parse("2026-10-19"))!!.id)
        assertEquals("b", Energy.weightFor(records, LocalDate.parse("2026-10-20"))!!.id)
        // Food recorded before the first weigh-in borrows the first one.
        assertEquals("a", Energy.weightFor(records, LocalDate.parse("2026-10-01"))!!.id)
        assertNull(Energy.weightFor(emptyList(), LocalDate.parse("2026-10-01")))
    }

    @Test fun noBurnWithoutProfileWeightOrAdulthood() {
        val day = LocalDate.parse("2026-10-20")
        assertNull(Energy.burn(null, records, day))
        assertNull(Energy.burn(profile, emptyList(), day))
        assertNull(Energy.burn(profile.copy(birthDate = "2010-01-01"), records, day))
    }

    @Test fun aDayIsItsEntriesInOrderAgainstItsBurn() {
        val all = listOf(entry(at = 20, name = "晚饭"), entry(at = 10, name = "早饭"), entry(date = "2026-10-19"))
        val d = Energy.day(LocalDate.parse("2026-10-20"), all, profile, records)
        assertEquals(listOf("早饭", "晚饭"), d.entries.map { it.name })
        assertEquals(408_700L, d.intakeCal)
        // 91.8 kg that day: 10·91.8 + 1137.5 − 125 + 5 = 1935.5, × 1.2 = 2322.6.
        assertEquals(2_322_600L, d.burn!!.totalCal)
        assertEquals(2_322_600L - 408_700L, d.deficitCal)
        assertNull(Energy.day(LocalDate.parse("2026-10-20"), all, null, records).deficitCal)
    }

    @Test fun totalsSayWhenSomeNutrientsAreMissing() {
        val t = Energy.totals(listOf(entry(), entry(protein = null, at = 1)))
        assertEquals(3_000L, t.proteinMg)
        assertTrue(t.hasMacros)
        assertTrue(t.partial)
        assertFalse(Energy.totals(listOf(entry())).partial)
    }

    @Test fun theAverageDeficitCountsRecordedDaysBeforeToday() {
        val today = LocalDate.parse("2026-10-20")
        val all = listOf(
            entry(date = "2026-10-20", per = null, amount = null, energyCal = 500_000),
            entry(date = "2026-10-19", per = null, amount = null, energyCal = 1_000_000),
            entry(date = "2026-10-18", per = null, amount = null, energyCal = 1_500_000),
        )
        val (avg, days) = Energy.averageDeficit(today, 7, all, profile, records)!!
        assertEquals(2, days)
        assertEquals((1_326_200L + 826_200L) / 2, avg)
        assertNull(Energy.averageDeficit(today, 7, all.take(1), profile, records))
        assertNull(Energy.averageDeficit(today, 7, all, null, records))
    }

    @Test fun recentIsOnePerNameNewestFirst() {
        val all = listOf(
            entry(date = "2026-10-18", name = "燕麦奶", energyCal = 1),
            entry(date = "2026-10-19", name = "米饭"),
            entry(date = "2026-10-20", name = " 燕麦奶 ", energyCal = 2),
            entry(date = "2026-10-20", name = null),
        )
        val r = Energy.recent(all)
        assertEquals(listOf(" 燕麦奶 ", "米饭"), r.map { it.name })
        assertEquals(2L, r.first().energyCal)
    }
}
