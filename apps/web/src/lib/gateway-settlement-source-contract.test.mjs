import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import yaml from "js-yaml";
import ts from "typescript";

const contract = yaml.load(readFileSync(new URL("../../../../packages/contracts/openapi.yaml", import.meta.url), "utf8"));
const schemas = contract.components.schemas;
const model = readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/finance/GatewaySettlementSourceReviewModels.java", import.meta.url), "utf8");
const expectedReasons = [
  "UNSUPPORTED_PROVIDER", "REFUND_ASSOCIATION_REVIEW", "UNSUPPORTED_EVENT_TYPE",
  "UNSUPPORTED_EVENT_DIRECTION", "UNSUPPORTED_EVENT_STATUS", "UNSUPPORTED_CURRENCY",
  "FRACTIONAL_PAISE", "NON_POSITIVE_GROSS", "MISSING_PAYMENT_REFERENCES",
  "DUPLICATE_PAYMENT_SOURCE", "SOURCE_NOT_FOUND_OR_OUT_OF_SCOPE", "AMBIGUOUS_PAYMENT_SOURCE",
  "UNRESOLVED_NUMBERED_RECEIPT", "GROSS_MISMATCH", "ORIGINAL_POSTING_INVALID",
  "CLEARING_EVIDENCE_UNAVAILABLE", "LEGACY_BANK_CLEARING_REVIEW", "INVALID_CLEARING_IDENTITY",
];

test("source review is a bounded private session GET requiring both live read capabilities, never a write", () => {
  const route = contract.paths["/finance/gateway/settlements/requests/{requestId}/source-review"];
  assert.deepEqual(Object.keys(route), ["get"]);
  const operation = route.get;
  assert.deepEqual(operation.security, [{ sessionCookie: [] }]);
  for (const capability of ["fees.read", "accounting.read"]) assert.ok(operation.description.includes(capability));
  assert.match(operation.description, /full unfiltered selected COMPLETE EVENTS chain before pagination/);
  assert.match(operation.description, /Refund associations always require review/);
  assert.match(operation.description, /no provider call/);
  assert.equal(operation.responses["200"].headers["Cache-Control"].schema.const, "private, no-store");
  assert.equal(operation.responses["200"].headers["X-Content-Type-Options"].schema.const, "nosniff");
  assert.equal(operation.responses["200"].content["application/json"].schema.$ref, "#/components/schemas/GatewaySettlementSourceReview");
  for (const code of ["400", "401", "403", "404", "409", "503"]) assert.ok(operation.responses[code], code);
  assert.deepEqual(operation.parameters.map(parameter => parameter.name), ["requestId", "schoolId", "page", "pageSize"]);
  assert.equal(operation.parameters.find(parameter => parameter.name === "schoolId").required, true);
  assert.deepEqual(operation.parameters.find(parameter => parameter.name === "page").schema, { type: "integer", minimum: 0, maximum: 10000, default: 0 });
  assert.deepEqual(operation.parameters.find(parameter => parameter.name === "pageSize").schema, { type: "integer", minimum: 1, maximum: 50, default: 25 });
  assert.equal(operation.requestBody, undefined);
});

test("source review contract properties match the exact Java and browser DTOs without private or posting fields", () => {
  const client = ts.createSourceFile("gateway-settlement-source-review.ts", readFileSync(new URL("./gateway-settlement-source-review.ts", import.meta.url), "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  for (const [record, schemaName, aliasName] of [
    ["Review", "GatewaySettlementSourceReview", "SettlementSourceReview"],
    ["Row", "GatewaySettlementSourceReviewRow", "SettlementSourceReviewRow"],
    ["Source", "GatewaySettlementSourceReviewSource", "SettlementIdentifiedSource"],
  ]) {
    const header = model.match(new RegExp(`public record ${record}\\(([\\s\\S]*?)\\)\\s*\\{`));
    assert.ok(header, record);
    const javaKeys = header[1].split(",").map(component => component.trim().match(/(\w+)$/)[1]).sort();
    const schema = schemas[schemaName];
    assert.equal(schema.additionalProperties, false, schemaName);
    assert.deepEqual(Object.keys(schema.properties).sort(), javaKeys, schemaName);
    assert.deepEqual([...schema.required].sort(), javaKeys, schemaName);
    const alias = client.statements.find(node => ts.isTypeAliasDeclaration(node) && node.name.text === aliasName);
    assert.ok(alias && ts.isTypeLiteralNode(alias.type), aliasName);
    assert.deepEqual(alias.type.members.map(member => member.name.getText(client)).sort(), javaKeys, aliasName);
    assert.ok(javaKeys.every(key => !/canPost|canWrite|bank|netAmount|credential|merchant|cursor|customer|cardNumber|phone/i.test(key)), schemaName);
  }
});

