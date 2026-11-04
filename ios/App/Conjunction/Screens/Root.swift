import SwiftUI
import ConjunctionCore

enum AppTab: String, CaseIterable, Identifiable {
    case chart, today, eastern, palm, nearby, profile
    var id: String { rawValue }

    var label: String {
        switch self {
        case .chart: return AppText.tabChart
        case .today: return AppText.tabToday
        case .eastern: return AppText.tabEastern
        case .palm: return AppText.tabPalm
        case .nearby: return AppText.tabNearby
        case .profile: return AppText.tabProfile
        }
    }

    /// SF Symbols matching the Android outlined icons (Explore, Brightness3, AutoAwesome, PanTool, Favorite, Person).
    var symbol: String {
        switch self {
        case .chart: return "safari"
        case .today: return "moon"
        case .eastern: return "sparkles"
        case .palm: return "hand.raised"
        case .nearby: return "heart"
        case .profile: return "person"
        }
    }
}

/// An open chat with a friend, by local friend id.
struct ChatTarget: Identifiable, Equatable {
    let friendId: String
    var id: String { friendId }
}

/// Top of the UI: the age block, onboarding, legal re-acceptance when the bundled documents changed
/// version, or the main tabs. Match / twin / conjunction events (from the Bluetooth engine while the
/// app is open, or a tapped notification) open the full-screen match experience over everything.
struct RootView: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        Group {
            if app.profile.ageBlocked {
                AgeBlockedScreen()
            } else if !app.profile.onboardingComplete {
                OnboardingFlow()
            } else if !app.changedLegalDocs.isEmpty {
                ReacceptScreen(changed: app.changedLegalDocs)
            } else {
                MainScaffold()
            }
        }
        .preferredColorScheme(.dark)
        .tint(Palette.gold)
    }
}

struct MainScaffold: View {
    @EnvironmentObject var app: AppModel
    @SceneStorage("tab") private var tab: AppTab = .today
    @State private var chat: ChatTarget?
    @State private var overlay: NearbyEvent?

    var body: some View {
        ZStack {
            Palette.midnight.ignoresSafeArea()
            VStack(spacing: 0) {
                ZStack {
                    switch tab {
                    case .chart: ChartScreen()
                    case .today: TodayScreen()
                    case .eastern: EasternScreen()
                    case .palm: PalmScreen()
                    case .nearby: NearbyScreen(openChat: { chat = $0 })
                    case .profile: ProfileScreen()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.opacity)
                .id(tab)
                GoldTabBar(selected: $tab)
            }
        }
        .fullScreenCover(item: $chat) { target in
            ChatScreen(target: target, onClose: { chat = nil })
        }
        .fullScreenCover(item: $overlay) { event in
            MatchExperience(event: event, onDismiss: { overlay = nil }, onOpenChat: { target in
                overlay = nil
                // Present the chat once the cover has gone.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { chat = target }
            })
        }
        .onReceive(app.nearby.events) { event in
            // A mutual match while that person's card is already on screen: the card moves on by itself.
            if overlay?.encounterId == nil || overlay?.encounterId != event.encounterId { overlay = event }
        }
        .onChange(of: app.pendingEvent) { _, event in consume(event) }
        .onAppear { consume(app.pendingEvent) }
    }

    private func consume(_ event: NearbyEvent?) {
        guard let event else { return }
        app.pendingEvent = nil
        if event.kind == .message, let friend = event.friendId {
            tab = .nearby
            overlay = nil
            chat = ChatTarget(friendId: friend)
        } else {
            chat = nil
            overlay = event
        }
    }
}

/// Ornate bottom bar: indigo glass, gold hairline, the selected tab lit in gold.
struct GoldTabBar: View {
    @Binding var selected: AppTab

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(GoldBrush.leafHorizontal).frame(height: 1)
            HStack(spacing: 0) {
                ForEach(AppTab.allCases) { tab in
                    let isSelected = tab == selected
                    Button {
                        withAnimation(.easeInOut(duration: 0.25)) { selected = tab }
                    } label: {
                        VStack(spacing: 2) {
                            ZStack {
                                if isSelected { Circle().stroke(GoldBrush.leaf, lineWidth: 1) }
                                Image(systemName: tab.symbol).font(.system(size: 19, weight: .regular))
                                    .foregroundStyle(isSelected ? Palette.goldPale : Palette.moonstoneDim)
                            }
                            .frame(width: 36, height: 36)
                            Text(tab.label.uppercased()).typeStyle(.labelSmall)
                                .foregroundStyle(isSelected ? Palette.gold : Palette.moonstoneDim)
                                .lineLimit(1).minimumScaleFactor(0.7)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tab.label)
                    .accessibilityAddTraits(isSelected ? [.isSelected, .isButton] : .isButton)
                }
            }
            .padding(.vertical, 6)
        }
        .background(
            LinearGradient(colors: [Palette.inkViolet.opacity(0.96), Palette.midnight], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .bottom)
        )
    }
}

/// Shown when the bundled document versions are newer than what was accepted.
struct ReacceptScreen: View {
    @EnvironmentObject var app: AppModel
    let changed: [LegalDoc]
    @State private var open: LegalDoc?

    var body: some View {
        StarfieldBackground {
            ScrollView {
                VStack(spacing: 14) {
                    GoldText(L10n.legalReacceptTitle, style: .headlineMedium, heading: true)
                    Text(L10n.format(L10n.legalReacceptBody, AppInfo.name)).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone)
                        .multilineTextAlignment(.center)
                    ForEach(changed) { doc in GhostButton("Read the \(doc.title)") { open = doc } }
                    GoldButton(L10n.legalContinue) {
                        for doc in changed { if let text = LegalTexts.load(doc) { app.recordDocument(text) } }
                    }
                }
                .padding(24)
            }
        }
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
