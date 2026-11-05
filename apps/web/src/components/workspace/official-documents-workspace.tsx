"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ArrowUpRight, Check } from "@/components/icons";
import { ReportIcon } from "@/components/workspace/workspace-icons";
import { CertificateEvidence, CertificateSourcePicker } from "./certificate-controls";
import {
  issueAttempt,
  type CertificateSource,
  type IssueAttempt,
} from "@/lib/official-certificates";

type Template = {
  id: string;
  code: string;
  revisionNumber: number;
  supersedesTemplateId?: string;
  documentKind: "REPORT_CARD" | "FEE_RECEIPT" | "BONAFIDE_CERTIFICATE";
  displayName: string;
  locale: "en-IN" | "hi-IN";
  title: string;
  subtitle?: string;
  footerText?: string;
  signatoryRole: string;
  accentColour: string;
  approvalRequired: boolean;
  status: "DRAFT" | "PUBLISHED" | "RETIRED";
  creatorName: string;
  publisherName?: string;
  publishedAt?: string;
  rowVersion: number;
};
type Source = {
  id: string;
  sourceType: string;
  documentKind: Template["documentKind"];
  reference: string;
  learnerName: string;
  contextLabel: string;
  sourceStatus: string;
  sourceDate?: string;
};
type Document = {
  id: string;
  templateId: string;
  templateName: string;
  documentKind: Template["documentKind"];
  sourceType: string;
  sourceId: string;
  documentNumber: string;
  approvalRequired: boolean;
  status: "PENDING_APPROVAL" | "ISSUED" | "REJECTED" | "CANCELLED" | "SUPERSEDED";
  requestedBy: string;
  requestedAt: string;
  decidedBy?: string;
  issuedAt?: string;
  cancellationReason?: string;
  pdfSha256?: string;
  rowVersion: number;
};
type Snapshot = {
  schoolId: string;
  schoolName: string;
  canConfigure: boolean;
  canIssue: boolean;
  canApprove: boolean;
  canRevoke: boolean;
  canReadStudents: boolean;
  templates: Template[];
  sources: Source[];
  documents: Document[];
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const label = (value: string) =>
  value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
const dateLabel = (value?: string) =>
  value
    ? new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" }).format(
        new Date(value),
      )
    : "Not recorded";
