import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = ts.createSourceFile(
  "assessment.tsx",
  readFileSync(
    new URL("../components/workspace/assessment-workspace.tsx", import.meta.url),
    "utf8",
  ),
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
  ["createScale", "/grading-scales", { sessionId: "synthetic-session", name: "Synthetic scale" }],
  [
    "createBand",
    "/grade-bands",
    {
      scaleId: "synthetic-scale",
      code: "TEST",
      name: "Test band",
      minimum: "0",
      maximum: "100",
      outcome: "NOT_APPLICABLE",
      displayOrder: "0",
    },
  ],
  [
    "createScheme",
    "/schemes",
    {
      sessionId: "synthetic-session",
      scaleId: "synthetic-scale",
      gradeCode: "VI",
      name: "Synthetic scheme",
      aggregationMode: "WEIGHTED",
      roundingMode: "HALF_UP",
      decimalPlaces: "2",
      passRule: "NO_PASS_RULE",
    },
  ],
  [
    "createComponent",
    "/components",
    {
      schemeId: "synthetic-scheme",
      code: "SYN_TEST",
      name: "Synthetic component",
      scoringMode: "MARKS",
      weight: "100",
      defaultMax: "10",
      displayOrder: "0",
    },
  ],
  [
    "createAssessment",
    "/assessments",
    {
      termId: "synthetic-term",
      offeringId: "synthetic-offering",
      componentId: "synthetic-component",
      name: "Synthetic assessment",
      date: "2026-10-01",
      maximumMarks: "10",
    },
  ],
  [
    "publishReports",
    "/publications",
    {
      termId: "synthetic-term",
      sectionId: "synthetic-section",
      schemeId: "synthetic-scheme",
      name: "Synthetic publication",
      reason: "Local acceptance only",
    },
  ],
];

for (const [name, path, fields] of cases) {
  for (const saved of [true, false]) {
    test(`${name} ${saved ? "resets acknowledged form" : "retains rejected inputs"} after dispatch`, async () => {
      const declaration = handlers.get(name);
      assert.ok(declaration, name);
      const compiled = ts.transpileModule(`const handler = ${declaration.getText(source)};`, {
        compilerOptions: { target: ts.ScriptTarget.ES2022 },
      }).outputText;
      let resolve;
      let request;
      let resets = 0;
      class Fields {
        constructor(form) {
          this.form = form;
        }
        get(key) {
          return this.form.values[key] ?? null;
        }
      }
      const handler = new Function(
        "data",
        "mutate",
        "FormData",
        "nullableNumber",
        `${compiled}\nreturn handler;`,
      )(
        { schoolId: "synthetic-school" },
        (...args) => {
          request = args;
          return new Promise((done) => {
            resolve = done;
          });
        },
        Fields,
        (value) => (value == null || value === "" ? null : Number(value)),
      );
      const form = {
        values: { ...fields },
        reset() {
          resets++;
        },
      };
      const event = { currentTarget: form, preventDefault() {} };
      const pending = handler(event);
      event.currentTarget = null;
      assert.equal(resets, 0);
      resolve(saved);
      await pending;
      assert.equal(resets, saved ? 1 : 0);
      assert.equal(request[0], `/api/v1/assessment${path}`);
      assert.equal(request[1].schoolId, "synthetic-school");
      assert.equal(request[1].name, fields.name);
      assert.deepEqual(form.values, fields);
    });
  }
}
