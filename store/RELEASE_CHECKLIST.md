# Release checklist: from this repository to Google Play and the App Store (serverless build)

Ordered. **[OWNER]** = needs the owner personally (identity, payment, signing secrets, legal
decisions, or a console only they can sign in to). Everything else an agent or developer can do.
Details for each store are in `play/CONSOLE.md` and `appstore/APP_STORE_CONNECT.md`. Requirements
were checked on 2026-11-03; re-check anything marked "check".

There is **no backend to set up**: no Firebase project, no Cloud Functions, no APNs key, no TypeSafe
secret, no `google-services.json` / `GoogleService-Info.plist`. `backend/` is kept in the repo marked
"not used (serverless build)" and is never deployed.

Long poles — start them first, in parallel:
- **Apple Developer Program enrolment** (days; longer for an organisation with D-U-N-S).
- **Play closed test: 12+ testers × 14 continuous days** before production can be requested. With
  Bluetooth matching, testers must physically meet — plan the meet-ups early.
- **Name clearance** (trademark search) before printing the name anywhere permanent.

## Phase 0 — Decisions

1. **[OWNER]** Pick the name from `docs/NAMES.md` (recommended: Conjunction). Run the USPTO / EUIPO
   searches listed there; consider an intent-to-use filing in classes 9 and 45.
2. **[OWNER]** Decide the legal entity: personal (seller shown as your name on the App Store) or an
   organisation "MetaVirtuoso" (LLC + D-U-N-S). Fill `[LEGAL ENTITY NAME]`, `[ADDRESS]`,
   `[CONTACT EMAIL]` and the other placeholders in `legal/*.md` (`legal/README.md` lists them).
3. **[OWNER]** Have a lawyer review `legal/WAIVER.md`, `TERMS.md`, `PRIVACY.md` and the items in
   `legal/README.md` "Items for counsel" (peer-to-peer controller analysis, broadcasting orientation
   data, no-ban limitation, export controls). Decide launch countries.
4. **[OWNER]** Decide the open product questions that change store answers:
   - Android 8–11: Nearby off below Android 12, or raise minSdk (`play/PERMISSIONS.md` §1.1).
   - Reports: email only, or an in-app report post (e.g. Google Form) for Play's "without leaving
     the app" rule (`play/POLICY.md` §4).
   - Blocked-identity-key list in app updates (`legal/TERMS.md` §7.3).
   - Play Data safety: conservative declaration of the Nearby card (recommended) — and ask the
     protocol owner to encrypt the hello, which changes the "encrypted in transit" answer
     (`play/DATA_SAFETY.md`).
5. Update the app name in one place per platform (`strings.xml` `app_name`, `CFBundleDisplayName`)
   and in `store/` copy if it changes; run `python3 store/tools/check_lengths.py`.

## Phase 1 — Accounts

6. **[OWNER]** Enrol in the **Apple Developer Program** (99 USD/yr) —
   https://developer.apple.com/programs/enroll/. Needed for TestFlight and the App Store (not for
   push or Sign in with Apple: the app uses neither).
7. **[OWNER]** Declare **EU DSA trader status** in App Store Connect (and check Play Console's
   developer verification / contact details are current for the MetaVirtuoso account at `/u/1`).
8. **[OWNER]** Set up the **report inbox**: the `[CONTACT EMAIL]` mailbox with phone notifications,
   a filter/label for subjects starting "Conjunction report", and a written routine for the 24-hour
   response, child-safety escalation (NCMEC CyberTipline) and threats to life (police). This replaces
   the server's admin queue.

## Phase 2 — Public web pages (both stores need live URLs)

9. **[OWNER]** Publish on the owner's site (proposed under `https://primitive.io/`, like Sundial):
   - `/legal/conjunction-privacy/` ← `legal/PRIVACY.md`
   - `/legal/conjunction-terms/` ← `legal/TERMS.md` (+ waiver)
   - `/legal/conjunction-community-guidelines/#child-safety` ← `legal/COMMUNITY_GUIDELINES.md`
   - `/conjunction/support/` — contact / report email, the 24 h commitment, safety tips, and how to
     delete your data (there is no account, so no separate deletion form is needed)
   - `/conjunction/` — marketing page (optional but used as the marketing URL)
   If different URLs are used, update `appstore/fastlane/metadata/en-US/*_url.txt`, the description
   footer and `play/CONSOLE.md`.

