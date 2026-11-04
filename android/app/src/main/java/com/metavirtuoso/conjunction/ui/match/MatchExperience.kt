package com.metavirtuoso.conjunction.ui.match

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
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
) {
    val repo = container.nearby
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(repo.store.isLoaded) }
    LaunchedEffect(Unit) { repo.store.load(); ready = true }
    val fp = event.encounterId
    val encounter by remember(fp) { fp?.let(repo::encounter) ?: flowOf(null) }.collectAsState(initial = fp?.let { repo.store.state.value.encounter(it)?.toCard() })
    var stage by remember(event) { mutableStateOf(if (skipAnimation) CardStage.CARD else CardStage.ANIMATING) }
    var blurb by remember(event) { mutableStateOf<String?>(null) }
    var safety by remember { mutableStateOf<EncounterCard?>(null) }
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
            friendId != null -> ConjunctionPopup(container, friendId, card, onOpenChat = onOpenChat, onClose = onDismiss)
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
                onSafety = { safety = card },
                onClose = onDismiss,
            )
        }
    }
    safety?.let { c ->
        SafetyActionsDialog(
            container = container,
            target = SafetyTarget.stranger(c),
            onDone = { closed -> safety = null; if (closed) onDismiss() },
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

/** The swipe card: right = accept, left = pass, plus buttons and accessibility actions. */
@Composable
internal fun SwipeCardPage(card: EncounterCard, blurb: String?, onSwipe: (Boolean) -> Unit, onSafety: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var answered by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val threshold = with(density) { 120.dp.toPx() }
    fun answer(accept: Boolean) {
        if (answered) return
        answered = true
        scope.launch {
            offsetX.animateTo(if (accept) threshold * 4 else -threshold * 4, tween(320))
            onSwipe(accept)
        }
    }
    val c = card.card
    val acceptLabel = stringResource(R.string.match_card_accept)
    val rejectLabel = stringResource(R.string.match_card_reject)
    Column(
        Modifier.fillMaxSize().background(Palette.Midnight).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GhostButton("Close", onClick = onClose)
            GhostButton("Safety", onClick = onSafety, color = Palette.RoseCopper)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
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
                    contentDescription = "Match card for ${c?.firstName ?: "someone nearby"}, ${card.total} percent compatible. " + "Swipe right to accept, left to pass."
                    customActions = listOf(
                        CustomAccessibilityAction(acceptLabel) { answer(true); true },
                        CustomAccessibilityAction(rejectLabel) { answer(false); true },
                    )
                },
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp).background(Brush.verticalGradient(listOf(Palette.InkViolet, Palette.Midnight))))
            Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ScreenedPhoto(
                    path = card.photoPath,
                    screening = card.photoScreening,
                    placeholder = Art.signKey(c?.sun),
                    contentDescription = if (card.photoPath != null) "Photo of ${c?.firstName ?: "your match"}" else "${Art.displaySign(c?.sun)} artwork",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape),
                )
                Spacer(Modifier.height(8.dp))
                GoldText("${c?.firstName ?: "Someone nearby"}${c?.age?.takeIf { it > 0 }?.let { ", $it" } ?: ""}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
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
                Text("${card.total}% · ${if (card.kind == "dating") "Dating match" else "Friends match"}", style = MaterialTheme.typography.titleMedium, color = Palette.Gold)
                c?.bio?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone, textAlign = TextAlign.Center, maxLines = 3) }
                blurb?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim, textAlign = TextAlign.Center) }
            }
            Image(painterResource(R.drawable.frame_swipe_card), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            // Swipe hint stamps.
            val accept = (offsetX.value / threshold).coerceIn(0f, 1f)
            val reject = (-offsetX.value / threshold).coerceIn(0f, 1f)
            if (accept > 0f) Text(acceptLabel.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Palette.Gold, modifier = Modifier.align(Alignment.TopStart).padding(40.dp).graphicsLayer { alpha = accept; rotationZ = -15f })
            if (reject > 0f) Text(rejectLabel.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Palette.RoseCopper, modifier = Modifier.align(Alignment.TopEnd).padding(40.dp).graphicsLayer { alpha = reject; rotationZ = 15f })
        }
        FinePrint(stringResource(R.string.match_card_accessibility_hint), Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton(rejectLabel, onClick = { answer(false) }, modifier = Modifier.weight(1f), enabled = !answered, color = Palette.RoseCopper)
            GoldButton(acceptLabel, onClick = { answer(true) }, modifier = Modifier.weight(1f), enabled = !answered)
        }
        if (card.highlights.isNotEmpty()) {
            OrnatePanel(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text("WHY YOUR STARS ALIGN", style = MaterialTheme.typography.titleSmall, color = Palette.Gold)
                card.highlights.forEach { Text("✦ $it", style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone) }
            }
        }
        FinePrint(stringResource(R.string.match_card_entertainment_note), Modifier.padding(top = 10.dp))
    }
}

/** "It's a Conjunction!": the friend's photo pops up with an intro box; the message is queued if they left. */
@Composable
private fun ConjunctionPopup(container: AppContainer, friendId: String, card: EncounterCard?, onOpenChat: (ChatTarget) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val friend by remember(friendId) { container.nearby.friend(friendId) }.collectAsState(initial = null)
    var intro by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GoldText("It's a Conjunction!", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            OrnateDivider()
            if (f?.photoPath != null) {
                ScreenedPhoto(f.photoPath, f.photoScreening, Art.signKey(sun), "Photo of $name", Modifier.size(180.dp).clip(CircleShape).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
            } else {
                Medallion(signArt = Art.signKey(sun), photoPath = null, screening = Screening.SAFE, size = 200.dp, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
            }
            GoldText(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            f?.bio?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center) }
            Text(
                listOfNotNull(sun?.let { "☉ ${Art.displaySign(it)}" }, (f?.moon ?: card?.card?.moon)?.let { "☽ ${Art.displaySign(it)}" }, (f?.rising ?: card?.card?.rising)?.let { "AC ${Art.displaySign(it)}" }).joinToString("   "),
                style = MaterialTheme.typography.titleSmall, color = Palette.GoldPale,
            )
            Text("You and $name both said yes. Say hello.", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
            OrnateTextField(intro, { intro = it.take(500) }, label = "Introduce yourself", singleLine = false, minLines = 2)
            FinePrint(stringResource(R.string.chat_first_open_notice))
            GoldButton(if (sending) "Sending…" else "Send & open chat", enabled = intro.isNotBlank() && !sending, modifier = Modifier.fillMaxWidth(), onClick = {
                sending = true
                scope.launch {
                    try {
                        container.nearby.sendChat(friendId, intro)
                        onOpenChat(ChatTarget(friendId))
                    } finally {
                        sending = false
                    }
                }
            })
            GhostButton("Open chat", onClick = { onOpenChat(ChatTarget(friendId)) }, modifier = Modifier.fillMaxWidth())
            GhostButton("Later", onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}
