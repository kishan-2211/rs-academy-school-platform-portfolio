import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";
import ts from "typescript";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import * as collection from "./manual-fee-collection.ts";

const text = readFileSync(new URL("../components/workspace/manual-fee-collection-form.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("manual-fee-collection-form.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const handlers = new Map();
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) handlers.set(node.name.text, node);
  ts.forEachChild(node, visit);
}
visit(source);
let rejectionInitializer;
function findRejections(node) {
  if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === "controlledRejections") rejectionInitializer = node.initializer;
  ts.forEachChild(node, findRejections);
}
findRejections(source);
assert.ok(rejectionInitializer, "the real controlled server-error catalogue must exist");
const controlledRejectionMessages = new Function("return (" + rejectionInitializer.getText(source) + ");")();
function executable(name, bindings) {
  assert.ok(handlers.has(name), `The real ${name} form handler must exist`);
  const implementation = ts.transpileModule(`const handler = ${handlers.get(name).getText(source)};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  return new Function(...Object.keys(bindings), `${implementation}\nreturn handler;`)(...Object.values(bindings));
}
class SchoolNow extends Date { constructor(...args) { super(...(args.length ? args : ["2026-10-11T06:00:00Z"])); } }
function setup(overrides = {}) {
  const state = { draft: { paymentMethod: "CASH", amount: "100.00", externalReference: "", notes: "Synthetic collection only",
    allocations: { "synthetic-charge": "100.00" }, actualLocal: "2026-10-11T10:00", utcOffset: "",
    chequeReceivedOn: "2026-10-11", postingOn: "", reviewReason: "", cashCounted: true, bankCreditConfirmed: false, bankCreditSource: "BANK_APP_CREDIT" },
  busy: false, uncertain: false, error: "", receipt: null, refreshed: false, refreshing: false, reviewDate: false,
  requests: [], confirmations: [], guards: [], refreshes: 0, uuidCount: 0 };
  const refs = { frozen: { current: null }, uncertainEver: { current: false }, inFlight: { current: false },
    lifecycle: { current: 1 }, originalScope: { current: "synthetic-school:synthetic-account" } };
  const props = { schoolId: "synthetic-school", accountId: "synthetic-account", learnerName: "Synthetic learner",
    currencyCode: "INR", balance: 400, schoolTimeZone: "Asia/Kolkata", schoolToday: "2026-10-11",
    charges: [{ id: "synthetic-charge", label: "Synthetic October", feeHeadName: "Synthetic tuition", dueOn: "2026-10-01", outstandingAmount: 400, allowPartial: true }],
    onCollectionGuardChange: (blocked) => { state.guards.push(blocked); },
    onCollected: async () => { state.refreshes++; },
    ...overrides.props,
  };
  const options = { confirm: () => true, csrf: async () => "synthetic-form-token", fetch: async () => ({ ok: true, json: async () => ({ id: "synthetic-payment", receiptNumber: "SYN/000010" }) }), ...overrides };
  const setters = Object.fromEntries(["draft", "busy", "uncertain", "error", "receipt", "refreshed", "refreshing", "reviewDate"].map((key) => [
    `set${key[0].toUpperCase()}${key.slice(1)}`, (value) => { state[key] = typeof value === "function" ? value(state[key]) : value; },
  ]));
  const t = (copy, values = {}) => copy.replace(/\{(\w+)\}/g, (matched, key) => Object.hasOwn(values, key) ? String(values[key]) : matched);
  const event = { preventDefault() {}, currentTarget: {} };
  function invoke(name = "collect") {
    const bindings = { ...collection, ...state, ...refs, ...setters, props, context: props, Date: SchoolNow,
      reviewed: state.reviewDate, apiUrl: "https://api.example.invalid", csrfToken: () => options.csrf(),
      fetch: async (...args) => { state.requests.push(args); return options.fetch(...args); },
      window: { confirm: (copy) => { state.confirmations.push(copy); return options.confirm(copy); } },
      crypto: { randomUUID: () => `synthetic-request-${++state.uuidCount}` }, money: (value) => `₹${value.toFixed(2)}`,
      t, methodLabel: (method) => method === "CASH" ? "Cash" : method === "CHEQUE" ? "Cheque" : "Verified bank credit",
      sourceLabel: (source) => source === "BANK_APP_CREDIT" ? "School bank app" : "School bank statement",
      controlledRejections: controlledRejectionMessages,
      initialDraft: () => ({ ...state.draft, amount: "", allocations: {}, cashCounted: false, bankCreditConfirmed: false }),
    };
    return executable(name, bindings)(event);
  }
  return { state, refs, props, options, event, invoke };
}

test("real form handler confirms scoped amount, learner, original day, posting day and source before posting", async () => {
  const fixture = setup();
  const pending = fixture.invoke(); fixture.event.currentTarget = null; await pending;
  assert.equal(fixture.state.requests.length, 1);
  const [url, request] = fixture.state.requests[0];
  assert.equal(url, "https://api.example.invalid/api/v1/finance/payments");
  const body = JSON.parse(request.body);
  assert.equal(body.schoolId, "synthetic-school"); assert.equal(body.accountId, "synthetic-account");
  assert.equal(body.actualReceivedAt, "2026-10-11T04:30:00.000Z");
  assert.equal(body.receivedOn, "2026-10-11");
  assert.deepEqual(body.allocations, [{ chargeId: "synthetic-charge", amount: 100 }]);
  assert.match(fixture.state.confirmations[0], /₹100\.00.*Synthetic learner.*Cash.*2026-10-11.*2026-10-11.*Cash counted/);
  assert.equal(fixture.state.receipt.receiptNumber, "SYN/000010");
  assert.equal(fixture.state.refreshed, true);
  assert.deepEqual(fixture.state.guards, [true, false]);
});

test("cancelled confirmation, uncounted cash, wrong scope and external busy never POST or reserve a request key", async () => {
  const cancelled = setup({ confirm: () => false }); await cancelled.invoke();
  const cash = setup(); cash.state.draft.cashCounted = false; await cash.invoke();
  const scope = setup(); scope.props.accountId = "different-synthetic-account"; await scope.invoke();
  const busy = setup({ props: { externallyBusy: true } }); await busy.invoke();
  for (const fixture of [cancelled, cash, scope, busy]) { assert.equal(fixture.state.requests.length, 0); assert.equal(fixture.state.uuidCount, 0); }
});

test("rapid duplicate submit is blocked while the actual first request is in flight", async () => {
  let finish;
  const fixture = setup({ fetch: async () => new Promise((resolve) => { finish = resolve; }) });
  const pending = fixture.invoke();
  await new Promise((resolve) => setImmediate(resolve));
  await fixture.invoke();
  assert.equal(fixture.state.requests.length, 1);
  finish({ ok: true, json: async () => ({ id: "synthetic-payment", receiptNumber: "SYN/000011" }) });
  await pending;
});

test("network uncertainty locks the financial draft and retries only the exact saved body and key", async () => {
  let count = 0;
  const fixture = setup({ fetch: async () => {
    if (++count === 1) throw new TypeError("Synthetic response loss");
    return { ok: true, json: async () => ({ id: "synthetic-payment", receiptNumber: "SYN/000012" }) };
  } });
  await fixture.invoke();
  assert.equal(fixture.state.uncertain, true);
  assert.match(fixture.state.error, /response is uncertain/);
  assert.equal(fixture.state.receipt, null);
  assert.deepEqual(fixture.state.guards, [true]);
  // A stale editable value must never change the saved retry request.
  fixture.state.draft.amount = "200.00"; fixture.state.draft.allocations["synthetic-charge"] = "200.00";
  await fixture.invoke();
  assert.equal(fixture.state.uuidCount, 1);
  assert.deepEqual(fixture.state.requests[0], fixture.state.requests[1]);
  assert.match(fixture.state.confirmations[1], /same ₹100\.00 collection/);
  assert.equal(fixture.state.receipt.receiptNumber, "SYN/000012");
});

test("authorization rejection or token failure on retry cannot erase the earlier dispatch uncertainty", async () => {
  const fixture = setup({ fetch: async () => { throw new TypeError("Synthetic lost response"); } });
  await fixture.invoke();
  const originalAttempt = fixture.refs.frozen.current;
  fixture.options.fetch = async () => ({ ok: false, status: 403, json: async () => ({ code: "ACCESS_DENIED" }) });
  await fixture.invoke();
  assert.equal(fixture.state.uncertain, true); assert.equal(fixture.refs.frozen.current, originalAttempt);
  assert.match(fixture.state.error, /earlier response remains uncertain/);
  fixture.options.csrf = async () => { throw new Error("Synthetic unavailable token"); };
  await fixture.invoke();
  assert.equal(fixture.state.uncertain, true); assert.equal(fixture.refs.frozen.current, originalAttempt);
  assert.equal(fixture.state.guards.includes(false), false);
});

test("a known posted receipt with refresh failure is not labelled failed and navigation remains guarded", async () => {
  const fixture = setup({ props: { onCollected: async () => { throw new Error("Synthetic refresh failure"); } } });
  await fixture.invoke();
  assert.equal(fixture.state.receipt.receiptNumber, "SYN/000010");
  assert.equal(fixture.state.uncertain, false); assert.equal(fixture.state.refreshed, false);
  assert.equal(fixture.state.error, "The receipt was posted, but account details could not be refreshed.");
  assert.deepEqual(fixture.state.guards, [true]);
  await fixture.invoke("nextCollection");
  assert.notEqual(fixture.state.receipt, null);
  assert.equal(fixture.state.requests.length, 1);
  fixture.props.onCollected = async () => {};
  await fixture.invoke("refreshAccount");
  assert.equal(fixture.state.refreshed, true); assert.deepEqual(fixture.state.guards, [true, false]);
  await fixture.invoke("nextCollection");
  assert.equal(fixture.state.receipt, null); assert.equal(fixture.refs.frozen.current, null);
});

test("premature uncertain-account refresh cannot count as a receipt-specific refresh after delayed acknowledgement", async () => {
  const fixture = setup({ fetch: async () => { throw new TypeError("Synthetic uncertain response"); } });
  await fixture.invoke(); await fixture.invoke("refreshAccount");
  assert.equal(fixture.state.refreshed, false);
  fixture.options.fetch = async () => ({ ok: true, json: async () => ({ id: "synthetic-payment", receiptNumber: "SYN/000013" }) });
  fixture.props.onCollected = async () => { throw new Error("Synthetic post-ack refresh failure"); };
  await fixture.invoke();
  assert.equal(fixture.state.receipt.receiptNumber, "SYN/000013");
  assert.equal(fixture.state.refreshed, false); assert.equal(fixture.state.guards.includes(false), false);
  await fixture.invoke("nextCollection"); assert.notEqual(fixture.state.receipt, null);
});

test("unmount after dispatch never updates a different learner or performs a stale account refresh", async () => {
  let finish;
  const fixture = setup({ fetch: async () => new Promise((resolve) => { finish = resolve; }) });
  const pending = fixture.invoke();
  await new Promise((resolve) => setImmediate(resolve));
  fixture.refs.lifecycle.current++;
  finish({ ok: true, json: async () => ({ id: "synthetic-payment", receiptNumber: "SYN/000014" }) });
  await pending;
  assert.equal(fixture.state.receipt, null); assert.equal(fixture.state.refreshes, 0);
  assert.equal(fixture.state.guards.includes(false), false);
});

test("a definite rejection keeps editable inputs and allows a non-destructive account refresh", async () => {
  const fixture = setup({ fetch: async () => ({ ok: false, status: 409, json: async () => ({ code: "MANUAL_BANK_CREDIT_ALREADY_RECORDED" }) }) });
  const originalDraft = structuredClone(fixture.state.draft);
  await fixture.invoke();
  assert.deepEqual(fixture.state.draft, originalDraft);
  assert.equal(fixture.state.uncertain, false); assert.equal(fixture.refs.frozen.current, null);
  assert.match(fixture.state.error, /already has a school receipt/);
  await fixture.invoke("refreshAccount"); assert.deepEqual(fixture.state.draft, originalDraft);
  assert.equal(fixture.state.refreshed, false);
});

test("actual settlement-reserved-credit rejection preserves bank draft without a second receipt", async () => {
  for (const paymentMethod of ["BANK_TRANSFER", "SCHOOL_UPI"]) {
    const fixture = setup({ fetch: async () => ({ ok: false, status: 409,
      json: async () => ({ code: "MANUAL_BANK_CREDIT_RESERVED_FOR_SETTLEMENT", detail: "Synthetic private bank evidence" }) }) });
    Object.assign(fixture.state.draft, { paymentMethod, bankCreditConfirmed: true, externalReference: "SYN_RESERVED_BANK_CREDIT" });
    const original = structuredClone(fixture.state.draft);
    await fixture.invoke();
    assert.equal(fixture.state.requests.length, 1);
    assert.equal(fixture.state.receipt, null);
    assert.equal(fixture.state.refreshes, 0);
    assert.deepEqual(fixture.state.draft, original);
    assert.equal(fixture.state.error, controlledRejectionMessages.MANUAL_BANK_CREDIT_RESERVED_FOR_SETTLEMENT);
    assert.match(fixture.state.error, /gateway settlement review/);
    assert.ok(!fixture.state.error.includes("Synthetic private bank evidence"));
    assert.equal(fixture.state.uncertain, false);
    assert.equal(fixture.refs.frozen.current, null);
  }
});

test("unexpected server codes cannot select inherited object values or leak diagnostic text", async () => {
  for (const code of ["constructor", "__proto__", "SYNTHETIC_UNKNOWN_CODE"]) {
    const fixture = setup({ fetch: async () => ({ ok: false, status: 400,
      json: async () => ({ code, detail: "Synthetic private server diagnostic" }) }) });
    await fixture.invoke();
    assert.equal(fixture.state.error, "The collection was not accepted. Check the fields and refresh the account.");
    assert.equal(fixture.state.receipt, null);
    assert.deepEqual(fixture.state.guards, [true, false]);
  }
});

test("shared form retains labelled native controls, live outcomes, keyboard focus and mobile sizing", () => {
  const css = readFileSync(new URL("../app/workspace/fees/manual-collection.css", import.meta.url), "utf8");
  assert.match(text, /aria-labelledby=\{`\$\{formId\}-title`\}/);
  assert.match(text, /role="alert"/); assert.match(text, /role="status"/);
  assert.match(text, /aria-busy=\{busy \|\| refreshing\}/);
  assert.match(text, /fieldset disabled=\{locked\}/);
  assert.match(text, /formNoValidate=\{uncertain\}/);
  assert.match(css, /outline: 3px solid var\(--theme-text\)/);
  assert.match(css, /min-height: 44px/); assert.match(css, /max-width: 700px/);
  assert.match(css, /prefers-reduced-motion: reduce/);
  assert.doesNotMatch(text, /(?:localStorage|sessionStorage)\./);
});

function serverForm() {
  const require = createRequire(import.meta.url);
  const exports = {};
  const implementation = ts.transpileModule(text, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const translation = (copy, values = {}) => copy.replace(/\{(\w+)\}/g, (match, key) => Object.hasOwn(values, key) ? String(values[key]) : match);
  const dependencies = (name) => name === "@/lib/manual-fee-collection" ? collection
    : name === "@/components/i18n/language-provider" ? { useT: () => translation, useLanguage: () => ({ locale: "en" }) } : require(name);
  new Function("require", "exports", implementation)(dependencies, exports);
  return exports.ManualFeeCollectionForm;
}
test("actual React server rendering emits accessible native controls without a clock-dependent received-time default", () => {
  const component = serverForm();
  const { props } = setup();
  const html = renderToStaticMarkup(React.createElement(component, props));
  assert.match(html, /<section class="manual-collection" aria-labelledby="[^"]+">/);
  assert.match(html, /<form aria-label="Manual collection for Synthetic learner" aria-busy="false">/);
  assert.match(html, /<fieldset><legend>Collection details<\/legend>/);
  assert.match(html, /<input type="datetime-local"[^>]+value=""/);
  assert.match(html, /<input type="checkbox" required=""/);
  assert.match(html, /<button class="manual-collection__submit" type="submit">Review and issue receipt<\/button>/);
  assert.doesNotMatch(html, /Synthetic private|Secure form token/);
});
test("actual rendered learner and charge text remains escaped and non-editable fields stay scoped to the account", () => {
  const component = serverForm();
  const { props } = setup();
  props.learnerName = "Synthetic <script>learner</script>";
  props.charges[0].label = "Synthetic <img>fee period";
  const html = renderToStaticMarkup(React.createElement(component, props));
  assert.match(html, /Synthetic &lt;script&gt;learner&lt;\/script&gt;/);
  assert.match(html, /Synthetic &lt;img&gt;fee period/);
  assert.doesNotMatch(html, /<script>|<img>/);
  assert.doesNotMatch(html, /name="(?:schoolId|accountId)"/);
});

const parentText = readFileSync(new URL("../components/workspace/finance-workspace.tsx", import.meta.url), "utf8");
const parentSource = ts.createSourceFile("finance-workspace.tsx", parentText, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const parentNodes = new Map();
let formNode;
function parentVisit(node) {
  if (ts.isFunctionDeclaration(node) && node.name?.text === "mutate") parentNodes.set("mutate", node);
  if (ts.isVariableDeclaration(node) && node.name.getText(parentSource) === "load") parentNodes.set("load", node.initializer.arguments[0]);
  if (ts.isJsxSelfClosingElement(node) && node.tagName.getText(parentSource) === "ManualFeeCollectionForm") formNode = node;
  ts.forEachChild(node, parentVisit);
}
parentVisit(parentSource);
function parentExecutable(node, bindings) {
  assert.ok(node, "The actual finance integration handler must exist");
  const implementation = ts.transpileModule(`const handler = ${node.getText(parentSource)};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  return new Function(...Object.keys(bindings), `${implementation}\nreturn handler;`)(...Object.values(bindings));
}
test("the parent renders the real collector only with collection capability and keys it by school and account", () => {
  assert.ok(formNode);
  assert.match(formNode.parent.getText(parentSource), /^data\.canCollect &&/);
  const attributes = new Map(formNode.attributes.properties.filter(ts.isJsxAttribute).map((attribute) => [attribute.name.text, attribute]));
  assert.equal(attributes.get("key").initializer.expression.getText(parentSource), "`${data.schoolId}:${selectedAccount.id}`");
  for (const [name, expression] of [["schoolId", "data.schoolId"], ["accountId", "selectedAccount.id"],
    ["schoolTimeZone", "data.schoolTimeZone"], ["schoolToday", "data.schoolToday"], ["charges", "selectedAccount.charges"]]) {
    assert.equal(attributes.get(name).initializer.expression.getText(parentSource), expression);
  }
  const busyExpression = attributes.get("externallyBusy").initializer.expression.getText(parentSource);
  assert.equal(busyExpression, "busy || !selectedAccountMatchesSearch");
  const collectionDisabled = new Function("busy", "selectedAccountMatchesSearch", `return (${busyExpression});`);
  assert.equal(collectionDisabled(false, true), false);
  assert.equal(collectionDisabled(false, false), true);
  assert.equal(collectionDisabled(true, true), true);
});
test("parent collection guard binds the actual school and account and locks every finance tab and account picker", () => {
  const callback = formNode.attributes.properties.find((attribute) => ts.isJsxAttribute(attribute) && attribute.name.text === "onCollectionGuardChange").initializer.expression;
  const guardedCollection = { current: null };
  const states = [];
  const handler = parentExecutable(callback, { guardedCollection, setCollectionBlocked: (value) => states.push(value),
    data: { schoolId: "synthetic-school" }, selectedAccount: { id: "synthetic-account" } });
  handler(true);
  assert.deepEqual(guardedCollection.current, { schoolId: "synthetic-school", accountId: "synthetic-account" });
  handler(false); assert.equal(guardedCollection.current, null); assert.deepEqual(states, [true, false]);
  const nav = [];
  let picker = 0;
  function assertGuard(openingNode, requireRefundGuard = false) {
    const disabled = openingNode.attributes.properties.find(attribute => ts.isJsxAttribute(attribute) && attribute.name.text === "disabled");
    assert.ok(disabled?.initializer && ts.isJsxExpression(disabled.initializer) && disabled.initializer.expression);
    const expression = disabled.initializer.expression.getText(parentSource);
    const evaluate = new Function("collectionBlocked", "refundBlocked", "return (" + expression + ");");
    assert.equal(evaluate(true, false), true, "manual collection must lock this control");
    assert.equal(evaluate(true, true), true);
    if (requireRefundGuard) assert.equal(evaluate(false, true), true, "preserve the stronger existing refund navigation guard");
  }
  function inspect(node, insideTabs = false) {
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText(parentSource) === "nav"
        && /className="finance-tabs"/.test(node.openingElement.getText(parentSource))) insideTabs = true;
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText(parentSource) === "button") {
      const opening = node.openingElement.getText(parentSource);
      if (insideTabs && /setTab\(/.test(opening)) {
        assertGuard(node.openingElement, true); nav.push(opening.match(/setTab\("(\w+)"/)[1]);
      }
      if (/setSelectedAccountId\(/.test(opening)) { assertGuard(node.openingElement); picker++; }
    }
    ts.forEachChild(node, child => inspect(child, insideTabs));
  }
  inspect(parentSource);
  assert.deepEqual(nav.sort(), ["accounts","plans","operations","approvals","gateway","register","refunds","settlements"].sort());
  assert.equal(picker, 1);
});
test("other parent mutations refuse dispatch while a manual collection is unresolved", async () => {
  let calls = 0;
  const unexpected = () => { calls++; throw new Error("No unrelated mutation should run"); };
  const handler = parentExecutable(parentNodes.get("mutate"), {
    guardedCollection: { current: { schoolId: "synthetic-school", accountId: "synthetic-account" } },
    setBusy: unexpected, setError: unexpected, setNotice: unexpected, csrfToken: unexpected,
    fetch: unexpected, load: unexpected, problem: unexpected, apiUrl: "https://api.example.invalid",
  });
  assert.equal(await handler("/api/v1/finance/concessions", { method: "POST" }, "Synthetic mutation"), false);
  assert.equal(calls, 0);
});
test("a missing or cross-school post-ack refreshed account fails without replacing the original guarded learner", async () => {
  for (const snapshot of [
    { schoolId: "other-synthetic-school", feePlans: [], accounts: [{ id: "synthetic-account" }] },
    { schoolId: "synthetic-school", feePlans: [], accounts: [{ id: "other-synthetic-account" }] },
  ]) {
    let replaced = false;
    const guardedCollection = { current: { schoolId: "synthetic-school", accountId: "synthetic-account" } };
    const handler = parentExecutable(parentNodes.get("load"), {
      guardedCollection, guardedRefund: { current: null }, guardedSettlement: { current: null },
      apiUrl: "https://api.example.invalid", fetch: async () => ({ ok: true, json: async () => snapshot }),
      setData: () => { replaced = true; }, setSelectedPlanId: () => { replaced = true; }, setSelectedAccountId: () => { replaced = true; },
      problem: async () => "Synthetic controlled error",
    });
    await assert.rejects(handler(), /collected account could not be refreshed/);
    assert.equal(replaced, false);
    assert.deepEqual(guardedCollection.current, { schoolId: "synthetic-school", accountId: "synthetic-account" });
  }
});
test("a successful authoritative refresh preserves the guarded account rather than auto-switching to another learner", async () => {
  const snapshot = { schoolId: "synthetic-school", feePlans: [], accounts: [{ id: "other-synthetic-account" }, { id: "synthetic-account", balance: 300 }] };
  let data;
  let selected = "synthetic-account";
  const guardedCollection = { current: { schoolId: "synthetic-school", accountId: selected } };
  const handler = parentExecutable(parentNodes.get("load"), {
    guardedCollection, guardedRefund: { current: null }, guardedSettlement: { current: null },
    apiUrl: "https://api.example.invalid", fetch: async () => ({ ok: true, json: async () => snapshot }),
    setData: (value) => { data = value; }, setSelectedPlanId: () => {}, setSelectedAccountId: (value) => { selected = value(selected); },
    problem: async () => "Synthetic controlled error",
  });
  await handler(); assert.equal(data, snapshot); assert.equal(selected, "synthetic-account");
  // Child releases this guard only after it acknowledges the successful receipt-specific read.
  assert.notEqual(guardedCollection.current, null);
});
