import Foundation
import XCTest
@testable import ConjunctionCore

/// Structural checks mirroring android/core's EnginesTest.
final class EnginesTests: XCTestCase {
    private let ava = try! BirthData(date: LocalDate(1990, 8, 12), time: LocalTime(14, 20), latitude: 40.71,
                                     longitude: -74.01, zoneId: "America/New_York", placeName: "New York")

    func testChartStructure() throws {
        let chart = NatalChart.compute(ava)
        XCTAssertEqual(chart.bodies.count, 10)
        XCTAssertEqual(chart.sunSign, .leo)
        XCTAssertEqual(chart.houses?.count, 12)
        XCTAssertTrue(chart.bodies.allSatisfy { (1...12).contains($0.house ?? 0) })
        XCTAssertEqual(chart.sun.house, Math.floorMod(chart.sunSign.ordinal - chart.risingSign!.ordinal, 12) + 1)
        XCTAssertTrue(zip(chart.aspects, chart.aspects.dropFirst()).allSatisfy { $0.orb <= $1.orb })
        for a in chart.aspects {
            let sep = Angles.separation(chart.body(a.a).longitude, chart.body(a.b).longitude)
            XCTAssertEqual(a.orb, abs(sep - a.type.angle), accuracy: 1e-9)
        }
        let placidus = NatalChart.compute(ava, houseSystem: .placidus)
        XCTAssertEqual(placidus.houses![0], chart.ascendant!, accuracy: 1e-9)
        XCTAssertEqual(placidus.houses![9], chart.midheaven!, accuracy: 1e-9)
    }

    func testUnknownTime() throws {
        let chart = NatalChart.compute(try BirthData(date: ava.date, time: nil, latitude: ava.latitude, longitude: ava.longitude, zoneId: ava.zoneId))
        XCTAssertNil(chart.ascendant)
        XCTAssertNil(chart.risingSign)
        XCTAssertTrue(chart.bodies.allSatisfy { $0.house == nil })
        XCTAssertTrue(HoroscopePrompt.bigThree(chart).contains("birth time unknown"))
        let d = LocalDate(2026, 11, 3)
        let day = NatalChart.compute(try BirthData(date: d, time: nil, latitude: 0, longitude: 0, zoneId: "UTC"))
        let start = NatalChart.compute(try BirthData(date: d, time: LocalTime(0, 0), latitude: 0, longitude: 0, zoneId: "UTC")).moonSign
        let end = NatalChart.compute(try BirthData(date: d, time: LocalTime(23, 59, 59), latitude: 0, longitude: 0, zoneId: "UTC")).moonSign
        XCTAssertEqual(day.moonSignUncertain, start != end)
    }

    func testTransits() {
        let chart = NatalChart.compute(ava)
        let t = Transits.compute(chart, Date(timeIntervalSince1970: 1_793_725_200))  // 2026-11-03T17:00:00Z
        XCTAssertEqual(t.bodies.count, 10)
        XCTAssertTrue(t.aspectsToNatal.allSatisfy { $0.orb <= 5.0 })
        XCTAssertTrue(t.bodies.first { $0.planet == .mercury }!.retrograde)  // Mercury retrograde early Nov 2026
    }

    func testFormatting() {
        XCTAssertEqual(formatLongitude(134.12), "14°07′ Leo")
        XCTAssertEqual(formatLongitude(359.999), "29°59′ Pisces")
        XCTAssertEqual(ZodiacSign.of(360.0), .aries)
        XCTAssertEqual(HoroscopePrompt.ordinal(1), "1st")
        XCTAssertEqual(HoroscopePrompt.ordinal(12), "12th")
        XCTAssertEqual(HoroscopePrompt.ordinal(3), "3rd")
        XCTAssertEqual(HoroscopePrompt.ordinal(22), "22nd")
    }

    func testBloodTypes() {
        XCTAssertEqual(BloodType.parse("ab+"), .ab)
        XCTAssertEqual(BloodType.parse(" O- "), .o)
        for t in BloodType.allCases {
            let r = BloodTypeReading.traits(t)
            XCTAssertTrue(r.title.hasPrefix("Type \(t.displayName)"))
            XCTAssertEqual(r.keywords.count, 3)
        }
        XCTAssertEqual(BloodTypeReading.label(.ab, .negative), "Type AB\u{2212}")
    }

    func testPalmGeometry() throws {
        // Synthetic hand: palm 100 px long, 90 px wide, long fingers (middle 95 px).
        func hand(_ palmWidth: Double, _ middle: Double) -> [Point2] {
            var pts = [Point2](repeating: Point2(0, 0), count: 21)
            pts[0] = Point2(50, 200)
            let knuckles: [(Int, Double)] = [(5, 50 - palmWidth / 2), (9, 50), (13, 50 + palmWidth / 4), (17, 50 + palmWidth / 2)]
            for (mcp, x) in knuckles {
                let len: Double = mcp == 9 ? middle : (mcp == 5 ? middle * 0.88 : (mcp == 13 ? middle * 0.92 : middle * 0.7))
                pts[mcp] = Point2(x, 100)
                for k in 1...3 { pts[mcp + k] = Point2(x, 100 - len * Double(k) / 3) }
            }
            for k in 1...4 { pts[k] = Point2(50 - palmWidth / 2 - 10 * Double(k), 180 - 15 * Double(k)) }
            return pts
        }
        let arc = (0...20).map { i -> Point2 in let a = Double.pi * Double(i) / 40; return Point2(100 * cos(a), 100 * sin(a)) }
        let f = try PalmAnalyzer.analyze(hand: .right, landmarks: hand(90, 95), lines: [.heart: arc], depth: [.heart: 0.9])
        XCTAssertEqual(f.handShape, .air)
        XCTAssertEqual(f.palmRatio, 0.9, accuracy: 1e-9)
        XCTAssertLessThan(f.fingers.indexToRing, 1.0)
        XCTAssertTrue(f.line(.heart).present)
        XCTAssertEqual(f.line(.heart).lengthClass, .long)
        XCTAssertGreaterThan(f.line(.heart).curvature, 0)
        XCTAssertFalse(f.line(.fate).present)
        XCTAssertEqual(try PalmAnalyzer.analyze(hand: .left, landmarks: hand(70, 80), lines: [:]).handShape, .fire)
        XCTAssertEqual(PalmAnalyzer.classifyHandShape(palmRatio: 0.9, fingerToPalm: 0.8), .earth)
        XCTAssertEqual(PalmAnalyzer.classifyHandShape(palmRatio: 0.7, fingerToPalm: 0.95), .water)
        XCTAssertEqual(PalmAnalyzer.curvature([Point2(0, 0), Point2(10, 0)]), 0, accuracy: 1e-12)
        XCTAssertThrowsError(try PalmAnalyzer.analyze(hand: .left, landmarks: [], lines: [:]))
        XCTAssertThrowsError(try PalmAnalyzer.analyze(hand: .left, landmarks: [Point2](repeating: Point2(1, 1), count: 21), lines: [:]))
    }
}
