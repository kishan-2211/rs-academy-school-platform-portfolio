import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { accountingText, accountingCode, accountingError, AccountingUiError } from "./i18n/accounting-messages.ts";

const text = readFileSync(new URL("../components/workspace/accounting-workspace.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("accounting.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const functions = new Map();
let confirmation;
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) functions.set(node.name.text, node);
  if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === "mutate") functions.set("mutate", node.initializer.arguments[0]);
  if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some((attribute) =>
    ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className" &&
    attribute.initializer && ts.isStringLiteral(attribute.initializer) &&
    attribute.initializer.text.includes("accounting-confirmation"))) confirmation = node;
  ts.forEachChild(node, visit);
}
visit(source);
function compile(name, bindings) {
  assert.ok(functions.has(name), name);
  const compiled = ts.transpileModule(`const handler = ${functions.get(name).getText(source)};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const context = { accountingText, accountingCode, accountingError, AccountingUiError, ...bindings }; return new Function(...Object.keys(context), `${compiled}\nreturn handler;`)(...Object.values(context));
}
const label = (value) => value.toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
const period = { id: "synthetic-period", name: "Synthetic October", rowVersion: 2, status: "OPEN", startsOn: "2026-10-01", endsOn: "2026-10-31" };
const journal = { id: "synthetic-journal", narration: "Synthetic voucher", voucherNumber: "SYN-0001", rowVersion: 4 };
const request = { id: "synthetic-reversal", requesterName: "Synthetic requester", rowVersion: 2 };

for (const [name, args, path, body] of [
  ["transitionYear", [{ id: "year", name: "Synthetic year", rowVersion: 2 }, "open"], "/financial-years/year/open", { expectedVersion: 2 }],
  ["transitionYear", [{ id: "year", name: "Synthetic year", rowVersion: 3 }, "close"], "/financial-years/year/close", { expectedVersion: 3 }],
  ["transitionPeriod", [period, "LOCK"], "/periods/synthetic-period/transition", { transition: "LOCK", expectedVersion: 2 }],
  ["submitJournal", [journal], "/journals/synthetic-journal/submit", { expectedVersion: 4 }],
  ["decideJournal", [journal, "POST"], "/journals/synthetic-journal/decision", { decision: "POST", expectedVersion: 4 }],
  ["decideJournal", [journal, "REJECT"], "/journals/synthetic-journal/decision", { decision: "REJECT", expectedVersion: 4 }],
  ["requestReversal", [journal], "/journals/synthetic-journal/reversal-requests", {}],
  ["decideReversal", [request, "APPROVE"], "/reversal-requests/synthetic-reversal/decision", { decision: "APPROVE", expectedVersion: 2 }],
  ["decideReversal", [request, "REJECT"], "/reversal-requests/synthetic-reversal/decision", { decision: "REJECT", expectedVersion: 2 }],
]) {
  test(`${name} ${args[1] ?? ""} only prepares the existing scoped action before confirmation`, () => {
    let prepared;
    compile(name, { data: { schoolId: "synthetic-school" }, postingPeriods: [period], label, beginDecision: (next) => { prepared = next; } })(...args);
    assert.equal(prepared.path, path);
    assert.deepEqual(prepared.body, { schoolId: "synthetic-school", ...body });
    assert.ok(prepared.title.includes("Synthetic") || prepared.title.includes("SYN-0001"));
    assert.equal(prepared.body.reason, undefined);
    if (name === "requestReversal") assert.deepEqual(prepared.reversalPeriods, [period]);
  });
}

function confirmationBindings(overrides = {}) {
  const state = { errors: [], calls: [], decisions: [], focused: 0 };
  const bindings = {
    decision: { title: "Post synthetic voucher?", path: "/journals/synthetic-journal/decision", body: { schoolId: "synthetic-school", decision: "POST", expectedVersion: 4 }, success: "Synthetic posted" },
    busy: false, refreshRequired: false, decisionInFlight: { current: false },
    FormData: class { constructor(form) { this.form = form; } get(key) { return this.form.values[key] ?? null; } },
    setError: (value) => state.errors.push(value), setDecision: (value) => state.decisions.push(value),
    noticeTarget: { current: { focus() { state.focused++; } } },
    decisionReason: { current: { focus() { state.reasonFocused = true; } } },
    requestAnimationFrame: (callback) => callback(),
    mutate: async (...args) => { state.calls.push(args); return { id: "synthetic-journal" }; },
    ...overrides,
  };
  return { state, bindings, handler: compile("confirmDecision", bindings) };
}
const event = (reason, postingDate) => ({ preventDefault() {}, currentTarget: { values: { reason, postingDate } } });

for (const reason of ["", "   ", " a ", "x".repeat(501)]) {
  test(`invalid audit reason (${reason.length} characters) cannot call the API`, async () => {
    const { state, handler } = confirmationBindings();
    await handler(event(reason));
    assert.equal(state.calls.length, 0);
    assert.match(state.errors[0], /3 and 500/);
    assert.equal(state.decisions.length, 0);
  });
}

test("successful confirmation sends the trimmed reason and captured version, then closes and focuses the saved status", async () => {
  const { state, bindings, handler } = confirmationBindings();
  await handler(event("  Synthetic independent decision  "));
  assert.deepEqual(state.calls, [[bindings.decision.path, "POST", { ...bindings.decision.body, reason: "Synthetic independent decision" }, "Synthetic posted"]]);
  assert.deepEqual(state.decisions, [null]);
  assert.equal(state.focused, 1);
  assert.equal(bindings.decisionInFlight.current, false);
});

test("a rejected save keeps the confirmation and its untouched form for a reasoned retry", async () => {
  const { state, handler } = confirmationBindings({ mutate: async () => null });
  const submitted = event("Synthetic rejection");
  await handler(submitted);
  assert.equal(state.decisions.length, 0);
  assert.equal(state.focused, 0);
  assert.equal(state.reasonFocused, true);
  assert.equal(submitted.currentTarget.values.reason, "Synthetic rejection");
});

for (const override of [{ busy: true }, { refreshRequired: true }, { decisionInFlight: { current: true } }, { decision: null }]) {
  test(`confirmation blocks unsafe state ${Object.keys(override)[0]}`, async () => {
    const { state, handler } = confirmationBindings(override);
    await handler(event("Synthetic reason"));
    assert.equal(state.calls.length, 0);
  });
}

test("a rapid duplicate submission cannot run twice before React updates busy", async () => {
  let finish;
  let requests = 0;
  const { handler } = confirmationBindings({ mutate: () => { requests++; return new Promise((resolve) => { finish = resolve; }); } });
  const first = handler(event("Synthetic reason"));
  await handler(event("Synthetic reason"));
  assert.equal(requests, 1);
  finish({ id: "synthetic-journal" });
  await first;
});

for (const date of ["", "2026-09-30", "2026-11-01", "not-a-date"]) {
  test(`reversal date ${date || "empty"} must belong to an open accounting period`, async () => {
    const { state, handler } = confirmationBindings({ decision: { path: "/reversal", body: { schoolId: "synthetic-school" }, reversalPeriods: [period] } });
    await handler(event("Synthetic reversal", date));
    assert.equal(state.calls.length, 0);
    assert.match(state.errors[0], /open periods/);
  });
}
test("reversal uses the chosen open period, preserves the posted source and requests independent approval", async () => {
  const { state, handler } = confirmationBindings({ decision: { path: "/journals/synthetic-journal/reversal-requests", body: { schoolId: "synthetic-school" }, reversalPeriods: [period] } });
  await handler(event("Synthetic reversal", "2026-10-04"));
  assert.deepEqual(state.calls[0][2], { schoolId: "synthetic-school", reason: "Synthetic reversal", requestedAccountingPeriodId: "synthetic-period", requestedPostingDate: "2026-10-04" });
});

test("cancel makes no mutation and returns focus to the connected trigger", () => {
  let focused = 0; let cancelled;
  compile("cancelDecision", { busy: false, decisionInFlight: { current: false }, setDecision: (value) => { cancelled = value; }, setError: () => {}, decisionTrigger: { current: { isConnected: true, focus() { focused++; } } } })();
  assert.equal(cancelled, null); assert.equal(focused, 1);
});

test("the actual inline form has a named reason, native keyboard controls, Escape cancellation and a conditional date", () => {
  assert.ok(confirmation);
  const compiled = ts.transpileModule(`const form = (${confirmation.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
  let cancelled = 0;
  const render = (reversalPeriods, busy) => new Function("React", "decision", "busy", "confirmDecision", "cancelDecision", "localToday", "decisionPanel", "decisionReason", `const t = (source) => source;\n${compiled}\nreturn form;`)(React, { title: "Synthetic decision", reversalPeriods }, busy, () => {}, () => { cancelled++; }, () => "2026-10-04", { current: null }, { current: null });
  const element = render(undefined, false);
  const html = renderToStaticMarkup(element);
  assert.match(html, /aria-labelledby="accounting-decision-title"/);
  assert.match(html, /aria-describedby="accounting-decision-help"/);
  assert.match(html, /<label[^>]*>Reason for accounting action<textarea[^>]*name="reason"[^>]*minLength="3"[^>]*maxLength="500"/);
  assert.match(html, /type="submit"/); assert.match(html, /type="button"/);
  assert.doesNotMatch(html, /postingDate/);
  element.props.onKeyDown({ key: "Escape", preventDefault() {} }); assert.equal(cancelled, 1);
  const reversal = renderToStaticMarkup(render([period], true));
  assert.match(reversal, /Counter-voucher posting date<input[^>]*type="date"/);
  assert.match(reversal, /aria-busy="true"/); assert.match(reversal, /disabled=""/);
  assert.doesNotMatch(text, /window\.(prompt|confirm)\(/);
});

test("an acknowledged write with failed reload cannot invite an unsafe resubmission", async () => {
  let required = false; let error; let notice;
  const result = { id: "synthetic-journal", rowVersion: 5 };
  const bindings = { refreshRequired: false, apiUrl: "http://localhost:8080", csrfToken: async () => "synthetic-token", fetch: async () => ({ ok: true, json: async () => result }), load: async () => { throw new Error("Synthetic refresh failed"); }, setRefreshRequired: (value) => { required = value; }, setBusy: () => {}, setError: (value) => { error = value; }, setNotice: (value) => { notice = value; } };
  assert.equal(await compile("mutate", bindings)("/journal", "POST", {}, "Synthetic saved"), result);
  assert.equal(required, true); assert.equal(notice, "Synthetic saved"); assert.match(error, /change was saved.*Reload/);
  assert.equal(await compile("mutate", { ...bindings, refreshRequired: true, fetch: () => { assert.fail("Must reload before another mutation"); } })("/journal", "POST", {}, "Synthetic saved"), null);
});

test("confirmation styling uses defined theme tokens, readable controls and the existing reduced-motion rules", () => {
  const css = readFileSync(new URL("../app/workspace/accounting/accounting.css", import.meta.url), "utf8");
  const globals = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");
  const rules = css.split(".accounting-confirmation").slice(1).join(".accounting-confirmation").split("@media")[0];
  for (const [, token] of rules.matchAll(/var\((--[\w-]+)\)/g)) assert.ok(globals.includes(`${token}:`), token);
  assert.match(rules, /min-height: 2.75rem/);
  assert.match(rules, /outline-color: var\(--theme-success-text\)/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
});
