import SwiftUI
import PhotosUI
import ConjunctionCore

/// Profile & settings (Android `ProfileScreen`): photo, name, bio, birth details, blood type,
/// dating profile and its consent, notifications, Nearby pause, blocked people, legal documents,
/// data export, delete all my data, licences.
struct ProfileScreen: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    @State private var editingBirth = false
    @State private var openDoc: LegalDoc?
    @State private var confirmDelete = false
    @State private var confirmWithdraw = false
    @State private var message: String?
    @State private var showLicences = false
    @State private var showRecords = false
    @State private var showBlocked = false
    @State private var pick: PhotosPickerItem?
    @State private var name = ""
    @State private var bio = ""
    @State private var draft: EmailDraft?
    @State private var exportURL: URL?

    var body: some View {
        if editingBirth {
            BirthEditor { editingBirth = false }
        } else {
            page
        }
    }

    private var page: some View {
        let p = app.profile
        return TabPage(title: AppText.tabProfile, subtitle: p.displayName.isEmpty ? nil : p.displayName) {
            photoPanel(p)
            OrnatePanel {
                OrnateTextField(label: "First name", text: $name, limit: 40)
                OrnateTextField(label: "Short bio", text: $bio, multiline: true, minLines: 3,
                                supporting: "Your first line goes on your card to people you match with nearby.", limit: 500)
                GoldButton("Save", enabled: name != p.displayName || bio != p.bio) {
                    app.update {
                        $0.displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        $0.bio = bio.trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                    message = "Saved on this phone."
                }
            }
            OrnatePanel {
                SettingsHeading(text: "Birth details")
                if let b = p.birth {
                    Text("\(PickerDates.longText(b.date)) · \(b.time.map(PickerDates.timeText) ?? "time unknown")\n\(b.placeName) (\(b.zoneId))")
                        .typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                } else {
                    Text("Not set").typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                }
                FinePrint(L10n.birthPrivacyNote)
                GhostButton("Edit birth details") { editingBirth = true }.padding(.top, 6)
            }
            bloodPanel(p)
            DatingSettings(onWithdraw: { confirmWithdraw = true })
            OrnatePanel {
                SettingsHeading(text: "Notifications & Nearby")
                GoldToggleRow(isOn: Binding(get: { p.dailyReminder }, set: { on in
                    Task {
                        let ok = on ? await Notifications.requestAuthorization() : true
                        app.update { $0.dailyReminder = on && ok }
                    }
                }), title: "Morning reminder", subtitle: "A notification at 8:00 when today's reading is ready.")
                if p.nearbyEnabled {
                    HStack(spacing: 8) {
                        GhostButton(L10n.nearbyPause1h) { app.pauseNearby(until: P2PLink.nowMs() + 3_600_000) }
                        GhostButton("Turn Nearby off") { app.disableNearby() }
                    }
                }
                GhostButton("Blocked people (\(repo.state.friends.filter(\.blocked).count + repo.state.hidden.filter { $0.reason == "block" }.count))") {
                    showBlocked = true
                }
            }
            OrnatePanel {
                SettingsHeading(text: "Legal")
                ForEach(LegalDoc.allCases) { doc in GhostButton(doc.title) { openDoc = doc }.padding(.vertical, 3) }
                GhostButton("Open-source licences") { showLicences = true }.padding(.vertical, 3)
                GhostButton(L10n.settingsLegalRecords) { showRecords = true }.padding(.vertical, 3)
                Button { arbitrationOptOut() } label: { FinePrint(L10n.settingsArbitrationOptoutHint) }.buttonStyle(.plain)
                FinePrint(L10n.format(L10n.settingsContact, AppInfo.supportEmail))
            }
            OrnatePanel {
                SettingsHeading(text: "Your data")
                FinePrint(L10n.settingsNoServerNote)
                GhostButton("Export my data") { exportURL = exportData() }
                GhostButton(L10n.settingsDeleteData, color: Palette.roseCopper) { confirmDelete = true }.padding(.top, 8)
                FinePrint("Version \(AppInfo.version) (\(AppInfo.build))").padding(.top, 8)
            }
            if let message {
                Text(message).typeStyle(.bodyMedium).foregroundStyle(Palette.goldPale).multilineTextAlignment(.center)
            }
        }
        .onAppear {
            name = p.displayName
            bio = p.bio
        }
        .onChange(of: pick) { _, item in
            guard let item else { return }
            pick = nil
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) { message = app.setPhoto(data) } else { message = "The photo could not be used." }
            }
        }
        .sheet(item: $openDoc) { LegalDocSheet(doc: $0) }
        .sheet(isPresented: $showLicences) { LicencesSheet() }
        .sheet(isPresented: $showRecords) { ConsentRecordsSheet() }
        .sheet(isPresented: $showBlocked) { BlockedPeopleSheet() }
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
        .alert(L10n.settingsWithdrawOrientation, isPresented: $confirmWithdraw) {
            Button("Withdraw", role: .destructive) {
                app.record(ConsentKeys.orientationData, version: ConsentKeys.copyVersion, text: L10n.orientationConsentCheckbox, withdrawn: true)
                app.update { $0.nearbyEnabled = false; $0.dating = nil; $0.orientationConsent = false }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(L10n.settingsWithdrawOrientationConfirm)
        }
    }

    private func photoPanel(_ p: OwnerProfile) -> some View {
        OrnatePanel {
            HStack(spacing: 14) {
                ProfilePhoto(url: app.ownerPhotoURL, placeholder: app.charts.map { Art.sign($0.natal.sunSign) } ?? "brand_mark")
                    .frame(width: 96, height: 96).clipShape(Circle()).overlay(Circle().stroke(GoldBrush.leaf, lineWidth: 2))
                    .accessibilityLabel("Your profile photo")
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.photoTitle.uppercased()).typeStyle(.labelMedium).foregroundStyle(Palette.gold)
                    Text(p.photoFile != nil ? "Saved on this phone. Sent (640 px) only to people you match with nearby."
                         : "No photo yet: your sun-sign art is shown instead.")
                        .typeStyle(.bodySmall).foregroundStyle(Palette.moonstone)
                }
            }
            FinePrint(L10n.photoBody)
            FinePrint(L10n.photoTip)
            HStack(spacing: 8) {
                PhotosPicker(selection: $pick, matching: .images, photoLibrary: .shared()) {
                    Text((p.photoFile == nil ? L10n.photoAdd : L10n.photoChange).uppercased()).typeStyle(.labelMedium)
                        .foregroundStyle(Palette.goldPale).frame(maxWidth: .infinity, minHeight: 48)
                        .background(Palette.midnight.opacity(0.55).clipShape(CutCornerShape(cut: 10)))
                        .overlay(CutCornerShape(cut: 10).stroke(GoldBrush.leaf, lineWidth: 1))
                }
                if p.photoFile != nil { GhostButton(L10n.photoRemove, color: Palette.roseCopper) { app.removePhoto() } }
            }
            .padding(.top, 8)
        }
    }

    private func bloodPanel(_ p: OwnerProfile) -> some View {
        OrnatePanel {
            SettingsHeading(text: L10n.bloodTypeTitle)
            ChoiceChips(options: BloodType.allCases.map { ($0, "Type \($0.displayName)") }, selected: p.bloodType) { t in app.update { $0.bloodType = t } }
            if p.bloodType != nil {
                ChoiceChips(options: RhChoice.options, selected: RhChoice(p.rh)) { r in app.update { $0.rh = r.factor } }.padding(.top, 8)
                GoldCheckRow(checked: Binding(get: { p.bloodForMatching }, set: { on in
                    app.record(ConsentKeys.bloodTypeMatching, version: ConsentKeys.copyVersion, text: L10n.bloodTypeConsentCheckbox, withdrawn: !on)
                    app.update { $0.bloodForMatching = on }
                }), text: L10n.bloodTypeConsentCheckbox, note: L10n.bloodTypeConsentNote)
                if p.bloodForMatching {
                    GhostButton(L10n.settingsRemoveBloodType) {
                        app.record(ConsentKeys.bloodTypeMatching, version: ConsentKeys.copyVersion, text: L10n.bloodTypeConsentCheckbox, withdrawn: true)
                        app.update { $0.bloodForMatching = false }
                    }
                }
                GhostButton("Remove blood type", color: Palette.roseCopper) {
                    if p.bloodForMatching {
                        app.record(ConsentKeys.bloodTypeMatching, version: ConsentKeys.copyVersion, text: L10n.bloodTypeConsentCheckbox, withdrawn: true)
                    }
                    app.update { $0.bloodType = nil; $0.rh = nil; $0.bloodForMatching = false }
                }
            }
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

private struct SettingsHeading: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).typeStyle(.titleMedium).foregroundStyle(Palette.gold).padding(.bottom, 6).accessibilityAddTraits(.isHeader)
    }
}

