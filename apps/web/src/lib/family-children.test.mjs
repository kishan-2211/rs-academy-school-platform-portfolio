import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { familyChildren } from "./family-children.ts";

const child = { learnerId: "10000000-0000-4000-8000-000000000011", legalName: "Synthetic learner", gradeCode: "VI", sectionName: null };

test("family selection uses the API learnerId, not an invented id field", () => {
  const linked = familyChildren({ children: [{ ...child, learnerReference: "STU-00000011" }, { ...child, learnerId: "10000000-0000-4000-8000-000000000012", sectionName: "Synthetic A" }] });
  assert.equal(linked[0].learnerId, child.learnerId);
  assert.notEqual(linked[1].learnerId, linked[0].learnerId);
  assert.equal(linked[0].id, undefined);
  assert.deepEqual(linked[0], child);
  assert.deepEqual(familyChildren({ children: [] }), []);
});

test("malformed child identifiers fail closed instead of producing names in API paths", () => {
  for (const payload of [null, {}, { children: null }, { children: [{ ...child, learnerId: undefined, id: child.learnerId }] }, { children: [{ ...child, learnerId: "Synthetic learner" }] }, { children: [{ ...child, learnerId: "../another-child" }] }, { children: [null] }]) {
    assert.throws(() => familyChildren(payload), /Linked children are unavailable/);
  }
});

test("all four affected family screens consume the shared contract and real option values", () => {
  for (const file of ["student-leave-centre.tsx", "family/document-centre.tsx", "family/ptm-centre.tsx", "family/parent-request-centre.tsx"]) {
    const source = readFileSync(new URL(`../components/${file}`, import.meta.url), "utf8");
    assert.match(source, /familyChildren\(/);
    assert.match(source, /value=\{child\.learnerId\}/);
    assert.match(source, /linked\[0\]\?\.learnerId/);
    assert.doesNotMatch(source, /child\.id\b|children\[0\]\?\.id/);
  }
  const java = readFileSync(new URL("../../../api/src/main/java/com/educore/api/family/FamilyChildSummary.java", import.meta.url), "utf8");
  assert.match(java, /UUID learnerId,/);
});
