package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.Gilded
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** One card in a [CardDeckContent]: what it picks, what it says and the colour its art wears. */
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
 * A deck of gilded cards standing in for a long row of lists: one card fills the screen, the
 * neighbours lean away in 3D, and a swipe or a tap brings the next one forward. Tapping the card
 * in front picks it. Used by the todo tab for its lists and by the notes tab for its topics.
 *
 * The look is a brass frame around deep enamel, with a count gemstone, a name plate and a foil
 * sweep when a card comes to the front: the app's own jewel-green-and-brass palette rather than
 * borrowed artwork.
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
    val g = AppTheme.extra.gilded
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val initial = remember(cards.size, selected) { cards.indexOfFirst { it.key == selected }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initial, pageCount = { cards.size })
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val pick by rememberUpdatedState(onPick)
    val createFocus = remember { FocusRequester() }
    // One burst as the deck is laid out; it is a reveal, not a loop, so the page stays quiet.
    val burst = remember { Animatable(0f) }
    LaunchedEffect(Unit) { burst.animateTo(1f, tween(1_250, easing = FastOutSlowInEasing)) }
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
    val accent = cards.getOrNull(pager.currentPage.coerceIn(0, (cards.size - 1).coerceAtLeast(0)))?.accent ?: g.glow
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                // A dark card table, lit from above the deck, so the brass has something to answer.
                drawRect(Brush.verticalGradient(g.table))
                drawCircle(
                    Brush.radialGradient(
                        listOf(g.tableGlow.copy(alpha = 0.46f), g.tableGlow.copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.47f), radius = w * 1.05f,
                    ),
                    w * 1.05f, Offset(w * 0.5f, h * 0.47f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.52f), radius = w * 0.72f,
                    ),
                    w * 0.72f, Offset(w * 0.5f, h * 0.52f),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.52f)),
                        center = Offset(w * 0.5f, h * 0.5f), radius = maxOf(w, h) * 0.72f,
                    ),
                )
                // A thin brass rule around the table, with a diamond at each corner.
                val inset = 9.dp.toPx()
                val radius = CornerRadius(20.dp.toPx())
                drawRoundRect(
                    g.rim[1].copy(alpha = 0.30f),
                    topLeft = Offset(inset, inset),
                    size = Size(w - inset * 2, h - inset * 2),
                    cornerRadius = radius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                )
                listOf(
                    Offset(inset + 1.dp.toPx(), inset + 1.dp.toPx()),
                    Offset(w - inset - 1.dp.toPx(), inset + 1.dp.toPx()),
                    Offset(inset + 1.dp.toPx(), h - inset - 1.dp.toPx()),
                    Offset(w - inset - 1.dp.toPx(), h - inset - 1.dp.toPx()),
                ).forEach { drawDiamond(it, 2.4.dp.toPx(), g.spark.copy(alpha = 0.6f)) }
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = g.ink, letterSpacing = 2.sp)
                        Spacer(Modifier.width(10.dp))
                        OrnamentRule(g)
                    }
                    Text(
                        if (cards.isEmpty()) emptyText else hint,
                        style = MaterialTheme.typography.bodySmall, color = g.muted, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭", tint = g.muted) }
            }
            if (cards.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText, style = MaterialTheme.typography.titleMedium, color = g.muted)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DeckAura(burst.value, accent, g, Modifier.matchParentSize())
                    HorizontalPager(
                        state = pager,
                        pageSpacing = 12.dp,
                        contentPadding = PaddingValues(horizontal = 52.dp),
                        beyondViewportPageCount = 1,
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxHeight().fillMaxWidth().padding(vertical = 12.dp),
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
                                    .heightIn(max = 470.dp)
                                    .graphicsLayer {
                                        val rel = (page - pager.currentPage) - pager.currentPageOffsetFraction
                                        val d = rel.coerceIn(-1.7f, 1.7f)
                                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        cameraDistance = 22f * density
                                        rotationY = -d * 36f
                                        rotationZ = -d * 1.2f
                                        translationY = abs(d) * 10.dp.toPx()
                                        val s = 1f - 0.12f * abs(d).coerceAtMost(1f)
                                        scaleX = s
                                        scaleY = s
                                        alpha = (1f - 0.30f * abs(d).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                                    },
                                onClick = {
                                    if (current) pick(card.key) else scope.launch { pager.animateScrollToPage(page) }
                                },
                            )
                        }
                    }
                }
                DeckDots(cards.size, pager.currentPage, g)
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
                            placeholder = { Text("名字", color = g.muted) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { createNow() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = g.ink, unfocusedTextColor = g.ink, cursorColor = g.glow,
                                focusedBorderColor = g.rim[2], unfocusedBorderColor = g.rim[1].copy(alpha = 0.45f),
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f).focusRequester(createFocus),
                        )
                        TextButton(
                            onClick = { createNow() }, enabled = name.isNotBlank(),
                            colors = ButtonDefaults.textButtonColors(contentColor = g.ink, disabledContentColor = g.muted.copy(alpha = 0.45f)),
                        ) { Text("创建") }
                        TextButton(onClick = { naming = false; name = "" }, colors = ButtonDefaults.textButtonColors(contentColor = g.muted)) { Text("取消") }
                    } else {
                        OutlinedButton(
                            onClick = { naming = true }, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, g.rim[1].copy(alpha = 0.75f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = g.ink),
                        ) { Text(createLabel) }
                    }
                }
                if (onManage != null && manageLabel != null && !naming) {
                    TextButton(
                        onClick = onManage, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(contentColor = g.ink),
                    ) { Text(manageLabel) }
                }
            }
        }
    }
}

