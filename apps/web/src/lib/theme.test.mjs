import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";
import {
  applyTheme,
  persistThemePreference,
  readThemePreference,
  resolveTheme,
  THEME_BOOTSTRAP_SCRIPT,
  THEME_STORAGE_KEY,
  themeOptionSemantics,
  watchSystemTheme,
} from "./theme.ts";

const GLOBAL_STYLES = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");

function relativeLuminance(hex) {
  const channels = hex
    .slice(1)
    .match(/.{2}/g)
    .map((channel) => Number.parseInt(channel, 16) / 255)
    .map((channel) =>
      channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
    );
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

function contrastRatio(foreground, background) {
  const values = [relativeLuminance(foreground), relativeLuminance(background)].sort(
    (left, right) => right - left,
  );
  return (values[0] + 0.05) / (values[1] + 0.05);
}

function themeVariables(selector) {
  const selectorStart = GLOBAL_STYLES.indexOf(selector);
  const blockStart = GLOBAL_STYLES.indexOf("{", selectorStart);
  const blockEnd = GLOBAL_STYLES.indexOf("}", blockStart);
  assert.ok(
    selectorStart >= 0 && blockStart > selectorStart && blockEnd > blockStart,
    `Missing ${selector} theme tokens`,
  );
  const block = GLOBAL_STYLES.slice(blockStart + 1, blockEnd);
  return Object.fromEntries(
    [...block.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6})/gi)].map((match) => [match[1], match[2]]),
  );
}

function runBootstrap({ savedPreference, systemDark, storageThrows = false }) {
  let themeColor = "";
  const documentElement = { dataset: {}, style: {} };
  vm.runInNewContext(THEME_BOOTSTRAP_SCRIPT, {
    localStorage: {
      getItem: () => {
        if (storageThrows) throw new Error("storage blocked");
        return savedPreference ?? null;
      },
    },
    matchMedia: () => ({ matches: systemDark }),
    document: {
      documentElement,
      querySelector: () => ({ setAttribute: (_name, value) => { themeColor = value; } }),
    },
  });
  return { documentElement, themeColor };
}

test("defaults to System and persists every valid preference", () => {
  const values = new Map();
  const storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };
  assert.equal(readThemePreference(storage), "system");
  persistThemePreference(storage, "dark");
  assert.equal(values.get(THEME_STORAGE_KEY), "dark");
  assert.equal(readThemePreference(storage), "dark");
  values.set(THEME_STORAGE_KEY, "unknown");
  assert.equal(readThemePreference(storage), "system");
});

test("resolves System from the operating-system preference", () => {
  assert.equal(resolveTheme("system", true), "dark");
  assert.equal(resolveTheme("system", false), "light");
  assert.equal(resolveTheme("light", true), "light");
  assert.equal(resolveTheme("dark", false), "dark");
});

test("pre-paint bootstrap defaults to System even when storage is unavailable", () => {
  const { documentElement, themeColor } = runBootstrap({ systemDark: true, storageThrows: true });
  assert.equal(documentElement.dataset.themePreference, "system");
  assert.equal(documentElement.dataset.theme, "dark");
  assert.equal(documentElement.style.colorScheme, "dark");
  assert.equal(themeColor, "#17130f");
});

test("pre-paint bootstrap honors a saved preference over the operating system", () => {
  const { documentElement, themeColor } = runBootstrap({ savedPreference: "light", systemDark: true });
  assert.equal(documentElement.dataset.themePreference, "light");
  assert.equal(documentElement.dataset.theme, "light");
  assert.equal(themeColor, "#f7f3ea");
});

test("pre-paint bootstrap restores Light, Dark, and System preferences on reload", () => {
  const scenarios = [
    { savedPreference: "light", systemDark: true, resolved: "light", themeColor: "#f7f3ea" },
    { savedPreference: "dark", systemDark: false, resolved: "dark", themeColor: "#17130f" },
    { savedPreference: "system", systemDark: true, resolved: "dark", themeColor: "#17130f" },
  ];

  for (const scenario of scenarios) {
    const { documentElement, themeColor } = runBootstrap(scenario);
    assert.equal(documentElement.dataset.themePreference, scenario.savedPreference);
    assert.equal(documentElement.dataset.theme, scenario.resolved);
    assert.equal(themeColor, scenario.themeColor);
  }
});

test("applies the resolved theme and matching browser theme color", () => {
  let themeColor = "";
  const documentTarget = {
    documentElement: { dataset: {}, style: {} },
    querySelector: () => ({ setAttribute: (_name, value) => { themeColor = value; } }),
  };
  assert.equal(applyTheme(documentTarget, "system", true), "dark");
  assert.equal(documentTarget.documentElement.dataset.theme, "dark");
  assert.equal(documentTarget.documentElement.dataset.themePreference, "system");
  assert.equal(documentTarget.documentElement.style.colorScheme, "dark");
  assert.equal(themeColor, "#17130f");
});

