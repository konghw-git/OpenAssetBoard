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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import com.assetboard.data.DeckMode
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.Gilded
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Where the endless carousel parks: halfway through the page indices, so it can turn both ways. */
private const val LOOP_START = Int.MAX_VALUE / 2

private fun Int.floorMod(other: Int): Int {
    val r = this % other
    return if (r < 0) r + other else r
}

/**
 * One card in a [CardDeckContent]: what it picks, what it says and the colour its art wears.
 * [countLabel] says what the count badge counts; [preview] is a few lines from inside the
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
 * A deck of glass cards standing in for a long row of lists: a short carousel shows the card in
 * front and its neighbours leaning away in 3D like a turned drum — swiping past either end wraps
 * to the other — and a grid lays every card out at once when there are too many to walk through.
 * Tapping the card in front picks it. Used by the todo tab for its lists and by the notes tab for
 * its topics.
 *
 * The look is the app's own: the page's ambient colour fields seen through translucent glass,
 * with a brass hairline and the card's colour as a soft wash — a thin keepsake of the gilded deck
 * this replaces, in the same glass language as the rest of the app.
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
    rememberedMode: DeckMode? = null,
    onModeChange: ((DeckMode) -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CardDeckContent(
            title = title, cards = cards, selected = selected, onPick = onPick, onDismiss = onDismiss,
            hint = hint, gridHint = gridHint, openVerb = openVerb, createLabel = createLabel, onCreate = onCreate,
            manageLabel = manageLabel, onManage = onManage, emptyText = emptyText,
            rememberedMode = rememberedMode, onModeChange = onModeChange,
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
    rememberedMode: DeckMode? = null,
    onModeChange: ((DeckMode) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val g = AppTheme.extra.gilded
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    // Many cards are quicker to pick from a page of the whole deck than from a carousel; the
    // toggle in the header swaps between the two. A remembered choice wins; without one the deck
    // falls back to the threshold, keyed on it so a deck whose data arrives after it opened still
    // starts on the page when it turns out to be long. A remembered seed is steady, so a toggle
    // on a deck is kept, and [onModeChange] carries the choice out to be remembered.
    val seed = rememberedMode ?: if (cards.size > 6) DeckMode.LIST else DeckMode.CAROUSEL
    var listMode by rememberSaveable(seed) { mutableStateOf(seed == DeckMode.LIST) }
    val scope = rememberCoroutineScope()
    val createFocus = remember { FocusRequester() }
    val ambient = rememberAmbient()
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
    CompositionLocalProvider(LocalAmbient provides ambient) {
        Box(
            modifier
                .fillMaxSize()
                .ambient(ambient)
                .drawBehind {
                    // A soft vignette over the colour fields, and a thin brass rule with a diamond
                    // at each corner: the deck's one keepsake of its gilded days.
                    val w = size.width
                    val h = size.height
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = if (ambient.dark) 0.40f else 0.10f)),
                            center = Offset(w * 0.5f, h * 0.5f), radius = maxOf(w, h) * 0.75f,
                        ),
                    )
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
                            Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, letterSpacing = 2.sp)
                            Spacer(Modifier.width(10.dp))
                            OrnamentRule(g)
                            if (cards.isNotEmpty()) {
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "共 ${cards.size} 张", style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            when {
                                cards.isEmpty() -> emptyText
                                listMode -> gridHint
                                else -> hint
                            },
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (cards.isNotEmpty()) {
                        IconButton(onClick = {
                            listMode = !listMode
                            onModeChange?.invoke(if (listMode) DeckMode.LIST else DeckMode.CAROUSEL)
                        }) {
                            Icon(
                                if (listMode) Icons.Outlined.ViewCarousel else Icons.Outlined.GridView,
                                if (listMode) "环绕展示" else "网格展示", tint = cs.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭", tint = cs.onSurfaceVariant) }
                }
                if (cards.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(emptyText, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                    }
                } else if (listMode) {
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
                                placeholder = { Text("名字", color = cs.onSurfaceVariant) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { createNow() }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = cs.onSurface, unfocusedTextColor = cs.onSurface, cursorColor = cs.primary,
                                    focusedBorderColor = g.rim[2], unfocusedBorderColor = g.rim[1].copy(alpha = 0.45f),
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                ),
                                modifier = Modifier.weight(1f).focusRequester(createFocus),
                            )
                            TextButton(
                                onClick = { createNow() }, enabled = name.isNotBlank(),
                                colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurface, disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.45f)),
                            ) { Text("创建") }
                            TextButton(onClick = { naming = false; name = "" }, colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant)) { Text("取消") }
                        } else {
                            OutlinedButton(
                                onClick = { naming = true }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, g.rim[1].copy(alpha = 0.75f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurface),
                            ) { Text(createLabel) }
                        }
                    }
                    if (onManage != null && manageLabel != null && !naming) {
                        TextButton(
                            onClick = onManage, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurface),
                        ) { Text(manageLabel) }
                    }
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
 * time, and the pager snaps one card to the middle and turns like a drum: three cards or more
 * wrap past either end to the other, while a deck of two stops at its ends. [reveal] fades the
 * pane in; [burst] is the one puff of light as the deck opens.
 *
 * A [HorizontalPager] whose page indices map back to cards with floorMod (the looping pager
 * pattern from compose-playtime): with three cards or more the page count is endless and the
 * pager parks halfway through it. The 3D transforms are read in `graphicsLayer` from the page's
 * offset fraction, so scrolling invalidates a layer instead of recomposing each card.
 */
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
    // Two cards in an endless pager would show the same card on both sides; they stop at their
    // ends like any short row instead.
    val looping = cards.size >= 3
    // Which card sits in front, for the dots and the spotlight under the pager.
    val centered = remember { mutableIntStateOf(initial) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val byWidth = maxWidth * 0.42f
        // The spotlight is part of the same block, so leave it room before growing the cards; the
        // block is centred as a whole, instead of the cards floating in all the spare height.
        val byHeight = (maxHeight - 220.dp) / 1.42f
        val cardWidth = minOf(byWidth, byHeight).coerceIn(120.dp, 212.dp)
        val cardHeight = cardWidth * 1.42f
        val edge = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
        val cardPx = with(LocalDensity.current) { cardWidth.toPx() }
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 24.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val current = cards.getOrNull(centered.intValue.coerceIn(0, cards.lastIndex))
            Box(Modifier.fillMaxWidth().height(cardHeight + 30.dp)) {
                DeckAura(burst.value, current?.accent ?: g.rim[1], g, Modifier.matchParentSize())
                if (cards.size == 1) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DeckCardView(
                            cards[0],
                            current = true,
                            selected = cards[0].key == selected,
                            modifier = Modifier.requiredWidth(cardWidth).requiredHeight(cardHeight),
                            onClick = { onPick(cards[0].key) },
                        )
                    }
                } else {
                    // Remounted when a deck crosses the two-card line, so the pager is always
                    // created in the mode its page count belongs to.
                    key(looping) {
                        val pagerState = rememberPagerState(
                            initialPage = if (looping) LOOP_START + initial else initial,
                            pageCount = { if (looping) Int.MAX_VALUE else cards.size },
                        )
                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.currentPage }.collect { page ->
                                centered.intValue = if (looping) (page - LOOP_START).floorMod(cards.size) else page
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            contentPadding = PaddingValues(horizontal = edge),
                            pageSize = PageSize.Fixed(cardWidth),
                        ) { page ->
                            val card = cards[if (looping) (page - LOOP_START).floorMod(cards.size) else page]
                            val isFront = page == pagerState.currentPage
                            Box(
                                Modifier
                                    .width(cardWidth)
                                    .fillMaxHeight()
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
                                            // The offset in page units, positive to the right of
                                            // the centre: the drum's turns, read in the layer so
                                            // scrolling only invalidates the layer.
                                            val e = (-((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction))
                                                .coerceIn(-1f, 1f)
                                            cameraDistance = 18f * density
                                            rotationY = -e * 36f
                                            rotationZ = -e * 1.2f
                                            translationX = e * cardPx * 0.03f
                                            translationY = abs(e) * 10.dp.toPx()
                                            val s = 1f - 0.14f * abs(e)
                                            scaleX = s
                                            scaleY = s
                                            alpha = 1f - 0.18f * abs(e)
                                        },
                                    onClick = {
                                        if (isFront) onPick(card.key) else scope.launch { pagerState.animateScrollToPage(page) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            DeckDots(cards.size, centered.intValue, current?.accent ?: g.rim[1], g)
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
                listOf(Color.White.copy(alpha = 0.16f), accent.copy(alpha = 0.12f), Color.Transparent),
                center = center, radius = w * 0.68f,
            ),
            w * 0.68f, center,
        )
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f || p >= 1f) return@Canvas
        val fade = 1f - p
        val ring = w * (0.18f + 0.55f * p)
        drawCircle(accent.copy(alpha = 0.32f * fade), ring, center, style = androidx.compose.ui.graphics.drawscope.Stroke((0.6f + 2.2f * fade).dp.toPx()))
        repeat(14) { i ->
            val angle = (i / 14f) * 2f * PI.toFloat() + 0.18f
            val inner = w * 0.14f
            val outer = w * (0.32f + 0.34f * p)
            drawLine(
                Color.White.copy(alpha = 0.22f * fade),
                center + Offset(cos(angle) * inner, sin(angle) * inner),
                center + Offset(cos(angle) * outer, sin(angle) * outer),
                strokeWidth = (1.1f * fade + 0.3f).dp.toPx(), cap = StrokeCap.Round,
            )
        }
        var seed = 7
        fun nextFloat(): Float {
            seed = seed * 1_103_515_245 + 12_345
            return ((seed ushr 16) and 0x7fff) / 32768f
        }
        repeat(20) {
            val angle = nextFloat() * 2f * PI.toFloat()
            val r = w * (0.08f + 0.45f * p) * (0.4f + nextFloat() * 0.8f)
            drawCircle(
                Color.White.copy(alpha = 0.55f * fade),
                (0.8f + 1.4f * fade).dp.toPx(),
                center + Offset(cos(angle) * r, sin(angle) * r * 0.8f),
            )
        }
    }
}

