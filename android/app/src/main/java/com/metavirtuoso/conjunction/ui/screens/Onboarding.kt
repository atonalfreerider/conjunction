package com.metavirtuoso.conjunction.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.OnboardingAssistant
import com.metavirtuoso.conjunction.core.match.Age
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.MatchFilter
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.core.onboarding.AboutYou
import com.metavirtuoso.conjunction.core.onboarding.AboutYouParser
import com.metavirtuoso.conjunction.core.onboarding.BirthExtraction
import com.metavirtuoso.conjunction.core.onboarding.BirthTextParser
import com.metavirtuoso.conjunction.core.onboarding.BirthTextResult
import com.metavirtuoso.conjunction.core.onboarding.OnboardingExtraction
import com.metavirtuoso.conjunction.core.profile.InstagramHandle
import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Instagram
import com.metavirtuoso.conjunction.data.PlaceResult
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.legal.ConsentKeys
import com.metavirtuoso.conjunction.legal.ConsentRecord
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.legal.LegalTexts
import com.metavirtuoso.conjunction.legal.LegalVersions
import com.metavirtuoso.conjunction.nearby.NearbyRepository
import com.metavirtuoso.conjunction.palm.PalmScanner
import com.metavirtuoso.conjunction.ui.components.ChoiceChips
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.LinkedText
import com.metavirtuoso.conjunction.ui.components.OrnateDivider
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.ProfilePhoto
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.components.formatLinked
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UX 2.0 onboarding (SPEC 5d.5): four screens. */
enum class OnboardingStep { WELCOME, BIRTH, ABOUT, CARD }

/**
 * Where a partly onboarded profile resumes (SPEC 5d.1.4), or null = onboarding is over. Old 2.0
 * steps map by what was saved: nothing → Welcome; an age-gate date but no birth data → Birth (the
 * date pre-filled); birth saved → About you; the dating profile also saved (the old flow was at
 * Nearby / Notifications) → finished.
 */
fun resumeStep(p: Profile): OnboardingStep? {
    if (p.onboardingComplete) return null
    p.onboardingStep?.let { saved -> OnboardingStep.entries.firstOrNull { it.name == saved }?.let { return it } }
    return when {
        p.birth == null && p.ageGateBirthDate == null -> OnboardingStep.WELCOME
        p.birth == null -> OnboardingStep.BIRTH
        p.dating != null -> null
        else -> OnboardingStep.ABOUT
    }
}

/** Test seams: the place lookup and the model helper (real ones by default). */
class OnboardingDeps(val places: PlaceLookup, val assistant: OnboardingAssistant)

@Composable
fun OnboardingFlow(container: AppContainer, profile: Profile, onFinished: () -> Unit, deps: OnboardingDeps? = null) {
    val scope = rememberCoroutineScope()
    val d = deps ?: remember(container) { OnboardingDeps(defaultPlaceLookup(container), OnboardingAssistant(container.textModel)) }
    val initial = remember { resumeStep(profile) }
    var step by rememberSaveable { mutableStateOf(initial ?: OnboardingStep.CARD) }
    fun finish() {
        scope.launch {
            container.profileStore.update { it.copy(onboardingComplete = true, onboardingStep = null, landOnChart = true) }
            onFinished()
        }
    }
    LaunchedEffect(Unit) { if (initial == null) container.profileStore.update { it.copy(onboardingComplete = true, onboardingStep = null) } }
    fun go(next: OnboardingStep) {
        step = next
        scope.launch { container.profileStore.update { it.copy(onboardingStep = next.name) } }
    }
    val background = when (step) {
        OnboardingStep.WELCOME -> R.drawable.bg_onboarding_1
        OnboardingStep.BIRTH -> R.drawable.bg_onboarding_2
        else -> R.drawable.bg_onboarding_3
    }
    StarfieldBackground(background = background, dim = if (step == OnboardingStep.WELCOME) 0.55f else 0.84f) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { (fadeIn() + slideInHorizontally { it / 6 }) togetherWith (fadeOut() + slideOutHorizontally { -it / 6 }) },
            label = "onboarding",
        ) { current ->
            when (current) {
                OnboardingStep.WELCOME -> WelcomeStep { go(OnboardingStep.BIRTH) }
                OnboardingStep.BIRTH -> BirthStep(container, profile, d) { go(OnboardingStep.ABOUT) }
                OnboardingStep.ABOUT -> AboutYouStep(container, profile, d.assistant, onSkip = { go(OnboardingStep.CARD) }) { go(OnboardingStep.CARD) }
                OnboardingStep.CARD -> CardStep(container, profile, d.assistant, onSkip = ::finish, onDone = ::finish)
            }
        }
    }
}

