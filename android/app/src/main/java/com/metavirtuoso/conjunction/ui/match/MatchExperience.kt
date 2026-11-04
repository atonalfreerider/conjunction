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
import com.metavirtuoso.conjunction.nearby.FriendProfile
import com.metavirtuoso.conjunction.nearby.NearbyException
import com.metavirtuoso.conjunction.nearby.PushEvent
import com.metavirtuoso.conjunction.nearby.PushKind
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
import com.metavirtuoso.conjunction.ui.screens.SafetyActionsDialog
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

private sealed interface MatchStage {
    data object Loading : MatchStage
    data class Animating(val card: EncounterCard) : MatchStage
    data class Card(val card: EncounterCard) : MatchStage
    data class Waiting(val card: EncounterCard) : MatchStage
    data class Conjunction(val card: EncounterCard?, val friend: FriendProfile?) : MatchStage
    data class Failed(val message: String) : MatchStage
}

/**
 * Full-screen match flow for a push or an inbox tap (SPEC 5b): the match animation (both sun-sign
 * medallions spiral in from opposite corners and merge into the conjunction emblem; birthday
 * twins get the lavish starburst with star confetti), then the swipe card (right = accept, left =
 * pass, with buttons too), then "It's a Conjunction!" with the other person's photo and an intro
 * box when both said yes.
 */
