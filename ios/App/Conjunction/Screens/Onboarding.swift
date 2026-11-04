import SwiftUI
import ConjunctionCore

/// First run (Android `OnboardingFlow`): 18+ age gate → waiver / terms / privacy (versioned
/// acceptance) → birth date and exact time (or "unknown") → birth place (geocoded on the phone,
/// time zone from the placemark) → blood type (optional) → dating & friends profile (optional,
/// explicit consent) → Nearby (optional, off by default) → notifications. Strings come from
/// legal/IN_APP_CONSENT_COPY.md.
struct OnboardingFlow: View {
    @EnvironmentObject var app: AppModel
    @State private var step: OnboardingStep = .welcome
    @State private var birthTime: LocalTime?
    @State private var timeUnknown = false
    @State private var started = false

    private var birthDate: LocalDate {
        app.profile.birth?.date ?? app.profile.ageGateBirthDate ?? LocalDate.todayInCurrentTimeZone().plusYears(-25)
    }

    private var background: String {
        switch step {
        case .welcome, .age: return "bg_onboarding_1"
        case .legal, .birthTime, .birthPlace: return "bg_onboarding_2"
        default: return "bg_onboarding_3"
        }
    }

    var body: some View {
        StarfieldBackground(background: background, dim: step == .welcome ? 0.55 : 0.74) {
            Group {
                switch step {
                case .welcome: WelcomeStep { go(.age) }
                case .age: AgeGateStep { go(.legal) }
                case .legal: LegalStep { go(.birthTime) }
                case .birthTime:
                    BirthTimeStep(date: birthDate, time: $birthTime, unknown: $timeUnknown, onDate: nil, progress: 0.35) { go(.birthPlace) }
                case .birthPlace:
                    BirthPlaceStep(date: birthDate, time: timeUnknown ? nil : birthTime, progress: 0.5, onBack: { go(.birthTime) }) { go(.blood) }
                case .blood: BloodStep { go(.profile) }
                case .profile: DatingProfileStep(onSkip: { go(.notifications) }) { go(.nearby) }
                case .nearby: NearbyOptInStep { go(.notifications) }
                case .notifications: NotificationsStep { app.update { $0.onboardingComplete = true } }
                }
            }
            .id(step)
            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
        }
        .onAppear {
            guard !started else { return }
            started = true
            step = app.profile.resumeStep
            birthTime = app.profile.birth?.time
            timeUnknown = app.profile.birth != nil && app.profile.birth?.time == nil
        }
    }

    private func go(_ next: OnboardingStep) { withAnimation(.easeInOut(duration: 0.35)) { step = next } }
}

/// Common page: progress, scrolling content, actions at the bottom.
struct StepPage<Content: View, Actions: View>: View {
    let title: String
    var progress: Double?
    @ViewBuilder var actions: Actions
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) {
            if let progress {
                ProgressView(value: progress).tint(Palette.gold)
                    .padding(.horizontal, 24).padding(.vertical, 10)
                    .accessibilityLabel("Setup progress")
            }
            ScrollView {
                VStack(spacing: 14) {
                    GoldText(title, style: .headlineMedium, heading: true)
                    OrnateDivider()
                    content
                }
                .padding(.horizontal, 20).padding(.vertical, 12)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            VStack(spacing: 10) { actions }
                .padding(.horizontal, 20).padding(.vertical, 12)
                .frame(maxWidth: 640)
        }
    }
}

private struct WelcomeStep: View {
    let onNext: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Image("splash_mark").resizable().scaledToFit().frame(width: 150, height: 150).accessibilityHidden(true)
            GoldText(AppInfo.name, style: .displayMedium, heading: true)
            OrnateDivider().padding(.vertical, 8)
            Text(AppText.tagline).typeStyle(.headlineSmall).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            Text("Your birth chart, Chinese zodiac, blood-type and palm readings — written by the AI on this phone. And, if you choose, the people nearby whose stars align with yours.")
                .typeStyle(.bodyLarge).foregroundStyle(Palette.moonstoneDim).multilineTextAlignment(.center).padding(.top, 10)
            GoldButton("Begin", action: onNext).padding(.top, 28)
            Spacer().frame(height: 24)
        }
        .padding(24)
        .frame(maxWidth: 640)
    }
}

