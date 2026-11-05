import assert from "node:assert/strict";
import test from "node:test";
import { admissionHandoffSessions } from "./admission-sessions.ts";

const sessions = [
  { id: "synthetic-original", name: "Synthetic original session" },
  { id: "synthetic-other", name: "Synthetic other session" },
];

test("Regular admissions retain all configured session choices", () => {
  assert.equal(admissionHandoffSessions("REGULAR", null, sessions), sessions);
  assert.equal(admissionHandoffSessions("REGULAR", sessions[0].id, sessions), sessions);
});

test("RTE enrolment offers only its original scoped application session", () => {
  assert.deepEqual(admissionHandoffSessions("RTE", sessions[0].id, sessions), [sessions[0]]);
  assert.deepEqual(admissionHandoffSessions("RTE", null, sessions), []);
  assert.deepEqual(admissionHandoffSessions("RTE", "unknown", sessions), []);
  assert.deepEqual(admissionHandoffSessions("RTE", sessions[0].id, []), []);
  assert.equal(sessions.length, 2);
});
