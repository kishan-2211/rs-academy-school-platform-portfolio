/** Private invitation transport. Only the existing HttpOnly session authenticates staff actions. */
export const staffInvitationRoles = ["school_admin", "admissions_manager", "teacher", "finance_officer", "principal_director", "auditor", "staff_operations"] as const;
export type StaffInvitationRoleCode = typeof staffInvitationRoles[number];
export type StaffInvitationRole = { code: StaffInvitationRoleCode; name: string; capabilities: string[] };
export type StaffAccountInvitation = {
  id: string; schoolId: string; staffId: string; roleCode: StaffInvitationRoleCode; roleName: string; maskedDestination: string;
  status: "ISSUED" | "ACCEPTED" | "REVOKED" | "EXPIRED"; deliveryStatus: "AWAITING_HANDOFF" | "HANDOFF_REVEALED" | "CANCELLED";
  expiresAt: string; createdAt: string; acceptedAt: string | null; revokedAt: string | null; handoffAt: string | null;
  rowVersion: number; canRevoke: boolean; canRevealHandoff: boolean;
};
export type StaffInvitationList = { schoolId: string; staffId: string; manualHandoffEnabled: boolean; canManage: boolean; roles: StaffInvitationRole[]; items: StaffAccountInvitation[] };
export type StaffInvitationPreview = { schoolName: string; maskedDestination: string; roleCode: StaffInvitationRoleCode; roleName: string; expiresAt: string; existingAccount: boolean; status: "ready" };
export type StaffInvitationHandoff = { invitationId: string; setupToken: string; expiresAt: string };
export type StaffInvitationAcceptance = { status: "accepted"; nextAction: "sign_in" };
export type StaffInvitationErrorCode = "unavailable" | "session" | "scope" | "mfa" | "conflict" | "disabled" | "invalid" | "existing" | "uncertain" | "handoffUncertain";
export class StaffInvitationError extends Error {
  readonly code: StaffInvitationErrorCode;
  constructor(code: StaffInvitationErrorCode) { super(code); this.code = code; }
}
export type StaffInvitationCommand = Readonly<{ kind: "ISSUE" | "REVOKE" | "HANDOFF"; schoolId: string; staffId: string; invitationId: string | null; requestId: string; body: string }>;
const api = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const text = (value: unknown, maximum = 240): value is string => typeof value === "string" && value.length > 0 && value.length <= maximum && !/[\p{Cc}\p{Cf}]/u.test(value);
const integer = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const role = (value: unknown): value is StaffInvitationRoleCode => typeof value === "string" && (staffInvitationRoles as readonly string[]).includes(value);
const instant = (value: unknown): value is string => typeof value === "string" && value.length <= 40 && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value));
const nullableInstant = (value: unknown) => value === null || instant(value);
function record(value: unknown, keys: string[]): value is Record<string, unknown> { return value !== null && typeof value === "object" && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)); }
function invalid(code: StaffInvitationErrorCode = "unavailable"): never { throw new StaffInvitationError(code); }
export function staffInvitationErrorCode(error: unknown): StaffInvitationErrorCode { return error instanceof StaffInvitationError ? error.code : "unavailable"; }

