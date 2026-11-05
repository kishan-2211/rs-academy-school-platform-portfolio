import assert from "node:assert/strict";
import test from "node:test";
import {prepareRefundJournalRepair,readRefundJournalRepair,readRefundPostingContext,sendRefundJournalRepair,RefundPostingWriteError,refundPostingErrors} from "./gateway-refund-postings.ts";
const id=n=>`79000000-0000-4000-8000-${String(n).padStart(12,"0")}`;
const posting={id:id(1),refundId:id(2),paymentId:id(3),accountId:id(4),amount:50.25,currencyCode:"INR",postingOn:"2026-09-30",postedAt:"2026-10-01T04:00:00Z",ledgerEntryId:id(5),accountingBatchId:id(6),journalId:id(7),journalStatus:"REJECTED",journalPostingOn:"2026-10-02",journalFinancialYearId:id(8)};
const context={schoolId:id(9),refundId:id(2),paymentId:id(3),accountId:id(4),receiptNumber:"SYN-REPAIR-01",learnerName:"Synthetic learner",academicSessionName:"Synthetic historical session",accountStatus:"CLOSED",currencyCode:"INR",balance:850.25,verifiedRefundAmount:50.25,sourceState:"POSTED",processedAtExact:"2026-09-30T04:00:00.123456789Z",processedOn:"2026-09-30",schoolTimeZone:"Asia/Kolkata",originalDayClosed:true,canPost:false,posting,canRepairJournal:true};
const year={id:id(8),name:"Synthetic open book",startsOn:"2026-04-01",endsOn:"2027-03-31",currencyCode:"INR",status:"OPEN",periods:[{startsOn:"2026-09-01",endsOn:"2026-09-30",status:"CLOSED"},{startsOn:"2026-10-01",endsOn:"2026-10-31",status:"OPEN"}]};
const prepare=(source=context,years=[year],date="2026-10-21",reason="Synthetic reviewed accounting-only repair")=>prepareRefundJournalRepair(source,years,year.id,date,reason,id(10),"2026-10-21");
const response=()=>({refundPostingId:posting.id,refundId:context.refundId,previousBatchId:posting.accountingBatchId,previousJournalId:posting.journalId,accountingBatchId:id(11),journalId:id(12),journalStatus:"SUBMITTED",financialYearId:year.id,postingOn:"2026-10-21"});

test("repair freezes exact predecessor and open target without accepting amount or provider instructions",()=>{
 const command=prepare();assert.equal(Object.isFrozen(command),true);assert.equal(command.kind,"JOURNAL_REPAIR");
 assert.equal(command.path,`/api/v1/finance/gateway/refunds/${context.refundId}/posting/journal-repairs`);
 assert.deepEqual(Object.keys(JSON.parse(command.body)),["schoolId","clientRequestId","expectedBatchId","expectedJournalId","financialYearId","postingOn","reason"]);
 assert.equal(JSON.parse(command.body).expectedBatchId,posting.accountingBatchId);assert.equal(JSON.parse(command.body).expectedJournalId,posting.journalId);
 assert.equal(command.amount,50.25);assert.equal(command.refundPostingId,posting.id);assert.doesNotMatch(command.body,/amount|provider|receipt|ledger|balance/i);
});
test("accounting-only correction permits a closed original fee account without reopening or altering dues",()=>{
 assert.equal(prepare().accountId,context.accountId);assert.equal(context.accountStatus,"CLOSED");assert.equal(context.balance,850.25);
 assert.equal(prepare({...context,posting:{...posting,journalStatus:"REVERSED"}}).expectedJournalId,posting.journalId);
});
test("repair capability is explicit and only completed rejected or reversed journals are eligible",()=>{
 for(const changes of[{canRepairJournal:false},{canRepairJournal:undefined},{posting:{...posting,journalStatus:"SUBMITTED"}},{posting:{...posting,journalStatus:"POSTED"}},{posting:{...posting,accountingBatchId:null}},{sourceState:"READY",posting:null}])assert.throws(()=>prepare({...context,...changes}));
});
test("open-date reason currency and exact historical lower bounds fail closed",()=>{
 for(const date of["2026-09-30","2026-10-01","2026-10-22","2026-02-30","not-a-date"])assert.throws(()=>prepare(context,[year],date));
 for(const reason of["x"," ","x".repeat(501),"Synthetic\nreview"])assert.throws(()=>prepare(context,[year],"2026-10-21",reason));
 for(const changed of[{status:"CLOSED"},{currencyCode:"USD"},{periods:[{startsOn:"2026-10-01",endsOn:"2026-10-31",status:"LOCKED"}]}])assert.throws(()=>prepare(context,[{...year,...changed}]));
});
test("acknowledgement must prove the same refund predecessor target date and a genuinely different journal",()=>{
 const command=prepare();assert.deepEqual(readRefundJournalRepair(response(),command),response());
 for(const changes of[{refundPostingId:id(20)},{refundId:id(20)},{previousBatchId:id(20)},{previousJournalId:id(20)},{accountingBatchId:posting.accountingBatchId},{journalId:posting.journalId},{financialYearId:id(20)},{postingOn:"2026-10-20"},{journalStatus:"DRAFT"}])assert.throws(()=>readRefundJournalRepair({...response(),...changes},command));
});
test("send uses private no-store session CSRF and the exact immutable body for a retry",async()=>{
 const original=globalThis.fetch,calls=[];const command=prepare();
 try {globalThis.fetch=async(url,options)=>{calls.push({url,options});return new Response(JSON.stringify(response()),{status:200});};
  await sendRefundJournalRepair(command,"synthetic-csrf");await sendRefundJournalRepair(command,"synthetic-csrf");
  assert.equal(calls.length,2);for(const {options} of calls){assert.equal(options.body,command.body);assert.equal(options.credentials,"include");assert.equal(options.cache,"no-store");assert.equal(options.headers["X-CSRF-TOKEN"],"synthetic-csrf");}
 } finally {globalThis.fetch=original;}
});
test("network server malformed success and bad acknowledgement remain unknown, never fabricated success",async()=>{
 const original=globalThis.fetch,command=prepare();
 try {for(const run of[async()=>{throw new Error("Synthetic disconnected browser");},async()=>new Response("{}",{status:503}),async()=>new Response("invalid-json",{status:200}),async()=>new Response(JSON.stringify({...response(),previousJournalId:id(30)}),{status:200})]){
   globalThis.fetch=run;await assert.rejects(()=>sendRefundJournalRepair(command,"synthetic-csrf"),e=>e instanceof RefundPostingWriteError && e.outcome==="unknown" && e.message===refundPostingErrors.repairUnknown);
  }
  for(const status of[401,403,409]){globalThis.fetch=async()=>new Response("private body must not be echoed",{status});await assert.rejects(()=>sendRefundJournalRepair(command,"synthetic-csrf"),e=>e.outcome==="rejected" && !e.message.includes("private body"));}
  let calls=0;globalThis.fetch=async()=>{calls++;return new Response("{}");};await assert.rejects(()=>sendRefundJournalRepair(command,""));assert.equal(calls,0);
 } finally {globalThis.fetch=original;}
});
test("current journal dates cannot overwrite or precede the retained economic refund date",()=>{
 assert.equal(readRefundPostingContext(context,context.schoolId,context.refundId).posting.postingOn,"2026-09-30");
 for(const changes of[{journalPostingOn:"2026-09-29"},{journalPostingOn:"not-a-date"},{journalFinancialYearId:"not-a-year"}])assert.throws(()=>readRefundPostingContext({...context,posting:{...posting,...changes}},context.schoolId,context.refundId));
});
