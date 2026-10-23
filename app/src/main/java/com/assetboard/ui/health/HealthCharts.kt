package com.assetboard.ui.health

import androidx.compose.ui.graphics.Color
import com.assetboard.data.BodyMeasurement
import com.assetboard.data.HealthProfile
import com.assetboard.domain.health.Health
import com.assetboard.domain.health.HealthTrend
import com.assetboard.ui.components.ChartPoint
import com.assetboard.ui.components.ChartSeries
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToLong

/** The three lines the health page can draw. Values are Long: grams, bp, and BMI × 100. */
enum class HealthMetric(val label: String, val unit: String) {
    WEIGHT("体重", "kg"), FAT("体脂率", "%"), BMI("BMI", "");

    fun value(m: BodyMeasurement, heightMm: Int?): Long? = when (this) {
        WEIGHT -> m.weightG
        FAT -> m.bodyFatBp?.toLong()
        BMI -> heightMm?.let { Health.bmi(m.weightG, it) }?.let { (it * 100).roundToLong() }
    }

    fun format(v: Long): String = when (this) {
        WEIGHT -> Health.kg(v)
        FAT -> Health.percent(v.toInt())
        BMI -> String.format(Locale.US, "%.1f", v / 100.0)
    }

    /** The difference between two values, signed, in this metric's own unit (kg, points, BMI). */
    fun formatDelta(d: Long): String = when (this) {
        WEIGHT -> Health.kgDelta(d)
        FAT -> Health.bpDelta(d.toInt())
        BMI -> (if (d < 0) "−" else if (d > 0) "+" else "") + String.format(Locale.US, "%.1f", kotlin.math.abs(d) / 100.0)
    }

    /** A body fat change is in percentage points, never "%" of itself. */
    fun deltaWithUnit(d: Long): String = when (this) {
        WEIGHT -> formatDelta(d) + " kg"
        FAT -> formatDelta(d) + " 个百分点"
        BMI -> formatDelta(d)
    }

    fun withUnit(text: String): String = when (this) {
        WEIGHT -> "$text kg"
        FAT -> "$text%"
        BMI -> text
    }
}

enum class HealthRange(val label: String, val days: Long?) {
    D30("30天", 30), D90("90天", 90), Y1("1年", 365), ALL("全部", null)
}

/** What [com.assetboard.ui.components.LineChart] is handed for one metric and one period. */
class HealthChart(
    /** The original line first, then the moving average as a dashed series when there is one. */
    val series: List<ChartSeries>,
    /** The records behind the first series, in its order, so a selected index maps back to a day. */
    val records: List<BodyMeasurement>,
    val target: Long?,
    val targetLabel: String?,
    val band: LongRange?,
)

fun dateX(date: String): Long = HealthTrend.measurementAt(BodyMeasurement("", date, 0, 0, null, null, 0, 0))

/**
 * The series for [metric] over [points]. The moving average is one dashed line over the same
 * days, its windows reaching back into [history] before the period. Target and band are plain
 * values for the chart's background, never series: a series would be scrubbed and would stretch
 * the y range.
 */
fun healthChart(
    metric: HealthMetric,
    points: List<BodyMeasurement>,
    profile: HealthProfile?,
    line: Color,
    average: Color,
    history: List<BodyMeasurement> = points,
): HealthChart {
    val height = profile?.heightMm
    val records = points.filter { metric.value(it, height) != null }
    val main = ChartSeries(records.map { ChartPoint(dateX(it.date), metric.value(it, height)!!) }, line, fill = true)
    val runs = HealthTrend.movingAverage(points, history) { metric.value(it, height) }
        .takeIf { it.isNotEmpty() }
        ?.let { avg -> listOf(ChartSeries(avg.map { (d, v) -> ChartPoint(dateX(d), v) }, average, dashed = true)) }
        .orEmpty()
    val (target, label, band) = when (metric) {
        HealthMetric.WEIGHT -> if (profile == null) Triple(null, null, null) else {
            val t = Health.targetWeightG(profile.heightMm, profile.targetWeightG)
            Triple(t, "目标 ${Health.kg(t)}", Health.healthyRangeG(profile.heightMm))
        }
        HealthMetric.BMI -> if (profile == null) Triple(null, null, null) else {
            val t = Health.bmi(Health.targetWeightG(profile.heightMm, profile.targetWeightG), profile.heightMm)!!.let { (it * 100).roundToLong() }
            Triple(t, "目标 ${metric.format(t)}", (Health.BMI_LOW * 100).roundToLong() until (Health.BMI_HIGH * 100).roundToLong())
        }
        HealthMetric.FAT -> {
            val last = points.lastOrNull()
            val age = if (profile != null && last != null) Health.ageOn(LocalDate.parse(profile.birthDate), LocalDate.parse(last.date)) else null
            val std = if (profile != null && age != null) Health.fatStandard(profile.sex, age) else null
            Triple(null, null, std?.let { (lo, hi) -> lo.toLong() until hi.toLong() })
        }
    }
    return HealthChart(listOf(main) + runs, records, target, label, band)
}
