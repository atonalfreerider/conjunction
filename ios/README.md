# Conjunction for iPhone (and iPad)

Two parts:

| Part | What | Builds where |
|---|---|---|
| `Package.swift`, `Sources/ConjunctionCore`, `Tests/` | **ConjunctionKit**: the engines shared with Android (chart, Chinese zodiac, blood type, compatibility, match filter, palm features, prompts, fallback readings), the serverless P2P protocol (`P2P/`), and the app's own logic that needs no UI (`Nearby/`, `AppSupport/`, `Palm/PalmGeometry.swift`) | Linux and macOS: `swift build && swift test` |
| `App/` | The SwiftUI app (iOS 17+), XcodeGen spec `App/project.yml` | macOS only (GitHub Actions, `.github/workflows/ios-*.yml`) |

Bundle id `com.metavirtuoso.conjunction`, version 1.0.0 (1), display name `Conjunction`
(`CFBundleDisplayName` in `App/project.yml` is the only place it lives).

## Features (mirrors the Android app screen for screen)

- **UX 2.0 (SPEC 5d)** — bar Today · Chart · [Nearby] · Readings · Profile with the raised 68 pt
  gold Nearby medallion (shimmer when on, badge for new matches); legal UI minimised to L1 (one
  clickwrap line above **Begin**), L2 (one Nearby consent sheet), L3 (the blood-type switch) and L4
  (a compact re-accept / notice sheet: only a major version asks again); everything else in Profile ›
  Settings › Privacy & legal.
- **Onboarding** — four screens: Welcome, Your birth (one text box parsed by `BirthTextParser` into
  date / time / place chips, the on-device model fills only empty chips; the birth date is the 18+
  gate), About you (`AboutYouParser` + guided generation, optional), Your card (PhotosPicker with a
  Vision face-centred 512×640 crop, "Write one for me" bio, Instagram handle with `PasteButton`,
  optional). Places are looked up with `CLGeocoder` (the placemark's `timeZone` gives the IANA zone,
  no location permission; coordinates can be typed). No permission prompt during onboarding.
- **Chart** (animated wheel drawn in `Canvas`), **Today**, **Readings** (Eastern | Palm) — readings
  written by Apple's on-device model (Foundation Models, iOS 26 + Apple Intelligence) from
  ConjunctionCore's prompts, cleaned by `ReadingCleaner`, cached per day; on iOS 17–25 or without
  Apple Intelligence the bundled `FallbackReadings` text is used. Never a cloud model.
- **Palm** — AVFoundation camera with the gold guide overlay (mirrored for the right hand) or
  PhotosPicker (no library permission) → Vision `VNDetectHumanHandPoseRequest` (21 joints in the
  MediaPipe order) → `PalmGeometry` (rectify to 240×300, CLAHE 8×8 clip 4, high-pass, multi-scale
  Hessian ridge filter, lower bar for the fate line — the Android algorithm, ported and tested on
  Linux) → `PalmAnalyzer` → `PalmFeatures` → reading. The result shows the traced lines and the
  "scanner view". The photo is kept only if you tap Save (app-private storage).
- **Nearby (serverless, Bluetooth LE)** — `App/Conjunction/Nearby/`: see below. The Nearby tab holds
  only cards (status card / empty state, New matches swipe stack, Waiting, Conjunctions); all toggles
  are in Profile › Settings › Nearby.
- **Matches** — full-screen animation, swipe card (Pass / ☌ Chart / Accept, ⋯ Snooze / Block /
  Report / Safety Tips), "It's a Conjunction!" (Say hi / See our chart), then the end-to-end
  encrypted chat (header photo + name and score chip open the Compatibility view; ⋯ holds Instagram,
  the per-friend share switch, safety code, Safety Tips, unmatch, block, report).
