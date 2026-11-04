# Conjunction — In-app consent and permission copy

| | |
|---|---|
| Document | `IN_APP_CONSENT_COPY` |
| Version | 2.0 (draft of 2026-11-03) |
| Effective date | [EFFECTIVE DATE] |

These are the exact strings the Android and iOS apps show. Both platforms use the same keys (Android
`strings.xml` name / iOS `Localizable.xcstrings` key) and the same wording. `%1$s`-style placeholders are
Android format; on iOS use `%@` / `%d` in the same order. Keep the wording identical on both platforms
so the consent record means the same thing.

Version 2.0 describes the **serverless Bluetooth build** (SPEC 5c): no account, no server, no
location, no push. Version 1.0 (server design) is in `archive/v1.0-server/`.

**Rules for the apps**

- Checkboxes start **unticked**. The Continue button stays disabled until every required box is ticked.
- Each consent is **separate**; never bundle the dating-profile (orientation) consent, the blood-type
  consent or the Nearby consent with the Terms or with each other.
- When the user ticks a box and continues, record `{document or consent key, version, UTC timestamp,
  app version, platform, SHA-256 of the displayed text}` **on the phone** (Waiver Section 19.2). There
  is no server to upload it to; Settings → Legal shows the records.
- Show the system permission prompt **only after** the matching rationale screen below, and only when
  the user taps the button on it.
- Every link opens the full document in-app (Settings → Legal holds them all).
- Strings tagged *(store review)* may be read by Apple/Google reviewers; keep them accurate.
- Removed in 2.0 (do not ship): every `perm_location_*` string, `NSLocationWhenInUseUsageDescription`,
  `photo_pending`, `photo_rejected`, `chat_held`, `chat_blocked`, `settings_export`,
  `settings_delete_account`, `settings_delete_confirm`, `settings_account_id`, and the consent key
  `CONSENT_NEARBY_LOCATION` (replaced by `CONSENT_NEARBY_BLUETOOTH`).

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
stored. Do not let the user immediately retry with a different date on the same install (store a local
"blocked" flag). The birth date used for the age shown on the Nearby card must be the same one (do not
let the user change it to an adult date without going through the age gate again). The App also
refuses incoming cards whose age or birth date is under 18.

## 2. Legal acceptance (waiver, terms, privacy)

| Key | Text |
|---|---|
| `legal_title` | The fine print, in plain words |
| `legal_summary_1` | Readings are for entertainment only — not medical, mental-health, financial, legal or relationship advice. |
| `legal_summary_2` | Readings are written by AI on your phone and can be wrong or odd. |
| `legal_summary_3` | We don't run background checks or verify anyone. You're responsible for your own safety when you chat with or meet people. |
| `legal_summary_6` | There's no Conjunction server. Nearby talks straight to nearby phones over Bluetooth, and what you send stays on their phones. |
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

Show the summaries in the order 1, 2, 3, 6, 4, 5 (`legal_summary_6` was added in 2.0; the key names
are kept stable). Recorded keys: `WAIVER`, `TERMS`, `PRIVACY` with their versions, plus
`AGE_18_CONFIRMED`.

## 3. Birth data and blood type

| Key | Text |
|---|---|
| `birth_privacy_note` | Your birth time and place stay on this phone. |
| `blood_type_title` | Blood type (optional) |
| `blood_type_body` | Used for blood-type readings, a fun East-Asian tradition — not medical. Stays on your phone. |
| `blood_type_consent_checkbox` | Also use my blood type in Nearby compatibility. My phone then sends it to every nearby phone with Nearby on, not only to matches. |
| `blood_type_consent_note` | Blood type is health-related data. You can stop sending it any time in Settings. |

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
| `orientation_consent_body` | Your gender and who you're seeking can reveal your sexual orientation. With Nearby on, your phone sends them, and your relationship status, to nearby phones running Conjunction so their app can check whether you match. Matches see your relationship status on your card. |
| `orientation_consent_checkbox` | I explicitly consent to Conjunction sending my gender, who I'm seeking and my relationship status to nearby phones for matching. |
| `orientation_consent_withdraw_note` | You can withdraw this in Settings → Privacy. Nearby will turn off and your phone stops sending these details. Phones that already received them keep their copies. |
| `honesty_checkbox` | My age, gender and relationship status are true. |

