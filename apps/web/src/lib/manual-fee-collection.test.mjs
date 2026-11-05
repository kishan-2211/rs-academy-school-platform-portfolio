import assert from "node:assert/strict";
import test from "node:test";
import {
  availableManualChargePaise, buildManualCollectionRequest, currentSchoolLocalTime,
  freezeManualCollectionAttempt, manualAmountPaise, manualAmountText, ManualCollectionValidationError,
  schoolLocalTimeToInstant, selectedManualTotalPaise, sendManualCollectionAttempt,
} from "./manual-fee-collection.ts";

const now = new Date("2026-10-11T06:00:00Z");
const context = {
  schoolId: "synthetic-school", accountId: "synthetic-account", currencyCode: "INR", balance: 600,
  schoolTimeZone: "Asia/Kolkata", schoolToday: "2026-10-11",
  charges: [
    { id: "synthetic-october", label: "Synthetic October", feeHeadName: "Synthetic tuition", dueOn: "2026-10-01", outstandingAmount: 400, allowPartial: true, reservedAmount: 100 },
    { id: "synthetic-november", label: "Synthetic November", feeHeadName: "Synthetic tuition", dueOn: "2026-11-01", outstandingAmount: 200, allowPartial: false },
  ],
};
function draft(overrides = {}) {
  return { paymentMethod: "CASH", amount: "300.00", externalReference: "", notes: "Synthetic acceptance only",
    allocations: { "synthetic-october": "100.00", "synthetic-november": "200.00" }, actualLocal: "2026-10-11T10:00",
    utcOffset: "", chequeReceivedOn: "2026-10-11", postingOn: "", reviewReason: "", cashCounted: true,
    bankCreditConfirmed: false, bankCreditSource: "BANK_APP_CREDIT", ...overrides };
}
function rejected(code, mutate = {}, values = context) {
  assert.throws(() => buildManualCollectionRequest(values, draft(mutate), now),
    (error) => error instanceof ManualCollectionValidationError && error.code === code);
}

test("minor-unit arithmetic is exact and rejects excess precision, exponent syntax and invalid ranges", () => {
  assert.equal(manualAmountPaise("0.01"), 1);
  assert.equal(manualAmountPaise(0.1), 10);
  assert.equal(manualAmountPaise("100000000.00"), 10_000_000_000);
  assert.equal(manualAmountText(301), "3.01");
  assert.equal(selectedManualTotalPaise({ october: "0.10", november: "0.20" }), 30);
  for (const value of ["0.001", "1e3", "-1", "Infinity", "100000000.01", "00.1", NaN]) assert.equal(manualAmountPaise(value), null);
});

test("online reservations reduce eligible manual charge amounts and block reserved full-only periods", () => {
  assert.equal(availableManualChargePaise(context.charges[0]), 30_000);
  assert.equal(availableManualChargePaise({ ...context.charges[1], reservedAmount: 1 }), 0);
  assert.equal(availableManualChargePaise({ ...context.charges[0], reservedAmount: 401 }), null);
  rejected("CHARGE", { amount: "301", allocations: { "synthetic-october": "301" } });
  rejected("FULL_ONLY", { amount: "100", allocations: { "synthetic-november": "100" } });
  rejected("CHARGE", { amount: "1", allocations: { "synthetic-unknown": "1" } });
});

test("school-local conversion resolves India across UTC midnight independently of the device zone", () => {
  assert.equal(currentSchoolLocalTime("Asia/Kolkata", new Date("2026-10-10T20:00:00Z")), "2026-10-11T01:30:00");
  assert.deepEqual(schoolLocalTimeToInstant("2026-10-11T00:15", "Asia/Kolkata"), { kind: "valid", instant: "2026-10-10T18:45:00.000Z" });
  assert.deepEqual(schoolLocalTimeToInstant("2026-10-11T00:15", "Asia/Kolkata", "+05:30"), { kind: "valid", instant: "2026-10-10T18:45:00.000Z" });
  assert.deepEqual(schoolLocalTimeToInstant("2026-10-11T00:15", "Asia/Kolkata", "+00:00"), { kind: "invalid" });
  assert.equal(currentSchoolLocalTime("Synthetic/Unknown", now), null);
});

