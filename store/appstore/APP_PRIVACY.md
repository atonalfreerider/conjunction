# App Privacy ("nutrition label") and privacy manifest (serverless Bluetooth build)

App Store Connect → the app → **App Privacy**. Built from `SPEC.md` §5c and §5d and `legal/PRIVACY.md` 2.1.
Apple's definitions, re-read 2026-11-03 at <https://developer.apple.com/app-store/app-privacy-details/>:

- **"Collect"** = "transmitting data off the device in a way that allows **you and/or your
  third-party partners to access it** for a period longer than what is necessary to service the
  transmitted request in real time." Data processed only on device is not collected.
- **Optional disclosure** applies to data that is not used for tracking or advertising, is collected
  only in infrequent cases that are not part of the app's primary functionality and are optional for
  the user, and is provided by the user in a form where it is clear what is sent and the user
  affirmatively chooses to send it each time. Apple's example: "data collected in optional feedback
  forms or customer service requests".
- Apple's FAQ says apps that "offer in-app private messaging between users" should declare *Emails
  or Text Messages*. That FAQ assumes the developer's service carries or stores the messages.

## Recommended answer: **Data Not Collected** — and why

**Do you or your third-party partners collect data from this app? → No.**

1. **No server, no SDK that phones home.** The iOS app has no Firebase, analytics, crash reporting or
   ads, and no Google ML Kit (readings use Apple's on-device Foundation Models). Birth-place search
   uses Apple's own `CLGeocoder` / MapKit, which is Apple's service, not a partner of ours.
2. **Peer-to-peer data is not accessible to us or any partner.** The Nearby card goes over Bluetooth
   directly to other users' iPhones and Android phones; chats are end-to-end encrypted and stored
   only on the two phones. Apple's test is whether *the developer or its third-party partners* can
   access the data. Another user who receives your card is neither, and nothing passes through
   infrastructure we or a partner control. So the card and the chat are **not "collected"** in
   Apple's sense, even though they leave the device. The private-messaging FAQ does not apply
   because the messages never reach us. The same holds for what PRIVACY.md 2.1 adds: the optional
   Instagram username (sent only to mutual matches, inside the end-to-end encrypted chat) and the
   chart positions a match or friend keeps for the compatibility wheel (phone to phone, kept on
   their phone): neither reaches us or a partner, so the answer stays **Data Not Collected**.
3. **Report and support emails** are written in, and sent from, the user's own mail account (iOS
   mail sheet), only when the user taps Send, for an infrequent, optional purpose that is not the
   app's primary functionality, showing exactly what is sent — this meets the optional-disclosure
   criteria for customer-service requests.

**This differs from the Google Play answer on purpose:** Google defines collection as data leaving
the device at all (with an exemption only for end-to-end encryption), Apple as data reaching the
developer or its partners. See `store/play/DATA_SAFETY.md`.

**Conservative alternative (owner's choice).** If the owner prefers a label that mirrors Play, or if
App Review questions "Data Not Collected" for a dating app, declare — all *Not linked to you*, *Not
used for tracking*, purpose *App Functionality*: Contact Info → Name; Sensitive Info; Health & Fitness
→ Health (only if blood type is sent); User Content → Photos or Videos; Other Data → Other Data Types
(age, birth date, signs, relationship status, looking-for); and, for report emails, User Content →
Customer Support. ("Not linked" because there is no account or identity on our side.) Do not declare
Emails or Text Messages: they are end-to-end encrypted and never accessible to us.

**If anything changes** — a report endpoint (e.g. a Google Form, see `store/play/POLICY.md` §4), an
analytics or crash SDK, any server — the answer becomes "Yes" for the data involved. Change this file,
the privacy manifest, `legal/PRIVACY.md` and `store/play/DATA_SAFETY.md` together.

**Privacy policy URL:** `https://primitive.io/legal/conjunction-privacy/` (still required with Data
Not Collected).

**Tracking:** none. No ATT prompt, no `NSUserTrackingUsageDescription`.

Expected label: **Data Not Collected** — "The developer does not collect any data from this app."

## Privacy manifest (`PrivacyInfo.xcprivacy`)

The app target ships a privacy manifest that matches the label:

- `NSPrivacyTracking` = false; `NSPrivacyTrackingDomains` = empty.
- `NSPrivacyCollectedDataTypes` = **empty array** (matching Data Not Collected). If the conservative
  alternative is chosen, add one entry per declared type with `Linked = false`, `Tracking = false`,
  purpose `NSPrivacyCollectedDataTypePurposeAppFunctionality`.
- `NSPrivacyAccessedAPITypes` (required-reason APIs): `NSPrivacyAccessedAPICategoryUserDefaults` with
  `CA92.1` (app's own settings); add `NSPrivacyAccessedAPICategoryFileTimestamp` (`C617.1`) if the app
  reads file dates (e.g. expiring match cards or cleaning up palm photos),
  `NSPrivacyAccessedAPICategorySystemBootTime` (`35F9.1`) if it uses `systemUptime` /
  `mach_absolute_time` (common in BLE timing and animation code), `NSPrivacyAccessedAPICategoryDiskSpace`
  (`E174.1`) if it checks free space. List only what the code uses; check Xcode's privacy report on the
  CI archive.

Keep `legal/PRIVACY.md`, this file, the manifest and `store/play/DATA_SAFETY.md` in step.
