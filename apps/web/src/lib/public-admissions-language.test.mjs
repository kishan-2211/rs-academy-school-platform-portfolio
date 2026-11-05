import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { hindi } from "./i18n/messages.ts";
import { translate } from "./i18n/translate.ts";

const read = relative => readFileSync(new URL(relative, import.meta.url), "utf8");
const pageSource = read("../components/public-inner-page.tsx");
const require = createRequire(import.meta.url);
function compile(source, bind = require) {
  const exports = {};
  const compiled = ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX,
  } }).outputText;
  new Function("require", "exports", compiled)(bind, exports);
  return exports;
}
const { publicPages } = compile(read("../content/public-pages.ts"));
const icons = compile(read("../components/icons.tsx"));
function render(locale, slug = "admissions", afterSections) {
  const seen = new Set();
  const { PublicInnerPage } = compile(pageSource, module => {
    if (module === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
    if (module === "@/components/icons") return icons;
    if (module === "@/components/reveal") return { Reveal: ({ children, className }) => React.createElement("div", { className }, children) };
    if (module === "@/components/i18n/language-provider") return {
      Text: ({ domain = "common", children }) => {
        assert.equal(typeof children, "string");
        seen.add(children);
        if (locale === "hi" && slug === "admissions") assert.equal(typeof hindi[domain]?.[children], "string", `Missing admission translation: ${children}`);
        return translate(locale, domain, children);
      },
    };
    return require(module);
  });
  return { html: renderToStaticMarkup(React.createElement(PublicInnerPage, { page: publicPages[slug], afterSections })), seen };
}

test("actual admissions body and shared next-step links render Hindi rather than English fallback", () => {
  const { html, seen } = render("hi");
  const page = publicPages.admissions;
  const expected = new Set([page.eyebrow, page.title, page.accent, page.introduction,
    ...page.sections.flatMap(section => [section.label, section.title, section.body]),
    "Your next step", "See the school for yourself.",
    "Come with questions. Leave with a clearer sense of the people, place, and possibilities.", "Book a visit", "Ask a question"]);
  assert.deepEqual(seen, expected, "all actual hero, process and next-step copy must use the existing live language boundary");
  assert.match(html, /<h1>आपके अगले कदम का<br\/><em>स्पष्ट रास्ता।<\/em><\/h1>/);
  for (const phrase of ["स्कूल को समझें", "हमसे मिलें और बात करें", "एक निर्देशित आवेदन पूरा करें", "प्रवेश का प्रस्ताव नहीं", "स्कूल आने का समय लें", "सवाल पूछें"]) assert.ok(html.includes(phrase), phrase);
  for (const phrase of ["A clear path", "Families considering", "Understand the school", "Your next step", "Book a visit", "Ask a question"]) assert.ok(!html.includes(phrase), phrase);
});

test("English admissions retains approved facts, enquiry boundary, links and existing structure", () => {
  const { html } = render("en");
  assert.match(html, /<h1>A clear path to your<br\/><em>next chapter\.<\/em><\/h1>/);
  for (const phrase of ["Principal Brijesh Kumar Singh", "+91 98380 02012", "enquiry—not an admission offer", "age rules, documents, and fees are confirmed by the school office"]) assert.ok(html.includes(phrase), phrase);
  for (const path of ["/admissions/visit", "/contact"]) assert.ok(html.includes(`href="${path}"`), path);
  const classLists = [...html.matchAll(/class="([^"]*)"/g)].map(match => match[1].split(/\s+/));
  for (const className of ["inner-hero inner-hero--coral", "inner-sections__grid", "inner-section-card", "inner-next__inner"]) assert.ok(classLists.some(tokens => className.split(" ").every(token => tokens.includes(token))), className);
});

test("Hindi admissions preserves actual phone and office-confirmation safeguards without invented policy", () => {
  const { html } = render("hi");
  assert.ok(html.includes("+91 98380 02012"));
  assert.ok(html.includes("प्रधानाचार्य बृजेश कुमार सिंह"));
  assert.ok(html.includes("उम्र के नियम, दस्तावेज़ और फीस की पुष्टि स्कूल कार्यालय"));
  assert.ok(html.includes("केवल स्वीकृत जानकारी"));
  assert.ok(html.includes("जानकारी के लिए किया गया संपर्क है—प्रवेश का प्रस्ताव नहीं"));
});

test("shared layout preserves server-rendered optional public content instead of translating authored records", () => {
  const authored = "Synthetic school notice <script>no execution</script>";
  const { html, seen } = render("hi", "news", React.createElement("aside", null, authored));
  assert.ok(html.includes("Synthetic school notice &lt;script&gt;no execution&lt;/script&gt;"));
  assert.ok(!seen.has(authored), "school-authored content must not be sent through the product catalogue");
  assert.doesNotMatch(pageSource, /["']use client["']|fetch\(|useEffect|useState|dangerouslySetInnerHTML|localStorage|sessionStorage/);
});

test("static metadata and route data stay on the existing server-rendered public route", () => {
  const route = read("../app/[slug]/page.tsx");
  assert.match(route, /export async function generateMetadata/);
  assert.match(route, /return page \? \{ title: page\.eyebrow, description: page\.introduction \}/);
  assert.match(route, /if \(!page\) notFound\(\)/);
  assert.doesNotMatch(route, /["']use client["']|fetch\(/);
});

test("Hindi inner-page display leading is local and does not alter English or dense operational typography", () => {
  const css = read("../components/i18n/language.css");
  assert.match(css, /html:lang\(hi\) :is\(\.inner-hero h1, \.inner-section-card h2, \.inner-next h2\)\s*\{\s*line-height: 1\.25;\s*letter-spacing: normal;\s*\}/);
});
