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
  configurationPayload,
  createPostingAttempts,
  effectiveMapping,
  eligiblePaymentStaff,
  mappingDraft,
  openAccrualYears,
  postingAccounts,
  postingPayload,
} from "./payroll-accounting.ts";

const year = {
  id: "year",
  code: "TEST",
  displayName: "Synthetic year",
  startsOn: "2025-04-01",
  endsOn: "2026-03-31",
  accountingBasis: "ACCRUAL",
  currencyCode: "INR",
  status: "OPEN",
};
const accounts = [
  ["expense", "EXPENSE", "EXPENSE"],
  ["payable", "LIABILITY", "PAYABLE"],
  ["cash", "ASSET", "CASH"],
  ["bank", "ASSET", "BANK"],
].map(([id, accountType, category]) => ({
  id,
  accountType,
  category,
  code: id,
  displayName: "Synthetic " + id,
  status: "ACTIVE",
}));
const components = ["EARNING", "DEDUCTION", "EMPLOYER_CONTRIBUTION"].map((componentKind) => ({
  id: componentKind,
  componentKind,
  code: componentKind,
  displayName: "Synthetic " + componentKind,
  status: "ACTIVE",
}));
const config = {
  id: "config",
  financialYearId: "year",
  supersedesConfigurationId: null,
  revisionNumber: 1,
  effectiveFrom: "2025-04-01",
  accountingBasis: "ACCRUAL",
  currencyCode: "INR",
  salaryPayableAccountId: "payable",
  status: "APPROVED",
  creatorName: "Synthetic maker",
  createdAt: null,
  submitterName: null,
  submittedAt: null,
  deciderName: null,
  decidedAt: null,
  rowVersion: 2,
  canEdit: false,
  canSubmit: false,
  canDecide: false,
  componentMappings: components.map((component) => ({
    payrollComponentId: component.id,
    componentKind: component.componentKind,
    componentCode: component.code,
    componentName: component.displayName,
    expenseAccountId: component.componentKind === "DEDUCTION" ? null : "expense",
    payableAccountId: component.componentKind === "EARNING" ? null : "payable",
  })),
  tenderMappings: [
    { paymentMethod: "CASH", paymentAccountId: "cash" },
    { paymentMethod: "BANK_TRANSFER", paymentAccountId: "bank" },
  ],
};
const run = {
  id: "run",
  runCode: "SYNTHETIC-RUN",
  payrollMonth: "2025-08-01",
  periodEnd: "2025-08-31",
  currencyCode: "INR",
  status: "FINALIZED",
  staffCount: 3,
  payableGross: 1000,
  deductions: 100,
  employerContributions: 50,
  net: 900,
  accrualStatus: "POSTED",
  staff: [
    {
      id: "one",
      staffProfileId: "profile-one",
      staffName: "Synthetic staff one",
      employeeCode: "TEST-1",
      net: 600,
      paymentStatus: "UNPAID",
    },
    {
      id: "two",
      staffProfileId: "profile-two",
      staffName: "Synthetic staff two",
      employeeCode: "TEST-2",
      net: 300,
      paymentStatus: "UNPAID",
    },
    {
      id: "zero",
      staffProfileId: "profile-zero",
      staffName: "Synthetic zero-net staff",
      employeeCode: "TEST-0",
      net: 0,
      paymentStatus: "NOT_PAYABLE",
    },
  ],
};
const data = {
  schoolId: "school",
  schoolName: "Synthetic school",
  canConfigure: true,
  canPrepare: true,
  canApprove: false,
  accounts,
  components,
  financialYears: [year],
  configurations: [config],
  runs: [run],
  postingBatches: [],
};
const payment = {
  kind: "payments",
  runId: "run",
  financialYearId: "year",
  postingDate: "2025-09-01",
  paymentMethod: "CASH",
  paymentReference: "",
  staffIds: ["two", "one"],
  reason: "Synthetic payment evidence",
};
const today = "2025-09-02";