test("reacts to OS changes only while System is selected", () => {
  let listener;
  let preference = "system";
  const changes = [];
  const media = {
    addEventListener: (_type, callback) => { listener = callback; },
    removeEventListener: (_type, callback) => { assert.equal(callback, listener); },
  };
  const stop = watchSystemTheme(media, () => preference, (dark) => changes.push(dark));
  listener({ matches: true });
  preference = "light";
  listener({ matches: false });
  assert.deepEqual(changes, [true]);
  stop();
});

test("selector state exposes exactly one checked and selected radio", () => {
  const states = ["light", "dark", "system"].map((value) =>
    themeOptionSemantics("dark", value),
  );
  assert.deepEqual(states.map((state) => state.checked), [false, true, false]);
  assert.deepEqual(states.map((state) => state.ariaChecked), [false, true, false]);
});

test("semantic text and status tokens maintain WCAG AA contrast in both themes", () => {
  const light = themeVariables(":root");
  const dark = { ...light, ...themeVariables(':root[data-theme="dark"]') };
  const pairs = [
    ["theme-text", "theme-bg"],
    ["theme-muted", "theme-bg"],
    ["theme-faint", "theme-bg"],
    ["theme-text", "theme-card"],
    ["theme-muted", "theme-card"],
    ["theme-faint", "theme-card"],
    ["theme-text", "theme-input"],
    ["theme-text", "theme-bg-soft"],
    ["theme-muted", "theme-neutral-bg"],
    ["theme-muted", "theme-success-bg"],
    ["theme-success-text", "theme-card"],
    ["theme-success-text", "theme-success-bg"],
    ["theme-warning-text", "theme-warning-bg"],
    ["theme-error-text", "theme-error-bg"],
    ["theme-info-text", "theme-info-bg"],
    ["theme-action-text", "theme-action-bg"],
    ["theme-action-text", "theme-action-hover"],
    ["public-ink", "cream"],
    ["public-muted", "cream"],
    ["public-accent-text", "cream"],
    ["public-ink", "coral"],
  ];

  for (const [themeName, variables] of [["light", light], ["dark", dark]]) {
    for (const [foreground, background] of pairs) {
      const ratio = contrastRatio(variables[foreground], variables[background]);
      assert.ok(
        ratio >= 4.5,
        `${themeName} ${foreground} on ${background} is ${ratio.toFixed(2)}:1`,
      );
    }
  }
});

