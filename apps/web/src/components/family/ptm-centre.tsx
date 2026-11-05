"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { apiUrl, parentRequestMutation as mutate, parentRequestProblem as problem } from "@/lib/parent-requests";
import { ptmTime, ptmWindow, type PtmEvent, type PtmSchedule } from "@/lib/ptm";
import styles from "@/components/ptm.module.css";

import { familyChildren, type FamilyChild as Child } from "@/lib/family-children";

export function PtmCentre() {
  const [children, setChildren] = useState<Child[]>([]);
  const [childrenLoaded, setChildrenLoaded] = useState(false);
  const [learnerId, setLearnerId] = useState("");
  const [schedule, setSchedule] = useState<PtmSchedule | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void fetch(`${apiUrl}/api/v1/family/children`, { credentials: "include", cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(await problem(response, "Linked children are unavailable."));
        const linked = familyChildren(await response.json());
        if (!controller.signal.aborted) { setChildren(linked); setLearnerId(linked[0]?.learnerId ?? ""); setChildrenLoaded(true); }
      }).catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "Family access is unavailable."); });
    return () => controller.abort();
  }, []);
  useEffect(() => {
    if (!learnerId) return;
    const controller = new AbortController();
    void fetch(`${apiUrl}/api/v1/family/children/${learnerId}/ptm`, { credentials: "include", cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(await problem(response, "Meeting schedules are unavailable."));
        const data = await response.json() as PtmSchedule;
        if (!controller.signal.aborted) { setSchedule(data); setError(""); }
      }).catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "Meeting schedules are unavailable."); });
    return () => controller.abort();
  }, [learnerId, revision]);
  return <main id="main-content" className={styles.centre}>
    <header className={styles.hero}><span>Family &amp; teacher meetings</span><h1>A little time. A shared plan.</h1><p>Choose a meeting with your child’s teacher and keep your appointment in one place.</p></header>
    <label className={styles.field}>Child<select value={learnerId} onChange={(event) => { setLearnerId(event.target.value); setSchedule(null); setError(""); }}>{children.map((child) => <option key={child.learnerId} value={child.learnerId}>{child.legalName}</option>)}</select></label>
    {error && <div className={styles.error} role="alert">{error}<button onClick={() => setRevision((value) => value + 1)}>Try again</button></div>}
    {!schedule && !error && <p role="status">{children.length ? "Loading meeting times…" : childrenLoaded ? "No children are linked to this account. Contact the school to confirm your access." : "Checking linked children…"}</p>}
    {schedule?.events.length === 0 && <section className={styles.card}><h2>No upcoming meetings</h2><p>Published meetings for this child’s class will appear here.</p></section>}
    {schedule?.learnerId === learnerId && schedule.events.map((event) => <Meeting key={`${learnerId}:${event.id}`} event={event} learnerId={learnerId} refresh={() => setRevision((value) => value + 1)} />)}
  </main>;
}

