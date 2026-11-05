import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const parse = (source) => {
  const settings = new Map();
  for (const raw of source.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line);
    assert.ok(match, `Invalid example setting: ${line}`);
    assert.ok(!settings.has(match[1]), `Duplicate example setting: ${match[1]}`);
    settings.set(match[1], match[2]);
  }
  return settings;
};

test("intended production URLs share the canonical HTTPS browser origin", () => {
  const values = parse(read(".env.production.example"));
  for (const name of ["NEXT_PUBLIC_APP_URL", "NEXT_PUBLIC_API_URL", "APP_PUBLIC_URL", "APP_ALLOWED_ORIGINS"]) {
    const value = values.get(name);
    assert.equal(value, "https://rsacademyschool.in", name);
    const url = new URL(value);
    assert.equal(url.origin, value);
    assert.equal(url.pathname, "/");
    assert.equal(url.search + url.hash + url.username + url.password, "");
  }
  assert.equal(values.get("APP_COOKIE_SECURE"), "true");
  assert.equal(values.get("APP_COOKIE_DOMAIN"), "");
  assert.equal(values.get("APP_BOOTSTRAP_ENABLED"), "false");
});

test("URL fragment contains no credentials or enabling payment/push switches", () => {
  const values = parse(read(".env.production.example"));
  const allowed = new Set([
    "NEXT_PUBLIC_APP_URL", "NEXT_PUBLIC_API_URL", "APP_PUBLIC_URL", "APP_ALLOWED_ORIGINS",
    "APP_COOKIE_SECURE", "APP_COOKIE_DOMAIN", "APP_BOOTSTRAP_ENABLED",
    "APP_FINANCE_GATEWAY_CREATION_ENABLED", "APP_FINANCE_GATEWAY_WORKER_ENABLED",
    "APP_FINANCE_GATEWAY_LIVE_ENABLED", "APP_FINANCE_GATEWAY_REFUNDS_ENABLED", "APP_FINANCE_GATEWAY_TEST_ALLOW_LOOPBACK",
    "APP_PUSH_REGISTRATION_ENABLED", "APP_PUSH_DELIVERY_ENABLED", "APP_PUSH_WORKER_ENABLED",
  ]);
  assert.deepEqual(new Set(values.keys()), allowed);
  for (const [name, value] of values) {
    if (name.includes("GATEWAY_") || name.startsWith("APP_PUSH_")) assert.equal(value, "false", name);
    assert.doesNotMatch(name, /PASSWORD|SECRET|KEY|TOKEN|DATABASE|STORAGE|CLIENT_ID/);
  }
});

test("existing localhost configuration and relative API/PWA contracts remain intact", () => {
  const values = parse(read(".env.example"));
  for (const name of ["NEXT_PUBLIC_APP_URL", "APP_PUBLIC_URL", "APP_ALLOWED_ORIGINS"]) {
    assert.equal(values.get(name), "http://localhost:3000", name);
  }
  assert.equal(values.get("NEXT_PUBLIC_API_URL"), "http://localhost:8080");
  assert.equal(values.get("APP_COOKIE_SECURE"), "false");
  assert.match(read("packages/contracts/openapi.yaml"), /servers:\s*\n\s*- url: \/api\/v1\s*\n\s*description: Same-origin deployment/);
  const manifest = read("apps/web/src/app/manifest.ts");
  assert.match(manifest, /start_url: "\/portal"/);
  assert.match(manifest, /scope: "\/"/);
  assert.match(read("apps/web/src/app/layout.tsx"), /metadataBase: new URL\(process\.env\.NEXT_PUBLIC_APP_URL/);
});

test("domain runbook documents origins, cookies and the required edge proxy", () => {
  const domain = read("docs/operations/PRODUCTION_DOMAIN.md").replace(/\s+/g, " ");
  for (const expected of [
    "https://www.rsacademyschool.in",
    "https://api.rsacademyschool.in/api/v1", "same-origin", "does **not** implement API rewrites",
    "APP_COOKIE_DOMAIN", "without stripping its path", "host-only",
    "Before a live deployment",
  ]) assert.ok(domain.includes(expected), expected);
  for (const path of ["docs/operations/PRODUCTION_OPERATOR_HANDOFF.md", "docs/operations/PRODUCTION_READINESS.md", "docs/operations/GATEWAY_CONFIGURATION.md"]) {
    assert.ok(read(path).includes("PRODUCTION_DOMAIN.md"), path);
    assert.ok(read(path).includes("rsacademyschool.in"), path);
  }
  assert.doesNotMatch(read("docs/operations/GATEWAY_CONFIGURATION.md"), /public-url: https:\/\/school\.example\.invalid/);
});
