export type PtmSlot = { id: string; startsAt: string; endsAt: string; available: boolean; selected: boolean };
export type PtmBooking = { id: string; slotId: string; status: string; topic: string | null; rowVersion: number };
export type PtmEvent = {
  id: string; titleEn: string; titleHi: string | null; instructionsEn: string | null;
  instructionsHi: string | null; venue: string; teacherName: string; className: string;
  eventDate: string; bookingOpensAt: string; bookingClosesAt: string; changesCloseAt: string;
  slots: PtmSlot[]; booking: PtmBooking | null; timeZone: string; status: string;
};
export type PtmSchedule = { learnerId: string; learnerName: string; events: PtmEvent[] };
export type PtmStaffEvent = Omit<PtmEvent, "slots" | "booking" | "instructionsEn" | "instructionsHi"> & {
  sessionId: string; sectionId: string; teacherMembershipId: string; reminderMinutesBefore: number; rowVersion: number;
  slots: { id: string; startsAt: string; endsAt: string; status: string; booking: { id: string; learnerName: string; guardianName: string; topic: string | null } | null }[];
};
export type PtmWorkspaceData = {
  schoolId: string; schoolName: string; canManage: boolean;
  sessions: { id: string; name: string; startsOn: string; endsOn: string; status: string }[];
  sections: { id: string; sessionId: string; gradeCode: string; name: string; timeZone: string }[];
  teachers: { membershipId: string; staffProfileId: string; employeeCode: string; name: string }[];
  events: PtmStaffEvent[];
};

// Resolve school wall-clock input independently of the browser timezone; reject DST gaps/ambiguity.
export function ptmLocalInstant(value: string, timeZone: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) throw new Error("Enter a complete date and time.");
  const wall = Date.parse(`${value}:00Z`);
  if (!Number.isFinite(wall) || new Date(wall).toISOString().slice(0, 16) !== value) throw new Error("Enter a valid date and time.");
  const formatter = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
  function localStamp(instant: number) {
    const parts = Object.fromEntries(formatter.formatToParts(new Date(instant)).map((part) => [part.type, part.value]));
    return Date.parse(`${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}Z`);
  }
  const offsets = new Set([-36, 0, 36].map((hours) => { const probe = wall + hours * 3600000; return localStamp(probe) - probe; }));
  const candidates = [...offsets].map((offset) => wall - offset).filter((candidate) => localStamp(candidate) === wall);
  const candidate = candidates[0];
  if (candidates.length !== 1 || candidate === undefined) throw new Error("This local time is skipped or repeated by a clock change. Choose another time.");
  return new Date(candidate).toISOString();
}

export function ptmWindow(event: PtmEvent, now: number) {
  const active = event.status === "PUBLISHED";
  const booked = event.booking?.status === "BOOKED";
  const selected = event.slots.find((slot) => slot.id === event.booking?.slotId);
  return {
    canBook: active && !booked && now >= Date.parse(event.bookingOpensAt) && now <= Date.parse(event.bookingClosesAt),
    canChange: active && booked && now <= Date.parse(event.changesCloseAt) && Boolean(selected && Date.parse(selected.startsAt) > now),
  };
}

export function ptmTime(value: string, timeZone: string) {
  return new Intl.DateTimeFormat("en-IN", { timeZone, dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
