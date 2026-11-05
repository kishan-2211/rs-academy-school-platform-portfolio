"use client";

import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { ArrowUpRight, Check, Shield } from "@/components/icons";
import { FeeAccountingPanel } from "@/components/workspace/fee-accounting-panel";
import { SettlementPreparationProvenance } from "@/components/workspace/settlement-preparation-provenance";
import { MoneyIcon, PlusIcon, ReportIcon } from "@/components/workspace/workspace-icons";
import { useLanguage } from "@/components/i18n/language-provider";
import { accountingCode, accountingError, accountingPresentation, accountingText, AccountingUiError, type AccountingCopy, type AccountingPresentation } from "@/lib/i18n/accounting-messages";

type Period = { id: string; periodNumber: number; code: string; name: string; startsOn: string; endsOn: string; status: string; rowVersion: number };
type FinancialYear = { id: string; code: string; name: string; startsOn: string; endsOn: string; accountingBasis: string; currencyCode: string; status: string; creatorName: string; openerName?: string; openedAt?: string; closerName?: string; closedAt?: string; rowVersion: number; periods: Period[] };
type Account = { id: string; parentAccountId?: string; code: string; name: string; description?: string; accountType: string; normalBalance: string; category: string; allowDirectPosting: boolean; status: string; rowVersion: number };
type Vendor = { id: string; code: string; name: string; contactName?: string; phone?: string; email?: string; address?: string; status: string; rowVersion: number };
type JournalLine = { id: string; lineNumber: number; accountId: string; accountCode: string; accountName: string; accountType: string; vendorId?: string; learnerFeeAccountId?: string; debitAmount: number; creditAmount: number; memo?: string };
type JournalHistory = { fromStatus?: string; toStatus: string; reason: string; actorName: string; occurredAt: string };
type Journal = { id: string; financialYearId: string; accountingPeriodId: string; voucherType: string; voucherNumber?: string; postingDate: string; reference?: string; narration: string; sourceModule: string; sourceId?: string; reversalOfJournalId?: string; status: string; lineCount?: number; totalAmount?: number; creatorName: string; submitterName?: string; submittedAt?: string; deciderName?: string; decidedAt?: string; rowVersion: number; lines: JournalLine[]; history: JournalHistory[] };
type Reversal = { id: string; accountingJournalId: string; requestedAccountingPeriodId: string; requestedPostingDate: string; requestReason: string; status: string; requesterName: string; deciderName?: string; decisionReason?: string; reversalJournalId?: string; createdAt: string; rowVersion: number };
type TrialRow = { accountId: string; accountCode: string; accountName: string; accountType: string; category: string; debitMovements: number; creditMovements: number; debitBalance: number; creditBalance: number };
type LedgerRow = { journalId: string; voucherNumber: string; voucherType: string; postingDate: string; accountId: string; accountCode: string; accountName: string; vendorId?: string; vendorName?: string; learnerFeeAccountId?: string; debitAmount: number; creditAmount: number; narration: string; memo?: string };
type Statements = { trialDebit: number; trialCredit: number; assets: number; liabilities: number; fundBalance: number; income: number; expenses: number; periodSurplus: number; cash: number; bank: number; receivables: number; payables: number };
type Snapshot = { schoolId: string; schoolName: string; canConfigure: boolean; canPrepare: boolean; canPost: boolean; canClose: boolean; canRequestReversal: boolean; canApproveReversal: boolean; selectedFinancialYearId?: string; from: string; to: string; financialYears: FinancialYear[]; accounts: Account[]; vendors: Vendor[]; journals: Journal[]; reversalRequests: Reversal[]; trialBalance: TrialRow[]; generalLedger: LedgerRow[]; statements: Statements };
type DraftLine = { accountId: string; vendorId: string; debitAmount: string; creditAmount: string; memo: string };
type Tab = "overview" | "vouchers" | "fees" | "ledger" | "setup";
type BookFilters = { yearId: string; from: string; to: string };
type AccountingDecision = { title: string; titleCopy?: AccountingCopy; path: string; body: Record<string, unknown>; success: string; reversalPeriods?: Period[] };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const emptyLine = (): DraftLine => ({ accountId: "", vendorId: "", debitAmount: "", creditAmount: "", memo: "" });
const localToday = () => { const value = new Date(); value.setMinutes(value.getMinutes() - value.getTimezoneOffset()); return value.toISOString().slice(0, 10); };
const label = (value?: string) => value ? value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase()) : "Not set";
const cookie = (name: string) => document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new AccountingUiError("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string): Promise<AccountingPresentation> { try { const detail = ((await response.json()) as { detail?: unknown }).detail; return typeof detail === "string" && detail ? { literal: detail } : fallback; } catch { return fallback; } }

export function AccountingWorkspace() {
  const { locale } = useLanguage(), regionId = useId();
  const t = (source: string, values?: Record<string,string|number>) => accountingText(locale,source,values);
  const displayCode = (value?: string) => accountingCode(locale,value);
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<Tab>("overview");
  const [yearId, setYearId] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([emptyLine(), emptyLine()]);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<AccountingPresentation>("");
  const [notice, setNotice] = useState("");
  const [decision, setDecision] = useState<AccountingDecision | null>(null);
  const [refreshRequired, setRefreshRequired] = useState(false);
  const decisionTrigger = useRef<HTMLElement | null>(null);
  const decisionInFlight = useRef(false);
  const decisionPanel = useRef<HTMLFormElement | null>(null);
  const decisionReason = useRef<HTMLTextAreaElement | null>(null);
  const noticeTarget = useRef<HTMLDivElement | null>(null);
  const journalKey = useRef("");

  useEffect(() => {
    if (decision) decisionPanel.current?.scrollIntoView({ block: "start", behavior: "instant" });
  }, [decision]);

  const load = useCallback(async (overrides?: BookFilters) => {
    const filters = overrides ?? { yearId, from, to };
    const query = new URLSearchParams();
    if (filters.yearId) query.set("financialYearId", filters.yearId);
    if (filters.from) query.set("from", filters.from);
    if (filters.to) query.set("to", filters.to);
    const response = await fetch(`${apiUrl}/api/v1/accounting?${query}`, { credentials: "include" });
    if (!response.ok) throw new AccountingUiError(await problem(response, "Accounting books are unavailable."));
    const result = await response.json() as Snapshot;
    setData(result);
    setYearId((current) => overrides?.yearId || current || result.selectedFinancialYearId || "");
    setFrom((current) => overrides?.from || current || result.from);
    setTo((current) => overrides?.to || current || result.to);
    return result;
  }, [from, to, yearId]);

  useEffect(() => {
    let cancelled = false;
    Promise.resolve().then(() => load())
      .catch((reason: unknown) => { if (!cancelled) setError(accountingError(reason,"Accounting books are unavailable.")); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [load]);

  const mutate = useCallback(async (path: string, method: "POST" | "PUT", body: object, success: string, idempotencyKey?: string, createdYearRange?: Pick<BookFilters, "from" | "to">) => {
    if (refreshRequired) return null;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiUrl}/api/v1/accounting${path}`, { method, credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken(), ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}) }, body: JSON.stringify(body) });
      if (!response.ok) throw new AccountingUiError(await problem(response, "The accounting change could not be saved."));
      const result = await response.json() as { id: string; rowVersion: number; voucherNumber?: string };
      setNotice(success);
      try { await load(createdYearRange ? { yearId: result.id, ...createdYearRange } : undefined); }
      catch { setRefreshRequired(true); setError("The change was saved, but updated records could not be loaded. Reload verified records before making another change."); }
      return result;
    } catch (reason) { setError(accountingError(reason,"The accounting change could not be saved.")); return null; }
    finally { setBusy(false); }
  }, [load, refreshRequired]);

  const selectedYear = data?.financialYears.find((item) => item.id === (yearId || data.selectedFinancialYearId));
  const postingPeriods = selectedYear?.periods.filter((item) => item.status === "OPEN") ?? [];
  const postingAccounts = data?.accounts.filter((item) => item.status === "ACTIVE" && item.allowDirectPosting) ?? [];
  const pendingJournals = data?.journals.filter((item) => item.status === "SUBMITTED") ?? [];
  const pendingReversals = data?.reversalRequests.filter((item) => item.status === "PENDING") ?? [];
  const currency = selectedYear?.currencyCode || "INR";
  const money = new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", { style: "currency", currency, maximumFractionDigits: 2 });

  async function createYear(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    const result = await mutate("/financial-years", "POST", { schoolId: data.schoolId, code: form.get("code"), name: form.get("name"), startsOn: form.get("startsOn"), endsOn: form.get("endsOn"), accountingBasis: form.get("accountingBasis"), currencyCode: form.get("currencyCode"), reason: form.get("reason") }, "Draft financial year created with monthly periods and voucher sequences.", undefined, { from: String(form.get("startsOn")), to: String(form.get("endsOn")) });
    if (result) formElement.reset();
  }

  async function createAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    if (await mutate("/accounts", "POST", { schoolId: data.schoolId, parentAccountId: form.get("parentAccountId") || null, code: form.get("code"), name: form.get("name"), description: form.get("description") || null, accountType: form.get("accountType"), category: form.get("category"), allowDirectPosting: form.get("allowDirectPosting") === "on", status: "ACTIVE", expectedVersion: null, reason: form.get("reason") || null }, "Chart-of-accounts node created.")) formElement.reset();
  }

  async function createVendor(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    if (await mutate("/vendors", "POST", { schoolId: data.schoolId, code: form.get("code"), name: form.get("name"), contactName: form.get("contactName") || null, phone: form.get("phone") || null, email: form.get("email") || null, address: form.get("address") || null, status: "ACTIVE", expectedVersion: null, reason: form.get("reason") || null }, "Vendor ledger party created.")) formElement.reset();
  }

  async function createJournal(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !selectedYear) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    if (!journalKey.current) journalKey.current = crypto.randomUUID();
    const result = await mutate("/journals", "POST", { schoolId: data.schoolId, financialYearId: selectedYear.id, accountingPeriodId: form.get("accountingPeriodId"), voucherType: form.get("voucherType"), postingDate: form.get("postingDate"), reference: form.get("reference") || null, narration: form.get("narration"), lines: lines.map((line) => ({ accountId: line.accountId, vendorId: line.vendorId || null, learnerFeeAccountId: null, debitAmount: Number(line.debitAmount || 0), creditAmount: Number(line.creditAmount || 0), memo: line.memo || null })) }, "Balanced draft voucher created.", journalKey.current);
    if (result) { journalKey.current = ""; setLines([emptyLine(), emptyLine()]); formElement.reset(); }
  }

  function beginDecision(next: AccountingDecision) {
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
    event.preventDefault(); if (!decision || busy || refreshRequired || decisionInFlight.current) return;
    const form = new FormData(event.currentTarget);
    const reason = String(form.get("reason") ?? "").trim();
    if (reason.length < 3 || reason.length > 500) { setError("Enter a reason between 3 and 500 characters."); return; }
    const body = { ...decision.body, reason };
    if (decision.reversalPeriods) {
      const date = String(form.get("postingDate") ?? "");
      const period = /^\d{4}-\d{2}-\d{2}$/.test(date) && decision.reversalPeriods.find((item) => item.status === "OPEN" && date >= item.startsOn && date <= item.endsOn);
      if (!period) { setError("Choose a date inside one of the open periods."); return; }
      Object.assign(body, { requestedAccountingPeriodId: period.id, requestedPostingDate: date });
    }
    decisionInFlight.current = true;
    try {
      if (await mutate(decision.path, "POST", body, decision.success)) {
        setDecision(null); requestAnimationFrame(() => noticeTarget.current?.focus());
      } else requestAnimationFrame(() => decisionReason.current?.focus({ preventScroll: true }));
    } finally { decisionInFlight.current = false; }
  }

  function transitionYear(year: FinancialYear, action: "open" | "close") {
    if (!data) return;
    beginDecision({ title: `${label(action)} ${year.name}?`, titleCopy: { key: action === "open" ? "Open {name}?" : "Close {name}?", values: { name: year.name } }, path: `/financial-years/${year.id}/${action}`, body: { schoolId: data.schoolId, expectedVersion: year.rowVersion }, success: `Financial year ${action === "open" ? "opened" : "closed"} with protected evidence.` });
  }

  function transitionPeriod(period: Period, transition: "LOCK" | "CLOSE") {
    if (!data) return;
    beginDecision({ title: `${label(transition)} ${period.name}?`, titleCopy: { key: transition === "LOCK" ? "Lock {name}?" : "Close {name}?", values: { name: period.name } }, path: `/periods/${period.id}/transition`, body: { schoolId: data.schoolId, transition, expectedVersion: period.rowVersion }, success: `Accounting period ${label(transition)} recorded.` });
  }

  function submitJournal(journal: Journal) {
    if (!data) return;
    beginDecision({ title: `Submit ${journal.narration}?`, titleCopy: { key: "Submit {name}?", values: { name: journal.narration } }, path: `/journals/${journal.id}/submit`, body: { schoolId: data.schoolId, expectedVersion: journal.rowVersion }, success: "Voucher submitted for independent review." });
  }

  function decideJournal(journal: Journal, action: "POST" | "REJECT") {
    if (!data) return;
    beginDecision({ title: `${label(action)} ${journal.narration}?`, titleCopy: { key: action === "POST" ? "Post {name}?" : "Reject {name}?", values: { name: journal.narration } }, path: `/journals/${journal.id}/decision`, body: { schoolId: data.schoolId, decision: action, expectedVersion: journal.rowVersion }, success: action === "POST" ? "Voucher posted to the ledger." : "Voucher rejected with review evidence." });
  }

  function requestReversal(journal: Journal) {
    if (!data || !postingPeriods.length) return;
    beginDecision({ title: `Request reversal of ${journal.voucherNumber || journal.narration}?`, titleCopy: { key: "Request reversal of {name}?", values: { name: journal.voucherNumber || journal.narration } }, path: `/journals/${journal.id}/reversal-requests`, body: { schoolId: data.schoolId }, reversalPeriods: [...postingPeriods], success: "Reversal sent for independent approval." });
  }

  function decideReversal(request: Reversal, action: "APPROVE" | "REJECT") {
    if (!data) return;
    beginDecision({ title: `${label(action)} reversal requested by ${request.requesterName}?`, titleCopy: { key: action === "APPROVE" ? "Approve reversal requested by {name}?" : "Reject reversal requested by {name}?", values: { name: request.requesterName } }, path: `/reversal-requests/${request.id}/decision`, body: { schoolId: data.schoolId, decision: action, expectedVersion: request.rowVersion }, success: action === "APPROVE" ? "Counter-voucher posted; the source remains visible as reversed." : "Reversal request rejected with evidence." });
  }

  function updateLine(index: number, field: keyof DraftLine, value: string) {
    setLines((current) => current.map((line, lineIndex) => lineIndex === index ? { ...line, [field]: value } : line));
  }

  if (loading) return <section className="accounting-state" aria-live="polite"><span /><h1>{t("Opening protected accounting books…")}</h1></section>;
  if (!data) return <section className="accounting-state" aria-live="assertive"><MoneyIcon /><h1>{t("Accounting is unavailable")}</h1><p>{accountingPresentation(locale,error)}</p><button type="button" onClick={() => window.location.reload()}>{t("Try again")}</button></section>;
  const yearStart = new Date().getMonth() >= 3 ? new Date().getFullYear() : new Date().getFullYear() - 1;

  return <section className="accounting-page">
    <header className="accounting-hero">
      <div><span><Shield /> {t("Double-entry accounting")}</span><h1>{t("Books that explain")} <em>{t("every balance.")}</em></h1><p>{t("Prepare balanced vouchers, keep approval independent, and correct posted history only with a matching counter-voucher.")}</p></div>
      <aside><small>{t("Selected book")}</small><strong>{selectedYear?.name || t("Not configured")}</strong><span>{selectedYear ? `${displayCode(selectedYear.accountingBasis)} · ${selectedYear.currencyCode}` : t("Create a draft financial year to begin")}</span></aside>
    </header>

    <div className="accounting-trust"><span><i /> {t("Balanced in PostgreSQL")}</span><span><i /> {t("Maker-checker posting")}</span><span><i /> {t("Append-only history")}</span><span><i /> {t("School-scoped access")}</span></div>
    {error && <div className="accounting-banner is-error" role="alert"><span>{accountingPresentation(locale,error)}</span>{refreshRequired ? <button type="button" onClick={() => window.location.reload()}>{t("Reload verified records")}</button> : <button type="button" aria-label={t("Dismiss error")} onClick={() => setError("")}>×</button>}</div>}
    {notice && <div ref={noticeTarget} tabIndex={-1} className="accounting-banner is-success" role="status"><span><Check /> {t(notice)}</span><button type="button" aria-label={t("Dismiss notice")} onClick={() => setNotice("")}>×</button></div>}

    {decision && <form ref={decisionPanel} className="accounting-panel accounting-form accounting-confirmation" onSubmit={confirmDecision} aria-labelledby="accounting-decision-title" aria-describedby="accounting-decision-help" aria-busy={busy} onKeyDown={(event) => { if (event.key === "Escape") { event.preventDefault(); cancelDecision(); } }}>
      <header><h2 id="accounting-decision-title">{decision.titleCopy ? t(decision.titleCopy.key,decision.titleCopy.values) : decision.title}</h2></header>
      <div className="accounting-form-grid">
        <p id="accounting-decision-help" className="is-wide">{t("Record why this action is needed. Existing independent approval, version checks and audit rules still apply; nothing changes until you confirm.")}</p>
        <label className="is-wide">{t("Reason for accounting action")}<textarea ref={decisionReason} name="reason" autoFocus required minLength={3} maxLength={500} rows={3} disabled={busy} /></label>
        {decision.reversalPeriods && <label className="is-wide">{t("Counter-voucher posting date")}<input name="postingDate" type="date" defaultValue={localToday()} required disabled={busy} min={decision.reversalPeriods[0]?.startsOn} max={decision.reversalPeriods.at(-1)?.endsOn} /><small>{t("Choose a date in an open period. The original posted voucher is retained.")}</small></label>}
      </div>
      <footer><button type="submit" disabled={busy}>{t(busy ? "Saving…" : "Confirm accounting action")}</button><button className="is-secondary" type="button" disabled={busy} onClick={cancelDecision}>{t("Cancel accounting action")}</button></footer>
    </form>}

    <section className="accounting-filters" aria-label={t("Accounting report filters")}>
      <label>{t("Financial year")}<select value={yearId} onChange={(event) => { const year = data.financialYears.find((item) => item.id === event.target.value); setYearId(event.target.value); if (year) { setFrom(year.startsOn); setTo(year.endsOn); } }}><option value="">{t("No financial year")}</option>{data.financialYears.map((year) => <option key={year.id} value={year.id}>{year.name} · {displayCode(year.status)}</option>)}</select></label>
      <label>{t("From")}<input type="date" value={from} min={selectedYear?.startsOn} max={to || selectedYear?.endsOn} onChange={(event) => setFrom(event.target.value)} /></label>
      <label>{t("To")}<input type="date" value={to} min={from || selectedYear?.startsOn} max={selectedYear?.endsOn} onChange={(event) => setTo(event.target.value)} /></label>
      <span>{t(data.journals.length === 1 ? "{count} voucher in view" : "{count} vouchers in view",{count:data.journals.length})}</span>
    </section>

    <nav className="accounting-tabs" aria-label={t("Accounting sections")}>
      {(["overview", "vouchers", "fees", "ledger", "setup"] as Tab[]).map((item) => <button type="button" key={item} className={tab === item ? "is-active" : ""} aria-pressed={tab === item} onClick={() => setTab(item)}>{item === "fees" ? t("Fee reconciliation") : displayCode(item)}{item === "vouchers" && pendingJournals.length > 0 && <b>{pendingJournals.length}</b>}</button>)}
    </nav>

    {tab === "overview" && <>
      <section className="accounting-metrics">
        <article><span>{t("Cash position")}</span><strong>{money.format(data.statements.cash)}</strong><small>{t("Posted ledger as of {date}",{date:data.to})}</small></article>
        <article><span>{t("Bank position")}</span><strong>{money.format(data.statements.bank)}</strong><small>{t("Reconciliation-ready book balance")}</small></article>
        <article><span>{t("Receivables")}</span><strong>{money.format(data.statements.receivables)}</strong><small>{t("Ledger accounts classified as receivable")}</small></article>
        <article><span>{t("Payables")}</span><strong>{money.format(data.statements.payables)}</strong><small>{t("Ledger accounts classified as payable")}</small></article>
        <article><span>{t("Income in range")}</span><strong>{money.format(data.statements.income)}</strong><small>{t("{from} to {to}",{from:data.from,to:data.to})}</small></article>
        <article><span>{t(data.statements.periodSurplus >= 0 ? "Surplus in range" : "Deficit in range")}</span><strong>{money.format(Math.abs(data.statements.periodSurplus))}</strong><small>{t("Income less expenses from posted lines")}</small></article>
      </section>
      <section className="accounting-grid">
        <article className="accounting-panel"><header><div><span>{t("Control equation")}</span><h2>{t("Trial balance")}</h2></div><b className={data.statements.trialDebit === data.statements.trialCredit ? "is-ok" : "is-alert"}>{t(data.statements.trialDebit === data.statements.trialCredit ? "Balanced" : "Review")}</b></header><div className="accounting-equation"><strong>{money.format(data.statements.trialDebit)}</strong><span>{t("total debit")}</span><i>=</i><strong>{money.format(data.statements.trialCredit)}</strong><span>{t("total credit")}</span></div></article>
        <article className="accounting-panel"><header><div><span>{t("Statement position")}</span><h2>{t("Balance sheet view")}</h2></div><ReportIcon /></header><dl className="accounting-statement"><div><dt>{t("Assets")}</dt><dd>{money.format(data.statements.assets)}</dd></div><div><dt>{t("Liabilities")}</dt><dd>{money.format(data.statements.liabilities)}</dd></div><div><dt>{t("Fund balance")}</dt><dd>{money.format(data.statements.fundBalance)}</dd></div></dl></article>
      </section>
      {(pendingJournals.length > 0 || pendingReversals.length > 0) && <section className="accounting-panel accounting-attention"><header><div><span>{t("Needs attention")}</span><h2>{t("Independent decisions")}</h2></div><strong>{pendingJournals.length + pendingReversals.length}</strong></header><div>{pendingJournals.map((journal) => <article key={journal.id}><div><b>{journal.narration}</b><small>{journal.creatorName} · {journal.postingDate} · {money.format(journal.totalAmount || 0)}</small>{journal.sourceModule === "GATEWAY_SETTLEMENT" && journal.sourceId && <SettlementPreparationProvenance key={journal.id+":"+journal.rowVersion} schoolId={data.schoolId} preparationId={journal.sourceId} journalId={journal.id} disabled={busy} />}</div>{data.canPost && <span><button type="button" disabled={busy} onClick={() => void decideJournal(journal, "POST")}>{t("Post")}</button><button className="is-secondary" type="button" disabled={busy} onClick={() => void decideJournal(journal, "REJECT")}>{t("Reject")}</button></span>}</article>)}{pendingReversals.map((request) => <article key={request.id}><div><b>{t("Reversal request")}</b><small>{t("{name} · counter-date {date}",{name:request.requesterName,date:request.requestedPostingDate})}</small><p>{request.requestReason}</p></div>{data.canApproveReversal && <span><button type="button" disabled={busy} onClick={() => void decideReversal(request, "APPROVE")}>{t("Approve")}</button><button className="is-secondary" type="button" disabled={busy} onClick={() => void decideReversal(request, "REJECT")}>{t("Reject")}</button></span>}</article>)}</div></section>}
    </>}

    {tab === "vouchers" && <section className="accounting-voucher-layout">
      {data.canPrepare && <form className="accounting-panel accounting-form" onSubmit={createJournal}><header><div><span>{t("Balanced draft")}</span><h2>{t("Prepare voucher")}</h2></div><PlusIcon /></header><div className="accounting-form-grid"><label>{t("Voucher type")}<select name="voucherType" required><option value="RECEIPT">{displayCode("RECEIPT")}</option><option value="PAYMENT">{displayCode("PAYMENT")}</option><option value="JOURNAL">{displayCode("JOURNAL")}</option><option value="CONTRA">{displayCode("CONTRA")}</option><option value="EXPENSE">{displayCode("EXPENSE")}</option><option value="OPENING">{displayCode("OPENING")}</option></select></label><label>{t("Posting period")}<select name="accountingPeriodId" required><option value="">{t("Choose open period")}</option>{postingPeriods.map((period) => <option key={period.id} value={period.id}>{period.name}</option>)}</select></label><label>{t("Posting date")}<input name="postingDate" type="date" defaultValue={localToday()} min={selectedYear?.startsOn} max={selectedYear?.endsOn} required /></label><label>{t("Reference")}<input name="reference" maxLength={120} /></label><label className="is-wide">{t("Narration")}<textarea name="narration" minLength={3} maxLength={500} required /></label></div><div className="accounting-lines"><div className="accounting-line is-head"><span>{t("Account")}</span><span>{t("Vendor")}</span><span>{t("Debit")}</span><span>{t("Credit")}</span><span>{t("Memo")}</span><span /></div>{lines.map((line, index) => <div className="accounting-line" key={index}><select aria-label={t("Line {number} account",{number:index+1})} value={line.accountId} onChange={(event) => updateLine(index, "accountId", event.target.value)} required><option value="">{t("Choose account")}</option>{postingAccounts.map((account) => <option key={account.id} value={account.id}>{account.code} · {account.name}</option>)}</select><select aria-label={t("Line {number} vendor",{number:index+1})} value={line.vendorId} onChange={(event) => updateLine(index, "vendorId", event.target.value)}><option value="">{t("No vendor")}</option>{data.vendors.filter((vendor) => vendor.status === "ACTIVE").map((vendor) => <option key={vendor.id} value={vendor.id}>{vendor.name}</option>)}</select><input aria-label={t("Line {number} debit",{number:index+1})} type="number" min="0" step="0.01" value={line.debitAmount} onChange={(event) => updateLine(index, "debitAmount", event.target.value)} /><input aria-label={t("Line {number} credit",{number:index+1})} type="number" min="0" step="0.01" value={line.creditAmount} onChange={(event) => updateLine(index, "creditAmount", event.target.value)} /><input aria-label={t("Line {number} memo",{number:index+1})} maxLength={240} value={line.memo} onChange={(event) => updateLine(index, "memo", event.target.value)} /><button type="button" aria-label={t("Remove line {number}",{number:index+1})} disabled={lines.length <= 2} onClick={() => setLines((current) => current.filter((_, lineIndex) => lineIndex !== index))}>×</button></div>)}</div><footer><button className="is-secondary" type="button" onClick={() => setLines((current) => [...current, emptyLine()])}>{t("Add line")}</button><button type="submit" disabled={busy || !selectedYear || selectedYear.status !== "OPEN"}>{t("Create balanced draft")} <ArrowUpRight /></button></footer></form>}
      <section className="accounting-panel accounting-daybook"><header><div><span>{t("Day book")}</span><h2>{t("Voucher evidence")}</h2></div><strong>{data.journals.length}</strong></header>{data.journals.length ? <div>{data.journals.map((journal) => <article key={journal.id}><div className="accounting-daybook-title"><span><b>{journal.voucherNumber || t("Draft")}</b><em className={`is-${journal.status.toLowerCase()}`}>{displayCode(journal.status)}</em></span><h3>{journal.narration}</h3><small>{displayCode(journal.voucherType)} · {journal.postingDate} · {journal.creatorName}</small></div><strong>{money.format(journal.totalAmount || journal.lines.reduce((sum, line) => sum + line.debitAmount, 0))}</strong><div className="accounting-journal-lines">{journal.lines.map((line) => <span key={line.id}><b>{line.accountCode} · {line.accountName}</b><small>{line.debitAmount > 0 ? t("Debit {amount}",{amount:money.format(line.debitAmount)}) : t("Credit {amount}",{amount:money.format(line.creditAmount)})}</small></span>)}</div>{journal.sourceModule === "GATEWAY_SETTLEMENT" && journal.sourceId && <SettlementPreparationProvenance key={journal.id+":"+journal.rowVersion} schoolId={data.schoolId} preparationId={journal.sourceId} journalId={journal.id} disabled={busy} />}<footer>{journal.status === "DRAFT" && data.canPrepare && <button type="button" disabled={busy} onClick={() => void submitJournal(journal)}>{t("Submit")}</button>}{journal.status === "SUBMITTED" && data.canPost && <><button type="button" disabled={busy} onClick={() => void decideJournal(journal, "POST")}>{t("Post")}</button><button className="is-secondary" type="button" disabled={busy} onClick={() => void decideJournal(journal, "REJECT")}>{t("Reject")}</button></>}{journal.status === "POSTED" && !journal.reversalOfJournalId && data.canRequestReversal && <button className="is-secondary" type="button" disabled={busy || !postingPeriods.length} onClick={() => void requestReversal(journal)}>{t("Request reversal")}</button>}</footer></article>)}</div> : <p className="accounting-empty">{t("No vouchers match this date range.")}</p>}</section>
    </section>}

    {tab === "ledger" && <section className="accounting-ledgers"><p id={`${regionId}-scroll-help`} className="accounting-scroll-help">{t("Scroll horizontally to inspect all columns. With this region focused, use the left and right arrow keys.")}</p>
      <article className="accounting-panel"><header><div><span>{t("As of {date}",{date:data.to})}</span><h2>{t("Trial balance")}</h2></div><b className="is-ok">{money.format(data.statements.trialDebit)} = {money.format(data.statements.trialCredit)}</b></header><div className="accounting-table" role="region" tabIndex={0} aria-label={t("Trial balance records")} aria-describedby={`${regionId}-scroll-help`}><div className="accounting-table-row is-head"><span>{t("Account")}</span><span>{t("Type")}</span><span>{t("Debit")}</span><span>{t("Credit")}</span></div>{data.trialBalance.map((row) => <div className="accounting-table-row" key={row.accountId}><span><b>{row.accountCode}</b><small>{row.accountName}</small></span><span>{displayCode(row.accountType)}</span><strong>{money.format(row.debitBalance)}</strong><strong>{money.format(row.creditBalance)}</strong></div>)}</div></article>
      <article className="accounting-panel"><header><div><span>{t("{from} to {to}",{from:data.from,to:data.to})}</span><h2>{t("General ledger")}</h2></div><ReportIcon /></header><div className="accounting-table accounting-table--ledger" role="region" tabIndex={0} aria-label={t("General ledger records")} aria-describedby={`${regionId}-scroll-help`}><div className="accounting-table-row is-head"><span>{t("Date / voucher")}</span><span>{t("Account")}</span><span>{t("Debit")}</span><span>{t("Credit")}</span><span>{t("Narration")}</span></div>{data.generalLedger.map((row, index) => <div className="accounting-table-row" key={`${row.journalId}-${row.accountId}-${index}`}><span><b>{row.postingDate}</b><small>{row.voucherNumber}</small></span><span><b>{row.accountCode}</b><small>{row.accountName}{row.vendorName ? ` · ${row.vendorName}` : ""}</small></span><strong>{money.format(row.debitAmount)}</strong><strong>{money.format(row.creditAmount)}</strong><span>{row.narration}<small>{row.memo}</small></span></div>)}</div></article>
    </section>}

    {tab === "fees" && <FeeAccountingPanel schoolId={data.schoolId} selectedYear={selectedYear} accounts={data.accounts} money={money} onAccountingChanged={load} />}

    {tab === "setup" && <section className="accounting-setup accounting-form">
      <article className="accounting-panel"><header><div><span>{t("Book control")}</span><h2>{t("Financial years & periods")}</h2></div><Shield /></header>{data.financialYears.map((year) => <section className="accounting-year" key={year.id}><div><span><b>{year.name}</b><small>{year.code} · {t("{from} to {to}",{from:year.startsOn,to:year.endsOn})}</small></span><em className={`is-${year.status.toLowerCase()}`}>{displayCode(year.status)}</em>{data.canClose && year.status === "DRAFT" && <button type="button" disabled={busy} onClick={() => void transitionYear(year, "open")}>{t("Open independently")}</button>}{data.canClose && year.status === "OPEN" && year.periods.every((period) => period.status === "CLOSED") && <button type="button" disabled={busy} onClick={() => void transitionYear(year, "close")}>{t("Close year")}</button>}</div><div className="accounting-periods">{year.periods.map((period) => <span key={period.id}><b>{period.name}</b><small>{displayCode(period.status)}</small>{data.canClose && year.status === "OPEN" && period.status === "OPEN" && <button type="button" disabled={busy} onClick={() => void transitionPeriod(period, "LOCK")}>{t("Lock")}</button>}{data.canClose && year.status === "OPEN" && period.status !== "CLOSED" && <button type="button" disabled={busy} onClick={() => void transitionPeriod(period, "CLOSE")}>{t("Close")}</button>}</span>)}</div></section>)}{data.canConfigure && <form className="accounting-form-grid accounting-inline-form" onSubmit={createYear}><h3>{t("Create draft financial year")}</h3><label>{t("Code")}<input name="code" defaultValue={`FY_${yearStart}_${String(yearStart + 1).slice(-2)}`} pattern="[A-Za-z][A-Za-z0-9_-]{1,29}" required /></label><label>{t("Name")}<input name="name" defaultValue={`Financial year ${yearStart}-${yearStart + 1}`} maxLength={120} required /></label><label>{t("Starts on")}<input name="startsOn" type="date" defaultValue={`${yearStart}-04-01`} required /></label><label>{t("Ends on")}<input name="endsOn" type="date" defaultValue={`${yearStart + 1}-03-31`} required /></label><label>{t("Basis")}<select name="accountingBasis"><option value="ACCRUAL">{displayCode("ACCRUAL")}</option><option value="CASH">{displayCode("CASH")}</option></select></label><label>{t("Currency")}<input name="currencyCode" defaultValue="INR" pattern="[A-Za-z]{3}" required /></label><label className="is-wide">{t("Configuration reason")}<textarea name="reason" minLength={3} maxLength={500} required /></label><button type="submit" disabled={busy}>{t("Create draft year")}</button></form>}</article>
      <article className="accounting-panel"><header><div><span>{t("Master data")}</span><h2>{t("Chart of accounts")}</h2></div><MoneyIcon /></header><div className="accounting-master-list">{data.accounts.map((account) => <span key={account.id}><b>{account.code} · {account.name}</b><small>{displayCode(account.accountType)} · {displayCode(account.category)} · {t(account.allowDirectPosting ? "Posting" : "Parent")} · {displayCode(account.status)}</small></span>)}</div>{data.canConfigure && <form className="accounting-form-grid accounting-inline-form" onSubmit={createAccount}><h3>{t("Add account")}</h3><label>{t("Parent")}<select name="parentAccountId"><option value="">{t("Top level")}</option>{data.accounts.filter((account) => !account.allowDirectPosting).map((account) => <option key={account.id} value={account.id}>{account.code} · {account.name}</option>)}</select></label><label>{t("Code")}<input name="code" placeholder="A1000.CASH" pattern="[A-Za-z][A-Za-z0-9_.-]{1,29}" required /></label><label>{t("Name")}<input name="name" maxLength={160} required /></label><label>{t("Type")}<select name="accountType"><option value="ASSET">{displayCode("ASSET")}</option><option value="LIABILITY">{displayCode("LIABILITY")}</option><option value="EQUITY">{displayCode("EQUITY")}</option><option value="INCOME">{displayCode("INCOME")}</option><option value="EXPENSE">{displayCode("EXPENSE")}</option></select></label><label>{t("Category")}<select name="category"><option value="CASH">{displayCode("CASH")}</option><option value="BANK">{displayCode("BANK")}</option><option value="RECEIVABLE">{displayCode("RECEIVABLE")}</option><option value="PAYABLE">{displayCode("PAYABLE")}</option><option value="FUND">{displayCode("FUND")}</option><option value="INCOME">{displayCode("INCOME")}</option><option value="EXPENSE">{displayCode("EXPENSE")}</option><option value="OTHER">{displayCode("OTHER")}</option></select></label><label className="accounting-check"><input name="allowDirectPosting" type="checkbox" defaultChecked /> {t("Allow voucher posting")}</label><label className="is-wide">{t("Description")}<textarea name="description" maxLength={500} /></label><label className="is-wide">{t("Reason")}<input name="reason" maxLength={500} /></label><button type="submit" disabled={busy}>{t("Add account")}</button></form>}</article>
      <article className="accounting-panel"><header><div><span>{t("Payables dimension")}</span><h2>{t("Vendors")}</h2></div><PlusIcon /></header><div className="accounting-master-list">{data.vendors.map((vendor) => <span key={vendor.id}><b>{vendor.code} · {vendor.name}</b><small>{vendor.contactName || t("No contact")} · {displayCode(vendor.status)}</small></span>)}</div>{data.canConfigure && <form className="accounting-form-grid accounting-inline-form" onSubmit={createVendor}><h3>{t("Add vendor")}</h3><label>{t("Code")}<input name="code" pattern="[A-Za-z][A-Za-z0-9_-]{1,39}" required /></label><label>{t("Name")}<input name="name" maxLength={180} required /></label><label>{t("Contact")}<input name="contactName" maxLength={120} /></label><label>{t("Phone")}<input name="phone" maxLength={30} /></label><label>{t("Email")}<input name="email" type="email" maxLength={254} /></label><label className="is-wide">{t("Address")}<textarea name="address" maxLength={500} /></label><label className="is-wide">{t("Reason")}<input name="reason" maxLength={500} /></label><button type="submit" disabled={busy}>{t("Add vendor")}</button></form>}</article>
    </section>}
  </section>;
}