/** A straw of brass with a lit diamond in the middle, under the deck's title. */
@Composable
private fun OrnamentRule(g: Gilded) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, g.rim[1].copy(alpha = 0.8f)))))
        Canvas(Modifier.size(9.dp)) { drawDiamond(center, size.minDimension * 0.27f, g.rim[2]) }
        Box(Modifier.width(26.dp).height(1.dp).background(Brush.horizontalGradient(listOf(g.rim[1].copy(alpha = 0.8f), Color.Transparent))))
    }
}

/** The light the deck sheds: a pool under the cards and one short burst as it opens. */
@Composable
private fun DeckAura(progress: Float, accent: Color, g: Gilded, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.5f, h * 0.5f)
        drawCircle(
            Brush.radialGradient(
                listOf(g.glow.copy(alpha = 0.22f), accent.copy(alpha = 0.10f), Color.Transparent),
                center = center, radius = w * 0.68f,
            ),
            w * 0.68f, center,
        )
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f || p >= 1f) return@Canvas
        val fade = 1f - p
        val ring = w * (0.18f + 0.55f * p)
        drawCircle(g.glow.copy(alpha = 0.34f * fade), ring, center, style = androidx.compose.ui.graphics.drawscope.Stroke((0.6f + 2.2f * fade).dp.toPx()))
        repeat(14) { i ->
            val angle = (i / 14f) * 2f * PI.toFloat() + 0.18f
            val inner = w * 0.14f
            val outer = w * (0.32f + 0.34f * p)
            drawLine(
                g.spark.copy(alpha = 0.17f * fade),
                center + Offset(cos(angle) * inner, sin(angle) * inner),
                center + Offset(cos(angle) * outer, sin(angle) * outer),
                strokeWidth = (1.1f * fade + 0.3f).dp.toPx(), cap = StrokeCap.Round,
            )
        }
        val rnd = Random(7)
        repeat(20) {
            val angle = rnd.nextFloat() * 2f * PI.toFloat()
            val r = w * (0.08f + 0.45f * p) * (0.4f + rnd.nextFloat() * 0.8f)
            drawCircle(
                g.spark.copy(alpha = 0.55f * fade),
                (0.8f + 1.4f * fade).dp.toPx(),
                center + Offset(cos(angle) * r, sin(angle) * r * 0.8f),
            )
        }
    }
}

/** A count of the deck in dots, until there are too many for dots to read. */
@Composable
private fun DeckDots(count: Int, current: Int, g: Gilded) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count <= 10) {
            repeat(count) { i ->
                val on = i == current
                Box(
                    Modifier.padding(horizontal = 3.dp)
                        .size(width = if (on) 16.dp else 5.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(if (on) Brush.horizontalGradient(listOf(g.rim[1], g.rim[2], g.rim[1])) else Brush.horizontalGradient(listOf(g.muted.copy(alpha = 0.35f), g.muted.copy(alpha = 0.35f)))),
                )
            }
        } else {
            Text("${current + 1} / $count", style = MaterialTheme.typography.labelLarge, color = g.muted)
        }
    }
}

