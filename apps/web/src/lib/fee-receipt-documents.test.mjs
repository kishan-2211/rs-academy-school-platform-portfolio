import assert from "node:assert/strict";
import test from "node:test";
import {
  availableReceiptTemplates,
  loadFeeReceiptPage,
  loadStaffReceiptPdf,
  readFeeReceiptPage,
  receiptCsrfToken,
  receiptDocumentErrors,
  receiptDocumentPath,
  receiptDocumentProblem,
  receiptIssueBody,
  sendReceiptIssue,
} from "./fee-receipt-documents.ts";
import { loadFamilyDocuments } from "./family-documents.ts";

const school = "40000000-0000-4000-8000-000000000001";
const payment = "40000000-0000-4000-8000-000000000002";
const template = "40000000-0000-4000-8000-000000000003";
const document = "40000000-0000-4000-8000-000000000004";
const key = "40000000-0000-4000-8000-000000000005";
const learner = "40000000-0000-4000-8000-000000000006";
const item = {
  id: document,
  templateId: template,
  templateName: "Synthetic receipt template",
  documentNumber: "SYN-PDF-1",
  documentKind: "FEE_RECEIPT",
  sourceType: "FEE_PAYMENT",
  sourceId: payment,
  status: "ISSUED",
  requestedAt: "2026-10-01T00:00:00Z",
};
const page = {
  schoolId: school,
  paymentId: payment,
  receiptNumber: "SYN-FEE-1",
  sourceStatus: "POSTED",
  canIssue: true,
  page: 0,
  pageSize: 25,
  hasMore: false,
  templates: [
    {
      id: template,
      displayName: "Synthetic school receipt",
      locale: "en-IN",
      documentKind: "FEE_RECEIPT",
      status: "PUBLISHED",
      approvalRequired: true,
    },
  ],
  documents: [item],
};

