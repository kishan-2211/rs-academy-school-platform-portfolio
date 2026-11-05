import assert from "node:assert/strict";
import test from "node:test";
import { ptmLocalInstant, ptmWindow, ptmTime } from "./ptm.ts";

test("school wall-clock input resolves independently of browser timezone", () => {
  assert.equal(ptmLocalInstant("2030-04-10T10:00", "Asia/Kolkata"), "2030-04-10T04:30:00.000Z");
  assert.match(ptmTime("2030-04-10T04:30:00Z", "Asia/Kolkata"), /10:00/);
  assert.throws(() => ptmLocalInstant("2030-02-30T10:00", "Asia/Kolkata"));
});
test("clock-change gaps and repeated local times require an unambiguous time", () => {
  assert.throws(() => ptmLocalInstant("2026-03-08T02:30", "America/New_York"), /skipped or repeated/);
  assert.throws(() => ptmLocalInstant("2026-11-01T01:30", "America/New_York"), /skipped or repeated/);
});
test("family booking and correction windows close independently", () => {
  const event = { status: "PUBLISHED", bookingOpensAt: "2030-04-01T00:00Z", bookingClosesAt: "2030-04-09T00:00Z", changesCloseAt: "2030-04-10T03:00Z", slots: [{ id: "slot", startsAt: "2030-04-10T04:30Z" }], booking: null };
  assert.equal(ptmWindow(event, Date.parse("2030-04-08T00:00Z")).canBook, true);
  assert.equal(ptmWindow(event, Date.parse("2030-04-09T01:00Z")).canBook, false);
  event.booking = { status: "BOOKED", slotId: "slot" };
  assert.equal(ptmWindow(event, Date.parse("2030-04-09T01:00Z")).canChange, true);
  assert.equal(ptmWindow(event, Date.parse("2030-04-10T03:01Z")).canChange, false);
  event.status = "CANCELLED";
  assert.deepEqual(ptmWindow(event, Date.parse("2030-04-08T00:00Z")), { canBook: false, canChange: false });
});
