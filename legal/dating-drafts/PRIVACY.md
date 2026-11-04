# Conjunction — Privacy Policy

| | |
|---|---|
| Document | `PRIVACY` |
| Version | 1.0 (draft of 2026-11-03) |
| Effective date | [EFFECTIVE DATE] |
| Controller | [LEGAL ENTITY NAME], doing business as MetaVirtuoso, [ADDRESS] |
| Contact (including privacy and data-protection requests) | [CONTACT EMAIL] |
| EU representative (GDPR Art. 27) | [EU REPRESENTATIVE NAME AND ADDRESS, or "not appointed" if an exemption applies] |
| UK representative (UK GDPR Art. 27) | [UK REPRESENTATIVE NAME AND ADDRESS, or "not appointed" if an exemption applies] |
| Apps | Conjunction for Android (`com.metavirtuoso.conjunction`) and iOS |

Conjunction ("the **App**") draws your birth chart, writes horoscopes and palm readings **on your
phone**, and — only if you turn it on — tells you when compatible people are nearby so you can match
for dating or friendship and chat. This policy explains what data the App uses, what stays on your
phone, what is sent to our servers, who processes it, how long we keep it and your rights.

**The short version**

- Your **birth time and place, your readings and your palm photos stay on your phone.** Readings are
  written by an AI model that runs on the phone itself; they are never sent to a cloud AI.
- **Nearby is off by default.** If you turn it on, we receive your phone's **precise location** about
  once a minute. We keep only the latest position, it **expires after 10 minutes**, it is **never
  shown to anyone**, and we keep no location history.
- If you use Nearby, other nearby users you are matched with see your **match card**: moderated
  profile photo, first name, **age**, signs, relationship status and whether you are looking for dating
  or friends — **before they accept you**.
- Your **gender, who you are seeking and your relationship status** can reveal your **sexual
  orientation**, and **blood type** is health-related. We process these only with your **explicit
  consent**, and only to find and show matches.
- **Chat messages** are stored on our servers so they can be delivered, and each one is checked by an
  automated moderation service (TypeSafe) before delivery.
- **No ads, no selling or sharing of your data for advertising, no data brokers.**
- You can **export** or **permanently delete** your data in the App at any time.

## 1. Data that stays on your phone

| Data | What it is used for |
|---|---|
| **Birth date, exact birth time, birth place** (and its coordinates and time zone) | Calculating your birth chart and Chinese zodiac / Four Pillars on the phone. |
| **Your full birth chart** (all planet positions, houses, aspects, Four Pillars) | Drawing the chart and writing readings. |
| **Readings** (daily, natal, Eastern, palm, compatibility blurbs) | Shown to you; generated on the phone. |
| **Palm photos** | Measured on the phone (hand landmarks and line detection) to describe your palm to the on-device AI. **Never uploaded.** Deleted after the reading unless you choose to save it in the App. |
| **App settings**, your acceptance records for our legal documents | Running the App. A copy of acceptance records is also kept with your account (Section 2). |

**On-device AI.** Readings are written by Gemini Nano through Android's AICore system service (Android)
or by Apple's on-device Foundation Models (iOS). Prompts and readings are processed on the phone and
are not sent to us, to Apple or to Google as part of generating the reading. Where the model is not
available, the App assembles a reading from text bundled in the App, also on the phone.

**Birth place search.** When you type your birth place, the App asks your phone's built-in geocoding
service (Apple's or Google's) to find its coordinates; your phone's operating system sends the search
text to Apple or Google for that purpose, under their privacy policies. We do not receive it.

**Android ML Kit diagnostics.** On Android, Google's ML Kit libraries (used for on-device AI and hand
detection) may send Google limited diagnostic and usage information — such as device model, Android
version, app package and version, performance metrics, error codes and a diagnostics identifier — to
operate and improve ML Kit. It does not include your birth details, photos or reading text. See
<https://developers.google.com/ml-kit/android-data-disclosure>.

