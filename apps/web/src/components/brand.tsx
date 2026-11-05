"use client";

import Link from "next/link";
import Image from "next/image";
import { site } from "@/content/site";
import { useT } from "@/components/i18n/language-provider";
import styles from "./brand.module.css";

export function Brand({ inverse = false, onNavigate }: { inverse?: boolean; onNavigate?: () => void }) {
  const t = useT("public");
  return (
    <Link className={`brand${inverse ? ` brand--inverse ${styles.inverse}` : ""}`} href="/" aria-label={t("{school} home", { school: site.schoolName })} onNavigate={onNavigate}>
      <span className="brand__mark brand__mark--official">
        <Image className="brand__logo" src={site.logo} alt={t("{school} emblem", { school: site.schoolName })} width={54} height={50} priority />
      </span>
      <span className="brand__words">
        <strong>{site.schoolName}</strong>
        <small>{t(site.motto)}</small>
      </span>
    </Link>
  );
}
