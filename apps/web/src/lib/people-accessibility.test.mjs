import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const text = readFileSync(new URL("../components/workspace/people-workspace.tsx", import.meta.url), "utf8");
const css = readFileSync(new URL("../app/workspace/staff/people.css", import.meta.url), "utf8");
const source = ts.createSourceFile("people-workspace.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let navigation;
let profile;
let evidenceSelector;
const staffSelects = new Map();
function visit(node) {
  if (ts.isJsxElement(node)) {
    const tag = node.openingElement.tagName.getText(source);
    const attributes = node.openingElement.attributes.properties;
    const attribute = (name) => attributes.find((item) => ts.isJsxAttribute(item) && item.name.getText(source) === name)?.initializer?.getText(source);
    if (tag === "nav" && attribute("className") === '"people-tabs"') navigation = node;
    if (attribute("className") === '"people-detail"') profile = node;
    if (tag === "select" && attribute("value") === "{selectedId}") evidenceSelector = node;
    if (tag === "select" && attribute("name")) staffSelects.set(attribute("name"), node);
  }
  ts.forEachChild(node, visit);
}
visit(source);

for (const [name, values, initial] of [
  ["employmentType", ["FULL_TIME", "PART_TIME", "FIXED_TERM", "CONTRACT", "OUTSOURCED", "VOLUNTEER"], "FULL_TIME"],
  ["level", ["CERTIFICATE", "DIPLOMA", "GRADUATE", "POSTGRADUATE", "DOCTORATE", "PROFESSIONAL", "OTHER"], "GRADUATE"],
]) {
  test(`staff ${name} uses API values and honors its declared initial choice`, () => {
    const select = staffSelects.get(`"${name}"`);
    assert.ok(select);
    const code = ts.transpileModule(`const view = (${select.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
    const view = new Function("React", "label", `${code}\nreturn view;`)(React, (value) => value.toLowerCase().replaceAll("_", " "));
    assert.deepEqual(React.Children.toArray(view.props.children).map((option) => option.props.value), values);
    assert.equal(view.props.defaultValue, initial);
    assert.match(renderToStaticMarkup(view), new RegExp(`value="${initial}" selected=""`));
  });
}

for (const tab of ["directory", "configuration", "evidence", "publication"]) {
  test(`staff section ${tab} has selected semantics and native keyboard buttons`, () => {
    assert.ok(navigation);
    const code = ts.transpileModule(`const view = (${navigation.getText(source)});`, { compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 } }).outputText;
    const changes = [];
    const view = new Function("React", "tab", "label", "pendingEvidence", "pendingPublic", "setTab", `${code}\nreturn view;`)(React, tab, (value) => value, 2, 1, (value) => changes.push(value));
    const buttons = React.Children.toArray(view.props.children);
    assert.equal(buttons.length, 4);
    assert.deepEqual(buttons.map((button) => button.props["aria-pressed"]), ["directory", "configuration", "evidence", "publication"].map((value) => value === tab));
    buttons.forEach((button) => { assert.equal(button.type, "button"); assert.equal(button.props.type, "button"); });
    assert.equal((renderToStaticMarkup(view).match(/aria-pressed="true"/g) ?? []).length, 1);
    buttons[2].props.onClick();
    assert.deepEqual(changes, ["evidence"]);
  });
}

test("staff detail uses a named region within the shell's sole main landmark", () => {
  assert.ok(profile);
  assert.equal(profile.openingElement.tagName.getText(source), "section");
  assert.ok(profile.openingElement.attributes.properties.some((attribute) => ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "aria-label" && attribute.initializer?.getText(source) === '"Staff profile"'));
  assert.doesNotMatch(text, /<main\b/);
});

test("staff evidence selector has an accessible name", () => {
  assert.ok(evidenceSelector);
  assert.ok(evidenceSelector.openingElement.attributes.properties.some((attribute) => ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "aria-label" && attribute.initializer?.getText(source) === '"Staff member for evidence"'));
});

test("staff operational colors and focus reuse the shared theme, retaining the branded hero", () => {
  assert.match(css, /--ink:var\(--theme-text\);--muted:var\(--theme-muted\);--line:var\(--theme-border\)/);
  assert.match(css, /\.people-workspace :focus-visible\{outline:3px solid var\(--theme-success-text\);outline-offset:3px\}/);
  assert.match(css, /background:var\(--theme-card\)/);
  assert.match(css, /background:var\(--theme-input\);color:var\(--ink\)/);
  assert.match(css, /\.people-hero>div>span\{color:#bae7d3\}/);
  assert.match(css, /linear-gradient\(135deg,#182b36,#234950 55%,#317164\)/);
  assert.match(css, /\.people-workspace \[data-state="ACTIVE"\]/);
  assert.doesNotMatch(css, /(?<!people-workspace )\[data-state=/);
  assert.doesNotMatch(css, /data-theme|prefers-color-scheme|background:#fff;/);
});
