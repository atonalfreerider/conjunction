import Foundation
import CoreBluetooth
import UIKit
import ConjunctionCore

/// Radio status for the Nearby screen.
struct RadioStatus: Equatable {
    var poweredOn = false
    var unauthorized = false
    var unsupported = false
    /// Bluetooth is switched off in Control Centre / Settings (known, not just "not yet on").
    var poweredOff = false
    var advertising = false
    var scanning = false
    /// Conjunction phones currently close by (RSSI gate), whether or not they match.
    var closeBy = 0
    var error: String?
}

/// Bluetooth LE transport for the Nearby protocol (docs/P2P_PROTOCOL.md sections 2-3 and 12.2).
/// Every phone is at once a GATT **peripheral** (`CBPeripheralManager`: one primary service with
/// INBOX write-with-response and OUTBOX notify, advertising only the service UUID) and a
/// **central** (`CBCentralManager`: scans with the service-UUID filter, applies the RSSI proximity
/// gate, connects, discovers, subscribes to OUTBOX, then writes frames one Write Request at a time).
///
/// Everything runs on [queue], which is also the engine's queue: both managers deliver their
/// delegate callbacks there. Both are created with restore identifiers, so iOS can relaunch the app
/// for Bluetooth events after the system ended it (`bluetooth-central` / `bluetooth-peripheral`
/// background modes). Creating them shows the system Bluetooth prompt, so this object exists only
/// once the person turned Nearby on.
final class BleTransport: NSObject {
    static let centralRestoreId = "com.metavirtuoso.conjunction.central"
    static let peripheralRestoreId = "com.metavirtuoso.conjunction.peripheral"
    private static let tickInterval: TimeInterval = 1
    private static let setupTimeoutMs: Int64 = 20_000

    private let serviceUUID = CBUUID(string: P2PConstants.serviceUUID)
    private let inboxUUID = CBUUID(string: P2PConstants.inboxUUID)
    private let outboxUUID = CBUUID(string: P2PConstants.outboxUUID)

    private let queue: DispatchQueue
    private let engine: NearbyEngine
    private let log: (String) -> Void
    private let onStatus: (RadioStatus) -> Void
    /// True while the app is in the foreground (read on the queue; set through `setForeground`).
    private var foreground = true

    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var outbox: CBMutableCharacteristic?
    private var serviceAdded = false
    private var running = false
    private var timer: DispatchSourceTimer?
    private var gate = ProximityGate()
    private var reconnect = ReconnectPolicy()
    private var clients: [UUID: ClientConn] = [:]
    private var servers: [UUID: ServerConn] = [:]
    /// Connections handed back by state restoration. They are cancelled once the central is powered
    /// on (CoreBluetooth ignores `cancelPeripheralConnection` before that, with an API-misuse log)
    /// and are held until then so they are not released mid-connection.
    private var restoredPeripherals: [CBPeripheral] = []
    private(set) var status = RadioStatus() {
        didSet { if status != oldValue { onStatus(status) } }
    }

    init(queue: DispatchQueue, engine: NearbyEngine, log: @escaping (String) -> Void, onStatus: @escaping (RadioStatus) -> Void) {
        self.queue = queue
        self.engine = engine
        self.log = log
        self.onStatus = onStatus
        super.init()
        central = CBCentralManager(delegate: self, queue: queue, options: [
            CBCentralManagerOptionRestoreIdentifierKey: BleTransport.centralRestoreId,
            CBCentralManagerOptionShowPowerAlertKey: false,
        ])
        peripheralManager = CBPeripheralManager(delegate: self, queue: queue, options: [
            CBPeripheralManagerOptionRestoreIdentifierKey: BleTransport.peripheralRestoreId,
            CBPeripheralManagerOptionShowPowerAlertKey: false,
        ])
    }

    private static func nowMs() -> Int64 { P2PLink.nowMs() }

    // ------------------------------------------------------------------ lifecycle (on the queue)

    /// Nearby on and not paused: run when Bluetooth is powered on (now or later).
    func start() {
        guard !running else { return }
        running = true
        engine.start()
        startTicker()
        bringUp()
    }

