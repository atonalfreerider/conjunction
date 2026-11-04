/** Sign names derived from tropical longitudes (for cards and notification text). */
export const SIGNS = [
  "aries", "taurus", "gemini", "cancer", "leo", "virgo",
  "libra", "scorpio", "sagittarius", "capricorn", "aquarius", "pisces",
] as const;
export type Sign = (typeof SIGNS)[number];

export const CHINESE_ANIMALS = [
  "rat", "ox", "tiger", "rabbit", "dragon", "snake", "horse", "goat", "monkey", "rooster", "dog", "pig",
] as const;
export type ChineseAnimal = (typeof CHINESE_ANIMALS)[number];

/** Sign of an ecliptic longitude in degrees (any real number; normalised to [0, 360)). */
export function signOf(longitude: number): Sign {
  const lon = ((longitude % 360) + 360) % 360;
  return SIGNS[Math.floor(lon / 30) % 12]!;
}

export const displaySign = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
