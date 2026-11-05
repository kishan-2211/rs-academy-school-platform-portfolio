"use client";

import Link from "next/link";
import { LanguageSelector } from "@/components/i18n/language-selector";
import { useT } from "@/components/i18n/language-provider";
import type { ReactNode } from "react";
import { Brand } from "@/components/brand";
import { Shield } from "@/components/icons";
import { SignOutButton } from "@/components/sign-out-button";
import { ThemeSelector } from "@/components/theme-selector";
import { PrivilegedAccessContent, usePrivilegedAccessCheck } from "@/components/workspace/privileged-access-boundary";
import styles from "./family-shell.module.css";

export function FamilyShell({ children }: { children: ReactNode }) {
  const t = useT("navigation");
  const securityT = useT("security");
  const access = usePrivilegedAccessCheck();
  return (
    <div className="family-shell">
      <header className={`family-topbar ${styles.header}`}>
        <Brand inverse />
        <nav aria-label={t("Family portal")} tabIndex={0}>
          {access.state === "allowed" && <>
          <Link href="/family">{t("Overview")}</Link>
          <Link href="/family#family-calendar">{t("Calendar")}</Link>
          <Link href="/family#family-attendance">{t("Attendance")}</Link>
          <Link href="/family#family-schedule">{t("Timetable")}</Link>
          <Link href="/family#family-curriculum">{t("Books & syllabus")}</Link>
          <Link href="/family#family-student-access">{t("Student access")}</Link>
          <Link href="/family#family-modules">{t("School updates")}</Link>
          <Link href="/family/requests">{t("Support requests")}</Link>
          <Link href="/family/leave">{t("Student leave")}</Link>
          <Link href="/family/transport">{t("Transport plans")}</Link>
          <Link href="/family/documents">{t("Documents")}</Link>
          <Link href="/family/ptm">{t("Parent-teacher meetings")}</Link>
          </>}
          <Link href="/family/security">{t("Account security")}</Link>
          {access.state === "allowed" && <Link href="/">{t("School website")}</Link>}
        </nav>
        <span className="family-topbar__trust">
          <Shield /> {access.state === "allowed" ? t("Verified family access") : securityT("Account verification")} </span>
        <div className={styles.actions}>
          <LanguageSelector />
          <ThemeSelector compact />
          <SignOutButton className="family-signout" />
        </div>
      </header>
      <PrivilegedAccessContent state={access.state} retry={access.retry}>{children}</PrivilegedAccessContent>
    </div>
  );
}
