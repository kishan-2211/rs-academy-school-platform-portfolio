/** Read-only report contracts. Salary calculations and authorization remain on the API. */
export type ReportKind =
  | "register"
  | "reconciliation"
  | "advanceReconciliation"
  | "department"
  | "designation"
  | "deductionComponent";
export type ReportFilters = {
  kind: ReportKind;
  from: string;
  to: string;
  dueThrough: string;
  search: string;
};
export type ReportPage<T> = {
  schoolId: string;
  schoolName: string;
  generatedAt: string;
  from: string;
  to: string;
  page: number;
  size: number;
  total: number;
  items: T[];
};
export type RegisterRow = {
  id: string;
  payrollRunId: string;
  runCode: string;
  payrollMonth: string;
  runStatus: string;
  staffProfileId: string;
  employeeCode: string;
  staffName: string;
  currencyCode: string;
  gross: number;
  deductions: number;
  employerContributions: number;
  net: number;
  accrualStatus: string;
  paymentStatus: string;
  paid: number | null;
  pending: number | null;
  unsettled: number | null;
  accrualJournalId: string | null;
  paymentJournalId: string | null;
};
export type ReconciliationRow = {
  id: string;
  payrollRunId: string;
  runCode: string;
  postingType: string;
  postingDate: string;
  currencyCode: string;
  sourceCount: number;
  sourceAmount: number;
  actualSourceCount: number;
  actualSourceAmount: number;
  frozenPayrollAmount: number;
  journalId: string | null;
  journalStatus: string;
  journalDebit: number | null;
  journalCredit: number | null;
  reversalJournalId: string | null;
  reversalStatus: string | null;
  reconciliationStatus: string;
  issues: string[];
};
export type SummaryRow = {
  groupBy: "DEPARTMENT" | "DESIGNATION" | "DEDUCTION_COMPONENT";
  dimensionStatus: string;
  dimensionCode: string | null;
  dimensionLabel: string;
  runStatus: "FINALIZED" | "REVERSED";
  currencyCode: string;
  payrollRunCount: number;
  staffResultCount: number;
  gross: number | null;
  deductions: number;
  employerContributions: number | null;
  net: number | null;
};
export type AdvanceReportPage<T> = {
  schoolId: string;
  schoolName: string;
  generatedAt: string;
  dueThrough: string;
  page: number;
  size: number;
  total: number;
  items: T[];
};
export type AdvanceReconciliationRow = {
  id: string;
  advanceNumber: string;
  advanceType: "ADVANCE" | "LOAN";
  staffProfileId: string;
  employeeCode: string;
  staffName: string;
  issueDate: string;
  recoveryStartMonth: string;
  currencyCode: string;
  principalAmount: number;
  advanceStatus: "APPROVED";
  declaredInstallmentCount: number;
  actualInstallmentCount: number;
  scheduledAmount: number;
  scheduledDueAmount: number;
  disbursementStatus: string;
  postedDisbursementAmount: number | null;
  recoveredAmount: number | null;
  outstandingAmount: number | null;
  overdueAmount: number | null;
  nextUnrecoveredMonth: string | null;
  pendingRecoveryCount: number;
  pendingRecoveryAmount: number;
  reconciliationStatus: string;
  issues: string[];
};
export type ReportResult =
  | { kind: "register"; data: ReportPage<RegisterRow> }
  | { kind: "reconciliation"; data: ReportPage<ReconciliationRow> }
  | { kind: "advanceReconciliation"; data: AdvanceReportPage<AdvanceReconciliationRow> }
  | {
      kind: "department" | "designation" | "deductionComponent";
      data: ReportPage<SummaryRow>;
    };

export const isAdvanceReport = (kind: ReportKind) => kind === "advanceReconciliation";
export const isMonthlyReport = (kind: ReportKind) =>
  kind !== "reconciliation" && kind !== "advanceReconciliation";
export function reportTitle(kind: ReportKind) {
  return {
    register: "Historical salary register",
    reconciliation: "Payroll-to-ledger reconciliation",
    advanceReconciliation: "Advance recovery reconciliation",
    department: "Historical payroll by department",
    designation: "Historical payroll by designation",
    deductionComponent: "Historical deductions by component",
  }[kind];
}

