import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";
import { accountingText, accountingCode, accountingError, AccountingUiError } from "./i18n/accounting-messages.ts";

function implementation(file) {
  const source = ts.createSourceFile(file, readFileSync(new URL(`../components/workspace/${file}`, import.meta.url), "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const nodes = new Map();
  function visit(node) {
    if (ts.isFunctionDeclaration(node) && node.name) nodes.set(node.name.text, node);
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer && ts.isCallExpression(node.initializer) && node.initializer.expression.getText(source) === "useCallback") nodes.set(node.name.text, node.initializer.arguments[0]);
    ts.forEachChild(node, visit);
  }
  visit(source);
  return (name, bindings) => {
    assert.ok(nodes.has(name), name);
    const compiled = ts.transpileModule(`const handler = ${nodes.get(name).getText(source)};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
    const context = { accountingText, accountingCode, accountingError, AccountingUiError, ...bindings }; return new Function(...Object.keys(context), `${compiled}\nreturn handler;`)(...Object.values(context));
  };
}

const accounting = implementation("accounting-workspace.tsx");
const feeAccounting = implementation("fee-accounting-panel.tsx");
const cases = [
  [accounting, "createYear", "/financial-years", { code: "SYN_FY", name: "Synthetic book", startsOn: "2026-04-01", endsOn: "2027-03-31", accountingBasis: "ACCRUAL", currencyCode: "INR", reason: "Synthetic acceptance" }],
  [accounting, "createAccount", "/accounts", { code: "SYN_CASH", name: "Synthetic cash", accountType: "ASSET", category: "CASH", allowDirectPosting: "on" }],
  [accounting, "createVendor", "/vendors", { code: "SYN_VENDOR", name: "Synthetic vendor" }],
  [accounting, "createJournal", "/journals", { accountingPeriodId: "synthetic-period", voucherType: "RECEIPT", postingDate: "2026-10-03", narration: "Synthetic voucher" }],
  [feeAccounting, "saveConfiguration", "/configurations", { reason: "Synthetic mapping" }],
  [feeAccounting, "preparePosting", "/postings", { businessDate: "2026-10-03", eventType: "PAYMENT", maxEntries: "200", reason: "Synthetic evidence" }],
];

for (const [compile, name, path, fields] of cases) {
  for (const saved of [true, false]) {
    test(`${name} ${saved ? "clears acknowledged inputs" : "retains rejected inputs"} after React clears currentTarget`, async () => {
      let resolve;
      let request;
      let resets = 0;
      let clearedLines = 0;
      const journalKey = { current: "synthetic-journal-retry" };
      const postingKey = { current: "synthetic-posting-retry" };
      const bindings = {
        data: { schoolId: "synthetic-school" }, schoolId: "synthetic-school",
        selectedYear: { id: "synthetic-year" }, year: { id: "synthetic-year", accountingBasis: "ACCRUAL" },
        draft: undefined, approved: undefined,
        snapshot: { feeHeads: [{ id: "synthetic-head" }] },
        effectiveFrom: "2026-04-01", receivableAccountId: "synthetic-receivable", concessionAccountId: "synthetic-expense",
        headMappings: { "synthetic-head": "synthetic-income" }, tenderMappings: { CASH: "synthetic-cash" }, tenders: ["CASH"],
        lines: [{ accountId: "synthetic-cash", debitAmount: "600", creditAmount: "" }, { accountId: "synthetic-income", debitAmount: "", creditAmount: "600" }],
        journalKey, postingKey, emptyLine: () => ({}), setLines: () => { clearedLines++; },
        setChangeReason: () => { resets++; },
        mutate: (...args) => { request = args; return new Promise((done) => { resolve = done; }); },
        FormData: class { constructor(form) { this.form = form; } get(key) { return this.form.values[key] ?? null; } },
      };
      const form = { values: { ...fields }, reset() { resets++; } };
      const event = { currentTarget: form, preventDefault() {} };
      const pending = compile(name, bindings)(event);
      event.currentTarget = null;
      assert.equal(resets, 0);
      resolve(saved ? { id: "synthetic-new-year", rowVersion: 0 } : null);
      await pending;
      assert.equal(resets, saved ? 1 : 0);
      assert.equal(request[0], path);
      assert.equal(request[1], "POST");
      assert.equal(request[2].schoolId, "synthetic-school");
      assert.deepEqual(form.values, fields);
      if (name === "createYear") assert.deepEqual(request[5], { from: fields.startsOn, to: fields.endsOn });
      if (name === "createJournal") {
        assert.equal(request[4], "synthetic-journal-retry");
        assert.equal(journalKey.current, saved ? "" : "synthetic-journal-retry");
        assert.equal(clearedLines, saved ? 1 : 0);
      }
      if (name === "preparePosting") {
        assert.equal(request[4], "synthetic-posting-retry");
        assert.equal(postingKey.current, saved ? "" : "synthetic-posting-retry");
      }
    });
  }
}

for (const overrides of [undefined, { yearId: "synthetic-new-year", from: "2026-04-01", to: "2027-03-31" }]) {
  test(`accounting load ${overrides ? "selects the newly created book with its own valid range" : "preserves existing report filters"}`, async () => {
    let request;
    const state = { yearId: "synthetic-old-year", from: "2026-01-01", to: "2026-10-03" };
    const result = { selectedFinancialYearId: "synthetic-new-year", from: "2026-04-01", to: "2027-03-31" };
    const bindings = {
      ...state, apiUrl: "http://localhost:8080",
      fetch: async (...args) => { request = args; return { ok: true, json: async () => result }; },
      setData: () => {}, setYearId: (update) => { state.yearId = update(state.yearId); },
      setFrom: (update) => { state.from = update(state.from); }, setTo: (update) => { state.to = update(state.to); },
    };
    await accounting("load", bindings)(overrides);
    const expected = overrides ?? { yearId: "synthetic-old-year", from: "2026-01-01", to: "2026-10-03" };
    const query = new URL(request[0]).searchParams;
    assert.equal(query.get("financialYearId"), expected.yearId);
    assert.equal(query.get("from"), expected.from);
    assert.equal(query.get("to"), expected.to);
    assert.equal(request[1].credentials, "include");
    assert.deepEqual(state, expected);
  });
}

for (const range of [undefined, { from: "2026-04-01", to: "2027-03-31" }]) {
  test(`successful accounting mutation ${range ? "refreshes the saved year's identity and dates" : "does not replace normal filters"}`, async () => {
    let refreshed;
    const result = { id: "synthetic-new-year", rowVersion: 0 };
    const handler = accounting("mutate", {
      apiUrl: "http://localhost:8080", csrfToken: async () => "synthetic-token",
      fetch: async () => ({ ok: true, json: async () => result }),
      load: async (filters) => { refreshed = filters; },
      refreshRequired: false, setRefreshRequired: () => {},
      setBusy: () => {}, setError: () => {}, setNotice: () => {},
    });
    assert.equal(await handler("/financial-years", "POST", {}, "Synthetic saved", undefined, range), result);
    assert.deepEqual(refreshed, range ? { yearId: result.id, ...range } : undefined);
  });
}
