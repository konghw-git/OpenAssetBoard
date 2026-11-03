package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewCarousel
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.Gilded
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * One card in a [CardDeckContent]: what it picks, what it says and the colour its art wears.
 * [countLabel] says what the gemstone's number counts; [preview] is a few lines from inside the
 * card, so the deck can say what a card holds without being opened; [previewEmpty] is said
 * instead when there is nothing to list.
 */
@Immutable
data class DeckCard(
    val key: String,
    val title: String,
    val count: Int? = null,
    val accent: Color,
    val icon: ImageVector,
    val footnote: String? = null,
    val countLabel: String? = null,
    val preview: List<String> = emptyList(),
    val previewEmpty: String? = null,
)

/**
 * A deck of gilded cards standing in for a long row of lists: a short carousel shows the card in
 * front and its neighbours leaning away in 3D, a swipe (or a tap on a neighbour) turns the deck,
 * and a grid lays every card out at once when there are too many to walk through. Tapping the
 * card in front picks it. Used by the todo tab for its lists and by the notes tab for its topics.
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
    hint: String = "左右滑动浏览，点中间那张打开",
    gridHint: String = "点一张卡直接打开",
    openVerb: String = "打开",
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
            hint = hint, gridHint = gridHint, openVerb = openVerb, createLabel = createLabel, onCreate = onCreate,
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
    hint: String = "左右滑动浏览，点中间那张打开",
    gridHint: String = "点一张卡直接打开",
    openVerb: String = "打开",
    createLabel: String? = null,
    onCreate: ((String) -> Unit)? = null,
    manageLabel: String? = null,
    onManage: (() -> Unit)? = null,
    emptyText: String = "还没有内容",
) {
    val g = AppTheme.extra.gilded
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    // Many cards are quicker to pick from a page of the whole deck than from a carousel; the
    // toggle in the header swaps between the two and remembers the choice while this deck is open.
    // Keyed on the threshold so a deck whose data arrives after it opened still starts on the
    // page when it turns out to be long; a toggle on a steady deck is kept.
    var grid by rememberSaveable(cards.size > 6) { mutableStateOf(cards.size > 6) }
    val scope = rememberCoroutineScope()
    val createFocus = remember { FocusRequester() }
    val accent = cards.firstOrNull { it.key == selected }?.accent ?: cards.firstOrNull()?.accent ?: g.glow
    // One burst as the deck is laid out; it is a reveal, not a loop, so the page stays quiet.
    val burst = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        burst.animateTo(1f, tween(1_250, easing = FastOutSlowInEasing))
        reveal.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(naming) { if (naming) runCatching { createFocus.requestFocus() } }
    fun createNow() {
        val n = name.trim()
        if (n.isNotEmpty()) {
            name = ""
            naming = false
            onCreate?.invoke(n)
        }
    }
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
                        center = Offset(w * 0.5f, h * 0.45f), radius = w * 1.05f,
                    ),
                    w * 1.05f, Offset(w * 0.5f, h * 0.45f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.46f), radius = w * 0.72f,
                    ),
                    w * 0.72f, Offset(w * 0.5f, h * 0.46f),
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
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = g.ink, letterSpacing = 2.sp)
                        Spacer(Modifier.width(10.dp))
                        OrnamentRule(g)
                        if (cards.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "共 ${cards.size} 张", style = MaterialTheme.typography.labelMedium,
                                color = g.muted.copy(alpha = 0.9f),
                            )
                        }
                    }
                    Text(
                        when {
                            cards.isEmpty() -> emptyText
                            grid -> gridHint
                            else -> hint
                        },
                        style = MaterialTheme.typography.bodySmall, color = g.muted, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (cards.isNotEmpty()) {
                    IconButton(onClick = { grid = !grid }) {
                        Icon(
                            if (grid) Icons.Outlined.ViewCarousel else Icons.Outlined.GridView,
                            if (grid) "环绕展示" else "网格展示", tint = g.rim[2],
                        )
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭", tint = g.muted) }
            }
            if (cards.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText, style = MaterialTheme.typography.titleMedium, color = g.muted)
                }
            } else if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .graphicsLayer {
                            alpha = reveal.value
                            translationY = (1f - reveal.value) * 24.dp.toPx()
                        },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(cards, key = { it.key }) { card ->
                        GridDeckCard(
                            card = card,
                            selected = card.key == selected,
                            onClick = { onPick(card.key) },
                            modifier = Modifier.fillMaxWidth().testTag("deck:${card.key}"),
                        )
                    }
                }
            } else {
                CarouselPane(
                    cards = cards,
                    selected = selected,
                    onPick = onPick,
                    openVerb = openVerb,
                    burst = burst,
                    reveal = reveal,
                    g = g,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
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

/**
 * The carousel and what the card in front holds. The cards are small enough to see three at a
 * time, and the row snaps one card to the middle, so a swipe never leaves the deck between two
 * cards the way the old full-page pager did. [reveal] fades the pane in; [burst] is the one puff
 * of light as the deck opens.
 *
 * The row is a [LazyRow] with content padding and a snap fling, in the manner of compose-coverflow:
 * the padding gives every card a slot whose centre is the screen's centre, and the 3D transforms
 * are read in `graphicsLayer`, so scrolling invalidates a layer instead of recomposing each card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarouselPane(
    cards: List<DeckCard>,
    selected: String?,
    onPick: (String) -> Unit,
    openVerb: String,
    burst: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    reveal: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    g: Gilded,
    modifier: Modifier = Modifier,
) {
    val initial = remember(cards, selected) { cards.indexOfFirst { it.key == selected }.coerceAtLeast(0) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val centered by remember(state, cards.size) {
        derivedStateOf {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo
                .asSequence()
                .filter { it.index in cards.indices }
                .minByOrNull { abs(it.offset + it.size / 2f - center) }
                ?.index ?: initial
        }
    }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(centered) {
        if (cards.isNotEmpty()) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val current = cards.getOrNull(centered.coerceIn(0, cards.lastIndex))
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val byWidth = maxWidth * 0.42f
        // The spotlight is part of the same block, so leave it room before growing the cards; the
        // block is centred as a whole, instead of the cards floating in all the spare height.
        val byHeight = (maxHeight - 220.dp) / 1.42f
        val cardWidth = minOf(byWidth, byHeight).coerceIn(120.dp, 212.dp)
        val cardHeight = cardWidth * 1.42f
        val edge = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
        val unit = LocalDensity.current
        val viewportPx = with(unit) { maxWidth.toPx() }
        val cardPx = with(unit) { cardWidth.toPx() }
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 24.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(cardHeight + 30.dp)) {
                DeckAura(burst.value, current?.accent ?: g.glow, g, Modifier.matchParentSize())
                LazyRow(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(horizontal = edge),
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
                ) {
                    itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
                        // The item's position is read inside graphicsLayer, so scrolling only
                        // invalidates the layer instead of recomposing the card on every frame.
                        var x by remember(card.key) { mutableStateOf<Float?>(null) }
                        val isFront = index == centered
                        Box(
                            Modifier
                                .width(cardWidth)
                                .fillMaxHeight()
                                .onGloballyPositioned { coordinates -> x = coordinates.positionInParent().x }
                                .zIndex(if (isFront) 2f else 1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            DeckCardView(
                                card,
                                current = isFront,
                                selected = card.key == selected,
                                modifier = Modifier
                                    .requiredWidth(cardWidth)
                                    .requiredHeight(cardHeight)
                                    .graphicsLayer {
                                        val e = x?.let {
                                            (((it + cardPx / 2f - viewportPx / 2f) / cardPx).coerceIn(-1.8f, 1.8f))
                                                .coerceIn(-1f, 1f)
                                        } ?: 0f
                                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        cameraDistance = 18f * density
                                        rotationY = -e * 36f
                                        rotationZ = -e * 1.2f
                                        translationX = e * cardPx * 0.03f
                                        translationY = abs(e) * 10.dp.toPx()
                                        val s = 1f - 0.14f * abs(e)
                                        scaleX = s
                                        scaleY = s
                                        alpha = if (x == null) 0f else 1f - 0.18f * abs(e)
                                    },
                                onClick = {
                                    if (isFront) onPick(card.key) else scope.launch { state.animateScrollToItem(index) }
                                },
                            )
                        }
                    }
                }
            }
            DeckDots(cards.size, centered, g)
            current?.let {
                DeckSpotlight(
                    card = it,
                    selected = it.key == selected,
                    openVerb = openVerb,
                    g = g,
                    onOpen = { onPick(it.key) },
                )
            }
        }
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
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
 * What is in the card in front: a few names from inside it, so choosing between two lists means
 * reading their contents instead of opening each one. The button opens the card.
 */
