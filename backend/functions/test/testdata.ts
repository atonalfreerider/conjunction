/** Locates the repository's shared testdata/ directory (golden vectors shared with Kotlin and Swift). */
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

export function testdataPath(name: string): string {
  let dir = process.cwd();
  for (let i = 0; i < 8; i++) {
    const candidate = join(dir, "testdata", name);
    if (existsSync(candidate)) return candidate;
    dir = resolve(dir, "..");
  }
  throw new Error(`testdata/${name} not found above ${process.cwd()}`);
}

export function readGolden<T = unknown>(name: string): T {
  return JSON.parse(readFileSync(testdataPath(name), "utf8")) as T;
}
