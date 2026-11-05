/** Human-scoped report reads only. No payment, refund, receipt or ledger authority. */
export const settlementStates = ["REQUESTED", "SYNCING", "RETRYING", "COMPLETE", "REVIEW_REQUIRED"] as const;
export const settlementKinds = ["SUMMARY", "EVENTS"] as const;
export type SettlementState = typeof settlementStates[number];
export type SettlementKind = typeof settlementKinds[number];
export type SettlementFilter = { schoolId: string; status: SettlementState | "ALL"; kind: SettlementKind | "ALL"; query: string; page: number };
export type SettlementRequest = { id: string; kind: SettlementKind; schoolTimeZone: string; status: SettlementState; from: string | null; until: string | null; sourceSummaryReadId: string | null; settlementReference: string | null; requestedAt: string; requestedBy: string; pagesCompleted: number; pagesPending: number; pagesReview: number; lastCheckedAt: string | null; nextCheckAt: string | null };
export type SettlementQueue = { schoolId: string; schoolTimeZone: string; syncEnabled: boolean; canSync: boolean; status: SettlementFilter["status"]; kind: SettlementFilter["kind"]; query: string; page: number; pageSize: number; total: number; hasMore: boolean; items: SettlementRequest[] };
export type SettlementSummary = { settlementReference: string; reportedStatus: string; transferReference: string | null; paymentsFrom: string | null; paymentsThrough: string | null; initiatedAt: string | null; processedAt: string | null; reportedPaymentAmount: string | null; reportedServiceCharge: string | null; reportedServiceTax: string | null; reportedAdjustment: string | null; reportedSettlementCharge: string | null; reportedSettlementTax: string | null; reportedSplitCharge: string | null; reportedSplitTax: string | null; reportedVendorCommission: string | null; reportedAmountSettled: string | null; reportedChargeType: string | null; reportedSettlementType: string | null };
export type SettlementEvent = { settlementReference: string; eventReference: string; reportedType: string; reportedDirection: string; reportedStatus: string; currencyCode: string; reportedAmount: string; reportedServiceCharge: string | null; reportedServiceTax: string | null; reportedSettlementAmount: string | null; occurredAt: string; orderReference: string | null; paymentReference: string | null; refundReferences: string[]; chargesCurrencyCode: string | null };
export type SettlementRow = { sourceReadId: string; rowIndex: number; observedAt: string; summary: SettlementSummary | null; event: SettlementEvent | null };
export type SettlementReport = { schoolId: string; request: SettlementRequest; query: string; page: number; pageSize: number; total: number; hasMore: boolean; items: SettlementRow[] };
export type SettlementResult = { id: string; schoolId: string; kind: SettlementKind };
export type SettlementAttempt = Readonly<{ schoolId: string; clientRequestId: string; kind: SettlementKind; body: string }>;
export type SettlementErrorCode = "unavailable" | "scope" | "session" | "filters" | "dates" | "csrf" | "conflict" | "rejected" | "unknown";
export class SettlementError extends Error { readonly code: SettlementErrorCode; constructor(code: SettlementErrorCode) { super(`SETTLEMENT_${code.toUpperCase()}`); this.name = "SettlementError"; this.code = code; } }
export class SettlementWriteError extends SettlementError { readonly outcome: "unknown" | "rejected"; constructor(outcome: "unknown" | "rejected", code: SettlementErrorCode) { super(code); this.name = "SettlementWriteError"; this.outcome = outcome; } }
export function settlementErrorCode(reason: unknown): SettlementErrorCode { return reason instanceof SettlementError ? reason.code : "unavailable"; }

