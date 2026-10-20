package com.assetboard.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assetboard.ui.theme.AppTheme

/**
 * The top of each tab: a large title, one line of context under it, and round
 * action buttons on the right. [statusBar] adds the status bar inset when the header is not
 * inside something that already did.
 */
@Composable
fun TabHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    statusBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .then(if (statusBar) Modifier.statusBarsPadding() else Modifier)
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
            if (subtitle != null) {
                AnimatedContent(subtitle, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subtitle") {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A round icon button on a soft card background, the header's action style. */
@Composable
fun HeaderAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(42.dp)
            .pressScale(source, 0.9f)
            .shadow(2.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/**
 * The tab's main action, centered above the navigation bar: a glossy pill in the hero gradient
 * with the icon set in a light disc.
 */
@Composable
fun PrimaryActionButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = AppTheme.extra
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .pressScale(source, 0.93f)
            .shadow(18.dp, shape, ambientColor = extra.heroStart, spotColor = extra.heroStart)
            .clip(shape)
            .background(Brush.linearGradient(listOf(extra.heroEnd, extra.heroStart)))
            .drawWithContent {
                drawContent()
                // A soft gloss over the top half.
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), endY = size.height * 0.6f))
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .height(58.dp)
            .padding(start = 7.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(extra.onHero),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = extra.heroStart, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = extra.onHero, style = MaterialTheme.typography.titleMedium)
    }
}

/** A tinted rounded square holding a row's icon, so each settings row has its own color. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 34.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.75f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.56f))
    }
}
