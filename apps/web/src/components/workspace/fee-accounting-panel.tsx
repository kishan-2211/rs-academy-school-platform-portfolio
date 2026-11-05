"use client";

import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { Check, Shield } from "@/components/icons";
import { feeTenderAccounts } from "@/lib/fee-tender-accounts";
import { useLanguage } from "@/components/i18n/language-provider";
import { accountingCode, accountingError, accountingPresentation, accountingText, AccountingUiError, type AccountingCopy, type AccountingPresentation } from "@/lib/i18n/accounting-messages";

type FinancialYear = {
  id: string;
  name: string;
  startsOn: string;
  endsOn: string;
  accountingBasis: string;
  currencyCode: string;
  status: string;
};

type Account = {
  id: string;
  code: string;
  name: string;
  accountType: string;
  category: string;
  allowDirectPosting: boolean;
  status: string;
};

type FeeHead = { id: string; code: string; name: string; status: string };
type HeadMapping = { feeHeadId: string; feeHeadCode: string; feeHeadName: string; incomeAccountId: string };
type TenderMapping = { paymentMethod: PaymentMethod; depositAccountId: string };
type Configuration = {
  id: string;
  financialYearId: string;
  supersedesConfigurationId?: string;
  revisionNumber: number;
  effectiveFrom: string;
  accountingBasis: string;
  currencyCode: string;
  receivableAccountId?: string;
  concessionAccountId?: string;
  status: string;
  creatorName: string;
  createdAt: string;
  submitterName?: string;
  submittedAt?: string;
  deciderName?: string;
  decidedAt?: string;
  rowVersion: number;
  headMappings: HeadMapping[];
  tenderMappings: TenderMapping[];
};
type PostingBatch = {
  id: string;
  businessDate: string;
  eventType: EventType;
  disposition: string;
  sequenceNumber: number;
  sourceCount: number;
  activeSourceCount: number;
  sourceAmount: number;
  journalStatus?: string;
  reconciliationStatus: string;
  preparerName: string;
  preparedAt: string;
};
type Snapshot = {
  schoolId: string;
  canConfigure: boolean;
  canPrepare: boolean;
  canApprove: boolean;
  feeHeads: FeeHead[];
  configurations: Configuration[];
  postingBatches: PostingBatch[];
  unpreparedEvents: { eventType: EventType; sourceCount: number; sourceAmount: number }[];
  reconciliation: {
    reconciledBatches: number;
    awaitingApprovalBatches: number;
    noGlEffectBatches: number;
    repreparedBatches: number;
    requiresRepreparationBatches: number;
    unpreparedSourceCount: number;
    unpreparedSourceAmount: number;
  };
};

