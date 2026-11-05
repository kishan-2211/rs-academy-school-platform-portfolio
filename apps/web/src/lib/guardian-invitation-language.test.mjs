import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as messages from "./i18n/guardian-invitation-messages.ts";

const source = readFileSync(new URL("../components/family/guardian-invitation.tsx", import.meta.url), "utf8");
const require = createRequire(import.meta.url);
const preview = {
  schoolName: "Synthetic school <script>no execution</script>",
  maskedDestination: "s***@example.invalid",
  expiresAt: "2026-11-02T08:15:00Z",
  existingAccount: false,
  status: "ready",
};
const compiled = ts.transpileModule(source, { compilerOptions: {
  target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
} }).outputText;

function harness({ locale = "en", initialToken = "", states = {} } = {}) {
  let currentLocale = locale;
  let cursor = 0;
  const state = [];
  const hooks = {
    ...React,
    useEffect: () => {},
    useState: (initial) => {
      const index = cursor++;
      if (!(index in state)) state[index] = Object.hasOwn(states, index) ? states[index] : initial;
      return [state[index], next => { state[index] = typeof next === "function" ? next(state[index]) : next; }];
    },
  };
  const icon = () => React.createElement("svg", { "aria-hidden": true });
  const modules = {
    react: hooks,
    "next/link": { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) },
    "@/components/icons": { Check: icon, Shield: icon, ArrowRight: icon },
    "@/components/motion-illustration": { MotionIllustration: ({ label }) => React.createElement("span", { role: "img", "aria-label": label }) },
    "@/components/i18n/language-provider": { useLanguage: () => ({ locale: currentLocale }) },
    "@/lib/i18n/guardian-invitation-messages": messages,
  };
  const exports = {};
  new Function("require", "exports", compiled)(name => modules[name] ?? require(name), exports);
  function tree() { cursor = 0; return exports.GuardianInvitation({ initialToken }); }
  return {
    tree,
    html: () => renderToStaticMarkup(tree()),
    choose: next => { currentLocale = next; },
  };
}

function find(node, tag) {
  if (!React.isValidElement(node)) return null;
  if (node.type === tag) return node;
  for (const child of React.Children.toArray(node.props.children)) {
    const result = find(child, tag);
    if (result) return result;
  }
  return null;
}

