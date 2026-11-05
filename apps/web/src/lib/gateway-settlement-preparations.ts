import { readReportedSettlementEvent, readReportedSettlementSummary, reportedSettlementMoney, type SettlementEvent, type SettlementSummary } from "./gateway-settlements";
import { readSettlementIdentifiedSource, type SettlementIdentifiedSource } from "./gateway-settlement-source-review";
import { readBankEvidence, type SettlementBankEvidence } from "./gateway-settlement-bank-evidence";

export const settlementComponentKeys = ["SERVICE_CHARGE","SERVICE_TAX","ADJUSTMENT","SETTLEMENT_CHARGE","SETTLEMENT_TAX","SPLIT_CHARGE","SPLIT_TAX","VENDOR_COMMISSION"] as const;
export const settlementComponentModes = ["REPORTED","ROUNDING_REVIEW","CONFIRMED_ZERO","EXTERNAL_REVIEW"] as const;
export const settlementPreparationStates = ["SUBMITTED","POSTED","REJECTED","REVERSED","ABANDONED"] as const;
export type SettlementComponentKey = typeof settlementComponentKeys[number];
export type SettlementComponentMode = typeof settlementComponentModes[number];
export type SettlementAccountingYear = {id:string;code:string;name:string;startsOn:string;endsOn:string;accountingBasis:"CASH"|"ACCRUAL";currencyCode:"INR"};
export type SettlementAccountingAccount = {id:string;code:string;name:string;accountType:string;category:string};
export type SettlementAccountingOptions = {canPrepare:boolean;financialYears:SettlementAccountingYear[];accounts:SettlementAccountingAccount[]};
export type SettlementComponentFact = {key:SettlementComponentKey;summaryAmount:string|null;eventsAmount:string|null;eventsComplete:boolean};
export type SettlementPreparationRow = {sourceReadId:string;rowIndex:number;event:SettlementEvent;status:"PAYMENT_SOURCE_IDENTIFIED"|"REFUND_SELECTION_REQUIRED"|"REVIEW_REQUIRED";reasons:string[];source:SettlementIdentifiedSource|null};
export type SettlementPreparation = {id:string;clientRequestId:string;rootId:string;requestId:string;bankEvidenceId:string;revision:number;previousPreparationId:string|null;journalId:string;status:typeof settlementPreparationStates[number];rowVersion:number;voucherNumber:string|null;postingOn:string;currencyCode:"INR";bankAmount:string;paymentTotal:string;refundTotal:string;componentDebitTotal:string;componentCreditTotal:string;sourceCount:number;preparedAt:string;preparedBy:string;reason:string};
export type SettlementPreparationContext = {schoolId:string;requestId:string;settlementReference:string;schoolTimeZone:string;contextFingerprint:string;canPrepare:boolean;accountingOptions:SettlementAccountingOptions;bankEvidence:SettlementBankEvidence[];summary:SettlementSummary;componentFacts:SettlementComponentFact[];paymentTotal:string|null;refundTotal:string|null;page:number;pageSize:number;totalEvents:number;totalRefundEvents:number;hasMore:boolean;rows:SettlementPreparationRow[];blockers:string[];preparations:SettlementPreparation[]};
export type SettlementComponentDraft = {key:SettlementComponentKey;mode:SettlementComponentMode|"";direction:"DEBIT"|"CREDIT"|"";accountId:string;postedAmount:string;evidenceReference:string;reason:string};
export type SettlementRefundAssociation = {sourceReadId:string;rowIndex:number;refundPostingId:string;reason:string};
export type SettlementPreparationDraft = {bankEvidenceId:string;financialYearId:string;postingOn:string;confirmedSummaryCurrency:boolean;componentBasis:"SUMMARY"|"EVENTS"|"";componentReviews:SettlementComponentDraft[];refundAssociations:SettlementRefundAssociation[];previousPreparationId:string;confirmedReviewed:boolean;reason:string};
export type SettlementPreparationAttempt = Readonly<{kind:"PREPARE"|"ABANDON";schoolId:string;requestId:string;clientRequestId:string;preparationId:string|null;body:string;expected:string}>;
export type SettlementComponentReview = {key:SettlementComponentKey;mode:SettlementComponentMode;direction:"DEBIT"|"CREDIT";accountId:string|null;postedAmount:string;evidenceReference:string|null;reason:string};
export type SettlementSourceIdentity = {kind:"PAYMENT"|"REFUND";paymentId:string;refundPostingId:string|null;feeLedgerEntryId:string;postingItemId:string;postingBatchId:string;accountingJournalId:string;clearingAccountId:string;amount:string;sourceReadId:string;rowIndex:number;associationReason:string|null};
export type SettlementPreparationDetail = {preparation:SettlementPreparation;schoolTimeZone:string;summary:SettlementSummary;bankEvidence:SettlementBankEvidence;componentBasis:"SUMMARY"|"EVENTS";componentFacts:SettlementComponentFact[];componentReviews:SettlementComponentReview[];refundAssociations:SettlementRefundAssociation[];sources:SettlementSourceIdentity[]};
export type SettlementPreparationErrorCode = "unavailable"|"session"|"scope"|"incomplete"|"terms"|"bank"|"year"|"date"|"components"|"refunds"|"confirmation"|"reason"|"stale"|"consumed"|"independent"|"rejected"|"csrf"|"unknown";
export class SettlementPreparationError extends Error {
  readonly code:SettlementPreparationErrorCode;
  constructor(code:SettlementPreparationErrorCode){super("SETTLEMENT_PREPARATION_"+code.toUpperCase());this.name="SettlementPreparationError";this.code=code;}
}
export class SettlementPreparationWriteError extends SettlementPreparationError {
  readonly outcome:"unknown"|"rejected";
  constructor(outcome:"unknown"|"rejected",code:SettlementPreparationErrorCode){super(code);this.name="SettlementPreparationWriteError";this.outcome=outcome;}
}
export function settlementPreparationErrorCode(error:unknown):SettlementPreparationErrorCode{return error instanceof SettlementPreparationError?error.code:"unavailable";}
const base=process.env.NEXT_PUBLIC_API_URL??"http://localhost:8080",path="/api/v1/finance/gateway/settlements";
const uuid=(value:unknown):value is string=>typeof value==="string"&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
const keys=(value:unknown,names:readonly string[]):value is Record<string,unknown>=>!!value&&typeof value==="object"&&!Array.isArray(value)&&Object.keys(value).length===names.length&&Object.keys(value).every(key=>names.includes(key));
const text=(value:unknown,max:number,min=1):value is string=>typeof value==="string"&&value.length>=min&&value.length<=max&&value===value.trim()&&!/[\u0000-\u001f\u007f-\u009f]/.test(value);
const tag=(value:unknown):value is string=>typeof value==="string"&&/^[A-Z][A-Z0-9_]{0,99}$/.test(value);
const integer=(value:unknown,max:number):value is number=>typeof value==="number"&&Number.isSafeInteger(value)&&value>=0&&value<=max;
const fail=():never=>{throw new SettlementPreparationError("unavailable");};
function readPrivate<T>(read:()=>T):T {try{return read();}catch{return fail();}}
function date(value:unknown):value is string {
  if(typeof value!=="string"||!/^\d{4}-\d{2}-\d{2}$/.test(value)||value<"2000-01-01"||value>"2100-12-31")return false;
  const parsed=new Date(value+"T00:00:00Z");return Number.isFinite(parsed.getTime())&&parsed.toISOString().slice(0,10)===value;
}
function instant(value:unknown):value is string {
  if(typeof value!=="string"||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3}(?:\d{3}){0,2})?Z$/.test(value)||!date(value.slice(0,10)))return false;
  const parsed=new Date(value);return Number.isFinite(parsed.getTime())&&parsed.toISOString().slice(0,19)===value.slice(0,19);
}
function zone(value:unknown):value is string {if(!text(value,60))return false;try{new Intl.DateTimeFormat("en",{timeZone:value}).format(0);return true;}catch{return false;}}
function money(value:unknown,zero=true):value is string {
  if(typeof value!=="string"||!/^(?:0|[1-9]\d{0,8})\.\d{2}$/.test(value))return false;
  const amount=BigInt(value.replace(".",""));return amount<=10000000000n&&(zero||amount>0n);
}
export function settlementPaise(value:string):bigint {if(!money(value))throw new SettlementPreparationError("components");return BigInt(value.replace(".",""));}
export function settlementPhysicalRow(row:Pick<SettlementPreparationRow,"sourceReadId"|"rowIndex">):string {return row.sourceReadId+":"+row.rowIndex;}
function scope(schoolId:string,requestId?:string):void {if(!uuid(schoolId)||requestId!==undefined&&!uuid(requestId))throw new SettlementPreparationError("terms");}
const statementReference=(value:unknown):value is string=>typeof value==="string"&&/^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/.test(value);
const summaryComponentFields:Record<SettlementComponentKey,keyof SettlementSummary>={SERVICE_CHARGE:"reportedServiceCharge",SERVICE_TAX:"reportedServiceTax",ADJUSTMENT:"reportedAdjustment",SETTLEMENT_CHARGE:"reportedSettlementCharge",SETTLEMENT_TAX:"reportedSettlementTax",SPLIT_CHARGE:"reportedSplitCharge",SPLIT_TAX:"reportedSplitTax",VENDOR_COMMISSION:"reportedVendorCommission"};
function codeList(value:unknown):value is string[] {return Array.isArray(value)&&value.length<=100&&new Set(value).size===value.length&&value.every(tag);}

