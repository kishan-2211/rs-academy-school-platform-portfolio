import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const require = createRequire(import.meta.url);
const componentPath = fileURLToPath(new URL("../components/workspace/payroll-recovery-evidence.tsx", import.meta.url));
const compiled = ts.transpileModule(readFileSync(componentPath, "utf8"), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 },
}).outputText;
const componentModule = { exports: {} };
new vm.Script("(function(require,module,exports){" + compiled + "\n})", { filename: componentPath })
  .runInThisContext()(require, componentModule, componentModule.exports);
const { PayrollRecoveryEvidence } = componentModule.exports;

test("renders no misleading recovery section when there are no frozen installments", () => {
  assert.equal(renderToStaticMarkup(React.createElement(PayrollRecoveryEvidence, { recoveries: [], currency: "INR" })), "");
});

test("labels reserved and historical recovery evidence without claiming early repayment", () => {
  const html = renderToStaticMarkup(React.createElement(PayrollRecoveryEvidence, {
    currency: "INR",
    recoveries: [
      { id: "one", advanceId: "advance", advanceNumber: "SYN-ADV-001", installmentId: "i1", dueMonth: "2026-09-01", amount: 33.33, reservationStatus: "ACTIVE" },
      { id: "two", advanceId: "advance", advanceNumber: "SYN-ADV-001", installmentId: "i2", dueMonth: "2026-10-01", amount: 33.35, reservationStatus: "RELEASED" },
    ],
  }));
  assert.match(html, /2 advance recoveries/);
  assert.match(html, /Reserved for this payroll/);
  assert.match(html, /Released — historical evidence/);
  assert.match(html, /repayment takes effect only when the matching payroll accrual is posted/i);
  assert.match(html, /₹33\.33/);
});
