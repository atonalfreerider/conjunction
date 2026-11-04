import SwiftUI
import PhotosUI
import AVFoundation
import ConjunctionCore

/// UX 2.0 onboarding (SPEC 5d.5; Android `OnboardingFlow`): four screens, Welcome · Your birth
/// (free text -> confirm chips, the L1 clickwrap line and Begin) · About you (one sentence ->
/// editable chips, optional) · Your card (photo, bio, Instagram, optional). Only the birth date,
/// place and L1 are required. No permission prompt during onboarding (the photo picker needs none).
/// The deterministic parsers fill the chips first; the on-device model only fills chips that are
/// still empty, only when it is available now, and never blocks a button.
struct OnboardingFlow: View {
    @EnvironmentObject var app: AppModel
    @State private var step: OnboardingScreen = .welcome
    @State private var started = false

    private var background: String {
        switch step {
        case .welcome: return "bg_onboarding_1"
        case .birth: return "bg_onboarding_2"
        default: return "bg_onboarding_3"
        }
    }

    var body: some View {
        StarfieldBackground(background: background, dim: step == .welcome ? 0.55 : 0.84) {
            Group {
                switch step {
                case .welcome: WelcomeStep { go(.birth) }
                case .birth: BirthStep { go(.about) }
                case .about: AboutYouStep(onSkip: { go(.card) }, onNext: { go(.card) })
                case .card: CardStep(onDone: { app.finishOnboarding() })
                }
            }
            .id(step)
            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
        }
        .onAppear {
            guard !started else { return }
            started = true
            if let resume = app.profile.resumeScreen {
                step = resume
            } else {
                // A user the old flow had taken past the dating step: onboarding is over.
                app.update { $0.onboardingComplete = true; $0.onboardingStep = nil }
            }
        }
    }

    private func go(_ next: OnboardingScreen) {
        withAnimation(.easeInOut(duration: 0.35)) { step = next }
        app.update { $0.onboardingStep = next.rawValue }
    }
}

