package com.metavirtuoso.conjunction.ui.match

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.core.match.MatchBlurbPrompt
import com.metavirtuoso.conjunction.core.match.MatchKind
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.nearby.EncounterCard
import com.metavirtuoso.conjunction.nearby.NearbyEvent
import com.metavirtuoso.conjunction.nearby.Screening
import com.metavirtuoso.conjunction.ui.ChatTarget
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnateDivider
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OrreryLoader
import com.metavirtuoso.conjunction.ui.components.ProfilePhoto
import com.metavirtuoso.conjunction.ui.components.ScreenedPhoto
import com.metavirtuoso.conjunction.ui.screens.SafetyActionsDialog
import com.metavirtuoso.conjunction.ui.screens.SafetyTarget
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private enum class CardStage { ANIMATING, CARD, WAITING }

/**
 * Full-screen match flow for a match found nearby (popup while the app is open, or a tapped local
 * notification), SPEC 5b/5c: the match animation (both sun-sign medallions spiral in from opposite
 * corners and merge into the conjunction emblem; birthday twins get the lavish starburst with star
 * confetti), then the swipe card (right = accept, left = pass, with buttons too), then
 * "It's a Conjunction!" with the other person's photo and an intro box once both said yes. All data
 * comes from this phone's store: what the other phone sent over Bluetooth.
 */
@Composable
fun MatchExperience(
    container: AppContainer,
    event: NearbyEvent,
    myCharts: Charts?,
    profile: Profile,
    onDismiss: () -> Unit,
    onOpenChat: (ChatTarget) -> Unit,
    skipAnimation: Boolean = false,
    onOpenCompat: (String) -> Unit = {},
) {
    val repo = container.nearby
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(repo.store.isLoaded) }
    LaunchedEffect(Unit) { repo.store.load(); ready = true }
    val fp = event.encounterId
    val encounter by remember(fp) { fp?.let(repo::encounter) ?: flowOf(null) }.collectAsState(initial = fp?.let { repo.store.state.value.encounter(it)?.toCard() })
    var stage by remember(event) { mutableStateOf(if (skipAnimation) CardStage.CARD else CardStage.ANIMATING) }
    var blurb by remember(event) { mutableStateOf<String?>(null) }
    var safety by remember { mutableStateOf<Pair<EncounterCard, String>?>(null) }
    BackHandler(onBack = onDismiss)
    val card = encounter
    val friendId = event.friendId ?: card?.friendId

    LaunchedEffect(card?.encounterId) {
        val c = card ?: return@LaunchedEffect
        if (c.friendId != null) return@LaunchedEffect
        val input = MatchBlurbPrompt.Input(
            kind = if (c.kind == "dating") MatchKind.DATING else MatchKind.FRIENDS,
            mySun = myCharts?.natal?.sunSign?.displayName ?: "",
            theirSun = Art.displaySign(c.card?.sun),
            theirMoon = c.card?.moon?.let(Art::displaySign),
            theirRising = c.card?.rising?.let(Art::displaySign),
            myAnimal = myCharts?.chinese?.yearAnimal?.displayName ?: "",
            theirAnimal = Art.displayAnimal(c.card?.chineseAnimal),
            total = c.total, western = c.western, chinese = c.chinese, blood = c.blood,
            birthdayTwin = c.birthdayTwin, exactTwin = c.exactTwin,
        )
        blurb = MatchBlurbPrompt.fallback(input)
        blurb = container.readings.matchBlurb(input)
    }

    Box(
        Modifier.fillMaxSize().background(Palette.Midnight).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        when {
            friendId != null -> ConjunctionPopup(container, friendId, card, onOpenChat = onOpenChat, onOpenCompat = onOpenCompat, onClose = onDismiss)
            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OrreryLoader(size = 96.dp) }
            card == null || (card.mySwipe == "left") -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("This match is no longer available.", style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone, textAlign = TextAlign.Center)
                GhostButton("Close", onClick = onDismiss, modifier = Modifier.padding(top = 16.dp))
            }
            stage == CardStage.ANIMATING && card.mySwipe == null -> MatchAnimation(
                card = card,
                mySignArt = myCharts?.natal?.sunSign?.let(Art::sign) ?: R.drawable.brand_mark,
                myPhotoPath = profile.localPhotoPath,
                blurb = blurb,
                onFinished = { stage = CardStage.CARD },
            )
            stage == CardStage.WAITING || card.mySwipe == "right" -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.popup_match_nearby), contentDescription = null, modifier = Modifier.size(220.dp))
                GoldText("Your star is sent", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    "If ${card.card?.firstName ?: "they"} says yes too, you'll both get a notification and the chat opens. " +
                        "If you're out of range now, your answer goes the next time your phones are near each other (within 24 hours).",
                    style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center,
                )
                GoldButton("Done", onClick = onDismiss, modifier = Modifier.padding(top = 20.dp))
                GhostButton("☌\uFE0E " + stringResource(R.string.match_card_chart), onClick = { onOpenCompat(card.encounterId) }, modifier = Modifier.padding(top = 10.dp))
            }
            else -> SwipeCardPage(
                card = card,
                blurb = blurb,
                onSwipe = { accept ->
                    scope.launch {
                        repo.swipe(card.encounterId, accept)
                        if (accept) stage = CardStage.WAITING else onDismiss()
                    }
                },
                onChart = { onOpenCompat(card.encounterId) },
                onMenu = { action ->
                    when (action) {
                        CardMenuAction.SNOOZE -> scope.launch { repo.snooze(card.encounterId); onDismiss() }
                        CardMenuAction.TIPS -> safety = card to "tips"
                        CardMenuAction.BLOCK -> safety = card to "block"
                        CardMenuAction.REPORT -> safety = card to "report"
                    }
                },
                onClose = onDismiss,
            )
        }
    }
    safety?.let { (c, mode) ->
        if (mode == "tips") com.metavirtuoso.conjunction.ui.screens.SafetyTipsDialog(container) { safety = null }
        else SafetyActionsDialog(
            container = container,
            target = SafetyTarget.stranger(c),
            onDone = { closed -> safety = null; if (closed) onDismiss() },
            initialMode = mode,
        )
    }
}

