"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  certificateSourcePath,
  readCertificateReview,
  readCertificateSources,
  type CertificateSource,
  type CertificateSourcePage,
} from "@/lib/official-certificates";
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function CertificateSourcePicker({
  schoolId,
  disabled,
  onSelect,
}: {
  schoolId: string;
  disabled: boolean;
  onSelect: (source: CertificateSource) => void;
}) {
  const [search, setSearch] = useState({ query: "", page: 0, revision: 0 });
  const [result, setResult] = useState<{
    key: string;
    data?: CertificateSourcePage;
    error?: string;
  } | null>(null);
  const key = `${schoolId}:${search.query}:${search.page}:${search.revision}`;
  const current = result?.key === key ? result : null;
  useEffect(() => {
    const controller = new AbortController();
    void fetch(`${apiUrl}${certificateSourcePath(schoolId, search.query, search.page)}`, {
      credentials: "include",
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok)
          throw new Error(
            response.status === 401
              ? "Sign in again to search enrolments."
              : "Enrolments are unavailable for your current school access.",
          );
        const data = readCertificateSources(await response.json(), search.page);
        if (!controller.signal.aborted) setResult({ key, data });
      })
      .catch((reason) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error: reason instanceof Error ? reason.message : "Enrolments could not be loaded.",
          });
      });
    return () => controller.abort();
  }, [schoolId, search, key]);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const query = String(new FormData(event.currentTarget).get("query") || "").trim();
    setSearch((previous) => ({ query, page: 0, revision: previous.revision + 1 }));
  }
  return (
    <details className="documents-source-search">
      <summary>Find a student for a bonafide certificate</summary>
      <p>
        Search current enrolments by student name or admission number. No details need to be
        retyped.
      </p>
      <form className="documents-form" onSubmit={submit}>
        <label>
          Student name or admission number
          <input name="query" maxLength={100} type="search" />
        </label>
        <button disabled={disabled}>Search enrolments</button>
      </form>
      {!current && <p role="status">Loading enrolments…</p>}
      {current?.error && <p role="alert">{current.error}</p>}
      {current?.data && (
        <>
          <p role="status">
            {current.data.sources.length
              ? `Page ${search.page + 1} · ${current.data.sources.length} enrolments`
              : "No eligible enrolments match this search."}
          </p>
          <ul>
            {current.data.sources.map((source) => (
              <li key={source.id}>
                <button type="button" disabled={disabled} onClick={() => onSelect(source)}>
                  {source.learnerName} · {source.reference}
                  <small>{source.contextLabel}</small>
                </button>
              </li>
            ))}
          </ul>
          <div className="documents-form__actions">
            <button
              type="button"
              disabled={disabled || search.page === 0}
              onClick={() => setSearch((s) => ({ ...s, page: s.page - 1 }))}
            >
              Previous enrolments
            </button>
            <button
              type="button"
              disabled={disabled || !current.data.hasMore || search.page >= 10000}
              onClick={() => setSearch((s) => ({ ...s, page: s.page + 1 }))}
            >
              Next enrolments
            </button>
          </div>
        </>
      )}
    </details>
  );
}

export function CertificateEvidence({
  schoolId,
  documentId,
  version,
  onReviewed,
}: {
  schoolId: string;
  documentId: string;
  version: number;
  onReviewed: (verified: boolean) => void;
}) {
  const [fields, setFields] = useState<[string, string][] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const pendingRequest = useRef<AbortController | null>(null);
  useEffect(() => () => pendingRequest.current?.abort(), []);
  async function review() {
    pendingRequest.current?.abort();
    const controller = new AbortController();
    pendingRequest.current = controller;
    onReviewed(false);
    setLoading(true);
    setError("");
    setFields(null);
    try {
      const response = await fetch(
        `${apiUrl}/api/v1/documents/${encodeURIComponent(documentId)}/review?schoolId=${encodeURIComponent(schoolId)}`,
        { credentials: "include", cache: "no-store", signal: controller.signal },
      );
      if (!response.ok)
        throw new Error("The frozen evidence is unavailable. Refresh or check your school access.");
      const result = readCertificateReview(await response.json(), documentId, version);
      if (!controller.signal.aborted) {
        setFields(result);
        onReviewed(true);
      }
    } catch (reason) {
      if (!controller.signal.aborted)
        setError(reason instanceof Error ? reason.message : "Evidence could not be loaded.");
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }
  return (
    <div className="documents-certificate-evidence">
      <button type="button" className="is-quiet" disabled={loading} onClick={() => void review()}>
        Review frozen certificate facts
      </button>
      {loading && <p role="status">Loading frozen evidence…</p>}
      {error && <p role="alert">{error}</p>}
      {fields && (
        <>
          <dl>
            {fields.map(([name, value]) => (
              <div key={name}>
                <dt>{name}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          <p>
            Enrolment confirmation at the record date only. Not transfer, character or fee-clearance
            evidence.
          </p>
        </>
      )}
    </div>
  );
}
