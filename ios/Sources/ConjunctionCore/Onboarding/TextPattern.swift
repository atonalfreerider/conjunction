import Foundation

/// A java.util.regex-compatible pattern for the onboarding parsers: NSRegularExpression over UTF-16
/// (NSString) ranges, as Kotlin's Regex works on UTF-16 chars. The parsers use only ASCII classes
/// ([0-9], explicit boundaries) and \p{L}/\p{M}, which ICU and Java read the same way.
struct TextPattern: @unchecked Sendable {
    let re: NSRegularExpression

    init(_ pattern: String, ignoreCase: Bool = true) {
        re = try! NSRegularExpression(pattern: pattern, options: ignoreCase ? [.caseInsensitive] : [])
    }

    struct Match {
        let range: NSRange
        let groups: [String]

        func group(_ i: Int) -> String { groups[i] }
    }

    private func make(_ m: NSTextCheckingResult, _ ns: NSString) -> Match {
        Match(range: m.range, groups: (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        })
    }

    func find(_ s: String) -> Match? {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: ns.length)) else { return nil }
        return make(m, ns)
    }

    func findAll(_ s: String) -> [Match] {
        let ns = s as NSString
        return re.matches(in: s, options: [], range: NSRange(location: 0, length: ns.length)).map { make($0, ns) }
    }

    func contains(_ s: String) -> Bool { find(s) != nil }

    /// Kotlin Regex.matches: the whole string.
    func matchesEntire(_ s: String) -> Bool {
        guard let m = find(s) else { return false }
        return m.range.location == 0 && m.range.length == (s as NSString).length
    }

    /// Kotlin Regex.replace(input, literal).
    func replace(_ s: String, with literal: String) -> String {
        let ns = s as NSString
        return re.stringByReplacingMatches(in: s, options: [], range: NSRange(location: 0, length: ns.length),
                                           withTemplate: NSRegularExpression.escapedTemplate(for: literal))
    }
}

extension String {
    /// UTF-16 substring helpers matching Kotlin's String.substring(start, end).
    func utf16Prefix(_ end: Int) -> String { (self as NSString).substring(to: end) }
    func utf16Suffix(from start: Int) -> String { (self as NSString).substring(from: start) }
    var utf16Length: Int { (self as NSString).length }
}