/** Page scaffold: 4-dot progress, optional Skip, scrolling content, fixed bottom actions. */
@Composable
internal fun StepPage(
    title: String,
    stepIndex: Int?,
    actions: @Composable ColumnScope.() -> Unit,
    onSkip: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp)) {
            if (stepIndex != null) {
                Row(Modifier.align(Alignment.Center).semantics { contentDescription = "Step ${stepIndex + 1} of 4" }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { i ->
                        Box(Modifier.size(if (i == stepIndex) 10.dp else 8.dp).clip(CircleShape).background(if (i <= stepIndex) Palette.Gold else Palette.Twilight))
                    }
                }
            }
            if (onSkip != null) {
                TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text(stringResource(R.string.onb_skip).uppercase(), color = Palette.GoldPale, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Column(
            Modifier.weight(1f)
                // Fade the illustrated background behind the form so labels and fine print stay legible.
                .background(Brush.verticalGradient(listOf(Palette.Midnight.copy(alpha = 0.25f), Palette.Midnight.copy(alpha = 0.6f))))
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GoldText(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, heading = true)
            OrnateDivider()
            content()
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = actions)
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
        Spacer(Modifier.height(36.dp))
        GoldButton(stringResource(R.string.onb_welcome_start), onClick = onNext, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
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

/** The L1 clickwrap texts (SPEC 5d.1.1): the formatted sentence is what the consent hash covers. */
class ClickwrapCopy(val template: String, val privacyTemplate: String, val terms: String, val guidelines: String, val waiver: String, val privacy: String) {
    val checkboxText: String get() = formatLinked(template, listOf(terms, guidelines, waiver))
    val privacyText: String get() = formatLinked(privacyTemplate, listOf(privacy))
}

@Composable
fun rememberClickwrapCopy(): ClickwrapCopy = ClickwrapCopy(
    stringResource(R.string.legal_accept_checkbox), stringResource(R.string.legal_privacy_line),
    stringResource(R.string.legal_link_terms), stringResource(R.string.legal_link_guidelines),
    stringResource(R.string.legal_link_waiver), stringResource(R.string.legal_link_privacy),
)

/** The L1 checkbox line: unticked box + the sentence with its three document links. */
@Composable
fun ClickwrapRow(checked: Boolean, onChange: (Boolean) -> Unit, copy: ClickwrapCopy, onOpen: (LegalDoc) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).testTag("clickwrap"),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Palette.Gold, uncheckedColor = Palette.GoldPale, checkmarkColor = Palette.Midnight),
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.width(8.dp))
        LinkedText(
            copy.template,
            listOf(copy.terms to { onOpen(LegalDoc.TERMS) }, copy.guidelines to { onOpen(LegalDoc.COMMUNITY) }, copy.waiver to { onOpen(LegalDoc.WAIVER) }),
            Modifier.weight(1f),
        )
    }
}

/**
 * Records the L1 acceptance (SPEC 5d.1.1 / L-3): WAIVER, TERMS and COMMUNITY_GUIDELINES hash the
 * formatted checkbox sentence and carry the bundled document's own SHA-256. The Privacy Policy is
 * only read: PRIVACY_NOTICE_SEEN hashes the formatted privacy line.
 */
suspend fun recordClickwrap(container: AppContainer, checkboxText: String, privacyText: String) {
    val texts = withContext(Dispatchers.IO) {
        LegalDoc.entries.mapNotNull { doc -> runCatching { doc to LegalTexts.load(container.context, doc) }.getOrNull() }.toMap()
    }
    listOf(LegalDoc.WAIVER, LegalDoc.TERMS, LegalDoc.COMMUNITY).forEach { doc ->
        texts[doc]?.let { container.consentLedger.record(ConsentRecord.now(doc.key, it.version, checkboxText, docSha256 = it.sha256)) }
    }
    texts[LegalDoc.PRIVACY]?.let {
        container.consentLedger.record(ConsentRecord.now(ConsentKeys.noticeSeen(LegalDoc.PRIVACY), it.version, privacyText, docSha256 = it.sha256))
    }
    container.consentLedger.record(ConsentRecord.now(ConsentKeys.AGE_18_CONFIRMED, ConsentKeys.COPY_VERSION, checkboxText))
}

