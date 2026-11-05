/** RTE enrolment must retain the original application session; missing evidence fails closed. */
export function admissionHandoffSessions<T extends { id: string }>(
  admissionType: "REGULAR" | "RTE",
  originalSessionId: string | null,
  sessions: readonly T[],
): readonly T[] {
  return admissionType === "RTE"
    ? sessions.filter((session) => session.id === originalSessionId)
    : sessions;
}