## Phase 3 — Builds, signing and versions

10. Versioning: `versionName` / `MARKETING_VERSION` = `1.0.0`; Android `versionCode` = 1 and
    increasing by 1 on every upload; iOS `CURRENT_PROJECT_VERSION` = CI run number. Tag releases
    `v1.0.0` (annotated, never moved), as in Sundial.
11. Android target: **compileSdk / targetSdk 36** (check Play's current target-API rule before each
    release), minSdk 26 (or 31, step 4). Verify the merged release manifest against
    `play/PERMISSIONS.md`: Bluetooth SCAN (`neverForLocation`) / ADVERTISE / CONNECT,
    `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `CAMERA`; **no** `ACCESS_*_LOCATION`,
    `FOREGROUND_SERVICE_LOCATION`, `AD_ID`, `READ_MEDIA_IMAGES`, `USE_FULL_SCREEN_INTENT`, C2DM.
12. Check the dependency lists contain **no Firebase, no play-services-location, no analytics or
    crash SDK** (`./gradlew :app:dependencies`; iOS `Package.resolved` / `project.yml`). Only ML Kit
    GenAI and MediaPipe on Android (declared in Data safety).
13. **[OWNER]** Create the **upload keystore** once and back it up offline:
    `keytool -genkeypair -v -keystore ~/keys/conjunction-upload.jks -alias conjunction-upload -keyalg RSA -keysize 4096 -validity 10000`
    and `android/keystore.properties` (git-ignored) with its path and passwords — the owner types
    the passwords; they never go in chat or git.
14. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test :app:bundleRelease` (one Gradle build
    at a time on this PC) → `app/build/outputs/bundle/release/app-release.aab`. iOS: `swift build -j 2`
    / `swift test` for ConjunctionCore on Linux; app build + tests on the macOS CI workflow. Protocol
    parity: both apps and `tools/p2p-sim/` pass `testdata/p2p_wire.json`.
