# Conjunction — In-app consent and permission copy

| | |
|---|---|
| Document | `IN_APP_CONSENT_COPY` |
| Version | 2.1 (draft of 2026-11-04) |
| Effective date | [EFFECTIVE DATE] |

These are the exact strings the Android and iOS apps show. Both platforms use the same keys (Android
`strings.xml` name / iOS `Localizable.xcstrings` key) and the same wording. `%1$s`-style placeholders are
Android format; on iOS use `%@` / `%d` in the same order. Keep the wording identical on both platforms
so the consent record means the same thing.

Version 2.0 describes the **serverless Bluetooth build** (SPEC 5c): no account, no server, no
location, no push. Version 1.0 (server design) is in `archive/v1.0-server/`.

**Version 2.1 (UX 2.0, SPEC 5d.1)** cuts the legal UI to four places: one clickwrap checkbox on the
birth-date screen (§1), one Nearby consent sheet the first time Nearby is turned on (§5), one inline
blood-type switch (§3) and a compact sheet when documents change (§13). Everything else is in
Profile › Settings › Privacy & legal (§12). The copy version is **not** a re-acceptance trigger:
records made under 2.0 stay valid (see "Existing records" below).

**Rules for the apps**

- Checkboxes and switches start **unticked / off**. A button that a consent unlocks stays disabled
  until it is ticked.
- **Consents that must stay separate** (never bundled with the Terms or with each other):
  1. the **Nearby consent** (§5) — one checkbox that covers both sending orientation-revealing
     dating details (GDPR Art. 9 explicit consent) and turning on Bluetooth Nearby, because both serve
     the single purpose "Nearby matching";
  2. the **blood-type switch** (§3) — optional health data for an extra purpose.
  The Terms / Waiver / Community Guidelines acceptance (§1) is a contract, not a consent; the
  Privacy Policy is linked as "read", never "agree".
- When the user ticks a box / turns a switch on and continues, record `{document or consent key,
  version, UTC timestamp, app version, platform, SHA-256 of the displayed text}` **on the phone**
  (Waiver Section 19.2). For sentences with link placeholders, hash the **formatted** sentence as
  shown. There is no server; Settings › Privacy & legal › My acceptance records shows the records.
- Show the system Bluetooth ("Nearby devices") permission prompt **only after** the user taps
  **Turn on** on the Nearby sheet (§5): the sheet is the prominent disclosure. No other rationale
  dialog is shown for Bluetooth.
- Every document link opens the full document in-app (Settings › Privacy & legal holds them all).
  Links are gold and underlined, at body text size.
- Strings tagged *(store review)* may be read by Apple/Google reviewers; keep them accurate.
- **Removed in 2.0** (do not ship): every `perm_location_*` string,
  `NSLocationWhenInUseUsageDescription`, `photo_pending`, `photo_rejected`, `chat_held`,
  `chat_blocked`, `settings_export`, `settings_delete_account`, `settings_delete_confirm`,
  `settings_account_id`, and the consent key `CONSENT_NEARBY_LOCATION` (replaced by
  `CONSENT_NEARBY_BLUETOOTH`).
- **Retired in 2.1** (removed from the generated strings; delete their uses in the same change):
  `age_gate_title`, `age_gate_body`, `age_gate_dob_label`, `age_gate_continue`, `age_gate_dob_note`, `legal_title`,
  `legal_summary_1`, `legal_summary_2`, `legal_summary_3`, `legal_summary_4`, `legal_summary_5`,
  `legal_summary_6`, `legal_read_waiver`, `legal_read_terms`,
  `legal_read_privacy`, `legal_checkbox_age`, `legal_checkbox_agree`, `legal_continue`,
  `blood_type_body`, `blood_type_consent_checkbox`, `blood_type_consent_note`,
  `orientation_consent_body`, `orientation_consent_checkbox`, `orientation_consent_withdraw_note`,
  `honesty_checkbox`, `nearby_body`, `nearby_checkbox`, `nearby_read_safety`, `photo_body`,
  `photo_tip`, `chat_keep_note`, `perm_bluetooth_title`, `perm_bluetooth_body`,
  `perm_bluetooth_android_note`, `perm_bluetooth_button`, `perm_notifications_driving`,
  `settings_withdraw_orientation`, `settings_withdraw_orientation_confirm`,
  `settings_remove_blood_type`. Record key no longer written: `PROFILE_HONESTY_CONFIRMED` (honesty is
  part of the Terms §5(e),(g) and Waiver §20(5), accepted in §1).