/** A count of the deck in dots, until there are too many for dots to read. */
@Composable
private fun DeckDots(count: Int, current: Int, accent: Color, g: Gilded) {
    val cs = MaterialTheme.colorScheme
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
                        .background(if (on) accent else cs.onSurfaceVariant.copy(alpha = 0.35f)),
                )
            }
        } else {
            Text("${current + 1} / $count", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * What is in the card in front: a few names from inside it, so choosing between two lists means
 * reading their contents instead of opening each one. The button opens the card, wearing the
 * card's own colour.
 */
@Composable
private fun DeckSpotlight(card: DeckCard, selected: Boolean, openVerb: String, g: Gilded, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .glass(shape, glass, elevation = 12.dp)
            .clip(shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(7.dp)) { drawDiamond(center, size.minDimension * 0.5f, g.rim[2]) }
            Spacer(Modifier.width(7.dp))
            Text(
                if (selected) "当前使用" else "当前选中",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            if (card.count != null) {
                Text(
                    card.countLabel ?: "共 ${card.count} 项", style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        if (card.preview.isEmpty()) {
            Text(
                card.previewEmpty ?: "里面还没有内容",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            card.preview.take(3).forEach { line ->
                Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(card.accent))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        line, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        val source = remember { MutableInteractionSource() }
        val onAccent = if (card.accent.luminance() > 0.55f) Color(0xFF1A1A1A) else Color.White
        Box(
            Modifier.fillMaxWidth().height(38.dp)
                .pressScale(source, 0.98f)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.horizontalGradient(listOf(card.accent, card.accent.copy(alpha = 0.82f))))
                .clickable(interactionSource = source, indication = null, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$openVerb「${card.title}」", style = MaterialTheme.typography.titleSmall,
                    color = onAccent, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.KeyboardArrowRight, null, tint = onAccent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * A card in the grid: the same glass, cut smaller, with the name, its count and one quiet line
 * under it. The card in use wears a check instead of the count.
 */
@Composable
private fun GridDeckCard(card: DeckCard, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val outer = RoundedCornerShape(18.dp)
    Box(
        modifier
            .aspectRatio(0.76f)
            .pressScale(source, 0.97f)
            .glass(
                outer, glass,
                tint = if (selected) cs.primary.copy(alpha = 0.10f) else Color.Unspecified,
                elevation = if (selected) 14.dp else 8.dp,
            )
            .clip(outer)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArtWindow(
                card,
                crest = 52.dp, medallion = 30.dp, icon = 15.dp, countSize = 21.dp,
                checked = selected,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                card.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (card.footnote != null) {
                Text(
                    card.footnote, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}

/**
 * One glass card in the carousel: a wash of the card's own colour behind its crest, the name and
 * a quiet footnote. The front card catches a light sweep as it comes forward.
 */
@Composable
private fun DeckCardView(
    card: DeckCard,
    current: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val outer = RoundedCornerShape(21.dp)
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
            .glass(
                outer, glass,
                tint = if (selected) cs.primary.copy(alpha = 0.10f) else Color.Unspecified,
                elevation = if (current) 20.dp else 8.dp,
            )
            .clip(outer)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArtWindow(
                card,
                crest = 92.dp, medallion = 50.dp, icon = 24.dp, countSize = 27.dp,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                card.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (card.footnote != null) {
                Text(
                    card.footnote, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp),
                )
            }
            if (selected) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(11.dp)) }
                    Spacer(Modifier.width(5.dp))
                    Text("正在使用", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Canvas(Modifier.matchParentSize()) { drawFoilSweep(foil.value) }
    }
}

/** A wash of the card's own colour, lit from above, with a crest and a count. */
@Composable
private fun ArtWindow(
    card: DeckCard,
    crest: Dp,
    medallion: Dp,
    icon: Dp,
    countSize: Dp,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
) {
    val g = AppTheme.extra.gilded
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(card.accent.copy(alpha = 0.22f), card.accent.copy(alpha = 0.08f), card.accent.copy(alpha = 0.16f))))
            .border(1.dp, g.rim[1].copy(alpha = 0.35f), shape),
    ) {
        // Light from above, so the crest sits in a lit window rather than on a flat wash.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.06f))),
            ),
        )
        ArtCrest(card.icon, card.accent, g, crest = crest, medallion = medallion, iconSize = icon, modifier = Modifier.align(Alignment.Center))
        if (checked) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp)
                    .clip(CircleShape).background(cs.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Check, "正在使用", tint = cs.onPrimary, modifier = Modifier.size(12.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(7.dp)) {
            Spacer(Modifier.weight(1f))
            if (card.count != null) {
                CountGem(card.count, g, size = countSize, textStyle = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The card's emblem: its icon on a lit medallion with a brass hairline and a halo of its colour. */
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
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.30f), Color.Transparent))))
        Box(
            Modifier.size(medallion).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.12f))))
                .border(1.dp, g.rim[1].copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(iconSize))
        }
    }
}

