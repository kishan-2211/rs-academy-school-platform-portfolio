import assert from "node:assert/strict";
import test from "node:test";
import { formatSettlementAmount, loadSettlementQueue, loadSettlementReport, prepareSettlementEvents, prepareSettlementSummary, readSettlementQueue, readSettlementReport, reportedSettlementMoney, sendSettlementSync, settlementErrorCode, settlementSchoolDate, SettlementWriteError } from "./gateway-settlements.ts";

const school="88000000-0000-4000-8000-000000000001",requestId="88000000-0000-4000-8000-000000000002",readId="88000000-0000-4000-8000-000000000003",clientId="88000000-0000-4000-8000-000000000004";
const when="2026-10-22T04:17:39.123456789Z";
const filter={schoolId:school,status:"ALL",kind:"ALL",query:"",page:0};
const request={id:requestId,kind:"SUMMARY",schoolTimeZone:"Asia/Kolkata",status:"COMPLETE",from:"2026-10-01T00:00:00Z",until:"2026-10-15T00:00:00Z",sourceSummaryReadId:null,settlementReference:null,requestedAt:when,requestedBy:"Synthetic accountant",pagesCompleted:1,pagesPending:0,pagesReview:0,lastCheckedAt:when,nextCheckAt:null};
const queue={schoolId:school,schoolTimeZone:"Asia/Kolkata",syncEnabled:true,canSync:true,status:"ALL",kind:"ALL",query:"",page:0,pageSize:25,total:1,hasMore:false,items:[request]};
const summary={settlementReference:"synthetic_statement",reportedStatus:"SUCCESS",transferReference:"synthetic_utr",paymentsFrom:null,paymentsThrough:null,initiatedAt:null,processedAt:when,reportedPaymentAmount:"100.00000000",reportedServiceCharge:"1.12345678",reportedServiceTax:null,reportedAdjustment:"-0.00000001",reportedSettlementCharge:null,reportedSettlementTax:null,reportedSplitCharge:null,reportedSplitTax:null,reportedVendorCommission:null,reportedAmountSettled:"98.87654321",reportedChargeType:"PREPAID",reportedSettlementType:"STANDARD"};
const event={settlementReference:"synthetic_statement",eventReference:"synthetic_event",reportedType:"NEW_PROVIDER_KIND",reportedDirection:"DEBIT",reportedStatus:"NEW_PROVIDER_STATUS",currencyCode:"USD",reportedAmount:"-7.12345678",reportedServiceCharge:null,reportedServiceTax:null,reportedSettlementAmount:null,occurredAt:when,orderReference:"synthetic_order",paymentReference:"synthetic_payment",refundReferences:["synthetic_refund"],chargesCurrencyCode:null};
const row={sourceReadId:readId,rowIndex:0,observedAt:when,summary,event:null};
const report={schoolId:school,request,query:"",page:0,pageSize:25,total:1,hasMore:false,items:[row]};
const eventRequest={...request,id:clientId,kind:"EVENTS",from:null,until:null,sourceSummaryReadId:readId,settlementReference:summary.settlementReference};
const eventReport={...report,request:eventRequest,items:[{...row,summary:null,event}]};
const fails=fn=>assert.throws(fn,error=>error.message==="SETTLEMENT_UNAVAILABLE");

test("queue preserves exact school, pagination, disabled defaults and report-only completion",()=>{
  assert.equal(readSettlementQueue(queue,filter),queue);
  assert.equal(readSettlementQueue({...queue,syncEnabled:false,canSync:false},filter).canSync,false);
  for(const changes of[{schoolId:clientId},{pageSize:50},{total:2},{hasMore:true},{query:null},{syncEnabled:false},{items:[{...request,pagesPending:1}]},{items:[{...request,status:"PAID"}]},{items:[{...request,lastCheckedAt:null}]},{items:[{...request,schoolTimeZone:"synthetic/invalid"}]}])fails(()=>readSettlementQueue({...queue,...changes},filter));
});

test("queue states require truthful pending, complete and review page counts without invented bank success",()=>{
  for(const status of["REQUESTED","SYNCING","RETRYING","COMPLETE","REVIEW_REQUIRED"]){
    const current={...request,status,pagesCompleted:status==="COMPLETE"?1:0,pagesPending:["REQUESTED","SYNCING","RETRYING"].includes(status)?1:0,pagesReview:status==="REVIEW_REQUIRED"?1:0};
    assert.equal(readSettlementQueue({...queue,items:[current]},filter).items[0].status,status);
  }
  for(const changes of[{pagesCompleted:0},{pagesCompleted:1001},{pagesCompleted:1.1},{status:"RETRYING",pagesPending:0},{status:"REVIEW_REQUIRED",pagesReview:0},{nextCheckAt:when}])fails(()=>readSettlementQueue({...queue,items:[{...request,...changes}]},filter));
});

