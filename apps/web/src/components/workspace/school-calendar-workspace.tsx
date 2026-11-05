"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ArrowUpRight } from "@/components/icons";
import { AttendanceIcon, BellIcon, PlusIcon } from "@/components/workspace/workspace-icons";

type Campus = { id: string; code: string; name: string; timeZone: string };
type Session = { id: string; name: string; startsOn: string; endsOn: string; status: string };
type Policy = {
  id: string;
  revisionNumber: number;
  schoolStartTime: string;
  sameDayCutoffMinutes: number;
  sameDayCutoffTime: string;
  reminderTime: string;
  timeZone: string;
  guardianPushEnabled: boolean;
  studentInAppEnabled: boolean;
  emergencyAcknowledgementRequired: boolean;
  changeReason: string;
  revisedBy: string;
  revisedAt: string;
};
type NotificationJob = {
  id: string;
  audienceKind: string;
  requestedChannels: string[];
  requiresAcknowledgement: boolean;
  scheduledFor: string;
  nextAttemptAt: string;
  status: string;
  announcementId?: string;
  recipientCount?: number;
  attemptCount: number;
  lastErrorCode?: string;
  inAppReadyCount: number;
  pushAwaitingProviderCount: number;
  pushReadyCount: number;
  pushSentCount: number;
  pushDeliveredCount: number;
  pushFailedCount: number;
  pushSuppressedCount: number;
};
type CalendarNotice = {
  noticeSeriesId: string;
  revisionId: string;
  revisionNumber: number;
  supersedesRevisionId?: string;
  calendarDate: string;
  dayType: string;
  noticeKind: string;
  titleEn: string;
  titleHi: string;
  bodyEn: string;
  bodyHi: string;
  sourceReference?: string;
  changeReason: string;
  revisedBy: string;
  revisedAt: string;
  reviewStatus?: string;
  notificationJobs: NotificationJob[];
};
type Review = {
  id: string;
  noticeSeriesId: string;
  noticeRevisionId: string;
  revisionNumber: number;
  calendarDate: string;
  titleEn: string;
  titleHi: string;
  status: string;
  submissionReason: string;
  submittedBy: string;
  submittedAt: string;
  decidedBy?: string;
  decidedAt?: string;
  decisionReason?: string;
  rowVersion: number;
};
type Snapshot = {
  schoolId: string;
  schoolName: string;
  canManage: boolean;
  canApprove: boolean;
  selectedCampusId?: string;
  selectedAcademicSessionId?: string;
  campuses: Campus[];
  sessions: Session[];
  policy?: Policy;
  notices: CalendarNotice[];
  reviews: Review[];
};
type CalendarConfirmation = {
  title: string;
  calendarDate: string;
  titleEn: string;
  titleHi: string;
  bodyEn?: string;
  bodyHi?: string;
  sourceReference?: string;
  campusId?: string;
  sessionId?: string;
  path: string;
  body: { schoolId: string; expectedRevision?: number; expectedVersion?: number; decision?: "APPROVED" | "REJECTED" };
  evidenceRequired: boolean;
  reasonMaxLength: number;
  success: string;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const titleCase = (value: string) =>
  value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
const shortDate = (value: string) =>
  new Intl.DateTimeFormat("en-IN", { day: "2-digit", month: "short", year: "numeric" })
    .format(new Date(`${value}T12:00:00`));
const localDate = (date: Date) =>
  new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
const suggestedDate = () => localDate(new Date(Date.now() + 24 * 60 * 60_000));
function cookie(name: string) {
  return document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
}
async function csrfToken() {
  let token = cookie("educore_csrf");
  if (!token) {
    await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
    token = cookie("educore_csrf");
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}
async function problem(response: Response, fallback: string) {
  try {
    const body = await response.json() as { detail?: string };
    return body.detail || fallback;
  } catch {
    return fallback;
  }
}

export function SchoolCalendarWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<"calendar" | "notice" | "reviews" | "policy">("calendar");
  const [selectedId, setSelectedId] = useState("");
  const [editing, setEditing] = useState(false);
  const [noticeKind, setNoticeKind] = useState("PLANNED_HOLIDAY");
  const [dayType, setDayType] = useState("HOLIDAY");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [confirmation, setConfirmation] = useState<CalendarConfirmation | null>(null);
  const [refreshRequired, setRefreshRequired] = useState(false);
  const confirmationTrigger = useRef<HTMLElement | null>(null);
  const confirmationPanel = useRef<HTMLFormElement | null>(null);
  const confirmationReason = useRef<HTMLTextAreaElement | null>(null);
  const confirmationInFlight = useRef(false);
  const noticeTarget = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (confirmation) confirmationPanel.current?.scrollIntoView({ block: "start", behavior: "instant" });
  }, [confirmation]);

  const load = useCallback(async (campusId?: string, sessionId?: string) => {
    const search = new URLSearchParams();
    if (campusId) search.set("campusId", campusId);
    if (sessionId) search.set("academicSessionId", sessionId);
    const response = await fetch(`${apiUrl}/api/v1/school-calendar${search.size ? `?${search}` : ""}`, {
      credentials: "include",
    });
    if (!response.ok) throw new Error(await problem(response, "The school calendar could not be opened."));
    const snapshot = await response.json() as Snapshot;
    setData(snapshot);
    setSelectedId((current) =>
      snapshot.notices.some((item) => item.noticeSeriesId === current)
        ? current
        : snapshot.notices[0]?.noticeSeriesId || "",
    );
  }, []);

  useEffect(() => {
    Promise.resolve().then(() => load()).catch((reason: unknown) =>
      setError(reason instanceof Error ? reason.message : "The school calendar could not be opened."));
  }, [load]);

  const selected = useMemo(
    () => data?.notices.find((item) => item.noticeSeriesId === selectedId) ?? null,
    [data, selectedId],
  );
  const pendingReviews = data?.reviews.filter((item) => item.status === "PENDING") ?? [];
  const futureDates = data?.notices.filter((item) => item.calendarDate >= localDate(new Date())) ?? [];
  const nextGovernedDate = futureDates.find((item) => item.dayType !== "INSTRUCTIONAL") ?? futureDates[0];
  const delivery = (data?.notices ?? []).flatMap((item) => item.notificationJobs).reduce(
    (totals, job) => ({
      inApp: totals.inApp + job.inAppReadyCount,
      awaiting: totals.awaiting + job.pushAwaitingProviderCount,
      delivered: totals.delivered + job.pushDeliveredCount,
      failed: totals.failed + job.pushFailedCount,
    }),
    { inApp: 0, awaiting: 0, delivered: 0, failed: 0 },
  );

  async function mutate(path: string, init: RequestInit, success: string) {
    if (refreshRequired) return false;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(`${apiUrl}${path}`, {
        ...init,
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-TOKEN": await csrfToken(),
          ...(init.headers || {}),
        },
      });
      if (!response.ok) throw new Error(await problem(response, "The calendar change could not be saved."));
      setNotice(success);
      try { await load(data?.selectedCampusId, data?.selectedAcademicSessionId); }
      catch {
        setRefreshRequired(true);
        setError("The change was saved, but updated records could not be loaded. Reload verified records before making another calendar change.");
      }
      return true;
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The calendar change could not be saved.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  function selectScope(campusId: string, sessionId: string) {
    setError("");
    setNotice("");
    void load(campusId, sessionId).catch((reason: unknown) =>
      setError(reason instanceof Error ? reason.message : "The selected calendar could not be opened."));
  }

  async function savePolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data?.selectedCampusId || !data.selectedAcademicSessionId) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const payload = {
      schoolId: data.schoolId,
      ...(data.policy ? {} : {
        campusId: data.selectedCampusId,
        academicSessionId: data.selectedAcademicSessionId,
      }),
      schoolStartTime: form.get("schoolStartTime"),
      sameDayCutoffMinutes: Number(form.get("sameDayCutoffMinutes")),
      reminderTime: form.get("reminderTime"),
      timeZone: form.get("timeZone"),
      guardianPushEnabled: form.get("guardianPushEnabled") === "on",
      studentInAppEnabled: form.get("studentInAppEnabled") === "on",
      emergencyAcknowledgementRequired: form.get("emergencyAcknowledgementRequired") === "on",
      ...(data.policy ? { expectedRevision: data.policy.revisionNumber } : {}),
      authorityConfirmed: form.get("authorityConfirmed") === "on",
      reason: form.get("reason"),
    };
    const path = data.policy
      ? `/api/v1/school-calendar/policies/${data.policy.id}`
      : "/api/v1/school-calendar/policies";
    if (await mutate(path, {
      method: data.policy ? "PUT" : "POST",
      body: JSON.stringify(payload),
    }, data.policy ? "A new policy revision was recorded." : "The session calendar policy was created.")) {
      formElement.reset();
      setTab("calendar");
    }
  }

  async function saveNotice(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data?.selectedCampusId || !data.selectedAcademicSessionId) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const current = editing ? selected : null;
    const payload = {
      schoolId: data.schoolId,
      ...(current ? {} : {
        campusId: data.selectedCampusId,
        academicSessionId: data.selectedAcademicSessionId,
        calendarDate: form.get("calendarDate"),
      }),
      dayType,
      noticeKind,
      titleEn: form.get("titleEn"),
      titleHi: form.get("titleHi"),
      bodyEn: form.get("bodyEn"),
      bodyHi: form.get("bodyHi"),
      sourceReference: form.get("sourceReference") || null,
      ...(current ? { expectedRevision: current.revisionNumber } : {}),
      bilingualContentConfirmed: form.get("bilingualContentConfirmed") === "on",
      reason: form.get("reason"),
    };
    const path = current
      ? `/api/v1/school-calendar/notices/${current.noticeSeriesId}/revisions`
      : "/api/v1/school-calendar/notices";
    if (await mutate(path, { method: "POST", body: JSON.stringify(payload) },
      current ? "A correction revision was recorded without erasing history." : "The calendar draft was created.")) {
      setEditing(false);
      setTab("calendar");
      formElement.reset();
    }
  }

  function beginConfirmation(next: CalendarConfirmation) {
    if (busy || confirmation || refreshRequired) return;
    confirmationTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setError(""); setNotice(""); setConfirmation(next);
  }

  function cancelConfirmation() {
    if (busy || confirmationInFlight.current) return;
    setConfirmation(null); setError("");
    requestAnimationFrame(() => {
      if (confirmationTrigger.current?.isConnected) confirmationTrigger.current.focus();
    });
  }

  async function confirmCalendarAction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!confirmation || busy || refreshRequired || confirmationInFlight.current) return;
    if (!data || confirmation.body.schoolId !== data.schoolId || confirmation.campusId !== data.selectedCampusId || confirmation.sessionId !== data.selectedAcademicSessionId) {
      setError("The selected calendar changed. Cancel this confirmation and select the action again."); return;
    }
    const form = new FormData(event.currentTarget);
    const reason = String(form.get("reason") ?? "").trim();
    if (reason.length < 3 || reason.length > confirmation.reasonMaxLength) {
      setError(`Enter a reason between 3 and ${confirmation.reasonMaxLength} characters.`); return;
    }
    if (confirmation.evidenceRequired && form.get("evidenceConfirmed") !== "on") {
      setError("Confirm the date, source, bilingual messages and family impact before submission."); return;
    }
    confirmationInFlight.current = true;
    try {
      if (await mutate(confirmation.path, {
        method: "POST",
        body: JSON.stringify({ ...confirmation.body, reason, ...(confirmation.evidenceRequired ? { evidenceConfirmed: true } : {}) }),
      }, confirmation.success)) {
        setConfirmation(null); requestAnimationFrame(() => noticeTarget.current?.focus());
      } else requestAnimationFrame(() => confirmationReason.current?.focus({ preventScroll: true }));
    } finally { confirmationInFlight.current = false; }
  }

  function submitForReview(item: CalendarNotice) {
    if (!data) return;
    beginConfirmation({
      title: `Submit calendar revision ${item.revisionNumber} for independent approval?`,
      calendarDate: item.calendarDate, titleEn: item.titleEn, titleHi: item.titleHi,
      bodyEn: item.bodyEn, bodyHi: item.bodyHi, sourceReference: item.sourceReference,
      campusId: data.selectedCampusId, sessionId: data.selectedAcademicSessionId,
      path: `/api/v1/school-calendar/notices/${item.noticeSeriesId}/reviews`,
      body: { schoolId: data.schoolId, expectedRevision: item.revisionNumber },
      evidenceRequired: true, reasonMaxLength: 500,
      success: "The date change is waiting for a different authorized approver.",
    });
  }

  function decide(review: Review, decision: "APPROVED" | "REJECTED") {
    if (!data) return;
    const item = data.notices.find((notice) => notice.revisionId === review.noticeRevisionId);
    beginConfirmation({
      title: decision === "APPROVED" ? "Approve this date and schedule family notifications?" : "Reject this calendar revision?",
      calendarDate: review.calendarDate, titleEn: review.titleEn, titleHi: review.titleHi,
      bodyEn: item?.bodyEn, bodyHi: item?.bodyHi, sourceReference: item?.sourceReference,
      campusId: data.selectedCampusId, sessionId: data.selectedAcademicSessionId,
      path: `/api/v1/school-calendar/reviews/${review.id}/decision`,
      body: { schoolId: data.schoolId, decision, expectedVersion: review.rowVersion },
      evidenceRequired: false, reasonMaxLength: 1000,
      success: decision === "APPROVED" ? "Approved. Attendance-day state and due notifications were synchronized." : "Rejected. The review evidence remains preserved.",
    });
  }

  function beginCreate() {
    setEditing(false);
    setNoticeKind("PLANNED_HOLIDAY");
    setDayType("HOLIDAY");
    setTab("notice");
  }

  function beginCorrection(item: CalendarNotice) {
    setSelectedId(item.noticeSeriesId);
    setEditing(true);
    setNoticeKind(item.noticeKind);
    setDayType(item.dayType);
    setTab("notice");
  }

  function changeNoticeKind(value: string) {
    setNoticeKind(value);
    if (value === "PLANNED_HOLIDAY") setDayType("HOLIDAY");
    if (["EMERGENCY_CLOSURE", "GOVERNMENT_ORDER"].includes(value)) setDayType("CLOSURE");
    if (value === "REOPENING") setDayType("INSTRUCTIONAL");
  }

  if (!data) {
    return <section className="calendar-state">
      <AttendanceIcon />
      <h1>{error ? "Calendar is unavailable" : "Opening the governed school calendar…"}</h1>
      {error && <p>{error}</p>}
    </section>;
  }

  const selectedCampus = data.campuses.find((item) => item.id === data.selectedCampusId);
  const selectedSession = data.sessions.find((item) => item.id === data.selectedAcademicSessionId);
  const editable = editing ? selected : null;

  return <section className="calendar-workspace">
    <header className="calendar-hero">
      <div>
        <span>Governed school-day operations</span>
        <h1>One trusted calendar. No silent closure changes.</h1>
        <p>Plan session holidays, publish urgent closures, reopen a date, and notify the families who are eligible when delivery becomes due.</p>
      </div>
      <aside>
        <BellIcon />
        <strong>{pendingReviews.length}</strong>
        <span>Awaiting another authority</span>
      </aside>
    </header>

    <div className="calendar-trust">
      <span><i className="calendar-dot is-green" /> Attendance calendar synchronized on approval</span>
      <span><i className="calendar-dot is-green" /> In-app: {delivery.inApp} ready</span>
      <span><i className={`calendar-dot ${delivery.awaiting || delivery.failed ? "is-amber" : "is-green"}`} /> Push: {delivery.delivered} delivered · {delivery.awaiting} awaiting provider{delivery.failed ? ` · ${delivery.failed} failed` : ""}</span>
    </div>

    <section className="calendar-scope" aria-label="Calendar scope">
      <label>Campus
        <select disabled={busy || Boolean(confirmation) || refreshRequired} value={data.selectedCampusId || ""} onChange={(event) =>
          selectScope(event.target.value, data.selectedAcademicSessionId || "")}>
          {data.campuses.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      </label>
      <label>Academic session
        <select disabled={busy || Boolean(confirmation) || refreshRequired} value={data.selectedAcademicSessionId || ""} onChange={(event) =>
          selectScope(data.selectedCampusId || "", event.target.value)}>
          {data.sessions.map((item) => <option key={item.id} value={item.id}>{item.name} · {titleCase(item.status)}</option>)}
        </select>
      </label>
      <div>
        <span>Authority window</span>
        <strong>{data.policy ? `Until ${data.policy.sameDayCutoffTime.slice(0, 5)} on the affected day` : "Policy required"}</strong>
        <small>{selectedCampus?.timeZone || "Campus time zone"} · {selectedSession ? `${shortDate(selectedSession.startsOn)}–${shortDate(selectedSession.endsOn)}` : "Select a session"}</small>
      </div>
    </section>

    {error && <div className="calendar-banner is-error" role="alert">{error}{refreshRequired && <button type="button" onClick={() => window.location.reload()}>Reload verified records</button>}</div>}
    {notice && <div ref={noticeTarget} tabIndex={-1} className="calendar-banner is-success" role="status">{notice}</div>}

    {confirmation && <form ref={confirmationPanel} className="calendar-confirmation" onSubmit={confirmCalendarAction} aria-labelledby="calendar-confirmation-title" aria-describedby="calendar-confirmation-help" aria-busy={busy} onKeyDown={(event) => { if (event.key === "Escape") { event.preventDefault(); cancelConfirmation(); } }}>
      <h2 id="calendar-confirmation-title">{confirmation.title}</h2>
      <p id="calendar-confirmation-help">Nothing changes until you confirm. Independent approval, current revision, authority cutoff, notification and audit rules still apply.</p>
      <div className="calendar-confirmation-context"><time dateTime={confirmation.calendarDate}>{shortDate(confirmation.calendarDate)}</time><strong>{confirmation.titleEn}</strong><span lang="hi">{confirmation.titleHi}</span></div>
      {confirmation.bodyEn && confirmation.bodyHi ? <details className="calendar-confirmation-message"><summary>Review bilingual messages and source</summary><p lang="en">{confirmation.bodyEn}</p><p lang="hi">{confirmation.bodyHi}</p><p>Source: {confirmation.sourceReference || "Authority confirmation"}</p></details> : <p>The current message revision is unavailable in this view. Reload and review the date in Calendar before deciding; the server still rejects stale reviews.</p>}
      {confirmation.evidenceRequired && <label className="calendar-confirmation-evidence"><input name="evidenceConfirmed" type="checkbox" required disabled={busy} /> I reviewed the affected date, source, both language messages and family impact.</label>}
      <label>Reason for calendar action<textarea ref={confirmationReason} name="reason" autoFocus required minLength={3} maxLength={confirmation.reasonMaxLength} rows={3} disabled={busy} /></label>
      <footer><button type="submit" disabled={busy}>{busy ? "Saving…" : "Confirm calendar action"}</button><button className="is-secondary" type="button" disabled={busy} onClick={cancelConfirmation}>Cancel calendar action</button></footer>
    </form>}

    <nav className="calendar-tabs" aria-label="Calendar workspace sections">
      <button className={tab === "calendar" ? "is-active" : ""} aria-pressed={tab === "calendar"} onClick={() => setTab("calendar")}>Calendar <b>{data.notices.length}</b></button>
      {data.canManage && <button className={tab === "notice" ? "is-active" : ""} aria-pressed={tab === "notice"} onClick={beginCreate}>Add date</button>}
      <button className={tab === "reviews" ? "is-active" : ""} aria-pressed={tab === "reviews"} onClick={() => setTab("reviews")}>Reviews <b>{pendingReviews.length}</b></button>
      <button className={tab === "policy" ? "is-active" : ""} aria-pressed={tab === "policy"} onClick={() => setTab("policy")}>Session policy</button>
    </nav>

    {tab === "calendar" && <div className="calendar-layout">
      <aside className="calendar-list">
        <header><span>Current revisions</span><strong>{data.notices.length}</strong></header>
        {data.notices.length ? data.notices.map((item) => <button
          className={selectedId === item.noticeSeriesId ? "is-active" : ""}
          aria-pressed={selectedId === item.noticeSeriesId}
          key={item.noticeSeriesId}
          onClick={() => setSelectedId(item.noticeSeriesId)}
        >
          <time dateTime={item.calendarDate}>{shortDate(item.calendarDate)}</time>
          <span>{item.titleEn}</span>
          <small>{titleCase(item.noticeKind)} · revision {item.revisionNumber}</small>
          <em data-state={item.reviewStatus || "DRAFT"}>{titleCase(item.reviewStatus || "DRAFT")}</em>
        </button>) : <div className="calendar-empty-mini"><AttendanceIcon /><p>No governed dates in this session.</p></div>}
      </aside>
      <section className="calendar-detail" aria-label="Calendar date details">
        {selected ? <>
          <header>
            <div><span>{shortDate(selected.calendarDate)} · {titleCase(selected.dayType)}</span><h2>{selected.titleEn}</h2><p lang="hi">{selected.titleHi}</p></div>
            <em data-state={selected.reviewStatus || "DRAFT"}>{titleCase(selected.reviewStatus || "DRAFT")}</em>
          </header>
          <div className="calendar-copy">
            <article lang="en"><span>English</span><h3>{selected.titleEn}</h3><p>{selected.bodyEn}</p></article>
            <article lang="hi"><span>हिन्दी</span><h3>{selected.titleHi}</h3><p>{selected.bodyHi}</p></article>
          </div>
          <section className="calendar-evidence">
            <div><span>Revision</span><strong>{selected.revisionNumber}</strong><small>{selected.supersedesRevisionId ? "Earlier evidence preserved" : "Original controlled record"}</small></div>
            <div><span>Source</span><strong>{selected.sourceReference || "Authority confirmation"}</strong><small>{selected.changeReason}</small></div>
            <div><span>Revised by</span><strong>{selected.revisedBy}</strong><small>{new Date(selected.revisedAt).toLocaleString("en-IN")}</small></div>
          </section>
          <section className="calendar-jobs">
            <header><div><span>Notification evidence</span><h3>Recipients resolve only when due</h3></div><strong>{selected.notificationJobs.length} audience jobs</strong></header>
            {selected.notificationJobs.length ? selected.notificationJobs.map((job) => <article key={job.id}>
              <div><span>{titleCase(job.audienceKind)}</span><strong>{titleCase(job.status)}</strong><small>Scheduled {new Date(job.scheduledFor).toLocaleString("en-IN")}</small></div>
              <div><span>In app</span><strong>{job.inAppReadyCount}</strong><small>{job.recipientCount ?? 0} recipients resolved</small></div>
              <div><span>Phone push</span><strong>{job.pushDeliveredCount} delivered</strong><small>{job.pushAwaitingProviderCount} awaiting provider · {job.pushFailedCount} failed</small></div>
            </article>) : <p className="calendar-jobs__empty">Notifications are created only after independent approval.</p>}
          </section>
          <div className="calendar-actions">
            {data.canManage && selected.reviewStatus !== "PENDING" && <button disabled={busy || Boolean(confirmation) || refreshRequired} onClick={() => beginCorrection(selected)}>Record correction</button>}
            {data.canManage && (!selected.reviewStatus || ["DRAFT", "REJECTED"].includes(selected.reviewStatus)) &&
              <button disabled={busy || Boolean(confirmation) || refreshRequired} onClick={() => submitForReview(selected)}>Submit for approval <ArrowUpRight /></button>}
          </div>
        </> : <div className="calendar-empty"><AttendanceIcon /><h2>Build the session calendar</h2><p>Create a policy first, then add planned holidays or urgent closure dates.</p>{data.canManage && <button onClick={data.policy ? beginCreate : () => setTab("policy")}>{data.policy ? "Add first date" : "Configure session policy"}</button>}</div>}
      </section>
    </div>}

    {tab === "notice" && data.canManage && <form className="calendar-form" key={editable?.revisionId || "new"} onSubmit={saveNotice}>
      <header><div><span>{editable ? "Append-only correction" : "New governed date"}</span><h2>{editable ? "Revise without erasing history" : "Prepare a school-day change"}</h2><p>Drafts do not alter attendance or notify families. A different authorized person must approve.</p></div><PlusIcon /></header>
      <div className="calendar-form-grid">
        <label>Notice type<select name="noticeKind" value={noticeKind} onChange={(event) => changeNoticeKind(event.target.value)}>
          <option value="PLANNED_HOLIDAY">Planned holiday</option><option value="EMERGENCY_CLOSURE">Emergency closure</option><option value="GOVERNMENT_ORDER">Government order</option><option value="REOPENING">Reopening / instructional day</option><option value="OTHER">Other controlled change</option>
        </select></label>
        {noticeKind === "OTHER" && <label>School-day result<select name="dayType" value={dayType} onChange={(event) => setDayType(event.target.value)}><option value="HOLIDAY">Holiday</option><option value="CLOSURE">Closure</option><option value="INSTRUCTIONAL">Instructional</option></select></label>}
        <label>Date<input name="calendarDate" type="date" min={selectedSession?.startsOn} max={selectedSession?.endsOn} defaultValue={editable?.calendarDate || suggestedDate()} disabled={Boolean(editable)} required /></label>
        <label>Source / order reference (optional)<input name="sourceReference" defaultValue={editable?.sourceReference || ""} maxLength={500} placeholder="Circular, order, or management reference" /></label>
      </div>
      <fieldset><legend>English family message</legend><label>Title<input name="titleEn" defaultValue={editable?.titleEn || ""} maxLength={180} required /></label><label>Message<textarea name="bodyEn" defaultValue={editable?.bodyEn || ""} maxLength={4000} required /></label></fieldset>
      <fieldset lang="hi"><legend>हिन्दी परिवार संदेश</legend><label>शीर्षक<input name="titleHi" defaultValue={editable?.titleHi || ""} maxLength={180} required /></label><label>संदेश<textarea name="bodyHi" defaultValue={editable?.bodyHi || ""} maxLength={4000} required /></label></fieldset>
      <div className="calendar-confirm"><label><input name="bilingualContentConfirmed" type="checkbox" required /> I confirm both language versions communicate the same date, outcome, and instructions.</label></div>
      <label className="calendar-reason">Recorded reason<input name="reason" defaultValue={editable ? "Corrected after new authority information" : "Added to the approved session calendar"} maxLength={500} required /></label>
      <footer><p>Emergency and government closures queue immediately after approval. Planned holidays use the configured previous-day reminder.</p><button disabled={busy || Boolean(confirmation) || refreshRequired}>{editable ? "Record correction revision" : "Create controlled draft"} <ArrowUpRight /></button></footer>
    </form>}

    {tab === "reviews" && <section className="calendar-review-board">
      <header><div><span>Maker-checker control</span><h2>Independent decisions</h2><p>The person who submitted a revision cannot approve it.</p></div><strong>{pendingReviews.length} pending</strong></header>
      {data.reviews.length ? data.reviews.map((review) => <article key={review.id}>
        <time dateTime={review.calendarDate}>{shortDate(review.calendarDate)}</time>
        <div><span>Revision {review.revisionNumber} · {titleCase(review.status)}</span><h3>{review.titleEn}</h3><p lang="hi">{review.titleHi}</p><small>Submitted by {review.submittedBy} · {review.submissionReason}</small></div>
        <em data-state={review.status}>{titleCase(review.status)}</em>
        {review.status === "PENDING" && data.canApprove && <footer><button disabled={busy || Boolean(confirmation) || refreshRequired} onClick={() => decide(review, "REJECTED")}>Reject</button><button disabled={busy || Boolean(confirmation) || refreshRequired} onClick={() => decide(review, "APPROVED")}>Approve & schedule</button></footer>}
        {review.status !== "PENDING" && <footer><small>{review.decidedBy ? `${review.decidedBy}: ` : ""}{review.decisionReason}</small></footer>}
      </article>) : <div className="calendar-empty"><BellIcon /><h3>No review history yet</h3><p>Submitted calendar revisions will appear here.</p></div>}
    </section>}

    {tab === "policy" && <section className="calendar-policy-layout">
      <div className="calendar-policy-summary">
        <span>Session notification rules</span><h2>{data.policy ? `Revision ${data.policy.revisionNumber}` : "Not configured"}</h2>
        {data.policy ? <><dl><div><dt>School starts</dt><dd>{data.policy.schoolStartTime.slice(0, 5)}</dd></div><div><dt>Same-day cut-off</dt><dd>{data.policy.sameDayCutoffTime.slice(0, 5)}</dd></div><div><dt>Previous-day reminder</dt><dd>{data.policy.reminderTime.slice(0, 5)}</dd></div><div><dt>Time zone</dt><dd>{data.policy.timeZone}</dd></div></dl><p>Revised by {data.policy.revisedBy} · {data.policy.changeReason}</p></> : <p>Calendar dates cannot be submitted until leadership confirms these session-specific facts.</p>}
      </div>
      {data.canManage && <form className="calendar-policy-form" key={data.policy?.id || "new"} onSubmit={savePolicy}>
        <header><span>{data.policy ? "New immutable revision" : "Authority confirmation required"}</span><h2>{data.policy ? "Revise the session rules" : "Configure this session"}</h2></header>
        <label>School start time<input name="schoolStartTime" type="time" defaultValue={data.policy?.schoolStartTime?.slice(0, 5) || "08:00"} required /></label>
        <label>Same-day change closes this many minutes before start<input name="sameDayCutoffMinutes" type="number" min="0" max="240" defaultValue={data.policy?.sameDayCutoffMinutes ?? 60} required /></label>
        <label>Previous-day reminder time<input name="reminderTime" type="time" defaultValue={data.policy?.reminderTime?.slice(0, 5) || "18:00"} required /></label>
        <label>IANA time zone<input name="timeZone" defaultValue={data.policy?.timeZone || selectedCampus?.timeZone || "Asia/Kolkata"} maxLength={60} required /></label>
        <div className="calendar-policy-checks">
          <label><input name="guardianPushEnabled" type="checkbox" defaultChecked={data.policy?.guardianPushEnabled ?? true} /> Request guardian phone push</label>
          <label><input name="studentInAppEnabled" type="checkbox" defaultChecked={data.policy?.studentInAppEnabled ?? true} /> Student in-app notice</label>
          <label><input name="emergencyAcknowledgementRequired" type="checkbox" defaultChecked={data.policy?.emergencyAcknowledgementRequired ?? true} /> Emergency acknowledgement</label>
        </div>
        <label>Revision reason<input name="reason" defaultValue={data.policy ? "Updated by school leadership for this academic session" : "Initial session calendar rules confirmed by school leadership"} maxLength={500} required /></label>
        <label className="calendar-authority-confirm"><input name="authorityConfirmed" type="checkbox" required /> I confirm these are school-authority facts for the selected campus and session.</label>
        <button disabled={busy || Boolean(confirmation) || refreshRequired}>{data.policy ? "Record policy revision" : "Create session policy"} <ArrowUpRight /></button>
      </form>}
    </section>}

    {nextGovernedDate && <aside className="calendar-next" aria-label="Next governed school date"><span>Next affected date</span><strong>{shortDate(nextGovernedDate.calendarDate)}</strong><p>{nextGovernedDate.titleEn}</p></aside>}
  </section>;
}
