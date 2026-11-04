# Conjunction — Dating consent strings and safety tips (for merge)

| | |
|---|---|
| Document | `DATING_CONSENT_AND_SAFETY` (merge source, not shipped on its own) |
| Version | 1.0 (draft of 2026-11-03) |
| Effective date | [EFFECTIVE DATE] |

This file holds the dating additions from SPEC section 5b, to be merged into `IN_APP_CONSENT_COPY.md`
(Part A) and `SAFETY_TIPS.md` (Part B). The keys and wording match `dating-drafts/PRIVACY.md`
(Sections 2, 4 and 5), `dating-drafts/WAIVER.md` (Sections 5.6–5.10) and `dating-drafts/TERMS.md`
(Section 4).

App rules: every checkbox starts **unticked**. Each consent below is **separate**: never bundle it with
the Terms or with another consent. On acceptance, record `{key, version, UTC timestamp, app version,
platform, SHA-256 of the displayed text}` (Waiver Section 19.2).

## Part A — In-app strings

### A1. Dating and friends profile, with the sexual-orientation consent (GDPR Art. 9 explicit consent)

| Key | Text |
|---|---|
| `dating_profile_title` | Who you'd like to meet |
| `dating_profile_i_am` | I am |
| `dating_profile_seeking` | Seeking |
| `dating_profile_status` | Relationship status |
| `dating_profile_looking_for` | Looking for |
| `option_male` / `option_female` / `option_both` | Male / Female / Both |
| `option_single` / `option_in_relationship` | Single / In a relationship |
| `option_dating` / `option_friends` / `option_dating_and_friends` | Dating / Friends / Both |
| `dating_profile_in_relationship_note` | People in a relationship are matched for friends only. |
| `orientation_consent_body` | Your gender and who you're seeking can reveal your sexual orientation. We use them, and your relationship status, only to find matches, and we show them on your match card. |
| `orientation_consent_checkbox` | I explicitly consent to Conjunction processing my gender, who I'm seeking and my relationship status for matching. |
| `orientation_consent_withdraw_note` | You can withdraw this in Settings → Privacy. Matching will turn off and we'll delete these details. |
| `honesty_checkbox` | My age, gender and relationship status are true. |
| `settings_withdraw_orientation` | Withdraw dating-profile consent |
| `settings_withdraw_orientation_confirm` | This turns off matching and deletes your gender, seeking and relationship status from our servers. |

Recorded keys: `CONSENT_ORIENTATION_DATA` (required before Nearby can be turned on; a user may skip the
whole screen and still use charts and readings), `PROFILE_HONESTY_CONFIRMED`.

Optional blood-type consent for matching (health-related data, also Art. 9):

| Key | Text |
|---|---|
| `blood_type_consent_checkbox` | Also use my blood type in Nearby compatibility scores. This sends it to our servers. |
| `blood_type_consent_note` | Blood type is health-related data. You can remove it any time in Settings. |

Recorded key: `CONSENT_BLOOD_TYPE_MATCHING`. If the box is not ticked, the blood type never leaves the
phone.

### A2. Nearby opt-in (dating and friends)

| Key | Text |
|---|---|
| `nearby_title` | Nearby Conjunctions |
| `nearby_body` | When you're within about 50 m of someone compatible who also uses Nearby, you'll both get a match card. |
| `nearby_point_location` | While Nearby is on, your phone sends its location to our servers about once a minute. We keep only the latest spot, for 10 minutes. It's never shown to anyone. |
| `nearby_point_card` | Matches see your photo, first name, age, signs, relationship status and what you're looking for, before either of you accepts. |
| `nearby_point_swipe` | Swipe right to accept, left to pass. Chat opens only when you both swipe right. |
| `nearby_point_safety` | We don't verify anyone. Meet in public and read the Safety Tips first. |
| `nearby_point_control` | Pause or turn off any time. Pause at home or work so strangers can't place you. |
| `nearby_checkbox` | I understand and want to turn on Nearby. |
| `nearby_turn_on` | Turn on Nearby |
| `nearby_not_now` | Not now |

Recorded key: `CONSENT_NEARBY_LOCATION`.

### A3. Profile-photo visibility notice

| Key | Text |
|---|---|
| `photo_title` | Your profile photo |
| `photo_body` | Your photo is checked by automated moderation. Once approved, it appears on your match card to nearby people before they accept you, and to your matches. |
| `photo_tip` | Use a recent photo of just you. Avoid house numbers, street signs or work badges. |
| `photo_pending` | Checking your photo… Your zodiac art shows until it's approved. |
| `photo_rejected` | This photo can't be used. Please choose another. |

### A4. Match card and chat moderation notice

| Key | Text |
|---|---|
| `match_card_accept` | Accept |
| `match_card_reject` | Pass |
| `match_card_accessibility_hint` | Swipe right to accept, left to pass, or use the buttons. |
| `match_card_entertainment_note` | Compatibility is for fun. It says nothing about safety. |
| `notif_matched` | It's a Conjunction! You and %1$s both said yes. Say hi. |
| `chat_first_open_notice` | Messages are checked by automated moderation before they're sent. Keep chatting here until you trust someone. |
| `chat_held` | This message is being reviewed. |
| `chat_blocked` | This message wasn't sent because it may break our Community Guidelines. |
| `chat_unmatch` | Unmatch |
| `chat_unmatch_confirm` | Unmatch %1$s? Your chat will be deleted for both of you. |
| `chat_block_report` | Block or report |

### A5. Short safety tips (dating)

| Key | Text |
|---|---|
| `safety_tip_photo_visible` | Your photo, first name and age show to nearby matches before they accept. |
| `safety_tip_sexual_health` | Talk about sexual health and consent. Use protection. |
| `safety_tip_no_verification` | We don't verify anyone's identity, age, gender or relationship status. |

## Part B — Safety Tips additions (full text)

**Replace** the first bullet under "Before you turn on Nearby" with:

- **Think about where you are.** A match card shows your approved photo, first name and age to someone
  within about 50 metres **before either of you accepts**, so they may recognise you in person. **Pause
  Nearby at home, at work, at your gym, or anywhere you would not want a stranger to know you go.**

**Add** this section after "Consent":

### Sexual health

- Any sexual activity is your choice. Talk about sexual health and boundaries **before** things get
  physical, use condoms or other protection, and get tested regularly. Ask a doctor or sexual-health
  clinic for advice; the App cannot give it.
- Remember that **we do not verify** anyone's age, identity, gender, relationship status or health.
