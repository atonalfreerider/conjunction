# App Review: notes, guideline risks and how to test

The text pasted into App Store Connect → App Review Information → Notes is
`fastlane/metadata/review_information/notes.txt` (≤ 4000 characters; run
`store/tools/check_lengths.py`). This file explains the reasoning, the guidelines involved
(https://developer.apple.com/app-store/review/guidelines/, read 2026-11-03), and what must exist
before submitting.

## Blocking items before the first submission

1. **Demo companion on the backend (needed by the notes as written).** Reviewers usually test with
   one device in one place, so Nearby would show nothing and the app would be rejected under 2.1
   (incomplete / can't review). The notes promise a server-side demo member, "Aurora (App Review
   Demo)", so the `backend/` owner must implement:
   - a Firestore config doc (e.g. `config/reviewDemo { enabled: bool, matchDisplayName: "AppReview" }`)
     that only an admin can write;
   - in `updatePresence`, when enabled and the caller's display name equals `matchDisplayName`, create
     an encounter with a fixed demo profile (illustrated avatar, not a real person's photo; clearly
     labelled "App Review Demo") of the kind the caller qualifies for, regardless of distance;
   - the demo side swipes right automatically and posts one fixed message after the caller's first
     message, through the normal moderated `sendMessage` path;
   - the demo account is never matched with anyone else and is excluded from real matching.
   This is a server feature disclosed in the notes, not a hidden feature in the binary (2.3.1).
   If it is not built, rewrite the notes to rely on the two-device video only — and expect questions.
2. **Two-device screen recording** (both screens side by side or one after the other): onboarding →
   Nearby on → match animation → swipe right on both → chat → report → block. Upload unlisted and put
   the URL in place of `[DEMO VIDEO URL]` in `notes.txt` (the length checker warns until it is
   filled). Attach the file to the review submission too.
3. **Contact info** filled in App Store Connect (owner's name and phone).
4. **Published legal pages** at the URLs in the metadata (privacy, terms, community guidelines,
   support page with contact email, account-deletion page).
5. **"Report this reading"** on AI readings in the iOS app (also a Play requirement; it helps here
   under 1.1 for generated content).

## Guideline by guideline

### 1.2 User-Generated Content

Apple requires all four, and the notes say where each is:

| Requirement | Conjunction |
|---|---|
| A method for filtering objectionable material from being posted | TypeSafe pre-delivery moderation of names, bios, every chat message; Cloud Vision SafeSearch on photos, which stay hidden until approved |
| A mechanism to report offensive content, and timely responses | Report on cards, profiles, chats and messages; human review queue; commit to act within 24 h (Apple has historically expected 24 h for objectionable-content reports — make sure the owner can actually meet it, including by email alerts on urgent reports) |
| The ability to block abusive users | Block on cards, profiles, chats; blocked pairs never re-matched |
| Published contact information | `themetavirtuoso@gmail.com` in the app (Settings → Help) and on the support URL |

1.2 also says apps used primarily for "Chatroulette-style experiences, random or anonymous chat … do
not belong on the App Store". The notes therefore explain why Conjunction is not random chat: both
people opt in to Nearby, must be physically near each other at the same time, must fit each other's
stated intentions (gender / seeking / dating-or-friends / relationship status), see a non-anonymous
card (moderated photo, first name, age), and **both** must swipe right before any message is possible.

1.1.4 bans "hookup" apps. The copy, screenshots and onboarding must frame matching as dating or
friendship, never casual sex; moderation blocks solicitation.

### 4.3 Spam — dating and fortune telling are both saturated

Guideline 4.3(b): "Certain kinds of apps, such as **dating**, flashlight, sound effects, wallpaper,
simple timers, and **fortune telling**, are well established on the App Store and we will not accept
new submissions unless they offer a meaningfully different or improved experience." Conjunction is in
**both** listed categories, so 4.3(b) is the most likely rejection. What is meaningfully different,
to state in the notes (done, briefly), in the description and, if rejected, in the appeal:

1. **Proximity-only, real-world matching.** There is no profile browsing or endless feed. You are
   matched only with people who are physically within ~50 m at the same moment and who also opted in
   — a meet-in-person experience, not another swipe catalogue.
2. **On-device intelligence and privacy.** Every reading (natal, daily, Eastern, palm, match blurb) is
   written by Apple's on-device Foundation Models; birth time, birthplace, readings and palm photos
   never leave the iPhone. Most astrology apps generate readings in the cloud.
3. **East–West synthesis computed, not templated:** an astronomical natal chart (Meeus algorithms),
   the Chinese Four Pillars with solar-term boundaries, blood-type pairing, and on-device palm
   analysis with Vision hand-pose detection, combined into one deterministic compatibility score.
4. **Birthday-twin detection** and the zodiac-merge match moment are unique interactions.
5. **Safety-first design:** no messaging before mutual acceptance, pre-delivery moderation, exact
   location never shared, no location history, pause controls.

If rejected under 4.3(b): reply in App Review's thread with the five points and the demo video, and
request a phone call (Apple offers one for 4.3 conversations); then, if needed, appeal to the App
Review Board. Do not resubmit unchanged builds repeatedly.

### 4.8 Login Services

Google Sign-In is offered → Sign in with Apple must be offered too (`APP_STORE_CONNECT.md` §8).

### 5.1.1 Data collection and storage

- (i)/(ii) Permission strings and explicit consent for sensitive data: `INFO_PLIST.md`,
  `legal/IN_APP_CONSENT_COPY.md`.
- (v) **Account deletion in the app**: Settings → Delete account runs `deleteAccount` (full erasure)
  and revokes the Sign in with Apple token.
- 5.1.2: no sharing with third parties beyond service providers; no third-party AI receives user
  data — TypeSafe moderation receives message text as our processor; disclose it in the privacy
  policy (done in `legal/PRIVACY.md` §6). Apple's 5.1.2(i) asks apps to "clearly disclose where
  personal data will be shared with third parties, including with third-party AI, and obtain explicit
  permission": treat TypeSafe as third-party AI and disclose it in the onboarding consent screen too
  ("Messages are checked by an automated moderation service before delivery").

### 5.1.5 Location Services and 2.5.4 background modes

Location is used only for Nearby, which is opt-in and explained before the prompt; background updates
come from the `location` background mode started in the foreground with the blue indicator; When In
Use only.

### 1.4 Physical harm (meeting strangers)

Safety tips (`legal/SAFETY_TIPS.md`) shown on first match and in the chat menu; exact location never
revealed; "Nearby" only within a public-feeling radius; easy pause.

### 1.1.6 False information (entertainment framing)

Readings and scores are labelled "for entertainment" in the app and the description (fortune-telling
apps are expected not to present predictions as fact).

## Two-device test plan (for the owner, TestFlight, and the video)

1. Two iPhones (iOS with Apple Intelligence on one, without on the other, to see both reading paths),
   TestFlight build, notifications allowed.
2. Phone A: birth 1990-08-15 14:30 London, man, seeking women, single, looking for dating.
   Phone B: birth 1992-03-02 09:10 New York, woman, seeking men, single, looking for both.
   (For a birthday-twin test give B the same birth date as A.)
3. Turn on Nearby on both, side by side, outdoors or by a window (GPS). Within ~1 min both get the
   match notification; open it → match animation → swipe card.
4. Swipe right on both → "It's a Conjunction!" → chat opens; exchange messages; send one containing a
   phone number to see moderation hold it.
5. Report the other, block, unmatch; check the pair is not shown again.
6. Pause Nearby for 1 h; check no further matches; turn off Nearby; check the blue indicator
   disappears.
7. Delete account on one phone; the other's chat empties.
