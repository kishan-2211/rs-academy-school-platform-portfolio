import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as gateway from "./family-gateway-payments.ts";
import { hindi } from "./i18n/messages.ts";
import { familyFeeAccountMessages } from "./i18n/family-fee-account-messages.ts";

const read = path => readFileSync(new URL(path, import.meta.url), "utf8");
const panelText = read("../components/family/gateway-payment-panel.tsx"), statusText = read("../components/family/payment-status-view.tsx");
const learner = "72000000-0000-4000-8000-000000000001", account = "72000000-0000-4000-8000-000000000002", charge = "72000000-0000-4000-8000-000000000003", requestId = "72000000-0000-4000-8000-000000000004", attempt = "72000000-0000-4000-8000-000000000005", receipt = "72000000-0000-4000-8000-000000000006";
const fees = { learnerId: learner, accountId: account, currencyCode: "INR", charges: [{ id: charge, label: "Synthetic tuition", dueOn: "2026-10-01", outstandingAmount: 20, reservedAmount: 0, allowPartial: true }] };
const payment = changes => ({ id: attempt, learnerId: learner, accountId: account, sessionName: "Synthetic session", amount: 20, currencyCode: "INR", status: "PENDING", createdAt: "2026-10-01T01:00:00Z", expiresAt: "2030-10-01T01:15:00Z", originalBusinessDate: "2026-10-01", reservationActive: true, initiatedByYou: true, canContinue: false, selectedCharges: [{ chargeId: charge, label: "Synthetic tuition", dueOn: "2026-10-01", amount: 20 }], receipt: null, ...changes });
const page = () => ({ learnerId: learner, availability: "AVAILABLE", page: 0, pageSize: 20, hasMore: false, payments: [payment()] });
const translate = (copy, values = {}) => copy.replace(/\{(\w+)\}/g, (_, key) => String(values[key] ?? `{${key}}`));

function buttonAttributes(html, caption) {
  const patterns = {
    "Refresh payment history": /<button\b([^>]*)>Refresh payment history<\/button>/,
    "Refresh verified status": /<button\b([^>]*)>Refresh verified status<\/button>/,
    "Load older payments": /<button\b([^>]*)>Load older payments<\/button>/,
    "All online payments loaded": /<button\b([^>]*)>All online payments loaded<\/button>/,
    "Payment history page limit reached": /<button\b([^>]*)>Payment history page limit reached<\/button>/,
  };
  assert.ok(Object.hasOwn(patterns, caption), `Unreviewed button caption: ${caption}`);
  const button = html.match(patterns[caption]);
  assert.ok(button, `Missing actual ${caption} control`);
  return button[1];
}