/// Page scaffold: 4-dot progress, optional Skip, scrolling content, fixed bottom actions. At
/// accessibility text sizes the actions (privacy line, clickwrap, Begin) scroll with the content
/// instead, so a tall clickwrap can never push Begin off screen; their order is unchanged.
struct StepPage<Content: View, Actions: View>: View {
    let title: String
    var stepIndex: Int?
    var onSkip: (() -> Void)?
    @ViewBuilder var actions: Actions
    @ViewBuilder var content: Content
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                if let stepIndex {
                    HStack(spacing: 10) {
                        ForEach(0..<4, id: \.self) { i in
                            Circle().fill(i <= stepIndex ? Palette.gold : Palette.twilight)
                                .frame(width: i == stepIndex ? 10 : 8, height: i == stepIndex ? 10 : 8)
                        }
                    }
                    .accessibilityElement()
                    .accessibilityLabel("Step \(stepIndex + 1) of 4")
                }
                if let onSkip {
                    HStack {
                        Spacer()
                        Button(action: onSkip) {
                            Text(AppText.onbSkip.uppercased()).typeStyle(.labelLarge).foregroundStyle(Palette.goldPale)
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .frame(height: 48)
            .padding(.horizontal, 12)
            ScrollView {
                VStack(spacing: 14) {
                    GoldText(title, style: .headlineMedium, heading: true)
                    OrnateDivider()
                    content
                    if typeSize.isAccessibilitySize {
                        VStack(spacing: 6) { actions }.padding(.top, 10)
                    }
                }
                .padding(.horizontal, 20).padding(.vertical, 4)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            if !typeSize.isAccessibilitySize {
                VStack(spacing: 6) { actions }
                    .padding(.horizontal, 20).padding(.vertical, 10)
                    .frame(maxWidth: 640)
            }
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
            Spacer().frame(height: 36)
            GoldButton(AppText.onbWelcomeStart, action: onNext)
            Spacer().frame(height: 24)
        }
        .padding(24)
        .frame(maxWidth: 640)
    }
}

// MARK: - 2. Your birth

/// Screen 2: free-text birth entry -> confirm chips (parser first, model fills gaps) -> L1 -> Begin.
private struct BirthStep: View {
    @EnvironmentObject var app: AppModel
    let onNext: () -> Void
    let assistant = OnboardingAssistant()
    let today = LocalDate.todayInCurrentTimeZone()
    @State private var text = ""
    @State private var parsed = BirthTextResult(date: nil, time: nil, timeUnknown: false, placeQuery: nil, ambiguousDate: false)
    @State private var model: BirthExtraction?
    @State private var askNonce = 0
    @State private var working = false
    // Values the person picked with a picker win over anything parsed or suggested.
    @State private var userDate: LocalDate?
    @State private var userTime: LocalTime?
    @State private var userTimeSet = false
    @State private var userTimeUnknown = false
    @State private var place: PlaceResult?
    @State private var placeFromUser = false
    @State private var candidates: [PlaceResult] = []
    @State private var searchingPlace = false
    @State private var separately = false
    @State private var accepted = false
    @State private var alreadyAccepted = false
    @State private var openDoc: LegalDoc?
    @State private var loaded = false

    private var merged: BirthTextResult { OnboardingExtraction.fillEmpty(parsed, model) }
    private var date: LocalDate? {
        userDate ?? merged.date.flatMap { BirthTextParser.isAcceptableDate($0, today: today) ? $0 : nil }
    }
    private var timeUnknown: Bool { userTimeSet ? userTimeUnknown : merged.timeUnknown }
    private var time: LocalTime? { userTimeSet ? userTime : merged.time }
    private var placeQuery: String? { merged.placeQuery }

    var body: some View {
        StepPage(title: AppText.onbBirthTitle, stepIndex: 1, actions: {
            if !alreadyAccepted {
                LinkedText(template: L10n.legalPrivacyLine, links: [(L10n.legalLinkPrivacy, { openDoc = .privacy })])
                    .frame(maxWidth: .infinity, alignment: .leading)
                ClickwrapRow(checked: $accepted) { openDoc = $0 }
            }
            GoldButton(L10n.legalBegin, enabled: (accepted || alreadyAccepted) && date != nil && place != nil, action: begin)
        }) {
            if !separately {
                OrnateTextField(label: "Date, time and place", text: $text, limit: 200, onSubmit: ask,
                                placeholder: AppText.onbBirthHint, onFocusLost: ask)
            }
            BirthChips(date: date, time: time, timeUnknown: timeUnknown && time == nil, place: place, placeQuery: placeQuery,
                       placeCandidates: candidates,
                       onDate: { userDate = $0 },
                       onTime: { t, unknown in
                           userTimeSet = true
                           userTime = t
                           userTimeUnknown = unknown
                       },
                       onPlace: { place = $0; placeFromUser = true },
                       ambiguousDate: parsed.ambiguousDate && userDate == nil,
                       working: working,
                       searchingPlace: searchingPlace)
            SheetLink(text: AppText.onbBirthSeparately) { separately.toggle() }
                .padding(.horizontal, 10)
                .background(Palette.midnight.opacity(0.75).clipShape(CutCornerShape(cut: 8)))
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            let p = app.profile
            alreadyAccepted = app.clickwrapAlreadyAccepted
            userDate = p.birth?.date ?? p.ageGateBirthDate
            if let b = p.birth {
                userTimeSet = true
                userTime = b.time
                userTimeUnknown = b.time == nil
                place = PlaceResult(name: b.placeName, latitude: b.latitude, longitude: b.longitude, zoneId: b.zoneId, offsetLabel: "")
                placeFromUser = true
            }
        }
        // Deterministic parse 400 ms after typing stops.
        .task(id: text) {
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            parsed = BirthTextParser.parse(text, region: Locale.current.region?.identifier, today: today)
            if text.trimmingCharacters(in: .whitespaces).isEmpty { model = nil }
        }
        // The geocoder resolves the place query: top result in the chip, up to 5 under "Change".
        .task(id: "\(placeQuery ?? "")|\(date?.description ?? "")") {
            guard !placeFromUser else { return }
            guard let q = placeQuery else {
                // The text no longer names a place: drop the old one so Begin can't save it.
                place = nil
                candidates = []
                return
            }
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            searchingPlace = true
            let found = (try? await PlaceSearch.search(q, date: date ?? LocalDate(1995, 1, 1), time: time)) ?? []
            searchingPlace = false
            guard !Task.isCancelled, !placeFromUser else { return }
            candidates = Array(found.prefix(5))
            place = found.first
        }
        // On Done / focus loss, the on-device model fills what is still missing (latest input wins).
        .task(id: askNonce) {
            guard askNonce > 0 else { return }
            let asked = text
            guard !asked.trimmingCharacters(in: .whitespaces).isEmpty, date == nil || place == nil else { return }
            working = true
            let answer = await assistant.birth(asked, today: today)
            working = false
            if !Task.isCancelled, asked == text, let answer { model = answer }
        }
        .sheet(item: $openDoc) { LegalDocSheet(doc: $0) }
    }

    private func ask() { askNonce += 1 }

    private func begin() {
        guard let d = date, let p = place else { return }
        // Under 18: the install is blocked (the date is not kept); nothing is recorded.
        guard Age.isAdult(d, today: today) else {
            app.saveBirth(date: d, time: nil, place: p)
            return
        }
        if !alreadyAccepted { app.recordClickwrap() }
        if app.saveBirth(date: d, time: timeUnknown ? nil : time, place: p) { onNext() }
    }
}

// MARK: - 3. About you

/// Screen 3: one sentence about you -> editable chips (parser first, model fills gaps). Optional.
/// Nothing here leaves the phone; the fields are sent only after the Nearby consent (L2).
private struct AboutYouStep: View {
    @EnvironmentObject var app: AppModel
    let onSkip: () -> Void
    let onNext: () -> Void
    let assistant = OnboardingAssistant()
    @State private var text = ""
    @State private var name = ""
    @State private var gender: Gender?
    @State private var seeking: Seeking?
    @State private var status: RelationshipStatus?
    @State private var lookingFor: LookingFor?
    @State private var interests = ""
    /// Chips the person set by hand are never changed by the parser or the model.
    @State private var edited: Set<String> = []
    @State private var loaded = false
    @State private var applying = false

    var body: some View {
        StepPage(title: AppText.onbAboutTitle, stepIndex: 2, onSkip: onSkip, actions: {
            GoldButton(AppText.onbContinue) {
                app.update {
                    let n = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
                    if !n.isEmpty { $0.displayName = n }
                    $0.gender = gender
                    $0.seeking = seeking
                    $0.relationshipStatus = status
                    $0.lookingFor = lookingFor
                    $0.interests = OnboardingExtraction.cleanInterests(interests) ?? ""
                }
                onNext()
            }
        }) {
            OrnateTextField(label: "In your own words", text: $text, multiline: true, minLines: 2, limit: 500,
                            placeholder: AppText.onbAboutHint)
            OrnateTextField(label: AppText.onbFirstName, text: binding($name, "name"), limit: 40)
            // Plain bindings: only a tap on a chip marks it as set by hand (onUserEdit), so the
            // Looking-for default that follows Status never blocks the parser or the model.
            DatingFields(gender: $gender, seeking: $seeking, status: $status, lookingFor: $lookingFor,
                         lookingForEdited: edited.contains("lookingFor"), onUserEdit: { touch($0) })
            OrnateTextField(label: AppText.onbInterests, text: binding($interests, "interests"), limit: 200)
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            let p = app.profile
            name = p.displayName
            gender = p.gender
            seeking = p.seeking
            status = p.relationshipStatus
            lookingFor = p.lookingFor
            interests = p.interests
        }
        .task(id: text) {
            guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            apply(AboutYouParser.parse(text), onlyEmpty: false)
            // Then the on-device model, only for what is still empty (cancelled when the text changes).
            if let found = await assistant.aboutYou(text), !Task.isCancelled { apply(found, onlyEmpty: true) }
        }
    }

    private func touch(_ field: String) { if !applying { edited.insert(field) } }

    /// A binding that marks the chip as set by hand.
    private func binding<T>(_ b: Binding<T>, _ field: String) -> Binding<T> {
        Binding(get: { b.wrappedValue }, set: { b.wrappedValue = $0; touch(field) })
    }

    private func apply(_ found: AboutYou, onlyEmpty: Bool) {
        applying = true
        defer { applying = false }
        if !edited.contains("name"), let n = found.firstName, !onlyEmpty || name.trimmingCharacters(in: .whitespaces).isEmpty { name = n }
        if !edited.contains("gender"), let g = found.gender, !onlyEmpty || gender == nil { gender = g }
        if !edited.contains("seeking"), let s = found.seeking, !onlyEmpty || seeking == nil { seeking = s }
        if !edited.contains("status"), let r = found.relationship, !onlyEmpty || status == nil {
            status = r
            if r == .inARelationship && !edited.contains("lookingFor") { lookingFor = .friends }
        }
        if !edited.contains("lookingFor"), let l = found.lookingFor, !onlyEmpty || lookingFor == nil, status != .inARelationship {
            lookingFor = l
        }
        if !edited.contains("interests"), let i = found.interests, !onlyEmpty || interests.trimmingCharacters(in: .whitespaces).isEmpty {
            interests = i
        }
    }
}

/// Gender / seeking / relationship status / looking-for chips (onboarding, the Nearby sheet, Profile).
struct DatingFields: View {
    @Binding var gender: Gender?
    @Binding var seeking: Seeking?
    @Binding var status: RelationshipStatus?
    @Binding var lookingFor: LookingFor?
    /// nil = no edit tracking (Nearby sheet, Profile): Status fills Looking for only while it is
    /// empty. Otherwise Status fills it until the person picked Looking for by hand.
    var lookingForEdited: Bool? = nil
    /// Called with "gender", "seeking", "status" or "lookingFor" when the person taps that chip.
    var onUserEdit: ((String) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            label(L10n.datingProfileIAm)
            ChoiceChips(options: [(Gender.male, L10n.optionMale), (.female, L10n.optionFemale)], selected: gender) { gender = $0; onUserEdit?("gender") }
            label(L10n.datingProfileSeeking)
            ChoiceChips(options: [(Seeking.male, L10n.optionSeekingMen), (.female, L10n.optionSeekingWomen), (.both, L10n.optionBoth)], selected: seeking) {
                seeking = $0
                onUserEdit?("seeking")
            }
            label(L10n.datingProfileStatus)
            ChoiceChips(options: [(RelationshipStatus.single, L10n.optionSingle), (.inARelationship, L10n.optionInRelationship)], selected: status) { s in
                status = s
                onUserEdit?("status")
                // The one place the "In a relationship → Friends" default is applied.
                let fill = lookingForEdited.map { !$0 } ?? (lookingFor == nil)
                if fill || s == .inARelationship { lookingFor = MatchFilter.defaultLookingFor(s) }
            }
            if status == .inARelationship { FinePrint(L10n.datingProfileInRelationshipNote) }
            label(L10n.datingProfileLookingFor)
            ChoiceChips(options: [(LookingFor.dating, L10n.optionDating), (.friends, L10n.optionFriends), (.both, L10n.optionDatingAndFriends)],
                        selected: lookingFor) { lookingFor = $0; onUserEdit?("lookingFor") }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func label(_ s: String) -> some View {
        Text(s.uppercased()).typeStyle(.labelMedium).foregroundStyle(Palette.gold)
    }
}

// MARK: - 4. Your card

/// Screen 4: photo (face-centred crop), bio ("Write one for me") and Instagram. Optional.
private struct CardStep: View {
    @EnvironmentObject var app: AppModel
    let onDone: () -> Void
    @State private var bio = ""
    @State private var ig = ""
    @State private var loaded = false

    private var igOk: Bool { ig.trimmingCharacters(in: .whitespaces).isEmpty || InstagramHandle.normalize(ig) != nil }

    var body: some View {
        StepPage(title: AppText.onbCardTitle, stepIndex: 3, onSkip: onDone, actions: {
            // An invalid Instagram entry keeps Done disabled (fix or clear it), as in the Profile editor.
            GoldButton(AppText.onbDone, enabled: igOk) {
                app.update { $0.bio = NearbyText.trimUTF8(bio.trimmingCharacters(in: .whitespacesAndNewlines), 280) }
                guard app.setInstagram(ig) else { return }
                onDone()
            }
        }) {
            PhotoEditor()
            BioEditor(bio: $bio, lookingFor: app.profile.lookingFor, interests: app.profile.interests)
            InstagramField(value: $ig)
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            bio = app.profile.bio
            ig = app.profile.instagram ?? ""
        }
    }
}

/// Your photo + "Choose from your photos" / "Take a photo" -> the crop sheet.
struct PhotoEditor: View {
    @EnvironmentObject var app: AppModel
    @State private var pick: PhotosPickerItem?
    @State private var cropping: CropImage?
    @State private var camera = false
    @State private var error: String?

    struct CropImage: Identifiable {
        let id = UUID()
        let image: UIImage
    }

    var body: some View {
        VStack(spacing: 8) {
            ProfilePhoto(url: app.ownerPhotoURL, placeholder: app.charts.map { Art.sign($0.natal.sunSign) } ?? "brand_mark")
                .frame(width: 150, height: 150 * 5 / 4)
                .clipShape(CutCornerShape(cut: 14))
                .overlay(CutCornerShape(cut: 14).stroke(GoldBrush.leaf, lineWidth: 2))
                .accessibilityLabel(L10n.photoTitle)
            HStack(spacing: 8) {
                PhotosPicker(selection: $pick, matching: .images, photoLibrary: .shared()) {
                    Text((app.profile.photoFile == nil ? L10n.photoAdd : L10n.photoChange).uppercased()).typeStyle(.labelMedium)
                        .foregroundStyle(Palette.goldPale).lineLimit(1).minimumScaleFactor(0.8)
                        .padding(.horizontal, 12).frame(minHeight: 48)
                        .background(Palette.midnight.opacity(0.55).clipShape(CutCornerShape(cut: 10)))
                        .overlay(CutCornerShape(cut: 10).stroke(GoldBrush.leaf, lineWidth: 1))
                }
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    GhostButton(AppText.photoTake, fullWidth: false, action: takePhoto)
                }
            }
            FinePrint(L10n.photoNote)
            if let error { Text(error).typeStyle(.bodySmall).foregroundStyle(Palette.roseCopper) }
        }
        .frame(maxWidth: .infinity)
        .onChange(of: pick) { _, item in
            guard let item else { return }
            pick = nil
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let image = await Task.detached(priority: .userInitiated, operation: { ProfilePhotoCrop.load(data) }).value {
                    cropping = CropImage(image: image)
                } else {
                    error = "The photo could not be used."
                }
            }
        }
        .sheet(item: $cropping) { c in
            PhotoCropSheet(image: c.image) { jpeg in app.setPhoto(jpeg: jpeg) }
        }
        .fullScreenCover(isPresented: $camera) {
            CameraPicker { image in
                camera = false
                if let image { cropping = CropImage(image: PhotoCodec.normalized(image, maxEdge: 2048)) }
            }
            .ignoresSafeArea()
        }
    }

    /// The camera permission is asked only now (never during the rest of onboarding).
    private func takePhoto() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: camera = true
        case .notDetermined:
            Task {
                if await AVCaptureDevice.requestAccess(for: .video) { camera = true } else { error = L10n.permCameraDenied }
            }
        default: error = L10n.permCameraDenied
        }
    }
}

