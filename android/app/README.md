# Conjunction — Android app (`:app`)

Jetpack Compose app for `com.metavirtuoso.conjunction` (versionCode 1, versionName 1.0.0). Engines
(chart, Chinese zodiac, compatibility, match filter, palm features, prompts, fallback readings)
live in `:core`; this module is UI, on-device AI, camera/palm analysis and serverless Bluetooth Nearby
(SPEC 5c, `docs/P2P_PROTOCOL.md`). There is no Firebase, no push and no location permission.

## Build

```
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug :app:bundleRelease :core:test :app:testDebugUnitTest lint
```

* **Network once per clean build:** `:app:downloadHandLandmarker` fetches Google's official
  `hand_landmarker.task` (float16, v1) into `build/generated/mlmodels/` and checks its SHA-256.
* **Release signing** comes from `android/keystore.properties` (same keys as Sundial:
  `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it `bundleRelease` produces an
  unsigned bundle. R8 (minify + shrink resources) is on; rules in `proguard-rules.pro`.
* **Support address** (Report this reading, Report a person, arbitration opt-out): defaults to
  `themetavirtuoso@gmail.com` (the store files' address); override with `-Pconjunction.supportEmail=...`.

## Generated assets (re-run when the sources change)

| Asset | Source | Command |
|---|---|---|
| `res/drawable-nodpi/*.webp`, launcher foreground mipmaps, `splash_mark` | `art/` (OpenAI-generated) | `python3 -I android/app/tools/import_art.py` |
| `res/values/consent_strings.xml` | `legal/IN_APP_CONSENT_COPY.md` | `python3 -I android/app/tools/import_consent_strings.py` |
| `assets/tz/tzgrid.bin` (offline lat/lon → IANA zone) | timezone-boundary-builder via Timeshape | `./gradlew :app:writeTimeZoneGrid` |
| `assets/legal/*.md` | `legal/` | automatic (`:app:copyLegalDocs` before every build) |
| `res/font/*` + `assets/licenses/OFL-*.txt` | github.com/google/fonts (OFL) | downloaded once, committed |

## Where things are

| Package | What |
|---|---|
| `ui/theme` | "Celestial Art Nouveau" palette, Cinzel Decorative / Cinzel / Cormorant Garamond typography, gold shimmer brushes |
| `ui/components` | starfield with parallax + twinkle, filigree panels, gold buttons, reading card (flip), Markdown renderer, art lookups |
| `ui/chart/ChartWheel.kt` | Canvas chart wheel that draws itself in |
| `ui/screens` | onboarding (age gate, legal, birth data, blood type, dating profile + consent, Nearby, notifications), Chart, Today, Eastern, Palm, Nearby, Chat, Profile |
| `ui/match` | match animation (medallions spiral in, merge, burst, count-up; twin variant with star confetti), swipe card, "It's a Conjunction!" popup |
| `ai` | Gemini Nano wrapper (`OnDeviceModel.kt`, ML Kit GenAI Prompt API 1.0.0-beta5), `ReadingService` (core prompts → model → cleaner, or core fallback), daily reminder |
| `palm` | MediaPipe HandLandmarker + documented ridge analysis → core `PalmAnalyzer` |
| `nearby` | Bluetooth Nearby: `BleTransport` (advertiser + GATT server INBOX/OUTBOX + filtered scanner + GATT client, RSSI gate, reconnect back-off), `NearbyEngine` (drives core `P2pLink` per link: de-duplication, Matches inbox, swipes out of range, friends, store-and-forward encrypted chat, receipts), `P2pStore` (DataStore JSON: friends, chats, inbox, hide/block list), `KeystoreIdentity` (P-256 PURPOSE_AGREE_KEY), `ContentScreening` (Gemini Nano + rule fallback), `NearbyBluetoothService` (`connectedDevice` FGS), local notifications, report email |
| `tz` | offline time-zone grid reader |
| `legal` | bundled documents, versions, SHA-256, consent ledger |

## Screenshots

Robolectric render of every main screen (no phone or emulator needed):

```
./gradlew :app:testDebugUnitTest --tests '*ScreenCaptureTest' -Pconjunction.screenshots=$PWD/screenshots/robolectric
```

On a phone, debug builds include `debug.MatchGalleryActivity` (src/debug only, absent from
release) so the match animation and swipe card can be seen without a second phone:

```
adb shell am start -n com.metavirtuoso.conjunction/.debug.MatchGalleryActivity --es screen match   # or twin, card
```

## Bluetooth test persona (debug builds)

`debug.P2pTestService` runs the real Nearby stack as a fixed test persona ("Vega") with its own
store and Keystore alias (the owner's data is never touched), driven over adb. With
`tools/p2p-sim` on the laptop:

```
adb shell pm grant com.metavirtuoso.conjunction android.permission.BLUETOOTH_SCAN   # + ADVERTISE, CONNECT
PY tools/p2p-sim/sim.py both aurora -v                          # laptop: test peer Aurora
S="-n com.metavirtuoso.conjunction/.debug.P2pTestService"
adb shell am start-foreground-service $S -a start
adb shell am start-foreground-service $S -a swipe --es dir right
adb shell am start-foreground-service $S -a chat --es text "hello"
adb shell am start-foreground-service $S -a status            # dumps cards, friends, messages
adb shell am start-foreground-service $S -a stop               # ... and -a reset to wipe Vega's data
adb logcat -s ConjunctionP2P ConjunctionP2PTest
```

## Offline time-zone grid

`assets/tz/tzgrid.bin` is regenerated by `./gradlew :app:writeTimeZoneGrid` (needs ~4 GB heap and
~30-60 min). On a memory-constrained PC use the standalone twin `tools/tzgrid/TzGridGen.java` (same algorithm
and format; classpath = the Timeshape 2026b.29 jar and its dependencies from the Gradle cache):

```
javac -cp "$CP" -d out tools/tzgrid/TzGridGen.java
java -Xmx1536m -cp "out:$CP" TzGridGen src/main/assets/tz/tzgrid.bin 8   # output path, threads
```
The file must pass `TimeZoneGridTest`.
