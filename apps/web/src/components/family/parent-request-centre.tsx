"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import {
  apiUrl, parentRequestCategories, parentRequestLabel as label, parentRequestMutation,
  parentRequestProblem, type ParentRequestDetail, type ParentRequestSummary,
} from "@/lib/parent-requests";

import { familyChildren, type FamilyChild as Child } from "@/lib/family-children";

export function ParentRequestCentre() {
  const [children, setChildren] = useState<Child[]>([]);
  const [learnerId, setLearnerId] = useState("");
  const [items, setItems] = useState<ParentRequestSummary[]>([]);
  const [detail, setDetail] = useState<ParentRequestDetail | null>(null);
  const [compose, setCompose] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadChildren = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/family/children`, { credentials: "include", cache: "no-store" });
    if (!response.ok) throw new Error(await parentRequestProblem(response, "Your linked learners are unavailable."));
    const linked = familyChildren(await response.json());
    setChildren(linked);
    setLearnerId((current) => current || linked[0]?.learnerId || "");
  }, []);

  const loadList = useCallback(async (childId: string) => {
    if (!childId) return;
    const response = await fetch(`${apiUrl}/api/v1/family/children/${childId}/requests`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(await parentRequestProblem(response, "Support requests are unavailable."));
    const data = await response.json() as { requests: ParentRequestSummary[] };
    setItems(data.requests);
    setDetail((current) => current && data.requests.some((item) => item.id === current.request.id) ? current : null);
  }, []);

  const openDetail = useCallback(async (id: string) => {
    const response = await fetch(`${apiUrl}/api/v1/family/children/${learnerId}/requests/${id}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(await parentRequestProblem(response, "This support request is unavailable."));
    setDetail(await response.json() as ParentRequestDetail);
  }, [learnerId]);

  useEffect(() => { void Promise.resolve().then(loadChildren).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Family access is unavailable.")); }, [loadChildren]);
  useEffect(() => { if (learnerId) void Promise.resolve().then(() => loadList(learnerId)).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Support requests are unavailable.")); }, [learnerId, loadList]);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    setBusy(true); setError(""); setNotice("");
    try {
      const result = await parentRequestMutation<{ id: string }>(`/api/v1/family/children/${learnerId}/requests`, {
        category: values.get("category"), subject: values.get("subject"), message: values.get("message"),
        idempotencyKey: crypto.randomUUID(),
      });
      form.reset(); setCompose(false); setNotice("Your request has been sent to the school.");
      await loadList(learnerId); await openDetail(result.id);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The request could not be sent."); }
    finally { setBusy(false); }
  }

  async function reply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail) return;
    const form = event.currentTarget;
    const values = new FormData(form);
    setBusy(true); setError(""); setNotice("");
    try {
      await parentRequestMutation(`/api/v1/family/children/${learnerId}/requests/${detail.request.id}/messages`, {
        message: values.get("message"), idempotencyKey: crypto.randomUUID(),
        expectedVersion: detail.request.rowVersion,
      });
      form.reset(); setNotice("Your reply has been added.");
      await loadList(learnerId); await openDetail(detail.request.id);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Your reply could not be sent."); }
    finally { setBusy(false); }
  }

  const selectedChild = children.find((child) => child.learnerId === learnerId);
  return <main className="request-centre request-centre--family" id="main-content">
    <header className="request-hero"><div><span>Family support</span><h1>Ask the school. Keep every update together.</h1><p>Choose a clear category instead of sending an untracked message. Only authorized school staff can see and work on your request.</p></div><aside><strong>{items.filter((item) => !["RESOLVED", "CLOSED"].includes(item.status)).length}</strong><span>requests needing attention</span></aside></header>
    <section className="request-toolbar" aria-label="Support request controls">
      <label>Child<select value={learnerId} onChange={(event) => { setLearnerId(event.target.value); setDetail(null); }} aria-label="Choose child">{children.map((child) => <option key={child.learnerId} value={child.learnerId}>{child.legalName} · {label(child.gradeCode)}{child.sectionName ? ` ${child.sectionName}` : ""}</option>)}</select></label>
      <button type="button" onClick={() => setCompose((value) => !value)} aria-expanded={compose}>{compose ? "Cancel new request" : "New support request"}</button>
    </section>
    {(error || notice) && <div className={`request-banner ${error ? "is-error" : "is-success"}`} role={error ? "alert" : "status"}>{error || notice}</div>}
    {compose && <form className="request-compose" onSubmit={create}><header><span>New request for</span><h2>{selectedChild?.legalName || "your child"}</h2></header><label>Category<select name="category" defaultValue="GENERAL">{parentRequestCategories.map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label><label>Subject<input name="subject" minLength={3} maxLength={180} required placeholder="A short description" /></label><label className="is-wide">How can the school help?<textarea name="message" maxLength={8000} required rows={5} /></label><footer><p>Do not include passwords, payment card details, or unnecessary medical information.</p><button disabled={busy}>Send request</button></footer></form>}
    <div className="request-layout">
      <aside className="request-list" aria-label="Your support requests">{items.length ? items.map((item) => <button type="button" key={item.id} className={detail?.request.id === item.id ? "is-active" : ""} onClick={() => void openDetail(item.id).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Request unavailable."))}><span>{item.subject}</span><small>{item.requestNumber} · {label(item.category)}</small><em data-state={item.status}>{label(item.status)}</em></button>) : <div className="request-empty"><strong>No requests yet</strong><p>When you need help, open a structured request here.</p></div>}</aside>
      <section className="request-detail">{detail ? <><header><div><span>{detail.request.requestNumber} · {label(detail.request.category)}</span><h2>{detail.request.subject}</h2><p>For {detail.request.learnerName} · {detail.request.assignedStaffName ? `Assigned to ${detail.request.assignedStaffName}` : "Waiting for school assignment"}</p></div><em data-state={detail.request.status}>{label(detail.request.status)}</em></header><div className="request-thread">{detail.messages.map((message) => <article key={message.id} data-author={message.authorType}><header><strong>{message.authorName}</strong><time dateTime={message.createdAt}>{new Date(message.createdAt).toLocaleString("en-IN")}</time></header><p>{message.body}</p></article>)}</div>{detail.resolutionSummary && <aside className="request-resolution"><strong>School resolution</strong><p>{detail.resolutionSummary}</p></aside>}{detail.request.status !== "CLOSED" && <form className="request-reply" onSubmit={reply}><label htmlFor="family-request-reply">Add information or reply</label><textarea id="family-request-reply" name="message" maxLength={8000} required rows={3} /><button disabled={busy}>Send reply</button></form>}</> : <div className="request-empty request-empty--detail"><strong>Select a request</strong><p>The full conversation and latest school status will appear here.</p></div>}</section>
    </div>
  </main>;
}
