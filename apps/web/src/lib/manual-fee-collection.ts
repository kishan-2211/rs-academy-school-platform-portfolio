export type ManualPaymentMethod = "CASH" | "SCHOOL_UPI" | "BANK_TRANSFER" | "CHEQUE";
export type BankCreditSource = "BANK_APP_CREDIT" | "BANK_STATEMENT_CREDIT";
export type ManualFeeCharge = {
  id: string; label: string; feeHeadName: string; dueOn: string;
  outstandingAmount: number; allowPartial: boolean; reservedAmount?: number;
};
export type ManualCollectionContext = {
  schoolId: string; accountId: string; currencyCode: string; balance: number;
  schoolTimeZone: string; schoolToday: string; latestClosedDate?: string;
  charges: ManualFeeCharge[];
};
export type ManualCollectionDraft = {
  paymentMethod: ManualPaymentMethod; amount: string; externalReference: string; notes: string;
  allocations: Record<string, string>; actualLocal: string; utcOffset: string;
  chequeReceivedOn: string; postingOn: string; reviewReason: string;
  cashCounted: boolean; bankCreditConfirmed: boolean; bankCreditSource: BankCreditSource;
};
export type ManualCollectionRequest = {
  schoolId: string; accountId: string; paymentMethod: ManualPaymentMethod;
  externalReference: string | null; amount: number; receivedOn: string; notes: string | null;
  allocations: Array<{ chargeId: string; amount: number }>;
  actualReceivedAt?: string; postingOn?: string | null;
  bankCreditVerification?: { sourceKind: BankCreditSource; confirmedReceived: true; creditedEntryReference: string } | null;
  reviewReason?: string | null;
};

export const manualCollectionErrors = {
  AMOUNT: "Enter a positive amount with no more than two decimal places.",
  ALLOCATIONS: "Choose eligible fee periods and allocate the exact amount received.",
  BALANCE: "The collection exceeds the available fees. Advance payments are not supported here.",
  CHARGE: "A fee period changed or has an online reservation. Refresh the account before collecting.",
  FULL_ONLY: "This fee period requires its full outstanding amount.",
  CASH: "Count the cash received and confirm it before posting.",
  CREDIT: "Confirm the actual credit in the school's bank app or statement before posting.",
  REFERENCE: "Enter the actual bounded bank or cheque reference, not a screenshot or payment credential.",
  RECEIVED_TIME: "Enter the actual received time in the school's configured time zone.",
  AMBIGUOUS_TIME: "This local time occurs twice. Enter the UTC offset shown on the original bank record.",
  DATE: "Choose a valid original school date that is not in the future.",
  POSTING_DATE: "Choose an eligible open posting date without changing the original received day.",
  REVIEW: "Enter a three-to-240-character review reason for the changed or closed original day.",
  NOTES: "Keep collection notes within 500 characters and do not include payment credentials.",
} as const;

export class ManualCollectionValidationError extends Error {
  readonly code: keyof typeof manualCollectionErrors;
  constructor(code: keyof typeof manualCollectionErrors) {
    super(manualCollectionErrors[code]);
    this.name = "ManualCollectionValidationError";
    this.code = code;
  }
}
function invalid(code: keyof typeof manualCollectionErrors): never { throw new ManualCollectionValidationError(code); }

/** Integer minor units throughout validation and allocation. Never sum decimal floats. */
export function manualAmountPaise(value: string | number): number | null {
  const text = typeof value === "number" ? String(value) : value.trim();
  if (!/^(?:0|[1-9]\d{0,8})(?:\.\d{1,2})?$/.test(text)) return null;
  const [whole, fraction = ""] = text.split(".");
  const paise = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return Number.isSafeInteger(paise) && paise <= 10_000_000_000 ? paise : null;
}
export function manualAmountText(paise: number): string {
  return `${Math.floor(paise / 100)}.${String(paise % 100).padStart(2, "0")}`;
}
export function availableManualChargePaise(charge: ManualFeeCharge): number | null {
  const outstanding = manualAmountPaise(charge.outstandingAmount);
  const reserved = manualAmountPaise(charge.reservedAmount ?? 0);
  if (outstanding === null || reserved === null || reserved > outstanding) return null;
  if (!charge.allowPartial && reserved > 0) return 0;
  return outstanding - reserved;
}
export function selectedManualTotalPaise(allocations: Record<string, string>): number | null {
  let total = 0;
  for (const value of Object.values(allocations)) {
    const amount = manualAmountPaise(value);
    if (amount === null || amount <= 0) return null;
    total += amount;
  }
  return Number.isSafeInteger(total) && total <= 10_000_000_000 ? total : null;
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value < "2000-01-01") return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
function schoolFormatter(timeZone: string) {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone, calendar: "gregory", numberingSystem: "latn", hourCycle: "h23",
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
}
function schoolParts(at: Date, formatter: Intl.DateTimeFormat) {
  const parts = Object.fromEntries(formatter.formatToParts(at).map((part) => [part.type, part.value]));
  return { local: `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`,
    localExact: `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}`,
    epoch: Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour), Number(parts.minute), Number(parts.second)) };
}
/** This is called only after hydration or a deliberate user action; not for SSR defaults. */
export function currentSchoolLocalTime(timeZone: string, at: Date): string | null {
  try { return schoolParts(at, schoolFormatter(timeZone)).localExact; } catch { return null; }
}
export type SchoolLocalResolution = { kind: "valid"; instant: string } | { kind: "invalid" | "ambiguous" };

