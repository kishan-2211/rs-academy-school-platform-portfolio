import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";
import ts from "typescript";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import * as receipts from "./fee-receipt-documents.ts";
import * as family from "./family-documents.ts";
import { issueAttempt } from "./official-certificates.ts";
import { receiptPdfMessages } from "./i18n/receipt-pdf-messages.ts";
import { gatewayRefundPostingMessages } from "./i18n/gateway-refund-posting-messages.ts";
import { hindi } from "./i18n/messages.ts";

const text = readFileSync(
  new URL("../components/receipt-pdf-actions.tsx", import.meta.url),
  "utf8",
);
const ast = ts.createSourceFile(
  "receipt-pdf-actions.tsx",
  text,
  ts.ScriptTarget.Latest,
  true,
  ts.ScriptKind.TSX,
);
const handlers = new Map();
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) handlers.set(node.name.text, node);
  if (ts.isVariableDeclaration(node) && node.name.getText(ast) === "handleFailure")
    handlers.set("handleFailure", node.initializer.arguments[0]);
  ts.forEachChild(node, visit);
}
visit(ast);
function executable(name, bindings) {
  assert.ok(handlers.has(name), `Missing actual ${name} handler`);
  const source = ts.transpileModule(`const handler = ${handlers.get(name).getText(ast)};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  return new Function(...Object.keys(bindings), `${source}\nreturn handler;`)(
    ...Object.values(bindings),
  );
}
const school = "50000000-0000-4000-8000-000000000001";
const payment = "50000000-0000-4000-8000-000000000002";
const template = "50000000-0000-4000-8000-000000000003";
const documentId = "50000000-0000-4000-8000-000000000004";
const requestKey = "50000000-0000-4000-8000-000000000005";
const learner = "50000000-0000-4000-8000-000000000006";
const translate = (copy, values = {}) =>
  copy.replace(/\{(\w+)\}/g, (_, key) => String(values[key] ?? `{${key}}`));
function setup(overrides = {}) {
  const context = {
    schoolId: school,
    paymentId: payment,
    receiptNumber: "SYN-FEE-1",
    sourceStatus: "POSTED",
    canIssue: true,
    page: 0,
    pageSize: 25,
    hasMore: false,
    templates: [
      {
        id: template,
        displayName: "Synthetic template",
        locale: "en-IN",
        documentKind: "FEE_RECEIPT",
        status: "PUBLISHED",
        approvalRequired: true,
      },
    ],
    documents: [],
  };
  const state = {
    history: { page: 0, hasMore: false, documents: [], staff: [], context },
    busy: false,
    error: "",
    message: "",
    templateId: template,
    reason: "Synthetic PDF copy",
    uncertain: false,
    acknowledged: null,
    keys: 0,
    requests: [],
    guard: [],
    blobs: [],
    revocations: [],
  };
  const refs = {
    request: { current: null },
    attempt: { current: null },
    urls: { current: new Set() },
  };
  const props = {
    audience: "staff",
    schoolId: school,
    paymentId: payment,
    paymentStatus: "POSTED",
    receiptNumber: "SYN-FEE-1",
    ...overrides.props,
  };
  const options = {
    csrf: async () => "synthetic-csrf",
    send: async () => ({
      id: documentId,
      documentNumber: "SYN-PDF-1",
      status: "PENDING_APPROVAL",
      rowVersion: 0,
    }),
    read: async () => context,
    familyRead: async () => ({ documents: [], page: 0, hasMore: false }),
    ...overrides,
  };
  const setters = Object.fromEntries(
    [
      "history",
      "busy",
      "error",
      "message",
      "templateId",
      "reason",
      "uncertain",
      "acknowledged",
    ].map((name) => [
      `set${name[0].toUpperCase()}${name.slice(1)}`,
      (value) => {
        state[name] = typeof value === "function" ? value(state[name]) : value;
      },
    ]),
  );
  function bindings() {
    const values = {
      ...receipts,
      ...family,
      ...state,
      ...refs,
      ...setters,
      attemptRef: refs.attempt,
      props,
      issueAttempt,
      t: translate,
      onBusy: (value) => state.guard.push(value),
      receiptCsrfToken: options.csrf,
      sendReceiptIssue: async (...args) => {
        state.requests.push(args);
        return options.send(...args);
      },
      loadFeeReceiptPage: options.read,
      loadFamilyDocuments: options.familyRead,
      crypto: {
        randomUUID: () => {
          state.keys++;
          return requestKey;
        },
      },
      safeError: (error) =>
        executable("safeError", { ...receipts, documentProblem: family.documentProblem })(error),
      downloadable: (status) => ["ISSUED", "CANCELLED", "SUPERSEDED"].includes(status),
      loadStaffReceiptPdf: async () => new Blob(["%PDF-synthetic"], { type: "application/pdf" }),
      loadFamilyPdf: async () => new Blob(["%PDF-synthetic"], { type: "application/pdf" }),
      URL: {
        createObjectURL: () => "blob:synthetic",
        revokeObjectURL: (url) => state.revocations.push(url),
      },
      document: {
        createElement: () => ({
          click() {
            state.blobs.push(this);
          },
          remove() {},
        }),
        body: { appendChild() {} },
      },
      window: { setTimeout: (fn) => fn() },
    };
    values.handleFailure = (reason) => executable("handleFailure", values)(reason);
    values.readHistory = (page, signal) => executable("readHistory", values)(page, signal);
    return values;
  }
  return {
    state,
    refs,
    props,
    options,
    invoke: (name = "issue", argument = { preventDefault() {} }) =>
      executable(name, bindings())(argument),
  };
}

test("actual issue handler keeps amount collection separate and acknowledges independent approval truthfully", async () => {
  const fixture = setup();
  await fixture.invoke();
  assert.equal(fixture.state.keys, 1);
  assert.equal(fixture.state.requests.length, 1);
  const [attempt, token, signal] = fixture.state.requests[0];
  assert.equal(attempt.key, requestKey);
  assert.equal(token, "synthetic-csrf");
  assert.equal(signal.aborted, false);
  assert.deepEqual(JSON.parse(attempt.body), {
    schoolId: school,
    templateId: template,
    sourceType: "FEE_PAYMENT",
    sourceId: payment,
    supersedesDocumentId: null,
    reason: "Synthetic PDF copy",
  });
  assert.equal(fixture.state.acknowledged.status, "PENDING_APPROVAL");
  assert.equal(fixture.refs.attempt.current, null);
  assert.deepEqual(fixture.state.guard, [true, false]);
  assert.match(fixture.state.message, /request was recorded/);
  await fixture.invoke();
  assert.equal(fixture.state.requests.length, 1);
});
test("actual issue refuses family, busy collection, reversed sources and incomplete/occupied history", async () => {
  const fixtures = [
    setup({ props: { audience: "family", learnerId: learner } }),
    setup({ props: { externallyBusy: true } }),
    setup({ props: { paymentStatus: "REVERSED" } }),
    setup(),
    setup(),
  ];
  fixtures[3].state.history.context.hasMore = true;
  fixtures[4].state.history.staff = [{ templateId: template, status: "CANCELLED" }];
  for (const fixture of fixtures) {
    await fixture.invoke();
    assert.equal(fixture.state.requests.length, 0);
    assert.equal(fixture.state.keys, 0);
  }
});
test("rapid repeated issue cannot dispatch while the first actual request is in flight", async () => {
  let finish;
  const fixture = setup({
    send: () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  });
  const first = fixture.invoke();
  await new Promise((resolve) => setImmediate(resolve));
  await fixture.invoke();
  assert.equal(fixture.state.requests.length, 1);
  finish({ id: documentId, documentNumber: "SYN-PDF-2", status: "ISSUED", rowVersion: 0 });
  await first;
});
test("lost issue response retries only the same immutable body and key even if editable state changes", async () => {
  const fixture = setup({
    send: async () => {
      throw new TypeError("Synthetic lost response");
    },
  });
  await fixture.invoke();
  const saved = fixture.refs.attempt.current;
  assert.equal(fixture.state.uncertain, true);
  assert.match(fixture.state.error, /unchanged request/);
  fixture.state.templateId = learner;
  fixture.state.reason = "Different synthetic reason";
  fixture.options.send = async () => ({
    id: documentId,
    documentNumber: "SYN-PDF-1",
    status: "PENDING_APPROVAL",
    rowVersion: 0,
  });
  await fixture.invoke();
  assert.equal(fixture.state.keys, 1);
  assert.equal(fixture.state.requests[0][0], saved);
  assert.equal(fixture.state.requests[1][0], saved);
});
test("token failure before dispatch never allocates a new key; after uncertainty it never erases the original key", async () => {
  const fresh = setup({
    csrf: async () => {
      throw new Error(receipts.receiptDocumentErrors.token);
    },
  });
  await fresh.invoke();
  assert.equal(fresh.state.keys, 0);
  assert.equal(fresh.refs.attempt.current, null);
  assert.equal(fresh.state.uncertain, false);
  const old = setup({
    send: async () => {
      throw new TypeError("Synthetic response loss");
    },
  });
  await old.invoke();
  const original = old.refs.attempt.current;
  old.options.csrf = async () => {
    throw new Error(receipts.receiptDocumentErrors.session);
  };
  await old.invoke();
  assert.equal(old.refs.attempt.current, original);
  assert.equal(old.state.uncertain, true);
  assert.equal(old.state.history, null);
});
test("actual aborted request never shows acknowledgment or changes a different scope", async () => {
  let finish;
  const fixture = setup({
    send: () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  });
  const pending = fixture.invoke();
  await new Promise((resolve) => setImmediate(resolve));
  fixture.refs.request.current.abort();
  finish({ id: documentId, documentNumber: "SYN-PDF-1", status: "ISSUED", rowVersion: 0 });
  await pending;
  assert.equal(fixture.state.acknowledged, null);
  assert.equal(fixture.refs.attempt.current.key, requestKey);
  assert.deepEqual(fixture.state.guard, [true]);
});
test("explicit family/staff revocation clears loaded private data and blob URLs but preserves an uncertain retry", () => {
  for (const problem of [
    family.documentProblem(401),
    family.documentProblem(403),
    receipts.receiptDocumentErrors.scope,
  ]) {
    const fixture = setup();
    fixture.state.acknowledged = { documentNumber: "SYN-PRIVATE-1" };
    fixture.refs.attempt.current = { key: requestKey, body: "Synthetic unchanged request" };
    fixture.refs.urls.current.add("blob:synthetic");
    fixture.invoke("handleFailure", new Error(problem));
    assert.equal(fixture.state.history, null);
    assert.equal(fixture.state.acknowledged, null);
    assert.equal(fixture.state.templateId, "");
    assert.equal(fixture.state.reason, "");
    assert.equal(fixture.refs.attempt.current.key, requestKey);
    assert.deepEqual(fixture.state.revocations, ["blob:synthetic"]);
  }
  const storage = setup();
  storage.invoke("handleFailure", new Error(receipts.receiptDocumentErrors.storage));
  assert.ok(storage.state.history);
  assert.match(storage.state.error, /temporarily unavailable/);
});
test("actual scoped lookup binds family payment ID and rejects another staff receipt number", async () => {
  const fixture = setup({
    read: async () => ({ schoolId: school, receiptNumber: "SYN-WRONG-RECEIPT" }),
  });
  await assert.rejects(() => fixture.invoke("readHistory", 0), /unavailable/);
  const child = setup({
    props: { audience: "family", learnerId: learner },
    familyRead: async (...args) => {
      assert.equal(args[0], learner);
      assert.equal(args[3], payment);
      return { page: 0, hasMore: false, documents: [] };
    },
  });
  assert.equal((await child.invoke("readHistory", 0)).context, null);
});
test("actual download uses only verified server bytes and a generic PDF filename, not private bank evidence", async () => {
  const fixture = setup();
  await fixture.invoke("download", {
    id: documentId,
    documentNumber: "SYN-PDF-1",
    status: "ISSUED",
  });
  assert.equal(fixture.state.blobs[0].download, `fee-receipt-${documentId}.pdf`);
  assert.deepEqual(fixture.state.revocations, ["blob:synthetic"]);
  assert.equal(fixture.refs.urls.current.size, 0);
  assert.match(fixture.state.message, /Download requested/);
  assert.equal(fixture.state.requests.length, 0);
});

function componentModule() {
  const require = createRequire(import.meta.url);
  const exports = {};
  const output = ts.transpileModule(text, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      jsx: ts.JsxEmit.ReactJSX,
    },
  }).outputText;
  const dependencies = (name) =>
    name === "next/link"
      ? { __esModule: true, default: (props) => React.createElement("a", props) }
      : name.endsWith(".module.css")
        ? { __esModule: true, default: new Proxy({}, { get: (_, key) => `receipt-${key}` }) }
        : name === "@/components/i18n/language-provider"
          ? { useT: () => translate }
          : name === "@/lib/family-documents"
            ? family
            : name === "@/lib/official-certificates"
              ? { issueAttempt }
              : name === "@/lib/fee-receipt-documents"
                ? receipts
                : require(name);
  new Function("require", "exports", output)(dependencies, exports);
  return exports;
}
test("real React rendering starts collapsed with labelled native keyboard controls and no private network request", () => {
  const component = componentModule().ReceiptPdfActions;
  const props = setup().props;
  const html = renderToStaticMarkup(React.createElement(component, props));
  assert.match(html, /aria-label="PDFs for receipt SYN-FEE-1"/);
  assert.match(
    html,
    /<button type="button" aria-expanded="false" aria-controls="[^"]+">Receipt PDFs<\/button>/,
  );
  assert.match(html, /hidden=""/);
  assert.doesNotMatch(html, /<form|schoolId|SYN-PDF/);
  const first = component(props);
  const other = component({ ...props, schoolId: learner });
  assert.notEqual(
    first.key,
    other.key,
    "Every state/ref (not only the panel) resets at the scope boundary",
  );
});
test("actual controls preserve responsive full-row family layout, visible focus, status colors and reduced motion", () => {
  const css = readFileSync(
    new URL("../components/receipt-pdf-actions.module.css", import.meta.url),
    "utf8",
  );
  assert.match(css, /\.familyReceiptRow \{ flex-wrap: wrap; \}/);
  assert.match(css, /flex: 1 1 100%/);
  assert.match(css, /min-height: 44px/);
  assert.match(css, /outline: 3px solid var\(--theme-text\)/);
  assert.match(css, /\.root \.panel \.error[^}]*color: var\(--theme-error-text\)/);
  assert.match(css, /max-width: 600px/);
  assert.match(css, /prefers-reduced-motion: reduce/);
  assert.match(text, /role="alert"/);
  assert.match(text, /role="status"/);
  assert.match(text, /controller\.abort\(\);\s*request\.current\?\.abort\(\)/);
  assert.doesNotMatch(text, /localStorage|sessionStorage|window\.open|finance\/payments/);
});
test("every controlled receipt label/error/status has Hindi copy with unchanged placeholders", () => {
  const keys = new Set(Object.values(receipts.receiptDocumentErrors));
  function inspect(node) {
    if (ts.isCallExpression(node) && node.expression.getText(ast) === "t") {
      function literals(value) {
        if (ts.isStringLiteral(value)) keys.add(value.text);
        else if (ts.isConditionalExpression(value)) {
          literals(value.whenTrue);
          literals(value.whenFalse);
        }
      }
      literals(node.arguments[0]);
    }
    if (ts.isVariableDeclaration(node) && node.name.getText(ast) === "statusCopy")
      node.initializer.expression.properties.forEach((item) => keys.add(item.initializer.text));
    if (
      ts.isCallExpression(node) &&
      ["setError", "setMessage"].includes(node.expression.getText(ast)) &&
      ts.isStringLiteral(node.arguments[0]) &&
      node.arguments[0].text
    )
      keys.add(node.arguments[0].text);
    ts.forEachChild(node, inspect);
  }
  inspect(ast);
  assert.ok(keys.size >= 35);
  for (const key of keys)
    assert.ok(Object.hasOwn(receiptPdfMessages, key), `Missing Hindi: ${key}`);
  for (const [english, translated] of Object.entries(receiptPdfMessages)) {
    // Preserve the exact intentional fees-domain override for the shared token
    // error, without exempting any other receipt label, error or placeholder.
    const expected = english === "The secure form token is unavailable. Refresh and try again."
      ? gatewayRefundPostingMessages[english] : translated;
    assert.equal(hindi.fees[english], expected, english);
    assert.match(translated, /\p{Script=Devanagari}/u);
    assert.deepEqual(
      [...english.matchAll(/\{\w+\}/g)].map((item) => item[0]).sort(),
      [...translated.matchAll(/\{\w+\}/g)].map((item) => item[0]).sort(),
    );
  }
});
