"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { availablePaymentPaise, continueFamilyPayment, FamilyPaymentAccessError, familyPaymentCsrf, familyPaymentErrorCopy, familyPaymentErrors, loadFamilyPaymentHistory, paymentAmountInput, paymentPaise, prepareFamilyPayment, sendFamilyPayment, validatePaymentSelection, type FamilyPayment, type FamilyPaymentHistory, type GatewayFeeContext, type PreparedFamilyPayment } from "@/lib/family-gateway-payments";
import { FamilyPaymentCard, useBoundedPendingPaymentRefresh } from "./payment-status-view";
import styles from "./gateway-payment-panel.module.css";

/** Scope every ref, request and contact field to exactly one current child/account. */
type Props = { fees: GatewayFeeContext; onRecorded: () => void | Promise<void>; onGuardChange?: (blocked: boolean) => void; onAccessLost?: () => void };
export function FamilyGatewayPaymentPanel(props: Props) {
  return <ScopedGatewayPaymentPanel key={`${props.fees.learnerId}:${props.fees.accountId}:${props.fees.explicitAccountId ? "explicit" : "current"}`} {...props} />;
}

function ScopedGatewayPaymentPanel({ fees, onRecorded, onGuardChange, onAccessLost }: Props) {
  const t = useT("parent"), { locale } = useLanguage(), id = useId();
  const [history, setHistory] = useState<FamilyPaymentHistory | null>(null), [selection, setSelection] = useState<Record<string, string>>({});
  const [payerPhone, setPayerPhone] = useState(""), [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState(""), [message, setMessage] = useState("");
  const request = useRef<AbortController | null>(null), prepared = useRef<PreparedFamilyPayment | null>(null);
  const acknowledged = useRef(false), callbacks = useRef({ onGuardChange, onAccessLost });
  useEffect(() => { callbacks.current = { onGuardChange, onAccessLost }; }, [onGuardChange, onAccessLost]);
  const [dispatched, setDispatched] = useState(false);
  const money = (paise: number) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", { style: "currency", currency: "INR" }).format(paise / 100);
  const fail = useCallback((reason: unknown) => {
    if (reason instanceof FamilyPaymentAccessError) {
      setHistory(null); setSelection({}); setPayerPhone(""); prepared.current = null; acknowledged.current = false; setUncertain(false); setDispatched(false); setMessage("");
      callbacks.current.onGuardChange?.(false); callbacks.current.onAccessLost?.();
    }
    setError(familyPaymentErrorCopy(reason));
  }, []);
  useEffect(() => {
    const controller = new AbortController(); request.current = controller;
    void Promise.resolve().then(() => loadFamilyPaymentHistory(fees.learnerId, 0, controller.signal)).then(value => {
      if (!controller.signal.aborted) setHistory(value);
    }).catch(reason => { if (!controller.signal.aborted) fail(reason); }).finally(() => { if (request.current === controller) request.current = null; });
    return () => { controller.abort(); request.current?.abort(); };
  }, [fees.learnerId, fail]);
  useEffect(() => {
    if (!uncertain && !dispatched) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [uncertain, dispatched]);
  const refresh = useCallback(async (append = false) => {
    if (request.current || (append && (!history || !history.hasMore || history.page >= 10000))) return;
    const controller = new AbortController(); request.current = controller; setBusy(true); setError("");
    try {
      const next = await loadFamilyPaymentHistory(fees.learnerId, append && history ? history.page + 1 : 0, controller.signal);
      if (controller.signal.aborted) return;
      setHistory(previous => append && previous ? { ...next, payments: [...previous.payments, ...next.payments.filter(item => !previous.payments.some(old => old.id === item.id))] } : next);
      // Refreshing history alone never clears an uncertain immutable request.
    } catch (reason) { if (!controller.signal.aborted) fail(reason); }
    finally { if (request.current === controller) request.current = null; if (!controller.signal.aborted) setBusy(false); }
  }, [fees.learnerId, history, fail]);
  useBoundedPendingPaymentRefresh(!!history?.payments.some(item => item.status === "PENDING") && !error, refresh);
  const eligible = useMemo(() => {
    try { return fees.charges.map(charge => ({ charge, available: availablePaymentPaise(charge) })); }
    catch { return null; }
  }, [fees.charges]);
  let total = 0, validSelection = false;
  try { const allocations = validatePaymentSelection(fees, selection); total = allocations.reduce((sum, item) => sum + paymentPaise(item.amount), 0); validSelection = true; } catch { /* Native controls remain editable; submit also validates current exact terms. */ }
  const enabled = history?.availability === "AVAILABLE" && !!eligible && fees.currencyCode === "INR" && fees.accountStatus !== "CLOSED" && fees.paymentsAllowed !== false && !error;
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (request.current || (!prepared.current && !enabled)) return;
    const controller = new AbortController(); request.current = controller; setBusy(true); setError(""); setMessage("");
    let sent = false;
    try {
      const csrf = await familyPaymentCsrf(controller.signal);
      if (controller.signal.aborted) return;
      prepared.current = prepareFamilyPayment(prepared.current, fees, selection, payerPhone, () => crypto.randomUUID());
      callbacks.current.onGuardChange?.(true);
      sent = true; setDispatched(true);
      if (!acknowledged.current) { await sendFamilyPayment(prepared.current, csrf, controller.signal); acknowledged.current = true; }
      if (controller.signal.aborted) return;
      setMessage("The payment request was recorded. Check verified status before paying or retrying.");
      const next = await loadFamilyPaymentHistory(fees.learnerId, 0, controller.signal);
      if (controller.signal.aborted) return;
      await onRecorded();
      if (controller.signal.aborted) return;
      setHistory(next);
      // Acknowledgment plus both scoped refreshes release the guard, never a browser success claim.
      prepared.current = null; acknowledged.current = false; setUncertain(false); setDispatched(false); setPayerPhone(""); setSelection({});
      callbacks.current.onGuardChange?.(false);
    } catch (reason) {
      if (!controller.signal.aborted) {
        fail(reason);
        if (!(reason instanceof FamilyPaymentAccessError) && prepared.current && (sent || uncertain)) {
          setUncertain(true); setError(familyPaymentErrors.uncertain);
        }
      }
    } finally { if (request.current === controller) request.current = null; if (!controller.signal.aborted) { setBusy(false); setDispatched(false); } }
  }
  async function continueCheckout(payment: FamilyPayment) {
    if (request.current || error || uncertain) return;
    const controller = new AbortController(); request.current = controller; setBusy(true); setError("");
    try {
      const csrf = await familyPaymentCsrf(controller.signal);
      if (controller.signal.aborted) return;
      const url = await continueFamilyPayment(payment, csrf, controller.signal);
      if (!controller.signal.aborted) window.location.assign(url);
    } catch (reason) { if (!controller.signal.aborted) fail(reason); }
    finally { if (request.current === controller) request.current = null; if (!controller.signal.aborted) setBusy(false); }
  }
  return <section className={styles.root} aria-labelledby={`${id}-title`} aria-busy={busy || (!history && !error)}>
    <h3 id={`${id}-title`}>{t("Pay school fees online")}</h3>
    <p>{t("Choose available charges, then use the provider’s secure UPI, card or net-banking checkout. The school records payment only after server verification.")}</p>
    {fees.accountStatus === "CLOSED" && <p className={styles.warning}>{t("This fee account is closed and view-only. Existing statements, receipts and payment history remain available; no new payment can be started.")}</p>}
    {fees.accountStatus !== "CLOSED" && fees.paymentsAllowed === false && <p className={styles.warning}>{t("Choose or refresh a fee account to confirm whether new payments are available.")}</p>}
    {error && <p className={styles.error} role="alert">{t(error)}</p>}
    {message && <p role="status">{t(message)}</p>}
    {!history && !error && <p role="status">{t("Checking online payment availability and history…")}</p>}
    {history && history.availability !== "AVAILABLE" && <p className={styles.warning}>{t(history.availability === "DISABLED" ? "Online checkout is not enabled for this school. Existing payment history remains available; contact the office for other payment methods." : "Online checkout is temporarily unavailable. Existing payment history remains available; do not repeat a pending payment.")}</p>}
    {!eligible && <p className={styles.error}>{t("The available fee balance could not be validated. Refresh the fee statement before starting a payment.")}</p>}
    <form onSubmit={event => void submit(event)} aria-label={t("Select school fees for online payment")}>
      <fieldset disabled={busy || uncertain || !enabled}>
        <legend>{t("Choose charges to pay")}</legend>
        {eligible?.map(({ charge, available }) => {
          const chosen = Object.hasOwn(selection, charge.id), blocked = available < 1 || (!charge.allowPartial && charge.reservedAmount > 0);
          return <div className={styles.choice} key={charge.id}>
            <div><label className={styles.checkLabel} htmlFor={`${id}-${charge.id}-choose`}><input id={`${id}-${charge.id}-choose`} type="checkbox" checked={chosen} disabled={blocked} onChange={event => setSelection(previous => {
              const next = { ...previous }; if (event.target.checked) next[charge.id] = paymentAmountInput(available); else delete next[charge.id]; return next;
            })} />{charge.label}</label><small>{t("Available {amount}", { amount: money(available) })} · {t(charge.allowPartial ? "Partial payment allowed" : "Full installment only")}</small>
              {charge.reservedAmount > 0 && <small>{t("Held for pending verification: {amount}", { amount: money(paymentPaise(charge.reservedAmount)) })}</small>}
            </div>
            <div><label htmlFor={`${id}-${charge.id}-amount`}>{t("Amount for {charge}", { charge: charge.label })}</label><input id={`${id}-${charge.id}-amount`} type="text" inputMode="decimal" autoComplete="off" value={selection[charge.id] ?? ""} disabled={!chosen || !charge.allowPartial || blocked} onChange={event => setSelection(previous => ({ ...previous, [charge.id]: event.target.value }))} aria-describedby={`${id}-${charge.id}-hint`} maxLength={12} /><small id={`${id}-${charge.id}-hint`}>{t("Enter rupees with up to two decimal places.")}</small></div>
          </div>;
        })}
        {eligible && !eligible.some(item => item.available > 0 && (item.charge.allowPartial || item.charge.reservedAmount === 0)) && <p>{t("There are no scheduled charges available for online payment.")}</p>}
        <div className={styles.phone}><label htmlFor={`${id}-phone`}>{t("Payer mobile number")}</label><input id={`${id}-phone`} type="tel" inputMode="numeric" autoComplete="off" value={payerPhone} onChange={event => setPayerPhone(event.target.value)} pattern="[0-9]{10}" minLength={10} maxLength={10} required aria-describedby={`${id}-phone-help`} /><small id={`${id}-phone-help`}>{t("10-digit Indian mobile number. Used only for this provider checkout; never stored in browser storage.")}</small></div>
      </fieldset>
      <div className={styles.total}><span>{t("Selected total")}</span><strong>{money(total)}</strong></div>
      <button type="submit" className={styles.primary} disabled={busy || (!uncertain && (!enabled || !validSelection))} formNoValidate={uncertain}>{t(busy ? "Checking payment…" : uncertain ? "Retry this unchanged payment request" : "Pay now")}</button>
      {(uncertain || dispatched) && <p className={styles.warning}>{t("This request is kept only in memory. Child, account and link changes are blocked until it is confirmed and refreshed. A forced exit or reload loses its retry key; check payment history before starting another request.")}</p>}
    </form>
    <section className={styles.history} aria-labelledby={`${id}-history`}><h4 id={`${id}-history`}>{t("Online payment history")}</h4>
      {/* Keep activated reads focusable while busy and retain the last append control. */}
      <div className={styles.actions}><button type="button" disabled={!busy && !history && !error} aria-disabled={busy || (!history && !error)} onClick={() => void refresh()}>{t("Refresh payment history")}</button>{history && (history.hasMore || history.page > 0) && <button type="button" aria-disabled={busy || !history.hasMore || history.page >= 10000} onClick={() => void refresh(true)}>{t(history.page >= 10000 ? "Payment history page limit reached" : !history.hasMore ? "All online payments loaded" : "Load older payments")}</button>}</div>
      {history?.payments.length === 0 && <p>{t("No online payment request has been recorded for this child yet.")}</p>}
      {history?.payments.map(payment => <FamilyPaymentCard key={payment.id} payment={payment} busy={busy || uncertain || !!error} onContinue={continueCheckout} />)}
      {history?.payments.some(payment => payment.status === "PENDING") && <small>{t("Automatic checks are limited and pause when this tab is hidden. You can refresh verified status manually.")}</small>}
    </section>
  </section>;
}
