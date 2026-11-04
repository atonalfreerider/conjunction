import Foundation
import XCTest
@testable import ConjunctionCore

/// SPEC 5d.5: BirthTextParser, AboutYouParser, model JSON validation and the onboarding/bio prompts,
/// against the same goldens as Kotlin's OnboardingTest.
final class OnboardingTests: XCTestCase {
    func testBirthTextGolden() throws {
        let g = try Golden.load("golden_birth_text.json")
        let today = date(g.str("today"))
        for c in g.list("cases") {
            let r = BirthTextParser.parse(c.str("text"), region: c.optStr("region"), today: today)
            let what = "\(c.str("text")) (\(c.optStr("region") ?? "nil"))"
            XCTAssertEqual(r.date?.description, c.optStr("date"), "\(what) date")
            XCTAssertEqual(r.time?.description, c.optStr("time"), "\(what) time")
            XCTAssertEqual(r.timeUnknown, c.bool("timeUnknown"), "\(what) timeUnknown")
            XCTAssertEqual(r.placeQuery, c.optStr("placeQuery"), "\(what) place")
            XCTAssertEqual(r.ambiguousDate, c.bool("ambiguousDate"), "\(what) ambiguous")
        }
        for c in g.list("acceptableDate") {
            XCTAssertEqual(BirthTextParser.isAcceptableDate(date(c.str("date")), today: today), c.bool("acceptable"), c.str("date"))
        }
    }

    func testAboutYouGolden() throws {
        for c in try Golden.load("golden_about_you.json").list("cases") {
            let r = AboutYouParser.parse(c.str("text"))
            let what = c.str("text")
            XCTAssertEqual(r.firstName, c.optStr("firstName"), "\(what) firstName")
            XCTAssertEqual(r.gender?.wire, c.optStr("gender"), "\(what) gender")
            XCTAssertEqual(r.seeking?.wire, c.optStr("seeking"), "\(what) seeking")
            XCTAssertEqual(r.relationship?.wire, c.optStr("relationship"), "\(what) relationship")
            XCTAssertEqual(r.lookingFor?.wire, c.optStr("lookingFor"), "\(what) lookingFor")
            XCTAssertEqual(r.interests, c.optStr("interests"), "\(what) interests")
        }
    }

    func testModelJSONGolden() throws {
        let g = try Golden.load("golden_onboarding_ai.json")
        let today = date(g.str("today"))
        for c in g.list("birth") {
            guard let r = OnboardingExtraction.parseBirth(c.str("raw"), today: today) else { XCTFail("rejected \(c.str("raw"))"); continue }
            XCTAssertEqual(r.date?.description, c.optStr("date"), c.str("raw"))
            XCTAssertEqual(r.time?.description, c.optStr("time"), c.str("raw"))
            XCTAssertEqual(r.timeUnknown, c.bool("timeUnknown"), c.str("raw"))
            XCTAssertEqual(r.place, c.optStr("place"), c.str("raw"))
        }
        for raw in g.array("birthReject") as! [String] { XCTAssertNil(OnboardingExtraction.parseBirth(raw, today: today), raw) }
        for c in g.list("aboutYou") {
            guard let r = OnboardingExtraction.parseAboutYou(c.str("raw")) else { XCTFail("rejected \(c.str("raw"))"); continue }
            XCTAssertEqual(r.firstName, c.optStr("firstName"), c.str("raw"))
            XCTAssertEqual(r.gender?.wire, c.optStr("gender"), c.str("raw"))
            XCTAssertEqual(r.seeking?.wire, c.optStr("seeking"), c.str("raw"))
            XCTAssertEqual(r.relationship?.wire, c.optStr("relationship"), c.str("raw"))
            XCTAssertEqual(r.lookingFor?.wire, c.optStr("lookingFor"), c.str("raw"))
            XCTAssertEqual(r.interests, c.optStr("interests"), c.str("raw"))
        }
        for raw in g.array("aboutYouReject") as! [String] { XCTAssertNil(OnboardingExtraction.parseAboutYou(raw), raw) }
    }

    func testModelOnlyFillsEmptyChips() {
        let parsed = BirthTextResult(date: LocalDate(1994, 8, 7), time: nil, timeUnknown: true, placeQuery: nil, ambiguousDate: false)
        let model = BirthExtraction(date: LocalDate(1990, 1, 1), time: LocalTime(4, 0), timeUnknown: false, place: "Providence, RI")
        let merged = OnboardingExtraction.fillEmpty(parsed, model)
        XCTAssertEqual(merged.date, LocalDate(1994, 8, 7))
        XCTAssertNil(merged.time)
        XCTAssertTrue(merged.timeUnknown)
        XCTAssertEqual(merged.placeQuery, "Providence, RI")
        XCTAssertEqual(OnboardingExtraction.fillEmpty(parsed, nil), parsed)
        let mine = AboutYou(firstName: "Sam", lookingFor: .friends)
        XCTAssertEqual(mine.fillEmpty(AboutYou(firstName: "Samuel", lookingFor: .dating, interests: "jazz")),
                       AboutYou(firstName: "Sam", lookingFor: .friends, interests: "jazz"))
    }

    func testPromptsAndBioMatchGolden() throws {
        let g = try Golden.load("golden_prompts.json")
        let onboarding = g.list("onboarding")
        XCTAssertEqual(onboarding.count, 3)
        for o in onboarding {
            let text = o.str("text")
            XCTAssertEqual(OnboardingPrompt.birth(text, today: date(o.str("today"))), o.str("birthPrompt"))
            XCTAssertEqual(OnboardingPrompt.aboutYou(text), o.str("aboutYouPrompt"))
        }
        for c in g.list("charts") {
            let m = c.obj("birth")
            let birth = try BirthData(date: date(m.str("date")), time: m.optStr("time").map { LocalTime.parse($0)! },
                                      latitude: m.double("latitude"), longitude: m.double("longitude"), zoneId: m.str("zoneId"))
            let chart = NatalChart.compute(birth)
            let chinese = ChineseZodiac.compute(birth)
            let bio = c.obj("bio")
            let lf = bio.optStr("lookingFor").flatMap { w in LookingFor.allCases.first { $0.wire == w } }
            XCTAssertEqual(BioPrompt.build(sun: chart.sunSign, moon: chart.moonSign, rising: chart.risingSign,
                                           chineseAnimal: chinese.yearAnimal, lookingFor: lf, interests: bio.optStr("interests")),
                           bio.str("prompt"), c.str("id"))
            for (seed, text) in (bio.array("fallback") as! [String]).enumerated() {
                XCTAssertEqual(FallbackReadings.bio(chart, interests: bio.optStr("interests"), seed: seed), text, "\(c.str("id")) seed \(seed)")
                XCTAssertLessThanOrEqual(text.unicodeScalars.count, BioPrompt.maxChars)
            }
            XCTAssertFalse(FallbackReadings.bio(chart, interests: " , ", seed: 0).contains("Into"))
        }
    }
}
