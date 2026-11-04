# Android permissions, declarations and justifications

What the release manifest (`android/app/src/main/AndroidManifest.xml`, after merging) should
contain, why, and what Play Console asks about each. Verify the merged manifest before every
release: `./gradlew :app:processReleaseMainManifest` then read
`app/build/intermediates/merged_manifests/release/AndroidManifest.xml` (or App bundle explorer →
Permissions in Play Console).

## 1. Permissions requested

| Permission | Runtime prompt? | Used for | Asked when |
|---|---|---|---|
| `ACCESS_FINE_LOCATION` | Yes ("While using the app") | Nearby: finding opted-in members within ~50 m needs a fix much better than the ~2 km of coarse location | Only when the user turns Nearby on, after the in-app explanation screen |
| `ACCESS_COARSE_LOCATION` | Requested together with FINE (Android 12+ requires both in one request; if the user chooses "Approximate", Nearby explains that it needs precise location and stays off) | — | Same request |
| `FOREGROUND_SERVICE` | No (normal) | Running the Nearby service | — |
| `FOREGROUND_SERVICE_LOCATION` | No (normal), but **Play declaration required** (§3) | The `location`-type foreground service that keeps reporting position while Nearby is on and the app is in the background | — |
| `POST_NOTIFICATIONS` (Android 13+) | Yes | The ongoing "Nearby is on" notification (required for a foreground service), match / birthday-twin / "It's a Conjunction!" / new-message notifications | Onboarding's notifications step, or when Nearby is first turned on |
| `CAMERA` | Yes | Palm photo capture (CameraX). Never used for anything else; the photo stays on the device. | When the user opens the palm scanner |
| `INTERNET`, `ACCESS_NETWORK_STATE` | No | Firebase (Firestore, Functions, Storage, FCM); the Gemini Nano model download is done by Google Play services / AICore, not the app | — |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | No (normal) — present only if the FCM / WorkManager libraries merge them in | Library internals. The app must **not** use boot to restart Nearby (see §2) | — |

**Not requested**, and the release build must not contain them:

| Permission | Why not |
|---|---|
| `ACCESS_BACKGROUND_LOCATION` | See §2. |
| `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` | Choosing a palm or profile photo uses the system **Photo Picker** (`PickVisualMedia`), which needs no permission. Play's Photo and Video Permissions policy only allows these permissions for apps whose core purpose is broad photo access, which this is not. |
| `USE_FULL_SCREEN_INTENT` | Play restricts it (Android 14+) to calling and alarm apps. The match animation is full-screen only when the app is already open; in the background the match arrives as an ordinary high-priority notification whose tap opens the animation. |
| `com.google.android.gms.permission.AD_ID` | No ads or advertising ID. Remove it with `<uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove"/>` if a dependency merges it in. |
| `BLUETOOTH_*`, `NEARBY_WIFI_DEVICES` | Proximity is computed on the server from location; no Bluetooth or Wi-Fi scanning. |
| `READ_CONTACTS`, `RECORD_AUDIO`, `SCHEDULE_EXACT_ALARM` | Not used. |

## 2. Why `ACCESS_BACKGROUND_LOCATION` is not requested

- **It is not needed.** On Android 10+ an app with only foreground ("while in use") location keeps
  location access while it runs a **foreground service of type `location`** that was **started while
  the app was visible**. Nearby is only ever started by the user tapping the Nearby switch in the
  app, so the service starts in the foreground, posts its ongoing notification, and continues to
  receive updates when the screen is off or the user switches apps. This is exactly the
  "while-in-use" model Android documents for user-initiated, ongoing location features.
- **It keeps the user in control.** The ongoing notification ("Conjunction Nearby is on — Pause /
  Turn off") is visible for the whole time location is used. Stopping the service (from the
  notification, the app, or by the system) stops location. There is no silent background
  collection.
- **It avoids a much stricter review.** `ACCESS_BACKGROUND_LOCATION` requires Play's separate
  background-location declaration, a prominent disclosure and a video, and is approved only when
  background access is essential to the core function and the experience cannot work otherwise.
  Since the foreground service delivers the feature, Play would reject the request.
- **Consequences the app must handle:** after a reboot, or if the user swipes the app away and the
  system stops the service, Nearby cannot restart itself from the background (Android 12+ blocks
  starting a location foreground service from the background, and without background location it
  would get no fixes anyway). The app shows Nearby as paused and asks the user to reopen it. It
  must **not** start the service from `BOOT_COMPLETED`.

## 3. Foreground service declaration (Play Console)

Policy → App content → **Foreground service permissions** → Start.

| Field | Answer |
|---|---|
| Foreground service type | **Location** (`FOREGROUND_SERVICE_LOCATION`) |
| Use case | User-initiated location sharing (closest listed choice: "User-initiated location sharing"; Nearby shares the user's proximity, never coordinates, with other opted-in members) |
| Description of the feature | "Nearby is an optional feature the user turns on with a switch in the app. While it is on, a location foreground service with a persistent notification reports the phone's position to our server about once a minute so that other members who also turned Nearby on and are within about 50 metres can be matched. The user can pause or turn it off at any time from the notification or the app. Exact location is never shown to other users and only the latest position is stored, for 10 minutes." |
| Impact if the task is deferred or interrupted | "Matches with people who are physically nearby would be missed or arrive after the people have left, which defeats the feature: proximity matches are only meaningful in real time. Deferring the work (for example with WorkManager) cannot give the minute-by-minute updates the feature needs." |
| Video link | An unlisted YouTube video (≤ ~1 min) showing: opening the app → Nearby tab → reading the explanation → turning Nearby on → granting "While using the app" location and notifications → the ongoing notification appearing → leaving the app with the notification still shown → a match notification arriving → pausing Nearby from the notification. Record it on a real device with sample accounts. |

`AndroidManifest.xml` must declare the service with the type:

```xml
<service
    android:name=".nearby.NearbyLocationService"
    android:exported="false"
    android:foregroundServiceType="location" />
```

and call `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)` only after
the location permission is granted (Android 14+ throws otherwise).

## 4. Prominent disclosure (in-app, before the permission prompt)

Play's User Data policy requires a prominent in-app disclosure before collecting location that is
sent off the device. The Nearby explanation screen (copy in `legal/IN_APP_CONSENT_COPY.md`) must,
before the system prompt:

- say that **precise location** is collected and **sent to our server** while Nearby is on,
  including **while the app is closed or not in use** (the foreground service), and why (to find
  members within ~50 m);
- say that it is never shown to others and is kept only 10 minutes;
- require an affirmative tap ("Turn on Nearby"), with an equally visible "Not now".

## 5. Camera

The palm scanner uses CameraX with `CAMERA` requested at the moment the user opens the scanner,
with a one-line rationale if they denied it before ("Conjunction needs the camera to photograph your
palm. The photo stays on your phone."). The photo is analysed on the device (ML Kit / MediaPipe hand
landmarks, Gemini Nano) and is deleted after the reading unless the user saves it in the app.
No declaration form is needed for `CAMERA`.
