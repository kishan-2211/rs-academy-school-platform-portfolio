"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { ArrowUpRight, Check, Shield } from "@/components/icons";
import { PlusIcon, SettingsIcon } from "@/components/workspace/workspace-icons";

type ReadinessItem = {
  id: string;
  controlCode: string;
  revisionNumber: number;
  systemControl: boolean;
  category: string;
  title: string;
  guidance: string;
  required: boolean;
  status: string;
  evidenceReference?: string;
  ownerRoleLabel?: string;
  reviewNote?: string;
  revisedBy: string;
  revisedAt: string;
};

type LaunchReview = {
  id: string;
  reviewNumber: number;
  status: string;
  itemCount: number;
  requiredCount: number;
  readyCount: number;
  blockedCount: number;
  snapshotSha256: string;
  matchesCurrentSnapshot: boolean;
  submissionStatement: string;
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
  canConfigure: boolean;
  canApprove: boolean;
  launchState: string;
  itemCount: number;
  requiredCount: number;
  readyCount: number;
  blockedCount: number;
  currentSnapshotSha256: string;
  items: ReadinessItem[];
  reviews: LaunchReview[];
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const categories = [
  "SCHOOL_GOVERNANCE",
  "PRIVACY_SAFEGUARDING",
  "IDENTITY_ACCESS",
  "ACADEMICS",
  "FINANCE",
  "DATA_MIGRATION",
  "PROVIDERS",
  "DEPLOYMENT_SECURITY",
  "RELIABILITY",
  "ACCESSIBILITY_EXPERIENCE",
  "TRAINING_SUPPORT",
  "OTHER",
];
const statuses = ["NOT_STARTED", "IN_PROGRESS", "READY", "BLOCKED", "NOT_APPLICABLE"];

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

async function problem(response: Response, fallback: string) {
  try {
    const body = (await response.json()) as { detail?: string };
    return body.detail || fallback;
  } catch {
    return fallback;
  }
}

function label(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

function dateLabel(value?: string) {
  if (!value) return "Pending";
  return new Intl.DateTimeFormat("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

function stateCopy(state: string) {
  if (state === "GO_LIVE_APPROVED") return "Current evidence independently approved";
  if (state === "APPROVAL_STALE") return "Approval is stale after a configuration change";
  if (state === "REVIEW_PENDING") return "Independent authority decision pending";
  if (state === "READY_FOR_REVIEW") return "All required controls are ready for review";
  return "Required launch controls are still incomplete";
}

export function LaunchReadinessWorkspace({ schoolId }: { schoolId: string }) {
  const [data, setData] = useState<Snapshot | null>(null);
  const [selectedId, setSelectedId] = useState("");
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(
      `${apiUrl}/api/v1/launch-readiness?schoolId=${encodeURIComponent(schoolId)}`,
      { credentials: "include" },
    );
    if (!response.ok) {
      throw new Error(await problem(response, "Launch readiness could not be opened."));
    }
    const snapshot = (await response.json()) as Snapshot;
    setData(snapshot);
    setSelectedId((current) => {
      if (snapshot.items.some((item) => item.id === current)) return current;
      return (
        snapshot.items.find((item) => item.status !== "READY")?.id || snapshot.items[0]?.id || ""
      );
    });
  }, [schoolId]);

  useEffect(() => {
    Promise.resolve()
      .then(load)
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error ? reason.message : "Launch readiness could not be opened.",
        ),
      );
  }, [load]);

  const selected = useMemo(
    () => data?.items.find((item) => item.id === selectedId) ?? null,
    [data, selectedId],
  );
  const eligible = Boolean(
    data?.items.length &&
      data.items.some((item) => item.required) &&
      data.items.filter((item) => item.required).every((item) => item.status === "READY") &&
      !data.items.some((item) => item.status === "BLOCKED"),
  );
  const requiredReadyCount =
    data?.items.filter((item) => item.required && item.status === "READY").length ?? 0;
  const pendingReview = data?.reviews.find((review) => review.status === "PENDING");

  async function mutate(path: string, body: object, success: string, method = "POST") {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(`${apiUrl}${path}`, {
        method,
        credentials: "include",
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken() },
        body: JSON.stringify(body),
      });
      if (!response.ok)
        throw new Error(await problem(response, "The launch record could not be saved."));
      await load();
      setNotice(success);
      return true;
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The launch record could not be saved.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function revise(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    const form = new FormData(event.currentTarget);
    await mutate(
      `/api/v1/launch-readiness/items/${selected.id}`,
      {
        schoolId,
        category: selected.systemControl ? selected.category : form.get("category"),
        title: form.get("title"),
        guidance: form.get("guidance"),
        required: selected.systemControl || form.get("required") === "on",
        status: form.get("status"),
        evidenceReference: form.get("evidenceReference") || null,
        ownerRoleLabel: form.get("ownerRoleLabel") || null,
        reviewNote: form.get("reviewNote") || null,
        expectedRevision: selected.revisionNumber,
        reason: form.get("reason"),
      },
      "A new immutable readiness revision was recorded. Any earlier approval is now stale.",
      "PUT",
    );
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const saved = await mutate(
      "/api/v1/launch-readiness/items",
      {
        schoolId,
        controlCode: form.get("controlCode"),
        category: form.get("category"),
        title: form.get("title"),
        guidance: form.get("guidance"),
        required: form.get("required") === "on",
        status: form.get("status"),
        evidenceReference: form.get("evidenceReference") || null,
        ownerRoleLabel: form.get("ownerRoleLabel") || null,
        reviewNote: form.get("reviewNote") || null,
        reason: form.get("reason"),
      },
      "Custom launch control added inside this school only.",
    );
    if (saved) setCreating(false);
  }

  async function submitReview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await mutate(
      "/api/v1/launch-readiness/reviews",
      {
        schoolId,
        submissionStatement: form.get("submissionStatement"),
        evidenceConfirmed: form.get("evidenceConfirmed") === "on",
        reason: form.get("reason"),
      },
      "Frozen launch evidence submitted for an independent decision.",
    );
  }

  async function decide(event: FormEvent<HTMLFormElement>, review: LaunchReview) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await mutate(
      `/api/v1/launch-readiness/reviews/${review.id}/decision`,
      {
        schoolId,
        decision: form.get("decision"),
        reason: form.get("reason"),
        expectedVersion: review.rowVersion,
      },
      "Independent launch decision recorded without deploying anything automatically.",
    );
  }

  if (!data && !error) {
    return (
      <section className="launch-loading" aria-live="polite">
        <i />
        <p>Opening protected launch evidence…</p>
      </section>
    );
  }
  if (!data) {
    return (
      <section className="launch-empty" role="alert">
        <Shield />
        <h2>Launch controls are unavailable</h2>
        <p>{error}</p>
        <button type="button" onClick={() => void load()}>
          Try again
        </button>
      </section>
    );
  }

  return (
    <section className="launch-workspace">
      <header className="launch-gate" data-state={data.launchState}>
        <div className="launch-gate__mark">
          <Shield />
        </div>
        <div>
          <span>Controlled first-launch gate · प्रथम लॉन्च नियंत्रण</span>
          <h2>{stateCopy(data.launchState)}</h2>
          <p>
            Approval freezes accountable evidence; it never deploys, changes DNS, or releases
            private data automatically.
          </p>
        </div>
        <em>{label(data.launchState)}</em>
      </header>

      {error && (
        <p className="school-config-notice school-config-notice--error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="school-config-notice" role="status">
          <Check /> {notice}
        </p>
      )}

      <div className="launch-metrics" aria-label="Launch readiness summary">
        <article>
          <span>Required ready</span>
          <strong>
            {requiredReadyCount}
            <small> / {data.requiredCount}</small>
          </strong>
        </article>
        <article>
          <span>Blocked</span>
          <strong>{data.blockedCount}</strong>
        </article>
        <article>
          <span>All controls</span>
          <strong>{data.itemCount}</strong>
        </article>
        <article>
          <span>Snapshot</span>
          <code title={data.currentSnapshotSha256}>{data.currentSnapshotSha256.slice(0, 12)}…</code>
        </article>
      </div>

      <div className="launch-control-layout">
        <aside className="launch-control-list">
          <header>
            <div>
              <span>Editable authority register</span>
              <h3>Launch controls</h3>
            </div>
            {data.canConfigure && (
              <button
                type="button"
                onClick={() => setCreating((value) => !value)}
                aria-expanded={creating}
              >
                <PlusIcon /> Custom
              </button>
            )}
          </header>
          <div>
            {data.items.map((item) => (
              <button
                type="button"
                key={item.id}
                className={selectedId === item.id && !creating ? "is-selected" : ""}
                onClick={() => {
                  setCreating(false);
                  setSelectedId(item.id);
                }}
              >
                <i data-status={item.status}>
                  {item.status === "READY" ? (
                    <Check />
                  ) : item.status === "BLOCKED" ? (
                    "!"
                  ) : (
                    item.revisionNumber
                  )}
                </i>
                <span>
                  <strong>{item.title}</strong>
                  <small>
                    {label(item.category)} · {item.required ? "required" : "optional"}
                  </small>
                </span>
                <em data-status={item.status}>{label(item.status)}</em>
              </button>
            ))}
          </div>
        </aside>

        {creating && data.canConfigure ? (
          <form className="launch-editor" onSubmit={create}>
            <header>
              <PlusIcon />
              <div>
                <span>School-defined requirement</span>
                <h3>Add a custom control</h3>
              </div>
            </header>
            <p>
              Use this for a real local requirement. Baseline safety controls cannot be removed or
              made optional.
            </p>
            <div className="launch-form-grid">
              <label>
                <span>Control code</span>
                <input
                  name="controlCode"
                  placeholder="LOCAL_PROVIDER_REVIEW"
                  pattern="[A-Za-z][A-Za-z0-9_ ]{2,59}"
                  maxLength={60}
                  required
                />
              </label>
              <label>
                <span>Category</span>
                <select name="category" defaultValue="OTHER">
                  {categories.map((item) => (
                    <option key={item} value={item}>
                      {label(item)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="wide">
                <span>Title</span>
                <input name="title" maxLength={180} required />
              </label>
              <label className="wide">
                <span>Guidance</span>
                <textarea name="guidance" maxLength={1000} required />
              </label>
              <label>
                <span>Status</span>
                <select name="status" defaultValue="NOT_STARTED">
                  {statuses.map((item) => (
                    <option key={item} value={item}>
                      {label(item)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Owner role</span>
                <input name="ownerRoleLabel" maxLength={120} />
              </label>
              <label className="wide">
                <span>Safe evidence reference</span>
                <input
                  name="evidenceReference"
                  maxLength={500}
                  placeholder="runbook:restore-drill/2026-08"
                />
                <small>
                  Reference only—never paste passwords, credentials, private keys, or raw
                  student/staff data.
                </small>
              </label>
              <label className="wide">
                <span>Review / blocker note</span>
                <textarea name="reviewNote" maxLength={1000} />
              </label>
              <label className="check">
                <input name="required" type="checkbox" />
                <span>Make this a required go-live control</span>
              </label>
              <label className="wide">
                <span>Reason</span>
                <input
                  name="reason"
                  defaultValue="School-specific launch requirement configured by authorized leadership"
                  maxLength={500}
                  required
                />
              </label>
            </div>
            <footer>
              <button type="button" onClick={() => setCreating(false)}>
                Cancel
              </button>
              <button className="button button--dark" disabled={busy}>
                Add control <ArrowUpRight />
              </button>
            </footer>
          </form>
        ) : selected ? (
          <form key={selected.id} className="launch-editor" onSubmit={revise}>
            <header>
              <SettingsIcon />
              <div>
                <span>
                  {selected.systemControl ? "Required safety baseline" : "School-defined control"}
                </span>
                <h3>{selected.title}</h3>
              </div>
              <b>Revision {selected.revisionNumber}</b>
            </header>
            <p>{selected.guidance}</p>
            <div className="launch-form-grid">
              <label>
                <span>Category</span>
                <select
                  name="category"
                  defaultValue={selected.category}
                  disabled={selected.systemControl}
                >
                  {categories.map((item) => (
                    <option key={item} value={item}>
                      {label(item)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Status</span>
                <select name="status" defaultValue={selected.status}>
                  {statuses
                    .filter((item) => !selected.systemControl || item !== "NOT_APPLICABLE")
                    .map((item) => (
                      <option key={item} value={item}>
                        {label(item)}
                      </option>
                    ))}
                </select>
              </label>
              <label className="wide">
                <span>Title</span>
                <input name="title" defaultValue={selected.title} maxLength={180} required />
              </label>
              <label className="wide">
                <span>Guidance</span>
                <textarea
                  name="guidance"
                  defaultValue={selected.guidance}
                  maxLength={1000}
                  required
                />
              </label>
              <label>
                <span>Owner role</span>
                <input
                  name="ownerRoleLabel"
                  defaultValue={selected.ownerRoleLabel || ""}
                  maxLength={120}
                />
              </label>
              <label className="wide">
                <span>Safe evidence reference</span>
                <input
                  name="evidenceReference"
                  defaultValue={selected.evidenceReference || ""}
                  maxLength={500}
                  placeholder="runbook:approved-evidence/reference"
                />
                <small>
                  Required for Ready. संदर्भ लिखें—पासवर्ड या निजी विद्यार्थी/कर्मचारी डेटा नहीं।
                </small>
              </label>
              <label className="wide">
                <span>Review / blocker note</span>
                <textarea
                  name="reviewNote"
                  defaultValue={selected.reviewNote || ""}
                  maxLength={1000}
                />
              </label>
              <label className="check">
                <input
                  name="required"
                  type="checkbox"
                  defaultChecked={selected.required}
                  disabled={selected.systemControl}
                />
                <span>
                  {selected.systemControl
                    ? "Baseline control remains required"
                    : "Required before launch"}
                </span>
              </label>
              <label className="wide">
                <span>Reason for revision</span>
                <input
                  name="reason"
                  defaultValue="Launch evidence reviewed by authorized school leadership"
                  maxLength={500}
                  required
                />
              </label>
            </div>
            <footer>
              <small>
                Last revised by {selected.revisedBy} · {dateLabel(selected.revisedAt)}
              </small>
              {data.canConfigure && (
                <button className="button button--dark" disabled={busy}>
                  Record new revision <ArrowUpRight />
                </button>
              )}
            </footer>
          </form>
        ) : null}
      </div>

      <section className="launch-review-room">
        <header>
          <div>
            <span>Immutable maker-checker review</span>
            <h2>Go-live evidence room</h2>
            <p>
              One authorized person submits; a different authorized person approves or rejects the
              frozen snapshot.
            </p>
          </div>
          <Shield />
        </header>
        {data.canConfigure &&
          eligible &&
          !pendingReview &&
          data.launchState !== "GO_LIVE_APPROVED" && (
            <form className="launch-submit" onSubmit={submitReview}>
              <label>
                <span>Authority statement</span>
                <textarea
                  name="submissionStatement"
                  defaultValue="All required launch controls and their safe evidence references were reviewed for the current school configuration."
                  maxLength={1000}
                  required
                />
              </label>
              <label className="check">
                <input name="evidenceConfirmed" type="checkbox" required />
                <span>
                  I independently checked each reference; no credential or raw private record is
                  stored here.
                </span>
              </label>
              <label>
                <span>Submission reason</span>
                <input
                  name="reason"
                  defaultValue="Current launch-readiness evidence submitted for independent authority review"
                  maxLength={500}
                  required
                />
              </label>
              <button className="button button--dark" disabled={busy}>
                Freeze and submit evidence <ArrowUpRight />
              </button>
            </form>
          )}
        {!eligible && (
          <p className="launch-review-hint">
            Complete every required control and resolve all blocked controls before submitting. सभी
            आवश्यक नियंत्रण Ready होने चाहिए।
          </p>
        )}
        <div className="launch-review-history">
          {data.reviews.length ? (
            data.reviews.map((review) => (
              <article key={review.id} data-status={review.status}>
                <header>
                  <div>
                    <span>
                      Review {review.reviewNumber} · {dateLabel(review.submittedAt)}
                    </span>
                    <h3>{review.submissionStatement}</h3>
                    <small>
                      Submitted by {review.submittedBy} · {review.requiredCount} required controls
                    </small>
                  </div>
                  <em>{label(review.status)}</em>
                </header>
                <dl>
                  <div>
                    <dt>Evidence fingerprint</dt>
                    <dd>
                      <code>{review.snapshotSha256.slice(0, 18)}…</code>
                    </dd>
                  </div>
                  <div>
                    <dt>Current match</dt>
                    <dd>{review.matchesCurrentSnapshot ? "Current" : "Stale"}</dd>
                  </div>
                </dl>
                {review.decisionReason && (
                  <blockquote>
                    <strong>{review.decidedBy}</strong>
                    {review.decisionReason}
                  </blockquote>
                )}
                {review.status === "PENDING" && data.canApprove && (
                  <form onSubmit={(event) => decide(event, review)}>
                    <label>
                      <span>Decision</span>
                      <select name="decision">
                        <option value="APPROVED">Approve current evidence</option>
                        <option value="REJECTED">Reject for revision</option>
                      </select>
                    </label>
                    <label>
                      <span>Independent reason</span>
                      <textarea name="reason" maxLength={1000} required />
                    </label>
                    <button className="button button--dark" disabled={busy}>
                      Record decision <ArrowUpRight />
                    </button>
                    <small>The submitter cannot decide their own review.</small>
                  </form>
                )}
              </article>
            ))
          ) : (
            <div className="launch-no-reviews">
              <Shield />
              <strong>No frozen launch review yet</strong>
              <span>The complete history will remain here after the first submission.</span>
            </div>
          )}
        </div>
      </section>
    </section>
  );
}