@Composable
private fun DeckSpotlight(card: DeckCard, selected: Boolean, openVerb: String, g: Gilded, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = g.glow.copy(alpha = 0.35f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(g.face[0], g.face[2])))
            .border(
                1.dp,
                Brush.linearGradient(listOf(g.rim[1].copy(alpha = 0.75f), g.rimDark.copy(alpha = 0.65f), g.rim[2].copy(alpha = 0.55f))),
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(7.dp)) { drawDiamond(center, size.minDimension * 0.5f, g.rim[2]) }
            Spacer(Modifier.width(7.dp))
            Text(
                if (selected) "当前使用" else "当前选中",
                style = MaterialTheme.typography.labelMedium, color = g.muted, letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            if (card.count != null) {
                Text(
                    card.countLabel ?: "共 ${card.count} 项", style = MaterialTheme.typography.labelMedium,
                    color = g.muted.copy(alpha = 0.9f),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        if (card.preview.isEmpty()) {
            Text(
                card.previewEmpty ?: "里面还没有内容",
                style = MaterialTheme.typography.bodyMedium, color = g.muted.copy(alpha = 0.9f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            card.preview.take(3).forEach { line ->
                Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(5.dp)) { drawDiamond(center, size.minDimension * 0.5f, g.rim[2]) }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        line, style = MaterialTheme.typography.bodyMedium, color = g.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxWidth().height(38.dp)
                .pressScale(source, 0.98f)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.verticalGradient(g.plate))
                .clickable(interactionSource = source, indication = null, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$openVerb「${card.title}」", style = MaterialTheme.typography.titleSmall,
                    color = g.onPlate, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.KeyboardArrowRight, null, tint = g.onPlate, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * A card in the grid: the same brass and enamel, cut smaller, with the name, its count and one
 * quiet line under it. The card in use wears a check instead of a gem.
 */
@Composable
private fun GridDeckCard(card: DeckCard, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = AppTheme.extra.gilded
    val source = remember { MutableInteractionSource() }
    val outer = RoundedCornerShape(18.dp)
    val inner = RoundedCornerShape(14.dp)
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .aspectRatio(0.76f)
            .pressScale(source, 0.97f)
            .shadow(
                if (selected) 14.dp else 8.dp, outer,
                ambientColor = Color.Black.copy(alpha = 0.32f), spotColor = g.glow.copy(alpha = if (selected) 0.55f else 0.30f),
            )
            .clip(outer)
            .background(Brush.linearGradient(g.rim))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Canvas(Modifier.matchParentSize()) { drawMetalSheen() }
        Column(
            Modifier.fillMaxSize().padding(4.dp).clip(inner)
                .background(Brush.verticalGradient(g.face))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(shape)
                    .border(1.dp, g.rim[1].copy(alpha = 0.55f), shape),
            ) {
                Canvas(Modifier.matchParentSize()) { drawCardArt(card.accent, card.key, g) }
                ArtCrest(
                    card.icon, card.accent, g,
                    crest = 52.dp, medallion = 30.dp, iconSize = 15.dp,
                    modifier = Modifier.align(Alignment.Center),
                )
                if (card.count != null) {
                    CountGem(card.count, g, size = 21.dp, textStyle = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
                }
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(5.dp).size(18.dp)
                            .clip(CircleShape).background(Brush.linearGradient(g.plate)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Check, "正在使用", tint = g.onPlate, modifier = Modifier.size(12.dp)) }
                }
            }
            Spacer(Modifier.height(5.dp))
            NamePlate(card.title, g, textStyle = MaterialTheme.typography.titleSmall, horizontal = 12.dp, vertical = 3.dp, corner = 7.dp)
            if (card.footnote != null) {
                Text(
                    card.footnote, style = MaterialTheme.typography.labelSmall, color = g.muted,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
        Canvas(Modifier.matchParentSize()) { drawCardOrnaments(g, compact = true) }
    }
}

/**
 * One gilded card in the carousel: brushed-brass frame, deep enamel face, a window of painted
 * light around the card's own colour, a brass name plate and a gemstone with its count. The front
 * card catches a foil sweep as it comes forward.
 */
@Composable
private fun DeckCardView(
    card: DeckCard,
    current: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val g = AppTheme.extra.gilded
    val source = remember { MutableInteractionSource() }
    val outer = RoundedCornerShape(21.dp)
    val inner = RoundedCornerShape(17.dp)
    val foil = remember { Animatable(0f) }
    LaunchedEffect(card.key, current) {
        if (current) {
            foil.snapTo(0f)
            foil.animateTo(1f, tween(1_050, delayMillis = 160, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier
            .testTag("deck:${card.key}")
            .pressScale(source, 0.975f)
            .shadow(
                if (current) 20.dp else 8.dp, outer,
                ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = g.glow.copy(alpha = 0.55f),
            )
            .clip(outer)
            .background(Brush.linearGradient(g.rim))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Canvas(Modifier.matchParentSize()) { drawMetalSheen() }
        Column(
            Modifier.fillMaxSize().padding(4.dp).clip(inner)
                .background(Brush.verticalGradient(g.face))
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArtWindow(
                card, g,
                crest = 92.dp, medallion = 50.dp, icon = 24.dp, countSize = 27.dp,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(6.dp))
            NamePlate(
                card.title, g,
                textStyle = MaterialTheme.typography.titleMedium,
                horizontal = 18.dp, vertical = 4.dp, corner = 8.dp,
            )
            if (card.footnote != null) {
                Text(
                    card.footnote, style = MaterialTheme.typography.bodySmall, color = g.muted,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp),
                )
            }
            if (selected) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(Brush.linearGradient(g.plate)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Check, null, tint = g.onPlate, modifier = Modifier.size(11.dp)) }
                    Spacer(Modifier.width(5.dp))
                    Text("正在使用", style = MaterialTheme.typography.labelSmall, color = g.ink)
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
private fun ArtWindow(
    card: DeckCard,
    g: Gilded,
    crest: Dp,
    medallion: Dp,
    icon: Dp,
    countSize: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).border(1.dp, g.rim[1].copy(alpha = 0.55f), shape)) {
        Canvas(Modifier.matchParentSize()) { drawCardArt(card.accent, card.key, g) }
        ArtCrest(card.icon, card.accent, g, crest = crest, medallion = medallion, iconSize = icon, modifier = Modifier.align(Alignment.Center))
        // A soft floor so the gem sits on the picture rather than floats.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                ),
            ),
        )
        if (crest >= 80.dp) {
            Canvas(Modifier.align(Alignment.TopStart).padding(10.dp).size(14.dp)) {
                drawStar(center, size.minDimension * 0.5f, g.spark.copy(alpha = 0.65f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(7.dp)) {
            Spacer(Modifier.weight(1f))
            if (card.count != null) {
                CountGem(card.count, g, size = countSize, textStyle = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The card's emblem: its icon struck in a brass ring, with a diamond outline behind it. */
@Composable
private fun ArtCrest(
    icon: ImageVector,
    accent: Color,
    g: Gilded,
    crest: Dp,
    medallion: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(crest), contentAlignment = Alignment.Center) {
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
            if (size.minDimension > 70.dp.toPx()) {
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
        }
        Box(
            Modifier.size(medallion).clip(CircleShape)
                .background(Brush.radialGradient(listOf(lerp(accent, Color.Black, 0.24f), g.face[2])))
                .border(1.dp, g.rim[1].copy(alpha = 0.62f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = g.ink, modifier = Modifier.size(iconSize))
        }
    }
}

/** The count in a cut gemstone: brass rim, enamel heart, brass figures. */
@Composable
private fun CountGem(
    count: Int,
    g: Gilded,
    size: Dp = 34.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(size)
            .shadow((size.value * 0.22f).dp, CircleShape, spotColor = g.glow.copy(alpha = 0.55f))
            .clip(CircleShape)
            .background(Brush.linearGradient(g.gem))
            .padding(2.5.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(lerp(g.face[0], Color.White, 0.16f), g.face[2]))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = textStyle.merge(com.assetboard.ui.theme.NumberStyle),
            color = g.ink, fontWeight = FontWeight.Bold,
        )
    }
}

/** The name on a strip of lit brass, the way a card is titled. */
@Composable
private fun NamePlate(
    title: String,
    g: Gilded,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
    horizontal: Dp = 26.dp,
    vertical: Dp = 6.dp,
    corner: Dp = 10.dp,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp)
            .shadow(4.dp, shape, spotColor = Color.Black.copy(alpha = 0.35f))
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
            if (horizontal >= 12.dp) {
                drawDiamond(Offset(9.dp.toPx(), size.height / 2f), 2.0.dp.toPx(), g.rimDark.copy(alpha = 0.45f))
                drawDiamond(Offset(size.width - 9.dp.toPx(), size.height / 2f), 2.0.dp.toPx(), g.rimDark.copy(alpha = 0.45f))
            }
        }
        Text(
            title, style = textStyle, color = g.onPlate,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = horizontal, vertical = vertical),
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
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCardOrnaments(g: Gilded, compact: Boolean = false) {
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
    val pad = (if (compact) 9.dp else 12.dp).toPx()
    val len = (if (compact) 9.dp else 14.dp).toPx()
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
