export type FeeReceiptTiming = {
  originalReceivedOn: string;
  actualReceivedAt: string | null;
  receivedTimePrecision: "INSTANT" | "DATE_ONLY";
  schoolTimeZone: string | null;
};

/** Recording/issuance time is never a fallback for when the school received money. */
export function receivedMoneyLabel(timing: FeeReceiptTiming, locale: "en" | "hi") {
  if (timing.receivedTimePrecision === "INSTANT" && timing.actualReceivedAt) {
    const instant = new Date(timing.actualReceivedAt);
    if (Number.isFinite(instant.getTime())) {
      if (timing.schoolTimeZone) {
        try {
          return new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", {
            timeZone: timing.schoolTimeZone, dateStyle: "medium", timeStyle: "short",
          }).format(instant) + ` (${timing.schoolTimeZone})`;
        } catch { /* Preserve the known instant in UTC, never this device's zone. */ }
      }
      return instant.toISOString();
    }
  }
  return timing.originalReceivedOn;
}