- **Existing records** (made under copy 2.0) stay valid and readable:
  `WAIVER`/`TERMS`/`PRIVACY` 2.0 and `AGE_18_CONFIRMED` = accepted (a missing
  `COMMUNITY_GUIDELINES` record is covered by the accepted Terms, which include the Guidelines);
  `CONSENT_ORIENTATION_DATA` **and** `CONSENT_NEARBY_BLUETOOTH`, both present and not withdrawn =
  the §5 Nearby consent given; `CONSENT_BLOOD_TYPE_MATCHING` = the §3 switch on. Existing users are
  never sent back through onboarding.

## 1. Birth date, age and acceptance (onboarding screen 2)

The birth date entered for the chart is the age gate (a neutral age screen: no date is
pre-selected and the 18+ cut-off is not announced before the date is entered). The checkbox line
and the privacy line sit directly above the **Begin** button, visible without scrolling. The
Welcome screen's button is "Get started", so **Begin** is only ever the agreement button.

| Key | Text |
|---|---|
| `age_gate_blocked_title` | Sorry, not yet |
| `age_gate_blocked_body` | You must be 18 or older to use Conjunction. |
| `legal_link_terms` | Terms |
| `legal_link_guidelines` | Community Guidelines |
| `legal_link_waiver` | Waiver |
| `legal_link_privacy` | Privacy Policy |
| `legal_accept_checkbox` | I'm 18+ and agree to the %1$s, %2$s and %3$s, including a release of claims and individual arbitration. |
| `legal_privacy_line` | See how your data stays on your phone: %1$s |
| `legal_begin` | Begin |

`legal_accept_checkbox` takes `legal_link_terms`, `legal_link_guidelines`, `legal_link_waiver` (each a
link); `legal_privacy_line` takes `legal_link_privacy` (a link). The checkbox starts unticked; Begin
is enabled only when it is ticked, the date is valid and the user is 18 or older, and the birth place
is resolved.

Recorded keys when Begin is tapped: `WAIVER`, `TERMS`, `COMMUNITY_GUIDELINES` (accepted), each with
its document version, the SHA-256 of the formatted `legal_accept_checkbox` text (`textSha256`) and the
SHA-256 of the full bundled document (`docSha256`); `PRIVACY_NOTICE_SEEN` (read, not agreed) with the
Privacy Policy version, the SHA-256 of the formatted `legal_privacy_line` and the policy's
`docSha256`; and `AGE_18_CONFIRMED` with the hash of the checkbox text. Records made under 2.0 with the
key `PRIVACY` stay valid as "seen".

Under-18 dates block onboarding and are not stored. Do not let the user immediately retry with a
different date on the same install (store a local "blocked" flag). The birth date used for the age
shown on the Nearby card must be the same one (changing it later in Profile re-runs this check). The
App also refuses incoming cards whose age or birth date is under 18.

## 2. (Merged into §1)

The separate "fine print" page of version 2.0 is gone. Its plain-words summary is kept, for readers,
at the top of each document in Settings › Privacy & legal (the documents already open with a short
version).

## 3. Birth data and blood type

Blood type is set in Profile › Eastern (not in onboarding). The switch appears only after a blood
type is chosen.

| Key | Text |
|---|---|
| `birth_privacy_note` | Your birth time and place stay on this phone. |
| `blood_type_title` | Blood type |
| `blood_type_note` | For fun, not medical. Stays on this phone unless you switch on the option below. |
| `blood_type_share_switch` | Use in Nearby matching |
| `blood_type_share_sub` | I consent to sending my blood type (health data) to every nearby phone with Nearby on. |

`blood_type_share_switch` is the switch label and `blood_type_share_sub` its subtitle (always visible
next to it). Recorded key: `CONSENT_BLOOD_TYPE_MATCHING` when switched on (hash of label +
subtitle), and the same key with `withdrawn: true` when switched off. Off means blood type never
leaves the phone.

## 4. Dating and friends profile

Collected in onboarding screen 3, in Profile, or inside the Nearby sheet (§5) when missing. These
fields stay on the phone until the §5 consent is given; no consent text is shown next to them.

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
| `option_seeking_men` | Men |
| `option_seeking_women` | Women |
| `option_single` | Single |
| `option_in_relationship` | In a relationship |
| `option_dating` | Dating |
| `option_friends` | Friends |
| `option_dating_and_friends` | Both |
| `dating_profile_in_relationship_note` | People in a relationship are matched for friends only. |

