"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Check, Shield } from "@/components/icons";
import { MoneyIcon, PeopleIcon, PlusIcon } from "@/components/workspace/workspace-icons";
import { PayrollRunWorkspace } from "@/components/workspace/payroll-run-workspace";
import { PayrollPayslipWorkspace } from "@/components/workspace/payroll-payslip-workspace";
import { PayrollAccountingWorkspace } from "@/components/workspace/payroll-accounting-workspace";
import { PayrollReportWorkspace } from "@/components/workspace/payroll-report-workspace";
import { PayrollAdvanceWorkspace } from "@/components/workspace/payroll-advance-workspace";

type Staff = { id: string; employeeCode: string; displayName: string; joinedOn: string; endedOn?: string; status: string; designation?: string; department?: string };
type Component = { id: string; code: string; displayName: string; componentKind: string; calculationMethod: string; basic: boolean; attendanceProrated: boolean; statutory: boolean; description?: string; displayOrder: number; status: string; rowVersion: number };
type SalaryLine = { id: string; payrollComponentId: string; componentName: string; calculationMethod: string; fixedAmount?: number; percentageRate?: number; estimatedAmount: number };
type History = { id: string; eventType: string; reason: string; actorName: string; occurredAt: string };
type SalaryProfile = { id: string; staffProfileId: string; supersedesProfileId?: string; revisionNumber: number; effectiveFrom: string; currencyCode: string; status: string; creationReason: string; creatorName: string; estimatedGross: number; estimatedDeductions: number; estimatedNet: number; rowVersion: number; lines: SalaryLine[]; history: History[] };
type Snapshot = { schoolId: string; schoolName: string; canConfigure: boolean; canApprove: boolean; staff: Staff[]; components: Component[]; salaryProfiles: SalaryProfile[] };
type LineDraft = { included: boolean; value: string };
type Editor = { editingId?: string; expectedVersion?: number; staffId: string; supersedesId?: string; revision: number; effectiveFrom: string; currency: string; reason: string; lines: Record<string, LineDraft> };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const localToday = () => { const date = new Date(); date.setMinutes(date.getMinutes() - date.getTimezoneOffset()); return date.toISOString().slice(0, 10); };
const nextDay = (value: string) => { const date = new Date(`${value}T00:00:00Z`); date.setUTCDate(date.getUTCDate() + 1); return date.toISOString().slice(0, 10); };
const label = (value?: string) => value ? value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase()) : "Not set";
const cookie = (name: string) => document.cookie.split("; ").find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
async function csrfToken() { let token = cookie("educore_csrf"); if (!token) { await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }); token = cookie("educore_csrf"); } if (!token) throw new Error("The secure form token is unavailable. Refresh and try again."); return decodeURIComponent(token); }
async function problem(response: Response, fallback: string) { try { return ((await response.json()) as { detail?: string }).detail || fallback; } catch { return fallback; } }

