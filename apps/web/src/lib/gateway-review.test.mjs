import assert from "node:assert/strict";
import test from "node:test";
import { eligibleReviewDate, gatewayConfigurationLabel, manualReceiptCanReverse, gatewayReviewQuery } from "./gateway-review.ts";

test("review dates preserve the original gateway day and require an eligible open date", () => {
  assert.equal(eligibleReviewDate("2026-10-09", "2026-10-09", "2026-10-10"), true);
  assert.equal(eligibleReviewDate("2026-10-10", "2026-10-09", "2026-10-10", "2026-10-09"), true);
  for (const value of ["2026-10-08", "2026-10-09", "2026-10-11", "2026-02-30", "not-a-date", ""]) {
    assert.equal(eligibleReviewDate(value, "2026-10-09", "2026-10-10", "2026-10-09"), false);
  }
});

test("online receipts never offer the manual reversal shortcut; existing manual methods are unchanged", () => {
  assert.equal(manualReceiptCanReverse({ status: "POSTED", paymentMethod: "ONLINE_PROVIDER" }), false);
  for (const method of ["CASH", "CHEQUE", "BANK_TRANSFER", "SCHOOL_UPI"]) {
    assert.equal(manualReceiptCanReverse({ status: "POSTED", paymentMethod: method }), true);
    assert.equal(manualReceiptCanReverse({ status: "REVERSED", paymentMethod: method }), false);
  }
});

test("provider configuration alone never claims that online checkout is operational", () => {
  assert.equal(gatewayConfigurationLabel("NOT_CONFIGURED"), "Not configured");
  assert.equal(gatewayConfigurationLabel("TEST_REDIRECT"), "Test configuration only");
  assert.equal(gatewayConfigurationLabel("LIVE_REDIRECT"), "Live configuration only");
  assert.equal(gatewayConfigurationLabel("UNEXPECTED"), "Unavailable");
});

test("queue queries retain server school scope, encode references and bound search input", () => {
  const query = gatewayReviewQuery("synthetic-school", "  payment&ref=other  ", "HOLD", 2);
  assert.equal(query.get("schoolId"), "synthetic-school");
  assert.equal(query.get("q"), "payment&ref=other");
  assert.equal(query.get("page"), "2");
  assert.equal(query.get("pageSize"), "25");
  assert.equal(gatewayReviewQuery("synthetic-school", "a".repeat(500), "ALL", 0).get("q").length, 100);
});