@Composable
fun MatchExperience(
    container: AppContainer,
    event: PushEvent,
    myCharts: Charts?,
    profile: Profile,
    onDismiss: () -> Unit,
    onOpenChat: (ChatTarget) -> Unit,
    skipAnimation: Boolean = false,
) {
    val api = container.nearbyApi
    val scope = rememberCoroutineScope()
    var stage by remember(event) { mutableStateOf<MatchStage>(MatchStage.Loading) }
    var blurb by remember(event) { mutableStateOf<String?>(null) }
    var safety by remember { mutableStateOf<EncounterCard?>(null) }
    BackHandler(onBack = onDismiss)

    LaunchedEffect(event) {
        val encounterId = event.encounterId ?: event.pairId
        if (encounterId == null) { stage = MatchStage.Failed("This match is no longer available."); return@LaunchedEffect }
        try {
            val card = api.getEncounterCard(encounterId)
            if (event.kind == PushKind.CONJUNCTION || card.status == "matched") {
                stage = MatchStage.Conjunction(card, runCatching { api.getFriendProfile(card.otherUid) }.getOrNull())
            } else {
                stage = if (skipAnimation) MatchStage.Card(card) else MatchStage.Animating(card)
                val kind = if (card.kind == "dating") MatchKind.DATING else MatchKind.FRIENDS
                val input = MatchBlurbPrompt.Input(
                    kind = kind,
                    mySun = myCharts?.natal?.sunSign?.displayName ?: "",
                    theirSun = Art.displaySign(card.card?.sun),
                    theirMoon = card.card?.moon?.let(Art::displaySign),
                    theirRising = card.card?.rising?.let(Art::displaySign),
                    myAnimal = myCharts?.chinese?.yearAnimal?.displayName ?: "",
                    theirAnimal = Art.displayAnimal(card.card?.chineseAnimal),
                    total = card.total,
                    western = card.western,
                    chinese = card.chinese,
                    blood = card.blood,
                    birthdayTwin = card.birthdayTwin,
                    exactTwin = card.exactTwin,
                )
                blurb = MatchBlurbPrompt.fallback(input)
                blurb = container.readings.matchBlurb(input)
            }
        } catch (e: NearbyException) {
            stage = MatchStage.Failed(e.message ?: "This match is no longer available.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            stage = MatchStage.Failed("Couldn't load this match. Check your connection.")
        }
    }

    Box(
        Modifier.fillMaxSize().background(Palette.Midnight).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        when (val s = stage) {
            MatchStage.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OrreryLoader(size = 96.dp) }
            is MatchStage.Failed -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(s.message, style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone, textAlign = TextAlign.Center)
                GhostButton("Close", onClick = onDismiss, modifier = Modifier.padding(top = 16.dp))
            }
            is MatchStage.Animating -> MatchAnimation(
                card = s.card,
                mySignArt = myCharts?.natal?.sunSign?.let(Art::sign) ?: R.drawable.brand_mark,
                myPhotoPath = profile.localPhotoPath,
                blurb = blurb,
                onFinished = { stage = MatchStage.Card(s.card) },
            )
            is MatchStage.Card -> SwipeCardPage(
                card = s.card,
                blurb = blurb,
                onSwipe = { accept ->
                    scope.launch {
                        try {
                            val result = api.swipe(s.card.encounterId, accept)
                            when {
                                !accept -> onDismiss()
                                result.outcome == "matched" -> stage = MatchStage.Conjunction(s.card, runCatching { api.getFriendProfile(s.card.otherUid) }.getOrNull())
                                else -> stage = MatchStage.Waiting(s.card)
                            }
                        } catch (e: NearbyException) {
                            stage = MatchStage.Failed(e.message ?: "Couldn't send your answer.")
                        }
                    }
                },
                onSafety = { safety = s.card },
                onClose = onDismiss,
            )
            is MatchStage.Waiting -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.popup_match_nearby), contentDescription = null, modifier = Modifier.size(220.dp))
                GoldText("Your star is sent", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text("If ${s.card.card?.firstName ?: "they"} says yes too, you'll both get a notification and the chat opens.", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
                GoldButton("Done", onClick = onDismiss, modifier = Modifier.padding(top = 20.dp))
            }
            is MatchStage.Conjunction -> ConjunctionPopup(container, s.card, s.friend, onOpenChat = onOpenChat, onClose = onDismiss)
        }
    }
    safety?.let { card ->
        SafetyActionsDialog(
            container = container,
            otherUid = card.otherUid,
            name = card.card?.firstName ?: "this person",
            encounterId = card.encounterId,
            pairId = null,
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
            Medallion(mySignArt, myPhotoPath, null, medallion, Modifier.offset { pos((225f / 180f) * PI.toFloat()) }.graphicsLayer { alpha = 1f - merge; scaleX = 1f - 0.4f * merge; scaleY = 1f - 0.4f * merge })
            Medallion(theirSignArt, null, card.photoUrl, medallion, Modifier.offset { pos((45f / 180f) * PI.toFloat()) }.graphicsLayer { alpha = 1f - merge; scaleX = 1f - 0.4f * merge; scaleY = 1f - 0.4f * merge })
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
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp).graphicsLayer { alpha = countT },
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

/** Gold medallion frame (art/match/medallion_frame) with sign art, and a profile photo inset when available. */
@Composable
fun Medallion(@DrawableRes signArt: Int, photoPath: String?, photoUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Image(painterResource(signArt), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size * 0.78f).clip(CircleShape))
        if (photoPath != null || photoUrl != null) {
            ProfilePhoto(
                url = photoUrl, localPath = photoPath, placeholder = signArt, contentDescription = null,
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
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
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
                ProfilePhoto(
                    url = card.photoUrl,
                    placeholder = Art.signKey(c?.sun),
                    contentDescription = if (card.photoUrl != null) "Photo of ${c?.firstName ?: "your match"}" else "${Art.displaySign(c?.sun)} artwork",
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

/** "It's a Conjunction!": the friend's photo pops up with an intro box. */
@Composable
private fun ConjunctionPopup(container: AppContainer, card: EncounterCard?, friend: FriendProfile?, onOpenChat: (ChatTarget) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var intro by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 140f)) }
    val pairId = friend?.pairId ?: card?.encounterId
    val otherUid = friend?.uid ?: card?.otherUid
    val name = friend?.displayName?.substringBefore(' ') ?: card?.card?.firstName ?: "your match"
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.popup_its_a_conjunction), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f })
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GoldText("It's a Conjunction!", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            OrnateDivider()
            Medallion(
                signArt = Art.signKey(friend?.sun ?: card?.card?.sun),
                photoPath = null,
                photoUrl = null,
                size = 220.dp,
                modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            )
            if (friend?.photoUrl != null) {
                ProfilePhoto(friend.photoUrl, Art.signKey(friend.sun), "Photo of $name", Modifier.size(150.dp).clip(CircleShape).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
            }
            GoldText(friend?.displayName ?: name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            friend?.bioFirstLine?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center) }
            Text(
                listOfNotNull(friend?.sun?.let { "☉ ${Art.displaySign(it)}" }, friend?.moon?.let { "☽ ${Art.displaySign(it)}" }, friend?.rising?.let { "AC ${Art.displaySign(it)}" }).joinToString("   "),
                style = MaterialTheme.typography.titleSmall, color = Palette.GoldPale,
            )
            Text("You and $name both said yes. Say hello.", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
            OrnateTextField(intro, { intro = it.take(500) }, label = "Introduce yourself", singleLine = false, minLines = 2)
            FinePrint(stringResource(R.string.chat_first_open_notice))
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.RoseCopper, textAlign = TextAlign.Center) }
            GoldButton(if (sending) "Sending…" else "Send & open chat", enabled = intro.isNotBlank() && !sending && pairId != null, modifier = Modifier.fillMaxWidth(), onClick = {
                sending = true
                scope.launch {
                    try {
                        when (container.nearbyApi.sendMessage(pairId!!, intro.trim())) {
                            "blocked" -> status = container.context.getString(R.string.chat_blocked)
                            else -> onOpenChat(ChatTarget(pairId, otherUid!!))
                        }
                    } catch (e: NearbyException) {
                        status = e.message
                    } finally {
                        sending = false
                    }
                }
            })
            if (pairId != null && otherUid != null) GhostButton("Open chat", onClick = { onOpenChat(ChatTarget(pairId, otherUid)) }, modifier = Modifier.fillMaxWidth())
            GhostButton("Later", onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}