/** One falling star of the twin confetti. */
private data class Confetti(val x: Float, val delay: Float, val speed: Float, val size: Float, val spin: Float, val gold: Boolean)

/**
 * ~2.6 s animation: gold flash; my sun medallion spirals in from the upper-left and theirs from
 * the lower-right (each with the person's photo inset when there is one); they meet and merge into
 * the conjunction emblem with a gold burst; the percentage counts up with the sub-scores and the
 * blurb. Tap to skip. Reduced motion: a simple cross-fade to the final frame.
 */
@Composable
internal fun MatchAnimation(card: EncounterCard, @DrawableRes mySignArt: Int, myPhotoPath: String?, blurb: String?, onFinished: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val t = remember { Animatable(if (reduced) 0.7f else 0f) }
    val twin = card.birthdayTwin || card.exactTwin
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(if (reduced) 600 else 2600, easing = LinearEasing))
        kotlinx.coroutines.delay(if (twin) 1600 else 1100)
        onFinished()
    }
    val scope = rememberCoroutineScope()
    val confetti = remember { val r = Random(11); List(70) { Confetti(r.nextFloat(), r.nextFloat() * 0.5f, 0.6f + r.nextFloat(), 6f + r.nextFloat() * 10f, r.nextFloat() * 720f, r.nextFloat() < 0.7f) } }
    val p = t.value
    val theirSignArt = Art.signKey(card.card?.sun)

    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(onClickLabel = "Skip animation") {
            scope.launch { t.snapTo(1f); onFinished() }
        }.semantics { contentDescription = if (twin) "Birthday twin match animation" else "Match animation" },
    ) {
        val localDensity = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Backdrop art.
        Image(
            painterResource(if (twin) R.drawable.popup_birthday_twins else R.drawable.popup_match_nearby),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = (0.25f + 0.45f * ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)) },
        )
        // Flash.
        val flash = if (p < 0.12f) 1f - abs(p - 0.04f) / 0.08f else 0f
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Palette.GoldPale.copy(alpha = flash.coerceIn(0f, 1f)), Color.White.copy(alpha = 0.6f * flash.coerceIn(0f, 1f)), Color.Transparent))))

        // Spiral approach (0.05 .. 0.62).
        val approach = ((p - 0.05f) / 0.57f).coerceIn(0f, 1f)
        val eased = 1f - (1f - approach) * (1f - approach)
        val radius = (1f - eased) * maxOf(w, h) * 0.62f
        val turn = eased * 1.25f * 2f * PI.toFloat()
        val medallion = 132.dp
        val medPx = with(localDensity) { medallion.toPx() }
        val merge = ((p - 0.58f) / 0.14f).coerceIn(0f, 1f)
        fun pos(start: Float): IntOffset {
            val a = start + turn
            return IntOffset((w / 2 + radius * cos(a) - medPx / 2).roundToInt(), (h * 0.42f + radius * sin(a) - medPx / 2).roundToInt())
        }
        if (merge < 1f) {
            Medallion(mySignArt, myPhotoPath, Screening.SAFE, medallion, Modifier.offset { pos((225f / 180f) * PI.toFloat()) }.graphicsLayer { alpha = 1f - merge; scaleX = 1f - 0.4f * merge; scaleY = 1f - 0.4f * merge })
            Medallion(theirSignArt, card.photoPath, card.photoScreening, medallion, Modifier.offset { pos((45f / 180f) * PI.toFloat()) }.graphicsLayer { alpha = 1f - merge; scaleX = 1f - 0.4f * merge; scaleY = 1f - 0.4f * merge })
        }
        // Burst + emblem.
        val emblemScale = ((p - 0.6f) / 0.15f).coerceIn(0f, 1f)
        if (emblemScale > 0f) {
            Image(
                painterResource(if (twin) R.drawable.match_twin_burst else R.drawable.match_merge_burst),
                contentDescription = null,
                modifier = Modifier.size(340.dp).align(Alignment.TopCenter).offset { IntOffset(0, (h * 0.42f - with(localDensity) { 170.dp.toPx() }).roundToInt()) }
                    .graphicsLayer { scaleX = 0.4f + emblemScale; scaleY = 0.4f + emblemScale; rotationZ = p * 40f; alpha = emblemScale },
            )
            Image(
                painterResource(R.drawable.match_merge_emblem),
                contentDescription = null,
                modifier = Modifier.size(190.dp).align(Alignment.TopCenter).offset { IntOffset(0, (h * 0.42f - with(localDensity) { 95.dp.toPx() }).roundToInt()) }
                    .graphicsLayer { scaleX = emblemScale; scaleY = emblemScale },
            )
        }
        // Twin confetti of stars.
        if (twin && !reduced) {
            Canvas(Modifier.fillMaxSize()) {
                confetti.forEach { c ->
                    val local = ((p - 0.55f - c.delay * 0.3f) * c.speed * 2.2f).coerceAtLeast(0f)
                    if (local <= 0f) return@forEach
                    val y = local * size.height
                    val x = c.x * size.width + sin(local * 8f + c.spin) * 24f
                    rotate(c.spin * local, Offset(x, y)) { drawPath(path = starPath(Offset(x, y), c.size * density), color = if (c.gold) Palette.Gold else Palette.Moonstone) }
                }
            }
        }
        // Score and blurb.
        val countT = ((p - 0.68f) / 0.3f).coerceIn(0f, 1f)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { alpha = countT }
                // Ink scrim so the score stays readable over the bright popup art and burst.
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Midnight.copy(alpha = 0.78f), Palette.Midnight.copy(alpha = 0.9f), Palette.Midnight.copy(alpha = 0.95f))))
                .navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 96.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GoldText(
                when { card.exactTwin -> "Exact twin!"; card.birthdayTwin -> "Birthday twin!"; else -> if (card.kind == "dating") "A spark nearby" else "A kindred star nearby" },
                style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
            )
            GoldText("${(card.total * countT).roundToInt()}%", style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center)
            Text(
                "Western ${card.western} · Chinese ${card.chinese}${card.blood?.let { " · Blood $it" } ?: ""}",
                style = MaterialTheme.typography.titleSmall, color = Palette.GoldPale, textAlign = TextAlign.Center,
            )
            blurb?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
            FinePrint("Tap to continue", Modifier.padding(top = 10.dp))
        }
    }
}

