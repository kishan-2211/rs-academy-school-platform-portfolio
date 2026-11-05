import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import yaml from "js-yaml";

const contract = yaml.load(readFileSync(new URL("../../../../packages/contracts/openapi.yaml", import.meta.url), "utf8"));
const models = readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/identity/StaffAccountInvitationModels.java", import.meta.url), "utf8");
const base = "/schools/{schoolId}/staff/{staffId}/account-invitations";
const schema = name => contract.components.schemas[name];
function fields(record) {
  const matches = [...models.matchAll(/\brecord\s+([A-Za-z_]\w*)\s*\(([\s\S]*?)\)\s*\{/g)].filter(match => match[1] === record);
  assert.equal(matches.length, 1, record);
  return matches[0][2].replace(/@\w+(?:\([^)]*\))?\s*/g, "").split(",").map(value => value.trim().match(/(\w+)$/)[1]).sort();
}

test("staff invitation writes and existing acceptance require the current session and CSRF", () => {
  const writes = [
    contract.paths[base].post,
    contract.paths[base + "/{invitationId}/revocations"].post,
    contract.paths[base + "/{invitationId}/handoffs"].post,
    contract.paths["/staff-account-invitations/acceptances"].post,
  ];
  for (const operation of writes) {
    assert.deepEqual(operation.security, [{ sessionCookie: [] }]);
    assert.ok(operation.parameters.some(value => value.$ref === "#/components/parameters/CsrfToken"));
    assert.ok(operation.responses["401"]);
    assert.ok(operation.responses["403"]);
  }
  assert.deepEqual(contract.paths[base].get.security, [{ sessionCookie: [] }]);
  for (const path of ["/public/staff-account-invitations/previews", "/public/staff-account-invitations/acceptances"]) {
    assert.deepEqual(contract.paths[path].post.security, []);
    assert.ok(contract.paths[path].post.responses["410"]);
  }
});

test("existing-account acceptance has no password or implicit login contract", () => {
  const accept = contract.paths["/staff-account-invitations/acceptances"].post;
  assert.equal(accept.requestBody.content["application/json"].schema.$ref, "#/components/schemas/StaffAccountInvitationTokenRequest");
  assert.deepEqual(fields("ExistingAcceptanceRequest"), ["token"]);
  assert.deepEqual(Object.keys(schema("StaffAccountInvitationTokenRequest").properties), ["token"]);
  assert.match(accept.description, /MFA/);
  assert.equal(schema("StaffAccountInvitationAcceptance").properties.nextAction.const, "sign_in");
  assert.deepEqual(Object.keys(schema("StaffAccountInvitationAcceptance").properties).sort(), ["nextAction", "status"]);
});

test("staff response properties match Java records and ordinary views contain no setup material", () => {
  for (const [record, name] of [
    ["Invitation", "StaffAccountInvitation"],
    ["ListResponse", "StaffAccountInvitationList"],
    ["HandoffResponse", "StaffAccountInvitationHandoff"],
    ["PreviewResponse", "StaffAccountInvitationPreview"],
    ["AcceptanceResponse", "StaffAccountInvitationAcceptance"],
  ]) {
    assert.deepEqual(Object.keys(schema(name).properties).sort(), fields(record), name);
    assert.deepEqual([...schema(name).required].sort(), fields(record), name);
  }
  for (const name of ["StaffAccountInvitation", "StaffAccountInvitationList", "StaffAccountInvitationPreview"]) {
    assert.ok(Object.keys(schema(name).properties).every(key => !/token|password|cipher|nonce|email/i.test(key)), name);
  }
  assert.match(contract.paths[base + "/{invitationId}/handoffs"].post.description, /never reveal it again/);
});

test("invitation inputs are bounded, purpose-specific and never allow arbitrary capabilities", () => {
  const issue = schema("IssueStaffAccountInvitationRequest");
  assert.deepEqual(Object.keys(issue.properties).sort(), fields("IssueRequest"));
  assert.equal(issue.additionalProperties, false);
  assert.equal(issue.properties.ttlHours.minimum, 1);
  assert.equal(issue.properties.ttlHours.maximum, 168);
  assert.equal(issue.properties.expectedStaffVersion.minimum, 0);
  assert.deepEqual(schema("StaffAccountInvitationRoleCode").enum,
    ["school_admin", "admissions_manager", "teacher", "finance_officer", "principal_director", "auditor", "staff_operations"]);
  assert.deepEqual(Object.keys(schema("AcceptNewStaffAccountInvitationRequest").properties).sort(), fields("NewAcceptanceRequest"));
  assert.ok(schema("AcceptNewStaffAccountInvitationRequest").properties.password.writeOnly);
  assert.equal(schema("StaffAccountInvitationTokenRequest").properties.token.minLength, 43);
});

test("successful staff setup responses remain private and do not return sessions", () => {
  for (const path of [base, base + "/{invitationId}/revocations", base + "/{invitationId}/handoffs",
    "/public/staff-account-invitations/previews", "/public/staff-account-invitations/acceptances",
    "/staff-account-invitations/acceptances"]) {
    for (const method of ["get", "post"]) {
      const operation = contract.paths[path][method];
      if (!operation) continue;
      const response = operation.responses["200"] ?? operation.responses["201"];
      assert.equal(response.headers["Cache-Control"].schema.const, "private, no-store");
      assert.equal(response.headers["X-Content-Type-Options"].schema.const, "nosniff");
      assert.match(response.headers.Vary.description, /Cookie/);
      assert.equal(response.headers["Set-Cookie"], undefined);
    }
  }
});