/**
 * One gilded card: brushed-brass frame, deep enamel face, a window of painted light around the
 * card's own colour, a brass name plate and a gemstone with its count. The front card catches a
 * foil sweep as it comes forward; opening the deck flips every card up off the table.
 */
@Composable
private fun DeckCardView(card: DeckCard, current: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = AppTheme.extra.gilded
    val source = remember { MutableInteractionSource() }
    val outer = RoundedCornerShape(26.dp)
    val inner = RoundedCornerShape(21.dp)
    val enter = remember { Animatable(0f) }
    val foil = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(640, easing = FastOutSlowInEasing)) }
    LaunchedEffect(card.key, current) {
        if (current) {
            foil.snapTo(0f)
            foil.animateTo(1f, tween(1_050, delayMillis = 160, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier
            .graphicsLayer {
                // The card is dealt up from the table as the deck appears.
                val e = enter.value
                rotationY += (1f - e) * 68f
                rotationX = (1f - e) * 8f
                translationY += (1f - e) * 46.dp.toPx()
                scaleX *= 0.93f + 0.07f * e
                scaleY *= 0.93f + 0.07f * e
                alpha *= e
            }
            .testTag("deck:${card.key}")
            .pressScale(source, 0.975f)
            .shadow(
                if (current) 26.dp else 9.dp, outer,
                ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = g.glow.copy(alpha = 0.55f),
            )
            .clip(outer)
            .background(Brush.linearGradient(g.rim))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Canvas(Modifier.matchParentSize()) { drawMetalSheen() }
        Column(
            Modifier.fillMaxSize().padding(6.dp).clip(inner)
                .background(Brush.verticalGradient(g.face))
                .padding(8.dp),
        ) {
            ArtWindow(card, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(8.dp))
            NamePlate(card.title, g)
            if (card.footnote != null) {
                Text(
                    card.footnote, style = MaterialTheme.typography.bodySmall, color = g.muted,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 6.dp, end = 6.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape).background(Brush.linearGradient(g.plate)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Check, null, tint = g.onPlate, modifier = Modifier.size(12.dp)) }
                        Spacer(Modifier.width(7.dp))
                        Text("正在使用", style = MaterialTheme.typography.labelLarge, color = g.ink)
                    }
                } else {
                    Text("轻点打开", style = MaterialTheme.typography.labelLarge, color = g.muted.copy(alpha = 0.85f))
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            drawCardOrnaments(g)
            drawFoilSweep(foil.value, g)
        }
    }
}

/** The painted window: the card's colour, lit from above, with a crest and a count gem. */
@Composable
private fun ArtWindow(card: DeckCard, modifier: Modifier = Modifier) {
    val g = AppTheme.extra.gilded
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.clip(shape).border(1.dp, g.rim[1].copy(alpha = 0.55f), shape)) {
        Canvas(Modifier.matchParentSize()) { drawCardArt(card.accent, card.key, g) }
        ArtCrest(card.icon, card.accent, g, Modifier.align(Alignment.Center))
        // A soft floor so the gem sits on the picture rather than floats.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                ),
            ),
        )
        Canvas(Modifier.align(Alignment.TopStart).padding(12.dp).size(16.dp)) {
            drawStar(center, size.minDimension * 0.5f, g.spark.copy(alpha = 0.65f))
        }
        Row(Modifier.fillMaxWidth().padding(10.dp)) {
            Spacer(Modifier.weight(1f))
            if (card.count != null) CountGem(card.count, g)
        }
    }
}

