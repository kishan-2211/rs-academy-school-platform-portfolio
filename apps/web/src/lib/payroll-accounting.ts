/** Browser presentation and request helpers; the API remains authoritative. */
export type PaymentMethod = "CASH" | "BANK_TRANSFER" | "CHEQUE" | "SCHOOL_UPI";
export const PAYMENT_METHODS: readonly PaymentMethod[] = [
  "CASH",
  "BANK_TRANSFER",
  "CHEQUE",
  "SCHOOL_UPI",
];
export type FinancialYear = {
  id: string;
  code: string;
  displayName: string;
  startsOn: string;
  endsOn: string;
  accountingBasis: string;
  currencyCode: string;
  status: string;
};
export type Account = {
  id: string;
  code: string;
  displayName: string;
  accountType: string;
  category: string;
  status: string;
};
export type Component = {
  id: string;
  code: string;
  displayName: string;
  componentKind: string;
  status: string;
};
export type ComponentMapping = {
  payrollComponentId: string;
  componentCode: string;
  componentName: string;
  componentKind: string;
  expenseAccountId: string | null;
  payableAccountId: string | null;
};
export type TenderMapping = { paymentMethod: PaymentMethod; paymentAccountId: string };
export type Configuration = {
  id: string;
  financialYearId: string;
  supersedesConfigurationId: string | null;
  revisionNumber: number;
  effectiveFrom: string;
  accountingBasis: string;
  currencyCode: string;
  salaryPayableAccountId: string;
  status: string;
  creatorName: string;
  createdAt: string | null;
  submitterName: string | null;
  submittedAt: string | null;
  deciderName: string | null;
  decidedAt: string | null;
  rowVersion: number;
  canEdit: boolean;
  canSubmit: boolean;
  canDecide: boolean;
  componentMappings: ComponentMapping[];
  tenderMappings: TenderMapping[];
};
export type RunStaff = {
  id: string;
  staffProfileId: string;
  employeeCode: string;
  staffName: string;
  net: number;
  paymentStatus: string;
};
export type Run = {
  id: string;
  runCode: string;
  payrollMonth: string;
  periodEnd: string;
  currencyCode: string;
  status: string;
  staffCount: number;
  payableGross: number;
  deductions: number;
  employerContributions: number;
  net: number;
  accrualStatus: string;
  staff: RunStaff[];
};
export type PostingBatch = {
  id: string;
  configurationId: string;
  payrollRunId: string;
  accrualBatchId: string | null;
  financialYearId: string;
  accountingPeriodId: string;
  postingType: string;
  postingDate: string;
  sequenceNumber: number;
  paymentMethod: string | null;
  paymentReference: string | null;
  sourceCount: number;
  activeSourceCount: number;
  sourceAmount: number;
  accountingJournalId: string | null;
  journalStatus: string | null;
  operationalStatus: string;
  preparerName: string;
  preparedAt: string;
  rowVersion: number;
};
export type PayrollAccountingData = {
  schoolId: string;
  schoolName: string;
  canConfigure: boolean;
  canPrepare: boolean;
  canApprove: boolean;
  financialYears: FinancialYear[];
  accounts: Account[];
  components: Component[];
  configurations: Configuration[];
  runs: Run[];
  postingBatches: PostingBatch[];
};
export type MappingDraft = {
  id?: string;
  expectedVersion?: number;
  financialYearId: string;
  supersedesConfigurationId: string | null;
  revisionNumber: number;
  effectiveFrom: string;
  salaryPayableAccountId: string;
  reason: string;
  components: Record<string, { expenseAccountId: string; payableAccountId: string }>;
  tenders: Partial<Record<PaymentMethod, string>>;
};
export type PostingDraft = {
  kind: "accruals" | "payments";
  runId: string;
  financialYearId: string;
  postingDate: string;
  paymentMethod: PaymentMethod;
  paymentReference: string;
  staffIds: string[];
  reason: string;
};

export const payrollLabel = (value: string | null | undefined) =>
  value
    ? value
        .toLowerCase()
        .replaceAll("_", " ")
        .replace(/\b\w/g, (letter) => letter.toUpperCase())
    : "Not recorded";
export const payrollMoney = (amount: number, currency = "INR") =>
  new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: 2 }).format(
    amount,
  );