/// Converts between `LocalDate` and the Gregorian `Date` a DatePicker shows (at noon UTC, so no
/// zone can move it to another day).
enum PickerDates {
    static let utc = TimeZone(identifier: "UTC")!
    static var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = utc
        return c
    }

    static func date(_ d: LocalDate) -> Date { ZonedDateTime.instant(d, LocalTime(12, 0), utc) }
    static func local(_ date: Date) -> LocalDate { LocalDate.of(date, utc) }

    static func longText(_ d: LocalDate) -> String {
        let f = DateFormatter()
        f.dateStyle = .long
        f.timeZone = utc
        return f.string(from: date(d))
    }

    static func timeText(_ t: LocalTime) -> String {
        let f = DateFormatter()
        f.timeStyle = .short
        f.timeZone = utc
        return f.string(from: ZonedDateTime.instant(LocalDate(2000, 1, 1), t, utc))
    }
}

private struct AgeGateStep: View {
    @EnvironmentObject var app: AppModel
    let onNext: () -> Void
    @State private var dob: LocalDate?
    @State private var picking = false

    var body: some View {
        StepPage(title: L10n.ageGateTitle, progress: 0.1, actions: {
            GoldButton(L10n.ageGateContinue, enabled: dob != nil) {
                guard let date = dob else { return }
                if !Age.isAdult(date, today: LocalDate.todayInCurrentTimeZone()) {
                    // Under 18: block this install; the date is not kept.
                    app.update { $0.ageBlocked = true; $0.ageGateBirthDate = nil }
                } else {
                    app.update { $0.ageGateBirthDate = date }
                    onNext()
                }
            }
        }) {
            Text(L10n.ageGateBody).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            OrnatePanel {
                Text(L10n.ageGateDobLabel.uppercased()).typeStyle(.labelMedium).foregroundStyle(Palette.gold)
                Button { picking = true } label: {
                    Text(dob.map(PickerDates.longText) ?? "Tap to choose").typeStyle(.headlineSmall).foregroundStyle(Palette.moonstone)
                        .padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                GhostButton("Choose date", fullWidth: false) { picking = true }
            }
            FinePrint(L10n.ageGateDobNote)
        }
        .onAppear { dob = app.profile.ageGateBirthDate }
        .sheet(isPresented: $picking) {
            DatePickSheet(title: L10n.ageGateDobLabel, initial: dob ?? LocalDate.todayInCurrentTimeZone().plusYears(-25),
                          latest: LocalDate.todayInCurrentTimeZone()) { dob = $0 }
        }
    }
}

/// Graphical date picker on ink violet, limited to 1900...[latest].
struct DatePickSheet: View {
    let title: String
    let initial: LocalDate
    let latest: LocalDate
    let onPicked: (LocalDate) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value = Date()

    var body: some View {
        OrnateSheet(title: title, onClose: { dismiss() }) {
            DatePicker("", selection: $value, in: PickerDates.date(LocalDate(1900, 1, 1))...PickerDates.date(latest), displayedComponents: .date)
                .datePickerStyle(.wheel)
                .labelsHidden()
                .environment(\.timeZone, PickerDates.utc)
                .environment(\.calendar, PickerDates.calendar)
                .colorScheme(.dark)
                .frame(maxWidth: .infinity)
            GoldButton("OK") { onPicked(PickerDates.local(value)); dismiss() }
            GhostButton("Cancel") { dismiss() }
        }
        .presentationDetents([.medium, .large])
        .onAppear { value = PickerDates.date(initial) }
    }
}

private struct LegalStep: View {
    @EnvironmentObject var app: AppModel
    let onNext: () -> Void
    @State private var ageChecked = false
    @State private var agreeChecked = false
    @State private var open: LegalDoc?

