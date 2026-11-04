# p2p-sim — Conjunction Bluetooth test peer (and protocol reference)

A Python implementation of `docs/P2P_PROTOCOL.md` that runs on this laptop's Bluetooth adapter and
behaves like a nearby Conjunction user ("Aurora", "Orion", "Sol"…). It is also the **reference
implementation**: `testdata/p2p_wire.json` is generated from it, and the Kotlin (`:core` `p2p`)
and Swift implementations are tested against those vectors.

```
conjunction_p2p/
  consts.py        UUIDs, limits, timeouts (= Kotlin P2pConstants)
  canonjson.py     canonical JSON
  frames.py        frame codec + reassembler
  messages.py      message schemas, encode/validate
  crypto.py        P-256 ECDH, HKDF-SHA256, AES-256-GCM, friend tags, safety code
  compat.py        Python port of Compatibility.score + MatchFilter.kind + Age (checked against golden_compat.json)
  link.py          transport-free per-link state machine (= Kotlin P2pLink)
  peer.py          persona behaviour: auto-swipe, canned replies, friends/outbox storage, frame log
  ble_peripheral.py  BlueZ GATT server + advertisement over D-Bus (dbus-fast)
  ble_central.py     bleak scanner/client with the proximity gate
personas/          aurora.json (+ aurora.jpg), orion.json, sol.json
tests/             unittest suite (no radio needed)
sim.py             command line
```

## Setup (once)

Python 3.12+ (tested with 3.14.4), BlueZ 5.62+ (tested with 5.85), adapter `hci0` powered on.
The venv lives outside the repo:

```bash
python3 -m venv ~/.venvs/conjunction-p2p
~/.venvs/conjunction-p2p/bin/pip install -r tools/p2p-sim/requirements.txt
```

No root, no `sudo`, no changes to BlueZ configuration: the GATT application and advertisement
are registered over D-Bus by this process and disappear when it exits. The adapter's name/alias
is not touched.

## Commands

Run from the repo root; `PY=~/.venvs/conjunction-p2p/bin/python`.

```bash
# protocol self-tests (no radio): frames, crypto, state machine, compat engine vs golden files
(cd tools/p2p-sim && $PY -m unittest discover -s tests -t . -v)

# two personas talking in memory (no radio): match -> swipes -> keyx -> encrypted chat
$PY tools/p2p-sim/sim.py loopback aurora orion

# the laptop as a nearby user that phones connect to (advertise + GATT server)
$PY tools/p2p-sim/sim.py peripheral aurora -v

# the laptop scanning for phones and connecting to them
$PY tools/p2p-sim/sim.py central aurora -v

# both at once, like a phone in Nearby mode
$PY tools/p2p-sim/sim.py both aurora -v --log /tmp/aurora.log

# friends and store-and-forward
$PY tools/p2p-sim/sim.py friends aurora
$PY tools/p2p-sim/sim.py queue aurora Orion "see you at the observatory"   # delivered next time Orion is near
```

