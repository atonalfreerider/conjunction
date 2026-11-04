# Info.plist keys, entitlements and capabilities (iOS)

For the iOS app agent (`ios/App/project.yml` → generated Info.plist). Purpose strings are shown in
the system permission prompts; App Review rejects vague ones (guideline 5.1.1(ii)), so each says what
is collected, why, and what happens to it. Keep the app name out of the strings so a rename touches
only `CFBundleDisplayName`.

## Usage descriptions

| Key | String |
|---|---|
| `NSLocationWhenInUseUsageDescription` | `Nearby uses your precise location to find members who also turned on Nearby and are within about 50 metres. Your location is never shown to anyone, and only your latest position is kept, for 10 minutes. Location is used only while Nearby is on.` |
| `NSCameraUsageDescription` | `The camera is used to photograph your open palm for a palm reading. The photo is analysed on your iPhone and is not uploaded.` |
| `NSPhotoLibraryUsageDescription` | **Omit** if photos are chosen with SwiftUI `PhotosPicker` / `PHPickerViewController` (they need no permission). Only if full library access is ever used: `Choose a photo of your palm for a reading, or a profile photo. Palm photos stay on your iPhone; a profile photo is uploaded only when you set it.` |
| `NSPhotoLibraryAddUsageDescription` | Only if the app offers "Save to Photos" for a reading card: `Saves your reading card to your photo library when you ask.` |

**Not included (and must not be):**

- `NSLocationAlwaysAndWhenInUseUsageDescription` / `NSLocationAlwaysUsageDescription` — the app never
  requests Always authorization (SPEC §5). Including the key invites App Review questions.
- `NSUserTrackingUsageDescription` — no tracking, no ATT.
- `NSContactsUsageDescription`, `NSMicrophoneUsageDescription`, `NSBluetoothAlwaysUsageDescription`,
  `NSLocalNetworkUsageDescription` — not used.
- `NSFaceIDUsageDescription` — only if an app lock with Face ID is added.

Notifications need no Info.plist string; request authorization (`.alert, .sound, .badge`) in
onboarding with an in-app explanation first.

## Background modes and location behaviour

```yaml
UIBackgroundModes:
  - location            # Nearby continues while the app is in the background
  - remote-notification # silent pushes from FCM (e.g. match withdrawn, chat sync)
```

The Nearby location manager, started only from the foreground when the user switches Nearby on:

- `requestWhenInUseAuthorization()` only;
- `allowsBackgroundLocationUpdates = true` *after* authorization, `showsBackgroundLocationIndicator =
  true` (the blue pill), `pausesLocationUpdatesAutomatically = false`, `activityType = .other`,
  `distanceFilter = 25`, with the ~60 s reporting interval enforced in code and by the server rate
  limit; or, on iOS 17+, `CLLocationUpdate.liveUpdates()` with a `CLBackgroundActivitySession` held
  while Nearby is on;
- stop updates when Nearby is turned off or paused.

App Review checks that background location is used for a feature the user turned on and understands
(guideline 2.5.4); the review notes explain it.

## Other keys

| Key | Value | Why |
|---|---|---|
| `ITSAppUsesNonExemptEncryption` | `false` | HTTPS/TLS via the OS and SDKs only — export-compliance exempt; skips the per-upload question |
| `CFBundleDisplayName` | `Conjunction` | The single place the display name lives on iOS (SPEC) |
| `LSApplicationQueriesSchemes` | none | |
| `UIRequiredDeviceCapabilities` | `arm64` (and optionally `gps`, `location-services`, `still-camera` — **do not** add these unless the app is unusable without them; readings work without GPS) | |
| `NSAppTransportSecurity` | default (no exceptions) | |
| `FirebaseAppDelegateProxyEnabled` | per Firebase Messaging setup | |
| `GIDClientID` + URL scheme | only if Google Sign-In is included | Google Sign-In config |

## Entitlements / capabilities (paid program required)

| Capability | Entitlement | Why |
|---|---|---|
| Push Notifications | `aps-environment = production` (development for debug) | FCM → APNs for match, twin, "It's a Conjunction!" and chat notifications |
| Sign In with Apple | `com.apple.developer.applesignin = [Default]` | Guideline 4.8 (Google Sign-In is offered) |
| Background Modes | Info.plist `UIBackgroundModes` above | |
| Associated Domains | only if universal links are added (e.g. `applinks:primitive.io`) | |

A free Personal Team (the current sideload setup) cannot sign `aps-environment` or Sign in with
Apple; a sideload build must strip both, and then Nearby notifications do not arrive — say so in any
sideload test instructions.
