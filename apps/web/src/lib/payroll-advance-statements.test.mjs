import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { isScopedAdvanceStatement } from "./payroll-advance-statements.ts";

const componentPath = fileURLToPath(
  new URL("../components/workspace/payroll-advance-statement.tsx", import.meta.url),
);
const require = createRequire(componentPath);
const compiled = ts.transpileModule(readFileSync(componentPath, "utf8"), {
  compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    jsx: ts.JsxEmit.ReactJSX,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText;
const componentModule = { exports: {} };
new vm.Script("(function(require,module,exports){" + compiled + "\n})", {
  filename: componentPath,
}).runInThisContext()(
  (id) => require(id.endsWith("/payroll-accounting") ? id + ".ts" : id),
  componentModule,
  componentModule.exports,
);
const { PayrollAdvanceStatementEvidence } = componentModule.exports;

const statement = {
  schoolId: "school-one",
  schoolName: "Synthetic school",
  generatedAt: "2026-09-06T10:00:00Z",
  advanceId: "advance-one",
  advanceNumber: "EPA-202509-001",
  advanceType: "ADVANCE",
  purpose: "Synthetic test purpose",
  staffProfileId: "staff-one",
  employeeCode: "EMP-SYN-1",
  staffName: "Synthetic employee",
  issueDate: "2025-09-01",
  currencyCode: "INR",
  principalAmount: 100.01,
  advanceStatus: "APPROVED",
  postedDisbursementAmount: 100.01,
  recoveredAmount: 33.33,
  outstandingAmount: 66.68,
  evidenceStatus: "PARTIALLY_RECOVERED",
  disbursements: [],
  installments: [
    {
      id: "installment-one",
      installmentNumber: 1,
      dueMonth: "2025-10-01",
      plannedAmount: 33.33,
      recoveredAmount: 33.33,
      outstandingAmount: 0,
      status: "RECOVERED",
      recoveryAttempts: [
        {
          id: "recovery-one",
          payrollRunId: "run-one",
          runCode: "PAY-202510-001",
          payrollMonth: "2025-10-01",
          payrollRunStatus: "FINALIZED",
          amount: 33.33,
          reservationStatus: "ACTIVE",
          reservedAt: "2025-10-31T10:00:00Z",
          releasedAt: null,
          releaseReason: null,
          releasedBy: null,
          status: "RECOVERED",
          ledgerPostings: [
            {
              batchId: "batch-one",
              postingDate: "2025-10-31",
              sourceStatus: "ACTIVE",
              accountingJournalId: "journal-one",
              journalStatus: "POSTED",
              reversalJournalId: null,
              reversalStatus: null,
              evidenceStatus: "MATCHED_POSTED",
              countsAsRecovered: true,
            },
          ],
        },
      ],
    },
  ],
};

test("rejects a statement response from another school or advance", () => {
  assert.equal(isScopedAdvanceStatement(statement, "school-one", "advance-one"), true);
  assert.equal(isScopedAdvanceStatement(statement, "school-two", "advance-one"), false);
  assert.equal(isScopedAdvanceStatement(statement, "school-one", "advance-two"), false);
});

test("renders keyboard-reachable table semantics and ledger-qualified recovery", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollAdvanceStatementEvidence, { statement }),
  );
  assert.match(html, /aria-labelledby="advance-statement-heading"/);
  assert.match(html, /tabindex="0" role="region"/);
  assert.match(html, /<caption>Complete approved installment schedule and recovery evidence/);
  assert.match(html, /<th scope="col">Installment/);
  assert.match(html, /<th scope="row">1/);
  assert.match(html, /PAY-202510-001/);
  assert.match(html, /MATCHED POSTED/i);
  assert.match(html, /A reservation is not repayment/);
});

test("evidence mismatch announces an alert and withholds misleading totals", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollAdvanceStatementEvidence, {
      statement: {
        ...statement,
        evidenceStatus: "EVIDENCE_MISMATCH",
        postedDisbursementAmount: null,
        recoveredAmount: null,
        outstandingAmount: null,
      },
    }),
  );
  assert.match(html, /role="alert"/);
  assert.match(html, /Aggregate recovered and outstanding amounts are withheld/);
  assert.match(html, /Withheld — verify evidence/);
});

test("workspace fetches private statements without caching and revokes download URLs", () => {
  const workspace = readFileSync(
    fileURLToPath(new URL("../components/workspace/payroll-advance-workspace.tsx", import.meta.url)),
    "utf8",
  );
  assert.match(workspace, /credentials:\s*"include"/);
  assert.match(workspace, /cache:\s*"no-store"/);
  assert.match(workspace, /isScopedAdvanceStatement\(result, schoolId, record\.id\)/);
  assert.match(workspace, /URL\.revokeObjectURL\(url\)/);
});
