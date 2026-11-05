import assert from "node:assert/strict";
import test from "node:test";
import { loadSettlementSourceReview, readSettlementSourceReview, SettlementSourceReviewError, settlementSourceReviewErrorCode, settlementSourceReviewReasons } from "./gateway-settlement-source-review.ts";

// All identities and evidence are synthetic. This is not provider/browser acceptance.
const school="8c000000-0000-4000-8000-000000000001",requestId="8c000000-0000-4000-8000-000000000002",readId="8c000000-0000-4000-8000-000000000003",other="8c000000-0000-4000-8000-000000000004",reference="synthetic_statement",at="2026-10-23T04:17:39.123456789Z";
const event={settlementReference:reference,eventReference:"synthetic_event",reportedType:"PAYMENT",reportedDirection:"CREDIT",reportedStatus:"SUCCESS",currencyCode:"INR",reportedAmount:"90071992.54740000",reportedServiceCharge:"1.12345678",reportedServiceTax:null,reportedSettlementAmount:null,occurredAt:at,orderReference:"synthetic_order",paymentReference:"synthetic_payment",refundReferences:[],chargesCurrencyCode:null};
const source={captureId:other,paymentId:other,feeLedgerEntryId:other,receiptNumber:"SYN-SOURCE-001",postingItemId:other,postingBatchId:other,configurationId:other,accountingJournalId:other,clearingAccountId:other,clearingAccountCategory:"OTHER",originalFinancialYearId:other,originalAccountingPeriodId:other,originalPostingDate:"2026-10-23",accountingBasis:"ACCRUAL",currencyCode:"INR",grossAmount:"90071992.54"};
const matchedEvent={...event,reportedAmount:"90071992.54000000"};
const row={sourceReadId:readId,rowIndex:0,observedAt:at,event:matchedEvent,status:"SOURCE_IDENTIFIED",reasons:[],source};
const review={schoolId:school,requestId,settlementReference:reference,reviewStage:"SOURCE_IDENTIFICATION_ONLY",pagesRead:1,totalRows:1,sourceIdentifiedRows:1,reviewRequiredRows:0,page:0,pageSize:25,hasMore:false,items:[row]};
const decode=value=>readSettlementSourceReview(value,school,requestId,reference,0);
const fails=value=>assert.throws(()=>decode(value),error=>error instanceof SettlementSourceReviewError&&error.code==="unavailable");
const reviewRow=changes=>({...review,items:[{...row,...changes}]});

