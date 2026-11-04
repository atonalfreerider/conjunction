# Conjunction — Privacy Policy

| | |
|---|---|
| Document | `PRIVACY` |
| Version | 2.0 (draft of 2026-11-03) |
| Effective date | [EFFECTIVE DATE] |
| Publisher | [LEGAL ENTITY NAME], doing business as MetaVirtuoso, [ADDRESS] |
| Contact (including privacy and data-protection requests, and reports) | [CONTACT EMAIL] |
| EU representative (GDPR Art. 27) | [EU REPRESENTATIVE NAME AND ADDRESS, or "not appointed" if an exemption applies] |
| UK representative (UK GDPR Art. 27) | [UK REPRESENTATIVE NAME AND ADDRESS, or "not appointed" if an exemption applies] |
| Apps | Conjunction for Android (`com.metavirtuoso.conjunction`) and iOS |

Conjunction ("the **App**") draws your birth chart, writes horoscopes and palm readings **on your
phone**, and — only if you turn it on — uses **Bluetooth** to find compatible people close by, so
you can match for dating or friendship and chat. **Conjunction has no servers and no accounts.**
This policy explains what stays on your phone, what your phone sends directly to other people's
phones, what (very little) reaches us or other companies, and your choices.

**The short version**

- **We do not run a server and we do not receive your data.** There is no Conjunction account, no
  cloud database and no push service. Everything the App stores is stored on your phone.
- Your **birth time and place, your readings and your palm photos never leave your phone.**
  Readings are written by an AI model that runs on the phone itself.
- **Nearby is off by default.** When you turn it on, your phone talks **directly to other phones
  over Bluetooth**. The App does **not** use GPS or ask for location permission, and it never
  learns or sends where you are.
- While Nearby is on, your phone sends a **hello** — your gender and who you are seeking,
  relationship status, what you are looking for, the planet positions used for the compatibility
  score, your birth date and birth hour, and your blood type if you chose to add it — to **every
  phone with Nearby on within Bluetooth range**, so the two phones can work out whether you match.
  Together these details are close to unique, so someone who keeps scanning near you could
  recognise you again even though the temporary ID changes. **If you match**, it also sends your **card** — first name,
  first line of your bio and profile photo — and the other person sees your match card (with your
  age, signs and Chinese animal) **before either of you accepts**.
- Before a mutual match, **this data is not encrypted** on the Bluetooth link.
- If you both swipe right, your phones exchange a long-term **identity key** and you can **chat**.
  Chats are **end-to-end encrypted** and stored **only on the two phones**.
- Your **gender and who you are seeking** can reveal your **sexual orientation**, and **blood type**
  is health-related. They are sent to nearby phones only with your **explicit consent**.
- **What you send to someone stays on their phone.** We cannot delete it from their phone, and
  neither can you.
- **No ads, no analytics by us, no selling or sharing of your data, no tracking.**
- To delete your data, delete the App's data or uninstall the App.

## 1. Who is responsible

[LEGAL ENTITY NAME], doing business as MetaVirtuoso ("**we**", "**us**"), publishes the App and
decides how it is built. Because the App has no server, **we do not hold any of the personal data
described in Sections 2 to 4**: it is on your phone and on the phones of people you meet. We are
responsible for designing the App so that it handles that data as this policy describes. The only
personal data we ourselves receive is what you choose to send us by email (Section 5).
[Counsel to confirm the controller analysis for data exchanged between users' phones, including
whether the publisher is a controller for processing it designs but never receives, and how the
GDPR household exemption applies to users.]

## 2. Data that stays on your phone

