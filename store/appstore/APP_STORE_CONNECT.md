# App Store Connect answers for Conjunction

Bundle id `com.metavirtuoso.conjunction`, publisher MetaVirtuoso. Companion documents:

| File | What it covers |
|---|---|
| `fastlane/metadata/` | `fastlane deliver` metadata: name, subtitle, promotional text, description, keywords, URLs, categories, copyright, App Review notes |
| `APP_PRIVACY.md` | The App Privacy ("nutrition label") answers and the privacy manifest |
| `INFO_PLIST.md` | Info.plist usage strings, background modes, entitlements, export compliance |
| `REVIEW_NOTES.md` | App Review: how to test, demo companion, guidelines 1.2, 4.3, 4.8, 5.1.1(v), 2.5.4, 1.1.4 |

## 0. Prerequisite: the paid Apple Developer Program

There is **no App Store or TestFlight path without it.** As of the Sundial notes (2026-09-30) the
owner has only a free Apple ID (Personal Team), which can sideload to their own phone for 7 days but
cannot: create an App Store Connect record, use TestFlight, register push notifications (APNs — the
`aps-environment` entitlement is not available to Personal Teams, so **match and chat notifications
cannot work in a sideloaded build**), or enable Sign in with Apple.

- Enrol at https://developer.apple.com/programs/enroll/ (99 USD/year), with two-factor auth on the
  Apple Account to keep. **Owner only.**
- Individual vs organisation: see `~/Desktop/SUNDIAL/sundial-apple/docs/RELEASE.md` §1. For a
  **dating app**, an organisation account (seller name "MetaVirtuoso", D-U-N-S number, legal
  entity) is worth considering: the seller name and, for EU distribution, the DSA trader contact
  details are public, and dating apps attract more abuse than a clock does. Enrol once for both
  apps; Sundial and Conjunction can share the membership.
- **EU Digital Services Act trader status** must be declared before EU distribution (Business →
  your account). A trader's address, phone and email appear on the EU product page.

## 1. Create the record

Apps → + → New App

| Field | Value |
|---|---|
| Platforms | iOS |
| Name | `Conjunction: Astrology Dating` (`name.txt`, 29/30; must be unique on the App Store — reserve it early) |
| Primary language | English (U.S.) |
| Bundle ID | `com.metavirtuoso.conjunction` (register it first in Certificates, Identifiers & Profiles with **Push Notifications** and **Sign In with Apple** capabilities) |
| SKU | `CONJUNCTION-IOS` |
| User access | Full access |

## 2. App Information

- **Subtitle:** `Horoscope, palm & star matches` (30/30).
- **Category:** primary **Lifestyle**, secondary **Social Networking**. (The App Store has no Dating
  category; dating apps sit in Lifestyle or Social Networking. Lifestyle is also where astrology apps
  are, and keeps the astrology side in front.)