test("complete exact source identity retains gross paise strings and all historical evidence",()=>{
  assert.equal(decode(review),review);assert.equal(decode(review).items[0].source.grossAmount,"90071992.54");assert.equal(decode(review).reviewStage,"SOURCE_IDENTIFICATION_ONLY");
  for(const accountingBasis of["CASH","ACCRUAL"])assert.equal(decode(reviewRow({source:{...source,accountingBasis}})).items[0].source.accountingBasis,accountingBasis);
});
test("all closed source review reasons require explicit null source and retain provider unknowns",()=>{
  for(const reason of settlementSourceReviewReasons){const value={...review,sourceIdentifiedRows:0,reviewRequiredRows:1,items:[{...row,event:{...event,reportedType:"NEW_EVENT",reportedStatus:"NEW_STATUS",currencyCode:"USD"},status:"REVIEW_REQUIRED",reasons:[reason],source:null}]};assert.equal(decode(value).items[0].reasons[0],reason);}
  for(const changes of[{status:"REVIEW_REQUIRED",source:null},{status:"REVIEW_REQUIRED",reasons:["GROSS_MISMATCH"]},{reasons:["GROSS_MISMATCH"]},{source:null},{status:"READY_TO_POST"},{status:"REVIEW_REQUIRED",source:null,reasons:["UNKNOWN_REASON"]},{status:"REVIEW_REQUIRED",source:null,reasons:["GROSS_MISMATCH","GROSS_MISMATCH"]}])fails(reviewRow(changes));
});
test("school request statement and immutable stage mismatch fail closed",()=>{
  for(const changes of[{schoolId:other},{requestId:other},{settlementReference:"synthetic_other"},{reviewStage:"BANK_RECONCILED"}])fails({...review,...changes});
  fails(reviewRow({event:{...matchedEvent,settlementReference:"synthetic_other"}}));
});
test("bounded full-chain counts and pagination cannot claim unsupported totals",()=>{
  for(const changes of[{pagesRead:0},{pagesRead:1001},{pagesRead:1.5},{totalRows:10001},{totalRows:11,sourceIdentifiedRows:11},{sourceIdentifiedRows:0},{reviewRequiredRows:1},{totalRows:"1"},{page:1},{pageSize:50},{hasMore:true},{items:[]},{totalRows:0,sourceIdentifiedRows:0,items:[row]}])fails({...review,...changes});
  assert.equal(decode({...review,totalRows:0,sourceIdentifiedRows:0,items:[]}).totalRows,0);
});
test("physical source identities prevent duplicate rows but do not merge repeated provider events",()=>{
  fails({...review,totalRows:2,sourceIdentifiedRows:2,items:[row,row]});
  const repeated={...review,totalRows:2,sourceIdentifiedRows:0,reviewRequiredRows:2,items:[0,1].map(rowIndex=>({...row,rowIndex,status:"REVIEW_REQUIRED",reasons:["DUPLICATE_PAYMENT_SOURCE"],source:null}))};
  assert.equal(decode(repeated).items.length,2);
  for(const changes of[{rowIndex:10},{rowIndex:-1},{rowIndex:0.1},{sourceReadId:"invalid"}])fails(reviewRow(changes));
});
test("exact event chronology preserves nanoseconds and rejects invalid dates",()=>{
  for(const changes of[{observedAt:"2026-02-30T00:00:00Z"},{observedAt:"2026-10-23T24:00:00Z"},{observedAt:"2026-10-23T04:17:39.123456788Z"},{event:{...matchedEvent,occurredAt:"2026-10-23T04:17:39.123456790Z"}}])fails(reviewRow(changes));
  assert.equal(decode(review).items[0].observedAt,at);
});
test("source fields cannot downgrade OTHER clearing or replace historical chain identities",()=>{
  for(const key of["captureId","paymentId","feeLedgerEntryId","postingItemId","postingBatchId","configurationId","accountingJournalId","clearingAccountId","originalFinancialYearId","originalAccountingPeriodId"])for(const invalid of[null,"invalid"])fails(reviewRow({source:{...source,[key]:invalid}}));
  for(const changes of[{clearingAccountCategory:"BANK"},{currencyCode:"USD"},{accountingBasis:"UNKNOWN"},{originalPostingDate:"2026-02-30"},{receiptNumber:""},{receiptNumber:"Synthetic\nreceipt"},{receiptNumber:"x".repeat(101)}])fails(reviewRow({source:{...source,...changes}}));
});
test("source gross and provider precision are compared exactly without floating point",()=>{
  for(const grossAmount of[90071992.54,"0.00","-1.00","01.00","1.0","1.001","1e2","100000000.01",null])fails(reviewRow({source:{...source,grossAmount}}));
  for(const reportedAmount of[90071992.54,"90071992.54000001","90071992.54740000","-90071992.54000000","0","1e8","100000000.00000001"])fails(reviewRow({event:{...matchedEvent,reportedAmount}}));
  assert.equal(decode(reviewRow({event:{...matchedEvent,reportedAmount:"90071992.54"}})).items[0].source.grossAmount,"90071992.54");
  for(const changes of[{reportedType:"REFUND"},{reportedDirection:"DEBIT"},{reportedStatus:"NEW_STATUS"},{orderReference:null},{paymentReference:null},{currencyCode:"USD"}])fails(reviewRow({event:{...matchedEvent,...changes}}));
});
test("private additional properties and malformed nullable event fields are rejected",()=>{
  for(const key of["credentials","cursor","rawProviderBody","bankAccount"]){fails({...review,[key]:"synthetic_private"});fails(reviewRow({[key]:"synthetic_private"}));fails(reviewRow({source:{...source,[key]:"synthetic_private"}}));fails(reviewRow({event:{...matchedEvent,[key]:"synthetic_private"}}));}
  for(const changes of[{refundReferences:null},{refundReferences:Array(101).fill("synthetic_refund")},{chargesCurrencyCode:"inr"},{orderReference:"contains space"},{reportedAmount:null}])fails(reviewRow({event:{...matchedEvent,...changes}}));
});
test("paginated rows keep complete-chain counters and use literal fixed authenticated GET",async()=>{
  const items=Array.from({length:25},(_,index)=>({...row,sourceReadId:`8c000000-0000-4000-8000-${String(100+index).padStart(12,"0")}`}));
  const first={...review,pagesRead:3,totalRows:26,sourceIdentifiedRows:26,hasMore:true,items};assert.equal(decode(first).items.length,25);
  const last={...review,pagesRead:3,totalRows:26,sourceIdentifiedRows:26,page:1,items:[row]};assert.equal(readSettlementSourceReview(last,school,requestId,reference,1).page,1);
  const original=globalThis.fetch,calls=[],signal=new AbortController().signal;globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(last);};
  try {await loadSettlementSourceReview(school,requestId,reference,1,signal);assert.match(calls[0].url,new RegExp(`/requests/${requestId}/source-review\\?schoolId=${school}&page=1&pageSize=25$`));assert.equal(calls[0].init.credentials,"include");assert.equal(calls[0].init.cache,"no-store");assert.equal(calls[0].init.signal,signal);assert.equal(calls[0].init.method,undefined);assert.equal(calls[0].init.body,undefined);}
  finally {globalThis.fetch=original;}
});
test("invalid request scope never makes a network call",async()=>{
  const original=globalThis.fetch;let calls=0;globalThis.fetch=async()=>{calls++;return Response.json(review);};
  try {for(const args of[["invalid",requestId,reference,0],[school,"invalid",reference,0],[school,requestId,"bad reference",0],[school,requestId,reference,-1],[school,requestId,reference,10001]])await assert.rejects(loadSettlementSourceReview(...args,new AbortController().signal),error=>error.code==="filters");assert.equal(calls,0);}
  finally {globalThis.fetch=original;}
});
test("HTTP denial incomplete chain and malformed success expose controlled errors not private bodies",async()=>{
  const original=globalThis.fetch;
  try {for(const [status,code]of[[401,"session"],[403,"scope"],[409,"incomplete"],[400,"filters"],[404,"unavailable"],[503,"unavailable"]]){globalThis.fetch=async()=>Response.json({detail:"synthetic_private"},{status});await assert.rejects(loadSettlementSourceReview(school,requestId,reference,0,new AbortController().signal),error=>settlementSourceReviewErrorCode(error)===code&&!error.message.includes("private"));}globalThis.fetch=async()=>new Response("invalid");await assert.rejects(loadSettlementSourceReview(school,requestId,reference,0,new AbortController().signal),error=>error.code==="unavailable");assert.equal(settlementSourceReviewErrorCode(new Error("synthetic_private")),"unavailable");}
  finally {globalThis.fetch=original;}
});
