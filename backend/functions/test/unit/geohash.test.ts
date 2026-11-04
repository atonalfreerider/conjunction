import { describe, expect, it } from "vitest";
import { cellSizeDegrees, cellsCovering, decodeBounds, encode, neighbours, searchCells } from "../../src/lib/geohash.js";
import { haversineMeters } from "../../src/lib/geo.js";

/** Deterministic PRNG so property tests are reproducible. */
function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 2 ** 32;
  };
}

/** Point at `distance` metres and `bearing` degrees from (lat, lon) (spherical destination formula). */
function destination(lat: number, lon: number, distance: number, bearing: number): [number, number] {
  const R = 6_371_008.8, d = distance / R, b = (bearing * Math.PI) / 180;
  const p1 = (lat * Math.PI) / 180, l1 = (lon * Math.PI) / 180;
  const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
  const l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
  let lonDeg = (l2 * 180) / Math.PI;
  lonDeg = ((((lonDeg + 180) % 360) + 360) % 360) - 180;
  return [(p2 * 180) / Math.PI, lonDeg];
}

describe("geohash encode/decode", () => {
  it("matches published reference hashes", () => {
    // Wikipedia / geohash.org examples.
    expect(encode(57.64911, 10.40744, 11)).toBe("u4pruydqqvj");
    expect(encode(42.6, -5.6, 5)).toBe("ezs42");
    expect(encode(-25.382708, -49.265506, 9)).toBe("6gkzwgjzn");
    expect(encode(0, 0, 7)).toBe("s000000");
    expect(encode(90, 180, 3)).toBe("zzz");
    expect(encode(-90, -180, 3)).toBe("000");
  });

  it("decodes to a box containing the point", () => {
    const b = decodeBounds("u4pruydqqvj");
    expect(b.minLat).toBeLessThanOrEqual(57.64911);
    expect(b.maxLat).toBeGreaterThanOrEqual(57.64911);
    expect(b.minLon).toBeLessThanOrEqual(10.40744);
    expect(b.maxLon).toBeGreaterThanOrEqual(10.40744);
  });

  it("rejects bad input", () => {
    expect(() => encode(91, 0)).toThrow(RangeError);
    expect(() => encode(0, 181)).toThrow(RangeError);
    expect(() => encode(Number.NaN, 0)).toThrow(RangeError);
    expect(() => encode(0, 0, 0)).toThrow(RangeError);
    expect(() => decodeBounds("abc")).toThrow(RangeError); // 'a' is not in the alphabet
    expect(() => decodeBounds("")).toThrow(RangeError);
  });

  it("geohash-7 cells are ~153 m tall", () => {
    const [h, w] = cellSizeDegrees(7);
    expect(h).toBeCloseTo(180 / 2 ** 17, 12);
    expect(w).toBeCloseTo(360 / 2 ** 18, 12);
    expect(haversineMeters(0, 0, h, 0)).toBeGreaterThan(150);
    expect(haversineMeters(0, 0, h, 0)).toBeLessThan(155);
  });
});

