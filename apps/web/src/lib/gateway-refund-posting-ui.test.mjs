import assert from "node:assert/strict";
import test from "node:test";
import {readFileSync} from "node:fs";
import {createRequire} from "node:module";
import React from "react";
import {renderToStaticMarkup} from "react-dom/server";
import ts from "typescript";
import * as postings from "./gateway-refund-postings.ts";
import { FamilyPaymentAccessError } from "./family-gateway-payments.ts";
import {gatewayRefundPostingMessages as messages} from "./i18n/gateway-refund-posting-messages.ts";
const source=readFileSync(new URL("../components/workspace/refund-posting-panel.tsx",import.meta.url),"utf8");
const schoolId="7b000000-0000-4000-8000-000000000001",refundId="7b000000-0000-4000-8000-000000000002",paymentId="7b000000-0000-4000-8000-000000000003",accountId="7b000000-0000-4000-8000-000000000004",key="7b000000-0000-4000-8000-000000000005",yearId="7b000000-0000-4000-8000-000000000006";
const context={schoolId,refundId,paymentId,receiptNumber:"SYN-POST-01",accountId,learnerName:"Synthetic <script>learner</script>",academicSessionName:"Synthetic historical year",accountStatus:"ACTIVE",currencyCode:"INR",balance:800,verifiedRefundAmount:50.25,sourceState:"READY",processedAtExact:"2026-10-18T03:30:00+05:30",processedOn:"2026-10-18",schoolTimeZone:"Asia/Kolkata",originalDayClosed:true,canPost:true,posting:null};
const year={id:yearId,name:"Synthetic named book",startsOn:"2026-04-01",endsOn:"2027-03-31",currencyCode:"INR",status:"OPEN",periods:[{startsOn:"2026-10-01",endsOn:"2026-10-31",status:"OPEN"}]};
const record={id:key,refundId,paymentId,accountId,amount:50.25,currencyCode:"INR",postingOn:"2026-10-20",postedAt:"2026-10-20T05:00:00Z",ledgerEntryId:key,accountingBatchId:key,journalId:key,journalStatus:"SUBMITTED"};
const props={schoolId,refundId,onGuardChange:()=>{},onSettled:async()=>{}};
const translate=(copy,values={})=>copy.replace(/\{(\w+)\}/g,(_,name)=>String(values[name]??`{${name}}`));
const states=changes=>Object.values({...{context,years:[year],yearId,postingOn:"2026-10-20",reason:"Synthetic reviewed posting",confirmed:false,busy:false,locked:false,recorded:false,error:"",bookError:"",notice:"",loadedFor:{schoolId,refundId}},...changes});
function module(values,locale="en"){
 let index=0;const exports={},require=createRequire(import.meta.url),react=values?{...React,useState:initial=>[index<values.length?values[index++]:initial,()=>{}]}:React;
 const bind=name=>name==="react"?react:name==="@/components/i18n/language-provider"?{useT:()=>((key,values)=>translate(locale==="hi"?messages[key]??key:key,values)),useLanguage:()=>({locale})}:name==="@/lib/gateway-refund-postings"?postings:name==="@/lib/family-gateway-payments"?{familyPaymentCsrf:async()=>"synthetic-csrf"}:name.endsWith(".module.css")?{__esModule:true,default:new Proxy({},{get:(_,key)=>`posting-${key}`})}:require(name);
 const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;new Function("require","exports",compiled)(bind,exports);return exports;
}
function actual(name,bindings){
 const ast=ts.createSourceFile("actual-posting.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let found;
 function visit(node){if(ts.isFunctionDeclaration(node)&&node.name?.getText(ast)===name)found=node;if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name&&node.initializer&&ts.isCallExpression(node.initializer))found=node.initializer.arguments[0];if(name==="feedbackEffect"&&ts.isCallExpression(node)&&node.expression.getText(ast)==="useEffect"&&node.arguments[0]?.getText(ast).includes("feedback.current?.focus"))found=node.arguments[0];ts.forEachChild(node,visit);}visit(ast);assert.ok(found,`Missing actual ${name}`);
 const declaration=ts.isFunctionDeclaration(found)?`${found.getText(ast)}\nconst actual=${name};`:`const actual=${found.getText(ast)};`;
 const compiled=ts.transpileModule(declaration,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;return new Function(...Object.keys(bindings),`${compiled}\nreturn actual;`)(...Object.values(bindings));
}
test("actual loading/selection shell never presents provider execution or a fabricated successful refund",()=>{
 const html=renderToStaticMarkup(React.createElement(module().RefundPostingPanel,{...props,refundId:undefined}));assert.match(html,/Record verified gateway refund/);assert.match(html,/It never sends money/);assert.match(html,/Choose an approved refund request/);assert.doesNotMatch(html,/Prepare reviewed refund posting|<main\b|refunded successfully/i);
});
test("actual native form shows original exact timestamp closed-day evidence named years separate signed balance and amount",()=>{
 const html=renderToStaticMarkup(React.createElement(module(states()).RefundPostingPanel,props));assert.match(html,/₹800\.00/);assert.match(html,/₹50\.25/);assert.match(html,/has not restored dues/);assert.match(html,/2026-10-18T03:30:00\+05:30/);assert.match(html,/It stays closed/);assert.match(html,/Synthetic named book/);assert.match(html,/aria-label="Prepare reviewed refund posting"/);assert.match(html,/type="date" required/);assert.match(html,/type="checkbox" required/);assert.match(html,/&lt;script&gt;learner&lt;\/script&gt;/);assert.doesNotMatch(html,/<script>/);
});
test("closed account unavailable evidence and role denial remove posting controls; retained journal states remain honest",()=>{
 for(const change of[{accountStatus:"CLOSED"},{sourceState:"NO_VERIFIED_SUCCESS",verifiedRefundAmount:null},{sourceState:"REVIEW_REQUIRED"}]){const html=renderToStaticMarkup(React.createElement(module(states({context:{...context,...change,canPost:false}})).RefundPostingPanel,props));assert.doesNotMatch(html,/Prepare reviewed refund posting/);assert.match(html,/has not restored dues/);}
 for(const [journalStatus,copy]of[["SUBMITTED","Journal awaiting independent approval"],["POSTED","Journal posted to the general ledger"],["REJECTED","Journal rejected — accounting review required"],["REVERSED","Journal reversed — accounting review required"]]){const html=renderToStaticMarkup(React.createElement(module(states({context:{...context,sourceState:"POSTED",canPost:false,balance:850.25,posting:{...record,journalStatus}}})).RefundPostingPanel,props));assert.ok(html.includes(copy));assert.match(html,/Dues restored on original account/);assert.match(html,/original receipt is unchanged/);assert.doesNotMatch(html,/Prepare reviewed refund posting|awaiting recording has not restored dues/);}
});
test("actual loading or unknown states disable mutable financial form and expose exact recovery; stale scope stays hidden",()=>{
 const html=renderToStaticMarkup(React.createElement(module(states({locked:true,error:postings.refundPostingErrors.unknown})).RefundPostingPanel,props));assert.match(html,/<fieldset disabled/);assert.match(html,/Retry this exact refund posting command/);assert.match(html,/role="alert"/);assert.match(html,/another tab/);
 const stale=renderToStaticMarkup(React.createElement(module(states({loadedFor:{schoolId:key,refundId}})).RefundPostingPanel,props));assert.doesNotMatch(stale,/SYN-POST-01|Synthetic named book|Prepare reviewed refund posting/);
 const noBooks=renderToStaticMarkup(React.createElement(module(states({years:[],bookError:postings.refundPostingErrors.books})).RefundPostingPanel,props));assert.match(noBooks,/No eligible open matching-currency/);assert.match(noBooks,/aria-disabled="true">Record verified refund and restore dues/);
});
function dispatchHarness(send,csrf=async()=>"synthetic-csrf"){
 const state={busy:false,locked:false,error:"",guards:[],refreshes:0,clears:0},attempt={current:null},unknown={current:false},accepted={current:false},acknowledged={current:null},writing={current:false},read={current:null},lifecycle={current:1};
 const dispatch=actual("dispatch",{disabled:false,attempt,unknown,accepted,acknowledged,writing,read,lifecycle,FamilyPaymentAccessError,RefundPostingWriteError:postings.RefundPostingWriteError,refundPostingErrors:postings.refundPostingErrors,refundPostingError:postings.refundPostingError,refundPostingAccessLost:postings.refundPostingAccessLost,clearPrivate:()=>{state.clears++;attempt.current=null;accepted.current=false;unknown.current=false;state.locked=false;},setBusy:value=>state.busy=value,setLocked:value=>state.locked=value,setError:value=>state.error=value,setNotice:value=>state.notice=value,setRecorded:value=>state.recorded=value,onGuardChange:value=>state.guards.push(value),familyPaymentCsrf:csrf,sendRefundPosting:send,sendRefundJournalRepair:send,refreshAccepted:async()=>state.refreshes++});
 return{state,attempt,unknown,accepted,acknowledged,writing,read,lifecycle,dispatch};
}
const prepared=()=>postings.prepareRefundPosting(context,[year],yearId,"2026-10-20","Synthetic reviewed posting",key,"2026-10-20");
const repairContext=()=>({...context,sourceState:"POSTED",canPost:false,canRepairJournal:true,accountStatus:"CLOSED",posting:{...record,journalStatus:"REJECTED",journalPostingOn:"2026-10-20",journalFinancialYearId:yearId}});
const repairPrepared=()=>postings.prepareRefundJournalRepair(repairContext(),[year],yearId,"2026-10-20","Synthetic accounting-only review",key,"2026-10-20");
const repairResult=()=>({refundPostingId:key,refundId,previousBatchId:key,previousJournalId:key,accountingBatchId:paymentId,journalId:accountId,journalStatus:"SUBMITTED",financialYearId:yearId,postingOn:"2026-10-20"});

test("actual staff repair form names the exact retained journal and distinguishes accounting correction from restoring dues",()=>{
 const html=renderToStaticMarkup(React.createElement(module(states({context:repairContext()})).RefundPostingPanel,props));
 assert.match(html,/aria-label="Prepare accounting-only refund journal correction"/);assert.match(html,/Submit replacement refund journal/);
 assert.match(html,/Current refund journal accounting date/);assert.match(html,/Accounting-only repair does not reopen it/);
 assert.match(html,/It does not issue money, restore dues again/);assert.match(html,/independent approval without another refund or fee debit/);
 assert.doesNotMatch(html,/type="number"|>Record verified refund and restore dues<|has not restored dues/);
 for(const status of["SUBMITTED","POSTED","REQUIRES_REVIEW"]){const value=repairContext();value.canRepairJournal=false;value.posting.journalStatus=status;const safe=renderToStaticMarkup(React.createElement(module(states({context:value})).RefundPostingPanel,props));assert.doesNotMatch(safe,/Submit replacement refund journal/);}
});
test("actual Hindi accounting-only repair form and recovery text use the maintained catalogue",()=>{
 const html=renderToStaticMarkup(React.createElement(module(states({context:repairContext()}),"hi").RefundPostingPanel,props));
 assert.ok(html.includes(messages["Submit replacement refund journal"]));assert.ok(html.includes(messages["Replacement journal accounting date"]));
 assert.doesNotMatch(html,/Prepare accounting-only refund journal correction|Submit replacement refund journal/);
 for(const error of[postings.refundPostingErrors.repairEligibility,postings.refundPostingErrors.repairUnknown,postings.refundPostingErrors.repairRejected])assert.match(messages[error],/[\u0900-\u097f]/);
});
test("actual journal dispatch freezes unknown recovery and acknowledges only a submitted accounting-only correction",async()=>{
 const command=repairPrepared();let calls=0;
 const h=dispatchHarness(async sent=>{calls++;assert.equal(sent,command);if(calls===1)throw new postings.RefundPostingWriteError("unknown",postings.refundPostingErrors.repairUnknown);return repairResult();});
 await h.dispatch(command);assert.equal(h.state.locked,true);assert.equal(h.state.error,postings.refundPostingErrors.repairUnknown);
 await h.dispatch({...command,expectedJournalId:schoolId});assert.equal(calls,1);
 await h.dispatch(command);assert.equal(calls,2);assert.equal(h.accepted.current,true);assert.equal(h.state.refreshes,1);
 assert.match(h.state.notice,/No second refund or fee debit/);assert.equal(h.acknowledged.current.accountingBatchId,paymentId);
});
test("actual repair submit requires confirmation and freezes current predecessor rather than issuing another economic posting",()=>{
 const sent=[];const bindings={busy:false,locked:false,disabled:false,confirmed:true,context:repairContext(),loadedFor:{schoolId,refundId},schoolId,refundId,years:[year],yearId,postingOn:"2026-10-20",reason:"Synthetic reviewed correction",crypto:{randomUUID:()=>key},refundSchoolToday:()=>"2026-10-20",prepareRefundJournalRepair:postings.prepareRefundJournalRepair,prepareRefundPosting:()=>{throw new Error("Economic writer must not be used for repair");},dispatch:command=>sent.push(command),refundPostingError:postings.refundPostingError,setError:()=>{}};
 actual("submit",bindings)({preventDefault:()=>{}});assert.equal(sent.length,1);assert.equal(sent[0].kind,"JOURNAL_REPAIR");assert.equal(JSON.parse(sent[0].body).expectedJournalId,key);
 actual("submit",{...bindings,confirmed:false})({preventDefault:()=>{}});actual("submit",{...bindings,disabled:true})({preventDefault:()=>{}});assert.equal(sent.length,1);
});
test("actual repair acknowledgement refresh cannot release its guard on the old journal or changed economic identity",async()=>{
 const command=repairPrepared(),state={locked:true},attempt={current:command},accepted={current:true},unknown={current:true},acknowledged={current:repairResult()},writing={current:false},read={current:null},lifecycle={current:1};let fresh=repairContext(),allow=false;
 const refresh=actual("refreshAccepted",{attempt,accepted,unknown,acknowledged,writing,read,lifecycle,load:async()=>fresh,onSettled:async()=>{if(!allow)throw new Error("Synthetic parent refresh unavailable");},refundPostingErrors:postings.refundPostingErrors,refundPostingPaise:postings.refundPostingPaise,setBusy:()=>{},setError:value=>state.error=value,setLocked:value=>state.locked=value,setRecorded:()=>{},onGuardChange:()=>{},setReason:()=>{},setConfirmed:()=>{},setYearId:()=>{},setPostingOn:()=>{}});
 await refresh();assert.equal(state.locked,true);assert.equal(attempt.current,command);
 fresh={...fresh,posting:{...record,accountingBatchId:paymentId,journalId:accountId}};await refresh();assert.equal(state.locked,true);
 allow=true;await refresh();assert.equal(state.locked,false);assert.equal(attempt.current,null);assert.equal(acknowledged.current,null);
});
test("actual dispatch guards synchronously before send double clicks cannot substitute body and exact unknown retry remains frozen",async()=>{
 let calls=0;const command=prepared(),h=dispatchHarness(async sent=>{assert.equal(sent,command);calls++;throw new postings.RefundPostingWriteError(calls===1?"unknown":"rejected",postings.refundPostingErrors.rejected);});await h.dispatch(command);assert.equal(h.state.locked,true);assert.equal(h.attempt.current,command);await h.dispatch({...command,clientRequestId:schoolId});assert.equal(calls,1);await h.dispatch(command);assert.equal(calls,2);assert.equal(h.unknown.current,true);assert.equal(h.state.error,postings.refundPostingErrors.unknown);assert.deepEqual(h.state.guards,[true,true]);
 let finish,started;const ready=new Promise(resolve=>started=resolve),pendingHarness=dispatchHarness(async()=>{started();return new Promise(resolve=>finish=resolve);});const pending=pendingHarness.dispatch(command);await ready;await pendingHarness.dispatch(command);assert.equal(pendingHarness.state.locked,true);finish(record);await pending;assert.equal(pendingHarness.accepted.current,true);assert.equal(pendingHarness.state.refreshes,1);
});
test("actual dispatch clears private retry only for real access loss; later 409 or lifecycle exit cannot invent acceptance",async()=>{
 const command=prepared(),denied=dispatchHarness(async()=>{throw new postings.RefundPostingWriteError("rejected",postings.refundPostingErrors.scope);});await denied.dispatch(command);assert.equal(denied.state.clears,1);assert.equal(denied.attempt.current,null);
 let finish,started;const ready=new Promise(resolve=>started=resolve),late=dispatchHarness(async()=>{started();return new Promise(resolve=>finish=resolve);});const pending=late.dispatch(command);await ready;late.lifecycle.current++;finish(record);await pending;assert.equal(late.accepted.current,false);assert.equal(late.state.refreshes,0);
});
test("actual CSRF session admission 401/403 clears private memory before send; generic token failure is not treated as loss proof",async()=>{
 let calls=0;for(const status of[401,403]){const h=dispatchHarness(async()=>calls++,async()=>{throw new FamilyPaymentAccessError(status);});await h.dispatch(prepared());assert.equal(h.state.clears,1);assert.equal(h.state.error,status===401?postings.refundPostingErrors.session:postings.refundPostingErrors.scope);}assert.equal(calls,0);
 const h=dispatchHarness(async()=>calls++,async()=>{throw new Error("Synthetic token temporarily unavailable");});await h.dispatch(prepared());assert.equal(h.state.clears,0);assert.equal(h.state.error,postings.refundPostingErrors.csrf);assert.equal(calls,0);
});
test("actual acknowledged refresh retries reads only and releases original command after scoped and parent refresh both succeed",async()=>{
 const command=prepared(),state={guards:[],locked:true},attempt={current:command},accepted={current:true},unknown={current:true},acknowledged={current:record},writing={current:false},read={current:null},lifecycle={current:1};let allow=false,calls=0,fresh=context;
 const refresh=actual("refreshAccepted",{attempt,accepted,unknown,acknowledged,writing,read,lifecycle,load:async target=>{calls++;assert.equal(target,refundId);return fresh;},onSettled:async()=>{if(!allow)throw new Error("Synthetic private unavailable");},refundPostingErrors:postings.refundPostingErrors,refundPostingPaise:postings.refundPostingPaise,setBusy:value=>state.busy=value,setError:value=>state.error=value,setLocked:value=>state.locked=value,setRecorded:()=>{},onGuardChange:value=>state.guards.push(value),setReason:()=>{},setConfirmed:()=>{},setYearId:()=>{},setPostingOn:()=>{}});
 await refresh();assert.equal(attempt.current,command);assert.equal(state.error,postings.refundPostingErrors.unavailable);fresh={...context,sourceState:"POSTED",canPost:false,posting:{...record,id:schoolId}};await refresh();assert.equal(state.locked,true);fresh={...fresh,posting:record};await refresh();assert.equal(attempt.current,command);assert.equal(accepted.current,true);assert.equal(state.locked,true);allow=true;await refresh();assert.equal(calls,4);assert.equal(attempt.current,null);assert.equal(unknown.current,false);assert.equal(acknowledged.current,null);assert.equal(state.locked,false);assert.deepEqual(state.guards,[false]);
});
test("actual submit requires explicit confirmation current scope reciprocal guard and canonical named book before preparing one UUID",()=>{
 const sent=[];const bindings={busy:false,locked:false,disabled:false,confirmed:true,context,loadedFor:{schoolId,refundId},schoolId,refundId,years:[year],yearId,postingOn:"2026-10-20",reason:"Synthetic reviewed posting",crypto:{randomUUID:()=>key},refundSchoolToday:()=>"2026-10-20",prepareRefundPosting:postings.prepareRefundPosting,dispatch:command=>sent.push(command),refundPostingError:postings.refundPostingError,setError:()=>{}};
 actual("submit",bindings)({preventDefault:()=>{}});assert.equal(sent.length,1);assert.equal(JSON.parse(sent[0].body).clientRequestId,key);
 for(const changed of[{disabled:true},{busy:true},{locked:true},{confirmed:false},{loadedFor:{schoolId:key,refundId}},{refundId:key}])actual("submit",{...bindings,...changed})({preventDefault:()=>{}});assert.equal(sent.length,1);
});
test("actual read retains acknowledged posting even when book access unavailable, and unknown absence does not discard frozen command",async()=>{
 const state={},read={current:null},lifecycle={current:1},attempt={current:prepared()};let cleared=0;
 const bindings={read,lifecycle,attempt,schoolId,loadRefundPosting:async()=>context,loadRefundPostingYears:async()=>{throw new Error(postings.refundPostingErrors.books);},refundPostingAccessLost:postings.refundPostingAccessLost,refundPostingError:postings.refundPostingError,refundPostingErrors:postings.refundPostingErrors,clearPrivate:()=>cleared++,setBusy:value=>state.busy=value,setError:value=>state.error=value,setContext:value=>state.context=value,setLoadedFor:value=>state.loadedFor=value,setYears:value=>state.years=value,setBookError:value=>state.bookError=value};
 const load=actual("load",bindings);assert.equal(await load(refundId),context);assert.equal(state.context,context);assert.deepEqual(state.years,[]);assert.equal(state.bookError,postings.refundPostingErrors.books);
 const missing=actual("load",{...bindings,loadRefundPosting:async()=>{throw new Error("Synthetic 404 does not prove a write did not commit");}});assert.equal(await missing(refundId),null);assert.ok(attempt.current);assert.equal(cleared,0);assert.equal(state.context,context);
 const lost=actual("load",{...bindings,loadRefundPosting:async()=>{throw new Error(postings.refundPostingErrors.session);}});assert.equal(await lost(refundId),null);assert.equal(cleared,1);
});
test("actual access-loss cleanup drops private record form and frozen command from memory without browser persistence",()=>{
 const attempt={current:prepared()},unknown={current:true},accepted={current:true},acknowledged={current:record},state={};
 const bindings={attempt,unknown,accepted,acknowledged,onGuardChange:value=>state.guard=value};for(const name of["Context","Years","Reason","YearId","PostingOn","Confirmed","Locked","Recorded","Notice"])bindings[`set${name}`]=value=>state[name]=value;
 actual("clearPrivate",bindings)();assert.equal(attempt.current,null);assert.equal(acknowledged.current,null);assert.equal(unknown.current,false);assert.equal(accepted.current,false);assert.equal(state.Context,null);assert.deepEqual(state.Years,[]);assert.equal(state.Reason,"");assert.equal(state.Locked,false);assert.equal(state.guard,false);
});
test("stable acknowledged feedback gets visible focus; all controlled copy has Hindi with unchanged placeholders and semantic mobile CSS",()=>{
 let focus=0;actual("feedbackEffect",{notice:"",feedback:{current:{focus:()=>focus++}}})();actual("feedbackEffect",{notice:"Synthetic acknowledged record",feedback:{current:{focus:()=>focus++}}})();assert.equal(focus,1);
 const keys=new Set(Object.values(postings.refundPostingErrors)),ast=ts.createSourceFile("actual.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
 function copy(node){if(!node)return;if(ts.isStringLiteral(node)){if(node.text.trim())keys.add(node.text);}else if(ts.isConditionalExpression(node)){copy(node.whenTrue);copy(node.whenFalse);}}
 function visit(node){if(ts.isCallExpression(node)&&["t","setNotice"].includes(node.expression.getText(ast)))copy(node.arguments[0]);ts.forEachChild(node,visit);}visit(ast);
 for(const key of keys){if(["Original gateway receipt","Current account balance (signed)"].includes(key))continue;assert.equal(typeof messages[key],"string",`Missing Hindi: ${key}`);assert.deepEqual([...messages[key].matchAll(/\{(\w+)\}/g)].map(x=>x[1]),[...key.matchAll(/\{(\w+)\}/g)].map(x=>x[1]));}
 const hindi=renderToStaticMarkup(React.createElement(module(states(),"hi").RefundPostingPanel,props));assert.ok(hindi.includes(messages["Record verified gateway refund"]));assert.doesNotMatch(source,/localStorage|sessionStorage|window\.confirm|dangerouslySetInnerHTML/);assert.match(source,/beforeunload/);
 const css=readFileSync(new URL("../components/workspace/refund-posting-panel.module.css",import.meta.url),"utf8");assert.match(css,/min-height:44px/);assert.match(css,/focus-visible/);assert.match(css,/feedback:focus/);assert.match(css,/max-width:600px/);assert.match(css,/prefers-reduced-motion/);assert.match(css,/var\(--theme-error/);assert.doesNotMatch(css,/#(?:[0-9a-f]{3}){1,2}\b/i);
});
