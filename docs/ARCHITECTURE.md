# Conjunction architecture (serverless build)

Status: describes the working tree on 2026-11-03, after the owner's decision to drop the server
(SPEC 5c). The wire format is normative in `docs/P2P_PROTOCOL.md`; this file explains how the
pieces fit together and where personal data goes. If the two disagree, the protocol document wins
for bytes on the air, and this file must be fixed.

## 1. The big picture

```
            Phone A (Android or iPhone)                          Phone B
 +--------------------------------------------+      +--------------------------------+
 | UI (Compose / SwiftUI)                     |      |               ...              |
 |   onboarding, chart, readings, palm,       |      |                                |
 |   Nearby, match animation, swipe card,     |      |                                |
 |   chat, profile/settings                   |      |                                |
 |--------------------------------------------|      |                                |
 | Engines (pure code, golden-tested parity)  |      |                                |
 |   Kotlin :core  ==  Swift ConjunctionCore  |      |                                |
 |   NatalChart, ChineseZodiac, Compatibility,|      |                                |
 |   MatchFilter, PalmFeatures, prompts,      |      |                                |
 |   fallback readings, P2P protocol (p2p/)   |      |                                |
 |--------------------------------------------|      |                                |
 | On-device AI: Gemini Nano (ML Kit) /       |      |                                |
 |   Apple Foundation Models; fallback text   |      |                                |
 |--------------------------------------------|      |                                |
 | NearbyEngine (one P2pLink per BLE link)    |      |                                |
 | BleTransport: GATT server + advertiser     | BLE  |  BleTransport (same roles)     |
 |               + scanner + GATT client      |<====>|                                |
 | Local store: profile, consents, readings,  |      |                                |
 |   friends, chats, inbox, hide/block list;  |      |                                |
 |   identity key in Keystore / Keychain      |      |                                |
 +--------------------------------------------+      +--------------------------------+

 Nothing else. No Conjunction server, no account, no push, no analytics.
 (backend/ is a record of the abandoned server design and is not deployed.)
```

Only three things ever leave a phone:

1. **Bluetooth LE traffic to other phones** running Conjunction with Nearby on (this document).
2. **What the operating system does on the app's behalf**: birth-place search text goes to the
   platform geocoder (Android `Geocoder`, iOS `CLGeocoder`), ML Kit sends Google diagnostics on
   Android (`legal/PRIVACY.md` §2), and the user's own phone backup, from which the app excludes
   its data (§6).
3. **Emails the user sends** from their own mail app: Report (a person), Report this reading,
   arbitration opt-out. The app only pre-fills them.

## 2. Code map

| Concern | Android | iOS | Shared contract |
|---|---|---|---|
| Wire protocol (framing, JSON, crypto, link state machine, matching) | `android/core/.../core/p2p/` | `ios/Sources/ConjunctionCore/P2P/` | `docs/P2P_PROTOCOL.md`, `testdata/p2p_wire.json` |
| Engine that drives links (inbox, swipes out of range, friends, store-and-forward, receipts, de-dup) | `android/app/.../nearby/NearbyEngine.kt` | `ios/Sources/ConjunctionCore/Nearby/NearbyEngine.swift` (Linux-tested) | protocol §6, §7 |
| Radio | `nearby/BleTransport.kt` (+ `ProximityGate.kt`) | `ios/App/Conjunction/Nearby/BleTransport.swift` (+ core `ProximityGate.swift`) | protocol §2, §3, §12 |
| Background | `nearby/NearbyBluetoothService.kt` (`connectedDevice` FGS) | `bluetooth-central` / `bluetooth-peripheral` modes + state restoration | SPEC 5c "Background" |
| Identity key | `nearby/IdentityKeys.kt` (Keystore `PURPOSE_AGREE_KEY`, API 31+) | `Services/IdentityKeychain.swift` (Secure Enclave or Keychain, this-device-only) | protocol §8.1 |
| Local store | `nearby/P2pStore.kt` (DataStore JSON + `files/p2p/photos/`) | core `Nearby/P2PStore.swift` + `App/Persistence.swift` (Application Support, excluded from backup) | — |
| What the phone sends (hello/card builder) | `nearby/NearbyRepository.kt` `buildLocal` | core `AppSupport/OwnerProfile.swift` `localPeerProfile` | protocol §5.3, §11 |
| Screening of received content | `nearby/ContentScreening.kt` | `Services/ContentScreener.swift` + core `Nearby/ContentRules.swift` | SPEC 5c "Safety" |
| Report email | `nearby/ReportEmail.kt` | core `Nearby/NearbyText.swift` + `Services/MailComposer.swift` | `legal/IN_APP_CONSENT_COPY.md` §8 |
| Consent strings (generated, never hand-edited) | `res/values/consent_strings.xml` ← `android/app/tools/import_consent_strings.py` | `ConjunctionCore/AppSupport/ConsentStrings.swift` ← `ios/App/tools/import_consent_strings.py` | `legal/IN_APP_CONSENT_COPY.md` |
| Test peer / reference implementation | — | — | `tools/p2p-sim/` (Python, BlueZ) |

