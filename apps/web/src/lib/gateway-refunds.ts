import { manualAmountPaise } from "./manual-fee-collection";

export const refundStatuses = ["SUBMITTED", "APPROVED", "REJECTED", "CANCELLED"] as const;
export type RefundStatus = typeof refundStatuses[number];
export type RefundFilter = { schoolId: string; status: RefundStatus | "ALL"; query: string; page: number };
export type RefundAllocation = { chargeId: string; label: string; amount: number };
export const refundProcessingStates = ["NOT_QUEUED","AWAITING_PROCESSING","PENDING_CONFIRMATION","PROVIDER_FAILED","REVIEW_REQUIRED","VERIFIED_AWAITING_POSTING","DUES_RESTORED"] as const;
export type RefundProcessing = { state: typeof refundProcessingStates[number]; requiresReview: boolean; lastCheckedAt: string | null; nextCheckAt: string | null };
export type RefundRequest = { id: string; paymentId: string; receiptNumber: string; learnerName: string; amount: number; currencyCode: string; status: RefundStatus; reason: string; requestedAt: string; requestedBy: string; isMaker: boolean; canReview: boolean; canCancel: boolean; allocations: RefundAllocation[]; decision: { id: string; decision: "APPROVE" | "REJECT" | "CANCEL"; reason: string; decidedAt: string; decidedBy: string } | null; processing?: RefundProcessing | null };
export type RefundQueue = { schoolId: string; status: RefundFilter["status"]; query: string | null; page: number; pageSize: number; total: number; hasMore: boolean; executionEnabled: boolean; items: RefundRequest[] };
export type RefundPayment = { schoolId: string; paymentId: string; receiptNumber: string; accountId: string; learnerName: string; currencyCode: string; amount: number; reserved: number; available: number; canRequest: boolean; executionEnabled: boolean; allocations: { chargeId: string; label: string; refundable: boolean; originalPaid: number; reserved: number; available: number }[] };
export type RefundAttempt = Readonly<{ schoolId: string; paymentId: string; requestId: string; kind: "request" | "decision"; expectedStatus: RefundStatus; path: string; body: string }>;
export const refundErrors = {
  unavailable: "Refund records are unavailable. Refresh or contact the school.",
  scope: "You no longer have refund access to this school.",
  session: "Your session has ended. Sign in again to review refunds.",
  filters: "Choose a valid refund status and a search of at most 100 characters.",
  allocations: "Choose refundable original receipt allocations and a positive available amount with at most two decimals.",
  reason: "Enter a reason of 3 to 500 characters. Do not include payment credentials.",
  csrf: "The secure form token is unavailable. Refresh and try again.",
  rejected: "This refund command was not accepted. Refresh its records before preparing another command.",
  unknown: "The refund command outcome is unknown. Keep this page open and retry only this same request; do not prepare another refund.",
} as const;
const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (value: unknown, max = 240): value is string => typeof value === "string" && value.length > 0 && value.length <= max;
const instant = (value: unknown): value is string => typeof value === "string" && value.length <= 40 && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value));
const money = (value: unknown): number | null => typeof value === "number" ? manualAmountPaise(value) : null;
const fail = (): never => { throw new Error(refundErrors.unavailable); };
export function refundProcessingLabel(state: RefundProcessing["state"]): string {
  return ({NOT_QUEUED:"Approved historical request needs processing review",AWAITING_PROCESSING:"Awaiting refund processing — no verified return",PENDING_CONFIRMATION:"Refund confirmation pending — do not issue another refund",PROVIDER_FAILED:"Provider reports an unsuccessful refund — review required",REVIEW_REQUIRED:"Refund evidence or processing needs review",VERIFIED_AWAITING_POSTING:"Verified refund — awaiting reviewed dues recording",DUES_RESTORED:"Refund recorded — original dues restored; check accounting separately"} as const)[state];
}
export function readRefundProcessing(value: unknown): RefundProcessing {
  const row=value as RefundProcessing | null;
  if (!row || !refundProcessingStates.some(state=>state===row.state) || typeof row.requiresReview!=="boolean" || (row.lastCheckedAt!==null&&!instant(row.lastCheckedAt)) || (row.nextCheckAt!==null&&!instant(row.nextCheckAt))) return fail();
  if (["NOT_QUEUED","PROVIDER_FAILED","REVIEW_REQUIRED"].includes(row.state)&&!row.requiresReview) return fail();
  if (["VERIFIED_AWAITING_POSTING","DUES_RESTORED"].includes(row.state)&&!row.lastCheckedAt) return fail();
  if (!["AWAITING_PROCESSING","PENDING_CONFIRMATION"].includes(row.state)&&row.nextCheckAt!==null) return fail();
  if (row.state==="AWAITING_PROCESSING"&&row.lastCheckedAt!==null) return fail();
  return row;
}
export function refundError(reason: unknown): string { return reason instanceof Error && Object.values(refundErrors).some(value => value === reason.message) ? reason.message : refundErrors.unavailable; }
export function refundFilter(filter: RefundFilter): RefundFilter {
  if (!uuid.test(filter.schoolId) || ![...refundStatuses,"ALL"].includes(filter.status) || typeof filter.query !== "string" || filter.query.length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(filter.query) || !Number.isInteger(filter.page) || filter.page < 0 || filter.page > 10000) throw new Error(refundErrors.filters);
  return { ...filter, query: filter.query.trim() };
}
export function readRefundPayment(value: unknown, schoolId: string, paymentId: string): RefundPayment {
  const item = value as RefundPayment | null;
  if (!item || item.schoolId !== schoolId || item.paymentId !== paymentId || !uuid.test(schoolId) || !uuid.test(paymentId) || !uuid.test(item.accountId) || !text(item.receiptNumber,120) || !text(item.learnerName,120) || !/^[A-Z]{3}$/.test(item.currencyCode) || typeof item.executionEnabled !== "boolean" || typeof item.canRequest !== "boolean" || money(item.amount) === null || money(item.amount)! < 1 || money(item.reserved) === null || money(item.available) === null || !Array.isArray(item.allocations) || !item.allocations.length || item.allocations.length > 100) return fail();
  let original = 0, reserved = 0, available = 0; const ids = new Set<string>();
  for (const line of item.allocations) {
    if (!line || !uuid.test(line.chargeId) || ids.has(line.chargeId) || !text(line.label,240) || typeof line.refundable !== "boolean" || money(line.originalPaid) === null || money(line.originalPaid)! < 1 || money(line.reserved) === null || money(line.available) === null || money(line.reserved)! > money(line.originalPaid)! || money(line.available) !== (line.refundable ? money(line.originalPaid)! - money(line.reserved)! : 0)) return fail();
    ids.add(line.chargeId); original += money(line.originalPaid)!; reserved += money(line.reserved)!; available += money(line.available)!;
  }
  if (original !== money(item.amount) || reserved !== money(item.reserved) || available !== money(item.available) || (item.canRequest && available === 0)) return fail();
  return item;
}
export function readRefundQueue(value: unknown, expected: RefundFilter): RefundQueue {
  const filter = refundFilter(expected), result = value as RefundQueue | null;
  if (!result || result.schoolId !== filter.schoolId || result.status !== filter.status || result.query !== (filter.query || null) || result.page !== filter.page || result.pageSize !== 25 || typeof result.executionEnabled !== "boolean" || !Number.isSafeInteger(result.total) || result.total < 0 || !Array.isArray(result.items) || result.items.length !== Math.min(25,Math.max(0,result.total - filter.page * 25)) || result.hasMore !== (filter.page * 25 + result.items.length < result.total)) return fail();
  const ids = new Set<string>();
  for (const item of result.items) {
    if (!item || !uuid.test(item.id) || ids.has(item.id) || !uuid.test(item.paymentId) || !text(item.receiptNumber,120) || !text(item.learnerName,120) || !/^[A-Z]{3}$/.test(item.currencyCode) || !refundStatuses.some(status => status === item.status) || (filter.status !== "ALL" && item.status !== filter.status) || !text(item.reason,500) || item.reason.trim().length < 3 || !instant(item.requestedAt) || !text(item.requestedBy,320) || [item.isMaker,item.canReview,item.canCancel].some(value => typeof value !== "boolean") || (item.canReview && item.isMaker) || (item.canCancel && !item.isMaker) || (item.status !== "SUBMITTED" && (item.canReview || item.canCancel)) || money(item.amount) === null || money(item.amount)! < 1 || !Array.isArray(item.allocations) || !item.allocations.length || item.allocations.length > 100) return fail();
    let amount = 0; const charges = new Set<string>();
    for (const line of item.allocations) { if (!line || !uuid.test(line.chargeId) || charges.has(line.chargeId) || !text(line.label,240) || money(line.amount) === null || money(line.amount)! < 1) return fail(); charges.add(line.chargeId); amount += money(line.amount)!; }
    if (amount !== money(item.amount)) return fail();
    if (item.status === "SUBMITTED" ? item.decision !== null : !item.decision) return fail();
    if (item.decision && (!uuid.test(item.decision.id) || item.decision.decision !== ({APPROVED:"APPROVE",REJECTED:"REJECT",CANCELLED:"CANCEL"} as Record<string,string>)[item.status] || !text(item.decision.reason,500) || item.decision.reason.trim().length < 3 || !text(item.decision.decidedBy,320) || !instant(item.decision.decidedAt))) return fail();
    if (item.processing!=null) { if (item.status!=="APPROVED") return fail(); readRefundProcessing(item.processing); }
    else if (result.executionEnabled&&item.status==="APPROVED") return fail(); // Older disabled APIs remain readable, never infer success from missing evidence.
    ids.add(item.id);
  }
  return result;
}
async function readResponse(response: Response): Promise<unknown> {
  if (!response.ok) throw new Error(response.status === 401 ? refundErrors.session : response.status === 403 ? refundErrors.scope : response.status === 400 ? refundErrors.filters : refundErrors.unavailable);
  return response.json();
}
export async function loadRefundQueue(filter: RefundFilter, signal: AbortSignal): Promise<RefundQueue> {
  const checked = refundFilter(filter), params = new URLSearchParams({schoolId:checked.schoolId,status:checked.status,page:String(checked.page),pageSize:"25"});
  if (checked.query) params.set("q",checked.query);
  return readRefundQueue(await readResponse(await fetch(`${base}/api/v1/finance/gateway/refunds?${params}`,{credentials:"include",cache:"no-store",signal})),checked);
}
export async function loadRefundPayment(schoolId: string, paymentId: string, signal: AbortSignal): Promise<RefundPayment> {
  if (!uuid.test(schoolId) || !uuid.test(paymentId)) return fail();
  return readRefundPayment(await readResponse(await fetch(`${base}/api/v1/finance/gateway/payments/${paymentId}/refunds?${new URLSearchParams({schoolId})}`,{credentials:"include",cache:"no-store",signal})),schoolId,paymentId);
}
function reasonText(value: string): string { const reason = value.trim(); if (reason.length < 3 || reason.length > 500 || /[\u0000-\u001f\u007f-\u009f]/.test(value)) throw new Error(refundErrors.reason); return reason; }
export function prepareRefund(payment: RefundPayment, values: Record<string,string>, reason: string, clientRequestId: string): RefundAttempt {
  if (!payment.canRequest || !uuid.test(clientRequestId)) throw new Error(refundErrors.allocations);
  const allocations = Object.entries(values).filter(([,value]) => value.trim() !== "").map(([chargeId,value]) => {
    const line = payment.allocations.find(item => item.chargeId === chargeId), amount = manualAmountPaise(value);
    if (!line || !line.refundable || amount === null || amount < 1 || amount > money(line.available)!) throw new Error(refundErrors.allocations);
    return {chargeId,amount:amount / 100};
  }).sort((a,b) => a.chargeId.localeCompare(b.chargeId));
  const total = allocations.reduce((sum,line) => sum + money(line.amount)!,0);
  if (!allocations.length || allocations.length > 100 || total > money(payment.available)!) throw new Error(refundErrors.allocations);
  return Object.freeze({schoolId:payment.schoolId,paymentId:payment.paymentId,requestId:clientRequestId,kind:"request",expectedStatus:"SUBMITTED",path:`/api/v1/finance/gateway/payments/${payment.paymentId}/refunds`,body:JSON.stringify({schoolId:payment.schoolId,clientRequestId,allocations,reason:reasonText(reason)})});
}
export function prepareRefundDecision(schoolId: string, request: RefundRequest, decision: "APPROVE" | "REJECT" | "CANCEL", reason: string): RefundAttempt {
  if (!uuid.test(schoolId) || !uuid.test(request.id) || !uuid.test(request.paymentId) || request.status !== "SUBMITTED" || (decision === "CANCEL" ? !request.canCancel || !request.isMaker : !request.canReview || request.isMaker)) throw new Error(refundErrors.scope);
  return Object.freeze({schoolId,paymentId:request.paymentId,requestId:request.id,kind:"decision",expectedStatus:({APPROVE:"APPROVED",REJECT:"REJECTED",CANCEL:"CANCELLED"} as const)[decision],path:`/api/v1/finance/gateway/refunds/${request.id}/decision`,body:JSON.stringify({schoolId,decision,reason:reasonText(reason)})});
}
/** Network/5xx/malformed success is deliberately uncertain, never a fresh editable command. */
export async function sendRefund(attempt: RefundAttempt, csrf: string): Promise<void> {
  if (!csrf) throw new RefundWriteError("rejected",refundErrors.csrf);
  let response: Response;
  try { response = await fetch(`${base}${attempt.path}`,{method:"POST",credentials:"include",cache:"no-store",headers:{"Content-Type":"application/json","X-CSRF-TOKEN":csrf},body:attempt.body}); }
  catch { throw new RefundWriteError("unknown",refundErrors.unknown); }
  if (!response.ok) throw new RefundWriteError(response.status >= 500 || response.status === 408 ? "unknown" : "rejected",response.status === 401 ? refundErrors.session : response.status === 403 ? refundErrors.scope : response.status >= 500 || response.status === 408 ? refundErrors.unknown : refundErrors.rejected);
  try { const value = await response.json() as {id?:string;paymentId?:string;status?:string}; if (!value || !uuid.test(value.id ?? "") || value.paymentId !== attempt.paymentId || (attempt.kind === "request" ? !refundStatuses.some(status => status === value.status) : value.status !== attempt.expectedStatus || value.id !== attempt.requestId)) throw new Error(); }
  catch { throw new RefundWriteError("unknown",refundErrors.unknown); }
}
export class RefundWriteError extends Error { readonly outcome: "unknown" | "rejected"; constructor(outcome: "unknown" | "rejected", message: string) { super(message); this.name = "RefundWriteError"; this.outcome = outcome; } }