function Meeting({ event, learnerId, refresh }: { event: PtmEvent; learnerId: string; refresh: () => void }) {
  const [now, setNow] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [cancelling, setCancelling] = useState(false);
  const keys = useRef(new Map<string, string>());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 30000); return () => clearInterval(timer); }, []);
  const access = ptmWindow(event, now);
  const booked = event.booking?.status === "BOOKED";
  const currentSlot = event.slots.find((slot) => slot.id === event.booking?.slotId);

  async function submit(formEvent: FormEvent<HTMLFormElement>, cancel = false) {
    formEvent.preventDefault();
    if (busy) return;
    const values = new FormData(formEvent.currentTarget);
    const base = `/api/v1/family/children/${learnerId}/ptm`;
    const slotId = String(values.get("slotId") ?? "");
    const reason = String(values.get("reason") ?? "");
    setBusy(true); setError(""); setNotice("");
    try {
      if (booked && event.booking) {
        await mutate(`${base}/bookings/${event.booking.id}/${cancel ? "cancel" : "reschedule"}`, {
          ...(cancel ? {} : { slotId }), expectedVersion: event.booking.rowVersion, reason,
        });
      } else {
        const topic = String(values.get("topic") ?? "").trim() || null;
        const fingerprint = JSON.stringify([learnerId, slotId, topic]);
        const key = keys.current.get(fingerprint) ?? crypto.randomUUID();
        keys.current.set(fingerprint, key);
        await mutate(`${base}/slots/${slotId}/book`, { topic, idempotencyKey: key });
        // Retry only unresolved attempts; a later booking is a new operation.
        keys.current.delete(fingerprint);
      }
      setNotice(cancel ? "Appointment cancelled." : booked ? "Appointment rescheduled." : "Appointment booked.");
      setCancelling(false); refresh();
    } catch (failure) { setError(failure instanceof Error ? failure.message : "The appointment could not be saved."); }
    finally { setBusy(false); }
  }

  return <article className={styles.card}>
    <header><span className={styles.badge}>{event.status === "CANCELLED" ? "Meeting cancelled" : booked ? "Appointment booked" : "Choose an appointment"}</span><h2>{event.titleEn}</h2>{event.titleHi && <p lang="hi">{event.titleHi}</p>}<p>{event.teacherName} · {event.className} · {event.venue}</p></header>
    {event.instructionsEn && <p>{event.instructionsEn}</p>}{event.instructionsHi && <p lang="hi">{event.instructionsHi}</p>}
    <p>All times: {event.timeZone}. Booking closes {ptmTime(event.bookingClosesAt, event.timeZone)}. Changes close {ptmTime(event.changesCloseAt, event.timeZone)}.</p>
    {booked && currentSlot && <p className={styles.confirmation}><strong>Your appointment: {ptmTime(currentSlot.startsAt, event.timeZone)}</strong></p>}
    {event.booking?.status === "CANCELLED" && <p>Your previous appointment was cancelled.</p>}
    {error && <p className={styles.error} role="alert">{error}</p>}{notice && <p className={styles.confirmation} role="status">{notice}</p>}
    {(access.canBook || access.canChange) && !cancelling && <form onSubmit={(e) => void submit(e)} aria-label={`Appointment for ${event.titleEn}`}>
      <fieldset disabled={busy} className={styles.slots}><legend>{booked ? "Choose a different time" : "Available times"}</legend>{event.slots.map((slot) => <label key={slot.id} className={styles.slot}><input type="radio" name="slotId" value={slot.id} required disabled={!slot.available || Date.parse(slot.startsAt) <= now} /><span>{ptmTime(slot.startsAt, event.timeZone)}<small>{slot.selected ? "Current appointment" : slot.available ? "Available" : "Booked"}</small></span></label>)}</fieldset>
      {booked ? <label className={styles.field}>Reason for rescheduling<input name="reason" required minLength={3} maxLength={500} disabled={busy} /></label> : <label className={styles.field}>Topic to discuss (optional)<textarea name="topic" minLength={3} maxLength={500} rows={2} disabled={busy} /></label>}
      <div className={styles.actions}><button disabled={busy || !event.slots.some((slot) => slot.available && Date.parse(slot.startsAt) > now)}>{busy ? "Saving…" : booked ? "Reschedule appointment" : "Book appointment"}</button>{booked && <button type="button" disabled={busy} onClick={() => setCancelling(true)}>Cancel appointment</button>}</div>
    </form>}
    {cancelling && access.canChange && <form onSubmit={(e) => void submit(e, true)}><h3>Cancel this appointment?</h3><p>The time will become available to another family.</p><label className={styles.field}>Cancellation reason<input autoFocus name="reason" required minLength={3} maxLength={500} disabled={busy} /></label><div className={styles.actions}><button disabled={busy}>Confirm cancellation</button><button type="button" disabled={busy} onClick={() => setCancelling(false)}>Keep appointment</button></div></form>}
    {!access.canBook && !access.canChange && event.status !== "CANCELLED" && <p>{now < Date.parse(event.bookingOpensAt) ? `Booking opens ${ptmTime(event.bookingOpensAt, event.timeZone)}.` : "Online booking or changes are closed. Contact the school if you need help."}</p>}
  </article>;
}
