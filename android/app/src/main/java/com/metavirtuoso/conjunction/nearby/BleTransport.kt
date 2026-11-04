package com.metavirtuoso.conjunction.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.metavirtuoso.conjunction.core.p2p.LinkRole
import com.metavirtuoso.conjunction.core.p2p.P2pConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RadioStartResult { STARTED, NO_PERMISSION, BLUETOOTH_OFF, UNSUPPORTED }

/** Radio status for the Nearby screen and the service notification. */
data class RadioStatus(
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val serving: Boolean = false,
    /** Conjunction phones currently close by (RSSI gate), whether or not they match. */
    val closeBy: Int = 0,
    val error: String? = null,
)

/**
 * Bluetooth LE transport for the Nearby protocol (docs/P2P_PROTOCOL.md sections 2-3, 12.1):
 * every phone is at once a GATT **peripheral** (advertises only the service UUID; GATT server with
 * INBOX write-with-response and OUTBOX notify) and a **central** (scans with a service-UUID
 * filter, applies the RSSI proximity gate, connects, requests ATT_MTU 517, discovers, enables
 * OUTBOX notifications through the CCCD, then writes frames one Write Request at a time).
 *
 * All callbacks run on [handler]'s thread, which is also the [engine]'s thread: GATT client
 * callbacks are delivered there directly (connectGatt with a Handler), server / scan / advertise
 * callbacks are posted to it. Requires Android 12+ (API 31) and the BLUETOOTH_SCAN /
 * BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT permissions, checked in [start].
 */
