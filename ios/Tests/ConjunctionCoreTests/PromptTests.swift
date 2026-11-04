import Foundation
import XCTest
@testable import ConjunctionCore

/// golden_prompts.json pins the exact prompt wording and fallback text Kotlin produces; Swift must
/// reproduce every string (android/core's PromptTest + PromptFixtures).
final class PromptTests: XCTestCase {
    private var golden: [String: Any] = [:]

    override func setUpWithError() throws {
        golden = try Golden.load("golden_prompts.json")
    }

    private func instant(_ iso: String) -> Date {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: iso)!
    }

    private func birth(_ m: [String: Any]) throws -> BirthData {
        try BirthData(date: date(m.str("date")), time: m.optStr("time").map { LocalTime.parse($0)! },
                      latitude: m.double("latitude"), longitude: m.double("longitude"), zoneId: m.str("zoneId"))
    }

    private func compare(_ actual: String, _ expected: String, _ label: String) {
        if actual != expected {
            let a = Array(actual), e = Array(expected)
            let i = (0..<min(a.count, e.count)).first { a[$0] != e[$0] } ?? min(a.count, e.count)
            XCTFail("\(label) differs at \(i):\n  swift:  …\(String(a[max(0, i - 30)..<min(a.count, i + 60)]))\n  kotlin: …\(String(e[max(0, i - 30)..<min(e.count, i + 60)]))")
        }
    }

    func testChartPromptsAndFallbacksMatchGolden() throws {
        let charts = golden.list("charts")
        XCTAssertFalse(charts.isEmpty)
        for c in charts {
            let id = c.str("id")
            let b = try birth(c.obj("birth"))
            let blood = c.optStr("bloodType").map { BloodType(rawValue: $0)! }
            let chart = NatalChart.compute(b)
            let transits = Transits.compute(chart, instant(c.str("transitInstant")))
            let chinese = ChineseZodiac.compute(b)
            let day = date(c.str("date"))
            let prompts = c.obj("prompts"), fallback = c.obj("fallback")
            compare(HoroscopePrompt.daily(chart, transits, day), prompts.str("daily"), "\(id) prompt daily")
            compare(HoroscopePrompt.eastern(chinese, blood), prompts.str("eastern"), "\(id) prompt eastern")
            compare(FallbackReadings.daily(chart, transits, day), fallback.str("daily"), "\(id) fallback daily")
            compare(FallbackReadings.eastern(chinese, blood), fallback.str("eastern"), "\(id) fallback eastern")
            let natalPrompts = prompts.obj("natal"), natalFallback = fallback.obj("natal")
            XCTAssertEqual(Set(natalPrompts.keys), Set(NatalSection.allCases.map(\.name)))
            for section in NatalSection.allCases {
                compare(HoroscopePrompt.natal(chart, section), natalPrompts.str(section.name), "\(id) prompt natal \(section.name)")
                compare(FallbackReadings.natal(chart, section), natalFallback.str(section.name), "\(id) fallback natal \(section.name)")
            }
        }
    }

    func testPairPromptsAndFallbacksMatchGolden() throws {
        let pairs = golden.list("pairs")
        XCTAssertFalse(pairs.isEmpty)
        for p in pairs {
            let label = "\(p.str("a"))-\(p.str("b"))-\(p.str("kind"))"
            let a = try CompatWire.profile(p.obj("aProfile")), b = try CompatWire.profile(p.obj("bProfile"))
            let kind = try MatchKind.fromWire(p.str("kind"))
            let score = Compatibility.score(a, b)
            compare(HoroscopePrompt.compatibility(a, b, score), p.str("compatibilityPrompt"), "\(label) compatibility prompt")
            compare(HoroscopePrompt.matchBlurb(a, b, score, kind), p.str("matchBlurbPrompt"), "\(label) blurb prompt")
            compare(FallbackReadings.compatibility(a, b, score, kind), p.str("fallback"), "\(label) fallback")
        }
    }

    /// The Swift charts give the same uploaded summaries as Kotlin for the prompt fixtures.
    func testPairProfilesMatchSwiftCharts() throws {
        for c in golden.list("charts") {
            let b = try birth(c.obj("birth"))
            let blood = c.optStr("bloodType").map { BloodType(rawValue: $0)! }
            let mine = CompatProfile.from(NatalChart.compute(b), ChineseZodiac.compute(b), blood)
            for p in golden.list("pairs") {
                if p.str("a") == c.str("id") { XCTAssertEqual(mine, try CompatWire.profile(p.obj("aProfile")), c.str("id")) }
                if p.str("b") == c.str("id") { XCTAssertEqual(mine, try CompatWire.profile(p.obj("bProfile")), c.str("id")) }
            }
        }
    }

    private func palm(_ m: [String: Any]) -> PalmFeatures {
        var lines: [PalmLine: LineFeature] = [:]
        for (k, v) in m.obj("lines") {
            let l = v as! [String: Any]
            lines[PalmLine.allCases.first { $0.name == k }!] = LineFeature(
                present: l.bool("present"), length: l.double("length"), curvature: l.double("curvature"),
                depth: l.double("depth"), breaks: l.int("breaks"), forked: l.bool("forked"))
        }
        var mounts: [Mount: Double] = [:]
        for (k, v) in m.obj("mounts") { mounts[Mount.allCases.first { $0.name == k }!] = (v as! NSNumber).doubleValue }
        let fingers = m.obj("fingers")
        return PalmFeatures(hand: Hand(rawValue: m.str("hand"))!, handShape: HandShape.allCases.first { $0.name == m.str("handShape") }!,
                            palmRatio: m.double("palmRatio"), lines: lines,
                            fingers: FingerRatios(indexToRing: fingers.double("indexToRing"), fingerToPalm: fingers.double("fingerToPalm")),
                            mounts: mounts, quality: m.double("quality"))
    }

    func testPalmPromptsAndFallbacksMatchGolden() {
        let palms = golden.list("palms")
        XCTAssertFalse(palms.isEmpty)
        for p in palms {
            let f = palm(p.obj("features"))
            compare(PalmReadingPrompt.build(f), p.str("prompt"), "\(p.str("id")) prompt")
            compare(FallbackReadings.palm(f), p.str("fallback"), "\(p.str("id")) fallback")
        }
    }

    func testEveryPromptCarriesGuardrails() {
        var prompts: [String] = []
        for c in golden.list("charts") {
            let p = c.obj("prompts")
            prompts += [p.str("daily"), p.str("eastern")] + p.obj("natal").values.map { $0 as! String }
        }
        prompts += golden.list("pairs").map { $0.str("compatibilityPrompt") }
        prompts += golden.list("palms").map { $0.str("prompt") }
        for p in prompts {
            XCTAssertTrue(p.contains(HoroscopePrompt.guardrails), p)
            XCTAssertFalse(p.contains("40.71") || p.contains("America/"), "no birthplace/coordinates in prompts")
        }
    }

    func testCleaner() {
        XCTAssertEqual(ReadingCleaner.clean("Horoscope: **Today** glows."), "Today glows.")
        XCTAssertEqual(ReadingCleaner.clean("## Reading\n\"A quiet tide.\""), "A quiet tide.")
        XCTAssertEqual(ReadingCleaner.clean("Line one.\n\n\n\nLine two."), "Line one.\n\nLine two.")
        XCTAssertEqual(ReadingCleaner.clean("Reading – _soft_ light"), "soft light")
        XCTAssertEqual(ReadingCleaner.clean("“Curly quotes.”"), "Curly quotes.")
        XCTAssertNil(ReadingCleaner.clean("   "))
        XCTAssertNil(ReadingCleaner.clean("# Only a heading"))
        XCTAssertNil(ReadingCleaner.clean(nil))
    }
}
