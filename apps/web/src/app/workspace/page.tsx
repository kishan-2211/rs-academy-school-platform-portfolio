"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useT } from "@/components/i18n/language-provider";
import { ArrowUpRight, Check, Clock, Sparkles } from "@/components/icons";
import {
  AcademicIcon,
  AttendanceIcon,
  MessageIcon,
  MoneyIcon,
  PeopleIcon,
  ReportIcon,
  SettingsIcon,
} from "@/components/workspace/workspace-icons";

type AccessContext = {
  capabilities: string[];
  activeOrganisationId: string | null;
  organisations: Array<{
    id: string;
    displayName: string;
    active: boolean;
  }>;
};

type Session = {
  displayName: string;
  loginIdentifier: string;
};

const destinations = [
  { label: "Admissions", detail: "Enquiries and admission decisions", href: "/workspace/admissions", icon: PeopleIcon, color: "coral", requires: ["admissions.*"] },
  { label: "Students & guardians", detail: "Scoped learner and family records", href: "/workspace/students", icon: PeopleIcon, color: "mint", requires: ["student.*", "guardian.*"] },
  { label: "Staff", detail: "People, roles, and school assignments", href: "/workspace/staff", icon: PeopleIcon, color: "yellow", requires: ["people.*"] },
  { label: "My teaching", detail: "Assigned classes and published timetable", href: "/workspace/my-academics", icon: AcademicIcon, color: "mint", requires: ["academics.read"] },
  { label: "Academic setup", detail: "Classes, timetable, books, and curriculum", href: "/workspace/academics", icon: AcademicIcon, color: "lavender", requires: ["academics.configure"] },
  { label: "Attendance", detail: "Authorized registers and corrections", href: "/workspace/attendance", icon: AttendanceIcon, color: "mint", requires: ["attendance.*"] },
  { label: "Assignments", detail: "Learning work for assigned classes", href: "/workspace/learning", icon: AcademicIcon, color: "coral", requires: ["learning.*"] },
  { label: "Assessment", detail: "Marks, review, and publication", href: "/workspace/assessment", icon: ReportIcon, color: "yellow", requires: ["assessment.*"] },
  { label: "Official documents", detail: "Bilingual, private, verifiable school records", href: "/workspace/documents", icon: ReportIcon, color: "lavender", requires: ["documents.*"] },
  { label: "Fees & finance", detail: "Schedules, receipts, and reconciliation", href: "/workspace/fees", icon: MoneyIcon, color: "lavender", requires: ["fees.*"] },
  { label: "Accounting books", detail: "Vouchers, ledgers, and financial statements", href: "/workspace/accounting", icon: MoneyIcon, color: "mint", requires: ["accounting.*"] },
  { label: "Payroll management", detail: "Governed salary terms, monthly runs, and private payslips", href: "/workspace/payroll", icon: MoneyIcon, color: "yellow", requires: ["payroll.read"] },
  { label: "My payslips", detail: "Your own finalized private salary records", href: "/workspace/my-payroll", icon: MoneyIcon, color: "mint", requires: ["payroll.payslip.self"] },
  { label: "Communication", detail: "Notices and delivery tracking", href: "/workspace/communication", icon: MessageIcon, color: "mint", requires: ["communication.*"] },
  { label: "Parent requests", detail: "Assigned, auditable family support", href: "/workspace/parent-requests", icon: MessageIcon, color: "coral", requires: ["parent_requests.*"] },
  { label: "Transport", detail: "Routes, vehicles, and assignments", href: "/workspace/transport", icon: AttendanceIcon, color: "coral", requires: ["transport.*"] },
  { label: "Reports", detail: "Permission-scoped operational views", href: "/workspace/reports", icon: ReportIcon, color: "yellow", requires: ["reports.*"] },
  { label: "School setup", detail: "School-controlled platform configuration", href: "/workspace/setup", icon: SettingsIcon, color: "lavender", requires: ["school.configure", "platform.configure"] },
] as const;

function grants(capabilities: string[], requirements: readonly string[]) {
  return requirements.some((requirement) =>
    requirement.endsWith(".*")
      ? capabilities.some((capability) => capability.startsWith(requirement.slice(0, -1)))
      : capabilities.includes(requirement),
  );
}

function workspaceProfile(capabilities: string[]) {
  if (capabilities.includes("platform.configure")) {
    return { label: "Platform administration", heading: "Your platform control centre", detail: "Work across only the organizations and controls assigned to this account." };
  }
  if (capabilities.includes("school.configure") || capabilities.includes("academics.configure")) {
    return { label: "School authority", heading: "Your school operations workspace", detail: "Configure and supervise the school through your authorized responsibilities." };
  }
  if (grants(capabilities, ["attendance.*", "learning.*", "assessment.*"])) {
    return { label: "Teaching and academic staff", heading: "Your school-day workspace", detail: "Open the class and academic work assigned to your account." };
  }
  if (grants(capabilities, ["transport.*"])) {
    return { label: "Transport team", heading: "Your transport workspace", detail: "Open only the routes and transport work assigned to your account." };
  }
  if (grants(capabilities, ["admissions.*"])) {
    return { label: "Admissions team", heading: "Your admissions workspace", detail: "Continue the admission work permitted for your account." };
  }
  if (grants(capabilities, ["fees.*", "accounting.*", "payroll.read"])) {
    return { label: "Finance team", heading: "Your finance workspace", detail: "Open the fee and reconciliation work permitted for your account." };
  }
  return { label: "School staff", heading: "Your authorized workspace", detail: "Only the areas granted to this account are available here." };
}