private fun starPath(c: Offset, r: Float): Path = Path().apply {
    for (i in 0 until 8) {
        val radius = if (i % 2 == 0) r else r * 0.35f
        val a = i * PI.toFloat() / 4f - PI.toFloat() / 2f
        val x = c.x + radius * cos(a)
        val y = c.y + radius * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Gold medallion frame (art/match/medallion_frame) with sign art, and a profile photo inset when
 * there is one that screening cleared (unchecked / flagged photos are not inset here).
 */
@Composable
fun Medallion(@DrawableRes signArt: Int, photoPath: String?, screening: Screening, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Image(painterResource(signArt), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size * 0.78f).clip(CircleShape))
        if (photoPath != null && screening == Screening.SAFE) {
            ProfilePhoto(
                localPath = photoPath, placeholder = signArt, contentDescription = null,
                modifier = Modifier.size(size * 0.42f).align(Alignment.BottomEnd).offset(x = -size * 0.06f, y = -size * 0.06f).clip(CircleShape).background(Palette.Gold),
            )
        }
        Image(painterResource(R.drawable.match_medallion_frame), contentDescription = null, modifier = Modifier.size(size))
    }
}

/** The ⋯ menu of a stranger's card (SPEC 5d.3): Snooze 7 days, Block, Report, Safety Tips. */
enum class CardMenuAction { SNOOZE, BLOCK, REPORT, TIPS }

