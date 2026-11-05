export type FamilyDocument = {
  id: string;
  documentKind: "REPORT_CARD" | "FEE_RECEIPT" | "BONAFIDE_CERTIFICATE";
  documentNumber: string;
  locale: "en-IN" | "hi-IN";
  issuedAt: string;
  sizeBytes: number;
};
export type FamilyDocumentPage = {
  learnerId: string;
  page: number;
  pageSize: number;
  hasMore: boolean;
  documents: FamilyDocument[];
};
const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const unavailable = "Documents are unavailable. Refresh or contact the school.";

export function documentKindLabel(kind: FamilyDocument["documentKind"]): string {
  return {
    REPORT_CARD: "Report card",
    FEE_RECEIPT: "Fee receipt",
    BONAFIDE_CERTIFICATE: "Bonafide certificate",
  }[kind];
}

export function documentPath(learnerId: string, documentId?: string): string {
  if (!uuid.test(learnerId) || (documentId !== undefined && !uuid.test(documentId)))
    throw new Error(unavailable);
  return `/api/v1/family/children/${learnerId}/documents${documentId ? `/${documentId}/download` : ""}`;
}

export function documentProblem(status: number): string {
  if (status === 401) return "Your session has ended. Sign in again to view documents.";
  if (status === 403 || status === 404)
    return "This document is no longer available through your family access. Refresh or contact the school.";
  if (status === 503)
    return "Private document storage is temporarily unavailable. Please try again later.";
  return unavailable;
}

export function readDocumentPage(
  value: unknown,
  learnerId: string,
  page: number,
): FamilyDocumentPage {
  const data = value as Partial<FamilyDocumentPage> | null;
  if (
    !data ||
    data.learnerId !== learnerId ||
    data.page !== page ||
    data.pageSize !== 25 ||
    typeof data.hasMore !== "boolean" ||
    !Array.isArray(data.documents) ||
    data.documents.length > 25 ||
    !data.documents.every(
      (item) =>
        item &&
        uuid.test(item.id) &&
        ["REPORT_CARD", "FEE_RECEIPT", "BONAFIDE_CERTIFICATE"].includes(item.documentKind) &&
        typeof item.documentNumber === "string" &&
        item.documentNumber.length > 0 &&
        ["en-IN", "hi-IN"].includes(item.locale) &&
        Number.isFinite(Date.parse(item.issuedAt)) &&
        Number.isSafeInteger(item.sizeBytes) &&
        item.sizeBytes > 0,
    )
  )
    throw new Error(unavailable);
  return data as FamilyDocumentPage;
}

export async function loadFamilyDocuments(
  learnerId: string,
  page: number,
  signal: AbortSignal,
  paymentId?: string,
): Promise<FamilyDocumentPage> {
  if (!Number.isInteger(page) || page < 0 || page > 10000) throw new Error(unavailable);
  if (paymentId !== undefined && !uuid.test(paymentId)) throw new Error(unavailable);
  const query = new URLSearchParams({ page: String(page) });
  if (paymentId !== undefined) query.set("paymentId", paymentId);
  const response = await fetch(`${base}${documentPath(learnerId)}?${query}`, {
    credentials: "include",
    cache: "no-store",
    signal,
  });
  if (!response.ok) throw new Error(documentProblem(response.status));
  const result = readDocumentPage(await response.json(), learnerId, page);
  if (paymentId !== undefined && result.documents.some((item) => item.documentKind !== "FEE_RECEIPT"))
    throw new Error(unavailable);
  return result;
}

export async function loadFamilyPdf(
  learnerId: string,
  documentId: string,
  signal: AbortSignal,
): Promise<Blob> {
  const response = await fetch(`${base}${documentPath(learnerId, documentId)}`, {
    credentials: "include",
    cache: "no-store",
    signal,
  });
  if (!response.ok) throw new Error(documentProblem(response.status));
  if (
    response.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase() !== "application/pdf"
  )
    throw new Error("The server did not return a PDF. Please try again later.");
  const blob = await response.blob();
  if (!blob.size) throw new Error("The PDF is empty. Contact the school for a new copy.");
  return blob;
}
