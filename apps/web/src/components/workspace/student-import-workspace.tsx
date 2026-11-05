"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { ArrowRight, Check, Clock, Shield } from "@/components/icons";
import {
  AcademicIcon,
  PeopleIcon,
  PlusIcon,
  ReportIcon,
} from "@/components/workspace/workspace-icons";

type ImportScope = {
  schoolId: string;
  schoolName: string;
  campusId: string;
  campusCode: string;
  campusName: string;
  academicSessionId: string;
  academicSessionName: string;
  sessionStartsOn: string;
  sessionEndsOn: string;
};

type Batch = {
  id: string;
  schoolId: string;
  schoolName: string;
  campusId: string;
  campusName: string;
  academicSessionId: string;
  academicSessionName: string;
  startsOn: string;
  sourceFileName: string;
  fileSha256: string;
  status: string;
  totalRows: number;
  readyRows: number;
  reviewRows: number;
  invalidRows: number;
  skippedRows: number;
  committedRows: number;
  createdAt: string;
  committedAt: string | null;
  version: number;
};

type LearnerCandidate = {
  learnerId: string;
  learnerReference: string;
  legalName: string;
  birthDate: string;
  admissionNumber: string | null;
  matchReason: string;
};

type GuardianCandidate = {
  guardianId: string;
  displayName: string;
  maskedEmail: string | null;
  maskedPhone: string | null;
  matchReason: string;
};

type ImportRow = {
  id: string;
  sourceRowNumber: number;
  legalName: string | null;
  preferredName: string | null;
  birthDate: string | null;
  genderCode: string | null;
  genderDescription: string | null;
  gradeCode: string | null;
  admissionNumber: string | null;
  guardianName: string | null;
  guardianExternalKey: string | null;
  guardianRelationshipCode: string | null;
  guardianEmail: string | null;
  guardianPhone: string | null;
  primaryContact: boolean;
  pickupAuthorized: boolean;
  status: string;
  validationErrors: string[];
  learnerCandidate: LearnerCandidate | null;
  guardianCandidate: GuardianCandidate | null;
  learnerResolution: string | null;
  guardianResolution: string | null;
  resolutionReason: string | null;
  resolvedGuardianId: string | null;
  createdLearnerId: string | null;
  createdEnrolmentId: string | null;
  version: number;
};

type BatchDetail = { batch: Batch; rows: ImportRow[] };
type ParsedRow = {
  sourceRowNumber: number;
  legalName: string;
  preferredName: string;
  birthDate: string;
  genderCode: string;
  genderDescription: string;
  gradeCode: string;
  admissionNumber: string;
  guardianName: string;
  guardianExternalKey: string;
  guardianRelationshipCode: string;
  guardianEmail: string;
  guardianPhone: string;
  primaryContact: boolean;
  pickupAuthorized: boolean;
};

