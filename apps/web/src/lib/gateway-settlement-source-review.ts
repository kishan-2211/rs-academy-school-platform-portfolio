import { reportedSettlementMoney, type SettlementEvent } from "./gateway-settlements";

/** Read-only historical identification. This is not bank reconciliation or posting authority. */
export const settlementSourceReviewReasons = ["UNSUPPORTED_PROVIDER", "REFUND_ASSOCIATION_REVIEW", "UNSUPPORTED_EVENT_TYPE", "UNSUPPORTED_EVENT_DIRECTION", "UNSUPPORTED_EVENT_STATUS", "UNSUPPORTED_CURRENCY", "FRACTIONAL_PAISE", "NON_POSITIVE_GROSS", "MISSING_PAYMENT_REFERENCES", "DUPLICATE_PAYMENT_SOURCE", "SOURCE_NOT_FOUND_OR_OUT_OF_SCOPE", "AMBIGUOUS_PAYMENT_SOURCE", "UNRESOLVED_NUMBERED_RECEIPT", "GROSS_MISMATCH", "ORIGINAL_POSTING_INVALID", "CLEARING_EVIDENCE_UNAVAILABLE", "LEGACY_BANK_CLEARING_REVIEW", "INVALID_CLEARING_IDENTITY"] as const;
export type SettlementSourceReviewReason = typeof settlementSourceReviewReasons[number];
export type SettlementIdentifiedSource = { captureId: string; paymentId: string; feeLedgerEntryId: string; receiptNumber: string; postingItemId: string; postingBatchId: string; configurationId: string; accountingJournalId: string; clearingAccountId: string; clearingAccountCategory: "OTHER"; originalFinancialYearId: string; originalAccountingPeriodId: string; originalPostingDate: string; accountingBasis: "CASH" | "ACCRUAL"; currencyCode: "INR"; grossAmount: string };
export type SettlementSourceReviewRow = { sourceReadId: string; rowIndex: number; observedAt: string; event: SettlementEvent; status: "SOURCE_IDENTIFIED" | "REVIEW_REQUIRED"; reasons: SettlementSourceReviewReason[]; source: SettlementIdentifiedSource | null };
export type SettlementSourceReview = { schoolId: string; requestId: string; settlementReference: string; reviewStage: "SOURCE_IDENTIFICATION_ONLY"; pagesRead: number; totalRows: number; sourceIdentifiedRows: number; reviewRequiredRows: number; page: number; pageSize: number; hasMore: boolean; items: SettlementSourceReviewRow[] };
export type SettlementSourceReviewErrorCode = "unavailable" | "session" | "scope" | "incomplete" | "filters";
export class SettlementSourceReviewError extends Error {
  readonly code: SettlementSourceReviewErrorCode;
  constructor(code: SettlementSourceReviewErrorCode) { super(`SETTLEMENT_SOURCE_REVIEW_${code.toUpperCase()}`); this.name = "SettlementSourceReviewError"; this.code = code; }
}
export function settlementSourceReviewErrorCode(reason: unknown): SettlementSourceReviewErrorCode { return reason instanceof SettlementSourceReviewError ? reason.code : "unavailable"; }

