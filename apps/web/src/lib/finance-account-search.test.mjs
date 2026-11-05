import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import ts from "typescript";
import { feeAccountMatches } from "./finance-account-search.ts";

const account = Object.freeze({ learnerName: "Synthetic Learner", admissionNumber: "SYN/042",
  gradeCode: "VI", sessionName: "2026–2027", rollNumber: "017A", sectionCode: "B",
  charges: Object.freeze([Object.freeze({ label: "October tuition", feeHeadName: "Tuition" }),
    Object.freeze({ label: "परीक्षा शुल्क", feeHeadName: "परीक्षा" })]) });

test("empty queries retain every authorized account", () => {
  for (const query of ["", " \t\n "]) assert.equal(feeAccountMatches(account, query), true);
});
test("learner, admission, class, section, roll, session and period search is literal and case-insensitive", () => {
  for (const query of ["learner", "syn/042", "vi", "B 017a", "2026", "octOBer", "tuition", "परीक्षा शुल्क"])
    assert.equal(feeAccountMatches(account, query), true, query);
});
test("all search words must match, including across fields", () => {
  assert.equal(feeAccountMatches(account, "  SYN/042  october VI "), true);
  assert.equal(feeAccountMatches(account, "SYN/042 november"), false);
  assert.equal(feeAccountMatches(account, "VI B 017A october"), true);
  assert.equal(feeAccountMatches(account, "VI A 099"), false);
});
test("regular-expression and HTML-like text never gain special matching behavior", () => {
  for (const query of [".*", "[VI]", "<script>", "learner|other", "%"])
    assert.equal(feeAccountMatches(account, query), false);
});
test("Unicode normalization supports full-width input without changing authored school records", () => {
  assert.equal(feeAccountMatches(account, "ＳＹＮ/０４２"), true);
  assert.equal(account.admissionNumber, "SYN/042");
  assert.equal(account.charges[1].label, "परीक्षा शुल्क");
});
test("missing admission numbers and empty charge lists remain searchable honestly", () => {
  const pending = { learnerName: "Synthetic New Learner", gradeCode: "NUR", sessionName: "2026–2027", charges: [] };
  assert.equal(feeAccountMatches(pending, "new NUR"), true);
  assert.equal(feeAccountMatches(pending, "pending"), false);
  assert.equal(feeAccountMatches(pending, "042"), false);
  assert.equal(feeAccountMatches(pending, "017A"), false);
});

test("actual workspace search cannot silently select a different child or collect on a filtered-out account", () => {
  const source = readFileSync(new URL("../components/workspace/finance-workspace.tsx", import.meta.url), "utf8");
  const ast = ts.createSourceFile("finance-workspace.tsx", source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let searchInput;
  let collection;
  function visit(node) {
    if (ts.isJsxSelfClosingElement(node) && node.tagName.getText(ast) === "input" && node.attributes.properties.some(
      (attribute) => ts.isJsxAttribute(attribute) && attribute.name.getText(ast) === "id" && attribute.initializer?.getText(ast) === '"finance-account-search"')) searchInput = node;
    if (ts.isJsxSelfClosingElement(node) && node.tagName.getText(ast) === "ManualFeeCollectionForm") collection = node;
    ts.forEachChild(node, visit);
  }
  visit(ast);
  const attr = (node, name) => node.attributes.properties.find((attribute) => ts.isJsxAttribute(attribute) && attribute.name.getText(ast) === name)?.initializer;
  assert.ok(searchInput && collection);
  assert.equal(attr(searchInput, "disabled").getText(ast), "{collectionBlocked}");
  const change = attr(searchInput, "onChange").expression;
  assert.match(change.getText(ast), /setAccountQuery\(event\.target\.value\)/);
  assert.doesNotMatch(change.getText(ast), /setSelectedAccountId|mutate|fetch|load/);
  assert.equal(attr(collection, "externallyBusy").getText(ast), "{busy || !selectedAccountMatchesSearch}");
  assert.match(source, /matchingAccounts\.some\(\(account\) => account\.id === selectedAccountId\)/);
  assert.match(source, /<label htmlFor="finance-account-search">/);
  assert.match(source, /id="finance-account-search-status" role="status" aria-live="polite" aria-atomic="true"/);
});

test("the finance module keeps its detail panels inside the existing workspace main landmark", () => {
  const source = readFileSync(new URL("../components/workspace/finance-workspace.tsx", import.meta.url), "utf8");
  const ast = ts.createSourceFile("finance-workspace.tsx", source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const details = []; let nestedMain = 0;
  function visit(node) {
    if (ts.isJsxOpeningElement(node)) {
      if (node.tagName.getText(ast) === "main") nestedMain++;
      if (node.attributes.properties.some(attribute => ts.isJsxAttribute(attribute)
        && attribute.name.getText(ast) === "className" && attribute.initializer?.getText(ast) === '"finance-detail"')) details.push(node);
    }
    ts.forEachChild(node, visit);
  }
  visit(ast);
  assert.equal(nestedMain, 0, "The workspace shell already owns the page's main landmark");
  assert.equal(details.length, 2, "Both existing account and plan panels are preserved");
  assert.ok(details.every(node => node.tagName.getText(ast) === "div"));
  assert.match(source, /<h2>\{selectedAccount\.learnerName\}<\/h2>/);
  assert.match(source, /<h2>\{selectedPlan\.name\}<\/h2>/);
});
