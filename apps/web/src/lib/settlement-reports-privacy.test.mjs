import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import ts from "typescript";
import { SettlementError, SettlementWriteError, settlementErrorCode } from "./gateway-settlements.ts";

// Execute the real component callbacks with synthetic state and deferred mocked
// reads. No browser session, provider, financial command or database is accessed.
const text = readFileSync(new URL("../components/workspace/settlement-reports-panel.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("settlement-reports-panel.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const callbacks = new Map();
function visit(node) {
  if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer &&
      ts.isCallExpression(node.initializer) && node.initializer.expression.getText(source) === "useCallback")
    callbacks.set(node.name.text, node.initializer.arguments[0].getText(source));
  if (ts.isFunctionDeclaration(node) && node.name) callbacks.set(node.name.text, node.getText(source));
  ts.forEachChild(node, visit);
}
visit(source);
const school = "89000000-0000-4000-8000-000000000001";
const requestId = "89000000-0000-4000-8000-000000000002";
const filter = { schoolId: school, status: "ALL", kind: "ALL", query: "synthetic-private-query", page: 0 };
const oldReport = { schoolId: school, request: { id: requestId }, marker: "synthetic-private-report" };
const freshQueue = { schoolId: school, marker: "synthetic-fresh-queue" };
const freshReport = { schoolId: school, request: { id: requestId }, marker: "synthetic-fresh-report" };
const saved = Object.freeze({ schoolId: school, clientRequestId: "89000000-0000-4000-8000-000000000003", kind: "EVENTS", body: '{"synthetic":"immutable original command"}' });
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function setup() {
  const state = { queue: { schoolId: school, marker: "synthetic-private-queue" }, report: oldReport, selectedId: requestId,
    reportQuery: "synthetic-private-query", filter: { ...filter }, error: null, accessError: null, notice: true, busy: false, locked: false };
  const calls = { queue: [], report: [], writes: [], guards: [] };
  const mocks = { queue: async () => freshQueue, report: async () => freshReport, write: async () => ({ id: requestId }) };
  const refs = {
    preparationReading: { current: false }, bankReading: { current: false }, sourceReading: { current: false },
    read: { current: null }, writing: { current: false }, attempt: { current: null }, unknown: { current: false },
    authorizationLost: { current: false }, authorizationVersion: { current: 0 }, lifecycle: { current: 1 },
    detail: { current: { id: requestId, query: "synthetic-private-query", page: 0 } }, applied: { current: { ...filter } },
  };
  const bindings = { ...refs, schoolId: school, busy: false, locked: false, AbortController, SettlementWriteError, settlementErrorCode,
    loadSettlementQueue: (...args) => { calls.queue.push(args); return mocks.queue(...args); },
    loadSettlementReport: (...args) => { calls.report.push(args); return mocks.report(...args); },
    sendSettlementSync: (...args) => { calls.writes.push(args); return mocks.write(...args); },
    familyPaymentCsrf: async () => "synthetic-csrf",
    onGuardChange: value => calls.guards.push(value),
  };
  for (const name of ["queue", "report", "selectedId", "reportQuery", "filter", "error", "accessError", "notice", "busy", "locked", "sourceBusy", "bankBusy", "preparationBusy"])
    bindings["set" + name[0].toUpperCase() + name.slice(1)] = value => { state[name] = typeof value === "function" ? value(state[name]) : value; };
  function compile(name) {
    assert.ok(callbacks.has(name), name);
    const compiled = ts.transpileModule("const handler = " + callbacks.get(name) + ";", { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
    return new Function(...Object.keys(bindings), compiled + "\nreturn handler;")(...Object.values(bindings));
  }
  bindings.loseAccess = compile("loseAccess");
  bindings.loadQueue = compile("loadQueue");
  bindings.loadReport = compile("loadReport");
  return { state, calls, mocks, refs, loseAccess: bindings.loseAccess, loadQueue: bindings.loadQueue, loadReport: bindings.loadReport,
    dispatch: compile("dispatch"), refresh: compile("refresh"), guard: name => compile(name) };
}
function assertPurged(fixture, code) {
  assert.equal(fixture.state.queue, null); assert.equal(fixture.state.report, null);
  assert.equal(fixture.state.selectedId, null); assert.equal(fixture.refs.detail.current, null);
  assert.equal(fixture.state.reportQuery, ""); assert.equal(fixture.state.filter.query, "");
  assert.equal(fixture.refs.applied.current.query, ""); assert.equal(fixture.state.notice, false);
  assert.equal(fixture.state.accessError, code); assert.equal(fixture.refs.authorizationLost.current, true);
}
for (const code of ["session", "scope"]) {
  test(code + " queue denial purges prior private selection and report children", async () => {
    const f = setup(); f.mocks.queue = async () => { throw new SettlementError(code); };
    assert.equal(await f.loadQueue(filter), false); assertPurged(f, code);
    assert.equal(f.state.error, code); assert.equal(f.state.busy, false);
    assert.equal(f.calls.report.length, 0); assert.equal(f.calls.writes.length, 0);
  });
  test(code + " report denial also purges the queue and requires fresh queue authority", async () => {
    const f = setup(); f.mocks.report = async () => { throw new SettlementError(code); };
    assert.equal(await f.loadReport({ id: requestId, query: "", page: 0 }), false); assertPurged(f, code);
    const count = f.calls.report.length;
    assert.equal(await f.loadReport({ id: requestId, query: "", page: 0 }), false);
    assert.equal(f.calls.report.length, count);
  });
}
test("clearing a transient error during retry cannot clear the authorization latch or resurrect an old report", async () => {
  const f = setup(); f.loseAccess("session");
  const next = deferred(); f.mocks.queue = () => next.promise;
  const reading = f.refresh();
  assert.equal(f.state.error, null); assert.equal(f.state.accessError, "session");
  assert.equal(f.state.report, null); assert.equal(f.refs.authorizationLost.current, true);
  next.resolve(freshQueue); await reading;
  assert.equal(f.state.queue, freshQueue); assert.equal(f.state.accessError, null);
  assert.equal(f.refs.authorizationLost.current, false); assert.equal(f.state.report, null);
  assert.equal(f.state.selectedId, null); assert.equal(f.calls.report.length, 0);
});
test("failed retry retains the denial latch and new report reads remain blocked", async () => {
  const f = setup(); f.loseAccess("scope"); f.mocks.queue = async () => { throw new SettlementError("unavailable"); };
  await f.loadQueue(filter);
  assert.equal(f.state.error, "unavailable"); assert.equal(f.state.accessError, "scope");
  await f.loadReport({ id: requestId, query: "", page: 0 });
  assert.equal(f.calls.report.length, 0); assert.equal(f.state.report, null);
});
for (const name of ["queue", "report"]) {
  test("late pre-denial " + name + " response cannot restore private state or clear the latch", async () => {
    const f = setup(), next = deferred(); f.mocks[name] = () => next.promise;
    const reading = name === "queue" ? f.loadQueue(filter) : f.loadReport({ id: requestId, query: "", page: 0 });
    f.loseAccess("scope"); next.resolve(name === "queue" ? freshQueue : freshReport);
    assert.equal(await reading, false); assertPurged(f, "scope");
  });
}
test("fresh authorized queue followed by explicit report inspection restores only newly read data", async () => {
  const f = setup(); f.loseAccess("session");
  assert.equal(await f.loadQueue({ ...filter, query: "" }), true);
  assert.equal(f.state.report, null);
  assert.equal(await f.loadReport({ id: requestId, query: "", page: 0 }), true);
  assert.equal(f.state.report, freshReport); assert.equal(f.state.accessError, null);
});
for (const owner of ["sourceReading", "bankReading", "preparationReading"]) {
  test(owner + " ownership blocks parent reads/commands before any child can be unmounted", async () => {
    const f = setup();
    f.refs[owner].current = true;
    assert.equal(await f.loadQueue(filter), false);
    assert.equal(await f.loadReport({ id: requestId, query: "", page: 0 }), false);
    await f.dispatch(saved);
    assert.equal(f.calls.queue.length + f.calls.report.length + f.calls.writes.length, 0);
    assert.equal(f.state.report, oldReport); assert.equal(f.refs[owner].current, true);
  });
}
test("unknown command retains its exact identity across authorization loss and successful same-command recovery", async () => {
  const f = setup(); f.mocks.write = async () => { throw new SettlementWriteError("unknown", "unknown"); };
  await f.dispatch(saved);
  assert.equal(f.refs.attempt.current, saved); assert.equal(f.state.locked, true);
  await f.loadQueue(filter); assert.equal(f.calls.queue.length, 0);
  f.mocks.write = async () => { throw new SettlementWriteError("rejected", "session"); };
  await f.dispatch(saved); assertPurged(f, "session");
  assert.equal(f.refs.attempt.current, saved); assert.equal(f.refs.unknown.current, true); assert.equal(f.state.locked, true);
  f.guard("bankGuardChange")(false); assert.equal(f.calls.guards.at(-1), true);
  await f.dispatch(Object.freeze({ ...saved })); assert.equal(f.calls.writes.length, 2);
  f.mocks.write = async () => ({ id: requestId });
  await f.dispatch(saved);
  assert.equal(f.calls.writes.length, 3);
  for (const [attempt] of f.calls.writes) assert.equal(attempt, saved);
  assert.equal(f.refs.attempt.current, null); assert.equal(f.refs.unknown.current, false); assert.equal(f.state.locked, false);
  assert.equal(f.state.accessError, null); assert.equal(f.state.report, freshReport);
});
test("a new command is refused after access loss while a definite auth rejection preserves existing rejection semantics", async () => {
  const f = setup(); f.mocks.write = async () => { throw new SettlementWriteError("rejected", "scope"); };
  await f.dispatch(saved); assertPurged(f, "scope");
  assert.equal(f.refs.attempt.current, null); assert.equal(f.state.locked, false);
  await f.dispatch(saved); assert.equal(f.calls.writes.length, 1);
});
test("aborted or stale-school authorization failures cannot mutate a replacement context", async () => {
  for (const abort of [true, false]) {
    const f = setup(), next = deferred(); f.mocks.queue = () => next.promise;
    const reading = f.loadQueue(filter);
    if (abort) f.refs.read.current.abort(); else f.refs.lifecycle.current++;
    next.reject(new SettlementError("session")); await reading;
    assert.equal(f.state.accessError, null); assert.equal(f.state.report, oldReport);
    assert.equal(f.refs.authorizationLost.current, false);
  }
});