Recorded keys: `CONSENT_ORIENTATION_DATA` (required for Nearby; the user may skip this whole screen and
still use charts and readings), `PROFILE_HONESTY_CONFIRMED`.

## 5. Nearby opt-in (Bluetooth)

This screen is the **prominent disclosure** for both stores. It must appear before the Bluetooth /
"Nearby devices" permission prompt and before Nearby first turns on.

| Key | Text |
|---|---|
| `nearby_title` | Nearby Conjunctions |
| `nearby_body` | Nearby uses Bluetooth to find people close by who also turned Nearby on. When one of them is compatible, you both get a match card. There's no server: phones talk to each other directly. |
| `nearby_point_bluetooth` | While Nearby is on, your phone uses Bluetooth, even when Conjunction is closed. It doesn't use your location, and no one sees a distance. |
| `nearby_point_hello` | To check for a match, your phone sends every nearby phone with Nearby on: your gender and who you seek, relationship status, what you're looking for, chart positions, birth date and birth hour, and your blood type if you chose to add it. Never your name, birth place, birth minute or location. These details together are close to unique, so someone who keeps scanning near you could recognise you again. |
| `nearby_point_card` | If you match, it also sends your first name, bio line and photo. They see your card before either of you accepts: photo, first name, age, signs, Chinese animal, relationship status and what you're looking for. |
| `nearby_point_keep` | This isn't encrypted until you both accept, and we can't control other phones: a modified app could keep what you send. Only add what you're happy for strangers nearby to see. |
| `nearby_point_swipe` | Swipe right to accept, left to pass. Chat opens only when you both swipe right. Chats are end-to-end encrypted and stay on your two phones. |
| `nearby_point_safety` | We don't verify anyone. Meet in public and read the Safety Tips first. |
| `nearby_point_control` | Pause or turn off any time. Pause at home or work so strangers can't place you. |
| `nearby_checkbox` | I understand and want to turn on Nearby. |
| `nearby_read_safety` | Read the Safety Tips |
| `nearby_turn_on` | Turn on Nearby |
| `nearby_not_now` | Not now |
| `nearby_switch_label` | Nearby |
| `nearby_switch_on_desc` | On — Bluetooth finds people close by |
| `nearby_switch_off_desc` | Off — nothing is sent |
| `nearby_pause_1h` | Pause for 1 hour |
| `nearby_pause_tomorrow` | Pause until tomorrow |
| `nearby_fgs_notification_title` | Nearby is on |
| `nearby_fgs_notification_body` | Using Bluetooth to find matches close by. Tap to pause. |
| `nearby_bluetooth_off` | Bluetooth is off. Turn it on to use Nearby. |
| `nearby_ios_background_note` | On iPhone, keep Conjunction open to be found by Android phones. In the background, other iPhones can still find you. |
| `nearby_android_old_version` | Nearby needs Android 12 or later. Your charts and readings work as usual. |

