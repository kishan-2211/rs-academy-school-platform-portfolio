import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = ts.createSourceFile("academics.tsx",
  readFileSync(new URL("../components/workspace/academic-workspace.tsx", import.meta.url), "utf8"),
  ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let declaration;
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name?.text === "createCurriculumDraft") declaration = node;
  ts.forEachChild(node, visit);
}
visit(source);
assert.ok(declaration);
const handlerSource = ts.transpileModule(`const handler = ${declaration.getText(source)};`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
}).outputText;

for (const saved of [true, false]) {
  test(`curriculum review draft ${saved ? "clears the acknowledged form" : "preserves rejected inputs"} after React dispatch`, async () => {
    let resolve;
    let request;
    let resets = 0;
    class Fields { constructor(form) { this.form = form; } }
    const handler = new Function("data", "mutate", "FormData", "value", `${handlerSource}\nreturn handler;`)(
      { schoolId: "synthetic-school" }, (...args) => {
        request = args;
        return new Promise((done) => { resolve = done; });
      }, Fields, (fields, name) => fields.form.values[name],
    );
    const form = {
      values: { session: "synthetic-session", grade: "VI", reason: "Synthetic review only" },
      reset() { resets++; },
    };
    const event = { currentTarget: form, preventDefault() {} };
    const pending = handler(event);
    event.currentTarget = null;
    assert.equal(resets, 0);
    resolve(saved);
    await pending;
    assert.equal(resets, saved ? 1 : 0);
    assert.equal(request[0], "/api/v1/academics/curriculum/publications/drafts");
    assert.equal(request[1], "POST");
    assert.deepEqual(request[2], {
      schoolId: "synthetic-school", academicSessionId: "synthetic-session",
      gradeCode: "VI", reason: "Synthetic review only",
    });
  });
}
