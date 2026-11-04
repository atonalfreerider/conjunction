# Conjunction peer-to-peer protocol, version 1

Status: **normative**. This is the single source of truth for how Conjunction phones (Android
Kotlin, iOS Swift) and the laptop test peer (Python, `tools/p2p-sim/`) talk to each other over
Bluetooth Low Energy (SPEC section 5c). Golden vectors: `testdata/p2p_wire.json`, generated from the
Python reference by `testdata/tools/make_p2p_wire.py`. Implementations:

| platform | code | tests |
|---|---|---|
| Python (reference) | `tools/p2p-sim/conjunction_p2p/` | `tools/p2p-sim/tests/` |
| Kotlin (`:core`) | `android/core/src/main/kotlin/com/metavirtuoso/conjunction/core/p2p/` | `android/core/src/test/kotlin/.../core/p2p/` (reads `p2p_wire.json`) |
| Swift | to be written against this document and `p2p_wire.json` | |

Key words MUST, MUST NOT, SHOULD, MAY are used as in RFC 2119. "Byte" = octet. All multi-byte
integers in binary structures are **big-endian** (network order). Hex in this document is lowercase.

Contents: 1 Overview · 2 Bluetooth layer · 3 Discovery and connection · 4 Framing · 5 Messages ·
6 Link state machine · 7 Ephemeral ids, de-duplication, friend recognition · 8 Cryptography ·
9 Matching · 10 Limits, timeouts, rate limits, errors · 11 Privacy · 12 Platform notes ·
13 Test vectors · 14 Changing the protocol

---

## 1. Overview

There is no server. Every phone with Nearby on is at the same time a **GATT peripheral**
(advertises one service, hosts two characteristics) and a **GATT central** (scans for that service
and connects). One connection between two phones is a **link**. On a link the two sides exchange
**messages**; each message is a small JSON document, optionally followed by binary bytes (only
the profile photo uses that), and is cut into **frames** that fit one ATT write or notification.

```mermaid
sequenceDiagram
    autonumber
    participant A as Phone A (central)
    participant B as Phone B (peripheral)
    B-->>A: advertisement (service UUID only)
    Note over A: RSSI >= -80 dBm in >= 2 scans within 30 s
    A->>B: connect, ATT MTU exchange (request 517)
    A->>B: discover service, enable OUTBOX notifications (CCCD = 0x0001)
    A->>B: hello {eid, match, compat, ft}   (INBOX writes)
    B-->>A: hello {eid, match, compat, ft}  (OUTBOX notifications)
    Note over A,B: both run MatchFilter.kind + Compatibility.score + twins<br/>(same inputs, same engines => same answer)
    alt no qualifying kind
        A->>B: bye {no_match}
        B-->>A: bye {no_match}
        Note over A,B: nothing shown, nothing kept; central disconnects
    else match
        B-->>A: card {kind, firstName, bio, photo meta}
        B-->>A: photo (JPEG bytes) when ATT_MTU >= 185
        A->>B: card + photo
        Note over A,B: match animation (foreground) or local notification (background)
        A->>B: swipe {right}
        B-->>A: swipe {right}
        Note over A,B: mutual right => keyx
        A->>B: keyx {ik, ek}
        B-->>A: keyx {ik, ek}
        Note over A,B: ECDH(ik,ik) + ECDH(ek,ek) -> HKDF -> session keys
        B-->>A: kc {mac}
        A->>B: kc {mac}
        Note over A,B: friends; friend record stored locally
        A->>B: chat {ctr, ct}  (AES-256-GCM)
        B-->>A: ack {id}
    end
    Note over A,B: Later, new ephemeral ids: hello.ft friend tag recognised<br/>-> keyx/kc immediately -> queued chat delivered
```

## 2. Bluetooth layer

### 2.1 UUIDs

| what | UUID |
|---|---|
| Service | `7C0A1E5F-6A3B-4E2D-9F11-C0471C710001` |
| INBOX characteristic | `7C0A1E5F-6A3B-4E2D-9F11-C0471C710002` |
| OUTBOX characteristic | `7C0A1E5F-6A3B-4E2D-9F11-C0471C710003` |
| Client Characteristic Configuration descriptor (on OUTBOX) | `00002902-0000-1000-8000-00805F9B34FB` (standard 0x2902) |

UUIDs compare case-insensitively; code SHOULD write them uppercase on iOS and lowercase in Python.

### 2.2 GATT layout

One primary service with exactly these two characteristics:

| characteristic | properties | permissions | value | direction |
|---|---|---|---|---|
| INBOX `…0002` | **Write** (Write Request, 0x08) only. Not Write Without Response, not Read. | writeable, **no encryption / no authentication** (no pairing, ever) | none stored; each write is one frame | central → peripheral |
| OUTBOX `…0003` | **Notify** (0x10) only. Not Read, not Indicate. | none (value not readable); CCCD readable + writeable without encryption | none stored; each notification is one frame | peripheral → central |

- Android peripheral: `BluetoothGattCharacteristic(INBOX, PROPERTY_WRITE, PERMISSION_WRITE)`,
  `BluetoothGattCharacteristic(OUTBOX, PROPERTY_NOTIFY, 0)` plus a `BluetoothGattDescriptor(0x2902,
  PERMISSION_READ or PERMISSION_WRITE)`; answer every INBOX write with `sendResponse(GATT_SUCCESS)`
  (or `GATT_INVALID_ATTRIBUTE_LENGTH` for a write longer than 512 or a prepared/long write).