/** The card's emblem: its icon struck in a brass ring, with a diamond outline behind it. */
@Composable
private fun ArtCrest(icon: ImageVector, accent: Color, g: Gilded, modifier: Modifier = Modifier) {
    Box(modifier.size(164.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(
                Brush.radialGradient(
                    listOf(lerp(accent, Color.White, 0.55f).copy(alpha = 0.34f), Color.Transparent),
                    center = c, radius = r,
                ),
                r, c,
            )
            drawCircle(g.spark.copy(alpha = 0.52f), r * 0.91f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
            drawCircle(g.rim[1].copy(alpha = 0.46f), r * 0.98f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(0.7.dp.toPx()))
            repeat(8) { i ->
                val a = i * PI.toFloat() / 4f + PI.toFloat() / 8f
                drawDiamond(c + Offset(cos(a) * r * 0.91f, sin(a) * r * 0.91f), 2.2.dp.toPx(), g.spark.copy(alpha = 0.8f))
            }
            // A diamond drawn just outside the medallion, woven through its ring.
            val d = r * 0.70f
            Path().apply {
                moveTo(c.x, c.y - d)
                lineTo(c.x + d * 0.78f, c.y)
                lineTo(c.x, c.y + d)
                lineTo(c.x - d * 0.78f, c.y)
                close()
            }.also { drawPath(it, g.spark.copy(alpha = 0.16f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx())) }
        }
        Box(
            Modifier.size(76.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(lerp(accent, Color.Black, 0.24f), g.face[2])))
                .border(1.dp, g.rim[1].copy(alpha = 0.62f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = g.ink, modifier = Modifier.size(36.dp))
        }
    }
}

/** The count in a cut gemstone: brass rim, enamel heart, brass figures. */
@Composable
private fun CountGem(count: Int, g: Gilded) {
    Box(
        Modifier.size(34.dp)
            .shadow(8.dp, CircleShape, spotColor = g.glow.copy(alpha = 0.55f))
            .clip(CircleShape)
            .background(Brush.linearGradient(g.gem))
            .padding(2.5.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(lerp(g.face[0], Color.White, 0.16f), g.face[2]))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge.merge(com.assetboard.ui.theme.NumberStyle),
            color = g.ink, fontWeight = FontWeight.Bold,
        )
    }
}

/** The name on a strip of lit brass, the way a card is titled. */
@Composable
private fun NamePlate(title: String, g: Gilded) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp)
            .shadow(5.dp, shape, spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .background(Brush.verticalGradient(g.plate))
            .border(1.dp, g.rimDark.copy(alpha = 0.65f), shape),
        contentAlignment = Alignment.Center,
    ) {
        // A dark line inside each long edge keeps the brass from reading as flat yellow.
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter).background(Color.White.copy(alpha = 0.5f)))
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(g.rimDark.copy(alpha = 0.35f)))
        Canvas(Modifier.matchParentSize()) {
            // The ends sit in shade, so the plate reads as a rounded bar rather than a filled box.
            drawRect(
                Brush.horizontalGradient(
                    listOf(g.rimDark.copy(alpha = 0.30f), Color.Transparent, Color.Transparent, g.rimDark.copy(alpha = 0.30f)),
                ),
            )
            drawDiamond(Offset(11.dp.toPx(), size.height / 2f), 2.2.dp.toPx(), g.rimDark.copy(alpha = 0.45f))
            drawDiamond(Offset(size.width - 11.dp.toPx(), size.height / 2f), 2.2.dp.toPx(), g.rimDark.copy(alpha = 0.45f))
        }
        Text(
            title, style = MaterialTheme.typography.titleLarge, color = g.onPlate,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 6.dp),
        )
    }
}

