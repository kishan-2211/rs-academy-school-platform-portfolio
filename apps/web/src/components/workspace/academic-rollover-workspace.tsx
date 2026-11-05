"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { AcademicIcon, AttendanceIcon, PeopleIcon, ReportIcon } from "@/components/workspace/workspace-icons";

type Outcome = "" | "PROMOTE" | "RETAIN" | "LEAVE";
type LeaveStatus = "" | "INACTIVE" | "ALUMNI";
type Session = { id: string; name: string; startsOn: string; endsOn: string; status: string; version: number };
type Section = { id: string; campusId: string; sessionId: string; gradeCode: string; code: string; name: string; capacity: number | null; status: string };
type Student = {
  sourceEnrolmentId: string; learnerId: string; learnerName: string; academicSessionId: string;
  campusId: string; admissionNumber: string | null; gradeCode: string;
  sourcePlacementId: string | null; sourceSectionId: string | null; sourceSectionCode: string | null;
  rollNumber: string | null; enrolmentVersion: number; placementVersion: number | null;
};
type Batch = {
  id: string; sourceSessionId: string; sourceSessionName: string; targetSessionId: string;
  targetSessionName: string; promotedCount: number; retainedCount: number; leavingCount: number;
  executedAt: string; status: string; version: number;
};
type Workspace = { schoolId: string; schoolName: string; sessions: Session[]; sections: Section[]; students: Student[]; history: Batch[] };
type Decision = { outcome: Outcome; targetGradeCode: string; targetSectionCode: string; targetRollNumber: string; leaveStatus: LeaveStatus; reason: string };
type DecisionMap = Record<string, Decision>;
type StudentDecisionRequest = {
  sourceEnrolmentId: string; outcome: Exclude<Outcome, "">; targetGradeCode: string | null;
  targetSectionCode: string | null; targetRollNumber: string | null;
  leaveStatus: Exclude<LeaveStatus, ""> | null; reason: string;
};
type PreviewRequest = {
  schoolId: string; sourceSessionId: string; targetSessionName: string; targetStartsOn: string;
  targetEndsOn: string; copyTeachingAllocations: boolean; copyPublishedTimetable: boolean;
  copyFeePlans: boolean; copyCalendarPolicy: boolean; studentDecisions: StudentDecisionRequest[];
};
type CopyCounts = {
  students: number; promoted: number; retained: number; leaving: number; sections: number;
  terms: number; resources: number; offerings: number; allocations: number; periods: number;
  timetablePlans: number; timetableEntries: number; feePlans: number; feeItems: number; calendarPolicies: number;
};
type Preview = {
  schoolId: string; sourceSessionId: string; sourceSessionName: string; targetSessionName: string;
  targetStartsOn: string; targetEndsOn: string; snapshotHash: string; counts: CopyCounts; warnings: string[];
};
type Execution = { batchId: string; targetSessionId: string; targetSessionName: string; status: string; executedAt: string; counts: CopyCounts };
type ReviewState = { request: PreviewRequest; result: Preview };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const pageSize = 24;
const titleCase = (value: string) => value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
const shortDate = (value: string) => new Intl.DateTimeFormat("en-IN", { day: "2-digit", month: "short", year: "numeric" }).format(new Date(`${value}T12:00:00`));
const localDate = (date = new Date()) => new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);

function cookie(name: string) {
  return document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
}
async function csrfToken() {
  let token = cookie("educore_csrf");
  if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}
async function problem(response: Response, fallback: string) {
  try { const body = await response.json() as { detail?: string }; return body.detail || fallback; } catch { return fallback; }
}
function addDays(value: string, amount: number) {
  const date = new Date(`${value}T12:00:00Z`); date.setUTCDate(date.getUTCDate() + amount); return date.toISOString().slice(0, 10);
}
function suggestedTarget(session: Session) {
  const startsOn = addDays(session.endsOn, 1);
  const end = new Date(`${startsOn}T12:00:00Z`); end.setUTCFullYear(end.getUTCFullYear() + 1); end.setUTCDate(end.getUTCDate() - 1);
  const match = session.name.match(/(\d{4})(\s*[-–/]\s*)(\d{2,4})/);
  let name = `Next session after ${session.name}`;
  if (match?.[0] && match[1] && match[2] && match[3]) {
    const finalYear = Number(match[3]) + 1;
    name = session.name.replace(match[0], `${Number(match[1]) + 1}${match[2]}${String(finalYear).padStart(match[3].length, "0")}`);
  }
  return { name, startsOn, endsOn: end.toISOString().slice(0, 10) };
}
function blankDecision(): Decision {
  return { outcome: "", targetGradeCode: "", targetSectionCode: "", targetRollNumber: "", leaveStatus: "", reason: "" };
}
function decisionComplete(decision: Decision | undefined) {
  if (!decision || decision.reason.trim().length < 3) return false;
  if (decision.outcome === "LEAVE") return Boolean(decision.leaveStatus);
  return ["PROMOTE", "RETAIN"].includes(decision.outcome) && Boolean(decision.targetGradeCode && decision.targetSectionCode);
}

