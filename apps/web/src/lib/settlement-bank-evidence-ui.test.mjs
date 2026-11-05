import assert from "node:assert/strict";
import test from "node:test";
import {readFileSync} from "node:fs";
import {createRequire} from "node:module";
import React from "react";
import {renderToStaticMarkup} from "react-dom/server";
import ts from "typescript";
import * as bank from "./gateway-settlement-bank-evidence.ts";
import {formatSettlementAmount} from "./gateway-settlements.ts";
import {currentSchoolLocalTime,schoolLocalTimeToInstant} from "./manual-fee-collection.ts";
import {gatewaySettlementBankMessages as messages,settlementBankMessage as message} from "./i18n/gateway-settlement-bank-messages.ts";

// These synthetic real-component render/handler checks are not browser acceptance.
const source=readFileSync(new URL("../components/workspace/settlement-bank-evidence-panel.tsx",import.meta.url),"utf8"),css=readFileSync(new URL("../components/workspace/settlement-bank-evidence-panel.module.css",import.meta.url),"utf8");
const ast=ts.createSourceFile("bank-evidence.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX),states=[];
function visitState(node){if(ts.isVariableDeclaration(node)&&ts.isArrayBindingPattern(node.name)&&node.initializer&&ts.isCallExpression(node.initializer)&&node.initializer.expression.getText(ast)==="useState")states.push(node.name.elements[0].getText(ast));ts.forEachChild(node,visitState);}visitState(ast);
const id=n=>`8f000000-0000-4000-8000-${String(n).padStart(12,"0")}`,schoolId=id(1),requestId=id(2),accountId=id(3),clientId=id(4),settlementReference="synthetic_statement",at="2026-10-23T04:00:00Z";
const evidence={id:id(5),clientRequestId:clientId,bankAccountId:accountId,bankReference:"SYN-UTR 001",currencyCode:"INR",creditedAmount:"90071992.54",actualCreditedAt:at,creditedOn:"2026-10-23",verificationKind:"BANK_APP_CREDIT",recordedAt:"2026-10-23T05:00:00Z",recordedBy:"Synthetic maker",reason:"Synthetic <script>bank review</script>",status:"BANK_EVIDENCE_RECORDED",reviewReasons:bank.bankReviewReasons.slice(0,3),rejection:null,canReject:false};
const context={schoolId,requestId,settlementReference,schoolTimeZone:"Asia/Kolkata",canRecord:true,canReject:true,summary:{sourceReadId:id(6),rowIndex:0,observedAt:at,reportedStatus:"SUCCESS",transferReference:null,reportedAmountSettled:"90071992.54000000"},bankAccounts:[{id:accountId,code:"SYN-BANK",name:"Synthetic school bank",currencyCode:"INR"}],evidence:[evidence],hasMoreEvidence:false};
const draft={bankAccountId:accountId,amount:"90071992.54",actualLocal:"2026-10-23T09:30:00",utcOffset:"",verificationKind:"BANK_APP_CREDIT",bankReference:"SYN-UTR 001",confirmed:true,reason:evidence.reason};
const props={schoolId,requestId,settlementReference,disabled:false,onGuardChange:()=>{}};
function render(locale="en",changes={},propChanges={}){
  const actualProps={...props,...propChanges},scope={schoolId:actualProps.schoolId,requestId:actualProps.requestId,settlementReference:actualProps.settlementReference,disabled:actualProps.disabled};
  const values={...changes,...(changes.data?{snapshot:{scope:changes.stale?{...scope}:scope,data:changes.data}}:{}),...(changes.busy?{pending:scope}:{}),...(changes.error?{error:{scope,code:changes.error}}:{}),...(changes.ack?{ack:{scope,evidence:changes.ack}}:{})};let index=0;const exports={},require=createRequire(import.meta.url);
  const modules={react:{...React,useMemo:()=>scope,useState:initial=>{const name=states[index++];return[Object.hasOwn(values,name)?values[name]:typeof initial==="function"?initial():initial,()=>{}];}},"@/components/i18n/language-provider":{useLanguage:()=>({locale})},"@/lib/family-gateway-payments":{familyPaymentCsrf:()=>{throw new Error("No requests in static render");}},"@/lib/gateway-settlements":{formatSettlementAmount},"@/lib/manual-fee-collection":{currentSchoolLocalTime,schoolLocalTimeToInstant},"@/lib/gateway-settlement-bank-evidence":bank,"@/lib/i18n/gateway-settlement-bank-messages":{settlementBankMessage:message},"./settlement-bank-evidence-panel.module.css":{default:new Proxy({},{get:(_,key)=>String(key)})}};
  const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;new Function("require","exports",compiled)(name=>{if((name.startsWith("@/")||name.endsWith(".css"))&&!modules[name])throw new Error(`Unreviewed dependency ${name}`);return modules[name]??require(name);},exports);
  return renderToStaticMarkup(React.createElement(exports.SettlementBankEvidencePanel,actualProps));
}
function actual(name,bindings){
  let found;function visit(node){if(ts.isFunctionDeclaration(node)&&node.name?.getText(ast)===name)found=node;if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name&&node.initializer&&ts.isCallExpression(node.initializer))found=node.initializer.arguments[0];if(ts.isCallExpression(node)&&node.expression.getText(ast)==="useEffect"){const text=node.arguments[0]?.getText(ast)??"";if(name==="lifecycleEffect"&&text.includes("lifecycle.current+=1"))found=node.arguments[0];if(name==="navigationEffect"&&text.includes("beforeunload"))found=node.arguments[0];}ts.forEachChild(node,visit);}visit(ast);assert.ok(found,`Missing actual ${name}`);
  const declaration=ts.isFunctionDeclaration(found)?`${found.getText(ast)}\nconst actual=${name};`:`const actual=${found.getText(ast)};`;
  const compiled=ts.transpileModule(declaration,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;return new Function(...Object.keys(bindings),`${compiled}\nreturn actual;`)(...Object.values(bindings));
}
function dispatchHarness(send,csrf=async()=>"synthetic_csrf"){
  const scope={schoolId,requestId,settlementReference,disabled:false},state={locked:false,saving:false,error:null,guards:[],loads:0,draft:{...draft},rejectConfirmed:true},attempt={current:null},writing={current:false},read={current:null},unknown={current:false},lifecycle={current:1};
  const bindings={scope,disabled:false,attempt,writing,read,unknown,lifecycle,BankEvidenceWriteError:bank.BankEvidenceWriteError,bankEvidenceErrorCode:bank.bankEvidenceErrorCode,familyPaymentCsrf:csrf,sendBankEvidence:send,onGuardChange:value=>state.guards.push(value),setLocked:value=>state.locked=value,setSaving:value=>state.saving=value,setError:value=>state.error=value,setFormOpen:value=>state.formOpen=value,setRejectId:value=>state.rejectId=value,setRejectConfirmed:value=>state.rejectConfirmed=value,setAck:value=>state.ack=value,setDraft:value=>state.draft=typeof value==="function"?value(state.draft):value,load:async()=>state.loads++};
  return{scope,state,attempt,writing,read,unknown,lifecycle,dispatch:actual("dispatch",bindings),bindings};
}
const command=()=>bank.prepareBankEvidence(context,draft,clientId,new Date("2026-10-23T05:00:00Z"));
test("English and Hindi maintain explicit non-reconciliation boundaries and known error/reason translations",()=>{
  assert.deepEqual(Object.keys(messages.en).sort(),Object.keys(messages.hi).sort());for(const key of Object.keys(messages.hi))assert.match(messages.hi[key],/[\u0900-\u097f]/,key);
  for(const locale of["en","hi"]){const html=render(locale);for(const key of["title","boundary","inspect","idle"])assert.ok(html.includes(message(locale,key)),key);assert.doesNotMatch(html,/<form|SYN-UTR|90071992|Activate provider|Mark.*reconciled|Post.*journal/i);}
});
test("read-only history shows actual status amounts reference mismatches and escaped reasons without write forms",()=>{
  const html=render("en",{data:{...context,canRecord:false,canReject:false,evidence:[{...evidence,reviewReasons:bank.bankReviewReasons}]}});assert.ok(html.includes(message("en","readOnly")));assert.match(html,/INR 9,00,71,992\.54/);assert.match(html,/Synthetic school bank|SYN-UTR 001/);assert.match(html,/&lt;script&gt;bank review&lt;\/script&gt;/);assert.doesNotMatch(html,/<script>|<form|Prepare new bank-evidence draft/);for(const reason of bank.bankReviewReasons)assert.ok(html.includes(message("en",`reason.${reason}`)),reason);
});
test("new record form uses school-zone native labelled time amount verification and explicit receipt attestation",()=>{
  const html=render("en",{data:context,formOpen:true,draft});assert.match(html,/aria-label="Record observed bank-credit evidence"/);assert.match(html,/type="datetime-local" step="1"/);assert.match(html,/Asia\/Kolkata/);assert.match(html,/type="text" inputMode="decimal"/);assert.match(html,/type="checkbox" required="" checked/);assert.match(html,/minLength="3" maxLength="240"/);assert.match(html,/htmlFor=|for="/);assert.doesNotMatch(html,/type="number"|Post journal|Choose financial year/i);
  const hi=render("hi",{data:context,formOpen:true,draft});for(const key of["form","noGuess","confirm","save","timeHelp"])assert.ok(hi.includes(message("hi",key)),key);
});
test("independent rejection form retains original credit and only appears with per-record live hint",()=>{
  const rejectable={...evidence,canReject:true},data={...context,evidence:[rejectable]},html=render("en",{data,rejectId:evidence.id,rejectReason:"Synthetic independent correction",rejectConfirmed:true});assert.match(html,/aria-label="Reject mistaken unposted bank evidence"/);assert.ok(html.includes(message("en","rejectionBoundary")));assert.match(html,/SYN-UTR 001/);assert.match(html,/type="checkbox" required="" checked/);assert.doesNotMatch(render("en",{data:context}),/Review mistaken evidence|Reject mistaken unposted bank evidence/);
  const rejected={...evidence,status:"REJECTED",rejection:{id:id(8),reason:"Synthetic correction",rejectedAt:"2026-10-23T06:00:00Z",rejectedBy:"Synthetic reviewer"}};const history=render("hi",{data:{...context,evidence:[rejected]}});assert.ok(history.includes(message("hi","status.REJECTED")));assert.match(history,/SYN-UTR 001|Synthetic correction/);assert.doesNotMatch(history,/aria-label="Reject/);
});
test("empty limited history fallback account identity loading error and stale guards remain honest",()=>{
  assert.ok(render("en",{data:{...context,evidence:[]}}).includes(message("en","empty")));assert.ok(render("en",{data:{...context,hasMoreEvidence:true}}).includes(message("en","historyLimit")));
  const historical=render("en",{data:{...context,canRecord:false,bankAccounts:[]}});assert.match(historical,new RegExp(accountId));assert.doesNotMatch(historical,/Synthetic school bank/);
  const loading=render("en",{busy:true});assert.match(loading,/aria-busy="true"/);assert.match(loading,/role="status"/);assert.match(render("hi",{error:"scope"}),/role="alert"/);
  for(const changes of[{data:context,stale:true},{data:{...context,schoolId:id(9)}},{data:{...context,requestId:id(9)}},{data:{...context,settlementReference:"synthetic_other"}}])assert.doesNotMatch(render("en",changes),/SYN-UTR|Synthetic maker|9,00,71/);
  assert.doesNotMatch(render("en",{data:context},{disabled:true}),/SYN-UTR|<form|<button/);
});
test("unknown command locks record and rejection forms and exposes exact retry not a new command",()=>{
  const html=render("en",{data:context,formOpen:true,draft,locked:true,error:"unknown"});assert.match(html,/<fieldset disabled/);assert.ok(html.includes(message("en","unknown")));assert.ok(html.includes(message("en","retry")));assert.match(source,/dispatch\(attempt\.current\)/);assert.doesNotMatch(source,/localStorage|sessionStorage|indexedDB/);
});
test("actual unknown dispatch preserves command through known retry failure and accepts only same-object recovery",async()=>{
  let calls=0;const prepared=command(),h=dispatchHarness(async sent=>{calls++;assert.equal(sent,prepared);if(calls===1)throw new bank.BankEvidenceWriteError("unknown","unknown");if(calls===2)throw new bank.BankEvidenceWriteError("rejected","reserved");return evidence;});
  await h.dispatch(prepared);assert.equal(h.state.locked,true);assert.equal(h.attempt.current,prepared);assert.equal(h.state.error.code,"unknown");await h.dispatch({...prepared});assert.equal(calls,1);
  await h.dispatch(prepared);assert.equal(h.state.locked,true);assert.equal(h.state.error.code,"unknown");assert.equal(h.attempt.current,prepared);
  await h.dispatch(prepared);assert.equal(calls,3);assert.equal(h.state.locked,false);assert.equal(h.attempt.current,null);assert.equal(h.unknown.current,false);assert.equal(h.state.ack.evidence,evidence);assert.equal(h.state.loads,1);
});
test("actual initial definite failure unlocks draft clears confirmation and permits a separately prepared new ID",async()=>{
  let calls=0;const h=dispatchHarness(async()=>{calls++;throw new bank.BankEvidenceWriteError("rejected","reserved");});await h.dispatch(command());assert.equal(h.state.locked,false);assert.equal(h.attempt.current,null);assert.equal(h.state.draft.confirmed,false);assert.equal(h.state.rejectConfirmed,false);assert.equal(h.state.error.code,"reserved");
  const another=bank.prepareBankEvidence(context,draft,id(9),new Date("2026-10-23T05:00:00Z"));await h.dispatch(another);assert.equal(calls,2);assert.equal(h.unknown.current,false);
});
test("actual CSRF failure after uncertainty cannot release original command and simultaneous dispatch is blocked",async()=>{
  let tokens=0,calls=0;const h=dispatchHarness(async()=>{calls++;throw new bank.BankEvidenceWriteError("unknown","unknown");},async()=>{tokens++;if(tokens===2)throw new Error("private");return"synthetic_csrf";});const prepared=command();await h.dispatch(prepared);await h.dispatch(prepared);assert.equal(calls,1);assert.equal(h.state.locked,true);assert.equal(h.attempt.current,prepared);assert.equal(h.state.error.code,"unknown");
  let finish,posts=0;const pending=dispatchHarness(async()=>{posts++;return new Promise(resolve=>{finish=resolve;});});const first=pending.dispatch(prepared);await Promise.resolve();await pending.dispatch(prepared);assert.equal(posts,1);finish(evidence);await first;
});
test("actual rejection dispatch freezes the independent decision and retained result just like recording",async()=>{
  const row={...evidence,canReject:true},data={...context,evidence:[row]},prepared=bank.prepareBankEvidenceRejection(data,row,"Synthetic correction",true,id(8)),result={...evidence,status:"REJECTED",rejection:{id:id(9),reason:"Synthetic correction",rejectedAt:"2026-10-23T06:00:00Z",rejectedBy:"Synthetic reviewer"}};let calls=0;const h=dispatchHarness(async sent=>{calls++;assert.equal(sent,prepared);if(calls===1)throw new bank.BankEvidenceWriteError("unknown","unknown");return result;});await h.dispatch(prepared);assert.equal(h.state.locked,true);await h.dispatch(prepared);assert.equal(h.state.ack.evidence.status,"REJECTED");assert.equal(h.state.ack.evidence.bankReference,evidence.bankReference);assert.equal(h.state.loads,1);
});
test("actual scope cleanup aborts private reads but never clears a pending or unknown write guard",()=>{
  for(const pendingWrite of[false,true]){const h=dispatchHarness(async()=>evidence);h.attempt.current=pendingWrite?command():null;h.read.current=new AbortController();const controller=h.read.current,cleanup=actual("lifecycleEffect",h.bindings)();cleanup();assert.equal(controller.signal.aborted,true);assert.equal(h.read.current,null);assert.deepEqual(h.state.guards,pendingWrite?[]:[false]);}
});
test("actual pending-write navigation guard prevents anchor exit and warns before browser unload",()=>{
  const events={},removed=[],feedback={current:{focus:()=>{events.focused=true;}}},win={addEventListener:(name,fn)=>events[name]=fn,removeEventListener:name=>removed.push(name),navigation:{addEventListener:(name,fn)=>events[name]=fn,removeEventListener:name=>removed.push(name)}},doc={addEventListener:(name,fn)=>events[name]=fn,removeEventListener:name=>removed.push(name)};
  class FakeElement{closest(){return{getAttribute:()=>"/workspace/other"};}}
  const cleanup=actual("navigationEffect",{locked:true,window:win,document:doc,Element:FakeElement,feedback})();const event={target:new FakeElement(),preventDefault:()=>events.prevented=true,stopPropagation:()=>events.stopped=true};events.click(event);assert.equal(events.prevented,true);assert.equal(events.stopped,true);assert.equal(events.focused,true);const unload={preventDefault:()=>{},returnValue:null};events.beforeunload(unload);assert.equal(unload.returnValue,"");let cancelled=false;events.navigate({cancelable:true,hashChange:false,preventDefault:()=>cancelled=true});assert.equal(cancelled,true);cleanup();assert.deepEqual(removed,["beforeunload","click","navigate"]);
});
test("new explicit draft clears prior success/error confirmations and never auto-fills provider amount or credit time",()=>{
  const state={};actual("startDraft",{blocked:false,context,attempt:{current:null},emptyDraft:()=>({bankAccountId:"",amount:"",actualLocal:"",utcOffset:"",verificationKind:"BANK_APP_CREDIT",bankReference:"",confirmed:false,reason:""}),setDraft:value=>state.draft=value,setFormOpen:value=>state.formOpen=value,setRejectId:value=>state.rejectId=value,setRejectReason:value=>state.rejectReason=value,setRejectConfirmed:value=>state.rejectConfirmed=value,setError:value=>state.error=value,setAck:value=>state.ack=value})();assert.equal(state.formOpen,true);assert.equal(state.draft.amount,"");assert.equal(state.draft.actualLocal,"");assert.equal(state.draft.confirmed,false);assert.equal(state.error,null);assert.equal(state.ack,null);
});
test("themed phone cards native labels focus and reduced motion have no financial success badge",()=>{
  for(const pattern of[/var\(--theme-card\)/,/var\(--theme-error-text\)/,/var\(--theme-warning-text\)/,/min-height:44px/,/:focus-visible/,/@media\(max-width:600px\)/,/prefers-reduced-motion:reduce/])assert.match(css,pattern);assert.doesNotMatch(css,/#(?:[0-9a-f]{3}){1,2}\b|theme-success/i);assert.match(source,/aria-describedby/);assert.match(source,/type="checkbox" required/);
});
