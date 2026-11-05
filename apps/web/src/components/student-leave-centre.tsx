"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { leaveApi, leaveAttempt, leaveLabel as label, type LeaveAttempt, type LeaveDetail, type LeaveWorkspace } from "@/lib/student-leave";

import { familyChildren, type FamilyChild as Child } from "@/lib/family-children";
const message = (error: unknown) => error instanceof Error ? error.message : "Student leave is unavailable.";

export function StudentLeaveCentre({ family = false }: { family?: boolean }) {
  const [children, setChildren] = useState<Child[] | null>(null);
  const [learnerId, setLearnerId] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    if (!family) return;
    const controller = new AbortController();
    void leaveApi<unknown>("/api/v1/family/children", { signal: controller.signal })
      .then((data) => { const linked = familyChildren(data); if (!controller.signal.aborted) { setChildren(linked); setLearnerId(linked[0]?.learnerId ?? ""); } })
      .catch((error: unknown) => { if (!controller.signal.aborted) setError(message(error)); });
    return () => controller.abort();
  }, [family]);
  return <main id="main-content" className={`request-centre student-leave-centre ${family ? "request-centre--family" : "request-centre--workspace"}`}>
    <header className="request-hero"><div><span>{family ? "Family attendance" : "School attendance"}</span>
      <h1>Student leave, clearly recorded.</h1>
      <p>{family ? "Request full-day leave and follow the school's decision. Previously recorded absences still use the attendance explanation workflow." : "Review leave, keep decisions independent, and preserve each family's original request."}</p>
    </div></header>
    {error && <p className="request-banner is-error" role="alert">{error}</p>}
    {family && !children && !error && <p role="status">Loading your verified children…</p>}
    {family && children?.length === 0 && <div className="request-empty">No active linked children are available. Contact the school to verify access.</div>}
    {family && !!children?.length && <div className="request-toolbar"><label>Child
      <select value={learnerId} onChange={(event) => setLearnerId(event.target.value)}>{children.map((child) => <option key={child.learnerId} value={child.learnerId}>{child.legalName}</option>)}</select>
    </label></div>}
    {(!family || learnerId) && <LeavePanel key={`${family}:${learnerId}`} family={family} learnerId={learnerId} />}
  </main>;
}

