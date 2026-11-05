import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as register from "./fee-collection-register.ts";
import * as exportTools from "./fee-collection-export.ts";
import { hindi } from "./i18n/messages.ts";
import { collectionRegisterMessages } from "./i18n/collection-register-messages.ts";

const source=readFileSync(new URL("../components/workspace/collection-register-panel.tsx",import.meta.url),"utf8");
const translate=(copy,values={})=>copy.replace(/\{(\w+)\}/g,(_,key)=>String(values[key]??`{${key}}`));
const school="74000000-0000-4000-8000-000000000001";
const filter={schoolId:school,from:"2026-10-01",to:"2026-10-16",currencyCode:"INR",paymentMethod:"",query:""};
const props={schoolId:school,schoolToday:"2026-10-16",schoolCurrency:"INR",currencies:["INR"]};
const zero={collectionCount:0,grossCollections:0,reversalCount:0,reversalAmount:0,netCollections:0,refundCount:0,refundAmount:0};
const report={...filter,paymentMethod:null,query:null,page:0,pageSize:25,total:0,hasMore:false,summary:zero,tenderTotals:register.collectionMethods.map(paymentMethod=>({paymentMethod,summary:zero})),items:[]};
function module(values){
 let state=0;const exports={},require=createRequire(import.meta.url);
 const react=values?{...React,useState:initial=>[state<values.length?values[state++]:initial,()=>{}]}:React;
 const bindings=name=>name==="react"?react:name==="@/components/i18n/language-provider"?{useT:()=>translate,useLanguage:()=>({locale:"en"})}
  :name==="@/lib/fee-collection-register"?register:name==="@/lib/fee-collection-export"?exportTools:name.endsWith(".module.css")?{__esModule:true,default:new Proxy({},{get:(_,key)=>`register-${key}`})}
  :name==="@/components/fee-receipt-timing-text"?{FeeReceiptTimingText:()=>React.createElement("span",null,"Original received evidence")}
  :name==="@/components/receipt-pdf-actions"?{ReceiptPdfActions:props=>React.createElement("span",{"data-receipt":props.paymentId,"data-school":props.schoolId,"data-receipt-status":props.paymentStatus},"Existing private receipt actions")}:require(name);
 const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
 new Function("require","exports",compiled)(bindings,exports);return exports;
}
function actual(name,bindings){
 const ast=ts.createSourceFile("actual.tsx",source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let found;
 function visit(node){
  if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name&&node.initializer&&ts.isCallExpression(node.initializer))found=node.initializer.arguments[0];
  if(name==="useEffect"&&ts.isCallExpression(node)&&node.expression.getText(ast)===name)found=node.arguments[0];
  ts.forEachChild(node,visit);
 }
 visit(ast);assert.ok(found,`Missing actual ${name}`);
 const code=ts.transpileModule(`const actual=${found.getText(ast)};`,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 return new Function(...Object.keys(bindings),`${code}\nreturn actual;`)(...Object.values(bindings));
}
test("actual register initially renders labelled editable filters and honest loading without a fake total or nested main",()=>{
 const component=module().CollectionRegisterPanel,html=renderToStaticMarkup(React.createElement(component,props));
 assert.match(html,/aria-label="Collection register filters"/);assert.match(html,/Reading the collection register/);
 assert.match(html,/type="date"/);assert.match(html,/type="search"/);assert.doesNotMatch(html,/<fieldset disabled|<main\b|Gross fee collections|₹/);
 assert.notEqual(component(props).key,component({...props,schoolId:"74000000-0000-4000-8000-000000000002"}).key);
});
test("actual page controls remain mounted, native-enabled and aria-disabled during reads and exhaustion",()=>{
 for(const busy of[false,true]){
  const html=renderToStaticMarkup(React.createElement(module([filter,report,busy,""]).CollectionRegisterPanel,props));
  for(const caption of["Previous collection page","Next collection page"]){
   const button=html.match(new RegExp(`<button\\b([^>]*)>${caption}</button>`));assert.ok(button);assert.match(button[1],/aria-disabled="true"/);assert.doesNotMatch(button[1],/\sdisabled(?:\s|=|$)|tabindex="-1"/i);
  }
  assert.match(html,/Posting dates 2026-10-01 to 2026-10-16/);assert.match(html,/Totals cover the entire applied filter/);
  assert.doesNotMatch(html,/<fieldset disabled/);
 }
});
test("actual reversal cards keep the applied currency, sign and exact original receipt actions despite edited filter inputs",()=>{
 const payment="74000000-0000-4000-8000-000000000002",summary={collectionCount:0,grossCollections:0,reversalCount:1,reversalAmount:29.29,netCollections:-29.29,refundCount:0,refundAmount:0};
 const data={...report,total:1,summary,tenderTotals:register.collectionMethods.map(paymentMethod=>({paymentMethod,summary:paymentMethod==="CASH"?summary:zero})),items:[{id:"74000000-0000-4000-8000-000000000003",kind:"REVERSAL",paymentId:payment,accountId:"74000000-0000-4000-8000-000000000004",learnerId:"74000000-0000-4000-8000-000000000005",learnerName:"Synthetic learner",admissionNumber:null,gradeCode:"VI",sessionName:"Synthetic session",receiptNumber:"SYN-000001",paymentMethod:"CASH",receiptStatus:"REVERSED",amount:29.29,postingOn:"2026-10-16",recordedAt:"2026-10-16T10:00:00Z",timing:{originalReceivedOn:"2026-10-02",actualReceivedAt:null,receivedTimePrecision:"DATE_ONLY",schoolTimeZone:null}}]};
 const html=renderToStaticMarkup(React.createElement(module([{...filter,currencyCode:"USD",from:"2026-09-01"},data,false,""]).CollectionRegisterPanel,{...props,currencies:["INR","USD"]}));
 assert.match(html,/−₹29\.29/);assert.match(html,/Posting dates 2026-10-01 to 2026-10-16 · INR/);assert.match(html,/Reversal movement/);
 assert.ok(html.includes(`data-receipt="${payment}"`));assert.ok(html.includes(`data-school="${school}"`));assert.match(html,/data-receipt-status="REVERSED"/);
});

test("actual refund card shows a separate negative partial amount, source receipt action and distinct processing evidence",()=>{
 const payment="74000000-0000-4000-8000-000000000002",summary={...zero,refundCount:1,refundAmount:50.25,netCollections:-50.25};
 const data={...report,total:1,summary,tenderTotals:register.collectionMethods.map(paymentMethod=>({paymentMethod,summary:paymentMethod==="ONLINE_PROVIDER"?summary:zero})),
  items:[{id:"74000000-0000-4000-8000-000000000003",kind:"REFUND",paymentId:payment,accountId:school,learnerId:school,
   learnerName:"Synthetic learner",admissionNumber:"SYN-1",gradeCode:"VI",sessionName:"Synthetic session",receiptNumber:"SYN-ORIGINAL",paymentMethod:"ONLINE_PROVIDER",receiptStatus:"POSTED",
   amount:50.25,postingOn:"2026-10-16",recordedAt:"2026-10-16T12:00:00Z",timing:{originalReceivedOn:"2026-10-02",actualReceivedAt:"2026-10-02T10:00:00Z",receivedTimePrecision:"INSTANT",schoolTimeZone:"Asia/Kolkata"},
   refundTiming:{processedOn:"2026-10-15",processedAt:"2026-10-15T11:00:00.123456789Z",schoolTimeZone:"Asia/Kolkata"}}]};
 const html=renderToStaticMarkup(React.createElement(module([filter,data,false,""]).CollectionRegisterPanel,props));
 assert.match(html,/Refund movement/);assert.match(html,/−₹50\.25/);assert.match(html,/Original receipt evidence — not refund processing time/);
 assert.match(html,/2026-10-15T11:00:00\.123456789Z/);assert.match(html,/Reviewed refund recorded at 2026-10-16T12:00:00Z/);
 assert.ok(html.includes(`data-receipt="${payment}"`));assert.match(html,/SYN-ORIGINAL/);
 assert.match(html,/Pending refunds and verified holds do not change this register/);
});
test("actual load keeps the previous applied snapshot while busy, fences duplicates and atomically replaces a validated read",async()=>{
 const request={current:null},state={data:report,busy:false,error:""};let calls=0,finish;
 const load=actual("load",{request,clearExport:()=>{},collectionFilter:register.collectionFilter,collectionRegisterError:register.collectionRegisterError,
  setBusy:value=>state.busy=value,setError:value=>state.error=value,setData:value=>state.data=value,
  loadCollectionRegister:async checked=>{calls++;assert.notEqual(checked,filter);return new Promise(resolve=>finish=resolve);}});
 const pending=load(filter,0);assert.equal(state.busy,true);assert.equal(state.data,report);
 await load(filter,0);assert.equal(calls,1);
 const next={...report,total:0};finish(next);await pending;assert.equal(state.data,next);assert.equal(request.current,null);assert.equal(state.busy,false);
});
test("actual failed reads clear financial snapshots and do not reflect private transport diagnostics",async()=>{
 const request={current:null},state={data:report,busy:false,error:""};
 const load=actual("load",{request,clearExport:()=>{},collectionFilter:register.collectionFilter,collectionRegisterError:register.collectionRegisterError,
  setBusy:value=>state.busy=value,setError:value=>state.error=value,setData:value=>state.data=value,
  loadCollectionRegister:async()=>{throw new Error("SYNTHETIC PRIVATE BANK / SQL DETAILS");}});
 await load(filter,0);assert.equal(state.data,null);assert.equal(state.error,register.collectionRegisterErrors.unavailable);assert.equal(state.busy,false);
});
test("actual effect cleanup aborts and releases its request so Strict Mode's fresh setup can read again",async()=>{
 const request={current:null},initial={current:filter},controllers=[];
 const setup=actual("useEffect",{request,initial,preparedResource:{current:null},releaseCollectionExport:()=>{},load:()=>{const next=new AbortController();controllers.push(next);request.current=next;return Promise.resolve();}});
 const cancelledBeforeRead=setup();cancelledBeforeRead();await Promise.resolve();assert.equal(controllers.length,0);
 const cleanup=setup();await Promise.resolve();cleanup();assert.equal(request.current,null);assert.equal(controllers[0].signal.aborted,true);
 const secondCleanup=setup();await Promise.resolve();assert.equal(controllers.length,2);assert.equal(controllers[1].signal.aborted,false);secondCleanup();assert.equal(request.current,null);
});
test("controlled register labels, error states and page summaries have complete Hindi placeholders",()=>{
 const keys=new Set([...Object.keys(collectionRegisterMessages),...Object.values(register.collectionRegisterErrors)]);
 const printable=readFileSync(new URL("./fee-collection-print-document.ts",import.meta.url),"utf8");
 const ast=ts.createSourceFile("actual.tsx",source+"\n"+printable,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
 function copy(node){if(!node)return;if(ts.isStringLiteral(node))keys.add(node.text);else if(ts.isConditionalExpression(node)){copy(node.whenTrue);copy(node.whenFalse);}}
 function visit(node){if(ts.isCallExpression(node)&&node.expression.getText(ast)==="t")copy(node.arguments[0]);ts.forEachChild(node,visit);}visit(ast);
 for(const key of keys){const translated=hindi.fees[key]??hindi.common[key];assert.equal(typeof translated,"string",`Missing Hindi: ${key}`);assert.deepEqual([...translated.matchAll(/\{(\w+)\}/g)].map(value=>value[1]).sort(),[...key.matchAll(/\{(\w+)\}/g)].map(value=>value[1]).sort());}
});

test("actual report preparation uses the applied snapshot, fences repeated clicks and ignores a late aborted response",async()=>{
 const request={current:null},preparedResource={current:null},state={busy:false,error:"",report:null,data:report};let calls=0,builds=0,finish;
 const snapshot={...report,schoolName:"Synthetic school",generatedAt:"2026-10-16T10:00:00Z"};
 // Bind the real callback with a delayed loader, not a simulated event handler.
 const bindings={request,preparedResource,data:report,schoolId:school,locale:"hi",t:translate,clearExport:()=>{},
  setBusy:value=>state.busy=value,setError:value=>state.error=value,setData:value=>state.data=value,setReport:value=>state.report=value,
  collectionRegisterError:register.collectionRegisterError,loadCollectionExport:async filter=>{calls++;assert.deepEqual(filter,{schoolId:school,from:report.from,to:report.to,currencyCode:report.currencyCode,paymentMethod:"",query:""});return new Promise(resolve=>finish=resolve);},
  prepareCollectionExport:(value,locale)=>{builds++;assert.equal(value,snapshot);assert.equal(locale,"hi");return {csvUrl:"blob:synthetic-csv",printUrl:"blob:synthetic-print",total:0};}};
 const actualPrepare=actual("prepare",bindings),pending=actualPrepare();await actualPrepare();assert.equal(calls,1);assert.equal(state.busy,true);
 finish(snapshot);await pending;assert.equal(state.report,preparedResource.current);assert.equal(state.busy,false);
 const late=actualPrepare();request.current.abort();finish(snapshot);await late;assert.equal(builds,1);
});

test("actual export callback releases a late lazy-render result after cleanup instead of publishing private links",async()=>{
 const request={current:null},preparedResource={current:null},released=[],state={report:null,busy:false};let finish;
 const result={csvUrl:"blob:synthetic-late-csv",printUrl:"blob:synthetic-late-print",total:0};
 const bindings={request,preparedResource,data:report,schoolId:school,locale:"en",t:translate,clearExport:()=>{},
  setBusy:value=>state.busy=value,setError:()=>{},setData:()=>{},setReport:value=>state.report=value,
  collectionRegisterError:register.collectionRegisterError,loadCollectionExport:async()=>report,
  prepareCollectionExport:async(value,locale,t,signal)=>{assert.equal(value,report);assert.equal(signal,request.current.signal);return new Promise(resolve=>finish=resolve);},
  releaseCollectionExport:prepared=>released.push(prepared)};
 const pending=actual("prepare",bindings)();await Promise.resolve();assert.equal(state.busy,true);
 request.current.abort();request.current=null;finish(result);await pending;
 assert.deepEqual(released,[result]);assert.equal(state.report,null);assert.equal(preparedResource.current,null);
});

test("the native print renderer is dynamically loaded only inside explicit export preparation",()=>{
 const exporter=readFileSync(new URL("./fee-collection-export.ts",import.meta.url),"utf8"),printable=readFileSync(new URL("./fee-collection-print-document.ts",import.meta.url),"utf8");
 const ast=ts.createSourceFile("export.ts",exporter,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
 assert.ok(!ast.statements.some(node=>ts.isImportDeclaration(node)&&/react-dom|print-document/.test(node.moduleSpecifier.text)));
 const preparation=ast.statements.find(node=>ts.isFunctionDeclaration(node)&&node.name?.text==="prepareCollectionExport");
 assert.ok(preparation);let dynamic=false;
 function visit(node){if(ts.isCallExpression(node)&&node.expression.kind===ts.SyntaxKind.ImportKeyword&&node.arguments[0].text==="./fee-collection-print-document")dynamic=true;ts.forEachChild(node,visit);}visit(preparation);
 assert.equal(dynamic,true);assert.match(printable,/renderToStaticMarkup\(document\)/);assert.doesNotMatch(printable,/dangerouslySetInnerHTML|replaceAll|<script/);
});

test("actual ready exports are explicit keyboard-native links with a private-record warning and no automatic popup or print",()=>{
 const prepared={csvUrl:"blob:synthetic-csv",printUrl:"blob:synthetic-print",filename:"fee-collections-2026-10-01-2026-10-16-INR.csv",generatedAt:"2026-10-16T10:00:00Z",total:26};
 const html=renderToStaticMarkup(React.createElement(module([filter,report,false,"",prepared]).CollectionRegisterPanel,props));
 assert.match(html,/Prepare full-range CSV and print/);assert.match(html,/Downloads contain private school records/);assert.match(html,/Complete report ready: 26 movements/);
 assert.match(html,/href="blob:synthetic-csv" download="fee-collections-/);assert.match(html,/target="_blank" rel="noopener noreferrer"/);
 assert.doesNotMatch(source,/window\.open|window\.print|dangerouslySetInnerHTML|localStorage|sessionStorage/);
});
test("actual finance navigation retains the unresolved-collection guard and semantic responsive theme styles",()=>{
 const workspace=readFileSync(new URL("../components/workspace/finance-workspace.tsx",import.meta.url),"utf8");
 assert.match(workspace,/<button disabled=\{collectionBlocked \|\| refundBlocked\}[^\n]+setTab\("register"\)/);
 assert.match(workspace,/<CollectionRegisterPanel key=\{data\.schoolId\}/);
 assert.match(workspace,/min=\{refundDayCloseMinimum\(tender\.paymentMethod,tender\.systemAmount,data\.dayCloseCandidate\.refundCount\)\}/);
 const css=readFileSync(new URL("../components/workspace/collection-register-panel.module.css",import.meta.url),"utf8");
 for(const token of["--theme-text","--theme-card","--theme-border","--theme-error-text","--theme-error-bg"])assert.ok(css.includes(token));
 assert.match(css,/:focus-visible/);assert.match(css,/min-height:\s*44px/);assert.match(css,/max-width:600px/);assert.match(css,/prefers-reduced-motion/);
 assert.doesNotMatch(source,/localStorage|sessionStorage|window\.confirm|<main\b|method:\s*"POST"/);
});
