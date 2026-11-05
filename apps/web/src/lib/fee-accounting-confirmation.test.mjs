import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { accountingText, accountingCode, accountingError, AccountingUiError } from "./i18n/accounting-messages.ts";

const text = readFileSync(new URL("../components/workspace/fee-accounting-panel.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("fee-accounting.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
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
const configuration = { id: "synthetic-mapping", financialYearId: "synthetic-year", revisionNumber: 2, rowVersion: 4 };
const label = (value) => value.toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());

for (const saved of [true, false]) {
  test(`mapping save ${saved ? "clears only its acknowledged reason" : "keeps its rejected reason"} without resetting controlled account selectors`, async () => {
    let resets = 0; let cleared; let request;
    const form = { values: { reason: "Synthetic mapping" }, reset() { resets++; } };
    const state = { headMappings: { "synthetic-head": "synthetic-income" }, tenderMappings: { CASH: "synthetic-cash" } };
    const handler = compile("saveConfiguration", {
      schoolId: "synthetic-school", year: { id: "synthetic-year", accountingBasis: "ACCRUAL" },
      draft: undefined, approved: undefined, effectiveFrom: "2026-04-01",
      receivableAccountId: "synthetic-receivable", concessionAccountId: "synthetic-expense",
      snapshot: { feeHeads: [{ id: "synthetic-head" }] }, ...state, tenders: ["CASH"],
      FormData: class { constructor(form) { this.form = form; } get(key) { return this.form.values[key]; } },
      mutate: async (...args) => { request = args; return saved; },
      setChangeReason: (value) => { cleared = value; },
    });
    await handler({ currentTarget: form, preventDefault() {} });
    assert.equal(resets, 0, "Native reset incorrectly blanks controlled account selectors after load");
    assert.equal(cleared, saved ? "" : undefined);
    assert.deepEqual(request[2].headMappings, [{ feeHeadId: "synthetic-head", incomeAccountId: "synthetic-income" }]);
    assert.deepEqual(request[2].tenderMappings, [{ paymentMethod: "CASH", depositAccountId: "synthetic-cash" }]);
    assert.equal(state.headMappings["synthetic-head"], "synthetic-income");
    assert.equal(state.tenderMappings.CASH, "synthetic-cash");
  });
}

for (const [name, args, path, body] of [
  ["submitConfiguration", [configuration], "/configurations/synthetic-mapping/submit", { expectedVersion: 4 }],
  ["decideConfiguration", [configuration, "APPROVE"], "/configurations/synthetic-mapping/decision", { expectedVersion: 4, decision: "APPROVE" }],
  ["decideConfiguration", [configuration, "REJECT"], "/configurations/synthetic-mapping/decision", { expectedVersion: 4, decision: "REJECT" }],
]) {
  test(`${name} ${args[1] ?? ""} captures the existing scope/version without sending a mutation`, () => {
    let prepared;
    compile(name, { schoolId: "synthetic-school", label, beginDecision: (next) => { prepared = next; } })(...args);
    assert.equal(prepared.path, path);
    assert.deepEqual(prepared.body, { schoolId: "synthetic-school", ...body });
    assert.equal(prepared.financialYearId, "synthetic-year");
    assert.match(prepared.title, /fee mapping revision 2/);
    assert.equal(prepared.body.reason, undefined);
  });
}

function bindingsFor(overrides = {}) {
  const state = { errors: [], calls: [], decisions: [], focused: 0 };
  const bindings = {
    decision: { path: "/configurations/synthetic-mapping/decision", financialYearId: "synthetic-year", body: { schoolId: "synthetic-school", decision: "APPROVE", expectedVersion: 4 }, success: "Synthetic approved" },
    selectedYear: { id: "synthetic-year" }, busy: false, refreshRequired: false, decisionInFlight: { current: false },
    FormData: class { constructor(form) { this.form = form; } get(key) { return this.form.values[key] ?? null; } },
    setError: (value) => state.errors.push(value), setDecision: (value) => state.decisions.push(value),
    noticeTarget: { current: { focus() { state.focused++; } } },
    decisionReason: { current: { focus() { state.reasonFocused = true; } } },
    requestAnimationFrame: (callback) => callback(),
    mutate: async (...args) => { state.calls.push(args); return true; },
    ...overrides,
  };
  return { state, bindings, handler: compile("confirmDecision", bindings) };
}
const event = (reason) => ({ preventDefault() {}, currentTarget: { values: { reason } } });

for (const reason of ["", "   ", " a ", "x".repeat(501)]) {
  test(`invalid trimmed audit reason (${reason.length} characters) cannot call the API`, async () => {
    const { state, handler } = bindingsFor();
    await handler(event(reason));
    assert.equal(state.calls.length, 0);
    assert.match(state.errors[0], /3 and 500/);
    assert.equal(state.decisions.length, 0);
  });
}
test("confirmation sends the trimmed reason, school, decision and captured version only after submission", async () => {
  const { state, bindings, handler } = bindingsFor();
  await handler(event("  Synthetic independent decision  "));
  assert.deepEqual(state.calls, [[bindings.decision.path, "POST", { ...bindings.decision.body, reason: "Synthetic independent decision" }, "Synthetic approved"]]);
  assert.deepEqual(state.decisions, [null]);
  assert.equal(state.focused, 1);
  assert.equal(bindings.decisionInFlight.current, false);
});
test("rejection preserves the input and confirmation and restores reason focus", async () => {
  const { state, handler } = bindingsFor({ mutate: async () => false });
  const submitted = event("Synthetic denied");
  await handler(submitted);
  assert.equal(state.decisions.length, 0);
  assert.equal(state.focused, 0);
  assert.equal(state.reasonFocused, true);
  assert.equal(submitted.currentTarget.values.reason, "Synthetic denied");
});
for (const override of [{ busy: true }, { refreshRequired: true }, { decisionInFlight: { current: true } }, { decision: null }]) {
  test(`confirmation blocks unsafe state ${Object.keys(override)[0]}`, async () => {
    const { state, handler } = bindingsFor(override);
    await handler(event("Synthetic reason"));
    assert.equal(state.calls.length, 0);
  });
}
for (const selectedYear of [undefined, { id: "other-synthetic-year" }]) {
  test(`changed or absent book (${selectedYear?.id ?? "none"}) cannot confirm the old action`, async () => {
    const { state, handler } = bindingsFor({ selectedYear });
    await handler(event("Synthetic reason"));
    assert.equal(state.calls.length, 0);
    assert.match(state.errors[0], /selected book changed/);
  });
}
test("rapid duplicate confirmation cannot execute twice before the busy rerender", async () => {
  let finish;
  let requests = 0;
  const { handler } = bindingsFor({ mutate: () => { requests++; return new Promise((resolve) => { finish = resolve; }); } });
  const first = handler(event("Synthetic reason"));
  await handler(event("Synthetic reason"));
  assert.equal(requests, 1);
  finish(true);
  await first;
});
test("cancel restores the connected trigger without any mutation", () => {
  let focused = 0; let cancelled;
  compile("cancelDecision", { busy: false, decisionInFlight: { current: false }, setDecision: (value) => { cancelled = value; }, setError: () => {}, decisionTrigger: { current: { isConnected: true, focus() { focused++; } } } })();
  assert.equal(cancelled, null); assert.equal(focused, 1);
});
test("the actual form is named, keyboard-operable, labelled and pending-safe without native dialogs", () => {
  assert.ok(confirmation);
  const compiled = ts.transpileModule(`const form = (${confirmation.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
  let cancelled = 0;
  const render = (busy) => new Function("React", "decision", "busy", "confirmDecision", "cancelDecision", "decisionPanel", "decisionReason", `const t = (source) => source;\n${compiled}\nreturn form;`)(React, { title: "Synthetic mapping decision" }, busy, () => {}, () => { cancelled++; }, { current: null }, { current: null });
  const element = render(false);
  const html = renderToStaticMarkup(element);
  assert.match(html, /aria-labelledby="fee-mapping-decision-title"/);
  assert.match(html, /aria-describedby="fee-mapping-decision-help"/);
  assert.match(html, /Reason for fee mapping action<textarea[^>]*name="reason"[^>]*minLength="3"[^>]*maxLength="500"/);
  assert.match(html, /type="submit"/); assert.match(html, /type="button"/);
  element.props.onKeyDown({ key: "Escape", preventDefault() {} }); assert.equal(cancelled, 1);
  const pending = renderToStaticMarkup(render(true));
  assert.match(pending, /aria-busy="true"/); assert.match(pending, /disabled=""/);
  assert.doesNotMatch(text, /window\.(prompt|confirm)\(/);
});

test("confirmation tokens override fee-form cream controls without losing dark input contrast", () => {
  const css = readFileSync(new URL("../app/workspace/accounting/accounting.css", import.meta.url), "utf8");
  assert.match(css, /\.accounting-form\.accounting-confirmation textarea[^}]*background: var\(--theme-input\)/);
  assert.match(css, /\.accounting-panel\.accounting-confirmation button[^}]*color: var\(--theme-action-text\)/);
  const existingCreamRule = css.indexOf(".fee-accounting .accounting-form textarea");
  const semanticRule = css.indexOf(".accounting-form.accounting-confirmation textarea");
  assert.ok(existingCreamRule >= 0 && semanticRule > existingCreamRule);
});

for (const accountingBasis of ["ACCRUAL", "CASH"]) {
  test(`${accountingBasis} independent reviewers can inspect the actual full mapping without configuration permission`, () => {
    const node = functions.get("FeeMappingEvidence");
    assert.ok(node);
    const compiled = ts.transpileModule(node.getText(source), { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
    const Component = new Function("React", "label", "accountingText", "accountingCode", `${compiled}\nreturn FeeMappingEvidence;`)(React, label, accountingText, accountingCode);
    const config = { status: "SUBMITTED", revisionNumber: 2, effectiveFrom: "2026-04-01", accountingBasis, currencyCode: "INR", receivableAccountId: "receivable", concessionAccountId: "expense", headMappings: [{ feeHeadId: "head", feeHeadCode: "SYN_FEE", feeHeadName: "Synthetic fee", incomeAccountId: "income" }], tenderMappings: [{ paymentMethod: "BANK_TRANSFER", depositAccountId: "bank" }, { paymentMethod: "CASH", depositAccountId: "cash" }] };
    const accounts = ["receivable", "expense", "income", "bank", "cash"].map((id) => ({ id, code: `SYN_${id.toUpperCase()}`, name: `Synthetic ${id}` }));
    const html = renderToStaticMarkup(React.createElement(Component, { configuration: config, accounts }));
    assert.match(html, /<details[^>]*open=""/);
    assert.match(html, /<summary>Review mapping revision 2/);
    assert.match(html, /2026-04-01/); assert.match(html, /INR/);
    assert.match(html, /SYN_FEE.*Synthetic fee/);
    for (const id of ["income", "bank", "cash"]) assert.ok(html.includes(`SYN_${id.toUpperCase()} · Synthetic ${id}`));
    assert.equal(html.includes("Student fee receivable"), accountingBasis === "ACCRUAL");
    assert.doesNotMatch(html, /<button|<input|<select|<textarea/);
    const missing = renderToStaticMarkup(React.createElement(Component, { configuration: config, accounts: [] }));
    assert.match(missing, /Account unavailable.*verify before approval/);
  });
}

function mutationBindings(overrides = {}) {
  const state = { busy: [], errors: [], notices: [], required: false, refreshes: 0, parentRefreshes: 0 };
  const bindings = {
    apiUrl: "http://localhost:8080", refreshRequired: false, csrfToken: async () => "synthetic-token",
    fetch: async () => ({ ok: true }),
    load: async () => { state.refreshes++; }, onAccountingChanged: async () => { state.parentRefreshes++; },
    setBusy: (value) => state.busy.push(value), setError: (value) => state.errors.push(value),
    setNotice: (value) => state.notices.push(value), setRefreshRequired: (value) => { state.required = value; },
    problem: async () => "Synthetic rejection", ...overrides,
  };
  return { state, handler: compile("mutate", bindings) };
}
test("acknowledged changes refresh both existing finance/accounting views", async () => {
  const { state, handler } = mutationBindings();
  assert.equal(await handler("/mapping", "POST", {}, "Synthetic saved"), true);
  assert.equal(state.refreshes, 1); assert.equal(state.parentRefreshes, 1);
  assert.equal(state.required, false);
  assert.deepEqual(state.busy, [true, false]);
});
for (const target of ["load", "onAccountingChanged"]) {
  test(`acknowledged write with failed ${target} is not reported as an unsaved change`, async () => {
    const { state, handler } = mutationBindings({ [target]: async () => { throw new Error("Synthetic refresh failure"); } });
    assert.equal(await handler("/mapping", "POST", {}, "Synthetic saved"), true);
    assert.equal(state.required, true);
    assert.equal(state.notices.at(-1), "Synthetic saved");
    assert.match(state.errors.at(-1), /change was saved.*Reload/);
  });
}
test("stale records block another mutation until verified reload", async () => {
  const { handler } = mutationBindings({ refreshRequired: true, fetch: () => { assert.fail("Must not write from stale records"); } });
  assert.equal(await handler("/mapping", "POST", {}, "Synthetic saved"), false);
});
test("server rejection is not acknowledged, does not refresh records and preserves retry state", async () => {
  const { state, handler } = mutationBindings({ fetch: async () => ({ ok: false }) });
  assert.equal(await handler("/mapping", "POST", {}, "Synthetic saved"), false);
  assert.equal(state.refreshes, 0); assert.equal(state.parentRefreshes, 0);
  assert.equal(state.notices.at(-1), ""); assert.equal(state.required, false);
  assert.equal(state.errors.at(-1), "Synthetic rejection");
});
