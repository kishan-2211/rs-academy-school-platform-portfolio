"use client";

import { useT } from "@/components/i18n/language-provider";

import { useEffect, useMemo, useState } from "react";
import { Calendar, Check, Clock, Shield, Sparkles } from "@/components/icons";

type TeachingGroup = {
  allocationId: string;
  offeringId: string;
  academicSessionId: string;
  academicSessionName: string;
  sectionId: string;
  gradeCode: string;
  sectionCode: string;
  sectionName: string;
  subjectId: string;
  subjectName: string;
  subjectShortName: string | null;
  weeklyTargetPeriods: number;
  allocationRole: "PRIMARY" | "CO_TEACHER";
  startsOn: string;
  endsOn: string | null;
};

type ScheduleEntry = {
  entryId: string;
  timetablePlanId: string;
  timetableName: string;
  publishedAt: string;
  campusId: string;
  campusName: string;
  academicSessionId: string;
  academicSessionName: string;
  academicTermId: string | null;
  academicTermName: string | null;
  sectionId: string;
  gradeCode: string;
  sectionCode: string;
  sectionName: string;
  dayOfWeek: number;
  sequenceNumber: number;
  periodName: string;
  startsAt: string;
  endsAt: string;
  subjectName: string;
  subjectShortName: string | null;
  roomLabel: string | null;
  note: string | null;
};

type Snapshot = {
  schoolId: string;
  schoolName: string;
  teachingGroups: TeachingGroup[];
  scheduleEntries: ScheduleEntry[];
};

type Problem = { detail?: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const days = [
  { value: 1, short: "Mon", label: "Monday" },
  { value: 2, short: "Tue", label: "Tuesday" },
  { value: 3, short: "Wed", label: "Wednesday" },
  { value: 4, short: "Thu", label: "Thursday" },
  { value: 5, short: "Fri", label: "Friday" },
  { value: 6, short: "Sat", label: "Saturday" },
  { value: 7, short: "Sun", label: "Sunday" },
] as const;

function dayLabel(value: number) {
  return days.find((day) => day.value === value)?.label ?? `Day ${value}`;
}

function formatTime(value: string) {
  const [hourText = "0", minute = "00"] = value.split(":");
  const hour = Number(hourText);
  const suffix = hour >= 12 ? "PM" : "AM";
  return `${hour % 12 || 12}:${minute} ${suffix}`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(`${value}T12:00:00`));
}

