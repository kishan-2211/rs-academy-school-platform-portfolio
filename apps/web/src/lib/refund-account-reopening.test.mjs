import assert from "node:assert/strict";
import test from "node:test";
import { loadReopeningContext, loadReopeningQueue, prepareReopening, prepareReopeningDecision, readReopeningContext, readReopeningQueue, reopeningError, reopeningErrors, reopeningFilter, reopeningPaise, ReopeningWriteError, sendReopening } from "./refund-account-reopening.ts";

const school="78000000-0000-4000-8000-000000000001",refundId="78000000-0000-4000-8000-000000000002",paymentId="78000000-0000-4000-8000-000000000003",accountId="78000000-0000-4000-8000-000000000004",reviewId="78000000-0000-4000-8000-000000000005",key="78000000-0000-4000-8000-000000000006";
const closure={closedAt:"2026-10-18T05:00:00Z",reason:"Synthetic original closure",closedByName:"Synthetic closing officer"};
const review={id:reviewId,refundId,paymentId,accountId,learnerName:"Synthetic learner",sessionName:"Synthetic historical session",state:"SUBMITTED",reason:"Synthetic original-account review",requestedAt:"2026-10-18T06:00:00Z",makerName:"Synthetic maker",isMaker:false,canApprove:true,canReject:true,canCancel:false,closedAccountVersion:2,originalClosure:closure,decision:null};
const context={schoolId:school,refundId,paymentId,accountId,learnerName:"Synthetic learner",sessionName:"Synthetic historical session",currencyCode:"INR",schoolTimeZone:"Asia/Kolkata",sourceState:"HOLD",accountState:"CLOSED",accountVersion:2,currentClosure:closure,currentBalance:-29.29,confirmedUnpostedRefundAmount:50.25,reopeningRequired:true,canRequest:true,refundPosted:false,hasMoreHistory:false,requests:[]};
const filter={schoolId:school,status:"SUBMITTED",query:"",page:0};
const queue={schoolId:school,status:"SUBMITTED",query:null,page:0,pageSize:25,total:1,hasMore:false,refundPosted:false,items:[review]};
const result=state=>({id:reviewId,refundId,accountId,state,accountReopened:state==="APPROVED",refundPosted:false});