/** Screen 2: free-text birth entry → confirm chips (parser first, model fills gaps) → L1 → Begin. */
@Composable
private fun BirthStep(container: AppContainer, profile: Profile, deps: OnboardingDeps, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val today = remember { LocalDate.now() }
    val region = remember { Locale.getDefault().country }
    var text by rememberSaveable { mutableStateOf("") }
    var parsed by remember { mutableStateOf(BirthTextResult(null, null, false, null, false)) }
    var model by remember { mutableStateOf<BirthExtraction?>(null) }
    var modelJob by remember { mutableStateOf<Job?>(null) }
    // Values the person picked with a picker win over anything parsed or suggested.
    var userDate by rememberSaveable { mutableStateOf(profile.birth?.date ?: profile.ageGateBirthDate) }
    var userTime by rememberSaveable { mutableStateOf(profile.birth?.time) }
    var userTimeSet by rememberSaveable { mutableStateOf(profile.birth != null) }
    var userTimeUnknown by rememberSaveable { mutableStateOf(profile.birth != null && profile.birth.time == null) }
    var place by remember { mutableStateOf(profile.birth?.let { PlaceResult(it.placeName, it.latitude, it.longitude, it.zoneId, "") }) }
    var placeFromUser by remember { mutableStateOf(profile.birth != null) }
    var candidates by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var searchingPlace by remember { mutableStateOf(false) }
    var separately by rememberSaveable { mutableStateOf(false) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var openDoc by remember { mutableStateOf<LegalDoc?>(null) }
    var saving by remember { mutableStateOf(false) }
    val copy = rememberClickwrapCopy()
    val alreadyAccepted by produceState(false) {
        val records = container.consentLedger.all()
        value = LegalVersions.clickwrapAlreadyAccepted(records, withContext(Dispatchers.IO) { LegalTexts.bundledVersions(container.context) })
    }

    val merged = OnboardingExtraction.fillEmpty(parsed, model)
    val date = userDate ?: merged.date?.takeIf { BirthTextParser.isAcceptableDate(it, today) }
    val timeUnknown = if (userTimeSet) userTimeUnknown else merged.timeUnknown
    val time = if (userTimeSet) userTime else merged.time
    val placeQuery = merged.placeQuery

    // Deterministic parse 400 ms after typing stops.
    LaunchedEffect(text) {
        delay(400)
        parsed = BirthTextParser.parse(text, region, today)
        if (text.isBlank()) model = null
    }
    // Resolve the place query with the geocoder (top result in the chip, up to 5 under "Change").
    LaunchedEffect(placeQuery, date) {
        val q = placeQuery ?: return@LaunchedEffect
        if (placeFromUser) return@LaunchedEffect
        delay(300)
        searchingPlace = true
        val found = runCatching { deps.places.search(q, date ?: LocalDate.of(1995, 1, 1), time) }.getOrDefault(emptyList())
        searchingPlace = false
        candidates = found.take(5)
        if (!placeFromUser) place = found.firstOrNull()
    }
    fun askModel() {
        if (text.isBlank() || (date != null && place != null)) return
        modelJob?.cancel()
        val asked = text
        modelJob = scope.launch {
            val answer = deps.assistant.birth(asked, today)
            if (asked == text && answer != null) model = answer
        }
    }

    StepPage(
        title = stringResource(R.string.onb_birth_title),
        stepIndex = 1,
        actions = {
            if (!alreadyAccepted) {
                LinkedText(copy.privacyTemplate, listOf(copy.privacy to { openDoc = LegalDoc.PRIVACY }), Modifier.fillMaxWidth().padding(start = 4.dp))
                ClickwrapRow(accepted, { accepted = it }, copy) { openDoc = it }
            }
            GoldButton(
                stringResource(R.string.legal_begin),
                enabled = (accepted || alreadyAccepted) && date != null && place != null && !saving,
                modifier = Modifier.fillMaxWidth().testTag("begin"),
                onClick = {
                    val d = date ?: return@GoldButton
                    val p = place ?: return@GoldButton
                    saving = true
                    modelJob?.cancel()
                    scope.launch {
                        if (!Age.isAdult(d, today)) {
                            // Under 18: block this install; the date is not kept.
                            container.profileStore.update { it.copy(ageBlocked = true, ageGateBirthDate = null, birth = null) }
                            return@launch
                        }
                        if (!alreadyAccepted) recordClickwrap(container, copy.checkboxText, copy.privacyText)
                        container.profileStore.update {
                            it.copy(
                                ageGateBirthDate = d, legalAccepted = true,
                                birth = BirthInput(d, if (timeUnknown) null else time, p.latitude, p.longitude, p.zoneId, p.name),
                            )
                        }
                        saving = false
                        onNext()
                    }
                },
            )
        },
    ) {
        if (!separately) {
            OrnateTextField(
                text, { text = it.take(200) }, label = null, placeholder = stringResource(R.string.onb_birth_hint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); askModel() }),
                modifier = Modifier.testTag("birthText").onFocusChanged { if (!it.isFocused) askModel() },
            )
        }
        BirthChips(
            date = date, time = time, timeUnknown = timeUnknown && time == null, place = place, placeQuery = placeQuery, placeCandidates = candidates,
            onDate = { userDate = it },
            onTime = { t, unknown -> userTimeSet = true; userTime = t; userTimeUnknown = unknown },
            onPlace = { place = it; placeFromUser = true },
            lookup = deps.places, container = container,
            ambiguousDate = parsed.ambiguousDate && userDate == null,
            working = modelJob?.isActive == true,
            searchingPlace = searchingPlace,
        )
        Text(
            stringResource(R.string.onb_birth_separately),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.GoldPale,
            modifier = Modifier.clip(CutCornerShape(8.dp)).background(Palette.Midnight.copy(alpha = 0.75f))
                .clickable(role = Role.Button) { separately = !separately }.padding(vertical = 12.dp, horizontal = 14.dp),
        )
    }
    openDoc?.let { LegalDocDialog(it, onDismiss = { openDoc = null }) }
}