function cookie(name: string) {
  return document.cookie
    .split("; ")
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}
async function csrfToken() {
  let token = cookie("educore_csrf");
  if (!token) {
    await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
    token = cookie("educore_csrf");
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}
async function problem(response: Response, fallback: string) {
  try {
    const body = (await response.json()) as { detail?: string };
    return body.detail || fallback;
  } catch {
    return fallback;
  }
}

export function OfficialDocumentsWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<"issue" | "templates" | "history">("issue");
  const [editingId, setEditingId] = useState("");
  const [selectedSourceId, setSelectedSourceId] = useState("");
  const [certificateSource, setCertificateSource] = useState<CertificateSource | null>(null);
  const [reviewed, setReviewed] = useState<Record<string, number>>({});
  const attempt = useRef<IssueAttempt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/documents`, {
      credentials: "include",
      cache: "no-store",
    });
    if (!response.ok)
      throw new Error(
        await problem(response, "The official document workspace could not be opened."),
      );
    setData((await response.json()) as Snapshot);
  }, []);
  useEffect(() => {
    Promise.resolve()
      .then(load)
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error
            ? reason.message
            : "The official document workspace could not be opened.",
        ),
      );
  }, [load]);
  const editing = useMemo(
    () => data?.templates.find((item) => item.id === editingId && item.status === "DRAFT") ?? null,
    [data, editingId],
  );
  const currentTemplates = useMemo(
    () => data?.templates.filter((item) => item.status === "PUBLISHED") ?? [],
    [data],
  );
  const selectedSource = useMemo(
    () =>
      certificateSource?.id === selectedSourceId
        ? certificateSource
        : (data?.sources.find((item) => item.id === selectedSourceId) ?? null),
    [data, selectedSourceId, certificateSource],
  );
  const eligibleTemplates = useMemo(
    () =>
      currentTemplates.filter(
        (item) => !selectedSource || item.documentKind === selectedSource.documentKind,
      ),
    [currentTemplates, selectedSource],
  );
  const pending = useMemo(
    () => data?.documents.filter((item) => item.status === "PENDING_APPROVAL") ?? [],
    [data],
  );

  async function mutate(path: string, init: RequestInit, success: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(`${apiUrl}${path}`, {
        ...init,
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-TOKEN": await csrfToken(),
          ...(init.headers || {}),
        },
      });
      if (!response.ok)
        throw new Error(await problem(response, "The document change could not be saved."));
      await load();
      setNotice(success);
      return true;
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "The document change could not be saved.",
      );
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function saveTemplate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data) return;
    const element = event.currentTarget;
    const form = new FormData(element);
    const payload = {
      schoolId: data.schoolId,
      templateCode: form.get("templateCode"),
      revisionNumber: Number(form.get("revisionNumber")),
      supersedesTemplateId: form.get("supersedesTemplateId") || null,
      documentKind: form.get("documentKind"),
      displayName: form.get("displayName"),
      locale: form.get("locale"),
      title: form.get("title"),
      subtitle: form.get("subtitle") || null,
      footerText: form.get("footerText"),
      signatoryRole: form.get("signatoryRole"),
      accentColour: form.get("accentColour"),
      approvalRequired:
        form.get("documentKind") === "BONAFIDE_CERTIFICATE" ||
        form.get("approvalRequired") === "on",
      labelOverrides: {},
      expectedVersion: editing?.rowVersion ?? null,
      reason: form.get("reason"),
    };
    const saved = await mutate(
      editing ? `/api/v1/documents/templates/${editing.id}` : "/api/v1/documents/templates",
      { method: editing ? "PUT" : "POST", body: JSON.stringify(payload) },
      editing ? "Draft template updated." : "Draft template created.",
    );
    if (saved) {
      setEditingId("");
      element.reset();
    }
  }
  async function publishTemplate(event: FormEvent<HTMLFormElement>, template: Template) {
    event.preventDefault();
    if (!data) return;
    const form = new FormData(event.currentTarget);
    await mutate(
      `/api/v1/documents/templates/${template.id}/publish`,
      {
        method: "POST",
        body: JSON.stringify({
          schoolId: data.schoolId,
          expectedVersion: template.rowVersion,
          reason: form.get("reason"),
        }),
      },
      "Template independently approved and published.",
    );
  }
  async function issue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data || !selectedSource) return;
    const element = event.currentTarget;
    const form = new FormData(element);
    const body = JSON.stringify({
      schoolId: data.schoolId,
      templateId: form.get("templateId"),
      sourceType: selectedSource.sourceType,
      sourceId: selectedSource.id,
      supersedesDocumentId: form.get("supersedesDocumentId") || null,
      reason: form.get("reason"),
    });
    // Keep the same attempt after an uncertain response, including a failed refresh.
    // This is memory-only: no learner information is placed in browser storage.
    attempt.current = issueAttempt(attempt.current, body, () => crypto.randomUUID());
    const saved = await mutate(
      "/api/v1/documents/issues",
      {
        method: "POST",
        headers: { "Idempotency-Key": attempt.current.key },
        body,
      },
      "Document request recorded. Its approval state is shown below.",
    );
    if (saved) {
      attempt.current = null;
      element.reset();
      setSelectedSourceId("");
      setCertificateSource(null);
    }
  }
  async function decide(event: FormEvent<HTMLFormElement>, document: Document) {
    event.preventDefault();
    if (!data) return;
    if (
      document.documentKind === "BONAFIDE_CERTIFICATE" &&
      reviewed[document.id] !== document.rowVersion
    ) {
      setError("Review the frozen certificate facts before recording a decision.");
      return;
    }
    const form = new FormData(event.currentTarget);
    await mutate(
      `/api/v1/documents/${document.id}/decision`,
      {
        method: "POST",
        body: JSON.stringify({
          schoolId: data.schoolId,
          decision: form.get("decision"),
          reason: form.get("reason"),
          expectedVersion: document.rowVersion,
        }),
      },
      "Independent document decision recorded.",
    );
  }
  async function cancel(event: FormEvent<HTMLFormElement>, document: Document) {
    event.preventDefault();
    if (!data) return;
    const form = new FormData(event.currentTarget);
    await mutate(
      `/api/v1/documents/${document.id}/cancel`,
      {
        method: "POST",
        body: JSON.stringify({
          schoolId: data.schoolId,
          expectedVersion: document.rowVersion,
          reason: form.get("reason"),
        }),
      },
      "Document cancelled; its evidence and public verification remain intact.",
    );
  }
  async function download(document: Document) {
    if (!data) return;
    setBusy(true);
    setError("");
    try {
      const response = await fetch(
        `${apiUrl}/api/v1/documents/${document.id}/download?schoolId=${encodeURIComponent(data.schoolId)}`,
        { credentials: "include" },
      );
      if (!response.ok)
        throw new Error(await problem(response, "The private PDF could not be downloaded."));
      const url = URL.createObjectURL(await response.blob());
      const anchor = window.document.createElement("a");
      anchor.href = url;
      anchor.download = `${document.documentNumber.replace(/[\\/:*?"<>|]/g, "-")}.pdf`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "The private PDF could not be downloaded.",
      );
    } finally {
      setBusy(false);
    }
  }

  if (!data)
    return (
      <section className="documents-state">
        <ReportIcon />
        <h1>
          {error
            ? "Official documents are unavailable"
            : "Opening the controlled document workspace…"}
        </h1>
        {error && <p>{error}</p>}
      </section>
    );
  return (
    <section className="documents-workspace">
      <header className="documents-hero">
        <div>
          <span>Official document factory</span>
          <h1>
            Issue records people can <em>verify.</em>
          </h1>
          <p>
            Published school evidence becomes a private bilingual PDF with a frozen template, QR
            verification, accountable approval, and history that cannot be silently rewritten.
          </p>
        </div>
        <aside>
          <ReportIcon />
          <strong>{data.documents.filter((item) => item.status === "ISSUED").length}</strong>
          <span>Current issued records</span>
          <small>{pending.length} awaiting independent decision</small>
        </aside>
      </header>
      <div className="documents-trust">
        <span>
          <i /> Private PDF storage
        </span>
        <span>QR reveals no learner identity</span>
        <span>Correction by cancellation or supersession</span>
      </div>
      {(error || notice) && (
        <p className={`documents-banner ${error ? "is-error" : "is-success"}`} role="status">
          {error || notice}
        </p>
      )}
      <nav className="documents-tabs" aria-label="Official document sections">
        <button
          type="button"
          className={tab === "issue" ? "is-active" : ""}
          aria-pressed={tab === "issue"}
          onClick={() => setTab("issue")}
        >
          Issue & approve <b>{pending.length}</b>
        </button>
        <button
          type="button"
          className={tab === "templates" ? "is-active" : ""}
          aria-pressed={tab === "templates"}
          onClick={() => {
            setTab("templates");
            setReviewed({});
          }}
        >
          Templates
        </button>
        <button
          type="button"
          className={tab === "history" ? "is-active" : ""}
          aria-pressed={tab === "history"}
          onClick={() => {
            setTab("history");
            setReviewed({});
          }}
        >
          Document history
        </button>
      </nav>

      {tab === "issue" && (
        <div className="documents-grid">
          <section className="documents-panel">
            <header>
              <span>Authoritative source</span>
              <h2>Prepare a controlled PDF</h2>
              <p>
                Use published report cards, posted fee payments, or current enrolments for bonafide
                certificates.
              </p>
            </header>
            {data.canIssue && data.canReadStudents && (
              <CertificateSourcePicker
                key={data.schoolId}
                schoolId={data.schoolId}
                disabled={busy}
                onSelect={(source) => {
                  setCertificateSource(source);
                  setSelectedSourceId(source.id);
                }}
              />
            )}
            {certificateSource && (
              <p role="status">
                Selected for certificate: {certificateSource.learnerName} ·{" "}
                {certificateSource.reference}
              </p>
            )}
            {data.canIssue ? (
              <form className="documents-form" onSubmit={issue}>
                <label>
                  Source record
                  <select
                    name="sourceId"
                    required
                    value={selectedSourceId}
                    onChange={(event) => {
                      setSelectedSourceId(event.target.value);
                      if (event.target.value !== certificateSource?.id) setCertificateSource(null);
                    }}
                  >
                    <option value="" disabled>
                      Choose a record or search for a student above…
                    </option>
                    {certificateSource && (
                      <option value={certificateSource.id}>
                        {certificateSource.learnerName} · {certificateSource.reference} · Bonafide
                        certificate
                      </option>
                    )}
                    {data.sources.map((source) => (
                      <option value={source.id} key={source.id}>
                        {source.learnerName} · {source.reference} · {label(source.documentKind)}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Published template
                  <select
                    name="templateId"
                    required
                    defaultValue=""
                    key={selectedSource?.documentKind || "none"}
                  >
                    <option value="" disabled>
                      Choose a matching published template…
                    </option>
                    {eligibleTemplates.map((template) => (
                      <option value={template.id} key={template.id}>
                        {template.displayName} · {template.locale} · r{template.revisionNumber}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Supersedes a current document (optional)
                  <select name="supersedesDocumentId">
                    <option value="">No replacement</option>
                    {data.documents
                      .filter(
                        (item) =>
                          item.status === "ISSUED" &&
                          (!selectedSource || item.documentKind === selectedSource.documentKind),
                      )
                      .map((item) => (
                        <option value={item.id} key={item.id}>
                          {item.documentNumber} · {item.templateName}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  Issuance reason
                  <textarea name="reason" minLength={3} maxLength={240} required />
                </label>
                <button disabled={busy || !selectedSource || !eligibleTemplates.length}>
                  Prepare document <ArrowUpRight />
                </button>
              </form>
            ) : (
              <div className="documents-empty">
                <p>Your role can review records but cannot issue documents.</p>
              </div>
            )}
          </section>
          <section className="documents-panel">
            <header>
              <span>Maker-checker queue</span>
              <h2>Independent decisions</h2>
              <p>The person who requested a protected document cannot approve it.</p>
            </header>
            {pending.length ? (
              <div className="documents-queue">
                {pending.map((document) => (
                  <article key={document.id}>
                    <div>
                      <span>{label(document.documentKind)}</span>
                      <h3>{document.documentNumber}</h3>
                      <p>
                        {document.templateName} · requested by {document.requestedBy}
                      </p>
                      <small>{dateLabel(document.requestedAt)}</small>
                    </div>
                    {data.canApprove && document.documentKind === "BONAFIDE_CERTIFICATE" && (
                      <CertificateEvidence
                        key={`${document.id}:${document.rowVersion}`}
                        schoolId={data.schoolId}
                        documentId={document.id}
                        version={document.rowVersion}
                        onReviewed={(verified) =>
                          setReviewed((previous) => ({
                            ...previous,
                            [document.id]: verified ? document.rowVersion : -1,
                          }))
                        }
                      />
                    )}
                    {data.canApprove ? (
                      <form onSubmit={(event) => decide(event, document)}>
                        <select
                          name="decision"
                          aria-label={`Decision for ${document.documentNumber}`}
                        >
                          <option value="APPROVE">Approve and issue</option>
                          <option value="REJECT">Reject</option>
                        </select>
                        <input
                          name="reason"
                          aria-label={`Decision reason for ${document.documentNumber}`}
                          placeholder="Required decision reason"
                          minLength={3}
                          maxLength={240}
                          required
                        />
                        <button
                          disabled={
                            busy ||
                            (document.documentKind === "BONAFIDE_CERTIFICATE" &&
                              reviewed[document.id] !== document.rowVersion)
                          }
                        >
                          Record decision
                        </button>
                      </form>
                    ) : (
                      <small>A separate authorized person must decide.</small>
                    )}
                  </article>
                ))}
              </div>
            ) : (
              <div className="documents-empty">
                <Check />
                <p>No document requests are awaiting a decision.</p>
              </div>
            )}
          </section>
        </div>
      )}

      {tab === "templates" && (
        <div className="documents-grid documents-grid--templates">
          {data.canConfigure && (
            <section className="documents-panel">
              <header>
                <span>Versioned design</span>
                <h2>{editing ? `Edit ${editing.code}` : "Create a template revision"}</h2>
                <p>Content remains editable only while this revision is a draft.</p>
              </header>
              <form
                key={editing?.id || "new"}
                className="documents-form documents-form--two"
                onSubmit={saveTemplate}
              >
                <label>
                  Code
                  <input
                    name="templateCode"
                    defaultValue={editing?.code || ""}
                    pattern="[A-Z][A-Z0-9_]{1,39}"
                    required
                  />
                </label>
                <label>
                  Revision
                  <input
                    name="revisionNumber"
                    type="number"
                    min="1"
                    max="10000"
                    defaultValue={editing?.revisionNumber || 1}
                    required
                  />
                </label>
                <label>
                  Kind
                  <select name="documentKind" defaultValue={editing?.documentKind || "REPORT_CARD"}>
                    <option value="REPORT_CARD">Report card</option>
                    <option value="FEE_RECEIPT">Fee receipt</option>
                    <option value="BONAFIDE_CERTIFICATE">Bonafide certificate</option>
                  </select>
                </label>
                <label>
                  Language
                  <select name="locale" defaultValue={editing?.locale || "en-IN"}>
                    <option value="en-IN">English (India)</option>
                    <option value="hi-IN">Hindi (India)</option>
                  </select>
                </label>
                <label className="is-wide">
                  Supersedes published revision
                  <select
                    name="supersedesTemplateId"
                    defaultValue={editing?.supersedesTemplateId || ""}
                  >
                    <option value="">First revision</option>
                    {data.templates
                      .filter((item) => item.status === "PUBLISHED")
                      .map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.displayName} · r{item.revisionNumber}
                        </option>
                      ))}
                  </select>
                </label>
                <label className="is-wide">
                  Internal name
                  <input
                    name="displayName"
                    maxLength={120}
                    defaultValue={editing?.displayName || ""}
                    required
                  />
                </label>
                <label className="is-wide">
                  Document title
                  <input
                    name="title"
                    maxLength={160}
                    defaultValue={editing?.title || ""}
                    required
                  />
                </label>
                <label className="is-wide">
                  Subtitle
                  <input name="subtitle" maxLength={240} defaultValue={editing?.subtitle || ""} />
                </label>
                <label>
                  Signatory role
                  <input
                    name="signatoryRole"
                    maxLength={120}
                    defaultValue={editing?.signatoryRole || "Principal"}
                    required
                  />
                </label>
                <label>
                  Accent colour
                  <input
                    name="accentColour"
                    type="color"
                    defaultValue={editing?.accentColour || "#23483F"}
                    required
                  />
                </label>
                <label className="is-wide">
                  Footer
                  <textarea
                    name="footerText"
                    maxLength={500}
                    defaultValue={
                      editing?.footerText || "Verify the QR code before relying on this document."
                    }
                    required
                  />
                </label>
                <label className="is-check is-wide">
                  <input
                    name="approvalRequired"
                    type="checkbox"
                    defaultChecked={editing?.approvalRequired ?? true}
                  />{" "}
                  Require independent approval before issuance
                </label>
                <p className="is-wide">
                  Bonafide certificates always require independent approval. Their published
                  template code is the certificate-number prefix.
                </p>
                <label className="is-wide">
                  Configuration reason
                  <input name="reason" minLength={3} maxLength={240} required />
                </label>
                <div className="documents-form__actions is-wide">
                  <button disabled={busy}>{editing ? "Save draft" : "Create draft"}</button>
                  {editing && (
                    <button type="button" className="is-quiet" onClick={() => setEditingId("")}>
                      Cancel editing
                    </button>
                  )}
                </div>
              </form>
            </section>
          )}
          <section className="documents-panel">
            <header>
              <span>Revision register</span>
              <h2>Template authority</h2>
              <p>
                A different authorized person publishes each draft; the previous revision is retired
                automatically.
              </p>
            </header>
            <div className="documents-templates">
              {data.templates.map((template) => (
                <article key={template.id}>
                  <div
                    className="documents-template__accent"
                    style={{ background: template.accentColour }}
                  />
                  <div>
                    <span>
                      {template.code} · r{template.revisionNumber} · {template.locale}
                    </span>
                    <h3>{template.displayName}</h3>
                    <p>
                      {label(template.documentKind)} ·{" "}
                      {template.approvalRequired ? "approval required" : "direct issue allowed"}
                    </p>
                    <small>
                      Created by {template.creatorName}
                      {template.publisherName ? ` · published by ${template.publisherName}` : ""}
                    </small>
                  </div>
                  <b data-status={template.status}>{label(template.status)}</b>
                  {template.status === "DRAFT" && data.canConfigure && (
                    <button
                      type="button"
                      className="is-quiet"
                      onClick={() => setEditingId(template.id)}
                    >
                      Edit
                    </button>
                  )}
                  {template.status === "DRAFT" && data.canApprove && (
                    <form onSubmit={(event) => publishTemplate(event, template)}>
                      <input
                        name="reason"
                        placeholder="Publication reason"
                        minLength={3}
                        maxLength={240}
                        required
                      />
                      <button disabled={busy}>Publish</button>
                    </form>
                  )}
                </article>
              ))}
            </div>
          </section>
        </div>
      )}

      {tab === "history" && (
        <section className="documents-panel documents-history">
          <header>
            <span>Immutable register</span>
            <h2>Issued document history</h2>
            <p>
              Cancelled and superseded records stay visible; they are never deleted to hide a
              correction.
            </p>
          </header>
          {data.documents.length ? (
            <div className="documents-history__list">
              {data.documents.map((document) => (
                <article key={document.id}>
                  <div>
                    <span>{document.documentNumber}</span>
                    <h3>{document.templateName}</h3>
                    <p>
                      {label(document.documentKind)} · requested by {document.requestedBy} ·{" "}
                      {dateLabel(document.requestedAt)}
                    </p>
                    {document.pdfSha256 && <small>PDF SHA-256 · {document.pdfSha256}</small>}
                  </div>
                  <b data-status={document.status}>{label(document.status)}</b>
                  {["ISSUED", "CANCELLED", "SUPERSEDED"].includes(document.status) && (
                    <button type="button" disabled={busy} onClick={() => void download(document)}>
                      Download PDF
                    </button>
                  )}
                  {document.status === "ISSUED" && data.canRevoke && (
                    <form onSubmit={(event) => cancel(event, document)}>
                      <input
                        name="reason"
                        placeholder="Required cancellation reason"
                        minLength={3}
                        maxLength={240}
                        required
                      />
                      <button disabled={busy}>Cancel</button>
                    </form>
                  )}
                </article>
              ))}
            </div>
          ) : (
            <div className="documents-empty">
              <ReportIcon />
              <p>No official document has been requested yet.</p>
            </div>
          )}
        </section>
      )}
    </section>
  );
}
