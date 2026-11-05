import assert from "node:assert/strict";
import test from "node:test";
import { loadRefundPayment, loadRefundQueue, prepareRefund, prepareRefundDecision, readRefundPayment, readRefundQueue, readRefundProcessing, refundProcessingStates, refundError, refundErrors, RefundWriteError, sendRefund } from "./gateway-refunds.ts";

const school="75000000-0000-4000-8000-000000000001",paymentId="75000000-0000-4000-8000-000000000002",chargeId="75000000-0000-4000-8000-000000000003",requestId="75000000-0000-4000-8000-000000000004";
const payment={schoolId:school,paymentId,receiptNumber:"SYN-00001",accountId:"75000000-0000-4000-8000-000000000005",learnerName:"Synthetic learner",currencyCode:"INR",amount:29.29,reserved:10,available:19.29,canRequest:true,executionEnabled:false,allocations:[{chargeId,label:"Synthetic tuition",refundable:true,originalPaid:29.29,reserved:10,available:19.29}]};
const filter={schoolId:school,status:"SUBMITTED",query:"",page:0};
const request={id:requestId,paymentId,receiptNumber:"SYN-00001",learnerName:"Synthetic learner",amount:10,currencyCode:"INR",status:"SUBMITTED",reason:"Synthetic review only",requestedAt:"2026-10-17T10:00:00Z",requestedBy:"Synthetic maker",isMaker:false,canReview:true,canCancel:false,allocations:[{chargeId,label:"Synthetic tuition",amount:10}],decision:null};
const queue={schoolId:school,status:"SUBMITTED",query:null,page:0,pageSize:25,total:1,hasMore:false,executionEnabled:false,items:[request]};

test("configured processing is only a boolean hint and approved enabled responses require separate evidence",()=>{
 assert.equal(readRefundPayment({...payment,executionEnabled:true},school,paymentId).executionEnabled,true);
 const approved={...request,status:"APPROVED",canReview:false,decision:{id:paymentId,decision:"APPROVE",reason:"Synthetic independent decision",decidedAt:request.requestedAt,decidedBy:"Synthetic checker"}};
 const expected={...filter,status:"ALL"},disabled={...queue,status:"ALL",items:[approved]};
 assert.equal(readRefundQueue(disabled,expected).items[0].processing,undefined);
 assert.throws(()=>readRefundQueue({...disabled,executionEnabled:true},expected));
 const processing={state:"AWAITING_PROCESSING",requiresReview:false,lastCheckedAt:null,nextCheckAt:request.requestedAt};
 assert.equal(readRefundQueue({...disabled,executionEnabled:true,items:[{...approved,processing}]},expected).items[0].processing,processing);
 assert.throws(()=>readRefundQueue({...queue,items:[{...request,processing}]},filter));
});

