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
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) functions.set(node.name.text, node);
  if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some((a) => ts.isJsxAttribute(a) && a.name.getText(source) === "className" && a.initializer?.getText(source).includes("people-review-confirmation"))) formNode = node;
  ts.forEachChild(node, visit);
}
visit(source);
function compile(name, bindings) {
  assert.ok(functions.has(name), name);
  const code = ts.transpileModule(`const handler = ${functions.get(name).getText(source)};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(bindings), `${code}\nreturn handler;`)(...Object.values(bindings));
}
const staff = { id: "synthetic-staff", employeeCode: "SYN-RVW-08", displayName: "Synthetic Review Staff", status: "ACTIVE", workEmail: "private-synthetic@example.test" };
const qualification = { id: "synthetic-qualification", staffProfileId: staff.id, name: "Synthetic Certificate", level: "CERTIFICATE", verificationStatus: "PENDING", rowVersion: 2 };
const rule = { id: "synthetic-rule", name: "Synthetic Source Check", validityMonths: 6, status: "ACTIVE", rowVersion: 3 };
const compliance = { id: "synthetic-check", staffProfileId: staff.id, complianceTypeId: rule.id, safeReference: "SYNTHETIC-REF", restrictedNote: "Private synthetic note", status: "PENDING", rowVersion: 4 };
const profile = { id: "synthetic-public", staffProfileId: staff.id, status: "DRAFT", rowVersion: 5, revisionNumber: 2, displayName: "Synthetic Public Name", designation: "Synthetic Office", qualificationSummary: "Synthetic Certificate", shortBio: "Synthetic biography only", cbseStaffCategory: "NON_TEACHING", creatorName: "Synthetic Maker", supersedesRevisionId: "synthetic-previous" };
const data = { schoolId: "synthetic-school", canManage: true, canReviewCompliance: true, canPublish: true, staff: [staff], qualifications: [qualification], complianceTypes: [rule], complianceChecks: [compliance], publicProfiles: [profile] };
const cases = [
  { kind: "qualifications", decision: "VERIFIED", item: qualification },
  { kind: "qualifications", decision: "REJECTED", item: qualification },
  { kind: "compliance-checks", decision: "VERIFIED", item: compliance },
  { kind: "compliance-checks", decision: "WAIVED", item: compliance },
  { kind: "compliance-checks", decision: "REJECTED", item: compliance },
  { kind: "public-profiles", action: "submit", item: profile },
  { kind: "public-profiles", action: "decision", decision: "PUBLISHED", item: { ...profile, status: "PENDING_APPROVAL" } },
  { kind: "public-profiles", action: "decision", decision: "REJECTED", item: { ...profile, status: "PENDING_APPROVAL" } },
];
function setup(overrides = {}) {
  const state = { writes: [], reviews: [], errors: [], frames: [], focus: [] };
  const bindings = {
    data, selectedId: staff.id, busy: false, ending: null, endingInFlight: { current: false },
    review: null, reviewInFlight: { current: false }, reviewTrigger: { current: null },
    reviewReason: { current: { focus: () => state.focus.push("reason") } }, noticeTarget: { current: { focus: () => state.focus.push("saved") } },
    document: { activeElement: null }, HTMLElement: class {}, today: () => "2026-10-08",
    label: (value) => value ?? "Not set", setError: (value) => state.errors.push(value), setNotice: () => {},
    setReview: (value) => state.reviews.push(value), requestAnimationFrame: (action) => state.frames.push(action),
    FormData: class { constructor(form) { this.form = form; } get(name) { return this.form.values[name] ?? null; } },
    mutate: async (...args) => { state.writes.push(args); return true; }, ...overrides,
  };
  return { state, bindings };
}
function prepare(scenario, overrides = {}) {
  const result = setup({ data: { ...data, publicProfiles: [scenario.item.id === profile.id ? scenario.item : profile] }, ...overrides });
  if (scenario.kind === "public-profiles") compile("publicWorkflow", result.bindings)(scenario.item, scenario.action, scenario.decision);
  else compile("decideEvidence", result.bindings)(scenario.kind, scenario.item.id, scenario.item.rowVersion, scenario.decision);
  return result;
}
const event = (fields = {}) => ({ preventDefault() {}, currentTarget: { values: { reason: "  Synthetic independently reviewed reason  ", sourceConfirmed: "on", completedOn: "2026-10-02", validUntil: "2027-01-01", ...fields } } });

for (const scenario of cases) {
  const name = `${scenario.kind} ${scenario.decision ?? scenario.action}`;
  test(`${name}: first click prepares exact source/version without writing or inventing a reason`, () => {
    const result = prepare(scenario);
    assert.equal(result.state.writes.length, 0);
    assert.equal(result.state.reviews.length, 1);
    const review = result.state.reviews[0];
    assert.equal(review.schoolId, data.schoolId); assert.equal(review.id, scenario.item.id);
    assert.equal(review.staffId, staff.id); assert.equal(review.expectedVersion, scenario.item.rowVersion);
    assert.equal(review.reason, undefined);
  });
  for (const ok of [true, false]) {
    test(`${name}: ${ok ? "acknowledged" : "rejected"} review preserves API and operator input`, async () => {
      const prepared = prepare(scenario);
      const review = prepared.state.reviews[0]; assert.ok(review);
      const result = setup({ review, data: prepared.bindings.data, mutate: async (...args) => { result.state.writes.push(args); return ok; } });
      const e = event(); const original = structuredClone(e.currentTarget.values);
      await compile("confirmReview", result.bindings)(e);
      assert.equal(result.state.writes.length, 1);
      const [path, request] = result.state.writes[0];
      assert.equal(path, `/api/v1/people/${scenario.kind}/${scenario.item.id}/${scenario.action ?? "decision"}`);
      assert.equal(request.method, "POST");
      const expected = { schoolId: data.schoolId, expectedVersion: scenario.item.rowVersion, reason: "Synthetic independently reviewed reason" };
      if (scenario.decision) expected.decision = scenario.decision;
      if (scenario.kind === "compliance-checks") {
        expected.completedOn = scenario.decision === "VERIFIED" ? "2026-10-02" : null;
        expected.validUntil = null;
      }
      assert.deepEqual(JSON.parse(request.body), expected);
      assert.deepEqual(e.currentTarget.values, original);
      assert.deepEqual(result.state.reviews, ok ? [null] : []);
      result.state.frames.forEach((run) => run()); assert.deepEqual(result.state.focus, [ok ? "saved" : "reason"]);
      assert.equal(result.bindings.reviewInFlight.current, false);
    });
  }
}
for (const [index, overrides] of [
  { busy: true }, { ending: {} }, { endingInFlight: { current: true } }, { review: {} }, { reviewInFlight: { current: true } },
  { data: { ...data, canReviewCompliance: false } }, { selectedId: "other-staff" },
  { data: { ...data, qualifications: [{ ...qualification, rowVersion: 9 }] } },
  { data: { ...data, qualifications: [{ ...qualification, verificationStatus: "VERIFIED" }] } },
].entries()) {
  test(`unsafe evidence preparation ${index + 1} cannot write or open a review`, () => {
    const result = prepare(cases[0], overrides);
    assert.equal(result.state.writes.length, 0); assert.equal(result.state.reviews.length, 0);
  });
}
for (const [index, fields] of [
  { reason: "" }, { reason: "  " }, { reason: "ab" }, { reason: "x".repeat(501) }, { sourceConfirmed: null },
  { completedOn: "" }, { completedOn: "2026-02-30" },
].entries()) {
  test(`invalid compliance review input ${index + 1} cannot write`, async () => {
    const prepared = prepare(cases[2]);
    const result = setup({ review: prepared.state.reviews[0] });
    await compile("confirmReview", result.bindings)(event(fields));
    assert.equal(result.state.writes.length, 0); assert.equal(result.state.reviews.length, 0); assert.ok(result.state.errors.length);
  });
}
for (const [index, overrides] of [
  { data: null }, { data: { ...data, schoolId: "other-school" } }, { data: { ...data, canReviewCompliance: false } },
  { selectedId: "other-staff" }, { busy: true }, { ending: {} }, { endingInFlight: { current: true } }, { reviewInFlight: { current: true } },
  { data: { ...data, qualifications: [{ ...qualification, rowVersion: 8 }] } },
  { data: { ...data, qualifications: [{ ...qualification, verificationStatus: "REJECTED" }] } },
].entries()) {
  test(`unsafe or changed review context ${index + 1} cannot write`, async () => {
    const prepared = prepare(cases[0]);
    const result = setup({ review: prepared.state.reviews[0], ...overrides });
    await compile("confirmReview", result.bindings)(event());
    assert.equal(result.state.writes.length, 0); assert.equal(result.state.reviews.length, 0);
  });
}
test("manual optional expiry is editable only without a configured month rule", async () => {
  const manual = { ...data, complianceTypes: [{ ...rule, validityMonths: null }] };
  const prepared = prepare(cases[2], { data: manual });
  for (const value of ["2026-12-31", "", "2026-10-01", "2026-02-30"]) {
    const result = setup({ review: prepared.state.reviews[0], data: manual });
    await compile("confirmReview", result.bindings)(event({ validUntil: value }));
    if (value === "2026-12-31" || value === "") assert.equal(JSON.parse(result.state.writes[0][1].body).validUntil, value || null);
    else assert.equal(result.state.writes.length, 0);
  }
});
test("changed rule metadata requires re-review rather than silently using an old displayed policy", async () => {
  const prepared = prepare(cases[2]);
  const result = setup({ review: prepared.state.reviews[0], data: { ...data, complianceTypes: [{ ...rule, rowVersion: 4, validityMonths: 12 }] } });
  await compile("confirmReview", result.bindings)(event()); assert.equal(result.state.writes.length, 0);
});
test("public approval preview includes only intended publication fields, not private people/check data", () => {
  const result = prepare(cases[6]); const review = result.state.reviews[0];
  assert.ok(review);
  const view = JSON.stringify(review.fields);
  assert.match(view, /Synthetic Public Name/); assert.match(view, /Synthetic Certificate/); assert.match(view, /Synthetic biography only/);
  assert.doesNotMatch(view, /private-synthetic|Private synthetic note|SYNTHETIC-REF|userAccountId|workPhone/);
});
test("public review preserves all-school list behavior without depending on directory selection", async () => {
  const prepared = prepare(cases[6], { selectedId: "different-directory-selection" });
  const result = setup({ review: prepared.state.reviews[0], data: prepared.bindings.data, selectedId: "different-directory-selection" });
  await compile("confirmReview", result.bindings)(event()); assert.equal(result.state.writes.length, 1);
});
test("public management and publication capabilities remain distinct", () => {
  const submit = prepare(cases[5], { data: { ...data, canManage: false } });
  const publish = prepare(cases[6], { data: { ...data, canPublish: false, publicProfiles: [cases[6].item] } });
  assert.equal(submit.state.reviews.length, 0); assert.equal(publish.state.reviews.length, 0);
});

test("unknown decisions and missing evidence/rules cannot prepare a review", () => {
  for (const scenario of [{ ...cases[0], decision: "WAIVED" }, { ...cases[2], decision: "PUBLISHED" }]) {
    assert.equal(prepare(scenario).state.reviews.length, 0);
  }
  for (const source of [{ ...data, qualifications: [] }, { ...data, staff: [] }, { ...data, complianceTypes: [] }]) {
    assert.equal(prepare(source.complianceTypes.length ? cases[0] : cases[2], { data: source }).state.reviews.length, 0);
  }
});

test("public review cannot prepare on changed versions/status, missing records or invalid decisions", () => {
  for (const override of [
    { data: null }, { busy: true }, { ending: {} }, { review: {} }, { reviewInFlight: { current: true } },
    { data: { ...data, publicProfiles: [] } }, { data: { ...data, staff: [] } },
    { data: { ...data, publicProfiles: [{ ...profile, rowVersion: 99 }] } },
    { data: { ...data, publicProfiles: [{ ...profile, status: "REJECTED" }] } },
  ]) assert.equal(prepare(cases[5], override).state.reviews.length, 0);
  assert.equal(prepare({ ...cases[6], decision: "VERIFIED" }).state.reviews.length, 0);
});

test("public review checks school, access and exact revision again at confirmation", async () => {
  const prepared = prepare(cases[6]);
  for (const source of [
    { ...prepared.bindings.data, schoolId: "other-school" }, { ...prepared.bindings.data, canPublish: false },
    { ...prepared.bindings.data, publicProfiles: [{ ...cases[6].item, rowVersion: 99 }] },
    { ...prepared.bindings.data, publicProfiles: [{ ...cases[6].item, status: "PUBLISHED" }] },
    { ...prepared.bindings.data, staff: [] },
  ]) {
    const result = setup({ review: prepared.state.reviews[0], data: source });
    await compile("confirmReview", result.bindings)(event()); assert.equal(result.state.writes.length, 0);
  }
});

test("non-pending or unexpected prepared decisions still fail closed", async () => {
  const prepared = prepare(cases[0]);
  for (const patch of [{ decision: "WAIVED" }, { action: "submit" }, { status: "VERIFIED" }]) {
    const result = setup({ review: { ...prepared.state.reviews[0], ...patch } });
    await compile("confirmReview", result.bindings)(event()); assert.equal(result.state.writes.length, 0);
  }
});
test("rapid duplicate submission and racing cancellation cannot post twice", async () => {
  const prepared = prepare(cases[0]); let finish; let count = 0;
  const result = setup({ review: prepared.state.reviews[0], mutate: () => { count++; return new Promise((resolve) => { finish = resolve; }); } });
  const pending = compile("confirmReview", result.bindings)(event());
  await compile("confirmReview", result.bindings)(event()); compile("cancelReview", result.bindings)();
  assert.equal(count, 1); assert.deepEqual(result.state.reviews, []); finish(true); await pending;
});
test("cancel review writes nothing and restores its connected trigger after rendering", () => {
  let focused = false;
  const result = setup({ reviewTrigger: { current: { isConnected: true, focus: () => { focused = true; } } } });
  compile("cancelReview", result.bindings)(); assert.deepEqual(result.state.reviews, [null]); assert.equal(focused, false);
  result.state.frames.forEach((run) => run()); assert.equal(focused, true); assert.equal(result.state.writes.length, 0);
});
test("actual review form provides scoped context, native reason/acknowledgement and keyboard cancellation", () => {
  assert.ok(formNode);
  const code = ts.transpileModule(`const view = (${formNode.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
  let cancelled = 0;
  const render = (scenario, busy = false) => new Function("React", "review", "busy", "confirmReview", "cancelReview", "reviewPanel", "reviewReason", "today", `${code}\nreturn view;`)(React, prepare(scenario).state.reviews[0], busy, () => {}, () => { cancelled++; }, { current: null }, { current: null }, () => "2026-10-08");
  const view = render(cases[2]); const html = renderToStaticMarkup(view);
  assert.match(html, /aria-labelledby="people-review-title"/); assert.match(html, /aria-describedby="people-review-help"/);
  assert.match(html, /name="reason"[^>]*minLength="3"[^>]*maxLength="500"/);
  assert.match(html, /<input(?=[^>]*name="sourceConfirmed")(?=[^>]*required="")[^>]*>/);
  assert.match(html, /name="completedOn"/); assert.doesNotMatch(html, /name="validUntil"/);
  assert.match(html, /server/); assert.match(html, /different authorized/);
  assert.doesNotMatch(renderToStaticMarkup(render(cases[0])), /name="completedOn"|name="validUntil"/);
  assert.match(renderToStaticMarkup(render(cases[6])), /Synthetic Public Name/);
  assert.match(renderToStaticMarkup(render(cases[6])), /Created by Synthetic Maker/);
  assert.match(renderToStaticMarkup(render(cases[6], true)), /aria-busy="true"/);
  view.props.onKeyDown({ key: "Escape", preventDefault() {} }); assert.equal(cancelled, 1);
  assert.doesNotMatch(text, /window\.(confirm|prompt)\(/);
});

test("optional expiry uses a native labelled date input only for a rule without configured months", () => {
  const code = ts.transpileModule(`const view = (${formNode.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
  const prepared = prepare(cases[2], { data: { ...data, complianceTypes: [{ ...rule, validityMonths: null }] } });
  const view = new Function("React", "review", "busy", "confirmReview", "cancelReview", "reviewPanel", "reviewReason", "today", `${code}\nreturn view;`)(React, prepared.state.reviews[0], false, () => {}, () => {}, { current: null }, { current: null }, () => "2026-10-08");
  const html = renderToStaticMarkup(view);
  assert.match(html, /Valid until \(optional\)<input type="date" name="validUntil"/);
  assert.doesNotMatch(html, /server calculates expiry/);
});
