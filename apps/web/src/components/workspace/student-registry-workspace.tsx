"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Calendar, Check, Clock, Shield, User } from "@/components/icons";
import { MotionIllustration } from "@/components/motion-illustration";
import { StudentPortalAccessPanel } from "@/components/workspace/student-portal-access-panel";
import {
  AcademicIcon,
  PeopleIcon,
  PlusIcon,
  SearchIcon,
} from "@/components/workspace/workspace-icons";

type Student = {
  admissionType: "REGULAR" | "RTE";
  rteAdmissionSessionId: string | null;
  rteAdmissionSessionName: string | null;
  rteReference: string | null;
  learnerId: string;
  learnerReference: string;
  legalName: string;
  preferredName: string | null;
  birthDate: string;
  learnerStatus: string;
  enrolmentId: string;
  enrolmentReference: string;
  admissionNumber: string | null;
  campusId: string;
  campusName: string;
  academicSessionId: string;
  academicSessionName: string;
  gradeCode: string;
  placementStatus: string;
  sectionId: string | null;
  sectionCode: string | null;
  sectionName: string | null;
  rollNumber: string | null;
  guardianCount: number;
  portalReadyGuardianCount: number;
  learnerVersion: number;
  enrolmentVersion: number;
};

type StudentPage = {
  items: Student[];
  page: number;
  size: number;
  total: number;
  metrics: {
    activeCount: number;
    pendingPlacementCount: number;
    placedCount: number;
    portalReadyCount: number;
  };
};

type Guardian = {
  guardianId: string;
  displayName: string;
  relationshipCode: string;
  email: string | null;
  phone: string | null;
  primaryContact: boolean;
  pickupAuthorized: boolean;
  portalAccessStatus: string;
  portalReadiness: string;
  linkedLearnerCount: number;
  guardianVersion: number;
  relationshipVersion: number;
};

type PortalInvitation = {
  id: string;
  learnerId: string;
  guardianId: string;
  maskedDestination: string;
  status: string;
  deliveryStatus: string;
  expiresAt: string;
  acceptedAt: string | null;
  revokedAt: string | null;
  createdAt: string;
  version: number;
};

type Placement = {
  placementId: string;
  sectionId: string;
  sectionCode: string;
  sectionName: string;
  rollNumber: string | null;
  startsOn: string;
  endsOn: string | null;
  status: string;
  placementReason: string;
  endReason: string | null;
  version: number;
};

type StudentDetail = {
  student: Student;
  genderCode: string;
  genderDescription: string | null;
  guardians: Guardian[];
  placementHistory: Placement[];
};

type SectionOption = {
  id: string;
  code: string;
  displayName: string;
  capacity: number | null;
  activeStudentCount: number;
  version: number;
};

type ApiProblem = { detail?: string };
type ProfileForm = {
  legalName: string;
  preferredName: string;
  birthDate: string;
  genderCode: string;
  genderDescription: string;
  correctionReason: string;
};
type GuardianForm = {
  guardianId: string;
  displayName: string;
  email: string;
  phone: string;
  relationshipCode: string;
  primaryContact: boolean;
  pickupAuthorized: boolean;
  correctionReason: string;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const pageSize = 25;

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

function dateLabel(value: string | null) {
  if (!value) return "Current";
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(`${value}T00:00:00`));
}

function dateTimeLabel(value: string) {
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value));
}

function effectiveInvitationStatus(invitation: PortalInvitation) {
  if (invitation.status === "ISSUED" && new Date(invitation.expiresAt).getTime() <= Date.now()) {
    return "EXPIRED";
  }
  return invitation.status;
}