@RequiresApi(Build.VERSION_CODES.S)
@SuppressLint("MissingPermission") // checked in start(); every entry point bails out when not running
class BleTransport(
    private val context: Context,
    private val engine: NearbyEngine,
    private val handler: Handler,
    private val log: (String) -> Unit,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var running = false
    private var server: BluetoothGattServer? = null
    private var outbox: BluetoothGattCharacteristic? = null
    private val servers = HashMap<String, ServerConn>()
    private val clients = HashMap<String, ClientConn>()
    val gate = ProximityGate()
    private val reconnect = ReconnectPolicy()
    private val _status = MutableStateFlow(RadioStatus())
    val status: StateFlow<RadioStatus> = _status.asStateFlow()

    @Volatile private var requestedScanMode: Int = ScanSettings.SCAN_MODE_BALANCED
    /** Mode of the scan that is running (engine thread only); -1 = none. */
    private var activeScanMode = -1
    /** Recent startScan times: Android silently refuses a 6th start within 30 s, so we stay under it. */
    private val scanStarts = ArrayDeque<Long>()
    private var scanRetryPosted = false
    private val scanRetry = Runnable {
        scanRetryPosted = false
        if (running && activeScanMode == -1) startScan()
    }
    /** A graceful stop's delayed teardown; run at once if Nearby restarts before it fires. */
    private var pendingTeardown: Runnable? = null

    /** SCAN_MODE_* for the scanner (balanced by default; low latency while the app is open). Any thread. */
    var scanMode: Int
        get() = requestedScanMode
        set(value) {
            requestedScanMode = value
            handler.post { if (running && activeScanMode != -1 && activeScanMode != requestedScanMode) restartScan() }
        }

    // ------------------------------------------------------------------ lifecycle (engine thread)

    fun start(): RadioStartResult {
        if (running) return RadioStartResult.STARTED
        // Paused and resumed within the grace period: finish the old teardown first, or it would
        // close the new GATT server and links when it fires.
        pendingTeardown?.let { handler.removeCallbacks(it); it.run() }
        if (!NearbyPermissions.granted(context)) return RadioStartResult.NO_PERMISSION
        val a = adapter ?: return RadioStartResult.UNSUPPORTED
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return RadioStartResult.UNSUPPORTED
        if (!a.isEnabled) return RadioStartResult.BLUETOOTH_OFF
        if (a.bluetoothLeAdvertiser == null || !a.isMultipleAdvertisementSupported) return RadioStartResult.UNSUPPORTED
        running = true
        engine.start()
        openServer()
        startScan()
        handler.postDelayed(ticker, TICK_MS)
        return RadioStartResult.STARTED
    }

    /** Says bye on every link, then tears the radio down. [graceful] = give the byes time to go out. */
    fun stop(graceful: Boolean) {
        if (!running) return
        running = false
        handler.removeCallbacks(ticker)
        engine.stop()
        stopScan()
        stopAdvertising()
        handler.removeCallbacks(scanRetry)
        scanRetryPosted = false
        val teardown = object : Runnable {
            override fun run() {
                if (pendingTeardown === this) pendingTeardown = null
                clients.values.toList().forEach { it.forceClose() }
                clients.clear()
                // server.close() unregisters the callback, so no disconnect events follow: end the
                // peripheral-side links in the engine here (otherwise they count against the link cap forever).
                servers.values.toList().forEach { it.release() }
                servers.clear()
                runCatching { server?.close() }
                server = null
                outbox = null
                gate.clear()
                _status.value = RadioStatus()
            }
        }
        if (graceful) {
            pendingTeardown = teardown
            handler.postDelayed(teardown, 1_500)
        } else teardown.run()
    }

    /** Someone queued a message: reconnect to recently-finished advertisers as soon as they are seen. */
    fun expediteReconnect() {
        reconnect.expedite(SystemClock.elapsedRealtime())
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            engine.tick()
            val now = SystemClock.elapsedRealtime()
            gate.prune(now)
            reconnect.prune(now)
            clients.values.toList().forEach { it.checkTimeout(now) }
            _status.value = _status.value.copy(closeBy = gate.closeBy(now))
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------ peripheral: GATT server + advertising

    private fun openServer() {
        val s = manager?.openGattServer(context, serverCallback) ?: run {
            setError("Couldn't start the Bluetooth server.")
            return
        }
        server = s
        val service = BluetoothGattService(P2pConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val inbox = BluetoothGattCharacteristic(P2pConstants.INBOX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val out = BluetoothGattCharacteristic(P2pConstants.OUTBOX_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        out.addDescriptor(BluetoothGattDescriptor(P2pConstants.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(inbox)
        service.addCharacteristic(out)
        outbox = out
        if (!s.addService(service)) setError("Couldn't publish the Nearby service.")
        // Advertising starts in onServiceAdded, once the service exists.
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            handler.post { _status.value = _status.value.copy(advertising = true); log("advertising") }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                _status.value = _status.value.copy(advertising = false)
                log("advertising failed: $errorCode")
                if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) setError("Couldn't advertise over Bluetooth (error $errorCode).")
            }
        }
    }

    private fun startAdvertising() {
        if (!running) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Flags + the 128-bit service UUID only: no name, no manufacturer/service data, no TX power (protocol 2.3).
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(P2pConstants.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { setError("Couldn't advertise over Bluetooth.") }
    }

    private fun stopAdvertising() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        _status.value = _status.value.copy(advertising = false)
    }

    /** One central connected to our GATT server. The link is created on its first CCCD/INBOX write. */
    private inner class ServerConn(val device: BluetoothDevice) : LinkPort {
        var mtu = P2pConstants.DEFAULT_ATT_MTU
        var subscribed = false
        var linkId: Int? = null
        private val queue = ArrayDeque<ByteArray>()
        private var notifying = false
        private var closing = false
        private var finishing = false
        private var retries = 0
        override val label: String get() = "peripheral of ${device.address.takeLast(5)}"

        override fun send(frames: List<ByteArray>) {
            queue.addAll(frames)
            pump()
        }

        override fun close(outcome: LinkOutcome) {
            closing = true
            pump()
        }

        fun pump() {
            if (notifying) return
            val s = server
            val char = outbox
            if (queue.isEmpty() || s == null || char == null || !subscribed) {
                // Not subscribed: nothing queued can ever go out, so finish anyway.
                if (closing && (queue.isEmpty() || !subscribed)) finish()
                return
            }
            val frame = queue.first()
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                s.notifyCharacteristicChanged(device, char, false, frame) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { char.value = frame; s.notifyCharacteristicChanged(device, char, false) }
            }
            if (ok) {
                queue.removeFirst()
                notifying = true
                retries = 0
            } else if (++retries > MAX_RETRIES) {
                log("notifications to ${device.address.takeLast(5)} keep failing; dropping the link")
                queue.clear()
                closing = true
                finish()
            } else {
                handler.postDelayed({ if (servers[device.address] === this) pump() }, RETRY_MS)
            }
        }

        fun onSent() {
            notifying = false
            pump()
        }

        private fun finish() {
            if (finishing) return
            finishing = true
            // The central disconnects after bye (protocol 3, 5.3). If it does not, end the link for
            // the engine and drop the connection, unless we are also its central on another link
            // (then the connection stays and a later hello on it opens a fresh link).
            handler.postDelayed({
                if (servers[device.address] === this) {
                    release()
                    if (clients[device.address] == null) runCatching { server?.cancelConnection(device) }
                }
            }, 2_000)
        }

        /** Ends this connection's current link in the engine (idempotent). */
        fun release() {
            linkId?.let(engine::onDisconnected)
            linkId = null
            queue.clear()
            closing = false
            finishing = false
            retries = 0
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _status.value = _status.value.copy(serving = true)
                    startAdvertising()
                } else setError("Couldn't publish the Nearby service (status $status).")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Android reports our own outgoing (central) connections here too, so no link yet.
                    if (running) servers.getOrPut(device.address) { ServerConn(device) }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    servers.remove(device.address)?.linkId?.let(engine::onDisconnected)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                val c = servers.getOrPut(device.address) { ServerConn(device) }
                c.mtu = mtu
                c.linkId?.let { engine.onMtu(it, mtu) }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            handler.post {
                val ok = descriptor.uuid == P2pConstants.CCCD_UUID && !preparedWrite && offset == 0 && value != null && value.size == 2
                if (ok && running) {
                    val c = servers.getOrPut(device.address) { ServerConn(device) }
                    c.subscribed = (value!![0].toInt() and 0x01) != 0
                    c.pump()
                }
                if (responseNeeded) {
                    runCatching { server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null) }
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            handler.post {
                val on = servers[device.address]?.subscribed == true
                val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v) }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            handler.post { runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null) } }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            handler.post {
                val status = when {
                    characteristic.uuid != P2pConstants.INBOX_UUID -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    preparedWrite || offset != 0 || value == null || value.size > P2pConstants.MAX_FRAME_LEN -> GATT_INVALID_ATTRIBUTE_LENGTH
                    else -> {
                        // Pass the frame to the link first, then answer (ATT flow control throttles the sender).
                        onInboxWrite(device, value)
                        BluetoothGatt.GATT_SUCCESS
                    }
                }
                if (responseNeeded) runCatching { server?.sendResponse(device, requestId, status, offset, null) }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post { runCatching { server?.sendResponse(device, requestId, GATT_INVALID_ATTRIBUTE_LENGTH, 0, null) } }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post { servers[device.address]?.onSent() }
        }
    }

    private fun onInboxWrite(device: BluetoothDevice, frame: ByteArray) {
        if (!running) return
        val c = servers.getOrPut(device.address) { ServerConn(device) }
        val id = c.linkId ?: engine.openLink(LinkRole.PERIPHERAL, c, c.mtu).also { c.linkId = it }
        if (id == null) {
            // Over the link cap or Nearby not ready: do not answer (protocol 3.6).
            log("refusing incoming link from ${device.address.takeLast(5)}")
            handler.postDelayed({ runCatching { server?.cancelConnection(device) } }, 200)
            return
        }
        engine.onFrame(id, frame)
    }

    // ------------------------------------------------------------------ central: scan + GATT client

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onAdvertiser(result.device, result.rssi) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post { results.forEach { onAdvertiser(it.device, it.rssi) } }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                log("scan failed: $errorCode")
                if (errorCode != SCAN_FAILED_ALREADY_STARTED) activeScanMode = -1
                _status.value = _status.value.copy(scanning = errorCode == SCAN_FAILED_ALREADY_STARTED)
                if (errorCode != SCAN_FAILED_ALREADY_STARTED && running) handler.postDelayed({ startScan() }, 10_000)
            }
        }
    }

    private fun startScan() {
        if (!running || activeScanMode != -1) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        // Android ignores (without any callback) an app's 6th startScan within 30 s, which would
        // leave Nearby silently deaf; foreground/background switches restart the scan, so throttle.
        val now = SystemClock.elapsedRealtime()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() >= SCAN_START_WINDOW_MS) scanStarts.removeFirst()
        if (scanStarts.size >= MAX_SCAN_STARTS_PER_WINDOW) {
            if (!scanRetryPosted) {
                scanRetryPosted = true
                handler.postDelayed(scanRetry, SCAN_START_WINDOW_MS - (now - scanStarts.first()) + 250)
            }
            return
        }
        val mode = requestedScanMode
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(P2pConstants.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()
        scanStarts.addLast(now)
        runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onSuccess { activeScanMode = mode; _status.value = _status.value.copy(scanning = true); log("scanning (mode $mode)") }
            .onFailure { setError("Couldn't scan over Bluetooth.") }
    }

    private fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        activeScanMode = -1
        _status.value = _status.value.copy(scanning = false)
    }

    private fun restartScan() {
        if (!running) return
        stopScan()
        startScan()
    }

    private fun onAdvertiser(device: BluetoothDevice, rssi: Int) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (!gate.observe(device.address, rssi, now)) return
        val address = device.address
        if (clients.containsKey(address)) return
        if (servers[address]?.linkId != null) return // already linked to it as its peripheral
        if (clients.size + servers.values.count { it.linkId != null } >= NearbyEngine.MAX_LINKS) return
        if (!reconnect.mayConnect(address, now)) return
        val conn = ClientConn(device, now)
        clients[address] = conn
        log("connecting to ${address.takeLast(5)} (rssi $rssi)")
        conn.connect()
    }

    private inner class ClientConn(val device: BluetoothDevice, private val startedAt: Long) : LinkPort {
        var gatt: BluetoothGatt? = null
        var mtu = P2pConstants.DEFAULT_ATT_MTU
        var inbox: BluetoothGattCharacteristic? = null
        var linkId: Int? = null
        private val queue = ArrayDeque<ByteArray>()
        private var writing = false
        private var closing = false
        private var outcome: LinkOutcome? = null
        private var disconnectRequestedAt = 0L
        private var finished = false
        private var discovering = false
        private var retries = 0
        override val label: String get() = "central to ${device.address.takeLast(5)}"

        fun connect() {
            gatt = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler) }.getOrNull()
            if (gatt == null) fail("connectGatt returned null")
        }

        override fun send(frames: List<ByteArray>) {
            queue.addAll(frames)
            pump()
        }

        override fun close(outcome: LinkOutcome) {
            closing = true
            this.outcome = outcome
            pump()
        }

        private fun pump() {
            val g = gatt ?: return
            val c = inbox ?: return
            if (writing) return
            if (queue.isEmpty()) {
                if (closing) disconnect()
                return
            }
            val frame = queue.first()
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = frame; g.writeCharacteristic(c) }
            }
            if (ok) {
                queue.removeFirst()
                writing = true
                retries = 0
            } else if (++retries > MAX_RETRIES) {
                // The stack keeps refusing the write: give up (otherwise a closing link never disconnects).
                log("INBOX writes to ${device.address.takeLast(5)} keep failing; disconnecting")
                if (outcome == null) outcome = LinkOutcome.ERROR
                queue.clear()
                disconnect()
            } else {
                handler.postDelayed({ if (clients[device.address] === this) pump() }, RETRY_MS)
            }
        }

        /** Exactly one service discovery per connection (onMtuChanged may be delivered more than once). */
        private fun discover(g: BluetoothGatt) {
            if (discovering) return
            discovering = true
            if (!g.discoverServices()) fail("discoverServices refused")
        }

        private fun disconnect() {
            if (disconnectRequestedAt != 0L) return
            disconnectRequestedAt = SystemClock.elapsedRealtime()
            runCatching { gatt?.disconnect() }
        }

        fun checkTimeout(now: Long) {
            when {
                // No disconnect callback after asking: close the client ourselves.
                disconnectRequestedAt != 0L && now - disconnectRequestedAt > 4_000 -> cleanup(BluetoothGatt.GATT_SUCCESS)
                // Connect + MTU + discovery + CCCD must finish in time.
                linkId == null && now - startedAt > SETUP_TIMEOUT_MS -> fail("setup timeout")
            }
        }

        private fun fail(why: String) {
            log("link to ${device.address.takeLast(5)} failed: $why")
            runCatching { gatt?.disconnect() }
            cleanup(-1)
        }

        /** Bluetooth turned off / Nearby stopped without grace. */
        fun forceClose() {
            runCatching { gatt?.disconnect() }
            cleanup(BluetoothGatt.GATT_SUCCESS)
        }

        private fun cleanup(status: Int) {
            if (finished) return
            finished = true
            runCatching { gatt?.close() }
            gatt = null
            if (clients[device.address] === this) clients.remove(device.address)
            linkId?.let(engine::onDisconnected)
            val now = SystemClock.elapsedRealtime()
            val o = outcome
            when {
                o != null -> reconnect.finished(device.address, o, now)
                linkId != null -> reconnect.finished(device.address, LinkOutcome.TIMEOUT, now)
                else -> reconnect.failed(device.address, now)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) log("gatt to ${device.address.takeLast(5)} ended with status $status")
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // Protocol 2.4: request the largest MTU first, before anything else.
                    if (!g.requestMtu(P2pConstants.REQUESTED_ATT_MTU)) discover(g)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    cleanup(status)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                linkId?.let { engine.onMtu(it, mtu) }
                if (inbox == null) discover(g)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(P2pConstants.SERVICE_UUID)
                val inChar = service?.getCharacteristic(P2pConstants.INBOX_UUID)
                val outChar = service?.getCharacteristic(P2pConstants.OUTBOX_UUID)
                val cccd = outChar?.getDescriptor(P2pConstants.CCCD_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || inChar == null || outChar == null || cccd == null) {
                    outcome = LinkOutcome.ERROR
                    return fail("no Conjunction service (status $status)")
                }
                inbox = inChar
                if (!g.setCharacteristicNotification(outChar, true)) return fail("setCharacteristicNotification refused")
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
                }
                if (!ok) fail("CCCD write refused")
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != P2pConstants.CCCD_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) return fail("CCCD write status $status")
                // Notifications on: the link may start (central sends hello).
                val id = engine.openLink(LinkRole.CENTRAL, this@ClientConn, mtu)
                if (id == null) {
                    outcome = LinkOutcome.SHUTDOWN
                    closing = true
                    disconnect()
                    return
                }
                linkId = id
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                writing = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("INBOX write failed: $status")
                    outcome = LinkOutcome.ERROR
                    queue.clear()
                    disconnect()
                    return
                }
                pump()
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == P2pConstants.OUTBOX_UUID) linkId?.let { engine.onFrame(it, value) }
            }

            @Deprecated("API < 33 path")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                if (characteristic.uuid == P2pConstants.OUTBOX_UUID) linkId?.let { engine.onFrame(it, value) }
            }
        }
    }

    private fun setError(message: String) {
        log(message)
        _status.value = _status.value.copy(error = message)
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val RETRY_MS = 30L
        /** Consecutive refused writes / notifications (x [RETRY_MS]) before a link is dropped. */
        private const val MAX_RETRIES = 100
        private const val SCAN_START_WINDOW_MS = 30_000L
        private const val MAX_SCAN_STARTS_PER_WINDOW = 4
        private const val SETUP_TIMEOUT_MS = 20_000L
        /** ATT error 0x0D Invalid Attribute Value Length (protocol 2.2). */
        private const val GATT_INVALID_ATTRIBUTE_LENGTH = 0x0D
    }
}

/** The Android 12+ "Nearby devices" runtime permissions Nearby needs (no location). */
object NearbyPermissions {
    @android.annotation.SuppressLint("InlinedApi") // only requested / checked on API 31+
    val BLUETOOTH: Array<String> = arrayOf(
        android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    /** False below Android 12, where Nearby is not offered. */
    fun granted(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && BLUETOOTH.all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
