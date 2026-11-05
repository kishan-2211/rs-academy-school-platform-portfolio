import assert from "node:assert/strict";
import test from "node:test";
import { collectionFilter, collectionMethods, collectionRegisterError, collectionRegisterErrors, loadCollectionRegister, readCollectionRegister, refundDayCloseMinimum } from "./fee-collection-register.ts";

const school="73000000-0000-4000-8000-000000000001",payment="73000000-0000-4000-8000-000000000002",account="73000000-0000-4000-8000-000000000003",learner="73000000-0000-4000-8000-000000000004",reversal="73000000-0000-4000-8000-000000000005";
const filter=(extra={})=>({schoolId:school,from:"2026-10-01",to:"2026-10-16",currencyCode:"INR",paymentMethod:"",query:"",...extra});
const zero=()=>({collectionCount:0,grossCollections:0,reversalCount:0,reversalAmount:0,netCollections:0,refundCount:0,refundAmount:0});
const row=(extra={})=>({id:payment,kind:"COLLECTION",paymentId:payment,accountId:account,learnerId:learner,learnerName:"Synthetic learner",admissionNumber:"SYN-1",gradeCode:"VI",sessionName:"Synthetic 2026-2027",receiptNumber:"SYN-000001",paymentMethod:"CASH",receiptStatus:"POSTED",amount:29.29,postingOn:"2026-10-03",recordedAt:"2026-10-03T10:00:00Z",timing:{originalReceivedOn:"2026-10-02",actualReceivedAt:null,receivedTimePrecision:"DATE_ONLY",schoolTimeZone:null},...extra});
const report=(extra={})=>{
 const summary={collectionCount:1,grossCollections:29.29,reversalCount:0,reversalAmount:0,netCollections:29.29,refundCount:0,refundAmount:0};
 return {schoolId:school,from:"2026-10-01",to:"2026-10-16",currencyCode:"INR",paymentMethod:null,query:null,page:0,pageSize:25,total:1,hasMore:false,summary,tenderTotals:collectionMethods.map(paymentMethod=>({paymentMethod,summary:paymentMethod==="CASH"?{...summary}:zero()})),items:[row()],...extra};
};
test("date range, currency, literal search and paging are validated without silently changing the applied scope",()=>{
 assert.equal(collectionFilter(filter({query:"  Synthetic_%\\' परीक्षण  "})).query,"Synthetic_%\\' परीक्षण");
 assert.equal(collectionFilter(filter({from:"2024-01-01",to:"2024-12-31"}),10000,100).from,"2024-01-01");
 for(const change of [{schoolId:"../school"},{from:"2026-02-30"},{from:"0000-01-01",to:"0000-01-01"},{from:"2024-01-01",to:"2025-01-01"},{currencyCode:"INR,USD"},{paymentMethod:"ALL"},{query:"x".repeat(101)},{query:"Synthetic\nprivate"}])assert.throws(()=>collectionFilter(filter(change)));
 for(const [page,size]of[[-1,25],[10001,25],[0,0],[0,101]])assert.throws(()=>collectionFilter(filter(),page,size));
});
test("whole-range monetary and tender totals require exact integer paise, not floating-point subtraction",()=>{
 assert.equal(readCollectionRegister(report(),filter()).summary.grossCollections,29.29);
 for(const summary of [{...zero(),grossCollections:1},{...report().summary,netCollections:29.28},{...report().summary,grossCollections:0.1+0.2}])assert.throws(()=>readCollectionRegister(report({summary}),filter()));
 const wrong=report();wrong.tenderTotals[0].summary.netCollections=28.29;assert.throws(()=>readCollectionRegister(wrong,filter()));
 const duplicated=report();duplicated.tenderTotals[1].paymentMethod="CASH";assert.throws(()=>readCollectionRegister(duplicated,filter()));
});
test("a reversal of earlier money has a negative current net while preserving the original receipt time",()=>{
 const summary={collectionCount:0,grossCollections:0,reversalCount:1,reversalAmount:29.29,netCollections:-29.29,refundCount:0,refundAmount:0};
 const value=report({summary,items:[row({id:reversal,kind:"REVERSAL",receiptStatus:"REVERSED",postingOn:"2026-10-10"})],tenderTotals:collectionMethods.map(paymentMethod=>({paymentMethod,summary:paymentMethod==="CASH"?{...summary}:zero()}))});
 const checked=readCollectionRegister(value,filter());assert.equal(checked.summary.netCollections,-29.29);assert.equal(checked.items[0].timing.originalReceivedOn,"2026-10-02");
 const historical=report({items:[row({receiptStatus:"REVERSED"})]});assert.equal(readCollectionRegister(historical,filter()).summary.grossCollections,29.29);
});
test("wrong school, dates, currency, page or total cannot render as another applied result",()=>{
 for(const change of[{schoolId:learner},{currencyCode:"USD"},{from:"2026-09-01"},{paymentMethod:"SCHOOL_UPI"},{query:"another"},{page:1},{pageSize:26},{total:2},{hasMore:true}])assert.throws(()=>readCollectionRegister(report(change),filter()));
 const wrong=report();wrong.items[0].postingOn="2026-09-01";assert.throws(()=>readCollectionRegister(wrong,filter()));
});
test("bounded pages retain complete-filter totals even when a page is empty",()=>{
 const value=report({page:4,items:[]});const checked=readCollectionRegister(value,filter(),4);assert.equal(checked.total,1);assert.equal(checked.summary.grossCollections,29.29);
 assert.throws(()=>readCollectionRegister({...value,hasMore:true},filter(),4));
});
test("exact received instants survive parsing and recording time cannot replace missing received evidence",()=>{
 const timing={originalReceivedOn:"2026-10-02",actualReceivedAt:"2026-10-02T10:00:00.123456789Z",receivedTimePrecision:"INSTANT",schoolTimeZone:"Asia/Kolkata"};
 assert.equal(readCollectionRegister(report({items:[row({timing})]}),filter()).items[0].timing.actualReceivedAt,timing.actualReceivedAt);
 for(const invalid of[{...timing,actualReceivedAt:null},{...timing,receivedTimePrecision:"DATE_ONLY"},{...timing,originalReceivedOn:"not-a-date"}])assert.throws(()=>readCollectionRegister(report({items:[row({timing:invalid})]}),filter()));
});

