# Conjunction — legal documents

**These are drafts. They were written by an AI assistant, not a lawyer, and must be reviewed and adapted
by a licensed attorney in the publisher's jurisdiction before the App is released.** In particular,
waivers, arbitration clauses and dating-app safety obligations vary a lot by state and country.

| File | Purpose | Shown in app |
|---|---|---|
| `WAIVER.md` | Release, Waiver of Liability, Assumption of Risk and Indemnity Agreement: entertainment-only readings, AI content, meeting strangers / dating, location, driving, camera, 18+, release (incl. Cal. Civ. Code §1542), indemnity, no warranty, limitation of liability, US arbitration + class waiver + 30-day opt-out, non-waivable rights (EU/UK/Australia/Quebec/NJ), clickwrap record | Onboarding (accept), Settings → Legal |
| `TERMS.md` | Terms of Service: accounts, Nearby/matching/chat, acceptable use, content licence, moderation, termination, IP, DMCA, Apple minimum EULA terms, Google Play, changes | Onboarding (accept), Settings → Legal |
| `PRIVACY.md` | Privacy Policy matching the SPEC architecture (on-device vs uploaded data, processors, retention, deletion/export, GDPR/UK GDPR bases, CCPA/CPRA and state laws, consumer health data, children, transfers) | Onboarding (read), Settings → Legal, store listing URL |
| `COMMUNITY_GUIDELINES.md` | Rules of conduct and enforcement | Onboarding (accept), Settings → Legal |
| `SAFETY_TIPS.md` | Meeting safely | Before first Nearby opt-in, from chat, Settings → Safety |
| `IN_APP_CONSENT_COPY.md` | Exact short strings (keys shared by Android and iOS): age gate, acceptance checkbox, dating-data consent, Nearby / location / camera / notification rationales, Info.plist strings, block/report/delete | Everywhere |

All documents are version `1.0` (draft of 2026-11-03). The apps record the accepted versions with a UTC
timestamp, platform, app version and a SHA-256 of the displayed text (`WAIVER.md` §19.2 and the
"Rules for the apps" in `IN_APP_CONSENT_COPY.md`). Bump the version and effective date whenever the text changes; a version bump
triggers re-acceptance in the apps.

## Placeholders to fill before release

| Placeholder | Meaning |
|---|---|
| `[LEGAL ENTITY NAME]` | The person or company that publishes the App (MetaVirtuoso is the trade name) |
| `[ADDRESS]` | Postal address for legal notices. Apple requires a developer address in the EULA (Terms §12.2(h)). A business address or registered-agent address can be used instead of a home address. |
| `[CONTACT EMAIL]` | Support / privacy / legal email. (Sundial's store listings use the MetaVirtuoso Gmail address; a dedicated address on a domain is better for a dating-style app.) |
| `[EFFECTIVE DATE]` | Date the documents take effect |
| `[GOVERNING LAW STATE]`, `[GOVERNING LAW COUNTY]` | US state and county for governing law and venue |
| `[WEBSITE URL]` | Where the documents are hosted (Play and App Store both require a public privacy-policy URL) |
| `[ACCOUNT DELETION URL]` | Web page for deletion requests without the App (required by Google Play) |
| `[EU REPRESENTATIVE ...]`, `[UK REPRESENTATIVE ...]` | GDPR Art. 27 representatives, if counsel says they are required |
| Bracketed retention values in `PRIVACY.md` §6 (`[30 days]`, `[2 years]`, `[6 years]`) | Must match what the backend actually implements |
| `[Engineering: ...]` / `[confirm ...]` notes | Facts to check against the final build (backup exclusions, App Check, Firebase region, log retention, TypeSafe legal entity name) |

## Items for counsel and the owner to decide

1. **Arbitration.** AAA Consumer Rules are assumed (`WAIVER.md` §14). Confirm the provider, the mass-arbitration
   clause and the small-claims carve-out. Consider whether arbitration is worth having at all for a free app.
2. **Felony / sex-offender representation** (`WAIVER.md` §2.3), as used by dating apps. Confirm enforceability
   and whether to add any screening.
3. **Dating-app safety laws.** Some US states have specific rules for online dating services (for example
   safety-awareness notifications and disclosures about background checks). Confirm which apply, and whether
   the "we do not screen users" notice needs particular wording or placement.
4. **Sensitive data.** Gender + "Seeking" (can reveal sexual orientation), precise location, chat contents and
   blood type are treated as sensitive data, with explicit consent. Confirm whether a Data Protection Impact
   Assessment (GDPR Art. 35) is required (likely, for location-based matching of strangers), and whether
   Washington's My Health My Data Act requires a separate consumer-health-data policy for blood type.
5. **EU Digital Services Act.** Terms §7.5–7.6 include a basic notice-and-action and appeal process. Confirm
   the App's DSA obligations (small enterprises are exempt from some), and add a point of contact if needed.
6. **DMCA agent.** Register a designated agent with the US Copyright Office before relying on Terms §9.3.
7. **Crisis and support lines** in `SAFETY_TIPS.md` are US-only; localise per store country.

## Facts the engineering agents must keep true

These documents promise the following; if the build differs, change the build or the documents before
release:

- Birth data, readings, palm photos and `PalmFeatures` never leave the phone; no cloud LLM is used for
  readings. Palm photos are deleted after the reading unless saved.
- Nearby is off by default; location is sent only while it is on and not paused; only the latest position
  is kept, expired after 10 minutes, deleted immediately when Nearby is turned off; no location in
  encounters; no location or distance shown to users.
- The match card shows only: moderation-approved photo (else zodiac art), first name, age, Sun/Moon/rising,
  Chinese animal, relationship status, looking-for, match kind, compatibility. The "Seeking" value itself is
  never shown. Birth date, birth hour and blood type are never shown.
- Left swipes are never revealed; left-swiped pairs are suppressed for 30 days; unanswered matches stay in
  the inbox for 24 h.
- Every chat message, display name and bio is moderated by TypeSafe before other users see it; profile
  photos by Cloud Vision SafeSearch (no face recognition); uncertain cases are held for human review.
- Blood type is uploaded only with the "Include my blood type" toggle on (default off). Dating settings
  require a separate explicit-consent checkbox; withdrawing it (Settings → Privacy) turns matching off and
  deletes gender, seeking and relationship status server-side.
- No ads, no Firebase Analytics, no advertising ID, no cross-app tracking (so no ATT prompt). If Crashlytics
  or analytics is added, update `PRIVACY.md` §3.10 and the store privacy labels.
- In-app "Export my data" (JSON) and "Delete account" exist, plus a web deletion path.
- Acceptance records store document versions, UTC timestamp, platform, app version and text hash.

## Note on `dating-drafts/`

`dating-drafts/` holds working copies and merge sources made while the documents were updated for the
dating & friends layer (SPEC item 5b). The files at the top of `legal/` are the ones the apps ship; delete
`dating-drafts/` once its content has been merged (it should not ship or be bundled into the apps).
