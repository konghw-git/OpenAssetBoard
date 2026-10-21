package com.assetboard.domain

import com.assetboard.data.Sex
import com.assetboard.domain.health.Health
import com.assetboard.domain.health.Health.BodyType
import com.assetboard.domain.health.Health.FatGrade
import com.assetboard.domain.health.Health.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The health rules, checked against the S400 report of 2026-10-17 (92.1 kg, 25.8%, 182 cm). */
class HealthTest {
    @Test fun theScaleReportAddsUp() {
        val bmi = Health.bmi(92_100, 1820)!!
        assertEquals("27.8", Health.bmiText(bmi))
        assertEquals(Grade.HIGH, Health.bmiGrade(bmi))
        assertEquals(23_762L, Health.fatMassG(92_100, 2_580))
        assertEquals("23.8", Health.kg(Health.fatMassG(92_100, 2_580)!!))
        assertEquals("68.3", Health.kg(Health.leanMassG(92_100, 2_580)!!))
        val age = Health.ageOn(LocalDate.parse("2001-08-02"), LocalDate.parse("2026-10-17"))
        assertEquals(25, age)
        assertEquals(FatGrade.HIGH, Health.fatGrade(2_580, Sex.MALE, age))
        assertEquals(BodyType.OBESE, Health.bodyType(bmi, 2_580))
    }

    @Test fun bmiBandsAreHalfOpen() {
        assertEquals(Grade.LOW, Health.bmiGrade(18.49))
        assertEquals(Grade.STANDARD, Health.bmiGrade(18.5))
        assertEquals(Grade.STANDARD, Health.bmiGrade(23.99))
        assertEquals(Grade.HIGH, Health.bmiGrade(24.0))
        assertEquals(Grade.HIGH, Health.bmiGrade(27.99))
        assertEquals(Grade.OBESE, Health.bmiGrade(28.0))
    }

    @Test fun fatBandsFollowSexAndTheAgeOfTheDay() {
        assertEquals(FatGrade.LOW, Health.fatGrade(799, Sex.MALE, 18))
        assertEquals(FatGrade.STANDARD, Health.fatGrade(800, Sex.MALE, 39))
        assertEquals(FatGrade.HIGH, Health.fatGrade(2_000, Sex.MALE, 39))
        assertEquals(FatGrade.STANDARD, Health.fatGrade(2_000, Sex.MALE, 40))
        assertEquals(FatGrade.HIGH, Health.fatGrade(2_500, Sex.MALE, 60))
        assertEquals(FatGrade.STANDARD, Health.fatGrade(2_100, Sex.FEMALE, 25))
        assertEquals(FatGrade.STANDARD, Health.fatGrade(3_250, Sex.FEMALE, 25))
        assertEquals(FatGrade.HIGH, Health.fatGrade(3_300, Sex.FEMALE, 25))
        assertEquals(FatGrade.LOW, Health.fatGrade(2_299, Sex.FEMALE, 45))
        assertEquals(FatGrade.STANDARD, Health.fatGrade(3_500, Sex.FEMALE, 70))
        // A grade is fixed by the measurement day: the birthday the day after does not move it.
        assertEquals(39, Health.ageOn(LocalDate.parse("1980-10-18"), LocalDate.parse("2020-10-17")))
        assertEquals(40, Health.ageOn(LocalDate.parse("1980-10-18"), LocalDate.parse("2020-10-18")))
    }

    @Test fun underEighteenHasNoGradeButNoError() {
        assertNull(Health.fatGrade(2_000, Sex.MALE, 17))
        assertNull(Health.fatStandard(Sex.FEMALE, 0))
        assertNull(Health.fatGrade(2_000, Sex.MALE, -3))
    }

    @Test fun allNineBodyTypes() {
        assertEquals(BodyType.THIN, Health.bodyType(17.0, 900))
        assertEquals(BodyType.SLIM, Health.bodyType(17.0, 1_000))
        assertEquals(BodyType.UNDERWEIGHT, Health.bodyType(17.0, 2_000))
        assertEquals(BodyType.MUSCULAR, Health.bodyType(18.5, 999))
        assertEquals(BodyType.HEALTHY, Health.bodyType(22.0, 1_500))
        assertEquals(BodyType.HIDDEN_FAT, Health.bodyType(23.99, 2_000))
        assertEquals(BodyType.STRONG, Health.bodyType(24.0, 900))
        assertEquals(BodyType.PLUMP, Health.bodyType(30.0, 1_999))
        assertEquals(BodyType.OBESE, Health.bodyType(30.0, 2_000))
        assertNull(Health.bodyType(30.0, null))
        assertNull(Health.bodyType(null, 2_000))
    }

    @Test fun derivedMassesAreNullWithoutBodyFat() {
        assertNull(Health.fatMassG(92_100, null))
        assertNull(Health.leanMassG(92_100, null))
    }

    @Test fun targetAndHealthyRangeFor182() {
        assertEquals("72.9", Health.kg(Health.targetWeightG(1820, null)))
        assertEquals(75_000L, Health.targetWeightG(1820, 75_000))
        val range = Health.healthyRangeG(1820)
        assertEquals("61.3", Health.kg(range.first))
        assertEquals("79.5", Health.kg(range.last + 1))
    }

    @Test fun parsingIsExact() {
        assertEquals(92_100L, Health.parseKg("92.1"))
        assertEquals(92_150L, Health.parseKg(" 92.15 "))
        assertNull(Health.parseKg("92.155"))
        assertNull(Health.parseKg("-3"))
        assertNull(Health.parseKg("abc"))
        assertNull(Health.parseKg(""))
        assertEquals(2_580, Health.parsePercent("25.8"))
        assertEquals(1_820, Health.parseCm("182"))
        assertEquals(1_825, Health.parseCm("182.5"))
        assertNull(Health.parseCm("182.55"))
        assertEquals("92.1", Health.kgInput(92_100))
        assertEquals("25.8", Health.percentInput(2_580))
        assertEquals("182", Health.cmInput(1_820))
    }

    @Test fun deltasUseATrueMinusAndPoints() {
        assertEquals("−1.2", Health.kgDelta(-1_200))
        assertEquals("+0.4", Health.kgDelta(400))
        assertEquals("0.0", Health.kgDelta(0))
        assertEquals("−0.9", Health.bpDelta(-90))
    }
}
