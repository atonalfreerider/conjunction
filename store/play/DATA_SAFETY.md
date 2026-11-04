# Play Console Data safety answers (serverless Bluetooth build)

Policy → App content → **Data safety**. Built from `SPEC.md` §5c and §5d and `legal/PRIVACY.md` 2.1.
Definitions are Google's, re-read on 2026-11-03 at
<https://support.google.com/googleplay/android-developer/answer/10787469>:

- **Collected** = "transmitting data from your app off a user's device". The definition is about the
  data leaving the device, **not** about whether it reaches the developer. It explicitly includes data
  sent off the device by libraries and SDKs in the app.
- **Collection exemptions:** data processed only on the device; data sent off the device that is
  "unreadable by you or anyone other than the sender and recipient as a result of **end-to-end
  encryption**"; ephemeral processing (in memory only, still declared on the form but not shown).
- **Shared** = transferring collected data to a third party. **Sharing exemptions** include transfers
  "based on a specific **user-initiated action**, where the user reasonably expects the data to be
  shared", and transfers made after a **prominent in-app disclosure and consent**; also service
  providers and legal purposes.
- Google's page does not mention peer-to-peer transfers between users' phones.

## The reasoning: why the answer is not "No data collected"

It is tempting to answer "No data collected" because nothing reaches MetaVirtuoso. Two things make
that answer inaccurate under Google's definitions:

1. **Google ML Kit (GenAI Prompt API, `com.google.mlkit:genai-prompt`) sends diagnostics to
   Google.** Google's ML Kit disclosure (<https://developers.google.com/ml-kit/android-data-disclosure>,
   read 2026-11-03) lists, for GenAI features: device information (manufacturer, model, OS version,
   build), performance metrics, API configuration, feature input/output **size**, feature version,
   event type, error codes, configured languages, and "user, device or other identifiers" plus
   per-installation identifiers, all "used for diagnostics and usage analytics", over HTTPS, not
   transferred to third parties. SDK transmissions count as collection. This alone requires
   "Yes, collects data".
2. **Nearby data is transmitted off the device, and it is not encrypted before a mutual match**
   (`docs/P2P_PROTOCOL.md` §8.6). The `hello` (gender, seeking, relationship status, looking-for,
   planet longitudes, Chinese animal and day stem, birth date and hour, optional blood type) goes over
   Bluetooth to every nearby phone with Nearby on; the `card` (first name, bio line, photo) to phones
   where the match filter passed. Only chat after a mutual match is end-to-end encrypted. Google's only exemption for
   off-device data is end-to-end encryption, so on a literal reading the card data is "collected".
   (It is not "shared": it is a transfer the user starts by turning on Nearby, after a prominent
   disclosure and consent — the `nearby_*` screen in `legal/IN_APP_CONSENT_COPY.md` §5.)

**Chat messages** are end-to-end encrypted between the two phones → exempt, not declared. The
**identity public key** is exchanged in the clear but is a random key, not user data in Play's
categories; it is not declared (re-check if Google adds guidance). **Report emails** are written and
sent by the user from their own email app; the app transmits nothing itself → not declared (the
privacy policy covers them).

**Recommendation (owner decision): declare the Nearby card fields as collected (optional, App
functionality, not shared) plus the ML Kit diagnostics.** Under-declaring is what Play enforces
against; over-declaring a peer-to-peer transfer costs only a longer label. Explain the design in the
privacy policy (done) and, if the listing looks alarming, in the description ("no servers: data goes
phone to phone"). If the protocol later **encrypts the pre-match exchange** (e.g. an ephemeral
ECDH session per link before the hello), the Nearby data may become eligible for the
end-to-end-encryption exemption and these rows could be removed — encryption with keys negotiated
with an unauthenticated stranger arguably meets the literal "unreadable by anyone other than the
sender and recipient" test, but re-read Google's wording at that time.

The alternative reading — "data that never reaches the developer is not collected" — has no support
in Google's text, so this file does not use it.

