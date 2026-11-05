import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const text = readFileSync(new URL("../components/workspace/school-calendar-workspace.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("calendar.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const functions = new Map();
let confirmationForm;
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) functions.set(node.name.text, node);
  if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some((attribute) =>
    ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className" &&
    attribute.initializer && ts.isStringLiteral(attribute.initializer) && attribute.initializer.text === "calendar-confirmation")) confirmationForm = node;
  ts.forEachChild(node, visit);
}
visit(source);
function compile(name, bindings) {
  assert.ok(functions.has(name), name);
  const compiled = ts.transpileModule(`const handler = ${functions.get(name).getText(source)};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(bindings), `${compiled}\nreturn handler;`)(...Object.values(bindings));
}
const scope = { schoolId: "synthetic-school", selectedCampusId: "synthetic-campus", selectedAcademicSessionId: "synthetic-session" };
const item = { noticeSeriesId: "synthetic-series", revisionId: "synthetic-revision", revisionNumber: 2, calendarDate: "2026-10-06", titleEn: "Synthetic holiday only", titleHi: "केवल परीक्षण", bodyEn: "Not a real closure", bodyHi: "वास्तविक बंद नहीं", sourceReference: "SYN_LOCAL_ONLY" };
const review = { id: "synthetic-review", noticeRevisionId: item.revisionId, rowVersion: 4, ...item };

for (const [name, args, path, expected, evidenceRequired, maximum] of [
  ["submitForReview", [item], "/notices/synthetic-series/reviews", { expectedRevision: 2 }, true, 500],
  ["decide", [review, "APPROVED"], "/reviews/synthetic-review/decision", { expectedVersion: 4, decision: "APPROVED" }, false, 1000],
  ["decide", [review, "REJECTED"], "/reviews/synthetic-review/decision", { expectedVersion: 4, decision: "REJECTED" }, false, 1000],
]) {
  test(`${name} ${args[1] ?? ""} captures existing scope, revision, bilingual evidence and contract limits without a write`, () => {
    let prepared;
    compile(name, { data: { ...scope, notices: [item] }, beginConfirmation: (next) => { prepared = next; } })(...args);
    assert.equal(prepared.path, `/api/v1/school-calendar${path}`);
    assert.deepEqual(prepared.body, { schoolId: scope.schoolId, ...expected });
    assert.equal(prepared.campusId, scope.selectedCampusId);
    assert.equal(prepared.sessionId, scope.selectedAcademicSessionId);
    for (const key of ["calendarDate", "titleEn", "titleHi", "bodyEn", "bodyHi", "sourceReference"]) assert.equal(prepared[key], item[key]);
    assert.equal(prepared.evidenceRequired, evidenceRequired);
    assert.equal(prepared.reasonMaxLength, maximum);
    assert.equal(prepared.body.reason, undefined);
  });
}

function bindingsFor(overrides = {}) {
  const state = { calls: [], errors: [], closed: [], focus: [] };
  const bindings = {
    confirmation: { ...item, campusId: scope.selectedCampusId, sessionId: scope.selectedAcademicSessionId, path: "/api/v1/school-calendar/notices/synthetic-series/reviews", body: { schoolId: scope.schoolId, expectedRevision: 2 }, evidenceRequired: true, reasonMaxLength: 500, success: "Synthetic submitted" },
    data: scope, busy: false, refreshRequired: false, confirmationInFlight: { current: false },
    FormData: class { constructor(form) { this.form = form; } get(key) { return this.form.values[key] ?? null; } },
    mutate: async (...args) => { state.calls.push(args); return true; },
    setError: (value) => state.errors.push(value), setConfirmation: (value) => state.closed.push(value),
    requestAnimationFrame: (action) => action(), noticeTarget: { current: { focus: () => state.focus.push("saved") } },
    confirmationReason: { current: { focus: () => state.focus.push("reason") } }, ...overrides,
  };
  return { state, bindings, handler: compile("confirmCalendarAction", bindings) };
}
const event = (reason = "Synthetic reason", evidenceConfirmed = "on") => ({ preventDefault() {}, currentTarget: { values: { reason, evidenceConfirmed } } });

for (const reason of ["", "   ", "ab", "x".repeat(501)]) {
  test(`invalid trimmed submission reason (${reason.length} characters) cannot write`, async () => {
    const { state, handler } = bindingsFor();
    await handler(event(reason));
    assert.equal(state.calls.length, 0); assert.equal(state.closed.length, 0);
    assert.match(state.errors.at(-1), /between 3 and 500/);
  });
}
test("submission requires actual evidence acknowledgement rather than a hard-coded confirmation", async () => {
  const { state, handler } = bindingsFor();
  await handler(event("Synthetic reason", null));
  assert.equal(state.calls.length, 0); assert.match(state.errors.at(-1), /Confirm the date/);
});
test("confirmed submission sends captured school/revision and trimmed reason, then focuses saved status", async () => {
  const { state, bindings, handler } = bindingsFor();
  await handler(event("  Synthetic reason  "));
  assert.equal(state.calls.length, 1);
  assert.deepEqual(JSON.parse(state.calls[0][1].body), { schoolId: scope.schoolId, expectedRevision: 2, reason: "Synthetic reason", evidenceConfirmed: true });
  assert.equal(state.calls[0][1].method, "POST");
  assert.deepEqual(state.closed, [null]); assert.deepEqual(state.focus, ["saved"]);
  assert.equal(bindings.confirmationInFlight.current, false);
});
test("decision preserves the API's 1000-character limit and does not add submission-only fields", async () => {
  const setup = bindingsFor();
  const { state, handler } = bindingsFor({ confirmation: { ...setup.bindings.confirmation, body: { schoolId: scope.schoolId, expectedVersion: 4, decision: "REJECTED" }, evidenceRequired: false, reasonMaxLength: 1000 } });
  await handler(event("x".repeat(1000), null));
  assert.equal(state.calls.length, 1);
  assert.equal(JSON.parse(state.calls[0][1].body).evidenceConfirmed, undefined);
  assert.equal(JSON.parse(state.calls[0][1].body).decision, "REJECTED");
});
test("failed server decision retains the confirmation and focuses the untouched reason", async () => {
  const { state, handler } = bindingsFor({ mutate: async () => false });
  await handler(event());
  assert.deepEqual(state.closed, []); assert.deepEqual(state.focus, ["reason"]);
});
for (const overrides of [{ busy: true }, { refreshRequired: true }, { confirmation: null }, { confirmationInFlight: { current: true } }]) {
  test(`unsafe confirmation state ${Object.keys(overrides)[0]} cannot write`, async () => {
    const { state, handler } = bindingsFor(overrides);
    await handler(event()); assert.equal(state.calls.length, 0);
  });
}
for (const changed of [null, { ...scope, schoolId: "other-school" }, { ...scope, selectedCampusId: "other-campus" }, { ...scope, selectedAcademicSessionId: "other-session" }]) {
  test(`changed scope ${changed?.schoolId ?? "none"}/${changed?.selectedCampusId ?? "none"}/${changed?.selectedAcademicSessionId ?? "none"} cannot confirm a stale action`, async () => {
    const { state, handler } = bindingsFor({ data: changed });
    await handler(event()); assert.equal(state.calls.length, 0);
    assert.match(state.errors.at(-1), /selected calendar changed/);
  });
}
test("rapid duplicate confirmation cannot run twice before React updates busy", async () => {
  let finish; let requests = 0;
  const { handler } = bindingsFor({ mutate: () => { requests++; return new Promise((resolve) => { finish = resolve; }); } });
  const first = handler(event()); await handler(event()); assert.equal(requests, 1);
  finish(true); await first;
});
test("cancellation writes nothing and restores its connected trigger", () => {
  let cancelled; let focused = 0; let nextFrame; let disabled = true;
  compile("cancelConfirmation", { busy: false, confirmationInFlight: { current: false }, setConfirmation: (value) => { cancelled = value; }, setError: () => {}, requestAnimationFrame: (action) => { nextFrame = action; }, confirmationTrigger: { current: { isConnected: true, focus: () => { assert.equal(disabled, false, "Wait for React to re-enable the confirmation trigger"); focused++; } } } })();
  assert.equal(cancelled, null); assert.equal(focused, 0);
  disabled = false; nextFrame(); assert.equal(focused, 1);
});
test("actual confirmation form exposes context, languages, native keyboard controls, reason limits and pending state", () => {
  assert.ok(confirmationForm);
  const compiled = ts.transpileModule(`const form = (${confirmationForm.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
  let cancelled = 0;
  const render = (busy) => new Function("React", "confirmation", "busy", "shortDate", "confirmCalendarAction", "cancelConfirmation", "confirmationPanel", "confirmationReason", `${compiled}\nreturn form;`)(React, { ...bindingsFor().bindings.confirmation, title: "Synthetic submission" }, busy, (value) => value, () => {}, () => { cancelled++; }, { current: null }, { current: null });
  const element = render(false); const html = renderToStaticMarkup(element);
  assert.match(html, /aria-labelledby="calendar-confirmation-title"/);
  assert.match(html, /aria-describedby="calendar-confirmation-help"/);
  assert.match(html, /<input(?=[^>]*name="evidenceConfirmed")(?=[^>]*required="")[^>]*>/);
  assert.match(html, /Reason for calendar action<textarea[^>]*minLength="3"[^>]*maxLength="500"/);
  assert.match(html, /<time dateTime="2026-10-06"/);
  assert.match(html, /lang="hi"/); assert.match(html, /<summary>Review bilingual messages and source/);
  assert.match(html, /Not a real closure/); assert.match(html, /SYN_LOCAL_ONLY/);
  assert.match(html, /type="submit"/); assert.match(html, /type="button"/);
  element.props.onKeyDown({ key: "Escape", preventDefault() {} }); assert.equal(cancelled, 1);
  const pending = renderToStaticMarkup(render(true)); assert.match(pending, /aria-busy="true"/); assert.match(pending, /disabled=""/);
  assert.doesNotMatch(text, /window\.(confirm|prompt)\(/);
});
test("confirmation uses semantic colors, 44px actions, visible focus and preserved reduced-motion rules", () => {
  const css = readFileSync(new URL("../app/workspace/calendar/school-calendar.css", import.meta.url), "utf8");
  assert.match(css, /\.calendar-confirmation textarea[^}]*background:var\(--theme-input\);color:var\(--theme-text\)/);
  assert.match(css, /\.calendar-confirmation button[^}]*min-height:44px/);
  assert.match(css, /\.calendar-confirmation :focus-visible\{outline-color:var\(--theme-success-text\)/);
  assert.match(css, /\.calendar-workspace :is\(button,input,select,textarea\):focus-visible\{outline:3px solid var\(--theme-success-text\)/);
  assert.match(css, /prefers-reduced-motion:reduce/);
});

function mutationBindings(overrides = {}) {
  const state = { refreshes: 0, required: false, notices: [], errors: [], busy: [] };
  const bindings = { apiUrl: "http://localhost:8080", data: scope, refreshRequired: false,
    fetch: async () => ({ ok: true }), csrfToken: async () => "synthetic-token", problem: async () => "Synthetic rejection",
    load: async () => { state.refreshes++; }, setBusy: (value) => state.busy.push(value), setNotice: (value) => state.notices.push(value),
    setError: (value) => state.errors.push(value), setRefreshRequired: (value) => { state.required = value; }, ...overrides };
  return { state, handler: compile("mutate", bindings) };
}
test("acknowledged change refreshes the existing selected campus/session", async () => {
  let request; const { state, handler } = mutationBindings({ load: async (...args) => { request = args; } });
  assert.equal(await handler("/calendar", { method: "POST" }, "Synthetic saved"), true);
  assert.deepEqual(request, [scope.selectedCampusId, scope.selectedAcademicSessionId]); assert.equal(state.notices.at(-1), "Synthetic saved");
});
test("acknowledged save with failed reload remains saved and blocks new writes until verified reload", async () => {
  const { state, handler } = mutationBindings({ load: async () => { throw new Error("Synthetic reload failure"); } });
  assert.equal(await handler("/calendar", { method: "POST" }, "Synthetic saved"), true);
  assert.equal(state.required, true); assert.equal(state.notices.at(-1), "Synthetic saved"); assert.match(state.errors.at(-1), /change was saved.*Reload/);
  assert.deepEqual(state.busy, [true, false]);
});
test("stale records cannot issue another mutation", async () => {
  const { handler } = mutationBindings({ refreshRequired: true, fetch: () => assert.fail("No stale write") });
  assert.equal(await handler("/calendar", { method: "POST" }, "Synthetic saved"), false);
});
test("server rejection does not refresh or acknowledge a write", async () => {
  const { state, handler } = mutationBindings({ fetch: async () => ({ ok: false }) });
  assert.equal(await handler("/calendar", { method: "POST" }, "Synthetic saved"), false);
  assert.equal(state.refreshes, 0); assert.equal(state.notices.at(-1), ""); assert.equal(state.required, false); assert.equal(state.errors.at(-1), "Synthetic rejection");
});
