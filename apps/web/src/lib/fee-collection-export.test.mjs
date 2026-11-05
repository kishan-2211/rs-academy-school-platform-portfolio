import assert from "node:assert/strict";
import test from "node:test";
import { collectionMethods,collectionRegisterErrors,readCollectionExport,loadCollectionExport } from "./fee-collection-register.ts";
import { collectionExportCsv,prepareCollectionExport,releaseCollectionExport } from "./fee-collection-export.ts";
import { collectionExportHtml } from "./fee-collection-print-document.ts";
import { createElement, Fragment } from "react";
import { renderToStaticMarkup } from "react-dom/server";

const school="75000000-0000-4000-8000-000000000001";
const filter={schoolId:school,from:"2026-10-01",to:"2026-10-16",currencyCode:"INR",paymentMethod:"",query:""};
const zero=()=>({collectionCount:0,grossCollections:0,reversalCount:0,reversalAmount:0,netCollections:0,refundCount:0,refundAmount:0});
const row=index=>({id:`75000000-0000-4000-8000-${String(index+1).padStart(12,"0")}`,kind:"COLLECTION",paymentId:school,accountId:school,learnerId:school,learnerName:"Synthetic परीक्षण",admissionNumber:"SYN-1",gradeCode:"VI",sessionName:"Synthetic session",receiptNumber:`SYN-${index}`,paymentMethod:"CASH",receiptStatus:"POSTED",amount:1.25,postingOn:"2026-10-02",recordedAt:"2026-10-03T10:00:00Z",timing:{originalReceivedOn:"2026-10-01",actualReceivedAt:null,receivedTimePrecision:"DATE_ONLY",schoolTimeZone:null}});
const report=(count=26)=>{
 const summary={collectionCount:count,grossCollections:count*1.25,reversalCount:0,reversalAmount:0,netCollections:count*1.25,refundCount:0,refundAmount:0};
 return {...filter,paymentMethod:null,query:null,schoolName:"Synthetic school",generatedAt:"2026-10-16T10:00:00Z",total:count,summary,tenderTotals:collectionMethods.map(paymentMethod=>({paymentMethod,summary:paymentMethod==="CASH"?{...summary}:zero()})),items:Array.from({length:count},(_,index)=>row(index))};
};
const t=(key,values={})=>key.replace(/\{(\w+)\}/g,(_,key)=>String(values[key]??`{${key}}`));
function parseCsv(csv){
 const rows=[];let row=[],cell="",quoted=false;
 for(let index=1;index<csv.length;index++){
  const char=csv[index];
  if(char==='"'){if(quoted&&csv[index+1]==='"'){cell+='"';index++;}else quoted=!quoted;}
  else if(char===","&&!quoted){row.push(cell);cell="";}
  else if(char==="\r"&&csv[index+1]==="\n"&&!quoted){row.push(cell);rows.push(row);row=[];cell="";index++;}
  else cell+=char;
 }
 assert.equal(quoted,false);assert.equal(cell,"");return rows;
}
test("complete snapshots accept more than a display page but reject truncation, foreign scope and mismatched row money",()=>{
 const data=report(101);assert.equal(readCollectionExport(data,filter).items.length,101);
 for(const changed of[{...data,items:data.items.slice(0,25)},{...data,total:10001},{...data,schoolId:"foreign"},{...data,generatedAt:"invalid"},{...data,schoolName:""}])assert.throws(()=>readCollectionExport(changed,filter));
 const corrupt=report(1);corrupt.items[0].amount=1.24;assert.throws(()=>readCollectionExport(corrupt,filter));
 assert.equal(readCollectionExport(report(0),filter).total,0);
});
test("CSV is rectangular with complete-filter and all-method summaries, UTF-8 BOM, escaped quotes and honest date-only timing",()=>{
 const data=report(2);data.items[0].learnerName='Synthetic, "परीक्षण"';
 const csv=collectionExportCsv(readCollectionExport(data,filter)),rows=parseCsv(csv);
 assert.equal(csv.charCodeAt(0),0xfeff);assert.equal(rows.length,9);assert.ok(rows.every(row=>row.length===33));
 assert.equal(rows[1][7],"SUMMARY");assert.equal(rows[1][10],"2.50");assert.equal(rows[2][7],"METHOD");assert.equal(rows[7][17],data.items[0].learnerName);
 assert.equal(rows[7][22],"2026-10-01");assert.equal(rows[7][23],"");assert.equal(rows[7][24],"DATE_ONLY");assert.equal(rows[7][26],"2026-10-03T10:00:00Z");
 assert.equal(parseCsv(collectionExportCsv(report(0))).length,7);
});
test("untrusted spreadsheet formula, whitespace and control prefixes are neutralized while negative financial numbers stay numeric",()=>{
 for(const name of["=1+1","+SUM(A1:A2)","-1+2","@SUM(A1)","  =1+1","\t=1+1","\ufeff=1+1","\u200b=1+1","Synthetic\r\n=1+1"]){
  const data=report(1);data.schoolName=name;data.items[0].learnerName=name;
  const rows=parseCsv(collectionExportCsv(readCollectionExport(data,filter)));assert.equal(rows[1][0],"'"+name);assert.equal(rows[7][17],"'"+name);
 }
 const data=report(1),summary={collectionCount:0,grossCollections:0,reversalCount:1,reversalAmount:1.25,netCollections:-1.25,refundCount:0,refundAmount:0};
 data.summary=summary;data.tenderTotals[0].summary=summary;data.items[0].kind="REVERSAL";data.items[0].receiptStatus="REVERSED";
 const rows=parseCsv(collectionExportCsv(readCollectionExport(data,filter)));assert.equal(rows[1][13],"-1.25");assert.equal(rows[7][27],"-1.25");
});

