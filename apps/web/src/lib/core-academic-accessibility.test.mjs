import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");
const academic = read("../components/workspace/academic-workspace.tsx");
const attendance = read("../components/workspace/attendance-workspace.tsx");
const css = read("../app/workspace/academics/academic.css");
const globals = read("../app/globals.css");

function renderNavigation(text, className, tab, capabilities = {}) {
  const source = ts.createSourceFile("workspace.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let navigation;
  function visit(node) {
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText(source) === "nav" &&
        node.openingElement.attributes.properties.some((attribute) => ts.isJsxAttribute(attribute) &&
          attribute.name.getText(source) === "className" && attribute.initializer &&
          ts.isStringLiteral(attribute.initializer) && attribute.initializer.text === className)) navigation = node;
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.ok(navigation, `Missing actual ${className} navigation`);
  const compiled = ts.transpileModule(`const navigation = (${navigation.getText(source)});`, {
    compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const changes = [];
  const element = new Function("React", "tab", "data", "draftEntries", "setTab", "t",
    `${compiled}\nreturn navigation;`)(React, tab, {
    sessions: [], terms: [], subjects: [], periods: [], resources: [], corrections: [], explanations: [],
    ...capabilities,
  }, [], (selected) => changes.push(selected), (label) => label);
  const buttons = React.Children.toArray(element.props.children).filter((child) => child.type === "button");
  return { html: renderToStaticMarkup(element), buttons, changes };
}

for (const selected of ["structure", "resources", "timetable"]) {
  test(`actual academic navigation announces ${selected} and preserves keyboard-native controls`, () => {
    const { html, buttons, changes } = renderNavigation(academic, "academic-tabs", selected);
    assert.equal(buttons.length, 3);
    assert.deepEqual(buttons.map((button) => button.props["aria-pressed"]),
      ["structure", "resources", "timetable"].map((value) => value === selected));
    assert.equal((html.match(/aria-pressed="true"/g) ?? []).length, 1);
    buttons.forEach((button) => assert.equal(button.props.type, "button"));
    buttons[2].props.onClick();
    assert.deepEqual(changes, ["timetable"]);
  });
}

for (const selected of ["register", "setup", "review"]) {
  test(`actual attendance navigation announces ${selected} without changing authority gating`, () => {
    const { buttons } = renderNavigation(attendance, "attendance-tabs", selected, { canConfigure: true, canCorrect: true });
    assert.deepEqual(buttons.map((button) => button.props["aria-pressed"]),
      ["register", "setup", "review"].map((value) => value === selected));
    const restricted = renderNavigation(attendance, "attendance-tabs", "register", { canConfigure: false, canCorrect: false });
    assert.equal(restricted.buttons.length, 1);
    assert.equal(restricted.buttons[0].props["aria-pressed"], true);
  });
}

test("attendance sheet is a named region, not a second nested main landmark", () => {
  assert.equal(/<main\b/.test(attendance), false, "Attendance must not nest main landmarks");
  assert.match(attendance, /<section className="attendance-sheet" aria-label=\{t\("Attendance register"\)\}>/);
});

test("academic operational surfaces use existing semantic colors, with fixed dark brand sections preserved", () => {
  const rule = (selector) => {
    const start = css.indexOf(`${selector}{`);
    assert.ok(start >= 0, `Missing ${selector}`);
    return css.slice(start, css.indexOf("}", start) + 1);
  };
  assert.match(rule(".academic-workspace"), /color:var\(--theme-text\)/);
  assert.match(rule(".academic-panel"), /background:var\(--theme-card\)/);
  assert.match(rule(".academic-form input,.academic-form select,.academic-form textarea"), /background:var\(--theme-input\)/);
  assert.match(rule(".academic-form label>span"), /color:var\(--theme-muted\)/);
  assert.match(rule(".academic-empty"), /background:var\(--theme-surface\)/);
  assert.match(rule(".academic-notice--error"), /background:var\(--theme-error-bg\)/);
  assert.match(rule(".academic-workspace :focus-visible"), /outline:3px solid var\(--theme-success-text\)/);
  assert.match(rule(".academic-hero"), /linear-gradient\(135deg,#102b2d 0%,#16484a 56%,#1c5d56 100%\)/);
  assert.match(rule(".academic-publish"), /background:#122f31;color:#fff/);
  assert.match(css, /@media\(prefers-reduced-motion:reduce\).*\.academic-loading i\{animation:none\}/s);
  assert.doesNotMatch(css, /data-theme|prefers-color-scheme/);
});

function luminance(hex) {
  const [r, g, b] = hex.slice(1).match(/../g).map((part) => Number.parseInt(part, 16) / 255)
    .map((value) => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
  return .2126 * r + .7152 * g + .0722 * b;
}

test("shared academic heading, label, field, badge and error colors meet AA text contrast in both themes", () => {
  for (const selector of [":root {", ':root[data-theme="dark"] {']) {
    const start = globals.indexOf(selector);
    const block = globals.slice(start, globals.indexOf("}", start));
    const values = Object.fromEntries([...block.matchAll(/(--[\w-]+):\s*(#[\da-f]{6})/g)].map((match) => [match[1], match[2]]));
    for (const [foreground, background] of [
      ["--theme-text", "--theme-card"], ["--theme-muted", "--theme-card"],
      ["--theme-text", "--theme-input"], ["--theme-success-text", "--theme-card"],
      ["--theme-success-text", "--theme-success-bg"], ["--theme-error-text", "--theme-error-bg"],
      ["--theme-warning-text", "--theme-warning-bg"], ["--theme-text", "--theme-warning-bg"],
      ["--theme-muted", "--theme-warning-bg"], ["--theme-action-text", "--theme-action-bg"],
      ["--theme-muted", "--theme-neutral-bg"], ["--theme-text", "--theme-hover"],
      ["--theme-muted", "--theme-surface"], ["--theme-text-soft", "--theme-surface"],
    ]) {
      const [lighter, darker] = [luminance(values[foreground]), luminance(values[background])].sort((a, b) => b - a);
      assert.ok((lighter + .05) / (darker + .05) >= 4.5, `${selector} ${foreground}/${background}`);
    }
  }
});
