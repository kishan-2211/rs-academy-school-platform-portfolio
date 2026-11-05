"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useCodeLabel, useLanguage, useT } from "@/components/i18n/language-provider";
import { localizedContent } from "@/lib/i18n/locale";
import { Calendar, Heart, Message, Shield, Sparkles } from "@/components/icons";
import { MotionIllustration } from "@/components/motion-illustration";
import { PushNotificationControl } from "@/components/push-notification-control";
import { StudentPortalAccessCard } from "@/components/family/student-portal-access-card";
import { FeeReceiptTimingText } from "@/components/fee-receipt-timing-text";
import { ReceiptPdfActions } from "@/components/receipt-pdf-actions";
import { FamilyGatewayPaymentPanel } from "@/components/family/gateway-payment-panel";
import { FamilyFeeAccountSelector } from "@/components/family/fee-account-selector";
import type { FamilyFeeAccount } from "@/lib/family-fee-accounts";
import { FamilyRefundHistory, type FamilyRefund } from "@/components/family/refund-history";
import receiptStyles from "@/components/receipt-pdf-actions.module.css";
import type { FeeReceiptTiming } from "@/lib/fee-receipt-timing";
import {
  AcademicIcon,
  MoneyIcon,
  PeopleIcon,
  ReportIcon,
} from "@/components/workspace/workspace-icons";

type FamilyChild = {
  learnerId: string;
  learnerReference: string;
  legalName: string;
  preferredName: string | null;
  schoolName: string;
  campusName: string;
  academicSessionName: string;
  gradeCode: string;
  sectionName: string | null;
  rollNumber: string | null;
  relationshipCode: string;
};

type ApiProblem = { detail?: string };
type FamilySchedule = {
  learnerId: string;
  timetablePlanId: string | null;
  timetableName: string | null;
  publishedAt: string | null;
  entries: Array<{
    id: string;
    dayOfWeek: number;
    sequenceNumber: number;
    periodName: string;
    startsAt: string;
    endsAt: string;
    subjectName: string;
    subjectShortName: string | null;
    roomLabel: string | null;
  }>;
};
type FamilyCalendar = {
  learnerId: string;
  schoolId: string;
  campusId: string;
  academicSessionId: string;
  academicSessionName: string;
  sessionStartsOn: string;
  sessionEndsOn: string;
  timeZone: string;
  today: string;
  days: Array<{
    calendarDayId: string;
    noticeRevisionId: string;
    calendarDate: string;
    dayType: string;
    noticeKind: string;
    titleEn: string;
    titleHi: string;
    bodyEn: string;
    bodyHi: string;
    attendanceRequired: boolean;
    publishedAt: string;
  }>;
};
type FamilyCurriculum = {
  learnerId: string;
  publicationId: string | null;
  publicationVersion: number | null;
  sessionName: string;
  gradeCode: string;
  publishedAt: string | null;
  items: Array<{
    id: string;
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
  }>;
};
type FamilyAttendance = {
  learnerId: string;
  from: string;
  to: string;
  summary: {
    recordedMarks: number;
    earnedCredits: number;
    attendancePercent: number | null;
    warningThresholdPercent: number | null;
    belowConfiguredWarning: boolean;
    stateCounts: Record<string, number>;
  };
  records: Array<{
    markId: string;
    date: string;
    registerMode: string;
    periodName: string | null;
    startsAt: string | null;
    endsAt: string | null;
    stateCode: string;
    creditWeight: number;
    explanationAllowedUntil: string;
    canExplain: boolean;
    explanationId: string | null;
    explanationStatus: string | null;
  }>;
};
type FamilyResults = {
  learnerId: string;
  reportCards: Array<{
    reportCardId: string;
    publicationId: string;
    publicationVersion: number;
    reportNumber: string;
    sessionName: string;
    termName: string;
    sectionName: string;
    overallPercentage: number | null;
    overallGradeCode: string | null;
    overallOutcome: string | null;
    publishedAt: string;
    snapshotSha256: string;
    report: {
      subjects?: Array<{
        subjectId: string;
        name: string;
        percentage: number | null;
        gradeCode: string | null;
        outcome: string;
      }>;
    };
  }>;
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
type FamilyAssignments = {
  learnerId: string;
  assignments: Array<{
    assignmentId: string;
    title: string;
    instructions: string;
    subjectName: string;
    categoryName: string | null;
    resourceLabel: string | null;
    resourceUrl: string | null;
    availableFrom: string;
    dueAt: string;
    submissionMode: string;
    maximumPoints?: number | null;
    allowAttachments: boolean;
    maxAttachmentCount: number;
    attachmentRequired: boolean;
    allowLate: boolean;
    lateUntil: string | null;
    status: string;
    publicationRevision: number;
    attachments: PrivateFile[];
    submission: {
      submissionId: string;
      state: string;
      responseText: string | null;
      responseLink: string | null;
      submittedAt: string | null;
      teacherFeedback: string | null;
      pointsAwarded?: number | null;
      reviewedAt: string | null;
      rowVersion: number;
      attachments: PrivateFile[];
    };
  }>;
};
type FamilyFees = {
  learnerId: string;
  accountId: string | null;
  academicSessionName: string;
  currencyCode: string;
  onlinePaymentStatus: string;
  totalCharges: number;
  totalCredits: number;
  balance: number;
  refunds?: FamilyRefund[];
  charges: Array<{
    id: string;
    feeHeadCode: string;
    feeHeadName: string;
    label: string;
    dueOn: string;
    amount: number;
    concessionAmount: number;
    rteExemptionAmount: number;
    paidAmount: number;
    outstandingAmount: number;
    reservedAmount: number;
    allowPartial: boolean;
  }>;
  receipts: Array<{
    paymentId: string;
    receiptNumber: string;
    paymentMethod: string;
    amount: number;
    receivedOn: string;
    status: string;
    issuedAt: string;
    timing?: FeeReceiptTiming;
  }>;
  statement: Array<{
    id: string;
    entryType: string;
    direction: string;
    amount: number;
    effectiveOn: string;
    description: string;
    createdAt: string;
  }>;
};
type FamilyCommunications = {
  schoolId: string;
  learnerId: string;
  items: Array<{
    recipientId: string;
    announcementId: string;
    learnerId: string;
    category: string;
    priority: string;
    audienceKind: string;
    languageMode: string;
    preferredLanguage: string;
    titleEn: string | null;
    titleHi: string | null;
    bodyEn: string | null;
    bodyHi: string | null;
    requiresAcknowledgement: boolean;
    scheduledFor: string;
    expiresAt: string | null;
    expired: boolean;
    readAt: string | null;
    acknowledgedAt: string | null;
    deliveries: Array<{ channel: string; status: string }>;
    rowVersion: number;
  }>;
};
type FamilyTransport = {
  learnerId: string;
  journeys: Array<{
    journeyKind: string;
    routeName: string;
    serviceLabel: string;
    operatingDays: string;
    stopLabel: string;
    scheduledTime: string;
    boardingWindowMinutes: number;
    vehicleLabel: string | null;
    vehicleRegistration: string | null;
    effectiveFrom: string;
    effectiveUntil: string | null;
    latestTrip: {
      tripId: string;
      serviceDate: string;
      status: string;
      lastUpdatedAt: string;
      learnerStatus: string | null;
      learnerStatusAt: string | null;
    } | null;
  }>;
};
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const weekDays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

function formatCode(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatMoney(amount: number, currency: string) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
  }).format(amount);
}

