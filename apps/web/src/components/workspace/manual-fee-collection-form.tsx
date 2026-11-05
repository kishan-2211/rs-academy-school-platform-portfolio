"use client";

import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import {
  availableManualChargePaise, buildManualCollectionRequest, currentSchoolLocalTime,
  freezeManualCollectionAttempt, manualAmountText, ManualCollectionValidationError,
  schoolLocalTimeToInstant, selectedManualTotalPaise, sendManualCollectionAttempt,
  type FrozenManualCollectionAttempt, type ManualCollectionContext, type ManualCollectionDraft,
  type ManualFeeCharge, type ManualPaymentMethod,
} from "@/lib/manual-fee-collection";

export type ManualFeeCollectionFormProps = {
  schoolId: string; accountId: string; learnerName: string; currencyCode: string; balance: number;
  schoolTimeZone: string; schoolToday: string; latestClosedDate?: string; charges: ManualFeeCharge[];
  onCollected: () => Promise<void>; externallyBusy?: boolean;
  onCollectionGuardChange?: (blocked: boolean) => void;
};
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const methodLabel = (method: ManualPaymentMethod) => method === "CASH" ? "Cash" : method === "SCHOOL_UPI"
  ? "School UPI — verified bank credit" : method === "BANK_TRANSFER" ? "Bank transfer — verified bank credit" : "Cheque";
const sourceLabel = (source: string) => source === "BANK_APP_CREDIT" ? "School bank app" : "School bank statement";
const controlledRejections: Record<string, string> = {
  ACCESS_DENIED: "Financial access is no longer available for this school.",
  FINANCE_SCOPE_DENIED: "Financial access is no longer available for this school.",
  MANUAL_COLLECTION_SCOPE_DENIED: "Financial access is no longer available for this school.",
  MANUAL_BANK_CREDIT_ALREADY_RECORDED: "This bank credit already has a school receipt. Review its history; do not record it again.",
  MANUAL_BANK_CREDIT_RESERVED_FOR_SETTLEMENT: "This bank credit is reserved for gateway settlement review. Review that evidence; do not issue another fee receipt for it.",
  MANUAL_COLLECTION_POSTING_DATE_CLOSED: "The posting date is closed. Choose an eligible open date and keep the original received time.",
  MANUAL_COLLECTION_REVIEW_REQUIRED: "The original financial day is closed. An authorized review reason and open posting date are required.",
  MANUAL_COLLECTION_FUTURE_DATE: "The received time or posting date is in the future. Check the school time zone and original record.",
  MANUAL_COLLECTION_EVIDENCE_CONFLICT: "The collection evidence conflicts with an existing record. Refresh and review the receipt history.",
  PAYMENT_REFERENCE_REQUIRED: "Enter the actual bounded bank or cheque reference, not a screenshot or payment credential.",
  IDEMPOTENCY_KEY_REUSED: "This request key belongs to a different collection. Review the existing receipt history before continuing.",
  PAYMENT_EXCEEDS_BALANCE: "The collection exceeds the available fees. Advance payments are not supported here.",
  ALLOCATION_EXCEEDS_CHARGE: "A fee period changed or has an online reservation. Refresh the account before collecting.",
  PARTIAL_PAYMENT_DISABLED: "This fee period requires its full outstanding amount.",
};
function initialDraft(schoolToday: string): ManualCollectionDraft {
  return { paymentMethod: "CASH", amount: "", externalReference: "", notes: "", allocations: {},
    actualLocal: "", utcOffset: "", chequeReceivedOn: schoolToday, postingOn: "", reviewReason: "",
    cashCounted: false, bankCreditConfirmed: false, bankCreditSource: "BANK_APP_CREDIT" };
}
async function csrfToken() {
  const read = () => document.cookie.split("; ").find((part) => part.startsWith("educore_csrf="))?.slice(13);
  let token = read();
  if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store" }); token = read(); }
  if (!token) throw new Error("Secure form token unavailable");
  return decodeURIComponent(token);
}