    var body: some View {
        StepPage(title: L10n.legalTitle, progress: 0.2, actions: {
            GoldButton(L10n.legalContinue, enabled: ageChecked && agreeChecked) {
                for doc in LegalDoc.accepted { if let text = LegalTexts.load(doc) { app.recordDocument(text) } }
                app.record(ConsentKeys.age18Confirmed, version: ConsentKeys.copyVersion, text: L10n.legalCheckboxAge)
                app.update { $0.legalAccepted = true }
                onNext()
            }
        }) {
            OrnatePanel {
                ForEach([L10n.legalSummary1, L10n.legalSummary2, L10n.legalSummary3, L10n.legalSummary6, L10n.legalSummary4,
                         L10n.legalSummary5], id: \.self) { StarBullet(text: $0) }
            }
            GhostButton(L10n.legalReadWaiver) { open = .waiver }
            GhostButton(L10n.legalReadTerms) { open = .terms }
            GhostButton(L10n.legalReadPrivacy) { open = .privacy }
            GoldCheckRow(checked: $ageChecked, text: L10n.legalCheckboxAge)
            GoldCheckRow(checked: $agreeChecked, text: L10n.legalCheckboxAgree)
        }
        .sheet(item: $open) { LegalDocSheet(doc: $0) }
    }
}

/// Birth date (optionally editable), exact birth time or "unknown".
struct BirthTimeStep: View {
    let date: LocalDate
    @Binding var time: LocalTime?
    @Binding var unknown: Bool
    var onDate: ((LocalDate) -> Void)?
    var progress: Double?
    let onNext: () -> Void
    @State private var pickingTime = false
    @State private var pickingDate = false

    var body: some View {
        StepPage(title: "The moment you arrived", progress: progress, actions: {
            GoldButton("Continue", enabled: unknown || time != nil, action: onNext)
        }) {
            Text("Your birth chart needs the exact hour and minute you were born — the Ascendant moves a whole sign every two hours.")
                .typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            OrnatePanel {
                Text("BIRTH DATE").typeStyle(.labelMedium).foregroundStyle(Palette.gold)
                Text(PickerDates.longText(date)).typeStyle(.headlineSmall).foregroundStyle(Palette.moonstone)
                if onDate != nil { GhostButton("Change date", fullWidth: false) { pickingDate = true } }
                Text("BIRTH TIME (LOCAL CLOCK)").typeStyle(.labelMedium).foregroundStyle(Palette.gold).padding(.top, 12)
                Button { if !unknown { pickingTime = true } } label: {
                    Text(unknown ? "Unknown" : time.map(PickerDates.timeText) ?? "Tap to set")
                        .typeStyle(.headlineSmall).foregroundStyle(Palette.moonstone)
                        .padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                GhostButton("Set time", enabled: !unknown, fullWidth: false) { pickingTime = true }
                GoldCheckRow(checked: $unknown, text: "I don't know my birth time",
                             note: "Your chart will skip the Ascendant, houses and the BaZi hour pillar. You can add the time later in Profile.")
            }
            FinePrint(L10n.birthPrivacyNote)
        }
        .sheet(isPresented: $pickingTime) { TimePickSheet(initial: time ?? LocalTime(12, 0)) { time = $0 } }
        .sheet(isPresented: $pickingDate) {
            DatePickSheet(title: "Birth date", initial: date, latest: Age.latestAdultBirthDate(LocalDate.todayInCurrentTimeZone())) { onDate?($0) }
        }
    }
}

struct TimePickSheet: View {
    let initial: LocalTime
    let onPicked: (LocalTime) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value = Date()

