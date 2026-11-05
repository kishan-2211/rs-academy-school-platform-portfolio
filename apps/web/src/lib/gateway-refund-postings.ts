/** Reviewed recording of money already returned; never a provider refund command. */
export type RefundPosting = { id: string; refundId: string; paymentId: string; accountId: string; amount: number; currencyCode: string; postingOn: string; postedAt: string; ledgerEntryId: string; accountingBatchId: string | null; journalId: string | null; journalStatus: "DRAFT" | "SUBMITTED" | "POSTED" | "REJECTED" | "REVERSED" | "REQUIRES_REVIEW"; journalFinancialYearId?: string | null; journalPostingOn?: string | null };
export type RefundPostingContext = { schoolId: string; refundId: string; paymentId: string; receiptNumber: string; accountId: string; learnerName: string; academicSessionName: string; accountStatus: "ACTIVE" | "CLOSED"; currencyCode: string; balance: number; verifiedRefundAmount: number | null; sourceState: "READY" | "NO_VERIFIED_SUCCESS" | "REVIEW_REQUIRED" | "POSTED"; processedAtExact: string | null; processedOn: string | null; schoolTimeZone: string | null; originalDayClosed: boolean; canPost: boolean; posting: RefundPosting | null; canRepairJournal?: boolean };
export type RefundPostingYear = { id: string; name: string; startsOn: string; endsOn: string; currencyCode: string; status: "OPEN"; periods: { startsOn: string; endsOn: string; status: "OPEN" | "LOCKED" | "CLOSED" }[] };
export type RefundPostingAttempt = Readonly<{ kind?: undefined; schoolId: string; refundId: string; paymentId: string; accountId: string; amount: number; currencyCode: string; clientRequestId: string; postingOn: string; path: string; body: string }>;
export type RefundJournalRepair = { refundPostingId: string; refundId: string; previousBatchId: string; previousJournalId: string; accountingBatchId: string; journalId: string; journalStatus: "SUBMITTED" | "POSTED" | "REJECTED" | "REVERSED"; financialYearId: string; postingOn: string };
export type RefundJournalRepairAttempt = Readonly<Omit<RefundPostingAttempt,"kind"> & { kind: "JOURNAL_REPAIR"; refundPostingId: string; expectedBatchId: string; expectedJournalId: string; financialYearId: string }>;
export const refundPostingErrors = {
  unavailable: "Reviewed refund posting records are unavailable. Refresh or contact the school.",
  session: "Your session has ended. Sign in again to review refund posting.",
  scope: "You no longer have access to this refund posting.",
  books: "Eligible accounting books are unavailable. An authorized accountant must review the open financial year.",
  terms: "Choose an eligible open financial year, non-future posting date and reason of 3 to 500 characters.",
  eligibility: "Refresh the verified refund and original active fee account before posting.",
  csrf: "The secure form token is unavailable. Refresh and try again.",
  rejected: "This refund posting command was not accepted. Refresh the records before preparing another command.",
  unknown: "The refund posting outcome is unknown. Keep this page open and retry only this exact command; do not create another posting.",
  repairEligibility: "Refresh the completed refund and exact rejected or reversed journal before preparing its accounting-only correction.",
  repairUnknown: "The journal correction outcome is unknown. Keep this page open and retry only this exact correction; do not issue another refund.",
  repairRejected: "This journal correction was not accepted. Refresh the current refund journal before preparing another correction.",
} as const;
const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (value: unknown, max: number): value is string => typeof value === "string" && !!value.trim() && value.length <= max;
const date = (value: unknown): value is string => typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0,10) === value;
const instant = (value: unknown): value is string => typeof value === "string" && value.length <= 45 && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value));
const currency = (value: unknown): value is string => typeof value === "string" && /^[A-Z]{3}$/.test(value);
export function refundPostingPaise(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  const match = /^(-?)(0|[1-9]\d{0,12})(?:\.(\d{1,2}))?$/.exec(String(value));
  if (!match) return null;
  const paise = Number(match[2])*100 + Number((match[3] ?? "").padEnd(2,"0"));
  return Number.isSafeInteger(paise) ? (match[1] ? -paise : paise) : null;
}
const positive = (value: unknown) => (refundPostingPaise(value) ?? 0) > 0;
const fail = (): never => { throw new Error(refundPostingErrors.unavailable); };
export function refundPostingError(failure: unknown): string { return failure instanceof Error && Object.values(refundPostingErrors).some(value => value === failure.message) ? failure.message : refundPostingErrors.unavailable; }
export function refundPostingAccessLost(failure: unknown): boolean { return failure instanceof Error && [refundPostingErrors.session,refundPostingErrors.scope].some(value => value === failure.message); }
export function readRefundPosting(value: unknown, expected: { refundId: string; paymentId: string; accountId: string; amount?: number; currencyCode?: string; postingOn?: string }): RefundPosting {
  const row = value as RefundPosting | null;
  if (!row || ![row.id,row.refundId,row.paymentId,row.accountId,row.ledgerEntryId].every(value => uuid.test(value)) || row.refundId !== expected.refundId || row.paymentId !== expected.paymentId || row.accountId !== expected.accountId || !positive(row.amount) || !currency(row.currencyCode) || !date(row.postingOn) || !instant(row.postedAt) || !["DRAFT","SUBMITTED","POSTED","REJECTED","REVERSED","REQUIRES_REVIEW"].includes(row.journalStatus) || (row.accountingBatchId !== null && !uuid.test(row.accountingBatchId)) || (row.journalId !== null && !uuid.test(row.journalId)) || (row.journalStatus !== "REQUIRES_REVIEW" && (!row.accountingBatchId || !row.journalId)) || (expected.amount !== undefined && refundPostingPaise(row.amount) !== refundPostingPaise(expected.amount)) || (expected.currencyCode !== undefined && row.currencyCode !== expected.currencyCode) || (expected.postingOn !== undefined && row.postingOn !== expected.postingOn)) return fail();
  if ((row.journalPostingOn != null && (!date(row.journalPostingOn) || row.journalPostingOn < row.postingOn)) || (row.journalFinancialYearId != null && !uuid.test(row.journalFinancialYearId))) return fail();
  return row;
}
export function readRefundPostingContext(value: unknown, schoolId: string, refundId: string): RefundPostingContext {
  const row = value as RefundPostingContext | null;
  if (!uuid.test(schoolId) || !uuid.test(refundId) || !row || row.schoolId !== schoolId || row.refundId !== refundId || !uuid.test(row.paymentId) || !uuid.test(row.accountId) || !text(row.receiptNumber,160) || !text(row.learnerName,500) || !text(row.academicSessionName,200) || !["ACTIVE","CLOSED"].includes(row.accountStatus) || !currency(row.currencyCode) || refundPostingPaise(row.balance) === null || !["READY","NO_VERIFIED_SUCCESS","REVIEW_REQUIRED","POSTED"].includes(row.sourceState) || typeof row.canPost !== "boolean" || typeof row.originalDayClosed !== "boolean" || (row.verifiedRefundAmount !== null && !positive(row.verifiedRefundAmount)) || (row.processedAtExact !== null && !instant(row.processedAtExact)) || (row.processedOn !== null && !date(row.processedOn)) || (row.schoolTimeZone !== null && !text(row.schoolTimeZone,60))) return fail();
  if (["READY","POSTED"].includes(row.sourceState) && (!positive(row.verifiedRefundAmount) || !row.processedAtExact || !row.processedOn || !row.schoolTimeZone)) return fail();
  if (row.canPost && (row.sourceState !== "READY" || row.accountStatus !== "ACTIVE" || row.posting !== null)) return fail();
  if (row.sourceState === "POSTED") { if (row.posting === null || row.canPost) return fail(); readRefundPosting(row.posting,{refundId,paymentId:row.paymentId,accountId:row.accountId,amount:row.verifiedRefundAmount ?? undefined,currencyCode:row.currencyCode}); }
  else if (row.posting !== null) return fail();
  if (row.canRepairJournal !== undefined && typeof row.canRepairJournal !== "boolean") return fail();
  if (row.canRepairJournal && (row.sourceState !== "POSTED" || !row.posting?.accountingBatchId || !row.posting.journalId || !["REJECTED","REVERSED"].includes(row.posting.journalStatus))) return fail();
  return row;
}
async function responseValue(response: Response): Promise<unknown> {
  if (!response.ok) throw new Error(response.status === 401 ? refundPostingErrors.session : response.status === 403 ? refundPostingErrors.scope : refundPostingErrors.unavailable);
  return response.json();
}
export async function loadRefundPosting(schoolId: string, refundId: string, signal: AbortSignal): Promise<RefundPostingContext> {
  if (!uuid.test(schoolId) || !uuid.test(refundId)) return fail();
  return readRefundPostingContext(await responseValue(await fetch(`${base}/api/v1/finance/gateway/refunds/${refundId}/posting?${new URLSearchParams({schoolId})}`,{credentials:"include",cache:"no-store",signal})),schoolId,refundId);
}
/** Reuse the current accounting snapshot, discard all data except named book/period options. */
export async function loadRefundPostingYears(schoolId: string, signal: AbortSignal): Promise<RefundPostingYear[]> {
  if (!uuid.test(schoolId)) return fail();
  const response = await fetch(`${base}/api/v1/accounting?${new URLSearchParams({schoolId})}`,{credentials:"include",cache:"no-store",signal});
  if (response.status === 401) throw new Error(refundPostingErrors.session);
  if (!response.ok) throw new Error(refundPostingErrors.books);
  const value = await response.json() as { schoolId: string; financialYears: unknown[] };
  if (!value || value.schoolId !== schoolId || !Array.isArray(value.financialYears) || value.financialYears.length > 100) throw new Error(refundPostingErrors.books);
  const ids = new Set<string>(), result: RefundPostingYear[] = [];
  for (const item of value.financialYears) {
    const year = item as RefundPostingYear;
    if (!year || !uuid.test(year.id) || ids.has(year.id) || !text(year.name,160) || !date(year.startsOn) || !date(year.endsOn) || year.startsOn > year.endsOn || !currency(year.currencyCode) || !["DRAFT","OPEN","CLOSED"].includes(year.status) || !Array.isArray(year.periods) || year.periods.length > 24 || year.periods.some(period => !date(period.startsOn) || !date(period.endsOn) || period.startsOn > period.endsOn || !["OPEN","LOCKED","CLOSED"].includes(period.status))) throw new Error(refundPostingErrors.books);
    ids.add(year.id);
    if (year.status === "OPEN") result.push({id:year.id,name:year.name,startsOn:year.startsOn,endsOn:year.endsOn,currencyCode:year.currencyCode,status:"OPEN",periods:year.periods.map(period => ({startsOn:period.startsOn,endsOn:period.endsOn,status:period.status}))});
  }
  return result;
}
export function refundSchoolToday(timeZone: string | null, now = new Date()): string {
  if (!timeZone) throw new Error(refundPostingErrors.terms);
  try { return new Intl.DateTimeFormat("en-CA",{timeZone,year:"numeric",month:"2-digit",day:"2-digit"}).format(now); }
  catch { throw new Error(refundPostingErrors.terms); }
}
export function prepareRefundPosting(context: RefundPostingContext, years: RefundPostingYear[], financialYearId: string, postingOn: string, reason: string, clientRequestId: string, today: string): RefundPostingAttempt {
  readRefundPostingContext(context,context.schoolId,context.refundId);
  if (!context.canPost || context.sourceState !== "READY" || context.accountStatus !== "ACTIVE" || !positive(context.verifiedRefundAmount) || !uuid.test(clientRequestId)) throw new Error(refundPostingErrors.eligibility);
  const year = years.find(item => item.id === financialYearId);
  if (!year || year.status !== "OPEN" || year.currencyCode !== context.currencyCode || !date(postingOn) || !date(today) || postingOn > today || postingOn < (context.processedOn ?? "9999") || postingOn < year.startsOn || postingOn > year.endsOn || !year.periods.some(period => period.status === "OPEN" && postingOn >= period.startsOn && postingOn <= period.endsOn) || typeof reason !== "string" || /[\u0000-\u001f\u007f-\u009f]/.test(reason) || reason.trim().length < 3 || reason.trim().length > 500) throw new Error(refundPostingErrors.terms);
  return Object.freeze({schoolId:context.schoolId,refundId:context.refundId,paymentId:context.paymentId,accountId:context.accountId,amount:context.verifiedRefundAmount as number,currencyCode:context.currencyCode,clientRequestId,postingOn,path:`/api/v1/finance/gateway/refunds/${context.refundId}/posting`,body:JSON.stringify({schoolId:context.schoolId,clientRequestId,financialYearId,postingOn,reason:reason.trim()})});
}
export class RefundPostingWriteError extends Error {
  readonly outcome: "unknown" | "rejected";
  constructor(outcome: "unknown" | "rejected", message: string) { super(message); this.name="RefundPostingWriteError"; this.outcome=outcome; }
}

