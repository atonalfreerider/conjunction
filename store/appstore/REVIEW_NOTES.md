# App Review: notes, guideline risks and how to test (serverless Bluetooth build)

The text pasted into App Store Connect → App Review Information → Notes is
`fastlane/metadata/review_information/notes.txt` (≤ 4000 characters; run
`store/tools/check_lengths.py`). This file explains the reasoning, the guidelines involved
(https://developer.apple.com/app-store/review/guidelines/), and what must exist before submitting.
The same notes serve Google Play's App access instructions.

## Blocking items before the first submission

1. **A way to review Nearby with one device.** Reviewers usually test one device, in one place, with
   no second Conjunction user nearby, and there is no server that could provide a demo partner. Build
   one of these (the notes are written for (a); edit them if (b) is chosen):
   - **(a) "Preview a match" on the Nearby screen, visible to every user** (not hidden — guideline
     2.3.1): plays the full match animation, a swipe card for a bundled, clearly labelled sample
     member "Aurora (demo)" with illustrated art (never a real person's photo), and, after a right
     swipe, a sample chat with scripted replies — all on the device, no Bluetooth, nothing sent. Uses
     the real engines (`MatchFilter`, `Compatibility.score`) against a fixed demo profile. Report and
     Block work on it and show the real report sheet (the email is addressed to the support address
     and can be cancelled).
   - **(b)** rely only on the two-device video and expect a 2.1 "unable to review" question.
2. **Two-device screen recording**, uploaded unlisted, URL in place of `[DEMO VIDEO URL]` in
   `notes.txt` (the length checker warns until it is filled), file attached to the submission. Show:
   onboarding → Nearby disclosure → Bluetooth permission → phones side by side → match animation on
   both → swipe right on both → chat (text arriving on the other phone) → a flagged message hidden
   behind a warning → Report (block + email sheet) → Block → Pause Nearby. One phone Android, one
   iPhone in the foreground, and a second take with both iPhones and one in the background.
3. **Contact info** filled in App Store Connect (owner's name and phone).
4. **Published legal pages** at the URLs in the metadata (privacy, terms, community guidelines with
   `#child-safety`, support page with the report address and the 24 h commitment).
5. **"Report this reading"** on every AI reading in the iOS app.
6. **Monitored report inbox** (`themetavirtuoso@gmail.com` or the final `[CONTACT EMAIL]`) with
   phone notifications on, so the 24 h commitment is real.

## Testing aids (owner and QA — not for reviewers)

- **Laptop test peer** `tools/p2p-sim/` (Python: bleak for the central, BlueZ's D-Bus API via dbus-fast for the peripheral; `bless` turned out unsuitable, see `tools/p2p-sim/README.md`): runs on the owner's
  laptop Bluetooth and acts as a configurable nearby user (e.g. "Aurora"), speaking the real
  protocol. Use it to test one phone at a time, to rehearse the video, and to test the background
  iPhone limitation (the laptop, like an Android phone, cannot see a backgrounded iPhone).
- Debug-only Android `MatchGalleryActivity` for animation and card screenshots.

## Guideline by guideline

### 1.2 User-Generated Content

Apple requires all four. Without a server they are met like this; the notes say so plainly rather
than claiming server moderation:

| Requirement | Conjunction |
|---|---|
| A method for filtering objectionable material from being posted | **On-device filtering on the receiving phone**: Apple's Sensitive Content Analysis screens incoming photos when the user has Sensitive Content Warning on; otherwise unscreened photos are blurred until tapped. Incoming chat text is screened by the on-device Foundation Model (harassment, sexual content, scams, contact-info lures, hate) and flagged messages are hidden behind a warning. No one can message before a mutual match; inbound data is rate-limited. |
| A mechanism to report offensive content, and timely responses | Report on every card, friend and chat: blocks immediately and opens the in-app mail sheet to the developer with the reported card and (optionally) messages. Commitment: a person reads every report **within 24 hours**, escalates threats and child-safety reports to the authorities, and replies to the reporter. |
| The ability to block abusive users | Block on every card, friend and chat; blocked identity keys and temporary IDs are never matched or accepted again on that phone. |
| Published contact information | In the app (Settings → Help) and on the support URL. |

**Honest limitation to state if asked:** chats are end-to-end encrypted and there are no accounts, so
the developer cannot read chats, remove content from users' phones, or ban a user. Apple's 1.2 text
does not require server moderation, but the "remove content / eject user" expectation from older
review responses may come up. Answer with the table, and — if built — the blocked-identity-key list
shipped in app updates (`legal/TERMS.md` §7.3).

1.2 also says random or anonymous chat ("Chatroulette-style") does not belong on the App Store.
Conjunction is not that: both people opt in to Nearby, must be physically near each other at the same
time, must fit each other's stated intentions, see a non-anonymous card (photo, first name, age), and
**both** must swipe right before any message.

1.1.4 bans "hookup" apps: copy, screenshots and onboarding frame matching as dating or friendship.

### 4.3 Spam — dating and fortune telling are both saturated

Guideline 4.3(b) lists **dating** and **fortune telling** as saturated. Differentiators for the notes,
the description and any appeal:

1. **No servers at all.** Phones find each other over Bluetooth and talk directly; chats are
   end-to-end encrypted and live only on the two phones; the developer receives no user data.
2. **Proximity-only, real-world matching.** No profile browsing or feed; you are matched only with
   people physically near you at the same moment who also opted in.
3. **On-device intelligence.** Every reading and match blurb is written by Apple's on-device
   Foundation Models; birth time, birthplace, readings and palm photos never leave the iPhone.
4. **East–West synthesis computed, not templated:** astronomical natal chart, Chinese Four Pillars
   with solar-term boundaries, blood-type pairing, on-device palm analysis (Vision hand pose), one
   deterministic compatibility score identical on both phones.
5. **Birthday-twin detection** and the zodiac-merge match moment.

If rejected under 4.3(b): reply in the App Review thread with these points and the video, request a
call, then appeal to the App Review Board if needed. Do not resubmit unchanged builds repeatedly.

### 4.8 Login Services

Not applicable: there is no login of any kind.

### 5.1.1 Data collection and storage

- (i)/(ii) Permission strings (`INFO_PLIST.md`) and explicit consent for sensitive data
  (`legal/IN_APP_CONSENT_COPY.md` §3–5) — gender/seeking and blood type are sent to nearby phones only
  after separate consents.
- (v) **Account deletion:** not applicable (no accounts). "Settings → Delete all my data" wipes the
  phone; the notes mention it.
- 5.1.2(i): no data is shared with third parties or third-party AI; readings and screening use
  on-device Apple models.
- App Privacy label: **Data Not Collected** with reasoning in `APP_PRIVACY.md`.

### 2.5.4 Background modes

`bluetooth-central` and `bluetooth-peripheral` are used only while the user has Nearby on, to keep
finding and talking to nearby members while the phone is in a pocket. No location, no push.

### 1.4 Physical harm (meeting strangers)

Safety tips shown before Nearby and in the chat menu; no location or distance ever shown; easy pause;
emergency guidance on the report sheet.

### 1.1.6 False information (entertainment framing)

Readings and scores are labelled "for entertainment" in the app and the description.

## Two-device test plan (owner, TestFlight, and the video)

1. Two phones with the build: ideally an iPhone and an Android phone, plus a second iPhone for the
   iPhone-to-iPhone background case. Notifications and Bluetooth allowed. (Or one phone + the laptop
   test peer.)
2. Phone A: birth 1990-08-15 14:30 London, man, seeking women, single, looking for dating.
   Phone B: birth 1992-03-02 09:10 New York, woman, seeking men, single, looking for both.
   (Birthday-twin test: give B the same birth date as A.)
3. Turn on Nearby on both, side by side, iPhone in the foreground. Within seconds to a minute both
   get the match animation (foreground) or a local notification (background).
4. Swipe right on both → "It's a Conjunction!" → chat opens; exchange messages; send one containing
   a phone number or an insult to see the receiving phone hide it behind a warning.
5. Walk one phone out of range, send a message (shows "Waiting"), walk back: it is delivered.
6. Report (block + email sheet), Block, Unmatch; check the person is not shown again.
7. Pause Nearby for 1 h; check nothing is found; turn Nearby off; check scanning/advertising stop.
8. Background the iPhone with an Android peer: expect no discovery (documented limitation); repeat
   with two iPhones: expect discovery.
9. Settings → Delete all my data on one phone: its data is gone; the other phone keeps its copy of
   the chat (as the privacy policy says).
