/**
 * Live evaluation of the TypeSafe moderation battery and policy thresholds.
 *
 *   TYPESAFE_API_KEY_FILE=~/Desktop/jev-key.txt npm run eval:moderation
 *   (or TYPESAFE_API_KEY in the environment)
 *
 * Sends each labelled example through the real `TypeSafeModerator` (same questions, same policy as
 * production), then reports:
 *   - decision-level precision / recall, where "positive" = not allowed (hold or block);
 *   - per-violation precision / recall of the raw Noul at the policy's hold threshold;
 *   - every misclassified example with its top scores.
 *
 * The key is read from the file at runtime and never printed. Example texts are synthetic.
 * Exit code 1 when decision-level recall falls below 0.9 or precision below 0.8.
 */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { TypeSafeModerator, type TextModerationInput } from "../src/moderation/moderator.js";
import { VIOLATIONS, type Violation } from "../src/moderation/questions.js";
import { STANDARD } from "../src/moderation/policy.js";

interface Example extends TextModerationInput {
  id: string;
  /** Expected: "allow", or the set of violations that should stop it. */
  expect: "allow" | Violation[];
  /** Other violations that are also correct to raise for this text (not counted as false positives). */
  alsoOk?: Violation[];
  /** Non-blocking flags expected on an allowed text. */
  expectFlags?: Violation[];
}

const EXAMPLES: Example[] = [
  // --- benign: flirty / astrological intros and normal chat -----------------------------------
  { id: "leo_hello", field: "chat_message", text: "Hi! A Leo sun and a Sagittarius moon — no wonder the app said 91%. How's your day going?", expect: "allow" },
  { id: "coffee_invite", field: "chat_message", text: "Fellow Pisces here 🌙 Would you like to grab a coffee at the cafe on the corner sometime this week?", expect: "allow" },
  { id: "cute_compliment", field: "chat_message", matchKind: "dating", text: "Your smile in that photo is adorable. Is it true Scorpios are as intense as everyone says?", expect: "allow" },
  { id: "twin_excited", field: "chat_message", text: "Birthday twins!! Same day, same year. Do you also hate when people forget it's your birthday?", expect: "allow" },
  { id: "playful_tease", field: "chat_message", text: "A Virgo and a Gemini? I'm already bracing for you to correct my spelling 😄", expect: "allow" },
  { id: "polite_no", field: "chat_message", text: "Thanks for the message! I'm only here for friends right now, but happy to chat about charts.", expect: "allow" },
  { id: "friends_walk", field: "chat_message", matchKind: "friends", text: "Want to join our Saturday morning walk in the park? A few of us from the astrology meetup go.", expect: "allow" },
  { id: "flirty_date", field: "chat_message", matchKind: "dating", text: "I think the stars did their job. Dinner on Friday, my treat?", expect: "allow" },
  { id: "rising_question", field: "chat_message", text: "What's your rising sign? Mine's Capricorn, which apparently explains the resting serious face.", expect: "allow" },
  { id: "bio_benign", field: "bio", text: "Taurus sun, Cancer moon. Plant parent, bad at chess, great at finding the best dumplings in town.", expect: "allow" },
  { id: "bio_flirty", field: "bio", text: "Hopeless romantic Libra looking for someone to stargaze with. Bonus points if you can name three constellations.", expect: "allow" },
  { id: "name_benign", field: "display_name", text: "María José", expect: "allow" },
  { id: "name_benign2", field: "display_name", text: "Sam O'Neill", expect: "allow" },
  { id: "chart_nerd", field: "chat_message", text: "Our Venus signs trine each other, which is supposedly great. Do you read your horoscope daily?", expect: "allow" },
  { id: "established_ig", field: "chat_message", isNewConnection: false, text: "This was fun! My insta is @lunar.lena if you want to keep in touch.", expect: "allow", alsoOk: ["contact_info"] },
  { id: "established_num", field: "chat_message", isNewConnection: false, text: "Loved chatting this week. If you'd like, my number is 555 212 8899 for planning Saturday.", expect: "allow", alsoOk: ["contact_info"] },

  // --- violations -------------------------------------------------------------------------------
  { id: "insult", field: "chat_message", text: "You look way older than your photo, honestly you should be grateful anyone swiped on you.", expect: ["harassment"] },
  { id: "threat", field: "chat_message", text: "Ignore me again and I'll find out where you live. I saw which way you walked.", expect: ["harassment"] },
  { id: "explicit_new", field: "chat_message", matchKind: "dating", text: "Send me nudes first and then I'll tell you what I want to do to you in bed tonight.", expect: ["sexual"], alsoOk: ["harassment"] },
  { id: "bio_sexual", field: "bio", text: "DTF tonight, only looking for hookups, send pics of your body.", expect: ["sexual"], alsoOk: ["harassment"] },
  { id: "crypto_scam", field: "chat_message", text: "My uncle runs a crypto fund that doubles your money in a week. I can get you in with just $500.", expect: ["scam"] },
  { id: "gift_card", field: "chat_message", text: "I'm stuck at the airport and my card got blocked, could you send me a $100 Apple gift card? I'll pay you back.", expect: ["scam"] },
  { id: "escort_ad", field: "bio", text: "Generous gentlemen only. Rates for your private time with me on request.", expect: ["scam", "sexual"] },
  { id: "phone_new", field: "chat_message", text: "Text me instead, my number is five five five, two one two, eight eight nine nine.", expect: ["contact_info"], alsoOk: ["offplatform_lure"] },
  { id: "snap_bio", field: "bio", text: "Not on here much, add me on snap: starrynight_99", expect: ["contact_info", "offplatform_lure"] },
  { id: "telegram_lure", field: "chat_message", text: "This app is glitchy, let's move to Telegram right now so we can talk properly, search for me there.", expect: ["offplatform_lure", "contact_info"] },
  { id: "verify_link", field: "chat_message", text: "Before we meet you need to verify you're real on my safety site, it only takes a minute, just enter your card.", expect: ["offplatform_lure", "scam"] },
  { id: "hate", field: "chat_message", text: "I don't talk to people from your country, you're all dirty liars.", expect: ["hate"], alsoOk: ["harassment"] },
  { id: "hate_bio", field: "bio", text: "No disabled people, they're a burden on everyone.", expect: ["hate"], alsoOk: ["harassment"] },
  { id: "underage", field: "chat_message", text: "lol my mom doesn't know I'm on here, I'm in 10th grade but I look older right", expect: ["underage"] },
  { id: "name_contact", field: "display_name", text: "Jake at gmail dot com", expect: ["contact_info"], alsoOk: ["offplatform_lure"] },
  { id: "established_link", field: "chat_message", isNewConnection: false, text: "Here's the event page for Saturday: https://example.com/stargazing-night", expect: ["offplatform_lure"] },

  // --- allowed but flagged for a human check-in -------------------------------------------------
  { id: "self_harm_flag", field: "chat_message", isNewConnection: false, text: "Rough week. Honestly sometimes I wonder if anyone would notice if I just disappeared for good.", expect: "allow", expectFlags: ["self_harm"] },
];

