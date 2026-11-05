"use client";

import { useRef, useState, type FormEvent } from "react";
import { useT } from "@/components/i18n/language-provider";
import { LocalizedForm } from "@/components/i18n/localized-form";
import type { AttendanceLeaveEvidence } from "@/lib/attendance-leave";
import { leaveApi, leaveAttempt, type LeaveAttempt } from "@/lib/student-leave";

type Props = {
  schoolId: string;
  registerId: string;
  registerVersion: number;
  markId: string;
  markVersion: number;
  learnerName: string;
  evidence: AttendanceLeaveEvidence;
  disabled: boolean;
  onSaved: () => Promise<void>;
};

export function DraftLeaveReview(props: Props) {
  const t = useT("attendance");
  const [decision, setDecision] = useState("KEEP_RECORDED");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const attempt = useRef<LeaveAttempt | null>(null);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || props.disabled) return;
    setBusy(true);
    setError("");
    const payload = {
      schoolId: props.schoolId,
      markId: props.markId,
      leaveRequestId: props.evidence.requestId,
      leaveRequestVersion: props.evidence.requestVersion,
      expectedRegisterVersion: props.registerVersion,
      expectedMarkVersion: props.markVersion,
      decision,
      reason,
    };
    try {
      attempt.current = leaveAttempt(attempt.current, { registerId: props.registerId, ...payload });
      await leaveApi(`/api/v1/attendance/registers/${props.registerId}/leave-review`, {
        method: "POST",
        body: { ...payload, operationId: attempt.current.key },
      });
      await props.onSaved();
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "The review could not be saved. Refresh and try again.",
      );
    } finally {
      setBusy(false);
    }
  }

  if (props.evidence.review)
    return (
      <small role="status">
        {t("Reviewed:")}{" "}
        {props.evidence.review.decision === "KEEP_RECORDED"
          ? t("recorded observation kept")
          : t("approved leave applied")}
        {t(". Applies to the current leave and mark versions.")} </small>
    );
  return (
    <details className="attendance-leave-review">
      <summary>{t("Review this leave")}</summary>
      <LocalizedForm
        aria-label={t("Review leave for {name}", { name: props.learnerName })}
        onSubmit={(event) => void save(event)}
      >
        <fieldset disabled={busy || props.disabled}>
          <legend>{t("Confirm the recorded observation")}</legend>
          <label>
            {t("Review outcome")} <select value={decision} onChange={(event) => setDecision(event.target.value)}>
              <option value="KEEP_RECORDED">{t("Keep recorded attendance")}</option>
              {props.evidence.status === "APPROVED" && (
                <option value="APPLY_APPROVED">{t("Apply approved leave absence")}</option>
              )}
            </select>
          </label>
          <label>
            {t("Attendance review reason")} <textarea
              required
              minLength={3}
              maxLength={240}
              rows={3}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <label className="attendance-leave-source-choice">
            <input type="checkbox" required />
            <span>
              {t("I checked the saved attendance observation. Leave alone does not prove whether the child attended.")} </span>
          </label>
          <button type="submit">{busy ? t("Saving review…") : t("Save review")}</button>
        </fieldset>
        {props.disabled && <small>{t("Finish saving attendance edits before reviewing leave.")}</small>}
        {error && <p role="alert">{t(error)}</p>}
      </LocalizedForm>
    </details>
  );
}
