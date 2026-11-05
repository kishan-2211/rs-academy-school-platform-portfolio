"use client";

import {useCallback,useEffect,useId,useMemo,useRef,useState,type FormEvent,type ReactNode} from "react";
import {useLanguage} from "@/components/i18n/language-provider";
import {familyPaymentCsrf} from "@/lib/family-gateway-payments";
import {formatSettlementAmount} from "@/lib/gateway-settlements";
import {currentSchoolLocalTime,schoolLocalTimeToInstant} from "@/lib/manual-fee-collection";
import {bankEvidenceErrorCode,bankVerificationKinds,BankEvidenceWriteError,loadBankEvidenceContext,prepareBankEvidence,prepareBankEvidenceRejection,sendBankEvidence,type BankEvidenceAttempt,type BankEvidenceDraft,type BankEvidenceErrorCode,type SettlementBankContext,type SettlementBankEvidence} from "@/lib/gateway-settlement-bank-evidence";
import {settlementBankMessage,type SettlementBankMessageKey} from "@/lib/i18n/gateway-settlement-bank-messages";
import styles from "./settlement-bank-evidence-panel.module.css";

type Props={schoolId:string;requestId:string;settlementReference:string;disabled:boolean;onGuardChange:(blocked:boolean)=>void};
type Scope={schoolId:string;requestId:string;settlementReference:string;disabled:boolean};
type Snapshot={scope:Scope;data:SettlementBankContext};
const emptyDraft=():BankEvidenceDraft=>({bankAccountId:"",amount:"",actualLocal:"",utcOffset:"",verificationKind:"BANK_APP_CREDIT",bankReference:"",confirmed:false,reason:""});