export function defaultReportFilters(today: string, kind: ReportKind = "register"): ReportFilters {
  return {
    kind,
    from: today.slice(0, 4) + (isMonthlyReport(kind) ? "-01" : "-01-01"),
    to: isMonthlyReport(kind) ? today.slice(0, 7) : today,
    dueThrough: today,
    search: "",
  };
}
function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value.slice(0, 4) === "0000") return false;
  const date = new Date(value + "T00:00:00Z");
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
export function reportQuery(schoolId: string, filters: ReportFilters, page = 0, csv = false) {
  if (!schoolId) throw new Error("A school is required.");
  if (![
    "register",
    "reconciliation",
    "advanceReconciliation",
    "department",
    "designation",
    "deductionComponent",
  ].includes(filters.kind))
    throw new Error("Choose a report.");
  const monthly = isMonthlyReport(filters.kind);
  const advance = isAdvanceReport(filters.kind);
  const from = filters.from + (monthly ? "-01" : "");
  const to = filters.to + (monthly ? "-01" : "");
  if (advance ? !validDate(filters.dueThrough) :
    !validDate(from) || !validDate(to) || to < from ||
      (Date.parse(to) - Date.parse(from)) / 86400000 > 3660) {
    throw new Error(advance
      ? "Choose a valid due-through date."
      : "Choose valid, ordered dates covering at most ten years.");
  }
  const search = filters.search.trim();
  if (search.length > 100 || /[\u0000-\u001f\u007f-\u009f]/u.test(search))
    throw new Error("Use at most 100 printable characters for the employee search.");
  if (!Number.isInteger(page) || page < 0 || page > 10000)
    throw new Error("Choose a valid report page.");
  const params = advance
    ? new URLSearchParams({ schoolId, dueThrough: filters.dueThrough })
    : new URLSearchParams({ schoolId, from, to });
  const summaryGroup =
    filters.kind === "department"
      ? "DEPARTMENT"
      : filters.kind === "designation"
        ? "DESIGNATION"
        : filters.kind === "deductionComponent"
          ? "DEDUCTION_COMPONENT"
          : null;
  if (summaryGroup) params.set("groupBy", summaryGroup);
  if (search) params.set("search", search);
  if (!csv) {
    params.set("page", String(page));
    params.set("size", "25");
  }
  const path = summaryGroup
    ? "summary"
    : advance
      ? "advance-reconciliation"
      : filters.kind;
  return "/api/v1/payroll/accounting/reports/" + path + (csv ? ".csv" : "") + "?" + params;
}
export async function reportResponse(
  fetcher: typeof fetch,
  apiUrl: string,
  schoolId: string,
  filters: ReportFilters,
  page: number,
  csv: boolean,
  signal: AbortSignal,
) {
  const response = await fetcher(apiUrl + reportQuery(schoolId, filters, page, csv), {
    credentials: "include",
    cache: "no-store",
    redirect: "error",
    signal,
    headers: { Accept: csv ? "text/csv" : "application/json" },
  });
  if (!response.ok) {
    if (response.status === 401)
      throw new Error("Your session has ended. Sign in again to view payroll reports.");
    if (response.status === 403)
      throw new Error("Payroll and accounting access are both required for this school's reports.");
    let detail = "The report could not be loaded. Please retry.";
    try {
      detail = ((await response.json()) as { detail?: string }).detail || detail;
    } catch {
      /* Safe fallback. */
    }
    throw new Error(detail);
  }
  const media = response.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase();
  if (media !== (csv ? "text/csv" : "application/json"))
    throw new Error("An unexpected report response was received. Please retry.");
  return response;
}
export async function readReport(
  response: Response,
  kind: ReportKind,
  schoolId: string,
  expectedPage: number,
  expectedDueThrough = "",
): Promise<ReportResult> {
  const data = (await response.json()) as
    | ReportPage<RegisterRow>
    | ReportPage<ReconciliationRow>
    | ReportPage<SummaryRow>
    | AdvanceReportPage<AdvanceReconciliationRow>;
  if (
    data.schoolId !== schoolId ||
    data.page !== expectedPage ||
    data.size !== 25 ||
    !Number.isSafeInteger(data.total) ||
    data.total < 0 ||
    !Array.isArray(data.items) ||
    data.items.length > data.size
  )
    throw new Error("The report scope or pagination could not be verified. Please reload.");
  if (kind === "advanceReconciliation") {
    const advance = data as AdvanceReportPage<AdvanceReconciliationRow>;
    if (!validDate(advance.dueThrough) || advance.dueThrough !== expectedDueThrough)
      throw new Error("The report scope or due-through date could not be verified. Please reload.");
  }
  return kind === "register"
    ? { kind, data: data as ReportPage<RegisterRow> }
    : kind === "reconciliation"
      ? { kind, data: data as ReportPage<ReconciliationRow> }
      : kind === "advanceReconciliation"
        ? { kind, data: data as AdvanceReportPage<AdvanceReconciliationRow> }
      : { kind, data: data as ReportPage<SummaryRow> };
}
export function reportStatusTone(status: string): "ok" | "attention" | "neutral" {
  if (["PAID", "POSTED", "CAPTURED", "MATCHED_POSTED", "MATCHED_REVERSED", "MATCHED_REJECTED",
    "DISBURSED", "PARTIALLY_RECOVERED", "FULLY_RECOVERED"].includes(status))
    return "ok";
  if (["MISMATCH", "UNVERIFIED", "INCONSISTENT", "AWAITING_APPROVAL", "UNPAID",
    "NOT_CAPTURED", "NO_ASSIGNMENT", "AMBIGUOUS", "NO_DEPARTMENT", "NOT_DISBURSED",
    "RECOVERY_PENDING", "OVERDUE", "EVIDENCE_MISMATCH"].includes(status))
    return "attention";
  return "neutral";
}