Useful flags: `--swipe right|left|none|persona` (default: the persona's `autoSwipe`),
`--swipe-delay 2`, `--no-greet`, `--rssi -80` (central proximity threshold), `--today 2026-11-03`
(18+ check), `--duration 60`, `--hex` (every frame in full hex), `-v` (every frame header and
advertisement), `--state-dir DIR` (default `tools/p2p-sim/.state/`, git-ignored: identity key,
friends, outbox, chat log, received photos).

What the log shows: each frame (`-> v1 msg=3 1/2 len=182`), each decoded message as canonical
JSON, the match result (kind, total / western / chinese / blood, twins, highlights — computed
with the Python port of the shared engines, so it must equal what the phone shows), received
photos (saved to the state dir), the safety code after a mutual match, chats in and out, acks.

## Personas

`personas/<name>.json`: `firstName`, `bio`, `gender`, `seeking`, `relationship`, `lookingFor`,
`bloodType`, `birth` (date, time, lat/lon, zone, place), `compat` (the wire `hello.compat`
object), `photo` (JPEG next to the file, ≤ 61440 bytes, ≤ 640 px), `autoSwipe`, `replies`.

`compat` is precomputed because the astronomy engines are Kotlin/Swift only. After editing a
persona's birth data, set `"compat": null` and run `cd android && ./gradlew :core:test`:
`PersonaProfilesTest` fails and prints the correct `compat` line to paste in. It also fails if a
stored value ever drifts from the engines.

| persona | intent | matches |
|---|---|---|
| aurora | female, seeking both, single, dating+friends, Pisces sun, has photo, swipes right | almost anyone (dating if the gender rule allows, else friends) |
| orion | male, seeking female, single, dating only, unknown birth time | dating with women seeking men; nothing else |
| sol | male, in a relationship, friends only, swipes left | friends with anyone open to friends |

## Regenerating the golden vectors

```bash
$PY testdata/tools/make_p2p_wire.py      # deterministic; a re-run must give an identical file
cd android && ./gradlew :core:test       # P2pWireVectorsTest checks Kotlin against it
```

## What was verified on this laptop (2026-11-03) and what was not

Verified:
- `tests/` (frames incl. fuzzing, crypto vectors incl. tamper/replay, state machine incl. friend
  recognition, impostor, rate limits, timeouts; Python compat port = `golden_compat.json` for all
  cases and `golden_match_filter.json`).
- `loopback`: full match → photo → swipes → keyx/kc → chat → ack, and on a second run friend
  recognition with new ephemeral ids plus delivery of a queued message.
- `peripheral` / `both` on hci0 without root: BlueZ accepted the GATT application and the
  advertisement (`bluetoothctl show` → `Advertising Features: ActiveInstances: 0x01` while running,
  `0x00` after exit); the bleak scanner starts and stops cleanly.

Not verifiable here, and why:
- **Over-the-air advertisement content and a real GATT round trip.** A BlueZ adapter cannot
  connect to its own GATT server, there is only one adapter (hci0), and `btmon` needs root
  (`Failed to bind channel: Operation not permitted`). The phone app had no Bluetooth transport
  yet when this was written. To check:
  - `sudo btmon` in one terminal while `sim.py peripheral aurora` runs; look for an
    `LE Advertising Report`/`LE Extended Advertising Report` from the adapter with
    `128-bit Service UUIDs (complete): 7c0a1e5f-6a3b-4e2d-9f11-c0471c710001` and **no Name**; or
  - a BLE scanner app on any phone (e.g. nRF Connect): the laptop shows up with only that UUID,
    connect → service with INBOX (Write) and OUTBOX (Notify); or
  - a USB Bluetooth dongle as `hci1`: `sim.py peripheral aurora --adapter hci0` in one terminal and
    `sim.py central orion --adapter hci1` in another gives a real radio round trip.
  - with the Conjunction app's Nearby on, `sim.py both aurora -v` and watch the log.

## Limitations of the test peer (not of the protocol)

- As peripheral it serves **one central at a time**: BlueZ delivers a characteristic's
  notifications to every subscribed client, so frames meant for one phone would reach another.
  Writes from a second central are refused (`org.bluez.Error.NotPermitted`) until the first link
  ends. As central it opens up to 4 links.
- `bless` (named in SPEC 5c) is not used: bless 0.3.0 renames the adapter (a persistent alias
  change) when it advertises, always adds a local name to the advertisement, and drops BlueZ's
  `WriteValue` options (needed for the per-link MTU and to tell centrals apart).
  `ble_peripheral.py` uses BlueZ's D-Bus API directly through `dbus-fast` (bleak's own D-Bus
  library) instead.
- BlueZ picks the ATT MTU (it requests 517 itself); the central reads it from the
  characteristic's `MTU` (BlueZ ≥ 5.62) and falls back to 23.
- Two copies of the sim talking to each other reply to each other's canned lines; replies stop
  after 20 per link.
- A phone re-advertises under a new random address after every connection, and BlueZ cannot tell
  that it is the same phone (no bonding, so no IRK); connecting to it would time out after 20 s.
  So by default the central does not connect to an address first seen while a link with a phone
  is up. With several phones, start them before the sim or pass `--multi-phone`.
- When both roles are linked with the same phone (the pair's two links share one LE connection),
  ending the duplicate link leaves the connection up: BlueZ's `Device1.Disconnect` (bleak's
  `disconnect()`) would drop the kept link too. Frames that still arrive for the ended link are
  ignored until the phone starts a new link with `hello` (protocol section 3, step 7).
