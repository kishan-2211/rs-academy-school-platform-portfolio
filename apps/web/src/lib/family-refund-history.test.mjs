import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { signedFeePaise } from "./family-fee-accounts.ts";
import { familyFeeAccountMessages } from "./i18n/family-fee-account-messages.ts";

const read = name => readFileSync(new URL(name, import.meta.url), "utf8");
const source = read("../components/family/refund-history.tsx");
const refund = { id: "7c000000-0000-4000-8000-000000000001", paymentId: "7c000000-0000-4000-8000-000000000002", receiptNumber: "SYN-000001", amount: "50.25", currencyCode: "INR", postingOn: "2026-10-20", processedAtExact: "2026-10-19T18:30:00.123456789Z", processedOn: "2026-10-20", schoolTimeZone: "Asia/Kolkata" };
function render(refunds, locale = "en") {
  const exports = {}, require = createRequire(import.meta.url);
  const bind = name => name === "react" ? React : name === "@/components/i18n/language-provider" ? { useT: () => text => locale === "hi" ? familyFeeAccountMessages[text] ?? text : text, useLanguage: () => ({ locale }) } : name === "@/lib/family-fee-accounts" ? { signedFeePaise } : name.endsWith(".module.css") ? { __esModule: true, default: new Proxy({}, { get: (_, key) => `refund-${key}` }) } : require(name);
  new Function("require", "exports", ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } }).outputText)(bind, exports);
  return renderToStaticMarkup(React.createElement(exports.FamilyRefundHistory, { refunds }));
}
test("legacy omitted field and empty completed list never invent refund success", () => {
  assert.equal(render(undefined), ""); assert.equal(render([]), "");
});
test("actual family component retains original receipt, exact processing instant and distinct posting day", () => {
  const html = render([refund]);
  assert.match(html, /SYN-000001/); assert.match(html, /₹50\.25/);
  assert.match(html, /2026-10-19T18:30:00\.123456789Z/); assert.match(html, /Asia\/Kolkata/);
  assert.match(html, /Original receipts and payment amounts remain unchanged/);
  assert.match(html, /Any waiver requires a separate school approval/); assert.match(html, /aria-labelledby=/);
  assert.doesNotMatch(html, /<button|Pay now|credentials|gatewayCaptureId/);
});
test("malformed, duplicate or nonpositive completed refund projections fail closed rather than show paid success", () => {
  for (const value of [null, {}, [refund, refund], [{ ...refund, amount: "-50.25" }], [{ ...refund, amount: "50.255" }], [{ ...refund, processedAtExact: "not-a-time" }]]) {
    const html = render(value); assert.match(html, /role="alert"/); assert.doesNotMatch(html, /SYN-000001|₹50\.25/);
  }
});
test("family refund copy escapes receipt text and is fully bilingual without adding animation or private storage", () => {
  const html = render([{ ...refund, receiptNumber: "<script>Synthetic</script>" }], "hi");
  assert.match(html, /&lt;script&gt;/); assert.doesNotMatch(html, /<script>/); assert.match(html, /दर्ज फीस वापसी/);
  const ast = ts.createSourceFile("actual.tsx", source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  function visit(node) { if (ts.isCallExpression(node) && node.expression.getText(ast) === "t" && ts.isStringLiteral(node.arguments[0])) assert.match(familyFeeAccountMessages[node.arguments[0].text] ?? "", /\p{Script=Devanagari}/u); ts.forEachChild(node, visit); } visit(ast);
  const css = read("../components/family/refund-history.module.css"); assert.match(css, /max-width: 640px/); assert.match(css, /var\(--theme-card\)/);
  assert.doesNotMatch(source + css, /localStorage|sessionStorage|animation:|fetch\(/);
});
