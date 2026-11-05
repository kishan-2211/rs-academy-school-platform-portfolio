"use client";

import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { familyPaymentCsrf } from "@/lib/family-gateway-payments";
import { loadRefundPayment, loadRefundQueue, prepareRefund, prepareRefundDecision, refundError, refundErrors, refundStatuses, refundProcessingLabel, RefundWriteError, sendRefund, type RefundAttempt, type RefundFilter, type RefundPayment, type RefundQueue, type RefundRequest } from "@/lib/gateway-refunds";
import styles from "./refund-requests-panel.module.css";
import { RefundAccountReopeningPanel } from "./refund-account-reopening-panel";
import { RefundPostingPanel } from "./refund-posting-panel";

type Props = { schoolId: string; paymentId?: string; onGuardChange: (blocked: boolean) => void; onSettled: () => Promise<void> };
export function RefundRequestsPanel({ schoolId, paymentId, onGuardChange: notifyGuard, onSettled }: Props) {
  const t = useT("fees"), { locale } = useLanguage(), id = useId();
  const [filter, setFilter] = useState<RefundFilter>({schoolId,status:"SUBMITTED",query:"",page:0});
  const [queue, setQueue] = useState<RefundQueue | null>(null);
  const [payment, setPayment] = useState<RefundPayment | null>(null);
  const [amounts, setAmounts] = useState<Record<string,string>>({});
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(true);
  const [locked, setLocked] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [recorded, setRecorded] = useState(false);
  const [reopeningBlocked, setReopeningBlocked] = useState(false);
  const [reopeningRefund, setReopeningRefund] = useState<string | undefined>();
  const [postingBlocked, setPostingBlocked] = useState(false);
  const [reopeningRevision, setReopeningRevision] = useState(0);
  const [postingRevision, setPostingRevision] = useState(0);
  const refundGuard = useRef(false), reopeningGuard = useRef(false), postingGuard = useRef(false);
  const onGuardChange = useCallback((blocked: boolean) => {
    refundGuard.current = blocked; notifyGuard(blocked || reopeningGuard.current || postingGuard.current);
  },[notifyGuard]);
  const reopeningGuardChange = useCallback((blocked: boolean) => {
    reopeningGuard.current = blocked; setReopeningBlocked(blocked); notifyGuard(blocked || refundGuard.current || postingGuard.current);
  },[notifyGuard]);
  const postingGuardChange = useCallback((blocked: boolean) => {
    postingGuard.current = blocked; setPostingBlocked(blocked); notifyGuard(blocked || refundGuard.current || reopeningGuard.current);
  },[notifyGuard]);
  const attempt = useRef<RefundAttempt | null>(null), unknown = useRef(false), accepted = useRef(false), writing = useRef(false);
  const read = useRef<AbortController | null>(null), lifecycle = useRef(0), applied = useRef(filter);
  const feedback = useRef<HTMLParagraphElement | null>(null);
  // A verified change refreshes only its idle sibling. Keep the originating
  // child's accepted command, feedback and guard mounted until it clears them.
  const reopeningSettled = useCallback(async () => {
    const epoch = lifecycle.current;
    if (postingGuard.current) throw new Error(refundErrors.unavailable);
    await onSettled();
    if (epoch !== lifecycle.current || postingGuard.current) throw new Error(refundErrors.unavailable);
    setPostingRevision((current) => current + 1);
  },[onSettled]);
  const postingSettled = useCallback(async () => {
    const epoch = lifecycle.current;
    if (reopeningGuard.current) throw new Error(refundErrors.unavailable);
    await onSettled();
    if (epoch !== lifecycle.current || reopeningGuard.current) throw new Error(refundErrors.unavailable);
    setReopeningRevision((current) => current + 1);
  },[onSettled]);
  const money = (amount: number, currency: string) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN",{style:"currency",currency,minimumFractionDigits:2,maximumFractionDigits:2}).format(amount);
  const timestamp = (value: string) => new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN",{dateStyle:"medium",timeStyle:"short"}).format(new Date(value));
  const statusName = (status: string) => t(status === "SUBMITTED" ? "Awaiting independent refund review" : status === "APPROVED" ? "Refund request approved — check processing separately" : status === "REJECTED" ? "Refund request rejected" : "Refund request cancelled");
  const load = useCallback(async (next: RefundFilter): Promise<boolean> => {
    if (read.current) return false;
    const controller = new AbortController(), epoch = lifecycle.current; read.current = controller;
    setBusy(true); setError("");
    try {
      const [nextQueue,nextPayment] = await Promise.all([loadRefundQueue(next,controller.signal),paymentId ? loadRefundPayment(schoolId,paymentId,controller.signal) : Promise.resolve(null)]);
      if (controller.signal.aborted || epoch !== lifecycle.current) return false;
      applied.current = {...next,query:next.query.trim()}; setQueue(nextQueue); setPayment(nextPayment); return true;
    } catch (failure) { if (!controller.signal.aborted && epoch === lifecycle.current) { setQueue(null); setPayment(null); setError(refundError(failure)); } return false; }
    finally { if (read.current === controller) read.current = null; if (!controller.signal.aborted && epoch === lifecycle.current) setBusy(false); }
  },[schoolId,paymentId]);
  useEffect(() => {
    lifecycle.current += 1; let active = true;
    void Promise.resolve().then(() => { if (active) return load({schoolId,status:"SUBMITTED",query:"",page:0}); });
    return () => { active = false; lifecycle.current += 1; const controller = read.current; controller?.abort(); if (read.current === controller) read.current = null; };
  },[schoolId,load]);
  useEffect(() => {
    if (!locked) return;
    const leave = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    const navigate = (event: MouseEvent) => { if (event.target instanceof Element && event.target.closest("a[href]")) { event.preventDefault(); event.stopPropagation(); setError(refundErrors.unknown); } };
    window.addEventListener("beforeunload",leave); document.addEventListener("click",navigate,true);
    return () => { window.removeEventListener("beforeunload",leave); document.removeEventListener("click",navigate,true); };
  },[locked]);
  useEffect(() => {
    // The submitting card can leave the filtered queue; ordinary reads never set this notice.
    if (notice) feedback.current?.focus();
  },[notice]);
  async function refreshAccepted() {
    if (writing.current || read.current || !accepted.current) return;
    writing.current = true; const epoch = lifecycle.current; setBusy(true);
    try { if (!await load(applied.current)) return; await onSettled(); if (epoch !== lifecycle.current) return;
      attempt.current = null; accepted.current = false; unknown.current = false; setRecorded(false); setLocked(false); onGuardChange(false); setAmounts({}); setReason(""); setConfirmed(false);
    } catch { if (epoch === lifecycle.current) setError(refundErrors.unavailable); }
    finally { writing.current = false; if (epoch === lifecycle.current) setBusy(false); }
  }
  async function dispatch(next: RefundAttempt) {
    if ((reopeningGuard.current || postingGuard.current) && !attempt.current) return;
    if (writing.current || read.current || accepted.current || attempt.current && attempt.current !== next) return;
    writing.current = true; attempt.current = next; setLocked(true); onGuardChange(true); setBusy(true); setError(""); setNotice("");
    const epoch = lifecycle.current;
    try {
      const token = await familyPaymentCsrf(new AbortController().signal).catch(() => { throw new RefundWriteError("rejected",refundErrors.csrf); });
      if (epoch !== lifecycle.current) return;
      await sendRefund(next,token);
      if (epoch !== lifecycle.current) return;
      accepted.current = true; setRecorded(true); setNotice("Refund request or decision recorded. Check processing and financial status before preparing anything new.");
    } catch (failure) {
      if (epoch !== lifecycle.current) return;
      if (!(failure instanceof RefundWriteError) || failure.outcome === "unknown" || unknown.current) { unknown.current = true; setError(refundErrors.unknown); }
      else { attempt.current = null; setLocked(false); onGuardChange(false); setError(refundError(failure)); }
    } finally { writing.current = false; if (epoch === lifecycle.current) setBusy(false); }
    if (accepted.current && epoch === lifecycle.current) await refreshAccepted();
  }
  function requestRefund(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || locked || !payment || !confirmed) return;
    try { void dispatch(prepareRefund(payment,amounts,reason,crypto.randomUUID())); } catch (failure) { setError(refundError(failure)); }
  }
  function decide(event: FormEvent<HTMLFormElement>, request: RefundRequest) {
    event.preventDefault(); if (busy || locked) return;
    const fields = new FormData(event.currentTarget); if (fields.get("confirm") !== "on") return;
    const decision = fields.get("decision"); if (decision !== "APPROVE" && decision !== "REJECT" && decision !== "CANCEL") return;
    try { void dispatch(prepareRefundDecision(schoolId,request,decision,String(fields.get("reason") ?? ""))); } catch (failure) { setError(refundError(failure)); }
  }
  function apply(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if (!busy && !locked) void load({...filter,page:0}); }
  const automation = queue?.executionEnabled ?? payment?.executionEnabled;
  return <section className={styles.root} aria-labelledby={`${id}-heading`} aria-busy={busy}>
    <header><h2 id={`${id}-heading`}>{t("Gateway refund requests")}</h2><p>{t("Request only from an original captured gateway receipt. A different authorized person must approve it.")}</p></header>
    <fieldset className={styles.guardBoundary} disabled={reopeningBlocked || postingBlocked}>
    <legend>{t("Refund intent and review")}</legend>
    <p className={styles.warning}>{t(automation === true ? "New gateway refund processing is configured. Independent approval may authorize background processing; approval alone never proves that money was returned or dues restored." : automation === false ? "New automated gateway refunds are disabled. Existing refunds may still be confirmed. Approval alone is not refund completion; check retained processing and financial records." : "Read current refund records before deciding. Independent approval and verified processing are separate from recording restored dues.")}</p>
    <p>{t("A verified successful refund restores the original fee obligation through reviewed recording. Any waiver needs separate approval; the original receipt remains unchanged.")}</p>
    {locked && <p role="status" className={styles.warning}>{t(recorded ? "The intent is recorded. Refresh its current records before preparing another command." : "Keep this page open while the command is unresolved. Do not repeat it from another tab. After a forced exit, review the queue before creating anything new.")}</p>}
    {busy && <p role="status">{t("Reading or recording refund intent…")}</p>}
    {error && <p role="alert" className={styles.error}>{t(error)}</p>}
    <p ref={feedback} tabIndex={-1} role="status" className={styles.feedback} hidden={!notice}>{notice ? t(notice) : ""}</p>
    {locked && <button type="button" aria-disabled={busy} onClick={() => { if (!busy && attempt.current && !accepted.current) void dispatch(attempt.current); if (!busy && accepted.current) void refreshAccepted(); }}>{t(recorded ? "Refresh recorded refund intent" : "Retry this exact refund command")}</button>}
    {!locked && error && <button type="button" aria-disabled={busy} onClick={() => { if (!busy) void load(applied.current); }}>{t("Refresh refund records")}</button>}
    {queue && !locked && !error && <button type="button" aria-disabled={busy} onClick={() => { if (!busy) void load(applied.current); }}>{t("Refresh refund processing status")}</button>}
    {payment && <section className={styles.source} aria-labelledby={`${id}-receipt`}>
      <h3 id={`${id}-receipt`}>{t("Original gateway receipt")}: {payment.receiptNumber}</h3><p>{payment.learnerName} · {t("Original paid")}: {money(payment.amount,payment.currencyCode)} · {t("Reserved for refund review")}: {money(payment.reserved,payment.currencyCode)} · {t("Available refundable amount")}: {money(payment.available,payment.currencyCode)}</p>
      {!payment.canRequest && <p>{t("No new refund can be requested from this receipt with your current access and available allocations.")}</p>}
      {payment.canRequest && <form onSubmit={requestRefund} aria-label={t("Prepare refund request")}>
        <fieldset disabled={locked}><legend>{t("Choose original refundable allocations")}</legend><div className={styles.allocations}>{payment.allocations.map(line => <div key={line.chargeId}><p><strong>{line.label}</strong><br />{t("Original paid")}: {money(line.originalPaid,payment.currencyCode)} · {t("Reserved for refund review")}: {money(line.reserved,payment.currencyCode)} · {t("Available refundable amount")}: {money(line.available,payment.currencyCode)}</p>{line.refundable && line.available > 0 ? <label>{t("Requested refund amount for {fee}",{fee:line.label})}<input inputMode="decimal" type="text" value={amounts[line.chargeId] ?? ""} maxLength={12} autoComplete="off" onChange={event => { if (!busy && !locked) { setAmounts({...amounts,[line.chargeId]:event.target.value}); setConfirmed(false); } }} /></label> : <p>{t("Not available for a refund request")}</p>}</div>)}</div>
          <label>{t("Refund request reason")}<textarea value={reason} required minLength={3} maxLength={500} onChange={event => { if (!busy && !locked) { setReason(event.target.value); setConfirmed(false); } }} /></label>
          <label className={styles.check}><input type="checkbox" checked={confirmed} required onChange={event => { if (!busy && !locked) setConfirmed(event.target.checked); }} />{t("I checked the original allocations. This submits intent for independent review; it does not issue a refund.")}</label>
        </fieldset><button type="submit" aria-disabled={busy || locked || !confirmed}>{t("Submit refund request")}</button>
      </form>}
    </section>}
    <form onSubmit={apply} aria-label={t("Refund queue filters")}><fieldset disabled={locked}><legend>{t("Review refund requests")}</legend><div className={styles.filters}>
      <label>{t("Refund status")}<select value={filter.status} onChange={event => { if (!busy && !locked) setFilter({...filter,status:event.target.value as RefundFilter["status"]}); }}><option value="ALL">{t("All refund requests")}</option>{refundStatuses.map(status => <option key={status} value={status}>{statusName(status)}</option>)}</select></label>
      <label>{t("Learner or receipt search")}<input type="search" maxLength={100} autoComplete="off" value={filter.query} onChange={event => { if (!busy && !locked) setFilter({...filter,query:event.target.value}); }} /></label>
    </div></fieldset><button type="submit" aria-disabled={busy || locked}>{t("Apply refund filters")}</button></form>
    {queue && <><p role="status">{t("{count} refund requests · page {page}",{count:queue.total,page:queue.page + 1})}</p>{!queue.items.length && <p>{t("No refund requests match this page and filter.")}</p>}
      <ol className={styles.items} aria-label={t("Refund requests")}>{queue.items.map(request => <li key={request.id}><article><header><div><h3>{request.receiptNumber}</h3><p>{request.learnerName}</p></div><strong>{money(request.amount,request.currencyCode)}</strong></header><p className={styles.state}>{statusName(request.status)}</p>
        {request.processing ? <div className={styles.decision}><p className={styles.state}>{t(refundProcessingLabel(request.processing.state))}</p>
          {request.processing.requiresReview && <p className={styles.warning}>{t("Additional refund review is required. Do not issue another refund or release reserved amounts from this status screen.")}</p>}
          {request.processing.lastCheckedAt && <p>{t("Last independent gateway check")}: <time dateTime={request.processing.lastCheckedAt}>{timestamp(request.processing.lastCheckedAt)}</time></p>}
          {request.processing.nextCheckAt && <p>{t("Next eligible check when background processing is enabled")}: <time dateTime={request.processing.nextCheckAt}>{timestamp(request.processing.nextCheckAt)}</time></p>}
          <p>{t("Processing status is not a bank settlement or general-ledger approval.")}</p></div>
          : request.status === "APPROVED" && <p>{t("Processing evidence is unavailable in this response. Open the original account and verified refund records; do not infer completion from approval.")}</p>}
        <p>{t("Requested by {name} at {time}",{name:request.requestedBy,time:timestamp(request.requestedAt)})} {request.isMaker && <strong>{t("Your request")}</strong>}</p><p className={styles.authored}>{request.reason}</p><ul>{request.allocations.map(line => <li key={line.chargeId}>{line.label}: {money(line.amount,request.currencyCode)}</li>)}</ul>
        {request.decision && <div className={styles.decision}><p>{t("Decision by {name} at {time}",{name:request.decision.decidedBy,time:timestamp(request.decision.decidedAt)})}</p><p className={styles.authored}>{request.decision.reason}</p></div>}
        {request.status === "APPROVED" && <button type="button" aria-disabled={busy || locked || reopeningBlocked || postingBlocked} onClick={() => { if (!busy && !locked && !reopeningGuard.current && !postingGuard.current) setReopeningRefund(request.id); }}>{t("Review original fee account")}</button>}
        {request.status === "SUBMITTED" && (request.canReview || request.canCancel) && <form onSubmit={event => decide(event,request)} aria-label={t("Decision for receipt {receipt}",{receipt:request.receiptNumber})}><fieldset disabled={locked}><legend>{t("Independent review or maker cancellation")}</legend><label>{t("Refund decision")}<select name="decision">{request.canReview && !request.isMaker && <><option value="APPROVE">{t("Approve refund processing")}</option><option value="REJECT">{t("Reject refund request")}</option></>}{request.canCancel && <option value="CANCEL">{t("Cancel my refund request")}</option>}</select></label><label>{t("Refund decision reason")}<textarea name="reason" minLength={3} maxLength={500} required /></label><label className={styles.check}><input name="confirm" type="checkbox" required />{t("I reviewed the refund terms. Approval may authorize configured gateway processing; verified return, restored dues and accounting approval must be checked separately.")}</label></fieldset><button type="submit" aria-disabled={busy || locked}>{t("Record refund decision")}</button></form>}
      </article></li>)}</ol>
      <div className={styles.pages} aria-label={t("Refund queue pages")}><button type="button" aria-disabled={busy || locked || queue.page === 0} onClick={() => { if (!busy && !locked && queue.page > 0) void load({...applied.current,page:queue.page - 1}); }}>{t("Previous refund page")}</button><button type="button" aria-disabled={busy || locked || !queue.hasMore || queue.page >= 10000} onClick={() => { if (!busy && !locked && queue.hasMore && queue.page < 10000) void load({...applied.current,page:queue.page + 1}); }}>{t("Next refund page")}</button></div>
    </>}
    </fieldset>
    <RefundAccountReopeningPanel key={`reopening:${reopeningRevision}`} schoolId={schoolId} refundId={reopeningRefund} disabled={busy || locked || postingBlocked} onGuardChange={reopeningGuardChange} onSettled={reopeningSettled} />
    <RefundPostingPanel key={`posting:${postingRevision}`} schoolId={schoolId} refundId={reopeningRefund} disabled={busy || locked || reopeningBlocked} onGuardChange={postingGuardChange} onSettled={postingSettled} />
  </section>;
}