function parsed(text) { return ts.createSourceFile("actual.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX); }
function executable(text, name, bindings) {
  const ast = parsed(text); let found;
  function visit(node) {
    if (ts.isFunctionDeclaration(node) && node.name?.text === name) found = node;
    if (ts.isVariableDeclaration(node) && node.name.getText(ast) === name && node.initializer && ts.isCallExpression(node.initializer)) found = node.initializer.arguments[0];
    ts.forEachChild(node, visit);
  }
  visit(ast); assert.ok(found, `Missing actual ${name} implementation`);
  const output = ts.transpileModule(`const actual = ${found.getText(ast).replace(/^export\s+/, "")};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(bindings), `${output}\nreturn actual;`)(...Object.values(bindings));
}
function componentModule(text, extra = {}) {
  const require = createRequire(import.meta.url), exports = {};
  const output = ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } }).outputText;
  const dependency = name => name in extra ? extra[name]
    : name === "next/link" ? { __esModule: true, default: props => { const attributes = { ...props }; delete attributes.prefetch; return React.createElement("a", attributes); } }
    : name.endsWith(".module.css") ? { __esModule: true, default: new Proxy({}, { get: (_, key) => `payment-${key}` }) }
    : name === "@/components/i18n/language-provider" ? { useT: () => translate, useLanguage: () => ({ locale: "en" }) }
    : name === "@/lib/family-gateway-payments" ? gateway
    : name === "@/components/receipt-pdf-actions" ? { ReceiptPdfActions: props => React.createElement("span", { "data-exact-receipt-payment": props.paymentId }, "Receipt PDFs") }
    : require(name);
  new Function("require", "exports", output)(dependency, exports); return exports;
}
const statusModule = componentModule(statusText), panelModule = componentModule(panelText, { "./payment-status-view": statusModule });

function fixture(overrides = {}) {
  const state = { history: page(), selection: { [charge]: "20.00" }, payerPhone: "0000000000", uncertain: false, busy: false, dispatched: false, error: "", message: "", enabled: true, recorded: 0, keyCalls: 0, sent: [], failSend: true, failRead: false, failStatement: false, guards: [], accessLost: 0, redirects: [], ...overrides };
  const refs = { request: { current: null }, prepared: { current: null }, acknowledged: { current: false }, callbacks: { current: { onGuardChange: value => state.guards.push(value), onAccessLost: () => state.accessLost++ } } };
  const bindings = { ...gateway, ...refs, fees, crypto: { randomUUID: () => { state.keyCalls++; return requestId; } },
    familyPaymentCsrf: async () => "synthetic-csrf",
    sendFamilyPayment: async body => { state.sent.push(body); if (state.failSend) throw new Error("synthetic-private-connection-failure"); return payment(); },
    loadFamilyPaymentHistory: async () => { if (state.failRead) throw new Error("synthetic-private-refresh-failure"); return page(); },
    continueFamilyPayment: async () => "https://payments-test.cashfree.com/links/synthetic",
    onRecorded: async () => { if (state.failStatement) throw new Error("synthetic-private-statement-failure"); state.recorded++; }, window: { location: { search: "?success=true&paid=true", assign: value => state.redirects.push(value) } },
  };
  for (const key of ["history", "selection", "payerPhone", "uncertain", "busy", "dispatched", "error", "message"]) bindings[`set${key[0].toUpperCase()}${key.slice(1)}`] = value => { state[key] = typeof value === "function" ? value(state[key]) : value; };
  bindings.fail = executable(panelText, "fail", bindings);
  return { state, refs, bindings, run: (name, ...args) => executable(panelText, name, { ...bindings, ...state })(...args) };
}

test("actual React panel renders a labelled native disabled-by-default form without initiating private network requests", () => {
  const html = renderToStaticMarkup(React.createElement(panelModule.FamilyGatewayPaymentPanel, { fees, onRecorded() {} }));
  assert.match(html, /aria-busy="true"/); assert.match(html, /aria-label="Select school fees for online payment"/);
  assert.match(html, /<fieldset disabled=""/); assert.match(html, /type="checkbox"/); assert.match(html, /inputMode="decimal"/i);
  assert.match(html, /type="tel"[^>]*inputMode="numeric"/i); assert.match(html, /Checking online payment availability/);
  assert.doesNotMatch(html, /checkoutUrl|<iframe|name="(?:credit.card|card.number|cvv|upi.pin)"/i);
  assert.match(html, /type="tel"[^>]*value=""/i, "The payer contact starts empty rather than exposing fixture or stored contact data");
  const first = panelModule.FamilyGatewayPaymentPanel({ fees, onRecorded() {} });
  const changed = panelModule.FamilyGatewayPaymentPanel({ fees: { ...fees, learnerId: receipt }, onRecorded() {} });
  assert.notEqual(first.key, changed.key, "All request/contact refs reset at the verified child boundary");
});
test("actual React return page initially reads verified status and never trusts provider success query claims", () => {
  const html = renderToStaticMarkup(React.createElement(statusModule.FamilyPaymentStatusView, { attemptId: attempt }));
  assert.match(html, /Checking verified payment status/); assert.match(html, /does not mark fees paid/);
  assert.doesNotMatch(html, /Paid —|numbered receipt|checkoutUrl/);
  const route = read("../app/family/payments/[attemptId]/page.tsx");
  assert.match(route, /params: Promise/); assert.match(route, /await params/); assert.match(route, /notFound\(\)/);
  assert.doesNotMatch(route, /searchParams|fetch\(|generateStaticParams|use cache/);
});
test("actual React refresh controls stay focusable with aria-disabled while busy, including no-data error retries, but begin natively disabled", () => {
  for (const [text, component, props, caption, values] of [
    [panelText, "FamilyGatewayPaymentPanel", { fees, onRecorded() {} }, "Refresh payment history", [page(), {}, "", true, false, "", "", false]],
    [panelText, "FamilyGatewayPaymentPanel", { fees, onRecorded() {} }, "Refresh payment history", [null, {}, "", true, false, "", "", false]],
    [statusText, "FamilyPaymentStatusView", { attemptId: attempt }, "Refresh verified status", [payment(), true, ""]],
    [statusText, "FamilyPaymentStatusView", { attemptId: attempt }, "Refresh verified status", [null, true, ""]],
  ]) {
    let state = 0;
    const controlledReact = { ...React, useState: initial => [state < values.length ? values[state++] : initial, () => {}] };
    const rendered = componentModule(text, { react: controlledReact, "./payment-status-view": statusModule });
    const html = renderToStaticMarkup(React.createElement(rendered[component], props));
    const attributes = buttonAttributes(html, caption);
    assert.match(attributes, /aria-disabled="true"/);
    assert.doesNotMatch(attributes, /\sdisabled(?:\s|=|$)/, "Busy must not natively disable and blur the activated control");
    assert.doesNotMatch(attributes, /tabindex="-1"/i);
  }
  for (const [component, props, caption] of [
    [panelModule.FamilyGatewayPaymentPanel, { fees, onRecorded() {} }, "Refresh payment history"],
    [statusModule.FamilyPaymentStatusView, { attemptId: attempt }, "Refresh verified status"],
  ]) assert.match(buttonAttributes(renderToStaticMarkup(React.createElement(component, props)), caption), /\sdisabled=""/);
  assert.match(read("../components/family/gateway-payment-panel.module.css"), /button\[aria-disabled="true"\]/);
});
test("actual history and status refresh handlers fence repeat keyboard activation with the existing in-flight request rather than a disabled DOM control", async () => {
  const family = fixture(); let familyReads = 0, settleFamily;
  family.bindings.loadFamilyPaymentHistory = async () => { familyReads++; return new Promise(resolve => { settleFamily = resolve; }); };
  const firstFamilyRead = family.run("refresh");
  assert.equal(family.state.busy, true); const familyController = family.refs.request.current;
  await family.run("refresh");
  assert.equal(familyReads, 1); assert.equal(family.refs.request.current, familyController);
  settleFamily(page()); await firstFamilyRead;
  assert.equal(family.state.busy, false); assert.equal(family.refs.request.current, null);

  const request = { current: null }, state = { busy: false, error: "", payment: null }; let statusReads = 0, settleStatus;
  const bindings = { request, attemptId: attempt, setBusy: value => { state.busy = value; }, setError: value => { state.error = value; }, setPayment: value => { state.payment = value; }, fail: error => { throw error; },
    loadFamilyPayment: async () => { statusReads++; return new Promise(resolve => { settleStatus = resolve; }); } };
  const refreshStatus = executable(statusText, "refresh", bindings), firstStatusRead = refreshStatus();
  assert.equal(state.busy, true); const statusController = request.current;
  await refreshStatus(); assert.equal(statusReads, 1); assert.equal(request.current, statusController);
  settleStatus(payment()); await firstStatusRead;
  assert.equal(state.busy, false); assert.equal(request.current, null); assert.equal(state.payment.id, attempt);
});
test("actual React pagination remains native-enabled while busy and retained after the last append or page cap", () => {
  for (const [history, busy, caption, ariaDisabled] of [
    [{ ...page(), hasMore: true }, false, "Load older payments", false],
    [{ ...page(), hasMore: true }, true, "Load older payments", true],
    [{ ...page(), page: 1, hasMore: false }, false, "All online payments loaded", true],
    [{ ...page(), page: 10000, hasMore: true }, false, "Payment history page limit reached", true],
  ]) {
    const values = [history, {}, "", busy, false, "", "", false]; let state = 0;
    const controlledReact = { ...React, useState: initial => [state < values.length ? values[state++] : initial, () => {}] };
    const rendered = componentModule(panelText, { react: controlledReact, "./payment-status-view": statusModule });
    const html = renderToStaticMarkup(React.createElement(rendered.FamilyGatewayPaymentPanel, { fees, onRecorded() {} }));
    const attributes = buttonAttributes(html, caption);
    assert.match(attributes, ariaDisabled ? /aria-disabled="true"/ : /aria-disabled="false"/);
    assert.doesNotMatch(attributes, /\sdisabled(?:\s|=|$)|tabindex="-1"/i);
  }
});
test("actual append handler synchronously fences duplicate, exhausted, absent-history and page-cap requests", async () => {
  const f = fixture({ history: { ...page(), hasMore: true } }); let calls = 0, settle;
  f.bindings.loadFamilyPaymentHistory = async (child, nextPage) => {
    calls++; assert.equal(child, learner); assert.equal(nextPage, 1);
    return new Promise(resolve => { settle = resolve; });
  };
  const first = f.run("refresh", true), controller = f.refs.request.current;
  await f.run("refresh", true); assert.equal(calls, 1); assert.equal(f.refs.request.current, controller);
  settle({ ...page(), page: 1, hasMore: false }); await first;
  assert.equal(f.state.history.page, 1); assert.equal(f.state.history.hasMore, false);
  await f.run("refresh", true); assert.equal(calls, 1);
  for (const history of [null, { ...page(), page: 0, hasMore: false }, { ...page(), page: 10000, hasMore: true }]) {
    f.state.history = history; await f.run("refresh", true);
    assert.equal(calls, 1); assert.equal(f.refs.request.current, null); assert.equal(f.state.busy, false);
  }
});
test("the actual family fee-count expression translates the entire count without appending an English plural suffix in Hindi", () => {
  const text = read("../components/family/family-portal.tsx"), ast = parsed(text); let expression;
  function inspect(node) {
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText(ast) === "span" && node.children.length === 1) {
      const child = node.children[0];
      if (ts.isJsxExpression(child) && child.expression && ts.isCallExpression(child.expression)
        && child.expression.expression.getText(ast) === "t" && child.expression.arguments[0]?.text === "Scheduled fee lines: {count}") expression = child.expression;
    }
    ts.forEachChild(node, inspect);
  }
  inspect(ast); assert.ok(expression, "The fee count must be one whole translated message, not a translated noun plus an English suffix");
  const renderCount = new Function("t", "fees", `return ${expression.getText(ast)};`);
  for (const count of [0, 1, 3]) {
    const context = { charges: Array(count).fill({}) };
    assert.equal(renderCount(translate, context), `Scheduled fee lines: ${count}`);
    const hindiText = renderCount((copy, values) => translate(hindi.parent[copy], values), context);
    assert.equal(hindiText, `तय फीस मदों की संख्या: ${count}`); assert.doesNotMatch(hindiText, /[A-Za-z]/);
  }
});
test("review and pending cards do not offer re-payment or browser-created receipts; exact posted receipts retain their existing actions", () => {
  for (const status of ["PENDING", "REVIEW_REQUIRED", "EXPIRED"]) {
    const html = renderToStaticMarkup(React.createElement(statusModule.FamilyPaymentCard, { payment: payment({ status }), onContinue: async () => {} }));
    assert.match(html, /do not pay again|not proof that no money/i); assert.doesNotMatch(html, /Open secure checkout|data-exact-receipt-payment/);
    assert.ok(html.includes(`/family/payments/${attempt}`));
  }
  const html = renderToStaticMarkup(React.createElement(statusModule.FamilyPaymentCard, { payment: payment({ status: "REVIEW_REQUIRED", receipt: { paymentId: receipt, receiptNumber: "SYN-RECEIPT-1", postingOn: "2026-10-02" } }), onContinue: async () => {} }));
  assert.ok(html.includes(`data-exact-receipt-payment="${receipt}"`)); assert.match(html, /SYN-RECEIPT-1/);
});
test("actual submit handler preserves uncertain identity/body through refresh failure and edited inputs, then acknowledges only the matching server result", async () => {
  const f = fixture(); const event = { preventDefault() {} };
  await f.run("submit", event);
  assert.equal(f.state.uncertain, true); assert.equal(f.state.error, gateway.familyPaymentErrors.uncertain);
  const original = f.refs.prepared.current; assert.equal(f.state.recorded, 0); assert.equal(f.state.keyCalls, 1);
  f.state.failRead = true; await f.run("refresh");
  assert.equal(f.refs.prepared.current, original, "A failed history read cannot erase the original mutation");
  f.state.selection = {}; f.state.payerPhone = "edited-invalid"; f.state.enabled = false; f.state.failSend = false;
  await f.run("submit", event);
  assert.equal(f.state.sent.length, 2); assert.equal(f.state.sent[0], original); assert.equal(f.state.sent[1], original);
  assert.equal(f.state.keyCalls, 1); assert.equal(f.state.recorded, 0); assert.equal(f.state.uncertain, true); assert.equal(f.refs.prepared.current, original);
  assert.equal(f.refs.acknowledged.current, true); assert.ok(!f.state.guards.includes(false), "Failed post-ack reads must not release the parent guard");
  f.state.failRead = false; await f.run("submit", event);
  assert.equal(f.state.sent.length, 2, "Acknowledged recovery refreshes records without redispatching the payment");
  assert.equal(f.state.recorded, 1); assert.equal(f.state.uncertain, false); assert.equal(f.refs.prepared.current, null); assert.equal(f.state.guards.at(-1), false);
  assert.equal(f.state.payerPhone, ""); assert.equal(f.state.busy, false); assert.equal(f.refs.request.current, null);
});
test("actual submission cannot create while unavailable or already busy and cannot dispatch invalid phone/amount fields", async () => {
  for (const overrides of [{ enabled: false }, { payerPhone: "123" }, { selection: { [charge]: "20.001" } }]) {
    const f = fixture(overrides); await f.run("submit", { preventDefault() {} });
    assert.equal(f.state.sent.length, 0); assert.equal(f.refs.prepared.current, null);
  }
  const f = fixture(); f.refs.request.current = new AbortController(); await f.run("submit", { preventDefault() {} }); assert.equal(f.state.sent.length, 0);
});
test("acknowledged initiation keeps parent locked until history and selected statement both refresh without dispatching twice", async () => {
  const f = fixture({ failSend: false, failStatement: true });
  await f.run("submit", { preventDefault() {} });
  assert.equal(f.state.sent.length, 1); assert.equal(f.refs.acknowledged.current, true); assert.equal(f.state.uncertain, true); assert.deepEqual(f.state.guards, [true]);
  f.state.failStatement = false; await f.run("submit", { preventDefault() {} });
  assert.equal(f.state.sent.length, 1); assert.equal(f.state.guards.at(-1), false); assert.equal(f.refs.prepared.current, null);
});
test("closed historical account retains whole-child payment history but disables fresh collection in actual native form", () => {
  const values = [page(), {}, "", false, false, "", "", false]; let state = 0;
  const controlledReact = { ...React, useState: initial => [state < values.length ? values[state++] : initial, () => {}] };
  const rendered = componentModule(panelText, { react: controlledReact, "./payment-status-view": statusModule });
  const html = renderToStaticMarkup(React.createElement(rendered.FamilyGatewayPaymentPanel, { fees: { ...fees, accountStatus: "CLOSED", explicitAccountId: account }, onRecorded() {} }));
  assert.match(html, /closed and view-only/); assert.match(html, /<fieldset disabled/); assert.match(html, /Online payment history/); assert.match(html, /Synthetic session/);
});
test("actual family authorization failure clears contact, private history, selections and the in-memory retry body", () => {
  const f = fixture({ uncertain: true, dispatched: true, message: "Synthetic old private status" });
  f.refs.prepared.current = gateway.prepareFamilyPayment(null, fees, f.state.selection, f.state.payerPhone, () => requestId);
  f.bindings.fail(new gateway.FamilyPaymentAccessError(403));
  assert.equal(f.state.history, null); assert.equal(f.state.payerPhone, ""); assert.deepEqual(f.state.selection, {});
  assert.equal(f.refs.prepared.current, null); assert.equal(f.state.uncertain, false); assert.equal(f.state.message, "");
  assert.equal(f.state.error, gateway.familyPaymentErrors.scope);
  assert.equal(f.state.guards.at(-1), false); assert.equal(f.state.accessLost, 1);
});
test("actual explicit continuation consumes only a fresh authenticated helper result and does not act on return-page query claims", async () => {
  const f = fixture(); await f.run("continueCheckout", payment({ status: "READY", canContinue: true }));
  assert.deepEqual(f.state.redirects, ["https://payments-test.cashfree.com/links/synthetic"]);
  const denied = fixture({ error: gateway.familyPaymentErrors.scope }); await denied.run("continueCheckout", payment()); assert.deepEqual(denied.state.redirects, []);
  const cancelled = fixture(); cancelled.bindings.familyPaymentCsrf = async () => { cancelled.refs.request.current.abort(); return "synthetic-csrf"; };
  await cancelled.run("continueCheckout", payment({ status: "READY", canContinue: true })); assert.deepEqual(cancelled.state.redirects, []);
});
test("actual pending refresh hook limits checks to five, pauses hidden tabs and removes timers/listeners on unmount", async () => {
  const timers = new Map(), listeners = new Map(), cleanups = []; let next = 0, checks = 0;
  const doc = { visibilityState: "hidden", addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: (name, fn) => { assert.equal(listeners.get(name), fn); listeners.delete(name); } };
  const hook = executable(statusText, "useBoundedPendingPaymentRefresh", {
    useRef: current => ({ current }), useEffect: fn => { const cleanup = fn(); if (cleanup) cleanups.push(cleanup); }, document: doc,
    setTimeout: (fn, delay) => { assert.equal(delay, 25_000); const key = ++next; timers.set(key, fn); return key; }, clearTimeout: key => timers.delete(key),
  });
  hook(true, async () => { checks++; }); assert.equal(timers.size, 0);
  doc.visibilityState = "visible"; listeners.get("visibilitychange")(); assert.equal(timers.size, 1);
  for (let index = 0; index < 5; index++) { const [key, fn] = timers.entries().next().value; timers.delete(key); fn(); await Promise.resolve(); await Promise.resolve(); }
  assert.equal(checks, 5); assert.equal(timers.size, 0);
  cleanups.forEach(fn => fn()); assert.equal(listeners.size, 0);
});
test("payment controls retain semantic theme/status tokens, mobile layout, native focus targets and reduced motion without storage or background redirects", () => {
  const css = read("../components/family/gateway-payment-panel.module.css");
  assert.match(css, /min-height: 44px/); assert.match(css, /outline: 3px solid var\(--theme-text\)/);
  assert.match(css, /max-width: 640px/); assert.match(css, /grid-template-columns: minmax\(0, 1fr\)/);
  assert.match(css, /prefers-reduced-motion: reduce/); assert.match(css, /var\(--theme-action-text\)/);
  assert.doesNotMatch(`${panelText}\n${statusText}\n${read("./family-gateway-payments.ts")}`, /localStorage|sessionStorage|window\.open|dangerouslySetInnerHTML|useSearchParams/);
  assert.match(panelText, /controller\.abort\(\); request\.current\?\.abort\(\)/);
  assert.match(panelText, /beforeunload/); assert.match(panelText, /role="alert"/);
});
test("every controlled payment label, status and error has Hindi translation with the same placeholders", () => {
  const keys = new Set([...Object.values(gateway.familyPaymentErrors), ...Object.values(gateway.familyPaymentStatusCopy)]);
  for (const text of [panelText, statusText]) {
    const ast = parsed(text);
    const literals = node => { if (ts.isStringLiteral(node)) keys.add(node.text); else if (ts.isConditionalExpression(node)) { literals(node.whenTrue); literals(node.whenFalse); } };
    function inspect(node) {
      if (ts.isCallExpression(node) && node.expression.getText(ast) === "t") literals(node.arguments[0]);
      if (ts.isCallExpression(node) && ["setError", "setMessage"].includes(node.expression.getText(ast)) && ts.isStringLiteral(node.arguments[0]) && node.arguments[0].text) keys.add(node.arguments[0].text);
      ts.forEachChild(node, inspect);
    }
    inspect(ast);
  }
  assert.ok(keys.size >= 50);
  for (const copy of keys) {
    const translated = familyFeeAccountMessages[copy] ?? hindi.parent[copy] ?? hindi.common[copy];
    assert.ok(translated, `Missing parent Hindi copy: ${copy}`); assert.match(translated, /\p{Script=Devanagari}/u);
    assert.deepEqual([...copy.matchAll(/\{\w+\}/g)].map(item => item[0]).sort(), [...translated.matchAll(/\{\w+\}/g)].map(item => item[0]).sort());
  }
});
