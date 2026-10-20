package com.assetboard.domain.health

import com.assetboard.data.Sex
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * The health rules, as pure functions, in the same spirit as [com.assetboard.domain.Ledger].
 * Weight is grams, body fat is one ten-thousandth (bp), height millimetres; every band is
 * half-open ([18.5, 24), not 18.5 ≤ bmi ≤ 24) so no reading falls between two labels.
 */
object Health {
    const val WEIGHT_MIN_G = 20_000L
    const val WEIGHT_MAX_G = 300_000L
    const val FAT_MIN_BP = 300
    const val FAT_MAX_BP = 7_000
    const val HEIGHT_MIN_MM = 1_000
    const val HEIGHT_MAX_MM = 2_500

    /** The normal band of the Chinese adult standard WS/T 428-2013, which the scale also uses. */
    const val BMI_LOW = 18.5
    const val BMI_HIGH = 24.0
    const val BMI_OBESE = 28.0

    /** Where a BMI-22 weight sits; the device's own "标准体重" is a different formula and is not shown. */
    const val TARGET_BMI = 22.0

    enum class Grade(val label: String) {
        LOW("偏低"), STANDARD("标准"), HIGH("偏高"), OBESE("肥胖"),
    }

    enum class FatGrade(val label: String) {
        LOW("偏低"), STANDARD("标准"), HIGH("偏高"),
    }

    /** The scale's two-by-two reading of BMI against body fat, all nine cells named. */
    enum class BodyType(val label: String) {
        THIN("消瘦型"), SLIM("苗条型"), UNDERWEIGHT("偏瘦型"),
        MUSCULAR("肌肉型"), HEALTHY("健康型"), HIDDEN_FAT("隐形肥胖"),
        STRONG("强壮型"), PLUMP("偏胖"), OBESE("肥胖"),
    }

    /** BMI from grams and millimetres; null when the height is unusable. */
    fun bmi(weightG: Long, heightMm: Int): Double? {
        if (heightMm <= 0) return null
        val m = heightMm / 1000.0
        return weightG / 1000.0 / (m * m)
    }

    fun bmiGrade(bmi: Double): Grade = when {
        bmi < BMI_LOW -> Grade.LOW
        bmi < BMI_HIGH -> Grade.STANDARD
        bmi < BMI_OBESE -> Grade.HIGH
        else -> Grade.OBESE
    }

    /** Whole years between [birthDate] and the day of the measurement; negative if the birth date is later. */
    fun ageOn(birthDate: LocalDate, on: LocalDate): Int = ChronoUnit.YEARS.between(birthDate, on).toInt()

    /**
     * Body fat bands for the age of the measurement day, from Gallagher et al. 2000 (AJCN
     * 72(3):694–701), the table Garmin, Omron and Tanita also use. The published table starts at
     * 20; this extends its lowest band down to 18. Below 18 there is no reliable band, so the
     * result is null and the UI shows no label — the record itself is always kept.
     */
    fun fatGrade(bodyFatBp: Int, sex: Sex, age: Int): FatGrade? {
        val (low, high) = fatStandard(sex, age) ?: return null
        return when {
            bodyFatBp < low -> FatGrade.LOW
            bodyFatBp < high -> FatGrade.STANDARD
            else -> FatGrade.HIGH
        }
    }

    /** The standard band [low, high) in bp for [sex] at [age]; null under 18. */
    fun fatStandard(sex: Sex, age: Int): Pair<Int, Int>? {
        if (age < 18) return null
        return when (sex) {
            Sex.MALE -> when {
                age < 40 -> 800 to 2_000
                age < 60 -> 1_100 to 2_200
                else -> 1_300 to 2_500
            }
            Sex.FEMALE -> when {
                age < 40 -> 2_100 to 3_200
                age < 60 -> 2_300 to 3_400
                else -> 2_400 to 3_600
            }
        }
    }

    /** Fat in grams; null without a body fat reading, never 0. */
    fun fatMassG(weightG: Long, bodyFatBp: Int?): Long? =
        bodyFatBp?.let { (weightG.toDouble() * it / 10_000.0).roundToLong() }

    /** Weight minus fat; null for the same reason. Muscle is deliberately not derived on top of it. */
    fun leanMassG(weightG: Long, bodyFatBp: Int?): Long? =
        fatMassG(weightG, bodyFatBp)?.let { weightG - it }

