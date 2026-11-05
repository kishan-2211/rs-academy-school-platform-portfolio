import assert from "node:assert/strict";
import test from "node:test";
import * as posting from "./gateway-refund-postings.ts";
const schoolId="7a000000-0000-4000-8000-000000000001",refundId="7a000000-0000-4000-8000-000000000002",paymentId="7a000000-0000-4000-8000-000000000003",accountId="7a000000-0000-4000-8000-000000000004",key="7a000000-0000-4000-8000-000000000005",yearId="7a000000-0000-4000-8000-000000000006";
const context={schoolId,refundId,paymentId,receiptNumber:"SYN-REF-01",accountId,learnerName:"Synthetic learner",academicSessionName:"Synthetic historical year",accountStatus:"ACTIVE",currencyCode:"INR",balance:800,verifiedRefundAmount:50.25,sourceState:"READY",processedAtExact:"2026-10-18T03:30:00+05:30",processedOn:"2026-10-18",schoolTimeZone:"Asia/Kolkata",originalDayClosed:true,canPost:true,posting:null};
const year={id:yearId,name:"Synthetic open book",startsOn:"2026-04-01",endsOn:"2027-03-31",currencyCode:"INR",status:"OPEN",periods:[{startsOn:"2026-10-01",endsOn:"2026-10-31",status:"OPEN"}]};
const record={id:key,refundId,paymentId,accountId,amount:50.25,currencyCode:"INR",postingOn:"2026-10-20",postedAt:"2026-10-20T05:00:00Z",ledgerEntryId:key,accountingBatchId:key,journalId:key,journalStatus:"SUBMITTED"};
const attempt=()=>posting.prepareRefundPosting(context,[year],yearId,"2026-10-20","Synthetic reviewed posting",key,"2026-10-20");
test("verified unposted amount and actual signed balance remain separate; contradictory source/permission is rejected",()=>{
 assert.equal(posting.readRefundPostingContext(context,schoolId,refundId),context);assert.equal(posting.refundPostingPaise(-0.01),-1);assert.equal(posting.refundPostingPaise(50.25),5025);
 for(const change of[{schoolId:key},{refundId:key},{balance:1.001},{verifiedRefundAmount:-1},{processedOn:"2026-02-30"},{sourceState:"POSTED"},{accountStatus:"CLOSED"},{sourceState:"NO_VERIFIED_SUCCESS"}])assert.throws(()=>posting.readRefundPostingContext({...context,...change},schoolId,refundId));
 const missing={...context,canPost:false,sourceState:"NO_VERIFIED_SUCCESS",verifiedRefundAmount:null,processedAtExact:null,processedOn:null,schoolTimeZone:null};assert.equal(posting.readRefundPostingContext(missing,schoolId,refundId),missing);
});
test("retained posting requires actual original identity exact amount and explicit independent journal state",()=>{
 const retained={...context,canPost:false,sourceState:"POSTED",balance:850.25,posting:record};assert.equal(posting.readRefundPostingContext(retained,schoolId,refundId),retained);
 for(const journalStatus of["SUBMITTED","POSTED","REJECTED","REVERSED","DRAFT"])assert.equal(posting.readRefundPosting({...record,journalStatus},record).journalStatus,journalStatus);
 for(const change of[{refundId:key},{accountId:key},{amount:50.24},{currencyCode:"USD"},{journalStatus:"SUCCESS"},{journalId:null},{postingOn:"2026-02-30"}])assert.throws(()=>posting.readRefundPosting({...record,...change},record));
});
test("open year/date/reason selection freezes exact terms without provider credentials or mutable money fields",()=>{
 const prepared=attempt();assert.equal(Object.isFrozen(prepared),true);assert.deepEqual(JSON.parse(prepared.body),{schoolId,clientRequestId:key,financialYearId:yearId,postingOn:"2026-10-20",reason:"Synthetic reviewed posting"});assert.doesNotMatch(prepared.body,/amount|balance|card|pin|provider|phone/i);
 for(const [years,date,reason,today]of[[[{...year,status:"CLOSED"}],"2026-10-20","Synthetic review","2026-10-20"],[[{...year,currencyCode:"USD"}],"2026-10-20","Synthetic review","2026-10-20"],[[{...year,periods:[{...year.periods[0],status:"LOCKED"}]}],"2026-10-20","Synthetic review","2026-10-20"],[[year],"2026-10-17","Synthetic review","2026-10-20"],[[year],"2026-10-21","Synthetic review","2026-10-20"],[[year],"2026-10-20","Synthetic\nreview","2026-10-20"],[[year],"2026-10-20","x","2026-10-20"]])assert.throws(()=>posting.prepareRefundPosting(context,years,yearId,date,reason,key,today));
 assert.equal(posting.refundSchoolToday("Asia/Kolkata",new Date("2026-10-19T20:00:00Z")),"2026-10-20");
});
test("private reads use exact school/refund sessions no-store and validate before displaying; book options discard unrelated financial data",async()=>{
 const original=globalThis.fetch,calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(url.includes("/accounting?")?{schoolId,financialYears:[year,{...year,id:key,status:"CLOSED"}],journals:[{private:"synthetic unused detail"}]}:context);};
 try{const signal=new AbortController().signal;await posting.loadRefundPosting(schoolId,refundId,signal);const options=await posting.loadRefundPostingYears(schoolId,signal);assert.deepEqual(options,[year]);for(const {init}of calls){assert.equal(init.signal,signal);assert.equal(init.credentials,"include");assert.equal(init.cache,"no-store");}assert.match(calls[0].url,/\/posting\?schoolId=/);assert.match(calls[1].url,/\/accounting\?schoolId=/);}
 finally{globalThis.fetch=original;}
});
test("book read failures preserve controlled copy without private diagnostics and never fabricate an option",async()=>{
 const original=globalThis.fetch;
 try{for(const [status,message]of[[401,posting.refundPostingErrors.session],[403,posting.refundPostingErrors.books],[500,posting.refundPostingErrors.books]]){globalThis.fetch=async()=>Response.json({detail:"SYNTHETIC PRIVATE"},{status});await assert.rejects(posting.loadRefundPostingYears(schoolId,new AbortController().signal),{message});}globalThis.fetch=async()=>Response.json({schoolId:key,financialYears:[year]});await assert.rejects(posting.loadRefundPostingYears(schoolId,new AbortController().signal));assert.equal(posting.refundPostingError(new Error("SYNTHETIC PRIVATE")),posting.refundPostingErrors.unavailable);}
 finally{globalThis.fetch=original;}
});
test("exact retries retain body/UUID and validate actual acknowledged ledger result rather than browser success",async()=>{
 const original=globalThis.fetch,calls=[],prepared=attempt();globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(record);};
 try{await posting.sendRefundPosting(prepared,"synthetic-csrf");await posting.sendRefundPosting(prepared,"synthetic-csrf");assert.deepEqual(calls.map(x=>x.init.body),[prepared.body,prepared.body]);for(const {init}of calls){assert.equal(init.method,"POST");assert.equal(init.credentials,"include");assert.equal(init.cache,"no-store");assert.equal(init.headers["X-CSRF-TOKEN"],"synthetic-csrf");}}
 finally{globalThis.fetch=original;}
});
test("lost response 408/5xx malformed or forged success stays unknown; fresh definite rejection is distinguishable",async()=>{
 const original=globalThis.fetch,prepared=attempt();
 try{for(const factory of[()=>Promise.reject(new Error("private")),()=>new Response("private",{status:503}),()=>new Response("private",{status:408}),()=>new Response("not-json"),()=>Response.json({...record,amount:50.24}),()=>Response.json({...record,refundId:key})]){globalThis.fetch=async()=>factory();await assert.rejects(posting.sendRefundPosting(prepared,"synthetic-csrf"),e=>e instanceof posting.RefundPostingWriteError&&e.outcome==="unknown"&&e.message===posting.refundPostingErrors.unknown);}for(const [status,message]of[[401,posting.refundPostingErrors.session],[403,posting.refundPostingErrors.scope],[409,posting.refundPostingErrors.rejected]]){globalThis.fetch=async()=>Response.json({detail:"SYNTHETIC PRIVATE"},{status});await assert.rejects(posting.sendRefundPosting(prepared,"synthetic-csrf"),e=>e.outcome==="rejected"&&e.message===message);}await assert.rejects(posting.sendRefundPosting(prepared,""),e=>e.outcome==="rejected"&&e.message===posting.refundPostingErrors.csrf);}
 finally{globalThis.fetch=original;}
});