function titleCase(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

async function problem(response: Response) {
  try {
    return ((await response.json()) as Problem).detail || "Your teaching workspace is unavailable.";
  } catch {
    return "Your teaching workspace is unavailable.";
  }
}

export function TeacherAcademicWorkspace() {
  const t = useT("teacher");
  const [data, setData] = useState<Snapshot | null>(null);
  const [selectedDay, setSelectedDay] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    fetch(`${apiUrl}/api/v1/academics/me`, { credentials: "include" })
      .then(async (response) => {
        if (!response.ok) throw new Error(await problem(response));
        return (await response.json()) as Snapshot;
      })
      .then((snapshot) => {
        if (!active) return;
        setData(snapshot);
        const browserDay = new Date().getDay() || 7;
        setSelectedDay(
          snapshot.scheduleEntries.some((entry) => entry.dayOfWeek === browserDay) ? browserDay : 0,
        );
      })
      .catch((reason: Error) => {
        if (active) setError(reason.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const availableDays = useMemo(
    () => new Set(data?.scheduleEntries.map((entry) => entry.dayOfWeek) ?? []),
    [data],
  );
  const visibleEntries =
    data?.scheduleEntries.filter((entry) => selectedDay === 0 || entry.dayOfWeek === selectedDay) ??
    [];
  const publishedPlans = new Set(data?.scheduleEntries.map((entry) => entry.timetablePlanId) ?? []);

  if (loading) {
    return (
      <section className="teacher-academic-state" aria-live="polite">
        <i />
        <p>{t("Opening your assigned teaching view…")}</p>
      </section>
    );
  }
  if (error || !data) {
    return (
      <section className="teacher-academic-state teacher-academic-state--error" role="alert">
        <Shield />
        <h1>{t("Teaching view unavailable")}</h1>
        <p>{t(error || "No authorized school scope was returned.")}</p>
        <button type="button" onClick={() => window.location.reload()}>
          {t("Try again")} </button>
      </section>
    );
  }

  return (
    <div className="teacher-academic">
      <section className="teacher-academic__hero">
        <div>
          <span>{t("My teaching ·")} {data.schoolName}</span>
          <h1>
            {t("Your classes.")} <em>{t("Your published school week.")}</em>
          </h1>
          <p>
            {t("This view is read-only and limited to the teaching allocations attached to your signed-in school membership.")} </p>
        </div>
        <aside aria-label={t("Teaching summary")}>
          <Sparkles />
          <div>
            <strong>{data.teachingGroups.length}</strong>
            <span>{t(data.teachingGroups.length === 1 ? "assigned group" : "assigned groups")}</span>
          </div>
          <div>
            <strong>{data.scheduleEntries.length}</strong>
            <span>{t(data.scheduleEntries.length === 1 ? "published period" : "published periods")}</span>
          </div>
        </aside>
      </section>

      <section className="teacher-academic__trust">
        <span>
          <Check /> {t("Membership-scoped")} </span>
        <span>
          <Check /> {t("Published timetable only")} </span>
        <span>
          <Check /> {t("No other teacher's allocation")} </span>
        <small>
          {publishedPlans.size} {t(publishedPlans.size === 1 ? "published timetable source" : "published timetable sources")}
        </small>
      </section>

      <section className="teacher-academic__schedule" aria-labelledby="teacher-schedule-title">
        <header>
          <div>
            <span>{t("School-published timetable")}</span>
            <h2 id="teacher-schedule-title">{t("My week")}</h2>
          </div>
          <p>
            {t("Draft changes do not appear here. Authority must publish a timetable revision first.")} </p>
        </header>
        <div className="teacher-academic__days" role="group" aria-label={t("Filter timetable by day")}>
          <button
            className={selectedDay === 0 ? "is-active" : ""}
            type="button"
            aria-pressed={selectedDay === 0}
            onClick={() => setSelectedDay(0)}
          >
            {t("All week")} </button>
          {days
            .filter((day) => availableDays.has(day.value))
            .map((day) => (
              <button
                className={selectedDay === day.value ? "is-active" : ""}
                type="button"
                aria-pressed={selectedDay === day.value}
                onClick={() => setSelectedDay(day.value)}
                key={day.value}
              >
                <span>{t(day.short)}</span>
                <small>
                  {data.scheduleEntries.filter((entry) => entry.dayOfWeek === day.value).length}
                </small>
              </button>
            ))}
        </div>

        {visibleEntries.length === 0 ? (
          <div className="teacher-academic__empty">
            <Calendar />
            <h3>{t("No published periods")}</h3>
            <p>
              {t("Your assigned periods will appear after school authority publishes a timetable containing your membership.")} </p>
          </div>
        ) : (
          <div className="teacher-academic__entries">
            {visibleEntries.map((entry) => (
              <article key={entry.entryId}>
                <time>
                  <span>{t(dayLabel(entry.dayOfWeek))}</span>
                  <strong>{formatTime(entry.startsAt)}</strong>
                  <small>{t("to")} {formatTime(entry.endsAt)}</small>
                </time>
                <div>
                  <span>
                    {entry.periodName} {t("· Period")} {entry.sequenceNumber}
                  </span>
                  <h3>{entry.subjectName}</h3>
                  <p>
                    {entry.gradeCode} · {entry.sectionName} ({entry.sectionCode})
                  </p>
                  <small>
                    {entry.academicSessionName}
                    {entry.academicTermName ? ` · ${entry.academicTermName}` : ""} ·{" "}
                    {entry.campusName}
                  </small>
                </div>
                <aside>
                  {entry.roomLabel && <span>{t("Room")} {entry.roomLabel}</span>}
                  <small>
                    {t("Published")}{" "}
                    {new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short" }).format(
                      new Date(entry.publishedAt),
                    )}
                  </small>
                </aside>
                {entry.note && <p className="teacher-academic__note">{entry.note}</p>}
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="teacher-academic__groups" aria-labelledby="teacher-groups-title">
        <header>
          <div>
            <span>{t("Authority-managed allocation")}</span>
            <h2 id="teacher-groups-title">{t("My teaching groups")}</h2>
          </div>
          <p>
            {t("Class, subject, weekly target, role, and effective dates come from the school's academic configuration.")} </p>
        </header>
        {data.teachingGroups.length === 0 ? (
          <div className="teacher-academic__empty">
            <Clock />
            <h3>{t("No active allocation")}</h3>
            <p>{t("Ask school authority to assign a class and subject to this membership.")}</p>
          </div>
        ) : (
          <div className="teacher-academic__group-grid">
            {data.teachingGroups.map((group) => (
              <article key={group.allocationId}>
                <span>{t(titleCase(group.allocationRole))}</span>
                <h3>{group.subjectName}</h3>
                <p>
                  {group.gradeCode} · {group.sectionName} ({group.sectionCode})
                </p>
                <dl>
                  <div>
                    <dt>{t("Session")}</dt>
                    <dd>{group.academicSessionName}</dd>
                  </div>
                  <div>
                    <dt>{t("Weekly target")}</dt>
                    <dd>
                      {group.weeklyTargetPeriods} {t(group.weeklyTargetPeriods === 1 ? "period" : "periods")}
                    </dd>
                  </div>
                  <div>
                    <dt>{t("Effective")}</dt>
                    <dd>
                      {formatDate(group.startsOn)} –{" "}
                      {group.endsOn ? formatDate(group.endsOn) : t("Open ended")}
                    </dd>
                  </div>
                </dl>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
