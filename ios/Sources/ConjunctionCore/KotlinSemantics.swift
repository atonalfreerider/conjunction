import Foundation

// The few Kotlin and JVM library behaviours the port depends on where Swift's own differ in ways
// the Android reference data can see: java.lang.Math's conversions, Kotlin's whitespace, trim(),
// isBlank(), toIntOrNull() and split(), and java.time's Instant and Duration read from a Date.
// They keep their Kotlin names so ported lines read as the originals do.

/// java.lang.Math as the JDK computes it.
enum Math {
    /// Math.toDegrees: one multiplication by a rounded constant (not x * 180 / π), so results
    /// agree with Android to the last bit.
    static func toDegrees(_ angrad: Double) -> Double { angrad * 57.29577951308232 }

    /// Math.toRadians, likewise.
    static func toRadians(_ angdeg: Double) -> Double { angdeg * 0.017453292519943295 }

    /// Math.floorDiv (Kotlin's Int.floorDiv): the quotient rounded toward negative infinity.
    static func floorDiv(_ x: Int, _ y: Int) -> Int {
        let q = x / y
        return (x % y != 0 && (x < 0) != (y < 0)) ? q - 1 : q
    }
}

extension Double {
    /// Kotlin's Double.toLong(): truncates toward zero, NaN becomes 0 and values beyond the range
    /// of a Long saturate (Swift's Int64(_:) traps on both).
    func toLong() -> Int64 {
        if isNaN { return 0 }
        if self >= 9_223_372_036_854_775_808.0 { return .max }
        if self <= -9_223_372_036_854_775_808.0 { return .min }
        return Int64(self)
    }

    /// Kotlin's Double.toInt(), likewise, saturating at the range of Kotlin's 32-bit Int.
    func toInt() -> Int {
        if isNaN { return 0 }
        if self >= 2_147_483_647.0 { return Int(Int32.max) }
        if self <= -2_147_483_648.0 { return Int(Int32.min) }
        return Int(self)
    }
}

extension Unicode.Scalar {
    /// Kotlin's Char.isWhitespace() on the JVM: Character.isWhitespace(c) || Character.isSpaceChar(c).
    /// That is every space, line and paragraph separator (NBSP included), tab, line feed, vertical
    /// tab, form feed, carriage return and U+001C–U+001F, but not U+0085 or U+200B.
    var isKotlinWhitespace: Bool {
        switch value {
        case 0x09...0x0D, 0x1C...0x1F:
            return true
        default:
            switch properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }
}

extension String {
    /// Kotlin's String.trim(): drops [isKotlinWhitespace] characters from both ends. It works on
    /// Unicode scalars, as Kotlin works on chars, so a combining mark after a space is kept.
    func trim() -> String {
        let scalars = unicodeScalars
        guard let first = scalars.firstIndex(where: { !$0.isKotlinWhitespace }) else { return "" }
        let last = scalars.lastIndex(where: { !$0.isKotlinWhitespace })!
        return String(scalars[first...last])
    }

    /// Kotlin's CharSequence.isBlank(): empty or only [isKotlinWhitespace] characters.
    func isBlank() -> Bool { unicodeScalars.allSatisfy { $0.isKotlinWhitespace } }

    func isNotBlank() -> Bool { !isBlank() }

    /// Kotlin's String.toIntOrNull() (radix 10), step for step: an optional leading '+' or '-',
    /// then digits, where a digit is any UTF-16 unit Character.digit accepts (every Unicode
    /// decimal digit in the Basic Multilingual Plane, so "１２" and "١٩٩٠" parse), and nil past
    /// the range of a 32-bit Int.
    func toIntOrNull() -> Int? {
        let chars = Array(utf16)
        let length = chars.count
        if length == 0 { return nil }

        let start: Int
        let isNegative: Bool
        let limit: Int
        let firstChar = chars[0]
        if firstChar < 0x30 { // Possible leading sign ('0' is 0x30)
            if length == 1 { return nil } // non-digit (possible sign) only, no digits after
            start = 1
            if firstChar == 0x2D { // '-'
                isNegative = true
                limit = Int(Int32.min)
            } else if firstChar == 0x2B { // '+'
                isNegative = false
                limit = -Int(Int32.max)
            } else {
                return nil
            }
        } else {
            start = 0
            isNegative = false
            limit = -Int(Int32.max)
        }

        let radix = 10
        let limitForMaxRadix = (-Int(Int32.max)) / 36
        var limitBeforeMul = limitForMaxRadix
        var result = 0
        for i in start..<length {
            let digit = String.digitOf(chars[i], radix)
            if digit < 0 { return nil }
            if result < limitBeforeMul {
                if limitBeforeMul == limitForMaxRadix {
                    limitBeforeMul = limit / radix
                    if result < limitBeforeMul {
                        return nil
                    }
                } else {
                    return nil
                }
            }
            result *= radix
            if result < limit + digit { return nil }
            result -= digit
        }
        return isNegative ? result : -result
    }