    /// Says bye on every link, then stops advertising and scanning and drops every connection.
    func stop() {
        guard running else { return }
        running = false
        engine.stop()
        timer?.cancel()
        timer = nil
        if central.state == .poweredOn { central.stopScan() }
        if peripheralManager.state == .poweredOn { peripheralManager.stopAdvertising() }
        status.advertising = false
        status.scanning = false
        // Give the byes a moment to go out, then disconnect.
        queue.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self, !self.running else { return }
            for c in self.clients.values { c.forceClose() }
            self.clients.removeAll()
            self.servers.removeAll()
            if self.peripheralManager.state == .poweredOn { self.peripheralManager.removeAllServices() }
            self.serviceAdded = false
            self.outbox = nil
            self.gate.clear()
            self.status.closeBy = 0
        }
    }

    func setForeground(_ visible: Bool) { foreground = visible }

    /// Someone queued a message: reconnect to recently finished advertisers as soon as they are seen.
    func expediteReconnect() { reconnect.expedite(nowMs: BleTransport.nowMs()) }

    private func startTicker() {
        timer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + BleTransport.tickInterval, repeating: BleTransport.tickInterval, leeway: .milliseconds(200))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    private func tick() {
        guard running else { return }
        engine.tick()
        let now = BleTransport.nowMs()
        gate.prune(nowMs: now)
        for c in clients.values { c.checkTimeout(now) }
        for s in servers.values { s.checkIdle(now) }
        status.closeBy = gate.closeBy(nowMs: now)
    }

    private func bringUp() {
        guard running else { return }
        updateAuthorization()
        if peripheralManager.state == .poweredOn { publishService() }
        if central.state == .poweredOn { startScan() }
    }

    private func updateAuthorization() {
        let auth = CBManager.authorization
        status.unauthorized = auth == .denied || auth == .restricted
        status.poweredOn = central.state == .poweredOn && peripheralManager.state == .poweredOn
        status.unsupported = central.state == .unsupported
        status.poweredOff = central.state == .poweredOff
    }

    // ------------------------------------------------------------------ peripheral: service + advertising

    private func publishService() {
        guard running else { return }
        if serviceAdded, outbox != nil {
            startAdvertising()
            return
        }
        let inbox = CBMutableCharacteristic(type: inboxUUID, properties: [.write], value: nil, permissions: [.writeable])
        let out = CBMutableCharacteristic(type: outboxUUID, properties: [.notify], value: nil, permissions: [])
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [inbox, out]
        outbox = out
        peripheralManager.removeAllServices()
        peripheralManager.add(service)
    }

    private func startAdvertising() {
        guard running, !peripheralManager.isAdvertising else { return }
        // Only the 128-bit service UUID: no local name, nothing else (protocol 2.3).
        peripheralManager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    /// One central subscribed to our OUTBOX. Its link is created on its first INBOX write.
    final class ServerConn: LinkPort {
        unowned let transport: BleTransport
        let central: CBCentral
        var linkId: Int?
        var queue: [[UInt8]] = []
        var closing = false
        var finished = false
        var lastActivity: Int64
        var label: String { "peripheral of \(central.identifier.uuidString.suffix(5))" }

        init(transport: BleTransport, central: CBCentral) {
            self.transport = transport
            self.central = central
            lastActivity = BleTransport.nowMs()
        }

        func send(_ frames: [[UInt8]]) {
            queue += frames
            pump()
        }

        func close(_ outcome: LinkOutcome) {
            closing = true
            pump()
        }

        func discardPending() { queue.removeAll() }

        func pump() {
            guard let out = transport.outbox else { return }
            while let frame = queue.first {
                // Back-pressure: false = the transmit queue is full; peripheralManagerIsReady resumes.
                if !transport.peripheralManager.updateValue(Data(frame), for: out, onSubscribedCentrals: [central]) { return }
                queue.removeFirst()
            }
            if closing { finish() }
        }

        /// A CoreBluetooth peripheral cannot disconnect a central (protocol 12.2): after bye it just
        /// stops processing that central's frames; the central disconnects.
        func finish() {
            guard !finished else { return }
            finished = true
            // Not from inside the engine's own call: report the end on the next turn of the queue.
            if let id = linkId { transport.queue.async { [engine = transport.engine] in engine.onDisconnected(id) } }
        }

        func checkIdle(_ now: Int64) {
            // A subscribed central that never writes (or vanished without unsubscribing) is let go.
            if linkId == nil && now - lastActivity > BleTransport.setupTimeoutMs { transport.dropServer(central.identifier) }
        }
    }

    fileprivate func dropServer(_ id: UUID) {
        guard let s = servers.removeValue(forKey: id) else { return }
        s.queue.removeAll()
        if let link = s.linkId, !s.finished { engine.onDisconnected(link) }
    }

    private func onInboxWrite(_ central: CBCentral, _ frame: [UInt8]) {
        guard running else { return }
        let conn: ServerConn
        if let existing = servers[central.identifier] {
            conn = existing
        } else {
            conn = ServerConn(transport: self, central: central)
            servers[central.identifier] = conn
        }
        conn.lastActivity = BleTransport.nowMs()
        if conn.finished { return }
        // A link starts with the central's hello (msgId 0, index 0); anything else on a connection
        // without a link is left over from one that already ended: ignore it (protocol 3.7).
        if conn.linkId == nil && !FrameCodec.isLinkStart(frame) { return }
        if conn.linkId == nil {
            let mtu = central.maximumUpdateValueLength + 3
            guard let id = engine.openLink(role: .peripheral, port: conn, attMtu: mtu) else {
                // Over the link cap or Nearby not ready: do not answer (protocol 3.6); the central times out.
                log("refusing incoming link from \(central.identifier.uuidString.suffix(5))")
                conn.finished = true
                return
            }
            conn.linkId = id
        }
        engine.onFrame(conn.linkId!, frame)
    }

    // ------------------------------------------------------------------ central: scan + GATT client

    private func startScan() {
        guard running, central.state == .poweredOn else { return }
        // Duplicates are needed for the "2 sightings in 30 s" gate in the foreground; in the
        // background iOS coalesces them anyway (see onAdvertiser).
        central.scanForPeripherals(withServices: [serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        status.scanning = true
        log("scanning")
    }

    private func onAdvertiser(_ peripheral: CBPeripheral, rssi: Int) {
        guard running else { return }
        let now = BleTransport.nowMs()
        let key = peripheral.identifier.uuidString
        var close = gate.observe(key, rssi: rssi, nowMs: now)
        // In the background iOS reports each advertiser about once, so a second sighting may never
        // come: one strong sighting is enough then.
        if !close && !foreground && rssi >= gate.rssiThreshold && rssi < 20 { close = true }
        guard close else { return }
        if clients[peripheral.identifier] != nil { return }
        let linked = servers.values.filter { $0.linkId != nil && !$0.finished }.count
        if clients.count + linked >= NearbyEngine.maxLinks { return }
        if !reconnect.mayConnect(key, nowMs: now) { return }
        let conn = ClientConn(transport: self, peripheral: peripheral, startedAt: now)
        clients[peripheral.identifier] = conn
        log("connecting to \(key.suffix(5)) (rssi \(rssi))")
        central.connect(peripheral, options: nil)
    }

    /// Our connection to another phone's GATT server.
    final class ClientConn: NSObject, LinkPort, CBPeripheralDelegate {
        unowned let transport: BleTransport
        let peripheral: CBPeripheral
        let startedAt: Int64
        var inbox: CBCharacteristic?
        var linkId: Int?
        var queue: [[UInt8]] = []
        var writing = false
        var closing = false
        var outcome: LinkOutcome?
        var label: String { "central to \(peripheral.identifier.uuidString.suffix(5))" }

        init(transport: BleTransport, peripheral: CBPeripheral, startedAt: Int64) {
            self.transport = transport
            self.peripheral = peripheral
            self.startedAt = startedAt
            super.init()
            peripheral.delegate = self
        }

        func send(_ frames: [[UInt8]]) {
            queue += frames
            pump()
        }

        func close(_ outcome: LinkOutcome) {
            self.outcome = outcome
            closing = true
            pump()
        }

        func discardPending() { queue.removeAll() }

        private func pump() {
            guard !writing, let inbox else { return }
            guard let frame = queue.first else {
                if closing { transport.central.cancelPeripheralConnection(peripheral) }
                return
            }
            queue.removeFirst()
            writing = true
            peripheral.writeValue(Data(frame), for: inbox, type: .withResponse)
        }

        func checkTimeout(_ now: Int64) {
            if linkId == nil && now - startedAt > BleTransport.setupTimeoutMs { fail("setup timeout") }
        }

        func fail(_ why: String) {
            transport.log("\(label): \(why)")
            if outcome == nil && linkId == nil { outcome = .error }
            transport.central.cancelPeripheralConnection(peripheral)
        }

        func forceClose() { transport.central.cancelPeripheralConnection(peripheral) }

        // CBPeripheralDelegate (on the transport's queue)

        func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
            guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == transport.serviceUUID }) else {
                return fail("service discovery failed")
            }
            peripheral.discoverCharacteristics([transport.inboxUUID, transport.outboxUUID], for: service)
        }

        func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
            guard error == nil,
                  let inbox = service.characteristics?.first(where: { $0.uuid == transport.inboxUUID }),
                  let outbox = service.characteristics?.first(where: { $0.uuid == transport.outboxUUID }) else {
                return fail("characteristic discovery failed")
            }
            self.inbox = inbox
            // MUST subscribe before the first INBOX write (protocol 3.4).
            peripheral.setNotifyValue(true, for: outbox)
        }

        func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
            guard characteristic.uuid == transport.outboxUUID else { return }
            guard error == nil, characteristic.isNotifying else { return fail("could not subscribe to OUTBOX") }
            guard linkId == nil else { return }
            // Not .withResponse: that reports 512 because iOS would do long writes (protocol 2.4).
            let mtu = peripheral.maximumWriteValueLength(for: .withoutResponse) + 3
            guard let id = transport.engine.openLink(role: .central, port: self, attMtu: mtu) else {
                outcome = .shutdown
                transport.central.cancelPeripheralConnection(peripheral)
                return
            }
            linkId = id
        }

        func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
            writing = false
            if let error { return fail("write failed: \(error.localizedDescription)") }
            if let id = linkId { transport.engine.onMtu(id, attMtu: peripheral.maximumWriteValueLength(for: .withoutResponse) + 3) }
            pump()
        }

        func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
            guard error == nil, characteristic.uuid == transport.outboxUUID, let value = characteristic.value, let id = linkId else { return }
            transport.engine.onFrame(id, [UInt8](value))
        }

        func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
            if invalidatedServices.contains(where: { $0.uuid == transport.serviceUUID }) { fail("service went away") }
        }
    }

    private func clientDisconnected(_ peripheral: CBPeripheral, error: Error?) {
        guard let conn = clients.removeValue(forKey: peripheral.identifier) else { return }
        let now = BleTransport.nowMs()
        let key = peripheral.identifier.uuidString
        if let id = conn.linkId {
            engine.onDisconnected(id)
            reconnect.finished(key, outcome: conn.outcome ?? (error == nil ? .done : .timeout), nowMs: now)
        } else if conn.outcome == .shutdown {
            reconnect.finished(key, outcome: .shutdown, nowMs: now)
        } else {
            reconnect.failed(key, nowMs: now)
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension BleTransport: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        updateAuthorization()
        switch central.state {
        case .poweredOn:
            for p in restoredPeripherals where clients[p.identifier] == nil { central.cancelPeripheralConnection(p) }
            restoredPeripherals.removeAll()
            if running { startScan() }
        default:
            status.scanning = false
            // Bluetooth off / reset: every connection is gone.
            for (_, c) in clients { if let id = c.linkId { engine.onDisconnected(id) } }
            clients.removeAll()
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // The protocol has no resumption (2.5): connections that survived a relaunch are dropped and
        // made fresh; scanning restarts when the manager powers on. The manager is not powered on yet
        // here, so the cancel happens in centralManagerDidUpdateState.
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        restoredPeripherals = restored
        if central.state == .poweredOn {
            for p in restored { central.cancelPeripheralConnection(p) }
            restoredPeripherals.removeAll()
        }
        log("central restored (\(restored.count) peripherals)")
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        onAdvertiser(peripheral, rssi: RSSI.intValue)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let conn = clients[peripheral.identifier], running else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        // iOS exchanges the ATT MTU by itself on connection; the link reads it after subscribing.
        peripheral.delegate = conn
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        clients.removeValue(forKey: peripheral.identifier)
        reconnect.failed(peripheral.identifier.uuidString, nowMs: BleTransport.nowMs())
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        clientDisconnected(peripheral, error: error)
    }
}

// MARK: - CBPeripheralManagerDelegate

extension BleTransport: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        updateAuthorization()
        if peripheral.state == .poweredOn {
            if running { publishService() }
        } else {
            status.advertising = false
            serviceAdded = false
            outbox = nil
            for (id, _) in servers { dropServer(id) }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] ?? []
        if let service = services.first(where: { $0.uuid == serviceUUID }),
           let out = service.characteristics?.first(where: { $0.uuid == outboxUUID }) as? CBMutableCharacteristic {
            outbox = out
            serviceAdded = true
        }
        log("peripheral restored (\(services.count) services)")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            status.error = "Couldn't publish the Nearby service (\(error.localizedDescription))."
            return
        }
        serviceAdded = true
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            status.advertising = false
            status.error = "Couldn't advertise over Bluetooth (\(error.localizedDescription))."
            log("advertising failed: \(error)")
        } else {
            status.advertising = true
            status.error = nil
            log("advertising")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard running, characteristic.uuid == outboxUUID else { return }
        if servers[central.identifier] == nil { servers[central.identifier] = ServerConn(transport: self, central: central) }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == outboxUUID else { return }
        dropServer(central.identifier)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        // Frames always fit one ATT PDU (protocol 4.1): prepared / long writes are refused.
        if requests.count > 1 || requests.contains(where: { $0.offset != 0 || $0.characteristic.uuid != inboxUUID })
            || (first.value?.count ?? 0) > P2PConstants.maxFrameLen {
            peripheral.respond(to: first, withResult: .invalidAttributeValueLength)
            return
        }
        // Answer only after the frame went to the link, so ATT flow control throttles the sender;
        // always success: protocol errors travel as `error` messages (protocol 2.2).
        onInboxWrite(first.central, [UInt8](first.value ?? Data()))
        peripheral.respond(to: first, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        peripheral.respond(to: request, withResult: .readNotPermitted)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        for s in servers.values { s.pump() }
    }
}
