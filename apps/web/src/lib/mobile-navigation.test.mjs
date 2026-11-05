import assert from "node:assert/strict";
import test from "node:test";
import { bindMobileNavigation } from "./mobile-navigation.ts";

function fixture() {
  const listeners = new Map();
  const owner = {
    activeElement: null,
    body: { style: { overflow: "clip" } },
    addEventListener: (name, handler) => listeners.set(name, handler),
    removeEventListener: (name) => listeners.delete(name),
  };
  const control = () => ({ ownerDocument: owner, focus() { owner.activeElement = this; } });
  const toggle = control();
  const links = [control(), control()];
  const background = [{ inert: false }, { inert: true }];
  let closed = 0;
  const cleanup = bindMobileNavigation(toggle, { querySelectorAll: () => links }, background, () => { closed++; });
  function key(key, shiftKey = false) {
    let prevented = false;
    listeners.get("keydown")?.({ key, shiftKey, preventDefault: () => { prevented = true; } });
    return prevented;
  }
  return { owner, toggle, links, background, cleanup, key, closed: () => closed };
}

test("Escape closes the public menu and restores focus to its toggle", () => {
  const f = fixture();
  f.links[0].focus();
  assert.equal(f.key("Escape"), true);
  assert.equal(f.closed(), 1);
  assert.equal(f.owner.activeElement, f.toggle);
  f.cleanup();
});

test("Tab and Shift+Tab stay within the visible navigation controls", () => {
  const f = fixture();
  f.links[1].focus();
  assert.equal(f.key("Tab"), true);
  assert.equal(f.owner.activeElement, f.toggle);
  assert.equal(f.key("Tab", true), true);
  assert.equal(f.owner.activeElement, f.links[1]);
  f.links[0].focus();
  assert.equal(f.key("Tab"), false);
  f.owner.activeElement = null;
  assert.equal(f.key("Tab"), true);
  assert.equal(f.owner.activeElement, f.toggle);
  f.cleanup();
});

test("closing or unmounting restores existing scroll and inert state and removes listeners", () => {
  const f = fixture();
  assert.equal(f.owner.body.style.overflow, "hidden");
  assert.deepEqual(f.background.map((element) => element.inert), [true, true]);
  f.cleanup();
  assert.equal(f.owner.body.style.overflow, "clip");
  assert.deepEqual(f.background.map((element) => element.inert), [false, true]);
  f.key("Escape");
  assert.equal(f.closed(), 0);
});
