import assert from "node:assert/strict";
import test from "node:test";
import {readFileSync} from "node:fs";
import ts from "typescript";
import {settlementComponentKeys,settlementPreparationStates} from "./gateway-settlement-preparations.ts";

const java=readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/finance/GatewaySettlementPreparationModels.java",import.meta.url),"utf8"),bridge=readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/accounting/AccountingModuleBridge.java",import.meta.url),"utf8");
const client=ts.createSourceFile("preparations.ts",readFileSync(new URL("./gateway-settlement-preparations.ts",import.meta.url),"utf8"),ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
function properties(record,source=java){const matches=[...source.matchAll(/\brecord\s+([A-Za-z_]\w*)\s*\(([\s\S]*?)\)\s*\{/g)].filter(match=>match[1]===record);assert.equal(matches.length,1,record);return matches[0][2].split(",").map(field=>field.trim().match(/(\w+)$/)[1]).sort();}
for(const[record,alias,source]of[["Context","SettlementPreparationContext",java],["Preparation","SettlementPreparation",java],["ComponentFact","SettlementComponentFact",java],["Row","SettlementPreparationRow",java],["SourceIdentity","SettlementSourceIdentity",java],["PreparationDetail","SettlementPreparationDetail",java],["SettlementAccountingOptions","SettlementAccountingOptions",bridge],["SettlementFinancialYear","SettlementAccountingYear",bridge],["SettlementAccountOption","SettlementAccountingAccount",bridge]]){
  test("preparation "+record+" wire keys exactly match current Java and strict TypeScript DTO",()=>{
    const type=client.statements.find(node=>ts.isTypeAliasDeclaration(node)&&node.name.text===alias);assert.ok(type&&ts.isTypeLiteralNode(type.type),alias);assert.deepEqual(type.type.members.map(member=>member.name.getText(client)).sort(),properties(record,source));
  });
}
test("review component keys and public journal states stay fixed without browser-owned journal lines",()=>{
  const terms=readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/finance/GatewaySettlementPreparationTerms.java",import.meta.url),"utf8");
  const block=terms.match(/KEYS=List\.of\(([\s\S]*?)\);/);assert.ok(block);assert.deepEqual([...block[1].matchAll(/"([A-Z_]+)"/g)].map(match=>match[1]),settlementComponentKeys);
  assert.deepEqual(settlementPreparationStates,["SUBMITTED","POSTED","REJECTED","REVERSED","ABANDONED"]);
  const command=java.match(/public record Command\(([\s\S]*?)\) \{/)[1];assert.doesNotMatch(command,/journalLines|clearingAccountId|paymentMatches/);
});
