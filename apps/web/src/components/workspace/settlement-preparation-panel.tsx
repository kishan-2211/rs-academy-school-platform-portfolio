"use client";

import {useCallback,useEffect,useId,useMemo,useRef,useState,type FormEvent,type ReactNode} from "react";
import {useLanguage} from "@/components/i18n/language-provider";
import {familyPaymentCsrf} from "@/lib/family-gateway-payments";
import {formatSettlementAmount} from "@/lib/gateway-settlements";
import {loadSettlementRefundCandidates,settlementRefundCandidateErrorCode,type SettlementRefundCandidate,type SettlementRefundCandidates} from "@/lib/gateway-settlement-refund-candidates";
import {componentReportedAmount,loadSettlementPreparationContext,newSettlementPreparationDraft,prepareSettlementAbandonment,prepareSettlementJournal,sendSettlementPreparation,settlementComponentAccounts,settlementComponentModes,settlementPaise,settlementPhysicalRow,settlementPreparationErrorCode,SettlementPreparationWriteError,type SettlementComponentDraft,type SettlementPreparation,type SettlementPreparationAttempt,type SettlementPreparationContext,type SettlementPreparationDetail,type SettlementPreparationDraft,type SettlementPreparationErrorCode,type SettlementPreparationRow} from "@/lib/gateway-settlement-preparations";
import {settlementPreparationMessage} from "@/lib/i18n/gateway-settlement-preparation-messages";
import {SettlementPreparationProvenance,SettlementPreparationSummary} from "./settlement-preparation-provenance";
import styles from "./settlement-bank-evidence-panel.module.css";

type Props={schoolId:string;requestId:string;settlementReference:string;disabled:boolean;onGuardChange:(blocked:boolean)=>void};
type Scope=Omit<Props,"onGuardChange">;
type Snapshot={scope:Scope;data:SettlementPreparationContext};
type Mode={kind:"PREPARE"}|{kind:"ABANDON";preparation:SettlementPreparation};

