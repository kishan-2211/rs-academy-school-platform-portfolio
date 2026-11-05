import type { IssueAttempt } from "./official-certificates";

export type ReceiptTemplate = {
  id: string;
  displayName: string;
  locale: "en-IN" | "hi-IN";
  documentKind: "FEE_RECEIPT";
  status: "PUBLISHED";
  approvalRequired: boolean;
};
export type ReceiptDocument = {
  id: string;
  templateId: string;
  templateName: string;
  documentNumber: string;
  documentKind: "FEE_RECEIPT";
  sourceType: "FEE_PAYMENT";
  sourceId: string;
  status: "PENDING_APPROVAL" | "ISSUED" | "REJECTED" | "CANCELLED" | "SUPERSEDED";
  requestedAt: string;
};
export type FeeReceiptPage = {
  schoolId: string;
  paymentId: string;
  receiptNumber: string;
  sourceStatus: "POSTED" | "REVERSED";
  canIssue: boolean;
  page: number;
  pageSize: 25;
  hasMore: boolean;
  templates: ReceiptTemplate[];
  documents: ReceiptDocument[];
};
export type ReceiptIssueResult = {
  id: string;
  documentNumber: string;
  status: ReceiptDocument["status"];
  rowVersion: number;
};
const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const receiptDocumentErrors = {
  unavailable: "Receipt documents are unavailable. Refresh or contact the school.",
  session: "Your session has ended. Sign in again to view receipt PDFs.",
  scope: "Receipt PDF access is no longer available for this account.",
  storage: "Private document storage is temporarily unavailable. Please try again later.",
  invalidPdf: "The server did not return a valid non-empty PDF. Try again later.",
  token: "The secure form token is unavailable. Refresh and try again.",
} as const;
const statuses = ["PENDING_APPROVAL", "ISSUED", "REJECTED", "CANCELLED", "SUPERSEDED"];
const bounded = (value: unknown, max: number): value is string =>
  typeof value === "string" && value.length > 0 && value.length <= max;
