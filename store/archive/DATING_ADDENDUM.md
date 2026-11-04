> **Archived 2026-11-03 — superseded.** Written for SPEC 5b with the server design (Firebase, location,
> push, server moderation). The serverless Bluetooth build (SPEC 5c) replaced it; the current answers are
> in `store/play/` and `store/appstore/`. Do not upload anything from this file.

# Store changes required by the dating and friends layer (SPEC 5b)

SPEC 5b changes Nearby from "connect as friends" into dating or friends matching: gender, seeking,
relationship status and looking-for; match cards that show a moderated photo, first name and age
**before** anyone accepts; swipe right to accept and left to pass; and full chat after a mutual right
swipe. Both stores treat this as a **dating or matchmaking app**, which brings extra obligations.

This addendum lists each store-side change as an edit to apply on top of the base store package (the
listing, Data safety, privacy label, ratings, review notes and checklist in `store/`). Wherever the
base files say "connect", "friends only", "intro message", or "no photo or name before mutual
connect", the text below replaces it. The data facts come from `legal/PRIVACY.md` (dating version,
Sections 2–4), `legal/TERMS.md` §4.2 and `legal/COMMUNITY_GUIDELINES.md`.

Policy sources were checked on 2026-11-03:

- Play Age-Restricted Content and Functionality policy, announced 2025-10-30 ([announcement](https://support.google.com/googleplay/android-developer/answer/16550159)),
  and [Requirements for apps with incidental dating or matchmaking features](https://support.google.com/googleplay/android-developer/answer/16838200)
- Play [Child Safety Standards policy](https://support.google.com/googleplay/android-developer/answer/14747720)
- Play [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936),
  [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111) and
  [foreground service declaration](https://support.google.com/googleplay/android-developer/answer/13392821)
- [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) 1.1.4, 1.2,
  4.3(b), 4.8, 5.1.1(v) and 5.1.5
- Apple [age rating values](https://developer.apple.com/help/app-store-connect/reference/age-ratings-values-and-definitions/)
  and [setting an age rating, including the override](https://developer.apple.com/help/app-store-connect/manage-app-information/set-an-app-age-rating)
- fastlane `deliver` age-rating keys ([example_rating_config.json](https://github.com/fastlane/fastlane/blob/master/deliver/assets/example_rating_config.json))

---

## 1. Listing copy (both stores)

Every length below was counted with a script: characters and UTF-8 bytes, and the larger of the two
must be within the limit.

| Field | New value | Length / limit |
|---|---|---|
| Play title | `Conjunction: Astrology Dating` | 29 / 30 |
| Play short description | `Meet compatible signs nearby. Birth chart, horoscopes & palm reading on-device.` | 79 / 80 |
| App Store name | `Conjunction: Astrology Dating` | 29 / 30 |
| App Store subtitle | `Compatible signs, close by` | 26 / 30 |
| App Store promotional text | `Turn on Nearby and Conjunction tells you when a compatible sign is within about 50 m. Both swipe right and your chat opens. Your readings never leave your phone.` | 161 / 170 |
| App Store keywords | `horoscope,zodiac,birth chart,compatibility,palm reading,chinese,synastry,nearby,love,singles,friends` | 100 / 100 |

The keywords leave out "astrology" and "dating" because they are already in the name, and Apple
indexes the name. They also contain no competitor names (Co–Star, Tinder and so on), which
guideline 2.3.7 forbids.

If the owner prefers the friendship-first title from the base package, keep it. The rest of this
addendum still applies, because the obligations come from the features, not from the title.

**Full description (Play `full_description.txt`, App Store `description.txt`).** Replace the base
package's "Conjunctions nearby" or "connect as friends" section with the block below (862
characters). Add the safety block after it, or replace the existing one (417 characters). After the
merge, run the base package's length check again; the full description must stay at or under 4,000
characters in both stores.

```
DATING AND FRIENDS, WRITTEN IN THE STARS
Turn on Nearby and choose what you are looking for: dating, friends or both. When a compatible person is within about 50 m, both of your phones light up. Your sun signs fly in from opposite corners, spiral together into a golden conjunction, and your compatibility score counts up. Birthday twins get an extra-lavish celebration.
• Swipe right to accept or left to pass. Buttons work too, and a pass is silent.
• When you both swipe right, it's a Conjunction and your chat opens.
• For dating, you set your gender, who you are seeking and your relationship status. Dating matches are only made when both people's choices fit, and people in a relationship are offered friends matches.
• Match cards show a moderated photo, first name, age, signs and compatibility. They never show your location or distance, only "nearby".
```

```
SAFETY FIRST
• Adults only (18+).
• Every chat message, name, bio and photo is checked by automated moderation before other people see it, and content the system is unsure about is held for a person to review.
• Report or block anyone from their card, profile or chat. Unmatch at any time.
• Pause Nearby for an hour or until tomorrow, or turn it off completely.
• Delete your account and data in the app at any time.
```

**Copy rules for dating, for both stores and for screenshots.**

- No sexual wording or imagery, and no "hookup", "casual", "tonight" or "DTF" (App Store 1.1.4;
  Play Sexual Content and Inappropriate Content policies).
- No promise of love, a soulmate or a guaranteed match. Keep the base package's line that astrology
  readings are for entertainment.
- Do not claim "verified" profiles. The app has a self-declared age gate, not identity verification.
- Do not mention or picture another dating app.

**Screenshots.** Add, in this order after the chart and reading shots:

1. The match animation, mid-spiral, with the score counting up.
2. A swipe card.
3. The Matches inbox.
4. A chat.
5. The safety menu (report, block, unmatch, pause).

The faces on match cards and in chats must be AI-generated portraits from `art/` or people who have
signed a model release. Never use a real user's photo or a stock photo of an identifiable person
without a licence that allows it. Names and ages must be clearly fictional, and every person shown
must look like an adult.

**Release notes.** These are only needed if the dating layer ships after a friends-only version.
Otherwise the first release has none.

---

## 2. Google Play Console

### 2.1 Category and tags (Store settings)

- **Category: Dating.** This replaces Lifestyle. The core loop is now matching nearby strangers for
  dating. Choosing Lifestyle would not avoid the obligations below, which follow from what the app
  does, and Play reviewers can reclassify apps. Declaring Dating also makes the Child Safety
  Standards declaration (2.3) an explicit requirement, and Play would expect it of this app anyway.
- **Tags:** pick from Play's list. Prefer dating, astrology, horoscope, friends and compatibility
  (or the closest tags the Console offers).

### 2.2 Restrict minor access (Age-Restricted Content and Functionality): new and mandatory

Dating and matchmaking are core functionality, not an incidental feature. Play therefore requires
the **Restrict Minor Access** feature in Play Console. The incidental-feature exemption does not
apply. Turn it on before the first closed-testing release. The owner must do this personally in the
Console.

Keep the in-app age gate as well (SPEC §7, 18+). Google's page warns that "simple age gates that
can be easily circumvented may not be considered reasonable". That warning is about apps relying on
the exemption, and it is the reason the Console restriction is used.

**Age signals (engineering).** Some US states now require age signals from the app stores
(Texas SB 2420 took effect on 2026-06-04 after an appeals court lifted the injunction; Utah and
Louisiana have similar laws). On Android, read Google Play's **Age Signals API**. On iOS, read
Apple's **Declared Age Range API** (see 3.8). Any user the store reports as under 18 is refused
before Nearby, profile or chat. The SPEC does not yet include this. Legal review is needed for the
states where the app launches.

### 2.3 Child Safety Standards: new and mandatory

The policy applies to apps in the Social or Dating categories, and also to random-chat apps. In
**App content → Child Safety Standards**:

1. **Published standards URL.** Publish a public page with the app's standards against child sexual
   abuse and exploitation (CSAE). It must name "Conjunction" (or the final name) and MetaVirtuoso,
   and it must load without errors. Source: the "Adults only" and enforcement sections of
   `legal/COMMUNITY_GUIDELINES.md`, which already cover removing minors and reporting to NCMEC.
   Publish it at a stable URL, for example
   `https://primitive.io/legal/conjunction-child-safety/`, following the Sundial pattern. Expand it
   into a dedicated page that states:
   - zero tolerance for CSAE and CSAM;
   - how users report it in the app (report from a card, profile or chat, with the reason
     "Involves a minor");
   - that accounts are removed and confirmed CSAM is reported to NCMEC (US) and the competent
     authorities elsewhere;
   - that reports involving minors are reviewed first;
   - a contact email.
2. **Certify the in-app mechanism.** The in-app report flow (card, profile and chat) qualifies,
   because users can file a report without leaving the app.
3. **Designate a CSAM point of contact.** This must be a named person with contact details. The
   owner must do this personally, and it can be the owner.

### 2.4 Target audience and content

- Target age groups: **18 and over only.** Appeals to children: **No.**
- Ads: **No ads** (unchanged).

### 2.5 Content rating (IARC questionnaire): changed answers

| Question | Base answer | Dating answer |
|---|---|---|
| Category | Social or communication | Unchanged |
| Users can interact or exchange content (chat, photos) | Yes | **Yes**: full chat between matches, and match cards with photos are shown to strangers |
| Shares the user's current location with other users | Yes (the "nearby" signal) | **Yes**. Exact location is never shown, but being told "within about 50 m" alongside a photo reveals where someone is. Answer conservatively. |
| Dating or romantic matchmaking (if the questionnaire asks) | — | **Yes** |
| Sexual content or nudity | No | **No**. Photo moderation blocks it, and the Terms forbid it. |
| Digital purchases | No | No |

Expect the interactive-element labels "Users Interact" and "Shares Location". The age band IARC
assigns does not replace 2.2: Restrict Minor Access and the 18+ target audience still apply.

### 2.6 Data safety: replace the data-type table with this

General answers: data is collected; it is encrypted in transit (**Yes**); users can request
deletion (**Yes**, both in the app and through a web link, see 2.7). No data is **shared**. Showing
a user's match card and messages to the people they match with is done at the user's direction,
which Google's definition excludes from "sharing". Firebase, TypeSafe and Cloud Vision are
processors, which are not sharing either. Nothing is processed ephemerally: every server-side item
is stored, at least for a short time.

| Data type | Collected | Required / optional | Purposes | What it is |
|---|---|---|---|---|
| Location → **Precise location** | Yes | Optional | App functionality | Nearby positions. Only the latest is kept, with a 10-minute TTL. |
| Personal info → **Name** | Yes | Optional | App functionality | First or display name on match cards |
| Personal info → **Email address** | Yes | Optional | Account management | Only if Sign in with Apple or Google is linked |
| Personal info → **User IDs** | Yes | Optional | App functionality, Account management, Fraud prevention, security and compliance | Firebase account ID |
| Personal info → **Sexual orientation** | Yes | Optional | App functionality | **New.** Gender plus seeking can reveal orientation (`legal/PRIVACY.md` §2). |
| Personal info → **Other info** | Yes | Optional | App functionality | **New or expanded.** Gender, relationship status, looking-for, birth year and age, birth date and hour, chart summary |
| Health and fitness → **Health info** | Yes | Optional | App functionality | Blood type, only if the user allows it for matching |
| Messages → **Other in-app messages** | Yes | Optional | App functionality, Fraud prevention, security and compliance | **Changed** from single intro messages to full chat, each message moderated |
| Photos and videos → **Photos** | Yes | Optional | App functionality, Fraud prevention, security and compliance | Profile photo, moderated, shown on match cards before acceptance |
| App activity → **Other user-generated content** | Yes | Optional | App functionality, Fraud prevention, security and compliance | Bio, report text, a reported reading |
| App activity → **Other actions** | Yes | Optional | App functionality | **New.** Swipes, unmatches, blocks, snoozes |
| App info and performance → **Diagnostics** | Yes | Required | Analytics (ML Kit), App functionality (server error logs) | Google ML Kit diagnostics; operational logs |
| App info and performance → **Other app performance data** | Yes | Required | Analytics | ML Kit |
| Device or other IDs | Yes | Required | App functionality (FCM push token), Analytics (ML Kit diagnostics ID) | |

Not collected: palm photos, hand measurements, birth place and minute, full chart, readings (unless
reported), contacts, advertising ID. Before submitting, confirm against the merged release manifest
that no Analytics or Crashlytics SDK is included (`legal/PRIVACY.md` §2 assumes none is).

### 2.7 Account deletion

Account creation is part of the core loop, so both of these are mandatory:

- an in-app **Delete account** option (Settings);
- a **public web page** where users can request deletion without the app.

Put the web URL in the Data safety form. The page must name the app and MetaVirtuoso as they appear
on Play, and say what is deleted and what is kept. Retention is in `legal/PRIVACY.md` §7: reports
and blocks are kept for safety, and a minimal record is kept for under-18 accounts to prevent
re-registration. The owner must host the page. Its content can come from `legal/PRIVACY.md`'s
section on rights and deletion.

### 2.8 User-generated content policy checklist (Play UGC policy)

Play reviewers check for the following, and the build must have each before the closed test:

- the user accepts the Terms and Community Guidelines before creating a profile, uploading a photo
  or chatting (onboarding, SPEC §7);
- objectionable content is defined (`legal/COMMUNITY_GUIDELINES.md`);
- moderation runs before content is shown (TypeSafe for text, Cloud Vision SafeSearch for photos,
  with uncertain content held);
- in-app reporting of users, photos, messages and AI readings (the AI-Generated Content policy also
  requires reporting of AI output in the app);
- blocking and unmatching.

### 2.9 App access (reviewer instructions)

Replace the base "Nearby testing" paragraph with the following:

> Charts, readings, Eastern reading and palm reading need no account. Nearby matching needs two
> phones within about 50 m that have both turned on Nearby. To test with one phone, use the review
> account below: once its Nearby is on, a demo partner account ("Vega", 29, Libra, both dating and
> friends) appears as a nearby match within a minute. Swipe right to see the match animation and
> open a chat; the demo partner sends one canned reply. Credentials: [REVIEW ACCOUNT EMAIL] /
> [PASSWORD]. Report, block and unmatch are in the ⋯ menu on the card and in the chat.

The **demo partner** is a backend requirement. The SPEC does not have it yet; see "Notes for
engineering" at the end. If it is not built, delete the one-phone paragraph and state that two
devices are needed. Expect slower review and a possible "unable to test" rejection in that case.

### 2.10 Foreground service (location) declaration

The use case stays "User-initiated location sharing". Re-record the required video so it shows the
current flow:

1. Turn on Nearby.
2. Read the explanation.
3. Grant While-in-use location.
4. The persistent notification appears.
5. A match card arrives.
6. Turn Nearby off from the notification.

### 2.11 Closed test (12 testers, 14 days)

The tester mix now matters, because dating matches need compatible gender and seeking settings and
people who are physically near each other. Recruit at least 15 adults, with friends-only and dating
users and people of different genders and seeking choices. Plan at least two in-person sessions
where several testers turn on Nearby together. For each tester, record which flows they tried:

- the match animation;
- the birthday-twin variant (two testers with the same birth date can test it);
- swiping right and left;
- chat;
- report, block, unmatch and pause.

The production-access questionnaire asks about engagement, and real matches are the strongest
evidence. Every tester must be 18 or over, and Restrict Minor Access (2.2) will enforce that.

---

## 3. App Store Connect

### 3.1 Category

The App Store has no Dating category. Use **Primary: Lifestyle** and **Secondary: Social
Networking**. In `deliver`, put `LIFESTYLE` in `primary_category.txt` and `SOCIAL_NETWORKING` in
`secondary_category.txt`. Major dating apps are listed in Lifestyle or Social Networking.

### 3.2 Age rating: replace the base answers

Apple's calculated rating will be lower than 18+, because user-generated content and messaging
alone are allowed at 4+ and social media raises the rating only to 13+. Use **Override to Higher
Age Rating → 18+**. Apple requires an override when a EULA (our Terms) sets an age above the
calculated rating.

For `deliver` (`app_rating_config_path`), use this file. The keys follow fastlane's current
template:

```json
{
  "alcoholTobaccoOrDrugUseOrReferences": "NONE",
  "contests": "NONE",
  "gamblingSimulated": "NONE",
  "gunsOrOtherWeapons": "NONE",
  "horrorOrFearThemes": "NONE",
  "matureOrSuggestiveThemes": "INFREQUENT_OR_MILD",
  "medicalOrTreatmentInformation": "NONE",
  "profanityOrCrudeHumor": "NONE",
  "sexualContentGraphicAndNudity": "NONE",
  "sexualContentOrNudity": "NONE",
  "socialMedia": true,
  "socialMediaAgeRestricted": false,
  "violenceCartoonOrFantasy": "NONE",
  "violenceRealistic": "NONE",
  "violenceRealisticProlongedGraphicOrSadistic": "NONE",
  "advertising": false,
  "ageAssurance": true,
  "gambling": false,
  "healthOrWellnessTopics": false,
  "lootBox": false,
  "messagingAndChat": true,
  "parentalControls": false,
  "unrestrictedWebAccess": false,
  "userGeneratedContent": true,
  "ageRatingOverrideV2": "EIGHTEEN_PLUS",
  "koreaAgeRatingOverride": "NINETEEN_PLUS",
  "kidsAgeBand": null,
  "developerAgeRatingInfoUrl": null
}
```

Why these values:

- **Mature or suggestive themes: infrequent or mild.** It is a dating app, but with no sexual
  content.
- **Age assurance: true.** There is an 18+ age gate, and the Declared Age Range API check is
  planned (3.8).
- **Korea: 19+.** Korea's youth-protection age is 19, so "not permitted for juveniles" matches the
  18+ intent.
- **Age rating info URL.** Set `developerAgeRatingInfoUrl` to the published Terms URL once it
  exists.
- **Check the strings in App Store Connect.** The 2025 questionnaire shows "Infrequent" and
  "Frequent" for some items, while fastlane's template uses `INFREQUENT_OR_MILD`. If `deliver`
  rejects a value, choose "Infrequent" for that item in App Store Connect by hand.

### 3.3 App Privacy (nutrition label): replace the base table

Tracking: **No** for every data type. There are no ads and no data brokers, so no App Tracking
Transparency prompt is needed.

| Apple data type | Linked to user | Purpose | What it is |
|---|---|---|---|
| Contact Info → Name | Yes | App Functionality | First or display name |
| Contact Info → Email Address | Yes | App Functionality | Sign in with Apple (often a relay address) or Google |
| Health & Fitness → Health | Yes | App Functionality | Blood type, optional |
| Location → Precise Location | Yes | App Functionality | Nearby. Linked because each position is stored under the account until it expires. |
| **Sensitive Info** | Yes | App Functionality | **New.** Gender plus seeking can reveal sexual orientation. |
| User Content → **Emails or Text Messages** | Yes | App Functionality | **Changed** to full chat |
| User Content → Photos or Videos | Yes | App Functionality | Profile photo |
| User Content → Other User Content | Yes | App Functionality | Bio, report text, a reported reading |
| Identifiers → User ID | Yes | App Functionality | Account ID |
| Identifiers → Device ID | Yes | App Functionality | Push token (APNs through FCM) |
| Usage Data → Product Interaction | Yes | App Functionality | **New.** Swipes, unmatches, blocks. Over-declared on purpose, to be safe. |
| Other Data → Other Data Types | Yes | App Functionality | **New or expanded.** Gender, relationship status, looking-for, age and birth year, birth date and hour, chart summary |
| Diagnostics → Other Diagnostic Data | Yes | App Functionality | Server error and rate-limit logs, which carry the account ID |

Keep `PrivacyInfo.xcprivacy` (`NSPrivacyCollectedDataTypes`) in step with this table. The iOS
team owns that file.

### 3.4 Guideline 4.3(b): saturated categories

Apple says it will reject new **dating** and **fortune-telling** apps unless they "offer a
meaningfully different or improved experience". This app falls into both. Put the following at the
top of the review notes:

> Conjunction is not a conventional dating or horoscope app. (1) Every reading — natal, daily,
> Chinese Four Pillars, blood-type and palm — is written on the device by Apple's Foundation Models
> from an accurate astronomical chart computed on the device; birth details, readings and palm
> photos never leave the phone. (2) Matching happens only between people who are physically within
> about 50 m of each other at the same moment, using When-In-Use location that is never shown to
> anyone and expires after 10 minutes — there is no browsable catalogue of profiles. (3)
> Compatibility combines Western synastry, Chinese zodiac relationships and blood-type pairing, and
> friendship matching is a first-class mode alongside dating.

### 3.5 Guidelines 1.1.4 and 1.2: dating and user-generated content

Add to the review notes, after 3.4:

> Safety and moderation (guideline 1.2): every chat message, display name and bio is screened by an
> automated moderation service before anyone else sees it; profile photos are screened by Google
> Cloud Vision SafeSearch and replaced with zodiac artwork until approved; uncertain content is held
> for human review. Users can report a match card, profile, message or AI reading and can block or
> unmatch from the card and the chat (⋯ menu). Reports are reviewed within 24 hours, and reports
> involving minors or threats first. No chat is possible until both people swipe right; nothing is
> random or anonymous. Contact: themetavirtuoso@gmail.com and the Support URL. Adults only: an
> in-app 18+ age gate at onboarding and the Terms; the app is rated 18+. The app is not for
> "hookups": the Community Guidelines prohibit sexual solicitation, and the listing contains no
> sexual content.

"Reviewed within 24 hours" is a commitment that the owner must be able to keep. Change it to match
the real moderation staffing before submitting.

### 3.6 Review notes: Nearby section (replaces the base "connect" walkthrough)

> Test account: [REVIEW ACCOUNT EMAIL] / [PASSWORD] (also in the Sign-In Information fields). It is
> set to "dating and friends". After signing in, open Nearby → turn on → allow location While Using.
> Within a minute a demo partner ("Vega", 29) appears as a nearby match: the match animation plays,
> then the swipe card. Swipe right (or tap the ✓ button) to match; the chat opens, and Vega sends
> one automatic reply. Try ⋯ → Report / Block / Unmatch on the card or in the chat. To see the
> birthday-twin variant, set the review account's birth date to 1997-06-21 in Profile → Birth data;
> the demo partner shares it. Real matching needs two devices within about 50 m that have both
> turned on Nearby.

Once the backend exists, fill the Sign-In Information fields from a dedicated review account. Do
not reuse a personal account. In `deliver`, `review_information/demo_user.txt` and
`demo_password.txt` hold these, so never commit the real values; set them through environment
variables or by hand in App Store Connect.

### 3.7 Info.plist: changes only if the profile photo can be taken with the camera

If the profile-photo picker offers "Take photo", the camera string must cover both uses:

- `NSCameraUsageDescription`: "Conjunction uses the camera to photograph your palm for a reading
  on your phone, and to take a profile photo if you choose to."

Use the system photo picker (PHPicker on iOS, the Photo Picker on Android) for choosing an existing
photo. It needs no photo-library permission or string on iOS, and no `READ_MEDIA_IMAGES` on
Android. No other new permission strings are needed. Location stays When-In-Use; do not add
"Always".

### 3.8 Declared Age Range API (iOS)

Apple added the Declared Age Range API (iOS 26.2 and later) for the state app-store age laws (Texas
from 2026-06-04). Call it during onboarding and when Nearby is turned on. If the declared range is
under 18, refuse Nearby, profile and chat, and keep only the on-device chart and readings. Because
of this, `ageAssurance` is `true` in 3.2. Engineering and legal should confirm the states, and
whether PermissionKit "significant change" consent is relevant. Since the app is 18+ only, minors
are refused rather than routed to parental consent.

### 3.9 Unchanged, but check

- Guideline 4.8: Sign in with Apple is offered whenever Google Sign-In is.
- Guideline 5.1.1(v): Delete account is in the app. For Sign in with Apple users, account deletion
  must also revoke the user's Apple tokens through Apple's REST API. This is a backend item.
- Export compliance: `ITSAppUsesNonExemptEncryption = NO` (HTTPS only). The chat is not end-to-end
  encrypted.
- Paid Apple Developer Program: still required before TestFlight and App Store.

---

## 4. Privacy policy and legal URLs (both stores)

The store listings must link to the **dating version** of the legal documents now in `legal/`
(PRIVACY.md says "for dating or friendship"). Publish these before either store submission:

- the privacy policy;
- the Terms (EULA);
- the Community Guidelines;
- the Child Safety Standards page (2.3);
- the account-deletion page (2.7).

The placeholders `[LEGAL ENTITY NAME]`, `[CONTACT EMAIL]` and the representatives must be filled
in first. Sundial's contact address is themetavirtuoso@gmail.com.

The policy describes special-category processing: orientation inferred from gender and seeking, and
blood type. Several US state laws treat that data as sensitive or as consumer health data, and the
policy itself flags a possible separate Consumer Health Data Privacy Policy. Owner and legal review
is needed before launch.

---

## 5. Steps the owner must take personally

1. Turn on **Restrict Minor Access** in Play Console (2.2).
2. Complete the **Child Safety Standards** declaration: the standards URL, the in-app mechanism
   certification and a **named CSAM point of contact** (2.3).
3. Host the child-safety, account-deletion, privacy, Terms and Guidelines pages (2.3, 2.7, 4).
4. Set the Apple age-rating override to 18+ (3.2) and answer the privacy label (3.3).
5. Commit to a real report-response time and state it in the review notes (3.5).
6. Create the dedicated review account, and the demo partner once engineering builds it (3.6).
7. Recruit a mixed group of 15 or more adult testers and run in-person Nearby sessions (2.11).
8. Run a trademark search in **Class 45** (dating services) as well as Class 9 for the final name
   (`docs/NAMES_DATING.md`).

## 6. Notes for engineering (not store files, but the store answers above depend on them)

- **Reviewer demo partner (backend).** This is a server-side flag on specific review accounts only.
  When a flagged account's presence updates, the server creates an encounter with a fixed demo user
  that has moderated art for a photo, sends both FCM notifications, swipes right automatically, and
  sends one canned chat reply. It must never match real users and must never be reachable from a
  normal account.
- **Age signals.** Add Play Age Signals on Android and Declared Age Range on iOS; refuse users the
  store reports as under 18 (2.2, 3.8).
- **Report reasons.** Include "Involves a minor" and "Threat or safety concern", and route them to
  the top of the review queue (2.3, 3.5).
- **Photo-before-accept fallback.** A photo that has not passed moderation must never appear on a
  match card; show the sun-sign art instead, as SPEC 5b says. Store screenshots and review notes
  assume this.
- **Sign in with Apple token revocation** on account deletion (3.9).
