package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Energy is cal (one thousandth of a kcal), nutrients milligrams: a kJ label divides by 4.184 and
// would drift as a decimal, so like money and weight everything stored is a whole number.

/**
 * What a label's amounts are measured in, or [SERVING]: no label, the energy of one portion and
 * how many were eaten ("水煮蛋 × 2"), kept in halves so ½ 份 is a whole number too.
 */
enum class FoodUnit(val label: String) {
    G("g"), ML("ml"), SERVING("份");

    companion object {
        /** The units a nutrition label uses. */
        val labelUnits = listOf(G, ML)
    }
}

/**
 * One thing eaten, as it was typed: either a nutrition label ("每 100 ml 171 kJ…") with how much
 * of it went down, or, without a label, the numbers of the whole portion. What it amounts to is
 * never stored; [com.assetboard.domain.health.Energy] scales it on every read.
 */
@Serializable
@Entity(
    tableName = "food_entries",
    indices = [Index(value = ["date"])],
)
data class FoodEntry(
    @PrimaryKey val id: String,
    /** yyyy-MM-dd; any number of entries a day. */
    val date: String,
    /** Orders a day's entries; a back-filled day sits at its end. */
    @ColumnInfo(name = "eaten_at") val eatenAt: Long,
    val name: String? = null,
    /**
     * The label's reference amount ("每 100 ml" is 100); null when the numbers are the whole portion.
     * For [FoodUnit.SERVING] it is [com.assetboard.domain.health.Energy.SERVING_PER] and [amount] the halves eaten.
     */
    val per: Int? = null,
    /** How much was eaten, in [unit]; set exactly when [per] is. */
    val amount: Int? = null,
    val unit: FoodUnit? = null,
    /** Per [per], or for the portion: 40870 = 40.87 kcal. */
    @ColumnInfo(name = "energy_cal") val energyCal: Long,
    /** The label gave kJ; only decides how the editor shows the energy again. */
    @ColumnInfo(name = "in_kj") val inKj: Boolean = false,
    /** Like [energyCal]; null when not filled in. 600 = 0.6 g. */
    @ColumnInfo(name = "protein_mg") val proteinMg: Long? = null,
    @ColumnInfo(name = "fat_mg") val fatMg: Long? = null,
    @ColumnInfo(name = "carb_mg") val carbMg: Long? = null,
    @ColumnInfo(name = "sodium_mg") val sodiumMg: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
