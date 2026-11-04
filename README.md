# Conjunction

*"Your stars, nearby."* An 18+ astrology app for Android and iPhone, with optional matching for
dating or friendship between people who are physically close to each other.

- **Birth chart** (Sun to Pluto, Ascendant, Midheaven, whole-sign houses, aspects) on a chart wheel
  that draws itself, computed on the phone (Meeus / truncated VSOP87, checked against Swiss
  Ephemeris).
- **Readings written on the phone**: daily, natal, Eastern (Chinese zodiac, Four Pillars, blood
  type) and palm readings, by the on-device model (Gemini Nano through ML Kit on Android, Apple
  Foundation Models on iOS), or from bundled template text when no model is available. Never a
  cloud model.
- **Palm reading** from a camera photo: hand landmarks plus ridge detection on the phone.
- **Nearby ("Conjunctions")**, off by default: phones find each other over **Bluetooth Low Energy**,
  with **no server at all**. Both phones work out the same compatibility score. A match plays the
  animation and shows a swipe card, and a mutual right swipe opens an **end-to-end encrypted** chat
  that is delivered phone to phone. Messages to a friend out of range wait and are delivered the
  next time the two phones are near each other.

The working name is a placeholder (`docs/NAMES.md`). It lives in one place per platform: Android
`strings.xml` `app_name`, iOS `CFBundleDisplayName` in `ios/App/project.yml`. Package / bundle id:
`com.metavirtuoso.conjunction`. Publisher: MetaVirtuoso.

**Read first:** `SPEC.md` is the product spec. Its section **5c (serverless peer-to-peer)
overrides every server/backend part** of sections 5, 5b, 6 and "Backend". The wire protocol is
`docs/P2P_PROTOCOL.md`; data flow and privacy are in `docs/ARCHITECTURE.md`.

## Repository layout

```
SPEC.md                  product + engineering spec (5c = serverless P2P, authoritative)
docs/
  P2P_PROTOCOL.md        normative Bluetooth protocol v1 (GATT, framing, messages, crypto, limits)
  ARCHITECTURE.md        components, P2P data flow, what is sent/stored, safety, known gaps
  ENGINES.md, COMPATIBILITY.md, MATCH_FILTER.md   engine conventions and algorithms
  NAMES.md, NAMES_DATING.md                        name candidates
android/                 Gradle project
  core/                  pure Kotlin/JVM: engines + p2p protocol (package core.p2p), JVM tests
  app/                   Compose app: UI, on-device AI, palm, Nearby (nearby/), legal, tz
                         app/README.md has the module guide; src/debug has MatchGalleryActivity
                         and P2pTestService (debug builds only)
ios/
  Package.swift, Sources/ConjunctionCore, Tests/   ConjunctionKit: engines, P2P protocol, Nearby
                         engine and app logic without UI; builds and tests on Linux
  App/                   SwiftUI app (iOS 17+), XcodeGen project.yml, generated .xcodeproj
  README.md              iOS guide
.github/workflows/       ios-build (CI build + tests), ios-sideload (unsigned IPA), ios-testflight
tools/p2p-sim/           Python Bluetooth test peer + protocol reference implementation (BlueZ)
testdata/                golden vectors shared by Kotlin, Swift and Python (+ generators in tools/)
art/                     generated artwork, manifest.json, generator script
legal/                   privacy policy, terms, waiver, community guidelines, safety tips,
                         IN_APP_CONSENT_COPY.md (source of every consent string); archive/
store/                   Play and App Store listing copy, Data safety / App Privacy answers,
                         permission justifications, review notes, release checklist
backend/                 NOT USED: the abandoned Firebase server design, kept for the record
```

## Building

