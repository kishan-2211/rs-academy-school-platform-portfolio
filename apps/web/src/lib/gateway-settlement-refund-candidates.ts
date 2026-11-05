import { reportedSettlementMoney } from "./gateway-settlements";
import type { SettlementSourceReviewRow } from "./gateway-settlement-source-review";
export type SettlementRefundCandidateRow = Pick<SettlementSourceReviewRow, "sourceReadId" | "rowIndex" | "event">;

/** Read-only historical candidates; never an event association or posting authority. */
export const settlementRefundCandidateReasons = ["UNSUPPORTED_PROVIDER","REFUND_EVENT_REQUIRED","UNSUPPORTED_DIRECTION","UNSUCCESSFUL_EVENT","UNSUPPORTED_CURRENCY","FRACTIONAL_PAISE","NON_POSITIVE_AMOUNT","MISSING_PAYMENT_REFERENCES","ORIGINAL_RECEIPT_UNRESOLVED","ORIGINAL_PAYMENT_SOURCE_AMBIGUOUS"] as const;
export type SettlementRefundCandidateReason = typeof settlementRefundCandidateReasons[number];
export type SettlementRefundCandidate = { refundPostingId:string;refundId:string;postedOn:string;amount:string;currencyCode:"INR";processedAt:string|null;accountingState:"POSTED_SOURCE_PROVEN"|"ACCOUNTING_REVIEW_REQUIRED";accountingJournalId:string|null;referenceMention:"MENTIONED"|"NOT_MENTIONED"|"NOT_REPORTED" };
export type SettlementRefundCandidates = { schoolId:string;requestId:string;sourceReadId:string;rowIndex:number;settlementReference:string;reviewStage:"HISTORICAL_REFUND_CANDIDATES_ONLY";state:"CANDIDATE_CONTEXT"|"REVIEW_REQUIRED";eventReference:string;reportedAmount:string;currencyCode:string;receiptNumber:string|null;reviewReasons:SettlementRefundCandidateReason[];page:number;pageSize:number;hasMore:boolean;items:SettlementRefundCandidate[] };
export type SettlementRefundCandidateErrorCode = "unavailable"|"session"|"scope"|"incomplete"|"filters";
export class SettlementRefundCandidateError extends Error {
  readonly code:SettlementRefundCandidateErrorCode;
  constructor(code:SettlementRefundCandidateErrorCode) { super(`SETTLEMENT_REFUND_CANDIDATES_${code.toUpperCase()}`);this.name="SettlementRefundCandidateError";this.code=code; }
}
export function settlementRefundCandidateErrorCode(value:unknown):SettlementRefundCandidateErrorCode { return value instanceof SettlementRefundCandidateError?value.code:"unavailable"; }

