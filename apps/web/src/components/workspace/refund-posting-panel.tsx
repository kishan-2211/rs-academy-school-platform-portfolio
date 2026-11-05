"use client";

import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { familyPaymentCsrf, FamilyPaymentAccessError } from "@/lib/family-gateway-payments";
import { loadRefundPosting, loadRefundPostingYears, prepareRefundPosting, prepareRefundJournalRepair, refundPostingAccessLost, refundPostingError, refundPostingErrors, refundPostingPaise, refundSchoolToday, RefundPostingWriteError, sendRefundPosting, sendRefundJournalRepair, type RefundPosting, type RefundPostingAttempt, type RefundPostingContext, type RefundPostingYear, type RefundJournalRepair, type RefundJournalRepairAttempt } from "@/lib/gateway-refund-postings";
import styles from "./refund-posting-panel.module.css";

type Props = { schoolId: string; refundId?: string; disabled?: boolean; onGuardChange: (blocked: boolean) => void; onSettled: () => Promise<void> };
export function RefundPostingPanel({ schoolId, refundId, disabled = false, onGuardChange, onSettled }: Props) {
  const t=useT("fees"), {locale}=useLanguage(), id=useId();
  const [context,setContext]=useState<RefundPostingContext | null>(null);
  const [years,setYears]=useState<RefundPostingYear[]>([]);
  const [yearId,setYearId]=useState("");
  const [postingOn,setPostingOn]=useState("");
  const [reason,setReason]=useState("");
  const [confirmed,setConfirmed]=useState(false);
  const [busy,setBusy]=useState(false);
  const [locked,setLocked]=useState(false);
  const [recorded,setRecorded]=useState(false);
  const [error,setError]=useState("");
  const [bookError,setBookError]=useState("");
  const [notice,setNotice]=useState("");
  const [loadedFor,setLoadedFor]=useState({schoolId,refundId});
  const attempt=useRef<RefundPostingAttempt | RefundJournalRepairAttempt | null>(null), unknown=useRef(false), accepted=useRef(false), writing=useRef(false);
  const acknowledged=useRef<RefundPosting | RefundJournalRepair | null>(null);
  const read=useRef<AbortController | null>(null), lifecycle=useRef(0), feedback=useRef<HTMLParagraphElement | null>(null);
  const clearPrivate=useCallback(() => {
    attempt.current=null; unknown.current=false; accepted.current=false; acknowledged.current=null;
    setContext(null); setYears([]); setReason(""); setYearId(""); setPostingOn(""); setConfirmed(false);
    setLocked(false); setRecorded(false); setNotice(""); onGuardChange(false);
  },[onGuardChange]);
  const load=useCallback(async (target: string): Promise<RefundPostingContext | null> => {
    if (read.current) return null;
    const controller=new AbortController(), epoch=lifecycle.current; read.current=controller; setBusy(true); setError("");
    try {
      const next=await loadRefundPosting(schoolId,target,controller.signal);
      if (controller.signal.aborted || epoch !== lifecycle.current) return null;
      setContext(next); setLoadedFor({schoolId,refundId:target});
      // Read authority for books is separate from fee history. Do not hide an
      // already recorded posting merely because accounting options are unavailable.
      if (next.canPost || next.canRepairJournal) {
        try { const options=await loadRefundPostingYears(schoolId,controller.signal);
          if (controller.signal.aborted || epoch !== lifecycle.current) return null;
          setYears(options); setBookError("");
        } catch (failure) {
          if (controller.signal.aborted || epoch !== lifecycle.current) return null;
          if (refundPostingAccessLost(failure)) throw failure;
          setYears([]); setBookError(refundPostingErrors.books);
        }
      } else { setYears([]); setBookError(""); }
      return next;
    } catch (failure) {
      if (!controller.signal.aborted && epoch === lifecycle.current) {
        if (refundPostingAccessLost(failure)) clearPrivate();
        else if (!attempt.current) setContext(null);
        setError(refundPostingError(failure));
      }
      return null;
    } finally { if (read.current === controller) read.current=null; if (!controller.signal.aborted && epoch === lifecycle.current) setBusy(false); }
  },[schoolId,clearPrivate]);
  useEffect(() => {
    lifecycle.current+=1; let active=true;
    void Promise.resolve().then(() => { if (active && refundId) return load(refundId); });
    return () => { active=false; lifecycle.current+=1; const controller=read.current; controller?.abort(); if (read.current === controller) read.current=null; };
  },[refundId,load]);
  useEffect(() => {
    if (!locked) return;
    const leave=(event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue=""; };
    const navigate=(event: MouseEvent) => { if (event.target instanceof Element && event.target.closest("a[href]")) { event.preventDefault(); event.stopPropagation(); setError(refundPostingErrors.unknown); } };
    window.addEventListener("beforeunload",leave); document.addEventListener("click",navigate,true);
    return () => { window.removeEventListener("beforeunload",leave); document.removeEventListener("click",navigate,true); };
  },[locked]);
  useEffect(() => { if (notice) feedback.current?.focus(); },[notice]);
  async function refreshAccepted() {
    if (writing.current || read.current || !accepted.current || !attempt.current) return;
    writing.current=true; const epoch=lifecycle.current, original=attempt.current; setBusy(true);
    try {
      const fresh=await load(original.refundId);
      if (!fresh) return;
      if (fresh.sourceState !== "POSTED" || !fresh.posting
          || (original.kind === "JOURNAL_REPAIR"
            ? fresh.posting.id !== original.refundPostingId || !fresh.posting.accountingBatchId || !fresh.posting.journalId
                || fresh.posting.accountingBatchId === original.expectedBatchId || fresh.posting.journalId === original.expectedJournalId
            : fresh.posting.id !== (acknowledged.current as RefundPosting | null)?.id)
          || fresh.paymentId !== original.paymentId || fresh.accountId !== original.accountId
          || fresh.posting.currencyCode !== original.currencyCode
          || refundPostingPaise(fresh.posting.amount) !== refundPostingPaise(original.amount)) throw new Error(refundPostingErrors.unavailable);
      await onSettled(); if (epoch !== lifecycle.current) return;
      attempt.current=null; unknown.current=false; accepted.current=false; acknowledged.current=null; setLocked(false); setRecorded(false); onGuardChange(false);
      setReason(""); setConfirmed(false); setYearId(""); setPostingOn("");
    } catch { if (epoch === lifecycle.current) setError(refundPostingErrors.unavailable); }
    finally { writing.current=false; if (epoch === lifecycle.current) setBusy(false); }
  }
  async function dispatch(next: RefundPostingAttempt | RefundJournalRepairAttempt) {
    if (disabled || writing.current || read.current || accepted.current || (attempt.current && attempt.current !== next)) return;
    writing.current=true; attempt.current=next; setLocked(true); onGuardChange(true); setBusy(true); setError(""); setNotice("");
    const epoch=lifecycle.current;
    try {
      const token=await familyPaymentCsrf(new AbortController().signal).catch(failure => {
        if (failure instanceof FamilyPaymentAccessError && [401,403].includes(failure.status)) throw new RefundPostingWriteError("rejected",failure.status === 401 ? refundPostingErrors.session : refundPostingErrors.scope);
        throw new RefundPostingWriteError("rejected",refundPostingErrors.csrf);
      });
      if (epoch !== lifecycle.current) return;
      const result=next.kind === "JOURNAL_REPAIR" ? await sendRefundJournalRepair(next,token) : await sendRefundPosting(next,token); if (epoch !== lifecycle.current) return;
      acknowledged.current=result;
      accepted.current=true; setRecorded(true); setNotice(next.kind === "JOURNAL_REPAIR"
        ? "Replacement journal submitted for independent approval. No second refund or fee debit was created; original dates and closed periods remain unchanged."
        : "Verified refund recorded: dues restored on the original account. The original receipt remains unchanged; check the journal status separately.");
    } catch (failure) {
      if (epoch !== lifecycle.current) return;
      if (refundPostingAccessLost(failure)) { clearPrivate(); setError(refundPostingError(failure)); }
      else if (!(failure instanceof RefundPostingWriteError) || failure.outcome === "unknown" || unknown.current) { unknown.current=true; setError(next.kind === "JOURNAL_REPAIR" ? refundPostingErrors.repairUnknown : refundPostingErrors.unknown); }
      else { attempt.current=null; setLocked(false); onGuardChange(false); setError(refundPostingError(failure)); }
    } finally { writing.current=false; if (epoch === lifecycle.current) setBusy(false); }
    if (accepted.current && epoch === lifecycle.current) await refreshAccepted();
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || locked || disabled || !confirmed || !context || loadedFor.schoolId !== schoolId || loadedFor.refundId !== refundId || context.refundId !== refundId) return;
    try { void dispatch(context.canRepairJournal
      ? prepareRefundJournalRepair(context,years,yearId,postingOn,reason,crypto.randomUUID(),refundSchoolToday(context.schoolTimeZone))
      : prepareRefundPosting(context,years,yearId,postingOn,reason,crypto.randomUUID(),refundSchoolToday(context.schoolTimeZone))); }
    catch (failure) { setError(refundPostingError(failure)); }
  }
  const visible=context && loadedFor.schoolId === schoolId && loadedFor.refundId === refundId ? context : null;
  const blocked=busy || locked || disabled, options=years.filter(year => year.currencyCode === visible?.currencyCode), selectedYear=options.find(year => year.id === yearId);
  const repairing=!!visible?.canRepairJournal && !!visible.posting;
  const money=(value: number,currency: string) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN",{style:"currency",currency,minimumFractionDigits:2,maximumFractionDigits:2}).format(value);
  const stateName=(state: string) => t(state === "READY" ? "Verified refund is ready for reviewed recording" : state === "POSTED" ? "Verified refund financial record retained" : state === "NO_VERIFIED_SUCCESS" ? "No verified successful refund — no dues restored" : "Refund evidence needs review — no new posting available");
  const journalName=(status: string) => t(status === "SUBMITTED" ? "Journal awaiting independent approval" : status === "POSTED" ? "Journal posted to the general ledger" : status === "REVERSED" ? "Journal reversed — accounting review required" : status === "REJECTED" ? "Journal rejected — accounting review required" : status === "DRAFT" ? "Journal draft — not posted to the general ledger" : "Accounting evidence requires review");
  return <section className={styles.root} aria-labelledby={`${id}-title`} aria-busy={busy}>
    <header><h2 id={`${id}-title`}>{t("Record verified gateway refund")}</h2><p>{t("This records money already returned and cryptographically verified. It never sends money to a payment provider.")}</p></header>
    {!refundId && <p>{t("Choose an approved refund request to inspect its original account and financial posting.")}</p>}
    {disabled && !locked && <p role="status">{t("Another financial command is unresolved. Refund recording is temporarily unavailable.")}</p>}
    {busy && <p role="status">{t("Reading or recording verified refund…")}</p>}
    {error && <p className={styles.error} role="alert">{t(error)}</p>}
    <p ref={feedback} tabIndex={-1} role="status" className={styles.feedback} hidden={!notice}>{notice ? t(notice) : ""}</p>
    {locked && <><p className={styles.warning} role="status">{t(recorded ? "The refund record was acknowledged. Refresh the original account and related records before another command." : "Keep this page open. Do not repeat this unresolved refund posting from another tab. After a forced exit, inspect retained posting history before creating another command.")}</p><button type="button" aria-disabled={busy || disabled} onClick={() => { if (!busy && !disabled) { if (accepted.current) void refreshAccepted(); else if (attempt.current) void dispatch(attempt.current); } }}>{t(recorded ? "Refresh recorded refund posting" : repairing ? "Retry this exact journal correction command" : "Retry this exact refund posting command")}</button></>}
    {refundId && !locked && <button type="button" aria-disabled={blocked} onClick={() => { if (!blocked) void load(refundId); }}>{t("Refresh verified refund records")}</button>}
    {visible && <section className={styles.context} aria-labelledby={`${id}-source`}><h3 id={`${id}-source`}>{visible.learnerName} · {visible.academicSessionName}</h3><p>{t("Original gateway receipt")}: {visible.receiptNumber}</p><p className={styles.state}>{stateName(visible.sourceState)}</p>
      <dl className={styles.amounts}><div><dt>{t("Current account balance (signed)")}</dt><dd>{money(visible.balance,visible.currencyCode)}</dd></div><div><dt>{t("Verified refund amount")}</dt><dd>{visible.verifiedRefundAmount === null ? t("No verified refund amount") : money(visible.verifiedRefundAmount,visible.currencyCode)}</dd></div></dl>
      {visible.processedAtExact && <p>{t("Original gateway refund timestamp")}: <time dateTime={visible.processedAtExact}>{visible.processedAtExact}</time></p>}
      {visible.processedOn && <p>{t("Original school refund day")}: {visible.processedOn} · {visible.schoolTimeZone}</p>}
      {visible.originalDayClosed && <p className={styles.warning}>{t("The original financial day is closed. It stays closed; select an eligible open posting date. Original gateway timestamps are preserved.")}</p>}
      {visible.accountStatus === "CLOSED" && <p className={styles.warning}>{t(visible.posting ? "The original fee account is closed. Accounting-only repair does not reopen it or change its retained dues." : "The original fee account is closed. Independent reopening approval is required before recording restored dues. Academic history is not reactivated.")}</p>}
      {!visible.posting && <p>{t("A verified refund awaiting recording has not restored dues. Do not add its amount to the displayed account balance.")}</p>}
      {visible.posting && <section className={styles.retained} aria-labelledby={`${id}-retained`}><h4 id={`${id}-retained`}>{t("Retained refund posting")}</h4><p>{t("Dues restored on original account")}: {money(visible.posting.amount,visible.posting.currencyCode)}</p><p>{t("Financial posting date")}: <time dateTime={visible.posting.postingOn}>{visible.posting.postingOn}</time></p><p>{t("Recorded at")}: <time dateTime={visible.posting.postedAt}>{visible.posting.postedAt}</time></p><p className={styles.state}>{journalName(visible.posting.journalStatus)}</p><p>{t("The numbered original receipt is unchanged. Returning money does not cancel the fee; any waiver requires a separate approval.")}</p><p className={styles.reference}>{t("Refund posting reference")}: {visible.posting.id}</p><p className={styles.reference}>{t("Journal reference")}: {visible.posting.journalId ?? t("Not available — accounting review required")}</p></section>}
      {visible.posting?.journalPostingOn && <p>{t("Current refund journal accounting date")}: <time dateTime={visible.posting.journalPostingOn}>{visible.posting.journalPostingOn}</time></p>}
      {((visible.canPost && !visible.posting) || repairing) && <form onSubmit={submit} aria-label={t(repairing ? "Prepare accounting-only refund journal correction" : "Prepare reviewed refund posting")}><fieldset disabled={blocked}><legend>{t(repairing ? "Review rejected or reversed journal and open accounting date" : "Review original refund and open accounting book")}</legend>
        {repairing && <p className={styles.warning}>{t("This replaces only the selected rejected or reversed journal. It does not issue money, restore dues again, alter the original refund date or reopen a closed period.")}</p>}
        {bookError && <p className={styles.error} role="alert">{t(bookError)}</p>}{!options.length && <p>{t("No eligible open matching-currency financial year is available. Ask the accountant to configure or review the book.")}</p>}
        <label>{t("Refund posting financial year")}<select value={yearId} required onChange={event => { if (!blocked) { setYearId(event.target.value); setPostingOn(""); setConfirmed(false); } }}><option value="">{t("Choose open financial year")}</option>{options.map(year => <option key={year.id} value={year.id}>{year.name} · {year.startsOn} – {year.endsOn}</option>)}</select></label>
        <label>{t(repairing ? "Replacement journal accounting date" : "Refund posting date")}<input type="date" required value={postingOn} min={selectedYear ? [selectedYear.startsOn,repairing ? visible.posting?.journalPostingOn ?? visible.posting?.postingOn ?? selectedYear.startsOn : visible.processedOn ?? selectedYear.startsOn].sort().at(-1) : undefined} max={selectedYear?.endsOn} onChange={event => { if (!blocked) { setPostingOn(event.target.value); setConfirmed(false); } }} /></label>
        <label>{t("Refund posting review reason")}<textarea required minLength={3} maxLength={500} value={reason} onChange={event => { if (!blocked) { setReason(event.target.value); setConfirmed(false); } }} /></label>
        <label className={styles.check}><input type="checkbox" required checked={confirmed} onChange={event => { if (!blocked) setConfirmed(event.target.checked); }} />{t(repairing ? "I reviewed the exact prior journal and open accounting date. Submit its replacement for independent approval without another refund or fee debit." : "I reviewed the verified refund and original account. This restores dues and prepares a balanced journal for independent approval, without issuing another refund.")}</label>
      </fieldset><button type="submit" aria-disabled={blocked || !confirmed || !options.length}>{t(repairing ? "Submit replacement refund journal" : "Record verified refund and restore dues")}</button></form>}
    </section>}
  </section>;
}
