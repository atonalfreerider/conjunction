import Foundation

/// Local Nearby storage (the Swift twin of Android's `P2pStore`): friends, chats, the Matches
/// inbox and the hide/block list in one JSON file, written atomically; received photos are JPEG
/// files in [photosDirectory]. Nothing here is backed up or leaves the phone.
///
/// Not thread-safe: the app owns one store on its Bluetooth queue (the engine's thread) and hands
/// snapshots to the UI through [onChange]. [update] changes memory at once and marks the file dirty;
/// [flush] writes it (the engine calls it every tick, the app when it goes to the background).
public final class P2PStore {
    public static let maxMessagesPerFriend = 1000

    public private(set) var state: P2PState
    public let fileURL: URL?
    public let photosDirectory: URL
    private var dirty = false
    /// Called after every change with the new state (on the store's thread).
    public var onChange: ((P2PState) -> Void)?
    /// Extra attributes for written files (the iOS app passes complete-until-first-user-authentication protection).
    public var fileAttributes: [FileAttributeKey: Any] = [:]

    /// [fileURL] nil = memory only (tests). A missing or unreadable file starts empty.
    public init(fileURL: URL?, photosDirectory: URL) {
        self.fileURL = fileURL
        self.photosDirectory = photosDirectory
        if let url = fileURL, let data = try? Data(contentsOf: url), let decoded = P2PStore.decode(data) {
            state = decoded
        } else {
            state = P2PState()
        }
    }

    @discardableResult
    public func update(_ transform: (inout P2PState) -> Void) -> P2PState {
        var next = state
        transform(&next)
        if next != state {
            state = next
            dirty = true
            onChange?(state)
        }
        return state
    }

    /// Writes the newest state if it changed since the last write.
    public func flush() {
        guard dirty, let url = fileURL else { return }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true,
                                                    attributes: fileAttributes.isEmpty ? nil : fileAttributes)
            try P2PStore.encode(state).write(to: url, options: .atomic)
            if !fileAttributes.isEmpty { try? FileManager.default.setAttributes(fileAttributes, ofItemAtPath: url.path) }
            dirty = false
        } catch {
            // Stays dirty; the next flush tries again.
        }
    }

    /// Erases friends, chats, inbox, hide list and received photos (Delete all my data).
    public func clear() {
        update { $0 = P2PState() }
        flush()
        if let url = fileURL { try? FileManager.default.removeItem(at: url) }
        try? FileManager.default.removeItem(at: photosDirectory)
    }

    /// Where a received photo with this hash is kept.
    public func photoURL(_ fileName: String) -> URL { photosDirectory.appendingPathComponent(fileName) }

    public static func photoFileName(sha256: String) -> String { "\(sha256).jpg" }

    /// Stores [jpeg] under its hash; returns the file name, or nil when it could not be written.
    public func savePhoto(_ jpeg: [UInt8], sha256: String) -> String? {
        let name = P2PStore.photoFileName(sha256: sha256)
        let url = photoURL(name)
        if FileManager.default.fileExists(atPath: url.path) { return name }
        do {
            try FileManager.default.createDirectory(at: photosDirectory, withIntermediateDirectories: true,
                                                    attributes: fileAttributes.isEmpty ? nil : fileAttributes)
            try Data(jpeg).write(to: url, options: .atomic)
            return name
        } catch {
            return nil
        }
    }

    public func deletePhoto(_ fileName: String) { try? FileManager.default.removeItem(at: photoURL(fileName)) }

    // ------------------------------------------------------------------ JSON

    public static func encode(_ s: P2PState) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(s)) ?? Data("{}".utf8)
    }

    /// Nil for anything that is not a valid store document.
    public static func decode(_ data: Data) -> P2PState? {
        if data.isEmpty { return P2PState() }
        return try? JSONDecoder().decode(P2PState.self, from: data)
    }
}
