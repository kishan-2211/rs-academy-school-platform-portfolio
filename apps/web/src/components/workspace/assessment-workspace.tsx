"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Check, Shield, Sparkles } from "@/components/icons";

type Session = { id: string; name: string; startsOn: string; endsOn: string; status: string };
type Term = {
  id: string;
  sessionId: string;
  code: string;
  name: string;
  startsOn: string;
  endsOn: string;
  status: string;
};
type Section = { id: string; sessionId: string; gradeCode: string; name: string };
type Offering = {
  id: string;
  sectionId: string;
  subjectId: string;
  subjectCode: string;
  subjectName: string;
};
type Scale = { id: string; sessionId: string; name: string; status: string; rowVersion: number };
type Band = {
  id: string;
  gradingScaleId: string;
  gradeCode: string;
  name: string;
  minimumPercentage: number;
  maximumPercentage: number;
  outcome: string;
  displayOrder: number;
  rowVersion: number;
};
type Scheme = {
  id: string;
  sessionId: string;
  gradingScaleId: string | null;
  gradeCode: string;
  name: string;
  aggregationMode: string;
  roundingMode: string;
  decimalPlaces: number;
  passRule: string;
  overallPassPercentage: number | null;
  status: string;
  rowVersion: number;
};
type Component = {
  id: string;
  schemeId: string;
  code: string;
  name: string;
  scoringMode: string;
  weightPercentage: number;
  defaultMaxMarks: number | null;
  passPercentage: number | null;
  displayOrder: number;
  status: string;
  rowVersion: number;
};
type Mark = {
  id: string;
  learnerId: string;
  learnerName: string;
  rollNumber: string | null;
  evidenceState: string;
  marksAwarded: number | null;
  gradeCode: string | null;
  narrative: string | null;
  teacherRemark: string | null;
  rowVersion: number;
};
type Assessment = {
  id: string;
  termId: string;
  sectionId: string;
  offeringId: string;
  schemeId: string;
  componentId: string;
  name: string;
  assessmentDate: string;
  scoringMode: string;
  maximumMarks: number | null;
  subjectName: string;
  componentName: string;
  status: string;
  expectedLearnerCount: number;
  recordedCount: number;
  submittedAt: string | null;
  validatedAt: string | null;
  publishedAt: string | null;
  rowVersion: number;
  marks: Mark[];
};
type Correction = {
  id: string;
  markId: string;
  assessmentId: string;
  learnerName: string;
  assessmentName: string;
  requestedState: string;
  requestedMarks: number | null;
  requestedGrade: string | null;
  requestedNarrative: string | null;
  requestReason: string;
  status: string;
  requestedBy: string;
  createdAt: string;
  rowVersion: number;
};
type Publication = {
  id: string;
  termId: string;
  sectionId: string;
  schemeId: string;
  version: number;
  name: string;
  status: string;
  supersedesPublicationId: string | null;
  reportCardCount: number;
  publishedAt: string;
  supersededAt: string | null;
  revokedAt: string | null;
};
type Snapshot = {
  schoolId: string;
  schoolName: string;
  canConfigure: boolean;
  canPublish: boolean;
  sessions: Session[];
  terms: Term[];
  sections: Section[];
  offerings: Offering[];
  gradingScales: Scale[];
  gradeBands: Band[];
  schemes: Scheme[];
  components: Component[];
  assessments: Assessment[];
  corrections: Correction[];
  publications: Publication[];
};
type DraftMark = { state: string; marks: string; grade: string; narrative: string; remark: string };
type Problem = { detail?: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

function cookie(name: string) {
  return document.cookie
    .split("; ")
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

function label(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

async function message(response: Response, fallback: string) {
  try {
    return ((await response.json()) as Problem).detail || fallback;
  } catch {
    return fallback;
  }
}

function nullableNumber(value: FormDataEntryValue | null) {
  const text = String(value || "").trim();
  return text ? Number(text) : null;
}

export function AssessmentWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<"register" | "setup" | "publish">("register");
  const [selectedId, setSelectedId] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [draftMarks, setDraftMarks] = useState<Record<string, DraftMark>>({});
  const [entryReason, setEntryReason] = useState("");
  const [transitionReason, setTransitionReason] = useState("");
  const [reviewReasons, setReviewReasons] = useState<Record<string, string>>({});
  const [correctionMarkId, setCorrectionMarkId] = useState("");
  const [correctionState, setCorrectionState] = useState("RECORDED");
  const [correctionValue, setCorrectionValue] = useState("");
  const [correctionReason, setCorrectionReason] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/assessment`, { credentials: "include" });
    if (!response.ok) throw new Error(await message(response, "Assessment is unavailable."));
    return (await response.json()) as Snapshot;
  }, []);

  const refresh = useCallback(async () => {
    const result = await load();
    setData(result);
    setSelectedId((current) =>
      result.assessments.some((item) => item.id === current)
        ? current
        : result.assessments[0]?.id || "",
    );
  }, [load]);

  useEffect(() => {
    let cancelled = false;
    load()
      .then((result) => {
        if (cancelled) return;
        setData(result);
        setSelectedId(result.assessments[0]?.id || "");
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

  const selected = useMemo(
    () => data?.assessments.find((item) => item.id === selectedId) ?? null,
    [data, selectedId],
  );

  async function mutate(path: string, body: unknown, method = "POST") {
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
      if (!response.ok)
        throw new Error(await message(response, "The assessment change could not be saved."));
      await refresh();
      setNotice("Saved with an audit trail.");
      return true;
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "The assessment change could not be saved.",
      );
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function createScale(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    if (
      await mutate("/api/v1/assessment/grading-scales", {
        schoolId: data?.schoolId,
        academicSessionId: form.get("sessionId"),
        name: form.get("name"),
        status: "ACTIVE",
      })
    )
      submittedForm.reset();
  }
  async function createBand(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    if (
      await mutate("/api/v1/assessment/grade-bands", {
        schoolId: data?.schoolId,
        gradingScaleId: form.get("scaleId"),
        gradeCode: form.get("code"),
        name: form.get("name"),
        minimumPercentage: Number(form.get("minimum")),
        maximumPercentage: Number(form.get("maximum")),
        gradePoint: nullableNumber(form.get("gradePoint")),
        outcome: form.get("outcome"),
        descriptor: form.get("descriptor"),
        displayOrder: Number(form.get("displayOrder")),
      })
    )
      submittedForm.reset();
  }
  async function createScheme(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    const passRule = String(form.get("passRule"));
    if (
      await mutate("/api/v1/assessment/schemes", {
        schoolId: data?.schoolId,
        academicSessionId: form.get("sessionId"),
        gradingScaleId: form.get("scaleId") || null,
        gradeCode: form.get("gradeCode"),
        name: form.get("name"),
        aggregationMode: form.get("aggregationMode"),
        roundingMode: form.get("roundingMode"),
        decimalPlaces: Number(form.get("decimalPlaces")),
        passRule,
        overallPassPercentage: ["OVERALL", "BOTH"].includes(passRule)
          ? nullableNumber(form.get("overallPass"))
          : null,
      })
    )
      submittedForm.reset();
  }
  async function createComponent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    const mode = String(form.get("scoringMode"));
    if (
      await mutate("/api/v1/assessment/components", {
        schoolId: data?.schoolId,
        schemeId: form.get("schemeId"),
        code: form.get("code"),
        name: form.get("name"),
        scoringMode: mode,
        weightPercentage: mode === "MARKS" ? Number(form.get("weight")) : 0,
        defaultMaxMarks: mode === "MARKS" ? nullableNumber(form.get("defaultMax")) : null,
        passPercentage: mode === "MARKS" ? nullableNumber(form.get("passPercentage")) : null,
        displayOrder: Number(form.get("displayOrder")),
        status: "ACTIVE",
      })
    )
      submittedForm.reset();
  }
  async function createAssessment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    if (
      await mutate("/api/v1/assessment/assessments", {
        schoolId: data?.schoolId,
        academicTermId: form.get("termId"),
        offeringId: form.get("offeringId"),
        componentId: form.get("componentId"),
        name: form.get("name"),
        assessmentDate: form.get("date"),
        maximumMarks: nullableNumber(form.get("maximumMarks")),
      })
    )
      submittedForm.reset();
  }
  async function saveMarks() {
    if (!selected || !entryReason.trim()) {
      setError("Enter a reason before saving marks.");
      return;
    }
    const marks = selected.marks.map((mark) => {
      const draft = draftMarks[mark.id] ?? {
        state: mark.evidenceState,
        marks: mark.marksAwarded?.toString() || "",
        grade: mark.gradeCode || "",
        narrative: mark.narrative || "",
        remark: mark.teacherRemark || "",
      };
      return {
        learnerId: mark.learnerId,
        evidenceState: draft.state,
        marksAwarded:
          selected.scoringMode === "MARKS" && draft.state === "RECORDED"
            ? nullableNumber(draft.marks)
            : null,
        gradeCode:
          selected.scoringMode === "GRADE" && draft.state === "RECORDED" ? draft.grade : null,
        narrative:
          selected.scoringMode === "NARRATIVE" && draft.state === "RECORDED"
            ? draft.narrative
            : null,
        teacherRemark: draft.remark || null,
        expectedVersion: mark.rowVersion,
      };
    });
    if (
      await mutate(
        `/api/v1/assessment/assessments/${selected.id}/marks`,
        {
          schoolId: data?.schoolId,
          expectedAssessmentVersion: selected.rowVersion,
          marks,
          reason: entryReason,
        },
        "PUT",
      )
    )
      setEntryReason("");
  }
  async function transition(action: "open" | "submit" | "validate" | "publish") {
    if (!selected || !transitionReason.trim()) {
      setError("Enter a reason before changing the assessment status.");
      return;
    }
    if (
      await mutate(`/api/v1/assessment/assessments/${selected.id}/${action}`, {
        schoolId: data?.schoolId,
        expectedVersion: selected.rowVersion,
        reason: transitionReason,
      })
    )
      setTransitionReason("");
  }
  async function requestCorrection() {
    const mark = selected?.marks.find((item) => item.id === correctionMarkId);
    if (!selected || !mark || !correctionReason.trim()) {
      setError("Choose a learner and enter a correction reason.");
      return;
    }
    const recorded = correctionState === "RECORDED";
    if (
      await mutate("/api/v1/assessment/corrections", {
        schoolId: data?.schoolId,
        markId: correctionMarkId,
        evidenceState: correctionState,
        marksAwarded:
          recorded && selected.scoringMode === "MARKS" ? nullableNumber(correctionValue) : null,
        gradeCode: recorded && selected.scoringMode === "GRADE" ? correctionValue : null,
        narrative: recorded && selected.scoringMode === "NARRATIVE" ? correctionValue : null,
        teacherRemark: mark.teacherRemark,
        reason: correctionReason,
      })
    ) {
      setCorrectionMarkId("");
      setCorrectionValue("");
      setCorrectionReason("");
    }
  }
  async function publishReports(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    if (
      await mutate("/api/v1/assessment/publications", {
        schoolId: data?.schoolId,
        academicTermId: form.get("termId"),
        sectionId: form.get("sectionId"),
        schemeId: form.get("schemeId"),
        name: form.get("name"),
        reason: form.get("reason"),
      })
    )
      submittedForm.reset();
  }

  if (loading)
    return (
      <section className="assessment-loading">
        <i />
        <span>Opening assessment workflows…</span>
      </section>
    );
  if (!data)
    return (
      <section className="assessment-state assessment-state--error">
        <Shield />
        <h1>Assessment is unavailable</h1>
        <p>{error}</p>
      </section>
    );

  return (
    <section className="assessment-workspace">
      <header className="assessment-hero">
        <div>
          <span>Evidence to trusted results</span>
          <h1>Assessment that keeps its history.</h1>
          <p>
            Configure any school scheme, collect assigned marks, validate completeness, and issue
            report cards without silently rewriting a published result.
          </p>
        </div>
        <aside>
          <Sparkles />
          <strong>{data.assessments.filter((item) => item.status === "SUBMITTED").length}</strong>
          <span>awaiting validation</span>
        </aside>
      </header>

      <nav className="assessment-tabs" aria-label="Assessment sections">
        {(["register", "setup", "publish"] as const).map((item) => (
          <button
            key={item}
            type="button"
            className={tab === item ? "is-active" : ""}
            aria-pressed={tab === item}
            onClick={() => setTab(item)}
          >
            {item === "register"
              ? "Marks register"
              : item === "setup"
                ? "Rules & schemes"
                : "Publication"}
          </button>
        ))}
      </nav>
      {(error || notice) && (
        <div
          className={error ? "assessment-notice is-error" : "assessment-notice"}
          role={error ? "alert" : "status"}
        >
          {error || notice}
        </div>
      )}

      {tab === "setup" && (
        <div className="assessment-setup">
          {!data.canConfigure ? (
            <div className="assessment-state">
              <Shield />
              <h2>Authority configuration only</h2>
              <p>
                You can use published schemes for assigned classes, but cannot change school rules.
              </p>
            </div>
          ) : (
            <>
              <article>
                <header>
                  <span>1</span>
                  <div>
                    <h2>Grading scale</h2>
                    <p>Define school-owned bands; no grade table is fixed in code.</p>
                  </div>
                </header>
                <form onSubmit={createScale}>
                  <label>
                    Session
                    <select name="sessionId" required>
                      {data.sessions.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Scale name
                    <input
                      name="name"
                      required
                      maxLength={120}
                      placeholder="Primary achievement scale"
                    />
                  </label>
                  <button disabled={busy}>Create scale</button>
                </form>
                <div className="assessment-chips">
                  {data.gradingScales.map((item) => (
                    <span key={item.id}>
                      {item.name} · {item.status}
                    </span>
                  ))}
                </div>
              </article>
              <article>
                <header>
                  <span>2</span>
                  <div>
                    <h2>Grade bands</h2>
                    <p>Ranges must cover 0–100 without overlaps before activation.</p>
                  </div>
                </header>
                <form onSubmit={createBand}>
                  <label>
                    Scale
                    <select name="scaleId" required>
                      {data.gradingScales
                        .filter((item) => item.status === "ACTIVE")
                        .map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.name}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    Code
                    <input name="code" required placeholder="A1" />
                  </label>
                  <label>
                    Name
                    <input name="name" required placeholder="Outstanding" />
                  </label>
                  <label>
                    Minimum
                    <input name="minimum" type="number" min="0" max="100" step="0.01" required />
                  </label>
                  <label>
                    Maximum
                    <input name="maximum" type="number" min="0" max="100" step="0.01" required />
                  </label>
                  <label>
                    Grade point
                    <input name="gradePoint" type="number" min="0" step="0.01" />
                  </label>
                  <label>
                    Outcome
                    <select name="outcome">
                      <option>PASS</option>
                      <option>FAIL</option>
                      <option>NOT_APPLICABLE</option>
                    </select>
                  </label>
                  <label>
                    Order
                    <input name="displayOrder" type="number" min="0" defaultValue="0" />
                  </label>
                  <label className="wide">
                    Descriptor
                    <input name="descriptor" maxLength={240} />
                  </label>
                  <button disabled={busy}>Add band</button>
                </form>
              </article>
              <article>
                <header>
                  <span>3</span>
                  <div>
                    <h2>Assessment scheme</h2>
                    <p>Choose grade, aggregation, rounding, and pass behavior per session.</p>
                  </div>
                </header>
                <form onSubmit={createScheme}>
                  <label>
                    Session
                    <select name="sessionId" required>
                      {data.sessions.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Scale
                    <select name="scaleId" required>
                      <option value="">Choose scale</option>
                      {data.gradingScales.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Grade code
                    <input name="gradeCode" required placeholder="VI" />
                  </label>
                  <label>
                    Scheme name
                    <input name="name" required placeholder="Term assessment" />
                  </label>
                  <label>
                    Aggregation
                    <select name="aggregationMode">
                      <option>WEIGHTED</option>
                      <option>DESCRIPTIVE</option>
                    </select>
                  </label>
                  <label>
                    Rounding
                    <select name="roundingMode">
                      <option>HALF_UP</option>
                      <option>HALF_EVEN</option>
                      <option>DOWN</option>
                    </select>
                  </label>
                  <label>
                    Decimals
                    <input name="decimalPlaces" type="number" min="0" max="2" defaultValue="2" />
                  </label>
                  <label>
                    Pass rule
                    <select name="passRule">
                      <option>NO_PASS_RULE</option>
                      <option>OVERALL</option>
                      <option>EACH_COMPONENT</option>
                      <option>BOTH</option>
                    </select>
                  </label>
                  <label>
                    Overall pass %
                    <input name="overallPass" type="number" min="0" max="100" step="0.01" />
                  </label>
                  <button disabled={busy}>Create draft scheme</button>
                </form>
                <div className="assessment-scheme-list">
                  {data.schemes.map((scheme) => (
                    <div key={scheme.id}>
                      <span>
                        <strong>{scheme.name}</strong>
                        <small>
                          {scheme.gradeCode} · {label(scheme.aggregationMode)} · {scheme.status}
                        </small>
                      </span>
                      {scheme.status === "DRAFT" && (
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() =>
                            void mutate(`/api/v1/assessment/schemes/${scheme.id}/activate`, {
                              schoolId: data.schoolId,
                              expectedVersion: scheme.rowVersion,
                              reason: "Authority reviewed scheme configuration",
                            })
                          }
                        >
                          Activate
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </article>
              <article>
                <header>
                  <span>4</span>
                  <div>
                    <h2>Components</h2>
                    <p>
                      Marks, grade-only, and narrative evidence can coexist across school stages.
                    </p>
                  </div>
                </header>
                <form onSubmit={createComponent}>
                  <label>
                    Draft scheme
                    <select name="schemeId" required>
                      {data.schemes
                        .filter((item) => item.status === "DRAFT")
                        .map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.name} · {item.gradeCode}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    Code
                    <input name="code" required placeholder="PT1" />
                  </label>
                  <label>
                    Name
                    <input name="name" required placeholder="Periodic test" />
                  </label>
                  <label>
                    Scoring
                    <select name="scoringMode">
                      <option>MARKS</option>
                      <option>GRADE</option>
                      <option>NARRATIVE</option>
                    </select>
                  </label>
                  <label>
                    Weight %
                    <input
                      name="weight"
                      type="number"
                      min="0"
                      max="100"
                      step="0.01"
                      defaultValue="0"
                    />
                  </label>
                  <label>
                    Default max
                    <input name="defaultMax" type="number" min="0.01" step="0.01" />
                  </label>
                  <label>
                    Pass %
                    <input name="passPercentage" type="number" min="0" max="100" step="0.01" />
                  </label>
                  <label>
                    Order
                    <input name="displayOrder" type="number" min="0" defaultValue="0" />
                  </label>
                  <button disabled={busy}>Add component</button>
                </form>
              </article>
            </>
          )}
        </div>
      )}

      {tab === "register" && (
        <div className="assessment-register-layout">
          <aside>
            <form onSubmit={createAssessment}>
              <h2>New assessment</h2>
              <label>
                Term
                <select name="termId" required>
                  {data.terms
                    .filter((item) => item.status === "ACTIVE")
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                Class subject
                <select name="offeringId" required>
                  {data.offerings.map((item) => (
                    <option key={item.id} value={item.id}>
                      {data.sections.find((section) => section.id === item.sectionId)?.name} ·{" "}
                      {item.subjectName}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Scheme component
                <select name="componentId" required>
                  {data.components
                    .filter(
                      (item) =>
                        item.status === "ACTIVE" &&
                        data.schemes.find((scheme) => scheme.id === item.schemeId)?.status ===
                          "ACTIVE",
                    )
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {data.schemes.find((scheme) => scheme.id === item.schemeId)?.gradeCode} ·{" "}
                        {item.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                Name
                <input name="name" required placeholder="Periodic Test 1" />
              </label>
              <label>
                Date
                <input name="date" type="date" required />
              </label>
              <label>
                Maximum marks
                <input name="maximumMarks" type="number" min="0.01" step="0.01" />
              </label>
              <button disabled={busy}>Create draft</button>
            </form>
            <div className="assessment-list">
              {data.assessments.map((item) => (
                <button
                  type="button"
                  key={item.id}
                  className={selectedId === item.id ? "is-active" : ""}
                  onClick={() => setSelectedId(item.id)}
                >
                  <span>
                    <strong>{item.subjectName}</strong>
                    <small>
                      {item.name} · {item.componentName}
                    </small>
                  </span>
                  <em>{item.status}</em>
                </button>
              ))}
            </div>
          </aside>
          <section className="assessment-register" aria-label="Assessment marks register">
            {!selected ? (
              <div className="assessment-state">
                <Check />
                <h2>No assessment yet</h2>
                <p>Create a draft from an active scheme and assigned class subject.</p>
              </div>
            ) : (
              <>
                <header className="assessment-register-header">
                  <div>
                    <span>
                      {selected.subjectName} · {selected.componentName}
                    </span>
                    <h2>{selected.name}</h2>
                    <p>
                      {selected.assessmentDate} · {selected.recordedCount}/
                      {selected.expectedLearnerCount} recorded · {label(selected.status)}
                    </p>
                  </div>
                  <div>
                    {selected.status === "DRAFT" && (
                      <button disabled={busy} onClick={() => void transition("open")}>
                        Open roster
                      </button>
                    )}
                    {selected.status === "OPEN" && (
                      <button disabled={busy} onClick={() => void transition("submit")}>
                        Submit
                      </button>
                    )}
                    {selected.status === "SUBMITTED" && data.canPublish && (
                      <button disabled={busy} onClick={() => void transition("validate")}>
                        Validate
                      </button>
                    )}
                    {selected.status === "VALIDATED" && data.canPublish && (
                      <button disabled={busy} onClick={() => void transition("publish")}>
                        Publish assessment
                      </button>
                    )}
                  </div>
                </header>
                <label className="assessment-reason">
                  Status-change reason
                  <input
                    value={transitionReason}
                    onChange={(event) => setTransitionReason(event.target.value)}
                    maxLength={240}
                    placeholder="Required before opening, submitting, validating, or publishing"
                  />
                </label>
                {selected.marks.length > 0 && (
                  <div className="assessment-marks">
                    <div className="assessment-marks__head">
                      <span>Learner</span>
                      <span>Evidence</span>
                      <span>Recorded value</span>
                      <span>Remark</span>
                    </div>
                    {selected.marks.map((mark) => {
                      const draft = draftMarks[mark.id] ?? {
                        state: mark.evidenceState,
                        marks: mark.marksAwarded?.toString() || "",
                        grade: mark.gradeCode || "",
                        narrative: mark.narrative || "",
                        remark: mark.teacherRemark || "",
                      };
                      return (
                        <div key={mark.id}>
                          <span>
                            <strong>{mark.learnerName}</strong>
                            <small>{mark.rollNumber || "No roll number"}</small>
                          </span>
                          <span className="assessment-mark-field">
                            <span className="assessment-mark-label">Evidence</span>
                            <select
                              aria-label={`Evidence for ${mark.learnerName}`}
                              disabled={selected.status !== "OPEN"}
                              value={draft.state}
                              onChange={(event) =>
                                setDraftMarks((current) => ({
                                  ...current,
                                  [mark.id]: { ...draft, state: event.target.value },
                                }))
                              }
                            >
                              <option>MISSING</option>
                              <option>RECORDED</option>
                              <option>ABSENT</option>
                              <option>EXEMPT</option>
                            </select>
                          </span>
                          <span className="assessment-mark-field">
                            <span className="assessment-mark-label">
                              {selected.scoringMode === "MARKS"
                                ? "Marks"
                                : selected.scoringMode === "GRADE"
                                  ? "Grade"
                                  : "Observation"}
                            </span>
                            {selected.scoringMode === "MARKS" ? (
                              <input
                                aria-label={`Marks for ${mark.learnerName}`}
                                disabled={selected.status !== "OPEN" || draft.state !== "RECORDED"}
                                type="number"
                                min="0"
                                max={selected.maximumMarks || undefined}
                                step="0.01"
                                value={draft.marks}
                                onChange={(event) =>
                                  setDraftMarks((current) => ({
                                    ...current,
                                    [mark.id]: { ...draft, marks: event.target.value },
                                  }))
                                }
                              />
                            ) : selected.scoringMode === "GRADE" ? (
                              <input
                                aria-label={`Grade for ${mark.learnerName}`}
                                disabled={selected.status !== "OPEN" || draft.state !== "RECORDED"}
                                value={draft.grade}
                                onChange={(event) =>
                                  setDraftMarks((current) => ({
                                    ...current,
                                    [mark.id]: { ...draft, grade: event.target.value },
                                  }))
                                }
                                placeholder="Grade code"
                              />
                            ) : (
                              <textarea
                                aria-label={`Observation for ${mark.learnerName}`}
                                disabled={selected.status !== "OPEN" || draft.state !== "RECORDED"}
                                value={draft.narrative}
                                onChange={(event) =>
                                  setDraftMarks((current) => ({
                                    ...current,
                                    [mark.id]: { ...draft, narrative: event.target.value },
                                  }))
                                }
                                placeholder="Observation"
                              />
                            )}
                          </span>
                          <span className="assessment-mark-field">
                            <span className="assessment-mark-label">Remark</span>
                            <input
                              aria-label={`Remark for ${mark.learnerName}`}
                              disabled={selected.status !== "OPEN"}
                              value={draft.remark}
                              onChange={(event) =>
                                setDraftMarks((current) => ({
                                  ...current,
                                  [mark.id]: { ...draft, remark: event.target.value },
                                }))
                              }
                              placeholder="Optional remark"
                            />
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
                {selected.status === "OPEN" && (
                  <footer className="assessment-save">
                    <label>
                      Entry reason
                      <input
                        value={entryReason}
                        onChange={(event) => setEntryReason(event.target.value)}
                        maxLength={240}
                        placeholder="Why this batch is being saved"
                      />
                    </label>
                    <button type="button" disabled={busy} onClick={() => void saveMarks()}>
                      Save marks
                    </button>
                  </footer>
                )}
                {["SUBMITTED", "VALIDATED", "PUBLISHED"].includes(selected.status) && (
                  <section className="assessment-correction-form">
                    <header>
                      <h3>Request a controlled correction</h3>
                      <p>
                        Submitted evidence cannot be edited directly. An assessment authority must
                        review this request. The existing teacher remark is retained.
                      </p>
                    </header>
                    <div>
                      <select
                        aria-label="Correction learner"
                        value={correctionMarkId}
                        onChange={(event) => setCorrectionMarkId(event.target.value)}
                      >
                        <option value="">Choose learner</option>
                        {selected.marks.map((mark) => (
                          <option key={mark.id} value={mark.id}>
                            {mark.learnerName}
                          </option>
                        ))}
                      </select>
                      <select
                        aria-label="Correction evidence"
                        value={correctionState}
                        onChange={(event) => setCorrectionState(event.target.value)}
                      >
                        <option>RECORDED</option>
                        <option>ABSENT</option>
                        <option>EXEMPT</option>
                      </select>
                      <input
                        aria-label="Corrected value"
                        value={correctionValue}
                        disabled={correctionState !== "RECORDED"}
                        onChange={(event) => setCorrectionValue(event.target.value)}
                        placeholder={
                          selected.scoringMode === "MARKS"
                            ? "Correct marks"
                            : selected.scoringMode === "GRADE"
                              ? "Correct grade"
                              : "Correct narrative"
                        }
                      />
                      <input
                        aria-label="Correction reason"
                        value={correctionReason}
                        onChange={(event) => setCorrectionReason(event.target.value)}
                        maxLength={240}
                        placeholder="Reason for correction"
                      />
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void requestCorrection()}
                      >
                        Send for review
                      </button>
                    </div>
                  </section>
                )}
              </>
            )}
          </section>
        </div>
      )}

      {tab === "publish" && (
        <div className="assessment-publication">
          <article>
            <header>
              <h2>Issue report cards</h2>
              <p>
                Requires every active subject/component assessment to be published. A correction
                produces a superseding version.
              </p>
            </header>
            {data.canPublish ? (
              <form onSubmit={publishReports}>
                <label>
                  Term
                  <select name="termId" required>
                    {data.terms
                      .filter((item) => item.status === "ACTIVE")
                      .map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  Section
                  <select name="sectionId" required>
                    {data.sections.map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.gradeCode} · {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Scheme
                  <select name="schemeId" required>
                    {data.schemes
                      .filter((item) => item.status === "ACTIVE")
                      .map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.gradeCode} · {item.name}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  Publication name
                  <input name="name" required placeholder="Term 1 Report Card" />
                </label>
                <label className="wide">
                  Reason
                  <input
                    name="reason"
                    required
                    maxLength={240}
                    placeholder="Approval or result committee reference"
                  />
                </label>
                <button disabled={busy}>Publish immutable version</button>
              </form>
            ) : (
              <div className="assessment-state">
                <Shield />
                <p>Result publication requires assessment publication authority.</p>
              </div>
            )}
          </article>
          <article>
            <header>
              <h2>Correction review</h2>
              <p>
                Approved corrections change source evidence; already issued report cards remain
                unchanged until a new version is published.
              </p>
            </header>
            <div className="assessment-review-list">
              {data.corrections
                .filter((item) => item.status === "PENDING")
                .map((item) => (
                  <div key={item.id}>
                    <span>
                      <strong>
                        {item.learnerName} · {item.assessmentName}
                      </strong>
                      <small>
                        {item.requestedState}
                        {item.requestedMarks !== null ? ` · ${item.requestedMarks}` : ""} ·{" "}
                        {item.requestReason}
                      </small>
                    </span>
                    {data.canPublish && (
                      <aside>
                        <input
                          aria-label={`Decision reason for ${item.learnerName} · ${item.assessmentName}`}
                          value={reviewReasons[item.id] || ""}
                          onChange={(event) =>
                            setReviewReasons((current) => ({
                              ...current,
                              [item.id]: event.target.value,
                            }))
                          }
                          placeholder="Decision reason"
                        />
                        <button
                          disabled={busy || !(reviewReasons[item.id] || "").trim()}
                          onClick={() =>
                            void mutate(`/api/v1/assessment/corrections/${item.id}/decision`, {
                              schoolId: data.schoolId,
                              decision: "APPROVED",
                              reason: reviewReasons[item.id],
                              expectedVersion: item.rowVersion,
                            })
                          }
                        >
                          Approve
                        </button>
                        <button
                          disabled={busy || !(reviewReasons[item.id] || "").trim()}
                          onClick={() =>
                            void mutate(`/api/v1/assessment/corrections/${item.id}/decision`, {
                              schoolId: data.schoolId,
                              decision: "REJECTED",
                              reason: reviewReasons[item.id],
                              expectedVersion: item.rowVersion,
                            })
                          }
                        >
                          Reject
                        </button>
                      </aside>
                    )}
                  </div>
                ))}
              {!data.corrections.some((item) => item.status === "PENDING") && (
                <p>No corrections await review.</p>
              )}
            </div>
          </article>
          <article className="wide">
            <header>
              <h2>Publication history</h2>
              <p>Current, superseded, and revoked versions remain traceable.</p>
            </header>
            <div className="assessment-publication-list">
              {data.publications.map((item) => (
                <div key={item.id}>
                  <span>
                    <strong>{item.name}</strong>
                    <small>
                      Version {item.version} · {item.reportCardCount} report cards ·{" "}
                      {new Date(item.publishedAt).toLocaleDateString("en-IN")}
                    </small>
                  </span>
                  <em>{item.status}</em>
                  {item.status === "PUBLISHED" && data.canPublish && (
                    <button
                      disabled={busy}
                      onClick={() => {
                        const reason = window.prompt(
                          "Reason for revoking this current publication",
                        );
                        if (reason)
                          void mutate(`/api/v1/assessment/publications/${item.id}/revoke`, {
                            schoolId: data.schoolId,
                            reason,
                          });
                      }}
                    >
                      Revoke
                    </button>
                  )}
                </div>
              ))}
            </div>
          </article>
        </div>
      )}
    </section>
  );
}
