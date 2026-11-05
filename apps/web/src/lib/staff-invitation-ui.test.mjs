import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import * as client from "./staff-account-invitations.ts";
import { staffInvitationMessage } from "./i18n/staff-invitation-messages.ts";

const acceptanceSource=readFileSync(new URL("../components/staff-invitation.tsx",import.meta.url),"utf8"),panelSource=readFileSync(new URL("../components/workspace/staff-account-invitations-panel.tsx",import.meta.url),"utf8");
const id=number=>`5f000000-0000-4000-8000-${String(number).padStart(12,"0")}`;
const preview={schoolName:"Synthetic school",maskedDestination:"s***@example.invalid",roleCode:"teacher",roleName:"Teacher",expiresAt:"2026-10-30T08:00:00Z",existingAccount:false,status:"ready"};
const context={schoolId:id(1),staffId:id(2),manualHandoffEnabled:false,canManage:true,roles:[{code:"teacher",name:"Teacher",capabilities:["people.read"]}],items:[]};
function render(kind,locale="en",changes={}){
  const state=kind==="accept"?[preview,false,false,false,null,false,false,null,false]:[context,false,false,null,"","24","",false,null,null,null,false];
  for(const[index,value]of Object.entries(changes))state[Number(index)]=value;
  let call=0;const react={...React,useState:initial=>[call<state.length?state[call++]:typeof initial==="function"?initial():initial,()=>{}],useRef:initial=>({current:initial}),useCallback:fn=>fn,useEffect:()=>{},useId:()=>"synthetic-invitation"};
  const require=createRequire(import.meta.url),modules={react,"next/link":{__esModule:true,default:({children,...props})=>React.createElement("a",props,children)},"@/components/i18n/language-provider":{useLanguage:()=>({locale})},"@/lib/i18n/staff-invitation-messages":{staffInvitationMessage},"@/lib/staff-account-invitations":client,"./workspace/staff-account-invitations.module.css":{__esModule:true,default:{}},"./staff-account-invitations.module.css":{__esModule:true,default:{}}},exports={};
  const compiled=ts.transpileModule(kind==="accept"?acceptanceSource:panelSource,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX,esModuleInterop:true}}).outputText;
  new Function("require","exports",compiled)(name=>modules[name]??require(name),exports);
  const Component=kind==="accept"?exports.StaffInvitation:exports.StaffAccountInvitationsPanel;
  return renderToStaticMarkup(React.createElement(Component,kind==="accept"?{}:{schoolId:id(1),staffId:id(2),displayName:"Synthetic employee",employeeCode:"SYN-001",staffVersion:0,linked:false,disabled:false,onGuardChange:()=>{}}));
}
test("new-account form is named, keyboard-native and never creates a sign-in assurance",()=>{
  const html=render("accept");assert.match(html,/<form[^>]+aria-label="Private staff invitation"/);assert.equal((html.match(/type="password"/g)??[]).length,2);assert.equal((html.match(/autoComplete="new-password"/gi)??[]).length,2);assert.match(html,/minLength="16"/i);assert.match(html,/maxLength="200"/i);assert.match(html,/required=""/);assert.doesNotMatch(html,/name="email"|current-password|returnTo|token=/);
});
test("existing account form never asks for a password and preserves a separate normal MFA login",()=>{
  const html=render("accept","en",{0:{...preview,existingAccount:true}});assert.doesNotMatch(html,/type="password"|name="password"/);assert.match(html,/<a[^>]+href="\/portal"[^>]+target="_blank"[^>]+rel="noopener noreferrer"/);assert.match(html,/Check signed-in session/);assert.match(html,/<button type="submit" disabled=""/);assert.match(html,/never changes your existing password/);
});
test("accepted and uncertain results cannot display another credential-creation form",()=>{
  const accepted=render("accept","en",{0:null,3:true});assert.match(accepted,/Invitation accepted/);assert.match(accepted,/has not signed you in/);assert.doesNotMatch(accepted,/<form|type="password"/);
  const unknown=render("accept","en",{7:"acceptUncertain",8:true});assert.match(unknown,/role="alert"/);assert.match(unknown,/Do not create another account/);assert.doesNotMatch(unknown,/<form|type="password"/);
});
test("disabled private delivery is visibly unavailable and cannot be mistaken for sent",()=>{
  const html=render("panel");assert.match(html,/Private invitation handoff is not enabled/);assert.match(html,/No invitation has been sent/);assert.match(html,/<button type="button" disabled="">Prepare staff invitation/);assert.match(html,/No account invitations/);
});
test("bilingual forms retain explicit review controls, loading, error and empty states",()=>{
  const hindi=render("accept","hi");assert.match(hindi,/\p{Script=Devanagari}/u);assert.match(hindi,/type="checkbox" required=""/);
  assert.match(render("accept","en",{0:null,1:true}),/role="status"/);assert.match(render("accept","en",{0:null,7:"unavailable"}),/Invitation unavailable/);
  const review=render("panel","en",{0:{...context,manualHandoffEnabled:true},3:{kind:"ISSUE"}});assert.match(review,/<legend[^>]*>Review the account invitation/);assert.match(review,/min="1" max="168"/);assert.match(review,/Financial preparation and approval must belong to different/);assert.match(review,/type="button">Cancel/);
});
test("private token capture and route avoid query propagation, storage, automatic clipboard and redirects",()=>{
  assert.match(acceptanceSource,/staffInvitationFragment\(window.location.hash\)/);assert.match(acceptanceSource,/window.history.replaceState\(null,"","\/staff-invitation"\)/);
  for(const source of[acceptanceSource,panelSource])assert.doesNotMatch(source,/localStorage|sessionStorage|console\.|navigator.clipboard|location.assign|document.cookie\s*=/);
  const route=readFileSync(new URL("../app/staff-invitation/page.tsx",import.meta.url),"utf8");assert.doesNotMatch(route,/searchParams|initialToken/);assert.match(route,/force-dynamic/);assert.match(route,/index: false, follow: false/);assert.match(route,/referrer: "no-referrer"/);
  const config=readFileSync(new URL("../../next.config.ts",import.meta.url),"utf8");assert.match(config,/source:\s*"\/staff-invitation"/);assert.match(config,/private,\s*no-store/);assert.match(config,/X-Robots-Tag/);
});
test("People selection is locked while the panel is open without disabling its recovery controls",()=>{
  const source=readFileSync(new URL("../components/workspace/people-workspace.tsx",import.meta.url),"utf8");assert.match(source,/disabled=\{busy \|\| Boolean\(ending\) \|\| Boolean\(review\) \|\| invitationBlocked\}/);assert.ok(source.lastIndexOf("<StaffAccountInvitationsPanel")>source.lastIndexOf("</fieldset>"));assert.match(source,/onGuardChange=\{setInvitationBlocked\}/);
  const css=readFileSync(new URL("../components/workspace/staff-account-invitations.module.css",import.meta.url),"utf8");assert.match(css,/:focus-visible/);assert.match(css,/min-height:44px/);assert.match(css,/@media\(max-width:600px\)/);assert.match(css,/prefers-reduced-motion:reduce/);
});