const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const path = "/api/v1/finance/gateway/settlements";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const reference = /^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/;
const tag = /^[A-Z][A-Z0-9_]{0,59}$/;
const currency = /^[A-Z]{3}$/;
const fail = (): never => { throw new SettlementError("unavailable"); };
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === "object" && !Array.isArray(value);
const keys = (value: unknown, names: readonly string[]): boolean => record(value) && Object.keys(value).length === names.length && Object.keys(value).every(key => names.includes(key));
const safeText = (value: unknown, max: number): value is string => typeof value === "string" && value.length > 0 && value.length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
const count = (value: unknown, max = 1000): value is number => typeof value === "number" && Number.isInteger(value) && value >= 0 && value <= max;
const nullable = (value: unknown, check: (item: unknown) => boolean): boolean => value === null || check(value);
const isRef = (value: unknown): value is string => typeof value === "string" && reference.test(value);
const isTag = (value: unknown): value is string => typeof value === "string" && tag.test(value);
const isCurrency = (value: unknown): value is string => typeof value === "string" && currency.test(value);
const isUuid = (value: unknown): value is string => typeof value === "string" && uuid.test(value);

function instant(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{3}(?:\d{3}){0,2})?Z$/.exec(value);
  if (!match) return false;
  const [,y,m,d,h,min,s] = match, date = new Date(Date.UTC(Number(y),Number(m)-1,Number(d),Number(h),Number(min),Number(s)));
  return Number(y) >= 2000 && Number(y) <= 2100 && date.getUTCFullYear() === Number(y) && date.getUTCMonth() === Number(m)-1 && date.getUTCDate() === Number(d) && date.getUTCHours() === Number(h) && date.getUTCMinutes() === Number(min) && date.getUTCSeconds() === Number(s);
}
function instantNanos(value: string): bigint {
  if (!instant(value)) return fail();
  const whole = value.slice(0,19) + "Z", fraction = value.includes(".") ? value.slice(20,-1) : "";
  return BigInt(Date.parse(whole)) * 1000000n + BigInt(fraction.padEnd(9,"0"));
}
function zone(value: unknown): value is string {
  if (!safeText(value,60)) return false;
  try { new Intl.DateTimeFormat("en",{timeZone:value}).format(0); return true; } catch { return false; }
}
/** Provider precision is not posted paise. No Number conversion or rounding. */
export function reportedSettlementMoney(value: unknown): value is string {
  if (typeof value !== "string" || !/^-?(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,8})?$/.test(value)) return false;
  const negative = value.startsWith("-"), [whole,fraction = ""] = (negative ? value.slice(1) : value).split(".");
  const exact = BigInt(whole!) * 100000000n + BigInt(fraction.padEnd(8,"0"));
  return exact <= 10000000000000000n && (!negative || exact !== 0n);
}
export function formatSettlementAmount(value: string | null, locale: "en" | "hi"): string {
  if (value === null) return "—";
  if (!reportedSettlementMoney(value)) return fail();
  const number = new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN"), negative = value.startsWith("-");
  const [whole,fraction] = (negative ? value.slice(1) : value).split(".");
  const sign = negative ? number.formatToParts(-1).find(part=>part.type === "minusSign")?.value ?? "-" : "";
  const decimal = number.formatToParts(1.1).find(part=>part.type === "decimal")?.value ?? ".";
  return sign + number.format(BigInt(whole!)) + (fraction === undefined ? "" : decimal + fraction);
}