**Device backups.** Your phone's own backup (iCloud, or Google backup on Android) may include the App's
local data if you have backup turned on. We have no access to those backups. Palm photos are excluded
from backups [confirm in implementation].

## 2. Data we receive and store on our servers

We receive data only for the features you use. If you use only charts and readings, we receive
nothing except what is described in Section 1 for Google's ML Kit. Everything below starts when you
create an account, which happens when you set up Nearby, a profile or chat.

| Category | Data | When | Purpose |
|---|---|---|---|
| **Account** | A random account ID; if you link it, your Sign in with Apple or Google Sign-In identifier and the email address that provider gives us (Apple may give a private relay address) | Account creation | Signing you in, restoring your account, security |
| **Legal acceptance records** | Document names and versions, UTC timestamps, App version, platform, a hash of the text shown, the consents you gave or withdrew | Onboarding and whenever you change a consent | Proving consent and acceptance (legal obligation; establishing and defending claims) |
| **Profile** | First / display name, profile photo, short bio | When you add them | Showing your match card and profile |
| **Age** | Your birth year (from your birth date) and the age calculated from it | When you turn on Nearby | Showing your age on your match card; enforcing the 18+ rule |
| **Dating and friends profile** — *special category / sensitive* | Gender (I am), seeking, relationship status, looking for (dating / friends / both) | When you set them | Deciding who you can be matched with; shown on your match card. **Can reveal your sexual orientation.** |
| **Chart summary** | Longitudes of Sun, Moon, Venus, Mars and (if birth time known) Ascendant; Chinese year animal and day-pillar stem; **birth date** and (if known) **birth hour** | When you turn on Nearby | Calculating compatibility on the server; spotting birthday twins. Not your birth place, minute or full chart. |
| **Blood type** — *special category / sensitive (health)* | A, B, AB or O and Rh, **only if you enter it and allow it to be used for matching** | When you allow it | Blood-type part of the compatibility score |
| **Location** — *sensitive (precise geolocation)* | Latitude and longitude, a geohash cell (about 150 m), and time of the reading | About every 60 s or 25 m of movement **while Nearby is on** | Finding opted-in users within about 50 m. See Section 3. |
| **Encounters and matches** | The two account IDs, compatibility score and sub-scores, match kind (dating or friends), birthday-twin flags, each side's swipe (right, left, none), times | When a match is found | Showing match cards, the Matches inbox, not re-showing a pair for 30 days |
| **Chat messages** | Message text, sender, time, delivery and read state, moderation result | When you send a message | Delivering messages, moderation and safety |
| **Safety records** | Blocks, snoozes, unmatches, reports (reason, your description, and the reported content and profile), moderation decisions, appeals | When they happen | Keeping users safe, enforcing our Terms, handling appeals |
| **Device and push** | Firebase Cloud Messaging token, platform, OS and App version, language, time zone | Account creation and updates | Sending notifications; compatibility of the service |
| **Security and service logs** | Server logs of requests (account ID, time, function called, errors, IP address as seen by our hosting provider), rate-limit counters | Every server request | Security, abuse prevention, debugging |
| **Support** | What you send us by email | When you contact us | Answering you |

We do **not** collect: contacts, call logs, microphone audio, advertising identifiers, health data
beyond the optional blood type, or browsing history. We do not use the App to track you across other
companies' apps or websites. [Confirm before release: whether Firebase Crashlytics or Analytics is
included in the builds. If it is, add it to this table and to the processor list; this draft assumes it
is not.]

## 3. Location in detail

- **Opt-in only.** Nearby is off until you turn it on. Your phone will also ask for location permission.
  You can pause Nearby (1 hour or until tomorrow) or turn it off at any time in the App, or revoke the
  permission in your phone's settings.
- **When it runs.** On Android, while Nearby is on the App runs a foreground service with a permanent
  notification. On iOS, it uses "While Using the App" location, and the blue location indicator is shown
  while the App receives location in the background. The App does not collect location when Nearby is
  off.