/** Brushed metal: broad bands of light and shade across the frame. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMetalSheen() {
    drawRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.34f),
            0.16f to Color.White.copy(alpha = 0.05f),
            0.36f to Color.Black.copy(alpha = 0.24f),
            0.55f to Color.White.copy(alpha = 0.14f),
            0.74f to Color.Black.copy(alpha = 0.20f),
            1f to Color.White.copy(alpha = 0.26f),
            start = Offset.Zero, end = Offset(size.width, size.height),
        ),
    )
}

/** A window of painted light: the card's colour, rays from above, dust and a vignette. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCardArt(accent: Color, key: String, g: Gilded) {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.linearGradient(
            listOf(lerp(accent, Color.Black, 0.12f), lerp(accent, Color.Black, 0.48f), lerp(accent, Color.Black, 0.78f)),
            start = Offset(0f, 0f), end = Offset(w * 0.35f, h),
        ),
    )
    drawCircle(
        Brush.radialGradient(
            listOf(lerp(accent, Color.White, 0.58f).copy(alpha = 0.55f), Color.Transparent),
            center = Offset(w * 0.72f, h * 0.16f), radius = w * 0.78f,
        ),
        w * 0.78f, Offset(w * 0.72f, h * 0.16f),
    )
    val rnd = Random(key.hashCode())
    // Rays falling from behind the crest, like light through a window.
    val cx = w * 0.5f
    repeat(11) { i ->
        val t = (i - 5) / 5f
        val tip = cx + t * w * 1.25f
        Path().apply {
            moveTo(cx - w * 0.05f, 0f)
            lineTo(cx + w * 0.05f, 0f)
            lineTo(tip + w * 0.10f, h * 1.2f)
            lineTo(tip - w * 0.10f, h * 1.2f)
            close()
        }.let { drawPath(it, Color.White.copy(alpha = 0.030f + 0.018f * rnd.nextFloat())) }
    }
    repeat(18) {
        drawCircle(
            g.spark.copy(alpha = 0.08f + 0.18f * rnd.nextFloat()),
            (0.6f + 1.6f * rnd.nextFloat()).dp.toPx(),
            Offset(rnd.nextFloat() * w, rnd.nextFloat() * h),
        )
    }
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), startY = h * 0.36f, endY = h))
    drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent, Color.Black.copy(alpha = 0.24f))))
}

/** The frame's cut line, corner curls and crests. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCardOrnaments(g: Gilded) {
    val inset = 7.dp.toPx()
    val corner = CornerRadius(20.dp.toPx())
    val stroke = 1.dp.toPx()
    drawRoundRect(
        g.rimDark.copy(alpha = 0.72f),
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = corner, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
    )
    drawRoundRect(
        Brush.linearGradient(
            listOf(g.spark.copy(alpha = 0.55f), g.rim.first().copy(alpha = 0.18f), g.spark.copy(alpha = 0.38f), g.rim.first().copy(alpha = 0.16f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        ),
        topLeft = Offset(inset + 1.2.dp.toPx(), inset + 1.2.dp.toPx()),
        size = Size(size.width - inset * 2 - 2.4.dp.toPx(), size.height - inset * 2 - 2.4.dp.toPx()),
        cornerRadius = corner, style = androidx.compose.ui.graphics.drawscope.Stroke(0.7.dp.toPx()),
    )
    val pad = 12.dp.toPx()
    val len = 14.dp.toPx()
    listOf(
        Triple(Offset(pad, pad), 1f, 1f),
        Triple(Offset(size.width - pad, pad), -1f, 1f),
        Triple(Offset(pad, size.height - pad), 1f, -1f),
        Triple(Offset(size.width - pad, size.height - pad), -1f, -1f),
    ).forEach { (at, sx, sy) ->
        val path = Path().apply {
            moveTo(at.x + sx * len, at.y)
            quadraticBezierTo(at.x + sx * len * 0.18f, at.y, at.x, at.y + sy * len)
        }
        drawPath(path, g.rimDark.copy(alpha = 0.9f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, g.spark.copy(alpha = 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.6.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(g.spark.copy(alpha = 0.85f), 1.4.dp.toPx(), Offset(at.x + sx * 3.5.dp.toPx(), at.y + sy * 3.5.dp.toPx()))
    }
    drawDiamond(Offset(size.width / 2f, 6.5.dp.toPx()), 3.6.dp.toPx(), g.rimDark)
    drawDiamond(Offset(size.width / 2f, 6.5.dp.toPx()), 2.7.dp.toPx(), g.rim[2])
    drawDiamond(Offset(size.width / 2f, size.height - 6.5.dp.toPx()), 3.2.dp.toPx(), g.rimDark)
    drawDiamond(Offset(size.width / 2f, size.height - 6.5.dp.toPx()), 2.3.dp.toPx(), g.rim[2])
}

/** A band of light travelling across the brass; [progress] runs zero to one. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFoilSweep(progress: Float, g: Gilded) {
    if (progress <= 0f || progress >= 1f) return
    val w = size.width
    val band = w * 0.55f
    val x = -band + (w + band * 2f) * progress
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.40f to g.spark.copy(alpha = 0.02f),
            0.50f to g.spark.copy(alpha = 0.13f),
            0.60f to g.spark.copy(alpha = 0.02f),
            1f to Color.Transparent,
            start = Offset(x - band, 0f), end = Offset(x + band, size.height),
        ),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDiamond(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius, center.y)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius, center.y)
        close()
    }
    drawPath(path, color)
}

/** A four-pointed star, for the small ornaments in the corners of the art. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val path = Path()
    repeat(8) { i ->
        val angle = -PI.toFloat() / 2f + i * PI.toFloat() / 4f
        val r = if (i % 2 == 0) radius else radius * 0.30f
        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

/**
 * The compact way into a deck: a small gilded bar with a fan of cards, the deck's name, how many
 * there are and what is showing now. It takes one row where the cards took one row each, and is
 * as wide as the notes or todos under it.
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
    val g = AppTheme.extra.gilded
    val source = remember { MutableInteractionSource() }
    val outer = RoundedCornerShape(17.dp)
    val inner = RoundedCornerShape(14.dp)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(1_150, delayMillis = 220, easing = FastOutSlowInEasing)) }
    Box(
        modifier
            .fillMaxWidth()
            .pressScale(source, 0.985f)
            .shadow(10.dp, outer, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = g.glow.copy(alpha = 0.4f))
            .clip(outer)
            .background(Brush.linearGradient(g.rim))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .height(58.dp),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawMetalSheen()
            drawCardOrnaments(g)
            drawFoilSweep(sweep.value, g)
        }
        Row(
            Modifier.fillMaxSize().padding(2.5.dp).clip(inner)
                .background(Brush.horizontalGradient(g.face))
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeckStack(accents, g)
            Spacer(Modifier.width(11.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = g.ink, letterSpacing = 0.8.sp)
            Spacer(Modifier.width(8.dp))
            CountGemSmall(count, g)
            Spacer(Modifier.weight(1f))
            if (current != null) {
                Text(
                    current, style = MaterialTheme.typography.bodySmall, color = g.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = g.rim[2], modifier = Modifier.size(20.dp))
        }
    }
}

/** The count in a small gem on the bar. */
@Composable
private fun CountGemSmall(count: Int, g: Gilded) {
    Box(
        Modifier.size(23.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(g.gem))
            .padding(1.5.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(lerp(g.face[0], Color.White, 0.14f), g.face[2]))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall.merge(com.assetboard.ui.theme.NumberStyle),
            color = g.ink, fontWeight = FontWeight.Bold,
        )
    }
}

