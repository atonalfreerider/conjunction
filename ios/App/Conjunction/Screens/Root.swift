import SwiftUI
import ConjunctionCore

/// Bar order (SPEC 5d.2): Today · Chart · [Nearby] · Readings · Profile.
enum AppTab: String, CaseIterable, Identifiable {
    case today, chart, nearby, readings, profile
    var id: String { rawValue }

    var label: String {
        switch self {
        case .today: return AppText.tabToday
        case .chart: return AppText.tabChart
        case .nearby: return AppText.tabNearby
        case .readings: return AppText.tabReadings
        case .profile: return AppText.tabProfile
        }
    }

    /// SF Symbols matching the Android outlined icons (Brightness3, Explore, AutoAwesome, Person).
    var symbol: String {
        switch self {
        case .today: return "moon"
        case .chart: return "safari"
        case .nearby: return "heart"
        case .readings: return "sparkles"
        case .profile: return "person"
        }
    }

    /// A saved tab name; the old Eastern / Palm tabs open Readings with that segment (SPEC 5d.1.4).
    static func fromSaved(_ name: String) -> (AppTab, ReadingsSegment?) {
        switch name {
        case "eastern": return (.readings, .eastern)
        case "palm": return (.readings, .palm)
        default: return (AppTab(rawValue: name) ?? .today, nil)
        }
    }
}

/// An open chat with a friend, by local friend id.
struct ChatTarget: Identifiable, Equatable {
    let friendId: String
    var id: String { friendId }
}

/// The Compatibility view of a friend id or an encounter fingerprint.
struct CompatTarget: Identifiable, Equatable {
    let key: String
    var id: String { key }
}

/// Top of the UI: the age block, onboarding or the main tabs (with the legal update sheet when the
/// documents changed). Match / twin / conjunction events (from the Bluetooth engine while the app
/// is open, or a tapped notification) open the full-screen match experience over everything.
struct RootView: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        Group {
            if app.profile.ageBlocked {
                AgeBlockedScreen()
            } else if !app.profile.onboardingComplete {
                OnboardingFlow()
            } else {
                MainScaffold()
            }
        }
        .preferredColorScheme(.dark)
        .tint(Palette.gold)
    }
}

/// Height of the bar itself; the Nearby medallion rises 34 pt above it.
enum TabBarMetrics {
    static let height: CGFloat = 64
    static let overhang: CGFloat = 34
    static let medallion: CGFloat = 68
}