export function settlementFilter(value: SettlementFilter): SettlementFilter {
  if (!isUuid(value.schoolId) || !["ALL",...settlementStates].includes(value.status) || !["ALL",...settlementKinds].includes(value.kind) || typeof value.query !== "string" || value.query.length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(value.query) || !count(value.page,10000)) throw new SettlementError("filters");
  return {...value,query:value.query.trim()};
}
function readRequest(value: unknown): SettlementRequest {
  if (!keys(value,["id","kind","schoolTimeZone","status","from","until","sourceSummaryReadId","settlementReference","requestedAt","requestedBy","pagesCompleted","pagesPending","pagesReview","lastCheckedAt","nextCheckAt"])) return fail();
  const row = value as SettlementRequest;
  if (!isUuid(row.id) || !settlementKinds.includes(row.kind) || !settlementStates.includes(row.status) || !zone(row.schoolTimeZone) || !instant(row.requestedAt) || !safeText(row.requestedBy,320) || ![row.pagesCompleted,row.pagesPending,row.pagesReview].every(value=>count(value)) || row.pagesCompleted + row.pagesPending + row.pagesReview < 1 || row.pagesCompleted + row.pagesPending + row.pagesReview > 1000 || !nullable(row.lastCheckedAt,instant) || !nullable(row.nextCheckAt,instant)) return fail();
  if (row.kind === "SUMMARY") {
    if (!instant(row.from) || !instant(row.until) || instantNanos(row.from) >= instantNanos(row.until) || instantNanos(row.until)-instantNanos(row.from) > 2678400000000000n || row.sourceSummaryReadId !== null || row.settlementReference !== null) return fail();
  } else if (row.from !== null || row.until !== null || !isUuid(row.sourceSummaryReadId) || !isRef(row.settlementReference)) return fail();
  if (row.status === "COMPLETE" && (row.pagesCompleted < 1 || row.pagesPending !== 0 || row.pagesReview !== 0 || row.nextCheckAt !== null || row.lastCheckedAt === null)) return fail();
  if (["REQUESTED","SYNCING","RETRYING"].includes(row.status) && row.pagesPending < 1) return fail();
  if (row.status === "REVIEW_REQUIRED" && row.pagesReview < 1) return fail();
  return row;
}
function paging(value: {page:number;pageSize:number;total:number;hasMore:boolean;items:unknown[]}, expectedPage: number): void {
  if (value.page !== expectedPage || value.pageSize !== 25 || !Number.isSafeInteger(value.total) || value.total < 0 || !Array.isArray(value.items) || value.items.length !== Math.min(25,Math.max(0,value.total - expectedPage*25)) || value.hasMore !== (expectedPage*25 + value.items.length < value.total)) return fail();
}
export function readSettlementQueue(value: unknown, expected: SettlementFilter): SettlementQueue {
  const filter = settlementFilter(expected);
  if (!keys(value,["schoolId","schoolTimeZone","syncEnabled","canSync","status","kind","query","page","pageSize","total","hasMore","items"])) return fail();
  const queue = value as SettlementQueue;
  if (queue.schoolId !== filter.schoolId || !zone(queue.schoolTimeZone) || typeof queue.syncEnabled !== "boolean" || typeof queue.canSync !== "boolean" || queue.canSync && !queue.syncEnabled || queue.status !== filter.status || queue.kind !== filter.kind || queue.query !== filter.query) return fail();
  paging(queue,filter.page); const ids = new Set<string>();
  for (const value of queue.items) { const row = readRequest(value); if (ids.has(row.id) || filter.status !== "ALL" && row.status !== filter.status || filter.kind !== "ALL" && row.kind !== filter.kind) return fail(); ids.add(row.id); }
  return queue;
}
const summaryKeys = ["settlementReference","reportedStatus","transferReference","paymentsFrom","paymentsThrough","initiatedAt","processedAt","reportedPaymentAmount","reportedServiceCharge","reportedServiceTax","reportedAdjustment","reportedSettlementCharge","reportedSettlementTax","reportedSplitCharge","reportedSplitTax","reportedVendorCommission","reportedAmountSettled","reportedChargeType","reportedSettlementType"] as const;
const eventKeys = ["settlementReference","eventReference","reportedType","reportedDirection","reportedStatus","currencyCode","reportedAmount","reportedServiceCharge","reportedServiceTax","reportedSettlementAmount","occurredAt","orderReference","paymentReference","refundReferences","chargesCurrencyCode"] as const;
function readSummary(value: unknown): SettlementSummary {
  if (!keys(value,summaryKeys)) return fail(); const row = value as SettlementSummary;
  if (!isRef(row.settlementReference) || !isTag(row.reportedStatus) || !nullable(row.transferReference,value=>typeof value === "string" && /^[A-Za-z0-9][A-Za-z0-9_.:/\-]{0,119}$/.test(value)) || ![row.paymentsFrom,row.paymentsThrough,row.initiatedAt,row.processedAt].every(value=>nullable(value,instant)) || ![row.reportedPaymentAmount,row.reportedServiceCharge,row.reportedServiceTax,row.reportedAdjustment,row.reportedSettlementCharge,row.reportedSettlementTax,row.reportedSplitCharge,row.reportedSplitTax,row.reportedVendorCommission,row.reportedAmountSettled].every(value=>nullable(value,reportedSettlementMoney)) || ![row.reportedChargeType,row.reportedSettlementType].every(value=>nullable(value,isTag)) || row.paymentsFrom && row.paymentsThrough && instantNanos(row.paymentsFrom) > instantNanos(row.paymentsThrough)) return fail();
  return row;
}
function readEvent(value: unknown): SettlementEvent {
  if (!keys(value,eventKeys)) return fail(); const row = value as SettlementEvent;
  if (!isRef(row.settlementReference) || !isRef(row.eventReference) || ![row.reportedType,row.reportedDirection,row.reportedStatus].every(isTag) || !isCurrency(row.currencyCode) || !reportedSettlementMoney(row.reportedAmount) || ![row.reportedServiceCharge,row.reportedServiceTax,row.reportedSettlementAmount].every(value=>nullable(value,reportedSettlementMoney)) || !instant(row.occurredAt) || !nullable(row.orderReference,isRef) || !nullable(row.paymentReference,isRef) || !Array.isArray(row.refundReferences) || row.refundReferences.length > 100 || !row.refundReferences.every(isRef) || !nullable(row.chargesCurrencyCode,isCurrency)) return fail();
  return row;
}
export { readSummary as readReportedSettlementSummary, readEvent as readReportedSettlementEvent };
export function readSettlementReport(value: unknown, schoolId: string, requestId: string, query: string, page: number): SettlementReport {
  const filter = settlementFilter({schoolId,status:"ALL",kind:"ALL",query,page});
  if (!isUuid(requestId) || !keys(value,["schoolId","request","query","page","pageSize","total","hasMore","items"])) return fail();
  const report = value as SettlementReport;
  if (report.schoolId !== schoolId || readRequest(report.request).id !== requestId || report.query !== filter.query) return fail();
  paging(report,page); const positions = new Set<string>();
  for (const value of report.items) {
    if (!keys(value,["sourceReadId","rowIndex","observedAt","summary","event"])) return fail();
    const row = value as SettlementRow, identity = `${row.sourceReadId}:${row.rowIndex}`;
    if (!isUuid(row.sourceReadId) || !count(row.rowIndex,9) || !instant(row.observedAt) || positions.has(identity)) return fail();
    if (report.request.kind === "SUMMARY" ? row.event !== null || !row.summary : row.summary !== null || !row.event) return fail();
    if (row.summary) readSummary(row.summary);
    if (row.event && readEvent(row.event).settlementReference !== report.request.settlementReference) return fail();
    positions.add(identity);
  }
  return report;
}

