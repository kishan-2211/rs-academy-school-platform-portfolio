"use client";

import type { AdvanceStatement } from "../../lib/payroll-advance-statements";
import { payrollLabel as label, payrollMoney as money } from "../../lib/payroll-accounting";

export function PayrollAdvanceStatementEvidence({ statement }: { statement: AdvanceStatement }) {
  const unknown = statement.evidenceStatus === "EVIDENCE_MISMATCH";
  return (
    <section className="payroll-panel pa-statement" aria-labelledby="advance-statement-heading">
      <header className="pa-introduction">
        <div>
          <p className="eyebrow">Protected employee record</p>
          <h3 id="advance-statement-heading">
            Recovery statement · {statement.advanceNumber}
          </h3>
          <p>
            {statement.staffName} · {statement.employeeCode} · generated{" "}
            <time dateTime={statement.generatedAt}>{statement.generatedAt}</time>
          </p>
        </div>
        <span className="pa-status">{label(statement.evidenceStatus)}</span>
      </header>
      {unknown && (
        <div className="pr-error" role="alert">
          Evidence does not reconcile. Aggregate recovered and outstanding amounts are withheld;
          inspect the retained attempts and journals before acting.
        </div>
      )}
      <dl className="pa-totals">
        <div>
          <dt>Principal</dt>
          <dd>{money(statement.principalAmount, statement.currencyCode)}</dd>
        </div>
        <div>
          <dt>Posted disbursement</dt>
          <dd>
            {statement.postedDisbursementAmount === null
              ? "Withheld — verify evidence"
              : money(statement.postedDisbursementAmount, statement.currencyCode)}
          </dd>
        </div>
        <div>
          <dt>Ledger-confirmed recovered</dt>
          <dd>
            {statement.recoveredAmount === null
              ? "Withheld — verify evidence"
              : money(statement.recoveredAmount, statement.currencyCode)}
          </dd>
        </div>
        <div>
          <dt>Outstanding</dt>
          <dd>
            {statement.outstandingAmount === null
              ? "Unavailable until disbursed and reconciled"
              : money(statement.outstandingAmount, statement.currencyCode)}
          </dd>
        </div>
      </dl>
      <p className="pr-filter-note">
        A reservation is not repayment. Recovered amounts require matching posted payroll accrual
        evidence; released and reversed attempts remain in this history.
      </p>
      <div
        className="pa-table-wrap"
        tabIndex={0}
        role="region"
        aria-label={"Installment and ledger history for " + statement.advanceNumber}
      >
        <table>
          <caption>Complete approved installment schedule and recovery evidence</caption>
          <thead>
            <tr>
              <th scope="col">Installment</th>
              <th scope="col">Due month</th>
              <th scope="col">Planned</th>
              <th scope="col">Recovered</th>
              <th scope="col">Outstanding</th>
              <th scope="col">Evidence</th>
            </tr>
          </thead>
          <tbody>
            {statement.installments.map((installment) => (
              <tr key={installment.id}>
                <th scope="row">{installment.installmentNumber}</th>
                <td>{installment.dueMonth.slice(0, 7)}</td>
                <td>{money(installment.plannedAmount, statement.currencyCode)}</td>
                <td>{money(installment.recoveredAmount, statement.currencyCode)}</td>
                <td>{money(installment.outstandingAmount, statement.currencyCode)}</td>
                <td>
                  <strong>{label(installment.status)}</strong>
                  {!installment.recoveryAttempts.length ? (
                    <span>No payroll attempt</span>
                  ) : (
                    installment.recoveryAttempts.map((attempt) => (
                      <details key={attempt.id}>
                        <summary>
                          {attempt.runCode} · {label(attempt.status)}
                        </summary>
                        <span>
                          Payroll month {attempt.payrollMonth.slice(0, 7)} · run{" "}
                          {label(attempt.payrollRunStatus)}
                        </span>
                        {attempt.releasedAt && (
                          <span>
                            Released <time dateTime={attempt.releasedAt}>{attempt.releasedAt}</time>
                            {attempt.releasedBy ? " by " + attempt.releasedBy : ""}: {attempt.releaseReason}
                          </span>
                        )}
                        {!attempt.ledgerPostings.length ? (
                          <span>No accrual journal prepared.</span>
                        ) : (
                          attempt.ledgerPostings.map((posting) => (
                            <span key={posting.batchId}>
                              Journal <code>{posting.accountingJournalId ?? "Unavailable"}</code> ·{" "}
                              {label(posting.evidenceStatus)}
                              {posting.reversalJournalId
                                ? " · reversal " + posting.reversalJournalId
                                : ""}
                            </span>
                          ))
                        )}
                      </details>
                    ))
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
