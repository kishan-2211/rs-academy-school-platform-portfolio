"use client";

import { useId } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { signedFeePaise } from "@/lib/family-fee-accounts";
import styles from "./refund-history.module.css";

export type FamilyRefund = { id: string; paymentId: string; receiptNumber: string; amount: number | string; currencyCode: string; postingOn: string; processedAtExact: string; processedOn: string; schoolTimeZone: string };

/** Only persisted completion rows from the exact authorized account; no provider actions. */
export function FamilyRefundHistory({ refunds }: { refunds?: readonly FamilyRefund[] }) {
  const t = useT("parent"), { locale } = useLanguage(), id = useId();
  if (refunds === undefined) return null;
  try {
    if (!Array.isArray(refunds)) throw new Error("Invalid retained refund list");
    const seen = new Set<string>();
    for (const refund of refunds) {
      if (!refund || seen.has(refund.id) || !/^[a-f0-9-]{36}$/i.test(refund.id) || !/^[a-f0-9-]{36}$/i.test(refund.paymentId)
        || !refund.receiptNumber || !/^[A-Z]{3}$/.test(refund.currencyCode) || signedFeePaise(refund.amount) <= 0
        || !/^\d{4}-\d{2}-\d{2}$/.test(refund.postingOn) || !/^\d{4}-\d{2}-\d{2}$/.test(refund.processedOn)
        || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(refund.processedAtExact) || !refund.schoolTimeZone) throw new Error("Invalid retained refund projection");
      seen.add(refund.id);
    }
  } catch {
    return <p className={styles.error} role="alert">{t("Recorded refund history could not be validated. Refresh the statement or contact the school.")}</p>;
  }
  if (refunds.length === 0) return null;
  return <section className={styles.root} aria-labelledby={`${id}-title`}>
    <h3 id={`${id}-title`}>{t("Recorded fee refunds")}</h3>
    <p>{t("These completed refunds restore the corresponding fee dues. Any waiver requires a separate school approval. Original receipts and payment amounts remain unchanged.")}</p>
    <ul>{refunds.map(refund => <li key={refund.id}>
      <header><strong>{refund.receiptNumber}</strong><b>{new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", { style: "currency", currency: refund.currencyCode }).format(signedFeePaise(refund.amount) / 100)}</b></header>
      <dl>
        <div><dt>{t("Recorded on the fee account")}</dt><dd><time dateTime={refund.postingOn}>{refund.postingOn}</time></dd></div>
        <div><dt>{t("Original refund processing day")}</dt><dd><time dateTime={refund.processedOn}>{refund.processedOn}</time> · {refund.schoolTimeZone}</dd></div>
        <div><dt>{t("Verified processing time (UTC)")}</dt><dd><time dateTime={refund.processedAtExact}>{refund.processedAtExact}</time></dd></div>
      </dl>
    </li>)}</ul>
  </section>;
}
