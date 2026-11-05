"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { ReceiptPdfActions } from "@/components/receipt-pdf-actions";
import { continueFamilyPayment, FamilyPaymentAccessError, familyPaymentCsrf, familyPaymentErrorCopy, familyPaymentStatusCopy, loadFamilyPayment, type FamilyPayment } from "@/lib/family-gateway-payments";
import styles from "./gateway-payment-panel.module.css";

/** Five visible-tab checks per mount. Hidden tabs, unmounts and terminal states do no polling. */
export function useBoundedPendingPaymentRefresh(pending: boolean, refresh: () => Promise<void>) {
  const polls = useRef(0), current = useRef(refresh);
  useEffect(() => { current.current = refresh; }, [refresh]);
  useEffect(() => {
    if (!pending) return;
    let timer: ReturnType<typeof setTimeout> | undefined, active = true;
    const schedule = () => {
      if (timer) clearTimeout(timer);
      if (!active || document.visibilityState !== "visible" || polls.current >= 5) return;
      timer = setTimeout(() => {
        timer = undefined;
        if (!active || document.visibilityState !== "visible" || polls.current >= 5) return;
        polls.current += 1;
        void current.current().finally(() => { if (active) schedule(); });
      }, 25_000);
    };
    document.addEventListener("visibilitychange", schedule); schedule();
    return () => { active = false; if (timer) clearTimeout(timer); document.removeEventListener("visibilitychange", schedule); };
  }, [pending]);
}

export function FamilyPaymentCard({ payment, busy = false, onContinue, showStatusLink = true }: {
  payment: FamilyPayment; busy?: boolean; onContinue: (payment: FamilyPayment) => Promise<void>; showStatusLink?: boolean;
}) {
  const t = useT("parent"), { locale } = useLanguage();
  const money = (amount: number) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", { style: "currency", currency: payment.currencyCode }).format(amount);
  const date = (value: string) => new Date(value).toLocaleString(locale === "hi" ? "hi-IN" : "en-IN", { dateStyle: "medium", timeStyle: "short" });
  return <article className={styles.payment} data-status={payment.status}>
    <div className={styles.summary}><strong>{money(payment.amount)}</strong><span className={styles.badge}>{t(familyPaymentStatusCopy[payment.status])}</span></div>
    <p>{payment.sessionName} · {t("Requested at {time}", { time: date(payment.createdAt) })}</p>
    {payment.status === "REVIEW_REQUIRED" && <p className={styles.warning}>{t("The school must review this confirmation. A closed financial day will not be changed; approved posting uses an eligible open date. Do not make another payment for these charges.")}</p>}
    {payment.status === "EXPIRED" && payment.reservationActive && <p className={styles.warning}>{t("An expired checkout is not proof that no money was received. These charges remain held until the school verifies or releases the reservation.")}</p>}
    {payment.status === "PENDING" && <p>{t("Closing the browser does not cancel provider verification. Payment is recorded only after server-side confirmation.")}</p>}
    <ul className={styles.breakdown}>{payment.selectedCharges.map(charge => <li key={charge.chargeId}><span>{charge.label}</span><strong>{money(charge.amount)}</strong></li>)}</ul>
    <div className={styles.actions}>
      {payment.canContinue && <button type="button" disabled={busy} onClick={() => void onContinue(payment)}>{t("Open secure checkout")}</button>}
      {showStatusLink && <a href={`/family/payments/${payment.id}`} rel="nofollow">{t("View verified payment status")}</a>}
    </div>
    {payment.canContinue && <small>{t("This opens the payment provider only after the server rechecks this exact payment. Card, CVV and UPI PIN details are never entered here.")}</small>}
    {!payment.initiatedByYou && payment.status === "READY" && <small>{t("Only the guardian who initiated this payment can open its private checkout link.")}</small>}
    {payment.receipt && <div className={styles.receipt}><strong>{t("Receipt {number}", { number: payment.receipt.receiptNumber })}</strong><p>{t("Posted on {date}", { date: payment.receipt.postingOn })}</p>
      <ReceiptPdfActions audience="family" learnerId={payment.learnerId} paymentId={payment.receipt.paymentId} receiptNumber={payment.receipt.receiptNumber} paymentStatus="POSTED" />
    </div>}
  </article>;
}

