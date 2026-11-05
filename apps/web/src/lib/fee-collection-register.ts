import type { FeeReceiptTiming } from "./fee-receipt-timing";

export const collectionMethods = ["CASH", "SCHOOL_UPI", "BANK_TRANSFER", "CHEQUE", "ONLINE_PROVIDER"] as const;
export type CollectionMethod = typeof collectionMethods[number];
export type CollectionFilter = { schoolId: string; from: string; to: string; currencyCode: string; paymentMethod: CollectionMethod | ""; query: string };
export type CollectionSummary = { collectionCount: number; grossCollections: number; reversalCount: number; reversalAmount: number; netCollections: number; refundCount: number; refundAmount: number };
export type CollectionRefundTiming = { processedOn: string; processedAt: string; schoolTimeZone: string };
export type CollectionMovement = { id: string; kind: "COLLECTION" | "REVERSAL" | "REFUND"; paymentId: string; accountId: string; learnerId: string; learnerName: string; admissionNumber: string | null; gradeCode: string; sessionName: string; receiptNumber: string; paymentMethod: CollectionMethod; receiptStatus: "POSTED" | "REVERSED"; amount: number; postingOn: string; recordedAt: string; timing: FeeReceiptTiming; refundTiming: CollectionRefundTiming | null };
export type CollectionRegister = Omit<CollectionFilter, "paymentMethod" | "query"> & { paymentMethod: CollectionMethod | null; query: string | null; page: number; pageSize: number; total: number; hasMore: boolean; summary: CollectionSummary; tenderTotals: { paymentMethod: CollectionMethod; summary: CollectionSummary }[]; items: CollectionMovement[] };
export type CollectionExport = Omit<CollectionRegister, "page" | "pageSize" | "hasMore"> & { schoolName: string; generatedAt: string };
export const collectionRegisterErrors = {
  unavailable: "The collection register is unavailable. Refresh or contact the school.",
  session: "Your session has ended. Sign in again to read collections.",
  scope: "You no longer have financial access to this school.",
  filters: "Choose a valid posting-date range of at most 366 days, currency and payment method.",
  exportLimit: "Narrow the collection range to at most 10,000 movements before downloading or printing.",
} as const;
/** A signed declaration is exceptional, not a general negative-money input. Server rechecks the chosen date. */
export function refundDayCloseMinimum(paymentMethod:string,systemAmount:number,refundCount:number):number {
  return paymentMethod === "ONLINE_PROVIDER" && Number.isSafeInteger(refundCount) && refundCount > 0
    && typeof systemAmount === "number" && Number.isFinite(systemAmount) && systemAmount < 0 ? Math.max(systemAmount,-100000000) : 0;
}
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const fail = (): never => { throw new Error(collectionRegisterErrors.unavailable); };
const date = (value: unknown): value is string => typeof value === "string" && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().startsWith(value);
const text = (value: unknown, max = 240): value is string => typeof value === "string" && value.length > 0 && value.length <= max;
const method = (value: unknown): value is CollectionMethod => collectionMethods.some(item => item === value);
const instant = (value: unknown): value is string => typeof value === "string" && value.length <= 40 && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value));
/** Totals may exceed one fee line, but must remain exact safe integer paise. */
function paise(value: number): number {
  if (typeof value !== "number" || !Number.isFinite(value)) return fail();
  const match = /^(-?)([0-9]{1,14})(?:\.([0-9]{1,2}))?$/.exec(String(value));
  if (!match) return fail();
  const result = (Number(match[2]) * 100 + Number((match[3] ?? "").padEnd(2,"0"))) * (match[1] ? -1 : 1);
  return Number.isSafeInteger(result) ? result : fail();
}
function summary(value: CollectionSummary): void {
  if (!value || !Number.isSafeInteger(value.collectionCount) || value.collectionCount < 0 || !Number.isSafeInteger(value.reversalCount) || value.reversalCount < 0
    || !Number.isSafeInteger(value.refundCount) || value.refundCount < 0 || paise(value.refundAmount) < 0
    || paise(value.grossCollections) < 0 || paise(value.reversalAmount) < 0
    || (value.collectionCount === 0 ? paise(value.grossCollections) !== 0 : paise(value.grossCollections) === 0)
    || (value.reversalCount === 0 ? paise(value.reversalAmount) !== 0 : paise(value.reversalAmount) === 0)
    || (value.refundCount === 0 ? paise(value.refundAmount) !== 0 : paise(value.refundAmount) === 0)
    || paise(value.grossCollections) - paise(value.reversalAmount) - paise(value.refundAmount) !== paise(value.netCollections)) return fail();
}
export function collectionFilter(filter: CollectionFilter, page = 0, pageSize = 25): CollectionFilter {
  if (!uuid.test(filter.schoolId) || !date(filter.from) || !date(filter.to) || filter.from.startsWith("0000") || filter.to < filter.from
    || (Date.parse(filter.to) - Date.parse(filter.from)) / 86400000 > 365 || !/^[A-Z]{3}$/.test(filter.currencyCode)
    || (filter.paymentMethod !== "" && !method(filter.paymentMethod)) || typeof filter.query !== "string" || filter.query.length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(filter.query)
    || !Number.isInteger(page) || page < 0 || page > 10000 || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) throw new Error(collectionRegisterErrors.filters);
  return { ...filter, query: filter.query.trim() };
}
export function readCollectionRegister(value: unknown, filter: CollectionFilter, page = 0, pageSize = 25): CollectionRegister {
  return readRegister(value,collectionFilter(filter,page,pageSize),page,pageSize);
}
function readRegister(value: unknown, expected: CollectionFilter, page: number, pageSize: number): CollectionRegister {
  const result = value as CollectionRegister | null;
  if (!result || result.schoolId !== expected.schoolId || result.from !== expected.from || result.to !== expected.to || result.currencyCode !== expected.currencyCode
    || result.paymentMethod !== (expected.paymentMethod || null) || result.query !== (expected.query || null) || result.page !== page || result.pageSize !== pageSize
    || !Number.isSafeInteger(result.total) || result.total < 0 || typeof result.hasMore !== "boolean" || !Array.isArray(result.items)
    || result.items.length !== Math.min(pageSize,Math.max(0,result.total - page * pageSize))
    || result.hasMore !== (page * pageSize + result.items.length < result.total)
    || !Array.isArray(result.tenderTotals) || result.tenderTotals.length !== 5) return fail();
  summary(result.summary);
  if (result.summary.collectionCount + result.summary.reversalCount + result.summary.refundCount !== result.total) return fail();
  const methods = new Set<string>(); let count = 0, gross = 0, reversals = 0, reversed = 0, refunds = 0, refunded = 0;
  for (const tender of result.tenderTotals) {
    if (!tender || !method(tender.paymentMethod) || methods.has(tender.paymentMethod)) return fail();
    summary(tender.summary); methods.add(tender.paymentMethod);
    count += tender.summary.collectionCount; gross += paise(tender.summary.grossCollections);
    reversals += tender.summary.reversalCount; reversed += paise(tender.summary.reversalAmount);
    refunds += tender.summary.refundCount; refunded += paise(tender.summary.refundAmount);
    if (tender.paymentMethod !== "ONLINE_PROVIDER" && (tender.summary.refundCount || tender.summary.refundAmount)) return fail();
    if (expected.paymentMethod && tender.paymentMethod !== expected.paymentMethod && (tender.summary.collectionCount || tender.summary.reversalCount || tender.summary.grossCollections || tender.summary.reversalAmount || tender.summary.refundCount || tender.summary.refundAmount)) return fail();
  }
  if (![count,gross,reversals,reversed,refunds,refunded].every(Number.isSafeInteger) || count !== result.summary.collectionCount || reversals !== result.summary.reversalCount || gross !== paise(result.summary.grossCollections) || reversed !== paise(result.summary.reversalAmount) || refunds !== result.summary.refundCount || refunded !== paise(result.summary.refundAmount)) return fail();
  const ids = new Set<string>();
  for (const item of result.items) {
    if (!item || !uuid.test(item.id) || !uuid.test(item.paymentId) || !uuid.test(item.accountId) || !uuid.test(item.learnerId) || !["COLLECTION","REVERSAL","REFUND"].includes(item.kind)
      || ids.has(`${item.kind}:${item.id}`) || !text(item.learnerName,120) || (item.admissionNumber !== null && !text(item.admissionNumber,100))
      || !text(item.gradeCode,40) || !text(item.sessionName,120) || !text(item.receiptNumber,120) || !method(item.paymentMethod)
      || (expected.paymentMethod && item.paymentMethod !== expected.paymentMethod) || !["POSTED","REVERSED"].includes(item.receiptStatus)
      || (item.kind === "REVERSAL" && item.receiptStatus !== "REVERSED")
      || (item.kind === "REFUND" && (item.paymentMethod !== "ONLINE_PROVIDER" || item.receiptStatus !== "POSTED")) || paise(item.amount) < 1 || item.amount > 100000000
      || !date(item.postingOn) || item.postingOn < expected.from || item.postingOn > expected.to || !instant(item.recordedAt)
      || !item.timing || !date(item.timing.originalReceivedOn) || !["INSTANT","DATE_ONLY"].includes(item.timing.receivedTimePrecision)
      || (item.timing.receivedTimePrecision === "DATE_ONLY" ? item.timing.actualReceivedAt !== null : !instant(item.timing.actualReceivedAt))
      || (item.timing.schoolTimeZone !== null && !text(item.timing.schoolTimeZone,100))) return fail();
    if (item.kind === "REFUND" ? (!item.refundTiming || !date(item.refundTiming.processedOn)
      || item.refundTiming.processedOn > item.postingOn || !instant(item.refundTiming.processedAt)
      || !text(item.refundTiming.schoolTimeZone,100)) : item.refundTiming != null) return fail();
    ids.add(`${item.kind}:${item.id}`);
  }
  return result;
}
export function readCollectionExport(value: unknown, filter: CollectionFilter): CollectionExport {
  const expected=collectionFilter(filter), result=value as CollectionExport | null;
  if (!result || !text(result.schoolName,240) || !instant(result.generatedAt)
    || !Number.isSafeInteger(result.total) || result.total<0 || result.total>10000) return fail();
  readRegister({...result,page:0,pageSize:Math.max(1,result.total),hasMore:false},expected,0,Math.max(1,result.total));
  for (const tender of result.tenderTotals) {
    const rows=result.items.filter(item=>item.paymentMethod===tender.paymentMethod);
    const collections=rows.filter(item=>item.kind==="COLLECTION"), reversals=rows.filter(item=>item.kind==="REVERSAL"), refunds=rows.filter(item=>item.kind==="REFUND");
    const gross=collections.reduce((sum,item)=>sum+paise(item.amount),0), reversed=reversals.reduce((sum,item)=>sum+paise(item.amount),0), refunded=refunds.reduce((sum,item)=>sum+paise(item.amount),0);
    if (!Number.isSafeInteger(gross) || !Number.isSafeInteger(reversed) || !Number.isSafeInteger(refunded) || collections.length!==tender.summary.collectionCount
      || reversals.length!==tender.summary.reversalCount || gross!==paise(tender.summary.grossCollections) || reversed!==paise(tender.summary.reversalAmount)
      || refunds.length!==tender.summary.refundCount || refunded!==paise(tender.summary.refundAmount)) return fail();
  }
  return result;
}
export async function loadCollectionExport(filter: CollectionFilter, signal: AbortSignal): Promise<CollectionExport> {
  const checked=collectionFilter(filter), params=new URLSearchParams({schoolId:checked.schoolId,from:checked.from,to:checked.to,currencyCode:checked.currencyCode});
  if (checked.paymentMethod) params.set("paymentMethod",checked.paymentMethod);
  if (checked.query) params.set("q",checked.query);
  const response=await fetch(`${apiUrl}/api/v1/finance/collections/export?${params}`,{credentials:"include",cache:"no-store",signal});
  if (!response.ok) {
    if (response.status===400) {
      const problem=await response.json().catch(()=>null) as {code?:string} | null;
      throw new Error(problem?.code==="COLLECTION_REGISTER_EXPORT_TOO_LARGE" ? collectionRegisterErrors.exportLimit : collectionRegisterErrors.filters);
    }
    throw new Error(response.status===401 ? collectionRegisterErrors.session : response.status===403 ? collectionRegisterErrors.scope : collectionRegisterErrors.unavailable);
  }
  return readCollectionExport(await response.json(),checked);
}
export async function loadCollectionRegister(filter: CollectionFilter, page: number, signal: AbortSignal): Promise<CollectionRegister> {
  const checked = collectionFilter(filter,page), params = new URLSearchParams({schoolId:checked.schoolId,from:checked.from,to:checked.to,currencyCode:checked.currencyCode,page:String(page),pageSize:"25"});
  if (checked.paymentMethod) params.set("paymentMethod",checked.paymentMethod);
  if (checked.query) params.set("q",checked.query);
  const response = await fetch(`${apiUrl}/api/v1/finance/collections?${params}`, { credentials:"include",cache:"no-store",signal });
  if (!response.ok) throw new Error(response.status === 401 ? collectionRegisterErrors.session : response.status === 403 ? collectionRegisterErrors.scope : response.status === 400 ? collectionRegisterErrors.filters : collectionRegisterErrors.unavailable);
  return readCollectionRegister(await response.json(),checked,page);
}
export function collectionRegisterError(reason: unknown): string {
  return reason instanceof Error && Object.values(collectionRegisterErrors).some(value => value === reason.message) ? reason.message : collectionRegisterErrors.unavailable;
}
