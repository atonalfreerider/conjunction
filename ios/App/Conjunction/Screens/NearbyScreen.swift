import SwiftUI
import ConjunctionCore

/// Where Nearby stands, for the header dot, the status card and the empty state (SPEC 5d.3).
enum NearbyStatus { case on, off, paused, bluetoothOff, denied, unsupported }

/// Nearby (SPEC 5d.3; Android `NearbyScreen`): only match cards. Header (title + status dot ->
/// Settings › Nearby), a status card when Nearby is not running and there are cards, then New
/// matches (swipe stack), Waiting and Conjunctions; or the empty state with its one button. No
/// switch, no paragraphs, no hero image.
struct NearbyScreen: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    @Environment(\.appNav) private var nav
    @State private var safety: SafetySheet?
    @State private var safetyCode: StoredFriend?
    @State private var tips = false

    /// A safety step for one person, opened from a ⋯ menu.
    struct SafetySheet: Identifiable {
        let target: SafetyTarget
        let mode: String
        var id: String { "\(target.fingerprint ?? target.friendId ?? "")|\(mode)" }
    }

    var body: some View {
        let p = app.profile
        let status = nearbyStatus(p)
        let pending = repo.inbox
        let waiting = repo.waiting
        let friends = repo.friends
        let empty = pending.isEmpty && waiting.isEmpty && friends.isEmpty
        StarfieldBackground {
            VStack(spacing: 0) {
                HStack {
                    GoldText(AppText.tabNearby, style: .headlineMedium, alignment: .leading, heading: true)
                    Spacer()
                    StatusDot(status: status) { nav.openSettings(.nearby) }
                }
                .padding(.horizontal, 20).padding(.vertical, 10)
                ScrollView {
                    VStack(spacing: 14) {
                        if empty {
                            EmptyState(line: emptyLine(status, p), button: actionLabel(status, card: false), action: action(status))
                        } else {
                            if status != .on { StatusCard(line: statusLine(status, p), button: actionLabel(status, card: true), action: action(status)) }
                            if !pending.isEmpty {
                                HStack {
                                    SectionLabel(text: AppText.nearbySectionNew)
                                    CardMenu { a in menu(a, .stranger(pending[0])) }
                                }
                                SwipeStack(cards: pending, photo: { repo.photoURL($0.photoFile) },
                                           onAnswer: { card, accept in repo.swipe(card.fingerprint, accept: accept) },
                                           onChart: { nav.openCompat($0.fingerprint) },
                                           onMenu: { card, a in menu(a, .stranger(card)) })
                            }
                            if !waiting.isEmpty {
                                SectionLabel(text: AppText.nearbySectionWaiting)
                                ForEach(waiting) { card in
                                    WaitingCard(card: card, photo: repo.photoURL(card.photoFile), onChart: { nav.openCompat(card.fingerprint) },
                                                onMenu: { a in menu(a, .stranger(card)) })
                                }
                            }
                            if !friends.isEmpty {
                                SectionLabel(text: AppText.nearbySectionConjunctions)
                                ForEach(friends) { f in
                                    FriendCard(friend: f, photo: repo.photoURL(f.friend.photoFile), shareOn: f.igShareOn(default: p.instagramShareDefault),
                                               onOpen: { nav.openChat(f.id) }, onChart: { nav.openCompat(f.id) },
                                               onMenu: { a in friendMenu(a, f, shareDefault: p.instagramShareDefault) })
                                }
                            }
                        }
                        // Room for the raised Nearby medallion over the end of the page.
                        Spacer().frame(height: 44)
                    }
                    .frame(maxWidth: 640)
                    .padding(.horizontal, 16)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .sheet(item: $safety) { s in
            SafetyActionsSheet(target: s.target, initialMode: s.mode) { _ in safety = nil }
        }
        .sheet(item: $safetyCode) { f in SafetyCodeSheet(name: f.firstName, code: f.safetyCode) }
        .sheet(isPresented: $tips) { SafetyTipsSheet() }
    }

    private func menu(_ a: CardMenuAction, _ target: SafetyTarget) {
        switch a {
        case .snooze: if let fp = target.fingerprint { repo.snooze(fp) }
        case .tips: tips = true
        case .block: safety = SafetySheet(target: target, mode: "block")
        case .report: safety = SafetySheet(target: target, mode: "report")
        }
    }

    private func friendMenu(_ a: FriendMenuAction, _ f: FriendSummary, shareDefault: Bool) {
        switch a {
        case .instagram: if let h = f.peerIg { Instagram.open(h) }
        case .shareInstagram: repo.setInstagramShare(f.id, on: !f.igShareOn(default: shareDefault))
        case .safetyCode: safetyCode = f.friend
        case .tips: tips = true
        case .unmatch: safety = SafetySheet(target: .friend(f.friend), mode: "unmatch")
        case .block: safety = SafetySheet(target: .friend(f.friend), mode: "block")
        case .report: safety = SafetySheet(target: .friend(f.friend), mode: "report")
        }
    }

    private func nearbyStatus(_ p: OwnerProfile) -> NearbyStatus {
        let r = repo.radio
        if r.unsupported { return .unsupported }
        if p.nearbyEnabled && (r.unauthorized || !repo.bluetoothAllowed) { return .denied }
        if p.nearbyEnabled && p.isPaused(nowMs: P2PLink.nowMs()) { return .paused }
        if p.nearbyEnabled && r.poweredOff { return .bluetoothOff }
        if p.nearbyEnabled { return .on }
        if app.bluetoothDenied { return .denied }
        return .off
    }

    private func pausedUntil(_ p: OwnerProfile) -> String {
        let f = DateFormatter()
        f.timeStyle = .short
        f.dateStyle = .none
        return f.string(from: Date(timeIntervalSince1970: Double(p.nearbyPausedUntil) / 1000))
    }

    private func statusLine(_ s: NearbyStatus, _ p: OwnerProfile) -> String {
        switch s {
        case .off: return AppText.nearbyOffLine
        case .paused: return L10n.format(AppText.nearbyPausedLine, pausedUntil(p))
        case .bluetoothOff: return L10n.nearbyBluetoothOff
        case .denied: return L10n.permBluetoothDenied
        case .unsupported: return "This device has no Bluetooth Low Energy, so Nearby can't run."
        case .on: return ""
        }
    }

    private func emptyLine(_ s: NearbyStatus, _ p: OwnerProfile) -> String {
        switch s {
        case .off: return AppText.nearbyEmptyOff
        case .on: return AppText.nearbyEmptyListening
        case .paused: return L10n.format(AppText.nearbyEmptyPaused, pausedUntil(p))
        default: return statusLine(s, p)
        }
    }

    /// The status card says just "Turn on" (the card already says Nearby is off); the empty state
    /// says "Turn on Nearby". Bluetooth off has no button: the line says to turn it on (Control
    /// Centre), and the app's Settings page can't do that.
    private func actionLabel(_ s: NearbyStatus, card: Bool) -> String? {
        switch s {
        case .off: return card ? L10n.nearbyTurnOn : AppText.nearbyTurnOnButton
        case .paused: return AppText.nearbyResume
        case .denied: return AppText.nearbyOpenSettings
        default: return nil
        }
    }

    private func action(_ s: NearbyStatus) -> (() -> Void)? {
        switch s {
        case .off: return { app.requestNearbyOn() }
        case .paused: return { app.resumeNearby() }
        // Permission missing: the app's Settings page is where it is fixed.
        case .denied:
            return {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
        default: return nil
        }
    }
}

private struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).typeStyle(.titleSmall).foregroundStyle(Palette.gold)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 4)
            .accessibilityAddTraits(.isHeader)
    }
}

