import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { canRespondToAssignment } from "./assignment-response.ts";

const published = { status: "PUBLISHED", submissionMode: "TEXT", submission: null };

test("only unstarted, draft and teacher-returned responses stay editable", () => {
  assert.equal(canRespondToAssignment(published), true);
  for (const state of ["NOT_STARTED", "DRAFT", "RETURNED"]) {
    assert.equal(canRespondToAssignment({ ...published, submission: { state } }), true, state);
  }
  for (const state of ["SUBMITTED", "LATE", "ACCEPTED", "EXEMPT", "REVIEWED", "UNKNOWN", ""]) {
    assert.equal(canRespondToAssignment({ ...published, submission: { state } }), false, state);
  }
});

test("closed, unpublished and offline assignments offer no online response editing", () => {
  for (const status of ["DRAFT", "CLOSED", "ARCHIVED", "UNKNOWN", ""]) {
    assert.equal(canRespondToAssignment({ ...published, status }), false);
  }
  for (const state of ["NOT_STARTED", "DRAFT", "RETURNED", "ACCEPTED"]) {
    assert.equal(canRespondToAssignment({ ...published, submissionMode: "OFFLINE", submission: { state } }), false);
  }
});

test("the student controls and actionable count share the API-compatible response predicate", () => {
  const ui = readFileSync(new URL("../components/student/student-portal.tsx", import.meta.url), "utf8");
  assert.ok(/assignments\?\.assignments\.filter\(canRespondToAssignment\)/.test(ui), "actionable count uses response state");
  assert.ok(/const canRespond = canRespondToAssignment\(assignment\)/.test(ui), "assignment controls use response state");
  assert.ok(/disabled=\{busy \|\| !canRespond\}/.test(ui), "locked responses stay disabled");
  assert.ok(!/\["SUBMITTED", "REVIEWED"\]\.includes/.test(ui), "obsolete review state is not the edit boundary");
  assert.ok(/canRespond \? <div><button/.test(ui), "save/submit controls are limited to editable responses");
});
