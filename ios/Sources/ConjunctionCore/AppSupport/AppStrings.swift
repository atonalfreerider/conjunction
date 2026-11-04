import Foundation

/// App strings that are not consent copy (Android `res/values/strings.xml`), with the same wording.
/// The display name lives in one place on iOS, `CFBundleDisplayName` (ios/App/project.yml); the
/// UI reads it from the bundle and falls back to [appName].
public enum AppText {
    public static let appName = "Conjunction"
    public static let tagline = "Your stars, nearby."
    public static let nearbyPausedUntil = "Paused until %1$s. Nothing is shared."
    public static let nearbyTurnOff = "Turn off"
    public static let nearbyResume = "Resume"
    public static let dailyReminderTitle = "Today's reading is ready"
    public static let dailyReminderBody = "Open Conjunction to see what the sky has written for you."
    public static let tabChart = "Chart"
    public static let tabToday = "Today"
    public static let tabEastern = "Eastern"
    public static let tabPalm = "Palm"
    public static let tabNearby = "Nearby"
    public static let tabProfile = "Profile"
    public static let entertainmentNote = "For entertainment only. Written on this phone; never sent anywhere."
    public static let aiSourceDevice = "Written on this phone by Apple Intelligence"
    public static let aiSourceFallback = "From Conjunction's bundled verses"
    public static let fallbackNote =
        "Written from Conjunction's bundled verses: this phone's on-device AI isn't available right now."
}

extension L10n {
    /// Fills Android-style placeholders: `%1$s` / `%1$d` (1-based, by position), `%s` / `%d` in
    /// order, and `%%`. Values are given as strings (ints formatted by the caller or `String(n)`).
    public static func format(_ template: String, _ args: String...) -> String {
        format(template, args: args)
    }

    public static func format(_ template: String, args: [String]) -> String {
        var out = ""
        var next = 0
        var i = template.startIndex
        while i < template.endIndex {
            let c = template[i]
            guard c == "%", let j = template.index(i, offsetBy: 1, limitedBy: template.endIndex), j < template.endIndex else {
                out.append(c)
                i = template.index(after: i)
                continue
            }
            if template[j] == "%" {
                out.append("%")
                i = template.index(after: j)
                continue
            }
            // %N$s / %N$d
            var k = j
            var digits = ""
            while k < template.endIndex, template[k].isASCII, template[k].isNumber {
                digits.append(template[k])
                k = template.index(after: k)
            }
            if !digits.isEmpty, k < template.endIndex, template[k] == "$",
               let typeIndex = template.index(k, offsetBy: 1, limitedBy: template.endIndex), typeIndex < template.endIndex,
               "sd".contains(template[typeIndex]), let n = Int(digits), n >= 1 {
                out += n <= args.count ? args[n - 1] : ""
                i = template.index(after: typeIndex)
                continue
            }
            if digits.isEmpty, "sd".contains(template[j]) {
                out += next < args.count ? args[next] : ""
                next += 1
                i = template.index(after: j)
                continue
            }
            out.append(c)
            i = template.index(after: i)
        }
        return out
    }
}
