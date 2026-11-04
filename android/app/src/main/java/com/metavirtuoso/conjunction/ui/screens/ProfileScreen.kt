package com.metavirtuoso.conjunction.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.BuildConfig
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.DailyReminderReceiver
import com.metavirtuoso.conjunction.ai.OnboardingAssistant
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.match.Age
import com.metavirtuoso.conjunction.core.profile.InstagramHandle
import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.PlaceResult
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.legal.ConsentKeys
import com.metavirtuoso.conjunction.legal.ConsentRecord
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.legal.LegalTexts
import com.metavirtuoso.conjunction.nearby.NearbyEngine
import com.metavirtuoso.conjunction.nearby.NearbyRepository
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.CelestialSheet
import com.metavirtuoso.conjunction.ui.components.ChoiceChips
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldSwitchRow
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.PageHeader
import com.metavirtuoso.conjunction.ui.components.ProfilePhoto
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.match.KindChip
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Profile › Settings pages (SPEC 5d.3.2 / 5d.1.3). */
enum class SettingsPage { NEARBY, NOTIFICATIONS, PRIVACY, BLOCKED }

/**
 * Profile (SPEC 5d.7): Your card (live preview, tap to edit), Birth (chips), Eastern (blood type
 * + L3 switch), then the Settings rows. One panel per group.
 */
@Composable
fun ProfileScreen(container: AppContainer, profile: Profile, charts: Charts?, page: SettingsPage?, onPage: (SettingsPage?) -> Unit) {
    if (page != null) {
        androidx.activity.compose.BackHandler { onPage(if (page == SettingsPage.BLOCKED) SettingsPage.PRIVACY else null) }
        StarfieldBackground(dim = 0.72f) {
            Column(Modifier.fillMaxSize()) {
                PageHeader(
                    stringResource(
                        when (page) {
                            SettingsPage.NEARBY -> R.string.settings_nearby
                            SettingsPage.NOTIFICATIONS -> R.string.settings_notifications
                            SettingsPage.PRIVACY -> R.string.settings_privacy_legal_title
                            SettingsPage.BLOCKED -> R.string.settings_blocked
                        },
                    ),
                    onBack = { onPage(if (page == SettingsPage.BLOCKED) SettingsPage.PRIVACY else null) },
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (page) {
                            SettingsPage.NEARBY -> NearbySettings(container, profile)
                            SettingsPage.NOTIFICATIONS -> NotificationSettings(container, profile)
                            SettingsPage.PRIVACY -> PrivacyLegalSettings(container, profile) { onPage(SettingsPage.BLOCKED) }
                            SettingsPage.BLOCKED -> BlockedPeople(container)
                        }
                        Spacer(Modifier.height(44.dp))
                    }
                }
            }
        }
        return
    }
    var editing by remember { mutableStateOf(false) }
    TabPage(stringResource(R.string.tab_profile), subtitle = profile.displayName.ifBlank { null }) {
        YourCard(profile, charts) { editing = true }
        BirthPanel(container, profile)
        EasternPanel(container, profile)
        OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
            SettingsHeading(stringResource(R.string.settings_title), Modifier.padding(start = 10.dp, top = 8.dp))
            SettingsRow(stringResource(R.string.settings_nearby)) { onPage(SettingsPage.NEARBY) }
            SettingsRow(stringResource(R.string.settings_notifications)) { onPage(SettingsPage.NOTIFICATIONS) }
            SettingsRow(stringResource(R.string.settings_privacy_legal_title)) { onPage(SettingsPage.PRIVACY) }
        }
    }
    if (editing) CardEditorSheet(container, profile, charts) { editing = false }
}

@Composable
private fun SettingsHeading(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.titleMedium, color = Palette.Gold, modifier = modifier.padding(bottom = 6.dp).semantics { heading() })
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim) }
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = Palette.Gold)
    }
}

