import { createElement as element, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import type { CollectionExport, CollectionSummary } from "./fee-collection-register";
import { collectionDecimal, type CollectionExportTranslate } from "./fee-collection-export";

const printStyle = `
  :root{color-scheme:light}body{font:15px/1.5 Arial,"Nirmala UI",sans-serif;color:#1c1917;background:#fff;margin:24px;overflow-wrap:anywhere}h1{font-size:24px}h2{font-size:18px}p{max-width:100ch}.scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;margin:16px 0;font-size:12px}th,td{border:1px solid #78716c;padding:7px;vertical-align:top;text-align:left}th{background:#f5f5f4}thead{display:table-header-group}tr{break-inside:avoid}small{display:block}.notice{border:1px solid #78716c;padding:12px}@media print{@page{size:landscape;margin:12mm}body{margin:0;font-size:10pt}.notice{display:none}.scroll{overflow:visible}table{font-size:8pt}h1,h2{break-after:avoid}}
`;

/** Standalone private print document. All variable content is React text, never HTML. */
export function collectionExportHtml(data:CollectionExport, locale:string, t:CollectionExportTranslate):string {
  const method=(value:string)=>t(value==="CASH"?"Cash":value==="SCHOOL_UPI"?"School UPI — verified bank credit":value==="BANK_TRANSFER"?"Bank transfer — verified bank credit":value==="CHEQUE"?"Cheque":"Online gateway — posted receipts");
  const money=(value:number)=>`${data.currencyCode} ${collectionDecimal(value)}`;
  const cell=(...children:ReactNode[])=>element("td",null,...children);
  const small=(...children:ReactNode[])=>element("small",null,...children);
  const summary=(value:CollectionSummary)=>[
    cell(value.collectionCount),cell(money(value.grossCollections)),cell(value.reversalCount),
    cell(money(value.reversalAmount)),cell(value.refundCount),cell(money(value.refundAmount)),cell(money(value.netCollections)),
  ];
  const headings=(keys:string[])=>element("thead",null,element("tr",null,...keys.map(key=>element("th",{scope:"col",key},t(key)))));
  const document=element("html",{lang:locale==="hi"?"hi":"en"},
    element("head",null,
      element("meta",{charSet:"utf-8"}),
      element("meta",{name:"viewport",content:"width=device-width,initial-scale=1"}),
      element("meta",{name:"referrer",content:"no-referrer"}),
      element("meta",{httpEquiv:"Content-Security-Policy",content:"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"}),
      element("title",null,t("Dated collection register")),element("style",null,printStyle)),
    element("body",null,element("main",null,
      element("p",{className:"notice"},t("Use your browser’s Print command to print or save this complete report as PDF. Keep downloaded school records private.")),
      element("h1",null,data.schoolName," · ",t("Dated collection register")),
      element("p",null,t("Posting dates {from} to {to} · {currency} · {count} movements",{from:data.from,to:data.to,currency:data.currencyCode,count:data.total})),
      element("p",null,t("Collection method"),": ",data.paymentMethod?method(data.paymentMethod):t("All collection methods"),element("br"),
        t("Learner, admission or receipt"),": ",data.query??"—",element("br"),t("Snapshot generated at {time}",{time:data.generatedAt})),
      element("p",null,t("Filter by accounting posting dates. Original received times are shown separately; online collections are not bank settlements.")),
      element("div",{className:"scroll"},element("table",null,
        element("caption",null,t("Totals by collection method")),
        headings(["Collection method","Collection count","Gross fee collections","Reversal count","Receipt reversals","Refund count","Completed gateway refunds","Net collection movements"]),
        element("tbody",null,
          element("tr",null,element("th",{scope:"row"},t("Entire applied filter")),...summary(data.summary)),
          ...data.tenderTotals.map(item=>element("tr",{key:item.paymentMethod},element("th",{scope:"row"},method(item.paymentMethod)),...summary(item.summary)))))),
      element("div",{className:"scroll"},element("table",null,
        element("caption",null,t("Collection movements")),
        headings(["Receipt","Learner, admission or receipt","Collection method","Accounting posting date","Original received evidence","Refund processing evidence","Movement recorded at","Signed movement amount"]),
        element("tbody",null,...data.items.map(item=>element("tr",{key:`${item.kind}:${item.id}`},
          cell(item.receiptNumber,small(t(item.kind==="REFUND"?"Refund movement":item.kind==="REVERSAL"?"Reversal movement":"Collection movement")," · ",t(item.receiptStatus==="REVERSED"?"Reversed":"Posted"))),
          cell(item.learnerName,small(item.admissionNumber??"—"," · ",item.gradeCode," · ",item.sessionName)),
          cell(method(item.paymentMethod)),cell(item.postingOn),
          cell(item.timing.originalReceivedOn,small(item.timing.actualReceivedAt??t("Date only — received time was not recorded")," · ",item.timing.schoolTimeZone??"—")),
          item.refundTiming?cell(item.refundTiming.processedOn,small(item.refundTiming.processedAt," · ",item.refundTiming.schoolTimeZone)):cell("—"),
          cell(item.recordedAt),cell(item.kind!=="COLLECTION"?"−":"",money(item.amount))))))),
      data.total===0?element("p",null,t("No collection movements match this page and filter.")):null)));
  return "<!doctype html>"+renderToStaticMarkup(document);
}
