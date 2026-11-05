"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Check, Shield, Sparkles, User } from "@/components/icons";
import { useCodeLabel, useT } from "@/components/i18n/language-provider";
import { LocalizedForm } from "@/components/i18n/localized-form";
import styles from "./student-portal-access-card.module.css";

type ApiProblem = { detail?: string };
type PortalAccess = {
  learnerId: string;
  learnerName: string;
  account: {
    loginIdentifier: string;
    status: string;
    activatedAt: string;
    suspendedAt: string | null;
    suspensionReason: string | null;
    endedAt: string | null;
    endingReason: string | null;
    rowVersion: number;
  } | null;
  requests: Array<{
    id: string;
    loginIdentifier: string;
    privacyNoticeVersion: string;
    purposeSummary: string;
    status: string;
    expiresAt: string;
    createdAt: string;
    decidedAt: string | null;
    decisionReason: string | null;
    withdrawnAt: string | null;
    withdrawalReason: string | null;
    rowVersion: number;
  }>;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

function cookie(name: string) {
  const value = document.cookie.split("; ").find((entry) => entry.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : "";
}

function displayCode(value: string) {
  return value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function dateTimeLabel(value: string | null) {
  if (!value) return "Not recorded";
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value));
}

async function problemMessage(response: Response, fallback: string) {
  try {
    const problem = (await response.json()) as ApiProblem;
    return problem.detail || fallback;
  } catch {
    return fallback;
  }
}

export function StudentPortalAccessCard({ learnerId }: { learnerId: string }) {
  const t = useT("parent");
  const labelCode = useCodeLabel(displayCode);
  const [access, setAccess] = useState<PortalAccess | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [decisionReason, setDecisionReason] = useState("");
  const [withdrawalReason, setWithdrawalReason] = useState("");
  const [currentTime, setCurrentTime] = useState(() => Date.now());

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    const response = await fetch(
      `${apiUrl}/api/v1/family/children/${learnerId}/student-portal-access`,
      { credentials: "include" },
    );
    if (!response.ok) {
      setAccess(null);
      setError(await problemMessage(response, "Student portal access details are unavailable."));
    } else {
      setAccess((await response.json()) as PortalAccess);
    }
    setLoading(false);
  }, [learnerId]);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(() => {
      if (cancelled) return;
      setPassword("");
      setConfirmPassword("");
      setDecisionReason("");
      setWithdrawalReason("");
      setNotice("");
      void load();
    });
    return () => {
      cancelled = true;
    };
  }, [load]);

  useEffect(() => {
    const timer = window.setInterval(() => setCurrentTime(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  const pending = useMemo(
    () => access?.requests.find((request) => request.status === "PENDING") ?? null,
    [access],
  );
  const pendingExpired = pending ? new Date(pending.expiresAt).getTime() <= currentTime : false;

  async function securedPost(path: string, body: unknown) {
    let csrf = cookie("educore_csrf");
    if (!csrf) {
      await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
      csrf = cookie("educore_csrf");
    }
    return fetch(`${apiUrl}${path}`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf },
      body: JSON.stringify(body),
    });
  }

  async function decide(decision: "APPROVE" | "DECLINE") {
    if (!pending) return;
    setError("");
    setNotice("");
    if (decision === "APPROVE" && password !== confirmPassword) {
      setError("The password confirmation does not match.");
      return;
    }
    setBusy(true);
    try {
      const response = await securedPost(
        `/api/v1/family/children/${learnerId}/student-portal-access/consent-requests/${pending.id}/decision`,
        {
          decision,
          password: decision === "APPROVE" ? password : null,
          expectedVersion: pending.rowVersion,
          reason: decisionReason,
        },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The student access decision could not be saved."));
      }
      setPassword("");
      setConfirmPassword("");
      setDecisionReason("");
      await load();
      setNotice(
        decision === "APPROVE"
          ? "Student access approved and activated. Share the school sign-in ID safely."
          : "The request was declined and retained in the audit history.",
      );
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The student access decision could not be saved.");
    } finally {
      setBusy(false);
    }
  }

  async function withdraw(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!access?.account) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await securedPost(
        `/api/v1/family/children/${learnerId}/student-portal-access/withdrawal`,
        { expectedVersion: access.account.rowVersion, reason: withdrawalReason },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "Student access could not be withdrawn."));
      }
      setWithdrawalReason("");
      await load();
      setNotice("Student access withdrawn. The account can no longer sign in, and the audit history remains intact.");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Student access could not be withdrawn.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={styles.card} id="family-student-access" aria-labelledby="family-student-access-title">
      <header>
        <div>
          <span><Sparkles /> {t("Guardian-approved access")}</span>
          <h2 id="family-student-access-title">{t("Student's own portal")}</h2>
        </div>
        {access?.account ? <em data-state={access.account.status}>{labelCode(access.account.status)}</em> : null}
      </header>
      <div className={styles.privacy}>
        <Shield />
        <p>
          {t("Student access is separate from your family account. It shows only this learner's own timetable, attendance, work, published results, and student messages—never family fees, guardian details, transport operations, or another learner.")} </p>
      </div>

      {loading ? <div className={styles.state} aria-live="polite">{t("Checking student portal access…")}</div> : null}
      {error ? <div className={styles.error} role="alert">{t(error)}</div> : null}
      {notice ? <div className={styles.notice} role="status"><Check /> {t(notice)}</div> : null}

      {!loading && access?.account ? (
        <article className={styles.account}>
          <div>
            <span><User /></span>
            <div><small>{t("School sign-in ID")}</small><strong>{access.account.loginIdentifier}</strong><p>{t("Activated {date}", { date: dateTimeLabel(access.account.activatedAt) })}</p></div>
          </div>
          {access.account.suspensionReason ? <p className={styles.reason}>{t("School suspension reason:")} {access.account.suspensionReason}</p> : null}
          {access.account.endingReason ? <p className={styles.reason}>{t("Access ended:")} {access.account.endingReason}</p> : null}
        </article>
      ) : null}

      {!loading && pending && !pendingExpired ? (
        <LocalizedForm className={styles.decision} onSubmit={(event) => { event.preventDefault(); void decide("APPROVE"); }}>
          <header><div><span>{t("Adult decision required")}</span><h3>{t("Review access for {name}", { name: access?.learnerName ?? "" })}</h3></div><small>{t("Expires {date}", { date: dateTimeLabel(pending.expiresAt) })}</small></header>
          <dl>
            <div><dt>{t("Proposed sign-in ID")}</dt><dd>{pending.loginIdentifier}</dd></div>
            <div><dt>{t("Privacy notice")}</dt><dd>{pending.privacyNoticeVersion}</dd></div>
          </dl>
          <div className={styles.purpose}><strong>{t("Why access is being requested")}</strong><p>{pending.purposeSummary}</p></div>
          <div className={styles.passwords}>
            <label><span>{t("Create a student password")}</span><input type="password" autoComplete="new-password" minLength={12} maxLength={200} required value={password} disabled={busy} onChange={(event) => setPassword(event.target.value)} /><small>{t("Use at least 12 characters. Do not reuse your family-portal password.")}</small></label>
            <label><span>{t("Confirm the password")}</span><input type="password" autoComplete="new-password" minLength={12} maxLength={200} required value={confirmPassword} disabled={busy} onChange={(event) => setConfirmPassword(event.target.value)} /></label>
          </div>
          <label className={styles.reasonField}><span>{t("Decision reason")}</span><textarea minLength={3} maxLength={500} required value={decisionReason} disabled={busy} onChange={(event) => setDecisionReason(event.target.value)} placeholder={t("For example: I reviewed the stated purpose and approve my child's own school access.")} /></label>
          <footer>
            <button type="button" className={styles.decline} disabled={busy || decisionReason.trim().length < 3} onClick={() => void decide("DECLINE")}>{t("Decline request")}</button>
            <button type="submit" className={styles.approve} disabled={busy || password.length < 12 || password !== confirmPassword || decisionReason.trim().length < 3}>{busy ? t("Saving decision…") : t("Approve and activate")}</button>
          </footer>
        </LocalizedForm>
      ) : null}

      {!loading && pending && pendingExpired ? <div className={styles.state}>{t("This consent request expired on {date}. Ask the school to prepare a new request.", { date: dateTimeLabel(pending.expiresAt) })}</div> : null}

      {!loading && !pending && !access?.account ? <div className={styles.state}>{t("The school has not prepared a student portal request for this learner. Nothing needs your approval.")}</div> : null}

      {!loading && access?.account && ["ACTIVE", "SUSPENDED"].includes(access.account.status) ? (
        <details className={styles.withdrawal}>
          <summary>{t("Withdraw student portal access")}</summary>
          <LocalizedForm onSubmit={(event) => void withdraw(event)}>
            <p>{t("Withdrawal ends sign-in access but does not erase school records, published results, or the audit trail.")}</p>
            <label><span>{t("Reason for withdrawal")}</span><textarea minLength={3} maxLength={500} required value={withdrawalReason} disabled={busy} onChange={(event) => setWithdrawalReason(event.target.value)} /></label>
            <button type="submit" disabled={busy || withdrawalReason.trim().length < 3}>{t("Withdraw access")}</button>
          </LocalizedForm>
        </details>
      ) : null}

      {!loading && access?.requests.length ? (
        <details className={styles.history}>
          <summary>{t("Consent history ({count})", { count: access.requests.length })}</summary>
          <ol>{access.requests.map((request) => <li key={request.id}><div><strong>{request.loginIdentifier}</strong><small>{t("Created {date} · privacy notice {version}", { date: dateTimeLabel(request.createdAt), version: request.privacyNoticeVersion })}</small></div><em data-state={request.status}>{labelCode(request.status)}</em></li>)}</ol>
        </details>
      ) : null}
    </section>
  );
}
