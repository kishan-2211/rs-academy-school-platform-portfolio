"use client";

import { useEffect, useRef, type FormEvent } from "react";
import {
  PAYMENT_METHODS,
  effectiveMapping,
  eligiblePaymentStaff,
  openAccrualYears,
  payrollLabel as label,
  payrollMoney as money,
  postingAccounts,
  schoolToday,
  type Account,
  type MappingDraft,
  type PayrollAccountingData,
  type PostingDraft,
} from "../../lib/payroll-accounting";

function EditorTitle({ children }: { children: React.ReactNode }) {
  const title = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    title.current?.focus();
  }, []);
  return (
    <h3 id="payroll-accounting-editor-title" ref={title} tabIndex={-1}>
      {children}
    </h3>
  );
}

function AccountField({
  title,
  accounts,
  value,
  onChange,
  optional = false,
}: {
  title: string;
  accounts: Account[];
  value: string;
  onChange: (value: string) => void;
  optional?: boolean;
}) {
  return (
    <label>
      {title}
      <select required={!optional} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">{optional ? "Not enabled" : "Choose account"}</option>
        {value && !accounts.some((account) => account.id === value) && (
          <option value={value} disabled>
            Previously selected account is unavailable — choose again
          </option>
        )}
        {accounts.map((account) => (
          <option key={account.id} value={account.id}>
            {account.code} · {account.displayName}
          </option>
        ))}
      </select>
    </label>
  );
}

export function PayrollMappingForm({
  data,
  draft,
  busy,
  onChange,
  onSubmit,
  onClose,
}: {
  data: PayrollAccountingData;
  draft: MappingDraft;
  busy: boolean;
  onChange: (draft: MappingDraft) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onClose: () => void;
}) {
  const year = data.financialYears.find((item) => item.id === draft.financialYearId);
  const expense = postingAccounts(data.accounts, "EXPENSE");
  const payable = postingAccounts(data.accounts, "PAYABLE");
  return (
    <form
      className="payroll-panel pa-editor"
      aria-labelledby="payroll-accounting-editor-title"
      onSubmit={onSubmit}
    >
      <header>
        <div>
          <span>Mapping revision {draft.revisionNumber}</span>
          <EditorTitle>
            {draft.id
              ? "Edit draft mapping"
              : draft.supersedesConfigurationId
                ? "Prepare mapping revision"
                : "Prepare payroll mapping"}
          </EditorTitle>
        </div>
        <button type="button" className="is-secondary" disabled={busy} onClick={onClose}>
          Close
        </button>
      </header>
      <fieldset disabled={busy} className="pa-form-body">
        <legend className="sr-only">Payroll posting configuration</legend>
        <p>
          {year?.displayName} · {year?.currencyCode}. This draft takes effect only after independent
          approval. Existing approved records are preserved.
        </p>
        <div className="pa-fields">
          <label>
            Effective from
            <input
              required
              type="date"
              min={year?.startsOn}
              max={year?.endsOn}
              value={draft.effectiveFrom}
              onChange={(event) => onChange({ ...draft, effectiveFrom: event.target.value })}
            />
          </label>
          <AccountField
            title="Net salary payable"
            accounts={payable}
            value={draft.salaryPayableAccountId}
            onChange={(value) => onChange({ ...draft, salaryPayableAccountId: value })}
          />
        </div>
        <fieldset>
          <legend>All active payroll components</legend>
          {data.components
            .filter((component) => component.status === "ACTIVE")
            .map((component) => {
              const mapping = draft.components[component.id] ?? {
                expenseAccountId: "",
                payableAccountId: "",
              };
              const update = (field: "expenseAccountId" | "payableAccountId", value: string) =>
                onChange({
                  ...draft,
                  components: {
                    ...draft.components,
                    [component.id]: { ...mapping, [field]: value },
                  },
                });
              return (
                <section
                  key={component.id}
                  className="pa-mapping-row"
                  aria-label={component.displayName}
                >
                  <div>
                    <b>{component.displayName}</b>
                    <small>
                      {component.code} · {label(component.componentKind)}
                    </small>
                  </div>
                  {component.componentKind !== "DEDUCTION" && (
                    <AccountField
                      title={component.displayName + " expense account"}
                      accounts={expense}
                      value={mapping.expenseAccountId}
                      onChange={(value) => update("expenseAccountId", value)}
                    />
                  )}
                  {component.componentKind !== "EARNING" && (
                    <AccountField
                      title={component.displayName + " payable account"}
                      accounts={payable}
                      value={mapping.payableAccountId}
                      onChange={(value) => update("payableAccountId", value)}
                    />
                  )}
                </section>
              );
            })}
        </fieldset>
        <fieldset>
          <legend>Payment methods — enable at least one</legend>
          <div className="pa-fields">
            {PAYMENT_METHODS.map((method) => (
              <AccountField
                key={method}
                title={label(method) + " account"}
                accounts={postingAccounts(data.accounts, method === "CASH" ? "CASH" : "BANK")}
                value={draft.tenders[method] ?? ""}
                optional
                onChange={(value) =>
                  onChange({ ...draft, tenders: { ...draft.tenders, [method]: value } })
                }
              />
            ))}
          </div>
        </fieldset>
        <label>
          Configuration reason
          <textarea
            required
            minLength={3}
            maxLength={500}
            value={draft.reason}
            onChange={(event) => onChange({ ...draft, reason: event.target.value })}
          />
        </label>
        <footer>
          <p>Account selection does not change salary calculations or initiate bank transfers.</p>
          <button
            type="submit"
            disabled={busy || !data.components.some((component) => component.status === "ACTIVE")}
          >
            {busy ? "Saving…" : "Save mapping draft"}
          </button>
        </footer>
      </fieldset>
    </form>
  );
}

