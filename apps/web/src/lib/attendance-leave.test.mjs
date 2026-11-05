import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { leaveAttempt } from "./student-leave.ts";
import {
  attendanceLeaveMessage,
  leaveCorrectionSource,
  reconcileReviewedAttendanceDraft,
} from "./attendance-leave.ts";

const approved = Object.freeze({
  requestId: "synthetic-request",
  requestVersion: 1,
  status: "APPROVED",
  attendanceState: "EXCUSED",
  initializedFromLeave: false,
});

test("late approval explains the mismatch without claiming the saved mark changed", () => {
  const message = attendanceLeaveMessage(approved, "PRESENT");
  assert.match(message, /saved attendance differs/);
  assert.match(message, /Review the observation/);
  assert.match(message, /submitted-record correction/);
  assert.equal(approved.initializedFromLeave, false);
});

test("matching leave still requires actual attendance confirmation", () => {
  assert.match(attendanceLeaveMessage(approved, "EXCUSED"), /Confirm actual attendance/);
  assert.match(
    attendanceLeaveMessage({ ...approved, attendanceState: "ABSENT" }, "ABSENT"),
    /Approved leave \(absent\)/,
  );
});

test("withdrawn and revoked initialization never implies presence or automatic reversal", () => {
  for (const status of ["WITHDRAWN", "REVOKED"]) {
    const message = attendanceLeaveMessage(
      { ...approved, status, initializedFromLeave: true },
      "EXCUSED",
    );
    assert.match(message, new RegExp(`was ${status.toLowerCase()}`));
    assert.match(message, /Verify actual attendance/);
    assert.match(message, /has not been changed automatically/);
  }
});

test("unexpected source state does not masquerade as approved leave", () => {
  assert.equal(
    attendanceLeaveMessage({ ...approved, status: "SUBMITTED" }, "PRESENT"),
    "Leave evidence needs school review.",
  );
});

test("correction sources retain the exact immutable revision and observed mark version", () => {
  assert.deepEqual(leaveCorrectionSource(approved, 3), {
    requestId: approved.requestId,
    requestVersion: 1,
    expectedMarkVersion: 3,
  });
  for (const status of ["WITHDRAWN", "REVOKED", "SUBMITTED"]) {
    assert.throws(() => leaveCorrectionSource({ ...approved, status }, 0), /Refresh/);
  }
  for (const version of [-1, 0.5, NaN, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => leaveCorrectionSource(approved, version), /Refresh/);
  }
  assert.throws(() => leaveCorrectionSource({ ...approved, requestVersion: 0 }, 0), /Refresh/);
});

test("withdrawn correction evidence does not claim the mark was initialized from leave", () => {
  const text = attendanceLeaveMessage(
    { ...approved, status: "WITHDRAWN", initializedFromLeave: false },
    "PRESENT",
  );
  assert.match(text, /linked to this attendance mark/);
  assert.doesNotMatch(text, /used to initialize/);
});

test("draft review retries bind the register, source and observed mark versions", () => {
  const payload = {
    registerId: "synthetic-register",
    leaveRequestId: "synthetic-leave",
    leaveRequestVersion: 1,
    expectedRegisterVersion: 2,
    expectedMarkVersion: 0,
    decision: "KEEP_RECORDED",
    reason: "Synthetic observation",
  };
  const first = leaveAttempt(null, payload, () => "synthetic-first-operation");
  assert.equal(
    leaveAttempt(first, { ...payload }, () => "unexpected"),
    first,
  );
  for (const changes of [
    { registerId: "other-register" },
    { leaveRequestVersion: 2 },
    { expectedMarkVersion: 1 },
    { expectedRegisterVersion: 3 },
    { decision: "APPLY_APPROVED" },
    { reason: "Changed observation" },
  ]) {
    assert.equal(
      leaveAttempt(first, { ...payload, ...changes }, () => "synthetic-new-operation").key,
      "synthetic-new-operation",
    );
  }
});

test("saved leave review refreshes the prior draft value without losing other marks", () => {
  const previous = { id: "reviewed", stateCode: "PRESENT", note: null };
  const saved = { ...previous, stateCode: "EXCUSED" };
  const other = { state: "LATE", note: "Synthetic observation" };
  const drafts = { reviewed: { state: "PRESENT", note: "" }, other };
  const updated = reconcileReviewedAttendanceDraft(drafts, previous, saved);
  assert.deepEqual(updated.reviewed, { state: "EXCUSED", note: "" });
  assert.equal(updated.other, other);
  assert.equal(drafts.reviewed.state, "PRESENT");
});

test("review refresh preserves newer local state and note edits and rejects mismatched marks", () => {
  const previous = { id: "reviewed", stateCode: "PRESENT", note: null };
  const saved = { ...previous, stateCode: "EXCUSED" };
  for (const drafts of [
    {},
    { reviewed: { state: "LATE", note: "" } },
    { reviewed: { state: "PRESENT", note: "New synthetic observation" } },
  ])
    assert.equal(reconcileReviewedAttendanceDraft(drafts, previous, saved), drafts);
  const drafts = { reviewed: { state: "PRESENT", note: "" } };
  assert.equal(
    reconcileReviewedAttendanceDraft(drafts, previous, { ...saved, id: "other" }),
    drafts,
  );
});

test("draft review form preserves explicit confirmation, disabled unsaved edits and in-memory retry identity", () => {
  const form = readFileSync(
    new URL("../components/workspace/draft-leave-review.tsx", import.meta.url),
    "utf8",
  );
  const workspace = readFileSync(
    new URL("../components/workspace/attendance-workspace.tsx", import.meta.url),
    "utf8",
  );
  assert.match(form, /useRef<LeaveAttempt/);
  assert.match(form, /operationId: attempt.current.key/);
  assert.match(form, /type="checkbox" required/);
  assert.match(form, /fieldset disabled=\{busy \|\| props.disabled\}/);
  assert.match(form, /role="alert"/);
  assert.match(form, /aria-label=\{t\("Review leave for \{name\}", \{ name: props\.learnerName \}\)\}/);
  assert.match(workspace, /disabled=\{busy \|\| changedMarkCount > 0\}/);
  assert.doesNotMatch(form, /localStorage|sessionStorage|dangerouslySetInnerHTML/);
});
