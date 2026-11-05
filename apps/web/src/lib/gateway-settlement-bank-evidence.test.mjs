import assert from "node:assert/strict";
import test from "node:test";
import * as bank from "./gateway-settlement-bank-evidence.ts";

// Synthetic fixtures only; no bank/provider credentials or real school records.
const id=n=>`8e000000-0000-4000-8000-${String(n).padStart(12,"0")}`;
const school=id(1),request=id(2),account=id(3),client=id(4),reference="synthetic_statement",at="2026-10-23T04:00:00.123456789Z",recorded="2026-10-23T05:00:00.123456789Z";
const evidence={id:id(5),clientRequestId:client,bankAccountId:account,bankReference:"SYN-UTR 001",currencyCode:"INR",creditedAmount:"90071992.54",actualCreditedAt:at,creditedOn:"2026-10-23",verificationKind:"BANK_APP_CREDIT",recordedAt:recorded,recordedBy:"Synthetic maker",reason:"Synthetic observed bank credit",status:"BANK_EVIDENCE_RECORDED",reviewReasons:bank.bankReviewReasons.slice(0,3),rejection:null,canReject:false};
const summary={sourceReadId:id(6),rowIndex:0,observedAt:at,reportedStatus:"SUCCESS",transferReference:"synthetic_utr",reportedAmountSettled:"90071992.54000000"};
const context={schoolId:school,requestId:request,settlementReference:reference,schoolTimeZone:"Asia/Kolkata",canRecord:true,canReject:true,summary,bankAccounts:[{id:account,code:"SYN-BANK",name:"Synthetic school bank",currencyCode:"INR"}],evidence:[evidence],hasMoreEvidence:false};
const draft={bankAccountId:account,amount:"90071992.54",actualLocal:"2026-10-23T09:30:00",utcOffset:"",verificationKind:"BANK_APP_CREDIT",bankReference:"SYN-UTR 001",confirmed:true,reason:"Synthetic observed bank credit"};
const now=new Date("2026-10-23T05:00:00Z"),decode=value=>bank.readBankEvidenceContext(value,school,request,reference),fails=value=>assert.throws(()=>decode(value),error=>error.code==="unavailable");
const prepared=()=>bank.prepareBankEvidence(context,draft,client,now);
const accepted=()=>({...evidence,actualCreditedAt:"2026-10-23T04:00:00Z"});
const rejectable={...evidence,canReject:true},rejectContext={...context,evidence:[rejectable]};
const rejected={...evidence,status:"REJECTED",rejection:{id:id(7),reason:"Synthetic independent correction",rejectedAt:"2026-10-23T06:00:00.123456789Z",rejectedBy:"Synthetic independent reviewer"}};
const rejection=()=>bank.prepareBankEvidenceRejection(rejectContext,rejectable,rejected.rejection.reason,true,id(8));