The I am row uses `option_male` / `option_female`; the Seeking row uses `option_seeking_men` /
`option_seeking_women` / `option_both`.

## 5. Nearby consent sheet (Bluetooth)

A bottom sheet shown the first time the user turns Nearby on (from the Nearby tab or Settings), and
again after the consent is withdrawn. It is the **prominent disclosure** for both stores and the
**explicit consent** for orientation-revealing data. It must appear before the Bluetooth /
"Nearby devices" permission prompt and before Nearby first turns on. If the dating fields (§4) or
the first name are missing, the sheet shows them above the points.

**Visible layer**

| Key | Text |
|---|---|
| `nearby_title` | Turn on Nearby |
| `nearby_sheet_point_bluetooth` | Uses Bluetooth, even while the app is closed. Never your location. |
| `nearby_sheet_point_sent` | Nearby phones get your gender, who you seek, status, what you're looking for, chart and birth date and hour (blood type if on). |
| `nearby_sheet_point_match` | Matches also get your first name, photo and bio before accepting, and keep them. Encrypted only once you both accept; then chats are end-to-end encrypted. |
| `nearby_sheet_point_control` | No one is verified. Pause or withdraw any time in Profile › Settings. |
| `nearby_consent_checkbox` | I consent to sending these details, which can reveal my sexual orientation, to nearby phones for matching. |
| `nearby_details` | Details |
| `nearby_safety_link` | Safety Tips |
| `nearby_turn_on` | Turn on |
| `nearby_not_now` | Not now |

`nearby_consent_checkbox` starts unticked and `nearby_turn_on` stays disabled until it is ticked (and
the dating fields are complete). Recorded keys on Turn on: `CONSENT_NEARBY_BLUETOOTH` and
`CONSENT_ORIENTATION_DATA`, both with the SHA-256 of `nearby_title` + the four points (in table order) +
the checkbox text. Then the system Bluetooth prompt; on Android 13+ / iOS, the notification prompt follows only
if it was never asked. Not now records nothing.

**Details layer** (opens from `nearby_details`; same sheet, scrollable)

| Key | Text |
|---|---|
| `nearby_details_title` | How Nearby works |
| `nearby_point_bluetooth` | While Nearby is on, your phone uses Bluetooth, even when Conjunction is closed. It doesn't use your location, and no one sees a distance. |
| `nearby_point_hello` | To check for a match, your phone sends every nearby phone with Nearby on: your gender and who you seek, relationship status, what you're looking for, chart positions, birth date and birth hour, and your blood type if you chose to add it. Never your name, birth place, birth minute or location. These details together are close to unique, so someone who keeps scanning near you could recognise you again. |
| `nearby_point_card` | If you match, it also sends your first name, bio line and photo. They see your card before either of you accepts: photo, first name, age, signs, Chinese animal, relationship status and what you're looking for, and a compatibility wheel with your Sun, Moon, Venus, Mars and Ascendant. Their phone keeps your chart positions for as long as the match or friendship lasts. |
| `nearby_point_keep` | This isn't encrypted until you both accept, and we can't control other phones: a modified app could keep what you send. Only add what you're happy for strangers nearby to see. |
| `nearby_point_swipe` | Swipe right to accept, left to pass. Chat opens only when you both swipe right. Chats are end-to-end encrypted and stay on your two phones. Your Instagram username, if you add one, goes only to people you both accepted, inside that encrypted chat. |
| `nearby_point_device_name` | Phones that connect can also read your phone's Bluetooth name (for example "Sam's Pixel"). You can change it in your phone's Bluetooth settings. |
| `nearby_point_safety` | We don't verify anyone. Meet in public and read the Safety Tips first. |
| `nearby_point_no_checks` | CONJUNCTION DOES NOT CONDUCT CRIMINAL BACKGROUND CHECKS ON ITS USERS. |
| `nearby_point_control` | Pause or turn off any time. Pause at home or work so strangers can't place you. |

`nearby_point_no_checks` is shown in **bold capitals at 12 pt or larger** (Texas Bus. & Com. Code
§106.004; counsel to confirm applicability, README item 6). On iOS, `nearby_ios_background_note`
(§5b) is also shown in the Details layer.

### 5b. Nearby status and settings (Profile › Settings › Nearby, Nearby tab cards)

