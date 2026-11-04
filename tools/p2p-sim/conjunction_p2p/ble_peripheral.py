"""GATT peripheral on BlueZ over D-Bus (dbus-fast), implementing docs/P2P_PROTOCOL.md section 2.

Why not bless: bless 0.3.0 (a) renames the adapter (persistent Alias change) when it starts
advertising, (b) always puts a LocalName in the advertisement, and (c) drops BlueZ's WriteValue
options, so it cannot learn the ATT MTU or tell centrals apart. This module talks to BlueZ's
GattManager1 / LEAdvertisingManager1 directly with the same D-Bus library bleak uses, and changes
no adapter settings. Everything it registers disappears when the process exits.

Limitation: BlueZ sends a characteristic's notifications to every subscribed client, so this
peripheral serves one central at a time; a second central's writes are rejected until the first
link ends."""
import asyncio
import logging

from dbus_fast import BusType, Message, MessageType, Variant
from dbus_fast.aio import MessageBus
from dbus_fast.constants import PropertyAccess
from dbus_fast.errors import DBusError
from dbus_fast.service import ServiceInterface, dbus_property, method

from . import consts as C
from .link import PERIPHERAL

log = logging.getLogger("p2p.peripheral")

BASE = "/org/metavirtuoso/conjunction"
NOTIFY_GAP_S = 0.008  # pace notifications (~125 frames/s, under the receiver's 150/s refill)