| Data | What it is used for |
|---|---|
| **Birth date, exact birth time, birth place** (and its coordinates and time zone) | Calculating your birth chart and Chinese zodiac / Four Pillars. **Your birth time (minute) and birth place are never sent anywhere.** |
| **Your full birth chart** (all planet positions, houses, aspects, Four Pillars) | Drawing the chart and writing readings. |
| **Readings** (daily, natal, Eastern, palm, compatibility blurbs) | Shown to you; generated on the phone. |
| **Palm photos** | Measured on the phone (hand landmarks and line detection) to describe your palm to the on-device AI. **Never sent anywhere.** Deleted after the reading unless you choose to save it in the App. |
| **Your profile** (first name, profile photo, short bio, dating and friends settings, optional blood type) | Building your hello and card for Nearby (Section 3). |
| **Your identity key** | A random key pair made by the App the first time you match, kept in the Android Keystore or iOS Keychain. Used to set up encrypted chats and to let your friends' phones recognise yours later. The private key never leaves your phone. |
| **Matches, friends and chats** | The cards of people you matched with, their identity keys, and your chat history with them (Section 4). |
| **Safety settings** | People you blocked, snoozed or passed on, pause settings. |
| **Your acceptance records** for our legal documents | Showing what you agreed to and when (document, version, time, app version, platform and a fingerprint of the text). Kept on your phone only. |

**On-device AI.** Readings are written by Gemini Nano through Android's AICore system service (Android)
or by Apple's on-device Foundation Models (iOS). Prompts and readings are processed on the phone and
are not sent to us, to Apple or to Google as part of generating the reading. Where the model is not
available, the App assembles a reading from text bundled in the App, also on the phone. The same
on-device models are used to screen incoming chat messages and photos (Section 6).

**Birth place search.** When you type your birth place, the App asks your phone's built-in geocoding
service (Apple's or Google's) to find its coordinates; your phone's operating system sends the search
text to Apple or Google for that purpose, under their privacy policies. We do not receive it. You can
enter coordinates by hand instead.

**Android: Google ML Kit diagnostics.** On Android, Google's ML Kit library that connects the App to
Gemini Nano may send Google limited diagnostic and usage information — such as device model, Android
version, app package and version, the feature used, performance metrics, error codes, input and
output sizes, configured languages and diagnostic identifiers — to operate and improve ML Kit. It
does not include your birth details, photos, chats or reading text. Google says it does not pass this
data to third parties. See <https://developers.google.com/ml-kit/android-data-disclosure>. We do not
receive it. The iOS App contains no Google ML Kit.

**Device backups.** Your phone's own backup (iCloud, or Google backup on Android) may include the
App's local data if you have backup turned on, under Apple's or Google's terms; we have no access to
those backups. The App **excludes its data from those backups**: on Android, the backup rules
include no App data files (birth data, profile, readings, consent records, palm photos, friends,
chats, received photos and the identity key are all left out); on iOS, the App's data folder is
marked "excluded from backup", and only two display preferences (which hand you photograph for palm
readings, and the last open tab) can be backed up. The identity private key is device-only (Android
Keystore; iOS Keychain "this device only" or the Secure Enclave). A new or restored phone therefore
starts empty and cannot resume your existing friendships.

## 3. What your phone sends to other phones before a match

**Nearby is off by default.** When you turn it on (and until you pause it or turn it off), your phone
uses **Bluetooth Low Energy** to look for other phones running Conjunction with Nearby on, and to be
found by them. This works without pairing, without the internet and **without location
permission**.

**What your phone broadcasts to everyone.** The Bluetooth advertisement contains only a code that
says "Conjunction Nearby is here". It contains no name, no ID and nothing about you. Anyone with a
Bluetooth scanner nearby can, however, see that a phone running Conjunction Nearby is present.

**Step 1 — the "hello", sent to every Conjunction phone yours connects to.** When two phones with
Nearby on are within Bluetooth range — usually up to a few tens of metres, sometimes more — they
connect and each sends a "hello" with only what is needed to work out whether you match:

