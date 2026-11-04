package com.metavirtuoso.conjunction.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.nearby.NearbyPermissions
import com.metavirtuoso.conjunction.ai.DailyReminderReceiver
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.match.Age
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.MatchFilter
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.DatingProfile
import com.metavirtuoso.conjunction.data.PlaceResult
import com.metavirtuoso.conjunction.data.PlaceSearchException
import com.metavirtuoso.conjunction.data.Places
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.legal.ConsentKeys
import com.metavirtuoso.conjunction.legal.ConsentRecord
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.legal.LegalTexts
import com.metavirtuoso.conjunction.ui.components.ChoiceChips
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldCheckRow
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnateDivider
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.OrreryLoader
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OnboardingStep { WELCOME, AGE, LEGAL, BIRTH_TIME, BIRTH_PLACE, BLOOD, PROFILE, NEARBY, NOTIFICATIONS }

/** Which onboarding step a partly-onboarded profile should resume at. */
fun resumeStep(p: Profile): OnboardingStep = when {
    p.ageGateBirthDate == null -> OnboardingStep.WELCOME
    !p.legalAccepted -> OnboardingStep.LEGAL
    p.birth == null -> OnboardingStep.BIRTH_TIME
    else -> OnboardingStep.BLOOD
}

/**
 * First run: 18+ age gate → waiver / terms / privacy (versioned acceptance) → birth date and exact
 * time (or "unknown") → birth place (geocoded, time zone resolved offline) → blood type (optional)
 * → dating & friends profile (optional, explicit consent) → Nearby (optional, off by default) →
 * notifications. Strings come from legal/IN_APP_CONSENT_COPY.md.
 */
@Composable
fun OnboardingFlow(container: AppContainer, profile: Profile, onFinished: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(resumeStep(profile)) }
    // Birth time is chosen one page before the place; both are saved together on the place page.
    var birthTime by rememberSaveable { mutableStateOf(profile.birth?.time) }
    var timeUnknown by rememberSaveable { mutableStateOf(profile.birth != null && profile.birth.time == null) }
    val birthDate = profile.birth?.date ?: profile.ageGateBirthDate ?: LocalDate.now().minusYears(25)
    val scope = rememberCoroutineScope()
    val background = when (step) {
        OnboardingStep.WELCOME, OnboardingStep.AGE -> R.drawable.bg_onboarding_1
        OnboardingStep.LEGAL, OnboardingStep.BIRTH_TIME, OnboardingStep.BIRTH_PLACE -> R.drawable.bg_onboarding_2
        else -> R.drawable.bg_onboarding_3
    }
    fun go(next: OnboardingStep) { step = next }
    fun finish() {
        scope.launch {
            container.profileStore.update { it.copy(onboardingComplete = true) }
            onFinished()
        }
    }

    // The welcome page shows the art; form pages dim it further so titles and fields stay legible.
    StarfieldBackground(background = background, dim = if (step == OnboardingStep.WELCOME) 0.55f else 0.74f) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { (fadeIn() + slideInHorizontally { it / 6 }) togetherWith (fadeOut() + slideOutHorizontally { -it / 6 }) },
            label = "onboarding",
        ) { current ->
            when (current) {
                OnboardingStep.WELCOME -> WelcomeStep { go(OnboardingStep.AGE) }
                OnboardingStep.AGE -> AgeGateStep(container, profile) { go(OnboardingStep.LEGAL) }
                OnboardingStep.LEGAL -> LegalStep(container) { go(OnboardingStep.BIRTH_TIME) }
                OnboardingStep.BIRTH_TIME -> BirthTimeStep(birthDate, birthTime, timeUnknown, onTime = { birthTime = it }, onUnknown = { timeUnknown = it }) {
                    go(OnboardingStep.BIRTH_PLACE)
                }
                OnboardingStep.BIRTH_PLACE -> BirthPlaceStep(container, profile, birthDate, if (timeUnknown) null else birthTime, onBack = { go(OnboardingStep.BIRTH_TIME) }) {
                    go(OnboardingStep.BLOOD)
                }
                OnboardingStep.BLOOD -> BloodStep(container, profile) { go(OnboardingStep.PROFILE) }
                OnboardingStep.PROFILE -> DatingProfileStep(container, profile, onSkip = { go(OnboardingStep.NOTIFICATIONS) }) { go(OnboardingStep.NEARBY) }
                OnboardingStep.NEARBY -> NearbyOptInStep(container, profile) { go(OnboardingStep.NOTIFICATIONS) }
                OnboardingStep.NOTIFICATIONS -> NotificationsStep(container) { finish() }
            }
        }
    }
}