export const schoolToday = () =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
export const postingAccounts = (
  accounts: Account[],
  category: "EXPENSE" | "PAYABLE" | "CASH" | "BANK",
) =>
  accounts.filter(
    (account) =>
      account.status === "ACTIVE" &&
      account.category === category &&
      account.accountType ===
        (category === "EXPENSE" ? "EXPENSE" : category === "PAYABLE" ? "LIABILITY" : "ASSET"),
  );
export const openAccrualYears = (data: PayrollAccountingData) =>
  data.financialYears.filter(
    (year) => year.status === "OPEN" && year.accountingBasis === "ACCRUAL",
  );
export function effectiveMapping(data: PayrollAccountingData, yearId: string, date: string) {
  return data.configurations
    .filter(
      (mapping) =>
        mapping.financialYearId === yearId &&
        ["APPROVED", "SUPERSEDED"].includes(mapping.status) &&
        mapping.effectiveFrom <= date,
    )
    .sort(
      (a, b) =>
        b.effectiveFrom.localeCompare(a.effectiveFrom) || b.revisionNumber - a.revisionNumber,
    )[0];
}
export function mappingDraft(
  data: PayrollAccountingData,
  yearId: string,
  source?: Configuration,
  edit = false,
): MappingDraft {
  const year = data.financialYears.find((item) => item.id === yearId);
  const active = data.components.filter((item) => item.status === "ACTIVE");
  const nextDate = source ? new Date(source.effectiveFrom + "T00:00:00Z") : null;
  if (nextDate) nextDate.setUTCDate(nextDate.getUTCDate() + 1);
  return {
    ...(edit && source ? { id: source.id, expectedVersion: source.rowVersion } : {}),
    financialYearId: yearId,
    supersedesConfigurationId: edit
      ? (source?.supersedesConfigurationId ?? null)
      : (source?.id ?? null),
    revisionNumber: edit && source ? source.revisionNumber : source ? source.revisionNumber + 1 : 1,
    effectiveFrom:
      edit && source
        ? source.effectiveFrom
        : (nextDate?.toISOString().slice(0, 10) ?? year?.startsOn ?? ""),
    salaryPayableAccountId: source?.salaryPayableAccountId ?? "",
    reason: "",
    components: Object.fromEntries(
      active.map((component) => {
        const old = source?.componentMappings.find(
          (mapping) => mapping.payrollComponentId === component.id,
        );
        return [
          component.id,
          {
            expenseAccountId: old?.expenseAccountId ?? "",
            payableAccountId: old?.payableAccountId ?? "",
          },
        ];
      }),
    ),
    tenders: Object.fromEntries(
      source?.tenderMappings.map((mapping) => [mapping.paymentMethod, mapping.paymentAccountId]) ??
        [],
    ),
  };
}
export function configurationPayload(data: PayrollAccountingData, draft: MappingDraft) {
  if (!draft.reason.trim() || draft.reason.trim().length < 3)
    throw new Error("Enter a reason of at least three characters.");
  const year = openAccrualYears(data).find((item) => item.id === draft.financialYearId);
  if (!year || draft.effectiveFrom < year.startsOn || draft.effectiveFrom > year.endsOn)
    throw new Error("Choose an effective date within an open accrual financial year.");
  const hasAccount = (id: string, category: "EXPENSE" | "PAYABLE" | "CASH" | "BANK") =>
    postingAccounts(data.accounts, category).some((account) => account.id === id);
  if (!hasAccount(draft.salaryPayableAccountId, "PAYABLE"))
    throw new Error("Choose an active salary-payable account.");
  const componentMappings = data.components
    .filter((component) => component.status === "ACTIVE")
    .map((component) => {
      const line = draft.components[component.id];
      const expense = component.componentKind !== "DEDUCTION" ? line?.expenseAccountId : null;
      const payable = component.componentKind !== "EARNING" ? line?.payableAccountId : null;
      if (
        (component.componentKind !== "DEDUCTION" &&
          (!expense || !hasAccount(expense, "EXPENSE"))) ||
        (component.componentKind !== "EARNING" && (!payable || !hasAccount(payable, "PAYABLE")))
      )
        throw new Error("Map every active component to the required posting accounts.");
      return {
        payrollComponentId: component.id,
        expenseAccountId: expense ?? null,
        payableAccountId: payable ?? null,
      };
    });
  const tenderMappings = PAYMENT_METHODS.flatMap((paymentMethod) => {
    const paymentAccountId = draft.tenders[paymentMethod];
    if (!paymentAccountId) return [];
    if (!hasAccount(paymentAccountId, paymentMethod === "CASH" ? "CASH" : "BANK"))
      throw new Error("Choose an active cash or bank account for each enabled payment method.");
    return [{ paymentMethod, paymentAccountId }];
  });
  if (!componentMappings.length || !tenderMappings.length)
    throw new Error("Map active payroll components and at least one payment method.");
  return {
    schoolId: data.schoolId,
    financialYearId: draft.financialYearId,
    supersedesConfigurationId: draft.supersedesConfigurationId,
    revisionNumber: draft.revisionNumber,
    effectiveFrom: draft.effectiveFrom,
    salaryPayableAccountId: draft.salaryPayableAccountId,
    componentMappings,
    tenderMappings,
    reason: draft.reason.trim(),
  };
}
export const eligiblePaymentStaff = (run: Run) =>
  run.status === "FINALIZED" && run.accrualStatus === "POSTED"
    ? run.staff.filter((staff) => staff.paymentStatus === "UNPAID" && staff.net > 0)
    : [];