export function readStaffAccountInvitation(value: unknown, schoolId: string, staffId: string): StaffAccountInvitation {
  if (!record(value, ["id", "schoolId", "staffId", "roleCode", "roleName", "maskedDestination", "status", "deliveryStatus", "expiresAt", "createdAt", "acceptedAt", "revokedAt", "handoffAt", "rowVersion", "canRevoke", "canRevealHandoff"]) ||
      !uuid.test(String(value.id)) || value.schoolId !== schoolId || value.staffId !== staffId || !role(value.roleCode) || !text(value.roleName, 120) || !text(value.maskedDestination, 320) ||
      !["ISSUED", "ACCEPTED", "REVOKED", "EXPIRED"].includes(String(value.status)) || !["AWAITING_HANDOFF", "HANDOFF_REVEALED", "CANCELLED"].includes(String(value.deliveryStatus)) ||
      !instant(value.createdAt) || !instant(value.expiresAt) || Date.parse(value.expiresAt) <= Date.parse(value.createdAt) || !nullableInstant(value.acceptedAt) || !nullableInstant(value.revokedAt) || !nullableInstant(value.handoffAt) ||
      !integer(value.rowVersion) || typeof value.canRevoke !== "boolean" || typeof value.canRevealHandoff !== "boolean" ||
      (value.status !== "ISSUED" && (value.canRevoke || value.canRevealHandoff)) || (value.canRevealHandoff && value.deliveryStatus !== "AWAITING_HANDOFF") ||
      (value.status === "ACCEPTED") !== (value.acceptedAt !== null) || (value.status === "REVOKED") !== (value.revokedAt !== null)) return invalid();
  return value as StaffAccountInvitation;
}
export function readStaffInvitationList(value: unknown, schoolId: string, staffId: string): StaffInvitationList {
  if (!uuid.test(schoolId) || !uuid.test(staffId) || !record(value, ["schoolId", "staffId", "manualHandoffEnabled", "canManage", "roles", "items"]) || value.schoolId !== schoolId || value.staffId !== staffId ||
      typeof value.manualHandoffEnabled !== "boolean" || typeof value.canManage !== "boolean" || !Array.isArray(value.roles) || value.roles.length > 7 || !Array.isArray(value.items) || value.items.length > 100) return invalid();
  const codes = new Set<string>(), ids = new Set<string>();
  for (const item of value.roles) {
    if (!record(item, ["code", "name", "capabilities"]) || !role(item.code) || codes.has(item.code) || !text(item.name, 120) || !Array.isArray(item.capabilities) || !item.capabilities.length || item.capabilities.length > 200 ||
        item.capabilities.some(capability => !text(capability, 120)) || new Set(item.capabilities).size !== item.capabilities.length) return invalid();
    codes.add(item.code);
  }
  for (const raw of value.items) { const item = readStaffAccountInvitation(raw, schoolId, staffId); if (ids.has(item.id) || (!value.canManage && (item.canRevoke || item.canRevealHandoff)) || (!value.manualHandoffEnabled && item.canRevealHandoff)) return invalid(); ids.add(item.id); }
  return value as StaffInvitationList;
}
export function readStaffInvitationPreview(value: unknown): StaffInvitationPreview {
  if (!record(value, ["schoolName", "maskedDestination", "roleCode", "roleName", "expiresAt", "existingAccount", "status"]) || !text(value.schoolName, 180) || !text(value.maskedDestination, 320) ||
      !role(value.roleCode) || !text(value.roleName, 120) || !instant(value.expiresAt) || typeof value.existingAccount !== "boolean" || value.status !== "ready") return invalid("invalid");
  return value as StaffInvitationPreview;
}
export function staffInvitationFragment(hash: string): string | null { const match = /^#token=([A-Za-z0-9_-]{43})$/.exec(hash); return match?.[1] ?? null; }
export function staffInvitationSetupLink(origin: string, token: string): string {
  if (!tokenPattern.test(token)) return invalid();
  try { const url = new URL(origin); if (url.origin !== origin || url.username || url.password || (url.protocol !== "https:" && !(url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)))) return invalid(); return `${origin}/staff-invitation#token=${token}`; }
  catch { return invalid(); }
}
function path(schoolId: string, staffId: string): string { if (!uuid.test(schoolId) || !uuid.test(staffId)) return invalid("invalid"); return `/api/v1/schools/${schoolId}/staff/${staffId}/account-invitations`; }
function reason(value: string): string { const trimmed = value.trim(); if (!text(trimmed) || trimmed.length < 3) return invalid("invalid"); return trimmed; }
function command(kind: StaffInvitationCommand["kind"], schoolId: string, staffId: string, invitationId: string | null, requestId: string, payload: Record<string, unknown>): StaffInvitationCommand {
  path(schoolId, staffId); if (!uuid.test(requestId) || (invitationId !== null && !uuid.test(invitationId))) return invalid("invalid");
  return Object.freeze({ kind, schoolId, staffId, invitationId, requestId, body: JSON.stringify({ requestId, ...payload }) });
}
export function prepareStaffInvitation(context: StaffInvitationList, staffVersion: number, roleCode: string, ttlHours: number, explanation: string, confirmed: boolean, requestId: string): StaffInvitationCommand {
  if (!context.canManage || !context.manualHandoffEnabled || !integer(staffVersion) || !context.roles.some(item => item.code === roleCode) || !Number.isInteger(ttlHours) || ttlHours < 1 || ttlHours > 168 || !confirmed || context.items.some(item => item.status === "ISSUED")) return invalid("invalid");
  return command("ISSUE", context.schoolId, context.staffId, null, requestId, { expectedStaffVersion: staffVersion, roleCode, ttlHours, reason: reason(explanation) });
}
export function prepareStaffInvitationAction(context: StaffInvitationList, invitation: StaffAccountInvitation, kind: "REVOKE" | "HANDOFF", explanation: string, confirmed: boolean, requestId: string): StaffInvitationCommand {
  if (!context.canManage || !context.items.includes(invitation) || !confirmed || invitation.status !== "ISSUED" || (kind === "REVOKE" ? !invitation.canRevoke : !context.manualHandoffEnabled || !invitation.canRevealHandoff)) return invalid("invalid");
  return command(kind, context.schoolId, context.staffId, invitation.id, requestId, { expectedVersion: invitation.rowVersion, reason: reason(explanation) });
}
async function reject(response: Response): Promise<never> {
  let code: unknown;
  try { const problem: unknown = await response.json(); if (problem !== null && typeof problem === "object" && "code" in problem) code = problem.code; } catch { /* Never echo response details or private values. */ }
  if (code === "STAFF_INVITATION_HANDOFF_DISABLED") return invalid("disabled");
  if (code === "STAFF_INVITATION_HANDOFF_ALREADY_REVEALED") return invalid("handoffUncertain");
  if (code === "STAFF_INVITATION_SIGN_IN_REQUIRED") return invalid("existing");
  if (code === "STAFF_INVITATION_MFA_REQUIRED" || code === "MFA_VERIFICATION_REQUIRED") return invalid("mfa");
  if (code === "STAFF_INVITATION_INVALID" || response.status === 410) return invalid("invalid");
  if (response.status === 401) return invalid("session");
  if (response.status === 403) return invalid("scope");
  if (response.status === 409) return invalid("conflict");
  return invalid(response.status === 400 ? "invalid" : "unavailable");
}
export async function loadStaffInvitations(schoolId: string, staffId: string, signal: AbortSignal): Promise<StaffInvitationList> {
  const response = await fetch(api + path(schoolId, staffId), { credentials: "include", cache: "no-store", signal }); if (!response.ok) return reject(response); return readStaffInvitationList(await response.json(), schoolId, staffId);
}
export async function staffInvitationCsrf(signal: AbortSignal): Promise<string> {
  const response = await fetch(`${api}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store", signal }); if (!response.ok) return reject(response);
  const value = document.cookie.split("; ").find(entry => entry.startsWith("educore_csrf="))?.slice("educore_csrf=".length); if (!value) return invalid("session"); try { return decodeURIComponent(value); } catch { return invalid("session"); }
}
export async function sendStaffInvitationCommand(attempt: StaffInvitationCommand, csrf: string): Promise<StaffAccountInvitation | StaffInvitationHandoff> {
  if (!csrf || !uuid.test(attempt.requestId)) return invalid("session");
  const suffix = attempt.kind === "ISSUE" ? "" : `/${attempt.invitationId}/${attempt.kind === "REVOKE" ? "revocations" : "handoffs"}`;
  let response: Response;
  try { response = await fetch(api + path(attempt.schoolId, attempt.staffId) + suffix, { method: "POST", credentials: "include", cache: "no-store", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf }, body: attempt.body }); }
  catch { return invalid(attempt.kind === "HANDOFF" ? "handoffUncertain" : "uncertain"); }
  if (!response.ok) { if (response.status >= 500) return invalid(attempt.kind === "HANDOFF" ? "handoffUncertain" : "uncertain"); return reject(response); }
  try {
    const value: unknown = await response.json();
    if (attempt.kind === "HANDOFF") { if (!record(value, ["invitationId", "setupToken", "expiresAt"]) || value.invitationId !== attempt.invitationId || typeof value.setupToken !== "string" || !tokenPattern.test(value.setupToken) || !instant(value.expiresAt)) return invalid("handoffUncertain"); return value as StaffInvitationHandoff; }
    const invitation = readStaffAccountInvitation(value, attempt.schoolId, attempt.staffId); if ((attempt.kind === "REVOKE" && (invitation.id !== attempt.invitationId || invitation.status !== "REVOKED")) || (attempt.kind === "ISSUE" && invitation.status !== "ISSUED")) return invalid("uncertain"); return invitation;
  } catch { return invalid(attempt.kind === "HANDOFF" ? "handoffUncertain" : "uncertain"); }
}
export async function previewStaffInvitation(token: string, signal: AbortSignal): Promise<StaffInvitationPreview> {
  if (!tokenPattern.test(token)) return invalid("invalid"); const response = await fetch(`${api}/api/v1/public/staff-account-invitations/previews`, { method: "POST", credentials: "omit", cache: "no-store", signal, headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token }) }); if (!response.ok) return reject(response); return readStaffInvitationPreview(await response.json());
}
export async function currentStaffInvitationSession(signal: AbortSignal): Promise<boolean> {
  const response = await fetch(`${api}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store", signal }); if (response.status === 401) return false; if (!response.ok) return reject(response); return true;
}
export function validStaffInvitationPassword(password: string): boolean { return password.length >= 16 && password.length <= 200 && password.trim().length > 0 && !/[\p{Cc}]/u.test(password); }
export async function acceptStaffInvitation(token: string, mode: { kind: "new"; password: string; confirmation: string } | { kind: "existing"; csrf: string }): Promise<StaffInvitationAcceptance> {
  if (!tokenPattern.test(token) || (mode.kind === "new" && (!validStaffInvitationPassword(mode.password) || mode.password !== mode.confirmation)) || (mode.kind === "existing" && !mode.csrf)) return invalid("invalid");
  const authenticated = mode.kind === "existing";
  let response: Response;
  try { response = await fetch(`${api}/api/v1/${authenticated ? "" : "public/"}staff-account-invitations/acceptances`, { method: "POST", credentials: authenticated ? "include" : "omit", cache: "no-store", headers: { "Content-Type": "application/json", ...(authenticated ? { "X-CSRF-TOKEN": mode.csrf } : {}) }, body: JSON.stringify(authenticated ? { token } : { token, password: mode.password, confirmation: mode.confirmation }) }); }
  catch { return invalid("uncertain"); }
  if (!response.ok) { if (response.status >= 500) return invalid("uncertain"); return reject(response); }
  try { const value: unknown = await response.json(); if (!record(value, ["status", "nextAction"]) || value.status !== "accepted" || value.nextAction !== "sign_in") return invalid("uncertain"); return value as StaffInvitationAcceptance; } catch { return invalid("uncertain"); }
}
