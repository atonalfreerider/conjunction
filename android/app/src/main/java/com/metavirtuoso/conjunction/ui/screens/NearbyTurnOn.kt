package com.metavirtuoso.conjunction.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.core.match.MatchFilter
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.data.DatingProfile
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.legal.ConsentKeys
import com.metavirtuoso.conjunction.legal.ConsentRecord
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.nearby.NearbyPermissions
import com.metavirtuoso.conjunction.ui.components.CelestialSheet
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldCheckRow
import com.metavirtuoso.conjunction.ui.components.GoldSwitchRow
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * The one Nearby turn-on flow (SPEC 5d.3.1), used by the empty state, the status card and the
 * Settings switch: L2 sheet when the consent or the dating fields / first name are missing → record
 * the consents → system Bluetooth ("Nearby devices") prompt (+ notifications if never asked) →
 * Nearby starts. "Not now" records nothing.
 */
class NearbyTurnOn internal constructor(private val start: () -> Unit) {
    fun request() = start()
}

@Composable
fun rememberNearbyTurnOn(container: AppContainer, profile: Profile): NearbyTurnOn {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(profile)
    var sheet by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted.containsKey(Manifest.permission.POST_NOTIFICATIONS)) {
                container.profileStore.update { it.copy(notificationsPrompted = true) }
            }
            val ok = NearbyPermissions.BLUETOOTH.all { granted[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            container.nearby.permissionDenied.value = !ok
            if (ok) runCatching { container.nearby.enable() }
        }
    }
    fun permissionsThenEnable(p: Profile) {
        val askNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !p.notificationsPrompted &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (NearbyPermissions.granted(context) && !askNotifications) {
            scope.launch { container.nearby.permissionDenied.value = false; runCatching { container.nearby.enable() } }
        } else {
            val wanted = NearbyPermissions.BLUETOOTH.toList() + (if (askNotifications) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            permissions.launch(wanted.toTypedArray())
        }
    }
    if (sheet) {
        NearbyConsentSheet(
            container, current,
            onTurnOn = { updated -> sheet = false; permissionsThenEnable(updated) },
            onDismiss = { sheet = false },
        )
    }
    return remember {
        NearbyTurnOn {
            val p = current
            if (!p.nearbyConsentGiven || !p.datingFieldsComplete || p.firstName.isBlank()) sheet = true else permissionsThenEnable(p)
        }
    }
}

/** The visible L2 points, in IN_APP_CONSENT_COPY §5 table order. */
val NEARBY_SHEET_POINTS = listOf(
    R.string.nearby_sheet_point_bluetooth, R.string.nearby_sheet_point_sent,
    R.string.nearby_sheet_point_match, R.string.nearby_sheet_point_control,
)

/** The texts the L2 consent hash covers: title, the four points and the checkbox (SPEC 5d.1 L2). */
@Composable
fun nearbySheetText(): String = (
    listOf(stringResource(R.string.nearby_title)) + NEARBY_SHEET_POINTS.map { stringResource(it) } +
        stringResource(R.string.nearby_consent_checkbox)
    ).joinToString("\n")

/**
 * L2: the Nearby consent sheet (prominent disclosure + GDPR Art. 9 explicit consent). Dating chips
 * and the first name appear above the points when missing. [onTurnOn] gets the updated profile.
 */