test("posting account options exclude inactive and wrong-type categories", () => {
  const extra = [
    ...accounts,
    { ...accounts[0], id: "inactive", status: "INACTIVE" },
    { ...accounts[0], id: "wrong-type", accountType: "ASSET" },
  ];
  assert.deepEqual(
    postingAccounts(extra, "EXPENSE").map((item) => item.id),
    ["expense"],
  );
  assert.equal(
    openAccrualYears({
      ...data,
      financialYears: [
        { ...year, accountingBasis: "CASH" },
        { ...year, status: "CLOSED" },
      ],
    }).length,
    0,
  );
});
test("mapping drafts preserve immutable revision identity and map active components only", () => {
  const next = mappingDraft(data, "year", config);
  assert.equal(next.revisionNumber, 2);
  assert.equal(next.effectiveFrom, "2025-04-02");
  assert.equal(next.supersedesConfigurationId, config.id);
  assert.equal(next.reason, "");
  const draft = mappingDraft(data, "year", { ...config, status: "DRAFT" }, true);
  assert.equal(draft.id, config.id);
  assert.equal(draft.expectedVersion, 2);
  assert.equal(draft.revisionNumber, 1);
  const payload = configurationPayload(data, { ...draft, reason: "Synthetic new reason" });
  assert.deepEqual(
    payload.componentMappings.map((item) => [item.expenseAccountId, item.payableAccountId]),
    [
      ["expense", null],
      [null, "payable"],
      ["expense", "payable"],
    ],
  );
  assert.equal(payload.schoolId, "school");
});
test("configuration rejects missing active components, wrong accounts and absent tender", () => {
  const draft = { ...mappingDraft(data, "year", config, true), reason: "Synthetic reason" };
  assert.throws(
    () => configurationPayload(data, { ...draft, components: {} }),
    /every active component/,
  );
  assert.throws(
    () => configurationPayload(data, { ...draft, salaryPayableAccountId: "cash" }),
    /salary-payable/,
  );
  assert.throws(
    () => configurationPayload(data, { ...draft, tenders: {} }),
    /at least one payment method/,
  );
  assert.throws(
    () => configurationPayload(data, { ...draft, tenders: { CASH: "bank" } }),
    /cash or bank/,
  );
  assert.throws(
    () => configurationPayload(data, { ...draft, effectiveFrom: "2026-04-01" }),
    /open accrual/,
  );
});
test("historical effective mapping uses superseded evidence until the next approved effective date", () => {
  const sample = {
    ...data,
    configurations: [
      { ...config, status: "SUPERSEDED" },
      { ...config, id: "later", revisionNumber: 2, effectiveFrom: "2025-10-01" },
      { ...config, id: "draft", status: "DRAFT", effectiveFrom: "2025-08-01" },
    ],
  };
  assert.equal(effectiveMapping(sample, "year", today).id, "config");
  assert.equal(effectiveMapping(sample, "year", "2025-10-01").id, "later");
  assert.equal(effectiveMapping(sample, "other-school-year", today), undefined);
});
test("salary requests use immutable staff result IDs, canonical order and no client amounts", () => {
  const payload = postingPayload(data, { ...payment, staffIds: ["two", "one", "two"] }, today);
  assert.deepEqual(payload.payrollRunStaffResultIds, ["one", "two"]);
  assert.equal(payload.paymentReference, null);
  assert.equal("net" in payload, false);
  assert.equal("amount" in payload, false);
  assert.equal("staffNames" in payload, false);
});
test("staff eligibility excludes paid, pending, zero-net and reversed runs", () => {
  assert.deepEqual(
    eligiblePaymentStaff(run).map((item) => item.id),
    ["one", "two"],
  );
  for (const status of ["PAID", "AWAITING_APPROVAL"])
    assert.equal(
      eligiblePaymentStaff({ ...run, staff: [{ ...run.staff[0], paymentStatus: status }] }).length,
      0,
    );
  assert.equal(eligiblePaymentStaff({ ...run, status: "REVERSED" }).length, 0);
  assert.equal(eligiblePaymentStaff({ ...run, accrualStatus: "AWAITING_APPROVAL" }).length, 0);
});
test("payment validation denies unknown staff, zero-net, empty selection, backdating, future dates and noncash without reference", () => {
  for (const staffIds of [[], ["unknown"], ["zero"]])
    assert.throws(() => postingPayload(data, { ...payment, staffIds }, today), /Select unpaid/);
  assert.throws(
    () => postingPayload(data, { ...payment, postingDate: "2025-08-30" }, today),
    /precede/,
  );
  assert.throws(
    () => postingPayload(data, { ...payment, postingDate: "2025-09-03" }, today),
    /non-future/,
  );
  assert.throws(
    () => postingPayload(data, { ...payment, paymentMethod: "BANK_TRANSFER" }, today),
    /reference/,
  );
  assert.throws(
    () =>
      postingPayload(
        data,
        { ...payment, paymentMethod: "SCHOOL_UPI", paymentReference: "synthetic" },
        today,
      ),
    /enabled/,
  );
  assert.throws(() => postingPayload({ ...data, configurations: [] }, payment, today), /approved/);
});
test("accrual is only available at period end without an active accrual", () => {
  const sample = { ...data, runs: [{ ...run, accrualStatus: "NOT_PREPARED" }] };
  const draft = { ...payment, kind: "accruals", postingDate: run.periodEnd };
  assert.equal(postingPayload(sample, draft, today).postingDate, run.periodEnd);
  assert.throws(
    () => postingPayload(sample, { ...draft, postingDate: "2025-09-01" }, today),
    /period-end/,
  );
  assert.throws(() => postingPayload(data, draft, today), /active accrual/);
  assert.throws(
    () => postingPayload({ ...sample, runs: [{ ...run, status: "REVERSED" }] }, draft, today),
    /finalized/,
  );
});
test("uncertain retries retain the original key even after editing and returning to the original payload", () => {
  let next = 0;
  const attempts = createPostingAttempts(() => "synthetic-key-" + ++next);
  const body = postingPayload(data, payment, today);
  const first = attempts.key("/runs/run/payments", body);
  assert.equal(
    attempts.key(
      "/runs/run/payments",
      postingPayload(data, { ...payment, staffIds: ["one", "two"] }, today),
    ),
    first,
  );
  assert.notEqual(attempts.key("/runs/run/payments", { ...body, reason: "Changed reason" }), first);
  assert.equal(attempts.key("/runs/run/payments", body), first);
  assert.notEqual(attempts.key("/runs/other/payments", body), first);
  attempts.confirmed("/runs/run/payments", body);
  assert.notEqual(attempts.key("/runs/run/payments", body), first);
});