Recorded key: `CONSENT_NEARBY_BLUETOOTH` (and `SAFETY_TIPS` version, recorded when the Safety Tips
sheet is opened and dismissed with "Got it"). `nearby_ios_background_note` is iOS only (Apple keeps
a backgrounded iPhone's advertisement in an "overflow area" that only iPhones can see).
`nearby_android_old_version` is shown only if the app keeps minSdk below 31 and disables Nearby there
(see `store/play/PERMISSIONS.md` §1.1).

## 6. Profile photo visibility notice

| Key | Text |
|---|---|
| `photo_title` | Your profile photo |
| `photo_body` | With Nearby on, your photo is sent to people you match with nearby, before either of you accepts, and friends keep it. Their phone checks it before showing it. |
| `photo_tip` | Use a recent photo of just you. Avoid house numbers, street signs or work badges. |
| `photo_add` | Add photo |
| `photo_change` | Change photo |
| `photo_remove` | Remove photo |
| `photo_hidden` | Photo hidden — it couldn't be checked on this phone. |
| `photo_show` | Show photo |
| `photo_flagged` | This photo may break our Community Guidelines. |

## 7. Chat notice

| Key | Text |
|---|---|
| `chat_first_open_notice` | Messages are end-to-end encrypted and go straight from phone to phone. They arrive when you're near each other. This phone warns you about messages that look harmful. |
| `chat_queued` | Waiting — delivered next time you're near %1$s. |
| `chat_flagged` | Hidden: this message may break our Community Guidelines. |
| `chat_flagged_show` | Show anyway |
| `chat_keep_note` | %1$s keeps a copy of everything you send. |
| `chat_unmatch` | Unmatch |
| `chat_unmatch_confirm` | Unmatch %1$s? The chat is deleted from this phone. %1$s keeps their copy. |
| `chat_block_report` | Block or report |

## 8. Block and report

| Key | Text |
|---|---|
| `block_title` | Block %1$s? |
| `block_body` | They won't be matched with you or able to message you from this phone again. Your chat with them is deleted from this phone. Take screenshots first if you may need evidence. |
| `block_confirm` | Block |
| `report_title` | Report %1$s |
| `report_body` | Reporting blocks %1$s and opens an email to our support team with their card and your reason. Nothing is sent until you press Send. We aim to reply within 24 hours. |
| `report_reason_label` | What happened? |
| `report_reason_harassment` | Harassment or threats |
| `report_reason_sexual` | Sexual content or unwanted advances |
| `report_reason_scam` | Scam, money or spam |
| `report_reason_fake` | Fake profile or wrong age |
| `report_reason_hate` | Hate or discrimination |
| `report_reason_minor` | Child safety / possible minor |
| `report_reason_other` | Something else |
| `report_include_messages` | Include our messages in the email |
| `report_open_email` | Block and write email |
| `report_no_email_app` | No email app found. Email %1$s — we've copied the report so you can paste it. |
| `report_emergency` | In danger? Call local emergency services first. |
| `report_reading` | Report this reading |
| `report_reading_body` | Opens an email to us with this reading and your reason. Your birth details aren't included. |

The email (to [CONTACT EMAIL], subject "Conjunction report: <reason>") contains the reported card
(first name, age, Sun / Moon / Rising signs, match kind, whether the card had a photo, the local
"Temporary ID" code, and the safety code if they were friends), the reason, a description field, the
app version and platform and, only if `report_include_messages` is ticked, the chat text. The photo
itself is **not** attached (the body says "attach a screenshot if relevant"). The "Temporary ID" in
the email is the reporting phone's local profile fingerprint of that person (`docs/P2P_PROTOCOL.md`
§7.4: a hash of their `hello.compat`), not the rotating Bluetooth id, so it stays the same if the
same person is reported again (Android `nearby/ReportEmail.kt`, iOS `ConjunctionCore/Nearby/NearbyText.swift`). iOS uses the in-app mail sheet (`MFMailComposeViewController`) where available.

## 9. Permission rationales

Shown in an in-app screen before the system prompt. The Info.plist strings (*(store review)*) must
match `store/appstore/INFO_PLIST.md`.

