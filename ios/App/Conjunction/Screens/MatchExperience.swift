import SwiftUI
import ConjunctionCore

private enum CardStage { case animating, card, waiting }

/// Full-screen match flow (Android `MatchExperience`), SPEC 5b/5c: the match animation (both sun
/// signs in gold medallions with profile photos spiral in from the upper-left and lower-right and
/// merge into the conjunction emblem; birthday twins get the lavish starburst with star confetti),
/// then the swipe card (right = accept, left = pass, buttons and accessibility actions too), then
/// "It's a Conjunction!" with the other person's photo and an intro box once both said yes.
/// Everything comes from this phone's store: what the other phone sent over Bluetooth.
struct MatchExperience: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    let event: NearbyEvent
    let skipAnimation: Bool
    let onDismiss: () -> Void
    let onOpenChat: (ChatTarget) -> Void
    @State private var stage: CardStage
    @State private var blurb: String?
    @State private var safety: NearbyScreen.SafetySheet?
    @State private var tips = false
    @State private var compat: CompatTarget?

    init(event: NearbyEvent, skipAnimation: Bool = false, onDismiss: @escaping () -> Void, onOpenChat: @escaping (ChatTarget) -> Void) {
        self.event = event
        self.skipAnimation = skipAnimation
        self.onDismiss = onDismiss
        self.onOpenChat = onOpenChat
        _stage = State(initialValue: skipAnimation ? .card : .animating)
    }

    private var card: StoredEncounter? { event.encounterId.flatMap { repo.encounter($0) } }

    var body: some View {
        let card = self.card
        let friendId = event.friendId ?? card?.friendId
        ZStack {
            Palette.midnight.ignoresSafeArea()
            if let friendId {
                ConjunctionPopup(friendId: friendId, card: card, onOpenChat: onOpenChat,
                                 onOpenCompat: { compat = CompatTarget(key: friendId) }, onClose: onDismiss)
            } else if let card, card.mySwipe != "left" {
                if stage == .animating && card.mySwipe == nil {
                    MatchAnimation(card: card, mySign: app.charts?.natal.sunSign, myPhoto: app.ownerPhotoURL,
                                   theirPhoto: repo.photoURL(card.photoFile), blurb: blurb) { stage = .card }
                } else if stage == .waiting || card.mySwipe == "right" {
                    waiting(card)
                } else {
                    SwipeCardPage(card: card, photo: repo.photoURL(card.photoFile), blurb: blurb,
                                  onSwipe: { accept in
                                      repo.swipe(card.fingerprint, accept: accept)
                                      if accept { stage = .waiting } else { onDismiss() }
                                  },
                                  onChart: { compat = CompatTarget(key: card.fingerprint) },
                                  onMenu: { action in
                                      switch action {
                                      case .snooze:
                                          repo.snooze(card.fingerprint)
                                          onDismiss()
                                      case .tips: tips = true
                                      case .block: safety = NearbyScreen.SafetySheet(target: .stranger(card), mode: "block")
                                      case .report: safety = NearbyScreen.SafetySheet(target: .stranger(card), mode: "report")
                                      }
                                  },
                                  onClose: onDismiss)
                }
            } else {
                VStack(spacing: 16) {
                    Text("This match is no longer available.").typeStyle(.titleMedium).foregroundStyle(Palette.moonstone)
                        .multilineTextAlignment(.center)
                    GhostButton("Close", fullWidth: false, action: onDismiss)
                }
                .padding(32)
            }
        }
        .task(id: card?.fingerprint) { await loadBlurb() }
        .sheet(item: $safety) { s in
            SafetyActionsSheet(target: s.target, initialMode: s.mode) { closed in if closed { onDismiss() } }
        }
        .sheet(isPresented: $tips) { SafetyTipsSheet() }
        .fullScreenCover(item: $compat) { target in
            CompatibilityScreen(key: target.key, onClose: { compat = nil })
        }
    }

    private func waiting(_ card: StoredEncounter) -> some View {
        VStack(spacing: 12) {
            Image("popup_match_nearby").resizable().scaledToFit().frame(width: 220, height: 220).accessibilityHidden(true)
            GoldText("Your star is sent", style: .headlineMedium)
            Text(L10n.format(AppText.nearbyWaitingFor, card.firstName ?? "them"))
                .typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            GhostButton(ChartWheelGeometry.textGlyph("☌") + " " + AppText.matchCardChart, fullWidth: false) {
                compat = CompatTarget(key: card.fingerprint)
            }
            .padding(.top, 10)
            GoldButton(AppText.onbDone, fullWidth: false, action: onDismiss).padding(.top, 10)
        }
        .padding(32)
    }

    private func loadBlurb() async {
        guard let c = card, c.friendId == nil else { return }
        let natal = app.charts?.natal
        let input = MatchBlurbPrompt.Input(
            kind: c.kind == "dating" ? .dating : .friends,
            mySun: natal?.sunSign.displayName ?? "",
            theirSun: CardText.sign(c.sun),
            theirMoon: CardText.sign(c.moon),
            theirRising: c.rising.map(CardText.sign),
            myAnimal: app.charts?.chinese.yearAnimal.displayName ?? "",
            theirAnimal: CardText.animal(c.chineseAnimal),
            total: c.total, western: c.western, chinese: c.chinese, blood: c.blood,
            birthdayTwin: c.birthdayTwin, exactTwin: c.exactTwin)
        blurb = MatchBlurbPrompt.fallback(input)
        blurb = await app.readings.matchBlurb(input)
    }
}