    var body: some View {
        OrnateSheet(title: "Birth time", onClose: { dismiss() }) {
            DatePicker("", selection: $value, displayedComponents: .hourAndMinute)
                .datePickerStyle(.wheel).labelsHidden()
                .environment(\.timeZone, PickerDates.utc)
                .colorScheme(.dark)
                .frame(maxWidth: .infinity)
            GoldButton("OK") {
                let z = ZonedDateTime(value, PickerDates.utc)
                onPicked(LocalTime(z.hour, z.minute))
                dismiss()
            }
            GhostButton("Cancel") { dismiss() }
        }
        .presentationDetents([.medium])
        .onAppear { value = ZonedDateTime.instant(LocalDate(2000, 1, 1), initial, PickerDates.utc) }
    }
}

/// Birth place: geocoded on the phone; the time zone comes with the placemark.
struct BirthPlaceStep: View {
    @EnvironmentObject var app: AppModel
    let date: LocalDate
    let time: LocalTime?
    var progress: Double?
    let onBack: () -> Void
    let onNext: () -> Void
    @State private var query = ""
    @State private var results: [PlaceResult] = []
    @State private var chosen: PlaceResult?
    @State private var searching = false
    @State private var error: String?
    @State private var manual = false
    @State private var latText = ""
    @State private var lonText = ""

    var body: some View {
        StepPage(title: "Where you were born", progress: progress, actions: {
            GoldButton("Continue", enabled: chosen != nil) {
                guard let p = chosen else { return }
                app.update {
                    $0.birth = BirthInput(date: date, time: time, latitude: p.latitude, longitude: p.longitude, zoneId: p.zoneId, placeName: p.name)
                }
                onNext()
            }
            GhostButton("Back", action: onBack)
        }) {
            Text("We look up the place on this phone and work out the time zone and daylight-saving rules in force on your birthday.")
                .typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            OrnateTextField(label: "City or town of birth", text: $query, onSubmit: { if canSearch { search() } })
            GoldButton(searching ? "Searching…" : "Search", enabled: canSearch, action: search)
            if searching { OrreryLoader(size: 48) }
            if let error { Text(error).typeStyle(.bodyMedium).foregroundStyle(Palette.roseCopper).multilineTextAlignment(.center) }
            ForEach(results) { place in
                let selected = chosen == place
                Button { chosen = place } label: {
                    OrnatePanel(padding: 16) {
                        Text((selected ? "✦ " : "") + place.name).typeStyle(.titleMedium)
                            .foregroundStyle(selected ? Palette.goldPale : Palette.moonstone).multilineTextAlignment(.leading)
                        Text("\(place.zoneId) · \(place.offsetLabel)").typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
            if let place = chosen, !results.contains(place) {
                OrnatePanel(padding: 16) {
                    Text("✦ \(place.name)").typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                    Text(place.zoneId).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim)
                }
            }
            GhostButton(manual ? "Hide coordinates" : "Enter coordinates instead") { manual.toggle() }
            if manual {
                OrnateTextField(label: "Latitude (−90 to 90, north positive)", text: $latText, keyboard: .numbersAndPunctuation)
                OrnateTextField(label: "Longitude (−180 to 180, east positive)", text: $lonText, keyboard: .numbersAndPunctuation)
                GhostButton("Use these coordinates", action: useCoordinates)
            }
        }
        .onAppear {
            if let b = app.profile.birth, !b.placeName.isEmpty, chosen == nil {
                query = b.placeName
                chosen = PlaceResult(name: b.placeName, latitude: b.latitude, longitude: b.longitude, zoneId: b.zoneId, offsetLabel: "")
            }
        }
    }

    private var canSearch: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty && !searching }

    private func search() {
        searching = true
        error = nil
        Task {
            do {
                results = try await PlaceSearch.search(query, date: date, time: time)
            } catch {
                results = []
                self.error = error.localizedDescription
            }
            searching = false
        }
    }

    private func useCoordinates() {
        func number(_ s: String) -> Double? {
            Double(s.replacingOccurrences(of: ",", with: ".").replacingOccurrences(of: "−", with: "-").trimmingCharacters(in: .whitespaces))
        }
        guard let lat = number(latText), let lon = number(lonText), (-90...90).contains(lat), (-180...180).contains(lon) else {
            error = "Enter a latitude between −90 and 90 and a longitude between −180 and 180."
            return
        }
        let label = String(format: "%.4f, %.4f", lat, lon)
        let name = query.trimmingCharacters(in: .whitespaces).isEmpty ? label : query
        Task {
            chosen = await PlaceSearch.resolve(name: name, latitude: lat, longitude: lon, date: date, time: time)
            error = nil
        }
    }
}

private struct BloodStep: View {
    @EnvironmentObject var app: AppModel
    let onNext: () -> Void
    @State private var type: BloodType?
    @State private var rh: RhFactor?
    @State private var forMatching = false