type ApiProblem = { detail?: string };
type ResolutionDraft = { learnerResolution: string; guardianResolution: string; reason: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const maximumFileBytes = 2 * 1024 * 1024;
const maximumRows = 2000;
const reviewPageSize = 50;
const headers = [
  "legal_name",
  "preferred_name",
  "birth_date",
  "gender_code",
  "gender_description",
  "grade_code",
  "admission_number",
  "guardian_name",
  "guardian_external_key",
  "guardian_relationship_code",
  "guardian_email",
  "guardian_phone",
  "primary_contact",
  "pickup_authorized",
] as const;

const template = `${headers.join(",")}\n"Synthetic Learner","","2015-01-15","NOT_STATED","","VI","","Synthetic Guardian","family-001","PARENT_GUARDIAN","guardian@example.test","","true","true"\n`;

function cookie(name: string) {
  const value = document.cookie.split("; ").find((entry) => entry.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : "";
}

async function problemMessage(response: Response, fallback: string) {
  try {
    const problem = (await response.json()) as ApiProblem;
    return problem.detail || fallback;
  } catch {
    return fallback;
  }
}

function dateLabel(value: string | null) {
  if (!value) return "Not committed";
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(value));
}

function displayCode(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function parseBoolean(value: string, field: string, row: number) {
  const normalized = value.trim().toLowerCase();
  if (["true", "yes", "1", "y"].includes(normalized)) return true;
  if (["false", "no", "0", "n", ""].includes(normalized)) return false;
  throw new Error(`Row ${row}: ${field} must be true or false.`);
}

export function parseCsv(text: string) {
  const records: string[][] = [];
  let record: string[] = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (quoted) {
      if (character === '"' && text[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (character === '"') quoted = false;
      else field += character;
    } else if (character === '"') quoted = true;
    else if (character === ",") {
      record.push(field);
      field = "";
    } else if (character === "\n") {
      record.push(field.replace(/\r$/, ""));
      if (record.some((value) => value.trim())) records.push(record);
      record = [];
      field = "";
    } else field += character;
  }
  if (quoted) throw new Error("The CSV contains an unclosed quoted value.");
  record.push(field.replace(/\r$/, ""));
  if (record.some((value) => value.trim())) records.push(record);
  return records;
}

function toRows(text: string): ParsedRow[] {
  const records = parseCsv(text.replace(/^\uFEFF/, ""));
  if (records.length < 2)
    throw new Error("The CSV must contain a header and at least one student row.");
  const actualHeaders = records[0]!.map((value) => value.trim().toLowerCase());
  const missing = headers.filter((header) => !actualHeaders.includes(header));
  const unexpected = actualHeaders.filter(
    (header) => !headers.includes(header as (typeof headers)[number]),
  );
  const duplicates = actualHeaders.filter(
    (header, index) => actualHeaders.indexOf(header) !== index,
  );
  if (missing.length || unexpected.length || duplicates.length) {
    const problems = [
      missing.length ? `Missing columns: ${missing.join(", ")}.` : "",
      unexpected.length ? `Unexpected columns: ${unexpected.join(", ")}.` : "",
      duplicates.length ? `Duplicate columns: ${[...new Set(duplicates)].join(", ")}.` : "",
    ].filter(Boolean);
    throw new Error(problems.join(" "));
  }
  if (records.length - 1 > maximumRows)
    throw new Error(`Use at most ${maximumRows} student rows per import.`);
  const column = Object.fromEntries(actualHeaders.map((header, index) => [header, index]));
  return records.slice(1).map((values, index) => {
    const sourceRowNumber = index + 2;
    if (values.length > actualHeaders.length)
      throw new Error(`Row ${sourceRowNumber}: too many columns.`);
    const value = (name: (typeof headers)[number]) => {
      const position = column[name];
      return position === undefined ? "" : (values[position] ?? "").trim();
    };
    return {
      sourceRowNumber,
      legalName: value("legal_name"),
      preferredName: value("preferred_name"),
      birthDate: value("birth_date"),
      genderCode: value("gender_code").toUpperCase(),
      genderDescription: value("gender_description"),
      gradeCode: value("grade_code").toUpperCase(),
      admissionNumber: value("admission_number"),
      guardianName: value("guardian_name"),
      guardianExternalKey: value("guardian_external_key"),
      guardianRelationshipCode: value("guardian_relationship_code").toUpperCase(),
      guardianEmail: value("guardian_email").toLowerCase(),
      guardianPhone: value("guardian_phone"),
      primaryContact: parseBoolean(value("primary_contact"), "primary_contact", sourceRowNumber),
      pickupAuthorized: parseBoolean(
        value("pickup_authorized"),
        "pickup_authorized",
        sourceRowNumber,
      ),
    };
  });
}

async function sha256(file: File) {
  const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function StudentImportWorkspace({ initialBatchId }: { initialBatchId: string }) {
  const initialOpenAttempted = useRef(false);
  const [scopes, setScopes] = useState<ImportScope[]>([]);
  const [batches, setBatches] = useState<Batch[]>([]);
  const [selectedScope, setSelectedScope] = useState("");
  const [startsOn, setStartsOn] = useState("");
  const [fileName, setFileName] = useState("");
  const [fileDigest, setFileDigest] = useState("");
  const [parsedRows, setParsedRows] = useState<ParsedRow[]>([]);
  const [detail, setDetail] = useState<BatchDetail | null>(null);
  const [rowPage, setRowPage] = useState(0);
  const [drafts, setDrafts] = useState<Record<string, ResolutionDraft>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  const securedRequest = useCallback(async (path: string, init: RequestInit) => {
    let csrf = cookie("educore_csrf");
    if (!csrf) {
      await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
      csrf = cookie("educore_csrf");
    }
    return fetch(`${apiUrl}${path}`, {
      ...init,
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": csrf,
        ...(init.headers ?? {}),
      },
    });
  }, []);

  const loadFoundation = useCallback(async () => {
    const [optionsResponse, batchesResponse] = await Promise.all([
      fetch(`${apiUrl}/api/v1/student-imports/options`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/student-imports?page=0&size=20`, { credentials: "include" }),
    ]);
    if (!optionsResponse.ok)
      throw new Error(await problemMessage(optionsResponse, "Import options are unavailable."));
    if (!batchesResponse.ok)
      throw new Error(await problemMessage(batchesResponse, "Import history is unavailable."));
    return {
      scopes: ((await optionsResponse.json()) as { scopes: ImportScope[] }).scopes,
      batches: ((await batchesResponse.json()) as { items: Batch[] }).items,
    };
  }, []);

  const openBatch = useCallback(async (batchId: string) => {
    setLoading(true);
    setError("");
    try {
      const response = await fetch(`${apiUrl}/api/v1/student-imports/${batchId}`, {
        credentials: "include",
      });
      if (!response.ok)
        throw new Error(await problemMessage(response, "The import batch could not be opened."));
      const result = (await response.json()) as BatchDetail;
      setDetail(result);
      setRowPage(0);
      setDrafts(
        Object.fromEntries(
          result.rows.map((row) => [
            row.id,
            {
              learnerResolution:
                row.learnerResolution ??
                (row.status === "INVALID" ? "SKIP" : row.learnerCandidate ? "" : "CREATE_NEW"),
              guardianResolution:
                row.guardianResolution ?? (row.guardianCandidate ? "" : "CREATE_NEW"),
              reason: "",
            },
          ]),
        ),
      );
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The import batch could not be opened.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    loadFoundation()
      .then((result) => {
        if (cancelled) return;
        setScopes(result.scopes);
        setBatches(result.batches);
        if (result.scopes.length > 0) {
          const first = result.scopes[0]!;
          setSelectedScope(`${first.campusId}|${first.academicSessionId}`);
          setStartsOn(first.sessionStartsOn);
        }
        setError("");
      })
      .catch((reason: Error) => {
        if (!cancelled) setError(reason.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [loadFoundation]);

  useEffect(() => {
    if (initialBatchId && !initialOpenAttempted.current) {
      initialOpenAttempted.current = true;
      void Promise.resolve().then(() => openBatch(initialBatchId));
    }
  }, [initialBatchId, openBatch]);

  async function chooseFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setError("");
    setMessage("");
    if (file.size > maximumFileBytes) {
      setError("Use a CSV file no larger than 2 MB.");
      return;
    }
    if (!file.name.toLowerCase().endsWith(".csv")) {
      setError("Choose a .csv file created from the provided template.");
      return;
    }
    try {
      const [rows, digest] = await Promise.all([file.text().then(toRows), sha256(file)]);
      setFileName(file.name);
      setFileDigest(digest);
      setParsedRows(rows);
      setMessage(
        `${rows.length} rows parsed locally. Preview will validate them on the server; nothing is live yet.`,
      );
    } catch (reason) {
      setFileName("");
      setFileDigest("");
      setParsedRows([]);
      setError(reason instanceof Error ? reason.message : "The CSV could not be parsed.");
    }
  }

  function downloadTemplate() {
    const url = URL.createObjectURL(new Blob([template], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "rs-academy-student-import-template.csv";
    anchor.click();
    URL.revokeObjectURL(url);
  }

  async function preview(event: FormEvent) {
    event.preventDefault();
    const scope = scopes.find(
      (item) => `${item.campusId}|${item.academicSessionId}` === selectedScope,
    );
    if (!scope || parsedRows.length === 0) return;
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const response = await securedRequest("/api/v1/student-imports", {
        method: "POST",
        body: JSON.stringify({
          campusId: scope.campusId,
          academicSessionId: scope.academicSessionId,
          startsOn,
          sourceFileName: fileName,
          fileSha256: fileDigest,
          rows: parsedRows,
        }),
      });
      if (!response.ok)
        throw new Error(await problemMessage(response, "The import preview could not be created."));
      const result = (await response.json()) as BatchDetail;
      setDetail(result);
      setRowPage(0);
      setBatches((current) => [
        result.batch,
        ...current.filter((batch) => batch.id !== result.batch.id),
      ]);
      setDrafts(
        Object.fromEntries(
          result.rows.map((row) => [
            row.id,
            {
              learnerResolution:
                row.status === "INVALID" ? "SKIP" : row.learnerCandidate ? "" : "CREATE_NEW",
              guardianResolution: row.guardianCandidate ? "" : "CREATE_NEW",
              reason: "",
            },
          ]),
        ),
      );
      setMessage(
        "Preview created. Review every warning before committing any live learner records.",
      );
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "The import preview could not be created.",
      );
    } finally {
      setSaving(false);
    }
  }

  async function resolveRow(row: ImportRow) {
    if (!detail) return;
    const draft = drafts[row.id];
    if (!draft) return;
    setSaving(true);
    setError("");
    try {
      const response = await securedRequest(
        `/api/v1/student-imports/${detail.batch.id}/rows/${row.id}`,
        {
          method: "PATCH",
          body: JSON.stringify({
            learnerResolution: draft.learnerResolution,
            guardianResolution:
              draft.learnerResolution === "SKIP" ? null : draft.guardianResolution,
            expectedVersion: row.version,
            reason: draft.reason,
          }),
        },
      );
      if (!response.ok)
        throw new Error(await problemMessage(response, "The row resolution could not be saved."));
      const result = (await response.json()) as BatchDetail;
      setDetail(result);
      setBatches((current) =>
        current.map((batch) => (batch.id === result.batch.id ? result.batch : batch)),
      );
      setMessage(`Source row ${row.sourceRowNumber} resolution saved.`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The row resolution could not be saved.");
    } finally {
      setSaving(false);
    }
  }

  async function commit() {
    if (!detail) return;
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const response = await securedRequest(`/api/v1/student-imports/${detail.batch.id}/commits`, {
        method: "POST",
        body: JSON.stringify({ expectedVersion: detail.batch.version }),
      });
      if (!response.ok)
        throw new Error(
          await problemMessage(response, "The student import could not be committed."),
        );
      const result = (await response.json()) as BatchDetail;
      setDetail(result);
      setBatches((current) =>
        current.map((batch) => (batch.id === result.batch.id ? result.batch : batch)),
      );
      setMessage(
        `${result.batch.committedRows} learner records were committed with import provenance.`,
      );
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "The student import could not be committed.",
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="import-workspace">
      <header className="import-hero">
        <div>
          <span>Controlled data migration</span>
          <h1>Student import centre</h1>
          <p>
            Parse locally, preview on the server, resolve duplicates explicitly, then commit
            canonical records in one protected transaction.
          </p>
        </div>
        <div className="import-hero__actions">
          <button type="button" onClick={downloadTemplate}>
            <ReportIcon /> Download CSV template
          </button>
          <Link href="/workspace/students">
            Back to student registry <ArrowRight />
          </Link>
        </div>
      </header>

      {error ? (
        <div className="student-alert student-alert--error" role="alert">
          {error}
        </div>
      ) : null}
      {message ? (
        <div className="student-alert student-alert--success" role="status">
          <Check /> {message}
        </div>
      ) : null}

      <div className="import-layout">
        <div className="import-main">
          <section className="import-card import-uploader">
            <header>
              <div>
                <span>Step 1</span>
                <h2>Prepare a safe preview</h2>
              </div>
              <em>
                <Shield /> No direct database write
              </em>
            </header>
            <form onSubmit={preview}>
              <label className="import-field import-field--wide">
                <span>School, campus &amp; session</span>
                <select
                  value={selectedScope}
                  required
                  disabled={saving || scopes.length === 0}
                  onChange={(event) => {
                    setSelectedScope(event.target.value);
                    const scope = scopes.find(
                      (item) => `${item.campusId}|${item.academicSessionId}` === event.target.value,
                    );
                    if (scope) setStartsOn(scope.sessionStartsOn);
                  }}
                >
                  {scopes.length === 0 ? (
                    <option value="">No authorized import scope</option>
                  ) : null}
                  {scopes.map((scope) => (
                    <option
                      key={`${scope.campusId}|${scope.academicSessionId}`}
                      value={`${scope.campusId}|${scope.academicSessionId}`}
                    >
                      {scope.schoolName} · {scope.campusName} · {scope.academicSessionName}
                    </option>
                  ))}
                </select>
              </label>
              <label className="import-field">
                <span>Enrolment starts on</span>
                <input
                  type="date"
                  required
                  value={startsOn}
                  disabled={saving}
                  onChange={(event) => setStartsOn(event.target.value)}
                />
              </label>
              <label className="import-file">
                <input
                  type="file"
                  accept=".csv,text/csv"
                  disabled={saving}
                  onChange={(event) => void chooseFile(event)}
                />
                <span>
                  <PlusIcon />
                  <strong>{fileName || "Choose completed CSV"}</strong>
                  <small>
                    {parsedRows.length
                      ? `${parsedRows.length} parsed rows · digest ${fileDigest.slice(0, 10)}…`
                      : "Maximum 2 MB and 2,000 rows"}
                  </small>
                </span>
              </label>
              <button
                className="button button--dark"
                type="submit"
                disabled={saving || parsedRows.length === 0 || !selectedScope}
              >
                Create protected preview
              </button>
            </form>
            <div className="import-rules">
              <span>
                <Check /> One learner per row
              </span>
              <span>
                <Check /> YYYY-MM-DD dates
              </span>
              <span>
                <Check /> Exact template headers
              </span>
              <span>
                <Check /> Shared guardians need one external key
              </span>
            </div>
          </section>

          {detail ? (
            <section className="import-card import-review">
              <header>
                <div>
                  <span>Step 2</span>
                  <h2>Review {detail.batch.sourceFileName}</h2>
                </div>
                <em
                  className={`import-status import-status--${detail.batch.status.toLowerCase().replaceAll("_", "-")}`}
                >
                  {displayCode(detail.batch.status)}
                </em>
              </header>
              <div className="import-metrics">
                <div>
                  <strong>{detail.batch.readyRows}</strong>
                  <small>Ready</small>
                </div>
                <div>
                  <strong>{detail.batch.reviewRows}</strong>
                  <small>Review</small>
                </div>
                <div>
                  <strong>{detail.batch.invalidRows}</strong>
                  <small>Invalid</small>
                </div>
                <div>
                  <strong>{detail.batch.skippedRows}</strong>
                  <small>Skipped</small>
                </div>
                <div>
                  <strong>{detail.batch.committedRows}</strong>
                  <small>Committed</small>
                </div>
              </div>
              <div className="import-rows">
                {detail.rows
                  .slice(rowPage * reviewPageSize, (rowPage + 1) * reviewPageSize)
                  .map((row) => {
                    const draft = drafts[row.id] ?? {
                      learnerResolution: "",
                      guardianResolution: "",
                      reason: "",
                    };
                    const editable =
                      (["INVALID", "REVIEW", "SKIPPED"].includes(row.status) ||
                        (row.status === "READY" && row.resolutionReason !== null)) &&
                      detail.batch.status !== "COMMITTED";
                    return (
                      <article
                        key={row.id}
                        className={`import-row import-row--${row.status.toLowerCase()}`}
                      >
                        <header>
                          <span>CSV row {row.sourceRowNumber}</span>
                          <em>{displayCode(row.status)}</em>
                        </header>
                        <div className="import-row__identity">
                          <span>
                            <PeopleIcon />
                          </span>
                          <div>
                            <strong>{row.legalName || "Missing learner name"}</strong>
                            <small>
                              {row.birthDate || "Missing birth date"} · Class {row.gradeCode || "—"}{" "}
                              · {row.admissionNumber || "No admission number"}
                            </small>
                          </div>
                        </div>
                        <div className="import-row__guardian">
                          <strong>{row.guardianName || "Missing guardian"}</strong>
                          <small>
                            {row.guardianEmail || row.guardianPhone || "No contact"}
                            {row.guardianExternalKey ? ` · Key ${row.guardianExternalKey}` : ""}
                          </small>
                        </div>
                        {row.validationErrors.length ? (
                          <ul className="import-errors">
                            {row.validationErrors.map((item) => (
                              <li key={item}>{item}</li>
                            ))}
                          </ul>
                        ) : null}
                        {row.learnerCandidate ? (
                          <div className="import-candidate">
                            <span>Possible learner duplicate</span>
                            <strong>
                              {row.learnerCandidate.legalName} ·{" "}
                              {row.learnerCandidate.learnerReference}
                            </strong>
                            <small>
                              Matched by {displayCode(row.learnerCandidate.matchReason)}
                              {row.learnerCandidate.admissionNumber
                                ? ` · ${row.learnerCandidate.admissionNumber}`
                                : ""}
                            </small>
                          </div>
                        ) : null}
                        {row.guardianCandidate ? (
                          <div className="import-candidate">
                            <span>Exact guardian candidate</span>
                            <strong>{row.guardianCandidate.displayName}</strong>
                            <small>
                              {row.guardianCandidate.maskedEmail ||
                                row.guardianCandidate.maskedPhone}{" "}
                              · matched by {displayCode(row.guardianCandidate.matchReason)}
                            </small>
                          </div>
                        ) : null}
                        {editable ? (
                          <div className="import-resolution">
                            <label>
                              <span>Learner decision</span>
                              <select
                                value={draft.learnerResolution}
                                onChange={(event) =>
                                  setDrafts((current) => ({
                                    ...current,
                                    [row.id]: { ...draft, learnerResolution: event.target.value },
                                  }))
                                }
                              >
                                <option value="">Choose</option>
                                {row.validationErrors.length === 0 &&
                                row.learnerCandidate?.matchReason !== "ADMISSION_NUMBER" ? (
                                  <option value="CREATE_NEW">Create separate learner</option>
                                ) : null}
                                <option value="SKIP">Skip this row</option>
                              </select>
                            </label>
                            {draft.learnerResolution !== "SKIP" ? (
                              <label>
                                <span>Guardian decision</span>
                                <select
                                  value={draft.guardianResolution}
                                  onChange={(event) =>
                                    setDrafts((current) => ({
                                      ...current,
                                      [row.id]: {
                                        ...draft,
                                        guardianResolution: event.target.value,
                                      },
                                    }))
                                  }
                                >
                                  <option value="">Choose</option>
                                  <option value="CREATE_NEW">Create guardian</option>
                                  {row.guardianCandidate ? (
                                    <option value="LINK_EXISTING">Link exact candidate</option>
                                  ) : null}
                                </select>
                              </label>
                            ) : null}
                            <label className="import-field--wide">
                              <span>Review reason</span>
                              <input
                                value={draft.reason}
                                maxLength={240}
                                placeholder="State the source checked and why this decision is safe"
                                onChange={(event) =>
                                  setDrafts((current) => ({
                                    ...current,
                                    [row.id]: { ...draft, reason: event.target.value },
                                  }))
                                }
                              />
                            </label>
                            <button
                              type="button"
                              disabled={
                                saving ||
                                !draft.learnerResolution ||
                                (draft.learnerResolution !== "SKIP" && !draft.guardianResolution) ||
                                !draft.reason.trim()
                              }
                              onClick={() => void resolveRow(row)}
                            >
                              Save resolution
                            </button>
                          </div>
                        ) : null}
                        {row.status === "COMMITTED" && row.createdLearnerId ? (
                          <Link
                            className="import-row__result"
                            href={`/workspace/students?student=${row.createdLearnerId}`}
                          >
                            Open committed learner <ArrowRight />
                          </Link>
                        ) : null}
                      </article>
                    );
                  })}
              </div>
              {detail.rows.length > reviewPageSize ? (
                <nav className="import-row-pages" aria-label="Import row pages">
                  <button
                    type="button"
                    disabled={rowPage === 0}
                    onClick={() => setRowPage((current) => Math.max(0, current - 1))}
                  >
                    Previous rows
                  </button>
                  <span>
                    Rows {rowPage * reviewPageSize + 1}–
                    {Math.min((rowPage + 1) * reviewPageSize, detail.rows.length)} of{" "}
                    {detail.rows.length}
                  </span>
                  <button
                    type="button"
                    disabled={(rowPage + 1) * reviewPageSize >= detail.rows.length}
                    onClick={() => setRowPage((current) => current + 1)}
                  >
                    Next rows
                  </button>
                </nav>
              ) : null}
              <footer>
                <div>
                  <Shield />
                  <span>
                    <strong>Final commit is transactional</strong>
                    <small>
                      Any new conflict rolls back the entire batch; no partial learner import
                      remains.
                    </small>
                  </span>
                </div>
                {detail.batch.status === "READY" ? (
                  <button
                    className="button button--dark"
                    type="button"
                    disabled={saving}
                    onClick={() => void commit()}
                  >
                    {detail.batch.readyRows > 0
                      ? `Commit ${detail.batch.readyRows} learners`
                      : "Finalize skipped batch"}
                  </button>
                ) : detail.batch.status === "COMMITTED" ? (
                  <Link href="/workspace/students">
                    View student registry <ArrowRight />
                  </Link>
                ) : (
                  <span>Resolve or skip every flagged row to continue.</span>
                )}
              </footer>
            </section>
          ) : null}
        </div>

        <aside className="import-history">
          <header>
            <span>Recent imports</span>
            <h2>Batch history</h2>
          </header>
          {loading && batches.length === 0 ? (
            <div className="import-history__empty">Loading import history…</div>
          ) : null}
          {!loading && batches.length === 0 ? (
            <div className="import-history__empty">
              <Clock />
              <strong>No import batches yet</strong>
              <p>
                Download the template and create a preview. No real data is needed to inspect the
                workflow.
              </p>
            </div>
          ) : null}
          <div>
            {batches.map((item) => (
              <button
                type="button"
                key={item.id}
                className={detail?.batch.id === item.id ? "is-active" : ""}
                onClick={() => void openBatch(item.id)}
              >
                <span>
                  <strong>{item.sourceFileName}</strong>
                  <small>
                    {item.campusName} · {item.academicSessionName}
                  </small>
                </span>
                <em>{displayCode(item.status)}</em>
                <small>
                  {item.totalRows} rows · {dateLabel(item.createdAt)}
                </small>
              </button>
            ))}
          </div>
          <section>
            <AcademicIcon />
            <div>
              <strong>Migration boundary</strong>
              <p>
                This importer creates identity, one guardian relationship, and a pending-placement
                enrolment. Medical, custody, consent, document, transport, fee, and attendance data
                require their protected workflows.
              </p>
            </div>
          </section>
        </aside>
      </div>
    </section>
  );
}
