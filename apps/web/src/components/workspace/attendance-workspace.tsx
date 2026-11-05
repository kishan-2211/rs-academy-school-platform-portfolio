"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { useCodeLabel, useT } from "@/components/i18n/language-provider";
import { LocalizedForm } from "@/components/i18n/localized-form";
import { Check, Shield, Sparkles } from "@/components/icons";
import { DraftLeaveReview } from "@/components/workspace/draft-leave-review";
import {
  attendanceLeaveMessage,
  leaveCorrectionSource,
  reconcileReviewedAttendanceDraft,
  type AttendanceLeaveEvidence,
  type LeaveCorrectionSource,
} from "@/lib/attendance-leave";

type Session = { id: string; name: string; startsOn: string; endsOn: string; status: string };
type Campus = { id: string; name: string };
type Section = { id: string; campusId: string; sessionId: string; gradeCode: string; name: string };
type Period = {
  id: string;
  campusId: string;
  sessionId: string;
  dayOfWeek: number;
  name: string;
  startsAt: string;
  endsAt: string;
};
type Policy = {
  id: string;
  sessionId: string;
  name: string;
  registerMode: string;
  enabledStates: string[];
  stateWeights: Record<string, number>;
  submissionCutoff: string | null;
  lockAfterHours: number;
  guardianExplanationDays: number;
  warningThresholdPercent: number | null;
  status: string;
  rowVersion: number;
};
type CalendarDay = {
  id: string;
  campusId: string;
  sessionId: string;
  date: string;
  dayType: string;
  name: string;
  attendanceRequired: boolean;
  status: string;
  rowVersion: number;
};
type Mark = {
  id: string;
  learnerId: string;
  learnerName: string;
  rollNumber: string | null;
  stateCode: string;
  note: string | null;
  rowVersion: number;
  leaveEvidence?: AttendanceLeaveEvidence[];
};
type Register = {
  id: string;
  sectionId: string;
  calendarDayId: string;
  periodId: string | null;
  mode: string;
  enabledStates: string[];
  status: string;
  expectedLearnerCount: number;
  recordedCount: number;
  absentCount: number;
  submittedAt: string | null;
  rowVersion: number;
  marks: Mark[];
};
type Correction = {
  id: string;
  markId: string;
  learnerName: string;
  currentState: string;
  requestedState: string;
  requestReason: string;
  requesterName: string;
  rowVersion: number;
  leaveSource?: LeaveCorrectionSource;
};
type Explanation = {
  id: string;
  markId: string;
  learnerName: string;
  stateCode: string;
  guardianName: string;
  explanation: string;
  rowVersion: number;
};
type Snapshot = {
  schoolId: string;
  schoolName: string;
  canConfigure: boolean;
  canCorrect: boolean;
  sessions: Session[];
  campuses: Campus[];
  sections: Section[];
  periods: Period[];
  policies: Policy[];
  calendarDays: CalendarDay[];
  registers: Register[];
  corrections: Correction[];
  explanations: Explanation[];
};
type Problem = { detail?: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const attendanceStates = ["PRESENT", "ABSENT", "LATE", "EXCUSED", "HALF_DAY", "SCHOOL_ACTIVITY"];
const initialWeights: Record<string, number> = {
  PRESENT: 1,
  ABSENT: 0,
  LATE: 0.75,
  EXCUSED: 0,
  HALF_DAY: 0.5,
  SCHOOL_ACTIVITY: 1,
};

function today() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function isoDay(value: string) {
  const day = new Date(`${value}T12:00:00`).getDay();
  return day === 0 ? 7 : day;
}

function cookie(name: string) {
  return document.cookie
    .split("; ")
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

function formatCode(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

async function problem(response: Response, fallback = "The attendance change could not be saved.") {
  try {
    return ((await response.json()) as Problem).detail || fallback;
  } catch {
    return fallback;
  }
}

export function AttendanceWorkspace() {
  const t = useT("attendance");
  const label = useCodeLabel(formatCode);
  const [date, setDate] = useState(today);
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<"register" | "setup" | "review">("register");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [activeRegisterId, setActiveRegisterId] = useState("");
  const [draftMarks, setDraftMarks] = useState<Record<string, { state: string; note: string }>>({});
  const [markReason, setMarkReason] = useState("");
  const [submitReason, setSubmitReason] = useState("");
  const [correctionMarkId, setCorrectionMarkId] = useState("");
  const [correctionState, setCorrectionState] = useState("PRESENT");
  const [correctionReason, setCorrectionReason] = useState("");
  const [correctionUseLeave, setCorrectionUseLeave] = useState(false);
  const [reviewReasons, setReviewReasons] = useState<Record<string, string>>({});
  const [enabledStates, setEnabledStates] = useState<string[]>(attendanceStates);
  const [weights, setWeights] = useState<Record<string, number>>(initialWeights);
  const [editingPolicyId, setEditingPolicyId] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/attendance?date=${date}`, {
      credentials: "include",
    });
    if (!response.ok) throw new Error(await problem(response, "Attendance is unavailable."));
    return (await response.json()) as Snapshot;
  }, [date]);

  const refresh = useCallback(async () => {
    const result = await load();
    setData(result);
    setActiveRegisterId((current) =>
      result.registers.some((register) => register.id === current)
        ? current
        : result.registers[0]?.id || "",
    );
    return result;
  }, [load]);

  useEffect(() => {
    let cancelled = false;
    load()
      .then((result) => {
        if (cancelled) return;
        setData(result);
        setActiveRegisterId(result.registers[0]?.id || "");
      })
      .catch((reason: Error) => {
        if (!cancelled) setError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [load]);

  const activeRegister = data?.registers.find((item) => item.id === activeRegisterId) ?? null;
  const selectedCalendarDay = data?.calendarDays.find(
    (item) => item.date === date && item.status === "ACTIVE",
  );
  const registerPolicy = selectedCalendarDay
    ? data?.policies.find(
        (item) => item.sessionId === selectedCalendarDay.sessionId && item.status === "ACTIVE",
      )
    : undefined;
  const editingPolicy = data?.policies.find((item) => item.id === editingPolicyId) ?? null;

  const mutate = useCallback(
    async (
      path: string,
      method: "POST" | "PUT",
      body: object,
      success: string,
      selectReturnedRegister = false,
    ) => {
      setBusy(true);
      setError("");
      setNotice("");
      try {
        let csrf = cookie("educore_csrf");
        if (!csrf) {
          await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
          csrf = cookie("educore_csrf");
        }
        if (!csrf) throw new Error("The secure form token is unavailable. Refresh and try again.");
        const response = await fetch(`${apiUrl}${path}`, {
          method,
          credentials: "include",
          headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": decodeURIComponent(csrf) },
          body: JSON.stringify(body),
        });
        if (!response.ok) throw new Error(await problem(response));
        const result = (await response.json()) as { id?: string };
        await refresh();
        if (selectReturnedRegister && result.id) setActiveRegisterId(result.id);
        setNotice(success);
        return true;
      } catch (reason) {
        setError(
          reason instanceof Error ? reason.message : "The attendance change could not be saved.",
        );
        return false;
      } finally {
        setBusy(false);
      }
    },
    [refresh],
  );

  const sectionName = (id: string) =>
    data?.sections.find((item) => item.id === id)?.name || t("Class");
  const periodName = (id: string | null) =>
    id ? data?.periods.find((item) => item.id === id)?.name || t("Period") : t("Daily register");
  const changedMarkCount = useMemo(
    () =>
      activeRegister?.marks.filter((mark) => {
        const draft = draftMarks[mark.id];
        return draft && (draft.state !== mark.stateCode || draft.note !== (mark.note || ""));
      }).length ?? 0,
    [activeRegister, draftMarks],
  );

  if (loading)
    return (
      <section className="attendance-loading" aria-live="polite">
        <i />
        <p>{t("Opening the attendance register…")}</p>
      </section>
    );
  if (!data)
    return (
      <section className="attendance-empty" role="alert">
        <Shield />
        <h1>{t("Attendance is unavailable")}</h1>
        <p>{t(error)}</p>
      </section>
    );

  async function openRegister(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const mode = form.get("mode")?.toString() || "DAILY";
    await mutate(
      "/api/v1/attendance/registers",
      "POST",
      {
        schoolId: data!.schoolId,
        sectionId: form.get("section"),
        calendarDayId: form.get("calendarDay"),
        periodId: mode === "PERIOD" ? form.get("period") : null,
        mode,
      },
      "Register is ready. Everyone starts as present; record only the exceptions, then submit.",
      true,
    );
  }

  async function saveMarks() {
    if (!activeRegister) return;
    const changed = activeRegister.marks.filter((mark) => {
      const draft = draftMarks[mark.id];
      return draft && (draft.state !== mark.stateCode || draft.note !== (mark.note || ""));
    });
    await mutate(
      `/api/v1/attendance/registers/${activeRegister.id}/marks`,
      "PUT",
      {
        schoolId: data!.schoolId,
        expectedRegisterVersion: activeRegister.rowVersion,
        reason: markReason,
        marks: changed.map((mark) => {
          const draft = draftMarks[mark.id]!;
          return {
            learnerId: mark.learnerId,
            stateCode: draft.state,
            note: draft.note || null,
            expectedVersion: mark.rowVersion,
          };
        }),
      },
      t(changed.length === 1 ? "{count} attendance mark saved." : "{count} attendance marks saved.", { count: changed.length }),
    );
  }

  async function submitRegister() {
    if (!activeRegister) return;
    const ok = await mutate(
      `/api/v1/attendance/registers/${activeRegister.id}/submit`,
      "POST",
      {
        schoolId: data!.schoolId,
        expectedVersion: activeRegister.rowVersion,
        reason: submitReason,
      },
      "Attendance submitted and frozen. Any later change now needs a reviewed correction.",
    );
    if (ok) setSubmitReason("");
  }

  async function requestCorrection(markId: string) {
    const mark = activeRegister?.marks.find((item) => item.id === markId);
    if (!mark) return;
    const approval = mark.leaveEvidence?.find((item) => item.status === "APPROVED");
    if (correctionUseLeave && !approval) {
      setError(
        "The approved leave is no longer available. Refresh before requesting a correction.",
      );
      return;
    }
    let source: LeaveCorrectionSource | undefined;
    try {
      if (correctionUseLeave && approval) source = leaveCorrectionSource(approval, mark.rowVersion);
    } catch (error) {
      setError(error instanceof Error ? error.message : "Refresh the correction source.");
      return;
    }
    const ok = await mutate(
      "/api/v1/attendance/corrections",
      "POST",
      {
        schoolId: data!.schoolId,
        markId,
        requestedState: source && approval ? approval.attendanceState : correctionState,
        reason: correctionReason,
        ...(source ? { leaveSource: source } : {}),
      },
      "Correction sent to attendance authority for review.",
    );
    if (ok) {
      setCorrectionMarkId("");
      setCorrectionReason("");
      setCorrectionUseLeave(false);
    }
  }

  async function savePolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const editing = editingPolicy;
    await mutate(
      editing ? `/api/v1/attendance/policies/${editing.id}` : "/api/v1/attendance/policies",
      editing ? "PUT" : "POST",
      {
        schoolId: data!.schoolId,
        sessionId: form.get("session"),
        name: form.get("name"),
        registerMode: form.get("mode"),
        enabledStates,
        stateWeights: Object.fromEntries(enabledStates.map((state) => [state, weights[state]])),
        submissionCutoff: form.get("cutoff") || null,
        lockAfterHours: Number(form.get("lockHours")),
        guardianExplanationDays: Number(form.get("explanationDays")),
        warningThresholdPercent: form.get("threshold") || null,
        status: form.get("status"),
        expectedVersion: editing?.rowVersion ?? null,
        reason: form.get("reason") || null,
      },
      editing ? "Attendance policy updated." : "Attendance policy created.",
    );
  }

  async function saveCalendarDay(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const existing = data!.calendarDays.find(
      (item) => item.date === form.get("date") && item.campusId === form.get("campus"),
    );
    await mutate(
      existing
        ? `/api/v1/attendance/calendar-days/${existing.id}`
        : "/api/v1/attendance/calendar-days",
      existing ? "PUT" : "POST",
      {
        schoolId: data!.schoolId,
        campusId: form.get("campus"),
        sessionId: form.get("session"),
        date: form.get("date"),
        dayType: form.get("dayType"),
        name: form.get("name"),
        attendanceRequired: form.get("required") === "on",
        status: form.get("status"),
        expectedVersion: existing?.rowVersion ?? null,
        reason: form.get("reason") || null,
      },
      existing ? "Calendar day updated." : "Calendar day added.",
    );
  }

  async function decide(
    kind: "corrections" | "explanations",
    id: string,
    decision: string,
    version: number,
  ) {
    const reason = reviewReasons[id]?.trim();
    if (!reason) return;
    await mutate(
      `/api/v1/attendance/${kind}/${id}/decision`,
      "POST",
      { schoolId: data!.schoolId, decision, reason, expectedVersion: version },
      kind === "corrections" ? "Attendance correction reviewed." : "Attendance explanation reviewed.",
    );
  }

  return (
    <section className="attendance-workspace">
      <header className="attendance-hero">
        <div>
          <span>{t("Accountable school day")}</span>
          <h1>{t("Attendance that is fast to take and hard to rewrite.")}</h1>
          <p>
            {data.schoolName} {t("controls its own working days, attendance modes, states, credit weights, cut-offs, warning level, and explanation window.")} </p>
        </div>
        <Sparkles />
      </header>

      <nav className="attendance-tabs" aria-label={t("Attendance workspace sections")}>
        <button
          className={tab === "register" ? "is-active" : ""}
          aria-pressed={tab === "register"}
          type="button"
          onClick={() => setTab("register")}
        >
          {t("Take attendance")} </button>
        {data.canConfigure && (
          <button
            className={tab === "setup" ? "is-active" : ""}
            aria-pressed={tab === "setup"}
            type="button"
            onClick={() => {
              setTab("setup");
              const firstPolicy = data.policies[0];
              setEditingPolicyId(firstPolicy?.id || "");
              if (firstPolicy) {
                setEnabledStates(firstPolicy.enabledStates);
                setWeights({ ...initialWeights, ...firstPolicy.stateWeights });
              } else {
                setEnabledStates(attendanceStates);
                setWeights(initialWeights);
              }
            }}
          >
            {t("School rules & calendar")} </button>
        )}
        {data.canCorrect && (
          <button className={tab === "review" ? "is-active" : ""} aria-pressed={tab === "review"} type="button" onClick={() => setTab("review")}>
            {t("Review queue (")}{data.corrections.length + data.explanations.length})
          </button>
        )}
      </nav>

      {(error || notice) && (
        <div
          className={error ? "attendance-message is-error" : "attendance-message"}
          role={error ? "alert" : "status"}
        >
          {error ? <Shield /> : <Check />}
          <span>{t(error || notice)}</span>
        </div>
      )}

      {tab === "register" && (
        <div className="attendance-register-layout">
          <aside className="attendance-register-picker">
            <label>
              <span>{t("Attendance date")}</span>
              <input
                type="date"
                value={date}
                onChange={(event) => {
                  setLoading(true);
                  setDate(event.target.value);
                }}
              />
            </label>
            {!selectedCalendarDay ? (
              <div className="attendance-empty attendance-empty--small">
                <strong>{t("No attendance day configured")}</strong>
                <p>
                  {t("An authority must add this date to the school calendar before a register can open.")} </p>
              </div>
            ) : !selectedCalendarDay.attendanceRequired ? (
              <div className="attendance-empty attendance-empty--small">
                <strong>{selectedCalendarDay.name}</strong>
                <p>{t("This day is configured without learner attendance.")}</p>
              </div>
            ) : (
              <LocalizedForm onSubmit={openRegister} className="attendance-form">
                <input type="hidden" name="calendarDay" value={selectedCalendarDay.id} />
                <label>
                  <span>{t("Class / section")}</span>
                  <select name="section" required defaultValue="">
                    <option value="" disabled>
                      {t("Select")} </option>
                    {data.sections
                      .filter(
                        (item) =>
                          item.campusId === selectedCalendarDay.campusId &&
                          item.sessionId === selectedCalendarDay.sessionId,
                      )
                      .map((item) => (
                        <option value={item.id} key={item.id}>
                          {item.name}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  <span>{t("Register type")}</span>
                  <select
                    name="mode"
                    defaultValue={registerPolicy?.registerMode === "PERIOD" ? t("PERIOD") : t("DAILY")}
                  >
                    {registerPolicy?.registerMode !== "PERIOD" && (
                      <option value="DAILY">{t("Daily")}</option>
                    )}
                    {registerPolicy?.registerMode !== "DAILY" && (
                      <option value="PERIOD">{t("Teaching period")}</option>
                    )}
                  </select>
                </label>
                <label>
                  <span>{t("Period (for period attendance)")}</span>
                  <select name="period" defaultValue="">
                    <option value="">{t("Choose when needed")}</option>
                    {data.periods
                      .filter(
                        (item) =>
                          item.campusId === selectedCalendarDay.campusId &&
                          item.sessionId === selectedCalendarDay.sessionId &&
                          item.dayOfWeek === isoDay(date),
                      )
                      .map((item) => (
                        <option value={item.id} key={item.id}>
                          {item.name} · {item.startsAt.slice(0, 5)}
                        </option>
                      ))}
                  </select>
                </label>
                <button className="button button--dark" disabled={busy}>
                  {t("Open or continue register")} </button>
              </LocalizedForm>
            )}
            <div className="attendance-register-list">
              {data.registers.map((register) => (
                <button
                  type="button"
                  key={register.id}
                  className={register.id === activeRegisterId ? "is-active" : ""}
                  onClick={() => {
                    setActiveRegisterId(register.id);
                    setDraftMarks(
                      Object.fromEntries(
                        register.marks.map((mark) => [
                          mark.id,
                          { state: mark.stateCode, note: mark.note || "" },
                        ]),
                      ),
                    );
                    setMarkReason("");
                    setSubmitReason("");
                    setCorrectionMarkId("");
                    setCorrectionReason("");
                  }}
                >
                  <span>
                    <strong>{sectionName(register.sectionId)}</strong>
                    <small>{periodName(register.periodId)}</small>
                  </span>
                  <em>{label(register.status)}</em>
                </button>
              ))}
            </div>
          </aside>

          <section className="attendance-sheet" aria-label={t("Attendance register")}>
            {!activeRegister ? (
              <div className="attendance-empty">
                <Shield />
                <h2>{t("No register open for this date")}</h2>
                <p>
                  {t("Choose a configured class and open its register. The dated class roster is loaded automatically.")} </p>
              </div>
            ) : (
              <>
                <header>
                  <div>
                    <span>{periodName(activeRegister.periodId)}</span>
                    <h2>{sectionName(activeRegister.sectionId)}</h2>
                    <p>
                      {activeRegister.recordedCount} {t("of")} {activeRegister.expectedLearnerCount}{" "}
                      {t("learners ·")} {activeRegister.absentCount} {t("absent/excused")} </p>
                  </div>
                  <strong
                    className={`attendance-status attendance-status--${activeRegister.status.toLowerCase()}`}
                  >
                    {label(activeRegister.status)}
                  </strong>
                </header>
                {activeRegister.status === "DRAFT" && (
                  <div className="attendance-bulk">
                    <button
                      type="button"
                      onClick={() =>
                        setDraftMarks(
                          Object.fromEntries(
                            activeRegister.marks.map((mark) => [
                              mark.id,
                              { state: "PRESENT", note: "" },
                            ]),
                          ),
                        )
                      }
                    >
                      {t("Mark everyone present")} </button>
                    <span>{t("Then change only the exceptions below.")}</span>
                  </div>
                )}
                <div className="attendance-learners">
                  {activeRegister.marks.map((mark, index) => {
                    const approval = mark.leaveEvidence?.find(
                      (item) =>
                        item.status === "APPROVED" &&
                        activeRegister.enabledStates.includes(item.attendanceState),
                    );
                    const draft = draftMarks[mark.id] ?? {
                      state: mark.stateCode,
                      note: mark.note || "",
                    };
                    return (
                      <article
                        key={mark.id}
                        className={draft.state === "PRESENT" ? "" : "has-exception"}
                      >
                        <span className="attendance-roll">
                          {mark.rollNumber || String(index + 1).padStart(2, "0")}
                        </span>
                        <div>
                          <strong>{mark.learnerName}</strong>
                          <small>{label(draft.state)}</small>
                          {mark.leaveEvidence?.map((evidence) => (
                            <div
                              key={`${evidence.requestId}:${evidence.requestVersion}:${mark.rowVersion}`}
                            >
                              <small className="attendance-leave-context">
                                {t(attendanceLeaveMessage(evidence, mark.stateCode))}
                              </small>
                              {activeRegister.status === "DRAFT" && (
                                <DraftLeaveReview
                                  schoolId={data.schoolId}
                                  registerId={activeRegister.id}
                                  registerVersion={activeRegister.rowVersion}
                                  markId={mark.id}
                                  markVersion={mark.rowVersion}
                                  learnerName={mark.learnerName}
                                  evidence={evidence}
                                  disabled={busy || changedMarkCount > 0}
                                  onSaved={async () => {
                                    const refreshed = await refresh();
                                    const saved = refreshed.registers
                                      .find((register) => register.id === activeRegister.id)
                                      ?.marks.find((item) => item.id === mark.id);
                                    if (saved)
                                      setDraftMarks((current) =>
                                        reconcileReviewedAttendanceDraft(current, mark, saved),
                                      );
                                  }}
                                />
                              )}
                            </div>
                          ))}
                        </div>
                        {activeRegister.status === "DRAFT" ? (
                          <>
                            <select
                              aria-label={t("Attendance state for {name}", { name: mark.learnerName })}
                              value={draft.state}
                              onChange={(event) =>
                                setDraftMarks((current) => ({
                                  ...current,
                                  [mark.id]: { ...draft, state: event.target.value },
                                }))
                              }
                            >
                              {activeRegister.enabledStates.map((state) => (
                                <option value={state} key={state}>
                                  {label(state)}
                                </option>
                              ))}
                            </select>
                            <input
                              aria-label={t("Optional note for {name}", { name: mark.learnerName })}
                              placeholder={t("Optional note")}
                              maxLength={240}
                              value={draft.note}
                              onChange={(event) =>
                                setDraftMarks((current) => ({
                                  ...current,
                                  [mark.id]: { ...draft, note: event.target.value },
                                }))
                              }
                            />
                          </>
                        ) : (
                          <>
                            <span className="attendance-readonly-state">
                              {label(mark.stateCode)}
                            </span>
                            <button
                              className="attendance-correction-open"
                              type="button"
                              onClick={() => {
                                setCorrectionMarkId(mark.id);
                                setCorrectionState(mark.stateCode);
                                setCorrectionReason("");
                                setCorrectionUseLeave(false);
                              }}
                            >
                              {t("Request correction")} </button>
                          </>
                        )}
                        {correctionMarkId === mark.id && (
                          <div className="attendance-correction-form">
                            {approval && (
                              <label className="attendance-leave-source-choice">
                                <input
                                  type="checkbox"
                                  checked={correctionUseLeave}
                                  onChange={(event) => {
                                    setCorrectionUseLeave(event.target.checked);
                                    if (event.target.checked)
                                      setCorrectionState(approval.attendanceState);
                                  }}
                                />
                                <span>
                                  {t("Use approved leave as the correction source. Its current revision and this mark will be checked again on approval.")} </span>
                              </label>
                            )}
                            <label>
                              <span>{t("Correct state")}</span>
                              <select
                                value={correctionState}
                                disabled={correctionUseLeave}
                                onChange={(event) => setCorrectionState(event.target.value)}
                              >
                                {activeRegister.enabledStates.map((state) => (
                                  <option value={state} key={state}>
                                    {label(state)}
                                  </option>
                                ))}
                              </select>
                            </label>
                            <label>
                              <span>{t("Why should this submitted record change?")}</span>
                              <input
                                value={correctionReason}
                                onChange={(event) => setCorrectionReason(event.target.value)}
                                maxLength={240}
                              />
                            </label>
                            <button
                              type="button"
                              disabled={busy || !correctionReason.trim()}
                              onClick={() => void requestCorrection(mark.id)}
                            >
                              {t("Send for review")} </button>
                            <button type="button" onClick={() => setCorrectionMarkId("")}>
                              {t("Cancel")} </button>
                          </div>
                        )}
                      </article>
                    );
                  })}
                </div>
                {activeRegister.status === "DRAFT" && (
                  <footer className="attendance-actions">
                    <label>
                      <span>{t("Reason for saved changes")}</span>
                      <input
                        value={markReason}
                        onChange={(event) => setMarkReason(event.target.value)}
                        maxLength={240}
                        placeholder={t("For example: morning roll call")}
                      />
                    </label>
                    <button
                      type="button"
                      disabled={busy || !changedMarkCount || !markReason.trim()}
                      onClick={() => void saveMarks()}
                    >
                      {t("Save")} {changedMarkCount || ""} {changedMarkCount === 1 ? "change" : "changes"}
                    </button>
                    <label>
                      <span>{t("Submission reason")}</span>
                      <input
                        value={submitReason}
                        onChange={(event) => setSubmitReason(event.target.value)}
                        maxLength={240}
                        placeholder={t("Register checked by…")}
                      />
                    </label>
                    <button
                      className="button button--dark"
                      type="button"
                      disabled={busy || Boolean(changedMarkCount) || !submitReason.trim()}
                      onClick={() => void submitRegister()}
                    >
                      {t("Submit & freeze register")} </button>
                  </footer>
                )}
              </>
            )}
          </section>
        </div>
      )}

      {tab === "setup" && data.canConfigure && (
        <div className="attendance-setup">
          <article className="attendance-panel">
            <header>
              <span>{t("Configurable attendance policy")}</span>
              <h2>{editingPolicy ? t("Edit school rules") : t("Create school rules")}</h2>
            </header>
            <div className="attendance-policy-switcher">
              <label>
                <span>{t("Policy to edit")}</span>
                <select
                  value={editingPolicyId}
                  onChange={(event) => {
                    const id = event.target.value;
                    setEditingPolicyId(id);
                    const policy = data.policies.find((item) => item.id === id);
                    setEnabledStates(policy?.enabledStates || attendanceStates);
                    setWeights({ ...initialWeights, ...(policy?.stateWeights || {}) });
                  }}
                >
                  <option value="">{t("Create a policy for another session")}</option>
                  {data.policies.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.name} ·{" "}
                      {data.sessions.find((session) => session.id === item.sessionId)?.name}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <LocalizedForm
              key={editingPolicy?.id || "new-policy"}
              onSubmit={savePolicy}
              className="attendance-form attendance-form--grid"
            >
              <label>
                <span>{t("Academic session")}</span>
                <select name="session" required defaultValue={editingPolicy?.sessionId || ""}>
                  <option value="" disabled>
                    {t("Select")} </option>
                  {data.sessions.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>{t("Policy name")}</span>
                <input
                  name="name"
                  required
                  maxLength={120}
                  defaultValue={editingPolicy?.name || "School attendance policy"}
                />
              </label>
              <label>
                <span>{t("Register mode")}</span>
                <select name="mode" defaultValue={editingPolicy?.registerMode || "DAILY"}>
                  <option>{t("DAILY")}</option>
                  <option>{t("PERIOD")}</option>
                  <option>{t("BOTH")}</option>
                </select>
              </label>
              <label>
                <span>{t("Teacher submission cut-off")}</span>
                <input
                  name="cutoff"
                  type="time"
                  defaultValue={editingPolicy?.submissionCutoff?.slice(0, 5) || ""}
                />
              </label>
              <label>
                <span>{t("Lock after submission (hours)")}</span>
                <input
                  name="lockHours"
                  type="number"
                  min={0}
                  max={720}
                  defaultValue={editingPolicy?.lockAfterHours ?? 24}
                />
              </label>
              <label>
                <span>{t("Guardian explanation window (days)")}</span>
                <input
                  name="explanationDays"
                  type="number"
                  min={0}
                  max={90}
                  defaultValue={editingPolicy?.guardianExplanationDays ?? 7}
                />
              </label>
              <label>
                <span>{t("Warning threshold (%)")}</span>
                <input
                  name="threshold"
                  type="number"
                  min={0}
                  max={100}
                  step="0.01"
                  defaultValue={editingPolicy?.warningThresholdPercent ?? ""}
                  placeholder={t("School decides")}
                />
              </label>
              <label>
                <span>{t("Status")}</span>
                <select name="status" defaultValue={editingPolicy?.status || "ACTIVE"}>
                  <option>{t("ACTIVE")}</option>
                  <option>{t("INACTIVE")}</option>
                </select>
              </label>
              <fieldset className="attendance-state-weights">
                <legend>{t("Enabled states and attendance credit")}</legend>
                <p>{t("Credit is configurable from 0 (not counted) to 1 (fully counted).")}</p>
                {attendanceStates.map((state) => {
                  const required = state === "PRESENT" || state === "ABSENT";
                  const enabled = enabledStates.includes(state);
                  return (
                    <label key={state}>
                      <input
                        type="checkbox"
                        checked={enabled}
                        disabled={required}
                        onChange={(event) =>
                          setEnabledStates((current) =>
                            event.target.checked
                              ? [...current, state]
                              : current.filter((item) => item !== state),
                          )
                        }
                      />
                      <span>{label(state)}</span>
                      <input
                        aria-label={t("{state} attendance credit", { state: label(state) })}
                        type="number"
                        min={0}
                        max={1}
                        step="0.01"
                        disabled={!enabled}
                        value={weights[state]}
                        onChange={(event) =>
                          setWeights((current) => ({
                            ...current,
                            [state]: Number(event.target.value),
                          }))
                        }
                      />
                    </label>
                  );
                })}
              </fieldset>
              <label className="attendance-form__wide">
                <span>{editingPolicy ? t("Reason for policy change") : t("Setup note")}</span>
                <input name="reason" required={Boolean(editingPolicy)} maxLength={240} />
              </label>
              <button className="button button--dark" disabled={busy}>
                {editingPolicy ? t("Save school rules") : "Create policy"}
              </button>
            </LocalizedForm>
          </article>

          <article className="attendance-panel">
            <header>
              <span>{t("Authority-managed working days")}</span>
              <h2>{t("Add or change a calendar day")}</h2>
            </header>
            <LocalizedForm onSubmit={saveCalendarDay} className="attendance-form attendance-form--grid">
              <label>
                <span>{t("Campus")}</span>
                <select name="campus" required defaultValue={data.campuses[0]?.id || ""}>
                  {data.campuses.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>{t("Academic session")}</span>
                <select
                  name="session"
                  required
                  defaultValue={editingPolicy?.sessionId || registerPolicy?.sessionId || ""}
                >
                  <option value="" disabled>
                    {t("Select")} </option>
                  {data.sessions.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>{t("Date")}</span>
                <input name="date" type="date" required defaultValue={date} />
              </label>
              <label>
                <span>{t("Day type")}</span>
                <select name="dayType" defaultValue="INSTRUCTIONAL">
                  <option>{t("INSTRUCTIONAL")}</option>
                  <option>{t("EXAM")}</option>
                  <option>{t("EVENT")}</option>
                </select>
              </label>
              <label>
                <span>{t("Display name")}</span>
                <input
                  name="name"
                  required
                  maxLength={120}
                  placeholder={t("Regular school day / festival holiday")}
                />
              </label>
              <label>
                <span>{t("Status")}</span>
                <select name="status" defaultValue="ACTIVE">
                  <option>{t("ACTIVE")}</option>
                  <option>{t("INACTIVE")}</option>
                </select>
              </label>
              <label className="attendance-checkbox">
                <input name="required" type="checkbox" defaultChecked />
                <span>{t("Learner attendance is required")}</span>
              </label>
              <label className="attendance-form__wide">
                <span>{t("Reason when changing an existing date")}</span>
                <input name="reason" maxLength={240} />
              </label>
              <button className="button button--dark" disabled={busy}>
                {t("Save calendar day")} </button>
            </LocalizedForm>
            <div className="attendance-calendar-list">
              {data.calendarDays.map((item) => (
                <article key={item.id}>
                  <time>
                    {new Date(`${item.date}T12:00:00`).toLocaleDateString("en-IN", {
                      day: "2-digit",
                      month: "short",
                    })}
                  </time>
                  <span>
                    <strong>{item.name}</strong>
                    <small>
                      {label(item.dayType)} ·{" "}
                      {item.attendanceRequired ? t("Attendance required") : t("No attendance")}
                    </small>
                  </span>
                  <em>{item.status}</em>
                </article>
              ))}
            </div>
          </article>
        </div>
      )}

      {tab === "review" && data.canCorrect && (
        <div className="attendance-review">
          <article className="attendance-panel">
            <header>
              <span>{t("Post-submission changes")}</span>
              <h2>{t("Correction requests")}</h2>
            </header>
            {!data.corrections.length ? (
              <p className="attendance-panel__empty">{t("No correction requests are waiting.")}</p>
            ) : (
              data.corrections.map((item) => (
                <div className="attendance-review-card" key={item.id}>
                  <div>
                    <strong>{item.learnerName}</strong>
                    <span>
                      {label(item.currentState)} → {label(item.requestedState)}
                    </span>
                    <p>{item.requestReason}</p>
                    <small>{t("Requested by")} {item.requesterName}</small>
                    {item.leaveSource && (
                      <small>
                        {t("Linked to approved leave revision")} {item.leaveSource.requestVersion}{t(". Current approval and attendance versions are checked before this can be approved; obsolete requests can be rejected.")} </small>
                    )}
                  </div>
                  <label>
                    <span>{t("Review reason")}</span>
                    <input
                      value={reviewReasons[item.id] || ""}
                      onChange={(event) =>
                        setReviewReasons((current) => ({
                          ...current,
                          [item.id]: event.target.value,
                        }))
                      }
                      maxLength={240}
                    />
                  </label>
                  <footer>
                    <button
                      disabled={busy || !reviewReasons[item.id]?.trim()}
                      onClick={() =>
                        void decide("corrections", item.id, "REJECTED", item.rowVersion)
                      }
                    >
                      {t("Reject")} </button>
                    <button
                      className="button button--dark"
                      disabled={busy || !reviewReasons[item.id]?.trim()}
                      onClick={() =>
                        void decide("corrections", item.id, "APPROVED", item.rowVersion)
                      }
                    >
                      {t("Approve correction")} </button>
                  </footer>
                </div>
              ))
            )}
          </article>
          <article className="attendance-panel">
            <header>
              <span>{t("Guardian context")}</span>
              <h2>{t("Absence explanations")}</h2>
            </header>
            {!data.explanations.length ? (
              <p className="attendance-panel__empty">{t("No guardian explanations are waiting.")}</p>
            ) : (
              data.explanations.map((item) => (
                <div className="attendance-review-card" key={item.id}>
                  <div>
                    <strong>{item.learnerName}</strong>
                    <span>
                      {label(item.stateCode)} {t("· from")} {item.guardianName}
                    </span>
                    <p>{item.explanation}</p>
                  </div>
                  <label>
                    <span>{t("Review reason")}</span>
                    <input
                      value={reviewReasons[item.id] || ""}
                      onChange={(event) =>
                        setReviewReasons((current) => ({
                          ...current,
                          [item.id]: event.target.value,
                        }))
                      }
                      maxLength={240}
                    />
                  </label>
                  <footer>
                    <button
                      disabled={busy || !reviewReasons[item.id]?.trim()}
                      onClick={() =>
                        void decide("explanations", item.id, "DECLINED", item.rowVersion)
                      }
                    >
                      {t("Decline")} </button>
                    <button
                      className="button button--dark"
                      disabled={busy || !reviewReasons[item.id]?.trim()}
                      onClick={() =>
                        void decide("explanations", item.id, "ACCEPTED", item.rowVersion)
                      }
                    >
                      {t("Accept explanation")} </button>
                  </footer>
                </div>
              ))
            )}
          </article>
        </div>
      )}
    </section>
  );
}
