import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { browserSupportsPush, pushCsrfToken, pushDeviceActions, waitForPushRegistration } from "./push-notification-client.ts";

function fakeGlobal(t, name, value) {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, name);
  Object.defineProperty(globalThis, name, { configurable: true, value });
  t.after(() => { if (descriptor) Object.defineProperty(globalThis, name, descriptor); else delete globalThis[name]; });
}

test("another registered device never hides this browser's registration action", () => {
  assert.deepEqual(pushDeviceActions({ registrationEnabled: true, activeDeviceCount: 4 }, true, false), { canEnable: true, canDisable: true });
  assert.deepEqual(pushDeviceActions({ registrationEnabled: false }, true, false), { canEnable: false, canDisable: true });
  assert.deepEqual(pushDeviceActions(null, true, false), { canEnable: false, canDisable: false });
  assert.equal(pushDeviceActions({ registrationEnabled: true }, false, false).canEnable, false);
  assert.equal(pushDeviceActions({ registrationEnabled: true }, true, true).canDisable, false);
});

test("push support requires a secure browser and all necessary capabilities", (t) => {
  assert.equal(browserSupportsPush(), false);
  fakeGlobal(t, "window", { isSecureContext: true, PushManager: {}, Notification: {} });
  fakeGlobal(t, "navigator", { serviceWorker: {} });
  assert.equal(browserSupportsPush(), true);
  window.isSecureContext = false;
  assert.equal(browserSupportsPush(), false);
});

test("missing CSRF is bootstrapped from the real current-session endpoint", async (t) => {
  fakeGlobal(t, "document", { cookie: "" });
  t.mock.method(globalThis, "fetch", async (url, init) => {
    assert.match(url, /\/api\/v1\/auth\/sessions\/current$/);
    assert.equal(init.credentials, "include"); assert.equal(init.cache, "no-store");
    document.cookie = "educore_csrf=synthetic%2Dtoken";
    return new Response(null, { status: 200 });
  });
  assert.equal(await pushCsrfToken(), "synthetic-token");
});

test("missing token and failed session checks fail closed", async (t) => {
  fakeGlobal(t, "document", { cookie: "" });
  t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 200 }));
  await assert.rejects(pushCsrfToken(), /secure form token is unavailable/);
  t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 401 }));
  await assert.rejects(pushCsrfToken(), /session could not be confirmed/);
});

test("worker readiness resolves normally or times out without trapping the user", async (t) => {
  const registration = { synthetic: true };
  assert.equal(await waitForPushRegistration(Promise.resolve(registration)), registration);
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const pending = waitForPushRegistration(new Promise(() => {}), 10_000);
  const rejected = assert.rejects(pending, /service worker is not ready/);
  t.mock.timers.tick(10_000);
  await rejected;
});

test("shared control keeps SSR support stable and exposes explicit independent device actions", () => {
  const component = readFileSync(new URL("../components/push-notification-control.tsx", import.meta.url), "utf8");
  const styles = readFileSync(new URL("../components/push-notification-control.module.css", import.meta.url), "utf8");
  assert.match(component, /useSyncExternalStore\(subscribeSupport, browserSupportsPush, serverSupport\)/);
  assert.match(component, /const serverSupport = \(\) => false/);
  assert.match(component, /disabled=\{!deviceActions.canEnable\}/);
  assert.match(component, /disabled=\{!deviceActions.canDisable\}/);
  assert.doesNotMatch(component, /!active \?|\/api\/v1\/session["`]/);
  assert.equal((component.match(/await waitForPushRegistration/g) ?? []).length, 2);
  assert.match(styles, /outline: 3px solid var\(--theme-text\)/);
  assert.match(styles, /\.buttonSecondary:hover:not\(:disabled\)/);
});
