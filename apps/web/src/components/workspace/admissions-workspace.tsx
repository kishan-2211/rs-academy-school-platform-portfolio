"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowRight, Check, Clock, Message, User } from "@/components/icons";
import { MotionIllustration } from "@/components/motion-illustration";

type EnquiryStatus = "NEW" | "OPEN" | "QUALIFIED" | "CLOSED" | "SPAM";
type EnquiryIntent = "GENERAL" | "VISIT" | "APPLICATION";

type EnquiryAssignee = {
  membershipId: string;
  schoolId: string;
  displayName: string;
};

type EnquiryListItem = {
  id: string;
  reference: string;
  name: string;
  email: string;
  phone: string | null;
  topic: string | null;
  intent: EnquiryIntent;
  status: EnquiryStatus;
  source: string;
  assignee: EnquiryAssignee | null;
  followUpAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
};

type EnquiryMetrics = {
  newCount: number;
  openCount: number;
  qualifiedCount: number;
  overdueCount: number;
  unassignedCount: number;
};

type EnquiryPage = {
  items: EnquiryListItem[];
  page: number;
  size: number;
  total: number;
  metrics: EnquiryMetrics;
};

type EnquiryDetail = EnquiryListItem & {
  message: string;
  lastContactedAt: string | null;
  closedAt: string | null;
  privacyNoticeVersion: string;
  consentedAt: string;
  notes: Array<{ id: string; body: string; authorName: string; createdAt: string }>;
};

type ApiProblem = { detail?: string; code?: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const pageSize = 25;

const statusLabels: Record<EnquiryStatus, string> = {
  NEW: "New",
  OPEN: "In follow-up",
  QUALIFIED: "Qualified",
  CLOSED: "Closed",
  SPAM: "Spam",
};

const intentLabels: Record<EnquiryIntent, string> = {
  GENERAL: "General question",
  VISIT: "Campus visit",
  APPLICATION: "Application interest",
};

const transitions: Record<EnquiryStatus, EnquiryStatus[]> = {
  NEW: ["NEW", "OPEN", "CLOSED", "SPAM"],
  OPEN: ["OPEN", "QUALIFIED", "CLOSED", "SPAM"],
  QUALIFIED: ["QUALIFIED", "OPEN", "CLOSED"],
  CLOSED: ["CLOSED", "OPEN"],
  SPAM: ["SPAM", "NEW"],
};

function cookie(name: string) {
  const value = document.cookie.split("; ").find((entry) => entry.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : "";
}

function dateLabel(value: string | null, withTime = true) {
  if (!value) return "Not recorded";
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    ...(withTime ? { hour: "numeric", minute: "2-digit" } : {}),
  }).format(new Date(value));
}

