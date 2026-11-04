import SwiftUI
import ConjunctionCore

/// Shown on reading tabs until birth details exist.
struct NoBirthData: View {
    var body: some View {
        OrnatePanel(alignment: .center) {
            Text("Add your birth date, time and place in Profile to cast your chart.")
                .typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
        }
    }
}

private func glyph(_ g: String) -> String { ChartWheelGeometry.textGlyph(g) }

private struct BigThree: View {
    let natal: NatalChart

    var body: some View {
        let items: [(String, ZodiacSign)] = [("Sun", natal.sunSign), ("Moon", natal.moonSign)] + (natal.risingSign.map { [("Rising", $0)] } ?? [])
        HStack {
            ForEach(items, id: \.0) { item in
                VStack(spacing: 2) {
                    ArtMedallion(art: Art.sign(item.1), size: 96, label: "\(item.0) in \(item.1.displayName)")
                    Text(item.0.uppercased()).typeStyle(.labelSmall).foregroundStyle(Palette.gold)
                    Text(item.1.displayName).typeStyle(.titleMedium).foregroundStyle(Palette.moonstone)
                }
                .frame(maxWidth: .infinity)
            }
        }
    }
}

struct ChartScreen: View {
    @EnvironmentObject var app: AppModel

    private func subtitle(_ c: OwnerCharts) -> String {
        "\(c.birth.placeName) · \(PickerDates.longText(c.birth.date))" + (c.birth.time.map { " · \(PickerDates.timeText($0))" } ?? "")
    }

    var body: some View {
        TabPage(title: "Your Chart", subtitle: app.charts.map(subtitle)) {
            if let charts = app.charts {
                let natal = charts.natal
                BigThree(natal: natal)
                OrnatePanel(padding: 10) { ChartWheel(chart: natal).frame(maxWidth: .infinity) }
                if natal.ascendant == nil {
                    FinePrint("Birth time unknown: the wheel starts at 0° Aries and has no houses or Ascendant." +
                              (natal.moonSignUncertain ? " Your Moon changed sign that day, so the Moon sign is uncertain." : ""))
                }
                OrnatePanel {
                    PanelHeading(text: "Placements")
                    OrnateDivider().frame(maxWidth: .infinity).padding(.vertical, 4)
                    ForEach(natal.bodies, id: \.planet) { body in
                        PlacementRow(glyph: glyph(body.planet.glyph), name: body.name + (body.retrograde ? " ℞" : ""),
                                     position: formatLongitude(body.longitude), house: body.house.map { "House \($0)" } ?? "")
                    }
                    if let asc = natal.ascendant { PlacementRow(glyph: "AC", name: "Ascendant", position: formatLongitude(asc), house: "") }
                    if let mc = natal.midheaven { PlacementRow(glyph: "MC", name: "Midheaven", position: formatLongitude(mc), house: "") }
                    FinePrint("Tropical zodiac, \(natal.houseSystem.rawValue.lowercased().replacingOccurrences(of: "_", with: "-")) houses" +
                              (natal.housesFellBack ? " (Porphyry at this latitude)" : "") + ". Zone \(charts.birth.zoneId).")
                }
                if !natal.aspects.isEmpty {
                    OrnatePanel {
                        PanelHeading(text: "Aspects")
                        OrnateDivider().frame(maxWidth: .infinity).padding(.vertical, 4)
                        ForEach(Array(natal.aspects.prefix(14).enumerated()), id: \.offset) { _, a in
                            HStack(alignment: .firstTextBaseline) {
                                Text(glyph(a.type.glyph)).typeStyle(.bodyLarge).foregroundStyle(Palette.goldPale).frame(width: 30, alignment: .leading)
                                Text(a.label).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                                Spacer()
                                Text(String(format: "%.1f°", a.orb) + (a.applying ? " app." : " sep."))
                                    .typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim)
                            }
                            .padding(.vertical, 3)
                        }
                    }
                }
                NatalReadings(charts: charts)
            } else {
                NoBirthData()
            }
        }
    }
}

private struct PlacementRow: View {
    let glyph: String, name: String, position: String, house: String

    var body: some View {
        HStack {
            Text(glyph).typeStyle(.titleLarge).foregroundStyle(Palette.goldPale).frame(width: 36, alignment: .leading)
            Text(name).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone)
            Spacer(minLength: 4)
            Text(position).typeStyle(.bodyMedium).foregroundStyle(Palette.goldPale).multilineTextAlignment(.trailing)
            Text(house).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim).frame(width: 64, alignment: .trailing)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

/// Natal reading: one section at a time, written on demand and cached.
private struct NatalReadings: View {
    @EnvironmentObject var app: AppModel
    let charts: OwnerCharts
    @State private var states: [NatalSection: ReadingState] = [:]
    @State private var open: NatalSection?