test("refund-only CSV and print retain exact partial negative amount and distinguish original receipt from refund processing",()=>{
 const data=report(1),summary={...zero(),refundCount:1,refundAmount:0.75,netCollections:-0.75};
 data.summary=summary;data.tenderTotals=collectionMethods.map(paymentMethod=>({paymentMethod,summary:paymentMethod==="ONLINE_PROVIDER"?summary:zero()}));
 data.items[0]={...data.items[0],kind:"REFUND",paymentMethod:"ONLINE_PROVIDER",amount:0.75,postingOn:"2026-10-12",recordedAt:"2026-10-12T10:00:00Z",
  refundTiming:{processedOn:"2026-10-10",processedAt:"2026-10-10T09:00:00.123456789Z",schoolTimeZone:"Asia/Kolkata"}};
 const checked=readCollectionExport(data,filter),rows=parseCsv(collectionExportCsv(checked));
 assert.equal(rows[1][13],"-0.75");assert.equal(rows[1][28],"1");assert.equal(rows[1][29],"0.75");
 assert.equal(rows[7][14],"REFUND");assert.equal(rows[7][22],"2026-10-01");assert.equal(rows[7][27],"-0.75");
 assert.equal(rows[7][30],"2026-10-10");assert.equal(rows[7][31],"2026-10-10T09:00:00.123456789Z");
 const html=collectionExportHtml(checked,"en",t);assert.match(html,/Refund movement/);assert.match(html,/Refund processing evidence/);assert.match(html,/2026-10-10T09:00:00\.123456789Z/);assert.match(html,/−INR 0\.75/);
 const wrong={...data,summary:{...summary,refundAmount:1,netCollections:-1}};assert.throws(()=>readCollectionExport(wrong,filter));
});
test("printable full-range document escapes configured and authored text, retains exact received instants and contains no executable content or external assets",()=>{
 const data=report(26);data.schoolName='<img src="https://synthetic.example.test" onerror="alert(1)">';data.items[0].learnerName="<script>alert('synthetic')</script>";
 data.items[0].timing={originalReceivedOn:"2026-10-01",actualReceivedAt:"2026-10-01T10:00:00.123456789Z",receivedTimePrecision:"INSTANT",schoolTimeZone:"Asia/Kolkata"};
 const html=collectionExportHtml(readCollectionExport(data,filter),"hi",t);
 assert.match(html,/<html lang="hi">/);assert.match(html,/&lt;img/);assert.match(html,/&lt;script/);assert.match(html,/123456789Z/);assert.match(html,/26 movements/);
 assert.doesNotMatch(html,/<script|<img|<link|<form|<iframe|onclick=|javascript:/i);assert.match(html,/default-src &#x27;none&#x27;/);assert.match(html,/Date only — received time was not recorded/);
 assert.match(html,/@media print/);assert.match(html,/table-header-group/);assert.match(html,/SYN-25/);assert.match(html,/Keep downloaded school records private/);
});
test("prepared CSV and printable resources are lazy, bounded to one snapshot and revoked on release or partial failure",async t=>{
 const blobs=[],revoked=[];
 t.mock.method(URL,"createObjectURL",blob=>{blobs.push(blob);return `blob:synthetic-${blobs.length}`;});t.mock.method(URL,"revokeObjectURL",url=>revoked.push(url));
 const prepared=await prepareCollectionExport(report(0),"en",(key)=>key);assert.equal(blobs.length,2);assert.equal(prepared.filename,"fee-collections-2026-10-01-2026-10-16-INR.csv");
 releaseCollectionExport(prepared);assert.deepEqual(revoked,["blob:synthetic-1","blob:synthetic-2"]);releaseCollectionExport(null);
 let calls=0;t.mock.method(URL,"createObjectURL",()=>{if(++calls===2)throw new Error("Synthetic blob unavailable");return "blob:partial";});
 await assert.rejects(prepareCollectionExport(report(0),"en",key=>key));assert.equal(revoked.at(-1),"blob:partial");
});

test("print text keeps query, translations, quotes and entities as literal text without executable structure",()=>{
 const payload='</title></p><img src=x onerror="alert(1)"><script>synthetic</script>&amp;\'"';
 const adversarialFilter={...filter,query:payload};
 const data=report(1);data.query=payload;data.schoolName=payload;data.items[0].learnerName=payload;
 const checked=readCollectionExport(data,adversarialFilter),translated=key=>key==="Dated collection register"||key==="Collection method"?payload:t(key);
 const html=collectionExportHtml(checked,"hi\" onload=\"synthetic",translated);
 const literal=renderToStaticMarkup(createElement(Fragment,null,payload));
 assert.equal(html.split(literal).length-1,8,"Keep title, heading, school, filters, both table headings and learner text encoded exactly once");
 assert.match(html,/&amp;amp;/);assert.match(html,/&#x27;&quot;/);assert.match(html,/<html lang="en">/);
 assert.doesNotMatch(html,/<img\b|<script\b|<iframe\b|<form\b|<[^>]*\s(?:onload|onerror)=/i);
 assert.match(html,/http-equiv="Content-Security-Policy"/);assert.match(html,/base-uri &#x27;none&#x27;; form-action &#x27;none&#x27;/);
});

test("abort before or during lazy print preparation creates no downloadable private resources",async t=>{
 const blobs=[];t.mock.method(URL,"createObjectURL",blob=>{blobs.push(blob);return"blob:unexpected";});
 const alreadyAborted=new AbortController();alreadyAborted.abort();
 await assert.rejects(prepareCollectionExport(report(0),"en",key=>key,alreadyAborted.signal),{name:"AbortError"});
 const cancelled=new AbortController(),pending=prepareCollectionExport(report(0),"en",key=>key,cancelled.signal);cancelled.abort();
 await assert.rejects(pending,{name:"AbortError"});assert.equal(blobs.length,0);
 const rendering=new AbortController();
 await assert.rejects(prepareCollectionExport(report(0),"en",key=>{rendering.abort();return key;},rendering.signal),{name:"AbortError"});
 assert.equal(blobs.length,0);
});
test("export fetch uses an explicit authenticated no-store read with no paging, provider calls or diagnostic leakage",async t=>{
 const signal=new AbortController().signal;
 t.mock.method(globalThis,"fetch",async(url,options)=>{const parsed=new URL(url);assert.equal(parsed.pathname,"/api/v1/finance/collections/export");assert.equal(parsed.searchParams.has("page"),false);assert.equal(options.credentials,"include");assert.equal(options.cache,"no-store");assert.equal(options.signal,signal);return Response.json(report());});
 assert.equal((await loadCollectionExport(filter,signal)).total,26);
 for(const [status,body,copy]of[[400,{code:"COLLECTION_REGISTER_EXPORT_TOO_LARGE"},collectionRegisterErrors.exportLimit],[400,{message:"SYNTHETIC PRIVATE SQL"},collectionRegisterErrors.filters],[401,{},collectionRegisterErrors.session],[403,{},collectionRegisterErrors.scope],[500,{message:"SYNTHETIC PRIVATE SQL"},collectionRegisterErrors.unavailable]]){
  t.mock.method(globalThis,"fetch",async()=>Response.json(body,{status}));await assert.rejects(loadCollectionExport(filter,signal),{message:copy});
 }
});
