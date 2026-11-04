# Google Play policy considerations

Conjunction combines four things Play reviews closely: a **dating** app, **user-generated content**
between strangers, **precise location** use, and **AI-generated content**. This is what each policy
requires and how the app meets it. Policy pages change; re-read the linked pages before each
submission.

## 1. Dating and matchmaking (Age-Restricted Content and Functionality)

Policy: apps whose core functionality is dating or matchmaking **must use Restrict Minor Access**
(https://support.google.com/googleplay/android-developer/answer/16838200; how-to:
https://support.google.com/googleplay/android-developer/answer/9867159).

- Matching people for dating is core here (§5b of the spec), so the incidental-dating exemption does
  not apply. **Target audience: 18 and over only, and tick "Restrict minor access"** in Policy → App
  content → Target audience and content. Users Google determines to be under 18 then cannot find or
  install the app.
- Play also expects the app's own age gating to be "robust and effective"; a simple, easily
  bypassed age gate "may not be considered reasonable". The app's layers:
  1. Restrict Minor Access (Play's own age signal).
  2. Onboarding age gate: a full birth date (not a yes/no button), which is also the chart's birth
     date and is uploaded with the chart summary, so the server enforces 18+ on every
     `updatePresence`, `swipe` and `sendMessage`; a user cannot change their birth date to an
     adult one without the server noticing a change (lock birth date after first Nearby use, or
     require support to change it).
  3. Reports of suspected minors are a dedicated report reason with priority review; confirmed
     minors are banned and their data deleted.
  4. **Recommended before launch:** integrate the **Play Age Signals API** (beta)
     (https://support.google.com/googleplay/android-developer/answer/16569691), which returns
     verified/supervised status and an age range for users in US states with app-store
     age-verification laws (Texas SB 2420 is in effect). Treat "supervised" or "under 18" as a hard
     block for Nearby, matching and chat. The equivalent on iOS is Apple's Declared Age Range API.
- **Dating-app expectations** Play and users look for, all in the spec: no messaging before a mutual
  match; block, unmatch and report on the card and in the chat; safety tips
  (`legal/SAFETY_TIPS.md`) linked from the match screen and before the first chat; no exact
  location; photos moderated before anyone sees them; ability to pause matching.
- **Listing:** say "18+" in the description (done) and do not use suggestive imagery or wording in
  screenshots (illustrated avatars, not photos of real people).

## 2. User-generated content (UGC policy)

Policy: https://support.google.com/googleplay/android-developer/answer/9876937 (User Generated
Content). Apps with UGC must:

| Requirement | How Conjunction meets it |
|---|---|
| Terms / policies that define objectionable content and forbid it, accepted before UGC is created | `legal/TERMS.md` and `legal/COMMUNITY_GUIDELINES.md`, accepted (versioned, stored) during onboarding before any profile, match or chat |
| Robust, effective, ongoing moderation | TypeSafe moderation of every display name, bio and chat message **before delivery** (harassment, sexual content, solicitation/scam, contact info / off-platform lure, hate); Cloud Vision SafeSearch on profile photos, not shown until approved; uncertain cases held for human review |
| In-app reporting of users and content | Report on every match card, profile and chat message; reports routed by urgency |
| In-app blocking of users | Block on every card, profile and chat; blocked pairs are never matched again |
| Acting on reports and repeat offenders | Human review queue; bans; ban evasion prevented by the hashed sign-in identifier (`legal/PRIVACY.md` §8) |

## 3. Child Safety Standards (Social and Dating apps)

Policy: https://support.google.com/googleplay/android-developer/answer/14747720. Required for apps
in the Social or Dating categories:

1. **Published standards** that explicitly prohibit child sexual abuse and exploitation (CSAE):
   `legal/COMMUNITY_GUIDELINES.md` does; publish it at a public URL with an anchor for the
   child-safety section, and enter that URL in the declaration.
2. **In-app feedback mechanism** for users to report concerns: the Report flow includes "Child
   safety / possible minor".
3. **Addressing CSAM:** take appropriate action on actual knowledge (remove, ban, preserve and report
   to NCMEC's CyberTipline in the US / the relevant authority elsewhere) — described in the community
   guidelines; the owner must be able to do it in practice (`backend` admin tooling).
4. **Compliance with child-safety laws.**
5. **Child safety point of contact** in Play Console: a named person who can speak to the app's
   CSAM prevention practices (the owner) and `themetavirtuoso@gmail.com`.

## 4. AI-generated content

Policy: https://support.google.com/googleplay/android-developer/answer/13985936 (AI-Generated
Content). Horoscopes, palm readings, Eastern readings and match blurbs are produced by Gemini Nano
on the device. Play requires:

- Generative output must not be harmful (the prompts carry entertainment-only guardrails and the
  app cleans up the answer, as in Sundial's `HoroscopeGenerator.kt`).
- **In-app reporting or flagging of offensive AI-generated content without leaving the app.** Each
  reading card needs a "Report this reading" action that sends the reading text, the reason, the app
  version and date (not birth data) to the backend `report` function. Sundial's Play listing was
  held to this rule. **Make sure the Android and iOS app agents implement it** — it is easy to
  forget because readings are local.
- Declare AI-generated content as Yes in the content-rating questionnaire.

## 5. Location and personal data

- **User Data policy / prominent disclosure:** `PERMISSIONS.md` §4.
- **Location permissions:** foreground only, foreground service declaration filed
  (`PERMISSIONS.md` §2–3).
- **Sensitive data (sexual orientation, health-adjacent blood type):** explicit opt-in consent
  (separate checkboxes in onboarding, `legal/IN_APP_CONSENT_COPY.md`), disclosed in Data safety and
  the privacy policy; never used for ads (there are none).
- **Account deletion:** in app and via a web page (`CONSOLE.md` §4).
- **Health apps:** the app is not a health app; the blood-type reading is labelled as
  entertainment, not medical information.

## 6. Closed testing before production (personal account)

MetaVirtuoso is a **personal** developer account created after 13 November 2023, so Play requires a
closed test with **at least 12 testers opted in continuously for 14 days** before the owner can apply
for production access (https://support.google.com/googleplay/android-developer/answer/14151465).
Twelve is the minimum to apply, not a guarantee; Google also looks at engagement. Sundial is going
through the same process on this account (`~/Desktop/SUNDIAL/sundial-android-native/play/TESTING.md`).

Specific to Conjunction: testers need to **meet each other** for the core feature to be exercised.

1. Recruit 18–25 adults (to stay above 12 if anyone drops out) through a Google Group, e.g.
   `conjunction-testers@googlegroups.com`. All must be 18+; Restrict Minor Access applies to the
   closed track too.
2. Test and release → Testing → **Closed testing** → create track "Conjunction testers" → add the
   Google Group → create a release (same AAB as internal) → send for review.
3. Share the opt-in link. Everyone stays opted in for the full 14 days; the count restarts if it
   falls below 12.
4. Plan at least two **meet-ups** (an office, a café, a party) so that several testers have Nearby on
   in the same place: this produces real encounters, swipes, birthday-twin popups (if lucky) and
   chats — the engagement Google asks about. Ask testers to try reports and blocks on each other.
5. Ask everyone to use the readings daily (horoscope, palm, Eastern reading) — that engagement
   counts too.
6. Ship at least one update during the test (fixes from feedback, higher `versionCode`).

**Invitation (ready to send):**

> Subject: Help test Conjunction, an astrology app that finds compatible people nearby
>
> I'm releasing Conjunction on Google Play: your birth chart, Chinese zodiac, blood type and palm
> reading, written by the AI on your phone — and an optional Nearby mode that tells you when a
> compatible person is standing close by, for dating or friends. Google asks new apps to be tested
> by at least 12 people for two weeks. Would you be one of them? (You must be 18 or over.)
>
> 1. On your Android phone, open this link and tap "Become a tester": OPT_IN_LINK
> 2. Install Conjunction from the Play Store link on that page.
> 3. Stay opted in for 14 days. Read your horoscope now and then, try the palm scanner, and come to
>    the meet-up on DATE at PLACE with Nearby turned on so we can try matching for real.
>
> Reply to this email (or themetavirtuoso@gmail.com) with anything odd. Thank you!

**Applying for production access (draft answers):**

- *How did you recruit testers?* Friends, family and colleagues (all adults) invited by email and a
  Google Group, using their own Android phones, plus two in-person meet-ups to exercise Nearby.
- *How easy was it?* Fill in after the test.
- *Engagement:* fill in — number of active testers per day, readings generated, encounters, mutual
  matches, chats, reports and blocks tried.
- *Feedback and changes:* fill in, with the versions that shipped the fixes.
- *Intended audience:* adults 18+ interested in astrology who want to meet compatible people for
  dating or friendship, in person.
- *Value:* private on-device readings (Western, Chinese, blood type, palm) and real-world,
  proximity-based matching with strong safety tools.
- *Expected installs in year one:* the owner's estimate.
