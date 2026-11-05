"use client";

import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { familyPaymentCsrf } from "@/lib/family-gateway-payments";
import { loadReopeningContext, loadReopeningQueue, prepareReopening, prepareReopeningDecision, reopeningError, reopeningErrors, reopeningStates, ReopeningWriteError, sendReopening, type ReopeningAttempt, type ReopeningClosure, type ReopeningContext, type ReopeningFilter, type ReopeningQueue, type ReopeningReview } from "@/lib/refund-account-reopening";
import styles from "./refund-account-reopening-panel.module.css";

type Props = { schoolId: string; refundId?: string; disabled?: boolean; onGuardChange: (blocked: boolean) => void; onSettled: () => Promise<void> };
export function RefundAccountReopeningPanel({ schoolId, refundId, disabled = false, onGuardChange, onSettled }: Props) {
  const t = useT("fees"), { locale } = useLanguage(), id = useId();
  const [filter, setFilter] = useState<ReopeningFilter>({schoolId,status:"SUBMITTED",query:"",page:0});
  const [selectedRefund, setSelectedRefund] = useState<string | undefined>(refundId);
  const [queue, setQueue] = useState<ReopeningQueue | null>(null);
  const [context, setContext] = useState<ReopeningContext | null>(null);
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(true);
  const [locked, setLocked] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [recorded, setRecorded] = useState(false);
  const [loadedFor, setLoadedFor] = useState({schoolId,refundId});
  const attempt = useRef<ReopeningAttempt | null>(null), unknown = useRef(false), accepted = useRef(false), writing = useRef(false);
  const read = useRef<AbortController | null>(null), lifecycle = useRef(0), applied = useRef(filter), selected = useRef(refundId);
  const feedback = useRef<HTMLParagraphElement | null>(null);
  const blocked = busy || locked || disabled;
  const money = (amount: number, currency: string) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN",{style:"currency",currency,minimumFractionDigits:2,maximumFractionDigits:2}).format(amount);
  const timestamp = (value: string) => `${new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN",{dateStyle:"medium",timeStyle:"short",timeZone:"UTC"}).format(new Date(value))} UTC`;
  const stateName = (state: string) => t(state === "SUBMITTED" ? "Awaiting independent account review" : state === "APPROVED" ? "Account reopening approved — refund not posted" : state === "REJECTED" ? "Account reopening rejected" : "Account reopening cancelled");
  const sourceName = (state: string) => t(state === "HOLD" ? "Confirmed refund hold — not posted" : state === "UNAPPROVED" ? "Refund intent is not approved" : state === "CONFLICTING" ? "Refund evidence needs reconciliation" : "No confirmed unposted refund hold");
  const load = useCallback(async (next: ReopeningFilter, target: string | undefined): Promise<boolean> => {
    if (read.current || next.schoolId !== schoolId) return false;
    const controller = new AbortController(), epoch = lifecycle.current; read.current = controller;
    setBusy(true); setError("");
    try {
      const [nextQueue,nextContext] = await Promise.all([loadReopeningQueue(next,controller.signal),target ? loadReopeningContext(schoolId,target,controller.signal) : Promise.resolve(null)]);
      if (controller.signal.aborted || epoch !== lifecycle.current) return false;
      applied.current = {...next,query:next.query.trim()}; selected.current = target;
      setQueue(nextQueue); setContext(nextContext); setSelectedRefund(target); setLoadedFor({schoolId,refundId}); return true;
    } catch (failure) { if (!controller.signal.aborted && epoch === lifecycle.current) { setQueue(null); setContext(null); setError(reopeningError(failure)); } return false; }
    finally { if (read.current === controller) read.current = null; if (!controller.signal.aborted && epoch === lifecycle.current) setBusy(false); }
  },[schoolId,refundId]);
  useEffect(() => {
    lifecycle.current += 1; let active = true;
    void Promise.resolve().then(() => {
      if (!active) return;
      if (attempt.current) { setQueue(null); setContext(null); setError(reopeningErrors.unknown); return; }
      const next: ReopeningFilter = {schoolId,status:"SUBMITTED",query:"",page:0}; setFilter(next);
      return load(next,refundId);
    });
    return () => { active = false; lifecycle.current += 1; const controller = read.current; controller?.abort(); if (read.current === controller) read.current = null; };
  },[schoolId,refundId,load]);
  useEffect(() => {
    if (!locked) return;
    const leave = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    const navigate = (event: MouseEvent) => { if (event.target instanceof Element && event.target.closest("a[href]")) { event.preventDefault(); event.stopPropagation(); setError(reopeningErrors.unknown); } };
    window.addEventListener("beforeunload",leave); document.addEventListener("click",navigate,true);
    return () => { window.removeEventListener("beforeunload",leave); document.removeEventListener("click",navigate,true); };
  },[locked]);
  useEffect(() => { if (notice) feedback.current?.focus(); },[notice]);
  async function refreshAccepted() {
    if (writing.current || read.current || !accepted.current || !attempt.current || attempt.current.schoolId !== schoolId) return;
    writing.current = true; const epoch = lifecycle.current, target = attempt.current; setBusy(true);
    try {
      if (!await load(applied.current,target.refundId)) return;
      await onSettled(); if (epoch !== lifecycle.current) return;
      attempt.current = null; accepted.current = false; unknown.current = false; setRecorded(false);
      setLocked(false); onGuardChange(false); setReason(""); setConfirmed(false);
    } catch { if (epoch === lifecycle.current) setError(reopeningErrors.unavailable); }
    finally { writing.current = false; if (epoch === lifecycle.current) setBusy(false); }
  }
  async function dispatch(next: ReopeningAttempt) {
    if (writing.current || read.current || accepted.current || next.schoolId !== schoolId || (disabled && !attempt.current) || (attempt.current && attempt.current !== next)) return;
    writing.current = true; attempt.current = next; setLocked(true); onGuardChange(true); setBusy(true); setError(""); setNotice("");
    const epoch = lifecycle.current;
    try {
      const token = await familyPaymentCsrf(new AbortController().signal).catch(() => { throw new ReopeningWriteError("rejected",reopeningErrors.csrf); });
      if (epoch !== lifecycle.current) return;
      await sendReopening(next,token);
      if (epoch !== lifecycle.current) return;
      accepted.current = true; setRecorded(true); setNotice("Account review recorded. Re-read the current account; no refund was posted and no dues were restored.");
    } catch (failure) {
      if (epoch !== lifecycle.current) return;
      if (!(failure instanceof ReopeningWriteError) || failure.outcome === "unknown" || unknown.current) { unknown.current = true; setError(reopeningErrors.unknown); }
      else { attempt.current = null; setLocked(false); onGuardChange(false); setError(reopeningError(failure)); }
    } finally { writing.current = false; if (epoch === lifecycle.current) setBusy(false); }
    if (accepted.current && epoch === lifecycle.current) await refreshAccepted();
  }
  function requestReopening(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (blocked || loadedFor.schoolId !== schoolId || loadedFor.refundId !== refundId || !confirmed || !context || context.schoolId !== schoolId || context.refundId !== selectedRefund) return;
    try { void dispatch(prepareReopening(context,reason,crypto.randomUUID())); } catch (failure) { setError(reopeningError(failure)); }
  }
  function decide(event: FormEvent<HTMLFormElement>, review: ReopeningReview) {
    event.preventDefault(); if (blocked || loadedFor.schoolId !== schoolId || loadedFor.refundId !== refundId) return;
    const fields = new FormData(event.currentTarget), decision = fields.get("decision");
    if (fields.get("confirm") !== "on" || (decision !== "APPROVE" && decision !== "REJECT" && decision !== "CANCEL")) return;
    try { void dispatch(prepareReopeningDecision(schoolId,review,decision,String(fields.get("reason") ?? ""))); } catch (failure) { setError(reopeningError(failure)); }
  }
  function apply(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if (!blocked) void load({...filter,schoolId,page:0},selected.current); }
  function closure(value: ReopeningClosure) {
    return <div className={styles.closure}><p>{t("Closed by {name} at {time}",{name:value.closedByName,time:timestamp(value.closedAt)})}</p><p className={styles.authored}>{value.reason}</p></div>;
  }
  function reviewCard(review: ReopeningReview, showSource: boolean) {
    return <li key={review.id}><article><h3>{review.learnerName} · {review.sessionName}</h3><p className={styles.state}>{stateName(review.state)}</p>
      <p>{t("Account review requested by {name} at {time}",{name:review.makerName,time:timestamp(review.requestedAt)})} {review.isMaker && <strong>{t("Your account reopening request")}</strong>}</p><p className={styles.authored}>{review.reason}</p>
      <h4>{t("Preserved original closure")}</h4>{closure(review.originalClosure)}
      {review.decision && <div className={styles.decision}><p>{t("Account decision by {name} at {time}",{name:review.decision.reviewerName,time:timestamp(review.decision.decidedAt)})}</p><p className={styles.authored}>{review.decision.reason}</p>{review.state === "APPROVED" && <p>{t("This approval records its original reopening transition, not the account's current status. A later closure remains possible.")}</p>}</div>}
      {showSource && <button type="button" aria-disabled={blocked} onClick={() => { if (!blocked) { setReason(""); setConfirmed(false); void load(applied.current,review.refundId); } }}>{t("View original account for this review")}</button>}
      {review.state === "SUBMITTED" && (review.canApprove || review.canReject || review.canCancel) && <form onSubmit={event => decide(event,review)} aria-label={t("Review original account for {learner}",{learner:review.learnerName})}>
        <fieldset disabled={blocked}><legend>{t("Independent account decision or maker cancellation")}</legend><label>{t("Account reopening decision")}<select name="decision">
          {review.canApprove && !review.isMaker && <option value="APPROVE">{t("Approve account reopening only")}</option>}{review.canReject && !review.isMaker && <option value="REJECT">{t("Reject account reopening")}</option>}{review.canCancel && review.isMaker && <option value="CANCEL">{t("Cancel my account reopening request")}</option>}
        </select></label><label>{t("Account reopening decision reason")}<textarea name="reason" required minLength={3} maxLength={500} /></label><label className={styles.check}><input name="confirm" type="checkbox" required />{t("I understand this decision does not post a refund, restore dues or reopen an academic session.")}</label></fieldset><button type="submit" aria-disabled={blocked}>{t("Record account reopening decision")}</button>
      </form>}
    </article></li>;
  }
  const scopeMatches = loadedFor.schoolId === schoolId && loadedFor.refundId === refundId;
  const visibleQueue = scopeMatches && queue?.schoolId === schoolId ? queue : null;
  const visibleContext = scopeMatches && context?.schoolId === schoolId && context.refundId === selectedRefund ? context : null;
  return <section className={styles.root} aria-labelledby={`${id}-heading`} aria-busy={busy}>
    <header><h2 id={`${id}-heading`}>{t("Original fee account reopening")}</h2><p>{t("Review only the original closed fee account. A different authorized person must approve reopening.")}</p></header>
    <p className={styles.warning}>{t("Account reopening is not a refund. This workflow does not move money, post accounting, restore dues, change receipts or reopen academic records.")}</p>
    {disabled && !locked && <p role="status">{t("Another financial command is unresolved. Account review actions are temporarily unavailable.")}</p>}
    {locked && <p role="status" className={styles.warning}>{t(recorded ? "The account review is recorded. Current account and related records must refresh before another command." : "Keep this page open. Do not repeat this unresolved account review from another tab. After a forced exit, inspect review history before making a new request.")}</p>}
    {busy && <p role="status">{t("Reading or recording account reopening review…")}</p>}
    {error && <p role="alert" className={styles.error}>{t(error)}</p>}
    <p ref={feedback} tabIndex={-1} role="status" className={styles.feedback} hidden={!notice}>{notice ? t(notice) : ""}</p>
    {locked && <button type="button" aria-disabled={busy} onClick={() => { if (!busy && attempt.current && !accepted.current) void dispatch(attempt.current); if (!busy && accepted.current) void refreshAccepted(); }}>{t(recorded ? "Refresh recorded account review" : "Retry this exact account review command")}</button>}
    {!locked && error && <button type="button" aria-disabled={blocked} onClick={() => { if (!blocked) void load(applied.current,selected.current); }}>{t("Refresh account reopening records")}</button>}
    {visibleContext && <section className={styles.context} aria-labelledby={`${id}-account`}><h3 id={`${id}-account`}>{visibleContext.learnerName} · {visibleContext.sessionName}</h3>
      <p className={styles.state}>{t(visibleContext.accountState === "CLOSED" ? "Original fee account is closed" : "Original fee account is active")}</p><p>{sourceName(visibleContext.sourceState)}</p>
      <dl className={styles.amounts}><div><dt>{t("Current account balance (signed)")}</dt><dd>{money(visibleContext.currentBalance,visibleContext.currencyCode)}</dd></div><div><dt>{t("Confirmed unposted refund amount")}</dt><dd>{visibleContext.confirmedUnpostedRefundAmount === null ? t("No confirmed unposted amount") : money(visibleContext.confirmedUnpostedRefundAmount,visibleContext.currencyCode)}</dd></div></dl>
      <p>{t("The signed account balance is separate from the confirmed unposted refund amount. A hold has not restored any dues.")}</p>
      {visibleContext.currentClosure && <><h4>{t("Current account closure")}</h4>{closure(visibleContext.currentClosure)}</>}
      {!visibleContext.canRequest && <p>{t("No new reopening request is available with the current account, refund evidence and access. Existing history remains below.")}</p>}
      {visibleContext.canRequest && <form onSubmit={requestReopening} aria-label={t("Prepare original account reopening")}><fieldset disabled={blocked}><legend>{t("Request independent reopening review")}</legend><label>{t("Account reopening request reason")}<textarea value={reason} onChange={event => { if (!blocked) { setReason(event.target.value); setConfirmed(false); } }} required minLength={3} maxLength={500} /></label><label className={styles.check}><input type="checkbox" checked={confirmed} required onChange={event => { if (!blocked) setConfirmed(event.target.checked); }} />{t("I checked this original closure and confirmed hold. This request alone cannot reopen the account or post a refund.")}</label></fieldset><button type="submit" aria-disabled={blocked || !confirmed}>{t("Submit account reopening request")}</button></form>}
      <h4>{t("Original account review history")}</h4>{!visibleContext.requests.length && <p>{t("No account reopening history is recorded for this refund.")}</p>}<ol className={styles.items} aria-label={t("Original account review history")}>{visibleContext.requests.map(review => reviewCard(review,false))}</ol>{visibleContext.hasMoreHistory && <p>{t("Only the latest 50 reviews are shown here. Use the review queue to find older records.")}</p>}
    </section>}
    <form onSubmit={apply} aria-label={t("Account reopening queue filters")}><fieldset disabled={blocked}><legend>{t("Find account reopening reviews")}</legend><div className={styles.filters}><label>{t("Account review status")}<select value={filter.status} onChange={event => { if (!blocked) setFilter({...filter,status:event.target.value as ReopeningFilter["status"]}); }}><option value="ALL">{t("All account reopening reviews")}</option>{reopeningStates.map(state => <option value={state} key={state}>{stateName(state)}</option>)}</select></label><label>{t("Learner or session search")}<input type="search" maxLength={100} autoComplete="off" value={filter.query} onChange={event => { if (!blocked) setFilter({...filter,query:event.target.value}); }} /></label></div></fieldset><button type="submit" aria-disabled={blocked}>{t("Apply account review filters")}</button></form>
    {visibleQueue && <><p role="status">{t("{count} account reviews · page {page}",{count:visibleQueue.total,page:visibleQueue.page+1})}</p>{!visibleQueue.items.length && <p>{t("No account reopening reviews match this page and filter.")}</p>}<ol className={styles.items} aria-label={t("Account reopening review queue")}>{visibleQueue.items.map(review => reviewCard(review,true))}</ol><nav className={styles.pages} aria-label={t("Account reopening queue pages")}><button type="button" aria-disabled={blocked || visibleQueue.page === 0} onClick={() => { if (!blocked && visibleQueue.page > 0) void load({...applied.current,page:visibleQueue.page-1},selected.current); }}>{t("Previous account review page")}</button><button type="button" aria-disabled={blocked || !visibleQueue.hasMore || visibleQueue.page >= 10000} onClick={() => { if (!blocked && visibleQueue.hasMore && visibleQueue.page < 10000) void load({...applied.current,page:visibleQueue.page+1},selected.current); }}>{t("Next account review page")}</button></nav></>}
  </section>;
}
