/** Presentation/request validation only. The API owns approval, schedules and money movement. */
import type { Account, FinancialYear, PaymentMethod } from "./payroll-accounting";

export type AdvanceStaff = {
  id: string;
  employeeCode: string;
  displayName: string;
  joinedOn: string;
  endedOn: string | null;
  status: string;
  designation: string | null;
  department: string | null;
};
export type Advance = {
  id: string;
  staffProfileId: string;
  advanceNumber: string;
  advanceType: "ADVANCE" | "LOAN";
  purpose: string;
  financialYearId: string;
  accountingPeriodId: string;
  issueDate: string;
  currencyCode: string;
  principalAmount: number;
  recoveryStartMonth: string;
  installmentCount: number;
  receivableAccountId: string;
  disbursementAccountId: string;
  paymentMethod: PaymentMethod;
  paymentReference: string | null;
  employeeCode: string;
  staffName: string;
  status: string;
  creatorName: string;
  createdAt: string;
  submitterName: string | null;
  submittedAt: string | null;
  deciderName: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
  cancellerName: string | null;
  cancelledAt: string | null;
  cancellationReason: string | null;
  postedDisbursementAmount: number | null;
  recoveredAmount: number;
  outstandingAmount: number | null;
  recoveryStatus: string;
  operationalStatus: string;
  rowVersion: number;
  canEdit: boolean;
  canSubmit: boolean;
  canCancel: boolean;
  canDecide: boolean;
  canPrepareDisbursement: boolean;
  installments: {
    id: string;
    installmentNumber: number;
    dueMonth: string;
    plannedAmount: number;
  }[];
  history: {
    id: string;
    eventType: string;
    previousStatus: string | null;
    newStatus: string;
    reason: string;
    actorName: string;
    occurredAt: string;
  }[];
  postings: {
    id: string;
    sequenceNumber: number;
    postingDate: string;
    paymentMethod: string;
    paymentReference: string | null;
    sourceAmount: number;
    accountingJournalId: string | null;
    journalStatus: string | null;
    operationalStatus: string;
    preparerName: string;
    preparedAt: string;
    rowVersion: number;
  }[];
};
export type AdvanceData = {
  schoolId: string;
  schoolName: string;
  canPrepare: boolean;
  canApprove: boolean;
  page: number;
  size: number;
  total: number;
  staff: AdvanceStaff[];
  financialYears: FinancialYear[];
  accounts: Account[];
  advances: Advance[];
};
export type AdvanceDraft = {
  id?: string;
  expectedVersion?: number;
  staffProfileId: string;
  advanceType: "ADVANCE" | "LOAN";
  purpose: string;
  financialYearId: string;
  issueDate: string;
  principalAmount: string;
  recoveryStartMonth: string;
  installmentCount: string;
  receivableAccountId: string;
  disbursementAccountId: string;
  paymentMethod: PaymentMethod;
  paymentReference: string;
  reason: string;
};
export type AdvanceAction = "submit" | "cancel" | "APPROVE" | "REJECT" | "disbursements";
export const advanceAccounts = (accounts: Account[], category: "RECEIVABLE" | "CASH" | "BANK") =>
  accounts.filter(
    (a) => a.accountType === "ASSET" && a.category === category && a.status === "ACTIVE",
  );
export const advanceYears = (data: AdvanceData) =>
  data.financialYears.filter((y) => y.status === "OPEN" && y.accountingBasis === "ACCRUAL");
