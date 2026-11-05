import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { filterInbox, inboxCopy, loadStaffInbox, recordStaffInboxAction } from "./communication-inbox.ts";

const notice = {
  recipientId: "synthetic-recipient", announcementId: "synthetic-notice", learnerId: null,
  category: "GENERAL", priority: "NORMAL", audienceKind: "ALL_STAFF", languageMode: "BILINGUAL",
  preferredLanguage: "hi", titleEn: "Staff meeting", titleHi: "शिक्षक बैठक", bodyEn: "Review the schedule.",
  bodyHi: "समय सारणी देखें।", requiresAcknowledgement: true, scheduledFor: "2030-01-01T00:00:00Z",
  expiresAt: null, expired: false, readAt: null, acknowledgedAt: null, deliveries: [], version: 0,
};

test("inbox language uses the published complete translation with safe fallback", () => {
  assert.equal(inboxCopy(notice, "preferred").language, "hi");
  assert.equal(inboxCopy(notice, "en").title, "Staff meeting");
  assert.equal(inboxCopy({ ...notice, bodyHi: null }, "hi").language, "en");
  assert.equal(inboxCopy({ ...notice, titleEn: null, bodyEn: null }, "en").language, "hi");
});

test("filters distinguish unread, outstanding acknowledgement, and past notices without mutating records", () => {
  const items = [notice, { ...notice, recipientId: "read", readAt: "2030-01-02T00:00:00Z" },
    { ...notice, recipientId: "past", expired: true },
    { ...notice, recipientId: "acknowledged", readAt: "2030-01-02T00:00:00Z", acknowledgedAt: "2030-01-02T00:00:00Z" }];
  assert.deepEqual(filterInbox(items, "unread", "").map((item) => item.recipientId), ["synthetic-recipient"]);
  assert.equal(filterInbox(items, "acknowledgement", "").length, 2);
  assert.equal(filterInbox(items, "expired", "")[0].recipientId, "past");
  assert.equal(filterInbox(items, "all", "  STAFF  ").length, 4);
  assert.equal(filterInbox(items, "all", "बैठक").length, 4);
  assert.equal(filterInbox(items, "all", "not in these notices").length, 0);
  assert.equal(notice.readAt, null);
});

test("staff inbox reads use authenticated no-store requests and carry cancellation", async (t) => {
  const controller = new AbortController();
  t.mock.method(globalThis, "fetch", async (url, init) => {
    assert.match(url, /\/api\/v1\/communications\/inbox$/);
    assert.equal(init.credentials, "include"); assert.equal(init.cache, "no-store");
    assert.equal(init.signal, controller.signal);
    return Response.json({ schoolId: "synthetic-school", learnerId: null, items: [notice] });
  });
  assert.equal((await loadStaffInbox(controller.signal)).items[0].recipientId, notice.recipientId);
});

test("read and acknowledgement use school scope and CSRF without publishing extra data", async (t) => {
  const original = Object.getOwnPropertyDescriptor(globalThis, "document");
  Object.defineProperty(globalThis, "document", { configurable: true, value: { cookie: "educore_csrf=synthetic%2Dcsrf" } });
  t.after(() => { if (original) Object.defineProperty(globalThis, "document", original); else delete globalThis.document; });
  const actions = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    actions.push(url.split("/").at(-1));
    assert.equal(init.method, "POST"); assert.equal(init.credentials, "include");
    assert.equal(init.headers["X-CSRF-TOKEN"], "synthetic-csrf");
    assert.deepEqual(JSON.parse(init.body), { schoolId: "synthetic-school" });
    return Response.json({ id: notice.recipientId, rowVersion: 1 });
  });
  await recordStaffInboxAction("synthetic-school", notice.recipientId, "read");
  await recordStaffInboxAction("synthetic-school", notice.recipientId, "acknowledge");
  assert.deepEqual(actions, ["read", "acknowledge"]);
});

test("missing CSRF or expired session cannot submit an inbox action", async (t) => {
  const original = Object.getOwnPropertyDescriptor(globalThis, "document");
  Object.defineProperty(globalThis, "document", { configurable: true, value: { cookie: "" } });
  t.after(() => { if (original) Object.defineProperty(globalThis, "document", original); else delete globalThis.document; });
  let calls = 0;
  t.mock.method(globalThis, "fetch", async (url, init) => {
    calls++; assert.match(url, /\/auth\/sessions\/current$/); assert.notEqual(init.method, "POST");
    return new Response(null, { status: 200 });
  });
  await assert.rejects(recordStaffInboxAction("synthetic-school", notice.recipientId, "read"), /secure form token/);
  assert.equal(calls, 1);
  t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 401 }));
  await assert.rejects(recordStaffInboxAction("synthetic-school", notice.recipientId, "read"), /session has ended/);
});

test("denied inbox reads surface the boundary instead of inventing empty data", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 403 }));
  await assert.rejects(loadStaffInbox(), /cannot access this inbox/);
});

test("staff inbox has labelled controls, status, focus and bounded mobile rendering", () => {
  const component = readFileSync(new URL("../components/workspace/staff-inbox.tsx", import.meta.url), "utf8");
  const styles = readFileSync(new URL("../components/workspace/staff-inbox.module.css", import.meta.url), "utf8");
  assert.match(component, /aria-label="Find school notices"/);
  assert.match(component, /aria-label="Inbox pages"/);
  assert.match(component, /role="status"/); assert.match(component, /role="alert"/);
  assert.match(component, /resultsHeading\.current\?\.focus/);
  assert.match(component, /const pageSize = 10/);
  assert.match(component, /controller\.signal\.aborted/);
  assert.match(styles, /:focus-visible/); assert.match(styles, /min-height: 44px/);
  assert.match(styles, /@media \(max-width: 640px\)/);
  assert.doesNotMatch(styles, /animation:|transition:|perspective:/);
  assert.doesNotMatch(component, /dangerouslySetInnerHTML|localStorage|sessionStorage/);
});