- **Content rights:** the app contains third-party content: the OFL fonts (bundle each font's
  `OFL.txt`) and AI-generated artwork made by the owner with the OpenAI image API (owned by the
  developer under OpenAI's terms). Answer "Yes, it contains third-party content, and I have the
  rights".
- **Age rating:** §3.
- **License agreement:** Apple's standard EULA plus our Terms (`legal/TERMS.md`), linked from the
  description and in the app. (Or paste the Terms as a custom EULA; the standard EULA is simpler.)

## 3. Age rating questionnaire (2025+ system: 4+, 9+, 13+, 16+, 18+)

App Information → Age Rating → Edit. Apple's questionnaire
(https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions)
has these sections; answers:

| Section → item | Answer | Notes |
|---|---|---|
| **In-App Controls → Parental Controls** | No | |
| **In-App Controls → Age Assurance** | **Yes**, *only if* the build verifies age beyond a self-declared birth date — i.e. integrates Apple's **Declared Age Range** API and blocks under-18 ranges from Nearby / matching / chat (recommended). Otherwise **No**. | Do not claim age assurance the app does not perform. |
| **Capabilities → Unrestricted Web Access** | No | Only our own legal pages. |
| **Capabilities → User-Generated Content** | **Yes** | Names, bios, photos and chat visible to other users. |
| **Capabilities → Social Media** | No (it is not a public feed; there is no posting to followers) — answer Yes if Apple's definition in the form covers person-to-person profiles | Read the in-form definition. |
| **Capabilities → Messaging and Chat** | **Yes** | One-to-one chat after mutual match. |
| **Capabilities → Advertising** | No | |
| **Mature Themes** (profanity, horror, alcohol/drugs) | None | The app's own content has none; user messages are moderated. |
| **Medical or Wellness → Medical or Treatment Information** | No | |
| **Medical or Wellness → Health or Wellness Topics** | No (the blood-type reading is an entertainment personality reading) | |
| **Sexuality or Nudity → Mature or Suggestive Themes** | **Infrequent/Mild** — the app is a dating app; romance and attraction are its theme | Honest answer for dating. |
| **Sexuality or Nudity → Sexual Content or Nudity / Graphic** | None | Forbidden by the guidelines and moderated out. |
| **Violence, Chance-Based Activities** | None | Compatibility % is not gambling or a contest. |

**Rating to publish: 18+.** The calculated rating from the answers above will come out lower
(user-generated content and messaging alone are allowed at 4+), but the app's Terms require 18+ and
it introduces adults to strangers nearby. In App Store Connect use **Override to Higher Age Rating →
18+** (https://developer.apple.com/help/app-store-connect/manage-app-information/set-an-app-age-rating);
Korea: 19+ ("not permitted for juveniles"). A dating app shown as suitable for under-18s would also
be inconsistent with the Google Play listing (18+, Restrict Minor Access).

The same answers for `fastlane deliver` (`app_rating_config_path`) are in
`fastlane/rating_config.json` (keys from fastlane's current template, per the parallel
`store/DATING_ADDENDUM.md` §3.2). Two deliberate choices: `ageAssurance` is **false** until the
Declared Age Range check ships (flip it to true then), and `socialMedia` is false (no public feed —
re-read Apple's in-form definition). If `deliver` rejects a value, set that item by hand.

## 4. Pricing and availability

- Free. No in-app purchases in 1.0 (so no Paid Apps Agreement, banking or tax forms needed).
- Availability: all countries where the owner is ready to handle reports and law-enforcement
  requests; consider excluding countries where dating apps or LGBTQ+ users face legal risk (the app
  stores "seeking", which can reveal sexual orientation) — a decision for the owner.
- **Untick** "iPhone and iPad Apps on Apple Silicon Macs" and "Apple Vision Pro" (Nearby needs a
  phone's GPS; Macs would match people at their desks).
- iPad: build iPhone-only for 1.0 (`TARGETED_DEVICE_FAMILY = 1`), which also removes the 13" iPad
  screenshot requirement. iPhone apps still run on iPads in compatibility mode.

## 5. Version page

- Description, keywords, promotional text, support / marketing URLs: `fastlane/metadata/en-US/`.
- Copyright: `2026 MetaVirtuoso` (`copyright.txt`).
- Screenshots (https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications,
  checked 2026-11-03): up to 10 per size, PNG/JPEG, no transparency.
  - **iPhone 6.9" / "Dynamic Island (large)"**: 1320 × 2868 portrait (also 1290 × 2796, 1260 × 2736).
  - **iPhone 6.3" / "Dynamic Island (medium)"**: 1206 × 2622 portrait (also 1179 × 2556). Apple's
    page lists this set as the required one; provide both sets so neither the required set nor the
    6.5" fallback is missing.
  - iPad 13" (2064 × 2752) only if the app ships for iPad.
  - "iPhone Duo" screenshots will be required from April 2027 for apps built with the iOS 27.1 SDK or
    later — plan for it in the first update after that date.
  - Same storyboard as Google Play (`store/play/CONSOLE.md` §3), sample data and illustrated avatars
    only. Capture them in CI like Sundial (`sundial-apple/.github/workflows/apple.yml`,
    `ScreenshotTests`) with launch arguments that freeze sample data.
- App Review Information: §6.

## 6. App Review information

- **Sign-in required:** No (anonymous account). Leave demo user/password blank
  (`review_information/demo_user.txt` is empty on purpose).
- **Contact:** first name, last name, phone (owner; not committed to the repo — add them in
  App Store Connect or as local `review_information/first_name.txt`, `last_name.txt`,
  `phone_number.txt` that are not committed), email `themetavirtuoso@gmail.com`.
- **Notes:** `review_information/notes.txt` (3168/4000). Background and reasoning: `REVIEW_NOTES.md`.
- **Attachment:** the two-device screen recording (also link it in the notes).

## 7. Export compliance

The app uses only standard encryption provided by the OS and Apple/Google SDKs over HTTPS (TLS) —
exempt under the EAR's mass-market / standard-protocol exemption, so set
`ITSAppUsesNonExemptEncryption = NO` in Info.plist (see `INFO_PLIST.md`); App Store Connect then
does not ask on each upload. If the chat ever adds its own end-to-end encryption, revisit this
(that is still usually exempt but needs the "uses encryption" answers and possibly an annual
self-classification report).

## 8. Sign in with Apple (guideline 4.8)

The spec offers **optional Google Sign-In** to upgrade the anonymous account. Guideline 4.8: an app
that uses a third-party or social login (such as Google Sign-In) to set up or authenticate the
user's primary account **must also offer an equivalent login service** that limits data to name and
email, lets users keep their email private, and does not collect interactions for advertising
without consent. **Sign in with Apple** meets all three, so the iOS app must offer Sign in with Apple
wherever Google Sign-In appears, shown at least as prominently (Apple's HIG). Requirements that
follow:

- Enable the **Sign In with Apple** capability on the App ID and the entitlement
  `com.apple.developer.applesignin = [Default]`.
- Firebase Auth: enable the Apple provider; configure the Services ID / key for token revocation.
- **Account deletion (5.1.1(v))** must also **revoke the Sign in with Apple token** through Apple's
  REST API (`/auth/revoke`) when a Sign-in-with-Apple-linked account is deleted.
- Handle the private relay email (do not require a "real" email).

If Google Sign-In were dropped from iOS (anonymous accounts only), 4.8 would not apply; keeping both
is friendlier for people who switch phones.