| Key | Text |
|---|---|
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
| `settings_blood_share` | Use blood type in Nearby |
| `settings_instagram_default` | Share my Instagram with new matches |

`SAFETY_TIPS` version is recorded when the Safety Tips sheet is opened and dismissed with "Got it".
`nearby_ios_background_note` is iOS only (Apple keeps a backgrounded iPhone's advertisement in an
"overflow area" that only iPhones can see). `nearby_android_old_version` is shown only if the app
keeps minSdk below 31 and disables Nearby there (see `store/play/PERMISSIONS.md` §1.1).
`settings_blood_share` mirrors the §3 switch (same records). Settings › Nearby also shows the
`settings_nearby_consent` row with `settings_withdraw_nearby_consent` (§12), so withdrawal sits next
to the Nearby switch.

## 6. Profile notices (photo, Instagram)

One FinePrint line each, directly under the control; no panel.

| Key | Text |
|---|---|
| `photo_title` | Your profile photo |
| `photo_note` | Matches nearby see this photo before accepting, and friends keep it. |
| `photo_add` | Choose from your photos |
| `photo_change` | Change photo |
| `photo_remove` | Remove photo |
| `photo_hidden` | Photo hidden — it couldn't be checked on this phone. |
| `photo_show` | Show photo |
| `photo_flagged` | This photo may break our Community Guidelines. |
| `instagram_label` | Instagram (optional) |
| `instagram_note` | Shared only with your mutual matches, encrypted. Never with strangers nearby. |
| `instagram_share_with_match` | Share my Instagram with %1$s |

The Instagram username is typed or pasted by the user (the App has no Instagram login and reads
nothing from Instagram). It is sent only to friends after a mutual match, inside the end-to-end
encrypted chat channel, and only while `instagram_share_with_match` is on for that friend
(default from `settings_instagram_default`). No consent record is needed (not sensitive data;
user-initiated sharing with a chosen person).

## 7. Chat notice

`chat_first_open_notice` shows once per friend, as one FinePrint line at the top of a new chat. Block,
report, unmatch, Safety Tips and the safety code are in the chat header's ⋯ menu.


| Key | Text |
|---|---|
| `chat_first_open_notice` | End-to-end encrypted, phone to phone. Messages arrive when you're near each other, and they keep a copy. |
| `chat_queued` | Waiting — delivered next time you're near %1$s. |
| `chat_flagged` | Hidden: this message may break our Community Guidelines. |
| `chat_flagged_show` | Show anyway |
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

