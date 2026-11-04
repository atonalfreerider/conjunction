package com.metavirtuoso.conjunction.ui.match

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.Reading
import com.metavirtuoso.conjunction.core.compat.ChineseRelation
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.compat.StemRelation
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.fallback.FallbackReadings
import com.metavirtuoso.conjunction.core.match.MatchKind
import com.metavirtuoso.conjunction.core.p2p.WireCompat
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Instagram
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.data.ReadingSource
import com.metavirtuoso.conjunction.legal.LegalTexts
import com.metavirtuoso.conjunction.nearby.CompatSubject
import com.metavirtuoso.conjunction.nearby.NearbyEngine
import com.metavirtuoso.conjunction.nearby.StoredBlurb
import com.metavirtuoso.conjunction.nearby.StoredScore
import com.metavirtuoso.conjunction.ui.chart.SynastryWheel
import com.metavirtuoso.conjunction.ui.chart.aspectGlyph
import com.metavirtuoso.conjunction.ui.chart.pointGlyph
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.MoreButton
import com.metavirtuoso.conjunction.ui.components.OrnateDivider
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OverlayPage
import com.metavirtuoso.conjunction.ui.components.PageHeader
import com.metavirtuoso.conjunction.ui.components.ProfilePhoto
import com.metavirtuoso.conjunction.ui.components.ReportReadingDialog
import com.metavirtuoso.conjunction.ui.components.ScreenedPhoto
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Compatibility view shows, computed from this phone's record of the person. */
data class CompatView(
    val score: StoredScore,
    val me: CompatProfile?,
    val them: CompatProfile?,
    val lines: List<Compatibility.SynastryLine>,
    val chineseRelation: ChineseRelation?,
    val stemRelation: StemRelation?,
) {
    companion object {
        /**
         * SPEC 5d.4: always recompute from both profiles when the peer's chart is stored (so a
         * change of my birth data shows), else the stored numbers and no wheel.
         */
        fun of(subject: CompatSubject, me: CompatProfile?): CompatView {
            val them = subject.peerCompat?.toCompatProfile()
            if (me != null && them != null) {
                val r = Compatibility.score(me, them)
                return CompatView(NearbyEngine.storedScoreOf(r), me, them, Compatibility.synastry(me, them), r.chineseRelation, r.stemRelation)
            }
            val myAnimal = me?.chineseAnimal
            val theirAnimal = ChineseAnimal.entries.firstOrNull { it.key == subject.animal }
            return CompatView(
                subject.score, me, null, emptyList(),
                subject.score.chineseRelation?.let { n -> ChineseRelation.entries.firstOrNull { it.name == n } }
                    ?: if (myAnimal != null && theirAnimal != null) Compatibility.chineseRelation(myAnimal, theirAnimal) else null,
                subject.score.stemRelation?.let { n -> StemRelation.entries.firstOrNull { it.name == n } },
            )
        }

        /** Cache key of the reading: both compatibility profiles and the kind. */
        fun blurbHash(me: CompatProfile, them: CompatProfile, kind: String): String =
            LegalTexts.sha256("${WireCompat.from(me).toWire()}|${WireCompat.from(them).toWire()}|$kind").take(32)
    }
}

fun chineseRelationLabel(r: ChineseRelation): String = when (r) {
    ChineseRelation.SAME -> "Same animal — a mirror"
    ChineseRelation.SAME_SELF_PUNISHMENT -> "Same animal — mirrors that test each other"
    ChineseRelation.TRINE -> "Trine allies (San He)"
    ChineseRelation.HARMONY -> "Secret friends (Liu He)"
    ChineseRelation.CLASH -> "Clash (Chong) — opposites that spark"
    ChineseRelation.HARM -> "Harm (Hai) — handle with care"
    ChineseRelation.NEUTRAL -> "Neutral — room to grow"
}

fun stemRelationLabel(r: StemRelation): String = when (r) {
    StemRelation.COMBINATION -> "Day masters combine (He)"
    StemRelation.SAME_STEM -> "The same day master"
    StemRelation.SAME_ELEMENT -> "Day masters share an element"
    StemRelation.GENERATING -> "One day master nourishes the other"
    StemRelation.CONTROLLING -> "One day master tempers the other"
}

/** "Your Venus △ their Mars — trine, 2.1°" (SPEC 5d.4 item 4). */
fun aspectRow(l: Compatibility.SynastryLine): String =
    "Your ${l.pointA.label} ${aspectGlyph(l.aspect.name)} their ${l.pointB.label} — ${l.aspect.name}, ${"%.1f".format(java.util.Locale.ROOT, l.orbCentideg / 100.0)}°"