Check every row against the merged release manifest and the SDK list of the shipped AAB
(`./gradlew :app:dependencies`, Play Console → App bundle explorer → SDKs) before submitting. Status
2026-11-03 (checked in the working tree): Firebase, FCM and `play-services-location` are gone from
`android/app/build.gradle.kts` and the merged release manifest; the remaining SDKs are AndroidX,
CameraX, ML Kit GenAI Prompt (`genai-prompt`) and MediaPipe Tasks Vision. ML Kit merges in
`INTERNET`, `ACCESS_NETWORK_STATE` and Google's `datatransport` scheduler, which is how its
diagnostics leave the phone (the rows below). The app's own code opens no network connection. If any
other SDK that transmits data is added, add it here.

## Overview questions

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (see above: ML Kit diagnostics; the pre-match Nearby card). |
| Is all of the user data collected by your app encrypted in transit? | **No.** ML Kit uses HTTPS, but the protocol sends `hello`, `card`, `photo` and `swipe` unencrypted over an unpaired BLE link (`docs/P2P_PROTOCOL.md` §2, §8.6). Answer **Yes** only if pre-match encryption is added. |
| Account creation | **"My app does not allow users to create an account."** There are no accounts: the profile and the random identity key live only on the phone. |
| Do you provide a way for users to request that their data be deleted? | **No** is the accurate answer for collected data: the developer holds none, ML Kit diagnostics are held by Google, and cards already received are on other users' phones, which no one can reach. Users can delete everything on their own phone (Settings → Delete all my data, or clear storage / uninstall); the privacy policy §11 explains. If Google's form offers "data is deleted automatically / not retained by the developer" wording, use it. |
| Delete account URL | Not applicable (no accounts). |
| Independent security review (MASA) | No (optional) |
| Families policy | Not applicable (18+) |

## Data types

"Optional" = the user can use the app without it (Nearby is opt-in; Play: "Users can choose whether
this data is collected"). **Shared = No** for every row. **Ephemeral = No** (receiving phones keep
match cards up to 24 h and friends' cards indefinitely).

| Category → type | What exactly | Required / optional | Purposes |
|---|---|---|---|
| **Personal info → Name** | First name in the `card` (sent only to matches) | Optional (Nearby) | App functionality |
| **Personal info → Sexual orientation** | Gender + seeking in the `hello`, sent to **every** nearby phone with Nearby on (not only matches), can reveal it; sent only with `CONSENT_ORIENTATION_DATA` | Optional | App functionality |
| **Personal info → Other info** | Birth date and birth hour (age, twins check), gender, relationship status, looking-for, chart summary (Sun, Moon, Venus, Mars, Ascendant longitudes; Chinese year animal; day stem), first line of bio, temporary ID | Optional | App functionality |
| **Health and fitness → Health info** | Blood type (A/B/AB/O, never Rh) in the `hello` to every nearby phone, only if the user switches on "Use in Nearby matching" (Profile › Blood type) (declared conservatively; entertainment use) | Optional | App functionality |
| **Photos and videos → Photos** | Profile photo (≤ 640 px), sent only to matches. **Palm photos are not collected** (on-device only). | Optional | App functionality |
| **App info and performance → Diagnostics** and **Other app performance data** | ML Kit GenAI usage/performance diagnostics to Google | Required (whenever on-device AI runs) | Analytics |
| **Device or other IDs** | ML Kit per-installation / device identifiers | Required | Analytics |

**Not collected:** location (the app has no location permission), messages (end-to-end encrypted →
exempt), Instagram username (optional; sent only to mutual matches inside the end-to-end encrypted
chat → exempt), email address, user IDs (no accounts), app interactions (swipes, blocks stay on the phone;
the swipe answer itself goes to one peer — declare under "App activity → App interactions" only if
Google's reviewers ask; it reveals no more than the match), birth time minute and place, full chart,
readings, palm photos, contacts, calendar, SMS, call logs, microphone, files, web history, installed
apps, financial info, advertising ID.

## How it should appear on the listing

- *Data shared with third parties:* No data shared.
- *Data collected:* Personal info, Health and fitness, Photos and videos, App info and performance,
  Device or other IDs (all optional except the ML Kit rows).
- *Security practices:* no "encrypted in transit" badge (pre-match Bluetooth data is not
  encrypted) and no "you can request that data be deleted" badge.

Keep this file, `legal/PRIVACY.md`, `store/appstore/APP_PRIVACY.md` and the iOS privacy manifest in
step.
