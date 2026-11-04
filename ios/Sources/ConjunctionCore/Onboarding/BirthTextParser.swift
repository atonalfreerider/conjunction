import Foundation

/// What `BirthTextParser` found in one free-text birth entry (SPEC 5d.5 screen 2).
public struct BirthTextResult: Hashable, Sendable {
    public var date: LocalDate?
    public var time: LocalTime?
    /// "time unknown", "don't know", "unknown time" ... (false whenever a time was found).
    public var timeUnknown: Bool
    /// The text left for the platform geocoder, or nil.
    public var placeQuery: String?
    /// A numeric M/D or D/M date whose two parts are both <= 12 (and differ): highlight the chip.
    public var ambiguousDate: Bool

    public init(date: LocalDate?, time: LocalTime?, timeUnknown: Bool, placeQuery: String?, ambiguousDate: Bool) {
        self.date = date
        self.time = time
        self.timeUnknown = timeUnknown
        self.placeQuery = placeQuery
        self.ambiguousDate = ambiguousDate
    }
}

/// Deterministic parser for "Aug 7 1994, 4:53 pm, Providence RI" style birth entries (SPEC 5d.5,
/// golden `testdata/golden_birth_text.json`). Port of Kotlin `onboarding.BirthTextParser` with the
/// same regex texts. The date is returned only when it exists in the calendar; use
/// `isAcceptableDate` for the 1900-01-01...today rule.
public enum BirthTextParser {
    /// Device regions that write numeric dates month first.
    public static let monthFirstRegions: Set<String> = ["US", "PH", "FM", "MH", "PW"]
    public static let earliestDate = LocalDate(1900, 1, 1)

