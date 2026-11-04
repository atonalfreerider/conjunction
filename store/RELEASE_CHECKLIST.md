# Release checklist: from this repository to Google Play and the App Store

Ordered. **[OWNER]** = needs the owner personally (identity, payment, signing secrets, legal
decisions, or a console only they can sign in to). Everything else an agent or developer can do.
Details for each store are in `play/CONSOLE.md` and `appstore/APP_STORE_CONNECT.md`. Requirements
were checked on 2026-11-03; re-check anything marked "check".

Long poles — start them first, in parallel:
- **Apple Developer Program enrolment** (days; longer for an organisation with D-U-N-S).
- **Play closed test: 12+ testers × 14 continuous days** before production can even be requested.
- **Name clearance** (trademark search) before printing the name anywhere permanent.

## Phase 0 — Decisions

1. **[OWNER]** Pick the name from `docs/NAMES.md` (recommended: Conjunction). Run the USPTO / EUIPO
   searches listed there; consider an intent-to-use filing in classes 9 and 45.
2. **[OWNER]** Decide the legal entity: personal (seller shown as your name on the App Store) or an
   organisation "MetaVirtuoso" (LLC + D-U-N-S). Fill `[LEGAL ENTITY NAME]`, `[ADDRESS]`,
   `[CONTACT EMAIL]` placeholders in `legal/*.md`.
3. **[OWNER]** Have a lawyer review `legal/WAIVER.md`, `TERMS.md`, `PRIVACY.md` (dating + sensitive
   data + location across US states / GDPR is not a template job). Decide launch countries.
4. Update the app name in one place per platform (`strings.xml` `app_name`, `CFBundleDisplayName`)
   and in `store/` copy if it changes; run `python3 store/tools/check_lengths.py`.

## Phase 1 — Accounts and cloud

5. **[OWNER]** Enrol in the **Apple Developer Program** (99 USD/yr) — https://developer.apple.com/programs/enroll/.
   Without it there is no TestFlight, no App Store, no push on iOS, no Sign in with Apple.
6. **[OWNER]** Declare **EU DSA trader status** in App Store Connect (and check Play Console's
   developer verification / contact details are current for the MetaVirtuoso account at `/u/1`).
7. **[OWNER]** Create the **Firebase project** (e.g. `conjunction-prod`, plus `conjunction-dev` for
   testing) in the Google Cloud account that should own the data; choose the Firestore region once
   (cannot be changed; pick near most users, e.g. `nam5` or `eur3`); upgrade to the **Blaze** plan
   (Cloud Functions and Secret Manager require it) and set a **budget alert**.
8. In Firebase: enable Auth providers **Anonymous**, **Google**, **Apple**; Firestore (deploy
   `backend/firestore.rules` and the TTL policy on `presence.expiresAt`), Storage (deploy
   `storage.rules`), Cloud Functions (Node 22), Cloud Messaging; enable the **Cloud Vision API**;
   turn on **App Check** (Play Integrity / App Attest) for callable functions.
9. **[OWNER]** Store the TypeSafe key as a secret: `npx firebase functions:secrets:set TYPESAFE_API_KEY`
   and paste the key from `~/Desktop/jev-key.txt` at the prompt (never in a file in the repo). Accept
   TypeSafe's DPA; ask about zero data retention (`legal/PRIVACY.md` §6).
10. **[OWNER]** Apple side of push and Sign in with Apple: create an **APNs auth key (.p8)** and upload
    it in Firebase → Project settings → Cloud Messaging; create the Sign in with Apple key / Services
    ID for Firebase Auth's Apple provider.
11. Register the Android app (`com.metavirtuoso.conjunction`) and iOS app (same bundle id) in
    Firebase; download **`google-services.json`** → `android/app/` and
    **`GoogleService-Info.plist`** → `ios/App/` (both git-ignored; for CI, store them as base64
    repository secrets and write them in the workflow). Add the debug and **upload-key and Play App
    Signing SHA-256** fingerprints to the Android app in Firebase (needed for Google Sign-In and App
    Check; the Play signing fingerprint appears only after step 19).
