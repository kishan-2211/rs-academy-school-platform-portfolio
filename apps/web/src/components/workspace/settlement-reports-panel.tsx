"use client";

import { useCallback, useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useLanguage } from "@/components/i18n/language-provider";
import { familyPaymentCsrf } from "@/lib/family-gateway-payments";
import { formatSettlementAmount, loadSettlementQueue, loadSettlementReport, prepareSettlementEvents, prepareSettlementSummary, sendSettlementSync, settlementErrorCode, settlementKinds, settlementSchoolDate, settlementStates, SettlementWriteError, type SettlementAttempt, type SettlementErrorCode, type SettlementFilter, type SettlementQueue, type SettlementReport, type SettlementRequest, type SettlementRow } from "@/lib/gateway-settlements";
import { settlementMessage, type SettlementMessageKey } from "@/lib/i18n/gateway-settlement-messages";
import { SettlementSourceReviewPanel } from "./settlement-source-review-panel";
import { SettlementBankEvidencePanel } from "./settlement-bank-evidence-panel";
import { SettlementPreparationPanel } from "./settlement-preparation-panel";
import styles from "./settlement-reports-panel.module.css";

type Props = { schoolId: string; onGuardChange: (blocked: boolean) => void };
type Detail = { id: string; query: string; page: number };