test("scope, filters, duplicate identities, controls and private API properties fail closed",()=>{
  for(const changed of[{query:"a".repeat(101)},{query:"synthetic\u0000query"},{page:-1},{page:0.5},{status:"PAID"},{kind:"BANK"},{schoolId:"invalid"}])assert.throws(()=>readSettlementQueue(queue,{...filter,...changed}));
  fails(()=>readSettlementQueue({...queue,items:[request,request],total:2},filter));
  for(const field of["credentials","cursor","raw_body","bank_account","client_secret"]){fails(()=>readSettlementQueue({...queue,[field]:"synthetic_private"},filter));fails(()=>readSettlementReport({...report,items:[{...row,summary:{...summary,[field]:"synthetic_private"}}]},school,requestId,"",0));}
  fails(()=>readSettlementQueue({...queue,items:[{...request,requestedBy:"Synthetic\nactor"}]},filter));
});

test("summary projection retains exact strings, nullable unknowns and no assumed currency",()=>{
  assert.equal(readSettlementReport(report,school,requestId,"",0),report);
  assert.equal(readSettlementReport({...report,items:[{...row,summary:{...summary,reportedAmountSettled:null}}]},school,requestId,"",0).items[0].summary.reportedAmountSettled,null);
  for(const changes of[{reportedAmountSettled:98.87654321},{reportedAmountSettled:"98.876543219"},{reportedAmountSettled:"1e2"},{reportedStatus:"success"},{currencyCode:"INR"},{processedAt:"2026-02-30T00:00:00Z"}])fails(()=>readSettlementReport({...report,items:[{...row,summary:{...summary,...changes}}]},school,requestId,"",0));
  assert.equal(Object.hasOwn(report.items[0].summary,"currencyCode"),false);
});

test("report identity and row position bind scope, pagination and summary versus event shape",()=>{
  for(const changed of[{schoolId:clientId},{request:{...request,id:clientId}},{page:1},{hasMore:true},{total:2},{items:[{...row,rowIndex:10}]},{items:[{...row,rowIndex:-1}]},{items:[{...row,event}]},{items:[{...row,summary:null}]},{items:[{...row,sourceReadId:"invalid"}]}])fails(()=>readSettlementReport({...report,...changed},school,requestId,"",0));
  fails(()=>readSettlementReport({...report,items:[row,row],total:2},school,requestId,"",0));
  const empty={...report,total:0,items:[]};assert.equal(readSettlementReport(empty,school,requestId,"",0).total,0);
});

test("event reports retain unknown kinds and currencies, repeated event IDs across physical rows, and signed precision",()=>{
  assert.equal(readSettlementReport(eventReport,school,clientId,"",0).items[0].event.currencyCode,"USD");
  const repeated={...eventReport,total:2,items:[eventReport.items[0],{...eventReport.items[0],rowIndex:1,event:{...event,reportedType:"DISPUTE"}}]};
  assert.equal(readSettlementReport(repeated,school,clientId,"",0).items.length,2);
  for(const changes of[{settlementReference:"synthetic_foreign"},{reportedAmount:null},{reportedAmount:7},{refundReferences:"synthetic_refund"},{refundReferences:Array(101).fill("synthetic_refund")},{chargesCurrencyCode:"inr"},{occurredAt:null},{orderReference:"contains space"}])fails(()=>readSettlementReport({...eventReport,items:[{...eventReport.items[0],event:{...event,...changes}}]},school,clientId,"",0));
});

test("eight-place amounts are bounded and formatted without binary floating point or lost trailing scale",()=>{
  for(const amount of["0","0.00","-0.00000001","100000000.00000000","90071992.54740991","-100000000.00000000"])assert.equal(reportedSettlementMoney(amount),true);
  for(const amount of[0,"-0","-0.00","1.123456789","100000000.00000001","-100000000.00000001","01.00","1e8"," 1","1.","+1",null])assert.equal(reportedSettlementMoney(amount),false);
  assert.equal(formatSettlementAmount("90071992.54740991","en"),"9,00,71,992.54740991");
  assert.equal(formatSettlementAmount("100000000.00000000","hi"),"10,00,00,000.00000000");
  assert.equal(formatSettlementAmount("-0.00000001","en"),"-0.00000001");assert.equal(formatSettlementAmount(null,"en"),"—");
});