// MARK: - Animation

/// One falling star of the twin confetti.
private struct Confetti {
    let x: CGFloat, delay: CGFloat, speed: CGFloat, size: CGFloat, spin: CGFloat, gold: Bool
}

private let confetti: [Confetti] = {
    var seed: UInt64 = 11
    func next() -> CGFloat {
        seed = seed &* 6364136223846793005 &+ 1442695040888963407
        return CGFloat(seed >> 11) / CGFloat(UInt64(1) << 53)
    }
    return (0..<70).map { _ in Confetti(x: next(), delay: next() * 0.5, speed: 0.6 + next(), size: 6 + next() * 10, spin: next() * 720, gold: next() < 0.7) }
}()

/// ~2.6 s: gold flash; my sun medallion spirals in from the upper-left and theirs from the
/// lower-right (each with the person's photo inset); they meet and merge into the conjunction
/// emblem with a gold burst; the percentage counts up with the sub-scores and the blurb. Tap to
/// skip. Reduce Motion: a short cross-fade to the final frame.
struct MatchAnimation: View {
    let card: StoredEncounter
    let mySign: ZodiacSign?
    let myPhoto: URL?
    let theirPhoto: URL?
    let blurb: String?
    let onFinished: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var p: Double = 0
    @State private var done = false

    var body: some View {
        MatchAnimationFrame(p: p, card: card, mySign: mySign, myPhoto: myPhoto, theirPhoto: theirPhoto, blurb: blurb, reduceMotion: reduceMotion)
            .contentShape(Rectangle())
            .onTapGesture { finish() }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(card.isTwin ? "Birthday twin match animation" : "Match animation")
            .accessibilityAction(named: "Skip animation") { finish() }
            .onAppear {
                p = reduceMotion ? 0.7 : 0
                let duration = reduceMotion ? 0.6 : 2.6
                withAnimation(.linear(duration: duration)) { p = 1 }
                DispatchQueue.main.asyncAfter(deadline: .now() + duration + (card.isTwin ? 1.6 : 1.1)) { finish() }
            }
    }

    private func finish() {
        guard !done else { return }
        done = true
        onFinished()
    }
}

/// One frame of the animation at progress [p]; Animatable, so SwiftUI redraws it per frame.
private struct MatchAnimationFrame: View, Animatable {
    var p: Double
    let card: StoredEncounter
    let mySign: ZodiacSign?
    let myPhoto: URL?
    let theirPhoto: URL?
    let blurb: String?
    let reduceMotion: Bool

    var animatableData: Double {
        get { p }
        set { p = newValue }
    }

