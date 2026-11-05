"use client";

import { useEffect, useState } from "react";
import { Message } from "@/components/icons";

type PublicAnnouncement = { id: string; category: string; priority: string; languageMode: string; titleEn?: string; titleHi?: string; bodyEn?: string; bodyHi?: string; publishedAt: string; expiresAt?: string };
type Response = { schoolName?: string; announcements: PublicAnnouncement[] };
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const label = (value: string) => value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());

export function PublicNotices() {
  const [data, setData] = useState<Response | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let cancelled = false;
    fetch(`${apiUrl}/api/v1/public/schools/rs-academy/announcements`)
      .then(async (response) => {
        if (!response.ok) throw new Error("Approved notices are temporarily unavailable.");
        if (!cancelled) setData(await response.json() as Response);
      })
      .catch((reason: Error) => { if (!cancelled) setError(reason.message); });
    return () => { cancelled = true; };
  }, []);

  return <section className="public-notices" aria-labelledby="public-notices-title">
    <div className="shell">
      <header><div><span>Authority-published</span><h2 id="public-notices-title">Current school notices</h2></div><p>Only approved, currently valid public notices appear here. Drafts and family messages stay private.</p></header>
      {error ? <div className="public-notices__state is-error" role="alert">{error} Please contact the school office for time-sensitive information.</div> : !data ? <div className="public-notices__state" aria-live="polite">Checking for approved notices…</div> : !data.announcements.length ? <div className="public-notices__state"><Message /><strong>No public notice is currently active.</strong><span>This is an honest empty state—not sample school information.</span></div> : <div className="public-notices__grid">{data.announcements.map((item) => <article key={item.id}><header><span>{label(item.category)}</span><em>{label(item.priority)}</em></header><h3>{item.titleEn || item.titleHi}</h3><p>{item.bodyEn || item.bodyHi}</p>{item.titleEn && item.titleHi && <details lang="hi"><summary>हिन्दी में पढ़ें</summary><h4>{item.titleHi}</h4><p>{item.bodyHi}</p></details>}<footer><time dateTime={item.publishedAt}>Published {new Date(item.publishedAt).toLocaleString("en-IN")}</time>{item.expiresAt && <span>Valid until {new Date(item.expiresAt).toLocaleString("en-IN")}</span>}</footer></article>)}</div>}
    </div>
  </section>;
}
