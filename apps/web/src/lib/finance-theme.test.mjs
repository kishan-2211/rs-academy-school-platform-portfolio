import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const css = readFileSync(new URL("../app/workspace/fees/finance.css", import.meta.url), "utf8");
const globals = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");
const component = readFileSync(new URL("../components/workspace/finance-workspace.tsx", import.meta.url), "utf8");
const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
function rule(selector) {
  const matching = rules.filter(([, selectors]) => selectors.trim().split(/,\s*/).includes(selector));
  assert.ok(matching.length, `Missing actual ${selector} rule`);
  return matching.map(([, , declarations]) => declarations).join("\n");
}
function variables(selector) {
  const start = globals.indexOf(selector);
  assert.ok(start >= 0, `Missing ${selector}`);
  const block = globals.slice(start, globals.indexOf("}", start));
  return Object.fromEntries([...block.matchAll(/(--[\w-]+):\s*(#[\da-f]{6})/g)]
    .map(([, name, value]) => [name, value]));
}
function luminance(hex) {
  const [r, g, b] = hex.slice(1).match(/../g).map((part) => Number.parseInt(part, 16) / 255)
    .map((value) => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
  return .2126 * r + .7152 * g + .0722 * b;
}

test("the actual closed-account status has an explicit semantic warning surface instead of inheriting fixed finance ink", () => {
  assert.match(component, /selectedAccount\.status !== "ACTIVE" && <div className="finance-banner" role="status">/);
  assert.match(rule(".finance-banner"), /color: var\(--theme-warning-text\)/);
  assert.match(rule(".finance-banner"), /background: var\(--theme-warning-bg\)/);
  assert.match(rule(".finance-banner"), /border: 1px solid var\(--theme-warning-border\)/);
  for (const kind of ["success", "error"]) {
    assert.match(rule(`.finance-banner--${kind}`), new RegExp(`color: var\\(--theme-${kind}-text\\)`));
    assert.match(rule(`.finance-banner--${kind}`), new RegExp(`background: var\\(--theme-${kind}-bg\\)`));
    assert.match(rule(`.finance-banner--${kind}`), new RegExp(`border-color: var\\(--theme-${kind}-border\\)`));
  }
});

test("retired status and standalone loading/error/empty states do not pair fixed light surfaces with inherited dark-theme text", () => {
  assert.match(rule(".finance-status--retired"), /color: var\(--theme-muted\)/);
  assert.match(rule(".finance-status--retired"), /background: var\(--theme-neutral-bg\)/);
  for (const selector of [".finance-empty", ".finance-state"]) {
    assert.match(rule(selector), /color: var\(--theme-muted\)/);
    assert.match(rule(selector), /background: var\(--theme-surface\)/);
    assert.match(rule(selector), /border: 1px dashed var\(--theme-border\)/);
    assert.match(rule(`${selector} svg`), /color: var\(--theme-muted\)/);
  }
  assert.match(rule(".finance-empty h2"), /color: var\(--theme-text\)/);
  assert.match(rule(".finance-state h1"), /color: var\(--theme-text\)/);
  assert.doesNotMatch(css, /data-theme|prefers-color-scheme/);
});

test("fee status and state text pairs meet WCAG AA in both existing themes", () => {
  for (const selector of [":root {", ':root[data-theme="dark"] {']) {
    const tokens = variables(selector);
    for (const [foreground, background] of [
      ["--theme-warning-text", "--theme-warning-bg"],
      ["--theme-success-text", "--theme-success-bg"],
      ["--theme-error-text", "--theme-error-bg"],
      ["--theme-muted", "--theme-neutral-bg"],
      ["--theme-muted", "--theme-surface"],
      ["--theme-text", "--theme-surface"],
    ]) {
      const [lighter, darker] = [luminance(tokens[foreground]), luminance(tokens[background])].sort((a, b) => b - a);
      const ratio = (lighter + .05) / (darker + .05);
      assert.ok(ratio >= 4.5, `${selector} ${foreground} on ${background}: ${ratio.toFixed(2)}:1`);
    }
  }
});
