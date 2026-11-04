import Foundation
import XCTest
@testable import ConjunctionCore

/// Wire format of CompatProfile / CompatResult used by golden_compat.json (Kotlin CompatWire).
enum CompatWire {
    static func profile(_ m: [String: Any]) throws -> CompatProfile {
        try CompatProfile(
            sun: m.double("sun"), moon: m.double("moon"), venus: m.double("venus"), mars: m.double("mars"),
            ascendant: m.optDouble("ascendant"), chineseAnimal: ChineseAnimal.fromKey(m.str("chineseAnimal"))!,
            dayStem: HeavenlyStem.allCases.first { $0.name == m.str("dayStem").uppercased() }!,
            bloodType: m.optStr("bloodType").map { BloodType(rawValue: $0)! },
            birthDate: date(m.str("birthDate")), birthHour: m.optInt("birthHour"))
    }

    static func result(_ r: CompatResult) -> [String: Any] {
        ["total": r.total, "western": r.western, "chinese": r.chinese, "blood": r.blood as Any,
         "highlights": r.highlights, "isBirthdayTwin": r.isBirthdayTwin, "isExactTwin": r.isExactTwin,
         "sharesBirthday": r.sharesBirthday, "chineseRelation": r.chineseRelation.name, "stemRelation": r.stemRelation.name]
    }
}

/// Fixture people (same as android/core's CompatFixtures).
enum CompatFixtures {
    struct Person { let id: String; let birth: BirthData; let blood: BloodType? }

    static func person(_ id: String, _ d: String, _ t: String?, _ lat: Double, _ lon: Double, _ zone: String, _ blood: BloodType?) -> Person {
        Person(id: id, birth: try! BirthData(date: date(d), time: t.map { LocalTime.parse($0)! }, latitude: lat, longitude: lon, zoneId: zone), blood: blood)
    }

    static let people: [Person] = [
        person("ava-1990", "1990-08-12", "14:20", 40.71, -74.01, "America/New_York", .o),
        person("ben-1988", "1988-02-29", "06:05", 51.51, -0.13, "Europe/London", .a),
        person("chen-1992", "1992-02-04", "23:40", 31.23, 121.47, "Asia/Shanghai", .ab),
        person("dara-1995", "1995-11-03", nil, -33.87, 151.21, "Australia/Sydney", nil),
        person("eli-1985", "1985-06-21", "12:00", 34.05, -118.24, "America/Los_Angeles", .b),
        person("fatima-1999", "1999-12-31", "23:59", 25.20, 55.27, "Asia/Dubai", .o),
        person("gus-1978", "1978-04-01", "03:15", 59.91, 10.75, "Europe/Oslo", .a),
        person("hana-2001", "2001-07-07", "07:07", 35.69, 139.69, "Asia/Tokyo", .a),
        person("ivan-1993", "1993-01-23", "18:30", 55.76, 37.62, "Europe/Moscow", nil),
        person("jade-1990", "1990-08-12", "14:50", 37.77, -122.42, "America/Los_Angeles", .b),
        person("kai-1990", "1990-08-12", "09:10", 19.43, -99.13, "America/Mexico_City", .o),
        person("lena-1990", "1990-08-12", nil, 52.52, 13.40, "Europe/Berlin", .ab),
        person("milo-1984", "1984-02-03", "09:00", 25.03, 121.57, "Asia/Taipei", .b),
        person("nia-2000", "2000-02-04", "21:00", -1.29, 36.82, "Africa/Nairobi", .o),
        person("omar-1975", "1975-08-12", "14:20", 30.04, 31.24, "Africa/Cairo", .a),
        person("pia-2003", "2003-03-14", "15:09", -23.55, -46.63, "America/Sao_Paulo", nil),
        person("quinn-1982", "1982-10-31", "22:22", 53.35, -6.26, "Europe/Dublin", .ab),
        person("rosa-1996", "1996-05-05", "05:05", 41.39, 2.17, "Europe/Madrid", .b),
        person("sam-1990", "1990-08-12", "14:05", 40.71, -74.01, "America/New_York", .a),
        person("tara-1970", "1970-01-01", "00:01", 28.61, 77.21, "Asia/Kolkata", .o),
    ]

    static func profile(_ p: Person) -> CompatProfile {
        CompatProfile.from(NatalChart.compute(p.birth), ChineseZodiac.compute(p.birth), p.blood)
    }