const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const reference = /^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/;
const tag = /^[A-Z][A-Z0-9_]{0,59}$/;
const currency = /^[A-Z]{3}$/;
const fail = (): never => { throw new SettlementSourceReviewError("unavailable"); };
const isUuid = (value: unknown): value is string => typeof value === "string" && uuid.test(value);
const isReference = (value: unknown): value is string => typeof value === "string" && reference.test(value);
const isTag = (value: unknown): value is string => typeof value === "string" && tag.test(value);
const isCurrency = (value: unknown): value is string => typeof value === "string" && currency.test(value);
const integer = (value: unknown, max: number): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= max;
function keys(value: unknown, expected: readonly string[]): boolean { return value !== null && typeof value === "object" && !Array.isArray(value) && Object.keys(value).length === expected.length && Object.keys(value).every(key => expected.includes(key)); }
const nullable = (value: unknown, test: (value: unknown) => boolean) => value === null || test(value);
function date(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`); return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0,10) === value && value >= "2000-01-01" && value <= "2100-12-31";
}
function instant(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3}(?:\d{3}){0,2})?Z$/.test(value) || !date(value.slice(0,10))) return false;
  const parsed = new Date(value); return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0,19) === value.slice(0,19);
}
function nanos(value: string): bigint { return BigInt(Date.parse(`${value.slice(0,19)}Z`)) * 1000000n + BigInt((value.includes(".") ? value.slice(20,-1) : "").padEnd(9,"0")); }
function scaledAmount(value: string): bigint { const [whole,fraction = ""] = value.split("."); return BigInt(whole!) * 100000000n + BigInt(fraction.padEnd(8,"0")); }
function readEvent(value: unknown): SettlementEvent {
  if (!keys(value,["settlementReference","eventReference","reportedType","reportedDirection","reportedStatus","currencyCode","reportedAmount","reportedServiceCharge","reportedServiceTax","reportedSettlementAmount","occurredAt","orderReference","paymentReference","refundReferences","chargesCurrencyCode"])) return fail();
  const event = value as SettlementEvent;
  if (!isReference(event.settlementReference) || !isReference(event.eventReference) || ![event.reportedType,event.reportedDirection,event.reportedStatus].every(isTag) || !isCurrency(event.currencyCode) || !reportedSettlementMoney(event.reportedAmount) || ![event.reportedServiceCharge,event.reportedServiceTax,event.reportedSettlementAmount].every(value=>nullable(value,reportedSettlementMoney)) || !instant(event.occurredAt) || !nullable(event.orderReference,isReference) || !nullable(event.paymentReference,isReference) || !Array.isArray(event.refundReferences) || event.refundReferences.length > 100 || !event.refundReferences.every(isReference) || !nullable(event.chargesCurrencyCode,isCurrency)) return fail();
  return event;
}
function readSource(value: unknown): SettlementIdentifiedSource {
  if (!keys(value,["captureId","paymentId","feeLedgerEntryId","receiptNumber","postingItemId","postingBatchId","configurationId","accountingJournalId","clearingAccountId","clearingAccountCategory","originalFinancialYearId","originalAccountingPeriodId","originalPostingDate","accountingBasis","currencyCode","grossAmount"])) return fail();
  const source = value as SettlementIdentifiedSource;
  if (![source.captureId,source.paymentId,source.feeLedgerEntryId,source.postingItemId,source.postingBatchId,source.configurationId,source.accountingJournalId,source.clearingAccountId,source.originalFinancialYearId,source.originalAccountingPeriodId].every(isUuid) || typeof source.receiptNumber !== "string" || !source.receiptNumber.trim() || source.receiptNumber.length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(source.receiptNumber) || source.clearingAccountCategory !== "OTHER" || !["CASH","ACCRUAL"].includes(source.accountingBasis) || source.currencyCode !== "INR" || !date(source.originalPostingDate) || typeof source.grossAmount !== "string" || !/^(?:0|[1-9]\d{0,8})\.\d{2}$/.test(source.grossAmount) || !reportedSettlementMoney(source.grossAmount) || BigInt(source.grossAmount.replace(".","")) <= 0n) return fail();
  return source;
}
export { readSource as readSettlementIdentifiedSource };
function scope(schoolId: string, requestId: string, settlementReference: string, page: number): void {
  if (!isUuid(schoolId) || !isUuid(requestId) || !isReference(settlementReference) || !integer(page,10000)) throw new SettlementSourceReviewError("filters");
}
export function readSettlementSourceReview(value: unknown, schoolId: string, requestId: string, settlementReference: string, page: number): SettlementSourceReview {
  scope(schoolId,requestId,settlementReference,page);
  if (!keys(value,["schoolId","requestId","settlementReference","reviewStage","pagesRead","totalRows","sourceIdentifiedRows","reviewRequiredRows","page","pageSize","hasMore","items"])) return fail();
  const review = value as SettlementSourceReview;
  if (review.schoolId !== schoolId || review.requestId !== requestId || review.settlementReference !== settlementReference || review.reviewStage !== "SOURCE_IDENTIFICATION_ONLY" || !integer(review.pagesRead,1000) || review.pagesRead < 1 || ![review.totalRows,review.sourceIdentifiedRows,review.reviewRequiredRows].every(value=>integer(value,10000)) || review.totalRows > review.pagesRead * 10 || review.sourceIdentifiedRows + review.reviewRequiredRows !== review.totalRows || review.page !== page || review.pageSize !== 25 || !Array.isArray(review.items) || review.items.length !== Math.min(25,Math.max(0,review.totalRows-page*25)) || review.hasMore !== (page*25+review.items.length < review.totalRows)) return fail();
  const identities = new Set<string>(); let identified = 0, required = 0;
  for (const value of review.items) {
    if (!keys(value,["sourceReadId","rowIndex","observedAt","event","status","reasons","source"])) return fail();
    const row = value as SettlementSourceReviewRow, identity = `${row.sourceReadId}:${row.rowIndex}`;
    if (!isUuid(row.sourceReadId) || !integer(row.rowIndex,9) || !instant(row.observedAt) || identities.has(identity) || readEvent(row.event).settlementReference !== settlementReference || nanos(row.event.occurredAt) > nanos(row.observedAt) || !Array.isArray(row.reasons) || row.reasons.length > settlementSourceReviewReasons.length || new Set(row.reasons).size !== row.reasons.length || !row.reasons.every(reason=>settlementSourceReviewReasons.includes(reason))) return fail();
    if (row.status === "SOURCE_IDENTIFIED") { if (row.reasons.length || !row.source) return fail(); const source = readSource(row.source); if (row.event.reportedType !== "PAYMENT" || row.event.reportedDirection !== "CREDIT" || row.event.reportedStatus !== "SUCCESS" || !row.event.orderReference || !row.event.paymentReference || row.event.currencyCode !== source.currencyCode || row.event.reportedAmount.startsWith("-") || scaledAmount(row.event.reportedAmount) !== scaledAmount(source.grossAmount)) return fail(); identified++; }
    else if (row.status === "REVIEW_REQUIRED") { if (!row.reasons.length || row.source !== null) return fail(); required++; }
    else return fail();
    identities.add(identity);
  }
  if (identified > review.sourceIdentifiedRows || required > review.reviewRequiredRows || page === 0 && review.totalRows <= 25 && (identified !== review.sourceIdentifiedRows || required !== review.reviewRequiredRows)) return fail();
  return review;
}
export async function loadSettlementSourceReview(schoolId: string, requestId: string, settlementReference: string, page: number, signal: AbortSignal): Promise<SettlementSourceReview> {
  scope(schoolId,requestId,settlementReference,page);
  const params = new URLSearchParams({schoolId,page:String(page),pageSize:"25"});
  const response = await fetch(`${base}/api/v1/finance/gateway/settlements/requests/${requestId}/source-review?${params}`,{credentials:"include",cache:"no-store",signal});
  if (!response.ok) throw new SettlementSourceReviewError(response.status === 401 ? "session" : response.status === 403 ? "scope" : response.status === 409 ? "incomplete" : response.status === 400 ? "filters" : "unavailable");
  let result: unknown; try { result = await response.json(); } catch { return fail(); }
  return readSettlementSourceReview(result,schoolId,requestId,settlementReference,page);
}