/** Your card: the match card as others see it (photo, first name, age, signs, kind). Tap to edit. */
@Composable
private fun YourCard(profile: Profile, charts: Charts?, onEdit: () -> Unit) {
    val age = profile.birth?.date?.let { Age.yearsOn(it, LocalDate.now()) }
    OrnatePanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit your card", onClick = onEdit)) {
        SettingsHeading(stringResource(R.string.onb_card_title))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePhoto(
                localPath = profile.localPhotoPath,
                placeholder = charts?.natal?.sunSign?.let(Art::sign) ?: R.drawable.brand_mark,
                contentDescription = stringResource(R.string.photo_title),
                modifier = Modifier.width(96.dp).aspectRatio(4f / 5f).clip(CutCornerShape(10.dp)).border(2.dp, GoldBrushes.leaf, CutCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GoldText("${profile.firstName.ifBlank { charts?.natal?.sunSign?.let { "A ${it.displayName}" } ?: "You" }}${age?.let { ", $it" } ?: ""}", style = MaterialTheme.typography.titleLarge)
                charts?.let { c ->
                    Text(
                        listOfNotNull("☉ ${c.natal.sunSign.displayName}", "☽ ${c.natal.moonSign.displayName}", c.natal.risingSign?.let { "AC ${it.displayName}" }).joinToString("  "),
                        style = MaterialTheme.typography.bodySmall, color = Palette.GoldPale,
                    )
                }
                profile.lookingFor?.let { KindChip(if (it == com.metavirtuoso.conjunction.core.match.LookingFor.FRIENDS) "friends" else "dating") }
                if (profile.bio.isNotBlank()) Text("“${profile.bio}”", style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone, maxLines = 2)
                profile.instagram?.let { Text("Instagram @$it", style = MaterialTheme.typography.labelSmall, color = Palette.MoonstoneDim) }
            }
        }
        Text("Tap to edit", style = MaterialTheme.typography.labelSmall, color = Palette.MoonstoneDim, modifier = Modifier.align(Alignment.End))
    }
}

/** Edit the card: photo/crop, first name, bio with "Write one for me", dating chips, Instagram. */
@Composable
private fun CardEditorSheet(container: AppContainer, profile: Profile, charts: Charts?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(profile.displayName) }
    var bio by rememberSaveable { mutableStateOf(profile.bio) }
    var ig by rememberSaveable { mutableStateOf(profile.instagram.orEmpty()) }
    var gender by rememberSaveable { mutableStateOf(profile.gender) }
    var seeking by rememberSaveable { mutableStateOf(profile.seeking) }
    var status by rememberSaveable { mutableStateOf(profile.relationshipStatus) }
    var lookingFor by rememberSaveable { mutableStateOf(profile.lookingFor) }
    val assistant = remember(container) { OnboardingAssistant(container.textModel) }
    val igOk = ig.isBlank() || InstagramHandle.normalize(ig) != null
    CelestialSheet(onDismiss = onClose) {
        GoldText(stringResource(R.string.onb_card_title), style = MaterialTheme.typography.headlineSmall, heading = true)
        PhotoEditor(container, profile, charts)
        if (profile.localPhotoPath != null) GhostButton(stringResource(R.string.photo_remove), color = Palette.RoseCopper, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { profile.localPhotoPath.let { File(it).delete() } }
                container.profileStore.update { it.copy(localPhotoPath = null) }
            }
        })
        OrnateTextField(name, { name = it.take(40) }, label = stringResource(R.string.onb_first_name))
        BioEditor(bio, { bio = it }, charts, lookingFor, profile.interests, profile.localPhotoPath, assistant)
        Text(stringResource(R.string.dating_profile_title).uppercase(), style = MaterialTheme.typography.titleSmall, color = Palette.Gold)
        DatingFields(
            gender, { gender = it }, seeking, { seeking = it },
            status, { s -> status = s; if (s == com.metavirtuoso.conjunction.core.match.RelationshipStatus.IN_A_RELATIONSHIP) lookingFor = com.metavirtuoso.conjunction.core.match.MatchFilter.defaultLookingFor(s) },
            lookingFor, { lookingFor = it },
        )
        InstagramField(ig) { ig = it }
        GoldButton("Save", enabled = igOk, modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                container.profileStore.update {
                    it.copy(
                        displayName = name.trim(), bio = NearbyRepository.trimUtf8(bio.trim(), 280),
                        gender = gender, seeking = seeking, relationshipStatus = status, lookingFor = lookingFor,
                        instagram = InstagramHandle.normalize(ig),
                    )
                }
                onClose()
            }
        })
    }
}

