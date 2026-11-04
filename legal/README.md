# Conjunction — legal documents

**Attorney review required.** These are drafts written by an AI assistant, not a lawyer. A licensed
attorney in the publisher's jurisdiction must review and adapt them before the App is released. In
particular, waivers, arbitration clauses, dating-app safety obligations and the controller analysis
for peer-to-peer data vary a lot by state and country.

The documents describe the **serverless Bluetooth build** (SPEC section 5c): no servers, no
accounts, no location, no push, no server-side moderation. Phones exchange match cards and
end-to-end encrypted chats directly over Bluetooth Low Energy.

| File | Purpose | Shown in app |
|---|---|---|
| `WAIVER.md` | Release, Waiver of Liability, Assumption of Risk and Indemnity: entertainment-only readings, AI content, meeting strangers / dating, Bluetooth and data sent to other phones, driving, camera, 18+, release (incl. Cal. Civ. Code §1542), indemnity, no warranty, limitation of liability, US arbitration + class waiver + 30-day opt-out, non-waivable rights, clickwrap record | Onboarding (accept), Settings → Legal |
| `TERMS.md` | Terms of Service: no account, Nearby / matching / chat over Bluetooth, acceptable use, content licence, safety tools and reports without a server, termination, IP, DMCA, Apple minimum EULA terms, Google Play, changes | Onboarding (accept), Settings → Legal |
| `PRIVACY.md` | Privacy Policy: what stays on the phone, what is sent to nearby phones before and after a match, what reaches us (emails only), on-device screening, sensitive-data consents, retention on phones, deletion, rights | Onboarding (read), Settings → Legal, store listing URL |
| `COMMUNITY_GUIDELINES.md` | Rules of conduct, child-safety section (`#child-safety` anchor for Play), enforcement without a server | Onboarding (accept), Settings → Legal |
| `SAFETY_TIPS.md` | Meeting safely | Before first Nearby opt-in, from chat, Settings → Safety |
| `IN_APP_CONSENT_COPY.md` | Exact short strings (keys shared by Android and iOS): age gate, acceptance, dating-data and blood-type consents, Nearby / Bluetooth disclosure, permission rationales and Info.plist strings, block / report, settings | Everywhere |
| `archive/` | Superseded texts: `v1.0-server/` (server design) and `dating-drafts/` (merged). Not shipped. | — |

All documents are version `2.0` (draft of 2026-11-03). The apps record accepted versions on the
phone with a UTC timestamp, platform, app version and a SHA-256 of the displayed text (`WAIVER.md`
§19.2 and the "Rules for the apps" in `IN_APP_CONSENT_COPY.md`). Bump the version and effective date
whenever the text changes; a version bump triggers re-acceptance in the apps. The Android build
bundles the five top-level documents from this folder (`android/app/build.gradle.kts`
`copyLegalDocs`); the header table's `| Version | ... |` row is parsed by the apps, so keep that row's
format.

## Placeholders to fill before release

| Placeholder | Meaning |
|---|---|
| `[LEGAL ENTITY NAME]` | The person or company that publishes the App (MetaVirtuoso is the trade name) |
| `[ADDRESS]` | Postal address for legal notices. Apple requires a developer address in the EULA (Terms §12.2(h)). A business or registered-agent address can be used instead of a home address. |
| `[CONTACT EMAIL]` | Support / privacy / legal / **report** address. Reports arrive here by email, so it must be monitored daily (24 h response commitment). The store files currently use `themetavirtuoso@gmail.com`; a dedicated address on a domain is better. |
| `[EMAIL PROVIDER, ...]`, `[COUNTRY / EMAIL PROVIDER REGION]` | Who hosts that mailbox (PRIVACY §5, §14) |
| `[CHILD SAFETY CONTACT NAME]` | Named person for child-safety reports (COMMUNITY_GUIDELINES; Play Child Safety Standards) |
| `[EFFECTIVE DATE]` | Date the documents take effect |
| `[GOVERNING LAW STATE]`, `[GOVERNING LAW COUNTY]` | US state and county for governing law and venue |
| `[WEBSITE URL]` | Where the documents are hosted (both stores require a public privacy-policy URL) |
| `[EU REPRESENTATIVE ...]`, `[UK REPRESENTATIVE ...]` | GDPR Art. 27 representatives, if counsel says they are required |
| Bracketed retention values (`[2 years]`) | Retention of report and support emails |
| `[Engineering: ...]` / `[Engineering/Owner: ...]` / `[Counsel ...]` / `[Confirm ...]` notes | Facts to check against the final build or decide; remove each note once settled (see below) |

## Items for counsel and the owner to decide

1. **Controller analysis.** The publisher never receives Nearby or chat data, but designs how phones
   process and exchange it. Confirm whether the publisher is a controller (or joint controller) for
   that processing, how the household exemption applies to users, and whether a DPIA (GDPR Art. 35)
   is still advisable for broadcasting orientation-revealing data to nearby phones (likely yes).
2. **Pre-match broadcast of sensitive data.** Per `docs/P2P_PROTOCOL.md`, the `hello` carries gender,
   seeking (orientation), relationship status, chart longitudes, birth date/hour and optional blood
   type to **every** nearby phone with Nearby on; the card (first name, bio line, photo) goes only to
   phones where the match filter passed. Nothing is encrypted before a mutual match (protocol §8.6).
   The documents disclose this. Consider whether to encrypt the pre-match exchange (an ephemeral
   ECDH per link would stop passive sniffing, not a modified app).
