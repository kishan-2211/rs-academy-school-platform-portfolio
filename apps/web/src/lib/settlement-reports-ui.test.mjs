import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as client from "./gateway-settlements.ts";
import { settlementMessage, gatewaySettlementMessages } from "./i18n/gateway-settlement-messages.ts";

// Synthetic static-render tests are not authenticated browser/mobile acceptance.
const source=readFileSync(new URL("../components/workspace/settlement-reports-panel.tsx",import.meta.url),"utf8");
const css=readFileSync(new URL("../components/workspace/settlement-reports-panel.module.css",import.meta.url),"utf8");
const ast=ts.createSourceFile("settlement-reports-panel.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX),states=[];
function visit(node){if(ts.isVariableDeclaration(node)&&ts.isArrayBindingPattern(node.name)&&node.initializer&&ts.isCallExpression(node.initializer)&&node.initializer.expression.getText(ast)==="useState")states.push(node.name.elements[0].getText(ast));ts.forEachChild(node,visit);}
visit(ast);
const school="89000000-0000-4000-8000-000000000001",requestId="89000000-0000-4000-8000-000000000002",readId="89000000-0000-4000-8000-000000000003",at="2026-10-22T04:17:39.123456789Z";
const request={id:requestId,kind:"SUMMARY",schoolTimeZone:"Asia/Kolkata",status:"COMPLETE",from:"2026-10-01T00:00:00Z",until:"2026-10-15T00:00:00Z",sourceSummaryReadId:null,settlementReference:null,requestedAt:at,requestedBy:"Synthetic accountant",pagesCompleted:1,pagesPending:0,pagesReview:0,lastCheckedAt:at,nextCheckAt:null};
const queue={schoolId:school,schoolTimeZone:"Asia/Kolkata",syncEnabled:true,canSync:true,status:"ALL",kind:"ALL",query:"",page:0,pageSize:25,total:1,hasMore:false,items:[request]};
const summary={settlementReference:"synthetic_statement",reportedStatus:"SUCCESS",transferReference:"synthetic_utr",paymentsFrom:null,paymentsThrough:null,initiatedAt:null,processedAt:at,reportedPaymentAmount:"100.00000000",reportedServiceCharge:"1.12345678",reportedServiceTax:null,reportedAdjustment:"-0.00000001",reportedSettlementCharge:null,reportedSettlementTax:null,reportedSplitCharge:null,reportedSplitTax:null,reportedVendorCommission:null,reportedAmountSettled:"98.87654321",reportedChargeType:"PREPAID",reportedSettlementType:"STANDARD"};
const row={sourceReadId:readId,rowIndex:0,observedAt:at,summary,event:null};
const report={schoolId:school,request,query:"",page:0,pageSize:25,total:1,hasMore:false,items:[row]};
function render(locale="en",changes={}){
  let call=0;const values={busy:false,...changes},exports={},require=createRequire(import.meta.url);
  const modules={react:{...React,useState:initial=>[Object.hasOwn(values,states[call])?values[states[call++]]:(call++,initial),()=>{}]},
    "@/components/i18n/language-provider":{useLanguage:()=>({locale})},
    "@/lib/family-gateway-payments":{familyPaymentCsrf:()=>{throw new Error("No requests in static render");}},
    "@/lib/gateway-settlements":client,"@/lib/i18n/gateway-settlement-messages":{settlementMessage},
    "./settlement-source-review-panel":{SettlementSourceReviewPanel:props=>React.createElement("section",{"data-source-review":props.requestId,"data-school":props.schoolId,"data-disabled":String(props.disabled)},"Source-identification child boundary")},
    "./settlement-preparation-panel":{SettlementPreparationPanel:props=>React.createElement("section",{"data-preparation":props.requestId,"data-school":props.schoolId,"data-preparation-disabled":String(props.disabled)},"Preparation child boundary")},
    "./settlement-bank-evidence-panel":{SettlementBankEvidencePanel:props=>React.createElement("section",{"data-bank-evidence":props.requestId,"data-school":props.schoolId,"data-bank-disabled":String(props.disabled)},"Bank-evidence child boundary")},
    "./settlement-reports-panel.module.css":{default:new Proxy({}, {get:(_,key)=>String(key)})}};
  const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
  new Function("require","exports",compiled)(name=>{if((name.startsWith("@/")||name.endsWith(".css"))&&!modules[name])throw new Error(`Unreviewed dependency ${name}`);return modules[name]??require(name);},exports);
  return renderToStaticMarkup(React.createElement(exports.SettlementReportsPanel,{schoolId:school,onGuardChange:()=>{}}));
}
test("both languages retain report-only boundary, labels, loading and controlled errors",()=>{
  assert.deepEqual(Object.keys(gatewaySettlementMessages.en).sort(),Object.keys(gatewaySettlementMessages.hi).sort());
  for(const locale of ["en","hi"]){const html=render(locale,{busy:true,error:"scope"});for(const key of ["title","boundary","loading","error.scope","syncForm","searchHelp"])assert.ok(html.includes(settlementMessage(locale,key)),key);assert.match(html,/role="alert"/);assert.match(html,/aria-busy="true"/);}
});
test("unconfigured empty school stays inspectable without activation or guessed results",()=>{
  const html=render("en",{queue:{...queue,syncEnabled:false,canSync:false,total:0,items:[]}});
  assert.ok(html.includes(settlementMessage("en","disabled")));assert.ok(html.includes(settlementMessage("en","empty")));assert.match(html,/<fieldset disabled=""/);assert.doesNotMatch(html,/synthetic_statement/);
});
test("complete scan is clearly not bank reconciliation and carries selected-state semantics",()=>{
  const html=render("en",{queue,selectedId:requestId});assert.ok(html.includes(settlementMessage("en","state.COMPLETE")));assert.match(html,/aria-pressed="true"/);assert.match(html,/Synthetic accountant/);assert.match(html,/2026-10-22T04:17:39.123456789Z/);
});
test("summary evidence preserves eight decimal places, signed adjustment and missing currency",()=>{
  const html=render("en",{queue,report,selectedId:requestId});assert.match(html,/98\.87654321/);assert.match(html,/-0\.00000001/);assert.ok(html.includes(settlementMessage("en","currencyMissing")));assert.match(html,/<details>/);assert.match(html,/Request this statement/);assert.doesNotMatch(html,/₹|Mark.*reconciled|Post.*journal/);
});
test("event evidence retains supplied currency and unknown provider type without inventing bank success",()=>{
  const eventRequest={...request,kind:"EVENTS",from:null,until:null,sourceSummaryReadId:readId,settlementReference:"synthetic_statement"};
  const event={settlementReference:"synthetic_statement",eventReference:"synthetic_event",reportedType:"NEW_KIND",reportedDirection:"DEBIT",reportedStatus:"NEW_STATUS",currencyCode:"USD",reportedAmount:"-7.12345678",reportedServiceCharge:null,reportedServiceTax:null,reportedSettlementAmount:null,occurredAt:at,orderReference:null,paymentReference:null,refundReferences:[],chargesCurrencyCode:null};
  const html=render("hi",{queue,selectedId:requestId,report:{...report,request:eventRequest,items:[{...row,summary:null,event}]}});assert.match(html,/USD/);assert.match(html,/NEW_KIND/);assert.match(html,/-7\.12345678/);assert.match(html,/synthetic_event/);assert.doesNotMatch(html,/₹/);
});
test("unknown request locks context and offers exact retry rather than another new scan",()=>{
  const html=render("en",{queue,locked:true,error:"unknown"});assert.ok(html.includes(settlementMessage("en","retry")));assert.ok(html.includes(settlementMessage("en","unknownHelp")));assert.match(html,/<fieldset disabled=""/);
  assert.match(source,/attempt\.current = next/);assert.match(source,/dispatch\(attempt\.current\)/);assert.match(source,/onGuardChange\(true\)/);assert.match(source,/beforeunload/);
});
test("source identification is mounted only for the selected complete EVENTS report and guards parent context",()=>{
  const events={...request,kind:"EVENTS",from:null,until:null,sourceSummaryReadId:readId,settlementReference:"synthetic_statement"};
  const complete=render("en",{queue,selectedId:requestId,report:{...report,request:events,items:[]}});assert.match(complete,/data-source-review=/);assert.match(complete,new RegExp(`data-school="${school}"`));
  for(const current of[request,{...events,status:"RETRYING"},{...events,status:"REVIEW_REQUIRED"}])assert.doesNotMatch(render("en",{queue,selectedId:requestId,report:{...report,request:current,items:[]}}),/data-source-review=/);
  const guarded=render("en",{queue,sourceBusy:true,selectedId:requestId,report:{...report,request:events,items:[]}});assert.match(guarded,/<fieldset disabled/);assert.match(guarded,/data-disabled="false"/);
  assert.match(source,/sourceReading\.current = blocked/);assert.match(source,/onGuardChange\(blocked \|\| bankReading\.current \|\| preparationReading\.current \|\| writing\.current \|\| attempt\.current !== null\)/);assert.match(source,/if \(authorizationLost\.current && attempt\.current !== next \|\| preparationReading\.current \|\| bankReading\.current \|\| sourceReading\.current \|\| writing\.current/);
});
test("bank evidence preserves complete-events-only scope and merges source/write/read context guards",()=>{
  const events={...request,kind:"EVENTS",from:null,until:null,sourceSummaryReadId:readId,settlementReference:"synthetic_statement"};
  const complete=render("en",{queue,selectedId:requestId,report:{...report,request:events,items:[]}});assert.match(complete,/data-bank-evidence=/);assert.match(complete,/data-bank-disabled="false"/);
  for(const current of[request,{...events,status:"RETRYING"},{...events,status:"REVIEW_REQUIRED"}])assert.doesNotMatch(render("en",{queue,selectedId:requestId,report:{...report,request:current,items:[]}}),/data-bank-evidence=/);
  const bankGuard=render("en",{queue,bankBusy:true,selectedId:requestId,report:{...report,request:events,items:[]}});assert.match(bankGuard,/<fieldset disabled/);assert.match(bankGuard,/data-disabled="true"/);assert.match(bankGuard,/data-bank-disabled="false"/);
  const sourceGuard=render("en",{queue,sourceBusy:true,selectedId:requestId,report:{...report,request:events,items:[]}});assert.match(sourceGuard,/data-bank-disabled="true"/);
  assert.match(source,/bankReading\.current = blocked/);assert.match(source,/onGuardChange\(blocked \|\| sourceReading\.current \|\| preparationReading\.current \|\| writing\.current \|\| attempt\.current !== null\)/);assert.match(source,/if \(!contextBlocked && !bankReading\.current && !sourceReading\.current\)/);
});
test("shell keeps settlement command keyed to school and blocks unrelated mutations while unknown",()=>{
  const shell=readFileSync(new URL("../components/workspace/finance-workspace.tsx",import.meta.url),"utf8");assert.match(shell,/<SettlementReportsPanel key=\{data\.schoolId\} schoolId=\{data\.schoolId\} onGuardChange=\{settlementGuardChange\}/);assert.match(shell,/collectionIntentBlocked \|\| settlementBlocked/);assert.match(shell,/guardedRefund\.current \|\| guardedSettlement\.current/);
});
test("component styling uses shared theme tokens, phone stacking and visible keyboard focus without motion",()=>{
  assert.match(css,/var\(--theme-card\)/);assert.match(css,/var\(--theme-error-text\)/);assert.match(css,/min-height:44px/);assert.match(css,/:focus-visible/);assert.match(css,/@media\(max-width:600px\)/);assert.match(css,/prefers-reduced-motion:reduce/);assert.doesNotMatch(css,/#(?:[0-9a-f]{3}){1,2}\b/i);
});
test("authorization loss hides cached private reports and child contexts in both languages even during retry loading",()=>{
  const events={...request,kind:"EVENTS",from:null,until:null,sourceSummaryReadId:readId,settlementReference:"synthetic_statement"};
  for(const locale of ["en","hi"])for(const code of ["session","scope"])for(const retrying of [false,true]){
    for(const current of [report,{...report,request:events,items:[]}]){
      const html=render(locale,{queue,report:current,selectedId:requestId,error:retrying?null:code,accessError:code,busy:retrying});
      assert.doesNotMatch(html,/synthetic_statement|synthetic_utr|Synthetic accountant|data-source-review=|data-bank-evidence=|data-preparation=/);
      assert.ok(html.includes(settlementMessage(locale,"error."+code)));
    }
    const immediate=render(locale,{queue,report,selectedId:requestId,error:code});
    assert.doesNotMatch(immediate,/synthetic_statement|synthetic_utr|Synthetic accountant/);
  }
});
