import SwiftUI
import UIKit
import ConjunctionCore

// Building blocks of UX 2.0 (SPEC 5d), the SwiftUI twins of Android ui/components/Sheets.kt and
// the chips / menus in NearbyScreen.kt and CompatibilityScreen.kt.

// MARK: - Navigation

/// Profile › Settings pages (SPEC 5d.3.2 / 5d.1.3).
enum SettingsPage: String, Identifiable {
    case nearby, notifications, privacy, blocked
    var id: String { rawValue }

    var title: String {
        switch self {
        case .nearby: return AppText.settingsNearby
        case .notifications: return AppText.settingsNotifications
        case .privacy: return L10n.settingsPrivacyLegalTitle
        case .blocked: return L10n.settingsBlocked
        }
    }
}

/// App-wide navigation for screens deep in the tree (chat, compatibility, settings, tabs).
struct AppNav {
    var openChat: (String) -> Void = { _ in }
    var openCompat: (String) -> Void = { _ in }
    var openSettings: (SettingsPage) -> Void = { _ in }
    var openTab: (AppTab) -> Void = { _ in }
}

private struct AppNavKey: EnvironmentKey {
    static let defaultValue = AppNav()
}

/// Something a parent puts above a tab page's title (the Readings segment control).
private struct TabHeaderKey: EnvironmentKey {
    static let defaultValue: AnyView? = nil
}

extension EnvironmentValues {
    var appNav: AppNav {
        get { self[AppNavKey.self] }
        set { self[AppNavKey.self] = newValue }
    }

    var tabHeader: AnyView? {
        get { self[TabHeaderKey.self] }
        set { self[TabHeaderKey.self] = newValue }
    }
}

// MARK: - Links and the clickwrap line (SPEC 5d.1.1)

/// Body text with gold, underlined links: [template] uses `%1$s`, `%2$s`...; each [links] entry is
/// (label, action). The sentence is laid out word by word in a flow, and every link is its own
/// Button with a >= 44 pt touch target (an inline AttributedString link is only as tall as its
/// glyphs). [onTextTap] = a tap on the non-link words (the clickwrap ticks its box); link taps
/// never reach it. VoiceOver reads the whole sentence as one element with one action per link.
struct LinkedText: View {
    let template: String
    let links: [(String, () -> Void)]
    var style: TypeStyle = .bodyLarge
    var onTextTap: (() -> Void)? = nil

    private enum Token { case word(String), link(Int) }

    var body: some View {
        let flow = FlowLayout(spacing: 0) {
            ForEach(Array(tokens.enumerated()), id: \.offset) { _, token in
                switch token {
                case .word(let w):
                    Text(w).typeStyle(style).foregroundStyle(Palette.moonstone).frame(minHeight: 44)
                case .link(let n):
                    Button { links[n].1() } label: {
                        Text(links[n].0).underline(true, color: Palette.gold).typeStyle(style).foregroundStyle(Palette.gold)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(formatted)
        .accessibilityActions {
            ForEach(links.indices, id: \.self) { i in
                Button(links[i].0) { links[i].1() }
            }
        }
        if let onTextTap {
            flow.contentShape(Rectangle()).onTapGesture { onTextTap() }
        } else {
            flow
        }
    }

    /// The sentence as shown, with the link labels in place (what the consent hash covers).
    private var formatted: String {
        var out = template
        for (i, link) in links.enumerated() { out = out.replacingOccurrences(of: "%\(i + 1)$s", with: link.0) }
        return out
    }

    private var tokens: [Token] {
        var out: [Token] = []
        func words(_ text: Substring) {
            let parts = text.components(separatedBy: " ")
            for (i, p) in parts.enumerated() {
                let w = i < parts.count - 1 ? p + " " : p
                if !w.isEmpty { out.append(.word(w)) }
            }
        }
        var rest = Substring(template)
        while let r = rest.range(of: "%[0-9]\\$s", options: .regularExpression) {
            words(rest[rest.startIndex..<r.lowerBound])
            let digit = rest[rest.index(after: r.lowerBound)]
            if let n = Int(String(digit)), n >= 1, n <= links.count { out.append(.link(n - 1)) }
            rest = rest[r.upperBound...]
        }
        words(rest)
        return out
    }
}

/// L1: the unticked checkbox and the agreement sentence with its three document links.
struct ClickwrapRow: View {
    @Binding var checked: Bool
    let onOpen: (LegalDoc) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Button { checked.toggle() } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 3).stroke(checked ? Palette.gold : Palette.goldPale, lineWidth: 2)
                    if checked {
                        RoundedRectangle(cornerRadius: 3).fill(Palette.gold)
                        Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.midnight)
                    }
                }
                .frame(width: 22, height: 22)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Clickwrap.checkboxText)
            .accessibilityValue(checked ? "Ticked" : "Not ticked")
            .accessibilityAddTraits(checked ? [.isSelected, .isButton] : .isButton)
            // The links are separate buttons: tapping one opens the document and never ticks the box.
            LinkedText(template: L10n.legalAcceptCheckbox, links: [
                (L10n.legalLinkTerms, { onOpen(.terms) }),
                (L10n.legalLinkGuidelines, { onOpen(.community) }),
                (L10n.legalLinkWaiver, { onOpen(.waiver) }),
            ], onTextTap: { checked.toggle() })
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Chips, medallions and menus

/// A confirm chip (SPEC 5d.5): filled = gold-leaf border with the value; empty = outline "Add …".
/// [highlight] marks an ambiguous numeric date; [working] paints a faint shimmer while the
/// on-device model may still fill it.
struct ConfirmChip: View {
    let value: String?
    let placeholder: String
    var highlight = false
    var working = false
    let action: () -> Void

