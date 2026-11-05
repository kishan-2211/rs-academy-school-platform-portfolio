"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { ArrowUpRight, Check, Clock, Shield } from "@/components/icons";
import { SignOutButton } from "@/components/sign-out-button";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import styles from "./security-requirement.module.css";

type Session = {
  id: string;
  deviceName: string;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  current: boolean;
  mfaVerified: boolean;
};

type MfaStatus = {
  enabled: boolean;
  factors: Array<{ id: string; type: string; label: string; verifiedAt: string }>;
  unusedRecoveryCodes: number;
  sessionVerified: boolean;
};

type Enrollment = {
  factorId: string;
  secret: string;
  provisioningUri: string;
  expiresAt: string;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

function cookie(name: string) {
  const value = document.cookie.split("; ").find((entry) => entry.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : "";
}

function relativeTime(value: string, locale: string) {
  const milliseconds = new Date(value).getTime() - Date.now();
  const minutes = Math.round(milliseconds / 60_000);
  if (Math.abs(minutes) < 60) return new Intl.RelativeTimeFormat(locale, { numeric: "auto" }).format(minutes, "minute");
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 48) return new Intl.RelativeTimeFormat(locale, { numeric: "auto" }).format(hours, "hour");
  return new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "short", year: "numeric" }).format(new Date(value));
}

