"use client";

import { useCodeLabel, useT } from "@/components/i18n/language-provider";
import { LocalizedForm } from "@/components/i18n/localized-form";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Check, Shield, Sparkles } from "@/components/icons";

type Session = { id: string; name: string; startsOn: string; endsOn: string; status: string };
type Campus = { id: string; name: string; timeZone: string };
type Staff = { id: string; employeeCode: string; displayName: string; campusId?: string; designation?: string; joinedOn: string; status: string };
type CalendarDay = { id: string; campusId: string; sessionId: string; date: string; dayType: string; name: string; attendanceRequired: boolean; status: string };
type Policy = { id: string; sessionId: string; campusId: string; name: string; enabledStates: string[]; stateWeights: Record<string, number>; lockAfterHours: number; warningThresholdPercent: number; consecutiveAbsenceThreshold: number; status: string; rowVersion: number };
type LeaveType = { id: string; code: string; name: string; paid: boolean; balanceRequired: boolean; maxDaysPerRequest: number; status: string; rowVersion: number };
type Balance = { staffProfileId: string; leaveTypeId: string; sessionId: string; availableDays: number };
type LeaveRequest = { id: string; staffProfileId: string; leaveTypeId: string; sessionId: string; startsOn: string; endsOn: string; duration: string; requestedDays: number; reason: string; status: string; requesterName: string; deciderName?: string; decisionReason?: string; createdAt: string; rowVersion: number };
type Mark = { id: string; staffProfileId: string; staffName: string; employeeCode: string; stateCode: string; checkInTime?: string; checkOutTime?: string; note?: string; rowVersion: number };
type Register = { id: string; campusId: string; sessionId: string; calendarDayId: string; date: string; dayName: string; enabledStates: string[]; status: string; expectedStaffCount: number; recordedCount: number; absentCount: number; submittedAt?: string; rowVersion: number; marks: Mark[] };
type Correction = { id: string; markId: string; staffProfileId: string; staffName: string; currentState: string; requestedState: string; requestedCheckInTime?: string; requestedCheckOutTime?: string; requestReason: string; status: string; requesterName: string; createdAt: string; rowVersion: number };
type Summary = { staffProfileId: string; sessionId: string; recordedDays: number; attendancePercent: number; consecutiveAbsences: number; belowThreshold: boolean; consecutiveAbsenceAlert: boolean };
type Snapshot = { schoolId: string; schoolName: string; canReadAll: boolean; canRecordAttendance: boolean; canCorrectAttendance: boolean; canApproveLeave: boolean; canConfigure: boolean; selfStaffProfileId?: string; sessions: Session[]; campuses: Campus[]; staff: Staff[]; calendarDays: CalendarDay[]; policies: Policy[]; leaveTypes: LeaveType[]; leaveBalances: Balance[]; leaveRequests: LeaveRequest[]; registers: Register[]; corrections: Correction[]; attendanceSummaries: Summary[] };
type DraftMark = { stateCode: string; checkInTime: string; checkOutTime: string; note: string; expectedVersion?: number };
type Tab = "leave" | "attendance" | "approvals" | "setup";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const states = ["PRESENT", "ABSENT", "LATE", "LEAVE", "HALF_DAY", "OFFICIAL_DUTY"];
const defaultWeights: Record<string, number> = { PRESENT: 1, ABSENT: 0, LATE: 0.75, LEAVE: 1, HALF_DAY: 0.5, OFFICIAL_DUTY: 1 };
const localToday = () => { const value = new Date(); value.setMinutes(value.getMinutes() - value.getTimezoneOffset()); return value.toISOString().slice(0, 10); };
const formatCode = (value?: string) => value ? value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase()) : "Not set";
const cookie = (name: string) => document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new Error("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string) { try { return ((await response.json()) as { detail?: string }).detail || fallback; } catch { return fallback; } }

export function WorkforceWorkspace() {
  const t = useT("attendance");
  const label = useCodeLabel(formatCode);
  const [date, setDate] = useState(localToday);
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<Tab>("leave");
  const [activeRegisterId, setActiveRegisterId] = useState("");
  const [draftMarks, setDraftMarks] = useState<Record<string, DraftMark>>({});
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [selectedStates, setSelectedStates] = useState(states);
  const [editingPolicyId, setEditingPolicyId] = useState("");
  const [editingLeaveTypeId, setEditingLeaveTypeId] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/workforce?date=${date}`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "Staff attendance and leave are unavailable."));
    return (await response.json()) as Snapshot;
  }, [date]);

  const refresh = useCallback(async () => {
    const result = await load();
    setData(result);
    setActiveRegisterId((current) => result.registers.some((item) => item.id === current) ? current : result.registers[0]?.id || "");
    return result;
  }, [load]);

  useEffect(() => {
    let cancelled = false;
    load().then((result) => { if (!cancelled) { setData(result); setActiveRegisterId(result.registers[0]?.id || ""); } })
      .catch((reason: Error) => { if (!cancelled) setError(reason.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [load]);

  const mutate = useCallback(async (path: string, method: "POST" | "PUT", body: object, success: string) => {
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiUrl}/api/v1/workforce${path}`, { method, credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken() }, body: JSON.stringify(body) });
      if (!response.ok) throw new Error(await problem(response, "The workforce change could not be saved."));
      await refresh(); setNotice(success); return await response.json() as { id: string; rowVersion: number };
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The workforce change could not be saved."); return null; }
    finally { setBusy(false); }
  }, [refresh]);

  const activeRegister = data?.registers.find((item) => item.id === activeRegisterId);
  const selectedDay = data?.calendarDays.find((item) => item.date === date && item.status === "ACTIVE" && item.attendanceRequired);
  const roster = useMemo(() => data?.staff.filter((item) => item.status === "ACTIVE" && item.campusId === activeRegister?.campusId) || [], [data, activeRegister]);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (cancelled) return;
      if (!activeRegister) { setDraftMarks({}); return; }
      const existing = new Map(activeRegister.marks.map((mark) => [mark.staffProfileId, mark]));
      setDraftMarks(Object.fromEntries(roster.map((staff) => { const mark = existing.get(staff.id); return [staff.id, { stateCode: mark?.stateCode || "PRESENT", checkInTime: mark?.checkInTime || "", checkOutTime: mark?.checkOutTime || "", note: mark?.note || "", expectedVersion: mark?.rowVersion }]; })));
    });
    return () => { cancelled = true; };
  }, [activeRegister, roster]);

  async function submitLeave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const result = await mutate("/leave-requests", "POST", { schoolId: data.schoolId, sessionId: form.get("sessionId"), leaveTypeId: form.get("leaveTypeId"), startsOn: form.get("startsOn"), endsOn: form.get("endsOn"), duration: form.get("duration"), idempotencyKey: crypto.randomUUID(), reason: form.get("reason") }, "Leave request sent for independent review.");
    if (result) formElement.reset();
  }

  async function decide(kind: "leave-requests" | "attendance-corrections", id: string, decision: string, version: number) {
    if (!data) return; const reason = window.prompt(t("Reason for {decision}:", { decision: label(decision) })); if (!reason?.trim()) return;
    await mutate(`/${kind}/${id}/decision`, "POST", { schoolId: data.schoolId, decision, reason, expectedVersion: version }, t("{decision} with protected history.", { decision: label(decision) }));
  }

  async function openRegister() {
    if (!data || !selectedDay) return;
    const result = await mutate("/attendance-registers", "POST", { schoolId: data.schoolId, calendarDayId: selectedDay.id }, "Staff register opened from the dated active roster.");
    if (result) setActiveRegisterId(result.id);
  }

  async function saveMarks() {
    if (!data || !activeRegister) return;
    await mutate(`/attendance-registers/${activeRegister.id}/marks`, "PUT", { schoolId: data.schoolId, expectedRegisterVersion: activeRegister.rowVersion, marks: roster.map((staff) => ({ staffProfileId: staff.id, ...draftMarks[staff.id], expectedVersion: draftMarks[staff.id]?.expectedVersion ?? null })), reason: "Daily staff roll call reviewed before submission" }, "Staff attendance draft saved with history.");
  }

  async function submitRegister() {
    if (!data || !activeRegister || !window.confirm("Submit this complete register? Later changes require an approved correction.")) return;
    await mutate(`/attendance-registers/${activeRegister.id}/submit`, "POST", { schoolId: data.schoolId, expectedVersion: activeRegister.rowVersion, reason: "Completed staff register reviewed and submitted" }, "Register submitted. Corrections are now controlled.");
  }

  async function requestCorrection(mark: Mark) {
    if (!data) return; const requestedState = window.prompt(t("Requested state"), mark.stateCode); const reason = window.prompt(t("Reason for correction")); if (!requestedState || !reason?.trim()) return;
    await mutate("/attendance-corrections", "POST", { schoolId: data.schoolId, markId: mark.id, requestedState, requestedCheckInTime: mark.checkInTime || null, requestedCheckOutTime: mark.checkOutTime || null, reason }, "Attendance correction sent for independent review.");
  }

  async function savePolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const existing = data.policies.find((item) => item.id === editingPolicyId);
    const result = await mutate(existing ? `/attendance-policies/${existing.id}` : "/attendance-policies", existing ? "PUT" : "POST", { schoolId: data.schoolId, sessionId: form.get("sessionId"), campusId: form.get("campusId"), name: form.get("name"), enabledStates: selectedStates, stateWeights: Object.fromEntries(selectedStates.map((state) => [state, Number(form.get(`weight-${state}`))])), lockAfterHours: Number(form.get("lockAfterHours")), warningThresholdPercent: Number(form.get("warningThresholdPercent")), consecutiveAbsenceThreshold: Number(form.get("consecutiveAbsenceThreshold")), status: form.get("status"), expectedVersion: existing?.rowVersion ?? null, reason: existing ? "Workforce policy revised by school authority" : "Workforce policy configured by school authority" }, existing ? "Staff attendance policy updated; existing registers keep their frozen policy." : "Staff attendance policy configured.");
    if (result) { setEditingPolicyId(""); setSelectedStates(states); formElement.reset(); }
  }

  async function saveLeaveType(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const existing = data.leaveTypes.find((item) => item.id === editingLeaveTypeId);
    const result = await mutate(existing ? `/leave-types/${existing.id}` : "/leave-types", existing ? "PUT" : "POST", { schoolId: data.schoolId, code: form.get("code"), name: form.get("name"), paid: form.get("paid") === "on", balanceRequired: form.get("balanceRequired") === "on", maxDaysPerRequest: Number(form.get("maxDaysPerRequest")), status: form.get("status"), expectedVersion: existing?.rowVersion ?? null, reason: existing ? "Leave type revised by school authority" : "Leave type configured by school authority" }, existing ? "Leave type updated without rewriting submitted leave evidence." : "Leave type configured.");
    if (result) { setEditingLeaveTypeId(""); formElement.reset(); }
  }

  async function adjustBalance(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const form = new FormData(event.currentTarget);
    await mutate("/leave-balances", "POST", { schoolId: data.schoolId, sessionId: form.get("sessionId"), staffProfileId: form.get("staffProfileId"), leaveTypeId: form.get("leaveTypeId"), entryKind: form.get("entryKind"), quantity: Number(form.get("quantity")), idempotencyKey: crypto.randomUUID(), reason: form.get("reason") }, "Append-only leave balance entry posted.");
  }

  if (loading) return <section className="workforce-loading" aria-live="polite"><span /><p>{t("Opening protected workforce records…")}</p></section>;
  if (!data) return <section className="workforce-error"><h1>{t("Workforce is unavailable")}</h1><p>{t(error)}</p><button onClick={() => window.location.reload()}>{t("Retry")}</button></section>;

  const ownSummary = data.attendanceSummaries.find((item) => item.staffProfileId === data.selfStaffProfileId);
  const pendingLeaves = data.leaveRequests.filter((item) => item.status === "SUBMITTED");
  const pendingCorrections = data.corrections.filter((item) => item.status === "PENDING");
  const editingPolicy = data.policies.find((item) => item.id === editingPolicyId);
  const editingLeaveType = data.leaveTypes.find((item) => item.id === editingLeaveTypeId);
  const tabs: Array<{ id: Tab; label: string; show: boolean }> = [
    { id: "leave", label: t(data.canReadAll ? "Leave records" : "My leave"), show: true },
    { id: "attendance", label: t(data.canRecordAttendance ? "Staff attendance" : "My attendance"), show: data.canReadAll || !!data.selfStaffProfileId },
    { id: "approvals", label: t("Approvals ({count})", { count: pendingLeaves.length + pendingCorrections.length }), show: data.canApproveLeave || data.canCorrectAttendance },
    { id: "setup", label: t("Setup"), show: data.canConfigure },
  ];

  return <section className="workforce-page">
    <header className="workforce-hero"><div><span><Sparkles /> {t("Workforce operations")}</span><h1>{t("Attendance and leave that stay reconciled")}</h1><p>{t("Self-service remains private. Management sees the school roster, and every correction, approval, and balance change keeps evidence.")}</p></div><div className="workforce-date"><label htmlFor="workforce-date">{t("Working date")}</label><input id="workforce-date" type="date" value={date} onChange={(event) => setDate(event.target.value)} /></div></header>
    <div className="workforce-metrics"><article><span>{t("Present evidence")}</span><strong>{ownSummary ? `${ownSummary.attendancePercent}%` : "—"}</strong><small>{ownSummary ? t("{count} submitted days", { count: ownSummary.recordedDays }) : t("No submitted marks yet")}</small></article><article><span>{t("Pending leave")}</span><strong>{pendingLeaves.length}</strong><small>{t(data.canApproveLeave ? "Need independent decisions" : "Awaiting management")}</small></article><article><span>{t("Corrections")}</span><strong>{pendingCorrections.length}</strong><small>{t("Submitted history stays locked")}</small></article></div>
    {(error || notice) && <div className={`workforce-banner ${error ? "is-error" : "is-success"}`} role="status">{t(error || notice)}<button onClick={() => { setError(""); setNotice(""); }} aria-label={t("Dismiss message")}>×</button></div>}
    <nav className="workforce-tabs" aria-label={t("Workforce sections")}>{tabs.filter((item) => item.show).map((item) => <button key={item.id} className={tab === item.id ? "is-active" : ""} onClick={() => setTab(item.id)}>{item.label}</button>)}</nav>

    {tab === "leave" && <div className="workforce-leave-grid"><section className="workforce-panel"><header><div><span>{t("Simple self-service")}</span><h2>{t("Request leave")}</h2></div><Shield /></header>{data.selfStaffProfileId ? <LocalizedForm onSubmit={submitLeave} className="workforce-form"><label>{t("Session")}<select name="sessionId" required><option value="">{t("Choose…")}</option>{data.sessions.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>{t("Leave type")}<select name="leaveTypeId" required><option value="">{t("Choose…")}</option>{data.leaveTypes.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>{t("From")}<input name="startsOn" type="date" min={localToday()} required /></label><label>{t("To")}<input name="endsOn" type="date" min={localToday()} required /></label><label>{t("Duration")}<select name="duration" defaultValue="FULL_DAY"><option value="FULL_DAY">{t("Full day")}</option><option value="FIRST_HALF">{t("First half")}</option><option value="SECOND_HALF">{t("Second half")}</option></select></label><label className="is-wide">{t("Reason")}<textarea name="reason" minLength={3} maxLength={1000} required /></label><button disabled={busy}>{t("Send for review")}</button></LocalizedForm> : <p className="workforce-empty">{t("Link this login to an active staff profile before requesting leave.")}</p>}</section><section className="workforce-panel"><header><div><span>{t("Current evidence")}</span><h2>{data.canReadAll ? t("Leave queue") : t("My requests")}</h2></div></header><div className="workforce-list">{data.leaveRequests.length ? data.leaveRequests.map((item) => <article key={item.id}><div><strong>{data.staff.find((staff) => staff.id === item.staffProfileId)?.displayName || "Staff member"}</strong><span>{item.startsOn} → {item.endsOn} · {item.requestedDays} {t(item.requestedDays === 1 ? "day" : "days")}</span><small>{data.leaveTypes.find((type) => type.id === item.leaveTypeId)?.name || "Leave"} · {item.reason}</small></div><em data-state={item.status}>{label(item.status)}</em></article>) : <p className="workforce-empty">{t("No leave request has been submitted.")}</p>}</div></section><section className="workforce-panel workforce-balances"><header><div><span>{t("Append-only ledger")}</span><h2>{t("Available balances")}</h2></div></header>{data.leaveBalances.length ? data.leaveBalances.map((item) => <article key={`${item.staffProfileId}-${item.leaveTypeId}-${item.sessionId}`}><span>{data.staff.find((staff) => staff.id === item.staffProfileId)?.displayName || "My balance"}<small>{data.leaveTypes.find((type) => type.id === item.leaveTypeId)?.name}</small></span><strong>{item.availableDays}</strong></article>) : <p className="workforce-empty">{t("No tracked balance has been posted.")}</p>}</section></div>}

    {tab === "attendance" && <div className="workforce-attendance"><section className="workforce-toolbar"><label>{t("Date")}<input type="date" value={date} onChange={(event) => setDate(event.target.value)} /></label>{data.canRecordAttendance && !data.registers.length && <button disabled={busy || !selectedDay} onClick={openRegister}>{selectedDay ? t("Open dated register") : t("No attendance-required day")}</button>}<select value={activeRegisterId} onChange={(event) => setActiveRegisterId(event.target.value)} aria-label={t("Attendance register")}><option value="">{t("Choose register…")}</option>{data.registers.map((item) => <option key={item.id} value={item.id}>{data.campuses.find((campus) => campus.id === item.campusId)?.name} · {label(item.status)}</option>)}</select></section>{activeRegister ? <section className="workforce-register"><header><div><span>{activeRegister.dayName}</span><h2>{data.campuses.find((item) => item.id === activeRegister.campusId)?.name} {t("staff register")}</h2><p>{activeRegister.recordedCount}/{activeRegister.expectedStaffCount} {t("recorded ·")} {activeRegister.absentCount} {t("absent")}</p></div><em data-state={activeRegister.status}>{label(activeRegister.status)}</em></header><div className="workforce-roster">{(data.canRecordAttendance && activeRegister.status === "DRAFT" ? roster : activeRegister.marks.map((mark) => data.staff.find((staff) => staff.id === mark.staffProfileId)).filter(Boolean) as Staff[]).map((staff) => { const draft = draftMarks[staff.id]; const mark = activeRegister.marks.find((item) => item.staffProfileId === staff.id); return <article key={staff.id}><div><strong>{staff.displayName}</strong><small>{staff.employeeCode} · {staff.designation || "Staff"}</small></div>{data.canRecordAttendance && activeRegister.status === "DRAFT" && draft ? <><select value={draft.stateCode} onChange={(event) => setDraftMarks((current) => ({ ...current, [staff.id]: { ...draft, stateCode: event.target.value } }))}>{activeRegister.enabledStates.map((state) => <option key={state} value={state}>{label(state)}</option>)}</select><input type="time" aria-label={t("{name} check in", { name: staff.displayName })} value={draft.checkInTime} onChange={(event) => setDraftMarks((current) => ({ ...current, [staff.id]: { ...draft, checkInTime: event.target.value } }))} /></> : <><em data-state={mark?.stateCode}>{label(mark?.stateCode || "UNRECORDED")}</em>{mark && activeRegister.status !== "DRAFT" && <button onClick={() => requestCorrection(mark)}>{t("Request correction")}</button>}</>}</article>; })}</div>{data.canRecordAttendance && activeRegister.status === "DRAFT" && <footer><button disabled={busy} onClick={saveMarks}>{t("Save draft")}</button><button disabled={busy || activeRegister.recordedCount !== activeRegister.expectedStaffCount} onClick={submitRegister}>{t("Submit & lock")}</button></footer>}</section> : <div className="workforce-empty-card"><Check /><h2>{t("No register for this date")}</h2><p>{t("Only an authorized recorder can open one, and only on an attendance-required calendar day.")}</p></div>}</div>}

    {tab === "approvals" && <div className="workforce-approval-grid">{data.canApproveLeave && <section className="workforce-panel"><header><div><span>{t("Independent decisions")}</span><h2>{t("Leave approvals")}</h2></div></header><div className="workforce-list">{pendingLeaves.length ? pendingLeaves.map((item) => <article key={item.id}><div><strong>{data.staff.find((staff) => staff.id === item.staffProfileId)?.displayName}</strong><span>{item.startsOn} → {item.endsOn} · {item.requestedDays} {t("days")}</span><small>{item.reason}</small></div><div><button disabled={busy} onClick={() => decide("leave-requests", item.id, "APPROVED", item.rowVersion)}>{t("Approve")}</button><button className="is-danger" disabled={busy} onClick={() => decide("leave-requests", item.id, "REJECTED", item.rowVersion)}>{t("Reject")}</button></div></article>) : <p className="workforce-empty">{t("No leave approval is pending.")}</p>}</div></section>}{data.canCorrectAttendance && <section className="workforce-panel"><header><div><span>{t("Submitted history")}</span><h2>{t("Attendance corrections")}</h2></div></header><div className="workforce-list">{pendingCorrections.length ? pendingCorrections.map((item) => <article key={item.id}><div><strong>{item.staffName}</strong><span>{label(item.currentState)} → {label(item.requestedState)}</span><small>{item.requestReason} {t("· requested by")} {item.requesterName}</small></div><div><button disabled={busy} onClick={() => decide("attendance-corrections", item.id, "APPROVED", item.rowVersion)}>{t("Approve")}</button><button className="is-danger" disabled={busy} onClick={() => decide("attendance-corrections", item.id, "REJECTED", item.rowVersion)}>{t("Reject")}</button></div></article>) : <p className="workforce-empty">{t("No attendance correction is pending.")}</p>}</div></section>}</div>}

    {tab === "setup" && data.canConfigure && <div className="workforce-setup">
      <section className="workforce-panel"><header><div><span>{t("Frozen per register")}</span><h2>{t("Attendance policy")}</h2></div></header>
        <div className="workforce-config-list">{data.policies.map((policy) => <article key={policy.id}><div><strong>{policy.name}</strong><small>{data.sessions.find((item) => item.id === policy.sessionId)?.name} · {data.campuses.find((item) => item.id === policy.campusId)?.name} · {label(policy.status)}</small></div><button type="button" onClick={() => { setEditingPolicyId(policy.id); setSelectedStates(policy.enabledStates); }}>{t("Edit")}</button></article>)}</div>
        <LocalizedForm key={editingPolicy?.id || "new-policy"} onSubmit={savePolicy} className="workforce-form">
          <label>{t("Session")}<select name="sessionId" defaultValue={editingPolicy?.sessionId || ""} disabled={!!editingPolicy} required><option value="">{t("Choose…")}</option>{data.sessions.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select>{editingPolicy && <input type="hidden" name="sessionId" value={editingPolicy.sessionId} />}</label>
          <label>{t("Campus")}<select name="campusId" defaultValue={editingPolicy?.campusId || ""} disabled={!!editingPolicy} required><option value="">{t("Choose…")}</option>{data.campuses.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select>{editingPolicy && <input type="hidden" name="campusId" value={editingPolicy.campusId} />}</label>
          <label className="is-wide">{t("Policy name")}<input name="name" defaultValue={editingPolicy?.name} required /></label>
          <fieldset className="is-wide"><legend>{t("Enabled states and attendance credit")}</legend>{states.map((state) => <label key={state} className="workforce-state"><input type="checkbox" checked={selectedStates.includes(state)} disabled={["PRESENT", "ABSENT", "LEAVE"].includes(state)} onChange={(event) => setSelectedStates((current) => event.target.checked ? [...current, state] : current.filter((item) => item !== state))} />{label(state)}<input name={`weight-${state}`} type="number" min="0" max="1" step="0.01" defaultValue={editingPolicy?.stateWeights[state] ?? defaultWeights[state]} disabled={!selectedStates.includes(state)} aria-label={t("{state} attendance credit", { state: label(state) })} /></label>)}</fieldset>
          <label>{t("Lock after hours")}<input name="lockAfterHours" type="number" min="0" max="720" defaultValue={editingPolicy?.lockAfterHours ?? 24} required /></label>
          <label>{t("Warning below %")}<input name="warningThresholdPercent" type="number" min="0" max="100" step="0.01" defaultValue={editingPolicy?.warningThresholdPercent ?? 75} required /></label>
          <label>{t("Consecutive absence alert")}<input name="consecutiveAbsenceThreshold" type="number" min="1" max="30" defaultValue={editingPolicy?.consecutiveAbsenceThreshold ?? 3} required /></label>
          <label>{t("Status")}<select name="status" defaultValue={editingPolicy?.status || "ACTIVE"}><option value="ACTIVE">{t("Active")}</option><option value="INACTIVE">{t("Inactive")}</option></select></label>
          <div className="workforce-form-actions is-wide"><button disabled={busy}>{editingPolicy ? t("Save policy changes") : "Create policy"}</button>{editingPolicy && <button type="button" className="is-secondary" onClick={() => { setEditingPolicyId(""); setSelectedStates(states); }}>{t("Cancel")}</button>}</div>
        </LocalizedForm>
      </section>
      <section className="workforce-panel"><header><div><span>{t("School configurable")}</span><h2>{t("Leave types")}</h2></div></header>
        <div className="workforce-config-list">{data.leaveTypes.map((type) => <article key={type.id}><div><strong>{type.name}</strong><small>{type.code} · {label(type.status)} {t("· max")} {type.maxDaysPerRequest} {t("days")}</small></div><button type="button" onClick={() => setEditingLeaveTypeId(type.id)}>{t("Edit")}</button></article>)}</div>
        <LocalizedForm key={editingLeaveType?.id || "new-leave-type"} onSubmit={saveLeaveType} className="workforce-form">
          <label>{t("Code")}<input name="code" placeholder="CASUAL_LEAVE" defaultValue={editingLeaveType?.code} disabled={!!editingLeaveType} required />{editingLeaveType && <input type="hidden" name="code" value={editingLeaveType.code} />}</label>
          <label>{t("Name")}<input name="name" defaultValue={editingLeaveType?.name} required /></label>
          <label>{t("Maximum days/request")}<input name="maxDaysPerRequest" type="number" min="0.5" max="366" step="0.5" defaultValue={editingLeaveType?.maxDaysPerRequest ?? 5} required /></label>
          <label>{t("Status")}<select name="status" defaultValue={editingLeaveType?.status || "ACTIVE"}><option value="ACTIVE">{t("Active")}</option><option value="INACTIVE">{t("Inactive")}</option></select></label>
          <label className="workforce-check"><input name="paid" type="checkbox" defaultChecked={editingLeaveType?.paid ?? true} /> {t("Paid leave")}</label>
          <label className="workforce-check"><input name="balanceRequired" type="checkbox" defaultChecked={editingLeaveType?.balanceRequired ?? true} /> {t("Track balance")}</label>
          <div className="workforce-form-actions is-wide"><button disabled={busy}>{editingLeaveType ? t("Save leave type") : t("Create leave type")}</button>{editingLeaveType && <button type="button" className="is-secondary" onClick={() => setEditingLeaveTypeId("")}>{t("Cancel")}</button>}</div>
        </LocalizedForm>
      </section>
      <section className="workforce-panel"><header><div><span>{t("No silent total edits")}</span><h2>{t("Post balance entry")}</h2></div></header><LocalizedForm onSubmit={adjustBalance} className="workforce-form"><label>{t("Session")}<select name="sessionId" required><option value="">{t("Choose…")}</option>{data.sessions.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>{t("Staff")}<select name="staffProfileId" required><option value="">{t("Choose…")}</option>{data.staff.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}</select></label><label>{t("Leave type")}<select name="leaveTypeId" required><option value="">{t("Choose…")}</option>{data.leaveTypes.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>{t("Entry kind")}<select name="entryKind"><option>OPENING</option><option>ACCRUAL</option><option>ADJUSTMENT</option><option>REVERSAL</option></select></label><label>{t("Days (+ or −)")}<input name="quantity" type="number" min="-366" max="366" step="0.5" required /></label><label className="is-wide">{t("Reason")}<input name="reason" minLength={3} maxLength={500} required /></label><button disabled={busy}>{t("Post append-only entry")}</button></LocalizedForm></section>
    </div>}
  </section>;
}
