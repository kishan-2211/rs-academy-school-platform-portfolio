"use client";

import type { FormEvent } from "react";
import { payrollLabel as label, payrollMoney as money } from "../../lib/payroll-accounting";
import {
  isAdvanceReport,
  isMonthlyReport,
  reportStatusTone,
  type ReportFilters,
  type ReportKind,
  type ReportResult,
} from "../../lib/payroll-reports";

export function PayrollReportFilters({
  filters,
  busy,
  onChange,
  onSubmit,
}: {
  filters: ReportFilters;
  busy: boolean;
  onChange: (next: ReportFilters) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const monthly = isMonthlyReport(filters.kind);
  const advance = isAdvanceReport(filters.kind);
  function changeKind(kind: ReportKind) {
    if (kind === filters.kind) return;
    const nextMonthly = isMonthlyReport(kind);
    onChange({
      ...filters,
      kind,
      from: isAdvanceReport(kind) ? filters.from
        : nextMonthly ? filters.from.slice(0, 7)
          : filters.from.length === 7 ? filters.from + "-01" : filters.from,
      to: isAdvanceReport(kind) ? filters.to
        : nextMonthly ? filters.to.slice(0, 7)
          : filters.to.length === 7 ? filters.to + "-01" : filters.to,
    });
  }
  return (
    <form className="payroll-panel" onSubmit={onSubmit} aria-labelledby="payroll-report-filters">
      <fieldset className="pa-form-body" disabled={busy}>
        <legend id="payroll-report-filters">Report filters</legend>
        <div className="pa-fields">
          <label>
            Report
            <select
              value={filters.kind}
              onChange={(event) => changeKind(event.target.value as ReportKind)}
            >
              <option value="register">Historical salary register</option>
              <option value="department">Payroll summary by department</option>
              <option value="designation">Payroll summary by designation</option>
              <option value="deductionComponent">Deductions by component</option>
              <option value="reconciliation">Payroll-to-ledger reconciliation</option>
              <option value="advanceReconciliation">Advance recovery reconciliation</option>
            </select>
          </label>
          <label>
            Employee name or code
            <input
              type="search"
              maxLength={100}
              value={filters.search}
              autoComplete="off"
              onChange={(event) => onChange({ ...filters, search: event.target.value })}
            />
            <small>Search includes names saved in historical payroll runs.</small>
          </label>
          {advance ? (
            <label>
              Installments due through
              <input
                type="date"
                required
                value={filters.dueThrough}
                onChange={(event) => onChange({ ...filters, dueThrough: event.target.value })}
              />
              <small>Ledger and reversal evidence is current when loaded.</small>
            </label>
          ) : (
            <>
              <label>
                {monthly ? "From payroll month" : "From posting date"}
                <input
                  type={monthly ? "month" : "date"}
                  required
                  value={filters.from}
                  max={filters.to || undefined}
                  onChange={(event) => onChange({ ...filters, from: event.target.value })}
                />
              </label>
              <label>
                {monthly ? "Through payroll month" : "Through posting date"}
                <input
                  type={monthly ? "month" : "date"}
                  required
                  value={filters.to}
                  min={filters.from || undefined}
                  onChange={(event) => onChange({ ...filters, to: event.target.value })}
                />
              </label>
            </>
          )}
        </div>
        <footer>
          <p>
            {advance
              ? "Shows approved advances issued by this date, current ledger-backed recovery, overdue installments and visible evidence mismatches."
              : monthly
              ? filters.kind === "register"
                ? "Salary figures are frozen. Payment status is current when the report is loaded."
                : "Groups use frozen payroll evidence and stay separate by currency and finalized or reversed run state."
              : "An employee search selects whole matching batches, including all their staff. This is not bank-statement reconciliation."}
          </p>
          <button type="submit">Load report</button>
        </footer>
      </fieldset>
    </form>
  );
}

function Status({ value }: { value: string }) {
  return <span className={"pa-status pr-status-" + reportStatusTone(value)}>{label(value)}</span>;
}
const amount = (value: number | null, currency: string) =>
  value === null ? "Not verified" : money(value, currency);
function Reference({ name, value }: { name: string; value: string | null }) {
  return value ? (
    <span>
      {name}: <code>{value}</code>
    </span>
  ) : null;
}
export function PayrollReportTable({ report }: { report: ReportResult }) {
  if (!report.data.items.length)
    return <p className="pa-introduction">No records match these filters.</p>;
  if (report.kind === "register")
    return (
      <div
        className="pa-table-wrap"
        role="region"
        aria-label="Salary register table, scroll horizontally for more columns"
        tabIndex={0}
      >
        <table className="pr-table">
          <caption>
            Frozen salary figures and current settlement. Each row retains its own currency;
            reversed salaries are not new dues.
          </caption>
          <thead>
            <tr>
              <th scope="col">Employee / run</th>
              <th scope="col">Frozen salary</th>
              <th scope="col">Accounting status</th>
              <th scope="col">Current settlement</th>
              <th scope="col">References</th>
            </tr>
          </thead>
          <tbody>
            {report.data.items.map((row) => (
              <tr key={row.id}>
                <th scope="row">
                  <strong>{row.staffName}</strong>
                  <small>{row.employeeCode}</small>
                  <small>
                    {row.payrollMonth.slice(0, 7)} · {row.runCode}
                  </small>
                  <Status value={row.runStatus} />
                </th>
                <td>
                  <dl className="pr-amounts">
                    <div>
                      <dt>Gross</dt>
                      <dd>{amount(row.gross, row.currencyCode)}</dd>
                    </div>
                    <div>
                      <dt>Deductions</dt>
                      <dd>{amount(row.deductions, row.currencyCode)}</dd>
                    </div>
                    <div>
                      <dt>Employer contributions</dt>
                      <dd>{amount(row.employerContributions, row.currencyCode)}</dd>
                    </div>
                    <div>
                      <dt>Net</dt>
                      <dd>{amount(row.net, row.currencyCode)}</dd>
                    </div>
                  </dl>
                </td>
                <td>
                  <small>Accrual</small>
                  <Status value={row.accrualStatus} />
                  <small>Payment</small>
                  <Status value={row.paymentStatus} />
                </td>
                <td>
                  <dl className="pr-amounts">
                    <div>
                      <dt>Posted paid</dt>
                      <dd>{amount(row.paid, row.currencyCode)}</dd>
                    </div>
                    <div>
                      <dt>Awaiting approval</dt>
                      <dd>{amount(row.pending, row.currencyCode)}</dd>
                    </div>
                    <div>
                      <dt>Unsettled (includes pending)</dt>
                      <dd>{amount(row.unsettled, row.currencyCode)}</dd>
                    </div>
                  </dl>
                </td>
                <td>
                  <details>
                    <summary>View record references</summary>
                    <Reference name="Staff result" value={row.id} />
                    <Reference name="Accrual journal" value={row.accrualJournalId} />
                    <Reference name="Payment journal" value={row.paymentJournalId} />
                  </details>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  if (report.kind === "advanceReconciliation")
    return (
      <div
        className="pa-table-wrap"
        role="region"
        aria-label="Advance recovery reconciliation table, scroll horizontally for more columns"
        tabIndex={0}
      >
        <table className="pr-table pr-advance-table">
          <caption>
            Immutable schedules and current common-ledger evidence. The selected date controls due
            and overdue installments; it does not reconstruct a historical ledger snapshot.
          </caption>
          <thead>
            <tr>
              <th scope="col">Employee / advance</th>
              <th scope="col">Frozen schedule</th>
              <th scope="col">Current ledger</th>
              <th scope="col">Recovery position</th>
              <th scope="col">Reconciliation</th>
            </tr>
          </thead>
          <tbody>
            {report.data.items.map((row) => (
              <tr key={row.id}>
                <th scope="row">
                  <strong>{row.staffName}</strong>
                  <small>{row.employeeCode}</small>
                  <small>{row.advanceNumber} · {label(row.advanceType)}</small>
                  <small>Issued {row.issueDate}</small>
                </th>
                <td>
                  <dl className="pr-amounts">
                    <div><dt>Principal</dt><dd>{amount(row.principalAmount, row.currencyCode)}</dd></div>
                    <div><dt>Installments declared / found</dt><dd>{row.declaredInstallmentCount} / {row.actualInstallmentCount}</dd></div>
                    <div><dt>Schedule total</dt><dd>{amount(row.scheduledAmount, row.currencyCode)}</dd></div>
                    <div><dt>Due through selected date</dt><dd>{amount(row.scheduledDueAmount, row.currencyCode)}</dd></div>
                  </dl>
                </td>
                <td>
                  <small>Disbursement</small>
                  <Status value={row.disbursementStatus} />
                  <dl className="pr-amounts">
                    <div><dt>Posted issue</dt><dd>{amount(row.postedDisbursementAmount, row.currencyCode)}</dd></div>
                    <div><dt>Posted recovered</dt><dd>{amount(row.recoveredAmount, row.currencyCode)}</dd></div>
                    <div><dt>Outstanding</dt><dd>{amount(row.outstandingAmount, row.currencyCode)}</dd></div>
                  </dl>
                </td>
                <td>
                  <dl className="pr-amounts">
                    <div><dt>Overdue through date</dt><dd>{amount(row.overdueAmount, row.currencyCode)}</dd></div>
                    <div><dt>Pending payroll recovery</dt><dd>{row.pendingRecoveryCount} · {amount(row.pendingRecoveryAmount, row.currencyCode)}</dd></div>
                    <div><dt>Next unrecovered month</dt><dd>{row.nextUnrecoveredMonth ?? "Complete"}</dd></div>
                  </dl>
                </td>
                <td>
                  <Status value={row.reconciliationStatus} />
                  {row.issues.length > 0 ? (
                    <ul aria-label="Advance reconciliation issues">
                      {row.issues.map((issue) => <li key={issue}>{label(issue)}</li>)}
                    </ul>
                  ) : (
                    <small>No discrepancy detected in the checked evidence.</small>
                  )}
                  <details>
                    <summary>View statement reference</summary>
                    <Reference name="Advance" value={row.id} />
                    <small>Use the Advances tab to open the complete installment statement.</small>
                  </details>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  if (report.kind !== "reconciliation")
    return (
      <div
        className="pa-table-wrap"
        role="region"
        aria-label="Historical payroll summary table, scroll horizontally for more columns"
        tabIndex={0}
      >
        <table className="pr-table pr-summary-table">
          <caption>
            Immutable historical groups. Currency and finalized/reversed run states are never
            combined; missing classification evidence remains visible.
          </caption>
          <thead>
            <tr>
              <th scope="col">Historical group</th>
              <th scope="col">Evidence / run state</th>
              <th scope="col">Coverage</th>
              <th scope="col">Frozen payroll</th>
            </tr>
          </thead>
          <tbody>
            {report.data.items.map((row) => (
              <tr
                key={[
                  row.groupBy,
                  row.dimensionStatus,
                  row.dimensionCode,
                  row.dimensionLabel,
                  row.runStatus,
                  row.currencyCode,
                ].join(":")}
              >
                <th scope="row">
                  <strong>{row.dimensionLabel}</strong>
                  {row.dimensionCode && <small>{row.dimensionCode}</small>}
                  <small>{label(row.groupBy)}</small>
                </th>
                <td>
                  <small>Classification evidence</small>
                  <Status value={row.dimensionStatus} />
                  <small>Payroll run</small>
                  <Status value={row.runStatus} />
                  <small>{row.currencyCode}</small>
                </td>
                <td>
                  <dl className="pr-amounts">
                    <div><dt>Payroll runs</dt><dd>{row.payrollRunCount}</dd></div>
                    <div><dt>Staff results</dt><dd>{row.staffResultCount}</dd></div>
                  </dl>
                </td>
                <td>
                  <dl className="pr-amounts">
                    <div><dt>Gross</dt><dd>{row.gross === null ? "Not applicable" : amount(row.gross, row.currencyCode)}</dd></div>
                    <div><dt>Deductions</dt><dd>{amount(row.deductions, row.currencyCode)}</dd></div>
                    <div><dt>Employer contributions</dt><dd>{row.employerContributions === null ? "Not applicable" : amount(row.employerContributions, row.currencyCode)}</dd></div>
                    <div><dt>Net</dt><dd>{row.net === null ? "Not applicable" : amount(row.net, row.currencyCode)}</dd></div>
                  </dl>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  return (
    <div
      className="pa-table-wrap"
      role="region"
      aria-label="Reconciliation table, scroll horizontally for more columns"
      tabIndex={0}
    >
      <table className="pr-table">
        <caption>
          Whole-batch source, frozen payroll and common-ledger evidence. Matching evidence does not
          mean a pending journal is posted.
        </caption>
        <thead>
          <tr>
            <th scope="col">Batch</th>
            <th scope="col">Source evidence</th>
            <th scope="col">Journal evidence</th>
            <th scope="col">Reconciliation</th>
            <th scope="col">References</th>
          </tr>
        </thead>
        <tbody>
          {report.data.items.map((row) => (
            <tr key={row.id}>
              <th scope="row">
                <strong>{row.runCode}</strong>
                <small>
                  {label(row.postingType)} · {row.postingDate}
                </small>
                <small>{row.currencyCode}</small>
              </th>
              <td>
                <dl className="pr-amounts">
                  <div>
                    <dt>Declared sources / found</dt>
                    <dd>
                      {row.sourceCount} / {row.actualSourceCount}
                    </dd>
                  </div>
                  <div>
                    <dt>Declared amount</dt>
                    <dd>{amount(row.sourceAmount, row.currencyCode)}</dd>
                  </div>
                  <div>
                    <dt>Source item amount</dt>
                    <dd>{amount(row.actualSourceAmount, row.currencyCode)}</dd>
                  </div>
                  <div>
                    <dt>Frozen payroll</dt>
                    <dd>{amount(row.frozenPayrollAmount, row.currencyCode)}</dd>
                  </div>
                </dl>
              </td>
              <td>
                <Status value={row.journalStatus} />
                <dl className="pr-amounts">
                  <div>
                    <dt>Debit</dt>
                    <dd>{amount(row.journalDebit, row.currencyCode)}</dd>
                  </div>
                  <div>
                    <dt>Credit</dt>
                    <dd>{amount(row.journalCredit, row.currencyCode)}</dd>
                  </div>
                </dl>
                {row.reversalStatus && (
                  <>
                    <small>Reversal</small>
                    <Status value={row.reversalStatus} />
                  </>
                )}
              </td>
              <td>
                <Status value={row.reconciliationStatus} />
                {row.issues.length > 0 ? (
                  <ul aria-label="Reconciliation issues">
                    {row.issues.map((issue) => (
                      <li key={issue}>{label(issue)}</li>
                    ))}
                  </ul>
                ) : (
                  <small>No discrepancy detected in the checked evidence.</small>
                )}
              </td>
              <td>
                <details>
                  <summary>View record references</summary>
                  <Reference name="Batch" value={row.id} />
                  <Reference name="Journal" value={row.journalId} />
                  <Reference name="Reversal journal" value={row.reversalJournalId} />
                </details>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
