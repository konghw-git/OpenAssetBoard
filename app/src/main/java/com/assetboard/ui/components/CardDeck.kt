package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One card in a [CardDeckContent]: what it picks, what it says and the colour it wears. */
@Immutable
data class DeckCard(
    val key: String,
    val title: String,
    val count: Int? = null,
    val accent: Color,
    val icon: ImageVector,
    val footnote: String? = null,
)

/**
 * A deck of cards standing in for a long row of lists: one card fills the screen, the neighbours
 * lean away in 3D, and a swipe or a tap brings the next one forward. Tapping the card in front
 * picks it. Used by the todo tab for its lists and by the notes tab for its topics.
 */
@Composable
fun CardDeckDialog(
    title: String,
    cards: List<DeckCard>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String = "左右滑动挑一张，点一下打开",
    createLabel: String? = null,
    onCreate: ((String) -> Unit)? = null,
    manageLabel: String? = null,
    onManage: (() -> Unit)? = null,
    emptyText: String = "还没有内容",
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CardDeckContent(
            title = title, cards = cards, selected = selected, onPick = onPick, onDismiss = onDismiss,
            hint = hint, createLabel = createLabel, onCreate = onCreate,
            manageLabel = manageLabel, onManage = onManage, emptyText = emptyText,
        )
    }
}

@Composable
fun CardDeckContent(
    title: String,
    cards: List<DeckCard>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "左右滑动挑一张，点一下打开",
    createLabel: String? = null,
    onCreate: ((String) -> Unit)? = null,
    manageLabel: String? = null,
    onManage: (() -> Unit)? = null,
    emptyText: String = "还没有内容",
) {
    val cs = MaterialTheme.colorScheme
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val initial = remember(cards.size, selected) { cards.indexOfFirst { it.key == selected }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initial, pageCount = { cards.size })
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val pick by rememberUpdatedState(onPick)
    val createFocus = remember { FocusRequester() }
    LaunchedEffect(naming) { if (naming) runCatching { createFocus.requestFocus() } }
    fun createNow() {
        val n = name.trim()
        if (n.isNotEmpty()) {
            name = ""
            naming = false
            onCreate?.invoke(n)
        }
    }
    LaunchedEffect(pager.currentPage) {
        if (cards.isNotEmpty()) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val accent = cards.getOrNull(pager.currentPage.coerceIn(0, (cards.size - 1).coerceAtLeast(0)))?.accent ?: cs.primary
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(cs.surface.copy(alpha = 0.985f), lerp(cs.surface, accent, 0.09f).copy(alpha = 0.985f)),
                ),
            )
            .drawBehind {
                // A quiet pool of the front card's colour under the deck.
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.56f),
                        radius = size.width * 0.75f,
                    ),
                    size.width * 0.75f,
                    Offset(size.width / 2f, size.height * 0.56f),
                )
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (cards.isEmpty()) emptyText else hint,
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭", tint = cs.onSurfaceVariant) }
            }
            if (cards.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HorizontalPager(
                        state = pager,
                        pageSpacing = 14.dp,
                        contentPadding = PaddingValues(horizontal = 64.dp),
                        beyondViewportPageCount = 1,
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxHeight().fillMaxWidth().padding(vertical = 10.dp),
                    ) { page ->
                        val card = cards[page]
                        val current = page == pager.currentPage
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            DeckCardView(
                                card,
                                current = current,
                                selected = card.key == selected,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 480.dp)
                                    .graphicsLayer {
                                        val rel = (page - pager.currentPage) - pager.currentPageOffsetFraction
                                        val d = rel.coerceIn(-1.7f, 1.7f)
                                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        cameraDistance = 20f * density
                                        rotationY = -d * 40f
                                        rotationZ = -d * 1.6f
                                        translationY = abs(d) * 12.dp.toPx()
                                        val s = 1f - 0.14f * abs(d).coerceAtMost(1f)
                                        scaleX = s
                                        scaleY = s
                                        alpha = (1f - 0.32f * abs(d).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                                    },
                                onClick = {
                                    if (current) pick(card.key) else scope.launch { pager.animateScrollToPage(page) }
                                },
                            )
                        }
                    }
                }
                DeckDots(cards.size, pager.currentPage)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onCreate != null && createLabel != null) {
                    if (naming) {
                        OutlinedTextField(
                            name, { name = it.take(40) },
                            placeholder = { Text("名字") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { createNow() }),
                            modifier = Modifier.weight(1f).focusRequester(createFocus),
                        )
                        TextButton(onClick = { createNow() }, enabled = name.isNotBlank()) { Text("创建") }
                        TextButton(onClick = { naming = false; name = "" }) { Text("取消") }
                    } else {
                        OutlinedButton(onClick = { naming = true }, modifier = Modifier.weight(1f)) { Text(createLabel) }
                    }
                }
                if (onManage != null && manageLabel != null && !naming) {
                    TextButton(onClick = onManage, modifier = Modifier.weight(1f)) { Text(manageLabel) }
                }
            }
        }
    }
}