/// 10 pt dot: gold = on, grey = off / paused, rose-copper = Bluetooth off or permission missing.
private struct StatusDot: View {
    let status: NearbyStatus
    let action: () -> Void

    var body: some View {
        let color: Color = {
            switch status {
            case .on: return Palette.gold
            case .bluetoothOff, .denied: return Palette.roseCopper
            default: return Palette.moonstoneDim
            }
        }()
        Button(action: action) {
            Circle().fill(color).frame(width: 10, height: 10).frame(width: 48, height: 48).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Nearby status: \(spoken). Opens Nearby settings.")
    }

    private var spoken: String {
        switch status {
        case .on: return "on"
        case .off: return "off"
        case .paused: return "paused"
        case .bluetoothOff: return "Bluetooth is off"
        case .denied: return "Bluetooth permission is off"
        case .unsupported: return "not available"
        }
    }
}

private struct EmptyState: View {
    let line: String
    let button: String?
    let action: (() -> Void)?

    var body: some View {
        VStack(spacing: 16) {
            Image("popup_match_nearby").resizable().scaledToFit().frame(width: 240, height: 240).opacity(0.55)
                .accessibilityHidden(true)
            GoldText(line, style: .titleLarge)
            if let button, let action { GoldButton(button, action: action).frame(maxWidth: 360) }
        }
        .padding(.top, 24)
        .frame(maxWidth: .infinity)
    }
}

private struct StatusCard: View {
    let line: String
    let button: String?
    let action: (() -> Void)?

    var body: some View {
        OrnatePanel(padding: 16) {
            HStack(spacing: 10) {
                Text(line).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).frame(maxWidth: .infinity, alignment: .leading)
                if let button, let action { GhostButton(button, fullWidth: false, action: action) }
            }
        }
    }
}