class Advertisement(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.LEAdvertisement1")

    @method()
    def Release(self):  # noqa: N802
        log.info("advertisement released by BlueZ")

    @dbus_property(access=PropertyAccess.READ)
    def Type(self) -> "s":  # noqa: N802
        return "peripheral"

    @dbus_property(access=PropertyAccess.READ)
    def ServiceUUIDs(self) -> "as":  # noqa: N802,F722
        return [C.SERVICE_UUID]


class Service(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.GattService1")

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":  # noqa: N802
        return C.SERVICE_UUID

    @dbus_property(access=PropertyAccess.READ)
    def Primary(self) -> "b":  # noqa: N802
        return True


class Inbox(ServiceInterface):
    def __init__(self, server: "Peripheral"):
        super().__init__("org.bluez.GattCharacteristic1")
        self.server = server

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":  # noqa: N802
        return C.INBOX_UUID

    @dbus_property(access=PropertyAccess.READ)
    def Service(self) -> "o":  # noqa: N802
        return BASE + "/service0"

    @dbus_property(access=PropertyAccess.READ)
    def Flags(self) -> "as":  # noqa: N802,F722
        return ["write"]

    @method()
    def WriteValue(self, value: "ay", options: "a{sv}"):  # noqa: N802,F722
        opts = {k: v.value for k, v in options.items()}
        if opts.get("offset", 0) != 0 or opts.get("type") == "reliable" or opts.get("prepare-authorize"):
            raise DBusError("org.bluez.Error.InvalidValueLength", "long/prepared writes are not used")
        if len(value) > C.MAX_FRAME_LEN:
            raise DBusError("org.bluez.Error.InvalidValueLength", "frame longer than 512")
        if not self.server.on_write(opts.get("device", "?"), int(opts.get("mtu", C.DEFAULT_ATT_MTU)), bytes(value)):
            raise DBusError("org.bluez.Error.NotPermitted", "busy with another central")


class Outbox(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.GattCharacteristic1")
        self._value = b""
        self.notifying = False

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":  # noqa: N802
        return C.OUTBOX_UUID

    @dbus_property(access=PropertyAccess.READ)
    def Service(self) -> "o":  # noqa: N802
        return BASE + "/service0"

    @dbus_property(access=PropertyAccess.READ)
    def Flags(self) -> "as":  # noqa: N802,F722
        return ["notify"]

    @dbus_property(access=PropertyAccess.READ)
    def Value(self) -> "ay":  # noqa: N802
        return self._value

    @dbus_property(access=PropertyAccess.READ)
    def Notifying(self) -> "b":  # noqa: N802
        return self.notifying

    @method()
    def StartNotify(self):  # noqa: N802
        self.notifying = True
        log.info("central subscribed to OUTBOX")

    @method()
    def StopNotify(self):  # noqa: N802
        self.notifying = False
        log.info("central unsubscribed from OUTBOX")

    def notify(self, frame: bytes):
        self._value = frame
        self.emit_properties_changed({"Value": frame})


class Peripheral:
    def __init__(self, peer, adapter: str = "hci0"):
        self.peer = peer
        self.adapter_path = f"/org/bluez/{adapter}"
        self.bus = None
        self.outbox = Outbox()
        self.active_device = None
        self.handle = None
        self._send_task = None
        self._wake = asyncio.Event()
        self._link_n = 0

    async def start(self):
        self.bus = await MessageBus(bus_type=BusType.SYSTEM, negotiate_unix_fd=True).connect()
        self.bus.export(BASE + "/service0", Service())
        self.bus.export(BASE + "/service0/char0", Inbox(self))
        self.bus.export(BASE + "/service0/char1", self.outbox)
        self.bus.export(BASE + "/adv0", Advertisement())
        await self._call("org.bluez.GattManager1", "RegisterApplication", "oa{sv}", [BASE, {}])
        await self._call("org.bluez.LEAdvertisingManager1", "RegisterAdvertisement", "oa{sv}", [BASE + "/adv0", {}])
        await self._call("org.freedesktop.DBus", "AddMatch", "s",
                         ["type='signal',sender='org.bluez',interface='org.freedesktop.DBus.Properties',"
                          "member='PropertiesChanged',arg0='org.bluez.Device1'"], dest="org.freedesktop.DBus",
                         path="/org/freedesktop/DBus")
        self.bus.add_message_handler(self._on_signal)
        self._send_task = asyncio.create_task(self._sender())
        log.info("GATT service %s registered and advertising on %s (no local name)", C.SERVICE_UUID, self.adapter_path)

    async def stop(self):
        try:
            await self._call("org.bluez.LEAdvertisingManager1", "UnregisterAdvertisement", "o", [BASE + "/adv0"])
            await self._call("org.bluez.GattManager1", "UnregisterApplication", "o", [BASE])
        except Exception as e:  # BlueZ may already have dropped them
            log.debug("unregister: %s", e)
        if self._send_task:
            self._send_task.cancel()
        self.bus.disconnect()

    async def _call(self, iface, member, sig, body, dest="org.bluez", path=None):
        reply = await self.bus.call(Message(destination=dest, path=path or self.adapter_path, interface=iface,
                                            member=member, signature=sig, body=body))
        if reply.message_type == MessageType.ERROR:
            raise RuntimeError(f"{iface}.{member}: {reply.error_name} {reply.body}")
        return reply

    # called synchronously from the D-Bus WriteValue handler
    def on_write(self, device: str, mtu: int, frame: bytes) -> bool:
        if self.active_device is not None and device != self.active_device:
            return False
        if self.active_device is None:
            self._link_n += 1
            self.active_device = device
            self.handle = f"P{self._link_n}:{device.rsplit('/', 1)[-1]}"
            self.peer.new_link(self.handle, PERIPHERAL, mtu)
        link = self.peer.links.get(self.handle)
        if link is not None and C.MIN_FRAME_LEN <= mtu - 3 and link.frame_len != min(mtu - 3, C.MAX_FRAME_LEN):
            link.set_att_mtu(mtu)
        self.peer.feed(self.handle, frame)
        self._wake.set()
        return True

    def wake(self):
        self._wake.set()

    async def _sender(self):
        while True:
            await self._wake.wait()
            self._wake.clear()
            if self.handle is None:
                continue
            frames = self.peer.drain(self.handle)
            for f in frames:
                if not self.outbox.notifying:
                    log.warning("[%s] central not subscribed; dropping %d frames", self.handle, len(frames))
                    break
                self.outbox.notify(f)
                await asyncio.sleep(NOTIFY_GAP_S)
            link = self.peer.links.get(self.handle)
            if link is not None and link.closed:
                await asyncio.sleep(0.3)  # let the bye reach the central, then disconnect it
                await self._disconnect(self.active_device)

    async def _disconnect(self, device):
        handle = self.handle
        self.peer.drop_link(handle)
        self.active_device = None
        self.handle = None
        if device and device != "?":
            try:
                await self._call("org.bluez.Device1", "Disconnect", "", [], path=device)
                log.info("[%s] disconnected central", handle)
            except Exception as e:
                log.debug("disconnect %s: %s", device, e)

    def _on_signal(self, msg: Message):
        if msg.message_type != MessageType.SIGNAL or msg.member != "PropertiesChanged" or not msg.body:
            return
        if msg.body[0] != "org.bluez.Device1" or msg.path != self.active_device:
            return
        changed = msg.body[1]
        if "Connected" in changed and not changed["Connected"].value:
            log.info("[%s] central disconnected", self.handle)
            self.peer.drop_link(self.handle)
            self.active_device = None
            self.handle = None
            self.outbox.notifying = False
