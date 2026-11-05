"use client";

import { useEffect, useState, type FormEvent } from "react";
import { changePushProvider, loadPushOperations, type PushCount, type PushOperations } from "@/lib/push-operations";

const label = (value: string) => value.toLowerCase().replaceAll("_", " ");

function Counts({ title, items }: { title: string; items: PushCount[] }) {
  return <section className="push-operations-counts"><h3>{title}</h3>{items.length ? <dl>{items.map((item) =>
    <div key={item.code}><dt>{label(item.code)}</dt><dd>{item.count.toLocaleString("en-IN")}</dd></div>
  )}</dl> : <p>No records in this period.</p>}</section>;
}

export function PushOperationsPanel({ schoolId }: { schoolId: string }) {
  const [data, setData] = useState<PushOperations | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    loadPushOperations(schoolId, controller.signal).then((snapshot) => {
      if (!controller.signal.aborted) setData(snapshot);
    }).catch((cause: unknown) => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Push operations are unavailable.");
    }).finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [schoolId]);

  async function refresh() {
    if (busy) return;
    setBusy(true); setError(""); setNotice(""); setConfirmed(false);
    try { setData(await loadPushOperations(schoolId)); }
    catch (cause) { setData(null); setError(cause instanceof Error ? cause.message : "Push operations are unavailable."); }
    finally { setBusy(false); }
  }

  async function decide(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data || busy || !confirmed || !data.canApprove) return;
    const status = data.providerStatus === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await changePushProvider(schoolId, status, data.providerVersion, reason);
      setData(updated); setReason(""); setConfirmed(false);
      setNotice(status === "ACTIVE" ? "School provider approval saved. Deployment switches and device consent still apply." : "School push provider suspended. Delivery history is preserved; an already in-flight request may finish.");
    } catch (cause) {
      // An uncertain response must not invite blind replay or retain stale approval controls.
      setData(null); setConfirmed(false);
      setError(cause instanceof Error ? cause.message : "The decision could not be confirmed. Refresh before retrying.");
    } finally { setBusy(false); }
  }

  const canActivate = data?.providerCompatible;
  const active = data?.providerStatus === "ACTIVE";
  return <section className="push-operations" aria-labelledby="push-operations-title" aria-busy={busy}>
    <header><div><h2 id="push-operations-title">Phone notification operations</h2><p>School approval, deployment readiness and privacy-safe delivery evidence.</p></div>
      <button type="button" onClick={refresh} disabled={busy}>Refresh status</button></header>
    {error && <p className="push-operations-error" role="alert">{error}</p>}
    {notice && <p className="push-operations-notice" role="status">{notice}</p>}
    {busy && <p role="status">Checking push operations…</p>}
    {data && <>
      <dl className="push-operations-readiness">
        <div><dt>School provider</dt><dd>{label(data.providerStatus)}</dd></div>
        <div><dt>Encrypted transport</dt><dd>{data.transportEnabled ? "Enabled in deployment" : "Disabled in deployment"}</dd></div>
        <div><dt>Background worker</dt><dd>{data.workerEnabled ? "Enabled in deployment" : "Disabled in deployment"}</dd></div>
      </dl>
      <p>Approval alone does not enable sending. Both deployment switches, current recipient permission, consent and an active device are required. This page never displays or edits provider keys.</p>
      {!data.providerCompatible && <p className="push-operations-error">The existing provider mapping needs deployment review. It cannot be replaced from this screen.</p>}
      {data.canApprove ? <form onSubmit={decide}>
        <h3>{active ? "Suspend school push delivery" : "Approve school push delivery"}</h3>
        <p>MFA-verified approval is required. Notice approval remains separate. Suspending does not erase earlier deliveries or revoke families’ device consent.</p>
        <label htmlFor="push-provider-reason">Decision reason — do not include personal data or secrets</label>
        <input id="push-provider-reason" value={reason} onChange={(event) => setReason(event.target.value)} required minLength={3} maxLength={500} disabled={busy} autoComplete="off" />
        <label className="push-operations-confirm"><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} disabled={busy} required />
          I confirm this school-level decision. If sending is enabled, approval can release eligible waiting notices.</label>
        <button type="submit" disabled={busy || !confirmed || reason.trim().length < 3 || (!active && !canActivate)}>{active ? "Confirm suspension" : "Confirm approval"}</button>
      </form> : <p>Your current role can inspect operations. An MFA-verified school approver must approve or suspend the provider.</p>}
      <h3>Records created in the last 30 days</h3>
      <p>Since {new Date(data.windowStartsAt).toLocaleString("en-IN")}. Counts include current states for that period, not all-time totals. Sent/accepted means provider acceptance, not proof that a phone displayed or a person read the notification.</p>
      <div className="push-operations-grid"><Counts title="Recipient deliveries" items={data.parentStates} /><Counts title="Device jobs" items={data.deviceStates} /><Counts title="Current retry / failure codes" items={data.failureCodes} /></div>
    </>}
  </section>;
}
