import SwiftUI
import ConjunctionCore

/// Nearby (Android `NearbyScreen`): the switch (off by default) with a plain explanation, live status
/// (Bluetooth on/off, paused, how many Conjunction phones are close by — never a distance), pause
/// controls, the Matches inbox (cards stay 24 h) and the friends you matched with (open the chat).
struct NearbyScreen: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    let openChat: (ChatTarget) -> Void
    @State private var setup = false
    @State private var openCard: StoredEncounter?
    @State private var safetyTips = false
    @State private var error: String?

    var body: some View {
        if setup {
            VStack(spacing: 0) {
                HStack {
                    Button { setup = false } label: {
                        Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.goldPale)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Back")
                    Spacer()
                }
                .padding(.horizontal, 8)
                NearbyConsentContent(progress: nil) { setup = false }
            }
            .background(StarfieldBackground(dim: 0.74) { Color.clear })
        } else {
            main
        }
    }

    private var main: some View {
        let p = app.profile
        return TabPage(title: L10n.nearbyTitle, subtitle: AppText.tagline) {
            Image("popup_match_nearby").resizable().scaledToFit().frame(height: 170).accessibilityHidden(true)
            OrnatePanel {
                GoldToggleRow(isOn: Binding(get: { p.nearbyEnabled }, set: toggle), title: L10n.nearbySwitchLabel,
                              subtitle: p.nearbyEnabled ? L10n.nearbySwitchOnDesc : L10n.nearbySwitchOffDesc)
                Text(L10n.nearbyBody).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                if p.nearbyEnabled {
                    Text(statusLine(p)).typeStyle(.bodyMedium).foregroundStyle(Palette.goldPale).padding(.top, 8)
                    if repo.radio.unauthorized {
                        GhostButton("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    }
                    HStack(spacing: 8) {
                        if p.isPaused(nowMs: P2PLink.nowMs()) {
                            GoldButton(AppText.nearbyResume) { app.resumeNearby() }
                        } else {
                            GhostButton(L10n.nearbyPause1h) { app.pauseNearby(until: P2PLink.nowMs() + 3_600_000) }
                            GhostButton(L10n.nearbyPauseTomorrow) { app.pauseNearby(until: NearbyText.tomorrowMorning()) }
                        }
                    }
                    .padding(.top, 8)
                    FinePrint(L10n.nearbyIosBackgroundNote)
                    if let e = repo.radio.error { FinePrint(e) }
                }
                if !p.nearbyEligible {
                    FinePrint("Nearby needs your birth details and your dating & friends profile with its consent (Profile → Who you'd like to meet).")
                }
                if let error { Text(error).typeStyle(.bodyMedium).foregroundStyle(Palette.roseCopper) }
            }
            InboxList(openCard: { openCard = $0 }, openChat: openChat)
            GhostButton(L10n.safetyTipTitle) { safetyTips = true }
        }
        .fullScreenCover(item: $openCard) { e in
            MatchExperience(event: NearbyEvent(kind: e.exactTwin ? .exactTwin : e.birthdayTwin ? .birthdayTwin : .match,
                                               encounterId: e.fingerprint, friendId: nil, matchKind: e.kind, total: e.total),
                            skipAnimation: true,
                            onDismiss: { openCard = nil },
                            onOpenChat: { target in
                                openCard = nil
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { openChat(target) }
                            })
        }
        .sheet(isPresented: $safetyTips) { LegalDocSheet(doc: .safety) }
    }

    private func statusLine(_ p: OwnerProfile) -> String {
        let r = repo.radio
        if p.isPaused(nowMs: P2PLink.nowMs()) {
            let f = DateFormatter()
            f.timeStyle = .short
            f.dateStyle = .none
            return L10n.format(AppText.nearbyPausedUntil, f.string(from: Date(timeIntervalSince1970: Double(p.nearbyPausedUntil) / 1000)))
        }
        if r.unauthorized { return L10n.permBluetoothDenied }
        if r.unsupported { return "This device has no Bluetooth Low Energy, so Nearby can't run." }
        if !r.poweredOn { return L10n.nearbyBluetoothOff }
        if r.closeBy > 0 {
            return "Looking for conjunctions… \(r.closeBy) Conjunction \(r.closeBy == 1 ? "phone is" : "phones are") nearby."
        }
        return "Looking for conjunctions…"
    }

    private func toggle(_ on: Bool) {
        error = nil
        let p = app.profile
        if !on {
            app.disableNearby()
        } else if !p.nearbyConsent || !p.nearbyEligible || !repo.bluetoothAllowed {
            setup = true
        } else {
            do { try app.enableNearby() } catch { self.error = error.localizedDescription }
        }
    }
}

/// The Matches inbox (24 h) and the Conjunctions (friends) list.
private struct InboxList: View {
    @ObservedObject private var repo = AppModel.shared.nearby
    let openCard: (StoredEncounter) -> Void
    let openChat: (ChatTarget) -> Void

    var body: some View {
        let now = P2PLink.nowMs()
        let cards = repo.inbox + repo.waiting
        GoldText("Matches", style: .headlineSmall, heading: true)
        if cards.isEmpty {
            FinePrint("No cards waiting. When someone compatible is close by, their card appears here for 24 hours.")
        }
        ForEach(cards) { e in
            Button { openCard(e) } label: {
                OrnatePanel(padding: 14) {
                    HStack(spacing: 12) {
                        ScreenedPhoto(url: repo.photoURL(e.photoFile), screening: e.photoScreening, placeholder: Art.signKey(e.sun), compact: true)
                            .frame(width: 56, height: 56).clipShape(Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text((e.exactTwin ? "Exact twin · " : e.birthdayTwin ? "Birthday twin · " : "") + (e.firstName ?? "Someone nearby"))
                                .typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                            Text("\(CardText.sign(e.sun)) · \(e.total)% · \(e.kind == "dating" ? "dating" : "friends")")
                                .typeStyle(.bodySmall).foregroundStyle(Palette.moonstone)
                            let hours = max(0, (e.expiresAt - now) / 3_600_000)
                            Text(e.mySwipe == "right" ? "You said yes · waiting for them · \(hours) h left" : "\(hours) h left")
                                .typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim)
                        }
                        Spacer()
                        Text("›").typeStyle(.headlineMedium).foregroundStyle(Palette.gold)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityHint("Open match card")
        }
        GoldText("Conjunctions", style: .headlineSmall, heading: true)
        if repo.friends.isEmpty { FinePrint("When you both say yes, they appear here and you can chat.") }
        ForEach(repo.friends) { f in
            Button { openChat(ChatTarget(friendId: f.id)) } label: { FriendRow(friend: f) }
                .buttonStyle(.plain)
                .accessibilityHint("Open chat")
        }
    }
}

private struct FriendRow: View {
    @ObservedObject private var repo = AppModel.shared.nearby
    let friend: FriendSummary

    var body: some View {
        let f = friend.friend
        OrnatePanel(padding: 14) {
            HStack(spacing: 12) {
                ScreenedPhoto(url: repo.photoURL(f.photoFile), screening: f.photoScreening, placeholder: Art.signKey(f.sun), compact: true)
                    .frame(width: 56, height: 56).clipShape(Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(f.firstName).typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                    Text(([f.sun.map { "☉ \(CardText.sign($0))" }, f.moon.map { "☽ \(CardText.sign($0))" }].compactMap { $0 }
                          + [f.kind == "dating" ? "dating" : "friends"]).joined(separator: " · "))
                        .typeStyle(.bodySmall).foregroundStyle(Palette.moonstone)
                    Text(friend.inRange ? "Nearby now" : friend.queued > 0 ? "\(friend.queued) waiting to be delivered" : friend.lastMessage ?? "Say hello")
                        .typeStyle(.bodySmall).foregroundStyle(friend.inRange ? Palette.gold : Palette.moonstoneDim).lineLimit(1)
                }
                Spacer()
                if friend.unread > 0 {
                    Text("\(min(friend.unread, 99))").typeStyle(.labelMedium).foregroundStyle(Palette.midnight)
                        .frame(width: 26, height: 26).background(Circle().fill(Palette.gold))
                        .accessibilityLabel("\(friend.unread) unread")
                }
                Text("›").typeStyle(.headlineMedium).foregroundStyle(Palette.gold)
            }
        }
    }
}

/// Who a safety action is about: a stranger's match card (by fingerprint) or a friend.
struct SafetyTarget {
    let name: String
    let fingerprint: String?
    let friendId: String?
    let report: NearbyText.ReportSubject

    static func stranger(_ e: StoredEncounter) -> SafetyTarget {
        SafetyTarget(name: e.firstName ?? "this person", fingerprint: e.fingerprint, friendId: nil, report: .stranger(e))
    }

    static func friend(_ f: StoredFriend) -> SafetyTarget {
        SafetyTarget(name: f.firstName, fingerprint: nil, friendId: f.id, report: .friend(f))
    }
}

/// Block / report / snooze / unmatch for one person. Report = block + an email the person writes
/// and sends themselves (there is no server). [messages] are offered for the report from a chat.
/// [onDone] gets true when the person is now hidden.
struct SafetyActionsSheet: View {
    @ObservedObject private var repo = AppModel.shared.nearby
    let target: SafetyTarget
    var messages: [ChatLine]?
    let onDone: (Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var mode = "menu"
    @State private var reason: ReportReason?
    @State private var details = ""
    @State private var includeMessages = false
    @State private var draft: EmailDraft?
    @State private var note: String?

    private var title: String {
        switch mode {
        case "report": return L10n.format(L10n.reportTitle, target.name)
        case "block": return L10n.format(L10n.blockTitle, target.name)
        case "unmatch": return L10n.chatUnmatch
        default: return L10n.chatBlockReport
        }
    }

    var body: some View {
        OrnateSheet(title: title, onClose: { finish(false) }) {
            switch mode {
            case "menu":
                FinePrint(L10n.safetyTipEmergency)
                GhostButton(L10n.format(L10n.reportTitle, target.name), color: Palette.roseCopper) { mode = "report" }
                GhostButton(String(L10n.format(L10n.blockTitle, target.name).dropLast())) { mode = "block" }
                if let fp = target.fingerprint { GhostButton("Snooze for 7 days") { repo.snooze(fp); finish(true) } }
                if target.friendId != nil { GhostButton(L10n.chatUnmatch) { mode = "unmatch" } }
            case "block":
                Text(L10n.blockBody).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                GoldButton(L10n.blockConfirm) { block(); finish(true) }
            case "unmatch":
                Text(L10n.format(L10n.chatUnmatchConfirm, target.name)).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                GoldButton(L10n.chatUnmatch) {
                    if let id = target.friendId { repo.unmatch(id) }
                    finish(true)
                }
            default:
                Text(L10n.format(L10n.reportBody, target.name)).typeStyle(.bodySmall).foregroundStyle(Palette.moonstone)
                FinePrint(L10n.reportEmergency)
                Text(L10n.reportReasonLabel.uppercased()).typeStyle(.labelMedium).foregroundStyle(Palette.gold)
                ChoiceChips(options: ReportReason.allCases.map { ($0, $0.label) }, selected: reason) { reason = $0 }
                OrnateTextField(label: "Details (optional)", text: $details, multiline: true, minLines: 3, limit: 2000)
                if let messages, !messages.isEmpty {
                    GoldCheckRow(checked: $includeMessages, text: L10n.reportIncludeMessages)
                }
                GoldButton(L10n.reportOpenEmail, enabled: reason != nil) {
                    guard let r = reason else { return }
                    let body = NearbyText.reportBody(target.report, reason: r, description: details,
                                                     messages: includeMessages ? messages : nil,
                                                     appLine: "App: \(AppInfo.name) \(AppInfo.version) (\(AppInfo.build)), iOS \(UIDevice.current.systemVersion)")
                    block()
                    draft = EmailDraft(subject: NearbyText.reportSubjectLine(r, appName: AppInfo.name), body: body)
                }
            }
            if let note { Text(note).typeStyle(.bodySmall).foregroundStyle(Palette.roseCopper) }
        }
        .emailDraft($draft) { route in
            if route == .copied { note = L10n.format(L10n.reportNoEmailApp, AppInfo.supportEmail) } else { finish(true) }
        }
    }

    private func block() {
        if let id = target.friendId { repo.blockFriend(id) }
        if let fp = target.fingerprint { repo.blockStranger(fp) }
    }

    private func finish(_ hidden: Bool) {
        // After a report whose email could not open, the person is blocked already.
        onDone(hidden || (note != nil && mode == "report"))
        dismiss()
    }
}
