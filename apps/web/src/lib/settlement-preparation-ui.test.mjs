import assert from "node:assert/strict";
import test from "node:test";
import {readFileSync} from "node:fs";
import {createRequire} from "node:module";
import React from "react";
import {renderToStaticMarkup} from "react-dom/server";
import ts from "typescript";
import * as client from "./gateway-settlement-preparations.ts";
import {formatSettlementAmount} from "./gateway-settlements.ts";
import {gatewaySettlementPreparationMessages as messages,settlementPreparationMessage as message} from "./i18n/gateway-settlement-preparation-messages.ts";
import {id,schoolId,requestId,settlementReference,context,draft,preparation,detail,clone,association,candidate,candidates} from "./gateway-settlement-preparation-fixtures.mjs";

// Synthetic real-component/handler tests. These do not prove browser acceptance.
const source=readFileSync(new URL("../components/workspace/settlement-preparation-panel.tsx",import.meta.url),"utf8"),provenance=readFileSync(new URL("../components/workspace/settlement-preparation-provenance.tsx",import.meta.url),"utf8");
const ast=ts.createSourceFile("preparation.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX),functions=new Map();
function visit(node){if(ts.isFunctionDeclaration(node)&&node.name)functions.set(node.name.text,node);ts.forEachChild(node,visit);}visit(ast);
const main=functions.get("SettlementPreparationPanel"),child=functions.get("RefundChoice"),stateNames=node=>{const names=[];function visit(node){if(ts.isVariableDeclaration(node)&&ts.isArrayBindingPattern(node.name)&&node.initializer&&ts.isCallExpression(node.initializer)&&node.initializer.expression.getText(ast)==="useState")names.push(node.name.elements[0].getText(ast));ts.forEachChild(node,visit);}visit(node);return names;};
const escaped=value=>renderToStaticMarkup(React.createElement(React.Fragment,null,value));
function compile(code,modules){const exports={},require=createRequire(import.meta.url),compiled=ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;new Function("require","exports",compiled)(name=>{if((name.startsWith("@/")||name.startsWith("./settlement")||name.endsWith(".css"))&&!modules[name])throw new Error("Unreviewed dependency "+name);return modules[name]??require(name);},exports);return exports;}
function render(locale="en",changes={},propChanges={}){
  const props={schoolId,requestId,settlementReference,disabled:false,onGuardChange:()=>{},...propChanges},scope={schoolId:props.schoolId,requestId:props.requestId,settlementReference:props.settlementReference,disabled:props.disabled},values={...changes,...(changes.data?{snapshot:{scope:changes.stale?{...scope}:scope,data:changes.data}}:{}),...(changes.busy?{pending:scope}:{}),...(changes.error?{error:{scope,code:changes.error}}:{}),...(changes.notice?{notice:{scope,...changes.notice}}:{})},names=stateNames(main),prepared=[],exports={};let index=0;
  const stub="function RefundChoice(props){return require('react').createElement('section',{'data-refund-row':props.row.sourceReadId+':'+props.row.rowIndex,'data-selected-refund':props.selected?.refundPostingId??'','data-refund-immutable':String(props.immutable)},'Refund choices');}";
  const modified=source.slice(0,child.pos)+stub+source.slice(child.end),react={...React,useMemo:()=>scope,useState:initial=>{const name=names[index++];return[Object.hasOwn(values,name)?values[name]:typeof initial==="function"?initial():initial,()=>{}];}};
  Object.assign(exports,compile(modified,{react,"@/components/i18n/language-provider":{useLanguage:()=>({locale})},"@/lib/family-gateway-payments":{familyPaymentCsrf:()=>{throw new Error("No requests in static render");}},"@/lib/gateway-settlements":{formatSettlementAmount},"@/lib/gateway-settlement-refund-candidates":{},"@/lib/gateway-settlement-preparations":client,"@/lib/i18n/gateway-settlement-preparation-messages":{settlementPreparationMessage:message},"./settlement-preparation-provenance":{SettlementPreparationSummary:props=>React.createElement("section",{"data-preparation-id":props.preparation.id},props.preparation.reason),SettlementPreparationProvenance:props=>{prepared.push(props);return React.createElement("section",{"data-provenance":props.preparationId});}},"./settlement-bank-evidence-panel.module.css":{default:new Proxy({},{get:(_,key)=>String(key)})}}));
  return{html:renderToStaticMarkup(React.createElement(exports.SettlementPreparationPanel,props)),prepared};
}
function renderProvenance(locale="en",value=detail,changes={},propChanges={}){
  const props={schoolId,preparationId:preparation.id,journalId:preparation.journalId,disabled:false,...propChanges},scope={...props};delete scope.onRepair;
  const file=ts.createSourceFile("provenance.tsx",provenance,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX),component=file.statements.find(node=>ts.isFunctionDeclaration(node)&&node.name?.text==="SettlementPreparationProvenance"),names=[];function find(node){if(ts.isVariableDeclaration(node)&&ts.isArrayBindingPattern(node.name)&&node.initializer&&ts.isCallExpression(node.initializer)&&node.initializer.expression.getText(file)==="useState")names.push(node.name.elements[0].getText(file));ts.forEachChild(node,find);}find(component);
  const values={snapshot:value?{scope,data:value}:null,...changes};let index=0;
  const exported=compile(provenance,{react:{...React,useMemo:()=>scope,useState:initial=>{const name=names[index++];return[Object.hasOwn(values,name)?values[name]:typeof initial==="function"?initial():initial,()=>{}];}},"@/components/i18n/language-provider":{useLanguage:()=>({locale})},"@/lib/gateway-settlements":{formatSettlementAmount},"@/lib/gateway-settlement-preparations":client,"@/lib/i18n/gateway-settlement-preparation-messages":{settlementPreparationMessage:message},"./settlement-bank-evidence-panel.module.css":{default:new Proxy({},{get:(_,key)=>String(key)})}});
  return renderToStaticMarkup(React.createElement(exported.SettlementPreparationProvenance,props));
}
function renderRefundChoice(locale="en",value=candidates,changes={},propChanges={}){
  const props={schoolId,requestId,settlementReference,row:context.rows[1],selected:null,disabled:false,immutable:false,onChoose:()=>{},onReason:()=>{},onGuardChange:()=>{},...propChanges},scope={schoolId:props.schoolId,requestId:props.requestId,settlementReference:props.settlementReference,row:props.row},names=stateNames(child),values={snapshot:value?{context:changes.stale?{...scope}:scope,data:value}:null,...changes};let index=0;
  const exports=compile(source+"\nexport {RefundChoice};",{react:{...React,useMemo:()=>scope,useState:initial=>{const name=names[index++];return[Object.hasOwn(values,name)?values[name]:typeof initial==="function"?initial():initial,()=>{}];}},"@/components/i18n/language-provider":{useLanguage:()=>({locale})},"@/lib/family-gateway-payments":{},"@/lib/gateway-settlements":{formatSettlementAmount},"@/lib/gateway-settlement-refund-candidates":{},"@/lib/gateway-settlement-preparations":client,"@/lib/i18n/gateway-settlement-preparation-messages":{settlementPreparationMessage:message},"./settlement-preparation-provenance":{},"./settlement-bank-evidence-panel.module.css":{default:new Proxy({},{get:(_,key)=>String(key)})}});
  return renderToStaticMarkup(React.createElement(exports.RefundChoice,props));
}
test("preparation copy is bilingual and the default screen does not create a draft or invent settlement success",()=>{
  assert.deepEqual(Object.keys(messages.en).sort(),Object.keys(messages.hi).sort());for(const[key,value]of Object.entries(messages.hi))assert.match(value,/[\u0900-\u097f]/,key);
  for(const locale of["en","hi"]){const{html}=render(locale);for(const key of["title","boundary","inspect","idle"])assert.ok(html.includes(message(locale,key)),key);assert.doesNotMatch(html,/<form|SYN-UTR|Synthetic bank|Posted in the common ledger/);}
});
test("full context renders exact reported totals scoped blockers and every source without claiming reconciliation",()=>{
  const{html}=render("hi",{data:context});for(const key of["paymentTotal","refundTotal","totalsHelp","sourceRows","history"])assert.ok(html.includes(message("hi",key)),key);assert.match(html,/INR 100\.00|INR 5\.00/);assert.match(html,/SYN-000001/);assert.doesNotMatch(html,/<form|data-selected-refund=/);
  const unknown={...context,paymentTotal:null,refundTotal:null,blockers:["NEW_REVIEW_REASON"]};const rendered=render("hi",{data:unknown}).html;assert.ok(rendered.includes(message("hi","unknownTotals")));assert.match(rendered,/NEW_REVIEW_REASON/);
});
test("one labelled form contains bank date currency all eight component reviews and overall independent submission",()=>{
  const{html}=render("en",{data:context,mode:{kind:"PREPARE"},draft});assert.equal((html.match(/<form/g)??[]).length,1);assert.match(html,/aria-label="Review the complete settlement"/);assert.match(html,/type="date"/);assert.equal((html.match(/inputMode="decimal"/g)??[]).length,8);assert.match(html,/minLength="3" maxLength="240"/);assert.match(html,/type="checkbox" required="" checked/);assert.doesNotMatch(html,/type="number"|localStorage|<script>/);
  for(const key of client.settlementComponentKeys)assert.ok(html.includes(message("en","component."+key)),key);
  const hi=render("hi",{data:context,mode:{kind:"PREPARE"},draft}).html;for(const key of["form","currencyConfirm","modeHelp","prepare"])assert.ok(hi.includes(message("hi",key)),key);
});
test("refund choices remain physically keyed and unsaved choices persist across source pages without automatic selection",()=>{
  const blank={...draft,refundAssociations:[]},unselected=render("en",{data:context,mode:{kind:"PREPARE"},draft:blank}).html;assert.ok(unselected.includes('data-refund-row="'+association.sourceReadId+':1"'));assert.match(unselected,/data-selected-refund=""/);
  const selected=render("en",{data:context,mode:{kind:"PREPARE"},draft}).html;assert.ok(selected.includes('data-selected-refund="'+association.refundPostingId+'"'));assert.match(source,/read\.current\?\.abort\(\)/);assert.doesNotMatch(source,/localStorage|sessionStorage|indexedDB/);
});
test("actual refund child never preselects a candidate and unposted accounting cannot be selected",()=>{
  const html=renderRefundChoice();assert.match(html,/aria-pressed="false"/);assert.doesNotMatch(html,/aria-pressed="true"|<textarea/);
  const unposted=renderRefundChoice("hi",{...candidates,items:[{...candidate,accountingState:"ACCOUNTING_REVIEW_REQUIRED",accountingJournalId:null}]});assert.ok(unposted.includes(message("hi","reviewRefund")));assert.match(unposted,/disabled="" aria-pressed="false"/);
  const selected=renderRefundChoice("hi",candidates,{}, {selected:association});assert.match(selected,/aria-pressed="true"/);assert.match(selected,/minLength="3" maxLength="240"/);assert.ok(selected.includes(message("hi","refundReason")));
});
test("actual retained refund choice can be reviewed during repair without replacing its economic source",()=>{
  const html=renderRefundChoice("en",candidates,{}, {selected:association,immutable:true});assert.match(html,/disabled="" aria-pressed="true"/);assert.ok(!html.includes(">"+message("en","removeRefund")+"</button>"));
  assert.doesNotMatch(renderRefundChoice("en",candidates,{stale:true}),/Synthetic explicit refund choice|Complete posted source proven/);
  const loading=renderRefundChoice("hi",null,{busy:true});assert.match(loading,/aria-busy="true"/);assert.match(loading,/role="status"/);
});
test("readonly stale foreign disabled loading and known failure states never expose an old financial draft",()=>{
  assert.doesNotMatch(render("en",{data:{...context,canPrepare:false,accountingOptions:{...context.accountingOptions,canPrepare:false}}}).html,/<form|Prepare one settlement journal/);
  for(const data of[{...context,schoolId:id(90)},{...context,requestId:id(90)},{...context,settlementReference:"SYN_OTHER"}])assert.doesNotMatch(render("en",{data,mode:{kind:"PREPARE"},draft}).html,/SYN-UTR|Synthetic gateway expense|<form/);
  assert.doesNotMatch(render("en",{data:context,stale:true,mode:{kind:"PREPARE"},draft}).html,/<form|SYN-UTR/);
  assert.doesNotMatch(render("en",{data:context,mode:{kind:"PREPARE"},draft},{disabled:true}).html,/<form|SYN-UTR/);
  assert.match(render("en",{busy:true}).html,/aria-busy="true"/);assert.match(render("hi",{error:"scope"}).html,/role="alert"/);
});
test("unknown outcome locks all draft fields and offers an exact command retry instead of a fresh preparation",()=>{
  const{html}=render("en",{data:context,mode:{kind:"PREPARE"},draft,locked:true,error:"unknown"});assert.match(html,/<fieldset disabled/);assert.ok(html.includes(message("en","unknown")));assert.ok(html.includes(message("en","retry")));assert.doesNotMatch(html,/Prepare one settlement journal/);assert.match(source,/dispatch\(attempt\.current\)/);
});
test("only latest rejected never-posted chain offers release and rejected or reversed history offers controlled repair",()=>{
  const rejected={...preparation,status:"REJECTED"},value={...context,preparations:[rejected]},rendered=render("en",{data:value});assert.ok(rendered.html.includes(message("en","release")));assert.equal(rendered.prepared.length,1);assert.equal(typeof rendered.prepared[0].onRepair,"function");
  for(const status of["SUBMITTED","POSTED","ABANDONED"]){const item={...preparation,status},result=render("en",{data:{...context,preparations:[item]}});assert.ok(!result.html.includes(">"+message("en","release")+"</button>"));assert.equal(result.prepared[0].onRepair,undefined);}
  const postedThenRejected=[{...preparation,status:"POSTED"},{...preparation,id:id(95),revision:2,previousPreparationId:preparation.id,status:"REJECTED"}];assert.ok(!render("en",{data:{...context,preparations:postedThenRejected}}).html.includes(">"+message("en","release")+"</button>"));
});
test("provenance shows frozen bank zone component documents and exact source associations in both languages",()=>{
  for(const locale of["en","hi"]){const html=renderProvenance(locale);for(const key of["provenanceTitle","provenanceBoundary","components","sources","bankCreditedAt"])assert.ok(html.includes(escaped(message(locale,key))),key);assert.match(html,/SYN-UTR-001|Asia\/Kolkata/);assert.match(html,/Synthetic explicit refund choice/);assert.match(html,/Synthetic &lt;preparer&gt;/);assert.doesNotMatch(html,/<script>|<form/);}
  const rows=Array.from({length:26},(_,index)=>({...detail.sources[0],sourceReadId:id(100+index),paymentId:id(200+index)}));const page=renderProvenance("en",{...detail,sources:rows});assert.ok(page.includes(id(124)));assert.ok(!page.includes(id(125)));assert.match(page,/>Next page<\/button>/);
});
test("provenance on-demand scope and journal identity never render another preparation's data",()=>{
  assert.doesNotMatch(renderProvenance("en",null),/SYN-UTR|Synthetic component review/);
  for(const value of[{...detail,preparation:{...preparation,id:id(91)}},{...detail,preparation:{...preparation,journalId:id(92)}}])assert.doesNotMatch(renderProvenance("en",value),/SYN-UTR|Synthetic component review/);
  assert.doesNotMatch(renderProvenance("en",detail,{}, {disabled:true}),/SYN-UTR|Synthetic component review/);
});
function actual(name,bindings){const node=functions.get(name);assert.ok(node,name);const compiled=ts.transpileModule("const handler = "+node.getText(ast)+";",{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;return new Function(...Object.keys(bindings),compiled+"\nreturn handler;")(...Object.values(bindings));}
function dispatchHarness(send,csrf=async()=>"synthetic_csrf"){
  const state={locked:false,saving:false,error:null,notice:null,draft:clone(draft),releaseConfirmed:true,loads:0,guards:[]},refs={read:{current:null},writing:{current:false},attempt:{current:null},uncertain:{current:false},lifecycle:{current:1},form:{current:{kind:"PREPARE"}},childReads:{current:new Set()}},scope={schoolId,requestId,settlementReference,disabled:false};
  const bindings={...refs,disabled:false,context,scope,SettlementPreparationWriteError:client.SettlementPreparationWriteError,settlementPreparationErrorCode:client.settlementPreparationErrorCode,newSettlementPreparationDraft:client.newSettlementPreparationDraft,familyPaymentCsrf:csrf,sendSettlementPreparation:send,load:async()=>{state.loads++;},guard:()=>state.guards.push(!!(refs.writing.current||refs.attempt.current||refs.form.current)),setLocked:value=>{state.locked=value;},setSaving:value=>{state.saving=value;},setError:value=>{state.error=value;},setNotice:value=>{state.notice=value;},setMode:()=>{},setReleaseConfirmed:value=>{state.releaseConfirmed=value;},setDraft:value=>{state.draft=typeof value==="function"?value(state.draft):value;}};
  return{state,...refs,dispatch:actual("dispatch",bindings)};
}
const command=()=>client.prepareSettlementJournal(context,draft,preparation.clientRequestId);
test("actual unknown dispatch keeps same command through later rejection and clears only on correlated success",async()=>{
  let calls=0;const attempt=command(),h=dispatchHarness(async sent=>{calls++;assert.equal(sent,attempt);if(calls===1)throw new client.SettlementPreparationWriteError("unknown","unknown");if(calls===2)throw new client.SettlementPreparationWriteError("rejected","consumed");return preparation;});
  await h.dispatch(attempt);assert.equal(h.state.locked,true);assert.equal(h.attempt.current,attempt);await h.dispatch({...attempt});assert.equal(calls,1);
  await h.dispatch(attempt);assert.equal(h.state.error.code,"unknown");assert.equal(h.state.locked,true);assert.equal(h.attempt.current,attempt);
  await h.dispatch(attempt);assert.equal(calls,3);assert.equal(h.state.locked,false);assert.equal(h.attempt.current,null);assert.equal(h.uncertain.current,false);assert.equal(h.state.notice.preparation,preparation);assert.equal(h.state.loads,1);assert.equal(h.state.guards.at(-1),false);
});
test("actual definite initial rejection preserves unsent form and clears confirmation without pretending to post",async()=>{
  const h=dispatchHarness(async()=>{throw new client.SettlementPreparationWriteError("rejected","components");});await h.dispatch(command());assert.equal(h.state.locked,false);assert.equal(h.attempt.current,null);assert.equal(h.state.draft.confirmedReviewed,false);assert.equal(h.state.releaseConfirmed,false);assert.equal(h.state.error.code,"components");assert.equal(h.state.loads,0);assert.equal(h.state.guards.at(-1),true);
});
test("actual CSRF failure after uncertainty cannot release the original pending command",async()=>{
  let tokens=0,posts=0;const h=dispatchHarness(async()=>{posts++;throw new client.SettlementPreparationWriteError("unknown","unknown");},async()=>{tokens++;if(tokens===2)throw new Error("synthetic token failure");return"synthetic_csrf";}),attempt=command();
  await h.dispatch(attempt);await h.dispatch(attempt);assert.equal(posts,1);assert.equal(h.state.locked,true);assert.equal(h.attempt.current,attempt);assert.equal(h.state.error.code,"unknown");
});
test("actual concurrent dispatch and stale completion cannot create another command or release its guard",async()=>{
  let finish,calls=0;const h=dispatchHarness(async()=>{calls++;return new Promise(resolve=>{finish=resolve;});}),attempt=command(),first=h.dispatch(attempt);await Promise.resolve();await h.dispatch(attempt);assert.equal(calls,1);h.lifecycle.current++;finish(preparation);await first;assert.equal(h.attempt.current,attempt);assert.equal(h.state.locked,true);assert.equal(h.state.notice,null);assert.equal(h.state.loads,0);assert.equal(h.state.guards.at(-1),true);
});
test("actual refund choice handler rejects an unposted candidate and changes only its exact physical row",()=>{
  const state={draft:clone(draft)},bindings={blocked:false,draft:state.draft,settlementPhysicalRow:client.settlementPhysicalRow,setDraft:value=>{state.draft=value(state.draft);}};
  const handler=actual("association",bindings),before=JSON.stringify(state.draft);handler(context.rows[1],{...candidate,accountingState:"ACCOUNTING_REVIEW_REQUIRED"});assert.equal(JSON.stringify(state.draft),before);
  handler(context.rows[1],{...candidate,refundPostingId:id(92)});assert.deepEqual(state.draft.refundAssociations,[{...association,refundPostingId:id(92),reason:""}]);assert.equal(state.draft.confirmedReviewed,false);
});
test("actual explicit zero component clears an old account and bank changes clear its currency confirmation",()=>{
  const state={draft:clone(draft)},bindings={blocked:false,setDraft:value=>{state.draft=value(state.draft);},setError:()=>{}};
  actual("component",bindings)(0,"mode","CONFIRMED_ZERO");assert.equal(state.draft.componentReviews[0].postedAmount,"0.00");assert.equal(state.draft.componentReviews[0].accountId,"");assert.equal(state.draft.confirmedReviewed,false);
  state.draft.confirmedSummaryCurrency=true;actual("update",bindings)("bankEvidenceId",id(95));assert.equal(state.draft.confirmedSummaryCurrency,false);
});
test("parent binds the preparation child only to complete events and includes its guard without changing journal decisions",()=>{
  const report=readFileSync(new URL("../components/workspace/settlement-reports-panel.tsx",import.meta.url),"utf8"),accounting=readFileSync(new URL("../components/workspace/accounting-workspace.tsx",import.meta.url),"utf8");
  assert.match(report,/<SettlementPreparationPanel.*schoolId=\{schoolId\}.*requestId=\{activeReport\.request\.id\}/);assert.match(report,/preparationReading\.current = blocked/);assert.match(report,/contextBlocked = busy \|\| locked \|\| sourceBusy \|\| bankBusy \|\| preparationBusy/);
  assert.match(accounting,/journal\.sourceModule === "GATEWAY_SETTLEMENT" && journal\.sourceId/);assert.match(accounting,/preparationId=\{journal\.sourceId\} journalId=\{journal\.id\}/);assert.match(accounting,/decision: action, expectedVersion: journal\.rowVersion/);
});
