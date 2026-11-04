"""GATT central with bleak: scan for the service, proximity gate, connect, run links
(docs/P2P_PROTOCOL.md sections 2.4, 2.5, 3)."""
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
    def __init__(self, peer, rssi_min: int = -80, max_links: int = 4, adapter: str = "hci0"):
        self.peer = peer
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
        if rssi is None or rssi < self.rssi_min:
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

    async def _link(self, device):
        self._n += 1
        handle = f"C{self._n}:{device.address}"
        wake = asyncio.Event()
        self.wakers[handle] = wake
        inbox = asyncio.Queue()
        try:
            async with BleakClient(device, timeout=20.0) as client:
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
                await client.start_notify(ch_out, lambda _c, data: inbox.put_nowait(bytes(data)))
                link.start()
                while client.is_connected:
                    for f in self.peer.drain(handle):
                        await client.write_gatt_char(ch_in, f, response=True)
                    if link.closed:
                        await asyncio.sleep(0.2)
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
            self.busy.discard(device.address)
            self.last_done[device.address] = time.monotonic()
            log.info("[%s] disconnected", handle)