const base=process.env.NEXT_PUBLIC_API_URL??"http://localhost:8080";
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const reference=/^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/;
const isUuid=(value:unknown):value is string=>typeof value==="string"&&uuid.test(value);
const isReference=(value:unknown):value is string=>typeof value==="string"&&reference.test(value);
const integer=(value:unknown,max:number):value is number=>typeof value==="number"&&Number.isSafeInteger(value)&&value>=0&&value<=max;
const keys=(value:unknown,expected:readonly string[]):boolean=>value!==null&&typeof value==="object"&&!Array.isArray(value)&&Object.keys(value).length===expected.length&&Object.keys(value).every(key=>expected.includes(key));
const fail=():never=>{throw new SettlementRefundCandidateError("unavailable");};
function date(value:unknown):value is string {
  if(typeof value!=="string"||!/^\d{4}-\d{2}-\d{2}$/.test(value)||value<"2000-01-01"||value>"2100-12-31")return false;
  const parsed=new Date(`${value}T00:00:00Z`);return Number.isFinite(parsed.getTime())&&parsed.toISOString().slice(0,10)===value;
}
function instant(value:unknown):value is string {
  if(typeof value!=="string"||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3}(?:\d{3}){0,2})?Z$/.test(value)||!date(value.slice(0,10)))return false;
  const parsed=new Date(value);return Number.isFinite(parsed.getTime())&&parsed.toISOString().slice(0,19)===value.slice(0,19);
}
function scaled(value:string):bigint { const negative=value.startsWith("-"),[whole,fraction=""]=(negative?value.slice(1):value).split(".");return (BigInt(whole!)*100000000n+BigInt(fraction.padEnd(8,"0")))*(negative?-1n:1n); }
function scope(schoolId:string,requestId:string,settlementReference:string,row:SettlementRefundCandidateRow,page:number):void {
  if(!isUuid(schoolId)||!isUuid(requestId)||!isReference(settlementReference)||!row||!isUuid(row.sourceReadId)||!integer(row.rowIndex,9)||!row.event||row.event.settlementReference!==settlementReference||!isReference(row.event.eventReference)||!reportedSettlementMoney(row.event.reportedAmount)||typeof row.event.currencyCode!=="string"||!/^[A-Z]{3}$/.test(row.event.currencyCode)||!integer(page,10000))throw new SettlementRefundCandidateError("filters");
}
export function readSettlementRefundCandidates(value:unknown,schoolId:string,requestId:string,settlementReference:string,row:SettlementRefundCandidateRow,page:number):SettlementRefundCandidates {
  scope(schoolId,requestId,settlementReference,row,page);
  if(!keys(value,["schoolId","requestId","sourceReadId","rowIndex","settlementReference","reviewStage","state","eventReference","reportedAmount","currencyCode","receiptNumber","reviewReasons","page","pageSize","hasMore","items"]))return fail();
  const result=value as SettlementRefundCandidates;
  if(result.schoolId!==schoolId||result.requestId!==requestId||result.sourceReadId!==row.sourceReadId||result.rowIndex!==row.rowIndex||result.settlementReference!==settlementReference||result.reviewStage!=="HISTORICAL_REFUND_CANDIDATES_ONLY"||result.eventReference!==row.event.eventReference||result.reportedAmount!==row.event.reportedAmount||result.currencyCode!==row.event.currencyCode||result.page!==page||result.pageSize!==25||typeof result.hasMore!=="boolean"||!Array.isArray(result.items)||result.items.length>25||result.hasMore&&result.items.length!==25||!Array.isArray(result.reviewReasons)||result.reviewReasons.length>settlementRefundCandidateReasons.length||new Set(result.reviewReasons).size!==result.reviewReasons.length||!result.reviewReasons.every(reason=>settlementRefundCandidateReasons.includes(reason)))return fail();
  if(result.receiptNumber!==null&&(typeof result.receiptNumber!=="string"||!result.receiptNumber.trim()||result.receiptNumber.length>100||/[\u0000-\u001f\u007f-\u009f]/.test(result.receiptNumber)))return fail();
  if(result.state==="REVIEW_REQUIRED") { if(!result.reviewReasons.length||result.items.length||result.hasMore||result.receiptNumber!==null)return fail(); }
  else if(result.state==="CANDIDATE_CONTEXT") {
    if(result.reviewReasons.length||result.receiptNumber===null||row.event.reportedType!=="REFUND"||row.event.reportedDirection!=="DEBIT"||row.event.reportedStatus!=="SUCCESS"||result.currencyCode!=="INR"||!isReference(row.event.orderReference)||!isReference(row.event.paymentReference)||scaled(result.reportedAmount)<=0n||scaled(result.reportedAmount)%1000000n!==0n)return fail();
  } else return fail();
  const postings=new Set<string>(),refunds=new Set<string>();
  for(const item of result.items) {
    if(!keys(item,["refundPostingId","refundId","postedOn","amount","currencyCode","processedAt","accountingState","accountingJournalId","referenceMention"])||!isUuid(item.refundPostingId)||!isUuid(item.refundId)||postings.has(item.refundPostingId)||refunds.has(item.refundId)||!date(item.postedOn)||typeof item.amount!=="string"||!/^(?:0|[1-9]\d{0,8})\.\d{2}$/.test(item.amount)||!reportedSettlementMoney(item.amount)||scaled(item.amount)<=0n||scaled(item.amount)!==scaled(result.reportedAmount)||item.currencyCode!=="INR"||item.processedAt!==null&&!instant(item.processedAt)||!["MENTIONED","NOT_MENTIONED","NOT_REPORTED"].includes(item.referenceMention))return fail();
    if(item.accountingState==="POSTED_SOURCE_PROVEN") { if(!isUuid(item.accountingJournalId))return fail(); }
    else if(item.accountingState==="ACCOUNTING_REVIEW_REQUIRED") { if(item.accountingJournalId!==null)return fail(); }
    else return fail();
    postings.add(item.refundPostingId);refunds.add(item.refundId);
  }
  return result;
}
export async function loadSettlementRefundCandidates(schoolId:string,requestId:string,settlementReference:string,row:SettlementRefundCandidateRow,page:number,signal:AbortSignal):Promise<SettlementRefundCandidates> {
  scope(schoolId,requestId,settlementReference,row,page);
  const params=new URLSearchParams({schoolId,sourceReadId:row.sourceReadId,rowIndex:String(row.rowIndex),page:String(page),pageSize:"25"});
  const response=await fetch(`${base}/api/v1/finance/gateway/settlements/requests/${requestId}/refund-candidates?${params}`,{credentials:"include",cache:"no-store",signal});
  if(!response.ok)throw new SettlementRefundCandidateError(response.status===401?"session":response.status===403?"scope":response.status===409?"incomplete":response.status===400?"filters":"unavailable");
  let result:unknown;try{result=await response.json();}catch{return fail();}
  return readSettlementRefundCandidates(result,schoolId,requestId,settlementReference,row,page);
}