const validDate = (value: string) => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(value + "T00:00:00Z");
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
};
function requiredText(value: string, name: string) {
  const clean = value.trim();
  if (clean.length < 3 || clean.length > 500 || /[\u0000-\u001f\u007f]/.test(clean))
    throw new Error("Enter a " + name + " of 3–500 characters without control characters.");
  return clean;
}
export function advanceDraft(data: AdvanceData, today: string, advance?: Advance): AdvanceDraft {
  if (advance)
    return {
      id: advance.id,
      expectedVersion: advance.rowVersion,
      staffProfileId: advance.staffProfileId,
      advanceType: advance.advanceType,
      purpose: advance.purpose,
      financialYearId: advance.financialYearId,
      issueDate: advance.issueDate,
      principalAmount: String(advance.principalAmount),
      recoveryStartMonth: advance.recoveryStartMonth,
      installmentCount: String(advance.installmentCount),
      receivableAccountId: advance.receivableAccountId,
      disbursementAccountId: advance.disbursementAccountId,
      paymentMethod: advance.paymentMethod,
      paymentReference: advance.paymentReference ?? "",
      reason: "",
    };
  return {
    staffProfileId: "",
    advanceType: "ADVANCE",
    purpose: "",
    financialYearId: advanceYears(data)[0]?.id ?? "",
    issueDate: today,
    principalAmount: "",
    recoveryStartMonth: today.slice(0, 7) + "-01",
    installmentCount: "1",
    receivableAccountId: "",
    disbursementAccountId: "",
    paymentMethod: "CASH",
    paymentReference: "",
    reason: "",
  };
}
export function advancePayload(data: AdvanceData, draft: AdvanceDraft, today: string) {
  if (!data.canPrepare) throw new Error("Advance preparation is not permitted.");
  if (draft.id) {
    const existing = data.advances.find((a) => a.id === draft.id);
    if (
      !existing?.canEdit ||
      existing.status !== "DRAFT" ||
      existing.rowVersion !== draft.expectedVersion
    )
      throw new Error("This draft changed or is no longer editable. Refresh before continuing.");
  }
  const staff = data.staff.find((s) => s.id === draft.staffProfileId);
  const year = advanceYears(data).find((y) => y.id === draft.financialYearId);
  if (!validDate(draft.issueDate) || draft.issueDate > today)
    throw new Error("Choose a valid issue date that is not in the future.");
  if (
    !staff ||
    staff.joinedOn > draft.issueDate ||
    (staff.endedOn && staff.endedOn < draft.issueDate)
  )
    throw new Error("Choose a school employee employed on the issue date.");
  if (!year || draft.issueDate < year.startsOn || draft.issueDate > year.endsOn)
    throw new Error("Choose an issue date within an open accrual financial year.");
  if (
    !validDate(draft.recoveryStartMonth) ||
    !draft.recoveryStartMonth.endsWith("-01") ||
    draft.recoveryStartMonth < draft.issueDate.slice(0, 7) + "-01"
  )
    throw new Error("Recovery must start on the first day of the issue month or a later month.");
  if (!["ADVANCE", "LOAN"].includes(draft.advanceType)) throw new Error("Choose advance or loan.");
  if (!/^(0|[1-9]\d{0,12})(\.\d{1,2})?$/.test(draft.principalAmount))
    throw new Error("Enter a positive principal with at most two decimal places.");
  const [whole = "", fraction = ""] = draft.principalAmount.split(".");
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  const count = Number(draft.installmentCount);
  if (!/^\d{1,3}$/.test(draft.installmentCount) || count < 1 || count > 120)
    throw new Error("Choose 1–120 monthly installments.");
  if (cents < BigInt(count) || cents > 100000000000000n)
    throw new Error(
      "Principal must support positive installments and cannot exceed 1,000,000,000,000.",
    );
  if (!["CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI"].includes(draft.paymentMethod))
    throw new Error("Choose a supported payment method.");
  if (
    !advanceAccounts(data.accounts, "RECEIVABLE").some((a) => a.id === draft.receivableAccountId) ||
    !advanceAccounts(data.accounts, draft.paymentMethod === "CASH" ? "CASH" : "BANK").some(
      (a) => a.id === draft.disbursementAccountId,
    )
  )
    throw new Error("Choose an active receivable and a matching cash or bank posting account.");
  const reference = draft.paymentReference.trim();
  if (
    (draft.paymentMethod !== "CASH" && reference.length < 2) ||
    (reference && reference.length < 2) ||
    reference.length > 120 ||
    /[\u0000-\u001f\u007f]/.test(reference)
  )
    throw new Error("Use a reference of 2–120 characters; it is required for non-cash payments.");
  return {
    schoolId: data.schoolId,
    staffProfileId: staff.id,
    advanceType: draft.advanceType,
    purpose: requiredText(draft.purpose, "purpose"),
    financialYearId: year.id,
    issueDate: draft.issueDate,
    principalAmount: Number(draft.principalAmount),
    recoveryStartMonth: draft.recoveryStartMonth,
    installmentCount: count,
    receivableAccountId: draft.receivableAccountId,
    disbursementAccountId: draft.disbursementAccountId,
    paymentMethod: draft.paymentMethod,
    paymentReference: reference || null,
    reason: requiredText(draft.reason, "reason"),
  };
}
export function advanceTransition(
  data: AdvanceData,
  id: string,
  action: AdvanceAction,
  reason: string,
) {
  const advance = data.advances.find((a) => a.id === id);
  const permitted =
    advance &&
    {
      submit: advance.canSubmit,
      cancel: advance.canCancel,
      APPROVE: advance.canDecide,
      REJECT: advance.canDecide,
      disbursements: advance.canPrepareDisbursement,
    }[action];
  if (!advance || !permitted)
    throw new Error("This action is no longer permitted. Refresh the advance.");
  const body = { schoolId: data.schoolId, reason: requiredText(reason, "reason") };
  if (action === "disbursements")
    return { path: "/" + id + "/disbursements", body, retrySafe: true };
  return {
    path: "/" + id + (action === "APPROVE" || action === "REJECT" ? "/decision" : "/" + action),
    body: {
      ...body,
      expectedVersion: advance.rowVersion,
      ...(action === "APPROVE" || action === "REJECT" ? { decision: action } : {}),
    },
    retrySafe: false,
  };
}
