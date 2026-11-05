import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const text = readFileSync(new URL("../components/workspace/people-workspace.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("people.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const functions = new Map();
const variables = new Map();
let publication;
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) functions.set(node.name.text, node);
  if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) variables.set(node.name.text, node.initializer);
  if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some((attribute) => ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className" && attribute.initializer?.getText(source) === '"people-publication"')) publication = node;
  ts.forEachChild(node, visit);
}
visit(source);
function evaluate(fragment, bindings) {
  const code = ts.transpileModule(`const result = (${fragment});`, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  return new Function(...Object.keys(bindings), `${code}\nreturn result;`)(...Object.values(bindings));
}
function handler(name, bindings = {}) {
  assert.ok(functions.has(name), name);
  return evaluate(functions.get(name).getText(source), bindings);
}
function value(name, bindings) {
  assert.ok(variables.has(name), name);
  return evaluate(variables.get(name).getText(source), bindings);
}
function descendants(view, predicate) {
  const found = [];
  function walk(node) {
    React.Children.forEach(node, (child) => {
      if (!React.isValidElement(child)) return;
      if (predicate(child)) found.push(child);
      walk(child.props.children);
    });
  }
  walk(view);
  return found;
}
const staff = { id: "synthetic-staff", displayName: "Synthetic Staff", status: "ACTIVE", workEmail: "private-synthetic@example.test", workPhone: "private synthetic number", userAccountId: "private-synthetic-account" };
const rejected = { id: "synthetic-rejected", staffProfileId: staff.id, revisionNumber: 2, supersedesRevisionId: "synthetic-published", status: "REJECTED", rowVersion: 2, displayName: "Synthetic Public Name", designation: "Synthetic Office", qualificationSummary: "Synthetic Certificate", shortBio: "Synthetic public biography", cbseStaffCategory: "NON_TEACHING", displayOrder: 4, creatorName: "Synthetic Maker", deciderName: "Synthetic Checker", decisionReason: "Private synthetic rejection reason", restrictedNote: "Private synthetic safeguarding note" };
const published = { ...rejected, id: "synthetic-published", revisionNumber: 1, supersedesRevisionId: null, status: "PUBLISHED" };
const data = { schoolId: "synthetic-school", canManage: true, canPublish: true, staff: [staff], publicProfiles: [published, rejected] };
const canCreatePublicRevision = handler("canCreatePublicRevision");
function render(snapshot = data, overrides = {}) {
  const changes = [];
  const bindings = {
    React, data: snapshot, busy: false, supersedesId: "", editPublicId: "", selectedId: staff.id,
    canCreatePublicRevision, cbseCategories: ["NON_TEACHING"], label: (name) => name ?? "Not set", staffName: () => staff.displayName,
    PeopleIcon: () => null, savePublicProfile: () => {}, publicWorkflow: () => { throw new Error("A new revision must not submit or decide the predecessor"); },
    setSupersedesId: (id) => changes.push(["supersedes", id]), setEditPublicId: (id) => changes.push(["edit", id]), ...overrides,
  };
  bindings.publicDraft = value("publicDraft", bindings);
  bindings.superseded = value("superseded", bindings);
  bindings.publicRevisionSources = value("publicRevisionSources", bindings);
  assert.ok(publication);
  return { view: evaluate(publication.getText(source), bindings), changes, bindings };
}
function article(view, id) { return descendants(view, (node) => node.type === "article" && node.key === id)[0]; }
function buttons(view) { return descendants(view, (node) => node.type === "button"); }
function select(view, name) { return descendants(view, (node) => node.type === "select" && (name ? node.props.name === name : !node.props.name))[0]; }

for (const status of ["PUBLISHED", "REJECTED", "DRAFT", "PENDING_APPROVAL", "RETIRED"]) {
  test(`${status} is a new-revision source only when approved/rejected and without a successor`, () => {
    const item = { ...rejected, status };
    assert.equal(canCreatePublicRevision(item, [item]), ["PUBLISHED", "REJECTED"].includes(status));
    for (const nextStatus of ["DRAFT", "PENDING_APPROVAL", "REJECTED", "PUBLISHED", "RETIRED"]) {
      assert.equal(canCreatePublicRevision(item, [item, { ...item, id: "synthetic-successor", supersedesRevisionId: item.id, status: nextStatus }]), false);
    }
  });
}

test("rejected snapshot offers an append-only correction, not edit/resubmit or direct publication", () => {
  const result = render();
  const actions = buttons(article(result.view, rejected.id));
  assert.equal(actions.length, 1);
  assert.equal(actions[0].props.children, "Correct rejected revision");
  assert.equal(actions[0].props.type, "button");
  const original = structuredClone(data);
  actions[0].props.onClick();
  assert.deepEqual(result.changes, [["supersedes", rejected.id], ["edit", ""]]);
  assert.deepEqual(data, original);
  assert.match(renderToStaticMarkup(result.view), /current published snapshot stays live until a new revision receives independent approval/);
});

test("only the current lineage tip can be chosen; an earlier published revision with a rejected successor is excluded", () => {
  const result = render();
  assert.deepEqual(result.bindings.publicRevisionSources.map((item) => item.id), [rejected.id]);
  assert.equal(buttons(article(result.view, published.id)).length, 0);
  const html = renderToStaticMarkup(result.view);
  assert.match(html, /Previous approved or rejected revision/);
  assert.match(html, /Synthetic Public Name · r2 · REJECTED/);
});

test("a published tip retains its existing create-next-revision action", () => {
  const result = render({ ...data, publicProfiles: [published] });
  const action = buttons(article(result.view, published.id))[0];
  assert.equal(action.props.children, "Create next revision");
  action.props.onClick();
  assert.deepEqual(result.changes, [["supersedes", published.id], ["edit", ""]]);
});

test("predecessor correction action and choice disappear when a successor draft exists, but current draft remains editable", () => {
  const draft = { ...rejected, id: "synthetic-correction", revisionNumber: 3, supersedesRevisionId: rejected.id, status: "DRAFT", rowVersion: 1 };
  const result = render({ ...data, publicProfiles: [...data.publicProfiles, draft] });
  assert.equal(buttons(article(result.view, rejected.id)).length, 0);
  assert.deepEqual(result.bindings.publicRevisionSources, []);
  const actions = buttons(article(result.view, draft.id));
  assert.deepEqual(actions.map((button) => button.props.children), ["Edit", "Submit"]);
  actions[0].props.onClick();
  assert.deepEqual(result.changes, [["edit", draft.id], ["supersedes", ""]]);
});

test("editing a current draft keeps its own rejected predecessor visible in the disabled parent selector", () => {
  const draft = { ...rejected, id: "synthetic-correction", revisionNumber: 3, supersedesRevisionId: rejected.id, status: "DRAFT", rowVersion: 1 };
  const result = render({ ...data, publicProfiles: [...data.publicProfiles, draft] }, { editPublicId: draft.id });
  assert.deepEqual(result.bindings.publicRevisionSources.map((item) => item.id), [rejected.id]);
  const parent = select(result.view);
  assert.equal(parent.props.disabled, true);
  assert.equal(parent.props.value, rejected.id);
  assert.match(renderToStaticMarkup(parent), /value="synthetic-rejected" selected=""/);
  assert.equal(select(result.view, "staffProfileId").props.disabled, true);
});

test("correction form copies only intended publication fields into a new private draft and locks the staff source", () => {
  const result = render(data, { supersedesId: rejected.id });
  const form = descendants(result.view, (node) => node.type === "form")[0];
  const fields = descendants(form, (node) => ["input", "textarea", "select"].includes(node.type) && node.props.name);
  const fieldValues = Object.fromEntries(fields.map((node) => [node.props.name, node.props.defaultValue]));
  assert.deepEqual(fieldValues, {
    staffProfileId: staff.id, displayName: rejected.displayName, designation: rejected.designation,
    qualificationSummary: rejected.qualificationSummary, cbseStaffCategory: rejected.cbseStaffCategory,
    displayOrder: rejected.displayOrder, shortBio: rejected.shortBio, reason: "New public revision after staff information review",
  });
  assert.equal(select(form, "staffProfileId").props.disabled, true);
  assert.match(renderToStaticMarkup(form), /Revision 3/);
  assert.match(renderToStaticMarkup(form), /Create private draft/);
  assert.doesNotMatch(renderToStaticMarkup(form), /private-synthetic|Private synthetic|private synthetic|workEmail|workPhone|userAccountId|decisionReason|restrictedNote/);
});

test("publication-only or read-only membership cannot create or edit correction drafts", () => {
  const result = render({ ...data, canManage: false });
  assert.equal(buttons(article(result.view, rejected.id)).length, 0);
  assert.equal(descendants(result.view, (node) => node.type === "form").length, 0);
});

const formFields = { staffProfileId: "spoofed-other-staff", displayName: "Synthetic Corrected Public Name", designation: "Synthetic Office", qualificationSummary: "Synthetic Certificate", shortBio: "Synthetic corrected biography", cbseStaffCategory: "NON_TEACHING", displayOrder: "6", reason: "Synthetic correction after independent rejection" };
for (const saved of [true, false]) {
  for (const editing of [false, true]) {
    test(`${editing ? "editing the successor draft" : "creating a correction"}: ${saved ? "acknowledged" : "rejected"} save preserves predecessor, lineage and existing API contract`, async () => {
      const draft = editing ? { ...rejected, id: "synthetic-correction", revisionNumber: 3, supersedesRevisionId: rejected.id, status: "DRAFT", rowVersion: 7 } : undefined;
      const requests = []; const changes = []; let resets = 0; let resolve;
      const form = { reset: () => { resets++; } };
      const before = structuredClone(rejected);
      const save = handler("savePublicProfile", {
        data, superseded: editing ? undefined : rejected, publicDraft: draft,
        FormData: class { get(name) { return formFields[name] ?? null; } },
        mutate: (...args) => { requests.push(args); return new Promise((done) => { resolve = done; }); },
        setSupersedesId: (id) => changes.push(["supersedes", id]), setEditPublicId: (id) => changes.push(["edit", id]),
      });
      const event = { currentTarget: form, preventDefault() {} };
      const pending = save(event); event.currentTarget = null;
      assert.equal(requests.length, 1); assert.equal(resets, 0); assert.deepEqual(changes, []);
      assert.equal(requests[0][0], editing ? `/api/v1/people/public-profiles/${draft.id}` : "/api/v1/people/public-profiles");
      assert.equal(requests[0][1].method, editing ? "PUT" : "POST");
      assert.deepEqual(JSON.parse(requests[0][1].body), {
        schoolId: data.schoolId, staffProfileId: staff.id, revisionNumber: 3, supersedesRevisionId: rejected.id,
        displayName: formFields.displayName, designation: formFields.designation, qualificationSummary: formFields.qualificationSummary,
        shortBio: formFields.shortBio, cbseStaffCategory: formFields.cbseStaffCategory, displayOrder: 6,
        expectedVersion: editing ? 7 : null, reason: formFields.reason,
      });
      resolve(saved); await pending;
      assert.equal(resets, saved ? 1 : 0);
      assert.deepEqual(changes, saved ? [["supersedes", ""], ["edit", ""]] : []);
      assert.deepEqual(rejected, before);
    });
  }
}