test("receipt lookup validates every scope and page before making a bounded exact-source request", () => {
  assert.equal(
    receiptDocumentPath(school, payment, 0),
    `/api/v1/documents/fee-receipts/${payment}?schoolId=${school}&page=0`,
  );
  for (const value of ["../fees", "https://other.invalid", `${payment}?page=2`, ""]) {
    assert.throws(() => receiptDocumentPath(value, payment, 0));
    assert.throws(() => receiptDocumentPath(school, value, 0));
  }
  for (const number of [-1, 10001, 0.5, NaN])
    assert.throws(() => receiptDocumentPath(school, payment, number));
});
test("receipt metadata cannot silently cross a school, payment, document kind or stale page", () => {
  assert.deepEqual(readFeeReceiptPage(page, school, payment, 0), page);
  const patches = [
    null,
    { ...page, schoolId: payment },
    { ...page, paymentId: school },
    { ...page, page: 1 },
    { ...page, pageSize: 100 },
    { ...page, hasMore: "true" },
    { ...page, sourceStatus: "REVERSED" },
    { ...page, documents: Array(26).fill(item) },
    { ...page, documents: [item, item] },
    ...[
      { sourceId: school },
      { sourceType: "STUDENT_ENROLMENT" },
      { documentKind: "REPORT_CARD" },
      { status: "UNKNOWN" },
      { requestedAt: "not a date" },
      { templateId: "bad" },
    ].map((patch) => ({ ...page, documents: [{ ...item, ...patch }] })),
    { ...page, templates: [{ ...page.templates[0], status: "DRAFT" }] },
    { ...page, templates: [{ ...page.templates[0], approvalRequired: "yes" }] },
  ];
  for (const value of patches) assert.throws(() => readFeeReceiptPage(value, school, payment, 0));
  assert.equal(
    readFeeReceiptPage({ ...page, sourceStatus: "REVERSED", canIssue: false }, school, payment, 0)
      .sourceStatus,
    "REVERSED",
  );
});
test("all non-rejected historical statuses occupy their template slot, and partial history never permits issuing", () => {
  for (const status of ["PENDING_APPROVAL", "ISSUED", "CANCELLED", "SUPERSEDED"])
    assert.deepEqual(availableReceiptTemplates(page, [{ ...item, status }]), []);
  assert.equal(availableReceiptTemplates(page, [{ ...item, status: "REJECTED" }]).length, 1);
  assert.deepEqual(availableReceiptTemplates({ ...page, hasMore: true }, []), []);
  assert.deepEqual(availableReceiptTemplates({ ...page, canIssue: false }, []), []);
  assert.deepEqual(availableReceiptTemplates({ ...page, sourceStatus: "REVERSED" }, []), []);
});
test("exact staff lookup keeps session, no-store, cancellation and safe access errors", async (t) => {
  const signal = new AbortController().signal;
  let response = Response.json(page);
  t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.match(url, new RegExp(`/fee-receipts/${payment}\\?schoolId=${school}&page=0$`));
    assert.equal(options.credentials, "include");
    assert.equal(options.cache, "no-store");
    assert.equal(options.signal, signal);
    return response;
  });
  assert.deepEqual(await loadFeeReceiptPage(school, payment, 0, signal), page);
  response = new Response("Synthetic internal database detail", { status: 403 });
  await assert.rejects(() => loadFeeReceiptPage(school, payment, 0, signal), /access is no longer/);
  assert.equal(receiptDocumentProblem(401), receiptDocumentErrors.session);
  assert.equal(receiptDocumentProblem(404), receiptDocumentErrors.scope);
  assert.equal(receiptDocumentProblem(503), receiptDocumentErrors.storage);
  assert.doesNotMatch(receiptDocumentProblem(500), /database/);
});
test("family fee-PDF lookup keeps the old page contract but filters by the exact own-child payment", async (t) => {
  const signal = new AbortController().signal;
  let kind = "FEE_RECEIPT";
  let calls = 0;
  t.mock.method(globalThis, "fetch", async (url, options) => {
    calls++;
    assert.match(url, new RegExp(`/children/${learner}/documents\\?page=0&paymentId=${payment}$`));
    assert.equal(options.credentials, "include");
    assert.equal(options.cache, "no-store");
    assert.equal(options.signal, signal);
    return Response.json({
      learnerId: learner,
      page: 0,
      pageSize: 25,
      hasMore: false,
      documents: [
        {
          id: document,
          documentKind: kind,
          documentNumber: "SYN-PDF-1",
          locale: "en-IN",
          issuedAt: item.requestedAt,
          sizeBytes: 100,
        },
      ],
    });
  });
  assert.equal((await loadFamilyDocuments(learner, 0, signal, payment)).documents.length, 1);
  kind = "REPORT_CARD";
  await assert.rejects(() => loadFamilyDocuments(learner, 0, signal, payment));
  await assert.rejects(() => loadFamilyDocuments(learner, 0, signal, "../other"));
  assert.equal(calls, 2);
});
test("PDF issue builds only a scoped immutable document request, not a second financial payment", () => {
  assert.deepEqual(
    JSON.parse(receiptIssueBody(school, payment, template, "  Synthetic PDF copy  ")),
    {
      schoolId: school,
      templateId: template,
      sourceType: "FEE_PAYMENT",
      sourceId: payment,
      supersedesDocumentId: null,
      reason: "Synthetic PDF copy",
    },
  );
  for (const reason of ["", "ab", "a".repeat(241)])
    assert.throws(() => receiptIssueBody(school, payment, template, reason));
  assert.throws(() => receiptIssueBody(school, "../fees", template, "Synthetic copy"));
});
test("issue sends the exact in-memory key and body with CSRF and verifies server acknowledgment", async (t) => {
  const signal = new AbortController().signal;
  const attempt = { key, body: receiptIssueBody(school, payment, template, "Synthetic PDF copy") };
  let response = {
    id: document,
    documentNumber: "SYN-PDF-1",
    status: "PENDING_APPROVAL",
    rowVersion: 0,
  };
  const requests = [];
  t.mock.method(globalThis, "fetch", async (url, options) => {
    requests.push([url, options]);
    assert.match(url, /\/documents\/issues$/);
    assert.equal(options.credentials, "include");
    assert.equal(options.cache, "no-store");
    assert.equal(options.signal, signal);
    assert.equal(options.headers["Idempotency-Key"], key);
    assert.equal(options.headers["X-CSRF-TOKEN"], "synthetic-form-token");
    assert.equal(options.body, attempt.body);
    return Response.json(response);
  });
  assert.deepEqual(await sendReceiptIssue(attempt, "synthetic-form-token", signal), response);
  await sendReceiptIssue(attempt, "synthetic-form-token", signal);
  assert.deepEqual(requests[0], requests[1]);
  response = { ...response, id: "wrong" };
  await assert.rejects(() => sendReceiptIssue(attempt, "synthetic-form-token", signal));
});
test("staff PDF download never saves HTML, an empty response, or a forbidden body", async (t) => {
  const signal = new AbortController().signal;
  let response = new Response("<html>Synthetic login</html>", {
    headers: { "content-type": "text/html" },
  });
  t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.match(url, new RegExp(`/documents/${document}/download\\?schoolId=${school}$`));
    assert.equal(options.credentials, "include");
    assert.equal(options.cache, "no-store");
    assert.equal(options.signal, signal);
    return response;
  });
  await assert.rejects(() => loadStaffReceiptPdf(school, document, signal), /valid non-empty PDF/);
  response = new Response("", { headers: { "content-type": "application/pdf" } });
  await assert.rejects(() => loadStaffReceiptPdf(school, document, signal), /valid non-empty PDF/);
  response = new Response("Synthetic private detail", { status: 401 });
  await assert.rejects(() => loadStaffReceiptPdf(school, document, signal), /session has ended/);
  response = new Response("%PDF-synthetic", {
    headers: { "content-type": "application/pdf; charset=binary" },
  });
  assert.equal(
    await (await loadStaffReceiptPdf(school, document, signal)).text(),
    "%PDF-synthetic",
  );
});
test("CSRF recovery uses the session endpoint but never persists credentials or reads a bearer token", async (t) => {
  const signal = new AbortController().signal;
  const cookie = { cookie: "" };
  const original = Object.getOwnPropertyDescriptor(globalThis, "document");
  Object.defineProperty(globalThis, "document", { value: cookie, configurable: true });
  t.after(() => {
    if (original) Object.defineProperty(globalThis, "document", original);
    else delete globalThis.document;
  });
  let status = 200;
  t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.match(url, /\/auth\/sessions\/current$/);
    assert.equal(options.cache, "no-store");
    assert.equal(options.signal, signal);
    if (status === 200) cookie.cookie = "educore_csrf=synthetic%2Ftoken";
    return new Response(null, { status });
  });
  assert.equal(await receiptCsrfToken(signal), "synthetic/token");
  cookie.cookie = "educore_csrf=%INVALID";
  await assert.rejects(() => receiptCsrfToken(signal), /secure form token/);
  cookie.cookie = "";
  status = 401;
  await assert.rejects(() => receiptCsrfToken(signal), /session has ended/);
});
