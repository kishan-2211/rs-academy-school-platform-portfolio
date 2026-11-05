export type CertificateSource = {
  id: string;
  sourceType: "STUDENT_ENROLMENT";
  documentKind: "BONAFIDE_CERTIFICATE";
  reference: string;
  learnerName: string;
  contextLabel: string;
  sourceStatus: "ACTIVE";
};
export type CertificateSourcePage = {
  page: number;
  pageSize: 25;
  hasMore: boolean;
  sources: CertificateSource[];
};
export type IssueAttempt = { body: string; key: string };
export function validDocumentCopy(kind: string): { title: string; description: string } {
  return kind === "BONAFIDE_CERTIFICATE"
    ? {
        title: "This certificate's issuance is verifiable.",
        description:
          "This record confirms enrolment at the certificate's recorded date, not current attendance, character, transfer status or fee clearance.",
      }
    : {
        title: "This document is current and verifiable.",
        description: "The document matches a current authoritative school record.",
      };
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function issueAttempt(
  previous: IssueAttempt | null,
  body: string,
  createKey: () => string,
): IssueAttempt {
  return previous?.body === body ? previous : { body, key: createKey() };
}
export function certificateSourcePath(schoolId: string, query: string, page: number): string {
  if (
    !uuid.test(schoolId) ||
    query.length > 100 ||
    !Number.isInteger(page) ||
    page < 0 ||
    page > 10000
  )
    throw new Error("Choose a valid school, search and page.");
  return `/api/v1/documents/certificate-sources?${new URLSearchParams({ schoolId, query, page: String(page) })}`;
}
export function readCertificateSources(value: unknown, page: number): CertificateSourcePage {
  const data = value as CertificateSourcePage | null;
  if (
    !data ||
    data.page !== page ||
    data.pageSize !== 25 ||
    typeof data.hasMore !== "boolean" ||
    !Array.isArray(data.sources) ||
    data.sources.length > 25 ||
    !data.sources.every(
      (item) =>
        item &&
        uuid.test(item.id) &&
        item.sourceType === "STUDENT_ENROLMENT" &&
        item.documentKind === "BONAFIDE_CERTIFICATE" &&
        item.sourceStatus === "ACTIVE" &&
        [item.reference, item.learnerName, item.contextLabel].every(
          (text) => typeof text === "string" && text.length > 0,
        ),
    )
  )
    throw new Error("Enrolments could not be verified. Refresh the search.");
  return data;
}
export function readCertificateReview(
  value: unknown,
  documentId: string,
  version: number,
): [string, string][] {
  const data = value as {
    documentId?: string;
    documentKind?: string;
    status?: string;
    rowVersion?: number;
    sourceSnapshot?: {
      documentNumber?: string;
      school?: { name?: string };
      learner?: { name?: string; admissionNumber?: string };
      academic?: { grade?: string; session?: string };
      certificate?: { asOf?: string; enrolledSince?: string };
    };
    templateSnapshot?: { title?: string };
  } | null;
  if (
    !data ||
    data.documentId !== documentId ||
    data.rowVersion !== version ||
    data.documentKind !== "BONAFIDE_CERTIFICATE" ||
    data.status !== "PENDING_APPROVAL"
  )
    throw new Error("The request changed. Refresh the document workspace before deciding.");
  const s = data.sourceSnapshot;
  const fields = [
    ["Certificate", s?.documentNumber],
    ["School", s?.school?.name],
    ["Title", data.templateSnapshot?.title],
    ["Student", s?.learner?.name],
    ["Admission number", s?.learner?.admissionNumber],
    ["Class", s?.academic?.grade],
    ["Session", s?.academic?.session],
    ["Record date", s?.certificate?.asOf],
    ["Enrolled since", s?.certificate?.enrolledSince],
  ];
  if (!fields.every(([, value]) => typeof value === "string" && value.length > 0))
    throw new Error("The frozen certificate evidence is incomplete.");
  return fields as [string, string][];
}
