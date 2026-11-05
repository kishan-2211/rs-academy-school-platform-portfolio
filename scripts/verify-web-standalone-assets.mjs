import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { cp, readFile, readdir } from "node:fs/promises";
import { createServer } from "node:net";
import { spawn } from "node:child_process";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as pause } from "node:timers/promises";

const project = fileURLToPath(new URL("../", import.meta.url));
const web = join(project, "apps/web");
const runtime = join(web, ".next/standalone/apps/web");
const dockerfile = await readFile(join(web, "Dockerfile"), "utf8");
for (const [source, target] of [
  [".next/standalone", "./"], [".next/static", "./apps/web/.next/static"],
  ["public", "./apps/web/public"],
]) {
  assert.ok(dockerfile.includes(`COPY --from=builder --chown=educore:educore /workspace/apps/web/${source} ${target}`),
    `Container runtime must package ${source}`);
}
// Only generated standalone build output is populated. Source, school data and
// the running application are untouched. This is not a Docker-image acceptance claim.
await readFile(join(runtime, "server.js"));
await cp(join(web, "public"), join(runtime, "public"), { recursive: true });
await cp(join(web, ".next/static"), join(runtime, ".next/static"), { recursive: true });
const reservation = createServer();
await new Promise((resolve, reject) => { reservation.once("error", reject); reservation.listen(0, "127.0.0.1", resolve); });
const port = reservation.address().port;
await new Promise((resolve, reject) => reservation.close((error) => error ? reject(error) : resolve()));
const server = spawn(process.execPath, [join(runtime, "server.js")], {
  cwd: runtime, windowsHide: true, stdio: "ignore",
  env: { ...process.env, PORT: String(port), HOSTNAME: "127.0.0.1", NEXT_TELEMETRY_DISABLED: "1" },
});
let failure;
server.on("error", (error) => { failure = error; });
const base = `http://127.0.0.1:${port}`;
try {
  let ready = false;
  for (let attempt = 0; attempt < 40; attempt++) {
    if (failure || server.exitCode !== null) throw failure || new Error("Standalone server exited before readiness");
    try {
      const response = await fetch(`${base}/brand/rs-academy-logo.jpeg`, { signal: AbortSignal.timeout(1000) });
      if (response.ok) { ready = true; break; }
    } catch { /* Await this new server only, bounded below. */ }
    await pause(100);
  }
  assert.ok(ready, "Packaged school logo must become available on the standalone server");
  const assets = ["brand/rs-academy-logo.jpeg", "animations/study-discussion.lottie", "animations/dotlottie-player.wasm"];
  const staticFiles = await readdir(join(web, ".next/static"), { recursive: true });
  const css = staticFiles.find((name) => name.endsWith(".css"));
  assert.ok(css, "Production build must include stylesheet assets");
  const checks = assets.map((asset) => ({ url: `/${asset}`, source: join(web, "public", asset) }));
  checks.push({ url: `/_next/static/${css.replaceAll("\\", "/")}`, source: join(web, ".next/static", css) });
  for (const check of checks) {
    const response = await fetch(`${base}${check.url}`, { signal: AbortSignal.timeout(10000) });
    assert.equal(response.status, 200, `${check.url} must return 200`);
    const expected = createHash("sha256").update(await readFile(check.source)).digest("hex");
    const actual = createHash("sha256").update(Buffer.from(await response.arrayBuffer())).digest("hex");
    assert.equal(actual, expected, `${check.url} must serve the actual build asset, not an HTML fallback`);
    console.log(`Standalone asset verified: ${check.url}`);
  }
  console.log("Real standalone public/style asset verification passed. No deployment or container-image verification claimed.");
} finally {
  if (server.pid && server.exitCode === null && server.signalCode === null) {
    const exited = new Promise((resolve) => server.once("exit", resolve));
    assert.ok(server.kill(), "Owned standalone test server must stop");
    await Promise.race([exited, pause(5000).then(() => { throw new Error("Owned standalone test server did not stop"); })]);
  }
}
