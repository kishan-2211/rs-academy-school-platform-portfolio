import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { translate } from "./i18n/translate.ts";
import { hindi } from "./i18n/messages.ts";

const read = relative => readFileSync(new URL(relative, import.meta.url), "utf8");
const sources = {
  brand: read("../components/brand.tsx"),
  header: read("../components/site-header.tsx"),
  footer: read("../components/site-footer.tsx"),
  site: read("../content/site.ts"),
  icons: read("../components/icons.tsx"),
};

// Render the real chrome and catalogue. Only framework/media primitives and
// unrelated selector/navigation behavior are bound; this is not browser evidence.
function harness(initialLocale, customSite) {
  const require = createRequire(import.meta.url), cache = new Map(), seen = new Set();
  let locale = initialLocale;
  function text(domain, source, values) {
    seen.add(source);
    if (locale === "hi" && !customSite) {
      assert.equal(typeof (hindi[domain]?.[source] ?? hindi.common[source]), "string", `Missing chrome translation: ${source}`);
    }
    return translate(locale, domain, source, values);
  }
  function compile(name) {
    if (cache.has(name)) return cache.get(name);
    const exports = {}; cache.set(name, exports);
    function bind(module) {
      if (module === "react") return React;
      if (module === "next/link") return { __esModule: true, default: ({ children, onNavigate, ...props }) => {
        void onNavigate;
        return React.createElement("a", props, children);
      } };
      if (module === "next/image") return { __esModule: true, default: ({ priority, ...props }) => {
        void priority;
        return React.createElement("img", props);
      } };
      if (module === "./brand.module.css") return { __esModule: true, default: { inverse: "bound-inverse-style" } };
      if (module === "@/content/site") return customSite ? { site: customSite } : compile("site");
      if (module === "@/components/brand") return compile("brand");
      if (module === "@/components/icons") return compile("icons");
      if (module === "@/components/i18n/language-provider") return {
        useT: domain => (source, values) => text(domain, source, values),
        Text: ({ domain = "common", children, values }) => text(domain, children, values),
      };
      if (module === "@/components/theme-selector") return { ThemeSelector: () => React.createElement("button", { type: "button", "aria-label": translate(locale, "common", "Color theme") }, translate(locale, "common", "System")) };
      if (module === "@/components/i18n/language-selector") return { LanguageSelector: () => React.createElement("button", { type: "button", "aria-label": translate(locale, "common", "Language") }, locale === "hi" ? "हिन्दी" : "English") };
      if (module === "@/lib/mobile-navigation") return { bindMobileNavigation: () => () => {} };
      return require(module);
    }
    const compiled = ts.transpileModule(sources[name], { compilerOptions: {
      target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
      jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
    } }).outputText;
    new Function("require", "exports", compiled)(bind, exports);
    return exports;
  }
  const header = compile("header"), footer = compile("footer"), site = customSite ?? compile("site").site;
  return {
    site, seen,
    choose: next => { locale = next; },
    html: () => renderToStaticMarkup(React.createElement(React.Fragment, null,
      React.createElement(header.SiteHeader), React.createElement(footer.SiteFooter))),
  };
}

test("actual English public chrome retains checked-in school copy and accessible identity", () => {
  const h = harness("en"), html = h.html();
  for (const copy of [h.site.description, h.site.motto, `${h.site.admissionsLabel} are welcome`]) assert.ok(html.includes(copy), copy);
  assert.ok(html.includes(`aria-label="${h.site.schoolName} home"`));
  assert.ok(html.includes(`alt="${h.site.schoolName} emblem"`));
});

test("actual Hindi footer, tagline, announcement and accessible labels use the reviewed catalogue", () => {
  const h = harness("hi"), html = h.html();
  for (const source of [h.site.description, h.site.motto, h.site.admissionsLabel]) {
    assert.ok(h.seen.has(source), "checked-in configured copy must cross the live language boundary");
    assert.ok(html.includes(translate("hi", "public", source)), source);
    assert.ok(!html.includes(source), `English chrome leak: ${source}`);
  }
  assert.ok(html.includes('aria-label="R.S. Academy का मुख्य पृष्ठ"'));
  assert.ok(html.includes('alt="R.S. Academy का प्रतीक चिह्न"'));
  assert.ok(html.includes("सत्र 2026–27 में प्रवेश की जानकारी के लिए संपर्क करें"));
});

test("switching the current language updates the same compiled shared chrome in both directions", () => {
  const h = harness("en"), english = h.html();
  h.choose("hi"); const hindiHtml = h.html();
  assert.ok(hindiHtml.includes("ज्ञान ही शक्ति है"));
  assert.ok(!hindiHtml.includes(h.site.description));
  h.choose("en");
  assert.equal(h.html(), english);
});

test("Hindi chrome preserves official school identity, session value, logo, destinations and markup classes", () => {
  const english = harness("en"), hindiHarness = harness("hi");
  const en = english.html(), hi = hindiHarness.html();
  for (const html of [en, hi]) {
    assert.ok(html.includes(`<strong>${english.site.schoolName}</strong>`));
    assert.ok(html.includes(`src="${english.site.logo}"`));
    assert.ok(html.includes("2026–27"));
    assert.ok(html.includes(`© ${new Date().getFullYear()} ${english.site.schoolName}`));
    assert.ok(html.includes(english.site.platformName));
  }
  const attributes = (html, attribute) => [...html.matchAll(new RegExp(`${attribute}="([^"]*)"`, "g"))].map(match => match[1]);
  assert.deepEqual(attributes(hi, "href"), attributes(en, "href"));
  assert.deepEqual(attributes(hi, "class"), attributes(en, "class"));
  assert.equal(english.site.phone, "+91 98380 02012");
  assert.equal(english.site.email, "thersacademysuwari@gmail.com");
});

test("unrecognized school-authored values remain unchanged and safely escaped instead of being guessed", () => {
  const base = harness("en").site;
  const custom = { ...base, schoolName: "Synthetic <School>", description: "Synthetic description <script>no execution</script>", motto: "Synthetic custom motto", admissionsLabel: "Synthetic intake 2099–2100" };
  const html = harness("hi", custom).html();
  assert.ok(html.includes("Synthetic description &lt;script&gt;no execution&lt;/script&gt;"));
  assert.ok(html.includes("Synthetic custom motto"));
  assert.ok(html.includes("Synthetic intake 2099–2100"));
  assert.ok(html.includes('aria-label="Synthetic &lt;School&gt; का मुख्य पृष्ठ"'));
  assert.ok(!html.includes("सत्र 2026–27"));
});
