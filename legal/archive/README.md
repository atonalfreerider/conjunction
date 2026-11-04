# legal/archive — superseded texts (not shipped)

Nothing in this folder is bundled into the apps (`android/app/build.gradle.kts` `copyLegalDocs`
copies only the named files at the top of `legal/`) or published on the website.

| Folder | What it is |
|---|---|
| `v1.0-server/` | The version 1.0 documents (draft of 2026-11-03) written for the **server design** (Firebase, location, push, TypeSafe / Cloud Vision moderation). Kept because people may have accepted them on test builds: an acceptance record names a version and the SHA-256 of the text shown, and that text must stay retrievable. |
| `dating-drafts/` | The working copies made while the dating & friends layer (SPEC 5b) was written. `PRIVACY.md`, `TERMS.md`, `WAIVER.md` and `IN_APP_CONSENT_COPY.second-copy.md` were byte-identical to the 1.0 files; `DATING_CONSENT_AND_SAFETY.md` Part A was already in `IN_APP_CONSENT_COPY.md` 1.0, and its Part B (match-card bullet, "Sexual health") was merged into `SAFETY_TIPS.md` 2.0. |

The current documents are version 2.0 at the top of `legal/` and describe the serverless Bluetooth
build (SPEC 5c).
