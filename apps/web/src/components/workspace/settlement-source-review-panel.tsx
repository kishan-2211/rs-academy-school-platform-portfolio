"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { useLanguage } from "@/components/i18n/language-provider";
import { formatSettlementAmount } from "@/lib/gateway-settlements";
import { loadSettlementSourceReview, settlementSourceReviewErrorCode, type SettlementSourceReview, type SettlementSourceReviewErrorCode } from "@/lib/gateway-settlement-source-review";
import { settlementSourceReviewMessage, type SettlementSourceReviewMessageKey } from "@/lib/i18n/gateway-settlement-source-review-messages";
import styles from "./settlement-source-review-panel.module.css";
import { SettlementRefundCandidatesPanel } from "./settlement-refund-candidates-panel";

type Props = { schoolId: string; requestId: string; settlementReference: string; disabled: boolean; onGuardChange: (blocked: boolean) => void };
type ReviewContext = { schoolId: string; requestId: string; settlementReference: string; disabled: boolean };
type Snapshot = { context: ReviewContext; data: SettlementSourceReview | null; error: SettlementSourceReviewErrorCode | null };

export function SettlementSourceReviewPanel({ schoolId, requestId, settlementReference, disabled, onGuardChange }: Props) {
  const { locale } = useLanguage(), language = locale === "hi" ? "hi" : "en", id = useId();
  const t = (key: SettlementSourceReviewMessageKey) => settlementSourceReviewMessage(language,key);
  // A new object on any context/guard change prevents old results reappearing
  // even when a previous school/report is selected again after an aborted read.
  const context = useMemo<ReviewContext>(()=>({schoolId,requestId,settlementReference,disabled}),[schoolId,requestId,settlementReference,disabled]);
  const [snapshot,setSnapshot] = useState<Snapshot | null>(null);
  const [pending,setPending] = useState<ReviewContext | null>(null);
  const [childBusy,setChildBusy] = useState(false);
  const childReads = useRef(new Set<object>());
  const read = useRef<AbortController | null>(null), lifecycle = useRef(0);
  const busy = !disabled && (pending === context || childBusy);
  const active = !disabled && snapshot?.context === context && (!snapshot.data || snapshot.data.schoolId === schoolId && snapshot.data.requestId === requestId && snapshot.data.settlementReference === settlementReference) ? snapshot : null;
  const review = active?.data ?? null;

  useEffect(() => {
    lifecycle.current += 1;
    const children = childReads.current;
    return () => { lifecycle.current += 1; read.current?.abort(); read.current = null; children.clear(); setChildBusy(false); onGuardChange(false); };
  },[context,onGuardChange]);

  const childGuardChange = useCallback((key:object,blocked:boolean) => {
    if(blocked) childReads.current.add(key);
    else if(!childReads.current.delete(key)) return;
    const waiting=childReads.current.size>0;
    setChildBusy(waiting);onGuardChange(waiting||read.current!==null);
  },[onGuardChange]);

  const load = useCallback(async (page: number) => {
    if (disabled || read.current || childReads.current.size) return;
    const controller = new AbortController(), epoch = lifecycle.current;
    read.current = controller; setPending(context); setSnapshot(null); onGuardChange(true);
    try {
      const data = await loadSettlementSourceReview(schoolId,requestId,settlementReference,page,controller.signal);
      if (!controller.signal.aborted && epoch === lifecycle.current) setSnapshot({context,data,error:null});
    } catch (failure) {
      if (!controller.signal.aborted && epoch === lifecycle.current) setSnapshot({context,data:null,error:settlementSourceReviewErrorCode(failure)});
    } finally {
      if (read.current === controller) { read.current = null; setPending(null); onGuardChange(childReads.current.size>0); }
    }
  },[schoolId,requestId,settlementReference,disabled,context,onGuardChange]);

  return <section className={styles.root} aria-labelledby={`${id}-heading`} aria-busy={!disabled && busy}>
    <h4 id={`${id}-heading`}>{t("title")}</h4><p>{t("intro")}</p>
    <p className={styles.boundary}>{t("boundary")}</p>
    <p>{t("statement")}: <strong>{settlementReference}</strong></p>
    {disabled ? <p role="status">{t("paused")}</p> : <>
      <button type="button" disabled={busy} onClick={()=>{if (!busy) void load(review?.page ?? 0);}}>{t(review ? "refresh" : "inspect")}</button>
      {pending === context && <p role="status">{t("loading")}</p>}
      {active?.error && <p role="alert" className={styles.error}>{t(`error.${active.error}`)}</p>}
      {!busy && !active && <p>{t("idle")}</p>}
      {review && <>
        <dl className={styles.counts}>
          <Fact label={t("pagesRead")} value={review.pagesRead} /><Fact label={t("totalRows")} value={review.totalRows} /><Fact label={t("sourceIdentifiedRows")} value={review.sourceIdentifiedRows} /><Fact label={t("reviewRequiredRows")} value={review.reviewRequiredRows} />
        </dl><p>{t("allRows")}</p><p>{t("page")} {review.page+1}</p>
        {!review.items.length && <p className={styles.empty}>{t("empty")}</p>}
        <ul className={styles.rows}>{review.items.map(row=><li key={`${row.sourceReadId}:${row.rowIndex}`}><article className={styles.card}>
          <h5>{row.event.eventReference}</h5><p className={styles.state} data-status={row.status}>{t(`status.${row.status}`)}</p>
          <dl className={styles.facts}>
            <Fact label={t("type")} value={row.event.reportedType} /><Fact label={t("direction")} value={row.event.reportedDirection} /><Fact label={t("providerStatus")} value={row.event.reportedStatus} /><Fact label={t("currency")} value={row.event.currencyCode} /><Fact label={t("gross")} value={formatSettlementAmount(row.event.reportedAmount,language)} />
            <Fact label={t("order")} value={row.event.orderReference ?? t("notSupplied")} /><Fact label={t("payment")} value={row.event.paymentReference ?? t("notSupplied")} /><Fact label={t("refunds")} value={row.event.refundReferences.length ? row.event.refundReferences.join(", ") : t("notSupplied")} /><Fact label={t("occurred")} value={<ExactTime value={row.event.occurredAt} />} />
          </dl>
          {row.reasons.length > 0 && <div className={styles.warning}><h6>{t("reasons")}</h6><ul>{row.reasons.map(reason=><li key={reason}>{t(`reason.${reason}`)}</li>)}</ul></div>}
          {row.event.reportedType === "REFUND" && <SettlementRefundCandidatesPanel schoolId={schoolId} requestId={requestId} settlementReference={settlementReference} row={row} disabled={disabled} onGuardChange={childGuardChange} />}
          {row.source && <>
            <dl className={styles.facts}><Fact label={t("receipt")} value={row.source.receiptNumber} /><Fact label={t("sourceCurrency")} value={row.source.currencyCode} /><Fact label={t("sourceGross")} value={formatSettlementAmount(row.source.grossAmount,language)} /><Fact label={t("originalPostingDate")} value={row.source.originalPostingDate} /><Fact label={t("accountingBasis")} value={t(`basis.${row.source.accountingBasis}`)} /><Fact label={t("clearingAccountCategory")} value={t("clearingOther")} /></dl>
            <details><summary>{t("sourceDetails")}</summary><dl className={styles.facts}>
              {(["captureId","paymentId","feeLedgerEntryId","postingItemId","postingBatchId","configurationId","accountingJournalId","clearingAccountId","originalFinancialYearId","originalAccountingPeriodId"] as const).map(key=><Fact key={key} label={t(key)} value={row.source![key]} />)}
            </dl></details>
          </>}
          <dl className={styles.provenance}><Fact label={t("sourceRead")} value={row.sourceReadId} /><Fact label={t("row")} value={row.rowIndex+1} /><Fact label={t("observed")} value={<ExactTime value={row.observedAt} />} /></dl>
        </article></li>)}</ul>
        <nav className={styles.pages} aria-label={t("pagination")}><button type="button" disabled={busy || review.page === 0} onClick={()=>{if (!busy) void load(review.page-1);}}>{t("previous")}</button><button type="button" disabled={busy || !review.hasMore} onClick={()=>{if (!busy) void load(review.page+1);}}>{t("next")}</button></nav>
      </>}
    </>}
  </section>;
}
function Fact({label,value}: {label:string;value:ReactNode}) { return <div><dt>{label}</dt><dd>{value}</dd></div>; }
function ExactTime({value}: {value:string}) { return <time dateTime={value}>{value}</time>; }