function readKey(): string {
  const direct = process.env.TYPESAFE_API_KEY?.trim();
  if (direct) return direct;
  const file = process.env.TYPESAFE_API_KEY_FILE;
  if (!file) throw new Error("Set TYPESAFE_API_KEY_FILE (e.g. ~/Desktop/jev-key.txt) or TYPESAFE_API_KEY");
  return readFileSync(file.replace(/^~(?=\/)/, homedir()), "utf8").trim();
}

function pr(tp: number, fp: number, fn: number) {
  const precision = tp + fp === 0 ? 1 : tp / (tp + fp);
  const recall = tp + fn === 0 ? 1 : tp / (tp + fn);
  return { precision, recall };
}

async function main() {
  const moderator = TypeSafeModerator.fromKey(readKey(), { timeoutMs: 15000, maxRetries: 2 })!;
  const results = await Promise.all(EXAMPLES.map(async (ex) => ({ ex, res: await moderator.moderateText(ex) })));

  const unavailable = results.filter((r) => r.res.unavailable);
  if (unavailable.length) {
    console.error(`${unavailable.length} request(s) failed (fail-closed hold); check the key / network.`);
    process.exit(2);
  }
  const model = results[0]!.res.model;
  let tp = 0, fp = 0, fn = 0, tn = 0;
  const misses: string[] = [];
  for (const { ex, res } of results) {
    const positive = ex.expect !== "allow";
    const flagged = res.decision !== "allow";
    if (positive && flagged) tp++;
    else if (!positive && flagged) fp++;
    else if (positive && !flagged) fn++;
    else tn++;
    const top = Object.entries(res.scores).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([k, v]) => `${k}=${v.toFixed(2)}`).join(" ");
    const flagsOk = (ex.expectFlags ?? []).every((f) => res.flags.includes(f));
    const mark = positive === flagged && flagsOk ? "ok  " : "MISS";
    const line = `${mark} ${ex.id.padEnd(16)} expect=${ex.expect === "allow" ? "allow" : ex.expect.join("+")} got=${res.decision}${res.reasons.length ? `(${res.reasons.join(",")})` : ""}${res.flags.length ? ` flags=${res.flags.join(",")}` : ""}  ${top}`;
    console.log(line);
    if (positive !== flagged || !flagsOk) misses.push(line);
  }

  const decision = pr(tp, fp, fn);
  console.log(`\nmodel ${model}; ${EXAMPLES.length} examples`);
  console.log(`decision-level (positive = held or blocked): TP=${tp} FP=${fp} FN=${fn} TN=${tn}  precision=${decision.precision.toFixed(2)} recall=${decision.recall.toFixed(2)}`);

  console.log(`\nper-violation Noul at p >= ${STANDARD.hold} (labelled violations vs raw score):`);
  for (const v of VIOLATIONS) {
    let vtp = 0, vfp = 0, vfn = 0;
    for (const { ex, res } of results) {
      const labelled = (ex.expect !== "allow" && ex.expect.includes(v)) || (ex.expectFlags ?? []).includes(v);
      const fired = (res.scores[v] ?? 0) >= STANDARD.hold;
      if (labelled && fired) vtp++;
      else if (!labelled && fired && !(ex.alsoOk ?? []).includes(v)) vfp++;
      else if (labelled && !fired) vfn++;
    }
    if (vtp + vfp + vfn === 0) continue;
    const m = pr(vtp, vfp, vfn);
    console.log(`  ${v.padEnd(17)} TP=${vtp} FP=${vfp} FN=${vfn}  precision=${m.precision.toFixed(2)} recall=${m.recall.toFixed(2)}`);
  }
  if (misses.length) console.log(`\nmisclassified:\n${misses.join("\n")}`);
  if (decision.recall < 0.9 || decision.precision < 0.8) process.exit(1);
}

main().catch((err) => {
  console.error("eval failed:", err instanceof Error ? err.name : "error");
  process.exit(2);
});
