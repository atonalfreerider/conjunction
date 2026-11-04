"""GATT central with bleak: scan for the service, proximity gate, connect, run links
(docs/P2P_PROTOCOL.md sections 2.4, 2.5, 3).

Two test-peer specifics:
- When a link ends while the peripheral role is still linked with the same device (the pair's two
  links share one LE connection), the connection is left up: bleak's disconnect() is BlueZ's
  Device1.Disconnect, which would drop it for both links (protocol 7.2). The idle client is parked
  and reused if the central links with that device again.
- A phone that is already connected re-advertises under a new random address after every
  connection, and BlueZ cannot tell it is the same phone; connecting to it times out (one LE
  connection per pair of controllers). Unless --multi-phone is given, addresses first seen while a
  link with a phone is up are therefore not connected to."""
import asyncio
import logging
import time
from collections import defaultdict, deque

from bleak import BleakClient, BleakScanner

from . import consts as C
from .link import CENTRAL

log = logging.getLogger("p2p.central")

PROXIMITY_WINDOW_S = 30
RECONNECT_BACKOFF_S = 120


class Central:
    def __init__(self, peer, rssi_min: int = -80, max_links: int = 4, adapter: str = "hci0", multi_phone: bool = False):
        self.peer = peer
        self.multi_phone = multi_phone
        self.peripheral = None                # the test peer's Peripheral, if it runs both roles (set by sim.py)
        self.first_seen = {}                  # address -> monotonic time of its first advertisement
        self.linked_since = {}                # address -> monotonic time its central link came up
        self.parked = {}                      # address -> connected BleakClient with no link (shared connection)
        self._skipped = set()
        self.rssi_min = rssi_min
        self.max_links = max_links
        self.adapter = adapter
        self.sightings = defaultdict(deque)   # address -> monotonic times of qualifying sightings
        self.busy = set()                     # addresses with a link open / opening
        self.last_done = {}                   # address -> monotonic time the last link ended
        self.wakers = {}                      # handle -> asyncio.Event
        self._n = 0
        self._scanner = None

    async def run(self):
        self._scanner = BleakScanner(detection_callback=self._seen, service_uuids=[C.SERVICE_UUID],
                                     adapter=self.adapter)
        await self._scanner.start()
        log.info("scanning for %s (RSSI >= %d dBm, 2 sightings in %d s)", C.SERVICE_UUID, self.rssi_min, PROXIMITY_WINDOW_S)
        try:
            while True:
                await asyncio.sleep(3600)
        finally:
            await self._scanner.stop()

    def wake(self, handle):
        ev = self.wakers.get(handle)
        if ev:
            ev.set()

    def _seen(self, device, adv):
        if C.SERVICE_UUID not in [u.lower() for u in (adv.service_uuids or [])]:
            return
        now = time.monotonic()
        rssi = adv.rssi
        log.debug("adv %s rssi %s name=%r", device.address, rssi, adv.local_name)
        first = self.first_seen.setdefault(device.address, now)
        if rssi is None or rssi < self.rssi_min:
            return
        since = self._live_since()
        if (not self.multi_phone and since is not None and first > since and device.address not in self.last_done
                and device.address not in self.busy):
            if device.address not in self._skipped:
                self._skipped.add(device.address)
                log.info("not connecting to %s: it appeared while a link with a phone is up (most likely that phone's "
                         "new advertising address; --multi-phone to connect anyway)", device.address)
            return
        q = self.sightings[device.address]
        q.append(now)
        while q and now - q[0] > PROXIMITY_WINDOW_S:
            q.popleft()
        if len(q) < 2 or device.address in self.busy or len(self.busy) >= self.max_links:
            return
        if now - self.last_done.get(device.address, -1e9) < RECONNECT_BACKOFF_S:
            return
        self.busy.add(device.address)
        log.info("nearby: %s (rssi %d) -> connecting", device.address, rssi)
        asyncio.get_event_loop().create_task(self._link(device))

    def linked_with(self, address: str) -> bool:
        """True while a central link with this address is up."""
        return address in self.linked_since

    def _live_since(self):
        """Monotonic time since which a link with some phone has been up (either role), else None."""
        times = list(self.linked_since.values())
        if self.peripheral is not None and self.peripheral.link_since is not None:
            times.append(self.peripheral.link_since)
        return min(times) if times else None

    async def _link(self, device):
        self._n += 1
        address = device.address
        handle = f"C{self._n}:{address}"
        wake = asyncio.Event()
        self.wakers[handle] = wake
        inbox = asyncio.Queue()
        client = self.parked.pop(address, None)
        if client is not None and not client.is_connected:
            client = None
        if client is None:
            client = BleakClient(device, timeout=20.0,
                                 disconnected_callback=lambda c: self.parked.pop(address, None) if self.parked.get(address) is c else None)
        ch_out = None
        keep_connection = False
        try:
            if not client.is_connected:
                await client.connect()
            else:
                log.info("[%s] reusing the LE connection left up by an earlier link", handle)
            svc = client.services.get_service(C.SERVICE_UUID)
            if svc is None:
                log.warning("[%s] service missing after discovery", handle)
                return
            ch_in = svc.get_characteristic(C.INBOX_UUID)
            ch_out = svc.get_characteristic(C.OUTBOX_UUID)
            # BlueZ exchanges the MTU itself (requesting 517). bleak exposes it as the
            # characteristic's write-without-response size (MTU - 3) when BlueZ reports it.
            mtu = C.DEFAULT_ATT_MTU
            try:
                mtu = max(mtu, ch_in.max_write_without_response_size + 3)
            except Exception:
                pass
            link = self.peer.new_link(handle, CENTRAL, mtu)
            self.linked_since[address] = time.monotonic()
            await client.start_notify(ch_out, lambda _c, data: inbox.put_nowait(bytes(data)))
            link.start()
            while client.is_connected:
                for f in self.peer.drain(handle):
                    await client.write_gatt_char(ch_in, f, response=True)
                if link.closed:
                    await asyncio.sleep(0.2)
                    keep_connection = (client.is_connected and self.peripheral is not None
                                       and self.peripheral.linked_with(address))
                    break
                try:
                    frame = await asyncio.wait_for(inbox.get(), timeout=0.25)
                    self.peer.feed(handle, frame)
                    while not inbox.empty():
                        self.peer.feed(handle, inbox.get_nowait())
                except asyncio.TimeoutError:
                    self.peer.tick()
                wake.clear()
        except Exception as e:
            log.warning("[%s] link failed: %s: %s", handle, type(e).__name__, e)
        finally:
            self.peer.drop_link(handle)
            self.wakers.pop(handle, None)
            self.linked_since.pop(address, None)
            if keep_connection:
                # Device1.Disconnect would also end the peripheral link with this device.
                try:
                    await client.stop_notify(ch_out)
                except Exception as e:
                    log.debug("[%s] stop_notify: %s", handle, e)
                self.parked[address] = client
                log.info("[%s] link over; LE connection left up (our peripheral link with %s uses it)", handle, address)
            else:
                try:
                    await client.disconnect()
                except Exception as e:
                    log.debug("[%s] disconnect: %s", handle, e)
                log.info("[%s] disconnected", handle)
            self.busy.discard(address)
            self.last_done[address] = time.monotonic()
