"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { PushNotificationControl } from "@/components/push-notification-control";
import { filterInbox, inboxCopy, loadStaffInbox, recordStaffInboxAction,
  type InboxFilter, type InboxLanguage, type StaffInbox } from "@/lib/communication-inbox";
import styles from "./staff-inbox.module.css";

const label = (value: string) => value.toLowerCase().replaceAll("_", " ");
const when = (value: string) => new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
const pageSize = 10;

export function StaffInboxWorkspace() {
  const [data, setData] = useState<StaffInbox | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [filter, setFilter] = useState<InboxFilter>("all");
  const [language, setLanguage] = useState<InboxLanguage>("preferred");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const request = useRef<AbortController | null>(null);
  const mounted = useRef(false);
  const actionInFlight = useRef(false);
  const resultsHeading = useRef<HTMLHeadingElement>(null);

  const load = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true); setError("");
    try {
      const inbox = await loadStaffInbox(controller.signal);
      if (mounted.current && !controller.signal.aborted) setData(inbox);
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted) {
        setData(null);
        setError(reason instanceof Error ? reason.message : "Your inbox is unavailable.");
      }
    } finally {
      if (mounted.current && !controller.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void Promise.resolve().then(() => { if (mounted.current) return load(); });
    return () => { mounted.current = false; request.current?.abort(); };
  }, [load]);

  async function act(recipientId: string, action: "read" | "acknowledge") {
    if (!data || actionInFlight.current) return;
    actionInFlight.current = true;
    setBusy(true); setError(""); setNotice("");
    try {
      await recordStaffInboxAction(data.schoolId, recipientId, action);
      if (!mounted.current) return;
      setNotice(action === "acknowledge" ? "Receipt acknowledged for the school." : "Notice marked as read.");
      await load();
    } catch (reason) {
      if (mounted.current) {
        setData(null);
        setError(reason instanceof Error ? reason.message : "The inbox action could not be saved.");
      }
    } finally {
      actionInFlight.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  const items = filterInbox(data?.items ?? [], filter, search);
  const pages = Math.max(1, Math.ceil(items.length / pageSize));
  const currentPage = Math.min(page, pages - 1);
  const unread = data?.items.filter((item) => !item.readAt && !item.expired).length ?? 0;

  return <div className={styles.inbox}>
    <header className={styles.hero}>
      <span>YOUR SCHOOL DAY</span><h1>My school inbox</h1>
      <p>Approved notices addressed to your staff account. Reading a notice and acknowledging receipt are separate actions.</p>
      <div className={styles.actions}><button type="button" disabled={loading || busy} onClick={() => void load()}>Refresh inbox</button></div>
    </header>
    {notice && <p className={styles.success} role="status">{notice}</p>}
    {error && <div className={styles.error} role="alert"><p>{error}</p><Link href="/portal">Return to sign in</Link></div>}
    {loading && <p role="status">Loading your verified inbox…</p>}
    {data && <>
      <PushNotificationControl key={data.schoolId} schoolId={data.schoolId} />
      <section className={styles.tools} aria-label="Find school notices">
        <label>Search notices<input type="search" value={search} maxLength={200} onChange={(event) => { setSearch(event.target.value); setPage(0); }} /></label>
        <label>Show<select value={filter} onChange={(event) => { setFilter(event.target.value as InboxFilter); setPage(0); }}>
          <option value="all">All notices</option><option value="unread">Unread</option><option value="acknowledgement">Needs acknowledgement</option><option value="expired">Past notices</option>
        </select></label>
        <label>Reading language<select value={language} onChange={(event) => setLanguage(event.target.value as InboxLanguage)}>
          <option value="preferred">My preferred language</option><option value="en">English</option><option value="hi">हिन्दी</option>
        </select></label>
      </section>
      <section aria-labelledby="inbox-results" aria-busy={loading || busy}>
        <h2 id="inbox-results" ref={resultsHeading} tabIndex={-1}>School notices</h2>
        <p role="status">{items.length} matching {items.length === 1 ? "notice" : "notices"} · {unread} unread active {unread === 1 ? "notice" : "notices"}</p>
        {!items.length && <div className={styles.card}><h3>{data.items.length ? "No matching notices" : "You are up to date"}</h3><p>{data.items.length ? "Change the filter or search to find another notice." : "Approved notices addressed to you will appear here. No action is needed."}</p></div>}
        <div className={styles.list}>{items.slice(currentPage * pageSize, (currentPage + 1) * pageSize).map((item) => {
          const copy = inboxCopy(item, language);
          return <article key={item.recipientId} className={styles.card} aria-labelledby={`notice-${item.recipientId}`}>
            <div className={styles.tags}><span>{label(item.category)}</span><span className={item.priority === "EMERGENCY" || item.priority === "URGENT" ? styles.urgent : undefined}>{label(item.priority)}</span><strong>{item.expired ? "Past notice" : item.acknowledgedAt ? "Acknowledged" : item.readAt ? "Read" : "Unread"}</strong></div>
            <h3 id={`notice-${item.recipientId}`} lang={copy.language}>{copy.title}</h3>
            <p className={styles.body} lang={copy.language}>{copy.body}</p>
            <p className={styles.meta}>Published <time dateTime={item.scheduledFor}>{when(item.scheduledFor)}</time>{item.expiresAt && <> · Valid until <time dateTime={item.expiresAt}>{when(item.expiresAt)}</time></>}</p>
            {item.expired && <p>This notice has expired. Consult current notices before acting on it.</p>}
            {item.requiresAcknowledgement && !item.acknowledgedAt && <p>The school requests acknowledgement of receipt.</p>}
            <div className={styles.actions}>
              {!item.readAt && <button type="button" disabled={busy || loading} onClick={() => void act(item.recipientId, "read")} aria-label={`Mark as read: ${copy.title}`}>Mark as read</button>}
              {item.requiresAcknowledgement && !item.acknowledgedAt && <button type="button" disabled={busy || loading} onClick={() => void act(item.recipientId, "acknowledge")} aria-label={`Acknowledge receipt: ${copy.title}`}>Acknowledge receipt</button>}
            </div>
            {item.acknowledgedAt && <p className={styles.meta}>Receipt acknowledged <time dateTime={item.acknowledgedAt}>{when(item.acknowledgedAt)}</time></p>}
          </article>;
        })}</div>
        {pages > 1 && <nav aria-label="Inbox pages" className={styles.actions}>
          <button type="button" disabled={currentPage === 0} onClick={() => { setPage(currentPage - 1); resultsHeading.current?.focus(); }}>Previous</button>
          <span aria-current="page">Page {currentPage + 1} of {pages}</span>
          <button type="button" disabled={currentPage + 1 >= pages} onClick={() => { setPage(currentPage + 1); resultsHeading.current?.focus(); }}>Next</button>
        </nav>}
      </section>
    </>}
  </div>;
}