function formatter(timeZone: string): Intl.DateTimeFormat { if (!zone(timeZone)) throw new SettlementError("dates"); return new Intl.DateTimeFormat("en-CA-u-nu-latn",{timeZone,year:"numeric",month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit",second:"2-digit",hourCycle:"h23"}); }
function localParts(date: number, format: Intl.DateTimeFormat): {year:number;month:number;day:number;hour:number;minute:number;second:number} {
  const parts = Object.fromEntries(format.formatToParts(date).filter(part=>part.type !== "literal").map(part=>[part.type,Number(part.value)]));
  return {year:parts.year ?? NaN,month:parts.month ?? NaN,day:parts.day ?? NaN,hour:parts.hour ?? NaN,minute:parts.minute ?? NaN,second:parts.second ?? NaN};
}
function calendarDate(value: string): number {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new SettlementError("dates");
  const year = Number(value.slice(0,4)), month = Number(value.slice(5,7)), day = Number(value.slice(8,10)), result = Date.UTC(year,month-1,day), date = new Date(result);
  if (year < 2000 || year > 2100 || date.getUTCFullYear() !== year || date.getUTCMonth() !== month-1 || date.getUTCDate() !== day) throw new SettlementError("dates");
  return result;
}
function schoolMidnight(day: string, timeZone: string): number {
  const target = calendarDate(day), format = formatter(timeZone), offsets = new Set<number>();
  // Observe both sides of local transitions. Skipped or repeated midnight is
  // explicitly refused, never silently normalized or guessed in the browser zone.
  for (let hours=-36;hours<=36;hours+=6) { const sample = target + hours*3600000, p = localParts(sample,format); offsets.add(Date.UTC(p.year,p.month-1,p.day,p.hour,p.minute,p.second)-sample); }
  const candidates = [...offsets].map(offset=>target-offset).filter(candidate=>{const p=localParts(candidate,format);return Date.UTC(p.year,p.month-1,p.day,p.hour,p.minute,p.second)===target;});
  const candidate = candidates[0];
  if (candidates.length !== 1 || candidate === undefined) throw new SettlementError("dates"); return candidate;
}
export function settlementSchoolDate(timeZone: string, now = new Date()): string {
  if (!Number.isFinite(now.getTime())) throw new SettlementError("dates");
  const p = localParts(now.getTime(),formatter(timeZone)); return `${p.year.toString().padStart(4,"0")}-${p.month.toString().padStart(2,"0")}-${p.day.toString().padStart(2,"0")}`;
}
function authority(queue: SettlementQueue, clientRequestId: string): void {
  if (!isUuid(queue.schoolId) || !isUuid(clientRequestId) || !queue.canSync || !queue.syncEnabled || !zone(queue.schoolTimeZone)) throw new SettlementError("scope");
}
export function prepareSettlementSummary(queue: SettlementQueue, fromDay: string, throughDay: string, clientRequestId: string, now = new Date()): SettlementAttempt {
  authority(queue,clientRequestId); const fromDate = calendarDate(fromDay), throughDate = calendarDate(throughDay), today = settlementSchoolDate(queue.schoolTimeZone,now);
  if (fromDate > throughDate || throughDay > today) throw new SettlementError("dates");
  const from = schoolMidnight(fromDay,queue.schoolTimeZone), nextDay = new Date(throughDate+86400000).toISOString().slice(0,10);
  const until = throughDay === today ? now.getTime() : schoolMidnight(nextDay,queue.schoolTimeZone);
  if (from >= until || until-from > 31*86400000 || until > now.getTime()) throw new SettlementError("dates");
  return Object.freeze({schoolId:queue.schoolId,clientRequestId,kind:"SUMMARY",body:JSON.stringify({schoolId:queue.schoolId,clientRequestId,schoolTimeZone:queue.schoolTimeZone,kind:"SUMMARY",from:new Date(from).toISOString(),until:new Date(until).toISOString(),sourceSummaryReadId:null,settlementReference:null})});
}
export function prepareSettlementEvents(queue: SettlementQueue, row: SettlementRow, clientRequestId: string): SettlementAttempt {
  authority(queue,clientRequestId); if (!isUuid(row.sourceReadId) || !row.summary || row.event !== null || !count(row.rowIndex,9)) throw new SettlementError("scope");
  readSummary(row.summary);
  return Object.freeze({schoolId:queue.schoolId,clientRequestId,kind:"EVENTS",body:JSON.stringify({schoolId:queue.schoolId,clientRequestId,schoolTimeZone:queue.schoolTimeZone,kind:"EVENTS",from:null,until:null,sourceSummaryReadId:row.sourceReadId,settlementReference:row.summary.settlementReference})});
}
async function readResponse(response: Response): Promise<unknown> {
  if (!response.ok) throw new SettlementError(response.status === 401 ? "session" : response.status === 403 ? "scope" : response.status === 400 ? "filters" : "unavailable");
  try { return await response.json(); } catch { return fail(); }
}
export async function loadSettlementQueue(value: SettlementFilter, signal: AbortSignal): Promise<SettlementQueue> {
  const filter = settlementFilter(value), params = new URLSearchParams({schoolId:filter.schoolId,status:filter.status,kind:filter.kind,page:String(filter.page),pageSize:"25"}); if (filter.query) params.set("q",filter.query);
  return readSettlementQueue(await readResponse(await fetch(`${base}${path}?${params}`,{credentials:"include",cache:"no-store",signal})),filter);
}
export async function loadSettlementReport(schoolId: string, requestId: string, query: string, page: number, signal: AbortSignal): Promise<SettlementReport> {
  const filter = settlementFilter({schoolId,status:"ALL",kind:"ALL",query,page}); if (!isUuid(requestId)) return fail();
  const params = new URLSearchParams({schoolId,page:String(page),pageSize:"25"}); if (filter.query) params.set("q",filter.query);
  return readSettlementReport(await readResponse(await fetch(`${base}${path}/requests/${requestId}?${params}`,{credentials:"include",cache:"no-store",signal})),schoolId,requestId,filter.query,page);
}
export async function sendSettlementSync(attempt: SettlementAttempt, csrf: string): Promise<SettlementResult> {
  if (!csrf) throw new SettlementWriteError("rejected","csrf");
  if (!isUuid(attempt.schoolId) || !isUuid(attempt.clientRequestId) || !settlementKinds.includes(attempt.kind)) throw new SettlementWriteError("rejected","rejected");
  try {
    if (typeof attempt.body !== "string" || attempt.body.length > 4096) throw new Error();
    const body: unknown = JSON.parse(attempt.body);
    if (!keys(body,["schoolId","clientRequestId","schoolTimeZone","kind","from","until","sourceSummaryReadId","settlementReference"]) || JSON.stringify(body) !== attempt.body) throw new Error();
    const command = body as {schoolId:string;clientRequestId:string;schoolTimeZone:string;kind:SettlementKind;from:string|null;until:string|null;sourceSummaryReadId:string|null;settlementReference:string|null};
    if (command.schoolId !== attempt.schoolId || command.clientRequestId !== attempt.clientRequestId || command.kind !== attempt.kind || !zone(command.schoolTimeZone)) throw new Error();
    if (command.kind === "SUMMARY" ? !instant(command.from) || !instant(command.until) || instantNanos(command.from) >= instantNanos(command.until) || instantNanos(command.until)-instantNanos(command.from) > 2678400000000000n || command.sourceSummaryReadId !== null || command.settlementReference !== null : command.from !== null || command.until !== null || !isUuid(command.sourceSummaryReadId) || !isRef(command.settlementReference)) throw new Error();
  } catch { throw new SettlementWriteError("rejected","rejected"); }
  let response: Response;
  try { response = await fetch(`${base}${path}/requests`,{method:"POST",credentials:"include",cache:"no-store",headers:{"Content-Type":"application/json","X-CSRF-TOKEN":csrf},body:attempt.body}); }
  catch { throw new SettlementWriteError("unknown","unknown"); }
  if (!response.ok) throw new SettlementWriteError(response.status >= 500 || response.status === 408 ? "unknown" : "rejected",response.status === 401 ? "session" : response.status === 403 ? "scope" : response.status === 409 ? "conflict" : response.status >= 500 || response.status === 408 ? "unknown" : "rejected");
  try { const result: unknown = await response.json(); if (!keys(result,["id","schoolId","kind"])) throw new Error(); const row = result as SettlementResult;
    if (!isUuid(row.id) || row.schoolId !== attempt.schoolId || row.kind !== attempt.kind) throw new Error(); return row;
  } catch { throw new SettlementWriteError("unknown","unknown"); }
}
