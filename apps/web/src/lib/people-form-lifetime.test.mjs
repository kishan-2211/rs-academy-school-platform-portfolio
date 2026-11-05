import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const filename = "people-workspace.tsx";
const source = ts.createSourceFile(filename, readFileSync(new URL(`../components/workspace/${filename}`, import.meta.url), "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const handlers = new Map();
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) handlers.set(node.name.text, node);
  ts.forEachChild(node, visit);
}
visit(source);
function handler(name, bindings) {
  assert.ok(handlers.has(name));
  const compiled = ts.transpileModule(`const handler = ${handlers.get(name).getText(source)};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(bindings), `${compiled}\nreturn handler;`)(...Object.values(bindings));
}

const fields = {
  employeeCode: "SYN-STAF-08", legalName: "Synthetic Service Record", displayName: "Synthetic Staff",
  primaryLanguage: "EN", joinedOn: "2026-10-08", status: "ACTIVE", reason: "Synthetic acceptance only",
  code: "SYN_OCT08", name: "Synthetic Configuration", description: "Synthetic notes",
  designationId: "synthetic-designation", departmentId: "synthetic-department", campusId: "synthetic-campus",
  employmentType: "PART_TIME", employerKind: "SCHOOL", startsOn: "2026-10-08", primaryAssignment: "on",
  staffCategory: "ADMINISTRATION", displayOrder: "3", validityMonths: "12", requiredForActiveService: "on",
  level: "CERTIFICATE", completedYear: "2025", complianceTypeId: "synthetic-rule",
  safeReference: "SYN-REF-08", restrictedNote: "Synthetic restricted note", staffProfileId: "synthetic-staff",
  designation: "Synthetic office role", shortBio: "Synthetic draft only",
};
const selected = { id: "synthetic-staff", rowVersion: 4 };
const draft = { id: "synthetic-draft", rowVersion: 7 };
const cases = [
  { name: "saveStaff", path: "/staff", expected: { employeeCode: fields.employeeCode, expectedVersion: null } },
  { name: "saveStaff", variant: "update", path: `/staff/${selected.id}`, overrides: { editProfile: true }, expected: { employeeCode: fields.employeeCode, expectedVersion: selected.rowVersion } },
  { name: "saveAssignment", path: "/assignments", expected: { staffProfileId: selected.id, primaryAssignment: true, startsOn: fields.startsOn } },
  { name: "saveDepartment", path: "/departments", expected: { code: fields.code, expectedVersion: null } },
  { name: "saveDepartment", variant: "update", path: `/departments/${draft.id}`, overrides: { departmentDraft: draft }, expected: { code: fields.code, expectedVersion: draft.rowVersion } },
  { name: "saveDesignation", path: "/designations", expected: { staffCategory: fields.staffCategory, teachingRole: false, leadershipRole: false, displayOrder: 3, expectedVersion: null } },
  { name: "saveDesignation", variant: "update", path: `/designations/${draft.id}`, overrides: { designationDraft: draft }, expected: { code: fields.code, expectedVersion: draft.rowVersion } },
  { name: "saveComplianceType", path: "/compliance-types", expected: { requiredForActiveService: true, validityMonths: 12, expectedVersion: null } },
  { name: "saveComplianceType", variant: "update", path: `/compliance-types/${draft.id}`, overrides: { complianceTypeDraft: draft }, expected: { code: fields.code, expectedVersion: draft.rowVersion } },
  { name: "saveQualification", path: "/qualifications", expected: { staffProfileId: selected.id, completedYear: 2025 } },
  { name: "saveCompliance", path: "/compliance-checks", expected: { staffProfileId: selected.id, safeReference: fields.safeReference, restrictedNote: fields.restrictedNote } },
  { name: "savePublicProfile", path: "/public-profiles", expected: { staffProfileId: selected.id, revisionNumber: 1, supersedesRevisionId: null, expectedVersion: null } },
  { name: "savePublicProfile", variant: "next revision", path: "/public-profiles", overrides: { superseded: { id: "synthetic-old-publication", staffProfileId: selected.id, revisionNumber: 2 } }, expected: { staffProfileId: selected.id, revisionNumber: 3, supersedesRevisionId: "synthetic-old-publication", expectedVersion: null } },
  { name: "savePublicProfile", variant: "update", path: `/public-profiles/${draft.id}`, overrides: { publicDraft: { ...draft, staffProfileId: selected.id, revisionNumber: 3, supersedesRevisionId: "synthetic-old-publication" } }, expected: { staffProfileId: selected.id, revisionNumber: 3, supersedesRevisionId: "synthetic-old-publication", expectedVersion: draft.rowVersion } },
];

for (const scenario of cases) {
  for (const saved of [true, false]) {
    test(`${scenario.name} ${scenario.variant ?? "create"}: ${saved ? "clears acknowledged form" : "retains rejected draft"} after React releases currentTarget`, async () => {
      let request;
      let resolve;
      let resets = 0;
      const changed = [];
      const form = { values: { ...fields }, reset() { resets++; } };
      const bindings = {
        data: { schoolId: "synthetic-school" }, selected, editProfile: false,
        departmentDraft: undefined, designationDraft: undefined, complianceTypeDraft: undefined,
        superseded: undefined, publicDraft: undefined,
        setEditProfile: (value) => changed.push(["profile", value]),
        setEditDepartmentId: (value) => changed.push(["department", value]),
        setEditDesignationId: (value) => changed.push(["designation", value]),
        setEditComplianceTypeId: (value) => changed.push(["compliance", value]),
        setSupersedesId: (value) => changed.push(["supersedes", value]),
        setEditPublicId: (value) => changed.push(["public", value]),
        FormData: class { constructor(element) { assert.equal(element, form); this.values = { ...element.values }; } get(name) { return this.values[name] ?? null; } },
        mutate: (...args) => { request = args; return new Promise((done) => { resolve = done; }); },
        ...scenario.overrides,
      };
      const event = { currentTarget: form, preventDefault() {} };
      const pending = handler(scenario.name, bindings)(event);
      event.currentTarget = null;
      assert.equal(resets, 0);
      assert.deepEqual(changed, []);
      resolve(saved);
      await pending;
      assert.equal(resets, saved ? 1 : 0);
      if (!saved) assert.deepEqual(changed, []);
      assert.equal(request[0], `/api/v1/people${scenario.path}`);
      assert.equal(request[1].method, scenario.variant === "update" ? "PUT" : "POST");
      const payload = JSON.parse(request[1].body);
      assert.equal(payload.schoolId, "synthetic-school");
      assert.equal(payload.reason, fields.reason);
      for (const [name, expected] of Object.entries(scenario.expected)) assert.deepEqual(payload[name], expected, name);
      assert.deepEqual(form.values, fields);
    });
  }
}

for (const name of ["saveAssignment", "saveQualification", "saveCompliance"]) {
  test(`${name} does not submit without a current staff record`, async () => {
    await handler(name, { data: { schoolId: "synthetic-school" }, selected: null })( { preventDefault() {} } );
  });
}
