"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  apiUrl, parentRequestCategories, parentRequestLabel as label, parentRequestMutation,
  parentRequestProblem, parentRequestStatuses, type ParentRequestAssignee,
  type ParentRequestDetail, type ParentRequestSummary,
} from "@/lib/parent-requests";

type Workspace = { schoolId: string; schoolName: string; canManage: boolean; selfStaffProfileId?: string; requests: ParentRequestSummary[]; assignees: ParentRequestAssignee[] };

export function ParentRequestWorkspace() {
  const [data, setData] = useState<Workspace | null>(null);
  const [detail, setDetail] = useState<ParentRequestDetail | null>(null);
  const [status, setStatus] = useState("");
  const [category, setCategory] = useState("");
  const [search, setSearch] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [decision, setDecision] = useState("");
  const decisionTrigger = useRef<HTMLButtonElement | null>(null);
  const detailHeading = useRef<HTMLHeadingElement | null>(null);

  const load = useCallback(async () => {
    const params = new URLSearchParams();
    if (status) params.set("status", status);
    if (category) params.set("category", category);
    if (search.trim()) params.set("search", search.trim());
    const response = await fetch(`${apiUrl}/api/v1/parent-requests?${params}`, { credentials: "include", cache: "no-store" });
    if (!response.ok) throw new Error(await parentRequestProblem(response, "Parent requests are unavailable."));
    setData(await response.json() as Workspace);
  }, [category, search, status]);

  const openDetail = useCallback(async (id: string, schoolId?: string) => {
    const params = schoolId ? `?schoolId=${encodeURIComponent(schoolId)}` : "";
    const response = await fetch(`${apiUrl}/api/v1/parent-requests/${id}${params}`, { credentials: "include", cache: "no-store" });
    if (!response.ok) throw new Error(await parentRequestProblem(response, "This parent request is unavailable."));
    setDetail(await response.json() as ParentRequestDetail);
    setDecision("");
  }, []);

  useEffect(() => { void Promise.resolve().then(load).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Parent requests are unavailable.")); }, [load]);

  async function mutate(path: string, body: Record<string, unknown>, success: string) {
    if (!data || !detail) return false;
    setBusy(true); setError(""); setNotice("");
    let saved = false;
    try {
      await parentRequestMutation(path, { schoolId: data.schoolId, ...body });
      saved = true;
      setNotice(success); await load(); await openDetail(detail.request.id, data.schoolId);
    } catch (reason) {
      setError(saved
        ? "The change was saved, but updated records could not be loaded. Refresh before making another change."
        : reason instanceof Error ? reason.message : "The change could not be saved.");
    }
    finally { setBusy(false); }
    return saved;
  }

  async function assign(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!detail) return; const form = new FormData(event.currentTarget);
    await mutate(`/api/v1/parent-requests/${detail.request.id}/assignment`, { staffProfileId: form.get("staffProfileId"), priority: form.get("priority"), expectedVersion: detail.request.rowVersion, reason: form.get("reason") }, "Responsibility and priority updated.");
  }

  async function reply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!detail) return;
    // React clears currentTarget after dispatch, before the network await finishes.
    const element = event.currentTarget;
    const form = new FormData(element);
    const saved = await mutate(`/api/v1/parent-requests/${detail.request.id}/messages`, { message: form.get("message"), internalNote: form.get("internalNote") === "on", idempotencyKey: crypto.randomUUID(), expectedVersion: detail.request.rowVersion }, form.get("internalNote") === "on" ? "Internal note recorded." : "Response sent to the family.");
    if (saved) element.reset();
  }

  function transition(action: string, trigger: HTMLButtonElement) {
    decisionTrigger.current = trigger;
    setDecision(action);
  }

  function cancelDecision() {
    setDecision("");
    decisionTrigger.current?.focus();
  }

  async function confirmTransition(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!detail || !decision || busy) return;
    const reason = String(new FormData(event.currentTarget).get("reason") ?? "").trim();
    if (reason.length < 3 || reason.length > 500) {
      setError("Enter a reason between 3 and 500 characters."); return;
    }
    const saved = await mutate(`/api/v1/parent-requests/${detail.request.id}/transitions`, { action: decision, expectedVersion: detail.request.rowVersion, reason }, `${label(decision)} recorded with audit history.`);
    if (saved) { setDecision(""); detailHeading.current?.focus(); }
  }

  const assignedToMe = Boolean(detail && data?.selfStaffProfileId && detail.request.assignedStaffProfileId === data.selfStaffProfileId);
  if (!data) return <section className="request-state" aria-live="polite"><span /><h1>{error ? "Parent requests are unavailable" : "Opening the parent request desk…"}</h1>{error && <p role="alert">{error}</p>}</section>;

  return <section className="request-centre request-centre--workspace">
    <header className="request-hero"><div><span>Structured family support</span><h1>Clear ownership. One accountable conversation.</h1><p>Route family questions to the right staff member, preserve decisions, and close the loop without exposing contact details.</p></div><aside><strong>{data.requests.filter((item) => !["RESOLVED", "CLOSED"].includes(item.status)).length}</strong><span>visible active requests</span></aside></header>
    <form className="request-filters" onSubmit={(event) => { event.preventDefault(); void load(); }} role="search"><label>Status<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">All statuses</option>{parentRequestStatuses.map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label>Category<select value={category} onChange={(event) => setCategory(event.target.value)}><option value="">All categories</option>{parentRequestCategories.map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label className="is-wide">Search<input value={search} onChange={(event) => setSearch(event.target.value)} maxLength={120} placeholder="Request number, subject, or learner" /></label><button>Apply filters</button></form>
    {(error || notice) && <div className={`request-banner ${error ? "is-error" : "is-success"}`} role={error ? "alert" : "status"}>{error || notice}</div>}
    <div className="request-layout">
      <aside className="request-list" aria-label="Parent request queue">{data.requests.length ? data.requests.map((item) => <button type="button" key={item.id} className={detail?.request.id === item.id ? "is-active" : ""} onClick={() => void openDetail(item.id, data.schoolId).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Request unavailable."))}><span>{item.subject}</span><small>{item.requestNumber} · {item.learnerName}</small><div><em data-state={item.status}>{label(item.status)}</em><em data-priority={item.priority}>{label(item.priority)}</em></div></button>) : <div className="request-empty"><strong>No matching requests</strong><p>The queue is clear for these filters.</p></div>}</aside>
<main className="request-detail">{detail ? <><header><div><span>{detail.request.requestNumber} · {label(detail.request.category)}</span><h2 ref={detailHeading} tabIndex={-1}>{detail.request.subject}</h2><p>{detail.request.guardianName} · for {detail.request.learnerName}</p></div><em data-state={detail.request.status}>{label(detail.request.status)}</em></header><section className="request-ownership"><div><span>Owner</span><strong>{detail.request.assignedStaffName || "Unassigned"}</strong></div><div><span>Priority</span><strong>{label(detail.request.priority)}</strong></div><div><span>Last activity</span><strong>{new Date(detail.request.lastActivityAt).toLocaleString("en-IN")}</strong></div></section>{data.canManage && <form className="request-assignment" onSubmit={assign}><label>Assigned staff<select name="staffProfileId" defaultValue={detail.request.assignedStaffProfileId || ""} required><option value="">Choose an accountable staff member</option>{data.assignees.map((item) => <option key={item.staffProfileId} value={item.staffProfileId}>{item.displayName} · {item.designation || item.employeeCode}</option>)}</select></label><label>Priority<select name="priority" defaultValue={detail.request.priority}>{["LOW", "NORMAL", "HIGH", "URGENT"].map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label className="is-wide">Assignment reason<input name="reason" minLength={3} maxLength={500} defaultValue="Assigned to the responsible school team" required /></label><button disabled={busy}>Update ownership</button></form>}
        <div className="request-thread">{detail.messages.map((message) => <article key={message.id} data-author={message.authorType} className={message.internalNote ? "is-internal" : ""}><header><strong>{message.authorName}</strong><span>{message.internalNote ? "Internal note · family cannot see" : label(message.authorType)}</span><time dateTime={message.createdAt}>{new Date(message.createdAt).toLocaleString("en-IN")}</time></header><p>{message.body}</p></article>)}</div>
        {detail.resolutionSummary && <aside className="request-resolution"><strong>Recorded resolution</strong><p>{detail.resolutionSummary}</p></aside>}
        {assignedToMe && !["RESOLVED", "CLOSED"].includes(detail.request.status) && <form className="request-reply" onSubmit={reply}><label htmlFor="staff-request-reply">Response or internal note</label><textarea id="staff-request-reply" name="message" maxLength={8000} required rows={4} /><label className="request-check"><input name="internalNote" type="checkbox" /> Internal note — never shown to the family</label><button disabled={busy}>Record response</button></form>}
        <div className="request-actions">{assignedToMe && detail.request.status === "ASSIGNED" && <button disabled={busy} onClick={(event) => transition("START", event.currentTarget)}>Start work</button>}{assignedToMe && ["ASSIGNED", "IN_PROGRESS"].includes(detail.request.status) && <button disabled={busy} onClick={(event) => transition("RESOLVE", event.currentTarget)}>Resolve with summary</button>}{data.canManage && detail.request.status === "RESOLVED" && <button disabled={busy} onClick={(event) => transition("CLOSE", event.currentTarget)}>Close request</button>}{data.canManage && detail.request.status === "CLOSED" && <button disabled={busy} onClick={(event) => transition("REOPEN", event.currentTarget)}>Reopen &amp; reassign</button>}</div>
        {decision && <form className="request-assignment" onSubmit={confirmTransition} aria-label="Confirm request decision"><h3>{label(decision)} request?</h3><label className="is-wide">{decision === "RESOLVE" ? "Resolution summary" : decision === "CLOSE" ? "Closure reason" : "Reason for decision"}<textarea name="reason" autoFocus required minLength={3} maxLength={500} rows={3} disabled={busy} /></label><div className="request-actions"><button disabled={busy}>Confirm {label(decision).toLowerCase()}</button><button type="button" disabled={busy} onClick={cancelDecision}>Cancel decision</button></div></form>}
        <details className="request-history"><summary>Audit history · {detail.history.length} events</summary>{detail.history.map((item) => <article key={item.id}><span>{label(item.eventType)}</span><strong>{item.actorName}</strong><p>{item.reason}</p><time dateTime={item.occurredAt}>{new Date(item.occurredAt).toLocaleString("en-IN")}</time></article>)}</details>
      </> : <div className="request-empty request-empty--detail"><strong>Select a family request</strong><p>Ownership, conversation, decisions, and audit history will appear here.</p></div>}</main>
    </div>
  </section>;
}
