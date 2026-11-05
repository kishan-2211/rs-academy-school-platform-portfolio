/** Private payment transport. Authentication stays in the existing HttpOnly session. */
export type GatewayCharge = { id: string; label: string; dueOn: string; outstandingAmount: number; reservedAmount: number; allowPartial: boolean };
export type GatewayFeeContext = { learnerId: string; accountId: string | null; currencyCode: string; charges: GatewayCharge[]; explicitAccountId?: string; accountStatus?: "ACTIVE" | "CLOSED"; paymentsAllowed?: boolean };
export type FamilyPaymentStatus = "PAID" | "REVIEW_REQUIRED" | "READY" | "PENDING" | "EXPIRED" | "RELEASED";
export type FamilyPayment = {
  id: string; learnerId: string; accountId: string; sessionName: string; amount: number; currencyCode: string;
  status: FamilyPaymentStatus; createdAt: string; expiresAt: string; originalBusinessDate: string;
  reservationActive: boolean; initiatedByYou: boolean; canContinue: boolean;
  selectedCharges: { chargeId: string; label: string; dueOn: string; amount: number }[];
  receipt?: { paymentId: string; receiptNumber: string; postingOn: string } | null;
};
export type FamilyPaymentHistory = { learnerId: string; availability: "AVAILABLE" | "DISABLED" | "UNAVAILABLE"; page: number; pageSize: 20; hasMore: boolean; payments: FamilyPayment[] };
export type PreparedFamilyPayment = Readonly<{ learnerId: string; accountId: string; explicitAccountId?: string; clientRequestId: string; body: string }>;
export const familyPaymentErrors = {
  unavailable: "Online payment information is unavailable. Refresh or contact the school.",
  session: "Your session has ended. Sign in again to view payments.",
  scope: "This payment is no longer available through your family access. Refresh or contact the school.",
  selection: "Choose eligible charges and enter a valid amount within the available balance.",
  phone: "Enter a 10-digit Indian mobile number for the secure payment provider.",
  csrf: "The secure form token is unavailable. Refresh and try again.",
  conflict: "The fee balance or payment eligibility changed. Refresh the statement before starting another payment.",
  uncertain: "The payment request is uncertain. Refresh history and retry only this unchanged request; do not start another payment.",
  continuation: "The secure payment link is unavailable. Refresh the verified payment status; do not pay again.",
} as const;
export const familyPaymentStatusCopy: Record<FamilyPaymentStatus, string> = {
  PAID: "Paid — numbered receipt recorded", REVIEW_REQUIRED: "School review required — do not pay again",
  READY: "Ready for secure checkout", PENDING: "Awaiting verified confirmation — do not pay again",
  EXPIRED: "Checkout expired — balance still held for verification", RELEASED: "Reservation released — not paid",
};
const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_PAISE = 10_000_000_000;
const dateOnly = (value: unknown): value is string => typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().startsWith(value);
const instant = (value: unknown): value is string => typeof value === "string" && value.length <= 40 && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value));
const label = (value: unknown, max = 240): value is string => typeof value === "string" && value.length > 0 && value.length <= max;
function unavailable(): never { throw new Error(familyPaymentErrors.unavailable); }
export class FamilyPaymentAccessError extends Error {
  status: number;
  constructor(status: number) { super(status === 401 ? familyPaymentErrors.session : familyPaymentErrors.scope); this.status = status; }
}
function reject(status: number): never {
  if ([401, 403, 404].includes(status)) throw new FamilyPaymentAccessError(status);
  throw new Error(status === 409 ? familyPaymentErrors.conflict : familyPaymentErrors.unavailable);
}
/** Parse decimal money without binary floating-point arithmetic or exponent/third-decimal acceptance. */
export function paymentPaise(value: string | number): number {
  const text = typeof value === "string" ? value.trim() : String(value);
  if (!/^[0-9]{1,9}(?:\.[0-9]{1,2})?$/.test(text)) throw new Error(familyPaymentErrors.selection);
  const [whole, fractional = ""] = text.split(".");
  const result = Number(whole) * 100 + Number(fractional.padEnd(2, "0"));
  if (!Number.isSafeInteger(result) || result < 0 || result > MAX_PAISE) throw new Error(familyPaymentErrors.selection);
  return result;
}
export function paymentAmountInput(paise: number): string {
  if (!Number.isSafeInteger(paise) || paise < 0 || paise > MAX_PAISE) throw new Error(familyPaymentErrors.selection);
  return `${Math.floor(paise / 100)}.${String(paise % 100).padStart(2, "0")}`;
}
export function availablePaymentPaise(charge: GatewayCharge): number {
  if (!charge || !uuid.test(charge.id) || !label(charge.label) || !dateOnly(charge.dueOn) || typeof charge.allowPartial !== "boolean") throw new Error(familyPaymentErrors.selection);
  const outstanding = paymentPaise(charge.outstandingAmount), reserved = paymentPaise(charge.reservedAmount);
  if (reserved > outstanding) throw new Error(familyPaymentErrors.selection);
  return outstanding - reserved;
}
export function validatePaymentSelection(fees: GatewayFeeContext, selection: Readonly<Record<string, string>>): { chargeId: string; amount: number }[] {
  if (!uuid.test(fees.learnerId) || !fees.accountId || !uuid.test(fees.accountId) || fees.currencyCode !== "INR" || !Array.isArray(fees.charges) || fees.accountStatus === "CLOSED" || fees.paymentsAllowed === false || (fees.explicitAccountId !== undefined && (fees.explicitAccountId !== fees.accountId || !uuid.test(fees.explicitAccountId)))) throw new Error(familyPaymentErrors.selection);
  const charges = new Map<string, GatewayCharge>();
  for (const charge of fees.charges) {
    if (charges.has(charge.id)) throw new Error(familyPaymentErrors.selection);
    availablePaymentPaise(charge); charges.set(charge.id, charge);
  }
  const entries = Object.entries(selection);
  if (entries.length < 1 || entries.length > 100) throw new Error(familyPaymentErrors.selection);
  let total = 0;
  return entries.sort(([left], [right]) => left.localeCompare(right)).map(([chargeId, input]) => {
    const charge = charges.get(chargeId);
    if (!charge) throw new Error(familyPaymentErrors.selection);
    const amount = paymentPaise(input), available = availablePaymentPaise(charge);
    // A school full-only installment is not split merely because another
    // request already reserves part of it. The backend checks this again.
    if (amount < 1 || amount > available || (!charge.allowPartial && (amount !== paymentPaise(charge.outstandingAmount) || charge.reservedAmount > 0))) throw new Error(familyPaymentErrors.selection);
    total += amount;
    if (total > MAX_PAISE) throw new Error(familyPaymentErrors.selection);
    return { chargeId, amount: Number(paymentAmountInput(amount)) };
  });
}
/** Retain the exact immutable serialized request across unknown results, never rebuild it from refreshed fees. */
export function prepareFamilyPayment(previous: PreparedFamilyPayment | null, fees: GatewayFeeContext, selection: Readonly<Record<string, string>>, payerPhone: string, requestId: () => string): PreparedFamilyPayment {
  if (previous) {
    if (previous.learnerId !== fees.learnerId || previous.accountId !== fees.accountId || previous.explicitAccountId !== fees.explicitAccountId) throw new Error(familyPaymentErrors.selection);
    return previous;
  }
  const allocations = validatePaymentSelection(fees, selection);
  if (!/^[0-9]{10}$/.test(payerPhone)) throw new Error(familyPaymentErrors.phone);
  const clientRequestId = requestId();
  if (!uuid.test(clientRequestId)) throw new Error(familyPaymentErrors.selection);
  return Object.freeze({ learnerId: fees.learnerId, accountId: fees.accountId!, ...(fees.explicitAccountId ? { explicitAccountId: fees.explicitAccountId } : {}), clientRequestId, body: JSON.stringify({ clientRequestId, ...(fees.explicitAccountId ? { accountId: fees.explicitAccountId } : {}), allocations, payerPhone }) });
}
export function readFamilyPayment(value: unknown, learnerId?: string, attemptId?: string): FamilyPayment {
  const p = value as Partial<FamilyPayment> | null;
  if (!p || !uuid.test(p.id ?? "") || !uuid.test(p.learnerId ?? "") || !uuid.test(p.accountId ?? "") || (learnerId && p.learnerId !== learnerId) || (attemptId && p.id !== attemptId)
    || !label(p.sessionName, 120) || p.currencyCode !== "INR" || !Object.hasOwn(familyPaymentStatusCopy, p.status ?? "")
    || !instant(p.createdAt) || !instant(p.expiresAt) || !dateOnly(p.originalBusinessDate)
    || typeof p.reservationActive !== "boolean" || typeof p.initiatedByYou !== "boolean" || typeof p.canContinue !== "boolean"
    || !Array.isArray(p.selectedCharges) || p.selectedCharges.length < 1 || p.selectedCharges.length > 100) return unavailable();
  const ids = new Set<string>(); let total = 0;
  for (const charge of p.selectedCharges) {
    if (!charge || !uuid.test(charge.chargeId) || ids.has(charge.chargeId) || !label(charge.label) || !dateOnly(charge.dueOn)) return unavailable();
    const amount = paymentPaise(charge.amount); if (!amount) return unavailable(); total += amount; ids.add(charge.chargeId);
  }
  if (!total || total !== paymentPaise(p.amount!) || Date.parse(p.expiresAt) <= Date.parse(p.createdAt)) return unavailable();
  if (p.receipt && (!uuid.test(p.receipt.paymentId) || !label(p.receipt.receiptNumber, 120) || !dateOnly(p.receipt.postingOn))) return unavailable();
  // Extra received-money evidence may require review even when the original
  // numbered receipt is already posted. Preserve that valid receipt, rather
  // than treating REVIEW_REQUIRED as proof that no payment exists.
  if ((p.status === "PAID" && !p.receipt) || (p.receipt && !["PAID", "REVIEW_REQUIRED"].includes(p.status!))
    || (p.canContinue && (p.status !== "READY" || !p.initiatedByYou || !p.reservationActive))) return unavailable();
  return p as FamilyPayment;
}
export function readFamilyPaymentHistory(value: unknown, learnerId: string, page: number): FamilyPaymentHistory {
  const h = value as Partial<FamilyPaymentHistory> | null;
  if (!uuid.test(learnerId) || !Number.isInteger(page) || page < 0 || page > 10000 || !h || h.learnerId !== learnerId || h.page !== page || h.pageSize !== 20 || !["AVAILABLE", "DISABLED", "UNAVAILABLE"].includes(h.availability ?? "") || typeof h.hasMore !== "boolean" || !Array.isArray(h.payments) || h.payments.length > 20) return unavailable();
  const ids = new Set<string>();
  h.payments.forEach(value => { const item = readFamilyPayment(value, learnerId); if (ids.has(item.id)) return unavailable(); ids.add(item.id); });
  return h as FamilyPaymentHistory;
}
export function safeFamilyCheckoutUrl(value: unknown): string {
  if (typeof value !== "string" || value.length > 240) throw new Error(familyPaymentErrors.continuation);
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || !["payments.cashfree.com", "payments-test.cashfree.com"].includes(url.hostname)
      || url.port || url.username || url.password || url.search || url.hash || !/^\/links\/[A-Za-z0-9_-]{1,100}$/.test(url.pathname)
      || url.toString() !== value) throw new Error();
    return value;
  } catch { throw new Error(familyPaymentErrors.continuation); }
}
function childPath(learnerId: string): string { if (!uuid.test(learnerId)) return unavailable(); return `/api/v1/family/children/${learnerId}/fee-payments`; }
export async function loadFamilyPaymentHistory(learnerId: string, page: number, signal: AbortSignal): Promise<FamilyPaymentHistory> {
  if (!Number.isInteger(page) || page < 0 || page > 10000) return unavailable();
  const response = await fetch(`${base}${childPath(learnerId)}?page=${page}`, { credentials: "include", cache: "no-store", signal });
  if (!response.ok) return reject(response.status);
  return readFamilyPaymentHistory(await response.json(), learnerId, page);
}
export async function loadFamilyPayment(attemptId: string, signal: AbortSignal): Promise<FamilyPayment> {
  if (!uuid.test(attemptId)) return unavailable();
  const response = await fetch(`${base}/api/v1/family/fee-payments/${attemptId}`, { credentials: "include", cache: "no-store", signal });
  if (!response.ok) return reject(response.status);
  return readFamilyPayment(await response.json(), undefined, attemptId);
}
export async function familyPaymentCsrf(signal: AbortSignal, readCookie = () => document.cookie): Promise<string> {
  const read = () => readCookie().split("; ").find(item => item.startsWith("educore_csrf="))?.slice(13);
  let token = read();
  if (!token) {
    const response = await fetch(`${base}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store", signal });
    if (!response.ok) return reject(response.status);
    token = read();
  }
  try {
    const decoded = token ? decodeURIComponent(token) : "";
    if (!decoded || decoded.length > 4096 || /[\r\n]/.test(decoded)) throw new Error();
    return decoded;
  } catch { throw new Error(familyPaymentErrors.csrf); }
}
export async function sendFamilyPayment(attempt: PreparedFamilyPayment, csrf: string, signal: AbortSignal): Promise<FamilyPayment> {
  const response = await fetch(`${base}${childPath(attempt.learnerId)}`, { method: "POST", credentials: "include", cache: "no-store", signal, headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf }, body: attempt.body });
  if (!response.ok) return reject(response.status);
  const payment = readFamilyPayment(await response.json(), attempt.learnerId);
  const body = JSON.parse(attempt.body) as { allocations: { chargeId: string; amount: number }[] };
  if (payment.accountId !== attempt.accountId || payment.selectedCharges.length !== body.allocations.length
    || body.allocations.some(item => !payment.selectedCharges.some(charge => charge.chargeId === item.chargeId && paymentPaise(charge.amount) === paymentPaise(item.amount)))) return unavailable();
  return payment;
}
export async function continueFamilyPayment(payment: FamilyPayment, csrf: string, signal: AbortSignal): Promise<string> {
  if (!payment.canContinue || payment.status !== "READY" || !payment.initiatedByYou || !payment.reservationActive) throw new Error(familyPaymentErrors.continuation);
  const response = await fetch(`${base}${childPath(payment.learnerId)}/${payment.id}/continue`, { method: "POST", credentials: "include", cache: "no-store", signal, headers: { "X-CSRF-TOKEN": csrf } });
  if (!response.ok) return reject(response.status);
  const result = await response.json() as { attemptId?: unknown; checkoutUrl?: unknown; expiresAt?: unknown };
  if (!result || result.attemptId !== payment.id || !instant(result.expiresAt) || Date.parse(result.expiresAt) <= Date.now()) throw new Error(familyPaymentErrors.continuation);
  return safeFamilyCheckoutUrl(result.checkoutUrl);
}
export function familyPaymentErrorCopy(reason: unknown): string {
  return reason instanceof Error && Object.values(familyPaymentErrors).some(copy => copy === reason.message) ? reason.message : familyPaymentErrors.unavailable;
}
