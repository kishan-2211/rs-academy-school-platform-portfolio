import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");
const css = read("../app/workspace/my-academics/teacher-academic.css");
const globals = read("../app/globals.css");
const component = read("../components/workspace/teacher-academic-workspace.tsx");
const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];

function rule(selector) {
  const matches = rules.filter((entry) => entry[1].trim().split(/,\s*/).includes(selector));
  assert.ok(matches.length, `Missing actual ${selector} rule`);
  return matches.map((entry) => entry[2]).join("\n");
}

function renderDays(selectedDay, published = true) {
  const source = ts.createSourceFile("teacher.tsx", component, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let navigation;
  let actualDays;
  function visit(node) {
    if (ts.isVariableDeclaration(node) && node.name.getText(source) === "days") actualDays = node.initializer;
    if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some((attribute) =>
      ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className" &&
      attribute.initializer && ts.isStringLiteral(attribute.initializer) &&
      attribute.initializer.text === "teacher-academic__days")) navigation = node;
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.ok(navigation && actualDays, "Use the actual teacher day filter and day configuration");
  const compiled = ts.transpileModule(`const days = ${actualDays.getText(source)}; const navigation = (${navigation.getText(source)});`, {
    compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const changes = [];
  const element = new Function("React", "selectedDay", "availableDays", "data", "setSelectedDay", "t",
    `${compiled}\nreturn navigation;`)(React, selectedDay, new Set(published ? [1, 2] : []), {
    scheduleEntries: published ? [{ dayOfWeek: 1 }, { dayOfWeek: 1 }, { dayOfWeek: 2 }] : [],
  }, (value) => changes.push(value), (label) => label);
  return { buttons: React.Children.toArray(element.props.children), html: renderToStaticMarkup(element), changes };
}

for (const selectedDay of [0, 1, 2]) {
  test(`actual teacher day selector exposes selected state ${selectedDay} and native keyboard controls`, () => {
    const { buttons, html, changes } = renderDays(selectedDay);
    assert.equal(buttons.length, 3);
    assert.deepEqual(buttons.map((button) => button.props["aria-pressed"]), [0, 1, 2].map((day) => day === selectedDay));
    assert.equal((html.match(/aria-pressed="true"/g) ?? []).length, 1);
    buttons.forEach((button) => assert.equal(button.props.type, "button"));
    buttons[2].props.onClick();
    assert.deepEqual(changes, [2]);
    assert.match(html, /Filter timetable by day/);
  });
}

test("unpublished teacher timetable keeps only the existing all-week filter, without inventing periods", () => {
  const { buttons, html } = renderDays(0, false);
  assert.equal(buttons.length, 1);
  assert.equal(buttons[0].props["aria-pressed"], true);
  assert.match(html, /All week/);
  assert.doesNotMatch(html, /Mon|Tue/);
});

test("teacher cards, schedule, metadata and empty states inherit semantic surfaces and text", () => {
  assert.match(rule(".teacher-academic"), /color: var\(--theme-text\)/);
  for (const selector of [".teacher-academic__schedule", ".teacher-academic__groups", ".teacher-academic-state"])
    assert.match(rule(selector), /background: var\(--theme-card\)/);
  for (const selector of [".teacher-academic__entries article", ".teacher-academic__group-grid article", ".teacher-academic__empty", ".teacher-academic__days"])
    assert.match(rule(selector), /background: var\(--theme-surface\)/);
  for (const selector of [".teacher-academic__schedule > header p", ".teacher-academic__group-grid dt", ".teacher-academic__empty p"])
    assert.match(rule(selector), /color: var\(--theme-muted\)/);
  assert.match(rule(".teacher-academic__schedule > header span"), /color: var\(--theme-success-text\)/);
  assert.match(rule(".teacher-academic__trust"), /background: var\(--theme-success-bg\)/);
  assert.match(rule(".teacher-academic__group-grid article > span"), /color: var\(--theme-success-text\)/);
  assert.doesNotMatch(css, /data-theme|prefers-color-scheme/);
});

test("teacher retry, loading and focus styles resolve through the same semantic theme", () => {
  for (const selector of [".teacher-academic__days button.is-active", ".teacher-academic-state button"]) {
    assert.match(rule(selector), /color: var\(--theme-action-text\)/);
    assert.match(rule(selector), /background: var\(--theme-action-bg\)/);
  }
  assert.match(rule(".teacher-academic-state--error svg"), /color: var\(--theme-error-text\)/);
  assert.match(rule(".teacher-academic-state i"), /border-top-color: var\(--theme-success-text\)/);
  assert.match(rule(".teacher-academic button:focus-visible"), /outline: 3px solid var\(--theme-success-text\)/);
});

test("teacher brand hero, responsive layout and reduced-motion fallback remain intact", () => {
  assert.match(rule(".teacher-academic__hero"), /linear-gradient\(135deg, #142d32, #1b5854 62%, #317765\)/);
  assert.match(rule(".teacher-academic__hero"), /color: #f8fffb/);
  assert.match(css, /@media \(max-width: 760px\)[\s\S]*grid-template-columns: 1fr/);
  assert.match(css, /@media \(max-width: 560px\)[\s\S]*\.teacher-academic__group-grid\s*\{\s*grid-template-columns: 1fr/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\.teacher-academic-state i\s*\{\s*animation: none/);
});

function luminance(hex) {
  const [r, g, b] = hex.slice(1).match(/../g).map((part) => Number.parseInt(part, 16) / 255)
    .map((value) => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
  return .2126 * r + .7152 * g + .0722 * b;
}

test("teacher operational text, actions, status and focus meet AA contrast in Light and Dark", () => {
  for (const selector of [":root {", ':root[data-theme="dark"] {']) {
    const start = globals.indexOf(selector);
    const block = globals.slice(start, globals.indexOf("}", start));
    const values = Object.fromEntries([...block.matchAll(/(--[\w-]+):\s*(#[\da-f]{6})/g)].map((match) => [match[1], match[2]]));
    for (const [foreground, background] of [
      ["--theme-text", "--theme-card"], ["--theme-text", "--theme-surface"],
      ["--theme-text-soft", "--theme-surface"], ["--theme-muted", "--theme-card"],
      ["--theme-muted", "--theme-surface"], ["--theme-success-text", "--theme-card"],
      ["--theme-success-text", "--theme-success-bg"], ["--theme-action-text", "--theme-action-bg"],
      ["--theme-muted", "--theme-neutral-bg"], ["--theme-error-text", "--theme-card"],
    ]) {
      const [lighter, darker] = [luminance(values[foreground]), luminance(values[background])].sort((a, b) => b - a);
      assert.ok((lighter + .05) / (darker + .05) >= 4.5, `${selector} ${foreground}/${background}`);
    }
  }
});
