export type AttendanceLeaveEvidence = {
  requestId: string;
  requestVersion: number;
  status: "APPROVED" | "WITHDRAWN" | "REVOKED";
  attendanceState: "EXCUSED" | "ABSENT";
  initializedFromLeave: boolean;
  review?: { id: string; decision: "KEEP_RECORDED" | "APPLY_APPROVED"; occurredAt: string };
};

/** Context only: neither leave approval nor withdrawal proves physical attendance. */
export function attendanceLeaveMessage(
  evidence: AttendanceLeaveEvidence,
  recordedState: string,
): string {
  if (evidence.status === "WITHDRAWN" || evidence.status === "REVOKED") {
    const use = evidence.initializedFromLeave
      ? "used to initialize this mark"
      : "linked to this attendance mark";
    return `The leave ${use} was ${evidence.status.toLowerCase()}. Verify actual attendance; the recorded mark has not been changed automatically.`;
  }
  if (evidence.status !== "APPROVED") return "Leave evidence needs school review.";
  const state = evidence.attendanceState === "EXCUSED" ? "excused" : "absent";
  return recordedState === evidence.attendanceState
    ? `Approved leave (${state}). Confirm actual attendance before finalizing the register.`
    : `Approved leave suggests ${state}; the saved attendance differs. Review the observation before changing a draft or requesting a submitted-record correction.`;
}

export type LeaveCorrectionSource = {
  requestId: string;
  requestVersion: number;
  expectedMarkVersion: number;
};

type AttendanceDraft = { state: string; note: string };
type SavedAttendanceMark = { id: string; stateCode: string; note: string | null };

/** Refresh only the reviewed mark; never overwrite a newer local observation. */
export function reconcileReviewedAttendanceDraft(
  drafts: Record<string, AttendanceDraft>,
  previous: SavedAttendanceMark,
  saved: SavedAttendanceMark,
): Record<string, AttendanceDraft> {
  const current = drafts[previous.id];
  if (
    saved.id !== previous.id ||
    !current ||
    current.state !== previous.stateCode ||
    current.note !== (previous.note ?? "")
  )
    return drafts;
  return { ...drafts, [saved.id]: { state: saved.stateCode, note: saved.note ?? "" } };
}

export function leaveCorrectionSource(
  evidence: AttendanceLeaveEvidence,
  markVersion: number,
): LeaveCorrectionSource {
  if (
    evidence.status !== "APPROVED" ||
    !Number.isSafeInteger(evidence.requestVersion) ||
    evidence.requestVersion < 1 ||
    !Number.isSafeInteger(markVersion) ||
    markVersion < 0
  ) {
    throw new Error("Refresh the approved leave and attendance before requesting a correction.");
  }
  return {
    requestId: evidence.requestId,
    requestVersion: evidence.requestVersion,
    expectedMarkVersion: markVersion,
  };
}
