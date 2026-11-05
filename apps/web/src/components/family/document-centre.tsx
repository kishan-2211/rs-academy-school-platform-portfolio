"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import {
  documentKindLabel,
  documentProblem,
  loadFamilyDocuments,
  loadFamilyPdf,
  type FamilyDocument,
  type FamilyDocumentPage,
} from "@/lib/family-documents";
import styles from "@/components/ptm.module.css";
import documentStyles from "./document-centre.module.css";

import { familyChildren, type FamilyChild as Child } from "@/lib/family-children";
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function FamilyDocumentCentre() {
  const [children, setChildren] = useState<Child[] | null>(null);
  const [learnerId, setLearnerId] = useState("");
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void fetch(`${apiUrl}/api/v1/family/children`, {
      credentials: "include",
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) throw new Error(documentProblem(response.status));
        const linked = familyChildren(await response.json());
        if (!controller.signal.aborted) {
          setChildren(linked);
          setLearnerId(linked[0]?.learnerId ?? "");
        }
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted)
          setError(reason instanceof Error ? reason.message : "Family access is unavailable.");
      });
    return () => controller.abort();
  }, [revision]);
  return (
    <main id="main-content" className={styles.centre}>
      <header className={styles.hero}>
        <span>Private school documents</span>
        <h1>Important records, together.</h1>
        <p>
          Download report cards, fee receipts and bonafide certificates issued by the school. Only
          currently valid documents for your linked child appear here.
        </p>
      </header>
      {error && (
        <div className={styles.error} role="alert">
          {error}
          <div className={styles.actions}>
            <button
              onClick={() => {
                setError("");
                setChildren(null);
                setLearnerId("");
                setRevision((n) => n + 1);
              }}
            >
              Try again
            </button>
            <Link href="/portal">Sign in</Link>
          </div>
        </div>
      )}
      {!children && !error && <p role="status">Checking linked children…</p>}
      {children?.length === 0 && (
        <section className={styles.card}>
          <h2>No linked children</h2>
          <p>Contact the school to confirm your family access.</p>
        </section>
      )}
      {!!children?.length && (
        <label className={styles.field}>
          Child
          <select value={learnerId} onChange={(event) => setLearnerId(event.target.value)}>
            {children.map((child) => (
              <option key={child.learnerId} value={child.learnerId}>
                {child.legalName}
              </option>
            ))}
          </select>
        </label>
      )}
      {learnerId && <ChildDocuments key={learnerId} learnerId={learnerId} />}
      <p>
        Need a certificate or a corrected copy?{" "}
        <Link href="/family/requests">Send a school support request</Link>. Downloaded files stay on
        your device; keep them private on shared phones.
      </p>
    </main>
  );
}

function ChildDocuments({ learnerId }: { learnerId: string }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<FamilyDocumentPage | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const download = useRef<AbortController | null>(null);
  const urls = useRef(new Set<string>());
  useEffect(() => {
    const controller = new AbortController();
    void loadFamilyDocuments(learnerId, page, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted)
          setError(reason instanceof Error ? reason.message : "Documents could not be loaded.");
      });
    return () => controller.abort();
  }, [learnerId, page, revision]);
  useEffect(() => {
    const activeUrls = urls.current;
    return () => {
      download.current?.abort();
      activeUrls.forEach((url) => URL.revokeObjectURL(url));
      activeUrls.clear();
    };
  }, []);
  function refresh(nextPage = page) {
    setData(null);
    setError("");
    setMessage("");
    setPage(nextPage);
    setRevision((n) => n + 1);
  }
  async function save(item: FamilyDocument) {
    if (download.current) return;
    const controller = new AbortController();
    download.current = controller;
    setBusy(item.id);
    setError("");
    setMessage("");
    try {
      const blob = await loadFamilyPdf(learnerId, item.id, controller.signal);
      if (controller.signal.aborted) return;
      const url = URL.createObjectURL(blob);
      urls.current.add(url);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `school-document-${item.id}.pdf`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      // Allow the browser to consume the URL; also clear it immediately on child change/unmount.
      window.setTimeout(() => {
        URL.revokeObjectURL(url);
        urls.current.delete(url);
      }, 30000);
      setMessage("Download requested. Check your browser’s downloads for the PDF.");
    } catch (reason: unknown) {
      if (!controller.signal.aborted) {
        setData(null);
        setError(reason instanceof Error ? reason.message : "The PDF could not be downloaded.");
      }
    } finally {
      download.current = null;
      if (!controller.signal.aborted) setBusy(null);
    }
  }
  return (
    <section
      className={documentStyles.documents}
      aria-label="Child documents"
      aria-busy={!data && !error}
    >
      {error && (
        <div className={styles.error} role="alert">
          {error}
          <div className={styles.actions}>
            <button disabled={!!busy} onClick={() => refresh()}>
              Refresh documents
            </button>
            <Link href="/portal">Sign in</Link>
          </div>
        </div>
      )}
      {message && (
        <p role="status" className={styles.confirmation}>
          {message}
        </p>
      )}
      {!data && !error && <p role="status">Loading documents…</p>}
      {data?.documents.length === 0 && (
        <div className={styles.card}>
          <h2>{page ? "No more documents" : "No issued documents yet"}</h2>
          <p>
            School-issued PDFs will appear here after approval. You can still view available results
            and fee information in your family overview.
          </p>
          <Link href="/family">Family overview</Link>
        </div>
      )}
      {data?.documents.map((item) => (
        <article key={item.id} className={styles.card}>
          <span className={styles.badge}>
            {item.locale === "hi-IN" ? "हिन्दी" : "English"} · PDF
          </span>
          <h2>{documentKindLabel(item.documentKind)}</h2>
          <p>
            {item.documentNumber}
            <br />
            Issued{" "}
            {new Intl.DateTimeFormat("en-IN", {
              dateStyle: "medium",
              timeZone: "Asia/Kolkata",
            }).format(new Date(item.issuedAt))}{" "}
            · {Math.max(1, Math.ceil(item.sizeBytes / 1024))} KB
          </p>
          <button
            disabled={!!busy}
            onClick={() => void save(item)}
            aria-label={`Download ${documentKindLabel(item.documentKind)} ${item.documentNumber} as PDF`}
          >
            {busy === item.id ? "Preparing download…" : "Download PDF"}
          </button>
        </article>
      ))}
      {data && (
        <nav className={styles.actions} aria-label="Document pages">
          <button disabled={page === 0 || !!busy} onClick={() => refresh(page - 1)}>
            Previous
          </button>
          <span aria-live="polite">Page {page + 1}</span>
          <button
            disabled={!data.hasMore || page >= 10000 || !!busy}
            onClick={() => refresh(page + 1)}
          >
            Next
          </button>
        </nav>
      )}
    </section>
  );
}
