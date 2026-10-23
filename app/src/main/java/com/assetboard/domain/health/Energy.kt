package com.assetboard.domain.health

import com.assetboard.data.BodyMeasurement
import com.assetboard.data.FoodEntry
import com.assetboard.data.FoodUnit
import com.assetboard.data.HealthProfile
import com.assetboard.data.Sex
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

/**
 * Food energy and what a day burns, as pure functions. Energy is cal (1/1000 kcal), nutrients mg.
 * The baseline is the resting rate from the profile and the day's weight (Mifflin–St Jeor). What
 * a day burns on top of it, walking about or training, cannot be measured here, so it is left out:
 * the deficit is the resting rate minus what was eaten, the least a day can have, and any activity
 * only adds to it. There is no target and no advice.
 */
object Energy {
    /** 1 kcal = 4.184 kJ, the conversion GB 28050 labels use. */
    const val J_PER_KCAL = 4_184L

    const val PER_MAX = 10_000
    const val AMOUNT_MAX = 100_000
    /** 20 000 kcal: a single entry above that is a typo. */
    const val ENERGY_MAX_CAL = 20_000_000L
    /** 5 kg of one nutrient. */
    const val NUTRIENT_MAX_MG = 5_000_000L
    const val NAME_MAX = 40

    /** Portions are counted in halves: 2 份 is 4 of [SERVING_PER]. */
    const val SERVING_PER = 2
    const val SERVINGS_MAX_HALVES = 99 * SERVING_PER

    // ------------------------------------------------------------ scaling

    /** [value] for the label's [per] scaled to the [amount] eaten, rounded half up; the value itself without a label. */
    fun scaled(value: Long, per: Int?, amount: Int?): Long =
        if (per == null || amount == null || per <= 0) value else (value * amount + per / 2) / per

    fun energyOf(e: FoodEntry): Long = scaled(e.energyCal, e.per, e.amount)

    /** How many portions an entry without a label was: 1 when it was not counted. */
    fun servingHalves(e: FoodEntry): Int =
        if (e.unit == FoodUnit.SERVING && e.per != null && e.amount != null) e.amount * SERVING_PER / e.per else SERVING_PER

    /** 4 -> "2", 1 -> "0.5", 3 -> "1.5". */
    fun servings(halves: Int): String = if (halves % SERVING_PER == 0) "${halves / SERVING_PER}" else "${halves / SERVING_PER}.5"
    fun proteinOf(e: FoodEntry): Long? = e.proteinMg?.let { scaled(it, e.per, e.amount) }
    fun fatOf(e: FoodEntry): Long? = e.fatMg?.let { scaled(it, e.per, e.amount) }
    fun carbOf(e: FoodEntry): Long? = e.carbMg?.let { scaled(it, e.per, e.amount) }
    fun sodiumOf(e: FoodEntry): Long? = e.sodiumMg?.let { scaled(it, e.per, e.amount) }

    /** Protein and carbohydrate 4 kcal/g, fat 9: mg times kcal/g is cal. */
    fun atwaterCal(proteinMg: Long, fatMg: Long, carbMg: Long): Long = proteinMg * 4 + fatMg * 9 + carbMg * 4

    /**
     * Whether a label's energy and its three nutrients disagree by more than rounding and fibre
     * explain (15%, and at least 10 kcal): most likely a digit typed wrong, or kJ taken for kcal.
     */
    fun disagrees(energyCal: Long, atwaterCal: Long): Boolean {
        val diff = kotlin.math.abs(energyCal - atwaterCal)
        return diff > 10_000 && diff * 100 > maxOf(energyCal, atwaterCal) * 15
    }

    /** A day's sums; a nutrient sums the entries that have it, and [partial] says some did not. */
    class Totals(
        val energyCal: Long,
        val proteinMg: Long,
        val fatMg: Long,
        val carbMg: Long,
        val sodiumMg: Long,
        val hasMacros: Boolean,
        val partial: Boolean,
    )

    fun totals(entries: List<FoodEntry>): Totals = Totals(
        energyCal = entries.sumOf(::energyOf),
        proteinMg = entries.sumOf { proteinOf(it) ?: 0L },
        fatMg = entries.sumOf { fatOf(it) ?: 0L },
        carbMg = entries.sumOf { carbOf(it) ?: 0L },
        sodiumMg = entries.sumOf { sodiumOf(it) ?: 0L },
        hasMacros = entries.any { it.proteinMg != null || it.fatMg != null || it.carbMg != null },
        partial = entries.any { it.proteinMg == null || it.fatMg == null || it.carbMg == null },
    )

    // ------------------------------------------------------------ the burn

    /** Mifflin–St Jeor (1990) in cal: 10·kg + 6.25·cm − 5·age, +5 for men, −161 for women. */
    fun restingCal(sex: Sex, age: Int, weightG: Long, heightMm: Int): Long =
        10 * weightG + 625L * heightMm - 5_000L * age + if (sex == Sex.MALE) 5_000L else -161_000L

    /** The weigh-in that stands for [date]: the last one on or before it, else the first after it. */
    fun weightFor(records: List<BodyMeasurement>, date: LocalDate): BodyMeasurement? {
        val day = date.toString()
        return records.lastOrNull { it.date <= day } ?: records.firstOrNull()
    }

