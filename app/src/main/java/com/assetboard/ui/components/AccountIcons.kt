package com.assetboard.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.R
import com.assetboard.data.Account
import com.assetboard.data.AccountKind

/**
 * Which picture an account gets. Brands come from the institutions' own marks; the name is
 * matched before the institution, so 余额宝 and 花呗 (both 支付宝) keep their own marks.
 */
object AccountIcons {
    enum class Fit {
        /** Artwork that is a whole square icon: fills the tile. */
        FILL,
        /** A logo on transparent ground: centered on a white tile. */
        MARK,
    }

    data class Brand(@DrawableRes val res: Int, val fit: Fit)

    /** One choice in the account's icon picker. [key] is what [Account.icon] stores. */
    data class Option(val key: String, val label: String, val vector: ImageVector)

    /**
     * The icons an account can wear instead of the automatic mark. Keys persist in the database
     * and in backups, so they never change; [label] is the picker's accessibility name.
     */
    val options: List<Option> = listOf(
        Option("wallet", "钱包", Icons.Rounded.AccountBalanceWallet),
        Option("card", "银行卡", Icons.Rounded.CreditCard),
        Option("bank", "银行", Icons.Rounded.AccountBalance),
        Option("savings", "储蓄", Icons.Rounded.Savings),
        Option("invest", "投资", Icons.AutoMirrored.Rounded.ShowChart),
        Option("cash", "现金", Icons.Rounded.Payments),
        Option("pay", "支付", Icons.Rounded.QrCodeScanner),
        Option("home", "房子", Icons.Rounded.Home),
        Option("car", "车", Icons.Rounded.DirectionsCar),
        Option("travel", "旅行", Icons.Rounded.Flight),
        Option("education", "教育", Icons.Rounded.School),
        Option("medical", "医疗", Icons.Rounded.LocalHospital),
        Option("insurance", "保险", Icons.Rounded.Shield),
        Option("gold", "黄金", Icons.Rounded.Diamond),
        Option("digital", "数字资产", Icons.Rounded.CurrencyBitcoin),
        Option("gift", "红包", Icons.Rounded.Redeem),
        Option("other", "其他", Icons.Rounded.Star),
    )

    /** The icon stored under [key], or null when the account follows its name. */
    fun vectorOf(key: String?): ImageVector? = options.firstOrNull { it.key == key }?.vector

    private class Rule(val keys: List<String>, val brand: Brand, val investmentOnly: Boolean = false)

    private fun rule(vararg keys: String, res: Int, fit: Fit, investmentOnly: Boolean = false) =
        Rule(keys.toList(), Brand(res, fit), investmentOnly)

    // Most specific first: a product before the company behind it.
    private val rules = listOf(
        rule("花呗", res = R.drawable.brand_huabei, fit = Fit.MARK),
        rule("余额宝", res = R.drawable.brand_yuebao, fit = Fit.MARK),
        rule("白条", "京东", res = R.drawable.brand_jdjr, fit = Fit.FILL),
        rule("抖音", res = R.drawable.brand_douyin, fit = Fit.FILL),
        rule("美团", res = R.drawable.brand_meituan, fit = Fit.FILL),
        rule("微信", "零钱通", res = R.drawable.brand_weixinpay, fit = Fit.FILL),
        rule("支付宝", res = R.drawable.brand_zhifubao, fit = Fit.MARK),
        rule("农行", "农业银行", res = R.drawable.brand_abc, fit = Fit.MARK),
        rule("邮储", "邮政", res = R.drawable.brand_psbc, fit = Fit.MARK),
        rule("宁波银行", res = R.drawable.brand_nbcb, fit = Fit.MARK),
        rule("招商银行", "招行", res = R.drawable.brand_cmb, fit = Fit.MARK),
        rule("招商", res = R.drawable.brand_cmfund, fit = Fit.FILL, investmentOnly = true),
        rule("兴业银行", res = R.drawable.brand_cib, fit = Fit.MARK),
        rule("兴证", "兴全", res = R.drawable.brand_xqfund, fit = Fit.FILL),
        rule("SJTU", "交大", "交通大学", res = R.drawable.brand_sjtu, fit = Fit.MARK),
    )

    fun brandOf(a: Account): Brand? {
        val investment = a.kind == AccountKind.INVESTMENT
        fun match(text: String?): Brand? = text?.let { t ->
            rules.firstOrNull { r -> (!r.investmentOnly || investment) && r.keys.any { t.contains(it, ignoreCase = true) } }?.brand
        }
        return match(a.name) ?: match(a.institution)
    }

    /** The user's icon first; otherwise a drawn symbol, or null for the name's first character. */
    fun glyphOf(a: Account): ImageVector? = vectorOf(a.icon) ?: when {
        "现金" in a.name -> Icons.Rounded.Payments
        listOf("贷款", "助学", "学贷").any { it in a.name } -> Icons.Rounded.School
        listOf("信用卡", "月付").any { it in a.name } -> Icons.Rounded.CreditCard
        a.kind == AccountKind.INVESTMENT -> Icons.AutoMirrored.Rounded.ShowChart
        else -> null
    }

    /** "农行①" → "1": accounts at the same institution keep apart by their number. */
    fun badgeOf(a: Account): String? {
        val last = a.name.trim().lastOrNull() ?: return null
        return if (last in '①'..'⑳') (last - '①' + 1).toString() else null
    }
}

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * The account's tile: the institution's mark when it has one, else a symbol or the first
 * character, tinted by its group color. A trailing circled number becomes a corner badge.
 */
@Composable
fun AccountAvatar(account: Account, color: Color, modifier: Modifier = Modifier, size: Dp = 40.dp, muted: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val tint = if (muted) cs.onSurfaceVariant else color
    val shape = RoundedCornerShape(size * 0.3f)
    // A picked icon replaces the automatic brand and glyph, but the number badge still applies.
    val custom = AccountIcons.vectorOf(account.icon)
    val brand = if (custom == null) AccountIcons.brandOf(account) else null
    Box(modifier.size(size)) {
        val tile = Modifier.size(size).clip(shape)
        when {
            brand?.fit == AccountIcons.Fit.FILL -> Image(
                painterResource(brand.res), null,
                contentScale = ContentScale.Crop,
                colorFilter = if (muted) grayscale else null,
                modifier = tile.alpha(if (muted) 0.55f else 1f),
            )
            brand != null -> Box(
                tile.background(Color.White).border(1.dp, cs.outlineVariant.copy(alpha = 0.7f), shape).alpha(if (muted) 0.55f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                // The marks carry their own margin of about a sixth per side; this takes most of it back.
                Image(
                    painterResource(brand.res), null,
                    colorFilter = if (muted) grayscale else null,
                    modifier = Modifier.padding(size * 0.06f).graphicsLayer { scaleX = 1.12f; scaleY = 1.12f },
                )
            }
            else -> Box(tile.background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                val glyph = AccountIcons.glyphOf(account)
                if (glyph != null) {
                    Icon(glyph, null, tint = tint, modifier = Modifier.size(size * 0.55f))
                } else {
                    Text(account.name.trim().take(1).ifEmpty { "?" }, color = tint, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
                }
            }
        }
        AccountIcons.badgeOf(account)?.let { n ->
            val d = size * 0.4f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(d * 0.25f, d * 0.25f)
                    .size(d)
                    .clip(CircleShape)
                    .background(cs.surfaceContainer)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Text(n, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (d.value * 0.56f).sp, lineHeight = (d.value * 0.56f).sp)
            }
        }
    }
}