/**
 * Compatibility view (SPEC 5d.4): medallions + score, the synastry bi-wheel (or the "next time
 * you're near" placeholder for friends from before UX 2.0), score breakdown, highlights and aspects,
 * the Eastern relations and the on-device reading (cached on the record). Never shows the other
 * person's birth date or hour.
 */
@Composable
fun CompatibilityScreen(container: AppContainer, profile: Profile, myCharts: Charts?, key: String, onClose: () -> Unit) {
    val subject by remember(key) { container.nearby.compatSubject(key) }.collectAsState(initial = null)
    var loaded by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { container.nearby.store.load(); loaded = true }
    OverlayPage(onBack = onClose) {
        StarfieldBackground(dim = 0.72f) {
            val s = subject
            when {
                s != null -> CompatContent(container, profile, myCharts, s, onClose)
                loaded -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("This match is no longer available.", style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone, textAlign = TextAlign.Center)
                    GhostButton("Close", onClick = onClose, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun CompatContent(container: AppContainer, profile: Profile, myCharts: Charts?, s: CompatSubject, onClose: () -> Unit) {
    val context = LocalContext.current
    val me = remember(myCharts, profile.bloodForMatching) { myCharts?.compatProfile(includeBlood = profile.bloodForMatching) }
    val view = remember(s, me) { CompatView.of(s, me) }
    val reduced = LocalReducedMotion.current
    val firstOpen = remember(s.key) { container.nearby.compatOpened.add(s.key) }
    val count = remember(s.key) { Animatable(if (firstOpen && !reduced) 0f else view.score.total.toFloat()) }
    LaunchedEffect(s.key, view.score.total) { count.animateTo(view.score.total.toFloat(), tween(if (firstOpen && !reduced) 1400 else 0)) }
    var menu by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    // Reading: the cached one when it was made for these two profiles, else write it once.
    var reading by remember(s.key) { mutableStateOf(s.blurb?.let { Reading(it.text, if (it.source == "model") ReadingSource.ON_DEVICE_AI else ReadingSource.FALLBACK) }) }
    LaunchedEffect(s.key, view.me, view.them) {
        val a = view.me ?: return@LaunchedEffect
        val b = view.them ?: return@LaunchedEffect
        val hash = CompatView.blurbHash(a, b, s.kind)
        if (s.blurb?.forHash == hash) return@LaunchedEffect
        val kind = if (s.kind == "dating") MatchKind.DATING else MatchKind.FRIENDS
        val result = Compatibility.score(a, b)
        reading = Reading(FallbackReadings.compatibility(a, b, result, kind), ReadingSource.FALLBACK)
        val written = withContext(Dispatchers.Default) { container.readings.compatibility(a, b, result, kind) }
        reading = written
        container.nearby.saveBlurb(s.key, StoredBlurb(written.text, if (written.source == ReadingSource.ON_DEVICE_AI) "model" else "fallback", hash))
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.compat_title, s.name), onBack = onClose) {
            Box {
                MoreButton("More", onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.InkViolet) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.report_reading), color = Palette.Moonstone) }, onClick = { menu = false; report = true })
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 1. Medallions joined by the conjunction glyph, total score and kind chip.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    ProfilePhoto(
                        profile.localPhotoPath, myCharts?.natal?.sunSign?.let(Art::sign) ?: R.drawable.brand_mark, "You",
                        Modifier.size(92.dp).clip(CircleShape).border(2.dp, GoldBrushes.leaf, CircleShape),
                    )
                    Text("  ☌︎  ", style = MaterialTheme.typography.displaySmall, color = Palette.Gold)
                    ScreenedPhoto(
                        s.photoPath, s.photoScreening, Art.signKey(s.sun), s.name,
                        Modifier.size(92.dp).clip(CircleShape).border(2.dp, GoldBrushes.leaf, CircleShape), compact = true,
                    )
                }
                GoldText("${count.value.roundToInt()}%", style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindChip(s.kind)
                    if (view.score.birthdayTwin) KindChip(if (view.score.exactTwin) "Exact twins" else "Birthday twins", raw = true)
                    s.peerIg?.let { handle -> InstagramChip(handle) { Instagram.open(context, handle) } }
                }
                // 2. The synastry bi-wheel (or the placeholder for friends made before UX 2.0).
                OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                    if (view.me != null && view.them != null) {
                        SynastryWheel(view.me, view.them, view.lines, Modifier.fillMaxWidth())
                        FinePrint(stringResource(R.string.compat_legend, s.name), Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
                    } else {
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.popup_friend_connected), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(150.dp).padding(8.dp).clip(CircleShape), alpha = 0.22f)
                            Text(stringResource(R.string.compat_wheel_pending, s.name), style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                // 3. Score breakdown.
                OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                    ScoreBar(stringResource(R.string.compat_western), view.score.western)
                    ScoreBar(stringResource(R.string.compat_chinese), view.score.chinese)
                    view.score.blood?.let { ScoreBar(stringResource(R.string.compat_blood), it) }
                    FinePrint(
                        "Weights: Western ${Compatibility.WEIGHT_WESTERN} · Chinese ${Compatibility.WEIGHT_CHINESE}" +
                            if (view.score.blood != null) " · Blood type ${Compatibility.WEIGHT_BLOOD}" else "",
                        Modifier.padding(top = 4.dp),
                    )
                }
                // 4. Highlights and the aspect list.
                if (view.score.highlights.isNotEmpty() || view.lines.isNotEmpty()) {
                    OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                        view.score.highlights.forEach { Text("✦ $it", style = MaterialTheme.typography.bodyMedium, color = Palette.GoldPale) }
                        if (view.lines.isNotEmpty()) {
                            Text(stringResource(R.string.compat_aspects_title).uppercase(), style = MaterialTheme.typography.titleSmall, color = Palette.Gold, modifier = Modifier.padding(top = 8.dp))
                            view.lines.take(8).forEach { l ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(pointGlyph(l.pointA), color = Palette.GoldPale, modifier = Modifier.width(26.dp))
                                    Text(aspectRow(l), style = MaterialTheme.typography.bodySmall, color = if (l.isHarmonious) Palette.Moonstone else Palette.RoseCopper)
                                }
                            }
                        }
                    }
                }
                // 5. Eastern relations with the two animal arts.
                if (view.chineseRelation != null || view.stemRelation != null) {
                    OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                        Text(stringResource(R.string.compat_eastern_title).uppercase(), style = MaterialTheme.typography.titleSmall, color = Palette.Gold)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            myCharts?.chinese?.yearAnimal?.let { Image(painterResource(Art.animal(it)), contentDescription = it.displayName, modifier = Modifier.size(56.dp).clip(CircleShape)) }
                            Spacer(Modifier.width(6.dp))
                            Image(painterResource(Art.animalKey(s.animal)), contentDescription = Art.displayAnimal(s.animal), modifier = Modifier.size(56.dp).clip(CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                view.chineseRelation?.let { Text(chineseRelationLabel(it), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone) }
                                view.stemRelation?.let { Text(stemRelationLabel(it), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone) }
                            }
                        }
                    }
                }
                // 6. The reading.
                reading?.let { r ->
                    OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                        Text(stringResource(R.string.compat_reading_title).uppercase(), style = MaterialTheme.typography.titleSmall, color = Palette.Gold)
                        OrnateDivider(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp))
                        Text(r.text, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                        FinePrint(stringResource(if (r.source == ReadingSource.ON_DEVICE_AI) R.string.ai_source_device else R.string.ai_source_fallback), Modifier.padding(top = 6.dp))
                    }
                }
                // 7. The one entertainment line.
                FinePrint(stringResource(R.string.match_card_entertainment_note), Modifier.padding(bottom = 24.dp))
            }
        }
    }
    if (report) ReportReadingDialog(stringResource(R.string.compat_title, s.name), reading?.text.orEmpty()) { report = false }
}

@Composable
private fun ScoreBar(label: String, value: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone, modifier = Modifier.weight(1f))
            Text("$value", style = MaterialTheme.typography.titleSmall, color = Palette.GoldPale)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CutCornerShape(4.dp)).background(Palette.Midnight)) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0, 100) / 100f).height(8.dp).background(GoldBrushes.leaf))
        }
    }
}

/** Dating / Friends (or a free label) chip. */
@Composable
fun KindChip(kind: String, raw: Boolean = false) {
    val text = if (raw) kind else if (kind == "dating") "Dating" else "Friends"
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.GoldPale,
        modifier = Modifier.border(1.dp, GoldBrushes.leaf, CutCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The Instagram chip (SPEC 5d.6): opens https://www.instagram.com/<handle>/. */
@Composable
fun InstagramChip(handle: String, onClick: () -> Unit) {
    Text(
        "${stringResource(R.string.instagram_chip)} @$handle", style = MaterialTheme.typography.labelSmall, color = Palette.Midnight,
        modifier = Modifier.clip(CutCornerShape(6.dp)).background(GoldBrushes.leaf)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
