import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import yaml from "js-yaml";
import ts from "typescript";

const contract=yaml.load(readFileSync(new URL("../../../../packages/contracts/openapi.yaml",import.meta.url),"utf8"));
const client=ts.createSourceFile("gateway-settlements.ts",readFileSync(new URL("./gateway-settlements.ts",import.meta.url),"utf8"),ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
test("report endpoints retain real session security, independent write capability documentation and CSRF",()=>{
  const base="/finance/gateway/settlements";
  for(const [path,method] of [[base,"get"],[base+"/requests","post"],[base+"/requests/{requestId}","get"]]){
    const operation=contract.paths[path][method];assert.deepEqual(operation.security,[{sessionCookie:[]}]);assert.equal(operation.responses["200"].headers["Cache-Control"].schema.const,"private, no-store");assert.ok(operation.responses["401"]);assert.ok(operation.responses["403"]);
  }
  const write=contract.paths[base+"/requests"].post;assert.ok(write.parameters.some(p=>p.$ref==="#/components/parameters/CsrfToken"));assert.match(write.description,/fees.close.propose/);assert.match(write.description,/UNKNOWN/);assert.equal(write.requestBody.content["application/json"].schema.$ref,"#/components/schemas/GatewaySettlementSubmit");
});
test("all typed response DTO properties are explicit required contract properties without extra private data",()=>{
  for(const name of ["SettlementRequest","SettlementQueue","SettlementSummary","SettlementEvent","SettlementRow","SettlementReport","SettlementResult"]){
    const alias=client.statements.find(node=>ts.isTypeAliasDeclaration(node)&&node.name.text===name);assert.ok(alias&&ts.isTypeLiteralNode(alias.type),name);
    const names=alias.type.members.map(member=>member.name.getText(client)).sort(),schema=contract.components.schemas["Gateway"+name];
    assert.equal(schema.additionalProperties,false,name);assert.deepEqual(Object.keys(schema.properties).sort(),names,name);assert.deepEqual([...schema.required].sort(),names,name);
    assert.ok(names.every(key=>!/credential|merchant|cursor|customer|cardNumber|phone|bankAccount/i.test(key)),name);
  }
});
test("provider decimals stay nullable signed strings, not posted-number amounts or an invented summary currency",()=>{
  const money=contract.components.schemas.GatewaySettlementReportedMoney;assert.deepEqual(money.type,["string","null"]);
  const valid=new RegExp(money.pattern);for(const text of ["0.00000000","100000000.00000000","-0.12345678","98.12345678"])assert.ok(valid.test(text),text);
  for(const text of ["100000000.00000001","1.123456789","1e2","999999999.99"])assert.equal(valid.test(text),false,text);
  const summary=contract.components.schemas.GatewaySettlementSummary;assert.equal(summary.properties.currencyCode,undefined);
  for(const [key,value] of Object.entries(summary.properties))if(key.startsWith("reported")&&!/Status|Type/.test(key))assert.equal(value.$ref,"#/components/schemas/GatewaySettlementReportedMoney",key);
});
test("request and physical-row contracts preserve exact replay identity, zero-based row bounds and exclusive shapes",()=>{
  const submit=contract.components.schemas.GatewaySettlementSubmit;assert.equal(submit.additionalProperties,false);assert.ok(submit.required.includes("clientRequestId"));assert.ok(submit.required.includes("schoolTimeZone"));assert.equal(submit.oneOf.length,2);
  const row=contract.components.schemas.GatewaySettlementRow;assert.equal(row.properties.rowIndex.minimum,0);assert.equal(row.properties.rowIndex.maximum,9);assert.equal(row.oneOf.length,2);
  assert.equal(contract.components.schemas.GatewaySettlementQueue.properties.query.type,"string");assert.equal(contract.components.schemas.GatewaySettlementEvent.properties.refundReferences.uniqueItems,undefined);
});
