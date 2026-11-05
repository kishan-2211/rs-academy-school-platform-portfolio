"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { schoolToday } from "../../lib/payroll-accounting";
import {
  defaultReportFilters,
  readReport,
  reportQuery,
  reportResponse,
  reportTitle,
  type ReportFilters,
  type ReportResult,
} from "../../lib/payroll-reports";
import { PayrollReportFilters, PayrollReportTable } from "./payroll-report-views";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function PayrollReportWorkspace({ schoolId }: { schoolId: string }) {
  const [filters, setFilters] = useState(() => defaultReportFilters(schoolToday()));
  const [applied, setApplied] = useState<ReportFilters | null>(null);
  const [report, setReport] = useState<ReportResult | null>(null);
  const [busy, setBusy] = useState<"load" | "download" | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const request = useRef<AbortController | null>(null);
  const downloads = useRef(new Set<string>());
  const errorBox = useRef<HTMLDivElement | null>(null);
  const resultHeading = useRef<HTMLHeadingElement | null>(null);
  useEffect(() => {
    const urls = downloads.current;
    return () => {
      request.current?.abort();
      for (const url of urls) URL.revokeObjectURL(url);
      urls.clear();
    };
  }, []);
  useEffect(() => {
    if (error) errorBox.current?.focus();
  }, [error]);
  useEffect(() => {
    if (report) resultHeading.current?.focus();
  }, [report]);

  async function load(next: ReportFilters, page: number) {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy("load");
    setError("");
    setNotice("");
    setReport(null);
    try {
      const response = await reportResponse(
        fetch,
        apiUrl,
        schoolId,
        next,
        page,
        false,
        controller.signal,
      );
      const result = await readReport(response, next.kind, schoolId, page, next.dueThrough);
      if (controller.signal.aborted) return;
      setReport(result);
      setApplied({ ...next });
      setNotice(result.data.total + " matching rows. Page " + (page + 1) + " loaded.");
    } catch (failure) {
      if (!controller.signal.aborted)
        setError(failure instanceof Error ? failure.message : "The report could not be loaded.");
    } finally {
      if (!controller.signal.aborted) {
        setBusy(null);
        request.current = null;
      }
    }
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Validate before removing a previously loaded report.
    try {
      reportQuery(schoolId, filters);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Check the report filters.");
      return;
    }
    void load(filters, 0);
  }
  async function download() {
    if (!report || !applied || request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy("download");
    setError("");
    setNotice("");
    try {
      const response = await reportResponse(
        fetch,
        apiUrl,
        schoolId,
        applied,
        0,
        true,
        controller.signal,
      );
      const blob = await response.blob();
      if (controller.signal.aborted) return;
      const url = URL.createObjectURL(blob);
      downloads.current.add(url);
      const link = document.createElement("a");
      link.href = url;
      link.download = "payroll-" +
        (applied.kind === "advanceReconciliation"
          ? "advance-reconciliation"
          : applied.kind.replace("Component", "-component")) + ".csv";
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => {
        URL.revokeObjectURL(url);
        downloads.current.delete(url);
      }, 1000);
      setNotice(
        "CSV download requested for all matching records, not just this page. Keep this private file secure.",
      );
    } catch (failure) {
      if (!controller.signal.aborted) {
        setReport(null);
        setError(
          failure instanceof Error ? failure.message : "The report could not be downloaded.",
        );
      }
    } finally {
      if (!controller.signal.aborted) {
        setBusy(null);
        request.current = null;
      }
    }
  }
  const changed = applied && JSON.stringify(applied) !== JSON.stringify(filters);
  const page = report?.data.page ?? 0;
  const pages = report ? Math.max(1, Math.ceil(report.data.total / report.data.size)) : 1;
  return (
    <section className="pa-workspace pr-workspace" aria-labelledby="payroll-report-title">
      <header className="payroll-panel pa-introduction">
        <h2 id="payroll-report-title">Payroll reports</h2>
        <p>
          Review historical salaries, frozen workforce/deduction groupings, and ledger evidence.
          Payroll and accounting permissions are both required.
        </p>
      </header>
      <PayrollReportFilters
        filters={filters}
        busy={busy !== null}
        onChange={setFilters}
        onSubmit={submit}
      />
      {error && (
        <div ref={errorBox} tabIndex={-1} role="alert" className="pr-error">
          {error}
        </div>
      )}
      <p role="status" aria-live="polite" aria-atomic="true">
        {busy === "load"
          ? "Loading report…"
          : busy === "download"
            ? "Preparing private CSV…"
            : notice}
      </p>
      {!report && !busy && !error && (
        <p>Choose the report and its date filters, then load the records.</p>
      )}
      {report && applied && (
        <section className="payroll-panel" aria-labelledby="payroll-report-results">
          <div className="pa-introduction">
            <h3 ref={resultHeading} id="payroll-report-results" tabIndex={-1}>
              {reportTitle(report.kind)}
            </h3>
            <p>
              {report.data.schoolName} · {report.kind === "advanceReconciliation"
                ? "Installments due through " + report.data.dueThrough
                : report.data.from + " through " + report.data.to}
            </p>
            <small>
              Snapshot: {report.data.generatedAt}.{" "}
              {applied.search ? "Employee search: " + applied.search : "All employees."}
            </small>
            {changed && (
              <p className="pr-filter-note">
                Filter changes are not applied yet. The table and download still use the range and
                search shown above.
              </p>
            )}
            <div className="pa-toolbar">
              <p>
                {report.data.total} matching rows · Page {page + 1} of {pages}
              </p>
              <button
                className="is-secondary"
                type="button"
                disabled={busy !== null}
                onClick={() => void load(applied, page)}
              >
                Refresh shown report
              </button>
              <button
                type="button"
                disabled={busy !== null || report.data.total === 0 || report.data.total > 5000}
                aria-describedby="payroll-report-download-note"
                onClick={() => void download()}
              >
                Download CSV
              </button>
            </div>
            <small id="payroll-report-download-note">
              Exports include all matching rows, up to 5,000. Narrow the filters for larger
              reports. Download only on a trusted device.
            </small>
          </div>
          <PayrollReportTable report={report} />
          <nav className="pa-toolbar pa-introduction" aria-label="Payroll report pages">
            <button
              className="is-secondary"
              type="button"
              disabled={busy !== null || page === 0}
              onClick={() => void load(applied, page - 1)}
            >
              Previous page
            </button>
            <span>
              Page {page + 1} of {pages}
            </span>
            <button
              className="is-secondary"
              type="button"
              disabled={busy !== null || page + 1 >= pages || page >= 10000}
              onClick={() => void load(applied, page + 1)}
            >
              Next page
            </button>
          </nav>
        </section>
      )}
    </section>
  );
}
