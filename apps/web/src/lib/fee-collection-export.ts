import type { CollectionExport, CollectionSummary } from "./fee-collection-register";

export type CollectionExportTranslate = (key: string, values?: Record<string,string | number>) => string;
type RecordRow = Record<string,string>;
const columns: [string,string,boolean?][] = [
  ["school","School"],["from","Posting range from"],["to","Posting range to"],["currency","Currency"],
  ["filter","Method filter"],["query","Search filter"],["generatedAt","Snapshot generated at (UTC)"],
  ["type","Record type"],["method","Collection method"],["count","Collection count",true],["gross","Gross collections",true],
  ["reversalCount","Reversal count",true],["reversed","Reversal amount",true],["net","Net collections",true],
  ["movement","Movement"],["receipt","Receipt"],["status","Current receipt status"],["learner","Learner"],
  ["admission","Admission number"],["grade","Class"],["session","Session"],["posting","Accounting posting date"],
  ["original","Original received date"],["actual","Exact received instant"],["precision","Received time precision"],
  ["zone","Received time zone"],["recorded","Movement recorded at (UTC)"],["amount","Signed movement amount",true],
  ["refundCount","Refund count",true],["refunded","Completed refund amount",true],
  ["refundProcessedOn","Refund processed date"],["refundProcessedAt","Exact refund processed instant"],["refundZone","Refund time zone"],
];
// All money has already passed exact integer-paise validation. Preserve its decimal
// spelling, including negative net/reversal values, without floating-point arithmetic.
export const collectionDecimal=(amount:number):string=>{const [whole,fraction=""]=String(amount).split(".");return `${whole}.${fraction.padEnd(2,"0")}`;};
function csvText(value:string):string {
  const unsafe=/^[\s\u0000-\u001f\u007f-\u009f\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]*[=+\-@]/u.test(value) || /[\u0000-\u001f\u007f-\u009f]/u.test(value);
  return `"${(unsafe ? "'"+value : value).replaceAll('"','""')}"`;
}
function summaryFields(value:CollectionSummary):RecordRow {
  return {count:String(value.collectionCount),gross:collectionDecimal(value.grossCollections),reversalCount:String(value.reversalCount),reversed:collectionDecimal(value.reversalAmount),net:collectionDecimal(value.netCollections),refundCount:String(value.refundCount),refunded:collectionDecimal(value.refundAmount)};
}
/** Rectangular UTF-8 CSV: one complete-filter SUMMARY, five METHOD rows, then movements. */
export function collectionExportCsv(data:CollectionExport):string {
  const common:RecordRow={school:data.schoolName,from:data.from,to:data.to,currency:data.currencyCode,filter:data.paymentMethod??"ALL",query:data.query??"",generatedAt:data.generatedAt};
  const rows:RecordRow[]=[{...common,type:"SUMMARY",...summaryFields(data.summary)},...data.tenderTotals.map(item=>({...common,type:"METHOD",method:item.paymentMethod,...summaryFields(item.summary)})),
    ...data.items.map(item=>({...common,type:"MOVEMENT",method:item.paymentMethod,movement:item.kind,receipt:item.receiptNumber,status:item.receiptStatus,
      learner:item.learnerName,admission:item.admissionNumber??"",grade:item.gradeCode,session:item.sessionName,posting:item.postingOn,
      original:item.timing.originalReceivedOn,actual:item.timing.actualReceivedAt??"",precision:item.timing.receivedTimePrecision,zone:item.timing.schoolTimeZone??"",
      recorded:item.recordedAt,amount:(item.kind!=="COLLECTION"?"-":"")+collectionDecimal(item.amount),
      refundProcessedOn:item.refundTiming?.processedOn??"",refundProcessedAt:item.refundTiming?.processedAt??"",refundZone:item.refundTiming?.schoolTimeZone??""}))];
  return "\ufeff"+columns.map(([,label])=>csvText(label)).join(",")+"\r\n"+rows.map(row=>columns.map(([key,,numeric])=>numeric?(row[key]??""):csvText(row[key]??"")).join(",")).join("\r\n")+"\r\n";
}
export type PreparedCollectionExport = { csvUrl:string; printUrl:string; filename:string; generatedAt:string; total:number };
export async function prepareCollectionExport(data:CollectionExport, locale:string, t:CollectionExportTranslate, signal?:AbortSignal):Promise<PreparedCollectionExport> {
  signal?.throwIfAborted();
  // Native React text encoding is loaded only after the user's explicit export
  // request. The renderer is never needed by ordinary collection-register reads.
  const { collectionExportHtml } = await import("./fee-collection-print-document");
  signal?.throwIfAborted();
  const html = collectionExportHtml(data,locale,t);
  signal?.throwIfAborted();
  const csvUrl=URL.createObjectURL(new Blob([collectionExportCsv(data)],{type:"text/csv;charset=utf-8"}));
  try {
    return {csvUrl,printUrl:URL.createObjectURL(new Blob([html],{type:"text/html;charset=utf-8"})),filename:`fee-collections-${data.from}-${data.to}-${data.currencyCode}.csv`,generatedAt:data.generatedAt,total:data.total};
  } catch(error) { URL.revokeObjectURL(csvUrl); throw error; }
}
export function releaseCollectionExport(prepared:PreparedCollectionExport|null):void {
  if (prepared) { URL.revokeObjectURL(prepared.csvUrl);URL.revokeObjectURL(prepared.printUrl); }
}
