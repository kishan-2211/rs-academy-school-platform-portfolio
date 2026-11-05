import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = ts.createSourceFile(
  "school-calendar-workspace.tsx",
  readFileSync(new URL("../components/workspace/school-calendar-workspace.tsx", import.meta.url), "utf8"),
  ts.ScriptTarget.Latest,
  true,
  ts.ScriptKind.TSX,
);
const handlers = new Map();
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) handlers.set(node.name.text, node);
  ts.forEachChild(node, visit);
}
visit(source);

const fields = {
  schoolStartTime: "08:00", sameDayCutoffMinutes: "60", reminderTime: "18:00",
  timeZone: "Asia/Kolkata", studentInAppEnabled: "on", authorityConfirmed: "on",
  calendarDate: "2026-10-03", titleEn: "Synthetic closure", titleHi: "परीक्षण बंद",
  bodyEn: "Synthetic acceptance only", bodyHi: "केवल परीक्षण",
  sourceReference: "local-test-only", bilingualContentConfirmed: "on", reason: "Synthetic test only",
};
const data = { schoolId: "synthetic-school", selectedCampusId: "synthetic-campus", selectedAcademicSessionId: "synthetic-session" };
const cases = [
  { name: "savePolicy", label: "new policy", data, path: "/policies", method: "POST" },
  { name: "savePolicy", label: "policy revision", data: { ...data, policy: { id: "policy", revisionNumber: 1 } }, path: "/policies/policy", method: "PUT", revision: 1 },
  { name: "saveNotice", label: "new notice", data, path: "/notices", method: "POST" },
  { name: "saveNotice", label: "notice correction", data, editing: true, selected: { noticeSeriesId: "notice", revisionNumber: 2 }, path: "/notices/notice/revisions", method: "POST", revision: 2 },
];

function makeHandler(name, scope, mutate, transition, editing = false, selected = null) {
  const declaration = handlers.get(name);
  assert.ok(declaration, `Missing ${name}`);
  const compiled = ts.transpileModule(`const handler = ${declaration.getText(source)};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  class Fields {
    constructor(form) { this.form = form; }
    get(key) { return this.form.values[key] ?? null; }
  }
  return new Function("data", "mutate", "FormData", "editing", "selected", "dayType", "noticeKind", "setTab", "setEditing", `${compiled}\nreturn handler;`)(
    scope, mutate, Fields, editing, selected, "CLOSURE", "EMERGENCY_CLOSURE",
    (value) => transition.push(["tab", value]), (value) => transition.push(["editing", value]),
  );
}

for (const scenario of cases) {
  for (const saved of [true, false]) {
    test(`${scenario.label} ${saved ? "clears acknowledged form" : "preserves rejected draft"} after React releases currentTarget`, async () => {
      let resolve;
      let request;
      let resets = 0;
      const transitions = [];
      const handler = makeHandler(scenario.name, scenario.data, (...args) => {
        request = args;
        return new Promise((done) => { resolve = done; });
      }, transitions, scenario.editing, scenario.selected);
      const form = { values: { ...fields }, reset() { resets++; } };
      const event = { currentTarget: form, preventDefault() {} };
      const pending = handler(event);
      event.currentTarget = null;
      assert.equal(resets, 0);
      assert.deepEqual(transitions, []);
      resolve(saved);
      await pending;
      assert.equal(resets, saved ? 1 : 0);
      assert.deepEqual(transitions, saved
        ? scenario.name === "saveNotice" ? [["editing", false], ["tab", "calendar"]] : [["tab", "calendar"]]
        : []);
      assert.equal(request[0], `/api/v1/school-calendar${scenario.path}`);
      assert.equal(request[1].method, scenario.method);
      const payload = JSON.parse(request[1].body);
      assert.equal(payload.schoolId, data.schoolId);
      assert.equal(payload.reason, fields.reason);
      assert.equal(payload.expectedRevision, scenario.revision);
      if (scenario.revision) {
        assert.equal(payload.campusId, undefined);
        assert.equal(payload.academicSessionId, undefined);
      } else {
        assert.equal(payload.campusId, data.selectedCampusId);
        assert.equal(payload.academicSessionId, data.selectedAcademicSessionId);
      }
      if (scenario.name === "saveNotice") {
        assert.equal(payload.dayType, "CLOSURE");
        assert.equal(payload.noticeKind, "EMERGENCY_CLOSURE");
        assert.equal(payload.titleHi, fields.titleHi);
        assert.equal(payload.bilingualContentConfirmed, true);
      } else {
        assert.equal(payload.authorityConfirmed, true);
        assert.equal(payload.guardianPushEnabled, false);
      }
      assert.deepEqual(form.values, fields);
    });
  }
}

for (const name of ["savePolicy", "saveNotice"]) {
  test(`${name} does not submit or reset when the selected scope is absent`, async () => {
    let calls = 0;
    const transitions = [];
    const handler = makeHandler(name, { schoolId: data.schoolId }, () => { calls++; }, transitions);
    await handler({ currentTarget: { reset() { calls++; } }, preventDefault() {} });
    assert.equal(calls, 0);
    assert.deepEqual(transitions, []);
  });
}

test("calendar navigation exposes selected states without nesting a second main landmark", () => {
  const text = source.getFullText();
  assert.doesNotMatch(text, /<main\b/);
  assert.match(text, /<section className="calendar-detail" aria-label="Calendar date details">/);
  for (const section of ["calendar", "notice", "reviews", "policy"]) {
    assert.ok(text.includes(`aria-pressed={tab === "${section}"}`), `Missing selected state for ${section}`);
  }
  assert.match(text, /aria-pressed=\{selectedId === item\.noticeSeriesId\}/);
});