12. `cd backend && npm ci && npm test` (emulator tests incl. golden compatibility vectors) →
    `npx firebase deploy --only firestore,storage,functions`.
13. Implement and enable the **App Review demo companion** (`appstore/REVIEW_NOTES.md` "Blocking
    items" §1) and the admin tooling to review reports and held content. Set up an email/SMS alert
    for urgent reports (child safety, threats) so they are handled within 24 h.

## Phase 2 — Public web pages (both stores need live URLs)

14. **[OWNER]** Publish on the owner's site (proposed under `https://primitive.io/`, like Sundial):
    - `/legal/conjunction-privacy/` ← `legal/PRIVACY.md`
    - `/legal/conjunction-terms/` ← `legal/TERMS.md` (+ waiver)
    - `/legal/conjunction-community-guidelines/#child-safety` ← `legal/COMMUNITY_GUIDELINES.md`
    - `/conjunction/support/` — contact email, safety tips, how to report
    - `/conjunction/delete-account/` — how to delete in the app + request deletion by email without
      the app (Play requires a web deletion URL)
    - `/conjunction/` — marketing page (optional but used as the marketing URL)
    If different URLs are used, update `appstore/fastlane/metadata/en-US/*_url.txt`, the description
    footer, and `play/CONSOLE.md`.

## Phase 3 — Builds, signing and versions

15. Versioning: `versionName` / `MARKETING_VERSION` = `1.0.0`; Android `versionCode` = 1 and
    increasing by 1 on every upload; iOS `CURRENT_PROJECT_VERSION` = CI run number. Tag releases
    `v1.0.0` (annotated, never moved), as in Sundial.
16. Android target: **compileSdk / targetSdk 36** (Play requires API 36 for new apps and updates
    since 31 Aug 2026 — check again before each release), minSdk 26. Verify the merged release
    manifest against `play/PERMISSIONS.md` (no `ACCESS_BACKGROUND_LOCATION`, no `AD_ID`, no
    `READ_MEDIA_IMAGES`, no `USE_FULL_SCREEN_INTENT`).
17. **[OWNER]** Create the **upload keystore** once and back it up offline:
    `keytool -genkeypair -v -keystore ~/keys/conjunction-upload.jks -alias conjunction-upload -keyalg RSA -keysize 4096 -validity 10000`
    and `android/keystore.properties` (git-ignored) with its path and passwords — the owner types
    the passwords; they never go in chat or git.
18. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test :app:bundleRelease` →
    `app/build/outputs/bundle/release/app-release.aab`. iOS: `swift test` for ConjunctionCore on
    Linux; app build + tests on the macOS CI workflow.
19. **[OWNER]** iOS signing: put the Team ID in `ios/App/project.yml`, create an **App Store Connect
    API key** (Admin) and add `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `APPLE_TEAM_ID` as GitHub
    secrets for the TestFlight workflow (cloud signing, as Sundial).
20. Implement and verify the store-facing app requirements: "Report this reading" on AI output;
    Report / Block on cards, profiles, chats; in-app account deletion (+ Apple token revocation);
    Nearby prominent disclosure before the location prompt; 18+ birth-date gate enforced on the
    server; Sign in with Apple wherever Google Sign-In appears on iOS; recommended: Play Age Signals
    API and Apple Declared Age Range.

## Phase 4 — Store records and listing assets

21. **[OWNER]** Play Console (`/u/1`, MetaVirtuoso): **Create app** with the values in
    `play/CONSOLE.md` §1. The package name is permanent after the first upload.
22. **[OWNER]** App Store Connect: register the bundle id (Push + Sign in with Apple capabilities),
    **New App** with `appstore/APP_STORE_CONNECT.md` §1 — reserves the name.
23. Graphics (sample data and illustrated avatars only, no real people):
    - Play: icon 512 × 512 PNG; feature graphic 1024 × 500 (`art/store/feature_graphic_1024x500.png`);
      **phone screenshots: 2–8, at least 4 at ≥ 1080 × 1920 (9:16)** for recommendation eligibility.
      Tablet screenshots optional.
    - App Store: iPhone **1320 × 2868** (6.9") and **1206 × 2622** (6.3", the set Apple currently
      lists as required); up to 10 each; no alpha. iPad 13" (2064 × 2752) only if shipping for iPad.
      From April 2027 iOS 27.1-SDK builds also need "iPhone Duo" screenshots — check.
    - Capture with instrumented / UI tests in screenshot mode (as Sundial's `StoreAssetsCapture` and
      `ScreenshotTests`), not by hand.
24. Fill the listing: Play — `fastlane supply --metadata_path store/play/fastlane/metadata/android
    --skip_upload_aab` (or paste); Apple — `fastlane deliver --metadata_path
    store/appstore/fastlane/metadata --skip_binary_upload` (or paste). Age rating, App Privacy and
    the new Apple questionnaire are filled by hand in the consoles.
25. Play App content (`play/CONSOLE.md` §4–5): privacy policy, app access instructions, **ads: none**,
    **content rating** (IARC, Social category, users interact + shares location + AI content),
    **target audience 18+ with Restrict minor access**, **Data safety** (`play/DATA_SAFETY.md`),
    **foreground service declaration with video** (`play/PERMISSIONS.md` §3), **child safety
    standards** URL + **[OWNER]** point of contact, account deletion URL.
26. App Store: age rating questionnaire → **18+** (`APP_STORE_CONNECT.md` §3), App Privacy
    (`appstore/APP_PRIVACY.md`), privacy manifest in the build, export compliance via
    `ITSAppUsesNonExemptEncryption = NO`, pricing Free, Mac/Vision Pro availability off.

## Phase 5 — Testing

27. **Play internal testing**: upload the AAB, opt in to **Play App Signing** (Google holds the app
    signing key; the upload key only uploads). Add the Play signing SHA-256 to Firebase (step 11).
    Install from Play on two Android phones; run the two-device test (`appstore/REVIEW_NOTES.md`).
28. **[OWNER]** Record the **two videos**: Android foreground-service video (for Play) and the
    two-device match/chat video (for App Review and Play app access). Fill `[DEMO VIDEO URL]` in
    `review_information/notes.txt`.
29. **[OWNER]** **Play closed testing**: Google Group of 18–25 adult testers, opt-in link, release on
    the closed track, **≥ 12 testers opted in for 14 continuous days**, two in-person meet-ups to
    exercise Nearby, ship at least one fix during the test (`play/POLICY.md` §6).
30. **TestFlight**: tag → TestFlight workflow uploads the build. Internal group (no review) first;
    then an external group (Beta App Review, usually ~1 day) for the same testers on iPhone. Builds
    expire after 90 days. Fill Test Information (what to test, feedback email, privacy URL, review
    notes).
31. Test matrix: Android with and without Gemini Nano (Pixel 9+/recent Galaxy vs. older), iPhone with
    and without Apple Intelligence, permission denials (location "approximate", camera denied,
    notifications denied), Nearby surviving screen-off for 30 min, pause/resume, delete account,
    twins, moderation holds, report → admin queue → action within 24 h.

## Phase 6 — Review and launch

32. **[OWNER]** Play: after 14 days, **apply for production access** (Dashboard) with the answers in
    `play/POLICY.md` §6. Expect several days. Then promote the closed-test release to production
    (staged rollout, e.g. 20 % → 100 %) and send for review.
33. **[OWNER]** App Store: select the build, App Review contact info (name, phone), notes from
    `review_information/notes.txt`, attach the video → **Add for Review → Submit**. Release manually
    after approval so both stores launch together.
34. If Apple rejects under **4.3(b)** (dating and fortune telling are saturated): answer in App Review
    with the differentiators in `appstore/REVIEW_NOTES.md`, request a call, appeal if needed.
35. After approval: switch off the review demo companion (`config/reviewDemo.enabled = false`);
    monitor reports daily; keep the privacy label, Data safety form, privacy policy and manifest in
    step whenever data use changes.
