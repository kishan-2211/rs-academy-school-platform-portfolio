import assert from "node:assert/strict";
import test from "node:test";
import * as invitations from "./staff-account-invitations.ts";
import { staffInvitationMessage } from "./i18n/staff-invitation-messages.ts";

const id = number => `5f000000-0000-4000-8000-${String(number).padStart(12,"0")}`;
const school=id(1),staff=id(2),invitation=id(3),request=id(4),at="2026-10-29T08:00:00Z",later="2026-10-30T08:00:00Z";
const token="s".repeat(43);
const row={id:invitation,schoolId:school,staffId:staff,roleCode:"teacher",roleName:"Teacher",maskedDestination:"s***@example.invalid",status:"ISSUED",deliveryStatus:"AWAITING_HANDOFF",expiresAt:later,createdAt:at,acceptedAt:null,revokedAt:null,handoffAt:null,rowVersion:0,canRevoke:true,canRevealHandoff:true};
const context={schoolId:school,staffId:staff,manualHandoffEnabled:true,canManage:true,roles:[{code:"teacher",name:"Teacher",capabilities:["people.read"]}],items:[]};
const preview={schoolName:"Synthetic school",maskedDestination:row.maskedDestination,roleCode:"teacher",roleName:"Teacher",expiresAt:later,existingAccount:false,status:"ready"};
const expectCode=code=>error=>error instanceof invitations.StaffInvitationError&&error.code===code;

