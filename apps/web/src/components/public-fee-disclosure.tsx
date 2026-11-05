"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowUpRight, Shield } from "@/components/icons";
import { MoneyIcon } from "@/components/workspace/workspace-icons";

type Disclosure = {
  schoolName: string;
  currencyCode: string;
  retrievedAt: string;
  plans: Array<{
    academicSession: string;
    planName: string;
    gradeCode: string | null;
    revisionNumber: number;
    lines: Array<{ feeHead: string; label: string; dueOn: string; amount: number; allowPartial: boolean }>;
  }>;
};
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function PublicFeeDisclosure() {
  const [data, setData] = useState<Disclosure | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let cancelled = false;
    fetch(`${apiUrl}/api/v1/public/schools/rs-academy/fees`)
      .then(async (response) => {
        if (!response.ok) throw new Error("The approved fee disclosure is temporarily unavailable.");
        if (!cancelled) setData(await response.json() as Disclosure);
      })
      .catch((reason: Error) => { if (!cancelled) setError(reason.message); });
    return () => { cancelled = true; };
  }, []);
  const money = (amount: number) => new Intl.NumberFormat("en-IN", { style: "currency", currency: data?.currencyCode || "INR", maximumFractionDigits: 2 }).format(amount);
  return <main id="main-content" className="public-fees">
    <section className="public-fees__hero"><div className="shell"><p><span /> Fees & public disclosure</p><h1>Clear amounts.<br /><em>No outdated forwards.</em></h1><div><p>Only a fee-plan revision activated and explicitly approved by school authority appears here. Drafts and photographed reference notices are never published automatically.</p><span><Shield /> Authority-controlled disclosure</span></div></div></section>
    <section className="shell public-fees__content">
      {!data && !error ? <div className="public-fees__state"><i /><span>Checking the latest approved fee structure…</span></div> : error ? <div className="public-fees__state public-fees__state--error"><MoneyIcon /><h2>Disclosure unavailable</h2><p>{error}</p><Link href="/contact">Contact the school office <ArrowUpRight /></Link></div> : !data?.plans.length ? <div className="public-fees__state"><Shield /><h2>No fee structure is approved for web publication yet</h2><p>Please contact the school office for the current signed fee information. The reference photograph supplied during development is not treated as approved production data.</p><Link href="/contact">Ask the school directly <ArrowUpRight /></Link></div> : <>
        <header className="public-fees__intro"><div><span>Latest approved records</span><h2>{data.schoolName} fee structure</h2></div><p>Retrieved {new Date(data.retrievedAt).toLocaleString("en-IN")}</p></header>
        <div className="public-fees__plans">{data.plans.map((plan) => <article key={`${plan.academicSession}-${plan.planName}-${plan.revisionNumber}`}><header><div><span>{plan.academicSession} · revision {plan.revisionNumber}</span><h2>{plan.planName}</h2><p>{plan.gradeCode ? `Class ${plan.gradeCode}` : "School-authorized audience"}</p></div><Shield /></header><div className="public-fees__table"><div><span>Fee line</span><span>Due date</span><span>Amount</span></div>{plan.lines.map((line, index) => <div key={`${line.label}-${line.dueOn}-${index}`}><span><strong>{line.label}</strong><small>{line.feeHead}{line.allowPartial ? " · partial payment permitted" : " · payable in full"}</small></span><time dateTime={line.dueOn}>{new Date(`${line.dueOn}T12:00:00`).toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" })}</time><b>{money(line.amount)}</b></div>)}</div><footer><span>Total scheduled amount</span><strong>{money(plan.lines.reduce((sum, line) => sum + line.amount, 0))}</strong></footer></article>)}</div>
      </>}
      <aside className="public-fees__note"><MoneyIcon /><div><h2>Confirm individual eligibility with the school</h2><p>Concessions, scholarships, optional services, admission availability, late rules, and a learner&apos;s actual account are private operational matters. Families see verified personal charges and receipts only after secure login.</p></div><Link href="/portal">Open portal <ArrowUpRight /></Link></aside>
    </section>
  </main>;
}
