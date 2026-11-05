import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import {
  defaultReportFilters,
  reportQuery,
  reportResponse,
  readReport,
  reportStatusTone,
} from "./payroll-reports.ts";

const filters = {
  kind: "register",
  from: "2025-08",
  to: "2025-09",
  dueThrough: "2025-09-30",
  search: "",
};
const jsonResponse = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
const registerRow = {
  id: "synthetic-result",
  payrollRunId: "synthetic-run",
  runCode: "SYNTHETIC-1",
  payrollMonth: "2025-08-01",
  runStatus: "FINALIZED",
  staffProfileId: "synthetic-staff",
  employeeCode: "TEST-1",
  staffName: "Synthetic employee",
  currencyCode: "INR",
  gross: 100,
  deductions: 10,
  employerContributions: 5,
  net: 90,
  accrualStatus: "POSTED",
  paymentStatus: "AWAITING_APPROVAL",
  paid: 0,
  pending: 90,
  unsettled: 90,
  accrualJournalId: "synthetic-accrual",
  paymentJournalId: "synthetic-payment",
};
const summaryRow = {
  groupBy: "DEPARTMENT",
  dimensionStatus: "NOT_CAPTURED",
  dimensionCode: null,
  dimensionLabel: "Historical classification not captured",
  runStatus: "FINALIZED",
  currencyCode: "INR",
  payrollRunCount: 1,
  staffResultCount: 1,
  gross: 100,
  deductions: 10,
  employerContributions: 5,
  net: 90,
};
const data = {
  schoolId: "school",
  schoolName: "Synthetic school",
  generatedAt: "2025-09-01T08:00:00Z",
  from: "2025-08-01",
  to: "2025-09-01",
  page: 0,
  size: 25,
  total: 1,
  items: [registerRow],
};
test("report defaults and monthly filters use inclusive month starts without timezone arithmetic", () => {
  assert.deepEqual(defaultReportFilters("2025-09-30"), { ...filters, from: "2025-01" });
  assert.equal(defaultReportFilters("2025-09-30", "reconciliation").to, "2025-09-30");
  const url = new URL(reportQuery("school", filters, 2), "https://example.test");
  assert.equal(url.searchParams.get("from"), "2025-08-01");
  assert.equal(url.searchParams.get("to"), "2025-09-01");
  assert.equal(url.searchParams.get("page"), "2");
  assert.equal(url.searchParams.get("size"), "25");
  const summary = new URL(
    reportQuery("school", { ...filters, kind: "department" }),
    "https://example.test",
  );
  assert.equal(summary.pathname, "/api/v1/payroll/accounting/reports/summary");
  assert.equal(summary.searchParams.get("groupBy"), "DEPARTMENT");
  assert.equal(summary.searchParams.get("from"), "2025-08-01");
  assert.equal(defaultReportFilters("2025-09-30", "deductionComponent").to, "2025-09");
  const advances = new URL(
    reportQuery("school", { ...filters, kind: "advanceReconciliation" }),
    "https://example.test",
  );
  assert.equal(advances.pathname, "/api/v1/payroll/accounting/reports/advance-reconciliation");
  assert.equal(advances.searchParams.get("dueThrough"), "2025-09-30");
  assert.equal(advances.searchParams.has("from"), false);
});
test("invalid dates, overlong ranges, control characters and pagination are rejected", () => {
  for (const from of ["", "2025-13", "0000-01", "2025-02-01"])
    assert.throws(() => reportQuery("school", { ...filters, from }), /valid, ordered/);
  assert.throws(() => reportQuery("school", { ...filters, to: "2025-07" }), /ordered/);
  assert.throws(() => reportQuery("school", { ...filters, to: "2036-08" }), /ten years/);
  assert.throws(
    () =>
      reportQuery("school", {
        kind: "reconciliation",
        from: "2025-02-30",
        to: "2025-03-01",
        search: "",
      }),
    /valid/,
  );
  assert.throws(() => reportQuery("school", { ...filters, search: "a\nb" }), /printable/);
  assert.throws(() => reportQuery("school", { ...filters, search: "x".repeat(101) }), /100/);
  assert.throws(() => reportQuery("school", {
    ...filters,
    kind: "advanceReconciliation",
    dueThrough: "2025-02-30",
  }), /due-through/);
  for (const page of [-1, 0.5, 10001])
    assert.throws(() => reportQuery("school", filters, page), /page/);
  assert.throws(() => reportQuery("", filters), /school/);
});
test("query encoding prevents employee input from replacing the school or report path; exports omit pagination", () => {
  const url = new URL(
    reportQuery("school", { ...filters, search: " परीक्षण &schoolId=foreign " }, 0, true),
    "https://example.test",
  );
  assert.equal(url.pathname, "/api/v1/payroll/accounting/reports/register.csv");
  assert.equal(url.searchParams.get("schoolId"), "school");
  assert.equal(url.searchParams.get("search"), "परीक्षण &schoolId=foreign");
  assert.equal(url.searchParams.has("page"), false);
  assert.equal(url.searchParams.has("size"), false);
});
test("actual report request uses the existing session, no cache, no redirect and a cancellable signal", async () => {
  const controller = new AbortController();
  let observed;
  await reportResponse(
    async (url, options) => {
      observed = { url, options };
      return jsonResponse(data);
    },
    "https://example.test",
    "school",
    filters,
    0,
    false,
    controller.signal,
  );
  assert.match(observed.url, /schoolId=school/);
  assert.equal(observed.options.credentials, "include");
  assert.equal(observed.options.cache, "no-store");
  assert.equal(observed.options.redirect, "error");
  assert.equal(observed.options.signal, controller.signal);
  assert.equal(observed.options.headers.Accept, "application/json");
});
test("session loss and denied scope never return report data or private CSV", async () => {
  for (const csv of [false, true]) {
    for (const status of [401, 403])
      await assert.rejects(
        reportResponse(
          async () => jsonResponse({ items: [registerRow] }, status),
          "",
          "school",
          filters,
          0,
          csv,
          new AbortController().signal,
        ),
        status === 401 ? /session has ended/ : /both required/,
      );
  }
});
test("unexpected content cannot be mistaken for a salary report or downloaded as CSV", async () => {
  for (const csv of [false, true])
    await assert.rejects(
      reportResponse(
        async () =>
          new Response("<html>Login</html>", { headers: { "content-type": "text/html" } }),
        "",
        "school",
        filters,
        0,
        csv,
        new AbortController().signal,
      ),
      /unexpected report/,
    );
  await assert.rejects(
    reportResponse(
      async () => jsonResponse({ detail: "Narrow the filters; exceeds 5000 rows." }, 400),
      "",
      "school",
      filters,
      0,
      true,
      new AbortController().signal,
    ),
    /5000/,
  );
});
test("server-authorized UTF-8 CSV is accepted without converting its protected content", async () => {
  const response = await reportResponse(
    async () =>
      new Response('\uFEFF"Synthetic"\r\n', {
        headers: { "content-type": "text/csv;charset=UTF-8" },
      }),
    "",
    "school",
    filters,
    0,
    true,
    new AbortController().signal,
  );
  assert.equal((await response.blob()).type, "text/csv;charset=utf-8");
});
test("aborted report requests propagate cancellation without a fallback dataset", async () => {
  const controller = new AbortController();
  const pending = reportResponse(
    async (_url, options) =>
      new Promise((_resolve, reject) => {
        options.signal.addEventListener("abort", () =>
          reject(new DOMException("Aborted", "AbortError")),
        );
      }),
    "",
    "school",
    filters,
    0,
    false,
    controller.signal,
  );
  controller.abort();
  await assert.rejects(pending, { name: "AbortError" });
});
test("report response validates school scope and pagination before presentation", async () => {
  assert.equal(
    (await readReport(jsonResponse(data), "register", "school", 0)).data.items[0].net,
    90,
  );
  for (const change of [
    { schoolId: "foreign" },
    { page: 1 },
    { size: 100 },
    { total: -1 },
    { items: null },
  ])
    await assert.rejects(
      readReport(jsonResponse({ ...data, ...change }), "register", "school", 0),
      /scope or pagination/,
    );
  const summary = await readReport(
    jsonResponse({ ...data, items: [summaryRow] }),
    "department",
    "school",
    0,
  );
  assert.equal(summary.kind, "department");
  assert.equal(summary.data.items[0].dimensionStatus, "NOT_CAPTURED");
  const advancePage = {
    schoolId: "school",
    schoolName: "Synthetic school",
    generatedAt: "2025-09-30T08:00:00Z",
    dueThrough: "2025-09-30",
    page: 0,
    size: 25,
    total: 0,
    items: [],
  };
  assert.equal((await readReport(jsonResponse(advancePage), "advanceReconciliation",
    "school", 0, "2025-09-30")).data.dueThrough, "2025-09-30");
  await assert.rejects(readReport(jsonResponse(advancePage), "advanceReconciliation",
    "school", 0, "2025-10-01"), /due-through/);
});

