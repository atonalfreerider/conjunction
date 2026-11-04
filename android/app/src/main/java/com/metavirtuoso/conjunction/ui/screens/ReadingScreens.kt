package com.metavirtuoso.conjunction.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.core.blood.BloodTypeReading
import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.chart.formatLongitude
import com.metavirtuoso.conjunction.core.chinese.Pillar
import com.metavirtuoso.conjunction.core.prompt.NatalSection
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.ui.chart.ChartWheel
import com.metavirtuoso.conjunction.ui.chart.textGlyph
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.ArtMedallion
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnateDivider
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.ReadingCard
import com.metavirtuoso.conjunction.ui.components.ReadingState
import com.metavirtuoso.conjunction.ui.components.SectionTitle
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Something a parent puts above a tab page's title (the Readings segment control). */
val LocalTabHeader = androidx.compose.runtime.staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** Common tab page: starfield, title, scrolling content capped to a readable width. */
@Composable
fun TabPage(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val header = LocalTabHeader.current
    StarfieldBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                header?.invoke()
                SectionTitle(title, subtitle = subtitle)
                content()
                // Room for the raised Nearby medallion over the end of the page.
                Spacer(Modifier.height(44.dp))
            }
        }
    }
}

enum class ReadingsSegment { EASTERN, PALM }

