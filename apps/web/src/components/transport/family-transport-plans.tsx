"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useT } from "@/components/i18n/language-provider";
import { familyChildren, type FamilyChild } from "@/lib/family-children";
import { canChangeTransportPlan, nonTravelPath, readFamilyTransportDay, readTransportChange, selectedTransportJourneys,
  transportChangeBody, transportPlanApi, transportPlanAttempt, TransportPlanProblem,
  type FamilyTransportDay, type JourneyChoice, type TransportPlanAttempt, type TransportProblemCode } from "@/lib/transport-non-travel";
import { TransportPlanError, transportJourneyLabel, useTransportDate } from "./transport-plan-ui";
import styles from "./transport-plans.module.css";

const errorCode = (error: unknown): TransportProblemCode => error instanceof TransportPlanProblem ? error.code : "unavailable";

export function FamilyTransportPlans() {
  const t = useT("transportPlans");
  const [children, setChildren] = useState<FamilyChild[] | null>(null);
  const [learnerId, setLearnerId] = useState("");
  const [error, setError] = useState<TransportProblemCode | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void transportPlanApi("/api/v1/family/children", { signal: controller.signal })
      .then((value) => { const linked = familyChildren(value); if (!controller.signal.aborted) { setChildren(linked); setLearnerId(linked[0]?.learnerId ?? ""); } })
      .catch((error: unknown) => { if (!controller.signal.aborted) setError(errorCode(error)); });
    return () => controller.abort();
  }, [revision]);
  return <main id="main-content" className={styles.centre}>
    <header className={styles.hero}><span>{t("Transport plans")}</span><h1>{t("Today's journeys, clearly recorded.")}</h1>
      <p>{t("Tell the school when your child will not use transport. Changes follow the school's deadlines and never mark classroom attendance.")}</p></header>
    {error && <><TransportPlanError code={error} /><div className={styles.actions}>
      <button type="button" onClick={() => { setError(null); setChildren(null); setLearnerId(""); setRevision((value) => value + 1); }}>{t("Refresh")}</button>
      {error === "session" && <Link href="/portal">{t("Sign in again")}</Link>}
    </div></>}
    {!children && !error && <p role="status">{t("Loading your verified children…")}</p>}
    {children?.length === 0 && <p className={styles.empty}>{t("No active linked children are available. Contact the school to verify access.")}</p>}
    {!!children?.length && <label className={styles.childPicker}>{t("Child")}<select value={learnerId} onChange={(event) => setLearnerId(event.target.value)}>
      {children.map((child) => <option key={child.learnerId} value={child.learnerId}>{child.legalName}</option>)}
    </select></label>}
    {learnerId && <FamilyTransportDayPanel key={learnerId} learnerId={learnerId} />}
  </main>;
}

