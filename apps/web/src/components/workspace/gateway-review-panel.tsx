"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { eligibleReviewDate, gatewayReviewQuery } from "@/lib/gateway-review";

type Capture = {
  id: string; attemptId?: string; learnerId?: string; learnerName?: string; admissionNumber?: string;
  amount: number; currencyCode: string; paidAt: string; originalBusinessDate?: string | null;
  classification: string; attemptStatus?: string; status: string; holdReason?: string;
  closedDayCloseId?: string; paymentId?: string; receiptNumber?: string; postedOn?: string;
  earliestOpenDate?: string; canReviewedPost: boolean;
};
type Queue = {
  schoolId: string; schoolToday: string; latestApprovedCloseDate?: string;
  items: Capture[]; page: number; pageSize: number; total: number; hasMore: boolean;
};
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

async function responseProblem(response: Response, fallback: string, t: (source: string) => string) {
  const messages: Record<string, string> = {
    ACCESS_DENIED: "Financial access is no longer available for this school.",
    FINANCE_SCOPE_DENIED: "Financial access is no longer available for this school.",
    GATEWAY_CAPTURE_NOT_FOUND: "This payment evidence is no longer available in this school.",
    GATEWAY_CAPTURE_REVIEW_MISMATCH: "The payment evidence is not eligible for posting. Refresh and review the mismatch.",
    GATEWAY_POSTING_DATE_CLOSED: "This date is closed or ineligible. Choose another open date after review.",
    GATEWAY_REVIEW_NOT_ELIGIBLE: "The payment evidence is not eligible for posting. Refresh and review the mismatch.",
  };
  try { const problem = await response.json() as { code?: string }; return t(problem.code && messages[problem.code] || fallback); }
  catch { return t(fallback); }
}
async function csrf() {
  const read = () => document.cookie.split("; ").find((part) => part.startsWith("educore_csrf="))?.slice(13);
  let token = read();
  if (!token) {
    await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include", cache: "no-store" });
    token = read();
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}

export function GatewayReviewPanel({ schoolId, onPosted }: { schoolId: string; onPosted: () => Promise<void> }) {
  const t = useT("fees");
  const { locale } = useLanguage();
  const [query, setQuery] = useState({ q: "", status: "HOLD", page: 0 });
  const [queue, setQueue] = useState<Queue | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [revision, setRevision] = useState(0);
  const lifecycle = useRef(0);
  useEffect(() => { lifecycle.current += 1; return () => { lifecycle.current += 1; }; }, [schoolId]);
  const money = (capture: Capture) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", {
    style: "currency", currency: capture.currencyCode,
  }).format(capture.amount);
  const timestamp = (value: string) => new Date(value).toLocaleString(locale === "hi" ? "hi-IN" : "en-IN", {
    dateStyle: "medium", timeStyle: "short",
  });

  useEffect(() => {
    let active = true;
    let knownFailure = "";
    const controller = new AbortController();
    Promise.resolve().then(async () => {
      if (!active) return;
      setLoading(true); setQueue(null); setError("");
      const response = await fetch(`${apiUrl}/api/v1/finance/gateway/captures?${gatewayReviewQuery(schoolId, query.q, query.status, query.page)}`, {
        credentials: "include", cache: "no-store", signal: controller.signal,
      });
      if (!response.ok) {
        knownFailure = await responseProblem(response, "The gateway review queue could not be opened.", t);
        throw new Error(knownFailure);
      }
      const result = await response.json() as Queue;
      if (result.schoolId !== schoolId) throw new Error(t("The gateway review queue could not be opened."));
      if (active) setQueue(result);
    }).catch(() => {
      if (active) setError(knownFailure || t("The gateway review queue could not be opened."));
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [schoolId, query, revision, t]);

  const refresh = useCallback(() => setRevision((current) => current + 1), []);
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setQuery({ q: String(form.get("q") || "").trim(), status: String(form.get("status") || "HOLD"), page: 0 });
    setNotice("");
  }
  async function post(event: FormEvent<HTMLFormElement>, capture: Capture) {
    event.preventDefault();
    if (!queue || busyId || !capture.canReviewedPost) return;
    const form = new FormData(event.currentTarget);
    const openDate = String(form.get("openDate") || "");
    const reason = String(form.get("reason") || "").trim();
    if (!eligibleReviewDate(openDate, capture.originalBusinessDate || "", queue.schoolToday, queue.latestApprovedCloseDate)
        || reason.length < 3 || reason.length > 240) {
      setError(t("Choose an eligible open date and enter the review reason.")); return;
    }
    if (!window.confirm(t("Post {amount} on {date}? The original gateway time and closed day will remain unchanged.", {
      amount: money(capture), date: openDate,
    }))) return;
    const submissionLifecycle = lifecycle.current;
    const current = () => submissionLifecycle === lifecycle.current;
    let dispatched = false;
    let knownFailure = "";
    setBusyId(capture.id); setError(""); setNotice("");
    try {
      const token = await csrf().catch(() => { throw new Error(t("The secure form token is unavailable. Refresh and try again.")); });
      if (!current()) return;
      dispatched = true;
      const response = await fetch(`${apiUrl}/api/v1/finance/gateway/captures/${encodeURIComponent(capture.id)}/posting`, {
        method: "POST", credentials: "include", cache: "no-store",
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
        body: JSON.stringify({ schoolId, openDate, reason }),
      });
      if (!response.ok) {
        knownFailure = await responseProblem(response, "The reviewed payment could not be posted.", t);
        throw new Error(knownFailure);
      }
      const result = await response.json() as { status: string; receiptNumber?: string };
      if (result.status !== "POSTED" || !result.receiptNumber) throw new Error(t("The posting response is uncertain. Refresh before retrying."));
      if (!current()) return;
      // A failed refresh must not tell the reviewer that a completed payment failed.
      setNotice(t("Payment posted. Receipt {receipt} is available in the learner account.", { receipt: result.receiptNumber }));
      refresh();
      try { await onPosted(); } catch { if (current()) setError(t("The payment was posted, but account details could not be refreshed.")); }
    } catch (reason: unknown) {
      if (current()) setError(dispatched ? knownFailure || t("The posting response is uncertain. Refresh before retrying.")
        : reason instanceof Error ? reason.message : t("The secure form token is unavailable. Refresh and try again."));
    } finally { if (current()) setBusyId(""); }
  }

  return <section className="gateway-review" aria-labelledby="gateway-review-title">
    <header><span>{t("Online payment control")}</span><h2 id="gateway-review-title">{t("Review received payments without changing closed days.")}</h2>
      <p>{t("Only independently verified gateway captures appear here. Posting creates the existing numbered school receipt; it does not repeat the gateway payment.")}</p>
    </header>
    <form className="gateway-review__filters" onSubmit={search} aria-label={t("Search gateway captures")}>
      <label>{t("Learner or receipt")}<input name="q" type="search" maxLength={100} defaultValue={query.q} /></label>
      <label>{t("Status")}<select name="status" defaultValue={query.status}>
        <option value="HOLD">{t("Held for review")}</option><option value="POSTED">{t("Posted")}</option>
        <option value="REFUND_REQUIRED">{t("Refund review required")}</option><option value="UNRESOLVED">{t("Unresolved")}</option>
        <option value="ALL">{t("All captures")}</option>
      </select></label>
      <button type="submit" disabled={Boolean(busyId)}>{t("Search")}</button>
      <button type="button" onClick={refresh} disabled={loading || Boolean(busyId)}>{t("Refresh")}</button>
    </form>
    {error && <div className="gateway-review__alert gateway-review__alert--error" role="alert"><p>{error}</p><button type="button" onClick={refresh} disabled={Boolean(busyId)}>{t("Try again")}</button></div>}
    {notice && <p className="gateway-review__alert gateway-review__alert--success" role="status">{notice}</p>}
    <div aria-busy={loading}>
      {loading && <p role="status" className="gateway-review__empty">{t("Loading verified payment evidence…")}</p>}
      {queue && <>
        <p className="gateway-review__summary">{t("{count} captures · School date {date}", { count: queue.total, date: queue.schoolToday })}
          {queue.latestApprovedCloseDate && <> · {t("Closed through {date}", { date: queue.latestApprovedCloseDate })}</>}</p>
        {queue.items.length ? <div className="gateway-review__list">{queue.items.map((capture) => <article key={capture.id}>
          <header><div><h3>{capture.learnerName || t("Unmatched payment — restricted review")}</h3>
            <p>{capture.admissionNumber || t("Admission number unavailable")}</p></div>
            <strong>{money(capture)}</strong><span className={`gateway-review__badge ${capture.status === "POSTED" ? "gateway-review__badge--posted" : ""}`}>
              {t(capture.status === "POSTED" ? "Posted" : capture.status === "HOLD" ? "Held for review" : capture.status === "REFUND_REQUIRED" ? "Refund review required" : "Unresolved")}
            </span></header>
          <dl><div><dt>{t("Gateway payment time")}</dt><dd><time dateTime={capture.paidAt}>{timestamp(capture.paidAt)}</time></dd></div>
            <div><dt>{t("Original school business date")}</dt><dd>{capture.originalBusinessDate || t("Unmatched payment")}</dd></div>
            {capture.closedDayCloseId && <div><dt>{t("Preserved closed-day evidence")}</dt><dd className="gateway-review__reference">{capture.closedDayCloseId}</dd></div>}
            {capture.receiptNumber && <div><dt>{t("School receipt")}</dt><dd>{capture.receiptNumber} · {capture.postedOn}</dd></div>}
          </dl>
          {capture.holdReason && <p className="gateway-review__hold">{capture.holdReason}</p>}
          {capture.canReviewedPost ? <form onSubmit={(event) => void post(event, capture)} className="gateway-review__posting" aria-label={t("Reviewed posting for {name}", { name: capture.learnerName || t("Unmatched payment") })}>
            <label>{t("Eligible open posting date")}<input name="openDate" type="date" min={capture.earliestOpenDate || capture.originalBusinessDate || undefined} max={queue.schoolToday} defaultValue={queue.schoolToday} required disabled={Boolean(busyId)} /></label>
            <label>{t("Review reason")}<textarea name="reason" minLength={3} maxLength={240} required disabled={Boolean(busyId)} /></label>
            <button disabled={Boolean(busyId)}>{t(busyId === capture.id ? "Posting…" : "Review and post receipt")}</button>
            <p>{t("The server checks the evidence, permissions, reservations and open date again before posting. No closed record is reopened.")}</p>
          </form> : capture.status !== "POSTED" && <p className="gateway-review__restricted">{t("Posting is not currently eligible. Resolve the evidence or refund issue with an authorized finance reviewer; do not collect this payment again.")}</p>}
        </article>)}</div> : <div className="gateway-review__empty"><h3>{t("No captures match these filters")}</h3><p>{t("Verified online payments will appear here when the approved gateway processing is configured. This is not evidence that online checkout is active.")}</p></div>}
        <nav className="gateway-review__pagination" aria-label={t("Gateway capture pages")}>
          <button type="button" disabled={queue.page === 0 || Boolean(busyId)} onClick={() => setQuery((current) => ({ ...current, page: current.page - 1 }))}>{t("Previous")}</button>
          <span>{t("Page {page}", { page: queue.page + 1 })}</span>
          <button type="button" disabled={!queue.hasMore || Boolean(busyId)} onClick={() => setQuery((current) => ({ ...current, page: current.page + 1 }))}>{t("Next")}</button>
        </nav>
      </>}
    </div>
  </section>;
}