export function postingPayload(data: PayrollAccountingData, draft: PostingDraft, today: string) {
  const run = data.runs.find((item) => item.id === draft.runId);
  const year = openAccrualYears(data).find((item) => item.id === draft.financialYearId);
  const mapping = effectiveMapping(data, draft.financialYearId, draft.postingDate);
  if (!run || run.status !== "FINALIZED")
    throw new Error("Refresh and choose an unchanged finalized payroll run.");
  if (
    !year ||
    year.currencyCode !== run.currencyCode ||
    !draft.postingDate ||
    draft.postingDate < year.startsOn ||
    draft.postingDate > year.endsOn ||
    draft.postingDate > today
  )
    throw new Error(
      "Choose a non-future posting date within an open matching-currency financial year.",
    );
  if (!mapping)
    throw new Error("An approved payroll mapping must be effective on the posting date.");
  if (draft.reason.trim().length < 3)
    throw new Error("Enter a reason of at least three characters.");
  const base = {
    schoolId: data.schoolId,
    financialYearId: year.id,
    postingDate: draft.postingDate,
    reason: draft.reason.trim(),
  };
  if (draft.kind === "accruals") {
    if (draft.postingDate !== run.periodEnd || run.accrualStatus !== "NOT_PREPARED")
      throw new Error("Accrual requires the payroll period-end date and no active accrual.");
    return base;
  }
  if (draft.postingDate < run.periodEnd)
    throw new Error("Salary payment cannot precede the payroll accrual.");
  if (!mapping.tenderMappings.some((tender) => tender.paymentMethod === draft.paymentMethod))
    throw new Error("Choose a payment method enabled in the effective mapping.");
  if (draft.paymentMethod !== "CASH" && !draft.paymentReference.trim())
    throw new Error("Record the bank, cheque or manual UPI reference.");
  const ids = [...new Set(draft.staffIds)].sort();
  const eligible = new Set(eligiblePaymentStaff(run).map((staff) => staff.id));
  if (!ids.length || ids.length > 500 || ids.some((id) => !eligible.has(id)))
    throw new Error(
      "Select unpaid, positive-net staff from a run with a posted accrual. Refresh if payment status changed.",
    );
  return {
    ...base,
    paymentMethod: draft.paymentMethod,
    paymentReference: draft.paymentReference.trim() || null,
    payrollRunStaffResultIds: ids,
  };
}

/** Keep failed/uncertain attempts in memory. Never persist payroll payloads in browser storage. */
export function createPostingAttempts(makeKey: () => string) {
  const attempts = new Map<string, string>();
  const identity = (path: string, body: object) => JSON.stringify([path, body]);
  return {
    key(path: string, body: object) {
      const fingerprint = identity(path, body);
      let key = attempts.get(fingerprint);
      if (!key) {
        key = makeKey();
        attempts.set(fingerprint, key);
      }
      return key;
    },
    confirmed(path: string, body: object) {
      attempts.delete(identity(path, body));
    },
  };
}
