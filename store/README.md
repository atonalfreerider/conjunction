# Store listings and release (serverless Bluetooth build)

Everything here describes the build in `SPEC.md` §5c: **no servers, no accounts, no location, no
push**; phones exchange match cards and end-to-end encrypted chat directly over Bluetooth LE.

| Path | Contents |
|---|---|
| `RELEASE_CHECKLIST.md` | Ordered steps from this repo to both stores; owner-only steps marked |
| `play/CONSOLE.md` | Play Console: create app, store settings, listing assets, App content, IARC content rating |
| `play/DATA_SAFETY.md` | Data safety answers, with the reasoning on Google's "collected" definition vs peer-to-peer |
| `play/PERMISSIONS.md` | Bluetooth permissions (`neverForLocation`, no location), the Android 8–11 issue, `connectedDevice` foreground-service declaration and video, prominent disclosure |
| `play/POLICY.md` | Dating (Restrict Minor Access), UGC moderation without a server, Child Safety Standards, AI-generated content and the in-app reporting conflict, closed test of 12 testers × 14 days |
| `play/fastlane/metadata/android/en-US/` | `fastlane supply` listing text |
| `appstore/APP_STORE_CONNECT.md` | Paid program, record, categories, 18+ age rating, screenshots, export compliance (own E2E encryption) |
| `appstore/APP_PRIVACY.md` | App Privacy label (**Data Not Collected**, with reasoning and a conservative alternative) and privacy manifest |
| `appstore/INFO_PLIST.md` | Usage strings (Bluetooth, camera, photos), Bluetooth background modes, no location, no push, no Sign in with Apple |
| `appstore/REVIEW_NOTES.md` | App Review strategy (1.2 without a server, 4.3, 5.1.1, 2.5.4), in-app demo match, two-device test, laptop test peer |
| `appstore/fastlane/metadata/` | `fastlane deliver` metadata and review information |
| `tools/check_lengths.py` | Verifies every field against the store limits: `python3 store/tools/check_lengths.py` |
| `archive/DATING_ADDENDUM.md` | Superseded cross-check written for SPEC 5b with the server design. Not current; do not upload. |

Run the length check after any copy change; it exits non-zero if a field is too long.

The two stores get **different privacy answers on purpose**: Google counts any data leaving the device
(except end-to-end encrypted data) as "collected", so the Play form declares the pre-match Nearby card
and ML Kit diagnostics; Apple counts only data the developer or its partners can access, so the App
Store label is "Data Not Collected". Both are explained in their files.
