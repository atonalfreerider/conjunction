import SwiftUI
import ConjunctionCore

/// Compatibility view (SPEC 5d.4; Android `ui/match/CompatibilityScreen.kt`): medallions and score,
/// the synastry bi-wheel (or the "next time you're near" placeholder for friends made before UX
/// 2.0), score breakdown, highlights and aspects, the Eastern relations and the on-device reading
/// (generated once, cached on the record). Reachable from the swipe card, the waiting card, the
/// ConjunctionPopup, the Conjunctions ☌ chip and the chat header, for as long as the record exists.
/// Never shows the other person's birth date or hour.
struct CompatibilityScreen: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    /// Friend id or encounter fingerprint.
    let key: String
    let onClose: () -> Void

    var body: some View {
        StarfieldBackground(dim: 0.72) {
            if let subject = repo.compatSubject(key) {
                CompatContent(subject: subject, onClose: onClose)
            } else {
                VStack(spacing: 16) {
                    Text("This match is no longer available.").typeStyle(.titleMedium).foregroundStyle(Palette.moonstone)
                        .multilineTextAlignment(.center)
                    GhostButton("Close", fullWidth: false, action: onClose)
                }
                .padding(32)
            }
        }
    }
}

private struct CompatContent: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject private var repo = AppModel.shared.nearby
    let subject: CompatSubject
    let onClose: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown: Double = 0
    @State private var reading: Reading?
    @State private var report = false

    var body: some View {
        let s = subject
        let me = app.myCompat
        let view = CompatView.of(s, me: me)
        VStack(spacing: 0) {
            PageHeader(title: L10n.format(AppText.compatTitle, s.name), onBack: onClose) {
                MoreMenu(label: "More") {
                    Button(L10n.reportReading) { report = true }
                }
            }
            ScrollView {
                VStack(spacing: 14) {
                    // 1. Medallions joined by the conjunction glyph, total score and kind chip.
                    HStack(spacing: 0) {
                        ProfilePhoto(url: app.ownerPhotoURL, placeholder: app.charts.map { Art.sign($0.natal.sunSign) } ?? "brand_mark")
                            .frame(width: 92, height: 92).clipShape(Circle()).overlay(Circle().stroke(GoldBrush.leaf, lineWidth: 2))
                            .accessibilityLabel("You")
                        Text("  " + ChartWheelGeometry.textGlyph("☌") + "  ").typeStyle(.displaySmall).foregroundStyle(Palette.gold)
                            .accessibilityHidden(true)
                        ScreenedPhoto(url: repo.photoURL(s.photoFile), screening: s.photoScreening, placeholder: Art.signKey(s.sun), compact: true)
                            .frame(width: 92, height: 92).clipShape(Circle()).overlay(Circle().stroke(GoldBrush.leaf, lineWidth: 2))
                            .accessibilityLabel(s.name)
                    }
                    // A friend from before UX 2.0 with no stored numbers: no "0%" score, only the placeholder below.
                    if s.hasScore { CountUpScore(total: view.score.total, progress: shown) }
                    HStack(spacing: 8) {
                        KindChip(kind: s.kind)
                        if view.score.birthdayTwin { KindChip(label: view.score.exactTwin ? "Exact twins" : "Birthday twins") }
                        if let h = s.peerIg { InstagramChip(handle: h) }
                    }
                    // 2. The synastry bi-wheel, or the placeholder for friends made before UX 2.0.
                    OrnatePanel(padding: 12, alignment: .center) {
                        if let mine = view.me, let theirs = view.them {
                            SynastryWheel(me: mine, them: theirs, lines: view.lines)
                            FinePrint(L10n.format(AppText.compatLegend, s.name), alignment: .center).padding(.top, 6)
                        } else {
                            ZStack {
                                Image("popup_friend_connected").resizable().scaledToFit().frame(width: 150, height: 150)
                                    .clipShape(Circle()).opacity(0.22).accessibilityHidden(true)
                                Text(L10n.format(AppText.compatWheelPending, s.name)).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone)
                                    .multilineTextAlignment(.center).padding(16)
                            }
                            .frame(maxWidth: .infinity, minHeight: 180)
                        }
                    }
                    // 3. Score breakdown (only when there are numbers).
                    if s.hasScore {
                    OrnatePanel(padding: 18) {
                        ScoreBar(label: AppText.compatWestern, value: view.score.western)
                        ScoreBar(label: AppText.compatChinese, value: view.score.chinese)
                        if let blood = view.score.blood { ScoreBar(label: AppText.compatBlood, value: blood) }
                        FinePrint("Weights: Western \(Compatibility.weightWestern) · Chinese \(Compatibility.weightChinese)"
                                  + (view.score.blood != nil ? " · Blood type \(Compatibility.weightBlood)" : "")).padding(.top, 4)
                    }
                    }
                    // 4. Highlights and the aspect list.
                    if !view.score.highlights.isEmpty || !view.lines.isEmpty {
                        OrnatePanel(padding: 18) {
                            ForEach(view.score.highlights, id: \.self) { h in
                                Text("✦ \(h)").typeStyle(.bodyMedium).foregroundStyle(Palette.goldPale)
                            }
                            if !view.lines.isEmpty {
                                Text(AppText.compatAspectsTitle.uppercased()).typeStyle(.titleSmall).foregroundStyle(Palette.gold).padding(.top, 8)
                                ForEach(Array(view.aspectRows.enumerated()), id: \.offset) { _, l in
                                    HStack(spacing: 6) {
                                        Text(CompatView.pointGlyph(l.pointA)).foregroundStyle(Palette.goldPale).frame(width: 26)
                                        Text(CompatView.aspectRow(l)).typeStyle(.bodySmall)
                                            .foregroundStyle(l.isHarmonious ? Palette.moonstone : Palette.roseCopper)
                                    }
                                }
                            }
                        }
                    }
                    // 5. Eastern relations with the two animal arts.
                    if view.chineseRelation != nil || view.stemRelation != nil {
                        OrnatePanel(padding: 18) {
                            Text(AppText.compatEasternTitle.uppercased()).typeStyle(.titleSmall).foregroundStyle(Palette.gold)
                            HStack(spacing: 6) {
                                if let mine = app.charts?.chinese.yearAnimal {
                                    Image(Art.animal(mine)).resizable().scaledToFill().frame(width: 56, height: 56).clipShape(Circle())
                                        .accessibilityLabel(mine.displayName)
                                }
                                Image(Art.animalKey(s.animal)).resizable().scaledToFill().frame(width: 56, height: 56).clipShape(Circle())
                                    .accessibilityLabel(CardText.animal(s.animal))
                                VStack(alignment: .leading, spacing: 2) {
                                    if let r = view.chineseRelation {
                                        Text(CompatView.chineseRelationLabel(r)).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                                    }
                                    if let r = view.stemRelation {
                                        Text(CompatView.stemRelationLabel(r)).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                                    }
                                }
                                .padding(.leading, 6)
                            }
                            .padding(.top, 6)
                        }
                    }
                    // 6. The reading.
                    if let r = reading {
                        OrnatePanel(padding: 18) {
                            Text(AppText.compatReadingTitle.uppercased()).typeStyle(.titleSmall).foregroundStyle(Palette.gold)
                            OrnateDivider().frame(maxWidth: .infinity)
                            Text(r.text).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).fixedSize(horizontal: false, vertical: true)
                            FinePrint(r.source == .onDeviceAI ? AppText.aiSourceDevice : AppText.aiSourceFallback).padding(.top, 6)
                        }
                    }
                    // 7. The one entertainment line.
                    FinePrint(L10n.matchCardEntertainmentNote, alignment: .center).padding(.bottom, 24)
                }
                .frame(maxWidth: 640)
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity)
            }
        }
        .task(id: s.key) { await loadReading(view) }
        .onAppear {
            // The score counts up on the first open of this record only.
            let first = repo.compatOpened.insert(s.key).inserted
            if first && !reduceMotion {
                shown = 0
                withAnimation(.easeOut(duration: 1.4)) { shown = 1 }
            } else {
                shown = 1
            }
        }
        .sheet(isPresented: $report) {
            ReportReadingSheet(title: L10n.format(AppText.compatTitle, s.name), text: reading?.text ?? "")
        }
    }

    /// The cached reading when it was made for these two profiles, else written once and saved.
    private func loadReading(_ view: CompatView) async {
        let s = subject
        if let b = s.blurb { reading = Reading(text: b.text, source: b.source == "model" ? .onDeviceAI : .fallback) }
        guard let a = view.me, let b = view.them else { return }
        let hash = CompatView.blurbHash(me: a, them: b, kind: s.kind)
        if s.blurb?.forHash == hash { return }
        let kind: MatchKind = s.kind == "dating" ? .dating : .friends
        let result = Compatibility.score(a, b)
        reading = Reading(text: FallbackReadings.compatibility(a, b, result, kind), source: .fallback)
        let written = await app.readings.compatibility(a, b, result, kind: kind)
        guard !Task.isCancelled else { return }
        reading = written
        repo.saveBlurb(s.key, StoredBlurb(text: written.text, source: written.source == .onDeviceAI ? "model" : "fallback", forHash: hash))
    }
}

/// The big total, counting up with [progress] (animatable, so only the text changes per frame).
private struct CountUpScore: View, Animatable {
    let total: Int
    var progress: Double

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        GoldText("\(Int((Double(total) * progress).rounded()))%", style: .displayMedium)
            .accessibilityLabel("\(total) percent compatible")
    }
}

private struct ScoreBar: View {
    let label: String
    let value: Int

    var body: some View {
        VStack(spacing: 4) {
            HStack {
                Text(label).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                Spacer()
                Text("\(value)").typeStyle(.titleSmall).foregroundStyle(Palette.goldPale)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    CutCornerShape(cut: 4).fill(Palette.midnight)
                    Rectangle().fill(GoldBrush.leafHorizontal)
                        .frame(width: geo.size.width * CGFloat(min(max(value, 0), 100)) / 100)
                }
            }
            .frame(height: 8)
            .clipShape(CutCornerShape(cut: 4))
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
