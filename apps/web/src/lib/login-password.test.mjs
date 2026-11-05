import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { hindi } from "./i18n/messages.ts";

const text = readFileSync(new URL("../components/login-form.tsx", import.meta.url), "utf8");
const source = ts.createSourceFile("login-form.tsx", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let field;
function visit(node) {
  if (ts.isJsxElement(node) && node.openingElement.attributes.properties.some((attribute) =>
    ts.isJsxAttribute(attribute) && attribute.name.getText(source) === "className" &&
    attribute.initializer?.getText(source) === "{styles.field}")) field = node;
  ts.forEachChild(node, visit);
}
visit(source);
assert.ok(field, "Test the actual password field JSX");
const compiled = ts.transpileModule(`const element = (${field.getText(source)});`, {
  compilerOptions: { jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022 },
}).outputText;
const render = new Function("React", "styles", "passwordId", "passwordVisible", "submitting", "setPasswordVisible", "t",
  `${compiled}\nreturn element;`);

for (const visible of [false, true]) {
  test(`password visibility ${visible} uses a named, non-submitting toggle and preserves the input contract`, () => {
    let state = visible;
    const element = render(React, {}, "test-password", visible, false,
      (update) => { state = update(state); }, (value) => value);
    const [label, control] = React.Children.toArray(element.props.children);
    const [input, button] = React.Children.toArray(control.props.children);
    assert.equal(label.props.htmlFor, input.props.id);
    assert.equal(input.props.type, visible ? "text" : "password");
    assert.equal(input.props.name, "password");
    assert.equal(input.props.autoComplete, "current-password");
    assert.equal(input.props.required, true);
    assert.equal(input.props.minLength, 8);
    assert.equal(input.props.spellCheck, false);
    assert.equal(input.props.value, undefined, "Visibility must not take ownership of the password value");
    assert.equal(button.props.type, "button");
    assert.equal(button.props["aria-controls"], input.props.id);
    assert.equal(button.props.children, visible ? "Hide password" : "Show password");
    button.props.onClick();
    assert.equal(state, !visible);
    assert.match(renderToStaticMarkup(element), /autocomplete="current-password"/i);
  });
}

test("password is hidden initially, on submit, and when changing designation", () => {
  assert.match(text, /\[passwordVisible, setPasswordVisible\] = useState\(false\)/);
  assert.match(text, /async function submitCredentials[\s\S]*?event.preventDefault\(\);\s*setPasswordVisible\(false\);/);
  assert.match(text, /setPersona\(null\);\s*setPasswordVisible\(false\);/);
  const element = render(React, {}, "test-password", false, true, () => {}, (value) => value);
  assert.match(renderToStaticMarkup(element), /<button[^>]*disabled=""/);
});

test("both password toggle actions have Hindi labels", () => {
  for (const key of ["Show password", "Hide password"]) {
    assert.match(hindi.authentication[key], /\p{Script=Devanagari}/u);
  }
});
