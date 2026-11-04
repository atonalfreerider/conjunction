import SwiftUI
import PhotosUI
import ConjunctionCore

/// Profile (SPEC 5d.7; Android `ProfileScreen`): Your card (live preview of the match card as
/// others see it, tap to edit), Birth (chips; changing the date re-runs the 18+ check), Eastern
/// (blood type + the L3 switch), then the Settings rows: Nearby, Notifications, Privacy & legal.
/// One panel per group.
struct ProfileScreen: View {
    @EnvironmentObject var app: AppModel
    @Binding var page: SettingsPage?
    @State private var editing = false

    var body: some View {
        if let page {
            SettingsPageView(page: page, onBack: { self.page = page == .blocked ? .privacy : nil },
                             onBlocked: { self.page = .blocked })
        } else {
            TabPage(title: AppText.tabProfile, subtitle: app.profile.displayName.isEmpty ? nil : app.profile.displayName) {
                YourCard { editing = true }
                BirthPanel()
                EasternPanel()
                OrnatePanel(padding: 18) {
                    SettingsHeading(text: AppText.settingsTitle)
                    SettingsRow(title: AppText.settingsNearby) { page = .nearby }
                    SettingsRow(title: AppText.settingsNotifications) { page = .notifications }
                    SettingsRow(title: L10n.settingsPrivacyLegalTitle) { page = .privacy }
                }
                Spacer().frame(height: 20)
            }
            .sheet(isPresented: $editing) { CardEditorSheet().environmentObject(app) }
        }
    }
}

/// Your card: the match card as others see it (photo, first name, age, signs, kind). Tap to edit.
private struct YourCard: View {
    @EnvironmentObject var app: AppModel
    let onEdit: () -> Void