@Composable
fun NearbyConsentSheet(container: AppContainer, profile: Profile, onTurnOn: (Profile) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ticked by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var safety by remember { mutableStateOf(false) }
    val needsFields = !profile.datingFieldsComplete
    val needsName = profile.firstName.isBlank()
    var name by rememberSaveable { mutableStateOf(profile.displayName) }
    var gender by rememberSaveable { mutableStateOf(profile.gender) }
    var seeking by rememberSaveable { mutableStateOf(profile.seeking) }
    var status by rememberSaveable { mutableStateOf(profile.relationshipStatus) }
    var lookingFor by rememberSaveable { mutableStateOf(profile.lookingFor) }
    val sheetText = nearbySheetText()
    val fieldsOk = gender != null && seeking != null && status != null && lookingFor != null && (!needsName || name.isNotBlank())
    val turnOn: () -> Unit = {
        scope.launch {
            container.consentLedger.record(ConsentRecord.now(ConsentKeys.NEARBY_BLUETOOTH, ConsentKeys.COPY_VERSION, sheetText))
            container.consentLedger.record(ConsentRecord.now(ConsentKeys.ORIENTATION_DATA, ConsentKeys.COPY_VERSION, sheetText))
            var updated: Profile? = null
            container.profileStore.update {
                it.withDating(DatingProfile(gender!!, seeking!!, status!!, lookingFor!!)).copy(
                    displayName = if (it.firstName.isBlank()) name.trim() else it.displayName,
                    orientationConsent = true, nearbyConsent = true,
                ).also { p -> updated = p }
            }
            onTurnOn(updated ?: container.profileStore.current())
        }
    }
    if (details) {
        CelestialSheet(onDismiss = { details = false }) { NearbyDetails(onBack = { details = false }) }
    } else {
        // The checkbox and the actions are pinned under the scrolling points, so Turn on / Not now are
        // always on screen (the sheet is the prominent disclosure, not a page to scroll through).
        CelestialSheet(
            onDismiss = onDismiss,
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SheetLink(stringResource(R.string.nearby_details)) { details = true }
                    SheetLink(stringResource(R.string.nearby_safety_link)) { safety = true }
                }
                GoldCheckRow(ticked, { ticked = it }, stringResource(R.string.nearby_consent_checkbox), modifier = Modifier.testTag("nearbyConsent"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    GhostButton(stringResource(R.string.nearby_not_now), onClick = onDismiss, modifier = Modifier.weight(1f))
                    GoldButton(
                        stringResource(R.string.nearby_turn_on), enabled = ticked && fieldsOk,
                        modifier = Modifier.weight(1f).testTag("turnOn"), onClick = turnOn,
                    )
                }
            },
        ) {
            GoldText(stringResource(R.string.nearby_title), style = MaterialTheme.typography.headlineSmall, heading = true)
            if (needsName) OrnateTextField(name, { name = it.take(40) }, label = stringResource(R.string.onb_first_name))
            if (needsFields) {
                DatingFields(
                    gender, { gender = it }, seeking, { seeking = it },
                    status, { s -> status = s; if (lookingFor == null || s == RelationshipStatus.IN_A_RELATIONSHIP) lookingFor = MatchFilter.defaultLookingFor(s) },
                    lookingFor, { lookingFor = it },
                )
            }
            NEARBY_SHEET_POINTS.forEach {
                Row(Modifier.fillMaxWidth()) {
                    Text("✦  ", color = Palette.Gold, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                }
            }
        }
    }
    if (safety) SafetyTipsDialog(container) { safety = false }
}