function displayCode(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function profileForm(detail: StudentDetail): ProfileForm {
  return {
    legalName: detail.student.legalName,
    preferredName: detail.student.preferredName ?? "",
    birthDate: detail.student.birthDate,
    genderCode: detail.genderCode,
    genderDescription: detail.genderDescription ?? "",
    correctionReason: "",
  };
}

function guardianForm(guardian: Guardian): GuardianForm {
  return {
    guardianId: guardian.guardianId,
    displayName: guardian.displayName,
    email: guardian.email ?? "",
    phone: guardian.phone ?? "",
    relationshipCode: guardian.relationshipCode,
    primaryContact: guardian.primaryContact,
    pickupAuthorized: guardian.pickupAuthorized,
    correctionReason: "",
  };
}

export function StudentRegistryWorkspace({ initialStudentId }: { initialStudentId: string }) {
  const initialOpenAttempted = useRef(false);
  const [data, setData] = useState<StudentPage | null>(null);
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [placementStatus, setPlacementStatus] = useState("");
  const [admissionType, setAdmissionType] = useState("");
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState(initialStudentId);
  const [detail, setDetail] = useState<StudentDetail | null>(null);
  const [sections, setSections] = useState<SectionOption[]>([]);
  const [capabilities, setCapabilities] = useState<string[]>([]);
  const [profile, setProfile] = useState<ProfileForm | null>(null);
  const [guardian, setGuardian] = useState<GuardianForm | null>(null);
  const [portalGuardianId, setPortalGuardianId] = useState("");
  const [portalReason, setPortalReason] = useState("");
  const [portalInvitations, setPortalInvitations] = useState<PortalInvitation[]>([]);
  const [portalLoading, setPortalLoading] = useState(false);
  const [placement, setPlacement] = useState({
    sectionId: "",
    rollNumber: "",
    startsOn: "",
    changeReason: "",
  });
  const [section, setSection] = useState({ code: "", displayName: "", capacity: "" });
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [detailError, setDetailError] = useState("");
  const [message, setMessage] = useState("");

  const canReadGuardians = capabilities.includes("guardian.read");
  const canManageStudents = capabilities.includes("student.manage");
  const canManageGuardians = capabilities.includes("guardian.manage");
  const canInviteGuardians = canManageGuardians && capabilities.includes("guardian.portal.invite");
  const canManageStudentPortal = capabilities.includes("student.portal.manage");
  const canConfigureAcademics = capabilities.includes("academics.configure");
  const canImportStudents = capabilities.includes("student.import");

  const loadStudents = useCallback(async () => {
    const parameters = new URLSearchParams({ page: String(page), size: String(pageSize) });
    if (query) parameters.set("query", query);
    if (placementStatus) parameters.set("placementStatus", placementStatus);
    if (admissionType) parameters.set("admissionType", admissionType);
    const [studentsResponse, contextResponse] = await Promise.all([
      fetch(`${apiUrl}/api/v1/students?${parameters}`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/context`, { credentials: "include" }),
    ]);
    if (!studentsResponse.ok)
      throw new Error(await problemMessage(studentsResponse, "Student records are unavailable."));
    const result = (await studentsResponse.json()) as StudentPage;
    const context = contextResponse.ok
      ? ((await contextResponse.json()) as { capabilities: string[] })
      : { capabilities: [] };
    return { result, context: context.capabilities };
  }, [page, placementStatus, query, admissionType]);

  useEffect(() => {
    let cancelled = false;
    loadStudents()
      .then(({ result, context }) => {
        if (cancelled) return;
        setData(result);
        setCapabilities(context);
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
  }, [loadStudents]);

  const openStudent = useCallback(
    async (learnerId: string) => {
      setSelectedId(learnerId);
      setDetailLoading(true);
      setDetailError("");
      setMessage("");
      setGuardian(null);
      setPortalGuardianId("");
      setPortalReason("");
      setPortalInvitations([]);
      try {
        const detailResponse = await fetch(`${apiUrl}/api/v1/students/${learnerId}`, {
          credentials: "include",
        });
        if (!detailResponse.ok)
          throw new Error(
            await problemMessage(detailResponse, "The student record could not be opened."),
          );
        const nextDetail = (await detailResponse.json()) as StudentDetail;
        setDetail(nextDetail);
        setProfile(profileForm(nextDetail));
        setPlacement({
          sectionId: nextDetail.student.sectionId ?? "",
          rollNumber: nextDetail.student.rollNumber ?? "",
          startsOn: new Date().toISOString().slice(0, 10),
          changeReason: "",
        });
        if (canManageStudents) {
          const optionsResponse = await fetch(`${apiUrl}/api/v1/students/${learnerId}/options`, {
            credentials: "include",
          });
          setSections(
            optionsResponse.ok
              ? ((await optionsResponse.json()) as { sections: SectionOption[] }).sections
              : [],
          );
        } else setSections([]);
      } catch (reason) {
        setDetail(null);
        setProfile(null);
        setDetailError(
          reason instanceof Error ? reason.message : "The student record could not be opened.",
        );
      } finally {
        setDetailLoading(false);
      }
    },
    [canManageStudents],
  );

  useEffect(() => {
    if (initialStudentId && canReadGuardians && !initialOpenAttempted.current) {
      initialOpenAttempted.current = true;
      void Promise.resolve().then(() => openStudent(initialStudentId));
    }
  }, [canReadGuardians, initialStudentId, openStudent]);

  async function securedRequest(path: string, init: RequestInit) {
    let csrf = cookie("educore_csrf");
    if (!csrf) {
      await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
      csrf = cookie("educore_csrf");
    }
    return fetch(`${apiUrl}${path}`, {
      ...init,
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": csrf,
        ...(init.headers ?? {}),
      },
    });
  }

  async function refresh(savedMessage: string) {
    if (!selectedId) return;
    await openStudent(selectedId);
    const { result } = await loadStudents();
    setData(result);
    setMessage(savedMessage);
  }

  async function saveProfile(event: FormEvent) {
    event.preventDefault();
    if (!detail || !profile) return;
    setSaving(true);
    setDetailError("");
    try {
      const response = await securedRequest(`/api/v1/students/${detail.student.learnerId}`, {
        method: "PATCH",
        body: JSON.stringify({
          ...profile,
          preferredName: profile.preferredName || null,
          genderDescription:
            profile.genderCode === "SELF_DESCRIBED" ? profile.genderDescription : null,
          expectedVersion: detail.student.learnerVersion,
        }),
      });
      if (!response.ok)
        throw new Error(
          await problemMessage(response, "The profile correction could not be saved."),
        );
      await refresh("Learner profile correction saved with an audit trail.");
    } catch (reason) {
      setDetailError(
        reason instanceof Error ? reason.message : "The profile correction could not be saved.",
      );
    } finally {
      setSaving(false);
    }
  }

  async function saveGuardian(event: FormEvent) {
    event.preventDefault();
    if (!detail || !guardian) return;
    const source = detail.guardians.find((item) => item.guardianId === guardian.guardianId);
    if (!source) return;
    setSaving(true);
    setDetailError("");
    try {
      const response = await securedRequest(
        `/api/v1/students/${detail.student.learnerId}/guardians/${guardian.guardianId}`,
        {
          method: "PATCH",
          body: JSON.stringify({
            ...guardian,
            email: guardian.email || null,
            phone: guardian.phone || null,
            expectedGuardianVersion: source.guardianVersion,
            expectedRelationshipVersion: source.relationshipVersion,
          }),
        },
      );
      if (!response.ok)
        throw new Error(
          await problemMessage(response, "The guardian correction could not be saved."),
        );
      await refresh("Guardian relationship and contact correction saved.");
      setGuardian(null);
    } catch (reason) {
      setDetailError(
        reason instanceof Error ? reason.message : "The guardian correction could not be saved.",
      );
    } finally {
      setSaving(false);
    }
  }

  async function loadPortalInvitations(learnerId: string, guardianId: string) {
    const response = await fetch(
      `${apiUrl}/api/v1/students/${learnerId}/guardians/${guardianId}/portal-invitations`,
      { credentials: "include" },
    );
    if (!response.ok) {
      throw new Error(await problemMessage(response, "Portal access history is unavailable."));
    }
    const result = (await response.json()) as { items: PortalInvitation[] };
    setPortalInvitations(result.items);
  }

  async function openPortalAccess(item: Guardian) {
    if (!detail) return;
    setGuardian(null);
    setPortalGuardianId(item.guardianId);
    setPortalReason("");
    setPortalLoading(true);
    setDetailError("");
    try {
      await loadPortalInvitations(detail.student.learnerId, item.guardianId);
    } catch (reason) {
      setPortalInvitations([]);
      setDetailError(
        reason instanceof Error ? reason.message : "Portal access history is unavailable.",
      );
    } finally {
      setPortalLoading(false);
    }
  }

  async function issuePortalInvitation(event: FormEvent) {
    event.preventDefault();
    if (!detail || !portalGuardianId) return;
    const source = detail.guardians.find((item) => item.guardianId === portalGuardianId);
    if (!source) return;
    setSaving(true);
    setDetailError("");
    try {
      const response = await securedRequest(
        `/api/v1/students/${detail.student.learnerId}/guardians/${source.guardianId}/portal-invitations`,
        {
          method: "POST",
          body: JSON.stringify({
            expectedRelationshipVersion: source.relationshipVersion,
            reason: portalReason,
          }),
        },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The invitation could not be created."));
      }
      const learnerId = detail.student.learnerId;
      const guardianId = source.guardianId;
      await refresh(
        "Portal invitation created securely. Delivery is waiting for a configured provider.",
      );
      setPortalGuardianId(guardianId);
      setPortalReason("");
      await loadPortalInvitations(learnerId, guardianId);
    } catch (reason) {
      setDetailError(
        reason instanceof Error ? reason.message : "The invitation could not be created.",
      );
    } finally {
      setSaving(false);
    }
  }

  async function revokePortalInvitation(invitation: PortalInvitation) {
    if (!detail || !portalGuardianId || !portalReason.trim()) return;
    setSaving(true);
    setDetailError("");
    try {
      const response = await securedRequest(
        `/api/v1/guardian-portal-invitations/${invitation.id}/revocations`,
        {
          method: "POST",
          body: JSON.stringify({ expectedVersion: invitation.version, reason: portalReason }),
        },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The invitation could not be revoked."));
      }
      const learnerId = detail.student.learnerId;
      const guardianId = portalGuardianId;
      await refresh("Portal invitation revoked. It can no longer be accepted.");
      setPortalGuardianId(guardianId);
      setPortalReason("");
      await loadPortalInvitations(learnerId, guardianId);
    } catch (reason) {
      setDetailError(
        reason instanceof Error ? reason.message : "The invitation could not be revoked.",
      );
    } finally {
      setSaving(false);
    }
  }

  async function savePlacement(event: FormEvent) {
    event.preventDefault();
    if (!detail) return;
    setSaving(true);
    setDetailError("");
    try {
      const response = await securedRequest(
        `/api/v1/students/${detail.student.learnerId}/placements`,
        {
          method: "POST",
          body: JSON.stringify({
            ...placement,
            rollNumber: placement.rollNumber || null,
            expectedEnrolmentVersion: detail.student.enrolmentVersion,
          }),
        },
      );
      if (!response.ok)
        throw new Error(
          await problemMessage(response, "The section placement could not be saved."),
        );
      await refresh(
        detail.student.sectionId
          ? "Section reassignment saved; the previous placement remains in history."
          : "Initial section placement saved.",
      );
    } catch (reason) {
      setDetailError(
        reason instanceof Error ? reason.message : "The section placement could not be saved.",
      );
    } finally {
      setSaving(false);
    }
  }

  async function createSection(event: FormEvent) {
    event.preventDefault();
    if (!detail) return;
    setSaving(true);
    setDetailError("");
    try {
      const response = await securedRequest("/api/v1/students/sections", {
        method: "POST",
        body: JSON.stringify({
          campusId: detail.student.campusId,
          academicSessionId: detail.student.academicSessionId,
          gradeCode: detail.student.gradeCode,
          code: section.code,
          displayName: section.displayName,
          capacity: section.capacity ? Number(section.capacity) : null,
        }),
      });
      if (!response.ok)
        throw new Error(await problemMessage(response, "The section could not be created."));
      const created = (await response.json()) as SectionOption;
      setSections((current) =>
        [...current, created].sort((a, b) => a.displayName.localeCompare(b.displayName)),
      );
      setPlacement((current) => ({ ...current, sectionId: created.id }));
      setSection({ code: "", displayName: "", capacity: "" });
      setMessage("Section created and selected for placement.");
    } catch (reason) {
      setDetailError(
        reason instanceof Error ? reason.message : "The section could not be created.",
      );
    } finally {
      setSaving(false);
    }
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setPage(0);
    setQuery(queryDraft.trim());
  }

  const items = data?.items ?? [];
  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <section className="student-workspace">
      <header className="student-hero">
        <div>
          <span>Student information system</span>
          <h1>Students &amp; guardians</h1>
          <p>
            One protected record for identity, family relationships, enrolment, section placement,
            and portal readiness.
          </p>
        </div>
        <div className="student-hero__aside">
          <div className="student-hero__trust">
            <Shield />
            <span>
              <strong>School-scoped</strong>
              <small>Versioned corrections · audited changes</small>
            </span>
          </div>
          {canImportStudents ? (
            <Link href="/workspace/students/imports">
              Open import centre <span aria-hidden="true">→</span>
            </Link>
          ) : null}
        </div>
      </header>

      <div className="student-metrics" aria-label="Student registry summary">
        <article>
          <span className="metric-icon metric-icon--mint">
            <PeopleIcon />
          </span>
          <div>
            <strong>{data?.metrics.activeCount ?? 0}</strong>
            <small>Active learners</small>
          </div>
        </article>
        <article>
          <span className="metric-icon metric-icon--yellow">
            <Clock />
          </span>
          <div>
            <strong>{data?.metrics.pendingPlacementCount ?? 0}</strong>
            <small>Awaiting section</small>
          </div>
        </article>
        <article>
          <span className="metric-icon metric-icon--lavender">
            <AcademicIcon />
          </span>
          <div>
            <strong>{data?.metrics.placedCount ?? 0}</strong>
            <small>Placed learners</small>
          </div>
        </article>
        <article>
          <span className="metric-icon metric-icon--coral">
            <User />
          </span>
          <div>
            <strong>{data?.metrics.portalReadyCount ?? 0}</strong>
            <small>Portal-ready families</small>
          </div>
        </article>
      </div>

      <div className="student-toolbar">
        <label><span>Admission type</span><select value={admissionType} onChange={(event) => { setLoading(true); setPage(0); setAdmissionType(event.target.value); }}><option value="">All admission types</option><option value="REGULAR">Regular</option><option value="RTE">RTE</option></select></label>
        <form onSubmit={submitSearch} role="search">
          <SearchIcon />
          <input
            aria-label="Search students"
            value={queryDraft}
            onChange={(event) => setQueryDraft(event.target.value)}
            placeholder="Name, admission number, grade, or section"
            maxLength={100}
          />
          <button type="submit">Search</button>
        </form>
        <label>
          <span>Placement</span>
          <select
            value={placementStatus}
            onChange={(event) => {
              setLoading(true);
              setPage(0);
              setPlacementStatus(event.target.value);
            }}
          >
            <option value="">All students</option>
            <option value="PENDING">Awaiting section</option>
            <option value="PLACED">Placed</option>
          </select>
        </label>
      </div>

      {error && (
        <div className="student-alert student-alert--error" role="alert">
          {error}
        </div>
      )}
      {message && (
        <div className="student-alert student-alert--success" role="status">
          <Check /> {message}
        </div>
      )}

      <div className={`student-registry${selectedId ? " student-registry--detail" : ""}`}>
        <section className="student-list" aria-label="Student records">
          <header>
            <div>
              <span>Registry</span>
              <h2>{loading ? "Loading students…" : `${data?.total ?? 0} student records`}</h2>
            </div>
            <small>Only records in your authorized schools are shown.</small>
          </header>
          {loading ? (
            <div className="student-list__loading">
              <i />
              <i />
              <i />
            </div>
          ) : null}
          {!loading && items.length === 0 ? (
            <div className="student-empty">
              <MotionIllustration
                src="/animations/study-discussion.lottie"
                className="student-empty__motion"
                label="Students studying together"
              />
              <h3>
                {query || placementStatus || admissionType
                  ? "No students match these filters"
                  : "No enrolled learners yet"}
              </h3>
              <p>
                {query || placementStatus || admissionType
                  ? "Try a broader search or remove the placement filter."
                  : "Learners appear here after an accepted admission application completes its enrolment handoff."}
              </p>
            </div>
          ) : null}
          <div className="student-list__rows">
            {items.map((student) => (
              <button
                className={
                  selectedId === student.learnerId
                    ? "student-row student-row--active"
                    : "student-row"
                }
                type="button"
                key={student.learnerId}
                onClick={() =>
                  canReadGuardians
                    ? void openStudent(student.learnerId)
                    : setDetailError(
                        "Guardian permission is required to open the combined student record.",
                      )
                }
              >
                <span className="student-row__avatar">
                  {student.legalName
                    .split(/\s+/)
                    .slice(0, 2)
                    .map((part) => part[0])
                    .join("")}
                </span>
                <span className="student-row__identity">
                  <strong>{student.legalName}{student.admissionType === "RTE" && <small> · RTE</small>}</strong>
                  <small>
                    {student.learnerReference} ·{" "}
                    {student.admissionNumber || "Admission number pending"}
                  </small>
                </span>
                <span className="student-row__class">
                  <strong>
                    {student.gradeCode}
                    {student.sectionCode ? ` · ${student.sectionCode}` : ""}
                  </strong>
                  <small>{student.sectionName || "Section pending"}</small>
                </span>
                <span
                  className={`student-status student-status--${student.placementStatus.toLowerCase()}`}
                >
                  {student.placementStatus === "PLACED" ? "Placed" : "Needs section"}
                </span>
                <span className="student-row__family">
                  <User />
                  <small>{student.guardianCount}</small>
                </span>
              </button>
            ))}
          </div>
          {data && data.total > data.size ? (
            <footer>
              <button
                type="button"
                disabled={page === 0}
                onClick={() => {
                  setLoading(true);
                  setPage((value) => value - 1);
                }}
              >
                Previous
              </button>
              <span>
                Page {page + 1} of {pageCount}
              </span>
              <button
                type="button"
                disabled={page + 1 >= pageCount}
                onClick={() => {
                  setLoading(true);
                  setPage((value) => value + 1);
                }}
              >
                Next
              </button>
            </footer>
          ) : null}
        </section>

        {selectedId ? (
          <aside className="student-detail" aria-label="Selected student record">
            <header>
              <div>
                <span>Protected record</span>
                <h2>{detail?.student.legalName ?? "Student details"}</h2>
                <small>{detail?.student.learnerReference ?? selectedId}</small>
              </div>
              <button
                type="button"
                onClick={() => {
                  setSelectedId("");
                  setDetail(null);
                  setDetailError("");
                }}
              >
                Close
              </button>
            </header>
            {detailLoading ? (
              <div className="student-detail__loading">
                <i />
                <i />
                <i />
              </div>
            ) : null}
            {detailError && (
              <div className="student-alert student-alert--error" role="alert">
                {detailError}
              </div>
            )}
            {detail ? (
              <div className="student-detail__body">
                <section className="student-summary">
                  <div><span>Admission type</span><strong>{detail.student.admissionType === "RTE" ? "RTE" : "Regular"}</strong>{detail.student.admissionType === "RTE" && <small>Original session: {detail.student.rteAdmissionSessionName}{detail.student.rteReference ? ` · Reference: ${detail.student.rteReference}` : ""}</small>}</div>
                  <div>
                    <span>Enrolment</span>
                    <strong>
                      {detail.student.admissionNumber || detail.student.enrolmentReference}
                    </strong>
                    <small>{detail.student.academicSessionName}</small>
                  </div>
                  <div>
                    <span>Class</span>
                    <strong>
                      {detail.student.gradeCode}
                      {detail.student.sectionCode ? ` · ${detail.student.sectionCode}` : ""}
                    </strong>
                    <small>{detail.student.sectionName || "Placement pending"}</small>
                  </div>
                  <div>
                    <span>Campus</span>
                    <strong>{detail.student.campusName}</strong>
                    <small>{detail.student.learnerStatus.toLowerCase()}</small>
                  </div>
                </section>

                <details className="student-panel" open>
                  <summary>
                    <span>
                      <User />
                      <strong>Learner profile</strong>
                    </span>
                    <small>Version {detail.student.learnerVersion}</small>
                  </summary>
                  <form className="student-form" onSubmit={saveProfile}>
                    <label>
                      <span>Legal name</span>
                      <input
                        required
                        maxLength={120}
                        value={profile?.legalName ?? ""}
                        disabled={!canManageStudents || saving}
                        onChange={(event) =>
                          setProfile(
                            (current) => current && { ...current, legalName: event.target.value },
                          )
                        }
                      />
                    </label>
                    <label>
                      <span>Preferred name</span>
                      <input
                        maxLength={120}
                        value={profile?.preferredName ?? ""}
                        disabled={!canManageStudents || saving}
                        onChange={(event) =>
                          setProfile(
                            (current) =>
                              current && { ...current, preferredName: event.target.value },
                          )
                        }
                      />
                    </label>
                    <label>
                      <span>Date of birth</span>
                      <input
                        type="date"
                        required
                        value={profile?.birthDate ?? ""}
                        disabled={!canManageStudents || saving}
                        onChange={(event) =>
                          setProfile(
                            (current) => current && { ...current, birthDate: event.target.value },
                          )
                        }
                      />
                    </label>
                    <label>
                      <span>Gender</span>
                      <select
                        value={profile?.genderCode ?? "NOT_STATED"}
                        disabled={!canManageStudents || saving}
                        onChange={(event) =>
                          setProfile(
                            (current) => current && { ...current, genderCode: event.target.value },
                          )
                        }
                      >
                        <option value="NOT_STATED">Not stated</option>
                        <option value="FEMALE">Female</option>
                        <option value="MALE">Male</option>
                        <option value="NON_BINARY">Non-binary</option>
                        <option value="SELF_DESCRIBED">Self-described</option>
                      </select>
                    </label>
                    {profile?.genderCode === "SELF_DESCRIBED" ? (
                      <label className="student-form__wide">
                        <span>Gender description</span>
                        <input
                          required
                          maxLength={80}
                          value={profile.genderDescription}
                          disabled={!canManageStudents || saving}
                          onChange={(event) =>
                            setProfile(
                              (current) =>
                                current && { ...current, genderDescription: event.target.value },
                            )
                          }
                        />
                      </label>
                    ) : null}
                    {canManageStudents ? (
                      <>
                        <label className="student-form__wide">
                          <span>Correction reason</span>
                          <textarea
                            required
                            maxLength={240}
                            value={profile?.correctionReason ?? ""}
                            disabled={saving}
                            onChange={(event) =>
                              setProfile(
                                (current) =>
                                  current && { ...current, correctionReason: event.target.value },
                              )
                            }
                            placeholder="State what source was checked; do not add unnecessary personal details."
                          />
                        </label>
                        <button
                          className="button button--dark student-form__action"
                          type="submit"
                          disabled={saving}
                        >
                          Save audited correction
                        </button>
                      </>
                    ) : (
                      <p className="student-form__note">
                        <Shield /> You have read-only access to this profile.
                      </p>
                    )}
                  </form>
                </details>

                <section className="student-panel student-guardians">
                  <header>
                    <span>
                      <PeopleIcon />
                      <strong>Guardians &amp; access</strong>
                    </span>
                    <small>{detail.guardians.length} linked</small>
                  </header>
                  <div className="guardian-cards">
                    {detail.guardians.map((item) => (
                      <article key={item.guardianId}>
                        <div className="guardian-card__top">
                          <span className="guardian-avatar">
                            {item.displayName
                              .split(/\s+/)
                              .slice(0, 2)
                              .map((part) => part[0])
                              .join("")}
                          </span>
                          <div>
                            <strong>{item.displayName}</strong>
                            <small>
                              {displayCode(item.relationshipCode)}
                              {item.primaryContact ? " · Primary" : ""}
                            </small>
                          </div>
                          <em
                            className={`portal-readiness portal-readiness--${item.portalReadiness.toLowerCase()}`}
                          >
                            {displayCode(item.portalReadiness)}
                          </em>
                        </div>
                        <dl>
                          <div>
                            <dt>Email</dt>
                            <dd>{item.email || "Not provided"}</dd>
                          </div>
                          <div>
                            <dt>Phone</dt>
                            <dd>{item.phone || "Not provided"}</dd>
                          </div>
                          <div>
                            <dt>Pickup</dt>
                            <dd>{item.pickupAuthorized ? "Authorized" : "Not authorized"}</dd>
                          </div>
                          <div>
                            <dt>Linked learners</dt>
                            <dd>{item.linkedLearnerCount}</dd>
                          </div>
                        </dl>
                        <p>
                          {item.portalAccessStatus === "ACTIVE"
                            ? "Verified family portal access is active for this learner relationship."
                            : item.portalReadiness === "READY"
                              ? "Verified portal access can be invited using this email address."
                              : `Portal state: ${displayCode(item.portalAccessStatus)}.`}
                        </p>
                        {canInviteGuardians ? (
                          <button type="button" onClick={() => void openPortalAccess(item)}>
                            Manage portal access
                          </button>
                        ) : null}
                        {canManageGuardians ? (
                          <button
                            type="button"
                            onClick={() => {
                              setPortalGuardianId("");
                              setPortalInvitations([]);
                              setPortalReason("");
                              setGuardian(guardianForm(item));
                            }}
                          >
                            Correct contact or relationship
                          </button>
                        ) : null}
                      </article>
                    ))}
                  </div>
                  {portalGuardianId && canInviteGuardians ? (
                    <section className="portal-access-panel" aria-labelledby="portal-access-title">
                      {(() => {
                        const selectedGuardian = detail.guardians.find(
                          (item) => item.guardianId === portalGuardianId,
                        );
                        const activeInvitation = portalInvitations.find(
                          (item) => effectiveInvitationStatus(item) === "ISSUED",
                        );
                        return (
                          <>
                            <header>
                              <div>
                                <span>Relationship-scoped access</span>
                                <h3 id="portal-access-title">
                                  {selectedGuardian?.displayName ?? "Guardian"}
                                </h3>
                              </div>
                              <button
                                type="button"
                                onClick={() => {
                                  setPortalGuardianId("");
                                  setPortalInvitations([]);
                                  setPortalReason("");
                                }}
                              >
                                Close
                              </button>
                            </header>
                            <p className="portal-access-panel__explanation">
                              This grants access only to this learner relationship. Siblings require
                              their own verified invitation. No email is sent until a delivery
                              provider is configured.
                            </p>
                            <form onSubmit={issuePortalInvitation}>
                              <label>
                                <span>Audited reason</span>
                                <textarea
                                  required
                                  maxLength={240}
                                  value={portalReason}
                                  disabled={saving}
                                  onChange={(event) => setPortalReason(event.target.value)}
                                  placeholder="State the approved request source and record checked; avoid personal details."
                                />
                              </label>
                              <div>
                                <button
                                  className="button button--dark"
                                  type="submit"
                                  disabled={
                                    saving ||
                                    portalLoading ||
                                    !selectedGuardian?.email ||
                                    selectedGuardian.portalAccessStatus === "ACTIVE"
                                  }
                                >
                                  {activeInvitation
                                    ? "Replace invitation"
                                    : "Create secure invitation"}
                                </button>
                                {activeInvitation ? (
                                  <button
                                    className="portal-access-panel__revoke"
                                    type="button"
                                    disabled={saving || !portalReason.trim()}
                                    onClick={() => void revokePortalInvitation(activeInvitation)}
                                  >
                                    Revoke current invitation
                                  </button>
                                ) : null}
                              </div>
                            </form>
                            <div className="portal-invitation-history">
                              <strong>Invitation history</strong>
                              {portalLoading ? (
                                <p>Loading secure history…</p>
                              ) : portalInvitations.length ? (
                                <ol>
                                  {portalInvitations.map((invitation) => {
                                    const status = effectiveInvitationStatus(invitation);
                                    return (
                                      <li key={invitation.id}>
                                        <div>
                                          <strong>{invitation.maskedDestination}</strong>
                                          <small>
                                            Created {dateTimeLabel(invitation.createdAt)} · Expires{" "}
                                            {dateTimeLabel(invitation.expiresAt)}
                                          </small>
                                        </div>
                                        <span
                                          className={`portal-invitation-status portal-invitation-status--${status.toLowerCase()}`}
                                        >
                                          {displayCode(status)}
                                        </span>
                                        <em>{displayCode(invitation.deliveryStatus)}</em>
                                      </li>
                                    );
                                  })}
                                </ol>
                              ) : (
                                <p>No invitation has been created for this relationship.</p>
                              )}
                            </div>
                          </>
                        );
                      })()}
                    </section>
                  ) : null}
                  {guardian ? (
                    <form className="student-form guardian-editor" onSubmit={saveGuardian}>
                      <header>
                        <div>
                          <span>Audited correction</span>
                          <h3>{guardian.displayName}</h3>
                        </div>
                        <button type="button" onClick={() => setGuardian(null)}>
                          Cancel
                        </button>
                      </header>
                      <label>
                        <span>Display name</span>
                        <input
                          required
                          maxLength={120}
                          value={guardian.displayName}
                          disabled={saving}
                          onChange={(event) =>
                            setGuardian(
                              (current) =>
                                current && { ...current, displayName: event.target.value },
                            )
                          }
                        />
                      </label>
                      <label>
                        <span>Relationship</span>
                        <input
                          required
                          maxLength={40}
                          value={guardian.relationshipCode}
                          disabled={saving}
                          onChange={(event) =>
                            setGuardian(
                              (current) =>
                                current && { ...current, relationshipCode: event.target.value },
                            )
                          }
                        />
                      </label>
                      <label>
                        <span>Email</span>
                        <input
                          type="email"
                          maxLength={254}
                          value={guardian.email}
                          disabled={saving}
                          onChange={(event) =>
                            setGuardian(
                              (current) => current && { ...current, email: event.target.value },
                            )
                          }
                        />
                      </label>
                      <label>
                        <span>Phone</span>
                        <input
                          maxLength={30}
                          value={guardian.phone}
                          disabled={saving}
                          onChange={(event) =>
                            setGuardian(
                              (current) => current && { ...current, phone: event.target.value },
                            )
                          }
                        />
                      </label>
                      <label className="student-check">
                        <input
                          type="checkbox"
                          checked={guardian.primaryContact}
                          disabled={saving}
                          onChange={(event) =>
                            setGuardian(
                              (current) =>
                                current && { ...current, primaryContact: event.target.checked },
                            )
                          }
                        />
                        <span>Primary school contact</span>
                      </label>
                      <label className="student-check">
                        <input
                          type="checkbox"
                          checked={guardian.pickupAuthorized}
                          disabled={saving}
                          onChange={(event) =>
                            setGuardian(
                              (current) =>
                                current && { ...current, pickupAuthorized: event.target.checked },
                            )
                          }
                        />
                        <span>Authorized for pickup</span>
                      </label>
                      <label className="student-form__wide">
                        <span>Correction reason</span>
                        <textarea
                          required
                          maxLength={240}
                          value={guardian.correctionReason}
                          disabled={saving}
                          onChange={(event) =>
                            setGuardian(
                              (current) =>
                                current && { ...current, correctionReason: event.target.value },
                            )
                          }
                        />
                      </label>
                      <button
                        className="button button--dark student-form__action"
                        type="submit"
                        disabled={saving}
                      >
                        Save guardian correction
                      </button>
                    </form>
                  ) : null}
                </section>

                {canManageStudentPortal ? (
                  <StudentPortalAccessPanel learnerId={detail.student.learnerId} />
                ) : null}

                <section className="student-panel student-placement">
                  <header>
                    <span>
                      <AcademicIcon />
                      <strong>Section placement</strong>
                    </span>
                    <small>{detail.placementHistory.length} history entries</small>
                  </header>
                  {canManageStudents ? (
                    <form className="student-form" onSubmit={savePlacement}>
                      <label className="student-form__wide">
                        <span>Active section</span>
                        <select
                          required
                          value={placement.sectionId}
                          disabled={saving}
                          onChange={(event) =>
                            setPlacement((current) => ({
                              ...current,
                              sectionId: event.target.value,
                            }))
                          }
                        >
                          <option value="">Choose a section</option>
                          {sections.map((item) => (
                            <option value={item.id} key={item.id}>
                              {item.displayName} ({item.code}) · {item.activeStudentCount}
                              {item.capacity ? `/${item.capacity}` : " placed"}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label>
                        <span>Roll number</span>
                        <input
                          maxLength={30}
                          value={placement.rollNumber}
                          disabled={saving}
                          onChange={(event) =>
                            setPlacement((current) => ({
                              ...current,
                              rollNumber: event.target.value,
                            }))
                          }
                        />
                      </label>
                      <label>
                        <span>Effective from</span>
                        <input
                          type="date"
                          required
                          value={placement.startsOn}
                          disabled={saving}
                          onChange={(event) =>
                            setPlacement((current) => ({
                              ...current,
                              startsOn: event.target.value,
                            }))
                          }
                        />
                      </label>
                      <label className="student-form__wide">
                        <span>Placement reason</span>
                        <textarea
                          required
                          maxLength={240}
                          value={placement.changeReason}
                          disabled={saving}
                          onChange={(event) =>
                            setPlacement((current) => ({
                              ...current,
                              changeReason: event.target.value,
                            }))
                          }
                          placeholder="Initial allocation or verified reason for reassignment"
                        />
                      </label>
                      <button
                        className="button button--dark student-form__action"
                        type="submit"
                        disabled={saving || sections.length === 0}
                      >
                        {detail.student.sectionId ? "Save reassignment" : "Place student"}
                      </button>
                    </form>
                  ) : null}
                  {canManageStudents && sections.length === 0 ? (
                    <div className="section-empty">
                      <AcademicIcon />
                      <div>
                        <strong>No eligible sections are configured</strong>
                        <p>
                          Create the real section used by {detail.student.gradeCode} at{" "}
                          {detail.student.campusName}; no section names are guessed.
                        </p>
                      </div>
                    </div>
                  ) : null}
                  {canManageStudents && canConfigureAcademics ? (
                    <details className="section-create">
                      <summary>
                        <PlusIcon /> Create a section for {detail.student.gradeCode}
                      </summary>
                      <form className="student-form" onSubmit={createSection}>
                        <label>
                          <span>Section code</span>
                          <input
                            required
                            maxLength={30}
                            value={section.code}
                            disabled={saving}
                            onChange={(event) =>
                              setSection((current) => ({ ...current, code: event.target.value }))
                            }
                            placeholder="A"
                          />
                        </label>
                        <label>
                          <span>Display name</span>
                          <input
                            required
                            maxLength={100}
                            value={section.displayName}
                            disabled={saving}
                            onChange={(event) =>
                              setSection((current) => ({
                                ...current,
                                displayName: event.target.value,
                              }))
                            }
                            placeholder="Class VI · A"
                          />
                        </label>
                        <label>
                          <span>Capacity (optional)</span>
                          <input
                            type="number"
                            min={1}
                            max={500}
                            value={section.capacity}
                            disabled={saving}
                            onChange={(event) =>
                              setSection((current) => ({
                                ...current,
                                capacity: event.target.value,
                              }))
                            }
                          />
                        </label>
                        <button
                          className="button button--dark student-form__action"
                          type="submit"
                          disabled={saving}
                        >
                          Create section
                        </button>
                      </form>
                    </details>
                  ) : null}
                  <ol className="placement-history">
                    {detail.placementHistory.map((item) => (
                      <li key={item.placementId}>
                        <span>
                          <Calendar />
                        </span>
                        <div>
                          <strong>
                            {item.sectionName} · {item.sectionCode}
                          </strong>
                          <small>
                            {dateLabel(item.startsOn)} – {dateLabel(item.endsOn)}
                            {item.rollNumber ? ` · Roll ${item.rollNumber}` : ""}
                          </small>
                          <p>
                            Placed: {item.placementReason}
                            {item.endReason ? ` · Ended: ${item.endReason}` : ""}
                          </p>
                        </div>
                        <em>{displayCode(item.status)}</em>
                      </li>
                    ))}
                  </ol>
                </section>
              </div>
            ) : null}
          </aside>
        ) : null}
      </div>
    </section>
  );
}