/** Three little gilded cards fanned out: the deck seen edge-on. */
@Composable
private fun DeckStack(accents: List<Color>, g: Gilded) {
    val colors = accents.takeLast(3).ifEmpty { listOf(g.rim[1]) }
    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        colors.forEachIndexed { i, color ->
            val back = colors.lastIndex - i
            Box(
                Modifier
                    .size(18.dp, 25.dp)
                    .graphicsLayer {
                        translationX = (back - (colors.size - 1) / 2f) * 7.5.dp.toPx()
                        translationY = back * 1.5.dp.toPx()
                        rotationZ = (back - (colors.size - 1) / 2f) * -9f
                    }
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.linearGradient(g.rim))
                    .padding(1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        // The backs are gilded too; the list's colour only warms the enamel.
                        Brush.linearGradient(
                            listOf(lerp(g.face[0], color, 0.30f), lerp(g.face[2], color, 0.16f)),
                        ),
                    ),
            ) {
                // A struck diamond and a hairline make even one card read as a card, not a chip.
                Canvas(Modifier.fillMaxSize()) {
                    drawDiamond(Offset(size.width / 2f, size.height * 0.70f), 1.6.dp.toPx(), g.spark.copy(alpha = 0.85f))
                    drawLine(
                        g.spark.copy(alpha = 0.5f),
                        Offset(size.width * 0.28f, size.height * 0.26f),
                        Offset(size.width * 0.72f, size.height * 0.26f),
                        strokeWidth = 0.7.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
