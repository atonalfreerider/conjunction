# Android permissions, declarations and justifications (serverless Bluetooth build)

What the release manifest (`android/app/src/main/AndroidManifest.xml`, after merging) should contain
for SPEC §5c, why, and what Play Console asks about each. Sources re-read on 2026-11-03:
Bluetooth permissions <https://developer.android.com/develop/connectivity/bluetooth/bt-permissions>,
foreground-service types <https://developer.android.com/develop/background-work/services/fgs/service-types>,
Play FGS requirements <https://support.google.com/googleplay/android-developer/answer/13392821>.

Verify the merged manifest before every release: `./gradlew :app:processReleaseMainManifest`, then
read `app/build/intermediates/merged_manifests/release/AndroidManifest.xml` (or App bundle explorer →
Permissions in Play Console).

> **Status 2026-11-03 (working tree):** `android/app/src/main/AndroidManifest.xml` now matches this
> file. It has no location permissions, no `location`-type service and no Firebase / C2DM entries;
> Firebase and `play-services-location` are gone from `build.gradle.kts`. Two items differ from the
> first draft of this file: `RECEIVE_BOOT_COMPLETED` is kept, for the optional 08:00 reading reminder
> only (`ai/DailyReminderReceiver`; it never starts Nearby), and the legacy `BLUETOOTH` /
> `BLUETOOTH_ADMIN` permissions are declared with `maxSdkVersion="30"` (Nearby itself is hidden below
> Android 12, see §1.1). The merged manifest also gets `INTERNET` and `ACCESS_NETWORK_STATE` from
> ML Kit. Re-check the merged manifest before each release anyway.

## 1. Permissions requested

| Permission | Runtime prompt? | Used for | Asked when |
|---|---|---|---|
| `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` | Yes — "Nearby devices" group (Android 12+) | Finding other phones advertising the Conjunction service UUID | When the user turns Nearby on, after the in-app explanation (`legal/IN_APP_CONSENT_COPY.md` §5, §9) |
| `BLUETOOTH_ADVERTISE` | Yes — same "Nearby devices" prompt | Advertising the service UUID so other phones can find this one | Same request |
| `BLUETOOTH_CONNECT` | Yes — same prompt | GATT client and server: connecting to peers, the INBOX / OUTBOX characteristics, MTU | Same request |
| `FOREGROUND_SERVICE` | No (normal) | Running the Nearby service | — |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | No (normal), but **Play declaration with video required** (§3) | The `connectedDevice` foreground service that keeps scanning, advertising and serving GATT while Nearby is on | — |
| `POST_NOTIFICATIONS` (Android 13+) | Yes | The ongoing "Nearby is on" notification (required for the foreground service) and the local match / birthday-twin / "It's a Conjunction!" / new-message notifications | Onboarding's notifications step, or when Nearby is first turned on |
| `CAMERA` | Yes | Palm photo capture (CameraX) and, optionally, taking a profile photo. The palm photo stays on the device. | When the user opens the palm scanner |
| `INTERNET`, `ACCESS_NETWORK_STATE` | No | **Not used by the app's own code.** Kept only if ML Kit / AICore (Gemini Nano availability, model download through Play services, diagnostics) or the platform geocoder need them in the merged manifest. If they can be removed without breaking ML Kit, remove them. | — |
| `RECEIVE_BOOT_COMPLETED` | No (normal) | Re-arms the optional daily "today's reading is ready" alarm after a reboot or app update (`ai/DailyReminderReceiver`). Nearby is **never** started from boot (§2). | — |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (`maxSdkVersion="30"`) | No (normal) | Legacy declarations for Android 11 and lower; Nearby is hidden there (§1.1), so they are not used for scanning. | — |
| `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>` | — | Lets the app install on phones without BLE; Nearby is hidden there. `required="false"` because charts and readings work without Bluetooth. | — |

Also declare the legacy permissions only for old Android versions, as Google recommends:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

`neverForLocation` is accurate: the app never derives location from scan results; it only reads RSSI
to decide "close by". Google notes that with this flag "some BLE beacons are filtered from the scan
results"; Conjunction's own advertisements (a 128-bit service UUID, not iBeacon/Eddystone) are not
beacons, but confirm on real devices with the laptop test peer.

### 1.1 Android 8–11 (minSdk 26) and "no location permission at all"

On **Android 11 and lower**, a BLE scan requires `ACCESS_FINE_LOCATION` (granted at runtime) and
location services switched on, and on Android 10–11 a scan from a background service also needs
`ACCESS_BACKGROUND_LOCATION`. SPEC §5c says "no location permission at all", and the app's minSdk is
26. The two cannot both hold if Nearby must work on Android 8–11. Options (owner / Android agent
decision):

1. **Recommended: Nearby only on Android 12+ (API 31).** Keep minSdk 26 for charts and readings, declare
   no location permission at all, and on API ≤ 30 hide Nearby with `nearby_android_old_version`
   ("Nearby needs Android 12 or later"). Simplest store story; no location declaration; tiny
   audience loss (Android 12+ is the large majority of active devices — check the current
   distribution in Android Studio before deciding).
2. Raise minSdk to 31 for the whole app.
3. Declare `ACCESS_FINE_LOCATION` with `android:maxSdkVersion="30"` (and background location for
   API 29–30). This brings back the location prominent disclosure, the background-location
   declaration and video, and contradicts the "no location" privacy promise on those versions. Not
   recommended.

The legal and store texts assume option 1 or 2.

