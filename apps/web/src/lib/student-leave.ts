export type LeavePolicy = {
  id: string; sessionId: string; sessionName: string; maxCalendarDays: number;
  retrospectiveDays: number; attendanceState: "EXCUSED" | "ABSENT"; enabled: boolean; rowVersion: number;
};
export type LeaveSession = { id: string; name: string; startsOn: string; endsOn: string };
export type LeaveRecord = {
  id: string; learnerId: string; learnerName: string; sessionId: string; startsOn: string; endsOn: string;
  leaveType: string; reason: string; status: string; lastActionReason: string;
  createdAt: string; rowVersion: number; canWithdraw: boolean;
};
export type LeaveWorkspace = {
  learnerId?: string; schoolId?: string; schoolName?: string; sessions: LeaveSession[]; policies: LeavePolicy[];
  records: { requests: LeaveRecord[]; total: number; page: number; pageSize: number };
};
export type LeaveDetail = { request: LeaveRecord; history: Array<{
  previousStatus?: string | null; newStatus: string; reason: string; occurredAt: string; requestVersion: number;
}> };
export type LeaveAttempt = { fingerprint: string; key: string };
export const leaveLabel = (value: string) => value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (c) => c.toUpperCase());

// Keep retry identity in component memory only. Never persist a child's private reason in browser storage.
export function leaveAttempt(previous: LeaveAttempt | null, payload: Record<string, unknown>, makeKey = () => crypto.randomUUID()): LeaveAttempt {
  const fingerprint = JSON.stringify(payload);
  return previous?.fingerprint === fingerprint ? previous : { fingerprint, key: makeKey() };
}

export async function leaveApi<T>(path: string, options: { method?: "GET" | "POST" | "PUT"; body?: Record<string, unknown>; signal?: AbortSignal } = {}): Promise<T> {
  const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
  if (!path.startsWith("/api/v1/")) throw new Error("Unsupported school API request.");
  const method = options.method ?? "GET";
  const headers: Record<string, string> = {};
  if (method !== "GET") {
    const cookie = () => document.cookie.split("; ").find((c) => c.startsWith("educore_csrf="))?.slice("educore_csrf=".length);
    let csrf = cookie();
    if (!csrf) {
      await fetch(`${base}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store", signal: options.signal });
      csrf = cookie();
    }
    if (!csrf) throw new Error("The secure form token is unavailable. Refresh and try again.");
    headers["X-CSRF-TOKEN"] = decodeURIComponent(csrf);
    headers["Content-Type"] = "application/json";
  }
  const response = await fetch(`${base}${path}`, { method, credentials: "include", cache: "no-store",
    headers, signal: options.signal, ...(options.body ? { body: JSON.stringify(options.body) } : {}) });
  if (!response.ok) {
    let message = "Student leave is unavailable. Try again or contact the school.";
    if (response.status < 500) {
      try { const problem = await response.json() as { detail?: string }; message = problem.detail || message; } catch { /* Use safe fallback. */ }
    }
    throw new Error(message);
  }
  return await response.json() as T;
}
