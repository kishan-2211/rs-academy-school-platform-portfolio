export type JourneyKind = "PICKUP" | "DROP";
export type JourneyChoice = JourneyKind | "FULL_DAY";
export type TransportPlanPolicy = {
  id?: string | null; campusId: string; campusName: string; timeZone: string; version: number;
  enabled: boolean; pickupClosesAt?: string | null; dropClosesAt?: string | null;
};
export type TransportPolicyWorkspace = { schoolId: string; schoolName: string; canConfigure: boolean; policies: TransportPlanPolicy[] };
export type TransportPlanChange = { id: string; serviceDate: string; journeys: JourneyChoice; notTravelling: boolean; occurredAt: string };
export type TransportPlanJourney = {
  journeyKind: JourneyKind; assignmentId: string; serviceLabel: string; stopLabel: string; scheduledTime: string;
  changesCloseAt?: string | null; changesAllowed: boolean; notTravelling: boolean;
  revisionId?: string | null; assignmentChanged: boolean;
};
export type FamilyTransportDay = {
  learnerId: string; serviceDate: string; timeZone: string; enabled: boolean; observedAt: string;
  journeys: TransportPlanJourney[]; history: TransportPlanChange[];
};
export type TransportChangeBody = {
  serviceDate: string; journeys: JourneyChoice; notTravelling: boolean;
  pickupAssignmentId: string | null; dropAssignmentId: string | null;
  expectedPickupRevisionId: string | null; expectedDropRevisionId: string | null;
};
export type TransportPlanAttempt = { fingerprint: string; clientRequestId: string; body: TransportChangeBody };
export type TransportProblemCode = "session" | "access" | "changed" | "closed" | "disabled" | "invalid" | "csrf" | "unavailable" | "response";
export class TransportPlanProblem extends Error {
  readonly code: TransportProblemCode;
  readonly uncertain: boolean;
  constructor(code: TransportProblemCode, uncertain = false) { super(code); this.name = "TransportPlanProblem"; this.code = code; this.uncertain = uncertain; }
}
const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const uuid = (value: unknown): value is string => typeof value === "string" && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const optionalUuid = (value: unknown) => value == null || uuid(value);
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === "object" && !Array.isArray(value);
const instant = (value: unknown): value is string => typeof value === "string" && /T.*(?:Z|[+-]\d\d:\d\d)$/.test(value) && Number.isFinite(Date.parse(value));
const time = (value: unknown): value is string => typeof value === "string" && /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(value);
const date = (value: unknown): value is string => {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
};
const zone = (value: unknown): value is string => {
  if (typeof value !== "string") return false;
  try { new Intl.DateTimeFormat("en-IN", { timeZone: value }); return true; } catch { return false; }
};
const text = (value: unknown): value is string => typeof value === "string" && value.length > 0;
const choice = (value: unknown): value is JourneyChoice => ["PICKUP", "DROP", "FULL_DAY"].includes(String(value));
const invalidResponse = () => new TransportPlanProblem("response");