/// Crop sheet: drag to move, pinch to zoom; starts on the face-centred 4:5 crop.
struct PhotoCropSheet: View {
    let image: UIImage
    let onSave: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var crop: PhotoCropGeometry.Rect?
    @State private var base: PhotoCropGeometry.Rect?
    @State private var saving = false

    var body: some View {
        let size = ProfilePhotoCrop.pixelSize(image)
        OrnateSheet(title: L10n.photoTitle, onClose: { dismiss() }) {
            GeometryReader { geo in
                let frameW = geo.size.width
                let c = crop ?? PhotoCropGeometry.cropFor(width: size.width, height: size.height, face: nil)
                let scale = frameW / c.width
                Image(uiImage: image).resizable()
                    .frame(width: size.width * scale, height: size.height * scale)
                    .offset(x: -c.x * scale, y: -c.y * scale)
                    .frame(width: frameW, height: frameW * 5 / 4, alignment: .topLeading)
                    .clipShape(CutCornerShape(cut: 14))
                    .overlay(CutCornerShape(cut: 14).stroke(GoldBrush.leaf, lineWidth: 2))
                    .contentShape(Rectangle())
                    // One combined gesture: move and pinch together update the crop in a single call
                    // from one shared start, so neither resets the other.
                    .gesture(
                        SimultaneousGesture(DragGesture(), MagnifyGesture())
                            .onChanged { v in
                                let start = base ?? c
                                if base == nil { base = c }
                                let t = v.first?.translation ?? .zero
                                crop = PhotoCropGeometry.moved(start, width: size.width, height: size.height, frameWidth: frameW,
                                                               dx: t.width, dy: t.height, zoom: v.second?.magnification ?? 1)
                            }
                            .onEnded { _ in base = nil }
                    )
            }
            .aspectRatio(4.0 / 5.0, contentMode: .fit)
            .frame(maxWidth: 360)
            .frame(maxWidth: .infinity)
            FinePrint("Drag to move, pinch to zoom.", alignment: .center).frame(maxWidth: .infinity)
            HStack(spacing: 10) {
                GhostButton(AppText.notNow) { dismiss() }
                GoldButton("Use photo", enabled: !saving) {
                    saving = true
                    let c = crop ?? PhotoCropGeometry.cropFor(width: size.width, height: size.height, face: nil)
                    let source = image
                    Task {
                        let jpeg = await Task.detached(priority: .userInitiated, operation: { ProfilePhotoCrop.render(source, crop: c) }).value
                        if let jpeg { onSave(jpeg) }
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.large])
        .task {
            // Face detection off the main thread; the sheet shows the centre crop meanwhile.
            let source = image
            let initial = await Task.detached(priority: .userInitiated, operation: { ProfilePhotoCrop.initialCrop(source) }).value
            if crop == nil { crop = initial }
        }
    }
}

/// The system camera for "Take a photo" (front camera; the picked image never leaves the phone).
struct CameraPicker: UIViewControllerRepresentable {
    let onDone: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        if UIImagePickerController.isCameraDeviceAvailable(.front) { picker.cameraDevice = .front }
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onDone: (UIImage?) -> Void
        init(onDone: @escaping (UIImage?) -> Void) { self.onDone = onDone }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            onDone(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onDone(nil) }
    }
}

/// Bio line with "Write one for me": the bundled fallback line shows at once; when the on-device
/// model is ready it refines it within 6 s (never blocking, never an error). "Another" re-rolls.
struct BioEditor: View {
    @EnvironmentObject var app: AppModel
    @Binding var bio: String
    let lookingFor: LookingFor?
    let interests: String?
    let assistant = OnboardingAssistant()
    @State private var seed = 0
    @State private var request = 0
    @State private var reroll = false
    @State private var writing = false
    /// The fallback line "Write one for me" put in the field; the model's line replaces only that.
    @State private var fallbackLine: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            OrnateTextField(label: AppText.bioLabel, text: Binding(get: { bio }, set: { bio = String($0.replacingOccurrences(of: "\n", with: " ").prefix(140)) }),
                            supporting: "\(bio.count)/140")
            HStack(spacing: 8) {
                GhostButton(AppText.bioWriteForMe, enabled: app.charts != nil, fullWidth: false) { write(reroll: false) }
                if !bio.trimmingCharacters(in: .whitespaces).isEmpty && app.charts != nil {
                    GhostButton(AppText.bioAnother, fullWidth: false) { write(reroll: true) }
                }
            }
            if writing { FinePrint("Polishing it on this phone…") }
        }
        .task(id: request) {
            guard request > 0, let charts = app.charts else { return }
            writing = true
            let line = await assistant.bio(charts, lookingFor: lookingFor, interests: interests, reroll: reroll)
            writing = false
            // Never overwrite a line the person started editing while the model was writing.
            if !Task.isCancelled, let line, bio == fallbackLine { bio = line; fallbackLine = line }
        }
    }

    private func write(reroll: Bool) {
        guard let charts = app.charts else { return }
        if reroll { seed += 1 }
        self.reroll = reroll
        let line = OnboardingAssistant.fallbackBio(charts, interests: interests, seed: seed)
        bio = line
        fallbackLine = bio
        request += 1
    }
}

/// "Instagram (optional)": typed or pasted handle / profile link, normalised (SPEC 5d.6). The
/// system Paste button reads the clipboard only when tapped (no paste prompt).
struct InstagramField: View {
    @Binding var value: String

    var body: some View {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = InstagramHandle.normalize(trimmed)
        let invalid = !trimmed.isEmpty && normalized == nil
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 8) {
                OrnateTextField(label: L10n.instagramLabel, text: $value, supporting: invalid ? AppText.instagramInvalid
                                    : normalized.flatMap { $0 == trimmed.replacingOccurrences(of: "@", with: "") ? nil : "@\($0)" },
                                limit: 200, isError: invalid)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                PasteButton(payloadType: String.self) { strings in
                    guard let pasted = strings.first else { return }
                    value = InstagramHandle.normalize(pasted) ?? String(pasted.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200))
                }
                .labelStyle(.titleOnly)
                .buttonBorderShape(.capsule)
                .tint(Palette.gold)
                .padding(.top, 22)
            }
            FinePrint(L10n.instagramNote)
        }
    }
}