    private func clamp(_ x: Double) -> Double { min(max(x, 0), 1) }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let twin = card.isTwin
            let flash = p < 0.12 ? clamp(1 - abs(p - 0.04) / 0.08) : 0
            let approach = clamp((p - 0.05) / 0.57)
            let eased = 1 - (1 - approach) * (1 - approach)
            let radius = (1 - eased) * max(w, h) * 0.62
            let turn = eased * 1.25 * 2 * Double.pi
            let merge = clamp((p - 0.58) / 0.14)
            let emblem = clamp((p - 0.6) / 0.15)
            let countT = clamp((p - 0.68) / 0.3)
            let centre = CGPoint(x: w / 2, y: h * 0.42)
            ZStack {
                Image(twin ? "popup_birthday_twins" : "popup_match_nearby").resizable().scaledToFill()
                    .frame(width: w, height: h).clipped()
                    .opacity(0.25 + 0.45 * clamp((p - 0.55) / 0.45))
                RadialGradient(colors: [Palette.goldPale.opacity(flash), Color.white.opacity(0.6 * flash), .clear],
                               center: .center, startRadius: 0, endRadius: max(w, h) * 0.7)
                if merge < 1 {
                    // Mine from the upper-left (225 deg on screen), theirs from the lower-right (45 deg).
                    medallion(sign: mySign.map(Art.sign) ?? "brand_mark", photo: myPhoto, screening: .safe)
                        .scaleEffect(1 - 0.4 * merge).opacity(1 - merge)
                        .position(spiral(centre, radius, start: 225 * .pi / 180 + turn))
                    medallion(sign: Art.signKey(card.sun), photo: theirPhoto, screening: card.photoScreening)
                        .scaleEffect(1 - 0.4 * merge).opacity(1 - merge)
                        .position(spiral(centre, radius, start: 45 * .pi / 180 + turn))
                }
                if emblem > 0 {
                    Image(twin ? "match_twin_burst" : "match_merge_burst").resizable().scaledToFit()
                        .frame(width: 340, height: 340)
                        .scaleEffect(0.4 + emblem).rotationEffect(.degrees(p * 40)).opacity(emblem)
                        .position(centre)
                    Image("match_merge_emblem").resizable().scaledToFit().frame(width: 190, height: 190)
                        .scaleEffect(emblem).position(centre)
                }
                if twin && !reduceMotion { confettiLayer }
                VStack(spacing: 4) {
                    Spacer()
                    GoldText(card.exactTwin ? "Exact twin!" : card.birthdayTwin ? "Birthday twin!" : (card.kind == "dating" ? "A spark nearby" : "A kindred star nearby"),
                             style: .headlineMedium)
                    Text("\(Int((Double(card.total) * countT).rounded()))%").typeStyle(.displayLarge).foregroundStyle(Palette.gold)
                    Text("Western \(card.western) · Chinese \(card.chinese)" + (card.blood.map { " · Blood \($0)" } ?? ""))
                        .typeStyle(.titleSmall).foregroundStyle(Palette.goldPale).multilineTextAlignment(.center)
                    if let blurb {
                        Text(blurb).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center).padding(.top, 8)
                    }
                    FinePrint("Tap to continue", alignment: .center).padding(.top, 10)
                }
                .padding(.horizontal, 24).padding(.top, 96).padding(.bottom, 24)
                .frame(maxWidth: .infinity)
                .background(
                    LinearGradient(colors: [.clear, Palette.midnight.opacity(0.78), Palette.midnight.opacity(0.9), Palette.midnight.opacity(0.95)],
                                   startPoint: .top, endPoint: .bottom)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                )
                .opacity(countT)
                .frame(maxHeight: .infinity, alignment: .bottom)
            }
        }
        .ignoresSafeArea()
    }

    private func spiral(_ c: CGPoint, _ r: Double, start: Double) -> CGPoint {
        CGPoint(x: c.x + r * cos(start), y: c.y + r * sin(start))
    }

    private func medallion(sign: String, photo: URL?, screening: Screening) -> some View {
        Medallion(signArt: sign, photo: photo, screening: screening, size: 132)
    }

    private var confettiLayer: some View {
        Canvas { ctx, size in
            for c in confetti {
                let local = max(0, (CGFloat(p) - 0.55 - c.delay * 0.3) * c.speed * 2.2)
                if local <= 0 { continue }
                let y = local * size.height
                let x = c.x * size.width + sin(local * 8 + c.spin) * 24
                var inner = ctx
                inner.translateBy(x: x, y: y)
                inner.rotate(by: .degrees(Double(c.spin * local)))
                inner.fill(starPath(radius: c.size), with: .color(c.gold ? Palette.gold : Palette.moonstone))
            }
        }
        .allowsHitTesting(false)
    }

    private func starPath(radius r: CGFloat) -> Path {
        var path = Path()
        for i in 0..<8 {
            let rr = i % 2 == 0 ? r : r * 0.35
            let a = CGFloat(i) * .pi / 4 - .pi / 2
            let pt = CGPoint(x: rr * cos(a), y: rr * sin(a))
            if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
        }
        path.closeSubpath()
        return path
    }
}

/// Gold medallion frame with sign art, and a profile photo inset when screening cleared it.
struct Medallion: View {
    let signArt: String
    let photo: URL?
    let screening: Screening
    let size: CGFloat

