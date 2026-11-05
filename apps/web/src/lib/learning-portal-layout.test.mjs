import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const css = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");
const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
function rule(selector) {
  const found = rules.filter((entry) => entry[1].trim().split(/,\s*/).includes(selector));
  assert.ok(found.length, `Missing actual ${selector}`);
  return found.map((entry) => entry[2]).join("\n");
}

// These CSS guards supplement real phone rendering; they do not simulate layout.
test("family assignment cards and their internal grid can shrink below long-title min-content width", () => {
  assert.match(rule(".family-assignments__list > article"), /min-width:\s*0(?:px)?\s*;/);
  assert.match(rule(".family-assignments__list > article"), /grid-template-columns:\s*minmax\(0,\s*1fr\)\s*;/);
});

test("the assignment title's flex child shrinks and preserves unbroken text by wrapping", () => {
  assert.match(rule(".family-assignments__title > div"), /min-width:\s*0(?:px)?\s*;/);
  assert.match(rule(".family-assignments__title h3"), /overflow-wrap:\s*anywhere\s*;/);
});

test("editable school instructions and feedback wrap long words without clipping their content", () => {
  assert.match(rule(".family-assignments__instructions"), /overflow-wrap:\s*anywhere\s*;/);
  assert.match(rule(".family-assignments__instructions"), /white-space:\s*pre-wrap\s*;/);
  assert.match(rule(".family-assignments__feedback p"), /overflow-wrap:\s*anywhere\s*;/);
});

test("long subject names and status badges wrap within the assignment card header", () => {
  assert.match(rule(".family-assignments__list article > header"), /flex-wrap:\s*wrap\s*;/);
  assert.match(rule(".family-assignments__list article > header span"), /min-width:\s*0(?:px)?\s*;/);
  assert.match(rule(".family-assignments__list article > header span"), /overflow-wrap:\s*anywhere\s*;/);
});

const studentCss = readFileSync(new URL("../components/student/student-portal.module.css", import.meta.url), "utf8");
const studentRules = [...studentCss.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
function studentRule(selector) {
  const found = studentRules.filter((entry) => entry[1].trim().split(/,\s*/).includes(selector));
  assert.ok(found.length, `Missing actual ${selector}`);
  return found.map((entry) => entry[2]).join("\n");
}

test("student assignment grid and cards shrink instead of expanding the phone viewport", () => {
  assert.match(studentRule(".assignmentList"), /grid-template-columns:\s*minmax\(0,\s*1fr\)\s*;/);
  assert.match(studentRule(".assignmentCard"), /min-width:\s*0(?:px)?\s*;/);
});

test("student assignment title and subject wrap inside a shrinking header", () => {
  assert.match(studentRule(".assignmentCard > header > div"), /min-width:\s*0(?:px)?\s*;/);
  assert.match(studentRule(".assignmentCard > header span"), /overflow-wrap:\s*anywhere\s*;/);
  assert.match(studentRule(".assignmentCard h3"), /overflow-wrap:\s*anywhere\s*;/);
});

test("student instructions and feedback retain line breaks while wrapping long words", () => {
  assert.match(studentRule(".instructions"), /white-space:\s*pre-wrap\s*;/);
  assert.match(studentRule(".instructions"), /overflow-wrap:\s*anywhere\s*;/);
  assert.match(studentRule(".feedback p"), /overflow-wrap:\s*anywhere\s*;/);
});

test("student response fields and their grid tracks can shrink without clipping inputs", () => {
  assert.match(studentRule(".submissionEditor"), /grid-template-columns:\s*minmax\(0,\s*1\.5fr\)\s+minmax\(0,\s*0\.85fr\)\s*;/);
  assert.match(studentRule(".submissionEditor > label"), /min-width:\s*0(?:px)?\s*;/);
  assert.match(studentCss, /@media \(max-width: 780px\)[\s\S]*\.submissionEditor \{ grid-template-columns: 1fr; \}/);
});