export function SettlementReportsPanel({ schoolId, onGuardChange }: Props) {
  const { locale } = useLanguage(), language = locale === "hi" ? "hi" : "en", id = useId();
  const t = (key: SettlementMessageKey) => settlementMessage(language,key);
  const [filter,setFilter] = useState<SettlementFilter>({schoolId,status:"ALL",kind:"ALL",query:"",page:0});
  const [queue,setQueue] = useState<SettlementQueue | null>(null);
  const [selectedId,setSelectedId] = useState<string | null>(null);
  const [report,setReport] = useState<SettlementReport | null>(null);
  const [reportQuery,setReportQuery] = useState("");
  const [fromDay,setFromDay] = useState("");
  const [throughDay,setThroughDay] = useState("");
  const [busy,setBusy] = useState(true);
  const [locked,setLocked] = useState(false);
  const [error,setError] = useState<SettlementErrorCode | null>(null);
  const [accessError,setAccessError] = useState<"session" | "scope" | null>(null);
  const [notice,setNotice] = useState(false);
  const [sourceBusy,setSourceBusy] = useState(false);
  const [bankBusy,setBankBusy] = useState(false);
  const [preparationBusy,setPreparationBusy] = useState(false);
  const read = useRef<AbortController | null>(null), lifecycle = useRef(0), writing = useRef(false);
  const attempt = useRef<SettlementAttempt | null>(null), applied = useRef(filter), detail = useRef<Detail | null>(null);
  const unknown = useRef(false);
  const sourceReading = useRef(false);
  const bankReading = useRef(false);
  const preparationReading = useRef(false);
  const authorizationLost = useRef(false);
  const authorizationVersion = useRef(0);
  const feedback = useRef<HTMLParagraphElement | null>(null);
  const accessBlocked = accessError !== null || error === "session" || error === "scope";
  const activeQueue = !accessBlocked && queue?.schoolId === schoolId ? queue : null;
  const activeReport = !accessBlocked && report?.schoolId === schoolId && report.request.id === selectedId ? report : null;
  const contextBlocked = busy || locked || sourceBusy || bankBusy || preparationBusy;
  const sourceGuardChange = useCallback((blocked: boolean) => {
    sourceReading.current = blocked; setSourceBusy(blocked);
    onGuardChange(blocked || bankReading.current || preparationReading.current || writing.current || attempt.current !== null);
  },[onGuardChange]);
  const bankGuardChange = useCallback((blocked: boolean) => {
    bankReading.current = blocked; setBankBusy(blocked);
    onGuardChange(blocked || sourceReading.current || preparationReading.current || writing.current || attempt.current !== null);
  },[onGuardChange]);

  const preparationGuardChange = useCallback((blocked: boolean) => {
    preparationReading.current = blocked; setPreparationBusy(blocked);
    onGuardChange(blocked || bankReading.current || sourceReading.current || writing.current || attempt.current !== null);
  },[onGuardChange]);

  const loseAccess = useCallback((code: SettlementErrorCode) => {
    if (code !== "session" && code !== "scope") return;
    authorizationLost.current = true; authorizationVersion.current += 1; setAccessError(code);
    setQueue(null); setReport(null); setSelectedId(null); detail.current = null;
    setReportQuery(""); setFilter(current => ({...current,query:"",page:0}));
    applied.current = {...applied.current,query:"",page:0}; setNotice(false);
    // Read/command entry guards exclude child-owned work before these children
    // can unmount. Immutable unknown attempts are deliberately never cleared.
  },[]);

  const loadQueue = useCallback(async (next: SettlementFilter): Promise<boolean> => {
    if (preparationReading.current || bankReading.current || sourceReading.current || read.current || writing.current || attempt.current || next.schoolId !== schoolId) return false;
    const controller = new AbortController(), epoch = lifecycle.current, accessVersion = authorizationVersion.current; read.current = controller; setBusy(true); setError(null);
    try {
      const result = await loadSettlementQueue(next,controller.signal);
      if (controller.signal.aborted || epoch !== lifecycle.current || accessVersion !== authorizationVersion.current) return false;
      applied.current = {...next,query:next.query.trim()}; setQueue(result);
      authorizationLost.current = false; setAccessError(null); return true;
    } catch (failure) { if (!controller.signal.aborted && epoch === lifecycle.current && accessVersion === authorizationVersion.current) { const code = settlementErrorCode(failure); setQueue(null); loseAccess(code); setError(code); } return false; }
    finally { if (read.current === controller) read.current = null; if (!controller.signal.aborted && epoch === lifecycle.current) setBusy(false); }
  },[schoolId,loseAccess]);

  const loadReport = useCallback(async (next: Detail): Promise<boolean> => {
    if (authorizationLost.current || preparationReading.current || bankReading.current || sourceReading.current || read.current || writing.current || attempt.current) return false;
    const controller = new AbortController(), epoch = lifecycle.current, accessVersion = authorizationVersion.current; read.current = controller; setBusy(true); setError(null); setSelectedId(next.id);
    try {
      const result = await loadSettlementReport(schoolId,next.id,next.query,next.page,controller.signal);
      if (controller.signal.aborted || epoch !== lifecycle.current || accessVersion !== authorizationVersion.current || authorizationLost.current) return false;
      detail.current = {...next,query:next.query.trim()}; setReport(result); return true;
    } catch (failure) { if (!controller.signal.aborted && epoch === lifecycle.current && accessVersion === authorizationVersion.current) { const code = settlementErrorCode(failure); setReport(null); loseAccess(code); setError(code); } return false; }
    finally { if (read.current === controller) read.current = null; if (!controller.signal.aborted && epoch === lifecycle.current) setBusy(false); }
  },[schoolId,loseAccess]);

  useEffect(() => {
    lifecycle.current += 1; let active = true;
    void Promise.resolve().then(() => { if (!active) return; const initial: SettlementFilter = {schoolId,status:"ALL",kind:"ALL",query:"",page:0};
      setFilter(initial); setQueue(null); setSelectedId(null); setReport(null); setReportQuery(""); setFromDay(""); setThroughDay(""); detail.current = null;
      return loadQueue(initial);
    });
    return () => { active = false; lifecycle.current += 1; const controller = read.current; controller?.abort(); if (read.current === controller) read.current = null; };
  },[schoolId,loadQueue]);

  useEffect(() => {
    if (!locked) return;
    const leave = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload",leave); return () => window.removeEventListener("beforeunload",leave);
  },[locked]);
  useEffect(() => { if (notice) feedback.current?.focus(); },[notice]);

  async function refresh() {
    if (preparationReading.current || busy || locked || bankReading.current || sourceReading.current || writing.current) return;
    const current = detail.current;
    if (await loadQueue(applied.current) && current) await loadReport(current);
  }
  async function dispatch(next: SettlementAttempt) {
    if (authorizationLost.current && attempt.current !== next || preparationReading.current || bankReading.current || sourceReading.current || writing.current || read.current || attempt.current && attempt.current !== next) return;
    attempt.current = next; writing.current = true; setLocked(true); onGuardChange(true); setBusy(true); setError(null); setNotice(false);
    const epoch = lifecycle.current; let recorded: {id:string} | null = null;
    try {
      const csrf = await familyPaymentCsrf(new AbortController().signal).catch(() => { throw new SettlementWriteError("rejected","csrf"); });
      if (epoch !== lifecycle.current) return;
      recorded = await sendSettlementSync(next,csrf);
      if (epoch !== lifecycle.current) return;
      attempt.current = null; unknown.current = false; setLocked(false); onGuardChange(false); setNotice(true);
    } catch (failure) {
      if (epoch !== lifecycle.current) return;
      loseAccess(settlementErrorCode(failure));
      if (!(failure instanceof SettlementWriteError) || failure.outcome === "unknown" || unknown.current) { unknown.current = true; setError("unknown"); }
      else { attempt.current = null; setLocked(false); onGuardChange(false); setError(settlementErrorCode(failure)); }
    } finally { writing.current = false; if (epoch === lifecycle.current) setBusy(false); }
    if (recorded && epoch === lifecycle.current) {
      const nextFilter: SettlementFilter = {schoolId,status:"ALL",kind:"ALL",query:"",page:0}; setFilter(nextFilter); setReportQuery("");
      if (await loadQueue(nextFilter)) await loadReport({id:recorded.id,query:"",page:0});
    }
  }
  function submitSummary(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (contextBlocked || bankReading.current || sourceReading.current || !activeQueue) return;
    try { void dispatch(prepareSettlementSummary(activeQueue,fromDay,throughDay,crypto.randomUUID())); } catch (failure) { setError(settlementErrorCode(failure)); }
  }
  function submitEvents(row: SettlementRow) {
    if (contextBlocked || bankReading.current || sourceReading.current || !activeQueue) return;
    try { void dispatch(prepareSettlementEvents(activeQueue,row,crypto.randomUUID())); } catch (failure) { setError(settlementErrorCode(failure)); }
  }
  function applyFilters(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if (!contextBlocked && !bankReading.current && !sourceReading.current) void loadQueue({...filter,page:0}); }
  function searchReport(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if (!contextBlocked && !bankReading.current && !sourceReading.current && selectedId) void loadReport({id:selectedId,query:reportQuery,page:0}); }
  const today = activeQueue ? settlementSchoolDate(activeQueue.schoolTimeZone) : undefined;

  return <section className={styles.root} aria-labelledby={`${id}-heading`} aria-busy={busy || sourceBusy || bankBusy}>
    <header><h2 id={`${id}-heading`}>{t("title")}</h2><p>{t("intro")}</p></header>
    <p className={styles.boundary}>{t("boundary")}</p>
    <p>{t("eventsBoundary")}</p>
    {activeQueue && !activeQueue.syncEnabled && <p className={styles.warning}>{t("disabled")}</p>}
    {activeQueue?.syncEnabled && !activeQueue.canSync && <p>{t("readOnly")}</p>}
    {busy && <p role="status">{t(locked ? "recording" : "loading")}</p>}
    {accessError && <p role="alert" className={styles.error}>{t(`error.${accessError}`)}</p>}
    {error && error !== accessError && <p role="alert" className={styles.error}>{t(`error.${error}`)}</p>}
    <p ref={feedback} tabIndex={-1} role="status" className={styles.feedback} hidden={!notice}>{notice ? t("recorded") : ""}</p>
    {locked && <div className={styles.warning}><p>{t("unknownHelp")}</p><button type="button" aria-disabled={busy} onClick={() => { if (!busy && attempt.current) void dispatch(attempt.current); }}>{t("retry")}</button></div>}
    {!locked && <button type="button" aria-disabled={contextBlocked} onClick={() => { if (!contextBlocked) void refresh(); }}>{t("refresh")}</button>}

    <form onSubmit={submitSummary} aria-label={t("syncForm")}>
      <fieldset disabled={contextBlocked || !activeQueue?.canSync}>
        <legend>{t("syncForm")}</legend>
        <p id={`${id}-date-help`}>{t("datesHelp")}</p>
        <p>{t("schoolZone")}: <strong>{activeQueue?.schoolTimeZone ?? "—"}</strong></p>
        <div className={styles.fields}>
          <label htmlFor={`${id}-from`}>{t("from")}<input id={`${id}-from`} type="date" min="2000-01-01" max={today} required value={fromDay} aria-describedby={`${id}-date-help`} onChange={event=>setFromDay(event.currentTarget.value)} /></label>
          <label htmlFor={`${id}-through`}>{t("through")}<input id={`${id}-through`} type="date" min={fromDay || "2000-01-01"} max={today} required value={throughDay} aria-describedby={`${id}-date-help`} onChange={event=>setThroughDay(event.currentTarget.value)} /></label>
        </div>
        <p>{t("noSensitive")}</p><button type="submit">{t("sync")}</button>
      </fieldset>
    </form>

    <section aria-labelledby={`${id}-history`}>
      <h3 id={`${id}-history`}>{t("history")}</h3>
      <form onSubmit={applyFilters} aria-label={t("filters")}>
        <fieldset disabled={contextBlocked} className={styles.filterFields}>
          <legend>{t("filters")}</legend>
          <div className={styles.fields}>
            <label htmlFor={`${id}-status`}>{t("status")}<select id={`${id}-status`} value={filter.status} onChange={event=>setFilter(current=>({...current,status:event.currentTarget.value as SettlementFilter["status"]}))}>{["ALL",...settlementStates].map(status=><option key={status} value={status}>{t(`state.${status as SettlementFilter["status"]}`)}</option>)}</select></label>
            <label htmlFor={`${id}-kind`}>{t("kind")}<select id={`${id}-kind`} value={filter.kind} onChange={event=>setFilter(current=>({...current,kind:event.currentTarget.value as SettlementFilter["kind"]}))}>{["ALL",...settlementKinds].map(kind=><option key={kind} value={kind}>{t(`kind.${kind as SettlementFilter["kind"]}`)}</option>)}</select></label>
          </div>
          <label htmlFor={`${id}-search`}>{t("search")}<input id={`${id}-search`} type="search" maxLength={100} value={filter.query} onChange={event=>setFilter(current=>({...current,query:event.currentTarget.value}))} aria-describedby={`${id}-search-help`} /></label>
          <p id={`${id}-search-help`}>{t("searchHelp")}</p><button type="submit">{t("apply")}</button>
        </fieldset>
      </form>
      {activeQueue && <>
        <p>{t("matching")}: {activeQueue.total} · {t("page")} {activeQueue.page + 1}</p>
        {!activeQueue.items.length && <p className={styles.empty}>{t("empty")}</p>}
        <ul className={styles.requests}>{activeQueue.items.map(request=><li key={request.id}>
          <article className={styles.requestCard} data-selected={request.id === selectedId}>
            <div className={styles.cardHeading}><h4>{t(`kind.${request.kind}`)}</h4><span className={styles.state} data-status={request.status}>{t(`state.${request.status}`)}</span></div>
            <RequestFacts request={request} locale={language} />
            <button type="button" aria-disabled={contextBlocked} aria-pressed={request.id === selectedId} aria-label={`${t("inspect")} ${request.id}`} onClick={() => { if (!contextBlocked && !bankReading.current && !sourceReading.current) { setReportQuery(""); void loadReport({id:request.id,query:"",page:0}); } }}>{t(request.id === selectedId ? "selected" : "inspect")}</button>
          </article>
        </li>)}</ul>
        <nav className={styles.pages} aria-label={t("history")}>
          <button type="button" disabled={contextBlocked || activeQueue.page === 0} onClick={()=>{if (!contextBlocked && !bankReading.current && !sourceReading.current) void loadQueue({...applied.current,page:activeQueue.page-1});}}>{t("previous")}</button>
          <button type="button" disabled={contextBlocked || !activeQueue.hasMore} onClick={()=>{if (!contextBlocked && !bankReading.current && !sourceReading.current) void loadQueue({...applied.current,page:activeQueue.page+1});}}>{t("next")}</button>
        </nav>
      </>}
    </section>

    {activeReport && <section className={styles.report} aria-labelledby={`${id}-report-heading`}>
      <div className={styles.cardHeading}><h3 id={`${id}-report-heading`}>{t("details")}</h3><button type="button" disabled={contextBlocked} onClick={()=>{if (!contextBlocked && !bankReading.current && !sourceReading.current) { detail.current=null; setSelectedId(null); setReport(null); }}}>{t("close")}</button></div>
      <p className={styles.state} data-status={activeReport.request.status}>{t(`state.${activeReport.request.status}`)}</p>
      <RequestFacts request={activeReport.request} locale={language} />
      <p className={styles.boundary}>{t("eventsBoundary")}</p>
      <form onSubmit={searchReport} aria-label={t("reportSearch")}>
        <fieldset disabled={contextBlocked}>
          <legend>{t("reportSearch")}</legend>
          <label htmlFor={`${id}-report-query`}>{t("reportSearchLabel")}<input id={`${id}-report-query`} type="search" maxLength={100} value={reportQuery} onChange={event=>setReportQuery(event.currentTarget.value)} /></label>
          <div className={styles.actions}><button type="submit">{t("reportFilter")}</button><button type="button" onClick={()=>{if (!contextBlocked && !bankReading.current && !sourceReading.current && detail.current) void loadReport(detail.current);}}>{t("refreshRows")}</button></div>
        </fieldset>
      </form>
      <p>{t("matching")}: {activeReport.total} · {t("page")} {activeReport.page + 1}</p>
      {!activeReport.items.length && <p className={styles.empty}>{t("noRows")}</p>}
      <ul className={styles.reportRows}>{activeReport.items.map(row=><li key={`${row.sourceReadId}:${row.rowIndex}`}><article className={styles.evidence}>
        {row.summary && <>
          <h4>{row.summary.settlementReference}</h4>
          <dl className={styles.facts}><Fact label={t("providerStatus")} value={row.summary.reportedStatus} /><Fact label={t("transfer")} value={row.summary.transferReference ?? t("notSupplied")} /><Fact label={t("amountSettled")} value={formatSettlementAmount(row.summary.reportedAmountSettled,language)} /></dl>
          <p className={styles.warning}>{t("currencyMissing")}</p>
          <details><summary>{t("components")}</summary><dl className={styles.facts}>
            {([ ["paymentAmount",row.summary.reportedPaymentAmount],["serviceCharge",row.summary.reportedServiceCharge],["serviceTax",row.summary.reportedServiceTax],["adjustment",row.summary.reportedAdjustment],["settlementCharge",row.summary.reportedSettlementCharge],["settlementTax",row.summary.reportedSettlementTax],["splitCharge",row.summary.reportedSplitCharge],["splitTax",row.summary.reportedSplitTax],["commission",row.summary.reportedVendorCommission] ] as const).map(([key,value])=><Fact key={key} label={t(key)} value={value === null ? t("notSupplied") : formatSettlementAmount(value,language)} />)}
            <Fact label={t("chargeType")} value={row.summary.reportedChargeType ?? t("notSupplied")} /><Fact label={t("settlementType")} value={row.summary.reportedSettlementType ?? t("notSupplied")} />
            {([ ["paymentsFrom",row.summary.paymentsFrom],["paymentsThrough",row.summary.paymentsThrough],["initiated",row.summary.initiatedAt],["processed",row.summary.processedAt] ] as const).map(([key,value])=><Fact key={key} label={t(key)} value={<ReportedTime value={value} locale={language} timeZone={activeReport.request.schoolTimeZone} />} />)}
          </dl></details>
          <button type="button" disabled={contextBlocked || !activeQueue?.canSync} onClick={()=>submitEvents(row)}>{t("events")}</button>
        </>}
        {row.event && <>
          <h4>{row.event.eventReference}</h4><dl className={styles.facts}>
            <Fact label={t("sourceStatement")} value={row.event.settlementReference} /><Fact label={t("eventType")} value={row.event.reportedType} /><Fact label={t("direction")} value={row.event.reportedDirection} /><Fact label={t("providerStatus")} value={row.event.reportedStatus} /><Fact label={t("eventCurrency")} value={row.event.currencyCode} />
            <Fact label={t("eventAmount")} value={formatSettlementAmount(row.event.reportedAmount,language)} /><Fact label={t("eventSettled")} value={row.event.reportedSettlementAmount === null ? t("notSupplied") : formatSettlementAmount(row.event.reportedSettlementAmount,language)} /><Fact label={t("serviceCharge")} value={row.event.reportedServiceCharge === null ? t("notSupplied") : formatSettlementAmount(row.event.reportedServiceCharge,language)} /><Fact label={t("serviceTax")} value={row.event.reportedServiceTax === null ? t("notSupplied") : formatSettlementAmount(row.event.reportedServiceTax,language)} /><Fact label={t("chargeCurrency")} value={row.event.chargesCurrencyCode ?? t("notSupplied")} /><Fact label={t("eventTime")} value={<ReportedTime value={row.event.occurredAt} locale={language} timeZone={activeReport.request.schoolTimeZone} />} />
            <Fact label={t("order")} value={row.event.orderReference ?? t("notSupplied")} /><Fact label={t("payment")} value={row.event.paymentReference ?? t("notSupplied")} /><Fact label={t("refunds")} value={row.event.refundReferences.length ? row.event.refundReferences.join(", ") : t("notSupplied")} />
          </dl>
        </>}
        <dl className={styles.provenance}><Fact label={t("sourceRead")} value={row.sourceReadId} /><Fact label={t("row")} value={String(row.rowIndex+1)} /><Fact label={t("observed")} value={<ReportedTime value={row.observedAt} locale={language} timeZone={activeReport.request.schoolTimeZone} />} /></dl>
      </article></li>)}</ul>
      <nav className={styles.pages} aria-label={t("details")}>
        <button type="button" disabled={contextBlocked || activeReport.page === 0} onClick={()=>{if (!contextBlocked && !bankReading.current && !sourceReading.current && detail.current) void loadReport({...detail.current,page:activeReport.page-1});}}>{t("previous")}</button>
        <button type="button" disabled={contextBlocked || !activeReport.hasMore} onClick={()=>{if (!contextBlocked && !bankReading.current && !sourceReading.current && detail.current) void loadReport({...detail.current,page:activeReport.page+1});}}>{t("next")}</button>
      </nav>
      {activeReport.request.kind === "EVENTS" && activeReport.request.status === "COMPLETE" && activeReport.request.settlementReference && <>
        <SettlementSourceReviewPanel key={`source:${activeReport.request.id}`} schoolId={schoolId} requestId={activeReport.request.id} settlementReference={activeReport.request.settlementReference} disabled={busy || locked || bankBusy || preparationBusy} onGuardChange={sourceGuardChange} />
        <SettlementBankEvidencePanel key={`bank:${activeReport.request.id}`} schoolId={schoolId} requestId={activeReport.request.id} settlementReference={activeReport.request.settlementReference} disabled={busy || locked || sourceBusy || preparationBusy} onGuardChange={bankGuardChange} />
        <SettlementPreparationPanel key={"prepare:"+activeReport.request.id} schoolId={schoolId} requestId={activeReport.request.id} settlementReference={activeReport.request.settlementReference} disabled={busy || locked || sourceBusy || bankBusy} onGuardChange={preparationGuardChange} />
      </>}
    </section>}
  </section>;
}