/** Common page scaffold: scrolling column, safe-area padding, bottom actions. */
@Composable
internal fun StepPage(
    title: String,
    progress: Float?,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                color = Palette.Gold,
                trackColor = Palette.Dusk,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp).semantics { contentDescription = "Setup progress" },
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GoldText(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, heading = true)
            OrnateDivider()
            content()
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Image(painterResource(R.drawable.splash_mark), contentDescription = null, modifier = Modifier.size(150.dp))
        GoldText(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center, heading = true)
        OrnateDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.headlineSmall, color = Palette.Moonstone, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Your birth chart, Chinese zodiac, blood-type and palm readings — written by the AI on this phone. And, if you choose, the people nearby whose stars align with yours.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.MoonstoneDim, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        GoldButton("Begin", onClick = onNext, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgeGateStep(container: AppContainer, profile: Profile, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dob by rememberSaveable { mutableStateOf(profile.ageGateBirthDate) }
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    StepPage(
        title = stringResource(R.string.age_gate_title),
        progress = 0.1f,
        actions = {
            GoldButton(stringResource(R.string.age_gate_continue), enabled = dob != null, modifier = Modifier.fillMaxWidth(), onClick = {
                val date = dob ?: return@GoldButton
                scope.launch {
                    if (!Age.isAdult(date, today)) {
                        // Under 18: block this install; the date is not kept.
                        container.profileStore.update { it.copy(ageBlocked = true, ageGateBirthDate = null) }
                    } else {
                        container.profileStore.update { it.copy(ageGateBirthDate = date) }
                        onNext()
                    }
                }
            })
        },
    ) {
        Text(stringResource(R.string.age_gate_body), style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
        OrnatePanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.age_gate_dob_label).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
            Text(
                dob?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) ?: "Tap to choose",
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.Moonstone,
                modifier = Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 10.dp),
            )
            GhostButton("Choose date", onClick = { picking = true })
        }
        FinePrint(stringResource(R.string.age_gate_dob_note), Modifier.fillMaxWidth())
    }
    if (picking) {
        val initial = (dob ?: today.minusYears(25)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            yearRange = 1900..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { dob = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK", color = Palette.Gold) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", color = Palette.MoonstoneDim) } },
            colors = DatePickerDefaults.colors(containerColor = Palette.InkViolet),
        ) {
            DatePicker(
                state = state,
                colors = DatePickerDefaults.colors(
                    containerColor = Palette.InkViolet,
                    selectedDayContainerColor = Palette.Gold,
                    selectedDayContentColor = Palette.Midnight,
                    todayDateBorderColor = Palette.GoldPale,
                    selectedYearContainerColor = Palette.Gold,
                    selectedYearContentColor = Palette.Midnight,
                ),
            )
        }
    }
}

