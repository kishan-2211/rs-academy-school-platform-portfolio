import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";
import { manualCollectionMessages } from "./manual-collection-messages.ts";
import { manualCollectionErrors } from "../manual-fee-collection.ts";
import { hindi } from "./messages.ts";
import { gatewayRefundPostingMessages } from "./gateway-refund-posting-messages.ts";

const text = readFileSync(new URL("../../components/workspace/manual-fee-collection-form.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("manual-fee-collection-form.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
function controlledKeys(expression) {
  if (ts.isStringLiteral(expression)) return [expression.text];
  if (ts.isConditionalExpression(expression)) return [...controlledKeys(expression.whenTrue), ...controlledKeys(expression.whenFalse)];
  return [];
}
test("the manual form's controlled literal and conditional product copy has Hindi translations", () => {
  const keys = new Set();
  function visit(node) {
    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "t") {
      for (const key of controlledKeys(node.arguments[0])) keys.add(key);
    }
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && ["controlledRejections", "methodLabel", "sourceLabel"].includes(node.name.text)) {
      const inspect = (value) => { if (ts.isStringLiteral(value)) keys.add(value.text); ts.forEachChild(value, inspect); };
      inspect(node.initializer);
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  // Method/source condition comparisons are protocol codes rather than visible copy.
  for (const code of ["CASH", "SCHOOL_UPI", "BANK_TRANSFER", "CHEQUE", "BANK_APP_CREDIT"]) keys.delete(code);
  for (const copy of Object.values(manualCollectionErrors)) keys.add(copy);
  assert.ok(keys.size >= 75, "The catalogue check must inspect real form labels, outcomes and controlled errors");
  for (const key of keys) assert.ok(Object.hasOwn(manualCollectionMessages, key), `Missing manual collection translation: ${key}`);
  for (const [key, translation] of Object.entries(manualCollectionMessages)) {
    // This shared token error intentionally uses the later reviewed refund-posting
    // resource in the existing fees catalogue; do not replace working copy or
    // pretend every spread owns a distinct English source key.
    const expected = key === "The secure form token is unavailable. Refresh and try again."
      ? gatewayRefundPostingMessages[key] : translation;
    assert.equal(hindi.fees[key], expected, key);
  }
});
test("Hindi manual collection copy preserves every named value and distinguishes confirmed receipts from uncertain responses", () => {
  for (const [english, hindi] of Object.entries(manualCollectionMessages)) {
    assert.ok(hindi.trim() && /\p{Script=Devanagari}/u.test(hindi), english);
    assert.deepEqual([...english.matchAll(/\{\w+\}/g)].map((match) => match[0]).sort(),
      [...hindi.matchAll(/\{\w+\}/g)].map((match) => match[0]).sort(), english);
  }
  assert.notEqual(manualCollectionMessages["Receipt {receipt} was posted."],
    manualCollectionMessages["The collection response is uncertain. Do not collect again or change the draft. Review receipt history and retry only this saved request."]);
});
test("authored learner names, bank references, periods and collection notes are never translated as product text", () => {
  assert.doesNotMatch(text, /\bt\(\s*(?:props\.learnerName|charge\.(?:label|feeHeadName)|draft\.(?:notes|externalReference))/);
  assert.match(text, /\{charge\.label\}/);
  assert.match(text, /\{charge\.feeHeadName\}/);
  assert.match(text, /value=\{draft\.externalReference\}/);
  assert.match(text, /value=\{draft\.notes\}/);
});