/** Screen 3: one sentence about you → editable chips (parser first, model fills gaps). Optional. */
@Composable
private fun AboutYouStep(container: AppContainer, profile: Profile, assistant: OnboardingAssistant, onSkip: () -> Unit, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(profile.displayName) }
    var gender by rememberSaveable { mutableStateOf(profile.gender) }
    var seeking by rememberSaveable { mutableStateOf(profile.seeking) }
    var status by rememberSaveable { mutableStateOf(profile.relationshipStatus) }
    var lookingFor by rememberSaveable { mutableStateOf(profile.lookingFor) }
    var interests by rememberSaveable { mutableStateOf(profile.interests) }
    // Chips the person set by hand are never changed by the parser or the model.
    val edited = rememberSaveable { mutableStateOf(setOf<String>()) }
    fun touch(field: String) { edited.value = edited.value + field }
    fun apply(found: AboutYou, onlyEmpty: Boolean) {
        val e = edited.value
        if ("name" !in e && found.firstName != null && (!onlyEmpty || name.isBlank())) name = found.firstName!!
        if ("gender" !in e && found.gender != null && (!onlyEmpty || gender == null)) gender = found.gender
        if ("seeking" !in e && found.seeking != null && (!onlyEmpty || seeking == null)) seeking = found.seeking
        if ("status" !in e && found.relationship != null && (!onlyEmpty || status == null)) {
            status = found.relationship
            if (found.relationship == RelationshipStatus.IN_A_RELATIONSHIP && "lookingFor" !in e) lookingFor = LookingFor.FRIENDS
        }
        if ("lookingFor" !in e && found.lookingFor != null && (!onlyEmpty || lookingFor == null) && status != RelationshipStatus.IN_A_RELATIONSHIP) lookingFor = found.lookingFor
        if ("interests" !in e && found.interests != null && (!onlyEmpty || interests.isBlank())) interests = found.interests!!
    }
    LaunchedEffect(text) {
        if (text.isBlank()) return@LaunchedEffect
        delay(400)
        apply(AboutYouParser.parse(text), onlyEmpty = false)
        // Then the on-device model, only for what is still empty (cancelled when the text changes).
        assistant.aboutYou(text)?.let { apply(it, onlyEmpty = true) }
    }
    StepPage(
        title = stringResource(R.string.onb_about_title),
        stepIndex = 2,
        onSkip = onSkip,
        actions = {
            GoldButton(stringResource(R.string.onb_continue), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    container.profileStore.update {
                        it.copy(
                            displayName = name.trim().take(40).ifEmpty { it.displayName },
                            gender = gender, seeking = seeking, relationshipStatus = status, lookingFor = lookingFor,
                            interests = OnboardingExtraction.cleanInterests(interests).orEmpty(),
                        )
                    }
                    onNext()
                }
            })
        },
    ) {
        OrnateTextField(text, { text = it.take(500) }, label = null, placeholder = stringResource(R.string.onb_about_hint), singleLine = false, minLines = 2, modifier = Modifier.testTag("aboutText"))
        OrnateTextField(name, { name = it.take(40); touch("name") }, label = stringResource(R.string.onb_first_name))
        DatingFields(
            gender, { gender = it; touch("gender") },
            seeking, { seeking = it; touch("seeking") },
            status, { s ->
                status = s; touch("status")
                if ("lookingFor" !in edited.value || s == RelationshipStatus.IN_A_RELATIONSHIP) lookingFor = MatchFilter.defaultLookingFor(s)
            },
            lookingFor, { lookingFor = it; touch("lookingFor") },
        )
        OrnateTextField(interests, { interests = it.take(200); touch("interests") }, label = stringResource(R.string.onb_interests))
    }
}