    /// Character.digit(char, radix) for one UTF-16 unit: the value of a decimal digit (general
    /// category Nd) below [radix], else -1. A surrogate is never a digit on its own.
    private static func digitOf(_ unit: UInt16, _ radix: Int) -> Int {
        guard let scalar = Unicode.Scalar(unit),
              scalar.properties.generalCategory == .decimalNumber,
              let value = scalar.properties.numericValue else { return -1 }
        let digit = Int(value)
        return digit < radix ? digit : -1
    }

    /// Kotlin's split(delimiter, limit = limit) for one delimiter: every piece, empty ones
    /// included; with a positive [limit], at most that many, the last holding the rest.
    func split(_ delimiter: Unicode.Scalar, limit: Int = 0) -> [String] {
        var result: [String] = []
        var current = String.UnicodeScalarView()
        for scalar in unicodeScalars {
            if scalar == delimiter && (limit == 0 || result.count < limit - 1) {
                result.append(String(current))
                current = String.UnicodeScalarView()
            } else {
                current.append(scalar)
            }
        }
        result.append(String(current))
        return result
    }
}

extension Date {
    /// Instant.ofEpochMilli: the Date nearest to a whole number of milliseconds since 1970.
    init(epochMilli: Int64) {
        self.init(timeIntervalSinceReferenceDate: Double(epochMilli - 978_307_200_000) / 1_000)
    }

    /// Instant.getNano(): the nanoseconds past epochSecond (CivilTime.swift), taken from the same
    /// Double so the two always add back up to this Date. A Date resolves about 0.1 µs, so this
    /// stays fractional rather than rounding to java.time's whole nanoseconds.
    var nano: Double {
        let seconds = timeIntervalSince1970
        return (seconds - seconds.rounded(.down)) * 1_000_000_000
    }
}

/// Duration.between(from, to).toMillis(). Calendar instants are whole milliseconds, which a Date
/// holds only to about 0.1 µs, so the difference is rounded to the millisecond java.time computes
/// exactly (truncating would turn 90 minutes into 89.99998).
func durationMillis(_ from: Date, _ to: Date) -> Int64 {
    Int64((to.timeIntervalSince(from) * 1_000).rounded())
}

extension String {
    /// Kotlin's removePrefix: compares Unicode scalars exactly (String.hasPrefix compares graphemes).
    func removingPrefix(_ prefix: String) -> String {
        let p = prefix.unicodeScalars
        guard unicodeScalars.starts(with: p) else { return self }
        return String(unicodeScalars.dropFirst(p.count))
    }

    /// Kotlin's trim(vararg chars): drops any of [chars] from both ends.
    func trimming(_ chars: Set<Unicode.Scalar>) -> String {
        let scalars = unicodeScalars
        guard let first = scalars.firstIndex(where: { !chars.contains($0) }) else { return "" }
        let last = scalars.lastIndex(where: { !chars.contains($0) })!
        return String(scalars[first...last])
    }

    /// Kotlin's trimEnd(vararg chars).
    func trimmingEnd(_ chars: Set<Unicode.Scalar>) -> String {
        let scalars = unicodeScalars
        guard let last = scalars.lastIndex(where: { !chars.contains($0) }) else { return "" }
        return String(scalars[...last])
    }

    /// Kotlin's split(Regex("\\s+")) on the JVM, where \s is [ \t\n\x0B\f\r]: pieces between runs
    /// of those characters, keeping an empty piece at either end when the text starts or ends
    /// with one (Kotlin, unlike Java's String.split, keeps trailing empty strings).
    func splitOnJavaWhitespace() -> [String] {
        func isSpace(_ s: Unicode.Scalar) -> Bool {
            s == " " || s == "\t" || s == "\n" || s == "\u{0B}" || s == "\u{0C}" || s == "\r"
        }
        var result: [String] = []
        var current = String.UnicodeScalarView()
        var inRun = false
        for scalar in unicodeScalars {
            if isSpace(scalar) {
                if !inRun { result.append(String(current)); current = String.UnicodeScalarView(); inRun = true }
            } else {
                inRun = false
                current.append(scalar)
            }
        }
        result.append(String(current))
        return result
    }

    /// Kotlin's lineSequence() / lines(): splits on \r\n, \n and \r.
    func kotlinLines() -> [String] {
        var result: [String] = []
        var current = String.UnicodeScalarView()
        var previousCR = false
        for scalar in unicodeScalars {
            if scalar == "\n" {
                if !previousCR { result.append(String(current)) }
                current = String.UnicodeScalarView()
                previousCR = false
            } else if scalar == "\r" {
                result.append(String(current))
                current = String.UnicodeScalarView()
                previousCR = true
            } else {
                current.append(scalar)
                previousCR = false
            }
        }
        result.append(String(current))
        return result
    }
}