@Composable
fun CardMenu(onAction: (CardMenuAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        com.metavirtuoso.conjunction.ui.components.MoreButton("More options", onClick = { open = true })
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.InkViolet) {
            listOf(
                CardMenuAction.SNOOZE to R.string.menu_snooze, CardMenuAction.BLOCK to R.string.menu_block,
                CardMenuAction.REPORT to R.string.menu_report, CardMenuAction.TIPS to R.string.menu_safety_tips,
            ).forEach { (action, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(label), color = if (action == CardMenuAction.REPORT || action == CardMenuAction.BLOCK) Palette.RoseCopper else Palette.Moonstone) },
                    onClick = { open = false; onAction(action) },
                )
            }
        }
    }
}

/** Gold score medallion ("87%") for cards. */
@Composable
fun ScoreMedallion(total: Int, size: Dp = 56.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(com.metavirtuoso.conjunction.ui.theme.GoldBrushes.leaf),
        contentAlignment = Alignment.Center,
    ) {
        Text("$total%", style = MaterialTheme.typography.titleSmall, color = Palette.Midnight)
    }
}

/**
 * The face of a match card (SPEC 5d.3): photo (or zodiac art), first name, age, Sun / Moon /
 * Rising glyphs, Chinese animal, relationship status, looking-for, kind chip and score medallion.
 */
