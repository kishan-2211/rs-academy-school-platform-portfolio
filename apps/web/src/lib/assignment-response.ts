type ResponseState = {
  status: string;
  submissionMode: string;
  submission: { state: string } | null;
};

// Mirror the existing family/student API edit boundary. The server still
// enforces scope, dates, versioning and review locks for every write.
export function canRespondToAssignment(assignment: ResponseState): boolean {
  return assignment.status === "PUBLISHED"
    && assignment.submissionMode !== "OFFLINE"
    && ["NOT_STARTED", "DRAFT", "RETURNED"].includes(assignment.submission?.state ?? "NOT_STARTED");
}
