import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const text = readFileSync(new URL("../components/workspace/learning-workspace.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("learning.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);

test("category form explains and enforces the database-compatible code format", () => {
  assert.match(text, /pattern="\[A-Za-z\]\[A-Za-z0-9_\]\{1,29\}"/);
  assert.match(text, /aria-describedby="learning-category-code-help"/);
  assert.match(text, /id="learning-category-code-help"/);
  assert.match(text, /minLength=\{2\}/);
});

test("teacher review controls have visible native labels and a learner-scoped form name", () => {
  let reviewForm;
  function visit(node) {
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText(source) === "form"
        && node.openingElement.getText(source).includes("review(event, submission)")) reviewForm = node;
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.ok(reviewForm);
  assert.match(reviewForm.openingElement.getText(source), /aria-label=/);
  const names = new Map();
  function controls(node) {
    if (ts.isJsxSelfClosingElement(node) || ts.isJsxOpeningElement(node)) {
      const name = node.attributes.properties.find((prop) => ts.isJsxAttribute(prop) && prop.name.text === "name");
      if (name && ts.isStringLiteral(name.initializer)) {
        const element = ts.isJsxOpeningElement(node) ? node.parent : node;
        assert.equal(element.parent.openingElement?.tagName.getText(source), "label");
        names.set(name.initializer.text, element.parent.getText(source));
      }
    }
    ts.forEachChild(node, controls);
  }
  controls(reviewForm);
  for (const [name, label] of [["decision", "Decision"], ["feedback", "Teacher feedback"], ["points", "Points /"], ["reason", "Review reason"]]) {
    assert.ok(names.get(name)?.includes(`<span>${label}`), `${name} needs its visible label`);
  }
});

// Run the real handler through its await boundary, including React's clearing
// of currentTarget after dispatch. Do not substitute a second implementation.
function compiledHandler(name) {
  let handler;
  function visit(node) {
    if (ts.isFunctionDeclaration(node) && node.name?.text === name) handler = node;
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.ok(handler, `${name} must exist`);
  return ts.transpileModule(`const handler = ${handler.getText(source)};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
}

class Fields {
  constructor(form) { this.form = form; }
  get(key) { return this.form.values[key] ?? null; }
}

function formHandler(name, mutate, editing = null, updates = []) {
  return new Function("data", "mutate", "FormData", "editing", "iso", "setSelectedId", "setEditingId",
    `${compiledHandler(name)}\nreturn handler;`)(
    { schoolId: "synthetic-school" }, mutate, Fields, editing,
    (value) => new Date(String(value)).toISOString(),
    (value) => updates.push(["selected", value]), (value) => updates.push(["editing", value]),
  );
}

for (const name of ["saveCategory", "saveAssignment", "review"]) {
  for (const saved of [true, false]) {
    test(`${name} retains its form across dispatch and ${saved ? "clears acknowledged data" : "preserves a rejected draft"}`, async () => {
      let complete;
      let submitted;
      let resets = 0;
      const updates = [];
      const form = { values: {
        sessionId: "synthetic-session", code: "SYNTHETIC", name: "Synthetic category",
        termId: "synthetic-term", offeringId: "synthetic-offering", title: "Synthetic assignment",
        instructions: "Synthetic instructions only", availableFrom: "2026-10-01T08:00",
        dueAt: "2026-10-08T08:00", submissionMode: "TEXT", decision: "ACCEPTED",
        feedback: "Synthetic feedback", points: "8", reason: "Synthetic acceptance only",
      }, reset() { resets++; } };
      const event = { currentTarget: form, preventDefault() {} };
      const handler = formHandler(name, (...args) => {
        submitted = args;
        return new Promise((resolve) => { complete = resolve; });
      }, null, updates);
      const pending = handler(event, { id: "synthetic-response", rowVersion: 3 });
      event.currentTarget = null;
      assert.equal(resets, 0, "pending data must not be cleared");
      complete(saved);
      await pending;
      assert.equal(resets, saved ? 1 : 0);
      const payload = JSON.parse(submitted[1].body);
      assert.equal(payload.schoolId, "synthetic-school");
      if (name === "review") {
        assert.equal(payload.expectedVersion, 3);
        assert.equal(payload.pointsAwarded, 8);
      }
      if (name === "saveAssignment") {
        assert.equal(payload.title, "Synthetic assignment");
        assert.deepEqual(updates, saved ? [["editing", ""]] : []);
      }
    });
  }
}

test("an acknowledged assignment revision keeps the existing versioned update and selection", async () => {
  const updates = [];
  let request;
  const handler = formHandler("saveAssignment", async (...args) => { request = args; return true; },
    { id: "synthetic-assignment", rowVersion: 4, status: "PUBLISHED" }, updates);
  const form = { values: { availableFrom: "2026-10-01T08:00", dueAt: "2026-10-08T08:00" }, reset() {} };
  const event = { currentTarget: form, preventDefault() {} };
  const pending = handler(event);
  event.currentTarget = null;
  await pending;
  assert.equal(request[0], "/api/v1/learning/assignments/synthetic-assignment");
  assert.equal(request[1].method, "PUT");
  assert.equal(JSON.parse(request[1].body).expectedVersion, 4);
  assert.deepEqual(updates, [["selected", "synthetic-assignment"], ["editing", ""]]);
});
