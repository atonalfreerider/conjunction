# Conjunction — In-app consent and permission copy

| | |
|---|---|
| Document | `IN_APP_CONSENT_COPY` |
| Version | 1.0 (draft of 2026-11-03) |
| Effective date | [EFFECTIVE DATE] |

These are the exact strings the Android and iOS apps show. Both platforms use the same keys (Android
`strings.xml` name / iOS `Localizable.xcstrings` key) and the same wording. `%1$s`-style placeholders are
Android format; on iOS use `%@` / `%d` in the same order. Keep the wording identical on both platforms
so the consent record means the same thing.

**Rules for the apps**

- Checkboxes start **unticked**. The Continue button stays disabled until every required box is ticked.
- Each consent is **separate**; never bundle the dating-profile (orientation) consent or the blood-type
  consent with the Terms or with each other.
- When the user ticks a box and continues, record `{document or consent key, version, UTC timestamp,
  app version, platform, SHA-256 of the displayed text}` locally, and upload it with the account once
  one exists (Waiver Section 19.2, Privacy Policy Section 2).
- Show the system permission prompt **only after** the matching rationale screen below, and only when
  the user taps the button on it.
- Every link opens the full document in-app (Settings → Legal holds them all).
- Strings tagged *(store review)* may be read by Apple/Google reviewers; keep them accurate.

## 1. Age gate

| Key | Text |
|---|---|
| `age_gate_title` | Before we begin |
| `age_gate_body` | Conjunction is for adults 18 and over. Enter your date of birth. |
| `age_gate_dob_label` | Date of birth |
| `age_gate_continue` | Continue |
| `age_gate_blocked_title` | Sorry, not yet |
| `age_gate_blocked_body` | You must be 18 or older to use Conjunction. |
| `age_gate_dob_note` | We also use this as your birth date for your chart. |

The age gate's birth date pre-fills the birth-chart date. Under-18 dates block onboarding and are not
stored or uploaded. Do not let the user immediately retry with a different date on the same install
(store a local "blocked" flag).

## 2. Legal acceptance (waiver, terms, privacy)

| Key | Text |
|---|---|
| `legal_title` | The fine print, in plain words |
| `legal_summary_1` | Readings are for entertainment only — not medical, mental-health, financial, legal or relationship advice. |
| `legal_summary_2` | Readings are written by AI on your phone and can be wrong or odd. |
| `legal_summary_3` | We don't run background checks or verify anyone. You're responsible for your own safety when you chat with or meet people. |
| `legal_summary_4` | You give up some rights to sue us, and US disputes go to individual arbitration (no class actions) unless you opt out within 30 days. |
| `legal_summary_5` | Some rights can't be waived where you live; those stay with you. |
| `legal_read_waiver` | Read the Waiver |
| `legal_read_terms` | Read the Terms |
| `legal_read_privacy` | Read the Privacy Policy |
| `legal_checkbox_age` | I am 18 or older. |
| `legal_checkbox_agree` | I have read and agree to the Release, Waiver of Liability and Assumption of Risk, the Terms of Service and the Privacy Policy. |
| `legal_continue` | I agree — continue |
| `legal_reaccept_title` | We've updated our terms |
| `legal_reaccept_body` | Please review the changes to keep using %1$s. |

Recorded keys: `WAIVER`, `TERMS`, `PRIVACY` with their versions, plus `AGE_18_CONFIRMED`.

## 3. Birth data and blood type (on-device)

| Key | Text |
|---|---|
| `birth_privacy_note` | Your birth time and place stay on this phone. |
| `blood_type_title` | Blood type (optional) |
| `blood_type_body` | Used for blood-type readings, a fun East-Asian tradition — not medical. Stays on your phone. |
| `blood_type_consent_checkbox` | Also use my blood type in Nearby compatibility scores. This sends it to our servers. |
| `blood_type_consent_note` | Blood type is health-related data. You can remove it any time in Settings. |

Recorded key: `CONSENT_BLOOD_TYPE_MATCHING` (only if ticked; unticked means blood type never leaves the
phone).

## 4. Dating and friends profile — sexual-orientation consent

