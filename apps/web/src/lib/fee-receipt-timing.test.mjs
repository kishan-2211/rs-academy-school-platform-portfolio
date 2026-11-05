import assert from "node:assert/strict";
import test from "node:test";
import { receivedMoneyLabel } from "./fee-receipt-timing.ts";

test("date-only receipt evidence never invents midnight or uses the device zone", () => {
  assert.equal(receivedMoneyLabel({ originalReceivedOn: "2026-10-01", actualReceivedAt: null,
    receivedTimePrecision: "DATE_ONLY", schoolTimeZone: null }, "en"), "2026-10-01");
});
test("actual received instant is formatted in its persisted school zone", () => {
  const timing = { originalReceivedOn: "2026-10-02", actualReceivedAt: "2026-10-01T20:00:00Z",
    receivedTimePrecision: "INSTANT", schoolTimeZone: "Asia/Kolkata" };
  for (const locale of ["en", "hi"]) {
    const expected = new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", {
      timeZone: "Asia/Kolkata", dateStyle: "medium", timeStyle: "short",
    }).format(new Date(timing.actualReceivedAt));
    assert.equal(receivedMoneyLabel(timing, locale), expected + " (Asia/Kolkata)");
  }
});
test("unknown or invalid historical zone preserves the actual UTC instant", () => {
  for (const zone of [null, "Not/AZone"]) assert.equal(receivedMoneyLabel({ originalReceivedOn: "2026-10-02",
    actualReceivedAt: "2026-10-01T20:00:00Z", receivedTimePrecision: "INSTANT", schoolTimeZone: zone }, "en"),
  "2026-10-01T20:00:00.000Z");
});