    static let edgeProfiles: [String: CompatProfile] = [
        "edge-zero": try! CompatProfile(sun: 0, moon: 0, venus: 0, mars: 0, ascendant: 0, chineseAnimal: .rat, dayStem: .jia, bloodType: .a, birthDate: LocalDate(1990, 1, 1), birthHour: 0),
        "edge-orb-limit": try! CompatProfile(sun: 127, moon: 7, venus: 248, mars: 60, ascendant: 90, chineseAnimal: .dragon, dayStem: .ji, bloodType: .o, birthDate: LocalDate(1991, 1, 1), birthHour: 12),
        "edge-wrap": try! CompatProfile(sun: 359.995, moon: 29.995, venus: 359.994, mars: 180, ascendant: nil, chineseAnimal: .horse, dayStem: .geng, bloodType: nil, birthDate: LocalDate(1992, 6, 6), birthHour: nil),
        "edge-square": try! CompatProfile(sun: 91.23, moon: 268.77, venus: 181.23, mars: 1.23, ascendant: 271.23, chineseAnimal: .rooster, dayStem: .yi, bloodType: .ab, birthDate: LocalDate(1993, 3, 3), birthHour: 3),
    ]

    static func allProfiles() -> [String: CompatProfile] {
        var all = edgeProfiles
        for p in people { all[p.id] = profile(p) }
        return all
    }
}

final class CompatibilityTests: XCTestCase {
    /// Bit-identical with Kotlin: every field of every golden case must match exactly.
    func testGoldenVectors() throws {
        guard Golden.exists("golden_compat.json") else {
            throw XCTSkip("testdata/golden_compat.json not generated yet (./gradlew :core:writeGoldens)")
        }
        let cases = try Golden.load("golden_compat.json").list("cases")
        XCTAssertGreaterThanOrEqual(cases.count, 30)
        for c in cases {
            let id = c["id"].map { "\($0)" } ?? "?"
            let actual = CompatWire.result(Compatibility.score(try CompatWire.profile(c.obj("a")), try CompatWire.profile(c.obj("b"))))
            for (k, v) in c.obj("expected") {
                XCTAssertEqual(normalize(actual[k]), normalize(v), "\(id).\(k)")
            }
        }
        XCTAssertTrue(cases.contains { $0.obj("expected").bool("isExactTwin") })
        XCTAssertTrue(cases.contains { $0.obj("expected").bool("isBirthdayTwin") && !$0.obj("expected").bool("isExactTwin") })
    }

    /// The uploaded summaries Swift computes from the fixture births equal the ones Kotlin wrote.
    func testGoldenProfilesMatchSwiftCharts() throws {
        guard Golden.exists("golden_compat.json") else { throw XCTSkip("golden_compat.json not generated yet") }
        let golden = try Golden.load("golden_compat.json")
        let ours = CompatFixtures.allProfiles()
        var checked = 0
        for c in golden.list("cases") {
            // Case ids are "pair-NN-<idA>-<idB>"; find the split where both halves are fixture ids.
            let rest = String(c.str("id").split(separator: "-", maxSplits: 2)[2])
            let parts = rest.split(separator: "-").map(String.init)
            for k in 1..<parts.count {
                let x = parts[..<k].joined(separator: "-"), y = parts[k...].joined(separator: "-")
                guard let pa = ours[x], let pb = ours[y] else { continue }
                XCTAssertEqual(pa, try CompatWire.profile(c.obj("a")), x)
                XCTAssertEqual(pb, try CompatWire.profile(c.obj("b")), y)
                checked += 1
                break
            }
        }
        if checked == 0 { throw XCTSkip("golden_compat.json cases carry no person ids") }
    }