test("completed partial refund is its own negative movement without altering original receipt timing",()=>{
 const summary={...zero(),refundCount:1,refundAmount:50.25,netCollections:-50.25};
 const refundTiming={processedOn:"2026-10-09",processedAt:"2026-10-09T10:00:00.123456789Z",schoolTimeZone:"Asia/Kolkata"};
 const data=report({summary,items:[row({id:reversal,kind:"REFUND",paymentMethod:"ONLINE_PROVIDER",amount:50.25,postingOn:"2026-10-10",refundTiming})],
  tenderTotals:collectionMethods.map(paymentMethod=>({paymentMethod,summary:paymentMethod==="ONLINE_PROVIDER"?{...summary}:zero()}))});
 const checked=readCollectionRegister(data,filter());
 assert.equal(checked.summary.grossCollections,0);assert.equal(checked.summary.refundAmount,50.25);
 assert.equal(checked.items[0].timing.originalReceivedOn,"2026-10-02");assert.equal(checked.items[0].refundTiming.processedAt,refundTiming.processedAt);
 for(const change of[{refundTiming:null},{refundTiming:{...refundTiming,processedOn:"2026-10-11"}},{paymentMethod:"CASH"},{receiptStatus:"REVERSED"},{kind:"HOLD"}]){
  assert.throws(()=>readCollectionRegister({...data,items:[{...data.items[0],...change}]},filter()));
 }
 const overcount={...data,summary:{...summary,refundCount:0}};assert.throws(()=>readCollectionRegister(overcount,filter()));
});

test("signed day-close input stays nonnegative except actual refund-negative online tender",()=>{
 assert.equal(refundDayCloseMinimum("ONLINE_PROVIDER",-50.25,1),-50.25);
 for(const method of["CASH","BANK_TRANSFER","CHEQUE","SCHOOL_UPI"])assert.equal(refundDayCloseMinimum(method,-50.25,1),0);
 assert.equal(refundDayCloseMinimum("ONLINE_PROVIDER",-50.25,0),0);
 assert.equal(refundDayCloseMinimum("ONLINE_PROVIDER",29.29,1),0);
 assert.equal(refundDayCloseMinimum("ONLINE_PROVIDER",Number.NaN,1),0);
 assert.equal(refundDayCloseMinimum("ONLINE_PROVIDER",-100000000.01,2),-100000000);
});
test("actual fetch uses the authenticated private read endpoint, literal encoded filters and cancellation",async t=>{
 const request=filter({query:"Synthetic_%\\' परीक्षण"}),signal=new AbortController().signal;
 t.mock.method(globalThis,"fetch",async(url,options)=>{
  const parsed=new URL(url);assert.equal(parsed.pathname,"/api/v1/finance/collections");assert.equal(parsed.searchParams.get("q"),request.query);
  assert.equal(parsed.searchParams.get("currencyCode"),"INR");assert.equal(options.credentials,"include");assert.equal(options.cache,"no-store");assert.equal(options.signal,signal);assert.equal(options.method,undefined);
  return Response.json(report({query:request.query}));
 });
 assert.equal((await loadCollectionRegister(request,0,signal)).total,1);
});
test("session, scope and unexpected diagnostics use controlled copy, never private server errors",async t=>{
 for(const [status,copy]of[[401,collectionRegisterErrors.session],[403,collectionRegisterErrors.scope],[400,collectionRegisterErrors.filters],[500,collectionRegisterErrors.unavailable]]){
  t.mock.method(globalThis,"fetch",async()=>new Response("SYNTHETIC PRIVATE SQL / BANK REFERENCE",{status}));
  await assert.rejects(loadCollectionRegister(filter(),0,new AbortController().signal),{message:copy});
 }
 assert.equal(collectionRegisterError(new Error("SYNTHETIC PRIVATE SQL")),collectionRegisterErrors.unavailable);
});
