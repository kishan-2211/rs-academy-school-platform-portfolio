"use client";

import Link from "next/link";
import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useLanguage } from "@/components/i18n/language-provider";
import { staffInvitationMessage, type StaffInvitationMessageKey } from "@/lib/i18n/staff-invitation-messages";
import { acceptStaffInvitation, currentStaffInvitationSession, previewStaffInvitation, staffInvitationCsrf, staffInvitationErrorCode, staffInvitationFragment, validStaffInvitationPassword, type StaffInvitationErrorCode, type StaffInvitationPreview } from "@/lib/staff-account-invitations";
import styles from "./workspace/staff-account-invitations.module.css";

const errorKeys: Record<StaffInvitationErrorCode, StaffInvitationMessageKey> = { unavailable:"errorUnavailable",session:"errorSession",scope:"errorScope",mfa:"errorMfa",conflict:"errorConflict",disabled:"errorDisabled",invalid:"unavailable",existing:"errorExisting",uncertain:"acceptUncertain",handoffUncertain:"errorUnavailable" };

export function StaffInvitation() {
  const { locale } = useLanguage(), id = useId(), t = (key: StaffInvitationMessageKey) => staffInvitationMessage(locale, key);
  const [preview,setPreview] = useState<StaffInvitationPreview|null>(null), [loading,setLoading] = useState(true), [submitting,setSubmitting] = useState(false), [accepted,setAccepted] = useState(false), [session,setSession] = useState<boolean|null>(null), [checking,setChecking] = useState(false), [confirmed,setConfirmed] = useState(false), [error,setError] = useState<StaffInvitationMessageKey|null>(null);
  const [uncertainOutcome,setUncertainOutcome] = useState(false);
  const token = useRef<string|null>(null), read = useRef<AbortController|null>(null), checkingRead = useRef<AbortController|null>(null), active = useRef(true), writing = useRef(false), feedback = useRef<HTMLDivElement|null>(null);
  useEffect(() => {
    active.current = true;
    if (token.current === null) { token.current = staffInvitationFragment(window.location.hash) ?? ""; window.history.replaceState(null,"","/staff-invitation"); }
    const controller = new AbortController(); read.current = controller;
    Promise.resolve().then(async () => {
      if (controller.signal.aborted) return;
      if (!token.current) { setError("unavailable"); setLoading(false); return; }
      try { const next = await previewStaffInvitation(token.current,controller.signal); if (!controller.signal.aborted && active.current) setPreview(next); }
      catch (failure) { if (!controller.signal.aborted && active.current) setError(errorKeys[staffInvitationErrorCode(failure)]); }
      finally { if (!controller.signal.aborted && active.current) setLoading(false); }
    });
    return () => { active.current = false; controller.abort(); checkingRead.current?.abort(); };
  }, []);
  useEffect(() => { if (error || accepted) feedback.current?.focus(); }, [error,accepted]);
  const checkSession = useCallback(async () => {
    if (checkingRead.current || writing.current) return;
    const controller = new AbortController(); checkingRead.current = controller; setChecking(true); setError(null);
    try { const available = await currentStaffInvitationSession(controller.signal); if (!controller.signal.aborted && active.current) setSession(available); }
    catch (failure) { if (!controller.signal.aborted && active.current) setError(errorKeys[staffInvitationErrorCode(failure)]); }
    finally { if (checkingRead.current === controller) { checkingRead.current = null; if (active.current) setChecking(false); } }
  }, []);
  async function accept(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!preview || !token.current || writing.current || !confirmed || checking || accepted || uncertainOutcome) return;
    const element = event.currentTarget, values = new FormData(element), password = String(values.get("password")??""), confirmation = String(values.get("confirmation")??"");
    if (!preview.existingAccount && password !== confirmation) { setError("passwordMismatch"); return; }
    if (!preview.existingAccount && !validStaffInvitationPassword(password)) { setError("passwordInvalid"); return; }
    if (preview.existingAccount && session !== true) { setError("sessionMissing"); return; }
    writing.current = true; setSubmitting(true); setError(null);
    try {
      const mode = preview.existingAccount ? { kind:"existing" as const, csrf:await staffInvitationCsrf(new AbortController().signal) } : { kind:"new" as const,password,confirmation };
      element.reset(); setConfirmed(false);
      await acceptStaffInvitation(token.current,mode);
      if (active.current) { element.reset(); token.current=""; setPreview(null); setAccepted(true); setConfirmed(false); }
    } catch (failure) { if (active.current) { const code=staffInvitationErrorCode(failure); element.reset(); setConfirmed(false); setError(errorKeys[code]); if(code==="uncertain")setUncertainOutcome(true); if(code==="existing"){setPreview(current=>current?{...current,existingAccount:true}:null);setSession(null);} } }
    finally { writing.current = false; if (active.current) setSubmitting(false); }
  }
  return <main id="main-content" className={styles.page}>
    <section className={styles.story}><span className={styles.eyebrow}>{t("acceptEyebrow")}</span><h1>{t("acceptTitle")}</h1><p>{t("acceptIntro")}</p></section>
    <section className={styles.root} aria-busy={loading||submitting||checking}>
      <div ref={feedback} tabIndex={-1} className={styles.feedback}>
        {accepted ? <><h2>{t("successTitle")}</h2><p role="status">{t("success")}</p><Link className="button button--dark" href="/portal">{t("returnSignIn")}</Link></> : error ? <p role="alert" className={styles.error}>{t(error)}</p> : null}
      </div>
      {loading ? <p role="status">{t("verifying")}</p> : uncertainOutcome ? <div className={styles.actions}><Link href="/portal">{t("returnSignIn")}</Link><Link href="/contact">{t("contact")}</Link></div> : !accepted && preview ? <>
        <h2>{preview.schoolName}</h2><dl className={styles.facts}><div><dt>{t("destination")}</dt><dd>{preview.maskedDestination}</dd></div><div><dt>{t("role")}</dt><dd>{preview.roleName}</dd></div><div><dt>{t("expires")}</dt><dd>{new Intl.DateTimeFormat(locale==="hi"?"hi-IN":"en-IN",{dateStyle:"medium",timeStyle:"short"}).format(new Date(preview.expiresAt))}</dd></div></dl>
        <p className={styles.boundary}>{t("membershipWarning")}</p>
        {preview.existingAccount && <><p>{t("existingBoundary")}</p><p>{t("sessionHelp")}</p><div className={styles.actions}><Link href="/portal" target="_blank" rel="noopener noreferrer">{t("signIn")}</Link><button type="button" disabled={checking||submitting} onClick={()=>void checkSession()}>{t("checkSession")}</button></div>{session!==null&&<p role="status">{t(session?"sessionReady":"sessionMissing")}</p>}</>}
        <form onSubmit={accept} aria-label={t("acceptEyebrow")}><fieldset disabled={submitting||checking}><legend>{t("acceptEyebrow")}</legend>
          {!preview.existingAccount && <><p id={id+"-password-help"}>{t("passwordHelp")}</p><label htmlFor={id+"-password"}>{t("password")}<input id={id+"-password"} name="password" type="password" autoComplete="new-password" minLength={16} maxLength={200} required spellCheck={false} aria-describedby={id+"-password-help"}/></label><label htmlFor={id+"-confirmation"}>{t("confirmation")}<input id={id+"-confirmation"} name="confirmation" type="password" autoComplete="new-password" minLength={16} maxLength={200} required spellCheck={false}/></label></>}
          <label className={styles.confirm} htmlFor={id+"-confirmed"}><input id={id+"-confirmed"} type="checkbox" required checked={confirmed} onChange={event=>setConfirmed(event.currentTarget.checked)}/>{t("confirmAccept")}</label>
          <button type="submit" disabled={!confirmed || (preview.existingAccount && session!==true)}>{t(submitting?"accepting":preview.existingAccount?"acceptExisting":"acceptNew")}</button>
        </fieldset></form>
      </> : !accepted ? <><h2>{t("unavailableTitle")}</h2><p>{t("unavailable")}</p><div className={styles.actions}><Link href="/portal">{t("returnSignIn")}</Link><Link href="/contact">{t("contact")}</Link></div></> : null}
    </section>
  </main>;
}