test("strict context retains exact provider amount and human evidence without calling it reconciliation",()=>{
  assert.equal(decode(context),context);assert.equal(decode(context).summary.reportedAmountSettled,"90071992.54000000");assert.equal(decode({...context,canRecord:false,canReject:false,evidence:[]}).evidence.length,0);
  const unknown={...context,summary:{...summary,reportedStatus:"NEW_STATUS",transferReference:null,reportedAmountSettled:null}};assert.equal(decode(unknown).summary.reportedAmountSettled,null);
});
test("school request statement zone and explicit nullable shape fail closed",()=>{
  for(const changes of[{schoolId:id(9)},{requestId:id(9)},{settlementReference:"synthetic_other"},{schoolTimeZone:"not/a-zone"},{canRecord:"yes"},{canReject:null},{hasMoreEvidence:1},{summary:{...summary,reportedAmountSettled:90071992.54}},{summary:{...summary,transferReference:undefined}},{summary:{...summary,rowIndex:10}}])fails({...context,...changes});
});
test("bounded unique history and eligible INR chart identities cannot be fabricated",()=>{
  for(const changes of[{bankAccounts:[context.bankAccounts[0],context.bankAccounts[0]]},{bankAccounts:[{...context.bankAccounts[0],currencyCode:"USD"}]},{bankAccounts:Array(1001).fill(context.bankAccounts[0])},{evidence:Array(26).fill(evidence)},{evidence:[evidence,evidence]},{hasMoreEvidence:true},{canReject:false,evidence:[rejectable]}])fails({...context,...changes});
  const rows=Array.from({length:25},(_,index)=>({...evidence,id:id(100+index),clientRequestId:id(200+index)}));assert.equal(decode({...context,evidence:rows,hasMoreEvidence:true}).evidence.length,25);
});
test("evidence has mandatory non-posting reasons and explicit independent rejection semantics",()=>{
  for(const reviewReasons of[[],["NOT_RECONCILED"],bank.bankReviewReasons.concat("UNKNOWN_REASON"),[...evidence.reviewReasons,"NOT_RECONCILED"]])fails({...context,evidence:[{...evidence,reviewReasons}]});
  for(const changes of[{status:"RECONCILED"},{rejection:rejected.rejection},{status:"REJECTED"},{...rejected,canReject:true},{...rejected,rejection:{...rejected.rejection,rejectedAt:at}}])fails({...context,evidence:[{...evidence,...changes}]});
  assert.equal(decode({...context,evidence:[rejected]}).evidence[0].status,"REJECTED");
  for(const reason of bank.bankReviewReasons)assert.ok(decode({...context,evidence:[{...evidence,reviewReasons:[...new Set([...evidence.reviewReasons,reason])]}]}));
});
test("different makers may retain the same locally generated request ID without collapsing evidence identity",()=>{
  const other={...evidence,id:id(9),bankReference:"SYN-OTHER-ENTRY",recordedBy:"Synthetic other maker"};
  assert.equal(decode({...context,evidence:[evidence,other]}).evidence.length,2);
});
test("exact paise strings signed provider precision safe actor text and nanos chronology are preserved",()=>{
  for(const creditedAmount of[90071992.54,"0.00","-1.00","1.1","1.001","01.00","1e2","100000000.01"])fails({...context,evidence:[{...evidence,creditedAmount}]});
  for(const changes of[{creditedOn:"2026-10-22"},{actualCreditedAt:"2026-02-30T04:00:00Z"},{recordedAt:"2026-10-23T04:00:00.123456788Z"},{recordedBy:"Synthetic\nactor"},{reason:"x"},{bankReference:"https://synthetic.invalid"}])fails({...context,evidence:[{...evidence,...changes}]});
  assert.equal(decode({...context,summary:{...summary,reportedAmountSettled:"-0.00000001"}}).summary.reportedAmountSettled,"-0.00000001");
  assert.equal(bank.readBankEvidence({...evidence,reason:"विद्यालय बैंक जमा की जाँच"},"Asia/Kolkata").actualCreditedAt,at);
});
test("private extra properties are not accepted at any response level",()=>{
  for(const key of["credentials","cursor","rawProviderBody","accountNumber"]){fails({...context,[key]:"synthetic_private"});fails({...context,summary:{...summary,[key]:"synthetic_private"}});fails({...context,bankAccounts:[{...context.bankAccounts[0],[key]:"synthetic_private"}]});fails({...context,evidence:[{...evidence,[key]:"synthetic_private"}]});fails({...context,evidence:[{...rejected,rejection:{...rejected.rejection,[key]:"synthetic_private"}}]});}
});
test("retained historical instants use the same proleptic year range as the backend",()=>{
  const row={...evidence,actualCreditedAt:"1999-12-31T18:30:00.123456789Z",creditedOn:"2000-01-01"};
  assert.equal(bank.readBankEvidence(row,"Asia/Kolkata").actualCreditedAt,row.actualCreditedAt);
  assert.throws(()=>bank.readBankEvidence({...row,actualCreditedAt:"0000-01-01T00:00:00Z",creditedOn:"0000-01-01"},"Asia/Kolkata"));
});
test("record draft uses configured school time exact string amount and immutable same-ID intent",()=>{
  const command=prepared(),body=JSON.parse(command.body);assert.equal(Object.isFrozen(command),true);assert.equal(body.creditedAmount,"90071992.54");assert.equal(body.actualCreditedAt,"2026-10-23T04:00:00.000Z");assert.equal(body.confirmedReceived,true);assert.equal(body.currencyCode,"INR");assert.equal(body.clientRequestId,client);assert.equal(Object.hasOwn(body,"schoolTimeZone"),false);
  for(const [amount,expected]of[["1","1.00"],["1.2","1.20"],["100000000.00","100000000.00"]])assert.equal(JSON.parse(bank.prepareBankEvidence(context,{...draft,amount},client,now).body).creditedAmount,expected);
});
test("invalid or unverified drafts do not become writable commands",()=>{
  for(const [change,code]of[[{amount:"0"},"amount"],[{amount:"1.001"},"amount"],[{amount:"1e2"},"amount"],[{bankAccountId:id(9)},"account"],[{confirmed:false},"confirmation"],[{verificationKind:"SCREENSHOT"},"confirmation"],[{bankReference:"https://synthetic.invalid"},"reference"],[{bankReference:"नकली"},"reference"],[{reason:"x"},"reason"],[{reason:"Synthetic\nreason"},"reason"],[{actualLocal:"2026-02-30T09:00"},"time"],[{actualLocal:"2026-10-23T12:00"},"future"]])assert.throws(()=>bank.prepareBankEvidence(context,{...draft,...change},client,now),error=>error.code===code);
  assert.throws(()=>bank.prepareBankEvidence({...context,canRecord:false},draft,client,now),error=>error.code==="scope");
});
test("DST overlaps need original matching offset and gaps cannot be guessed as UTC",()=>{
  const ny={...context,schoolTimeZone:"America/New_York"},overlap={...draft,actualLocal:"2026-11-01T01:30:00"},later=new Date("2026-11-03T00:00:00Z");
  assert.throws(()=>bank.prepareBankEvidence(ny,overlap,client,later),error=>error.code==="ambiguous");
  assert.equal(JSON.parse(bank.prepareBankEvidence(ny,{...overlap,utcOffset:"-04:00"},client,later).body).actualCreditedAt,"2026-11-01T05:30:00.000Z");
  assert.equal(JSON.parse(bank.prepareBankEvidence(ny,{...overlap,utcOffset:"-05:00"},client,later).body).actualCreditedAt,"2026-11-01T06:30:00.000Z");
  assert.throws(()=>bank.prepareBankEvidence(ny,{...draft,actualLocal:"2026-03-08T02:30:00"},client,later),error=>error.code==="time");
});
test("independent rejection freezes original evidence and never permits maker or forged selection",()=>{
  const command=rejection(),body=JSON.parse(command.body);assert.equal(command.kind,"REJECT");assert.equal(command.evidenceId,evidence.id);assert.equal(body.confirmedMistakenEvidence,true);assert.equal(body.reason,rejected.rejection.reason);assert.deepEqual(JSON.parse(command.expected),rejectable);assert.equal(Object.isFrozen(command),true);
  for(const [ctx,row,reason,confirmed,code]of[[context,evidence,"Synthetic correction",true,"scope"],[{...rejectContext,canReject:false},rejectable,"Synthetic correction",true,"scope"],[rejectContext,{...rejectable},"Synthetic correction",true,"scope"],[rejectContext,rejectable,"x",true,"reason"],[rejectContext,rejectable,"Synthetic correction",false,"confirmation"]])assert.throws(()=>bank.prepareBankEvidenceRejection(ctx,row,reason,confirmed,id(8)),error=>error.code===code);
});
test("reads are explicit scoped authenticated no-store GET and denial never echoes body",async()=>{
  const original=globalThis.fetch,calls=[],signal=new AbortController().signal;globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(context);};
  try{await bank.loadBankEvidenceContext(school,request,reference,signal);assert.match(calls[0].url,new RegExp(`/requests/${request}/bank-evidence\\?schoolId=${school}$`));assert.equal(calls[0].init.credentials,"include");assert.equal(calls[0].init.cache,"no-store");assert.equal(calls[0].init.signal,signal);assert.equal(calls[0].init.body,undefined);for(const[status,code]of[[401,"session"],[403,"scope"],[409,"incomplete"],[500,"unavailable"]]){globalThis.fetch=async()=>Response.json({detail:"synthetic_private"},{status});await assert.rejects(bank.loadBankEvidenceContext(school,request,reference,signal),error=>error.code===code&&!error.message.includes("private"));}}
  finally{globalThis.fetch=original;}
});
test("record exact retry preserves body and result identity without treating bank evidence as money",async()=>{
  const original=globalThis.fetch,calls=[],command=prepared();globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(accepted());};
  try{await bank.sendBankEvidence(command,"synthetic_csrf");await bank.sendBankEvidence(command,"synthetic_csrf");assert.equal(calls[0].init.body,calls[1].init.body);assert.equal(calls[0].init.body,command.body);assert.equal(calls[0].init.method,"POST");assert.equal(calls[0].init.credentials,"include");assert.equal(calls[0].init.cache,"no-store");assert.equal(calls[0].init.headers["X-CSRF-TOKEN"],"synthetic_csrf");assert.doesNotMatch(calls[0].url,/payment|journal|refund/);}
  finally{globalThis.fetch=original;}
});
test("record retry can acknowledge already independently rejected retained evidence without reactivation",async()=>{
  const original=globalThis.fetch;globalThis.fetch=async()=>Response.json({...accepted(),status:"REJECTED",rejection:rejected.rejection});try{assert.equal((await bank.sendBankEvidence(prepared(),"synthetic_csrf")).status,"REJECTED");}finally{globalThis.fetch=original;}
});
test("bank reference normalization explains reservations but never rewrites retained evidence",async()=>{
  assert.equal(bank.normalizeBankEvidenceReference("Syn-UTR \t001"),"SYN-UTR001");const original=globalThis.fetch;globalThis.fetch=async()=>Response.json({...accepted(),bankReference:"syn-utr001"});try{await assert.rejects(bank.sendBankEvidence(prepared(),"synthetic_csrf"),error=>error.outcome==="unknown");}finally{globalThis.fetch=original;}
});
test("rejection retry targets exact original item and preserves all retained financial evidence",async()=>{
  const original=globalThis.fetch,calls=[],command=rejection();globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(rejected);};
  try{const result=await bank.sendBankEvidence(command,"synthetic_csrf");assert.equal(result.bankReference,evidence.bankReference);assert.equal(result.creditedAmount,evidence.creditedAmount);assert.equal(result.clientRequestId,evidence.clientRequestId);assert.match(calls[0].url,new RegExp(`/bank-evidence/${evidence.id}/rejections$`));assert.equal(calls[0].init.body,command.body);globalThis.fetch=async()=>Response.json({...rejected,recordedBy:"Synthetic updated display name"});assert.equal((await bank.sendBankEvidence(command,"synthetic_csrf")).recordedBy,"Synthetic updated display name");}
  finally{globalThis.fetch=original;}
});
test("forged commands and absent CSRF fail before network, with safe generic codes",async()=>{
  const original=globalThis.fetch;let calls=0;globalThis.fetch=async()=>{calls++;throw new Error();};
  try{const command=prepared();for(const changes of[{body:" "+command.body},{body:command.body.replace('"INR"','"USD"')},{requestId:"invalid"},{schoolId:id(9)},{kind:"REFUND"},{body:command.body.replace('"confirmedReceived":true','"confirmedReceived":false')}])await assert.rejects(bank.sendBankEvidence({...command,...changes},"synthetic_csrf"),error=>error.outcome==="rejected");await assert.rejects(bank.sendBankEvidence(command,""),error=>error.code==="csrf");assert.equal(calls,0);}
  finally{globalThis.fetch=original;}
});
test("network timeout retry-required malformed and mismatched successes remain UNKNOWN",async()=>{
  const original=globalThis.fetch,command=prepared();
  try{for(const response of[()=>Promise.reject(new Error("private")),()=>new Response("private",{status:503}),()=>new Response("private",{status:408}),()=>Response.json({code:"GATEWAY_SETTLEMENT_BANK_RETRY_REQUIRED"},{status:409}),()=>new Response("not-json"),()=>Response.json({...accepted(),clientRequestId:id(9)}),()=>Response.json({...accepted(),creditedAmount:"1.00"}),()=>Response.json({...accepted(),canReject:true}),()=>Response.json({...accepted(),credentials:"private"})]){globalThis.fetch=async()=>response();await assert.rejects(bank.sendBankEvidence(command,"synthetic_csrf"),error=>error.outcome==="unknown"&&error.code==="unknown");}}
  finally{globalThis.fetch=original;}
});
test("all frozen backend error tags translate without accepting untrusted response detail",async()=>{
  const original=globalThis.fetch;
  const codes={TERMS_INVALID:"terms",EVIDENCE_NOT_FOUND:"missing",REQUEST_REUSED:"reused",REFERENCE_RESERVED:"reserved",REFERENCE_ALREADY_COLLECTED:"collected",ACCOUNT_UNAVAILABLE:"account",TIME_ZONE_CHANGED:"zone",FUTURE_CREDIT:"future",EVIDENCE_ALREADY_REJECTED:"alreadyRejected",INDEPENDENT_REJECTION_REQUIRED:"independent"};
  try{for(const[tag,code]of Object.entries(codes)){globalThis.fetch=async()=>Response.json({code:`GATEWAY_SETTLEMENT_BANK_${tag}`,detail:"synthetic_private"},{status:409});await assert.rejects(bank.sendBankEvidence(prepared(),"synthetic_csrf"),error=>error.outcome==="rejected"&&error.code===code&&!error.message.includes("private"));}assert.equal(bank.bankEvidenceErrorCode(new Error("private")),"unavailable");}
  finally{globalThis.fetch=original;}
});
test("altered rejection success never silently changes original bank evidence",async()=>{
  const original=globalThis.fetch;
  try{for(const changes of[{creditedAmount:"1.00"},{bankReference:"SYN-OTHER"},{actualCreditedAt:"2026-10-23T04:00:00Z"},{rejection:{...rejected.rejection,reason:"Synthetic different correction"}},{status:"BANK_EVIDENCE_RECORDED",rejection:null}]){globalThis.fetch=async()=>Response.json({...rejected,...changes});await assert.rejects(bank.sendBankEvidence(rejection(),"synthetic_csrf"),error=>error.outcome==="unknown");}}
  finally{globalThis.fetch=original;}
});
