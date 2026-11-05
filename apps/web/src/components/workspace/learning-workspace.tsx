"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  AcademicIcon,
  AttendanceIcon,
  PlusIcon,
  ReportIcon,
} from "@/components/workspace/workspace-icons";

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
type Category = {
  id: string;
  sessionId: string;
  code: string;
  name: string;
  accentColor?: string;
  displayOrder: number;
  status: string;
  rowVersion: number;
};
type PrivateFile = {
  fileId: string;
  filename: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  purpose: string;
  availableAt: string;
  replacesFileId?: string;
  rowVersion: number;
};
type Submission = {
  id: string;
  learnerId: string;
  learnerName: string;
  rollNumber?: string;
  state: string;
  responseText?: string;
  responseLink?: string;
  submittedAt?: string;
  teacherFeedback?: string;
  pointsAwarded?: number;
  reviewedAt?: string;
  rowVersion: number;
  attachments: PrivateFile[];
};
type Assignment = {
  id: string;
  termId: string;
  sectionId: string;
  offeringId: string;
  categoryId?: string;
  title: string;
  instructions: string;
  subjectName: string;
  categoryName?: string;
  resourceLabel?: string;
  resourceUrl?: string;
  availableFrom: string;
  dueAt: string;
  submissionMode: string;
  maximumPoints?: number;
  allowAttachments: boolean;
  maxAttachmentCount: number;
  attachmentRequired: boolean;
  allowLate: boolean;
  lateUntil?: string;
  status: string;
  publicationRevision: number;
  expectedLearnerCount: number;
  submissionCounts: Record<string, number>;
  publishedAt?: string;
  closedAt?: string;
  rowVersion: number;
  attachments: PrivateFile[];
  submissions: Submission[];
};
type Snapshot = {
  schoolId: string;
  schoolName: string;
  canModerate: boolean;
  sessions: Session[];
  terms: Term[];
  sections: Section[];
  offerings: Offering[];
  categories: Category[];
  assignments: Assignment[];
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const display = (value: string) =>
  value
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
const localInput = (value?: string) => {
  if (!value) return "";
  const date = new Date(value);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
};
const iso = (value: FormDataEntryValue | null) => new Date(String(value)).toISOString();

async function problem(response: Response, fallback: string) {
  try {
    const body = (await response.json()) as { detail?: string; message?: string };
    return body.detail || body.message || fallback;
  } catch {
    return fallback;
  }
}

function cookie(name: string) {
  return document.cookie
    .split("; ")
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1);
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

export function LearningWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [selectedId, setSelectedId] = useState("");
  const [editingId, setEditingId] = useState("");
  const [tab, setTab] = useState<"board" | "configure">("board");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [transitionReason, setTransitionReason] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/learning`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "Assignments could not be opened."));
    const result = (await response.json()) as Snapshot;
    setData(result);
    setSelectedId((current) =>
      current && result.assignments.some((item) => item.id === current)
        ? current
        : result.assignments[0]?.id || "",
    );
  }, []);

  useEffect(() => {
    Promise.resolve()
      .then(load)
      .catch((reason: unknown) =>
        setError(reason instanceof Error ? reason.message : "Assignments could not be opened."),
      );
  }, [load]);

  const selected = useMemo(
    () => data?.assignments.find((item) => item.id === selectedId) ?? null,
    [data, selectedId],
  );
  const editing = useMemo(
    () => data?.assignments.find((item) => item.id === editingId) ?? null,
    [data, editingId],
  );

  async function mutate(path: string, init: RequestInit, success: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const csrf = await csrfToken();
      const response = await fetch(`${apiUrl}${path}`, {
        ...init,
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-TOKEN": csrf,
          ...(init.headers || {}),
        },
      });
      if (!response.ok)
        throw new Error(await problem(response, "The learning change could not be saved."));
      await load();
      setNotice(success);
      return true;
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "The learning change could not be saved.",
      );
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function saveCategory(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data) return;
    // React clears currentTarget after dispatch; retain the form across awaits.
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    const saved = await mutate(
      "/api/v1/learning/categories",
      {
        method: "POST",
        body: JSON.stringify({
          schoolId: data.schoolId,
          academicSessionId: form.get("sessionId"),
          code: form.get("code"),
          name: form.get("name"),
          accentColor: form.get("color") || null,
          displayOrder: Number(form.get("displayOrder") || 0),
          status: "ACTIVE",
          reason: form.get("reason") || null,
        }),
      },
      "Assignment category created.",
    );
    if (saved) submittedForm.reset();
  }

  async function saveAssignment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data) return;
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    const current = editing;
    const lateUntil = form.get("lateUntil");
    const payload = {
      schoolId: data.schoolId,
      academicTermId: form.get("termId"),
      offeringId: form.get("offeringId"),
      categoryId: form.get("categoryId") || null,
      title: form.get("title"),
      instructions: form.get("instructions"),
      resourceLabel: form.get("resourceLabel") || null,
      resourceUrl: form.get("resourceUrl") || null,
      availableFrom: iso(form.get("availableFrom")),
      dueAt: iso(form.get("dueAt")),
      submissionMode: form.get("submissionMode"),
      maximumPoints: form.get("maximumPoints") ? Number(form.get("maximumPoints")) : null,
      allowAttachments: form.get("allowAttachments") === "on",
      maxAttachmentCount:
        form.get("allowAttachments") === "on" ? Number(form.get("maxAttachmentCount") || 3) : 0,
      attachmentRequired:
        form.get("allowAttachments") === "on" && form.get("attachmentRequired") === "on",
      allowLate: form.get("allowLate") === "on",
      lateUntil: lateUntil ? iso(lateUntil) : null,
      expectedVersion: current?.rowVersion ?? null,
      reason: form.get("reason") || null,
    };
    const saved = await mutate(
      current ? `/api/v1/learning/assignments/${current.id}` : "/api/v1/learning/assignments",
      { method: current ? "PUT" : "POST", body: JSON.stringify(payload) },
      current
        ? current.status === "PUBLISHED"
          ? "A new traceable assignment revision was published."
          : "Draft assignment updated."
        : "Draft assignment created.",
    );
    if (saved) {
      if (current) setSelectedId(current.id);
      setEditingId("");
      submittedForm.reset();
    }
  }

  async function transition(action: "publish" | "close") {
    if (!data || !selected) return;
    const reason = transitionReason.trim();
    if (!reason) {
      setError("Enter a reason before changing the assignment status.");
      return;
    }
    const saved = await mutate(
      `/api/v1/learning/assignments/${selected.id}/${action}`,
      {
        method: "POST",
        body: JSON.stringify({
          schoolId: data.schoolId,
          expectedVersion: selected.rowVersion,
          reason,
        }),
      },
      action === "publish"
        ? "Assignment published to its frozen learner audience."
        : "Assignment closed.",
    );
    if (saved) setTransitionReason("");
  }

  async function review(event: FormEvent<HTMLFormElement>, submission: Submission) {
    event.preventDefault();
    if (!data) return;
    const submittedForm = event.currentTarget;
    const form = new FormData(submittedForm);
    const saved = await mutate(
      `/api/v1/learning/submissions/${submission.id}/review`,
      {
        method: "POST",
        body: JSON.stringify({
          schoolId: data.schoolId,
          decision: form.get("decision"),
          feedback: form.get("feedback") || null,
          pointsAwarded: form.get("points") ? Number(form.get("points")) : null,
          expectedVersion: submission.rowVersion,
          reason: form.get("reason"),
        }),
      },
      "Learner response reviewed.",
    );
    if (saved) submittedForm.reset();
  }

  async function uploadAssignmentAttachment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data || !selected || selected.status !== "DRAFT") return;
    const form = event.currentTarget;
    const body = new FormData(form);
    const upload = body.get("file");
    if (!(upload instanceof File) || !upload.size) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(
        `${apiUrl}/api/v1/learning/assignments/${selected.id}/attachments?schoolId=${data.schoolId}`,
        {
          method: "POST",
          credentials: "include",
          headers: { "X-CSRF-TOKEN": await csrfToken() },
          body,
        },
      );
      if (!response.ok) throw new Error(await problem(response, "The private resource could not be uploaded."));
      await load();
      form.reset();
      setNotice("Private assignment resource scanned and attached.");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The private resource could not be uploaded.");
    } finally {
      setBusy(false);
    }
  }

  async function expireAttachment(file: PrivateFile) {
    await mutate(
      `/api/v1/files/${file.fileId}/expire`,
      {
        method: "POST",
        body: JSON.stringify({
          expectedVersion: file.rowVersion,
          reason: "Teacher removed this resource while the assignment was still a draft",
        }),
      },
      "Private resource removed from the draft; its audit history was retained.",
    );
  }

  if (error && !data) {
    return (
      <section className="learning-state learning-state--error" role="alert">
        <AcademicIcon />
        <h1>Assignments are unavailable</h1>
        <p>{error}</p>
      </section>
    );
  }
  if (!data) {
    return (
      <section className="learning-state" aria-live="polite">
        <AcademicIcon />
        <p>Opening the learning workspace…</p>
      </section>
    );
  }

  const activeSessions = data.sessions.filter((item) => item.status === "ACTIVE");
  const reviewCount = data.assignments.reduce(
    (total, item) =>
      total + (item.submissionCounts.SUBMITTED || 0) + (item.submissionCounts.LATE || 0),
    0,
  );

  return (
    <section className="learning-workspace">
      <header className="learning-hero">
        <div>
          <span>Assignments & learner work</span>
          <h1>Make every task clear, timely, and accountable.</h1>
          <p>
            {data.schoolName} controls categories, timing, late rules, response modes, scoring,
            publication, and review. Published changes become numbered revisions instead of silently
            changing what families saw.
          </p>
        </div>
        <aside>
          <ReportIcon />
          <strong>{reviewCount}</strong>
          <span>Waiting for review</span>
        </aside>
      </header>

      <nav className="learning-tabs" aria-label="Learning workspace sections">
        <button type="button" aria-pressed={tab === "board"} className={tab === "board" ? "is-active" : ""} onClick={() => setTab("board")}>
          Assignment board
        </button>
        <button
          type="button"
          aria-pressed={tab === "configure"}
          className={tab === "configure" ? "is-active" : ""}
          onClick={() => setTab("configure")}
        >
          Create & configure
        </button>
      </nav>

      {(notice || error) && (
        <div className={error ? "learning-notice is-error" : "learning-notice"} role="status">
          {error || notice}
        </div>
      )}

      {tab === "configure" ? (
        <div className="learning-configure">
          <article className="learning-panel">
            <header>
              <PlusIcon />
              <div>
                <span>Authority-owned vocabulary</span>
                <h2>Assignment categories</h2>
              </div>
            </header>
            {data.canModerate ? (
              <form className="learning-form learning-form--category" onSubmit={saveCategory}>
                <label>
                  <span>Academic session</span>
                  <select name="sessionId" required>
                    {activeSessions.map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Code</span>
                  <input
                    name="code"
                    required
                    minLength={2}
                    maxLength={30}
                    pattern="[A-Za-z][A-Za-z0-9_]{1,29}"
                    aria-describedby="learning-category-code-help"
                    placeholder="HOMEWORK"
                  />
                </label>
                <small id="learning-category-code-help">
                  2–30 letters, numbers or underscores; start with a letter.
                </small>
                <label>
                  <span>Name</span>
                  <input name="name" required maxLength={100} placeholder="Homework" />
                </label>
                <label>
                  <span>Accent</span>
                  <input name="color" type="color" defaultValue="#5b9279" />
                </label>
                <label>
                  <span>Order</span>
                  <input name="displayOrder" type="number" min="0" max="1000" defaultValue="0" />
                </label>
                <label className="learning-form__wide">
                  <span>Configuration reason</span>
                  <input
                    name="reason"
                    maxLength={240}
                    placeholder="Initial learning policy setup"
                  />
                </label>
                <button className="learning-primary" disabled={busy}>
                  Add category
                </button>
              </form>
            ) : (
              <p className="learning-muted">School assignment authorities manage this list.</p>
            )}
            <div className="learning-category-list">
              {data.categories.map((item) => (
                <span key={item.id} style={{ borderColor: item.accentColor || "#d7dfda" }}>
                  <i style={{ background: item.accentColor || "#8bad9c" }} />
                  <strong>{item.name}</strong>
                  <small>{item.code}</small>
                </span>
              ))}
            </div>
          </article>

          <article className="learning-panel">
            <header>
              <AcademicIcon />
              <div>
                <span>{editing ? "Version-aware change" : "Assigned teaching group"}</span>
                <h2>{editing ? `Edit ${editing.title}` : "New assignment"}</h2>
              </div>
            </header>
            <form
              key={editing?.id || "new"}
              className="learning-form learning-form--assignment"
              onSubmit={saveAssignment}
            >
              <label>
                <span>Term</span>
                <select name="termId" required defaultValue={editing?.termId || ""}>
                  <option value="" disabled>
                    Choose term
                  </option>
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
                <span>Class subject</span>
                <select name="offeringId" required defaultValue={editing?.offeringId || ""}>
                  <option value="" disabled>
                    Choose assigned subject
                  </option>
                  {data.offerings.map((item) => {
                    const section = data.sections.find(
                      (candidate) => candidate.id === item.sectionId,
                    );
                    return (
                      <option key={item.id} value={item.id}>
                        {section?.gradeCode} · {section?.name} · {item.subjectName}
                      </option>
                    );
                  })}
                </select>
              </label>
              <label>
                <span>Category</span>
                <select name="categoryId" defaultValue={editing?.categoryId || ""}>
                  <option value="">No category</option>
                  {data.categories
                    .filter((item) => item.status === "ACTIVE")
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.name}
                      </option>
                    ))}
                </select>
              </label>
              <label className="learning-form__wide">
                <span>Title</span>
                <input name="title" required maxLength={180} defaultValue={editing?.title} />
              </label>
              <label className="learning-form__wide">
                <span>Clear learner instructions</span>
                <textarea
                  name="instructions"
                  required
                  maxLength={6000}
                  defaultValue={editing?.instructions}
                  rows={6}
                />
              </label>
              <label>
                <span>Available from</span>
                <input
                  name="availableFrom"
                  type="datetime-local"
                  required
                  defaultValue={localInput(editing?.availableFrom)}
                />
              </label>
              <label>
                <span>Due at</span>
                <input
                  name="dueAt"
                  type="datetime-local"
                  required
                  defaultValue={localInput(editing?.dueAt)}
                />
              </label>
              <label>
                <span>Response mode</span>
                <select name="submissionMode" defaultValue={editing?.submissionMode || "TEXT"}>
                  <option value="TEXT">Written in portal</option>
                  <option value="LINK">HTTPS link</option>
                  <option value="TEXT_OR_LINK">Text or HTTPS link</option>
                  <option value="FILE">Private file</option>
                  <option value="FLEXIBLE">Text, link, or private file</option>
                  <option value="OFFLINE">Hand in at school</option>
                </select>
              </label>
              <label>
                <span>Maximum points (optional)</span>
                <input
                  name="maximumPoints"
                  type="number"
                  min="0.01"
                  max="10000"
                  step="0.01"
                  defaultValue={editing?.maximumPoints}
                />
              </label>
              <label>
                <span>Resource label (optional)</span>
                <input name="resourceLabel" maxLength={120} defaultValue={editing?.resourceLabel} />
              </label>
              <label>
                <span>HTTPS resource link</span>
                <input
                  name="resourceUrl"
                  type="url"
                  maxLength={1000}
                  defaultValue={editing?.resourceUrl}
                />
              </label>
              <label className="learning-checkbox">
                <input
                  name="allowAttachments"
                  type="checkbox"
                  defaultChecked={editing?.allowAttachments}
                />
                <span>Allow scanned private response attachments</span>
              </label>
              <label>
                <span>Maximum response attachments</span>
                <input
                  name="maxAttachmentCount"
                  type="number"
                  min="1"
                  max="10"
                  defaultValue={editing?.maxAttachmentCount || 3}
                />
              </label>
              <label className="learning-checkbox">
                <input
                  name="attachmentRequired"
                  type="checkbox"
                  defaultChecked={editing?.attachmentRequired}
                />
                <span>Require at least one private attachment</span>
              </label>
              <label className="learning-checkbox">
                <input name="allowLate" type="checkbox" defaultChecked={editing?.allowLate} />
                <span>Allow late responses</span>
              </label>
              <label>
                <span>Late until (optional)</span>
                <input
                  name="lateUntil"
                  type="datetime-local"
                  defaultValue={localInput(editing?.lateUntil)}
                />
              </label>
              <label className="learning-form__wide">
                <span>
                  {editing?.status === "PUBLISHED" ? "Required revision reason" : "Change reason"}
                </span>
                <input
                  name="reason"
                  required={editing?.status === "PUBLISHED"}
                  maxLength={240}
                  placeholder="Explain a material published change"
                />
              </label>
              <div className="learning-form__actions">
                <button className="learning-primary" disabled={busy}>
                  {editing ? "Save assignment" : "Create draft"}
                </button>
                {editing && (
                  <button
                    type="button"
                    className="learning-secondary"
                    onClick={() => setEditingId("")}
                  >
                    Cancel edit
                  </button>
                )}
              </div>
            </form>
          </article>
        </div>
      ) : (
        <div className="learning-board">
          <aside className="learning-assignment-list">
            <header>
              <span>Assigned work</span>
              <strong>{data.assignments.length}</strong>
            </header>
            {data.assignments.length ? (
              data.assignments.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  aria-pressed={item.id === selectedId}
                  className={item.id === selectedId ? "is-active" : ""}
                  onClick={() => setSelectedId(item.id)}
                >
                  <span>
                    <strong>{item.title}</strong>
                    <small>
                      {item.subjectName} · due {new Date(item.dueAt).toLocaleString()}
                    </small>
                  </span>
                  <em className={`learning-status learning-status--${item.status.toLowerCase()}`}>
                    {display(item.status)}
                  </em>
                </button>
              ))
            ) : (
              <div className="learning-empty">
                <AcademicIcon />
                <p>Create the first assignment from the configuration tab.</p>
              </div>
            )}
          </aside>

          <section className="learning-detail" aria-labelledby="learning-assignment-title">
            {!selected ? (
              <div className="learning-empty">
                <AcademicIcon />
                <h2 id="learning-assignment-title">No assignment selected</h2>
              </div>
            ) : (
              <>
                <header className="learning-detail__header">
                  <div>
                    <span>
                      {selected.categoryName || "Assignment"} · revision{" "}
                      {selected.publicationRevision}
                    </span>
                    <h2 id="learning-assignment-title">{selected.title}</h2>
                    <p>{selected.subjectName}</p>
                  </div>
                  <div className="learning-detail__actions">
                    <button
                      className="learning-secondary"
                      onClick={() => {
                        setEditingId(selected.id);
                        setTab("configure");
                      }}
                      disabled={selected.status === "CLOSED"}
                    >
                      {selected.status === "PUBLISHED" ? "Create revision" : "Edit draft"}
                    </button>
                    {selected.status === "DRAFT" && (
                      <button
                        className="learning-primary"
                        disabled={busy}
                        onClick={() => void transition("publish")}
                      >
                        Publish
                      </button>
                    )}
                    {selected.status === "PUBLISHED" && (
                      <button
                        className="learning-primary"
                        disabled={busy}
                        onClick={() => void transition("close")}
                      >
                        Close
                      </button>
                    )}
                  </div>
                </header>
                {(selected.status === "DRAFT" || selected.status === "PUBLISHED") && (
                  <label className="learning-transition-reason">
                    <span>Status-change reason</span>
                    <input
                      value={transitionReason}
                      maxLength={240}
                      onChange={(event) => setTransitionReason(event.target.value)}
                      placeholder="Why this assignment is ready for families or should close"
                    />
                  </label>
                )}
                <section className="learning-brief">
                  <div>
                    <span>Available</span>
                    <strong>{new Date(selected.availableFrom).toLocaleString()}</strong>
                  </div>
                  <div>
                    <span>Due</span>
                    <strong>{new Date(selected.dueAt).toLocaleString()}</strong>
                  </div>
                  <div>
                    <span>Response</span>
                    <strong>{display(selected.submissionMode)}</strong>
                  </div>
                  <div>
                    <span>Audience</span>
                    <strong>{selected.expectedLearnerCount || "Not frozen"}</strong>
                  </div>
                </section>
                <section className="learning-instructions">
                  <p>{selected.instructions}</p>
                  {selected.resourceUrl && (
                    <a href={selected.resourceUrl} target="_blank" rel="noreferrer">
                      {selected.resourceLabel || "Open resource"}
                    </a>
                  )}
                  {!!selected.attachments.length && (
                    <div className="learning-private-files">
                      <strong>Private resources</strong>
                      {selected.attachments.map((file) => (
                        <span key={file.fileId}>
                          <a href={`${apiUrl}/api/v1/files/${file.fileId}`}>{file.filename}</a>
                          {selected.status === "DRAFT" && (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => void expireAttachment(file)}
                            >
                              Remove
                            </button>
                          )}
                        </span>
                      ))}
                    </div>
                  )}
                  {selected.status === "DRAFT" && (
                    <form className="learning-file-upload" onSubmit={uploadAssignmentAttachment}>
                      <label>
                        <span>Add a scanned private resource</span>
                        <input
                          name="file"
                          type="file"
                          required
                          accept="application/pdf,image/jpeg,image/png,image/webp"
                        />
                      </label>
                      <button className="learning-secondary" disabled={busy}>
                        Scan & attach
                      </button>
                      <small>PDF, JPEG, PNG, or WebP · private download only · up to 10 MB</small>
                    </form>
                  )}
                </section>
                {selected.status === "DRAFT" ? (
                  <div className="learning-empty learning-empty--compact">
                    <AttendanceIcon />
                    <p>
                      Publishing freezes the current class roster and creates one response record
                      per learner.
                    </p>
                  </div>
                ) : (
                  <section className="learning-submissions">
                    <header>
                      <div>
                        <span>Learner response register</span>
                        <h3>Review without losing history</h3>
                      </div>
                      <small>
                        {(selected.submissionCounts.SUBMITTED || 0) +
                          (selected.submissionCounts.LATE || 0)}{" "}
                        awaiting review
                      </small>
                    </header>
                    <div className="learning-submission-list">
                      {selected.submissions.map((submission) => (
                        <article key={submission.id}>
                          <header>
                            <span className="learning-roll">{submission.rollNumber || "—"}</span>
                            <div>
                              <strong>{submission.learnerName}</strong>
                              <small>
                                {submission.submittedAt
                                  ? new Date(submission.submittedAt).toLocaleString()
                                  : "No response yet"}
                              </small>
                            </div>
                            <em
                              className={`learning-state-pill learning-state-pill--${submission.state.toLowerCase()}`}
                            >
                              {display(submission.state)}
                            </em>
                          </header>
                          {(submission.responseText || submission.responseLink) && (
                            <div className="learning-response">
                              {submission.responseText && <p>{submission.responseText}</p>}
                              {submission.responseLink && (
                                <a href={submission.responseLink} target="_blank" rel="noreferrer">
                                  Open learner response link
                                </a>
                              )}
                            </div>
                          )}
                          {!!submission.attachments.length && (
                            <div className="learning-private-files">
                              <strong>Private learner evidence</strong>
                              {submission.attachments.map((file) => (
                                <a key={file.fileId} href={`${apiUrl}/api/v1/files/${file.fileId}`}>
                                  {file.filename}
                                </a>
                              ))}
                            </div>
                          )}
                          {submission.teacherFeedback && (
                            <p className="learning-feedback">
                              Teacher feedback: {submission.teacherFeedback}
                            </p>
                          )}
                          {(submission.state === "SUBMITTED" ||
                            submission.state === "LATE" ||
                            (selected.submissionMode === "OFFLINE" &&
                              submission.state === "NOT_STARTED")) && (
                            <form
                              aria-label={`Review ${submission.learnerName}'s response`}
                              onSubmit={(event) => void review(event, submission)}
                            >
                              <label>
                                <span>Decision</span>
                                <select name="decision" defaultValue="ACCEPTED">
                                  <option value="ACCEPTED">Accept</option>
                                  <option value="RETURNED">Return for revision</option>
                                  <option value="EXEMPT">Mark exempt</option>
                                </select>
                              </label>
                              <label>
                                <span>Teacher feedback</span>
                                <input
                                  name="feedback"
                                  maxLength={3000}
                                  placeholder="Required when returned"
                                />
                              </label>
                              {selected.maximumPoints != null && (
                                <label>
                                  <span>Points / {selected.maximumPoints}</span>
                                  <input
                                    name="points"
                                    type="number"
                                    min="0"
                                    max={selected.maximumPoints}
                                    step="0.01"
                                  />
                                </label>
                              )}
                              <label>
                                <span>Review reason</span>
                                <input name="reason" required maxLength={240} />
                              </label>
                              <button className="learning-primary" disabled={busy}>
                                Save review
                              </button>
                            </form>
                          )}
                        </article>
                      ))}
                    </div>
                  </section>
                )}
              </>
            )}
          </section>
        </div>
      )}
    </section>
  );
}
