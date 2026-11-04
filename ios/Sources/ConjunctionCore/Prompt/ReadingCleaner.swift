import Foundation

/// Tidies a model answer before display (port of prompt/ReadingCleaner.kt): strips a leading label
/// such as "Horoscope:", markdown heading lines and emphasis, wrapping quotes, and collapses blank-line runs.
/// Returns nil when nothing usable is left, so the caller can use the fallback reading.
///
/// The patterns are the Kotlin (java.util.regex) ones with `\s` / `\S` spelled out as Java's ASCII
/// classes, since ICU's `\s` would also match Unicode spaces.
public enum ReadingCleaner {
    private static let ws = "[ \\t\\n\\x0B\\f\\r]"
    private static let nonWs = "[^ \\t\\n\\x0B\\f\\r]"

    /// NSRegularExpression is immutable and documented thread-safe; the box makes that explicit on
    /// SDKs where the class is not annotated Sendable.
    private struct Pattern: @unchecked Sendable {
        let re: NSRegularExpression
    }

    private static func regex(_ pattern: String, _ options: NSRegularExpression.Options = []) -> Pattern {
        Pattern(re: try! NSRegularExpression(pattern: pattern, options: options))
    }

    private static let label = regex(
        "^(?:horoscope|reading|palm reading|daily horoscope|answer|response)\(ws)*[:\\-\u{2013}]\(ws)*", [.caseInsensitive])
    /// A markdown heading line is a title the model added; drop the whole line.
    private static let heading = regex("^#{1,6}[^\\n]*(\\n|$)", [.anchorsMatchLines])
    private static let emphasis = regex("(\\*\\*|__|\\*|_)(?=\(nonWs))(.+?)(?<=\(nonWs))\\1")
    private static let blanks = regex("\\n{3,}")

    private static func replace(_ p: Pattern, _ text: String, _ template: String) -> String {
        p.re.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: template)
    }

    public static func clean(_ raw: String?) -> String? {
        guard let raw else { return nil }
        var text = raw.trim()
        text = replace(heading, text, "")
        text = replace(emphasis, text, "$2")
        text = replace(label, text, "")
        text = text.trim().removingSurrounding("\"", "\"").removingSurrounding("\u{201C}", "\u{201D}").trim()
        text = replace(blanks, text, "\n\n")
        return text.isNotBlank() ? text : nil
    }
}

extension String {
    /// Kotlin's removeSurrounding(prefix, suffix): only when long enough to hold both and it
    /// starts and ends with them (compared as Unicode scalars).
    func removingSurrounding(_ prefix: String, _ suffix: String) -> String {
        let s = Array(unicodeScalars), p = Array(prefix.unicodeScalars), q = Array(suffix.unicodeScalars)
        guard s.count >= p.count + q.count, s.starts(with: p), s.suffix(q.count).elementsEqual(q) else { return self }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: s[p.count..<(s.count - q.count)])
        return String(view)
    }
}