function Fact({label,value}: {label:string;value:ReactNode}) { return <div><dt>{label}</dt><dd>{value}</dd></div>; }
function ReportedTime({value,locale,timeZone}: {value:string|null;locale:"en"|"hi";timeZone:string}) {
  return value ? <><time dateTime={value}>{new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN",{dateStyle:"medium",timeStyle:"short",timeZone}).format(new Date(value))}</time><small className={styles.exact}>{value}</small></> : settlementMessage(locale,"notSupplied");
}
function RequestFacts({request,locale}: {request:SettlementRequest;locale:"en"|"hi"}) {
  const t = (key: SettlementMessageKey) => settlementMessage(locale,key);
  return <dl className={styles.facts}>
    <Fact label={t("requestedBy")} value={request.requestedBy} /><Fact label={t("requestedAt")} value={<ReportedTime value={request.requestedAt} locale={locale} timeZone={request.schoolTimeZone} />} /><Fact label={t("schoolZone")} value={request.schoolTimeZone} />
    {request.kind === "SUMMARY" ? <Fact label={t("interval")} value={<><ReportedTime value={request.from} locale={locale} timeZone={request.schoolTimeZone} /><span aria-hidden="true"> → </span><ReportedTime value={request.until} locale={locale} timeZone={request.schoolTimeZone} /></>} /> : <Fact label={t("sourceStatement")} value={request.settlementReference} />}
    <Fact label={t("completePages")} value={String(request.pagesCompleted)} /><Fact label={t("pendingPages")} value={String(request.pagesPending)} /><Fact label={t("reviewPages")} value={String(request.pagesReview)} /><Fact label={t("lastCheck")} value={request.lastCheckedAt ? <ReportedTime value={request.lastCheckedAt} locale={locale} timeZone={request.schoolTimeZone} /> : t("noCheck")} />
    {request.nextCheckAt && <Fact label={t("nextCheck")} value={<ReportedTime value={request.nextCheckAt} locale={locale} timeZone={request.schoolTimeZone} />} />}
  </dl>;
}