export function nonTravelPath(learnerId: string): string {
  if (!uuid(learnerId)) throw new TransportPlanProblem("invalid");
  return `/api/v1/family/children/${learnerId}/transport/non-travel`;
}
export function nonTravelPolicyPath(schoolId: string): string {
  if (!uuid(schoolId)) throw new TransportPlanProblem("invalid");
  return `/api/v1/transport/non-travel/policies?schoolId=${schoolId}`;
}
export function readTransportChange(value: unknown): TransportPlanChange {
  if (!record(value) || !uuid(value.id) || !date(value.serviceDate) || !choice(value.journeys)
    || typeof value.notTravelling !== "boolean" || !instant(value.occurredAt)) throw invalidResponse();
  return { id: value.id, serviceDate: value.serviceDate, journeys: value.journeys, notTravelling: value.notTravelling, occurredAt: value.occurredAt };
}
export function readFamilyTransportDay(value: unknown, learnerId: string): FamilyTransportDay {
  if (!record(value) || value.learnerId !== learnerId || !uuid(learnerId) || !date(value.serviceDate)
    || !zone(value.timeZone) || typeof value.enabled !== "boolean" || !instant(value.observedAt)
    || !Array.isArray(value.journeys) || value.journeys.length > 2 || !Array.isArray(value.history) || value.history.length > 30) throw invalidResponse();
  const journeys = value.journeys.map((item: unknown): TransportPlanJourney => {
    if (!record(item) || !["PICKUP", "DROP"].includes(String(item.journeyKind)) || !uuid(item.assignmentId)
      || !text(item.serviceLabel) || !text(item.stopLabel) || !time(item.scheduledTime)
      || (item.changesCloseAt != null && !instant(item.changesCloseAt)) || !optionalUuid(item.revisionId)
      || typeof item.changesAllowed !== "boolean" || typeof item.notTravelling !== "boolean" || typeof item.assignmentChanged !== "boolean"
      || (item.assignmentChanged && (item.revisionId == null || item.notTravelling))
      || (item.changesAllowed && (!value.enabled || !instant(item.changesCloseAt) || Date.parse(item.changesCloseAt) <= Date.parse(String(value.observedAt))))) throw invalidResponse();
    return { journeyKind: item.journeyKind as JourneyKind, assignmentId: item.assignmentId, serviceLabel: item.serviceLabel, stopLabel: item.stopLabel,
      scheduledTime: item.scheduledTime, changesCloseAt: item.changesCloseAt as string | null | undefined, changesAllowed: item.changesAllowed,
      notTravelling: item.notTravelling, revisionId: item.revisionId as string | null | undefined, assignmentChanged: item.assignmentChanged };
  });
  if (new Set(journeys.map((item) => item.journeyKind)).size !== journeys.length) throw invalidResponse();
  const history = value.history.map(readTransportChange);
  if (new Set(history.map((item) => item.id)).size !== history.length || history.some((item) => {
    const age = Date.parse(`${value.serviceDate}T00:00:00Z`) - Date.parse(`${item.serviceDate}T00:00:00Z`);
    return age < 0 || age > 30 * 86400000;
  })) throw invalidResponse();
  return { learnerId, serviceDate: value.serviceDate, timeZone: value.timeZone, enabled: value.enabled, observedAt: value.observedAt, journeys, history };
}
export function readTransportPolicy(value: unknown): TransportPlanPolicy {
  if (!record(value) || !uuid(value.campusId) || !text(value.campusName) || !zone(value.timeZone)
    || !Number.isSafeInteger(value.version) || Number(value.version) < -1 || typeof value.enabled !== "boolean"
    || !optionalUuid(value.id) || (Number(value.version) >= 0 && !uuid(value.id))
    || (Number(value.version) === -1 && (value.enabled || value.id != null || value.pickupClosesAt != null || value.dropClosesAt != null))
    || (value.pickupClosesAt != null && !time(value.pickupClosesAt)) || (value.dropClosesAt != null && !time(value.dropClosesAt))
    || (value.enabled && (!time(value.pickupClosesAt) || !time(value.dropClosesAt)))) throw invalidResponse();
  return { id: value.id as string | null | undefined, campusId: value.campusId, campusName: value.campusName, timeZone: value.timeZone,
    version: Number(value.version), enabled: value.enabled, pickupClosesAt: value.pickupClosesAt as string | null | undefined, dropClosesAt: value.dropClosesAt as string | null | undefined };
}
export function readTransportPolicies(value: unknown, schoolId: string): TransportPolicyWorkspace {
  if (!record(value) || !uuid(schoolId) || value.schoolId !== schoolId || !text(value.schoolName)
    || value.canConfigure !== true || !Array.isArray(value.policies)) throw invalidResponse();
  const policies = value.policies.map(readTransportPolicy);
  if (new Set(policies.map((item) => item.campusId)).size !== policies.length) throw invalidResponse();
  return { schoolId, schoolName: value.schoolName, canConfigure: true, policies };
}