    private static let b = "(?<![A-Za-z0-9])"
    private static let e = "(?![A-Za-z0-9])"
    private static let month =
        "(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"
    private static let months = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]

    private static let timeUnknownRe = TextPattern(
        "\(b)(?:(?:i +)?(?:don't|dont|do +not) +know(?: +(?:the|my))?(?: +(?:birth +)?time)?|(?:birth +)?time +(?:is +)?unknown" +
        "|unknown +(?:birth +)?time|(?:birth +)?time +not +known|no +(?:birth +)?time)\(e)")
    private static let iso = TextPattern("\(b)([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})\(e)")
    private static let monthDayYear = TextPattern("\(b)\(month)\\.? *([0-9]{1,2})(?:st|nd|rd|th)?(?: *, *| +)([0-9]{4})\(e)")
    private static let dayMonthYear = TextPattern("\(b)([0-9]{1,2})(?:st|nd|rd|th)?(?: +of)? +\(month)\\.?(?: *, *| +)([0-9]{4})\(e)")
    private static let dotted = TextPattern("\(b)([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{4}|[0-9]{2})\(e)")
    private static let numeric = TextPattern("\(b)([0-9]{1,2})([/-])([0-9]{1,2})\\2([0-9]{4}|[0-9]{2})\(e)")
    private static let noon = TextPattern("\(b)(?:noon|midday)\(e)")
    private static let midnight = TextPattern("\(b)midnight\(e)")
    private static let time12Min = TextPattern("\(b)([0-9]{1,2})[:.]([0-9]{2}) *([ap])\\.? ?m\\.?\(e)")
    private static let time12 = TextPattern("\(b)([0-9]{1,2}) *([ap])\\.? ?m\\.?\(e)")
    private static let time24 = TextPattern("\(b)([0-9]{1,2}):([0-9]{2})\(e)")
    private static let leadingWord = TextPattern("^(?:i +was +born|was +born|born|in|at|on)\(e)")
    private static let trailingWord = TextPattern("\(b)(?:born|in|at|on)$")
    private static let commas = TextPattern(" *,(?: *,)* *", ignoreCase: false)
    private static let spaces = TextPattern(" {2,}", ignoreCase: false)
    private static let edge: Set<Unicode.Scalar> = [" ", ",", ";", ".", ":", "-"]

    public static func parse(_ text: String, region: String?, today: LocalDate) -> BirthTextResult {
        var s = normalizeSpaces(text)
        var timeUnknown = false
        if let m = timeUnknownRe.find(s) { timeUnknown = true; s = cut(s, m.range) }

        var date: LocalDate?
        var ambiguous = false
        var dm: TextPattern.Match?
        if let m = iso.find(s) {
            dm = m; date = ymd(Int(m.group(1))!, Int(m.group(2))!, Int(m.group(3))!)
        } else if let m = monthDayYear.find(s) {
            dm = m; date = ymd(Int(m.group(3))!, monthNumber(m.group(1)), Int(m.group(2))!)
        } else if let m = dayMonthYear.find(s) {
            dm = m; date = ymd(Int(m.group(3))!, monthNumber(m.group(2)), Int(m.group(1))!)
        } else if let m = dotted.find(s) {
            dm = m; date = ymd(year(m.group(3), today), Int(m.group(2))!, Int(m.group(1))!)
        } else if let m = numeric.find(s) {
            dm = m
            let p = Int(m.group(1))!, q = Int(m.group(3))!
            let monthFirst = region.map { monthFirstRegions.contains($0.uppercased()) } ?? false
            date = monthFirst ? ymd(year(m.group(4), today), p, q) : ymd(year(m.group(4), today), q, p)
            ambiguous = date != nil && p <= 12 && q <= 12 && p != q
        }
        if let dm { s = cut(s, dm.range) }

        var time: LocalTime?
        var tm: TextPattern.Match?
        if let m = noon.find(s) {
            tm = m; time = LocalTime(12, 0)
        } else if let m = midnight.find(s) {
            tm = m; time = LocalTime(0, 0)
        } else if let m = time12Min.find(s) {
            tm = m; time = hm12(Int(m.group(1))!, Int(m.group(2))!, m.group(3))
        } else if let m = time12.find(s) {
            tm = m; time = hm12(Int(m.group(1))!, 0, m.group(2))
        } else if let m = time24.find(s) {
            tm = m
            let h = Int(m.group(1))!, mi = Int(m.group(2))!
            time = (0...23).contains(h) && (0...59).contains(mi) ? LocalTime(h, mi) : nil
        }
        if let tm { s = cut(s, tm.range) }
        if time != nil { timeUnknown = false }

        return BirthTextResult(date: date, time: time, timeUnknown: timeUnknown, placeQuery: place(s), ambiguousDate: ambiguous)
    }

    /// SPEC 5d.5 validation: >= 1900-01-01 and <= today.
    public static func isAcceptableDate(_ date: LocalDate, today: LocalDate) -> Bool { date >= earliestDate && date <= today }

    /// Unicode spaces -> ' ', curly apostrophes -> '\'' (one scalar for one scalar).
    static func normalizeSpaces(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for c in text.unicodeScalars {
            if InstagramHandle.isWhitespace(c) { out.append(" ") }
            else if c == "\u{2019}" || c == "\u{2018}" || c == "\u{02BC}" { out.append("'") }
            else { out.append(c) }
        }
        return String(out)
    }

    /// A date if it exists in the proleptic Gregorian calendar.
    static func ymd(_ y: Int, _ m: Int, _ d: Int) -> LocalDate? {
        guard (1...12).contains(m), d >= 1, d <= LocalDate.lengthOfMonth(y, m) else { return nil }
        return LocalDate(y, m, d)
    }

    private static func cut(_ s: String, _ r: NSRange) -> String {
        s.utf16Prefix(r.location) + "," + s.utf16Suffix(from: r.location + r.length)
    }

    private static func monthNumber(_ name: String) -> Int { (months.firstIndex(of: String(name.lowercased().prefix(3))) ?? -1) + 1 }

    private static func year(_ y: String, _ today: LocalDate) -> Int {
        let v = Int(y)!
        if y.count == 4 { return v }
        return v > today.year % 100 ? 1900 + v : 2000 + v
    }

    private static func hm12(_ h: Int, _ m: Int, _ ap: String) -> LocalTime? {
        guard (1...12).contains(h), (0...59).contains(m) else { return nil }
        return LocalTime(h % 12 + (ap.lowercased() == "p" ? 12 : 0), m)
    }

    private static func place(_ rest: String) -> String? {
        var s = rest
        while true {
            let before = s
            s = s.trimming(edge)
            if let m = leadingWord.find(s) { s = s.utf16Suffix(from: m.range.location + m.range.length) }
            if let m = trailingWord.find(s) { s = s.utf16Prefix(m.range.location) }
            if s == before { break }
        }
        s = commas.replace(s, with: ", ")
        s = spaces.replace(s, with: " ")
        return s.isEmpty ? nil : s
    }
}