async function submit(h, response, { password = "Synthetic-test-only-credential", confirmation = password } = {}) {
  const before = { fetch: globalThis.fetch, FormData: globalThis.FormData, window: globalThis.window };
  const requests = [], redirects = [];
  globalThis.FormData = class {
    get(key) { return { password, confirmation }[key] ?? null; }
  };
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options });
    if (response instanceof Error) throw response;
    return response;
  };
  globalThis.window = { setTimeout: callback => { callback(); }, location: { assign: path => redirects.push(path) } };
  try {
    const form = find(h.tree(), "form");
    assert.ok(form, "an actual rendered form is required");
    await form.props.onSubmit({ preventDefault() {}, currentTarget: {} });
    return { requests, redirects, html: h.html() };
  } finally {
    for (const [key, value] of Object.entries(before)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
}

for (const locale of ["en", "hi"]) {
  const t = key => messages.guardianInvitationMessage(locale, key);
  test(`${locale}: incomplete invitation is a localized alert with a native contact link`, () => {
    const html = harness({ locale }).html();
    assert.ok(html.includes(t("unavailableTitle")));
    assert.ok(html.includes(`<p role="alert">${t("missing")}</p>`));
    assert.ok(html.includes(`href="/contact">${t("contactSchool")}</a>`));
    assert.doesNotMatch(html, /<form|type="password"/);
    if (locale === "hi") assert.doesNotMatch(html, /Invitation unavailable|Contact the school|A private link to/);
  });

  test(`${locale}: preview loading is announced without premature credential fields`, () => {
    const html = harness({ locale, initialToken: "synthetic-not-a-real-token" }).html();
    assert.ok(html.includes(`<p role="status">${t("verifying")}</p>`));
    assert.doesNotMatch(html, /<form|type="password"/);
  });

  test(`${locale}: new-account form has translated labels while preserving account and relationship boundaries`, () => {
    const html = harness({ locale, states: { 0: preview } }).html();
    assert.ok(html.includes(`aria-label="${t("formLabel")}"`));
    for (const key of ["newInstruction", "createPassword", "confirmPassword", "passwordHelp", "privacy"]) assert.ok(html.includes(t(key)), key);
    assert.equal((html.match(/type="password"/g) ?? []).length, 2);
    assert.equal((html.match(/autoComplete="new-password"/g) ?? []).length, 2);
    assert.equal((html.match(/minLength="12" maxLength="200" required=""/g) ?? []).length, 2);
    assert.match(html, /Synthetic school &lt;script&gt;no execution&lt;\/script&gt;/);
    assert.ok(html.includes(preview.maskedDestination));
    assert.doesNotMatch(html, /<script>|name="email"|current-password/);
  });

  test(`${locale}: existing-account form retains its current-password flow and omits new-password confirmation`, () => {
    const html = harness({ locale, states: { 0: { ...preview, existingAccount: true } } }).html();
    assert.ok(html.includes(t("existingInstruction")));
    assert.ok(html.includes(t("currentPassword")));
    assert.match(html, /autoComplete="current-password"/);
    assert.equal((html.match(/type="password"/g) ?? []).length, 1);
    assert.doesNotMatch(html, /name="confirmation"|autoComplete="new-password"/);
  });

  test(`${locale}: submitting and accepted states keep truthful progress and normal-sign-in semantics`, () => {
    const busy = harness({ locale, states: { 0: preview, 2: true } }).html();
    assert.match(busy, /type="submit" disabled=""/);
    assert.ok(busy.includes(t("accepting")));
    const accepted = harness({ locale, states: { 3: true } }).html();
    for (const key of ["successTitle", "successBody", "successAnimation"]) assert.ok(accepted.includes(t(key)), key);
    assert.ok(accepted.includes(`<p role="status">${t("successBody")}</p>`));
    assert.doesNotMatch(accepted, /<form|type="password"/);
  });
}

test("expiry formatting follows the selected locale and never translates school-authored values", () => {
  const h = harness({ states: { 0: preview } });
  const options = { day: "numeric", month: "long", year: "numeric", hour: "numeric", minute: "2-digit" };
  const english = h.html();
  assert.ok(english.includes(new Intl.DateTimeFormat("en-IN", options).format(new Date(preview.expiresAt))));
  h.choose("hi");
  const hindi = h.html();
  assert.ok(hindi.includes(new Intl.DateTimeFormat("hi-IN", options).format(new Date(preview.expiresAt))));
  assert.match(hindi, /नवंबर/);
  for (const html of [english, hindi]) {
    assert.ok(html.includes(preview.maskedDestination));
    assert.match(html, /Synthetic school &lt;script&gt;no execution&lt;\/script&gt;/);
  }
});

test("known API codes map to bounded bilingual problems, and unknown/non-string codes use their context fallback", () => {
  const known = new Map([
    ["GUARDIAN_INVITATION_UNAVAILABLE", "unavailable"], ["GUARDIAN_INVITATION_ACCEPTANCE_DENIED", "unavailable"],
    ["GUARDIAN_INVITATION_CONTEXT_CHANGED", "contextChanged"], ["STALE_VERSION", "contextChanged"],
    ["GUARDIAN_ACCOUNT_LINK_CONFLICT", "accountConflict"], ["GUARDIAN_MEMBERSHIP_UNAVAILABLE", "membershipUnavailable"],
  ]);
  for (const [code, expected] of known) {
    assert.equal(messages.guardianInvitationProblemKey(code, "acceptUnavailable"), expected);
    const h = harness({ states: { 0: preview, 4: expected } });
    assert.ok(h.html().includes(messages.guardianInvitationMessage("en", expected)));
    h.choose("hi");
    assert.ok(h.html().includes(messages.guardianInvitationMessage("hi", expected)));
    assert.doesNotMatch(h.html(), new RegExp(code));
  }
  for (const code of [undefined, null, 12, {}, ["GUARDIAN_ACCOUNT_LINK_CONFLICT"], "__proto__", "UNKNOWN_ERROR"]) {
    assert.equal(messages.guardianInvitationProblemKey(code, "unavailable"), "unavailable");
    assert.equal(messages.guardianInvitationProblemKey(code, "acceptUnavailable"), "acceptUnavailable");
  }
});

test("password mismatch is localized and sends no acceptance request", async () => {
  const h = harness({ locale: "hi", initialToken: "synthetic-not-a-real-token", states: { 0: preview, 1: false } });
  const result = await submit(h, new Response(null, { status: 204 }), { confirmation: "Different-synthetic-value" });
  assert.equal(result.requests.length, 0);
  assert.equal(result.redirects.length, 0);
  assert.ok(result.html.includes(messages.guardianInvitationMessage("hi", "mismatch")));
});

test("acceptance errors ignore raw server details and remain translatable after the locale changes", async () => {
  const errorBody = { code: "GUARDIAN_ACCOUNT_LINK_CONFLICT", detail: "Synthetic private internal detail <script>no execution</script>" };
  const response = Response.json(errorBody, { status: 409 });
  const h = harness({ initialToken: "synthetic-not-a-real-token", states: { 0: preview, 1: false } });
  const result = await submit(h, response);
  assert.equal(result.requests.length, 1);
  assert.equal(result.redirects.length, 0);
  assert.ok(result.html.includes(messages.guardianInvitationMessage("en", "accountConflict")));
  h.choose("hi");
  assert.ok(h.html().includes(messages.guardianInvitationMessage("hi", "accountConflict")));
  assert.doesNotMatch(h.html(), /Synthetic private internal detail|GUARDIAN_ACCOUNT_LINK_CONFLICT|<script>/);
});

test("unknown, malformed and network errors use a localized safe fallback and re-enable the form", async () => {
  for (const response of [
    Response.json({ code: "UNKNOWN", detail: "Synthetic server-only detail" }, { status: 500 }),
    new Response("Synthetic non-JSON error", { status: 502 }),
    new Error("Synthetic network-only diagnostic"),
  ]) {
    const h = harness({ locale: "hi", states: { 0: preview } });
    const result = await submit(h, response);
    assert.ok(result.html.includes(messages.guardianInvitationMessage("hi", "acceptUnavailable")));
    assert.doesNotMatch(result.html, /Synthetic server-only detail|Synthetic non-JSON error|Synthetic network-only diagnostic|type="submit" disabled/);
    assert.equal(result.redirects.length, 0);
  }
});

test("successful acceptance preserves the exact existing endpoint/body and normal sign-in redirect", async () => {
  const h = harness({ locale: "hi", initialToken: "synthetic-not-a-real-token", states: { 0: preview, 1: false } });
  const result = await submit(h, new Response(null, { status: 204 }));
  assert.equal(result.requests.length, 1);
  assert.ok(result.requests[0].url.endsWith("/api/v1/public/guardian-invitations/acceptances"));
  assert.equal(result.requests[0].options.method, "POST");
  assert.deepEqual(JSON.parse(result.requests[0].options.body), { token: "synthetic-not-a-real-token", password: "Synthetic-test-only-credential" });
  assert.deepEqual(result.redirects, ["/portal?invitation=accepted"]);
  assert.ok(result.html.includes(messages.guardianInvitationMessage("hi", "successBody")));
  assert.doesNotMatch(result.html, /<form|type="password"/);
});