function FamilyTransportDayPanel({ learnerId }: { learnerId: string }) {
  const t = useT("transportPlans");
  const formatDate = useTransportDate();
  const [data, setData] = useState<FamilyTransportDay | null>(null);
  const [error, setError] = useState<TransportProblemCode | null>(null);
  const [notice, setNotice] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [revision, setRevision] = useState(0);
  const [selected, setSelected] = useState<JourneyChoice | null>(null);
  const [notTravelling, setNotTravelling] = useState(true);
  const [elapsed, setElapsed] = useState(0);
  const received = useRef<number | null>(null);
  const attempt = useRef<TransportPlanAttempt | null>(null);
  const write = useRef<AbortController | null>(null);
  const inFlight = useRef(false);
  useEffect(() => () => write.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    void transportPlanApi(nonTravelPath(learnerId), { signal: controller.signal })
      .then((value) => { const next = readFamilyTransportDay(value, learnerId); if (!controller.signal.aborted) {
        received.current = performance.now(); setElapsed(0); setData(next);
      } })
      .catch((error: unknown) => { if (!controller.signal.aborted) setError(errorCode(error)); });
    return () => controller.abort();
  }, [learnerId, revision]);
  useEffect(() => {
    const timer = window.setInterval(() => { if (received.current !== null) setElapsed(Math.max(0, performance.now() - received.current)); }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  function refresh() { setData(null); setError(null); setRevision((value) => value + 1); }
  const selection = selected ?? data?.journeys[0]?.journeyKind ?? "PICKUP";
  const now = data ? Date.parse(data.observedAt) + elapsed : NaN;
  const canSubmit = !error && !uncertain && !!data && canChangeTransportPlan(data, selection, notTravelling, now);
  const selectedJourneys = data ? selectedTransportJourneys(data, selection) : [];
  const canCancel = selectedJourneys.length === (selection === "FULL_DAY" ? 2 : 1) && selectedJourneys.every((item) => item.notTravelling);

  async function save(retry = false) {
    if (inFlight.current || (!retry && (uncertain || !data || !canSubmit)) || (retry && !attempt.current)) return;
    if (!retry && data) attempt.current = transportPlanAttempt(attempt.current, learnerId, transportChangeBody(data, selection, notTravelling));
    const pending = attempt.current;
    if (!pending) return;
    const controller = new AbortController(); write.current = controller; inFlight.current = true;
    setBusy(true); setError(null); setNotice(false);
    try {
      const value = await transportPlanApi(nonTravelPath(learnerId), { method: "POST", signal: controller.signal,
        body: { ...pending.body, clientRequestId: pending.clientRequestId } });
      try {
        const change = readTransportChange(value);
        if (change.serviceDate !== pending.body.serviceDate || change.journeys !== pending.body.journeys || change.notTravelling !== pending.body.notTravelling)
          throw new Error("Mismatched change");
      } catch { throw new TransportPlanProblem("response", true); }
      if (controller.signal.aborted) return;
      attempt.current = null; setUncertain(false); setNotice(true); refresh();
    } catch (error) {
      if (controller.signal.aborted) return;
      const unknownOutcome = error instanceof TransportPlanProblem && error.uncertain;
      setUncertain(unknownOutcome); if (!unknownOutcome) attempt.current = null;
      setError(errorCode(error));
      if (["session", "access"].includes(errorCode(error))) setData(null);
    } finally { inFlight.current = false; if (!controller.signal.aborted) setBusy(false); }
  }
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); void save(); }

  return <section className={styles.dayPanel} aria-busy={busy}>
    <div className={styles.actions}><button type="button" disabled={busy} onClick={refresh}>{t("Refresh")}</button>
      {error === "session" && <Link href="/portal">{t("Sign in again")}</Link>}</div>
    {error && <TransportPlanError code={error} />}
    {notice && <p role="status" className={styles.success}>{t("Transport change recorded. Check the current plans below.")}</p>}
    {uncertain && <div className={styles.warning}><p>{t("The save could not be confirmed. Retry the same change to avoid duplicate records.")}</p>
      <button type="button" disabled={busy} onClick={() => void save(true)}>{t("Retry this change")}</button></div>}
    {!data && !error && <p role="status">{t("Loading transport plans…")}</p>}
    {data && <>
      <p className={styles.dateSummary}>{t("School-local service date")}: <strong>{data.serviceDate}</strong> · {data.timeZone}</p>
      {!data.enabled && <p className={styles.warning}>{t("The school has not enabled parent transport plans for this campus.")}</p>}
      {data.journeys.length === 0 && <p className={styles.empty}>{t("No operating journeys are assigned for today.")}</p>}
      <div className={styles.journeys}>{data.journeys.map((item) => <article key={item.journeyKind} className={styles.card}>
        <span>{t(transportJourneyLabel(item.journeyKind))}</span><h2>{item.serviceLabel}</h2><p>{item.stopLabel} · {item.scheduledTime.slice(0, 5)}</p>
        <strong className={item.notTravelling ? styles.statusWarning : styles.statusNeutral}>{t(item.notTravelling ? "Not travelling" : "No current opt-out plan")}</strong>
        {item.assignmentChanged && <p className={styles.warning}>{t("This assignment changed. Review and confirm a new plan; the old plan has not been carried forward.")}</p>}
        {item.changesCloseAt && <p>{t("Changes close")}: <time dateTime={item.changesCloseAt}>{formatDate(item.changesCloseAt, data.timeZone)}</time></p>}
        {data.enabled && (!item.changesAllowed || (!!item.changesCloseAt && now >= Date.parse(item.changesCloseAt)))
          && <p className={styles.muted}>{t("Changes are closed. Contact the school for urgent arrangements.")}</p>}
      </article>)}</div>
      {data.enabled && data.journeys.length > 0 && <form onSubmit={submit} className={styles.form}>
        <fieldset disabled={busy || uncertain}><legend>{t("Journey selection")}</legend><div className={styles.choices}>
          {(["PICKUP", "DROP", "FULL_DAY"] as JourneyChoice[]).map((kind) => <label key={kind}><input type="radio" name="journeys" value={kind}
            checked={selection === kind} disabled={selectedTransportJourneys(data, kind).length !== (kind === "FULL_DAY" ? 2 : 1)}
            onChange={() => setSelected(kind)} />{t(transportJourneyLabel(kind))}</label>)}
        </div></fieldset>
        <fieldset disabled={busy || uncertain}><legend>{t("Plan action")}</legend><div className={styles.choices}>
          <label><input type="radio" name="planAction" checked={notTravelling} onChange={() => setNotTravelling(true)} />{t("My child will not use the selected transport")}</label>
          <label><input type="radio" name="planAction" checked={!notTravelling} disabled={!canCancel} onChange={() => setNotTravelling(false)} />{t("Cancel the current opt-out plan")}</label>
        </div>{!canCancel && <p className={styles.muted}>{t("A cancellation is available only when every selected journey has a current opt-out plan.")}</p>}</fieldset>
        <p className={styles.muted}>{t("Parent plans do not authorize pickup, remove a rider, or replace boarding and handover checks.")}</p>
        <button type="submit" disabled={busy || uncertain || !canSubmit}>{t(busy ? "Recording…" : notTravelling ? "Record transport plan" : "Cancel transport plan")}</button>
      </form>}
      <section className={styles.history} aria-labelledby="transport-history-heading"><h2 id="transport-history-heading">{t("Recent plan history")}</h2>
        {!data.history.length && <p className={styles.empty}>{t("No transport changes have been recorded in the last 30 days.")}</p>}
        <ol>{data.history.map((item) => <li key={item.id}><strong>{t(transportJourneyLabel(item.journeys))} · {t(item.notTravelling ? "Opt-out recorded" : "Opt-out cancelled")}</strong>
          <span>{item.serviceDate} · <time dateTime={item.occurredAt}>{formatDate(item.occurredAt, data.timeZone)}</time></span></li>)}</ol>
      </section>
    </>}
  </section>;
}
