"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { useLanguage } from "@/components/i18n/language-provider";
import { formatSettlementAmount } from "@/lib/gateway-settlements";
import type { SettlementSourceReviewRow } from "@/lib/gateway-settlement-source-review";
import { loadSettlementRefundCandidates, settlementRefundCandidateErrorCode, type SettlementRefundCandidates, type SettlementRefundCandidateErrorCode } from "@/lib/gateway-settlement-refund-candidates";
import { settlementRefundCandidateMessage, type SettlementRefundCandidateMessageKey } from "@/lib/i18n/gateway-settlement-refund-candidate-messages";
import styles from "./settlement-source-review-panel.module.css";

type Props = { schoolId:string;requestId:string;settlementReference:string;row:SettlementSourceReviewRow;disabled:boolean;onGuardChange:(key:object,blocked:boolean)=>void };
type Context = Omit<Props,"onGuardChange">;
type Snapshot = {context:Context;data:SettlementRefundCandidates|null;error:SettlementRefundCandidateErrorCode|null};

export function SettlementRefundCandidatesPanel({schoolId,requestId,settlementReference,row,disabled,onGuardChange}:Props) {
  const {locale}=useLanguage(),language=locale==="hi"?"hi":"en",id=useId();
  const t=(key:SettlementRefundCandidateMessageKey)=>settlementRefundCandidateMessage(language,key);
  const context=useMemo<Context>(()=>({schoolId,requestId,settlementReference,row,disabled}),[schoolId,requestId,settlementReference,row,disabled]);
  const [snapshot,setSnapshot]=useState<Snapshot|null>(null),[pending,setPending]=useState<Context|null>(null);
  const read=useRef<AbortController|null>(null),lifecycle=useRef(0);
  const busy=!disabled&&pending===context;
  const active=!disabled&&snapshot?.context===context?snapshot:null,review=active?.data??null;
  useEffect(()=>{
    lifecycle.current+=1;
    return()=>{lifecycle.current+=1;read.current?.abort();read.current=null;onGuardChange(context,false);};
  },[context,onGuardChange]);
  const load=useCallback(async(page:number)=>{
    if(disabled||read.current)return;
    const controller=new AbortController(),epoch=lifecycle.current;
    read.current=controller;setPending(context);setSnapshot(null);onGuardChange(context,true);
    try {
      const data=await loadSettlementRefundCandidates(schoolId,requestId,settlementReference,row,page,controller.signal);
      if(!controller.signal.aborted&&epoch===lifecycle.current)setSnapshot({context,data,error:null});
    } catch(failure) {
      if(!controller.signal.aborted&&epoch===lifecycle.current)setSnapshot({context,data:null,error:settlementRefundCandidateErrorCode(failure)});
    } finally {
      if(read.current===controller){read.current=null;setPending(null);onGuardChange(context,false);}
    }
  },[disabled,schoolId,requestId,settlementReference,row,context,onGuardChange]);
  return <section className={styles.root} aria-labelledby={`${id}-heading`} aria-busy={busy}>
    <h6 id={`${id}-heading`}>{t("title")}</h6><p className={styles.boundary}>{t("boundary")}</p>
    {disabled?<p role="status">{t("paused")}</p>:<>
      <button type="button" disabled={busy} onClick={()=>{if(!busy)void load(review?.page??0);}}>{t(review?"refresh":"inspect")}</button>
      {busy&&<p role="status">{t("loading")}</p>}
      {active?.error&&<p role="alert" className={styles.error}>{t(`error.${active.error}`)}</p>}
      {!busy&&!active&&<p>{t("idle")}</p>}
      {review&&<>
        {review.receiptNumber&&<dl className={styles.facts}><Fact label={t("receipt")} value={review.receiptNumber}/></dl>}
        {review.reviewReasons.length>0&&<div className={styles.warning}><p>{t("reasons")}</p><ul>{review.reviewReasons.map(reason=><li key={reason}>{t(`reason.${reason}`)}</li>)}</ul></div>}
        {review.state==="CANDIDATE_CONTEXT"&&!review.items.length&&<p className={styles.empty}>{t("empty")}</p>}
        <ul className={styles.rows}>{review.items.map(item=><li key={item.refundPostingId} className={styles.card}>
          <p className={styles.warning}>{t(`accounting.${item.accountingState}`)}</p><p>{t(`reference.${item.referenceMention}`)}</p>
          <dl className={styles.facts}>
            <Fact label={t("amount")} value={formatSettlementAmount(item.amount,language)}/><Fact label={t("currency")} value={item.currencyCode}/>
            <Fact label={t("postedOn")} value={<time dateTime={item.postedOn}>{item.postedOn}</time>}/><Fact label={t("processedAt")} value={item.processedAt?<time dateTime={item.processedAt}>{item.processedAt}</time>:t("notReported")}/>
            <Fact label={t("postingId")} value={item.refundPostingId}/><Fact label={t("refundId")} value={item.refundId}/>
            {item.accountingJournalId&&<Fact label={t("journalId")} value={item.accountingJournalId}/>}
          </dl>
        </li>)}</ul>
        {review.state==="CANDIDATE_CONTEXT"&&<><p>{t("page")} {review.page+1}</p><nav className={styles.pages} aria-label={t("pagination")}>
          <button type="button" disabled={busy||review.page===0} onClick={()=>{if(!busy)void load(review.page-1);}}>{t("previous")}</button>
          <button type="button" disabled={busy||!review.hasMore} onClick={()=>{if(!busy)void load(review.page+1);}}>{t("next")}</button>
        </nav></>}
      </>}
    </>}
  </section>;
}
function Fact({label,value}:{label:string;value:ReactNode}) { return <div><dt>{label}</dt><dd>{value}</dd></div>; }
