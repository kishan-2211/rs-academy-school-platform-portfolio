"use client";

import Link from "next/link";
import type { Route } from "next";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ArrowUpRight, Check } from "@/components/icons";
import {
  AcademicIcon,
  AttendanceIcon,
  MessageIcon,
  MoneyIcon,
  PeopleIcon,
  ReportIcon,
} from "@/components/workspace/workspace-icons";

type Metric = {
  code: string;
  label: string;
  value: number;
  unit: "COUNT" | "CURRENCY";
  definition: string;
};

type ReportSection = {
  code: string;
  title: string;
  description: string;
  metrics: Metric[];
};

type DataQualityIssue = {
  code: string;
  severity: "WARNING" | "CRITICAL";
  count: number;
  title: string;
  description: string;
  destination: string;
};

type Snapshot = {
  schoolId: string;
  schoolName: string;
  currencyCode: string;
  generatedAt: string;
  sections: ReportSection[];
  dataQualityIssues: DataQualityIssue[];
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const sectionIcons = {
  admissions: PeopleIcon,
  students: PeopleIcon,
  academics: AcademicIcon,
  attendance: AttendanceIcon,
  people: PeopleIcon,
  finance: MoneyIcon,
  communication: MessageIcon,
  transport: AttendanceIcon,
} as const;

async function problem(response: Response, fallback: string) {
  try {
    const body = (await response.json()) as { detail?: string };
    return body.detail || fallback;
  } catch {
    return fallback;
  }
}

function generatedLabel(value: string) {
  return new Intl.DateTimeFormat("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

export function ReportsWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setBusy(true);
    setError("");
    try {
      const response = await fetch(`${apiUrl}/api/v1/reports/overview`, {
        credentials: "include",
        cache: "no-store",
      });
      if (!response.ok) {
        throw new Error(await problem(response, "The operational report could not be generated."));
      }
      setData((await response.json()) as Snapshot);
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "The operational report could not be generated.",
      );
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const number = useMemo(() => new Intl.NumberFormat("en-IN"), []);
  const currency = useMemo(
    () =>
      new Intl.NumberFormat("en-IN", {
        style: "currency",
        currency: data?.currencyCode || "INR",
        maximumFractionDigits: 2,
      }),
    [data?.currencyCode],
  );

  function format(metric: Metric) {
    return metric.unit === "CURRENCY" ? currency.format(metric.value) : number.format(metric.value);
  }

  if (!data && busy) {
    return (
      <section className="reports-loading" aria-live="polite">
        <i />
        <p>Generating a school-scoped operational report…</p>
      </section>
    );
  }

  if (!data) {
    return (
      <section className="reports-error" role="alert">
        <ReportIcon />
        <h1>Reports are unavailable</h1>
        <p>{error}</p>
        <button type="button" onClick={() => void load()}>
          Try again
        </button>
      </section>
    );
  }

  return (
    <section className="reports-workspace">
      <header className="reports-hero">
        <div>
          <span>Permission-aware reporting</span>
          <h1>
            Real operating signals, <em>with their definitions.</em>
          </h1>
          <p>
            This overview reads aggregate facts from the current school only. It exposes no learner,
            guardian, staff, payment, or incident details, and every generation is written to the
            immutable audit stream.
          </p>
        </div>
        <aside>
          <ReportIcon />
          <span>Generated for</span>
          <strong>{data.schoolName}</strong>
          <small>{generatedLabel(data.generatedAt)}</small>
          <button type="button" disabled={busy} onClick={() => void load()}>
            {busy ? "Refreshing…" : "Refresh report"}
          </button>
        </aside>
      </header>

      {error && (
        <p className="reports-notice" role="alert">
          {error}
        </p>
      )}

      <section className="reports-quality">
        <header>
          <div>
            <span>Data quality queue</span>
            <h2>
              {data.dataQualityIssues.length
                ? "Records that need attention"
                : "No configured quality exceptions"}
            </h2>
            <p>
              Counts are generated live. Opening an item moves to the authorized source workflow.
            </p>
          </div>
          <strong>{data.dataQualityIssues.length}</strong>
        </header>
        {data.dataQualityIssues.length ? (
          <div>
            {data.dataQualityIssues.map((issue) => (
              <Link
                href={issue.destination as Route}
                key={issue.code}
                data-severity={issue.severity}
              >
                <span>{number.format(issue.count)}</span>
                <span>
                  <strong>{issue.title}</strong>
                  <small>{issue.description}</small>
                </span>
                <ArrowUpRight />
              </Link>
            ))}
          </div>
        ) : (
          <div className="reports-quality__empty">
            <Check />
            <p>
              None of the implemented quality checks returned an exception. This does not replace
              school review.
            </p>
          </div>
        )}
      </section>

      <div className="reports-grid">
        {data.sections.map((section) => {
          const Icon = sectionIcons[section.code as keyof typeof sectionIcons] || ReportIcon;
          return (
            <article className="reports-section" key={section.code}>
              <header>
                <span>
                  <Icon />
                </span>
                <div>
                  <small>Live aggregate</small>
                  <h2>{section.title}</h2>
                </div>
              </header>
              <p>{section.description}</p>
              <div className="reports-metrics">
                {section.metrics.map((metric) => (
                  <div key={metric.code}>
                    <span>{metric.label}</span>
                    <strong>{format(metric)}</strong>
                    <details>
                      <summary>Definition</summary>
                      <p>{metric.definition}</p>
                    </details>
                  </div>
                ))}
              </div>
            </article>
          );
        })}
      </div>

      <footer className="reports-boundary">
        <ReportIcon />
        <div>
          <strong>Reporting boundary</strong>
          <p>
            These are point-in-time operating aggregates. Controlled exports, scheduling,
            comparisons, and drill-down datasets remain separate milestones and are not simulated
            here.
          </p>
        </div>
      </footer>
    </section>
  );
}