    /// Compares through a JSON round trip of the actual value, so numbers, booleans, null and
    /// arrays compare exactly as the golden file encodes them.
    private func normalize(_ x: Any?) -> String {
        let wrapped: Any = ["v": x ?? NSNull()]
        let data = try! JSONSerialization.data(withJSONObject: wrapped, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }

    func testSymmetricAndBounded() {
        let profiles = Array(CompatFixtures.allProfiles().values)
        for a in profiles {
            for b in profiles {
                let ab = Compatibility.score(a, b), ba = Compatibility.score(b, a)
                XCTAssertEqual(ab, ba)
                XCTAssertTrue((0...100).contains(ab.total) && (0...100).contains(ab.western) && (0...100).contains(ab.chinese))
                if let blood = ab.blood { XCTAssertTrue((0...100).contains(blood)) }
                XCTAssertLessThanOrEqual(ab.highlights.count, 4)
            }
        }
    }

    func testRelationsTables() {
        XCTAssertEqual(Compatibility.chineseRelation(.rat, .dragon), .trine)
        XCTAssertEqual(Compatibility.chineseRelation(.monkey, .rat), .trine)
        XCTAssertEqual(Compatibility.chineseRelation(.rat, .ox), .harmony)
        XCTAssertEqual(Compatibility.chineseRelation(.horse, .goat), .harmony)
        XCTAssertEqual(Compatibility.chineseRelation(.tiger, .pig), .harmony)
        XCTAssertEqual(Compatibility.chineseRelation(.rat, .horse), .clash)
        XCTAssertEqual(Compatibility.chineseRelation(.rat, .goat), .harm)
        XCTAssertEqual(Compatibility.chineseRelation(.rooster, .dog), .harm)
        XCTAssertEqual(Compatibility.chineseRelation(.dragon, .dragon), .sameSelfPunishment)
        XCTAssertEqual(Compatibility.chineseRelation(.ox, .ox), .same)
        XCTAssertEqual(Compatibility.chineseRelation(.rat, .tiger), .neutral)
        XCTAssertEqual(Compatibility.stemRelation(.jia, .ji), .combination)
        XCTAssertEqual(Compatibility.stemRelation(.jia, .yi), .sameElement)
        XCTAssertEqual(Compatibility.stemRelation(.jia, .bing), .generating)
        XCTAssertEqual(Compatibility.stemRelation(.jia, .wu), .controlling)
        XCTAssertEqual(Compatibility.bloodScore(.a, .o), 85)
        XCTAssertNil(Compatibility.bloodScore(.a, nil))
        XCTAssertEqual(Compatibility.centidegrees(359.995), 0)
        XCTAssertEqual(Compatibility.centidegrees(29.995), 3000)
        XCTAssertEqual(Compatibility.centidegrees(-0.004), 0)
        XCTAssertEqual(Compatibility.roundDiv(5, 2), 3)
        XCTAssertEqual(Compatibility.roundDiv(-5, 2), -2)
        XCTAssertEqual(Compatibility.pairScore(0, 9123).aspectPart, -329)
    }

    func testTwins() {
        let all = CompatFixtures.allProfiles()
        let ava = all["ava-1990"]!
        XCTAssertTrue(Compatibility.isExactTwin(ava, all["sam-1990"]!))
        XCTAssertTrue(Compatibility.isBirthdayTwin(ava, all["kai-1990"]!))
        XCTAssertFalse(Compatibility.isExactTwin(ava, all["kai-1990"]!))
        XCTAssertFalse(Compatibility.isExactTwin(ava, all["lena-1990"]!))
        XCTAssertTrue(Compatibility.sharesBirthday(ava, all["omar-1975"]!))
        XCTAssertEqual(Compatibility.score(ava, all["sam-1990"]!).highlights.first, "Exact twins: same day, same hour")
    }

    func testProfileValidation() {
        XCTAssertThrowsError(try CompatProfile(sun: 361, moon: 0, venus: 0, mars: 0, ascendant: nil, chineseAnimal: .rat, dayStem: .jia, bloodType: nil, birthDate: LocalDate(2000, 1, 1), birthHour: nil))
        XCTAssertThrowsError(try CompatProfile(sun: 0, moon: 0, venus: 0, mars: 0, ascendant: nil, chineseAnimal: .rat, dayStem: .jia, bloodType: nil, birthDate: LocalDate(2000, 1, 1), birthHour: 24))
        XCTAssertEqual(CompatProfile.round2(359.996), 0.0)
        XCTAssertEqual(CompatProfile.round2(123.456), 123.46)
    }

    func testBloodParsing() {
        XCTAssertEqual(BloodType.parse(" ab+ "), .ab)
        XCTAssertEqual(BloodType.parse("0"), .o)
        XCTAssertEqual(BloodType.parse("B-"), .b)
        XCTAssertNil(BloodType.parse("C"))
        XCTAssertEqual(RhFactor.parse("O-"), .negative)
        XCTAssertEqual(RhFactor.parse("A\u{2212}"), .negative)
        XCTAssertEqual(RhFactor.parse("A+"), .positive)
        XCTAssertNil(RhFactor.parse("A"))
        XCTAssertEqual(BloodTypeReading.label(.ab, .positive), "Type AB+")
    }
}
