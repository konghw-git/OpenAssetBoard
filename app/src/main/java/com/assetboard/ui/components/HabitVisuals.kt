package com.assetboard.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhonelinkErase
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.SmokeFree
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.assetboard.domain.Habit

/** The icons a habit can wear, by the name [Habit.icon] stores; the first five are the built-in ones. */
val HabitIcons: List<Pair<String, ImageVector>> = listOf(
    "medication" to Icons.Outlined.Medication,
    "fitness" to Icons.Outlined.FitnessCenter,
    "restaurant" to Icons.Outlined.Restaurant,
    "water" to Icons.Outlined.WaterDrop,
    "bedtime" to Icons.Outlined.Bedtime,
    "book" to Icons.AutoMirrored.Outlined.MenuBook,
    "run" to Icons.AutoMirrored.Outlined.DirectionsRun,
    "meditate" to Icons.Outlined.SelfImprovement,
    "language" to Icons.Outlined.Translate,
    "code" to Icons.Outlined.Code,
    "music" to Icons.Outlined.MusicNote,
    "draw" to Icons.Outlined.Brush,
    "study" to Icons.Outlined.School,
    "savings" to Icons.Outlined.Savings,
    "smokefree" to Icons.Outlined.SmokeFree,
    "phone" to Icons.Outlined.PhonelinkErase,
    "sun" to Icons.Outlined.WbSunny,
    "spa" to Icons.Outlined.Spa,
    "clean" to Icons.Outlined.CleaningServices,
    "pets" to Icons.Outlined.Pets,
    "heart" to Icons.Outlined.Favorite,
)

/** The colors a habit can take, ARGB; the first five are the built-in ones'. */
val HabitColors: List<Long> = listOf(
    0xFFD9822B, 0xFFD0533F, 0xFF5E9B3A, 0xFF3D8FD1, 0xFF7A62C2,
    0xFF2A9D8F, 0xFFC2477A, 0xFFB59A1E, 0xFF8D6E63, 0xFF546E7A,
)

/** Each habit's icon and its own color, shared by the todo battery, the diary strip and the week. */
val Habit.iconVector: ImageVector
    get() = HabitIcons.firstOrNull { it.first == icon }?.second ?: Icons.Outlined.CheckCircle

val Habit.tint: Color
    get() = Color(color)

/** The battery's charge color by how many of [total] are done: amber, then lime from three in five, then full green. */
fun habitCharge(done: Int, total: Int): Color = when {
    total > 0 && done >= total -> Color(0xFF22B163)
    done * 5 >= total * 3 && done > 0 -> Color(0xFF7DBE3C)
    else -> Color(0xFFF0A23B)
}