    var body: some View {
        let p = app.profile
        let c = app.charts
        let age = p.birth.flatMap { try? Age.yearsOn($0.date, today: LocalDate.todayInCurrentTimeZone()) }
        Button(action: onEdit) {
            OrnatePanel {
                SettingsHeading(text: AppText.onbCardTitle)
                HStack(alignment: .center, spacing: 14) {
                    ProfilePhoto(url: app.ownerPhotoURL, placeholder: c.map { Art.sign($0.natal.sunSign) } ?? "brand_mark")
                        .frame(width: 96, height: 120).clipShape(CutCornerShape(cut: 10))
                        .overlay(CutCornerShape(cut: 10).stroke(GoldBrush.leaf, lineWidth: 2))
                        .accessibilityLabel(L10n.photoTitle)
                    VStack(alignment: .leading, spacing: 4) {
                        GoldText((p.firstName.isEmpty ? (c.map { "A \($0.natal.sunSign.displayName)" } ?? "You") : p.firstName)
                                 + (age.map { ", \($0)" } ?? ""), style: .titleLarge, alignment: .leading)
                        if let c {
                            Text(["☉ \(c.natal.sunSign.displayName)", "☽ \(c.natal.moonSign.displayName)",
                                  c.natal.risingSign.map { "AC \($0.displayName)" }].compactMap { $0 }.joined(separator: "  "))
                                .typeStyle(.bodySmall).foregroundStyle(Palette.goldPale)
                        }
                        if let l = p.lookingFor { KindChip(kind: l == .friends ? "friends" : "dating") }
                        if !p.bio.isEmpty {
                            Text("“\(p.bio)”").typeStyle(.bodySmall).foregroundStyle(Palette.moonstone).lineLimit(2)
                        }
                        if let ig = p.instagram {
                            Text("Instagram @\(ig)").typeStyle(.labelSmall).foregroundStyle(Palette.moonstoneDim)
                        }
                    }
                    Spacer(minLength: 0)
                }
                Text("Tap to edit").typeStyle(.labelSmall).foregroundStyle(Palette.moonstoneDim).frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .buttonStyle(.plain)
        .accessibilityHint("Edit your card")
    }
}

/// Edit the card: photo / crop, first name, bio with "Write one for me", dating chips, Instagram.
private struct CardEditorSheet: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var bio = ""
    @State private var ig = ""
    @State private var gender: Gender?
    @State private var seeking: Seeking?
    @State private var status: RelationshipStatus?
    @State private var lookingFor: LookingFor?
    @State private var loaded = false

    var body: some View {
        let igOk = ig.trimmingCharacters(in: .whitespaces).isEmpty || InstagramHandle.normalize(ig) != nil
        OrnateSheet(title: AppText.onbCardTitle, onClose: { dismiss() }) {
            PhotoEditor()
            if app.profile.photoFile != nil {
                GhostButton(L10n.photoRemove, color: Palette.roseCopper) { app.removePhoto() }
            }
            OrnateTextField(label: AppText.onbFirstName, text: $name, limit: 40)
            BioEditor(bio: $bio, lookingFor: lookingFor, interests: app.profile.interests)
            Text(L10n.datingProfileTitle.uppercased()).typeStyle(.titleSmall).foregroundStyle(Palette.gold)
            DatingFields(gender: $gender, seeking: $seeking, status: $status, lookingFor: $lookingFor)
            InstagramField(value: $ig)
            GoldButton("Save", enabled: igOk) {
                app.update {
                    $0.displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    $0.bio = NearbyText.trimUTF8(bio.trimmingCharacters(in: .whitespacesAndNewlines), 280)
                    $0.gender = gender
                    $0.seeking = seeking
                    $0.relationshipStatus = status
                    $0.lookingFor = lookingFor
                }
                app.setInstagram(ig)
                dismiss()
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            let p = app.profile
            name = p.displayName
            bio = p.bio
            ig = p.instagram ?? ""
            gender = p.gender
            seeking = p.seeking
            status = p.relationshipStatus
            lookingFor = p.lookingFor
        }
    }
}

/// Birth: date / time / place chips; changing the date re-runs the 18+ check (SPEC 5d.1.2).
private struct BirthPanel: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        let b = app.profile.birth
        let current = b.map { PlaceResult(name: $0.placeName, latitude: $0.latitude, longitude: $0.longitude, zoneId: $0.zoneId,
                                          offsetLabel: $0.zoneId) }
        OrnatePanel {
            SettingsHeading(text: "Birth")
            BirthChips(date: b?.date, time: b?.time, timeUnknown: b != nil && b?.time == nil, place: current, placeQuery: b?.placeName,
                       placeCandidates: [],
                       onDate: { d in if let p = current { app.saveBirth(date: d, time: b?.time, place: p) } },
                       onTime: { t, _ in if let b, let p = current { app.saveBirth(date: b.date, time: t, place: p) } },
                       onPlace: { p in if let b { app.saveBirth(date: b.date, time: b.time, place: p) } })
            FinePrint(L10n.birthPrivacyNote).padding(.top, 8)
        }
    }
}