private struct DatingSettings: View {
    @EnvironmentObject var app: AppModel
    let onWithdraw: () -> Void
    @State private var gender: Gender?
    @State private var seeking: Seeking?
    @State private var status: RelationshipStatus?
    @State private var lookingFor: LookingFor?
    @State private var consent = false
    @State private var honest = false
    @State private var saved: String?

    var body: some View {
        let p = app.profile
        let complete = gender != nil && seeking != nil && status != nil && lookingFor != nil &&
            (p.orientationConsent || consent) && (p.honestyConfirmed || honest)
        OrnatePanel {
            SettingsHeading(text: L10n.datingProfileTitle)
            DatingFields(gender: $gender, seeking: $seeking, status: $status, lookingFor: $lookingFor)
            if !p.orientationConsent {
                Text(L10n.orientationConsentBody).typeStyle(.bodySmall).foregroundStyle(Palette.moonstone).padding(.top, 8)
                GoldCheckRow(checked: $consent, text: L10n.orientationConsentCheckbox, note: L10n.orientationConsentWithdrawNote)
            }
            if !p.honestyConfirmed { GoldCheckRow(checked: $honest, text: L10n.honestyCheckbox) }
            GoldButton("Save", enabled: complete) {
                if !p.orientationConsent { app.record(ConsentKeys.orientationData, version: ConsentKeys.copyVersion, text: L10n.orientationConsentCheckbox) }
                if !p.honestyConfirmed { app.record(ConsentKeys.profileHonesty, version: ConsentKeys.copyVersion, text: L10n.honestyCheckbox) }
                app.update {
                    $0.dating = DatingProfile(gender: gender!, seeking: seeking!, relationshipStatus: status!, lookingFor: lookingFor!)
                    $0.orientationConsent = true
                    $0.honestyConfirmed = true
                }
                saved = "Saved on this phone."
            }
            .padding(.top, 8)
            if let saved { FinePrint(saved) }
            if p.orientationConsent {
                GhostButton(L10n.settingsWithdrawOrientation, color: Palette.roseCopper, action: onWithdraw).padding(.top, 8)
            }
        }
        .onAppear(perform: load)
        .onChange(of: app.profile.dating) { _, _ in load() }
    }