    var body: some View {
        GoldText("Natal Reading", style: .headlineSmall, heading: true)
        ForEach(NatalSection.allCases, id: \.self) { section in
            let isOpen = open == section
            Button {
                open = isOpen ? nil : section
                if !isOpen, !isReady(section) { load(section, refresh: false) }
            } label: {
                OrnatePanel(padding: 16) {
                    HStack {
                        Text(section.planet.map { glyph($0.glyph) } ?? "AC").typeStyle(.titleLarge).foregroundStyle(Palette.goldPale)
                            .frame(width: 36, alignment: .leading)
                        Text(section.title).typeStyle(.titleMedium).foregroundStyle(Palette.moonstone)
                        Spacer()
                        Text(isOpen ? "−" : "+").typeStyle(.titleLarge).foregroundStyle(Palette.gold)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityHint(isOpen ? "Close" : "Read")
            if isOpen {
                ReadingCard(title: section.title, state: states[section] ?? .loading(), onRefresh: { load(section, refresh: true) })
            }
        }
        .onChange(of: charts.fingerprint) { _, _ in states = [:]; open = nil }
    }

    private func isReady(_ s: NatalSection) -> Bool { if case .ready = states[s] { return true } else { return false } }

    private func load(_ section: NatalSection, refresh: Bool) {
        states[section] = .loading()
        Task {
            let r = await app.readings.natal(charts, section: section, refresh: refresh) { status in
                Task { @MainActor in states[section] = .loading(status) }
            }
            states[section] = .ready(r)
        }
    }
}

struct TodayScreen: View {
    @EnvironmentObject var app: AppModel
    @State private var state: ReadingState = .loading()
    private let today = LocalDate.todayInCurrentTimeZone()

    private var isReady: Bool {
        if case .ready = state { return true }
        return false
    }

    private var fullDate: String {
        let f = DateFormatter()
        f.dateStyle = .full
        return f.string(from: Date())
    }

    var body: some View {
        TabPage(title: "Today", subtitle: fullDate) {
            if let charts = app.charts {
                let natal = charts.natal
                ArtMedallion(art: Art.sign(natal.sunSign), size: 170, label: "\(natal.sunSign.displayName) sun")
                Text("\(natal.sunSign.displayName) sun · \(natal.moonSign.displayName) moon" + (natal.risingSign.map { " · \($0.displayName) rising" } ?? ""))
                    .typeStyle(.titleMedium).foregroundStyle(Palette.goldPale).multilineTextAlignment(.center)
                ReadingCard(title: "Your day", state: state, onRefresh: { load(charts, refresh: true) })
                if isReady { NotificationsCard() }
                SkyNow(date: today)
            } else {
                NoBirthData()
            }
        }
        .task(id: app.charts?.fingerprint) {
            if let c = app.charts { load(c, refresh: false) }
        }
    }

    private func load(_ charts: OwnerCharts, refresh: Bool) {
        state = .loading()
        Task {
            let r = await app.readings.daily(charts, date: today, zone: .current, refresh: refresh) { status in
                Task { @MainActor in state = .loading(status) }
            }
            state = .ready(r)
        }
    }
}

/// One-time card on Today (SPEC 5d.3.2): "Get your reading each morning?" after the first reading
/// is shown, if notifications were never asked. Allow -> system prompt and the daily reminder;
/// Not now -> never shown again.
private struct NotificationsCard: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        if !app.profile.notificationsPrompted {
            OrnatePanel(padding: 18) {
                Text(L10n.permNotificationsTitle).typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                Text(L10n.permNotificationsBody).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).padding(.vertical, 6)
                HStack(spacing: 10) {
                    GoldButton(L10n.permNotificationsButton) {
                        Task {
                            let granted = await Notifications.requestAuthorization()
                            app.update { $0.notificationsPrompted = true; if granted { $0.dailyReminder = true } }
                        }
                    }
                    GhostButton(AppText.notNow) { app.update { $0.notificationsPrompted = true } }
                }
            }
        }
    }
}

/// Readings (SPEC 5d.2): Eastern and Palm under one "Eastern | Palm" segment control.
enum ReadingsSegment: String { case eastern, palm }

struct ReadingsScreen: View {
    @Binding var segment: ReadingsSegment

    var body: some View {
        let header = HStack {
            Spacer()
            ChoiceChips(options: [(ReadingsSegment.eastern, AppText.readingsSegmentEastern), (.palm, AppText.readingsSegmentPalm)],
                        selected: segment) { segment = $0 }
                .fixedSize()
            Spacer()
        }
        Group {
            switch segment {
            case .eastern: EasternScreen()
            case .palm: PalmScreen()
            }
        }
        .environment(\.tabHeader, AnyView(header))
    }
}

/// Where the planets are today (sign per body), as a compact table.
private struct SkyNow: View {
    let date: LocalDate

