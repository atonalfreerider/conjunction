import Foundation
import UserNotifications
import UIKit
import ConjunctionCore

/// Local notifications (there is no push service: SPEC 5c). Matches, twins and mutual matches use
/// the popup art as an attachment; messages never show their text. Every notification carries the
/// `NearbyEvent` in userInfo, so tapping it opens the match animation or the chat.
enum Notifications {
    static let categoryMatch = "conjunction.match"
    static let categoryMessage = "conjunction.message"
    static let dailyId = "daily-reading"

    static func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    static func authorized() async -> Bool {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        return s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
    }

    static func show(_ event: NearbyEvent, peerName: String?, peerSun: String?) {
        let (title, body) = NearbyText.notification(event, peerName: peerName, sunSign: peerSun, appName: AppInfo.name)
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.userInfo = event.userInfo
        content.threadIdentifier = event.kind == .message ? "messages" : "conjunctions"
        content.categoryIdentifier = event.kind == .message ? categoryMessage : categoryMatch
        content.interruptionLevel = .active
        if let art = artFor(event.kind), let attachment = attachment(art) { content.attachments = [attachment] }
        let request = UNNotificationRequest(identifier: event.notificationId, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    static func artFor(_ kind: NearbyEventKind) -> String? {
        switch kind {
        case .match: return "popup_match_nearby"
        case .birthdayTwin, .exactTwin: return "popup_birthday_twins"
        case .conjunction: return "popup_its_a_conjunction"
        case .message: return nil
        }
    }

    /// Attachments must be files: the asset is written once to Caches.
    private static func attachment(_ asset: String) -> UNNotificationAttachment? {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("notify", isDirectory: true)
        let source = dir.appendingPathComponent("\(asset).jpg")
        if !FileManager.default.fileExists(atPath: source.path) {
            guard let data = UIImage(named: asset)?.jpegData(compressionQuality: 0.8) else { return nil }
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? data.write(to: source)
        }
        // The system moves the file it is given: hand it a fresh copy each time.
        let copy = dir.appendingPathComponent("\(asset)-\(UUID().uuidString).jpg")
        guard (try? FileManager.default.copyItem(at: source, to: copy)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: asset, url: copy)
    }

    static func cancel(_ event: NearbyEvent) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [event.notificationId])
    }

    /// The morning reminder ("Today's reading is ready") at 08:00 local, repeating.
    static func setDailyReminder(_ on: Bool) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [dailyId])
        guard on else { return }
        let content = UNMutableNotificationContent()
        content.title = AppText.dailyReminderTitle
        content.body = AppText.dailyReminderBody.replacingOccurrences(of: AppText.appName, with: AppInfo.name)
        content.sound = .default
        let trigger = UNCalendarNotificationTrigger(dateMatching: DateComponents(hour: 8, minute: 0), repeats: true)
        center.add(UNNotificationRequest(identifier: dailyId, content: content, trigger: trigger))
    }
}
