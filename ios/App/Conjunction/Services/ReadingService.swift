import Foundation
import ConjunctionCore

/// Who wrote a reading: the phone's model, or the bundled fallback verses.
enum ReadingSource: String, Codable { case onDeviceAI, fallback }

struct Reading: Equatable {
    let text: String
    let source: ReadingSource
    var note: String?
}

/// UI state of one reading card.
enum ReadingState: Equatable {
    case loading(String = "Consulting the stars…")
    case ready(Reading)
    case failed(String)
}

private struct CachedReading: Codable {
    let text: String
    let source: ReadingSource
    let createdAt: Date
}

/// Builds every reading the app shows (Android `ReadingService`): the prompt from ConjunctionCore's
/// `HoroscopePrompt` / `PalmReadingPrompt` (wording shared with Android), written on the phone by
/// Foundation Models, tidied by `ReadingCleaner`. When the model is unavailable or fails, the
/// deterministic `FallbackReadings` text is used — never a cloud model. Daily, natal and Eastern
/// readings are cached per day / birth-data fingerprint in a local file.
@MainActor
final class ReadingService {
    private let model: OnDeviceModel
    private var cache: [String: CachedReading]

    init(model: OnDeviceModel = .shared) {
        self.model = model
        cache = AppFiles.read([String: CachedReading].self, from: AppFiles.readingsURL) ?? [:]
    }

    private func save() { AppFiles.write(cache, to: AppFiles.readingsURL) }

    func clear() {
        cache = [:]
        try? FileManager.default.removeItem(at: AppFiles.readingsURL)
    }

    private func generateOrFallback(_ prompt: String, fallback: () -> String, onStatus: (String) -> Void) async -> Reading {
        if model.isAvailable {
            onStatus("Writing your reading on this phone…")
            if let raw = try? await model.generate(prompt), let cleaned = ReadingCleaner.clean(raw) {
                return Reading(text: cleaned, source: .onDeviceAI)
            }
        }
        return Reading(text: fallback(), source: .fallback, note: AppText.fallbackNote)
    }

    private func cached(_ key: String, refresh: Bool, produce: () async -> Reading) async -> Reading {
        if !refresh, let c = cache[key] { return Reading(text: c.text, source: c.source) }
        let reading = await produce()
        cache[key] = CachedReading(text: reading.text, source: reading.source, createdAt: Date())
        save()
        return reading
    }

    func daily(_ charts: OwnerCharts, date: LocalDate, zone: TimeZone, refresh: Bool = false,
               onStatus: @escaping (String) -> Void = { _ in }) async -> Reading {
        let reading = await cached("daily:\(date)#\(charts.fingerprint)", refresh: refresh) {
            let natal = charts.natal
            let transits = await Task.detached { Transits.compute(natal, Transits.noonOf(date, zone)) }.value
            return await generateOrFallback(HoroscopePrompt.daily(natal, transits, date),
                                            fallback: { FallbackReadings.daily(natal, transits, date) }, onStatus: onStatus)
        }
        prune(today: date)
        return reading
    }

    func natal(_ charts: OwnerCharts, section: NatalSection, refresh: Bool = false,
               onStatus: @escaping (String) -> Void = { _ in }) async -> Reading {
        await cached("natal:\(section.name)#\(charts.fingerprint)", refresh: refresh) {
            await generateOrFallback(HoroscopePrompt.natal(charts.natal, section),
                                     fallback: { FallbackReadings.natal(charts.natal, section) }, onStatus: onStatus)
        }
    }

    func eastern(_ charts: OwnerCharts, rh: RhFactor?, refresh: Bool = false,
                 onStatus: @escaping (String) -> Void = { _ in }) async -> Reading {
        await cached("eastern#\(charts.fingerprint)\(rh?.rawValue ?? "")", refresh: refresh) {
            await generateOrFallback(HoroscopePrompt.eastern(charts.chinese, charts.bloodType, rh),
                                     fallback: { FallbackReadings.eastern(charts.chinese, charts.bloodType) }, onStatus: onStatus)
        }
    }

    /// Palm readings are not cached: the photo and features are discarded after the reading.
    /// Foundation Models takes text only, so the reading is written from the measurements.
    func palm(_ features: PalmFeatures, onStatus: @escaping (String) -> Void = { _ in }) async -> Reading {
        await generateOrFallback(PalmReadingPrompt.build(features), fallback: { FallbackReadings.palm(features) }, onStatus: onStatus)
    }

    /// One-line blurb for the match animation; falls back to the deterministic line.
    func matchBlurb(_ input: MatchBlurbPrompt.Input) async -> String {
        guard model.isAvailable, let raw = try? await model.generate(MatchBlurbPrompt.build(input)),
              let cleaned = MatchBlurbPrompt.clean(raw) else { return MatchBlurbPrompt.fallback(input) }
        return cleaned
    }

    /// Drops daily entries older than two weeks.
    private func prune(today: LocalDate) {
        let cutoff = today.minusDays(14)
        let before = cache.count
        cache = cache.filter { key, _ in
            guard key.hasPrefix("daily:"), let d = LocalDate.parse(String(key.dropFirst(6).prefix(10))) else { return true }
            return d >= cutoff
        }
        if cache.count != before { save() }
    }
}
