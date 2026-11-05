import assert from "node:assert/strict";
import test from "node:test";
import * as gateway from "./family-gateway-payments.ts";

const learner = "71000000-0000-4000-8000-000000000001", account = "71000000-0000-4000-8000-000000000002";
const charge = "71000000-0000-4000-8000-000000000003", secondCharge = "71000000-0000-4000-8000-000000000004";
const attempt = "71000000-0000-4000-8000-000000000005", requestId = "71000000-0000-4000-8000-000000000006", receipt = "71000000-0000-4000-8000-000000000007";
const fees = () => ({ learnerId: learner, accountId: account, currencyCode: "INR", charges: [{ id: charge, label: "Synthetic tuition", dueOn: "2026-10-01", outstandingAmount: 110.2, reservedAmount: 10, allowPartial: true }] });
const payment = (changes = {}) => ({ id: attempt, learnerId: learner, accountId: account, sessionName: "Synthetic session", amount: 100.2, currencyCode: "INR", status: "PENDING", createdAt: "2026-10-01T01:00:00Z", expiresAt: "2030-10-01T01:15:00Z", originalBusinessDate: "2026-10-01", reservationActive: true, initiatedByYou: true, canContinue: false, selectedCharges: [{ chargeId: charge, label: "Synthetic tuition", dueOn: "2026-10-01", amount: 100.2 }], receipt: null, ...changes });
const history = (changes = {}) => ({ learnerId: learner, availability: "AVAILABLE", page: 0, pageSize: 20, hasMore: false, payments: [payment()], ...changes });
const ready = () => payment({ status: "READY", canContinue: true });
const signal = () => new AbortController().signal;

