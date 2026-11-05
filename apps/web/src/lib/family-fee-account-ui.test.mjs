import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as accounts from "./family-fee-accounts.ts";
import { familyFeeAccountMessages as messages } from "./i18n/family-fee-account-messages.ts";

const read=path=>readFileSync(new URL(path,import.meta.url),"utf8"),source=read("../components/family/fee-account-selector.tsx"),parent=read("../components/family/family-portal.tsx");
const learner="7b000000-0000-4000-8000-000000000001",accountId="7b000000-0000-4000-8000-000000000002",sessionId="7b000000-0000-4000-8000-000000000003",other="7b000000-0000-4000-8000-000000000004";
const account={id:accountId,sessionId,sessionName:"Synthetic <script>old session</script>",status:"CLOSED",currencyCode:"INR",balance:-29.29,currentSession:false};
const data={learnerId:learner,currentAccountId:null,page:0,pageSize:20,hasMore:false,accounts:[account]};
function component(values,locale="en"){
 let state=0;const exports={},require=createRequire(import.meta.url),react=values?{...React,useState:initial=>[state<values.length?values[state++]:initial,()=>{}]}:React;
 const bind=name=>name==="react"?react:name==="@/components/i18n/language-provider"?{useT:()=>copy=>locale==="hi"?messages[copy]??copy:copy,useLanguage:()=>({locale})}:name==="@/lib/family-fee-accounts"?accounts:name.endsWith(".module.css")?{__esModule:true,default:new Proxy({},{get:(_,key)=>`account-${key}`})}:require(name);
 const output=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
 new Function("require","exports",output)(bind,exports);return exports;
}
function actual(text,name,bindings){
 const ast=ts.createSourceFile("actual.tsx",text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let found;
 function visit(node){if(ts.isFunctionDeclaration(node)&&node.name?.text===name)found=node;if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name&&node.initializer&&ts.isCallExpression(node.initializer))found=node.initializer.arguments[0];ts.forEachChild(node,visit);}visit(ast);assert.ok(found,`Missing actual ${name}`);
 const output=ts.transpileModule(`const actual=${found.getText(ast)};`,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;return new Function(...Object.keys(bindings),`${output}\nreturn actual;`)(...Object.values(bindings));
}
const props={learnerId:learner,selectedAccount:null,disabled:false,onSelect:()=>{},onCurrentAccount:()=>{},onAccountsRead:()=>{},onAccessLost:()=>{}};
test("native selector has explicit labels/default/current semantics and does not invent a historical academic context",()=>{
 const html=renderToStaticMarkup(React.createElement(component().FamilyFeeAccountSelector,props));assert.match(html,/<label for=/);assert.match(html,/<select[^>]*disabled/);assert.match(html,/Current academic session \(default\)/);assert.match(html,/does not change classes, enrollment/);assert.match(html,/refund holds are not restored dues/);assert.doesNotMatch(html,/Synthetic/);
});
test("closed historical account shows exact signed balance, escaped session and truthful view-only explanation in both languages",()=>{
 for(const locale of["en","hi"]){const html=renderToStaticMarkup(React.createElement(component([data,false,""],locale).FamilyFeeAccountSelector,{...props,selectedAccount:account}));assert.match(html,/&lt;script&gt;old session&lt;\/script&gt;/);assert.doesNotMatch(html,/<script>/);assert.match(html,/-₹29\.29/);assert.ok(html.includes(locale==="hi"?messages["Closed — view only"]:"Closed — view only"));assert.ok(html.includes(locale==="hi"?messages["This fee account is closed and view-only. Existing statements, receipts and payment history remain available; no new payment can be started."]:"This fee account is closed and view-only."));}
});
test("native disabled state, scoped error and terminal paging retain accessible focusable read controls",()=>{
 const html=renderToStaticMarkup(React.createElement(component([{...data,page:1},false,accounts.familyFeeAccountErrors.unavailable]).FamilyFeeAccountSelector,{...props,disabled:true}));assert.match(html,/<select[^>]*disabled/);assert.match(html,/role="alert"/);assert.match(html,/aria-disabled="true">All fee accounts loaded/);assert.doesNotMatch(html,/<button[^>]*\sdisabled=/);
});
test("actual selection handler enforces reciprocal unknown-command, busy and child scope guards before changing accounts",()=>{
 const selected=[],bindings={disabled:false,busy:false,scope:{current:learner},learnerId:learner,data,onSelect:value=>selected.push(value)};
 actual(source,"choose",bindings)(accountId);actual(source,"choose",bindings)("");assert.deepEqual(selected,[account,null]);
 for(const changed of[{disabled:true},{busy:true},{scope:{current:other}},{data:null}])actual(source,"choose",{...bindings,...changed})(accountId);assert.equal(selected.length,2);
 actual(source,"choose",bindings)(other);assert.equal(selected.length,2);
});
test("actual paged account reader fences duplicate requests, ignores stale child replies and clears private state on revoked access",async()=>{
 const state={},request={current:null},scope={current:learner},current=[],callbacks={current:{onCurrentAccount:value=>current.push(value),onAccountsRead:value=>state.accounts=value,onAccessLost:()=>state.lost=true}};let finish,reads=0;
 const bindings={learnerId:learner,request,scope,callbacks,loadFamilyFeeAccounts:async()=>{reads++;return new Promise(resolve=>finish=resolve);},FamilyFeeAccountAccessError:accounts.FamilyFeeAccountAccessError,familyFeeAccountError:accounts.familyFeeAccountError,setBusy:value=>state.busy=value,setError:value=>state.error=value,setData:value=>state.data=typeof value==="function"?value(state.data):value};
 const load=actual(source,"load",bindings),pending=load();await load();assert.equal(reads,1);finish(data);await pending;assert.equal(state.data.accounts[0],account);assert.deepEqual(current,[null]);
 const stale=load();scope.current=other;finish({...data,accounts:[]});await stale;assert.equal(state.data.accounts[0],account);
 scope.current=learner;await actual(source,"load",{...bindings,loadFamilyFeeAccounts:async()=>{throw new accounts.FamilyFeeAccountAccessError(403);}})();assert.equal(state.data,null);assert.equal(state.lost,true);assert.equal(state.error,accounts.familyFeeAccountErrors.scope);
});
test("actual parent child/account handlers synchronously prevent a remount while an immutable payment remains unresolved",()=>{
 const state={},paymentGuard={current:{learnerId:learner,accountId}},bindings={paymentGuard,setSelectedFeeAccount:value=>state.account=value,setFees:value=>state.fees=value,setFeesError:value=>state.error=value,setSelectedId:value=>state.child=value,setCurrentFeeAccount:value=>state.current=value,setKnownFeeAccounts:value=>state.accounts=value};
 actual(parent,"chooseFeeAccount",bindings)(account);actual(parent,"chooseChild",bindings)(other);assert.deepEqual(state,{});
 paymentGuard.current=null;actual(parent,"chooseFeeAccount",bindings)(account);assert.equal(state.account,account);assert.equal(state.fees,null);actual(parent,"chooseChild",bindings)(other);assert.equal(state.child,other);assert.equal(state.account,null);assert.equal(state.current,null);
});
test("actual parent statement reader keeps confirmed data mounted during reload, includes explicit account and rejects a guard scope switch",async()=>{
 const old={learnerId:learner,accountId},state={fees:old},calls=[],signal=new AbortController().signal,paymentGuard={current:{learnerId:learner,accountId}};
 const bindings={selectedId:learner,selectedFeeAccount:account,paymentGuard,apiUrl:"http://localhost:8080",setFeesLoading:value=>state.busy=value,setFeesError:value=>state.error=value,setFees:value=>state.fees=value,setCurrentFeeAccount:value=>state.current=value,setPaymentBlocked:value=>state.blocked=value,setSelectedFeeAccount:value=>state.selected=value,setKnownFeeAccounts:value=>state.accounts=value,fetch:async(url,options)=>{calls.push({url,options});assert.equal(state.fees,old,"Same-scope refresh cannot unmount the payment panel");return Response.json({...old});}};
 await actual(parent,"refreshFees",bindings)(signal);assert.ok(calls[0].url.endsWith(`/fees?accountId=${accountId}`));assert.equal(calls[0].options.cache,"no-store");assert.equal(state.fees.accountId,accountId);
 for(const bad of[{learnerId:other,accountId},{learnerId:learner,accountId:other}]){state.fees=old;await assert.rejects(actual(parent,"refreshFees",{...bindings,fetch:async()=>Response.json(bad)})(signal));assert.equal(state.fees,old);}
 calls.length=0;state.fees=old;await actual(parent,"refreshFees",{...bindings,selectedFeeAccount:null})(signal);assert.ok(calls[0].url.endsWith(`/fees?accountId=${accountId}`),"Default-mode recovery reads its retained original account even after rollover");
 paymentGuard.current=null;calls.length=0;state.fees=old;await actual(parent,"refreshFees",{...bindings,selectedFeeAccount:null})(signal);assert.ok(calls[0].url.endsWith("/fees"),"Only a released guard resumes latest-current-session reads");
 await assert.rejects(actual(parent,"refreshFees",{...bindings,fetch:async()=>new Response(null,{status:403})})(signal));assert.equal(state.fees,null);assert.equal(paymentGuard.current,null);assert.equal(state.blocked,false);assert.deepEqual(state.accounts,{});
});
test("aborted previous statement denial cannot clear a newer child's private view or unresolved payment guard",async()=>{
 const controller=new AbortController(),state={},paymentGuard={current:{learnerId:learner,accountId}};let finish;
 const bindings={selectedId:learner,selectedFeeAccount:null,paymentGuard,apiUrl:"http://localhost:8080",setFeesLoading:value=>state.busy=value,setFeesError:value=>state.error=value,setFees:value=>state.fees=value,setCurrentFeeAccount:value=>state.current=value,setPaymentBlocked:value=>state.blocked=value,setSelectedFeeAccount:value=>state.selected=value,setKnownFeeAccounts:value=>state.accounts=value,fetch:async()=>new Promise(resolve=>finish=resolve)};
 const pending=actual(parent,"refreshFees",bindings)(controller.signal);controller.abort();
 const newer={learnerId:other,accountId:other};paymentGuard.current=newer;state.fees=newer;
 const before={...state};finish(new Response(null,{status:403}));await pending;
 assert.deepEqual(state,before);assert.equal(paymentGuard.current,newer);
});

test("actual parent guard captures original child/account synchronously before rendered disable catches up",()=>{
 const paymentGuard={current:null},state={},guard=actual(parent,"guardPayment",{paymentGuard,fees:{learnerId:learner,accountId},selectedId:learner,setPaymentBlocked:value=>state.blocked=value});guard(true);assert.deepEqual(paymentGuard.current,{learnerId:learner,accountId});assert.equal(state.blocked,true);guard(false);assert.equal(paymentGuard.current,null);assert.equal(state.blocked,false);
});
test("all new labels/errors translate completely and semantic CSS supports mobile/focus/reduced motion with no private storage",()=>{
 const keys=new Set(Object.values(accounts.familyFeeAccountErrors));const ast=ts.createSourceFile("labels.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
 function literal(node){if(ts.isStringLiteral(node))keys.add(node.text);else if(ts.isConditionalExpression(node)){literal(node.whenTrue);literal(node.whenFalse);}}
 function visit(node){if(ts.isCallExpression(node)&&node.expression.getText(ast)==="t")literal(node.arguments[0]);ts.forEachChild(node,visit);}visit(ast);
 for(const key of keys){assert.ok(messages[key],key);assert.match(messages[key],/\p{Script=Devanagari}/u);assert.deepEqual([...key.matchAll(/\{\w+\}/g)].map(match=>match[0]),[...messages[key].matchAll(/\{\w+\}/g)].map(match=>match[0]));}
 const css=read("../components/family/fee-account-selector.module.css");assert.match(css,/min-height: 44px/);assert.match(css,/:focus-visible/);assert.match(css,/max-width: 640px/);assert.match(css,/prefers-reduced-motion: reduce/);assert.match(css,/var\(--theme-card\)/);assert.doesNotMatch(source+parent,/localStorage|sessionStorage/);
});
