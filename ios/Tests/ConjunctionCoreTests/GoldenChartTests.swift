import Foundation
import XCTest
@testable import ConjunctionCore

/// Checks the ephemeris against the Swiss Ephemeris reference charts in testdata/golden_charts.json
/// (testdata/tools/make_golden.py), mirroring android/core's GoldenChartTest. Contract tolerances:
/// planets 0.05 deg, Ascendant / MC / Placidus cusps 0.1 deg.
final class GoldenChartTests: XCTestCase {
    private var charts: [[String: Any]] = []

    override func setUpWithError() throws {
        charts = try Golden.load("golden_charts.json").list("charts")
    }

    private func birthOf(_ c: [String: Any]) throws -> BirthData {
        try BirthData(date: date(c.str("date")), time: c.optStr("time").map { LocalTime.parse($0)! },
                      latitude: c.double("latitude"), longitude: c.double("longitude"),
                      zoneId: c.str("zoneId"), placeName: c.str("placeName"))
    }

    private func instant(_ iso: String) -> Date {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: iso)!
    }

    func testHasEnoughCharts() {
        XCTAssertGreaterThanOrEqual(charts.count, 40)
    }

    func testTimeZoneResolutionMatchesReference() throws {
        for c in charts {
            XCTAssertEqual(try birthOf(c).instant.timeIntervalSince1970, instant(c.str("utc")).timeIntervalSince1970, c.str("id"))
        }
    }

    func testDeltaTMatchesSwissEphemeris() {
        for c in charts {
            XCTAssertEqual(TimeScales.deltaTSeconds(instant(c.str("utc"))), c.double("deltaTSeconds"), accuracy: 0.5, c.str("id"))
        }
    }

    func testPlanetsWithinFiveHundredthsOfADegree() throws {
        var worst = 0.0, worstLabel = ""
        for c in charts {
            let chart = NatalChart.compute(try birthOf(c))
            let bodies = c.obj("bodies")
            for planet in Planet.allCases {
                let ref = bodies.obj(planet.key)
                let err = Angles.separation(chart.body(planet).longitude, ref.double("longitude"))
                if err > worst { worst = err; worstLabel = "\(c.str("id")) \(planet.key)" }
                XCTAssertLessThanOrEqual(err, 0.05, "\(c.str("id")) \(planet.key)")
                if abs(ref.double("speed")) > 0.002 {
                    XCTAssertEqual(chart.body(planet).retrograde, ref.bool("retrograde"), "\(c.str("id")) \(planet.key) retrograde")
                }
            }
        }
        print("worst planet error: \(String(format: "%.5f", worst)) deg (\(worstLabel))")
    }

    func testAnglesAndHouses() throws {
        var worst = 0.0
        for c in charts {
            let id = c.str("id")
            let birth = try birthOf(c)
            let whole = NatalChart.compute(birth)
            if c.isNull("ascendant") {
                XCTAssertNil(whole.ascendant, id)
                XCTAssertNil(whole.houses, id)
                XCTAssertTrue(whole.bodies.allSatisfy { $0.house == nil }, id)
                continue
            }
            let asc = try XCTUnwrap(whole.ascendant), mc = try XCTUnwrap(whole.midheaven)
            let ascErr = Angles.separation(asc, c.double("ascendant"))
            let mcErr = Angles.separation(mc, c.double("midheaven"))
            worst = max(worst, ascErr, mcErr)
            XCTAssertLessThanOrEqual(ascErr, 0.1, "\(id) asc")
            XCTAssertLessThanOrEqual(mcErr, 0.1, "\(id) mc")
            let wsRef = (c.array("wholeSignCusps") as! [NSNumber]).map(\.doubleValue)
            XCTAssertEqual(whole.houses, wsRef, id)
            let placidus = NatalChart.compute(birth, houseSystem: .placidus)
            if c.isNull("placidusCusps") {
                XCTAssertTrue(placidus.housesFellBack, "\(id) should fall back to Porphyry")
            } else {
                for (i, ref) in (c.array("placidusCusps") as! [NSNumber]).enumerated() {
                    let err = Angles.separation(placidus.houses![i], ref.doubleValue)
                    worst = max(worst, err)
                    XCTAssertLessThanOrEqual(err, 0.1, "\(id) Placidus cusp \(i + 1)")
                }
            }
        }
        print("worst angle/cusp error: \(String(format: "%.5f", worst)) deg")
    }

    func testBirthDataValidation() {
        XCTAssertThrowsError(try BirthData(date: LocalDate(2000, 1, 1), time: nil, latitude: 91, longitude: 0, zoneId: "UTC"))
        XCTAssertThrowsError(try BirthData(date: LocalDate(2000, 1, 1), time: nil, latitude: 0, longitude: 181, zoneId: "UTC"))
        XCTAssertThrowsError(try BirthData(date: LocalDate(2000, 1, 1), time: nil, latitude: 0, longitude: 0, zoneId: "Mars/Olympus"))
    }

    func testSignHelpers() {
        XCTAssertEqual(ZodiacSign.of(0), .aries)
        XCTAssertEqual(ZodiacSign.of(359.9999), .pisces)
        XCTAssertEqual(ZodiacSign.of(-0.5), .pisces)
        XCTAssertEqual(ZodiacSign.of(134.12), .leo)
        XCTAssertEqual(ZodiacSign.fromKey("LEO"), .leo)
        XCTAssertEqual(formatLongitude(134.1199), "14°07′ Leo")
        XCTAssertEqual(formatLongitude(29.99999), "29°59′ Aries")
        XCTAssertEqual(Element.fire.displayName, "Fire")
    }
}

/// The bundled tables must be the exact bytes android/core ships.
final class ResourceParityTests: XCTestCase {
    func testResourcesMatchKotlin() throws {
        let android = Golden.directory.deletingLastPathComponent()
            .appendingPathComponent("android/core/src/main/resources/com/metavirtuoso/conjunction/core")
        let ours = Golden.directory.deletingLastPathComponent().appendingPathComponent("ios/Sources/ConjunctionCore/Resources")
        for (dir, name) in [("astro", "ephemeris_tables.txt"), ("astro", "delta_t.txt"), ("chinese", "chinese_new_year.txt"), ("fallback", "content_en.json")] {
            let theirs = android.appendingPathComponent(dir).appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: theirs.path) else { throw XCTSkip("android resources not present") }
            XCTAssertEqual(try Data(contentsOf: ours.appendingPathComponent(name)), try Data(contentsOf: theirs), name)
        }
    }
}
