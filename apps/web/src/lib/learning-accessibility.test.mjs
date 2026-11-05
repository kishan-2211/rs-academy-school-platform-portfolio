import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");
const workspace = read("../components/workspace/learning-workspace.tsx");
const family = read("../components/family/family-portal.tsx");
const css = read("../app/workspace/learning/learning.css");

// Render the real JSX, rather than a separate example of the intended behavior.
function expression(text, className) {
  const source = ts.createSourceFile("actual.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let found;
  function visit(node) {
    if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some((attribute) =>
      ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className" &&
      attribute.initializer && ts.isStringLiteral(attribute.initializer) &&
      attribute.initializer.text === className)) found = node;
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.ok(found, `Missing actual ${className}`);
  return ts.transpileModule(`const element = (${found.getText(source)});`, {
    compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 },
  }).outputText;
}

for (const tab of ["board", "configure"]) {
  test(`actual homework navigation announces ${tab} with native keyboard buttons`, () => {
    const changes = [];
    const element = new Function("React", "tab", "setTab", `${expression(workspace, "learning-tabs")}\nreturn element;`)
      (React, tab, (value) => changes.push(value));
    const buttons = React.Children.toArray(element.props.children);
    assert.deepEqual(buttons.map((button) => button.props["aria-pressed"]),
      ["board", "configure"].map((value) => value === tab));
    buttons.forEach((button) => assert.equal(button.props.type, "button"));
    buttons[1].props.onClick();
    assert.deepEqual(changes, ["configure"]);
    assert.equal((renderToStaticMarkup(element).match(/aria-pressed="true"/g) ?? []).length, 1);
  });
}

test("homework detail is a named region inside the existing shell, not a second main", () => {
  assert.doesNotMatch(workspace, /<main\b/);
  assert.match(workspace, /<section className="learning-detail" aria-labelledby="learning-assignment-title">/);
  assert.equal((workspace.match(/<h2 id="learning-assignment-title">/g) ?? []).length, 2,
    "Both mutually exclusive selected and empty states must name the region");
  assert.match(workspace, /aria-pressed=\{item\.id === selectedId\}/);
});

function familyMarkup(className, assignment) {
  const element = new Function("React", "assignment", "displayCode", "t",
    `${expression(family, className)}\nreturn element;`)(React, assignment, (value) => value, (value) => value);
  return renderToStaticMarkup(element);
}

for (const maximumPoints of [undefined, null, 10]) {
  test(`actual family metadata handles ${maximumPoints === undefined ? "omitted" : maximumPoints} maximum points`, () => {
    const html = familyMarkup("family-assignments__meta", { maximumPoints, submissionMode: "TEXT", allowLate: false });
    assert.ok(html.includes(maximumPoints == null ? "Not point-scored" : "10 points"));
    assert.doesNotMatch(html, /undefined|null points/);
  });
}

for (const pointsAwarded of [undefined, null, 0, 8]) {
  test(`actual family feedback handles ${pointsAwarded === undefined ? "omitted" : pointsAwarded} awarded points`, () => {
    const html = familyMarkup("family-assignments__feedback", {
      maximumPoints: 10, submission: { teacherFeedback: "Synthetic feedback", pointsAwarded },
    });
    if (pointsAwarded == null) assert.doesNotMatch(html, /<span>/);
    else assert.match(html, new RegExp(`${pointsAwarded} / 10`));
    assert.doesNotMatch(html, /undefined|null/);
  });
}

test("family feedback does not fabricate a maximum when it is omitted", () => {
  const html = familyMarkup("family-assignments__feedback", {
    submission: { teacherFeedback: "Synthetic feedback", pointsAwarded: 0 },
  });
  assert.match(html, /0.*points/s);
  assert.doesNotMatch(html.replace(/<[^>]*>/g, ""), /\/|undefined/);
});

const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
function rule(selector) {
  const found = rules.filter((entry) => entry[1].trim().split(/,\s*/).includes(selector));
  assert.ok(found.length, `Missing ${selector}`);
  return found.map((entry) => entry[2]).join("\n");
}

test("homework cards, inputs, review content, metadata and focus reuse shared semantic colors", () => {
  assert.match(rule(".learning-workspace"), /color: var\(--theme-text\)/);
  for (const selector of [".learning-tabs", ".learning-panel", ".learning-detail", ".learning-assignment-list > button"])
    assert.match(rule(selector), /background: var\(--theme-card\)/);
  for (const selector of [".learning-form input", ".learning-submissions form select", ".learning-transition-reason input"])
    assert.match(rule(selector), /background: var\(--theme-input\)/);
  for (const selector of [".learning-form label > span", ".learning-assignment-list button small", ".learning-empty"])
    assert.match(rule(selector), /color: var\(--theme-muted\)/);
  assert.match(rule(".learning-response"), /background: var\(--theme-surface\)/);
  assert.match(rule(".learning-feedback"), /background: var\(--theme-success-bg\)/);
  assert.match(rule(".learning-workspace :focus-visible"), /outline: 3px solid var\(--theme-success-text\)/);
});

test("homework status/error colors remain semantic while fixed brand hero and mobile layout remain", () => {
  for (const [selector, meaning] of [[".learning-notice", "success"], [".learning-notice.is-error", "error"],
    [".learning-state-pill--returned", "warning"], [".learning-state-pill--accepted", "success"]]) {
    assert.match(rule(selector), new RegExp(`color: var\\(--theme-${meaning}-text\\)`));
    assert.match(rule(selector), new RegExp(`background: var\\(--theme-${meaning}-bg\\)`));
  }
  assert.match(rule(".learning-primary"), /color: var\(--theme-action-text\)/);
  assert.match(rule(".learning-hero"), /linear-gradient\(135deg, #263027, #53694c\)/);
  assert.match(css, /@media \(max-width: 900px\)[\s\S]*grid-template-columns: 1fr/);
  assert.match(css, /@media \(max-width: 720px\)[\s\S]*\.learning-submissions form/);
  assert.doesNotMatch(css, /data-theme|prefers-color-scheme|animation:/);
});