- **Compatibility view** — `Screens/CompatibilityScreen.swift`: medallions, count-up score, the
  synastry bi-wheel (`SynastryWheel`, from the peer's stored `hello.compat`), score breakdown,
  aspects, Eastern relations and a reading generated once and cached on the match / friend.
- **Instagram** — handle only (no sign-in or import exists for consumer accounts); sent only to
  friends inside the encrypted chat (`profile` message); the chip opens
  `https://www.instagram.com/<handle>/` (universal link → app, else Safari).
- **Profile** — Your card (live preview, tap to edit), Birth chips, Eastern (blood type + L3), then
  Settings › Nearby / Notifications / Privacy & legal (documents, Nearby consent with Withdraw,
  records, arbitration opt-out, blocked people, export, **Delete all my data**).
- **Look** — the Android "Celestial Art Nouveau" tokens (`Theme/Theme.swift`), bundled OFL fonts
  (Cinzel Decorative, Cinzel, Cormorant Garamond, licences included), filigree panels, gold-leaf
  buttons, starfield with parallax and twinkle. The gold shimmer slides a pre-laid-out gradient by
  `offset` (a render-time transform), so nothing is re-laid out per frame. Art comes from `art/`
  via `App/tools/import_art.py` (same names and sizes as Android).

## Nearby on iOS

`docs/P2P_PROTOCOL.md` is the contract; ConjunctionCore's `P2PLink` speaks it, and
`Nearby/NearbyEngine.swift` (core, Linux-tested) is the port of the Android `NearbyEngine`: link
de-duplication, the 24-h Matches inbox, swipes made out of range, friend records, store-and-forward
chat with delivery / read receipts and pacing, hide (30 days after a left swipe), snooze, block.

- `BleTransport` — `CBPeripheralManager` (one primary service: INBOX `.write` / `.writeable`, OUTBOX
  `.notify`; advertises only the service UUID) and `CBCentralManager` (scan with the service UUID,
  RSSI ≥ −80 dBm in 2 sightings / 30 s, connect, discover, subscribe to OUTBOX, ATT MTU from
  `maximumWriteValueLength(for: .withoutResponse) + 3`, one write-with-response at a time). The
  peripheral answers each INBOX write after the frame reached the link, refuses long writes, sends
  with `updateValue` and waits for `peripheralManagerIsReady`. Both managers use restore identifiers
  (`bluetooth-central` / `bluetooth-peripheral` background modes). Everything runs on one serial queue
  shared with the engine.
- Identity key: Secure Enclave `P256.KeyAgreement` key where available (simulator: software key),
  Keychain item `AfterFirstUnlockThisDeviceOnly`.
- Received photos: SensitiveContentAnalysis when the person turned on Sensitive Content Warning;
  otherwise blurred until "Show photo". Chat text: ConjunctionCore's rules, then the on-device model
  while the app is in the foreground (pending messages are checked when it comes back).
- Local notifications only (`UNUserNotificationCenter`), with the popup art; tapping opens the match
  animation or the chat.
- **Limitation (SPEC 5c):** a backgrounded iPhone advertises only in Apple's overflow area, which
  Android cannot see; the Nearby screen shows `nearby_ios_background_note`. In the background iOS also
  reports each advertiser about once, so the "2 sightings" rule is relaxed to one strong sighting there.

## Commands

```bash
# Core: build and test (Linux or macOS)
cd ios && swift build -j 2 && swift test -j 2

# Regenerate the Xcode project after adding/removing app files (the project is committed)
~/tools/XcodeGen/.build/release/xcodegen generate --spec ios/App/project.yml
cp ios/Package.resolved ios/App/Conjunction.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/

# After editing legal/IN_APP_CONSENT_COPY.md (strings are generated, never retyped)
python3 -I ios/App/tools/import_consent_strings.py

# After the art changes
python3 -I ios/App/tools/import_art.py

# Syntax check of every app file without a Mac
ios/App/tools/parse_check.sh ~/swift/swift-6.4.0-RELEASE-ubuntu26.04/usr/bin/swift
```

`swift test` also checks that the generated strings match the legal markdown and that the
Bluetooth and camera usage strings in `App/project.yml` match `IN_APP_CONSENT_COPY.md` §9, and that
no location key, push mode or Firebase config is in the project.

## CI (GitHub Actions, `runs-on: xcode-27`)

| Workflow | Does |
|---|---|
| `ios-build.yml` | `swift test` on macOS (CryptoKit), regenerates the project, builds the app for the Simulator (Debug) and for devices (Release, unsigned), checks the bundle (legal files, fonts, privacy manifest, no location key, nothing that looks like an API key) |
| `ios-sideload.yml` | Unsigned `Conjunction-sideload.ipa` artifact, for Plume Impactor with a free Apple Account (as for Sundial). The ad hoc signature drops the SensitiveContentAnalysis entitlement, so sideloaded builds blur received photos until tapped |
| `ios-testflight.yml` | Archive + App Store upload on an `ios-v*` tag; needs secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `APPLE_TEAM_ID` (placeholders until the paid program exists) |

## What is verified where

There is no Mac on the development PC.

- **Verified on Linux:** everything in ConjunctionKit (116 tests, Swift 6 language mode, no warnings),
  including two complete Nearby engines wired back to back (match → card + photo → swipes → keys →
  encrypted chat → disconnect → queued message delivered on a new link → read receipt; de-duplication;
  hide after left swipe; blocked friend refused; store file round trip), `PalmGeometry` on synthetic
  palms, consent strings and Info.plist strings against the legal copy.
- **Partly verified on Linux:** every app file parses (`parse_check.sh`). `Persistence`,
  `OnDeviceModel` (without FoundationModels), `ReadingService`, `AppModel`, `NearbyRepository`,
  `NearbyNode` and `BleTransport` were type-checked against hand-written stubs of CoreBluetooth /
  Combine (stubs written from the documented APIs, so they check our code's consistency, not Apple's
  exact signatures).
- **Not verified until CI runs:** the SwiftUI screens, Vision, AVFoundation, CoreLocation,
  UserNotifications, MessageUI, SensitiveContentAnalysis and FoundationModels code type-checking
  against the real SDK; anything at runtime (layout, fonts resolving by PostScript name, the shimmer,
  the match animation, camera, palm detection on real photos); and **all Bluetooth behaviour on
  hardware** (iPhone ↔ Android, iPhone ↔ iPhone, iPhone ↔ `tools/p2p-sim`, background and state
  restoration, MTU, notification back-pressure).