- iOS peripheral: `CBMutableCharacteristic(type: INBOX, properties: [.write], value: nil,
  permissions: [.writeable])` and `CBMutableCharacteristic(type: OUTBOX, properties: [.notify],
  value: nil, permissions: [])` (CoreBluetooth adds the CCCD itself).
- BlueZ peripheral: flags `["write"]` and `["notify"]`.

A peripheral MUST answer each INBOX Write Request *after* it has passed the frame to its link
state machine (so ATT flow control also throttles the sender), and MUST answer success even when
the frame is rejected by the protocol (protocol errors are reported with an `error` message, not
an ATT error). It MUST reject prepared (long/queued) writes and writes with a non-zero offset with
ATT error `0x0D Invalid Attribute Value Length` — frames always fit one ATT PDU (section 4.1).

### 2.3 Advertising

- Advertising data: Flags + the **128-bit service UUID** (complete list). Nothing else: **no
  local name**, no manufacturer data, no service data, no TX power. Scan response: empty.
- Connectable, undirected; the platform's own (random, rotating) address. Never a public address
  when the platform lets us choose.
- Android: `AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(ADVERTISE_MODE_BALANCED)`,
  `AdvertiseData.Builder().addServiceUuid(SERVICE).setIncludeDeviceName(false).setIncludeTxPowerLevel(false)`.
- iOS: `startAdvertising([CBAdvertisementDataServiceUUIDsKey: [SERVICE]])` (no
  `CBAdvertisementDataLocalNameKey`). In the background iOS moves the UUID to the "overflow area",
  which only other iPhones can see (section 12.2).

### 2.4 ATT MTU and frame size

- A central MUST request ATT_MTU **517** right after connecting, before anything else
  (Android `requestMtu(517)`; on Android 14+ the stack negotiates 517 on the first request anyway;
  iOS and BlueZ exchange the MTU automatically).
- The **maximum frame length** in both directions is
  `frameLen = min(ATT_MTU − 3, 512)`, never less than 20.
  (`ATT_MTU − 3` is the largest Write Request / Handle Value Notification value; 512 is the ATT
  maximum attribute length.) Table: ATT_MTU 23 → 20, 185 → 182, 247 → 244, 517 → 512.
- How each side learns ATT_MTU:

  | role | Android | iOS | BlueZ (Python) |
  |---|---|---|---|
  | central | `onMtuChanged(mtu)` (23 if never called) | `peripheral.maximumWriteValueLength(for: .withoutResponse) + 3` (do **not** use `.withResponse`, which reports 512 because iOS would then do long writes) | `BleakClient.mtu_size` after `_acquire_mtu()`, else `max_write_without_response_size + 3` |
  | peripheral | `BluetoothGattServerCallback.onMtuChanged(device, mtu)` per device (23 until called) | `central.maximumUpdateValueLength + 3` | the `mtu` option of `WriteValue` |

- A side MUST NOT send a frame longer than its current `frameLen`. If the MTU changes during a
  link (rare), new frames use the new value; frames already built may be sent as they are only if
  they still fit.

### 2.5 Transport behaviour

- **Order.** Frames are delivered in order per direction (ATT is reliable and ordered on one
  connection). The protocol relies on this; it never retransmits.
- **Central → peripheral**: one INBOX Write Request per frame, waiting for each write's response
  before the next (Android: one outstanding GATT operation; iOS `didWriteValueFor`; bleak
  `write_gatt_char(..., response=True)`).
- **Peripheral → central**: one OUTBOX notification per frame, respecting back-pressure (Android:
  wait for `onNotificationSent` before the next `notifyCharacteristicChanged`; iOS: when
  `updateValue(_:for:onSubscribedCentrals:)` returns false, wait for
  `peripheralManagerIsReady(toUpdateSubscribers:)`; iOS: always pass the one subscribed central of
  this link in `onSubscribedCentrals`).
- **Disconnect** ends the link; all link state (partial messages, session keys, counters) is
  discarded. There is no resumption.

## 3. Discovery and connection

1. **Scan** for the service UUID only (Android `ScanFilter.setServiceUuid`, required for
   background scanning; iOS `scanForPeripherals(withServices: [SERVICE])`).
2. **Proximity gate**: connect only to an advertiser seen with RSSI ≥ **−80 dBm** (tunable
   setting) in **≥ 2** scan results within **30 s**. The UI says "nearby", never a distance.
3. **Connect** as central when: no link to that advertiser address is open or being opened; fewer
   than **4** links are open in total; and the advertiser address has not completed a link in the
   last **120 s** (prevents reconnect loops; addresses rotate anyway).
4. Central setup order (MUST): connect → request MTU 517 → discover the service → enable
   notifications on OUTBOX (write CCCD `0x0001`; Android `setCharacteristicNotification` + descriptor
   write; iOS `setNotifyValue(true)`; bleak `start_notify`) → **send `hello`**. A central MUST NOT
   write INBOX before notifications are enabled.
5. The peripheral sends nothing until it has received the central's `hello`; it then answers with
   its own `hello` (section 6). This means the peripheral always knows the MTU from the write
   before it sends anything.
6. Accept every incoming connection as peripheral (subject to the 4-link cap; over the cap, a
   peripheral simply does not answer: Android `cancelConnection`, iOS cannot refuse and lets the
   hello timeout fire).