    var body: some View {
        StepPage(title: L10n.bloodTypeTitle, progress: 0.62, actions: {
            GoldButton(type == nil ? "Skip" : "Continue") {
                let consent = forMatching && type != nil
                let wasConsented = app.profile.bloodForMatching
                app.update { $0.bloodType = type; $0.rh = rh; $0.bloodForMatching = consent }
                if consent && !wasConsented {
                    app.record(ConsentKeys.bloodTypeMatching, version: ConsentKeys.copyVersion, text: L10n.bloodTypeConsentCheckbox)
                }
                onNext()
            }
        }) {
            Text(L10n.bloodTypeBody).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            ChoiceChips(options: BloodType.allCases.map { ($0, "Type \($0.displayName)") }, selected: type) { type = $0 }
            if type != nil {
                ChoiceChips(options: RhChoice.options, selected: RhChoice(rh)) { rh = $0.factor }
                OrnatePanel {
                    GoldCheckRow(checked: $forMatching, text: L10n.bloodTypeConsentCheckbox, note: L10n.bloodTypeConsentNote)
                }
            }
        }
        .onAppear {
            type = app.profile.bloodType
            rh = app.profile.rh
            forMatching = app.profile.bloodForMatching
        }
    }
}

/// Rh+ / Rh− / Not sure as a chip value.
enum RhChoice: Hashable {
    case positive, negative, unsure
    init(_ f: RhFactor?) { self = f == .positive ? .positive : f == .negative ? .negative : .unsure }
    var factor: RhFactor? { self == .positive ? .positive : self == .negative ? .negative : nil }
    static let options: [(RhChoice, String)] = [(.positive, "Rh+"), (.negative, "Rh−"), (.unsure, "Not sure")]
}

private struct DatingProfileStep: View {
    @EnvironmentObject var app: AppModel
    let onSkip: () -> Void
    let onNext: () -> Void
    @State private var name = ""
    @State private var gender: Gender?
    @State private var seeking: Seeking?
    @State private var status: RelationshipStatus?
    @State private var lookingFor: LookingFor?
    @State private var consent = false
    @State private var honest = false

    private var complete: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && gender != nil && seeking != nil && status != nil && lookingFor != nil && consent && honest
    }

