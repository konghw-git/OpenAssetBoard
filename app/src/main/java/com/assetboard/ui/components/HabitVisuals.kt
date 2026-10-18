package com.assetboard.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.assetboard.domain.Habit

/** Each habit's icon and its own color, shared by the todo battery and the diary strip. */
val Habit.icon: ImageVector
    get() = when (this) {
        Habit.SUPPLEMENTS -> Icons.Outlined.Medication
        Habit.WORKOUT -> Icons.Outlined.FitnessCenter
        Habit.DIET -> Icons.Outlined.Restaurant
        Habit.WATER -> Icons.Outlined.WaterDrop
        Habit.SLEEP -> Icons.Outlined.Bedtime
    }

val Habit.tint: Color
    get() = when (this) {
        Habit.SUPPLEMENTS -> Color(0xFFD9822B)
        Habit.WORKOUT -> Color(0xFFD0533F)
        Habit.DIET -> Color(0xFF5E9B3A)
        Habit.WATER -> Color(0xFF3D8FD1)
        Habit.SLEEP -> Color(0xFF7A62C2)
    }

/** The battery's charge color by how many of the five are done: amber, then lime, then full green. */
fun habitCharge(done: Int): Color = when {
    done >= Habit.entries.size -> Color(0xFF22B163)
    done >= 3 -> Color(0xFF7DBE3C)
    else -> Color(0xFFF0A23B)
}
