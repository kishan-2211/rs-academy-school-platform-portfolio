"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ArrowUpRight } from "@/components/icons";
import { MoneyIcon, PlusIcon, ReportIcon } from "@/components/workspace/workspace-icons";
import { gatewayConfigurationLabel, manualReceiptCanReverse } from "@/lib/gateway-review";
import { GatewayReviewPanel } from "@/components/workspace/gateway-review-panel";
import { CollectionRegisterPanel } from "@/components/workspace/collection-register-panel";
import { refundDayCloseMinimum } from "@/lib/fee-collection-register";
import { RefundRequestsPanel } from "@/components/workspace/refund-requests-panel";
import { SettlementReportsPanel } from "@/components/workspace/settlement-reports-panel";
import { settlementMessage } from "@/lib/i18n/gateway-settlement-messages";
import { ManualFeeCollectionForm } from "@/components/workspace/manual-fee-collection-form";
import { FeeReceiptTimingText } from "@/components/fee-receipt-timing-text";
import { ReceiptPdfActions } from "@/components/receipt-pdf-actions";
import type { FeeReceiptTiming } from "@/lib/fee-receipt-timing";
import { feeAccountMatches } from "@/lib/finance-account-search";
import { useLanguage, useT } from "@/components/i18n/language-provider";

type Session = { id: string; name: string; startsOn: string; endsOn: string; status: string };
type FeeHead = { id: string; code: string; name: string; description?: string; category: string; refundable: boolean; status: string; rowVersion: number };
type PlanItem = { id: string; feeHeadId: string; feeHeadName: string; sequenceNumber: number; label: string; dueOn: string; amount: number; allowPartial: boolean; rowVersion: number };
type FeePlan = { id: string; sessionId: string; supersedesPlanId?: string; code: string; revisionNumber: number; name: string; gradeCode?: string; currencyCode: string; publicDisclosure: boolean; receiptPrefix: string; notes?: string; status: string; activatedAt?: string; rowVersion: number; items: PlanItem[] };
type Learner = { learnerId: string; enrolmentId: string; sessionId: string; learnerName: string; admissionNumber?: string; gradeCode: string; sessionName: string };
type Charge = { id: string; feeHeadCode: string; feeHeadName: string; label: string; dueOn: string; amount: number; concessionAmount: number; paidAmount: number; outstandingAmount: number; allowPartial: boolean; rteExemptionAmount: number; reservedAmount: number };
type Payment = { id: string; receiptNumber: string; paymentMethod: string; gatewayRefundSource?: boolean; externalReference?: string; amount: number; receivedOn: string; notes?: string; status: string; createdAt: string; rowVersion: number; timing?: FeeReceiptTiming };
type Account = { id: string; learnerId: string; enrolmentId: string; sessionId: string; learnerName: string; admissionNumber?: string; gradeCode: string; sessionName: string; rollNumber?: string | null; sectionCode?: string | null; currencyCode: string; status: string; totalCharges: number; totalCredits: number; balance: number; charges: Charge[]; payments: Payment[]; ledger: Array<{ id: string; entryType: string; direction: string; amount: number; effectiveOn: string; description: string; createdAt: string }> };
type Concession = { id: string; accountId: string; chargeId: string; learnerName: string; feeLabel: string; concessionType: string; amount: number; requestReason: string; status: string; requestedBy: string; decisionReason?: string; createdAt: string; rowVersion: number };
type AgeingAccount = { accountId: string; learnerName: string; admissionNumber?: string; gradeCode: string; sessionName: string; currencyCode: string; current: number; days1To30: number; days31To60: number; days61To90: number; daysOver90: number; totalOutstanding: number; oldestDueOn?: string; maximumDaysOverdue: number };
type Ageing = { asOf: string; totalOutstanding: number; accountCount: number; overdueAccountCount: number; current: number; days1To30: number; days31To60: number; days61To90: number; daysOver90: number; accounts: AgeingAccount[] };
type TenderSystemTotal = { paymentMethod: string; systemAmount: number };
type TenderTotal = TenderSystemTotal & { declaredAmount: number; variance: number };
type DayCloseCandidate = { businessDate: string; currencyCode: string; collectionCount: number; grossCollections: number; reversalCount: number; reversalAmount: number; netCollections: number; tenderTotals: TenderSystemTotal[]; refundCount:number; refundAmount:number };
type DayClose = { id: string; businessDate: string; revisionNumber: number; supersedesCloseId?: string; currencyCode: string; status: string; collectionCount: number; grossCollections: number; reversalCount: number; reversalAmount: number; netCollections: number; declaredTotal: number; varianceTotal: number; tenderTotals: TenderTotal[]; evidenceSha256: string; submissionReason: string; varianceReason?: string; submittedBy: string; submittedAt: string; decidedBy?: string; decidedAt?: string; decisionReason?: string; rowVersion: number; refundCount:number; refundAmount:number };
type Snapshot = { schoolId: string; schoolName: string; schoolCurrency: string; schoolTimeZone: string; schoolToday: string; latestClosedDate: string | null; canConfigure: boolean; canCollect: boolean; canApprove: boolean; canReverse: boolean; canProposeDayClose: boolean; canApproveDayClose: boolean; onlinePaymentStatus: string; ageing: Ageing; dayCloseCandidate: DayCloseCandidate; dayCloses: DayClose[]; sessions: Session[]; feeHeads: FeeHead[]; feePlans: FeePlan[]; learners: Learner[]; accounts: Account[]; concessions: Concession[] };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const label = (value: string) => value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
const today = () => new Date().toISOString().slice(0, 10);
function cookie(name: string) { return document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1); }
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new Error("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string) { try { const body = await response.json() as { detail?: string }; return body.detail || fallback; } catch { return fallback; } }

export function FinanceWorkspace() {
  const { locale } = useLanguage();
  const t = useT("fees");
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<"accounts" | "plans" | "approvals" | "operations" | "gateway" | "register" | "refunds" | "settlements">("accounts");
  const [selectedPlanId, setSelectedPlanId] = useState("");
  const [selectedAccountId, setSelectedAccountId] = useState("");
  const [accountQuery, setAccountQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [collectionIntentBlocked, setCollectionBlocked] = useState(false);
  const [settlementBlocked, setSettlementBlocked] = useState(false);
  const collectionBlocked = collectionIntentBlocked || settlementBlocked;
  const [refundBlocked, setRefundBlocked] = useState(false);
  const [refundPaymentId, setRefundPaymentId] = useState<string | undefined>();
  const guardedRefund = useRef<string | null>(null);
  const refundGuardChange = useCallback((blocked: boolean) => { guardedRefund.current = blocked ? data?.schoolId ?? guardedRefund.current : null; setRefundBlocked(blocked); },[data?.schoolId]);
  const guardedCollection = useRef<{ schoolId: string; accountId: string } | null>(null);
  const guardedSettlement = useRef<string | null>(null);
  const settlementGuardChange = useCallback((blocked: boolean) => { guardedSettlement.current = blocked ? data?.schoolId ?? guardedSettlement.current : null; setSettlementBlocked(blocked); }, [data?.schoolId]);

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/finance`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "The finance workspace could not be opened."));
    const snapshot = await response.json() as Snapshot;
    const guarded = guardedCollection.current;
    if (guarded && (snapshot.schoolId !== guarded.schoolId || !snapshot.accounts.some((account) => account.id === guarded.accountId))) {
      throw new Error("The collected account could not be refreshed. Review receipt history before starting another collection.");
    }
    if (guardedRefund.current && snapshot.schoolId !== guardedRefund.current) {
      throw new Error("The refund school's records could not be refreshed. Keep the original refund intent open and review its history.");
    }
    if (guardedSettlement.current && snapshot.schoolId !== guardedSettlement.current) {
      throw new Error("The report request's school changed. Preserve the original request and review its history before starting another sync.");
    }
    setData(snapshot);
    setSelectedPlanId((current) => snapshot.feePlans.some((item) => item.id === current) ? current : snapshot.feePlans[0]?.id || "");
    setSelectedAccountId((current) => snapshot.accounts.some((item) => item.id === current) ? current : snapshot.accounts[0]?.id || "");
  }, []);

  useEffect(() => {
    Promise.resolve()
      .then(load)
      .catch((reason: unknown) =>
        setError(reason instanceof Error ? reason.message : "The finance workspace could not be opened."),
      );
  }, [load]);
  const selectedPlan = useMemo(() => data?.feePlans.find((item) => item.id === selectedPlanId) ?? null, [data, selectedPlanId]);
  const selectedAccount = useMemo(() => data?.accounts.find((item) => item.id === selectedAccountId) ?? null, [data, selectedAccountId]);
  const matchingAccounts = useMemo(() => data?.accounts.filter((account) => feeAccountMatches(account, accountQuery)) ?? [], [data, accountQuery]);
  const selectedAccountMatchesSearch = matchingAccounts.some((account) => account.id === selectedAccountId);
  const money = (amount: number, currency = data?.schoolCurrency || "INR") => new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: 2 }).format(amount);

  async function mutate(path: string, init: RequestInit, success: string) {
    if (guardedCollection.current || guardedRefund.current || guardedSettlement.current) return false;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiUrl}${path}`, { ...init, credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken(), ...(init.headers || {}) } });
      if (!response.ok) throw new Error(await problem(response, "The finance change could not be saved."));
      await load(); setNotice(success); return true;
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The finance change could not be saved."); return false; }
    finally { setBusy(false); }
  }

  async function createHead(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    if (await mutate("/api/v1/finance/fee-heads", { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, code: form.get("code"), name: form.get("name"), description: form.get("description") || null, category: form.get("category"), refundable: form.get("refundable") === "on", status: "ACTIVE", reason: form.get("reason") || null }) }, "Fee head created.")) formElement.reset();
  }
  async function createPlan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement); const supersedes = String(form.get("supersedesPlanId") || ""); const source = data.feePlans.find((item) => item.id === supersedes);
    if (await mutate("/api/v1/finance/plans", { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, academicSessionId: form.get("sessionId"), supersedesPlanId: supersedes || null, code: source?.code || form.get("code"), revisionNumber: source ? source.revisionNumber + 1 : 1, name: form.get("name"), gradeCode: form.get("gradeCode") || null, currencyCode: form.get("currencyCode"), receiptPrefix: form.get("receiptPrefix"), notes: form.get("notes") || null, reason: form.get("reason") || null }) }, "Editable fee-plan draft created.")) formElement.reset();
  }
  async function addPlanItem(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !selectedPlan) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    if (await mutate(`/api/v1/finance/plans/${selectedPlan.id}/items`, { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, feeHeadId: form.get("feeHeadId"), sequenceNumber: Number(form.get("sequenceNumber")), label: form.get("label"), dueOn: form.get("dueOn"), amount: Number(form.get("amount")), allowPartial: form.get("allowPartial") === "on", reason: form.get("reason") || null }) }, "Scheduled fee line added to the draft.")) formElement.reset();
  }
  async function activatePlan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !selectedPlan) return; const form = new FormData(event.currentTarget);
    await mutate(`/api/v1/finance/plans/${selectedPlan.id}/activate`, { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, expectedVersion: selectedPlan.rowVersion, publicDisclosure: form.get("publicDisclosure") === "on", reason: form.get("reason") }) }, "Fee plan activated and frozen for assignment.");
  }
  async function assignPlan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    if (await mutate("/api/v1/finance/plan-assignments", { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, feePlanId: form.get("planId"), enrolmentId: form.get("enrolmentId"), reason: form.get("reason") }) }, "Plan assigned; learner charges and ledger evidence were created.")) formElement.reset();
  }
  async function deletePlanItem(item: PlanItem) {
    if (!data || !selectedPlan) return;
    await mutate(`/api/v1/finance/plans/${selectedPlan.id}/items/${item.id}?schoolId=${data.schoolId}&reason=${encodeURIComponent("Removed from the fee-plan draft during authority review")}`, { method: "DELETE" }, "Draft fee line removed; no learner charge was affected.");
  }
  async function requestConcession(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !selectedAccount) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    if (await mutate("/api/v1/finance/concessions", { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, accountId: selectedAccount.id, chargeId: form.get("chargeId"), concessionType: form.get("concessionType"), amount: Number(form.get("amount")), reason: form.get("reason") }) }, "Concession sent to a different authority for approval.")) formElement.reset();
  }
  async function decideConcession(event: FormEvent<HTMLFormElement>, concession: Concession) {
    event.preventDefault(); if (!data) return; const form = new FormData(event.currentTarget);
    await mutate(`/api/v1/finance/concessions/${concession.id}/decision`, { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, decision: form.get("decision"), reason: form.get("reason"), expectedVersion: concession.rowVersion }) }, "Concession decision recorded with immutable evidence.");
  }
  async function reversePayment(event: FormEvent<HTMLFormElement>, payment: Payment) {
    event.preventDefault(); if (!data) return; const form = new FormData(event.currentTarget);
    await mutate(`/api/v1/finance/payments/${payment.id}/reverse`, { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, expectedVersion: payment.rowVersion, reason: form.get("reason") }) }, "Receipt reversed with a counter-entry; the original remains visible.");
  }
  async function proposeDayClose(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    const declarations = ["CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER"].map((paymentMethod) => ({ paymentMethod, declaredAmount: Number(form.get(paymentMethod)) }));
    if (await mutate("/api/v1/finance/day-closes", { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, businessDate: form.get("businessDate"), declarations, reason: form.get("reason"), varianceReason: form.get("varianceReason") || null }) }, "Daily finance evidence submitted to a different authority.")) formElement.reset();
  }
  async function decideDayClose(event: FormEvent<HTMLFormElement>, close: DayClose) {
    event.preventDefault(); if (!data) return; const form = new FormData(event.currentTarget);
    await mutate(`/api/v1/finance/day-closes/${close.id}/decision`, { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, decision: form.get("decision"), reason: form.get("reason"), expectedVersion: close.rowVersion }) }, "Finance-close decision recorded; approved dates are now locked.");
  }

  if (!data) return <section className="finance-state"><MoneyIcon /><h1>{t(error ? "Finance is unavailable" : "Opening the finance workspace…")}</h1>{error && <p>{error}</p>}</section>;
  const pending = data.concessions.filter((item) => item.status === "PENDING");
  const totalBalance = data.accounts.reduce((sum, item) => sum + item.balance, 0);
  return <section className="finance-workspace">
    <header className="finance-hero"><div><span>{t("Fees & accountable finance")}</span><h1>{t("Every amount has a reason, owner, and history.")}</h1><p>{t("Draft the school's real schedules, activate them with authority, issue sequential receipts, and correct mistakes through visible counter-entries.")}</p></div><aside><ReportIcon /><strong>{money(totalBalance)}</strong><span>{t("Current account balance")}</span></aside></header>
    <div className="finance-trust"><span><i className="finance-dot finance-dot--amber" /> {t("Gateway configuration")}: {t(gatewayConfigurationLabel(data.onlinePaymentStatus))}</span><span>{t("Checkout availability is checked separately.")}</span><span>{t("Card details never enter this application")}</span><span>{t(pending.length === 1 ? "{count} approval waiting" : "{count} approvals waiting", { count: pending.length })}</span></div>
    {(error || notice) && <div className={`finance-banner ${error ? "finance-banner--error" : "finance-banner--success"}`} role="status">{error || notice}</div>}
    <nav className="finance-tabs" aria-label={t("Finance sections")}><button disabled={collectionBlocked || refundBlocked} className={tab === "accounts" ? "is-active" : ""} aria-pressed={tab === "accounts"} onClick={() => setTab("accounts")}>{t("Learner accounts")}</button><button disabled={collectionBlocked || refundBlocked} className={tab === "plans" ? "is-active" : ""} aria-pressed={tab === "plans"} onClick={() => setTab("plans")}>{t("Fee configuration")}</button><button disabled={collectionBlocked || refundBlocked} className={tab === "operations" ? "is-active" : ""} aria-pressed={tab === "operations"} onClick={() => setTab("operations")}>{t("Ageing & close")}</button><button disabled={collectionBlocked || refundBlocked} className={tab === "approvals" ? "is-active" : ""} aria-pressed={tab === "approvals"} onClick={() => setTab("approvals")}>{t("Approvals")} <b>{pending.length}</b></button><button disabled={collectionBlocked || refundBlocked} className={tab === "gateway" ? "is-active" : ""} aria-pressed={tab === "gateway"} onClick={() => setTab("gateway")}>{t("Online payment review")}</button><button disabled={collectionBlocked || refundBlocked} className={tab === "register" ? "is-active" : ""} aria-pressed={tab === "register"} onClick={() => setTab("register")}>{t("Collection register")}</button><button disabled={collectionBlocked || refundBlocked} className={tab === "refunds" ? "is-active" : ""} aria-pressed={tab === "refunds"} onClick={() => { setRefundPaymentId(undefined); setTab("refunds"); }}>{t("Refund requests")}</button></nav>

    <nav className="finance-tabs" aria-label={settlementMessage(locale,"title")}><button type="button" className={tab === "settlements" ? "is-active" : ""} disabled={collectionBlocked || refundBlocked} aria-pressed={tab === "settlements"} onClick={() => setTab("settlements")}>{settlementMessage(locale,"title")}</button></nav>
    {tab === "settlements" && <SettlementReportsPanel key={data.schoolId} schoolId={data.schoolId} onGuardChange={settlementGuardChange} />}
    {tab === "register" && <CollectionRegisterPanel key={data.schoolId} schoolId={data.schoolId} schoolToday={data.schoolToday} schoolCurrency={data.schoolCurrency} currencies={data.accounts.map(account=>account.currencyCode)} />}
    {tab === "gateway" && <GatewayReviewPanel key={data.schoolId} schoolId={data.schoolId} onPosted={load} />}
    {tab === "refunds" && <RefundRequestsPanel key={`${data.schoolId}:${refundPaymentId ?? "queue"}`} schoolId={data.schoolId} paymentId={refundPaymentId} onGuardChange={refundGuardChange} onSettled={load} />}

    {tab === "accounts" && <div className="finance-layout">
      <section className="finance-account-picker" aria-label={t("Find a learner fee account")}>
        <label htmlFor="finance-account-search">{t("Find a learner fee account")}</label>
        <input id="finance-account-search" type="search" maxLength={160} value={accountQuery} disabled={collectionBlocked} onChange={(event) => setAccountQuery(event.target.value)} placeholder={t("Name, admission number, class, section, roll, session or fee period")} aria-describedby="finance-account-search-status" autoComplete="off" />
        {accountQuery && <button type="button" disabled={collectionBlocked} onClick={() => setAccountQuery("")}>{t("Clear account search")}</button>}
        <p id="finance-account-search-status" role="status" aria-live="polite" aria-atomic="true">{t("{shown} of {total} accounts", { shown: matchingAccounts.length, total: data.accounts.length })}</p>
        {selectedAccount && !matchingAccounts.some((account) => account.id === selectedAccountId) && <p>{t("The selected account is unchanged. Clear the search or choose a matching account before collecting.")}</p>}
        <aside className="finance-list"><div className="finance-list__head"><span>{t("Accounts")}</span><strong>{matchingAccounts.length}</strong></div>{matchingAccounts.length ? matchingAccounts.map((account) => <button disabled={collectionBlocked} aria-pressed={selectedAccountId === account.id} key={account.id} className={selectedAccountId === account.id ? "is-active" : ""} onClick={() => setSelectedAccountId(account.id)}><span>{account.learnerName}</span><small>{account.admissionNumber || "Admission number pending"} · {account.gradeCode}{account.sectionCode && ` · ${account.sectionCode}`}{account.rollNumber && ` · ${t("Roll {roll}", { roll: account.rollNumber })}`} · {account.sessionName}</small><strong>{money(account.balance, account.currencyCode)}</strong></button>) : <p>{data.accounts.length ? t("No fee accounts match this search.") : t("No plan has been assigned to a learner yet.")}</p>}</aside>
      </section>
      <div className="finance-detail">{selectedAccount ? <>
        <header><div><span>{selectedAccount.admissionNumber || "Admission number pending"}</span><h2>{selectedAccount.learnerName}</h2><p>{selectedAccount.gradeCode} · {selectedAccount.sessionName}</p></div><strong>{money(selectedAccount.balance, selectedAccount.currencyCode)}<small>balance</small></strong></header>
        {selectedAccount.status !== "ACTIVE" && <div className="finance-banner" role="status"><strong>{t("Account history — new collections unavailable")}</strong><p>{t("This fee account is not active. Review its existing records and any eligible original-account reopening request; no new payment can be collected here.")}</p></div>}
        <div className="finance-metrics"><article><span>Assessed</span><b>{money(selectedAccount.totalCharges, selectedAccount.currencyCode)}</b></article><article><span>Credits</span><b>{money(selectedAccount.totalCredits, selectedAccount.currencyCode)}</b></article><article><span>Open lines</span><b>{selectedAccount.charges.filter((item) => item.outstandingAmount > 0).length}</b></article></div>
        <section className="finance-card"><div className="finance-card__title"><div><span>Scheduled charges</span><h3>What is due and why</h3></div></div><div className="finance-table"><div className="finance-table__row finance-table__row--head"><span>Fee</span><span>Due</span><span>Original</span><span>Paid / concession</span><span>Outstanding</span></div>{selectedAccount.charges.map((charge) => <div className="finance-table__row" key={charge.id}><span><b>{charge.label}</b><small>{charge.feeHeadName}{charge.allowPartial ? " · partial allowed" : " · full installment"}</small>{charge.rteExemptionAmount > 0 && <small>Exempt — RTE</small>}</span><span>{new Date(`${charge.dueOn}T00:00:00`).toLocaleDateString("en-IN")}</span><span>{money(charge.amount, selectedAccount.currencyCode)}</span><span>{money(charge.paidAmount + charge.concessionAmount, selectedAccount.currencyCode)}{charge.rteExemptionAmount > 0 && <small>RTE Fee Exemption: {money(charge.rteExemptionAmount, selectedAccount.currencyCode)}</small>}</span><strong>{money(charge.outstandingAmount, selectedAccount.currencyCode)}</strong></div>)}</div></section>
        <div className="finance-actions">
          {data.canCollect && selectedAccount.status === "ACTIVE" && <ManualFeeCollectionForm
            key={`${data.schoolId}:${selectedAccount.id}`}
            schoolId={data.schoolId} accountId={selectedAccount.id} learnerName={selectedAccount.learnerName}
            currencyCode={selectedAccount.currencyCode} balance={selectedAccount.balance}
            schoolTimeZone={data.schoolTimeZone} schoolToday={data.schoolToday}
            latestClosedDate={data.latestClosedDate ?? undefined} charges={selectedAccount.charges}
            externallyBusy={busy || !selectedAccountMatchesSearch} onCollected={load}
            onCollectionGuardChange={(blocked) => {
              guardedCollection.current = blocked ? { schoolId: data.schoolId, accountId: selectedAccount.id } : null;
              setCollectionBlocked(blocked);
            }}
          />}
{data.canConfigure && <form onSubmit={requestConcession} className="finance-form"><div className="finance-form__title"><ReportIcon /><div><span>Maker-checker control</span><h3>Request a concession</h3></div></div><label className="finance-form__wide">Charge<select name="chargeId" required>{selectedAccount.charges.filter((item) => item.outstandingAmount > 0).map((charge) => <option key={charge.id} value={charge.id}>{charge.label} · {money(charge.outstandingAmount, selectedAccount.currencyCode)}</option>)}</select></label><label>Type<select name="concessionType"><option value="SCHOLARSHIP">Scholarship</option><option value="SIBLING">Sibling</option><option value="STAFF">Staff</option><option value="HARDSHIP">Hardship</option><option value="OTHER">Other</option></select></label><label>Amount<input name="amount" type="number" min="0.01" step="0.01" required /></label><label className="finance-form__wide">Reason<textarea name="reason" minLength={3} maxLength={500} required /></label><button disabled={busy || collectionBlocked}>Send for approval <ArrowUpRight /></button></form>}
        </div>
        <section className="finance-card"><div className="finance-card__title"><div><span>Receipt history</span><h3>Original evidence is never overwritten</h3></div></div>{selectedAccount.payments.length ? <div className="finance-receipts">{selectedAccount.payments.map((payment) => <article key={payment.id}><div><span>{payment.receiptNumber}</span><h4>{money(payment.amount, selectedAccount.currencyCode)}</h4><p>{label(payment.paymentMethod)}</p><FeeReceiptTimingText postingOn={payment.receivedOn} timing={payment.timing} /></div><b className={payment.status === "POSTED" ? "is-posted" : "is-reversed"}>{label(payment.status)}</b>{data.canReverse && manualReceiptCanReverse(payment) && <form onSubmit={(event) => reversePayment(event, payment)}><input name="reason" placeholder="Required reversal reason" minLength={3} maxLength={240} required /><button disabled={busy || collectionBlocked}>Reverse</button></form>}{payment.gatewayRefundSource === true && payment.paymentMethod === "ONLINE_PROVIDER" && payment.status === "POSTED" && <button type="button" disabled={busy || collectionBlocked || refundBlocked} onClick={() => { if (!guardedCollection.current && !guardedRefund.current) { setRefundPaymentId(payment.id); setTab("refunds"); } }}>{t("Request gateway refund")}</button>}<ReceiptPdfActions key={`${data.schoolId}:${selectedAccount.id}:${payment.id}`} audience="staff" schoolId={data.schoolId} paymentId={payment.id} receiptNumber={payment.receiptNumber} paymentStatus={payment.status} externallyBusy={busy || collectionBlocked} /></article>)}</div> : <p>No receipts have been issued on this account.</p>}</section>
      </> : <div className="finance-empty"><MoneyIcon /><h2>{t("No learner fee account yet")}</h2><p>{t("Activate a reviewed plan, then assign it to a current enrolment.")}</p></div>}</div>
    </div>}

    {tab === "plans" && <div className="finance-config">
        <div className="finance-config__forms">
        {data.canConfigure && <form onSubmit={createHead} className="finance-form finance-form--stack"><div className="finance-form__title"><PlusIcon /><div><span>Reusable building block</span><h3>Create a fee head</h3></div></div><label>Code<input name="code" placeholder="TUITION" pattern="[A-Za-z][A-Za-z0-9_ ]{1,29}" required /></label><label>Name<input name="name" placeholder="Tuition fee" maxLength={120} required /></label><label>Category<select name="category"><option>TUITION</option><option>ADMISSION</option><option>ANNUAL</option><option>MATERIAL</option><option>TRANSPORT</option><option>ACTIVITY</option><option>EXAM</option><option>OTHER</option></select></label><label>Description<textarea name="description" maxLength={500} /></label><label className="finance-check"><input name="refundable" type="checkbox" /> Refundable under policy</label><label>Setup reason<input name="reason" maxLength={240} /></label><button disabled={busy}>Create fee head</button></form>}
        {data.canConfigure && <form onSubmit={createPlan} className="finance-form finance-form--stack"><div className="finance-form__title"><ReportIcon /><div><span>Editable until activation</span><h3>Create a plan draft</h3></div></div><label>Academic session<select name="sessionId" required>{data.sessions.map((session) => <option value={session.id} key={session.id}>{session.name}</option>)}</select></label><label>Supersedes (optional)<select name="supersedesPlanId"><option value="">New plan code</option>{data.feePlans.filter((item) => item.status === "ACTIVE").map((plan) => <option value={plan.id} key={plan.id}>{plan.name} · r{plan.revisionNumber}</option>)}</select></label><label>Plan code<input name="code" placeholder="CLASS_6_CORE" pattern="[A-Za-z][A-Za-z0-9_ ]{1,39}" required /></label><label>Name<input name="name" placeholder="Class VI standard fees" maxLength={140} required /></label><label>Grade code (optional)<input name="gradeCode" placeholder="VI" maxLength={40} /></label><label>Currency<input name="currencyCode" defaultValue={data.schoolCurrency} pattern="[A-Za-z]{3}" required /></label><label>Receipt prefix<input name="receiptPrefix" placeholder="RSA/26" pattern="[A-Za-z0-9][A-Za-z0-9/\-]{1,23}" required /></label><label>Notes<textarea name="notes" maxLength={1000} /></label><label>Setup reason<input name="reason" maxLength={240} /></label><button disabled={busy}>Create editable draft</button></form>}
      </div>
      <section className="finance-plans"><aside className="finance-list"><div className="finance-list__head"><span>Plan revisions</span><strong>{data.feePlans.length}</strong></div>{data.feePlans.map((plan) => <button key={plan.id} className={selectedPlanId === plan.id ? "is-active" : ""} onClick={() => setSelectedPlanId(plan.id)}><span>{plan.name}</span><small>{plan.code} · revision {plan.revisionNumber}</small><strong>{label(plan.status)}</strong></button>)}</aside><div className="finance-detail">{selectedPlan ? <><header><div><span>{selectedPlan.code} · revision {selectedPlan.revisionNumber}</span><h2>{selectedPlan.name}</h2><p>{selectedPlan.gradeCode || "Any matching grade"} · {selectedPlan.currencyCode}</p></div><b className={`finance-status finance-status--${selectedPlan.status.toLowerCase()}`}>{label(selectedPlan.status)}</b></header><div className="finance-table"><div className="finance-table__row finance-table__row--plan finance-table__row--head"><span>Line</span><span>Head</span><span>Due</span><span>Amount</span></div>{selectedPlan.items.map((item) => <div className="finance-table__row finance-table__row--plan" key={item.id}><span><b>{item.sequenceNumber}. {item.label}</b><small>{item.allowPartial ? "Partial collection allowed" : "Collect in full"}</small></span><span>{item.feeHeadName}</span><span>{item.dueOn}</span><strong>{money(item.amount, selectedPlan.currencyCode)}{selectedPlan.status === "DRAFT" && data.canConfigure && <button className="finance-line-remove" type="button" disabled={busy} onClick={() => void deletePlanItem(item)}>Remove</button>}</strong></div>)}</div>{selectedPlan.status === "DRAFT" && data.canConfigure && <form onSubmit={addPlanItem} className="finance-inline-form"><h3>Add a scheduled line</h3><label>Fee head<select name="feeHeadId" required>{data.feeHeads.filter((head) => head.status === "ACTIVE").map((head) => <option value={head.id} key={head.id}>{head.name}</option>)}</select></label><label>Sequence<input name="sequenceNumber" type="number" min="1" max="1000" defaultValue={selectedPlan.items.length + 1} required /></label><label>Label<input name="label" placeholder="July tuition" maxLength={140} required /></label><label>Due on<input name="dueOn" type="date" required /></label><label>Amount<input name="amount" type="number" min="0.01" step="0.01" required /></label><label className="finance-check"><input name="allowPartial" type="checkbox" defaultChecked /> Allow partial</label><label>Reason<input name="reason" maxLength={240} /></label><button disabled={busy}>Add line</button></form>}{selectedPlan.status === "DRAFT" && data.canApprove && <form onSubmit={activatePlan} className="finance-activate"><div><span>Authority checkpoint</span><h3>Freeze and activate this schedule</h3><p>After activation, learner assignments copy these exact lines into immutable charges.</p></div><label className="finance-check"><input name="publicDisclosure" type="checkbox" /> Approved for public fee disclosure</label><input name="reason" placeholder="Required activation reason" minLength={3} maxLength={240} required /><button disabled={busy || !selectedPlan.items.length}>Activate plan</button></form>}</> : <div className="finance-empty"><ReportIcon /><h2>Create the first draft</h2><p>The photographed fee notice remains reference material until an authority confirms each line.</p></div>}</div></section>
      {data.canConfigure && <form onSubmit={assignPlan} className="finance-assign"><div><span>Controlled materialization</span><h3>Assign an active plan to a learner</h3><p>This creates dated charges and cannot be silently undone.</p></div><label>Plan<select name="planId" required>{data.feePlans.filter((plan) => plan.status === "ACTIVE").map((plan) => <option value={plan.id} key={plan.id}>{plan.name} · {plan.gradeCode || "all grades"}</option>)}</select></label><label>Learner enrolment<select name="enrolmentId" required>{data.learners.map((learner) => <option value={learner.enrolmentId} key={learner.enrolmentId}>{learner.learnerName} · {learner.gradeCode} · {learner.sessionName}</option>)}</select></label><label>Assignment reason<input name="reason" maxLength={240} required /></label><button disabled={busy}>Create learner charges</button></form>}
    </div>}

    {tab === "operations" && <section className="finance-operations">
      <header className="finance-operations__head"><span>Collections control</span><h2>See what is overdue, then close each financial day with independent evidence.</h2><p>Ageing is calculated live from charges and ledger entries. A day close freezes the source rows and declared tender totals; another authority must approve it before the date is locked.</p></header>
      <div className="finance-ageing-metrics">
        <article><span>Outstanding</span><strong>{money(data.ageing.totalOutstanding)}</strong><small>{data.ageing.accountCount} account{data.ageing.accountCount === 1 ? "" : "s"}</small></article>
        <article><span>Current</span><strong>{money(data.ageing.current)}</strong><small>Not overdue</small></article>
        <article><span>1–30 days</span><strong>{money(data.ageing.days1To30)}</strong><small>Early follow-up</small></article>
        <article><span>31–90 days</span><strong>{money(data.ageing.days31To60 + data.ageing.days61To90)}</strong><small>Escalated review</small></article>
        <article><span>Over 90 days</span><strong>{money(data.ageing.daysOver90)}</strong><small>{data.ageing.overdueAccountCount} overdue account{data.ageing.overdueAccountCount === 1 ? "" : "s"}</small></article>
      </div>
      <section className="finance-card"><div className="finance-card__title"><div><span>As of {data.ageing.asOf}</span><h3>Ageing queue</h3></div><small>Authorized finance use only</small></div>{data.ageing.accounts.length ? <div className="finance-ageing-table"><div className="finance-ageing-row finance-ageing-row--head"><span>Learner</span><span>Current</span><span>1–30</span><span>31–60</span><span>61–90</span><span>90+</span><span>Total</span></div>{data.ageing.accounts.map((account) => <div className="finance-ageing-row" key={account.accountId}><span><b>{account.learnerName}</b><small>{account.admissionNumber || "Admission pending"} · {account.gradeCode}</small></span><span>{money(account.current, account.currencyCode)}</span><span>{money(account.days1To30, account.currencyCode)}</span><span>{money(account.days31To60, account.currencyCode)}</span><span>{money(account.days61To90, account.currencyCode)}</span><span>{money(account.daysOver90, account.currencyCode)}</span><strong>{money(account.totalOutstanding, account.currencyCode)}<small>{account.maximumDaysOverdue ? `${account.maximumDaysOverdue} days oldest` : "Current"}</small></strong></div>)}</div> : <div className="finance-empty"><MoneyIcon /><h2>No outstanding scheduled charges</h2><p>The ageing queue will appear when an assigned charge has a balance.</p></div>}</section>
      <div className="finance-close-grid">
        <section className="finance-card finance-close-candidate">
          <div className="finance-card__title"><div><span>{data.dayCloseCandidate.businessDate}</span><h3>Today&apos;s system evidence</h3></div><b>{money(data.dayCloseCandidate.netCollections, data.dayCloseCandidate.currencyCode)}</b></div>
          <div className="finance-close-summary"><span>{data.dayCloseCandidate.collectionCount} collection{data.dayCloseCandidate.collectionCount === 1 ? "" : "s"}</span><span>Gross {money(data.dayCloseCandidate.grossCollections, data.dayCloseCandidate.currencyCode)}</span><span>{data.dayCloseCandidate.reversalCount} reversal{data.dayCloseCandidate.reversalCount === 1 ? "" : "s"}</span><span>Reversed {money(data.dayCloseCandidate.reversalAmount, data.dayCloseCandidate.currencyCode)}</span><span>{t("Completed gateway refunds")}: {data.dayCloseCandidate.refundCount} · {money(data.dayCloseCandidate.refundAmount,data.dayCloseCandidate.currencyCode)}</span></div>
          <div className="finance-close-tenders">{data.dayCloseCandidate.tenderTotals.map((tender) => <div key={tender.paymentMethod}><span>{label(tender.paymentMethod)}</span><strong>{money(tender.systemAmount, data.dayCloseCandidate.currencyCode)}</strong></div>)}</div>
        </section>
        {data.canProposeDayClose ? <form onSubmit={proposeDayClose} className="finance-form finance-form--stack finance-close-form">
          <div className="finance-form__title"><ReportIcon /><div><span>Maker submission</span><h3>Declare and submit a day close</h3></div></div>
          <label>Business date<input name="businessDate" type="date" max={today()} defaultValue={data.dayCloseCandidate.businessDate} required /></label>
          {data.dayCloseCandidate.tenderTotals.map((tender) => <label key={tender.paymentMethod}>{label(tender.paymentMethod)} declared<input name={tender.paymentMethod} type="number" min={refundDayCloseMinimum(tender.paymentMethod,tender.systemAmount,data.dayCloseCandidate.refundCount)} max="100000000" step="0.01" defaultValue={tender.systemAmount.toFixed(2)} required /></label>)}
          <small>{t("Signed online declaration is available only for verified refund-negative movements; it is not a bank-settlement total.")}</small>
          <label>Submission reason<textarea name="reason" minLength={3} maxLength={500} required /></label><label>Variance reason (required when totals differ)<textarea name="varianceReason" maxLength={500} /></label><button disabled={busy}>Submit frozen evidence</button><small>The declaration is independent evidence. Do not copy system totals without physically verifying the school&apos;s tender records.</small>
        </form> : <section className="finance-card"><div className="finance-empty"><ReportIcon /><h2>Close preparation is restricted</h2><p>An authorized finance officer must declare the tender totals.</p></div></section>}
      </div>
      <section className="finance-card"><div className="finance-card__title"><div><span>Immutable register</span><h3>Daily close history</h3></div><small>Rejected closes are superseded, never edited</small></div>{data.dayCloses.length ? <div className="finance-close-history">{data.dayCloses.map((close) => <article key={close.id}><header><div><span>{close.businessDate} · revision {close.revisionNumber}</span><h4>{money(close.netCollections, close.currencyCode)} net</h4></div><b className={`finance-close-status finance-close-status--${close.status.toLowerCase()}`}>{label(close.status)}</b></header><div className="finance-close-summary"><span>System {money(close.netCollections, close.currencyCode)}</span><span>Declared {money(close.declaredTotal, close.currencyCode)}</span><span className={close.varianceTotal ? "is-variance" : ""}>Variance {money(close.varianceTotal, close.currencyCode)}</span><span>{t("Completed gateway refunds")}: {close.refundCount} · {money(close.refundAmount,close.currencyCode)}</span></div><p>Submitted by {close.submittedBy} · {new Date(close.submittedAt).toLocaleString("en-IN")}</p>{close.varianceReason && <blockquote>Variance: {close.varianceReason}</blockquote>}<small>Evidence SHA-256: {close.evidenceSha256}</small>{close.decisionReason && <p>{label(close.status)} by {close.decidedBy}: {close.decisionReason}</p>}{close.status === "PENDING" && data.canApproveDayClose && <form onSubmit={(event) => decideDayClose(event, close)}><select name="decision"><option value="APPROVED">Approve and lock date</option><option value="REJECTED">Reject for revision</option></select><textarea name="reason" placeholder="Required independent decision reason" minLength={3} maxLength={500} required /><button disabled={busy}>Record decision</button></form>}</article>)}</div> : <div className="finance-empty"><ReportIcon /><h2>No finance dates have been closed</h2><p>The first submitted close will create the permanent register.</p></div>}</section>
    </section>}

    {tab === "approvals" && <section className="finance-approval"><header><span>Maker-checker queue</span><h2>One person proposes; another person decides.</h2><p>Approved concessions become visible credits. Rejections preserve the request and reason without changing the ledger.</p></header>{pending.length ? <div className="finance-approval__grid">{pending.map((item) => <article key={item.id}><span>{item.concessionType}</span><h3>{item.learnerName}</h3><p>{item.feeLabel}</p><strong>{money(item.amount)}</strong><blockquote>{item.requestReason}</blockquote><small>Requested by {item.requestedBy}</small>{data.canApprove ? <form onSubmit={(event) => decideConcession(event, item)}><select name="decision"><option value="APPROVED">Approve</option><option value="REJECTED">Reject</option></select><textarea name="reason" placeholder="Required decision reason" minLength={3} maxLength={500} required /><button disabled={busy}>Record decision</button></form> : <p>A separate fee authority must decide this request.</p>}</article>)}</div> : <div className="finance-empty"><ReportIcon /><h2>No concessions are waiting</h2><p>Decided requests remain available in the audit history.</p></div>}</section>}
  </section>;
}