/** Reuses the existing economic refund; request bodies never contain an amount or a provider command. */
export function prepareRefundJournalRepair(context: RefundPostingContext, years: RefundPostingYear[], financialYearId: string, postingOn: string, reason: string, clientRequestId: string, today: string): RefundJournalRepairAttempt {
  readRefundPostingContext(context,context.schoolId,context.refundId);
  const posting=context.posting;
  if (!context.canRepairJournal || !posting?.accountingBatchId || !posting.journalId || !["REJECTED","REVERSED"].includes(posting.journalStatus) || !uuid.test(clientRequestId)) throw new Error(refundPostingErrors.repairEligibility);
  const year=years.find(item=>item.id===financialYearId), lower=posting.journalPostingOn ?? posting.postingOn;
  if (!year || year.status!=="OPEN" || year.currencyCode!==context.currencyCode || !date(postingOn) || !date(today) || postingOn>today || postingOn<lower || postingOn<year.startsOn || postingOn>year.endsOn || !year.periods.some(period=>period.status==="OPEN" && postingOn>=period.startsOn && postingOn<=period.endsOn) || typeof reason!=="string" || /[\u0000-\u001f\u007f-\u009f]/.test(reason) || reason.trim().length<3 || reason.trim().length>500) throw new Error(refundPostingErrors.terms);
  return Object.freeze({kind:"JOURNAL_REPAIR",schoolId:context.schoolId,refundId:context.refundId,refundPostingId:posting.id,paymentId:context.paymentId,accountId:context.accountId,amount:posting.amount,currencyCode:posting.currencyCode,expectedBatchId:posting.accountingBatchId,expectedJournalId:posting.journalId,financialYearId,clientRequestId,postingOn,path:`/api/v1/finance/gateway/refunds/${context.refundId}/posting/journal-repairs`,body:JSON.stringify({schoolId:context.schoolId,clientRequestId,expectedBatchId:posting.accountingBatchId,expectedJournalId:posting.journalId,financialYearId,postingOn,reason:reason.trim()})});
}
export function readRefundJournalRepair(value: unknown, attempt: RefundJournalRepairAttempt): RefundJournalRepair {
  const row=value as RefundJournalRepair | null;
  if (!row || ![row.refundPostingId,row.refundId,row.previousBatchId,row.previousJournalId,row.accountingBatchId,row.journalId,row.financialYearId].every(value=>typeof value==="string" && uuid.test(value)) || row.refundPostingId!==attempt.refundPostingId || row.refundId!==attempt.refundId || row.previousBatchId!==attempt.expectedBatchId || row.previousJournalId!==attempt.expectedJournalId || row.accountingBatchId===row.previousBatchId || row.journalId===row.previousJournalId || row.financialYearId!==attempt.financialYearId || row.postingOn!==attempt.postingOn || !["SUBMITTED","POSTED","REJECTED","REVERSED"].includes(row.journalStatus)) return fail();
  return row;
}
export async function sendRefundJournalRepair(attempt: RefundJournalRepairAttempt, csrf: string): Promise<RefundJournalRepair> {
  if (!csrf) throw new RefundPostingWriteError("rejected",refundPostingErrors.csrf);
  let response: Response;
  try { response=await fetch(`${base}${attempt.path}`,{method:"POST",credentials:"include",cache:"no-store",headers:{"Content-Type":"application/json","X-CSRF-TOKEN":csrf},body:attempt.body}); }
  catch { throw new RefundPostingWriteError("unknown",refundPostingErrors.repairUnknown); }
  if (!response.ok) throw new RefundPostingWriteError(response.status>=500 || response.status===408?"unknown":"rejected",response.status===401?refundPostingErrors.session:response.status===403?refundPostingErrors.scope:response.status>=500 || response.status===408?refundPostingErrors.repairUnknown:refundPostingErrors.repairRejected);
  try { return readRefundJournalRepair(await response.json(),attempt); }
  catch { throw new RefundPostingWriteError("unknown",refundPostingErrors.repairUnknown); }
}
export async function sendRefundPosting(attempt: RefundPostingAttempt, csrf: string): Promise<RefundPosting> {
  if (!csrf) throw new RefundPostingWriteError("rejected",refundPostingErrors.csrf);
  let response: Response;
  try { response=await fetch(`${base}${attempt.path}`,{method:"POST",credentials:"include",cache:"no-store",headers:{"Content-Type":"application/json","X-CSRF-TOKEN":csrf},body:attempt.body}); }
  catch { throw new RefundPostingWriteError("unknown",refundPostingErrors.unknown); }
  if (!response.ok) throw new RefundPostingWriteError(response.status >= 500 || response.status === 408 ? "unknown" : "rejected",response.status === 401 ? refundPostingErrors.session : response.status === 403 ? refundPostingErrors.scope : response.status >= 500 || response.status === 408 ? refundPostingErrors.unknown : refundPostingErrors.rejected);
  try { return readRefundPosting(await response.json(),attempt); }
  catch { throw new RefundPostingWriteError("unknown",refundPostingErrors.unknown); }
}