/// Eastern: blood type chips, Rh, and the L3 switch (only once a type is chosen).
private struct EasternPanel: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        let p = app.profile
        OrnatePanel {
            SettingsHeading(text: L10n.bloodTypeTitle)
            ChoiceChips(options: BloodType.allCases.map { ($0, "Type \($0.displayName)") }, selected: p.bloodType) { t in app.update { $0.bloodType = t } }
            if p.bloodType != nil {
                ChoiceChips(options: RhChoice.options, selected: RhChoice(p.rh)) { r in app.update { $0.rh = r.factor } }.padding(.top, 8)
                BloodShareSwitch()
                Button { app.removeBloodType() } label: {
                    Text("Remove blood type").typeStyle(.labelMedium).foregroundStyle(Palette.roseCopper).padding(.vertical, 10)
                }
                .buttonStyle(.plain)
            }
            FinePrint(L10n.bloodTypeNote)
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

// MARK: - Settings pages

private struct SettingsPageView: View {
    let page: SettingsPage
    let onBack: () -> Void
    let onBlocked: () -> Void

    var body: some View {
        StarfieldBackground(dim: 0.72) {
            VStack(spacing: 0) {
                PageHeader(title: page.title, onBack: onBack)
                ScrollView {
                    VStack(spacing: 14) {
                        switch page {
                        case .nearby: NearbySettings()
                        case .notifications: NotificationSettings()
                        case .privacy: PrivacyLegalSettings(onBlocked: onBlocked)
                        case .blocked: BlockedPeople()
                        }
                        Spacer().frame(height: 44)
                    }
                    .frame(maxWidth: 640)
                    .padding(.horizontal, 16)
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

/// Settings › Nearby (SPEC 5d.3.2): every Nearby toggle lives here.
private struct NearbySettings: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    @State private var details = false

    var body: some View {
        let p = app.profile
        let now = P2PLink.nowMs()
        OrnatePanel {
            GoldToggleRow(isOn: Binding(get: { p.nearbyEnabled }, set: { on in if on { app.requestNearbyOn() } else { app.disableNearby() } }),
                          title: L10n.nearbySwitchLabel, subtitle: p.nearbyEnabled ? L10n.nearbySwitchOnDesc : L10n.nearbySwitchOffDesc)
            if p.nearbyEnabled {
                HStack(spacing: 8) {
                    if p.isPaused(nowMs: now) {
                        GoldButton(AppText.nearbyResume) { app.resumeNearby() }
                    } else {
                        GhostButton(L10n.nearbyPause1h) { app.pauseNearby(until: P2PLink.nowMs() + 3_600_000) }
                        GhostButton(L10n.nearbyPauseTomorrow) { app.pauseNearby(until: NearbyText.tomorrowMorning()) }
                    }
                }
                .padding(.vertical, 6)
            }
            GoldToggleRow(isOn: Binding(get: { p.instagramShareDefault }, set: { on in app.update { $0.instagramShareDefault = on } }),
                          title: L10n.settingsInstagramDefault, subtitle: L10n.instagramNote)
            if p.bloodType != nil { BloodShareSwitch(title: L10n.settingsBloodShare, mirror: true) }
            NearbyConsentRow()
            FinePrint(L10n.nearbyIosBackgroundNote)
            SheetLink(text: L10n.nearbyDetails) { details = true }
        }
        OrnatePanel {
            SettingsHeading(text: AppText.settingsSnoozed)
            let snoozed = repo.snoozed
            if snoozed.isEmpty { FinePrint("Nobody is snoozed.") }
            ForEach(snoozed, id: \.fingerprint) { h in
                HStack {
                    Text("Snoozed until \(Self.dateText(h.until))").typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                    Spacer()
                    GhostButton("Unsnooze", fullWidth: false) { repo.unsnooze(h.fingerprint) }
                }
            }
        }
        .sheet(isPresented: $details) {
            ScrollView { NearbyDetails().padding(20) }
                .presentationDetents([.large])
                .presentationBackground(LinearGradient(colors: [Palette.inkViolet, Palette.midnight], startPoint: .top, endPoint: .bottom))
        }
    }

    static func dateText(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}

/// Settings › Notifications: daily reading (switch + time, default 08:00), matches, messages.
/// Each "on" asks for the system permission if needed.
private struct NotificationSettings: View {
    @EnvironmentObject var app: AppModel
    @State private var pickTime = false
    @State private var time = Date()

    var body: some View {
        let p = app.profile
        OrnatePanel {
            GoldToggleRow(isOn: Binding(get: { p.dailyReminder }, set: { on in
                Task {
                    let ok = on ? await Notifications.requestAuthorization() : true
                    app.update { $0.notificationsPrompted = true; $0.dailyReminder = on && ok }
                }
            }), title: AppText.settingsNotifDaily, subtitle: PickerDates.timeText(p.dailyReadingTime))
            if p.dailyReminder { GhostButton("Change time", fullWidth: false) { pickTime = true } }
            GoldToggleRow(isOn: Binding(get: { p.notifyMatches }, set: { on in toggle(on) { $0.notifyMatches = $1 } }),
                          title: AppText.settingsNotifMatches)
            GoldToggleRow(isOn: Binding(get: { p.notifyMessages }, set: { on in toggle(on) { $0.notifyMessages = $1 } }),
                          title: AppText.settingsNotifMessages)
        }
        .sheet(isPresented: $pickTime) {
            BirthTimeSheet(initial: p.dailyReadingTime, unknown: false) { t, _ in
                if let t { app.update { $0.dailyReadingTime = t } }
            }
        }
    }

    private func toggle(_ on: Bool, _ apply: @escaping (inout OwnerProfile, Bool) -> Void) {
        Task {
            let ok = on ? await Notifications.requestAuthorization() : true
            app.update { p in
                p.notificationsPrompted = true
                apply(&p, on && ok)
            }
        }
    }
}

/// Settings › Privacy & legal (SPEC 5d.1.3): one panel, plain rows, in this order.
private struct PrivacyLegalSettings: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    let onBlocked: () -> Void
    @State private var openDoc: LegalDoc?
    @State private var licences = false
    @State private var records = false
    @State private var confirmDelete = false
    @State private var draft: EmailDraft?
    @State private var exportURL: URL?
    @State private var message: String?

    var body: some View {
        let p = app.profile
        OrnatePanel(padding: 16) {
            // 1. Documents
            SettingsHeading(text: L10n.settingsLegalDocsTitle)
            ForEach(LegalDoc.settingsOrder) { doc in
                SettingsRow(title: doc.title, subtitle: app.bundledVersions[doc].map { "Version \($0)" }) { openDoc = doc }
            }
            SettingsRow(title: "Open-source licences") { licences = true }
            PanelRule()
            // 2. Nearby consent  3. Blood type in Nearby
            NearbyConsentRow()
            if p.bloodType != nil { BloodShareSwitch(title: L10n.settingsBloodShare, mirror: true) }
            PanelRule()
            // 4. Records  5. Arbitration  6. Blocked people
            SettingsRow(title: L10n.settingsLegalRecords) { records = true }
            SettingsRow(title: L10n.settingsArbitrationOptout, subtitle: L10n.settingsArbitrationOptoutHint) { arbitrationOptOut() }
            SettingsRow(title: L10n.settingsBlocked, action: onBlocked)
            PanelRule()
            // 7. Export / delete
            SettingsRow(title: "Export my data") { exportURL = exportData() }
            Button { confirmDelete = true } label: {
                Text(L10n.settingsDeleteData).typeStyle(.titleMedium).foregroundStyle(Palette.roseCopper)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 12).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            // 8. Notes
            FinePrint(L10n.settingsNoServerNote)
            FinePrint(L10n.format(L10n.settingsContact, AppInfo.supportEmail))
            FinePrint("Version \(AppInfo.version) (\(AppInfo.build))")
        }
        if let message {
            Text(message).typeStyle(.bodyMedium).foregroundStyle(Palette.goldPale).multilineTextAlignment(.center)
        }
        Color.clear.frame(height: 0)
            .sheet(item: $openDoc) { LegalDocSheet(doc: $0) }
            .sheet(isPresented: $licences) { LicencesSheet() }
            .sheet(isPresented: $records) { ConsentRecordsSheet().environmentObject(app) }
            .sheet(item: Binding(get: { exportURL.map(ShareItem.init) }, set: { exportURL = $0?.url })) { item in
                ShareSheet(items: [item.url])
            }
            .emailDraft($draft)
            .alert(L10n.settingsDeleteData, isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) { app.deleteEverything() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(L10n.settingsDeleteDataConfirm)
            }
    }

    /// Arbitration opt-out email (legal/IN_APP_CONSENT_COPY.md section 12), with the waiver acceptance date.
    private func arbitrationOptOut() {
        let accepted = app.consents.last { $0.key == LegalDoc.waiver.key && !$0.withdrawn }?.acceptedAt ?? "unknown"
        draft = EmailDraft(subject: "Arbitration Opt-Out",
                           body: "I opt out of the arbitration agreement.\n\nWaiver accepted: \(accepted)\nApp: \(AppInfo.version)")
    }

    /// This phone's data (profile, consents, Nearby friends and chats) as a JSON file to share.
    /// Friend secrets are key material, not personal data: they are left out.
    private func exportData() -> URL? {
        var nearby = repo.state
        for i in nearby.friends.indices { nearby.friends[i].friendSecret = "" }
        struct Export: Encodable {
            let exportedAt: String
            let app: String
            let profile: OwnerProfile
            let consents: [ConsentRecord]
            let nearby: P2PState
        }
        let export = Export(exportedAt: ConsentRecord.isoUTC(Date()), app: "\(AppInfo.name) \(AppInfo.version)", profile: app.profile,
                            consents: app.consents, nearby: nearby)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(export) else {
            message = "Export failed."
            return nil
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("conjunction-export.json")
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            return url
        } catch {
            message = "Export failed: \(error.localizedDescription)"
            return nil
        }
    }
}

/// Settings › Privacy & legal › Blocked people: blocked former friends and blocked cards, with Unblock.
private struct BlockedPeople: View {
    @ObservedObject private var repo = AppModel.shared.nearby

    var body: some View {
        let friends = repo.state.friends.filter(\.blocked)
        let strangers = repo.state.hidden.filter { $0.reason == "block" }
        OrnatePanel {
            if friends.isEmpty && strangers.isEmpty { FinePrint("You haven't blocked anyone.") }
            ForEach(friends) { f in
                HStack {
                    Text(f.firstName).typeStyle(.titleMedium).foregroundStyle(Palette.moonstone)
                    Spacer()
                    GhostButton(L10n.settingsUnblock, fullWidth: false) { repo.unblockFriend(f.id) }
                }
            }
            ForEach(Array(strangers.enumerated()), id: \.element.fingerprint) { i, h in
                HStack {
                    Text("Someone nearby (card \(i + 1))").typeStyle(.titleMedium).foregroundStyle(Palette.moonstone)
                    Spacer()
                    GhostButton(L10n.settingsUnblock, fullWidth: false) { repo.unblockStranger(h.fingerprint) }
                }
            }
            if !friends.isEmpty { FinePrint("Unblocking a former friend does not restore your chat; you would need to match again.") }
        }
    }
}

/// Settings → Legal → My acceptance records: the on-phone consent ledger.
private struct ConsentRecordsSheet: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        OrnateSheet(title: L10n.settingsLegalRecords, onClose: { dismiss() }) {
            if app.consents.isEmpty { FinePrint("No records yet.") }
            // By position: two records can share key, time (to the second) and state.
            ForEach(Array(app.consents.enumerated()), id: \.offset) { _, r in
                Text("\(r.key) v\(r.version)\(r.withdrawn ? " (withdrawn)" : "")\n\(r.acceptedAt) · \(r.textSha256.prefix(12))…"
                     + (r.docSha256.map { " · doc \($0.prefix(12))…" } ?? ""))
                    .typeStyle(.bodySmall).foregroundStyle(Palette.moonstone).padding(.vertical, 4)
            }
        }
    }
}

private struct LicencesSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        OrnateSheet(title: "Open-source licences", onClose: { dismiss() }) {
            ForEach([
                "Cinzel Decorative — Natanael Gama — SIL Open Font License 1.1",
                "Cinzel — The Cinzel Project Authors — SIL Open Font License 1.1",
                "Cormorant Garamond — The Cormorant Project Authors — SIL Open Font License 1.1",
                "swift-crypto — Apple Inc. — Apache License 2.0",
                "swift-asn1 — Apple Inc. — Apache License 2.0",
            ], id: \.self) { StarBullet(text: $0) }
            FinePrint("Full font licences are bundled in the app (OFL-*.txt).")
            ForEach(["OFL-CinzelDecorative", "OFL-Cinzel", "OFL-CormorantGaramond"], id: \.self) { name in
                if let url = Bundle.main.url(forResource: name, withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) {
                    Text(text).font(.system(size: 11, design: .monospaced)).foregroundStyle(Palette.moonstoneDim).padding(.top, 8)
                }
            }
        }
    }
}

private struct ShareItem: Identifiable {
    let url: URL
    var id: String { url.path }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
