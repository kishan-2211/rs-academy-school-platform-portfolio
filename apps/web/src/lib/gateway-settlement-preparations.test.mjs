import assert from "node:assert/strict";
import test from "node:test";
import * as client from "./gateway-settlement-preparations.ts";
import {id,schoolId,requestId,settlementReference,context,draft,preparation,detail,clone,association} from "./gateway-settlement-preparation-fixtures.mjs";

const read=value=>client.readSettlementPreparationContext(value,schoolId,requestId,settlementReference,0);
const failure=(action,code)=>assert.throws(action,error=>error instanceof client.SettlementPreparationError&&(!code||error.code===code));
const command=(ctx=context,value=draft)=>client.prepareSettlementJournal(ctx,value,preparation.clientRequestId);
test("complete context retains exact whole-statement amounts physical rows and unknown facts without mutation",()=>{
  const before=JSON.stringify(context);assert.equal(read(context),context);assert.equal(JSON.stringify(context),before);
  const unknown=clone(context);unknown.componentFacts[2].summaryAmount=null;unknown.summary.reportedAdjustment=null;assert.equal(read(unknown).componentFacts[2].summaryAmount,null);
  assert.equal(client.componentReportedAmount({...context.componentFacts[0],eventsComplete:false},"EVENTS"),null);
  assert.equal(client.componentReportedAmount(context.componentFacts[2],"EVENTS"),"0.00000000");
});
test("context denies wrong scope stale paging extra fields duplicate identities and impossible source projections",()=>{
  const defects=[
    value=>{value.schoolId=id(90);},value=>{value.requestId=id(90);},value=>{value.settlementReference="SYN_OTHER";},value=>{value.page=1;},value=>{value.pageSize=50;},value=>{value.hasMore=true;},value=>{value.extra=null;},value=>{delete value.contextFingerprint;},value=>{value.paymentTotal=null;},
    value=>{value.rows[1].sourceReadId=value.rows[0].sourceReadId;value.rows[1].rowIndex=0;},value=>{value.bankEvidence.push(value.bankEvidence[0]);},value=>{value.accountingOptions.accounts.push(value.accountingOptions.accounts[0]);},value=>{value.componentFacts[7].key=value.componentFacts[0].key;},
    value=>{value.rows[0].source.clearingAccountCategory="BANK";},value=>{value.rows[0].source.grossAmount="99.99";},value=>{value.rows[0].event.currencyCode="USD";},value=>{value.rows[0].status="POSTED";},value=>{value.rows[0].reasons=["UNEXPECTED"];},value=>{value.rows[0].source=null;}
  ];
  for(const change of defects){const value=clone(context);change(value);failure(()=>read(value),"unavailable");}
});
test("unresolved full-statement totals remain unknown and cannot be converted into a preparation",()=>{
  const value=clone(context);value.paymentTotal=null;value.refundTotal=null;value.blockers=["SOURCE_REVIEW_REQUIRED","NEW_REVIEW_CODE"];assert.equal(read(value).refundTotal,null);
  failure(()=>command(value),"incomplete");
});
test("preparation decoder checks exact sums lineage live journal state and no fabricated voucher",()=>{
  assert.equal(client.readSettlementPreparation(preparation,requestId),preparation);
  for(const mutate of[value=>{value.bankAmount="90.01";},value=>{value.clientRequestId="bad";},value=>{value.rootId="bad";},value=>{value.revision=0;},value=>{value.previousPreparationId=value.id;},value=>{value.status="SUCCESS";},value=>{value.componentCreditTotal="-1.00";},value=>{value.preparedAt="yesterday";},value=>{value.voucherNumber="";},value=>{value.unknown=null;}]){
    const value=clone(preparation);mutate(value);failure(()=>client.readSettlementPreparation(value,requestId),"unavailable");
  }
});

