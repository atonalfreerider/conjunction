/**
 * Minimal JPEG validation and metadata stripping (no native image library needed).
 *
 * Phone photos carry EXIF, often with GPS coordinates — exactly what a nearby-matching app must not
 * leak. `stripJpegMetadata` removes every APPn segment except APP0 (JFIF) and APP14 (Adobe colour
 * transform), and all COM segments, before the photo is stored or shown to anyone. The pixel data is
 * untouched. Orientation lives in EXIF, so the apps must rotate pixels before upload (they re-encode
 * the cropped photo anyway).
 */

export function isJpeg(buf: Buffer): boolean {
  return buf.length > 4 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff;
}

export function stripJpegMetadata(buf: Buffer): Buffer {
  if (!isJpeg(buf)) throw new TypeError("not a JPEG");
  const parts: Buffer[] = [buf.subarray(0, 2)]; // SOI
  let i = 2;
  while (i < buf.length) {
    if (buf[i] !== 0xff) throw new TypeError("corrupt JPEG: expected marker");
    // Skip fill bytes.
    let j = i;
    while (j < buf.length && buf[j] === 0xff) j++;
    const marker = buf[j];
    if (marker === undefined) throw new TypeError("corrupt JPEG: truncated");
    const markerStart = j - 1;
    if (marker === 0xda) {
      // Start of scan: the rest (entropy-coded data + EOI) is copied verbatim.
      parts.push(buf.subarray(markerStart));
      return Buffer.concat(parts);
    }
    if (marker === 0xd9) {
      parts.push(buf.subarray(markerStart, j + 1));
      return Buffer.concat(parts);
    }
    if ((marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) {
      parts.push(buf.subarray(markerStart, j + 1));
      i = j + 1;
      continue;
    }
    if (j + 2 >= buf.length) throw new TypeError("corrupt JPEG: truncated segment");
    const len = buf.readUInt16BE(j + 1);
    const end = j + 1 + len;
    if (len < 2 || end > buf.length) throw new TypeError("corrupt JPEG: bad segment length");
    const isApp = marker >= 0xe0 && marker <= 0xef;
    const keep = !(isApp && marker !== 0xe0 && marker !== 0xee) && marker !== 0xfe;
    if (keep) parts.push(buf.subarray(markerStart, end));
    i = end;
  }
  throw new TypeError("corrupt JPEG: no image data");
}