**Not requested**, and the release build must not contain them:

| Permission | Why not |
|---|---|
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION` | No location is used (§1.1). Check that no library merges them in (`play-services-location` must be removed). |
| `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` | Photos are chosen with the system **Photo Picker** (`PickVisualMedia`), which needs no permission. Play's Photo and Video Permissions policy only allows these for apps whose core purpose is broad photo access. |
| `USE_FULL_SCREEN_INTENT` | Play restricts it to calling and alarm apps. The match animation is full-screen only when the app is already open; in the background the match is an ordinary high-priority local notification. |

| `com.google.android.gms.permission.AD_ID` | No ads or advertising ID. Remove with `tools:node="remove"` if a dependency merges it in. |
| `NEARBY_WIFI_DEVICES`, `CHANGE_WIFI_STATE`, `NFC`, `UWB_RANGING` | Not used; Bluetooth LE only. |
| `READ_CONTACTS`, `RECORD_AUDIO`, `SCHEDULE_EXACT_ALARM` | Not used. |
| Firebase Messaging / C2DM permissions (`com.google.android.c2dm.permission.RECEIVE`) | No push. Firebase is removed. |

## 2. Foreground service and background behaviour

```xml
<service
    android:name=".nearby.NearbyBluetoothService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

- `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` only after
  at least one of `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` is granted (the
  runtime prerequisite for this type; Android 14+ throws otherwise).
- The service is started **only** by the user tapping the Nearby switch (or "Resume" in the app /
  notification) while the app is visible. Android 12+ blocks starting a foreground service from the
  background in most cases anyway.
- After a reboot, or if the system kills the service, the app shows Nearby as paused and asks the
  user to turn it back on. It does not restart Nearby from `BOOT_COMPLETED`. (Android 15's boot
  restrictions do not list `connectedDevice`, so this is a product and policy choice, not a platform
  limit: the Play video must show a user-initiated start, and the persistent notification keeps it
  perceptible.)
- The ongoing notification ("Nearby is on — Using Bluetooth to find matches close by. Tap to pause.")
  has Pause and Turn off actions.

## 3. Foreground service declaration (Play Console)

Policy → App content → **Foreground service permissions**. Apps targeting Android 14+ must declare
every FGS type; for **connected device** Play asks for a description, the impact if deferred or
interrupted, a **video**, and a use case.

| Field | Answer |
|---|---|
| Foreground service type | **Connected device** (`FOREGROUND_SERVICE_CONNECTED_DEVICE`) |
| Use case | **"Continuous data transfer to an external device"** — Play's listed use case for interactions with external devices over Bluetooth. The external devices are other people's phones running the app, found and connected over Bluetooth LE. If the reviewer disputes that a phone is an "external device", the fallback is `specialUse` with a free-text subtype; do not switch without a rejection. |
| Description of the feature | "Nearby is an optional feature the user turns on with a switch in the app. While it is on, a connected-device foreground service with a persistent notification keeps a Bluetooth Low Energy scanner, advertiser and GATT server running so that the phone can find other phones running the app nearby, connect to them directly (there is no server), exchange a match card, and deliver end-to-end encrypted chat messages to friends who come into range. The user can pause or turn it off at any time from the notification or the app. The app does not use location." |
| Impact if the task is deferred or interrupted | "Matches between people who are physically near each other would be missed, because the phones must be scanning and advertising while the people are in Bluetooth range — often only minutes. Messages queued for a friend would not be delivered while they pass by. Deferring the work (WorkManager, JobScheduler) cannot keep a GATT server and scanner running continuously." |
| Video link | Unlisted YouTube video, about 1 minute, on a real device: open the app → Nearby → read the explanation → tick and tap "Turn on Nearby" → grant **Nearby devices** and notifications → the ongoing notification appears → leave the app, notification still shown → a second phone (or the laptop test peer, `tools/p2p-sim/`) comes in range → a match notification arrives → open it, swipe → pause Nearby from the notification. Play's guidance for this type asks that the video show connecting to the external device "directly inside the app", so show the match being made and the card arriving inside the app. |

The 2026 Play guidance also points `connectedDevice` users to the Companion Device Manager. CDM is for
pairing a user's own accessory and needs a system pairing dialog per device, so it does not fit
discovering strangers' phones; say so if asked.

## 4. Prominent disclosure (in-app, before the permission prompt)

Play's User Data policy requires a prominent in-app disclosure when personal and sensitive data is
transmitted off the device in a way the user might not expect. The Nearby explanation screen
(`legal/IN_APP_CONSENT_COPY.md` §5) must, before the "Nearby devices" prompt and before Nearby first
turns on:

- say that Nearby uses **Bluetooth**, including **while the app is closed** (the foreground service);
- list **what is sent to every nearby phone with Nearby on** (gender and seeking, relationship
  status, looking-for, chart positions, birth date) and **what matches also receive before anyone
  accepts** (first name, bio line, photo), and that none of it is encrypted before a mutual match;
- say that matches see the card, that chats are end-to-end encrypted, and that other phones keep what
  they receive;
- say that location is not used;
- require an affirmative tap ("Turn on Nearby" after ticking the checkbox), with an equally visible
  "Not now".

## 5. Camera

The palm scanner uses CameraX with `CAMERA` requested when the user opens the scanner, with a
one-line rationale if they denied it before (`perm_camera_*`). The photo is analysed on the device
(MediaPipe hand landmarks, Gemini Nano) and deleted after the reading unless saved. No Play
declaration is needed for `CAMERA`.
