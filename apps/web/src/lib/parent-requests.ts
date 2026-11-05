export type ParentRequestSummary = {
  id: string;
  requestNumber: string;
  learnerId: string;
  learnerName: string;
  guardianName: string;
  category: string;
  subject: string;
  priority: string;
  status: string;
  assignedStaffProfileId?: string;
  assignedStaffName?: string;
  openedAt: string;
  lastActivityAt: string;
  rowVersion: number;
};

export type ParentRequestMessage = {
  id: string;
  authorType: "GUARDIAN" | "STAFF";
  authorName: string;
  body: string;
  internalNote: boolean;
  createdAt: string;
};

export type ParentRequestHistory = {
  id: string;
  eventType: string;
  previousStatus?: string;
  newStatus: string;
  previousPriority?: string;
  newPriority: string;
  previousAssigneeName?: string;
  newAssigneeName?: string;
  reason: string;
  actorName: string;
  occurredAt: string;
};

export type ParentRequestDetail = {
  request: ParentRequestSummary;
  resolutionSummary?: string;
  resolvedAt?: string;
  closeReason?: string;
  closedAt?: string;
  messages: ParentRequestMessage[];
  history: ParentRequestHistory[];
};

export type ParentRequestAssignee = {
  staffProfileId: string;
  employeeCode: string;
  displayName: string;
  designation?: string;
};

export const parentRequestCategories = [
  "FEES", "ATTENDANCE", "ACADEMICS", "HOMEWORK", "TRANSPORT",
  "ADMISSION", "CERTIFICATES", "GENERAL",
] as const;

export const parentRequestStatuses = ["OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED"] as const;
export const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
export const parentRequestLabel = (value: string) => value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());

function cookie(name: string) {
  return document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.split("=").slice(1).join("=");
}

async function csrfToken() {
  let token = cookie("educore_csrf");
  if (!token) {
    await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
    token = cookie("educore_csrf");
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}

export async function parentRequestProblem(response: Response, fallback: string) {
  try {
    const data = await response.json() as { detail?: string; message?: string };
    return data.detail || data.message || fallback;
  } catch {
    return fallback;
  }
}

export async function parentRequestMutation<T>(path: string, body: Record<string, unknown>) {
  const response = await fetch(`${apiUrl}${path}`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken() },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(await parentRequestProblem(response, "The request change could not be saved."));
  return await response.json() as T;
}