Toolchains on this PC (SPEC "Toolchains"): JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64`,
Android SDK `~/Android/Sdk` (platform 36), Swift 6.4 at
`~/swift/swift-6.4.0-RELEASE-ubuntu26.04/usr/bin/swift`, XcodeGen at
`~/tools/XcodeGen/.build/release/xcodegen`. There is no Mac: the iOS app builds only on GitHub
Actions.

This PC has 15 GB of RAM. Run **one Gradle build at a time**, use `swift build -j 2`, never start an
Android emulator, and run `./gradlew --stop` when finished. `android/gradle.properties` caps the
Gradle heaps.

### Android

```bash
cd android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :core:test                          # engines + p2p protocol (golden vectors, p2p_wire.json)
./gradlew :app:testDebugUnitTest              # app logic, Nearby engine, Robolectric screen captures
./gradlew :app:assembleDebug                  # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:bundleRelease lint             # release AAB (signed only if keystore.properties exists)
./gradlew --stop
```

- minSdk 26, target/compile 36. **Nearby needs Android 12 (API 31)** and is hidden below that,
  because BLE scanning on Android 11 and lower requires location permission and the app requests
  none.
- The first clean build downloads Google's `hand_landmarker.task` and checks its SHA-256.
- Release signing reads `android/keystore.properties`, which is git-ignored and never committed.
- Screens without a phone: `./gradlew :app:testDebugUnitTest --tests '*ScreenCaptureTest'
  -Pconjunction.screenshots=$PWD/screenshots/robolectric`.
- Installing on a phone that is already in use: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  keeps the app's data. Do not uninstall the app or clear its data on the owner's phone.

### iOS

```bash
cd ios
~/swift/swift-6.4.0-RELEASE-ubuntu26.04/usr/bin/swift build -j 2 --build-tests
~/swift/swift-6.4.0-RELEASE-ubuntu26.04/usr/bin/swift test -j 2 --skip-build
~/tools/XcodeGen/.build/release/xcodegen generate --spec App/project.yml   # after editing project.yml
App/tools/parse_check.sh                                                     # syntax check of the app sources
```

The SwiftUI app (`ios/App`) compiles only on macOS. Push to GitHub and use the workflows:

- `ios-build.yml`: builds the app and runs the tests on `xcode-27`, and checks that the build contains no keys.
- `ios-sideload.yml`: builds an unsigned IPA for Plume Impactor / free-Apple-ID sideloading. The
  SensitiveContentAnalysis entitlement is dropped from this build.
- `ios-testflight.yml`: a signed TestFlight upload. It needs the repository secrets `ASC_KEY_ID`,
  `ASC_ISSUER_ID`, `ASC_KEY_P8` and `APPLE_TEAM_ID` (the paid Apple Developer Program).

### Generated files (never hand-edit)

| Output | Source | Command |
|---|---|---|
| `android/app/src/main/res/values/consent_strings.xml` | `legal/IN_APP_CONSENT_COPY.md` | `python3 -I android/app/tools/import_consent_strings.py` |
| `ios/Sources/ConjunctionCore/AppSupport/ConsentStrings.swift` | same | `python3 -I ios/App/tools/import_consent_strings.py` |
| Android drawables, iOS `Assets.xcassets` | `art/` | `python3 -I android/app/tools/import_art.py`, `python3 -I ios/App/tools/import_art.py` |
| `testdata/p2p_wire.json` | `tools/p2p-sim` Python code | `$PY testdata/tools/make_p2p_wire.py` (deterministic) |
| `testdata/golden_compat.json`, `golden_prompts.json` | Kotlin engines | `./gradlew :core:writeGoldens` |

**After any edit to `legal/IN_APP_CONSENT_COPY.md`, run both consent importers.** The iOS test
`AppSupportTests` fails until `ConsentStrings.swift` matches the markdown.

### Store copy

```bash
python3 -I store/tools/check_lengths.py      # every listing field within its store limit
```

## Testing Nearby with the laptop as the other phone

`tools/p2p-sim` plays a nearby Conjunction user on this laptop's Bluetooth adapter (`hci0`). It
needs no root and changes no BlueZ settings. Its personas are `aurora` (female, seeking both, has
a photo, swipes right), `orion` (male seeking female, dating only) and `sol` (in a relationship,
friends only, swipes left). Details and limits: `tools/p2p-sim/README.md`.

One-time setup (the venv lives outside the repo; as of 2026-11-03 it **does not exist yet** on this
PC):

```bash
python3 -m venv ~/.venvs/conjunction-p2p
~/.venvs/conjunction-p2p/bin/pip install -r tools/p2p-sim/requirements.txt   # bleak, dbus-fast, cryptography
PY=~/.venvs/conjunction-p2p/bin/python
```

No radio needed:

```bash
(cd tools/p2p-sim && $PY -m unittest discover -s tests -t . -v)   # protocol + compat port vs golden files
$PY tools/p2p-sim/sim.py loopback aurora orion                    # full flow in memory
```

Against a phone:

```bash
$PY tools/p2p-sim/sim.py both aurora -v --log /tmp/aurora.log      # advertise + GATT server + scan
```

1. On the phone, finish onboarding and set up a dating profile that can match the persona. For
   example, if the phone's profile is male seeking female, `aurora` matches it for dating. Turn on
   Nearby, allow "Nearby devices" and notifications, and keep the phone within about a metre.
2. Expected: the sim log shows `hello` both ways, the match result (kind and percentage, which must
   equal the phone's), `card` and `photo`. The phone shows the match animation and card.
3. Swipe right on the phone. `aurora` swipes right too, and both sides then show `keyx`, `kc` and
   the same safety code. Chat from the phone; the sim decrypts, acks and replies.
4. Store-and-forward: stop the sim, send a message from the phone ("Waiting — delivered next time…"),
   restart the sim, and check that it arrives. Or run
   `$PY tools/p2p-sim/sim.py queue aurora <name> "text"` to queue one from the laptop.
5. Also try: a left swipe (nothing is shown and the card does not come back for 30 days), block,
   pause Nearby, Bluetooth off and back on, and an iPhone in the background. A backgrounded iPhone
   cannot be seen by the laptop, which is expected.

**Debug-build test persona on Android.** Instead of the owner's profile, the debug-only
`P2pTestService` can run the Nearby stack as the persona "Vega", with its own store and key alias.
See `android/app/README.md` → "Bluetooth test persona" for the `adb shell am start-foreground-service`
commands. Those instructions grant the app's Bluetooth permissions with `pm grant`, which is the
owner's call on their own phone. **Animation without Bluetooth:** use `debug.MatchGalleryActivity`
(`adb shell am start -n com.metavirtuoso.conjunction/.debug.MatchGalleryActivity --es screen match`).

Without a phone: `sudo btmon`, a BLE scanner app such as nRF Connect, or a second USB adapter as
`hci1` (`sim.py peripheral aurora --adapter hci0` plus `sim.py central orion --adapter hci1`) gives
a real over-the-air round trip.

## Status (2026-11-03)

The feature matrix below compares the working tree with SPEC (5c overrides 5/5b/6). It is
**not committed yet**; the coordinator commits.

| Feature | Android | iOS |
|---|---|---|
| Birth chart + wheel | done (`core/chart`, `ui/chart/ChartWheel.kt`) | done (`ConjunctionCore/Chart`, `Screens/ChartWheel.swift`) |
| Historical time zone for birth place | partial: `tz/tzgrid.bin` not built (fallback `zone1970.tab` by country; `TimeZoneGridTest` skipped) | done (`CLGeocoder` placemark `timeZone`) |
| On-device readings + fallback | done (`ai/`) | done (`Services/ReadingService.swift`, `OnDeviceModel.swift`) |
| Chinese zodiac, BaZi, blood type | done | done |
| Palm reading | done (`palm/`, MediaPipe + `PalmGeometry`; image passed to Gemini Nano when accepted) | done in code (`Services/PalmScanner.swift`, `ConjunctionCore/Palm/PalmGeometry.swift`) |
| Onboarding: 18+, legal acceptance, consents | done (`ui/screens/Onboarding.kt`, `legal/Legal.kt`) | done (`Screens/Onboarding.swift`, `AppSupport/Legal.swift`) |
| P2P protocol (frames, JSON, crypto, link) | done (`core/p2p`, golden-vector tests) | done (`ConjunctionCore/P2P`, golden-vector tests) |
| BLE transport (advertise, GATT server, scan, client, MTU, proximity gate) | done in code (`nearby/BleTransport.kt`); **not tested over the air** | done in code (`Nearby/BleTransport.swift`, state restoration); **not tested over the air** |
| Background running | done (`connectedDevice` FGS, `NearbyBluetoothService.kt`) | done (Bluetooth background modes) |
| Matching on both phones, twins | done (`P2pMatching`, `NearbyEngine.kt`) | done (`P2PMatching`, `Nearby/NearbyEngine.swift`) |
| Match animation, twin variant, swipe card | done (`ui/match/MatchExperience.kt`) | done (`Screens/MatchExperience.swift`) |
| Matches inbox 24 h, swipes made out of range | done | done |
| Received photo screening / blur | done (`ContentScreening.kt`) | done (`Services/ContentScreener.swift`) |
| Encrypted chat, store-and-forward, receipts | done (`ui/screens/ChatScreen.kt`) | done (`Screens/ChatScreen.swift`) |
| Friend recognition | done | done |
| Incoming text screening | done (rules + Gemini Nano) | done (rules + Foundation Models) |
| Block, snooze, unmatch, pause | done | done |
| Unblock / "Blocked people" screen | **missing** | done (`ProfileScreen.swift`) |
| Report (block + email) | done (`nearby/ReportEmail.kt`) | done (`Services/MailComposer.swift`) |
| Delete all my data | done | done |
| Export my data (local file) | present, but the "Export my data" label is hard-coded and not in the consent copy | same |
| "Preview a match" demo for App Review | **missing** | **missing** |
| Compiled for the target platform | yes (debug APK and release AAB build) | **no**: never compiled against the iOS SDK; the first macOS CI run is pending |
| Firebase / push / location / TypeSafe removed | done | done |

## Owner to-do

**Before anyone else tests this**

- [ ] Commit the working tree. Nothing since `471adde` is committed, including all of the P2P code and the iOS app.
- [ ] Create the sim venv (see above) and run the laptop-peer test against the Pixel. **No
  over-the-air test has ever been done.**
- [ ] Push to GitHub and run `ios-build.yml`. This is the first real compile of the iOS app. Then
  sideload it (`ios-sideload.yml`) and test iPhone ↔ Android, iPhone ↔ laptop (foreground) and
  iPhone ↔ iPhone (background).
- [ ] The installed debug build on the Pixel will ask you to accept the terms again, because all
  documents are now version 2.0.

**Decisions only the owner can make** (details in `legal/README.md`, `store/play/POLICY.md`, `store/play/PERMISSIONS.md`, `store/appstore/REVIEW_NOTES.md`):

- [ ] Final app name (`docs/NAMES.md`).
- [ ] Reporting on Play: report-by-email (leaves the app) vs. Play's "report without leaving the app" expectation for AI and UGC apps.
- [ ] Build the "Preview a match" demo for App Review, or delete that paragraph from
  `store/appstore/fastlane/metadata/review_information/notes.txt` (it is now marked).
- [ ] App Store label: "Data Not Collected" (recommended) or the conservative alternative.
  On Play, the Data safety form declares the Nearby data as collected but not shared.
- [ ] Export compliance: `ITSAppUsesNonExemptEncryption` is YES. Answer the App Store Connect export questions.
- [ ] Whether a paused Nearby should keep the Android foreground service running (Play FGS policy).
- [ ] Keep or remove "Export my data". If you keep it, add a `settings_export` row back to the consent copy.

**Before store submission**

- [ ] Attorney review of `legal/`. Fill in `[CONTACT EMAIL]`, `[LEGAL ENTITY NAME]`, `[ADDRESS]`,
  `[CHILD SAFETY CONTACT NAME]`, `[EMAIL PROVIDER]`, `[EFFECTIVE DATE]` and the EU/UK representatives.
  The apps default the support address to `themetavirtuoso@gmail.com`.
- [ ] Publish the legal pages at the URLs in the store metadata, including the `#child-safety` anchor.
- [ ] A monitored report inbox with notifications on, to back the 24-hour commitment.
- [ ] Record the two-device demo video and replace `[DEMO VIDEO URL]`. Record the
  `connectedDevice` foreground-service declaration video for Play.
- [ ] Work through `store/RELEASE_CHECKLIST.md`, including checking the merged manifest and the SDK
  list against `store/play/DATA_SAFETY.md`.

**Secrets.** No API key, keystore or password is in the repo, the build outputs or the git history.
This was checked on 2026-11-03 against the contents of `~/Desktop/*-key.txt` and the usual key
patterns. `~/Desktop/oai-key.txt` is read only by `art/tools/generate_art.py` at development time.
The apps contain no keys. Signing material (`android/keystore.properties`, `*.jks`), `.env*` and
the sim's test identities (`tools/p2p-sim/.state/`) are git-ignored.
