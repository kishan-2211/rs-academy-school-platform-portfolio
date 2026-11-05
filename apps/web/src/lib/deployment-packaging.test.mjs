import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

test("standalone container retains school branding, PWA/player assets and production styles", () => {
  const dockerfile = readFileSync(new URL("../../Dockerfile", import.meta.url), "utf8");
  for (const [source, target] of [["public", "./apps/web/public"], [".next/static", "./apps/web/.next/static"]]) {
    assert.ok(dockerfile.includes(`COPY --from=builder --chown=educore:educore /workspace/apps/web/${source} ${target}`));
  }
  assert.match(dockerfile, /USER educore/);
});

test("Docker context excludes local synthetic credentials, private data and keys at every depth", () => {
  const patterns = new Set(readFileSync(new URL("../../../../.dockerignore", import.meta.url), "utf8").split(/\r?\n/));
  for (const directory of [".build-tmp", ".local-tools", ".editor-tools", ".aws", ".data", "backups", "uploads", "private-uploads", "storage", "secrets", "credentials"]) {
    assert.ok(patterns.has(directory), directory);
    assert.ok(patterns.has(`**/${directory}`), `nested ${directory}`);
  }
  for (const pattern of ["**/.env", "**/.env.*", ".npmrc", "**/.npmrc", "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore", "**/credentials.json", "**/service-account*.json"]) {
    assert.ok(patterns.has(pattern), pattern);
  }
  // Keep the nonsecret template available; no .env.local exception is allowed.
  assert.ok(patterns.has("!**/.env.example"));
  assert.ok(!patterns.has("!**/.env.local"));
});

test("image install uses committed security policy without developer registry configuration", () => {
  const dockerfile = readFileSync(new URL("../../Dockerfile", import.meta.url), "utf8");
  const workspace = readFileSync(new URL("../../../../pnpm-workspace.yaml", import.meta.url), "utf8");
  assert.match(dockerfile, /COPY package\.json pnpm-lock\.yaml pnpm-workspace\.yaml \.\//);
  assert.doesNotMatch(dockerfile, /COPY[^\n]*\.npmrc/);
  assert.match(dockerfile, /pnpm install --frozen-lockfile/);
  assert.match(workspace, /^engineStrict: true$/m);
  assert.match(workspace, /^minimumReleaseAge: 10080$/m);
  assert.match(workspace, /^trustPolicy: no-downgrade$/m);
  assert.match(workspace, /^allowBuilds:$/m);
});

test("real-image verifier does not assign reserved PowerShell system variables", () => {
  const verifier = readFileSync(new URL("../../../../scripts/verify-web-runtime-image.ps1", import.meta.url), "utf8");
  assert.doesNotMatch(verifier, /\$(?:home|workspace_home|host|pid)\s*=/i);
  assert.match(verifier, /\$homepageResponse = Read-WebResponse '\/'/);
});
