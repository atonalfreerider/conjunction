# Google Play Console answers for Conjunction

Every value to enter, in Console order. Package `com.metavirtuoso.conjunction`; developer account
**MetaVirtuoso** (personal account, Play Console at `https://play.google.com/console/u/1/`, the same
account as Sundial). Companion documents in this folder:

| File | What it covers |
|---|---|
| `fastlane/metadata/android/en-US/` | Listing text for `fastlane supply` (title, short and full description, changelog) |
| `DATA_SAFETY.md` | The Data safety form, row by row |
| `PERMISSIONS.md` | Every manifest permission (Bluetooth, no location), the `connectedDevice` foreground-service declaration and video |
| `POLICY.md` | Dating, user-generated content without a server, child safety, AI-generated content; the 12-tester closed test |

Answers follow `SPEC.md` (sections 5b and **5c — serverless, Bluetooth peer-to-peer, which overrides
the server parts of 5 and 5b**) and `legal/PRIVACY.md` 2.0. If the build differs (for example an
analytics or crash SDK is added), change the answers *and* the privacy policy together.

## 1. Create the app

Home → **Create app**

| Field | Value |
|---|---|
| App name | `Conjunction: Astrology Dating` (from `title.txt`, 29/30) |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free (no in-app purchases in 1.0) |
| Declarations | Developer Program Policies ✓, Play App Signing terms ✓, US export laws ✓ |

## 2. Store settings (Grow → Store presence → Store settings)

- **Category: Dating.** Matching people for dating is a core function, not incidental, so the
  app is a dating app under Play's Age-Restricted Content and Functionality policy whichever
  category is chosen; choosing Dating states that honestly and puts the app where people look for
  it. (Lifestyle is the alternative if the owner wants the astrology side to lead, but it does not
  change any obligation below.)