test("signed balance stays separate from positive confirmed unposted paise",()=>{
 assert.equal(reopeningPaise(-29.29),-2929);assert.equal(reopeningPaise(-0.01),-1);assert.equal(reopeningPaise(0),0);assert.equal(reopeningPaise(50.25),5025);
 for(const amount of[29.291,NaN,Infinity,"29.29",1e15])assert.equal(reopeningPaise(amount),null);
 assert.equal(readReopeningContext(context,school,refundId),context);
 for(const changed of[{currentBalance:1.001},{confirmedUnpostedRefundAmount:-0.01},{confirmedUnpostedRefundAmount:0},{refundPosted:true},{accountVersion:-1},{schoolId:key},{refundId:key}])assert.throws(()=>readReopeningContext({...context,...changed},school,refundId));
});
test("unapproved no-hold and conflicting source states never invent an amount or permission",()=>{
 for(const sourceState of["UNAPPROVED","NO_HOLD","CONFLICTING"]){const value={...context,sourceState,canRequest:false,confirmedUnpostedRefundAmount:null};assert.equal(readReopeningContext(value,school,refundId),value);assert.throws(()=>readReopeningContext({...value,canRequest:true},school,refundId));assert.throws(()=>readReopeningContext({...value,confirmedUnpostedRefundAmount:50.25},school,refundId));}
 assert.throws(()=>readReopeningContext({...context,confirmedUnpostedRefundAmount:null},school,refundId));
});
test("approved history preserves original closure even while the actual account is active",()=>{
 const approved={...review,state:"APPROVED",canApprove:false,canReject:false,decision:{id:key,decision:"APPROVE",reason:"Synthetic independent approval",decidedAt:"2026-10-18T07:00:00Z",reviewerName:"Synthetic checker"}};
 const value={...context,accountState:"ACTIVE",accountVersion:3,currentClosure:null,reopeningRequired:false,canRequest:false,requests:[approved]};
 assert.equal(readReopeningContext(value,school,refundId).requests[0].originalClosure,closure);
 for(const requests of[[{...review,refundId:key}],[{...review,paymentId:key}],[{...review,accountId:key}],Array(51).fill(review)])assert.throws(()=>readReopeningContext({...context,requests},school,refundId));
});
test("paged review records reject false totals cross-scope and maker/checker permission contradictions",()=>{
 assert.equal(readReopeningQueue(queue,filter),queue);
 for(const change of[{schoolId:key},{pageSize:50},{total:2},{hasMore:true},{refundPosted:true},{items:[{...review,isMaker:true}]},{items:[{...review,canCancel:true}]},{items:[{...review,state:"APPROVED"}]}])assert.throws(()=>readReopeningQueue({...queue,...change},filter));
 for(const change of[{status:"PAID"},{page:-1},{page:10001},{query:"x".repeat(101)},{query:"hello\nprivate"}])assert.throws(()=>reopeningFilter({...filter,...change}));
 assert.equal(reopeningFilter({...filter,query:"  Synthetic  "}).query,"Synthetic");
});
test("request preparation freezes exact original account version reason and client UUID",()=>{
 const attempt=prepareReopening(context,"  Synthetic reviewed need  ",key),body=JSON.parse(attempt.body);
 assert.equal(Object.isFrozen(attempt),true);assert.equal(attempt.accountId,accountId);assert.equal(attempt.refundId,refundId);assert.equal(attempt.kind,"request");
 assert.deepEqual(body,{schoolId:school,clientRequestId:key,expectedAccountVersion:2,reason:"Synthetic reviewed need"});assert.match(attempt.path,new RegExp(`/refunds/${refundId}/account-reopening$`));
 for(const change of[{canRequest:false},{accountState:"ACTIVE",currentClosure:null},{sourceState:"NO_HOLD",confirmedUnpostedRefundAmount:null}])assert.throws(()=>prepareReopening({...context,...change},"Synthetic reason",key));
});
test("independent approval rejection and maker-only cancellation use immutable exact terms",()=>{
 assert.equal(prepareReopeningDecision(school,review,"APPROVE","Synthetic review").expectedState,"APPROVED");
 assert.equal(prepareReopeningDecision(school,review,"REJECT","Synthetic review").expectedState,"REJECTED");
 assert.throws(()=>prepareReopeningDecision(school,review,"CANCEL","Synthetic cancellation"));
 assert.throws(()=>prepareReopeningDecision(school,review,"OTHER","Synthetic unsupported decision"));
 const maker={...review,isMaker:true,canApprove:false,canReject:false,canCancel:true};
 const cancellation=prepareReopeningDecision(school,maker,"CANCEL","Synthetic cancellation");assert.equal(cancellation.expectedState,"CANCELLED");assert.equal(cancellation.requestId,reviewId);assert.equal(Object.isFrozen(cancellation),true);
 for(const decision of["APPROVE","REJECT"])assert.throws(()=>prepareReopeningDecision(school,maker,decision,"Synthetic review"));
});
test("canonical reasons reject all embedded ISO controls and invalid bounded input",()=>{
 for(const reason of["x"," ","x".repeat(501),...['\n','\t','\r','\u0000','\u001f','\u007f','\u0085','\u009f'].map(control=>`Synthetic${control}reason`)]){
  assert.throws(()=>prepareReopening(context,reason,key),{message:reopeningErrors.reason});
  assert.throws(()=>prepareReopeningDecision(school,review,"APPROVE",reason),{message:reopeningErrors.reason});
 }
});
test("reads use private no-store sessions abort signals and exact scope validation",async()=>{
 const original=globalThis.fetch,calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(url.includes("/refunds/")?context:queue);};
 try{const signal=new AbortController().signal;await loadReopeningQueue(filter,signal);await loadReopeningContext(school,refundId,signal);for(const call of calls){assert.equal(call.init.credentials,"include");assert.equal(call.init.cache,"no-store");assert.equal(call.init.signal,signal);}assert.match(calls[0].url,/status=SUBMITTED&page=0&pageSize=25/);assert.match(calls[1].url,/account-reopening\?schoolId=/);}finally{globalThis.fetch=original;}
});
test("read errors and private backend diagnostics become controlled translated copy",async()=>{
 const original=globalThis.fetch;
 try{for(const [status,message]of[[401,reopeningErrors.session],[403,reopeningErrors.scope],[400,reopeningErrors.filters],[500,reopeningErrors.unavailable]]){globalThis.fetch=async()=>Response.json({detail:"SYNTHETIC PRIVATE FINANCIAL DATA"},{status});await assert.rejects(loadReopeningQueue(filter,new AbortController().signal),{message});}assert.equal(reopeningError(new Error("SYNTHETIC PRIVATE")),reopeningErrors.unavailable);}finally{globalThis.fetch=original;}
});
test("exact request retries accept retained current review state without claiming current account status or refund posting",async()=>{
 const original=globalThis.fetch,attempt=prepareReopening(context,"Synthetic reason",key),calls=[];
 try{for(const state of["SUBMITTED","APPROVED","REJECTED","CANCELLED"]){globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(result(state));};assert.equal((await sendReopening(attempt,"synthetic-csrf")).refundPosted,false);}
  assert.deepEqual(calls.map(call=>call.init.body),Array(4).fill(attempt.body));for(const {init}of calls){assert.equal(init.method,"POST");assert.equal(init.credentials,"include");assert.equal(init.cache,"no-store");assert.equal(init.headers["X-CSRF-TOKEN"],"synthetic-csrf");assert.doesNotMatch(init.body,/provider|card|cvv|pin|phone|token|balance/i);}
 }finally{globalThis.fetch=original;}
});
test("decision response requires exact review state and source identity; forged money success stays unknown",async()=>{
 const original=globalThis.fetch,attempt=prepareReopeningDecision(school,review,"APPROVE","Synthetic approval");
 try{for(const change of[{id:key},{refundId:key},{accountId:key},{state:"SUBMITTED"},{state:"PAID"},{refundPosted:true},{accountReopened:false}]){globalThis.fetch=async()=>Response.json({...result("APPROVED"),...change});await assert.rejects(sendReopening(attempt,"synthetic-csrf"),failure=>failure instanceof ReopeningWriteError&&failure.outcome==="unknown");}}
 finally{globalThis.fetch=original;}
});
test("lost network timeout 5xx and malformed success stay unknown; a definite rejection is distinguishable",async()=>{
 const original=globalThis.fetch,attempt=prepareReopening(context,"Synthetic reason",key);
 try{for(const factory of[()=>Promise.reject(new Error("SYNTHETIC PRIVATE")),()=>new Response("private",{status:503}),()=>new Response("timeout",{status:408}),()=>new Response("not-json")]){globalThis.fetch=async()=>factory();await assert.rejects(sendReopening(attempt,"synthetic-csrf"),failure=>failure.outcome==="unknown"&&failure.message===reopeningErrors.unknown);}
  globalThis.fetch=async()=>Response.json({detail:"SYNTHETIC PRIVATE"},{status:409});await assert.rejects(sendReopening(attempt,"synthetic-csrf"),failure=>failure.outcome==="rejected"&&failure.message===reopeningErrors.rejected);
  await assert.rejects(sendReopening(attempt,""),failure=>failure.outcome==="rejected"&&failure.message===reopeningErrors.csrf);
 }finally{globalThis.fetch=original;}
});
