import { describe, expect, it } from "vitest";
import { isJpeg, stripJpegMetadata } from "../../src/lib/jpeg.js";
import { decidePhoto } from "../../src/moderation/photo.js";

function segment(marker: number, payload: Buffer): Buffer {
  const len = Buffer.alloc(2);
  len.writeUInt16BE(payload.length + 2);
  return Buffer.concat([Buffer.from([0xff, marker]), len, payload]);
}

/** A structurally valid JPEG skeleton: SOI, APP0, APP1 (Exif with "GPS"), COM, DQT, SOS + data, EOI. */
function fakeJpeg(): Buffer {
  return Buffer.concat([
    Buffer.from([0xff, 0xd8]),
    segment(0xe0, Buffer.from("JFIF\0\x01\x01\0\0\x01\0\x01\0\0", "binary")),
    segment(0xe1, Buffer.from("Exif\0\0GPSLatitude=51.5;GPSLongitude=-0.12", "binary")),
    segment(0xfe, Buffer.from("taken at 12 Baker St", "binary")),
    segment(0xdb, Buffer.alloc(65, 1)),
    segment(0xda, Buffer.from([0x01, 0x01, 0x00, 0x00, 0x3f, 0x00])),
    Buffer.from([0x12, 0x34, 0xff, 0x00, 0x56]),
    Buffer.from([0xff, 0xd9]),
  ]);
}

describe("JPEG metadata stripping", () => {
  it("removes EXIF/GPS and comments, keeps JFIF, tables and scan data", () => {
    const input = fakeJpeg();
    const out = stripJpegMetadata(input);
    expect(isJpeg(out)).toBe(true);
    const s = out.toString("binary");
    expect(s).not.toContain("GPSLatitude");
    expect(s).not.toContain("Baker");
    expect(s).toContain("JFIF");
    expect(out.subarray(-7).equals(Buffer.from([0x12, 0x34, 0xff, 0x00, 0x56, 0xff, 0xd9]))).toBe(true);
    expect(out.length).toBeLessThan(input.length);
    // Idempotent.
    expect(stripJpegMetadata(out).equals(out)).toBe(true);
  });
  it("rejects non-JPEG and corrupt input", () => {
    expect(isJpeg(Buffer.from("\x89PNG\r\n", "binary"))).toBe(false);
    expect(() => stripJpegMetadata(Buffer.from("hello"))).toThrow();
    expect(() => stripJpegMetadata(Buffer.from([0xff, 0xd8, 0xff, 0xe1, 0xff, 0xff]))).toThrow();
  });
});

describe("photo SafeSearch policy", () => {
  const base = { adult: "VERY_UNLIKELY", racy: "VERY_UNLIKELY", violence: "VERY_UNLIKELY", medical: "VERY_UNLIKELY", spoof: "UNLIKELY" } as const;
  it("approves ordinary photos", () => expect(decidePhoto(base)).toBe("approve"));
  it("rejects likely adult or violent content", () => {
    expect(decidePhoto({ ...base, adult: "LIKELY" })).toBe("reject");
    expect(decidePhoto({ ...base, violence: "VERY_LIKELY" })).toBe("reject");
  });
  it("sends borderline photos to a person", () => {
    expect(decidePhoto({ ...base, adult: "POSSIBLE" })).toBe("review");
    expect(decidePhoto({ ...base, racy: "LIKELY" })).toBe("review");
    expect(decidePhoto({ ...base, adult: "UNKNOWN" })).toBe("review");
  });
});