test("processing decoder rejects invented money states, missing times and contradictory review or scheduling claims",()=>{
 const pending={state:"PENDING_CONFIRMATION",requiresReview:false,lastCheckedAt:request.requestedAt,nextCheckAt:null};
 for(const state of refundProcessingStates){const row={...pending,state,requiresReview:["NOT_QUEUED","PROVIDER_FAILED","REVIEW_REQUIRED"].includes(state),lastCheckedAt:["NOT_QUEUED","AWAITING_PROCESSING"].includes(state)?null:request.requestedAt};assert.equal(readRefundProcessing(row),row);}
 for(const row of[{...pending,state:"PAID"},{...pending,requiresReview:"false"},{...pending,lastCheckedAt:"not-a-date"},{...pending,state:"VERIFIED_AWAITING_POSTING",lastCheckedAt:null},{...pending,state:"DUES_RESTORED",nextCheckAt:request.requestedAt},{...pending,state:"REVIEW_REQUIRED"},{...pending,state:"PROVIDER_FAILED"},{...pending,state:"AWAITING_PROCESSING"}])assert.throws(()=>readRefundProcessing(row),{message:refundErrors.unavailable});
});
test("refund payment projection binds exact school/receipt, nonrefundable availability and original integer paise",()=>{
 assert.equal(readRefundPayment(payment,school,paymentId),payment);
 for(const changed of[{schoolId:requestId},{paymentId:requestId},{amount:29.291},{executionEnabled:"true"},{reserved:11},{available:20},{allocations:[{...payment.allocations[0],refundable:false}]}])assert.throws(()=>readRefundPayment({...payment,...changed},school,paymentId),{message:refundErrors.unavailable});
 const nonrefundable={...payment,canRequest:false,available:0,allocations:[{...payment.allocations[0],refundable:false,available:0}]};assert.equal(readRefundPayment(nonrefundable,school,paymentId),nonrefundable);
});
test("queue refuses cross-school, unexpected execution, false totals, maker self-review and decision/history mismatch",()=>{
 assert.equal(readRefundQueue(queue,filter),queue);
 for(const changed of[{schoolId:requestId},{pageSize:50},{total:2},{hasMore:true},{executionEnabled:"true"},{items:[{...request,isMaker:true}]},{items:[{...request,allocations:[{...request.allocations[0],amount:9}]}]},{items:[{...request,decision:{id:paymentId,decision:"APPROVE",reason:"Synthetic decision",decidedAt:request.requestedAt,decidedBy:"Synthetic checker"}}]}])assert.throws(()=>readRefundQueue({...queue,...changed},filter));
 const approved={...request,status:"APPROVED",canReview:false,decision:{id:paymentId,decision:"APPROVE",reason:"Synthetic decision",decidedAt:request.requestedAt,decidedBy:"Synthetic checker"}};
 assert.equal(readRefundQueue({...queue,status:"ALL",items:[approved]},{...filter,status:"ALL"}).items[0].status,"APPROVED");
});
test("refund preparation uses only available original allocations, exact paise and frozen request identity",()=>{
 const attempt=prepareRefund(payment,{[chargeId]:"19.29"},"  Synthetic intent only  ",requestId),body=JSON.parse(attempt.body);
 assert.equal(Object.isFrozen(attempt),true);assert.equal(attempt.expectedStatus,"SUBMITTED");assert.equal(body.clientRequestId,requestId);assert.equal(body.reason,"Synthetic intent only");assert.deepEqual(body.allocations,[{chargeId,amount:19.29}]);
 for(const value of["19.30","-1","0","1.001","1e1","","0.0001"])assert.throws(()=>prepareRefund(payment,{[chargeId]:value},"Synthetic intent",requestId));
 assert.throws(()=>prepareRefund(payment,{[requestId]:"1"},"Synthetic intent",requestId));assert.throws(()=>prepareRefund({...payment,canRequest:false},{[chargeId]:"1"},"Synthetic intent",requestId));assert.throws(()=>prepareRefund(payment,{[chargeId]:"1"},"  x  ",requestId));
});
test("decision preparation enforces maker cancellation versus independent review, retaining exact reason/body",()=>{
 const checker=prepareRefundDecision(school,request,"APPROVE","Synthetic independent approval");assert.equal(checker.requestId,requestId);assert.equal(checker.expectedStatus,"APPROVED");assert.equal(Object.isFrozen(checker),true);
 assert.throws(()=>prepareRefundDecision(school,{...request,isMaker:true},"APPROVE","Synthetic forbidden self approval"));
 assert.throws(()=>prepareRefundDecision(school,request,"CANCEL","Synthetic checker cancellation"));
 const maker={...request,isMaker:true,canReview:false,canCancel:true};assert.equal(prepareRefundDecision(school,maker,"CANCEL","Synthetic maker cancellation").expectedStatus,"CANCELLED");
 assert.throws(()=>prepareRefundDecision(school,{...maker,status:"APPROVED"},"CANCEL","Synthetic late cancellation"));
});
test("refund reasons reject every embedded ISO control consistently with server canonical terms",()=>{
 for(const control of["\n","\r","\t","\u0000","\u001f","\u007f","\u0085","\u009f"]){
  for(const reason of[`Synthetic${control}intent`,`${control}Synthetic intent`,`Synthetic intent${control}`]){
   assert.throws(()=>prepareRefund(payment,{[chargeId]:"1"},reason,requestId),{message:refundErrors.reason});
   assert.throws(()=>prepareRefundDecision(school,request,"APPROVE",reason),{message:refundErrors.reason});
  }
 }
});
test("reads send authenticated no-store requests and validate scopes rather than trusting UI hints",async()=>{
 const original=globalThis.fetch;const calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(url.includes("/payments/")?payment:queue);};
 try{const signal=new AbortController().signal;assert.equal((await loadRefundQueue(filter,signal)).total,1);await loadRefundPayment(school,paymentId,signal);for(const call of calls){assert.equal(call.init.credentials,"include");assert.equal(call.init.cache,"no-store");assert.equal(call.init.signal,signal);}assert.match(calls[0].url,/status=SUBMITTED&page=0&pageSize=25/);assert.match(calls[1].url,new RegExp(paymentId));}finally{globalThis.fetch=original;}
});
test("failed reads expose controlled bilingual-able copy and never private response diagnostics",async()=>{
 const original=globalThis.fetch;
 try{for(const [status,message]of[[401,refundErrors.session],[403,refundErrors.scope],[400,refundErrors.filters],[500,refundErrors.unavailable]]){globalThis.fetch=async()=>Response.json({detail:"SYNTHETIC PRIVATE SQL / PROVIDER EVIDENCE"},{status});await assert.rejects(loadRefundQueue(filter,new AbortController().signal),{message});}assert.equal(refundError(new Error("PRIVATE")),refundErrors.unavailable);}finally{globalThis.fetch=original;}
});
test("write retry reuses the exact immutable key/body, csrf session credentials and no provider details",async()=>{
 const attempt=prepareRefund(payment,{[chargeId]:"1.01"},"Synthetic intent",requestId),original=globalThis.fetch,calls=[];
 globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json({id:requestId,paymentId,status:"SUBMITTED"});};
 try{await sendRefund(attempt,"synthetic-csrf");await sendRefund(attempt,"synthetic-csrf");assert.equal(calls.length,2);assert.equal(calls[0].init.body,calls[1].init.body);for(const call of calls){assert.equal(call.init.body,attempt.body);assert.equal(call.init.credentials,"include");assert.equal(call.init.cache,"no-store");assert.equal(call.init.method,"POST");assert.equal(call.init.headers["X-CSRF-TOKEN"],"synthetic-csrf");assert.doesNotMatch(call.init.body,/card|cvv|upi|provider|token/i);}}finally{globalThis.fetch=original;}
});
test("submit retry accepts the same intent after independent review without treating it as refund success",async()=>{
 const attempt=prepareRefund(payment,{[chargeId]:"1.01"},"Synthetic intent",requestId),original=globalThis.fetch,bodies=[];
 try{for(const status of["SUBMITTED","APPROVED","REJECTED","CANCELLED"]){globalThis.fetch=async(_url,init)=>{bodies.push(init.body);return Response.json({id:requestId,paymentId,status});};await sendRefund(attempt,"synthetic-csrf");}
  assert.deepEqual(bodies,Array(4).fill(attempt.body));
  for(const status of["SUCCESS","REFUNDED","PAID"]){globalThis.fetch=async()=>Response.json({id:requestId,paymentId,status});await assert.rejects(sendRefund(attempt,"synthetic-csrf"),error=>error instanceof RefundWriteError&&error.outcome==="unknown");}
 }finally{globalThis.fetch=original;}
});
test("network, 5xx, timeout or malformed success is unknown while confirmed HTTP rejection remains controlled",async()=>{
 const attempt=prepareRefundDecision(school,request,"APPROVE","Synthetic approval"),original=globalThis.fetch;
 try{for(const factory of[()=>Promise.reject(new Error("PRIVATE network")),()=>new Response("private",{status:503}),()=>new Response("timeout",{status:408}),()=>Response.json({id:requestId,paymentId,status:"PAID"}),()=>Response.json({id:school,paymentId,status:"APPROVED"}),()=>new Response("invalid-json",{status:200})]){globalThis.fetch=async()=>factory();await assert.rejects(sendRefund(attempt,"synthetic-csrf"),error=>error instanceof RefundWriteError&&error.outcome==="unknown"&&error.message===refundErrors.unknown);}
  globalThis.fetch=async()=>Response.json({detail:"PRIVATE"},{status:409});await assert.rejects(sendRefund(attempt,"synthetic-csrf"),error=>error.outcome==="rejected"&&error.message===refundErrors.rejected);
 }finally{globalThis.fetch=original;}
});