test("context decoding binds the exact school/employee and refuses leaked or contradictory grants",()=>{
  assert.equal(invitations.readStaffInvitationList({...context,items:[row]},school,staff).items[0],row);
  for(const change of[{schoolId:id(5)},{staffId:id(5)},{manualHandoffEnabled:"yes"},{roles:[{code:"parent",name:"Parent",capabilities:["people.read"]}]},{roles:[context.roles[0],context.roles[0]]},{items:[row,row]},{items:[{...row,schoolId:id(5)}]},{items:[{...row,setupToken:token}]},{manualHandoffEnabled:false,items:[row]},{canManage:false,items:[row]}])assert.throws(()=>invitations.readStaffInvitationList({...context,...change},school,staff),expectCode("unavailable"));
});
test("terminal invitations cannot advertise grants or reveal authority",()=>{
  const accepted={...row,status:"ACCEPTED",deliveryStatus:"CANCELLED",acceptedAt:at,canRevoke:false,canRevealHandoff:false};
  assert.equal(invitations.readStaffAccountInvitation(accepted,school,staff),accepted);
  for(const change of[{canRevoke:true},{canRevealHandoff:true},{acceptedAt:null},{revokedAt:at}])assert.throws(()=>invitations.readStaffAccountInvitation({...accepted,...change},school,staff),expectCode("unavailable"));
});
test("preview is minimal; fragment tokens are neither query values nor retained URLs",()=>{
  assert.equal(invitations.readStaffInvitationPreview(preview),preview);
  assert.equal(invitations.staffInvitationFragment(`#token=${token}`),token);
  for(const hash of["",`?token=${token}`,`#${token}`,`#token=${token}&other=1`,`#token=${token.slice(1)}`,`#token=%73${token.slice(1)}`,`#token=${token}/`])assert.equal(invitations.staffInvitationFragment(hash),null);
  assert.equal(invitations.staffInvitationSetupLink("https://rsacademyschool.in",token),`https://rsacademyschool.in/staff-invitation#token=${token}`);
  assert.equal(invitations.staffInvitationSetupLink("http://localhost:3000",token),`http://localhost:3000/staff-invitation#token=${token}`);
  for(const origin of["http://school.invalid","https://school.invalid/path","https://school.invalid?token=x","javascript:alert(1)","https://user@school.invalid"])assert.throws(()=>invitations.staffInvitationSetupLink(origin,token));
  for(const change of[{setupToken:token},{existingAccount:1},{roleCode:"student"},{schoolName:"Synthetic\nschool"}])assert.throws(()=>invitations.readStaffInvitationPreview({...preview,...change}));
});
test("issuance and revocation freeze a reviewed request and reject scope/version/role substitution",()=>{
  const command=invitations.prepareStaffInvitation(context,3,"teacher",24,"Synthetic approved employment",true,request),body=JSON.parse(command.body);
  assert.equal(Object.isFrozen(command),true);assert.deepEqual(body,{requestId:request,expectedStaffVersion:3,roleCode:"teacher",ttlHours:24,reason:"Synthetic approved employment"});
  for(const [ctx,version,role,hours,reason,confirmed]of[[{...context,canManage:false},3,"teacher",24,"Synthetic reason",true],[{...context,manualHandoffEnabled:false},3,"teacher",24,"Synthetic reason",true],[{...context,items:[row]},3,"teacher",24,"Synthetic reason",true],[context,-1,"teacher",24,"Synthetic reason",true],[context,3,"school_admin",24,"Synthetic reason",true],[context,3,"teacher",169,"Synthetic reason",true],[context,3,"teacher",24,"x",true],[context,3,"teacher",24,"Synthetic reason",false]])assert.throws(()=>invitations.prepareStaffInvitation(ctx,version,role,hours,reason,confirmed,request));
  const listed={...context,items:[row]},revoke=invitations.prepareStaffInvitationAction(listed,row,"REVOKE","Synthetic cancelled invitation",true,request);
  assert.equal(JSON.parse(revoke.body).expectedVersion,0);assert.equal(revoke.invitationId,invitation);
  assert.throws(()=>invitations.prepareStaffInvitationAction(listed,{...row},"REVOKE","Synthetic cancellation",true,request));
});
test("staff history uses authenticated no-store reads without accepting echoed private errors",async()=>{
  const original=globalThis.fetch,calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json(context);};
  try {const signal=new AbortController().signal;await invitations.loadStaffInvitations(school,staff,signal);assert.match(calls[0].url,new RegExp(`/schools/${school}/staff/${staff}/account-invitations$`));assert.equal(calls[0].init.credentials,"include");assert.equal(calls[0].init.cache,"no-store");assert.equal(calls[0].init.signal,signal);
    for(const [status,code]of[[401,"session"],[403,"scope"],[409,"conflict"],[500,"unavailable"]]){globalThis.fetch=async()=>Response.json({detail:token},{status});await assert.rejects(invitations.loadStaffInvitations(school,staff,signal),error=>expectCode(code)(error)&&!error.message.includes(token));}
  }finally{globalThis.fetch=original;}
});
test("unknown issue outcomes keep the exact immutable request and never claim success",async()=>{
  const original=globalThis.fetch,command=invitations.prepareStaffInvitation(context,3,"teacher",24,"Synthetic reason",true,request),calls=[];
  globalThis.fetch=async(url,init)=>{calls.push({url,init});throw new Error("synthetic disconnected response");};
  try{for(let index=0;index<2;index++)await assert.rejects(invitations.sendStaffInvitationCommand(command,"synthetic-csrf"),expectCode("uncertain"));assert.equal(calls[0].init.body,calls[1].init.body);assert.equal(calls[0].init.headers["X-CSRF-TOKEN"],"synthetic-csrf");assert.equal(calls[0].init.credentials,"include");assert.equal(calls[0].init.cache,"no-store");}finally{globalThis.fetch=original;}
});
test("one-use handoff refuses ambiguous, substituted and already-revealed responses",async()=>{
  const original=globalThis.fetch,command=invitations.prepareStaffInvitationAction({...context,items:[row]},row,"HANDOFF","Synthetic private handoff",true,request);
  try{globalThis.fetch=async()=>Response.json({invitationId:invitation,setupToken:token,expiresAt:later});assert.equal((await invitations.sendStaffInvitationCommand(command,"synthetic-csrf")).setupToken,token);
    for(const value of[{invitationId:id(5),setupToken:token,expiresAt:later},{invitationId:invitation,setupToken:"short",expiresAt:later},{invitationId:invitation,setupToken:token,expiresAt:later,password:"synthetic"}]){globalThis.fetch=async()=>Response.json(value);await assert.rejects(invitations.sendStaffInvitationCommand(command,"synthetic-csrf"),expectCode("handoffUncertain"));}
    globalThis.fetch=async()=>Response.json({code:"STAFF_INVITATION_HANDOFF_ALREADY_REVEALED",detail:token},{status:409});await assert.rejects(invitations.sendStaffInvitationCommand(command,"synthetic-csrf"),expectCode("handoffUncertain"));
  }finally{globalThis.fetch=original;}
});
test("existing account acceptance requires the authenticated CSRF endpoint and never submits a password",async()=>{
  const original=globalThis.fetch,calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json({status:"accepted",nextAction:"sign_in"});};
  try{assert.deepEqual(await invitations.acceptStaffInvitation(token,{kind:"existing",csrf:"synthetic-csrf"}),{status:"accepted",nextAction:"sign_in"});const call=calls[0];assert.match(call.url,/\/api\/v1\/staff-account-invitations\/acceptances$/);assert.doesNotMatch(call.url,/public|token=/);assert.deepEqual(JSON.parse(call.init.body),{token});assert.equal(call.init.credentials,"include");assert.equal(call.init.headers["X-CSRF-TOKEN"],"synthetic-csrf");assert.equal(call.init.cache,"no-store");
    for(const code of["STAFF_INVITATION_MFA_REQUIRED","MFA_VERIFICATION_REQUIRED"]){globalThis.fetch=async()=>Response.json({code},{status:403});await assert.rejects(invitations.acceptStaffInvitation(token,{kind:"existing",csrf:"synthetic-csrf"}),expectCode("mfa"));}
  }finally{globalThis.fetch=original;}
});
test("new-account acceptance is separate, validates its password and never returns session assurance",async()=>{
  const original=globalThis.fetch,calls=[],password="synthetic-not-a-login-"+crypto.randomUUID();globalThis.fetch=async(url,init)=>{calls.push({url,init});return Response.json({status:"accepted",nextAction:"sign_in"});};
  try{await invitations.acceptStaffInvitation(token,{kind:"new",password,confirmation:password});assert.match(calls[0].url,/\/api\/v1\/public\/staff-account-invitations\/acceptances$/);assert.equal(calls[0].init.credentials,"omit");assert.equal(calls[0].init.headers["X-CSRF-TOKEN"],undefined);assert.equal(JSON.parse(calls[0].init.body).password,password);
    for(const invalid of["short"," ".repeat(20),"synthetic\npassword-not-valid"]){assert.equal(invitations.validStaffInvitationPassword(invalid),false);await assert.rejects(invitations.acceptStaffInvitation(token,{kind:"new",password:invalid,confirmation:invalid}),expectCode("invalid"));}
    globalThis.fetch=async()=>Response.json({status:"authenticated",nextAction:"workspace",rawToken:token});await assert.rejects(invitations.acceptStaffInvitation(token,{kind:"new",password,confirmation:password}),expectCode("uncertain"));
    globalThis.fetch=async()=>Response.json({code:"STAFF_INVITATION_SIGN_IN_REQUIRED"},{status:409});await assert.rejects(invitations.acceptStaffInvitation(token,{kind:"new",password,confirmation:password}),expectCode("existing"));
  }finally{globalThis.fetch=original;}
});
test("English and Hindi include actionable private delivery, uncertainty and MFA guidance",()=>{
  for(const key of["disabled","membershipWarning","confirmIssue","secretWarning","handoffUncertain","acceptUncertain","errorMfa","existingBoundary","sessionHelp"]){assert.match(staffInvitationMessage("hi",key),/\p{Script=Devanagari}/u);assert.ok(staffInvitationMessage("en",key).length>30);}
});
