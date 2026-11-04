# Play Console Data safety answers

Policy → App content → **Data safety**. Built from `SPEC.md` §5, §5b, §6 and `legal/PRIVACY.md` §2,
§4, §6. Definitions are Play's (https://support.google.com/googleplay/android-developer/answer/10787469):

- **Collected** = transmitted off the device (to us or a processor). Data used only on the phone is
  not "collected".
- **Shared** = transferred to a third party. Transfers to **service providers** that process data on
  our behalf (Google Firebase / Cloud Vision, TypeSafe moderation) are *not* sharing. Data a user
  deliberately makes visible to other users as part of the feature (the match card, chat) is a
  user-initiated transfer, not sharing; it is still described in the listing and policy.
- **Ephemeral** = kept only in memory for the real-time request. Nothing below qualifies (location
  is stored for up to 10 minutes; moderation inputs are stored with the message).

Check every row against the merged release manifest and the SDK list of the shipped AAB
(`./gradlew :app:dependencies`, Play Console → App bundle explorer → SDKs) before submitting.

## Overview questions

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (Firebase SDKs use TLS only; the app has no cleartext traffic — `usesCleartextTraffic=false`) |
| Account creation methods | The app does create accounts: an **anonymous** Firebase account is created automatically when the user turns on Nearby, sets up a profile or chats, and it can optionally be linked to Google Sign-In (Sign in with Apple on iOS). Select **OAuth** (Google) and **Other** (describe: "anonymous account created automatically in the app; no username or password"). Do **not** choose "My app does not allow users to create an account". The options Play lists can change; choose by meaning. |
| Do you provide a way for users to request that their data be deleted? | **Yes** — in app (Settings → Delete account, full erasure via the `deleteAccount` function) and on the web: `https://primitive.io/conjunction/delete-account/` |
| Delete account URL | `https://primitive.io/conjunction/delete-account/` |
| Can users request that some data be deleted without deleting their account? | **Yes** — turning off Nearby deletes the stored position; removing blood type, the dating profile (withdrawing consent) or the profile photo deletes them from the server; unmatching deletes the chat. |
| Independent security review (MASA) | No (optional) |
| UPI / Families / committed to Play Families Policy | Not applicable (18+) |

## Data types

All rows: **Collected = Yes, Shared = No, Ephemeral = No**, linked to the user's account. "Optional"
means the user can use the app without it (Play: "Users can choose whether this data is
collected"). Purposes use Play's labels.

| Category → type | What exactly | Required / optional | Purposes |
|---|---|---|---|
| **Location → Precise location** | Latitude/longitude and geohash-7 (~150 m cell) every ~60 s / 25 m while Nearby is on; only the latest point is kept, TTL 10 min | Optional (Nearby is opt-in) | App functionality; Fraud prevention, security and compliance (rate limits) |
| **Location → Approximate location** | Not collected separately (the precise point is the only location sent). Answer **No** unless the build sends coarse location elsewhere. | — | — |
| **Personal info → Name** | First / display name | Optional (only with Nearby/profile) | App functionality; Account management |
| **Personal info → Email address** | Only if the user links Google Sign-In or Sign in with Apple (Apple may give a relay address) | Optional | Account management |
| **Personal info → User IDs** | Firebase account ID; Google / Apple sign-in identifier if linked | Required for Nearby/chat | App functionality; Account management; Fraud prevention, security and compliance |
| **Personal info → Sexual orientation** | Gender (I am) + Seeking + Looking for can reveal sexual orientation | Optional (dating profile, explicit consent) | App functionality (matching filter, shown on match cards) |
| **Personal info → Other info** | Gender, relationship status, looking-for, birth year / age, birth date and birth hour (twins check), chart summary (Sun, Moon, Venus, Mars, Ascendant longitudes; Chinese year animal; day stem), bio | Optional (Nearby) | App functionality |
| **Health and fitness → Health info** | Blood type (A/B/AB/O, Rh) — only if the user enters it *and* allows it for matching. Declared conservatively; it is used only for an entertainment reading and the compatibility score. | Optional | App functionality |
| **Messages → Other in-app messages** | Chat messages between matched users (text, time, read state, moderation result) | Optional | App functionality; Fraud prevention, security and compliance (moderation) |
| **Photos and videos → Photos** | Profile photo (moderated, stored in Firebase Storage). **Palm photos are not collected** (processed on device only). | Optional | App functionality |
| **App activity → App interactions** | Swipes (right/left), match/unmatch, blocks, snoozes, pause settings | Optional (Nearby) | App functionality; Fraud prevention, security and compliance |
| **App activity → Other user-generated content** | Bio, report descriptions and the reported content; reported AI readings (see `POLICY.md` §4) | Optional | App functionality; Fraud prevention, security and compliance |
| **App info and performance → Diagnostics / Other app performance data** | **Yes if Google ML Kit GenAI is in the build** (it is: `com.google.mlkit:genai-prompt`). ML Kit sends Google usage and performance diagnostics (see https://developers.google.com/ml-kit/android-data-disclosure). Add **Crash logs** too if Crashlytics is added. | Required | Analytics |
| **Device or other IDs** | Firebase Cloud Messaging token / Firebase installation ID (push); ML Kit diagnostics identifier | Required for notifications | App functionality; Analytics (ML Kit) |

**Not collected:** birth time and minute, birthplace, the full natal chart, horoscope and palm
reading text (unless the user reports one), palm photos, contacts, calendar, SMS, call logs,
microphone, files, web history, installed apps, financial info, race/ethnicity, political or
religious beliefs, advertising ID.

## How it appears on the listing (expected)

- *Data shared with third parties:* No data shared.
- *Data collected:* Location, Personal info, Health and fitness, Messages, Photos and videos, App
  activity, App info and performance, Device or other IDs.
- *Security practices:* Data is encrypted in transit; you can request that data be deleted.