describe("neighbours", () => {
  it("matches the reference lookup-table implementation (ngeohash 0.6.4), N, NE, E, SE, S, SW, W, NW", () => {
    expect(neighbours("u4pruyd")).toEqual(["u4pruyf", "u4pruyg", "u4pruye", "u4pruy7", "u4pruy6", "u4pruy3", "u4pruy9", "u4pruyc"]);
    expect(neighbours("dr5ru7v")).toEqual(["dr5rukj", "dr5rukn", "dr5ru7y", "dr5ru7w", "dr5ru7t", "dr5ru7s", "dr5ru7u", "dr5rukh"]);
    expect(neighbours("9q8yyk8")).toEqual(["9q8yykb", "9q8yykc", "9q8yyk9", "9q8yyk3", "9q8yyk2", "9q8yyhr", "9q8yyhx", "9q8yyhz"]);
  });

  it("every neighbour touches the cell", () => {
    const box = decodeBounds("dr5ru7v");
    for (const h of neighbours("dr5ru7v")) {
      const nb = decodeBounds(h);
      const eps = 1e-12;
      const touchesLat = nb.minLat <= box.maxLat + eps && nb.maxLat >= box.minLat - eps;
      const touchesLon = nb.minLon <= box.maxLon + eps && nb.maxLon >= box.minLon - eps;
      expect(touchesLat && touchesLon).toBe(true);
    }
  });

  it("wraps across the antimeridian", () => {
    const east = encode(10, 179.9999, 7);
    const west = encode(10, -179.9999, 7);
    expect(neighbours(east)).toContain(west);
    expect(neighbours(west)).toContain(east);
  });

  it("has no cells beyond the poles", () => {
    const top = encode(89.99999, 20, 7);
    const n = neighbours(top);
    expect(n.length).toBe(5); // E, W and three to the south
    expect(new Set(n).size).toBe(n.length);
  });

  it("searchCells is the cell plus its 8 neighbours", () => {
    const cells = searchCells("9q8yyk8");
    expect(cells[0]).toBe("9q8yyk8");
    expect(new Set(cells).size).toBe(9);
  });
});

describe("cellsCovering (50 m circle)", () => {
  const cases: Array<[string, number, number]> = [
    ["equator", 0.0001, 0.0001],
    ["New York", 40.7128, -74.006],
    ["Sydney", -33.8688, 151.2093],
    ["Oslo", 59.9139, 10.7522],
    ["Tromsø (69.6°N)", 69.6492, 18.9553],
    ["Longyearbyen (78.2°N)", 78.2232, 15.6267],
    ["antimeridian", -16.5, 179.99995],
    ["cell corner", ...(() => { const b = decodeBounds("u4pruyd"); return [b.maxLat, b.maxLon] as [number, number]; })()],
  ];

  for (const [name, lat, lon] of cases) {
    it(`covers every point within 50 m — ${name}`, () => {
      const cells = new Set(cellsCovering(lat, lon, 50));
      expect(cells.size).toBeLessThanOrEqual(30); // Firestore `in` limit
      const rand = rng(Math.round(Math.abs(lat * 1000 + lon)));
      for (let i = 0; i < 400; i++) {
        const [pLat, pLon] = destination(lat, lon, 50 * Math.sqrt(rand()), 360 * rand());
        expect(cells.has(encode(pLat, pLon, 7))).toBe(true);
      }
      // Ring of points exactly at 50 m.
      for (let bearing = 0; bearing < 360; bearing += 5) {
        const [pLat, pLon] = destination(lat, lon, 49.999, bearing);
        expect(cells.has(encode(pLat, pLon, 7))).toBe(true);
      }
    });
  }

  it("is the 3x3 block (or fewer) at mid latitudes", () => {
    const c = cellsCovering(40.7128, -74.006, 50);
    expect(c.length).toBeLessThanOrEqual(9);
    const cell = encode(40.7128, -74.006, 7);
    for (const h of c) expect(searchCells(cell)).toContain(h);
  });
});

describe("haversine", () => {
  it("matches known distances", () => {
    // One degree of latitude ≈ 111.195 km on the mean-radius sphere.
    expect(haversineMeters(0, 0, 1, 0)).toBeCloseTo(111_195.08, 0);
    // Paris – London ≈ 343.9 km (spherical).
    expect(haversineMeters(48.8566, 2.3522, 51.5074, -0.1278) / 1000).toBeCloseTo(343.5, 0);
    expect(haversineMeters(10, 10, 10, 10)).toBe(0);
  });
  it("is accurate at 50 m", () => {
    const [lat, lon] = destination(51.5, -0.12, 50, 73);
    expect(haversineMeters(51.5, -0.12, lat, lon)).toBeCloseTo(50, 6);
  });
});
