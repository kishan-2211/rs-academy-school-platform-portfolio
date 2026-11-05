"use client";

import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useLanguage } from "@/components/i18n/language-provider";
import { staffInvitationMessage, type StaffInvitationMessageKey } from "@/lib/i18n/staff-invitation-messages";
import { loadStaffInvitations, prepareStaffInvitation, prepareStaffInvitationAction, sendStaffInvitationCommand, staffInvitationCsrf, staffInvitationErrorCode, staffInvitationSetupLink, type StaffAccountInvitation, type StaffInvitationCommand, type StaffInvitationErrorCode, type StaffInvitationHandoff, type StaffInvitationList } from "@/lib/staff-account-invitations";
import styles from "./staff-account-invitations.module.css";

type Props = { schoolId: string; staffId: string; displayName: string; employeeCode: string; staffVersion: number; linked: boolean; disabled: boolean; onGuardChange: (blocked: boolean) => void };
type Review = { kind: "ISSUE" } | { kind: "REVOKE" | "HANDOFF"; invitation: StaffAccountInvitation };
const errorKeys: Record<StaffInvitationErrorCode, StaffInvitationMessageKey> = { unavailable: "errorUnavailable", session: "errorSession", scope: "errorScope", mfa: "errorMfa", conflict: "errorConflict", disabled: "errorDisabled", invalid: "errorInvalid", existing: "errorExisting", uncertain: "uncertain", handoffUncertain: "handoffUncertain" };

