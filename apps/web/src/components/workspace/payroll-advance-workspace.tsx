"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  advanceDraft,
  advancePayload,
  advanceTransition,
  type Advance,
  type AdvanceAction,
  type AdvanceData,
  type AdvanceDraft,
} from "../../lib/payroll-advances";
import { createPostingAttempts, schoolToday } from "../../lib/payroll-accounting";
import {
  isScopedAdvanceStatement,
  type AdvanceStatement,
} from "../../lib/payroll-advance-statements";
import { PayrollAdvanceStatementEvidence } from "./payroll-advance-statement";
import {
  advanceActionLabel,
  PayrollAdvanceForm,
  PayrollAdvanceRecords,
} from "./payroll-advance-views";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const root = "/api/v1/payroll/advances";
type Filters = { search: string; status: string; staffProfileId: string; page: number };
const emptyFilters: Filters = { search: "", status: "", staffProfileId: "", page: 0 };
async function problem(response: Response, fallback: string) {
  try {
    return ((await response.json()) as { detail?: string }).detail || fallback;
  } catch {
    return fallback;
  }
}
async function csrfToken(signal: AbortSignal) {
  const read = () =>
    document.cookie
      .split("; ")
      .find((p) => p.startsWith("educore_csrf="))
      ?.slice("educore_csrf=".length);
  let token = read();
  if (!token) {
    await fetch(apiUrl + "/api/v1/auth/sessions/current", {
      credentials: "include",
      cache: "no-store",
      signal,
    });
    token = read();
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}

export function PayrollAdvanceWorkspace({ schoolId }: { schoolId: string }) {
  const [data, setData] = useState<AdvanceData | null>(null);
  const [filters, setFilters] = useState<Filters>(emptyFilters);
  const [filterDraft, setFilterDraft] = useState<Filters>(emptyFilters);
  const [draft, setDraft] = useState<AdvanceDraft | null>(null);
  const [decision, setDecision] = useState<{
    id: string;
    number: string;
    action: AdvanceAction;
  } | null>(null);
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [statement, setStatement] = useState<AdvanceStatement | null>(null);
  const [statementBusy, setStatementBusy] = useState(false);
  const lock = useRef(false);
  const lifetime = useRef<AbortController | null>(null);
  const attempts = useRef(createPostingAttempts(() => crypto.randomUUID()));
  const origin = useRef<HTMLButtonElement | null>(null);
  const heading = useRef<HTMLHeadingElement | null>(null);
  const errorBox = useRef<HTMLDivElement | null>(null);
  const reasonInput = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    return () => controller.abort();
  }, []);
  const fetchData = useCallback(
    async (signal: AbortSignal) => {
      const params = new URLSearchParams({ schoolId, page: String(filters.page), size: "25" });
      if (filters.search) params.set("search", filters.search);
      if (filters.status) params.set("status", filters.status);
      if (filters.staffProfileId) params.set("staffProfileId", filters.staffProfileId);
      const response = await fetch(apiUrl + root + "?" + params, {
        credentials: "include",
        cache: "no-store",
        signal,
      });
      if (!response.ok) {
        if (!signal.aborted) {
          setData(null);
          setDraft(null);
          setDecision(null);
          setReason("");
        }
        throw new Error(await problem(response, "Employee advances are unavailable."));
      }
      const result = (await response.json()) as AdvanceData;
      if (result.schoolId !== schoolId)
        throw new Error("The advance response did not match the selected school.");
      if (!signal.aborted) {
        setData(result);
        setStatement(null);
        setNeedsRefresh(false);
      }
    },
    [schoolId, filters],
  );
  useEffect(() => {
    const controller = new AbortController();
    void Promise.resolve()
      .then(() => {
        if (!controller.signal.aborted) {
          setLoading(true);
          setError("");
        }
        return fetchData(controller.signal);
      })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) {
          setData(null);
          setError(
            failure instanceof Error ? failure.message : "Employee advances are unavailable.",
          );
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [fetchData]);
  useEffect(() => {
    if (decision) reasonInput.current?.focus();
  }, [decision]);
  useEffect(() => {
    if (error) errorBox.current?.focus();
  }, [error]);

  function closeEditor() {
    setDraft(null);
    setDecision(null);
    setReason("");
    requestAnimationFrame(() => {
      (origin.current?.isConnected && !origin.current.disabled
        ? origin.current
        : heading.current
      )?.focus();
    });
  }
  function begin(button: HTMLButtonElement) {
    origin.current = button;
    setDraft(null);
    setDecision(null);
    setReason("");
    setError("");
    setNotice("");
  }
  async function refresh() {
    const signal = lifetime.current?.signal;
    if (lock.current || !signal || signal.aborted) return;
    lock.current = true;
    setBusy(true);
    setError("");
    try {
      await fetchData(signal);
      if (!signal.aborted) setNotice("Advance and journal status refreshed.");
    } catch (failure) {
      if (!signal.aborted) setError(failure instanceof Error ? failure.message : "Refresh failed.");
    } finally {
      lock.current = false;
      if (!signal.aborted) setBusy(false);
    }
  }
  async function mutate(
    path: string,
    method: "POST" | "PUT",
    body: object,
    success: string,
    retrySafe: boolean,
  ) {
    const signal = lifetime.current?.signal;
    if (lock.current || needsRefresh || !signal || signal.aborted) return;
    lock.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    let acknowledged = false;
    try {
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": await csrfToken(signal),
      };
      if (retrySafe) headers["Idempotency-Key"] = attempts.current.key(path, body);
      const response = await fetch(apiUrl + root + path, {
        method,
        credentials: "include",
        cache: "no-store",
        signal,
        headers,
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        if (!signal.aborted && (response.status === 401 || response.status === 403)) {
          setData(null);
          setDraft(null);
          setDecision(null);
          setReason("");
        }
        throw new Error(await problem(response, "The advance change could not be completed."));
      }
      acknowledged = true;
      if (retrySafe) attempts.current.confirmed(path, body);
      if (signal.aborted) return;
      closeEditor();
      setNotice(success);
      await fetchData(signal);
    } catch (failure) {
      if (!signal.aborted) {
        if (acknowledged) setNeedsRefresh(true);
        const message = failure instanceof Error ? failure.message : "The request failed.";
        setError(
          acknowledged
            ? "The change was saved, but refreshed status is unavailable. Refresh before any further action. " +
                message
            : message +
                (retrySafe
                  ? " If the connection was interrupted, retry the unchanged form before leaving this screen; its request key remains in memory."
                  : " If the result is uncertain, refresh before retrying."),
        );
      }
    } finally {
      lock.current = false;
      if (!signal.aborted) setBusy(false);
    }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data || !draft) return;
    try {
      const body = advancePayload(data, draft, schoolToday());
      await mutate(
        draft.id ? "/" + draft.id : "",
        draft.id ? "PUT" : "POST",
        draft.id ? { advance: body, expectedVersion: draft.expectedVersion } : body,
        "Advance draft saved. No funds were transferred.",
        !draft.id,
      );
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Review the advance terms.");
    }
  }
  async function decide(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data || !decision) return;
    try {
      const request = advanceTransition(data, decision.id, decision.action, reason);
      await mutate(
        request.path,
        "POST",
        request.body,
        decision.action === "disbursements"
          ? "Disbursement journal prepared for independent accounting approval. No bank transfer was initiated."
          : "Advance workflow updated with protected history.",
        request.retrySafe,
      );
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Review the action.");
    }
  }
  function edit(record: Advance, button: HTMLButtonElement) {
    if (!data) return;
    begin(button);
    setDraft(advanceDraft(data, schoolToday(), record));
  }
  function action(record: Advance, selected: AdvanceAction, button: HTMLButtonElement) {
    begin(button);
    setDecision({ id: record.id, number: record.advanceNumber, action: selected });
  }
  async function viewStatement(record: Advance, button: HTMLButtonElement) {
    const signal = lifetime.current?.signal;
    if (!signal || signal.aborted || statementBusy) return;
    origin.current = button;
    setStatementBusy(true);
    setStatement(null);
    setError("");
    try {
      const params = new URLSearchParams({ schoolId });
      const response = await fetch(`${apiUrl}${root}/${record.id}/statement?${params}`, {
        credentials: "include",
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await problem(response, "The recovery statement is unavailable."));
      const result = (await response.json()) as AdvanceStatement;
      if (!isScopedAdvanceStatement(result, schoolId, record.id))
        throw new Error("The statement response did not match the selected school and advance.");
      if (!signal.aborted) setStatement(result);
    } catch (failure) {
      if (!signal.aborted)
        setError(failure instanceof Error ? failure.message : "The recovery statement is unavailable.");
    } finally {
      if (!signal.aborted) setStatementBusy(false);
    }
  }
  async function downloadStatement() {
    const signal = lifetime.current?.signal;
    if (!statement || !signal || signal.aborted || statementBusy) return;
    setStatementBusy(true);
    setError("");
    try {
      const params = new URLSearchParams({ schoolId });
      const response = await fetch(`${apiUrl}${root}/${statement.advanceId}/statement.csv?${params}`, {
        credentials: "include",
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await problem(response, "The private statement export is unavailable."));
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `employee-advance-${statement.advanceNumber.toLowerCase()}.csv`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (failure) {
      if (!signal.aborted)
        setError(failure instanceof Error ? failure.message : "The private statement export is unavailable.");
    } finally {
      if (!signal.aborted) setStatementBusy(false);
    }
  }
  const disabled = busy || loading || needsRefresh;
  return (
    <section
      className="pa-workspace pr-workspace"
      aria-labelledby="advances-heading"
      aria-busy={busy || loading}
    >
      <header className="payroll-panel pa-introduction">
        <h2 id="advances-heading" tabIndex={-1} ref={heading}>
          Employee advances & loans
        </h2>
        <p>
          Prepare, review and track school-scoped advances with independent approval and
          common-ledger disbursement.
        </p>
        <p className="pr-filter-note">
          Approved installments are deducted only in their scheduled month after the advance is
          posted. A reservation is not a repayment; recovered balances require a posted payroll accrual.
        </p>
        <div className="pa-toolbar">
          <Link href="/workspace/accounting">Open accounting and journal approvals</Link>
          <button type="button" disabled={busy || loading} onClick={() => void refresh()}>
            Refresh status
          </button>
          {data?.canPrepare && (
            <button
              type="button"
              disabled={disabled || !!draft || !!decision}
              onClick={(e) => {
                begin(e.currentTarget);
                setDraft(advanceDraft(data, schoolToday()));
              }}
            >
              Prepare advance
            </button>
          )}
        </div>
      </header>
      {error && (
        <div className="pr-error" role="alert" tabIndex={-1} ref={errorBox}>
          {error}
        </div>
      )}
      {notice && (
        <div className="payroll-banner is-success" role="status">
          {notice}
        </div>
      )}
      {loading && <p role="status">Loading protected advance records…</p>}
      {!loading && !data && (
        <p>
          Records are unavailable. Check your session and payroll/accounting access, then refresh.
          No records are shown from a previous school.
        </p>
      )}
      {data && (
        <>
          <form
            className="payroll-panel"
            onSubmit={(e) => {
              e.preventDefault();
              setFilters({ ...filterDraft, search: filterDraft.search.trim(), page: 0 });
            }}
          >
            <fieldset className="pa-form-body" disabled={disabled || !!draft || !!decision}>
              <legend>Find advances</legend>
              <div className="pa-fields">
                <label>
                  Search name, employee code or advance number
                  <input
                    type="search"
                    maxLength={120}
                    value={filterDraft.search}
                    onChange={(e) => setFilterDraft({ ...filterDraft, search: e.target.value })}
                  />
                </label>
                <label>
                  Status
                  <select
                    value={filterDraft.status}
                    onChange={(e) => setFilterDraft({ ...filterDraft, status: e.target.value })}
                  >
                    <option value="">All statuses</option>
                    {["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED"].map((s) => (
                      <option key={s} value={s}>
                        {s.toLowerCase()}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Employee
                  <select
                    value={filterDraft.staffProfileId}
                    onChange={(e) =>
                      setFilterDraft({ ...filterDraft, staffProfileId: e.target.value })
                    }
                  >
                    <option value="">All permitted employees</option>
                    {data.staff.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.displayName} · {s.employeeCode}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <footer>
                <button type="submit">Apply filters</button>
                <button
                  type="button"
                  onClick={() => {
                    setFilterDraft(emptyFilters);
                    setFilters({ ...emptyFilters });
                  }}
                >
                  Clear filters
                </button>
              </footer>
            </fieldset>
          </form>
          {draft && (
            <PayrollAdvanceForm
              key={draft.id ?? "new"}
              data={data}
              draft={draft}
              busy={disabled}
              today={schoolToday()}
              onChange={setDraft}
              onSave={save}
              onCancel={closeEditor}
            />
          )}
          {decision && (
            <form
              className="payroll-panel"
              aria-labelledby="advance-decision-heading"
              onSubmit={decide}
            >
              <fieldset className="pa-form-body" disabled={disabled}>
                <legend id="advance-decision-heading">
                  {advanceActionLabel[decision.action]} · {decision.number}
                </legend>
                <p>
                  {decision.action === "disbursements"
                    ? "This prepares a journal using approved frozen terms. An independent accountant must post it."
                    : decision.action === "cancel" || decision.action === "REJECT"
                      ? "This retains the record and its history. It does not delete financial or employee evidence."
                      : "Submitted terms and schedules cannot be overwritten. Independent approval is enforced by the server."}
                </p>
                <label>
                  Decision or action reason
                  <textarea
                    ref={reasonInput}
                    required
                    minLength={3}
                    maxLength={500}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
                <footer>
                  <button type="submit">
                    {busy ? "Saving…" : advanceActionLabel[decision.action]}
                  </button>
                  <button type="button" className="is-quiet" onClick={closeEditor}>
                    Close without action
                  </button>
                </footer>
              </fieldset>
            </form>
          )}
          <PayrollAdvanceRecords
            data={data}
            busy={disabled || !!draft || !!decision}
            onEdit={edit}
            onAction={action}
            onStatement={viewStatement}
          />
          {statementBusy && <p role="status">Loading protected recovery statement…</p>}
          {statement && (
            <>
              <div className="pa-toolbar">
                <p>Private financial record. Store and share the export securely.</p>
                <button type="button" disabled={statementBusy} onClick={() => void downloadStatement()}>
                  Download complete CSV
                </button>
                <button type="button" className="is-quiet" onClick={() => setStatement(null)}>
                  Close statement
                </button>
              </div>
              <PayrollAdvanceStatementEvidence statement={statement} />
            </>
          )}
          <nav className="pa-toolbar" aria-label="Advance register pages">
            <p>
              Page {data.page + 1} · {data.total} matching records
            </p>
            <button
              type="button"
              disabled={disabled || !!draft || !!decision || data.page === 0}
              onClick={() => setFilters({ ...filters, page: Math.max(0, data.page - 1) })}
            >
              Previous page
            </button>
            <button
              type="button"
              disabled={
                disabled || !!draft || !!decision || (data.page + 1) * data.size >= data.total
              }
              onClick={() => setFilters({ ...filters, page: data.page + 1 })}
            >
              Next page
            </button>
          </nav>
        </>
      )}
    </section>
  );
}
