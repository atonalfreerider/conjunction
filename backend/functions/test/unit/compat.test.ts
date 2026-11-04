/** The TypeScript compatibility port must reproduce testdata/golden_compat.json exactly (docs/COMPATIBILITY.md). */
import { describe, expect, it } from "vitest";
import { bloodScore, centidegrees, chineseRelation, compatibilityScore, pairScore, roundDiv, score, stemRelation } from "../../src/compat/score.js";
import type { CompatProfile } from "../../src/compat/types.js";
import { compatProfileSchema } from "../../src/schemas.js";
import { readGolden } from "../testdata.js";

interface Case { id: string; note?: string; a: CompatProfile; b: CompatProfile; expected: Record<string, unknown> }
const golden = readGolden<{ cases: Case[] }>("golden_compat.json");

describe("compatibility vs golden_compat.json", () => {
  it("has the documented coverage", () => {
    expect(golden.cases.length).toBeGreaterThanOrEqual(30);
    expect(golden.cases.some((c) => c.expected.isExactTwin === true)).toBe(true);
    expect(golden.cases.some((c) => c.expected.isBirthdayTwin === true && c.expected.isExactTwin === false)).toBe(true);
  });

  for (const c of golden.cases) {
    it(`${c.id}${c.note ? ` — ${c.note}` : ""}`, () => {
      const got = score(c.a, c.b) as unknown as Record<string, unknown>;
      for (const [k, v] of Object.entries(c.expected)) expect(got[k], `${c.id}.${k}`).toEqual(v);
      // Symmetric.
      expect(score(c.b, c.a)).toEqual(score(c.a, c.b));
    });
  }

  it("every golden profile passes the upload schema (the wire format the phones send)", () => {
    for (const c of golden.cases) {
      for (const p of [c.a, c.b]) expect(compatProfileSchema.safeParse(p).success, `${c.id}`).toBe(true);
    }
  });

  it("compatibilityScore keeps the stored subset", () => {
    const c = golden.cases[0]!;
    const s = compatibilityScore(c.a, c.b);
    expect(Object.keys(s).sort()).toEqual(["blood", "chinese", "highlights", "total", "western"]);
    expect(s.total).toBe(c.expected.total);
  });
});

describe("compatibility building blocks (same checks as CompatibilityTest.relationsTables)", () => {
  it("relations, blood and rounding", () => {
    expect(chineseRelation("rat", "dragon")).toBe("TRINE");
    expect(chineseRelation("monkey", "rat")).toBe("TRINE");
    expect(chineseRelation("rat", "ox")).toBe("HARMONY");
    expect(chineseRelation("horse", "goat")).toBe("HARMONY");
    expect(chineseRelation("tiger", "pig")).toBe("HARMONY");
    expect(chineseRelation("rat", "horse")).toBe("CLASH");
    expect(chineseRelation("rat", "goat")).toBe("HARM");
    expect(chineseRelation("rooster", "dog")).toBe("HARM");
    expect(chineseRelation("dragon", "dragon")).toBe("SAME_SELF_PUNISHMENT");
    expect(chineseRelation("ox", "ox")).toBe("SAME");
    expect(chineseRelation("rat", "tiger")).toBe("NEUTRAL");
    expect(stemRelation("jia", "ji")).toBe("COMBINATION");
    expect(stemRelation("jia", "yi")).toBe("SAME_ELEMENT");
    expect(stemRelation("jia", "bing")).toBe("GENERATING");
    expect(stemRelation("jia", "wu")).toBe("CONTROLLING");
    expect(bloodScore("A", "O")).toBe(85);
    expect(bloodScore("A", null)).toBeNull();
    expect(centidegrees(359.995)).toBe(0);
    expect(centidegrees(29.995)).toBe(3000);
    expect(roundDiv(5, 2)).toBe(3);
    expect(roundDiv(-5, 2)).toBe(-2);
    expect(pairScore(0, 9123).aspectPart).toBe(-329);
  });
  it("unknown animal keys are an error, not a silent default", () => {
    expect(() => chineseRelation("cat", "rat")).toThrow(RangeError);
  });
});
