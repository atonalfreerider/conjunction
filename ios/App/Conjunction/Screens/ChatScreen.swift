import SwiftUI
import ConjunctionCore

/// Chat with a friend (mutual match), SPEC 5c: end-to-end encrypted over Bluetooth, stored only on
/// the two phones. Messages to a friend who is out of range are queued and delivered the next time
/// the phones are near each other, and the bubble says so. Incoming messages are screened on this
/// phone and hidden behind "Show anyway" when they look harmful. Header (SPEC 5d.8): Back · photo
/// + first name (-> Compatibility) · score chip (-> Compatibility) · ⋯ (Instagram, share switch,
/// safety code, Safety Tips, unmatch, block, report).
struct ChatScreen: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    let target: ChatTarget
    let onClose: () -> Void
    @State private var draft = ""
    @State private var safety: String?
    @State private var tips = false
    @State private var verify = false
    @State private var compat: CompatTarget?

    var body: some View {
        let summary = repo.friend(target.friendId)
        let f = summary?.friend
        let name = f?.firstName ?? "Your match"
        let messages = repo.messages(target.friendId)
        StarfieldBackground(dim: 0.7) {
            VStack(spacing: 0) {
                header(summary, name: name)
                Rectangle().fill(GoldBrush.leafHorizontal).frame(height: 1).padding(.horizontal, 16)
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            // One centred line at the top of a new chat (SPEC 5d.8).
                            if messages.isEmpty {
                                Text(L10n.chatFirstOpenNotice).typeStyle(.bodySmall, italic: true).foregroundStyle(Palette.moonstoneDim)
                                    .multilineTextAlignment(.center).padding(.vertical, 12).padding(.horizontal, 24)
                                    .frame(maxWidth: .infinity)
                            }
                            ForEach(messages) { m in bubble(m, name: name).id(m.id) }
                        }
                        .padding(16)
                    }
                    .scrollDismissesKeyboard(.interactively)
                    .onAppear {
                        if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                        repo.markSeen(target.friendId)
                    }
                    .onChange(of: messages.count) { _, _ in
                        if let last = messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                        repo.markSeen(target.friendId)
                    }
                }
                HStack(alignment: .bottom, spacing: 8) {
                    OrnateTextField(label: "Message", text: $draft, multiline: true, limit: 2000)
                    Button {
                        repo.sendChat(target.friendId, draft)
                        draft = ""
                    } label: {
                        Image(systemName: "paperplane.fill").font(.system(size: 20))
                            .foregroundStyle(canSend(f) ? Palette.gold : Palette.moonstoneDim)
                            .frame(width: 48, height: 48)
                    }
                    .disabled(!canSend(f))
                    .accessibilityLabel("Send message")
                }
                .padding(12)
            }
        }
        .onAppear { repo.openChatFriendId = target.friendId }
        .onDisappear { if repo.openChatFriendId == target.friendId { repo.openChatFriendId = nil } }
        .sheet(item: Binding(get: { safety.map { CompatTarget(key: $0) } }, set: { safety = $0?.key })) { mode in
            if let f {
                SafetyActionsSheet(target: .friend(f), messages: messages, initialMode: mode.key) { closed in if closed { onClose() } }
            }
        }
        .sheet(isPresented: $tips) { SafetyTipsSheet() }
        .sheet(isPresented: $verify) { SafetyCodeSheet(name: name, code: f?.safetyCode ?? "") }
        .fullScreenCover(item: $compat) { t in CompatibilityScreen(key: t.key, onClose: { compat = nil }) }
    }

    private func canSend(_ f: StoredFriend?) -> Bool { f != nil && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private func header(_ s: FriendSummary?, name: String) -> some View {
        let f = s?.friend
        let shareDefault = app.profile.instagramShareDefault
        return HStack(spacing: 8) {
            Button(action: onClose) {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.goldPale).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Back")
            Button { compat = CompatTarget(key: target.friendId) } label: {
                HStack(spacing: 10) {
                    ScreenedPhoto(url: repo.photoURL(f?.photoFile), screening: f?.photoScreening ?? .safe, placeholder: Art.signKey(f?.sun), compact: true)
                        .frame(width: 44, height: 44).clipShape(Circle()).overlay(Circle().stroke(GoldBrush.leaf, lineWidth: 1))
                    VStack(alignment: .leading, spacing: 0) {
                        GoldText(name, style: .titleMedium, alignment: .leading)
                        Text(s?.inRange == true ? "Nearby now" : CardText.signsLine(sun: f?.sun, moon: f?.moon, rising: nil))
                            .typeStyle(.bodySmall).foregroundStyle(s?.inRange == true ? Palette.gold : Palette.moonstoneDim)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Compatibility chart")
            if let total = s?.total {
                Button { compat = CompatTarget(key: target.friendId) } label: {
                    Text("\(total) " + ChartWheelGeometry.textGlyph("☌")).typeStyle(.titleSmall).foregroundStyle(Palette.midnight)
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 12).fill(GoldBrush.leaf))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(total) percent, compatibility chart")
            }
            if let s {
                FriendMenu(friend: s, shareOn: s.igShareOn(default: shareDefault), showInstagram: true, showTips: true) { a in
                    switch a {
                    case .instagram: if let h = s.peerIg { Instagram.open(h) }
                    case .shareInstagram: repo.setInstagramShare(s.id, on: !s.igShareOn(default: shareDefault))
                    case .safetyCode: verify = true
                    case .tips: tips = true
                    case .unmatch: safety = "unmatch"
                    case .block: safety = "block"
                    case .report: safety = "report"
                    }
                }
            }
        }
        .padding(8)
    }

    private static let timeFormat: DateFormatter = {
        let f = DateFormatter()
        f.timeStyle = .short
        f.dateStyle = .none
        return f
    }()

    @ViewBuilder
    private func bubble(_ m: ChatLine, name: String) -> some View {
        let time = Self.timeFormat.string(from: Date(timeIntervalSince1970: Double(m.ts) / 1000))
        if m.mine {
            let status: String? = {
                switch m.status {
                case .queued: return L10n.format(L10n.chatQueued, name)
                case .sent: return "Sending…"
                case .delivered: return "Delivered"
                case .read: return "Seen"
                case .received: return nil
                }
            }()
            Bubble(text: m.text, mine: true, meta: [time, status].compactMap { $0 }.joined(separator: " · "), faded: m.status == .queued)
        } else {
            switch m.screening {
            case .safe:
                Bubble(text: m.text, mine: false, meta: time, faded: false)
            case .flagged:
                HiddenBubble(text: L10n.chatFlagged, time: time) { repo.reveal(m.id) }
            default:
                HiddenBubble(text: "Checking this message on your phone…", time: time, onShow: nil)
            }
        }
    }
}

private struct Bubble: View {
    let text: String
    let mine: Bool
    let meta: String
    let faded: Bool

    var body: some View {
        let shape = UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: mine ? 16 : 4, bottomTrailingRadius: mine ? 4 : 16,
                                           topTrailingRadius: 16)
        HStack {
            if mine { Spacer(minLength: 60) }
            VStack(alignment: .trailing, spacing: 2) {
                Text(text).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).multilineTextAlignment(.leading)
                    .textSelection(.enabled)
                if !meta.isEmpty { Text(meta).typeStyle(.bodySmall, italic: true).foregroundStyle(Palette.moonstoneDim) }
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(shape.fill(mine ? Palette.dusk.opacity(faded ? 0.5 : 0.95) : Palette.inkViolet.opacity(0.95)))
            .overlay(shape.stroke(mine ? Palette.goldDeep : Palette.twilight, lineWidth: 1))
            if !mine { Spacer(minLength: 60) }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct HiddenBubble: View {
    let text: String
    let time: String
    let onShow: (() -> Void)?

    var body: some View {
        let shape = UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 4, bottomTrailingRadius: 16, topTrailingRadius: 16)
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(text).typeStyle(.bodyMedium, italic: true).foregroundStyle(Palette.moonstoneDim)
                if let onShow {
                    Button(action: onShow) { Text(L10n.chatFlaggedShow.uppercased()).typeStyle(.labelMedium).foregroundStyle(Palette.gold) }
                }
                Text(time).typeStyle(.bodySmall, italic: true).foregroundStyle(Palette.moonstoneDim).frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .frame(maxWidth: 300, alignment: .leading)
            .background(shape.fill(Palette.inkViolet.opacity(0.6)))
            .overlay(shape.stroke(Palette.roseCopper.opacity(0.6), lineWidth: 1))
            Spacer(minLength: 40)
        }
    }
}