export function StaffAccountInvitationsPanel({ schoolId, staffId, displayName, employeeCode, staffVersion, linked, disabled, onGuardChange }: Props) {
  const { locale } = useLanguage(), id = useId(), t = (key: StaffInvitationMessageKey) => staffInvitationMessage(locale, key);
  const [context, setContext] = useState<StaffInvitationList | null>(null), [loading, setLoading] = useState(false), [saving, setSaving] = useState(false), [review, setReview] = useState<Review | null>(null);
  const [roleCode, setRoleCode] = useState(""), [hours, setHours] = useState("24"), [reason, setReason] = useState(""), [confirmed, setConfirmed] = useState(false), [error, setError] = useState<StaffInvitationErrorCode | null>(null), [notice, setNotice] = useState<StaffInvitationMessageKey | null>(null), [privateLink, setPrivateLink] = useState<string | null>(null), [uncertain, setUncertain] = useState(false);
  const read = useRef<AbortController | null>(null), write = useRef(false), attempt = useRef<StaffInvitationCommand | null>(null), active = useRef(true), feedback = useRef<HTMLParagraphElement | null>(null), form = useRef<HTMLFormElement | null>(null);
  const blocked = disabled || loading || saving || uncertain || privateLink !== null;
  const guard = useCallback((value: boolean) => onGuardChange(value), [onGuardChange]);
  useEffect(() => { active.current = true; return () => { active.current = false; read.current?.abort(); attempt.current = null; onGuardChange(false); }; }, [schoolId, staffId, onGuardChange]);
  useEffect(() => { guard(loading || saving || review !== null || uncertain || privateLink !== null); }, [guard, loading, saving, review, uncertain, privateLink]);
  useEffect(() => { if (error || notice || uncertain) feedback.current?.focus(); }, [error, notice, uncertain]);
  useEffect(() => { if (review) form.current?.focus(); }, [review]);
  const date = (value: string) => new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));

  const load = useCallback(async () => {
    if (disabled || read.current || write.current || attempt.current) return;
    const controller = new AbortController(); read.current = controller; setLoading(true); setError(null);
    try { const data = await loadStaffInvitations(schoolId, staffId, controller.signal); if (!controller.signal.aborted && active.current) setContext(data); }
    catch (failure) { if (!controller.signal.aborted && active.current) { setContext(null); setError(staffInvitationErrorCode(failure)); } }
    finally { if (read.current === controller) { read.current = null; if (active.current) setLoading(false); } }
  }, [disabled, schoolId, staffId]);

  function begin(next: Review) { if (blocked || review || !context?.canManage) return; setReview(next); setRoleCode(""); setHours("24"); setReason(""); setConfirmed(false); setError(null); setNotice(null); }
  function cancel() { if (saving || uncertain) return; setReview(null); setError(null); setConfirmed(false); setReason(""); }
  async function dispatch(next: StaffInvitationCommand) {
    if (disabled || write.current || read.current || (attempt.current && attempt.current !== next)) return;
    write.current = true; attempt.current = next; setSaving(true); setError(null); setNotice(null);
    let refresh = false;
    try {
      const csrf = await staffInvitationCsrf(new AbortController().signal);
      const result = await sendStaffInvitationCommand(next, csrf);
      if (!active.current) return;
      attempt.current = null; setUncertain(false); setReview(null); setReason(""); setConfirmed(false);
      if (next.kind === "HANDOFF") setPrivateLink(staffInvitationSetupLink(window.location.origin, (result as StaffInvitationHandoff).setupToken));
      else { setNotice(next.kind === "ISSUE" ? "recorded" : "revokedNotice"); refresh = true; }
    } catch (failure) {
      if (!active.current) return;
      const code = staffInvitationErrorCode(failure); setError(code);
      if (code === "uncertain") setUncertain(true);
      else { attempt.current = null; setUncertain(false); setConfirmed(false); if (next.kind === "HANDOFF" || code === "conflict") { setReview(null); setContext(null); } }
    } finally { write.current = false; if (active.current) setSaving(false); }
    if (refresh && active.current) await load();
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!review || !context || blocked) return;
    try { const requestId = crypto.randomUUID(); void dispatch(review.kind === "ISSUE" ? prepareStaffInvitation(context, staffVersion, roleCode, Number(hours), reason, confirmed, requestId) : prepareStaffInvitationAction(context, review.invitation, review.kind, reason, confirmed, requestId)); }
    catch (failure) { setError(staffInvitationErrorCode(failure)); }
  }
  const selectedRole = context?.roles.find(item => item.code === roleCode), title = review?.kind === "ISSUE" ? "issueTitle" : review?.kind === "REVOKE" ? "revokeTitle" : "revealTitle";
  return <section className={styles.root} aria-labelledby={id + "-title"} aria-busy={loading || saving}>
    <h2 id={id + "-title"}>{t("title")}</h2><p>{t("intro")}</p><p>{t("selected")}: <strong>{displayName} · {employeeCode}</strong></p>
    <p className={styles.boundary}>{t("membershipWarning")}</p>
    {linked && <p>{t("linked")}</p>}
    {disabled ? <p role="status">{t("paused")}</p> : <>
      <button type="button" disabled={loading || saving || review !== null || uncertain || privateLink !== null} onClick={() => void load()}>{t(context ? "refresh" : "inspect")}</button>
      {loading && <p role="status">{t("loading")}</p>}
      <p ref={feedback} tabIndex={-1} role={error ? "alert" : "status"} className={error ? styles.error : styles.feedback} hidden={!error && !notice}>{error ? t(errorKeys[error]) : notice ? t(notice) : ""}</p>
      {uncertain && <button type="button" disabled={saving} onClick={() => { if (attempt.current) void dispatch(attempt.current); }}>{t("retry")}</button>}
      {privateLink && <section className={styles.secret} aria-label={t("privateLink")}><p className={styles.warning}>{t("secretWarning")}</p><label htmlFor={id + "-link"}>{t("privateLink")}<textarea id={id + "-link"} readOnly spellCheck={false} autoComplete="off" value={privateLink} /></label><button type="button" onClick={() => { setPrivateLink(null); setContext(null); }}>{t("dismiss")}</button></section>}
      {context && <>
        {!context.manualHandoffEnabled && <p className={styles.warning}>{t("disabled")}</p>}
        {!context.canManage && <p>{t("readOnly")}</p>}
        {!linked && context.canManage && !review && <button type="button" disabled={blocked || !context.manualHandoffEnabled || context.items.some(item => item.status === "ISSUED")} onClick={() => begin({ kind: "ISSUE" })}>{t("newInvite")}</button>}
        {review && <form ref={form} tabIndex={-1} onSubmit={submit} aria-labelledby={id + "-review"} onKeyDown={event => { if (event.key === "Escape") { event.preventDefault(); cancel(); } }}><fieldset disabled={saving || uncertain}>
          <legend id={id + "-review"}>{t(title)}</legend>
          {review.kind === "ISSUE" ? <>
            <label htmlFor={id + "-role"}>{t("role")}<select id={id + "-role"} required value={roleCode} onChange={event => { setRoleCode(event.currentTarget.value); setConfirmed(false); }}><option value="">{t("chooseRole")}</option>{context.roles.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label>
            <p className={styles.warning}>{t("roleWarning")}</p>{selectedRole && <details><summary>{t("capabilities")}</summary><ul>{selectedRole.capabilities.map(capability => <li key={capability}>{capability}</li>)}</ul></details>}
            <label htmlFor={id + "-hours"}>{t("hours")}<input id={id + "-hours"} type="number" min={1} max={168} step={1} required value={hours} onChange={event => { setHours(event.currentTarget.value); setConfirmed(false); }} /></label>
          </> : <><p><strong>{review.invitation.roleName}</strong> · {review.invitation.maskedDestination}</p><p>{t("expires")}: {date(review.invitation.expiresAt)}</p>{review.kind === "HANDOFF" && <p className={styles.warning}>{t("revealWarning")}</p>}</>}
          <label htmlFor={id + "-reason"}>{t("reason")}<textarea id={id + "-reason"} required minLength={3} maxLength={240} value={reason} onChange={event => { setReason(event.currentTarget.value); setConfirmed(false); }} /></label>
          <label className={styles.confirm} htmlFor={id + "-confirm"}><input id={id + "-confirm"} type="checkbox" required checked={confirmed} onChange={event => setConfirmed(event.currentTarget.checked)} />{t(review.kind === "ISSUE" ? "confirmIssue" : review.kind === "REVOKE" ? "confirmRevoke" : "confirmReveal")}</label>
          <div className={styles.actions}><button type="submit">{t(saving ? "saving" : review.kind === "ISSUE" ? "issue" : review.kind === "REVOKE" ? "revokeSubmit" : "revealSubmit")}</button><button type="button" onClick={cancel}>{t("cancel")}</button></div>
        </fieldset></form>}
        {!context.items.length && <p className={styles.empty}>{t("noHistory")}</p>}
        <ul className={styles.records}>{context.items.map(item => <li key={item.id} className={styles.record}><h3>{item.roleName}</h3><dl className={styles.facts}><div><dt>{t("destination")}</dt><dd>{item.maskedDestination}</dd></div><div><dt>{t("status")}</dt><dd>{t(item.status)}</dd></div><div><dt>{t("delivery")}</dt><dd>{t(item.deliveryStatus)}</dd></div><div><dt>{t("expires")}</dt><dd>{date(item.expiresAt)}</dd></div></dl><div className={styles.actions}>{item.canRevealHandoff && <button type="button" disabled={blocked || review !== null} onClick={() => begin({ kind: "HANDOFF", invitation: item })}>{t("reveal")}</button>}{item.canRevoke && <button type="button" disabled={blocked || review !== null} onClick={() => begin({ kind: "REVOKE", invitation: item })}>{t("revoke")}</button>}</div></li>)}</ul>
      </>}
    </>}
  </section>;
}