export function SettlementBankEvidencePanel({schoolId,requestId,settlementReference,disabled,onGuardChange}:Props){
  const {locale}=useLanguage(),language=locale==="hi"?"hi":"en",id=useId();
  const t=(key:SettlementBankMessageKey)=>settlementBankMessage(language,key);
  const scope=useMemo<Scope>(()=>({schoolId,requestId,settlementReference,disabled}),[schoolId,requestId,settlementReference,disabled]);
  const [snapshot,setSnapshot]=useState<Snapshot|null>(null);
  const [pending,setPending]=useState<Scope|null>(null);
  const [locked,setLocked]=useState(false);
  const [saving,setSaving]=useState(false);
  const [draft,setDraft]=useState<BankEvidenceDraft>(emptyDraft);
  const [formOpen,setFormOpen]=useState(false);
  const [rejectId,setRejectId]=useState<string|null>(null);
  const [rejectReason,setRejectReason]=useState("");
  const [rejectConfirmed,setRejectConfirmed]=useState(false);
  const [error,setError]=useState<{scope:Scope;code:BankEvidenceErrorCode}|null>(null);
  const [ack,setAck]=useState<{scope:Scope;evidence:SettlementBankEvidence}|null>(null);
  const read=useRef<AbortController|null>(null),writing=useRef(false),attempt=useRef<BankEvidenceAttempt|null>(null),unknown=useRef(false),lifecycle=useRef(0),feedback=useRef<HTMLParagraphElement|null>(null);
  const context=!disabled&&snapshot?.scope===scope&&snapshot.data.schoolId===schoolId&&snapshot.data.requestId===requestId&&snapshot.data.settlementReference===settlementReference?snapshot.data:null;
  const busy=!disabled&&pending===scope;
  const blocked=busy||saving||locked||disabled;
  const visibleAck=!disabled&&ack?.scope===scope?ack.evidence:null;
  const selected=context?.evidence.find(row=>row.id===rejectId)??null;
  const resolution=context&&draft.actualLocal?schoolLocalTimeToInstant(draft.actualLocal,context.schoolTimeZone):null;

  useEffect(()=>{
    lifecycle.current+=1;
    return()=>{lifecycle.current+=1;read.current?.abort();read.current=null;if(!attempt.current)onGuardChange(false);};
  },[scope,onGuardChange]);
  useEffect(()=>{
    if(!locked)return;
    const leave=(event:BeforeUnloadEvent)=>{event.preventDefault();event.returnValue="";};
    const link=(event:MouseEvent)=>{const target=event.target instanceof Element?event.target.closest("a[href]"):null;if(target&&target.getAttribute("href")?.startsWith("#")!==true){event.preventDefault();event.stopPropagation();feedback.current?.focus();}};
    // Modern browser history traversal is cancellable through the Navigation
    // API; anchor capture and beforeunload remain the progressive fallbacks.
    const navigation=(window as Window&{navigation?:EventTarget}).navigation;
    const navigate=(event:Event)=>{if(event.cancelable&&!(event as Event&{hashChange?:boolean}).hashChange){event.preventDefault();feedback.current?.focus();}};
    window.addEventListener("beforeunload",leave);document.addEventListener("click",link,true);
    navigation?.addEventListener("navigate",navigate);
    return()=>{window.removeEventListener("beforeunload",leave);document.removeEventListener("click",link,true);navigation?.removeEventListener("navigate",navigate);};
  },[locked]);
  useEffect(()=>{if(visibleAck)feedback.current?.focus();},[visibleAck]);

  const load=useCallback(async()=>{
    if(disabled||read.current||writing.current||attempt.current)return;
    const controller=new AbortController(),epoch=lifecycle.current;read.current=controller;setPending(scope);setSnapshot(null);setError(null);setFormOpen(false);setRejectId(null);onGuardChange(true);
    try{const data=await loadBankEvidenceContext(schoolId,requestId,settlementReference,controller.signal);if(!controller.signal.aborted&&epoch===lifecycle.current)setSnapshot({scope,data});}
    catch(failure){if(!controller.signal.aborted&&epoch===lifecycle.current)setError({scope,code:bankEvidenceErrorCode(failure)});}
    finally{if(read.current===controller){read.current=null;setPending(null);if(!attempt.current)onGuardChange(false);}}
  },[schoolId,requestId,settlementReference,disabled,scope,onGuardChange]);

  async function dispatch(next:BankEvidenceAttempt){
    if(disabled||read.current||writing.current||attempt.current&&attempt.current!==next)return;
    attempt.current=next;writing.current=true;setLocked(true);setSaving(true);setError(null);onGuardChange(true);
    const epoch=lifecycle.current;let recorded:SettlementBankEvidence|null=null;
    try{
      const csrf=await familyPaymentCsrf(new AbortController().signal).catch(()=>{throw new BankEvidenceWriteError("rejected","csrf");});
      if(epoch!==lifecycle.current)return;
      recorded=await sendBankEvidence(next,csrf);
      if(epoch!==lifecycle.current)return;
      attempt.current=null;unknown.current=false;setLocked(false);setFormOpen(false);setRejectId(null);setRejectConfirmed(false);setAck({scope,evidence:recorded});onGuardChange(false);
    }catch(failure){
      if(epoch!==lifecycle.current)return;
      if(!(failure instanceof BankEvidenceWriteError)||failure.outcome==="unknown"||unknown.current){unknown.current=true;setError({scope,code:"unknown"});}
      else{attempt.current=null;unknown.current=false;setLocked(false);setDraft(current=>({...current,confirmed:false}));setRejectConfirmed(false);setError({scope,code:bankEvidenceErrorCode(failure)});onGuardChange(false);}
    }finally{writing.current=false;if(epoch===lifecycle.current)setSaving(false);}
    if(recorded&&epoch===lifecycle.current)await load();
  }
  function startDraft(){if(blocked||!context?.canRecord||attempt.current)return;setDraft(emptyDraft());setFormOpen(true);setRejectId(null);setRejectReason("");setRejectConfirmed(false);setError(null);setAck(null);}
  function update<K extends keyof BankEvidenceDraft>(key:K,value:BankEvidenceDraft[K]){if(!blocked){setDraft(current=>({...current,[key]:value,...(key!=="confirmed"?{confirmed:false}:{})}));setError(null);}}
  function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();if(blocked||!context)return;try{void dispatch(prepareBankEvidence(context,draft,crypto.randomUUID()));}catch(failure){setError({scope,code:bankEvidenceErrorCode(failure)});}}
  function reject(event:FormEvent<HTMLFormElement>){event.preventDefault();if(blocked||!context||!selected)return;try{void dispatch(prepareBankEvidenceRejection(context,selected,rejectReason,rejectConfirmed,crypto.randomUUID()));}catch(failure){setError({scope,code:bankEvidenceErrorCode(failure)});}}
  function cancel(){if(blocked)return;setFormOpen(false);setDraft(emptyDraft());setRejectId(null);setRejectReason("");setRejectConfirmed(false);setError(null);}

  return <section className={styles.root} aria-labelledby={`${id}-heading`} aria-busy={busy||saving}>
    <h4 id={`${id}-heading`}>{t("title")}</h4><p>{t("intro")}</p><p className={styles.boundary}>{t("boundary")}</p><p>{t("statement")}: <strong>{settlementReference}</strong></p>
    {disabled?<p role="status">{t("paused")}</p>:<>
      {!locked&&<button type="button" disabled={busy||saving||Boolean(context&&(formOpen||rejectId!==null))} onClick={()=>{if(!busy&&!saving)void load();}}>{t(context?"refresh":"inspect")}</button>}
      {(busy||saving)&&<p role="status">{t(saving?"saving":"loading")}</p>}
      {error?.scope===scope&&<p role="alert" className={styles.error}>{t(`error.${error.code}`)}</p>}
      <p ref={feedback} tabIndex={-1} role="status" className={styles.feedback} hidden={!locked&&!visibleAck}>{locked?t("navigationBlocked"):visibleAck?t("recorded"):""}</p>
      {locked&&<div className={styles.warning}><p>{t(saving?"saving":"unknown")}</p><button type="button" disabled={saving} onClick={()=>{if(!saving&&attempt.current)void dispatch(attempt.current);}}>{t("retry")}</button></div>}
      {!busy&&!context&&!error&&!visibleAck&&<p>{t("idle")}</p>}
      {visibleAck&&<EvidenceCard evidence={visibleAck} accounts={context?.bankAccounts??[]} locale={language} />}
      {context&&<>
        <p>{t("zone")}: <strong>{context.schoolTimeZone}</strong></p>
        <details className={styles.summary}><summary>{t("summary")}</summary><dl className={styles.facts}><Fact label={t("summaryAmount")} value={context.summary.reportedAmountSettled===null?t("unknownValue"):formatSettlementAmount(context.summary.reportedAmountSettled,language)} /><Fact label={t("summaryStatus")} value={context.summary.reportedStatus} /><Fact label={t("summaryReference")} value={context.summary.transferReference??t("unknownValue")} /><Fact label={t("summarySource")} value={context.summary.sourceReadId} /><Fact label={t("summaryRow")} value={context.summary.rowIndex+1} /><Fact label={t("summaryObserved")} value={<ExactTime value={context.summary.observedAt} />} /></dl></details>
        {!context.canRecord&&<p>{t("readOnly")}</p>}
        {context.canRecord&&!formOpen&&<button type="button" disabled={blocked||rejectId!==null||!context.bankAccounts.length} onClick={startDraft}>{t("newDraft")}</button>}
        {context.canRecord&&!context.bankAccounts.length&&<p className={styles.warning}>{t("noAccounts")}</p>}
        {context.canRecord&&formOpen&&<form onSubmit={submit} aria-label={t("form")}><fieldset disabled={blocked}><legend>{t("form")}</legend><p className={styles.boundary}>{t("noGuess")}</p>
          <div className={styles.fields}><label htmlFor={`${id}-account`}>{t("account")}<select id={`${id}-account`} required value={draft.bankAccountId} onChange={event=>update("bankAccountId",event.currentTarget.value)}><option value="">{t("chooseAccount")}</option>{context.bankAccounts.map(account=><option key={account.id} value={account.id}>{account.code} · {account.name} · {account.currencyCode}</option>)}</select></label>
          <label htmlFor={`${id}-amount`}>{t("amount")}<input id={`${id}-amount`} type="text" inputMode="decimal" required maxLength={12} value={draft.amount} onChange={event=>update("amount",event.currentTarget.value)} /></label></div>
          <label htmlFor={`${id}-actual`}>{t("actualTime")} · {context.schoolTimeZone}<input id={`${id}-actual`} type="datetime-local" step="1" min="2000-01-01T00:00:00" max={currentSchoolLocalTime(context.schoolTimeZone,new Date())??undefined} required value={draft.actualLocal} aria-describedby={`${id}-time-help`} onChange={event=>{update("actualLocal",event.currentTarget.value);update("utcOffset","");}} /></label><p id={`${id}-time-help`}>{t("timeHelp")}</p>
          {(resolution?.kind==="ambiguous"||draft.utcOffset)&&<><label htmlFor={`${id}-offset`}>{t("offset")}<input id={`${id}-offset`} type="text" maxLength={6} placeholder="+05:30" value={draft.utcOffset} required aria-describedby={`${id}-offset-help`} onChange={event=>update("utcOffset",event.currentTarget.value)} /></label><p id={`${id}-offset-help`}>{t("offsetHelp")}</p></>}
          <label htmlFor={`${id}-verification`}>{t("verification")}<select id={`${id}-verification`} value={draft.verificationKind} onChange={event=>update("verificationKind",event.currentTarget.value as BankEvidenceDraft["verificationKind"])}>{bankVerificationKinds.map(kind=><option key={kind} value={kind}>{t(`verification.${kind}`)}</option>)}</select></label>
          <label htmlFor={`${id}-reference`}>{t("bankReference")}<input id={`${id}-reference`} type="text" autoComplete="off" required maxLength={120} value={draft.bankReference} aria-describedby={`${id}-reference-help`} onChange={event=>update("bankReference",event.currentTarget.value)} /></label><p id={`${id}-reference-help`}>{t("referenceHelp")}</p>
          <label htmlFor={`${id}-reason`}>{t("reason")}<textarea id={`${id}-reason`} required minLength={3} maxLength={240} value={draft.reason} onChange={event=>update("reason",event.currentTarget.value)} /></label>
          <label className={styles.confirm} htmlFor={`${id}-confirm`}><input id={`${id}-confirm`} type="checkbox" required checked={draft.confirmed} onChange={event=>update("confirmed",event.currentTarget.checked)} />{t("confirm")}</label>
          <div className={styles.actions}><button type="submit">{t("save")}</button><button type="button" onClick={cancel}>{t("cancel")}</button></div>
        </fieldset></form>}
        <h5>{t("history")}</h5>{context.hasMoreEvidence&&<p className={styles.warning}>{t("historyLimit")}</p>}{!context.evidence.length&&<p className={styles.empty}>{t("empty")}</p>}
        <ul className={styles.records}>{context.evidence.map(row=><li key={row.id}><EvidenceCard evidence={row} accounts={context.bankAccounts} locale={language} />
          {row.canReject&&context.canReject&&<button type="button" disabled={blocked||formOpen||rejectId!==null} onClick={()=>{if(!blocked){setRejectId(row.id);setRejectReason("");setRejectConfirmed(false);setError(null);setAck(null);}}}>{t("reject")}</button>}
          {selected?.id===row.id&&row.canReject&&<form onSubmit={reject} aria-label={t("rejectForm")}><fieldset disabled={blocked}><legend>{t("rejectionTitle")}</legend><p className={styles.boundary}>{t("rejectionBoundary")}</p><label htmlFor={`${id}-reject-reason`}>{t("rejectReason")}<textarea id={`${id}-reject-reason`} required minLength={3} maxLength={240} value={rejectReason} onChange={event=>{setRejectReason(event.currentTarget.value);setRejectConfirmed(false);}} /></label><label className={styles.confirm} htmlFor={`${id}-reject-confirm`}><input id={`${id}-reject-confirm`} type="checkbox" required checked={rejectConfirmed} onChange={event=>setRejectConfirmed(event.currentTarget.checked)} />{t("rejectConfirm")}</label><div className={styles.actions}><button type="submit">{t("rejectSave")}</button><button type="button" onClick={cancel}>{t("cancel")}</button></div></fieldset></form>}
        </li>)}</ul>
      </>}
    </>}
  </section>;
}
function Fact({label,value}:{label:string;value:ReactNode}){return <div><dt>{label}</dt><dd>{value}</dd></div>;}
function ExactTime({value}:{value:string}){return <time dateTime={value}>{value}</time>;}
function EvidenceCard({evidence,accounts,locale}:{evidence:SettlementBankEvidence;accounts:SettlementBankContext["bankAccounts"];locale:"en"|"hi"}){
  const t=(key:SettlementBankMessageKey)=>settlementBankMessage(locale,key),account=accounts.find(row=>row.id===evidence.bankAccountId);
  return <article className={styles.card}><p className={styles.state} data-status={evidence.status}>{t(`status.${evidence.status}`)}</p><dl className={styles.facts}><Fact label={t("recordId")} value={evidence.id} /><Fact label={t("accountHistory")} value={account?`${account.code} · ${account.name}`:evidence.bankAccountId} /><Fact label={t("creditReference")} value={evidence.bankReference} /><Fact label={t("creditedAmount")} value={`${evidence.currencyCode} ${formatSettlementAmount(evidence.creditedAmount,locale)}`} /><Fact label={t("creditedAt")} value={<ExactTime value={evidence.actualCreditedAt} />} /><Fact label={t("creditedOn")} value={evidence.creditedOn} /><Fact label={t("verification")} value={t(`verification.${evidence.verificationKind}`)} /><Fact label={t("recordedAt")} value={<ExactTime value={evidence.recordedAt} />} /><Fact label={t("recordedBy")} value={evidence.recordedBy} /><Fact label={t("recordReason")} value={evidence.reason} /></dl><h6>{t("reviewReasons")}</h6><ul className={styles.reasons}>{evidence.reviewReasons.map(reason=><li key={reason}>{t(`reason.${reason}`)}</li>)}</ul>{evidence.rejection&&<div className={styles.warning}><dl className={styles.facts}><Fact label={t("rejectionReason")} value={evidence.rejection.reason} /><Fact label={t("rejectedAt")} value={<ExactTime value={evidence.rejection.rejectedAt} />} /><Fact label={t("rejectedBy")} value={evidence.rejection.rejectedBy} /></dl></div>}</article>;
}
