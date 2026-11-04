import Foundation

// Chinese zodiac and Four Pillars (BaZi), ported from android/core .../chinese/ChineseZodiac.kt.

/// Wu Xing. [key] matches art/elements/<key>.png.
public enum FiveElement: Int, CaseIterable, Sendable {
    case wood, fire, earth, metal, water

    public var displayName: String { ["Wood", "Fire", "Earth", "Metal", "Water"][rawValue] }
    public var hanzi: String { ["木", "火", "土", "金", "水"][rawValue] }
    public var key: String { displayName.lowercased() }
    public var ordinal: Int { rawValue }

    /// The element this one nourishes in the generating (sheng) cycle.
    public var generates: FiveElement { FiveElement(rawValue: (rawValue + 1) % 5)! }
    /// The element this one restrains in the controlling (ke) cycle.
    public var controls: FiveElement { FiveElement(rawValue: (rawValue + 2) % 5)! }
}

public enum YinYang: Int, CaseIterable, Sendable {
    case yang, yin
    public var displayName: String { self == .yang ? "Yang" : "Yin" }
}

/// Twelve animals. [key] matches art/chinese/<key>.png.
public enum ChineseAnimal: Int, CaseIterable, Sendable {
    case rat, ox, tiger, rabbit, dragon, snake, horse, goat, monkey, rooster, dog, pig

    public var displayName: String {
        ["Rat", "Ox", "Tiger", "Rabbit", "Dragon", "Snake", "Horse", "Goat", "Monkey", "Rooster", "Dog", "Pig"][rawValue]
    }
    public var key: String { displayName.lowercased() }
    public var ordinal: Int { rawValue }

    /// The animal with this key, case-insensitive; nil when there is none (Kotlin's fromKey throws).
    public static func fromKey(_ key: String) -> ChineseAnimal? {
        let k = key.lowercased()
        return allCases.first { $0.key == k }
    }
}

/// Ten Heavenly Stems, index 0 = 甲.
public enum HeavenlyStem: Int, CaseIterable, Sendable {
    case jia, yi, bing, ding, wu, ji, geng, xin, ren, gui

    public var hanzi: String { ["甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"][rawValue] }
    public var pinyin: String { ["Jiǎ", "Yǐ", "Bǐng", "Dīng", "Wù", "Jǐ", "Gēng", "Xīn", "Rén", "Guǐ"][rawValue] }
    public var ordinal: Int { rawValue }
    /// Kotlin's enum name (JIA ...), used in uploaded summaries.
    public var name: String { String(describing: self).uppercased() }
    public var element: FiveElement { FiveElement(rawValue: rawValue / 2)! }
    public var yinYang: YinYang { rawValue % 2 == 0 ? .yang : .yin }
    /// "Yang Wood" etc.
    public var label: String { "\(yinYang.displayName) \(element.displayName)" }
}

/// Twelve Earthly Branches, index 0 = 子 (Rat).
public enum EarthlyBranch: Int, CaseIterable, Sendable {
    case zi, chou, yin, mao, chen, si, wu, wei, shen, you, xu, hai

    public var hanzi: String { ["子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"][rawValue] }
    public var pinyin: String { ["Zǐ", "Chǒu", "Yín", "Mǎo", "Chén", "Sì", "Wǔ", "Wèi", "Shēn", "Yǒu", "Xū", "Hài"][rawValue] }
    public var ordinal: Int { rawValue }
    public var name: String { String(describing: self).uppercased() }
    public var animal: ChineseAnimal { ChineseAnimal(rawValue: rawValue)! }

    /// Native element of the branch.
    public var element: FiveElement {
        let elements: [FiveElement] = [.water, .earth, .wood, .wood, .earth, .fire, .fire, .earth, .metal, .metal, .earth, .water]
        return elements[rawValue]
    }
}

/// One of the sixty stem-branch pairs.
public struct Pillar: Hashable, Sendable {
    public let stem: HeavenlyStem
    public let branch: EarthlyBranch

    /// Stem and branch must share parity (a programming error otherwise, as Kotlin's require).
    public init(_ stem: HeavenlyStem, _ branch: EarthlyBranch) {
        precondition(stem.ordinal % 2 == branch.ordinal % 2, "Invalid stem-branch pair \(stem)/\(branch)")
        self.stem = stem
        self.branch = branch
    }

