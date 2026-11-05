/** Staff account review only. No browser response is proof that a refund was posted. */
export const reopeningStates = ["SUBMITTED", "APPROVED", "REJECTED", "CANCELLED"] as const;
export type ReopeningState = typeof reopeningStates[number];
export type ReopeningFilter = { schoolId: string; status: ReopeningState | "ALL"; query: string; page: number };
export type ReopeningClosure = { closedAt: string; reason: string; closedByName: string };
export type ReopeningReview = {
  id: string; refundId: string; paymentId: string; accountId: string; learnerName: string; sessionName: string;
  state: ReopeningState; reason: string; requestedAt: string; makerName: string; isMaker: boolean;
  canApprove: boolean; canReject: boolean; canCancel: boolean; closedAccountVersion: number;
  originalClosure: ReopeningClosure;
  decision: { id: string; decision: "APPROVE" | "REJECT" | "CANCEL"; reason: string; decidedAt: string; reviewerName: string } | null;
};
export type ReopeningContext = {
  schoolId: string; refundId: string; paymentId: string; accountId: string; learnerName: string; sessionName: string;
  currencyCode: string; schoolTimeZone: string; sourceState: "UNAPPROVED" | "NO_HOLD" | "CONFLICTING" | "HOLD";
  accountState: "ACTIVE" | "CLOSED"; accountVersion: number; currentClosure: ReopeningClosure | null;
  currentBalance: number; confirmedUnpostedRefundAmount: number | null; reopeningRequired: boolean;
  canRequest: boolean; refundPosted: false; hasMoreHistory: boolean; requests: ReopeningReview[];
};
export type ReopeningQueue = { schoolId: string; status: ReopeningFilter["status"]; query: string | null; page: number; pageSize: number; total: number; hasMore: boolean; refundPosted: false; items: ReopeningReview[] };
export type ReopeningResult = { id: string; refundId: string; accountId: string; state: ReopeningState; accountReopened: boolean; refundPosted: false };
export type ReopeningAttempt = Readonly<{ schoolId: string; refundId: string; accountId: string; requestId: string; kind: "request" | "decision"; expectedState: ReopeningState; path: string; body: string }>;
export const reopeningErrors = {
  unavailable: "Account reopening records are unavailable. Refresh or contact the school.",
  session: "Your session has ended. Sign in again to review account reopening.",
  scope: "You no longer have access to this account reopening review.",
  filters: "Choose a valid account review status and a search of at most 100 characters.",
  reason: "Enter an account review reason of 3 to 500 characters without payment credentials.",
  eligibility: "Refresh the original closed account and confirmed refund hold before requesting reopening.",
  csrf: "The secure form token is unavailable. Refresh and try again.",
  rejected: "This account review command was not accepted. Refresh its records before preparing another command.",
  unknown: "The account review outcome is unknown. Keep this page open and retry only this exact request; do not create another command.",
} as const;
const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const controls = /[\u0000-\u001f\u007f-\u009f]/;
const text = (value: unknown, max: number): value is string => typeof value === "string" && value.trim().length > 0 && value.length <= max;
const instant = (value: unknown): value is string => typeof value === "string" && value.length <= 40 && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value));
const version = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0;
const fail = (): never => { throw new Error(reopeningErrors.unavailable); };
export function reopeningError(failure: unknown): string { return failure instanceof Error && Object.values(reopeningErrors).some(value => value === failure.message) ? failure.message : reopeningErrors.unavailable; }
/** Signed statement balance is not the positive, separately confirmed unposted refund. */
export function reopeningPaise(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  const match = /^(-?)(0|[1-9]\d{0,12})(?:\.(\d{1,2}))?$/.exec(String(value));
  if (!match) return null;
  const amount = Number(match[2]) * 100 + Number((match[3] ?? "").padEnd(2, "0"));
  return Number.isSafeInteger(amount) ? (match[1] ? -amount : amount) : null;
}
function readClosure(value: unknown): value is ReopeningClosure {
  const closure = value as ReopeningClosure | null;
  return !!closure && instant(closure.closedAt) && text(closure.reason,240) && text(closure.closedByName,320);
}
function readReview(value: unknown): value is ReopeningReview {
  const item = value as ReopeningReview | null;
  if (!item || ![item.id,item.refundId,item.paymentId,item.accountId].every(value => uuid.test(value)) || !text(item.learnerName,500) || !text(item.sessionName,200) || !reopeningStates.some(value => value === item.state) || !text(item.reason,500) || item.reason.trim().length < 3 || !instant(item.requestedAt) || !text(item.makerName,320) || !version(item.closedAccountVersion) || !readClosure(item.originalClosure) || [item.isMaker,item.canApprove,item.canReject,item.canCancel].some(value => typeof value !== "boolean")) return false;
  if ((item.isMaker && (item.canApprove || item.canReject)) || (!item.isMaker && item.canCancel) || (item.state !== "SUBMITTED" && (item.canApprove || item.canReject || item.canCancel))) return false;
  if (item.state === "SUBMITTED") return item.decision === null;
  const decision = item.decision;
  return !!decision && uuid.test(decision.id) && decision.decision === ({APPROVED:"APPROVE",REJECTED:"REJECT",CANCELLED:"CANCEL"} as Record<string,string>)[item.state] && text(decision.reason,500) && decision.reason.trim().length >= 3 && instant(decision.decidedAt) && text(decision.reviewerName,320);
}
export function reopeningFilter(filter: ReopeningFilter): ReopeningFilter {
  if (!uuid.test(filter.schoolId) || ![...reopeningStates,"ALL"].includes(filter.status) || typeof filter.query !== "string" || filter.query.length > 100 || controls.test(filter.query) || !Number.isInteger(filter.page) || filter.page < 0 || filter.page > 10000) throw new Error(reopeningErrors.filters);
  return {...filter,query:filter.query.trim()};
}
export function readReopeningContext(value: unknown, schoolId: string, refundId: string): ReopeningContext {
  const item = value as ReopeningContext | null;
  if (!uuid.test(schoolId) || !uuid.test(refundId) || !item || item.schoolId !== schoolId || item.refundId !== refundId || !uuid.test(item.paymentId) || !uuid.test(item.accountId) || !text(item.learnerName,500) || !text(item.sessionName,200) || !/^[A-Z]{3}$/.test(item.currencyCode) || !text(item.schoolTimeZone,60) || !["UNAPPROVED","NO_HOLD","CONFLICTING","HOLD"].includes(item.sourceState) || !["ACTIVE","CLOSED"].includes(item.accountState) || !version(item.accountVersion) || reopeningPaise(item.currentBalance) === null || item.refundPosted !== false || [item.canRequest,item.reopeningRequired,item.hasMoreHistory].some(value => typeof value !== "boolean") || (item.currentClosure !== null && !readClosure(item.currentClosure)) || (item.accountState === "CLOSED" && !item.currentClosure) || !Array.isArray(item.requests) || item.requests.length > 50) return fail();
  const held = item.confirmedUnpostedRefundAmount === null ? null : reopeningPaise(item.confirmedUnpostedRefundAmount);
  if (item.sourceState === "HOLD" ? held === null || held <= 0 : item.confirmedUnpostedRefundAmount !== null) return fail();
  if (item.canRequest && (item.accountState !== "CLOSED" || item.sourceState !== "HOLD" || !item.reopeningRequired)) return fail();
  const ids = new Set<string>();
  for (const review of item.requests) {
    if (!readReview(review) || ids.has(review.id) || review.refundId !== refundId || review.accountId !== item.accountId || review.paymentId !== item.paymentId) return fail();
    ids.add(review.id);
  }
  return item;
}
export function readReopeningQueue(value: unknown, expected: ReopeningFilter): ReopeningQueue {
  const filter = reopeningFilter(expected), queue = value as ReopeningQueue | null;
  if (!queue || queue.schoolId !== filter.schoolId || queue.status !== filter.status || queue.query !== (filter.query || null) || queue.page !== filter.page || queue.pageSize !== 25 || !version(queue.total) || queue.refundPosted !== false || !Array.isArray(queue.items) || queue.items.length !== Math.min(25,Math.max(0,queue.total-filter.page*25)) || queue.hasMore !== (filter.page*25+queue.items.length < queue.total)) return fail();
  const ids = new Set<string>();
  for (const item of queue.items) { if (!readReview(item) || ids.has(item.id) || (filter.status !== "ALL" && item.state !== filter.status)) return fail(); ids.add(item.id); }
  return queue;
}
async function readResponse(response: Response): Promise<unknown> {
  if (!response.ok) throw new Error(response.status === 401 ? reopeningErrors.session : response.status === 403 ? reopeningErrors.scope : response.status === 400 ? reopeningErrors.filters : reopeningErrors.unavailable);
  return response.json();
}
export async function loadReopeningQueue(filter: ReopeningFilter, signal: AbortSignal): Promise<ReopeningQueue> {
  const checked = reopeningFilter(filter), params = new URLSearchParams({schoolId:checked.schoolId,status:checked.status,page:String(checked.page),pageSize:"25"});
  if (checked.query) params.set("q",checked.query);
  return readReopeningQueue(await readResponse(await fetch(`${base}/api/v1/finance/gateway/refund-account-reopenings?${params}`,{credentials:"include",cache:"no-store",signal})),checked);
}
export async function loadReopeningContext(schoolId: string, refundId: string, signal: AbortSignal): Promise<ReopeningContext> {
  if (!uuid.test(schoolId) || !uuid.test(refundId)) return fail();
  return readReopeningContext(await readResponse(await fetch(`${base}/api/v1/finance/gateway/refunds/${refundId}/account-reopening?${new URLSearchParams({schoolId})}`,{credentials:"include",cache:"no-store",signal})),schoolId,refundId);
}
function reasonText(value: string): string {
  if (typeof value !== "string" || controls.test(value) || value.trim().length < 3 || value.trim().length > 500) throw new Error(reopeningErrors.reason);
  return value.trim();
}
export function prepareReopening(context: ReopeningContext, reason: string, clientRequestId: string): ReopeningAttempt {
  const checked = readReopeningContext(context,context.schoolId,context.refundId);
  if (!checked.canRequest || checked.accountState !== "CLOSED" || checked.sourceState !== "HOLD" || !uuid.test(clientRequestId)) throw new Error(reopeningErrors.eligibility);
  return Object.freeze({schoolId:checked.schoolId,refundId:checked.refundId,accountId:checked.accountId,requestId:clientRequestId,kind:"request",expectedState:"SUBMITTED",path:`/api/v1/finance/gateway/refunds/${checked.refundId}/account-reopening`,body:JSON.stringify({schoolId:checked.schoolId,clientRequestId,expectedAccountVersion:checked.accountVersion,reason:reasonText(reason)})});
}
export function prepareReopeningDecision(schoolId: string, review: ReopeningReview, decision: "APPROVE" | "REJECT" | "CANCEL", reason: string): ReopeningAttempt {
  if (!["APPROVE","REJECT","CANCEL"].includes(decision) || !uuid.test(schoolId) || !readReview(review) || review.state !== "SUBMITTED" || (decision === "CANCEL" ? !review.isMaker || !review.canCancel : review.isMaker || (decision === "APPROVE" ? !review.canApprove : !review.canReject))) throw new Error(reopeningErrors.scope);
  return Object.freeze({schoolId,refundId:review.refundId,accountId:review.accountId,requestId:review.id,kind:"decision",expectedState:({APPROVE:"APPROVED",REJECT:"REJECTED",CANCEL:"CANCELLED"} as const)[decision],path:`/api/v1/finance/gateway/refund-account-reopenings/${review.id}/decision`,body:JSON.stringify({schoolId,decision,reason:reasonText(reason)})});
}
export class ReopeningWriteError extends Error {
  readonly outcome: "unknown" | "rejected";
  constructor(outcome: "unknown" | "rejected", message: string) { super(message); this.name = "ReopeningWriteError"; this.outcome = outcome; }
}
/** Request retry may return current retained review state, not a new account transition. */
export async function sendReopening(attempt: ReopeningAttempt, csrf: string): Promise<ReopeningResult> {
  if (!csrf) throw new ReopeningWriteError("rejected",reopeningErrors.csrf);
  let response: Response;
  try { response = await fetch(`${base}${attempt.path}`,{method:"POST",credentials:"include",cache:"no-store",headers:{"Content-Type":"application/json","X-CSRF-TOKEN":csrf},body:attempt.body}); }
  catch { throw new ReopeningWriteError("unknown",reopeningErrors.unknown); }
  if (!response.ok) throw new ReopeningWriteError(response.status >= 500 || response.status === 408 ? "unknown" : "rejected",response.status === 401 ? reopeningErrors.session : response.status === 403 ? reopeningErrors.scope : response.status >= 500 || response.status === 408 ? reopeningErrors.unknown : reopeningErrors.rejected);
  try {
    const value = await response.json() as ReopeningResult;
    if (!value || !uuid.test(value.id) || value.refundId !== attempt.refundId || value.accountId !== attempt.accountId || !reopeningStates.some(state => state === value.state) || value.refundPosted !== false || value.accountReopened !== (value.state === "APPROVED") || (attempt.kind === "decision" && (value.id !== attempt.requestId || value.state !== attempt.expectedState))) throw new Error();
    return value;
  } catch { throw new ReopeningWriteError("unknown",reopeningErrors.unknown); }
}