/** Birth: date / time / place chips; changing the date re-runs the 18+ check (SPEC 5d.1.2). */
@Composable
private fun BirthPanel(container: AppContainer, profile: Profile) {
    val scope = rememberCoroutineScope()
    val b = profile.birth
    val lookup = remember(container) { defaultPlaceLookup(container) }
    fun save(date: LocalDate, time: LocalTime?, place: PlaceResult?) {
        scope.launch {
            if (!Age.isAdult(date, LocalDate.now())) {
                container.nearby.disable()
                container.profileStore.update { it.copy(ageBlocked = true, ageGateBirthDate = null, birth = null) }
                return@launch
            }
            val p = place ?: b?.let { PlaceResult(it.placeName, it.latitude, it.longitude, it.zoneId, "") } ?: return@launch
            container.profileStore.update { it.copy(ageGateBirthDate = date, birth = BirthInput(date, time, p.latitude, p.longitude, p.zoneId, p.name)) }
            container.nearby.refreshLocalProfile()
        }
    }
    OrnatePanel(Modifier.fillMaxWidth()) {
        SettingsHeading("Birth")
        BirthChips(
            date = b?.date, time = b?.time, timeUnknown = b != null && b.time == null,
            place = b?.let { PlaceResult(it.placeName, it.latitude, it.longitude, it.zoneId, it.zoneId) },
            placeQuery = b?.placeName, placeCandidates = emptyList(),
            onDate = { d -> save(d, b?.time, null) },
            onTime = { t, _ -> b?.let { save(it.date, t, null) } },
            onPlace = { p -> b?.let { save(it.date, it.time, p) } },
            lookup = lookup, container = container,
        )
        FinePrint(stringResource(R.string.birth_privacy_note), Modifier.padding(top = 8.dp))
    }
}

/** Eastern: blood type chips, Rh, and the L3 switch (only once a type is chosen). */
@Composable
private fun EasternPanel(container: AppContainer, profile: Profile) {
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.blood_type_share_switch)
    val sub = stringResource(R.string.blood_type_share_sub)
    OrnatePanel(Modifier.fillMaxWidth()) {
        SettingsHeading(stringResource(R.string.blood_type_title))
        ChoiceChips(BloodType.entries.map { it to "Type ${it.displayName}" }, profile.bloodType, { t -> scope.launch { container.profileStore.update { it.copy(bloodType = t) } } })
        if (profile.bloodType != null) {
            ChoiceChips(listOf<Pair<RhFactor?, String>>(RhFactor.POSITIVE to "Rh+", RhFactor.NEGATIVE to "Rh−", null to "Not sure"), profile.rh, { r -> scope.launch { container.profileStore.update { it.copy(rh = r) } } }, Modifier.padding(top = 8.dp))
            BloodShareSwitch(container, profile)
            Text(
                "Remove blood type", style = MaterialTheme.typography.labelMedium, color = Palette.RoseCopper,
                modifier = Modifier.clickable(role = Role.Button) {
                    scope.launch {
                        if (profile.bloodForMatching) container.consentLedger.record(ConsentRecord.now(ConsentKeys.BLOOD_TYPE_MATCHING, ConsentKeys.COPY_VERSION, "$label\n$sub", withdrawn = true))
                        container.profileStore.update { it.copy(bloodType = null, rh = null, bloodForMatching = false) }
                    }
                }.padding(vertical = 10.dp),
            )
        }
        FinePrint(stringResource(R.string.blood_type_note))
    }
}