function calendarDateLabel(value: string) {
  return new Intl.DateTimeFormat("en-IN", {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(`${value}T12:00:00`));
}

async function problemMessage(response: Response, fallback: string) {
  try {
    const problem = (await response.json()) as ApiProblem;
    return problem.detail || fallback;
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

export function FamilyPortal() {
  const t = useT("parent");
  const displayCode = useCodeLabel(formatCode);
  const { locale } = useLanguage();
  const [children, setChildren] = useState<FamilyChild[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [schedule, setSchedule] = useState<FamilySchedule | null>(null);
  const [scheduleLoading, setScheduleLoading] = useState(false);
  const [scheduleError, setScheduleError] = useState("");
  const [calendar, setCalendar] = useState<FamilyCalendar | null>(null);
  const [calendarLoading, setCalendarLoading] = useState(false);
  const [calendarError, setCalendarError] = useState("");
  const [curriculum, setCurriculum] = useState<FamilyCurriculum | null>(null);
  const [curriculumLoading, setCurriculumLoading] = useState(false);
  const [curriculumError, setCurriculumError] = useState("");
  const [attendance, setAttendance] = useState<FamilyAttendance | null>(null);
  const [attendanceLoading, setAttendanceLoading] = useState(false);
  const [attendanceError, setAttendanceError] = useState("");
  const [attendanceNotice, setAttendanceNotice] = useState("");
  const [explanations, setExplanations] = useState<Record<string, string>>({});
  const [explanationBusy, setExplanationBusy] = useState("");
  const [results, setResults] = useState<FamilyResults | null>(null);
  const [resultsLoading, setResultsLoading] = useState(false);
  const [resultsError, setResultsError] = useState("");
  const [assignments, setAssignments] = useState<FamilyAssignments | null>(null);
  const [assignmentsLoading, setAssignmentsLoading] = useState(false);
  const [assignmentsError, setAssignmentsError] = useState("");
  const [assignmentsNotice, setAssignmentsNotice] = useState("");
  const [assignmentBusy, setAssignmentBusy] = useState("");
  const [assignmentDrafts, setAssignmentDrafts] = useState<
    Record<string, { text: string; link: string }>
  >({});
  const [fees, setFees] = useState<FamilyFees | null>(null);
  const [feesLoading, setFeesLoading] = useState(false);
  const [feesError, setFeesError] = useState("");
  const [selectedFeeAccount, setSelectedFeeAccount] = useState<FamilyFeeAccount | null>(null);
  const [currentFeeAccount, setCurrentFeeAccount] = useState<FamilyFeeAccount | null>(null);
  const [knownFeeAccounts, setKnownFeeAccounts] = useState<Record<string, FamilyFeeAccount>>({});
  const [paymentBlocked, setPaymentBlocked] = useState(false);
  const paymentGuard = useRef<{ learnerId: string; accountId: string } | null>(null);
  const [communications, setCommunications] = useState<FamilyCommunications | null>(null);
  const [communicationsLoading, setCommunicationsLoading] = useState(false);
  const [communicationsError, setCommunicationsError] = useState("");
  const [communicationsNotice, setCommunicationsNotice] = useState("");
  const [communicationBusy, setCommunicationBusy] = useState("");
  const [transport, setTransport] = useState<FamilyTransport | null>(null);
  const [transportLoading, setTransportLoading] = useState(false);
  const [transportError, setTransportError] = useState("");

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      fetch(`${apiUrl}/api/v1/context`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/family/children`, { credentials: "include" }),
    ])
      .then(async ([contextResponse, childrenResponse]) => {
        if (!contextResponse.ok) {
          // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Discard the current family document when its authenticated access cannot be verified.
          window.location.assign("/portal");
          return;
        }
        const context = (await contextResponse.json()) as { capabilities: string[] };
        if (!context.capabilities.includes("portal.guardian")) {
          // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document at the verified persona boundary without retaining family state.
          window.location.assign("/workspace");
          return;
        }
        if (!childrenResponse.ok) {
          throw new Error(
            await problemMessage(childrenResponse, "Your linked learners are unavailable."),
          );
        }
        const result = (await childrenResponse.json()) as { children: FamilyChild[] };
        if (cancelled) return;
        setChildren(result.children);
        setSelectedId((current) => current || result.children[0]?.learnerId || "");
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
  }, []);

  const selected = useMemo(
    () => children.find((child) => child.learnerId === selectedId) ?? children[0],
    [children, selectedId],
  );

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    Promise.resolve()
      .then(() => {
        if (cancelled) return null;
        setScheduleLoading(true);
        setScheduleError("");
        return fetch(`${apiUrl}/api/v1/family/children/${selectedId}/schedule`, {
          credentials: "include",
        });
      })
      .then(async (response) => {
        if (!response) return;
        if (!response.ok) {
          throw new Error(
            await problemMessage(response, "The published timetable is unavailable."),
          );
        }
        if (!cancelled) setSchedule((await response.json()) as FamilySchedule);
      })
      .catch((reason: Error) => {
        if (!cancelled) setScheduleError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setScheduleLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    Promise.resolve()
      .then(() => {
        if (cancelled) return null;
        setCalendarLoading(true);
        setCalendarError("");
        setCalendar(null);
        return fetch(`${apiUrl}/api/v1/family/children/${selectedId}/calendar`, {
          credentials: "include",
        });
      })
      .then(async (response) => {
        if (!response) return;
        if (!response.ok) {
          throw new Error(await problemMessage(response, "The approved school calendar is unavailable."));
        }
        if (!cancelled) setCalendar((await response.json()) as FamilyCalendar);
      })
      .catch((reason: Error) => {
        if (!cancelled) setCalendarError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setCalendarLoading(false);
      });
    return () => { cancelled = true; };
  }, [selectedId]);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    Promise.resolve()
      .then(() => {
        if (cancelled) return null;
        setCurriculumLoading(true);
        setCurriculumError("");
        setCurriculum(null);
        return fetch(`${apiUrl}/api/v1/family/children/${selectedId}/curriculum`, {
          credentials: "include",
        });
      })
      .then(async (response) => {
        if (!response) return;
        if (!response.ok) {
          throw new Error(await problemMessage(response, "The approved books and syllabus are unavailable."));
        }
        if (!cancelled) setCurriculum((await response.json()) as FamilyCurriculum);
      })
      .catch((reason: Error) => {
        if (!cancelled) setCurriculumError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setCurriculumLoading(false);
      });
    return () => { cancelled = true; };
  }, [selectedId]);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    Promise.resolve()
      .then(() => {
        if (cancelled) return null;
        setCommunicationsLoading(true);
        setCommunicationsError("");
        setCommunicationsNotice("");
        setCommunications(null);
        return fetch(`${apiUrl}/api/v1/family/children/${selectedId}/communications`, {
          credentials: "include",
        });
      })
      .then(async (response) => {
        if (!response) return;
        if (!response.ok) {
          throw new Error(await problemMessage(response, "School announcements are unavailable."));
        }
        if (!cancelled) setCommunications((await response.json()) as FamilyCommunications);
      })
      .catch((reason: Error) => {
        if (!cancelled) setCommunicationsError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setCommunicationsLoading(false);
      });
    return () => { cancelled = true; };
  }, [selectedId]);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    Promise.resolve()
      .then(() => {
        if (cancelled) return null;
        setTransportLoading(true);
        setTransportError("");
        setTransport(null);
        return fetch(`${apiUrl}/api/v1/family/children/${selectedId}/transport`, {
          credentials: "include",
        });
      })
      .then(async (response) => {
        if (!response) return;
        if (!response.ok) {
          throw new Error(await problemMessage(response, "Transport details are unavailable."));
        }
        if (!cancelled) setTransport((await response.json()) as FamilyTransport);
      })
      .catch((reason: Error) => {
        if (!cancelled) setTransportError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setTransportLoading(false);
      });
    return () => { cancelled = true; };
  }, [selectedId]);

  const refreshFees = useCallback(async (signal: AbortSignal) => {
    if (!selectedId || signal.aborted) return;
    setFeesLoading(true); setFeesError("");
    try {
        // Read the exact retained account while a default-session POST is unresolved;
        // a later academic rollover must not replace/remount that payment component.
        const retainedAccountId = paymentGuard.current?.accountId ?? selectedFeeAccount?.id;
        const accountQuery = retainedAccountId ? `?accountId=${encodeURIComponent(retainedAccountId)}` : "";
        const response = await fetch(`${apiUrl}/api/v1/family/children/${selectedId}/fees${accountQuery}`, {
          credentials: "include",
          cache: "no-store",
          signal,
        });
        // An aborted previous-child read must not clear a newer payment guard
        // merely because its late response reports lost access.
        if (signal.aborted) return;
        if (!response.ok) {
          if ([401, 403, 404].includes(response.status)) {
            paymentGuard.current = null; setPaymentBlocked(false); setFees(null); setCurrentFeeAccount(null); setSelectedFeeAccount(null); setKnownFeeAccounts({});
          }
          throw new Error("The family fee statement is unavailable.");
        }
        const next = (await response.json()) as FamilyFees;
        if (signal.aborted) return;
        const guarded = paymentGuard.current;
        if (next.learnerId !== selectedId || (selectedFeeAccount && next.accountId !== selectedFeeAccount.id)
          || (guarded && (next.learnerId !== guarded.learnerId || next.accountId !== guarded.accountId))) throw new Error("The family fee statement is unavailable.");
        setFees(next);
    } catch (reason) {
      if (!signal.aborted) setFeesError("The family fee statement is unavailable.");
      throw reason;
    } finally { if (!signal.aborted) setFeesLoading(false); }
  }, [selectedId, selectedFeeAccount]);
  useEffect(() => {
    if (!selectedId || paymentBlocked) return;
    const controller = new AbortController();
    void Promise.resolve().then(() => refreshFees(controller.signal)).catch(() => {});
    return () => controller.abort();
  }, [selectedId, refreshFees, paymentBlocked]);
  useEffect(() => {
    if (!paymentBlocked) return;
    const preventNavigation = (event: MouseEvent) => {
      if (paymentGuard.current && event.target instanceof Element && event.target.closest("a[href]")) { event.preventDefault(); event.stopImmediatePropagation(); }
    };
    document.addEventListener("click", preventNavigation, true);
    return () => document.removeEventListener("click", preventNavigation, true);
  }, [paymentBlocked]);
  function guardPayment(blocked: boolean) {
    if (blocked && fees?.accountId && fees.learnerId === selectedId) paymentGuard.current ??= { learnerId: fees.learnerId, accountId: fees.accountId };
    if (!blocked) paymentGuard.current = null;
    setPaymentBlocked(blocked);
  }
  function chooseFeeAccount(account: FamilyFeeAccount | null) {
    if (paymentGuard.current) return;
    setSelectedFeeAccount(account); setFees(null); setFeesError("");
  }
  function chooseChild(learnerId: string) {
    if (paymentGuard.current) return;
    setSelectedId(learnerId); setSelectedFeeAccount(null); setCurrentFeeAccount(null); setKnownFeeAccounts({}); setFees(null); setFeesError("");
  }

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    Promise.resolve()
      .then(() => {
        if (cancelled) return null;
        setAssignmentsLoading(true);
        setAssignmentsError("");
        setAssignmentsNotice("");
        setAssignments(null);
        setAssignmentDrafts({});
        return fetch(`${apiUrl}/api/v1/family/children/${selectedId}/assignments`, {
          credentials: "include",
        });
      })
      .then(async (response) => {
        if (!response) return;
        if (!response.ok) {
          throw new Error(await problemMessage(response, "Published assignments are unavailable."));
        }
        if (!cancelled) setAssignments((await response.json()) as FamilyAssignments);
      })
      .catch((reason: Error) => {
        if (!cancelled) setAssignmentsError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setAssignmentsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    Promise.resolve()
      .then(() => {
        if (cancelled) return null;
        setResultsLoading(true);
        setResultsError("");
        setResults(null);
        return fetch(`${apiUrl}/api/v1/family/children/${selectedId}/results`, {
          credentials: "include",
        });
      })
      .then(async (response) => {
        if (!response) return;
        if (!response.ok) {
          throw new Error(await problemMessage(response, "Published results are unavailable."));
        }
        if (!cancelled) setResults((await response.json()) as FamilyResults);
      })
      .catch((reason: Error) => {
        if (!cancelled) setResultsError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setResultsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    Promise.resolve()
      .then(() => {
        if (cancelled) return null;
        setAttendanceLoading(true);
        setAttendanceError("");
        setAttendanceNotice("");
        return fetch(`${apiUrl}/api/v1/family/children/${selectedId}/attendance`, {
          credentials: "include",
        });
      })
      .then(async (response) => {
        if (!response) return;
        if (!response.ok) {
          throw new Error(await problemMessage(response, "Attendance is unavailable."));
        }
        if (!cancelled) setAttendance((await response.json()) as FamilyAttendance);
      })
      .catch((reason: Error) => {
        if (!cancelled) setAttendanceError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setAttendanceLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  async function submitExplanation(markId: string) {
    const explanation = explanations[markId]?.trim();
    if (!explanation || !selectedId) return;
    setExplanationBusy(markId);
    setAttendanceError("");
    setAttendanceNotice("");
    try {
      let csrf = cookie("educore_csrf");
      if (!csrf) {
        await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
        csrf = cookie("educore_csrf");
      }
      if (!csrf) throw new Error("The secure form token is unavailable. Refresh and try again.");
      const response = await fetch(
        `${apiUrl}/api/v1/family/children/${selectedId}/attendance/${markId}/explanations`,
        {
          method: "POST",
          credentials: "include",
          headers: {
            "Content-Type": "application/json",
            "X-CSRF-TOKEN": decodeURIComponent(csrf),
          },
          body: JSON.stringify({ explanation }),
        },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The explanation could not be sent."));
      }
      const refreshed = await fetch(`${apiUrl}/api/v1/family/children/${selectedId}/attendance`, {
        credentials: "include",
      });
      if (!refreshed.ok)
        throw new Error("The explanation was sent, but attendance did not refresh.");
      setAttendance((await refreshed.json()) as FamilyAttendance);
      setExplanations((current) => ({ ...current, [markId]: "" }));
      setAttendanceNotice("Your explanation was sent to the school for review.");
    } catch (reason) {
      setAttendanceError(
        reason instanceof Error ? reason.message : "The explanation could not be sent.",
      );
    } finally {
      setExplanationBusy("");
    }
  }

  async function saveAssignmentResponse(
    assignment: FamilyAssignments["assignments"][number],
    action: "DRAFT" | "SUBMIT",
  ) {
    if (!selectedId) return;
    const draft = assignmentDrafts[assignment.assignmentId] || {
      text: assignment.submission.responseText || "",
      link: assignment.submission.responseLink || "",
    };
    setAssignmentBusy(assignment.assignmentId);
    setAssignmentsError("");
    setAssignmentsNotice("");
    try {
      let csrf = cookie("educore_csrf");
      if (!csrf) {
        await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
        csrf = cookie("educore_csrf");
      }
      if (!csrf) throw new Error("The secure form token is unavailable. Refresh and try again.");
      const response = await fetch(
        `${apiUrl}/api/v1/family/children/${selectedId}/assignments/${assignment.assignmentId}/submission`,
        {
          method: "POST",
          credentials: "include",
          headers: {
            "Content-Type": "application/json",
            "X-CSRF-TOKEN": decodeURIComponent(csrf),
          },
          body: JSON.stringify({
            action,
            responseText: draft.text.trim() || null,
            responseLink: draft.link.trim() || null,
            expectedVersion: assignment.submission.rowVersion,
          }),
        },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The learner response could not be saved."));
      }
      const refreshed = await fetch(`${apiUrl}/api/v1/family/children/${selectedId}/assignments`, {
        credentials: "include",
      });
      if (!refreshed.ok)
        throw new Error("The response was saved, but assignments did not refresh.");
      setAssignments((await refreshed.json()) as FamilyAssignments);
      setAssignmentsNotice(
        action === "DRAFT"
          ? "Learner response saved privately as a draft."
          : "Learner response submitted to the assigned teacher.",
      );
    } catch (reason) {
      setAssignmentsError(
        reason instanceof Error ? reason.message : "The learner response could not be saved.",
      );
    } finally {
      setAssignmentBusy("");
    }
  }

  async function refreshAssignments() {
    if (!selectedId) return;
    const refreshed = await fetch(`${apiUrl}/api/v1/family/children/${selectedId}/assignments`, {
      credentials: "include",
    });
    if (!refreshed.ok) throw new Error("The attachment changed, but assignments did not refresh.");
    setAssignments((await refreshed.json()) as FamilyAssignments);
  }

  async function recordCommunication(recipientId: string, action: "read" | "acknowledge") {
    if (!selectedId) return;
    setCommunicationBusy(recipientId);
    setCommunicationsError("");
    setCommunicationsNotice("");
    try {
      let csrf = cookie("educore_csrf");
      if (!csrf) {
        await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
        csrf = cookie("educore_csrf");
      }
      if (!csrf) throw new Error("The secure form token is unavailable. Refresh and try again.");
      const response = await fetch(
        `${apiUrl}/api/v1/family/children/${selectedId}/communications/${recipientId}/${action}`,
        {
          method: "POST",
          credentials: "include",
          headers: { "X-CSRF-TOKEN": decodeURIComponent(csrf) },
        },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The announcement evidence could not be saved."));
      }
      const refreshed = await fetch(
        `${apiUrl}/api/v1/family/children/${selectedId}/communications`,
        { credentials: "include" },
      );
      if (!refreshed.ok) throw new Error("The action was saved, but announcements did not refresh.");
      setCommunications((await refreshed.json()) as FamilyCommunications);
      setCommunicationsNotice(
        action === "acknowledge"
          ? "Your acknowledgement was recorded for the school."
          : "The announcement was marked as read.",
      );
    } catch (reason) {
      setCommunicationsError(
        reason instanceof Error ? reason.message : "The announcement evidence could not be saved.",
      );
    } finally {
      setCommunicationBusy("");
    }
  }

  async function uploadAssignmentAttachment(
    assignment: FamilyAssignments["assignments"][number],
    file: File,
  ) {
    if (!selectedId || !file.size) return;
    setAssignmentBusy(assignment.assignmentId);
    setAssignmentsError("");
    setAssignmentsNotice("");
    try {
      let csrf = cookie("educore_csrf");
      if (!csrf) {
        await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
        csrf = cookie("educore_csrf");
      }
      if (!csrf) throw new Error("The secure form token is unavailable. Refresh and try again.");
      const body = new FormData();
      body.append("file", file);
      const response = await fetch(
        `${apiUrl}/api/v1/family/children/${selectedId}/assignments/${assignment.assignmentId}/attachments`,
        {
          method: "POST",
          credentials: "include",
          headers: { "X-CSRF-TOKEN": decodeURIComponent(csrf) },
          body,
        },
      );
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The private attachment could not be uploaded."));
      }
      await refreshAssignments();
      setAssignmentsNotice("Private learner evidence was scanned and attached.");
    } catch (reason) {
      setAssignmentsError(
        reason instanceof Error ? reason.message : "The private attachment could not be uploaded.",
      );
    } finally {
      setAssignmentBusy("");
    }
  }

  async function removeAssignmentAttachment(
    assignment: FamilyAssignments["assignments"][number],
    file: PrivateFile,
  ) {
    setAssignmentBusy(assignment.assignmentId);
    setAssignmentsError("");
    setAssignmentsNotice("");
    try {
      let csrf = cookie("educore_csrf");
      if (!csrf) {
        await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
        csrf = cookie("educore_csrf");
      }
      if (!csrf) throw new Error("The secure form token is unavailable. Refresh and try again.");
      const response = await fetch(`${apiUrl}/api/v1/files/${file.fileId}/expire`, {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-TOKEN": decodeURIComponent(csrf),
        },
        body: JSON.stringify({
          expectedVersion: file.rowVersion,
          reason: "Guardian removed draft submission evidence before final submission",
        }),
      });
      if (!response.ok) {
        throw new Error(await problemMessage(response, "The private attachment could not be removed."));
      }
      await refreshAssignments();
      setAssignmentsNotice("Private draft attachment removed; its audit history was retained.");
    } catch (reason) {
      setAssignmentsError(
        reason instanceof Error ? reason.message : "The private attachment could not be removed.",
      );
    } finally {
      setAssignmentBusy("");
    }
  }

  const viewedAccountStatus = fees?.accountId ? knownFeeAccounts[fees.accountId]?.status ?? (currentFeeAccount?.id === fees.accountId ? currentFeeAccount.status : undefined) : undefined;
  return (
    <main id="main-content" className="family-portal">
      <section className="family-hero" id="family-overview">
        <div>
          <span>{t("Verified family portal")}</span>
          <h1>
            {t("School life,")} <em>{t("kept close.")}</em>
          </h1>
          <p>
            {t("One calm, private place for every learner relationship the school has verified for your account.")} </p>
        </div>
        <MotionIllustration
          className="family-hero__motion"
          src="/animations/study-discussion.lottie"
          label={t("Learners studying together")}
        />
      </section>

      {loading ? (
        <section className="family-loading" aria-live="polite">
          <i />
          <i />
          <i />
          <span>{t("Opening your verified family view…")}</span>
        </section>
      ) : error ? (
        <section className="family-empty family-empty--error" role="alert">
          <Shield />
          <h2>{t("We could not open the family portal")}</h2>
          <p>{t(error)}</p>
        </section>
      ) : !selected ? (
        <section className="family-empty">
          <Heart />
          <h2>{t("No active learner relationship")}</h2>
          <p>
            {t("Access appears only after the school issues and verifies a separate invitation for a learner relationship. Contact the school office if you expected to see a child here.")} </p>
        </section>
      ) : (
        <>
          <section className="family-child-switcher" aria-labelledby="family-children-title">
            <header>
              <div>
                <span>{t("Your verified learners")}</span>
                <h2 id="family-children-title">{t("Choose a child")}</h2>
              </div>
              <small>{t("Only school-approved relationships appear here.")}</small>
            </header>
            <div>
              {children.map((child) => (
                <button
                  type="button"
                  key={child.learnerId}
                  className={child.learnerId === selected.learnerId ? "is-active" : ""}
                  aria-pressed={child.learnerId === selected.learnerId}
                  disabled={paymentBlocked && child.learnerId !== selected.learnerId}
                  onClick={() => chooseChild(child.learnerId)}
                >
                  <span>
                    {(child.preferredName || child.legalName)
                      .split(/\s+/)
                      .slice(0, 2)
                      .map((part) => part[0])
                      .join("")}
                  </span>
                  <strong>{child.preferredName || child.legalName}</strong>
                  <small>
                    {displayCode(child.gradeCode)} · {child.sectionName || "Section pending"}
                  </small>
                </button>
              ))}
            </div>
          </section>

          <section className="family-child-summary">
            <article className="family-child-summary__identity">
              <span>
                <PeopleIcon /> {t("Active learner")} </span>
              <h2>{selected.preferredName || selected.legalName}</h2>
              <p>{selected.schoolName}</p>
              <dl>
                <div>
                  <dt>{t("Class")}</dt>
                  <dd>{displayCode(selected.gradeCode)}</dd>
                </div>
                <div>
                  <dt>{t("Section")}</dt>
                  <dd>{selected.sectionName || "Pending"}</dd>
                </div>
                <div>
                  <dt>{t("Roll number")}</dt>
                  <dd>{selected.rollNumber || "Pending"}</dd>
                </div>
                <div>
                  <dt>{t("Your relationship")}</dt>
                  <dd>{displayCode(selected.relationshipCode)}</dd>
                </div>
              </dl>
            </article>
            <article className="family-child-summary__school">
              <Calendar />
              <span>{t("Current school context")}</span>
              <strong>{selected.academicSessionName}</strong>
              <p>{selected.campusName}</p>
              <small>
                {t("Changing children changes this context; it never expands your account permissions.")} </small>
            </article>
          </section>

          <StudentPortalAccessCard learnerId={selected.learnerId} />

          <section className="family-calendar" id="family-calendar">
            <header>
              <div>
                <span>{t("Leadership-approved calendar")}</span>
                <h2>{t("Holidays & closures")}</h2>
              </div>
              <small>
                {calendar
                  ? `${calendar.academicSessionName} · ${calendarDateLabel(calendar.sessionStartsOn)} to ${calendarDateLabel(calendar.sessionEndsOn)}`
                  : "Draft and rejected notices are never shown here."}
              </small>
            </header>
            <div className="family-calendar__assurance">
              <Shield />
              <p>
                {t("This view follows the same approved record used by school attendance. Emergency changes appear here after independent approval, and the verified inbox carries the notification.")} </p>
            </div>
            {calendarLoading ? (
              <div className="family-calendar__state" aria-live="polite">{t("Opening the approved school calendar…")}</div>
            ) : calendarError && !calendar ? (
              <div className="family-calendar__state is-error" role="alert">{t(calendarError)}</div>
            ) : !calendar?.days.length ? (
              <div className="family-calendar__state">{t("No holiday, closure, or reopening change has been approved for this session yet.")}</div>
            ) : (
              <div className="family-calendar__days">
                {calendar.days.map((day) => {
                  const urgent = ["EMERGENCY_CLOSURE", "GOVERNMENT_ORDER"].includes(day.noticeKind);
                  const past = day.calendarDate < calendar.today;
                  const title = localizedContent(locale, day.titleEn, day.titleHi);
                  const body = localizedContent(locale, day.bodyEn, day.bodyHi);
                  return (
                    <article className={`${urgent ? "is-emergency" : ""} ${past ? "is-past" : ""}`} key={day.calendarDayId}>
                      <header>
                        <time dateTime={day.calendarDate}>{calendarDateLabel(day.calendarDate)}</time>
                        <span>{displayCode(day.noticeKind)}</span>
                      </header>
                      <h3 lang={title.primaryLanguage}>{title.primary}</h3>
                      <p lang={body.primaryLanguage}>{body.primary}</p>
                      <details>
                        <summary>{t("Read in another language")}</summary>
                        <strong lang={title.secondaryLanguage}>{title.secondary}</strong>
                        <p lang={body.secondaryLanguage}>{body.secondary}</p>
                      </details>
                      <footer>
                        <b>{displayCode(day.dayType)}</b>
                        <small>{day.attendanceRequired ? "Classes scheduled" : "No attendance required"}</small>
                      </footer>
                    </article>
                  );
                })}
              </div>
            )}
          </section>

          {calendar?.schoolId ? <PushNotificationControl schoolId={calendar.schoolId} /> : null}

          <section className="family-transport" id="family-transport">
            <header>
              <div>
                <span>{t("School-approved journeys")}</span>
                <h2>{t("Transport & handover")}</h2>
              </div>
              <small>{t("Only this learner's assigned stop and trip events are visible.")}</small>
            </header>
            <div className="family-transport__privacy">
              <Shield />
              <p>
                {t("This is an operational record, not live GPS tracking. Other riders, staff contact details, exact coordinates, and restricted incidents remain private.")} </p>
            </div>
            {transportLoading ? (
              <div className="family-transport__state" aria-live="polite">
                {t("Opening approved transport journeys…")} </div>
            ) : transportError && !transport ? (
              <div className="family-transport__state is-error" role="alert">
                {t(transportError)}
              </div>
            ) : !transport?.journeys.length ? (
              <div className="family-transport__state">
                {t("No active school transport journey is assigned to this learner.")} </div>
            ) : (
              <div className="family-transport__journeys">
                {transport.journeys.map((journey) => (
                  <article key={`${journey.journeyKind}-${journey.serviceLabel}-${journey.stopLabel}`}>
                    <header>
                      <div>
                        <span>{displayCode(journey.journeyKind)} {t("journey")}</span>
                        <h3>{journey.serviceLabel}</h3>
                        <p>{journey.routeName}</p>
                      </div>
                      <b>{journey.latestTrip ? displayCode(journey.latestTrip.status) : "Scheduled"}</b>
                    </header>
                    <dl>
                      <div>
                        <dt>{t("Approved stop")}</dt>
                        <dd>{journey.stopLabel}</dd>
                      </div>
                      <div>
                        <dt>{t("Scheduled time")}</dt>
                        <dd>
                          {journey.scheduledTime.slice(0, 5)} · {journey.boardingWindowMinutes} {t("min window")} </dd>
                      </div>
                      <div>
                        <dt>{t("Operating days")}</dt>
                        <dd>{journey.operatingDays.split(",").join(" · ")}</dd>
                      </div>
                      <div>
                        <dt>{t("Assigned vehicle")}</dt>
                        <dd>
                          {journey.vehicleLabel && journey.vehicleRegistration
                            ? `${journey.vehicleLabel} · ${journey.vehicleRegistration}`
                            : "Awaiting an active school assignment"}
                        </dd>
                      </div>
                    </dl>
                    <footer>
                      {journey.latestTrip ? (
                        <>
                          <div>
                            <small>{t("Latest service day")}</small>
                            <strong>
                              {new Date(`${journey.latestTrip.serviceDate}T12:00:00`).toLocaleDateString("en-IN", {
                                day: "2-digit",
                                month: "short",
                                year: "numeric",
                              })}
                            </strong>
                          </div>
                          <div>
                            <small>{t("This learner's latest event")}</small>
                            <strong>
                              {journey.latestTrip.learnerStatus
                                ? displayCode(journey.latestTrip.learnerStatus)
                                : "No event recorded"}
                            </strong>
                            {journey.latestTrip.learnerStatusAt && (
                              <time dateTime={journey.latestTrip.learnerStatusAt}>
                                {new Date(journey.latestTrip.learnerStatusAt).toLocaleString("en-IN")}
                              </time>
                            )}
                          </div>
                        </>
                      ) : (
                        <p>{t("No service-day trip has been opened for this journey yet.")}</p>
                      )}
                    </footer>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="family-communications" id="family-communications">
            <header>
              <div>
                <span>{t("Approved school inbox")}</span>
                <h2>{t("Notices & acknowledgements")}</h2>
              </div>
              <small>{t("Frozen to this verified learner relationship")}</small>
            </header>
            {(communicationsError || communicationsNotice) && (
              <div
                className={communicationsError ? "family-communications__notice is-error" : "family-communications__notice"}
                role={communicationsError ? "alert" : "status"}
              >
                {t(communicationsError || communicationsNotice)}
              </div>
            )}
            {communicationsLoading ? (
              <div className="family-communications__state" aria-live="polite">
                {t("Opening approved announcements…")} </div>
            ) : communicationsError && !communications ? (
              <div className="family-communications__state is-error">{t(communicationsError)}</div>
            ) : !communications?.items.length ? (
              <div className="family-communications__state">
                {t("No approved announcement is currently available for this learner.")} </div>
            ) : (
              <div className="family-communications__list">
                {communications.items.map((item) => {
                  const title = localizedContent(locale, item.titleEn, item.titleHi);
                  const body = localizedContent(locale, item.bodyEn, item.bodyHi);
                  const primaryTitle = title.primary;
                  const primaryBody = body.primary;
                  const secondaryTitle = title.secondary;
                  const secondaryBody = body.secondary;
                  return (
                    <article key={item.recipientId} className={!item.readAt ? "is-unread" : ""}>
                      <header>
                        <span><Message /> {displayCode(item.category)}</span>
                        <div>
                          {item.expired && <em>{t("Expired")}</em>}
                          <b>{displayCode(item.priority)}</b>
                        </div>
                      </header>
                      <h3 lang={title.primaryLanguage}>{primaryTitle}</h3>
                      <p lang={body.primaryLanguage}>{primaryBody}</p>
                      {secondaryTitle && secondaryBody && (
                        <details lang={body.secondaryLanguage}>
                          <summary>{t("Read in another language")}</summary>
                          <h4>{secondaryTitle}</h4>
                          <p>{secondaryBody}</p>
                        </details>
                      )}
                      <footer>
                        <time dateTime={item.scheduledFor}>
                          {new Date(item.scheduledFor).toLocaleString("en-IN")}
                        </time>
                        <span>{item.deliveries.map((delivery) => `${displayCode(delivery.channel)}: ${displayCode(delivery.status)}`).join(" · ")}</span>
                      </footer>
                      <div className="family-communications__actions">
                        {!item.readAt && (
                          <button type="button" disabled={communicationBusy === item.recipientId} onClick={() => void recordCommunication(item.recipientId, "read")}>{t("Mark read")}</button>
                        )}
                        {item.requiresAcknowledgement && !item.acknowledgedAt && (
                          <button type="button" disabled={communicationBusy === item.recipientId} onClick={() => void recordCommunication(item.recipientId, "acknowledge")}>{t("Acknowledge")}</button>
                        )}
                        {item.acknowledgedAt && <strong>{t("Acknowledged")} {new Date(item.acknowledgedAt).toLocaleString("en-IN")}</strong>}
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </section>

          <section className="family-attendance" id="family-attendance">
            <header>
              <div>
                <span>{t("School-submitted attendance")}</span>
                <h2>{t("Attendance & explanations")}</h2>
              </div>
              <small>{t("Last 31 days · based on the school’s recorded policy")}</small>
            </header>
            {attendanceLoading ? (
              <div className="family-attendance__state" aria-live="polite">
                {t("Opening submitted attendance…")} </div>
            ) : attendanceError && !attendance ? (
              <div
                className="family-attendance__state family-attendance__state--error"
                role="alert"
              >
                {t(attendanceError)}
              </div>
            ) : !attendance?.records.length ? (
              <div className="family-attendance__state">
                {t("No submitted attendance records are available in this period.")} </div>
            ) : (
              <>
                <div className="family-attendance__summary">
                  <article
                    className={attendance.summary.belowConfiguredWarning ? "needs-attention" : ""}
                  >
                    <small>{t("Configured-credit attendance")}</small>
                    <strong>
                      {attendance.summary.attendancePercent === null
                        ? "—"
                        : `${attendance.summary.attendancePercent.toFixed(2)}%`}
                    </strong>
                    <span>
                      {attendance.summary.warningThresholdPercent === null
                        ? t("No warning threshold set")
                        : t("School warning level {percent}%", { percent: attendance.summary.warningThresholdPercent })}
                    </span>
                  </article>
                  <article>
                    <small>{t("Recorded marks")}</small>
                    <strong>{attendance.summary.recordedMarks}</strong>
                    <span>
                      {attendance.summary.earnedCredits.toFixed(2)} {t("credited by school rules")} </span>
                  </article>
                  <article>
                    <small>{t("Exceptions")}</small>
                    <strong>
                      {Object.entries(attendance.summary.stateCounts)
                        .filter(([state]) => state !== "PRESENT")
                        .reduce((total, [, count]) => total + count, 0)}
                    </strong>
                    <span>{t("Late, absent, excused, half-day, or activity marks")}</span>
                  </article>
                </div>
                {(attendanceError || attendanceNotice) && (
                  <div
                    className={
                      attendanceError
                        ? "family-attendance__notice is-error"
                        : "family-attendance__notice"
                    }
                    role={attendanceError ? "alert" : "status"}
                  >
                    {t(attendanceError || attendanceNotice)}
                  </div>
                )}
                <div className="family-attendance__records">
                  {attendance.records.map((record) => (
                    <article
                      key={record.markId}
                      className={record.creditWeight < 1 ? "has-exception" : ""}
                    >
                      <time dateTime={record.date}>
                        {new Date(`${record.date}T12:00:00`).toLocaleDateString("en-IN", {
                          day: "2-digit",
                          month: "short",
                        })}
                      </time>
                      <div>
                        <strong>{displayCode(record.stateCode)}</strong>
                        <small>
                          {record.periodName || t("Daily register")}
                          {record.startsAt ? ` · ${record.startsAt.slice(0, 5)}` : ""}
                        </small>
                      </div>
                      <span>{record.creditWeight.toFixed(2)} {t("credit")}</span>
                      {record.explanationStatus ? (
                        <em>{displayCode(record.explanationStatus)}</em>
                      ) : record.canExplain ? (
                        <div className="family-attendance__explain">
                          <label>
                            <span>{t("Explain this attendance mark")}</span>
                            <textarea
                              value={explanations[record.markId] || ""}
                              onChange={(event) =>
                                setExplanations((current) => ({
                                  ...current,
                                  [record.markId]: event.target.value,
                                }))
                              }
                              minLength={3}
                              maxLength={1000}
                              placeholder={t("Share the reason or context for the school to review.")}
                            />
                          </label>
                          <button
                            type="button"
                            disabled={
                              explanationBusy === record.markId ||
                              (explanations[record.markId]?.trim().length || 0) < 3
                            }
                            onClick={() => void submitExplanation(record.markId)}
                          >
                            {t("Send explanation")} </button>
                        </div>
                      ) : record.creditWeight < 1 ? (
                        <em>{t("Explanation window closed")}</em>
                      ) : null}
                    </article>
                  ))}
                </div>
              </>
            )}
          </section>

          <section className="family-fees" id="family-fees">
            <header>
              <div>
                <span>{t("Verified family account")}</span>
                <h2>{t("Fees, dues & receipts")}</h2>
              </div>
              <small>{fees?.academicSessionName || selected.academicSessionName}</small>
            </header>
            <FamilyFeeAccountSelector key={selected.learnerId} learnerId={selected.learnerId} selectedAccount={selectedFeeAccount ? knownFeeAccounts[selectedFeeAccount.id] ?? selectedFeeAccount : null} disabled={paymentBlocked} onSelect={chooseFeeAccount} onCurrentAccount={setCurrentFeeAccount} onAccountsRead={accounts => setKnownFeeAccounts(previous => ({ ...previous, ...Object.fromEntries(accounts.map(account => [account.id, account])) }))} onAccessLost={() => { paymentGuard.current = null; setPaymentBlocked(false); setFees(null); setSelectedFeeAccount(null); setCurrentFeeAccount(null); setKnownFeeAccounts({}); }} />
            {paymentBlocked && <p role="status">{t("A payment request is unresolved. Keep this child and fee account selected until its exact request and refreshed records are confirmed.")}</p>}
            {feesLoading && (
              <div className="family-fees__state" aria-live="polite">
                {t("Opening the verified fee statement…")} </div>
            )}
            {feesError && (
              <div className="family-fees__state family-fees__state--error" role="alert">
                {t(feesError)}
              </div>
            )}
            {!fees?.accountId || fees.learnerId !== selected.learnerId ? (!feesLoading && !feesError &&
              <div className="family-fees__state">
                {t("The school has not assigned a fee plan to this learner for the current session.")} </div>
            ) : (
              <>
                <div className="family-fees__summary">
                  <article>
                    <small>{t("Current balance")}</small>
                    <strong>{formatMoney(fees.balance, fees.currencyCode)}</strong>
                    <span>{t("Charges less verified payments and approved concessions")}</span>
                  </article>
                  <article>
                    <small>{t("Assessed")}</small>
                    <strong>{formatMoney(fees.totalCharges, fees.currencyCode)}</strong>
                    <span>{t("Scheduled fee lines: {count}", { count: fees.charges.length })}</span>
                  </article>
                  <article>
                    <small>{t("Credits")}</small>
                    <strong>{formatMoney(fees.totalCredits, fees.currencyCode)}</strong>
                    <span>{t("Posted collections and approved concessions")}</span>
                  </article>
                </div>
                <div className="family-fees__provider">
                  <Shield />
                  <div>
                    <strong>
                      {t("Secure online fee payments")}
                    </strong>
                    <p>
                      {t("Card, bank and UPI PIN details are entered only on the provider’s secure page, never in this application.")} </p>
                  </div>
                </div>
                <FamilyGatewayPaymentPanel key={`${fees.learnerId}:${fees.accountId}:${selectedFeeAccount ? "explicit" : "current"}`} fees={{ ...fees, explicitAccountId: selectedFeeAccount?.id, accountStatus: viewedAccountStatus, paymentsAllowed: viewedAccountStatus === "ACTIVE" && !feesLoading && !feesError }} onGuardChange={guardPayment} onAccessLost={() => { paymentGuard.current = null; setPaymentBlocked(false); setFees(null); setSelectedFeeAccount(null); setCurrentFeeAccount(null); setKnownFeeAccounts({}); }} onRecorded={() => refreshFees(new AbortController().signal)} />
                <div className="family-fees__layout">
                  <section>
                    <h3>{t("Scheduled charges")}</h3>
                    <div className="family-fees__charges">
                      {fees.charges.map((charge) => (
                        <article key={charge.id}>
                          <time dateTime={charge.dueOn}>
                            {new Date(`${charge.dueOn}T12:00:00`).toLocaleDateString("en-IN", {
                              day: "2-digit",
                              month: "short",
                            })}
                          </time>
                          <div>
                            <strong>{charge.label}</strong>
                            <small>
                              {charge.feeHeadName} · {t(charge.allowPartial ? "Partial payment allowed" : "Full installment only")}
                            </small>
                            {charge.rteExemptionAmount > 0 && <small>{t("RTE Fee Exemption")}: {formatMoney(charge.rteExemptionAmount, fees.currencyCode)} · {t("Exempt — RTE")}</small>}
                          </div>
                          <span>
                            <strong>{formatMoney(charge.outstandingAmount, fees.currencyCode)}</strong>
                            <small>{t("of")} {formatMoney(charge.amount, fees.currencyCode)}</small>
                            {charge.reservedAmount > 0 && <small>{t("Held for pending verification: {amount}", { amount: formatMoney(charge.reservedAmount, fees.currencyCode) })}</small>}
                          </span>
                        </article>
                      ))}
                    </div>
                  </section>
                  <section>
                    <h3>{t("Receipt history")}</h3>
                    {fees.receipts.length ? (
                      <div className="family-fees__receipts">
                        {fees.receipts.map((receipt) => (
                          <article key={receipt.paymentId} className={receiptStyles.familyReceiptRow}>
                            <MoneyIcon />
                            <div>
                              <strong>{receipt.receiptNumber}</strong>
                              <small>
                                {displayCode(receipt.paymentMethod)}
                              </small>
                              <FeeReceiptTimingText postingOn={receipt.receivedOn} timing={receipt.timing} />
                            </div>
                            <span>
                              {formatMoney(receipt.amount, fees.currencyCode)}
                              <em className={receipt.status === "POSTED" ? "is-posted" : "is-reversed"}>
                                {displayCode(receipt.status)}
                              </em>
                            </span>
                            <ReceiptPdfActions key={`${fees.learnerId}:${receipt.paymentId}`} audience="family" learnerId={fees.learnerId} paymentId={receipt.paymentId} receiptNumber={receipt.receiptNumber} paymentStatus={receipt.status} />
                          </article>
                        ))}
                      </div>
                    ) : (
                      <p className="family-fees__none">{t("No receipt has been issued for this account yet.")}</p>
                    )}
                  </section>
                </div>
                <FamilyRefundHistory refunds={fees.refunds} />
                <details className="family-fees__statement">
                  <summary>{t("View full account statement")}</summary>
                  <div>
                    {fees.statement.map((entry) => (
                      <article key={entry.id}>
                        <time dateTime={entry.effectiveOn}>{entry.effectiveOn}</time>
                        <span>
                          <strong>{entry.description}</strong>
                          <small>{displayCode(entry.entryType)}</small>
                        </span>
                        <b className={entry.direction === "CREDIT" ? "is-credit" : ""}>
                          {entry.direction === "CREDIT" ? "−" : "+"}
                          {formatMoney(entry.amount, fees.currencyCode)}
                        </b>
                      </article>
                    ))}
                  </div>
                </details>
              </>
            )}
          </section>

          <section className="family-schedule" id="family-schedule">
            <header>
              <div>
                <span>{t("School-published timetable")}</span>
                <h2>{t("Learning week")}</h2>
              </div>
              <small>{schedule?.timetableName || t(selected.sectionName
                ? "Waiting for publication" : "Waiting for section placement")}</small>
            </header>
            {scheduleLoading ? (
              <div className="family-schedule__state" aria-live="polite">
                {t("Opening the latest published timetable…")} </div>
            ) : scheduleError ? (
              <div className="family-schedule__state family-schedule__state--error" role="alert">
                {t(scheduleError)}
              </div>
            ) : !schedule?.entries.length ? (
              <div className="family-schedule__state">
                {t(selected.sectionName
                  ? "The school has not published a timetable for this class yet."
                  : "Your child's timetable will appear after the school assigns a section and publishes it.")} </div>
            ) : (
              <div className="family-schedule__days">
                {weekDays.map((day, index) => {
                  const entries = schedule.entries.filter((entry) => entry.dayOfWeek === index + 1);
                  if (!entries.length) return null;
                  return (
                    <article key={day}>
                      <h3>{t(day)}</h3>
                      <div>
                        {entries.map((entry) => (
                          <span key={entry.id}>
                            <small>{entry.startsAt.slice(0, 5)}</small>
                            <strong>{entry.subjectShortName || entry.subjectName}</strong>
                            <em>
                              {entry.periodName}
                              {entry.roomLabel ? ` · ${entry.roomLabel}` : ""}
                            </em>
                          </span>
                        ))}
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </section>

          <section className="family-curriculum" id="family-curriculum">
            <header>
              <div>
                <span>{t("Authority-approved class catalogue")}</span>
                <h2>{t("Books & syllabus")}</h2>
              </div>
              <small>
                {curriculum?.publishedAt
                  ? `${curriculum.sessionName} · release ${curriculum.publicationVersion}`
                  : t("Waiting for school publication")}
              </small>
            </header>
            {curriculumLoading ? (
              <div className="family-curriculum__state" aria-live="polite">{t("Opening the approved class catalogue…")}</div>
            ) : curriculumError ? (
              <div className="family-curriculum__state is-error" role="alert">{t(curriculumError)}</div>
            ) : !curriculum?.items.length ? (
              <div className="family-curriculum__state">{t("The school has not published books or syllabus for this class yet.")}</div>
            ) : (
              <div className="family-curriculum__grid">
                {curriculum.items.map((item) => (
                  <article key={item.id}>
                    <header><span>{displayCode(item.resourceType)}</span><em>{item.subjectShortName || item.subjectCode}</em></header>
                    <h3>{item.title}</h3>
                    <p>{item.description || `${item.subjectName} · confirmed by school authority`}</p>
                    <dl>
                      {item.author ? <div><dt>{t("Author")}</dt><dd>{item.author}</dd></div> : null}
                      {item.publisher ? <div><dt>{t("Publisher")}</dt><dd>{item.publisher}</dd></div> : null}
                      {item.edition ? <div><dt>{t("Edition")}</dt><dd>{item.edition}</dd></div> : null}
                      {item.isbn ? <div><dt>ISBN</dt><dd>{item.isbn}</dd></div> : null}
                    </dl>
                    {item.sourceUrl ? <a href={item.sourceUrl} target="_blank" rel="noreferrer">{t("Open official resource")}</a> : null}
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="family-assignments" id="family-assignments">
            <header>
              <div>
                <span>{t("Assigned-teacher learning work")}</span>
                <h2>{t("Assignments & responses")}</h2>
              </div>
              <small>{t("Published revisions remain traceable for your family.")}</small>
            </header>
            {(assignmentsError || assignmentsNotice) && (
              <div
                className={
                  assignmentsError
                    ? "family-assignments__notice is-error"
                    : "family-assignments__notice"
                }
                role={assignmentsError ? "alert" : "status"}
              >
                {t(assignmentsError || assignmentsNotice)}
              </div>
            )}
            {assignmentsLoading ? (
              <div className="family-assignments__state" aria-live="polite">
                {t("Opening published assignments…")} </div>
            ) : assignmentsError && !assignments ? (
              <div className="family-assignments__state family-assignments__state--error">
                {t(assignmentsError)}
              </div>
            ) : !assignments?.assignments.length ? (
              <div className="family-assignments__state">
                {t("No assignment is currently published for this learner.")} </div>
            ) : (
              <div className="family-assignments__list">
                {assignments.assignments.map((assignment) => {
                  const canRespond =
                    assignment.status === "PUBLISHED" &&
                    assignment.submissionMode !== "OFFLINE" &&
                    ["NOT_STARTED", "DRAFT", "RETURNED"].includes(assignment.submission.state);
                  const draft = assignmentDrafts[assignment.assignmentId] || {
                    text: assignment.submission.responseText || "",
                    link: assignment.submission.responseLink || "",
                  };
                  return (
                    <article key={assignment.assignmentId}>
                      <header>
                        <span>
                          <AcademicIcon /> {assignment.subjectName}
                        </span>
                        <em>{displayCode(assignment.submission.state)}</em>
                      </header>
                      <div className="family-assignments__title">
                        <div>
                          <small>
                            {assignment.categoryName || "Assignment"} {t("· revision")}{" "}
                            {assignment.publicationRevision}
                          </small>
                          <h3>{assignment.title}</h3>
                        </div>
                        <time dateTime={assignment.dueAt}>
                          {t("Due")} {new Date(assignment.dueAt).toLocaleString("en-IN")}
                        </time>
                      </div>
                      <p className="family-assignments__instructions">{assignment.instructions}</p>
                      {assignment.resourceUrl && (
                        <a
                          className="family-assignments__resource"
                          href={assignment.resourceUrl}
                          target="_blank"
                          rel="noreferrer"
                        >
                          {assignment.resourceLabel || "Open teacher resource"}
                        </a>
                      )}
                      {!!assignment.attachments.length && (
                        <div className="family-assignments__files">
                          <strong>{t("Private teacher resources")}</strong>
                          {assignment.attachments.map((file) => (
                            <a key={file.fileId} href={`${apiUrl}/api/v1/files/${file.fileId}`}>
                              {file.filename}
                            </a>
                          ))}
                        </div>
                      )}
                      <div className="family-assignments__meta">
                        <span>{displayCode(assignment.submissionMode)}</span>
                        <span>
                          {assignment.maximumPoints == null
                            ? "Not point-scored"
                            : `${assignment.maximumPoints} points`}
                        </span>
                        <span>
                          {assignment.allowLate ? "Late responses allowed" : "Due time enforced"}
                        </span>
                        {assignment.allowAttachments && (
                          <span>
                            {t("Up to")} {assignment.maxAttachmentCount} {t("private attachment")} {assignment.maxAttachmentCount === 1 ? "" : "s"}
                            {assignment.attachmentRequired ? " · required" : ""}
                          </span>
                        )}
                      </div>
                      {assignment.submission.teacherFeedback && (
                        <div className="family-assignments__feedback">
                          <strong>{t("Teacher feedback")}</strong>
                          <p>{assignment.submission.teacherFeedback}</p>
                          {assignment.submission.pointsAwarded != null && (
                            <span>
                              {assignment.submission.pointsAwarded}
                              {assignment.maximumPoints != null ? ` / ${assignment.maximumPoints}` : ""}{" "}
                              {t("points")} </span>
                          )}
                        </div>
                      )}
                      {assignment.submissionMode === "OFFLINE" &&
                      assignment.submission.state === "NOT_STARTED" ? (
                        <div className="family-assignments__offline">
                          {t("Hand this work in directly to the school. The teacher will record receipt.")} </div>
                      ) : canRespond ? (
                        <div className="family-assignments__response">
                          {(assignment.submissionMode === "TEXT" ||
                            assignment.submissionMode === "TEXT_OR_LINK" ||
                            assignment.submissionMode === "FLEXIBLE") && (
                            <label>
                              <span>{t("Learner response")}</span>
                              <textarea
                                value={draft.text}
                                maxLength={8000}
                                onChange={(event) =>
                                  setAssignmentDrafts((current) => ({
                                    ...current,
                                    [assignment.assignmentId]: {
                                      text: event.target.value,
                                      link: draft.link,
                                    },
                                  }))
                                }
                                placeholder={t("Write the learner's response here.")}
                              />
                            </label>
                          )}
                          {(assignment.submissionMode === "LINK" ||
                            assignment.submissionMode === "TEXT_OR_LINK" ||
                            assignment.submissionMode === "FLEXIBLE") && (
                            <label>
                              <span>{t("HTTPS response link")}</span>
                              <input
                                type="url"
                                value={draft.link}
                                maxLength={1000}
                                onChange={(event) =>
                                  setAssignmentDrafts((current) => ({
                                    ...current,
                                    [assignment.assignmentId]: {
                                      text: draft.text,
                                      link: event.target.value,
                                    },
                                  }))
                                }
                                placeholder="https://…"
                              />
                            </label>
                          )}
                          {!!assignment.submission.attachments.length && (
                            <div className="family-assignments__files">
                              <strong>{t("Private learner evidence")}</strong>
                              {assignment.submission.attachments.map((file) => (
                                <span key={file.fileId}>
                                  <a href={`${apiUrl}/api/v1/files/${file.fileId}`}>
                                    {file.filename}
                                  </a>
                                  <button
                                    type="button"
                                    disabled={assignmentBusy === assignment.assignmentId}
                                    onClick={() => void removeAssignmentAttachment(assignment, file)}
                                  >
                                    {t("Remove")} </button>
                                </span>
                              ))}
                            </div>
                          )}
                          {assignment.allowAttachments &&
                            assignment.submission.attachments.length < assignment.maxAttachmentCount && (
                              <label>
                                <span>
                                  {t("Add private evidence")} {assignment.attachmentRequired ? "(required)" : ""}
                                </span>
                                <input
                                  type="file"
                                  accept="application/pdf,image/jpeg,image/png,image/webp"
                                  disabled={assignmentBusy === assignment.assignmentId}
                                  onChange={(event) => {
                                    const file = event.target.files?.[0];
                                    if (file) void uploadAssignmentAttachment(assignment, file);
                                    event.currentTarget.value = "";
                                  }}
                                />
                                <small>{t("PDF, JPEG, PNG, or WebP · scanned privately · up to 10 MB")}</small>
                              </label>
                            )}
                          <div>
                            <button
                              type="button"
                              disabled={assignmentBusy === assignment.assignmentId}
                              onClick={() => void saveAssignmentResponse(assignment, "DRAFT")}
                            >
                              {t("Save draft")} </button>
                            <button
                              type="button"
                              disabled={assignmentBusy === assignment.assignmentId}
                              onClick={() => void saveAssignmentResponse(assignment, "SUBMIT")}
                            >
                              {t("Submit to teacher")} </button>
                          </div>
                        </div>
                      ) : assignment.submission.submittedAt ? (
                        <footer>
                          {t("Submitted")}{" "}
                          {new Date(assignment.submission.submittedAt).toLocaleString("en-IN")}
                        </footer>
                      ) : null}
                    </article>
                  );
                })}
              </div>
            )}
          </section>

          <section className="family-results" id="family-results">
            <header>
              <div>
                <span>{t("Authority-published results")}</span>
                <h2>{t("Report cards")}</h2>
              </div>
              <small>{t("Every revision is issued as a new, traceable version.")}</small>
            </header>
            {resultsLoading ? (
              <div className="family-results__state" aria-live="polite">
                {t("Opening published report cards…")} </div>
            ) : resultsError ? (
              <div className="family-results__state family-results__state--error" role="alert">
                {t(resultsError)}
              </div>
            ) : !results?.reportCards.length ? (
              <div className="family-results__state">
                {t("The school has not published a report card for this learner yet.")} </div>
            ) : (
              <div className="family-results__cards">
                {results.reportCards.map((card) => (
                  <article key={card.reportCardId}>
                    <header>
                      <span>
                        <ReportIcon /> {card.termName}
                      </span>
                      <em>{t("Version")} {card.publicationVersion}</em>
                    </header>
                    <div className="family-results__overall">
                      <div>
                        <small>{t("Overall")}</small>
                        <strong>
                          {card.overallPercentage === null
                            ? card.overallGradeCode || "Descriptive"
                            : `${card.overallPercentage.toFixed(2)}%`}
                        </strong>
                      </div>
                      <span>{displayCode(card.overallOutcome || "NOT_APPLICABLE")}</span>
                    </div>
                    <div className="family-results__subjects">
                      {(card.report.subjects || []).map((subject) => (
                        <span key={subject.subjectId}>
                          <strong>{subject.name}</strong>
                          <em>
                            {subject.percentage === null
                              ? subject.gradeCode || "Recorded"
                              : `${subject.percentage.toFixed(2)}%`}
                          </em>
                        </span>
                      ))}
                    </div>
                    <footer>
                      <span>{card.reportNumber}</span>
                      <time dateTime={card.publishedAt}>
                        {t("Published")} {new Date(card.publishedAt).toLocaleDateString("en-IN")}
                      </time>
                    </footer>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="family-privacy-note">
            <Sparkles />
            <div>
              <strong>{t("Private by relationship, not by designation")}</strong>
              <p>
                {t("Choosing “Parent or guardian” at login only selects this experience. The server shows a learner after an active, accepted school relationship is verified.")} </p>
            </div>
            <Message />
          </section>
        </>
      )}
    </main>
  );
}
