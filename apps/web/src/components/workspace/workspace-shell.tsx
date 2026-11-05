"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { Brand } from "@/components/brand";
import { SignOutButton } from "@/components/sign-out-button";
import { ThemeSelector } from "@/components/theme-selector";
import { LanguageSelector } from "@/components/i18n/language-selector";
import { useT } from "@/components/i18n/language-provider";
import { bindMobileNavigation } from "@/lib/mobile-navigation";
import { SecurityCentre } from "@/components/workspace/security-centre";
import styles from "./workspace-navigation.module.css";
import {
  AcademicIcon,
  AttendanceIcon,
  MenuIcon,
  MessageIcon,
  MoneyIcon,
  OverviewIcon,
  PeopleIcon,
  ReportIcon,
  SettingsIcon,
} from "@/components/workspace/workspace-icons";

const navigation = [
  {
    label: "Workspace",
    items: [
      { href: "/workspace", label: "Overview", icon: OverviewIcon, requires: [] },
    ],
  },
  {
    label: "People",
    items: [
      { href: "/workspace/admissions", label: "Admissions", icon: PeopleIcon, requires: ["admissions.*"] },
      { href: "/workspace/students", label: "Students & guardians", icon: PeopleIcon, requires: ["student.*", "guardian.*"] },
      { href: "/workspace/staff", label: "Staff", icon: PeopleIcon, requires: ["people.*"] },
      { href: "/workspace/workforce", label: "Staff attendance & leave", icon: AttendanceIcon, requires: ["workforce.*"] },
    ],
  },
  {
    label: "School day",
    items: [
      { href: "/workspace/my-academics", label: "My teaching", icon: AcademicIcon, requires: ["academics.read"] },
      { href: "/workspace/academics", label: "Academic setup", icon: AcademicIcon, requires: ["academics.configure"] },
      { href: "/workspace/rollover", label: "Academic rollover", icon: AcademicIcon, requires: ["academic.rollover.manage"] },
      { href: "/workspace/attendance", label: "Attendance", icon: AttendanceIcon, requires: ["attendance.*"] },
      { href: "/workspace/calendar", label: "Calendar & closures", icon: AttendanceIcon, requires: ["calendar.manage", "calendar.approve"] },
      { href: "/workspace/learning", label: "Assignments", icon: AcademicIcon, requires: ["learning.*"] },
      { href: "/workspace/assessment", label: "Assessment", icon: ReportIcon, requires: ["assessment.*"] },
      { href: "/workspace/documents", label: "Official documents", icon: ReportIcon, requires: ["documents.*"] },
      { href: "/workspace/fees", label: "Fees & finance", icon: MoneyIcon, requires: ["fees.*"] },
      { href: "/workspace/accounting", label: "Accounting books", icon: MoneyIcon, requires: ["accounting.*"] },
      { href: "/workspace/payroll", label: "Payroll management", icon: MoneyIcon, requires: ["payroll.read"] },
      { href: "/workspace/my-payroll", label: "My payslips", icon: MoneyIcon, requires: ["payroll.payslip.self"] },
      { href: "/workspace/inbox", label: "My school inbox", icon: MessageIcon, requires: ["communication.read"] },
      { href: "/workspace/communication", label: "Communication", icon: MessageIcon, requires: ["communication.*"] },
      { href: "/workspace/parent-requests", label: "Parent requests", icon: MessageIcon, requires: ["parent_requests.*"] },
      { href: "/workspace/student-leave", label: "Student leave", icon: MessageIcon, requires: ["student_leave.manage"] },
      { href: "/workspace/ptm", label: "Parent-teacher meetings", icon: MessageIcon, requires: ["ptm.read", "ptm.manage"] },
    ],
  },
  {
    label: "Operations",
    items: [
      { href: "/workspace/transport", label: "Transport", icon: AttendanceIcon, requires: ["transport.*"] },
    ],
  },
  {
    label: "Administration",
    items: [
      { href: "/workspace/reports", label: "Reports", icon: ReportIcon, requires: ["reports.*"] },
      { href: "/workspace/setup", label: "School setup", icon: SettingsIcon, requires: ["school.configure", "platform.configure"] },
      { href: "/workspace/security", label: "Account security", icon: SettingsIcon, requires: [] },
      { href: "/workspace/help", label: "Help & training", icon: MessageIcon, requires: [] },
    ],
  },
] as const;

