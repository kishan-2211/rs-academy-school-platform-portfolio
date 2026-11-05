import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { canChangeTransportPlan, nonTravelPath, nonTravelPolicyPath, readFamilyTransportDay, readTransportChange,
  readTransportPolicies, readTransportPolicy, transportChangeBody, transportPlanApi, transportPlanAttempt,
  TransportPlanProblem } from "./transport-non-travel.ts";
import { transportPlanMessages } from "./i18n/transport-plan-messages.ts";

const id = (n) => `10000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const child = id(1), school = id(2), campus = id(3);
const day = () => ({ learnerId: child, serviceDate: "2026-10-03", timeZone: "Asia/Kolkata", enabled: true,
  observedAt: "2026-10-03T01:00:00Z", journeys: [
    { journeyKind: "PICKUP", assignmentId: id(4), serviceLabel: "Synthetic morning", stopLabel: "Synthetic stop",
      scheduledTime: "08:00:00", changesCloseAt: "2026-10-03T02:00:00Z", changesAllowed: true, notTravelling: false, assignmentChanged: false },
    { journeyKind: "DROP", assignmentId: id(5), serviceLabel: "Synthetic afternoon", stopLabel: "Synthetic stop",
      scheduledTime: "15:00:00", changesCloseAt: "2026-10-03T09:00:00Z", changesAllowed: true, notTravelling: false, assignmentChanged: false },
  ], history: [] });
const policy = () => ({ id: id(6), campusId: campus, campusName: "Synthetic campus", timeZone: "Asia/Kolkata",
  version: 0, enabled: true, pickupClosesAt: "07:30:00", dropClosesAt: "14:00:00" });
const change = () => ({ id: id(7), serviceDate: "2026-10-03", journeys: "FULL_DAY", notTravelling: true, occurredAt: "2026-10-03T01:15:00Z" });
const response = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
function documentCookie(t, initial = "educore_csrf=synthetic%20csrf") {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, "document");
  const fake = { cookie: initial };
  Object.defineProperty(globalThis, "document", { configurable: true, value: fake });
  t.after(() => { if (descriptor) Object.defineProperty(globalThis, "document", descriptor); else delete globalThis.document; });
  return fake;
}
const problem = (code, uncertain = false) => (error) => error instanceof TransportPlanProblem && error.code === code && error.uncertain === uncertain;

test("transport paths reject foreign path syntax and only interpolate validated UUIDs", () => {
  assert.equal(nonTravelPath(child), `/api/v1/family/children/${child}/transport/non-travel`);
  assert.equal(nonTravelPolicyPath(school), `/api/v1/transport/non-travel/policies?schoolId=${school}`);
  for (const value of ["../other", "learner-name", "", `${child}?schoolId=${school}`]) {
    assert.throws(() => nonTravelPath(value), problem("invalid"));
    assert.throws(() => nonTravelPolicyPath(value), problem("invalid"));
  }
});

test("family transport projects only approved fields and binds the returned learner", () => {
  const value = day(); value.privateDriverContact = "DO_NOT_PROJECT";
  assert.deepEqual(JSON.parse(JSON.stringify(readFamilyTransportDay(value, child))), day());
  assert.throws(() => readFamilyTransportDay(value, id(8)), problem("response"));
  assert.deepEqual(readFamilyTransportDay({ ...day(), journeys: [], enabled: false }, child).journeys, []);
});

test("malformed day, deadline, timezone, status and duplicate journey responses fail closed", () => {
  const invalid = [null, {}, { ...day(), serviceDate: "2026-02-30" }, { ...day(), timeZone: "invalid-zone" },
    { ...day(), observedAt: "not-a-date" }, { ...day(), enabled: "true" }, { ...day(), journeys: [day().journeys[0], day().journeys[0]] },
    { ...day(), journeys: [{ ...day().journeys[0], changesCloseAt: null }] },
    { ...day(), journeys: [{ ...day().journeys[0], changesCloseAt: day().observedAt }] },
    { ...day(), journeys: [{ ...day().journeys[0], scheduledTime: "25:00" }] },
    { ...day(), journeys: [{ ...day().journeys[0], assignmentChanged: true }] },
    { ...day(), journeys: [{ ...day().journeys[0], assignmentChanged: true, revisionId: id(9), notTravelling: true }] }];
  for (const value of invalid) assert.throws(() => readFamilyTransportDay(value, child), problem("response"));
});

test("history is bounded, validated and cannot include future or duplicate records", () => {
  const value = { ...day(), history: [change()] };
  assert.equal(readFamilyTransportDay(value, child).history.length, 1);
  for (const history of [[change(), change()], Array.from({ length: 31 }, (_, n) => ({ ...change(), id: id(n + 20) })),
    [{ ...change(), serviceDate: "2026-10-04" }], [{ ...change(), serviceDate: "2026-09-01" }]]) {
    assert.throws(() => readFamilyTransportDay({ ...day(), history }, child), problem("response"));
  }
  assert.throws(() => readTransportChange({ ...change(), notTravelling: undefined }), problem("response"));
});

test("policy validation distinguishes unconfigured disabled policies and scoped current revisions", () => {
  const unset = { campusId: campus, campusName: "Synthetic campus", timeZone: "Asia/Kolkata", version: -1, enabled: false };
  assert.equal(readTransportPolicy(unset).version, -1);
  assert.equal(readTransportPolicies({ schoolId: school, schoolName: "Synthetic school", canConfigure: true, policies: [policy()] }, school).policies.length, 1);
  for (const value of [{ ...unset, enabled: true }, { ...unset, id: id(6) }, { ...policy(), id: null },
    { ...policy(), version: -2 }, { ...policy(), pickupClosesAt: null }]) assert.throws(() => readTransportPolicy(value), problem("response"));
  for (const value of [{ schoolId: id(8), schoolName: "Synthetic school", canConfigure: true, policies: [] },
    { schoolId: school, schoolName: "Synthetic school", canConfigure: false, policies: [] },
    { schoolId: school, schoolName: "Synthetic school", canConfigure: true, policies: [policy(), policy()] }]) {
    assert.throws(() => readTransportPolicies(value, school), problem("response"));
  }
});

test("full-day changes require both open journeys and cancellation requires both current plans", () => {
  const now = Date.parse(day().observedAt);
  assert.equal(canChangeTransportPlan(day(), "FULL_DAY", true, now), true);
  assert.equal(canChangeTransportPlan(day(), "FULL_DAY", false, now), false);
  assert.equal(canChangeTransportPlan({ ...day(), journeys: [{ ...day().journeys[0], notTravelling: true }] }, "PICKUP", false, now), true);
  for (const value of [{ ...day(), enabled: false }, { ...day(), journeys: [day().journeys[0]] },
    { ...day(), journeys: [{ ...day().journeys[0], changesAllowed: false }, day().journeys[1]] }]) {
    assert.equal(canChangeTransportPlan(value, "FULL_DAY", true, now), false);
  }
  assert.equal(canChangeTransportPlan(day(), "PICKUP", true, Date.parse(day().journeys[0].changesCloseAt)), false);
  assert.equal(canChangeTransportPlan(day(), "PICKUP", true, NaN), false);
});

test("request bodies bind selected assignment and previous revision without carrying unselected journeys", () => {
  const value = day(); value.journeys[0].revisionId = id(10);
  assert.deepEqual(transportChangeBody(value, "PICKUP", true), { serviceDate: value.serviceDate, journeys: "PICKUP", notTravelling: true,
    pickupAssignmentId: id(4), dropAssignmentId: null, expectedPickupRevisionId: id(10), expectedDropRevisionId: null });
  assert.equal(transportChangeBody(value, "FULL_DAY", true).dropAssignmentId, id(5));
  assert.throws(() => transportChangeBody({ ...value, journeys: [value.journeys[0]] }, "FULL_DAY", true), problem("invalid"));
});

test("uncertain retries preserve exactly the same key and body; child or content changes create a new key", () => {
  let n = 100;
  const makeKey = () => id(n++), body = transportChangeBody(day(), "PICKUP", true);
  const attempt = transportPlanAttempt(null, child, body, makeKey);
  assert.equal(transportPlanAttempt(attempt, child, { ...body }, makeKey), attempt);
  assert.notEqual(transportPlanAttempt(attempt, id(8), body, makeKey).clientRequestId, attempt.clientRequestId);
  assert.notEqual(transportPlanAttempt(attempt, child, { ...body, notTravelling: false }, makeKey).clientRequestId, attempt.clientRequestId);
  body.pickupAssignmentId = id(11);
  assert.equal(attempt.body.pickupAssignmentId, id(4));
});

test("API reads use HttpOnly credentials, no-store and supplied abort signal", async (t) => {
  const controller = new AbortController();
  const mock = t.mock.method(globalThis, "fetch", async () => response(day()));
  assert.deepEqual(await transportPlanApi(nonTravelPath(child), { signal: controller.signal }), day());
  const [url, options] = mock.mock.calls[0].arguments;
  assert.ok(url.endsWith(nonTravelPath(child)));
  assert.equal(options.credentials, "include"); assert.equal(options.cache, "no-store"); assert.equal(options.signal, controller.signal);
  assert.equal(options.method, "GET"); assert.equal(options.body, undefined);
});

test("API whitelist prevents unrelated endpoint writes before network execution", async (t) => {
  const mock = t.mock.method(globalThis, "fetch", async () => response({}));
  for (const [path, method] of [["/api/v1/other", "GET"], [nonTravelPath(child), "PUT"], [nonTravelPolicyPath(school), "POST"],
    ["/api/v1/family/children", "POST"], [`${nonTravelPath(child)}?extra=true`, "GET"]]) {
    await assert.rejects(transportPlanApi(path, { method, signal: new AbortController().signal }), problem("invalid"));
  }
  assert.equal(mock.mock.callCount(), 0);
});

test("writes bootstrap and validate CSRF without storing credentials", async (t) => {
  const fake = documentCookie(t, ""); const controller = new AbortController();
  const mock = t.mock.method(globalThis, "fetch", async (url) => {
    if (url.endsWith("/auth/sessions/current")) { fake.cookie = "other=value; educore_csrf=synthetic%20csrf"; return response({}); }
    return response(change());
  });
  const body = { ...transportChangeBody(day(), "FULL_DAY", true), clientRequestId: id(100) };
  await transportPlanApi(nonTravelPath(child), { method: "POST", body, signal: controller.signal });
  assert.equal(mock.mock.callCount(), 2);
  for (const call of mock.mock.calls) { assert.equal(call.arguments[1].signal, controller.signal); assert.equal(call.arguments[1].cache, "no-store"); }
  const options = mock.mock.calls[1].arguments[1];
  assert.equal(options.headers["X-CSRF-TOKEN"], "synthetic csrf"); assert.deepEqual(JSON.parse(options.body), body);
});

test("failed bootstrap or malformed CSRF never submits the planned write", async (t) => {
  const fake = documentCookie(t, "");
  const mock = t.mock.method(globalThis, "fetch", async () => response({ detail: "PRIVATE_DETAIL" }, 401));
  await assert.rejects(transportPlanApi(nonTravelPath(child), { method: "POST", signal: new AbortController().signal }), problem("session"));
  assert.equal(mock.mock.callCount(), 1);
  fake.cookie = "educore_csrf=%invalid";
  await assert.rejects(transportPlanApi(nonTravelPath(child), { method: "POST", signal: new AbortController().signal }), problem("csrf"));
  assert.equal(mock.mock.callCount(), 1);
});

test("HTTP failures expose reviewed codes only and distinguish uncertain outcomes", async (t) => {
  documentCookie(t); let result;
  t.mock.method(globalThis, "fetch", async () => result);
  for (const [status, apiCode, expected, uncertain] of [[401, "", "session", false], [403, "", "access", false],
    [404, "", "access", false], [409, "TRANSPORT_NON_TRAVEL_CLOSED", "closed", false], [409, "", "changed", false],
    [400, "TRANSPORT_NON_TRAVEL_DISABLED", "disabled", false], [400, "", "invalid", false], [500, "", "unavailable", true]]) {
    result = response({ code: apiCode, detail: "PRIVATE_DETAIL" }, status);
    await assert.rejects(transportPlanApi(nonTravelPath(child), { method: "POST", signal: new AbortController().signal }), (error) => {
      assert.equal(error.message.includes("PRIVATE_DETAIL"), false); return problem(expected, uncertain)(error);
    });
  }
});

test("network and invalid JSON writes are uncertain while read failures remain retryable", async (t) => {
  documentCookie(t); let malformed = false;
  t.mock.method(globalThis, "fetch", async () => { if (malformed) return new Response("not-json", { status: 200 }); throw new Error("PRIVATE_NETWORK_DETAIL"); });
  await assert.rejects(transportPlanApi(nonTravelPath(child), { method: "POST", signal: new AbortController().signal }), problem("unavailable", true));
  await assert.rejects(transportPlanApi(nonTravelPath(child), { signal: new AbortController().signal }), problem("unavailable"));
  malformed = true;
  await assert.rejects(transportPlanApi(nonTravelPath(child), { method: "POST", signal: new AbortController().signal }), problem("response", true));
});

test("abort propagates without presenting a false save or generic retry error", async (t) => {
  documentCookie(t); const controller = new AbortController(); const failure = new DOMException("Aborted", "AbortError");
  t.mock.method(globalThis, "fetch", async () => { controller.abort(); throw failure; });
  await assert.rejects(transportPlanApi(nonTravelPath(child), { method: "POST", signal: controller.signal }), (error) => error === failure);
});

test("transport controls retain bilingual copy, native labels, scoped navigation and semantic theme styling", () => {
  const components = ["family-transport-plans.tsx", "transport-non-travel-policy-panel.tsx", "transport-plan-ui.tsx"]
    .map((file) => readFileSync(new URL(`../components/transport/${file}`, import.meta.url), "utf8"));
  for (const source of components) {
    for (const match of source.matchAll(/\bt\("([^"\n]+)"\)/g)) assert.ok(transportPlanMessages[match[1]], `Missing Hindi copy: ${match[1]}`);
    assert.doesNotMatch(source, /localStorage|sessionStorage|dangerouslySetInnerHTML/);
  }
  assert.match(components[0], /<fieldset[^>]*><legend>/); assert.match(components[0], /type="radio"/);
  assert.match(components[0], /key=\{learnerId\}/); assert.match(components[0], /performance\.now\(\)/);
  assert.match(components[1], /expectedVersion: policy\.version < 0 \? null : policy\.version/);
  assert.match(components[1], /minLength=\{3\} maxLength=\{500\}/);
  assert.match(components[2], /role="alert"/);
  const css = readFileSync(new URL("../components/transport/transport-plans.module.css", import.meta.url), "utf8");
  assert.match(css, /focus-visible/); assert.match(css, /min-height: 44px/); assert.match(css, /@media \(max-width: 600px\)/);
  assert.match(css, /\.policyPanel \.policyHeader h2[^\n]+color: var\(--theme-text\)/);
  assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b|animation:/i);
  const workspace = readFileSync(new URL("../components/workspace/transport-workspace.tsx", import.meta.url), "utf8");
  assert.match(workspace, /data\.canConfigure && <TransportNonTravelPolicyPanel/);
  assert.match(workspace, /<ParentTransportPlanStatus notTravelling=\{item\.parentNotTravelling\}/);
  assert.match(workspace, /recordEvent\(item, "ABSENT"\)/); // Still an explicit operator action, never automated by parent intent.
  const shell = readFileSync(new URL("../components/family/family-shell.tsx", import.meta.url), "utf8");
  assert.match(shell, /href="\/family\/transport"/);
});