Both platforms run the same engines; `testdata/golden_*.json` and `testdata/p2p_wire.json` are read
by the Kotlin tests, the Swift tests and (for the protocol and compatibility) the Python sim, so a
match computed on an Android phone and on an iPhone gives the same kind and the same percentage.

## 3. Discovery and connection

- Each phone with Nearby on **advertises** one 128-bit service UUID
  (`7C0A1E5F-6A3B-4E2D-9F11-C0471C710001`), with no device name and no service data, and **hosts a
  GATT server** with `INBOX` (write-with-response) and `OUTBOX` (notify). No pairing, no bonding.
- It also **scans** for that UUID. A peer counts as "close by" when its RSSI is ≥ −80 dBm in at least
  two sightings within 30 s (`ProximityGate`). The UI says "nearby"; no distance is computed or
  shown, and no location API is used.
- The central connects, asks for ATT MTU 517, discovers the service, subscribes to `OUTBOX`, then
  both sides run one `P2pLink`. Frames are `min(ATT_MTU − 3, 512)` bytes with a 7-byte header; a
  message may span many frames. Inbound frames are rate-limited per link.
- If two phones connect to each other twice, the link whose central has the smaller ephemeral id
  is kept (protocol §7.2). At most 4 links run at once (`MAX_LINKS` / `maxLinks`).
- **iOS limitation:** a backgrounded iPhone advertises only in Apple's "overflow area", which Android
  phones (and the laptop peer) cannot see. Two phones find each other when either is Android or in
  the foreground, or both are iPhones. The app says so (`nearby_ios_background_note`).
- **Android 8 to 11:** a BLE scan there needs location permission, which the app does not request,
  so Nearby is hidden below Android 12 (API 31) (`nearby_android_old_version`).

## 4. Data flow on one link

```
 A                                                     B
 |-- hello: eid, match{gender,seeking,relationship,    |   to EVERY phone that connects,
 |          lookingFor}, compat{sun,moon,venus,mars,   |   unencrypted
 |          asc?, animal, stem, blood?, birthDate,     |
 |          birthHour?}, ft[8..64 tags + decoys] ----->|
 |<--------------------------------------------- hello |
 |                                                     |
 |   both: age >= 18? -> MatchFilter.kind -> Compatibility.score + twins
 |   no kind -> bye no_match; nothing shown, nothing stored
 |                                                     |
 |-- card: kind, firstName, bio line, photo meta ----->|   only if a match, unencrypted
 |-- photo: JPEG <= 61440 B, <= 640 px (MTU >= 185) -->|
 |<------------------------------------- card + photo  |
 |   match animation (foreground) / local notification (background);
 |   card waits in the Matches inbox for 24 h
 |                                                     |
 |-- swipe right/left -------------------------------->|   left = silent, bye done
 |<------------------------------------------- swipe   |
 |                                                     |
 |   mutual right:
 |-- keyx: identity pubkey ik, link-ephemeral ek ----->|
 |<------------------------------------------- keyx    |
 |   ECDH(ik,ik) + ECDH(ek,ek) -> HKDF-SHA256 -> per-direction AES-256-GCM keys + kc key
 |-- kc ---------------------------------------------->|   proves possession of ik
 |<--------------------------------------------- kc    |
 |   both store a friend record: ik, friend secret fs, card, photo, safety code
 |                                                     |
 |== chat {ctr, ct} (AES-256-GCM, AAD "CJ1-CHAT") ====>|   end-to-end encrypted
 |<===================================== ack {id}      |
```