function grants(capabilities: string[], requirements: readonly string[]) {
  if (!requirements.length) return true;
  return requirements.some((requirement) =>
    requirement.endsWith(".*")
      ? capabilities.some((capability) => capability.startsWith(requirement.slice(0, -1)))
      : capabilities.includes(requirement),
  );
}

const mobileQuery = "(max-width: 980px)";
function subscribeMobile(onChange: () => void) {
  const query = window.matchMedia(mobileQuery);
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}
function mobileSnapshot() { return window.matchMedia(mobileQuery).matches; }
function serverMobileSnapshot() { return false; }

export function WorkspaceShell({ children }: { children: ReactNode }) {
  const t = useT("navigation");
  const securityT = useT("security");
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);
  const mobile = useSyncExternalStore(subscribeMobile, mobileSnapshot, serverMobileSnapshot);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const sidebarRef = useRef<HTMLElement>(null);
  const backgroundRef = useRef<HTMLDivElement>(null);
  const [accessChecked, setAccessChecked] = useState(false);
  const [capabilities, setCapabilities] = useState<string[]>([]);
  const [mfaRequired, setMfaRequired] = useState(false);
  const [identity, setIdentity] = useState({
    organisationName: "Authorized school",
    membershipCount: null as number | null,
    displayName: "Signed-in user",
    loginIdentifier: "Verified account",
  });

  useEffect(() => {
    const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
    Promise.all([
      fetch(`${apiUrl}/api/v1/context`, { credentials: "include" }),
      fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" }),
    ])
      .then(async ([contextResponse, sessionResponse]) => {
        if (!contextResponse.ok) {
          // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Discard the workspace document when authenticated access cannot be verified.
          window.location.assign("/portal");
          return;
        }
        const context = (await contextResponse.json()) as {
          capabilities: string[];
          mfaRequired: boolean;
          activeOrganisationId: string | null;
          organisations: Array<{
            id: string;
            displayName: string;
            membershipCount: number;
            active: boolean;
          }>;
        };
        const session = sessionResponse.ok
          ? ((await sessionResponse.json()) as { displayName: string; loginIdentifier: string })
          : null;
        const activeOrganisation = context.organisations.find(
          (organisation) =>
            organisation.active || organisation.id === context.activeOrganisationId,
        );
        setIdentity({
          organisationName: activeOrganisation?.displayName || "Authorized school",
          membershipCount: activeOrganisation?.membershipCount ?? null,
          displayName: session?.displayName || "Signed-in user",
          loginIdentifier: session?.loginIdentifier || "Verified account",
        });
        setCapabilities(context.capabilities);
        if (typeof context.mfaRequired !== "boolean") throw new Error("Authorization context incomplete");
        setMfaRequired(context.mfaRequired);
        if (context.mfaRequired) {
          setAccessChecked(true);
          return;
        }
        const hasStudentPortal = context.capabilities.includes("portal.student");
        const hasStaffAccess = context.capabilities.some(
          (capability) => !["portal.guardian", "portal.student"].includes(capability),
        );
        if (hasStudentPortal && !hasStaffAccess) {
          // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document at the verified persona boundary without retaining staff state.
          window.location.assign("/student");
          return;
        }
        const portalOnly =
          context.capabilities.includes("portal.guardian") &&
          !hasStaffAccess;
        if (portalOnly) {
          // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document at the verified persona boundary without retaining staff state.
          window.location.assign("/family");
          return;
        }
        setAccessChecked(true);
      })
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Discard the workspace document when its access check fails.
      .catch(() => window.location.assign("/portal"));
  }, []);

  useEffect(() => {
    if (!menuOpen || !mobile || !accessChecked || !closeRef.current || !sidebarRef.current || !backgroundRef.current) return;
    const toggle = toggleRef.current;
    const cleanup = bindMobileNavigation(closeRef.current, sidebarRef.current,
      [backgroundRef.current], () => setMenuOpen(false));
    closeRef.current.focus();
    const query = window.matchMedia(mobileQuery);
    const onResize = () => { if (!query.matches) setMenuOpen(false); };
    query.addEventListener("change", onResize);
    return () => {
      cleanup();
      query.removeEventListener("change", onResize);
      if (query.matches) toggle?.focus();
    };
  }, [menuOpen, mobile, accessChecked]);

  if (!accessChecked) {
    return (
      <main className="workspace-route-check" aria-live="polite">
        <span />
        <p>{t("Opening your authorized school workspace…")}</p>
      </main>
    );
  }

  return (
    <div className="workspace">
      <button
        className={`workspace__scrim${menuOpen ? " workspace__scrim--visible" : ""}`}
        type="button"
        aria-label={t("Close workspace navigation")}
        tabIndex={-1}
        aria-hidden="true"
        onClick={() => setMenuOpen(false)}
      />
      <aside ref={sidebarRef} id="workspace-navigation"
        inert={mobile && !menuOpen}
        role={mobile && menuOpen ? "dialog" : undefined}
        aria-modal={mobile && menuOpen ? true : undefined}
        aria-label={mobile && menuOpen ? t("School workspace navigation") : undefined}
        className={`workspace-sidebar${menuOpen ? " workspace-sidebar--open" : ""}`}>
        <button ref={closeRef} className={styles.close} type="button" onClick={() => setMenuOpen(false)}>
          {t("Close navigation")} <span aria-hidden="true">×</span>
        </button>
        <div className="workspace-sidebar__brand">
          <Brand />
        </div>
        <div className="school-switcher">
          <span>{identity.organisationName.split(/\s+/).slice(0, 2).map((part) => part[0]).join("").toUpperCase() || "SC"}</span>
          <span>
            <small>{t("Working in")}</small>
            <strong>{identity.organisationName}</strong>
            <em>{identity.membershipCount === null ? t("Organization-scoped access") : t("{count} active memberships", { count: identity.membershipCount })}</em>
          </span>
        </div>
        <nav className="workspace-nav" aria-label={t("School workspace")}>
          {navigation.map((group) => {
            const visibleItems = group.items.filter((item) => mfaRequired ? item.href === "/workspace/security" : grants(capabilities, item.requires));
            if (!visibleItems.length) return null;
            return (
              <div className="workspace-nav__group" key={group.label}>
                <span>{t(group.label)}</span>
                {visibleItems.map((item) => {
                  const active =
                    item.href === "/workspace"
                      ? pathname === item.href
                      : pathname.startsWith(item.href);
                  const Icon = item.icon;
                  return (
                    <Link
                      aria-current={active ? "page" : undefined}
                      className={active ? "workspace-nav__active" : ""}
                      href={item.href}
                      key={item.href}
                      onClick={() => setMenuOpen(false)}
                    >
                      <Icon />
                      <span>{t(item.label)}</span>
                    </Link>
                  );
                })}
              </div>
            );
          })}
        </nav>
        <div className="workspace-sidebar__foot">
          <span className="system-status">
            <i /> {mfaRequired ? securityT("Two-step verification required") : t("Secure session active")} </span>
          <small>EduCore · Foundation 0.1</small>
        </div>
      </aside>

      <div ref={backgroundRef} className="workspace-main">
        <header className="workspace-topbar">
          <button
            ref={toggleRef}
            className="workspace-menu"
            type="button"
            aria-expanded={menuOpen}
            aria-controls="workspace-navigation"
            onClick={() => setMenuOpen((value) => !value)}
          >
            <MenuIcon />
            <span className="sr-only">{t("Open workspace navigation")}</span>
          </button>
          <div className="workspace-topbar__actions">
            <LanguageSelector />
            <ThemeSelector compact />
            <span className="workspace-session">
              {t("Organization")} <strong>{identity.organisationName}</strong>
            </span>
            <div className="workspace-profile">
              <span>{identity.displayName.split(/\s+/).slice(0, 2).map((part) => part[0]).join("").toUpperCase() || "US"}</span>
              <span>
                <strong>{identity.displayName}</strong>
                <small>{identity.loginIdentifier}</small>
              </span>
            </div>
            <SignOutButton className="workspace-signout" />
          </div>
        </header>
        <main id="main-content" className="workspace-content">
          {mfaRequired ? <SecurityCentre mfaRequired /> : children}
        </main>
      </div>
    </div>
  );
}
