"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Check, Shield } from "@/components/icons";
import { MoneyIcon } from "@/components/workspace/workspace-icons";

type Batch = { id: string; payrollRunId: string; runCode: string; payrollMonth: string; locale: "en-IN" | "hi-IN"; runStatus: string; totalCount: number; generationReason: string; generatedBy: string; generatedAt: string };
type Payslip = { id: string; batchId: string; payrollRunId: string; staffProfileId: string; payslipNumber: string; employeeCode: string; staffName: string; payrollMonth: string; currencyCode: string; runStatus: string; reversedAt?: string; reversalReason?: string; scheduledWorkingDays: number; recordedDays: number; payableDays: number; payableGross: number; deductions: number; employerContributions: number; net: number; generatedAt: string; pdfSha256: string };
type PayslipWorkspace = { schoolId: string; schoolName: string; selfStaffProfileId: string | null; canReadAll: boolean; canGenerate: boolean; batches: Batch[]; payslips: Payslip[] };
type Run = { id: string; runCode: string; payrollMonth: string; status: string; staffCount: number; net: number; currencyCode: string };
type RunWorkspace = { runs: Run[] };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const cookie = (name: string) => document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new Error("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string) { try { return ((await response.json()) as { detail?: string }).detail || fallback; } catch { return fallback; } }
const money = (amount: number, currency = "INR") => new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: 2 }).format(amount);
const month = (value: string) => new Intl.DateTimeFormat("en-IN", { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(`${value}T00:00:00Z`));

export function PayrollPayslipWorkspace({ schoolId, selfService = false }: { schoolId?: string; selfService?: boolean }) {
  const [data, setData] = useState<PayslipWorkspace | null>(null);
  const [runs, setRuns] = useState<Run[]>([]);
  const [selectedRun, setSelectedRun] = useState("");
  const [locale, setLocale] = useState<"en-IN" | "hi-IN">("en-IN");
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const query = schoolId ? `?schoolId=${encodeURIComponent(schoolId)}` : "";
  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/payroll/payslips${query}`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "Private payslips are unavailable."));
    const next = await response.json() as PayslipWorkspace;
    setData(next);
    if (next.canReadAll) {
      const runResponse = await fetch(`${apiUrl}/api/v1/payroll/runs?schoolId=${encodeURIComponent(next.schoolId)}`, { credentials: "include" });
      if (!runResponse.ok) throw new Error(await problem(runResponse, "Finalized payroll runs are unavailable."));
      const runWorkspace = await runResponse.json() as RunWorkspace;
      setRuns(runWorkspace.runs.filter((run) => run.status === "FINALIZED"));
    }
    return next;
  }, [query]);

  useEffect(() => {
    let active = true;
    Promise.resolve().then(load).catch((cause: unknown) => { if (active) setError(cause instanceof Error ? cause.message : "Private payslips are unavailable."); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [load]);

  const generatedRuns = useMemo(() => new Set(data?.batches.map((batch) => batch.payrollRunId) ?? []), [data]);
  const availableRuns = runs.filter((run) => !generatedRuns.has(run.id));

  async function generate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data || !selectedRun) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiUrl}/api/v1/payroll/runs/${encodeURIComponent(selectedRun)}/payslips`, {
        method: "POST", credentials: "include",
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken(), "Idempotency-Key": globalThis.crypto?.randomUUID?.() || `payslip-${selectedRun}-${Date.now()}` },
        body: JSON.stringify({ schoolId: data.schoolId, locale, reason }),
      });
      if (!response.ok) throw new Error(await problem(response, "The private payslips could not be generated."));
      const result = await response.json() as { generatedCount: number; existing: boolean };
      await load(); setSelectedRun(""); setReason("");
      setNotice(result.existing ? "The matching protected payslip batch already existed." : `${result.generatedCount} private payslip${result.generatedCount === 1 ? "" : "s"} generated from finalized evidence.`);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "The private payslips could not be generated."); }
    finally { setBusy(false); }
  }

  async function download(payslip: Payslip) {
    if (!data) return;
    setBusy(true); setError("");
    try {
      const response = await fetch(`${apiUrl}/api/v1/payroll/payslips/${encodeURIComponent(payslip.id)}/download?schoolId=${encodeURIComponent(data.schoolId)}`, { credentials: "include" });
      if (!response.ok) throw new Error(await problem(response, "The private payslip could not be downloaded."));
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url; anchor.download = `payslip-${payslip.payslipNumber}.pdf`; anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "The private payslip could not be downloaded."); }
    finally { setBusy(false); }
  }

  if (loading) return <section className="payroll-panel payroll-run-state" aria-live="polite"><span /><p>Opening private payslips…</p></section>;
  if (!data) return <section className="payroll-panel payroll-run-state" role="alert"><MoneyIcon /><h2>Private payslips are unavailable</h2><p>{error}</p><button type="button" onClick={() => window.location.reload()}>Try again</button></section>;

  return <section className={`payroll-payslip-layout${selfService ? " is-self-service" : ""}`}>
    {error && <div className="payroll-banner is-error" role="alert"><span>{error}</span><button type="button" aria-label="Dismiss payslip error" onClick={() => setError("")}>×</button></div>}
    {notice && <div className="payroll-banner is-success" role="status"><span><Check /> {notice}</span><button type="button" aria-label="Dismiss payslip notice" onClick={() => setNotice("")}>×</button></div>}

    {data.canGenerate && <form className="payroll-panel payroll-payslip-generate" onSubmit={generate}>
      <header><div><span>Finalized evidence only</span><h2>Generate private payslip batch</h2></div><Shield /></header>
      <div>
        <label>Finalized payroll run<select required value={selectedRun} onChange={(event) => setSelectedRun(event.target.value)}><option value="">Choose a finalized run</option>{availableRuns.map((run) => <option value={run.id} key={run.id}>{run.runCode} · {month(run.payrollMonth)} · {run.staffCount} staff · {money(run.net, run.currencyCode)}</option>)}</select></label>
        <label>Payslip language<select value={locale} onChange={(event) => setLocale(event.target.value as "en-IN" | "hi-IN")}><option value="en-IN">English</option><option value="hi-IN">Hindi / हिन्दी</option></select></label>
        <label className="is-wide">Generation reason<textarea required minLength={3} maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} /></label>
      </div>
      <footer><small>One complete immutable batch is allowed per finalized run. Salary details remain private and have no public verification link.</small><button disabled={busy || !selectedRun || reason.trim().length < 3 || availableRuns.length === 0} type="submit">Generate protected batch</button></footer>
    </form>}

    {data.canGenerate && availableRuns.length === 0 && <p className="payroll-payslip-callout"><Check /> Every finalized payroll run currently has a protected payslip batch, or no run has been finalized yet.</p>}

    <article className="payroll-panel payroll-payslip-list">
      <header><div><span>{selfService ? "Your private records" : "Authenticated delivery"}</span><h2>{selfService ? "My payslips" : "Generated payslips"}</h2></div><strong>{data.payslips.length}</strong></header>
      {data.payslips.length ? <div>{data.payslips.map((payslip) => <article key={payslip.id}>
        <div><span><b>{month(payslip.payrollMonth)}</b>{payslip.runStatus === "REVERSED" && <em className="is-reversed">Reversed</em>}</span><small>{selfService ? payslip.payslipNumber : `${payslip.staffName} · ${payslip.employeeCode}`}</small>{payslip.runStatus === "REVERSED" && <small className="payroll-reversal-note">{selfService ? "This record was invalidated. Await the replacement payslip." : `Archived original evidence · ${payslip.reversalReason || "approved payroll reversal"}`}</small>}</div>
        <span><small>Payable days</small><b>{payslip.payableDays} / {payslip.scheduledWorkingDays}</b></span>
        <span><small>Deductions</small><b>{money(payslip.deductions, payslip.currencyCode)}</b></span>
        <span><small>Net salary</small><strong>{money(payslip.net, payslip.currencyCode)}</strong></span>
        <button type="button" disabled={busy || (selfService && payslip.runStatus === "REVERSED")} onClick={() => void download(payslip)} aria-label={`${payslip.runStatus === "REVERSED" ? selfService ? "Reversed payslip unavailable" : "Download archived evidence" : "Download"} ${payslip.payslipNumber} for ${month(payslip.payrollMonth)}`}>{payslip.runStatus === "REVERSED" ? selfService ? "Reversed" : "Archived PDF" : "Download PDF"}</button>
      </article>)}</div> : <p className="payroll-empty">{selfService ? "No finalized payslip has been issued to your linked staff profile yet." : "No private payslips have been generated from finalized payroll runs."}</p>}
    </article>

    {data.canReadAll && <article className="payroll-panel payroll-payslip-batches">
      <header><div><span>Append-only evidence</span><h2>Generation history</h2></div><strong>{data.batches.length}</strong></header>
      {data.batches.length ? <ol>{data.batches.map((batch) => <li key={batch.id}><div><span><b>{batch.runCode}</b>{batch.runStatus === "REVERSED" && <em className="is-reversed">Reversed</em>}</span><small>{month(batch.payrollMonth)} · {batch.locale === "hi-IN" ? "Hindi" : "English"} · {batch.totalCount} payslip{batch.totalCount === 1 ? "" : "s"}</small></div><p>{batch.generationReason}</p><small>{batch.generatedBy} · {new Date(batch.generatedAt).toLocaleString("en-IN")}</small></li>)}</ol> : <p className="payroll-empty">Generation history will appear after the first finalized run is issued.</p>}
    </article>}
  </section>;
}