export function PayrollPostingForm({
  data,
  draft,
  busy,
  onChange,
  onSubmit,
  onClose,
}: {
  data: PayrollAccountingData;
  draft: PostingDraft;
  busy: boolean;
  onChange: (draft: PostingDraft) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onClose: () => void;
}) {
  const run = data.runs.find((item) => item.id === draft.runId);
  if (!run)
    return (
      <p role="alert">
        The selected payroll run is no longer available. Close this form and refresh.
      </p>
    );
  const payment = draft.kind === "payments";
  const eligible = eligiblePaymentStaff(run);
  const mapping = effectiveMapping(data, draft.financialYearId, draft.postingDate);
  const selected = new Set(draft.staffIds);
  // Presentation only: the server recomputes exact decimal amounts from immutable payroll evidence.
  const selectedMinorUnits = eligible
    .filter((staff) => selected.has(staff.id))
    .reduce((sum, staff) => sum + Math.round(staff.net * 100), 0);
  const year = data.financialYears.find((item) => item.id === draft.financialYearId);
  const methods = mapping?.tenderMappings.map((tender) => tender.paymentMethod) ?? [];
  return (
    <form
      className="payroll-panel pa-editor"
      aria-labelledby="payroll-accounting-editor-title"
      onSubmit={onSubmit}
    >
      <header>
        <div>
          <span>
            {run.runCode} · {run.currencyCode}
          </span>
          <EditorTitle>
            {payment ? "Prepare salary payment evidence" : "Prepare payroll accrual"}
          </EditorTitle>
        </div>
        <button disabled={busy} type="button" className="is-secondary" onClick={onClose}>
          Close
        </button>
      </header>
      <fieldset disabled={busy} className="pa-form-body">
        <legend className="sr-only">Posting preparation</legend>
        <p id="pa-posting-safety">
          This prepares a journal for independent approval. It does not transfer money. A payment
          remains pending until its journal is posted in Accounting.
        </p>
        <div className="pa-fields">
          <label>
            Financial year
            <select
              required
              value={draft.financialYearId}
              onChange={(event) => onChange({ ...draft, financialYearId: event.target.value })}
            >
              <option value="">Choose open accrual year</option>
              {openAccrualYears(data)
                .filter((item) => item.currencyCode === run.currencyCode)
                .map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.displayName}
                  </option>
                ))}
            </select>
          </label>
          <label>
            {payment ? "Payment posting date" : "Accrual date (payroll period end)"}
            <input
              type="date"
              required
              readOnly={!payment}
              value={draft.postingDate}
              min={payment ? [run.periodEnd, year?.startsOn ?? ""].sort().at(-1) : run.periodEnd}
              max={year ? [year.endsOn, schoolToday()].sort()[0] : schoolToday()}
              onChange={(event) => onChange({ ...draft, postingDate: event.target.value })}
            />
          </label>
        </div>
        <p role="status">
          {mapping
            ? "Effective mapping: revision " +
              mapping.revisionNumber +
              " from " +
              mapping.effectiveFrom
            : "No approved mapping is effective for this financial year and date."}
        </p>
        {payment ? (
          <>
            <div className="pa-fields">
              <label>
                Payment method
                <select
                  required
                  value={draft.paymentMethod}
                  onChange={(event) =>
                    onChange({
                      ...draft,
                      paymentMethod: event.target.value as PostingDraft["paymentMethod"],
                    })
                  }
                >
                  {!methods.includes(draft.paymentMethod) && (
                    <option value={draft.paymentMethod} disabled>
                      Choose an enabled method
                    </option>
                  )}
                  {methods.map((method) => (
                    <option key={method} value={method}>
                      {label(method)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Payment reference{draft.paymentMethod === "CASH" ? " (optional)" : " (required)"}
                <input
                  maxLength={120}
                  required={draft.paymentMethod !== "CASH"}
                  value={draft.paymentReference}
                  onChange={(event) => onChange({ ...draft, paymentReference: event.target.value })}
                />
              </label>
            </div>
            <fieldset>
              <legend>Unpaid staff with a positive net salary</legend>
              <label className="pa-check">
                <input
                  type="checkbox"
                  checked={eligible.length > 0 && eligible.every((staff) => selected.has(staff.id))}
                  disabled={!eligible.length}
                  onChange={(event) =>
                    onChange({
                      ...draft,
                      staffIds: event.target.checked ? eligible.map((staff) => staff.id) : [],
                    })
                  }
                />
                Select all eligible staff ({eligible.length})
              </label>
              <div className="pa-staff-list">
                {run.staff.map((staff) => (
                  <label className="pa-check pa-staff" key={staff.id}>
                    <input
                      type="checkbox"
                      checked={selected.has(staff.id)}
                      disabled={!eligible.some((item) => item.id === staff.id)}
                      onChange={(event) =>
                        onChange({
                          ...draft,
                          staffIds: event.target.checked
                            ? [...draft.staffIds, staff.id]
                            : draft.staffIds.filter((id) => id !== staff.id),
                        })
                      }
                    />
                    <span>
                      <b>{staff.staffName}</b>
                      <small>
                        {staff.employeeCode} · {label(staff.paymentStatus)}
                      </small>
                    </span>
                    <strong>{money(staff.net, run.currencyCode)}</strong>
                  </label>
                ))}
              </div>
            </fieldset>
            <p role="status">
              {eligible.filter((staff) => selected.has(staff.id)).length} staff selected · Net
              salary {money(selectedMinorUnits / 100, run.currencyCode)}. Only whole, unpaid staff
              salaries can be included.
            </p>
          </>
        ) : (
          <dl className="pa-totals">
            <div>
              <dt>Staff</dt>
              <dd>{run.staffCount}</dd>
            </div>
            <div>
              <dt>Gross earnings</dt>
              <dd>{money(run.payableGross, run.currencyCode)}</dd>
            </div>
            <div>
              <dt>Employer contributions</dt>
              <dd>{money(run.employerContributions, run.currencyCode)}</dd>
            </div>
            <div>
              <dt>Deductions</dt>
              <dd>{money(run.deductions, run.currencyCode)}</dd>
            </div>
            <div>
              <dt>Net salary payable</dt>
              <dd>{money(run.net, run.currencyCode)}</dd>
            </div>
          </dl>
        )}
        <label>
          Preparation reason
          <textarea
            required
            minLength={3}
            maxLength={500}
            value={draft.reason}
            onChange={(event) => onChange({ ...draft, reason: event.target.value })}
          />
        </label>
        <footer>
          <p>
            All amounts and permissions are rechecked by the server. The accounting period must
            still be open.
          </p>
          <button
            type="submit"
            aria-describedby="pa-posting-safety"
            disabled={busy || !mapping || (payment && !draft.staffIds.length)}
          >
            {busy ? "Preparing…" : "Prepare for independent approval"}
          </button>
        </footer>
      </fieldset>
    </form>
  );
}