    /// Position in the sexagenary cycle, 0 = 甲子 ... 59 = 癸亥.
    public var cycleIndex: Int { Math.floorMod(6 * stem.ordinal - 5 * branch.ordinal, 60) }

    public var hanzi: String { stem.hanzi + branch.hanzi }

    /// "甲子 Jiǎ-Zǐ (Yang Wood Rat)".
    public var label: String { "\(hanzi) \(stem.pinyin)-\(branch.pinyin) (\(stem.label) \(branch.animal.displayName))" }

    public static func ofCycleIndex(_ index: Int) -> Pillar {
        let i = Math.floorMod(index, 60)
        return Pillar(HeavenlyStem(rawValue: i % 10)!, EarthlyBranch(rawValue: i % 12)!)
    }
}

public struct FourPillars: Hashable, Sendable {
    public let year: Pillar
    public let month: Pillar
    public let day: Pillar
    public let hour: Pillar?
}

/// Which clock the hour (and day boundary) of the Four Pillars use.
public enum SolarTimeBasis: Sendable {
    /// Default: the local civil clock time as entered (what birth certificates record).
    case civil
    /// True (apparent) local solar time: civil UTC + longitude/15 h + equation of time.
    case trueSolar
}

/// How the 子 (Zi) hour 23:00-01:00 is split across days.
public enum ZiHourConvention: Sendable {
    /// Default (traditional): the new day - and its day pillar - begins at 23:00.
    case dayStartsAt23
    /// "Late Zi" school: the day pillar changes at midnight; 23:00-24:00 keeps the old day but takes
    /// the next day's Zi-hour stem.
    case dayStartsAtMidnight
}

public struct ChineseOptions: Hashable, Sendable {
    public var timeBasis: SolarTimeBasis
    public var ziHour: ZiHourConvention

    public init(timeBasis: SolarTimeBasis = .civil, ziHour: ZiHourConvention = .dayStartsAt23) {
        self.timeBasis = timeBasis
        self.ziHour = ziHour
    }
}

public struct ChineseChart: Hashable, Sendable {
    /// Animal of the solar (Lichun-bounded) year - the BaZi year.
    public let yearAnimal: ChineseAnimal
    /// Element of the year stem.
    public let yearElement: FiveElement
    public let yinYang: YinYang
    /// Animal of the lunar year (bounded by Chinese New Year) - the popular "what year are you".
    public let lunarYearAnimal: ChineseAnimal
    public let lunarYearElement: FiveElement
    public let pillars: FourPillars
    /// The solar year (Gregorian year in which this BaZi year began at Lichun).
    public let solarYear: Int
    /// The lunar year number (Gregorian year in which this lunar year began).
    public let lunarYear: Int
    /// True when the birth time is unknown and Lichun or a month-term falls on the birth date.
    public let boundaryUncertain: Bool

    /// The "Day Master": the day stem, the self in BaZi.
    public var dayMaster: HeavenlyStem { pillars.day.stem }
    public var yearsDiffer: Bool { yearAnimal != lunarYearAnimal }
}