/** Parent renders only for fees.collect and keys this form by school/account. Server authorization remains authoritative. */
export function ManualFeeCollectionForm(props: ManualFeeCollectionFormProps) {
  const t = useT("fees");
  const { locale } = useLanguage();
  const formId = useId();
  const [draft, setDraft] = useState(() => initialDraft(props.schoolToday));
  const [reviewDate, setReviewDate] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState<{ id: string; receiptNumber: string } | null>(null);
  const [refreshed, setRefreshed] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const frozen = useRef<FrozenManualCollectionAttempt | null>(null);
  const uncertainEver = useRef(false);
  const inFlight = useRef(false);
  const lifecycle = useRef(0);
  const originalScope = useRef(`${props.schoolId}:${props.accountId}`);
  useEffect(() => {
    lifecycle.current += 1;
    let active = true;
    // No clock-based server-rendered default: use the configured school zone after hydration.
    void Promise.resolve().then(() => {
      if (!active) return;
      const actualLocal = currentSchoolLocalTime(props.schoolTimeZone, new Date());
      if (actualLocal) setDraft((current) => current.actualLocal ? current : { ...current, actualLocal });
    });
    return () => { active = false; lifecycle.current += 1; };
  }, [props.schoolTimeZone, props.schoolId, props.accountId]);
  useEffect(() => {
    if (!busy && !uncertain && !(receipt && !refreshed)) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [busy, uncertain, receipt, refreshed]);
  const context: ManualCollectionContext = props;
  const cash = draft.paymentMethod === "CASH";
  const bank = draft.paymentMethod === "SCHOOL_UPI" || draft.paymentMethod === "BANK_TRANSFER";
  const cheque = draft.paymentMethod === "CHEQUE";
  const originalDate = cheque ? draft.chequeReceivedOn : draft.actualLocal.slice(0, 10);
  const closedOriginalDay = Boolean(!cheque && props.latestClosedDate && originalDate && originalDate <= props.latestClosedDate);
  const reviewed = !cheque && (reviewDate || closedOriginalDay);
  const timeResolution = useMemo(() => !cheque && draft.actualLocal
    ? schoolLocalTimeToInstant(draft.actualLocal, props.schoolTimeZone) : null, [cheque, draft.actualLocal, props.schoolTimeZone]);
  const locked = busy || Boolean(props.externallyBusy) || uncertain || Boolean(receipt);
  const total = selectedManualTotalPaise(draft.allocations);
  const money = (amount: number) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", {
    style: "currency", currency: props.currencyCode, maximumFractionDigits: 2,
  }).format(amount);
  const update = <K extends keyof ManualCollectionDraft>(key: K, value: ManualCollectionDraft[K]) => {
    if (!locked) {
      const receivedEvidenceChanged = ["amount", "actualLocal", "utcOffset", "externalReference", "bankCreditSource"].includes(key);
      setDraft((current) => ({ ...current, [key]: value,
        ...(receivedEvidenceChanged ? { cashCounted: false, bankCreditConfirmed: false } : {}) }));
      setError("");
    }
  };
  function changeAllocation(chargeId: string, value: string | null) {
    if (locked) return;
    setDraft((current) => {
      const previousTotal = selectedManualTotalPaise(current.allocations);
      const allocations = { ...current.allocations };
      if (value === null) delete allocations[chargeId]; else allocations[chargeId] = value;
      const nextTotal = selectedManualTotalPaise(allocations);
      const followTotal = !current.amount || previousTotal !== null && current.amount === manualAmountText(previousTotal);
      return { ...current, allocations, cashCounted: false, bankCreditConfirmed: false,
        amount: followTotal && nextTotal !== null ? nextTotal ? manualAmountText(nextTotal) : "" : current.amount };
    });
    setError("");
  }
  function changeMethod(method: ManualPaymentMethod) {
    if (locked) return;
    setDraft((current) => ({ ...current, paymentMethod: method, externalReference: "", cashCounted: false,
      bankCreditConfirmed: false, postingOn: "", reviewReason: "", utcOffset: "" }));
    setReviewDate(false); setError("");
  }
  async function refreshAccount() {
    if (inFlight.current || refreshing || props.externallyBusy) return;
    const epoch = lifecycle.current;
    setRefreshing(true);
    try { await props.onCollected(); if (epoch === lifecycle.current) {
      if (receipt) { setRefreshed(true); props.onCollectionGuardChange?.(false); }
      setError("");
    } }
    catch { if (epoch === lifecycle.current) setError(t(receipt ? "The receipt was posted, but account details could not be refreshed."
      : uncertain ? "Account details could not be refreshed. The uncertain collection remains locked." : "Account details could not be refreshed. Keep the draft and try again.")); }
    finally { if (epoch === lifecycle.current) setRefreshing(false); }
  }
  async function collect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || refreshing || props.externallyBusy || receipt
        || originalScope.current !== `${props.schoolId}:${props.accountId}`) return;
    let attempt = frozen.current;
    if (!uncertain) {
      try {
        const request = buildManualCollectionRequest(context, { ...draft, postingOn: reviewed ? draft.postingOn : "",
          reviewReason: reviewed ? draft.reviewReason : "" }, new Date());
        if (!window.confirm(t("Record {amount} for {name} by {method}? Received on {received}; posting on {posting}. Verification: {source}. This issues a numbered receipt.", {
          amount: money(request.amount), name: props.learnerName, method: t(methodLabel(request.paymentMethod)),
          received: request.receivedOn, posting: request.postingOn || request.receivedOn,
          source: t(request.paymentMethod === "CASH" ? "Cash counted" : request.paymentMethod === "CHEQUE" ? "Existing cheque policy" : sourceLabel(draft.bankCreditSource)),
        }))) return;
        attempt = freezeManualCollectionAttempt(request, crypto.randomUUID());
        frozen.current = attempt;
      } catch (reason) {
        setError(t(reason instanceof ManualCollectionValidationError ? reason.message : "The collection could not be prepared. Check the fields and refresh the account.")); return;
      }
    } else if (!attempt || !window.confirm(t("Retry the same {amount} collection for {name}? The saved request and key remain unchanged so an acknowledged receipt is not duplicated.", {
      amount: money(attempt.request.amount), name: props.learnerName,
    }))) return;
    if (!attempt) return;
    const epoch = lifecycle.current;
    const current = () => epoch === lifecycle.current;
    inFlight.current = true; props.onCollectionGuardChange?.(true); setBusy(true); setError("");
    try {
      const result = await sendManualCollectionAttempt(attempt, { apiUrl, csrfToken, fetch, shouldDispatch: current });
      if (!current()) return;
      if (result.kind === "posted") {
        setReceipt({ id: result.id, receiptNumber: result.receiptNumber }); setRefreshed(false); setUncertain(false); uncertainEver.current = false;
        try { await props.onCollected(); if (current()) { setRefreshed(true); props.onCollectionGuardChange?.(false); } }
        catch { if (current()) setError(t("The receipt was posted, but account details could not be refreshed.")); }
      } else if (result.kind === "uncertain") {
        uncertainEver.current = true; setUncertain(true);
        setError(t("The collection response is uncertain. Do not collect again or change the draft. Review receipt history and retry only this saved request."));
      } else if (result.kind === "rejected") {
        // A later rejection cannot prove that an earlier timed-out request never posted.
        if (uncertainEver.current) { setUncertain(true); setError(t("The earlier response remains uncertain. Review receipt history; retry only the unchanged saved request.")); }
        else {
          frozen.current = null; props.onCollectionGuardChange?.(false);
          setError(t(result.code && Object.hasOwn(controlledRejections, result.code) ? controlledRejections[result.code]!
            : "The collection was not accepted. Check the fields and refresh the account."));
        }
      } else if (result.kind === "not-dispatched") {
        if (!uncertainEver.current) { frozen.current = null; props.onCollectionGuardChange?.(false); }
        setError(t("The secure form token is unavailable. Refresh and try again."));
      }
    } finally { if (current()) { inFlight.current = false; setBusy(false); } }
  }
  function nextCollection() {
    if (!receipt || !refreshed || busy || refreshing || props.externallyBusy) return;
    frozen.current = null; uncertainEver.current = false;
    const next = initialDraft(props.schoolToday);
    next.actualLocal = currentSchoolLocalTime(props.schoolTimeZone, new Date()) || "";
    setDraft(next); setReviewDate(false); setReceipt(null); setRefreshed(false); setError("");
  }

  return <section className="manual-collection" aria-labelledby={`${formId}-title`}>
    <header><span>{t("Verified manual collection")}</span><h3 id={`${formId}-title`}>{t("Record money the school has actually received")}</h3>
      <p>{t("Cash is counted at school. UPI and bank transfers require a confirmed credit in the school's own bank record; a parent's screenshot or reference alone is not proof.")}</p>
    </header>
    {error && <p className="manual-collection__alert manual-collection__alert--error" role="alert">{error}</p>}
    {error && !receipt && !uncertain && <button type="button" disabled={busy || refreshing || props.externallyBusy} onClick={() => void refreshAccount()}>{t(refreshing ? "Refreshing account…" : "Refresh account without discarding the draft")}</button>}
    {receipt && <div className="manual-collection__alert manual-collection__alert--success" role="status"><strong>{t("Receipt {receipt} was posted.", { receipt: receipt.receiptNumber })}</strong>
      <p>{t("The numbered receipt and ledger entry are saved. A failed screen refresh does not cancel the collection.")}</p>
      {refreshed ? <button type="button" onClick={nextCollection} disabled={busy || refreshing || props.externallyBusy}>{t("Start a separate collection")}</button>
        : <button type="button" onClick={() => void refreshAccount()} disabled={busy || refreshing || props.externallyBusy}>{t(refreshing ? "Refreshing account…" : "Refresh the posted account")}</button>}
    </div>}
    {uncertain && <div className="manual-collection__alert" role="status"><p>{t("The draft is locked to prevent a duplicate receipt. Retrying sends the same amount, allocations, evidence and request key.")}</p>
      <p>{t("Check receipt history with the school before starting another cash collection if you leave this page. Leaving or reloading can lose this in-memory retry request.")}</p>
      <button type="button" disabled={busy || refreshing || props.externallyBusy} onClick={() => void refreshAccount()}>{t(refreshing ? "Refreshing account…" : "Refresh account and receipt history")}</button>
    </div>}
    <form onSubmit={(event) => void collect(event)} aria-label={t("Manual collection for {name}", { name: props.learnerName })} aria-busy={busy || refreshing}>
      <fieldset disabled={locked}><legend>{t("Collection details")}</legend>
        <div className="manual-collection__fields"><label>{t("Collection method")}<select value={draft.paymentMethod} onChange={(event) => changeMethod(event.target.value as ManualPaymentMethod)}>
          <option value="CASH">{t("Cash")}</option><option value="SCHOOL_UPI">{t("School UPI — verified bank credit")}</option>
          <option value="BANK_TRANSFER">{t("Bank transfer — verified bank credit")}</option><option value="CHEQUE">{t("Cheque")}</option>
        </select></label><label>{t("Amount received")}<input name="amount" inputMode="decimal" autoComplete="off" value={draft.amount} onChange={(event) => update("amount", event.target.value)} pattern="(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,2})?" required aria-describedby={`${formId}-allocation-total`} /></label></div>
        <fieldset className="manual-collection__periods"><legend>{t("Choose fee periods covered by this receipt")}</legend>
          {props.charges.filter((charge) => charge.outstandingAmount > 0).length ? props.charges.filter((charge) => charge.outstandingAmount > 0).map((charge) => {
            const available = availableManualChargePaise(charge);
            const selected = Object.hasOwn(draft.allocations, charge.id);
            return <div className="manual-collection__period" key={charge.id}>
              <label className="manual-collection__choice"><input type="checkbox" checked={selected} disabled={available === null || available <= 0} onChange={(event) => changeAllocation(charge.id, event.target.checked && available !== null ? manualAmountText(available) : null)} />
                <span><strong>{charge.label}</strong><small>{charge.feeHeadName} · {t("Due {date}", { date: charge.dueOn })}</small>
                  <small>{t(charge.allowPartial ? "Partial payment allowed" : "Full outstanding period required")}</small>
                  {(charge.reservedAmount ?? 0) > 0 && <small>{t("Online checkout reserved {amount}; this portion cannot be collected again.", { amount: money(charge.reservedAmount || 0) })}</small>}
                </span></label>
              <div><span>{t("Available {amount}", { amount: money((available ?? 0) / 100) })}</span>
                {selected && <label>{t("Allocate to {period}", { period: charge.label })}<input type="text" inputMode="decimal" value={draft.allocations[charge.id]} onChange={(event) => changeAllocation(charge.id, event.target.value)} readOnly={!charge.allowPartial} required /></label>}
              </div>
            </div>;
          }) : <p>{t("No outstanding fee periods are available for collection.")}</p>}
          <p id={`${formId}-allocation-total`} className="manual-collection__total">{t("Selected total: {amount} · Account balance: {balance}", { amount: money((total ?? 0) / 100), balance: money(props.balance) })}</p>
          <p className="manual-collection__hint">{t("Select one or several periods. Their allocations must equal the received amount. Excess or unallocated advance payments are not accepted here.")}</p>
        </fieldset>
        <div className="manual-collection__fields">
          {cheque ? <label>{t("Cheque received school date")}<input type="date" value={draft.chequeReceivedOn} max={props.schoolToday} min="2000-01-01" onChange={(event) => update("chequeReceivedOn", event.target.value)} required /></label>
            : <label>{t("Actual received time — {zone}", { zone: props.schoolTimeZone })}<input type="datetime-local" step="1" min="2000-01-01T00:00" max={`${props.schoolToday}T23:59:59`} value={draft.actualLocal} onChange={(event) => { update("actualLocal", event.target.value); update("utcOffset", ""); }} required aria-describedby={`${formId}-time-help`} /></label>}
          <label>{t(cash ? "Cash reference (optional)" : bank ? "Verified bank credit reference" : "Cheque reference")}<input type="text" maxLength={120} value={draft.externalReference} autoComplete="off" onChange={(event) => update("externalReference", event.target.value)} required={!cash} /></label>
        </div>
        {!cheque && <p id={`${formId}-time-help`} className="manual-collection__hint">{t("Original received day: {date}. This is the school's time zone, not this device's time zone.", { date: originalDate || t("Not entered") })}</p>}
        {timeResolution?.kind === "ambiguous" && <label>{t("Original UTC offset (for repeated local time)")}<input value={draft.utcOffset} placeholder="+05:30" pattern="[+-][0-9]{2}:[0-9]{2}" maxLength={6} onChange={(event) => update("utcOffset", event.target.value)} required /></label>}
        {cash && <label className="manual-collection__attestation"><input type="checkbox" checked={draft.cashCounted} onChange={(event) => update("cashCounted", event.target.checked)} required /><span>{t("I counted this cash and the school actually received the amount shown.")}</span></label>}
        {bank && <><label>{t("Bank credit checked in")}<select value={draft.bankCreditSource} onChange={(event) => update("bankCreditSource", event.target.value as ManualCollectionDraft["bankCreditSource"])}><option value="BANK_APP_CREDIT">{t("School bank app")}</option><option value="BANK_STATEMENT_CREDIT">{t("School bank statement")}</option></select></label>
          <label className="manual-collection__attestation"><input type="checkbox" checked={draft.bankCreditConfirmed} onChange={(event) => update("bankCreditConfirmed", event.target.checked)} required /><span>{t("I checked the school's bank record: this exact amount is credited, with the reference and actual received time shown. A screenshot or a pending transfer is not enough.")}</span></label></>}
        {cheque && <p className="manual-collection__hint">{t("Cheque collection retains the existing school workflow. This form does not assert bank clearance or change cheque accounting policy.")}</p>}
        {!cheque && <><label className="manual-collection__choice"><input type="checkbox" checked={reviewed} disabled={closedOriginalDay} onChange={(event) => { setReviewDate(event.target.checked); if (!event.target.checked) { update("postingOn", ""); update("reviewReason", ""); } }} /><span>{t("Review and post on an explicitly chosen open accounting date")}</span></label>
          {closedOriginalDay && <p className="manual-collection__alert">{t("The original financial day is closed. Preserve its evidence and choose an eligible open posting date after review; do not reopen or change the original day.")}</p>}
          {reviewed && <div className="manual-collection__fields"><label>{t("Eligible open posting date")}<input type="date" min={originalDate || "2000-01-01"} max={props.schoolToday} value={draft.postingOn} onChange={(event) => update("postingOn", event.target.value)} required /></label>
            <label>{t("Review reason")}<textarea rows={2} minLength={3} maxLength={240} value={draft.reviewReason} onChange={(event) => update("reviewReason", event.target.value)} required /></label></div>}
        </>}
        <label>{t("Collection notes (optional)")}<textarea rows={2} maxLength={500} value={draft.notes} onChange={(event) => update("notes", event.target.value)} /></label>
        <p className="manual-collection__hint">{t("Cash and UPI together? Record each received part separately. Each part gets its own receipt; do not combine evidence from different payment methods.")}</p>
      </fieldset>
      {!receipt && <button className="manual-collection__submit" type="submit" disabled={busy || refreshing || props.externallyBusy || !uncertain && props.balance <= 0} formNoValidate={uncertain}>{t(busy ? "Posting collection…" : uncertain ? "Retry the unchanged saved request" : "Review and issue receipt")}</button>}
      <p className="manual-collection__hint">{t("The server checks live permissions, available fee periods, evidence, duplicates and open dates again before issuing the receipt. No closed financial history is rewritten.")}</p>
    </form>
  </section>;
}
