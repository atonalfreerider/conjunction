# Store listings and release

| Path | Contents |
|---|---|
| `RELEASE_CHECKLIST.md` | Ordered steps from this repo to both stores; owner-only steps marked |
| `play/CONSOLE.md` | Play Console: create app, store settings, listing assets, App content, IARC content rating |
| `play/DATA_SAFETY.md` | Data safety form answers |
| `play/PERMISSIONS.md` | Manifest permissions, foreground-service declaration, why no background location, prominent disclosure |
| `play/POLICY.md` | Dating (Restrict Minor Access), UGC, Child Safety Standards, AI-generated content, closed test of 12 testers × 14 days |
| `play/fastlane/metadata/android/en-US/` | `fastlane supply` listing text |
| `appstore/APP_STORE_CONNECT.md` | Paid program, record, categories, 18+ age rating, screenshots, export compliance, Sign in with Apple |
| `appstore/APP_PRIVACY.md` | App Privacy label and privacy manifest |
| `appstore/INFO_PLIST.md` | Usage-description strings, background modes, entitlements |
| `appstore/REVIEW_NOTES.md` | App Review strategy (1.2, 4.3, 4.8, 5.1.1), demo companion requirement, two-device test |
| `appstore/fastlane/metadata/` | `fastlane deliver` metadata and review information |
| `tools/check_lengths.py` | Verifies every field against the store limits: `python3 store/tools/check_lengths.py` |

Run the length check after any copy change; it exits non-zero if a field is too long.

**About `DATING_ADDENDUM.md`** (and `docs/NAMES_DATING.md`): these were written in parallel for SPEC
5b as edits "on top of the base package". The base files listed above were themselves written
after SPEC 5b and already include the dating layer (match cards with photo/first name/age, swipe,
chat, Restrict Minor Access, Child Safety Standards, sensitive data, 4.3(b), 1.2, review demo
partner), so the addendum is a cross-check rather than a set of pending edits. Where the two differ
the base files are the ones fastlane uploads; the addendum's alternative copy (e.g. subtitle
"Compatible signs, close by", short description "Meet compatible signs nearby. …") is an option the
owner may prefer.