    class Burn(val restingCal: Long, val weight: BodyMeasurement)

    /**
     * What [date] burns, or null without a profile or a weigh-in, and under 18, where the formula
     * is not meant for growing bodies (as the body fat bands are not).
     */
    fun burn(profile: HealthProfile?, records: List<BodyMeasurement>, date: LocalDate): Burn? {
        profile ?: return null
        val w = weightFor(records, date) ?: return null
        val age = Health.ageOn(LocalDate.parse(profile.birthDate), date)
        if (age < 18) return null
        val resting = restingCal(profile.sex, age, w.weightG, profile.heightMm)
        if (resting <= 0) return null
        return Burn(resting, w)
    }

    /** One day: what was eaten, in order, and what it burned. */
    class Day(val date: LocalDate, val entries: List<FoodEntry>, val burn: Burn?) {
        val totals: Totals = totals(entries)
        val intakeCal: Long get() = totals.energyCal
        /** The resting rate minus what was eaten; negative when more was eaten. Null without a burn. */
        val deficitCal: Long? get() = burn?.let { it.restingCal - intakeCal }
        /** What was eaten as a share of the resting rate, in whole percent rounded half up. */
        val percentOfResting: Int? get() = burn?.let { ((intakeCal * 100 + it.restingCal / 2) / it.restingCal).toInt() }
    }

    fun day(date: LocalDate, entries: List<FoodEntry>, profile: HealthProfile?, records: List<BodyMeasurement>): Day {
        val d = date.toString()
        return Day(date, entries.filter { it.date == d }.sortedWith(compareBy({ it.eatenAt }, { it.createdAt })), burn(profile, records, date))
    }

    /**
     * The average deficit of the recorded days among the [days] before [today] (today is not over
     * yet); null when none of them has both entries and a burn. Days without entries are not
     * counted as anything.
     */
    fun averageDeficit(today: LocalDate, days: Int, entries: List<FoodEntry>, profile: HealthProfile?, records: List<BodyMeasurement>): Pair<Long, Int>? {
        val recorded = (1..days).map { today.minusDays(it.toLong()) }
            .map { day(it, entries, profile, records) }
            .filter { it.entries.isNotEmpty() && it.deficitCal != null }
        if (recorded.isEmpty()) return null
        return recorded.sumOf { it.deficitCal!! } / recorded.size to recorded.size
    }

    /** What was eaten before, newest first, one per name: picking one fills the editor with it. */
    fun recent(entries: List<FoodEntry>, limit: Int = 12): List<FoodEntry> =
        entries.filter { !it.name.isNullOrBlank() }
            .sortedWith(compareByDescending<FoodEntry> { it.date }.thenByDescending { it.eatenAt }.thenByDescending { it.createdAt })
            .distinctBy { it.name!!.trim().lowercase(Locale.ROOT) }
            .take(limit)

    // ------------------------------------------------------------ formatting

    /** Whole kcal, rounded half up: 204350 -> "204", 1240000 -> "1,240". */
    fun kcal(cal: Long): String = String.format(Locale.US, "%,d", Math.floorDiv(cal + 500, 1000L))

    /** Grams of a nutrient: 3000 -> "3", 600 -> "0.6", 12345 -> "12.3", 123456 -> "123". */
    fun grams(mg: Long): String {
        if (mg >= 100_000) return String.format(Locale.US, "%,d", (mg + 500) / 1000)
        return BigDecimal.valueOf(mg, 3).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun milligrams(mg: Long): String = String.format(Locale.US, "%,d", mg)

    /** Energy as the editor shows it again: kcal or kJ, at most one decimal. */
    fun energyInput(cal: Long, kj: Boolean): String {
        val v = if (kj) BigDecimal.valueOf(cal * J_PER_KCAL, 6) else BigDecimal.valueOf(cal, 3)
        return v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun gramsInput(mg: Long): String = BigDecimal.valueOf(mg, 3).stripTrailingZeros().toPlainString()

    /** "171" kJ -> 40870 cal, "40.9" kcal -> 40900; one decimal at most. */
    fun parseEnergy(input: String, kj: Boolean): Long? {
        val v = Health.parseScaled(input, 3, maxDecimals = 1) ?: return null
        if (v > ENERGY_MAX_CAL * 10) return null
        return if (kj) (v * 1000 + J_PER_KCAL / 2) / J_PER_KCAL else v
    }

    /** "0.6" g -> 600 mg; two decimals at most. */
    fun parseGrams(input: String): Long? = Health.parseScaled(input, 3, maxDecimals = 2)?.takeIf { it <= NUTRIENT_MAX_MG * 10 }

    /** Sodium as labels give it, whole mg. */
    fun parseMilligrams(input: String): Long? = Health.parseScaled(input, 0, maxDecimals = 0)?.takeIf { it <= NUTRIENT_MAX_MG * 10 }

    /** A whole number of g or ml. */
    fun parseAmount(input: String): Int? = Health.parseScaled(input, 0, maxDecimals = 0)?.takeIf { it <= AMOUNT_MAX * 10L }?.toInt()
}
