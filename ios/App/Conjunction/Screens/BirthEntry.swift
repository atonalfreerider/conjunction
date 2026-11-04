import SwiftUI
import ConjunctionCore

/// Converts between `LocalDate` and the Gregorian `Date` a DatePicker shows (at noon UTC, so no
/// zone can move it to another day).
enum PickerDates {
    static let utc = TimeZone(identifier: "UTC")!
    static var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = utc
        return c
    }

    static func date(_ d: LocalDate) -> Date { ZonedDateTime.instant(d, LocalTime(12, 0), utc) }
    static func local(_ date: Date) -> LocalDate { LocalDate.of(date, utc) }

    static func longText(_ d: LocalDate) -> String {
        let f = DateFormatter()
        f.dateStyle = .long
        f.timeZone = utc
        return f.string(from: date(d))
    }

    /// "7 Aug 1994" (the confirm chip, Android `chipDateFormat`).
    static func chipText(_ d: LocalDate) -> String {
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        return "\(d.day) \(months[d.month - 1]) \(d.year)"
    }

    static func timeText(_ t: LocalTime) -> String {
        let f = DateFormatter()
        f.timeStyle = .short
        f.timeZone = utc
        return f.string(from: ZonedDateTime.instant(LocalDate(2000, 1, 1), t, utc))
    }
}

/// Date / time / place chips with their pickers (onboarding screen 2 and Profile › Birth).
struct BirthChips: View {
    let date: LocalDate?
    let time: LocalTime?
    let timeUnknown: Bool
    let place: PlaceResult?
    let placeQuery: String?
    let placeCandidates: [PlaceResult]
    let onDate: (LocalDate) -> Void
    let onTime: (LocalTime?, Bool) -> Void
    let onPlace: (PlaceResult) -> Void
    var ambiguousDate = false
    var working = false
    var searchingPlace = false
    @State private var picking: Picking?

    enum Picking: String, Identifiable {
        case date, time, place
        var id: String { rawValue }
    }