    var body: some View {
        ZStack {
            Image(signArt).resizable().scaledToFill().frame(width: size * 0.78, height: size * 0.78).clipShape(Circle())
            if photo != nil && screening == .safe {
                ProfilePhoto(url: photo, placeholder: signArt)
                    .frame(width: size * 0.42, height: size * 0.42).clipShape(Circle())
                    .background(Circle().fill(Palette.gold))
                    .overlay(Circle().stroke(Palette.gold, lineWidth: 2))
                    .frame(width: size, height: size, alignment: .bottomTrailing)
                    .offset(x: -size * 0.06, y: -size * 0.06)
            }
            Image("match_medallion_frame").resizable().scaledToFit().frame(width: size, height: size)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

// MARK: - Swipe card

/// The face of a match card (SPEC 5d.3): photo (or zodiac art) with the score medallion, first
/// name, age, Sun / Moon / Rising, Chinese animal, relationship status, looking-for, kind chip.
struct MatchCardFace: View {
    let card: StoredEncounter
    let photo: URL?
    var blurb: String?
    var photoSize: CGFloat?

    var body: some View {
        VStack(spacing: 4) {
            ScreenedPhoto(url: photo, screening: card.photoScreening, placeholder: Art.signKey(card.sun))
                .frame(width: photoSize, height: photoSize)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(Circle())
                .overlay(alignment: .bottomTrailing) { ScoreMedallion(total: card.total) }
                .accessibilityLabel(photo != nil ? "Photo of \(card.firstName ?? "your match")" : "\(CardText.sign(card.sun)) artwork")
            GoldText((card.exactTwin ? "Exact twin · " : card.birthdayTwin ? "Birthday twin · " : "")
                     + "\(card.firstName ?? "Someone nearby")" + (card.age > 0 ? ", \(card.age)" : ""), style: .headlineSmall)
                .padding(.top, 8)
            Text(CardText.signsLine(sun: card.sun, moon: card.moon, rising: card.rising))
                .typeStyle(.bodyMedium).foregroundStyle(Palette.goldPale).multilineTextAlignment(.center)
            Text([CardText.animal(card.chineseAnimal), CardText.relationship(card.relationship), CardText.lookingFor(card.lookingFor)]
                .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                .typeStyle(.bodySmall).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
            KindChip(kind: card.kind).padding(.top, 6)
            if let bio = card.bio {
                Text("“\(bio)”").typeStyle(.bodySmall).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center).lineLimit(2)
                    .padding(.top, 6)
            }
            if let blurb { Text(blurb).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim).multilineTextAlignment(.center).lineLimit(3) }
        }
    }
}

/// A swipeable framed card: right = accept, left = pass, with the hint stamps; accessibility
/// actions for both. Buttons Pass / ☌ Chart / Accept under it.
struct SwipeableMatchCard: View {
    let card: StoredEncounter
    let photo: URL?
    let onAnswer: (Bool) -> Void
    let onChart: () -> Void
    var blurb: String?
    @State private var offsetX: CGFloat = 0
    @State private var answered = false
    private let threshold: CGFloat = 120

    private func answer(_ accept: Bool) {
        guard !answered else { return }
        answered = true
        withAnimation(.easeIn(duration: 0.32)) { offsetX = accept ? threshold * 4 : -threshold * 4 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.32) { onAnswer(accept) }
    }

    var body: some View {
        VStack(spacing: 0) {
            cardView
                .aspectRatio(0.70, contentMode: .fit)
                .frame(maxWidth: 420)
                .offset(x: offsetX)
                .rotationEffect(.degrees(Double(offsetX / 40)))
                .gesture(
                    DragGesture()
                        .onChanged { if !answered { offsetX = $0.translation.width } }
                        .onEnded { _ in
                            if offsetX > threshold { answer(true) } else if offsetX < -threshold { answer(false) } else {
                                withAnimation(.spring()) { offsetX = 0 }
                            }
                        }
                )
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Match card for \(card.firstName ?? "someone nearby"), \(card.total) percent compatible.")
                .accessibilityHint(L10n.matchCardAccessibilityHint)
                .accessibilityAction(named: L10n.matchCardAccept) { answer(true) }
                .accessibilityAction(named: L10n.matchCardReject) { answer(false) }
            HStack(spacing: 8) {
                GhostButton(L10n.matchCardReject, color: Palette.roseCopper, enabled: !answered) { answer(false) }
                GhostButton(ChartWheelGeometry.textGlyph("☌") + " " + AppText.matchCardChart, action: onChart)
                GoldButton(L10n.matchCardAccept, enabled: !answered) { answer(true) }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: 420)
            .padding(.top, 10)
        }
    }

    private var cardView: some View {
        ZStack {
            LinearGradient(colors: [Palette.inkViolet, Palette.midnight], startPoint: .top, endPoint: .bottom).padding(12)
            GeometryReader { geo in
                MatchCardFace(card: card, photo: photo, blurb: blurb,
                              photoSize: min(geo.size.width * 0.86, geo.size.height * 0.55))
                    .frame(width: geo.size.width)
            }
            .padding(.horizontal, 34).padding(.vertical, 38)
            Image("frame_swipe_card").resizable().allowsHitTesting(false)
            let accept = min(max(offsetX / threshold, 0), 1)
            let reject = min(max(-offsetX / threshold, 0), 1)
            if accept > 0 {
                Text(L10n.matchCardAccept.uppercased()).typeStyle(.headlineMedium).foregroundStyle(Palette.gold)
                    .rotationEffect(.degrees(-15)).opacity(accept)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(40)
            }
            if reject > 0 {
                Text(L10n.matchCardReject.uppercased()).typeStyle(.headlineMedium).foregroundStyle(Palette.roseCopper)
                    .rotationEffect(.degrees(15)).opacity(reject)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(40)
            }
        }
    }
}

/// The full-screen swipe card after the match animation (popup / notification).
struct SwipeCardPage: View {
    let card: StoredEncounter
    let photo: URL?
    let blurb: String?
    let onSwipe: (Bool) -> Void
    let onChart: () -> Void
    let onMenu: (CardMenuAction) -> Void
    let onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                HStack {
                    GhostButton("Close", fullWidth: false, action: onClose)
                    Spacer()
                    CardMenu(onAction: onMenu)
                }
                SwipeableMatchCard(card: card, photo: photo, onAnswer: onSwipe, onChart: onChart, blurb: blurb)
                    .padding(.top, 10)
                FinePrint(L10n.matchCardAccessibilityHint, alignment: .center).padding(.top, 8)
            }
            .padding(16)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.midnight.ignoresSafeArea())
    }
}

