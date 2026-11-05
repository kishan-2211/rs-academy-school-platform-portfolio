import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import yaml from "js-yaml";

function repositorySource(path) {
  return readFileSync(new URL(`../../../../${path}`, import.meta.url), "utf8");
}

function repositoryYaml(path) {
  // Source contracts only: Docker's real interpolation is a separate CI gate.
  // Keep duplicate-key rejection and the installed parser's bounded merge handling.
  return yaml.load(repositorySource(path), { filename: path, maxTotalMergeKeys: 1000 });
}

const apiEnvironment = repositoryYaml("infra/compose.yaml").services.api.environment;
// Spring's file also contains a production-profile document; parse the full
// stream while checking the shared defaults rather than rejecting that profile.
const applicationDocuments = yaml.loadAll(repositorySource("apps/api/src/main/resources/application.yml"), {
  filename: "apps/api/src/main/resources/application.yml", maxTotalMergeKeys: 1000,
});
const application = applicationDocuments[0].app;
const exampleEnvironment = new Map();
for (const line of repositorySource(".env.example").split(/\r?\n/)) {
  const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line);
  if (!match) continue;
  assert.ok(!exampleEnvironment.has(match[1]), `duplicate example setting: ${match[1]}`);
  exampleEnvironment.set(match[1], match[2]);
}

const inAppWorkers = [
  ["APP_GUARDIAN_INVITATION_EXPIRY", application["guardian-invitations"].expiry],
  ["APP_CALENDAR_NOTIFICATIONS", application.calendar.notifications],
  ["APP_LEARNING_NOTIFICATIONS", application.learning.notifications],
  ["APP_PARENT_REQUEST_NOTIFICATIONS", application["parent-requests"].notifications],
  ["APP_STUDENT_LEAVE_NOTIFICATIONS", application["student-leave"].notifications],
  ["APP_PTM_REMINDERS", null],
];
const pushFlags = ["APP_PUSH_REGISTRATION_ENABLED", "APP_PUSH_DELIVERY_ENABLED", "APP_PUSH_WORKER_ENABLED"];
const switches = [...inAppWorkers.map(([prefix]) => `${prefix}_ENABLED`), ...pushFlags];

function assertForwardedSetting(key, fallback) {
  assert.equal(apiEnvironment[key], `\${${key}:-${fallback}}`, `Compose must forward ${key}`);
  assert.equal(exampleEnvironment.get(key), String(fallback), `example default for ${key}`);
}

test("Compose forwards all database-only worker switches and unchanged timing defaults", () => {
  for (const [prefix, configuration] of inAppWorkers) {
    for (const [suffix, fallback, property] of [
      ["ENABLED", "true", "enabled"],
      ["POLL_DELAY_MS", "60000", "poll-delay-ms"],
      ["INITIAL_DELAY_MS", "30000", "initial-delay-ms"],
    ]) {
      const key = `${prefix}_${suffix}`;
      assertForwardedSetting(key, fallback);
      if (configuration) assert.equal(configuration[property], `\${${key}:${fallback}}`);
    }
  }

  // PTM uses the existing relaxed environment binding and scheduler defaults,
  // rather than a duplicated application.yml mapping.
  const ptmScheduler = repositorySource("apps/api/src/main/java/com/educore/api/ptm/PtmReminderScheduler.java");
  assert.match(ptmScheduler, /@ConditionalOnProperty\(name\s*=\s*"app\.ptm\.reminders\.enabled",\s*havingValue\s*=\s*"true",\s*matchIfMissing\s*=\s*true\)/);
  assert.ok(ptmScheduler.includes("${app.ptm.reminders.poll-delay-ms:60000}"));
  assert.ok(ptmScheduler.includes("${app.ptm.reminders.initial-delay-ms:30000}"));
});

test("Web Push remains three independent opt-in switches with unchanged worker timing", () => {
  for (const key of pushFlags) assertForwardedSetting(key, "false");
  assert.equal(application.push["registration-enabled"], "${APP_PUSH_REGISTRATION_ENABLED:false}");
  assert.equal(application.push.delivery.enabled, "${APP_PUSH_DELIVERY_ENABLED:false}");
  assert.equal(application.push.delivery["worker-enabled"], "${APP_PUSH_WORKER_ENABLED:false}");

  for (const [key, fallback, property] of [
    ["APP_PUSH_POLL_DELAY_MS", "15000", "poll-delay-ms"],
    ["APP_PUSH_INITIAL_DELAY_MS", "30000", "initial-delay-ms"],
  ]) {
    assertForwardedSetting(key, fallback);
    assert.equal(application.push.delivery[property], `\${${key}:${fallback}}`);
  }
});

test("Compose forwards the existing VAPID configuration without supplying provider credentials", () => {
  for (const [key, fallback, configuredValue] of [
    ["APP_PUSH_APPLICATION_SERVER_KEY", "", application.push["application-server-key"]],
    ["APP_PUSH_DELIVERY_PRIVATE_KEY", "", application.push.delivery["private-key"]],
    ["APP_PUSH_DELIVERY_SUBJECT", "", application.push.delivery.subject],
    ["APP_PUSH_DELIVERY_ALLOWED_HOSTS", "fcm.googleapis.com,updates.push.services.mozilla.com,web.push.apple.com", application.push.delivery["allowed-hosts"]],
    ["APP_PUSH_DELIVERY_REQUEST_TIMEOUT_SECONDS", "10", application.push.delivery["request-timeout-seconds"]],
  ]) {
    assertForwardedSetting(key, fallback);
    assert.equal(configuredValue, `\${${key}:${fallback}}`);
  }
  assert.equal(apiEnvironment.APP_BOOTSTRAP_ENABLED, "false");
});

test("the isolated actual API image startup explicitly disables all nine worker and push switches", () => {
  const verifier = repositorySource("scripts/verify-api-runtime-image.ps1");
  const dockerRun = verifier.match(/\$output = & docker run[\s\S]*?\$imageTag\b/)?.[0];
  assert.ok(dockerRun, "inspect the actual docker run invocation, not a comment or unused setting");
  for (const key of switches) {
    assert.match(dockerRun, new RegExp(`--env\\s+(['"])${key}=false\\1`), `image startup must disable ${key}`);
  }
});

test("CI requires real no-start Docker Compose interpolation separately from source tests", () => {
  const workflow = repositoryYaml(".github/workflows/ci.yml");
  const steps = workflow.jobs.web.steps.filter((step) =>
    typeof step.run === "string" && step.run.includes("./scripts/verify-compose-notification-settings.ps1"));
  assert.equal(steps.length, 1, "the web gate must run the dedicated Compose verifier once");
  assert.equal(steps[0].shell, "pwsh");
  assert.equal(steps[0].if, undefined, "Compose configuration evidence is required on normal CI runs too");
});

test("the Compose verifier selects one real executable when Linux provides multiple Docker paths", () => {
  const verifier = repositorySource("scripts/verify-compose-notification-settings.ps1");
  assert.match(verifier, /\$settingsDocker\s*=\s*Get-Command docker -CommandType Application -ErrorAction SilentlyContinue\s*\|\s*Select-Object -First 1/);
  assert.ok(verifier.includes("$start.FileName = $settingsDocker.Source"));
  assert.ok(verifier.includes("$start.Environment.Clear()"));
  assert.ok(verifier.includes("'--env-file',$settingsEmptyEnv"));
  assert.ok(verifier.includes("'config','--format','json'"));
});
