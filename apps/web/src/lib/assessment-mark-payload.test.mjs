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
const declarations = new Map();
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name) declarations.set(node.name.text, node);
  ts.forEachChild(node, visit);
}
visit(source);
function handler(name, bindings) {
  const declaration = declarations.get(name);
  assert.ok(declaration);
  const compiled = ts.transpileModule(
    `${declarations.get("nullableNumber").getText(source)}\nconst handler = ${declaration.getText(source)};`,
    {
      compilerOptions: { target: ts.ScriptTarget.ES2022 },
    },
  ).outputText;
  return new Function(...Object.keys(bindings), `${compiled}\nreturn handler;`)(
    ...Object.values(bindings),
  );
}

for (const value of ["", "   ", "0", "8.5"]) {
  test(`marks entry preserves ${JSON.stringify(value)} without manufacturing zero`, async () => {
    let request;
    const save = handler("saveMarks", {
      data: { schoolId: "synthetic-school" },
      selected: {
        id: "synthetic-assessment",
        rowVersion: 3,
        scoringMode: "MARKS",
        marks: [{ id: "synthetic-mark", learnerId: "synthetic-learner", rowVersion: 2 }],
      },
      draftMarks: {
        "synthetic-mark": {
          state: "RECORDED",
          marks: value,
          grade: "",
          narrative: "",
          remark: "Synthetic only",
        },
      },
      entryReason: "Local test only",
      setError() {},
      setEntryReason() {},
      mutate(...args) {
        request = args;
        return Promise.resolve(true);
      },
    });
    await save();
    assert.equal(request[0], "/api/v1/assessment/assessments/synthetic-assessment/marks");
    assert.equal(request[2], "PUT");
    assert.equal(request[1].marks[0].marksAwarded, value.trim() ? Number(value) : null);
    assert.equal(request[1].marks[0].expectedVersion, 2);
    assert.equal(request[1].expectedAssessmentVersion, 3);
  });

  test(`correction preserves ${JSON.stringify(value)} without manufacturing zero`, async () => {
    let request;
    const correct = handler("requestCorrection", {
      data: { schoolId: "synthetic-school" },
      selected: {
        scoringMode: "MARKS",
        marks: [{ id: "synthetic-mark", teacherRemark: null }],
      },
      correctionMarkId: "synthetic-mark",
      correctionState: "RECORDED",
      correctionValue: value,
      correctionReason: "Local test only",
      setError() {},
      setCorrectionMarkId() {},
      setCorrectionValue() {},
      setCorrectionReason() {},
      mutate(...args) {
        request = args;
        return Promise.resolve(true);
      },
    });
    await correct();
    assert.equal(request[0], "/api/v1/assessment/corrections");
    assert.equal(request[1].marksAwarded, value.trim() ? Number(value) : null);
    assert.equal(request[1].reason, "Local test only");
  });
}

for (const scoringMode of ["MARKS", "GRADE", "NARRATIVE"]) {
  for (const teacherRemark of [null, "Synthetic existing teacher remark", ""]) {
    test(`${scoringMode} correction preserves the existing ${JSON.stringify(teacherRemark)} remark`, async () => {
      let request;
      const correct = handler("requestCorrection", {
        data: { schoolId: "synthetic-school" },
        selected: { scoringMode, marks: [{ id: "synthetic-mark", teacherRemark }] },
        correctionMarkId: "synthetic-mark",
        correctionState: "RECORDED",
        correctionValue: scoringMode === "MARKS" ? "9" : "Synthetic evidence",
        correctionReason: "Synthetic value-only correction",
        setError() {},
        setCorrectionMarkId() {},
        setCorrectionValue() {},
        setCorrectionReason() {},
        mutate(...args) {
          request = args;
          return Promise.resolve(true);
        },
      });
      await correct();
      assert.equal(request[0], "/api/v1/assessment/corrections");
      assert.equal(request[1].teacherRemark, teacherRemark);
      assert.equal(request[1].markId, "synthetic-mark");
      assert.equal(request[1].evidenceState, "RECORDED");
    });
  }
}

test("a stale correction selection does not submit or clear the draft", async () => {
  let request;
  let error;
  let resets = 0;
  const correct = handler("requestCorrection", {
    data: { schoolId: "synthetic-school" },
    selected: {
      scoringMode: "MARKS",
      marks: [{ id: "current-synthetic-mark", teacherRemark: "Preserve this" }],
    },
    correctionMarkId: "previous-synthetic-mark",
    correctionState: "RECORDED",
    correctionValue: "9",
    correctionReason: "Synthetic stale selection",
    setError(value) {
      error = value;
    },
    setCorrectionMarkId() {
      resets++;
    },
    setCorrectionValue() {
      resets++;
    },
    setCorrectionReason() {
      resets++;
    },
    mutate(...args) {
      request = args;
      return Promise.resolve(true);
    },
  });
  await correct();
  assert.equal(request, undefined);
  assert.match(error, /choose a learner/i);
  assert.equal(resets, 0);
});
