import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as refunds from "./gateway-refunds.ts";
import { hindi } from "./i18n/messages.ts";
import { gatewayRefundMessages } from "./i18n/gateway-refund-messages.ts";
import * as gatewayReview from "./gateway-review.ts";
import { feeAccountMatches } from "./finance-account-search.ts";
import * as collectionRegister from "./fee-collection-register.ts";
import { settlementMessage } from "./i18n/gateway-settlement-messages.ts";

const source=readFileSync(new URL("../components/workspace/refund-requests-panel.tsx",import.meta.url),"utf8");
const school="76000000-0000-4000-8000-000000000001",paymentId="76000000-0000-4000-8000-000000000002",chargeId="76000000-0000-4000-8000-000000000003",requestId="76000000-0000-4000-8000-000000000004";
const filter={schoolId:school,status:"SUBMITTED",query:"",page:0};
const request={id:requestId,paymentId,receiptNumber:"SYN-00001",learnerName:"Synthetic learner",amount:10,currencyCode:"INR",status:"SUBMITTED",reason:"Synthetic <script>authored reason</script>",requestedAt:"2026-10-17T10:00:00Z",requestedBy:"Synthetic maker",isMaker:false,canReview:true,canCancel:false,allocations:[{chargeId,label:"Synthetic tuition",amount:10}],decision:null};
const queue={schoolId:school,status:"SUBMITTED",query:null,page:0,pageSize:25,total:1,hasMore:false,executionEnabled:false,items:[request]};
const payment={schoolId:school,paymentId,receiptNumber:"SYN-00001",accountId:"76000000-0000-4000-8000-000000000005",learnerName:"Synthetic learner",currencyCode:"INR",amount:29.29,reserved:10,available:19.29,canRequest:true,executionEnabled:false,allocations:[{chargeId,label:"Synthetic tuition",refundable:true,originalPaid:29.29,reserved:10,available:19.29}]};
const props={schoolId:school,paymentId,onGuardChange:()=>{},onSettled:async()=>{}};
const translate=(copy,values={})=>copy.replace(/\{(\w+)\}/g,(_,key)=>String(values[key]??`{${key}}`));
function module(values,refs){
 let state=0,ref=0;const exports={},require=createRequire(import.meta.url);
 const react=values?{...React,useState:initial=>[state<values.length?values[state++]:initial,()=>{}],useRef:initial=>refs&&ref<refs.length?{current:refs[ref++]}:{current:initial}}:React;
 const bind=name=>name==="react"?react:name==="./refund-account-reopening-panel"?{RefundAccountReopeningPanel:()=>null}:name==="./refund-posting-panel"?{RefundPostingPanel:()=>null}:name==="@/components/i18n/language-provider"?{useT:()=>translate,useLanguage:()=>({locale:"en"})}:name==="@/lib/gateway-refunds"?refunds:name==="@/lib/family-gateway-payments"?{familyPaymentCsrf:async()=>"synthetic-csrf"}:name.endsWith(".module.css")?{__esModule:true,default:new Proxy({},{get:(_,key)=>`refund-${key}`})}:require(name);
 const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
 new Function("require","exports",code)(bind,exports);return exports;
}
function actual(name,bindings,componentSource=source){
 const ast=ts.createSourceFile("actual.tsx",componentSource,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let found;
 function visit(node){if(ts.isFunctionDeclaration(node)&&node.name?.getText(ast)===name)found=node;if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name&&node.initializer&&ts.isCallExpression(node.initializer))found=node.initializer.arguments[0];if(name==="feedbackEffect"&&ts.isCallExpression(node)&&node.expression.getText(ast)==="useEffect"&&node.arguments[0]?.getText(ast).includes("feedback.current?.focus"))found=node.arguments[0];ts.forEachChild(node,visit);}visit(ast);assert.ok(found,`Missing actual ${name}`);
 const declaration=ts.isFunctionDeclaration(found)?`${found.getText(ast)}\nconst actual=${name};`:`const actual=${found.getText(ast)};`;
 const code=ts.transpileModule(declaration,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;return new Function(...Object.keys(bindings),`${code}\nreturn actual;`)(...Object.values(bindings));
}
function refundChildren(revisions,changes={}){
 const ast=ts.createSourceFile("actual-children.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX),children=[];
 function visit(node){if(ts.isJsxSelfClosingElement(node)&&["RefundAccountReopeningPanel","RefundPostingPanel"].includes(node.tagName.getText(ast)))children.push(node.getText(ast));ts.forEachChild(node,visit);}visit(ast);assert.equal(children.length,2);
 const bindings={React,RefundAccountReopeningPanel:"reopening",RefundPostingPanel:"posting",schoolId:school,reopeningRefund:requestId,busy:false,locked:false,reopeningBlocked:false,postingBlocked:false,reopeningGuardChange:()=>{},postingGuardChange:()=>{},reopeningSettled:()=>{},postingSettled:()=>{},...revisions,...changes};
 const code=ts.transpileModule(`const children=[${children.join(",")}];`,{compilerOptions:{target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.React}}).outputText;
 return new Function(...Object.keys(bindings),`${code}\nreturn children;`)(...Object.values(bindings));
}
function siblingRefreshHarness(parentRefresh=async()=>{}){
 const revisions={reopeningRevision:0,postingRevision:0},lifecycle={current:1},reopeningGuard={current:false},postingGuard={current:false};
 const bindings={lifecycle,reopeningGuard,postingGuard,refundErrors:refunds.refundErrors,onSettled:parentRefresh,setReopeningRevision:update=>revisions.reopeningRevision=update(revisions.reopeningRevision),setPostingRevision:update=>revisions.postingRevision=update(revisions.postingRevision)};
 return{revisions,lifecycle,reopeningGuard,postingGuard,reopeningSettled:actual("reopeningSettled",bindings),postingSettled:actual("postingSettled",bindings),keys:()=>refundChildren(revisions).map(child=>child.key)};
}
const states=(changes={})=>Object.values({...{filter,queue,payment,amounts:{},reason:"",confirmed:false,busy:false,locked:false,error:"",notice:"",recorded:false},...changes});

test("verified reopening refreshes only the posting sibling while preserving its guarded source instance",async()=>{
 let refreshed=0;const h=siblingRefreshHarness(async()=>refreshed++),before=h.keys();h.reopeningGuard.current=true;
 await h.reopeningSettled();const children=refundChildren(h.revisions,{reopeningSettled:h.reopeningSettled,postingSettled:h.postingSettled});
 assert.equal(refreshed,1);assert.equal(children[0].key,before[0]);assert.notEqual(children[1].key,before[1]);assert.equal(h.reopeningGuard.current,true,"The originating child alone releases its accepted-command guard");
 assert.equal(children[0].props.onSettled,h.reopeningSettled);assert.equal(children[1].props.onSettled,h.postingSettled);for(const child of children){assert.equal(child.props.schoolId,school);assert.equal(child.props.refundId,requestId);}
});
test("verified refund posting or journal repair refreshes only reopening without resetting the posting recovery state",async()=>{
 const h=siblingRefreshHarness(),before=h.keys();h.postingGuard.current=true;
 await h.postingSettled();const after=h.keys();assert.notEqual(after[0],before[0]);assert.equal(after[1],before[1]);assert.equal(h.postingGuard.current,true);
});
test("sibling keys change only after successful parent refresh and never on a failed refresh",async()=>{
 let finish;const h=siblingRefreshHarness(()=>new Promise(resolve=>finish=resolve)),before=h.keys(),pending=h.postingSettled();assert.deepEqual(h.keys(),before);finish();await pending;assert.notDeepEqual(h.keys(),before);
 for(const direction of["reopeningSettled","postingSettled"]){const failed=siblingRefreshHarness(async()=>{throw new Error("Synthetic parent refresh unavailable");}),keys=failed.keys();await assert.rejects(failed[direction](),/Synthetic parent refresh unavailable/);assert.deepEqual(failed.keys(),keys);}
});
test("an unresolved sibling cannot be remounted before or during parent refresh",async()=>{
 for(const [callback,target]of[["reopeningSettled","postingGuard"],["postingSettled","reopeningGuard"]]){
  let calls=0;const h=siblingRefreshHarness(async()=>calls++),before=h.keys();h[target].current=true;await assert.rejects(h[callback](),error=>error.message===refunds.refundErrors.unavailable);assert.equal(calls,0);assert.deepEqual(h.keys(),before);assert.equal(h[target].current,true);
  let finish;const raced=siblingRefreshHarness(()=>new Promise(resolve=>finish=resolve)),keys=raced.keys(),pending=raced[callback]();raced[target].current=true;finish();await assert.rejects(pending,error=>error.message===refunds.refundErrors.unavailable);assert.deepEqual(raced.keys(),keys);
 }
});
test("late parent refresh cannot invalidate another lifecycle or turn refund selection into a destructive child key",async()=>{
 for(const callback of["reopeningSettled","postingSettled"]){let finish;const h=siblingRefreshHarness(()=>new Promise(resolve=>finish=resolve)),before=h.keys(),pending=h[callback]();h.lifecycle.current++;finish();await assert.rejects(pending,error=>error.message===refunds.refundErrors.unavailable);assert.deepEqual(h.keys(),before);}
 const h=siblingRefreshHarness(),before=refundChildren(h.revisions),changed=refundChildren(h.revisions,{reopeningRefund:paymentId});assert.deepEqual(changed.map(child=>child.key),before.map(child=>child.key));assert.deepEqual(changed.map(child=>child.props.refundId),[paymentId,paymentId]);
});
test("unknown guard changes and failed source reads leave both child identities and accepted recovery intact",async()=>{
 const h=siblingRefreshHarness(),before=h.keys(),notifications=[];
 const guardBindings={refundGuard:{current:false},reopeningGuard:h.reopeningGuard,postingGuard:h.postingGuard,notifyGuard:value=>notifications.push(value),setReopeningBlocked:()=>{},setPostingBlocked:()=>{}};
 actual("reopeningGuardChange",guardBindings)(true);assert.deepEqual(h.keys(),before);actual("postingGuardChange",guardBindings)(true);assert.deepEqual(h.keys(),before);assert.equal(notifications.at(-1),true);
 for(const file of["refund-posting-panel.tsx","refund-account-reopening-panel.tsx"]){
  const childSource=readFileSync(new URL(`../components/workspace/${file}`,import.meta.url),"utf8"),attempt={current:{schoolId:school,refundId:requestId}},accepted={current:true};let calls=0;
  const bindings={writing:{current:false},read:{current:null},accepted,attempt,schoolId:school,lifecycle:{current:1},setBusy:()=>{},load:async()=>file==="refund-posting-panel.tsx"?null:false,applied:{current:filter},onSettled:async()=>calls++,setError:()=>{},refundPostingErrors:{unavailable:"Synthetic unavailable"},reopeningErrors:{unavailable:"Synthetic unavailable"}};
  await actual("refreshAccepted",bindings,childSource)();assert.equal(calls,0);assert.equal(accepted.current,true);assert.equal(attempt.current.refundId,requestId);assert.deepEqual(h.keys(),before);
 }
});
test("actual refund shell uses labelled native controls without inventing configuration or money-success state",()=>{
 const html=renderToStaticMarkup(React.createElement(module().RefundRequestsPanel,props));
 assert.match(html,/Gateway refund requests/);assert.match(html,/Read current refund records before deciding/);assert.match(html,/verified processing are separate/);assert.match(html,/aria-label="Refund queue filters"/);assert.match(html,/Reading or recording refund intent/);assert.doesNotMatch(html,/<main\b|refunded successfully|₹|New automated gateway refunds are disabled/i);
});
test("actual source form shows original allocations, reserved and available exact paise with independent-review confirmation",()=>{
 const html=renderToStaticMarkup(React.createElement(module(states()).RefundRequestsPanel,props));assert.match(html,/Original gateway receipt/);assert.match(html,/₹29\.29/);assert.match(html,/₹19\.29/);assert.match(html,/inputMode="decimal"|inputmode="decimal"/);assert.match(html,/type="checkbox" required/);assert.match(html,/does not issue a refund/);assert.match(html,/aria-label="Prepare refund request"/);assert.match(html,/&lt;script&gt;authored reason&lt;\/script&gt;/);assert.doesNotMatch(html,/<script>/);
});
test("actual review UI withholds self approval and shows maker cancellation only for backend-authorized maker",()=>{
 const maker={...request,isMaker:true,canReview:false,canCancel:true};const html=renderToStaticMarkup(React.createElement(module(states({queue:{...queue,items:[maker]}})).RefundRequestsPanel,props));assert.match(html,/Your request/);assert.match(html,/Cancel my refund request/);assert.doesNotMatch(html,/Approve refund processing|Reject refund request/);
 const reader=renderToStaticMarkup(React.createElement(module(states({payment:null,queue:{...queue,items:[{...request,canReview:false}]}})).RefundRequestsPanel,props));assert.doesNotMatch(reader,/Record refund decision|Prepare refund request/);
});
test("legacy approved request does not infer an outcome and retains checker reason and timestamp without edit/delete actions",()=>{
 const approved={...request,status:"APPROVED",canReview:false,decision:{id:paymentId,decision:"APPROVE",reason:"Synthetic independent decision",decidedAt:request.requestedAt,decidedBy:"Synthetic checker"}};
 const html=renderToStaticMarkup(React.createElement(module(states({payment:null,queue:{...queue,status:"ALL",items:[approved]}})).RefundRequestsPanel,props));assert.match(html,/Refund request approved — check processing separately/);assert.match(html,/Processing evidence is unavailable/);assert.match(html,/Synthetic checker/);assert.match(html,/Synthetic independent decision/);assert.doesNotMatch(html,/Record refund decision|Delete|Edit refund|Approved intent — not refunded/);
});

test("actual approved cards distinguish every processing state and expose refresh without claiming bank or journal completion",()=>{
 const approved={...request,status:"APPROVED",canReview:false,decision:{id:paymentId,decision:"APPROVE",reason:"Synthetic independent decision",decidedAt:request.requestedAt,decidedBy:"Synthetic checker"}};
 for(const state of refunds.refundProcessingStates){
  const processing={state,requiresReview:["NOT_QUEUED","PROVIDER_FAILED","REVIEW_REQUIRED"].includes(state),lastCheckedAt:["NOT_QUEUED","AWAITING_PROCESSING"].includes(state)?null:request.requestedAt,nextCheckAt:null};
  const html=renderToStaticMarkup(React.createElement(module(states({payment:null,queue:{...queue,executionEnabled:true,status:"ALL",items:[{...approved,processing}]}})).RefundRequestsPanel,props));
  assert.ok(html.includes(refunds.refundProcessingLabel(state)));assert.match(html,/New gateway refund processing is configured/);assert.match(html,/approval alone never proves/);assert.match(html,/Refresh refund processing status/);assert.match(html,/Processing status is not a bank settlement or general-ledger approval/);assert.equal(html.includes("Last independent gateway check"),processing.lastCheckedAt!==null);assert.doesNotMatch(html,/Approved intent — not refunded|Gateway refund execution is disabled/);
  assert.equal(html.includes("Additional refund review is required"),processing.requiresReview);
 }
 const disabled=renderToStaticMarkup(React.createElement(module(states()).RefundRequestsPanel,props));assert.match(disabled,/New automated gateway refunds are disabled/);assert.match(disabled,/Existing refunds may still be confirmed/);assert.match(disabled,/Approval may authorize configured gateway processing/);
});
test("unknown outcomes lock drafts/filter decisions and retain explicit keyboard-focusable same-command recovery",()=>{
 const html=renderToStaticMarkup(React.createElement(module(states({locked:true,error:refunds.refundErrors.unknown})).RefundRequestsPanel,props));assert.match(html,/role="alert"/);assert.match(html,/outcome is unknown/);assert.match(html,/Retry this exact refund command/);assert.match(html,/<fieldset disabled/);assert.match(html,/Do not repeat it from another tab/);assert.match(html,/aria-disabled="true">Record refund decision/);assert.doesNotMatch(html,/window\.confirm|localStorage|sessionStorage/);
});
test("recorded intent uses state-driven recovery copy while remaining guarded until record refresh",()=>{
 const html=renderToStaticMarkup(React.createElement(module(states({locked:true,recorded:true})).RefundRequestsPanel,props));assert.match(html,/The intent is recorded/);assert.match(html,/Refresh recorded refund intent/);assert.doesNotMatch(html,/Retry this exact refund command|refunded successfully/i);
});
test("accepted command focuses stable feedback outside cards, while ordinary reads never request focus",()=>{
 let focused=0;const feedback={current:{focus:()=>focused++}},notice="Refund intent recorded. No money was returned and no dues were changed.";
 actual("feedbackEffect",{feedback,notice:""})();assert.equal(focused,0);
 actual("feedbackEffect",{feedback,notice})();assert.equal(focused,1);
 const before=renderToStaticMarkup(React.createElement(module(states({notice})).RefundRequestsPanel,props));
 const after=renderToStaticMarkup(React.createElement(module(states({notice,queue:{...queue,total:0,items:[]}})).RefundRequestsPanel,props));
 const target=/<p tabindex="-1" role="status" class="refund-feedback">Refund intent recorded\. No money was returned and no dues were changed\.<\/p>/;
 assert.match(before,target);assert.match(after,target);assert.ok(after.indexOf('class="refund-feedback"')<after.indexOf('aria-label="Refund requests"'));
 const ast=ts.createSourceFile("actual.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let dependencies;
 function visit(node){if(ts.isCallExpression(node)&&node.expression.getText(ast)==="useEffect"&&node.arguments[0]?.getText(ast).includes("feedback.current?.focus"))dependencies=node.arguments[1]?.getText(ast);ts.forEachChild(node,visit);}visit(ast);assert.equal(dependencies,"[notice]","Queue/filter/loading refreshes must not steal keyboard focus");
 const css=readFileSync(new URL("../components/workspace/refund-requests-panel.module.css",import.meta.url),"utf8");assert.match(css,/\.feedback:focus\s*\{[^}]*outline:3px solid var\(--theme-text\)/);
});
test("actual reads fence duplicate requests, replace both scoped views together and clear private stale data on error",async()=>{
 const read={current:null},lifecycle={current:1},applied={current:filter},state={busy:false,error:"",queue:null,payment:null};let complete,calls=0;
 const bindings={read,lifecycle,applied,schoolId:school,paymentId,refundError:refunds.refundError,setBusy:value=>state.busy=value,setError:value=>state.error=value,setQueue:value=>state.queue=value,setPayment:value=>state.payment=value,
  loadRefundQueue:async()=>{calls++;return new Promise(resolve=>complete=resolve);},loadRefundPayment:async()=>payment};
 const load=actual("load",bindings),pending=load(filter);assert.equal(state.busy,true);assert.equal(await load(filter),false);assert.equal(calls,1);complete(queue);assert.equal(await pending,true);assert.equal(state.queue,queue);assert.equal(state.payment,payment);assert.equal(state.busy,false);assert.equal(read.current,null);
 const failed=actual("load",{...bindings,loadRefundQueue:async()=>{throw new Error("SYNTHETIC PRIVATE SQL");}});assert.equal(await failed(filter),false);assert.equal(state.queue,null);assert.equal(state.payment,null);assert.equal(state.error,refunds.refundErrors.unavailable);
});
function dispatchHarness(send){
 const state={busy:false,locked:false,error:"",notice:"",guards:[],refreshes:0},attempt={current:null},unknown={current:false},accepted={current:false},writing={current:false},read={current:null},lifecycle={current:1};
 const dispatch=actual("dispatch",{attempt,unknown,accepted,writing,read,lifecycle,RefundWriteError:refunds.RefundWriteError,refundErrors:refunds.refundErrors,refundError:refunds.refundError,
  reopeningGuard:{current:false},postingGuard:{current:false},setBusy:value=>state.busy=value,setLocked:value=>state.locked=value,setError:value=>state.error=value,setNotice:value=>state.notice=value,setRecorded:value=>state.recorded=value,onGuardChange:value=>state.guards.push(value),familyPaymentCsrf:async()=>"synthetic-csrf",sendRefund:send,refreshAccepted:async()=>state.refreshes++});
 return{state,attempt,unknown,accepted,writing,read,lifecycle,dispatch};
}
test("actual write handler fences double clicks, preserves exact uncertain attempt and never substitutes a fresh command",async()=>{
 let finish,calls=0;const prepared=refunds.prepareRefund(payment,{[chargeId]:"1.01"},"Synthetic intent",requestId),h=dispatchHarness(async value=>{calls++;assert.equal(value,prepared);return new Promise(resolve=>finish=resolve);});
 const pending=h.dispatch(prepared);await Promise.resolve();assert.equal(h.state.locked,true);await h.dispatch(prepared);assert.equal(calls,1);await h.dispatch({...prepared,requestId:school});assert.equal(calls,1);finish();await pending;assert.equal(h.accepted.current,true);assert.equal(h.state.refreshes,1);
});
test("actual unknown recovery stays locked after a later rejection, and retry uses the original frozen body",async()=>{
 let calls=0;const prepared=refunds.prepareRefund(payment,{[chargeId]:"1.01"},"Synthetic intent",requestId),h=dispatchHarness(async value=>{assert.equal(value,prepared);calls++;throw new refunds.RefundWriteError(calls===1?"unknown":"rejected",calls===1?refunds.refundErrors.unknown:refunds.refundErrors.rejected);});
 await h.dispatch(prepared);assert.equal(h.attempt.current,prepared);assert.equal(h.unknown.current,true);assert.equal(h.state.error,refunds.refundErrors.unknown);await h.dispatch(prepared);assert.equal(calls,2);assert.equal(h.state.locked,true);assert.equal(h.attempt.current,prepared);assert.deepEqual(h.state.guards,[true,true]);assert.equal(h.state.error,refunds.refundErrors.unknown);
});
test("actual confirmed rejection releases only a fresh command, while lifecycle exit cannot claim acceptance",async()=>{
 const prepared=refunds.prepareRefund(payment,{[chargeId]:"1.01"},"Synthetic intent",requestId),h=dispatchHarness(async()=>{throw new refunds.RefundWriteError("rejected",refunds.refundErrors.scope);});await h.dispatch(prepared);assert.equal(h.attempt.current,null);assert.equal(h.state.locked,false);assert.deepEqual(h.state.guards,[true,false]);
 let finish,started;const dispatched=new Promise(resolve=>started=resolve),late=dispatchHarness(async()=>{started();return new Promise(resolve=>finish=resolve);});const pending=late.dispatch(prepared);await dispatched;late.lifecycle.current++;finish();await pending;assert.equal(late.accepted.current,false);assert.equal(late.state.refreshes,0);
});

test("refund and original-account review guards cannot release each other's unresolved command",()=>{
 const refundGuard={current:false},reopeningGuard={current:false},notifications=[],blocked=[];
 const bindings={refundGuard,reopeningGuard,postingGuard:{current:false},notifyGuard:value=>notifications.push(value),setReopeningBlocked:value=>blocked.push(value)};
 const intent=actual("onGuardChange",bindings),account=actual("reopeningGuardChange",bindings);
 intent(true);account(true);intent(false);assert.equal(notifications.at(-1),true);
 account(false);assert.equal(notifications.at(-1),false);
 account(true);intent(true);account(false);assert.equal(notifications.at(-1),true);
 intent(false);assert.equal(notifications.at(-1),false);assert.deepEqual(blocked,[true,false,true,false]);
});

test("actual posting intent and reopening guards retain each other synchronously without releasing a pending financial command",()=>{
 const refundGuard={current:false},reopeningGuard={current:false},postingGuard={current:false},notifications=[],postingStates=[],accountStates=[];
 const bindings={refundGuard,reopeningGuard,postingGuard,notifyGuard:value=>notifications.push(value),setPostingBlocked:value=>postingStates.push(value),setReopeningBlocked:value=>accountStates.push(value)};
 const intent=actual("onGuardChange",bindings),account=actual("reopeningGuardChange",bindings),posting=actual("postingGuardChange",bindings);
 posting(true);intent(true);account(true);posting(false);assert.equal(notifications.at(-1),true);intent(false);assert.equal(notifications.at(-1),true);account(false);assert.equal(notifications.at(-1),false);
 intent(true);posting(true);intent(false);assert.equal(notifications.at(-1),true);posting(false);assert.equal(notifications.at(-1),false);assert.deepEqual(postingStates,[true,false,true,false]);assert.deepEqual(accountStates,[true,false]);
});

test("actual unresolved refund posting disables fresh refund intent and original-account switching",async()=>{
 const view=states();view.push(false,requestId,true);
 const html=renderToStaticMarkup(React.createElement(module(view).RefundRequestsPanel,props));assert.match(html,/<fieldset[^>]*disabled/);
 let calls=0;const dispatch=actual("dispatch",{postingGuard:{current:true},reopeningGuard:{current:false},attempt:{current:null},sendRefund:async()=>calls++});await dispatch({});assert.equal(calls,0);
});

test("account review guard disables existing intent controls and blocks programmatic fresh dispatch",async()=>{
 const view=states();view.push(true,undefined);
 const html=renderToStaticMarkup(React.createElement(module(view).RefundRequestsPanel,props));
 assert.match(html,/<fieldset[^>]*disabled/);
 const prepared=refunds.prepareRefund(payment,{[chargeId]:"1.01"},"Synthetic guarded command",requestId);
 let sent=0;const dispatch=actual("dispatch",{reopeningGuard:{current:true},attempt:{current:null},sendRefund:async()=>sent++});
 await dispatch(prepared);assert.equal(sent,0);
});
test("actual accepted intent releases its guard only after current records and parent account refresh succeed",async()=>{
 const state={busy:false,locked:true,error:"",guards:[],amounts:{[chargeId]:"1"}},attempt={current:{}},accepted={current:true},unknown={current:true},writing={current:false},read={current:null},lifecycle={current:1};let refresh=false;
 const refreshAccepted=actual("refreshAccepted",{attempt,accepted,unknown,writing,read,lifecycle,applied:{current:filter},refundErrors:refunds.refundErrors,load:async()=>true,onSettled:async()=>{if(!refresh)throw new Error("Synthetic refresh unavailable");},setBusy:value=>state.busy=value,setLocked:value=>state.locked=value,setError:value=>state.error=value,setRecorded:value=>state.recorded=value,onGuardChange:value=>state.guards.push(value),setAmounts:value=>state.amounts=value,setReason:()=>{},setConfirmed:()=>{}});
 await refreshAccepted();assert.equal(accepted.current,true);assert.equal(state.locked,true);assert.deepEqual(state.guards,[]);refresh=true;await refreshAccepted();assert.equal(state.locked,false);assert.equal(attempt.current,null);assert.equal(unknown.current,false);assert.deepEqual(state.guards,[false]);assert.deepEqual(state.amounts,{});
});
test("all controlled refund labels/errors preserve complete Hindi placeholders and source privacy/navigation invariants",()=>{
 const keys=new Set([...Object.keys(gatewayRefundMessages),...Object.values(refunds.refundErrors)]),ast=ts.createSourceFile("actual.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
 function copy(node){if(!node)return;if(ts.isStringLiteral(node))keys.add(node.text);else if(ts.isConditionalExpression(node)){copy(node.whenTrue);copy(node.whenFalse);}}
 function visit(node){if(ts.isCallExpression(node)&&node.expression.getText(ast)==="t")copy(node.arguments[0]);ts.forEachChild(node,visit);}visit(ast);
 for(const key of keys){const value=hindi.fees[key]??hindi.common[key];assert.equal(typeof value,"string",`Missing Hindi: ${key}`);assert.deepEqual([...value.matchAll(/\{(\w+)\}/g)].map(x=>x[1]).sort(),[...key.matchAll(/\{(\w+)\}/g)].map(x=>x[1]).sort());}
 assert.doesNotMatch(source,/window\.confirm|localStorage|sessionStorage|dangerouslySetInnerHTML/);assert.match(source,/beforeunload/);assert.match(source,/document\.addEventListener\("click",navigate,true\)/);
 const css=readFileSync(new URL("../components/workspace/refund-requests-panel.module.css",import.meta.url),"utf8");assert.match(css,/min-height:44px/);assert.match(css,/focus-visible/);assert.match(css,/max-width:600px/);assert.match(css,/prefers-reduced-motion/);assert.match(css,/var\(--theme-error/);assert.doesNotMatch(css,/#(?:[0-9a-f]{3}){1,2}\b/i);
});
test("workspace integration preserves manual guards and captures-only entry with independent refund guards",()=>{
 const workspace=readFileSync(new URL("../components/workspace/finance-workspace.tsx",import.meta.url),"utf8");assert.match(workspace,/guardedCollection\.current \|\| guardedRefund\.current/);assert.match(workspace,/disabled=\{collectionBlocked \|\| refundBlocked\}/);assert.match(workspace,/payment\.gatewayRefundSource === true && payment\.paymentMethod === "ONLINE_PROVIDER" && payment\.status === "POSTED"/);assert.match(workspace,/onGuardChange=\{refundGuardChange\}/);assert.match(workspace,/onCollectionGuardChange=/);assert.doesNotMatch(workspace,/window\.confirm|gatewayCaptureId/);
});
test("actual finance workspace renders a native refund entry only for a flagged posted captured gateway receipt",()=>{
 const workspace=readFileSync(new URL("../components/workspace/finance-workspace.tsx",import.meta.url),"utf8"),require=createRequire(import.meta.url);
 const receipt={id:paymentId,receiptNumber:"SYN-00001",paymentMethod:"ONLINE_PROVIDER",amount:29.29,receivedOn:"2026-10-17",status:"POSTED",createdAt:"2026-10-17T10:00:00Z",rowVersion:0};
 const account={id:requestId,learnerId:chargeId,enrolmentId:chargeId,sessionId:chargeId,learnerName:"Synthetic learner",gradeCode:"VI",sessionName:"Synthetic session",currencyCode:"INR",status:"OPEN",totalCharges:29.29,totalCredits:29.29,balance:0,charges:[],payments:[],ledger:[]};
 const base={schoolId:school,schoolName:"Synthetic school",schoolCurrency:"INR",schoolTimeZone:"Asia/Kolkata",schoolToday:"2026-10-17",latestClosedDate:null,canConfigure:false,canCollect:false,canApprove:false,canReverse:false,canProposeDayClose:false,canApproveDayClose:false,onlinePaymentStatus:"NOT_CONFIGURED",feePlans:[],feeHeads:[],sessions:[],learners:[],concessions:[],accounts:[]};
 function render(changed,guards={},copy=translate){let state=0;const exports={},snapshot={...base,accounts:[{...account,payments:[{...receipt,...changed}]}]},values=[snapshot,"accounts","",account.id,"",false,"","",Boolean(guards.collection),false,Boolean(guards.refund),undefined];
  const react={...React,useState:initial=>[state<values.length?values[state++]:initial,()=>{}]},empty=()=>null;
  const bind=name=>name==="react"?react:name==="@/components/i18n/language-provider"?{useT:()=>copy,useLanguage:()=>({locale:"en"})}:name==="@/lib/gateway-review"?gatewayReview:name==="@/lib/finance-account-search"?{feeAccountMatches}:name==="@/lib/fee-collection-register"?collectionRegister:name==="@/lib/i18n/gateway-settlement-messages"?{settlementMessage}:name==="@/components/icons"?{ArrowUpRight:empty}:name==="@/components/workspace/workspace-icons"?{MoneyIcon:empty,PlusIcon:empty,ReportIcon:empty}:name==="@/components/workspace/gateway-review-panel"?{GatewayReviewPanel:empty}:name==="@/components/workspace/collection-register-panel"?{CollectionRegisterPanel:empty}:name==="@/components/workspace/refund-requests-panel"?{RefundRequestsPanel:empty}:name==="@/components/workspace/settlement-reports-panel"?{SettlementReportsPanel:empty}:name==="@/components/workspace/manual-fee-collection-form"?{ManualFeeCollectionForm:empty}:name==="@/components/fee-receipt-timing-text"?{FeeReceiptTimingText:empty}:name==="@/components/receipt-pdf-actions"?{ReceiptPdfActions:empty}:require(name);
  const compiled=ts.transpileModule(workspace,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;new Function("require","exports",compiled)(bind,exports);return renderToStaticMarkup(React.createElement(exports.FinanceWorkspace));
 }
 const button=/<button type="button"([^>]*)>Request gateway refund<\/button>/;
 const backed=render({gatewayRefundSource:true});assert.match(backed,button);assert.doesNotMatch(backed.match(button)[1],/disabled/);
 for(const changed of[{paymentMethod:"CASH",gatewayRefundSource:false},{paymentMethod:"CASH",gatewayRefundSource:true},{gatewayRefundSource:false},{},{gatewayRefundSource:"true"},{gatewayRefundSource:true,status:"REVERSED"}])assert.doesNotMatch(render(changed),button,JSON.stringify(changed));
 for(const guards of[{collection:true},{refund:true}])assert.match(render({gatewayRefundSource:true},guards).match(button)[1],/disabled/);
 const hi=(key,values)=>translate(hindi.fees[key]??hindi.common[key]??key,values);assert.ok(render({gatewayRefundSource:true},{},hi).includes(hindi.fees["Request gateway refund"]));
});
test("actual parent refresh rejects a changed school before replacing data or unmounting guarded refund intent",async()=>{
 const workspace=readFileSync(new URL("../components/workspace/finance-workspace.tsx",import.meta.url),"utf8"),ast=ts.createSourceFile("actual-parent.tsx",workspace,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let body;
 function visit(node){if(ts.isVariableDeclaration(node)&&node.name.getText(ast)==="load"&&node.initializer&&ts.isCallExpression(node.initializer))body=node.initializer.arguments[0];ts.forEachChild(node,visit);}visit(ast);assert.ok(body);
 const code=ts.transpileModule(`const actual=${body.getText(ast)};`,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 let replacements=0;const guardedRefund={current:school},guardedCollection={current:null},guardedSettlement={current:null},snapshot={schoolId:paymentId,accounts:[],feePlans:[]},bindings={guardedRefund,guardedCollection,guardedSettlement,apiUrl:"http://localhost:8080",fetch:async()=>Response.json(snapshot),problem:async()=>"Unavailable",setData:()=>replacements++,setSelectedPlanId:()=>replacements++,setSelectedAccountId:()=>replacements++};
 const load=new Function(...Object.keys(bindings),`${code}\nreturn actual;`)(...Object.values(bindings));await assert.rejects(load(),/refund school's records could not be refreshed/);assert.equal(replacements,0);assert.equal(guardedRefund.current,school);
 snapshot.schoolId=school;await load();assert.equal(replacements,3);
 guardedCollection.current={schoolId:school,accountId:requestId};await assert.rejects(load(),/collected account could not be refreshed/);assert.equal(replacements,3);
});
