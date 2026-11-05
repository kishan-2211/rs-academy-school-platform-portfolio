export type InboxItem = {
  recipientId: string;
  announcementId: string;
  learnerId: string | null;
  category: string;
  priority: string;
  audienceKind: string;
  languageMode: string;
  preferredLanguage: string;
  titleEn: string | null;
  titleHi: string | null;
  bodyEn: string | null;
  bodyHi: string | null;
  requiresAcknowledgement: boolean;
  scheduledFor: string;
  expiresAt: string | null;
  expired: boolean;
  readAt: string | null;
  acknowledgedAt: string | null;
  deliveries: { channel: string; status: string }[];
  version: number;
};

export type StaffInbox = { schoolId: string; learnerId: null; items: InboxItem[] };
export type InboxFilter = "all" | "unread" | "acknowledgement" | "expired";
export type InboxLanguage = "preferred" | "en" | "hi";
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function inboxCopy(item: InboxItem, choice: InboxLanguage) {
  const language = choice === "preferred" ? item.preferredLanguage : choice;
  const hindi = language === "hi" && Boolean(item.titleHi?.trim() && item.bodyHi?.trim());
  const english = Boolean(item.titleEn?.trim() && item.bodyEn?.trim());
  const useHindi = hindi || !english;
  return {
    language: useHindi ? "hi" : "en",
    title: (useHindi ? item.titleHi : item.titleEn) || "School notice",
    body: (useHindi ? item.bodyHi : item.bodyEn) || "",
  };
}

export function filterInbox(items: InboxItem[], filter: InboxFilter, search: string) {
  const query = search.trim().toLocaleLowerCase();
  return items.filter((item) => {
    if (filter === "unread" && (item.readAt || item.expired)) return false;
    if (filter === "acknowledgement" && (!item.requiresAcknowledgement || item.acknowledgedAt || item.expired)) return false;
    if (filter === "expired" && !item.expired) return false;
    return !query || [item.titleEn, item.titleHi, item.bodyEn, item.bodyHi, item.category]
      .some((value) => value?.toLocaleLowerCase().includes(query));
  });
}

async function problem(response: Response, fallback: string) {
  if (response.status === 401) return "Your session has ended. Sign in again to open your inbox.";
  if (response.status === 403) return "Your current school role cannot access this inbox.";
  try {
    const body = await response.json() as { detail?: string };
    return body.detail || fallback;
  } catch { return fallback; }
}

export async function loadStaffInbox(signal?: AbortSignal): Promise<StaffInbox> {
  const response = await fetch(`${apiUrl}/api/v1/communications/inbox`, {
    credentials: "include", cache: "no-store", signal,
  });
  if (!response.ok) throw new Error(await problem(response, "Your inbox could not be loaded. Please try again."));
  return await response.json() as StaffInbox;
}

export async function recordStaffInboxAction(schoolId: string, recipientId: string, action: "read" | "acknowledge") {
  const tokenCookie = () => document.cookie.split("; ")
    .find((part) => part.startsWith("educore_csrf="))?.slice("educore_csrf=".length);
  let token = tokenCookie();
  if (!token) {
    const session = await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store" });
    if (!session.ok) throw new Error(await problem(session, "The secure session could not be confirmed."));
    token = tokenCookie();
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  const response = await fetch(`${apiUrl}/api/v1/communications/inbox/${encodeURIComponent(recipientId)}/${action}`, {
    method: "POST", credentials: "include", cache: "no-store",
    headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": decodeURIComponent(token) },
    body: JSON.stringify({ schoolId }),
  });
  if (!response.ok) throw new Error(await problem(response, "The inbox action could not be saved. Please try again."));
}