    var body: some View {
        StepPage(title: L10n.datingProfileTitle, progress: 0.75, actions: {
            GoldButton("Continue", enabled: complete) {
                app.record(ConsentKeys.orientationData, version: ConsentKeys.copyVersion, text: L10n.orientationConsentCheckbox)
                app.record(ConsentKeys.profileHonesty, version: ConsentKeys.copyVersion, text: L10n.honestyCheckbox)
                app.update {
                    $0.displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    $0.dating = DatingProfile(gender: gender!, seeking: seeking!, relationshipStatus: status!, lookingFor: lookingFor!)
                    $0.orientationConsent = true
                    $0.honestyConfirmed = true
                }
                onNext()
            }
            GhostButton("Skip — charts and readings only") {
                let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
                if !n.isEmpty { app.update { $0.displayName = n } }
                onSkip()
            }
        }) {
            OrnateTextField(label: "First name (shown on match cards)", text: $name, limit: 40)
            DatingFields(gender: $gender, seeking: $seeking, status: $status, lookingFor: $lookingFor)
            OrnatePanel {
                Text(L10n.orientationConsentBody).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                GoldCheckRow(checked: $consent, text: L10n.orientationConsentCheckbox, note: L10n.orientationConsentWithdrawNote)
                GoldCheckRow(checked: $honest, text: L10n.honestyCheckbox)
            }
        }
        .onAppear {
            name = app.profile.displayName
            gender = app.profile.dating?.gender
            seeking = app.profile.dating?.seeking
            status = app.profile.dating?.relationshipStatus
            lookingFor = app.profile.dating?.lookingFor
        }
    }
}

/// Gender / seeking / relationship status / looking-for pickers (also used in Profile).
struct DatingFields: View {
    @Binding var gender: Gender?
    @Binding var seeking: Seeking?
    @Binding var status: RelationshipStatus?
    @Binding var lookingFor: LookingFor?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            label(L10n.datingProfileIAm)
            ChoiceChips(options: [(Gender.male, L10n.optionMale), (.female, L10n.optionFemale)], selected: gender) { gender = $0 }
            label(L10n.datingProfileSeeking)
            ChoiceChips(options: [(Seeking.male, L10n.optionMale), (.female, L10n.optionFemale), (.both, L10n.optionBoth)], selected: seeking) { seeking = $0 }
            label(L10n.datingProfileStatus)
            ChoiceChips(options: [(RelationshipStatus.single, L10n.optionSingle), (.inARelationship, L10n.optionInRelationship)], selected: status) { s in
                status = s
                if lookingFor == nil || s == .inARelationship { lookingFor = MatchFilter.defaultLookingFor(s) }
            }
            if status == .inARelationship { FinePrint(L10n.datingProfileInRelationshipNote) }
            label(L10n.datingProfileLookingFor)
            ChoiceChips(options: [(LookingFor.dating, L10n.optionDating), (.friends, L10n.optionFriends), (.both, L10n.optionDatingAndFriends)],
                        selected: lookingFor) { lookingFor = $0 }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func label(_ s: String) -> some View {
        Text(s.uppercased()).typeStyle(.labelMedium).foregroundStyle(Palette.gold)
    }
}

private struct NearbyOptInStep: View {
    let onNext: () -> Void
    var body: some View { NearbyConsentContent(progress: 0.88, onDone: onNext) }
}

/// Nearby prominent disclosure (legal/IN_APP_CONSENT_COPY.md section 5) + consent + Bluetooth
/// rationale, then the system Bluetooth prompt (created by turning the radio on). No location is
/// ever requested. Reused from the Nearby tab.
struct NearbyConsentContent: View {
    @EnvironmentObject var app: AppModel
    var progress: Double?
    let onDone: () -> Void
    @State private var understood = false
    @State private var rationale = false
    @State private var safety = false
    @State private var error: String?

    static var disclosure: [String] {
        [L10n.nearbyBody, L10n.nearbyPointBluetooth, L10n.nearbyPointHello, L10n.nearbyPointCard, L10n.nearbyPointKeep,
         L10n.nearbyPointSwipe, L10n.nearbyPointSafety, L10n.nearbyPointControl]
    }

    var body: some View {
        StepPage(title: L10n.nearbyTitle, progress: progress, actions: {
            GoldButton(L10n.nearbyTurnOn, enabled: understood && app.profile.nearbyEligible) { rationale = true }
            GhostButton(L10n.nearbyNotNow, action: onDone)
        }) {
            Image("popup_match_nearby").resizable().scaledToFit().frame(height: 180).accessibilityHidden(true)
            Text(Self.disclosure[0]).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            OrnatePanel {
                ForEach(Array(Self.disclosure.dropFirst()), id: \.self) { StarBullet(text: $0) }
                FinePrint(L10n.nearbyIosBackgroundNote).padding(.top, 6)
            }
            GhostButton(L10n.nearbyReadSafety) { safety = true }
            GoldCheckRow(checked: $understood, text: L10n.nearbyCheckbox)
            if !app.profile.nearbyEligible {
                FinePrint("Complete your birth details and your dating & friends profile first (Profile → Who you'd like to meet).")
            }
            if let error { Text(error).typeStyle(.bodyMedium).foregroundStyle(Palette.roseCopper).multilineTextAlignment(.center) }
        }
        .sheet(isPresented: $rationale) {
            PermissionRationaleSheet(title: L10n.permBluetoothTitle, message: L10n.permBluetoothBody, button: L10n.permBluetoothButton,
                                     art: "popup_match_nearby") {
                rationale = false
                turnOn()
            }
        }
        .sheet(isPresented: $safety) {
            LegalDocSheet(doc: .safety) { text in
                app.recordDocument(text)
                app.update { $0.safetyTipsSeen = true }
            }
        }
    }