const viewPath = fileURLToPath(
  new URL("../components/workspace/payroll-report-views.tsx", import.meta.url),
);
const require = createRequire(viewPath);
const compiled = ts.transpileModule(readFileSync(viewPath, "utf8"), {
  compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    jsx: ts.JsxEmit.ReactJSX,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText;
const viewModule = { exports: {} };
new vm.Script("(function(require,module,exports){" + compiled + "\n})", {
  filename: viewPath,
}).runInThisContext()(
  (id) => require(id.includes("/payroll-") ? id + ".ts" : id),
  viewModule,
  viewModule.exports,
);
const { PayrollReportFilters, PayrollReportTable } = viewModule.exports;

test("actual report form has native labeled controls and disables inputs during requests", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollReportFilters, {
      filters,
      busy: true,
      onChange() {},
      onSubmit() {},
    }),
  );
  assert.match(html, /aria-labelledby="payroll-report-filters"/);
  assert.match(html, /<fieldset class="pa-form-body" disabled=""/);
  assert.match(html, /<label>From payroll month<input type="month" required=""/);
  assert.match(html, /<label>Employee name or code<input type="search" maxLength="100"/);
  assert.match(html, /<button type="submit">Load report/);
  assert.match(html, /Payroll summary by department/);
  assert.match(html, /Advance recovery reconciliation/);
  const advanceHtml = renderToStaticMarkup(
    React.createElement(PayrollReportFilters, {
      filters: { ...filters, kind: "advanceReconciliation" },
      busy: false,
      onChange() {},
      onSubmit() {},
    }),
  );
  assert.match(advanceHtml, /Installments due through<input type="date" required="" value="2025-09-30"/);
  assert.match(advanceHtml, /Ledger and reversal evidence is current when loaded/);
  assert.doesNotMatch(advanceHtml, /From payroll month/);
});
test("actual salary table preserves pending versus paid semantics and renders unknown amounts honestly", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollReportTable, {
      report: {
        kind: "register",
        data: { ...data, items: [{ ...registerRow, paid: null, paymentStatus: "UNVERIFIED" }] },
      },
    }),
  );
  assert.match(html, /role="region".*tabindex="0"/);
  assert.match(html, /<th scope="row">/);
  assert.match(html, /Not verified/);
  assert.match(html, /Unverified/);
  assert.match(html, /Unsettled \(includes pending\)/);
  assert.match(html, /<summary>View record references/);
  assert.equal(reportStatusTone("AWAITING_APPROVAL"), "attention");
});
test("actual reconciliation table exposes evidence gaps without a success-only status", () => {
  const row = {
    id: "batch",
    payrollRunId: "run",
    runCode: "SYNTHETIC",
    postingType: "PAYMENT",
    postingDate: "2025-09-01",
    currencyCode: "INR",
    sourceCount: 1,
    sourceAmount: 90,
    actualSourceCount: 1,
    actualSourceAmount: 90,
    frozenPayrollAmount: 90,
    journalId: null,
    journalStatus: "UNVERIFIED",
    journalDebit: null,
    journalCredit: null,
    reversalJournalId: null,
    reversalStatus: null,
    reconciliationStatus: "MISMATCH",
    issues: ["JOURNAL_UNAVAILABLE"],
  };
  const html = renderToStaticMarkup(
    React.createElement(PayrollReportTable, {
      report: { kind: "reconciliation", data: { ...data, items: [row] } },
    }),
  );
  assert.match(html, /aria-label="Reconciliation issues"/);
  assert.match(html, /Journal Unavailable/);
  assert.match(html, /Not verified/);
  assert.doesNotMatch(html, /No discrepancy detected/);
});
test("advance reconciliation table exposes overdue and fail-closed ledger evidence", () => {
  const row = {
    id: "advance",
    advanceNumber: "EPA-202509-001",
    advanceType: "ADVANCE",
    staffProfileId: "staff",
    employeeCode: "EMP-001",
    staffName: "Synthetic employee",
    issueDate: "2025-09-01",
    recoveryStartMonth: "2025-09-01",
    currencyCode: "INR",
    principalAmount: 200,
    advanceStatus: "APPROVED",
    declaredInstallmentCount: 2,
    actualInstallmentCount: 2,
    scheduledAmount: 200,
    scheduledDueAmount: 100,
    disbursementStatus: "EVIDENCE_MISMATCH",
    postedDisbursementAmount: null,
    recoveredAmount: null,
    outstandingAmount: null,
    overdueAmount: null,
    nextUnrecoveredMonth: "2025-09-01",
    pendingRecoveryCount: 0,
    pendingRecoveryAmount: 0,
    reconciliationStatus: "EVIDENCE_MISMATCH",
    issues: ["DISBURSEMENT_PROVENANCE_MISMATCH"],
  };
  const html = renderToStaticMarkup(
    React.createElement(PayrollReportTable, {
      report: {
        kind: "advanceReconciliation",
        data: {
          schoolId: "school",
          schoolName: "Synthetic school",
          generatedAt: "2025-09-30T08:00:00Z",
          dueThrough: "2025-09-30",
          page: 0,
          size: 25,
          total: 1,
          items: [row],
        },
      },
    }),
  );
  assert.match(html, /Advance recovery reconciliation table/);
  assert.match(html, /does not reconstruct a historical ledger snapshot/);
  assert.match(html, /aria-label="Advance reconciliation issues"/);
  assert.match(html, /Disbursement Provenance Mismatch/);
  assert.match(html, /Not verified/);
  assert.match(html, /Use the Advances tab to open the complete installment statement/);
});
test("actual summary table preserves evidence buckets and does not invent component allocations", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollReportTable, {
      report: {
        kind: "deductionComponent",
        data: {
          ...data,
          items: [
            {
              ...summaryRow,
              groupBy: "DEDUCTION_COMPONENT",
              dimensionStatus: "CAPTURED",
              dimensionCode: "SYNTHETIC_DEDUCTION",
              dimensionLabel: "Synthetic deduction",
              gross: null,
              employerContributions: null,
              net: null,
            },
          ],
        },
      },
    }),
  );
  assert.match(html, /Historical payroll summary table/);
  assert.match(html, /Synthetic deduction/);
  assert.match(html, /SYNTHETIC_DEDUCTION/);
  assert.match(html, /Not applicable/);
  assert.doesNotMatch(html, /Not verified/);
  assert.equal(reportStatusTone("CAPTURED"), "ok");
  assert.equal(reportStatusTone("AMBIGUOUS"), "attention");
});
test("empty reports do not invent rows; new styles reuse themes, mobile layout and visible focus", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollReportTable, {
      report: { kind: "register", data: { ...data, total: 0, items: [] } },
    }),
  );
  assert.match(html, /No records match/);
  assert.doesNotMatch(html, /Synthetic employee/);
  const css = readFileSync(
    new URL("../app/workspace/payroll/payroll.css", import.meta.url),
    "utf8",
  );
  assert.match(css, /\.payroll-page \.pr-workspace :focus-visible.*var\(--theme-text\)/);
  assert.match(css, /@media \(max-width:560px\).*\.pa-fields.*grid-template-columns:1fr/);
  assert.match(css, /\.pa-table-wrap \{ overflow:auto/);
  assert.match(css, /prefers-reduced-motion:reduce/);
});
