import assert from "node:assert/strict";
import test from "node:test";
import { feeTenderAccounts } from "./fee-tender-accounts.ts";

const account = (id, category, overrides = {}) => ({ id, category, accountType: "ASSET", status: "ACTIVE", allowDirectPosting: true, ...overrides });
const accounts = [account("cash", "CASH"), account("bank", "BANK"), account("clearing", "OTHER"),
  account("inactive", "OTHER", { status: "INACTIVE" }), account("parent", "OTHER", { allowDirectPosting: false }),
  account("expense", "OTHER", { accountType: "EXPENSE" })];

test("online tender permits a separate clearing asset and preserves legacy bank mapping", () => {
  assert.deepEqual(feeTenderAccounts(accounts, "ONLINE_PROVIDER").map((item) => item.id), ["bank", "clearing"]);
});
test("manual cash and bank tenders keep their own required posting categories", () => {
  assert.deepEqual(feeTenderAccounts(accounts, "CASH").map((item) => item.id), ["cash"]);
  for (const method of ["BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI"]) {
    assert.deepEqual(feeTenderAccounts(accounts, method).map((item) => item.id), ["bank"]);
  }
});
test("inactive, non-posting, non-asset and unknown tender options are excluded without mutating input", () => {
  const before = structuredClone(accounts);
  assert.deepEqual(feeTenderAccounts(accounts, "UNKNOWN"), []);
  assert.deepEqual(accounts, before);
  assert.deepEqual(feeTenderAccounts([], "ONLINE_PROVIDER"), []);
});
