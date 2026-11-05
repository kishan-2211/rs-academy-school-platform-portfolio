"use client";

import { admissionHandoffSessions } from "@/lib/admission-sessions";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { ArrowRight, Check, Clock, Message, User } from "@/components/icons";
import { MotionIllustration } from "@/components/motion-illustration";

type ApplicationStatus = "DRAFT" | "SUBMITTED" | "UNDER_REVIEW" | "WAITLISTED" | "OFFERED" | "ACCEPTED" | "REJECTED" | "WITHDRAWN" | "ENROLLED";
type ApplicantGender = "FEMALE" | "MALE" | "NON_BINARY" | "SELF_DESCRIBED" | "NOT_STATED";
type DocumentStatus = "MISSING" | "RECEIVED" | "VERIFIED" | "REJECTED" | "WAIVED";
type ReceivedMethod = "PRIVATE_UPLOAD" | "OFFLINE_ORIGINAL" | "OFFLINE_COPY";

type Assignee = { membershipId: string; schoolId: string; displayName: string };
type ApplicationListItem = {
  admissionType: "REGULAR" | "RTE";
  id: string;
  reference: string;
  applicantLegalName: string;
  applicantPreferredName: string | null;
  targetGradeCode: string;
  intakeSessionLabel: string;
  status: ApplicationStatus;
  assignee: Assignee | null;
  enquiryReference: string | null;
  requiredDocumentCount: number;
  verifiedDocumentCount: number;
  createdAt: string;
  updatedAt: string;
  version: number;
};
type ApplicationPage = {
  items: ApplicationListItem[];
  page: number;
  size: number;
  total: number;
  metrics: { draftCount: number; reviewCount: number; offeredCount: number; acceptedCount: number; incompleteDocumentCount: number };
};
type ApplicationGuardian = {
  id: string;
  displayName: string;
  relationshipCode: string;
  email: string | null;
  phone: string | null;
  primaryContact: boolean;
  pickupAuthorized: boolean;
  matchedGuardianId: string | null;
  version: number;
};
type ApplicationDocument = {
  id: string;
  documentCode: string;
  displayName: string;
  required: boolean;
  status: DocumentStatus;
  receivedMethod: ReceivedMethod | null;
  receivedAt: string | null;
  verifiedAt: string | null;
  verifiedByName: string | null;
  reviewNote: string | null;
  version: number;
};
type Decision = {
  id: string;
  decision: "OFFER" | "WAITLIST" | "REJECT";
  reasonCode: string;
  internalSummary: string | null;
  offerExpiresOn: string | null;
  decidedByName: string;
  supersedesDecisionId: string | null;
  createdAt: string;
};
type Enrolment = {
  learnerId: string;
  enrolmentId: string;
  learnerReference: string;
  enrolmentReference: string;
  admissionNumber: string | null;
  campusId: string;
  campusName: string;
  academicSessionId: string;
  academicSessionName: string;
  gradeCode: string;
  placementStatus: string;
  startsOn: string;
};
type ApplicationDetail = {
  admissionType: "REGULAR" | "RTE";
  rteReference: string | null;
  id: string;
  schoolId: string;
  reference: string;
  enquiryId: string | null;
  enquiryReference: string | null;
  targetCampusId: string | null;
  targetCampusName: string | null;
  targetAcademicSessionId: string | null;
  targetAcademicSessionName: string | null;
  intakeSessionLabel: string;
  targetGradeCode: string;
  applicantLegalName: string;
  applicantPreferredName: string | null;
  birthDate: string;
  gender: ApplicantGender;
  genderDescription: string | null;
  status: ApplicationStatus;
  assignee: Assignee | null;
  submittedAt: string | null;
  acceptedAt: string | null;
  withdrawnAt: string | null;
  enrolledAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  guardians: ApplicationGuardian[];
  documents: ApplicationDocument[];
  decisions: Decision[];
  enrolment: Enrolment | null;
};
type Options = {
  campuses: Array<{ id: string; displayName: string }>;
  academicSessions: Array<{ id: string; name: string; startsOn: string; endsOn: string; status: string; rteSeatLimit: number; rteSeatsUsed: number; rteMinAgeYears: number; rteMaxAgeYears: number }>;
  assignees: Assignee[];
  guardianMatches: Array<{
    applicationGuardianId: string;
    applicationGuardianName: string;
    candidates: Array<{ guardianId: string; displayName: string; maskedEmail: string | null; maskedPhone: string | null }>;
  }>;
};
type ApiProblem = { detail?: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const pageSize = 25;
const grades = ["NURSERY", "LKG", "UKG", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"];
const statusLabels: Record<ApplicationStatus, string> = {
  DRAFT: "Draft",
  SUBMITTED: "Submitted",
  UNDER_REVIEW: "Under review",
  WAITLISTED: "Waitlisted",
  OFFERED: "Offered",
  ACCEPTED: "Accepted",
  REJECTED: "Rejected",
  WITHDRAWN: "Withdrawn",
  ENROLLED: "Enrolled",
};
const statusTransitions: Partial<Record<ApplicationStatus, ApplicationStatus[]>> = {
  DRAFT: ["SUBMITTED", "WITHDRAWN"],
  SUBMITTED: ["UNDER_REVIEW", "WITHDRAWN"],
  UNDER_REVIEW: ["WITHDRAWN"],
  WAITLISTED: ["UNDER_REVIEW", "WITHDRAWN"],
  OFFERED: ["ACCEPTED", "UNDER_REVIEW", "WITHDRAWN"],
  ACCEPTED: ["UNDER_REVIEW", "WITHDRAWN"],
  REJECTED: ["UNDER_REVIEW"],
  WITHDRAWN: ["UNDER_REVIEW"],
};

function cookie(name: string) {
  const value = document.cookie.split("; ").find((entry) => entry.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : "";
}

function dateLabel(value: string | null, withTime = false) {
  if (!value) return "Not recorded";
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    ...(withTime ? { hour: "numeric", minute: "2-digit" } : {}),
  }).format(new Date(value));
}

function genderLabel(value: ApplicantGender, description: string | null) {
  if (value === "SELF_DESCRIBED") return description || "Self-described";
  return { FEMALE: "Female", MALE: "Male", NON_BINARY: "Non-binary", NOT_STATED: "Not stated" }[value];
}

async function problemMessage(response: Response, fallback: string) {
  try {
    const problem = (await response.json()) as ApiProblem;
    return problem.detail || fallback;
  } catch {
    return fallback;
  }
}

export function AdmissionApplicationsWorkspace({ initialApplicationId }: { initialApplicationId: string }) {
  const [data, setData] = useState<ApplicationPage | null>(null);
  const [status, setStatus] = useState("");
  const [admissionTypeFilter, setAdmissionTypeFilter] = useState("");
  const [editingType, setEditingType] = useState("REGULAR");
  const [editingSession, setEditingSession] = useState("");
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState(initialApplicationId);
  const [detail, setDetail] = useState<ApplicationDetail | null>(null);
  const [options, setOptions] = useState<Options | null>(null);
  const [capabilities, setCapabilities] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [detailError, setDetailError] = useState("");
  const [message, setMessage] = useState("");

  const canManage = capabilities.includes("admissions.application.manage");
  const canDecide = capabilities.includes("admissions.application.decide");
  const canEnrol = capabilities.includes("admissions.application.enrol")
    && capabilities.includes("student.manage")
    && capabilities.includes("guardian.manage");

  const loadApplications = useCallback(async () => {
    const parameters = new URLSearchParams({ page: String(page), size: String(pageSize) });
    if (status) parameters.set("status", status);
    if (admissionTypeFilter) parameters.set("admissionType", admissionTypeFilter);
    if (query) parameters.set("query", query);
    const [response, contextResponse] = await Promise.all([
      fetch(`${apiUrl}/api/v1/admissions/applications?${parameters}`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/context`, { credentials: "include" }),
    ]);
    if (!response.ok) throw new Error(await problemMessage(response, "Admission applications are unavailable."));
    const result = (await response.json()) as ApplicationPage;
    const nextCapabilities = contextResponse.ok
      ? ((await contextResponse.json()) as { capabilities: string[] }).capabilities
      : [];
    return { result, nextCapabilities };
  }, [page, query, status, admissionTypeFilter]);

  useEffect(() => {
    let cancelled = false;
    loadApplications()
      .then(({ result, nextCapabilities }) => {
        if (cancelled) return;
        setData(result);
        setCapabilities(nextCapabilities);
        setError("");
      })
      .catch((reason: Error) => {
        if (!cancelled) setError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [loadApplications]);

  const openApplication = useCallback(async (applicationId: string, applicationCapabilities = capabilities) => {
    setSelectedId(applicationId);
    setDetailLoading(true);
    setDetailError("");
    setMessage("");
    try {
      const manageable = applicationCapabilities.includes("admissions.application.manage")
        && applicationCapabilities.includes("guardian.read");
      const [detailResponse, optionsResponse] = await Promise.all([
        fetch(`${apiUrl}/api/v1/admissions/applications/${applicationId}`, { credentials: "include" }),
        manageable
          ? fetch(`${apiUrl}/api/v1/admissions/applications/${applicationId}/options`, { credentials: "include" })
          : Promise.resolve(null),
      ]);
      if (!detailResponse.ok) throw new Error(await problemMessage(detailResponse, "The application could not be opened."));
      if (optionsResponse && !optionsResponse.ok) throw new Error(await problemMessage(optionsResponse, "Admission settings could not be loaded. Reopen the application to retry."));
      const application = (await detailResponse.json()) as ApplicationDetail;
      setDetail(application);
      setEditingType(application.admissionType);
      setEditingSession(application.targetAcademicSessionId || "");
      setOptions(optionsResponse?.ok ? (await optionsResponse.json()) as Options : null);
    } catch (reason) {
      setDetail(null);
      setOptions(null);
      setDetailError(reason instanceof Error ? reason.message : "The application could not be opened.");
    } finally {
      setDetailLoading(false);
    }
  }, [capabilities]);

  useEffect(() => {
    if (!initialApplicationId || capabilities.length === 0 || detail || detailLoading) return;
    const timer = window.setTimeout(() => void openApplication(initialApplicationId, capabilities), 0);
    return () => window.clearTimeout(timer);
  }, [capabilities, detail, detailLoading, initialApplicationId, openApplication]);

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

  async function mutate(path: string, init: RequestInit, success: string) {
    setSaving(true);
    setDetailError("");
    setMessage("");
    try {
      const response = await securedRequest(path, init);
      if (!response.ok) throw new Error(await problemMessage(response, "The application could not be updated."));
      const body = (await response.json()) as ApplicationDetail | { application: ApplicationDetail };
      const updated = "application" in body ? body.application : body;
      setDetail(updated);
      setMessage(success);
      const refreshed = await loadApplications();
      setData(refreshed.result);
      setCapabilities(refreshed.nextCapabilities);
      if (canManage && capabilities.includes("guardian.read")) {
        const optionResponse = await fetch(`${apiUrl}/api/v1/admissions/applications/${updated.id}/options`, { credentials: "include" });
        if (optionResponse.ok) setOptions((await optionResponse.json()) as Options);
      }
    } catch (reason) {
      setDetailError(reason instanceof Error ? reason.message : "The application could not be updated.");
    } finally {
      setSaving(false);
    }
  }

  function saveCore(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    void mutate(`/api/v1/admissions/applications/${detail.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        targetCampusId: String(form.get("targetCampusId") ?? "") || null,
        targetAcademicSessionId: String(form.get("targetAcademicSessionId") ?? "") || null,
        intakeSessionLabel: String(form.get("intakeSessionLabel")),
        targetGradeCode: editingType === "RTE" ? "I" : String(form.get("targetGradeCode")),
        admissionType: editingType,
        rteReference: editingType === "RTE" ? String(form.get("rteReference") || "") || null : null,
        applicantLegalName: String(form.get("applicantLegalName")),
        applicantPreferredName: String(form.get("applicantPreferredName") ?? "") || null,
        birthDate: String(form.get("birthDate")),
        gender: String(form.get("gender")),
        genderDescription: String(form.get("genderDescription") ?? "") || null,
        assignedMembershipId: String(form.get("assignedMembershipId") ?? "") || null,
        expectedVersion: detail.version,
      }),
    }, "Applicant details updated with an audit record.");
  }

  function changeStatus(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    void mutate(`/api/v1/admissions/applications/${detail.id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status: String(form.get("status")), expectedVersion: detail.version }),
    }, "Application stage updated safely.");
  }

  function addGuardian(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    void mutate(`/api/v1/admissions/applications/${detail.id}/guardians`, {
      method: "POST",
      body: JSON.stringify({
        guardian: {
          displayName: String(form.get("displayName")),
          relationshipCode: String(form.get("relationshipCode")),
          email: String(form.get("email") ?? "") || null,
          phone: String(form.get("phone") ?? "") || null,
          primaryContact: form.get("primaryContact") === "on",
          pickupAuthorized: form.get("pickupAuthorized") === "on",
        },
        expectedVersion: detail.version,
      }),
    }, "Guardian contact added to the application.");
  }

  function updateGuardian(event: FormEvent<HTMLFormElement>, guardian: ApplicationGuardian) {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    void mutate(`/api/v1/admissions/applications/${detail.id}/guardians/${guardian.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        guardian: {
          displayName: String(form.get("displayName")),
          relationshipCode: String(form.get("relationshipCode")),
          email: String(form.get("email") ?? "") || null,
          phone: String(form.get("phone") ?? "") || null,
          primaryContact: guardian.primaryContact || form.get("primaryContact") === "on",
          pickupAuthorized: form.get("pickupAuthorized") === "on",
        },
        expectedVersion: guardian.version,
      }),
    }, "Guardian contact corrected with an audit record.");
  }

  function addDocument(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    void mutate(`/api/v1/admissions/applications/${detail.id}/documents`, {
      method: "POST",
      body: JSON.stringify({
        documentCode: String(form.get("documentCode")).trim().toUpperCase().replace(/[^A-Z0-9]+/g, "_"),
        displayName: String(form.get("displayName")),
        required: form.get("required") === "on",
      }),
    }, "Document checklist updated.");
  }

  function reviewDocument(event: FormEvent<HTMLFormElement>, document: ApplicationDocument) {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    void mutate(`/api/v1/admissions/applications/${detail.id}/documents/${document.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        status: String(form.get("status")),
        receivedMethod: String(form.get("receivedMethod") ?? "") || null,
        reviewNote: String(form.get("reviewNote") ?? "") || null,
        expectedVersion: document.version,
      }),
    }, "Document review recorded.");
  }

  function recordDecision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    const decision = String(form.get("decision"));
    void mutate(`/api/v1/admissions/applications/${detail.id}/decisions`, {
      method: "POST",
      body: JSON.stringify({
        decision,
        reasonCode: String(form.get("reasonCode")),
        internalSummary: String(form.get("internalSummary") ?? "") || null,
        offerExpiresOn: decision === "OFFER" ? String(form.get("offerExpiresOn")) || null : null,
        expectedVersion: detail.version,
      }),
    }, "Decision appended to the immutable decision history.");
  }

  function enrol(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    const guardianMatches = detail.guardians.map((guardian) => ({
      applicationGuardianId: guardian.id,
      existingGuardianId: String(form.get(`guardian-${guardian.id}`) ?? "") || null,
    }));
    void mutate(`/api/v1/admissions/applications/${detail.id}/enrolment-handoffs`, {
      method: "POST",
      body: JSON.stringify({
        campusId: String(form.get("campusId")),
        academicSessionId: String(form.get("academicSessionId")),
        startsOn: String(form.get("startsOn")),
        admissionNumber: String(form.get("admissionNumber") ?? "") || null,
        guardianMatches,
        expectedVersion: detail.version,
      }),
    }, "Learner and guardian records created without duplicate data entry.");
  }

  function applySearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setPage(0);
    setQuery(queryDraft.trim());
  }

  const totalPages = Math.max(1, Math.ceil((data?.total ?? 0) / pageSize));
  const editable = detail && ["DRAFT", "SUBMITTED", "UNDER_REVIEW", "WAITLISTED", "OFFERED"].includes(detail.status);
  const decisionReady = detail && ["SUBMITTED", "UNDER_REVIEW", "WAITLISTED", "OFFERED", "ACCEPTED", "REJECTED"].includes(detail.status);

  return (
    <section className="applications-workspace">
      <header className="module-page__hero applications-hero">
        <div>
          <span>Admissions · Application pipeline</span>
          <h1>One careful journey from applicant to learner.</h1>
          <p>Review identity and guardians, track documents, record controlled decisions, and hand accepted applications into learner records without retyping them.</p>
        </div>
        <Link className="applications-back" href="/workspace/admissions">Enquiry desk <ArrowRight /></Link>
      </header>

      {error && <div className="admissions-notice admissions-notice--error" role="alert">{error}</div>}
      {message && <div className="admissions-notice admissions-notice--success admissions-notice--motion" role="status"><MotionIllustration className="admissions-success-motion" src="/animations/successful-target.lottie" loop={false} /><span><strong>Workflow secured</strong>{message}</span></div>}

      <div className="application-metrics" aria-label="Application summary">
        {([
          ["Draft", data?.metrics.draftCount ?? 0, "coral"],
          ["In review", data?.metrics.reviewCount ?? 0, "mint"],
          ["Offers", data?.metrics.offeredCount ?? 0, "yellow"],
          ["Accepted", data?.metrics.acceptedCount ?? 0, "lavender"],
        ] as const).map(([label, value, tone]) => <article className={`admissions-metric admissions-metric--${tone}`} key={label}><span>{label}</span><strong>{loading ? "—" : value}</strong><small>applications</small></article>)}
      </div>

      <form className="admissions-filters application-filters" onSubmit={applySearch}>
        <label className="admissions-search"><span className="sr-only">Search applications</span><input value={queryDraft} onChange={(event) => setQueryDraft(event.target.value)} maxLength={100} placeholder="Search applicant, class, or application ID" /><button type="submit">Search</button></label>
        <label><span>Status</span><select value={status} onChange={(event) => { setLoading(true); setStatus(event.target.value); setPage(0); }}><option value="">All statuses</option>{Object.entries(statusLabels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></label>
        <label><span>Admission type</span><select value={admissionTypeFilter} onChange={(event) => { setLoading(true); setAdmissionTypeFilter(event.target.value); setPage(0); }}><option value="">All admission types</option><option value="REGULAR">Regular</option><option value="RTE">RTE</option></select></label>
        {(status || query || admissionTypeFilter) && <button className="admissions-clear" type="button" onClick={() => { setLoading(true); setStatus(""); setAdmissionTypeFilter(""); setQuery(""); setQueryDraft(""); setPage(0); }}>Clear filters</button>}
      </form>

      <div className="applications-board">
        <section className="application-list" aria-label="Admission applications" aria-busy={loading}>
          <header><div><span>Applicant records</span><strong>{data?.total ?? 0} applications</strong></div><small>Recently updated</small></header>
          {loading ? <div className="enquiry-list__loading"><Clock /> Loading school-scoped applications…</div> : data?.items.length ? (
            <div className="application-list__items">
              {data.items.map((item) => <button className={`application-row${selectedId === item.id ? " application-row--selected" : ""}`} type="button" key={item.id} onClick={() => void openApplication(item.id)} aria-pressed={selectedId === item.id}>
                <span className={`application-status application-status--${item.status.toLowerCase()}`}>{statusLabels[item.status]}</span>
                <span className="application-row__person"><strong>{item.applicantPreferredName || item.applicantLegalName}{item.admissionType === "RTE" && <small> · RTE</small>}</strong><small>{item.reference} · {item.targetGradeCode} · {item.intakeSessionLabel}</small></span>
                <span className="application-row__documents"><small>Documents</small><strong>{item.verifiedDocumentCount}/{item.requiredDocumentCount}</strong></span>
                <span className="application-row__owner"><small>Owner</small><strong>{item.assignee?.displayName || "Unassigned"}</strong></span>
                <ArrowRight />
              </button>)}
            </div>
          ) : <div className="enquiry-list__empty"><User /><h2>No applications in this view</h2><p>Qualify a family enquiry and choose “Start admission application,” or clear the current filters.</p></div>}
          <footer><button type="button" disabled={page === 0 || loading} onClick={() => { setLoading(true); setPage((value) => Math.max(0, value - 1)); }}>Previous</button><span>Page {page + 1} of {totalPages}</span><button type="button" disabled={page + 1 >= totalPages || loading} onClick={() => { setLoading(true); setPage((value) => value + 1); }}>Next</button></footer>
        </section>

        <aside className="application-detail" aria-label="Selected admission application">
          {detailLoading ? <div className="enquiry-detail__placeholder"><Clock /><h2>Opening application…</h2><p>Checking your permission in the application&apos;s school.</p></div> : detail ? (
            <div className="application-detail__content">
              <header className="application-detail__header"><div><span>{detail.reference}</span><h2>{detail.applicantPreferredName || detail.applicantLegalName}</h2><p>{detail.targetGradeCode} · {detail.intakeSessionLabel}{detail.enquiryReference ? ` · ${detail.enquiryReference}` : ""}</p></div><em className={`application-status application-status--${detail.status.toLowerCase()}`}>{statusLabels[detail.status]}</em></header>
              {detailError && <div className="admissions-notice admissions-notice--error" role="alert">{detailError}</div>}

              <div className="application-facts">
                <div><small>Admission type</small><strong>{detail.admissionType === "RTE" ? "RTE" : "Regular"}</strong>{detail.admissionType === "RTE" && <small>{detail.targetAcademicSessionName}{detail.rteReference ? ` · Reference: ${detail.rteReference}` : ""}</small>}</div>
                <div><small>Legal name</small><strong>{detail.applicantLegalName}</strong></div>
                <div><small>Date of birth</small><strong>{dateLabel(detail.birthDate)}</strong></div>
                <div><small>Gender</small><strong>{genderLabel(detail.gender, detail.genderDescription)}</strong></div>
                <div><small>Target</small><strong>{detail.targetCampusName || "Campus not selected"} · {detail.targetAcademicSessionName || "Session not selected"}</strong></div>
              </div>

              <section className="application-section">
                <header><div><span>Guardian contacts</span><h3>Family relationship</h3></div><small>{detail.guardians.length} contact{detail.guardians.length === 1 ? "" : "s"}</small></header>
                <div className="application-guardians">{detail.guardians.map((guardian) => <article key={`${guardian.id}-${guardian.version}`}><div className="guardian-card__summary"><span><User /></span><div><strong>{guardian.displayName}</strong><small>{guardian.relationshipCode.replaceAll("_", " ")}{guardian.primaryContact ? " · Primary" : ""}{guardian.pickupAuthorized ? " · Pickup authorized" : ""}</small><p>{guardian.email || "No email"}{guardian.phone ? ` · ${guardian.phone}` : ""}</p></div>{guardian.matchedGuardianId && <em>Matched</em>}</div>{canManage && detail.status !== "ENROLLED" && <details className="guardian-card__editor"><summary>Correct contact details <ArrowRight /></summary><form onSubmit={(event) => updateGuardian(event, guardian)}><div className="guardian-editor__grid"><label><span>Full name</span><input name="displayName" defaultValue={guardian.displayName} maxLength={120} required /></label><label><span>Relationship</span><input name="relationshipCode" defaultValue={guardian.relationshipCode.replaceAll("_", " ")} maxLength={40} required /></label><label><span>Email</span><input name="email" type="email" defaultValue={guardian.email || ""} maxLength={254} /></label><label><span>Phone</span><input name="phone" type="tel" defaultValue={guardian.phone || ""} maxLength={30} /></label></div><div className="guardian-editor__checks">{guardian.primaryContact ? <label><input type="checkbox" checked disabled /><span>Primary contact</span></label> : <label><input name="primaryContact" type="checkbox" /><span>Make primary contact</span></label>}<label><input name="pickupAuthorized" type="checkbox" defaultChecked={guardian.pickupAuthorized} /><span>Pickup authorized</span></label></div><button type="submit" disabled={saving}>Save contact</button></form></details>}</article>)}</div>
                {canManage && detail.status !== "ENROLLED" && detail.guardians.length < 10 && <details className="guardian-add"><summary>Add another guardian <ArrowRight /></summary><form onSubmit={addGuardian}><div className="guardian-editor__grid"><label><span>Full name</span><input name="displayName" maxLength={120} required /></label><label><span>Relationship</span><input name="relationshipCode" maxLength={40} placeholder="Parent, grandparent, guardian…" required /></label><label><span>Email</span><input name="email" type="email" maxLength={254} /></label><label><span>Phone</span><input name="phone" type="tel" maxLength={30} /></label></div><div className="guardian-editor__checks"><label><input name="primaryContact" type="checkbox" /><span>Make primary contact</span></label><label><input name="pickupAuthorized" type="checkbox" /><span>Pickup authorized</span></label></div><small>At least an email or phone number is required.</small><button type="submit" disabled={saving}>Add guardian</button></form></details>}
              </section>

              {canManage && editable && (
                <details className="application-editor">
                  <summary><span><strong>Edit applicant and target</strong><small>Protected by optimistic version checks.</small></span><ArrowRight /></summary>
                  <form key={`${detail.id}-${detail.version}`} onSubmit={saveCore}>
                    <div className="application-editor__grid">
                      <label><span>Legal name</span><input name="applicantLegalName" defaultValue={detail.applicantLegalName} maxLength={120} required /></label>
                      <label><span>Preferred name</span><input name="applicantPreferredName" defaultValue={detail.applicantPreferredName || ""} maxLength={120} /></label>
                      <label><span>Date of birth</span><input name="birthDate" type="date" defaultValue={detail.birthDate} max={new Date().toISOString().slice(0, 10)} required /></label>
                      <label><span>Gender</span><select name="gender" defaultValue={detail.gender}><option value="NOT_STATED">Not stated</option><option value="FEMALE">Female</option><option value="MALE">Male</option><option value="NON_BINARY">Non-binary</option><option value="SELF_DESCRIBED">Self-described</option></select></label>
                      <label><span>Self-described value</span><input name="genderDescription" defaultValue={detail.genderDescription || ""} maxLength={80} /></label>
                      <label><span>Admission type</span><select name="admissionType" value={editingType} onChange={(event) => setEditingType(event.target.value)}><option value="REGULAR">Regular</option><option value="RTE">RTE</option></select></label>
                      <label><span>Class</span><select name="targetGradeCode" key={editingType} defaultValue={editingType === "RTE" ? "I" : detail.targetGradeCode}>{(editingType === "RTE" ? ["I"] : grades).map((grade) => <option value={grade} key={grade}>{grade === "NURSERY" ? "Nursery" : `Class ${grade}`}</option>)}</select></label>
                      <label><span>Intake label</span><input name="intakeSessionLabel" defaultValue={detail.intakeSessionLabel} maxLength={80} required /></label>
                      <label><span>Campus</span><select name="targetCampusId" defaultValue={detail.targetCampusId || ""}><option value="">Not selected</option>{options?.campuses.map((campus) => <option value={campus.id} key={campus.id}>{campus.displayName}</option>)}</select></label>
                      <label><span>Academic session</span><select name="targetAcademicSessionId" value={editingSession} required={editingType === "RTE"} onChange={(event) => setEditingSession(event.target.value)}><option value="">Not selected</option>{options?.academicSessions.map((session) => <option value={session.id} key={session.id}>{session.name}</option>)}</select></label>
                      {editingType === "RTE" && <label><span>RTE Reference / Allotment No. (optional)</span><input name="rteReference" defaultValue={detail.rteReference || ""} maxLength={100} /></label>}
                      <label><span>Owner</span><select name="assignedMembershipId" defaultValue={detail.assignee?.membershipId || ""}><option value="">Unassigned</option>{options?.assignees.map((assignee) => <option value={assignee.membershipId} key={assignee.membershipId}>{assignee.displayName}</option>)}</select></label>
                    </div>
                    {editingType === "RTE" && <div className="application-config-block" role="status"><div><strong>RTE admission · Class I</strong><p>Admission and Tuition fees are automatically exempt through Class VIII. Other charges remain payable under the school policy.</p>{options?.academicSessions.filter((session) => session.id === editingSession).map((session) => <p key={session.id}>RTE seats used: {session.rteSeatsUsed} / {session.rteSeatLimit} · Age {session.rteMinAgeYears}–{session.rteMaxAgeYears} at session start. Seats are confirmed at enrolment.</p>)}</div></div>}
                    <button className="button button--coral" type="submit" disabled={saving}>Save applicant details <ArrowRight /></button>
                  </form>
                </details>
              )}

              {canManage && (statusTransitions[detail.status]?.length || 0) > 0 && <form className="application-stage" onSubmit={changeStatus}><label><span>Next controlled stage</span><select name="status">{statusTransitions[detail.status]?.map((next) => <option value={next} key={next}>{statusLabels[next]}</option>)}</select></label><button type="submit" disabled={saving}>Move application <ArrowRight /></button></form>}

              <section className="application-section application-documents">
                <header><div><span>Document checklist</span><h3>Evidence review</h3></div><small>Metadata only · files stay private</small></header>
                {detail.documents.length ? <div className="application-document-list">{detail.documents.map((document) => <form key={`${document.id}-${document.version}`} onSubmit={(event) => reviewDocument(event, document)}><div><span className={`document-state document-state--${document.status.toLowerCase()}`}>{document.status.replaceAll("_", " ")}</span><strong>{document.displayName}</strong><small>{document.required ? "Required" : "Optional"} · {document.documentCode}</small>{document.reviewNote && <p>{document.reviewNote}</p>}</div>{canManage && detail.status !== "ENROLLED" ? <div className="document-review-controls"><select name="status" defaultValue={document.status}><option value="MISSING">Missing</option><option value="RECEIVED">Received</option><option value="VERIFIED">Verified</option><option value="REJECTED">Rejected</option><option value="WAIVED">Waived</option></select><select name="receivedMethod" defaultValue={document.receivedMethod || ""}><option value="">No received method</option><option value="OFFLINE_ORIGINAL">Original seen offline</option><option value="OFFLINE_COPY">Copy received offline</option></select><input name="reviewNote" maxLength={500} defaultValue={document.reviewNote || ""} placeholder="Reason or review note" /><button type="submit" disabled={saving}>Record</button></div> : <small>{document.verifiedByName ? `Verified by ${document.verifiedByName}` : dateLabel(document.receivedAt, true)}</small>}</form>)}</div> : <div className="application-empty"><Message /><p>No checklist items yet. Add only requirements confirmed by the school.</p></div>}
                {canManage && detail.status !== "ENROLLED" && <form className="document-add" onSubmit={addDocument}><input name="displayName" maxLength={120} placeholder="Document display name" required /><input name="documentCode" pattern="[A-Za-z][A-Za-z0-9 _-]{1,39}" maxLength={40} placeholder="Internal code" required /><label><input name="required" type="checkbox" /><span>Required</span></label><button type="submit" disabled={saving}>Add to checklist</button></form>}
              </section>

              {canDecide && decisionReady && detail.status !== "ENROLLED" && <section className="application-section application-decision"><header><div><span>Controlled decision</span><h3>Append a decision</h3></div><small>Previous decisions are never rewritten</small></header><form onSubmit={recordDecision}><div className="application-decision__grid"><label><span>Decision</span><select name="decision" defaultValue="OFFER"><option value="OFFER">Offer admission</option><option value="WAITLIST">Waitlist</option><option value="REJECT">Reject</option></select></label><label><span>Reason code</span><input name="reasonCode" maxLength={60} placeholder="SCHOOL_APPROVED_REASON" required /></label><label><span>Offer expires</span><input name="offerExpiresOn" type="date" /></label><label className="application-decision__wide"><span>Internal summary</span><textarea name="internalSummary" maxLength={1000} rows={3} placeholder="Keep this factual, necessary, and respectful." /></label></div><button className="button button--coral" type="submit" disabled={saving}>Record immutable decision <ArrowRight /></button></form></section>}

              {detail.decisions.length > 0 && <section className="application-section"><header><div><span>Decision history</span><h3>Append-only record</h3></div><small>{detail.decisions.length} decision{detail.decisions.length === 1 ? "" : "s"}</small></header><div className="decision-history">{detail.decisions.map((decision) => <article key={decision.id}><i /><div><strong>{decision.decision}</strong><small>{decision.reasonCode} · {decision.decidedByName} · {dateLabel(decision.createdAt, true)}</small>{decision.internalSummary && <p>{decision.internalSummary}</p>}{decision.offerExpiresOn && <em>Offer valid through {dateLabel(decision.offerExpiresOn)}</em>}</div></article>)}</div></section>}

{detail.status === "ACCEPTED" && canEnrol && <section className="application-section application-handoff"><header><div><span>Enrolment handoff</span><h3>Create learner records once</h3></div><small>Placement remains pending</small></header>{options?.campuses.length && options.academicSessions.length ? <form onSubmit={enrol}><div className="application-handoff__grid"><label><span>Campus</span><select name="campusId" required defaultValue={detail.targetCampusId || ""}><option value="" disabled>Choose campus</option>{options.campuses.map((campus) => <option value={campus.id} key={campus.id}>{campus.displayName}</option>)}</select></label><label><span>Academic session</span><select name="academicSessionId" required defaultValue={detail.targetAcademicSessionId || ""}><option value="" disabled>Choose session</option>{admissionHandoffSessions(detail.admissionType, detail.targetAcademicSessionId, options.academicSessions).map((session) => <option value={session.id} key={session.id}>{session.name}</option>)}</select></label><label><span>Start date</span><input name="startsOn" type="date" required /></label><label><span>Official admission number</span><input name="admissionNumber" maxLength={80} placeholder="Optional until numbering is configured" /></label></div><div className="guardian-match-list">{detail.guardians.map((guardian) => { const match = options.guardianMatches.find((entry) => entry.applicationGuardianId === guardian.id); return <label key={guardian.id}><span><strong>{guardian.displayName}</strong><small>Match an existing guardian only when the contact is clearly the same person.</small></span><select name={`guardian-${guardian.id}`} defaultValue={guardian.matchedGuardianId || ""}><option value="">Create a new guardian record</option>{match?.candidates.map((candidate) => <option value={candidate.guardianId} key={candidate.guardianId}>{candidate.displayName} · {candidate.maskedEmail || candidate.maskedPhone}</option>)}</select></label>; })}</div><button className="button button--coral" type="submit" disabled={saving}>Create learner and enrolment <ArrowRight /></button></form> : <div className="application-config-block"><Clock /><div><strong>School setup is required</strong><p>Configure at least one active campus and planned or active academic session before enrolment handoff.</p></div></div>}</section>}

              {detail.enrolment && <section className="application-enrolled"><Check /><div><span>Enrolment handoff complete</span><h3>{detail.enrolment.learnerReference}</h3><p>{detail.enrolment.enrolmentReference} · {detail.enrolment.campusName} · {detail.enrolment.academicSessionName} · {detail.enrolment.gradeCode}</p><small>{detail.enrolment.admissionNumber ? `Admission number ${detail.enrolment.admissionNumber}` : "Official admission number pending"} · Placement {detail.enrolment.placementStatus.toLowerCase()}</small></div></section>}
            </div>
          ) : <div className="enquiry-detail__placeholder"><span><User /></span><h2>Select an application</h2><p>Applicant identity, guardian contacts, documents, and decisions appear only after the server confirms same-school access.</p>{detailError && <div className="admissions-notice admissions-notice--error" role="alert">{detailError}</div>}</div>}
        </aside>
      </div>
    </section>
  );
}