/** Settings › Nearby (SPEC 5d.3.2): every Nearby toggle lives here. */
@Composable
private fun NearbySettings(container: AppContainer, profile: Profile) {
    val scope = rememberCoroutineScope()
    val repo = container.nearby
    val turnOn = rememberNearbyTurnOn(container, profile)
    val snoozed by remember { repo.hiddenStrangers("snooze") }.collectAsState(initial = emptyList())
    var details by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    OrnatePanel(Modifier.fillMaxWidth()) {
        if (!repo.supported) FinePrint(stringResource(R.string.nearby_android_old_version))
        GoldSwitchRow(
            checked = profile.nearbyEnabled, enabled = repo.supported,
            title = stringResource(R.string.nearby_switch_label),
            subtitle = stringResource(if (profile.nearbyEnabled) R.string.nearby_switch_on_desc else R.string.nearby_switch_off_desc),
            onChange = { on -> if (on) turnOn.request() else scope.launch { repo.disable() } },
        )
        if (profile.nearbyEnabled) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.isPaused(now)) {
                    GoldButton(stringResource(R.string.nearby_resume), onClick = { scope.launch { repo.resume() } }, modifier = Modifier.weight(1f))
                } else {
                    GhostButton(stringResource(R.string.nearby_pause_1h), onClick = { scope.launch { repo.pause(System.currentTimeMillis() + 3_600_000L) } }, modifier = Modifier.weight(1f))
                    GhostButton(stringResource(R.string.nearby_pause_tomorrow), onClick = { scope.launch { repo.pause(NearbyRepository.tomorrowMorning()) } }, modifier = Modifier.weight(1f))
                }
            }
        }
        GoldSwitchRow(
            checked = profile.instagramShareDefault,
            onChange = { on -> scope.launch { container.profileStore.update { it.copy(instagramShareDefault = on) } } },
            title = stringResource(R.string.settings_instagram_default),
            subtitle = stringResource(R.string.instagram_note),
        )
        if (profile.bloodType != null) BloodShareSwitch(container, profile, title = stringResource(R.string.settings_blood_share), mirror = true)
        NearbyConsentRow(container, profile)
        Text(
            stringResource(R.string.nearby_details), style = MaterialTheme.typography.bodyLarge.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
            color = Palette.Gold, modifier = Modifier.clickable(role = Role.Button) { details = true }.padding(vertical = 12.dp),
        )
    }
    OrnatePanel(Modifier.fillMaxWidth()) {
        SettingsHeading(stringResource(R.string.settings_snoozed))
        if (snoozed.isEmpty()) FinePrint("Nobody is snoozed.")
        snoozed.forEach { h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Snoozed until ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(h.until))}", style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone, modifier = Modifier.weight(1f))
                GhostButton("Unsnooze", onClick = { scope.launch { repo.unsnooze(h.fingerprint) } })
            }
        }
    }
    if (details) CelestialSheet(onDismiss = { details = false }) { NearbyDetails(onBack = { details = false }) }
}

/** Settings › Notifications: daily reading (switch + time), matches, messages. */
@Composable
private fun NotificationSettings(container: AppContainer, profile: Profile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daily by remember { mutableStateOf(DailyReminderReceiver.isEnabled(context)) }
    var pickTime by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            container.profileStore.update { it.copy(notificationsPrompted = true) }
            if (granted) when (pendingSwitch) {
                "daily" -> { daily = true; DailyReminderReceiver.setEnabled(context, true) }
                "matches" -> container.profileStore.update { it.copy(notifyMatches = true) }
                "messages" -> container.profileStore.update { it.copy(notifyMessages = true) }
            }
            pendingSwitch = null
        }
    }
    fun needsPermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    fun turnOn(which: String, apply: () -> Unit) {
        if (needsPermission()) { pendingSwitch = which; permission.launch(Manifest.permission.POST_NOTIFICATIONS) } else apply()
    }
    OrnatePanel(Modifier.fillMaxWidth()) {
        GoldSwitchRow(daily, { on ->
            if (on) turnOn("daily") { daily = true; DailyReminderReceiver.setEnabled(context, true) }
            else { daily = false; DailyReminderReceiver.setEnabled(context, false) }
        }, stringResource(R.string.settings_notif_daily), profile.dailyReadingTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
        if (daily) GhostButton("Change time", onClick = { pickTime = true })
        GoldSwitchRow(profile.notifyMatches, { on ->
            if (on) turnOn("matches") { scope.launch { container.profileStore.update { it.copy(notifyMatches = true) } } }
            else scope.launch { container.profileStore.update { it.copy(notifyMatches = false) } }
        }, stringResource(R.string.settings_notif_matches))
        GoldSwitchRow(profile.notifyMessages, { on ->
            if (on) turnOn("messages") { scope.launch { container.profileStore.update { it.copy(notifyMessages = true) } } }
            else scope.launch { container.profileStore.update { it.copy(notifyMessages = false) } }
        }, stringResource(R.string.settings_notif_messages))
    }
    if (pickTime) BirthTimeDialog(profile.dailyReadingTime, false, onDone = { t, _ ->
        pickTime = false
        t?.let { time -> scope.launch { container.profileStore.update { it.copy(dailyReadingTime = time) }; DailyReminderReceiver.setTime(context, time) } }
    }, onDismiss = { pickTime = false })
}

