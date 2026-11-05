import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";
import { gatewayReviewMessages } from "./gateway-review-messages.ts";
import { gatewayRefundPostingMessages } from "./gateway-refund-posting-messages.ts";
import { collectionRegisterMessages } from "./collection-register-messages.ts";
import { hindi } from "./messages.ts";
import { translate } from "./translate.ts";
import { gatewayConfigurationLabel } from "../gateway-review.ts";

const panelSource = readFileSync(new URL("../../components/workspace/gateway-review-panel.tsx", import.meta.url), "utf8");
const panel = ts.createSourceFile("gateway-review-panel.tsx", panelSource, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);

function productKeys(expression) {
  if (ts.isStringLiteral(expression)) return [expression.text];
  if (ts.isConditionalExpression(expression)) return [...productKeys(expression.whenTrue), ...productKeys(expression.whenFalse)];
  assert.fail("Gateway review translation keys must be controlled literal product copy");
}

test("fees catalogue covers every literal and conditional gateway review product key", () => {
  const keys = new Set();
  function visit(node) {
    // The helper's controlled map and caller-provided fallbacks are inspected separately below.
    if (ts.isFunctionDeclaration(node) && node.name?.text === "responseProblem") return;
    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "t") {
      for (const key of productKeys(node.arguments[0])) keys.add(key);
    }
    ts.forEachChild(node, visit);
  }
  visit(panel);
  assert.ok(keys.size >= 35, "The panel catalogue check must inspect its product copy");
  const sharedCopy = {
    "The secure form token is unavailable. Refresh and try again.":
      gatewayRefundPostingMessages["The secure form token is unavailable. Refresh and try again."],
    "Page {page}": collectionRegisterMessages["Page {page}"],
  };
  for (const [key, translation] of Object.entries(gatewayReviewMessages)) {
    // These two shared source keys resolve from later fees-domain resources;
    // every other panel key must still match its own reviewed translation.
    const expected = Object.hasOwn(sharedCopy, key) ? sharedCopy[key] : translation;
    assert.equal(hindi.fees[key], expected, key);
  }
  for (const key of keys) assert.ok(Object.hasOwn(gatewayReviewMessages, key), `Missing gateway review translation: ${key}`);
  // State-selected copy is explicit, including the in-flight posting button.
  for (const key of ["Held for review", "Posted", "Refund review required", "Unresolved", "Posting…", "Review and post receipt"]) {
    assert.ok(keys.has(key), `Expected controlled state key: ${key}`);
    assert.ok(Object.hasOwn(gatewayReviewMessages, key));
  }
});

test("controlled server error codes and both response fallbacks have Hindi catalogue entries", () => {
  const helper = panel.statements.find((node) => ts.isFunctionDeclaration(node) && node.name?.text === "responseProblem");
  assert.ok(helper, "The controlled response helper must be present");
  const codes = new Map();
  function inspectMap(node) {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === "messages") {
      assert.ok(node.initializer && ts.isObjectLiteralExpression(node.initializer), "Server errors must use a controlled literal map");
      for (const property of node.initializer.properties) {
        assert.ok(ts.isPropertyAssignment(property) && (ts.isIdentifier(property.name) || ts.isStringLiteral(property.name))
          && ts.isStringLiteral(property.initializer), "Server error messages must remain controlled literal product copy");
        codes.set(property.name.text, property.initializer.text);
      }
    }
    ts.forEachChild(node, inspectMap);
  }
  inspectMap(helper);
  assert.deepEqual([...codes.keys()].sort(), ["ACCESS_DENIED", "FINANCE_SCOPE_DENIED", "GATEWAY_CAPTURE_NOT_FOUND",
    "GATEWAY_CAPTURE_REVIEW_MISMATCH", "GATEWAY_POSTING_DATE_CLOSED", "GATEWAY_REVIEW_NOT_ELIGIBLE"].sort());
  for (const [code, source] of codes) assert.ok(Object.hasOwn(gatewayReviewMessages, source), `Missing ${code}: ${source}`);
  let fallbacks = 0;
  function inspectFallbacks(node) {
    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "responseProblem") {
      assert.ok(ts.isStringLiteral(node.arguments[1]), "Response fallback must remain controlled product copy");
      assert.ok(Object.hasOwn(gatewayReviewMessages, node.arguments[1].text), node.arguments[1].text);
      fallbacks += 1;
    }
    ts.forEachChild(node, inspectFallbacks);
  }
  inspectFallbacks(panel);
  assert.equal(fallbacks, 2, "Both queue and posting response paths must have translated fallbacks");
});

test("gateway review Hindi preserves placeholders and distinguishes posted fees from held evidence", () => {
  for (const [english, translated] of Object.entries(gatewayReviewMessages)) {
    assert.ok(translated.trim() && /\p{Script=Devanagari}/u.test(translated), english);
    assert.deepEqual([...english.matchAll(/\{\w+\}/g)].map((match) => match[0]).sort(),
      [...translated.matchAll(/\{\w+\}/g)].map((match) => match[0]).sort(), english);
  }
  assert.notEqual(gatewayReviewMessages["Held for review"], gatewayReviewMessages["Posted"]);
  assert.equal(translate("en", "fees", "Reviewed posting for {name}", { name: "Synthetic learner" }),
    "Reviewed posting for Synthetic learner");
  assert.equal(translate("hi", "fees", "Reviewed posting for {name}", { name: "Synthetic $& learner" }),
    "Synthetic $& learner का भुगतान समीक्षा के बाद दर्ज करें");
});

test("gateway review does not translate authored learner names or hold reasons", () => {
  assert.doesNotMatch(panelSource, /\bt\(\s*capture\.(?:learnerName|holdReason|admissionNumber|receiptNumber)/);
  assert.match(panelSource, /\{capture\.holdReason\}/);
});

test("configuration-only status labels and the operational caveat have Hindi translations", () => {
  for (const state of ["NOT_CONFIGURED", "TEST_REDIRECT", "LIVE_REDIRECT", "UNEXPECTED"]) {
    assert.ok(Object.hasOwn(gatewayReviewMessages, gatewayConfigurationLabel(state)));
  }
  for (const source of ["Gateway configuration", "Checkout availability is checked separately."]) {
    assert.ok(Object.hasOwn(gatewayReviewMessages, source));
  }
});
