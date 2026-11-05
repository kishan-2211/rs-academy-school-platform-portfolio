"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Brand } from "@/components/brand";
import { Check } from "@/components/icons";
import { validDocumentCopy } from "@/lib/official-certificates";

type Verification = {
  verificationStatus: "VALID" | "CANCELLED" | "SUPERSEDED" | "SOURCE_NO_LONGER_CURRENT";
  schoolName: string;
  documentKind: "REPORT_CARD" | "FEE_RECEIPT" | "BONAFIDE_CERTIFICATE";
  documentNumber: string;
  lifecycleStatus: string;
  sourceStatus: string;
  issuedAt: string;
  pdfSha256: string;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const label = (value: string) =>
  value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());

export function DocumentVerification({ token }: { token: string }) {
  const tokenIsValid = /^[A-Za-z0-9_-]{43}$/.test(token);
  const [lookup, setLookup] = useState<{ token: string; record: Verification | null } | null>(null);

  useEffect(() => {
    if (!tokenIsValid) return;
    const controller = new AbortController();
    fetch(`${apiUrl}/api/v1/public/documents/verify/${encodeURIComponent(token)}`, {
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) throw new Error("not found");
        setLookup({ token, record: (await response.json()) as Verification });
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          setLookup({ token, record: null });
        }
      });
    return () => controller.abort();
  }, [token, tokenIsValid]);

  const record = lookup?.token === token ? lookup.record : null;
  const state = !tokenIsValid
    ? "missing"
    : lookup?.token !== token
      ? "loading"
      : record
        ? "found"
        : "missing";
  const valid = record?.verificationStatus === "VALID";
  return (
    <main className="verification-page">
      <header>
        <Brand />
        <Link href="/contact">Contact school</Link>
      </header>
      <section className="verification-card" aria-live="polite">
        {state === "loading" && (
          <>
            <div className="verification-mark is-loading" />
            <span>Checking controlled evidence</span>
            <h1>Verifying this document…</h1>
            <p>The verification code is checked without exposing learner or payment details.</p>
          </>
        )}
        {state === "missing" && (
          <>
            <div className="verification-mark is-invalid">!</div>
            <span>Verification unavailable</span>
            <h1>This code could not be verified.</h1>
            <p>
              Check that the complete QR address was opened. Do not rely on the document until the
              school confirms it through an official contact.
            </p>
            <Link className="verification-action" href="/contact">
              Contact R.S. Academy
            </Link>
          </>
        )}
        {state === "found" && record && (
          <>
            <div className={`verification-mark ${valid ? "is-valid" : "is-warning"}`}>
              {valid ? <Check /> : "!"}
            </div>
            <span>Controlled school record</span>
            <h1>
              {valid
                ? validDocumentCopy(record.documentKind).title
                : `This document is ${label(record.verificationStatus)}.`}
            </h1>
            <p>
              {valid
                ? validDocumentCopy(record.documentKind).description
                : "The record remains visible for accountability, but it should not be treated as the current document."}
            </p>
            <dl>
              <div>
                <dt>School</dt>
                <dd>{record.schoolName}</dd>
              </div>
              <div>
                <dt>Document</dt>
                <dd>{label(record.documentKind)}</dd>
              </div>
              <div>
                <dt>Number</dt>
                <dd>{record.documentNumber}</dd>
              </div>
              <div>
                <dt>Lifecycle</dt>
                <dd>{label(record.lifecycleStatus)}</dd>
              </div>
              <div>
                <dt>Source status</dt>
                <dd>{label(record.sourceStatus)}</dd>
              </div>
              <div>
                <dt>Issued</dt>
                <dd>
                  {new Intl.DateTimeFormat("en-IN", {
                    dateStyle: "medium",
                    timeStyle: "short",
                  }).format(new Date(record.issuedAt))}
                </dd>
              </div>
            </dl>
            <details>
              <summary>PDF fingerprint</summary>
              <code>{record.pdfSha256}</code>
              <p>Compare this SHA-256 value with the fingerprint recorded for the PDF.</p>
            </details>
            <small>
              No learner name, marks, class, payment amount, admission number, or contact details
              are disclosed here.
            </small>
          </>
        )}
      </section>
      <footer>
        <p>
          Verification confirms platform evidence and lifecycle. It is not a certificate-backed
          digital signature.
        </p>
        <Link href="/privacy">Privacy notice</Link>
      </footer>
    </main>
  );
}