    /** The stored target, else the BMI-22 weight. */
    fun targetWeightG(heightMm: Int, targetWeightG: Long?): Long =
        targetWeightG ?: bmiWeightG(heightMm, TARGET_BMI)

    fun bmiWeightG(heightMm: Int, bmi: Double): Long {
        val m = heightMm / 1000.0
        return (bmi * m * m * 1000.0).roundToLong()
    }

    /** The normal band in grams, upper end exclusive, as the bands themselves are. */
    fun healthyRangeG(heightMm: Int): LongRange =
        bmiWeightG(heightMm, BMI_LOW) until bmiWeightG(heightMm, BMI_HIGH)

    /** Null without body fat: the matrix needs both axes, so a weight-only day places no dot. */
    fun bodyType(bmi: Double?, bodyFatBp: Int?): BodyType? {
        if (bmi == null || bodyFatBp == null) return null
        return when {
            bmi < BMI_LOW -> when {
                bodyFatBp < 1_000 -> BodyType.THIN
                bodyFatBp < 2_000 -> BodyType.SLIM
                else -> BodyType.UNDERWEIGHT
            }
            bmi < BMI_HIGH -> when {
                bodyFatBp < 1_000 -> BodyType.MUSCULAR
                bodyFatBp < 2_000 -> BodyType.HEALTHY
                else -> BodyType.HIDDEN_FAT
            }
            else -> when {
                bodyFatBp < 1_000 -> BodyType.STRONG
                bodyFatBp < 2_000 -> BodyType.PLUMP
                else -> BodyType.OBESE
            }
        }
    }

    // ------------------------------------------------------------ formatting

    /** 92100 -> "92.1"; one decimal, as the scale reports. */
    fun kg(weightG: Long): String = String.format(java.util.Locale.US, "%.1f", weightG / 1000.0)

    /** −1200 -> "−1.2", 1200 -> "+1.2": a difference in grams. */
    fun kgDelta(deltaG: Long, signed: Boolean = true): String {
        val sign = when {
            deltaG < 0 -> "−"
            signed && deltaG > 0 -> "+"
            else -> ""
        }
        return sign + String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(deltaG) / 1000.0)
    }

    /** 2580 -> "25.8"; the unit is added by the caller. */
    fun percent(bp: Int): String = String.format(java.util.Locale.US, "%.1f", bp / 100.0)

    /** −90 -> "−0.9": a body fat change is in percentage points, not percent of itself. */
    fun bpDelta(deltaBp: Int, signed: Boolean = true): String {
        val sign = when {
            deltaBp < 0 -> "−"
            signed && deltaBp > 0 -> "+"
            else -> ""
        }
        return sign + String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(deltaBp) / 100.0)
    }

    fun bmiText(bmi: Double): String = String.format(java.util.Locale.US, "%.1f", bmi)

    /** "92.1" -> 92100; up to two decimals, anything else is null. */
    fun parseKg(input: String): Long? = parseScaled(input, 3, maxDecimals = 2)

    /** "25.8" (percent) -> 2580; up to two decimals. */
    fun parsePercent(input: String): Int? = parseScaled(input, 2, maxDecimals = 2)?.let { if (it > Int.MAX_VALUE) null else it.toInt() }

    /** "182" or "182.5" (cm) -> 1820 / 1825. */
    fun parseCm(input: String): Int? = parseScaled(input, 1, maxDecimals = 1)?.let { if (it > Int.MAX_VALUE) null else it.toInt() }

    /** Plain editable text: 92100 -> "92.1", 1820 -> "182", 2580 -> "25.8". */
    fun kgInput(g: Long): String = java.math.BigDecimal.valueOf(g, 3).stripTrailingZeros().toPlainString()
    fun percentInput(bp: Int): String = java.math.BigDecimal.valueOf(bp.toLong(), 2).stripTrailingZeros().toPlainString()
    fun cmInput(mm: Int): String = java.math.BigDecimal.valueOf(mm.toLong(), 1).stripTrailingZeros().toPlainString()

    private fun parseScaled(input: String, shift: Int, maxDecimals: Int): Long? {
        val t = input.trim().replace("，", ".").replace(",", ".")
        if (t.isEmpty()) return null
        return try {
            val bd = java.math.BigDecimal(t)
            if (bd.signum() < 0 || bd.stripTrailingZeros().scale() > maxDecimals) return null
            bd.movePointRight(shift).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }
}
