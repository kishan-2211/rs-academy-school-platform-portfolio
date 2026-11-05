import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const text = readFileSync(new URL("../components/workspace/people-workspace.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("people.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const functions = new Map();
let formNode;
let contentNode;
let actionsNode;
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) functions.set(node.name.text, node);
  if (ts.isJsxElement(node)) {
    const hasClass = (value) => node.openingElement.attributes.properties.some((attribute) => ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className" && attribute.initializer?.getText(source) === `"${value}"`);
    if (hasClass("people-ending-confirmation")) formNode = node;
    if (hasClass("people-content")) contentNode = node;
    if (hasClass("people-actions")) {
      let parent = node.parent;
      while (parent && !ts.isJsxExpression(parent)) parent = parent.parent;
      actionsNode = parent;
    }
  }
  ts.forEachChild(node, visit);
}
visit(source);
function compile(name, bindings) {
  assert.ok(functions.has(name), name);
  const code = ts.transpileModule(`const handler = ${functions.get(name).getText(source)};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(bindings), `${code}\nreturn handler;`)(...Object.values(bindings));
}
const staff = { id: "synthetic-staff", displayName: "Synthetic Staff", employeeCode: "SYN-END-08", rowVersion: 4, joinedOn: "2026-08-01", status: "ACTIVE", userAccountId: null };
const assignment = { id: "synthetic-assignment", staffProfileId: staff.id, designationId: "synthetic-designation", rowVersion: 3, startsOn: "2026-09-01", status: "ACTIVE", primaryAssignment: true };
const data = { schoolId: "synthetic-school", canManage: true, staff: [staff], assignments: [assignment], designations: [{ id: assignment.designationId, name: "Synthetic Office" }] };
const ending = { kind: "staff", id: staff.id, staffId: staff.id, schoolId: data.schoolId, expectedVersion: staff.rowVersion, startsOn: staff.joinedOn, endDate: "2026-10-08", status: "ACTIVE", displayName: staff.displayName, employeeCode: staff.employeeCode };
function setup(overrides = {}) {
  const state = { writes: [], endings: [], errors: [], focus: [], frames: [] };
  const bindings = {
    data, selectedId: staff.id, busy: false, ending, endingInFlight: { current: false }, review: null, reviewInFlight: { current: false },
    endingTrigger: { current: null }, endingReason: { current: { focus: () => state.focus.push("reason") } },
    noticeTarget: { current: { focus: () => state.focus.push("saved") } },
    document: { activeElement: null }, HTMLElement: class {},
    setError: (value) => state.errors.push(value), setNotice: () => {}, setEnding: (value) => state.endings.push(value),
    requestAnimationFrame: (action) => state.frames.push(action),
    FormData: class { constructor(form) { this.form = form; } get(name) { return this.form.values[name] ?? null; } },
    mutate: async (...args) => { state.writes.push(args); return true; }, ...overrides,
  };
  return { state, bindings };
}
const event = (overrides = {}) => ({ preventDefault() {}, currentTarget: { values: { reason: "  Synthetic approved end reason  ", endDate: "2026-10-08", handoffConfirmed: "on", ...overrides } } });

for (const kind of ["staff", "assignment"]) {
  test(`${kind} ending button only prepares an exact record review, never writes`, async () => {
    const { state, bindings } = setup({ ending: null });
    const item = kind === "staff" ? staff : assignment;
    await compile("endRecord", bindings)(kind, item.id, item.rowVersion, "2026-10-08");
    assert.equal(state.writes.length, 0);
    assert.equal(state.endings.length, 1);
    assert.equal(state.endings[0].schoolId, data.schoolId);
    assert.equal(state.endings[0].id, item.id);
    assert.equal(state.endings[0].staffId, staff.id);
    assert.equal(state.endings[0].expectedVersion, item.rowVersion);
    assert.equal(state.endings[0].startsOn, kind === "staff" ? staff.joinedOn : assignment.startsOn);
    assert.equal(state.endings[0].reason, undefined);
  });
}
for (const overrides of [{ busy: true }, { data: { ...data, canManage: false } }, { ending }, { endingInFlight: { current: true } }, { review: {} }, { reviewInFlight: { current: true } }]) {
  test(`cannot prepare an ending while ${Object.keys(overrides)[0]} prevents review`, async () => {
    const { state, bindings } = setup({ ending: null, ...overrides });
    await compile("endRecord", bindings)("staff", staff.id, staff.rowVersion, "2026-10-08");
    assert.equal(state.writes.length, 0); assert.equal(state.endings.length, 0);
  });
}
test("unknown or already changed records cannot prepare an ending", async () => {
  for (const [id, version] of [["other-staff", 4], [staff.id, 2]]) {
    const { state, bindings } = setup({ ending: null });
    await compile("endRecord", bindings)("staff", id, version, "2026-10-08");
    assert.equal(state.writes.length, 0); assert.equal(state.endings.length, 0); assert.ok(state.errors.length);
  }
});
test("planned future assignment review defaults no earlier than its start", () => {
  const future = { ...assignment, startsOn: "2026-11-01", status: "PLANNED" };
  const { state, bindings } = setup({ ending: null, data: { ...data, assignments: [future] } });
  compile("endRecord", bindings)("assignment", future.id, future.rowVersion, "2026-10-08");
  assert.equal(state.endings[0].endDate, future.startsOn);
  assert.equal(state.endings[0].status, "PLANNED");
});
test("inactive employment keeps the existing formal offboarding workflow", async () => {
  const inactive = { ...staff, status: "INACTIVE" };
  const { state, bindings } = setup({ ending: null, data: { ...data, staff: [inactive] } });
  compile("endRecord", bindings)("staff", staff.id, staff.rowVersion, "2026-10-08");
  assert.equal(state.writes.length, 0); assert.equal(state.endings[0].status, "INACTIVE");
  const submission = setup({ ending: state.endings[0], data: { ...data, staff: [inactive] } });
  await compile("confirmEnding", submission.bindings)(event());
  assert.equal(submission.state.writes.length, 1);
});
for (const fields of [
  { reason: "" }, { reason: "  " }, { reason: "ab" }, { reason: "x".repeat(501) },
  { endDate: "" }, { endDate: "2026-02-30" }, { endDate: "not-a-date" }, { endDate: "2026-07-31" },
  { handoffConfirmed: null },
]) {
  test(`invalid ending input ${Object.keys(fields)[0]} cannot write (${String(Object.values(fields)[0]).length})`, async () => {
    const { state, bindings } = setup();
    await compile("confirmEnding", bindings)(event(fields));
    assert.equal(state.writes.length, 0); assert.equal(state.endings.length, 0); assert.ok(state.errors.length);
  });
}
for (const overrides of [
  { ending: null }, { busy: true }, { endingInFlight: { current: true } }, { review: {} }, { reviewInFlight: { current: true } },
  { data: null }, { data: { ...data, canManage: false } },
  { data: { ...data, schoolId: "other-school" } }, { selectedId: "other-staff" },
  { data: { ...data, staff: [{ ...staff, rowVersion: 5 }] } },
  { data: { ...data, staff: [{ ...staff, status: "ENDED" }] } },
  { ending: { ...ending, status: "ENDED" }, data: { ...data, staff: [{ ...staff, status: "ENDED" }] } },
]) {
  test(`unsafe or changed ending context ${JSON.stringify(overrides)} cannot write`, async () => {
    const { state, bindings } = setup(overrides);
    await compile("confirmEnding", bindings)(event());
    assert.equal(state.writes.length, 0); assert.equal(state.endings.length, 0);
  });
}
for (const kind of ["staff", "assignment"]) {
  test(`confirmed ${kind} ending preserves exact API/version and uses the operator's date/reason`, async () => {
    const item = kind === "staff" ? staff : assignment;
    const { state, bindings } = setup({ ending: { ...ending, kind, id: item.id, expectedVersion: item.rowVersion, startsOn: kind === "staff" ? staff.joinedOn : assignment.startsOn } });
    const e = event();
    await compile("confirmEnding", bindings)(e);
    assert.equal(state.writes.length, 1);
    assert.equal(state.writes[0][0], `/api/v1/people/${kind === "staff" ? "staff" : "assignments"}/${item.id}/end`);
    assert.equal(state.writes[0][1].method, "POST");
    assert.deepEqual(JSON.parse(state.writes[0][1].body), { schoolId: data.schoolId, expectedVersion: item.rowVersion, endDate: "2026-10-08", reason: "Synthetic approved end reason" });
    assert.deepEqual(state.endings, [null]);
    state.frames.forEach((run) => run()); assert.deepEqual(state.focus, ["saved"]);
    assert.equal(bindings.endingInFlight.current, false);
  });
}
test("failed server ending retains the review/date/reason and refocuses the reason", async () => {
  const { state, bindings } = setup({ mutate: async () => false });
  const e = event(); const original = structuredClone(e.currentTarget.values);
  await compile("confirmEnding", bindings)(e);
  assert.deepEqual(state.endings, []); assert.deepEqual(e.currentTarget.values, original);
  state.frames.forEach((run) => run()); assert.deepEqual(state.focus, ["reason"]);
});
test("rapid double confirmation is blocked before React renders busy; cancellation cannot race it", async () => {
  let finish; let count = 0;
  const { state, bindings } = setup({ mutate: () => { count++; return new Promise((resolve) => { finish = resolve; }); } });
  const first = compile("confirmEnding", bindings)(event());
  await compile("confirmEnding", bindings)(event());
  compile("cancelEnding", bindings)();
  assert.equal(count, 1); assert.deepEqual(state.endings, []);
  finish(true); await first;
});
test("cancellation writes nothing and returns focus after the workspace is re-enabled", () => {
  let restored = false;
  const { state, bindings } = setup({ endingTrigger: { current: { isConnected: true, focus: () => { restored = true; } } } });
  compile("cancelEnding", bindings)();
  assert.deepEqual(state.writes, []); assert.deepEqual(state.endings, [null]); assert.equal(restored, false);
  state.frames.forEach((run) => run()); assert.equal(restored, true);
});
test("actual ending panel has named context, date/reason/acknowledgement and keyboard cancel", () => {
  assert.ok(formNode); assert.ok(contentNode);
  const code = ts.transpileModule(`const view = (${formNode.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
  let cancelled = 0;
  const render = (busy) => new Function("React", "ending", "busy", "confirmEnding", "cancelEnding", "endingPanel", "endingDate", "endingReason", `${code}\nreturn view;`)(React, { ...ending, title: "End employment", description: "Synthetic immediate access impact" }, busy, () => {}, () => { cancelled++; }, { current: null }, { current: null }, { current: null });
  const view = render(false); const html = renderToStaticMarkup(view);
  assert.match(html, /aria-labelledby="people-ending-title"/); assert.match(html, /aria-describedby="people-ending-help"/);
  assert.match(html, /Synthetic Staff/); assert.match(html, /SYN-END-08/);
  assert.match(html, /<input(?=[^>]*type="date")(?=[^>]*name="endDate")(?=[^>]*min="2026-08-01")(?=[^>]*required="")[^>]*>/);
  assert.match(html, /name="reason"[^>]*minLength="3"[^>]*maxLength="500"/);
  assert.match(html, /<input(?=[^>]*name="handoffConfirmed")(?=[^>]*required="")[^>]*>/);
  assert.match(html, /future date does not schedule/);
  assert.match(html, /type="submit"/); assert.match(html, /type="button"/);
  view.props.onKeyDown({ key: "Escape", preventDefault() {} }); assert.equal(cancelled, 1);
  assert.match(renderToStaticMarkup(render(true)), /aria-busy="true"/);
  assert.equal(contentNode.openingElement.tagName.getText(source), "fieldset");
  const disabled = contentNode.openingElement.attributes.properties.find(attribute =>
    ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "disabled");
  assert.ok(disabled?.initializer && ts.isJsxExpression(disabled.initializer) && disabled.initializer.expression,
    "The actual shared controls must retain their native fieldset guard");
  const evaluate = new Function("busy", "ending", "review", "invitationBlocked",
    `return (${disabled.initializer.expression.getText(source)});`);
  assert.equal(evaluate(false, null, null, false), false, "Idle controls remain available");
  for (const [name, values] of [
    ["busy", [true, null, null, false]],
    ["ending review", [false, ending, null, false]],
    ["public review", [false, null, {}, false]],
    ["staff invitation", [false, null, null, true]],
    ["overlapping operations", [true, ending, {}, true]],
  ]) assert.equal(evaluate(...values), true, `${name} independently locks the shared controls`);
  assert.doesNotMatch(text, /window\.(confirm|prompt)\(/);
});

test("ending controls reuse defined semantic tokens and accessible touch sizes", () => {
  const css = readFileSync(new URL("../app/workspace/staff/people.css", import.meta.url), "utf8");
  const theme = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");
  const panel = css.slice(css.indexOf(".people-content{"));
  assert.match(panel, /\.people-content\{min-width:0;margin:0;padding:0;border:0\}/);
  assert.match(panel, /people-ending-confirmation button\{min-height:44px/);
  assert.match(panel, /background:var\(--theme-input\);color:var\(--theme-text\)/);
  for (const token of panel.matchAll(/var\((--theme-[\w-]+)\)/g)) assert.match(theme, new RegExp(`${token[1]}:`));
  assert.doesNotMatch(panel, /background:#|data-theme|animation:|scroll-behavior:smooth/);
});

test("ended employment is read-only instead of offering a server-forbidden protected edit", () => {
  assert.ok(actionsNode && ts.isJsxExpression(actionsNode));
  const code = ts.transpileModule(`const view = (${actionsNode.expression.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
  const render = (status) => new Function("React", "data", "selected", "busy", "setEditProfile", "endRecord", "today", `${code}\nreturn view;`)(React, data, { ...staff, status }, false, () => {}, () => {}, () => "2026-10-08");
  assert.equal(render("ENDED"), false);
  assert.match(renderToStaticMarkup(render("ACTIVE")), /Edit profile/);
  assert.match(renderToStaticMarkup(render("ACTIVE")), /Offboard safely/);
  assert.match(renderToStaticMarkup(render("INACTIVE")), /Edit profile/);
  assert.match(renderToStaticMarkup(render("INACTIVE")), /Offboard safely/);
});
