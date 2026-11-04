/** The backend's match filter and age gate must agree with testdata/golden_match_filter.json. */
import { describe, expect, it } from "vitest";
import {
  defaultLookingFor,
  eligibleMatchKind,
  isAdult,
  latestAdultBirthDate,
  matchKind,
  parseMatchProfile,
  utcToday,
  yearsOn,
  type MatchProfile,
  type RelationshipStatus,
} from "../../src/lib/matchFilter.js";
import { readGolden } from "../testdata.js";

interface Golden {
  defaultLookingFor: Record<string, string>;
  named: Array<{ name: string; a: MatchProfile; b: MatchProfile; expected: string | null }>;
  profiles: Array<MatchProfile & { id: string }>;
  pairs: Array<[string, string, string | null]>;
  age: Array<{ birthDate: string; today: string; years: number | null; adult: boolean }>;
  latestAdultBirthDate: Array<{ today: string; latestAdultBirthDate: string }>;
  eligibility: Array<{ a: MatchProfile; aBirthDate: string; b: MatchProfile; bBirthDate: string; today: string; expected: string | null }>;
}

const golden = readGolden<Golden>("golden_match_filter.json");

describe("match filter vs golden_match_filter.json", () => {
  it("defaultLookingFor", () => {
    for (const [status, expected] of Object.entries(golden.defaultLookingFor)) {
      expect(defaultLookingFor(status as RelationshipStatus)).toBe(expected);
    }
  });

  it("named cases", () => {
    for (const c of golden.named) expect(matchKind(c.a, c.b), c.name).toBe(c.expected);
  });

  it("all 1296 ordered profile pairs (64 dating, 560 friends, 672 null), symmetric", () => {
    const byId = new Map(golden.profiles.map((p) => [p.id, parseMatchProfile(p as unknown as Record<string, unknown>)]));
    expect(golden.pairs.length).toBe(1296);
    const counts = { dating: 0, friends: 0, null: 0 };
    for (const [a, b, expected] of golden.pairs) {
      const got = matchKind(byId.get(a)!, byId.get(b)!);
      expect(got, `${a} x ${b}`).toBe(expected);
      expect(matchKind(byId.get(b)!, byId.get(a)!)).toBe(got);
      counts[(got ?? "null") as keyof typeof counts]++;
    }
    expect(counts).toEqual({ dating: 64, friends: 560, null: 672 });
  });

  it("age: yearsOn / isAdult (throws for future birth dates)", () => {
    for (const c of golden.age) {
      if (c.years === null) expect(() => yearsOn(c.birthDate, c.today)).toThrow();
      else expect(yearsOn(c.birthDate, c.today), `${c.birthDate} @ ${c.today}`).toBe(c.years);
      expect(isAdult(c.birthDate, c.today), `${c.birthDate} @ ${c.today}`).toBe(c.adult);
    }
  });

  it("latestAdultBirthDate", () => {
    for (const c of golden.latestAdultBirthDate) expect(latestAdultBirthDate(c.today)).toBe(c.latestAdultBirthDate);
  });

  it("eligibility (both 18+)", () => {
    for (const c of golden.eligibility) {
      expect(eligibleMatchKind(c.a, c.aBirthDate, c.b, c.bBirthDate, c.today), JSON.stringify(c)).toBe(c.expected);
    }
  });

  it("rejects unknown wire values instead of mapping them", () => {
    expect(() => parseMatchProfile({ gender: "Male", seeking: "female", relationshipStatus: "single", lookingFor: "dating" })).toThrow();
    expect(() => parseMatchProfile({ gender: "male", seeking: "anyone", relationshipStatus: "single", lookingFor: "dating" })).toThrow();
  });

  it("utcToday uses the UTC calendar date", () => {
    expect(utcToday(new Date("2026-11-03T23:30:00-05:00"))).toBe("2026-11-04");
  });
});