/** Gender / seeking / relationship status / looking-for chips (onboarding, the Nearby sheet, Profile). */
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
        ChoiceChips(listOf(Seeking.MALE to stringResource(R.string.option_seeking_men), Seeking.FEMALE to stringResource(R.string.option_seeking_women), Seeking.BOTH to stringResource(R.string.option_both)), seeking, onSeeking)
        Text(stringResource(R.string.dating_profile_status).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
        ChoiceChips(listOf(RelationshipStatus.SINGLE to stringResource(R.string.option_single), RelationshipStatus.IN_A_RELATIONSHIP to stringResource(R.string.option_in_relationship)), status, onStatus)
        if (status == RelationshipStatus.IN_A_RELATIONSHIP) FinePrint(stringResource(R.string.dating_profile_in_relationship_note))
        Text(stringResource(R.string.dating_profile_looking_for).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
        ChoiceChips(listOf(LookingFor.DATING to stringResource(R.string.option_dating), LookingFor.FRIENDS to stringResource(R.string.option_friends), LookingFor.BOTH to stringResource(R.string.option_dating_and_friends)), lookingFor, onLookingFor)
    }
}

/** Screen 4: photo (face-centred crop), bio ("Write one for me") and Instagram. Optional. */
@Composable
private fun CardStep(container: AppContainer, profile: Profile, assistant: OnboardingAssistant, onSkip: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bio by rememberSaveable { mutableStateOf(profile.bio) }
    var ig by rememberSaveable { mutableStateOf(profile.instagram.orEmpty()) }
    val charts = remember(profile.birth) { profile.birth?.let { runCatching { Charts.compute(it, profile.bloodType) }.getOrNull() } }
    StepPage(
        title = stringResource(R.string.onb_card_title),
        stepIndex = 3,
        onSkip = onSkip,
        actions = {
            val igValid = ig.isBlank() || InstagramHandle.normalize(ig) != null
            // An invalid Instagram entry keeps Done disabled (fix it or clear it), instead of being dropped.
            GoldButton(stringResource(R.string.onb_done), enabled = igValid, modifier = Modifier.fillMaxWidth().testTag("done"), onClick = {
                scope.launch {
                    container.profileStore.update {
                        it.copy(
                            bio = NearbyRepository.trimUtf8(bio.trim(), 280),
                            instagram = if (igValid) InstagramHandle.normalize(ig) else it.instagram,
                        )
                    }
                    onDone()
                }
            })
        },
    ) {
        PhotoEditor(container, profile, charts)
        BioEditor(bio, { bio = it }, charts, profile.lookingFor, profile.interests, profile.localPhotoPath, assistant)
        InstagramField(ig, { ig = it })
    }
}

