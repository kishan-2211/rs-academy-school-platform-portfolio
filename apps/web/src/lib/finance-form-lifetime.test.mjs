import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = ts.createSourceFile(
  "finance-workspace.tsx",
  readFileSync(new URL("../components/workspace/finance-workspace.tsx", import.meta.url), "utf8"),
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

const cases = [
  ["createHead", "/fee-heads", { code: "SYN_TUITION", name: "Synthetic tuition", category: "TUITION" }],
  ["createPlan", "/plans", { sessionId: "synthetic-session", code: "SYN_CORE", name: "Synthetic plan", currencyCode: "INR", receiptPrefix: "SYN" }],
  ["addPlanItem", "/plans/synthetic-plan/items", { feeHeadId: "synthetic-head", sequenceNumber: "1", label: "Synthetic tuition", dueOn: "2026-10-03", amount: "1000", allowPartial: "on" }],
  ["assignPlan", "/plan-assignments", { planId: "synthetic-plan", enrolmentId: "synthetic-enrolment", reason: "Synthetic assignment" }],
  ["requestConcession", "/concessions", { chargeId: "synthetic-charge", concessionType: "HARDSHIP", amount: "100", reason: "Synthetic review" }],
  ["proposeDayClose", "/day-closes", { businessDate: "2026-10-03", CASH: "1000", BANK_TRANSFER: "0", CHEQUE: "0", SCHOOL_UPI: "0", ONLINE_PROVIDER: "0", reason: "Synthetic declaration" }],
];

for (const [name, path, fields] of cases) {
  for (const saved of [true, false]) {
    test(`${name} ${saved ? "resets the saved form" : "retains rejected inputs"} after React clears currentTarget`, async () => {
      const declaration = handlers.get(name);
      assert.ok(declaration, name);
      const compiled = ts.transpileModule(`const handler = ${declaration.getText(source)};`, {
        compilerOptions: { target: ts.ScriptTarget.ES2022 },
      }).outputText;
      let resolve;
      let request;
      let resets = 0;
      class Fields {
        constructor(form) { this.form = form; }
        get(key) { return this.form.values[key] ?? null; }
      }
      const handler = new Function("data", "selectedPlan", "selectedAccount", "mutate", "FormData",
        `${compiled}\nreturn handler;`)(
        { schoolId: "synthetic-school", feePlans: [] },
        { id: "synthetic-plan" },
        { id: "synthetic-account" },
        (...args) => { request = args; return new Promise((done) => { resolve = done; }); },
        Fields,
      );
      const form = { values: { ...fields }, reset() { resets++; } };
      const event = { currentTarget: form, preventDefault() {} };
      const pending = handler(event);
      event.currentTarget = null;
      assert.equal(resets, 0);
      resolve(saved);
      await pending;
      assert.equal(resets, saved ? 1 : 0);
      assert.equal(request[0], `/api/v1/finance${path}`);
      assert.equal(request[1].method, "POST");
      assert.equal(JSON.parse(request[1].body).schoolId, "synthetic-school");
      assert.deepEqual(form.values, fields);
    });
  }
}