| Sent in the hello | Notes |
|---|---|
| A random **temporary ID** | Changes every 15 minutes and every time you turn Nearby on, so you cannot be followed by it. |
| **Your gender, who you are seeking** — *sensitive: can reveal sexual orientation* — **relationship status** and **what you are looking for** (dating, friends or both) | Used by the other phone to decide whether you can be a dating or friends match. Sent only after you give the separate consent in Section 7. |
| **Chart positions used for the compatibility score**: the longitudes of the Sun, Moon, Venus, Mars and (if your birth time is known) the Ascendant; your Chinese year animal and day-pillar stem | So both phones calculate the same score. The other phone also works out your signs and Chinese animal from them. |
| **Birth date**, and **birth hour** if you know it (never the minute) | For the 18+ check, your age, and the "birthday twin" / "exact twin" checks. The birth date itself is not displayed; twins are told they share a birthday (and hour). |
| **Blood type** (A, B, AB or O; never Rh) — *sensitive: health-related* | **Only if you tick "Also use my blood type in Nearby compatibility"** (off by default). Otherwise it is left out and never leaves your phone. |
| Friend-recognition codes, mixed with decoys | Let your existing friends' phones recognise yours; strangers cannot use them to identify or track you. |

**Step 2 — your card, sent only when the other phone's hello makes you a match.** If both phones
work out that you qualify as a dating or friends match, each sends its **card**: your **first name**,
the **first line of your bio**, the match kind and your **profile photo** (resized to at most 640
pixels). If there is no match, the card and photo are not sent, the phones disconnect, and the
other App shows nothing and keeps nothing.

The other person then sees your **match card before either of you accepts**: photo (screened on
their phone first, Section 6), first name, age, Sun / Moon / Rising signs, Chinese animal,
relationship status, what you are looking for, the compatibility score and whether it is a dating or
friends match (a dating match also tells them you are a gender they seek and that you seek theirs).
Unanswered match cards are discarded after 24 hours.

**Not sent at any stage:** your birth place, the minute of your birth time, your full chart, your
readings, palm photos, your location, your phone number, contacts or any device identifier.

**What we cannot control.** Before a mutual match, **nothing your phone sends is encrypted**: the
hello, your card, your photo and your swipe can be read by anyone who connects to your phone or
listens to the Bluetooth radio nearby. Someone could also run a modified or imitation app, or
Bluetooth equipment, that keeps hellos instead of discarding them, pretends to match so as to get
your card and photo, ignores blocks, or records the cards other phones exchange. Bluetooth signals
can travel further than "nearby" suggests. **Only put on your card what you are comfortable with a
stranger near you knowing**, and pause Nearby where you would not want to be noticed.

**No location.** The App does not request location permission and never reads or sends GPS or
Wi-Fi location. It decides that someone is "nearby" only from Bluetooth signal strength, and shows
no distance. However, a match card does tell the other person that you are physically close to them
right now, so they may be able to recognise you.

## 4. What your phone sends after a match, and chats

- **Swipes.** Swiping right or left sends your answer to the other phone. A left swipe is never
  shown to the other person; your phone hides that person from you for 30 days.
- **Mutual match.** When you both swipe right, your phones exchange your **identity public keys**
  and save each other as **friends**: their card, photo and key are stored on your phone, and yours
  on theirs.
- **Chat is end-to-end encrypted.** Messages are encrypted on your phone with a key only your phone
  and your friend's phone can work out (ECDH P-256, HKDF-SHA256, AES-256-GCM) and sent over the
  Bluetooth link. We cannot read them, and they pass through no server. Each new connection uses
  fresh keys. The first key exchange is trusted as it happens; the App shows a **safety code** you
  can compare in person to make sure no one interfered.
- **Store and forward.** If your friend is out of range, your message waits on **your** phone, in the
  App's private storage, and is encrypted and delivered the next time your two phones are near each
  other. Until then it has not been delivered, and the App says so.
- **Where chats are kept.** Chat history is stored **only on the two phones**. Your friend can keep,
  screenshot or show your messages to others; please only send what you are happy for them to keep.
