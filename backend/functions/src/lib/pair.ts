/**
 * Pair ids: the two uids sorted and joined with "_", so both sides compute the same document id.
 * Code never splits a pair id back into uids (custom uids may contain "_"); documents keyed by a pair
 * id always store `uids: [a, b]` in sorted order.
 */
export function pairId(a: string, b: string): string {
  const [x, y] = sortedPair(a, b);
  return `${x}_${y}`;
}

export function sortedPair(a: string, b: string): [string, string] {
  if (a === b) throw new RangeError("a pair needs two different users");
  return a < b ? [a, b] : [b, a];
}