/** Settings › Privacy & legal (SPEC 5d.1.3): one panel, plain rows, in this order. */
@Composable
private fun PrivacyLegalSettings(container: AppContainer, profile: Profile, onBlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openDoc by remember { mutableStateOf<LegalDoc?>(null) }
    var licences by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val versions by produceState(emptyMap<LegalDoc, String>()) { value = withContext(Dispatchers.IO) { LegalTexts.bundledVersions(context) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { message = exportData(context, container, profile, uri) }
    }
    OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        // 1. Documents
        SettingsHeading(stringResource(R.string.settings_legal_docs_title))
        listOf(LegalDoc.TERMS, LegalDoc.WAIVER, LegalDoc.PRIVACY, LegalDoc.COMMUNITY, LegalDoc.SAFETY).forEach { doc ->
            SettingsRow(doc.title, versions[doc]?.let { "Version $it" }) { openDoc = doc }
        }
        SettingsRow("Open-source licences") { licences = true }
        Divider()
        // 2. Nearby consent  3. Blood type in Nearby
        NearbyConsentRow(container, profile)
        if (profile.bloodType != null) BloodShareSwitch(container, profile, title = stringResource(R.string.settings_blood_share), mirror = true)
        Divider()
        // 4. Records  5. Arbitration  6. Blocked people
        SettingsRow(stringResource(R.string.settings_legal_records)) { records = true }
        SettingsRow(stringResource(R.string.settings_arbitration_optout), stringResource(R.string.settings_arbitration_optout_hint)) { arbitrationOptOut(context, container) }
        SettingsRow(stringResource(R.string.settings_blocked), onClick = onBlocked)
        Divider()
        // 7. Export / delete
        SettingsRow("Export my data") { exporter.launch("conjunction-export.json") }
        Text(
            stringResource(R.string.settings_delete_data), style = MaterialTheme.typography.titleMedium, color = Palette.RoseCopper,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { confirmDelete = true }.padding(horizontal = 10.dp, vertical = 14.dp),
        )
        // 8. Notes
        FinePrint(stringResource(R.string.settings_no_server_note), Modifier.padding(horizontal = 10.dp))
        FinePrint(stringResource(R.string.settings_contact, BuildConfig.SUPPORT_EMAIL), Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        FinePrint("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.padding(horizontal = 10.dp))
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.GoldPale, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    openDoc?.let { LegalDocDialog(it, onDismiss = { openDoc = null }) }
    if (licences) LicencesDialog { licences = false }
    if (records) ConsentRecordsDialog(container) { records = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.InkViolet,
            title = { GoldText(stringResource(R.string.settings_delete_data), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.settings_delete_data_confirm), color = Palette.Moonstone, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { message = deleteEverything(context, container) }
                }) { Text("DELETE", color = Palette.RoseCopper) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", color = Palette.MoonstoneDim) } },
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Palette.GoldDeep.copy(alpha = 0.45f)))
}

/** Settings › Privacy & legal › Blocked people: blocked former friends and blocked cards, with Unblock. */
@Composable
private fun BlockedPeople(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val friends by remember { container.nearby.blockedFriends() }.collectAsState(initial = emptyList())
    val strangers by remember { container.nearby.hiddenStrangers("block") }.collectAsState(initial = emptyList())
    OrnatePanel(Modifier.fillMaxWidth()) {
        if (friends.isEmpty() && strangers.isEmpty()) FinePrint("You haven't blocked anyone.")
        friends.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.firstName, style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone, modifier = Modifier.weight(1f))
                GhostButton(stringResource(R.string.settings_unblock), onClick = { scope.launch { container.nearby.unblockFriend(f.id) } })
            }
        }
        strangers.forEachIndexed { i, h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Someone nearby (card ${i + 1})", style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone, modifier = Modifier.weight(1f))
                GhostButton(stringResource(R.string.settings_unblock), onClick = { scope.launch { container.nearby.unblockStranger(h.fingerprint) } })
            }
        }
        if (friends.isNotEmpty()) FinePrint("Unblocking a former friend does not restore your chat; you would need to match again.")
    }
}

