import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

function source(path) {
  const text = readFileSync(new URL(path, import.meta.url), "utf8");
  return { text, ast: ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX) };
}
const boundary = source("../components/workspace/privileged-access-boundary.tsx");
const centre = source("../components/workspace/security-centre.tsx");
function functionFrom(input, name, bindings) {
  const node = input.ast.statements.find((item) => ts.isFunctionDeclaration(item) && item.name?.text === name);
  assert.ok(node, name);
  const fragment = ts.factory.updateFunctionDeclaration(node, node.modifiers?.filter((item) => item.kind === ts.SyntaxKind.AsyncKeyword), node.asteriskToken, node.name,
    node.typeParameters, node.parameters, node.type, node.body);
  const text = ts.createPrinter().printNode(ts.EmitHint.Unspecified, fragment, input.ast);
  const js = ts.transpileModule(`const result = (${text});`, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  return new Function(...Object.keys(bindings), `${js}\nreturn result;`)(...Object.values(bindings));
}
const useT = () => (text) => text;
const styles = { content: "content", requirement: "requirement", acknowledgement: "acknowledgement" };
function descendants(view, predicate) {
  const output = [];
  function walk(nodes) {
    React.Children.forEach(nodes, (child) => {
      if (!React.isValidElement(child)) return;
      if (predicate(child)) output.push(child);
      walk(child.props.children);
    });
  }
  walk(view); return output;
}

for (const required of [false, true]) test(`real boundary handler resolves mfaRequired=${required} without credentials in storage`, async () => {
  const signal = new AbortController().signal;
  let request;
  const check = functionFrom(boundary, "checkPrivilegedAccess", { apiUrl: "https://synthetic.example.test", fetch: async (...args) => {
    request = args; return { ok: true, json: async () => ({ mfaRequired: required }) };
  } });
  assert.equal(await check(signal), required ? "mfa-required" : "allowed");
  assert.equal(request[0], "https://synthetic.example.test/api/v1/context");
  assert.deepEqual(request[1], { credentials: "include", cache: "no-store", signal });
});

for (const state of [null, {}, { mfaRequired: "false" }, { mfaRequired: 0 }]) test(`incomplete context never opens children: ${JSON.stringify(state)}`, async () => {
  const check = functionFrom(boundary, "checkPrivilegedAccess", { apiUrl: "", fetch: async () => ({ ok: true, json: async () => state }) });
  await assert.rejects(check(new AbortController().signal));
});
test("failed context request stays a failure, not an allowed empty response", async () => {
  const check = functionFrom(boundary, "checkPrivilegedAccess", { apiUrl: "", fetch: async () => ({ ok: false, json: () => { throw Error("Must not parse failed response"); } }) });
  await assert.rejects(check(new AbortController().signal), /unavailable/);
});

for (const state of ["checking", "allowed", "mfa-required", "unavailable"]) test(`${state} boundary renders only appropriate content with accessible status/retry`, () => {
  const Content = functionFrom(boundary, "PrivilegedAccessContent", { React, useT, styles,
    SecurityCentre: () => React.createElement("section", { "data-existing-centre": true }, "Existing security centre") });
  let retried = false;
  const view = Content({ state, retry: () => { retried = true; }, children: React.createElement("p", null, "Synthetic private school record") });
  const html = renderToStaticMarkup(view);
  assert.equal(html.includes("Synthetic private school record"), state === "allowed");
  assert.equal(html.includes("Existing security centre"), state === "mfa-required");
  if (state === "checking") assert.match(html, /role="status"/);
  if (state === "unavailable") {
    assert.match(html, /role="alert"/);
    const button = descendants(view, (node) => node.type === "button")[0];
    assert.equal(button.props.type, "button"); button.props.onClick(); assert.equal(retried, true);
  }
});

function notice(props) {
  const SignOutButton = () => React.createElement("button", { type: "button" }, "Explicit sign out");
  return functionFrom(centre, "MfaRequirementNotice", { React, useT, styles, SignOutButton })({
    required: true, enabled: false, recoveryCodesPresent: false, recoverySaved: false, setRecoverySaved: () => {}, ...props,
  });
}
test("mandatory initial enrollment is explained without implying a verified session", () => {
  const html = renderToStaticMarkup(notice());
  assert.match(html, /role="status"/); assert.match(html, /School records remain locked/);
  assert.match(html, /Set up two-step verification/); assert.doesNotMatch(html, /Explicit sign out/);
});
test("activated factor requires explicit fresh login, never automatic loss of recovery codes", () => {
  const html = renderToStaticMarkup(notice({ enabled: true }));
  assert.match(html, /has not verified a second factor/); assert.match(html, /Explicit sign out/);
});
test("recovery acknowledgement is a native labelled checkbox and protects the scoped signout action", () => {
  let saved;
  const view = notice({ enabled: true, recoveryCodesPresent: true, setRecoverySaved: (value) => { saved = value; } });
  const html = renderToStaticMarkup(view);
  assert.doesNotMatch(html, /Explicit sign out/);
  assert.match(html, /<label[^>]*><input type="checkbox"/);
  assert.match(html, /I have stored my recovery codes safely offline/);
  const checkbox = descendants(view, (node) => node.type === "input")[0];
  assert.equal(checkbox.props.checked, false);
  checkbox.props.onChange({ currentTarget: { checked: true } }); assert.equal(saved, true);
  assert.match(renderToStaticMarkup(notice({ enabled: true, recoveryCodesPresent: true, recoverySaved: true })), /Explicit sign out/);
});
test("optional parent MFA notice does not falsely say ordinary school records are locked", () => {
  const html = renderToStaticMarkup(notice({ required: false, enabled: true }));
  assert.match(html, /Finish authenticator setup/); assert.doesNotMatch(html, /School records remain locked/);
});
test("all authenticated shells block business children and reuse the existing security centre", () => {
  const workspace = source("../components/workspace/workspace-shell.tsx").text;
  assert.match(workspace, /mfaRequired \? <SecurityCentre mfaRequired \/> : children/);
  assert.match(workspace, /mfaRequired \? item.href === "\/workspace\/security"/);
  for (const path of ["../components/family/family-shell.tsx", "../components/student/student-shell.tsx"]) {
    const text = source(path).text;
    assert.match(text, /usePrivilegedAccessCheck\(\)/);
    assert.match(text, /<PrivilegedAccessContent state=\{access.state\} retry=\{access.retry\}>\{children\}/);
    assert.match(text, /<SignOutButton/);
  }
  assert.match(boundary.text, /controller.abort\(\)/);
  assert.doesNotMatch(boundary.text + centre.text, /localStorage|sessionStorage|window.location/);
  assert.match(centre.text, /setRecoveryCodes\(result.recoveryCodes\)/);
  assert.match(centre.text, /focus\(\{ preventScroll: true \}\)/);
  assert.match(centre.text, /behavior: "instant"/);
});
test("security copy has a scoped Hindi catalog and semantic focus/motion-safe styles", () => {
  const text = readFileSync(new URL("./i18n/security-messages.ts", import.meta.url), "utf8");
  const code = ts.transpileModule(text, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
  const exports = {}; new Function("exports", code)(exports);
  for (const key of ["Two-step verification required", "Finish authenticator setup", "Checking account verification…", "I have stored my recovery codes safely offline."]) {
    assert.ok(exports.securityMessages[key]); assert.notEqual(exports.securityMessages[key], key);
  }
  const css = readFileSync(new URL("../components/workspace/security-requirement.module.css", import.meta.url), "utf8");
  assert.match(css, /var\(--theme-warning-bg\)/); assert.match(css, /:focus-visible/);
  assert.match(css, /outline: 3px/); assert.doesNotMatch(css, /animation|transition|perspective/);
});

test("actual security centre keeps controls readable and narrow grids shrinkable without editing global styles", () => {
  const css = readFileSync(new URL("../components/workspace/security-requirement.module.css", import.meta.url), "utf8");
  assert.match(centre.text, /security-centre \$\{styles.centre\}/);
  assert.match(css, /grid-template-columns: minmax\(0, 1fr\)/);
  assert.match(css, /\.centre :global\(\.security-card\) button,[\s\S]*?min-height: 44px;[\s\S]*?font-size: 14px/);
  assert.match(css, /\.centre :global\(\.security-card\) > header \{[\s\S]*?flex-wrap: wrap/);
  assert.match(css, /var\(--theme-border\)/);
});

test("retained expired session records are not falsely labeled active", () => {
  assert.match(centre.text, /t\("Unrevoked sessions"\)/);
  assert.match(centre.text, /t\("\{count\} not revoked", \{ count: sessions.length \}\)/);
  assert.doesNotMatch(centre.text, /t\("\{count\} active"/);
});