- **Recognising friends later.** When friends' phones meet again, they recognise each other with a
  code only friends can check; strangers cannot use it to identify or follow you.

## 5. Data we receive: only what you email us

The only way personal data reaches us is **if you send it to us by email** — for example a report,
a support question or a privacy request.

- **Report.** Tapping **Report** blocks the person on your phone and opens a **pre-filled email to
  [CONTACT EMAIL]** in your email app, containing the reported person's card (first name, age, signs,
  match kind, whether their card had a photo, a short code your phone derived from their chart data
  so that repeated reports of the same person can be linked, and, if you were friends, their safety
  code), the reason you choose, your description and, if you choose to include them, the reported
  messages. Their photo is not attached; you can attach a screenshot yourself.
  **Nothing is sent until you press Send in your email app**, and you can edit or remove anything
  first. The email also contains your email address and whatever your email provider adds.
- **What we do with emails.** We read them to answer you, review reports, keep users safe, enforce
  our Terms and defend legal claims. We keep report emails for up to [2 years] after we close the
  report (longer only if needed for a legal claim or a lawful request) and other support email for up
  to [2 years] after the conversation ends; then we delete them. Our email provider
  ([EMAIL PROVIDER, e.g. Google Workspace / Gmail]) stores them for us.
- **Reports contain someone else's data.** If you report someone, you share their card and messages
  with us. Only include what is needed to explain the problem.

We do not receive anything else: no account, no profile, no location, no chats, no logs, no
analytics, no crash reports. [Confirm before release: that the builds contain no analytics,
crash-reporting, advertising or Firebase SDKs. If one is ever added, this policy and the store
privacy answers must change first.]

## 6. Safety screening on your phone

There is no server-side moderation. Instead, **the App on the receiving phone screens what it
receives**, on the device:

- **Photos** on match cards are checked on the receiving phone before they are shown (iOS: Apple's
  Sensitive Content Analysis when it is turned on in the phone's settings; Android: Gemini Nano image
  checks where the phone supports them). If a photo cannot be checked, it is **blurred until the
  viewer taps "Show"**.
- **Incoming chat messages** are checked on the phone by fixed word and pattern rules and, where
  the on-device AI is available, by the AI, for harassment, sexual content, scams and attempts to
  move you to other contact details; flagged messages are hidden behind a warning, which you can
  choose to open. A message that arrives while the App is in the background may wait to be checked
  until you open the App.
- These checks happen only on the phone that receives the content; the content and the result are
  not sent to us or anyone else. **Automated screening makes mistakes in both directions.**

**Automated decisions.** Matching and compatibility scores are calculated automatically, on the
phones, by a fixed formula, and on-device screening may hide or blur content. These do not produce
legal or similarly significant effects. If you think something was hidden or matched wrongly,
email [CONTACT EMAIL].

## 7. Sensitive data and your consent

- **Gender and seeking (sexual orientation).** Your gender, who you are seeking and your relationship
  status are used to decide who you can be matched with. Because they can reveal your sexual
  orientation, the App asks for your **explicit consent** with a separate checkbox before they are
  ever sent to another phone. Without it you can still use charts and readings, but not Nearby.
- **Blood type (health-related).** Used for your own blood-type reading on your phone. It is added to
  your hello **only if you separately agree**.
- **Withdrawing consent.** In **Settings → Privacy** you can withdraw either consent at any time.
  Your phone then stops sending that data (withdrawing the dating-profile consent turns Nearby off).
  **Copies already received by other phones** — for example
  those of your friends — **stay on those phones**; we cannot reach them.

## 8. Legal bases (EEA, UK and Switzerland)

To the extent the GDPR or UK GDPR applies to processing the App performs on your phone or that we
perform:

