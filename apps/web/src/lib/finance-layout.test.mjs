import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const css=readFileSync(new URL("../app/workspace/fees/finance.css",import.meta.url),"utf8");
test("fee detail grid items may shrink instead of widening the whole desktop or mobile page",()=>{
  assert.match(css,/\.finance-detail\s*\{[^}]*min-width:\s*0[^}]*display:\s*grid/);
  assert.match(css,/\.finance-detail\s*>\s*\*\s*\{\s*min-width:\s*0;\s*\}/);
});
test("wide fee evidence stays readable inside its own horizontal scroll region",()=>{
  assert.match(css,/\.finance-table\s*\{[^}]*overflow-x:\s*auto/);
  assert.match(css,/\.finance-table__row\s*\{[^}]*min-width:\s*680px/);
  assert.doesNotMatch(css,/\.finance-(?:workspace|detail)\s*\{[^}]*overflow(?:-x)?:\s*(?:hidden|clip)/);
});
