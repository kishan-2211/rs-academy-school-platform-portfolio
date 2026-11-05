"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useT } from "@/components/i18n/language-provider";
import { nonTravelPolicyPath, readTransportPolicies, readTransportPolicy, transportPlanApi, TransportPlanProblem,
  type TransportPlanPolicy, type TransportPolicyWorkspace, type TransportProblemCode } from "@/lib/transport-non-travel";
import { TransportPlanError } from "./transport-plan-ui";
import styles from "./transport-plans.module.css";

export function TransportNonTravelPolicyPanel({ schoolId }: { schoolId: string }) {
  const t = useT("transportPlans");
  const [data, setData] = useState<TransportPolicyWorkspace | null>(null);
  const [campusId, setCampusId] = useState("");
  const [error, setError] = useState<TransportProblemCode | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void transportPlanApi(nonTravelPolicyPath(schoolId), { signal: controller.signal })
      .then((value) => { const next = readTransportPolicies(value, schoolId); if (!controller.signal.aborted) {
        setData(next); setCampusId((old) => next.policies.some((policy) => policy.campusId === old) ? old : next.policies[0]?.campusId ?? "");
      } })
      .catch((error: unknown) => { if (!controller.signal.aborted) setError(error instanceof TransportPlanProblem ? error.code : "unavailable"); });
    return () => controller.abort();
  }, [schoolId, revision]);
  const policy = data?.policies.find((item) => item.campusId === campusId);
  function refresh() { setData(null); setError(null); setRevision((value) => value + 1); }
  return <section className={styles.policyPanel} aria-labelledby="transport-policy-heading">
    <header className={styles.policyHeader}><h2 id="transport-policy-heading">{t("Parent transport deadlines")}</h2>
      <p>{t("Configure school-local deadlines. Route departure and started trips may close changes earlier. Existing plans retain their original deadline.")}</p></header>
    <div className={styles.actions}><button type="button" onClick={refresh}>{t("Refresh")}</button></div>
    {error && <TransportPlanError code={error} />}
    {!data && !error && <p role="status">{t("Loading transport plans…")}</p>}
    {data?.policies.length === 0 && <p className={styles.empty}>{t("No active campus is available for configuration.")}</p>}
    {!!data?.policies.length && <label className={styles.childPicker}>{t("Campus")}<select value={campusId} onChange={(event) => setCampusId(event.target.value)}>
      {data.policies.map((item) => <option value={item.campusId} key={item.campusId}>{item.campusName}</option>)}</select></label>}
    {policy && <PolicyForm key={`${schoolId}:${policy.campusId}:${policy.version}`} schoolId={schoolId} policy={policy} refresh={refresh} />}
  </section>;
}

function PolicyForm({ schoolId, policy, refresh }: { schoolId: string; policy: TransportPlanPolicy; refresh: () => void }) {
  const t = useT("transportPlans");
  const [enabled, setEnabled] = useState(policy.enabled);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<TransportProblemCode | null>(null);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [saved, setSaved] = useState(false);
  const write = useRef<AbortController | null>(null);
  const inFlight = useRef(false);
  useEffect(() => () => write.current?.abort(), []);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || needsRefresh) return;
    const form = new FormData(event.currentTarget);
    const reason = String(form.get("reason") ?? "").trim();
    if (reason.length < 3 || reason.length > 500) { setError("invalid"); return; }
    const controller = new AbortController(); write.current = controller; inFlight.current = true;
    setBusy(true); setError(null);
    try {
      const value = await transportPlanApi("/api/v1/transport/non-travel/policies", { method: "PUT", signal: controller.signal, body: {
        schoolId, campusId: policy.campusId, enabled,
        pickupClosesAt: String(form.get("pickupClosesAt") ?? "") || null,
        dropClosesAt: String(form.get("dropClosesAt") ?? "") || null,
        expectedVersion: policy.version < 0 ? null : policy.version, reason,
      } });
      try {
        const recorded = readTransportPolicy(value);
        if (recorded.campusId !== policy.campusId || recorded.version !== policy.version + 1 || recorded.enabled !== enabled)
          throw new Error("Mismatched revision");
      } catch { throw new TransportPlanProblem("response", true); }
      if (!controller.signal.aborted) { setSaved(true); setNeedsRefresh(true); }
    } catch (error) {
      if (controller.signal.aborted) return;
      const code = error instanceof TransportPlanProblem ? error.code : "unavailable";
      setError(code);
      if ((error instanceof TransportPlanProblem && error.uncertain) || ["changed", "session", "access"].includes(code)) setNeedsRefresh(true);
    } finally { inFlight.current = false; if (!controller.signal.aborted) setBusy(false); }
  }
  return <form onSubmit={submit} aria-busy={busy}>
    <p className={styles.dateSummary}>{policy.timeZone} · {t(policy.version < 0 ? "Unconfigured — disabled" : policy.enabled ? "Enabled" : "Disabled")}</p>
    {error && <TransportPlanError code={error} />}
    {saved && <p className={styles.success} role="status">{t("Deadline revision saved.")}</p>}
    <fieldset disabled={busy || needsRefresh} className={styles.policyFields}><legend>{t("Parent transport deadlines")}</legend><div className={styles.policyGrid}>
      <label className={`${styles.check} ${styles.wide}`}><input name="enabled" type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />{t("Enable parent plans")}</label>
      <label>{t("Pickup change deadline")}<input name="pickupClosesAt" type="time" step="1" defaultValue={policy.pickupClosesAt ?? ""} required={enabled} /></label>
      <label>{t("Drop change deadline")}<input name="dropClosesAt" type="time" step="1" defaultValue={policy.dropClosesAt ?? ""} required={enabled} /></label>
      <label className={styles.wide}>{t("Reason for this policy revision")}<textarea name="reason" minLength={3} maxLength={500} required /></label>
      <button type="submit">{t(busy ? "Saving…" : "Save deadline revision")}</button>
    </div></fieldset>
    {needsRefresh && <div className={styles.actions}><p>{t("Refresh to view the current policy.")}</p><button type="button" onClick={refresh}>{t("Refresh")}</button></div>}
  </form>;
}
