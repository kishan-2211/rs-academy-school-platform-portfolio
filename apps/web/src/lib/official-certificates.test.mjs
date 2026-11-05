import test from "node:test";
import assert from "node:assert/strict";
import {
  validDocumentCopy,
  certificateSourcePath,
  issueAttempt,
  readCertificateSources,
  readCertificateReview,
} from "./official-certificates.ts";

const school = "30000000-0000-4000-8000-000000000001";
test("public verification describes historical enrolment without claiming current attendance or clearance", () => {
  assert.match(
    validDocumentCopy("BONAFIDE_CERTIFICATE").description,
    /recorded date, not current attendance/,
  );
  assert.doesNotMatch(validDocumentCopy("BONAFIDE_CERTIFICATE").title, /current/);
  assert.match(validDocumentCopy("REPORT_CARD").description, /current authoritative/);
  assert.deepEqual(validDocumentCopy("FEE_RECEIPT"), validDocumentCopy("REPORT_CARD"));
});
const id = "30000000-0000-4000-8000-000000000002";
const source = {
  id,
  sourceType: "STUDENT_ENROLMENT",
  documentKind: "BONAFIDE_CERTIFICATE",
  reference: "SYN-1",
  learnerName: "Synthetic learner",
  contextLabel: "VI · Synthetic session",
  sourceStatus: "ACTIVE",
};
const page = { page: 0, pageSize: 25, hasMore: false, sources: [source] };

test("certificate search keeps identifiers, literal terms and bounded pagination in their own parameters", () => {
  const path = new URL(
    certificateSourcePath(school, "A & 5%?schoolId=other", 4),
    "https://school.test",
  );
  assert.equal(path.searchParams.get("schoolId"), school);
  assert.equal(path.searchParams.get("query"), "A & 5%?schoolId=other");
  assert.equal(path.searchParams.get("page"), "4");
  for (const value of [-1, 1.5, 10001, NaN])
    assert.throws(() => certificateSourcePath(school, "", value));
  assert.throws(() => certificateSourcePath("../other", "", 0));
  assert.throws(() => certificateSourcePath(school, "a".repeat(101), 0));
});

test("certificate search rejects stale, malformed or ineligible source responses", () => {
  assert.deepEqual(readCertificateSources(page, 0), page);
  for (const invalid of [
    null,
    { ...page, page: 1 },
    { ...page, hasMore: "true" },
    { ...page, pageSize: 100 },
    { ...page, sources: Array(26).fill(source) },
    ...[
      { id: "bad" },
      { learnerName: null },
      { reference: "" },
      { documentKind: "FEE_RECEIPT" },
      { sourceType: "FEE_PAYMENT" },
      { sourceStatus: "ENDED" },
    ].map((change) => ({ ...page, sources: [{ ...source, ...change }] })),
  ]) {
    assert.throws(() => readCertificateSources(invalid, 0));
  }
});

test("an uncertain issuance retry reuses its key; changed requests and confirmed new attempts do not", () => {
  let count = 0;
  const key = () => `attempt-${++count}`;
  const first = issueAttempt(null, "request-one", key);
  assert.equal(issueAttempt(first, "request-one", key), first);
  assert.equal(count, 1);
  assert.equal(issueAttempt(first, "request-two", key).key, "attempt-2");
  assert.equal(issueAttempt(null, "request-one", key).key, "attempt-3");
});

test("review exposes only complete frozen facts for the expected pending document version", () => {
  const review = {
    documentId: id,
    documentKind: "BONAFIDE_CERTIFICATE",
    status: "PENDING_APPROVAL",
    rowVersion: 0,
    sourceSnapshot: {
      documentNumber: "SYN/2026/000000001",
      school: { name: "Synthetic school" },
      learner: { name: "Synthetic learner", admissionNumber: "SYN-1" },
      academic: { grade: "VI", session: "Synthetic session" },
      certificate: { asOf: "2026-09-24", enrolledSince: "2026-04-01" },
      storageKey: "must-not-display",
    },
    templateSnapshot: { title: "Synthetic certificate" },
  };
  const fields = readCertificateReview(review, id, 0);
  assert.equal(fields.length, 9);
  assert.equal(fields.find(([name]) => name === "Student")[1], "Synthetic learner");
  assert.doesNotMatch(JSON.stringify(fields), /must-not-display/);
  for (const change of [
    { documentId: school },
    { rowVersion: 1 },
    { status: "ISSUED" },
    { documentKind: "REPORT_CARD" },
    { sourceSnapshot: {} },
    { templateSnapshot: {} },
  ]) {
    assert.throws(() => readCertificateReview({ ...review, ...change }, id, 0));
  }
});