- **What we keep.** We keep **only your latest position**, overwriting the previous one. Each position
  is marked to expire **10 minutes** after it was received; expired positions are ignored immediately
  and are deleted automatically by our database's expiry process (normally within 24 hours of
  expiring). Turning off Nearby deletes your position immediately. **We keep no history of where you
  have been**, and encounter and match records do not contain any location.
- **Who sees it.** No other user and no app can read your position. Other users are told only that you
  are "nearby" (within about 50 m). However, because they are near you at the time and see your photo,
  they may recognise you in person.

## 4. Who can see what

| Who | What they see |
|---|---|
| **Nearby users you are matched with, before either of you accepts** | Your moderation-approved profile photo (or your sun-sign art if no photo is approved), first name, age, Sun / Moon / Rising signs, Chinese zodiac animal, relationship status, looking-for, the compatibility score and the match kind (dating or friends). Seeing a **dating** match card also tells them that your gender is one they seek and that you seek theirs. They do not see your exact location, birth date (other than that you are birthday twins, if you are), birth time or place, blood type, or bio. |
| **Your matches** (both swiped right) | The above, plus the first line of your bio and the chat messages you exchange. |
| **People you have not been matched with** | Nothing. Profile photos are served only through a server check that the viewer is matched with you or currently has your match card. |
| **Us** | What is described in Section 2, accessed only by authorised personnel when needed (for example to review a report or a held message). |

## 5. Why we use your data (legal bases)

If you are in the European Economic Area, the UK or Switzerland, we rely on these legal bases under
the GDPR / UK GDPR:

| Purpose | Legal basis |
|---|---|
| Providing the App's core features you ask for: account, profile, match cards, matching, chat, notifications | **Contract** (Art. 6(1)(b)) |
| Gender, seeking and relationship status (which may reveal **sexual orientation**), used to filter and show matches | **Explicit consent** (Art. 9(2)(a)), together with Art. 6(1)(a). Given by a separate checkbox; you can withdraw it at any time (see below). |
| Blood type, used in compatibility | **Explicit consent** (Art. 9(2)(a)), separate and optional |
| Precise location for Nearby | **Consent** (Art. 6(1)(a)) through the Nearby opt-in and your phone's permission; also necessary to provide Nearby (Art. 6(1)(b)). Location access on your phone is also subject to consent under the ePrivacy rules. |
| Moderation of names, bios, photos and chat messages; safety reviews; preventing abuse, fraud and spam; security logs | **Legitimate interests** (Art. 6(1)(f)) in keeping users safe and the service secure, which we consider not overridden by your interests given the safeguards here. Where moderated content is itself special-category data, Art. 9(2)(a) consent covers it as part of the feature you chose, and Art. 9(2)(f) applies where needed for legal claims. |
| Keeping acceptance and consent records; responding to lawful requests; tax and accounting | **Legal obligation** (Art. 6(1)(c)) and legitimate interests in establishing, exercising or defending legal claims (Art. 6(1)(f), Art. 9(2)(f)) |
| Responding to support requests | Contract or legitimate interests |

**Withdrawing consent.** You can withdraw consent at any time in **Settings → Privacy**: turning off
Nearby stops location; withdrawing the dating-profile consent deletes your gender, seeking and
relationship status from our servers and turns off matching (matching cannot work without them);
removing your blood type deletes it from our servers. Withdrawal does not affect processing that
already happened.

**Automated decisions.** Matching and compatibility scores are calculated automatically by a fixed
formula. They decide who receives a match card but do not produce legal or similarly significant
effects. Automated moderation may hold or block a message or photo; held items are reviewed by a
person, and you can ask for a human review of any moderation decision by emailing [CONTACT EMAIL].

## 6. Service providers (processors) and other recipients

We do not sell personal data and do not share it for cross-context behavioural advertising. We
disclose it only to:

| Recipient | Role | Data |
|---|---|---|
| **Google LLC — Firebase** (Authentication, Cloud Firestore, Cloud Functions, Cloud Storage, Cloud Messaging) | Hosting, database, server code, file storage, push notifications | Everything in Section 2 |
| **Google LLC — Cloud Vision SafeSearch** | Automated moderation of profile photos | Your profile photo |
| **Apple Inc. — Apple Push Notification service** (through Firebase Cloud Messaging), **Sign in with Apple** | Delivering notifications to iPhones; optional sign-in | Push token and notification content; sign-in identifier |
| **Google LLC — Google Sign-In** | Optional sign-in | Sign-in identifier and email |
| **TypeSafe** ([TYPESAFE LEGAL ENTITY NAME AND ADDRESS]) | Automated text moderation and triage of reports | Display names, bios, **each chat message** and report text, plus the minimum context needed to judge them (for example, that a message is sent between dating matches). Not your location or account identity. |
| **Other users** | As described in Section 4 | |
| **Authorities and others** | When we believe in good faith it is required by law, a court order or a lawful request, or necessary to protect someone from imminent death or serious harm, or to protect our rights | Only what is needed |
| **A successor** | If our business is merged, acquired or sold, under this policy | The data relevant to the App |

Our processors may use their own sub-processors under contract. [Before release: sign or accept the
Google Cloud / Firebase data processing terms and TypeSafe's Data Processing Agreement
(typesafe.ai/legal/data-processing); confirm TypeSafe's retention and model-training terms for
moderation inputs — request zero data retention if available — and update this row.]

## 7. How long we keep data

| Data | Retention |
|---|---|
| Location | Latest position only; expires after 10 minutes and is automatically deleted (normally within 24 hours of expiry); deleted immediately when you turn Nearby off |
| Unanswered match cards | Shown for 24 hours in the Matches inbox |
| Encounter records not leading to a match (including left swipes) | 30 days, then deleted (needed so the same pair is not shown again for 30 days) |
| Matches and chat messages | While the match exists. Deleted within 30 days after either of you unmatches, blocks, or deletes your account. |
| Profile, dating profile, chart summary, blood type | Until you change or remove them, withdraw consent, or delete your account |
| Profile photos rejected by moderation | Deleted within 30 days |
| Reports, the content attached to them, moderation decisions, bans | Up to 2 years after the report is closed, or longer if needed for a legal claim or law-enforcement request; then deleted |
| Blocks | Until your account is deleted |
| Legal acceptance and consent records | For the life of your account and up to [6] years after it closes, to establish or defend legal claims |
| Server and security logs | Up to 30 days [confirm hosting provider defaults] |
| Accounts inactive for 2 years | Deleted, after we notify you in the App where possible |

## 8. Deleting and exporting your data

- **Delete account** (**Settings → Delete account**): permanently erases your account, profile,
  photos, dating profile, chart summary, blood type, location, encounters, matches and chat messages
  from our active systems immediately, and from backups within [35] days. Your messages disappear from
  your matches' chats. We keep only what Section 7 says we must (for example, a report someone else
  made about you, and acceptance records), and a one-way hash of your sign-in identifier so that a
  banned account cannot simply re-register. You can also request deletion without the App at
  [ACCOUNT DELETION URL] or by emailing [CONTACT EMAIL].
- **Delete data on your phone:** clear the App's data or uninstall it. We cannot recover local data.
- **Export** (**Settings → Export my data**): downloads a machine-readable (JSON) copy of the data we
  hold about you, and your acceptance records.

## 9. Your rights

Wherever you live, you can ask us to access, correct, export or delete your data, and you can withdraw
consent at any time. Most of this you can do directly in the App. For anything else, email
[CONTACT EMAIL]. We will verify your request (usually by asking you to send it from inside the App or
from the linked sign-in email) and answer within one month (GDPR) or 45 days (US state laws), which may
be extended where the law allows. We will not discriminate against you for exercising your rights.