test("request and summary time comparisons retain nanoseconds rather than using rounded browser milliseconds",()=>{
  const tiny={...request,from:"2026-01-01T00:00:00.000000001Z",until:"2026-01-01T00:00:00.000000002Z"};
  assert.equal(readSettlementQueue({...queue,items:[tiny]},filter).items[0].from,tiny.from);
  fails(()=>readSettlementQueue({...queue,items:[{...tiny,until:"2026-02-01T00:00:00.000000002Z"}]},filter));
  const inverted={...summary,paymentsFrom:"2026-01-01T00:00:00.000000002Z",paymentsThrough:"2026-01-01T00:00:00.000000001Z"};fails(()=>readSettlementReport({...report,items:[{...row,summary:inverted}]},school,requestId,"",0));
});

test("school dates create exact school-zone boundaries without browser-zone assumptions",()=>{
  const now=new Date("2026-10-22T04:17:39.999Z");
  assert.equal(settlementSchoolDate("Asia/Kolkata",new Date("2026-10-21T20:00:00Z")),"2026-10-22");
  const prepared=prepareSettlementSummary(queue,"2026-10-01","2026-10-02",clientId,now),body=JSON.parse(prepared.body);
  assert.equal(Object.isFrozen(prepared),true);assert.equal(body.from,"2026-09-30T18:30:00.000Z");assert.equal(body.until,"2026-10-02T18:30:00.000Z");assert.equal(body.schoolTimeZone,"Asia/Kolkata");assert.equal(body.clientRequestId,clientId);assert.equal(body.sourceSummaryReadId,null);
  const today=JSON.parse(prepareSettlementSummary(queue,"2026-10-22","2026-10-22",clientId,now).body);assert.equal(today.from,"2026-10-21T18:30:00.000Z");assert.equal(today.until,now.toISOString());
});

test("31-day, invalid, future, missing authority, skipped and repeated midnight inputs are not silently repaired",()=>{
  const now=new Date("2026-11-03T12:00:00Z"),utc={...queue,schoolTimeZone:"UTC"};
  assert.equal(JSON.parse(prepareSettlementSummary(utc,"2026-10-01","2026-10-31",clientId,now).body).until,"2026-11-01T00:00:00.000Z");
  for(const [from,until]of[["2026-10-01","2026-11-01"],["2026-02-30","2026-03-01"],["2026-11-04","2026-11-04"],["2026-10-02","2026-10-01"],["","2026-10-01"]])assert.throws(()=>prepareSettlementSummary(utc,from,until,clientId,now));
  assert.throws(()=>prepareSettlementSummary({...queue,canSync:false},"2026-10-01","2026-10-02",clientId,now));
  assert.throws(()=>prepareSettlementSummary({...queue,schoolTimeZone:"Pacific/Apia"},"2011-12-30","2011-12-30",clientId,new Date("2012-01-03T00:00:00Z")));
  assert.throws(()=>prepareSettlementSummary({...queue,schoolTimeZone:"America/Havana"},"2020-11-01","2020-11-01",clientId,new Date("2020-11-03T00:00:00Z")));
});

test("school-zone daylight-saving days preserve their actual duration",()=>{
  const ny={...queue,schoolTimeZone:"America/New_York"},now=new Date("2026-11-03T12:00:00Z");
  for(const [day,hours]of[["2026-03-08",23],["2026-11-01",25]]){const body=JSON.parse(prepareSettlementSummary(ny,day,day,clientId,now).body);assert.equal(Date.parse(body.until)-Date.parse(body.from),hours*3600000);}
});

test("on-demand event request freezes the exact retained summary identity and statement with no browser provider binding",()=>{
  const prepared=prepareSettlementEvents(queue,row,clientId),body=JSON.parse(prepared.body);
  assert.equal(prepared.kind,"EVENTS");assert.equal(body.sourceSummaryReadId,readId);assert.equal(body.settlementReference,summary.settlementReference);assert.equal(body.from,null);assert.equal(body.until,null);assert.equal(body.schoolTimeZone,queue.schoolTimeZone);assert.equal(Object.isFrozen(prepared),true);
  assert.doesNotMatch(prepared.body,/cursor|credentials|merchant|provider|card|upi|bank/i);
  for(const changed of[{sourceReadId:"invalid"},{summary:null},{event},{rowIndex:10}])assert.throws(()=>prepareSettlementEvents(queue,{...row,...changed},clientId));
});

