"use client";

import { useLanguage, useT } from "@/components/i18n/language-provider";
import type { JourneyChoice, TransportProblemCode } from "@/lib/transport-non-travel";
import styles from "./transport-plans.module.css";

const problems: Record<TransportProblemCode, string> = {
  session: "Your session has ended. Sign in again.",
  access: "This transport record is unavailable through your current school access.",
  changed: "The plan or policy changed. Refresh before making another change.",
  closed: "Changes are closed. Contact the school for urgent arrangements.",
  disabled: "The school has not enabled parent transport plans for this campus.",
  invalid: "Check the selected journeys, dates and required policy fields.",
  csrf: "The secure form token is unavailable. Refresh and try again.",
  unavailable: "Transport plans are temporarily unavailable. Try again or contact the school.",
  response: "The transport response could not be verified. Refresh or contact the school.",
};
export function TransportPlanError({ code }: { code: TransportProblemCode }) {
  const t = useT("transportPlans");
  return <p role="alert" className={styles.error}>{t(problems[code])}</p>;
}
export function transportJourneyLabel(kind: JourneyChoice): string {
  return { PICKUP: "Pickup journey", DROP: "Drop journey", FULL_DAY: "Full day" }[kind];
}
export function useTransportDate() {
  const { locale } = useLanguage();
  return (value: string, timeZone: string) => new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", {
    dateStyle: "medium", timeStyle: "short", timeZone,
  }).format(new Date(value));
}
export function ParentTransportPlanStatus({ notTravelling, updatedAt, assignmentChanged }: {
  notTravelling?: boolean; updatedAt?: string | null; assignmentChanged?: boolean;
}) {
  const t = useT("transportPlans");
  const { locale } = useLanguage();
  if (!updatedAt) return null;
  const parsed = new Date(updatedAt);
  if (!Number.isFinite(parsed.valueOf())) return null;
  return <div className={styles.rosterPlan}>
    <strong>{t(assignmentChanged ? "The parent plan refers to another assignment. Review the current rider record."
      : notTravelling ? "Recorded parent plan: not travelling" : "Recorded parent opt-out was cancelled")}</strong>
    <time dateTime={updatedAt}>{new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", {
      year: "numeric", month: "short", day: "numeric", hour: "numeric", minute: "2-digit", timeZoneName: "short",
    }).format(parsed)}</time>
    <small>{t("Parent intent only — verify actual boarding and absence separately.")}</small>
  </div>;
}