export function readSettlementPreparation(value:unknown,requestId?:string):SettlementPreparation {
  if(!keys(value,["id","clientRequestId","rootId","requestId","bankEvidenceId","revision","previousPreparationId","journalId","status","rowVersion","voucherNumber","postingOn","currencyCode","bankAmount","paymentTotal","refundTotal","componentDebitTotal","componentCreditTotal","sourceCount","preparedAt","preparedBy","reason"]))return fail();
  const row=value as SettlementPreparation;
  if(![row.id,row.clientRequestId,row.rootId,row.requestId,row.bankEvidenceId,row.journalId].every(uuid)||requestId!==undefined&&row.requestId!==requestId||!integer(row.revision,1000000)||row.revision<1||!integer(row.rowVersion,Number.MAX_SAFE_INTEGER)||!settlementPreparationStates.includes(row.status)||!date(row.postingOn)||row.currencyCode!=="INR"||!money(row.bankAmount,false)||![row.paymentTotal,row.refundTotal,row.componentDebitTotal,row.componentCreditTotal].every(value=>money(value))||!integer(row.sourceCount,10000)||row.sourceCount<1||!instant(row.preparedAt)||!text(row.preparedBy,320)||!text(row.reason,240,3))return fail();
  if(row.previousPreparationId===null){if(row.revision!==1)return fail();}else if(!uuid(row.previousPreparationId)||row.previousPreparationId===row.id||row.revision<2)return fail();
  if(row.voucherNumber!==null&&!text(row.voucherNumber,120))return fail();
  if(settlementPaise(row.paymentTotal)-settlementPaise(row.refundTotal)!==settlementPaise(row.bankAmount)+settlementPaise(row.componentDebitTotal)-settlementPaise(row.componentCreditTotal))return fail();
  return row;
}
function readOptions(value:unknown):SettlementAccountingOptions {
  if(!keys(value,["canPrepare","financialYears","accounts"])||typeof value.canPrepare!=="boolean"||!Array.isArray(value.financialYears)||value.financialYears.length>1000||!Array.isArray(value.accounts)||value.accounts.length>10000)return fail();
  const result=value as SettlementAccountingOptions,yearIds=new Set<string>(),accountIds=new Set<string>();
  for(const year of result.financialYears){if(!keys(year,["id","code","name","startsOn","endsOn","accountingBasis","currencyCode"])||!uuid(year.id)||yearIds.has(year.id)||!text(year.code,100)||!text(year.name,200)||!date(year.startsOn)||!date(year.endsOn)||year.startsOn>year.endsOn||!["CASH","ACCRUAL"].includes(year.accountingBasis)||year.currencyCode!=="INR")return fail();yearIds.add(year.id);}
  for(const account of result.accounts){if(!keys(account,["id","code","name","accountType","category"])||!uuid(account.id)||accountIds.has(account.id)||!text(account.code,100)||!text(account.name,200)||!["ASSET","LIABILITY","EXPENSE","INCOME"].includes(account.accountType)||!["BANK","OTHER","EXPENSE","INCOME","PAYABLE"].includes(account.category))return fail();accountIds.add(account.id);}
  return result;
}
export function readSettlementPreparationContext(value:unknown,schoolId:string,requestId:string,settlementReference:string,page:number):SettlementPreparationContext {
  scope(schoolId,requestId);if(!integer(page,10000)||!statementReference(settlementReference))return fail();
  if(!keys(value,["schoolId","requestId","settlementReference","schoolTimeZone","contextFingerprint","canPrepare","accountingOptions","bankEvidence","summary","componentFacts","paymentTotal","refundTotal","page","pageSize","totalEvents","totalRefundEvents","hasMore","rows","blockers","preparations"]))return fail();
  const result=value as SettlementPreparationContext;
  if(result.schoolId!==schoolId||result.requestId!==requestId||result.settlementReference!==settlementReference||!zone(result.schoolTimeZone)||!text(result.contextFingerprint,64)||!/^[a-f0-9]{64}$/.test(result.contextFingerprint)||typeof result.canPrepare!=="boolean"||result.page!==page||result.pageSize!==25||!integer(result.totalEvents,10000)||!integer(result.totalRefundEvents,result.totalEvents)||typeof result.hasMore!=="boolean"||!Array.isArray(result.rows)||result.rows.length!==Math.min(25,Math.max(0,result.totalEvents-page*25))||result.hasMore!==(page*25+result.rows.length<result.totalEvents)||!codeList(result.blockers))return fail();
  if((result.paymentTotal===null)!==(result.refundTotal===null)||![result.paymentTotal,result.refundTotal].every(value=>value===null||money(value)))return fail();
  readOptions(result.accountingOptions);
  if(result.canPrepare&&!result.accountingOptions.canPrepare)return fail();
  if(!Array.isArray(result.bankEvidence)||result.bankEvidence.length>1000||!Array.isArray(result.preparations)||result.preparations.length>1000||!Array.isArray(result.componentFacts)||result.componentFacts.length!==8||readPrivate(()=>readReportedSettlementSummary(result.summary)).settlementReference!==settlementReference)return fail();
  const evidence=new Set<string>(),preparations=new Set<string>(),components=new Set<string>(),positions=new Set<string>();
  for(const bank of result.bankEvidence){readPrivate(()=>readBankEvidence(bank,result.schoolTimeZone));if(evidence.has(bank.id))return fail();evidence.add(bank.id);}
  for(const preparation of result.preparations){readSettlementPreparation(preparation,requestId);if(preparations.has(preparation.id))return fail();preparations.add(preparation.id);}
  for(const fact of result.componentFacts){if(!keys(fact,["key","summaryAmount","eventsAmount","eventsComplete"])||!settlementComponentKeys.includes(fact.key)||components.has(fact.key)||typeof fact.eventsComplete!=="boolean"||![fact.summaryAmount,fact.eventsAmount].every(value=>value===null||reportedSettlementMoney(value))||fact.summaryAmount!==result.summary[summaryComponentFields[fact.key]])return fail();components.add(fact.key);}
  for(const row of result.rows){
    if(!keys(row,["sourceReadId","rowIndex","event","status","reasons","source"])||!uuid(row.sourceReadId)||!integer(row.rowIndex,9)||positions.has(settlementPhysicalRow(row))||!codeList(row.reasons)||readPrivate(()=>readReportedSettlementEvent(row.event)).settlementReference!==settlementReference||!["PAYMENT_SOURCE_IDENTIFIED","REFUND_SELECTION_REQUIRED","REVIEW_REQUIRED"].includes(row.status))return fail();
    if(row.source!==null)readPrivate(()=>readSettlementIdentifiedSource(row.source));
    if(row.status==="PAYMENT_SOURCE_IDENTIFIED"&&(row.source===null||row.reasons.length||row.event.reportedType!=="PAYMENT"||row.event.reportedDirection!=="CREDIT"||row.event.reportedStatus!=="SUCCESS"||row.event.currencyCode!=="INR"||!row.event.orderReference||!row.event.paymentReference||scaled(row.event.reportedAmount)!==scaled(row.source.grossAmount)))return fail();
    if(row.status==="REFUND_SELECTION_REQUIRED"&&(row.event.reportedType!=="REFUND"||row.event.reportedDirection!=="DEBIT"||row.event.reportedStatus!=="SUCCESS"||row.event.currencyCode!=="INR"))return fail();
    if(row.status==="REVIEW_REQUIRED"&&(!row.reasons.length||row.source!==null))return fail();
    positions.add(settlementPhysicalRow(row));
  }
  return result;
}
function scaled(value:string):bigint {const negative=value.startsWith("-"),[whole,fraction=""]=(negative?value.slice(1):value).split(".");return(BigInt(whole!)*100000000n+BigInt(fraction.padEnd(8,"0")))*(negative?-1n:1n);}
export function componentReportedAmount(fact:SettlementComponentFact,basis:"SUMMARY"|"EVENTS"|""):string|null {return basis==="EVENTS"&&["SERVICE_CHARGE","SERVICE_TAX"].includes(fact.key)?fact.eventsComplete?fact.eventsAmount:null:fact.summaryAmount;}
export function newSettlementPreparationDraft():SettlementPreparationDraft{return{bankEvidenceId:"",financialYearId:"",postingOn:"",confirmedSummaryCurrency:false,componentBasis:"",componentReviews:settlementComponentKeys.map(key=>({key,mode:"",direction:"",accountId:"",postedAmount:"",evidenceReference:"",reason:""})),refundAssociations:[],previousPreparationId:"",confirmedReviewed:false,reason:""};}
export function settlementComponentAccounts(options:SettlementAccountingOptions,key:SettlementComponentKey):SettlementAccountingAccount[] {return options.accounts.filter(account=>key==="ADJUSTMENT"?account.accountType!=="ASSET"||account.category==="OTHER":key.endsWith("_TAX")?account.accountType==="EXPENSE"&&["EXPENSE","OTHER"].includes(account.category)||account.accountType==="ASSET"&&account.category==="OTHER":account.accountType==="EXPENSE"&&["EXPENSE","OTHER"].includes(account.category));}
function normalizeMoney(value:string):string {if(!/^(?:0|[1-9]\d{0,8})(?:\.\d{1,2})?$/.test(value))throw new SettlementPreparationError("components");const[whole,fraction=""]=value.split(".");const result=whole+"."+fraction.padEnd(2,"0");if(!money(result))throw new SettlementPreparationError("components");return result;}
function reason(value:string):string {const result=value.trim();if(!text(result,240,3))throw new SettlementPreparationError("reason");return result;}
function evidenceReference(value:string):string|null {const cleaned=value.trim();if(!cleaned)return null;if(!/^[\p{L}\p{N}][\p{L}\p{N} /_.:-]{0,119}$/u.test(cleaned)||cleaned.includes("://"))throw new SettlementPreparationError("components");return cleaned;}
export function prepareSettlementJournal(context:SettlementPreparationContext,draft:SettlementPreparationDraft,clientRequestId:string):SettlementPreparationAttempt {
  scope(context.schoolId,context.requestId);
  if(!context.canPrepare||!context.accountingOptions.canPrepare||!uuid(clientRequestId))throw new SettlementPreparationError("scope");
  if(context.blockers.some(code=>code!=="STATEMENT_ALREADY_PREPARED")||!draft.previousPreparationId&&context.blockers.includes("STATEMENT_ALREADY_PREPARED"))throw new SettlementPreparationError("incomplete");
  const bank=context.bankEvidence.find(row=>row.id===draft.bankEvidenceId&&row.status==="BANK_EVIDENCE_RECORDED");
  if(!bank)throw new SettlementPreparationError("bank");
  const year=context.accountingOptions.financialYears.find(row=>row.id===draft.financialYearId);if(!year)throw new SettlementPreparationError("year");
  if(!date(draft.postingOn)||draft.postingOn<year.startsOn||draft.postingOn>year.endsOn)throw new SettlementPreparationError("date");
  if(!draft.confirmedSummaryCurrency||!draft.confirmedReviewed)throw new SettlementPreparationError("confirmation");
  if(!["SUMMARY","EVENTS"].includes(draft.componentBasis)||draft.componentReviews.length!==8)throw new SettlementPreparationError("components");
  const seen=new Set<string>(),componentReviews=draft.componentReviews.map(component=>{
    if(!settlementComponentKeys.includes(component.key)||seen.has(component.key)||!settlementComponentModes.includes(component.mode as SettlementComponentMode)||!["DEBIT","CREDIT"].includes(component.direction))throw new SettlementPreparationError("components");seen.add(component.key);
    const amount=normalizeMoney(component.postedAmount),amountPaise=settlementPaise(amount),reference=evidenceReference(component.evidenceReference),fact=context.componentFacts.find(row=>row.key===component.key);
    if(!fact)throw new SettlementPreparationError("components");const raw=componentReportedAmount(fact,draft.componentBasis),reported=raw===null?null:scaled(raw),absolute=reported===null?null:reported<0n?-reported:reported;
    if(amountPaise>0n&&(!reference||!settlementComponentAccounts(context.accountingOptions,component.key).some(account=>account.id===component.accountId))||amountPaise===0n&&component.accountId)throw new SettlementPreparationError("components");
    if(component.mode==="REPORTED"&&(absolute===null||absolute!==amountPaise*1000000n)||component.mode==="ROUNDING_REVIEW"&&(absolute===null||!reference||absolute===amountPaise*1000000n||(absolute-amountPaise*1000000n<0n?amountPaise*1000000n-absolute:absolute-amountPaise*1000000n)>=1000000n)||component.mode==="CONFIRMED_ZERO"&&(amountPaise!==0n||reported!==null&&reported!==0n)||component.mode==="EXTERNAL_REVIEW"&&(reported!==null||amountPaise===0n||!reference))throw new SettlementPreparationError("components");
    if(reported!==null&&reported!==0n&&component.key!=="ADJUSTMENT"&&(reported>0n?"DEBIT":"CREDIT")!==component.direction)throw new SettlementPreparationError("components");
    return{key:component.key,mode:component.mode,direction:component.direction,accountId:amountPaise===0n?null:component.accountId,postedAmount:amount,evidenceReference:reference,reason:reason(component.reason)};
  });
  const positions=new Set<string>(),refunds=new Set<string>();
  if(draft.refundAssociations.length!==context.totalRefundEvents)throw new SettlementPreparationError("refunds");
  const refundAssociations=draft.refundAssociations.map(row=>{if(!uuid(row.sourceReadId)||!integer(row.rowIndex,9)||!uuid(row.refundPostingId)||positions.has(settlementPhysicalRow(row))||refunds.has(row.refundPostingId))throw new SettlementPreparationError("refunds");positions.add(settlementPhysicalRow(row));refunds.add(row.refundPostingId);return{...row,reason:reason(row.reason)};});
  if(draft.previousPreparationId){const previous=context.preparations.find(row=>row.id===draft.previousPreparationId);if(!previous||!["REJECTED","REVERSED"].includes(previous.status))throw new SettlementPreparationError("stale");}
  if(context.paymentTotal===null||context.refundTotal===null)throw new SettlementPreparationError("incomplete");
  const debit=componentReviews.filter(row=>row.direction==="DEBIT").reduce((sum,row)=>sum+settlementPaise(row.postedAmount),0n),credit=componentReviews.filter(row=>row.direction==="CREDIT").reduce((sum,row)=>sum+settlementPaise(row.postedAmount),0n);
  if(settlementPaise(context.paymentTotal)-settlementPaise(context.refundTotal)!==settlementPaise(bank.creditedAmount)+debit-credit)throw new SettlementPreparationError("components");
  const body=JSON.stringify({schoolId:context.schoolId,clientRequestId,contextFingerprint:context.contextFingerprint,bankEvidenceId:bank.id,financialYearId:year.id,postingOn:draft.postingOn,summaryCurrencyCode:"INR",confirmedSummaryCurrency:true,componentBasis:draft.componentBasis,componentReviews,refundAssociations,previousPreparationId:draft.previousPreparationId||null,confirmedReviewed:true,reason:reason(draft.reason)});
  return Object.freeze({kind:"PREPARE",schoolId:context.schoolId,requestId:context.requestId,clientRequestId,preparationId:null,body,expected:JSON.stringify({bankEvidenceId:bank.id,bankAmount:bank.creditedAmount,postingOn:draft.postingOn,previousPreparationId:draft.previousPreparationId||null})});
}
export function prepareSettlementAbandonment(schoolId:string,preparation:SettlementPreparation,clientRequestId:string,reviewReason:string,confirmed:boolean):SettlementPreparationAttempt {
  scope(schoolId,preparation.requestId);readSettlementPreparation(preparation);
  if(!uuid(clientRequestId)||preparation.status!=="REJECTED"||!confirmed)throw new SettlementPreparationError("confirmation");
  return Object.freeze({kind:"ABANDON",schoolId,requestId:preparation.requestId,clientRequestId,preparationId:preparation.id,body:JSON.stringify({schoolId,clientRequestId,reason:reason(reviewReason),confirmedReleaseRejectedPreparation:true}),expected:JSON.stringify(preparation)});
}
export async function loadSettlementPreparationContext(schoolId:string,requestId:string,settlementReference:string,page:number,signal:AbortSignal):Promise<SettlementPreparationContext>{
  scope(schoolId,requestId);if(!integer(page,10000)||!statementReference(settlementReference))throw new SettlementPreparationError("terms");const response=await fetch(base+path+"/requests/"+requestId+"/preparation-context?"+new URLSearchParams({schoolId,page:String(page),pageSize:"25"}),{credentials:"include",cache:"no-store",signal});
  if(!response.ok)throw new SettlementPreparationError(response.status===401?"session":response.status===403?"scope":response.status===409?"incomplete":"unavailable");
  let value:unknown;try{value=await response.json();}catch{return fail();}return readSettlementPreparationContext(value,schoolId,requestId,settlementReference,page);
}
export function readSettlementPreparationDetail(value:unknown,preparationId:string,expectedJournalId:string):SettlementPreparationDetail {
  if(!uuid(preparationId)||!uuid(expectedJournalId)||!keys(value,["preparation","schoolTimeZone","summary","bankEvidence","componentBasis","componentFacts","componentReviews","refundAssociations","sources"]))return fail();
  const detail=value as SettlementPreparationDetail,preparation=readSettlementPreparation(detail.preparation);
  if(preparation.id!==preparationId||preparation.journalId!==expectedJournalId||!zone(detail.schoolTimeZone)||!["SUMMARY","EVENTS"].includes(detail.componentBasis)||!Array.isArray(detail.componentFacts)||detail.componentFacts.length!==8||!Array.isArray(detail.componentReviews)||detail.componentReviews.length!==8||!Array.isArray(detail.refundAssociations)||detail.refundAssociations.length>10000||!Array.isArray(detail.sources)||detail.sources.length!==preparation.sourceCount)return fail();
  readPrivate(()=>readReportedSettlementSummary(detail.summary));readPrivate(()=>readBankEvidence(detail.bankEvidence,detail.schoolTimeZone));
  if(detail.bankEvidence.id!==preparation.bankEvidenceId||detail.bankEvidence.creditedAmount!==preparation.bankAmount)return fail();
  const facts=new Set<string>(),reviews=new Set<string>(),rows=new Set<string>(),economic=new Set<string>(),refunds=new Map<string,SettlementSourceIdentity>();let paymentTotal=0n,refundTotal=0n,debit=0n,credit=0n;
  for(const fact of detail.componentFacts){if(!keys(fact,["key","summaryAmount","eventsAmount","eventsComplete"])||!settlementComponentKeys.includes(fact.key)||facts.has(fact.key)||typeof fact.eventsComplete!=="boolean"||![fact.summaryAmount,fact.eventsAmount].every(value=>value===null||reportedSettlementMoney(value))||fact.summaryAmount!==detail.summary[summaryComponentFields[fact.key]])return fail();facts.add(fact.key);}
  for(const review of detail.componentReviews){
    if(!keys(review,["key","mode","direction","accountId","postedAmount","evidenceReference","reason"])||!settlementComponentKeys.includes(review.key)||reviews.has(review.key)||!settlementComponentModes.includes(review.mode)||!["DEBIT","CREDIT"].includes(review.direction)||!money(review.postedAmount)||!text(review.reason,240,3)||!(review.evidenceReference===null||text(review.evidenceReference,120)))return fail();
    const amount=settlementPaise(review.postedAmount);if(amount===0n?review.accountId!==null:!uuid(review.accountId)||review.evidenceReference===null)return fail();
    const fact=detail.componentFacts.find(value=>value.key===review.key);if(!fact||!reviewMatchesFact(review,fact,detail.componentBasis))return fail();
    reviews.add(review.key);if(review.direction==="DEBIT")debit+=amount;else credit+=amount;
  }
  for(const source of detail.sources){
    if(!keys(source,["kind","paymentId","refundPostingId","feeLedgerEntryId","postingItemId","postingBatchId","accountingJournalId","clearingAccountId","amount","sourceReadId","rowIndex","associationReason"])||!["PAYMENT","REFUND"].includes(source.kind)||![source.paymentId,source.feeLedgerEntryId,source.postingItemId,source.postingBatchId,source.accountingJournalId,source.clearingAccountId,source.sourceReadId].every(uuid)||!integer(source.rowIndex,9)||!money(source.amount,false)||rows.has(settlementPhysicalRow(source)))return fail();
    const identity=source.kind+":"+(source.kind==="PAYMENT"?source.paymentId:source.refundPostingId);if(economic.has(identity))return fail();economic.add(identity);rows.add(settlementPhysicalRow(source));
    if(source.kind==="PAYMENT"){if(source.refundPostingId!==null||source.associationReason!==null)return fail();paymentTotal+=settlementPaise(source.amount);}
    else{if(!uuid(source.refundPostingId)||!text(source.associationReason,240,3))return fail();refundTotal+=settlementPaise(source.amount);refunds.set(settlementPhysicalRow(source),source);}
  }
  if(detail.refundAssociations.length!==refunds.size||paymentTotal!==settlementPaise(preparation.paymentTotal)||refundTotal!==settlementPaise(preparation.refundTotal)||debit!==settlementPaise(preparation.componentDebitTotal)||credit!==settlementPaise(preparation.componentCreditTotal))return fail();
  const associations=new Set<string>();
  for(const association of detail.refundAssociations){if(!keys(association,["sourceReadId","rowIndex","refundPostingId","reason"])||!uuid(association.sourceReadId)||!integer(association.rowIndex,9)||!uuid(association.refundPostingId)||!text(association.reason,240,3)||associations.has(settlementPhysicalRow(association)))return fail();const source=refunds.get(settlementPhysicalRow(association));if(!source||source.refundPostingId!==association.refundPostingId||source.associationReason!==association.reason)return fail();associations.add(settlementPhysicalRow(association));}
  return detail;
}
function reviewMatchesFact(review:SettlementComponentReview,fact:SettlementComponentFact,basis:"SUMMARY"|"EVENTS"):boolean {
  const raw=componentReportedAmount(fact,basis),reported=raw===null?null:scaled(raw),absolute=reported===null?null:reported<0n?-reported:reported,amount=settlementPaise(review.postedAmount)*1000000n;
  if(review.evidenceReference!==null&&(!/^[\p{L}\p{N}][\p{L}\p{N} /_.:-]{0,119}$/u.test(review.evidenceReference)||review.evidenceReference.includes("://")))return false;
  if(review.mode==="REPORTED"&&(absolute===null||absolute!==amount)||review.mode==="ROUNDING_REVIEW"&&(absolute===null||review.evidenceReference===null||absolute===amount||(absolute-amount<0n?amount-absolute:absolute-amount)>=1000000n)||review.mode==="CONFIRMED_ZERO"&&(amount!==0n||reported!==null&&reported!==0n)||review.mode==="EXTERNAL_REVIEW"&&(reported!==null||amount===0n||review.evidenceReference===null))return false;
  return reported===null||reported===0n||review.key==="ADJUSTMENT"||(reported>0n?"DEBIT":"CREDIT")===review.direction;
}
export async function loadSettlementPreparation(schoolId:string,preparationId:string,expectedJournalId:string,signal:AbortSignal):Promise<SettlementPreparationDetail>{
  scope(schoolId);if(!uuid(preparationId)||!uuid(expectedJournalId))return fail();const response=await fetch(base+path+"/preparations/"+preparationId+"?"+new URLSearchParams({schoolId}),{credentials:"include",cache:"no-store",signal});
  if(!response.ok)throw new SettlementPreparationError(response.status===401?"session":response.status===403?"scope":"unavailable");
  let value:unknown;try{value=await response.json();}catch{return fail();}return readSettlementPreparationDetail(value,preparationId,expectedJournalId);
}
const errors:Record<string,SettlementPreparationErrorCode>={GATEWAY_SETTLEMENT_PREPARATION_INVALID:"terms",GATEWAY_SETTLEMENT_COMPONENT_REVIEW_REQUIRED:"components",GATEWAY_SETTLEMENT_NOT_BALANCED:"components",GATEWAY_SETTLEMENT_PREPARATION_CONTEXT_CHANGED:"stale",GATEWAY_SETTLEMENT_SOURCE_ALREADY_CLAIMED:"consumed",GATEWAY_SETTLEMENT_BANK_EVIDENCE_CONSUMED:"consumed",GATEWAY_SETTLEMENT_SCOPE_DENIED:"scope",GATEWAY_SETTLEMENT_PREPARATION_NOT_FOUND:"stale",GATEWAY_SETTLEMENT_REPAIR_REQUIRED:"stale",GATEWAY_SETTLEMENT_ABANDONMENT_NOT_ALLOWED:"stale"};
function validPreparationCommand(value:Record<string,unknown>):boolean {
  if(!keys(value,["schoolId","clientRequestId","contextFingerprint","bankEvidenceId","financialYearId","postingOn","summaryCurrencyCode","confirmedSummaryCurrency","componentBasis","componentReviews","refundAssociations","previousPreparationId","confirmedReviewed","reason"])||![value.schoolId,value.clientRequestId,value.bankEvidenceId,value.financialYearId].every(uuid)||typeof value.contextFingerprint!=="string"||!/^[a-f0-9]{64}$/.test(value.contextFingerprint)||!date(value.postingOn)||value.summaryCurrencyCode!=="INR"||value.confirmedSummaryCurrency!==true||value.confirmedReviewed!==true||!["SUMMARY","EVENTS"].includes(value.componentBasis as string)||!text(value.reason,240,3)||!(value.previousPreparationId===null||uuid(value.previousPreparationId))||!Array.isArray(value.componentReviews)||value.componentReviews.length!==8||!Array.isArray(value.refundAssociations)||value.refundAssociations.length>10000)return false;
  const componentKeys=new Set<string>(),positions=new Set<string>(),refunds=new Set<string>();
  for(const row of value.componentReviews){
    if(!keys(row,["key","mode","direction","accountId","postedAmount","evidenceReference","reason"])||!settlementComponentKeys.includes(row.key as SettlementComponentKey)||componentKeys.has(row.key as string)||!settlementComponentModes.includes(row.mode as SettlementComponentMode)||!["DEBIT","CREDIT"].includes(row.direction as string)||!money(row.postedAmount)||!text(row.reason,240,3)||!(row.evidenceReference===null||typeof row.evidenceReference==="string"&&/^[\p{L}\p{N}][\p{L}\p{N} /_.:-]{0,119}$/u.test(row.evidenceReference)&&!row.evidenceReference.includes("://")))return false;
    const amount=settlementPaise(row.postedAmount);if(amount===0n?row.accountId!==null:!uuid(row.accountId)||row.evidenceReference===null)return false;componentKeys.add(row.key as string);
  }
  for(const row of value.refundAssociations){if(!keys(row,["sourceReadId","rowIndex","refundPostingId","reason"])||!uuid(row.sourceReadId)||!integer(row.rowIndex,9)||!uuid(row.refundPostingId)||!text(row.reason,240,3))return false;const identity=row.sourceReadId+":"+row.rowIndex;if(positions.has(identity)||refunds.has(row.refundPostingId))return false;positions.add(identity);refunds.add(row.refundPostingId);}
  return true;
}
export async function sendSettlementPreparation(attempt:SettlementPreparationAttempt,csrf:string):Promise<SettlementPreparation>{
  if(!csrf)throw new SettlementPreparationWriteError("rejected","csrf");
  let command:Record<string,unknown>,expected:Record<string,unknown>;
  try{
    scope(attempt.schoolId,attempt.requestId);if(!uuid(attempt.clientRequestId)||typeof attempt.body!=="string"||attempt.body.length>6000000||typeof attempt.expected!=="string"||attempt.expected.length>8192)throw new Error();
    command=JSON.parse(attempt.body) as Record<string,unknown>;expected=JSON.parse(attempt.expected) as Record<string,unknown>;
    if(JSON.stringify(command)!==attempt.body||command.schoolId!==attempt.schoolId||command.clientRequestId!==attempt.clientRequestId)throw new Error();
    if(attempt.kind==="PREPARE"){
      if(attempt.preparationId!==null||!validPreparationCommand(command)||!keys(expected,["bankEvidenceId","bankAmount","postingOn","previousPreparationId"])||expected.bankEvidenceId!==command.bankEvidenceId||expected.postingOn!==command.postingOn||expected.previousPreparationId!==command.previousPreparationId||!money(expected.bankAmount,false))throw new Error();
    }else if(attempt.kind==="ABANDON"){
      if(!uuid(attempt.preparationId)||!keys(command,["schoolId","clientRequestId","reason","confirmedReleaseRejectedPreparation"])||command.confirmedReleaseRejectedPreparation!==true||!text(command.reason,240,3))throw new Error();
      const previous=readSettlementPreparation(expected,attempt.requestId);if(previous.id!==attempt.preparationId||previous.status!=="REJECTED")throw new Error();
    }else throw new Error();
  }catch{throw new SettlementPreparationWriteError("rejected","terms");}
  const url=base+path+(attempt.kind==="PREPARE"?"/requests/"+attempt.requestId+"/preparations":"/preparations/"+attempt.preparationId+"/abandonment");
  let response:Response;try{response=await fetch(url,{method:"POST",credentials:"include",cache:"no-store",headers:{"Content-Type":"application/json","X-CSRF-TOKEN":csrf},body:attempt.body});}catch{throw new SettlementPreparationWriteError("unknown","unknown");}
  if(!response.ok){let code:SettlementPreparationErrorCode=response.status===401?"session":response.status===403?"scope":"rejected";try{const value:unknown=await response.json();if(value&&typeof value==="object"&&"code"in value&&typeof value.code==="string")code=errors[value.code]??code;}catch{}const unknown=response.status>=500||[408,429].includes(response.status);throw new SettlementPreparationWriteError(unknown?"unknown":"rejected",unknown?"unknown":code);}
  try{
    let value:unknown=await response.json();
    if(attempt.kind==="ABANDON"){if(!keys(value,["clientRequestId","preparation"])||value.clientRequestId!==attempt.clientRequestId)throw new Error();value=value.preparation;}
    const result=readSettlementPreparation(value,attempt.requestId);
    if(attempt.kind==="PREPARE"){if(result.clientRequestId!==attempt.clientRequestId||result.bankEvidenceId!==expected.bankEvidenceId||result.bankAmount!==expected.bankAmount||result.postingOn!==expected.postingOn||result.previousPreparationId!==expected.previousPreparationId)throw new Error();}
    else if(result.id!==attempt.preparationId||result.status!=="ABANDONED"||result.journalId!==expected.journalId||result.rootId!==expected.rootId||result.clientRequestId!==expected.clientRequestId)throw new Error();
    return result;
  }catch{throw new SettlementPreparationWriteError("unknown","unknown");}
}
