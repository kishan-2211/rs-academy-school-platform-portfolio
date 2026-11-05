"use client";

import { useEffect, useRef, type FormEvent } from "react";
import {
  advanceAccounts,
  advanceYears,
  type Advance,
  type AdvanceData,
  type AdvanceDraft,
  type AdvanceAction,
} from "../../lib/payroll-advances";
import {
  PAYMENT_METHODS,
  payrollLabel as label,
  payrollMoney as money,
} from "../../lib/payroll-accounting";

export function PayrollAdvanceForm({
  data,
  draft,
  busy,
  today,
  onChange,
  onSave,
  onCancel,
}: {
  data: AdvanceData;
  draft: AdvanceDraft;
  busy: boolean;
  today: string;
  onChange: (draft: AdvanceDraft) => void;
  onSave: (event: FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}) {
  const first = useRef<HTMLSelectElement>(null);
  useEffect(() => {
    first.current?.focus();
  }, []);
  const years = advanceYears(data);
  const field = (key: keyof AdvanceDraft, value: string) => onChange({ ...draft, [key]: value });
  return (
    <form className="payroll-panel" aria-labelledby="advance-form-heading" onSubmit={onSave}>
      <header className="pa-introduction">
        <h3 id="advance-form-heading">
          {draft.id ? "Edit advance draft" : "Prepare advance or loan"}
        </h3>
        <p>
          Saving creates a draft only. Submission, independent approval and accounting posting are
          separate steps.
        </p>
      </header>
      <fieldset disabled={busy} className="pa-form-body">
        <legend>Advance terms</legend>
        <div className="pa-fields">
          <label>
            Employee
            <select
              ref={first}
              required
              value={draft.staffProfileId}
              onChange={(e) => field("staffProfileId", e.target.value)}
            >
              <option value="">Choose employee</option>
              {data.staff.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.displayName} · {s.employeeCode}
                </option>
              ))}
            </select>
          </label>
          <label>
            Type
            <select
              value={draft.advanceType}
              onChange={(e) => field("advanceType", e.target.value)}
            >
              <option value="ADVANCE">Salary advance</option>
              <option value="LOAN">Employee loan</option>
            </select>
          </label>
          <label>
            Financial year
            <select
              required
              value={draft.financialYearId}
              onChange={(e) => field("financialYearId", e.target.value)}
            >
              <option value="">Choose open accrual year</option>
              {years.map((y) => (
                <option key={y.id} value={y.id}>
                  {y.displayName} · {y.currencyCode}
                </option>
              ))}
            </select>
          </label>
          <label>
            Issue date
            <input
              type="date"
              required
              max={today}
              value={draft.issueDate}
              onChange={(e) => field("issueDate", e.target.value)}
            />
          </label>
          <label>
            Principal amount
            <input
              type="text"
              inputMode="decimal"
              required
              maxLength={16}
              value={draft.principalAmount}
              onChange={(e) => field("principalAmount", e.target.value)}
              aria-describedby="advance-plan-note"
            />
          </label>
          <label>
            Monthly installments
            <input
              type="number"
              min="1"
              max="120"
              step="1"
              required
              value={draft.installmentCount}
              onChange={(e) => field("installmentCount", e.target.value)}
            />
          </label>
          <label>
            Recovery starting month
            <input
              type="month"
              required
              min={draft.issueDate.slice(0, 7)}
              value={draft.recoveryStartMonth.slice(0, 7)}
              onChange={(e) =>
                field("recoveryStartMonth", e.target.value ? e.target.value + "-01" : "")
              }
            />
          </label>
          <label>
            Employee receivable account
            <select
              required
              value={draft.receivableAccountId}
              onChange={(e) => field("receivableAccountId", e.target.value)}
            >
              <option value="">Choose receivable account</option>
              {advanceAccounts(data.accounts, "RECEIVABLE").map((a) => (
                <option key={a.id} value={a.id}>
                  {a.code} · {a.displayName}
                </option>
              ))}
            </select>
          </label>
          <label>
            Payment method
            <select
              value={draft.paymentMethod}
              onChange={(e) =>
                onChange({
                  ...draft,
                  paymentMethod: e.target.value as AdvanceDraft["paymentMethod"],
                  disbursementAccountId: "",
                })
              }
            >
              {PAYMENT_METHODS.map((method) => (
                <option key={method} value={method}>
                  {label(method)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Cash or bank account
            <select
              required
              value={draft.disbursementAccountId}
              onChange={(e) => field("disbursementAccountId", e.target.value)}
            >
              <option value="">Choose matching account</option>
              {advanceAccounts(data.accounts, draft.paymentMethod === "CASH" ? "CASH" : "BANK").map(
                (a) => (
                  <option key={a.id} value={a.id}>
                    {a.code} · {a.displayName}
                  </option>
                ),
              )}
            </select>
          </label>
          <label>
            Payment reference{draft.paymentMethod === "CASH" ? " (optional)" : ""}
            <input
              type="text"
              required={draft.paymentMethod !== "CASH"}
              minLength={2}
              maxLength={120}
              value={draft.paymentReference}
              onChange={(e) => field("paymentReference", e.target.value)}
            />
          </label>
        </div>
        <label>
          Purpose
          <textarea
            required
            minLength={3}
            maxLength={500}
            value={draft.purpose}
            onChange={(e) => field("purpose", e.target.value)}
          />
        </label>
        <label>
          Preparation or edit reason
          <textarea
            required
            minLength={3}
            maxLength={500}
            value={draft.reason}
            onChange={(e) => field("reason", e.target.value)}
          />
        </label>
        <p id="advance-plan-note">
          The API saves a monthly plan with the rounding remainder in the final installment. A plan
          is not proof of repayment. Approved due installments enter payroll only after a posted
          disbursement; saving this form never transfers money.
        </p>
        {(!years.length || !advanceAccounts(data.accounts, "RECEIVABLE").length) && (
          <p role="status">
            Set up an open accrual year and employee receivable account in Accounting before saving.
          </p>
        )}
        <footer>
          <button type="submit">{busy ? "Saving…" : "Save draft"}</button>
          <button type="button" className="is-quiet" onClick={onCancel}>
            Close without saving
          </button>
        </footer>
      </fieldset>
    </form>
  );
}

export const advanceActionLabel: Record<AdvanceAction, string> = {
  submit: "Submit for review",
  cancel: "Cancel draft",
  APPROVE: "Approve advance",
  REJECT: "Reject advance",
  disbursements: "Prepare disbursement",
};

export function PayrollAdvanceRecords({
  data,
  busy,
  onEdit,
  onAction,
  onStatement,
}: {
  data: AdvanceData;
  busy: boolean;
  onEdit: (record: Advance, button: HTMLButtonElement) => void;
  onAction: (record: Advance, action: AdvanceAction, button: HTMLButtonElement) => void;
  onStatement?: (record: Advance, button: HTMLButtonElement) => void;
}) {
  return (
    <section className="payroll-panel pa-records" aria-labelledby="advance-records-heading">
      <header className="pa-introduction">
        <h3 id="advance-records-heading">Advance register</h3>
        <p>{data.total} matching records · saved evidence only</p>
      </header>
      {!data.advances.length && (
        <div className="pa-introduction">
          <p>No advances match these filters. Change the filters or prepare a permitted draft.</p>
        </div>
      )}
      {data.advances.map((a) => (
        <article key={a.id}>
          <header>
            <div>
              <h3>
                {a.advanceNumber} · {a.staffName}
              </h3>
              <small>
                {a.employeeCode} · {label(a.advanceType)} · issued {a.issueDate}
              </small>
            </div>
            <span className="pa-status">{label(a.operationalStatus)}</span>
          </header>
          <p>{a.purpose}</p>
          <dl className="pa-totals">
            <div>
              <dt>Approved/requested principal</dt>
              <dd>{money(a.principalAmount, a.currencyCode)}</dd>
            </div>
            <div>
              <dt>Ledger-confirmed disbursement</dt>
              <dd>
                {a.postedDisbursementAmount === null
                  ? "Unverified"
                  : money(a.postedDisbursementAmount, a.currencyCode)}
              </dd>
            </div>
            <div>
              <dt>Recovery plan</dt>
              <dd>
                {a.installmentCount} months from {a.recoveryStartMonth.slice(0, 7)}
              </dd>
            </div>
            <div>
              <dt>Ledger-confirmed recovered</dt>
              <dd>{money(a.recoveredAmount, a.currencyCode)}</dd>
            </div>
            <div>
              <dt>Outstanding</dt>
              <dd>{a.outstandingAmount === null ? "Starts after posted disbursement" : money(a.outstandingAmount, a.currencyCode)}</dd>
            </div>
            <div>
              <dt>Recovery status</dt>
              <dd>{label(a.recoveryStatus)}</dd>
            </div>
          </dl>
          <details>
            <summary>Saved terms, schedule and approval history</summary>
            <p>
              Prepared by {a.creatorName}. Status: {label(a.status)}. Payment method:{" "}
              {label(a.paymentMethod)}. Reference: {a.paymentReference || "Not recorded"}.
            </p>
            <div
              className="pa-table-wrap"
              tabIndex={0}
              role="region"
              aria-label={"Planned installments for " + a.advanceNumber}
            >
              <table>
                <caption>Approved installment plan — repayment requires a posted payroll accrual</caption>
                <thead>
                  <tr>
                    <th scope="col">Installment</th>
                    <th scope="col">Due month</th>
                    <th scope="col">Planned amount</th>
                  </tr>
                </thead>
                <tbody>
                  {a.installments.map((i) => (
                    <tr key={i.id}>
                      <th scope="row">{i.installmentNumber}</th>
                      <td>{i.dueMonth.slice(0, 7)}</td>
                      <td>{money(i.plannedAmount, a.currencyCode)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul>
              {a.history.map((h) => (
                <li key={h.id}>
                  <strong>{label(h.eventType)}</strong> · {h.actorName} ·{" "}
                  <time dateTime={h.occurredAt}>{h.occurredAt}</time>
                  <p>{h.reason}</p>
                </li>
              ))}
            </ul>
          </details>
          <details>
            <summary>Disbursement journal evidence ({a.postings.length})</summary>
            {!a.postings.length ? (
              <p>No disbursement journal has been prepared.</p>
            ) : (
              <ul>
                {a.postings.map((p) => (
                  <li key={p.id}>
                    <strong>
                      Attempt {p.sequenceNumber} · {label(p.operationalStatus)}
                    </strong>
                    <p>
                      {p.postingDate} · {money(p.sourceAmount, a.currencyCode)} · {p.preparerName}
                    </p>
                    <p>
                      Journal: <code>{p.accountingJournalId || "Unverified"}</code> ·{" "}
                      {p.journalStatus ? label(p.journalStatus) : "Status unverified"}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </details>
          <footer>
            {onStatement && (
              <button
                type="button"
                disabled={busy}
                onClick={(event) => onStatement(a, event.currentTarget)}
              >
                View recovery statement
              </button>
            )}
            {a.canEdit && (
              <button type="button" disabled={busy} onClick={(e) => onEdit(a, e.currentTarget)}>
                Edit draft
              </button>
            )}
            {(
              [
                ["submit", a.canSubmit],
                ["cancel", a.canCancel],
                ["APPROVE", a.canDecide],
                ["REJECT", a.canDecide],
                ["disbursements", a.canPrepareDisbursement],
              ] as const
            )
              .filter(([, allowed]) => allowed)
              .map(([action]) => (
                <button
                  key={action}
                  type="button"
                  disabled={busy}
                  onClick={(e) => onAction(a, action, e.currentTarget)}
                >
                  {advanceActionLabel[action]}
                </button>
              ))}
          </footer>
        </article>
      ))}
    </section>
  );
}