/**
 * Resolve the configured school zone, never the browser/device zone. Sample the
 * surrounding real zone offsets, then round-trip each possible instant. DST gaps
 * fail closed; overlaps need a supplied offset that also matches the school zone.
 */
export function schoolLocalTimeToInstant(local: string, timeZone: string, utcOffset = ""): SchoolLocalResolution {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(local) || !validDate(local.slice(0, 10))) return { kind: "invalid" };
  const civil = Date.parse(`${local.length === 16 ? `${local}:00` : local}Z`);
  if (!Number.isFinite(civil) || new Date(civil).toISOString().slice(0, local.length) !== local) return { kind: "invalid" };
  try {
    const formatter = schoolFormatter(timeZone);
    const matchesLocal = (at: Date) => {
      const parts = schoolParts(at, formatter);
      return (local.length === 16 ? parts.local : parts.localExact) === local;
    };
    if (utcOffset.trim()) {
      const offset = /^([+-])(\d{2}):(\d{2})$/.exec(utcOffset.trim());
      if (!offset || Number(offset[2]) > 14 || Number(offset[3]) > 59 || Number(offset[2]) === 14 && Number(offset[3]) !== 0) return { kind: "invalid" };
      const milliseconds = (Number(offset[2]) * 60 + Number(offset[3])) * 60_000 * (offset[1] === "+" ? 1 : -1);
      const at = new Date(civil - milliseconds);
      return matchesLocal(at) ? { kind: "valid", instant: at.toISOString() } : { kind: "invalid" };
    }
    const offsets = new Set<number>();
    for (let hours = -36; hours <= 36; hours += 6) {
      const at = new Date(civil + hours * 3_600_000);
      offsets.add(schoolParts(at, formatter).epoch - at.getTime());
    }
    const matches = [...offsets].map((offset) => new Date(civil - offset))
      .filter(matchesLocal);
    if (matches.length === 1 && matches[0]) return { kind: "valid", instant: matches[0].toISOString() };
    return { kind: matches.length > 1 ? "ambiguous" : "invalid" };
  } catch { return { kind: "invalid" }; }
}