function LeavePanel({ family, learnerId }: { family: boolean; learnerId: string }) {
  const [data, setData] = useState<LeaveWorkspace | null>(null);
  const [detail, setDetail] = useState<LeaveDetail | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const [revision, setRevision] = useState(0);
  const [sessionId, setSessionId] = useState("");
  const attempt = useRef<LeaveAttempt | null>(null);
  const detailController = useRef<AbortController | null>(null);
  const heading = useRef<HTMLHeadingElement | null>(null);
  const mounted = useRef(true);
  const root = family ? `/api/v1/family/children/${learnerId}/leave` : "/api/v1/student-leave";

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; detailController.current?.abort(); };
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    const query = new URLSearchParams({ page: String(page), ...(!family && status ? { status } : {}) });
    void leaveApi<LeaveWorkspace>(`${root}?${query}`, { signal: controller.signal })
      .then((data) => { if (!controller.signal.aborted) setData(data); })
      .catch((error: unknown) => { if (!controller.signal.aborted) setError(message(error)); });
    return () => controller.abort();
  }, [root, family, page, status, revision]);
  useEffect(() => { if (detail) heading.current?.focus(); }, [detail]);

  function clearView() { detailController.current?.abort(); setData(null); setDetail(null); setError(""); setDetailLoading(false); }
  function reload() { clearView(); setRevision((value) => value + 1); }

  async function openDetail(id: string) {
    detailController.current?.abort();
    const controller = new AbortController(); detailController.current = controller;
    setDetail(null); setDetailLoading(true); setError("");
    try {
      const result = await leaveApi<LeaveDetail>(`${root}/${id}${family ? "" : `?schoolId=${data?.schoolId}`}`, { signal: controller.signal });
      if (!controller.signal.aborted) setDetail(result);
    } catch (error) { if (!controller.signal.aborted) setError(message(error)); }
    finally { if (!controller.signal.aborted) setDetailLoading(false); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const body = { learnerId, sessionId: values.get("sessionId"), startsOn: values.get("startsOn"), endsOn: values.get("endsOn"), leaveType: values.get("leaveType"), reason: values.get("reason") };
    attempt.current = leaveAttempt(attempt.current, body);
    setBusy(true); setError(""); setNotice("");
    try {
      await leaveApi(root, { method: "POST", body: { sessionId: body.sessionId, startsOn: body.startsOn, endsOn: body.endsOn, leaveType: body.leaveType, reason: body.reason, idempotencyKey: attempt.current.key } });
      if (!mounted.current) return;
      attempt.current = null; setNotice("Leave request submitted. Approval is a separate school decision."); reload();
    } catch (error) { if (mounted.current) setError(message(error)); }
    finally { if (mounted.current) setBusy(false); }
  }

  async function savePolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data?.schoolId) return;
    const values = new FormData(event.currentTarget);
    const session = String(values.get("sessionId"));
    const previous = data.policies.find((policy) => policy.sessionId === session);
    setBusy(true); setError(""); setNotice("");
    try {
      await leaveApi("/api/v1/student-leave/policy", { method: "PUT", body: { schoolId: data.schoolId, sessionId: session,
        maxCalendarDays: Number(values.get("maxCalendarDays")), retrospectiveDays: Number(values.get("retrospectiveDays")),
        attendanceState: values.get("attendanceState"), enabled: values.get("enabled") === "on",
        expectedVersion: previous?.rowVersion ?? null, reason: values.get("reason") } });
      if (!mounted.current) return;
      setNotice("Session leave settings saved. Existing submitted requests retain their recorded policy."); reload();
    } catch (error) { if (mounted.current) setError(message(error)); }
    finally { if (mounted.current) setBusy(false); }
  }

  async function decide(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!detail || !data) return;
    const values = new FormData(event.currentTarget);
    const body = { expectedVersion: detail.request.rowVersion, reason: values.get("reason"),
      ...(!family ? { schoolId: data.schoolId, decision: values.get("decision") } : {}) };
    setBusy(true); setError(""); setNotice("");
    try {
      await leaveApi(`${root}/${detail.request.id}/${family ? "withdrawal" : "decision"}`, { method: "POST", body });
      if (!mounted.current) return;
      setNotice("Leave decision recorded. Submitted attendance has not been silently changed."); reload();
    } catch (error) { if (mounted.current) setError(message(error)); }
    finally { if (mounted.current) setBusy(false); }
  }

  const selectedSession = sessionId || data?.sessions[0]?.id || "";
  const policy = data?.policies.find((item) => item.sessionId === selectedSession);
  const session = data?.sessions.find((item) => item.id === selectedSession);
  return <>
    {error && <p className="request-banner is-error" role="alert">{error} <button type="button" onClick={reload} disabled={busy}>Refresh</button></p>}
    {notice && <p className="request-banner" role="status">{notice}</p>}
    {!data && !error && <p role="status">Loading authorized leave records…</p>}
    {data && <>
      {data.schoolName && <h2>{data.schoolName}</h2>}
      {!!data.sessions.length && <div className="request-toolbar"><label>Academic session<select value={selectedSession} disabled={busy} onChange={(event) => setSessionId(event.target.value)}>{data.sessions.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label></div>}
      {!data.sessions.length && <div className="request-empty">No open academic session is available for leave requests.</div>}
      {family && session && (policy?.enabled ? <form className="request-compose" onSubmit={submit} key={selectedSession}>
        <header className="is-wide"><h2>Request leave</h2><p>Up to {policy.maxCalendarDays} calendar days per request. Dates may begin up to {policy.retrospectiveDays} days before today, within your enrolment.</p></header>
        <input type="hidden" name="sessionId" value={selectedSession} />
        <label>From date<input type="date" name="startsOn" min={session.startsOn} max={session.endsOn} required disabled={busy} /></label>
        <label>Through date<input type="date" name="endsOn" min={session.startsOn} max={session.endsOn} required disabled={busy} /></label>
        <label>Leave type<select name="leaveType" disabled={busy}>{["PERSONAL","ILLNESS","FAMILY","OTHER"].map((type) => <option key={type} value={type}>{label(type)}</option>)}</select></label>
        <label>Reason<textarea name="reason" required minLength={3} maxLength={1000} rows={3} disabled={busy} aria-describedby="leave-privacy" /></label>
        <footer><p id="leave-privacy">Keep the reason brief. Do not include diagnoses, medical reports, passwords or payment details. Holidays are not counted as recorded absences merely because they fall inside this range.</p><button disabled={busy}>{busy ? "Saving…" : "Submit leave request"}</button></footer>
      </form> : <div className="request-empty">The school has not enabled leave requests for this session. Please contact the office.</div>)}
      {!family && session && <details className="request-detail"><summary>Session leave settings</summary>
        <form key={`${selectedSession}:${policy?.rowVersion ?? "new"}`} className="request-compose" onSubmit={savePolicy}>
          <input name="sessionId" type="hidden" value={selectedSession} />
          <label>Maximum calendar days<input name="maxCalendarDays" type="number" min={1} max={366} required defaultValue={policy?.maxCalendarDays} disabled={busy} /></label>
          <label>Retrospective request window (days)<input name="retrospectiveDays" type="number" min={0} max={90} required defaultValue={policy?.retrospectiveDays} disabled={busy} /></label>
          <label>Approved absence state<select name="attendanceState" defaultValue={policy?.attendanceState ?? "EXCUSED"} disabled={busy}><option value="EXCUSED">Excused</option><option value="ABSENT">Absent</option></select></label>
          <label>Reason for settings change<textarea name="reason" minLength={3} maxLength={500} required disabled={busy} /></label>
          <label className="request-check"><input type="checkbox" name="enabled" defaultChecked={policy?.enabled ?? false} disabled={busy} /> Enable family leave requests</label>
          <footer><p>Confirm the school policy before enabling. The selected absence state must exist in the session&apos;s <Link href="/workspace/attendance">attendance settings</Link>. This does not change attendance credit or past requests.</p><button disabled={busy}>Save session settings</button></footer>
        </form></details>}
      <div className="request-toolbar">
        {!family && <label>Request status<select value={status} disabled={busy} onChange={(event) => { clearView(); setPage(0); setStatus(event.target.value); }}><option value="">All statuses</option>{["SUBMITTED","APPROVED","REJECTED","WITHDRAWN","REVOKED"].map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label>}
        <p>{data.records.total} requests · Page {page + 1}</p>
        <button type="button" disabled={busy || page === 0} onClick={() => { clearView(); setPage(page - 1); }}>Previous page</button>
        <button type="button" disabled={busy || (page + 1) * data.records.pageSize >= data.records.total} onClick={() => { clearView(); setPage(page + 1); }}>Next page</button>
      </div>
      <div className="request-layout"><aside className="request-list" aria-label="Leave requests">
        {data.records.requests.map((item) => <button type="button" key={item.id} aria-pressed={detail?.request.id === item.id} disabled={busy} onClick={() => void openDetail(item.id)}>
          <span>{item.learnerName}</span><small>{item.startsOn} → {item.endsOn}</small><em>{label(item.status)}</em>
        </button>)}
        {!data.records.requests.length && <div className="request-empty">No leave requests match this view.</div>}
      </aside><section className="request-detail" aria-label="Selected leave request">
        {detailLoading && <p role="status">Loading private request history…</p>}
        {!detail && !detailLoading && <p>Select a request to view its reason and decision history.</p>}
        {detail && <><header><h2 ref={heading} tabIndex={-1}>{detail.request.learnerName}: {label(detail.request.status)}</h2></header>
          <p>{detail.request.startsOn} through {detail.request.endsOn} · {label(detail.request.leaveType)}</p><p>{detail.request.reason}</p>
          <ol className="request-history">{detail.history.map((item) => <li key={item.requestVersion}><strong>{label(item.newStatus)}</strong><p>{item.reason}</p><time dateTime={item.occurredAt}>{new Date(item.occurredAt).toLocaleString("en-IN")}</time></li>)}</ol>
          {((family && detail.request.canWithdraw) || (!family && ["SUBMITTED","APPROVED"].includes(detail.request.status))) && <form className="request-reply" onSubmit={decide}>
            {!family && <label>Decision<select name="decision" disabled={busy}>{detail.request.status === "APPROVED" ? <option value="REVOKED">Revoke approval</option> : <><option value="APPROVED">Approve leave</option><option value="REJECTED">Reject request</option></>}</select></label>}
            <label>{family ? "Reason for withdrawal" : "Reason for decision"}<textarea name="reason" minLength={3} maxLength={1000} required disabled={busy} /></label>
            <label className="request-check"><input type="checkbox" required disabled={busy} /> I confirm this {family ? "withdrawal" : "decision"}. Published attendance must use its separate correction process.</label>
            <button disabled={busy}>{family ? "Withdraw leave request" : "Record independent decision"}</button>
          </form>}
        </>}
      </section></div>
    </>}
  </>;
}