export function PayrollWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<"profiles" | "components" | "runs" | "payslips" | "accounting" | "reports" | "advances">("profiles");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editingComponent, setEditingComponent] = useState<Component | null>(null);
  const [editor, setEditor] = useState<Editor | null>(null);

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/payroll`, { credentials: "include" });
    if (!response.ok) throw new Error(await problem(response, "Payroll configuration is unavailable."));
    const result = await response.json() as Snapshot;
    setData(result);
    return result;
  }, []);

  useEffect(() => {
    let cancelled = false;
    Promise.resolve().then(load)
      .catch((reason: unknown) => { if (!cancelled) setError(reason instanceof Error ? reason.message : "Payroll configuration is unavailable."); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [load]);

  const mutate = useCallback(async (path: string, method: "POST" | "PUT", body: object, success: string) => {
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiUrl}/api/v1/payroll${path}`, { method, credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken() }, body: JSON.stringify(body) });
      if (!response.ok) throw new Error(await problem(response, "The payroll change could not be saved."));
      const result = await response.json() as { id: string; rowVersion: number };
      await load(); setNotice(success); return result;
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The payroll change could not be saved."); return null; }
    finally { setBusy(false); }
  }, [load]);

  const staffById = useMemo(() => new Map(data?.staff.map((staff) => [staff.id, staff]) ?? []), [data]);
  const approved = data?.salaryProfiles.filter((profile) => profile.status === "APPROVED") ?? [];
  const pending = data?.salaryProfiles.filter((profile) => profile.status === "SUBMITTED") ?? [];
  const activeComponents = data?.components.filter((component) => component.status === "ACTIVE") ?? [];
  const money = (amount: number, currency = "INR") => new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: 2 }).format(amount);

  function startProfile(staffId: string, source?: SalaryProfile, editing = false) {
    if (!data) return;
    const lines = Object.fromEntries(data.components.map((component) => {
      const existing = source?.lines.find((line) => line.payrollComponentId === component.id);
      return [component.id, { included: Boolean(existing), value: existing ? String(existing.fixedAmount ?? existing.percentageRate ?? "") : "" }];
    }));
    setEditor({
      editingId: editing ? source?.id : undefined,
      expectedVersion: editing ? source?.rowVersion : undefined,
      staffId,
      supersedesId: editing ? source?.supersedesProfileId : source?.id,
      revision: editing && source ? source.revisionNumber : source ? source.revisionNumber + 1 : 1,
      effectiveFrom: editing && source ? source.effectiveFrom : source ? [localToday(), nextDay(source.effectiveFrom)].sort().at(-1) || localToday() : localToday(),
      currency: source?.currencyCode || "INR",
      reason: editing && source ? source.creationReason : "",
      lines,
    });
    setTab("profiles");
  }

  function startForStaff(staffId: string) {
    if (!data) return;
    const draft = data.salaryProfiles.find((profile) => profile.staffProfileId === staffId && profile.status === "DRAFT");
    if (draft) { startProfile(staffId, draft, true); return; }
    startProfile(staffId, data.salaryProfiles.find((profile) => profile.staffProfileId === staffId && profile.status === "APPROVED"));
  }

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data || !editor) return;
    const lines = data.components.flatMap((component) => {
      const draft = editor.lines[component.id];
      if (!draft?.included || !draft.value) return [];
      return [{ payrollComponentId: component.id, fixedAmount: component.calculationMethod === "FIXED" ? Number(draft.value) : null, percentageRate: component.calculationMethod === "PERCENT_OF_BASIC" ? Number(draft.value) : null }];
    });
    const body = { schoolId: data.schoolId, staffProfileId: editor.staffId, supersedesProfileId: editor.supersedesId || null, revisionNumber: editor.revision, effectiveFrom: editor.effectiveFrom, currencyCode: editor.currency, payFrequency: "MONTHLY", lines, expectedVersion: editor.expectedVersion ?? null, reason: editor.reason };
    const result = await mutate(editor.editingId ? `/salary-profiles/${editor.editingId}` : "/salary-profiles", editor.editingId ? "PUT" : "POST", body, editor.editingId ? "Draft salary terms updated with refreshed component evidence." : "Draft salary terms prepared for review.");
    if (result) setEditor(null);
  }

  async function submitProfile(profile: SalaryProfile) {
    if (!data) return; const reason = window.prompt("Reason for submitting these salary terms for independent review:"); if (!reason?.trim()) return;
    await mutate(`/salary-profiles/${profile.id}/submit`, "POST", { schoolId: data.schoolId, expectedVersion: profile.rowVersion, reason }, "Salary terms submitted for independent review.");
  }

  async function decideProfile(profile: SalaryProfile, decision: "APPROVE" | "REJECT") {
    if (!data) return; const reason = window.prompt(`Reason to ${decision.toLowerCase()} these salary terms:`); if (!reason?.trim()) return;
    await mutate(`/salary-profiles/${profile.id}/decision`, "POST", { schoolId: data.schoolId, decision, expectedVersion: profile.rowVersion, reason }, decision === "APPROVE" ? "Salary terms approved with protected history." : "Salary terms rejected; a corrected revision can now be prepared.");
  }

  async function saveComponent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!data) return; const form = new FormData(event.currentTarget);
    const body = { schoolId: data.schoolId, code: form.get("code"), displayName: form.get("displayName"), componentKind: form.get("componentKind"), calculationMethod: form.get("calculationMethod"), basic: form.get("basic") === "on", attendanceProrated: form.get("attendanceProrated") === "on", statutory: form.get("statutory") === "on", description: form.get("description") || null, displayOrder: Number(form.get("displayOrder")), status: form.get("status"), expectedVersion: editingComponent?.rowVersion ?? null, reason: form.get("reason") };
    const result = await mutate(editingComponent ? `/components/${editingComponent.id}` : "/components", editingComponent ? "PUT" : "POST", body, editingComponent ? "Payroll component updated without rewriting approved salary snapshots." : "Payroll component created as school-controlled configuration.");
    if (result) { setEditingComponent(null); event.currentTarget.reset(); }
  }

  if (loading) return <section className="payroll-state" aria-live="polite"><span /><h1>Opening protected payroll configuration…</h1></section>;
  if (!data) return <section className="payroll-state" aria-live="assertive"><MoneyIcon /><h1>Payroll configuration is unavailable</h1><p>{error}</p><button type="button" onClick={() => window.location.reload()}>Try again</button></section>;
  const lineComponents = editor ? data.components.filter((component) => component.status === "ACTIVE" || editor.lines[component.id]?.included) : [];
  return <section className="payroll-page">
    <header className="payroll-hero">
      <div><span><Shield /> Governed payroll foundation</span><h1>Salary terms with <em>protected history.</em></h1><p>Configure staff terms, freeze monthly attendance calculations, and require independent decisions before payroll becomes final.</p></div>
      <aside><small>Current scope</small><strong>{data.schoolName}</strong><span>Salary terms, monthly runs, private payslips and independently approved payroll journals</span></aside>
    </header>
    <div className="payroll-trust"><span><i /> School-scoped access</span><span><i /> No hard-coded statutory rates</span><span><i /> Maker-checker approval</span><span><i /> Superseded, never overwritten</span></div>
    {error && <div className="payroll-banner is-error" role="alert"><span>{error}</span><button type="button" aria-label="Dismiss error" onClick={() => setError("")}>×</button></div>}
    {notice && <div className="payroll-banner is-success" role="status"><span><Check /> {notice}</span><button type="button" aria-label="Dismiss notice" onClick={() => setNotice("")}>×</button></div>}
    <section className="payroll-metrics" aria-label="Payroll configuration summary">
      <article><span>Eligible staff</span><strong>{data.staff.length}</strong><small>Employment-scoped people records</small></article>
      <article><span>Active components</span><strong>{activeComponents.length}</strong><small>School-controlled earnings and deductions</small></article>
      <article><span>Approved profiles</span><strong>{approved.length}</strong><small>{Math.max(0, data.staff.length - approved.length)} staff without current approved terms</small></article>
      <article className={pending.length ? "is-attention" : ""}><span>Awaiting decision</span><strong>{pending.length}</strong><small>Independent approval required</small></article>
    </section>
    <nav className="payroll-tabs" aria-label="Payroll configuration sections">
      <button type="button" aria-pressed={tab === "profiles"} className={tab === "profiles" ? "is-active" : ""} onClick={() => setTab("profiles")}>Salary profiles{pending.length > 0 && <b>{pending.length}</b>}</button>
      <button type="button" aria-pressed={tab === "components"} className={tab === "components" ? "is-active" : ""} onClick={() => setTab("components")}>Components & policy</button>
      <button type="button" aria-pressed={tab === "runs"} className={tab === "runs" ? "is-active" : ""} onClick={() => setTab("runs")}>Monthly runs</button>
      <button type="button" aria-pressed={tab === "payslips"} className={tab === "payslips" ? "is-active" : ""} onClick={() => setTab("payslips")}>Private payslips</button>
      <button type="button" aria-pressed={tab === "accounting"} className={tab === "accounting" ? "is-active" : ""} onClick={() => setTab("accounting")}>Accounting & payments</button>
      <button type="button" aria-pressed={tab === "reports"} className={tab === "reports" ? "is-active" : ""} onClick={() => setTab("reports")}>Reports</button>
      <button type="button" aria-pressed={tab === "advances"} className={tab === "advances" ? "is-active" : ""} onClick={() => setTab("advances")}>Advances & loans</button>
    </nav>

    {tab === "profiles" && <section className="payroll-profile-layout">
      <article className="payroll-panel payroll-roster">
        <header><div><span>Employment roster</span><h2>Staff salary coverage</h2></div><PeopleIcon /></header>
        <div>{data.staff.map((staff) => {
          const current = approved.find((profile) => profile.staffProfileId === staff.id);
          const draft = data.salaryProfiles.find((profile) => profile.staffProfileId === staff.id && ["DRAFT", "SUBMITTED"].includes(profile.status));
          return <article key={staff.id}><div><b>{staff.displayName}</b><small>{staff.employeeCode} · {staff.designation || "Designation not set"}{staff.department ? ` · ${staff.department}` : ""}</small></div><span>{current ? money(current.estimatedNet, current.currencyCode) : "Not configured"}<small>{draft ? `${label(draft.status)} revision ${draft.revisionNumber}` : current ? `Revision ${current.revisionNumber} current` : "No salary terms"}</small></span>{data.canConfigure && (!draft || draft.status === "DRAFT") && <button type="button" disabled={busy} onClick={() => startForStaff(staff.id)}>{draft ? "Edit draft" : current ? "Prepare revision" : "Prepare terms"}</button>}</article>;
        })}</div>
      </article>

      {editor && <form className="payroll-panel payroll-editor" onSubmit={saveProfile}>
        <header><div><span>{editor.editingId ? "Draft correction" : editor.supersedesId ? "Controlled revision" : "Initial terms"}</span><h2>{staffById.get(editor.staffId)?.displayName}</h2></div><button className="is-quiet" type="button" onClick={() => setEditor(null)}>Close</button></header>
        <div className="payroll-editor-meta">
          <label>Effective from<input type="date" required value={editor.effectiveFrom} min={staffById.get(editor.staffId)?.joinedOn} max={staffById.get(editor.staffId)?.endedOn} onChange={(event) => setEditor({ ...editor, effectiveFrom: event.target.value })} /></label>
          <label>Currency<input required pattern="[A-Za-z]{3}" maxLength={3} value={editor.currency} onChange={(event) => setEditor({ ...editor, currency: event.target.value.toUpperCase() })} /></label>
          <span>Monthly · revision {editor.revision}</span>
        </div>
        <fieldset><legend>Salary components</legend><div className="payroll-component-lines">{lineComponents.map((component) => {
          const line = editor.lines[component.id] || { included: false, value: "" };
          return <label key={component.id} className={line.included ? "is-included" : ""}><input type="checkbox" checked={line.included} onChange={(event) => setEditor({ ...editor, lines: { ...editor.lines, [component.id]: { ...line, included: event.target.checked } } })} /><span><b>{component.displayName}</b><small>{component.code} · {label(component.componentKind)}{component.basic ? " · Basic" : ""}{component.statutory ? " · Statutory setting" : ""}</small></span><input aria-label={`${component.displayName} ${component.calculationMethod === "FIXED" ? "amount" : "percentage of basic"}`} type="number" min="0.01" max={component.calculationMethod === "FIXED" ? "1000000000000" : "1000"} step={component.calculationMethod === "FIXED" ? "0.01" : "0.0001"} disabled={!line.included} required={line.included} placeholder={component.calculationMethod === "FIXED" ? "Amount" : "% of basic"} value={line.value} onChange={(event) => setEditor({ ...editor, lines: { ...editor.lines, [component.id]: { ...line, value: event.target.value } } })} /></label>;
        })}</div></fieldset>
        <label className="payroll-reason">Configuration reason<textarea required minLength={3} maxLength={500} value={editor.reason} onChange={(event) => setEditor({ ...editor, reason: event.target.value })} /></label>
        <footer><small>These are salary-term estimates. Monthly runs independently resolve submitted attendance and approved leave before freezing payable values.</small><button type="submit" disabled={busy || !lineComponents.some((component) => editor.lines[component.id]?.included)}>Save protected draft</button></footer>
      </form>}

      <article className="payroll-panel payroll-history">
        <header><div><span>Revision evidence</span><h2>Salary profile history</h2></div><strong>{data.salaryProfiles.length}</strong></header>
        {data.salaryProfiles.length ? <div>{data.salaryProfiles.map((profile) => {
          const staff = staffById.get(profile.staffProfileId);
          return <article key={profile.id}>
            <div className="payroll-profile-title"><span><b>{staff?.displayName || "Scoped staff record"}</b><em className={`is-${profile.status.toLowerCase()}`}>{label(profile.status)}</em></span><small>Revision {profile.revisionNumber} · effective {profile.effectiveFrom} · prepared by {profile.creatorName}</small></div>
            <div className="payroll-profile-values"><span><small>Gross</small><b>{money(profile.estimatedGross, profile.currencyCode)}</b></span><span><small>Deductions</small><b>{money(profile.estimatedDeductions, profile.currencyCode)}</b></span><span><small>Estimated net</small><strong>{money(profile.estimatedNet, profile.currencyCode)}</strong></span></div>
            <p>{profile.creationReason}</p>
            <details><summary>{profile.lines.length} component{profile.lines.length === 1 ? "" : "s"} · {profile.history.length} history event{profile.history.length === 1 ? "" : "s"}</summary><div className="payroll-profile-lines">{profile.lines.map((line) => <span key={line.id}><b>{line.componentName}</b><small>{line.calculationMethod === "FIXED" ? money(line.fixedAmount || 0, profile.currencyCode) : `${line.percentageRate}% of basic`} · estimate {money(line.estimatedAmount, profile.currencyCode)}</small></span>)}</div><ol>{profile.history.map((event) => <li key={event.id}><b>{label(event.eventType)}</b><span>{event.reason}</span><small>{event.actorName} · {new Date(event.occurredAt).toLocaleString("en-IN")}</small></li>)}</ol></details>
            <footer>{profile.status === "DRAFT" && data.canConfigure && <><button type="button" disabled={busy} onClick={() => startProfile(profile.staffProfileId, profile, true)}>Edit</button><button type="button" disabled={busy} onClick={() => void submitProfile(profile)}>Submit</button></>}{profile.status === "SUBMITTED" && data.canApprove && <><button type="button" disabled={busy} onClick={() => void decideProfile(profile, "APPROVE")}>Approve independently</button><button className="is-secondary" type="button" disabled={busy} onClick={() => void decideProfile(profile, "REJECT")}>Reject</button></>}</footer>
          </article>;
        })}</div> : <p className="payroll-empty">No salary terms have been prepared. Configure components, then begin from the staff roster.</p>}
      </article>
    </section>}
    {tab === "components" && <section className="payroll-component-layout">
      <article className="payroll-panel payroll-components">
        <header><div><span>School-controlled policy</span><h2>Earnings & deductions</h2></div><MoneyIcon /></header>
        <p className="payroll-guidance"><Shield /> Statutory labels and rates are configuration only. Obtain professional payroll and legal review before live processing.</p>
        <div>{data.components.map((component) => <article key={component.id}>
          <span className={`is-${component.componentKind.toLowerCase()}`}><MoneyIcon /></span>
          <div><b>{component.displayName}</b><small>{component.code} · {label(component.componentKind)} · {label(component.calculationMethod)}{component.basic ? " · Basic" : ""}{component.attendanceProrated ? " · Attendance-prorated" : ""}{component.statutory ? " · Statutory" : ""}</small><p>{component.description || "No description recorded."}</p></div>
          <em className={`is-${component.status.toLowerCase()}`}>{label(component.status)}</em>
          {data.canConfigure && <button type="button" onClick={() => setEditingComponent(component)}>Edit</button>}
        </article>)}</div>
      </article>
      {data.canConfigure && <form key={editingComponent?.id || "new"} className="payroll-panel payroll-component-form" onSubmit={saveComponent}>
        <header><div><span>{editingComponent ? "Controlled update" : "New component"}</span><h2>{editingComponent ? editingComponent.displayName : "Add payroll rule"}</h2></div><PlusIcon /></header>
        <div>
          <label>Code<input name="code" defaultValue={editingComponent?.code} pattern="[A-Za-z][A-Za-z0-9_]{1,39}" required /></label>
          <label>Display name<input name="displayName" defaultValue={editingComponent?.displayName} minLength={2} maxLength={120} required /></label>
          <label>Kind<select name="componentKind" defaultValue={editingComponent?.componentKind || "EARNING"}><option>EARNING</option><option>DEDUCTION</option><option>EMPLOYER_CONTRIBUTION</option></select></label>
          <label>Calculation<select name="calculationMethod" defaultValue={editingComponent?.calculationMethod || "FIXED"}><option>FIXED</option><option>PERCENT_OF_BASIC</option></select></label>
          <label>Display order<input name="displayOrder" type="number" min="0" max="1000" defaultValue={editingComponent?.displayOrder ?? 100} required /></label>
          <label>Status<select name="status" defaultValue={editingComponent?.status || "ACTIVE"}><option>ACTIVE</option><option>INACTIVE</option></select></label>
          <label className="payroll-check"><input name="basic" type="checkbox" defaultChecked={editingComponent?.basic} /> Basic salary component</label>
          <label className="payroll-check"><input name="attendanceProrated" type="checkbox" defaultChecked={editingComponent?.attendanceProrated} /> Attendance-prorated earning</label>
          <label className="payroll-check"><input name="statutory" type="checkbox" defaultChecked={editingComponent?.statutory} /> Statutory/compliance setting</label>
          <label className="is-wide">Description<textarea name="description" maxLength={500} defaultValue={editingComponent?.description} /></label>
          <label className="is-wide">Change reason<textarea name="reason" minLength={3} maxLength={500} required /></label>
        </div>
        <footer>{editingComponent && <button className="is-secondary" type="button" onClick={() => setEditingComponent(null)}>Cancel edit</button>}<button type="submit" disabled={busy}>{editingComponent ? "Save controlled update" : "Create component"}</button></footer>
      </form>}
    </section>}
    {tab === "runs" && <PayrollRunWorkspace schoolId={data.schoolId} />}
    {tab === "payslips" && <PayrollPayslipWorkspace schoolId={data.schoolId} />}
    {tab === "accounting" && <PayrollAccountingWorkspace key={data.schoolId} schoolId={data.schoolId} />}
    {tab === "reports" && <PayrollReportWorkspace key={data.schoolId} schoolId={data.schoolId} />}
    {tab === "advances" && <PayrollAdvanceWorkspace key={data.schoolId} schoolId={data.schoolId} />}
  </section>;
}
