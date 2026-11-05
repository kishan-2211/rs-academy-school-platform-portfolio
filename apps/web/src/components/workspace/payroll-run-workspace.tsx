"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Check, Shield } from "@/components/icons";
import { MoneyIcon } from "@/components/workspace/workspace-icons";
import { PayrollRecoveryEvidence, type PayrollRecovery } from "@/components/workspace/payroll-recovery-evidence";

type Policy = { id: string; displayName: string; currencyCode: string; zeroScheduledDaysAction: "FULL_PAY" | "BLOCK"; prorationScale: number; status: string; rowVersion: number };
type RunLine = { id: string; componentName: string; componentKind: string; attendanceProrated: boolean; fullAmount: number; payableAmount: number };
type RunStaff = { id: string; employeeCode: string; staffName: string; salaryRevision: number; scheduledWorkingDays: number; recordedDays: number; payableDays: number; paidLeaveDays: number; unpaidLeaveDays: number; missingAttendanceDays: number; prorationFactor: number; fullGross: number; payableGross: number; deductions: number; employerContributions: number; net: number; warnings: string[]; lines: RunLine[]; recoveries: PayrollRecovery[] };
type RunHistory = { id: string; eventType: string; previousStatus?: string; newStatus: string; reason: string; actorName: string; occurredAt: string };
type RunReversal = { id: string; status: "PENDING" | "APPROVED" | "REJECTED"; requestedRunVersion: number; requestReason: string; requesterName: string; requestedAt: string; deciderName?: string; decidedAt?: string; decisionReason?: string; canDecide: boolean; rowVersion: number };
type Run = { id: string; payrollMonth: string; periodStart: string; periodEnd: string; attemptNumber: number; runCode: string; inputFingerprint: string; currencyCode: string; status: "DRAFT" | "SUBMITTED" | "FINALIZED" | "REJECTED" | "CANCELLED" | "REVERSED"; staffCount: number; scheduledWorkingDays: number; recordedDays: number; payableDays: number; fullGross: number; payableGross: number; deductions: number; employerContributions: number; net: number; creationReason: string; creatorName: string; submitterName?: string; submittedAt?: string; deciderName?: string; decidedAt?: string; decisionReason?: string; canSubmit: boolean; canCancel: boolean; canDecide: boolean; canRequestReversal: boolean; canDecideReversal: boolean; rowVersion: number; staff: RunStaff[]; history: RunHistory[]; reversals: RunReversal[] };
type Workspace = { schoolId: string; schoolName: string; canConfigurePolicy: boolean; canPrepareRuns: boolean; canApproveRuns: boolean; canRequestReversals: boolean; canApproveReversals: boolean; activePolicy: Policy | null; runs: Run[] };
type Blocker = { staffProfileId?: string; staffName?: string; code: string; message: string };
type Preview = { schoolId: string; payrollMonth: string; periodStart: string; periodEnd: string; ready: boolean; inputFingerprint: string; policy: Policy | null; blockers: Blocker[]; staffCount: number; scheduledWorkingDays: number; recordedDays: number; payableDays: number; fullGross: number; payableGross: number; deductions: number; employerContributions: number; net: number; staff: RunStaff[] };
type Action = { run: Run; kind: "submit" | "cancel" | "finalize" | "reject" | "request_reversal" | "approve_reversal" | "reject_reversal"; reversal?: RunReversal };
type PolicyDraft = { displayName: string; currencyCode: string; zeroScheduledDaysAction: "FULL_PAY" | "BLOCK"; prorationScale: string; reason: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const label = (value?: string) => value ? value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase()) : "Not set";
const monthStart = () => { const date = new Date(); date.setMinutes(date.getMinutes() - date.getTimezoneOffset()); return `${date.toISOString().slice(0, 7)}-01`; };
const cookie = (name: string) => document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new Error("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string) { try { return ((await response.json()) as { detail?: string }).detail || fallback; } catch { return fallback; } }
const money = (amount: number, currency = "INR") => new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: 2 }).format(amount);