| Key | Text |
|---|---|
| `dating_profile_title` | Who you'd like to meet |
| `dating_profile_i_am` | I am |
| `dating_profile_seeking` | Seeking |
| `dating_profile_status` | Relationship status |
| `dating_profile_looking_for` | Looking for |
| `option_male` | Male |
| `option_female` | Female |
| `option_both` | Both |
| `option_single` | Single |
| `option_in_relationship` | In a relationship |
| `option_dating` | Dating |
| `option_friends` | Friends |
| `option_dating_and_friends` | Both |
| `dating_profile_in_relationship_note` | People in a relationship are matched for friends only. |
| `orientation_consent_body` | Your gender and who you're seeking can reveal your sexual orientation. We use them, and your relationship status, only to find matches, and show them on your match card. |
| `orientation_consent_checkbox` | I explicitly consent to Conjunction processing my gender, who I'm seeking and my relationship status for matching. |
| `orientation_consent_withdraw_note` | You can withdraw this in Settings → Privacy. Matching will turn off and we'll delete these details. |
| `honesty_checkbox` | My age, gender and relationship status are true. |

Recorded keys: `CONSENT_ORIENTATION_DATA` (required for Nearby; the user may skip this whole screen and
still use charts and readings), `PROFILE_HONESTY_CONFIRMED`.

## 5. Nearby opt-in (dating and friends)

| Key | Text |
|---|---|
| `nearby_title` | Nearby Conjunctions |
| `nearby_body` | When you're within about 50 m of someone compatible who also uses Nearby, you'll both get a match card. |
| `nearby_point_location` | While Nearby is on, your phone sends its location to our servers about once a minute. We keep only the latest spot, for 10 minutes. It's never shown to anyone. |
| `nearby_point_card` | Matches see your photo, first name, age, signs, relationship status and what you're looking for — before either of you accepts. |
| `nearby_point_swipe` | Swipe right to accept, left to pass. Chat opens only when you both swipe right. |
| `nearby_point_safety` | We don't verify anyone. Meet in public and read the Safety Tips first. |
| `nearby_point_control` | Pause or turn off any time. Pause at home or work so strangers can't place you. |
| `nearby_checkbox` | I understand and want to turn on Nearby. |
| `nearby_read_safety` | Read the Safety Tips |
| `nearby_turn_on` | Turn on Nearby |
| `nearby_not_now` | Not now |
| `nearby_switch_label` | Nearby |
| `nearby_switch_on_desc` | On — you can be matched with people close by |
| `nearby_switch_off_desc` | Off — no location is sent |
| `nearby_pause_1h` | Pause for 1 hour |
| `nearby_pause_tomorrow` | Pause until tomorrow |
| `nearby_fgs_notification_title` | Nearby is on |
| `nearby_fgs_notification_body` | Sharing your location to find matches close by. Tap to pause. |

Recorded key: `CONSENT_NEARBY_LOCATION` (and `SAFETY_TIPS` version, recorded when the Safety Tips sheet
is opened and dismissed with "Got it").

## 6. Profile photo visibility notice

| Key | Text |
|---|---|
| `photo_title` | Your profile photo |
| `photo_body` | Your photo is checked by automated moderation. Once approved, it appears on your match card to nearby people before they accept you, and to your matches. |
| `photo_tip` | Use a recent photo of just you. Avoid house numbers, street signs or work badges. |
| `photo_pending` | Checking your photo… Your zodiac art shows until it's approved. |
| `photo_rejected` | This photo can't be used. Please choose another. |
| `photo_add` | Add photo |
| `photo_change` | Change photo |
| `photo_remove` | Remove photo |

## 7. Chat notice

| Key | Text |
|---|---|
| `chat_first_open_notice` | Messages are checked by automated moderation before they're sent. Keep chatting here until you trust someone. |
| `chat_held` | This message is being reviewed. |
| `chat_blocked` | This message wasn't sent because it may break our Community Guidelines. |
| `chat_unmatch` | Unmatch |
| `chat_unmatch_confirm` | Unmatch %1$s? Your chat will be deleted for both of you. |
| `chat_block_report` | Block or report |

