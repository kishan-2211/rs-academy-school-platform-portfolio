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
import styles from "./student-portal.module.css";

export function StudentShell({ children }: { children: ReactNode }) {
  const t = useT("navigation");
  const securityT = useT("security");
  const access = usePrivilegedAccessCheck();
  return (
    <div className={styles.shell}>
      <a className="skip-link" href="#main-content">
        {t("Skip to student portal content")} </a>
      <header className={styles.topbar}>
        <Brand inverse />
        <nav aria-label={t("Student portal")}>
          {access.state === "allowed" && <>
          <Link href="/student#student-overview">{t("Overview")}</Link>
          <Link href="/student#student-calendar">{t("Calendar")}</Link>
          <Link href="/student#student-timetable">{t("Timetable")}</Link>
          <Link href="/student#student-curriculum">{t("Books & syllabus")}</Link>
          <Link href="/student#student-attendance">{t("Attendance")}</Link>
          <Link href="/student#student-assignments">{t("Assignments")}</Link>
          <Link href="/student#student-results">{t("Results")}</Link>
          <Link href="/student#student-messages">{t("Messages")}</Link>
          <Link href="/">{t("School website")}</Link>
          </>}
          {access.state !== "allowed" && <Link href="/workspace/security">{t("Account security")}</Link>}
        </nav>
        <span className={styles.trust}>
          <Shield /> {access.state === "allowed" ? t("My verified view") : securityT("Account verification")} </span>
        <LanguageSelector />
        <ThemeSelector compact />
        <SignOutButton className={styles.signOut} />
      </header>
      <PrivilegedAccessContent state={access.state} retry={access.retry}>{children}</PrivilegedAccessContent>
    </div>
  );
}