    var body: some View {
        let shape = CutCornerShape(cut: 8)
        Button(action: action) {
            Text(value ?? placeholder)
                .typeStyle(.titleSmall)
                .foregroundStyle(value != nil ? Palette.moonstone : Palette.goldPale.opacity(0.8))
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .background(shape.fill(value != nil ? Palette.inkViolet.opacity(0.9) : Palette.midnight.opacity(0.8)))
                .overlay {
                    if highlight {
                        shape.stroke(Palette.roseCopper, lineWidth: 2)
                    } else if value != nil {
                        shape.stroke(GoldBrush.leaf, lineWidth: 1)
                    } else {
                        shape.stroke(Palette.goldDeep, lineWidth: 1)
                    }
                }
                .overlay {
                    if working { GoldShimmerFill(duration: 2.4, mask: shape).opacity(0.25) }
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(value ?? placeholder)
    }
}

/// Dating / Friends (or a free label) chip.
struct KindChip: View {
    let text: String
    init(kind: String) { text = kind == "dating" ? "Dating" : "Friends" }
    init(label: String) { text = label }

    var body: some View {
        Text(text.uppercased()).typeStyle(.labelSmall).foregroundStyle(Palette.goldPale)
            .padding(.horizontal, 10).padding(.vertical, 5)
            .overlay(CutCornerShape(cut: 6).stroke(GoldBrush.leaf, lineWidth: 1))
    }
}

/// The Instagram chip (SPEC 5d.6): opens https://www.instagram.com/<handle>/.
struct InstagramChip: View {
    let handle: String

    var body: some View {
        Button { Instagram.open(handle) } label: {
            Text("\(AppText.instagramChip) @\(handle)").typeStyle(.labelSmall).foregroundStyle(Palette.midnight)
                .lineLimit(1)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(CutCornerShape(cut: 6).fill(GoldBrush.leaf))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Instagram, @\(handle)")
    }
}

/// Gold score medallion ("87%") for cards.
struct ScoreMedallion: View {
    let total: Int
    var size: CGFloat = 56

    var body: some View {
        Text("\(total)%").typeStyle(.titleSmall).foregroundStyle(Palette.midnight)
            .minimumScaleFactor(0.6).lineLimit(1)
            .frame(width: size, height: size)
            .background(Circle().fill(GoldBrush.leaf))
            .accessibilityLabel("\(total) percent compatible")
    }
}

/// The ☌ chip that opens the Compatibility view.
struct ChartChip: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(ChartWheelGeometry.textGlyph("☌")).typeStyle(.titleLarge).foregroundStyle(Palette.goldPale)
                .frame(width: 44, height: 44)
                .overlay(Circle().stroke(GoldBrush.leaf, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Compatibility chart")
    }
}

/// The ⋯ button with a menu.
struct MoreMenu<Content: View>: View {
    var label = "More options"
    @ViewBuilder var content: Content

    var body: some View {
        Menu { content } label: {
            Image(systemName: "ellipsis").font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.goldPale)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(label)
    }
}

/// The ⋯ menu of a stranger's card (SPEC 5d.3): Snooze 7 days, Block, Report, Safety Tips.
enum CardMenuAction { case snooze, block, report, tips }

struct CardMenu: View {
    let onAction: (CardMenuAction) -> Void

    var body: some View {
        MoreMenu {
            Button(AppText.menuSnooze) { onAction(.snooze) }
            Button(AppText.menuBlock, role: .destructive) { onAction(.block) }
            Button(AppText.menuReport, role: .destructive) { onAction(.report) }
            Button(AppText.menuSafetyTips) { onAction(.tips) }
        }
    }
}

/// Friend ⋯ (Conjunctions card and chat header): Instagram, share switch, safety code, tips, unmatch, block, report.
enum FriendMenuAction { case instagram, shareInstagram, safetyCode, tips, unmatch, block, report }

struct FriendMenu: View {
    let friend: FriendSummary
    let shareOn: Bool
    var showInstagram = false
    var showTips = false
    let onAction: (FriendMenuAction) -> Void

    var body: some View {
        MoreMenu(label: "More options for \(friend.firstName)") {
            if showInstagram, let h = friend.peerIg {
                Button("\(AppText.instagramChip) @\(h)") { onAction(.instagram) }
            }
            Button {
                onAction(.shareInstagram)
            } label: {
                if shareOn {
                    Label(L10n.format(L10n.instagramShareWithMatch, friend.firstName), systemImage: "checkmark")
                } else {
                    Text(L10n.format(L10n.instagramShareWithMatch, friend.firstName))
                }
            }
            Button(AppText.menuSafetyCode) { onAction(.safetyCode) }
            if showTips { Button(AppText.menuSafetyTips) { onAction(.tips) } }
            Button(AppText.menuUnmatch) { onAction(.unmatch) }
            Button(AppText.menuBlock, role: .destructive) { onAction(.block) }
            Button(AppText.menuReport, role: .destructive) { onAction(.report) }
        }
    }
}

// MARK: - Pages and rows

/// A page header with back: Settings sub-pages and the Compatibility view.
struct PageHeader<Trailing: View>: View {
    let title: String
    let onBack: () -> Void
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onBack) {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.goldPale)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Back")
            GoldText(title, style: .titleLarge, alignment: .leading, heading: true).lineLimit(1)
            Spacer(minLength: 0)
            trailing
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }
}

extension PageHeader where Trailing == EmptyView {
    init(title: String, onBack: @escaping () -> Void) {
        self.init(title: title, onBack: onBack) { EmptyView() }
    }
}

struct SettingsHeading: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).typeStyle(.titleMedium).foregroundStyle(Palette.gold).padding(.bottom, 6).accessibilityAddTraits(.isHeader)
    }
}

