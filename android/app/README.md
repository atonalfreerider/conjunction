# Conjunction — Android app (`:app`)

Jetpack Compose app for `com.metavirtuoso.conjunction` (versionCode 1, versionName 1.0.0). Engines
(chart, Chinese zodiac, compatibility, match filter, palm features, prompts, fallback readings)
live in `:core`; this module is UI, on-device AI, camera/palm analysis, Nearby and Firebase.

## Build

```
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug :app:bundleRelease :core:test :app:testDebugUnitTest lint
```

* **Network once per clean build:** `:app:downloadHandLandmarker` fetches Google's official
  `hand_landmarker.task` (float16, v1) into `build/generated/mlmodels/` and checks its SHA-256.
* **Firebase is optional.** Without `app/google-services.json` the google-services plugin is not
  applied, `FirebaseApp` is never initialised, and the Nearby tab says *"Nearby service not
  configured in this build"*. Everything on-device works. To enable Nearby, download
  `google-services.json` for `com.metavirtuoso.conjunction` from the Firebase console into `app/`
  (it is git-ignored) and deploy `backend/`.
* **Local backend:** `-Pconjunction.emulatorHost=10.0.2.2` points a debug build at the Firebase
  Local Emulator Suite (auth 9099, functions 5001, firestore 8080, storage 9199). Debug builds allow
  cleartext only to 10.0.2.2/localhost (`src/debug/res/xml/network_security_config.xml`).
* **Release signing** comes from `android/keystore.properties` (same keys as Sundial:
  `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it `bundleRelease` produces an
  unsigned bundle. R8 (minify + shrink resources) is on; rules in `proguard-rules.pro`.
* **"Report this reading"** (Play AI-content policy) emails `-Pconjunction.supportEmail=...` or
  falls back to the share sheet. Set the address before release.

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
| `nearby` | `NearbyApi.kt` (the whole backend contract), foreground location service, FCM service, notifications |
| `tz` | offline time-zone grid reader |
| `legal` | bundled documents, versions, SHA-256, consent ledger |

## Screenshots

There is no phone emulator image on this PC (only Wear OS), so `ScreenCaptureTest` renders each
screen with Robolectric native graphics:

```
./gradlew :app:testDebugUnitTest --tests '*ScreenCaptureTest' -Pconjunction.screenshots=$PWD/screenshots
```
