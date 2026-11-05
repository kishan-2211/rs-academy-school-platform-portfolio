"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useT } from "@/components/i18n/language-provider";
import { SecurityCentre } from "@/components/workspace/security-centre";
import styles from "./security-requirement.module.css";

type AccessCheck = "checking" | "allowed" | "mfa-required" | "unavailable";
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export async function checkPrivilegedAccess(signal: AbortSignal): Promise<AccessCheck> {
  const response = await fetch(`${apiUrl}/api/v1/context`, { credentials: "include", cache: "no-store", signal });
  if (!response.ok) throw new Error("Authorization context unavailable");
  const context = await response.json() as { mfaRequired?: boolean };
  if (typeof context.mfaRequired !== "boolean") throw new Error("Authorization context incomplete");
  return context.mfaRequired ? "mfa-required" : "allowed";
}

export function usePrivilegedAccessCheck() {
  const [state, setState] = useState<AccessCheck>("checking");
  const [revision, setRevision] = useState(0);
  const retry = useCallback(() => { setState("checking"); setRevision((value) => value + 1); }, []);
  useEffect(() => {
    const controller = new AbortController();
    checkPrivilegedAccess(controller.signal)
      .then((next) => { if (!controller.signal.aborted) setState(next); })
      .catch(() => { if (!controller.signal.aborted) setState("unavailable"); });
    return () => controller.abort();
  }, [revision]);
  return { state, retry };
}

export function PrivilegedAccessContent({ state, retry, children }: { state: AccessCheck; retry: () => void; children: ReactNode }) {
  const t = useT("security");
  if (state === "allowed") return <>{children}</>;
  return <main id="main-content" className={styles.content}>
    {state === "mfa-required" ? <SecurityCentre mfaRequired /> : state === "unavailable" ? (
      <div className={styles.requirement} role="alert">
        <h1>{t("Access verification is unavailable")}</h1>
        <p>{t("No school records have been opened. Retry the account check or use the sign-out control above to sign in again.")}</p>
        <button className="button button--dark" type="button" onClick={retry}>{t("Retry")}</button>
      </div>
    ) : <p className="security-notice" role="status">{t("Checking account verification…")}</p>}
  </main>;
}
