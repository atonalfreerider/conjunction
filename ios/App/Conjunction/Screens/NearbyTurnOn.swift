import SwiftUI
import ConjunctionCore

// The one Nearby turn-on flow (SPEC 5d.3.1; Android ui/screens/NearbyTurnOn.kt), used by the empty
// state, the status card and the Settings switch through `AppModel.requestNearbyOn()`: the L2
// sheet when the consent or the dating fields / first name are missing -> record the consents ->
// the system Bluetooth prompt (+ notifications if never asked) -> Nearby starts. "Not now"
// records nothing.

/// L2: the Nearby consent sheet (prominent disclosure + GDPR Art. 9 explicit consent). Dating chips
/// and the first name appear above the points when missing.
struct NearbyConsentSheet: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var ticked = false
    @State private var details = false
    @State private var safety = false
    @State private var loaded = false
    @State private var needsFields = false
    @State private var needsName = false
    @State private var name = ""
    @State private var gender: Gender?
    @State private var seeking: Seeking?
    @State private var status: RelationshipStatus?
    @State private var lookingFor: LookingFor?

    private var fieldsOk: Bool {
        gender != nil && seeking != nil && status != nil && lookingFor != nil
            && (!needsName || !name.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if details {
                        NearbyDetails { details = false }
                    } else {
                        GoldText(L10n.nearbyTitle, style: .headlineSmall, alignment: .leading, heading: true)
                        if needsName { OrnateTextField(label: AppText.onbFirstName, text: $name, limit: 40) }
                        if needsFields { DatingFields(gender: $gender, seeking: $seeking, status: $status, lookingFor: $lookingFor) }
                        ForEach(NearbySheet.points, id: \.self) { StarBullet(text: $0) }
                        HStack(spacing: 18) {
                            SheetLink(text: L10n.nearbyDetails) { details = true }
                            SheetLink(text: L10n.nearbySafetyLink) { safety = true }
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.top, 26).padding(.bottom, 8)
            }
            // The checkbox and the actions are pinned under the scrolling points, so Turn on / Not
            // now are always on screen.
            if !details {
                VStack(alignment: .leading, spacing: 8) {
                    GoldCheckRow(checked: $ticked, text: L10n.nearbyConsentCheckbox)
                    HStack(spacing: 10) {
                        GhostButton(L10n.nearbyNotNow) { dismiss() }
                        GoldButton(L10n.nearbyTurnOn, enabled: ticked && fieldsOk) {
                            guard let gender, let seeking, let status, let lookingFor else { return }
                            app.giveNearbyConsent(
                                firstName: needsName ? name.trimmingCharacters(in: .whitespacesAndNewlines) : nil,
                                dating: DatingProfile(gender: gender, seeking: seeking, relationshipStatus: status, lookingFor: lookingFor))
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.top, 4).padding(.bottom, 12)
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .presentationBackground(LinearGradient(colors: [Palette.inkViolet, Palette.midnight], startPoint: .top, endPoint: .bottom))
        .onAppear {
            guard !loaded else { return }
            loaded = true
            let p = app.profile
            needsFields = !p.datingFieldsComplete
            needsName = p.firstName.isEmpty
            name = p.displayName
            gender = p.gender
            seeking = p.seeking
            status = p.relationshipStatus
            lookingFor = p.lookingFor
        }
        .sheet(isPresented: $safety) { SafetyTipsSheet() }
    }
}

/// The Details layer: the full points (behind "Details"; also in Settings › Nearby).
struct NearbyDetails: View {
    var onBack: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            GoldText(L10n.nearbyDetailsTitle, style: .headlineSmall, alignment: .leading, heading: true)
            ForEach([L10n.nearbyPointBluetooth, L10n.nearbyPointHello, L10n.nearbyPointCard, L10n.nearbyPointKeep,
                     L10n.nearbyPointSwipe, L10n.nearbyPointDeviceName, L10n.nearbyPointSafety], id: \.self) {
                StarBullet(text: $0)
            }
            // Texas Bus. & Com. Code 106.004: bold capitals, 12 pt or larger.
            Text(L10n.nearbyPointNoChecks).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.goldPale)
                .fixedSize(horizontal: false, vertical: true)
            StarBullet(text: L10n.nearbyPointControl)
            FinePrint(L10n.nearbyIosBackgroundNote)
            if let onBack { GhostButton(AppText.back, action: onBack) }
        }
    }
}

/// Safety Tips reader; "Got it" records SAFETY_TIPS at its version.
struct SafetyTipsSheet: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        LegalDocSheet(doc: .safety) { text in
            app.recordDocument(text)
            app.update { $0.safetyTipsSeen = true }
        }
    }
}

/// "Nearby consent: Given 4 Nov 2026 / Not given" with Withdraw -> confirm (shown in Settings ›
/// Nearby and Privacy & legal). Withdrawing is one tap and one confirm.
struct NearbyConsentRow: View {
    @EnvironmentObject var app: AppModel
    @State private var confirm = false

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.settingsNearbyConsent).typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                Text(app.nearbyConsentDate.map { L10n.format(L10n.settingsNearbyConsentGiven, $0) } ?? L10n.settingsNearbyConsentNotGiven)
                    .typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim)
            }
            Spacer(minLength: 8)
            if app.profile.nearbyConsentGiven {
                GhostButton(L10n.settingsWithdrawNearbyConsent, color: Palette.roseCopper, fullWidth: false) { confirm = true }
            }
        }
        .padding(.vertical, 4)
        .alert(L10n.settingsNearbyConsent, isPresented: $confirm) {
            Button(L10n.settingsWithdrawNearbyConsent, role: .destructive) { app.withdrawNearbyConsent() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(L10n.settingsWithdrawNearbyConsentConfirm)
        }
    }
}

/// L3 / settings_blood_share: one switch, off by default; records consent on and withdrawal off.
/// L3 / settings_blood_share. The full consent sentence shows only at L3 (Profile › Blood type).
/// [mirror] = a Settings row: short title, no sentence; switching it on asks with the L3 sentence
/// first (same record).
struct BloodShareSwitch: View {
    @EnvironmentObject var app: AppModel
    var title: String = L10n.bloodTypeShareSwitch
    var mirror = false
    @State private var confirm = false

    var body: some View {
        GoldToggleRow(isOn: Binding(get: { app.profile.bloodForMatching }, set: { on in
                          if on && mirror { confirm = true } else { app.setBloodShare(on) }
                      }),
                      title: title, subtitle: mirror ? nil : L10n.bloodTypeShareSub)
            .alert(L10n.bloodTypeShareSwitch, isPresented: $confirm) {
                Button(L10n.nearbyTurnOn) { app.setBloodShare(true) }
                Button(L10n.nearbyNotNow, role: .cancel) {}
            } message: {
                Text(L10n.bloodTypeShareSub)
            }
    }
}
