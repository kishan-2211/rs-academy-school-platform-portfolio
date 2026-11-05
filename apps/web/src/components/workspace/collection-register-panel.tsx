"use client";

import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { FeeReceiptTimingText } from "@/components/fee-receipt-timing-text";
import { ReceiptPdfActions } from "@/components/receipt-pdf-actions";
import { collectionFilter, collectionMethods, collectionRegisterError, loadCollectionRegister, loadCollectionExport, type CollectionFilter, type CollectionRegister } from "@/lib/fee-collection-register";
import { prepareCollectionExport, releaseCollectionExport, type PreparedCollectionExport } from "@/lib/fee-collection-export";
import styles from "./collection-register-panel.module.css";

export function CollectionRegisterPanel({ schoolId, schoolToday, schoolCurrency, currencies }: { schoolId: string; schoolToday: string; schoolCurrency: string; currencies: string[] }) {
  return <ScopedRegister key={schoolId} schoolId={schoolId} schoolToday={schoolToday} schoolCurrency={schoolCurrency} currencies={currencies} />;
}
function ScopedRegister({ schoolId, schoolToday, schoolCurrency, currencies }: { schoolId: string; schoolToday: string; schoolCurrency: string; currencies: string[] }) {
  const t = useT("fees"), { locale } = useLanguage(), id = useId();
  const [draft,setDraft] = useState<CollectionFilter>({schoolId,from:`${schoolToday.slice(0,8)}01`,to:schoolToday,currencyCode:schoolCurrency,paymentMethod:"",query:""});
  const initial = useRef(draft), request = useRef<AbortController | null>(null);
  const [data,setData] = useState<CollectionRegister | null>(null), [busy,setBusy] = useState(true), [error,setError] = useState("");
  const [report,setReport] = useState<PreparedCollectionExport | null>(null), preparedResource=useRef<PreparedCollectionExport | null>(null);
  const clearExport=useCallback(()=>{releaseCollectionExport(preparedResource.current);preparedResource.current=null;setReport(null);},[]);
  const load = useCallback(async (filter: CollectionFilter, page: number) => {
    if (request.current) return;
    const controller = new AbortController(); request.current = controller;
    // Keep the applied range and pagination control mounted while reading.
    // New results replace it atomically; errors clear private stale records.
    clearExport(); setBusy(true); setError("");
    try {
      const checked = collectionFilter(filter,page);
      const next = await loadCollectionRegister(checked,page,controller.signal);
      if (!controller.signal.aborted) setData(next);
    } catch(reason) { if (!controller.signal.aborted) { setData(null); setError(collectionRegisterError(reason)); } }
    finally { if (request.current === controller) request.current = null; if (!controller.signal.aborted) setBusy(false); }
  },[clearExport]);
  useEffect(() => {
    let mounted=true;
    void Promise.resolve().then(()=>{if(mounted)return load(initial.current,0);});
    return () => { mounted=false; const active=request.current; active?.abort(); if(request.current===active)request.current=null; releaseCollectionExport(preparedResource.current);preparedResource.current=null; };
  },[load]);
  const prepare=useCallback(async()=>{
    if(request.current || !data) return;
    const controller=new AbortController();request.current=controller;
    clearExport();setBusy(true);setError("");
    try {
      const filter:CollectionFilter={schoolId,from:data.from,to:data.to,currencyCode:data.currencyCode,paymentMethod:data.paymentMethod??"",query:data.query??""};
      const snapshot=await loadCollectionExport(filter,controller.signal);
      if(!controller.signal.aborted){
        const result=await prepareCollectionExport(snapshot,locale,t,controller.signal);
        if(controller.signal.aborted || request.current!==controller)releaseCollectionExport(result);
        else {preparedResource.current=result;setReport(result);}
      }
    } catch(reason){if(!controller.signal.aborted){setData(null);setError(collectionRegisterError(reason));}}
    finally{if(request.current===controller)request.current=null;if(!controller.signal.aborted)setBusy(false);}
  },[data,schoolId,locale,t,clearExport]);
  const money = (amount: number, currency = data?.currencyCode ?? draft.currencyCode) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", {style:"currency",currency,minimumFractionDigits:2,maximumFractionDigits:2}).format(amount);
  const methodName = (method: string) => t(method === "CASH" ? "Cash" : method === "SCHOOL_UPI" ? "School UPI — verified bank credit" : method === "BANK_TRANSFER" ? "Bank transfer — verified bank credit" : method === "CHEQUE" ? "Cheque" : "Online gateway — posted receipts");
  const applied = (): CollectionFilter => data ? {schoolId,from:data.from,to:data.to,currencyCode:data.currencyCode,paymentMethod:data.paymentMethod ?? "",query:data.query ?? ""} : draft;
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if(!busy)void load({...draft},0); }
  return <section className={styles.root} aria-labelledby={`${id}-heading`} aria-busy={busy}>
    <header><h2 id={`${id}-heading`}>{t("Dated collection register")}</h2><p>{t("Filter by accounting posting dates. Original received times are shown separately; online collections are not bank settlements.")}</p><p>{t("Completed gateway refunds are separate negative movements. Pending refunds and verified holds do not change this register.")}</p></header>
    <form aria-label={t("Collection register filters")} onSubmit={submit}>
      <fieldset><legend>{t("Accounting posting dates")}</legend><div className={styles.filters}>
        <label>{t("From")}<input type="date" value={draft.from} onChange={event=>setDraft({...draft,from:event.target.value})} required /></label>
        <label>{t("To")}<input type="date" value={draft.to} onChange={event=>setDraft({...draft,to:event.target.value})} required /></label>
        <label>{t("Currency")}<select value={draft.currencyCode} onChange={event=>setDraft({...draft,currencyCode:event.target.value})}>{[...new Set([schoolCurrency,...currencies])].filter(value=>/^[A-Z]{3}$/.test(value)).map(value=><option key={value}>{value}</option>)}</select></label>
        <label>{t("Collection method")}<select value={draft.paymentMethod} onChange={event=>setDraft({...draft,paymentMethod:event.target.value as CollectionFilter["paymentMethod"]})}><option value="">{t("All collection methods")}</option>{collectionMethods.map(value=><option value={value} key={value}>{methodName(value)}</option>)}</select></label>
        <label className={styles.search}>{t("Learner, admission or receipt")}<input type="search" value={draft.query} maxLength={100} autoComplete="off" onChange={event=>setDraft({...draft,query:event.target.value})} /></label>
      </div></fieldset>
      <button type="submit" aria-disabled={busy}>{t("Apply collection filters")}</button>
    </form>
    {busy && <p role="status">{t("Reading the collection register…")}</p>}
    {error && <p role="alert" className={styles.error}>{t(error)}</p>}
    {!busy && error && <button type="button" onClick={()=>void load({...draft},0)}>{t("Retry collection register")}</button>}
    {data && <>
      <p className={styles.range} role="status">{t("Posting dates {from} to {to} · {currency} · {count} movements",{from:data.from,to:data.to,currency:data.currencyCode,count:data.total})}</p>
      <div className={styles.summary}><article><span>{t("Gross fee collections")}</span><strong>{money(data.summary.grossCollections)}</strong><small>{data.summary.collectionCount}</small></article><article><span>{t("Receipt reversals")}</span><strong>{money(data.summary.reversalAmount)}</strong><small>{data.summary.reversalCount}</small></article><article><span>{t("Completed gateway refunds")}</span><strong>{money(data.summary.refundAmount)}</strong><small>{data.summary.refundCount}</small></article><article><span>{t("Net collection movements")}</span><strong>{money(data.summary.netCollections)}</strong></article></div>
      <p>{t("Totals cover the entire applied filter, not just this page. A later reversal does not remove the original collection from its original date.")}</p>
      <ul className={styles.tenders} aria-label={t("Totals by collection method")}>{data.tenderTotals.map(item=><li key={item.paymentMethod}><span>{methodName(item.paymentMethod)}</span><span>{t("Gross fee collections")}: {money(item.summary.grossCollections)} · {t("Receipt reversals")}: {money(item.summary.reversalAmount)} · {t("Completed gateway refunds")}: {money(item.summary.refundAmount)} · <strong>{t("Net collection movements")}: {money(item.summary.netCollections)}</strong></span></li>)}</ul>
      <div className={styles.exports}>
        <p>{t("Prepare a fresh complete-range report, not just this page. Up to 10,000 movements; narrow larger ranges. Downloads contain private school records.")}</p>
        <button type="button" aria-disabled={busy} onClick={()=>void prepare()}>{t("Prepare full-range CSV and print")}</button>
        {report && <><p role="status">{t("Complete report ready: {count} movements · {time}",{count:report.total,time:report.generatedAt})}</p><div className={styles.actions}>
          <a href={report.csvUrl} download={report.filename}>{t("Download full-range CSV")}</a>
          <a href={report.printUrl} target="_blank" rel="noopener noreferrer">{t("Open printable report (new tab)")}</a>
        </div></>}
      </div>
      {!data.items.length && <p>{t("No collection movements match this page and filter.")}</p>}
      <ol className={styles.items} aria-label={t("Collection movements")}>{data.items.map(item=><li key={`${item.kind}:${item.id}`}><article>
        <header><div><span>{t(item.kind === "REFUND" ? "Refund movement" : item.kind === "REVERSAL" ? "Reversal movement" : "Collection movement")}</span><h3>{item.receiptNumber}</h3><p>{item.learnerName} · {item.admissionNumber ?? t("Admission number pending")} · {item.gradeCode} · {item.sessionName}</p></div><strong>{item.kind !== "COLLECTION" ? "−" : ""}{money(item.amount)}</strong></header>
        <p>{methodName(item.paymentMethod)} · {t("Current receipt status: {status}",{status:t(item.receiptStatus === "POSTED" ? "Posted" : "Reversed")})}</p>
        <div className={styles.timing}>{item.kind === "REFUND" && <strong>{t("Original receipt evidence — not refund processing time")}</strong>}<FeeReceiptTimingText postingOn={item.postingOn} timing={item.timing} />{item.refundTiming && <p>{t("Gateway refund processed: {date} · {time} · {zone}",{date:item.refundTiming.processedOn,time:item.refundTiming.processedAt,zone:item.refundTiming.schoolTimeZone})}</p>}{item.kind === "REFUND" && <p>{t("Reviewed refund recorded at {time}",{time:item.recordedAt})}</p>}</div>
        <ReceiptPdfActions audience="staff" schoolId={schoolId} paymentId={item.paymentId} receiptNumber={item.receiptNumber} paymentStatus={item.receiptStatus} externallyBusy={busy} />
      </article></li>)}</ol>
      <div className={styles.pages} aria-label={t("Collection register pages")}>
        <button type="button" aria-disabled={busy || data.page===0} onClick={()=>{if(!busy && data.page>0)void load(applied(),data.page-1);}}>{t("Previous collection page")}</button>
        <span>{t("Page {page}",{page:data.page+1})}</span>
        <button type="button" aria-disabled={busy || !data.hasMore || data.page>=10000} onClick={()=>{if(!busy && data.hasMore && data.page<10000)void load(applied(),data.page+1);}}>{t("Next collection page")}</button>
      </div>
    </>}
  </section>;
}