@Composable
fun MatchCardFace(card: EncounterCard, modifier: Modifier = Modifier, blurb: String? = null, photoSize: Dp? = null) {
    val c = card.card
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            ScreenedPhoto(
                path = card.photoPath,
                screening = card.photoScreening,
                placeholder = Art.signKey(c?.sun),
                contentDescription = if (card.photoPath != null) "Photo of ${c?.firstName ?: "your match"}" else "${Art.displaySign(c?.sun)} artwork",
                modifier = (if (photoSize != null) Modifier.size(photoSize) else Modifier.fillMaxWidth().aspectRatio(1f)).clip(CircleShape),
            )
            Box(Modifier.align(Alignment.BottomEnd)) { ScoreMedallion(card.total) }
        }
        Spacer(Modifier.height(8.dp))
        GoldText(
            (if (card.exactTwin) "Exact twin · " else if (card.birthdayTwin) "Birthday twin · " else "") +
                "${c?.firstName ?: "Someone nearby"}${c?.age?.takeIf { it > 0 }?.let { ", $it" } ?: ""}",
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
        )
        Text(
            listOfNotNull(c?.sun?.let { "☉ ${Art.displaySign(it)}" }, c?.moon?.let { "☽ ${Art.displaySign(it)}" }, c?.rising?.let { "AC ${Art.displaySign(it)}" }).joinToString("  "),
            style = MaterialTheme.typography.bodyMedium, color = Palette.GoldPale, textAlign = TextAlign.Center,
        )
        Text(
            listOfNotNull(
                c?.chineseAnimal?.let { Art.displayAnimal(it) },
                c?.relationshipStatus?.let { if (it == "single") "Single" else "In a relationship" },
                c?.lookingFor?.let { when (it) { "dating" -> "Looking for dating"; "friends" -> "Looking for friends"; else -> "Dating & friends" } },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        KindChip(card.kind)
        c?.bio?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp)) }
        blurb?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim, textAlign = TextAlign.Center, maxLines = 3) }
    }
}

/**
 * A swipeable framed card: right = accept, left = pass, with the hint stamps; accessibility
 * actions for both. Buttons Pass / ☌ Chart / Accept under it; the card's own ⋯ menu (Snooze,
 * Safety Tips, Block, Report) in the frame's top-right corner when [onMenu] is set. [maxCardHeight]
 * caps the framed card (the Nearby list keeps the next section in view).
 */
@Composable
fun SwipeableMatchCard(
    card: EncounterCard,
    onAnswer: (Boolean) -> Unit,
    onChart: () -> Unit,
    modifier: Modifier = Modifier,
    blurb: String? = null,
    onMenu: ((CardMenuAction) -> Unit)? = null,
    maxCardHeight: Dp? = null,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember(card.encounterId) { Animatable(0f) }
    var answered by remember(card.encounterId) { mutableStateOf(false) }
    val density = LocalDensity.current
    val threshold = with(density) { 120.dp.toPx() }
    fun answer(accept: Boolean) {
        if (answered) return
        answered = true
        scope.launch {
            offsetX.animateTo(if (accept) threshold * 4 else -threshold * 4, tween(320))
            onAnswer(accept)
        }
    }
    val c = card.card
    val acceptLabel = stringResource(R.string.match_card_accept)
    val rejectLabel = stringResource(R.string.match_card_reject)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
      BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val cardWidth = minOf(maxWidth, 420.dp, maxCardHeight?.let { it * 0.70f } ?: 420.dp)
        Box(
            Modifier
                .width(cardWidth)
                .aspectRatio(0.70f)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .graphicsLayer { rotationZ = offsetX.value / 40f }
                .pointerInput(card.encounterId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                offsetX.value > threshold -> answer(true)
                                offsetX.value < -threshold -> answer(false)
                                else -> scope.launch { offsetX.animateTo(0f) }
                            }
                        },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dx) }
                    }
                }
                .semantics {
                    contentDescription = "Match card for ${c?.firstName ?: "someone nearby"}, ${card.total} percent compatible."
                    customActions = listOf(
                        CustomAccessibilityAction(acceptLabel) { answer(true); true },
                        CustomAccessibilityAction(rejectLabel) { answer(false); true },
                    )
                },
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp).background(Brush.verticalGradient(listOf(Palette.InkViolet, Palette.Midnight))))
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 38.dp)) {
                MatchCardFace(card, Modifier.fillMaxSize(), blurb = blurb, photoSize = minOf(maxWidth * 0.86f, maxHeight * 0.55f))
            }
            Image(painterResource(R.drawable.frame_swipe_card), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            val accept = (offsetX.value / threshold).coerceIn(0f, 1f)
            val reject = (-offsetX.value / threshold).coerceIn(0f, 1f)
            if (accept > 0f) Text(acceptLabel.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Palette.Gold, modifier = Modifier.align(Alignment.TopStart).padding(40.dp).graphicsLayer { alpha = accept; rotationZ = -15f })
            if (reject > 0f) Text(rejectLabel.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Palette.RoseCopper, modifier = Modifier.align(Alignment.TopEnd).padding(40.dp).graphicsLayer { alpha = reject; rotationZ = 15f })
            if (onMenu != null) {
                // A dark disc keeps the ⋯ legible over the frame's corner ornament.
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp)
                        .clip(CircleShape).background(Palette.Midnight.copy(alpha = 0.88f))
                        .border(1.dp, com.metavirtuoso.conjunction.ui.theme.GoldBrushes.leaf, CircleShape),
                ) { CardMenu(onMenu) }
            }
        }
      }
        Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(rejectLabel, onClick = { answer(false) }, modifier = Modifier.weight(1f), enabled = !answered, color = Palette.RoseCopper, horizontalPadding = 6.dp)
            GhostButton("☌\uFE0E " + stringResource(R.string.match_card_chart), onClick = onChart, modifier = Modifier.weight(1f), horizontalPadding = 6.dp)
            GoldButton(acceptLabel, onClick = { answer(true) }, modifier = Modifier.weight(1f), enabled = !answered, horizontalPadding = 6.dp)
        }
    }
}

