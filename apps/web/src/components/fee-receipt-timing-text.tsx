"use client";

import { useLanguage, useT } from "@/components/i18n/language-provider";
import { receivedMoneyLabel, type FeeReceiptTiming } from "@/lib/fee-receipt-timing";

export function FeeReceiptTimingText({ postingOn, timing }: { postingOn: string; timing?: FeeReceiptTiming }) {
  const t = useT("fees");
  const { locale } = useLanguage();
  return <>
    <small>{t("Accounting posting day: {date}", { date: postingOn })}</small>
    {timing && <small>{t("Money received: {date}", { date: receivedMoneyLabel(timing, locale) })}</small>}
    {timing?.receivedTimePrecision === "DATE_ONLY" && <small>{t("Exact received time was not recorded.")}</small>}
  </>;
}