// MARK: - It's a Conjunction!

/// "It's a Conjunction!": the friend's photo pops up; Say hi (chat) or See our chart (SPEC 5d.4).
private struct ConjunctionPopup: View {
    @ObservedObject private var repo = AppModel.shared.nearby
    let friendId: String
    let card: StoredEncounter?
    let onOpenChat: (ChatTarget) -> Void
    let onOpenCompat: () -> Void
    let onClose: () -> Void
    @State private var pop: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let summary = repo.friend(friendId)
        let f = summary?.friend
        let name = f?.firstName ?? card?.firstName ?? "your match"
        let sun = f?.sun ?? card?.sun
        ZStack {
            Image("popup_its_a_conjunction").resizable().scaledToFill().opacity(0.55).ignoresSafeArea().accessibilityHidden(true)
            ScrollView {
                VStack(spacing: 12) {
                    Spacer().frame(height: 24)
                    GoldText("It's a Conjunction!", style: .displaySmall, heading: true)
                    OrnateDivider()
                    if let f, f.photoFile != nil {
                        ScreenedPhoto(url: repo.photoURL(f.photoFile), screening: f.photoScreening, placeholder: Art.signKey(sun))
                            .frame(width: 190, height: 190).clipShape(Circle()).scaleEffect(pop)
                            .accessibilityLabel("Photo of \(name)")
                    } else {
                        Medallion(signArt: Art.signKey(sun), photo: nil, screening: .safe, size: 200).scaleEffect(pop)
                    }
                    GoldText(name, style: .headlineSmall)
                    Text("You and \(name) both said yes.").typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone)
                        .multilineTextAlignment(.center)
                    if let total = summary?.total ?? card?.total {
                        Text("\(total)% compatible").typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                    }
                    GoldButton(AppText.matchPopupSayHi) { onOpenChat(ChatTarget(friendId: friendId)) }
                        .frame(maxWidth: 420).padding(.top, 8)
                    GhostButton(ChartWheelGeometry.textGlyph("☌") + " " + AppText.matchPopupSeeChart, action: onOpenCompat)
                        .frame(maxWidth: 420)
                    Button(action: onClose) {
                        Text("Later").typeStyle(.labelLarge).foregroundStyle(Palette.moonstoneDim).padding(12)
                    }
                    .buttonStyle(.plain)
                }
                .padding(24)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
        }
        .onAppear {
            if reduceMotion { pop = 1 } else { withAnimation(.spring(response: 0.6, dampingFraction: 0.45)) { pop = 1 } }
        }
    }
}