test("source identification has a fixed nonfinancial stage, complete-chain counts and exclusive null/reason states", () => {
  assert.equal(schemas.GatewaySettlementSourceReviewStage.const, "SOURCE_IDENTIFICATION_ONLY");
  const review = schemas.GatewaySettlementSourceReview;
  assert.equal(review.properties.reviewStage.$ref, "#/components/schemas/GatewaySettlementSourceReviewStage");
  assert.deepEqual(review.properties.pagesRead, { type: "integer", minimum: 1, maximum: 1000 });
  for (const name of ["totalRows", "sourceIdentifiedRows", "reviewRequiredRows"]) {
    assert.equal(review.properties[name].maximum, 10000, name);
    assert.equal(review.properties[name].minimum, 0, name);
  }
  assert.match(review.description, /before pagination/);
  assert.match(review.description, /sourceIdentifiedRows plus reviewRequiredRows equals totalRows/);
  const row = schemas.GatewaySettlementSourceReviewRow;
  assert.equal(row.properties.rowIndex.minimum, 0);
  assert.equal(row.properties.rowIndex.maximum, 9);
  assert.equal(row.properties.event.$ref, "#/components/schemas/GatewaySettlementEvent");
  assert.deepEqual(row.oneOf, [
    { properties: { status: { const: "SOURCE_IDENTIFIED" }, reasons: { maxItems: 0 }, source: { type: "object" } } },
    { properties: { status: { const: "REVIEW_REQUIRED" }, reasons: { minItems: 1 }, source: { type: "null" } } },
  ]);
  assert.deepEqual(schemas.GatewaySettlementSourceReviewReason.enum, expectedReasons);
  assert.equal(new Set(expectedReasons).size, expectedReasons.length);
  assert.equal(row.properties.reasons.items.$ref, "#/components/schemas/GatewaySettlementSourceReviewReason");
  assert.equal(row.properties.reasons.uniqueItems, true);
});

test("review reasons stay exhaustive and identical across the evaluator, browser and published contract", () => {
  const evaluator = readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/finance/GatewaySettlementSourceReviewEvaluator.java", import.meta.url), "utf8");
  const allowed = evaluator.match(/\bREASONS\s*=\s*java\.util\.Set\.of\(([\s\S]*?)\);/);
  assert.ok(allowed);
  const backendReasons = [...allowed[1].matchAll(/"([A-Z_]+)"/g)].map(match => match[1]);
  const client = ts.createSourceFile("gateway-settlement-source-review.ts", readFileSync(new URL("./gateway-settlement-source-review.ts", import.meta.url), "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const statement = client.statements.find(node => ts.isVariableStatement(node) && node.declarationList.declarations.some(declaration => declaration.name.getText(client) === "settlementSourceReviewReasons"));
  const declaration = statement?.declarationList.declarations.find(node => node.name.getText(client) === "settlementSourceReviewReasons");
  assert.ok(declaration && ts.isAsExpression(declaration.initializer) && ts.isArrayLiteralExpression(declaration.initializer.expression));
  const browserReasons = declaration.initializer.expression.elements.map(node => { assert.ok(ts.isStringLiteral(node)); return node.text; });
  for (const actual of [backendReasons, browserReasons, schemas.GatewaySettlementSourceReviewReason.enum]) {
    assert.deepEqual([...actual].sort(), [...expectedReasons].sort());
    assert.equal(new Set(actual).size, actual.length);
  }
});

test("identified gross uses exact historical paise precision while reported event amounts retain their own precision", () => {
  const source = schemas.GatewaySettlementSourceReviewSource;
  assert.equal(source.properties.grossAmount.type, "string");
  const exact = new RegExp(source.properties.grossAmount.pattern);
  for (const valid of ["0.01", "100.00", "123.45", "99999999.99", "100000000.00"]) assert.ok(exact.test(valid), valid);
  for (const invalid of ["0.00", "00.01", "1", "1.2", "1.234", "-1.00", "1e2", "NaN", "100000000.01", "100000001.00"]) assert.equal(exact.test(invalid), false, invalid);
  assert.equal(source.properties.currencyCode.const, "INR");
  assert.equal(source.properties.clearingAccountCategory.const, "OTHER");
  assert.deepEqual(source.properties.accountingBasis.enum, ["CASH", "ACCRUAL"]);
  assert.equal(schemas.GatewaySettlementEvent.properties.currencyCode.const, undefined);
  assert.equal(schemas.GatewaySettlementEvent.properties.reportedAmount.allOf[0].$ref, "#/components/schemas/GatewaySettlementReportedMoney");
  assert.deepEqual(schemas.GatewaySettlementReportedMoney.type, ["string", "null"]);
});
