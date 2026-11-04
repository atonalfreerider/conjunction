# Google Play policy considerations (serverless Bluetooth build)

Conjunction combines four things Play reviews closely: a **dating** app, **user-generated content**
between strangers, **personal data sent to other people's phones**, and **AI-generated content**. In
the serverless build (SPEC §5c) there is no backend, so every safety duty has to be met on the phone
or by email. This is what each policy requires and how the app meets it — and where it is weaker
than a server-backed app. Policy pages change; re-read the linked pages before each submission.

## 1. Dating and matchmaking (Age-Restricted Content and Functionality)

Policy: apps whose core functionality is dating or matchmaking **must use Restrict Minor Access**
(https://support.google.com/googleplay/android-developer/answer/16838200; how-to:
https://support.google.com/googleplay/android-developer/answer/9867159).

- **Target audience: 18 and over only, and tick "Restrict minor access"** in Policy → App content →
  Target audience and content.
- Play also expects the app's own age gating to be "robust and effective". Without a server, the
  layers are:
  1. Restrict Minor Access (Play's own age signal).
  2. Onboarding age gate: a full birth date (not a yes/no button), which is also the chart's birth
     date and the age on the Nearby card; a local "blocked" flag stops immediate retries; the birth
     date can't be changed to an adult one without repeating the gate.
  3. Every receiving phone **refuses cards whose age or birth date is under 18**, so even a
     modified sender is filtered by unmodified receivers.
  4. "Child safety / possible minor" is a report reason; reports reach the owner by email.
  5. **Recommended before launch:** the **Play Age Signals API**
     (https://support.google.com/googleplay/android-developer/answer/16569691), treating
     "supervised" or "under 18" as a hard block for Nearby and chat. It runs on the device, so it
     fits the serverless design. iOS equivalent: Declared Age Range.
  **Honest limit:** there is no server-side enforcement and no way to ban a confirmed minor; say so if
  asked, and point to layers 1, 3 and 5.
- **Dating-app expectations:** no messaging before a mutual match; block, unmatch and report on the
  card and in the chat; safety tips linked from the match screen and before the first chat; no
  location or distance; incoming photos screened (or blurred) before display; pause controls.
- **Listing:** say "18+" in the description (done) and do not use suggestive imagery or wording in
  screenshots (illustrated avatars, not photos of real people).

## 2. User-generated content (UGC policy)

Policy: https://support.google.com/googleplay/android-developer/answer/9876937. Apps with UGC must:

| Requirement | How Conjunction meets it without a server |
|---|---|
| Terms / policies that define objectionable content and forbid it, accepted before UGC is created | `legal/TERMS.md` and `legal/COMMUNITY_GUIDELINES.md`, accepted (versioned, recorded on the phone) during onboarding before any card is sent |
| Robust, effective, ongoing moderation | **On-device screening on the receiving phone**: Gemini Nano checks incoming chat text (harassment, sexual content, scams, contact-info lures, hate) and incoming photos where supported; flagged content is hidden behind a warning; unscreenable photos are blurred until tapped. No messaging before mutual match. Rate limits on inbound frames. There is no pre-delivery moderation by the developer (chats are end-to-end encrypted). |
| In-app reporting of users and content | Report on every match card, friend and chat message: blocks immediately and prepares an email to the developer with the reported card and (optionally) messages. See §4 for the "without leaving the app" issue. |
| In-app blocking of users | Block on every card, friend and chat; blocked identities and temporary IDs are never matched or accepted again on that phone |
| Acting on reports and repeat offenders | Owner reads reports daily (24 h target), escalates threats and CSAE to authorities. **Weak point:** no accounts → no bans. Mitigation to decide: a blocked-identity-key list shipped in app updates (`legal/TERMS.md` §7.3). |

Expect questions on "robust moderation" and "acting on repeat offenders". The honest answer is the
table above; do not claim server moderation or bans.

## 3. Child Safety Standards (Social and Dating apps)

Policy: https://support.google.com/googleplay/android-developer/answer/14747720.

1. **Published standards** that explicitly prohibit CSAE: the "Child safety" section of
   `legal/COMMUNITY_GUIDELINES.md`; publish it with the `#child-safety` anchor and enter that URL.
2. **In-app feedback mechanism:** the Report flow includes "Child safety / possible minor".
3. **Addressing CSAM:** on actual knowledge (a report), preserve the report and report to NCMEC's
   CyberTipline (US) / the relevant authority. The developer cannot remove content from users'
   phones; state that the receiving phone's screening hides sexual imagery and that reports are
   escalated.
4. **Compliance with child-safety laws.**
5. **Child safety point of contact** in Play Console: the owner, `themetavirtuoso@gmail.com`.

## 4. AI-generated content, and "report without leaving the app"

Policy: https://support.google.com/googleplay/android-developer/answer/13985936 (re-read
2026-11-03). Readings and match blurbs are generated by Gemini Nano on the device. The policy
requires **"in-app user reporting or flagging features"** for offensive AI output that work
**"without needing to exit the app"**. The UGC policy also expects in-app reporting.

**Conflict to resolve before submission:** SPEC §5c makes Report a pre-filled email. On Android an
email is sent through another app (`ACTION_SENDTO` opens Gmail etc.), which *leaves* the app. Options:

1. **In-app report sheet + email hand-off (minimum):** the user picks the reason and confirms inside
   the app; the app immediately hides the reading (or blocks the person) and records the report
   locally — the flagging itself happens in the app — then offers "Send to Conjunction" by email.
   Argue that the flag is made in-app and the email is an optional delivery step. Some reviewers may
   not accept this.
2. **Sundial's approach for reports only:** post the report from inside the app to a Google Form
   (`~/Desktop/SUNDIAL` `ReadingReporter`). No server of our own, but it is a network endpoint: the
   report content would then be collected (declare "App activity → Other user-generated content"
   in `DATA_SAFETY.md`, and on iOS "User Content → Other User Content" + "Customer Support" in
   `APP_PRIVACY.md`), and the privacy policy §5 must name Google Forms. Most robust against review.
3. iOS is less affected: `MFMailComposeViewController` composes and sends inside the app.

**Owner decision.** The documents describe option 1 (email). If option 2 is chosen, update
`legal/PRIVACY.md` §5/§9, `DATA_SAFETY.md`, `APP_PRIVACY.md` and `IN_APP_CONSENT_COPY.md` §8 together.

Also: each reading card needs "Report this reading" (`report_reading`); declare AI-generated content
as Yes in the content-rating questionnaire.

## 5. Personal data and permissions

- **User Data policy / prominent disclosure:** `PERMISSIONS.md` §4 — the card sent to nearby phones
  is personal and sensitive (orientation, optional blood type, photo).
- **No location permission** (`PERMISSIONS.md` §1.1). Nearby devices permission with
  `neverForLocation`; `connectedDevice` FGS declaration (`PERMISSIONS.md` §3).
- **Sensitive data:** separate explicit consents (orientation, blood type), disclosed in Data safety
  and the privacy policy; never used for ads (there are none).
- **Account deletion:** not applicable (no accounts); local "Delete all my data".
- **Health apps:** not a health app; the blood-type reading is labelled entertainment.

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
