"use client";

import { useCodeLabel, useLanguage, useT } from "@/components/i18n/language-provider";
import { localizedContent } from "@/lib/i18n/locale";
import { canRespondToAssignment } from "@/lib/assignment-response";

import { useCallback, useEffect, useMemo, useState, type ChangeEvent } from "react";
import {
  ArrowRight,
  Calendar,
  Check,
  Clock,
  Message,
  Shield,
  Sparkles,
  User,
} from "@/components/icons";
import { MotionIllustration } from "@/components/motion-illustration";
import { PushNotificationControl } from "@/components/push-notification-control";
import styles from "./student-portal.module.css";

type ApiProblem = { detail?: string };
type PrivateFile = {
  fileId: string;
  filename: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  purpose: string;
  availableAt: string | null;
  replacesFileId: string | null;
  rowVersion: number;
};
type Overview = {
  learnerId: string;
  learnerReference: string;
  legalName: string;
  preferredName: string | null;
  schoolName: string;
  campusName: string;
  academicSessionName: string;
  gradeCode: string;
  sectionName: string | null;
  rollNumber: string | null;
  preferredLanguage: string | null;
  accessActivatedAt: string;
};
type Schedule = {
  learnerId: string;
  timetablePlanId: string | null;
  timetableName: string | null;
  publishedAt: string | null;
  entries: Array<{
    id: string;
    dayOfWeek: number;
    sequenceNumber: number;
    periodName: string;
    startsAt: string;
    endsAt: string;
    subjectName: string;
    subjectShortName: string | null;
    roomLabel: string | null;
  }>;
};
type PublishedCalendar = {
  learnerId: string;
  schoolId: string;
  campusId: string;
  academicSessionId: string;
  academicSessionName: string;
  sessionStartsOn: string;
  sessionEndsOn: string;
  timeZone: string;
  today: string;
  days: Array<{
    calendarDayId: string;
    noticeRevisionId: string;
    calendarDate: string;
    dayType: string;
    noticeKind: string;
    titleEn: string;
    titleHi: string;
    bodyEn: string;
    bodyHi: string;
    attendanceRequired: boolean;
    publishedAt: string;
  }>;
};
type Curriculum = {
  learnerId: string;
  publicationId: string | null;
  publicationVersion: number | null;
  sessionName: string;
  gradeCode: string;
  publishedAt: string | null;
  items: Array<{
    id: string;
    subjectCode: string;
    subjectName: string;
    subjectShortName: string | null;
    resourceType: string;
    title: string;
    description: string | null;
    author: string | null;
    publisher: string | null;
    edition: string | null;
    isbn: string | null;
    sourceUrl: string | null;
    displayOrder: number;
  }>;
};
type Attendance = {
  learnerId: string;
  from: string;
  to: string;
  summary: {
    recordedMarks: number;
    earnedCredits: number;
    attendancePercent: number | null;
    warningThresholdPercent: number | null;
    belowConfiguredWarning: boolean;
    stateCounts: Record<string, number>;
  };
  records: Array<{
    markId: string;
    date: string;
    registerMode: string;
    periodName: string | null;
    startsAt: string | null;
    endsAt: string | null;
    stateCode: string;
    creditWeight: number;
  }>;
};
type Submission = {
  submissionId: string;
  state: string;
  responseText: string | null;
  responseLink: string | null;
  submittedAt: string | null;
  teacherFeedback: string | null;
  pointsAwarded: number | null;
  reviewedAt: string | null;
  rowVersion: number;
  attachments: PrivateFile[];
};
type Assignment = {
  assignmentId: string;
  title: string;
  instructions: string;
  subjectName: string;
  categoryName: string | null;
  resourceLabel: string | null;
  resourceUrl: string | null;
  availableFrom: string;
  dueAt: string;
  submissionMode: string;
  maximumPoints: number | null;
  allowAttachments: boolean;
  maxAttachmentCount: number;
  attachmentRequired: boolean;
  allowLate: boolean;
  lateUntil: string | null;
  status: string;
  publicationRevision: number;
  attachments: PrivateFile[];
  submission: Submission | null;
};
type Assignments = { learnerId: string; assignments: Assignment[] };
type Results = {
  learnerId: string;
  reportCards: Array<{
    reportCardId: string;
    publicationId: string;
    publicationVersion: number;
    reportNumber: string;
    sessionName: string;
    termName: string;
    sectionName: string;
    overallPercentage: number | null;
    overallGradeCode: string | null;
    overallOutcome: string | null;
    publishedAt: string;
    snapshotSha256: string;
    report: {
      subjects?: Array<{
        subjectId?: string;
        name?: string;
        subjectName?: string;
        percentage?: number | null;
        gradeCode?: string | null;
        outcome?: string | null;
      }>;
    } & Record<string, unknown>;
  }>;
};
type Communications = {
  schoolId: string;
  learnerId: string;
  items: Array<{
    recipientId: string;
    announcementId: string;
    learnerId: string;
    category: string;
    priority: string;
    audienceKind: string;
    languageMode: string;
    preferredLanguage: string | null;
    titleEn: string | null;
    titleHi: string | null;
    bodyEn: string | null;
    bodyHi: string | null;
    requiresAcknowledgement: boolean;
    scheduledFor: string;
    expiresAt: string | null;
    expired: boolean;
    readAt: string | null;
    acknowledgedAt: string | null;
    deliveries: Array<{ channel: string; status: string }>;
    version: number;
  }>;
};
type Draft = { text: string; link: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const weekDays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

function formatCode(value: string | null | undefined) {
  if (!value) return "Not recorded";
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
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

function dateLabel(value: string) {
  return new Intl.DateTimeFormat("en-IN", {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(`${value}T12:00:00`));
}

function timeLabel(value: string | null) {
  if (!value) return "";
  return value.slice(0, 5);
}

function fileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function cookie(name: string) {
  const value = document.cookie.split("; ").find((entry) => entry.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : "";
}

async function problemMessage(response: Response, fallback: string) {
  try {
    const problem = (await response.json()) as ApiProblem;
    return problem.detail || fallback;
  } catch {
    return fallback;
  }
}

async function readResource<T>(path: string, fallback: string): Promise<T> {
  const response = await fetch(`${apiUrl}${path}`, { credentials: "include" });
  if (!response.ok) throw new Error(await problemMessage(response, fallback));
  return (await response.json()) as T;
}

async function securedRequest(path: string, init: RequestInit) {
  let csrf = cookie("educore_csrf");
  if (!csrf) {
    await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
    csrf = cookie("educore_csrf");
  }
  const headers = new Headers(init.headers);
  headers.set("X-CSRF-TOKEN", csrf);
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  return fetch(`${apiUrl}${path}`, { ...init, headers, credentials: "include" });
}

export function StudentPortal() {
  const t = useT("student");
  const displayCode = useCodeLabel(formatCode);
  const { locale } = useLanguage();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [schedule, setSchedule] = useState<Schedule | null>(null);
  const [calendar, setCalendar] = useState<PublishedCalendar | null>(null);
  const [curriculum, setCurriculum] = useState<Curriculum | null>(null);
  const [attendance, setAttendance] = useState<Attendance | null>(null);
  const [assignments, setAssignments] = useState<Assignments | null>(null);
  const [results, setResults] = useState<Results | null>(null);
  const [communications, setCommunications] = useState<Communications | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [sectionErrors, setSectionErrors] = useState<Record<string, string>>({});
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [assignmentBusy, setAssignmentBusy] = useState("");
  const [assignmentNotice, setAssignmentNotice] = useState("");
  const [messageBusy, setMessageBusy] = useState("");
  const [messageNotice, setMessageNotice] = useState("");

  const loadAssignments = useCallback(async () => {
    const next = await readResource<Assignments>(
      "/api/v1/student/assignments",
      "Your assignments are unavailable.",
    );
    setAssignments(next);
    setDrafts((current) => {
      const updated = { ...current };
      next.assignments.forEach((assignment) => {
        if (!updated[assignment.assignmentId]) {
          updated[assignment.assignmentId] = {
            text: assignment.submission?.responseText ?? "",
            link: assignment.submission?.responseLink ?? "",
          };
        }
      });
      return updated;
    });
  }, []);

  const loadCommunications = useCallback(async () => {
    setCommunications(
      await readResource<Communications>(
        "/api/v1/student/communications",
        "Your school messages are unavailable.",
      ),
    );
  }, []);

  const loadPortal = useCallback(async () => {
    setLoading(true);
    setError("");
    setSectionErrors({});
    try {
      const contextResponse = await fetch(`${apiUrl}/api/v1/context`, { credentials: "include" });
      if (!contextResponse.ok) {
        // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Discard the student document when authenticated access cannot be verified.
        window.location.assign("/portal");
        return;
      }
      const context = (await contextResponse.json()) as { capabilities: string[] };
      if (!context.capabilities.includes("portal.student")) {
        // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document at the verified persona boundary without retaining student state.
        if (context.capabilities.includes("portal.guardian")) window.location.assign("/family");
        // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document at the verified persona boundary without retaining student state.
        else if (context.capabilities.length) window.location.assign("/workspace");
        // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Discard the student document when no portal capability remains.
        else window.location.assign("/portal");
        return;
      }

      const resources = await Promise.allSettled([
        readResource<Overview>("/api/v1/student/me", "Your student profile is unavailable."),
        readResource<Schedule>("/api/v1/student/schedule", "Your timetable is unavailable."),
        readResource<PublishedCalendar>("/api/v1/student/calendar", "Your school calendar is unavailable."),
        readResource<Curriculum>("/api/v1/student/curriculum", "Your books and syllabus are unavailable."),
        readResource<Attendance>("/api/v1/student/attendance", "Your attendance is unavailable."),
        readResource<Assignments>("/api/v1/student/assignments", "Your assignments are unavailable."),
        readResource<Results>("/api/v1/student/results", "Your results are unavailable."),
        readResource<Communications>(
          "/api/v1/student/communications",
          "Your school messages are unavailable.",
        ),
      ]);
      const setters = [setOverview, setSchedule, setCalendar, setCurriculum, setAttendance, setAssignments, setResults, setCommunications] as const;
      const keys = ["overview", "schedule", "calendar", "curriculum", "attendance", "assignments", "results", "messages"];
      const failures: Record<string, string> = {};
      resources.forEach((resource, index) => {
        if (resource.status === "fulfilled") {
          const setter = setters[index] as (value: unknown) => void;
          setter(resource.value);
        } else {
          failures[keys[index] ?? `section-${index}`] =
            resource.reason instanceof Error ? resource.reason.message : "This section is unavailable.";
        }
      });
      const assignmentResource = resources[5];
      if (assignmentResource?.status === "fulfilled") {
        const nextAssignments = assignmentResource.value as Assignments;
        setDrafts(
          Object.fromEntries(
            nextAssignments.assignments.map((assignment) => [
              assignment.assignmentId,
              {
                text: assignment.submission?.responseText ?? "",
                link: assignment.submission?.responseLink ?? "",
              },
            ]),
          ),
        );
      }
      setSectionErrors(failures);
      if (resources[0]?.status === "rejected") setError(failures.overview ?? "Your portal is unavailable.");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Your student portal is unavailable.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(() => {
      if (!cancelled) return loadPortal();
      return undefined;
    });
    return () => {
      cancelled = true;
    };
  }, [loadPortal]);

  const today = ((new Date().getDay() + 6) % 7) + 1;
  const todaysEntries = useMemo(
    () => schedule?.entries.filter((entry) => entry.dayOfWeek === today) ?? [],
    [schedule, today],
  );
  const unreadMessages = communications?.items.filter((item) => !item.readAt).length ?? 0;
  const openAssignments = assignments?.assignments.filter(canRespondToAssignment).length ?? 0;

  async function saveSubmission(assignment: Assignment, action: "DRAFT" | "SUBMIT") {
    const draft = drafts[assignment.assignmentId] ?? { text: "", link: "" };
    const evidenceCount = assignment.submission?.attachments.length ?? 0;
    if (action === "SUBMIT" && assignment.attachmentRequired && evidenceCount === 0) {
      setAssignmentNotice("Add the required attachment before submitting this assignment.");
      return;
    }
    setAssignmentBusy(assignment.assignmentId);
    setAssignmentNotice("");
    try {
      const response = await securedRequest(
        `/api/v1/student/assignments/${assignment.assignmentId}/submission`,
        {
          method: "POST",
          body: JSON.stringify({
            action,
            responseText: draft.text.trim() || null,
            responseLink: draft.link.trim() || null,
            expectedVersion: assignment.submission?.rowVersion ?? 0,
          }),
        },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "Your assignment response could not be saved."));
      }
      await loadAssignments();
      setAssignmentNotice(action === "SUBMIT" ? "Assignment submitted successfully." : "Draft saved.");
    } catch (reason) {
      setAssignmentNotice(
        reason instanceof Error ? reason.message : "Your assignment response could not be saved.",
      );
    } finally {
      setAssignmentBusy("");
    }
  }

  async function uploadAttachment(assignment: Assignment, event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    const count = assignment.submission?.attachments.length ?? 0;
    if (count >= assignment.maxAttachmentCount) {
      setAssignmentNotice(`This assignment allows up to ${assignment.maxAttachmentCount} attachments.`);
      return;
    }
    setAssignmentBusy(assignment.assignmentId);
    setAssignmentNotice("");
    try {
      const body = new FormData();
      body.append("file", file);
      const response = await securedRequest(
        `/api/v1/student/assignments/${assignment.assignmentId}/attachments`,
        { method: "POST", body },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The attachment could not be added."));
      }
      await loadAssignments();
      setAssignmentNotice("Attachment scanned and added securely.");
    } catch (reason) {
      setAssignmentNotice(reason instanceof Error ? reason.message : "The attachment could not be added.");
    } finally {
      setAssignmentBusy("");
    }
  }

  async function removeAttachment(assignment: Assignment, file: PrivateFile) {
    setAssignmentBusy(assignment.assignmentId);
    setAssignmentNotice("");
    try {
      const response = await securedRequest(`/api/v1/files/${file.fileId}/expire`, {
        method: "POST",
        body: JSON.stringify({
          expectedVersion: file.rowVersion,
          reason: "Student removed this attachment before final submission",
        }),
      });
      if (!response.ok) throw new Error(await problemMessage(response, "The attachment could not be removed."));
      await loadAssignments();
      setAssignmentNotice("Attachment removed from the active submission.");
    } catch (reason) {
      setAssignmentNotice(reason instanceof Error ? reason.message : "The attachment could not be removed.");
    } finally {
      setAssignmentBusy("");
    }
  }

  async function recordMessage(recipientId: string, action: "read" | "acknowledge") {
    setMessageBusy(recipientId);
    setMessageNotice("");
    try {
      const response = await securedRequest(`/api/v1/student/communications/${recipientId}/${action}`, {
        method: "POST",
      });
      if (!response.ok) throw new Error(await problemMessage(response, "The message could not be updated."));
      await loadCommunications();
      setMessageNotice(action === "acknowledge" ? "Message acknowledged." : "Message marked as read.");
    } catch (reason) {
      setMessageNotice(reason instanceof Error ? reason.message : "The message could not be updated.");
    } finally {
      setMessageBusy("");
    }
  }

  if (loading) {
    return (
      <main id="main-content" className={styles.portal}>
        <section className={styles.loading} aria-live="polite">
          <span />
          <div>
            <strong>{t("Opening your verified student view…")}</strong>
            <small>{t("Your school-scoped records are loading securely.")}</small>
          </div>
        </section>
      </main>
    );
  }

  if (error || !overview) {
    return (
      <main id="main-content" className={styles.portal}>
        <section className={styles.empty} role="alert">
          <Shield />
          <h1>{t("We could not open your student portal")}</h1>
          <p>{t(error || "Your student access is not active.")}</p>
          <button type="button" onClick={() => void loadPortal()}>{t("Try again")}</button>
        </section>
      </main>
    );
  }

  return (
    <main id="main-content" className={styles.portal}>
      <section className={styles.hero} id="student-overview">
        <div className={styles.heroCopy}>
          <span><Sparkles /> {t("Verified student portal")}</span>
          <h1>
            {t("Hello,")} <em>{overview.preferredName || overview.legalName.split(/\s+/)[0]}</em>
          </h1>
          <p>
            {t("Your timetable, books and syllabus, attendance, assignments, published results, and school messages—only the records linked to your own verified account.")} </p>
          <dl>
            <div><dt>{t("Class")}</dt><dd>{overview.gradeCode}{overview.sectionName ? ` · ${overview.sectionName}` : ""}</dd></div>
            <div><dt>{t("Roll number")}</dt><dd>{overview.rollNumber || "Not assigned"}</dd></div>
            <div><dt>{t("Session")}</dt><dd>{overview.academicSessionName}</dd></div>
          </dl>
        </div>
        <MotionIllustration
          className={styles.heroMotion}
          src="/animations/study-discussion.lottie"
          label={t("Learners studying together")}
        />
        <div className={styles.heroOrbit} aria-hidden="true"><i /><i /><i /></div>
      </section>

      <section className={styles.metrics} aria-label={t("My school-day summary")}>
        <article><span><Calendar /></span><div><strong>{todaysEntries.length}</strong><small>{t("Periods today")}</small></div></article>
        <article><span><Check /></span><div><strong>{attendance?.summary.attendancePercent == null ? "—" : `${attendance.summary.attendancePercent}%`}</strong><small>{t("Recorded attendance")}</small></div></article>
        <article><span><Clock /></span><div><strong>{openAssignments}</strong><small>{t("Assignments open")}</small></div></article>
        <article><span><Message /></span><div><strong>{unreadMessages}</strong><small>{t("Unread messages")}</small></div></article>
      </section>

      <section className={styles.identityNote}>
        <Shield />
        <div>
          <strong>{overview.schoolName}</strong>
          <span>{overview.campusName} · {overview.learnerReference}</span>
        </div>
        <small>{t("Access active since")} {dateTimeLabel(overview.accessActivatedAt)}</small>
      </section>

      <section className={styles.section} id="student-calendar" aria-labelledby="student-calendar-title">
        <header className={styles.sectionHeading}>
          <div><span>{t("Approved school days")}</span><h2 id="student-calendar-title">{t("Holidays & closures")}</h2></div>
          <p>
            {calendar
              ? `${calendar.academicSessionName} · ${dateLabel(calendar.sessionStartsOn)} to ${dateLabel(calendar.sessionEndsOn)}`
              : "Only notices approved by school leadership appear here."}
          </p>
        </header>
        {sectionErrors.calendar ? <div className={styles.sectionError} role="alert">{t(sectionErrors.calendar)}</div> : null}
        {!sectionErrors.calendar && !calendar?.days.length ? (
          <div className={styles.sectionEmpty}>
            <Calendar />
            <strong>{t("No approved calendar changes")}</strong>
            <p>{t("The school has not published a holiday, closure, or reopening change for this session yet.")}</p>
          </div>
        ) : null}
        {calendar?.days.length ? (
          <div className={styles.calendarGrid}>
            {calendar.days.map((day) => {
              const urgent = ["EMERGENCY_CLOSURE", "GOVERNMENT_ORDER"].includes(day.noticeKind);
              const title = localizedContent(locale, day.titleEn, day.titleHi);
              const body = localizedContent(locale, day.bodyEn, day.bodyHi);
              return (
                <article className={`${styles.calendarCard} ${urgent ? styles.calendarUrgent : ""}`} key={day.calendarDayId}>
                  <header>
                    <time dateTime={day.calendarDate}>{dateLabel(day.calendarDate)}</time>
                    <span>{displayCode(day.noticeKind)}</span>
                  </header>
                  <h3 lang={title.primaryLanguage}>{title.primary}</h3>
                  <p lang={body.primaryLanguage}>{body.primary}</p>
                  <details className={styles.calendarTranslation}>
                    <summary>{t("Read in another language")}</summary>
                    <strong lang={title.secondaryLanguage}>{title.secondary}</strong>
                    <p lang={body.secondaryLanguage}>{body.secondary}</p>
                  </details>
                  <footer>
                    <span>{displayCode(day.dayType)}</span>
                    <small>{day.attendanceRequired ? "Classes scheduled" : "No attendance required"}</small>
                  </footer>
                </article>
              );
            })}
          </div>
        ) : null}
      </section>

      {calendar?.schoolId ? <PushNotificationControl schoolId={calendar.schoolId} /> : null}

      <section className={styles.section} id="student-timetable" aria-labelledby="student-timetable-title">
        <header className={styles.sectionHeading}>
          <div><span>{t("My week")}</span><h2 id="student-timetable-title">{t("Timetable")}</h2></div>
          <p>{schedule?.timetableName || t("The school has not published a timetable yet.")}</p>
        </header>
        {sectionErrors.schedule ? <div className={styles.sectionError} role="alert">{t(sectionErrors.schedule)}</div> : null}
        {!sectionErrors.schedule && !schedule?.entries.length ? (
          <div className={styles.sectionEmpty}><Calendar /><strong>{t("No published periods")}</strong><p>{t("Your timetable will appear here after the school publishes it.")}</p></div>
        ) : null}
        {schedule?.entries.length ? (
          <div className={styles.timetable}>
            {weekDays.map((day, index) => {
              const entries = schedule.entries.filter((entry) => entry.dayOfWeek === index + 1);
              if (!entries.length) return null;
              return (
                <article className={index + 1 === today ? styles.today : ""} key={day}>
                  <header><div><span>{t(index + 1 === today ? "Today" : "School day")}</span><h3>{t(day)}</h3></div><small>{entries.length} {t("periods")}</small></header>
                  <ol>
                    {entries.map((entry) => (
                      <li key={entry.id}>
                        <time>{timeLabel(entry.startsAt)}</time>
                        <i />
                        <div><strong>{entry.subjectName}</strong><small>{entry.periodName}{entry.roomLabel ? ` · ${entry.roomLabel}` : ""}</small></div>
                        <span>{timeLabel(entry.endsAt)}</span>
                      </li>
                    ))}
                  </ol>
                </article>
              );
            })}
          </div>
        ) : null}
      </section>

      <section className={styles.section} id="student-curriculum" aria-labelledby="student-curriculum-title">
        <header className={styles.sectionHeading}>
          <div><span>{t("My course")}</span><h2 id="student-curriculum-title">{t("Books & syllabus")}</h2></div>
          <p>
            {curriculum?.publishedAt
              ? `${curriculum.sessionName} · school release ${curriculum.publicationVersion}`
              : t("Only the school’s approved class catalogue appears here.")}
          </p>
        </header>
        {sectionErrors.curriculum ? <div className={styles.sectionError} role="alert">{t(sectionErrors.curriculum)}</div> : null}
        {!sectionErrors.curriculum && !curriculum?.items.length ? (
          <div className={styles.sectionEmpty}><Shield /><strong>{t("No curriculum published yet")}</strong><p>{t("The school is still reviewing the books and syllabus for your class.")}</p></div>
        ) : null}
        {curriculum?.items.length ? (
          <div className={styles.curriculumGrid}>
            {curriculum.items.map((item) => (
              <article key={item.id}>
                <header><span>{displayCode(item.resourceType)}</span><em>{item.subjectShortName || item.subjectCode}</em></header>
                <h3>{item.title}</h3>
                <p>{item.description || `${item.subjectName} · confirmed by the school`}</p>
                <dl>
                  {item.author ? <div><dt>{t("Author")}</dt><dd>{item.author}</dd></div> : null}
                  {item.publisher ? <div><dt>{t("Publisher")}</dt><dd>{item.publisher}</dd></div> : null}
                  {item.edition ? <div><dt>{t("Edition")}</dt><dd>{item.edition}</dd></div> : null}
                  {item.isbn ? <div><dt>ISBN</dt><dd>{item.isbn}</dd></div> : null}
                </dl>
                {item.sourceUrl ? <a href={item.sourceUrl} target="_blank" rel="noreferrer">{t("Open official resource")} <ArrowRight /></a> : null}
              </article>
            ))}
          </div>
        ) : null}
      </section>

      <section className={styles.section} id="student-attendance" aria-labelledby="student-attendance-title">
        <header className={styles.sectionHeading}>
          <div><span>{t("My presence")}</span><h2 id="student-attendance-title">{t("Attendance")}</h2></div>
          <p>{attendance ? `${dateLabel(attendance.from)} to ${dateLabel(attendance.to)}` : "School-configured attendance period"}</p>
        </header>
        {sectionErrors.attendance ? <div className={styles.sectionError} role="alert">{t(sectionErrors.attendance)}</div> : null}
        {attendance ? (
          <div className={styles.attendanceGrid}>
            <article className={attendance.summary.belowConfiguredWarning ? styles.attendanceWarning : styles.attendanceScore}>
              <span>{attendance.summary.belowConfiguredWarning ? t("Needs attention") : t("Current record")}</span>
              <strong>{attendance.summary.attendancePercent == null ? "—" : `${attendance.summary.attendancePercent}%`}</strong>
              <p>{attendance.summary.recordedMarks} {t("recorded marks ·")} {attendance.summary.earnedCredits} {t("earned credits")}</p>
              {attendance.summary.warningThresholdPercent != null ? <small>{t("School warning threshold:")} {attendance.summary.warningThresholdPercent}%</small> : null}
            </article>
            <div className={styles.attendanceStates}>
              {Object.entries(attendance.summary.stateCounts).map(([state, count]) => (
                <div key={state}><span>{displayCode(state)}</span><strong>{count}</strong></div>
              ))}
            </div>
            <div className={styles.attendanceRecords}>
              {attendance.records.length ? attendance.records.slice(0, 10).map((record) => (
                <article key={record.markId}>
                  <time>{dateLabel(record.date)}</time>
                  <div><strong>{record.periodName || displayCode(record.registerMode)}</strong><small>{record.startsAt ? `${timeLabel(record.startsAt)}–${timeLabel(record.endsAt)}` : displayCode(record.registerMode)}</small></div>
                  <span data-state={record.stateCode}>{displayCode(record.stateCode)}</span>
                </article>
              )) : <div className={styles.inlineEmpty}>{t("No attendance marks were recorded in this period.")}</div>}
            </div>
          </div>
        ) : null}
      </section>

      <section className={styles.section} id="student-assignments" aria-labelledby="student-assignments-title">
        <header className={styles.sectionHeading}>
          <div><span>{t("My learning")}</span><h2 id="student-assignments-title">{t("Assignments")}</h2></div>
          <p>{t("Drafts stay editable until you submit. Attachments are private and scanned before use.")}</p>
        </header>
        {assignmentNotice ? <div className={styles.notice} role="status">{t(assignmentNotice)}</div> : null}
        {sectionErrors.assignments ? <div className={styles.sectionError} role="alert">{t(sectionErrors.assignments)}</div> : null}
        {!sectionErrors.assignments && !assignments?.assignments.length ? (
          <div className={styles.sectionEmpty}><Sparkles /><strong>{t("No assignments are open")}</strong><p>{t("Published work will appear here when it becomes available.")}</p></div>
        ) : null}
        <div className={styles.assignmentList}>
          {assignments?.assignments.map((assignment) => {
            const draft = drafts[assignment.assignmentId] ?? { text: "", link: "" };
            const canRespond = canRespondToAssignment(assignment);
            const busy = assignmentBusy === assignment.assignmentId;
            return (
              <article className={styles.assignmentCard} key={assignment.assignmentId}>
                <header>
                  <div><span>{assignment.subjectName}</span><h3>{assignment.title}</h3><small>{assignment.categoryName || displayCode(assignment.submissionMode)}</small></div>
                  <em data-state={assignment.status}>{displayCode(assignment.status)}</em>
                </header>
                <div className={styles.assignmentMeta}>
                  <span><Clock /> {t("Due")} {dateTimeLabel(assignment.dueAt)}</span>
                  {assignment.maximumPoints != null ? <span><Check /> {assignment.maximumPoints} {t("points")}</span> : null}
                  {assignment.attachmentRequired ? <span><Shield /> {t("Attachment required")}</span> : null}
                </div>
                <p className={styles.instructions}>{assignment.instructions}</p>
                {assignment.resourceUrl ? <a className={styles.resourceLink} href={assignment.resourceUrl} target="_blank" rel="noreferrer">{assignment.resourceLabel || "Open learning resource"} <ArrowRight /></a> : null}
                <div className={styles.submissionEditor}>
                  <label><span>{t("My response")}</span><textarea value={draft.text} maxLength={8000} disabled={busy || !canRespond} onChange={(event) => setDrafts((current) => ({ ...current, [assignment.assignmentId]: { ...draft, text: event.target.value } }))} placeholder={t("Write your answer or a short note about the work you are submitting.")} /></label>
                  <label><span>{t("Response link")} <small>{t("optional")}</small></span><input type="url" value={draft.link} maxLength={1000} disabled={busy || !canRespond} onChange={(event) => setDrafts((current) => ({ ...current, [assignment.assignmentId]: { ...draft, link: event.target.value } }))} placeholder="https://…" /></label>
                  {assignment.allowAttachments ? (
                    <div className={styles.attachments}>
                      <header><strong>{t("Private attachments")}</strong><small>{assignment.submission?.attachments.length ?? 0} {t("of")} {assignment.maxAttachmentCount}</small></header>
                      {assignment.submission?.attachments.map((file) => (
                        <div key={file.fileId}><a href={`${apiUrl}/api/v1/files/${file.fileId}`}>{file.filename}</a><span>{fileSize(file.sizeBytes)}</span>{canRespond ? <button type="button" disabled={busy} onClick={() => void removeAttachment(assignment, file)}>{t("Remove")}</button> : null}</div>
                      ))}
                      {canRespond && (assignment.submission?.attachments.length ?? 0) < assignment.maxAttachmentCount ? (
                        <label className={styles.filePicker}><input type="file" disabled={busy} onChange={(event) => void uploadAttachment(assignment, event)} /><span>{busy ? "Scanning…" : "Choose a file to scan and attach"}</span></label>
                      ) : null}
                    </div>
                  ) : null}
                  {assignment.submission?.teacherFeedback ? <div className={styles.feedback}><strong>{t("Teacher feedback")}</strong><p>{assignment.submission.teacherFeedback}</p>{assignment.submission.pointsAwarded != null ? <small>{assignment.submission.pointsAwarded}{assignment.maximumPoints != null ? ` / ${assignment.maximumPoints}` : ""} {t("points")}</small> : null}</div> : null}
                  <footer>
                    <span>{assignment.submission ? `Current state: ${displayCode(assignment.submission.state)} · version ${assignment.submission.rowVersion}` : t("No draft saved yet")}</span>
                    {canRespond ? <div><button type="button" disabled={busy || (!draft.text.trim() && !draft.link.trim() && !(assignment.submission?.attachments.length))} onClick={() => void saveSubmission(assignment, "DRAFT")}>{t("Save draft")}</button><button type="button" className={styles.primaryButton} disabled={busy || (!draft.text.trim() && !draft.link.trim() && !(assignment.submission?.attachments.length))} onClick={() => void saveSubmission(assignment, "SUBMIT")}>{t("Submit work")}</button></div> : assignment.submission?.submittedAt ? <strong><Check /> {t("Submitted")} {dateTimeLabel(assignment.submission.submittedAt)}</strong> : null}
                  </footer>
                </div>
              </article>
            );
          })}
        </div>
      </section>

      <section className={styles.section} id="student-results" aria-labelledby="student-results-title">
        <header className={styles.sectionHeading}>
          <div><span>{t("Published records")}</span><h2 id="student-results-title">{t("Results")}</h2></div>
          <p>{t("Only final report cards published by the school appear here.")}</p>
        </header>
        {sectionErrors.results ? <div className={styles.sectionError} role="alert">{t(sectionErrors.results)}</div> : null}
        {!sectionErrors.results && !results?.reportCards.length ? <div className={styles.sectionEmpty}><Check /><strong>{t("No published report cards")}</strong><p>{t("Your result will appear after the school completes and publishes it.")}</p></div> : null}
        <div className={styles.resultGrid}>
          {results?.reportCards.map((card) => (
            <article key={card.reportCardId}>
              <header><div><span>{card.sessionName}</span><h3>{card.termName}</h3><small>{card.reportNumber} · {card.sectionName}</small></div><strong>{card.overallPercentage == null ? card.overallGradeCode || "Published" : `${card.overallPercentage}%`}</strong></header>
              {card.overallOutcome ? <p>{displayCode(card.overallOutcome)}</p> : null}
              {card.report.subjects?.length ? <div>{card.report.subjects.map((subject, index) => <span key={subject.subjectId || `${card.reportCardId}-${index}`}><strong>{subject.name || subject.subjectName || "Subject"}</strong><small>{subject.percentage == null ? subject.gradeCode || displayCode(subject.outcome) : `${subject.percentage}%`}</small></span>)}</div> : <p className={styles.reportSummary}>{t("The signed report snapshot is available without exposing unpublished assessment data.")}</p>}
              <footer><span>{t("Published")} {dateTimeLabel(card.publishedAt)}</span><small>{t("Verified revision")} {card.publicationVersion}</small></footer>
            </article>
          ))}
        </div>
      </section>

      <section className={styles.section} id="student-messages" aria-labelledby="student-messages-title">
        <header className={styles.sectionHeading}>
          <div><span>{t("School updates")}</span><h2 id="student-messages-title">{t("Messages")}</h2></div>
          <p>{t("Student messages are delivered in-app; your account never exposes guardian contact channels.")}</p>
        </header>
        {messageNotice ? <div className={styles.notice} role="status">{t(messageNotice)}</div> : null}
        {sectionErrors.messages ? <div className={styles.sectionError} role="alert">{t(sectionErrors.messages)}</div> : null}
        {!sectionErrors.messages && !communications?.items.length ? <div className={styles.sectionEmpty}><Message /><strong>{t("No school messages")}</strong><p>{t("Notices addressed to you, your section, or all students will appear here.")}</p></div> : null}
        <div className={styles.messageList}>
          {communications?.items.map((item) => {
            const titleContent = localizedContent(locale, item.titleEn, item.titleHi);
            const bodyContent = localizedContent(locale, item.bodyEn, item.bodyHi);
            const title = titleContent.primary;
            const body = bodyContent.primary;
            const secondaryBody = item.languageMode === "BILINGUAL" ? bodyContent.secondary : null;
            return (
              <article className={!item.readAt ? styles.unread : ""} key={item.recipientId}>
                <header><div><span>{displayCode(item.category)} · {displayCode(item.priority)}</span><h3 lang={title ? titleContent.primaryLanguage : locale}>{title || t("School message")}</h3><small>{dateTimeLabel(item.scheduledFor)}</small></div>{!item.readAt ? <em>{t("New")}</em> : item.acknowledgedAt ? <em>{t("Acknowledged")}</em> : null}</header>
                <p lang={body ? bodyContent.primaryLanguage : locale}>{body || t("This message has no content in your selected language.")}</p>
                {secondaryBody && secondaryBody !== body ? <p lang={bodyContent.secondaryLanguage} className={styles.secondaryLanguage}>{secondaryBody}</p> : null}
                <footer><span>{displayCode(item.audienceKind)}{item.expiresAt ? ` · available until ${dateTimeLabel(item.expiresAt)}` : ""}</span><div>{!item.readAt ? <button type="button" disabled={messageBusy === item.recipientId} onClick={() => void recordMessage(item.recipientId, "read")}>{t("Mark read")}</button> : null}{item.requiresAcknowledgement && !item.acknowledgedAt ? <button type="button" className={styles.primaryButton} disabled={messageBusy === item.recipientId} onClick={() => void recordMessage(item.recipientId, "acknowledge")}>{t("Acknowledge")}</button> : null}</div></footer>
              </article>
            );
          })}
        </div>
      </section>

      <section className={styles.privacyFooter}>
        <User />
        <div><strong>{t("Your portal is deliberately limited")}</strong><p>{t("It shows only your own learning records. Guardian details, fee accounts, transport operations, staff records, and other learners are never available through this account.")}</p></div>
      </section>
    </main>
  );
}