/** Readings (SPEC 5d.2): Eastern and Palm under one "Eastern | Palm" segment control. */
@Composable
fun ReadingsScreen(container: AppContainer, profile: Profile, charts: Charts?, segment: ReadingsSegment, onSegment: (ReadingsSegment) -> Unit) {
    val header: @Composable () -> Unit = {
        com.metavirtuoso.conjunction.ui.components.ChoiceChips(
            listOf(
                ReadingsSegment.EASTERN to androidx.compose.ui.res.stringResource(com.metavirtuoso.conjunction.R.string.readings_segment_eastern),
                ReadingsSegment.PALM to androidx.compose.ui.res.stringResource(com.metavirtuoso.conjunction.R.string.readings_segment_palm),
            ),
            segment, onSegment,
            centered = true,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTabHeader provides header) {
        when (segment) {
            ReadingsSegment.EASTERN -> EasternScreen(container, profile, charts)
            ReadingsSegment.PALM -> PalmScreen(container)
        }
    }
}

@Composable
fun NoBirthData() {
    OrnatePanel(Modifier.fillMaxWidth()) {
        Text("Add your birth date, time and place in Profile to cast your chart.", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BigThree(charts: Charts) {
    val natal = charts.natal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOfNotNull(
            "Sun" to natal.sunSign,
            "Moon" to natal.moonSign,
            natal.risingSign?.let { "Rising" to it },
        ).forEach { (label, sign) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArtMedallion(Art.sign(sign), contentDescription = "$label in ${sign.displayName}", size = 96.dp)
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Gold)
                Text(sign.displayName, style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone)
            }
        }
    }
}

@Composable
fun ChartScreen(container: AppContainer, charts: Charts?) {
    TabPage("Your Chart", subtitle = charts?.let { "${it.birth.placeName} · ${it.birth.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}${it.birth.time?.let { t -> " · $t" } ?: ""}" }) {
        if (charts == null) { NoBirthData(); return@TabPage }
        val natal = charts.natal
        BigThree(charts)
        OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 10.dp) {
            ChartWheel(natal, Modifier.fillMaxWidth())
        }
        if (natal.ascendant == null) FinePrint("Birth time unknown: the wheel starts at 0° Aries and has no houses or Ascendant.${if (natal.moonSignUncertain) " Your Moon changed sign that day, so the Moon sign is uncertain." else ""}")
        OrnatePanel(Modifier.fillMaxWidth()) {
            Text("PLACEMENTS", style = MaterialTheme.typography.titleMedium, color = Palette.Gold, modifier = Modifier.semantics { heading() })
            OrnateDivider(Modifier.padding(vertical = 4.dp).align(Alignment.CenterHorizontally))
            natal.bodies.forEach { body ->
                PlacementRow(
                    glyph = textGlyph(body.planet.glyph),
                    name = body.name + if (body.retrograde) " ℞" else "",
                    position = formatLongitude(body.longitude),
                    house = body.house?.let { "House $it" } ?: "",
                )
            }
            natal.ascendant?.let { PlacementRow("AC", "Ascendant", formatLongitude(it), "") }
            natal.midheaven?.let { PlacementRow("MC", "Midheaven", formatLongitude(it), "") }
            FinePrint("Tropical zodiac, ${natal.houseSystem.name.lowercase().replace('_', '-')} houses${if (natal.housesFellBack) " (Porphyry at this latitude)" else ""}. Zone ${charts.birth.zoneId}.")
        }
        if (natal.aspects.isNotEmpty()) {
            OrnatePanel(Modifier.fillMaxWidth()) {
                Text("ASPECTS", style = MaterialTheme.typography.titleMedium, color = Palette.Gold, modifier = Modifier.semantics { heading() })
                OrnateDivider(Modifier.padding(vertical = 4.dp).align(Alignment.CenterHorizontally))
                natal.aspects.take(14).forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(textGlyph(a.type.glyph), color = Palette.GoldPale, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(30.dp))
                        Text(a.label, color = Palette.Moonstone, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("%.1f°".format(a.orb) + if (a.applying) " app." else " sep.", color = Palette.MoonstoneDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        NatalReadings(container, charts)
    }
}

@Composable
private fun PlacementRow(glyph: String, name: String, position: String, house: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, color = Palette.GoldPale, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(36.dp))
        Text(name, color = Palette.Moonstone, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(position, color = Palette.GoldPale, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
        Text(house, color = Palette.MoonstoneDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
    }
}

/** Natal reading: one section at a time, written on demand and cached. */
@Composable
private fun NatalReadings(container: AppContainer, charts: Charts) {
    val scope = rememberCoroutineScope()
    val states = remember(charts.fingerprint) { mutableStateMapOf<NatalSection, ReadingState>() }
    var open by remember(charts.fingerprint) { mutableStateOf<NatalSection?>(null) }
    fun load(section: NatalSection, refresh: Boolean) {
        states[section] = ReadingState.Loading()
        scope.launch {
            val reading = container.readings.natal(charts, section, refresh) { status -> states[section] = ReadingState.Loading(status) }
            states[section] = ReadingState.Ready(reading)
        }
    }
    GoldText("Natal Reading", style = MaterialTheme.typography.headlineSmall, heading = true)
    NatalSection.entries.forEach { section ->
        val isOpen = open == section
        OrnatePanel(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (isOpen) "Close" else "Read") {
                open = if (isOpen) null else section
                if (!isOpen && states[section] !is ReadingState.Ready) load(section, refresh = false)
            },
            contentPadding = 16.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(section.planet?.glyph?.let(::textGlyph) ?: "AC", color = Palette.GoldPale, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(36.dp))
                Text(section.title, style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone, modifier = Modifier.weight(1f))
                Text(if (isOpen) "−" else "+", style = MaterialTheme.typography.titleLarge, color = Palette.Gold)
            }
        }
        if (isOpen) {
            ReadingCard(section.title, states[section] ?: ReadingState.Loading(), onRefresh = { load(section, refresh = true) })
        }
    }
}

@Composable
fun TodayScreen(container: AppContainer, profile: Profile, charts: Charts?) {
    val today = remember { LocalDate.now() }
    val zone = remember { ZoneId.systemDefault() }
    var state by remember { mutableStateOf<ReadingState>(ReadingState.Loading()) }
    val scope = rememberCoroutineScope()
    fun load(refresh: Boolean) {
        val c = charts ?: return
        state = ReadingState.Loading()
        scope.launch {
            val reading = container.readings.daily(c, today, zone, refresh) { status -> state = ReadingState.Loading(status) }
            state = ReadingState.Ready(reading)
        }
    }
    LaunchedEffect(charts?.fingerprint) { load(refresh = false) }
    TabPage("Today", subtitle = today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))) {
        if (charts == null) { NoBirthData(); return@TabPage }
        val sun = charts.natal.sunSign
        ArtMedallion(Art.sign(sun), contentDescription = "${sun.displayName} sun", size = 170.dp)
        Text("${sun.displayName} sun · ${charts.natal.moonSign.displayName} moon${charts.natal.risingSign?.let { " · ${it.displayName} rising" } ?: ""}", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale, textAlign = TextAlign.Center)
        ReadingCard("Your day", state, onRefresh = { load(refresh = true) })
        if (state is ReadingState.Ready) NotificationsCard(container, profile)
        SkyNow(today, zone)
    }
}

/** Where the planets are today (sign per body), as a compact strip. */
@Composable
private fun SkyNow(date: LocalDate, zone: ZoneId) {
    val sky = remember(date) {
        com.metavirtuoso.conjunction.core.astro.Planet.entries.map { p ->
            p to ZodiacSign.of(com.metavirtuoso.conjunction.core.astro.Ephemeris.position(p, com.metavirtuoso.conjunction.core.chart.Transits.noonOf(date, zone)).longitude)
        }
    }
    OrnatePanel(Modifier.fillMaxWidth()) {
        Text("TODAY'S SKY", style = MaterialTheme.typography.titleMedium, color = Palette.Gold, modifier = Modifier.semantics { heading() })
        sky.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                pair.forEach { (planet, sign) ->
                    Text("${textGlyph(planet.glyph)} ${planet.displayName} in ${sign.displayName}", style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun EasternScreen(container: AppContainer, profile: Profile, charts: Charts?) {
    var state by remember { mutableStateOf<ReadingState>(ReadingState.Loading()) }
    val scope = rememberCoroutineScope()
    fun load(refresh: Boolean) {
        val c = charts ?: return
        state = ReadingState.Loading()
        scope.launch {
            state = ReadingState.Ready(container.readings.eastern(c, profile.rh, refresh) { status -> state = ReadingState.Loading(status) })
        }
    }
    LaunchedEffect(charts?.fingerprint, profile.rh) { load(refresh = false) }
    TabPage("Eastern Sky", subtitle = "Chinese zodiac, Four Pillars and blood type") {
        if (charts == null) { NoBirthData(); return@TabPage }
        val chinese = charts.chinese
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArtMedallion(Art.animal(chinese.yearAnimal), contentDescription = chinese.yearAnimal.displayName, size = 150.dp)
                Text("${chinese.yinYang.displayName} ${chinese.yearElement.displayName} ${chinese.yearAnimal.displayName}", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale, textAlign = TextAlign.Center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArtMedallion(Art.element(chinese.yearElement), contentDescription = "${chinese.yearElement.displayName} element", size = 96.dp)
                Text("${chinese.yearElement.hanzi} ${chinese.yearElement.displayName}", style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
            }
        }
        if (chinese.yearsDiffer) {
            FinePrint("Born before the solar year began at Lichun, you are a ${chinese.yearAnimal.displayName} by the solar calendar; by the lunar new year you are a ${chinese.lunarYearElement.displayName} ${chinese.lunarYearAnimal.displayName}.")
        }
        if (chinese.boundaryUncertain) FinePrint("Your birth falls within minutes of a solar-term boundary, so a pillar may differ by convention.")
        OrnatePanel(Modifier.fillMaxWidth()) {
            Text("FOUR PILLARS · BAZI", style = MaterialTheme.typography.titleMedium, color = Palette.Gold, modifier = Modifier.semantics { heading() })
            OrnateDivider(Modifier.padding(vertical = 4.dp).align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Hour" to chinese.pillars.hour, "Day" to chinese.pillars.day, "Month" to chinese.pillars.month, "Year" to chinese.pillars.year).forEach { (label, pillar) ->
                    PillarColumn(label, pillar)
                }
            }
            FinePrint("Day Master: ${chinese.dayMaster.hanzi} ${chinese.dayMaster.pinyin} — ${chinese.dayMaster.label}.")
        }
        val blood = charts.bloodType
        if (blood != null) {
            val traits = remember(blood) { BloodTypeReading.traits(blood) }
            OrnatePanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtMedallion(Art.blood(blood), contentDescription = "Blood type ${blood.displayName}", size = 88.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(BloodTypeReading.label(blood, profile.rh).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
                        Text(traits.title, style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
                        Text(traits.keywords.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim)
                    }
                }
                Text(traits.summary, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone, modifier = Modifier.padding(top = 8.dp))
                FinePrint("Ketsueki-gata is East Asian folklore, not science, and says nothing about health.")
            }
        } else {
            FinePrint("Add your blood type in Profile for a ketsueki-gata (blood-type) reading.")
        }
        ReadingCard("Eastern reading", state, onRefresh = { load(refresh = true) })
    }
}

@Composable
private fun PillarColumn(label: String, pillar: Pillar?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Gold)
        if (pillar == null) {
            Text("—", style = MaterialTheme.typography.headlineMedium, color = Palette.MoonstoneDim)
            Text("time\nunknown", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = Palette.MoonstoneDim, textAlign = TextAlign.Center)
        } else {
            Text(pillar.stem.hanzi, style = MaterialTheme.typography.headlineMedium, color = Palette.GoldPale)
            Text(pillar.branch.hanzi, style = MaterialTheme.typography.headlineMedium, color = Palette.Moonstone)
            Box(Modifier.height(2.dp))
            Text(pillar.stem.label, style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim, textAlign = TextAlign.Center)
            Text(pillar.branch.animal.displayName, style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim)
        }
    }
}

/**
 * One-time card on Today (SPEC 5d.3.2): "Get your reading each morning?" after the first reading
 * is shown, if notifications were never asked. Allow → system prompt (Android 13+) and the daily
 * reminder; Not now → never shown again.
 */
@Composable
private fun NotificationsCard(container: AppContainer, profile: Profile) {
    if (profile.notificationsPrompted) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            container.profileStore.update { it.copy(notificationsPrompted = true) }
            if (granted) com.metavirtuoso.conjunction.ai.DailyReminderReceiver.setEnabled(context, true)
        }
    }
    OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
        Text(androidx.compose.ui.res.stringResource(com.metavirtuoso.conjunction.R.string.perm_notifications_title), style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
        Text(androidx.compose.ui.res.stringResource(com.metavirtuoso.conjunction.R.string.perm_notifications_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone, modifier = Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.metavirtuoso.conjunction.ui.components.GoldButton(androidx.compose.ui.res.stringResource(com.metavirtuoso.conjunction.R.string.perm_notifications_button), modifier = Modifier.weight(1f), onClick = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else scope.launch {
                    com.metavirtuoso.conjunction.ai.DailyReminderReceiver.setEnabled(context, true)
                    container.profileStore.update { it.copy(notificationsPrompted = true) }
                }
            })
            com.metavirtuoso.conjunction.ui.components.GhostButton(androidx.compose.ui.res.stringResource(com.metavirtuoso.conjunction.R.string.not_now), modifier = Modifier.weight(1f), onClick = {
                scope.launch { container.profileStore.update { it.copy(notificationsPrompted = true) } }
            })
        }
    }
}