| Processing | Legal basis |
|---|---|
| Exchanging hellos and cards, matching, chat, notifications on your phone | **Contract** (Art. 6(1)(b)) — providing the features you turn on — and **consent** (Art. 6(1)(a)) through the Nearby opt-in |
| Gender, seeking and relationship status (may reveal **sexual orientation**) sent to nearby phones | **Explicit consent** (Art. 9(2)(a)), with Art. 6(1)(a) |
| Blood type sent to nearby phones | **Explicit consent** (Art. 9(2)(a)), separate and optional |
| Bluetooth scanning and advertising, and storing data on your phone | Consent under the ePrivacy rules where required (the Nearby opt-in and the phone's "Nearby devices" / Bluetooth permission); otherwise strictly necessary for the feature you asked for |
| On-device screening of photos and messages you receive | **Legitimate interests** (Art. 6(1)(f)) in keeping users safe |
| Reports and support emails we receive | **Legitimate interests** in safety, enforcing our Terms and answering you; **legal claims** (Art. 9(2)(f)) where the email contains special-category data; **legal obligation** (Art. 6(1)(c)) where the law requires us to act |

## 9. Sharing

We do not sell personal data, do not share it for cross-context behavioural advertising, and do not
give it to data brokers. Your data is disclosed only:

- **to other users' phones**, as described in Sections 3 and 4, because you turned on Nearby and
  chose to match and chat;
- **to Apple or Google** as part of your phone's own services (birth-place search, backups, and on
  Android the ML Kit diagnostics in Section 2) under their policies;
- **by us, for emails you send us**: to our email provider, and, when we believe in good faith that
  the law requires it or that it is necessary to protect someone from imminent death or serious
  harm, to the police or other authorities (for example, a report of a threat or of child sexual
  exploitation, which in the US we report to NCMEC);
- **to a successor** if our business is merged, acquired or sold, under this policy.

## 10. How long data is kept

On your phone:

| Data | Kept |
|---|---|
| Hellos received from people who are **not** a match | Not kept: discarded as soon as the App decides there is no match |
| Unanswered match cards | 24 hours |
| People you passed on (left swipe), snoozed or unmatched | A one-way fingerprint calculated from the chart data in their hello (not their name or photo), for 30 days after a left swipe or unmatch and 7 days after a snooze, so they are not shown again |
| Friends (mutual matches) and chats | Until you unmatch or block them (which deletes the chat from your phone), or delete the App's data |
| Messages waiting for delivery | Until delivered, or until you unmatch or block that friend or delete the App's data (there is no automatic expiry) |
| Blocks | Until you unblock (iPhone: Settings → Blocked people) or delete the App's data. [Engineering: the Android App has no unblock screen yet; until it does, an Android block lasts until the App's data is deleted.] |
| Palm photos | Deleted after the reading unless you save them |
| Everything else (profile, settings, acceptance records) | Until you change it or delete the App's data |

**On other people's phones,** what you sent them stays as long as their App keeps it under the same
rules — so a friend keeps your card, photo, key and your chats with them **until they unmatch, block
or delete their App's data**. Unmatching or blocking on your phone does **not** delete anything
from the other phone (the App does not send a remote-delete), and a phone running modified software
may ignore their own App's rules.

With us: emails as described in Section 5.

## 11. Deleting your data

- **On your phone:** unmatch or block a friend in the App (this deletes your chat with them from
  your phone); turn off Nearby; or delete **everything** — profile, birth data, readings, palm
  photos, friends, chats, received photos, acceptance records and your identity key — with
  **Settings → Delete all my data**, by clearing the App's storage in your phone's settings, or by
  uninstalling the App. We cannot
  recover local data, and nothing is restored if you reinstall (except from your own phone backup).
- **On other people's phones:** **we cannot delete what you already sent to other phones**, and the
  App does not let you remotely delete it. Your friends keep their copies of your card, photo,
  identity key and the messages you sent them until they remove them.
- **With us:** email [CONTACT EMAIL] to ask us to delete emails you sent us.

There is no account to delete and no data held by us in the App, so there is no web account-deletion
form; this section explains how deletion works instead.

## 12. Your rights