test("nonexistent and repeated local DST times fail closed unless an actual matching offset is supplied", () => {
  assert.deepEqual(schoolLocalTimeToInstant("2026-03-08T02:30", "America/New_York"), { kind: "invalid" });
  assert.deepEqual(schoolLocalTimeToInstant("2026-11-01T01:30", "America/New_York"), { kind: "ambiguous" });
  assert.deepEqual(schoolLocalTimeToInstant("2026-11-01T01:30", "America/New_York", "-04:00"), { kind: "valid", instant: "2026-11-01T05:30:00.000Z" });
  assert.deepEqual(schoolLocalTimeToInstant("2026-11-01T01:30", "America/New_York", "-05:00"), { kind: "valid", instant: "2026-11-01T06:30:00.000Z" });
  for (const local of ["2026-02-30T10:00", "2026-10-11T24:30", "2026-10-11", "2026-10-11T10:00:99"]) {
    assert.deepEqual(schoolLocalTimeToInstant(local, "Asia/Kolkata"), { kind: "invalid" });
  }
});

test("second-level received evidence is preserved instead of being truncated to an invented minute boundary", () => {
  assert.equal(currentSchoolLocalTime("Asia/Kolkata", new Date("2026-10-10T20:00:37Z")), "2026-10-11T01:30:37");
  assert.deepEqual(schoolLocalTimeToInstant("2026-10-11T10:00:37", "Asia/Kolkata"), { kind: "valid", instant: "2026-10-11T04:30:37.000Z" });
  const request = buildManualCollectionRequest(context, draft({ actualLocal: "2026-10-11T10:00:37" }), now);
  assert.equal(request.actualReceivedAt, "2026-10-11T04:30:37.000Z");
  assert.equal(request.receivedOn, "2026-10-11");
});

test("cash request preserves actual time, original school date and explicit multi-period allocations", () => {
  const request = buildManualCollectionRequest(context, draft(), now);
  assert.deepEqual(request, {
    schoolId: "synthetic-school", accountId: "synthetic-account", paymentMethod: "CASH", externalReference: null,
    amount: 300, receivedOn: "2026-10-11", notes: "Synthetic acceptance only",
    allocations: [{ chargeId: "synthetic-november", amount: 200 }, { chargeId: "synthetic-october", amount: 100 }],
    actualReceivedAt: "2026-10-11T04:30:00.000Z", postingOn: null, reviewReason: null, bankCreditVerification: null,
  });
  rejected("CASH", { cashCounted: false });
  rejected("DATE", { actualLocal: "2026-10-11T12:00" });
});

test("allocation sums, account overpayments, unknown periods and unallocated advances fail before dispatch", () => {
  rejected("ALLOCATIONS", { amount: "200" });
  rejected("ALLOCATIONS", { allocations: {} });
  rejected("BALANCE", { amount: "600.01", allocations: { "synthetic-october": "600.01" } });
  rejected("AMOUNT", { amount: "0" });
  rejected("NOTES", { notes: "x".repeat(501) });
});

test("closed original day requires a reviewed eligible open date while preserving original facts", () => {
  const closed = { ...context, latestClosedDate: "2026-10-10" };
  rejected("POSTING_DATE", { actualLocal: "2026-10-09T10:00" }, closed);
  rejected("REVIEW", { actualLocal: "2026-10-09T10:00", postingOn: "2026-10-11" }, closed);
  const request = buildManualCollectionRequest(closed, draft({ actualLocal: "2026-10-09T10:00", postingOn: "2026-10-11", reviewReason: "Synthetic closed-day review" }), now);
  assert.equal(request.receivedOn, "2026-10-09");
  assert.equal(request.actualReceivedAt, "2026-10-09T04:30:00.000Z");
  assert.equal(request.postingOn, "2026-10-11");
  assert.equal(request.reviewReason, "Synthetic closed-day review");
  rejected("POSTING_DATE", { postingOn: "2026-10-10", reviewReason: "Synthetic review" });
  rejected("REVIEW", { actualLocal: "2026-10-09T10:00", postingOn: "2026-10-11", reviewReason: "ab" });
});

test("UPI and bank transfer require actual verified school credit, same credited reference and no payment credentials", () => {
  for (const paymentMethod of ["SCHOOL_UPI", "BANK_TRANSFER"]) {
    rejected("CREDIT", { paymentMethod, externalReference: "SYN-CREDIT-001" });
    rejected("REFERENCE", { paymentMethod, bankCreditConfirmed: true });
    rejected("REFERENCE", { paymentMethod, externalReference: "नकली-reference", bankCreditConfirmed: true });
    const request = buildManualCollectionRequest(context, draft({ paymentMethod, externalReference: " SYN-CREDIT-001 ", bankCreditConfirmed: true, bankCreditSource: "BANK_STATEMENT_CREDIT" }), now);
    assert.equal(request.externalReference, "SYN-CREDIT-001");
    assert.deepEqual(request.bankCreditVerification, { sourceKind: "BANK_STATEMENT_CREDIT", confirmedReceived: true, creditedEntryReference: "SYN-CREDIT-001" });
    assert.equal(Object.hasOwn(request, "cardNumber"), false);
    assert.equal(Object.hasOwn(request, "upiPin"), false);
  }
});

