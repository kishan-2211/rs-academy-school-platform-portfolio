import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = ts.createSourceFile("accounting-workspace.tsx", readFileSync(new URL("../components/workspace/accounting-workspace.tsx", import.meta.url), "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const css = readFileSync(new URL("../app/workspace/accounting/accounting.css", import.meta.url), "utf8");

test("all three accounting setup forms inherit the established labeled field styling", () => {
  const forms = [];
  function visit(node, styled = false) {
    if (ts.isJsxElement(node)) {
      const attributes = node.openingElement.attributes.properties;
      const classes = attributes.find((attribute) => ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className");
      const value = classes?.initializer && ts.isStringLiteral(classes.initializer) ? classes.initializer.text.split(/\s+/) : [];
      styled ||= value.includes("accounting-form");
      const handler = attributes.find((attribute) => ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "onSubmit");
      const name = handler?.initializer?.getText(source).replace(/[{}]/g, "");
      if (["createYear", "createAccount", "createVendor"].includes(name)) forms.push({ name, styled });
    }
    ts.forEachChild(node, (child) => visit(child, styled));
  }
  visit(source);
  assert.deepEqual(forms, ["createYear", "createAccount", "createVendor"].map((name) => ({ name, styled: true })));
  assert.match(css, /\.accounting-filters label, \.accounting-form label \{[^}]*min-width: 0;/);
});

test("phone accounting filters cannot inherit column wrapping and a full-height first field", () => {
  const phoneRules = css.slice(css.indexOf("@media (max-width: 620px)"), css.indexOf("@media (prefers-reduced-motion"));
  assert.match(phoneRules, /\.accounting-filters \{[^}]*flex-direction: column;[^}]*flex-wrap: nowrap;/);
  assert.match(phoneRules, /\.accounting-filters label:first-child \{ flex: initial; \}/);
  assert.match(css, /\.accounting-page :focus-visible \{ outline: 3px/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
});
