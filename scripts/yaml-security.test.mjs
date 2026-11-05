import assert from "node:assert/strict";
import test from "node:test";
import yaml from "js-yaml";

test("YAML merge budget counts empty mappings (GHSA-2883-xcg3-v3hh)", () => {
  // Small deterministic fixture: exercise the repaired budget without a CPU-heavy payload.
  const source = "base: &base {}\ntarget:\n  <<: [*base, *base]\n";
  assert.deepEqual(yaml.load(source), { base: {}, target: {} });
  assert.throws(() => yaml.load(source, { maxTotalMergeKeys: 1 }), /merge/i);
});

test("YAML contract parsing continues to reject duplicate mapping keys", () => {
  assert.throws(() => yaml.load("openapi: 3.1.0\nopenapi: 3.0.0\n"), /duplicated mapping key/i);
});