test("server-created settlement root and first preparation have separate identities across reads",()=>{
  assert.notEqual(preparation.rootId,preparation.id);
  assert.equal(client.readSettlementPreparation(preparation,requestId).rootId,preparation.rootId);
  assert.equal(read({...context,preparations:[preparation]}).preparations[0].id,preparation.id);
  assert.equal(client.readSettlementPreparationDetail(detail,preparation.id,preparation.journalId).preparation.rootId,preparation.rootId);
  for(const value of[{...preparation,revision:2},{...preparation,previousPreparationId:id(90)},{...preparation,revision:2,previousPreparationId:preparation.id}]){
    failure(()=>client.readSettlementPreparation(value,requestId),"unavailable");
  }
  const correction={...preparation,id:id(43),revision:2,previousPreparationId:preparation.id};
  assert.equal(client.readSettlementPreparation(correction,requestId).rootId,preparation.rootId);
});
test("one command freezes reviewed values and all physical refund choices without client-owned journal lines",()=>{
  const before=JSON.stringify({context,draft}),attempt=command(),body=JSON.parse(attempt.body);
  assert.ok(Object.isFrozen(attempt));assert.equal(body.clientRequestId,preparation.clientRequestId);assert.equal(body.componentReviews.length,8);assert.deepEqual(body.refundAssociations,[association]);assert.equal(body.summaryCurrencyCode,"INR");assert.equal(body.previousPreparationId,null);
  assert.deepEqual(Object.keys(body),["schoolId","clientRequestId","contextFingerprint","bankEvidenceId","financialYearId","postingOn","summaryCurrencyCode","confirmedSummaryCurrency","componentBasis","componentReviews","refundAssociations","previousPreparationId","confirmedReviewed","reason"]);
  assert.doesNotMatch(attempt.body,/journalLines|clearingAccount|paymentId|cardNumber|password|credential/);assert.equal(JSON.stringify({context,draft}),before);
});
test("confirmation bank year date component account and reason requirements cannot be skipped",()=>{
  const cases=[["confirmation",value=>{value.confirmedSummaryCurrency=false;}],["confirmation",value=>{value.confirmedReviewed=false;}],["bank",value=>{value.bankEvidenceId=id(98);}],["year",value=>{value.financialYearId=id(98);}],["date",value=>{value.postingOn="2026-02-30";}],["date",value=>{value.postingOn="2028-01-01";}],["components",value=>{value.componentReviews[0].accountId=id(6);}],["components",value=>{value.componentReviews[1].accountId=id(7);}],["components",value=>{value.componentReviews[0].mode="";}],["components",value=>{value.componentReviews[0].evidenceReference="https://synthetic.example.test";}],["components",value=>{value.componentReviews[0].postedAmount="5.001";}],["reason",value=>{value.reason="x";}],["reason",value=>{value.componentReviews[0].reason="x";}]];
  for(const[code,change]of cases){const value=clone(draft);change(value);failure(()=>command(context,value),code);}
  failure(()=>command({...context,canPrepare:false}),"scope");failure(()=>command({...context,blockers:["SOURCE_REVIEW_REQUIRED"]}),"incomplete");
});
test("missing component values require an explicit zero or referenced external review and never become zero automatically",()=>{
  const ctx=clone(context);ctx.componentFacts[1].summaryAmount=null;failure(()=>command(ctx),"components");
  const zero=clone(draft);zero.componentReviews[1].mode="CONFIRMED_ZERO";assert.equal(JSON.parse(command(ctx,zero).body).componentReviews[1].postedAmount,"0.00");
  const external=clone(draft);external.componentReviews[1]={...external.componentReviews[1],mode:"EXTERNAL_REVIEW",postedAmount:"1.00",accountId:id(7),evidenceReference:"SYN_INVOICE_001"};ctx.bankEvidence[0].creditedAmount="89.00";assert.equal(JSON.parse(command(ctx,external).body).componentReviews[1].mode,"EXTERNAL_REVIEW");
  const known=clone(context);failure(()=>command(known,external),"components");
});
test("sub-cent rounding requires explicit document evidence and cannot become a one-paisa balancing plug",()=>{
  const ctx=clone(context);ctx.componentFacts[0].summaryAmount="5.00000001";failure(()=>command(ctx),"components");
  const value=clone(draft);value.componentReviews[0].mode="ROUNDING_REVIEW";assert.equal(JSON.parse(command(ctx,value).body).componentReviews[0].postedAmount,"5.00");
  value.componentReviews[0].evidenceReference="";failure(()=>command(ctx,value),"components");value.componentReviews[0].evidenceReference="SYN_REVIEW";
  ctx.componentFacts[0].summaryAmount="5.01000000";failure(()=>command(ctx,value),"components");
  ctx.componentFacts[0].summaryAmount="5.00000000";failure(()=>command(ctx,value),"components");
});
test("signed non-adjustment charge direction and reviewed equation remain exact",()=>{
  const ctx=clone(context),value=clone(draft);ctx.componentFacts[0].summaryAmount="-5.00000000";ctx.bankEvidence[0].creditedAmount="100.00";
  failure(()=>command(ctx,value),"components");value.componentReviews[0].direction="CREDIT";assert.ok(command(ctx,value));
  ctx.bankEvidence[0].creditedAmount="100.01";failure(()=>command(ctx,value),"components");
});
test("every physical refund requires an explicit unique completed refund and a review reason",()=>{
  failure(()=>command(context,{...draft,refundAssociations:[]}),"refunds");failure(()=>command(context,{...draft,refundAssociations:[{...association,reason:"x"}]}),"reason");
  const ctx={...context,totalRefundEvents:2},value={...draft,refundAssociations:[association,{...association,rowIndex:2}]};failure(()=>command(ctx,value),"refunds");
  failure(()=>command(context,{...draft,refundAssociations:[{...association,rowIndex:10}]}),"refunds");
});
test("repair keeps explicit previous identity and release is limited to rejected preparation",()=>{
  const previous={...preparation,status:"REJECTED"},ctx={...context,preparations:[previous],blockers:["STATEMENT_ALREADY_PREPARED"]};
  failure(()=>command(ctx),"incomplete");assert.equal(JSON.parse(command(ctx,{...draft,previousPreparationId:previous.id}).body).previousPreparationId,previous.id);
  failure(()=>command({...ctx,preparations:[{...previous,status:"POSTED"}]},{...draft,previousPreparationId:previous.id}),"stale");
  assert.equal(client.prepareSettlementAbandonment(schoolId,previous,id(70),"Synthetic release review",true).kind,"ABANDON");
  for(const status of["SUBMITTED","POSTED","REVERSED","ABANDONED"])failure(()=>client.prepareSettlementAbandonment(schoolId,{...previous,status},id(70),"Synthetic release review",true),"confirmation");
});
test("full provenance proves frozen bank timezone component facts amounts and exact source/refund identities",()=>{
  assert.equal(client.readSettlementPreparationDetail(detail,preparation.id,preparation.journalId),detail);
  const changes=[value=>{value.preparation.id=id(90);},value=>{value.preparation.journalId=id(90);},value=>{value.bankEvidence.creditedAmount="91.00";},value=>{value.schoolTimeZone="bad";},value=>{value.componentFacts[0].summaryAmount="5.01";},value=>{value.componentReviews[0].evidenceReference="https://synthetic.example.test";},value=>{value.sources[0].amount="99.99";},value=>{value.sources[1].refundPostingId=id(95);},value=>{value.sources[1].associationReason="Changed synthetic reason";},value=>{value.sources.push(value.sources[0]);},value=>{value.componentReviews[1].key="SERVICE_CHARGE";},value=>{value.refundAssociations=[];},value=>{value.unknown=true;}];
  for(const change of changes){const value=clone(detail);change(value);failure(()=>client.readSettlementPreparationDetail(value,preparation.id,preparation.journalId),"unavailable");}
});
test("private reads use scoped no-store authenticated endpoints and reject invalid paging before network",async()=>{
  const old=globalThis.fetch,requests=[];try{globalThis.fetch=async(url,options)=>{requests.push([url,options]);return new Response(JSON.stringify(url.includes("preparation-context")?context:detail));};
    await client.loadSettlementPreparationContext(schoolId,requestId,settlementReference,0,new AbortController().signal);
    await client.loadSettlementPreparation(schoolId,preparation.id,preparation.journalId,new AbortController().signal);
    assert.match(requests[0][0],/preparation-context\?schoolId=.*page=0&pageSize=25/);assert.match(requests[1][0],/\/preparations\/.*\?schoolId=/);for(const[,options]of requests){assert.equal(options.credentials,"include");assert.equal(options.cache,"no-store");}
    await assert.rejects(()=>client.loadSettlementPreparationContext(schoolId,requestId,settlementReference,-1,new AbortController().signal));assert.equal(requests.length,2);
  }finally{globalThis.fetch=old;}
});
test("write sends frozen CSRF command and verifies same command identity before acknowledging",async()=>{
  const old=globalThis.fetch,attempt=command(),sent=[];try{globalThis.fetch=async(url,options)=>{sent.push([url,options]);return new Response(JSON.stringify(preparation),{status:201});};
    assert.equal((await client.sendSettlementPreparation(attempt,"synthetic_csrf")).id,preparation.id);assert.equal(sent[0][1].body,attempt.body);assert.equal(sent[0][1].headers["X-CSRF-TOKEN"],"synthetic_csrf");assert.equal(sent[0][1].credentials,"include");
    for(const mutate of[value=>{value.clientRequestId=id(91);},value=>{value.requestId=id(92);},value=>{value.postingOn="2026-10-28";}]){const response=clone(preparation);mutate(response);globalThis.fetch=async()=>new Response(JSON.stringify(response));await assert.rejects(()=>client.sendSettlementPreparation(attempt,"synthetic_csrf"),error=>error.outcome==="unknown");}
  }finally{globalThis.fetch=old;}
});

