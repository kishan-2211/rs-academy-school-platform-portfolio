"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { ArrowUpRight } from "@/components/icons";
import { PushOperationsPanel } from "@/components/workspace/push-operations-panel";
import { BellIcon, MessageIcon, PlusIcon } from "@/components/workspace/workspace-icons";

type Provider = { channel: string; environment: string; providerCode: string; senderIdentity?: string; status: string };
type Template = { id: string; code: string; revisionNumber: number; supersedesTemplateId?: string; name: string; category: string; languageMode: string; titleEn?: string; titleHi?: string; bodyEn?: string; bodyHi?: string; requiresAcknowledgement: boolean; channels: string[]; status: string; rowVersion: number };
type Section = { id: string; displayName: string; gradeCode: string; sessionName: string };
type Learner = { id: string; legalName: string; admissionNumber?: string; gradeCode: string; classSectionId?: string; sectionName?: string };
type Announcement = { id: string; category: string; priority: string; audienceKind: string; audienceReferenceId?: string; audienceLabel: string; languageMode: string; titleEn?: string; titleHi?: string; bodyEn?: string; bodyHi?: string; requiresAcknowledgement: boolean; requestedChannels: string[]; scheduledFor: string; expiresAt?: string; status: string; creatorName: string; deciderName?: string; decisionReason?: string; recipientCount: number; eligibleRecipientCount: number; readCount: number; acknowledgementCount: number; rowVersion: number };
type Snapshot = { schoolId: string; schoolName: string; canConfigure: boolean; canSend: boolean; canApprove: boolean; providers: Provider[]; templates: Template[]; sections: Section[]; learners: Learner[]; announcements: Announcement[] };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const label = (value: string) => value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
const localDateTime = (value = new Date(Date.now() + 5 * 60_000)) => new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
function cookie(name: string) { return document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1); }
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new Error("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string) { try { const body = await response.json() as { detail?: string }; return body.detail || fallback; } catch { return fallback; } }

export function CommunicationWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<"announcements" | "compose" | "templates" | "push">("announcements");
  const [selectedId, setSelectedId] = useState("");
  const [editingId, setEditingId] = useState("");
  const [audienceKind, setAudienceKind] = useState("ALL_FAMILIES");
  const [languageMode, setLanguageMode] = useState("BILINGUAL");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/communications`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "The communication workspace could not be opened."));
    const snapshot = await response.json() as Snapshot;
    setData(snapshot);
    setSelectedId((current) => snapshot.announcements.some((item) => item.id === current) ? current : snapshot.announcements[0]?.id || "");
  }, []);

  useEffect(() => { Promise.resolve().then(load).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "The communication workspace could not be opened.")); }, [load]);
  const selected = useMemo(() => data?.announcements.find((item) => item.id === selectedId) ?? null, [data, selectedId]);
  const editing = useMemo(() => data?.announcements.find((item) => item.id === editingId) ?? null, [data, editingId]);
  const pending = data?.announcements.filter((item) => item.status === "PENDING_APPROVAL") ?? [];

  async function mutate(path: string, init: RequestInit, success: string) {
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiUrl}${path}`, { ...init, credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken(), ...(init.headers || {}) } });
      if (!response.ok) throw new Error(await problem(response, "The communication change could not be saved."));
      await load(); setNotice(success); return true;
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The communication change could not be saved."); return false; }
    finally { setBusy(false); }
  }

  function announcementPayload(form: FormData) {
    if (!data) throw new Error("The school scope is unavailable.");
    const selectedTemplate = data.templates.find((item) => item.id === form.get("templateId"));
    const schedule = new Date(String(form.get("scheduledFor")));
    const expiryValue = String(form.get("expiresAt") || "");
    const selectedAudience = String(form.get("audienceKind"));
    const studentAudience = ["ALL_STUDENTS", "SECTION_STUDENTS", "LEARNER_STUDENT"].includes(selectedAudience);
    return {
      schoolId: data.schoolId, templateId: form.get("templateId") || null,
      category: selectedTemplate?.category || form.get("category"), priority: form.get("priority"),
      audienceKind: selectedAudience, audienceReferenceId: form.get("audienceReferenceId") || null,
      languageMode: selectedTemplate?.languageMode || form.get("languageMode"),
      titleEn: selectedTemplate?.titleEn || form.get("titleEn") || null,
      titleHi: selectedTemplate?.titleHi || form.get("titleHi") || null,
      bodyEn: selectedTemplate?.bodyEn || form.get("bodyEn") || null,
      bodyHi: selectedTemplate?.bodyHi || form.get("bodyHi") || null,
      requiresAcknowledgement: selectedTemplate?.requiresAcknowledgement ?? form.get("requiresAcknowledgement") === "on",
      email: studentAudience ? false : selectedTemplate?.channels.includes("EMAIL") ?? form.get("email") === "on",
      sms: studentAudience ? false : selectedTemplate?.channels.includes("SMS") ?? form.get("sms") === "on",
      whatsapp: studentAudience ? false : selectedTemplate?.channels.includes("WHATSAPP") ?? form.get("whatsapp") === "on",
      push: studentAudience ? false : selectedTemplate?.channels.includes("PUSH") ?? form.get("push") === "on",
      scheduledFor: schedule.toISOString(), expiresAt: expiryValue ? new Date(expiryValue).toISOString() : null,
      expectedVersion: editing?.rowVersion ?? null, reason: form.get("reason") || null,
    };
  }

  async function saveAnnouncement(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const form = new FormData(event.currentTarget);
    const payload = announcementPayload(form);
    const path = editing ? `/api/v1/communications/announcements/${editing.id}` : "/api/v1/communications/announcements";
    const method = editing ? "PUT" : "POST";
    if (await mutate(path, { method, body: JSON.stringify(payload) }, editing ? "Draft changes saved." : "Announcement draft created.")) {
      setEditingId(""); setTab("announcements"); event.currentTarget.reset();
    }
  }

  async function createTemplate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const form = new FormData(event.currentTarget); const supersedesId = String(form.get("supersedesTemplateId") || ""); const source = data.templates.find((item) => item.id === supersedesId);
    const payload = { schoolId: data.schoolId, code: source?.code || form.get("code"), revisionNumber: source ? source.revisionNumber + 1 : 1, supersedesTemplateId: supersedesId || null, name: form.get("name"), category: form.get("category"), languageMode: form.get("languageMode"), titleEn: form.get("titleEn") || null, titleHi: form.get("titleHi") || null, bodyEn: form.get("bodyEn") || null, bodyHi: form.get("bodyHi") || null, requiresAcknowledgement: form.get("requiresAcknowledgement") === "on", email: form.get("email") === "on", sms: form.get("sms") === "on", whatsapp: form.get("whatsapp") === "on", push: form.get("push") === "on", reason: form.get("reason") || null };
    if (await mutate("/api/v1/communications/templates", { method: "POST", body: JSON.stringify(payload) }, "Editable bilingual template draft created.")) event.currentTarget.reset();
  }

  async function workflow(path: string, item: Announcement, message: string, extra: Record<string, unknown> = {}) {
    if (!data) return;
    await mutate(`/api/v1/communications/announcements/${item.id}/${path}`, { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, expectedVersion: item.rowVersion, reason: extra.reason || `${label(path)} after authority review`, ...extra }) }, message);
  }

  function startEdit(item: Announcement) { setEditingId(item.id); setAudienceKind(item.audienceKind); setLanguageMode(item.languageMode); setTab("compose"); }

  if (!data) return <section className="communication-state"><MessageIcon /><h1>{error ? "Communication is unavailable" : "Opening the communication workspace…"}</h1>{error && <p>{error}</p>}</section>;
  const audienceOptions = audienceKind.startsWith("SECTION_") ? data.sections : ["LEARNER_FAMILIES", "LEARNER_STUDENT"].includes(audienceKind) ? data.learners : [];
  const studentAudience = ["ALL_STUDENTS", "SECTION_STUDENTS", "LEARNER_STUDENT"].includes(audienceKind);
  const deliveryReady = ["EMAIL", "SMS", "WHATSAPP", "PUSH"].filter((channel) => data.providers.some((provider) => provider.channel === channel && provider.status === "ACTIVE"));

  return <section className="communication-workspace">
    <header className="communication-hero"><div><span>Audience-safe communication</span><h1>The right message, frozen for the right people.</h1><p>Write in Hindi and English, schedule publication, separate creation from approval, and keep delivery and acknowledgement evidence without exposing contact details.</p></div><aside><BellIcon /><strong>{pending.length}</strong><span>Awaiting a second authority</span></aside></header>
    <div className="communication-trust"><span><i className="communication-dot communication-dot--green" /> In-app inbox ready</span><span><i className={`communication-dot communication-dot--${deliveryReady.length ? "green" : "amber"}`} /> External providers: {deliveryReady.length ? deliveryReady.join(", ") : "not configured"}</span><span>Plain text · no hidden HTML</span></div>
    {(error || notice) && <div className={`communication-banner ${error ? "is-error" : "is-success"}`} role="status">{error || notice}</div>}
    <nav className="communication-tabs" aria-label="Communication sections"><button className={tab === "announcements" ? "is-active" : ""} onClick={() => setTab("announcements")}>Announcement desk <b>{data.announcements.length}</b></button><button className={tab === "compose" ? "is-active" : ""} onClick={() => { setEditingId(""); setTab("compose"); }}>Compose</button><button className={tab === "templates" ? "is-active" : ""} onClick={() => setTab("templates")}>Templates</button></nav>

    {(data.canConfigure || data.canApprove) && <button type="button" className="push-operations-toggle" aria-pressed={tab === "push"} onClick={() => setTab(tab === "push" ? "announcements" : "push")}>Phone notification operations</button>}
    {tab === "push" && (data.canConfigure || data.canApprove) && <PushOperationsPanel key={data.schoolId} schoolId={data.schoolId} />}
    {tab === "announcements" && <div className="communication-layout">
      <aside className="communication-list"><header><span>Workflow</span><strong>{data.announcements.length}</strong></header>{data.announcements.length ? data.announcements.map((item) => <button key={item.id} className={selectedId === item.id ? "is-active" : ""} onClick={() => setSelectedId(item.id)}><span>{item.titleEn || item.titleHi}</span><small>{item.audienceLabel} · {new Date(item.scheduledFor).toLocaleString("en-IN")}</small><em data-state={item.status}>{label(item.status)}</em></button>) : <div className="communication-empty-mini"><MessageIcon /><p>No drafts or approved notices yet.</p></div>}</aside>
      <main className="communication-detail">{selected ? <>
        <header><div><span>{label(selected.category)} · {label(selected.priority)}</span><h2>{selected.titleEn || selected.titleHi}</h2><p>{selected.audienceLabel}</p></div><em data-state={selected.status}>{label(selected.status)}</em></header>
        <div className="communication-copy"><article lang="en"><span>English</span><h3>{selected.titleEn || "Not included"}</h3><p>{selected.bodyEn || "This draft has no English version."}</p></article><article lang="hi"><span>हिन्दी</span><h3>{selected.titleHi || "शामिल नहीं"}</h3><p>{selected.bodyHi || "इस मसौदे में हिन्दी संस्करण नहीं है।"}</p></article></div>
        <div className="communication-evidence"><article><span>Frozen recipients</span><strong>{selected.recipientCount}</strong><small>{selected.eligibleRecipientCount} contact eligible</small></article><article><span>Read</span><strong>{selected.readCount}</strong><small>In verified inboxes</small></article><article><span>Acknowledged</span><strong>{selected.acknowledgementCount}</strong><small>{selected.requiresAcknowledgement ? "Requested" : "Not required"}</small></article><article><span>Channels</span><strong>{selected.requestedChannels.length}</strong><small>{selected.requestedChannels.map(label).join(" · ")}</small></article></div>
        <section className="communication-audit"><div><span>Created by</span><strong>{selected.creatorName}</strong></div><div><span>Decision</span><strong>{selected.deciderName || "Waiting"}</strong><small>{selected.decisionReason}</small></div><div><span>Schedule</span><strong>{new Date(selected.scheduledFor).toLocaleString("en-IN")}</strong><small>{selected.expiresAt ? `Expires ${new Date(selected.expiresAt).toLocaleString("en-IN")}` : "No automatic expiry"}</small></div></section>
        <div className="communication-actions">{selected.status === "DRAFT" && data.canSend && <><button onClick={() => startEdit(selected)}>Edit draft</button><button disabled={busy} onClick={() => void workflow("submit", selected, "Draft submitted for independent approval.")}>Submit for approval <ArrowUpRight /></button></>}{selected.status === "PENDING_APPROVAL" && data.canApprove && <><button disabled={busy} onClick={() => void workflow("decision", selected, "Announcement approved; audience and delivery evidence are frozen.", { decision: "APPROVED", reason: "Approved after content and audience review" })}>Approve & freeze</button><button className="is-danger" disabled={busy} onClick={() => void workflow("decision", selected, "Announcement rejected with preserved evidence.", { decision: "REJECTED", reason: "Rejected during content or audience review" })}>Reject</button></>}{!["REJECTED", "CANCELLED"].includes(selected.status) && <button className="is-quiet" disabled={busy} onClick={() => void workflow("cancel", selected, "Announcement cancelled; unsent deliveries were stopped.", { reason: "Cancelled by an authorized school user" })}>Cancel</button>}</div>
      </> : <div className="communication-empty"><MessageIcon /><h2>Compose the first controlled notice</h2><p>No message is sent merely because a draft exists.</p></div>}</main>
    </div>}

    {tab === "compose" && data.canSend && <form key={editing?.id || "new"} className="communication-compose" onSubmit={saveAnnouncement}>
      <header><div><span>{editing ? "Editable draft" : "New communication"}</span><h2>{editing ? "Refine before submission" : "Write once, publish with control"}</h2><p>After submission, content and audience cannot change. A different approver must decide.</p></div><MessageIcon /></header>
      <div className="communication-form-grid"><label>Active template (optional)<select name="templateId" defaultValue=""><option value="">Direct draft</option>{data.templates.filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name} · r{item.revisionNumber}</option>)}</select></label><label>Category<select name="category" defaultValue={editing?.category || "GENERAL"}>{["GENERAL", "ACADEMIC", "ATTENDANCE", "FEES", "TRANSPORT", "EVENT", "EMERGENCY", "OTHER"].map((item) => <option key={item}>{label(item)}</option>)}</select></label><label>Priority<select name="priority" defaultValue={editing?.priority || "NORMAL"}>{["NORMAL", "IMPORTANT", "URGENT", "EMERGENCY"].map((item) => <option key={item}>{label(item)}</option>)}</select></label><label>Audience<select name="audienceKind" value={audienceKind} onChange={(event) => setAudienceKind(event.target.value)}>{["ALL_FAMILIES", "SECTION_FAMILIES", "LEARNER_FAMILIES", "ALL_STUDENTS", "SECTION_STUDENTS", "LEARNER_STUDENT", "ALL_STAFF", "SECTION_STAFF", "PUBLIC"].map((item) => <option key={item} value={item}>{label(item)}</option>)}</select></label>{audienceOptions.length > 0 && <label>Audience record<select name="audienceReferenceId" defaultValue={editing?.audienceReferenceId || ""} required><option value="">Choose…</option>{audienceOptions.map((item) => <option key={item.id} value={item.id}>{"legalName" in item ? `${item.legalName} · ${item.gradeCode}` : `${item.displayName} · ${item.gradeCode}`}</option>)}</select></label>}<label>Language<select name="languageMode" value={languageMode} onChange={(event) => setLanguageMode(event.target.value)}><option value="BILINGUAL">English + Hindi</option><option value="ENGLISH">English only</option><option value="HINDI">Hindi only</option></select></label><label>Publish at<input name="scheduledFor" type="datetime-local" defaultValue={editing ? localDateTime(new Date(editing.scheduledFor)) : localDateTime()} required /></label><label>Expire at (optional)<input name="expiresAt" type="datetime-local" defaultValue={editing?.expiresAt ? localDateTime(new Date(editing.expiresAt)) : ""} /></label></div>
      {(languageMode === "ENGLISH" || languageMode === "BILINGUAL") && <fieldset><legend>English</legend><label>Title<input name="titleEn" defaultValue={editing?.titleEn || ""} maxLength={180} required /></label><label>Message<textarea name="bodyEn" defaultValue={editing?.bodyEn || ""} maxLength={8000} required /></label></fieldset>}
      {(languageMode === "HINDI" || languageMode === "BILINGUAL") && <fieldset lang="hi"><legend>हिन्दी</legend><label>शीर्षक<input name="titleHi" defaultValue={editing?.titleHi || ""} maxLength={180} required /></label><label>संदेश<textarea name="bodyHi" defaultValue={editing?.bodyHi || ""} maxLength={8000} required /></label></fieldset>}
      <section className="communication-controls"><div><span>Evidence</span><label><input name="requiresAcknowledgement" type="checkbox" defaultChecked={editing?.requiresAcknowledgement} /> Request explicit acknowledgement</label></div><div><span>{studentAudience ? "Student delivery protection" : "External delivery request"}</span>{studentAudience ? <p>Student audiences are delivered only inside verified student accounts. Email, SMS, WhatsApp, and push are intentionally unavailable.</p> : <><label><input name="email" type="checkbox" defaultChecked={editing?.requestedChannels.includes("EMAIL")} /> Email</label><label><input name="sms" type="checkbox" defaultChecked={editing?.requestedChannels.includes("SMS")} /> SMS</label><label><input name="whatsapp" type="checkbox" defaultChecked={editing?.requestedChannels.includes("WHATSAPP")} /> WhatsApp</label><label><input name="push" type="checkbox" defaultChecked={editing?.requestedChannels.includes("PUSH")} /> Push</label></>}</div></section>
      <label className="communication-reason">Draft change reason<input name="reason" defaultValue={editing ? "Updated before authority submission" : "New school communication draft"} maxLength={500} /></label><footer><p>Provider requests remain “awaiting provider” until the school completes registration, consent, credentials, and live verification.</p><button disabled={busy}>{editing ? "Save draft changes" : "Create editable draft"} <ArrowUpRight /></button></footer>
    </form>}

    {tab === "templates" && <div className="communication-template-layout"><section><header><span>Versioned content</span><h2>Authority-owned templates</h2><p>Active revisions are immutable; a new revision supersedes the old one.</p></header>{data.templates.length ? data.templates.map((item) => <article key={item.id}><div><span>{item.code} · revision {item.revisionNumber}</span><h3>{item.name}</h3><p>{label(item.category)} · {label(item.languageMode)} · {item.channels.map(label).join(", ")}</p></div><em data-state={item.status}>{label(item.status)}</em>{item.status === "DRAFT" && data.canConfigure && <button disabled={busy} onClick={() => void mutate(`/api/v1/communications/templates/${item.id}/activate`, { method: "POST", body: JSON.stringify({ schoolId: data.schoolId, expectedVersion: item.rowVersion, reason: "Activated after bilingual content and channel review" }) }, "Template revision activated and frozen.")}>Activate</button>}</article>) : <div className="communication-empty"><MessageIcon /><h3>No templates yet</h3></div>}</section>{data.canConfigure && <form onSubmit={createTemplate}><header><PlusIcon /><div><span>Editable until activation</span><h2>Create a template draft</h2></div></header><label>Supersedes (optional)<select name="supersedesTemplateId"><option value="">New template code</option>{data.templates.filter((item) => item.status === "ACTIVE").map((item) => <option value={item.id} key={item.id}>{item.name} · r{item.revisionNumber}</option>)}</select></label><label>Code<input name="code" placeholder="FEE_REMINDER" pattern="[A-Za-z][A-Za-z0-9_ ]{1,39}" required /></label><label>Name<input name="name" maxLength={140} required /></label><label>Category<select name="category">{["GENERAL", "ACADEMIC", "ATTENDANCE", "FEES", "TRANSPORT", "EVENT", "EMERGENCY", "OTHER"].map((item) => <option key={item}>{item}</option>)}</select></label><label>Language<select name="languageMode" defaultValue="BILINGUAL"><option>BILINGUAL</option><option>ENGLISH</option><option>HINDI</option></select></label><label>English title<input name="titleEn" maxLength={180} /></label><label>English message<textarea name="bodyEn" maxLength={8000} /></label><label lang="hi">हिन्दी शीर्षक<input name="titleHi" maxLength={180} /></label><label lang="hi">हिन्दी संदेश<textarea name="bodyHi" maxLength={8000} /></label><div className="communication-template-checks"><label><input name="requiresAcknowledgement" type="checkbox" /> Acknowledgement</label><label><input name="email" type="checkbox" /> Email</label><label><input name="sms" type="checkbox" /> SMS</label><label><input name="whatsapp" type="checkbox" /> WhatsApp</label><label><input name="push" type="checkbox" /> Push</label></div><label>Setup reason<input name="reason" maxLength={500} /></label><button disabled={busy}>Create template draft</button></form>}</div>}
  </section>;
}