    private func turnOn() {
        guard app.nearby.bluetoothAllowed else {
            error = L10n.permBluetoothDenied
            return
        }
        // The consent record hashes the whole disclosure the person saw, plus the checkbox.
        app.record(ConsentKeys.nearbyBluetooth, version: ConsentKeys.copyVersion,
                   text: (Self.disclosure + [L10n.nearbyCheckbox]).joined(separator: "\n"))
        app.update { $0.nearbyConsent = true }
        do {
            try app.enableNearby() // starts the radio: iOS shows its Bluetooth prompt now
            onDone()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// In-app rationale shown before a system permission prompt (consent copy section 9).
struct PermissionRationaleSheet: View {
    let title: String
    let message: String
    let button: String
    var art: String?
    let onConfirm: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        OrnateSheet(title: title, onClose: { dismiss() }) {
            if let art { Image(art).resizable().scaledToFit().frame(maxWidth: .infinity).frame(height: 140).accessibilityHidden(true) }
            Text(message).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
            GoldButton(button, action: onConfirm)
            GhostButton("Not now") { dismiss() }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct NotificationsStep: View {
    @EnvironmentObject var app: AppModel
    let onDone: () -> Void

    var body: some View {
        StepPage(title: L10n.permNotificationsTitle, progress: 1, actions: {
            GoldButton(L10n.permNotificationsButton) {
                Task {
                    let granted = await Notifications.requestAuthorization()
                    app.update { $0.notificationsPrompted = true; $0.dailyReminder = granted }
                    onDone()
                }
            }
            GhostButton(L10n.nearbyNotNow, action: onDone)
        }) {
            Image("popup_friend_connected").resizable().scaledToFit().frame(width: 200, height: 200).accessibilityHidden(true)
            Text(L10n.permNotificationsBody).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            FinePrint(L10n.permNotificationsDriving, alignment: .center)
        }
    }
}

/// Profile → Edit birth details: date, time (or unknown) and place, saved together.
struct BirthEditor: View {
    @EnvironmentObject var app: AppModel
    let onDone: () -> Void
    @State private var page = 0
    @State private var date = LocalDate(2000, 1, 1)
    @State private var time: LocalTime?
    @State private var unknown = false
    @State private var loaded = false

    var body: some View {
        StarfieldBackground(background: "bg_onboarding_2", dim: 0.6) {
            VStack(spacing: 0) {
                HStack {
                    Button(action: { if page == 1 { page = 0 } else { onDone() } }) {
                        Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.goldPale)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Back")
                    Spacer()
                }
                .padding(.horizontal, 8)
                if page == 0 {
                    BirthTimeStep(date: date, time: $time, unknown: $unknown, onDate: { date = $0 }, progress: nil) { page = 1 }
                } else {
                    BirthPlaceStep(date: date, time: unknown ? nil : time, progress: nil, onBack: { page = 0 }, onNext: onDone)
                }
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            date = app.profile.birth?.date ?? app.profile.ageGateBirthDate ?? LocalDate.todayInCurrentTimeZone().plusYears(-25)
            time = app.profile.birth?.time
            unknown = app.profile.birth != nil && app.profile.birth?.time == nil
        }
    }
}