**Later meetings.** Ephemeral ids rotate every 15 minutes and on every Nearby toggle. A friend
recognises a friend from `hello.ft`: `HMAC-SHA256(fs, "CJ1-TAG" || eid)` truncated to 8 bytes, padded
with random decoys, so a stranger cannot link two sightings by the tags. A recognised friend goes
straight to `keyx`/`kc` (the key must equal the stored one), and queued messages are delivered in
order, paced below the receiver's rate limit, each with an `ack`; read receipts follow.

**Swipes made out of range** are stored and sent the next time the same profile fingerprint is
seen within the 24-hour inbox window.

## 5. What is sent to whom (privacy table)

| Data | Advertisement (anyone scanning) | `hello` (every Nearby phone that connects) | `card`/`photo` (qualifying matches only) | After mutual match (friend only) | Never |
|---|---|---|---|---|---|
| Service UUID | yes | | | | |
| Rotating ephemeral id | | yes | | | |
| Gender, seeking, relationship status, looking-for | | yes (needs orientation consent) | | | |
| Sun, Moon, Venus, Mars, Ascendant longitudes (0.01°) | | yes (Ascendant only if birth time known) | | | |
| Chinese year animal, day-pillar stem | | yes | | | |
| Birth date, birth hour | | yes (hour only if known) | | | birth minute |
| Blood type A/B/AB/O | | only with the separate blood-type consent | | | Rh factor |
| Friend tags + decoys | | yes | | | |
| First name, first line of bio | | | yes | | |
| Profile photo (≤ 640 px JPEG) | | | yes, if ATT MTU ≥ 185 | | |
| Swipe answer | | | to that one peer | | |
| Identity public key, chat text, read receipts | | | | yes (chat encrypted) | |
| Birth place, coordinates, time zone, full chart, readings, palm photos, location, device name, phone number, contacts, any account id | | | | | never |

Consequences the legal text states plainly (`legal/PRIVACY.md` §3, consent `nearby_point_*`):

- Nothing before the mutual match is encrypted; anyone who connects or sniffs can read the hello,
  card, photo and swipe. A modified app can keep them.
- The hello profile (four longitudes, birth date and hour) is close to unique, so a determined
  scanner could recognise a person across ephemeral-id rotations even though the id itself changes.
- The match card shows the other person that you are physically near them right now.
- The first `keyx` is trust-on-first-use; the safety code lets two people check it in person.

Consent gates in code (both platforms):

- No hello is built at all without a dating profile **and** the recorded orientation consent
  (`CONSENT_ORIENTATION_DATA`). Withdrawing it turns Nearby off.
- Blood type is in the hello only when `bloodForMatching` is on (`CONSENT_BLOOD_TYPE_MATCHING`).
- Nearby starts only after the Nearby disclosure screen (`CONSENT_NEARBY_BLUETOOTH`) and the system
  Bluetooth / "Nearby devices" permission prompt that follows it.

## 6. What is stored, where, and for how long

All of it is on the phone. Nothing is uploaded.

