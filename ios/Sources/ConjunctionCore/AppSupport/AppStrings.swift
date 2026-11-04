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
    public static let tabReadings = "Readings"
    public static let tabNearbyBadgeDesc = "Nearby, %1$d new matches"
    public static let readingsSegmentEastern = "Eastern"
    public static let readingsSegmentPalm = "Palm"
    public static let nearbyOffLine = "Nearby is off."
    public static let nearbyPausedLine = "Paused until %1$s."
    public static let nearbyEmptyOff = "Turn on Nearby to meet compatible people close by."
    public static let nearbyEmptyListening = "Listening for conjunctions… We'll let you know."
    public static let nearbyEmptyPaused = "Paused until %1$s."
    public static let nearbyTurnOnButton = "Turn on Nearby"
    public static let back = "Back"
    public static let nearbyTurnOnBluetooth = "Turn on Bluetooth"
    public static let nearbyOpenSettings = "Open Settings"
    public static let nearbySectionNew = "New matches"
    public static let nearbySectionWaiting = "Waiting"
    public static let nearbySectionConjunctions = "Conjunctions"
    public static let nearbyWaitingFor = "Waiting for %1$s"
    public static let nearbySayHi = "Say hi"
    public static let matchCardChart = "Chart"
    public static let matchPopupSayHi = "Say hi"
    public static let matchPopupSeeChart = "See our chart"
    public static let compatTitle = "You & %1$s"
    public static let compatLegend = "Inner: you · Outer: %1$s"
    public static let compatWestern = "Western"
    public static let compatChinese = "Chinese"
    public static let compatBlood = "Blood type"
    public static let compatAspectsTitle = "Aspects"
    public static let compatEasternTitle = "Eastern"
    public static let compatReadingTitle = "Reading"
    public static let compatWheelPending = "Your chart together appears the next time you're near %1$s."
    public static let onbWelcomeStart = "Get started"
    public static let onbBirthTitle = "When and where were you born?"
    public static let onbBirthHint = "e.g. Aug 7 1994, 4:53 pm, Providence RI"
    public static let onbChipAddDate = "Add date"
    public static let onbChipAddTime = "Add time"
    public static let onbChipAddPlace = "Add place"
    public static let onbChipTimeUnknown = "Time unknown"
    public static let onbBirthSeparately = "Enter each separately"
    public static let onbAboutTitle = "Tell us about you"
    public static let onbAboutHint = "e.g. I'm Sam, a single guy into hiking and jazz, looking to date women"
    public static let onbFirstName = "First name"
    public static let onbInterests = "Interests"
    public static let onbCardTitle = "Your card"
    public static let onbSkip = "Skip"
    public static let onbContinue = "Continue"
    public static let onbDone = "Done"
    public static let photoTake = "Take a photo"
    public static let bioLabel = "Bio"
    public static let bioWriteForMe = "Write one for me"
    public static let bioAnother = "Another"
    public static let instagramPaste = "Paste"
    public static let instagramInvalid = "That doesn't look like an Instagram username."
    public static let instagramChip = "Instagram"
    public static let instagramFromShareTitle = "Use @%1$s as your Instagram?"
    public static let instagramFromShareUse = "Use it"
    public static let instagramShareNotProfile = "Conjunction can only use an Instagram profile link here."
    public static let settingsTitle = "Settings"
    public static let settingsNearby = "Nearby"
    public static let settingsNotifications = "Notifications"
    public static let settingsNotifDaily = "Daily reading"
    public static let settingsNotifMatches = "Matches nearby"
    public static let settingsNotifMessages = "Messages"
    public static let settingsSnoozed = "Snoozed people"
    public static let menuSnooze = "Snooze 7 days"
    public static let menuBlock = "Block"
    public static let menuReport = "Report"
    public static let menuSafetyTips = "Safety Tips"
    public static let menuSafetyCode = "Safety code"
    public static let menuUnmatch = "Unmatch"
    public static let notNow = "Not now"
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