export default function WorkspaceOverviewPage() {
  const t = useT("workspace");
  const navigation = useT("navigation");
  const [context, setContext] = useState<AccessContext | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [date, setDate] = useState("");
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    let active = true;
    const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
    Promise.all([
      fetch(`${apiUrl}/api/v1/context`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }),
    ])
      .then(async ([contextResponse, sessionResponse]) => {
        if (!contextResponse.ok || !sessionResponse.ok) throw new Error("Context unavailable");
        const [nextContext, nextSession] = await Promise.all([
          contextResponse.json() as Promise<AccessContext>,
          sessionResponse.json() as Promise<Session>,
        ]);
        if (!active) return;
        setContext(nextContext);
        setSession(nextSession);
        setDate(new Intl.DateTimeFormat("en-IN", {
          weekday: "long",
          day: "numeric",
          month: "long",
          timeZone: "Asia/Kolkata",
        }).format(new Date()));
      })
      .catch(() => {
        if (active) setLoadFailed(true);
      });

    return () => {
      active = false;
    };
  }, []);

  const capabilities = context?.capabilities ?? [];
  const visibleDestinations = destinations.filter((destination) =>
    grants(capabilities, destination.requires),
  );
  const profile = workspaceProfile(capabilities);
  const activeOrganisation = context?.organisations.find(
    (organisation) => organisation.active || organisation.id === context.activeOrganisationId,
  );

  if (loadFailed) {
    return (
      <section className="workspace-card activity-card" aria-live="polite">
        <div className="activity-empty">
          <span><Clock /></span>
          <h1>{t("Workspace details are unavailable")}</h1>
          <p>{t("Your authorization context could not be loaded. No school data has been displayed.")}</p>
          <button type="button" onClick={() => window.location.reload()}>{t("Try again")}</button>
        </div>
      </section>
    );
  }

  if (!context || !session) {
    return (
      <section className="workspace-card activity-card" aria-live="polite">
        <div className="activity-empty"><span><Clock /></span><h1>{t("Loading your authorized workspace")}</h1></div>
      </section>
    );
  }

  return (
    <>
      <section className="workspace-welcome">
        <div>
          <p>{date}</p>
          <h1>{t("Welcome, {name}.", { name: session.displayName })} <em>{t(profile.heading)}.</em></h1>
          <span>{t(profile.detail)}</span>
        </div>
        <Link className="workspace-help" href="/workspace/security"><Sparkles /> {t("Review account security")} <ArrowUpRight /></Link>
      </section>

      <section className="setup-banner">
        <div className="setup-banner__orb"><span>{t("Scoped")}</span><small>{t("access")}</small></div>
        <div className="setup-banner__copy">
          <span>{t(profile.label)}</span>
          <h2>{activeOrganisation?.displayName ?? t("Authorized school")}</h2>
          <p>{t("Your session, organization, and permissions were checked before these destinations were shown.")}</p>
        </div>
        <div className="setup-banner__steps">
          <div className="setup-step--done"><span><Check /></span><div><strong>{t("Session")}</strong><small>{t("Verified")}</small></div></div>
          <div className="setup-step--done"><span><Check /></span><div><strong>{t("Organization")}</strong><small>{t("Scoped")}</small></div></div>
          <div className="setup-step--done"><span><Check /></span><div><strong>{t("Permissions")}</strong><small>{t("Evaluated")}</small></div></div>
          <div><span /><div><strong>{t("Live records")}</strong><small>{t("Loaded inside each module")}</small></div></div>
        </div>
        <Link href="/workspace/security">{t("Security details")} <ArrowUpRight /></Link>
      </section>

      <section className="workspace-section">
        <div className="workspace-section__heading">
          <div><span>{t("Authorized destinations")}</span><h2>{t("Continue your work")}</h2></div>
          <small>{t("Modules appear only when this account has a matching permission.")}</small>
        </div>
        <div className="quick-actions">
          {visibleDestinations.map(({ label, detail, href, icon: Icon, color }) => (
            <Link className={`quick-action quick-action--${color}`} href={href} key={href}>
              <span><Icon /></span><strong>{navigation(label)}</strong><small>{t(detail)}</small><ArrowUpRight />
            </Link>
          ))}
        </div>
      </section>

      <section className="workspace-grid workspace-grid--bottom">
        <article className="workspace-card readiness-card">
          <header><div><span>{t("Access boundaries")}</span><h2>{t("Least-privilege workspace")}</h2></div></header>
          <div className="readiness-items">
            <span className="is-ready"><Check /> {t("Organization scope confirmed")}</span>
            <span className="is-ready"><Check /> {t(capabilities.length === 1 ? "{count} permission evaluated" : "{count} permissions evaluated", { count: capabilities.length })}</span>
            <span className="is-ready"><Check /> {t(visibleDestinations.length === 1 ? "{count} work area available" : "{count} work areas available", { count: visibleDestinations.length })}</span>
            <span><i /> {t("Each record is checked again by the server")}</span>
          </div>
        </article>
        <article className="workspace-card activity-card">
          <header><div><span>{t("Operational information")}</span><h2>{t("Live values stay source-backed")}</h2></div></header>
          <div className="activity-empty">
            <span><ReportIcon /></span>
            <h3>{t("No invented dashboard totals")}</h3>
            <p>{t("Attendance, fees, admissions, results, and delivery counts appear inside their modules only when a scoped server source is available.")}</p>
          </div>
        </article>
      </section>
    </>
  );
}