    private func load() {
        let d = app.profile.dating
        gender = d?.gender
        seeking = d?.seeking
        status = d?.relationshipStatus
        lookingFor = d?.lookingFor
    }
}

/// Settings → Blocked people: unblock strangers (by card) and blocked friends.
private struct BlockedPeopleSheet: View {
    @ObservedObject private var repo = AppModel.shared.nearby
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let friends = repo.state.friends.filter(\.blocked)
        let strangers = repo.state.hidden.filter { $0.reason == "block" }
        OrnateSheet(title: "Blocked people", onClose: { dismiss() }) {
            if friends.isEmpty && strangers.isEmpty { FinePrint("Nobody is blocked.") }
            ForEach(friends) { f in
                HStack {
                    Text(f.firstName).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone)
                    Spacer()
                    GhostButton("Unblock", fullWidth: false) { repo.unblockFriend(f.id) }
                }
            }
            ForEach(strangers, id: \.fingerprint) { h in
                HStack {
                    Text("Someone you blocked from a match card").typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                    Spacer()
                    GhostButton("Unblock", fullWidth: false) { repo.unblockStranger(h.fingerprint) }
                }
            }
            FinePrint("Unblocking a former friend does not restore your chat; you would need to match again.")
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
            ForEach(app.consents) { r in
                Text("\(r.key) v\(r.version)\(r.withdrawn ? " (withdrawn)" : "")\n\(r.acceptedAt) · \(r.textSha256.prefix(12))…")
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
