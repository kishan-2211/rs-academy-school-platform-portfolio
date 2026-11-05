import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = ts.createSourceFile("ptm-centre.tsx", readFileSync(new URL("../components/family/ptm-centre.tsx", import.meta.url), "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let submit;
function visit(node) {
  if (ts.isFunctionDeclaration(node) && node.name?.text === "submit") submit = node;
  ts.forEachChild(node, visit);
}
visit(source);
assert.ok(submit, "exercise the actual family booking handler");
const emitted = ts.transpileModule(`const handler = ${submit.getText(source)};`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
}).outputText;

test("failed booking retries retain their key but a new booking after acknowledgement gets a fresh key", async () => {
  const requests = [];
  const errors = [];
  const keys = { current: new Map() };
  let sequence = 0;
  let fail = true;
  class Fields {
    constructor(form) { this.values = form; }
    get(name) { return this.values[name] ?? null; }
  }
  const handler = new Function("busy", "FormData", "learnerId", "booked", "event", "setBusy", "setError", "setNotice", "mutate", "keys", "crypto", "setCancelling", "refresh",
    `${emitted}\nreturn handler;`)(
    false, Fields, "synthetic-learner", false, {}, () => {}, (value) => errors.push(value), () => {},
    async (path, body) => { requests.push({ path, body }); if (fail) throw new Error("Connection unavailable"); },
    keys, { randomUUID: () => `synthetic-key-${++sequence}` }, () => {}, () => {},
  );
  const event = () => ({ preventDefault() {}, currentTarget: { slotId: "synthetic-slot", topic: "Synthetic topic" } });
  await handler(event());
  assert.equal(keys.current.size, 1, "retain an unresolved request for safe retry");
  assert.ok(errors.includes("Connection unavailable"));
  fail = false;
  await handler(event());
  assert.equal(requests[0].body.idempotencyKey, requests[1].body.idempotencyKey);
  assert.equal(keys.current.size, 0, "the successful attempt is complete");
  // After cancellation React re-renders this same meeting with booked=false.
  // The shared retry map must not replay that completed/cancelled booking.
  await handler(event());
  assert.notEqual(requests[1].body.idempotencyKey, requests[2].body.idempotencyKey);
  assert.equal(requests[2].path, "/api/v1/family/children/synthetic-learner/ptm/slots/synthetic-slot/book");
  assert.equal(requests[2].body.topic, "Synthetic topic");
});
