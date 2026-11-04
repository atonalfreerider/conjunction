#!/usr/bin/env python3
"""Conjunction P2P test peer. See README.md.

  sim.py peripheral aurora        advertise + GATT server (phones connect to the laptop)
  sim.py central aurora           scan + connect to phones
  sim.py both aurora              both at once (what a phone does)
  sim.py loopback aurora orion    two personas in memory, no radio (protocol self-test)
  sim.py queue aurora Orion "hi"  queue a chat for a friend; delivered at the next encounter
  sim.py friends aurora           list stored friends
"""
import argparse
import asyncio
import datetime
import logging
import os
import signal
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from conjunction_p2p.link import CENTRAL, PERIPHERAL  # noqa: E402
from conjunction_p2p.peer import Persona, SimPeer, Store  # noqa: E402


def persona_path(name: str) -> str:
    if os.path.exists(name):
        return name
    p = os.path.join(HERE, "personas", f"{name}.json")
    if not os.path.exists(p):
        raise SystemExit(f"no persona {name!r} (looked for {p})")
    return p


def make_peer(args, name) -> SimPeer:
    persona = Persona.load(persona_path(name))
    store = Store(args.state_dir, persona.key)
    today = datetime.date.fromisoformat(args.today) if args.today else None
    swipe = None if args.swipe == "persona" else (None if args.swipe == "none" else args.swipe)
    peer = SimPeer(persona, store, swipe, args.swipe_delay, not args.no_greet, args.hex, today)
    if args.swipe == "none":
        peer.swipe_dir = "none"
    logging.getLogger("p2p").info("persona %s (%s, %s/%s/%s/%s), identity %s..., %d friend(s), state %s",
                                  persona.first_name, persona.key, *persona.match.values(),
                                  store.identity.public.hex()[:16], len(store.friends), store.dir)
    return peer


async def run_ble(args):
    from conjunction_p2p.ble_central import Central
    from conjunction_p2p.ble_peripheral import Peripheral
    peer = make_peer(args, args.persona)
    periph = Peripheral(peer, args.adapter) if args.mode in ("peripheral", "both") else None
    central = Central(peer, args.rssi, adapter=args.adapter) if args.mode in ("central", "both") else None

    def on_output(handle):
        if handle.startswith("P") and periph:
            periph.wake()
        elif central:
            central.wake(handle)
    peer.on_output = on_output

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for s in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(s, stop.set)
    tasks = []
    if periph:
        await periph.start()

        async def ticker():
            while True:
                await asyncio.sleep(0.5)
                if periph.handle:
                    peer.tick()
                    periph.wake()
        tasks.append(asyncio.create_task(ticker()))
    if central:
        tasks.append(asyncio.create_task(central.run()))
    if args.duration:
        loop.call_later(args.duration, stop.set)
    await stop.wait()
    for t in tasks:
        t.cancel()
    for lk in list(peer.links.values()):
        lk.close("shutdown")
    if periph:
        periph.wake()
        await asyncio.sleep(0.3)
        await periph.stop()
    logging.getLogger("p2p").info("stopped")


async def run_loopback(args):
    a = make_peer(args, args.persona)
    b = make_peer(args, args.other)
    la = a.new_link("A", CENTRAL, args.mtu)
    lb = b.new_link("B", PERIPHERAL, args.mtu)

    async def pump():
        for _ in range(int(args.duration or 15) * 20):
            for f in a.drain("A"):
                b.feed("B", f)
            for f in b.drain("B"):
                a.feed("A", f)
            a.tick(); b.tick()
            if la.closed and lb.closed:
                break
            await asyncio.sleep(0.05)
    la.start()
    await pump()
    log = logging.getLogger("p2p")
    log.info("loopback finished: A=%s B=%s", la.state, lb.state)
    if not args.keep_open and not la.closed:
        la.close("done")
        for f in a.drain("A"):
            b.feed("B", f)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["peripheral", "central", "both", "loopback", "queue", "friends"])
    ap.add_argument("persona", help="persona name in personas/ or a path")
    ap.add_argument("rest", nargs="*", help="loopback: other persona; queue: friend name + text")
    ap.add_argument("--swipe", choices=["persona", "right", "left", "none"], default="persona",
                    help="auto-swipe on a match (default: the persona's autoSwipe)")
    ap.add_argument("--swipe-delay", type=float, default=2.0, help="seconds before auto-swiping")
    ap.add_argument("--no-greet", action="store_true", help="do not send a greeting after a mutual match")
    ap.add_argument("--rssi", type=int, default=-80, help="proximity threshold in dBm (central)")
    ap.add_argument("--adapter", default="hci0")
    ap.add_argument("--mtu", type=int, default=185, help="loopback ATT_MTU")
    ap.add_argument("--today", help="override today's date (YYYY-MM-DD) for the 18+ check")
    ap.add_argument("--duration", type=float, help="stop after N seconds")
    ap.add_argument("--keep-open", action="store_true", help="loopback: do not close at the end")
    ap.add_argument("--state-dir", default=os.path.join(HERE, ".state"), help="identity/friends/chat storage")
    ap.add_argument("--hex", action="store_true", help="log every frame in full hex")
    ap.add_argument("-v", "--verbose", action="store_true", help="log every frame (header) and advertisement")
    ap.add_argument("--log", help="also write the log to this file")
    args = ap.parse_args()

    handlers = [logging.StreamHandler()]
    if args.log:
        handlers.append(logging.FileHandler(args.log))
    logging.basicConfig(level=logging.DEBUG if (args.verbose or args.hex) else logging.INFO,
                        format="%(asctime)s %(levelname).1s %(name)s %(message)s", handlers=handlers)
    for noisy in ("bleak", "dbus_fast", "asyncio"):
        logging.getLogger(noisy).setLevel(logging.WARNING)

    if args.mode == "friends":
        peer = make_peer(args, args.persona)
        for f in peer.store.friends:
            print(f"{f.name:20} ik {f.ik.hex()[:24]}... blocked={f.blocked} queued={len(peer.store.outbox.get(f.ik.hex(), []))}")
        return
    if args.mode == "queue":
        if len(args.rest) < 2:
            raise SystemExit("usage: sim.py queue PERSONA FRIEND_NAME TEXT")
        peer = make_peer(args, args.persona)
        f = next((f for f in peer.store.friends if f.name.lower() == args.rest[0].lower()), None)
        if f is None:
            raise SystemExit(f"no friend named {args.rest[0]!r}")
        peer.store.queue(f.ik, " ".join(args.rest[1:]))
        print("queued")
        return
    if args.mode == "loopback":
        if not args.rest:
            raise SystemExit("usage: sim.py loopback PERSONA_A PERSONA_B")
        args.other = args.rest[0]
        asyncio.run(run_loopback(args))
        return
    asyncio.run(run_ble(args))


if __name__ == "__main__":
    main()
