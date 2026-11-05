import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import yaml from "js-yaml";
import ts from "typescript";
import {bankReviewReasons} from "./gateway-settlement-bank-evidence.ts";

const contract=yaml.load(readFileSync(new URL("../../../../packages/contracts/openapi.yaml",import.meta.url),"utf8"));
const schemas=contract.components.schemas;
const model=readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/finance/GatewaySettlementBankEvidenceModels.java",import.meta.url),"utf8");
const root="/finance/gateway/settlements/requests/{requestId}/bank-evidence";
const controller=readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/finance/GatewaySettlementBankEvidenceController.java",import.meta.url),"utf8");
test("bank attestation routes are private and preserve real scoped human capability and CSRF boundaries",()=>{
  assert.deepEqual(Object.keys(contract.paths[root]),["get","post"]);
  assert.deepEqual(Object.keys(contract.paths[`${root}/{evidenceId}/rejections`]),["post"]);
  for(const [operation,decision]of[[contract.paths[root].get,null],[contract.paths[root].post,"fees.close.propose"],[contract.paths[`${root}/{evidenceId}/rejections`].post,"fees.close.approve"]]){
    assert.deepEqual(operation.security,[{sessionCookie:[]}]);
    for(const capability of["fees.read","accounting.read",...(decision?[decision]:[])]){assert.ok(operation.description.includes(capability));assert.ok(controller.includes(`CAPABILITY_${capability}`));}
    assert.equal(operation.responses["200"].headers["Cache-Control"].schema.const,"private, no-store");
    assert.equal(operation.responses["200"].headers["X-Content-Type-Options"].schema.const,"nosniff");
    assert.match(operation.responses["200"].headers.Vary.description,/Cookie/);
    if(decision)assert.equal(operation.parameters.find(p=>p.name==="X-CSRF-TOKEN").required,true);else assert.equal(operation.requestBody,undefined);
  }
  assert.match(contract.paths[root].post.description,/including reversed receipts/);
  assert.match(contract.paths[`${root}/{evidenceId}/rejections`].post.description,/different actual user/);
  assert.match(contract.paths[root].post.description,/never a receipt, fee credit/);
});
test("bank contract exact response and command fields agree with Java and browser DTOs",()=>{
  const client=ts.createSourceFile("bank.ts",readFileSync(new URL("./gateway-settlement-bank-evidence.ts",import.meta.url),"utf8"),ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
  for(const[name,schemaName,aliasName]of[["Context","GatewaySettlementBankContext","SettlementBankContext"],["Evidence","GatewaySettlementBankEvidence","SettlementBankEvidence"],["Summary","GatewaySettlementBankSummary","SettlementBankSummary"],["BankAccount","GatewaySettlementBankAccount","SettlementBankAccount"],["Rejection","GatewaySettlementBankRejection","SettlementBankRejection"],["Command","GatewaySettlementBankCommand",null],["RejectionCommand","GatewaySettlementBankRejectionCommand",null]]){
    const header=model.match(new RegExp(`public record ${name}\\(([\\s\\S]*?)\\)\\s*\\{`));assert.ok(header,name);
    const fields=header[1].replace(/@\w+(?:\([^)]*\))?\s*/g,"").split(",").map(part=>part.trim().match(/(\w+)$/)[1]).sort();
    const schema=schemas[schemaName];assert.equal(schema.additionalProperties,false);assert.deepEqual(Object.keys(schema.properties).sort(),fields,schemaName);assert.deepEqual([...schema.required].sort(),fields);
    if(aliasName){const alias=client.statements.find(n=>ts.isTypeAliasDeclaration(n)&&n.name.text===aliasName);assert.ok(alias&&ts.isTypeLiteralNode(alias.type));assert.deepEqual(alias.type.members.map(m=>m.name.getText(client)).sort(),fields);}
    assert.ok(fields.every(key=>!/password|cardNumber|cvv|pin|credential|merchant|cursor|accountNumber|canPost/i.test(key)));
  }
});
test("bank schemas expose bounded evidence and explicit non-posting correction states",()=>{
  assert.deepEqual(schemas.GatewaySettlementBankReviewReason.enum,[...bankReviewReasons]);
  for(const reason of bankReviewReasons)assert.ok(model.includes(`"${reason}"`));
  assert.equal(schemas.GatewaySettlementBankContext.properties.evidence.maxItems,25);
  assert.equal(schemas.GatewaySettlementBankContext.properties.bankAccounts.maxItems,1000);
  assert.equal(schemas.GatewaySettlementBankSummary.properties.rowIndex.maximum,9);
  assert.deepEqual(schemas.GatewaySettlementBankEvidence.properties.status.enum,["BANK_EVIDENCE_RECORDED","REJECTED"]);
  assert.equal(schemas.GatewaySettlementBankEvidence.oneOf[1].properties.canReject.const,false);
  assert.equal(schemas.GatewaySettlementBankCommand.properties.confirmedReceived.const,true);
  assert.equal(schemas.GatewaySettlementBankRejectionCommand.properties.confirmedMistakenEvidence.const,true);
  assert.match(schemas.GatewaySettlementBankEvidence.description,/SOURCE_RECONCILIATION_PENDING/);
});
test("exact positive paise string constraints do not permit rounding or an over-limit bank claim",()=>{
  const amount=schemas.GatewaySettlementBankCommand.properties.creditedAmount;
  const accepts=value=>new RegExp(amount.pattern).test(value)&&!amount.not.enum.includes(value);
  for(const value of["0.01","0.1","12","12.34","99999999.99","100000000.00"])assert.ok(accepts(value),value);
  for(const value of["0","0.0","0.00","-1","1.001","01.00","1e2","100000000.01","999999999.00"])assert.equal(accepts(value),false,value);
});