function validPage(page: number) {
  return Number.isInteger(page) && page >= 0 && page <= 10000;
}
export function receiptDocumentProblem(status: number): string {
  if (status === 401) return receiptDocumentErrors.session;
  if (status === 403 || status === 404) return receiptDocumentErrors.scope;
  if (status === 503) return receiptDocumentErrors.storage;
  return receiptDocumentErrors.unavailable;
}
export function receiptDocumentPath(schoolId: string, paymentId: string, page: number): string {
  if (!uuid.test(schoolId) || !uuid.test(paymentId) || !validPage(page))
    throw new Error(receiptDocumentErrors.unavailable);
  return `/api/v1/documents/fee-receipts/${paymentId}?${new URLSearchParams({ schoolId, page: String(page) })}`;
}
export function readFeeReceiptPage(
  value: unknown,
  schoolId: string,
  paymentId: string,
  page: number,
): FeeReceiptPage {
  const data = value as FeeReceiptPage | null;
  if (
    !data ||
    !uuid.test(schoolId) ||
    !uuid.test(paymentId) ||
    !validPage(page) ||
    data.schoolId !== schoolId ||
    data.paymentId !== paymentId ||
    data.page !== page ||
    data.pageSize !== 25 ||
    !bounded(data.receiptNumber, 100) ||
    !["POSTED", "REVERSED"].includes(data.sourceStatus) ||
    typeof data.canIssue !== "boolean" ||
    (data.sourceStatus !== "POSTED" && data.canIssue) ||
    typeof data.hasMore !== "boolean" ||
    !Array.isArray(data.documents) ||
    data.documents.length > 25 ||
    !Array.isArray(data.templates) ||
    !data.templates.every(
      (item) =>
        item &&
        uuid.test(item.id) &&
        item.documentKind === "FEE_RECEIPT" &&
        item.status === "PUBLISHED" &&
        bounded(item.displayName, 140) &&
        ["en-IN", "hi-IN"].includes(item.locale) &&
        typeof item.approvalRequired === "boolean",
    ) ||
    !data.documents.every(
      (item) =>
        item &&
        uuid.test(item.id) &&
        uuid.test(item.templateId) &&
        item.documentKind === "FEE_RECEIPT" &&
        item.sourceType === "FEE_PAYMENT" &&
        item.sourceId === paymentId &&
        bounded(item.templateName, 140) &&
        bounded(item.documentNumber, 100) &&
        statuses.includes(item.status) &&
        Number.isFinite(Date.parse(item.requestedAt)),
    ) ||
    new Set(data.documents.map((item) => item.id)).size !== data.documents.length ||
    new Set(data.templates.map((item) => item.id)).size !== data.templates.length
  )
    throw new Error(receiptDocumentErrors.unavailable);
  return data;
}
export async function loadFeeReceiptPage(
  schoolId: string,
  paymentId: string,
  page: number,
  signal: AbortSignal,
) {
  const response = await fetch(`${base}${receiptDocumentPath(schoolId, paymentId, page)}`, {
    credentials: "include",
    cache: "no-store",
    signal,
  });
  if (!response.ok) throw new Error(receiptDocumentProblem(response.status));
  return readFeeReceiptPage(await response.json(), schoolId, paymentId, page);
}
/** Never infer an unused template from a partial history; cancelled copies also occupy their slot. */
export function availableReceiptTemplates(
  context: FeeReceiptPage,
  history: ReceiptDocument[],
): ReceiptTemplate[] {
  if (!context.canIssue || context.sourceStatus !== "POSTED" || context.hasMore) return [];
  return context.templates.filter(
    (template) =>
      !history.some((item) => item.templateId === template.id && item.status !== "REJECTED"),
  );
}
export function receiptIssueBody(
  schoolId: string,
  paymentId: string,
  templateId: string,
  reason: string,
): string {
  if (
    ![schoolId, paymentId, templateId].every((id) => uuid.test(id)) ||
    reason.trim().length < 3 ||
    reason.trim().length > 240
  )
    throw new Error(receiptDocumentErrors.unavailable);
  return JSON.stringify({
    schoolId,
    templateId,
    sourceType: "FEE_PAYMENT",
    sourceId: paymentId,
    supersedesDocumentId: null,
    reason: reason.trim(),
  });
}
export async function sendReceiptIssue(
  attempt: IssueAttempt,
  csrf: string,
  signal: AbortSignal,
): Promise<ReceiptIssueResult> {
  if (!uuid.test(attempt.key) || !csrf) throw new Error(receiptDocumentErrors.token);
  const response = await fetch(`${base}/api/v1/documents/issues`, {
    method: "POST",
    credentials: "include",
    cache: "no-store",
    signal,
    headers: {
      "Content-Type": "application/json",
      "X-CSRF-TOKEN": csrf,
      "Idempotency-Key": attempt.key,
    },
    body: attempt.body,
  });
  if (!response.ok) throw new Error(receiptDocumentProblem(response.status));
  const result = (await response.json()) as ReceiptIssueResult;
  if (
    !result ||
    !uuid.test(result.id) ||
    !bounded(result.documentNumber, 100) ||
    !statuses.includes(result.status) ||
    !Number.isSafeInteger(result.rowVersion) ||
    result.rowVersion < 0
  )
    throw new Error(receiptDocumentErrors.unavailable);
  return result;
}
export async function receiptCsrfToken(signal: AbortSignal): Promise<string> {
  const read = () =>
    document.cookie
      .split("; ")
      .find((part) => part.startsWith("educore_csrf="))
      ?.slice(13);
  let token = read();
  if (!token) {
    const response = await fetch(`${base}/api/v1/auth/sessions/current`, {
      credentials: "include",
      cache: "no-store",
      signal,
    });
    if (!response.ok) throw new Error(receiptDocumentProblem(response.status));
    token = read();
  }
  if (!token) throw new Error(receiptDocumentErrors.token);
  try {
    return decodeURIComponent(token);
  } catch {
    throw new Error(receiptDocumentErrors.token);
  }
}
export async function loadStaffReceiptPdf(
  schoolId: string,
  documentId: string,
  signal: AbortSignal,
): Promise<Blob> {
  if (!uuid.test(schoolId) || !uuid.test(documentId))
    throw new Error(receiptDocumentErrors.unavailable);
  const response = await fetch(
    `${base}/api/v1/documents/${documentId}/download?${new URLSearchParams({ schoolId })}`,
    { credentials: "include", cache: "no-store", signal },
  );
  if (!response.ok) throw new Error(receiptDocumentProblem(response.status));
  if (
    response.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase() !== "application/pdf"
  )
    throw new Error(receiptDocumentErrors.invalidPdf);
  const blob = await response.blob();
  if (!blob.size) throw new Error(receiptDocumentErrors.invalidPdf);
  return blob;
}