struct MainScaffold: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    @SceneStorage("tab") private var savedTab = ""
    @State private var tab: AppTab = .today
    @State private var segment: ReadingsSegment = .eastern
    @State private var settings: SettingsPage?
    @State private var chat: ChatTarget?
    @State private var compat: CompatTarget?
    @State private var overlay: NearbyEvent?
    @State private var started = false

    var body: some View {
        ZStack(alignment: .bottom) {
            Palette.midnight.ignoresSafeArea()
            ZStack {
                switch tab {
                case .today: TodayScreen()
                case .chart: ChartScreen()
                case .nearby: NearbyScreen()
                case .readings: ReadingsScreen(segment: $segment)
                case .profile: ProfileScreen(page: $settings)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .transition(.opacity)
            .id(tab)
            .padding(.bottom, TabBarMetrics.height)
            GoldTabBar(selected: tab, nearbyOn: app.profile.nearbyEnabled && !app.profile.isPaused(nowMs: P2PLink.nowMs()),
                       badge: repo.pendingCount, onSelect: select)
        }
        .environment(\.appNav, nav)
        .fullScreenCover(item: $chat) { target in
            ChatScreen(target: target, onClose: { chat = nil })
                .environmentObject(app)
        }
        .background(
            Color.clear.fullScreenCover(item: $compat) { target in
                CompatibilityScreen(key: target.key, onClose: { compat = nil })
                    .environmentObject(app)
            }
        )
        .background(
            Color.clear.fullScreenCover(item: $overlay) { event in
                MatchExperience(event: event, onDismiss: { overlay = nil }, onOpenChat: { target in
                    overlay = nil
                    // Present the chat once the cover has gone.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { chat = target }
                })
                .environmentObject(app)
            }
        )
        .background(
            Color.clear.sheet(isPresented: $app.nearbySheet) {
                NearbyConsentSheet().environmentObject(app)
            }
        )
        .background(LegalUpdateGate())
        .onReceive(app.nearby.events) { event in
            // A mutual match while that person's card is already on screen: the card moves on by itself.
            if overlay?.encounterId == nil || overlay?.encounterId != event.encounterId { overlay = event }
        }
        .onChange(of: app.pendingEvent) { _, event in consume(event) }
        .onChange(of: tab) { _, t in savedTab = t.rawValue }
        .onAppear {
            if !started {
                started = true
                let (t, s) = AppTab.fromSaved(savedTab)
                // Onboarding's Done lands on Chart once (the wheel draws itself); later launches open Today.
                tab = app.profile.landOnChart ? .chart : t
                if let s { segment = s }
                if app.profile.landOnChart { app.update { $0.landOnChart = false } }
            }
            consume(app.pendingEvent)
        }
    }

    private var nav: AppNav {
        AppNav(
            openChat: { id in compat = nil; overlay = nil; chat = ChatTarget(friendId: id) },
            openCompat: { key in compat = CompatTarget(key: key) },
            openSettings: { page in select(.profile); settings = page },
            openTab: { t in select(t) })
    }

    private func select(_ t: AppTab) {
        withAnimation(.easeInOut(duration: 0.25)) { tab = t }
        if t != .profile { settings = nil }
    }

    private func consume(_ event: NearbyEvent?) {
        guard let event else { return }
        app.pendingEvent = nil
        select(.nearby)
        if event.kind == .message, let friend = event.friendId {
            overlay = nil
            chat = ChatTarget(friendId: friend)
        } else {
            chat = nil
            overlay = event
        }
    }
}

/// The bar (SPEC 5d.2): four side tabs (36 pt circle, 22 pt icon, label) and the raised 68 pt
/// gold-leaf Nearby medallion in the centre, its centre on the bar's top edge. Off = desaturated
/// 40%; on = a slow 6 s shimmer (a render-time offset on the overlay only; none with Reduce
/// Motion); a rose-copper badge at 1 o'clock counts new matches.
struct GoldTabBar: View {
    let selected: AppTab
    let nearbyOn: Bool
    let badge: Int
    let onSelect: (AppTab) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(GoldBrush.leafHorizontal).frame(height: 1)
            HStack(spacing: 0) {
                ForEach(AppTab.allCases) { tab in
                    if tab == .nearby {
                        Text(tab.label.uppercased()).typeStyle(.labelSmall)
                            .foregroundStyle(selected == .nearby ? Palette.gold : Palette.moonstoneDim)
                            .lineLimit(1).minimumScaleFactor(0.7)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                            .padding(.bottom, 8)
                            .accessibilityHidden(true)
                    } else {
                        sideTab(tab)
                    }
                }
            }
            .frame(height: TabBarMetrics.height - 1)
        }
        .background(
            LinearGradient(colors: [Palette.inkViolet.opacity(0.97), Palette.midnight], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .bottom)
        )
        .overlay(alignment: .top) {
            NearbyMedallion(selected: selected == .nearby, on: nearbyOn, badge: badge) { onSelect(.nearby) }
                .offset(y: -TabBarMetrics.overhang)
        }
    }

    private func sideTab(_ tab: AppTab) -> some View {
        let isSelected = tab == selected
        return Button { onSelect(tab) } label: {
            VStack(spacing: 2) {
                ZStack {
                    if isSelected { Circle().stroke(GoldBrush.leaf, lineWidth: 1) }
                    Image(systemName: tab.symbol).font(.system(size: 22, weight: .regular))
                        .foregroundStyle(isSelected ? Palette.goldPale : Palette.moonstoneDim)
                }
                .frame(width: 36, height: 36)
                Text(tab.label.uppercased()).typeStyle(.labelSmall)
                    .foregroundStyle(isSelected ? Palette.gold : Palette.moonstoneDim)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(isSelected ? [.isSelected, .isButton] : .isButton)
    }
}

/// The 68 pt gold-leaf Nearby button: gradient #D4AF37 -> #F5E6A8, 2 pt filigree ring, ☌ glyph.
private struct NearbyMedallion: View {
    let selected: Bool
    let on: Bool
    let badge: Int
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let size = TabBarMetrics.medallion
        Button(action: action) {
            ZStack {
                Circle().fill(LinearGradient(colors: [Palette.gold, Palette.goldPale, Palette.gold], startPoint: .topLeading,
                                             endPoint: .bottomTrailing))
                if on && !reduceMotion {
                    GoldShimmerFill(duration: 6, mask: Circle())
                }
                Circle().strokeBorder(AngularGradient(colors: [Palette.goldDeep, Palette.goldPale, Palette.gold, Palette.goldDeep],
                                                      center: .center), lineWidth: 2)
                Circle().strokeBorder(Palette.midnight.opacity(0.55), lineWidth: 0.8).padding(4)
                if selected { Circle().strokeBorder(Palette.midnight.opacity(0.25), lineWidth: 1).padding(7) }
                ConjunctionGlyph().stroke(Palette.midnight, style: StrokeStyle(lineWidth: 32 * 0.085, lineCap: .round))
                    .frame(width: 32, height: 32)
            }
            .frame(width: size, height: size)
            // Off: the gold desaturated by 40%.
            .saturation(on ? 1 : 0.6)
            .shadow(color: Palette.midnight.opacity(0.6), radius: 6, y: 2)
            .overlay(alignment: .topTrailing) {
                if badge > 0 {
                    Text(badge > 9 ? "9+" : "\(badge)").typeStyle(.labelSmall).foregroundStyle(Palette.moonstone)
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(Palette.roseCopper))
                        .overlay(Circle().stroke(Palette.midnight, lineWidth: 1))
                        .offset(x: 2, y: -2)
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(badge > 0 ? L10n.format(AppText.tabNearbyBadgeDesc, String(badge)) : AppText.tabNearby)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }
}

/// The ☌ glyph drawn as a path so it is crisp in every font: a ring with a stroke to the upper right.
struct ConjunctionGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        let w = min(rect.width, rect.height)
        let r = w * 0.27
        let c = CGPoint(x: rect.minX + w * 0.40, y: rect.minY + w * 0.60)
        var p = Path()
        p.addEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
        let k = 0.7071
        p.move(to: CGPoint(x: c.x + r * k, y: c.y - r * k))
        p.addLine(to: CGPoint(x: rect.minX + w * 0.92, y: rect.minY + w * 0.08))
        return p
    }
}

