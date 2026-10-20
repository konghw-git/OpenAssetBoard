package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Health numbers are integers too, for the same reason money is Long cents: weight is grams,
// body fat is one ten-thousandth (2580 = 25.80%). The UI divides; nothing drifts.

enum class Sex { MALE, FEMALE }

/** The single row of the health profile. It is user data, so it is backed up, unlike settings. */
@Serializable
@Entity(tableName = "health_profile")
data class HealthProfile(
    @PrimaryKey val id: String = "self",
    val sex: Sex,
    /** yyyy-MM-dd. */
    @ColumnInfo(name = "birth_date") val birthDate: String,
    /** 1820 = 182.0 cm. */
    @ColumnInfo(name = "height_mm") val heightMm: Int,
    /** Null means the target is the BMI 22 weight for this height. */
    @ColumnInfo(name = "target_weight_g") val targetWeightG: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One weigh-in a day, keyed by the date the user picked rather than by [measuredAt]: two records
 * never share a [date], and [measuredAt] exists only to show "today 07:12".
 */
@Serializable
@Entity(
    tableName = "body_measurements",
    indices = [Index(value = ["date"], unique = true)],
)
data class BodyMeasurement(
    @PrimaryKey val id: String,
    /** yyyy-MM-dd; at most one record per day. */
    val date: String,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    /** 92100 = 92.1 kg. */
    @ColumnInfo(name = "weight_g") val weightG: Long,
    /** 2580 = 25.80%; null when only a plain scale was at hand. */
    @ColumnInfo(name = "body_fat_bp") val bodyFatBp: Int? = null,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
