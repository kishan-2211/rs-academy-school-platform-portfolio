import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const helper = readFileSync(new URL("./parent-requests.ts", import.meta.url), "utf8");
const family = readFileSync(new URL("../components/family/parent-request-centre.tsx", import.meta.url), "utf8");
const staff = readFileSync(new URL("../components/workspace/parent-request-workspace.tsx", import.meta.url), "utf8");
const styles = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");

test("mutations use cookie sessions, CSRF protection, and fresh retry keys", () => {
  assert.match(helper, /credentials:\s*"include"/);
  assert.match(helper, /"X-CSRF-TOKEN":\s*await csrfToken\(\)/);
  assert.match(family, /idempotencyKey:\s*crypto\.randomUUID\(\)/);
  assert.match(staff, /idempotencyKey:\s*crypto\.randomUUID\(\)/);
  assert.doesNotMatch(helper + family + staff, /localStorage|sessionStorage/);
});

test("family and staff experiences keep internal notes out of family controls", () => {
  assert.doesNotMatch(family, /internalNote/);
  assert.match(staff, /Internal note — never shown to the family/);
  assert.match(staff, /assignedToMe/);
  assert.match(staff, /expectedVersion:\s*detail\.request\.rowVersion/);
});

test("support screens expose labels, live status, mobile layout, focus, and reduced motion", () => {
  assert.match(family, /aria-label="Choose child"/);
  assert.match(family, /role=\{error \? "alert" : "status"\}/);
  assert.match(staff, /aria-label="Parent request queue"/);
  assert.match(styles, /\.request-centre :is\(input, select, textarea, button\):focus-visible/);
  assert.match(styles, /@media \(max-width: 560px\)/);
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/);
});

// Execute the actual handler, including its await boundary, without a DOM or
// network mock that could hide React clearing event.currentTarget after dispatch.
function compiledStaffHandler(name) {
  const source = ts.createSourceFile("workspace.tsx", staff, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let handler;
  function visit(node) {
    if (ts.isFunctionDeclaration(node) && node.name?.text === name) handler = node;
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.ok(handler, `staff ${name} handler must exist`);
  return ts.transpileModule(`const handler = ${handler.getText(source)};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
}

function staffReplyHandler(mutate) {
  const emitted = compiledStaffHandler("reply");
  class Fields {
    constructor(form) { this.form = form; }
    get(key) { return this.form.values[key] ?? null; }
  }
  return new Function("detail", "mutate", "FormData", "crypto", `${emitted}\nreturn handler;`)(
    { request: { id: "synthetic-request", rowVersion: 2 } }, mutate, Fields,
    { randomUUID: () => "synthetic-retry-key" },
  );
}

test("staff reply retains the form across React event dispatch and clears only a successful submission", async () => {
  let finish;
  let submitted;
  let resets = 0;
  const form = { values: { message: "Synthetic response" }, reset: () => { resets++; } };
  const event = { currentTarget: form, preventDefault() {} };
  const reply = staffReplyHandler((path, body) => {
    submitted = { path, body };
    return new Promise((resolve) => { finish = resolve; });
  });
  const pending = reply(event);
  event.currentTarget = null;
  assert.equal(resets, 0, "do not clear a pending response");
  finish(true);
  await pending;
  assert.equal(resets, 1);
  assert.equal(submitted.body.message, "Synthetic response");
  assert.equal(submitted.body.expectedVersion, 2);
  assert.equal(submitted.body.internalNote, false);
});

test("failed staff response preserves the draft for retry", async () => {
  let resets = 0;
  const form = { values: { message: "Keep this synthetic draft", internalNote: "on" }, reset: () => { resets++; } };
  const event = { currentTarget: form, preventDefault() {} };
  const pending = staffReplyHandler(async () => false)(event);
  event.currentTarget = null;
  await pending;
  assert.equal(resets, 0);
  assert.equal(form.values.message, "Keep this synthetic draft");
});

test("staff mutation distinguishes a rejected save from an acknowledged save with a failed refresh", async () => {
  for (const saved of [false, true]) {
    let error = "";
    let busy = false;
    let refreshes = 0;
    const handler = new Function("data", "detail", "setBusy", "setError", "setNotice", "parentRequestMutation", "load", "openDetail",
      `${compiledStaffHandler("mutate")}\nreturn handler;`)(
      { schoolId: "synthetic-school" }, { request: { id: "synthetic-request" } },
      (value) => { busy = value; }, (value) => { error = value; }, () => {},
      async () => { if (!saved) throw new Error("Save rejected"); },
      async () => { refreshes++; throw new Error("Refresh unavailable"); }, () => {},
    );
    assert.equal(await handler("/synthetic", {}, "Saved"), saved);
    assert.equal(busy, false);
    assert.equal(refreshes, saved ? 1 : 0);
    assert.equal(error, saved
      ? "The change was saved, but updated records could not be loaded. Refresh before making another change."
      : "Save rejected");
  }
});

test("request decisions require a meaningful bounded reason before calling the existing transition API", async () => {
  const calls = [];
  const errors = [];
  class Fields {
    constructor(form) { this.form = form; }
    get() { return this.form.reason; }
  }
  const handler = new Function("detail", "decision", "busy", "FormData", "setError", "mutate", "label", "setDecision", "detailHeading",
    `${compiledStaffHandler("confirmTransition")}\nreturn handler;`)(
    { request: { id: "synthetic-request", rowVersion: 7 } }, "RESOLVE", false, Fields,
    (error) => errors.push(error), async (...args) => { calls.push(args); return true; },
    (value) => value, () => {}, { current: { focus() {} } },
  );
  for (const reason of ["   ", "ab", "x".repeat(501)]) {
    await handler({ preventDefault() {}, currentTarget: { reason } });
  }
  assert.equal(calls.length, 0);
  assert.equal(errors.length, 3);
  await handler({ preventDefault() {}, currentTarget: { reason: "  Synthetic resolution  " } });
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], "/api/v1/parent-requests/synthetic-request/transitions");
  assert.deepEqual(calls[0][1], { action: "RESOLVE", expectedVersion: 7, reason: "Synthetic resolution" });
});
