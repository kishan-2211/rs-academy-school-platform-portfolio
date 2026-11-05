import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import yaml from "js-yaml";
import * as client from "./gateway-settlement-refund-candidates.ts";
import { formatSettlementAmount } from "./gateway-settlements.ts";
import { gatewaySettlementRefundCandidateMessages as messages, settlementRefundCandidateMessage as message } from "./i18n/gateway-settlement-refund-candidate-messages.ts";

// Synthetic decoder/component/handler evidence only, never actual browser or provider acceptance.
const schoolId="8e000000-0000-4000-8000-000000000001",requestId="8e000000-0000-4000-8000-000000000002",readId="8e000000-0000-4000-8000-000000000003",other="8e000000-0000-4000-8000-000000000004",settlementReference="synthetic_statement";
const row={sourceReadId:readId,rowIndex:2,observedAt:"2026-10-26T10:00:00Z",event:{settlementReference,eventReference:"synthetic_refund",reportedType:"REFUND",reportedDirection:"DEBIT",reportedStatus:"SUCCESS",currencyCode:"INR",reportedAmount:"50.25000000",orderReference:"synthetic_order",paymentReference:"synthetic_payment",refundReferences:[other]},status:"REVIEW_REQUIRED",reasons:["REFUND_ASSOCIATION_REVIEW"],source:null};
const item={refundPostingId:other,refundId:other,postedOn:"2026-10-26",amount:"50.25",currencyCode:"INR",processedAt:"2026-10-26T09:00:00.123456789Z",accountingState:"POSTED_SOURCE_PROVEN",accountingJournalId:other,referenceMention:"MENTIONED"};
const review={schoolId,requestId,sourceReadId:readId,rowIndex:2,settlementReference,reviewStage:"HISTORICAL_REFUND_CANDIDATES_ONLY",state:"CANDIDATE_CONTEXT",eventReference:"synthetic_refund",reportedAmount:"50.25000000",currencyCode:"INR",receiptNumber:"SYN-REFUND-001",reviewReasons:[],page:0,pageSize:25,hasMore:false,items:[item]};
const decode=(value,selected=row,page=0)=>client.readSettlementRefundCandidates(value,schoolId,requestId,settlementReference,selected,page);
const bad=(value,selected=row)=>assert.throws(()=>decode(value,selected),e=>e.code==="unavailable");
test("candidates preserve exact historical money time and contextual mentions without automatic association",()=>{
  assert.equal(decode(review),review);
  for(const referenceMention of["MENTIONED","NOT_MENTIONED","NOT_REPORTED"])assert.equal(decode({...review,items:[{...item,referenceMention}]}).items[0].referenceMention,referenceMention);
  assert.equal(decode({...review,items:[{...item,accountingState:"ACCOUNTING_REVIEW_REQUIRED",accountingJournalId:null,processedAt:null}]}).items[0].accountingJournalId,null);
  assert.equal(decode({...review,items:[]}).items.length,0);
});
test("all scope row and stage substitutions fail closed even for repeated provider event references",()=>{
  for(const change of[{schoolId:other},{requestId:other},{sourceReadId:other},{rowIndex:1},{settlementReference:"synthetic_other"},{eventReference:"synthetic_other"},{reportedAmount:"50.25"},{currencyCode:"USD"},{reviewStage:"POSTING_ALLOWED"},{state:"ASSOCIATED"}])bad({...review,...change});
  for(const change of[{reportedType:"PAYMENT"},{reportedDirection:"CREDIT"},{reportedStatus:"PENDING"},{currencyCode:"USD"},{orderReference:null},{paymentReference:null}])bad(review,{...row,event:{...row.event,...change}});
});
test("review reasons require empty nonfinancial results and remain translated for every supported reason",()=>{
  for(const reason of client.settlementRefundCandidateReasons){const value={...review,state:"REVIEW_REQUIRED",receiptNumber:null,reviewReasons:[reason],items:[]};assert.equal(decode(value).state,"REVIEW_REQUIRED");assert.match(messages.hi[`reason.${reason}`],/[\u0900-\u097f]/);}
  for(const change of[{state:"REVIEW_REQUIRED"},{state:"REVIEW_REQUIRED",reviewReasons:["UNSUPPORTED_CURRENCY"],items:[]},{state:"REVIEW_REQUIRED",reviewReasons:["UNKNOWN"],items:[],receiptNumber:null},{reviewReasons:["FRACTIONAL_PAISE"]},{receiptNumber:null}])bad({...review,...change});
});
test("wrong amounts currency journal claims timestamps duplicate candidates and private properties are rejected",()=>{
  for(const change of[{amount:"50.24"},{amount:"50.250"},{amount:50.25},{currencyCode:"USD"},{postedOn:"2026-02-30"},{processedAt:"2026-10-26T25:00:00Z"},{accountingJournalId:null},{accountingState:"ACCOUNTING_REVIEW_REQUIRED"},{referenceMention:"AUTO_MATCHED"},{refundId:"bad"},{credentials:"synthetic_private"}])bad({...review,items:[{...item,...change}]});
  bad({...review,items:[item,item]});bad({...review,cursor:"synthetic_private"});bad({...review,receiptNumber:"Synthetic\nreceipt"});
  for(const change of[{page:1},{pageSize:50},{hasMore:true},{reviewReasons:["NON_POSITIVE_AMOUNT","NON_POSITIVE_AMOUNT"]}])bad({...review,...change});
});
test("authenticated bounded GET binds exact physical row and abort signal without any financial body",async()=>{
  const old=globalThis.fetch,signal=new AbortController().signal,calls=[];
  globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(review);};
  try {await client.loadSettlementRefundCandidates(schoolId,requestId,settlementReference,row,0,signal);const call=calls[0];assert.match(call.url,new RegExp(`/requests/${requestId}/refund-candidates\\?schoolId=${schoolId}&sourceReadId=${readId}&rowIndex=2&page=0&pageSize=25$`));assert.equal(call.init.credentials,"include");assert.equal(call.init.cache,"no-store");assert.equal(call.init.signal,signal);assert.equal(call.init.body,undefined);}
  finally{globalThis.fetch=old;}
});
test("denied incomplete malformed and failed reads expose controlled errors without echoing private responses",async()=>{
  const old=globalThis.fetch;
  try {for(const [status,code] of[[401,"session"],[403,"scope"],[409,"incomplete"],[400,"filters"],[503,"unavailable"]]){globalThis.fetch=async()=>Response.json({detail:"synthetic_private"},{status});await assert.rejects(client.loadSettlementRefundCandidates(schoolId,requestId,settlementReference,row,0,new AbortController().signal),e=>e.code===code&&!e.message.includes("private"));}globalThis.fetch=async()=>new Response("malformed");await assert.rejects(client.loadSettlementRefundCandidates(schoolId,requestId,settlementReference,row,0,new AbortController().signal),e=>e.code==="unavailable");}
  finally{globalThis.fetch=old;}
});
const source=readFileSync(new URL("../components/workspace/settlement-refund-candidates-panel.tsx",import.meta.url),"utf8");
const props={schoolId,requestId,settlementReference,row,disabled:false,onGuardChange:()=>{}};
function render(locale="en",changes={}){
  const context={...props},snapshot=changes.data||changes.error?{context:changes.stale?{...context}:context,data:changes.data??null,error:changes.error??null}:null;
  const values=[snapshot,changes.busy?context:null];let index=0;const exports={},require=createRequire(import.meta.url);
  const modules={react:{...React,useMemo:()=>context,useState:initial=>[values[index++]??initial,()=>{}]},"@/components/i18n/language-provider":{useLanguage:()=>({locale})},"@/lib/gateway-settlements":{formatSettlementAmount},"@/lib/gateway-settlement-refund-candidates":client,"@/lib/i18n/gateway-settlement-refund-candidate-messages":{settlementRefundCandidateMessage:message},"./settlement-source-review-panel.module.css":{default:new Proxy({},{get:(_,key)=>String(key)})}};
  const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
  new Function("require","exports",compiled)(name=>{if((name.startsWith("@/")||name.endsWith(".css"))&&!modules[name])throw new Error(`Unexpected dependency ${name}`);return modules[name]??require(name);},exports);
  return renderToStaticMarkup(React.createElement(exports.SettlementRefundCandidatesPanel,props));
}
test("real candidate component renders bilingual nonfinancial evidence and honest loading error and empty states",()=>{
  assert.deepEqual(Object.keys(messages.en).sort(),Object.keys(messages.hi).sort());
  for(const locale of["en","hi"]){const html=render(locale,{data:review});for(const key of["title","boundary","accounting.POSTED_SOURCE_PROVEN","reference.MENTIONED","receipt"])assert.ok(html.includes(message(locale,key)),key);assert.match(html,/50\.25|50\.25/);assert.match(html,/123456789Z/);assert.doesNotMatch(html,/<form|type="submit"|type="radio"|type="checkbox"/);assert.ok(render(locale,{data:{...review,items:[]}}).includes(message(locale,"empty")));assert.match(render(locale,{error:"scope"}),/role="alert"/);assert.match(render(locale,{busy:true}),/aria-busy="true"/);assert.doesNotMatch(render(locale,{data:review,stale:true}),/SYN-REFUND-001/);}
  assert.match(render("en",{data:{...review,receiptNumber:"SYN <script>private</script>"}}),/SYN &lt;script&gt;private&lt;\/script&gt;/);
});
function actual(name,bindings,text=source){
  const ast=ts.createSourceFile("candidate.tsx",text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let found;
  function visit(node){if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name&&ts.isCallExpression(node.initializer))found=node.initializer.arguments[0];if(name==="effect"&&ts.isCallExpression(node)&&node.expression.getText(ast)==="useEffect")found=node.arguments[0];ts.forEachChild(node,visit);}visit(ast);assert.ok(found,name);
  const compiled=ts.transpileModule(`const actual=${found.getText(ast)};`,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;return new Function(...Object.keys(bindings),`${compiled}\nreturn actual;`)(...Object.values(bindings));
}
test("actual candidate loader aborts stale scope responses and prevents duplicate requests",async()=>{
  let finish,signal,calls=0;const context={...props},read={current:null},lifecycle={current:0},snapshots=[],guards=[];
  const bindings={...props,context,read,lifecycle,setPending:()=>{},setSnapshot:v=>snapshots.push(v),onGuardChange:(key,blocked)=>guards.push([key,blocked]),settlementRefundCandidateErrorCode:client.settlementRefundCandidateErrorCode,loadSettlementRefundCandidates:async(...args)=>{calls++;signal=args[5];return new Promise(resolve=>{finish=resolve;});}};
  const cleanup=actual("effect",bindings)(),load=actual("load",bindings),pending=load(0);await load(0);assert.equal(calls,1);cleanup();assert.equal(signal.aborted,true);finish(review);await pending;assert.deepEqual(snapshots,[null]);assert.deepEqual(guards,[[context,true],[context,false]]);
});
test("actual source parent aggregates concurrent child guards without releasing another read",()=>{
  const parent=readFileSync(new URL("../components/workspace/settlement-source-review-panel.tsx",import.meta.url),"utf8"),childReads={current:new Set()},read={current:null},guards=[];
  const change=actual("childGuardChange",{childReads,read,setChildBusy:()=>{},onGuardChange:v=>guards.push(v)},parent),a={},b={};
  change(a,true);change(b,true);change(a,false);assert.equal(guards.at(-1),true);change(b,false);assert.equal(guards.at(-1),false);const count=guards.length;change(a,false);assert.equal(guards.length,count);
  read.current={};change(a,true);change(a,false);assert.equal(guards.at(-1),true);
});
test("OpenAPI Java and browser candidate projections stay aligned and private",()=>{
  const contract=yaml.load(readFileSync(new URL("../../../../packages/contracts/openapi.yaml",import.meta.url),"utf8")),schemas=contract.components.schemas;
  const path=contract.paths["/finance/gateway/settlements/requests/{requestId}/refund-candidates"];assert.deepEqual(Object.keys(path),["get"]);assert.deepEqual(path.get.security,[{sessionCookie:[]}]);assert.equal(path.get.responses["200"].headers["Cache-Control"].schema.const,"private, no-store");assert.equal(path.get.requestBody,undefined);
  assert.deepEqual(schemas.GatewaySettlementRefundCandidateReason.enum,[...client.settlementRefundCandidateReasons]);
  const model=readFileSync(new URL("../../../../apps/api/src/main/java/com/educore/api/finance/GatewaySettlementRefundCandidateModels.java",import.meta.url),"utf8"),ast=ts.createSourceFile("candidate.ts",readFileSync(new URL("./gateway-settlement-refund-candidates.ts",import.meta.url),"utf8"),ts.ScriptTarget.Latest,true);
  for(const [record,schemaName,aliasName] of[["Review","GatewaySettlementRefundCandidates","SettlementRefundCandidates"],["Candidate","GatewaySettlementRefundCandidate","SettlementRefundCandidate"]]){
    const fields=model.match(new RegExp(`public record ${record}\\(([\\s\\S]*?)\\)\\s*\\{`))[1].split(",").map(p=>p.trim().match(/(\w+)$/)[1]).sort(),schema=schemas[schemaName],alias=ast.statements.find(n=>ts.isTypeAliasDeclaration(n)&&n.name.text===aliasName);
    assert.equal(schema.additionalProperties,false);assert.deepEqual(Object.keys(schema.properties).sort(),fields);assert.deepEqual([...schema.required].sort(),fields);assert.deepEqual(alias.type.members.map(n=>n.name.getText(ast)).sort(),fields);
    assert.ok(fields.every(f=>!/canPost|credential|cursor|customer|bankAccount|sourceSha256/i.test(f)));
  }
});