// Render the actual TSX form with React, not a parallel mock UI. No fixtures are shipped as a route.
const formPath = fileURLToPath(
  new URL("../components/workspace/payroll-accounting-forms.tsx", import.meta.url),
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
  (id) => require(id.endsWith("/payroll-accounting") ? id + ".ts" : id),
  formModule,
  formModule.exports,
);
const { PayrollMappingForm, PayrollPostingForm } = formModule.exports;
const noChange = () => {};
test("actual mapping form exposes native labeled controls, required reason and no self-approval action", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollMappingForm, {
      data,
      draft: mappingDraft(data, "year", config),
      busy: false,
      onChange: noChange,
      onSubmit: noChange,
      onClose: noChange,
    }),
  );
  assert.match(html, /aria-labelledby="payroll-accounting-editor-title"/);
  assert.match(html, /<label>Net salary payable<select required/);
  assert.match(html, /<legend>All active payroll components/);
  assert.match(html, /<textarea required="" minLength="3" maxLength="500"/);
  assert.doesNotMatch(html, /<button[^>]*>Approve/);
});
test("actual payment form makes pending approval explicit and disables zero-net or busy controls", () => {
  const html = renderToStaticMarkup(
    React.createElement(PayrollPostingForm, {
      data,
      draft: payment,
      busy: true,
      onChange: noChange,
      onSubmit: noChange,
      onClose: noChange,
    }),
  );
  assert.match(html, /<fieldset disabled="" class="pa-form-body"/);
  assert.match(html, /aria-describedby="pa-posting-safety"/);
  assert.match(html, /does not transfer money/);
  assert.match(html, /<input type="checkbox" disabled=""\/>/);
  assert.match(html, /role="status"/);
});
test("new operational styling uses theme tokens, visible focus and narrow-screen layout", () => {
  const css = readFileSync(
    new URL("../app/workspace/payroll/payroll.css", import.meta.url),
    "utf8",
  );
  const section = css.slice(css.indexOf(".pa-workspace"), css.indexOf(".payroll-hero"));
  assert.match(section, /:focus-visible.*var\(--theme-text\)/);
  assert.match(section, /max-width:560px/);
  assert.match(section, /overflow:auto/);
  assert.doesNotMatch(section, /#[a-f0-9]{3,8}\b/i);
});