15. **[OWNER]** iOS signing: Team ID in `ios/App/project.yml`, an **App Store Connect API key**
    (Admin) and `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `APPLE_TEAM_ID` as GitHub secrets for the
    TestFlight workflow (cloud signing, as Sundial). Info.plist per `appstore/INFO_PLIST.md`
    (`NSBluetoothAlwaysUsageDescription`, `bluetooth-central` + `bluetooth-peripheral`, no
    `NSLocation*`, `ITSAppUsesNonExemptEncryption = YES`), `PrivacyInfo.xcprivacy` per
    `appstore/APP_PRIVACY.md`.
16. Implement and verify the store-facing app requirements: "Report this reading" on every AI
    reading; Report / Block on cards, friends and chats; the Nearby prominent disclosure before the
    "Nearby devices" / Bluetooth prompt; on-device screening of incoming photos and messages;
    receiving phones refuse under-18 cards; "Preview a match" demo (if chosen,
    `appstore/REVIEW_NOTES.md` blocking item 1); "Delete all my data"; legal documents 2.0 bundled;
    recommended: Play Age Signals API and Apple Declared Age Range.

## Phase 4 — Store records and listing assets

17. **[OWNER]** Play Console (`/u/1`, MetaVirtuoso): **Create app** with the values in
    `play/CONSOLE.md` §1. The package name is permanent after the first upload.
18. **[OWNER]** App Store Connect: register the bundle id (no extra capabilities), **New App** with
    `appstore/APP_STORE_CONNECT.md` §1 — reserves the name.
19. Graphics (sample data and illustrated avatars only, no real people):
    - Play: icon 512 × 512 PNG; feature graphic 1024 × 500 (`art/store/feature_graphic_1024x500.png`);
      **phone screenshots: 2–8, at least 4 at ≥ 1080 × 1920 (9:16)**.
    - App Store: iPhone **1320 × 2868** (6.9") and **1206 × 2622** (6.3"); up to 10 each; no alpha.
      Check the "iPhone Duo" screenshot requirement for builds from April 2027.
    - Capture with Robolectric / UI tests in screenshot mode (Android `ScreenCaptureTest`, the debug
      `MatchGalleryActivity`; iOS CI `ScreenshotTests`), not by hand.
20. Fill the listing: Play — `fastlane supply --metadata_path store/play/fastlane/metadata/android
    --skip_upload_aab` (or paste); Apple — `fastlane deliver --metadata_path
    store/appstore/fastlane/metadata --skip_binary_upload` (or paste). Age rating, App Privacy and
    the Apple questionnaire are filled by hand in the consoles.
21. Play App content (`play/CONSOLE.md` §4–5): privacy policy, app access instructions, **ads: none**,
    **content rating** (IARC, Social category, users interact + shares location (conservative) + AI
    content), **target audience 18+ with Restrict minor access**, **Data safety**
    (`play/DATA_SAFETY.md`), **foreground service declaration: connected device, with video**
    (`play/PERMISSIONS.md` §3), **child safety standards** URL + **[OWNER]** point of contact. No
    account-deletion URL (no accounts).
22. App Store: age rating questionnaire → **18+** (`APP_STORE_CONNECT.md` §3), App Privacy **Data Not
    Collected** (`appstore/APP_PRIVACY.md`), export compliance (§7), pricing Free, Mac/Vision Pro off.

## Phase 5 — Testing

23. **Play internal testing**: upload the AAB, opt in to **Play App Signing**. Install from Play on two
    Android phones and run the two-device test (`appstore/REVIEW_NOTES.md`); repeat with one phone
    and the laptop test peer (`tools/p2p-sim/`).
24. **[OWNER]** Record the **two videos**: the Android `connectedDevice` foreground-service video (for
    Play) and the two-device match/chat video (for App Review and Play app access). Fill
    `[DEMO VIDEO URL]` in `review_information/notes.txt`.
25. **[OWNER]** **Play closed testing**: Google Group of 18–25 adult testers, opt-in link, release on
    the closed track, **≥ 12 testers opted in for 14 continuous days**, at least two in-person
    meet-ups (Bluetooth range), ship at least one fix during the test (`play/POLICY.md` §6).
26. **TestFlight**: tag → TestFlight workflow uploads the build. Internal group first; then an
    external group (Beta App Review) for the same testers on iPhone. Builds expire after 90 days.
27. Test matrix: Android 12–16 and (if supported) 8–11; iPhone with and without Apple Intelligence;
    Android ↔ iPhone, iPhone ↔ iPhone, iPhone backgrounded (documented limitation); permission
    denials (Nearby devices / Bluetooth denied, notifications denied, camera denied); Bluetooth off;
    Nearby surviving screen-off for 30 min; pause/resume; store-and-forward delivery after walking out
    of range and back; birthday twins; on-device screening of photos and messages; report email
    content; block; "Delete all my data"; under-18 card refused.

## Phase 6 — Review and launch

28. **[OWNER]** Play: after 14 days, **apply for production access** with the answers in
    `play/POLICY.md` §6. Then promote the closed-test release to production (staged rollout) and
    send for review.
29. **[OWNER]** App Store: select the build, App Review contact info (name, phone), notes from
    `review_information/notes.txt`, attach the video → **Add for Review → Submit**. Release manually
    after approval so both stores launch together.
30. If Apple rejects under **4.3(b)**: answer with the differentiators in `appstore/REVIEW_NOTES.md`,
    request a call, appeal if needed. If either store questions moderation without a server, answer
    with the honest tables in `play/POLICY.md` §2 and `appstore/REVIEW_NOTES.md` §1.2.
31. After launch: read the report inbox daily (24 h commitment); keep the privacy policy, Data
    safety form, App Privacy label and privacy manifest in step whenever data use changes — adding
    any server, SDK or report endpoint changes all four.