/// New matches: the top card is interactive, the next one peeks out behind it.
private struct SwipeStack: View {
    let cards: [StoredEncounter]
    let photo: (StoredEncounter) -> URL?
    let onAnswer: (StoredEncounter, Bool) -> Void
    let onChart: (StoredEncounter) -> Void
    let onMenu: (StoredEncounter, CardMenuAction) -> Void

    var body: some View {
        let top = cards[0]
        ZStack(alignment: .top) {
            if cards.count > 1 {
                let next = cards[1]
                MatchCardFace(card: next, photo: photo(next), photoSize: 120)
                    .padding(28)
                    .frame(maxWidth: 420)
                    .background(CutCornerShape(cut: 14).fill(Palette.inkViolet))
                    .overlay(CutCornerShape(cut: 14).stroke(GoldBrush.leaf, lineWidth: 1))
                    .padding(.horizontal, 30)
                    .opacity(0.9)
                    .accessibilityHidden(true)
            }
            SwipeableMatchCard(card: top, photo: photo(top), onAnswer: { onAnswer(top, $0) }, onChart: { onChart(top) })
                .id(top.fingerprint)
                .padding(.top, cards.count > 1 ? 22 : 0)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct WaitingCard: View {
    let card: StoredEncounter
    let photo: URL?
    let onChart: () -> Void
    let onMenu: (CardMenuAction) -> Void

    var body: some View {
        OrnatePanel(padding: 14) {
            HStack(spacing: 10) {
                ScreenedPhoto(url: photo, screening: card.photoScreening, placeholder: Art.signKey(card.sun), compact: true)
                    .frame(width: 56, height: 56).clipShape(Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(card.firstName ?? "Someone nearby").typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                    Text(L10n.format(AppText.nearbyWaitingFor, card.firstName ?? "them")).typeStyle(.bodySmall)
                        .foregroundStyle(Palette.moonstoneDim)
                }
                Spacer(minLength: 4)
                ScoreMedallion(total: card.total, size: 44)
                ChartChip(action: onChart)
                CardMenu(onAction: onMenu)
            }
        }
    }
}

/// A mutual match: tap -> chat; ☌ -> Compatibility; Instagram chip when they shared one.
private struct FriendCard: View {
    let friend: FriendSummary
    let photo: URL?
    let shareOn: Bool
    let onOpen: () -> Void
    let onChart: () -> Void
    let onMenu: (FriendMenuAction) -> Void

    var body: some View {
        let f = friend.friend
        OrnatePanel(padding: 14) {
            HStack(spacing: 10) {
                Button(action: onOpen) {
                    HStack(spacing: 12) {
                        ScreenedPhoto(url: photo, screening: f.photoScreening, placeholder: Art.signKey(f.sun), compact: true)
                            .frame(width: 56, height: 56).clipShape(Circle())
                            .overlay(alignment: .topTrailing) {
                                if friend.unread > 0 {
                                    Circle().fill(Palette.gold).frame(width: 14, height: 14)
                                        .overlay(Circle().stroke(Palette.midnight, lineWidth: 1))
                                        .accessibilityLabel("\(friend.unread) unread")
                                }
                            }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.firstName).typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                            Text((friend.queued > 0 ? "⏱ " : "") + (friend.lastMessage ?? AppText.nearbySayHi))
                                .typeStyle(.bodySmall).foregroundStyle(friend.unread > 0 ? Palette.moonstone : Palette.moonstoneDim).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("Open chat")
                if let total = friend.total { ScoreMedallion(total: total, size: 44) }
                ChartChip(action: onChart)
                FriendMenu(friend: friend, shareOn: shareOn, onAction: onMenu)
            }
            if let h = friend.peerIg { InstagramChip(handle: h).padding(.leading, 68) }
        }
    }
}

/// The safety code to compare in person (friend ⋯ and chat ⋯).
struct SafetyCodeSheet: View {
    let name: String
    let code: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        OrnateSheet(title: AppText.menuSafetyCode, onClose: { dismiss() }) {
            Text(code).typeStyle(.headlineSmall).foregroundStyle(Palette.goldPale)
            FinePrint("When you meet, check that \(name)'s phone shows the same digits. If they differ, someone may have interfered when you matched: block and report.")
        }
        .presentationDetents([.medium])
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
    /// "menu", "block", "report" or "unmatch" (the ⋯ menus open the right step directly).
    var initialMode = "menu"
    let onDone: (Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var mode = "menu"
    @State private var started = false
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
                if let fp = target.fingerprint { GhostButton(AppText.menuSnooze) { repo.snooze(fp); finish(true) } }
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
        .onAppear {
            if !started {
                started = true
                mode = initialMode
            }
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