Wherever you live, you can ask us to access, correct, export or delete personal data **we hold** —
which, for almost everyone, is nothing, or only emails you sent us. Everything else is on your phone
and under your control in the App: you can correct it in Profile, delete it (Section 11), and save a
copy with **Settings → Export my data**, which writes the App's data on your phone (except key
material) to a file that you save or share yourself (Android: a location you pick; iPhone: the share sheet); nothing is sent to us. Email [CONTACT EMAIL]; we answer within one month (GDPR) or 45
days (US state laws), extendable where the law allows. We will not discriminate against you for
exercising your rights.

**EEA, UK and Switzerland.** You have the rights of access, rectification, erasure, restriction,
portability and objection, and the right not to be subject to solely automated decisions with legal
or similarly significant effects. You may complain to your local data-protection authority (in the
UK, the Information Commissioner's Office).

**California and other US states with comprehensive privacy laws.** We do **not** collect personal
information through the App for ourselves, and we do **not sell or share** personal information (as
defined in the CCPA) and have not in the past 12 months. The only personal information we collect is
what you email us: identifiers (your email address), and the content of your email, which may
include another person's name, photo, age and messages (from a report), and sensitive personal
information if you include it. We use it only to respond, keep users safe and comply with the law,
so the "right to limit" does not apply. You have the rights to know, access, correct and delete, and
to appeal a refusal (then, if needed, to contact your state Attorney General). You may use an
authorised agent.

**Washington (My Health My Data Act) and Nevada.** Your optional blood type is processed on your
phone and sent to other users' phones only with your consent; we never receive it. [Counsel to
confirm whether a separate Consumer Health Data Privacy Policy page is still required.]

**Biometric information.** The App does **not** collect biometric identifiers. Hand landmarks in palm
photos are measured only on your phone to describe palm features for a reading and are not used to
identify you. On-device photo screening checks for prohibited content; it does not recognise faces.

## 13. Children

The App is **for adults 18 and over only**. It asks for your date of birth and blocks anyone under 18,
and the App on other phones refuses cards that show an age under 18. If you believe a minor is using
the App, report them in the App or email [CONTACT EMAIL]; we will act on the reports we receive (for
example by reporting child sexual exploitation to the authorities). Because there is no server, we
cannot see or remove profiles ourselves.

## 14. International transfers

We do not operate servers, so the App does not transfer your data to any country; it goes
phone-to-phone over Bluetooth. Emails you send us are handled in [COUNTRY / EMAIL PROVIDER REGION]
by our email provider; where the GDPR or UK GDPR applies, we rely on the provider's Standard
Contractual Clauses or EU–US Data Privacy Framework certification.

## 15. Security

- Chats are end-to-end encrypted with fresh keys for each connection, and a safety code lets you
  check your friend's key in person; your identity private key is protected by the phone's secure key
  store (Android Keystore, iOS Keychain or Secure Enclave) and never leaves the phone.
- **Before a mutual match nothing is encrypted**: your hello, card, photo and swipe could be read by
  Bluetooth equipment close by (Section 3).
- Messages waiting to be delivered are kept unencrypted in the App's private storage on your phone
  until the next connection to that friend, when they are encrypted and sent.
- Temporary IDs change every 15 minutes; advertisements carry no name or ID; the App limits how much
  data it accepts from each nearby phone.
- Data on your phone is protected by your phone's own lock-screen encryption; keep your phone locked.
- No system is perfectly secure. If we learn of a security flaw that affects users, we will fix it in
  an update and tell users as the law requires.

## 16. Changes to this policy

We will publish any change in the App and at [WEBSITE URL]/legal/privacy with a new version and
effective date. If a change is material — especially a new use of sensitive data, or any server —
we will show it in the App before it takes effect and, where required, ask for your consent again.

## 17. Contact

[LEGAL ENTITY NAME] (MetaVirtuoso), [ADDRESS] — [CONTACT EMAIL].
