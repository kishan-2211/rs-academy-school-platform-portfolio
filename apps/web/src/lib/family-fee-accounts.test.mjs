import assert from "node:assert/strict";
import test from "node:test";
import * as accounts from "./family-fee-accounts.ts";

const learner="7a000000-0000-4000-8000-000000000001",account="7a000000-0000-4000-8000-000000000002",session="7a000000-0000-4000-8000-000000000003",other="7a000000-0000-4000-8000-000000000004";
const item=changes=>({id:account,sessionId:session,sessionName:"Synthetic historical session",status:"CLOSED",currencyCode:"INR",balance:-29.29,currentSession:false,...changes});
const page=changes=>({learnerId:learner,currentAccountId:other,page:0,pageSize:20,hasMore:false,accounts:[item()],...changes});
test("signed real balances preserve credit, zero and exact paise without treating holds as dues",()=>{
 for(const[value,paise]of[[-29.29,-2929],["0.00",0],["10.01",1001],["-0.01",-1]])assert.equal(accounts.signedFeePaise(value),paise);
 for(const value of["1e2","1.001",null,undefined,Infinity,0.1+0.2,"-99999999999999"])assert.throws(()=>accounts.signedFeePaise(value));
 assert.equal(accounts.readFamilyFeeAccounts(page(),learner,0).accounts[0].balance,-29.29);
});
test("current account can be missing from requested page or null without activating a past account",()=>{
 assert.equal(accounts.readFamilyFeeAccounts(page(),learner,0).currentAccountId,other);
 assert.equal(accounts.readFamilyFeeAccounts(page({currentAccountId:null}),learner,0).accounts[0].currentSession,false);
 assert.equal(accounts.readFamilyFeeAccounts(page({currentAccountId:account,accounts:[item({currentSession:true})]}),learner,0).accounts[0].status,"CLOSED");
});
test("wrong child, malformed pages, duplicate accounts and invented statuses fail closed",()=>{
 for(const changed of[{learnerId:other},{page:1},{pageSize:50},{hasMore:true},{currentAccountId:"../account"},{accounts:[item(),item()]},{accounts:[item({status:"REFUNDED"})]},{accounts:[item({currentSession:true})]},{accounts:[item({currencyCode:"inr"})]},{accounts:[item({sessionId:"../session"})]}])assert.throws(()=>accounts.readFamilyFeeAccounts(page(changed),learner,0));
 assert.throws(()=>accounts.readFamilyFeeAccounts(page(),learner,-1));assert.throws(()=>accounts.readFamilyFeeAccounts(page(),learner,0,51));
});
test("actual private GET is child-scoped bounded no-store with caller cancellation",async t=>{
 const calls=[],signal=new AbortController().signal;t.mock.method(globalThis,"fetch",async(url,options)=>{calls.push({url,options});return Response.json(page());});
 assert.equal((await accounts.loadFamilyFeeAccounts(learner,0,signal)).accounts.length,1);
 assert.ok(calls[0].url.endsWith(`/api/v1/family/children/${learner}/fee-accounts?page=0&pageSize=20`));assert.equal(calls[0].options.credentials,"include");assert.equal(calls[0].options.cache,"no-store");assert.equal(calls[0].options.signal,signal);
 for(const[child,p]of[["../child",0],[learner,-1],[learner,10001]])await assert.rejects(accounts.loadFamilyFeeAccounts(child,p,signal));assert.equal(calls.length,1);
});
test("auth failures are typed and private diagnostics never become public messages",async t=>{
 for(const status of[401,403,404]){t.mock.method(globalThis,"fetch",async()=>new Response("SYNTHETIC PRIVATE RECORD",{status}));await assert.rejects(accounts.loadFamilyFeeAccounts(learner,0,new AbortController().signal),error=>error instanceof accounts.FamilyFeeAccountAccessError&&error.status===status&&!error.message.includes("RECORD"));}
 assert.equal(accounts.familyFeeAccountError(new Error("SYNTHETIC PRIVATE RECORD")),accounts.familyFeeAccountErrors.unavailable);
});
