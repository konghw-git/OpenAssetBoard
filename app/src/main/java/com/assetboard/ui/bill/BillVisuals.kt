package com.assetboard.ui.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assetboard.domain.BillCategory
import com.assetboard.domain.Bills
import com.assetboard.ui.theme.AppTheme

/** Each category's icon. */
internal fun BillCategory.icon(): ImageVector = when (key) {
    "food" -> Icons.Outlined.Restaurant
    "transport" -> Icons.Outlined.DirectionsBus
    "shopping" -> Icons.Outlined.ShoppingBag
    "daily" -> Icons.Outlined.ShoppingBasket
    "housing" -> Icons.Outlined.Home
    "fun" -> Icons.Outlined.SportsEsports
    "health" -> Icons.Outlined.LocalHospital
    "learning" -> Icons.Outlined.School
    "social" -> Icons.Outlined.CardGiftcard
    "salary" -> Icons.Outlined.Payments
    "bonus" -> Icons.Outlined.EmojiEvents
    "parttime" -> Icons.Outlined.Handyman
    "invest" -> Icons.AutoMirrored.Outlined.TrendingUp
    "gift" -> Icons.Outlined.Redeem
    "reimburse" -> Icons.AutoMirrored.Outlined.ReceiptLong
    "refund" -> Icons.AutoMirrored.Outlined.Undo
    else -> Icons.Outlined.MoreHoriz
}

/** Each category's colour, from the group palette by its place in its list, so it follows the theme. */
@Composable
internal fun BillCategory.color(): Color {
    val i = Bills.categories(income).indexOf(this).coerceAtLeast(0)
    return AppTheme.extra.group(i + if (income) 3 else 0)
}

/** A category's icon on a disc of its colour, as bills show in lists. */
@Composable
internal fun CategoryDot(category: BillCategory, size: Dp = 34.dp) {
    val c = category.color()
    Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(category.icon(), null, tint = c, modifier = Modifier.size(size * 0.52f))
    }
}