/// Chinese zodiac and Four Pillars (BaZi).
///
/// - Year pillar: sexagenary year beginning at Lichun, the instant the apparent Sun reaches 315 deg.
///   Stem = (Y - 4) mod 10, branch = (Y - 4) mod 12; 1984 = 甲子.
/// - Lunar-year animal: from the bundled Chinese New Year table 1900-2100 (Hong Kong Observatory),
///   compared with the local civil birth date; outside the table it falls back to the solar year.
/// - Month pillar: the twelve "jie" terms (Sun at 315, 345, 15, ... deg) start the months 寅, 卯 ...;
///   stem by the "five tigers" rule from the year stem.
/// - Day pillar: continuous sexagenary day count, index = (JDN - 11) mod 60, on the local date.
/// - Hour pillar: branch from the two-hour 時辰, stem by the "five rats" rule. nil without a time.
public enum ChineseZodiac {
    private final class LichunCache: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [Int: Date] = [:]
        func value(_ year: Int, _ make: (Int) -> Date) -> Date {
            lock.lock()
            if let v = values[year] { lock.unlock(); return v }
            lock.unlock()
            let v = make(year)
            lock.lock(); values[year] = v; lock.unlock()
            return v
        }
    }

    private static let lichunCache = LichunCache()

    public static func compute(_ birth: BirthData, options: ChineseOptions = ChineseOptions()) -> ChineseChart {
        let instant = birth.instant
        let localDate = birth.date

        // Year (solar, Lichun-bounded).
        var solarYear = localDate.year
        if instant < lichun(solarYear) { solarYear -= 1 }
        let yearPillar = self.yearPillar(solarYear)

        // Month from the Sun's apparent longitude.
        let sunLon = Ephemeris.position(.sun, instant).longitude
        let monthPillar = self.monthPillar(yearPillar.stem, sunLongitude: sunLon)

        // Day and hour.
        let dayPillar: Pillar
        let hourPillar: Pillar?
        if birth.time == nil {
            dayPillar = self.dayPillar(localDate)
            hourPillar = nil
        } else {
            let clock: LocalDateTime
            switch options.timeBasis {
            case .civil: clock = LocalDateTime(birth.zonedDateTime)
            case .trueSolar: clock = trueSolarTime(instant, longitude: birth.longitude)
            }
            let lateZi = clock.hour == 23
            let dayDate = lateZi && options.ziHour == .dayStartsAt23 ? clock.date.plusDays(1) : clock.date
            dayPillar = self.dayPillar(dayDate)
            // In the late-Zi convention the 23:00 hour still takes the NEXT day's Zi stem.
            let stemDay = lateZi ? self.dayPillar(clock.date.plusDays(1)) : dayPillar
            hourPillar = self.hourPillar(stemDay.stem, hour: clock.hour)
        }

        // Lunar year from the new-year table.
        let lunarYear = lunarYearOf(localDate)
        let lunarPillar = self.yearPillar(lunarYear)

        var uncertain = false
        if birth.time == nil {
            let start = localDate.atStartOfDay(birth.zone)
            let end = localDate.plusDays(1).atStartOfDay(birth.zone)
            let a = Ephemeris.position(.sun, start).longitude
            let b = Ephemeris.position(.sun, end).longitude
            uncertain = (Angles.norm360(a - 315.0) / 30.0).rounded(.down) != (Angles.norm360(b - 315.0) / 30.0).rounded(.down)
        }

        return ChineseChart(
            yearAnimal: yearPillar.branch.animal, yearElement: yearPillar.stem.element, yinYang: yearPillar.stem.yinYang,
            lunarYearAnimal: lunarPillar.branch.animal, lunarYearElement: lunarPillar.stem.element,
            pillars: FourPillars(year: yearPillar, month: monthPillar, day: dayPillar, hour: hourPillar),
            solarYear: solarYear, lunarYear: lunarYear, boundaryUncertain: uncertain)
    }

    public static func yearPillar(_ year: Int) -> Pillar { Pillar.ofCycleIndex(year - 4) }

    /// Month pillar for a solar-year stem and the Sun's apparent longitude.
    public static func monthPillar(_ yearStem: HeavenlyStem, sunLongitude: Double) -> Pillar {
        let k = (Angles.norm360(sunLongitude - 315.0) / 30.0).rounded(.down).toInt().coerceIn(0, 11)
        let branch = EarthlyBranch(rawValue: (2 + k) % 12)!
        let stem = HeavenlyStem(rawValue: ((yearStem.ordinal % 5) * 2 + 2 + k) % 10)!
        return Pillar(stem, branch)
    }

    /// Day pillar of a civil date: index = (JDN - 11) mod 60 = (epochDay + 17) mod 60.
    public static func dayPillar(_ date: LocalDate) -> Pillar { Pillar.ofCycleIndex(Math.floorMod(date.epochDay + 17, 60)) }

    /// Hour pillar from the day stem and the clock hour 0..23.
    public static func hourPillar(_ dayStem: HeavenlyStem, hour: Int) -> Pillar {
        precondition((0...23).contains(hour), "hour out of range: \(hour)")
        let branchIndex = ((hour + 1) / 2) % 12
        let stem = HeavenlyStem(rawValue: ((dayStem.ordinal % 5) * 2 + branchIndex) % 10)!
        return Pillar(stem, EarthlyBranch(rawValue: branchIndex)!)
    }

    /// The Gregorian year whose lunar new year most recently began on or before [date].
    public static func lunarYearOf(_ date: LocalDate) -> Int {
        let y = date.year
        guard let thisYear = LunarNewYear.dates[y] else {
            return date >= LocalDate(y, 2, 4) ? y : y - 1
        }
        return date < thisYear ? y - 1 : y
    }

    /// Instant the apparent Sun reaches 315 deg (Lichun) in a Gregorian year.
    public static func lichun(_ year: Int) -> Date { lichunCache.value(year) { solarTermInstant($0, longitude: 315.0) } }

    /// Instant the apparent Sun reaches [longitude] (deg) during Gregorian [year]. For 285 and 300
    /// (Xiaohan, Dahan) that is in January; others follow in order through the year. The iteration
    /// runs on whole milliseconds, as the Kotlin engine's Instant arithmetic does.
    public static func solarTermInstant(_ year: Int, longitude: Double) -> Date {
        // The Sun is at ~280 deg on Jan 1; days after Jan 1 ~ (lon - 280) mod 360 / 0.9856.
        let guessDays = Angles.norm360(longitude - 280.0) / 0.98564736
        var millis = (Int64(LocalDate(year, 1, 1).epochDay) * 86_400 + (guessDays * 86_400.0).toLong()) * 1_000
        for _ in 0..<8 {
            let t = Date(epochMilli: millis)
            let p = Ephemeris.position(.sun, t)
            let diff = Angles.norm180(longitude - p.longitude)
            if abs(diff) < 1e-7 { return t }
            millis += (diff / p.speed * 86_400_000.0).toLong()
        }
        return Date(epochMilli: millis)
    }

    /// True local solar time (date and time) at an east-positive longitude.
    public static func trueSolarTime(_ instant: Date, longitude: Double) -> LocalDateTime {
        let offsetSeconds = longitude * 240.0 + equationOfTimeMinutes(instant) * 60.0
        let shifted = instant.addingTimeInterval(Double((offsetSeconds * 1000.0).toLong()) / 1000.0)
        return LocalDateTime(ZonedDateTime(shifted, TimeScales.utcZone))
    }

    /// Equation of time (apparent - mean solar time), minutes (Meeus 28.3).
    public static func equationOfTimeMinutes(_ instant: Date) -> Double {
        let jde = TimeScales.julianDayTt(instant)
        let tau = (jde - TimeScales.j2000) / 365_250.0
        let l0 = Angles.norm360(280.4664567 + 360_007.6982779 * tau + 0.03032028 * tau * tau +
            tau * tau * tau / 49_931.0 - tau * tau * tau * tau / 15_300.0 - pow(tau, 5.0) / 2_000_000.0)
        let nut = Ephemeris.nutation(jde)
        let lambda = Ephemeris.position(.sun, instant).longitude * Angles.deg
        let eps = nut.trueObliquity * Angles.deg
        let alpha = Angles.norm360(atan2(cos(eps) * sin(lambda), cos(lambda)) * Angles.rad)
        let e = Angles.norm180(l0 - 0.0057183 - alpha + nut.deltaPsi * cos(eps))
        return e * 4.0
    }
}

/// Chinese New Year dates 1900-2100 (China time), bundled as Resources/chinese_new_year.txt
/// (byte-for-byte copy of the Kotlin resource).
public enum LunarNewYear {
    public static let dates: [Int: LocalDate] = {
        do {
            var out: [Int: LocalDate] = [:]
            for line in try EphemerisData.read("chinese_new_year").map({ $0.trim() }) where !line.isEmpty && !line.hasPrefix("#") {
                let p = line.split(" ")
                guard p.count == 2, let y = Int(p[0]), let d = LocalDate.parse(p[1]) else {
                    throw EphemerisData.FormatError(description: "bad line \(line)")
                }
                out[y] = d
            }
            return out
        } catch {
            fatalError("Bundled chinese_new_year.txt is corrupt: \(error)")
        }
    }()

    public static var firstYear: Int { dates.keys.min()! }
    public static var lastYear: Int { dates.keys.max()! }
}
