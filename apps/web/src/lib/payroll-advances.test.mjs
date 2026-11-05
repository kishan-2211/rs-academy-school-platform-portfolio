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
  advanceAccounts,
  advanceDraft,
  advancePayload,
  advanceTransition,
} from "./payroll-advances.ts";
import { createPostingAttempts } from "./payroll-accounting.ts";

const today = "2026-09-05";
const staff = {
  id: "staff",
  employeeCode: "SYNTHETIC-EMP",
  displayName: "Synthetic employee",
  joinedOn: "2025-04-01",
  endedOn: null,
  status: "ACTIVE",
  designation: null,
  department: null,
};
const year = {
  id: "year",
  code: "SYNTHETIC",
  displayName: "Synthetic accrual year",
  startsOn: "2026-04-01",
  endsOn: "2027-03-31",
  status: "OPEN",
  accountingBasis: "ACCRUAL",
  currencyCode: "INR",
};
const accounts = ["RECEIVABLE", "CASH", "BANK"].map((category) => ({
  id: category,
  code: "SYN-" + category,
  displayName: "Synthetic " + category,
  accountType: "ASSET",
  category,
  status: "ACTIVE",
}));
const data = {
  schoolId: "school",
  schoolName: "Synthetic school",
  canPrepare: true,
  canApprove: true,
  page: 0,
  size: 25,
  total: 0,
  staff: [staff],
  financialYears: [year],
  accounts,
  advances: [],
};
const draft = {
  ...advanceDraft(data, today),
  staffProfileId: staff.id,
  principalAmount: "100.01",
  installmentCount: "3",
  purpose: "Synthetic purpose",
  reason: "Synthetic preparation reason",
  receivableAccountId: "RECEIVABLE",
  disbursementAccountId: "CASH",
};
const record = {
  ...advancePayload(data, draft, today),
  id: "advance",
  advanceNumber: "EPA-SYNTHETIC",
  accountingPeriodId: "period",
  currencyCode: "INR",
  employeeCode: staff.employeeCode,
  staffName: staff.displayName,
  status: "DRAFT",
  creatorName: "Synthetic maker",
  createdAt: "2026-09-05T06:00:00Z",
  submittedAt: null,
  submitterName: null,
  decidedAt: null,
  deciderName: null,
  decisionReason: null,
  cancelledAt: null,
  cancellerName: null,
  cancellationReason: null,
  postedDisbursementAmount: null,
  recoveredAmount: 0,
  outstandingAmount: null,
  operationalStatus: "UNVERIFIED",
  recoveryStatus: "NOT_DISBURSED",
  rowVersion: 2,
  canEdit: true,
  canSubmit: true,
  canCancel: true,
  canDecide: false,
  canPrepareDisbursement: false,
  installments: [
    { id: "installment", installmentNumber: 1, dueMonth: "2026-09-01", plannedAmount: 33.33 },
  ],
  history: [],
  postings: [],
};
test("advance drafts use scoped options and never manufacture recovered/outstanding values", () => {
  const payload = advancePayload(data, draft, today);
  assert.equal(payload.schoolId, "school");
  assert.equal(payload.principalAmount, 100.01);
  assert.equal(payload.installmentCount, 3);
  assert.equal(payload.paymentReference, null);
  assert.equal("recoveredAmount" in payload, false);
  assert.equal("outstandingAmount" in payload, false);
  assert.equal("installments" in payload, false);
  assert.equal(advanceDraft(data, today, record).expectedVersion, 2);
  assert.equal(advanceDraft(data, today, record).reason, "");
  assert.deepEqual(
    advanceAccounts(
      [
        ...accounts,
        { ...accounts[0], id: "inactive", status: "INACTIVE" },
        { ...accounts[0], id: "wrong-type", accountType: "LIABILITY" },
      ],
      "RECEIVABLE",
    ).map((a) => a.id),
    ["RECEIVABLE"],
  );
});
test("principal/count validation rejects subcents, scientific notation, bounds and zero installments", () => {
  for (const principalAmount of [
    "0",
    "-1",
    "1.001",
    "1e3",
    "NaN",
    "Infinity",
    "1000000000000.01",
    " 1",
    "01",
  ]) {
    assert.throws(() => advancePayload(data, { ...draft, principalAmount }, today));
  }
  for (const installmentCount of ["0", "121", "1.5", "-1", "3x", ""]) {
    assert.throws(() => advancePayload(data, { ...draft, installmentCount }, today));
  }
  assert.throws(
    () => advancePayload(data, { ...draft, principalAmount: "0.02" }, today),
    /positive installments/,
  );
  assert.equal(
    advancePayload(data, { ...draft, principalAmount: "1000000000000.00" }, today).principalAmount,
    1e12,
  );
});
test("preparation rejects missing permissions, foreign references and unsuitable employment/book dates", () => {
  assert.throws(
    () => advancePayload({ ...data, canPrepare: false }, draft, today),
    /not permitted/,
  );
  for (const change of [
    { staffProfileId: "foreign" },
    { issueDate: "2026-09-06" },
    { issueDate: "2026-02-30" },
    { financialYearId: "foreign" },
    { receivableAccountId: "BANK" },
    { disbursementAccountId: "BANK" },
    { recoveryStartMonth: "2026-08-01" },
    { recoveryStartMonth: "2026-09-02" },
  ]) {
    assert.throws(() => advancePayload(data, { ...draft, ...change }, today));
  }
  assert.throws(
    () => advancePayload({ ...data, staff: [{ ...staff, endedOn: "2026-09-04" }] }, draft, today),
    /employed/,
  );
  assert.throws(
    () =>
      advancePayload({ ...data, financialYears: [{ ...year, status: "CLOSED" }] }, draft, today),
    /open accrual/,
  );
});
test("bank methods require a valid reference and edits cannot overwrite a changed or submitted record", () => {
  const bank = { ...draft, paymentMethod: "BANK_TRANSFER", disbursementAccountId: "BANK" };
  assert.throws(() => advancePayload(data, bank, today), /reference/);
  assert.throws(() => advancePayload(data, { ...bank, paymentReference: "x" }, today), /reference/);
  assert.equal(
    advancePayload(data, { ...bank, paymentReference: "SYN-REFERENCE" }, today).paymentReference,
    "SYN-REFERENCE",
  );
  const edit = { ...draft, id: record.id, expectedVersion: record.rowVersion };
  assert.doesNotThrow(() => advancePayload({ ...data, advances: [record] }, edit, today));
  assert.throws(
    () => advancePayload({ ...data, advances: [record] }, { ...edit, expectedVersion: 1 }, today),
    /changed/,
  );
  assert.throws(
    () => advancePayload({ ...data, advances: [{ ...record, status: "SUBMITTED" }] }, edit, today),
    /editable/,
  );
});
test("workflow payloads preserve server versions and disbursement sends no client-selected money", () => {
  const scoped = { ...data, advances: [record] };
  assert.equal(
    advanceTransition(scoped, record.id, "submit", "Synthetic submission").body.expectedVersion,
    2,
  );
  assert.throws(
    () => advanceTransition(scoped, record.id, "APPROVE", "Synthetic denied decision"),
    /no longer permitted/,
  );
  assert.throws(
    () => advanceTransition(scoped, "foreign", "submit", "Synthetic denied scope"),
    /no longer permitted/,
  );
  assert.throws(() => advanceTransition(scoped, record.id, "submit", "x"), /reason/);
  const approved = { ...data, advances: [{ ...record, canPrepareDisbursement: true }] };
  const action = advanceTransition(approved, record.id, "disbursements", "Synthetic disbursement");
  assert.equal(action.retrySafe, true);
  assert.deepEqual(action.body, { schoolId: "school", reason: "Synthetic disbursement" });
  assert.equal(action.path, "/advance/disbursements");
});
test("uncertain advance and disbursement retries keep distinct stable in-memory keys", () => {
  let sequence = 0;
  const attempts = createPostingAttempts(() => "synthetic-key-" + ++sequence);
  const body = advancePayload(data, draft, today);
  const key = attempts.key("", body);
  assert.equal(attempts.key("", advancePayload(data, { ...draft }, today)), key);
  assert.notEqual(attempts.key("", { ...body, purpose: "Changed synthetic purpose" }), key);
  assert.equal(attempts.key("", body), key);
  assert.notEqual(attempts.key("/advance/disbursements", body), key);
  attempts.confirmed("", body);
  assert.notEqual(attempts.key("", body), key);
});