/** Writes this phone's data (profile, consents, Nearby friends and chats) to [uri]. There is no server copy. */
private suspend fun exportData(context: Context, container: AppContainer, profile: Profile, uri: Uri): String = try {
    val json = JSONObject()
    json.put("exportedAt", java.time.Instant.now().toString())
    json.put("app", "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}")
    json.put("onThisPhone", JSONObject().apply {
        put("displayName", profile.displayName)
        put("bio", profile.bio)
        profile.birth?.let { b ->
            put("birth", JSONObject().put("date", b.date.toString()).put("time", b.time?.toString() ?: JSONObject.NULL)
                .put("latitude", b.latitude).put("longitude", b.longitude).put("zoneId", b.zoneId).put("placeName", b.placeName))
        }
        put("bloodType", profile.bloodType?.name ?: JSONObject.NULL)
        put("rh", profile.rh?.sign ?: JSONObject.NULL)
        put("bloodForMatching", profile.bloodForMatching)
        profile.dating?.let { d -> put("dating", JSONObject().put("gender", d.gender.wire).put("seeking", d.seeking.wire).put("relationshipStatus", d.relationshipStatus.wire).put("lookingFor", d.lookingFor.wire)) }
        put("nearbyEnabled", profile.nearbyEnabled)
        put("consents", JSONArray(container.consentLedger.all().map { it.toJson() }))
    })
    container.nearby.store.load()
    json.put("nearby", JSONObject(com.metavirtuoso.conjunction.nearby.P2pStore.StateSerializer.encode(container.nearby.store.state.value)).apply {
        // Friend secrets are key material, not personal data: left out of the export.
        optJSONArray("friends")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).remove("fs") }
    })
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) } ?: error("could not open file")
    }
    "Export saved."
} catch (e: Exception) {
    "Export failed: ${e.message}"
}

/** Full erasure on this phone: Nearby (friends, chats, photos, identity key), readings, consents, profile. */
private suspend fun deleteEverything(context: Context, container: AppContainer): String {
    container.nearby.deleteAll()
    DailyReminderReceiver.setEnabled(context, false)
    container.readingCache.clear()
    container.consentLedger.clear()
    withContext(Dispatchers.IO) {
        File(context.filesDir, "palms").deleteRecursively()
        File(context.filesDir, "profile_photo.jpg").delete()
    }
    container.profileStore.clear()
    return "Your data was deleted from this phone."
}

/** Arbitration opt-out email (legal/IN_APP_CONSENT_COPY.md section 12), with the date the Waiver was accepted. */
private fun arbitrationOptOut(context: Context, container: AppContainer) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
        val accepted = container.consentLedger.all().lastOrNull { it.key == LegalDoc.WAIVER.key && !it.withdrawn }?.acceptedAtUtc ?: "unknown"
        com.metavirtuoso.conjunction.nearby.ReportEmail.open(context, "Arbitration Opt-Out", "I opt out of the arbitration agreement.\n\nWaiver accepted: $accepted\nApp: ${BuildConfig.VERSION_NAME}")
    }
}

/** Settings → Legal → My acceptance records: the on-phone consent ledger. */
@Composable
private fun ConsentRecordsDialog(container: AppContainer, onDismiss: () -> Unit) {
    val records by container.consentLedger.records.collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkViolet,
        title = { GoldText(stringResource(R.string.settings_legal_records), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (records.isEmpty()) FinePrint("No records yet.")
                records.forEach { r ->
                    Text(
                        "${r.key} v${r.version}${if (r.withdrawn) " (withdrawn)" else ""}\n${r.acceptedAtUtc} · ${r.textSha256.take(12)}…${r.docSha256?.let { " · doc ${it.take(12)}…" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone, modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = Palette.Gold) } },
    )
}

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkViolet,
        title = { GoldText("Open-source licences", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    "Cinzel Decorative — Natanael Gama — SIL Open Font License 1.1",
                    "Cinzel — The Cinzel Project Authors — SIL Open Font License 1.1",
                    "Cormorant Garamond — The Cormorant Project Authors — SIL Open Font License 1.1",
                    "Time-zone boundaries — timezone-boundary-builder (ODbL 1.0) via Timeshape (MIT)",
                    "MediaPipe Tasks & hand landmarker model — Google — Apache License 2.0",
                    "AndroidX, Jetpack Compose, CameraX — Apache License 2.0",
                    "ML Kit GenAI Prompt API — Google — ML Kit terms",
                    "Kotlin & kotlinx.coroutines — JetBrains — Apache License 2.0",
                ).forEach { Text("✦ $it", style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone, modifier = Modifier.padding(vertical = 3.dp)) }
                FinePrint("Full font licences are bundled in the app (assets/licenses).")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = Palette.Gold) } },
    )
}