export function FamilyPaymentStatusView({ attemptId }: { attemptId: string }) {
  return <ScopedPaymentStatusView key={attemptId} attemptId={attemptId} />;
}

function ScopedPaymentStatusView({ attemptId }: { attemptId: string }) {
  const t = useT("parent");
  const [payment, setPayment] = useState<FamilyPayment | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const request = useRef<AbortController | null>(null);
  const fail = useCallback((reason: unknown) => {
    if (reason instanceof FamilyPaymentAccessError) setPayment(null);
    setError(familyPaymentErrorCopy(reason));
  }, []);
  useEffect(() => {
    const controller = new AbortController(); request.current = controller;
    void Promise.resolve().then(() => loadFamilyPayment(attemptId, controller.signal)).then(value => {
      if (!controller.signal.aborted) setPayment(value);
    }).catch(reason => { if (!controller.signal.aborted) fail(reason); }).finally(() => { if (request.current === controller) request.current = null; });
    return () => { controller.abort(); request.current?.abort(); };
  }, [attemptId, fail]);
  const refresh = useCallback(async () => {
    if (request.current) return;
    const controller = new AbortController(); request.current = controller; setBusy(true); setError("");
    try { const next = await loadFamilyPayment(attemptId, controller.signal); if (!controller.signal.aborted) setPayment(next); }
    catch (reason) { if (!controller.signal.aborted) fail(reason); }
    finally { if (request.current === controller) request.current = null; if (!controller.signal.aborted) setBusy(false); }
  }, [attemptId, fail]);
  useBoundedPendingPaymentRefresh(payment?.status === "PENDING" && !error, refresh);
  async function continueCheckout(value: FamilyPayment) {
    if (request.current || error) return;
    const controller = new AbortController(); request.current = controller; setBusy(true); setError("");
    try {
      const csrf = await familyPaymentCsrf(controller.signal);
      if (controller.signal.aborted) return;
      const url = await continueFamilyPayment(value, csrf, controller.signal);
      // Only this explicit action consumes a freshly authorized, validated URL.
      // Never redirect because of return-page query parameters or browser claims.
      if (!controller.signal.aborted) window.location.assign(url);
    } catch (reason) { if (!controller.signal.aborted) fail(reason); }
    finally { if (request.current === controller) request.current = null; if (!controller.signal.aborted) setBusy(false); }
  }
  return <main className={`${styles.root} ${styles.statusPage}`} aria-busy={busy || (!payment && !error)}>
    <header><span>{t("Verified family account")}</span><h1>{t("Online fee payment status")}</h1><p>{t("Returning from a provider page does not mark fees paid. This view reads the school’s verified server record.")}</p></header>
    {error && <p role="alert" className={styles.error}>{t(error)}</p>}
    {!payment && !error && <p role="status">{t("Checking verified payment status…")}</p>}
    {payment && <FamilyPaymentCard payment={payment} busy={busy || !!error} onContinue={continueCheckout} showStatusLink={false} />}
    {/* Busy uses aria-disabled; the request ref fences duplicate keyboard/click reads. */}
    <div className={styles.actions}><button type="button" disabled={!busy && !payment && !error} aria-disabled={busy || (!payment && !error)} onClick={() => void refresh()}>{t("Refresh verified status")}</button><Link href="/family#family-fees" prefetch={false}>{t("Back to my child’s fees")}</Link></div>
    {payment?.status === "PENDING" && <small>{t("Automatic checks are limited and pause when this tab is hidden. You can refresh verified status manually.")}</small>}
  </main>;
}
