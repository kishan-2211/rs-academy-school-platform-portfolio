import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";
import { eligibleReviewDate } from "./gateway-review.ts";

const text = readFileSync(new URL("../components/workspace/gateway-review-panel.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("gateway-review-panel.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let post;
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name?.text === "post") post = node;
  ts.forEachChild(node, visit);
}
visit(source);
assert.ok(post);
const implementation = ts.transpileModule(`const handler = ${post.getText(source)};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const capture = { id: "synthetic-capture", originalBusinessDate: "2026-10-09", canReviewedPost: true };

function setup(overrides = {}) {
  const state = { requests: [], errors: [], notices: [], busy: [], refreshes: 0, accounts: 0, confirmations: [] };
  const lifecycle = { current: 1 };
  const bindings = {
    queue: { schoolToday: "2026-10-10", latestApprovedCloseDate: "2026-10-09" }, busyId: "",
    FormData: class { constructor(form) { this.values = form.values; } get(key) { return this.values[key]; } },
    eligibleReviewDate, schoolId: "synthetic-school", apiUrl: "https://api.example.invalid", encodeURIComponent,
    t: (key, values = {}) => key.replace(/\{(\w+)\}/g, (_, key) => String(values[key] ?? `{${key}}`)),
    money: () => "₹500", lifecycle, window: { confirm: (message) => { state.confirmations.push(message); return true; } },
    setError: (value) => state.errors.push(value), setNotice: (value) => state.notices.push(value),
    setBusyId: (value) => state.busy.push(value), csrf: async () => "synthetic-form-token",
    fetch: async (...args) => { state.requests.push(args); return { ok: true, json: async () => ({ status: "POSTED", receiptNumber: "SYN/00001" }) }; },
    responseProblem: async () => "Controlled server rejection", refresh: () => { state.refreshes++; },
    onPosted: async () => { state.accounts++; }, ...overrides,
  };
  const handler = new Function(...Object.keys(bindings), `${implementation}\nreturn handler;`)(...Object.values(bindings));
  const event = { preventDefault() {}, currentTarget: { values: { openDate: "2026-10-10", reason: "Synthetic accountant review" } } };
  return { state, lifecycle, event, invoke: (item = capture) => handler(event, item) };
}

test("review submits only scoped date and reason after financial confirmation, and refreshes the acknowledged receipt", async () => {
  const fixture = setup();
  const pending = fixture.invoke();
  fixture.event.currentTarget = null;
  await pending;
  assert.equal(fixture.state.requests.length, 1);
  const [url, request] = fixture.state.requests[0];
  assert.equal(url, "https://api.example.invalid/api/v1/finance/gateway/captures/synthetic-capture/posting");
  assert.deepEqual(JSON.parse(request.body), { schoolId: "synthetic-school", openDate: "2026-10-10", reason: "Synthetic accountant review" });
  assert.equal(request.credentials, "include");
  assert.equal(request.headers["X-CSRF-TOKEN"], "synthetic-form-token");
  assert.equal(fixture.state.refreshes, 1);
  assert.equal(fixture.state.accounts, 1);
  assert.match(fixture.state.notices.at(-1), /Receipt SYN\/00001/);
  assert.match(fixture.state.confirmations[0], /original gateway time and closed day will remain unchanged/);
});

test("cancelled confirmation, ineligible evidence, busy state and closed dates never submit", async () => {
  const cancelled = setup({ window: { confirm: () => false } });
  await cancelled.invoke();
  const ineligible = setup(); await ineligible.invoke({ ...capture, canReviewedPost: false });
  const busy = setup({ busyId: "synthetic-other-capture" }); await busy.invoke();
  const closed = setup(); closed.event.currentTarget.values.openDate = "2026-10-09"; await closed.invoke();
  for (const item of [cancelled, ineligible, busy, closed]) assert.equal(item.state.requests.length, 0);
  assert.match(closed.state.errors.at(-1), /eligible open date/);
});

test("network loss after dispatch is uncertain, not a claim of failed payment", async () => {
  const fixture = setup({ fetch: async () => { throw new TypeError("Private network diagnostics"); } });
  await fixture.invoke();
  assert.equal(fixture.state.errors.at(-1), "The posting response is uncertain. Refresh before retrying.");
  assert.equal(fixture.state.refreshes, 0);
  assert.equal(fixture.state.notices.filter(Boolean).length, 0);
  assert.equal(fixture.state.busy.at(-1), "");
});

test("failed account refresh preserves the already acknowledged receipt", async () => {
  const fixture = setup({ onPosted: async () => { throw new Error("Private refresh error"); } });
  await fixture.invoke();
  assert.match(fixture.state.notices.at(-1), /Receipt SYN\/00001/);
  assert.equal(fixture.state.errors.at(-1), "The payment was posted, but account details could not be refreshed.");
});

test("unmount while posting cannot update a different panel or trigger stale account refresh", async () => {
  let complete;
  let dispatched;
  const started = new Promise((resolve) => { dispatched = resolve; });
  const fixture = setup({ fetch: async () => new Promise((resolve) => { complete = resolve; dispatched(); }) });
  const pending = fixture.invoke();
  await started;
  fixture.lifecycle.current += 1;
  complete({ ok: true, json: async () => ({ status: "POSTED", receiptNumber: "SYN/00001" }) });
  await pending;
  assert.equal(fixture.state.refreshes, 0);
  assert.equal(fixture.state.accounts, 0);
  assert.equal(fixture.state.notices.filter(Boolean).length, 0);
});

test("shared review forms have labelled native controls, announced states, focus and mobile sizing", () => {
  const css = readFileSync(new URL("../app/workspace/fees/gateway-review.css", import.meta.url), "utf8");
  assert.match(text, /aria-labelledby="gateway-review-title"/);
  assert.match(text, /role="alert"/);
  assert.match(text, /role="status"/);
  assert.match(text, /aria-busy=\{loading\}/);
  assert.match(css, /focus-visible.*outline: 3px solid/);
  assert.match(css, /outline: 3px solid var\(--theme-text\)/);
  assert.match(css, /min-height: 44px/);
  assert.match(css, /max-width: 600px/);
});
