import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as reopening from "./refund-account-reopening.ts";
import { refundAccountReopeningMessages as messages } from "./i18n/refund-account-reopening-messages.ts";

const source=readFileSync(new URL("../components/workspace/refund-account-reopening-panel.tsx",import.meta.url),"utf8");
const school="79000000-0000-4000-8000-000000000001",refundId="79000000-0000-4000-8000-000000000002",paymentId="79000000-0000-4000-8000-000000000003",accountId="79000000-0000-4000-8000-000000000004",reviewId="79000000-0000-4000-8000-000000000005",key="79000000-0000-4000-8000-000000000006";
const closure={closedAt:"2026-10-18T05:00:00Z",reason:"Synthetic <script>original closure</script>",closedByName:"Synthetic closing officer"};
const review={id:reviewId,refundId,paymentId,accountId,learnerName:"Synthetic learner",sessionName:"Synthetic historical session",state:"SUBMITTED",reason:"Synthetic review",requestedAt:"2026-10-18T06:00:00Z",makerName:"Synthetic maker",isMaker:false,canApprove:true,canReject:true,canCancel:false,closedAccountVersion:2,originalClosure:closure,decision:null};
const context={schoolId:school,refundId,paymentId,accountId,learnerName:"Synthetic learner",sessionName:"Synthetic historical session",currencyCode:"INR",schoolTimeZone:"Asia/Kolkata",sourceState:"HOLD",accountState:"CLOSED",accountVersion:2,currentClosure:closure,currentBalance:-29.29,confirmedUnpostedRefundAmount:50.25,reopeningRequired:true,canRequest:true,refundPosted:false,hasMoreHistory:false,requests:[review]};
const filter={schoolId:school,status:"SUBMITTED",query:"",page:0};
const queue={schoolId:school,status:"SUBMITTED",query:null,page:0,pageSize:25,total:1,hasMore:false,refundPosted:false,items:[review]};
const props={schoolId:school,refundId,onGuardChange:()=>{},onSettled:async()=>{}};
const translate=(copy,values={})=>copy.replace(/\{(\w+)\}/g,(_,name)=>String(values[name]??`{${name}}`));
const states=(changes={})=>Object.values({...{filter,selectedRefund:refundId,queue,context,reason:"",confirmed:false,busy:false,locked:false,error:"",notice:"",recorded:false,loadedFor:{schoolId:school,refundId}},...changes});
function module(values,locale="en"){
 let state=0;const exports={},require=createRequire(import.meta.url),react=values?{...React,useState:initial=>[state<values.length?values[state++]:initial,()=>{}]}:React;
 const bind=name=>name==="react"?react:name==="@/components/i18n/language-provider"?{useT:()=>((key,values)=>translate(locale==="hi"?messages[key]??key:key,values)),useLanguage:()=>({locale})}:name==="@/lib/refund-account-reopening"?reopening:name==="@/lib/family-gateway-payments"?{familyPaymentCsrf:async()=>"synthetic-csrf"}:name.endsWith(".module.css")?{__esModule:true,default:new Proxy({},{get:(_,key)=>`reopen-${key}`})}:require(name);
 const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
 new Function("require","exports",compiled)(bind,exports);return exports;
}
function actual(name,bindings){
 const ast=ts.createSourceFile("actual-reopening.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let found;
 function visit(node){if(ts.isFunctionDeclaration(node)&&node.name?.getText(ast)===name)found=node;if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name&&ts.isCallExpression(node.initializer??{}))found=node.initializer.arguments[0];if(name==="feedbackEffect"&&ts.isCallExpression(node)&&node.expression.getText(ast)==="useEffect"&&node.arguments[0]?.getText(ast).includes("feedback.current?.focus"))found=node.arguments[0];ts.forEachChild(node,visit);}visit(ast);assert.ok(found,`Missing actual ${name}`);
 const declaration=ts.isFunctionDeclaration(found)?`${found.getText(ast)}\nconst actual=${name};`:`const actual=${found.getText(ast)};`;
 const compiled=ts.transpileModule(declaration,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;return new Function(...Object.keys(bindings),`${compiled}\nreturn actual;`)(...Object.values(bindings));
}
test("native accessible loading shell explains that review cannot move money or academic records",()=>{
 const html=renderToStaticMarkup(React.createElement(module().RefundAccountReopeningPanel,props));
 assert.match(html,/Original fee account reopening/);assert.match(html,/Account reopening is not a refund/);assert.match(html,/does not move money, post accounting, restore dues/);assert.match(html,/aria-label="Account reopening queue filters"/);assert.match(html,/Reading or recording account reopening/);assert.doesNotMatch(html,/<main\b|refund successful|paid successfully/i);
});
test("actual current signed balance and held refund are displayed separately with original closure and native labelled form",()=>{
 const html=renderToStaticMarkup(React.createElement(module(states()).RefundAccountReopeningPanel,props));
 assert.match(html,/Current account balance \(signed\)/);assert.match(html,/-₹29\.29/);assert.match(html,/₹50\.25/);assert.match(html,/hold has not restored any dues/);assert.match(html,/aria-label="Prepare original account reopening"/);assert.match(html,/type="checkbox" required/);assert.match(html,/&lt;script&gt;original closure&lt;\/script&gt;/);assert.doesNotMatch(html,/<script>/);
});
test("source state and active-account history never turn HOLD or historical approval into current refund success",()=>{
 const approved={...review,state:"APPROVED",canApprove:false,canReject:false,decision:{id:key,decision:"APPROVE",reason:"Synthetic independent decision",decidedAt:review.requestedAt,reviewerName:"Synthetic checker"}};
 const html=renderToStaticMarkup(React.createElement(module(states({context:{...context,accountState:"ACTIVE",currentClosure:null,canRequest:false,reopeningRequired:false,requests:[approved]},queue:{...queue,items:[],total:0}})).RefundAccountReopeningPanel,props));
 assert.match(html,/Original fee account is active/);assert.match(html,/Preserved original closure/);assert.match(html,/not the account&#x27;s current status/);assert.match(html,/refund not posted/);assert.doesNotMatch(html,/Prepare original account reopening|Current account closure/);
 for(const [sourceState,copy]of[["UNAPPROVED","Refund intent is not approved"],["NO_HOLD","No confirmed unposted refund hold"],["CONFLICTING","Refund evidence needs reconciliation"]]){const view=renderToStaticMarkup(React.createElement(module(states({context:{...context,sourceState,confirmedUnpostedRefundAmount:null,canRequest:false}})).RefundAccountReopeningPanel,props));assert.ok(view.includes(copy));assert.match(view,/No confirmed unposted amount/);}
});
test("independent native review controls withhold maker approval and permit only maker cancellation",()=>{
 const maker={...review,isMaker:true,canApprove:false,canReject:false,canCancel:true};
 const html=renderToStaticMarkup(React.createElement(module(states({context:null,queue:{...queue,items:[maker]}})).RefundAccountReopeningPanel,props));
 assert.match(html,/Cancel my account reopening request/);assert.doesNotMatch(html,/Approve account reopening only|Reject account reopening/);
 const viewer=renderToStaticMarkup(React.createElement(module(states({context:null,queue:{...queue,items:[{...review,canApprove:false,canReject:false}]}})).RefundAccountReopeningPanel,props));assert.doesNotMatch(viewer,/Record account reopening decision/);
});
test("reciprocal disabled and unknown guards disable forms while exact same-command recovery stays visible",()=>{
 const other=renderToStaticMarkup(React.createElement(module(states()).RefundAccountReopeningPanel,{...props,disabled:true}));assert.match(other,/Another financial command is unresolved/);assert.match(other,/<fieldset disabled/);assert.match(other,/aria-disabled="true">Submit account reopening request/);
 const unknown=renderToStaticMarkup(React.createElement(module(states({locked:true,error:reopening.reopeningErrors.unknown})).RefundAccountReopeningPanel,props));assert.match(unknown,/Retry this exact account review command/);assert.match(unknown,/role="alert"/);assert.match(unknown,/<fieldset disabled/);assert.match(unknown,/After a forced exit, inspect review history/);
});
test("a changed school or selected refund hides old private context before the next effect or response",()=>{
 for(const changed of[{schoolId:key},{refundId:key}]){const html=renderToStaticMarkup(React.createElement(module(states()).RefundAccountReopeningPanel,{...props,...changed}));assert.doesNotMatch(html,/Synthetic learner|Synthetic closing officer|Prepare original account reopening/);}
});
test("actual load handler fences duplicate reads and drops stale responses and private data after errors",async()=>{
 const read={current:null},lifecycle={current:1},applied={current:filter},selected={current:refundId},state={};let finish,calls=0;
 const bindings={read,lifecycle,applied,selected,schoolId:school,refundId,reopeningError:reopening.reopeningError,setBusy:value=>state.busy=value,setError:value=>state.error=value,setQueue:value=>state.queue=value,setContext:value=>state.context=value,setSelectedRefund:value=>state.selected=value,setLoadedFor:value=>state.loadedFor=value,loadReopeningQueue:async()=>{calls++;return new Promise(resolve=>finish=resolve);},loadReopeningContext:async()=>context};
 const load=actual("load",bindings),pending=load(filter,refundId);assert.equal(await load(filter,refundId),false);assert.equal(calls,1);finish(queue);assert.equal(await pending,true);assert.equal(state.context,context);assert.deepEqual(state.loadedFor,{schoolId:school,refundId});
 const stale=load(filter,refundId);lifecycle.current++;finish(queue);assert.equal(await stale,false);
 const failed=actual("load",{...bindings,loadReopeningQueue:async()=>{throw new Error("SYNTHETIC PRIVATE");}});assert.equal(await failed(filter,refundId),false);assert.equal(state.queue,null);assert.equal(state.context,null);assert.equal(state.error,reopening.reopeningErrors.unavailable);
});
function dispatchHarness(send,extra={}){
 const state={busy:false,locked:false,error:"",notice:"",guards:[],refreshes:0},attempt={current:null},unknown={current:false},accepted={current:false},writing={current:false},read={current:null},lifecycle={current:1};
 const dispatch=actual("dispatch",{attempt,unknown,accepted,writing,read,lifecycle,schoolId:school,disabled:false,ReopeningWriteError:reopening.ReopeningWriteError,reopeningErrors:reopening.reopeningErrors,reopeningError:reopening.reopeningError,setBusy:value=>state.busy=value,setLocked:value=>state.locked=value,setError:value=>state.error=value,setNotice:value=>state.notice=value,setRecorded:value=>state.recorded=value,onGuardChange:value=>state.guards.push(value),familyPaymentCsrf:async()=>"synthetic-csrf",sendReopening:send,refreshAccepted:async()=>state.refreshes++,...extra});
 return{state,attempt,unknown,accepted,writing,read,lifecycle,dispatch};
}
test("actual dispatch double-click fencing and immutable uncertain retries cannot substitute a new UUID or terms",async()=>{
 const prepared=reopening.prepareReopening(context,"Synthetic review",key);let finish,calls=0;
 const h=dispatchHarness(async value=>{assert.equal(value,prepared);calls++;return new Promise(resolve=>finish=resolve);});
 const pending=h.dispatch(prepared);await Promise.resolve();await h.dispatch(prepared);await h.dispatch({...prepared,requestId:school});assert.equal(calls,1);assert.equal(h.state.locked,true);finish();await pending;assert.equal(h.accepted.current,true);assert.equal(h.state.refreshes,1);
});
test("actual unknown recovery retains its original intent even after a later definite rejection",async()=>{
 const prepared=reopening.prepareReopening(context,"Synthetic review",key);let calls=0;
 const h=dispatchHarness(async value=>{assert.equal(value,prepared);calls++;throw new reopening.ReopeningWriteError(calls===1?"unknown":"rejected",reopening.reopeningErrors.rejected);});
 await h.dispatch(prepared);await h.dispatch(prepared);assert.equal(calls,2);assert.equal(h.attempt.current,prepared);assert.equal(h.unknown.current,true);assert.equal(h.state.locked,true);assert.equal(h.state.error,reopening.reopeningErrors.unknown);assert.deepEqual(h.state.guards,[true,true]);
 const disabled=dispatchHarness(async()=>{throw new Error("must not send");},{disabled:true});await disabled.dispatch(prepared);assert.equal(disabled.attempt.current,null);
});
test("actual rejection unlocks only fresh intent and stale lifecycle cannot announce acceptance",async()=>{
 const prepared=reopening.prepareReopening(context,"Synthetic review",key),rejected=dispatchHarness(async()=>{throw new reopening.ReopeningWriteError("rejected",reopening.reopeningErrors.scope);});await rejected.dispatch(prepared);assert.equal(rejected.attempt.current,null);assert.deepEqual(rejected.state.guards,[true,false]);
 let finish,started;const sent=new Promise(resolve=>started=resolve),late=dispatchHarness(async()=>{started();return new Promise(resolve=>finish=resolve);});const pending=late.dispatch(prepared);await sent;late.lifecycle.current++;finish();await pending;assert.equal(late.accepted.current,false);assert.equal(late.state.refreshes,0);
});
test("actual accepted review guard clears only after scoped context and parent records refresh successfully",async()=>{
 const state={locked:true,guards:[]},attempt={current:reopening.prepareReopening(context,"Synthetic review",key)},accepted={current:true},unknown={current:true},writing={current:false},read={current:null},lifecycle={current:1};let allow=false;
 const refresh=actual("refreshAccepted",{attempt,accepted,unknown,writing,read,lifecycle,schoolId:school,applied:{current:filter},reopeningErrors:reopening.reopeningErrors,load:async(next,target)=>{assert.equal(target,refundId);return true;},onSettled:async()=>{if(!allow)throw new Error("Synthetic unavailable refresh");},setBusy:value=>state.busy=value,setError:value=>state.error=value,setRecorded:value=>state.recorded=value,setLocked:value=>state.locked=value,onGuardChange:value=>state.guards.push(value),setReason:()=>{},setConfirmed:()=>{}});
 await refresh();assert.equal(state.locked,true);assert.equal(accepted.current,true);assert.deepEqual(state.guards,[]);allow=true;await refresh();assert.equal(attempt.current,null);assert.equal(unknown.current,false);assert.equal(state.locked,false);assert.deepEqual(state.guards,[false]);
});
test("actual request handler checks exact current selection reciprocal guard and confirmation before preparing one UUID",()=>{
 let calls=0;const bindings={blocked:false,loadedFor:{schoolId:school,refundId},schoolId:school,refundId,confirmed:true,context,selectedRefund:refundId,reason:"Synthetic review",crypto:{randomUUID:()=>key},prepareReopening:reopening.prepareReopening,dispatch:attempt=>{calls++;assert.equal(JSON.parse(attempt.body).clientRequestId,key);},setError:()=>{},reopeningError:reopening.reopeningError};
 actual("requestReopening",bindings)({preventDefault:()=>{}});assert.equal(calls,1);
 for(const changed of[{blocked:true},{confirmed:false},{selectedRefund:key},{loadedFor:{schoolId:school,refundId:key}}])actual("requestReopening",{...bindings,...changed})({preventDefault:()=>{}});assert.equal(calls,1);
});
test("actual native decision handler requires confirmation and current scope before recording independently allowed terms",()=>{
 const sent=[],failures=[];class FormFields{constructor(fields){this.fields=fields;}get(name){return this.fields[name]??null;}}
 const bindings={blocked:false,loadedFor:{schoolId:school,refundId},schoolId:school,refundId,FormData:FormFields,prepareReopeningDecision:reopening.prepareReopeningDecision,dispatch:attempt=>sent.push(attempt),setError:error=>failures.push(error),reopeningError:reopening.reopeningError};
 const event=fields=>({preventDefault:()=>{},currentTarget:fields});
 actual("decide",bindings)(event({confirm:"on",decision:"APPROVE",reason:"Synthetic independent review"}),review);assert.equal(sent.length,1);assert.equal(JSON.parse(sent[0].body).decision,"APPROVE");
 actual("decide",bindings)(event({decision:"APPROVE",reason:"Synthetic missing confirmation"}),review);
 actual("decide",{...bindings,blocked:true})(event({confirm:"on",decision:"APPROVE",reason:"Synthetic blocked command"}),review);
 actual("decide",{...bindings,loadedFor:{schoolId:school,refundId:key}})(event({confirm:"on",decision:"APPROVE",reason:"Synthetic stale scope"}),review);assert.equal(sent.length,1);
 const maker={...review,isMaker:true,canApprove:false,canReject:false,canCancel:true};actual("decide",bindings)(event({confirm:"on",decision:"APPROVE",reason:"Synthetic forbidden self approval"}),maker);assert.equal(sent.length,1);assert.deepEqual(failures,[reopening.reopeningErrors.scope]);
});
test("stable feedback focus survives disappearing submitted cards and ordinary reads do not steal focus",()=>{
 let focused=0;const feedback={current:{focus:()=>focused++}},notice="Account review recorded. Re-read the current account; no refund was posted and no dues were restored.";
 actual("feedbackEffect",{feedback,notice:""})();assert.equal(focused,0);actual("feedbackEffect",{feedback,notice})();assert.equal(focused,1);
 const html=renderToStaticMarkup(React.createElement(module(states({notice,queue:{...queue,items:[],total:0}})).RefundAccountReopeningPanel,props));assert.match(html,/<p tabindex="-1" role="status" class="reopen-feedback">Account review recorded/);
});
test("Hindi copy covers every controlled label/error and preserves placeholders; scoped CSS maintains accessible mobile theme behavior",()=>{
 const keys=new Set(Object.values(reopening.reopeningErrors)),ast=ts.createSourceFile("actual.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
 function copy(node){if(!node)return;if(ts.isStringLiteral(node)){if(node.text.trim())keys.add(node.text);}else if(ts.isConditionalExpression(node)){copy(node.whenTrue);copy(node.whenFalse);}}
 function visit(node){if(ts.isCallExpression(node)&&node.expression.getText(ast)==="t")copy(node.arguments[0]);if(ts.isCallExpression(node)&&node.expression.getText(ast)==="setNotice")copy(node.arguments[0]);ts.forEachChild(node,visit);}visit(ast);
 for(const key of keys){assert.equal(typeof messages[key],"string",`Missing Hindi: ${key}`);assert.deepEqual([...messages[key].matchAll(/\{(\w+)\}/g)].map(x=>x[1]).sort(),[...key.matchAll(/\{(\w+)\}/g)].map(x=>x[1]).sort());}
 const html=renderToStaticMarkup(React.createElement(module(states(),"hi").RefundAccountReopeningPanel,props));assert.ok(html.includes(messages["Original fee account reopening"]));
 assert.doesNotMatch(source,/localStorage|sessionStorage|window\.confirm|dangerouslySetInnerHTML/);assert.match(source,/beforeunload/);assert.match(source,/document\.addEventListener\("click",navigate,true\)/);
 const css=readFileSync(new URL("../components/workspace/refund-account-reopening-panel.module.css",import.meta.url),"utf8");assert.match(css,/min-height:44px/);assert.match(css,/focus-visible/);assert.match(css,/feedback:focus/);assert.match(css,/max-width:600px/);assert.match(css,/prefers-reduced-motion/);assert.match(css,/var\(--theme-error/);assert.doesNotMatch(css,/#(?:[0-9a-f]{3}){1,2}\b/i);
});