export function selectedTransportJourneys(data: FamilyTransportDay, selected: JourneyChoice): TransportPlanJourney[] {
  return data.journeys.filter((item) => selected === "FULL_DAY" || item.journeyKind === selected);
}
export function canChangeTransportPlan(data: FamilyTransportDay, selected: JourneyChoice, notTravelling: boolean, now: number): boolean {
  const journeys = selectedTransportJourneys(data, selected);
  return data.enabled && Number.isFinite(now) && journeys.length === (selected === "FULL_DAY" ? 2 : 1)
    && journeys.every((item) => item.changesAllowed && !!item.changesCloseAt && now < Date.parse(item.changesCloseAt)
      && (notTravelling || item.notTravelling));
}
export function transportChangeBody(data: FamilyTransportDay, selected: JourneyChoice, notTravelling: boolean): TransportChangeBody {
  const items = selectedTransportJourneys(data, selected);
  if (!choice(selected) || items.length !== (selected === "FULL_DAY" ? 2 : 1)) throw new TransportPlanProblem("invalid");
  const pickup = items.find((item) => item.journeyKind === "PICKUP"), drop = items.find((item) => item.journeyKind === "DROP");
  return { serviceDate: data.serviceDate, journeys: selected, notTravelling, pickupAssignmentId: pickup?.assignmentId ?? null,
    dropAssignmentId: drop?.assignmentId ?? null, expectedPickupRevisionId: pickup?.revisionId ?? null, expectedDropRevisionId: drop?.revisionId ?? null };
}
// Retry identity and child data stay in component memory; never persist them in browser storage.
export function transportPlanAttempt(previous: TransportPlanAttempt | null, learnerId: string, body: TransportChangeBody,
  makeKey = () => crypto.randomUUID()): TransportPlanAttempt {
  nonTravelPath(learnerId);
  const fingerprint = JSON.stringify([learnerId, body]);
  return previous?.fingerprint === fingerprint ? previous : { fingerprint, clientRequestId: makeKey(), body: { ...body } };
}
export async function transportPlanApi(path: string, options: { method?: "GET" | "POST" | "PUT"; body?: unknown; signal: AbortSignal }): Promise<unknown> {
  const method = options.method ?? "GET";
  const family = /^\/api\/v1\/family\/children\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/transport\/non-travel$/i.test(path);
  const policies = /^\/api\/v1\/transport\/non-travel\/policies(?:\?schoolId=[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/i.test(path);
  if (!((family && (method === "GET" || method === "POST")) || (policies && (method === "GET" || method === "PUT"))
    || (path === "/api/v1/family/children" && method === "GET"))) throw new TransportPlanProblem("invalid");
  const headers: Record<string, string> = {};
  try {
    if (method !== "GET") {
      const readToken = () => document.cookie.split(";").map((part) => part.trim()).find((part) => part.startsWith("educore_csrf="))?.slice(13);
      let token = readToken();
      if (!token) {
        const response = await fetch(`${base}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store", signal: options.signal });
        if (!response.ok) throw new TransportPlanProblem(response.status === 401 ? "session" : "csrf");
        token = readToken();
      }
      if (!token) throw new TransportPlanProblem("csrf");
      try { headers["X-CSRF-TOKEN"] = decodeURIComponent(token); } catch { throw new TransportPlanProblem("csrf"); }
      headers["Content-Type"] = "application/json";
    }
    const response = await fetch(`${base}${path}`, { method, credentials: "include", cache: "no-store", signal: options.signal,
      headers, ...(method !== "GET" ? { body: JSON.stringify(options.body) } : {}) });
    if (!response.ok) {
      let code = "";
      if (response.status < 500) { try { const body = await response.json() as { code?: unknown }; code = typeof body.code === "string" ? body.code : ""; } catch { /* Do not expose arbitrary server details. */ } }
      throw new TransportPlanProblem(response.status === 401 ? "session" : response.status === 403 || response.status === 404 ? "access"
        : code === "TRANSPORT_NON_TRAVEL_CLOSED" ? "closed" : code === "TRANSPORT_NON_TRAVEL_DISABLED" ? "disabled"
        : response.status === 409 ? "changed" : response.status === 400 ? "invalid" : "unavailable", method !== "GET" && response.status >= 500);
    }
    try { return await response.json(); } catch { throw new TransportPlanProblem("response", method !== "GET"); }
  } catch (error) {
    if (options.signal.aborted || error instanceof TransportPlanProblem) throw error;
    throw new TransportPlanProblem("unavailable", method !== "GET");
  }
}