function dateTimeInput(value: string | null) {
  if (!value) return "";
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function isOverdue(value: string | null, status: EnquiryStatus) {
  return Boolean(value && !["CLOSED", "SPAM"].includes(status) && new Date(value).getTime() < Date.now());
}

async function problemMessage(response: Response, fallback: string) {
  try {
    const problem = (await response.json()) as ApiProblem;
    return problem.detail || fallback;
  } catch {
    return fallback;
  }
}

export function AdmissionsWorkspace() {
  const router = useRouter();
  const applicationAttempt = useRef<{ enquiryId: string; key: string } | null>(null);
  const [data, setData] = useState<EnquiryPage | null>(null);
  const [status, setStatus] = useState("");
  const [intent, setIntent] = useState("");
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState("");
  const [detail, setDetail] = useState<EnquiryDetail | null>(null);
  const [assignees, setAssignees] = useState<EnquiryAssignee[]>([]);
  const [canManage, setCanManage] = useState(false);
  const [canManageApplications, setCanManageApplications] = useState(false);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [detailError, setDetailError] = useState("");
  const [message, setMessage] = useState("");
  const [applicationError, setApplicationError] = useState("");
  const [startingApplication, setStartingApplication] = useState(false);

  const loadEnquiries = useCallback(async () => {
    const parameters = new URLSearchParams({ page: String(page), size: String(pageSize) });
    if (status) parameters.set("status", status);
    if (intent) parameters.set("intent", intent);
    if (query) parameters.set("query", query);
    const [response, contextResponse] = await Promise.all([
      fetch(`${apiUrl}/api/v1/admissions/enquiries?${parameters}`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/context`, { credentials: "include" }),
    ]);
    if (!response.ok) throw new Error(await problemMessage(response, "Admissions enquiries are unavailable."));
    const result = (await response.json()) as EnquiryPage;
    let manageable = false;
    let applicationManageable = false;
    if (contextResponse.ok) {
      const context = (await contextResponse.json()) as { capabilities: string[] };
      manageable = context.capabilities.includes("admissions.enquiry.manage");
      applicationManageable = context.capabilities.includes("admissions.application.manage");
    }
    return { result, manageable, applicationManageable };
  }, [intent, page, query, status]);

  useEffect(() => {
    let cancelled = false;
    loadEnquiries()
      .then(({ result, manageable, applicationManageable }) => {
        if (cancelled) return;
        setData(result);
        setCanManage(manageable);
        setCanManageApplications(applicationManageable);
        setError("");
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
  }, [loadEnquiries]);

  async function openEnquiry(enquiryId: string) {
    setSelectedId(enquiryId);
    setDetailLoading(true);
    setDetailError("");
    setMessage("");
    try {
      const responses = await Promise.all([
        fetch(`${apiUrl}/api/v1/admissions/enquiries/${enquiryId}`, { credentials: "include" }),
        canManage
          ? fetch(`${apiUrl}/api/v1/admissions/enquiries/${enquiryId}/assignees`, { credentials: "include" })
          : Promise.resolve(null),
      ]);
      if (!responses[0].ok) {
        throw new Error(await problemMessage(responses[0], "The selected enquiry could not be opened."));
      }
      setDetail((await responses[0].json()) as EnquiryDetail);
      if (responses[1]?.ok) setAssignees((await responses[1].json()) as EnquiryAssignee[]);
      else setAssignees([]);
    } catch (reason) {
      setDetail(null);
      setDetailError(reason instanceof Error ? reason.message : "The selected enquiry could not be opened.");
    } finally {
      setDetailLoading(false);
    }
  }

  async function securedRequest(path: string, init: RequestInit) {
    let csrf = cookie("educore_csrf");
    if (!csrf) {
      await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
      csrf = cookie("educore_csrf");
    }
    return fetch(`${apiUrl}${path}`, {
      ...init,
      credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf, ...init.headers },
    });
  }

  async function saveEnquiry(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail || !canManage) return;
    const form = new FormData(event.currentTarget);
    const followUp = String(form.get("followUpAt") ?? "");
    setSaving(true);
    setDetailError("");
    setMessage("");
    try {
      const response = await securedRequest(`/api/v1/admissions/enquiries/${detail.id}`, {
        method: "PATCH",
        body: JSON.stringify({
          status: String(form.get("status")),
          assignedMembershipId: String(form.get("assignedMembershipId") ?? "") || null,
          followUpAt: followUp ? new Date(followUp).toISOString() : null,
          recordContact: form.get("recordContact") === "on",
          note: String(form.get("note") ?? "") || null,
          expectedVersion: detail.version,
        }),
      });
      if (!response.ok) {
        const fallback = response.status === 409
          ? "Someone else updated this enquiry. Reopen it before saving your changes."
          : "The enquiry could not be updated.";
        throw new Error(await problemMessage(response, fallback));
      }
      const updated = (await response.json()) as EnquiryDetail;
      setDetail(updated);
      setMessage("Enquiry updated and recorded in the audit history.");
      const refreshed = await loadEnquiries();
      setData(refreshed.result);
      setCanManage(refreshed.manageable);
      setCanManageApplications(refreshed.applicationManageable);
    } catch (reason) {
      setDetailError(reason instanceof Error ? reason.message : "The enquiry could not be updated.");
    } finally {
      setSaving(false);
    }
  }

  async function startApplication(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail || !canManageApplications) return;
    const form = new FormData(event.currentTarget);
    if (applicationAttempt.current?.enquiryId !== detail.id) {
      applicationAttempt.current = { enquiryId: detail.id, key: crypto.randomUUID() };
    }
    setStartingApplication(true);
    setApplicationError("");
    try {
      const response = await securedRequest("/api/v1/admissions/applications", {
        method: "POST",
        headers: { "Idempotency-Key": applicationAttempt.current.key },
        body: JSON.stringify({
          enquiryId: detail.id,
          intakeSessionLabel: String(form.get("intakeSessionLabel")),
          targetGradeCode: String(form.get("targetGradeCode")),
          applicantLegalName: String(form.get("applicantLegalName")),
          applicantPreferredName: String(form.get("applicantPreferredName") ?? "") || null,
          birthDate: String(form.get("birthDate")),
          gender: String(form.get("gender")),
          genderDescription: String(form.get("genderDescription") ?? "") || null,
        }),
      });
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The application could not be started."));
      }
      const application = (await response.json()) as { id: string };
      applicationAttempt.current = null;
      router.push(`/workspace/admissions/applications?application=${application.id}`);
    } catch (reason) {
      setApplicationError(reason instanceof Error ? reason.message : "The application could not be started.");
    } finally {
      setStartingApplication(false);
    }
  }

  function applySearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setPage(0);
    setQuery(queryDraft.trim());
  }

  const totalPages = Math.max(1, Math.ceil((data?.total ?? 0) / pageSize));

  return (
    <section className="admissions-workspace">
      <header className="module-page__hero admissions-hero">
        <div>
          <span>Admissions · Enquiry desk</span>
          <h1>Every family gets a thoughtful next step.</h1>
          <p>Receive website enquiries, assign ownership, record contact, and keep follow-up visible without exposing records outside the school.</p>
        </div>
        <div className="admissions-hero__actions">
          <div className="admissions-hero__signal">
            <span><i /> Live workflow</span>
            <strong>{data?.metrics.overdueCount ?? 0}</strong>
            <small>follow-ups overdue</small>
          </div>
          <Link href="/workspace/admissions/applications">Open applications <ArrowRight /></Link>
        </div>
      </header>

      {error && <div className="admissions-notice admissions-notice--error" role="alert">{error}</div>}
      {message && (
        <div className="admissions-notice admissions-notice--success admissions-notice--motion" role="status">
          <MotionIllustration className="admissions-success-motion" src="/animations/successful-target.lottie" loop={false} />
          <span><strong>Next step secured</strong>{message}</span>
        </div>
      )}

      <div className="admissions-metrics" aria-label="Admissions enquiry summary">
        {([
          ["New", data?.metrics.newCount ?? 0, "coral"],
          ["In follow-up", data?.metrics.openCount ?? 0, "mint"],
          ["Qualified", data?.metrics.qualifiedCount ?? 0, "yellow"],
          ["Unassigned", data?.metrics.unassignedCount ?? 0, "lavender"],
        ] as const).map(([label, value, tone]) => (
          <article className={`admissions-metric admissions-metric--${tone}`} key={label}>
            <span>{label}</span><strong>{loading ? "—" : value}</strong><small>current enquiries</small>
          </article>
        ))}
      </div>

      <form className="admissions-filters" onSubmit={applySearch}>
        <label className="admissions-search">
          <span className="sr-only">Search enquiries</span>
          <input value={queryDraft} onChange={(event) => setQueryDraft(event.target.value)} maxLength={100} placeholder="Search name, email, phone, or topic" />
          <button type="submit">Search</button>
        </label>
        <label><span>Status</span><select value={status} onChange={(event) => { setLoading(true); setStatus(event.target.value); setPage(0); }}><option value="">All statuses</option>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label><span>Intent</span><select value={intent} onChange={(event) => { setLoading(true); setIntent(event.target.value); setPage(0); }}><option value="">All intents</option>{Object.entries(intentLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        {(status || intent || query) && <button className="admissions-clear" type="button" onClick={() => { setLoading(true); setStatus(""); setIntent(""); setQuery(""); setQueryDraft(""); setPage(0); }}>Clear filters</button>}
      </form>

      <div className="admissions-board">
        <section className="enquiry-list" aria-label="Admissions enquiries" aria-busy={loading}>
          <header><div><span>Family enquiries</span><strong>{data?.total ?? 0} records</strong></div><small>Newest first</small></header>
          {loading ? (
            <div className="enquiry-list__loading"><Clock /> Loading school-scoped enquiries…</div>
          ) : data?.items.length ? (
            <div className="enquiry-list__items">
              {data.items.map((item) => (
                <button className={`enquiry-row${selectedId === item.id ? " enquiry-row--selected" : ""}`} type="button" key={item.id} onClick={() => openEnquiry(item.id)} aria-pressed={selectedId === item.id}>
                  <span className={`enquiry-row__status enquiry-row__status--${item.status.toLowerCase()}`}>{statusLabels[item.status]}</span>
                  <span className="enquiry-row__person"><strong>{item.name}</strong><small>{item.reference} · {intentLabels[item.intent]}</small></span>
                  <span className="enquiry-row__owner"><small>Owner</small><strong>{item.assignee?.displayName ?? "Unassigned"}</strong></span>
                  <span className={isOverdue(item.followUpAt, item.status) ? "enquiry-row__due enquiry-row__due--late" : "enquiry-row__due"}><Clock /><small>{item.followUpAt ? dateLabel(item.followUpAt) : dateLabel(item.createdAt, false)}</small></span>
                  <ArrowRight />
                </button>
              ))}
            </div>
          ) : (
            <div className="enquiry-list__empty"><Message /><h2>No enquiries in this view</h2><p>New website enquiries will appear here automatically. Try clearing filters if you expected to see a record.</p></div>
          )}
          <footer>
            <button type="button" disabled={page === 0 || loading} onClick={() => { setLoading(true); setPage((value) => Math.max(0, value - 1)); }}>Previous</button>
            <span>Page {page + 1} of {totalPages}</span>
            <button type="button" disabled={page + 1 >= totalPages || loading} onClick={() => { setLoading(true); setPage((value) => value + 1); }}>Next</button>
          </footer>
        </section>

        <aside className="enquiry-detail" aria-label="Selected enquiry">
          {detailLoading ? (
            <div className="enquiry-detail__placeholder"><Clock /><h2>Opening enquiry…</h2><p>Checking the record against your active school membership.</p></div>
          ) : detail ? (
            <div className="enquiry-detail__content">
              <header>
                <div><span>{detail.reference}</span><h2>{detail.name}</h2><p>{intentLabels[detail.intent]} · received {dateLabel(detail.createdAt)}</p></div>
                <em className={`enquiry-row__status enquiry-row__status--${detail.status.toLowerCase()}`}>{statusLabels[detail.status]}</em>
              </header>

              {detailError && <div className="admissions-notice admissions-notice--error" role="alert">{detailError}</div>}

              <div className="enquiry-contact-grid">
                <a href={`mailto:${detail.email}`}><Message /><span><small>Email</small><strong>{detail.email}</strong></span></a>
                {detail.phone && <a href={`tel:${detail.phone}`}><User /><span><small>Phone</small><strong>{detail.phone}</strong></span></a>}
              </div>

              <section className="enquiry-message"><span>Family message</span>{detail.topic && <strong>{detail.topic}</strong>}<p>{detail.message}</p></section>

              {detail.status === "QUALIFIED" && canManageApplications && (
                <details className="application-starter">
                  <summary><span><strong>Start admission application</strong><small>Carry this family contact into an applicant record.</small></span><ArrowRight /></summary>
                  <form onSubmit={startApplication}>
                    {applicationError && <div className="admissions-notice admissions-notice--error" role="alert">{applicationError}</div>}
                    <div className="application-starter__grid">
                      <label><span>Applicant legal name</span><input name="applicantLegalName" maxLength={120} required autoComplete="off" /></label>
                      <label><span>Preferred name</span><input name="applicantPreferredName" maxLength={120} autoComplete="off" /></label>
                      <label><span>Date of birth</span><input name="birthDate" type="date" max={new Date().toISOString().slice(0, 10)} required /></label>
                      <label><span>Gender</span><select name="gender" defaultValue="NOT_STATED"><option value="NOT_STATED">Prefer not to state</option><option value="FEMALE">Female</option><option value="MALE">Male</option><option value="NON_BINARY">Non-binary</option><option value="SELF_DESCRIBED">Self-described</option></select></label>
                      <label><span>Self-described value</span><input name="genderDescription" maxLength={80} autoComplete="off" /></label>
                      <label><span>Applying for</span><select name="targetGradeCode" required defaultValue=""><option value="" disabled>Choose class</option>{["NURSERY", "LKG", "UKG", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"].map((grade) => <option key={grade} value={grade}>{grade === "NURSERY" ? "Nursery" : `Class ${grade}`}</option>)}</select></label>
                      <label className="application-starter__wide"><span>Intake/session label</span><input name="intakeSessionLabel" maxLength={80} placeholder="Use the school-configured intake name" required /></label>
                    </div>
                    <p>The enquiry contact becomes the primary guardian. Campus and academic session can be selected after those settings are confirmed.</p>
                    <button className="button button--coral" type="submit" disabled={startingApplication}>{startingApplication ? "Creating safely…" : "Create draft application"} <ArrowRight /></button>
                  </form>
                </details>
              )}

              {canManage ? (
                <form className="enquiry-update" key={`${detail.id}-${detail.version}`} onSubmit={saveEnquiry}>
                  <div className="enquiry-update__grid">
                    <label><span>Status</span><select name="status" defaultValue={detail.status}>{transitions[detail.status].map((value) => <option value={value} key={value}>{statusLabels[value]}</option>)}</select></label>
                    <label><span>Owner</span><select name="assignedMembershipId" defaultValue={detail.assignee?.membershipId ?? ""}><option value="">Unassigned</option>{assignees.map((assignee) => <option value={assignee.membershipId} key={assignee.membershipId}>{assignee.displayName}</option>)}</select></label>
                    <label className="enquiry-update__wide"><span>Next follow-up</span><input name="followUpAt" type="datetime-local" defaultValue={dateTimeInput(detail.followUpAt)} /></label>
                  </div>
                  <label className="enquiry-contact-check"><input name="recordContact" type="checkbox" /><span><strong>Contact happened now</strong><small>Updates the last-contacted time when saved.</small></span></label>
                  <label className="enquiry-note"><span>Private note</span><textarea name="note" maxLength={1000} rows={3} placeholder="Add context for the admissions team. Never paste passwords or unnecessary sensitive data." /></label>
                  <button className="button button--coral" type="submit" disabled={saving}>{saving ? "Saving safely…" : "Save next step"} <ArrowRight /></button>
                </form>
              ) : (
                <div className="enquiry-readonly"><Check /><div><strong>Read-only access</strong><p>Your role can view this enquiry but cannot assign, change status, or add private notes.</p></div></div>
              )}

              <section className="enquiry-history">
                <header><span>Follow-up history</span><small>Last contact: {dateLabel(detail.lastContactedAt)}</small></header>
                {detail.notes.length ? detail.notes.map((note) => <article key={note.id}><i /><div><strong>{note.authorName}</strong><p>{note.body}</p><small>{dateLabel(note.createdAt)}</small></div></article>) : <div className="enquiry-history__empty">No private notes yet. The original family message remains above.</div>}
              </section>
            </div>
          ) : (
            <div className="enquiry-detail__placeholder">
              <span><User /></span><h2>Select a family enquiry</h2><p>Contact details and private notes appear only after the server confirms access to the enquiry&apos;s school.</p>
              {detailError && <div className="admissions-notice admissions-notice--error" role="alert">{detailError}</div>}
            </div>
          )}
        </aside>
      </div>
    </section>
  );
}