test("reads use authenticated no-store scoped requests, literal query encoding and their AbortSignal",async()=>{
  const original=globalThis.fetch,calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(url.includes("/requests/")?report:queue);};
  try {const signal=new AbortController().signal;await loadSettlementQueue(filter,signal);await loadSettlementReport(school,requestId,"",0,signal);for(const call of calls){assert.equal(call.init.credentials,"include");assert.equal(call.init.cache,"no-store");assert.equal(call.init.signal,signal);}assert.match(calls[0].url,/status=ALL&kind=ALL&page=0&pageSize=25/);assert.match(calls[1].url,new RegExp(requestId));}
  finally {globalThis.fetch=original;}
});

test("failed reads expose only controlled localized codes and never echo provider or database bodies",async()=>{
  const original=globalThis.fetch;
  try {for(const [status,code]of[[401,"session"],[403,"scope"],[400,"filters"],[500,"unavailable"]]){globalThis.fetch=async()=>Response.json({detail:"synthetic_private_body"},{status});await assert.rejects(loadSettlementQueue(filter,new AbortController().signal),error=>settlementErrorCode(error)===code&&!error.message.includes("private"));}assert.equal(settlementErrorCode(new Error("synthetic_private")),"unavailable");}
  finally {globalThis.fetch=original;}
});

test("write retries preserve exact frozen key/body and use only the session plus CSRF token",async()=>{
  const original=globalThis.fetch,calls=[],prepared=prepareSettlementEvents(queue,row,clientId);
  globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json({id:requestId,schoolId:school,kind:"EVENTS"});};
  try {assert.deepEqual(await sendSettlementSync(prepared,"synthetic_csrf"),{id:requestId,schoolId:school,kind:"EVENTS"});await sendSettlementSync(prepared,"synthetic_csrf");assert.equal(calls[0].init.body,calls[1].init.body);for(const call of calls){assert.equal(call.init.body,prepared.body);assert.equal(call.init.credentials,"include");assert.equal(call.init.cache,"no-store");assert.equal(call.init.method,"POST");assert.equal(call.init.headers["X-CSRF-TOKEN"],"synthetic_csrf");}}
  finally {globalThis.fetch=original;}
});

test("forged, noncanonical and missing-CSRF commands fail before network I/O",async()=>{
  const original=globalThis.fetch;let calls=0;globalThis.fetch=async()=>{calls++;throw new Error("Must not call");};const prepared=prepareSettlementEvents(queue,row,clientId);
  try {for(const changed of[{body:prepared.body.replace(readId,"invalid")},{body:" "+prepared.body},{schoolId:requestId},{kind:"SUMMARY"},{body:prepared.body.replace("\"EVENTS\"","\"EVENTS\",\"cursor\":\"private\"")}])await assert.rejects(sendSettlementSync({...prepared,...changed},"synthetic_csrf"),error=>error instanceof SettlementWriteError&&error.outcome==="rejected");await assert.rejects(sendSettlementSync(prepared,""),error=>error.outcome==="rejected"&&error.code==="csrf");assert.equal(calls,0);}
  finally {globalThis.fetch=original;}
});

test("network, timeout, 5xx and malformed success remain UNKNOWN while explicit rejection is controlled",async()=>{
  const original=globalThis.fetch,prepared=prepareSettlementEvents(queue,row,clientId);
  try {for(const response of[()=>Promise.reject(new Error("private")),()=>new Response("private",{status:503}),()=>new Response("private",{status:408}),()=>Response.json({id:requestId,schoolId:requestId,kind:"EVENTS"}),()=>Response.json({id:requestId,schoolId:school,kind:"SUMMARY"}),()=>Response.json({id:requestId,schoolId:school,kind:"EVENTS",cursor:"private"}),()=>new Response("invalid",{status:200})]){globalThis.fetch=async()=>response();await assert.rejects(sendSettlementSync(prepared,"synthetic_csrf"),error=>error instanceof SettlementWriteError&&error.outcome==="unknown"&&error.code==="unknown");}globalThis.fetch=async()=>Response.json({detail:"private"},{status:409});await assert.rejects(sendSettlementSync(prepared,"synthetic_csrf"),error=>error.outcome==="rejected"&&error.code==="conflict");}
  finally {globalThis.fetch=original;}
});