    var body: some View {
        let noon = Transits.noonOf(date, .current)
        let sky = Planet.allCases.map { p in (p, ZodiacSign.of(Ephemeris.position(p, noon).longitude)) }
        OrnatePanel {
            PanelHeading(text: "Today's sky")
            ForEach(0..<((sky.count + 1) / 2), id: \.self) { row in
                HStack {
                    ForEach(sky[(row * 2)..<min(row * 2 + 2, sky.count)], id: \.0) { entry in
                        Text("\(glyph(entry.0.glyph)) \(entry.0.displayName) in \(entry.1.displayName)")
                            .typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.vertical, 3)
            }
        }
    }
}

struct EasternScreen: View {
    @EnvironmentObject var app: AppModel
    @State private var state: ReadingState = .loading()

    var body: some View {
        TabPage(title: "Eastern Sky", subtitle: "Chinese zodiac, Four Pillars and blood type") {
            if let charts = app.charts {
                let chinese = charts.chinese
                HStack(alignment: .top) {
                    VStack {
                        ArtMedallion(art: Art.animal(chinese.yearAnimal), size: 150, label: chinese.yearAnimal.displayName)
                        Text("\(chinese.yinYang.displayName) \(chinese.yearElement.displayName) \(chinese.yearAnimal.displayName)")
                            .typeStyle(.titleMedium).foregroundStyle(Palette.goldPale).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    VStack {
                        ArtMedallion(art: Art.element(chinese.yearElement), size: 96, label: "\(chinese.yearElement.displayName) element")
                        Text("\(chinese.yearElement.hanzi) \(chinese.yearElement.displayName)").typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                    }
                    .frame(maxWidth: .infinity)
                }
                if chinese.yearsDiffer {
                    FinePrint("Born before the solar year began at Lichun, you are a \(chinese.yearAnimal.displayName) by the solar calendar; by the lunar new year you are a \(chinese.lunarYearElement.displayName) \(chinese.lunarYearAnimal.displayName).")
                }
                if chinese.boundaryUncertain {
                    FinePrint("Your birth falls within minutes of a solar-term boundary, so a pillar may differ by convention.")
                }
                OrnatePanel {
                    PanelHeading(text: "Four Pillars · BaZi")
                    OrnateDivider().frame(maxWidth: .infinity).padding(.vertical, 4)
                    HStack(alignment: .top) {
                        PillarColumn(label: "Hour", pillar: chinese.pillars.hour)
                        PillarColumn(label: "Day", pillar: chinese.pillars.day)
                        PillarColumn(label: "Month", pillar: chinese.pillars.month)
                        PillarColumn(label: "Year", pillar: chinese.pillars.year)
                    }
                    FinePrint("Day Master: \(chinese.dayMaster.hanzi) \(chinese.dayMaster.pinyin) — \(chinese.dayMaster.label).")
                }
                if let blood = charts.bloodType {
                    let traits = BloodTypeReading.traits(blood)
                    OrnatePanel {
                        HStack(spacing: 12) {
                            ArtMedallion(art: Art.blood(blood), size: 88, label: "Blood type \(blood.displayName)")
                            VStack(alignment: .leading, spacing: 2) {
                                Text(BloodTypeReading.label(blood, app.profile.rh).uppercased()).typeStyle(.labelMedium).foregroundStyle(Palette.gold)
                                Text(traits.title).typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                                Text(traits.keywords.joined(separator: " · ")).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim)
                            }
                        }
                        Text(traits.summary).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).padding(.top, 8)
                        FinePrint("Ketsueki-gata is East Asian folklore, not science, and says nothing about health.")
                    }
                } else {
                    FinePrint("Add your blood type in Profile for a ketsueki-gata (blood-type) reading.")
                }
                ReadingCard(title: "Eastern reading", state: state, onRefresh: { load(charts, refresh: true) })
            } else {
                NoBirthData()
            }
        }
        .task(id: "\(app.charts?.fingerprint ?? "-")\(app.profile.rh?.rawValue ?? "")") {
            if let c = app.charts { load(c, refresh: false) }
        }
    }

    private func load(_ charts: OwnerCharts, refresh: Bool) {
        state = .loading()
        let rh = app.profile.rh
        Task {
            let r = await app.readings.eastern(charts, rh: rh, refresh: refresh) { status in
                Task { @MainActor in state = .loading(status) }
            }
            state = .ready(r)
        }
    }
}

private struct PillarColumn: View {
    let label: String
    let pillar: Pillar?

    var body: some View {
        VStack(spacing: 2) {
            Text(label.uppercased()).typeStyle(.labelSmall).foregroundStyle(Palette.gold)
            if let p = pillar {
                Text(p.stem.hanzi).typeStyle(.headlineMedium).foregroundStyle(Palette.goldPale)
                Text(p.branch.hanzi).typeStyle(.headlineMedium).foregroundStyle(Palette.moonstone)
                Text(p.stem.label).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim).multilineTextAlignment(.center)
                Text(p.branch.animal.displayName).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim)
            } else {
                Text("—").typeStyle(.headlineMedium).foregroundStyle(Palette.moonstoneDim)
                Text("time\nunknown").typeStyle(.bodySmall, italic: true).foregroundStyle(Palette.moonstoneDim).multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(4)
        .accessibilityElement(children: .combine)
    }
}
