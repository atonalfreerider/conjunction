# Info.plist keys, background modes and capabilities (iOS, serverless Bluetooth build)

For the iOS app agent (`ios/App/project.yml` → generated Info.plist). Purpose strings appear in the
system permission prompts; App Review rejects vague ones (guideline 5.1.1(ii)), so each says what is
used, why, and what happens to the data. Keep the app name out of the strings where possible so a
rename touches only `CFBundleDisplayName`. The strings must match `legal/IN_APP_CONSENT_COPY.md` §9.

## Usage descriptions

| Key | String |
|---|---|
| `NSBluetoothAlwaysUsageDescription` | `Conjunction uses Bluetooth only while Nearby is on, to find people close by who also turned Nearby on and to exchange match cards and encrypted messages directly with their phones. It doesn't use your location.` |
| `NSCameraUsageDescription` | `Conjunction uses the camera to photograph your palm for a reading on this phone, and to take a profile photo if you choose.` |
| `NSPhotoLibraryUsageDescription` | **Omit** if photos are chosen with SwiftUI `PhotosPicker` / `PHPickerViewController` (they need no permission — recommended). Only if full library access is ever used: `Choose a palm photo for a reading or a profile photo.` |
| `NSPhotoLibraryAddUsageDescription` | Only if the app offers "Save to Photos" for a reading card: `Saves your reading card to your photo library when you ask.` |

**Notifications** need no Info.plist string. Request authorization (`.alert, .sound, .badge`) in
onboarding after the in-app explanation (`perm_notifications_*`). All notifications are **local**
(`UNUserNotificationCenter`), scheduled by the app when a match or message arrives over Bluetooth or
for the daily horoscope.

**Not included (and must not be):**

- `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription`,
  `NSLocationAlwaysUsageDescription` — **no location** in the serverless build. Remove them from
  `project.yml` if an earlier version added them. Birth-place search with `CLGeocoder` /
  `MKLocalSearch` (forward geocoding of typed text) needs no location permission.
- `NSBluetoothPeripheralUsageDescription` — deprecated since iOS 13; `NSBluetoothAlwaysUsageDescription`
  covers both central and peripheral roles.
- `NSUserTrackingUsageDescription` — no tracking, no ATT.
- `NSLocalNetworkUsageDescription`, `NSContactsUsageDescription`, `NSMicrophoneUsageDescription`,
  `NSFaceIDUsageDescription` — not used (Face ID only if an app lock is added).

## Background modes and Bluetooth behaviour

```yaml
UIBackgroundModes:
  - bluetooth-central      # keep scanning for / talking to peers in the background
  - bluetooth-peripheral   # keep advertising and serving the GATT service in the background
```

No `location` and no `remote-notification` mode (no push).

- Create `CBCentralManager` and `CBPeripheralManager` with restore identifiers
  (`CBCentralManagerOptionRestoreIdentifierKey`, `CBPeripheralManagerOptionRestoreIdentifierKey`) and
  implement `willRestoreState`, so iOS can relaunch the app for Bluetooth events after it was
  suspended or terminated by the system (not after the user force-quits it).
- Scan **with the service UUID filter** (`scanForPeripherals(withServices: [serviceUUID])`) — iOS
  delivers no background scan results otherwise, and coalesces duplicates.
- **Documented limitation (SPEC §5c):** when the iPhone app is in the background, iOS moves its
  advertised service UUID into Apple's "overflow area", which **only other iPhones scanning for that
  UUID can see**; Android phones cannot. So two phones find each other when either one is Android and
  the iPhone is in the foreground, or when both are iPhones. The app explains this with
  `nearby_ios_background_note`, and the review notes say so.
- Start Bluetooth only when the user turns Nearby on; stop scanning and advertising when Nearby is
  paused or off, so the system Bluetooth indicator and battery use match the switch.

App Review checks that background modes serve a feature the user turned on and understands
(guideline 2.5.4); the review notes explain it.

## Other keys

| Key | Value | Why |
|---|---|---|
| `ITSAppUsesNonExemptEncryption` | **`true`** (YES) — see `APP_STORE_CONNECT.md` §7 | The chat uses its own end-to-end encryption (ECDH P-256, HKDF, AES-GCM). If it is implemented only with Apple's **CryptoKit**, App Store Connect's category "encryption limited to that within the Apple operating system" needs **no documentation upload**. Setting `false` would claim the app uses no non-exempt encryption, which is doubtful for E2E messaging. Confirm with counsel. |
| `CFBundleDisplayName` | `Conjunction` | The single place the display name lives on iOS (SPEC) |
| `UIRequiredDeviceCapabilities` | `arm64` only. Do **not** add `bluetooth-le` (readings work without it; every supported iPhone has BLE anyway). | |
| `NSAppTransportSecurity` | default (no exceptions) | The app makes no network calls of its own |
| `LSApplicationQueriesSchemes` | `mailto` only if the app checks for a mail app before falling back from `MFMailComposeViewController` | Report emails |

## Entitlements / capabilities

| Capability | Needed? |
|---|---|
| Push Notifications (`aps-environment`) | **No** — local notifications only. Remove it from the App ID and entitlements. |
| Sign In with Apple | **No** — there is no sign-in (guideline 4.8 does not apply). |
| Background Modes | Yes, via `UIBackgroundModes` above (Info.plist; no entitlement file entry) |
| Keychain Sharing / App Groups | No (the identity key lives in the app's own Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) |
| Associated Domains | Only if universal links are added |

Because nothing above needs a paid-program-only entitlement, a **free Personal Team sideload build**
(the owner's current iOS setup) can run the full Nearby feature, including notifications. Bluetooth
background modes need no entitlement. The 7-day sideload expiry still applies.
