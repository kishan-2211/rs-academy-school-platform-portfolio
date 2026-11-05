import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { translate } from "./i18n/translate.ts";
import { financeShellMessages } from "./i18n/finance-shell-messages.ts";
import { settlementMessage } from "./i18n/gateway-settlement-messages.ts";
import * as gatewayReview from "./gateway-review.ts";
import { feeAccountMatches } from "./finance-account-search.ts";

const source = readFileSync(new URL("../components/workspace/finance-workspace.tsx", import.meta.url), "utf8");
const ast = ts.createSourceFile("finance-workspace.tsx", source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const stateNames = [];
function visit(node) {
  if (ts.isVariableDeclaration(node) && ts.isArrayBindingPattern(node.name)
      && node.initializer && ts.isCallExpression(node.initializer)
      && node.initializer.expression.getText(ast) === "useState") stateNames.push(node.name.elements[0].getText(ast));
  ts.forEachChild(node, visit);
}
visit(ast);
const empty = { schoolId: "81000000-0000-4000-8000-000000000001", schoolCurrency: "INR", onlinePaymentStatus: "NOT_CONFIGURED", accounts: [], concessions: [], feePlans: [], sessions: [], feeHeads: [], learners: [] };
function render(locale = "en", changes = {}) {
  let call = 0;
  const values = { data: empty, ...changes }, exports = {}, require = createRequire(import.meta.url);
  const react = { ...React, useState: initial => [Object.hasOwn(values, stateNames[call]) ? values[stateNames[call++]] : (call++, initial), () => {}] };
  const icon = () => React.createElement("span", { "aria-hidden": true });
  // Nested financial modules are not executed by these focused shell/SSR tests.
  const modules = {
    react,
    "@/components/i18n/language-provider": { useT: domain => (key, args) => translate(locale, domain, key, args), useLanguage: () => ({ locale }) },
    "@/lib/i18n/gateway-settlement-messages": { settlementMessage },
    "@/components/icons": { ArrowUpRight: icon },
    "@/components/workspace/workspace-icons": { MoneyIcon: icon, PlusIcon: icon, ReportIcon: icon },
    "@/lib/gateway-review": gatewayReview,
    "@/lib/finance-account-search": { feeAccountMatches },
    "@/lib/fee-collection-register": { refundDayCloseMinimum: () => { throw new Error("Day close is outside shell test scope"); } },
  };
  for (const [path, name] of [
    ["workspace/gateway-review-panel", "GatewayReviewPanel"], ["workspace/collection-register-panel", "CollectionRegisterPanel"],
    ["workspace/refund-requests-panel", "RefundRequestsPanel"], ["workspace/manual-fee-collection-form", "ManualFeeCollectionForm"], ["workspace/settlement-reports-panel", "SettlementReportsPanel"],
    ["fee-receipt-timing-text", "FeeReceiptTimingText"], ["receipt-pdf-actions", "ReceiptPdfActions"],
  ]) modules[`@/components/${path}`] = { [name]: () => null };
  modules["@/components/workspace/manual-fee-collection-form"] = { ManualFeeCollectionForm: () => React.createElement("div", { "data-manual-collection": "available" }) };
  const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } }).outputText;
  new Function("require", "exports", compiled)(name => {
    if (name.startsWith("@/") && !modules[name]) throw new Error(`Unreviewed shell dependency ${name}`);
    return modules[name] ?? require(name);
  }, exports);
  return renderToStaticMarkup(React.createElement(exports.FinanceWorkspace));
}

