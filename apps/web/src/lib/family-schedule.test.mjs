import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { hindi } from "./i18n/messages.ts";

// Render the actual existing timetable section, without mocking portal API data
// or needing unrelated portal widgets in this focused state regression.
const source = ts.createSourceFile("family-portal.tsx", readFileSync(
  new URL("../components/family/family-portal.tsx", import.meta.url), "utf8"),
ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let section;
function visit(node) {
  if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some(
    (property) => ts.isJsxAttribute(property) && property.name.getText(source) === "id"
      && property.initializer?.getText(source) === '"family-schedule"',
  )) section = node;
  ts.forEachChild(node, visit);
}
visit(source);
assert.ok(section, "The real family timetable section exists");
const compiled = ts.transpileModule(`function sectionView(selected,schedule,scheduleLoading,scheduleError,t,weekDays) { return ${section.getText(source)}; }`, {
  compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 },
}).outputText;
const view = new Function("React", `${compiled}\nreturn sectionView;`)(React);
function render({ sectionName = null, schedule = { entries: [] }, loading = false, error = "", locale = "en" } = {}) {
  return renderToStaticMarkup(view({ sectionName }, schedule, loading, error,
    (message) => locale === "hi" ? hindi.parent[message] || message : message, ["Monday"]));
}

test("own-child pending placement is an explanatory empty state in both languages", () => {
  for (const locale of ["en", "hi"]) {
    const html = render({ locale });
    const heading = "Waiting for section placement";
    const message = "Your child's timetable will appear after the school assigns a section and publishes it.";
    assert.ok(html.includes(locale === "hi" ? hindi.parent[heading] : heading));
    const renderedMessage = renderToStaticMarkup(React.createElement(React.Fragment, null,
      locale === "hi" ? hindi.parent[message] : message));
    assert.ok(html.includes(renderedMessage));
    assert.doesNotMatch(html, /role="alert"/);
  }
});

test("placed learners retain the distinct waiting-for-publication state", () => {
  const html = render({ sectionName: "Synthetic A" });
  assert.match(html, /Waiting for publication/);
  assert.match(html, /The school has not published a timetable for this class yet/);
  assert.doesNotMatch(html, /Waiting for section placement/);
});

test("loading and authorization errors take precedence over pending placement", () => {
  assert.match(render({ loading: true }), /aria-live="polite"/);
  assert.match(render({ error: "This learner is not available to your verified relationship." }), /role="alert"/);
  for (const html of [render({ loading: true }), render({ error: "Unauthorized synthetic relationship" })]) {
    assert.doesNotMatch(html, /Your child&#x27;s timetable will appear/);
  }
});

test("published entries continue rendering the school snapshot without invented data", () => {
  const html = render({ sectionName: "Synthetic A", schedule: { timetableName: "Synthetic approved week", entries: [{
    id: "synthetic-entry", dayOfWeek: 1, startsAt: "08:00:00", subjectShortName: "Maths",
    subjectName: "Synthetic Mathematics", periodName: "Period 1", roomLabel: "Synthetic room",
  }] } });
  for (const text of ["Synthetic approved week", "Monday", "08:00", "Maths", "Period 1", "Synthetic room"]) {
    assert.ok(html.includes(text));
  }
  assert.doesNotMatch(html, /timetable will appear|Waiting for section placement/);
});