/** Your photo + "Choose from your photos" / "Take a photo" → crop sheet. */
@Composable
fun PhotoEditor(container: AppContainer, profile: Profile, charts: Charts?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    var cameraRationale by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            cropping = runCatching { withContext(Dispatchers.IO) { PalmScanner.loadUri(context, uri) } }.onFailure { error = it.message }.getOrNull()
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> if (bitmap != null) cropping = bitmap }
    val cameraDenied = stringResource(R.string.perm_camera_denied)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(null) else error = cameraDenied
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProfilePhoto(
            localPath = profile.localPhotoPath,
            placeholder = charts?.natal?.sunSign?.let(com.metavirtuoso.conjunction.ui.components.Art::sign) ?: R.drawable.brand_mark,
            contentDescription = stringResource(R.string.photo_title),
            modifier = Modifier.width(150.dp).aspectRatio(4f / 5f).clip(CutCornerShape(14.dp)).border(2.dp, GoldBrushes.leaf, CutCornerShape(14.dp)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(stringResource(if (profile.localPhotoPath == null) R.string.photo_add else R.string.photo_change), onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            GhostButton(stringResource(R.string.photo_take), onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(null)
                else cameraRationale = true
            })
        }
        FinePrint(stringResource(R.string.photo_note), Modifier.fillMaxWidth())
        error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodySmall) }
    }
    cropping?.let { bitmap ->
        PhotoCropSheet(bitmap, onCancel = { cropping = null }, onSave = { rect ->
            cropping = null
            scope.launch {
                val path = withContext(Dispatchers.Default) { com.metavirtuoso.conjunction.data.ProfilePhotoCrop.save(context, bitmap, rect) }
                container.profileStore.update { it.copy(localPhotoPath = path) }
            }
        })
    }
    if (cameraRationale) {
        PermissionRationaleDialog(
            title = stringResource(R.string.perm_camera_title), body = stringResource(R.string.perm_camera_body), button = stringResource(R.string.perm_camera_button),
            art = null,
            onConfirm = { cameraRationale = false; cameraPermission.launch(Manifest.permission.CAMERA) },
            onDismiss = { cameraRationale = false },
        )
    }
}

/** In-app rationale shown before the camera permission prompt (consent copy section 9). */
@Composable
fun PermissionRationaleDialog(title: String, body: String, button: String, art: Int?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkViolet,
        title = { GoldText(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                art?.let { Image(painterResource(it), contentDescription = null, modifier = Modifier.fillMaxWidth().height(140.dp)) }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(button.uppercase(), color = Palette.Gold, style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now).uppercase(), color = Palette.MoonstoneDim, style = MaterialTheme.typography.labelLarge) } },
    )
}

/**
 * Bio line with "Write one for me": the bundled fallback line shows at once; when the on-device
 * model is ready it refines it within 6 s (never blocking, never an error). "Another" re-rolls.
 */