| Data | Android | iOS | Lifetime |
|---|---|---|---|
| Profile, birth data, blood type, dating profile | Preferences DataStore `profile` | `Application Support/Conjunction/profile.json` | until edited or wiped |
| Consent records (doc/key, version, time, app version, text SHA-256) | DataStore `consents` | `consents.json` | until wiped |
| Readings cache | DataStore `readings` | `readings.json` | per day, until wiped |
| Palm photos | `files/palms/` | `palms/` | deleted after the reading unless saved |
| Nearby state: Matches inbox, friends (ik, fs, card), chats, queued messages, hide/block list | `files/datastore/p2p.json` | `nearby/p2p.json` | inbox 24 h; left swipe / unmatch hidden 30 days; snooze 7 days; block until unblocked (iOS) or wiped; friends and chats until unmatch/block/wipe; queued messages until delivered (no expiry) |
| Received photos | `files/p2p/photos/<sha256>.jpg` | `nearby/photos/` | with the card or friend that uses them |
| Identity private key | Android Keystore | Secure Enclave or Keychain (`AfterFirstUnlockThisDeviceOnly`) | until wiped |

- Hellos from non-matches are evaluated in memory and dropped. Hide and block entries hold only the
  profile fingerprint `SHA-256("CJ1-FP" || canonical hello.compat)[0..16)` (protocol §7.4).
- Queued messages wait in plaintext in app-private storage until a secure link to that friend opens.
- **Backups:** Android `data_extraction_rules.xml` / `backup_rules.xml` include only a UI-preferences
  file (no app data is in it today); iOS marks the app folder `isExcludedFromBackup`, so only the
  `@AppStorage` palm-hand choice and the `@SceneStorage` tab can be in a backup. The identity key
  cannot be backed up on either platform, so a restored phone starts with no friends.
- **Delete all my data** (Settings) stops Nearby, clears the Nearby store and photos, deletes the
  identity key, readings, consent records, palm photos, the profile photo and the profile, and
  returns to onboarding. **Export my data** writes the same data (minus friend secrets) to a file the
  user saves or shares.
- What a phone already sent to other phones stays there. There is no remote delete or remote unmatch.

## 7. Safety without a server

- **18+:** own age gate in onboarding; peers younger than 18 by `hello.compat.birthDate` never match.
- **Received photos** are screened on the receiving phone: Android asks Gemini Nano with an image
  prompt when the on-device model accepts images, iOS uses SensitiveContentAnalysis when the user
  has it enabled; otherwise the photo is blurred until the viewer taps "Show photo". Checks that
  need the on-device language model (Gemini Nano photos and text on Android, Foundation Models text
  on iOS) run only while the app is in the foreground; until then the item waits as "pending".
- **Received chat text** goes through deterministic rules first (contact details, links, money,
  sexual content, threats), then the on-device model when available and the app is in the
  foreground. Flagged messages are hidden behind "Show anyway", never deleted.
- **Block** (by profile fingerprint for strangers, by identity key for friends, plus the open link),
  **snooze**, **unmatch**, **pause Nearby** (1 hour / until tomorrow) and **turn off**.
- **Report** = block + a pre-filled email the user sends to the support address with the card
  fields, reason, description and (optionally) the messages. The photo is not attached. This is
  the only channel to the developer; nobody can remove content from other phones or ban a user.

## 8. Testing the protocol without two phones

`tools/p2p-sim/` is a Python implementation of the protocol that runs on a laptop's BlueZ adapter
as a configurable nearby person (personas `aurora`, `orion`, `sol`). It is also the reference
implementation from which `testdata/p2p_wire.json` is generated. Kotlin `:core` and Swift
`ConjunctionCore` replay those vectors byte-for-byte. See `README.md` for commands and
`tools/p2p-sim/README.md` for details and limits.

## 9. Known gaps (2026-11-03)

- No over-the-air test has been done yet: not phone ↔ laptop, not phone ↔ phone, not Android ↔ iPhone.
- The iOS app has never been compiled against the iOS SDK (first macOS CI run pending).
- Android: no "Blocked people" / unblock screen (iOS has one).
- The App Review "Preview a match" demo does not exist on either platform.
- While Nearby is paused on Android, the foreground service and its notification stay up with the
  radio off (Play foreground-service policy question).
- Battery: continuous scanning with no duty cycling or batched scan results yet.