/** A count of the deck in dots, until there are too many for dots to read. */
@Composable
private fun DeckDots(count: Int, current: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count <= 10) {
            repeat(count) { i ->
                val on = i == current
                Box(
                    Modifier.padding(horizontal = 3.dp).size(if (on) 8.dp else 5.dp).clip(CircleShape)
                        .background(if (on) cs.primary else cs.outline.copy(alpha = 0.4f)),
                )
            }
        } else {
            Text("${current + 1} / $count", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * The one card in front: its colour a soft gradient, its icon, its name and count. Tapped, the
 * front one is picked and a side one slides forward.
 */
@Composable
private fun DeckCardView(card: DeckCard, current: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(30.dp)
    // Dark cards take white ink; the pale ones of the dark palette take dark ink.
    val ink = if (card.accent.luminance() > 0.55f) Color(0xFF15201B) else Color.White
    val enter = remember { Animatable(1f) }
    LaunchedEffect(Unit) { enter.animateTo(0f, tween(460, easing = FastOutSlowInEasing)) }
    Column(
        modifier
            .graphicsLayer {
                // The front card swings in from its right edge as the sheet opens.
                rotationY += enter.value * 55f
                alpha *= 1f - enter.value
            }
            .testTag("deck:${card.key}")
            .pressScale(source, 0.975f)
            .shadow(if (current) 24.dp else 8.dp, shape, spotColor = card.accent.copy(alpha = 0.7f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(card.accent, lerp(card.accent, Color.Black, 0.30f))))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.07f), size.width * 0.7f, Offset(size.width * 1.02f, -size.height * 0.06f))
                drawCircle(Color.White.copy(alpha = 0.05f), size.width * 0.5f, Offset(-size.width * 0.08f, size.height * 1.02f))
            }
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(card.icon, null, tint = ink, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            if (card.count != null) {
                Text(
                    card.count.toString(),
                    style = MaterialTheme.typography.titleMedium.merge(com.assetboard.ui.theme.NumberStyle),
                    color = ink,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            card.title, style = MaterialTheme.typography.headlineMedium, color = ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (card.footnote != null) {
            Text(card.footnote, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.78f), modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(ink),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Check, null, tint = card.accent, modifier = Modifier.size(13.dp)) }
                Spacer(Modifier.width(7.dp))
                Text("正在使用", style = MaterialTheme.typography.labelLarge, color = ink)
            } else {
                Text("点一下打开", style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = 0.72f))
            }
        }
    }
}

/**
 * The compact way into a deck: a glass pill with a little stack of coloured cards, the deck's
 * name, how many there are and what is showing now. It takes one row where the cards took one
 * row each.
 */
@Composable
fun DeckButton(
    label: String,
    current: String?,
    count: Int,
    accents: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(source, 0.985f)
            .glass(shape, glass, elevation = 4.dp)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .height(52.dp)
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeckStack(accents)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = cs.primary,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primary.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        if (current != null) {
            Text(current, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Default.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Three little cards fanned out: the deck seen edge-on. */
@Composable
private fun DeckStack(accents: List<Color>) {
    val cs = MaterialTheme.colorScheme
    val colors = accents.takeLast(3).ifEmpty { listOf(cs.primary) }
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        colors.forEachIndexed { i, color ->
            val back = colors.lastIndex - i
            Box(
                Modifier
                    .size(17.dp, 23.dp)
                    .graphicsLayer {
                        translationX = (back - (colors.size - 1) / 2f) * 7.dp.toPx()
                        translationY = back * 1.5.dp.toPx()
                        rotationZ = (back - (colors.size - 1) / 2f) * -9f
                    }
                    .clip(RoundedCornerShape(5.dp))
                    .background(color.copy(alpha = 0.55f + 0.15f * i))
                    .then(Modifier),
            )
        }
        Box(Modifier.size(17.dp, 23.dp).clip(RoundedCornerShape(5.dp)).background(colors.last().copy(alpha = 0.92f))) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.35f)))
        }
    }
}
