import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { translate } from "./i18n/translate.ts";
import { hindi } from "./i18n/messages.ts";
import { publicHomeMessages } from "./i18n/public-home-messages.ts";

const read = relative => readFileSync(new URL(relative, import.meta.url), "utf8");
const pageSource = read("../app/page.tsx"), orbitSource = read("../components/orbit-visual.tsx");

// Render the actual page, actual illustration and actual catalogue; only the
// framework link/reveal and context boundary are bound for a pure SSR unit test.
function render(locale) {
  const require = createRequire(import.meta.url), seen = new Set(), cache = new Map();
  function compile(name, source) {
    if (cache.has(name)) return cache.get(name);
    const exports = {}; cache.set(name, exports);
    const bind = module => module === "react" ? React : module === "next/link" ? {
      __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children),
    } : module === "@/components/reveal" ? {
      Reveal: ({ children, className }) => React.createElement("div", { className }, children),
    } : module === "@/components/i18n/language-provider" ? {
      Text: ({ domain = "common", children, values }) => {
        assert.equal(typeof children, "string"); seen.add(children);
        if (locale === "hi") assert.equal(typeof hindi[domain]?.[children], "string", `Missing live translation: ${children}`);
        return translate(locale, domain, children, values);
      },
    } : module === "@/components/icons" ? compile("icons", read("../components/icons.tsx"))
      : module === "@/components/orbit-visual" ? compile("orbit", orbitSource) : require(module);
    const compiled = ts.transpileModule(source, { compilerOptions: {
      target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX,
    } }).outputText;
    new Function("require", "exports", compiled)(bind, exports);
    return exports;
  }
  const page = compile("page", pageSource);
  return { html: renderToStaticMarkup(React.createElement(page.default)), seen };
}

test("actual homepage and illustrative visual render Hindi throughout without untranslated product copy", () => {
  const { html, seen } = render("hi");
  assert.ok(seen.size >= 100, "all homepage sections and the visual must opt into the existing language boundary");
  assert.match(html, /<h1>ज्ञान बनता है<br\/><em>शक्ति।<\/em><\/h1>/);
  for (const copy of ["परिवार देखें सीखने का सफ़र,", "सीखने के चरण", "आपके बच्चे का अगला कदम", "स्कूल का वास्तविक डेटा नहीं", "केवल उदाहरण की प्रोफ़ाइल"]) assert.ok(html.includes(copy), copy);
  for (const english of ["Knowledge becomes", "Foundations that last", "Rooted in community", "Ready for tomorrow", "Science studio", "Class timetable", "Nursery–Class II", "Your child&#x27;s next chapter"]) assert.ok(!html.includes(english), english);
  for (const originalValue of ["07:45", "12:30", "2026–27", "10:30"]) assert.ok(html.includes(originalValue), originalValue);
});

test("English rendering preserves original emphasis, destinations, illustrative warnings and layout classes", () => {
  const { html } = render("en");
  assert.match(html, /<h1>Knowledge becomes<br\/><em>strength\.<\/em><\/h1>/);
  for (const copy of ["Demonstration profile", "Not live school data", "school office will confirm the final published timetable"]) assert.ok(html.includes(copy), copy);
  for (const path of ["/admissions", "/admissions/visit", "/academics#foundational", "/academics#middle", "/academics#senior", "/portal"]) assert.ok(html.includes(`href="${path}"`), path);
  const classLists = [...html.matchAll(/class="([^"]*)"/g)].map(match => match[1].split(/\s+/));
  for (const className of ["hero section-dark", "hero__visual", "pillars__grid", "day__timeline", "connection__grid", "learning__cards", "invitation section-coral", "orbit__ring orbit__ring--three"]) assert.ok(classLists.some(tokens => className.split(" ").every(token => tokens.includes(token))), className);
});

test("icon-only learning links retain translated accessible names without changing their visual design", () => {
  const { html } = render("hi");
  const iconLinks = [...html.matchAll(/<a href="\/academics(?:#[^"]*)?"><span class="sr-only">([^<]+)<\/span><svg/g)];
  assert.equal(iconLinks.length, 6);
  for (const [, name] of iconLinks) assert.match(name, /[\u0900-\u097f]/);
  assert.match(html, /role="group" aria-labelledby="homepage-platform-preview-caption"/);
  assert.match(html, /id="homepage-platform-preview-caption">प्लेटफ़ॉर्म का उदाहरण — स्कूल का वास्तविक डेटा नहीं<\/span>/);
});

test("localization keeps the page and illustration server-renderable and introduces no new data or motion dependency", () => {
  for (const source of [pageSource, orbitSource]) {
    assert.doesNotMatch(source, /["']use client["']|useEffect|useState|fetch\(|dangerouslySetInnerHTML|localStorage|sessionStorage|process\.env|WebGL/);
    assert.match(source, /import \{ Text \} from "@\/components\/i18n\/language-provider"/);
  }
  for (const [key, value] of Object.entries(publicHomeMessages)) {
    assert.ok(value.trim()); assert.match(value, /[\u0900-\u097f]/, key);
    assert.deepEqual([...key.matchAll(/\{\w+\}/g)].map(x => x[0]), [...value.matchAll(/\{\w+\}/g)].map(x => x[0]));
  }
});

test("Hindi public display leading accommodates vowel marks without changing English or operational typography", () => {
  const css = read("../components/i18n/language.css");
  assert.match(css, /html:lang\(hi\) :is\(\.hero h1, \.intro__statement h2, \.day__heading h2, \.connection__copy h2, \.learning__heading h2, \.invitation__inner h2\)\s*\{\s*line-height: 1\.25;\s*letter-spacing: normal;/);
  assert.match(css, /html:lang\(hi\) \.orbit__core > strong \{ line-height: 1\.2; letter-spacing: normal; \}/);
  const displayRules = css.slice(css.indexOf("/* Devanagari vowel marks"), css.indexOf(".workspace-topbar__actions { flex-wrap: wrap; }"));
  assert.doesNotMatch(displayRules, /font-size|color:|background:|animation|\.workspace|\.family|\.student/);
});
