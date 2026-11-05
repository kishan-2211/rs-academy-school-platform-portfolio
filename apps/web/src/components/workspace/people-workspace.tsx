"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ArrowUpRight } from "@/components/icons";
import { PeopleIcon, PlusIcon, SettingsIcon } from "@/components/workspace/workspace-icons";
import { StaffAccountInvitationsPanel } from "./staff-account-invitations-panel";

type Campus = { id: string; name: string; status: string };
type Account = { userAccountId: string; membershipId: string; displayName: string; email?: string; roles: string[]; membershipStatus: string };
type Department = { id: string; code: string; name: string; description?: string; status: string; rowVersion: number };
type Designation = { id: string; code: string; name: string; staffCategory: string; cbseStaffCategory?: string; teachingRole: boolean; leadershipRole: boolean; eligibleAsTransportDriver: boolean; eligibleAsTransportAttendant: boolean; description?: string; displayOrder: number; status: string; rowVersion: number };
type Staff = { id: string; employeeCode: string; legalName: string; displayName: string; primaryLanguage: string; workEmail?: string; workPhone?: string; userAccountId?: string; joinedOn: string; status: string; endedOn?: string; endingReason?: string; rowVersion: number };
type Assignment = { id: string; staffProfileId: string; designationId: string; departmentId?: string; campusId: string; employmentType: string; employerKind: string; agencyName?: string; primaryAssignment: boolean; startsOn: string; endsOn?: string; status: string; endingReason?: string; rowVersion: number };
type Qualification = { id: string; staffProfileId: string; name: string; level: string; subjectSpecialisation?: string; institutionName?: string; awardingBody?: string; completedYear?: number; verificationStatus: string; publicSummaryEligible: boolean; verificationReason?: string; rowVersion: number };
type ComplianceType = { id: string; code: string; name: string; description?: string; applicableStaffCategory?: string; validityMonths?: number; requiredForActiveService: boolean; status: string; rowVersion: number };
type Compliance = { id: string; staffProfileId: string; complianceTypeId: string; status: string; completedOn?: string; validUntil?: string; safeReference?: string; restrictedNote?: string; decisionReason?: string; rowVersion: number };
type PublicProfile = { id: string; staffProfileId: string; revisionNumber: number; supersedesRevisionId?: string; displayName: string; designation: string; qualificationSummary?: string; shortBio?: string; cbseStaffCategory?: string; displayOrder: number; status: string; creatorName: string; deciderName?: string; decisionReason?: string; publishedAt?: string; rowVersion: number };
type Snapshot = { schoolId: string; schoolName: string; canManage: boolean; canReviewCompliance: boolean; canPublish: boolean; campuses: Campus[]; accounts: Account[]; departments: Department[]; designations: Designation[]; staff: Staff[]; assignments: Assignment[]; qualifications: Qualification[]; complianceTypes: ComplianceType[]; complianceChecks: Compliance[]; publicProfiles: PublicProfile[] };
type Tab = "directory" | "configuration" | "evidence" | "publication";
type EndingConfirmation = { kind: "staff" | "assignment"; id: string; staffId: string; schoolId: string; expectedVersion: number; startsOn: string; endDate: string; status: string; displayName: string; employeeCode: string; title: string; description: string };
type ReviewConfirmation = {
  kind: "qualifications" | "compliance-checks" | "public-profiles";
  action: "submit" | "decision"; decision?: string;
  id: string; staffId: string; schoolId: string; expectedVersion: number; status: string;
  displayName: string; employeeCode: string; title: string; description: string; createdBy?: string;
  fields: { label: string; value: string }[];
  complianceRule?: { id: string; expectedVersion: number; validityMonths: number | null };
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const categories = ["LEADERSHIP", "TEACHING", "ADMINISTRATION", "FINANCE", "TRANSPORT", "OPERATIONS", "SUPPORT", "HEALTH", "OTHER"];
const cbseCategories = ["PRINCIPAL", "VICE_PRINCIPAL", "HEAD", "PGT", "TGT", "PRT", "SPECIAL_EDUCATOR", "COUNSELLOR_WELLNESS", "OTHER_TEACHING", "NON_TEACHING"];
const today = () => { const date = new Date(); date.setMinutes(date.getMinutes() - date.getTimezoneOffset()); return date.toISOString().slice(0, 10); };
const label = (value?: string) => value ? value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase()) : "Not set";
function canCreatePublicRevision(item: PublicProfile, profiles: PublicProfile[]) {
  return ["PUBLISHED", "REJECTED"].includes(item.status) && !profiles.some((revision) => revision.supersedesRevisionId === item.id);
}
function cookie(name: string) { return document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1); }
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new Error("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string) { try { const body = await response.json() as { detail?: string }; return body.detail || fallback; } catch { return fallback; } }

export function PeopleWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<Tab>("directory");
  const [selectedId, setSelectedId] = useState("");
  const [editProfile, setEditProfile] = useState(false);
  const [editDepartmentId, setEditDepartmentId] = useState("");
  const [editDesignationId, setEditDesignationId] = useState("");
  const [editComplianceTypeId, setEditComplianceTypeId] = useState("");
  const [supersedesId, setSupersedesId] = useState("");
  const [editPublicId, setEditPublicId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [invitationBlocked, setInvitationBlocked] = useState(false);
  const [ending, setEnding] = useState<EndingConfirmation | null>(null);
  const endingTrigger = useRef<HTMLElement | null>(null);
  const endingPanel = useRef<HTMLFormElement | null>(null);
  const endingDate = useRef<HTMLInputElement | null>(null);
  const endingReason = useRef<HTMLTextAreaElement | null>(null);
  const endingInFlight = useRef(false);
  const [review, setReview] = useState<ReviewConfirmation | null>(null);
  const reviewTrigger = useRef<HTMLElement | null>(null);
  const reviewPanel = useRef<HTMLFormElement | null>(null);
  const reviewReason = useRef<HTMLTextAreaElement | null>(null);
  const reviewInFlight = useRef(false);
  const noticeTarget = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (ending) {
      endingPanel.current?.scrollIntoView({ block: "start", behavior: "instant" });
      endingDate.current?.focus({ preventScroll: true });
    }
  }, [ending]);

  useEffect(() => {
    if (review) {
      reviewPanel.current?.scrollIntoView({ block: "start", behavior: "instant" });
      reviewPanel.current?.focus({ preventScroll: true });
    }
  }, [review]);

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/people`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "The people workspace could not be opened."));
    const snapshot = await response.json() as Snapshot;
    setData(snapshot);
    setSelectedId((current) => snapshot.staff.some((item) => item.id === current) ? current : snapshot.staff[0]?.id || "");
  }, []);

  useEffect(() => { Promise.resolve().then(load).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "The people workspace could not be opened.")); }, [load]);
  const selected = useMemo(() => data?.staff.find((item) => item.id === selectedId) ?? null, [data, selectedId]);
  const selectedAssignments = data?.assignments.filter((item) => item.staffProfileId === selectedId) ?? [];
  const activeAssignment = selectedAssignments.find((item) => item.status === "ACTIVE" && item.primaryAssignment) ?? selectedAssignments.find((item) => item.status === "ACTIVE");
  const selectedQualifications = data?.qualifications.filter((item) => item.staffProfileId === selectedId) ?? [];
  const selectedChecks = data?.complianceChecks.filter((item) => item.staffProfileId === selectedId) ?? [];
  const departmentDraft = data?.departments.find((item) => item.id === editDepartmentId);
  const designationDraft = data?.designations.find((item) => item.id === editDesignationId);
  const complianceTypeDraft = data?.complianceTypes.find((item) => item.id === editComplianceTypeId);
  const superseded = data?.publicProfiles.find((item) => item.id === supersedesId);
  const publicDraft = data?.publicProfiles.find((item) => item.id === editPublicId && item.status === "DRAFT");
  const publicRevisionSources = data?.publicProfiles.filter((item) => canCreatePublicRevision(item, data.publicProfiles) || item.id === publicDraft?.supersedesRevisionId) ?? [];

  async function mutate(path: string, init: RequestInit, success: string) {
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiUrl}${path}`, { ...init, credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken(), ...(init.headers || {}) } });
      if (!response.ok) throw new Error(await problem(response, "The people change could not be saved."));
      await load(); setNotice(success); return true;
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The people change could not be saved."); return false; }
    finally { setBusy(false); }
  }

  async function saveStaff(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    const current = editProfile ? selected : null;
    const payload = { schoolId: data.schoolId, employeeCode: form.get("employeeCode"), legalName: form.get("legalName"), displayName: form.get("displayName"), primaryLanguage: form.get("primaryLanguage"), workEmail: form.get("workEmail") || null, workPhone: form.get("workPhone") || null, userAccountId: form.get("userAccountId") || null, joinedOn: form.get("joinedOn"), status: form.get("status"), expectedVersion: current?.rowVersion ?? null, reason: form.get("reason") || null };
    const ok = await mutate(current ? `/api/v1/people/staff/${current.id}` : "/api/v1/people/staff", { method: current ? "PUT" : "POST", body: JSON.stringify(payload) }, current ? "Staff profile updated with history." : "Staff profile created.");
    if (ok) { setEditProfile(false); formElement.reset(); }
  }

  async function saveAssignment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !selected) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    const payload = { schoolId: data.schoolId, staffProfileId: selected.id, designationId: form.get("designationId"), departmentId: form.get("departmentId") || null, campusId: form.get("campusId"), employmentType: form.get("employmentType"), employerKind: form.get("employerKind"), agencyName: form.get("agencyName") || null, primaryAssignment: form.get("primaryAssignment") === "on", startsOn: form.get("startsOn"), status: form.get("status"), reason: form.get("reason") };
    if (await mutate("/api/v1/people/assignments", { method: "POST", body: JSON.stringify(payload) }, "Effective-dated staff assignment added.")) formElement.reset();
  }

  function endRecord(kind: "staff" | "assignment", id: string, version: number, endDate: string) {
    if (!data?.canManage || busy || ending || endingInFlight.current || review || reviewInFlight.current) return;
    const assignment = kind === "assignment" ? data.assignments.find((item) => item.id === id) : undefined;
    const staff = data.staff.find((item) => item.id === (kind === "staff" ? id : assignment?.staffProfileId));
    const record = kind === "staff" ? staff : assignment;
    const startsOn = kind === "staff" ? staff?.joinedOn : assignment?.startsOn;
    if (!record || !staff || !startsOn || staff.id !== selectedId || record.rowVersion !== version ||
      (kind === "staff" ? !["ACTIVE", "INACTIVE"].includes(record.status) : !["ACTIVE", "PLANNED"].includes(record.status))) {
      setError("The selected record changed. Reload and review it before ending it."); return;
    }
    endingTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setError(""); setNotice("");
    setEnding({ kind, id, staffId: staff.id, schoolId: data.schoolId, expectedVersion: version,
      startsOn, endDate: endDate < startsOn ? startsOn : endDate, status: record.status,
      displayName: staff.displayName, employeeCode: staff.employeeCode,
      title: kind === "staff" ? "End employment" : record.status === "PLANNED" ? "Cancel planned assignment" : "End assignment",
      description: kind === "staff"
        ? "This closes active/planned assignments, retires public staff snapshots and revokes linked staff roles and sessions. Eligible guardian access and historical records are preserved. Complete the required school handoff first."
        : `This ${record.status === "PLANNED" ? "cancels the planned" : "ends the"} assignment without deleting history.${assignment?.primaryAssignment ? " Ending a primary assignment also retires its public staff snapshot and may change teaching eligibility." : ""} The linked portal account is not offboarded by this action.`,
    });
  }

  function cancelEnding() {
    if (busy || endingInFlight.current) return;
    setEnding(null); setError("");
    requestAnimationFrame(() => { if (endingTrigger.current?.isConnected) endingTrigger.current.focus(); });
  }

  async function confirmEnding(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ending || !data?.canManage || busy || endingInFlight.current || review || reviewInFlight.current) return;
    const record = ending.kind === "staff"
      ? data.staff.find((item) => item.id === ending.id)
      : data.assignments.find((item) => item.id === ending.id && item.staffProfileId === ending.staffId);
    if (data.schoolId !== ending.schoolId || selectedId !== ending.staffId || !record ||
      record.rowVersion !== ending.expectedVersion || record.status !== ending.status ||
      !(ending.kind === "staff" ? ["ACTIVE", "INACTIVE"] : ["ACTIVE", "PLANNED"]).includes(record.status)) {
      setError("The selected record changed. Cancel this review, reload and choose the action again."); return;
    }
    const form = new FormData(event.currentTarget);
    const reason = String(form.get("reason") ?? "").trim();
    if (reason.length < 3 || reason.length > 500) {
      setError("Enter an ending reason between 3 and 500 characters."); return;
    }
    const endDate = String(form.get("endDate") ?? "");
    const parsed = new Date(`${endDate}T00:00:00Z`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(endDate) || Number.isNaN(parsed.getTime()) ||
      parsed.toISOString().slice(0, 10) !== endDate || endDate < ending.startsOn) {
      setError("Enter a valid end date on or after the record's start date."); return;
    }
    if (form.get("handoffConfirmed") !== "on") {
      setError("Confirm the person, date, required handoff and access/publication impact before proceeding."); return;
    }
    endingInFlight.current = true;
    try {
      const ok = await mutate(`/api/v1/people/${ending.kind === "staff" ? "staff" : "assignments"}/${ending.id}/end`, {
        method: "POST", body: JSON.stringify({ schoolId: ending.schoolId, endDate, expectedVersion: ending.expectedVersion, reason }),
      }, ending.kind === "staff" ? "Staff employment and linked staff access were safely ended." : "Assignment ended without deleting history.");
      if (ok) { setEnding(null); requestAnimationFrame(() => noticeTarget.current?.focus()); }
      else requestAnimationFrame(() => endingReason.current?.focus());
    } finally { endingInFlight.current = false; }
  }

  async function saveDepartment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement); const item = departmentDraft;
    const payload = { schoolId: data.schoolId, code: form.get("code"), name: form.get("name"), description: form.get("description") || null, status: form.get("status"), expectedVersion: item?.rowVersion ?? null, reason: form.get("reason") || null };
    if (await mutate(item ? `/api/v1/people/departments/${item.id}` : "/api/v1/people/departments", { method: item ? "PUT" : "POST", body: JSON.stringify(payload) }, item ? "Department updated." : "Configurable department added.")) { setEditDepartmentId(""); formElement.reset(); }
  }

  async function saveDesignation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement); const item = designationDraft;
    const payload = { schoolId: data.schoolId, code: form.get("code"), name: form.get("name"), staffCategory: form.get("staffCategory"), cbseStaffCategory: form.get("cbseStaffCategory") || null, teachingRole: form.get("teachingRole") === "on", leadershipRole: form.get("leadershipRole") === "on", eligibleAsTransportDriver: form.get("eligibleAsTransportDriver") === "on", eligibleAsTransportAttendant: form.get("eligibleAsTransportAttendant") === "on", description: form.get("description") || null, displayOrder: Number(form.get("displayOrder")), status: form.get("status"), expectedVersion: item?.rowVersion ?? null, reason: form.get("reason") || null };
    if (await mutate(item ? `/api/v1/people/designations/${item.id}` : "/api/v1/people/designations", { method: item ? "PUT" : "POST", body: JSON.stringify(payload) }, item ? "Designation updated." : "Configurable designation added.")) { setEditDesignationId(""); formElement.reset(); }
  }

  async function saveComplianceType(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement); const item = complianceTypeDraft;
    const payload = { schoolId: data.schoolId, code: form.get("code"), name: form.get("name"), description: form.get("description") || null, applicableStaffCategory: form.get("applicableStaffCategory") || null, validityMonths: form.get("validityMonths") ? Number(form.get("validityMonths")) : null, requiredForActiveService: form.get("requiredForActiveService") === "on", status: form.get("status"), expectedVersion: item?.rowVersion ?? null, reason: form.get("reason") || null };
    if (await mutate(item ? `/api/v1/people/compliance-types/${item.id}` : "/api/v1/people/compliance-types", { method: item ? "PUT" : "POST", body: JSON.stringify(payload) }, item ? "Compliance rule updated." : "Compliance rule added.")) { setEditComplianceTypeId(""); formElement.reset(); }
  }

  async function saveQualification(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !selected) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    const payload = { schoolId: data.schoolId, staffProfileId: selected.id, name: form.get("name"), level: form.get("level"), subjectSpecialisation: form.get("subjectSpecialisation") || null, institutionName: form.get("institutionName") || null, awardingBody: form.get("awardingBody") || null, completedYear: form.get("completedYear") ? Number(form.get("completedYear")) : null, reason: form.get("reason") };
    if (await mutate("/api/v1/people/qualifications", { method: "POST", body: JSON.stringify(payload) }, "Qualification submitted for independent verification.")) formElement.reset();
  }

  async function saveCompliance(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !selected) return; const formElement = event.currentTarget; const form = new FormData(formElement);
    const payload = { schoolId: data.schoolId, staffProfileId: selected.id, complianceTypeId: form.get("complianceTypeId"), safeReference: form.get("safeReference") || null, restrictedNote: form.get("restrictedNote") || null, reason: form.get("reason") };
    if (await mutate("/api/v1/people/compliance-checks", { method: "POST", body: JSON.stringify(payload) }, "Restricted compliance check submitted for independent review.")) formElement.reset();
  }

  function decideEvidence(kind: "qualifications" | "compliance-checks", id: string, version: number, decision: string) {
    if (!data?.canReviewCompliance || busy || ending || endingInFlight.current || review || reviewInFlight.current) return;
    const item = kind === "qualifications" ? data.qualifications.find((row) => row.id === id) : data.complianceChecks.find((row) => row.id === id);
    const staff = data.staff.find((row) => row.id === item?.staffProfileId);
    const status = item && ("verificationStatus" in item ? item.verificationStatus : item.status);
    if (!item || !staff || staff.id !== selectedId || item.rowVersion !== version || status !== "PENDING" ||
      !(kind === "qualifications" ? ["VERIFIED", "REJECTED"] : ["VERIFIED", "REJECTED", "WAIVED"]).includes(decision)) {
      setError("The evidence changed. Reload and review the record before deciding it."); return;
    }
    const rule = "complianceTypeId" in item ? data.complianceTypes.find((row) => row.id === item.complianceTypeId) : undefined;
    if (kind === "compliance-checks" && !rule) { setError("The compliance rule is unavailable. Reload before reviewing this check."); return; }
    const fields = "verificationStatus" in item
      ? [{ label: "Qualification", value: item.name }, { label: "Level", value: label(item.level) },
        { label: "Subject specialisation", value: item.subjectSpecialisation || "Not recorded" },
        { label: "Institution", value: item.institutionName || "Not recorded" }, { label: "Awarding body", value: item.awardingBody || "Not recorded" },
        { label: "Completion year", value: item.completedYear?.toString() || "Not recorded" }]
      : [{ label: "Compliance rule", value: rule!.name }, { label: "Safe reference", value: item.safeReference || "Not recorded" },
        { label: "Restricted note — not public", value: item.restrictedNote || "Not recorded" }];
    reviewTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setError(""); setNotice("");
    setReview({ kind, action: "decision", decision, id, staffId: staff.id, schoolId: data.schoolId,
      expectedVersion: version, status, displayName: staff.displayName, employeeCode: staff.employeeCode,
      title: `${decision === "VERIFIED" ? "Verify" : decision === "WAIVED" ? "Waive" : "Reject"} ${kind === "qualifications" ? "qualification" : "compliance check"}`,
      description: kind === "qualifications"
        ? "Check the original school-held evidence. Verification makes this qualification eligible for a separately approved public summary; it does not publish the evidence. The decision and your reason are retained in history."
        : "Check the original school-held source and record the actual completion date when verifying. A waiver or rejection does not count as verification. The decision and your reason are retained in restricted history.",
      fields, ...(rule ? { complianceRule: { id: rule.id, expectedVersion: rule.rowVersion, validityMonths: rule.validityMonths ?? null } } : {}),
    });
  }

  async function savePublicProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const formElement = event.currentTarget; const form = new FormData(formElement); const source = superseded;
    const staffId = publicDraft?.staffProfileId || source?.staffProfileId || String(form.get("staffProfileId"));
    const payload = { schoolId: data.schoolId, staffProfileId: staffId, revisionNumber: publicDraft?.revisionNumber ?? (source ? source.revisionNumber + 1 : 1), supersedesRevisionId: publicDraft?.supersedesRevisionId || source?.id || null, displayName: form.get("displayName"), designation: form.get("designation"), qualificationSummary: form.get("qualificationSummary") || null, shortBio: form.get("shortBio") || null, cbseStaffCategory: form.get("cbseStaffCategory") || null, displayOrder: Number(form.get("displayOrder")), expectedVersion: publicDraft?.rowVersion ?? null, reason: form.get("reason") || null };
    const ok = await mutate(publicDraft ? `/api/v1/people/public-profiles/${publicDraft.id}` : "/api/v1/people/public-profiles", { method: publicDraft ? "PUT" : "POST", body: JSON.stringify(payload) }, publicDraft ? "Unpublished public snapshot updated." : "Public staff snapshot created as an unpublished draft.");
    if (ok) { setSupersedesId(""); setEditPublicId(""); formElement.reset(); }
  }

  function publicWorkflow(item: PublicProfile, action: "submit" | "decision", decision?: string) {
    if (!data || !(action === "submit" ? data.canManage : data.canPublish) || busy || ending || endingInFlight.current || review || reviewInFlight.current) return;
    const source = data.publicProfiles.find((row) => row.id === item.id);
    const staff = data.staff.find((row) => row.id === source?.staffProfileId);
    if (!source || !staff || source.rowVersion !== item.rowVersion || source.status !== (action === "submit" ? "DRAFT" : "PENDING_APPROVAL") ||
      (action === "decision" && !["PUBLISHED", "REJECTED"].includes(decision || ""))) {
      setError("The public snapshot changed. Reload and review the exact revision before proceeding."); return;
    }
    reviewTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setError(""); setNotice("");
    setReview({ kind: "public-profiles", action, ...(action === "decision" ? { decision } : {}), id: source.id,
      staffId: staff.id, schoolId: data.schoolId, expectedVersion: source.rowVersion, status: source.status,
      displayName: staff.displayName, employeeCode: staff.employeeCode, createdBy: source.creatorName,
      title: action === "submit" ? "Submit public snapshot" : decision === "PUBLISHED" ? "Publish public snapshot" : "Reject public snapshot",
      description: action === "submit"
        ? "Review every publication field for accuracy and privacy before sending this revision to a different authorized approver. Submission does not make this draft public."
        : decision === "PUBLISHED"
          ? "Review every publication field against the approved school records. Approval makes this exact snapshot public and, for a new revision, retires the previous published snapshot without deleting history."
          : "Review this exact revision before rejecting it. It stays unpublished; any existing published revision remains unchanged. Your reason is retained in history.",
      fields: [{ label: "Revision", value: String(source.revisionNumber) }, { label: "Public display name", value: source.displayName },
        { label: "Designation snapshot", value: source.designation }, { label: "Qualification summary", value: source.qualificationSummary || "Not included" },
        { label: "Short biography", value: source.shortBio || "Not included" }, { label: "CBSE disclosure category", value: label(source.cbseStaffCategory) },
        { label: "Display order", value: String(source.displayOrder ?? 0) }],
    });
  }

  function cancelReview() {
    if (busy || reviewInFlight.current) return;
    setReview(null); setError("");
    requestAnimationFrame(() => { if (reviewTrigger.current?.isConnected) reviewTrigger.current.focus(); });
  }

  async function confirmReview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!review || !data || busy || ending || endingInFlight.current || reviewInFlight.current) return;
    const canDecide = review.kind === "public-profiles" ? (review.action === "submit" ? data.canManage : data.canPublish) : data.canReviewCompliance;
    const item = review.kind === "qualifications" ? data.qualifications.find((row) => row.id === review.id)
      : review.kind === "compliance-checks" ? data.complianceChecks.find((row) => row.id === review.id)
        : data.publicProfiles.find((row) => row.id === review.id);
    const status = item && ("verificationStatus" in item ? item.verificationStatus : item.status);
    const expectedStatus = review.kind === "public-profiles" && review.action === "submit" ? "DRAFT" : review.kind === "public-profiles" ? "PENDING_APPROVAL" : "PENDING";
    const validAction = review.kind === "public-profiles"
      ? (review.action === "submit" ? !review.decision : ["PUBLISHED", "REJECTED"].includes(review.decision || ""))
      : review.action === "decision" && (review.kind === "qualifications" ? ["VERIFIED", "REJECTED"] : ["VERIFIED", "REJECTED", "WAIVED"]).includes(review.decision || "");
    if (!canDecide || !validAction || data.schoolId !== review.schoolId || !item || item.staffProfileId !== review.staffId ||
      item.rowVersion !== review.expectedVersion || status !== review.status ||
      status !== expectedStatus || !data.staff.some((row) => row.id === review.staffId) ||
      (review.kind !== "public-profiles" && selectedId !== review.staffId)) {
      setError("The record or your access changed. Cancel this review, reload and choose the action again."); return;
    }
    const rule = review.complianceRule && data.complianceTypes.find((row) => row.id === review.complianceRule!.id);
    if (review.kind === "compliance-checks" && (!review.complianceRule || !rule || rule.rowVersion !== review.complianceRule.expectedVersion ||
      (rule.validityMonths ?? null) !== review.complianceRule.validityMonths)) {
      setError("The compliance rule changed. Cancel this review and reload before deciding the check."); return;
    }
    const form = new FormData(event.currentTarget);
    const reason = String(form.get("reason") ?? "").trim();
    if (reason.length < 3 || reason.length > 500) { setError("Enter a review reason between 3 and 500 characters."); return; }
    if (form.get("sourceConfirmed") !== "on") { setError("Confirm that you reviewed the exact source and decision impact before proceeding."); return; }
    let completedOn: string | null = null;
    let validUntil: string | null = null;
    if (review.kind === "compliance-checks" && review.decision === "VERIFIED") {
      const isDate = (value: string) => {
        const parsed = new Date(`${value}T00:00:00Z`);
        return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
      };
      completedOn = String(form.get("completedOn") ?? "");
      if (!isDate(completedOn)) { setError("Enter the actual verified completion date."); return; }
      if (rule?.validityMonths == null) {
        validUntil = String(form.get("validUntil") ?? "") || null;
        if (validUntil && (!isDate(validUntil) || validUntil < completedOn)) { setError("Enter a valid expiry on or after completion, or leave it blank."); return; }
      }
    }
    const payload = { schoolId: review.schoolId, expectedVersion: review.expectedVersion, reason,
      ...(review.action === "decision" ? { decision: review.decision } : {}),
      ...(review.kind === "compliance-checks" ? { completedOn, validUntil } : {}),
    };
    reviewInFlight.current = true;
    try {
      const ok = await mutate(`/api/v1/people/${review.kind}/${review.id}/${review.action}`, { method: "POST", body: JSON.stringify(payload) },
        review.kind !== "public-profiles" ? "Independent evidence decision recorded." : review.action === "submit" ? "Public profile submitted to a different authority." : "Public disclosure decision recorded.");
      if (ok) { setReview(null); requestAnimationFrame(() => noticeTarget.current?.focus()); }
      else requestAnimationFrame(() => reviewReason.current?.focus());
    } finally { reviewInFlight.current = false; }
  }

  if (!data) return <section className="people-state"><PeopleIcon /><h1>{error ? "People records are unavailable" : "Opening the people workspace…"}</h1>{error && <p>{error}</p>}</section>;
  const activeStaff = data.staff.filter((item) => item.status === "ACTIVE").length;
  const pendingEvidence = data.qualifications.filter((item) => item.verificationStatus === "PENDING").length + data.complianceChecks.filter((item) => item.status === "PENDING").length;
  const pendingPublic = data.publicProfiles.filter((item) => item.status === "PENDING_APPROVAL").length;
  const designationName = (id?: string) => data.designations.find((item) => item.id === id)?.name || "No designation";
  const departmentName = (id?: string) => data.departments.find((item) => item.id === id)?.name || "No department";
  const campusName = (id?: string) => data.campuses.find((item) => item.id === id)?.name || "No campus";
  const staffName = (id: string) => data.staff.find((item) => item.id === id)?.displayName || "Unknown staff";

  return <section className="people-workspace">
    <header className="people-hero"><div><span>Employment-backed authority</span><h1>Know who serves the school—and why they have access.</h1><p>Profiles, designations, assignments, qualifications, safeguarding checks, portal links, and public disclosures remain configurable without becoming the same record.</p></div><aside><PeopleIcon /><strong>{activeStaff}</strong><span>Active staff profiles</span></aside></header>
    <div className="people-trust"><span>Employment ≠ login role</span><span>{pendingEvidence} evidence decisions waiting</span><span>{pendingPublic} public snapshots waiting</span><span>Private HR data never reaches the public API</span></div>
    {(error || notice) && <div ref={noticeTarget} tabIndex={-1} className={`people-banner ${error ? "is-error" : "is-success"}`} role={error ? "alert" : "status"}>{error || notice}</div>}
    {ending && <form ref={endingPanel} className="people-ending-confirmation" onSubmit={confirmEnding} aria-labelledby="people-ending-title" aria-describedby="people-ending-help" aria-busy={busy} onKeyDown={(event) => { if (event.key === "Escape") { event.preventDefault(); cancelEnding(); } }}>
      <h2 id="people-ending-title">{ending.title}</h2>
      <p id="people-ending-help">{ending.description}</p>
      <div className="people-ending-record"><strong>{ending.displayName}</strong><span>{ending.employeeCode} · {ending.kind === "staff" ? "Employment" : "Assignment"} · from {ending.startsOn}</span></div>
      <p className="people-ending-warning">This action takes effect when confirmed. Choosing a future date does not schedule the action or delay access/publication changes. Nothing changes if you cancel.</p>
      <label>Approved end date<input ref={endingDate} type="date" name="endDate" min={ending.startsOn} defaultValue={ending.endDate} required disabled={busy} /></label>
      <label>Reason for ending<textarea ref={endingReason} name="reason" required minLength={3} maxLength={500} rows={3} disabled={busy} /></label>
      <label className="people-ending-acknowledgement"><input type="checkbox" name="handoffConfirmed" required disabled={busy} /> I reviewed the person, date, required handoff and access/publication impact.</label>
      <footer><button type="submit" disabled={busy}>{busy ? "Saving…" : "Confirm ending"}</button><button type="button" className="is-secondary" disabled={busy} onClick={cancelEnding}>Cancel ending</button></footer>
    </form>}
    {review && <form ref={reviewPanel} tabIndex={-1} className="people-ending-confirmation people-review-confirmation" onSubmit={confirmReview} aria-labelledby="people-review-title" aria-describedby="people-review-help" aria-busy={busy} onKeyDown={(event) => { if (event.key === "Escape") { event.preventDefault(); cancelReview(); } }}>
      <h2 id="people-review-title">{review.title}</h2>
      <p id="people-review-help">{review.description}</p>
      <div className="people-ending-record"><strong>{review.displayName}</strong><span>{review.employeeCode} · record version {review.expectedVersion} · {review.kind === "public-profiles" ? "Public snapshot review" : "Restricted evidence review"}</span>{review.createdBy && <span>Created by {review.createdBy} · internal review context, not a publication field</span>}</div>
      <dl className="people-review-fields">{review.fields.map((field) => <div key={field.label}><dt>{field.label}</dt><dd>{field.value}</dd></div>)}</dl>
      <p className="people-ending-warning">Evidence and publication decisions require a different authorized membership from the creator. This review does not bypass the server&apos;s permissions, source/version checks or independent approval. Nothing changes if you cancel.</p>
      {review.kind === "compliance-checks" && review.decision === "VERIFIED" && <>
        <label>Verified completion date<input type="date" name="completedOn" defaultValue={today()} required disabled={busy} /></label>
        {review.complianceRule?.validityMonths != null
          ? <p>The server calculates expiry from the completion date using the configured {review.complianceRule.validityMonths}-month rule. No manual expiry is submitted. If the policy changed elsewhere, its current server configuration applies.</p>
          : <label>Valid until (optional)<input type="date" name="validUntil" disabled={busy} /><span>Leave blank if the source has no expiry. Any expiry must be on or after completion.</span></label>}
      </>}
      <label>Reason for this review<textarea ref={reviewReason} name="reason" required minLength={3} maxLength={500} rows={3} disabled={busy} /></label>
      <label className="people-ending-acknowledgement"><input type="checkbox" name="sourceConfirmed" required disabled={busy} /> I reviewed the exact source, selected decision and its publication or verification impact.</label>
      <footer><button type="submit" disabled={busy}>{busy ? "Saving…" : "Confirm review"}</button><button type="button" className="is-secondary" disabled={busy} onClick={cancelReview}>Cancel review</button></footer>
    </form>}
    <fieldset className="people-content" disabled={busy || Boolean(ending) || Boolean(review) || invitationBlocked}>
    <nav className="people-tabs" aria-label="People workspace sections">{(["directory", "configuration", "evidence", "publication"] as Tab[]).map((item) => <button key={item} type="button" aria-pressed={tab === item} className={tab === item ? "is-active" : ""} onClick={() => setTab(item)}>{label(item)}{item === "evidence" && pendingEvidence > 0 ? <b>{pendingEvidence}</b> : null}{item === "publication" && pendingPublic > 0 ? <b>{pendingPublic}</b> : null}</button>)}</nav>

    {tab === "directory" && <div className="people-directory">
      <aside className="people-list"><header><span>School people</span><button onClick={() => { setEditProfile(false); setSelectedId(""); }}><PlusIcon /> New</button></header>{data.staff.length ? data.staff.map((item) => { const assignment = data.assignments.find((row) => row.staffProfileId === item.id && row.status === "ACTIVE" && row.primaryAssignment); return <button key={item.id} type="button" aria-pressed={selectedId === item.id} className={selectedId === item.id ? "is-active" : ""} onClick={() => { setSelectedId(item.id); setEditProfile(false); }}><strong>{item.displayName}</strong><small>{item.employeeCode} · {designationName(assignment?.designationId)}</small><em data-state={item.status}>{label(item.status)}</em></button>; }) : <div className="people-mini-empty"><PeopleIcon /><p>No staff profiles yet.</p></div>}</aside>
      <section className="people-detail" aria-label="Staff profile">{selected && !editProfile ? <>
        <header><div><span>{selected.employeeCode}</span><h2>{selected.displayName}</h2><p>{activeAssignment ? `${designationName(activeAssignment.designationId)} · ${departmentName(activeAssignment.departmentId)}` : "No active primary assignment"}</p></div><em data-state={selected.status}>{label(selected.status)}</em></header>
        <section className="people-profile-grid"><article><span>Employment</span><strong>Joined {new Date(`${selected.joinedOn}T00:00:00`).toLocaleDateString("en-IN")}</strong><small>{selected.endedOn ? `Ended ${selected.endedOn}` : "Effective employment record"}</small></article><article><span>Portal identity</span><strong>{selected.userAccountId ? "Linked" : "Not linked"}</strong><small>{selected.userAccountId ? "Access still depends on separate roles" : "Record can exist without login"}</small></article><article><span>Work contact</span><strong>{selected.workPhone || selected.workEmail || "Not recorded"}</strong><small>Visible only inside authorized people workspace</small></article></section>
        <section className="people-assignment-list"><header><h3>Assignments</h3><span>{selectedAssignments.length} historical record{selectedAssignments.length === 1 ? "" : "s"}</span></header>{selectedAssignments.length ? selectedAssignments.map((item) => <article key={item.id}><div><strong>{designationName(item.designationId)}</strong><small>{departmentName(item.departmentId)} · {campusName(item.campusId)}</small></div><span>{label(item.employmentType)}{item.employerKind === "AGENCY" ? ` · ${item.agencyName}` : ""}</span><em data-state={item.status}>{label(item.status)}</em>{["ACTIVE", "PLANNED"].includes(item.status) && data.canManage && <button disabled={busy} onClick={() => void endRecord("assignment", item.id, item.rowVersion, today())}>End</button>}</article>) : <p>No assignment has been added.</p>}</section>
        {data.canManage && selected.status !== "ENDED" && <div className="people-actions"><button onClick={() => setEditProfile(true)}>Edit profile</button>{selected.status !== "ENDED" && <button className="is-danger" disabled={busy} onClick={() => void endRecord("staff", selected.id, selected.rowVersion, today())}>Offboard safely</button>}</div>}
        {data.canManage && selected.status === "ACTIVE" && <details className="people-add-panel"><summary>Add an effective-dated assignment</summary><form onSubmit={saveAssignment}><label>Designation<select name="designationId" required><option value="">Choose…</option>{data.designations.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>Department<select name="departmentId"><option value="">No department</option>{data.departments.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>Campus<select name="campusId" required><option value="">Choose…</option>{data.campuses.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>Employment type<select name="employmentType" defaultValue="FULL_TIME">{["FULL_TIME", "PART_TIME", "FIXED_TERM", "CONTRACT", "OUTSOURCED", "VOLUNTEER"].map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label>Employer<select name="employerKind" defaultValue="SCHOOL"><option value="SCHOOL">School</option><option value="AGENCY">Agency</option></select></label><label>Agency name<input name="agencyName" maxLength={160} /></label><label>Starts on<input name="startsOn" type="date" defaultValue={today()} required /></label><label>Status<select name="status" defaultValue="ACTIVE"><option value="ACTIVE">Active</option><option value="PLANNED">Planned</option></select></label><label className="people-check"><input name="primaryAssignment" type="checkbox" defaultChecked /> Primary assignment</label><label className="people-wide">Reason<input name="reason" defaultValue="Employment assignment approved by school authority" maxLength={500} required /></label><button disabled={busy}>Add assignment</button></form></details>}
      </> : data.canManage ? <form key={editProfile && selected ? selected.id : "new"} className="people-record-form" onSubmit={saveStaff}><header><PeopleIcon /><div><span>{editProfile ? "Protected update" : "New employment record"}</span><h2>{editProfile ? `Edit ${selected?.displayName}` : "Create staff profile"}</h2><p>Employee code and joining date become immutable identifiers. A portal account is optional.</p></div></header><div><label>Employee code<input name="employeeCode" defaultValue={selected?.employeeCode || ""} maxLength={50} readOnly={editProfile} required /></label><label>Legal name<input name="legalName" defaultValue={selected?.legalName || ""} maxLength={160} required /></label><label>Display name<input name="displayName" defaultValue={selected?.displayName || ""} maxLength={120} required /></label><label>Primary language<select name="primaryLanguage" defaultValue={selected?.primaryLanguage || "EN"}><option value="EN">English</option><option value="HI">Hindi</option></select></label><label>Work phone<input name="workPhone" defaultValue={selected?.workPhone || ""} maxLength={30} /></label><label>Work email<input name="workEmail" type="email" defaultValue={selected?.workEmail || ""} maxLength={254} /></label><label>Portal account<select name="userAccountId" defaultValue={selected?.userAccountId || ""}><option value="">No linked login</option>{data.accounts.map((item) => <option key={item.membershipId} value={item.userAccountId}>{item.displayName} · {item.roles.map(label).join(", ")}</option>)}</select></label><label>Joined on<input name="joinedOn" type="date" defaultValue={selected?.joinedOn || today()} readOnly={editProfile} required /></label><label>Status<select name="status" defaultValue={selected?.status === "INACTIVE" ? "INACTIVE" : "ACTIVE"}><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></label><label className="people-wide">Change reason<input name="reason" defaultValue={editProfile ? "Authorized staff profile correction" : "Staff profile created from approved school record"} maxLength={500} required /></label></div><footer><button type="button" onClick={() => { setEditProfile(false); if (!selectedId) setSelectedId(data.staff[0]?.id || ""); }}>Cancel</button><button disabled={busy}>{editProfile ? "Save protected update" : "Create profile"} <ArrowUpRight /></button></footer></form> : <div className="people-empty"><PeopleIcon /><h2>Select a staff profile</h2></div>}</section>
    </div>}

    {tab === "configuration" && <div className="people-config-grid">
      <section><header><SettingsIcon /><div><span>Organisational structure</span><h2>Departments</h2></div></header><div className="people-config-list">{data.departments.map((item) => <article key={item.id}><div><strong>{item.name}</strong><small>{item.code} · {label(item.status)}</small></div>{data.canManage && <button onClick={() => setEditDepartmentId(item.id)}>Edit</button>}</article>)}</div>{data.canManage && <form key={departmentDraft?.id || "department-new"} onSubmit={saveDepartment}><h3>{departmentDraft ? "Edit department" : "Add department"}</h3><label>Code<input name="code" defaultValue={departmentDraft?.code || ""} readOnly={!!departmentDraft} required /></label><label>Name<input name="name" defaultValue={departmentDraft?.name || ""} required /></label><label>Description<textarea name="description" defaultValue={departmentDraft?.description || ""} /></label><label>Status<select name="status" defaultValue={departmentDraft?.status || "ACTIVE"}><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></label><label>Reason<input name="reason" defaultValue={departmentDraft ? "Department configuration updated" : "Department configured by school authority"} required /></label><footer>{departmentDraft && <button type="button" onClick={() => setEditDepartmentId("")}>Cancel</button>}<button disabled={busy}>Save</button></footer></form>}</section>
      <section><header><PeopleIcon /><div><span>Employment meaning</span><h2>Designations</h2></div></header><div className="people-config-list">{data.designations.map((item) => <article key={item.id}><div><strong>{item.name}</strong><small>{label(item.staffCategory)} · {item.teachingRole ? "Teaching eligible" : "Non-teaching"}{item.eligibleAsTransportDriver ? " · Driver eligible" : ""}{item.eligibleAsTransportAttendant ? " · Attendant eligible" : ""}</small></div>{data.canManage && <button onClick={() => setEditDesignationId(item.id)}>Edit</button>}</article>)}</div>{data.canManage && <form key={designationDraft?.id || "designation-new"} onSubmit={saveDesignation}><h3>{designationDraft ? "Edit designation" : "Add designation"}</h3><label>Code<input name="code" defaultValue={designationDraft?.code || ""} readOnly={!!designationDraft} required /></label><label>Name<input name="name" defaultValue={designationDraft?.name || ""} required /></label><label>Staff category<select name="staffCategory" defaultValue={designationDraft?.staffCategory || "TEACHING"}>{categories.map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label>CBSE category<select name="cbseStaffCategory" defaultValue={designationDraft?.cbseStaffCategory || ""}><option value="">Not mapped</option>{cbseCategories.map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label>Display order<input name="displayOrder" type="number" min="0" max="500" defaultValue={designationDraft?.displayOrder ?? 0} /></label><label>Status<select name="status" defaultValue={designationDraft?.status || "ACTIVE"}><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></label><label className="people-check"><input name="teachingRole" type="checkbox" defaultChecked={designationDraft?.teachingRole} /> Eligible for teaching allocation</label><label className="people-check"><input name="leadershipRole" type="checkbox" defaultChecked={designationDraft?.leadershipRole} /> Leadership designation</label><label className="people-check"><input name="eligibleAsTransportDriver" type="checkbox" defaultChecked={designationDraft?.eligibleAsTransportDriver} /> Eligible for transport driver duty</label><label className="people-check"><input name="eligibleAsTransportAttendant" type="checkbox" defaultChecked={designationDraft?.eligibleAsTransportAttendant} /> Eligible for transport attendant duty</label><label>Description<textarea name="description" defaultValue={designationDraft?.description || ""} /></label><label>Reason<input name="reason" defaultValue={designationDraft ? "Designation configuration updated" : "Designation configured by school authority"} required /></label><footer>{designationDraft && <button type="button" onClick={() => setEditDesignationId("")}>Cancel</button>}<button disabled={busy}>Save</button></footer></form>}</section>
      <section><header><SettingsIcon /><div><span>Safeguarding configuration</span><h2>Compliance rules</h2></div></header><div className="people-config-list">{data.complianceTypes.map((item) => <article key={item.id}><div><strong>{item.name}</strong><small>{item.applicableStaffCategory ? label(item.applicableStaffCategory) : "All staff"} · {item.requiredForActiveService ? "Required" : "Advisory"}</small></div>{data.canManage && <button onClick={() => setEditComplianceTypeId(item.id)}>Edit</button>}</article>)}</div>{data.canManage && <form key={complianceTypeDraft?.id || "compliance-new"} onSubmit={saveComplianceType}><h3>{complianceTypeDraft ? "Edit compliance rule" : "Add compliance rule"}</h3><label>Code<input name="code" defaultValue={complianceTypeDraft?.code || ""} readOnly={!!complianceTypeDraft} required /></label><label>Name<input name="name" defaultValue={complianceTypeDraft?.name || ""} required /></label><label>Applicable category<select name="applicableStaffCategory" defaultValue={complianceTypeDraft?.applicableStaffCategory || ""}><option value="">All staff</option>{categories.map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label>Validity (months)<input name="validityMonths" type="number" min="1" max="240" defaultValue={complianceTypeDraft?.validityMonths || ""} /></label><label>Status<select name="status" defaultValue={complianceTypeDraft?.status || "ACTIVE"}><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></label><label className="people-check"><input name="requiredForActiveService" type="checkbox" defaultChecked={complianceTypeDraft?.requiredForActiveService} /> Required for active service</label><label>Description<textarea name="description" defaultValue={complianceTypeDraft?.description || ""} /></label><label>Reason<input name="reason" defaultValue={complianceTypeDraft ? "Compliance configuration updated" : "Compliance rule configured by school authority"} required /></label><footer>{complianceTypeDraft && <button type="button" onClick={() => setEditComplianceTypeId("")}>Cancel</button>}<button disabled={busy}>Save</button></footer></form>}</section>
    </div>}

    {tab === "evidence" && <div className="people-evidence"><header><div><span>Restricted workspace</span><h2>Qualification and safeguarding evidence</h2><p>Only safe references and restricted notes live here. Raw HR documents require the private HR file context before launch.</p></div><select aria-label="Staff member for evidence" value={selectedId} onChange={(event) => setSelectedId(event.target.value)}><option value="">Choose staff…</option>{data.staff.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.displayName} · {item.employeeCode}</option>)}</select></header>{selected ? <div className="people-evidence-grid"><section><h3>Qualifications</h3>{selectedQualifications.map((item) => <article key={item.id}><div><strong>{item.name}</strong><small>{label(item.level)} · {item.institutionName || "Institution not recorded"}</small></div><em data-state={item.verificationStatus}>{label(item.verificationStatus)}</em>{item.verificationStatus === "PENDING" && data.canReviewCompliance && <div><button disabled={busy} onClick={() => void decideEvidence("qualifications", item.id, item.rowVersion, "VERIFIED")}>Verify</button><button disabled={busy} onClick={() => void decideEvidence("qualifications", item.id, item.rowVersion, "REJECTED")}>Reject</button></div>}</article>)}{data.canManage && <form onSubmit={saveQualification}><h4>Add qualification evidence</h4><label>Name<input name="name" required /></label><label>Level<select name="level" defaultValue="GRADUATE">{["CERTIFICATE", "DIPLOMA", "GRADUATE", "POSTGRADUATE", "DOCTORATE", "PROFESSIONAL", "OTHER"].map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label>Subject specialisation<input name="subjectSpecialisation" /></label><label>Institution<input name="institutionName" /></label><label>Awarding body<input name="awardingBody" /></label><label>Completion year<input name="completedYear" type="number" min="1950" max="2100" /></label><label className="people-wide">Reason<input name="reason" defaultValue="Qualification copied from approved staff service record" required /></label><button disabled={busy}>Submit evidence</button></form>}</section><section><h3>Compliance checks</h3>{selectedChecks.map((item) => <article key={item.id}><div><strong>{data.complianceTypes.find((type) => type.id === item.complianceTypeId)?.name || "Compliance check"}</strong><small>{item.safeReference || "No safe reference"}{item.validUntil ? ` · valid to ${item.validUntil}` : ""}</small></div><em data-state={item.status}>{label(item.status)}</em>{item.status === "PENDING" && data.canReviewCompliance && <div><button disabled={busy} onClick={() => void decideEvidence("compliance-checks", item.id, item.rowVersion, "VERIFIED")}>Verify</button><button disabled={busy} onClick={() => void decideEvidence("compliance-checks", item.id, item.rowVersion, "WAIVED")}>Waive</button><button disabled={busy} onClick={() => void decideEvidence("compliance-checks", item.id, item.rowVersion, "REJECTED")}>Reject</button></div>}</article>)}{data.canReviewCompliance && <form onSubmit={saveCompliance}><h4>Add restricted check</h4><label>Rule<select name="complianceTypeId" required><option value="">Choose…</option>{data.complianceTypes.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>Safe reference<input name="safeReference" placeholder="Register number—not document content" /></label><label className="people-wide">Restricted note<textarea name="restrictedNote" /></label><label className="people-wide">Reason<input name="reason" defaultValue="Safeguarding evidence registered for review" required /></label><button disabled={busy}>Submit check</button></form>}</section></div> : <div className="people-empty"><PeopleIcon /><h2>Choose an active staff member</h2><p>Evidence is always attached to an employment record.</p></div>}</div>}

    {tab === "publication" && <div className="people-publication">
      <section>
        <header><span>Public boundary</span><h2>Approved staff snapshots</h2><p>Publishing copies only the chosen name, designation, verified qualification summary, CBSE category, and short biography. It never exposes work contact, account, or safeguarding notes. Any current published snapshot stays live until a new revision receives independent approval.</p></header>
        {data.publicProfiles.length ? data.publicProfiles.map((item) => <article key={item.id}>
          <div><span>{staffName(item.staffProfileId)} · revision {item.revisionNumber}</span><h3>{item.displayName}</h3><p>{item.designation}{item.qualificationSummary ? ` · ${item.qualificationSummary}` : ""}</p><small>Created by {item.creatorName}{item.deciderName ? ` · decided by ${item.deciderName}` : ""}</small></div>
          <em data-state={item.status}>{label(item.status)}</em>
          <div>
            {item.status === "DRAFT" && data.canManage && <><button onClick={() => { setEditPublicId(item.id); setSupersedesId(""); }}>Edit</button><button disabled={busy} onClick={() => void publicWorkflow(item, "submit")}>Submit</button></>}
            {item.status === "PENDING_APPROVAL" && data.canPublish && <><button disabled={busy} onClick={() => void publicWorkflow(item, "decision", "PUBLISHED")}>Publish</button><button disabled={busy} onClick={() => void publicWorkflow(item, "decision", "REJECTED")}>Reject</button></>}
            {data.canManage && canCreatePublicRevision(item, data.publicProfiles) && <button type="button" onClick={() => { setSupersedesId(item.id); setEditPublicId(""); }}>{item.status === "REJECTED" ? "Correct rejected revision" : "Create next revision"}</button>}
          </div>
        </article>) : <div className="people-mini-empty"><PeopleIcon /><p>No public staff snapshot exists. The public page will show an honest empty state.</p></div>}
      </section>
      {data.canManage && <form key={publicDraft?.id || superseded?.id || "public-new"} onSubmit={savePublicProfile}>
        <header><PeopleIcon /><div><span>Unpublished draft</span><h2>{publicDraft ? `Edit revision ${publicDraft.revisionNumber}` : superseded ? `Revision ${superseded.revisionNumber + 1}` : "New public profile"}</h2></div></header>
        <label>Previous approved or rejected revision<select value={publicDraft?.supersedesRevisionId || supersedesId} onChange={(event) => { setSupersedesId(event.target.value); setEditPublicId(""); }} disabled={!!publicDraft}><option value="">First revision</option>{publicRevisionSources.map((item) => <option key={item.id} value={item.id}>{item.displayName} · r{item.revisionNumber} · {label(item.status)}</option>)}</select></label>
        <label>Staff profile<select name="staffProfileId" defaultValue={publicDraft?.staffProfileId || superseded?.staffProfileId || selectedId} disabled={!!publicDraft || !!superseded} required><option value="">Choose…</option>{data.staff.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}</select></label>
        <label>Public display name<input name="displayName" defaultValue={publicDraft?.displayName || superseded?.displayName || ""} required /></label>
        <label>Designation snapshot<input name="designation" defaultValue={publicDraft?.designation || superseded?.designation || ""} required /></label>
        <label>Verified qualification summary<input name="qualificationSummary" defaultValue={publicDraft?.qualificationSummary || superseded?.qualificationSummary || ""} /></label>
        <label>CBSE disclosure category<select name="cbseStaffCategory" defaultValue={publicDraft?.cbseStaffCategory || superseded?.cbseStaffCategory || ""}><option value="">Not mapped</option>{cbseCategories.map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label>
        <label>Display order<input name="displayOrder" type="number" min="0" max="500" defaultValue={publicDraft?.displayOrder ?? superseded?.displayOrder ?? 0} /></label>
        <label>Short biography<textarea name="shortBio" defaultValue={publicDraft?.shortBio || superseded?.shortBio || ""} /></label>
        <label>Reason<input name="reason" defaultValue={publicDraft ? "Public draft corrected before submission" : superseded ? "New public revision after staff information review" : "Public profile draft created after privacy review"} required /></label>
        <footer>{(publicDraft || superseded) && <button type="button" onClick={() => { setSupersedesId(""); setEditPublicId(""); }}>Cancel</button>}<button disabled={busy}>{publicDraft ? "Save draft" : "Create private draft"}</button></footer>
      </form>}
    </div>}
    </fieldset>
    {tab === "directory" && selected && !editProfile && <StaffAccountInvitationsPanel key={data.schoolId + ":" + selected.id} schoolId={data.schoolId} staffId={selected.id} displayName={selected.displayName} employeeCode={selected.employeeCode} staffVersion={selected.rowVersion} linked={Boolean(selected.userAccountId)} disabled={busy || Boolean(ending) || Boolean(review)} onGuardChange={setInvitationBlocked} />}
  </section>;
}
