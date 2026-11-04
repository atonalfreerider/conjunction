/**
 * Geohash encoding and neighbour search.
 *
 * Nearby matching stores each user's latest position under a 7-character geohash (a cell of about
 * 153 m x 153 m at the equator, narrower east-west towards the poles). A 50 m search circle can cross
 * into any of the 8 surrounding cells, so a lookup asks for the cell itself plus its neighbours and
 * then filters by exact haversine distance (see `geo.ts`).
 *
 * Neighbours are found by stepping one cell height/width from the cell centre and re-encoding, which
 * handles the antimeridian (longitude wraps) and the poles (latitude is clamped and duplicates are
 * removed) without the classic lookup tables.
 */

const BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
const DECODE: Record<string, number> = Object.fromEntries([...BASE32].map((c, i) => [c, i]));

/** Precision used for presence documents (characters). */
export const PRESENCE_PRECISION = 7;

export interface BoundingBox {
  minLat: number;
  maxLat: number;
  minLon: number;
  maxLon: number;
}

function assertLatLon(lat: number, lon: number): void {
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    throw new RangeError(`invalid coordinate (${lat}, ${lon})`);
  }
}

/** Encodes a WGS-84 coordinate as a geohash of `precision` characters (1..12). */
export function encode(lat: number, lon: number, precision: number = PRESENCE_PRECISION): string {
  assertLatLon(lat, lon);
  if (!Number.isInteger(precision) || precision < 1 || precision > 12) {
    throw new RangeError(`invalid geohash precision ${precision}`);
  }
  let latLo = -90, latHi = 90, lonLo = -180, lonHi = 180;
  let hash = "";
  let bits = 0, ch = 0, evenBit = true; // geohash interleaves bits starting with longitude
  while (hash.length < precision) {
    if (evenBit) {
      const mid = (lonLo + lonHi) / 2;
      if (lon >= mid) { ch = (ch << 1) | 1; lonLo = mid; } else { ch = ch << 1; lonHi = mid; }
    } else {
      const mid = (latLo + latHi) / 2;
      if (lat >= mid) { ch = (ch << 1) | 1; latLo = mid; } else { ch = ch << 1; latHi = mid; }
    }
    evenBit = !evenBit;
    if (++bits === 5) {
      hash += BASE32[ch];
      bits = 0;
      ch = 0;
    }
  }
  return hash;
}

/** Returns the bounding box of a geohash cell. Throws on characters outside the geohash alphabet. */
export function decodeBounds(hash: string): BoundingBox {
  if (hash.length === 0) throw new RangeError("empty geohash");
  let latLo = -90, latHi = 90, lonLo = -180, lonHi = 180;
  let evenBit = true;
  for (const c of hash.toLowerCase()) {
    const v = DECODE[c];
    if (v === undefined) throw new RangeError(`invalid geohash character '${c}'`);
    for (let bit = 4; bit >= 0; bit--) {
      const on = (v >> bit) & 1;
      if (evenBit) {
        const mid = (lonLo + lonHi) / 2;
        if (on) lonLo = mid; else lonHi = mid;
      } else {
        const mid = (latLo + latHi) / 2;
        if (on) latLo = mid; else latHi = mid;
      }
      evenBit = !evenBit;
    }
  }
  return { minLat: latLo, maxLat: latHi, minLon: lonLo, maxLon: lonHi };
}

/** Wraps a longitude into [-180, 180). */
function wrapLon(lon: number): number {
  const wrapped = ((((lon + 180) % 360) + 360) % 360) - 180;
  return wrapped;
}

/**
 * The 8 cells around `hash` (N, NE, E, SE, S, SW, W, NW order, duplicates and the cell itself removed —
 * which only happens next to a pole, where there is nothing further north/south).
 */
export function neighbours(hash: string): string[] {
  const box = decodeBounds(hash);
  const height = box.maxLat - box.minLat;
  const width = box.maxLon - box.minLon;
  const cLat = (box.minLat + box.maxLat) / 2;
  const cLon = (box.minLon + box.maxLon) / 2;
  const steps: Array<[number, number]> = [
    [1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1],
  ];
  const out: string[] = [];
  for (const [dLat, dLon] of steps) {
    const lat = cLat + dLat * height;
    if (lat > 90 || lat < -90) continue; // beyond a pole: no cell there
    const h = encode(lat, wrapLon(cLon + dLon * width), hash.length);
    if (h !== hash && !out.includes(h)) out.push(h);
  }
  return out;
}

/** The cell plus its neighbours: every cell a short-radius search around a point in `hash` may touch. */
export function searchCells(hash: string): string[] {
  return [hash, ...neighbours(hash)];
}

/** Size in degrees of a cell at the given precision: [height (lat), width (lon)]. */
export function cellSizeDegrees(precision: number): [number, number] {
  const totalBits = precision * 5;
  const lonBits = Math.ceil(totalBits / 2);
  const latBits = Math.floor(totalBits / 2);
  return [180 / 2 ** latBits, 360 / 2 ** lonBits];
}

/**
 * Every cell of `precision` that intersects the bounding box of a circle of `radiusMeters` around
 * (lat, lon). Near the equator and at mid latitudes this is the cell plus at most its 8 neighbours;
 * above ~71° a geohash-7 cell is narrower than 50 m east-west, so more columns are needed — this
 * function handles that instead of assuming 3x3.
 *
 * Firestore `in` queries accept at most 30 values; the caller checks the length.
 */
export function cellsCovering(lat: number, lon: number, radiusMeters: number, precision: number = PRESENCE_PRECISION): string[] {
  assertLatLon(lat, lon);
  const R = 6_371_008.8;
  const dLat = (radiusMeters / R) * (180 / Math.PI);
  const cosLat = Math.max(Math.cos((lat * Math.PI) / 180), 1e-6);
  const dLon = Math.min(180, dLat / cosLat);
  const minLat = Math.max(-90, lat - dLat);
  const maxLat = Math.min(90, lat + dLat);
  const [h, w] = cellSizeDegrees(precision);
  const out = new Set<string>();
  const latSamples: number[] = [];
  for (let y = minLat; y < maxLat; y += h) latSamples.push(y);
  latSamples.push(maxLat);
  const lonSamples: number[] = [];
  for (let x = lon - dLon; x < lon + dLon; x += w) lonSamples.push(x);
  lonSamples.push(lon + dLon);
  for (const y of latSamples) {
    for (const x of lonSamples) {
      out.add(encode(y, wrapLon(x), precision));
    }
  }
  return [...out];
}