/// A plain settings row with a chevron.
struct SettingsRow: View {
    let title: String
    var subtitle: String?
    var color: Color = Palette.moonstone
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).typeStyle(.titleMedium).foregroundStyle(color).multilineTextAlignment(.leading)
                    if let subtitle { Text(subtitle).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim) }
                }
                Spacer(minLength: 8)
                Text("›").typeStyle(.headlineSmall).foregroundStyle(Palette.gold)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A thin gold rule between groups inside one panel.
struct PanelRule: View {
    var body: some View { Rectangle().fill(Palette.goldDeep.opacity(0.45)).frame(height: 1).padding(.vertical, 6) }
}

/// An underlined gold text button ("Details", "Safety Tips", "Enter each separately").
struct SheetLink: View {
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text).typeStyle(.bodyLarge).underline().foregroundStyle(Palette.gold)
                .padding(.vertical, 12).padding(.horizontal, 4)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Instagram (SPEC 5d.6)

enum Instagram {
    /// A plain https link: the universal link opens the Instagram app when it is installed, else
    /// Safari (no `LSApplicationQueriesSchemes`, no `instagram://`).
    static func url(_ handle: String) -> URL? { URL(string: InstagramHandle.profileURL(handle)) }

    @MainActor
    static func open(_ handle: String) {
        guard let url = url(handle) else { return }
        UIApplication.shared.open(url)
    }
}

/// In-app rationale shown before a system permission prompt (consent copy section 9; the palm camera).
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
            GhostButton(AppText.notNow) { dismiss() }
        }
        .presentationDetents([.medium, .large])
    }
}