test("actual Hindi fee shell translates heading, explanatory copy, balance and card privacy notice", () => {
  const html = render("hi");
  for (const key of ["Fees & accountable finance", "Every amount has a reason, owner, and history.", "Draft the school's real schedules, activate them with authority, issue sequential receipts, and correct mistakes through visible counter-entries.", "Current account balance", "Card details never enter this application"]) assert.ok(html.includes(financeShellMessages[key]), key);
  assert.doesNotMatch(html, /Every amount has a reason|Card details never enter/);
  assert.match(html, /₹0\.00/);
});
test("English fee copy and source-backed amount remain unchanged", () => {
  const html = render();
  assert.match(html, /Every amount has a reason, owner, and history\./);
  assert.match(html, /Card details never enter this application/);
  assert.match(html, /0 approvals waiting/);
  assert.match(html, /₹0\.00/);
});
test("native fee navigation has translated accessible name and selected/disabled semantics", () => {
  const html = render("hi", { tab: "refunds", refundBlocked: true });
  assert.ok(html.includes(`aria-label="${financeShellMessages["Finance sections"]}"`));
  for (const key of ["Learner accounts", "Fee configuration", "Ageing & close", "Approvals"]) assert.ok(html.includes(financeShellMessages[key]));
  assert.equal((html.match(/<button\b[^>]*\bdisabled=""/g) ?? []).length, 8);
  assert.match(html, /aria-pressed="true"[^>]*>धन-वापसी अनुरोध/);
});
test("Hindi empty account guidance uses the maintained catalogue", () => {
  const html = render("hi");
  for (const key of ["Accounts", "No plan has been assigned to a learner yet.", "No learner fee account yet", "Activate a reviewed plan, then assign it to a current enrolment."]) assert.ok(html.includes(financeShellMessages[key]), key);
});

test("closed or unknown fee accounts preserve historical records without offering new manual collections", () => {
  const account={id:"81000000-0000-4000-8000-000000000002",learnerName:"Synthetic closed learner",sessionName:"Synthetic historical session",gradeCode:"VI",currencyCode:"INR",totalCharges:1000,totalCredits:200,balance:800,charges:[],payments:[],ledger:[]};
  for(const locale of ["en","hi"])for(const status of ["CLOSED","UNKNOWN"]){
    const html=render(locale,{data:{...empty,canCollect:true,accounts:[{...account,status}]},selectedAccountId:account.id});
    assert.doesNotMatch(html,/data-manual-collection/);assert.match(html,/Synthetic closed learner/);assert.match(html,/800\.00/);
    assert.ok(html.includes(locale==="hi"?financeShellMessages["Account history — new collections unavailable"]:"Account history — new collections unavailable"));
  }
  const active=render("en",{data:{...empty,canCollect:true,accounts:[{...account,status:"ACTIVE"}]},selectedAccountId:account.id});assert.match(active,/data-manual-collection="available"/);assert.doesNotMatch(active,/Account history — new collections unavailable/);
  const readOnly=render("en",{data:{...empty,canCollect:false,accounts:[{...account,status:"ACTIVE"}]},selectedAccountId:account.id});assert.doesNotMatch(readOnly,/data-manual-collection/);
});
test("approval counts keep zero, singular and plural interpolation in both languages", () => {
  for (const count of [0, 1, 2]) {
    const concessions = Array.from({ length: count }, () => ({ status: "PENDING" }));
    const key = count === 1 ? "{count} approval waiting" : "{count} approvals waiting";
    for (const locale of ["en", "hi"]) assert.ok(render(locale, { data: { ...empty, concessions } }).includes(translate(locale, "fees", key, { count })));
  }
});
test("loading and unavailable shell titles translate without rewriting server diagnostic detail", () => {
  assert.ok(render("hi", { data: null }).includes(financeShellMessages["Opening the finance workspace…"]));
  const html = render("hi", { data: null, error: "Synthetic diagnostic &lt;retained&gt;" });
  assert.ok(html.includes(financeShellMessages["Finance is unavailable"]));
  assert.match(html, /Synthetic diagnostic &amp;lt;retained&amp;gt;/);
});
test("learner names matching a product key remain untouched and safely escaped", () => {
  const learnerName = "Every amount has a reason, owner, and history.";
  const account = { id: "81000000-0000-4000-8000-000000000002", learnerName, admissionNumber: "SYN-LANG-001", gradeCode: "VI", sessionName: "Synthetic <session>", currencyCode: "INR", balance: 123.45, totalCharges: 123.45, totalCredits: 0, charges: [], payments: [], ledger: [] };
  const html = render("hi", { data: { ...empty, accounts: [account] }, selectedAccountId: account.id });
  assert.ok(html.includes(`<h2>${learnerName}</h2>`));
  assert.match(html, /Synthetic &lt;session&gt;/);
  assert.match(html, /₹123\.45/);
});