test("form control boundaries retain non-text AA contrast in Light, Dark, and theme transitions", () => {
  const inputRule = [...GLOBAL_STYLES.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .find(([, selectors]) => selectors.trim().split(/,\s*/).includes(".login-form input"))?.[2];
  assert.ok(inputRule, "Use the actual shared contact/login input rule");
  assert.match(inputRule, /border:\s*1px solid var\(--theme-control-border\)/);
  const passwordStyles = readFileSync(new URL("../components/login-password.module.css", import.meta.url), "utf8");
  assert.match(passwordStyles.match(/\.toggle\s*\{([^{}]*)\}/)?.[1] ?? "", /border:\s*1px solid var\(--theme-control-border\)/);

  const light = themeVariables(":root");
  const dark = { ...light, ...themeVariables(':root[data-theme="dark"]') };
  for (const [name, variables] of [["Light", light], ["Dark", dark]]) {
    assert.match(variables["theme-control-border"], /^#[0-9a-f]{6}$/i, "Control borders must be opaque");
    for (const border of [light["theme-control-border"], dark["theme-control-border"]]) {
      for (const adjacent of [
        "theme-input", "theme-surface", "theme-card", "theme-elevated",
        "theme-bg", "theme-bg-soft", "theme-hover",
      ]) {
        // Both endpoint colors must pass, including the existing border
        // transition after a theme switch changes the adjacent surface.
        const ratio = contrastRatio(border, variables[adjacent]);
        assert.ok(ratio >= 3, `${name} control boundary on ${adjacent}: ${ratio.toFixed(2)}:1`);
      }
    }
  }
});

test("assessment operating panels use shared theme colors, with a fixed branded hero", () => {
  const styles = readFileSync(
    new URL("../app/workspace/assessment/assessment.css", import.meta.url),
    "utf8",
  );
  const operationalRules = [...styles.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter(
    ([, selector]) => !selector.includes(".assessment-hero"),
  );
  for (const [, selector, declarations] of operationalRules) {
    assert.doesNotMatch(
      declarations,
      /(?:color|background|border(?:-[a-z]+)?):[^;]*(?:#[\da-f]{3,8}|rgba?\()/i,
      `${selector.trim()} must reuse semantic colors instead of a fixed light surface`,
    );
  }
  assert.match(styles, /\.assessment-hero\s*\{[^}]*#102d2b/s);
  assert.match(styles, /prefers-reduced-motion: reduce/);
});

test("student published-result labels and scores use readable semantic colors in both themes", () => {
  const styles = readFileSync(
    new URL("../components/student/student-portal.module.css", import.meta.url),
    "utf8",
  );
  const light = themeVariables(":root");
  const dark = { ...light, ...themeVariables(':root[data-theme="dark"]') };
  const rules = [
    [".resultGrid header span", "theme-muted", "theme-card"],
    [".resultGrid header > strong", "theme-success-text", "theme-card"],
    [".resultGrid article > div small", "theme-success-text", "theme-bg-soft"],
  ];

  for (const [selector, foreground, background] of rules) {
    const declarations = [...styles.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
      .find(([, candidate]) => candidate.trim() === selector)?.[2];
    assert.ok(declarations, `Missing ${selector} rule`);
    assert.match(declarations, new RegExp(`color:\\s*var\\(--${foreground}\\)`));
    assert.doesNotMatch(declarations, /color:\s*#[\da-f]+/i);
    for (const [name, variables] of [["light", light], ["dark", dark]]) {
      const ratio = contrastRatio(variables[foreground], variables[background]);
      assert.ok(ratio >= 4.5, `${name} ${selector} contrast is ${ratio.toFixed(2)}:1`);
    }
  }
});

test("calendar operating panels reuse semantic themes while retaining intentional dark brand sections", () => {
  const styles = readFileSync(
    new URL("../app/workspace/calendar/school-calendar.css", import.meta.url),
    "utf8",
  );
  const fixedBrandSections = /\.calendar-hero|\.calendar-policy-summary|\.calendar-form footer|\.calendar-next/;
  for (const [, selector, declarations] of styles.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (fixedBrandSections.test(selector)) continue;
    assert.doesNotMatch(
      declarations,
      /(?:color|background|border(?:-[a-z]+)?):[^;]*(?:#[\da-f]{3,8}|rgba?\()/i,
      `${selector.trim()} must not retain fixed Light-only colors`,
    );
  }
  assert.match(styles, /\.calendar-hero\s*\{[^}]*#102f2b/s);
  assert.match(styles, /\.calendar-next\s*\{[^}]*position:\s*static/);
  assert.match(styles, /prefers-reduced-motion\s*:\s*reduce/);
  assert.match(styles, /:focus-visible\s*\{[^}]*outline:\s*3px solid var\(--theme-success-text\)/);
  const light = themeVariables(":root");
  const dark = { ...light, ...themeVariables(':root[data-theme="dark"]') };
  for (const [name, variables] of [["light", light], ["dark", dark]]) {
    for (const background of ["theme-card", "theme-bg-soft", "theme-input"]) {
      const ratio = contrastRatio(variables["theme-success-text"], variables[background]);
      assert.ok(ratio >= 3, `${name} calendar focus on ${background} is ${ratio.toFixed(2)}:1`);
    }
  }
});

const ROLLOVER_STYLES = ["rollover.css", "rollover-detail.css", "rollover-responsive.css"]
  .map((file) =>
    readFileSync(new URL(`../app/workspace/rollover/${file}`, import.meta.url), "utf8"),
  )
  .join("\n");

test("rollover operating surfaces and status states use semantic colors in both themes", () => {
  for (const [, selector, declarations] of ROLLOVER_STYLES.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (selector.includes(".rollover-hero")) continue;
    assert.doesNotMatch(
      declarations,
      /(?:^|;)\s*(?:color|background|border(?:-[a-z]+)?|outline|box-shadow):[^;]*(?:#[\da-f]{3,8}|rgba?\()/i,
      `${selector.trim()} must not retain a fixed Light-only operational color`,
    );
  }
  assert.match(ROLLOVER_STYLES, /\.rollover-hero\s*\{[^}]*#12282e/s);
  assert.match(ROLLOVER_STYLES, /\.rollover-review\s*\{[^}]*background:\s*var\(--theme-bg-soft\)/);
  assert.match(
    ROLLOVER_STYLES,
    /prefers-reduced-motion:\s*reduce[^}]*\.rollover-progress i\s*\{\s*transition:\s*none/s,
  );
});

test("rollover step labels and inverse controls retain AA text and visible focus contrast", () => {
  const fixed = Object.fromEntries(
    [...ROLLOVER_STYLES.matchAll(/--(rollover-[\w-]+):\s*(#[0-9a-f]{6})/gi)].map((match) => [
      match[1],
      match[2],
    ]),
  );
  const light = { ...themeVariables(":root"), ...fixed };
  const dark = { ...light, ...themeVariables(':root[data-theme="dark"]') };
  const textPairs = [
    ["theme-success-text", "theme-card"],
    ["theme-success-text", "theme-bg-soft"],
    ["theme-muted", "theme-bg-soft"],
    ["rollover-action-text", "rollover-action-bg"],
    ["rollover-confirm-text", "rollover-confirm-bg"],
    ...[
      "rollover-bulk-bg",
      "rollover-preview-bg",
      "rollover-execute-bg",
      "rollover-inverse-input",
      "rollover-inverse-focus",
    ].flatMap((background) => [
      ["rollover-inverse-text", background],
      ["rollover-inverse-muted", background],
    ]),
  ];
  for (const [name, variables] of [
    ["light", light],
    ["dark", dark],
  ]) {
    for (const [foreground, background] of textPairs) {
      const ratio = contrastRatio(variables[foreground], variables[background]);
      assert.ok(ratio >= 4.5, `${name} ${foreground} on ${background}: ${ratio.toFixed(2)}:1`);
    }
    for (const background of [
      "rollover-bulk-bg",
      "rollover-preview-bg",
      "rollover-execute-bg",
      "rollover-inverse-focus",
    ]) {
      assert.ok(contrastRatio(variables.yellow, variables[background]) >= 3);
    }
    assert.ok(
      contrastRatio(fixed["rollover-inverse-border"], fixed["rollover-inverse-input"]) >= 3,
    );
    assert.ok(contrastRatio(variables["theme-muted"], variables["theme-input"]) >= 3);
  }
});

test("rollover focused inverse inputs and native option menus remain readable without theme overrides", () => {
  const focused = ROLLOVER_STYLES.match(/\.rollover-bulk input:focus,([^{}]+)\{([^{}]*)\}/)?.[2];
  assert.ok(focused, "Missing inverse focus rule");
  assert.match(focused, /background:\s*var\(--rollover-inverse-focus\)/);
  assert.match(focused, /color:\s*var\(--rollover-inverse-text\)/);
  assert.match(
    ROLLOVER_STYLES,
    /\.rollover-bulk option\s*\{\s*background:\s*var\(--theme-input\);\s*color:\s*var\(--theme-text\)/,
  );
  const generalFocus = ROLLOVER_STYLES.indexOf(".rollover-workspace button:focus-visible");
  const inverseFocus = ROLLOVER_STYLES.indexOf(".rollover-bulk button:focus-visible");
  assert.ok(
    inverseFocus > generalFocus,
    "Inverse focus must win the route's stylesheet import order",
  );
  assert.match(ROLLOVER_STYLES.slice(inverseFocus), /outline:\s*3px solid var\(--yellow\)/);
});

test("transport section headings and standalone states use readable semantic colors without recoloring fixed light cards", () => {
  const css = readFileSync(new URL("../app/workspace/transport/transport.css", import.meta.url), "utf8");
  const rule = (selector) => {
    const start = css.indexOf(`${selector} {`);
    assert.ok(start >= 0, `Missing ${selector}`);
    return css.slice(start, css.indexOf("}", start) + 1);
  };
  assert.match(rule(".transport-section > header h2"), /color: var\(--theme-text\)/);
  for (const selector of [".transport-section > header span", ".transport-section > header p", ".transport-state", ".transport-state svg"]) {
    assert.match(rule(selector), /color: var\(--theme-muted\)/);
  }
  assert.match(rule(".transport-state"), /background: var\(--theme-surface\)/);
  assert.match(rule(".transport-state"), /border: 1px dashed var\(--theme-border\)/);
  assert.match(rule(".transport-state h1"), /color: var\(--theme-text\)/);
  assert.doesNotMatch(rule(".transport-state") + rule(".transport-state h1"), /var\(--t-(ink|muted)\)/);
  assert.match(rule(".transport-workspace"), /--t-ink: #102f3b/);
  assert.match(rule(".transport-card"), /background: #fff/);
  const light = themeVariables(":root");
  const dark = { ...light, ...themeVariables(':root[data-theme="dark"]') };
  for (const [name, tokens] of [["light", light], ["dark", dark]]) {
    for (const background of ["theme-bg", "theme-surface"]) {
      for (const foreground of ["theme-text", "theme-muted"]) {
        assert.ok(contrastRatio(tokens[foreground], tokens[background]) >= 4.5, `${name} ${foreground} on ${background}`);
      }
    }
  }
});