/// SPEC 5d.1.4: the compact re-accept sheet (major change; only its button closes it) or the
/// one-time notice (minor change; button or swipe).
private struct LegalUpdateGate: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        Color.clear.sheet(isPresented: Binding(get: { app.legalUpdate != .none }, set: { shown in
            // Swiped away: only the notice can be dismissed this way, and it then counts as seen.
            if !shown, case .notice(let docs) = app.legalUpdate { app.noticeSeen(docs, body: noticeBody(docs)) }
        })) {
            LegalUpdateSheet(update: app.legalUpdate)
                .environmentObject(app)
        }
    }

    private func noticeBody(_ docs: [LegalDoc]) -> String {
        L10n.format(L10n.legalNoticeBody, docs.map(\.title).joined(separator: ", "))
    }
}

private struct LegalUpdateSheet: View {
    @EnvironmentObject var app: AppModel
    let update: LegalUpdate
    @State private var open: LegalDoc?

    var body: some View {
        let (docs, reaccept): ([LegalDoc], Bool) = {
            switch update {
            case .reaccept(let d): return (d, true)
            case .notice(let d): return (d, false)
            case .none: return ([], false)
            }
        }()
        let template = reaccept ? L10n.legalReacceptBody : L10n.legalNoticeBody
        // One %1$s placeholder: the changed documents as links, comma-separated.
        let linkTemplate = docs.indices.map { "%\($0 + 1)$s" }.joined(separator: ", ")
        let body = L10n.format(template, docs.map(\.title).joined(separator: ", "))
        VStack(alignment: .leading, spacing: 12) {
            GoldText(reaccept ? L10n.legalReacceptTitle : L10n.legalNoticeTitle, style: .headlineSmall, alignment: .leading, heading: true)
            LinkedText(template: template.replacingOccurrences(of: "%1$s", with: linkTemplate),
                       links: docs.map { doc -> (String, () -> Void) in (doc.title, { open = doc }) })
            GoldButton(reaccept ? L10n.legalReacceptContinue : L10n.legalNoticeOk) {
                if reaccept { app.acceptLegalUpdate(docs, body: body) } else { app.noticeSeen(docs, body: body) }
            }
        }
        .padding(24)
        .frame(maxWidth: 640)
        .presentationDetents([.medium])
        .presentationDragIndicator(reaccept ? .hidden : .visible)
        .presentationBackground(LinearGradient(colors: [Palette.inkViolet, Palette.midnight], startPoint: .top, endPoint: .bottom))
        .interactiveDismissDisabled(reaccept)
        .sheet(item: $open) { LegalDocSheet(doc: $0) }
    }
}

/// Blocked install (under 18): no retry with another date.
struct AgeBlockedScreen: View {
    var body: some View {
        StarfieldBackground(background: "bg_onboarding_1", dim: 0.6) {
            VStack(spacing: 12) {
                GoldText(L10n.ageGateBlockedTitle, style: .headlineLarge, heading: true)
                OrnateDivider()
                Text(L10n.ageGateBlockedBody).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            }
            .padding(32)
        }
    }
}
