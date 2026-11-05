import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const css = readFileSync(new URL("../app/workspace/attendance/attendance.css", import.meta.url), "utf8");
const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
function rule(selector) {
  const matches = rules.filter((entry) => entry[1].trim().split(/,\s*/).includes(selector));
  assert.ok(matches.length, `Missing actual ${selector} rule`);
  return matches.map((entry) => entry[2]).join("\n");
}

test("attendance ordinary cards, register rows and empty surfaces use the shared theme", () => {
  assert.match(rule(".attendance-workspace"), /--attendance-ink: var\(--theme-text\)/);
  assert.match(rule(".attendance-workspace"), /--attendance-mint: var\(--theme-success-bg\)/);
  for (const selector of [".attendance-register-picker", ".attendance-sheet", ".attendance-panel", ".attendance-tabs"])
    assert.match(rule(selector), /background: var\(--theme-card\)/);
  for (const selector of [".attendance-sheet > header", ".attendance-correction-form", ".attendance-actions", ".attendance-empty--small"])
    assert.match(rule(selector), /background: var\(--theme-surface\)/);
  assert.match(rule(".attendance-learners article.has-exception"), /background: var\(--theme-warning-bg\)/);
  assert.match(rule(".attendance-roll"), /background: var\(--theme-neutral-bg\)/);
  assert.match(rule(".attendance-sheet"), /box-shadow: var\(--theme-shadow-soft\)/);
});

test("attendance forms, metadata, review actions and visible focus use readable semantic colors", () => {
  for (const selector of [".attendance-form input", ".attendance-learners select", ".attendance-review-card input", ".attendance-leave-review textarea", ".attendance-policy-switcher select"]) {
    assert.match(rule(selector), /background: var\(--theme-input\)/);
    assert.match(rule(selector), /border: 1px solid var\(--theme-border\)/);
  }
  for (const selector of [".attendance-form label > span", ".attendance-learners small", ".attendance-review-card small", ".attendance-empty p"])
    assert.match(rule(selector), /color: var\(--theme-muted\)/);
  for (const selector of [".attendance-tabs button.is-active", ".attendance-actions button", ".attendance-review-card footer button", ".attendance-correction-form button"]) {
    assert.match(rule(selector), /color: var\(--theme-action-text\)/);
    assert.match(rule(selector), /background: var\(--theme-action-bg\)/);
  }
  assert.match(rule(".attendance-workspace :focus-visible"), /outline: 3px solid var\(--theme-success-text\)/);
  assert.match(rule(".attendance-correction-open"), /color: var\(--theme-success-text\)/);
  assert.match(rule(".attendance-correction-open"), /grid-column: 2 \/ -1/);
  assert.match(rule(".attendance-correction-open"), /min-height: 2rem/);
});

test("attendance success, error, draft and locked indicators preserve meaning in both themes", () => {
  assert.match(rule(".attendance-message"), /color: var\(--theme-success-text\)/);
  assert.match(rule(".attendance-message"), /background: var\(--theme-success-bg\)/);
  assert.match(rule(".attendance-message.is-error"), /color: var\(--theme-error-text\)/);
  assert.match(rule(".attendance-message.is-error"), /background: var\(--theme-error-bg\)/);
  assert.match(rule(".attendance-status--draft"), /color: var\(--theme-warning-text\)/);
  assert.match(rule(".attendance-status--draft"), /background: var\(--theme-warning-bg\)/);
  assert.match(rule(".attendance-status--locked"), /color: var\(--theme-success-text\)/);
  assert.match(rule(".attendance-status--locked"), /background: var\(--attendance-mint\)/);
  assert.doesNotMatch(css, /data-theme|prefers-color-scheme/);
});

test("attendance fixed branded hero/accent, mobile structure and reduced-motion behavior remain", () => {
  assert.match(rule(".attendance-hero"), /color: var\(--public-ink\)/);
  assert.match(rule(".attendance-hero"), /linear-gradient\(125deg, #e8fff6, #f9f7ee 72%\)/);
  assert.match(rule(".attendance-bulk button"), /color: var\(--public-ink\)/);
  assert.match(rule(".attendance-bulk button"), /background: var\(--attendance-lime\)/);
  assert.match(css, /@media \(max-width: 980px\)[\s\S]*grid-template-columns: 1fr/);
  assert.match(css, /@media \(max-width: 680px\)[\s\S]*\.attendance-correction-form\s*\{[\s\S]*?grid-template-columns: 1fr/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\.attendance-loading i\s*\{\s*animation: none/);
});