export function PayrollRunWorkspace({ schoolId }: { schoolId: string }) {
  const [data, setData] = useState<Workspace | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [payrollMonth, setPayrollMonth] = useState(monthStart);
  const [prepareReason, setPrepareReason] = useState("");
  const [retryKey, setRetryKey] = useState("");
  const [policyDraft, setPolicyDraft] = useState<PolicyDraft>({ displayName: "Monthly attendance payroll", currencyCode: "INR", zeroScheduledDaysAction: "FULL_PAY", prorationScale: "6", reason: "" });
  const [action, setAction] = useState<Action | null>(null);
  const [actionReason, setActionReason] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/payroll/runs?schoolId=${encodeURIComponent(schoolId)}`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "Monthly payroll runs are unavailable."));
    const result = await response.json() as Workspace;
    setData(result);
    if (result.activePolicy) setPolicyDraft({ displayName: result.activePolicy.displayName, currencyCode: result.activePolicy.currencyCode, zeroScheduledDaysAction: result.activePolicy.zeroScheduledDaysAction, prorationScale: String(result.activePolicy.prorationScale), reason: "" });
    return result;
  }, [schoolId]);

  useEffect(() => {
    let cancelled = false;
    Promise.resolve().then(load).catch((reason: unknown) => { if (!cancelled) setError(reason instanceof Error ? reason.message : "Monthly payroll runs are unavailable."); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [load]);

  async function request(path: string, method: "POST" | "PUT", body: object, success: string, idempotencyKey?: string) {
    setBusy(true); setError(""); setNotice("");
    try {
      const headers: Record<string, string> = { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken() };
      if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
      const response = await fetch(`${apiUrl}/api/v1/payroll${path}`, { method, credentials: "include", headers, body: JSON.stringify(body) });
      if (!response.ok) throw new Error(await problem(response, "The payroll action could not be completed."));
      await load(); setNotice(success); return true;
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The payroll action could not be completed."); return false; }
    finally { setBusy(false); }
  }

  async function loadPreview() {
    setBusy(true); setError(""); setNotice(""); setPreview(null);
    try {
      const response = await fetch(`${apiUrl}/api/v1/payroll/run-preview?schoolId=${encodeURIComponent(schoolId)}&payrollMonth=${encodeURIComponent(payrollMonth)}`, { credentials: "include" });
      if (!response.ok) throw new Error(await problem(response, "The payroll preview could not be calculated."));
      setPreview(await response.json() as Preview);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The payroll preview could not be calculated."); }
    finally { setBusy(false); }
  }

  async function savePolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return;
    const body = { schoolId, displayName: policyDraft.displayName, currencyCode: policyDraft.currencyCode, zeroScheduledDaysAction: policyDraft.zeroScheduledDaysAction, prorationScale: Number(policyDraft.prorationScale), status: "ACTIVE", expectedVersion: data.activePolicy?.rowVersion ?? null, reason: policyDraft.reason };
    const ok = await request(data.activePolicy ? `/run-policies/${data.activePolicy.id}` : "/run-policies", data.activePolicy ? "PUT" : "POST", body, data.activePolicy ? "Monthly payroll policy updated without changing prior runs." : "Monthly payroll policy activated.");
    if (ok) { setPolicyDraft((current) => ({ ...current, reason: "" })); setPreview(null); }
  }

  async function prepareRun() {
    if (!preview?.ready || !prepareReason.trim()) return;
    const key = retryKey || globalThis.crypto?.randomUUID?.() || `payroll-${payrollMonth}`;
    if (!retryKey) setRetryKey(key);
    const ok = await request("/runs", "POST", { schoolId, payrollMonth, previewFingerprint: preview.inputFingerprint, idempotencyKey: key, reason: prepareReason }, "Payroll evidence frozen as a protected draft.");
    if (ok) { setPreview(null); setPrepareReason(""); setRetryKey(globalThis.crypto?.randomUUID?.() || `payroll-${Date.now()}`); }
  }

  async function completeAction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!action) return;
    if (action.kind === "request_reversal") {
      const reversalKey = globalThis.crypto?.randomUUID?.()
        || `payroll-reversal-${action.run.id}-${Date.now()}`;
      const ok = await request(`/runs/${action.run.id}/reversal-requests`, "POST", { schoolId, expectedRunVersion: action.run.rowVersion, reason: actionReason }, "Payroll reversal requested for an independent decision.", reversalKey);
      if (ok) { setAction(null); setActionReason(""); }
      return;
    }
    if (action.kind === "approve_reversal" || action.kind === "reject_reversal") {
      if (!action.reversal) return;
      const decision = action.kind === "approve_reversal" ? "APPROVE" : "REJECT";
      const ok = await request(`/run-reversal-requests/${action.reversal.id}/decision`, "POST", { schoolId, decision, expectedVersion: action.reversal.rowVersion, reason: actionReason }, decision === "APPROVE" ? "Payroll run reversed with all source evidence retained. The month is ready for a replacement attempt." : "Payroll reversal rejected with the finalized run unchanged.");
      if (ok) { setAction(null); setActionReason(""); }
      return;
    }
    const decision = action.kind === "finalize" ? "FINALIZE" : action.kind === "reject" ? "REJECT" : undefined;
    const path = decision ? `/runs/${action.run.id}/decision` : `/runs/${action.run.id}/${action.kind}`;
    const ok = await request(path, "POST", { schoolId, ...(decision ? { decision } : {}), expectedVersion: action.run.rowVersion, reason: actionReason }, action.kind === "submit" ? "Payroll submitted for an independent decision." : action.kind === "cancel" ? "Draft cancelled with history preserved." : action.kind === "finalize" ? "Payroll finalized after input revalidation." : "Payroll rejected with a recorded reason.");
    if (ok) { setAction(null); setActionReason(""); }
  }

  if (loading) return <section className="payroll-panel payroll-run-state" aria-live="polite"><span /><p>Opening governed payroll runs…</p></section>;
  if (!data) return <section className="payroll-panel payroll-run-state" role="alert"><MoneyIcon /><h2>Payroll runs are unavailable</h2><p>{error}</p><button type="button" onClick={() => window.location.reload()}>Try again</button></section>;

  return <section className="payroll-run-layout">
    {error && <div className="payroll-banner is-error" role="alert"><span>{error}</span><button type="button" aria-label="Dismiss payroll run error" onClick={() => setError("")}>×</button></div>}
    {notice && <div className="payroll-banner is-success" role="status"><span><Check /> {notice}</span><button type="button" aria-label="Dismiss payroll run notice" onClick={() => setNotice("")}>×</button></div>}

    <article className="payroll-panel payroll-run-policy">
      <header><div><span>Calculation control</span><h2>{data.activePolicy ? data.activePolicy.displayName : "Activate a monthly policy"}</h2></div><Shield /></header>
      {data.canConfigurePolicy ? <form onSubmit={savePolicy}>
        <label>Policy name<input required minLength={2} maxLength={120} value={policyDraft.displayName} onChange={(event) => setPolicyDraft({ ...policyDraft, displayName: event.target.value })} /></label>
        <label>Currency<input required pattern="[A-Za-z]{3}" maxLength={3} value={policyDraft.currencyCode} onChange={(event) => setPolicyDraft({ ...policyDraft, currencyCode: event.target.value.toUpperCase() })} /></label>
        <label>When no working days<select value={policyDraft.zeroScheduledDaysAction} onChange={(event) => setPolicyDraft({ ...policyDraft, zeroScheduledDaysAction: event.target.value as PolicyDraft["zeroScheduledDaysAction"] })}><option value="FULL_PAY">Pay full configured earnings</option><option value="BLOCK">Block payroll</option></select></label>
        <label>Proration precision<select value={policyDraft.prorationScale} onChange={(event) => setPolicyDraft({ ...policyDraft, prorationScale: event.target.value })}>{[2, 3, 4, 5, 6].map((value) => <option key={value}>{value}</option>)}</select></label>
        <label className="is-wide">Change reason<textarea required minLength={3} maxLength={500} value={policyDraft.reason} onChange={(event) => setPolicyDraft({ ...policyDraft, reason: event.target.value })} /></label>
        <footer><small>Statutory rates remain configurable salary components and require professional review.</small><button disabled={busy} type="submit">{data.activePolicy ? "Update policy" : "Activate policy"}</button></footer>
      </form> : <p className="payroll-empty">The active calculation policy is visible here; only payroll configuration roles may change it.</p>}
    </article>

    <article className="payroll-panel payroll-run-preview">
      <header><div><span>Pre-flight control</span><h2>Review before freezing</h2></div><MoneyIcon /></header>
      {data.canPrepareRuns ? <>
        <div className="payroll-run-controls"><label>Payroll month<input type="month" value={payrollMonth.slice(0, 7)} onChange={(event) => { setPayrollMonth(`${event.target.value}-01`); setPreview(null); }} /></label><button type="button" disabled={busy} onClick={() => void loadPreview()}>Calculate preview</button></div>
        {preview && <div className="payroll-preview-result">
          <div className={`payroll-preview-status ${preview.ready ? "is-ready" : "is-blocked"}`} role="status"><strong>{preview.ready ? "Ready to prepare" : `${preview.blockers.length} blocker${preview.blockers.length === 1 ? "" : "s"}`}</strong><span>Fingerprint {preview.inputFingerprint.slice(0, 12)}…</span></div>
          <div className="payroll-run-totals"><span><small>Staff</small><b>{preview.staffCount}</b></span><span><small>Recorded days</small><b>{preview.recordedDays}/{preview.scheduledWorkingDays}</b></span><span><small>Payable gross</small><b>{money(preview.payableGross, preview.policy?.currencyCode)}</b></span><span><small>Net</small><strong>{money(preview.net, preview.policy?.currencyCode)}</strong></span></div>
          {preview.blockers.length > 0 && <ul className="payroll-blockers" aria-label="Payroll preview blockers">{preview.blockers.map((item, index) => <li key={`${item.code}-${item.staffProfileId || index}`}><b>{item.staffName || "School configuration"}</b><span>{item.message}</span><small>{label(item.code)}</small></li>)}</ul>}
          {preview.staff.length > 0 && <StaffEvidence staff={preview.staff} currency={preview.policy?.currencyCode || "INR"} />}
          {preview.ready && <div className="payroll-prepare"><label>Preparation reason<textarea required minLength={3} maxLength={500} value={prepareReason} onChange={(event) => setPrepareReason(event.target.value)} /></label><button type="button" disabled={busy || prepareReason.trim().length < 3} onClick={() => void prepareRun()}>Freeze protected draft</button></div>}
        </div>}
      </> : <p className="payroll-empty">You can inspect frozen runs below. A payroll preparation role must calculate new previews.</p>}
    </article>

    {action && <form className="payroll-panel payroll-run-action" onSubmit={completeAction} aria-labelledby="payroll-action-title">
      <header><div><span>Recorded transition</span><h2 id="payroll-action-title">{label(action.kind)} {action.run.runCode}</h2></div><button className="is-quiet" type="button" onClick={() => { setAction(null); setActionReason(""); }}>Close</button></header>
      <label>{action.kind === "request_reversal" ? "Reversal reason" : "Decision reason"}<textarea autoFocus required minLength={3} maxLength={500} value={actionReason} onChange={(event) => setActionReason(event.target.value)} /></label>
      <footer><small>{action.kind === "finalize" ? "Inputs will be recalculated and must still match the frozen fingerprint." : action.kind === "request_reversal" ? "The finalized calculation remains immutable while an independent reviewer considers this request." : action.kind === "approve_reversal" ? "Approval invalidates staff self-service access to old payslips and releases the month for a new protected attempt; archived evidence remains available to payroll authorities." : "The transition is append-only and remains visible in run history."}</small><button disabled={busy} type="submit">Confirm {label(action.kind)}</button></footer>
    </form>}

    <article className="payroll-panel payroll-run-history">
      <header><div><span>Immutable monthly record</span><h2>Payroll run history</h2></div><strong>{data.runs.length}</strong></header>
      {data.runs.length ? <div>{data.runs.map((run) => <article key={run.id}>
        <div className="payroll-run-title"><span><b>{run.runCode}</b><em className={`is-${run.status.toLowerCase()}`}>{label(run.status)}</em></span><small>{run.periodStart} to {run.periodEnd} · attempt {run.attemptNumber} · prepared by {run.creatorName}</small></div>
        <div className="payroll-run-totals"><span><small>Staff</small><b>{run.staffCount}</b></span><span><small>Payable days</small><b>{run.payableDays}</b></span><span><small>Deductions</small><b>{money(run.deductions, run.currencyCode)}</b></span><span><small>Net</small><strong>{money(run.net, run.currencyCode)}</strong></span></div>
        <p>{run.creationReason}</p><StaffEvidence staff={run.staff} currency={run.currencyCode} />
        <ol>{run.history.map((event) => <li key={event.id}><b>{label(event.eventType)}</b><span>{event.reason}</span><small>{event.actorName} · {new Date(event.occurredAt).toLocaleString("en-IN")}</small></li>)}</ol>
        {run.reversals.length > 0 && <div className="payroll-reversal-history" aria-label={`Reversal history for ${run.runCode}`}>{run.reversals.map((reversal) => <article key={reversal.id}><span><b>{label(reversal.status)} reversal</b><em className={`is-${reversal.status.toLowerCase()}`}>{label(reversal.status)}</em></span><p>{reversal.requestReason}</p><small>Requested by {reversal.requesterName} · {new Date(reversal.requestedAt).toLocaleString("en-IN")}</small>{reversal.decisionReason && <><p>{reversal.decisionReason}</p><small>Decided by {reversal.deciderName} · {reversal.decidedAt ? new Date(reversal.decidedAt).toLocaleString("en-IN") : "Recorded"}</small></>}{reversal.canDecide && <div><button disabled={busy} type="button" onClick={() => setAction({ run, reversal, kind: "approve_reversal" })}>Approve reversal</button><button className="is-secondary" disabled={busy} type="button" onClick={() => setAction({ run, reversal, kind: "reject_reversal" })}>Reject reversal</button></div>}</article>)}</div>}
        <footer>{run.canSubmit && <button disabled={busy} type="button" onClick={() => setAction({ run, kind: "submit" })}>Submit for decision</button>}{run.canCancel && <button className="is-secondary" disabled={busy} type="button" onClick={() => setAction({ run, kind: "cancel" })}>Cancel draft</button>}{run.canDecide && <><button disabled={busy} type="button" onClick={() => setAction({ run, kind: "finalize" })}>Finalize independently</button><button className="is-secondary" disabled={busy} type="button" onClick={() => setAction({ run, kind: "reject" })}>Reject</button></>}{run.canRequestReversal && <button className="is-secondary" disabled={busy} type="button" onClick={() => setAction({ run, kind: "request_reversal" })}>Request reversal</button>}</footer>
      </article>)}</div> : <p className="payroll-empty">No monthly payroll has been prepared. Activate a policy, resolve preview blockers, and freeze the first run.</p>}
    </article>
  </section>;
}

function StaffEvidence({ staff, currency }: { staff: RunStaff[]; currency: string }) {
  return <details className="payroll-staff-evidence"><summary>{staff.length} staff calculation{staff.length === 1 ? "" : "s"}</summary><div>{staff.map((item) => <article key={item.id}><div><b>{item.staffName}</b><small>{item.employeeCode} · salary revision {item.salaryRevision}</small></div><span><small>Attendance</small><b>{item.recordedDays}/{item.scheduledWorkingDays} · {item.payableDays} payable</b></span><span><small>Proration</small><b>{(item.prorationFactor * 100).toFixed(2)}%</b></span><span><small>Net</small><strong>{money(item.net, currency)}</strong></span><details><summary>{item.lines.length} frozen component{item.lines.length === 1 ? "" : "s"}</summary><ul>{item.lines.map((line) => <li key={line.id}><span>{line.componentName}{line.attendanceProrated ? " · prorated" : ""}</span><b>{money(line.payableAmount, currency)}</b></li>)}</ul></details><PayrollRecoveryEvidence recoveries={item.recoveries} currency={currency} />{item.warnings.length > 0 && <p>{item.warnings.map(label).join(" · ")}</p>}</article>)}</div></details>;
}
