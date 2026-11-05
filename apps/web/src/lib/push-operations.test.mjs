import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as operations from "./push-operations.ts";

const snapshot = { schoolId: "synthetic-school", canApprove: true, transportEnabled: false, workerEnabled: false,
  providerStatus: "NOT_CONFIGURED", providerVersion: null, providerCompatible: true,
  windowStartsAt: "2030-01-01T00:00:00Z", parentStates: [], deviceStates: [], failureCodes: [] };

function cookie(t, value = "educore_csrf=synthetic%2Dcsrf") {
  const prior = Object.getOwnPropertyDescriptor(globalThis, "document");
  Object.defineProperty(globalThis, "document", { configurable: true, value: { cookie: value } });
  t.after(() => { if (prior) Object.defineProperty(globalThis, "document", prior); else delete globalThis.document; });
}

test("operations reads are school-scoped, authenticated, uncached and cancellable", async (t) => {
  const controller = new AbortController();
  t.mock.method(globalThis, "fetch", async (url, init) => {
    assert.equal(new URL(url).searchParams.get("schoolId"), snapshot.schoolId);
    assert.equal(init.credentials, "include"); assert.equal(init.cache, "no-store");
    assert.equal(init.signal, controller.signal);
    return Response.json(snapshot);
  });
  assert.deepEqual(await operations.loadPushOperations(snapshot.schoolId, controller.signal), snapshot);
});

test("provider decisions carry CSRF, exact version, reason and school, never configuration secrets", async (t) => {
  cookie(t);
  t.mock.method(globalThis, "fetch", async (url, init) => {
    assert.match(url, /push-operations\/provider$/);
    assert.equal(init.method, "PUT"); assert.equal(init.credentials, "include"); assert.equal(init.cache, "no-store");
    assert.equal(init.headers["X-CSRF-TOKEN"], "synthetic-csrf");
    assert.deepEqual(JSON.parse(init.body), { schoolId: snapshot.schoolId, status: "ACTIVE", expectedVersion: null, reason: "Synthetic decision" });
    return Response.json({ ...snapshot, providerStatus: "ACTIVE", providerVersion: 0 });
  });
  assert.equal((await operations.changePushProvider(snapshot.schoolId, "ACTIVE", null, "  Synthetic decision  ")).providerVersion, 0);
});

test("missing CSRF refreshes the session but never submits without a token", async (t) => {
  cookie(t, ""); let calls = 0;
  t.mock.method(globalThis, "fetch", async (url, init) => {
    calls++; assert.match(url, /sessions\/current$/); assert.equal(init.cache, "no-store");
    return Response.json({});
  });
  await assert.rejects(operations.changePushProvider(snapshot.schoolId, "ACTIVE", null, "Synthetic approval"), /secure form token/);
  assert.equal(calls, 1);
});

test("invalid reasons never reach the network", async (t) => {
  t.mock.method(globalThis, "fetch", () => assert.fail("Invalid input must not be submitted"));
  for (const reason of ["  ", "ab", "x".repeat(501), "bad\nreason"]) {
    await assert.rejects(operations.changePushProvider(snapshot.schoolId, "ACTIVE", null, reason), /decision reason/);
  }
});

test("stale, unauthorized and cross-school responses fail closed without leaking server internals", async (t) => {
  let response = new Response("private internal detail", { status: 409 });
  t.mock.method(globalThis, "fetch", async () => response);
  await assert.rejects(operations.loadPushOperations(snapshot.schoolId), /Refresh/);
  response = new Response("private internal detail", { status: 403 });
  await assert.rejects(operations.loadPushOperations(snapshot.schoolId), /school role or MFA/);
  response = new Response("private internal detail", { status: 401 });
  await assert.rejects(operations.loadPushOperations(snapshot.schoolId), /session has ended/);
  response = Response.json({ ...snapshot, schoolId: "other-school" });
  await assert.rejects(operations.loadPushOperations(snapshot.schoolId), /school scope changed/);
});

const componentPath = fileURLToPath(new URL("../components/workspace/push-operations-panel.tsx", import.meta.url));
const source = readFileSync(componentPath, "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: {
  module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022,
} }).outputText;
const componentModule = { exports: {} };
const require = createRequire(componentPath);
new vm.Script("(function(require,module,exports){" + compiled + "\n})", { filename: componentPath })
  .runInThisContext()((id) => id === "@/lib/push-operations" ? operations : require(id), componentModule, componentModule.exports);

test("initial SSR exposes named loading status without fabricating authenticated operating data", () => {
  const html = renderToStaticMarkup(React.createElement(componentModule.exports.PushOperationsPanel, { schoolId: snapshot.schoolId }));
  assert.match(html, /aria-labelledby="push-operations-title"/);
  assert.match(html, /aria-busy="true"/);
  assert.match(html, /role="status"/);
  assert.match(html, /disabled=""/);
  assert.doesNotMatch(html, /Confirm approval|Confirm suspension|synthetic-school/);
});

test("panel contracts retain labelled native controls, confirmation, focus and narrow responsive layout", () => {
  assert.match(source, /htmlFor="push-provider-reason"/);
  assert.match(source, /id="push-provider-reason"/);
  assert.match(source, /type="checkbox"[^\n]+required/);
  assert.match(source, /onSubmit=\{decide\}/);
  assert.match(source, /setData\(null\); setConfirmed\(false\)/);
  assert.match(source, /not proof that a phone displayed/);
  assert.doesNotMatch(source, /localStorage|sessionStorage|dangerouslySetInnerHTML/);
  const css = readFileSync(new URL("../app/workspace/communication/push-operations.css", import.meta.url), "utf8");
  assert.match(css, /focus-visible/); assert.match(css, /min-height: 44px/);
  assert.match(css, /max-width: 900px/); assert.match(css, /grid-template-columns: 1fr/);
  assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b|animation:/i);
});
