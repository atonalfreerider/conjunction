# App Privacy ("nutrition label") and privacy manifest

App Store Connect → the app → **App Privacy**. Built from `SPEC.md` §5, §5b, §6 and
`legal/PRIVACY.md`. Apple's definitions
(https://developer.apple.com/app-store/app-privacy-details/):

- **Collected** = transmitted off the device and stored longer than needed to service the request in
  real time. On-device-only data is not collected.
- **Linked to the user** = associated with the user's account or identity. Everything we store is
  keyed by the account ID, so every row is **linked**.
- **Tracking** = linking with third-party data for advertising or sharing with data brokers. **None.**
  No ATT prompt, no `NSUserTrackingUsageDescription`.
- Data processed by our **service providers** (Firebase, Cloud Vision, TypeSafe) on our behalf is
  covered by our answers; it is not separately "third-party" collection for advertising.

**Privacy policy URL:** `https://primitive.io/legal/conjunction-privacy/`
**Do you or your third-party partners collect data from this app?** **Yes.**

## Data types

All rows: **Linked to you: Yes. Used for tracking: No. Purpose: App Functionality** (plus others
where listed). "Optional" data is collected only when the user uses the feature.

| Apple category → type | What exactly | Purposes |
|---|---|---|
| **Contact Info → Name** | First / display name | App Functionality |
| **Contact Info → Email Address** | Only if the user links Sign in with Apple (relay address possible) or Google | App Functionality (account) |
| **Location → Precise Location** | Lat/lon + geohash while Nearby is on, latest point only, 10-min TTL, never shown to others | App Functionality |
| **Sensitive Info → Sensitive Info** | Gender + seeking (+ looking for), which can reveal **sexual orientation** — collected only after explicit consent | App Functionality |
| **Health & Fitness → Health** | Blood type, only if entered *and* allowed for matching (declared conservatively; entertainment use) | App Functionality |
| **User Content → Photos or Videos** | Profile photo (moderated). **Palm photos are not collected.** | App Functionality |
| **User Content → Emails or Text Messages** | In-app chat messages between matches (Apple's type covers "the contents of the email or message") | App Functionality; *Other Purposes*: none. Moderation is part of providing the service safely — App Functionality. |
| **User Content → Other User Content** | Bio; report descriptions; reported AI readings | App Functionality |
| **Identifiers → User ID** | Firebase account ID; Apple / Google sign-in identifier | App Functionality |
| **Identifiers → Device ID** | FCM registration token / Firebase installation ID (push routing) | App Functionality |
| **Usage Data → Product Interaction** | Swipes, matches, unmatches, blocks, pauses (stored to make matching work and enforce the 30-day re-show rule) | App Functionality |
| **Other Data → Other Data Types** | Birth date and hour (twins), birth year / age, relationship status, looking-for, chart summary (Sun, Moon, Venus, Mars, Ascendant longitudes; Chinese year animal; day stem) | App Functionality |

**Not collected:** birth time minute and place, full chart, horoscope / palm / Eastern reading text
(unless reported), palm photos, contacts, browsing / search history, purchases, financial info,
diagnostics and crash data (unless Crashlytics is added — then add **Diagnostics → Crash Data**),
advertising data, audio, gameplay, fitness, physical address, phone number.

Apple's Foundation Models run on device and send nothing to us; the iOS app contains no Google ML
Kit.

Expected label summary: *Data Linked to You* — Contact Info, Location, Sensitive Info, Health &
Fitness, User Content, Identifiers, Usage Data, Other Data. *Data Used to Track You* — none.

## Privacy manifest (`PrivacyInfo.xcprivacy`)

The app target ships a privacy manifest that matches the label (Xcode's privacy report aggregates it
with the SDKs' manifests; the Firebase SDKs ship their own):

- `NSPrivacyTracking` = false; `NSPrivacyTrackingDomains` = empty.
- `NSPrivacyCollectedDataTypes`: one entry per row above (`NSPrivacyCollectedDataTypeName`,
  `…PreciseLocation`, `…SensitiveInfo`, `…Health`, `…PhotosorVideos`, `…EmailsOrTextMessages`,
  `…OtherUserContent`, `…UserID`, `…DeviceID`, `…ProductInteraction`, `…OtherDataTypes`,
  `…EmailAddress`), each with `Linked = true`, `Tracking = false`, purpose
  `NSPrivacyCollectedDataTypePurposeAppFunctionality`.
- `NSPrivacyAccessedAPITypes` (required-reason APIs): `NSPrivacyAccessedAPICategoryUserDefaults`
  with `CA92.1` (app's own settings); add `NSPrivacyAccessedAPICategoryFileTimestamp` (`C617.1`) if
  the app reads file dates (e.g. cleaning up old palm photos), `NSPrivacyAccessedAPICategorySystemBootTime`
  (`35F9.1`) if animations use `systemUptime`, `NSPrivacyAccessedAPICategoryDiskSpace` (`E174.1`) if it
  checks space before saving photos. List only what the code uses.

Keep `legal/PRIVACY.md`, this file, the manifest and `store/play/DATA_SAFETY.md` in step: change
all four together.
