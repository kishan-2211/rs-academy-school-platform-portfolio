export type PayrollRecovery = {
  id: string;
  advanceId: string;
  advanceNumber: string;
  installmentId: string;
  dueMonth: string;
  amount: number;
  reservationStatus: "ACTIVE" | "RELEASED";
};

export function PayrollRecoveryEvidence({ recoveries, currency }: {
  recoveries: PayrollRecovery[];
  currency: string;
}) {
  if (recoveries.length === 0) return null;
  const money = new Intl.NumberFormat("en-IN", { style: "currency", currency });
  return (
    <details>
      <summary>{recoveries.length} advance recover{recoveries.length === 1 ? "y" : "ies"}</summary>
      <p>Included in frozen deductions. Repayment takes effect only when the matching payroll accrual is posted.</p>
      <ul>
        {recoveries.map((recovery) => (
          <li key={recovery.id}>
            <span>{recovery.advanceNumber} · {recovery.dueMonth.slice(0, 7)} · {recovery.reservationStatus === "RELEASED" ? "Released — historical evidence" : "Reserved for this payroll"}</span>
            <b>{money.format(recovery.amount)}</b>
          </li>
        ))}
      </ul>
    </details>
  );
}