    var body: some View {
        FlowLayout(spacing: 8) {
            ConfirmChip(value: date.map(PickerDates.chipText), placeholder: AppText.onbChipAddDate,
                        highlight: ambiguousDate && date != nil, working: working && date == nil) { picking = .date }
            ConfirmChip(value: timeUnknown ? AppText.onbChipTimeUnknown : time.map(PickerDates.timeText), placeholder: AppText.onbChipAddTime,
                        working: working && time == nil && !timeUnknown) { picking = .time }
            ConfirmChip(value: place?.name, placeholder: AppText.onbChipAddPlace, working: (working || searchingPlace) && place == nil) {
                picking = .place
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .sheet(item: $picking) { which in
            switch which {
            case .date:
                BirthDateSheet(initial: date) { onDate($0) }
            case .time:
                BirthTimeSheet(initial: time, unknown: timeUnknown) { t, unknown in onTime(t, unknown) }
            case .place:
                PlaceSheet(date: date ?? LocalDate(1995, 1, 1), time: time, initialQuery: placeQuery ?? place?.name ?? "",
                           initialResults: placeCandidates) { onPlace($0) }
            }
        }
    }
}

/// Birth date picker: nothing pre-selected (it opens at 1995-01-01, SPEC 5d.1.2), 1900-01-01 to
/// today. The 18+ rule is checked on Begin, not by limiting the picker (a neutral age screen).
struct BirthDateSheet: View {
    let initial: LocalDate?
    let onPicked: (LocalDate) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value = Date()

    var body: some View {
        let today = LocalDate.todayInCurrentTimeZone()
        OrnateSheet(title: "Birth date", onClose: { dismiss() }) {
            DatePicker("", selection: $value, in: PickerDates.date(BirthTextParser.earliestDate)...PickerDates.date(today),
                       displayedComponents: .date)
                .datePickerStyle(.wheel)
                .labelsHidden()
                .environment(\.timeZone, PickerDates.utc)
                .environment(\.calendar, PickerDates.calendar)
                .colorScheme(.dark)
                .frame(maxWidth: .infinity)
            GoldButton("OK") { onPicked(PickerDates.local(value)); dismiss() }
            GhostButton("Cancel") { dismiss() }
        }
        .presentationDetents([.medium, .large])
        .onAppear { value = PickerDates.date(initial ?? LocalDate(1995, 1, 1)) }
    }
}

/// Time picker with the "I don't know my birth time" switch.
struct BirthTimeSheet: View {
    let initial: LocalTime?
    let unknown: Bool
    let onDone: (LocalTime?, Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value = Date()
    @State private var dontKnow = false

    var body: some View {
        OrnateSheet(title: "Birth time", onClose: { dismiss() }) {
            if !dontKnow {
                DatePicker("", selection: $value, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel).labelsHidden()
                    .environment(\.timeZone, PickerDates.utc)
                    .colorScheme(.dark)
                    .frame(maxWidth: .infinity)
            }
            GoldToggleRow(isOn: $dontKnow, title: "I don't know my birth time",
                          subtitle: "Your chart skips the Ascendant, houses and the hour pillar. Add it later in Profile.")
            GoldButton("OK") {
                if dontKnow {
                    onDone(nil, true)
                } else {
                    let z = ZonedDateTime(value, PickerDates.utc)
                    onDone(LocalTime(z.hour, z.minute), false)
                }
                dismiss()
            }
            GhostButton("Cancel") { dismiss() }
        }
        .presentationDetents([.medium, .large])
        .onAppear {
            dontKnow = unknown
            value = ZonedDateTime.instant(LocalDate(2000, 1, 1), initial ?? LocalTime(12, 0), PickerDates.utc)
        }
    }
}

/// Place search sheet: up to 5 results ("Change"), or coordinates. Geocoded on the phone; the time
/// zone comes with the placemark.
struct PlaceSheet: View {
    let date: LocalDate
    let time: LocalTime?
    let initialQuery: String
    let initialResults: [PlaceResult]
    let onChosen: (PlaceResult) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [PlaceResult] = []
    @State private var searching = false
    @State private var error: String?
    @State private var manual = false
    @State private var latText = ""
    @State private var lonText = ""
    @State private var loaded = false

    var body: some View {
        OrnateSheet(title: "Where you were born", onClose: { dismiss() }) {
            OrnateTextField(label: "City or town of birth", text: $query, onSubmit: { if canSearch { search() } })
            GoldButton(searching ? "Searching…" : "Search", enabled: canSearch, action: search)
            if searching { OrreryLoader(size: 40).frame(maxWidth: .infinity) }
            if let error { Text(error).typeStyle(.bodyMedium).foregroundStyle(Palette.roseCopper) }
            ForEach(results) { place in
                Button { choose(place) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(place.name).typeStyle(.titleMedium).foregroundStyle(Palette.moonstone).multilineTextAlignment(.leading)
                        Text("\(place.zoneId) · \(place.offsetLabel)").typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            GhostButton(manual ? "Hide coordinates" : "Enter coordinates instead") { manual.toggle() }
            if manual {
                OrnateTextField(label: "Latitude (−90 to 90, north positive)", text: $latText, keyboard: .numbersAndPunctuation)
                OrnateTextField(label: "Longitude (−180 to 180, east positive)", text: $lonText, keyboard: .numbersAndPunctuation)
                GhostButton("Use these coordinates", action: useCoordinates)
            }
        }
        .presentationDetents([.large])
        .onAppear {
            guard !loaded else { return }
            loaded = true
            query = initialQuery
            results = Array(initialResults.prefix(5))
        }
    }

    private var canSearch: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty && !searching }

    private func choose(_ place: PlaceResult) {
        onChosen(place)
        dismiss()
    }

    private func search() {
        searching = true
        error = nil
        Task {
            do {
                results = Array(try await PlaceSearch.search(query, date: date, time: time).prefix(5))
                if results.isEmpty { error = "No places found. Try adding the country." }
            } catch {
                results = []
                self.error = error.localizedDescription
            }
            searching = false
        }
    }

    private func useCoordinates() {
        func number(_ s: String) -> Double? {
            Double(s.replacingOccurrences(of: ",", with: ".").replacingOccurrences(of: "−", with: "-").trimmingCharacters(in: .whitespaces))
        }
        guard let lat = number(latText), let lon = number(lonText), (-90...90).contains(lat), (-180...180).contains(lon) else {
            error = "Enter a latitude between −90 and 90 and a longitude between −180 and 180."
            return
        }
        let label = String(format: "%.4f, %.4f", lat, lon)
        let name = query.trimmingCharacters(in: .whitespaces).isEmpty ? label : query
        Task {
            let place = await PlaceSearch.resolve(name: name, latitude: lat, longitude: lon, date: date, time: time)
            choose(place)
        }
    }
}
