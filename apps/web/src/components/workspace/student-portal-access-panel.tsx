"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Check, Clock, Shield, Sparkles, User } from "@/components/icons";
import styles from "./student-portal-access-panel.module.css";

type ApiProblem = { detail?: string };
type Snapshot = {
  learnerId: string;
  legalName: string;
  birthDate: string;
  learnerStatus: string;
  learnerVersion: number;
  canManage: boolean;
  guardians: Array<{
    guardianId: string;
    displayName: string;
    relationshipCode: string;
    guardianStatus: string;
    relationshipPortalStatus: string;
    linkedPortalAccount: boolean;
    relationshipVersion: number;
  }>;
  account: {
    id: string;
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
    guardianId: string;
    guardianName: string;
    supersedesRequestId: string | null;
    loginIdentifier: string;
    privacyNoticeVersion: string;
    purposeSummary: string;
    adultVerificationMethod: string;
    adultVerificationReference: string;
    adultVerifiedAt: string;
    status: string;
    expiresAt: string;
    createdAt: string;
    guardianDecidedAt: string | null;
    guardianDecisionReason: string | null;
    revokedAt: string | null;
    revokedReason: string | null;
    withdrawnAt: string | null;
    withdrawalReason: string | null;
    rowVersion: number;
  }>;
  history: Array<{
    id: string;
    eventType: string;
    previousStatus: string | null;
    newStatus: string;
    consentRequestId: string | null;
    reason: string;
    actorName: string;
    occurredAt: string;
  }>;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

function cookie(name: string) {
  const value = document.cookie.split("; ").find((entry) => entry.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : "";
}

function displayCode(value: string | null) {
  if (!value) return "None";
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

export function StudentPortalAccessPanel({ learnerId }: { learnerId: string }) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [prepareOpen, setPrepareOpen] = useState(false);
  const [guardianId, setGuardianId] = useState("");
  const [loginIdentifier, setLoginIdentifier] = useState("");
  const [privacyNoticeVersion, setPrivacyNoticeVersion] = useState("");
  const [purposeSummary, setPurposeSummary] = useState("");
  const [verificationMethod, setVerificationMethod] = useState("SCHOOL_RECORD");
  const [verificationReference, setVerificationReference] = useState("");
  const [preparationReason, setPreparationReason] = useState("");
  const [actionReason, setActionReason] = useState("");
  const [currentTime, setCurrentTime] = useState(() => Date.now());

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    const response = await fetch(`${apiUrl}/api/v1/students/${learnerId}/portal-access`, {
      credentials: "include",
    });
    if (!response.ok) {
      setSnapshot(null);
      setError(await problemMessage(response, "Student portal access is unavailable."));
    } else {
      const next = (await response.json()) as Snapshot;
      setSnapshot(next);
      setGuardianId((current) => current || next.guardians.find((guardian) => guardian.linkedPortalAccount)?.guardianId || "");
    }
    setLoading(false);
  }, [learnerId]);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(() => {
      if (cancelled) return;
      setSnapshot(null);
      setGuardianId("");
      setPrepareOpen(false);
      setActionReason("");
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

  const selectedGuardian = snapshot?.guardians.find((guardian) => guardian.guardianId === guardianId) ?? null;
  const pendingRequest = useMemo(
    () => snapshot?.requests.find(
      (request) => request.status === "PENDING" && new Date(request.expiresAt).getTime() > currentTime,
    ) ?? null,
    [currentTime, snapshot],
  );
  const expiredPendingRequest = useMemo(
    () => snapshot?.requests.find(
      (request) => request.status === "PENDING" && new Date(request.expiresAt).getTime() <= currentTime,
    ) ?? null,
    [currentTime, snapshot],
  );
  const canPrepare = Boolean(snapshot && (!snapshot.account || snapshot.account.status === "ENDED") && !pendingRequest);

  async function post(path: string, body: unknown) {
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

  async function prepare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!snapshot || !selectedGuardian) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await post(`/api/v1/students/${learnerId}/portal-access/consent-requests`, {
        guardianId,
        loginIdentifier,
        privacyNoticeVersion,
        purposeSummary,
        adultVerificationMethod: verificationMethod,
        adultVerificationReference: verificationReference,
        expectedLearnerVersion: snapshot.learnerVersion,
        expectedRelationshipVersion: selectedGuardian.relationshipVersion,
        reason: preparationReason,
      });
      if (!response.ok) throw new Error(await problemMessage(response, "The consent request could not be prepared."));
      setPrepareOpen(false);
      setLoginIdentifier("");
      setPrivacyNoticeVersion("");
      setPurposeSummary("");
      setVerificationReference("");
      setPreparationReason("");
      await load();
      setNotice("Consent request prepared. The linked guardian must review and decide it in the family portal.");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The consent request could not be prepared.");
    } finally {
      setBusy(false);
    }
  }

  async function lifecycle(action: "revocation" | "suspension" | "resumption" | "ending") {
    if (!snapshot || actionReason.trim().length < 3) return;
    const requestId = pendingRequest?.id;
    const paths = {
      revocation: requestId ? `/api/v1/students/${learnerId}/portal-access/consent-requests/${requestId}/revocation` : "",
      suspension: `/api/v1/students/${learnerId}/portal-access/suspension`,
      resumption: `/api/v1/students/${learnerId}/portal-access/resumption`,
      ending: `/api/v1/students/${learnerId}/portal-access/ending`,
    };
    const expectedVersion = action === "revocation" ? pendingRequest?.rowVersion : snapshot.account?.rowVersion;
    if (!paths[action] || expectedVersion == null) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await post(paths[action], { expectedVersion, reason: actionReason });
      if (!response.ok) throw new Error(await problemMessage(response, `Student portal ${action} could not be completed.`));
      setActionReason("");
      await load();
      setNotice(`Student portal ${action} completed with an audit event.`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : `Student portal ${action} could not be completed.`);
    } finally {
      setBusy(false);
    }
  }

  const referenceHint = verificationMethod === "SCHOOL_RECORD"
    ? "school-record:reference"
    : verificationMethod === "AUTHORIZED_TOKEN"
      ? "authorized-token:reference"
      : "review-case:reference";

  return (
    <section className={styles.panel} aria-labelledby="student-portal-access-title">
      <header>
        <span><Sparkles /><strong id="student-portal-access-title">Student portal lifecycle</strong></span>
        {snapshot?.account ? <em data-state={snapshot.account.status}>{displayCode(snapshot.account.status)}</em> : <small>No learner account</small>}
      </header>
      <div className={styles.guardrail}><Shield /><p>Access is created only after a linked adult guardian approves a time-limited request. The resulting student role is own-record-only and cannot read guardian, fee, staff, transport-operation, health, safeguarding, or other learner records.</p></div>
      {loading ? <div className={styles.state} aria-live="polite">Loading consent and account history…</div> : null}
      {error ? <div className={styles.error} role="alert">{error}</div> : null}
      {notice ? <div className={styles.notice} role="status"><Check /> {notice}</div> : null}

      {!loading && snapshot ? (
        <>
          <div className={styles.summary}>
            <article><span><User /></span><div><small>Learner</small><strong>{snapshot.legalName}</strong><p>{snapshot.learnerStatus} · version {snapshot.learnerVersion}</p></div></article>
            <article><span><Shield /></span><div><small>Guardian evidence</small><strong>{snapshot.guardians.filter((guardian) => guardian.linkedPortalAccount).length} linked accounts</strong><p>{snapshot.guardians.length} relationships reviewed</p></div></article>
            <article><span><Clock /></span><div><small>Consent</small><strong>{pendingRequest ? "Decision pending" : "No pending request"}</strong><p>{pendingRequest ? `Expires ${dateTimeLabel(pendingRequest.expiresAt)}` : `${snapshot.requests.length} historical requests`}</p></div></article>
          </div>

          {snapshot.account ? (
            <div className={styles.account}>
              <div><small>School sign-in ID</small><strong>{snapshot.account.loginIdentifier}</strong><span>Activated {dateTimeLabel(snapshot.account.activatedAt)} · version {snapshot.account.rowVersion}</span></div>
              {snapshot.account.suspensionReason ? <p>Suspension reason: {snapshot.account.suspensionReason}</p> : null}
              {snapshot.account.endingReason ? <p>Ending reason: {snapshot.account.endingReason}</p> : null}
            </div>
          ) : null}

          {pendingRequest ? (
            <article className={styles.pending}>
              <header><div><span>Awaiting guardian</span><h3>{pendingRequest.guardianName}</h3></div><em>{pendingRequest.loginIdentifier}</em></header>
              <p>{pendingRequest.purposeSummary}</p>
              <dl><div><dt>Privacy notice</dt><dd>{pendingRequest.privacyNoticeVersion}</dd></div><div><dt>Adult evidence</dt><dd>{displayCode(pendingRequest.adultVerificationMethod)}</dd></div><div><dt>Prepared</dt><dd>{dateTimeLabel(pendingRequest.createdAt)}</dd></div><div><dt>Expires</dt><dd>{dateTimeLabel(pendingRequest.expiresAt)}</dd></div></dl>
            </article>
          ) : null}

          {expiredPendingRequest ? <div className={styles.state}>The previous pending request expired {dateTimeLabel(expiredPendingRequest.expiresAt)}. Preparing a new request will close it as expired first.</div> : null}

          {canPrepare ? <button type="button" className={styles.openPrepare} onClick={() => { setPrepareOpen((current) => !current); if (snapshot.account?.status === "ENDED") setLoginIdentifier(snapshot.account.loginIdentifier); }}>{prepareOpen ? "Close request form" : snapshot.account?.status === "ENDED" ? "Prepare guardian consent to reactivate" : "Prepare guardian consent request"}</button> : null}

          {prepareOpen && canPrepare ? (
            <form className={styles.prepare} onSubmit={(event) => void prepare(event)}>
              <header><div><span>Evidence-backed request</span><h3>Prepare—not activate—student access</h3></div><small>The guardian makes the final decision.</small></header>
              <label><span>Adult guardian</span><select required value={guardianId} disabled={busy} onChange={(event) => setGuardianId(event.target.value)}><option value="">Choose a linked guardian</option>{snapshot.guardians.map((guardian) => <option key={guardian.guardianId} value={guardian.guardianId} disabled={!guardian.linkedPortalAccount || guardian.guardianStatus !== "ACTIVE"}>{guardian.displayName} · {displayCode(guardian.relationshipCode)}{guardian.linkedPortalAccount ? "" : " · no linked account"}</option>)}</select></label>
              <label><span>Proposed school sign-in ID</span><input required minLength={3} maxLength={80} pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,79}" value={loginIdentifier} disabled={busy || snapshot.account?.status === "ENDED"} onChange={(event) => setLoginIdentifier(event.target.value.toLowerCase())} placeholder="school-issued identifier" /><small>{snapshot.account?.status === "ENDED" ? "Reactivation must keep the original sign-in ID." : "Use a school-managed identifier; do not use a personal contact address."}</small></label>
              <label><span>Privacy notice version</span><input required maxLength={60} value={privacyNoticeVersion} disabled={busy} onChange={(event) => setPrivacyNoticeVersion(event.target.value)} placeholder="Exact approved notice version" /></label>
              <label><span>Adult verification method</span><select value={verificationMethod} disabled={busy} onChange={(event) => { setVerificationMethod(event.target.value); setVerificationReference(""); }}><option value="SCHOOL_RECORD">School record</option><option value="AUTHORIZED_TOKEN">Authorized token</option><option value="OTHER_REVIEWED">Other reviewed case</option></select></label>
              <label className={styles.wide}><span>Purpose summary shown to guardian</span><textarea required minLength={20} maxLength={1000} value={purposeSummary} disabled={busy} onChange={(event) => setPurposeSummary(event.target.value)} placeholder="Explain what the student can access, why it is needed, and the important limits." /></label>
              <label className={styles.wide}><span>Opaque verification reference</span><input required minLength={3} maxLength={160} value={verificationReference} disabled={busy} onChange={(event) => setVerificationReference(event.target.value)} placeholder={referenceHint} /><small>Use the required prefix and an opaque case/reference ID only. Do not enter identity-document numbers or personal evidence.</small></label>
              <label className={styles.wide}><span>Preparation reason</span><textarea required minLength={3} maxLength={500} value={preparationReason} disabled={busy} onChange={(event) => setPreparationReason(event.target.value)} placeholder="State the authorized request source and review performed without copying private evidence." /></label>
              <button type="submit" disabled={busy || !selectedGuardian?.linkedPortalAccount}>{busy ? "Preparing request…" : "Prepare for guardian review"}</button>
            </form>
          ) : null}

          {(pendingRequest || snapshot.account) ? (
            <div className={styles.actions}>
              <label><span>Required audited reason for the next lifecycle action</span><textarea minLength={3} maxLength={500} value={actionReason} disabled={busy} onChange={(event) => setActionReason(event.target.value)} /></label>
              <div>
                {pendingRequest ? <button type="button" className={styles.danger} disabled={busy || actionReason.trim().length < 3} onClick={() => void lifecycle("revocation")}>Revoke pending request</button> : null}
                {snapshot.account?.status === "ACTIVE" ? <button type="button" disabled={busy || actionReason.trim().length < 3} onClick={() => void lifecycle("suspension")}>Suspend access</button> : null}
                {snapshot.account?.status === "SUSPENDED" ? <button type="button" disabled={busy || actionReason.trim().length < 3} onClick={() => void lifecycle("resumption")}>Resume access</button> : null}
                {snapshot.account && ["ACTIVE", "SUSPENDED"].includes(snapshot.account.status) ? <button type="button" className={styles.danger} disabled={busy || actionReason.trim().length < 3} onClick={() => void lifecycle("ending")}>Permanently end access</button> : null}
              </div>
            </div>
          ) : null}

          {snapshot.history.length ? <details className={styles.history}><summary>Immutable lifecycle history ({snapshot.history.length})</summary><ol>{snapshot.history.map((event) => <li key={event.id}><i /><div><strong>{displayCode(event.eventType)}</strong><p>{event.reason}</p><small>{event.actorName} · {dateTimeLabel(event.occurredAt)}</small></div><em>{displayCode(event.previousStatus)} → {displayCode(event.newStatus)}</em></li>)}</ol></details> : null}
        </>
      ) : null}
    </section>
  );
}