test("cheque retains the existing payload and does not assert bank clearance or add new manual evidence", () => {
  const request = buildManualCollectionRequest(context, draft({ paymentMethod: "CHEQUE", externalReference: "SYN-CHEQUE-001", cashCounted: false,
    actualLocal: "", postingOn: "2026-10-11", reviewReason: "Must not be sent", bankCreditConfirmed: true }), now);
  assert.equal(request.receivedOn, "2026-10-11");
  for (const field of ["actualReceivedAt", "postingOn", "reviewReason", "bankCreditVerification"]) assert.equal(Object.hasOwn(request, field), false);
});

test("frozen attempts detach from the editable draft and remain immutable including allocations and evidence", () => {
  const request = buildManualCollectionRequest(context, draft(), now);
  const attempt = freezeManualCollectionAttempt(request, "synthetic-stable-key");
  request.allocations[0].amount = 1;
  assert.equal(attempt.request.allocations[0].amount, 200);
  assert.equal(JSON.parse(attempt.body).allocations[0].amount, 200);
  assert.throws(() => { attempt.request.allocations[0].amount = 2; }, TypeError);
  assert.throws(() => { attempt.request.amount = 2; }, TypeError);
});

function transport(fetch, overrides = {}) {
  return { apiUrl: "https://api.example.invalid", csrfToken: async () => "synthetic-form-token", shouldDispatch: () => true, fetch, ...overrides };
}
test("uncertain dispatch retry uses identical JSON, scope and key with secure browser cookies and CSRF", async () => {
  const attempt = freezeManualCollectionAttempt(buildManualCollectionRequest(context, draft(), now), "synthetic-stable-key");
  const calls = [];
  const network = transport(async (...args) => {
    calls.push(args);
    if (calls.length === 1) throw new TypeError("Synthetic dropped response");
    return { ok: true, json: async () => ({ id: "synthetic-payment", receiptNumber: "SYN/000003" }) };
  });
  assert.deepEqual(await sendManualCollectionAttempt(attempt, network), { kind: "uncertain" });
  assert.deepEqual(await sendManualCollectionAttempt(attempt, network), { kind: "posted", id: "synthetic-payment", receiptNumber: "SYN/000003" });
  assert.deepEqual(calls[0], calls[1]);
  const [url, request] = calls[0];
  assert.equal(url, "https://api.example.invalid/api/v1/finance/payments");
  assert.equal(request.credentials, "include");
  assert.equal(request.headers["X-CSRF-TOKEN"], "synthetic-form-token");
  assert.equal(request.headers["Idempotency-Key"], "synthetic-stable-key");
});
test("secure-token failure and unmount during token lookup never dispatch a new collection", async () => {
  const attempt = freezeManualCollectionAttempt(buildManualCollectionRequest(context, draft(), now), "synthetic-stable-key");
  let calls = 0;
  const network = async () => { calls++; };
  assert.deepEqual(await sendManualCollectionAttempt(attempt, transport(network, { csrfToken: async () => { throw new Error("Synthetic unavailable token"); } })), { kind: "not-dispatched" });
  assert.deepEqual(await sendManualCollectionAttempt(attempt, transport(network, { shouldDispatch: () => false })), { kind: "cancelled" });
  assert.equal(calls, 0);
});
test("missing success receipt, unreadable responses, server failure and timeout stay uncertain", async () => {
  const attempt = freezeManualCollectionAttempt(buildManualCollectionRequest(context, draft(), now), "synthetic-stable-key");
  for (const response of [
    { ok: true, json: async () => ({ id: "synthetic-payment" }) },
    { ok: true, json: async () => { throw new Error("Synthetic lost JSON"); } },
    { ok: false, status: 500 }, { ok: false, status: 408 }, { ok: false, status: 429 },
  ]) assert.deepEqual(await sendManualCollectionAttempt(attempt, transport(async () => response)), { kind: "uncertain" });
});
test("controlled client rejection exposes a code only and does not copy arbitrary server diagnostics", async () => {
  const attempt = freezeManualCollectionAttempt(buildManualCollectionRequest(context, draft(), now), "synthetic-stable-key");
  const result = await sendManualCollectionAttempt(attempt, transport(async () => ({ ok: false, status: 409,
    json: async () => ({ code: "MANUAL_BANK_CREDIT_ALREADY_RECORDED", detail: "Synthetic private diagnostic" }) })));
  assert.deepEqual(result, { kind: "rejected", code: "MANUAL_BANK_CREDIT_ALREADY_RECORDED" });
});
