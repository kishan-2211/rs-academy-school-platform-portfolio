"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent, type MouseEvent } from "react";
import Link from "next/link";
import {
  configurationPayload,
  createPostingAttempts,
  effectiveMapping,
  eligiblePaymentStaff,
  mappingDraft,
  openAccrualYears,
  payrollLabel as label,
  payrollMoney as money,
  postingPayload,
  schoolToday,
  type Configuration,
  type MappingDraft,
  type PayrollAccountingData,
  type PostingDraft,
  type Run,
} from "../../lib/payroll-accounting";
import { PayrollMappingForm, PayrollPostingForm } from "./payroll-accounting-forms";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const root = "/api/v1/payroll/accounting";
async function problem(response: Response, fallback: string) {
  try {
    return ((await response.json()) as { detail?: string }).detail || fallback;
  } catch {
    return fallback;
  }
}
async function csrfToken() {
  const read = () =>
    document.cookie
      .split("; ")
      .find((part) => part.startsWith("educore_csrf="))
      ?.slice("educore_csrf=".length);
  let token = read();
  if (!token) {
    await fetch(apiUrl + "/api/v1/auth/sessions/current", {
      credentials: "include",
      cache: "no-store",
    });
    token = read();
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}
type Decision = { configuration: Configuration; action: "submit" | "APPROVE" | "REJECT" };

export function PayrollAccountingWorkspace({ schoolId }: { schoolId: string }) {
  const [data, setData] = useState<PayrollAccountingData | null>(null);
  const [yearId, setYearId] = useState("");
  const [mapping, setMapping] = useState<MappingDraft | null>(null);
  const [posting, setPosting] = useState<PostingDraft | null>(null);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const lock = useRef(false);
  const origin = useRef<HTMLButtonElement | null>(null);
  const heading = useRef<HTMLHeadingElement | null>(null);
  const errorBox = useRef<HTMLDivElement | null>(null);
  const reasonInput = useRef<HTMLTextAreaElement | null>(null);
  const attempts = useRef(createPostingAttempts(() => crypto.randomUUID()));

  const load = useCallback(
    async (signal?: AbortSignal) => {
      const response = await fetch(apiUrl + root + "?schoolId=" + encodeURIComponent(schoolId), {
        credentials: "include",
        cache: "no-store",
        signal,
      });
      if (!response.ok) {
        if (response.status === 401 || response.status === 403) setData(null);
        throw new Error(await problem(response, "Payroll accounting is unavailable."));
      }
      const result = (await response.json()) as PayrollAccountingData;
      if (signal?.aborted) return;
      setData(result);
      setYearId(
        (current) =>
          current || openAccrualYears(result)[0]?.id || result.financialYears[0]?.id || "",
      );
    },
    [schoolId],
  );
  useEffect(() => {
    const controller = new AbortController();
    void Promise.resolve()
      .then(() => load(controller.signal))
      .catch((failure: unknown) => {
        if (!controller.signal.aborted)
          setError(
            failure instanceof Error ? failure.message : "Payroll accounting is unavailable.",
          );
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [load]);
  useEffect(() => {
    if (decision) reasonInput.current?.focus();
  }, [decision]);
  useEffect(() => {
    if (error) errorBox.current?.focus();
  }, [error]);

  function closeEditor() {
    setMapping(null);
    setPosting(null);
    setDecision(null);
    setReason("");
    requestAnimationFrame(() =>
      (origin.current?.isConnected && !origin.current.disabled
        ? origin.current
        : heading.current
      )?.focus(),
    );
  }
  function begin(event: MouseEvent<HTMLButtonElement>) {
    origin.current = event.currentTarget;
    setError("");
    setNotice("");
    setMapping(null);
    setPosting(null);
    setDecision(null);
    setReason("");
  }
  async function refresh() {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    try {
      await load();
      setNotice("Posting status refreshed from the common ledger.");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Refresh failed.");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  async function mutate(
    path: string,
    method: "POST" | "PUT",
    body: object,
    success: string,
    retrySafe = false,
  ) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    let acknowledged = false;
    try {
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": await csrfToken(),
      };
      if (retrySafe) headers["Idempotency-Key"] = attempts.current.key(path, body);
      const response = await fetch(apiUrl + root + path, {
        method,
        credentials: "include",
        cache: "no-store",
        headers,
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        if (response.status === 401 || response.status === 403) setData(null);
        throw new Error(await problem(response, "The request could not be completed."));
      }
      // A successful HTTP result is final even if the subsequent refresh fails.
      acknowledged = true;
      if (retrySafe) attempts.current.confirmed(path, body);
      closeEditor();
      setNotice(success);
      await load();
    } catch (failure) {
      const message =
        failure instanceof Error ? failure.message : "The request could not be completed.";
      setError(
        acknowledged
          ? "The change was saved, but refreshed status is unavailable. Do not prepare it again; refresh the ledger status. " +
              message
          : message +
              (retrySafe
                ? " If the connection was interrupted, retry the unchanged form; its request key is retained while this screen stays open."
                : " Refresh before retrying if the result is uncertain."),
      );
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  async function saveMapping(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data || !mapping) return;
    try {
      const body = configurationPayload(data, mapping);
      await mutate(
        mapping.id ? "/configurations/" + mapping.id : "/configurations",
        mapping.id ? "PUT" : "POST",
        mapping.id ? { configuration: body, expectedVersion: mapping.expectedVersion } : body,
        "Mapping draft saved. Submit it for independent review when ready.",
      );
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Check the mapping fields.");
    }
  }
  async function prepare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data || !posting) return;
    try {
      const body = postingPayload(data, posting, schoolToday());
      await mutate(
        "/runs/" + posting.runId + "/" + posting.kind,
        "POST",
        body,
        "Journal prepared for independent approval in Accounting. No money was transferred.",
        true,
      );
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Check the posting fields.");
    }
  }
  async function decide(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!decision || reason.trim().length < 3) return;
    const { configuration, action } = decision;
    await mutate(
      "/configurations/" + configuration.id + (action === "submit" ? "/submit" : "/decision"),
      "POST",
      {
        schoolId,
        expectedVersion: configuration.rowVersion,
        reason: reason.trim(),
        ...(action === "submit" ? {} : { decision: action }),
      },
      action === "submit"
        ? "Mapping submitted for an independent decision."
        : action === "APPROVE"
          ? "Mapping approved with prior history preserved."
          : "Mapping rejected; prior approved mapping remains unchanged.",
    );
  }
  function startPosting(
    event: MouseEvent<HTMLButtonElement>,
    run: Run,
    kind: PostingDraft["kind"],
  ) {
    if (!data) return;
    begin(event);
    const date = kind === "accruals" ? run.periodEnd : schoolToday();
    const year = openAccrualYears(data).find(
      (item) =>
        item.currencyCode === run.currencyCode && item.startsOn <= date && item.endsOn >= date,
    );
    const effective = effectiveMapping(data, year?.id ?? "", date);
    setPosting({
      kind,
      runId: run.id,
      financialYearId: year?.id ?? "",
      postingDate: date,
      paymentMethod: effective?.tenderMappings[0]?.paymentMethod ?? "CASH",
      paymentReference: "",
      staffIds: [],
      reason: "",
    });
  }

  if (loading)
    return (
      <section className="payroll-panel payroll-run-state" aria-live="polite" aria-busy="true">
        <span />
        <p>Opening protected payroll accounting…</p>
      </section>
    );
  if (!data)
    return (
      <section className="payroll-panel payroll-run-state pa-workspace">
        <h2>Payroll accounting is unavailable</h2>
        <p role="alert">{error || "Both payroll and accounting access are required."}</p>
        <button type="button" disabled={busy} onClick={() => void refresh()}>
          Retry access check
        </button>
      </section>
    );
  const configurations = data.configurations.filter((item) => item.financialYearId === yearId);
  const pending = configurations.find((item) => ["DRAFT", "SUBMITTED"].includes(item.status));
  const approved = configurations.find((item) => item.status === "APPROVED");
  const year = data.financialYears.find((item) => item.id === yearId);
  const accountName = (id: string | null) =>
    data.accounts.find((account) => account.id === id)?.displayName ?? "Unavailable account";
  const editing = Boolean(mapping || posting || decision);
  return (
    <section className="pa-workspace" aria-labelledby="pa-title">
      <article className="payroll-panel">
        <header>
          <div>
            <span>Payroll → common ledger</span>
            <h2 id="pa-title" tabIndex={-1} ref={heading}>
              Salary accounting & payments
            </h2>
          </div>
          <button
            type="button"
            className="is-secondary"
            disabled={busy || editing}
            onClick={() => void refresh()}
          >
            Refresh status
          </button>
        </header>
        <div className="pa-introduction">
          <p>
            Prepare payroll accruals and salary settlement evidence, then use{" "}
            <Link href="/workspace/accounting" prefetch={false}>
              Accounting
            </Link>{" "}
            for independent journal approval, rejection or controlled reversal. This screen never
            transfers money.
          </p>
          <p>
            Latest 24 finalized/reversed run attempts and 100 batches only. These lists are not a
            complete historical register.
          </p>
        </div>
      </article>
      {error && (
        <div className="payroll-banner is-error" role="alert" ref={errorBox} tabIndex={-1}>
          {error}
        </div>
      )}
      {notice && (
        <div className="payroll-banner is-success" role="status">
          {notice}
        </div>
      )}
      {mapping && (
        <PayrollMappingForm
          data={data}
          draft={mapping}
          busy={busy}
          onChange={setMapping}
          onSubmit={saveMapping}
          onClose={closeEditor}
        />
      )}
      {posting && (
        <PayrollPostingForm
          data={data}
          draft={posting}
          busy={busy}
          onChange={setPosting}
          onSubmit={prepare}
          onClose={closeEditor}
        />
      )}
      {decision && (
        <form
          className="payroll-panel pa-editor"
          aria-labelledby="pa-decision-title"
          onSubmit={decide}
        >
          <header>
            <h3 id="pa-decision-title">
              {label(decision.action)} mapping revision {decision.configuration.revisionNumber}
            </h3>
            <button disabled={busy} className="is-secondary" type="button" onClick={closeEditor}>
              Close
            </button>
          </header>
          <fieldset disabled={busy} className="pa-form-body">
            <legend className="sr-only">Recorded mapping decision</legend>
            <p>
              Effective {decision.configuration.effectiveFrom} · prepared by{" "}
              {decision.configuration.creatorName}. A different authorized person must approve or
              reject. The server rechecks the current version and permissions.
            </p>
            <label>
              Reason
              <textarea
                ref={reasonInput}
                required
                minLength={3}
                maxLength={500}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </label>
            <button type="submit">{busy ? "Saving…" : "Confirm " + label(decision.action)}</button>
          </fieldset>
        </form>
      )}
      <article className="payroll-panel">
        <header>
          <h2>Posting mappings</h2>
        </header>
        <div className="pa-form-body pa-toolbar">
          <label>
            Mapping financial year
            <select
              value={yearId}
              disabled={busy || editing}
              onChange={(event) => setYearId(event.target.value)}
            >
              <option value="">Choose financial year</option>
              {data.financialYears.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.displayName} · {label(item.status)} · {item.accountingBasis}
                </option>
              ))}
            </select>
          </label>
          {data.canConfigure &&
            year?.status === "OPEN" &&
            year.accountingBasis === "ACCRUAL" &&
            !pending && (
              <button
                type="button"
                disabled={busy || editing}
                onClick={(event) => {
                  begin(event);
                  setMapping(mappingDraft(data, yearId, approved));
                }}
              >
                {approved ? "Prepare next revision" : "Prepare mapping"}
              </button>
            )}
        </div>
        {!data.financialYears.length && (
          <p className="payroll-empty">
            Create and open an accrual financial year in Accounting first.
          </p>
        )}
        {!configurations.length && (
          <p className="payroll-empty">
            No mappings for this financial year. Configure posting accounts and active salary
            components before preparing a mapping.
          </p>
        )}
        <div className="pa-records">
          {configurations.map((configuration) => (
            <article key={configuration.id}>
              <header>
                <h3>
                  Revision {configuration.revisionNumber} · {configuration.effectiveFrom}
                </h3>
                <span className="pa-status">{label(configuration.status)}</span>
              </header>
              <p>
                Prepared by {configuration.creatorName}
                {configuration.deciderName ? " · decided by " + configuration.deciderName : ""} ·
                Salary payable: {accountName(configuration.salaryPayableAccountId)}
              </p>
              <details>
                <summary>Review component and payment-method accounts</summary>
                <ul>
                  {configuration.componentMappings.map((item) => (
                    <li key={item.payrollComponentId}>
                      <b>{item.componentName}</b> ·{" "}
                      {item.expenseAccountId
                        ? "Expense: " + accountName(item.expenseAccountId)
                        : ""}
                      {item.expenseAccountId && item.payableAccountId ? " · " : ""}
                      {item.payableAccountId
                        ? "Payable: " + accountName(item.payableAccountId)
                        : ""}
                    </li>
                  ))}
                  {configuration.tenderMappings.map((item) => (
                    <li key={item.paymentMethod}>
                      {label(item.paymentMethod)}: {accountName(item.paymentAccountId)}
                    </li>
                  ))}
                </ul>
              </details>
              <footer>
                {configuration.canEdit && (
                  <button
                    type="button"
                    disabled={busy || editing}
                    onClick={(event) => {
                      begin(event);
                      setMapping(mappingDraft(data, yearId, configuration, true));
                    }}
                  >
                    Edit draft
                  </button>
                )}
                {configuration.canSubmit && (
                  <button
                    type="button"
                    disabled={busy || editing}
                    onClick={(event) => {
                      begin(event);
                      setDecision({ configuration, action: "submit" });
                    }}
                  >
                    Submit for review
                  </button>
                )}
                {configuration.canDecide && (
                  <>
                    {(["APPROVE", "REJECT"] as const).map((action) => (
                      <button
                        key={action}
                        className={action === "REJECT" ? "is-secondary" : ""}
                        type="button"
                        disabled={busy || editing}
                        onClick={(event) => {
                          begin(event);
                          setDecision({ configuration, action });
                        }}
                      >
                        {label(action)} independently
                      </button>
                    ))}
                  </>
                )}
              </footer>
            </article>
          ))}
        </div>
      </article>
      <article className="payroll-panel">
        <header>
          <h2>Finalized payroll & salary status</h2>
        </header>
        {!data.runs.length && (
          <p className="payroll-empty">
            Finalize a monthly payroll run before preparing its accrual.
          </p>
        )}
        <div className="pa-records">
          {data.runs.map((run) => (
            <article key={run.id}>
              <header>
                <h3>{run.runCode}</h3>
                <span className="pa-status">
                  {label(run.status)} · Accrual {label(run.accrualStatus)}
                </span>
              </header>
              <p>
                {run.payrollMonth.slice(0, 7)} · {run.staffCount} staff · Net salary{" "}
                {money(run.net, run.currencyCode)}
              </p>
              <details>
                <summary>Staff payment status</summary>
                <ul>
                  {run.staff.map((staff) => (
                    <li key={staff.id}>
                      {staff.staffName} · {staff.employeeCode} ·{" "}
                      {money(staff.net, run.currencyCode)} · {label(staff.paymentStatus)}
                    </li>
                  ))}
                </ul>
              </details>
              <footer>
                {data.canPrepare &&
                  run.status === "FINALIZED" &&
                  run.accrualStatus === "NOT_PREPARED" && (
                    <button
                      type="button"
                      disabled={busy || editing}
                      onClick={(event) => startPosting(event, run, "accruals")}
                    >
                      Prepare accrual
                    </button>
                  )}
                {data.canPrepare && eligiblePaymentStaff(run).length > 0 && (
                  <button
                    type="button"
                    disabled={busy || editing}
                    onClick={(event) => startPosting(event, run, "payments")}
                  >
                    Prepare salary payment evidence
                  </button>
                )}
                {run.accrualStatus === "AWAITING_APPROVAL" && (
                  <p>Accrual journal awaits independent approval in Accounting.</p>
                )}
              </footer>
            </article>
          ))}
        </div>
      </article>
      <article className="payroll-panel">
        <header>
          <h2>Recent posting evidence</h2>
          <Link href="/workspace/accounting" prefetch={false}>
            Open Accounting
          </Link>
        </header>
        {data.postingBatches.length ? (
          <div
            className="pa-table-wrap"
            tabIndex={0}
            role="region"
            aria-label="Recent payroll posting evidence, scroll horizontally if needed"
          >
            <table>
              <caption>Latest 100 batches — status comes from the linked ledger journal</caption>
              <thead>
                <tr>
                  <th scope="col">Batch / run</th>
                  <th scope="col">Date</th>
                  <th scope="col">Amount</th>
                  <th scope="col">Evidence</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {data.postingBatches.map((batch) => {
                  const currency = data.financialYears.find(
                    (item) => item.id === batch.financialYearId,
                  )?.currencyCode;
                  return (
                    <tr key={batch.id}>
                      <th scope="row">
                        {label(batch.postingType)} #{batch.sequenceNumber}
                        <small>
                          {data.runs.find((run) => run.id === batch.payrollRunId)?.runCode ??
                            "Earlier payroll run"}
                        </small>
                        <details>
                          <summary>Audit references</summary>
                          <span>Batch: {batch.id}</span>
                          <span>Journal: {batch.accountingJournalId ?? "Not linked"}</span>
                          <span>Prepared by {batch.preparerName}</span>
                        </details>
                      </th>
                      <td>{batch.postingDate}</td>
                      <td>
                        {currency
                          ? money(batch.sourceAmount, currency)
                          : batch.sourceAmount.toFixed(2) + " (currency unavailable)"}
                      </td>
                      <td>
                        {batch.sourceCount} sources
                        <small>
                          {label(batch.paymentMethod)}
                          {batch.paymentReference ? " · " + batch.paymentReference : ""}
                        </small>
                      </td>
                      <td>
                        <span className="pa-status">{label(batch.operationalStatus)}</span>
                        <small>Journal: {label(batch.journalStatus)}</small>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="payroll-empty">No accrual or salary payment batch has been prepared.</p>
        )}
        <p className="pa-introduction">
          Corrections preserve history: reject or reverse payment journals first, then the accrual,
          before requesting a payroll-run reversal.
        </p>
      </article>
    </section>
  );
}