test("same-ID recovery acknowledges a retained first preparation with an independent root",async()=>{
  const old=globalThis.fetch,attempt=command(),sent=[];
  try{
    globalThis.fetch=async(url,options)=>{sent.push([url,options.body]);return new Response(JSON.stringify(preparation),{status:201});};
    const first=await client.sendSettlementPreparation(attempt,"synthetic_csrf");
    const retry=await client.sendSettlementPreparation(attempt,"synthetic_csrf");
    assert.notEqual(first.rootId,first.id);assert.equal(retry.id,first.id);assert.equal(retry.clientRequestId,attempt.clientRequestId);
    assert.deepEqual(sent[1],sent[0]);assert.equal(attempt.body,sent[0][1]);
  }finally{globalThis.fetch=old;}
});
test("malformed or forged command never leaves the browser and unknown transport/server outcomes stay unknown",async()=>{
  const old=globalThis.fetch;let count=0;try{globalThis.fetch=async()=>{count++;return new Response("{}",{status:201});};const attempt=command(),body={...JSON.parse(attempt.body),journalLines:[]};
    await assert.rejects(()=>client.sendSettlementPreparation({...attempt,body:JSON.stringify(body)},"synthetic_csrf"),error=>error.outcome==="rejected");assert.equal(count,0);
    await assert.rejects(()=>client.sendSettlementPreparation(attempt,""),error=>error.outcome==="rejected");assert.equal(count,0);
    for(const status of[500,408,429]){globalThis.fetch=async()=>new Response("{}",{status});await assert.rejects(()=>client.sendSettlementPreparation(attempt,"synthetic_csrf"),error=>error.outcome==="unknown");}
    globalThis.fetch=async()=>{throw new Error("synthetic network interruption");};await assert.rejects(()=>client.sendSettlementPreparation(attempt,"synthetic_csrf"),error=>error.outcome==="unknown");
    globalThis.fetch=async()=>new Response("{}",{status:403});await assert.rejects(()=>client.sendSettlementPreparation(attempt,"synthetic_csrf"),error=>error.outcome==="rejected"&&error.code==="scope");
  }finally{globalThis.fetch=old;}
});
test("abandonment requires its own matching response identifier and preserves original preparation lineage",async()=>{
  const old=globalThis.fetch,previous={...preparation,status:"REJECTED"},attempt=client.prepareSettlementAbandonment(schoolId,previous,id(70),"Synthetic release review",true);
  try{globalThis.fetch=async()=>new Response(JSON.stringify({clientRequestId:id(70),preparation:{...preparation,status:"ABANDONED"}}));assert.equal((await client.sendSettlementPreparation(attempt,"synthetic_csrf")).status,"ABANDONED");
    globalThis.fetch=async()=>new Response(JSON.stringify({clientRequestId:id(71),preparation:{...preparation,status:"ABANDONED"}}));await assert.rejects(()=>client.sendSettlementPreparation(attempt,"synthetic_csrf"),error=>error.outcome==="unknown");
  }finally{globalThis.fetch=old;}
});
