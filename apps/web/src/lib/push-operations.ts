export type PushCount = { code: string; count: number };
export type PushOperations = {
  schoolId: string;
  canApprove: boolean;
  transportEnabled: boolean;
  workerEnabled: boolean;
  providerStatus: string;
  providerVersion: number | null;
  providerCompatible: boolean;
  windowStartsAt: string;
  parentStates: PushCount[];
  deviceStates: PushCount[];
  failureCodes: PushCount[];
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const path = `${apiUrl}/api/v1/communications/push-operations`;

async function read(response: Response, schoolId: string): Promise<PushOperations> {
  if (response.status === 401) throw new Error("Your session has ended. Sign in again.");
  if (response.status === 403) throw new Error("Your current school role or MFA verification does not permit this action.");
  if (response.status === 409) throw new Error("The provider state changed or its mapping needs review. Refresh before making another decision.");
  if (!response.ok) throw new Error("Push operations could not be completed. Refresh to check the current state before retrying.");
  const data = await response.json() as PushOperations;
  if (data.schoolId !== schoolId) throw new Error("The school scope changed. Reload the workspace.");
  return data;
}

export async function loadPushOperations(schoolId: string, signal?: AbortSignal) {
  return read(await fetch(`${path}?schoolId=${encodeURIComponent(schoolId)}`, {
    credentials: "include", cache: "no-store", signal,
  }), schoolId);
}

export async function changePushProvider(schoolId: string, status: "ACTIVE" | "SUSPENDED", expectedVersion: number | null, reason: string) {
  const decisionReason = reason.trim();
  const hasControlCharacter = [...decisionReason].some((character) => {
    const code = character.charCodeAt(0);
    return code < 32 || (code >= 127 && code <= 159);
  });
  if (decisionReason.length < 3 || decisionReason.length > 500 || hasControlCharacter) {
    throw new Error("Enter a decision reason between 3 and 500 characters, without control characters.");
  }
  const tokenCookie = () => document.cookie.split("; ").find((part) => part.startsWith("educore_csrf="))?.slice("educore_csrf=".length);
  let token = tokenCookie();
  if (!token) {
    const session = await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store" });
    if (!session.ok) throw new Error("The secure session could not be confirmed. Sign in again.");
    token = tokenCookie();
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return read(await fetch(`${path}/provider`, {
    method: "PUT", credentials: "include", cache: "no-store",
    headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": decodeURIComponent(token) },
    body: JSON.stringify({ schoolId, status, expectedVersion, reason: decisionReason }),
  }), schoolId);
}