type EventType = "CHARGE" | "CONCESSION" | "PAYMENT" | "PAYMENT_REVERSAL";
type PaymentMethod = "CASH" | "BANK_TRANSFER" | "CHEQUE" | "SCHOOL_UPI" | "ONLINE_PROVIDER";
type MappingDecision = { title: string; titleCopy?: AccountingCopy; path: string; financialYearId: string; body: Record<string, unknown>; success: string };
type Props = {
  schoolId: string;
  selectedYear?: FinancialYear;
  accounts: Account[];
  money: Intl.NumberFormat;
  onAccountingChanged: () => Promise<unknown>;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const events: EventType[] = ["CHARGE", "CONCESSION", "PAYMENT", "PAYMENT_REVERSAL"];
const tenders: PaymentMethod[] = ["CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER"];
const label = (value?: string) => value ? value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase()) : "Not set";
const localToday = () => { const value = new Date(); value.setMinutes(value.getMinutes() - value.getTimezoneOffset()); return value.toISOString().slice(0, 10); };
const clampDate = (value: string, minimum: string, maximum: string) => value < minimum ? minimum : value > maximum ? maximum : value;
const nextDate = (value: string) => { const date = new Date(`${value}T00:00:00Z`); date.setUTCDate(date.getUTCDate() + 1); return date.toISOString().slice(0, 10); };
const cookie = (name: string) => document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new AccountingUiError("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string): Promise<AccountingPresentation> { try { const detail = ((await response.json()) as { detail?: unknown }).detail; return typeof detail === "string" && detail ? { literal: detail } : fallback; } catch { return fallback; } }

function FeeMappingEvidence({ configuration, accounts, locale = "en" }: { configuration: Configuration; accounts: Account[]; locale?: string }) {
  const t = (source: string, values?: Record<string,string|number>) => accountingText(locale,source,values);
  const displayCode = (value?: string) => accountingCode(locale,value);
  const accountName = (id?: string) => { const account = accounts.find((item) => item.id === id); return account ? `${account.code} · ${account.name}` : t("Account unavailable — verify before approval"); };
  return <details className="fee-accounting-mapping-evidence" open={configuration.status === "SUBMITTED"}>
    <summary>{t("Review mapping revision {revision}",{revision:configuration.revisionNumber})}</summary>
    <dl>
      <div><dt>{t("Effective from")}</dt><dd>{configuration.effectiveFrom}</dd></div>
      <div><dt>{t("Accounting basis / currency")}</dt><dd>{displayCode(configuration.accountingBasis)} · {configuration.currencyCode}</dd></div>
      {configuration.accountingBasis === "ACCRUAL" && <><div><dt>{t("Student fee receivable")}</dt><dd>{accountName(configuration.receivableAccountId)}</dd></div><div><dt>{t("Concession expense")}</dt><dd>{accountName(configuration.concessionAccountId)}</dd></div></>}
      {configuration.headMappings.map((mapping) => <div key={mapping.feeHeadId}><dt>{mapping.feeHeadCode} · {mapping.feeHeadName}</dt><dd>{accountName(mapping.incomeAccountId)}</dd></div>)}
      {configuration.tenderMappings.map((mapping) => <div key={mapping.paymentMethod}><dt>{t("{method} deposit",{method:displayCode(mapping.paymentMethod)})}</dt><dd>{accountName(mapping.depositAccountId)}</dd></div>)}
    </dl>
  </details>;
}

export function FeeAccountingPanel({ schoolId, selectedYear, accounts, money, onAccountingChanged }: Props) {
  const { locale } = useLanguage(), regionId = useId();
  const t = (source: string, values?: Record<string,string|number>) => accountingText(locale,source,values);
  const displayCode = (value?: string) => accountingCode(locale,value);
  const [data, setData] = useState<Snapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<AccountingPresentation>("");
  const [notice, setNotice] = useState("");
  const [decision, setDecision] = useState<MappingDecision | null>(null);
  const [refreshRequired, setRefreshRequired] = useState(false);
  const decisionTrigger = useRef<HTMLElement | null>(null);
  const decisionPanel = useRef<HTMLFormElement | null>(null);
  const decisionReason = useRef<HTMLTextAreaElement | null>(null);
  const decisionInFlight = useRef(false);
  const noticeTarget = useRef<HTMLDivElement | null>(null);
  const [receivableAccountId, setReceivableAccountId] = useState("");
  const [concessionAccountId, setConcessionAccountId] = useState("");
  const [effectiveFrom, setEffectiveFrom] = useState("");
  const [headMappings, setHeadMappings] = useState<Record<string, string>>({});
  const [tenderMappings, setTenderMappings] = useState<Record<string, string>>({});
  const [changeReason, setChangeReason] = useState("");
  const postingKey = useRef("");

  useEffect(() => {
    if (decision) decisionPanel.current?.scrollIntoView({ block: "start", behavior: "instant" });
  }, [decision]);

  const load = useCallback(async () => {
    if (!selectedYear) { setData(null); setLoading(false); return null; }
    const response = await fetch(`${apiUrl}/api/v1/finance/accounting?schoolId=${encodeURIComponent(schoolId)}`, { credentials: "include" });
    if (!response.ok) throw new AccountingUiError(await problem(response, "Fee reconciliation is unavailable."));
    const result = await response.json() as Snapshot;
    setData(result);
    const configurations = result.configurations.filter((item) => item.financialYearId === selectedYear.id);
    const editable = configurations.find((item) => item.status === "DRAFT");
    const approved = configurations.find((item) => item.status === "APPROVED");
    const source = editable || approved;
    const minimum = editable ? selectedYear.startsOn : approved ? nextDate(approved.effectiveFrom) : selectedYear.startsOn;
    setEffectiveFrom(editable?.effectiveFrom || clampDate(localToday(), minimum, selectedYear.endsOn));
    setReceivableAccountId(source?.receivableAccountId || "");
    setConcessionAccountId(source?.concessionAccountId || "");
    setHeadMappings(Object.fromEntries(result.feeHeads.map((head) => [head.id, source?.headMappings.find((item) => item.feeHeadId === head.id)?.incomeAccountId || ""])));
    setTenderMappings(Object.fromEntries(tenders.map((tender) => [tender, source?.tenderMappings.find((item) => item.paymentMethod === tender)?.depositAccountId || ""])));
    return result;
  }, [schoolId, selectedYear]);

  useEffect(() => {
    let cancelled = false;
    Promise.resolve().then(load)
      .catch((reason: unknown) => { if (!cancelled) setError(accountingError(reason,"Fee reconciliation is unavailable.")); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [load]);

  const mutate = useCallback(async (path: string, method: "POST" | "PUT", body: object, success: string, idempotencyKey?: string) => {
    if (refreshRequired) return false;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiUrl}/api/v1/finance/accounting${path}`, { method, credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken(), ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}) }, body: JSON.stringify(body) });
      if (!response.ok) throw new AccountingUiError(await problem(response, "The fee-accounting change could not be saved."));
      setNotice(success);
      try { await Promise.all([load(), onAccountingChanged()]); }
      catch { setRefreshRequired(true); setError("The change was saved, but updated records could not be loaded. Reload verified records before making another fee-accounting change."); }
      return true;
    } catch (reason) { setError(accountingError(reason,"The fee-accounting change could not be saved.")); return false; }
    finally { setBusy(false); }
  }, [load, onAccountingChanged, refreshRequired]);

  function beginDecision(next: MappingDecision) {
    if (busy || decision || refreshRequired) return;
    decisionTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setError(""); setNotice(""); setDecision(next);
  }

  function cancelDecision() {
    if (busy || decisionInFlight.current) return;
    setDecision(null); setError("");
    if (decisionTrigger.current?.isConnected) decisionTrigger.current.focus();
  }

  async function confirmDecision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!decision || busy || refreshRequired || decisionInFlight.current) return;
    if (decision.financialYearId !== selectedYear?.id) { setError("The selected book changed. Cancel this confirmation and select the action again."); return; }
    const reason = String(new FormData(event.currentTarget).get("reason") ?? "").trim();
    if (reason.length < 3 || reason.length > 500) { setError("Enter a reason between 3 and 500 characters."); return; }
    decisionInFlight.current = true;
    try {
      if (await mutate(decision.path, "POST", { ...decision.body, reason }, decision.success)) {
        setDecision(null); requestAnimationFrame(() => noticeTarget.current?.focus());
      } else requestAnimationFrame(() => decisionReason.current?.focus({ preventScroll: true }));
    } finally { decisionInFlight.current = false; }
  }

  if (!selectedYear) return <section className="accounting-panel fee-accounting-empty"><Shield /><h2>{t("Select a financial year")}</h2><p>{t("Fee events are reconciled inside a specific protected accounting book.")}</p></section>;
  if (loading) return <section className="fee-accounting-loading" aria-live="polite"><span /><p>{t("Reconciling fee subledger evidence…")}</p></section>;
  if (!data) return <section className="accounting-panel fee-accounting-empty"><Shield /><h2>{t("Fee reconciliation unavailable")}</h2><p>{accountingPresentation(locale,error)}</p><button type="button" onClick={() => void load()}>{t("Try again")}</button></section>;

  const year = selectedYear;
  const snapshot = data;
  const configurations = snapshot.configurations.filter((item) => item.financialYearId === year.id);
  const draft = configurations.find((item) => item.status === "DRAFT");
  const submitted = configurations.find((item) => item.status === "SUBMITTED");
  const approved = configurations.find((item) => item.status === "APPROVED");
  const reviewConfiguration = submitted || approved;
  const minimumEffective = draft ? year.startsOn : approved ? nextDate(approved.effectiveFrom) : year.startsOn;
  const canCreateRevision = !approved || minimumEffective <= year.endsOn;
  const directAccounts = accounts.filter((item) => item.status === "ACTIVE" && item.allowDirectPosting);
  const receivables = directAccounts.filter((item) => item.accountType === "ASSET" && item.category === "RECEIVABLE");
  const expenses = directAccounts.filter((item) => item.accountType === "EXPENSE");
  const income = directAccounts.filter((item) => item.accountType === "INCOME");
  const bookToday = clampDate(localToday(), year.startsOn, year.endsOn);
  const bookNotStarted = year.startsOn > localToday();
  const batches = snapshot.postingBatches.filter((item) => item.businessDate >= year.startsOn && item.businessDate <= year.endsOn);

  async function saveConfiguration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const body = {
      schoolId,
      financialYearId: year.id,
      supersedesConfigurationId: draft?.supersedesConfigurationId || (!draft ? approved?.id : null),
      revisionNumber: draft?.revisionNumber || (approved ? approved.revisionNumber + 1 : 1),
      effectiveFrom,
      receivableAccountId: year.accountingBasis === "ACCRUAL" ? receivableAccountId : null,
      concessionAccountId: year.accountingBasis === "ACCRUAL" ? concessionAccountId : null,
      headMappings: snapshot.feeHeads.map((head) => ({ feeHeadId: head.id, incomeAccountId: headMappings[head.id] })),
      tenderMappings: tenders.map((paymentMethod) => ({ paymentMethod, depositAccountId: tenderMappings[paymentMethod] })),
      expectedVersion: draft?.rowVersion ?? null,
      reason: form.get("reason")
    };
    const saved = await mutate(draft ? `/configurations/${draft.id}` : "/configurations", draft ? "PUT" : "POST", body, draft ? "Draft mapping updated with a new audit version." : "Draft fee-to-ledger mapping created.");
    if (saved) setChangeReason("");
  }

  function submitConfiguration(configuration: Configuration) {
    beginDecision({ title: `Submit fee mapping revision ${configuration.revisionNumber}?`, titleCopy: { key: "Submit fee mapping revision {revision}?", values: { revision: configuration.revisionNumber } }, financialYearId: configuration.financialYearId, path: `/configurations/${configuration.id}/submit`, body: { schoolId, expectedVersion: configuration.rowVersion }, success: "Mapping submitted for independent approval." });
  }

  function decideConfiguration(configuration: Configuration, action: "APPROVE" | "REJECT") {
    beginDecision({ title: `${label(action)} fee mapping revision ${configuration.revisionNumber}?`, titleCopy: { key: action === "APPROVE" ? "Approve fee mapping revision {revision}?" : "Reject fee mapping revision {revision}?", values: { revision: configuration.revisionNumber } }, financialYearId: configuration.financialYearId, path: `/configurations/${configuration.id}/decision`, body: { schoolId, decision: action, expectedVersion: configuration.rowVersion }, success: action === "APPROVE" ? "Mapping approved and made effective." : "Mapping rejected with review evidence." });
  }

  async function preparePosting(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    if (!postingKey.current) postingKey.current = crypto.randomUUID();
    const saved = await mutate("/postings", "POST", { schoolId, financialYearId: year.id, businessDate: form.get("businessDate"), eventType: form.get("eventType"), maxEntries: Number(form.get("maxEntries")), reason: form.get("reason") }, "Fee evidence prepared as a balanced journal for independent posting.", postingKey.current);
    if (saved) { postingKey.current = ""; formElement.reset(); }
  }

  return <section className="fee-accounting">
    {error && <div className="accounting-banner is-error" role="alert"><span>{accountingPresentation(locale,error)}</span>{refreshRequired ? <button type="button" onClick={() => window.location.reload()}>{t("Reload verified records")}</button> : <button type="button" aria-label={t("Dismiss error")} onClick={() => setError("")}>×</button>}</div>}
    {notice && <div ref={noticeTarget} tabIndex={-1} className="accounting-banner is-success" role="status"><span><Check /> {t(notice)}</span><button type="button" aria-label={t("Dismiss notice")} onClick={() => setNotice("")}>×</button></div>}

    {decision && <form ref={decisionPanel} className="accounting-panel accounting-form accounting-confirmation" onSubmit={confirmDecision} aria-labelledby="fee-mapping-decision-title" aria-describedby="fee-mapping-decision-help" aria-busy={busy} onKeyDown={(event) => { if (event.key === "Escape") { event.preventDefault(); cancelDecision(); } }}>
      <header><h2 id="fee-mapping-decision-title">{decision.titleCopy ? t(decision.titleCopy.key,decision.titleCopy.values) : decision.title}</h2></header>
      <div className="accounting-form-grid"><p id="fee-mapping-decision-help" className="is-wide">{t("Record why this mapping is ready or why you accept or reject it. Existing independent approval, version checks and audit rules still apply; nothing changes until you confirm.")}</p><label className="is-wide">{t("Reason for fee mapping action")}<textarea ref={decisionReason} name="reason" autoFocus required minLength={3} maxLength={500} rows={3} disabled={busy} /></label></div>
      <footer><button type="submit" disabled={busy}>{t(busy ? "Saving…" : "Confirm fee mapping action")}</button><button className="is-secondary" type="button" disabled={busy} onClick={cancelDecision}>{t("Cancel fee mapping action")}</button></footer>
    </form>}

    <section className="fee-accounting-summary" aria-label={t("Fee reconciliation summary")}>
      <article className="is-featured"><span>{t("Subledger waiting")}</span><strong>{money.format(data.reconciliation.unpreparedSourceAmount)}</strong><small>{t(data.reconciliation.unpreparedSourceCount === 1 ? "{count} immutable fee event" : "{count} immutable fee events",{count:data.reconciliation.unpreparedSourceCount})}</small></article>
      <article><span>{t("Awaiting approval")}</span><strong>{data.reconciliation.awaitingApprovalBatches}</strong><small>{t("Balanced journals need another person")}</small></article>
      <article><span>{t("Reconciled")}</span><strong>{data.reconciliation.reconciledBatches}</strong><small>{t("Posted batches tied to source evidence")}</small></article>
      <article><span>{t("Needs re-preparation")}</span><strong>{data.reconciliation.requiresRepreparationBatches}</strong><small>{t("{count} safely re-prepared",{count:data.reconciliation.repreparedBatches})}</small></article>
    </section>

    <section className="fee-accounting-layout">
      <article className="accounting-panel fee-accounting-control">
        <header><div><span>{t("Source-to-ledger control")}</span><h2>{t("Fee accounting mapping")}</h2></div><b className={approved ? "is-ok" : submitted ? "is-submitted" : "is-draft"}>{approved ? t("Revision {revision} active",{revision:approved.revisionNumber}) : t(submitted ? "Awaiting approval" : "Setup required")}</b></header>
        <p className="fee-accounting-intro">{t("Map every fee head and payment method once. Approved revisions remain historical; later changes take effect from a controlled date.")}</p>
        {reviewConfiguration && <FeeMappingEvidence configuration={reviewConfiguration} accounts={accounts} locale={locale} />}
        {submitted && <div className="fee-accounting-decision"><span><b>{t("Revision {revision} is ready for review",{revision:submitted.revisionNumber})}</b><small>{t("Prepared by {name}",{name:submitted.submitterName || submitted.creatorName})}</small></span>{data.canApprove && <div><button type="button" disabled={busy} onClick={() => void decideConfiguration(submitted, "APPROVE")}>{t("Approve mapping")}</button><button className="is-secondary" type="button" disabled={busy} onClick={() => void decideConfiguration(submitted, "REJECT")}>{t("Reject")}</button></div>}</div>}
        {data.canConfigure && !submitted && (draft || canCreateRevision) && <form className="accounting-form fee-accounting-form" onSubmit={saveConfiguration}>
          <fieldset><legend>{draft ? t("Edit draft revision {revision}",{revision:draft.revisionNumber}) : approved ? t("Prepare revision {revision}",{revision:approved.revisionNumber+1}) : t("Create first mapping")}</legend>
            <div className="accounting-form-grid">
              <label>{t("Effective from")}<input type="date" value={effectiveFrom} min={minimumEffective} max={selectedYear.endsOn} onChange={(event) => setEffectiveFrom(event.target.value)} required /></label>
              <label>{t("Book basis")}<input value={displayCode(selectedYear.accountingBasis)} readOnly aria-readonly="true" /></label>
              {selectedYear.accountingBasis === "ACCRUAL" && <><label>{t("Student fee receivable")}<select value={receivableAccountId} onChange={(event) => setReceivableAccountId(event.target.value)} required><option value="">{t("Choose receivable account")}</option>{receivables.map((account) => <option key={account.id} value={account.id}>{account.code} · {account.name}</option>)}</select></label><label>{t("Concession expense")}<select value={concessionAccountId} onChange={(event) => setConcessionAccountId(event.target.value)} required><option value="">{t("Choose expense account")}</option>{expenses.map((account) => <option key={account.id} value={account.id}>{account.code} · {account.name}</option>)}</select></label></>}
            </div>
          </fieldset>
          <fieldset><legend>{t("Fee income accounts")}</legend><div className="fee-accounting-map-grid">{data.feeHeads.map((head) => <label key={head.id}><span>{head.code} · {head.name}<small>{displayCode(head.status)}</small></span><select aria-label={t("{name} income account",{name:head.name})} value={headMappings[head.id] || ""} onChange={(event) => setHeadMappings((current) => ({ ...current, [head.id]: event.target.value }))} required><option value="">{t("Choose income account")}</option>{income.map((account) => <option key={account.id} value={account.id}>{account.code} · {account.name}</option>)}</select></label>)}</div></fieldset>
          <fieldset><legend>{t("Collection deposit accounts")}</legend><p>{t("Use a separate gateway clearing asset (category Other) for online funds awaiting settlement. Existing bank mappings remain available for legacy records.")}</p><div className="fee-accounting-map-grid">{tenders.map((tender) => <label key={tender}><span>{displayCode(tender)}<small>{t(tender === "ONLINE_PROVIDER" ? "Gateway clearing before settlement" : "Payment destination")}</small></span><select aria-label={t("{method} deposit account",{method:displayCode(tender)})} value={tenderMappings[tender] || ""} onChange={(event) => setTenderMappings((current) => ({ ...current, [tender]: event.target.value }))} required><option value="">{t(tender === "CASH" ? "Choose cash account" : tender === "ONLINE_PROVIDER" ? "Choose clearing or legacy bank account" : "Choose bank account")}</option>{feeTenderAccounts(directAccounts, tender).map((account) => <option key={account.id} value={account.id}>{account.code} · {account.name}</option>)}</select></label>)}</div></fieldset>
          <div className="fee-accounting-save"><label>{t("Change reason")}<input name="reason" value={changeReason} onChange={(event) => setChangeReason(event.target.value)} minLength={3} maxLength={500} placeholder={t("Why this mapping is correct")} required /></label><button type="submit" disabled={busy}>{t(draft ? "Save draft" : "Prepare mapping")}</button>{draft && <button className="is-secondary" type="button" disabled={busy} onClick={() => void submitConfiguration(draft)}>{t("Submit for approval")}</button>}</div>
        </form>}
        {data.canConfigure && !submitted && !draft && !canCreateRevision && <div className="fee-accounting-guidance"><Shield /><b>{t("This book has no later effective date")}</b><p>{t("The active mapping already starts on the final day of this financial year. Create the next financial year instead of forcing an invalid revision.")}</p></div>}
      </article>

      <aside className="fee-accounting-side">
        <article className="accounting-panel fee-accounting-prepare"><header><div><span>{t("Controlled automation")}</span><h2>{t("Prepare fee journal")}</h2></div><Shield /></header>{approved ? <form className="accounting-form" onSubmit={preparePosting}><label>{t("Fee event")}<select name="eventType" defaultValue="PAYMENT">{events.map((event) => <option key={event} value={event}>{t("{event} · {count} waiting",{event:displayCode(event),count:data.unpreparedEvents.find((item) => item.eventType === event)?.sourceCount || 0})}</option>)}</select></label><label>{t("Business date")}<input name="businessDate" type="date" defaultValue={bookToday} min={selectedYear.startsOn} max={clampDate(localToday(), selectedYear.startsOn, selectedYear.endsOn)} required /></label><label>{t("Maximum events")}<input name="maxEntries" type="number" defaultValue="100" min="1" max="200" required /></label><label>{t("Preparation reason")}<textarea name="reason" minLength={3} maxLength={500} defaultValue="Prepare verified fee subledger events for independent ledger posting." required /></label><button type="submit" disabled={busy || !data.canPrepare || bookNotStarted}>{t("Prepare balanced journal")}</button>{bookNotStarted && <small>{t("The financial year has not started yet.")}</small>}</form> : <div className="fee-accounting-guidance"><Shield /><b>{t("Approval comes first")}</b><p>{t("An independently approved complete mapping is required before fee events can enter the general ledger.")}</p></div>}</article>
        <article className="accounting-panel fee-accounting-events"><header><div><span>{t("Unprepared evidence")}</span><h2>{t("Source queue")}</h2></div><strong>{data.reconciliation.unpreparedSourceCount}</strong></header>{data.unpreparedEvents.map((event) => <div key={event.eventType}><span><b>{displayCode(event.eventType)}</b><small>{t(event.sourceCount === 1 ? "{count} event" : "{count} events",{count:event.sourceCount})}</small></span><strong>{money.format(event.sourceAmount)}</strong></div>)}</article>
      </aside>
    </section>

    <article className="accounting-panel fee-accounting-history"><header><div><span>{t("Append-only evidence")}</span><h2>{t("Recent reconciliation batches")}</h2></div><small>{t("{count} in this financial year",{count:batches.length})}</small></header>{batches.length > 0 && <p id={`${regionId}-scroll-help`} className="accounting-scroll-help">{t("Scroll horizontally to inspect all columns. With this region focused, use the left and right arrow keys.")}</p>}{batches.length === 0 ? <p className="accounting-empty">{t("No fee event has been prepared for this financial year yet.")}</p> : <div className="fee-accounting-table" role="region" tabIndex={0} aria-label={t("Recent reconciliation batch records")} aria-describedby={`${regionId}-scroll-help`}><div className="fee-accounting-table-row is-head"><span>{t("Date & batch")}</span><span>{t("Source")}</span><span>{t("Amount")}</span><span>{t("Journal")}</span><span>{t("Reconciliation")}</span></div>{batches.map((batch) => <div className="fee-accounting-table-row" key={batch.id}><span><b>{batch.businessDate}</b><small>{t("Sequence {sequence} · {name}",{sequence:batch.sequenceNumber,name:batch.preparerName})}</small></span><span><b>{displayCode(batch.eventType)}</b><small>{t("{active}/{total} active evidence",{active:batch.activeSourceCount,total:batch.sourceCount})}</small></span><strong>{money.format(batch.sourceAmount)}</strong><span><b>{batch.disposition === "NO_GL_EFFECT" ? t("No GL effect") : displayCode(batch.journalStatus)}</b><small>{new Date(batch.preparedAt).toLocaleString(locale === "hi" ? "hi-IN" : "en-IN", { dateStyle: "medium", timeStyle: "short" })}</small></span><em className={`is-${batch.reconciliationStatus.toLowerCase().replaceAll("_", "-")}`}>{displayCode(batch.reconciliationStatus)}</em></div>)}</div>}</article>
  </section>;
}
