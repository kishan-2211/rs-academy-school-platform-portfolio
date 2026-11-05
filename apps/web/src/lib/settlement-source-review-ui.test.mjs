import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as client from "./gateway-settlement-source-review.ts";
import { formatSettlementAmount } from "./gateway-settlements.ts";
import { settlementSourceReviewMessage as message, gatewaySettlementSourceReviewMessages as messages } from "./i18n/gateway-settlement-source-review-messages.ts";

// Real component static-render and extracted-handler tests, not browser acceptance.
const source=readFileSync(new URL("../components/workspace/settlement-source-review-panel.tsx",import.meta.url),"utf8"),css=readFileSync(new URL("../components/workspace/settlement-source-review-panel.module.css",import.meta.url),"utf8");
const schoolId="8d000000-0000-4000-8000-000000000001",requestId="8d000000-0000-4000-8000-000000000002",readId="8d000000-0000-4000-8000-000000000003",other="8d000000-0000-4000-8000-000000000004",settlementReference="synthetic_statement",at="2026-10-23T04:17:39.123456789Z";
const props={schoolId,requestId,settlementReference,disabled:false,onGuardChange:()=>{}};
const event={settlementReference,eventReference:"synthetic_event",reportedType:"PAYMENT",reportedDirection:"CREDIT",reportedStatus:"SUCCESS",currencyCode:"INR",reportedAmount:"90071992.54000000",reportedServiceCharge:null,reportedServiceTax:null,reportedSettlementAmount:null,occurredAt:at,orderReference:"synthetic_order",paymentReference:"synthetic_payment",refundReferences:[],chargesCurrencyCode:null};
const sourceEvidence={captureId:other,paymentId:other,feeLedgerEntryId:other,receiptNumber:"SYN-SOURCE <script>receipt</script>",postingItemId:other,postingBatchId:other,configurationId:other,accountingJournalId:other,clearingAccountId:other,clearingAccountCategory:"OTHER",originalFinancialYearId:other,originalAccountingPeriodId:other,originalPostingDate:"2026-10-23",accountingBasis:"ACCRUAL",currencyCode:"INR",grossAmount:"90071992.54"};
const row={sourceReadId:readId,rowIndex:0,observedAt:at,event,status:"SOURCE_IDENTIFIED",reasons:[],source:sourceEvidence};
const review={schoolId,requestId,settlementReference,reviewStage:"SOURCE_IDENTIFICATION_ONLY",pagesRead:1,totalRows:1,sourceIdentifiedRows:1,reviewRequiredRows:0,page:0,pageSize:25,hasMore:false,items:[row]};
function render(locale="en",changes={},propChanges={}) {
  const actualProps={...props,...propChanges},context={schoolId:actualProps.schoolId,requestId:actualProps.requestId,settlementReference:actualProps.settlementReference,disabled:actualProps.disabled};
  const snapshot=changes.data||changes.error?{context:changes.stale?{...context}:context,data:changes.data??null,error:changes.error??null}:null;
  const values=[snapshot,changes.busy?context:null];let index=0;const exports={},require=createRequire(import.meta.url);
  const modules={react:{...React,useMemo:()=>context,useState:initial=>[index<values.length?values[index++]:initial,()=>{}]},"@/components/i18n/language-provider":{useLanguage:()=>({locale})},"@/lib/gateway-settlements":{formatSettlementAmount},"@/lib/gateway-settlement-source-review":client,"@/lib/i18n/gateway-settlement-source-review-messages":{settlementSourceReviewMessage:message},"./settlement-source-review-panel.module.css":{default:new Proxy({}, {get:(_,key)=>String(key)})},"./settlement-refund-candidates-panel":{SettlementRefundCandidatesPanel:()=>React.createElement("section",{"aria-label":"Historical refund candidates"})}};
  const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
  new Function("require","exports",compiled)(name=>{if((name.startsWith("@/")||name.endsWith(".css"))&&!modules[name])throw new Error(`Unreviewed dependency ${name}`);return modules[name]??require(name);},exports);
  return renderToStaticMarkup(React.createElement(exports.SettlementSourceReviewPanel,actualProps));
}
function actual(name,bindings) {
  const ast=ts.createSourceFile("source-review.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let found;
  function visit(node){if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name&&node.initializer&&ts.isCallExpression(node.initializer))found=node.initializer.arguments[0];if(name==="lifecycleEffect"&&ts.isCallExpression(node)&&node.expression.getText(ast)==="useEffect")found=node.arguments[0];ts.forEachChild(node,visit);}visit(ast);assert.ok(found,`Missing actual ${name}`);
  const compiled=ts.transpileModule(`const actual=${found.getText(ast)};`,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;return new Function(...Object.keys(bindings),`${compiled}\nreturn actual;`)(...Object.values(bindings));
}
function harness(loader,disabled=false){
  const context={schoolId,requestId,settlementReference,disabled},state={snapshots:[],guards:[],pending:null},read={current:null},lifecycle={current:0};
  const bindings={schoolId,requestId,settlementReference,disabled,context,read,lifecycle,childReads:{current:new Set()},setChildBusy:()=>{},loadSettlementSourceReview:loader,settlementSourceReviewErrorCode:client.settlementSourceReviewErrorCode,setPending:value=>{state.pending=value;},setSnapshot:value=>state.snapshots.push(value),onGuardChange:value=>state.guards.push(value)};
  const cleanup=actual("lifecycleEffect",bindings)(),load=actual("load",bindings);return{state,context,read,lifecycle,cleanup,load};
}
test("both catalogues translate the stage boundary known reasons and controlled errors",()=>{
  assert.deepEqual(Object.keys(messages.en).sort(),Object.keys(messages.hi).sort());
  for(const reason of client.settlementSourceReviewReasons)assert.match(messages.hi[`reason.${reason}`],/[\u0900-\u097f]/);
  for(const locale of["en","hi"]){const html=render(locale);for(const key of["title","boundary","inspect","idle"])assert.ok(html.includes(message(locale,key)),key);assert.doesNotMatch(html,/<form|type="submit"|Mark.*reconciled|Post.*journal|ready.to.post/i);}
});
test("identified source renders precise gross separate currency provenance and escaped retained receipt",()=>{
  const html=render("en",{data:review});assert.ok(html.includes(message("en","status.SOURCE_IDENTIFIED")));assert.match(html,/9,00,71,992\.54000000/);assert.match(html,/9,00,71,992\.54/);assert.match(html,/INR/);assert.match(html,/2026-10-23T04:17:39\.123456789Z/);assert.match(html,/<details>/);assert.match(html,/&lt;script&gt;receipt&lt;\/script&gt;/);assert.doesNotMatch(html,/<script>|₹|Bank confirmed|Ready to post/i);
  const hindi=render("hi",{data:review});for(const key of["sourceDetails","receipt","clearingOther","basis.ACCRUAL"])assert.ok(hindi.includes(message("hi",key)),key);
});
test("review-required events keep reasons provider unknowns no invented historical source",()=>{
  const required={...review,sourceIdentifiedRows:0,reviewRequiredRows:1,items:[{...row,status:"REVIEW_REQUIRED",reasons:["REFUND_ASSOCIATION_REVIEW","UNSUPPORTED_EVENT_TYPE"],source:null,event:{...event,reportedType:"NEW_KIND",currencyCode:"USD",reportedAmount:"-0.00000001"}}]};
  for(const locale of["en","hi"]){const html=render(locale,{data:required});assert.ok(html.includes(message(locale,"reason.REFUND_ASSOCIATION_REVIEW")));assert.match(html,/NEW_KIND|USD/);assert.match(html,/-0\.00000001/);assert.doesNotMatch(html,/SYN-SOURCE|<details>/);}
});
test("empty loading error and pagination use native semantics without financial mutation controls",()=>{
  const loading=render("en",{busy:true});assert.match(loading,/aria-busy="true"/);assert.match(loading,/role="status"/);assert.match(loading,/<button type="button" disabled/);
  const error=render("hi",{error:"scope"});assert.match(error,/role="alert"/);assert.ok(error.includes(message("hi","error.scope")));
  const empty=render("en",{data:{...review,totalRows:0,sourceIdentifiedRows:0,items:[]}});assert.ok(empty.includes(message("en","empty")));assert.match(empty,/aria-label="Source-review pages"/);
  const page=render("en",{data:{...review,pagesRead:3,totalRows:26,sourceIdentifiedRows:26,page:1,items:[row]}});assert.match(page,/Page 2/);assert.match(page,/>26<\/dd>/);assert.match(page,/>Previous source-review page<\/button>/);assert.match(page,/disabled="">Next source-review page/);
});
test("stale scope generation cross-school data and parent guards hide all source results",()=>{
  for(const changes of[{data:review,stale:true},{data:{...review,schoolId:other}},{data:{...review,requestId:other}},{data:{...review,settlementReference:"synthetic_other"}}])assert.doesNotMatch(render("en",changes),/SYN-SOURCE|synthetic_event|90071992|9,00,71/);
  const disabled=render("hi",{data:review},{disabled:true});assert.ok(disabled.includes(message("hi","paused")));assert.doesNotMatch(disabled,/SYN-SOURCE|<button/);
});
test("actual read handler prevents double requests guards context and releases only its own operation",async()=>{
  let finish,calls=0;const h=harness(async(...args)=>{calls++;assert.equal(args[0],schoolId);assert.equal(args[1],requestId);assert.equal(args[2],settlementReference);assert.equal(args[3],0);return new Promise(resolve=>{finish=resolve;});});
  const pending=h.load(0);await h.load(0);assert.equal(calls,1);assert.equal(h.state.pending,h.context);assert.deepEqual(h.state.guards,[true]);finish(review);await pending;assert.equal(h.state.snapshots.at(-1).data,review);assert.deepEqual(h.state.guards,[true,false]);assert.equal(h.read.current,null);assert.equal(h.state.pending,null);
});
test("actual lifecycle cleanup aborts and discards delayed private data after scope or guard change",async()=>{
  let finish,signal;const h=harness(async(...args)=>{signal=args[4];return new Promise(resolve=>{finish=resolve;});});const pending=h.load(0);h.cleanup();assert.equal(signal.aborted,true);finish(review);await pending;assert.deepEqual(h.state.snapshots,[null]);assert.deepEqual(h.state.guards,[true,false]);assert.equal(h.read.current,null);
});
test("actual denied read clears prior evidence exposes controlled error and disabled handler never calls API",async()=>{
  const h=harness(async()=>{throw new client.SettlementSourceReviewError("scope");});await h.load(0);assert.equal(h.state.snapshots.at(-1).data,null);assert.equal(h.state.snapshots.at(-1).error,"scope");assert.deepEqual(h.state.guards,[true,false]);
  let calls=0;const disabled=harness(async()=>{calls++;return review;},true);await disabled.load(0);assert.equal(calls,0);assert.deepEqual(disabled.state.guards,[]);
});
test("source review uses themed cards phone layout 44px targets focus and reduced motion without success badge",()=>{
  for(const pattern of[/var\(--theme-card\)/,/var\(--theme-error-text\)/,/var\(--theme-warning-text\)/,/min-height:44px/,/:focus-visible/,/@media\(max-width:600px\)/,/prefers-reduced-motion:reduce/])assert.match(css,pattern);
  assert.doesNotMatch(css,/#(?:[0-9a-f]{3}){1,2}\b|theme-success|animation:(?!none)/i);assert.match(source,/disabled=\{busy\}/);assert.match(source,/context === context/);
});
