import test from "node:test";
import assert from "node:assert/strict";
import {
  documentKindLabel,
  documentPath,
  documentProblem,
  readDocumentPage,
  loadFamilyDocuments,
  loadFamilyPdf,
} from "./family-documents.ts";

const learner = "30000000-0000-4000-8000-000000000001";
const id = "30000000-0000-4000-8000-000000000002";
const item = {
  id,
  documentKind: "REPORT_CARD",
  documentNumber: "SYN-RC-1",
  locale: "en-IN",
  issuedAt: "2026-09-01T00:00:00Z",
  sizeBytes: 123,
};
const page = { learnerId: learner, page: 0, pageSize: 25, hasMore: false, documents: [item] };

test("issued bonafide certificates use a distinct family label without relaxing document validation", () => {
  const certificate = {
    ...item,
    documentKind: "BONAFIDE_CERTIFICATE",
    documentNumber: "SYN/2026/000000001",
  };
  assert.deepEqual(readDocumentPage({ ...page, documents: [certificate] }, learner, 0).documents, [
    certificate,
  ]);
  assert.equal(documentKindLabel(certificate.documentKind), "Bonafide certificate");
  assert.equal(documentKindLabel("FEE_RECEIPT"), "Fee receipt");
  assert.equal(documentKindLabel("REPORT_CARD"), "Report card");
});

test("document paths accept identifiers only and cannot redirect to a different endpoint", () => {
  assert.equal(
    documentPath(learner, id),
    `/api/v1/family/children/${learner}/documents/${id}/download`,
  );
  for (const value of ["../fees", "https://other.test", "", `${learner}?page=1`]) {
    assert.throws(() => documentPath(value));
    assert.throws(() => documentPath(learner, value));
  }
});

test("document responses reject stale child/page data and invalid metadata", () => {
  assert.deepEqual(readDocumentPage(page, learner, 0), page);
  for (const bad of [
    null,
    { ...page, learnerId: id },
    { ...page, page: 1 },
    { ...page, pageSize: 100 },
    { ...page, hasMore: "yes" },
    { ...page, documents: Array(26).fill(item) },
    ...[
      { sizeBytes: 0 },
      { issuedAt: "invalid" },
      { documentKind: "INTERNAL_NOTE" },
      { locale: "unknown" },
      { id: "../other" },
    ].map((change) => ({ ...page, documents: [{ ...item, ...change }] })),
  ]) {
    assert.throws(() => readDocumentPage(bad, learner, 0));
  }
  assert.equal(readDocumentPage({ ...page, documents: [] }, learner, 0).documents.length, 0);
});

test("list requests use authenticated no-store fetch with cancellation and verify response scope", async (t) => {
  const signal = new AbortController().signal;
  t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.match(url, /\/documents\?page=0$/);
    assert.equal(options.credentials, "include");
    assert.equal(options.cache, "no-store");
    assert.equal(options.signal, signal);
    return Response.json(page);
  });
  assert.deepEqual(await loadFamilyDocuments(learner, 0, signal), page);
  await assert.rejects(() => loadFamilyDocuments(learner, -1, signal));
  await assert.rejects(() => loadFamilyDocuments(learner, 10001, signal));
});

test("PDF downloads reject HTML, empty files and unauthorized responses without leaking server details", async (t) => {
  let response = new Response("private database detail", { status: 403 });
  t.mock.method(globalThis, "fetch", async () => response);
  const signal = new AbortController().signal;
  await assert.rejects(() => loadFamilyPdf(learner, id, signal), /family access/);
  response = new Response("<html>Sign in</html>", { headers: { "content-type": "text/html" } });
  await assert.rejects(() => loadFamilyPdf(learner, id, signal), /did not return a PDF/);
  response = new Response("", { headers: { "content-type": "application/pdf" } });
  await assert.rejects(() => loadFamilyPdf(learner, id, signal), /empty/);
  assert.match(documentProblem(401), /Sign in again/);
  assert.match(documentProblem(503), /temporarily unavailable/);
  assert.doesNotMatch(documentProblem(500), /database/);
});

test("PDF downloads retain session/cancellation controls and return only the server PDF blob", async (t) => {
  const signal = new AbortController().signal;
  t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.match(url, /\/download$/);
    assert.equal(options.credentials, "include");
    assert.equal(options.cache, "no-store");
    assert.equal(options.signal, signal);
    return new Response("%PDF-synthetic-test", { headers: { "content-type": "application/pdf" } });
  });
  const blob = await loadFamilyPdf(learner, id, signal);
  assert.equal(blob.type, "application/pdf");
  assert.equal(await blob.text(), "%PDF-synthetic-test");
});