- **Tags** (pick up to five from Play's list; the closest available): Dating, Astrology /
  Horoscope, Social, Friends, Lifestyle. Play's tag list changes; choose the nearest matches shown.
- **Contact details:** email `themetavirtuoso@gmail.com` (required, shown publicly); website
  `https://primitive.io/conjunction/`; phone optional (leave blank to keep it private).
- **External marketing:** on (default).

## 3. Main store listing (Grow → Store presence → Main store listing)

| Field | Source / spec |
|---|---|
| App name | `fastlane/metadata/android/en-US/title.txt` |
| Short description | `short_description.txt` (77/80) |
| Full description | `full_description.txt` (3470/4000) |
| App icon | 512 × 512, 32-bit PNG with alpha, ≤ 1 MB — scale `art/icon/app_icon_1024.png` |
| Feature graphic | 1024 × 500, JPEG or 24-bit PNG, no alpha — `art/store/feature_graphic_1024x500.png` |
| Phone screenshots | 2–8, JPEG or 24-bit PNG, no alpha, sides 320–3840 px, long side ≤ 2× short side. **Use at least 4 at 1080 × 1920 (9:16) or larger** — required for Play's screenshot-based recommendation formats. Put them in `fastlane/metadata/android/en-US/images/phoneScreenshots/`. |
| 7" / 10" tablet screenshots | Optional (min 4 each, 1080–7680 px, 16:9 or 9:16). Skip unless the tablet layout is polished. |
| Video | Optional YouTube URL in `video.txt` (empty for now) |

Screenshot storyboard (sample data only; never a real person's photo or a real place):

1. Chart wheel drawing itself, "Your stars, written on your phone".
2. Daily horoscope card.
3. Chinese zodiac + Four Pillars + blood type ("Eastern reading").
4. Palm guide overlay and reading.
5. Match animation mid-merge (two sun-sign medallions spiralling in), with zodiac art in place of
   photos.
6. Swipe card (illustrated avatar, never a stock photo of a real person) with compatibility.
7. Birthday-twins celebration.
8. Safety and privacy: Nearby switch with its plain-language Bluetooth explanation ("no server — phone
   to phone"), Pause, Block/Report.

## 4. App content (Policy → App content)

| Declaration | Answer |
|---|---|
| **Privacy policy** | `https://primitive.io/legal/conjunction-privacy/` (publish `legal/PRIVACY.md` there first; it must be a public, non-PDF web page that names MetaVirtuoso and the app) |
| **App access** | *All or some functionality is restricted* → no login exists, but Nearby needs a second phone running the app within Bluetooth range. Give the reviewer the instructions in `store/appstore/fastlane/metadata/review_information/notes.txt` (in-app demo match if built, two-device test, video link). Credentials: none. |
| **Ads** | **No, my app does not contain ads.** (No ad SDKs; the merged manifest must not contain `com.google.android.gms.permission.AD_ID` — remove it with `tools:node="remove"` if a dependency pulls it in.) |
| **Content rating** | Section 5 below |
| **Target audience and content** | Age groups: **18 and over only.** Tick **"Restrict minor access"** (users Google determines to be under 18 cannot find or install the app). Appeals to children: No. |
| **News app** | No |
| **COVID-19 contact tracing / status** | No |
| **Data safety** | `DATA_SAFETY.md` |
| **Government app** | No |
| **Financial features** | None |
| **Health** | **No health features.** (The optional blood type is used only for an entertainment personality reading and compatibility; it is declared as "Health info" in Data safety to be safe, but the app is not a health app.) |
| **Advertising ID** | No, the app does not use the advertising ID |
| **Foreground service permissions** | `PERMISSIONS.md` §3 — type **connected device**, use case "Continuous data transfer to an external device", with a video |
| **Child safety standards** (shown for Social / Dating apps) | URL of the published standards: `https://primitive.io/legal/conjunction-community-guidelines/#child-safety` (publish `legal/COMMUNITY_GUIDELINES.md`; its "Child safety" section prohibits CSAE and describes reporting). Child-safety point of contact: the owner's name and `themetavirtuoso@gmail.com`. Self-certify compliance. See `POLICY.md` §3. |
| **Account deletion** (inside Data safety) | **Not applicable — the app has no accounts** ("My app does not allow users to create an account"). Play's account-deletion requirement applies only to apps that let users create accounts. Local data: Settings → Delete all my data. The support page should still explain this (`https://primitive.io/conjunction/support/`). |

## 5. Content rating (IARC questionnaire)

Policy → App content → Content rating → Start. Email: `themetavirtuoso@gmail.com`.

**Category:** *Social Networking, Forums, Blogs and UGC Sharing* (the app's distinctive feature is
users meeting and chatting; the IARC guidance puts dating and chat apps here). The wording of
the questions changes from time to time; answer by meaning:

| Question (by meaning) | Answer | Why |
|---|---|---|
| Violence, blood, gore, fear | No | |
| Sexuality, nudity, suggestive content in the app's own content | No | The app's art and copy are not sexual; dating is the context. |
| Language (profanity, crude humour) in the app's own content | No | |
| Controlled substances (drugs, alcohol, tobacco) | No | |
| Gambling, simulated gambling, loot boxes | No | Compatibility percentages are not a game of chance with stakes. |
| Hate speech, discrimination | No | |
| **Users can interact or exchange content with each other** (chat, messaging, UGC) | **Yes** | Text chat between matched users; display names, bios and profile photos are visible to matches. |
| Is that interaction moderated? | Answer by the form's definition. There is **no server-side moderation**: incoming photos and messages are screened by on-device AI on the receiving phone and hidden behind a warning; block, unmatch and pause are on the device; reports go to a person by email with a 24 h response target. If the form asks specifically about the developer reviewing content before it is shown, the answer is No. | |
| **Shares the user's current physical location with other users** | **Yes** (conservative) | The app never reads or sends a location. But a match card arriving over Bluetooth tells a stranger that this person, with this photo, is within Bluetooth range right now, which reveals their whereabouts to that person. Answering Yes matches the intent of the descriptor; the rating is already adult. |
| Allows users to purchase digital goods | No | |
| Unrestricted web browsing | No | Only links to our own legal pages. |
| Contains AI-generated content / generative AI | **Yes** | Horoscopes, palm readings and match blurbs are written by on-device Gemini Nano; reportable in app (`POLICY.md` §4). |
| Promotes or depicts real-world meeting of strangers | Yes, if asked | Proximity matching. |

Expected outcome: an adult or "mature" rating in most regions (e.g. ESRB "Mature 17+" / PEGI 18
with the *Users Interact*, *Shares Location* descriptors). The users-interact and shares-info answers
are unchanged by the serverless design: users still exchange names, photos and messages. The **18+ audience is enforced by the
Target audience setting and Restrict minor access**, not by the rating alone.

## 6. Release (Test and release)

See `../RELEASE_CHECKLIST.md`: internal testing → closed testing with ≥ 12 testers for 14 days
(this account is personal; see `POLICY.md` §6) → apply for production access → production.