export function SettlementPreparationPanel({schoolId,requestId,settlementReference,disabled,onGuardChange}:Props){
  const {locale}=useLanguage(),language=locale==="hi"?"hi":"en",id=useId(),t=(key:string)=>settlementPreparationMessage(language,key);
  const scope=useMemo<Scope>(()=>({schoolId,requestId,settlementReference,disabled}),[schoolId,requestId,settlementReference,disabled]);
  const[snapshot,setSnapshot]=useState<Snapshot|null>(null),[pending,setPending]=useState<Scope|null>(null),[draft,setDraft]=useState<SettlementPreparationDraft>(newSettlementPreparationDraft),[mode,setMode]=useState<Mode|null>(null),[locked,setLocked]=useState(false),[saving,setSaving]=useState(false),[childBusy,setChildBusy]=useState(false),[error,setError]=useState<{scope:Scope;code:SettlementPreparationErrorCode}|null>(null),[notice,setNotice]=useState<{scope:Scope;kind:"recorded"|"stale";preparation:SettlementPreparation|null}|null>(null),[releaseReason,setReleaseReason]=useState(""),[releaseConfirmed,setReleaseConfirmed]=useState(false);
  const read=useRef<AbortController|null>(null),writing=useRef(false),attempt=useRef<SettlementPreparationAttempt|null>(null),uncertain=useRef(false),lifecycle=useRef(0),form=useRef<Mode|null>(null),childReads=useRef(new Set<object>()),fingerprint=useRef<{schoolId:string;requestId:string;value:string}|null>(null),feedback=useRef<HTMLParagraphElement|null>(null),errorFeedback=useRef<HTMLParagraphElement|null>(null);
  const context=!disabled&&snapshot?.scope===scope&&snapshot.data.schoolId===schoolId&&snapshot.data.requestId===requestId&&snapshot.data.settlementReference===settlementReference?snapshot.data:null,busy=!disabled&&pending===scope,blocked=disabled||busy||saving||locked||childBusy,activeNotice=!disabled&&notice?.scope===scope?notice:null;
  const guard=useCallback(()=>onGuardChange(read.current!==null||writing.current||attempt.current!==null||form.current!==null||childReads.current.size>0),[onGuardChange]);
  useEffect(()=>{lifecycle.current+=1;const children=childReads.current;return()=>{lifecycle.current+=1;read.current?.abort();read.current=null;children.clear();if(!attempt.current){form.current=null;onGuardChange(false);}};},[scope,onGuardChange]);
  useEffect(()=>{
    if(!locked)return;
    const leave=(event:BeforeUnloadEvent)=>{event.preventDefault();event.returnValue="";};
    const click=(event:MouseEvent)=>{const anchor=event.target instanceof Element?event.target.closest("a[href]"):null;if(anchor&&!anchor.getAttribute("href")?.startsWith("#")){event.preventDefault();event.stopPropagation();feedback.current?.focus();}};
    const navigation=(window as Window&{navigation?:EventTarget}).navigation,navigate=(event:Event)=>{if(event.cancelable&&!(event as Event&{hashChange?:boolean}).hashChange){event.preventDefault();feedback.current?.focus();}};
    window.addEventListener("beforeunload",leave);document.addEventListener("click",click,true);navigation?.addEventListener("navigate",navigate);
    return()=>{window.removeEventListener("beforeunload",leave);document.removeEventListener("click",click,true);navigation?.removeEventListener("navigate",navigate);};
  },[locked]);
  useEffect(()=>{if(activeNotice)feedback.current?.focus();},[activeNotice]);
  useEffect(()=>{if(error?.scope===scope)errorFeedback.current?.focus();},[error,scope]);
  const childGuard=useCallback((key:object,value:boolean)=>{if(value)childReads.current.add(key);else childReads.current.delete(key);setChildBusy(childReads.current.size>0);guard();},[guard]);
  const load=useCallback(async(page:number)=>{
    if(disabled||read.current||writing.current||attempt.current||childReads.current.size)return;
    const controller=new AbortController(),epoch=lifecycle.current;read.current=controller;setPending(scope);setError(null);guard();
    try{
      const data=await loadSettlementPreparationContext(schoolId,requestId,settlementReference,page,controller.signal);
      if(controller.signal.aborted||epoch!==lifecycle.current)return;
      const previous=fingerprint.current;
      if(previous&&(previous.schoolId!==schoolId||previous.requestId!==requestId||previous.value!==data.contextFingerprint)){
        setDraft(newSettlementPreparationDraft());form.current=null;setMode(null);setNotice({scope,kind:"stale",preparation:null});
      }
      fingerprint.current={schoolId,requestId,value:data.contextFingerprint};setSnapshot({scope,data});
    }catch(failure){if(!controller.signal.aborted&&epoch===lifecycle.current){setSnapshot(null);setError({scope,code:settlementPreparationErrorCode(failure)});}}
    finally{if(read.current===controller){read.current=null;setPending(null);guard();}}
  },[disabled,scope,schoolId,requestId,settlementReference,guard]);

  function begin(detail?:SettlementPreparationDetail){
    if(blocked||!context?.canPrepare||attempt.current)return;
    const next=newSettlementPreparationDraft();
    if(detail){
      const previous=detail.preparation;if(previous.requestId!==requestId||!["REJECTED","REVERSED"].includes(previous.status))return;
      next.bankEvidenceId=previous.bankEvidenceId;next.previousPreparationId=previous.id;next.componentBasis=detail.componentBasis;
      next.componentReviews=detail.componentReviews.map(row=>({...row,accountId:row.accountId??"",evidenceReference:row.evidenceReference??""}));
      next.refundAssociations=detail.refundAssociations.map(row=>({...row}));
    }
    setDraft(next);form.current={kind:"PREPARE"};setMode(form.current);setError(null);setNotice(null);guard();
  }
  function cancel(){if(blocked)return;form.current=null;setMode(null);setDraft(newSettlementPreparationDraft());setReleaseReason("");setReleaseConfirmed(false);setError(null);guard();}
  function update<K extends keyof SettlementPreparationDraft>(key:K,value:SettlementPreparationDraft[K]){
    if(blocked)return;setDraft(current=>({...current,[key]:value,...(key!=="confirmedReviewed"?{confirmedReviewed:false}:{}),...(key==="bankEvidenceId"?{confirmedSummaryCurrency:false}:{})}));setError(null);
  }
  function component(index:number,key:keyof SettlementComponentDraft,value:string){
    if(blocked)return;setDraft(current=>({...current,confirmedReviewed:false,componentReviews:current.componentReviews.map((row,n)=>n===index?{...row,[key]:value,...(key==="mode"&&value==="CONFIRMED_ZERO"?{postedAmount:"0.00",accountId:"",direction:row.direction||"DEBIT"}:{}),...(key==="postedAmount"&&/^0(?:\.0{1,2})?$/.test(value)?{accountId:""}:{})}:row)}));setError(null);
  }
  function applyReportedAmount(index:number){
    if(blocked||!context)return;const fact=context.componentFacts.find(row=>row.key===draft.componentReviews[index]?.key);if(!fact)return;
    const value=componentReportedAmount(fact,draft.componentBasis);if(value===null||!exactPaise(value))return;
    const negative=value.startsWith("-"),absolute=(negative?value.slice(1):value),[whole,fraction=""]=absolute.split("."),amount=whole+"."+fraction.slice(0,2).padEnd(2,"0");
    setDraft(current=>({...current,confirmedReviewed:false,componentReviews:current.componentReviews.map((row,n)=>n===index?{...row,mode:"REPORTED",postedAmount:amount,direction:row.key==="ADJUSTMENT"&&amount!=="0.00"?row.direction:negative?"CREDIT":"DEBIT",accountId:amount==="0.00"?"":row.accountId,evidenceReference:amount==="0.00"?"":row.evidenceReference||settlementReference,reason:row.reason||current.reason}:row)}));setError(null);
  }
  function association(row:SettlementPreparationRow,candidate:SettlementRefundCandidate|null){
    if(blocked||draft.previousPreparationId||candidate&&candidate.accountingState!=="POSTED_SOURCE_PROVEN")return;const identity=settlementPhysicalRow(row);setDraft(current=>({...current,confirmedReviewed:false,refundAssociations:[...current.refundAssociations.filter(value=>settlementPhysicalRow(value)!==identity),...(candidate?[{sourceReadId:row.sourceReadId,rowIndex:row.rowIndex,refundPostingId:candidate.refundPostingId,reason:""}]:[])]}));
  }
  function associationReason(row:SettlementPreparationRow,value:string){if(blocked)return;update("refundAssociations",draft.refundAssociations.map(item=>settlementPhysicalRow(item)===settlementPhysicalRow(row)?{...item,reason:value}:item));}
  async function dispatch(next:SettlementPreparationAttempt){
    if(disabled||read.current||writing.current||childReads.current.size||attempt.current&&attempt.current!==next)return;
    attempt.current=next;writing.current=true;setLocked(true);setSaving(true);setError(null);setNotice(null);guard();
    const epoch=lifecycle.current;let recorded:SettlementPreparation|null=null;
    try{
      const csrf=await familyPaymentCsrf(new AbortController().signal).catch(()=>{throw new SettlementPreparationWriteError("rejected","csrf");});
      if(epoch!==lifecycle.current)return;
      recorded=await sendSettlementPreparation(next,csrf);
      if(epoch!==lifecycle.current)return;
      attempt.current=null;uncertain.current=false;setLocked(false);form.current=null;setMode(null);setDraft(newSettlementPreparationDraft());setNotice({scope,kind:"recorded",preparation:recorded});
    }catch(failure){
      if(epoch!==lifecycle.current)return;
      if(!(failure instanceof SettlementPreparationWriteError)||failure.outcome==="unknown"||uncertain.current){uncertain.current=true;setError({scope,code:"unknown"});}
      else{attempt.current=null;setLocked(false);setDraft(current=>({...current,confirmedReviewed:false}));setReleaseConfirmed(false);setError({scope,code:settlementPreparationErrorCode(failure)});}
    }finally{writing.current=false;if(epoch===lifecycle.current)setSaving(false);guard();}
    if(recorded&&epoch===lifecycle.current)await load(context?.page??0);
  }
  function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();if(blocked||!context)return;try{void dispatch(prepareSettlementJournal(context,draft,crypto.randomUUID()));}catch(failure){setError({scope,code:settlementPreparationErrorCode(failure)});}}
  function release(event:FormEvent<HTMLFormElement>){event.preventDefault();if(blocked||mode?.kind!=="ABANDON")return;try{void dispatch(prepareSettlementAbandonment(schoolId,mode.preparation,crypto.randomUUID(),releaseReason,releaseConfirmed));}catch(failure){setError({scope,code:settlementPreparationErrorCode(failure)});}}
  const year=context?.accountingOptions.financialYears.find(row=>row.id===draft.financialYearId),bank=context?.bankEvidence.find(row=>row.id===draft.bankEvidenceId),equation=context?previewEquation(context,draft):null;
  const repair=Boolean(draft.previousPreparationId),latest=(row:SettlementPreparation)=>!context?.preparations.some(other=>other.rootId===row.rootId&&other.revision>row.revision);
  return<section className={styles.root} aria-labelledby={id+"-heading"} aria-busy={busy||saving||childBusy}>
    <h4 id={id+"-heading"}>{t("title")}</h4><p>{t("intro")}</p><p className={styles.boundary}>{t("boundary")}</p><p>{t("statement")}: <strong>{settlementReference}</strong></p>
    {disabled?<p role="status">{t("paused")}</p>:<>
      {!locked&&<button type="button" disabled={busy||saving||childBusy||mode!==null} onClick={()=>void load(context?.page??0)}>{t(context?"refresh":"inspect")}</button>}
      {(busy||saving)&&<p role="status">{t(saving?"saving":"loading")}</p>}
      {error?.scope===scope&&<p ref={errorFeedback} tabIndex={-1} role="alert" className={styles.error}>{t("error."+error.code)}</p>}
      <p ref={feedback} tabIndex={-1} role="status" className={styles.feedback} hidden={!locked&&!activeNotice}>{locked?t("navigationBlocked"):activeNotice?t(activeNotice.kind):""}</p>
      {locked&&<div className={styles.warning}><p>{t("unknown")}</p><button type="button" disabled={saving} onClick={()=>{if(!saving&&attempt.current)void dispatch(attempt.current);}}>{t("retry")}</button></div>}
      {!busy&&!context&&!error&&!activeNotice&&<p>{t("idle")}</p>}
      {activeNotice?.preparation&&<SettlementPreparationSummary preparation={activeNotice.preparation} locale={language}/>}
      {context&&<>
        <dl className={styles.facts}><Fact label={t("events")} value={context.totalEvents}/><Fact label={t("refundEvents")} value={context.totalRefundEvents}/><Fact label={t("paymentTotal")} value={amount(context.paymentTotal,language,t("unknownValue"))}/><Fact label={t("refundTotal")} value={amount(context.refundTotal,language,t("unknownValue"))}/></dl>
        <p>{t("totalsHelp")}</p>{context.paymentTotal===null&&<p className={styles.warning}>{t("unknownTotals")}</p>}
        {context.blockers.length>0&&<div className={styles.warning}><h5>{t("blockers")}</h5><ul>{context.blockers.map(code=><li key={code}>{t("blocker."+code)}</li>)}</ul></div>}
        {!context.canPrepare&&<p>{t("readOnly")}</p>}
        {context.canPrepare&&!mode&&<button type="button" disabled={blocked} onClick={()=>begin()}>{t("newDraft")}</button>}
        {mode?.kind==="PREPARE"&&<form onSubmit={submit} aria-label={t("form")}><fieldset disabled={blocked}><legend>{t("form")}</legend>
          {repair&&<p className={styles.warning}>{t("repairHelp")} <strong>{draft.previousPreparationId}</strong></p>}
          <label htmlFor={id+"-bank"}>{t("bank")}<select id={id+"-bank"} required value={draft.bankEvidenceId} disabled={repair} onChange={event=>update("bankEvidenceId",event.currentTarget.value)}><option value="">{t("chooseBank")}</option>{context.bankEvidence.filter(row=>row.status==="BANK_EVIDENCE_RECORDED").map(row=><option key={row.id} value={row.id}>{row.currencyCode+" "+row.creditedAmount+" · "+row.bankReference+" · "+row.creditedOn}</option>)}</select></label>
          {bank&&<dl className={styles.facts}><Fact label={t("bankAmount")} value={amount(bank.creditedAmount,language,t("unknownValue"))}/><Fact label={t("reference")} value={bank.bankReference}/></dl>}
          <div className={styles.fields}><label htmlFor={id+"-year"}>{t("year")}<select id={id+"-year"} value={draft.financialYearId} required onChange={event=>update("financialYearId",event.currentTarget.value)}><option value="">{t("chooseYear")}</option>{context.accountingOptions.financialYears.map(row=><option key={row.id} value={row.id}>{row.code+" · "+row.name}</option>)}</select></label><label htmlFor={id+"-date"}>{t("postingOn")}<input id={id+"-date"} type="date" required min={year?.startsOn} max={year?.endsOn} value={draft.postingOn} onChange={event=>update("postingOn",event.currentTarget.value)} aria-describedby={id+"-date-help"}/></label></div>
          <p id={id+"-date-help"}>{t("dateHelp")}</p><label className={styles.confirm} htmlFor={id+"-currency"}><input id={id+"-currency"} type="checkbox" required checked={draft.confirmedSummaryCurrency} onChange={event=>update("confirmedSummaryCurrency",event.currentTarget.checked)}/>{t("currencyConfirm")}</label>
          <label htmlFor={id+"-reason"}>{t("reason")}<textarea id={id+"-reason"} required minLength={3} maxLength={240} value={draft.reason} onChange={event=>update("reason",event.currentTarget.value)}/></label>
          <label htmlFor={id+"-basis"}>{t("basis")}<select id={id+"-basis"} required value={draft.componentBasis} onChange={event=>update("componentBasis",event.currentTarget.value as SettlementPreparationDraft["componentBasis"])}><option value="">{t("chooseBasis")}</option><option value="SUMMARY">{t("summaryBasis")}</option><option value="EVENTS">{t("eventsBasis")}</option></select></label><p>{t("basisHelp")}</p>
          <h5>{t("components")}</h5><p>{t("modeHelp")}</p>
          {draft.componentReviews.map((row,index)=>{const fact=context.componentFacts.find(item=>item.key===row.key)!,raw=componentReportedAmount(fact,draft.componentBasis),prefix=id+"-component-"+index,zero=/^0(?:\.0{1,2})?$/.test(row.postedAmount);return<fieldset key={row.key}><legend>{t("component."+row.key)}</legend><dl className={styles.facts}><Fact label={t("reportedSummary")} value={amount(fact.summaryAmount,language,t("unknownValue"))}/><Fact label={t("reportedEvents")} value={fact.eventsComplete?amount(fact.eventsAmount,language,t("unknownValue")):t("unknownValue")}/><Fact label={t("selectedReported")} value={amount(raw,language,t("unknownValue"))}/></dl>
            <button type="button" disabled={!draft.componentBasis||raw===null||!exactPaise(raw)} onClick={()=>applyReportedAmount(index)}>{t("useReported")}</button>
            <label htmlFor={prefix+"-mode"}>{t("componentMode")}<select id={prefix+"-mode"} required value={row.mode} onChange={event=>component(index,"mode",event.currentTarget.value)}><option value="">{t("chooseMode")}</option>{settlementComponentModes.map(mode=><option key={mode} value={mode}>{t({REPORTED:"modeReported",ROUNDING_REVIEW:"modeRounding",CONFIRMED_ZERO:"modeZero",EXTERNAL_REVIEW:"modeExternal"}[mode])}</option>)}</select></label>
            <div className={styles.fields}><label htmlFor={prefix+"-direction"}>{t("direction")}<select id={prefix+"-direction"} required value={row.direction} onChange={event=>component(index,"direction",event.currentTarget.value)}><option value="">{t("chooseDirection")}</option><option value="DEBIT">{t("debit")}</option><option value="CREDIT">{t("credit")}</option></select></label><label htmlFor={prefix+"-amount"}>{t("amount")}<input id={prefix+"-amount"} type="text" inputMode="decimal" maxLength={12} required value={row.postedAmount} onChange={event=>component(index,"postedAmount",event.currentTarget.value)}/></label></div>
            <label htmlFor={prefix+"-account"}>{t("componentAccount")}<select id={prefix+"-account"} disabled={zero} required={!zero} value={row.accountId} onChange={event=>component(index,"accountId",event.currentTarget.value)}><option value="">{t("chooseAccount")}</option>{settlementComponentAccounts(context.accountingOptions,row.key).map(account=><option key={account.id} value={account.id}>{account.code+" · "+account.name}</option>)}</select></label>
            <label htmlFor={prefix+"-reference"}>{t("reference")}<input id={prefix+"-reference"} type="text" maxLength={120} autoComplete="off" required={!zero||row.mode==="ROUNDING_REVIEW"} value={row.evidenceReference} onChange={event=>component(index,"evidenceReference",event.currentTarget.value)}/></label>
            <label htmlFor={prefix+"-reason"}>{t("componentReason")}<textarea id={prefix+"-reason"} required minLength={3} maxLength={240} value={row.reason} onChange={event=>component(index,"reason",event.currentTarget.value)}/></label>
          </fieldset>;})}
          <p>{t("noCredentials")}</p>
          <h5>{t("equation")}</h5><p>{t("equationHelp")}</p><p className={equation===false?styles.warning:styles.boundary}>{t(equation===null?"equationPending":equation?"equationBalanced":"equationMismatch")}</p>
          <p>{t("refundCount")}: <strong>{draft.refundAssociations.length+" / "+context.totalRefundEvents}</strong></p>
          <label className={styles.confirm} htmlFor={id+"-confirmed"}><input id={id+"-confirmed"} type="checkbox" required checked={draft.confirmedReviewed} onChange={event=>update("confirmedReviewed",event.currentTarget.checked)}/>{t("confirmed")}</label>
          <div className={styles.actions}><button type="submit">{t("prepare")}</button><button type="button" onClick={cancel}>{t("cancel")}</button></div>
        </fieldset></form>}
        {mode?.kind==="ABANDON"&&<form onSubmit={release} aria-label={t("releaseTitle")}><fieldset disabled={blocked}><legend>{t("releaseTitle")}</legend><p className={styles.boundary}>{t("releaseBoundary")}</p><SettlementPreparationSummary preparation={mode.preparation} locale={language}/><label htmlFor={id+"-release-reason"}>{t("reason")}<textarea id={id+"-release-reason"} required minLength={3} maxLength={240} value={releaseReason} onChange={event=>{setReleaseReason(event.currentTarget.value);setReleaseConfirmed(false);}}/></label><label className={styles.confirm} htmlFor={id+"-release-confirmed"}><input id={id+"-release-confirmed"} type="checkbox" required checked={releaseConfirmed} onChange={event=>setReleaseConfirmed(event.currentTarget.checked)}/>{t("releaseConfirm")}</label><div className={styles.actions}><button type="submit">{t("releaseSubmit")}</button><button type="button" onClick={cancel}>{t("cancel")}</button></div></fieldset></form>}
        <h5>{t("sourceRows")}</h5><p>{t("sourcePageHelp")}</p>
        <ul className={styles.records}>{context.rows.map(row=><li key={settlementPhysicalRow(row)} className={styles.card}><p className={styles.state}>{t(row.status==="PAYMENT_SOURCE_IDENTIFIED"?"sourceIdentified":row.status==="REFUND_SELECTION_REQUIRED"?"refundSelection":"sourceReview")}</p><h6>{row.event.eventReference}</h6><dl className={styles.facts}><Fact label={t("row")} value={row.sourceReadId+" / "+(row.rowIndex+1)}/><Fact label={t("reportedAmount")} value={row.event.currencyCode+" "+formatSettlementAmount(row.event.reportedAmount,language)}/>{row.source&&<Fact label={t("receipt")} value={row.source.receiptNumber}/>}</dl>{row.reasons.length>0&&<ul>{row.reasons.map(reason=><li key={reason}>{t("blocker."+reason)}</li>)}</ul>}{mode?.kind==="PREPARE"&&row.status==="REFUND_SELECTION_REQUIRED"&&<RefundChoice schoolId={schoolId} requestId={requestId} settlementReference={settlementReference} row={row} selected={draft.refundAssociations.find(value=>settlementPhysicalRow(value)===settlementPhysicalRow(row))??null} disabled={disabled||busy||locked||saving} immutable={repair} onChoose={candidate=>association(row,candidate)} onReason={value=>associationReason(row,value)} onGuardChange={childGuard}/>}</li>)}</ul>
        <div className={styles.actions}><button type="button" disabled={blocked||context.page===0} onClick={()=>void load(context.page-1)}>{t("previousRows")}</button><span>{t("page")+" "+(context.page+1)}</span><button type="button" disabled={blocked||!context.hasMore} onClick={()=>void load(context.page+1)}>{t("nextRows")}</button></div>
        <h5>{t("history")}</h5>{!context.preparations.length&&<p className={styles.empty}>{t("emptyHistory")}</p>}
        <ul className={styles.records}>{context.preparations.map(row=><li key={row.id} className={styles.card}><SettlementPreparationSummary preparation={row} locale={language}/><SettlementPreparationProvenance key={row.id+":"+row.status} schoolId={schoolId} preparationId={row.id} journalId={row.journalId} disabled={blocked||mode!==null} onRepair={context.canPrepare&&latest(row)&&["REJECTED","REVERSED"].includes(row.status)?detail=>begin(detail):undefined}/>{context.canPrepare&&latest(row)&&row.status==="REJECTED"&&!context.preparations.some(other=>other.rootId===row.rootId&&["POSTED","REVERSED"].includes(other.status))&&<button type="button" disabled={blocked||mode!==null} onClick={()=>{form.current={kind:"ABANDON",preparation:row};setMode(form.current);setReleaseReason("");setReleaseConfirmed(false);guard();}}>{t("release")}</button>}</li>)}</ul>
      </>}
    </>}
  </section>;
}
function exactPaise(value:string):boolean {const[whole,fraction=""]=value.replace(/^-/,"").split(".");return /^\d+$/.test(whole!)&&fraction.slice(2).split("").every(digit=>digit==="0");}
function amount(value:string|null,locale:"en"|"hi",unknown:string):string{return value===null?unknown:"INR "+formatSettlementAmount(value,locale);}
function previewEquation(context:SettlementPreparationContext,draft:SettlementPreparationDraft):boolean|null {
  try{if(context.paymentTotal===null||context.refundTotal===null)return null;const bank=context.bankEvidence.find(row=>row.id===draft.bankEvidenceId);if(!bank)return null;let debits=0n,credits=0n;for(const row of draft.componentReviews){if(!/^(?:0|[1-9]\d{0,8})(?:\.\d{1,2})?$/.test(row.postedAmount)||!row.mode||!row.direction)return null;const[whole,fraction=""]=row.postedAmount.split("."),value=settlementPaise(whole+"."+fraction.padEnd(2,"0"));if(row.direction==="DEBIT")debits+=value;else credits+=value;}return settlementPaise(context.paymentTotal)-settlementPaise(context.refundTotal)===settlementPaise(bank.creditedAmount)+debits-credits;}catch{return null;}
}
function Fact({label,value}:{label:string;value:ReactNode}){return<div><dt>{label}</dt><dd>{value}</dd></div>;}
type RefundChoiceProps={schoolId:string;requestId:string;settlementReference:string;row:SettlementPreparationRow;selected:SettlementPreparationDraft["refundAssociations"][number]|null;disabled:boolean;immutable:boolean;onChoose:(candidate:SettlementRefundCandidate|null)=>void;onReason:(value:string)=>void;onGuardChange:(key:object,value:boolean)=>void};
function RefundChoice({schoolId,requestId,settlementReference,row,selected,disabled,immutable,onChoose,onReason,onGuardChange}:RefundChoiceProps){
  const {locale}=useLanguage(),language=locale==="hi"?"hi":"en",id=useId(),t=(key:string)=>settlementPreparationMessage(language,key),context=useMemo(()=>({schoolId,requestId,settlementReference,row}),[schoolId,requestId,settlementReference,row]);
  const[snapshot,setSnapshot]=useState<{context:typeof context;data:SettlementRefundCandidates}|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null),read=useRef<AbortController|null>(null),lifecycle=useRef(0);
  const data=snapshot?.context===context?snapshot.data:null;
  useEffect(()=>{lifecycle.current+=1;return()=>{lifecycle.current+=1;read.current?.abort();read.current=null;onGuardChange(context,false);};},[context,onGuardChange]);
  async function load(page:number){
    if(disabled||read.current)return;const controller=new AbortController(),epoch=lifecycle.current;read.current=controller;setBusy(true);setError(null);onGuardChange(context,true);
    try{const data=await loadSettlementRefundCandidates(schoolId,requestId,settlementReference,row,page,controller.signal);if(!controller.signal.aborted&&epoch===lifecycle.current)setSnapshot({context,data});}catch(failure){if(!controller.signal.aborted&&epoch===lifecycle.current){const code=settlementRefundCandidateErrorCode(failure);setError(code==="filters"?"terms":code);}}finally{if(read.current===controller){read.current=null;setBusy(false);onGuardChange(context,false);}}
  }
  return<section aria-labelledby={id+"-heading"} aria-busy={busy}><h6 id={id+"-heading"}>{t("refundChoices")}</h6><p>{t("refundBoundary")}</p>
    {selected&&<><p>{t("selectedRefund")}: <strong>{selected.refundPostingId}</strong></p><label htmlFor={id+"-reason"}>{t("refundReason")}<textarea id={id+"-reason"} required minLength={3} maxLength={240} disabled={disabled||busy} value={selected.reason} onChange={event=>onReason(event.currentTarget.value)}/></label>{!immutable&&<button type="button" disabled={disabled||busy} onClick={()=>onChoose(null)}>{t("removeRefund")}</button>}</>}
    <button type="button" disabled={disabled||busy} onClick={()=>void load(data?.page??0)}>{t("inspectRefunds")}</button>{busy&&<p role="status">{t("refundLoading")}</p>}{error&&<p role="alert" className={styles.error}>{t("error."+error)}</p>}
    {data&&<>{!data.items.length&&<p className={styles.empty}>{t("noRefunds")}</p>}<ul className={styles.records}>{data.items.map(candidate=><li key={candidate.refundPostingId} className={styles.card}><p>{t(candidate.accountingState==="POSTED_SOURCE_PROVEN"?"postedRefund":"reviewRefund")}</p><p>{t(candidate.referenceMention==="MENTIONED"?"referenceMentioned":candidate.referenceMention==="NOT_MENTIONED"?"referenceNotMentioned":"referenceNotReported")}</p><dl className={styles.facts}><Fact label={t("chosenPosting")} value={candidate.refundPostingId}/><Fact label={t("reportedAmount")} value={candidate.currencyCode+" "+formatSettlementAmount(candidate.amount,language)}/></dl><button type="button" disabled={disabled||busy||immutable||candidate.accountingState!=="POSTED_SOURCE_PROVEN"} aria-pressed={selected?.refundPostingId===candidate.refundPostingId} onClick={()=>onChoose(candidate)}>{t("chooseRefund")}</button></li>)}</ul><div className={styles.actions}><button type="button" disabled={disabled||busy||data.page===0} onClick={()=>void load(data.page-1)}>{t("previous")}</button><span>{t("page")+" "+(data.page+1)}</span><button type="button" disabled={disabled||busy||!data.hasMore} onClick={()=>void load(data.page+1)}>{t("next")}</button></div></>}
  </section>;
}