test("decimal money uses exact integer paise including ₹0.01, common binary fractions and the backend maximum", () => {
  for (const [value, expected] of [["0.01", 1], [29.29, 2929], ["100.2", 10020], [" 001.05 ", 105], [100_000_000, 10_000_000_000]]) {
    assert.equal(gateway.paymentPaise(value), expected);
    assert.equal(gateway.paymentPaise(gateway.paymentAmountInput(expected)), expected);
  }
  for (const value of [".01", "1.001", "1e2", "-1", "", "NaN", "Infinity", -0.01, Infinity, 100_000_000.01, 0.1 + 0.2]) assert.throws(() => gateway.paymentPaise(value));
  for (const value of [-1, 1.1, Infinity, 10_000_000_001]) assert.throws(() => gateway.paymentAmountInput(value));
});
test("available amounts subtract held reservations and never silently default absent or inconsistent data to zero", () => {
  assert.equal(gateway.availablePaymentPaise(fees().charges[0]), 10020);
  for (const changes of [{ reservedAmount: undefined }, { reservedAmount: 120 }, { outstandingAmount: -1 }, { allowPartial: "yes" }, { dueOn: "2026-02-30" }, { id: "../charge" }]) assert.throws(() => gateway.availablePaymentPaise({ ...fees().charges[0], ...changes }));
});
test("partial allocations honor exact available paise without inventing a ₹1 minimum", () => {
  assert.deepEqual(gateway.validatePaymentSelection(fees(), { [charge]: "0.01" }), [{ chargeId: charge, amount: 0.01 }]);
  assert.deepEqual(gateway.validatePaymentSelection(fees(), { [charge]: "100.20" }), [{ chargeId: charge, amount: 100.2 }]);
  for (const amount of ["0", "100.21", "110.20", "0.001"]) assert.throws(() => gateway.validatePaymentSelection(fees(), { [charge]: amount }));
});
test("full-only installments require the entire outstanding amount and cannot split a partially reserved obligation", () => {
  const full = fees(); full.charges[0] = { ...full.charges[0], allowPartial: false, reservedAmount: 0 };
  assert.deepEqual(gateway.validatePaymentSelection(full, { [charge]: "110.20" }), [{ chargeId: charge, amount: 110.2 }]);
  assert.throws(() => gateway.validatePaymentSelection(full, { [charge]: "100.20" }));
  full.charges[0].reservedAmount = 10;
  assert.throws(() => gateway.validatePaymentSelection(full, { [charge]: "100.20" }));
  assert.throws(() => gateway.validatePaymentSelection(full, { [charge]: "110.20" }));
});
test("selection rejects unknown or duplicate charges, no account, wrong currency and a total above the provider bound", () => {
  for (const context of [{ ...fees(), accountId: null }, { ...fees(), currencyCode: "USD" }, { ...fees(), charges: [fees().charges[0], fees().charges[0]] }]) assert.throws(() => gateway.validatePaymentSelection(context, { [charge]: "1" }));
  assert.throws(() => gateway.validatePaymentSelection(fees(), {}));
  assert.throws(() => gateway.validatePaymentSelection(fees(), { [secondCharge]: "1" }));
  const maximum = fees(); maximum.charges = [charge, secondCharge].map(id => ({ ...maximum.charges[0], id, outstandingAmount: 100_000_000, reservedAmount: 0 }));
  assert.throws(() => gateway.validatePaymentSelection(maximum, { [charge]: "100000000", [secondCharge]: "0.01" }));
});
test("new requests freeze one serialized identity/body and uncertain retries ignore edited form and refreshed balance", () => {
  let keys = 0;
  const original = gateway.prepareFamilyPayment(null, fees(), { [charge]: "100.20" }, "0000000000", () => { keys++; return requestId; });
  assert.ok(Object.isFrozen(original));
  assert.deepEqual(JSON.parse(original.body), { clientRequestId: requestId, allocations: [{ chargeId: charge, amount: 100.2 }], payerPhone: "0000000000" });
  const changed = fees(); changed.charges[0].outstandingAmount = 0;
  assert.equal(gateway.prepareFamilyPayment(original, changed, {}, "edited-invalid-phone", () => { keys++; return receipt; }), original);
  assert.equal(keys, 1);
  assert.throws(() => gateway.prepareFamilyPayment(original, { ...fees(), learnerId: receipt }, {}, "", () => receipt));
  assert.throws(() => gateway.prepareFamilyPayment(original, { ...fees(), accountId: receipt }, {}, "", () => receipt));
});
test("bad phone and request identities are rejected before preparing a payment", () => {
  for (const phone of ["", "123", "+910000000000", "0000000000 ", "१२३४५६७८९०"]) assert.throws(() => gateway.prepareFamilyPayment(null, fees(), { [charge]: "1" }, phone, () => requestId));
  assert.throws(() => gateway.prepareFamilyPayment(null, fees(), { [charge]: "1" }, "0000000000", () => "../new"));
});
test("explicit historical/current account selection is frozen in the POST body while default selection remains omitted", () => {
  const selected = { ...fees(), explicitAccountId: account, accountStatus: "ACTIVE" };
  const original = gateway.prepareFamilyPayment(null, selected, { [charge]: "1" }, "0000000000", () => requestId);
  assert.equal(JSON.parse(original.body).accountId, account); assert.equal(original.explicitAccountId, account);
  assert.equal(gateway.prepareFamilyPayment(original, { ...selected, accountStatus: "CLOSED" }, {}, "changed", () => receipt), original, "A frozen exact retry remains replayable after account closure");
  assert.throws(() => gateway.prepareFamilyPayment(original, fees(), {}, "", () => receipt), "Explicit intent cannot become today's default account");
  const legacy = gateway.prepareFamilyPayment(null, fees(), { [charge]: "1" }, "0000000000", () => requestId);
  assert.ok(!Object.hasOwn(JSON.parse(legacy.body), "accountId"));
  assert.throws(() => gateway.prepareFamilyPayment(legacy, selected, {}, "", () => receipt), "Legacy intent cannot gain explicit-account terms on retry");
});
test("closed or mismatched explicitly selected accounts cannot start fresh payments", () => {
  for (const changed of [{ accountStatus: "CLOSED" }, { explicitAccountId: receipt }, { explicitAccountId: "../account" }]) {
    assert.throws(() => gateway.prepareFamilyPayment(null, { ...fees(), ...changed }, { [charge]: "1" }, "0000000000", () => requestId));
  }
});
test("known payment states are distinct and PAID requires the exact posted receipt metadata", () => {
  for (const status of ["PENDING", "READY", "EXPIRED", "RELEASED", "REVIEW_REQUIRED"]) assert.equal(gateway.readFamilyPayment(payment({ status }), learner, attempt).status, status);
  const paid = payment({ status: "PAID", reservationActive: false, receipt: { paymentId: receipt, receiptNumber: "SYN-RECEIPT-1", postingOn: "2026-10-02" } });
  assert.deepEqual(gateway.readFamilyPayment(paid, learner, attempt), paid);
  assert.throws(() => gateway.readFamilyPayment(payment({ status: "PAID" }), learner));
  assert.throws(() => gateway.readFamilyPayment({ ...paid, status: "PENDING" }, learner));
});
test("review may preserve an already posted original receipt while additional money remains under review", () => {
  const reviewed = payment({ status: "REVIEW_REQUIRED", receipt: { paymentId: receipt, receiptNumber: "SYN-RECEIPT-1", postingOn: "2026-10-02" } });
  assert.equal(gateway.readFamilyPayment(reviewed).receipt.receiptNumber, "SYN-RECEIPT-1");
});
test("unknown states, forged continuation flags, wrong scope, duplicate allocations and mismatched totals fail closed", () => {
  for (const changes of [{ status: "SUCCESS_FROM_BROWSER" }, { status: "FAILED" }, { canContinue: true }, { status: "READY", canContinue: true, initiatedByYou: false }, { status: "READY", canContinue: true, reservationActive: false }, { amount: 100.21 }, { currencyCode: "USD" }, { expiresAt: "2026-09-01T01:00:00Z" }, { selectedCharges: [payment().selectedCharges[0], payment().selectedCharges[0]] }]) assert.throws(() => gateway.readFamilyPayment(payment(changes), learner, attempt));
  assert.throws(() => gateway.readFamilyPayment(payment(), receipt));
  assert.throws(() => gateway.readFamilyPayment(payment(), learner, receipt));
});
test("history preserves disabled-provider records while enforcing exact child, bounded page and unique attempts", () => {
  for (const availability of ["AVAILABLE", "DISABLED", "UNAVAILABLE"]) assert.equal(gateway.readFamilyPaymentHistory(history({ availability }), learner, 0).payments.length, 1);
  for (const changes of [{ learnerId: receipt }, { page: 1 }, { pageSize: 21 }, { hasMore: "yes" }, { availability: "ENABLED" }, { payments: [payment(), payment()] }, { payments: Array(21).fill(payment()) }]) assert.throws(() => gateway.readFamilyPaymentHistory(history(changes), learner, 0));
  assert.throws(() => gateway.readFamilyPaymentHistory(history({ learnerId: "../child", payments: [] }), "../child", 0));
});
test("continuation URLs allow exact canonical hosted link URLs only, not lookalikes, query redirects or normalized paths", () => {
  for (const host of ["payments.cashfree.com", "payments-test.cashfree.com"]) assert.equal(gateway.safeFamilyCheckoutUrl(`https://${host}/links/synthetic_123-ab`), `https://${host}/links/synthetic_123-ab`);
  for (const url of ["http://payments.cashfree.com/links/x", "https://payments.cashfree.com.evil.test/links/x", "https://payments.cashfree.com@evil.test/links/x", "https://user@payments.cashfree.com/links/x", "https://payments.cashfree.com:443/links/x", "https://payments.cashfree.com/links/x?redirect=evil", "https://payments.cashfree.com/links/x#fragment", "https://payments.cashfree.com/links/../links/x", "https://payments.cashfree.com/links/%78", "https://payments.cashfree.com/orders/x", "javascript:alert(1)", "//payments.cashfree.com/links/x", "https://PAYMENTS.CASHFREE.COM/links/x", null]) assert.throws(() => gateway.safeFamilyCheckoutUrl(url));
});
test("history and detail fetches use actual family endpoints, HttpOnly-session credentials, no-store and caller cancellation", async t => {
  const calls = [], abort = signal();
  t.mock.method(globalThis, "fetch", async (url, options) => { calls.push({ url, options }); return Response.json(url.includes("children") ? history() : payment()); });
  assert.equal((await gateway.loadFamilyPaymentHistory(learner, 0, abort)).learnerId, learner);
  assert.equal((await gateway.loadFamilyPayment(attempt, abort)).id, attempt);
  assert.ok(calls[0].url.endsWith(`/api/v1/family/children/${learner}/fee-payments?page=0`));
  assert.ok(calls[1].url.endsWith(`/api/v1/family/fee-payments/${attempt}`));
  calls.forEach(({ options }) => { assert.equal(options.credentials, "include"); assert.equal(options.cache, "no-store"); assert.equal(options.signal, abort); });
  await assert.rejects(gateway.loadFamilyPayment("../other", abort));
  await assert.rejects(gateway.loadFamilyPaymentHistory(learner, -1, abort));
  assert.equal(calls.length, 2);
});
test("private authorization failures are typed and never expose server diagnostics or record identifiers", async t => {
  for (const status of [401, 403, 404]) {
    t.mock.method(globalThis, "fetch", async () => new Response("synthetic-private-server-diagnostic", { status }));
    await assert.rejects(gateway.loadFamilyPayment(attempt, signal()), error => error instanceof gateway.FamilyPaymentAccessError && error.status === status && !error.message.includes("diagnostic"));
  }
});
test("CSRF discovery uses the existing session endpoint only when needed and rejects absent or malformed tokens", async t => {
  let cookie = "", calls = 0;
  t.mock.method(globalThis, "fetch", async (url, options) => { calls++; assert.ok(url.endsWith("/api/v1/auth/sessions/current")); assert.equal(options.credentials, "include"); assert.equal(options.cache, "no-store"); cookie = "educore_csrf=synthetic%2Dcsrf"; return new Response(null, { status: 204 }); });
  assert.equal(await gateway.familyPaymentCsrf(signal(), () => cookie), "synthetic-csrf");
  assert.equal(await gateway.familyPaymentCsrf(signal(), () => cookie), "synthetic-csrf"); assert.equal(calls, 1);
  for (const invalid of ["educore_csrf=%", "educore_csrf=%0Aprivate", `educore_csrf=${"x".repeat(4097)}`]) await assert.rejects(gateway.familyPaymentCsrf(signal(), () => invalid), { message: gateway.familyPaymentErrors.csrf });
});
test("unknown create results retry exactly the frozen original body rather than issuing another request identity", async t => {
  const bodies = [], original = gateway.prepareFamilyPayment(null, fees(), { [charge]: "100.20" }, "0000000000", () => requestId);
  t.mock.method(globalThis, "fetch", async (url, options) => { assert.ok(url.endsWith(`/children/${learner}/fee-payments`)); assert.equal(options.method, "POST"); assert.equal(options.headers["X-CSRF-TOKEN"], "synthetic-csrf"); assert.equal(options.cache, "no-store"); bodies.push(options.body); if (bodies.length === 1) throw new Error("synthetic-network-uncertainty"); return Response.json(payment(), { status: 201 }); });
  await assert.rejects(gateway.sendFamilyPayment(original, "synthetic-csrf", signal()));
  assert.equal((await gateway.sendFamilyPayment(original, "synthetic-csrf", signal())).id, attempt);
  assert.deepEqual(bodies, [original.body, original.body]);
});
test("an acknowledged initiation must match the original account and exact allocation, not merely the same total", async t => {
  const prepared = gateway.prepareFamilyPayment(null, fees(), { [charge]: "100.20" }, "0000000000", () => requestId);
  for (const mismatch of [payment({ accountId: receipt }), payment({ selectedCharges: [{ ...payment().selectedCharges[0], chargeId: secondCharge }] })]) {
    t.mock.method(globalThis, "fetch", async () => Response.json(mismatch));
    await assert.rejects(gateway.sendFamilyPayment(prepared, "synthetic-csrf", signal()));
  }
});
test("continuation requires a live server-authorized READY record, explicit POST and exact returned attempt/expiry", async t => {
  let calls = 0;
  t.mock.method(globalThis, "fetch", async (url, options) => { calls++; assert.ok(url.endsWith(`/children/${learner}/fee-payments/${attempt}/continue`)); assert.equal(options.method, "POST"); assert.equal(options.credentials, "include"); assert.equal(options.cache, "no-store"); assert.equal(options.headers["X-CSRF-TOKEN"], "synthetic-csrf"); return Response.json({ attemptId: attempt, checkoutUrl: "https://payments-test.cashfree.com/links/synthetic", expiresAt: "2030-10-01T01:15:00Z" }); });
  assert.equal(await gateway.continueFamilyPayment(ready(), "synthetic-csrf", signal()), "https://payments-test.cashfree.com/links/synthetic");
  for (const invalid of [payment(), { ...ready(), initiatedByYou: false }, { ...ready(), reservationActive: false }]) await assert.rejects(gateway.continueFamilyPayment(invalid, "synthetic-csrf", signal()));
  assert.equal(calls, 1);
});
test("stale, mismatched and forged continuation responses never become usable checkout URLs", async t => {
  for (const changes of [{ attemptId: receipt }, { expiresAt: "2020-01-01T00:00:00Z" }, { checkoutUrl: "https://evil.example.test/links/steal" }, { checkoutUrl: "https://payments.cashfree.com/links/x?private=1" }]) {
    t.mock.method(globalThis, "fetch", async () => Response.json({ attemptId: attempt, checkoutUrl: "https://payments.cashfree.com/links/synthetic", expiresAt: "2030-10-01T01:15:00Z", ...changes }));
    await assert.rejects(gateway.continueFamilyPayment(ready(), "synthetic-csrf", signal()));
  }
});
test("only controlled public copy is displayed for arbitrary transport and private-provider errors", () => {
  assert.equal(gateway.familyPaymentErrorCopy(new Error("synthetic-private-provider-payload")), gateway.familyPaymentErrors.unavailable);
  assert.equal(gateway.familyPaymentErrorCopy(new gateway.FamilyPaymentAccessError(401)), gateway.familyPaymentErrors.session);
  assert.equal(gateway.familyPaymentErrorCopy(new Error(gateway.familyPaymentErrors.uncertain)), gateway.familyPaymentErrors.uncertain);
});