const formPath = fileURLToPath(
  new URL("../components/workspace/payroll-advance-views.tsx", import.meta.url),
);
const require = createRequire(formPath);
const compiled = ts.transpileModule(readFileSync(formPath, "utf8"), {
  compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    jsx: ts.JsxEmit.ReactJSX,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText;
const formModule = { exports: {} };
new vm.Script("(function(require,module,exports){" + compiled + "\n})", {
  filename: formPath,
}).runInThisContext()(
  (id) => require(/\/payroll-(advances|accounting)$/.test(id) ? id + ".ts" : id),
  formModule,
  formModule.exports,
);
const { PayrollAdvanceForm, PayrollAdvanceRecords } = formModule.exports;
const noChange = () => {};
test("actual advance form exposes labeled native controls, disabled busy state and safety guidance", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollAdvanceForm, {
      data,
      draft,
      busy: true,
      today,
      onChange: noChange,
      onSave: noChange,
      onCancel: noChange,
    }),
  );
  assert.match(html, /aria-labelledby="advance-form-heading"/);
  assert.match(html, /<fieldset disabled="" class="pa-form-body"/);
  assert.match(html, /<label>Employee<select required/);
  assert.match(html, /inputMode="decimal"/);
  assert.match(html, /<textarea required="" minLength="3" maxLength="500"/);
  assert.match(html, /A plan is not proof of repayment/);
  assert.doesNotMatch(html, /<button[^>]*>Approve/);
});
test("actual evidence view distinguishes unknown money and hides non-permitted decisions", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollAdvanceRecords, {
      data: { ...data, total: 1, advances: [record] },
      busy: false,
      onEdit: noChange,
      onAction: noChange,
    }),
  );
  assert.match(html, /Ledger-confirmed disbursement<\/dt><dd>Unverified/);
  assert.match(html, /Approved installment plan — repayment requires a posted payroll accrual/);
  assert.match(html, /Ledger-confirmed recovered/);
  assert.match(html, /Starts after posted disbursement/);
  assert.match(html, /tabindex="0" role="region"/);
  assert.match(html, /scope="col"/);
  assert.match(html, /Edit draft/);
  assert.doesNotMatch(html, /Approve advance|Prepare disbursement/);
  const empty = renderToStaticMarkup(
    React.createElement(PayrollAdvanceRecords, {
      data,
      busy: false,
      onEdit: noChange,
      onAction: noChange,
    }),
  );
  assert.match(empty, /No advances match these filters/);
  assert.doesNotMatch(empty, /EPA-SYNTHETIC/);
});
