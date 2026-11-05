const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function browserSupportsPush() {
  return typeof window !== "undefined" && window.isSecureContext
    && typeof navigator !== "undefined" && "serviceWorker" in navigator
    && "PushManager" in window && "Notification" in window;
}

export function pushDeviceActions(status: { registrationEnabled: boolean } | null, supported: boolean, pending: boolean) {
  // Account-wide device counts cannot tell whether this browser is registered.
  return {
    canEnable: Boolean(status?.registrationEnabled && supported && !pending),
    canDisable: Boolean(status && supported && !pending),
  };
}

export async function pushCsrfToken() {
  const read = () => document.cookie.split("; ")
    .find((part) => part.startsWith("educore_csrf="))?.slice("educore_csrf=".length);
  let token = read();
  if (!token) {
    const response = await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store" });
    if (!response.ok) throw new Error("Your secure session could not be confirmed. Sign in again and retry.");
    token = read();
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}

export async function waitForPushRegistration<T>(readiness: Promise<T>, timeoutMs = 10_000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([readiness, new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error("The notification service worker is not ready. Refresh this page and try again.")), timeoutMs);
    })]);
  } finally { if (timer !== undefined) clearTimeout(timer); }
}
