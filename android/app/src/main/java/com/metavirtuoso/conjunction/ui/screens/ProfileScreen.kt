package com.metavirtuoso.conjunction.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.BuildConfig
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.DailyReminderReceiver
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.DatingProfile
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.legal.ConsentKeys
import com.metavirtuoso.conjunction.legal.ConsentRecord
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.palm.PalmScanner
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.ChoiceChips
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldCheckRow
import com.metavirtuoso.conjunction.ui.components.GoldSwitchRow
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.ProfilePhoto
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profile & settings: photo, name, bio, birth details, blood type, dating profile and its consent,
 * notifications, Nearby pause, legal documents, data export, account deletion, licences.
 */
@Composable
fun ProfileScreen(container: AppContainer, profile: Profile, charts: Charts?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingBirth by remember { mutableStateOf(false) }
    var openDoc by remember { mutableStateOf<LegalDoc?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmWithdraw by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLicences by remember { mutableStateOf(false) }
    var showRecords by remember { mutableStateOf(false) }

    if (editingBirth) {
        BirthEditor(container, profile) { editingBirth = false }
        return
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { message = setProfilePhoto(context, container, uri) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { message = exportData(context, container, profile, uri) }
    }

    TabPage(stringResource(R.string.tab_profile), subtitle = profile.displayName.ifBlank { null }) {
        // Photo
        OrnatePanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfilePhoto(
                    localPath = profile.localPhotoPath,
                    placeholder = charts?.natal?.sunSign?.let(Art::sign) ?: R.drawable.brand_mark,
                    contentDescription = "Your profile photo",
                    modifier = Modifier.size(96.dp).clip(CircleShape).border(2.dp, GoldBrushes.leaf, CircleShape),
                )
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(stringResource(R.string.photo_title).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
                    Text(
                        if (profile.localPhotoPath != null) "Saved on this phone. Sent (640 px) only to people you match with nearby."
                        else "No photo yet: your sun-sign art is shown instead.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone,
                    )
                }
            }
            FinePrint(stringResource(R.string.photo_body))
            FinePrint(stringResource(R.string.photo_tip))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(stringResource(if (profile.localPhotoPath == null) R.string.photo_add else R.string.photo_change), modifier = Modifier.weight(1f), onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                if (profile.localPhotoPath != null) GhostButton(stringResource(R.string.photo_remove), modifier = Modifier.weight(1f), color = Palette.RoseCopper, onClick = {
                    scope.launch {
                        profile.localPhotoPath.let { File(it).delete() }
                        container.profileStore.update { it.copy(localPhotoPath = null) }
                    }
                })
            }
        }
        // Name & bio
        var name by remember(profile.displayName) { mutableStateOf(profile.displayName) }
        var bio by remember(profile.bio) { mutableStateOf(profile.bio) }
        OrnatePanel(Modifier.fillMaxWidth()) {
            OrnateTextField(name, { name = it.take(40) }, label = "First name")
            OrnateTextField(bio, { bio = it.take(500) }, label = "Short bio", singleLine = false, minLines = 3, supporting = "Your first line goes on your card to people you match with nearby.")
            GoldButton("Save", enabled = name != profile.displayName || bio != profile.bio, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    container.profileStore.update { it.copy(displayName = name.trim(), bio = bio.trim()) }
                    message = "Saved on this phone."
                }
            })
        }
        // Birth details
        OrnatePanel(Modifier.fillMaxWidth()) {
            SettingsHeading("Birth details")
            val b = profile.birth
            Text(
                if (b == null) "Not set" else "${b.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))} · ${b.time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: "time unknown"}\n${b.placeName} (${b.zoneId})",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone,
            )
            FinePrint(stringResource(R.string.birth_privacy_note))
            GhostButton("Edit birth details", onClick = { editingBirth = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        // Blood type
        val bloodConsentText = stringResource(R.string.blood_type_consent_checkbox)
        OrnatePanel(Modifier.fillMaxWidth()) {
            SettingsHeading(stringResource(R.string.blood_type_title))
            ChoiceChips(BloodType.entries.map { it to "Type ${it.displayName}" }, profile.bloodType, { t -> scope.launch { container.profileStore.update { it.copy(bloodType = t) } } })
            if (profile.bloodType != null) {
                ChoiceChips(listOf<Pair<RhFactor?, String>>(RhFactor.POSITIVE to "Rh+", RhFactor.NEGATIVE to "Rh−", null to "Not sure"), profile.rh, { r -> scope.launch { container.profileStore.update { it.copy(rh = r) } } }, Modifier.padding(top = 8.dp))
                GoldCheckRow(profile.bloodForMatching, { on ->
                    scope.launch {
                        container.consentLedger.record(ConsentRecord.now(ConsentKeys.BLOOD_TYPE_MATCHING, ConsentKeys.COPY_VERSION, bloodConsentText, withdrawn = !on))
                        container.profileStore.update { it.copy(bloodForMatching = on) }
                    }
                }, bloodConsentText, note = stringResource(R.string.blood_type_consent_note))
                if (profile.bloodForMatching) GhostButton(stringResource(R.string.settings_remove_blood_type), modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        container.consentLedger.record(ConsentRecord.now(ConsentKeys.BLOOD_TYPE_MATCHING, ConsentKeys.COPY_VERSION, bloodConsentText, withdrawn = true))
                        container.profileStore.update { it.copy(bloodForMatching = false) }
                    }
                })
                GhostButton("Remove blood type", color = Palette.RoseCopper, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        if (profile.bloodForMatching) container.consentLedger.record(ConsentRecord.now(ConsentKeys.BLOOD_TYPE_MATCHING, ConsentKeys.COPY_VERSION, bloodConsentText, withdrawn = true))
                        container.profileStore.update { it.copy(bloodType = null, rh = null, bloodForMatching = false) }
                    }
                })
            }
        }
        DatingSettings(container, profile, charts, onWithdraw = { confirmWithdraw = true })
        // Notifications & Nearby
        OrnatePanel(Modifier.fillMaxWidth()) {
            SettingsHeading("Notifications & Nearby")
            var reminder by remember { mutableStateOf(DailyReminderReceiver.isEnabled(context)) }
            GoldSwitchRow(reminder, { on -> reminder = on; DailyReminderReceiver.setEnabled(context, on) }, "Morning reminder", "A notification at 8:00 when today's reading is ready.")
            if (profile.nearbyEnabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(stringResource(R.string.nearby_pause_1h), onClick = { scope.launch { container.nearby.pause(System.currentTimeMillis() + 3_600_000L) } }, modifier = Modifier.weight(1f))
                    GhostButton("Turn Nearby off", onClick = { scope.launch { container.nearby.disable() } }, modifier = Modifier.weight(1f))
                }
            }
        }
        // Legal
        OrnatePanel(Modifier.fillMaxWidth()) {
            SettingsHeading("Legal")
            LegalDoc.entries.forEach { doc -> GhostButton(doc.title, onClick = { openDoc = doc }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) }
            GhostButton("Open-source licences", onClick = { showLicences = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
            GhostButton(stringResource(R.string.settings_legal_records), onClick = { showRecords = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
            FinePrint(stringResource(R.string.settings_arbitration_optout_hint), Modifier.clickable(role = Role.Button) { arbitrationOptOut(context, container) })
            FinePrint(stringResource(R.string.settings_contact, BuildConfig.SUPPORT_EMAIL))
        }
        // Your data
        OrnatePanel(Modifier.fillMaxWidth()) {
            SettingsHeading("Your data")
            FinePrint(stringResource(R.string.settings_no_server_note))
            GhostButton("Export my data", onClick = { exporter.launch("conjunction-export.json") }, modifier = Modifier.fillMaxWidth())
            GhostButton(stringResource(R.string.settings_delete_data), color = Palette.RoseCopper, onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            FinePrint("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.padding(top = 8.dp))
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.GoldPale, textAlign = TextAlign.Center) }
    }

    openDoc?.let { LegalDocDialog(it, onDismiss = { openDoc = null }) }
    if (showLicences) LicencesDialog { showLicences = false }
    if (showRecords) ConsentRecordsDialog(container) { showRecords = false }
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
    if (confirmWithdraw) {
        val consentText = stringResource(R.string.orientation_consent_checkbox)
        AlertDialog(
            onDismissRequest = { confirmWithdraw = false },
            containerColor = Palette.InkViolet,
            title = { GoldText(stringResource(R.string.settings_withdraw_orientation), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.settings_withdraw_orientation_confirm), color = Palette.Moonstone, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWithdraw = false
                    scope.launch {
                        container.consentLedger.record(ConsentRecord.now(ConsentKeys.ORIENTATION_DATA, ConsentKeys.COPY_VERSION, consentText, withdrawn = true))
                        container.nearby.disable()
                        container.profileStore.update { it.copy(dating = null, orientationConsent = false) }
                    }
                }) { Text("WITHDRAW", color = Palette.RoseCopper) }
            },
            dismissButton = { TextButton(onClick = { confirmWithdraw = false }) { Text("CANCEL", color = Palette.MoonstoneDim) } },
        )
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.titleMedium, color = Palette.Gold, modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
}

@Composable
private fun DatingSettings(container: AppContainer, profile: Profile, charts: Charts?, onWithdraw: () -> Unit) {
    val scope = rememberCoroutineScope()
    val d = profile.dating
    var gender by remember(d) { mutableStateOf(d?.gender) }
    var seeking by remember(d) { mutableStateOf(d?.seeking) }
    var status by remember(d) { mutableStateOf(d?.relationshipStatus) }
    var lookingFor by remember(d) { mutableStateOf(d?.lookingFor) }
    var consent by remember { mutableStateOf(profile.orientationConsent) }
    var honest by remember { mutableStateOf(profile.honestyConfirmed) }
    var saved by remember { mutableStateOf<String?>(null) }
    val consentText = stringResource(R.string.orientation_consent_checkbox)
    val honestyText = stringResource(R.string.honesty_checkbox)
    OrnatePanel(Modifier.fillMaxWidth()) {
        SettingsHeading(stringResource(R.string.dating_profile_title))
        DatingFields(gender, { gender = it }, seeking, { seeking = it }, status, { s ->
            status = s
            if (s == com.metavirtuoso.conjunction.core.match.RelationshipStatus.IN_A_RELATIONSHIP) lookingFor = com.metavirtuoso.conjunction.core.match.MatchFilter.defaultLookingFor(s)
        }, lookingFor, { lookingFor = it })
        if (!profile.orientationConsent) {
            Text(stringResource(R.string.orientation_consent_body), style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone, modifier = Modifier.padding(top = 8.dp))
            GoldCheckRow(consent, { consent = it }, consentText, note = stringResource(R.string.orientation_consent_withdraw_note))
        }
        if (!profile.honestyConfirmed) GoldCheckRow(honest, { honest = it }, honestyText)
        val complete = gender != null && seeking != null && status != null && lookingFor != null && (profile.orientationConsent || consent) && (profile.honestyConfirmed || honest)
        GoldButton("Save", enabled = complete, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = {
            scope.launch {
                if (!profile.orientationConsent) container.consentLedger.record(ConsentRecord.now(ConsentKeys.ORIENTATION_DATA, ConsentKeys.COPY_VERSION, consentText))
                if (!profile.honestyConfirmed) container.consentLedger.record(ConsentRecord.now(ConsentKeys.PROFILE_HONESTY, ConsentKeys.COPY_VERSION, honestyText))
                container.profileStore.update { it.copy(dating = DatingProfile(gender!!, seeking!!, status!!, lookingFor!!), orientationConsent = true, honestyConfirmed = true) }
                saved = "Saved on this phone."
            }
        })
        saved?.let { FinePrint(it) }
        if (profile.orientationConsent) GhostButton(stringResource(R.string.settings_withdraw_orientation), color = Palette.RoseCopper, onClick = onWithdraw, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

/** Downscales a picked photo to a 1080 px JPEG kept on the phone (the Nearby card sends a 640 px copy). */
private suspend fun setProfilePhoto(context: Context, container: AppContainer, uri: Uri): String = try {
    val jpeg = withContext(Dispatchers.Default) {
        val bitmap = withContext(Dispatchers.IO) { PalmScanner.loadUri(context, uri) }
        val scaled = PalmScanner.downscale(bitmap, 1080)
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }
    val file = File(context.filesDir, "profile_photo.jpg")
    withContext(Dispatchers.IO) { file.writeBytes(jpeg) }
    container.profileStore.update { it.copy(localPhotoPath = file.absolutePath) }
    "Photo saved on this phone."
} catch (e: Exception) {
    "The photo could not be used: ${e.message}"
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
                        "${r.key} v${r.version}${if (r.withdrawn) " (withdrawn)" else ""}\n${r.acceptedAtUtc} · ${r.textSha256.take(12)}…",
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