## 8. Permission rationales

Shown in an in-app screen before the system prompt. The Info.plist / system strings (*(store review)*)
must match.

| Key | Text |
|---|---|
| `perm_location_title` | Location for Nearby |
| `perm_location_body` | Nearby needs your location to find matches within about 50 m. Used only while Nearby is on. Never shown to other people. |
| `perm_location_button` | Allow location |
| `perm_location_denied` | Location is off, so Nearby can't find matches. You can allow it in Settings. |
| `NSLocationWhenInUseUsageDescription` *(store review)* | Conjunction uses your location only while Nearby is on, to find compatible people within about 50 m. It's never shown to other users. |
| `perm_camera_title` | Camera for palm reading |
| `perm_camera_body` | Take a photo of your palm for a reading. The photo is read on this phone and never uploaded. |
| `perm_camera_button` | Allow camera |
| `perm_camera_denied` | Camera is off. You can pick a photo instead, or allow the camera in Settings. |
| `NSCameraUsageDescription` *(store review)* | Conjunction uses the camera to photograph your palm for a reading on this phone, and to take a profile photo if you choose. |
| `NSPhotoLibraryUsageDescription` *(store review)* | Choose a palm photo for a reading or a profile photo. |
| `perm_notifications_title` | Notifications |
| `perm_notifications_body` | Get your daily horoscope and know when a match is nearby or someone messages you. |
| `perm_notifications_driving` | Never check matches while driving. |
| `perm_notifications_button` | Allow notifications |

## 9. Notifications and popups

| Key | Text |
|---|---|
| `notif_match_dating` | A %1$s ☌ is nearby — %2$d%% compatible. Tap to see. |
| `notif_match_friends` | A potential %1$s friend is nearby — %2$d%% compatible. |
| `notif_birthday_twin` | Birthday twin nearby! You share a birthday with someone close by. |
| `notif_exact_twin` | Exact twin! Same birthday, same hour — and they're nearby. |
| `notif_matched` | It's a Conjunction! You and %1$s both said yes. Say hi. |
| `notif_message` | New message from %1$s |
| `match_card_accept` | Accept |
| `match_card_reject` | Pass |
| `match_card_accessibility_hint` | Swipe right to accept, left to pass, or use the buttons. |
| `match_card_entertainment_note` | Compatibility is for fun. It says nothing about safety. |

## 10. Safety tips (short in-app versions)

Full text: `SAFETY_TIPS.md`.

| Key | Text |
|---|---|
| `safety_tip_title` | Stay safe |
| `safety_tip_emergency` | In danger? Call local emergency services first. |
| `safety_tip_no_checks` | We don't verify anyone's identity, age or status. |
| `safety_tip_chat_here` | Chat in the App first. Be wary if someone rushes you to another app. |
| `safety_tip_public` | Meet in a busy public place. Never at home for a first date. |
| `safety_tip_tell_friend` | Tell a friend where you'll be and share your live location. |
| `safety_tip_transport` | Get yourself there and back. |
| `safety_tip_drink` | Watch your drink and know your limits. |
| `safety_tip_money` | Never send money or financial details. |
| `safety_tip_sexual_health` | Talk about sexual health and consent. Use protection. |
| `safety_tip_leave` | You can leave any time. Trust your instincts. |
| `safety_tip_pause` | Pause Nearby at home and work. |
| `safety_tip_driving` | Don't use the App while driving. |
| `safety_tip_got_it` | Got it |

## 11. Settings → Privacy and account

| Key | Text |
|---|---|
| `settings_withdraw_orientation` | Withdraw dating-profile consent |
| `settings_withdraw_orientation_confirm` | This turns off matching and deletes your gender, seeking and relationship status from our servers. |
| `settings_remove_blood_type` | Stop using blood type for matching |
| `settings_export` | Export my data |
| `settings_delete_account` | Delete account |
| `settings_delete_confirm` | Permanently delete your account, matches, chats and photos? This can't be undone. |
| `settings_account_id` | Account ID: %1$s |
| `settings_arbitration_optout_hint` | To opt out of arbitration, email us within 30 days of agreeing. Include this account ID. |