export function buildManualCollectionRequest(context: ManualCollectionContext, draft: ManualCollectionDraft, now: Date): ManualCollectionRequest {
  const amount = manualAmountPaise(draft.amount);
  if (amount === null || amount <= 0) invalid("AMOUNT");
  const balance = manualAmountPaise(context.balance);
  if (balance === null || amount > balance) invalid("BALANCE");
  const total = selectedManualTotalPaise(draft.allocations);
  const keys = Object.keys(draft.allocations);
  if (!keys.length || keys.length > 1000 || total !== amount) invalid("ALLOCATIONS");
  const allocations = keys.sort().map((chargeId) => {
    const charge = context.charges.find((item) => item.id === chargeId);
    const raw = draft.allocations[chargeId];
    if (raw === undefined) invalid("ALLOCATIONS");
    const line = manualAmountPaise(raw);
    const available = charge && availableManualChargePaise(charge);
    if (!charge || line === null || available === null || available === undefined || line <= 0 || line > available) invalid("CHARGE");
    if (!charge.allowPartial && line !== manualAmountPaise(charge.outstandingAmount)) invalid("FULL_ONLY");
    return { chargeId, amount: line / 100 };
  });
  const method = draft.paymentMethod;
  if (!["CASH", "SCHOOL_UPI", "BANK_TRANSFER", "CHEQUE"].includes(method)) invalid("CREDIT");
  const reference = draft.externalReference.trim();
  if (reference.length > 120 || /[\x00-\x1f\x7f]/.test(reference)) invalid("REFERENCE");
  if (method !== "CASH" && !reference) invalid("REFERENCE");
  if ((method === "SCHOOL_UPI" || method === "BANK_TRANSFER") && !/^[A-Za-z0-9][A-Za-z0-9/_.: -]{0,119}$/.test(reference)) invalid("REFERENCE");
  const notes = draft.notes.trim();
  if (notes.length > 500 || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(notes)) invalid("NOTES");
  let originalDate: string;
  let actualReceivedAt: string | undefined;
  if (method === "CHEQUE") originalDate = draft.chequeReceivedOn;
  else {
    const resolved = schoolLocalTimeToInstant(draft.actualLocal, context.schoolTimeZone, draft.utcOffset);
    if (resolved.kind === "ambiguous") invalid("AMBIGUOUS_TIME");
    if (resolved.kind !== "valid") invalid("RECEIVED_TIME");
    actualReceivedAt = resolved.instant;
    originalDate = draft.actualLocal.slice(0, 10);
    if (Date.parse(actualReceivedAt) > now.getTime()) invalid("DATE");
  }
  if (!validDate(originalDate) || !validDate(context.schoolToday) || originalDate > context.schoolToday) invalid("DATE");
  const request: ManualCollectionRequest = {
    schoolId: context.schoolId, accountId: context.accountId, paymentMethod: method,
    externalReference: reference || null, amount: amount / 100, receivedOn: originalDate,
    notes: notes || null, allocations,
  };
  // Cheque keeps its existing API behavior; received-funds/clearance policy is not silently changed.
  if (method === "CHEQUE") return request;
  const posting = draft.postingOn || originalDate;
  if (!validDate(posting) || posting < originalDate || posting > context.schoolToday
      || context.latestClosedDate && posting <= context.latestClosedDate) invalid("POSTING_DATE");
  const review = draft.reviewReason.trim();
  const reviewNeeded = posting !== originalDate || Boolean(context.latestClosedDate && originalDate <= context.latestClosedDate);
  if (review && (review.length < 3 || review.length > 240 || /[\x00-\x1f\x7f]/.test(review)) || reviewNeeded && !review) invalid("REVIEW");
  request.actualReceivedAt = actualReceivedAt;
  request.postingOn = draft.postingOn || null;
  request.reviewReason = review || null;
  if (method === "CASH") {
    if (!draft.cashCounted) invalid("CASH");
    request.bankCreditVerification = null;
  } else {
    if (!draft.bankCreditConfirmed || !["BANK_APP_CREDIT", "BANK_STATEMENT_CREDIT"].includes(draft.bankCreditSource)) invalid("CREDIT");
    request.bankCreditVerification = { sourceKind: draft.bankCreditSource, confirmedReceived: true, creditedEntryReference: reference };
  }
  return request;
}

export type FrozenManualCollectionAttempt = Readonly<{ key: string; body: string; request: Readonly<ManualCollectionRequest> }>;
export function freezeManualCollectionAttempt(request: ManualCollectionRequest, key: string): FrozenManualCollectionAttempt {
  if (!key || key.length > 100 || /[\x00-\x20\x7f]/.test(key)) throw new Error("A bounded idempotency key is required");
  const copy = { ...request, allocations: request.allocations.map((line) => Object.freeze({ ...line })),
    ...(request.bankCreditVerification ? { bankCreditVerification: Object.freeze({ ...request.bankCreditVerification }) } : {}) };
  Object.freeze(copy.allocations); Object.freeze(copy);
  return Object.freeze({ key, body: JSON.stringify(copy), request: copy });
}
export type ManualCollectionOutcome =
  | { kind: "posted"; id: string; receiptNumber: string }
  | { kind: "rejected"; code: string | null }
  | { kind: "uncertain" | "not-dispatched" | "cancelled" };
export async function sendManualCollectionAttempt(attempt: FrozenManualCollectionAttempt, transport: {
  apiUrl: string; csrfToken: () => Promise<string>; fetch: typeof fetch; shouldDispatch: () => boolean;
}): Promise<ManualCollectionOutcome> {
  let token: string;
  try { token = await transport.csrfToken(); if (!token) return { kind: "not-dispatched" }; }
  catch { return { kind: "not-dispatched" }; }
  if (!transport.shouldDispatch()) return { kind: "cancelled" };
  try {
    const response = await transport.fetch(`${transport.apiUrl}/api/v1/finance/payments`, {
      method: "POST", credentials: "include", cache: "no-store",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token, "Idempotency-Key": attempt.key },
      body: attempt.body,
    });
    if (!response.ok) {
      if (![400, 401, 403, 404, 405, 409, 415, 422].includes(response.status)) return { kind: "uncertain" };
      let code: string | null = null;
      try { const problem = await response.json() as { code?: unknown }; if (typeof problem.code === "string") code = problem.code; } catch { /* Safe generic rejection only. */ }
      return { kind: "rejected", code };
    }
    const result = await response.json() as { id?: unknown; receiptNumber?: unknown };
    if (typeof result.id !== "string" || !result.id || typeof result.receiptNumber !== "string"
        || !result.receiptNumber.trim() || result.receiptNumber.length > 120) return { kind: "uncertain" };
    return { kind: "posted", id: result.id, receiptNumber: result.receiptNumber };
  } catch { return { kind: "uncertain" }; }
}