export function SecurityCentre({ mfaRequired = false }: { mfaRequired?: boolean } = {}) {
  const t = useT("security");
  const { locale } = useLanguage();
  const [sessions, setSessions] = useState<Session[]>([]);
  const [mfa, setMfa] = useState<MfaStatus | null>(null);
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [recoverySaved, setRecoverySaved] = useState(false);
  const recoveryTarget = useRef<HTMLElement | null>(null);
  const [contextRequiresMfa, setContextRequiresMfa] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const mustVerify = mfaRequired || contextRequiresMfa;
  const sessionVerified = mfa?.sessionVerified === true;

  const fetchSecurityData = useCallback(async () => {
    const current = await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
    if (!current.ok) throw new Error("session");
    const [sessionResponse, mfaResponse, contextResponse] = await Promise.all([
      fetch(`${apiUrl}/api/v1/auth/sessions`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/auth/mfa`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/context`, { credentials: "include", cache: "no-store" }),
    ]);
    if (!sessionResponse.ok || !mfaResponse.ok || !contextResponse.ok) throw new Error("security data");
    const context = await contextResponse.json() as { mfaRequired?: boolean };
    if (typeof context.mfaRequired !== "boolean") throw new Error("security context incomplete");
    return {
      sessions: (await sessionResponse.json()) as Session[],
      mfa: (await mfaResponse.json()) as MfaStatus,
      mfaRequired: context.mfaRequired,
    };
  }, []);

  const load = useCallback(async () => {
    try {
      const data = await fetchSecurityData();
      setError("");
      setSessions(data.sessions);
      setMfa(data.mfa);
      setContextRequiresMfa(data.mfaRequired);
    } catch {
      setError("Security information is unavailable. Your session may have expired; sign in again before making changes.");
    } finally {
      setLoading(false);
    }
  }, [fetchSecurityData]);

  useEffect(() => {
    let cancelled = false;
    fetchSecurityData()
      .then((data) => {
        if (cancelled) return;
        setError("");
        setSessions(data.sessions);
        setMfa(data.mfa);
        setContextRequiresMfa(data.mfaRequired);
      })
      .catch(() => {
        if (!cancelled) setError("Security information is unavailable. Your session may have expired; sign in again before making changes.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [fetchSecurityData]);

  useEffect(() => {
    if (recoveryCodes.length) {
      recoveryTarget.current?.scrollIntoView({ block: "start", behavior: "instant" });
      recoveryTarget.current?.focus({ preventScroll: true });
    }
  }, [recoveryCodes]);

  async function securedRequest(path: string, init: RequestInit) {
    let csrf = cookie("educore_csrf");
    if (!csrf) {
      await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
      csrf = cookie("educore_csrf");
    }
    return fetch(`${apiUrl}${path}`, {
      ...init,
      credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf, ...init.headers },
    });
  }

  async function beginEnrollment() {
    setBusy("enroll"); setError(""); setMessage("");
    try {
      const response = await securedRequest("/api/v1/auth/mfa/totp/enrollments", {
        method: "POST",
        body: JSON.stringify({ label: "Primary authenticator" }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null) as { code?: string } | null;
        if (problem?.code === "MFA_VERIFICATION_REQUIRED") {
          setError("Sign in with an existing authenticator or recovery code before changing MFA setup.");
          await load();
          return;
        }
        throw new Error("enrollment");
      }
      setEnrollment((await response.json()) as Enrollment);
    } catch {
      setError("Authenticator setup could not start. Ask the platform operator to confirm the MFA encryption key.");
    } finally { setBusy(""); }
  }

  async function verifyEnrollment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!enrollment) return;
    const form = new FormData(event.currentTarget);
    setBusy("verify"); setError("");
    try {
      const response = await securedRequest(`/api/v1/auth/mfa/totp/enrollments/${enrollment.factorId}/verify`, {
        method: "POST",
        body: JSON.stringify({ code: String(form.get("code") ?? "") }),
      });
      if (!response.ok) throw new Error("verification");
      const result = (await response.json()) as { recoveryCodes: string[] };
      setRecoveryCodes(result.recoveryCodes);
      setRecoverySaved(false);
      setEnrollment(null);
      setMessage("Authenticator verified. Save every recovery code now—they will not be shown again.");
      await load();
    } catch {
      setError("The code was not accepted. Check the device time and enter the newest six-digit code.");
    } finally { setBusy(""); }
  }

  async function revokeSession(sessionId: string) {
    setBusy(sessionId); setError(""); setMessage("");
    try {
      const response = await securedRequest(`/api/v1/auth/sessions/${sessionId}`, { method: "DELETE" });
      if (!response.ok) throw new Error("revoke");
      setSessions((items) => items.filter((item) => item.id !== sessionId));
      setMessage("The selected session has been revoked.");
    } catch {
      setError("That session could not be revoked. Refresh and try again.");
    } finally { setBusy(""); }
  }

  function downloadRecoveryCodes() {
    const blob = new Blob([
      `${t("EduCore recovery codes")}\n${t("Created")} ${new Date().toISOString()}\n\n${recoveryCodes.join("\n")}\n\n${t("Each code works once. Store offline.")}`,
    ], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url; link.download = "educore-recovery-codes.txt"; link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <section className={`security-centre ${styles.centre}`}>
      <header className="module-page__hero security-centre__hero">
        <div><span>{t("Account security")}</span><h1>{t("Protect every doorway.")}</h1><p>{t("Strengthen sign-in, keep recovery options offline, and close devices you no longer recognize.")}</p></div>
        <div className={`security-score${mfa?.enabled ? " security-score--ready" : ""}`}>
          <Shield /><span><strong>{t(mfa?.enabled ? "Strong" : "Action needed")}</strong><small>{t("Identity posture")}</small></span>
        </div>
      </header>

      {loading && <div className="security-notice" role="status"><Clock /> {t("Loading current security posture…")}</div>}
      {error && <div className="security-notice security-notice--error" role="alert">{t(error)}</div>}
      {message && <div className="security-notice security-notice--success" role="status"><Check /> {t(message)}</div>}
      {!loading && mfa && (mustVerify || (mfa.enabled && !sessionVerified)) && <MfaRequirementNotice
        required={mustVerify} enabled={mfa.enabled} recoveryCodesPresent={recoveryCodes.length > 0} recoverySaved={recoverySaved}
        setRecoverySaved={setRecoverySaved} />}

      {!loading && mfa && (
        <div className="security-grid">
          <article className="security-card security-card--mfa">
            <header><div><span>{t("Two-step verification")}</span><h2>{t("Authenticator app")}</h2></div><em className={mfa.enabled ? "is-ready" : ""}>{t(mfa.enabled ? "Protected" : "Not configured")}</em></header>
            <p>{t("A password plus a rotating code protects staff access even if one credential is exposed.")}</p>
            {mfa.enabled ? (
              <div className="security-factor-list">
                {mfa.factors.map((factor) => <div key={factor.id}><span><Check /></span><div><strong>{factor.label}</strong><small>{t("Verified")} {relativeTime(factor.verifiedAt, locale)} · TOTP</small></div></div>)}
                <p>{t("{count} unused recovery codes remain.", { count: mfa.unusedRecoveryCodes })}</p>
              </div>
            ) : !enrollment ? (
              <button className="button button--dark" type="button" onClick={beginEnrollment} disabled={busy === "enroll"}>{t(busy === "enroll" ? "Preparing…" : "Set up authenticator")} <ArrowUpRight /></button>
            ) : (
              <form className="mfa-enrollment" onSubmit={verifyEnrollment}>
                <div><span>1</span><p>{t("Open an authenticator app and add an account with this setup key.")}</p></div>
                <code>{enrollment.secret}</code>
                <a href={enrollment.provisioningUri}>{t("Open in authenticator app")} <ArrowUpRight /></a>
                <label><span>{t("2 · Enter the newest six-digit code")}</span><input name="code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required /></label>
                <button className="button button--coral" type="submit" disabled={busy === "verify"}>{t(busy === "verify" ? "Verifying…" : "Verify authenticator")}</button>
              </form>
            )}
          </article>

          <article ref={recoveryTarget} tabIndex={recoveryCodes.length ? -1 : undefined} className={`security-card ${styles.recovery}`} aria-label={t("Offline recovery codes")}>
            <header><div><span>{t("Recovery")}</span><h2>{t("Offline recovery codes")}</h2></div><em>{mfa.unusedRecoveryCodes}/10</em></header>
            {recoveryCodes.length ? (
              <div className="recovery-codes">
                <p>{t("Save these now. Each code works once and cannot be displayed again.")}</p>
                <div>{recoveryCodes.map((code) => <code key={code}>{code}</code>)}</div>
                <button type="button" onClick={downloadRecoveryCodes}>{t("Download text file")} <ArrowUpRight /></button>
              </div>
            ) : (
              <div className="security-empty"><Shield /><p>{t("Recovery codes are generated only after authenticator verification. Keep them away from the device that holds your authenticator.")}</p></div>
            )}
          </article>
        </div>
      )}

      {!loading && sessions.length > 0 && (
        <article className="security-card security-card--sessions">
          <header><div><span>{t("Unrevoked sessions")}</span><h2>{t("Device sessions")}</h2></div><em>{t("{count} not revoked", { count: sessions.length })}</em></header>
          <div className="session-list">
            {sessions.map((session) => (
              <div key={session.id}>
                <span className="session-device"><i /><i /></span>
                <div><strong>{session.deviceName} {session.current && <em>{t("Current")}</em>}</strong><small>{t("Last used")} {relativeTime(session.lastSeenAt, locale)} · {t("Expires")} {relativeTime(session.expiresAt, locale)} {session.mfaVerified ? `· ${t("MFA verified")}` : ""}</small></div>
                {session.current ? <span className="session-current"><Check /> {t("This device")}</span> : <button type="button" disabled={busy === session.id} onClick={() => revokeSession(session.id)}>{t(busy === session.id ? "Revoking…" : "Revoke")}</button>}
              </div>
            ))}
          </div>
        </article>
      )}
    </section>
  );
}

export function MfaRequirementNotice({ required, enabled, recoveryCodesPresent, recoverySaved, setRecoverySaved }: {
  required: boolean; enabled: boolean; recoveryCodesPresent: boolean; recoverySaved: boolean; setRecoverySaved: (saved: boolean) => void;
}) {
  const t = useT("security");
  return <section className={styles.requirement} role="status" aria-label={t(required ? "Two-step verification required" : "Finish authenticator setup")}>
    <h2>{t(enabled ? "Complete a fresh two-step sign-in" : "Set up two-step verification")}</h2>
    <p>{t(enabled
      ? required ? "This session has not verified a second factor. School records remain locked. Save any new recovery codes, then sign out and sign in with an authenticator or recovery code."
        : "Authenticator enabled. Save any new recovery codes, then sign out and sign in with an authenticator or recovery code before changing MFA setup."
      : "Your selected school authority requires two-step verification. School records remain locked while you set up an authenticator below.")}</p>
    {enabled && recoveryCodesPresent && <label className={styles.acknowledgement}>
      <input type="checkbox" checked={recoverySaved} onChange={(event) => setRecoverySaved(event.currentTarget.checked)} />
      {t("I have stored my recovery codes safely offline.")}
    </label>}
    {enabled && (!recoveryCodesPresent || recoverySaved) && <SignOutButton className="button button--dark" />}
  </section>;
}
