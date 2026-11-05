"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { apiUrl, parentRequestMutation as mutate, parentRequestProblem as problem } from "@/lib/parent-requests";
import { ptmLocalInstant, ptmTime, type PtmStaffEvent, type PtmWorkspaceData } from "@/lib/ptm";
import styles from "@/components/ptm.module.css";

export function PtmWorkspace() {
  const [data, setData] = useState<PtmWorkspaceData | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [sessionId, setSessionId] = useState("");
  const [sectionId, setSectionId] = useState("");
  const [preview, setPreview] = useState<Record<string, unknown> | null>(null);
  const [decision, setDecision] = useState<{ event: PtmStaffEvent; action: "publish" | "cancel" } | null>(null);
  const retryKeys = useRef(new Map<string, string>());
  const load = useCallback(async (signal?: AbortSignal) => {
    const response = await fetch(`${apiUrl}/api/v1/ptm`, { credentials: "include", cache: "no-store", signal });
    if (!response.ok) throw new Error(await problem(response, "Meeting workspace is unavailable."));
    const result = await response.json() as PtmWorkspaceData;
    if (!signal?.aborted) { setData(result); setError(""); }
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    void Promise.resolve().then(() => load(controller.signal)).catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "Meeting workspace is unavailable."); });
    return () => controller.abort();
  }, [load]);
  const section = data?.sections.find((item) => item.id === sectionId);

  function prepare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !section) return;
    setError(""); setNotice("");
    try {
      const form = new FormData(event.currentTarget);
      const text = (key: string) => String(form.get(key) ?? "");
      const instant = (key: string) => ptmLocalInstant(text(key), section.timeZone);
      const body = {
        schoolId: data.schoolId, academicSessionId: sessionId, classSectionId: sectionId,
        teacherMembershipId: text("teacherMembershipId"), titleEn: text("titleEn"), titleHi: text("titleHi") || null,
        instructionsEn: text("instructionsEn") || null, instructionsHi: text("instructionsHi") || null,
        venue: text("venue"), eventDate: text("eventDate"), bookingOpensAt: instant("bookingOpensAt"),
        bookingClosesAt: instant("bookingClosesAt"), changesCloseAt: instant("changesCloseAt"),
        firstSlotStartsAt: ptmLocalInstant(`${text("eventDate")}T${text("firstSlotTime")}`, section.timeZone),
        reminderMinutesBefore: Number(text("reminderMinutesBefore")), slotDurationMinutes: Number(text("slotDurationMinutes")),
        gapMinutes: Number(text("gapMinutes")), slotCount: Number(text("slotCount")),
      };
      if (Date.parse(body.bookingOpensAt) >= Date.parse(body.bookingClosesAt)
          || Date.parse(body.bookingClosesAt) > Date.parse(body.changesCloseAt)
          || Date.parse(body.changesCloseAt) > Date.parse(body.firstSlotStartsAt)) throw new Error("Booking must open before it closes. Set the change deadline between booking closure and the first appointment.");
      setPreview(body);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Check the meeting details."); }
  }

  async function saveDraft() {
    if (!preview || busy) return;
    setBusy(true); setError("");
    const fingerprint = JSON.stringify(preview);
    const key = retryKeys.current.get(fingerprint) ?? crypto.randomUUID();
    retryKeys.current.set(fingerprint, key);
    try {
      await mutate("/api/v1/ptm/events", { ...preview, idempotencyKey: key });
      setPreview(null); setNotice("Draft created. Review its appointment times before publishing."); await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Draft could not be saved."); }
    finally { setBusy(false); }
  }

  async function decide(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !decision || busy) return;
    const reason = String(new FormData(event.currentTarget).get("reason") ?? "");
    setBusy(true); setError(""); setNotice("");
    try {
      await mutate(`/api/v1/ptm/events/${decision.event.id}/${decision.action}`, {
        schoolId: data.schoolId, expectedVersion: decision.event.rowVersion, reason,
      });
      setNotice(decision.action === "publish" ? "Meeting published for eligible families." : "Meeting and its appointments cancelled.");
      setDecision(null); await load();
    } catch (failure) { setError(failure instanceof Error ? failure.message : "The change could not be saved."); }
    finally { setBusy(false); }
  }

  return <section className={styles.centre}>
    <header className={styles.hero}><span>Parent-teacher meetings</span><h1>Time for each family.</h1><p>{data?.canManage ? "Prepare a class meeting, review its appointment times, then publish for family booking." : "Your meeting schedule and booked appointments appear here."}</p></header>
    {error && <div role="alert" className={styles.error}>{error}<button disabled={busy} onClick={() => void load().catch((e: unknown) => setError(e instanceof Error ? e.message : "Unable to refresh."))}>Refresh</button></div>}
    {notice && <p role="status" className={styles.confirmation}>{notice}</p>}
    {!data && !error && <p role="status">Loading meeting workspace…</p>}
    {data?.canManage && <details className={styles.card}><summary>Create a meeting schedule</summary>
      <form onSubmit={prepare} onChange={() => setPreview(null)}><fieldset disabled={busy} className={styles.slots}><legend>Meeting details</legend><div className={styles.grid}>
        <label className={styles.field}>Academic session<select required value={sessionId} onChange={(event) => { setSessionId(event.target.value); setSectionId(""); }}><option value="">Choose session</option>{data.sessions.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label className={styles.field}>Class / section<select required value={sectionId} onChange={(event) => setSectionId(event.target.value)}><option value="">Choose class</option>{data.sections.filter((item) => item.sessionId === sessionId).map((item) => <option key={item.id} value={item.id}>{item.gradeCode} · {item.name}</option>)}</select></label>
        <label className={styles.field}>Teacher<select name="teacherMembershipId" required><option value="">Choose teacher</option>{data.teachers.map((item) => <option key={item.membershipId} value={item.membershipId}>{item.name} · {item.employeeCode}</option>)}</select></label>
        <label className={styles.field}>Venue<input name="venue" required minLength={2} maxLength={160} /></label>
        <label className={styles.field}>Title<input name="titleEn" required minLength={3} maxLength={160} /></label>
        <label className={styles.field}>Title in Hindi (optional)<input name="titleHi" lang="hi" minLength={3} maxLength={160} /></label>
        <label className={styles.field}>Instructions (optional)<textarea name="instructionsEn" minLength={3} maxLength={1200} rows={3} /></label>
        <label className={styles.field}>Instructions in Hindi (optional)<textarea name="instructionsHi" lang="hi" minLength={3} maxLength={1200} rows={3} /></label>
      </div></fieldset>
      <p>Enter all dates and times in {section?.timeZone ?? "the selected class campus timezone"}.</p>
      <div className={styles.grid}>
        <label className={styles.field}>Meeting date<input type="date" name="eventDate" required min={data.sessions.find((s) => s.id === sessionId)?.startsOn} max={data.sessions.find((s) => s.id === sessionId)?.endsOn} disabled={busy} /></label>
        <label className={styles.field}>First appointment<input type="time" name="firstSlotTime" required disabled={busy} /></label>
        {[["bookingOpensAt", "Booking opens"], ["bookingClosesAt", "Booking closes"], ["changesCloseAt", "Family change deadline"]].map(([name, title]) => <label className={styles.field} key={name}>{title}<input type="datetime-local" name={name} required disabled={busy} /></label>)}
        <label className={styles.field}>Appointment length (minutes)<input type="number" name="slotDurationMinutes" min={5} max={120} defaultValue={10} required disabled={busy} /></label>
        <label className={styles.field}>Gap between appointments (minutes)<input type="number" name="gapMinutes" min={0} max={60} defaultValue={5} required disabled={busy} /></label>
        <label className={styles.field}>Number of appointments<input type="number" name="slotCount" min={1} max={100} defaultValue={12} required disabled={busy} /></label>
        <label className={styles.field}>Reminder lead time (minutes; 0 disables)<input type="number" name="reminderMinutesBefore" min={0} max={10080} defaultValue={1440} required disabled={busy} /></label>
      </div><button disabled={busy || !section}>Preview draft</button></form>
      {preview && section && <section className={styles.confirmation} aria-label="Meeting draft preview"><h3>Review {String(preview.titleEn)}</h3><p>{String(preview.slotCount)} appointments, {String(preview.slotDurationMinutes)} minutes each, with {String(preview.gapMinutes)} minute gaps. First appointment: {ptmTime(String(preview.firstSlotStartsAt), section.timeZone)}.</p><p>After saving, review all generated times below before publishing.</p><button onClick={() => void saveDraft()} disabled={busy}>{busy ? "Saving…" : "Save draft"}</button></section>}
    </details>}
    {decision && <form className={styles.card} onSubmit={decide} aria-label="Confirm meeting decision"><h2>{decision.action === "publish" ? "Publish" : "Cancel"} {decision.event.titleEn}?</h2><p>{decision.action === "publish" ? "Families in this class will be able to book within the configured window." : "All active appointments will be cancelled. Their history will remain available."}</p><label className={styles.field}>Reason<input autoFocus name="reason" minLength={3} maxLength={500} required disabled={busy} /></label><div className={styles.actions}><button disabled={busy}>Confirm {decision.action}</button><button type="button" disabled={busy} onClick={() => setDecision(null)}>Go back</button></div></form>}
    {data?.events.length === 0 && <div className={styles.card}><h2>No meeting schedules yet</h2><p>{data.canManage ? "Create a meeting when the session, class and teacher are ready." : "Your assigned meetings will appear here."}</p></div>}
    {data?.events.map((event) => <article key={event.id} className={styles.card}><span className={styles.badge}>{event.status.toLowerCase()}</span><h2>{event.titleEn}</h2><p>{event.teacherName} · {event.className} · {event.venue} · {event.eventDate}</p><p>All times: {event.timeZone}. Booking closes {ptmTime(event.bookingClosesAt, event.timeZone)}.</p>
      <div className={styles.scroll} tabIndex={0} role="region" aria-label={`${event.titleEn} appointments`}><table className={styles.table}><caption>Appointment schedule</caption><thead><tr><th scope="col">Time</th><th scope="col">Student</th><th scope="col">Guardian</th><th scope="col">Topic / status</th></tr></thead><tbody>{event.slots.map((slot) => <tr key={slot.id}><td>{ptmTime(slot.startsAt, event.timeZone)}</td><td>{slot.booking?.learnerName ?? "—"}</td><td>{slot.booking?.guardianName ?? "—"}</td><td>{slot.booking?.topic ?? (slot.booking ? "Booked" : slot.status === "CANCELLED" ? "Cancelled" : "Available")}</td></tr>)}</tbody></table></div>
      {data.canManage && event.status !== "CANCELLED" && <div className={styles.actions}><button disabled={busy} onClick={() => setDecision({ event, action: event.status === "DRAFT" ? "publish" : "cancel" })}>{event.status === "DRAFT" ? "Review publication" : "Cancel meeting"}</button></div>}
    </article>)}
  </section>;
}
