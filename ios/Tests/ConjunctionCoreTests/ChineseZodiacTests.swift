import Foundation
import XCTest
@testable import ConjunctionCore

/// Mirrors android/core's ChineseZodiacTest: golden_chinese.json (independent oracle) and the Hong
/// Kong Observatory tables in chinese_calendar.json.
final class ChineseZodiacTests: XCTestCase {
    private let chinaTime = TimeZone(secondsFromGMT: 8 * 3600)!

    private func instant(_ iso: String) -> Date {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: iso)!
    }

    func testGoldenCasesFromIndependentOracle() throws {
        let cases = try Golden.load("golden_chinese.json").list("cases")
        XCTAssertGreaterThanOrEqual(cases.count, 40)
        for c in cases {
            let id = c.str("id")
            let birth = try BirthData(date: date(c.str("date")), time: c.optStr("time").map { LocalTime.parse($0)! },
                                      latitude: 0, longitude: c.double("longitude"), zoneId: c.str("zoneId"))
            let e = c.obj("expected")
            let chart = ChineseZodiac.compute(birth)
            XCTAssertEqual(chart.pillars.year.hanzi, e.str("yearPillar"), "\(id) year")
            XCTAssertEqual(chart.pillars.month.hanzi, e.str("monthPillar"), "\(id) month")
            XCTAssertEqual(chart.pillars.day.hanzi, e.str("dayPillar"), "\(id) day")
            XCTAssertEqual(chart.pillars.hour?.hanzi, e.optStr("hourPillar"), "\(id) hour")
            XCTAssertEqual(chart.yearAnimal.key, e.str("yearAnimal"), "\(id) animal")
            XCTAssertEqual(chart.lunarYearAnimal.key, e.str("lunarYearAnimal"), "\(id) lunar animal")
            XCTAssertEqual(chart.solarYear, e.int("solarYear"), "\(id) solar year")
            if chart.solarYear == date(c.str("date")).year {
                let err = abs(ChineseZodiac.lichun(chart.solarYear).timeIntervalSince(instant(e.str("lichunUtc"))))
                XCTAssertLessThanOrEqual(err, 121, "\(id) Lichun within 2 min of Swiss Ephemeris")
            }
        }
    }

    func testLichunDateMatchesHongKongObservatoryEveryYear() throws {
        let terms = try Golden.load("chinese_calendar.json").list("solarTerms")
        let lichun = terms.filter { $0.int("longitude") == 315 }
        XCTAssertEqual(lichun.count, 200)
        for t in lichun {
            let d = date(t.str("date"))
            XCTAssertEqual(LocalDate.of(ChineseZodiac.lichun(d.year), chinaTime), d, "Lichun \(d.year)")
        }
    }

    func testAllSolarTermsMatchHongKongObservatory() throws {
        let terms = try Golden.load("chinese_calendar.json").list("solarTerms")
        XCTAssertEqual(terms.count, 4800)
        var mismatches: [String] = []
        for t in terms {
            let d = date(t.str("date"))
            let local = ZonedDateTime(ChineseZodiac.solarTermInstant(d.year, longitude: t.double("longitude")), chinaTime)
            if local.date != d {
                // HKO's tables disagree with modern ephemerides by up to ~12 min for 1912-1928;
                // accept a date difference only within 15 minutes of midnight China time.
                let minutes = local.hour * 60 + local.minute
                if min(minutes, 1440 - minutes) > 15 { mismatches.append("\(t.str("name")) \(d) -> \(local.date) \(local.hour):\(local.minute)") }
            }
        }
        XCTAssertTrue(mismatches.isEmpty, "solar term dates differ from HKO: \(mismatches)")
    }

    /// Wikipedia "Sexagenary cycle" mental-calculation algorithm, 0-based (0 = 甲子).
    private func wikipediaDayIndex(_ d: LocalDate) -> Int {
        let r = (d.year % 400) % 80
        let y = (r * 5 + r / 4) % 60
        let c = d.year / 400 - d.year / 100 + 10
        let mTable = [0, 31, -1, 30, 0, 31, 1, 32, 3, 33, 4, 34]
        var m = mTable[d.month - 1]
        if d.isLeapYear && d.month <= 2 { m -= 1 }
        let sb = Math.floorMod(y + c + m + d.day, 60)
        return Math.floorMod(sb - 1, 60)
    }

    func testDayPillarMatchesWikipediaAlgorithmEveryDay1900To2100() {
        var d = LocalDate(1900, 1, 1)
        let end = LocalDate(2100, 12, 31)
        while d <= end {
            if ChineseZodiac.dayPillar(d).cycleIndex != wikipediaDayIndex(d) { XCTFail(d.description); break }
            d = d.plusDays(1)
        }
        XCTAssertEqual(ChineseZodiac.dayPillar(LocalDate(1949, 10, 1)).hanzi, "甲子")
        XCTAssertEqual(ChineseZodiac.dayPillar(LocalDate(1900, 1, 31)).hanzi, "甲辰")
        XCTAssertEqual(ChineseZodiac.dayPillar(LocalDate(2000, 1, 1)).hanzi, "戊午")
    }

    func testLunarNewYearTableCoversRangeAndFlipsAnimal() throws {
        XCTAssertEqual(LunarNewYear.firstYear, 1900)
        XCTAssertEqual(LunarNewYear.lastYear, 2100)
        for (y, value) in try Golden.load("chinese_calendar.json").obj("newYear") {
            let ny = date(value as! String), year = Int(y)!
            XCTAssertEqual(LunarNewYear.dates[year], ny)
            XCTAssertEqual(ChineseZodiac.lunarYearOf(ny), year)
            XCTAssertEqual(ChineseZodiac.lunarYearOf(ny.minusDays(1)), year - 1)
        }
        XCTAssertEqual(ChineseZodiac.yearPillar(2024).hanzi, "甲辰")
        XCTAssertEqual(ChineseZodiac.yearPillar(2026).branch.animal, .horse)
        XCTAssertEqual(ChineseZodiac.yearPillar(2026).stem.element, .fire)
    }

    func testPillarArithmetic() {
        for i in 0..<60 { XCTAssertEqual(Pillar.ofCycleIndex(i).cycleIndex, i) }
        XCTAssertEqual(HeavenlyStem.jia.yinYang, .yang)
        XCTAssertEqual(HeavenlyStem.gui.element, .water)
        XCTAssertEqual(HeavenlyStem.jia.name, "JIA")
        XCTAssertEqual(ChineseZodiac.hourPillar(.jia, hour: 0).hanzi, "甲子")
        XCTAssertEqual(ChineseZodiac.hourPillar(.yi, hour: 0).hanzi, "丙子")
        XCTAssertEqual(ChineseZodiac.hourPillar(.wu, hour: 22).hanzi, "癸亥")
        XCTAssertEqual(Pillar.ofCycleIndex(0).label, "甲子 Jiǎ-Zǐ (Yang Wood Rat)")
        XCTAssertEqual(ChineseAnimal.fromKey("Rooster"), .rooster)
    }

    func testBruceLeePublishedChart() throws {
        // Widely published BaZi of Bruce Lee (27 Nov 1940, ~07:12, San Francisco): 庚辰 丁亥 甲戌 戊辰.
        let birth = try BirthData(date: LocalDate(1940, 11, 27), time: LocalTime(7, 12), latitude: 37.7749,
                                  longitude: -122.4194, zoneId: "America/Los_Angeles")
        let p = ChineseZodiac.compute(birth).pillars
        XCTAssertEqual([p.year.hanzi, p.month.hanzi, p.day.hanzi, p.hour!.hanzi], ["庚辰", "丁亥", "甲戌", "戊辰"])
    }

    func testConventionsAndOptions() throws {
        let late = try BirthData(date: LocalDate(2023, 1, 21), time: LocalTime(23, 45), latitude: 31.23, longitude: 121.47, zoneId: "Asia/Shanghai")
        let byDefault = ChineseZodiac.compute(late)
        let midnight = ChineseZodiac.compute(late, options: ChineseOptions(ziHour: .dayStartsAtMidnight))
        XCTAssertEqual(byDefault.pillars.day, ChineseZodiac.dayPillar(LocalDate(2023, 1, 22)))
        XCTAssertEqual(midnight.pillars.day, ChineseZodiac.dayPillar(LocalDate(2023, 1, 21)))
        XCTAssertEqual(byDefault.pillars.hour, midnight.pillars.hour)

        let kashgar = try BirthData(date: LocalDate(1990, 6, 1), time: LocalTime(9, 30), latitude: 39.47, longitude: 75.99, zoneId: "Asia/Shanghai")
        XCTAssertEqual(ChineseZodiac.compute(kashgar).pillars.hour!.branch.hanzi, "巳")
        XCTAssertEqual(ChineseZodiac.compute(kashgar, options: ChineseOptions(timeBasis: .trueSolar)).pillars.hour!.branch.hanzi, "卯")
        XCTAssertEqual(ChineseZodiac.equationOfTimeMinutes(instant("1990-11-03T12:00:00Z")), 16.4, accuracy: 0.3)

        let unknown = ChineseZodiac.compute(try BirthData(date: LocalDate(1950, 2, 4), time: nil, latitude: 31.23, longitude: 121.47, zoneId: "Asia/Shanghai"))
        XCTAssertNil(unknown.pillars.hour)
        XCTAssertTrue(unknown.boundaryUncertain)
    }
}