The Bluetooth prompt follows the Nearby sheet (§5) directly; there is no separate Bluetooth rationale
screen in 2.1. The camera rationale is shown before the camera prompt (palm reading, or "Take a
photo" for the profile photo). Notifications are asked just in time: after the Nearby sheet's Turn on
(if never asked) and from a one-time card on Today (`perm_notifications_*`). The Info.plist strings
(*(store review)*) must match `store/appstore/INFO_PLIST.md`.

| Key | Text |
|---|---|
| `perm_bluetooth_denied` | Bluetooth access is off, so Nearby can't find anyone. You can allow it in Settings. |
| `NSBluetoothAlwaysUsageDescription` *(store review)* | Conjunction uses Bluetooth only while Nearby is on, to find people close by who also turned Nearby on and to exchange match cards and encrypted messages directly with their phones. It doesn't use your location. |
| `perm_camera_title` | Camera for palm reading |
| `perm_camera_body` | Take a photo of your palm for a reading. The photo is read on this phone and never sent anywhere. |
| `perm_camera_button` | Allow camera |
| `perm_camera_denied` | Camera is off. You can pick a photo instead, or allow the camera in Settings. |
| `NSCameraUsageDescription` *(store review)* | Conjunction uses the camera to photograph your palm for a reading on this phone, and to take a profile photo if you choose. |
| `NSPhotoLibraryUsageDescription` *(store review)* | Choose a palm photo for a reading or a profile photo. |
| `perm_notifications_title` | Get your reading each morning? |
| `perm_notifications_body` | Get your daily horoscope, and know when a match is nearby or a friend's message arrives. Notifications are made on this phone. |
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
| `match_card_entertainment_note` | Compatibility is for fun, not a safety signal. |

All notifications are local (made by the app on the phone); there is no push service.
`match_card_entertainment_note` is one FinePrint line at the bottom of the Compatibility view (SPEC
5d.4), not on the swipe card.

## 11. Safety tips (short in-app versions)

Full text: `SAFETY_TIPS.md`. Shown from the Nearby sheet, the ⋯ menus and Settings › Privacy & legal.
`safety_tip_no_background_checks` is shown in bold capitals, 12 pt or larger.

| Key | Text |
|---|---|
| `safety_tip_title` | Stay safe |
| `safety_tip_emergency` | In danger? Call local emergency services first. |
| `safety_tip_no_checks` | We don't verify anyone's identity, age, gender or relationship status. |
| `safety_tip_no_background_checks` | CONJUNCTION DOES NOT CONDUCT CRIMINAL BACKGROUND CHECKS ON ITS USERS. |
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

## 12. Profile › Settings › Privacy & legal

One screen holds everything legal that is not one of the four in-flow items. Rows in this order.

| Key | Text |
|---|---|
| `settings_privacy_legal_title` | Privacy & legal |
| `settings_legal_docs_title` | Documents |
| `settings_nearby_consent` | Nearby consent |
| `settings_nearby_consent_given` | Given %1$s |
| `settings_nearby_consent_not_given` | Not given |
| `settings_withdraw_nearby_consent` | Withdraw |
| `settings_withdraw_nearby_consent_confirm` | This turns off Nearby, and your phone stops sending your gender, who you seek and your relationship status. Phones that already received them keep their copies. |
| `settings_legal_records` | My acceptance records |
| `settings_arbitration_optout` | Opt out of arbitration |
| `settings_arbitration_optout_hint` | Within 30 days of agreeing · prepares an email |
| `settings_blocked` | Blocked people |
| `settings_unblock` | Unblock |
| `settings_no_server_note` | Conjunction has no server and no account. Your data is on this phone, and what you sent to friends is on theirs. |
| `settings_delete_data` | Delete all my data |
| `settings_delete_data_confirm` | Delete your profile, friends, chats, photos, settings and acceptance records from this phone? This can't be undone, and we can't restore it. Friends keep the copies on their phones. |
| `settings_contact` | Contact us: %1$s |

The Documents row lists Terms, Waiver, Privacy Policy, Community Guidelines, Safety Tips and
Open-source licences, each with its version. `settings_withdraw_nearby_consent` records
`CONSENT_ORIENTATION_DATA` and `CONSENT_NEARBY_BLUETOOTH` with `withdrawn: true` after the confirm,
turns Nearby off and stops sending; the §5 sheet is shown again the next time Nearby is turned on.
The blood-type withdrawal is the §3 switch (mirrored as `settings_blood_share` in Settings › Nearby).
`settings_delete_data` wipes the app's local storage, the identity key and the consent records, then
returns to onboarding. The `settings_arbitration_optout` row (subtitle `settings_arbitration_optout_hint`) opens an email to [CONTACT EMAIL] with the
subject "Arbitration Opt-Out" and the date the Waiver was accepted.

## 13. Document updates: re-acceptance and notices

The apps compare the **major** version (the `2` in `2.1`) of each accepted `WAIVER`, `TERMS` and
`COMMUNITY_GUIDELINES` with the bundled document. The Privacy Policy is read, never agreed to (GDPR
Art. 7(2)): **any** change to it (major or minor) uses the notice below, never re-acceptance.

| Key | Text |
|---|---|
| `legal_reaccept_title` | We updated our Terms |
| `legal_reaccept_body` | Please review the changes: %1$s. Tapping "Agree and continue" means you agree. |
| `legal_reaccept_continue` | Agree and continue |
| `legal_notice_title` | We updated our documents |
| `legal_notice_body` | Small changes to %1$s. Nothing you need to do. |
| `legal_notice_ok` | OK |

- **Major version changed** → compact modal bottom sheet with `legal_reaccept_title`,
  `legal_reaccept_body` (the `%1$s` is the list of changed documents, each a link) and
  `legal_reaccept_continue`. Records the new versions with the hash of the formatted body and each
  document's `docSha256`. Only the button accepts it; Back minimises the app and a tap outside the
  sheet does nothing. No onboarding is repeated.
- **Only the minor version changed** (for example 2.0 → 2.1) → a one-time notice sheet with
  `legal_notice_title`, `legal_notice_body` (changed documents as links) and `legal_notice_ok`.
  Records `<DOC>_NOTICE_SEEN` at the new version; the accepted version is unchanged (Waiver §19.3:
  only material changes need acceptance).
