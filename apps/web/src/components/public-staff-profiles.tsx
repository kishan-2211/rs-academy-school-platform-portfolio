"use client";

import { useEffect, useState } from "react";
import { PeopleIcon } from "@/components/workspace/workspace-icons";
import styles from "./public-staff-profiles.module.css";

type StaffProfile = { id: string; displayName: string; designation: string; qualificationSummary?: string; shortBio?: string; cbseStaffCategory?: string; displayOrder: number; publishedAt: string };
type Response = { schoolName?: string; staff: StaffProfile[] };
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const label = (value?: string) => value ? value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase()) : "School staff";

export function PublicStaffProfiles() {
  const [data, setData] = useState<Response | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    fetch(`${apiUrl}/api/v1/public/schools/rs-academy/staff`)
      .then(async (response) => {
        if (!response.ok) throw new Error("Approved staff profiles are temporarily unavailable.");
        if (!cancelled) setData(await response.json() as Response);
      })
      .catch((reason: Error) => { if (!cancelled) setError(reason.message); });
    return () => { cancelled = true; };
  }, []);

  return <section className={styles.section} aria-labelledby="public-staff-title"><div className="shell">
    <header className={styles.header}><div><span>Authority-published</span><h2 id="public-staff-title">Meet the people we have approved to introduce.</h2></div><p>This directory uses deliberate public snapshots. Employment contacts, login accounts, safeguarding checks, and private staff records never appear here.</p></header>
    {error ? <div className={`${styles.state} ${styles.error}`} role="alert">{error} Please contact the school office if you need a verified staff detail.</div> : !data ? <div className={styles.state} aria-live="polite">Checking for approved staff profiles…</div> : !data.staff.length ? <div className={styles.state}><PeopleIcon /><strong>No staff profile has been approved for public display yet.</strong><span>This honest empty state avoids publishing sample or unverified information.</span></div> : <div className={styles.grid}>{data.staff.map((item, index) => <article key={item.id} className={styles.card}><div className={styles.number}>{String(index + 1).padStart(2, "0")}</div><header><span>{label(item.cbseStaffCategory)}</span><time dateTime={item.publishedAt}>Verified public snapshot</time></header><h3>{item.displayName}</h3><strong>{item.designation}</strong>{item.qualificationSummary && <p className={styles.qualification}>{item.qualificationSummary}</p>}{item.shortBio && <p>{item.shortBio}</p>}</article>)}</div>}
  </div></section>;
}