/** The full-screen swipe card after the match animation (popup / notification). */
@Composable
internal fun SwipeCardPage(card: EncounterCard, blurb: String?, onSwipe: (Boolean) -> Unit, onChart: () -> Unit, onMenu: (CardMenuAction) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.Midnight).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Close", onClick = onClose)
            CardMenu(onMenu)
        }
        Spacer(Modifier.height(10.dp))
        SwipeableMatchCard(card, onAnswer = onSwipe, onChart = onChart, blurb = blurb)
        FinePrint(stringResource(R.string.match_card_accessibility_hint), Modifier.padding(top = 8.dp))
    }
}

/** "It's a Conjunction!": the friend's photo pops up; Say hi (chat) or See our chart (SPEC 5d.4). */
@Composable
internal fun ConjunctionPopup(container: AppContainer, friendId: String, card: EncounterCard?, onOpenChat: (ChatTarget) -> Unit, onOpenCompat: (String) -> Unit, onClose: () -> Unit) {
    val friend by remember(friendId) { container.nearby.friend(friendId) }.collectAsState(initial = null)
    val reduced = LocalReducedMotion.current
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 140f)) }
    val f = friend
    val name = f?.firstName ?: card?.card?.firstName ?: "your match"
    val sun = f?.sun ?: card?.card?.sun
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.popup_its_a_conjunction), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f })
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            // A soft dark scrim keeps the gold title legible over the figures' halos.
            Box(
                Modifier.fillMaxWidth().drawBehind {
                    // Soft top and bottom edges (no visible band).
                    drawRect(Brush.verticalGradient(listOf(Palette.Midnight.copy(alpha = 0f), Palette.Midnight.copy(alpha = 0.72f), Palette.Midnight.copy(alpha = 0.72f), Palette.Midnight.copy(alpha = 0f))))
                }.padding(vertical = 26.dp),
                contentAlignment = Alignment.Center,
            ) {
                GoldText("It's a Conjunction!", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            }
            OrnateDivider()
            if (f?.photoPath != null) {
                ScreenedPhoto(f.photoPath, f.photoScreening, Art.signKey(sun), "Photo of $name", Modifier.size(190.dp).clip(CircleShape).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
            } else {
                Medallion(signArt = Art.signKey(sun), photoPath = null, screening = Screening.SAFE, size = 200.dp, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
            }
            GoldText(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text("You and $name both said yes.", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
            (f?.total ?: card?.total)?.let { Text("$it% compatible", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale) }
            Spacer(Modifier.height(8.dp))
            GoldButton(stringResource(R.string.match_popup_say_hi), onClick = { onOpenChat(ChatTarget(friendId)) }, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp))
            GhostButton("☌\uFE0E " + stringResource(R.string.match_popup_see_chart), onClick = { onOpenCompat(friendId) }, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp))
            Text(
                "Later", style = MaterialTheme.typography.labelLarge, color = Palette.MoonstoneDim,
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClose).padding(12.dp),
            )
        }
    }
}
