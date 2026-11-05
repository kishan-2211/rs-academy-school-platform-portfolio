/** Relationship-scoped, private historical fee reads; never academic activation or payment proof. */
export type FamilyFeeAccount = { id: string; sessionId: string; sessionName: string; status: "ACTIVE" | "CLOSED"; currencyCode: string; balance: number | string; currentSession: boolean };
export type FamilyFeeAccounts = { learnerId: string; currentAccountId: string | null; page: number; pageSize: number; hasMore: boolean; accounts: FamilyFeeAccount[] };
export const familyFeeAccountErrors = {
  unavailable: "Fee account history is unavailable. Retry or contact the school.",
  session: "Your session has ended. Sign in again to view fee accounts.",
  scope: "These fee accounts are no longer available through your family access.",
} as const;
const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const fail = (): never => { throw new Error(familyFeeAccountErrors.unavailable); };
export class FamilyFeeAccountAccessError extends Error {
  readonly status: number;
  constructor(status: number) { super(status === 401 ? familyFeeAccountErrors.session : familyFeeAccountErrors.scope); this.status = status; }
}
export function signedFeePaise(value: unknown): number {
  const text = String(value);
  if (!/^-?[0-9]{1,13}(?:\.[0-9]{1,2})?$/.test(text)) return fail();
  const [whole, decimal = ""] = text.replace(/^-/, "").split(".");
  const amount = Number(whole) * 100 + Number(decimal.padEnd(2, "0"));
  if (!Number.isSafeInteger(amount)) return fail();
  return text.startsWith("-") ? -amount : amount;
}
export function readFamilyFeeAccounts(value: unknown, learnerId: string, page: number, pageSize = 20): FamilyFeeAccounts {
  const data = value as FamilyFeeAccounts | null;
  if (!uuid.test(learnerId) || !Number.isInteger(page) || page < 0 || page > 10000 || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50
    || !data || data.learnerId !== learnerId || data.page !== page || data.pageSize !== pageSize || typeof data.hasMore !== "boolean"
    || (data.currentAccountId !== null && !uuid.test(data.currentAccountId)) || !Array.isArray(data.accounts) || data.accounts.length > pageSize || (data.hasMore && data.accounts.length !== pageSize)) return fail();
  const ids = new Set<string>();
  for (const account of data.accounts) {
    if (!account || !uuid.test(account.id) || !uuid.test(account.sessionId) || ids.has(account.id) || typeof account.sessionName !== "string" || !account.sessionName.trim() || account.sessionName.length > 240
      || !["ACTIVE", "CLOSED"].includes(account.status) || !/^[A-Z]{3}$/.test(account.currencyCode) || typeof account.currentSession !== "boolean"
      || (account.currentSession && account.id !== data.currentAccountId) || (!account.currentSession && account.id === data.currentAccountId)) return fail();
    signedFeePaise(account.balance); ids.add(account.id);
  }
  return data;
}
export async function loadFamilyFeeAccounts(learnerId: string, page: number, signal: AbortSignal): Promise<FamilyFeeAccounts> {
  if (!uuid.test(learnerId) || !Number.isInteger(page) || page < 0 || page > 10000) return fail();
  const response = await fetch(`${base}/api/v1/family/children/${learnerId}/fee-accounts?page=${page}&pageSize=20`, { credentials: "include", cache: "no-store", signal });
  if (!response.ok) {
    if ([401, 403, 404].includes(response.status)) throw new FamilyFeeAccountAccessError(response.status);
    return fail();
  }
  return readFamilyFeeAccounts(await response.json(), learnerId, page);
}
export function familyFeeAccountError(reason: unknown): string {
  return reason instanceof Error && Object.values(familyFeeAccountErrors).some(copy => copy === reason.message) ? reason.message : familyFeeAccountErrors.unavailable;
}
