import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const workspace = readFileSync(new URL("../components/workspace/official-documents-workspace.tsx", import.meta.url), "utf8");
const css = readFileSync(new URL("../app/workspace/documents/documents.css", import.meta.url), "utf8");
const source = ts.createSourceFile("actual.tsx", workspace, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let navigation;
function visit(node) {
  if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some((attribute) =>
    ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className" &&
    attribute.initializer && ts.isStringLiteral(attribute.initializer) &&
    attribute.initializer.text === "documents-tabs")) navigation = node;
  ts.forEachChild(node, visit);
}
visit(source);
assert.ok(navigation, "Missing actual document-section navigation");
const navigationCode = ts.transpileModule(`const element = (${navigation.getText(source)});`, {
  compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 },
}).outputText;

for (const tab of ["issue", "templates", "history"]) {
  test(`actual document ${tab} selection exposes native keyboard button semantics`, () => {
    const changes = [];
    const reviewResets = [];
    const element = new Function("React", "tab", "pending", "setTab", "setReviewed", `${navigationCode}\nreturn element;`)
      (React, tab, [], (value) => changes.push(value), (value) => reviewResets.push(value));
    const buttons = React.Children.toArray(element.props.children);
    assert.deepEqual(buttons.map((button) => button.props["aria-pressed"]),
      ["issue", "templates", "history"].map((value) => value === tab));
    buttons.forEach((button) => assert.equal(button.props.type, "button"));
    buttons.forEach((button) => button.props.onClick());
    assert.deepEqual(changes, ["issue", "templates", "history"]);
    assert.deepEqual(reviewResets, [{}, {}], "Keep existing review-reset behavior");
    assert.equal((renderToStaticMarkup(element).match(/aria-pressed="true"/g) ?? []).length, 1);
    assert.doesNotMatch(renderToStaticMarkup(element), /aria-current="page"/);
  });
}

const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
function rule(selector) {
  const found = rules.filter((entry) => entry[1].trim().split(/,\s*/).includes(selector));
  assert.ok(found.length, `Missing actual ${selector}`);
  return found.map((entry) => entry[2]).join("\n");
}

test("document panels, navigation, forms and loading use the established semantic palette", () => {
  for (const selector of [".documents-workspace", ".documents-state"])
    assert.match(rule(selector), /color:\s*var\(--theme-text\)/);
  for (const selector of [".documents-panel", ".documents-tabs button.is-active"])
    assert.match(rule(selector), /background:\s*var\(--theme-card\)/);
  for (const selector of [".documents-form input", ".documents-queue select", ".documents-history input"])
    assert.match(rule(selector), /background:\s*var\(--theme-input\)/);
  for (const selector of [".documents-form label", ".documents-panel > header p", ".documents-templates p"])
    assert.match(rule(selector), /color:\s*var\(--theme-muted\)/);
  assert.match(rule(".documents-panel > header > span"), /color:\s*var\(--theme-success-text\)/);
});

test("document status and failure states use shared theme-aware colors", () => {
  for (const [selector, meaning] of [[".documents-banner.is-error", "error"], [".documents-banner.is-success", "success"],
    ['.documents-panel article b[data-status="ISSUED"]', "success"],
    ['.documents-panel article b[data-status="PENDING_APPROVAL"]', "warning"],
    ['.documents-panel article b[data-status="CANCELLED"]', "error"]]) {
    assert.match(rule(selector), new RegExp(`color:\\s*var\\(--theme-${meaning}-text\\)`));
    assert.match(rule(selector), new RegExp(`background:\\s*var\\(--theme-${meaning}-bg\\)`));
  }
  assert.match(rule(".documents-state p"), /color:\s*var\(--theme-error-text\)/);
});

test("document focus, intentional branded hero, mobile flow and reduced motion remain intact", () => {
  assert.match(css, /\.documents-workspace :is\(button, input, select, textarea, summary\):focus-visible \{ outline: 3px solid var\(--theme-text\)/);
  assert.match(rule(".documents-hero"), /linear-gradient\(135deg,#102b2f,#1d4b4a 56%,#366c61\)/);
  assert.match(rule(".documents-hero > div > span"), /color:\s*#7ce0c5/);
  assert.match(css, /@media \(max-width: 660px\)[\s\S]*\.documents-form--two \{ grid-template-columns: 1fr; \}/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)[\s\S]*animation: none !important/);
  assert.doesNotMatch(css, /data-theme|prefers-color-scheme/);
});