@Composable
private fun SheetLink(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        color = Palette.Gold, modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

/** The Details layer: the full nine points (behind "Details"; also in Settings › Nearby). */
@Composable
fun NearbyDetails(onBack: () -> Unit) {
    GoldText(stringResource(R.string.nearby_details_title), style = MaterialTheme.typography.headlineSmall, heading = true)
    listOf(
        R.string.nearby_point_bluetooth, R.string.nearby_point_hello, R.string.nearby_point_card, R.string.nearby_point_keep,
        R.string.nearby_point_swipe, R.string.nearby_point_device_name, R.string.nearby_point_safety,
    ).forEach {
        Row(Modifier.fillMaxWidth()) {
            Text("✦  ", color = Palette.Gold, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
        }
    }
    // Texas Bus. & Com. Code 106.004: bold capitals, 12 pt or larger.
    Text(stringResource(R.string.nearby_point_no_checks), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp), color = Palette.GoldPale)
    Row(Modifier.fillMaxWidth()) {
        Text("✦  ", color = Palette.Gold, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.nearby_point_control), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
    }
    GhostButton(stringResource(R.string.action_back), onClick = onBack, modifier = Modifier.fillMaxWidth())
}

/** Safety Tips reader; "Got it" records SAFETY_TIPS at its version. */
@Composable
fun SafetyTipsDialog(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    LegalDocDialog(LegalDoc.SAFETY, onDismiss = onDismiss, onAcknowledge = { text ->
        scope.launch {
            container.consentLedger.record(ConsentRecord.now(LegalDoc.SAFETY.key, text.version, text.markdown))
            container.profileStore.update { it.copy(safetyTipsSeen = true) }
        }
        onDismiss()
    })
}

/** Withdraw the L2 consent (Settings, SPEC 5d.1.3 item 2): both records withdrawn, Nearby off. */
suspend fun withdrawNearbyConsent(container: AppContainer, confirmText: String) {
    container.consentLedger.record(ConsentRecord.now(ConsentKeys.ORIENTATION_DATA, ConsentKeys.COPY_VERSION, confirmText, withdrawn = true))
    container.consentLedger.record(ConsentRecord.now(ConsentKeys.NEARBY_BLUETOOTH, ConsentKeys.COPY_VERSION, confirmText, withdrawn = true))
    container.nearby.disable()
    container.profileStore.update { it.copy(orientationConsent = false, nearbyConsent = false) }
}

/** "Nearby consent: Given 4 Nov 2026 / Not given" row with Withdraw → confirm (shown in two places). */
@Composable
fun NearbyConsentRow(container: AppContainer, profile: Profile) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val records by container.consentLedger.records.collectAsState(initial = emptyList())
    val at = com.metavirtuoso.conjunction.legal.LegalVersions.nearbyConsentAt(records)
    val given = profile.nearbyConsentGiven
    val confirmText = stringResource(R.string.settings_withdraw_nearby_consent_confirm)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_nearby_consent), style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
            Text(
                // A consent given before the ledger existed has no date: show "Given" alone.
                if (given) stringResource(R.string.settings_nearby_consent_given, at?.let(::formatRecordDate) ?: "").trim() else stringResource(R.string.settings_nearby_consent_not_given),
                style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim,
            )
        }
        if (given) GhostButton(stringResource(R.string.settings_withdraw_nearby_consent), onClick = { confirm = true }, color = Palette.RoseCopper)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Palette.InkViolet,
            title = { GoldText(stringResource(R.string.settings_nearby_consent), style = MaterialTheme.typography.titleLarge) },
            text = { Text(confirmText, color = Palette.Moonstone, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirm = false; scope.launch { withdrawNearbyConsent(container, confirmText) } }) {
                    Text(stringResource(R.string.settings_withdraw_nearby_consent).uppercase(), color = Palette.RoseCopper)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("CANCEL", color = Palette.MoonstoneDim) } },
        )
    }
}

/** "2026-11-04T10:00:00Z" → "4 Nov 2026" in the phone's zone. */
fun formatRecordDate(utc: String): String = runCatching {
    java.time.Instant.parse(utc).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(chipDateFormat)
}.getOrDefault(utc.take(10))

/**
 * L3 / settings_blood_share: one switch, off by default; records consent on and withdrawal off.
 * The full consent sentence is shown only at L3 (Profile › Blood type). [mirror] = a Settings row:
 * a short title and no sentence; switching it on asks with the L3 sentence first (same record).
 */
@Composable
fun BloodShareSwitch(container: AppContainer, profile: Profile, title: String = stringResource(R.string.blood_type_share_switch), mirror: Boolean = false) {
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.blood_type_share_switch)
    val sub = stringResource(R.string.blood_type_share_sub)
    var confirm by remember { mutableStateOf(false) }
    fun set(on: Boolean) {
        scope.launch {
            container.consentLedger.record(ConsentRecord.now(ConsentKeys.BLOOD_TYPE_MATCHING, ConsentKeys.COPY_VERSION, "$label\n$sub", withdrawn = !on))
            container.profileStore.update { it.copy(bloodForMatching = on) }
        }
    }
    GoldSwitchRow(
        checked = profile.bloodForMatching,
        onChange = { on -> if (on && mirror) confirm = true else set(on) },
        title = title,
        subtitle = if (mirror) null else sub,
        modifier = Modifier.testTag(if (mirror) "bloodShareMirror" else "bloodShare"),
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Palette.InkViolet,
            title = { GoldText(label, style = MaterialTheme.typography.titleLarge) },
            text = { Text(sub, color = Palette.Moonstone, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { confirm = false; set(true) }) { Text(stringResource(R.string.nearby_turn_on).uppercase(), color = Palette.Gold) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.nearby_not_now).uppercase(), color = Palette.MoonstoneDim) } },
        )
    }
}