**EEA, UK and Switzerland.** You have the rights of access, rectification, erasure, restriction,
portability, objection (including to processing based on legitimate interests), and the right not to be
subject to solely automated decisions with legal or similarly significant effects. You may complain to
your local data-protection authority (in the UK, the Information Commissioner's Office), though we
would appreciate the chance to address your concern first.

**California (CCPA as amended by the CPRA)** and other US states with comprehensive privacy laws
(including Colorado, Connecticut, Virginia, Texas, Oregon and others):

- *Categories collected* in the last 12 months: identifiers (account ID, push token, sign-in email);
  personal records (name, photo); characteristics of protected classifications (age, sex/gender);
  geolocation data; inferences (compatibility scores); internet or electronic network activity (logs);
  and **sensitive personal information**: **precise geolocation**, information concerning **sex life
  or sexual orientation** (gender + seeking), **health** (optional blood type) and **the contents of
  messages** (chat, which we process to deliver and moderate them). Sources: you, your device, and
  other users (reports). Purposes and recipients: Sections 2, 5 and 6. Retention: Section 7.
- We **do not sell or share** personal information (as "sell" and "share" are defined in the CCPA),
  and have not in the past 12 months. We do not knowingly collect data of anyone under 18.
- We use sensitive personal information only to provide the services you request and for the other
  purposes permitted by Cal. Code Regs. tit. 11, § 7027(m), so the "right to limit" does not apply; in
  states that require it, we process sensitive data only with your **opt-in consent**.
- You have the right to know, access, correct and delete, to data portability, and to opt out of sale,
  sharing, targeted advertising and profiling (we do none of these). You may use an authorised agent.
  If we deny a request you may appeal by replying to our decision; if the appeal is denied you may
  contact your state Attorney General.
- **Washington (My Health My Data Act) and Nevada:** to the extent your optional blood type is
  "consumer health data", we collect it only with your consent, use it only for the compatibility
  feature, do not sell it, and you can delete it at any time. [Counsel to confirm whether a separate
  Consumer Health Data Privacy Policy page is required.]

**Biometric information.** The App does **not** collect biometric identifiers. Hand landmarks in palm
photos are measured only on your phone to describe palm features for a reading, are not used to
identify you, and are never sent to us. Profile photos are checked for prohibited content, not used
for face recognition.

## 10. Children

The App is **for adults 18 and over only**. We do not knowingly collect personal data from anyone under
18. The App asks for your date of birth and blocks anyone under 18. If we learn that an account belongs
to someone under 18, we will delete it. If you believe a minor is using the App, report the profile in
the App or email [CONTACT EMAIL].

## 11. International transfers

We are based in the United States, and our servers are operated by Google Cloud / Firebase in
[FIREBASE REGION, e.g. us-central1]. If you use the App from outside the US, your data is transferred to
the US and possibly other countries where our processors operate. Where the GDPR or UK GDPR applies, we
rely on the European Commission's Standard Contractual Clauses (with the UK Addendum), and, where a
recipient is certified, the EU–US Data Privacy Framework and its UK and Swiss extensions, together with
supplementary measures such as encryption in transit and at rest. You can ask us for a copy of the
relevant safeguards.

## 12. Security

Data is encrypted in transit (TLS) and at rest by our hosting provider. Access to the database is
controlled by server-side rules: no app can read another user's location, and photos are served only
after a server check. Our moderation key and other secrets are kept on the server, never in the App.
Access by our staff is limited and logged. No system is perfectly secure; if a breach affects your data
we will notify you and regulators as the law requires.

## 13. Changes to this policy

We will post any change in the App and at [WEBSITE URL]/legal/privacy with a new version and effective
date. If a change is material — especially a new use of sensitive data — we will tell you in the App
before it takes effect and, where required, ask for your consent again.

## 14. Contact

[LEGAL ENTITY NAME] (MetaVirtuoso), [ADDRESS] — [CONTACT EMAIL].
