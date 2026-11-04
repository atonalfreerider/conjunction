import Foundation
import ConjunctionCore

/// Where the app keeps its files: Application Support/Conjunction, excluded from iCloud/iTunes
/// backups (the data is sensitive and the identity key cannot move phones anyway). Files are
/// protected "until first unlock", so Bluetooth work in the background can still read them.
enum AppFiles {
    static let protection: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]

    static let root: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent("Conjunction", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: protection)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        return dir
    }()

    static func url(_ name: String) -> URL { root.appendingPathComponent(name) }

    static var profileURL: URL { url("profile.json") }
    static var consentsURL: URL { url("consents.json") }
    static var readingsURL: URL { url("readings.json") }
    static var nearbyURL: URL { url("nearby/p2p.json") }
    static var nearbyPhotos: URL { url("nearby/photos") }
    static var ownerPhotoURL: URL { url("profile_photo.jpg") }
    static var palmsDir: URL { url("palms") }

    static func read<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    static func write<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        write(data, to: url)
    }

    static func write(_ data: Data, to url: URL) {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true,
                                                 attributes: protection)
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Delete all my data: everything under the app's folder.
    static func eraseAll() {
        if let items = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            for item in items { try? FileManager.default.removeItem(at: item) }
        }
    }
}

/// App version line used in consent records and report emails.
enum AppInfo {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0" }
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1" }
    /// The single place the display name lives on iOS is CFBundleDisplayName (SPEC).
    static var name: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? AppText.appName }
    /// Support address for reports (store/appstore and Android BuildConfig.SUPPORT_EMAIL).
    static var supportEmail: String {
        Bundle.main.object(forInfoDictionaryKey: "ConjunctionSupportEmail") as? String ?? "themetavirtuoso@gmail.com"
    }
}

/// Bundled legal documents (legal/*.md, copied into the app by the Xcode project).
enum LegalTexts {
    static func load(_ doc: LegalDoc) -> LegalText? {
        guard let url = Bundle.main.url(forResource: doc.resourceName, withExtension: "md"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return LegalText(doc: doc, markdown: text)
    }
}
