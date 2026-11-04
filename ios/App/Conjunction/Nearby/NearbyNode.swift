import Foundation
import os
import ConjunctionCore

/// The Bluetooth side of Nearby: store, engine and (while Nearby runs) the CoreBluetooth transport,
/// all on one serial queue, as the Android app's `P2pNode` runs them on one thread. It is the
/// engine's `NearbyHost`. The UI talks to it through `NearbyRepository`, which posts here.
final class NearbyNode: NearbyHost, @unchecked Sendable {
    static let logger = Logger(subsystem: "com.metavirtuoso.conjunction", category: "ConjunctionP2P")

    let queue = DispatchQueue(label: "com.metavirtuoso.conjunction.bluetooth", qos: .userInitiated)
    private(set) var store: P2PStore!
    private(set) var engine: NearbyEngine!
    private(set) var transport: BleTransport?
    private let screener = ContentScreener()

    // Set from the main actor, read on the queue.
    private let lock = NSLock()
    private var _local: LocalPeerProfile?
    private var _foreground = true

    /// Callbacks to the main actor (set once by the repository).
    var onState: ((P2PState) -> Void)?
    var onEngineStatus: ((EngineStatus) -> Void)?
    var onRadioStatus: ((RadioStatus) -> Void)?
    var onAnnounce: ((NearbyEvent, String?, String?) -> Void)?

    init() {
        queue.sync {
            let s = P2PStore(fileURL: AppFiles.nearbyURL, photosDirectory: AppFiles.nearbyPhotos)
            s.fileAttributes = AppFiles.protection
            s.onChange = { [weak self] state in self?.onState?(state) }
            store = s
            let e = NearbyEngine(store: s, identity: { IdentityKeychain.identity() }, host: self)
            e.onStatus = { [weak self] st in self?.onEngineStatus?(st) }
            engine = e
        }
    }

    var foreground: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _foreground }
        set {
            lock.lock()
            _foreground = newValue
            lock.unlock()
            post { [weak self] in self?.transport?.setForeground(newValue) }
        }
    }

    func setLocalProfile(_ p: LocalPeerProfile?) {
        lock.lock()
        _local = p
        lock.unlock()
    }

    func post(_ block: @escaping () -> Void) { queue.async(execute: block) }

    // ------------------------------------------------------------------ radio

    /// Creates the CoreBluetooth managers (first time: the system Bluetooth prompt) and starts.
    func startRadio() {
        post { [weak self] in
            guard let self else { return }
            if self.transport == nil {
                self.transport = BleTransport(queue: self.queue, engine: self.engine,
                                              log: { [weak self] in self?.log($0) },
                                              onStatus: { [weak self] in self?.onRadioStatus?($0) })
                self.transport?.setForeground(self.foreground)
            }
            self.transport?.start()
        }
    }

    func stopRadio() {
        post { [weak self] in
            guard let self else { return }
            self.transport?.stop()
            if self.transport == nil { self.engine.stop() }
            self.store.flush()
        }
    }

    func flush() { post { [weak self] in self?.store.flush() } }

    // ------------------------------------------------------------------ NearbyHost (on the queue)

    func localProfile() -> LocalPeerProfile? {
        lock.lock()
        defer { lock.unlock() }
        return _local
    }

    func announce(_ event: NearbyEvent, peerName: String?, peerSun: String?) { onAnnounce?(event, peerName, peerSun) }

    func screenMessage(id: String, text: String) {
        let fg = foreground
        Task { [weak self, screener = self.screener] in
            let verdict = await screener.screenText(text, foreground: fg)
            self?.post { self?.engine.applyMessageScreening(id: id, verdict: verdict) }
        }
    }

    func screenPhoto(fileName: String) {
        let url = store.photoURL(fileName)
        Task { [weak self, screener = self.screener] in
            let verdict = await screener.screenPhoto(at: url)
            self?.post { self?.engine.applyPhotoScreening(fileName: fileName, verdict: verdict) }
        }
    }

    func log(_ message: String) { NearbyNode.logger.info("\(message, privacy: .public)") }
}