7. **Link start.** A peripheral opens a link on the first INBOX frame with `msgId` 0 and `index` 0
   (the central's `hello`). An INBOX frame that arrives while the connection has no open link and
   is not such a frame is left over from a link that already ended on a connection that stayed up
   (section 7.2): discard it silently — no link, no `error`, no disconnect.

Both phones are centrals and peripherals, so A may connect to B while B connects to A. Section 7.2
says which of the two links survives.

## 4. Framing

### 4.1 Frame layout

Every INBOX write and every OUTBOX notification is exactly one frame:

| offset | size | field | value |
|---|---|---|---|
| 0 | 1 | `ver` | frame format version, **0x01** |
| 1 | 2 | `msgId` | uint16 BE, message id (per link, per direction) |
| 3 | 2 | `index` | uint16 BE, this frame's index, `0 ≤ index < count` |
| 5 | 2 | `count` | uint16 BE, number of frames in the message, `≥ 1` |
| 7 | n | `payload` | `1 ≤ n ≤ frameLen − 7` bytes of the message body |

Header length is 7 bytes, so the maximum payload per frame is `min(ATT_MTU − 3, 512) − 7`:
13 bytes at ATT_MTU 23, 175 at 185, 505 at 517.

**Sender rules.** The body is split into `count = ceil(len(body) / (frameLen − 7))` chunks, all full
size except possibly the last, sent with `index` 0, 1, …, count−1 and the same `msgId` and `count`.
`msgId` starts at **0** for the first message a side sends on a link and increases by 1 per
message (mod 65536). A sender SHOULD send one message's frames back-to-back; it MAY interleave
frames of at most 4 messages (e.g. a chat while a photo is in flight).

### 4.2 Message body

```
body = jsonLen (uint16 BE) || json (jsonLen bytes, UTF-8, canonical JSON, section 5.1) || blob
```

`2 ≤ jsonLen ≤ 16384`. `blob` is empty for every message type except `photo`, where it is the
JPEG file (1..61440 bytes). Maximum body length = 2 + 16384 + 61440 = 77826 bytes.

Example (from `p2p_wire.json`, `swipe_right`): JSON `{"t":"swipe","dir":"right"}` (27 bytes) →
body `001b7b2274223a227377697065222c22646972223a227269676874227d` → at ATT_MTU 517 with msgId
0x0102 one frame `01 0102 0000 0001` + body.

### 4.3 Reassembly (receiver)

Per link and direction, keep a map `msgId → partial {count, nextIndex, bytes, lastFrameTime}`.
For each received frame:

1. Length < 8 (header + 1 byte) or > 512 → `error malformed`.
2. `ver ≠ 0x01` → `error frame_version` (a future frame format; close).
3. `count = 0` or `index ≥ count` → drop any partial for `msgId`; `error malformed`.
4. `index = 0`: drop any unfinished partial with this `msgId` (silently), then if `count = 1` the
   payload is a complete body; otherwise start a partial. If 4 partials are already open →
   `error rate`.
5. `index > 0`: there MUST be a partial for `msgId` with `nextIndex = index` and the same `count`;
   otherwise drop it and `error malformed`. Append the payload.
6. If the accumulated length exceeds 77826 → drop; `error too_large`.
7. When `nextIndex = count` the body is complete: remove the partial and decode it (section 5).
8. A partial with no new frame for **15 s** is dropped silently.

In this version every `error` is terminal (section 10.4), so a malformed frame ends the link.

## 5. Messages

### 5.1 Canonical JSON

Every message is a JSON object, encoded **canonically** so all implementations produce identical
bytes (needed for golden vectors and for the profile fingerprint, section 7.4):

- UTF-8, no BOM, **no whitespace** between tokens.
- Object members in **exactly the order listed** in the schemas below. Optional ("nullable")
  members are always present, with `null` when absent. Arrays keep their order.
- Values: strings, integers, `true`/`false`, `null`, objects, arrays. **No floating-point numbers**
  anywhere (longitudes travel as integer centidegrees). Integers in `[−2^63, 2^63)`, written in
  shortest decimal form (no `+`, no leading zeros, no exponent, no `-0`).
- Strings: escape `"` as `\"`, `\` as `\\`, U+0008 `\b`, U+000C `\f`, U+000A `\n`, U+000D `\r`,
  U+0009 `\t`, any other U+0000–U+001F as `\u00xx` with **lowercase** hex. Everything else,
  including `/`, non-ASCII and U+2028/U+2029, is written as raw UTF-8. Strings MUST NOT contain
  unpaired surrogates.
- This is exactly Python `json.dumps(obj, ensure_ascii=False, separators=(",", ":"))`.
  Swift: do not use `JSONEncoder`/`JSONSerialization` for writing (key order is not preserved
  and `/` is escaped by default) — write a small encoder. Kotlin: `core.p2p.WireJson`.

**Decoders** MUST accept any valid JSON (any member order, whitespace), MUST reject invalid
UTF-8, duplicate member names, floating-point numbers (a number token containing `.`, `e` or `E`)
and values of the wrong type or out of range (→ `error malformed`), and MUST ignore unknown
members. A message whose `t` is unknown MUST be ignored (forward compatibility); it still counts
toward the rate limits.

**Base64** below means RFC 4648 §4 standard alphabet **with** `=` padding. Decoders MUST reject
non-canonical base64 (wrong padding, non-zero padding bits, whitespace).

**Hex** means lowercase hex digits only.

### 5.2 Common types

| name | JSON | rule |
|---|---|---|
| `eid` | string | 32 hex chars (16 random bytes) |
| `centideg` | integer | 0..35999 = round(longitude° × 100) mod 36000 (`Compatibility.centidegrees`) |
| `date` | string | `YYYY-MM-DD`, a valid proleptic Gregorian date |
| `point` | string | base64 of a 65-byte X9.63 uncompressed P-256 point `04 || X || Y` |
| `msgid` | string | 32 hex chars (16 random bytes), chat message id |
| `sha256` | string | 64 hex chars |

### 5.3 Schemas (wire order)

**`hello`** — the first message on a link from each side. Only what matching needs.

```json
{"t":"hello","pv":1,"pmin":1,"eid":"0f1e2d3c4b5a69788796a5b4c3d2e1f0",
 "match":{"gender":"female","seeking":"male","relationship":"single","lookingFor":"both"},
 "compat":{"sun":35,"moon":21075,"venus":33012,"mars":9120,"asc":1550,"animal":"rat","stem":"geng",
           "blood":"o","birthDate":"1996-03-20","birthHour":6},
 "ft":["AAAAAAAAAAA=", "...8 to 64 tags..."]}
```
(shown wrapped; on the wire it is one line without spaces)

| field | type | rule |
|---|---|---|
| `t` | `"hello"` | |
| `pv` | int 1..255 | highest protocol version the sender speaks (this document: 1) |
| `pmin` | int 1..`pv` | lowest version it accepts |
| `eid` | eid | the sender's current ephemeral id (section 7.1) |
| `match.gender` | `"male"`/`"female"` | `MatchFilter` `Gender.wire` |
| `match.seeking` | `"male"`/`"female"`/`"both"` | `Seeking.wire` |
| `match.relationship` | `"single"`/`"in_a_relationship"` | `RelationshipStatus.wire` |
| `match.lookingFor` | `"dating"`/`"friends"`/`"both"` | `LookingFor.wire` |
| `compat.sun` `.moon` `.venus` `.mars` | centideg | `CompatProfile` longitudes |
| `compat.asc` | centideg or null | null when birth time unknown |
| `compat.animal` | string | `ChineseAnimal.key`: rat ox tiger rabbit dragon snake horse goat monkey rooster dog pig |
| `compat.stem` | string | day-master `HeavenlyStem`, lowercase enum name: jia yi bing ding wu ji geng xin ren gui |
| `compat.blood` | `"a"`/`"b"`/`"ab"`/`"o"` or null | `BloodType.key`; Rh is never sent |
| `compat.birthDate` | date | birth date (twins check, age) |
| `compat.birthHour` | int 0..23 or null | local clock hour of birth (exact-twins check); null when unknown. Minutes are never sent. |
| `ft` | array of 0..64 base64 strings, each 8 bytes | friend tags + decoys (section 7.3) |

**`card`** — sent only by a side that computed a qualifying match (section 9), right after its
`hello` evaluation. These are the match-card fields not derivable from `hello`.

```json
{"t":"card","kind":"dating","firstName":"Aurora","bio":"Stargazer. Tea, long walks, Saturn returns.",
 "photo":{"sha256":"…64 hex…","size":1024,"w":480,"h":640}}
```

| field | type | rule |
|---|---|---|
| `kind` | `"dating"`/`"friends"` | the sender's `MatchFilter.kind` result; receiver MUST compare with its own and, if different, close with `bye no_match` |
| `firstName` | string | 1..64 UTF-8 bytes (sender truncates at a code-point boundary) |
| `bio` | string or null | first line of the bio, 0..280 UTF-8 bytes |
| `photo` | object or null | null when there is no (approved) photo **or** `frameLen < 182` (ATT_MTU < 185: too slow) |
| `photo.sha256` | sha256 | SHA-256 of the JPEG bytes |
| `photo.size` | int 1..61440 | JPEG length in bytes |
| `photo.w`, `photo.h` | int 1..640 | pixel size (longest side ≤ 640) |

Age, sun/moon/rising signs and the Chinese animal shown on the card are derived by the receiver
from `hello.compat` (signs = `centideg / 3000` → `ZodiacSign.entries[...]`; age =
`Age.yearsOn(birthDate, today)`); relationship status and looking-for come from `hello.match`.

**`photo`** — immediately after `card` when `card.photo` is not null. The blob is the JPEG.

```json
{"t":"photo","sha256":"…64 hex…","mime":"image/jpeg"}
```
Receiver MUST check `sha256` = `card.photo.sha256` = SHA-256(blob) and `len(blob)` = `card.photo.size`
(else `error malformed`), and MUST screen the image on device before showing it (SPEC 5c).
A `photo` without a prior `card` with photo metadata, or a second `photo`, is `error state`.

**`swipe`** — once per side, in state MATCHED.

```json
{"t":"swipe","dir":"right"}
```
`dir` ∈ `"right"` (accept), `"left"` (reject). A side that sends `left` follows it with
`bye done` and closes. The receiver of `left` shows nothing ("left is silent"; the card simply
expires).

**`keyx`** — identity + link-ephemeral public keys (section 8.3). Sent after mutual right swipes, or
immediately after `hello` evaluation when the peer was recognised as a friend.

```json
{"t":"keyx","ik":"BHYp…(88 chars)…","ek":"BBt…(88 chars)…"}
```
`ik`, `ek`: point. Receiver MUST validate both are on P-256 and that `ik` ≠ its own `ik`,
`ek` ≠ its own `ek` (→ `error crypto`).

**`kc`** — key confirmation, after receiving the peer's `keyx`.

```json
{"t":"kc","mac":"…24 chars (16 bytes)…"}
```

**`chat`** — encrypted envelope, only in state SECURE.

```json
{"t":"chat","ctr":0,"ct":"…base64 of ciphertext || 16-byte GCM tag…"}
```
`ctr`: int 0..2^32−1, the sender's per-link message counter (also the nonce, section 8.4).
`ct`: 17..8256 bytes decoded. The plaintext is canonical JSON, one of:

```json
{"id":"…msgid…","ts":1762171200000,"kind":"text","text":"Hi Orion ☌"}
{"id":"…msgid…","ts":1762171260000,"kind":"read","refs":["…msgid…"]}
```
`id`: msgid chosen once when the user composes the message (stays the same when a queued message
is re-sent on a later link; the receiver de-duplicates by `id`). `ts`: int, sender's clock, ms since
the Unix epoch, 0..2^53. `text`: 1..4000 UTF-8 bytes. `refs`: 1..64 msgids the sender has displayed
(read receipts). Unknown `kind` values MUST be ignored after decryption.

**`ack`** — delivery receipt, sent by the receiver of a `chat` of kind `text` after it decrypted
and stored it.

```json
{"t":"ack","id":"…msgid…"}
```

**`bye`** — orderly close. After sending or receiving `bye` the link is CLOSED; the central
disconnects once the frame is written.

```json
{"t":"bye","reason":"no_match"}
```
`reason` (1..32 bytes; receivers accept unknown values): `no_match`, `done` (finished, declined,
blocked — deliberately indistinguishable), `dedup` (section 7.2), `timeout`, `shutdown`
(Nearby turned off / app stopping).

**`error`** — protocol violation; terminal.

```json
{"t":"error","code":"rate","detail":"too many frames"}
```
`code` (1..32 bytes): `version`, `malformed`, `frame_version`, `state`, `rate`, `too_large`,
`crypto`, `internal`. `detail`: string 0..200 bytes or null, for logs only (never shown to users,
never containing user data).

## 6. Link state machine

Each link is driven by a transport-free state machine (`P2pLink` in Kotlin, `Link` in Python):
the BLE layer gives it received frames and the ATT_MTU and sends the frames it produces, in order;
the app gives it user actions (swipe, chat, close) and receives events.

States: `AWAIT_HELLO` → (`MATCHED` | `KEYX`) → `KEYX` → `SECURE`; any → `CLOSED`.

| state | on receive | action |
|---|---|---|
| AWAIT_HELLO | `hello` (first) | Peripheral: send own `hello` first. Then: same `eid` as ours → `error state`. Version: `v = min(peer.pv, our.pv)`; if `v < max(peer.pmin, our.pmin)` → `error version`. Compute profile fingerprint (7.4). **Friend recognition** (7.3): if a friend's tag matches → blocked friend: `bye done`; else send `keyx`, go KEYX (friend mode). **Blocked/hidden stranger** (fingerprint in the 30-day hide list or block list) → `bye done`. **Matching** (9): no kind → event *no_match*, `bye no_match`. Else go MATCHED, event *match*, send `card` (+ `photo`); if the app has a stored swipe for this fingerprint (user swiped while out of range), apply it now. |
| AWAIT_HELLO | anything else | `error state` |
| MATCHED | `card` (once) | `kind` ≠ ours → `bye no_match`; else event *card* |
| MATCHED/KEYX/SECURE | `photo` (once, after a `card` with photo) | verify; event *photo* |
| MATCHED | `swipe` (once) | event *peer_swipe*; resolve |
| MATCHED | local swipe (once) | send `swipe`; resolve |
| — | resolve | mine = left → `bye done`, CLOSED. Mine = right and peer = right → send `keyx`, go KEYX (new-friend mode). Peer = left, mine = right → wait (the peer closes). |
| KEYX | `keyx` (after we sent ours) | friend mode: `ik` MUST equal the recognised friend's key, else `error crypto`. Derive session (8.3), send `kc`. |
| KEYX | `kc` | verify (constant-time) else `error crypto`. Go SECURE. Event *friend_new* (ik, friend secret, safety code, card, photo) or *friend_nearby*. App then sends queued chats. |
| SECURE | `chat` | `ctr` MUST equal the next expected counter (starts at 0) else `error crypto`; decrypt (failure → `error crypto`); event *chat*; for `text` send `ack`. |
| SECURE | `ack` | event *delivered* |
| any | `bye` | CLOSED (event *closed by peer*) |
| any | `error` | CLOSED (event *protocol_error* + *closed*) |
| any | unknown `t` | ignore |
| any | a message not allowed in the current state (e.g. `chat` before SECURE, `keyx` before mutual right, `card` without a match) | `error state` |

Who sends first after `hello`: both sides act on their own evaluation; there is no further
turn-taking. Example orders are in `p2p_wire.json` → `transcript.steps`.

Asymmetric outcomes are safe: if only one side recognises the other as a friend (one deleted the
friendship) the other side receives `keyx` in MATCHED/AWAIT state → `error state` → link closes
and nothing is shown. If engines disagree (should not happen: golden parity), `card.kind` differs
→ `bye no_match`.

## 7. Ephemeral ids, de-duplication, friend recognition

### 7.1 Ephemeral id

16 bytes from a CSPRNG (`SecureRandom`, `SecRandomCopyBytes`, `os.urandom`), sent as 32 lowercase
hex chars. A new id is drawn **every 15 minutes** and **every time Nearby is switched on**. A new id
applies to links opened after it was drawn; an open link keeps the id its `hello` carried. The id is
never stored after rotation and never linked to the identity key on the wire.

### 7.2 One link per pair

If two links connect the same two phones (both connected to each other; detected when the peer
`eid` from `hello` equals the peer `eid` of another open link), keep the link **whose central has
the numerically smaller eid** (compare the 16 bytes as unsigned big-endian, equivalently the hex
strings) and close the other with `bye dedup`. Each side evaluates
`keep = (myEid < peerEid) == iAmCentral` for each of the two links and closes the one where it is
false; both sides reach the same decision. The app keys match cards by peer `eid` so a duplicate
link that already raised a *match* event does not show a second card.

Closing the duplicate:

- After deciding, send nothing more on it but the `bye dedup`: frames of earlier messages that
  the transport has queued but not yet written (typically the `card` and `photo` produced
  together with our `hello`) are dropped; a write or notification already in flight completes.
- The two links of a pair usually share **one LE connection** (both phones' stacks reuse the
  existing ACL for the second GATT role). Ending the duplicate MUST NOT take that connection
  down: release only this link's GATT role (Android: `BluetoothGatt.disconnect()`/`close()` of
  this client, never `cancelConnection` of a device we are also linked with; iOS: the central
  disconnects as usual; BlueZ: do not call `Device1.Disconnect` / bleak `disconnect()` while the
  other role still has a link with that device). The peripheral side applies section 3, step 7
  to anything that still arrives.

### 7.3 Friend recognition tags

After a mutual match both sides hold the same **friend secret** `fs` (section 8.5). In every
`hello`, the sender puts, for each of its (non-blocked) friends, up to the 56 most recently seen:

```
tag = HMAC-SHA256(fs, "CJ1-TAG" || eid_bytes)[0..8)        (eid_bytes = the sender's own 16-byte eid)
```

and pads the list with random 8-byte **decoys** to `max(8, 8·ceil(n/8))` entries (at most 64),
then shuffles it uniformly. Every user therefore sends 8, 16, … tags, so the list reveals at most
a coarse friend count, and an eid-specific tag means a stranger cannot link two sightings.

The receiver computes `HMAC-SHA256(fs_F, "CJ1-TAG" || peer_eid_bytes)[0..8)` for each of its
friends F and looks it up in the peer's `ft`. A match only *suggests* the peer is F: the `keyx`
that follows MUST carry F's identity key and the `kc` proves possession of it (a replayed hello is
caught there). Tags of blocked friends are not sent; a recognised blocked friend gets `bye done`.

### 7.4 Profile fingerprint (local only)

`fp = hex(SHA-256("CJ1-FP" || canonical JSON of hello.compat)[0..16))`. Used only on the phone:
the 30-day "hidden after left swipe" list, the block list for strangers, the 24-h Matches inbox
(so a pending swipe can be delivered at the next encounter), and "do not notify the same person
twice within 24 h". It is never sent.

## 8. Cryptography

Primitives (all available on Android JCA API 26+, iOS CryptoKit 17, Python `cryptography`):
P-256 (secp256r1) ECDH, SHA-256, HMAC-SHA256, HKDF-SHA256 (RFC 5869), AES-256-GCM with 96-bit
nonces and 128-bit tags. ECDH output is the 32-byte big-endian X coordinate of the shared point
(JCA `KeyAgreement("ECDH").generateSecret()`, CryptoKit `sharedSecret.withUnsafeBytes`, Python
`exchange(ECDH())`). All labels below are ASCII byte strings without terminator.

### 8.1 Keys

- **Identity key** `ik`: a long-term P-256 key pair per installation, generated on first use.
  Android: Android Keystore with `PURPOSE_AGREE_KEY` on API 31+; on API 26–30 (no Keystore ECDH) a
  software key whose PKCS#8 bytes are encrypted with an AES-GCM Keystore key. iOS: Keychain
  (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), or Secure Enclave
  `SecureEnclave.P256.KeyAgreement.PrivateKey` where available. Deleting the app's data deletes
  the identity and all friendships.
- **Link-ephemeral key** `ek`: a fresh P-256 key pair per link, in memory only, discarded at
  disconnect (forward secrecy for chat on that link).

### 8.2 Roles L and H

Compare the two 65-byte `ik` encodings as unsigned byte strings: the smaller is **L**, the other
**H**. Equal keys → `error crypto`. `ek_L` / `ek_H` are the ephemeral keys *of* L and H.

### 8.3 Session keys

```
ss   = ECDH(ik_self, ik_peer)                         32 bytes
ee   = ECDH(ek_self, ek_peer)                         32 bytes
salt = SHA-256("CJ1-KEYX" || ik_L || ik_H || ek_L || ek_H)
okm  = HKDF-SHA256(IKM = ee || ss, salt, info = "conjunction/v1/session", L = 96)
k_LH = okm[0..32)    AES-256 key for chat sent by L
k_HL = okm[32..64)   AES-256 key for chat sent by H
k_kc = okm[64..96)   key-confirmation key
```

Key confirmation: side X sends `kc.mac = HMAC-SHA256(k_kc, "CJ1-KC" || role)[0..16)` with
role byte `0x4C` ('L') or `0x48` ('H') for X; the receiver verifies the peer's MAC with a
constant-time comparison. This proves the peer holds the private identity key matching `ik`.

### 8.4 Chat encryption

```
nonce = 00 00 00 00 || uint64_be(ctr)       12 bytes
aad   = "CJ1-CHAT"
ct    = AES-256-GCM-Encrypt(key = k_LH if sender is L else k_HL, nonce, plaintext, aad)  ciphertext || 16-byte tag
```

`ctr` starts at 0 for each direction on each link and increases by 1 per `chat`; a side never
sends more than 2^32 chats on one link (close and reconnect first). Keys are per link, so a nonce
never repeats under one key. Receivers require `ctr` = expected (no gaps, no replays).
Android: create a new `Cipher` per message (`AES/GCM/NoPadding`, `GCMParameterSpec(128, nonce)`,
`updateAAD(aad)`).

### 8.5 Friend secret and safety code

```
fs = HKDF-SHA256(IKM = ss, salt = SHA-256("CJ1-FRIEND" || ik_L || ik_H),
                 info = "conjunction/v1/friend-tag", L = 32)
```
Stored with the friend record (peer `ik`, `fs`, card, photo). Recomputable from the identity keys.

Safety code (optional "verify in person" screen, both phones show the same digits):
`h = SHA-256("CJ1-SAFETY" || ik_L || ik_H)`; for i = 0..5, `uint40_be(h[5i..5i+5)) mod 100000`
as 5 zero-padded digits; join with spaces (e.g. `20854 15157 29182 95769 12447 76044`).

### 8.6 Security properties and limits (plain statement)

- Confidentiality and integrity of chat against anyone who did not take part in the original
  `keyx` exchange; forward secrecy per link (ephemeral ECDH).
- **Trust on first use.** The first `keyx` is not authenticated by anything but the BLE link
  itself: an active attacker relaying both phones at the moment of the mutual match could insert
  their own keys. The safety code lets users detect this in person.
- Before the mutual match nothing is encrypted: `hello`, `card`, `photo` and `swipe` are readable
  by anyone who connects or sniffs the radio. They contain only what the SPEC says may be shown.
- Queued (store-and-forward) messages wait in plaintext in the app's private storage and are
  encrypted when the next link to that friend reaches SECURE.

## 9. Matching (the same on both phones)

From our `hello` (`H_me`) and the peer's (`H_peer`), with `today` = the phone's local date:

1. If either `compat.birthDate` is not 18+ on `today` (`Age.isAdult`) → no match.
2. `kind = MatchFilter.kind(profile(H_me.match), profile(H_peer.match))`; null → no match.
3. `score = Compatibility.score(cp(H_me), cp(H_peer))` where `cp` builds `CompatProfile` with
   longitude = `centideg / 100.0` (exact: `Compatibility.centidegrees` maps it back to `centideg`),
   `chineseAnimal`, `dayStem`, `bloodType`, `birthDate`, `birthHour`. `score.isBirthdayTwin`
   / `isExactTwin` drive the twins celebration. Birthday twins still need a kind (step 2).
4. Both sides get the same kind and total because the engines are pure functions with golden
   parity (`testdata/golden_compat.json`, `golden_match_filter.json`). `Compatibility.score(a, b)` and
   `(b, a)` give the same `total`; the highlights list is computed as `score(me, peer)`.

The only possible disagreement is the 18th birthday falling on "today" in one phone's time zone and
not the other's; then one side sends `card` and the other `bye no_match`, and nothing is shown.

Kotlin: `P2pMatching.evaluate(myHello, peerHello, today)`. Python: `compat.evaluate`.

## 10. Limits, timeouts, rate limits, errors

### 10.1 Size limits

| item | limit |
|---|---|
| frame | `min(ATT_MTU − 3, 512)` bytes, ≥ 8 |
| JSON part of a body | 16384 bytes |
| blob (photo) | 61440 bytes; JPEG, longest side ≤ 640 px |
| concurrent partial messages per direction | 4 |
| `ft` tags | 64 (56 real) |
| `firstName` / `bio` / chat `text` | 64 / 280 / 4000 UTF-8 bytes |
| open links per phone | 4 |

### 10.2 Timeouts

| timer | value | on expiry |
|---|---|---|
| partial message idle | 15 s | drop partial silently |
| `hello` not received (from link start; central: from sending its hello) | 10 s | `bye timeout` |
| KEYX state with no frame received | 30 s | `bye timeout` |
| MATCHED state (waiting for swipes) | 10 min | `bye timeout` (the card stays in the inbox for 24 h; a later swipe is applied at the next encounter via the fingerprint) |
| SECURE | none in the protocol | app closes when it wants (`bye done`) or on disconnect |

### 10.3 Inbound rate limits (per link, token buckets)

| what | capacity | refill |
|---|---|---|
| frames | 300 | 150 / s |
| decoded messages | 30 | 5 / s |
| `chat` messages | 10 | 1 / s |

Exceeding any bucket → `error rate` and close. Senders stay well below: a 60 KB photo at MTU 517
is ~155 frames, at MTU 185 ~ 445 frames, paced by ATT flow control (write responses /
notification back-pressure) to well under 150 frames/s on real links.

### 10.4 Errors

On any violation the detecting side sends `error {code, detail}` and goes CLOSED; the receiver of
an `error` also goes CLOSED. The central then disconnects. Implementations MUST NOT show protocol
errors to users other than as "couldn't connect"; they SHOULD log the code. A link that failed
with `error` SHOULD NOT be retried to the same advertiser address for 120 s.

## 11. Privacy

| sent | to whom | when |
|---|---|---|
| service UUID in advertisements | anyone scanning | while Nearby is on |
| `hello`: eid, gender, seeking, relationship status, looking-for, sun/moon/venus/mars (and ascendant if the birth time is known) longitudes to 0.01°, Chinese year animal, day stem, blood type (if given), birth **date**, birth **hour**, friend tags | every phone that connects (any app speaking this protocol) | each link |
| `card`: first name, first line of bio, photo hash/size | only peers for which the sender computed a qualifying match | after `hello` |
| `photo` | same | after `card`, if ATT_MTU ≥ 185 |
| `swipe` | the matched peer | user action |
| identity public key, chat (encrypted) | mutual matches (friends) only | after mutual right |

Never sent: birth place, birth minutes, exact time zone, exact location (the app has no location
permission), device name, phone number, account ids, Rh factor.

**Device name (outside the protocol).** The advertisement carries no name (section 2.3), but every
GATT server also exposes the Generic Access service, and its Device Name characteristic (0x2A00)
holds the phone's Bluetooth name (on Android often the owner's first name, e.g. "John's Pixel" or
"John"). Any central that connects — including any phone running this protocol — can read it, and
BlueZ does so automatically on connect. Apps cannot hide or change it (Android has no API for the
GAP service; renaming the adapter is a system setting the app must not touch). The consent copy
and help should tell people that their phone's Bluetooth name is visible to nearby devices that
connect, and that they can change it in the system Bluetooth settings.

**Honest limitation for the consent copy.** The ephemeral id rotates, but the `hello` profile
itself (four longitudes + birth date + hour) is close to unique, so someone running their own
scanner near you repeatedly could recognise you across rotations by that profile. Matching on
both phones without a server needs these inputs; the app must say so in plain words in the Nearby
consent screen (e.g. "Nearby shares your signs, planet positions, birth date and birth hour with
phones around you so they can compute compatibility. It never shares your birth place, birth
minute, location or name — your first name and photo go only to people you match with.").

Note for SPEC 5c: the SPEC lists the photo hash in `hello`; this protocol moves it into `card`
(sent only to qualifying matches), because a photo hash is a stable identifier. Everything else in
SPEC 5c's `hello` list is carried as specified (age is derived from the birth date).

## 12. Platform notes

### 12.1 Android

- Permissions: `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"`,
  `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`; foreground service type
  `connectedDevice`. No location permission.
- Central: `connectGatt(context, false, cb, TRANSPORT_LE)`; serialise all GATT operations;
  `requestMtu(517)` first; write with `WRITE_TYPE_DEFAULT` (API 33+: `writeCharacteristic(c, value,
  WRITE_TYPE_DEFAULT)`).
- Peripheral: `BluetoothGattServer`; track MTU per device from `onMtuChanged`; notify with
  `notifyCharacteristicChanged(device, char, false, value)` (API 33+) and wait for
  `onNotificationSent`.
- Drive `P2pLink` from one thread (e.g. a single-threaded coroutine dispatcher) per link.

### 12.2 iOS

- `bluetooth-central` and `bluetooth-peripheral` background modes, state restoration
  (`CBCentralManagerOptionRestoreIdentifierKey`, `CBPeripheralManagerOptionRestoreIdentifierKey`),
  `NSBluetoothAlwaysUsageDescription`. No location.
- **Limitation:** a backgrounded iPhone advertises the service UUID only in Apple's overflow area,
  visible to iPhones scanning for that UUID but not to Android. Two phones find each other when at
  least one is Android and the iPhone is in the foreground, or the Android phone is the advertiser
  (iPhones scan for the UUID in the background), or both are iPhones.
- A CoreBluetooth peripheral cannot disconnect a central; after `bye` it just stops processing
  that central's frames. The central always disconnects.

### 12.3 BlueZ (test peer)

See `tools/p2p-sim/README.md`. The test peer serves one central at a time as peripheral (BlueZ
D-Bus notifications go to every subscribed client, so a second central would see the first one's
frames) and uses the `mtu`/`device` options of `WriteValue`. BlueZ's `Device1.Disconnect` (which
bleak's `disconnect()` calls) drops the whole LE connection with that device, so the test peer
leaves the connection up when it ends a link while its other role is still linked with the same
device (section 7.2).

## 13. Test vectors

`testdata/p2p_wire.json` (generated, deterministic):

- `uuids`, `frameLenByMtu`.
- `messages[]`: `json` (canonical text), `bodyHex`, `framesByMtu` for ATT_MTU 23/185/517 with
  msgId 0x0102. Implementations MUST produce byte-identical `json` from the same field values and
  decode every entry back to the same values.
- `invalidBodies[]`, `invalidFrames[]`: inputs that MUST be rejected with the given error code.
- `crypto`: fixed private scalars and public keys, both ECDH secrets, L/H order, session
  `salt`/`okm`/`k_LH`/`k_HL`/`k_kc`/`kc` MACs, AES-GCM vectors (key, ctr, nonce, aad, plaintext,
  ciphertext, envelope frames), a tampered ciphertext that MUST fail, friend secret + tags, safety
  code, profile fingerprints, and RFC 5869 case 1 for the HKDF primitive.
- `match`: kind and score for the two sample hellos on a fixed date.
- `transcript`: a full central (Aurora) / peripheral (Orion) conversation at ATT_MTU 185 with fixed
  keys: hello, card, photo, swipes, keyx, kc, chat, ack, bye, as exact frames. An implementation
  playing either side, fed the other side's frames and the listed actions, MUST emit the same
  messages (byte-identical, except the random `ft` decoys in its own `hello`).

## 14. Changing the protocol

- Additive changes (new optional members, new message types, new `bye` reasons) keep `pv`; old
  receivers ignore them.
- Anything else increments `pv`; implementations SHOULD keep accepting the previous version for one
  release (`pmin`). The frame `ver` byte changes only if the frame layout changes.
- Change this document, the Python reference, the vectors (`make_p2p_wire.py`) and the Kotlin and
  Swift implementations together.