export function AcademicRolloverWorkspace() {
  const [data, setData] = useState<Workspace | null>(null);
  const [sourceSessionId, setSourceSessionId] = useState("");
  const [targetName, setTargetName] = useState("");
  const [targetStartsOn, setTargetStartsOn] = useState("");
  const [targetEndsOn, setTargetEndsOn] = useState("");
  const [copyAllocations, setCopyAllocations] = useState(true);
  const [copyTimetable, setCopyTimetable] = useState(true);
  const [copyFees, setCopyFees] = useState(true);
  const [copyPolicy, setCopyPolicy] = useState(true);
  const [decisions, setDecisions] = useState<DecisionMap>({});
  const [query, setQuery] = useState("");
  const [gradeFilter, setGradeFilter] = useState("");
  const [sectionFilter, setSectionFilter] = useState("");
  const [decisionFilter, setDecisionFilter] = useState("");
  const [page, setPage] = useState(1);
  const [bulkOutcome, setBulkOutcome] = useState<Outcome>("");
  const [bulkTarget, setBulkTarget] = useState("");
  const [bulkLeaveStatus, setBulkLeaveStatus] = useState<LeaveStatus>("");
  const [bulkReason, setBulkReason] = useState("");
  const [review, setReview] = useState<ReviewState | null>(null);
  const [executionReason, setExecutionReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [lastExecution, setLastExecution] = useState<Execution | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const idempotencyKey = useRef("");

  const prepareSource = useCallback((snapshot: Workspace, sessionId: string) => {
    const session = snapshot.sessions.find((item) => item.id === sessionId); if (!session) return;
    const target = suggestedTarget(session);
    setSourceSessionId(sessionId); setTargetName(target.name); setTargetStartsOn(target.startsOn); setTargetEndsOn(target.endsOn);
    setDecisions(Object.fromEntries(snapshot.students.filter((student) => student.academicSessionId === sessionId).map((student) => [student.sourceEnrolmentId, blankDecision()])));
    setQuery(""); setGradeFilter(""); setSectionFilter(""); setDecisionFilter(""); setPage(1);
    setReview(null); setLastExecution(null); setConfirmation(""); setExecutionReason(""); idempotencyKey.current = "";
  }, []);

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/academic-rollovers`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "The rollover workspace could not be opened."));
    const snapshot = await response.json() as Workspace; setData(snapshot);
    const available = snapshot.sessions.find((session) => session.status === "ACTIVE" && !snapshot.history.some((batch) => batch.sourceSessionId === session.id));
    if (available) prepareSource(snapshot, available.id);
  }, [prepareSource]);

  useEffect(() => { Promise.resolve().then(load).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "The rollover workspace could not be opened.")); }, [load]);

  const sourceSession = data?.sessions.find((session) => session.id === sourceSessionId) ?? null;
  const sourceStudents = useMemo(() => data?.students.filter((student) => student.academicSessionId === sourceSessionId) ?? [], [data, sourceSessionId]);
  const sourceSections = useMemo(() => data?.sections.filter((section) => section.sessionId === sourceSessionId && section.status === "ACTIVE") ?? [], [data, sourceSessionId]);
  const grades = useMemo(() => [...new Set(sourceSections.map((section) => section.gradeCode))].sort((left, right) => left.localeCompare(right, undefined, { numeric: true })), [sourceSections]);
  const sourceSectionCodes = useMemo(() => [...new Set(sourceStudents
    .filter((student) => !gradeFilter || student.gradeCode === gradeFilter)
    .map((student) => student.sourceSectionCode)
    .filter((value): value is string => Boolean(value)))].sort(), [sourceStudents, gradeFilter]);
  const filteredStudents = useMemo(() => {
    const search = query.trim().toLowerCase();
    return sourceStudents.filter((student) => {
      const decision = decisions[student.sourceEnrolmentId];
      const searchable = `${student.learnerName} ${student.admissionNumber || ""} ${student.rollNumber || ""}`.toLowerCase();
      return (!search || searchable.includes(search))
        && (!gradeFilter || student.gradeCode === gradeFilter)
        && (!sectionFilter || student.sourceSectionCode === sectionFilter)
        && (!decisionFilter || (decisionFilter === "PENDING" ? !decisionComplete(decision) : decision?.outcome === decisionFilter));
    });
  }, [sourceStudents, decisions, query, gradeFilter, sectionFilter, decisionFilter]);
  const completed = sourceStudents.filter((student) => decisionComplete(decisions[student.sourceEnrolmentId])).length;
  const totalPages = Math.max(1, Math.ceil(filteredStudents.length / pageSize));
  const currentPage = Math.min(page, totalPages);
  const visibleStudents = filteredStudents.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const executionOpen = Boolean(sourceSession && localDate() >= sourceSession.endsOn);
  const alreadyRolled = Boolean(data?.history.some((batch) => batch.sourceSessionId === sourceSessionId));
  const progress = sourceStudents.length ? Math.round((completed / sourceStudents.length) * 100) : 0;

  const bulkTargets = useMemo(() => {
    if (!filteredStudents.length) return [];
    const keys = new Map<string, Section>();
    for (const section of sourceSections) {
      const key = `${section.gradeCode}::${section.code}`;
      const worksForEveryCampus = filteredStudents.every((student) => sourceSections.some((candidate) =>
        candidate.campusId === student.campusId && candidate.gradeCode === section.gradeCode && candidate.code === section.code));
      if (worksForEveryCampus) keys.set(key, section);
    }
    return [...keys.entries()].sort((left, right) => left[0].localeCompare(right[0], undefined, { numeric: true }));
  }, [filteredStudents, sourceSections]);

  function invalidatePreview() {
    setReview(null); setConfirmation(""); setExecutionReason(""); idempotencyKey.current = ""; setNotice("");
  }
  function updateSetup(update: () => void) { update(); invalidatePreview(); }
  function changeSource(sessionId: string) {
    if (!data) return; setError(""); setNotice(""); prepareSource(data, sessionId);
  }
  function updateDecision(studentId: string, update: Partial<Decision>) {
    setDecisions((current) => ({ ...current, [studentId]: { ...(current[studentId] || blankDecision()), ...update } }));
    invalidatePreview();
  }
  function selectOutcome(student: Student, outcome: Outcome) {
    if (outcome === "RETAIN") {
      const matching = sourceSections.find((section) => section.campusId === student.campusId && section.gradeCode === student.gradeCode && section.code === student.sourceSectionCode)
        ?? sourceSections.find((section) => section.campusId === student.campusId && section.gradeCode === student.gradeCode);
      updateDecision(student.sourceEnrolmentId, {
        outcome, targetGradeCode: student.gradeCode, targetSectionCode: matching?.code || "",
        targetRollNumber: student.rollNumber || "", leaveStatus: "",
        reason: "Retained during authorized academic-session rollover",
      });
      return;
    }
    if (outcome === "LEAVE") {
      updateDecision(student.sourceEnrolmentId, {
        outcome, targetGradeCode: "", targetSectionCode: "", targetRollNumber: "", leaveStatus: "",
        reason: "Leaving status confirmed during authorized academic-session rollover",
      });
      return;
    }
    updateDecision(student.sourceEnrolmentId, {
      outcome, targetGradeCode: "", targetSectionCode: "", targetRollNumber: student.rollNumber || "",
      leaveStatus: "", reason: outcome ? "Promoted during authorized academic-session rollover" : "",
    });
  }
  function studentTargets(student: Student, outcome: Outcome) {
    return sourceSections.filter((section) => {
      if (section.campusId !== student.campusId) return false;
      if (outcome === "RETAIN") return section.gradeCode === student.gradeCode;
      if (outcome === "PROMOTE") return section.gradeCode !== student.gradeCode;
      return false;
    });
  }

  function applyBulk() {
    setError("");
    if (!filteredStudents.length) { setError("No students match the current filters."); return; }
    if (!bulkOutcome) { setError("Choose a bulk decision before applying it."); return; }
    if (bulkReason.trim().length < 3) { setError("Record a clear reason for this bulk decision."); return; }
    if (bulkOutcome === "LEAVE" && !bulkLeaveStatus) { setError("Choose the leaving status for the filtered students."); return; }
    const [targetGradeCode = "", targetSectionCode = ""] = bulkTarget.split("::");
    if (bulkOutcome === "PROMOTE" && (!targetGradeCode || !targetSectionCode)) { setError("Choose the target grade and section for this promotion."); return; }
    if (bulkOutcome === "PROMOTE" && filteredStudents.some((student) => student.gradeCode === targetGradeCode)) {
      setError("A promoted student must move to a different grade. Narrow the source-grade filter or choose another target."); return;
    }
    const next = { ...decisions };
    for (const student of filteredStudents) {
      if (bulkOutcome === "LEAVE") {
        next[student.sourceEnrolmentId] = { outcome: "LEAVE", targetGradeCode: "", targetSectionCode: "", targetRollNumber: "", leaveStatus: bulkLeaveStatus as Exclude<LeaveStatus, "">, reason: bulkReason.trim() };
        continue;
      }
      if (bulkOutcome === "RETAIN") {
        const matching = sourceSections.find((section) => section.campusId === student.campusId && section.gradeCode === student.gradeCode && section.code === student.sourceSectionCode)
          ?? sourceSections.find((section) => section.campusId === student.campusId && section.gradeCode === student.gradeCode);
        next[student.sourceEnrolmentId] = { outcome: "RETAIN", targetGradeCode: student.gradeCode, targetSectionCode: matching?.code || "", targetRollNumber: student.rollNumber || "", leaveStatus: "", reason: bulkReason.trim() };
        continue;
      }
      next[student.sourceEnrolmentId] = { outcome: "PROMOTE", targetGradeCode, targetSectionCode, targetRollNumber: student.rollNumber || "", leaveStatus: "", reason: bulkReason.trim() };
    }
    setDecisions(next); invalidatePreview();
    setNotice(`${filteredStudents.length} filtered student decision${filteredStudents.length === 1 ? "" : "s"} updated. Review before previewing.`);
  }

  function buildRequest(): PreviewRequest | null {
    if (!data || !sourceSession || completed !== sourceStudents.length || !sourceStudents.length) return null;
    return {
      schoolId: data.schoolId, sourceSessionId: sourceSession.id, targetSessionName: targetName.trim(),
      targetStartsOn, targetEndsOn, copyTeachingAllocations: copyAllocations,
      copyPublishedTimetable: copyTimetable, copyFeePlans: copyFees, copyCalendarPolicy: copyPolicy,
      studentDecisions: sourceStudents.map((student) => {
        const decision = decisions[student.sourceEnrolmentId] ?? blankDecision();
        return {
          sourceEnrolmentId: student.sourceEnrolmentId,
          outcome: decision.outcome as Exclude<Outcome, "">,
          targetGradeCode: decision.outcome === "LEAVE" ? null : decision.targetGradeCode,
          targetSectionCode: decision.outcome === "LEAVE" ? null : decision.targetSectionCode,
          targetRollNumber: decision.outcome === "LEAVE" ? null : decision.targetRollNumber.trim() || null,
          leaveStatus: decision.outcome === "LEAVE" ? decision.leaveStatus as Exclude<LeaveStatus, ""> : null,
          reason: decision.reason.trim(),
        };
      }),
    };
  }

  async function previewRollover(event: FormEvent) {
    event.preventDefault(); const request = buildRequest(); setError(""); setNotice("");
    if (!request) { setError("Complete one valid decision for every active student before creating the preview."); return; }
    if (!targetName.trim() || !targetStartsOn || !targetEndsOn) { setError("Complete the target session name and dates."); return; }
    setBusy(true);
    try {
      const response = await fetch(`${apiUrl}/api/v1/academic-rollovers/preview`, {
        method: "POST", credentials: "include",
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken() },
        body: JSON.stringify(request),
      });
      if (!response.ok) throw new Error(await problem(response, "The rollover preview could not be created."));
      const result = await response.json() as Preview;
      idempotencyKey.current = crypto.randomUUID(); setReview({ request, result });
      setNotice("Preview frozen. Review the counts and warnings before execution.");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The rollover preview could not be created.");
    } finally { setBusy(false); }
  }

  async function executeRollover(event: FormEvent) {
    event.preventDefault(); if (!data || !review) return; setError(""); setNotice("");
    if (confirmation !== review.result.targetSessionName) { setError("Type the exact target session name to confirm this irreversible rollover."); return; }
    if (executionReason.trim().length < 3) { setError("Record the authority reason for executing this rollover."); return; }
    if (!executionOpen) { setError("Execution remains locked until the source session end date."); return; }
    if (!window.confirm("Execute the reviewed rollover now? The source session will close and the new session will become active.")) return;
    setBusy(true);
    try {
      if (!idempotencyKey.current) idempotencyKey.current = crypto.randomUUID();
      const response = await fetch(`${apiUrl}/api/v1/academic-rollovers/execute`, {
        method: "POST", credentials: "include",
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken() },
        body: JSON.stringify({ preview: review.request, idempotencyKey: idempotencyKey.current, snapshotHash: review.result.snapshotHash, reason: executionReason.trim() }),
      });
      if (!response.ok) throw new Error(await problem(response, "The rollover could not be executed."));
      const result = await response.json() as Execution;
      setLastExecution(result); setReview(null); setConfirmation(""); setExecutionReason("");
      setNotice(`${result.targetSessionName} is active. The source session and immutable execution record were closed together.`);
      const refreshed = await fetch(`${apiUrl}/api/v1/academic-rollovers`, { credentials: "include" });
      if (refreshed.ok) setData(await refreshed.json() as Workspace);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The rollover could not be executed.");
    } finally { setBusy(false); }
  }

  if (!data) {
    return <section className="rollover-state" aria-live="polite">
      <AcademicIcon /><h1>{error ? "Rollover workspace unavailable" : "Opening the controlled rollover workspace…"}</h1>{error && <p>{error}</p>}
    </section>;
  }
  const activeSessions = data.sessions.filter((session) => session.status === "ACTIVE");

  return <section className="rollover-workspace" aria-busy={busy}>
    <header className="rollover-hero">
      <div><span>Controlled academic-year transition</span><h1>Move the school forward without rewriting its history.</h1><p>Prepare every student decision, copy approved session setup, freeze a reviewable preview, then close and activate both sessions in one protected transaction.</p></div>
      <aside><AcademicIcon /><strong>{data.history.length}</strong><span>Immutable rollover record{data.history.length === 1 ? "" : "s"}</span></aside>
    </header>
    <div className="rollover-trust" aria-label="Rollover safeguards">
      <span><i /> Preview hash prevents stale execution</span><span><i /> Every active student requires a decision</span><span><i /> Old sessions remain historical</span><span><i /> Safe retry and audit evidence</span>
    </div>
    {(error || notice) && <div className={`rollover-banner ${error ? "is-error" : "is-success"}`} role="status" aria-live="polite">{error || notice}</div>}
    {lastExecution && <section className="rollover-complete" aria-label="Completed rollover"><ReportIcon /><div><span>Rollover completed</span><strong>{lastExecution.targetSessionName} is active</strong><small>{new Date(lastExecution.executedAt).toLocaleString("en-IN")} · batch {lastExecution.batchId.slice(0, 8)}</small></div></section>}

    <section className="rollover-setup">
      <header><div><span>Step 1 · Session setup</span><h2>Choose the source and prepare the next session</h2></div><em data-state={executionOpen ? "OPEN" : "LOCKED"}>{executionOpen ? "Execution window open" : "Preview only"}</em></header>
      <div className="rollover-setup__grid">
        <label><span>Active source session</span><select value={sourceSessionId} onChange={(event) => changeSource(event.target.value)}><option value="">Choose an active session</option>{activeSessions.map((session) => <option key={session.id} value={session.id}>{session.name} · ends {shortDate(session.endsOn)}</option>)}</select></label>
        <label><span>Target session name</span><input maxLength={80} value={targetName} onChange={(event) => updateSetup(() => setTargetName(event.target.value))} /></label>
        <label><span>Target starts</span><input type="date" value={targetStartsOn} onChange={(event) => updateSetup(() => setTargetStartsOn(event.target.value))} /></label>
        <label><span>Target ends</span><input type="date" value={targetEndsOn} onChange={(event) => updateSetup(() => setTargetEndsOn(event.target.value))} /></label>
      </div>
      {sourceSession && <div className="rollover-source-summary">
        <div><span>Source period</span><strong>{shortDate(sourceSession.startsOn)} – {shortDate(sourceSession.endsOn)}</strong><small>{titleCase(sourceSession.status)}</small></div>
        <div><span>Active students</span><strong>{sourceStudents.length}</strong><small>All require an explicit outcome</small></div>
        <div><span>Section templates</span><strong>{sourceSections.length}</strong><small>Copied into the target session</small></div>
        <div><span>Execution rule</span><strong>{executionOpen ? "Available now" : `After ${shortDate(sourceSession.endsOn)}`}</strong><small>Preview can be prepared earlier</small></div>
      </div>}
      <fieldset className="rollover-copy-options"><legend>Optional setup to copy as editable target drafts</legend>
        <label><input type="checkbox" checked={copyAllocations} onChange={(event) => updateSetup(() => { setCopyAllocations(event.target.checked); if (!event.target.checked) setCopyTimetable(false); })} /><span>Eligible teaching allocations</span></label>
        <label><input type="checkbox" checked={copyTimetable} disabled={!copyAllocations} onChange={(event) => updateSetup(() => setCopyTimetable(event.target.checked))} /><span>Published timetable as a draft</span></label>
        <label><input type="checkbox" checked={copyFees} onChange={(event) => updateSetup(() => setCopyFees(event.target.checked))} /><span>Active fee plans as drafts</span></label>
        <label><input type="checkbox" checked={copyPolicy} onChange={(event) => updateSetup(() => setCopyPolicy(event.target.checked))} /><span>Latest calendar policy</span></label>
      </fieldset>
    </section>

    {sourceSession && !alreadyRolled && <>
      <section className="rollover-planner">
        <header>
          <div><span>Step 2 · Student decisions</span><h2>Plan in groups. Review one student at a time.</h2><p>Filters affect bulk actions, never hidden students. Preview stays locked until every active enrollment has a complete decision.</p></div>
          <div className="rollover-progress"><strong>{progress}%</strong><span>{completed} of {sourceStudents.length} ready</span><div role="progressbar" aria-label="Student decisions completed" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}><i style={{ width: `${progress}%` }} /></div></div>
        </header>

        <div className="rollover-filters">
          <label><span>Search students</span><input type="search" placeholder="Name, admission or roll number" value={query} onChange={(event) => { setQuery(event.target.value); setPage(1); }} /></label>
          <label><span>Source grade</span><select value={gradeFilter} onChange={(event) => { setGradeFilter(event.target.value); setSectionFilter(""); setPage(1); }}><option value="">All grades</option>{grades.map((grade) => <option key={grade}>{grade}</option>)}</select></label>
          <label><span>Source section</span><select value={sectionFilter} onChange={(event) => { setSectionFilter(event.target.value); setPage(1); }}><option value="">All sections</option>{sourceSectionCodes.map((section) => <option key={section}>{section}</option>)}</select></label>
          <label><span>Decision state</span><select value={decisionFilter} onChange={(event) => { setDecisionFilter(event.target.value); setPage(1); }}><option value="">All decisions</option><option value="PENDING">Needs review</option><option value="PROMOTE">Promote</option><option value="RETAIN">Retain</option><option value="LEAVE">Leave</option></select></label>
        </div>

        <section className="rollover-bulk" aria-label="Bulk student decision">
          <div><span>Bulk action</span><strong>Apply only to {filteredStudents.length} filtered student{filteredStudents.length === 1 ? "" : "s"}</strong></div>
          <label><span>Decision</span><select value={bulkOutcome} onChange={(event) => { setBulkOutcome(event.target.value as Outcome); setBulkTarget(""); setBulkLeaveStatus(""); }}><option value="">Choose</option><option value="PROMOTE">Promote</option><option value="RETAIN">Retain</option><option value="LEAVE">Leave school</option></select></label>
          {bulkOutcome === "PROMOTE" && <label><span>Target class</span><select value={bulkTarget} onChange={(event) => setBulkTarget(event.target.value)}><option value="">Choose grade and section</option>{bulkTargets.map(([key, section]) => <option value={key} key={key}>{section.gradeCode} · {section.name}</option>)}</select></label>}
          {bulkOutcome === "LEAVE" && <label><span>Leaving status</span><select value={bulkLeaveStatus} onChange={(event) => setBulkLeaveStatus(event.target.value as LeaveStatus)}><option value="">Choose status</option><option value="INACTIVE">Transferred / withdrawn</option><option value="ALUMNI">Completed school</option></select></label>}
          <label className="rollover-bulk__reason"><span>Recorded reason</span><input maxLength={240} value={bulkReason} onChange={(event) => setBulkReason(event.target.value)} placeholder="Why this decision group is correct" /></label>
          <button type="button" onClick={applyBulk} disabled={busy}>Apply to filtered students</button>
        </section>

        <div className="rollover-student-list" aria-label="Student rollover decisions">
          <header><span>Student</span><span>Source</span><span>Decision</span><span>Target or leaving state</span><span>Reason</span><span>Status</span></header>
          {visibleStudents.map((student) => {
            const decision = decisions[student.sourceEnrolmentId] || blankDecision();
            const targets = studentTargets(student, decision.outcome);
            const selectedTarget = decision.targetGradeCode && decision.targetSectionCode ? `${decision.targetGradeCode}::${decision.targetSectionCode}` : "";
            return <article key={student.sourceEnrolmentId} className={decisionComplete(decision) ? "is-ready" : "is-pending"}>
              <div className="rollover-student"><span>{student.learnerName.split(/\s+/).slice(0, 2).map((part) => part[0]).join("").toUpperCase()}</span><div><strong>{student.learnerName}</strong><small>{student.admissionNumber || "Admission number pending"} · roll {student.rollNumber || "—"}</small></div></div>
              <div><b>{student.gradeCode} · {student.sourceSectionCode || "Unassigned"}</b><small>Current placement</small></div>
              <label><span>Decision</span><select aria-label={`Decision for ${student.learnerName}`} value={decision.outcome} onChange={(event) => selectOutcome(student, event.target.value as Outcome)}><option value="">Review needed</option><option value="PROMOTE">Promote</option><option value="RETAIN">Retain</option><option value="LEAVE">Leave school</option></select></label>
              <div className="rollover-target">
                {decision.outcome === "LEAVE" ? <label><span>Leaving status</span><select aria-label={`Leaving status for ${student.learnerName}`} value={decision.leaveStatus} onChange={(event) => updateDecision(student.sourceEnrolmentId, { leaveStatus: event.target.value as LeaveStatus })}><option value="">Choose status</option><option value="INACTIVE">Transferred / withdrawn</option><option value="ALUMNI">Completed school</option></select></label> : <>
                  <label><span>Target class</span><select aria-label={`Target class for ${student.learnerName}`} disabled={!decision.outcome} value={selectedTarget} onChange={(event) => { const [targetGradeCode = "", targetSectionCode = ""] = event.target.value.split("::"); updateDecision(student.sourceEnrolmentId, { targetGradeCode, targetSectionCode }); }}><option value="">Choose grade and section</option>{targets.map((section) => <option key={section.id} value={`${section.gradeCode}::${section.code}`}>{section.gradeCode} · {section.name}</option>)}</select></label>
                  <label><span>Target roll</span><input aria-label={`Target roll number for ${student.learnerName}`} maxLength={30} disabled={!decision.outcome} value={decision.targetRollNumber} onChange={(event) => updateDecision(student.sourceEnrolmentId, { targetRollNumber: event.target.value })} placeholder="Optional" /></label>
                </>}
              </div>
              <label><span>Reason</span><input aria-label={`Decision reason for ${student.learnerName}`} maxLength={240} disabled={!decision.outcome} value={decision.reason} onChange={(event) => updateDecision(student.sourceEnrolmentId, { reason: event.target.value })} /></label>
              <em data-state={decisionComplete(decision) ? "READY" : "PENDING"}>{decisionComplete(decision) ? "Ready" : "Review"}</em>
            </article>;
          })}
          {!visibleStudents.length && <div className="rollover-empty"><PeopleIcon /><strong>No students match these filters.</strong><span>Clear or change the filters to continue planning.</span></div>}
        </div>

        {totalPages > 1 && <nav className="rollover-pagination" aria-label="Student decision pages"><button type="button" disabled={currentPage === 1} onClick={() => setPage((value) => Math.max(1, value - 1))}>Previous</button><span>Page {currentPage} of {totalPages} · {filteredStudents.length} students</span><button type="button" disabled={currentPage === totalPages} onClick={() => setPage((value) => Math.min(totalPages, value + 1))}>Next</button></nav>}
      </section>

      <form className="rollover-preview-action" onSubmit={previewRollover}>
        <div><AttendanceIcon /><span><strong>{completed === sourceStudents.length ? "All decisions are ready for validation" : `${sourceStudents.length - completed} decisions still need review`}</strong><small>Preview checks completeness, target sections, capacities, roll-number collisions, open drafts, staff eligibility, and current database versions.</small></span></div>
        <button type="submit" disabled={busy || !sourceStudents.length || completed !== sourceStudents.length}>{busy ? "Checking…" : review ? "Refresh protected preview" : "Create protected preview"}</button>
      </form>
    </>}

    {sourceSession && alreadyRolled && <section className="rollover-empty rollover-empty--large"><ReportIcon /><strong>This source session already has an executed rollover.</strong><span>Its historical batch is preserved below and cannot be executed again.</span></section>}

    {review && <section className="rollover-review">
      <header><div><span>Step 3 · Frozen preview</span><h2>{review.result.sourceSessionName} → {review.result.targetSessionName}</h2><p>{shortDate(review.result.targetStartsOn)} – {shortDate(review.result.targetEndsOn)} · snapshot {review.result.snapshotHash.slice(0, 12)}…</p></div><em>Not executed</em></header>
      <div className="rollover-counts">
        <article><span>Students</span><strong>{review.result.counts.students}</strong><small>{review.result.counts.promoted} promoted · {review.result.counts.retained} retained · {review.result.counts.leaving} leaving</small></article>
        <article><span>Academic setup</span><strong>{review.result.counts.sections + review.result.counts.terms + review.result.counts.resources}</strong><small>{review.result.counts.sections} sections · {review.result.counts.terms} terms · {review.result.counts.resources} resources</small></article>
        <article><span>Teaching & timetable</span><strong>{review.result.counts.allocations + review.result.counts.timetableEntries}</strong><small>{review.result.counts.allocations} allocations · {review.result.counts.timetableEntries} entries</small></article>
        <article><span>Operations drafts</span><strong>{review.result.counts.feePlans + review.result.counts.calendarPolicies}</strong><small>{review.result.counts.feePlans} fee plans · {review.result.counts.calendarPolicies} calendar policies</small></article>
      </div>
      {review.result.warnings.length > 0 && <div className="rollover-warnings"><strong>Review before execution</strong><ul>{review.result.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul></div>}
      <form className="rollover-execute" onSubmit={executeRollover}>
        <div><span>Execution effect</span><strong>Close {review.result.sourceSessionName} and activate {review.result.targetSessionName}</strong><small>{executionOpen ? "The configured source-session end date has passed." : `Locked until ${shortDate(sourceSession?.endsOn || "")}. Refresh this preview when the execution window opens.`}</small></div>
        <label><span>Authority reason</span><textarea required minLength={3} maxLength={500} value={executionReason} onChange={(event) => setExecutionReason(event.target.value)} placeholder="Record the approved operational reason" /></label>
        <label><span>Type “{review.result.targetSessionName}” to confirm</span><input required value={confirmation} onChange={(event) => setConfirmation(event.target.value)} autoComplete="off" /></label>
        <button type="submit" disabled={busy || !executionOpen || confirmation !== review.result.targetSessionName}>{busy ? "Executing safely…" : "Execute reviewed rollover"}</button>
      </form>
    </section>}

    <section className="rollover-history">
      <header><div><span>Immutable history</span><h2>Completed session transitions</h2></div><strong>{data.history.length} recorded</strong></header>
      {data.history.length ? <div>{data.history.map((batch) => <article key={batch.id}><ReportIcon /><div><strong>{batch.sourceSessionName} → {batch.targetSessionName}</strong><span>{batch.promotedCount} promoted · {batch.retainedCount} retained · {batch.leavingCount} leaving</span><small>{new Date(batch.executedAt).toLocaleString("en-IN")} · batch {batch.id.slice(0, 8)}</small></div><em>{titleCase(batch.status)}</em></article>)}</div> : <div className="rollover-empty"><ReportIcon /><strong>No academic rollover has been executed.</strong><span>The first completed transition will appear here as protected evidence.</span></div>}
    </section>
  </section>;
}
