import Foundation
import XCTest
@testable import ConjunctionCore

/// Checks MatchFilter, Age and MatchBlurbPrompt against testdata/golden_match_filter.json
/// (the same vectors android/core's MatchFilterTest reads).
final class MatchFilterTests: XCTestCase {
    private var golden: [String: Any] = [:]

    override func setUpWithError() throws {
        golden = try Golden.load("golden_match_filter.json")
    }

    private func profile(_ m: [String: Any]) throws -> MatchProfile {
        MatchProfile(gender: try .fromWire(m.str("gender")), seeking: try .fromWire(m.str("seeking")),
                     relationshipStatus: try .fromWire(m.str("relationshipStatus")),
                     lookingFor: try .fromWire(m.str("lookingFor")))
    }

    private func kind(_ value: Any?) throws -> MatchKind? {
        guard let s = value as? String else { return nil }
        return try MatchKind.fromWire(s)
    }

    func testEveryProfileCombinationMatchesGolden() throws {
        var profiles: [String: MatchProfile] = [:]
        for m in golden.list("profiles") { profiles[m.str("id")] = try profile(m) }
        XCTAssertEqual(profiles.count, 36)
        let pairs = golden.array("pairs") as! [[Any]]
        XCTAssertEqual(pairs.count, 1296)
        var counts: [String: Int] = [:]
        for row in pairs {
            let a = profiles[row[0] as! String]!, b = profiles[row[1] as! String]!
            let expected = try kind(row[2])
            XCTAssertEqual(MatchFilter.kind(a, b), expected, "\(row[0]) x \(row[1])")
            XCTAssertEqual(MatchFilter.kind(b, a), MatchFilter.kind(a, b), "symmetry \(row[0]) x \(row[1])")
            counts[expected?.wire ?? "null", default: 0] += 1
        }
        XCTAssertEqual(counts, ["dating": 64, "friends": 560, "null": 672])
    }

    func testNamedCases() throws {
        for c in golden.list("named") {
            XCTAssertEqual(MatchFilter.kind(try profile(c.obj("a")), try profile(c.obj("b"))), try kind(c["expected"]), c.str("name"))
        }
    }

    func testDefaultLookingFor() throws {
        for (status, expected) in golden.obj("defaultLookingFor") {
            XCTAssertEqual(MatchFilter.defaultLookingFor(try .fromWire(status)), try LookingFor.fromWire(expected as! String))
        }
    }

    func testWireValuesAreStrict() {
        XCTAssertThrowsError(try Gender.fromWire("Male"))
        XCTAssertThrowsError(try Seeking.fromWire("everyone"))
        XCTAssertThrowsError(try RelationshipStatus.fromWire("in a relationship"))
        XCTAssertThrowsError(try LookingFor.fromWire(""))
        XCTAssertThrowsError(try MatchKind.fromWire("DATING"))
        XCTAssertEqual(RelationshipStatus.inARelationship.wire, "in_a_relationship")
        XCTAssertEqual(LookingFor.both.displayName, "Dating & friends")
    }

    func testAge() throws {
        for c in golden.list("age") {
            let birth = date(c.str("birthDate")), today = date(c.str("today"))
            if let years = c.optInt("years") {
                XCTAssertEqual(try Age.yearsOn(birth, today: today), years, "\(birth) on \(today)")
            } else {
                XCTAssertThrowsError(try Age.yearsOn(birth, today: today), "\(birth) on \(today)")
            }
            XCTAssertEqual(Age.isAdult(birth, today: today), c.bool("adult"), "\(birth) on \(today)")
        }
        for c in golden.list("latestAdultBirthDate") {
            XCTAssertEqual(Age.latestAdultBirthDate(date(c.str("today"))).description, c.str("latestAdultBirthDate"))
        }
    }

    func testEligibility() throws {
        for c in golden.list("eligibility") {
            let got = MatchFilter.kind(try profile(c.obj("a")), aBirthDate: date(c.str("aBirthDate")),
                                       try profile(c.obj("b")), bBirthDate: date(c.str("bBirthDate")),
                                       today: date(c.str("today")))
            XCTAssertEqual(got, try kind(c["expected"]), "\(c)")
        }
    }

    func testBlurbPromptAndFallbackAreExact() throws {
        let blurbs = golden.list("blurbs")
        XCTAssertFalse(blurbs.isEmpty)
        for c in blurbs {
            let i = c.obj("input")
            let input = MatchBlurbPrompt.Input(
                kind: try MatchKind.fromWire(i.str("kind")), mySun: i.str("mySun"), theirSun: i.str("theirSun"),
                theirMoon: i.optStr("theirMoon"), theirRising: i.optStr("theirRising"),
                myAnimal: i.str("myAnimal"), theirAnimal: i.str("theirAnimal"),
                total: i.int("total"), western: i.int("western"), chinese: i.int("chinese"), blood: i.optInt("blood"),
                birthdayTwin: i.bool("birthdayTwin"), exactTwin: i.bool("exactTwin"))
            XCTAssertEqual(MatchBlurbPrompt.build(input), c.str("prompt"))
            XCTAssertEqual(MatchBlurbPrompt.fallback(input), c.str("fallback"))
        }
    }

    func testClean() throws {
        for c in golden.list("clean") {
            XCTAssertEqual(MatchBlurbPrompt.clean(c.optStr("raw")), c.optStr("expected"), "\(c["raw"] ?? "nil")")
        }
    }
}