/** The count in a small glass badge with a brass hairline. */
@Composable
private fun CountGem(
    count: Int,
    g: Gilded,
    size: Dp = 34.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(size)
            .clip(CircleShape)
            .background(cs.surface.copy(alpha = 0.88f))
            .border(1.dp, g.rim[1].copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = textStyle.merge(com.assetboard.ui.theme.NumberStyle),
            color = cs.onSurface, fontWeight = FontWeight.Bold,
        )
    }
}

/** A band of light travelling across the glass; [progress] runs zero to one. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFoilSweep(progress: Float) {
    if (progress <= 0f || progress >= 1f) return
    val w = size.width
    val band = w * 0.55f
    val x = -band + (w + band * 2f) * progress
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.44f to Color.White.copy(alpha = 0.14f),
            0.52f to Color.White.copy(alpha = 0.03f),
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

/**
 * The compact way into a deck: a small glass bar with a fan of cards, the deck's name, how many
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
    val cs = MaterialTheme.colorScheme
    val g = AppTheme.extra.gilded
    val source = remember { MutableInteractionSource() }
    val outer = RoundedCornerShape(17.dp)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(1_150, delayMillis = 220, easing = FastOutSlowInEasing)) }
    Box(
        modifier
            .fillMaxWidth()
            .pressScale(source, 0.985f)
            .glass(outer, glass, elevation = 10.dp)
            .clip(outer)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .height(58.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeckStack(accents, g)
            Spacer(Modifier.width(11.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, letterSpacing = 0.8.sp)
            Spacer(Modifier.width(8.dp))
            CountGemSmall(count, g)
            Spacer(Modifier.weight(1f))
            if (current != null) {
                Text(
                    current, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Canvas(Modifier.matchParentSize()) { drawFoilSweep(sweep.value) }
    }
}

/** The count in a small glass badge on the bar. */
@Composable
private fun CountGemSmall(count: Int, g: Gilded) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(23.dp)
            .clip(CircleShape)
            .background(cs.surface.copy(alpha = 0.88f))
            .border(1.dp, g.rim[1].copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall.merge(com.assetboard.ui.theme.NumberStyle),
            color = cs.onSurface, fontWeight = FontWeight.Bold,
        )
    }
}

/** Three little glass cards fanned out: the deck seen edge-on. */
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
                    .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.18f))))
                    .border(0.8.dp, g.rim[1].copy(alpha = 0.55f), RoundedCornerShape(5.dp)),
            ) {
                // A struck diamond makes even one card read as a card, not a chip.
                Canvas(Modifier.fillMaxSize()) {
                    drawDiamond(Offset(size.width / 2f, size.height * 0.70f), 1.6.dp.toPx(), Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}
