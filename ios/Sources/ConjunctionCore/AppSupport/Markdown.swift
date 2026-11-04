import Foundation

/// A block of the small Markdown subset the legal documents use (Android `MdBlock`).
public enum MdBlock: Hashable, Sendable {
    case heading(level: Int, text: String)
    case paragraph(String)
    case bullet(text: String, ordered: String?, indent: Int)
    case tableRow([String])
    case quote(String)
    case rule
}

/// One styled run of inline text.
public enum MdSpan: Hashable, Sendable {
    case plain(String)
    case bold(String)
    case italic(String)
    case code(String)
}

/// Minimal Markdown parser: headings, paragraphs, bullet / numbered lists, tables, quotes, rules.
/// A line-for-line port of the Android `MarkdownParser`, so legal documents read the same.
public enum MarkdownParser {
    public static func parse(_ markdown: String) -> [MdBlock] {
        var out: [MdBlock] = []
        var paragraph = ""
        func flush() {
            let t = paragraph.trimmingCharacters(in: .whitespaces)
            if !t.isEmpty { out.append(.paragraph(t)) }
            paragraph = ""
        }
        let lines = markdown.split(omittingEmptySubsequences: false) { $0 == "\n" || $0 == "\r\n" }
        for raw in lines {
            let line = String(raw).replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
            let trimmed = line.replacingOccurrences(of: "^\\s+", with: "", options: .regularExpression)
            let indent = (line.count - trimmed.count) / 2
            if trimmed.isEmpty {
                flush()
            } else if trimmed.hasPrefix("#") {
                flush()
                let level = trimmed.prefix { $0 == "#" }.count
                out.append(.heading(level: level, text: String(trimmed.dropFirst(level)).trimmingCharacters(in: .whitespaces)))
            } else if trimmed.range(of: #"^(-{3,}|\*{3,}|_{3,})$"#, options: .regularExpression) != nil {
                flush()
                out.append(.rule)
            } else if trimmed.hasPrefix("|") {
                flush()
                var inner = Substring(trimmed)
                while inner.first == "|" { inner = inner.dropFirst() }
                while inner.last == "|" { inner = inner.dropLast() }
                let cells = inner.split(separator: "|", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
                if cells.allSatisfy({ $0.isEmpty || $0.range(of: #"^:?-{2,}:?$"#, options: .regularExpression) != nil }) { continue }
                out.append(.tableRow(cells))
            } else if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") {
                flush()
                out.append(.bullet(text: String(trimmed.dropFirst(2)), ordered: nil, indent: indent))
            } else if let r = trimmed.range(of: #"^(\d+|[a-z])[.)]\s+"#, options: .regularExpression) {
                flush()
                let marker = trimmed[r].trimmingCharacters(in: .whitespaces).dropLast()
                out.append(.bullet(text: String(trimmed[r.upperBound...]), ordered: String(marker), indent: indent))
            } else if trimmed.hasPrefix(">") {
                flush()
                out.append(.quote(String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces)))
            } else {
                if !paragraph.isEmpty { paragraph += " " }
                paragraph += trimmed
            }
        }
        flush()
        return out
    }

    /// Inline **bold**, *italic* / _italic_, `code` and [links](url) (shown as their text).
    public static func inline(_ text: String) -> [MdSpan] {
        let s = Array(text.replacingOccurrences(of: #"\[([^\]]+)\]\(([^)]+)\)"#, with: "$1", options: .regularExpression))
        var spans: [MdSpan] = []
        var plain = ""
        func emit(_ span: MdSpan) {
            if !plain.isEmpty { spans.append(.plain(plain)); plain = "" }
            spans.append(span)
        }
        func find(_ needle: [Character], from: Int) -> Int? {
            guard from <= s.count - needle.count else { return nil }
            for k in from...(s.count - needle.count) where Array(s[k..<k + needle.count]) == needle { return k }
            return nil
        }
        var i = 0
        while i < s.count {
            let c = s[i]
            if c == "*", i + 1 < s.count, s[i + 1] == "*" {
                if let end = find(["*", "*"], from: i + 2) {
                    emit(.bold(String(s[(i + 2)..<end])))
                    i = end + 2
                } else {
                    plain += String(s[i...])
                    i = s.count
                }
            } else if (c == "*" || c == "_"), i + 1 < s.count, s[i + 1] != " ", i == 0 || !(s[i - 1].isLetter || s[i - 1].isNumber) {
                if let end = find([c], from: i + 1) {
                    emit(.italic(String(s[(i + 1)..<end])))
                    i = end + 1
                } else {
                    plain.append(c)
                    i += 1
                }
            } else if c == "`" {
                if let end = find(["`"], from: i + 1) {
                    emit(.code(String(s[(i + 1)..<end])))
                    i = end + 1
                } else {
                    plain.append(c)
                    i += 1
                }
            } else {
                plain.append(c)
                i += 1
            }
        }
        if !plain.isEmpty { spans.append(.plain(plain)) }
        return spans
    }
}

/// Chart-wheel geometry shared by the iOS drawing code (Android `ChartWheel.kt` helpers).
public enum ChartWheelGeometry {
    /// U+FE0E asks for the text (not emoji) presentation of astrological symbols.
    public static func textGlyph(_ glyph: String) -> String { glyph + "\u{FE0E}" }

    /// Screen angle (degrees, 0 = east, counter-clockwise positive) for an ecliptic longitude, with
    /// the Ascendant (or 0 deg Aries when the birth time is unknown) on the left.
    public static func wheelAngle(_ longitude: Double, ascendant: Double?) -> Double { 180.0 + (longitude - (ascendant ?? 0.0)) }

    /// Spreads planet positions apart so glyphs never overlap, keeping their order around the wheel.
    public static func spreadLongitudes(_ longitudes: [Double], minGap: Double = 7.0) -> [Double] {
        if longitudes.count < 2 { return longitudes }
        func norm(_ x: Double) -> Double { (x.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) }
        let order = longitudes.indices.sorted { norm(longitudes[$0]) < norm(longitudes[$1]) }
        var pos = order.map { norm(longitudes[$0]) }
        for _ in 0..<40 {
            var moved = false
            for i in pos.indices {
                let j = (i + 1) % pos.count
                var gap = pos[j] - pos[i]
                if j == 0 { gap += 360.0 }
                if gap < minGap {
                    let push = (minGap - gap) / 2
                    pos[i] -= push
                    pos[j] += push
                    moved = true
                }
            }
            if !moved { break }
        }
        var out = [Double](repeating: 0, count: longitudes.count)
        for (k, idx) in order.enumerated() { out[idx] = pos[k] }
        return out
    }
}
