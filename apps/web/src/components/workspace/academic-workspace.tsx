"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Check, Shield, Sparkles } from "@/components/icons";

type Versioned = { id: string; rowVersion: number; status: string };
type Session = {
  rteSeatLimit: number;
  rteMinAgeYears: number;
  rteMaxAgeYears: number;
  id: string;
  name: string;
  startsOn: string;
  endsOn: string;
  status: string;
  rowVersion: number;
};
type Campus = { id: string; name: string; timeZone: string };
type Section = {
  id: string;
  campusId: string;
  academicSessionId: string;
  gradeCode: string;
  code: string;
  name: string;
  status: string;
};
type Teacher = { membershipId: string; displayName: string; email: string };
type Term = Versioned & {
  academicSessionId: string;
  code: string;
  name: string;
  startsOn: string;
  endsOn: string;
};
type Subject = Versioned & {
  code: string;
  name: string;
  shortName: string | null;
  category: string;
  languageCode: string | null;
  boardSubjectCode: string | null;
};
type Resource = Versioned & {
  academicSessionId: string;
  subjectId: string;
  gradeCode: string;
  resourceType: string;
  title: string;
  description: string | null;
  author: string | null;
  publisher: string | null;
  edition: string | null;
  isbn: string | null;
  sourceUrl: string | null;
  displayOrder: number;
};
type CurriculumPublication = Versioned & {
  academicSessionId: string;
  gradeCode: string;
  publicationVersion: number;
  supersedesPublicationId: string | null;
  createdAt: string;
  publishedAt: string | null;
  itemCount: number;
};
type CurriculumItem = {
  id: string;
  publicationId: string;
  sourceResourceId: string;
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
};
type Offering = Versioned & { sectionId: string; subjectId: string; weeklyTargetPeriods: number };
type Allocation = Versioned & {
  offeringId: string;
  teacherMembershipId: string;
  allocationRole: string;
  startsOn: string;
  endsOn: string | null;
};
type Period = Versioned & {
  campusId: string;
  academicSessionId: string;
  dayOfWeek: number;
  sequenceNumber: number;
  code: string;
  name: string;
  startsAt: string;
  endsAt: string;
  kind: string;
};
type Plan = Versioned & {
  campusId: string;
  academicSessionId: string;
  academicTermId: string | null;
  name: string;
  supersedesPlanId: string | null;
  publishedAt: string | null;
};
type Entry = {
  id: string;
  planId: string;
  sectionId: string;
  offeringId: string;
  periodId: string;
  teacherMembershipId: string;
  roomLabel: string | null;
  note: string | null;
  rowVersion: number;
};
type Snapshot = {
  schoolId: string;
  schoolName: string;
  sessions: Session[];
  campuses: Campus[];
  sections: Section[];
  teachers: Teacher[];
  terms: Term[];
  subjects: Subject[];
  resources: Resource[];
  curriculumPublications: CurriculumPublication[];
  curriculumItems: CurriculumItem[];
  offerings: Offering[];
  allocations: Allocation[];
  periods: Period[];
  plans: Plan[];
  entries: Entry[];
};
type Problem = { detail?: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

function value(form: FormData, name: string) {
  const result = form.get(name)?.toString().trim();
  return result || null;
}

function labelCode(input: string) {
  return input
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function time(input: string) {
  return input.slice(0, 5);
}

function cookie(name: string) {
  return document.cookie
    .split("; ")
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

async function message(response: Response) {
  try {
    return ((await response.json()) as Problem).detail || "The change could not be saved.";
  } catch {
    return "The change could not be saved.";
  }
}

export function AcademicWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<"structure" | "resources" | "timetable">("structure");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [editingSession, setEditingSession] = useState<Session | null>(null);
  const [editingTerm, setEditingTerm] = useState<Term | null>(null);
  const [editingSubject, setEditingSubject] = useState<Subject | null>(null);
  const [editingResource, setEditingResource] = useState<Resource | null>(null);
  const [editingPeriod, setEditingPeriod] = useState<Period | null>(null);
  const [editingOffering, setEditingOffering] = useState<Offering | null>(null);
  const [editingAllocation, setEditingAllocation] = useState<Allocation | null>(null);
  const [removingEntryId, setRemovingEntryId] = useState("");
  const [removalReason, setRemovalReason] = useState("");
  const [publishReason, setPublishReason] = useState("");
  const [curriculumActionReason, setCurriculumActionReason] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/academics`, { credentials: "include" });
    if (!response.ok) throw new Error(await message(response));
    return (await response.json()) as Snapshot;
  }, []);

  useEffect(() => {
    let cancelled = false;
    load()
      .then((result) => {
        if (!cancelled) setData(result);
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

  const mutate = useCallback(
    async (path: string, method: "POST" | "PUT", body: object, success: string) => {
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
        if (!response.ok) throw new Error(await message(response));
        const result = (await response.json()) as { warnings?: string[] };
        setData(await load());
        setNotice(result.warnings?.length ? `${success} ${result.warnings.join(" ")}` : success);
        return true;
      } catch (reason) {
        setError(reason instanceof Error ? reason.message : "The change could not be saved.");
        return false;
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const draft = data?.plans.find((plan) => plan.status === "DRAFT");
  const published = data?.plans.find((plan) => plan.status === "PUBLISHED");
  const draftEntries = useMemo(
    () => data?.entries.filter((entry) => entry.planId === draft?.id) ?? [],
    [data, draft],
  );
  const subjectName = (id: string) =>
    data?.subjects.find((item) => item.id === id)?.name ?? "Unknown subject";
  const sessionName = (id: string) =>
    data?.sessions.find((item) => item.id === id)?.name ?? "Unknown session";
  const sectionName = (id: string) =>
    data?.sections.find((item) => item.id === id)?.name ?? "Unknown section";
  const teacherName = (id: string) =>
    data?.teachers.find((item) => item.membershipId === id)?.displayName ?? "Teacher unavailable";
  const offeringName = (id: string) => {
    const item = data?.offerings.find((offering) => offering.id === id);
    return item
      ? `${sectionName(item.sectionId)} · ${subjectName(item.subjectId)}`
      : "Unknown class subject";
  };

  if (loading)
    return (
      <section className="academic-loading" aria-live="polite">
        <i />
        <p>Opening the academic configuration…</p>
      </section>
    );
  if (!data)
    return (
      <section className="academic-error" role="alert">
        <Shield />
        <h1>Academics is unavailable</h1>
        <p>{error}</p>
      </section>
    );

  async function saveSession(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const body = {
      schoolId: data!.schoolId,
      name: value(form, "name"),
      startsOn: value(form, "starts"),
      endsOn: value(form, "ends"),
      status: value(form, "status"),
      expectedVersion: editingSession?.rowVersion ?? null,
      rteSeatLimit: value(form, "rteSeatLimit") === "" ? null : Number(value(form, "rteSeatLimit")),
      rteMinAgeYears: value(form, "rteMinAgeYears") === "" ? null : Number(value(form, "rteMinAgeYears")),
      rteMaxAgeYears: value(form, "rteMaxAgeYears") === "" ? null : Number(value(form, "rteMaxAgeYears")),
      reason: value(form, "reason"),
    };
    const ok = await mutate(
      editingSession
        ? `/api/v1/academics/sessions/${editingSession.id}`
        : "/api/v1/academics/sessions",
      editingSession ? "PUT" : "POST",
      body,
      editingSession ? "Academic session updated." : "Academic session created.",
    );
    if (ok) setEditingSession(null);
  }

  async function saveTerm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const body = {
      schoolId: data!.schoolId,
      academicSessionId: value(form, "session"),
      code: value(form, "code"),
      name: value(form, "name"),
      startsOn: value(form, "starts"),
      endsOn: value(form, "ends"),
      status: value(form, "status"),
      expectedVersion: editingTerm?.rowVersion ?? null,
      reason: value(form, "reason"),
    };
    const ok = await mutate(
      editingTerm ? `/api/v1/academics/terms/${editingTerm.id}` : "/api/v1/academics/terms",
      editingTerm ? "PUT" : "POST",
      body,
      editingTerm ? "Term updated." : "Term created.",
    );
    if (ok) setEditingTerm(null);
  }

  async function saveSubject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const body = {
      schoolId: data!.schoolId,
      code: value(form, "code"),
      name: value(form, "name"),
      shortName: value(form, "shortName"),
      category: value(form, "category"),
      languageCode: value(form, "languageCode"),
      boardSubjectCode: value(form, "boardCode"),
      status: value(form, "status"),
      expectedVersion: editingSubject?.rowVersion ?? null,
      reason: value(form, "reason"),
    };
    const ok = await mutate(
      editingSubject
        ? `/api/v1/academics/subjects/${editingSubject.id}`
        : "/api/v1/academics/subjects",
      editingSubject ? "PUT" : "POST",
      body,
      editingSubject ? "Subject updated." : "Subject created.",
    );
    if (ok) setEditingSubject(null);
  }

  async function saveResource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const body = {
      schoolId: data!.schoolId,
      academicSessionId: value(form, "session"),
      subjectId: value(form, "subject"),
      gradeCode: value(form, "grade"),
      resourceType: value(form, "type"),
      title: value(form, "title"),
      description: value(form, "description"),
      author: value(form, "author"),
      publisher: value(form, "publisher"),
      edition: value(form, "edition"),
      isbn: value(form, "isbn"),
      sourceUrl: value(form, "url"),
      displayOrder: Number(value(form, "order") ?? 0),
      status: value(form, "status"),
      expectedVersion: editingResource?.rowVersion ?? null,
      reason: value(form, "reason"),
    };
    const ok = await mutate(
      editingResource
        ? `/api/v1/academics/resources/${editingResource.id}`
        : "/api/v1/academics/resources",
      editingResource ? "PUT" : "POST",
      body,
      editingResource ? "Learning resource updated." : "Learning resource added.",
    );
    if (ok) setEditingResource(null);
  }

  async function createCurriculumDraft(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    const ok = await mutate(
      "/api/v1/academics/curriculum/publications/drafts",
      "POST",
      {
        schoolId: data!.schoolId,
        academicSessionId: value(form, "session"),
        gradeCode: value(form, "grade"),
        reason: value(form, "reason"),
      },
      "Review draft captured from the active catalogue.",
    );
    if (ok) submittedForm.reset();
  }

  async function changeCurriculum(
    publication: CurriculumPublication,
    action: "publish" | "cancel",
  ) {
    if (!curriculumActionReason.trim()) {
      setError("Enter a reason before publishing or cancelling the curriculum draft.");
      return;
    }
    const ok = await mutate(
      `/api/v1/academics/curriculum/publications/${publication.id}/${action}`,
      "POST",
      {
        schoolId: data!.schoolId,
        expectedVersion: publication.rowVersion,
        reason: curriculumActionReason,
      },
      action === "publish"
        ? "Curriculum published for families and students."
        : "Curriculum draft cancelled with history retained.",
    );
    if (ok) setCurriculumActionReason("");
  }

  async function savePeriod(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const body = {
      schoolId: data!.schoolId,
      campusId: value(form, "campus"),
      academicSessionId: value(form, "session"),
      dayOfWeek: Number(value(form, "day")),
      sequenceNumber: Number(value(form, "sequence")),
      code: value(form, "code"),
      name: value(form, "name"),
      startsAt: value(form, "starts"),
      endsAt: value(form, "ends"),
      kind: value(form, "kind"),
      status: value(form, "status"),
      expectedVersion: editingPeriod?.rowVersion ?? null,
      reason: value(form, "reason"),
    };
    const ok = await mutate(
      editingPeriod ? `/api/v1/academics/periods/${editingPeriod.id}` : "/api/v1/academics/periods",
      editingPeriod ? "PUT" : "POST",
      body,
      editingPeriod ? "Bell period updated." : "Bell period created.",
    );
    if (ok) setEditingPeriod(null);
  }

  async function saveOffering(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const ok = await mutate(
      editingOffering
        ? `/api/v1/academics/offerings/${editingOffering.id}`
        : "/api/v1/academics/offerings",
      editingOffering ? "PUT" : "POST",
      {
        schoolId: data!.schoolId,
        sectionId: value(form, "section"),
        subjectId: value(form, "subject"),
        weeklyTargetPeriods: Number(value(form, "target")),
        status: value(form, "status"),
        expectedVersion: editingOffering?.rowVersion ?? null,
        reason: value(form, "reason"),
      },
      editingOffering ? "Class subject updated." : "Class subject configured.",
    );
    if (ok) setEditingOffering(null);
  }

  async function saveAllocation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const ok = await mutate(
      editingAllocation
        ? `/api/v1/academics/allocations/${editingAllocation.id}`
        : "/api/v1/academics/allocations",
      editingAllocation ? "PUT" : "POST",
      {
        schoolId: data!.schoolId,
        offeringId: value(form, "offering"),
        teacherMembershipId: value(form, "teacher"),
        allocationRole: value(form, "role"),
        startsOn: value(form, "starts"),
        endsOn: value(form, "ends"),
        status: value(form, "status"),
        expectedVersion: editingAllocation?.rowVersion ?? null,
        reason: value(form, "reason"),
      },
      editingAllocation ? "Teacher allocation updated." : "Teacher allocated.",
    );
    if (ok) setEditingAllocation(null);
  }

  async function createDraft(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await mutate(
      "/api/v1/academics/timetables/drafts",
      "POST",
      {
        schoolId: data!.schoolId,
        campusId: value(form, "campus"),
        academicSessionId: value(form, "session"),
        academicTermId: value(form, "term"),
        name: value(form, "name"),
        copyPublished: form.get("copy") === "on",
        reason: value(form, "reason"),
      },
      "Timetable draft created.",
    );
  }

  async function addEntry(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!draft) return;
    const form = new FormData(event.currentTarget);
    await mutate(
      `/api/v1/academics/timetables/${draft.id}/entries`,
      "POST",
      {
        schoolId: data!.schoolId,
        sectionId: value(form, "section"),
        offeringId: value(form, "offering"),
        periodId: value(form, "period"),
        teacherMembershipId: value(form, "teacher"),
        roomLabel: value(form, "room"),
        note: value(form, "note"),
        expectedPlanVersion: draft.rowVersion,
        reason: value(form, "reason"),
      },
      "Timetable entry added.",
    );
  }

  async function removeEntry() {
    if (!draft || !removingEntryId || !removalReason.trim()) return;
    const ok = await mutate(
      `/api/v1/academics/timetables/${draft.id}/entries/${removingEntryId}/remove`,
      "POST",
      { schoolId: data!.schoolId, expectedPlanVersion: draft.rowVersion, reason: removalReason },
      "Draft entry removed.",
    );
    if (ok) {
      setRemovingEntryId("");
      setRemovalReason("");
    }
  }

  async function publishDraft() {
    if (!draft || !publishReason.trim()) return;
    const ok = await mutate(
      `/api/v1/academics/timetables/${draft.id}/publish`,
      "POST",
      { schoolId: data!.schoolId, expectedPlanVersion: draft.rowVersion, reason: publishReason },
      "Timetable published.",
    );
    if (ok) setPublishReason("");
  }

  return (
    <section className="academic-workspace">
      <header className="academic-hero">
        <div>
          <span>Academic operating core</span>
          <h1>
            Shape the school day.
            <br />
            <em>Never hard-code it.</em>
          </h1>
          <p>
            Configure sessions, terms, subjects, syllabus, books, teachers, periods, and a
            publishable timetable for {data.schoolName}.
          </p>
        </div>
        <div className="academic-hero__trust">
          <Shield />
          <strong>Draft safely</strong>
          <span>Validate collisions</span>
          <span>Publish with history</span>
        </div>
      </header>

      <nav className="academic-tabs" aria-label="Academic workspace sections">
        <button
          className={tab === "structure" ? "is-active" : ""}
          aria-pressed={tab === "structure"}
          onClick={() => setTab("structure")}
          type="button"
        >
          Structure{" "}
          <span>
            {data.sessions.length + data.terms.length + data.subjects.length + data.periods.length}
          </span>
        </button>
        <button
          className={tab === "resources" ? "is-active" : ""}
          aria-pressed={tab === "resources"}
          onClick={() => setTab("resources")}
          type="button"
        >
          Books & syllabus <span>{data.resources.length}</span>
        </button>
        <button
          className={tab === "timetable" ? "is-active" : ""}
          aria-pressed={tab === "timetable"}
          onClick={() => setTab("timetable")}
          type="button"
        >
          Teaching & timetable <span>{draftEntries.length}</span>
        </button>
      </nav>

      {(notice || error) && (
        <div
          className={`academic-notice${error ? " academic-notice--error" : ""}`}
          role={error ? "alert" : "status"}
        >
          {error ? <Shield /> : <Check />}
          <span>{error || notice}</span>
        </div>
      )}

      {tab === "structure" && (
        <div className="academic-columns">
          <article className="academic-panel academic-panel--wide">
            <header>
              <div>
                <span>00 · Academic years</span>
                <h2>{editingSession ? "Edit academic session" : "Create an academic session"}</h2>
              </div>
              {editingSession && (
                <button type="button" onClick={() => setEditingSession(null)}>
                  Cancel
                </button>
              )}
            </header>
            <form
              key={editingSession?.id ?? "new-session"}
              onSubmit={saveSession}
              className="academic-form academic-form--grid"
            >
              <label>
                <span>Session name</span>
                <input
                  name="name"
                  required
                  maxLength={80}
                  defaultValue={editingSession?.name ?? ""}
                  placeholder="2026–27"
                />
              </label>
              <label>
                <span>Starts</span>
                <input
                  name="starts"
                  type="date"
                  required
                  defaultValue={editingSession?.startsOn ?? ""}
                />
              </label>
              <label>
                <span>Ends</span>
                <input
                  name="ends"
                  type="date"
                  required
                  defaultValue={editingSession?.endsOn ?? ""}
                />
              </label>
              <label>
                <span>Status</span>
                <select name="status" defaultValue={editingSession?.status ?? "PLANNED"}>
                  <option>PLANNED</option>
                  <option>ACTIVE</option>
                  <option>CLOSED</option>
                  <option>ARCHIVED</option>
                </select>
              </label>
              {editingSession && (
                <label className="academic-form__wide">
                  <span>RTE Class I seats</span>
                  <input name="rteSeatLimit" type="number" min="0" max="1000" defaultValue={editingSession.rteSeatLimit} required />
                </label>
              )}
              {editingSession && <><label><span>RTE minimum age at session start</span><input name="rteMinAgeYears" type="number" min="0" max="18" defaultValue={editingSession.rteMinAgeYears} required /></label><label><span>RTE maximum age at session start</span><input name="rteMaxAgeYears" type="number" min="0" max="18" defaultValue={editingSession.rteMaxAgeYears} required /></label></>}
              {editingSession && (
                <label className="academic-form__wide">
                  <span>Reason for change</span>
                  <input name="reason" required maxLength={240} />
                </label>
              )}
              <button className="button button--dark" disabled={busy}>
                {editingSession ? "Save session changes" : "Create session"}
              </button>
            </form>
            <div className="academic-records">
              {data.sessions.map((item) => (
                <button type="button" key={item.id} onClick={() => setEditingSession(item)}>
                  <span>
                    <strong>{item.name}</strong>
                    <small>
                      {item.startsOn} → {item.endsOn}
                    </small>
                  </span>
                  <em>{item.status}</em>
                </button>
              ))}
            </div>
          </article>
          <article className="academic-panel">
            <header>
              <div>
                <span>01 · Calendar</span>
                <h2>{editingTerm ? "Edit term" : "Add a term"}</h2>
              </div>
              {editingTerm && (
                <button type="button" onClick={() => setEditingTerm(null)}>
                  Cancel
                </button>
              )}
            </header>
            <form key={editingTerm?.id ?? "new-term"} onSubmit={saveTerm} className="academic-form">
              <label>
                <span>Academic session</span>
                <select name="session" required defaultValue={editingTerm?.academicSessionId ?? ""}>
                  <option value="" disabled>
                    Select session
                  </option>
                  {data.sessions.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              </label>
              <div>
                <label>
                  <span>Code</span>
                  <input
                    name="code"
                    required
                    maxLength={30}
                    defaultValue={editingTerm?.code ?? ""}
                    placeholder="TERM_1"
                  />
                </label>
                <label>
                  <span>Name</span>
                  <input
                    name="name"
                    required
                    maxLength={100}
                    defaultValue={editingTerm?.name ?? ""}
                    placeholder="Term 1"
                  />
                </label>
              </div>
              <div>
                <label>
                  <span>Starts</span>
                  <input
                    name="starts"
                    type="date"
                    required
                    defaultValue={editingTerm?.startsOn ?? ""}
                  />
                </label>
                <label>
                  <span>Ends</span>
                  <input
                    name="ends"
                    type="date"
                    required
                    defaultValue={editingTerm?.endsOn ?? ""}
                  />
                </label>
              </div>
              <label>
                <span>Status</span>
                <select name="status" defaultValue={editingTerm?.status ?? "ACTIVE"}>
                  <option>ACTIVE</option>
                  <option>INACTIVE</option>
                </select>
              </label>
              {editingTerm && (
                <label>
                  <span>Reason for change</span>
                  <input
                    name="reason"
                    required
                    maxLength={240}
                    placeholder="Why is this term changing?"
                  />
                </label>
              )}
              <button className="button button--dark" disabled={busy}>
                {editingTerm ? "Save term changes" : "Create term"}
              </button>
            </form>
            <div className="academic-records">
              {data.terms.map((item) => (
                <button type="button" key={item.id} onClick={() => setEditingTerm(item)}>
                  <span>
                    <strong>{item.name}</strong>
                    <small>
                      {item.startsOn} → {item.endsOn}
                    </small>
                  </span>
                  <em>{item.status}</em>
                </button>
              ))}
            </div>
          </article>

          <article className="academic-panel">
            <header>
              <div>
                <span>02 · Subjects</span>
                <h2>{editingSubject ? "Edit subject" : "Add a subject"}</h2>
              </div>
              {editingSubject && (
                <button type="button" onClick={() => setEditingSubject(null)}>
                  Cancel
                </button>
              )}
            </header>
            <form
              key={editingSubject?.id ?? "new-subject"}
              onSubmit={saveSubject}
              className="academic-form"
            >
              <div>
                <label>
                  <span>Code</span>
                  <input
                    name="code"
                    required
                    maxLength={30}
                    defaultValue={editingSubject?.code ?? ""}
                    placeholder="MATH"
                  />
                </label>
                <label>
                  <span>Short name</span>
                  <input
                    name="shortName"
                    maxLength={40}
                    defaultValue={editingSubject?.shortName ?? ""}
                    placeholder="Maths"
                  />
                </label>
              </div>
              <label>
                <span>Display name</span>
                <input
                  name="name"
                  required
                  maxLength={120}
                  defaultValue={editingSubject?.name ?? ""}
                  placeholder="Mathematics"
                />
              </label>
              <div>
                <label>
                  <span>Category</span>
                  <select name="category" defaultValue={editingSubject?.category ?? "CORE"}>
                    {["LANGUAGE", "CORE", "SKILL", "ACTIVITY", "INTERNAL", "OTHER"].map((item) => (
                      <option key={item}>{item}</option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Language</span>
                  <input
                    name="languageCode"
                    maxLength={20}
                    defaultValue={editingSubject?.languageCode ?? ""}
                    placeholder="en-IN"
                  />
                </label>
              </div>
              <div>
                <label>
                  <span>Board code</span>
                  <input
                    name="boardCode"
                    maxLength={30}
                    defaultValue={editingSubject?.boardSubjectCode ?? ""}
                    placeholder="Optional"
                  />
                </label>
                <label>
                  <span>Status</span>
                  <select name="status" defaultValue={editingSubject?.status ?? "ACTIVE"}>
                    <option>ACTIVE</option>
                    <option>INACTIVE</option>
                  </select>
                </label>
              </div>
              {editingSubject && (
                <label>
                  <span>Reason for change</span>
                  <input name="reason" required maxLength={240} />
                </label>
              )}
              <button className="button button--dark" disabled={busy}>
                {editingSubject ? "Save subject changes" : "Create subject"}
              </button>
            </form>
            <div className="academic-records">
              {data.subjects.map((item) => (
                <button type="button" key={item.id} onClick={() => setEditingSubject(item)}>
                  <span>
                    <strong>{item.name}</strong>
                    <small>
                      {item.code} · {labelCode(item.category)}
                    </small>
                  </span>
                  <em>{item.status}</em>
                </button>
              ))}
            </div>
          </article>

          <article className="academic-panel academic-panel--wide">
            <header>
              <div>
                <span>03 · Bell schedule</span>
                <h2>{editingPeriod ? "Edit a period" : "Build the working day"}</h2>
              </div>
              {editingPeriod && (
                <button type="button" onClick={() => setEditingPeriod(null)}>
                  Cancel
                </button>
              )}
            </header>
            <form
              key={editingPeriod?.id ?? "new-period"}
              onSubmit={savePeriod}
              className="academic-form academic-form--grid"
            >
              <label>
                <span>Campus</span>
                <select name="campus" required defaultValue={editingPeriod?.campusId ?? ""}>
                  <option value="" disabled>
                    Select campus
                  </option>
                  {data.campuses.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Session</span>
                <select
                  name="session"
                  required
                  defaultValue={editingPeriod?.academicSessionId ?? ""}
                >
                  <option value="" disabled>
                    Select session
                  </option>
                  {data.sessions.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Day</span>
                <select name="day" defaultValue={editingPeriod?.dayOfWeek ?? 1}>
                  {days.map((day, index) => (
                    <option value={index + 1} key={day}>
                      {day}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Sequence</span>
                <input
                  name="sequence"
                  type="number"
                  min={1}
                  max={40}
                  required
                  defaultValue={editingPeriod?.sequenceNumber ?? 1}
                />
              </label>
              <label>
                <span>Code</span>
                <input
                  name="code"
                  required
                  defaultValue={editingPeriod?.code ?? ""}
                  placeholder="P1"
                />
              </label>
              <label>
                <span>Name</span>
                <input
                  name="name"
                  required
                  defaultValue={editingPeriod?.name ?? ""}
                  placeholder="Period 1"
                />
              </label>
              <label>
                <span>Starts</span>
                <input
                  name="starts"
                  type="time"
                  required
                  defaultValue={editingPeriod ? time(editingPeriod.startsAt) : ""}
                />
              </label>
              <label>
                <span>Ends</span>
                <input
                  name="ends"
                  type="time"
                  required
                  defaultValue={editingPeriod ? time(editingPeriod.endsAt) : ""}
                />
              </label>
              <label>
                <span>Kind</span>
                <select name="kind" defaultValue={editingPeriod?.kind ?? "TEACHING"}>
                  {["TEACHING", "ASSEMBLY", "BREAK", "ACTIVITY", "DISMISSAL"].map((item) => (
                    <option key={item}>{item}</option>
                  ))}
                </select>
              </label>
              <label>
                <span>Status</span>
                <select name="status" defaultValue={editingPeriod?.status ?? "ACTIVE"}>
                  <option>ACTIVE</option>
                  <option>INACTIVE</option>
                </select>
              </label>
              {editingPeriod && (
                <label className="academic-form__wide">
                  <span>Reason for change</span>
                  <input name="reason" required maxLength={240} />
                </label>
              )}
              <button className="button button--dark" disabled={busy}>
                {editingPeriod ? "Save period changes" : "Add period"}
              </button>
            </form>
            <div className="academic-periods">
              {data.periods.map((item) => (
                <button type="button" key={item.id} onClick={() => setEditingPeriod(item)}>
                  <span>{days[item.dayOfWeek - 1]}</span>
                  <strong>{item.name}</strong>
                  <small>
                    {time(item.startsAt)}–{time(item.endsAt)} · {labelCode(item.kind)}
                  </small>
                </button>
              ))}
            </div>
          </article>
        </div>
      )}

      {tab === "resources" && (
        <div className="academic-columns academic-columns--resources">
          <article className="academic-panel">
            <header>
              <div>
                <span>Authority-managed catalogue</span>
                <h2>{editingResource ? "Edit book or syllabus" : "Add book or syllabus"}</h2>
              </div>
              {editingResource && (
                <button type="button" onClick={() => setEditingResource(null)}>
                  Cancel
                </button>
              )}
            </header>
            <form
              key={editingResource?.id ?? "new-resource"}
              onSubmit={saveResource}
              className="academic-form"
            >
              <div>
                <label>
                  <span>Session</span>
                  <select
                    name="session"
                    required
                    defaultValue={editingResource?.academicSessionId ?? ""}
                  >
                    <option value="" disabled>
                      Select
                    </option>
                    {data.sessions.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Grade</span>
                  <input
                    name="grade"
                    required
                    maxLength={40}
                    defaultValue={editingResource?.gradeCode ?? ""}
                    placeholder="VI"
                  />
                </label>
              </div>
              <div>
                <label>
                  <span>Subject</span>
                  <select name="subject" required defaultValue={editingResource?.subjectId ?? ""}>
                    <option value="" disabled>
                      Select
                    </option>
                    {data.subjects.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Type</span>
                  <select name="type" defaultValue={editingResource?.resourceType ?? "SYLLABUS"}>
                    {["SYLLABUS", "TEXTBOOK", "WORKBOOK", "REFERENCE", "DIGITAL"].map((item) => (
                      <option key={item}>{item}</option>
                    ))}
                  </select>
                </label>
              </div>
              <label>
                <span>Title</span>
                <input
                  name="title"
                  required
                  maxLength={200}
                  defaultValue={editingResource?.title ?? ""}
                />
              </label>
              <label>
                <span>Description / syllabus coverage</span>
                <textarea
                  name="description"
                  maxLength={1000}
                  defaultValue={editingResource?.description ?? ""}
                />
              </label>
              <div>
                <label>
                  <span>Author</span>
                  <input name="author" defaultValue={editingResource?.author ?? ""} />
                </label>
                <label>
                  <span>Publisher</span>
                  <input name="publisher" defaultValue={editingResource?.publisher ?? ""} />
                </label>
              </div>
              <div>
                <label>
                  <span>Edition</span>
                  <input name="edition" defaultValue={editingResource?.edition ?? ""} />
                </label>
                <label>
                  <span>ISBN</span>
                  <input name="isbn" defaultValue={editingResource?.isbn ?? ""} />
                </label>
              </div>
              <label>
                <span>Official HTTPS source</span>
                <input
                  name="url"
                  type="url"
                  defaultValue={editingResource?.sourceUrl ?? ""}
                  placeholder="https://…"
                />
              </label>
              <div>
                <label>
                  <span>Display order</span>
                  <input
                    name="order"
                    type="number"
                    min={0}
                    max={1000}
                    defaultValue={editingResource?.displayOrder ?? 0}
                  />
                </label>
                <label>
                  <span>Status</span>
                  <select name="status" defaultValue={editingResource?.status ?? "ACTIVE"}>
                    <option>ACTIVE</option>
                    <option>INACTIVE</option>
                  </select>
                </label>
              </div>
              {editingResource && (
                <label>
                  <span>Reason for change</span>
                  <input name="reason" required maxLength={240} />
                </label>
              )}
              <button className="button button--dark" disabled={busy}>
                {editingResource ? "Save resource changes" : "Add to catalogue"}
              </button>
            </form>
          </article>
          <section className="academic-resource-list">
            <header>
              <span>Published only when the school is ready</span>
              <h2>{data.resources.length} configured resources</h2>
              <p>
                Nothing here is inferred from the photographs. School authorities own every title,
                edition, link, and status.
              </p>
            </header>
            {data.resources.length ? (
              data.resources.map((item) => (
                <button type="button" key={item.id} onClick={() => setEditingResource(item)}>
                  <span>{labelCode(item.resourceType)}</span>
                  <h3>{item.title}</h3>
                  <p>
                    {subjectName(item.subjectId)} · Class {item.gradeCode}
                  </p>
                  <small>
                    {item.publisher || item.description || "Details can be added by the school."}
                  </small>
                  <em>{item.status}</em>
                </button>
              ))
            ) : (
              <div className="academic-empty">
                <Sparkles />
                <h3>No fixed books or syllabus</h3>
                <p>Add only confirmed school choices for a session and grade.</p>
              </div>
            )}
          </section>
          <section className="academic-curriculum-release" aria-labelledby="curriculum-release-title">
            <header>
              <div>
                <span>Controlled release</span>
                <h2 id="curriculum-release-title">Publish by session and class</h2>
                <p>
                  A review draft freezes the active catalogue. Publishing makes that exact snapshot
                  visible to the matching class; later corrections create a new version.
                </p>
              </div>
              <Shield />
            </header>
            <form className="academic-form academic-curriculum-release__form" onSubmit={createCurriculumDraft}>
              <label>
                <span>Session</span>
                <select name="session" required defaultValue="">
                  <option value="" disabled>Select</option>
                  {data.sessions.filter((item) => item.status !== "ARCHIVED").map((item) => (
                    <option value={item.id} key={item.id}>{item.name}</option>
                  ))}
                </select>
              </label>
              <label>
                <span>Class / grade code</span>
                <input name="grade" required maxLength={40} placeholder="VI" />
              </label>
              <label className="academic-form__wide">
                <span>Reason for snapshot</span>
                <input name="reason" required maxLength={240} placeholder="Ready for authority review" />
              </label>
              <button className="button button--dark" disabled={busy}>Create review draft</button>
            </form>

            <div className="academic-curriculum-release__history">
              {data.curriculumPublications.length ? data.curriculumPublications.map((publication) => {
                const items = data.curriculumItems.filter((item) => item.publicationId === publication.id);
                return (
                  <article key={publication.id} className={`is-${publication.status.toLowerCase()}`}>
                    <div>
                      <span>{publication.status}</span>
                      <strong>Class {publication.gradeCode} · version {publication.publicationVersion}</strong>
                      <small>
                        {sessionName(publication.academicSessionId)} · {publication.itemCount} approved items
                        {publication.publishedAt
                          ? ` · ${new Date(publication.publishedAt).toLocaleString("en-IN")}`
                          : ""}
                      </small>
                    </div>
                    <details>
                      <summary>Review frozen snapshot</summary>
                      <ul>
                        {items.map((item) => (
                          <li key={item.id}>
                            <span>{labelCode(item.resourceType)}</span>
                            <strong>{item.title}</strong>
                            <small>{item.subjectName}{item.edition ? ` · ${item.edition}` : ""}</small>
                          </li>
                        ))}
                      </ul>
                    </details>
                    {publication.status === "DRAFT" && (
                      <div className="academic-curriculum-release__actions">
                        <label>
                          <span>Publication or cancellation reason</span>
                          <input
                            value={curriculumActionReason}
                            onChange={(event) => setCurriculumActionReason(event.target.value)}
                            maxLength={240}
                            placeholder="Reviewed against the confirmed school catalogue"
                          />
                        </label>
                        <button
                          type="button"
                          className="button button--dark"
                          disabled={busy}
                          onClick={() => changeCurriculum(publication, "publish")}
                        >
                          Publish to class
                        </button>
                        <button
                          type="button"
                          className="button button--ghost"
                          disabled={busy}
                          onClick={() => changeCurriculum(publication, "cancel")}
                        >
                          Cancel draft
                        </button>
                      </div>
                    )}
                  </article>
                );
              }) : (
                <div className="academic-empty">
                  <Shield />
                  <h3>No curriculum release yet</h3>
                  <p>Catalogue entries remain private until authority reviews and publishes a snapshot.</p>
                </div>
              )}
            </div>
          </section>
        </div>
      )}

      {tab === "timetable" && (
        <div className="academic-timetable">
          <section className="academic-timetable__status">
            <article>
              <span>Current published plan</span>
              <strong>{published?.name ?? "Not published"}</strong>
              <small>
                {published?.publishedAt
                  ? `Published ${new Date(published.publishedAt).toLocaleString("en-IN")}`
                  : "Families see no timetable until authority publishes one."}
              </small>
            </article>
            <article className={draft ? "has-draft" : ""}>
              <span>Editable draft</span>
              <strong>{draft?.name ?? "No open draft"}</strong>
              <small>
                {draft
                  ? `${draftEntries.length} entries · version ${draft.rowVersion}`
                  : "Create a draft to begin safely."}
              </small>
            </article>
          </section>
          {!draft && (
            <article className="academic-panel">
              <header>
                <div>
                  <span>Versioned timetable</span>
                  <h2>Create an editable draft</h2>
                </div>
              </header>
              <form onSubmit={createDraft} className="academic-form academic-form--grid">
                <label>
                  <span>Campus</span>
                  <select name="campus" required defaultValue="">
                    <option value="" disabled>
                      Select
                    </option>
                    {data.campuses.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Session</span>
                  <select name="session" required defaultValue="">
                    <option value="" disabled>
                      Select
                    </option>
                    {data.sessions.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Term (optional)</span>
                  <select name="term" defaultValue="">
                    <option value="">Whole session</option>
                    {data.terms.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Draft name</span>
                  <input name="name" required placeholder="Session timetable · revision 1" />
                </label>
                <label className="academic-check">
                  <input type="checkbox" name="copy" defaultChecked />
                  <span>Copy the current published plan when available</span>
                </label>
                <label className="academic-form__wide">
                  <span>Setup note</span>
                  <input
                    name="reason"
                    maxLength={240}
                    placeholder="Why is this draft being prepared?"
                  />
                </label>
                <button className="button button--dark" disabled={busy}>
                  Create draft
                </button>
              </form>
            </article>
          )}
          {draft && (
            <div className="academic-columns">
              <article className="academic-panel">
                <header>
                  <div>
                    <span>Class curriculum</span>
                    <h2>{editingOffering ? "Edit class subject" : "Subjects & targets"}</h2>
                  </div>
                  {editingOffering && (
                    <button type="button" onClick={() => setEditingOffering(null)}>
                      Cancel
                    </button>
                  )}
                </header>
                <form
                  key={editingOffering?.id ?? "new-offering"}
                  onSubmit={saveOffering}
                  className="academic-form"
                >
                  <label>
                    <span>Section</span>
                    <select name="section" required defaultValue={editingOffering?.sectionId ?? ""}>
                      <option value="" disabled>
                        Select
                      </option>
                      {data.sections
                        .filter(
                          (item) =>
                            item.academicSessionId === draft.academicSessionId &&
                            item.campusId === draft.campusId,
                        )
                        .map((item) => (
                          <option value={item.id} key={item.id}>
                            {item.name}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    <span>Subject</span>
                    <select name="subject" required defaultValue={editingOffering?.subjectId ?? ""}>
                      <option value="" disabled>
                        Select
                      </option>
                      {data.subjects.map((item) => (
                        <option value={item.id} key={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <div>
                    <label>
                      <span>Target periods each week</span>
                      <input
                        name="target"
                        type="number"
                        min={0}
                        max={60}
                        required
                        defaultValue={editingOffering?.weeklyTargetPeriods ?? 5}
                      />
                    </label>
                    <label>
                      <span>Status</span>
                      <select name="status" defaultValue={editingOffering?.status ?? "ACTIVE"}>
                        <option>ACTIVE</option>
                        <option>INACTIVE</option>
                      </select>
                    </label>
                  </div>
                  <label>
                    <span>{editingOffering ? "Reason for change" : "Setup note"}</span>
                    <input name="reason" required={Boolean(editingOffering)} maxLength={240} />
                  </label>
                  <button className="button button--dark" disabled={busy}>
                    {editingOffering ? "Save class subject" : "Add class subject"}
                  </button>
                </form>
                <div className="academic-records">
                  {data.offerings.map((item) => (
                    <button type="button" key={item.id} onClick={() => setEditingOffering(item)}>
                      <span>
                        <strong>{offeringName(item.id)}</strong>
                        <small>{item.weeklyTargetPeriods} periods/week</small>
                      </span>
                      <em>{item.status}</em>
                    </button>
                  ))}
                </div>
              </article>
              <article className="academic-panel">
                <header>
                  <div>
                    <span>Teacher responsibility</span>
                    <h2>{editingAllocation ? "Edit allocation" : "Allocate a teacher"}</h2>
                  </div>
                  {editingAllocation && (
                    <button type="button" onClick={() => setEditingAllocation(null)}>
                      Cancel
                    </button>
                  )}
                </header>
                <form
                  key={editingAllocation?.id ?? "new-allocation"}
                  onSubmit={saveAllocation}
                  className="academic-form"
                >
                  <label>
                    <span>Class subject</span>
                    <select
                      name="offering"
                      required
                      defaultValue={editingAllocation?.offeringId ?? ""}
                    >
                      <option value="" disabled>
                        Select
                      </option>
                      {data.offerings.map((item) => (
                        <option value={item.id} key={item.id}>
                          {offeringName(item.id)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    <span>Teacher</span>
                    <select
                      name="teacher"
                      required
                      defaultValue={editingAllocation?.teacherMembershipId ?? ""}
                    >
                      <option value="" disabled>
                        Select
                      </option>
                      {data.teachers.map((item) => (
                        <option value={item.membershipId} key={item.membershipId}>
                          {item.displayName}
                        </option>
                      ))}
                    </select>
                  </label>
                  <div>
                    <label>
                      <span>Role</span>
                      <select
                        name="role"
                        defaultValue={editingAllocation?.allocationRole ?? "PRIMARY"}
                      >
                        <option>PRIMARY</option>
                        <option>CO_TEACHER</option>
                      </select>
                    </label>
                    <label>
                      <span>Status</span>
                      <select name="status" defaultValue={editingAllocation?.status ?? "ACTIVE"}>
                        <option>ACTIVE</option>
                        <option>INACTIVE</option>
                      </select>
                    </label>
                  </div>
                  <div>
                    <label>
                      <span>Starts</span>
                      <input
                        name="starts"
                        type="date"
                        required
                        defaultValue={editingAllocation?.startsOn ?? ""}
                      />
                    </label>
                    <label>
                      <span>Ends (optional)</span>
                      <input
                        name="ends"
                        type="date"
                        defaultValue={editingAllocation?.endsOn ?? ""}
                      />
                    </label>
                  </div>
                  <label>
                    <span>{editingAllocation ? "Reason for change" : "Setup note"}</span>
                    <input name="reason" required={Boolean(editingAllocation)} maxLength={240} />
                  </label>
                  <button className="button button--dark" disabled={busy}>
                    {editingAllocation ? "Save allocation" : "Allocate teacher"}
                  </button>
                </form>
                <div className="academic-records">
                  {data.allocations.map((item) => (
                    <button type="button" key={item.id} onClick={() => setEditingAllocation(item)}>
                      <span>
                        <strong>{teacherName(item.teacherMembershipId)}</strong>
                        <small>{offeringName(item.offeringId)}</small>
                      </span>
                      <em>{item.status}</em>
                    </button>
                  ))}
                </div>
              </article>
              <article className="academic-panel academic-panel--wide">
                <header>
                  <div>
                    <span>Collision-aware planner</span>
                    <h2>Add a teaching period</h2>
                  </div>
                  <small>{draftEntries.length} draft entries</small>
                </header>
                <form onSubmit={addEntry} className="academic-form academic-form--grid">
                  <label>
                    <span>Section</span>
                    <select name="section" required defaultValue="">
                      <option value="" disabled>
                        Select
                      </option>
                      {data.sections
                        .filter(
                          (item) =>
                            item.academicSessionId === draft.academicSessionId &&
                            item.campusId === draft.campusId,
                        )
                        .map((item) => (
                          <option value={item.id} key={item.id}>
                            {item.name}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    <span>Class subject</span>
                    <select name="offering" required defaultValue="">
                      <option value="" disabled>
                        Select
                      </option>
                      {data.offerings
                        .filter((item) => item.status === "ACTIVE")
                        .map((item) => (
                          <option value={item.id} key={item.id}>
                            {offeringName(item.id)}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    <span>Period</span>
                    <select name="period" required defaultValue="">
                      <option value="" disabled>
                        Select
                      </option>
                      {data.periods
                        .filter(
                          (item) =>
                            item.campusId === draft.campusId &&
                            item.academicSessionId === draft.academicSessionId &&
                            item.kind === "TEACHING" &&
                            item.status === "ACTIVE",
                        )
                        .map((item) => (
                          <option value={item.id} key={item.id}>
                            {days[item.dayOfWeek - 1]} · {item.name} · {time(item.startsAt)}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    <span>Teacher</span>
                    <select name="teacher" required defaultValue="">
                      <option value="" disabled>
                        Select
                      </option>
                      {data.teachers.map((item) => (
                        <option value={item.membershipId} key={item.membershipId}>
                          {item.displayName}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    <span>Room (optional)</span>
                    <input name="room" maxLength={80} />
                  </label>
                  <label>
                    <span>Note (optional)</span>
                    <input name="note" maxLength={240} />
                  </label>
                  <label className="academic-form__wide">
                    <span>Setup note</span>
                    <input name="reason" maxLength={240} />
                  </label>
                  <button className="button button--dark" disabled={busy}>
                    Add to draft
                  </button>
                </form>
                <div className="academic-entry-list">
                  {draftEntries.map((entry) => {
                    const period = data.periods.find((item) => item.id === entry.periodId);
                    const offering = data.offerings.find((item) => item.id === entry.offeringId);
                    return (
                      <article key={entry.id}>
                        <span>
                          {period ? days[period.dayOfWeek - 1] : "Day"}
                          <strong>{period ? time(period.startsAt) : "—"}</strong>
                        </span>
                        <div>
                          <strong>{offering ? subjectName(offering.subjectId) : "Subject"}</strong>
                          <small>
                            {sectionName(entry.sectionId)} ·{" "}
                            {teacherName(entry.teacherMembershipId)}
                          </small>
                        </div>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => {
                            setRemovingEntryId(entry.id);
                            setRemovalReason("");
                          }}
                        >
                          Remove
                        </button>
                      </article>
                    );
                  })}
                </div>
                {removingEntryId && (
                  <div className="academic-removal">
                    <label>
                      <span>Reason for removing this draft entry</span>
                      <input
                        value={removalReason}
                        onChange={(event) => setRemovalReason(event.target.value)}
                        maxLength={240}
                        autoFocus
                      />
                    </label>
                    <button
                      type="button"
                      onClick={() => void removeEntry()}
                      disabled={busy || !removalReason.trim()}
                    >
                      Confirm removal
                    </button>
                    <button type="button" onClick={() => setRemovingEntryId("")} disabled={busy}>
                      Cancel
                    </button>
                  </div>
                )}
                <div className="academic-publish">
                  <div>
                    <Shield />
                    <span>
                      <strong>Publishing freezes this version</strong>
                      <small>
                        Conflicts and inactive allocations are checked again. Later changes start in
                        a new draft.
                      </small>
                    </span>
                  </div>
                  <label>
                    <span>Publication reason</span>
                    <input
                      value={publishReason}
                      onChange={(event) => setPublishReason(event.target.value)}
                      maxLength={240}
                      placeholder="Reviewed and approved by…"
                    />
                  </label>
                  <button
                    className="button button--dark"
                    disabled={busy || !draftEntries.length || !publishReason.trim()}
                    type="button"
                    onClick={() => void publishDraft()}
                  >
                    Validate & publish
                  </button>
                </div>
              </article>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