@Composable
fun BioEditor(
    bio: String,
    onBio: (String) -> Unit,
    charts: Charts?,
    lookingFor: LookingFor?,
    interests: String?,
    photoPath: String?,
    assistant: OnboardingAssistant,
) {
    val scope = rememberCoroutineScope()
    var seed by rememberSaveable { mutableStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }
    var writing by remember { mutableStateOf(false) }
    fun write(reroll: Boolean) {
        val c = charts ?: return
        if (reroll) seed++
        onBio(OnboardingAssistant.fallbackBio(c.natal, interests, seed))
        job?.cancel()
        writing = true
        job = scope.launch {
            val photo = photoPath?.let { runCatching { withContext(Dispatchers.IO) { com.metavirtuoso.conjunction.nearby.PhotoCodec.decode(java.io.File(it).readBytes(), 512) } }.getOrNull() }
            assistant.bio(c.natal, c.chinese.yearAnimal, lookingFor, interests, photo, reroll)?.let(onBio)
            writing = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OrnateTextField(
            bio, { onBio(it.replace('\n', ' ').take(140)) }, label = stringResource(R.string.bio_label),
            supporting = "${bio.length}/140", modifier = Modifier.testTag("bio"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(stringResource(R.string.bio_write_for_me), enabled = charts != null, onClick = { write(reroll = false) })
            if (bio.isNotBlank() && charts != null) GhostButton(stringResource(R.string.bio_another), onClick = { write(reroll = true) })
        }
        if (writing) FinePrint("Polishing it on this phone…")
    }
}

/** "Instagram (optional)": typed or pasted handle / profile link, normalised (SPEC 5d.6). */
@Composable
fun InstagramField(value: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    val invalid = value.isNotBlank() && InstagramHandle.normalize(value) == null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OrnateTextField(
                    value, { onValue(it.take(200)) }, label = stringResource(R.string.instagram_label),
                    isError = invalid,
                    // The normalised handle only when it differs from what was typed (e.g. a pasted profile URL).
                    supporting = if (invalid) stringResource(R.string.instagram_invalid)
                    else InstagramHandle.normalize(value)?.takeIf { it != value.trim().removePrefix("@") }?.let { "@$it" },
                    modifier = Modifier.testTag("instagram"),
                )
            }
            Spacer(Modifier.width(8.dp))
            GhostButton(stringResource(R.string.instagram_paste), onClick = {
                Instagram.clipboardText(context)?.let { pasted -> onValue(InstagramHandle.normalize(pasted) ?: pasted.trim().take(200)) }
            })
        }
        FinePrint(stringResource(R.string.instagram_note))
    }
}

/** Crop sheet: drag to move, pinch to zoom; starts on the face-centred 4:5 crop. */
@Composable
fun PhotoCropSheet(bitmap: Bitmap, onCancel: () -> Unit, onSave: (android.graphics.RectF) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var crop by remember(bitmap) {
        mutableStateOf(com.metavirtuoso.conjunction.data.ProfilePhotoCrop.cropFor(bitmap.width, bitmap.height, com.metavirtuoso.conjunction.data.ProfilePhotoCrop.detectFace(bitmap)))
    }
    com.metavirtuoso.conjunction.ui.components.CelestialSheet(onDismiss = onCancel) {
        GoldText(stringResource(R.string.photo_title), style = MaterialTheme.typography.titleLarge, heading = true)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 360.dp).align(Alignment.CenterHorizontally)) {
            val frameW = constraints.maxWidth.toFloat()
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(CutCornerShape(14.dp))
                    .androidxTransform(frameW, bitmap.width, bitmap.height, { crop }) { crop = it },
            ) {
                drawImage(
                    image,
                    srcOffset = androidx.compose.ui.unit.IntOffset(crop.left.toInt(), crop.top.toInt()),
                    srcSize = androidx.compose.ui.unit.IntSize(crop.width().toInt().coerceAtLeast(1), crop.height().toInt().coerceAtLeast(1)),
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
        }
        FinePrint("Drag to move, pinch to zoom.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(R.string.not_now), onClick = onCancel, modifier = Modifier.weight(1f))
            GoldButton("Use photo", onClick = { onSave(crop) }, modifier = Modifier.weight(1f))
        }
    }
}

/** Pan / zoom gesture that moves the crop rectangle (source pixels) under a frame [frameW] px wide. */
private fun Modifier.androidxTransform(frameW: Float, w: Int, h: Int, current: () -> android.graphics.RectF, onChange: (android.graphics.RectF) -> Unit): Modifier =
    pointerInput(frameW, w, h) {
        detectTransformGestures { _, pan, zoom, _ ->
            val c = current()
            val scale = frameW / c.width()
            val maxW = minOf(w.toFloat(), h * 4f / 5f)
            val newW = (c.width() / zoom).coerceIn(minOf(64f, maxW), maxW)
            val newH = newW * 5f / 4f
            val left = (c.centerX() - pan.x / scale - newW / 2f).coerceIn(0f, w - newW)
            val top = (c.centerY() - pan.y / scale - newH / 2f).coerceIn(0f, h - newH)
            onChange(android.graphics.RectF(left, top, left + newW, top + newH))
        }
    }
