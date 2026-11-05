import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

test("decorative homepage preview is explicitly illustrative and never claims live school facts", () => {
  const source = readFileSync(new URL("../components/orbit-visual.tsx", import.meta.url), "utf8");
  assert.match(source, /role="group" aria-labelledby="homepage-platform-preview-caption"/);
  assert.match(source, /id="homepage-platform-preview-caption"><Text domain="public">Illustrative platform preview — not live school data<\/Text>/);
  assert.match(source, />Platform preview</);
  assert.match(source, />Not live school data</);
  assert.doesNotMatch(source, /> Live<|Today at school|Thursday|Term 1|Discovery lab|Family showcase|10:30|>Done</);
  assert.doesNotMatch(source, /fetch\(|useEffect|use client/);
});