3. **No ability to remove content or ban users.** Terms §7.3 says so plainly. Decide whether to build
   a blocked-identity-key list shipped in app updates (bracketed in Terms §7.3 and the Community
   Guidelines); store reviewers will ask how abusive users are dealt with.
4. **Arbitration.** AAA Consumer Rules are assumed (`WAIVER.md` §14). Confirm the provider, the
   mass-arbitration clause and the small-claims carve-out. Consider whether arbitration is worth having
   at all for a free app with no accounts.
5. **Felony / sex-offender representation** (`WAIVER.md` §2.3), as used by dating apps. Confirm
   enforceability.
6. **Dating-app safety laws.** Some US states have specific rules for online dating services (for
   example safety-awareness notices and disclosures about background checks). Confirm which apply to
   a serverless app with no accounts or payments, and the wording of the "we do not screen users"
   notice.
7. **Sensitive data.** Gender + seeking (sexual orientation) and blood type are treated as sensitive,
   with explicit consent. Confirm whether Washington's My Health My Data Act still needs a separate
   consumer-health-data page when the publisher never receives blood type.
8. **EU Digital Services Act.** With no server the App may not be a "hosting service"; confirm, and
   whether the email notice-and-action process in Terms §7 is enough.
9. **Export controls.** The chat uses end-to-end encryption (ECDH P-256, AES-256-GCM); see
   `store/appstore/APP_STORE_CONNECT.md` §7.
10. **DMCA agent.** Register a designated agent with the US Copyright Office before relying on Terms
    §9.3.
11. **Crisis and support lines** in `SAFETY_TIPS.md` are US-only; localise per store country.

## Facts the engineering agents must keep true

These documents promise the following; if the build differs, change the build or the documents before
release:

- No servers, accounts, Firebase, push, analytics, crash reporting, ads or advertising ID in either
  app. The only network traffic is the platform's own (geocoding, Gemini Nano model download / ML Kit
  diagnostics on Android). If anything is added, update `PRIVACY.md` and the store privacy answers
  first.
- Birth place and the minute of birth never leave the phone; readings, palm photos and
  `PalmFeatures` never leave the phone; no cloud LLM. Palm photos are deleted after the reading unless
  saved.
- **No location permission** on either platform. Android: `BLUETOOTH_SCAN` with `neverForLocation`;
  no `ACCESS_*_LOCATION` (if minSdk stays below 31, Nearby is disabled on Android 8–11 rather than
  adding location; see `store/play/PERMISSIONS.md` §1.1). iOS: no `NSLocation*` keys.
- Nearby is off by default; nothing is advertised or sent while it is off or paused. Advertisements
  carry only the service UUID. Temporary IDs rotate every 15 minutes and on every Nearby toggle.
- The `hello` and `card` send only the fields listed in `PRIVACY.md` §3 (matching `docs/P2P_PROTOCOL.md`
  §5.3); the card and photo only after the match filter passes; blood type only with
  `CONSENT_BLOOD_TYPE_MATCHING`; Nearby only with `CONSENT_ORIENTATION_DATA`. Hellos from non-matches
  are discarded, not stored. Peers under 18 are never matched (protocol §9 step 1).
- The match card shows only: photo (screened on the receiving phone, else blurred) or zodiac art,
  first name, age, Sun/Moon/rising, Chinese animal, relationship status, looking-for, match kind,
  compatibility. "Seeking", birth date, birth hour and blood type are never displayed.
- Left swipes are never revealed; left-swiped peers are hidden for 30 days; unanswered matches expire
  after 24 h.
- Chat is end-to-end encrypted (ECDH P-256 → HKDF-SHA256 → AES-256-GCM), stored only on the two
  phones, store-and-forward with the "waiting" state shown. Identity private key in Android Keystore /
  iOS Keychain, device-only.
- Incoming photos and messages are screened on the receiving phone; flagged content is hidden behind
  a warning, not silently dropped.
- Report = block + email to [CONTACT EMAIL], sent only by the user; "Report this reading" exists on
  every AI reading.
- "Delete all my data" wipes local storage, identity key and consent records.
- Acceptance records are stored on the phone with document versions, UTC timestamp, platform, app
  version and text hash, and viewable under Settings → Legal.
- The App's data is excluded from phone backups (Android `data_extraction_rules.xml` /
  `backup_rules.xml` include no app data; iOS marks Application Support/Conjunction
  `isExcludedFromBackup`). Queued messages have no expiry. "Delete all my data" exists on both
  platforms. The report email does not attach the photo, and its "Temporary ID" is the local profile
  fingerprint (protocol §7.4). These were checked against the code on 2026-11-03 and written into
  PRIVACY §2, §5, §10 and §11.
- Open `[Engineering: ...]` notes to resolve: the Android unblock screen (PRIVACY §10, Blocks row;
  iOS has one) and the blocked-key list (TERMS §7.3, COMMUNITY_GUIDELINES). If the protocol ever adds pre-match encryption or a remote unmatch, update
  PRIVACY §3, §10 and §15.
