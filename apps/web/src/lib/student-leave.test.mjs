import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { leaveApi, leaveAttempt } from "./student-leave.ts";

test("unchanged failed submissions reuse their retry key; edits and child switches do not", () => {
  const payload = { learnerId: "synthetic-child-a", reason: "Synthetic reason", startsOn: "2026-09-17" };
  const first = leaveAttempt(null, payload, () => "synthetic-key-a");
  assert.equal(leaveAttempt(first, { ...payload }, () => "wrong-new-key"), first);
  assert.equal(leaveAttempt(first, { ...payload, learnerId: "synthetic-child-b" }, () => "synthetic-key-b").key, "synthetic-key-b");
  assert.equal(leaveAttempt(first, { ...payload, reason: "Changed synthetic reason" }, () => "synthetic-key-c").key, "synthetic-key-c");
});

test("leave reads are authenticated, uncached and abortable", async (t) => {
  const controller = new AbortController();
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    assert.equal(init.credentials, "include"); assert.equal(init.cache, "no-store");
    assert.equal(init.signal, controller.signal); assert.equal(init.method, "GET");
    return new Response(JSON.stringify({ records: [] }), { status: 200 });
  });
  assert.deepEqual(await leaveApi("/api/v1/student-leave", { signal: controller.signal }), { records: [] });
});

test("policy and decisions preserve explicit payload and CSRF without storing credentials", async (t) => {
  const original = Object.getOwnPropertyDescriptor(globalThis, "document");
  Object.defineProperty(globalThis, "document", { value: { cookie: "educore_csrf=synthetic%20csrf" }, configurable: true });
  t.after(() => { if (original) Object.defineProperty(globalThis, "document", original); else delete globalThis.document; });
  const body = { schoolId: "synthetic-school", expectedVersion: 2, reason: "Synthetic approval" };
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    assert.equal(init.method, "PUT"); assert.equal(init.headers["X-CSRF-TOKEN"], "synthetic csrf");
    assert.deepEqual(JSON.parse(init.body), body); assert.equal(init.credentials, "include");
    return new Response(JSON.stringify({ rowVersion: 3 }), { status: 200 });
  });
  assert.deepEqual(await leaveApi("/api/v1/student-leave/policy", { method: "PUT", body }), { rowVersion: 3 });
});

test("missing CSRF blocks writes and server errors do not leak diagnostic text", async (t) => {
  const original = Object.getOwnPropertyDescriptor(globalThis, "document");
  Object.defineProperty(globalThis, "document", { value: { cookie: "" }, configurable: true });
  t.after(() => { if (original) Object.defineProperty(globalThis, "document", original); else delete globalThis.document; });
  let calls = 0;
  t.mock.method(globalThis, "fetch", async (_url, init) => { calls++; assert.notEqual(init.method, "POST"); return new Response("diagnostic must not surface", { status: 500 }); });
  await assert.rejects(leaveApi("/api/v1/student-leave/id/decision", { method: "POST", body: {} }), /secure form token/);
  assert.equal(calls, 1);
  await assert.rejects(leaveApi("/api/v1/student-leave"), (error) => !error.message.includes("diagnostic") && error.message.includes("unavailable"));
});

test("unauthorized, stale and cross-scope failures remain failures rather than empty records", async (t) => {
  for (const status of [401,403,404,409]) {
    t.mock.method(globalThis, "fetch", async () => new Response(JSON.stringify({ detail: "Synthetic access or version rejected" }), { status }));
    await assert.rejects(leaveApi("/api/v1/student-leave"), /Synthetic access or version rejected/);
    t.mock.restoreAll();
  }
});

test("shared UI retains labelled forms, confirmation, private state cleanup and existing responsive tokens", () => {
  const ui = readFileSync(new URL("../components/student-leave-centre.tsx", import.meta.url), "utf8");
  const helper = readFileSync(new URL("./student-leave.ts", import.meta.url), "utf8");
  const css = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");
  assert.match(ui, /key=\{`\$\{family\}:\$\{learnerId\}`\}/);
  assert.match(ui, /controller\.abort\(\)/); assert.match(ui, /role="status"/); assert.match(ui, /role="alert"/);
  assert.match(ui, /aria-pressed=/); assert.match(ui, /heading\.current\?\.focus\(\)/);
  assert.match(ui, /type="checkbox" required/); assert.match(ui, /expectedVersion: detail\.request\.rowVersion/);
  assert.match(ui, /minLength=\{3\}/); assert.match(ui, /<label>Reason<textarea/);
  assert.doesNotMatch(ui + helper, /localStorage|sessionStorage/);
  assert.match(css, /\.request-centre :is\(input, select, textarea, button\):focus-visible/);
  assert.match(css, /@media \(max-width: 560px\)/); assert.match(css, /prefers-reduced-motion/);
});