| Key | Text |
|---|---|
| `perm_bluetooth_title` | Bluetooth for Nearby |
| `perm_bluetooth_body` | Nearby uses Bluetooth to find people close by who also use Nearby, and to send them your card and messages directly. It doesn't use your location. |
| `perm_bluetooth_android_note` | Android calls this "Nearby devices". |
| `perm_bluetooth_button` | Allow Bluetooth |
| `perm_bluetooth_denied` | Bluetooth access is off, so Nearby can't find anyone. You can allow it in Settings. |
| `NSBluetoothAlwaysUsageDescription` *(store review)* | Conjunction uses Bluetooth only while Nearby is on, to find people close by who also turned Nearby on and to exchange match cards and encrypted messages directly with their phones. It doesn't use your location. |
| `perm_camera_title` | Camera for palm reading |
| `perm_camera_body` | Take a photo of your palm for a reading. The photo is read on this phone and never sent anywhere. |
| `perm_camera_button` | Allow camera |
| `perm_camera_denied` | Camera is off. You can pick a photo instead, or allow the camera in Settings. |
| `NSCameraUsageDescription` *(store review)* | Conjunction uses the camera to photograph your palm for a reading on this phone, and to take a profile photo if you choose. |
| `NSPhotoLibraryUsageDescription` *(store review)* | Choose a palm photo for a reading or a profile photo. |
| `perm_notifications_title` | Notifications |
| `perm_notifications_body` | Get your daily horoscope, and know when a match is nearby or a friend's message arrives. Notifications are made on this phone. |
| `perm_notifications_driving` | Never check matches while driving. |
| `perm_notifications_button` | Allow notifications |

`NSPhotoLibraryUsageDescription` ships only if the app uses full photo-library access; the system
photo pickers need no permission (see `store/appstore/INFO_PLIST.md`).

## 10. Notifications and popups

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

All notifications are local (made by the app on the phone); there is no push service.

## 11. Safety tips (short in-app versions)

Full text: `SAFETY_TIPS.md`.

| Key | Text |
|---|---|
| `safety_tip_title` | Stay safe |
| `safety_tip_emergency` | In danger? Call local emergency services first. |
| `safety_tip_no_checks` | We don't verify anyone's identity, age, gender or relationship status. |
| `safety_tip_photo_visible` | Your photo, first name and age show to nearby matches before they accept. |
| `safety_tip_they_keep` | What you send stays on their phone. No one can delete it from there. |
| `safety_tip_chat_here` | Chat in the App first. Be wary if someone rushes you to another app. |
| `safety_tip_public` | Meet in a busy public place. Never at home for a first date. |
| `safety_tip_tell_friend` | Tell a friend where you'll be and share your live location. |
| `safety_tip_transport` | Get yourself there and back. |
| `safety_tip_drink` | Watch your drink and know your limits. |
| `safety_tip_money` | Never send money or financial details. |
| `safety_tip_sexual_health` | Talk about sexual health and consent. Use protection. |
| `safety_tip_evidence` | Screenshot anything worrying before you block. |
| `safety_tip_leave` | You can leave any time. Trust your instincts. |
| `safety_tip_pause` | Pause Nearby at home and work. |
| `safety_tip_driving` | Don't use the App while driving. |
| `safety_tip_got_it` | Got it |

## 12. Settings → Privacy and data

| Key | Text |
|---|---|
| `settings_withdraw_orientation` | Withdraw dating-profile consent |
| `settings_withdraw_orientation_confirm` | This turns off Nearby, and your phone stops sending your gender, seeking and relationship status. Phones that already received them keep their copies. |
| `settings_remove_blood_type` | Stop sending blood type to nearby phones |
| `settings_no_server_note` | Conjunction has no server and no account. Your data is on this phone, and what you sent to friends is on theirs. |
| `settings_delete_data` | Delete all my data |
| `settings_delete_data_confirm` | Delete your profile, friends, chats, photos and settings from this phone? This can't be undone, and we can't restore it. Friends keep the copies on their phones. |
| `settings_legal_records` | My acceptance records |
| `settings_arbitration_optout_hint` | To opt out of arbitration, email us within 30 days of agreeing. Tap to prepare the email. |
| `settings_contact` | Contact us: %1$s |

`settings_delete_data` wipes the app's local storage, the identity key and the consent records, then
returns to onboarding. `settings_arbitration_optout_hint` opens an email to [CONTACT EMAIL] with the
subject "Arbitration Opt-Out" and the date the Waiver was accepted.
