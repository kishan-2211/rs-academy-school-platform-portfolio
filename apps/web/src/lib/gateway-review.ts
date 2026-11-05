/** Browser form assistance only; the server rechecks all financial authority and evidence. */
export function eligibleReviewDate(value: string, originalDate: string, schoolToday: string, closedBusinessDate?: string | null) {
  const date = /^\d{4}-\d{2}-\d{2}$/;
  if (![value, originalDate, schoolToday].every((item) => date.test(item))) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) return false;
  return value >= originalDate && value <= schoolToday && (!closedBusinessDate || value > closedBusinessDate);
}

export function manualReceiptCanReverse(payment: { status: string; paymentMethod: string }) {
  return payment.status === "POSTED" && payment.paymentMethod !== "ONLINE_PROVIDER";
}

/** A provider row is configuration evidence, not proof that checkout or settlement is operational. */
export function gatewayConfigurationLabel(status: string) {
  if (status === "NOT_CONFIGURED") return "Not configured";
  if (status === "TEST_REDIRECT") return "Test configuration only";
  if (status === "LIVE_REDIRECT") return "Live configuration only";
  return "Unavailable";
}

export function gatewayReviewQuery(schoolId: string, q: string, status: string, page: number, pageSize = 25) {
  return new URLSearchParams({ schoolId, q: q.trim().slice(0, 100), status, page: String(page), pageSize: String(pageSize) });
}