/** Blocked install (under 18): no retry with another date. */
@Composable
fun AgeBlockedScreen() {
    StarfieldBackground(background = R.drawable.bg_onboarding_1, dim = 0.6f) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GoldText(stringResource(R.string.age_gate_blocked_title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, heading = true)
            OrnateDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.age_gate_blocked_body), style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LegalStep(container: AppContainer, onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ageChecked by rememberSaveable { mutableStateOf(false) }
    var agreeChecked by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf<LegalDoc?>(null) }
    var saving by remember { mutableStateOf(false) }
    val ageText = stringResource(R.string.legal_checkbox_age)
    val agreeText = stringResource(R.string.legal_checkbox_agree)
    StepPage(
        title = stringResource(R.string.legal_title),
        progress = 0.2f,
        actions = {
            GoldButton(stringResource(R.string.legal_continue), enabled = ageChecked && agreeChecked && !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                saving = true
                scope.launch {
                    val docs = withContext(Dispatchers.IO) { listOf(LegalDoc.WAIVER, LegalDoc.TERMS, LegalDoc.PRIVACY).map { LegalTexts.load(context, it) } }
                    docs.forEach { container.consentLedger.record(ConsentRecord.now(it.doc.key, it.version, it.markdown)) }
                    container.consentLedger.record(ConsentRecord.now(ConsentKeys.AGE_18_CONFIRMED, ConsentKeys.COPY_VERSION, ageText))
                    container.profileStore.update { it.copy(legalAccepted = true) }
                    saving = false
                    onNext()
                }
            })
        },
    ) {
        OrnatePanel(Modifier.fillMaxWidth()) {
            listOf(R.string.legal_summary_1, R.string.legal_summary_2, R.string.legal_summary_3, R.string.legal_summary_6, R.string.legal_summary_4, R.string.legal_summary_5).forEach {
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("✦  ", color = Palette.Gold, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                }
            }
        }
        GhostButton(stringResource(R.string.legal_read_waiver), onClick = { open = LegalDoc.WAIVER }, modifier = Modifier.fillMaxWidth())
        GhostButton(stringResource(R.string.legal_read_terms), onClick = { open = LegalDoc.TERMS }, modifier = Modifier.fillMaxWidth())
        GhostButton(stringResource(R.string.legal_read_privacy), onClick = { open = LegalDoc.PRIVACY }, modifier = Modifier.fillMaxWidth())
        GoldCheckRow(ageChecked, { ageChecked = it }, ageText)
        GoldCheckRow(agreeChecked, { agreeChecked = it }, agreeText)
    }
    open?.let { LegalDocDialog(it, onDismiss = { open = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthTimeStep(
    date: LocalDate,
    time: LocalTime?,
    unknown: Boolean,
    onTime: (LocalTime) -> Unit,
    onUnknown: (Boolean) -> Unit,
    onDate: ((LocalDate) -> Unit)? = null,
    progress: Float? = 0.35f,
    onNext: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    StepPage(
        title = "The moment you arrived",
        progress = progress,
        actions = {
            GoldButton("Continue", enabled = unknown || time != null, modifier = Modifier.fillMaxWidth(), onClick = onNext)
        },
    ) {
        Text("Your birth chart needs the exact hour and minute you were born — the Ascendant moves a whole sign every two hours.", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
        OrnatePanel(Modifier.fillMaxWidth()) {
            Text("BIRTH DATE", style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
            Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)), style = MaterialTheme.typography.headlineSmall, color = Palette.Moonstone)
            if (onDate != null) GhostButton("Change date", onClick = { pickingDate = true })
            Spacer(Modifier.height(12.dp))
            Text("BIRTH TIME (LOCAL CLOCK)", style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
            Text(
                when {
                    unknown -> "Unknown"
                    time != null -> time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                    else -> "Tap to set"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.Moonstone,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !unknown) { picking = true }.padding(vertical = 8.dp),
            )
            GhostButton("Set time", enabled = !unknown, onClick = { picking = true })
            GoldCheckRow(unknown, onUnknown, "I don't know my birth time", note = "Your chart will skip the Ascendant, houses and the BaZi hour pillar. You can add the time later in Profile.")
        }
        FinePrint(stringResource(R.string.birth_privacy_note), Modifier.fillMaxWidth())
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 12, initialMinute = time?.minute ?: 0, is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Palette.InkViolet,
            confirmButton = { TextButton(onClick = { onTime(LocalTime.of(state.hour, state.minute)); picking = false }) { Text("OK", color = Palette.Gold) } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", color = Palette.MoonstoneDim) } },
            text = {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Palette.Midnight,
                        selectorColor = Palette.Gold,
                        timeSelectorSelectedContainerColor = Palette.Gold,
                        timeSelectorSelectedContentColor = Palette.Midnight,
                        timeSelectorUnselectedContainerColor = Palette.Dusk,
                        timeSelectorUnselectedContentColor = Palette.Moonstone,
                        periodSelectorSelectedContainerColor = Palette.Gold,
                        periodSelectorSelectedContentColor = Palette.Midnight,
                    ),
                )
            },
        )
    }
    if (pickingDate && onDate != null) {
        BirthDatePickerDialog(date, onPicked = { onDate(it); pickingDate = false }, onDismiss = { pickingDate = false })
    }
}

/** Date picker limited to adult birth dates (18+), 1900 onwards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDatePickerDialog(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val latest = Age.latestAdultBirthDate(LocalDate.now())
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        yearRange = 1900..latest.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(latest)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) { Text("OK", color = Palette.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.MoonstoneDim) } },
        colors = DatePickerDefaults.colors(containerColor = Palette.InkViolet),
    ) {
        DatePicker(state = state, colors = DatePickerDefaults.colors(containerColor = Palette.InkViolet, selectedDayContainerColor = Palette.Gold, selectedDayContentColor = Palette.Midnight, selectedYearContainerColor = Palette.Gold, selectedYearContentColor = Palette.Midnight))
    }
}

@Composable
internal fun BirthPlaceStep(
    container: AppContainer,
    profile: Profile,
    date: LocalDate,
    time: LocalTime?,
    progress: Float? = 0.5f,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(profile.birth?.placeName.orEmpty()) }
    var results by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var chosen by remember {
        mutableStateOf(profile.birth?.takeIf { it.placeName.isNotEmpty() }?.let { PlaceResult(it.placeName, it.latitude, it.longitude, it.zoneId, "") })
    }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var latText by rememberSaveable { mutableStateOf("") }
    var lonText by rememberSaveable { mutableStateOf("") }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    fun search() {
        // Close the keyboard so the results below the field are visible.
        keyboard?.hide(); focus.clearFocus()
        searching = true; error = null
        scope.launch {
            try {
                val (grid, table) = withContext(Dispatchers.IO) { container.timeZonesOrNull to container.zoneTable }
                results = Places.search(context, query, grid, table, date, time)
                if (results.isEmpty()) error = "No places found. Try adding the country."
            } catch (e: PlaceSearchException) {
                error = e.message
            } catch (e: Exception) {
                android.util.Log.w("Conjunction", "Place search failed", e)
                error = "Place search failed. Check your connection or enter coordinates."
            } finally {
                searching = false
            }
        }
    }

    StepPage(
        title = "Where you were born",
        progress = progress,
        actions = {
            GoldButton("Continue", enabled = chosen != null, modifier = Modifier.fillMaxWidth(), onClick = {
                val place = chosen ?: return@GoldButton
                scope.launch {
                    container.profileStore.update {
                        it.copy(birth = BirthInput(date, time, place.latitude, place.longitude, place.zoneId, place.name))
                    }
                    onNext()
                }
            })
            GhostButton("Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Text("We look up the place on this phone and work out the time zone and daylight-saving rules in force on your birthday.", style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
        OrnateTextField(
            query, { query = it }, label = "City or town of birth",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !searching) search() }),
        )
        GoldButton(if (searching) "Searching…" else "Search", enabled = query.isNotBlank() && !searching, onClick = ::search, modifier = Modifier.fillMaxWidth())
        if (searching) OrreryLoader(size = 48.dp)
        error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        results.forEach { place ->
            val selected = chosen?.name == place.name && chosen?.latitude == place.latitude
            OrnatePanel(Modifier.fillMaxWidth().clickable { chosen = place }, contentPadding = 16.dp) {
                Text((if (selected) "✦ " else "") + place.name, style = MaterialTheme.typography.titleMedium, color = if (selected) Palette.GoldPale else Palette.Moonstone)
                Text("${place.zoneId} · ${place.offsetLabel}", style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim)
            }
        }
        chosen?.let { place ->
            if (results.none { it.name == place.name }) {
                OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                    Text("✦ ${place.name}", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
                    Text(place.zoneId, style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim)
                }
            }
        }
        GhostButton(if (manual) "Hide coordinates" else "Enter coordinates instead", onClick = { manual = !manual }, modifier = Modifier.fillMaxWidth())
        if (manual) {
            OrnateTextField(latText, { latText = it }, label = "Latitude (−90 to 90, north positive)", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OrnateTextField(lonText, { lonText = it }, label = "Longitude (−180 to 180, east positive)", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            GhostButton("Use these coordinates", modifier = Modifier.fillMaxWidth(), onClick = {
                val lat = latText.replace(',', '.').toDoubleOrNull()
                val lon = lonText.replace(',', '.').toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    error = "Enter a latitude between −90 and 90 and a longitude between −180 and 180."
                } else scope.launch {
                    val (grid, table) = withContext(Dispatchers.IO) { container.timeZonesOrNull to container.zoneTable }
                    val label = "%.4f, %.4f".format(java.util.Locale.ROOT, lat, lon)
                    chosen = Places.resolve(query.ifBlank { label }, lat, lon, grid, table, date, time)
                    error = null
                }
            })
        }
    }
}

@Composable
private fun BloodStep(container: AppContainer, profile: Profile, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(profile.bloodType) }
    var rh by rememberSaveable { mutableStateOf(profile.rh) }
    var forMatching by rememberSaveable { mutableStateOf(profile.bloodForMatching) }
    val consentText = stringResource(R.string.blood_type_consent_checkbox)
    StepPage(
        title = stringResource(R.string.blood_type_title),
        progress = 0.62f,
        actions = {
            GoldButton(if (type == null) "Skip" else "Continue", modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    val consent = forMatching && type != null
                    container.profileStore.update { it.copy(bloodType = type, rh = rh, bloodForMatching = consent) }
                    if (consent && !profile.bloodForMatching) {
                        container.consentLedger.record(ConsentRecord.now(ConsentKeys.BLOOD_TYPE_MATCHING, ConsentKeys.COPY_VERSION, consentText))
                    }
                    onNext()
                }
            })
        },
    ) {
        Text(stringResource(R.string.blood_type_body), style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
        ChoiceChips(BloodType.entries.map { it to "Type ${it.displayName}" }, type, { type = it })
        if (type != null) {
            ChoiceChips(listOf<Pair<RhFactor?, String>>(RhFactor.POSITIVE to "Rh+", RhFactor.NEGATIVE to "Rh−", null to "Not sure"), rh, { rh = it })
            OrnatePanel(Modifier.fillMaxWidth()) {
                GoldCheckRow(forMatching, { forMatching = it }, consentText, note = stringResource(R.string.blood_type_consent_note))
            }
        }
    }
}

@Composable
private fun DatingProfileStep(container: AppContainer, profile: Profile, onSkip: () -> Unit, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(profile.displayName) }
    var gender by rememberSaveable { mutableStateOf(profile.dating?.gender) }
    var seeking by rememberSaveable { mutableStateOf(profile.dating?.seeking) }
    var status by rememberSaveable { mutableStateOf(profile.dating?.relationshipStatus) }
    var lookingFor by rememberSaveable { mutableStateOf(profile.dating?.lookingFor) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var honest by rememberSaveable { mutableStateOf(false) }
    val consentText = stringResource(R.string.orientation_consent_checkbox)
    val honestyText = stringResource(R.string.honesty_checkbox)
    val complete = name.isNotBlank() && gender != null && seeking != null && status != null && lookingFor != null && consent && honest
    StepPage(
        title = stringResource(R.string.dating_profile_title),
        progress = 0.75f,
        actions = {
            GoldButton("Continue", enabled = complete, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    container.consentLedger.record(ConsentRecord.now(ConsentKeys.ORIENTATION_DATA, ConsentKeys.COPY_VERSION, consentText))
                    container.consentLedger.record(ConsentRecord.now(ConsentKeys.PROFILE_HONESTY, ConsentKeys.COPY_VERSION, honestyText))
                    container.profileStore.update {
                        it.copy(
                            displayName = name.trim(),
                            dating = DatingProfile(gender!!, seeking!!, status!!, lookingFor!!),
                            orientationConsent = true,
                            honestyConfirmed = true,
                        )
                    }
                    onNext()
                }
            })
            GhostButton("Skip — charts and readings only", onClick = {
                scope.launch {
                    if (name.isNotBlank()) container.profileStore.update { it.copy(displayName = name.trim()) }
                    onSkip()
                }
            }, modifier = Modifier.fillMaxWidth())
        },
    ) {
        OrnateTextField(name, { name = it.take(40) }, label = "First name (shown on match cards)")
        DatingFields(gender, { gender = it }, seeking, { seeking = it }, status, { s ->
            status = s
            if (lookingFor == null || s == RelationshipStatus.IN_A_RELATIONSHIP) lookingFor = MatchFilter.defaultLookingFor(s)
        }, lookingFor, { lookingFor = it })
        OrnatePanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.orientation_consent_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
            GoldCheckRow(consent, { consent = it }, consentText, note = stringResource(R.string.orientation_consent_withdraw_note))
            GoldCheckRow(honest, { honest = it }, honestyText)
        }
    }
}

/** Gender / seeking / relationship status / looking-for pickers (also used in Profile). */
@Composable
fun DatingFields(
    gender: Gender?, onGender: (Gender) -> Unit,
    seeking: Seeking?, onSeeking: (Seeking) -> Unit,
    status: RelationshipStatus?, onStatus: (RelationshipStatus) -> Unit,
    lookingFor: LookingFor?, onLookingFor: (LookingFor) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dating_profile_i_am).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
        ChoiceChips(listOf(Gender.MALE to stringResource(R.string.option_male), Gender.FEMALE to stringResource(R.string.option_female)), gender, onGender)
        Text(stringResource(R.string.dating_profile_seeking).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
        ChoiceChips(listOf(Seeking.MALE to stringResource(R.string.option_male), Seeking.FEMALE to stringResource(R.string.option_female), Seeking.BOTH to stringResource(R.string.option_both)), seeking, onSeeking)
        Text(stringResource(R.string.dating_profile_status).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
        ChoiceChips(listOf(RelationshipStatus.SINGLE to stringResource(R.string.option_single), RelationshipStatus.IN_A_RELATIONSHIP to stringResource(R.string.option_in_relationship)), status, onStatus)
        if (status == RelationshipStatus.IN_A_RELATIONSHIP) FinePrint(stringResource(R.string.dating_profile_in_relationship_note))
        Text(stringResource(R.string.dating_profile_looking_for).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
        ChoiceChips(listOf(LookingFor.DATING to stringResource(R.string.option_dating), LookingFor.FRIENDS to stringResource(R.string.option_friends), LookingFor.BOTH to stringResource(R.string.option_dating_and_friends)), lookingFor, onLookingFor)
    }
}

@Composable
private fun NearbyOptInStep(container: AppContainer, profile: Profile, onNext: () -> Unit) {
    if (!container.nearby.supported) {
        StepPage(stringResource(R.string.nearby_title), 0.88f, actions = { GoldButton("Continue", onClick = onNext, modifier = Modifier.fillMaxWidth()) }) {
            Text(stringResource(R.string.nearby_android_old_version), style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale, textAlign = TextAlign.Center)
        }
        return
    }
    NearbyConsentContent(container, profile, progress = 0.88f, onDone = onNext)
}

/**
 * Nearby prominent disclosure (legal/IN_APP_CONSENT_COPY.md section 5) + consent + Bluetooth
 * ("Nearby devices") rationale + permission + enable. No location permission is ever requested.
 * Reused from the Nearby tab.
 */
@Composable
fun NearbyConsentContent(container: AppContainer, profile: Profile, progress: Float?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var understood by rememberSaveable { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var safety by remember { mutableStateOf(false) }
    val checkboxText = stringResource(R.string.nearby_checkbox)
    val disclosure = listOf(
        R.string.nearby_body, R.string.nearby_point_bluetooth, R.string.nearby_point_hello, R.string.nearby_point_card,
        R.string.nearby_point_keep, R.string.nearby_point_swipe, R.string.nearby_point_safety, R.string.nearby_point_control,
    ).map { stringResource(it) }
    val bluetoothDenied = stringResource(R.string.perm_bluetooth_denied)

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // POST_NOTIFICATIONS may be declined; Nearby still works (matches then show only in the app).
        val ok = NearbyPermissions.BLUETOOTH.all { granted[it] == true || ContextCompat.checkSelfPermission(container.context, it) == PackageManager.PERMISSION_GRANTED }
        if (!ok) {
            error = bluetoothDenied
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            try {
                // The consent record hashes the whole disclosure the person saw, plus the checkbox.
                container.consentLedger.record(ConsentRecord.now(ConsentKeys.NEARBY_BLUETOOTH, ConsentKeys.COPY_VERSION, (disclosure + checkboxText).joinToString("\n")))
                container.profileStore.update { it.copy(nearbyConsent = true) }
                container.nearby.enable()
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Nearby could not be turned on."
            } finally {
                busy = false
            }
        }
    }

    StepPage(
        title = stringResource(R.string.nearby_title),
        progress = progress,
        actions = {
            GoldButton(if (busy) "Turning on…" else stringResource(R.string.nearby_turn_on), enabled = understood && !busy && profile.nearbyEligible, modifier = Modifier.fillMaxWidth(), onClick = { showRationale = true })
            GhostButton(stringResource(R.string.nearby_not_now), onClick = onDone, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Image(painterResource(R.drawable.popup_match_nearby), contentDescription = null, modifier = Modifier.size(180.dp))
        Text(disclosure.first(), style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
        OrnatePanel(Modifier.fillMaxWidth()) {
            disclosure.drop(1).forEach {
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("✦  ", color = Palette.Gold, style = MaterialTheme.typography.bodyMedium)
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                }
            }
        }
        GhostButton(stringResource(R.string.nearby_read_safety), onClick = { safety = true }, modifier = Modifier.fillMaxWidth())
        GoldCheckRow(understood, { understood = it }, checkboxText)
        if (!profile.nearbyEligible) FinePrint("Complete your birth details and your dating & friends profile first (Profile → Who you'd like to meet).")
        error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
    }
    if (showRationale) {
        PermissionRationaleDialog(
            title = stringResource(R.string.perm_bluetooth_title),
            body = stringResource(R.string.perm_bluetooth_body),
            button = stringResource(R.string.perm_bluetooth_button),
            art = R.drawable.popup_match_nearby,
            extra = stringResource(R.string.perm_bluetooth_android_note),
            onConfirm = {
                showRationale = false
                val wanted = NearbyPermissions.BLUETOOTH.toList() +
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
                permissions.launch(wanted.toTypedArray())
            },
            onDismiss = { showRationale = false },
        )
    }
    if (safety) {
        LegalDocDialog(LegalDoc.SAFETY, onDismiss = { safety = false }, onAcknowledge = { text ->
            scope.launch {
                container.consentLedger.record(ConsentRecord.now(LegalDoc.SAFETY.key, text.version, text.markdown))
                container.profileStore.update { it.copy(safetyTipsSeen = true) }
            }
            safety = false
        })
    }
}

/** In-app rationale shown before a system permission prompt (consent copy section 8). */
@Composable
fun PermissionRationaleDialog(title: String, body: String, button: String, @DrawableRes art: Int?, onConfirm: () -> Unit, onDismiss: () -> Unit, extra: String? = null) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkViolet,
        title = { GoldText(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                art?.let { Image(painterResource(it), contentDescription = null, modifier = Modifier.fillMaxWidth().height(140.dp)) }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                extra?.let { FinePrint(it) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(button.uppercase(), color = Palette.Gold, style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("NOT NOW", color = Palette.MoonstoneDim, style = MaterialTheme.typography.labelLarge) } },
    )
}

@Composable
private fun NotificationsStep(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            container.profileStore.update { it.copy(notificationsPrompted = true) }
            if (granted) DailyReminderReceiver.setEnabled(context, true)
            onDone()
        }
    }
    StepPage(
        title = stringResource(R.string.perm_notifications_title),
        progress = 1f,
        actions = {
            GoldButton(stringResource(R.string.perm_notifications_button), modifier = Modifier.fillMaxWidth(), onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else {
                    DailyReminderReceiver.setEnabled(context, true)
                    scope.launch { container.profileStore.update { it.copy(notificationsPrompted = true) }; onDone() }
                }
            })
            GhostButton(stringResource(R.string.nearby_not_now), onClick = onDone, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Image(painterResource(R.drawable.popup_friend_connected), contentDescription = null, modifier = Modifier.size(200.dp))
        Text(stringResource(R.string.perm_notifications_body), style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center)
        FinePrint(stringResource(R.string.perm_notifications_driving))
    }
}

/** Profile → Edit birth details: date, time (or unknown) and place, saved together. */
@Composable
fun BirthEditor(container: AppContainer, profile: Profile, onDone: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(0) }
    var date by rememberSaveable { mutableStateOf(profile.birth?.date ?: profile.ageGateBirthDate ?: LocalDate.now().minusYears(25)) }
    var time by rememberSaveable { mutableStateOf(profile.birth?.time) }
    var unknown by rememberSaveable { mutableStateOf(profile.birth != null && profile.birth.time == null) }
    androidx.activity.compose.BackHandler { if (page == 1) page = 0 else onDone() }
    StarfieldBackground(background = R.drawable.bg_onboarding_2, dim = 0.6f) {
        if (page == 0) {
            BirthTimeStep(date, time, unknown, onTime = { time = it }, onUnknown = { unknown = it }, onDate = { date = it }, progress = null) { page = 1 }
        } else {
            BirthPlaceStep(container, profile, date, if (unknown) null else time, progress = null, onBack = { page = 0 }, onNext = onDone)
        }
    }
}
