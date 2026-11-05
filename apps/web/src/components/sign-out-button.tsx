"use client";

import { useState } from "react";
import { useT } from "@/components/i18n/language-provider";
import { SESSION_LANGUAGE } from "@/lib/i18n/locale";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

function cookie(name: string) {
  const value = document.cookie.split("; ").find((entry) => entry.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : "";
}

export function SignOutButton({ className = "" }: { className?: string }) {
  const t = useT("common");
  const [state, setState] = useState<"ready" | "busy" | "failed">("ready");

  async function signOut() {
    setState("busy");
    try {
      let csrf = cookie("educore_csrf");
      if (!csrf) {
        await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
        csrf = cookie("educore_csrf");
      }
      const response = await fetch(`${apiUrl}/api/v1/auth/sessions/current`, {
        method: "DELETE",
        credentials: "include",
        headers: { "X-CSRF-TOKEN": csrf },
      });
      if (!response.ok && response.status !== 401) throw new Error("Sign out failed");
      window.sessionStorage.removeItem("educore_login_intent");
      window.sessionStorage.removeItem(SESSION_LANGUAGE);
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Discard the authenticated document and client cache after ending the session.
      window.location.assign("/portal");
    } catch {
      setState("failed");
    }
  }

  return (
    <button
      className={className}
      type="button"
      disabled={state === "busy"}
      onClick={() => void signOut()}
    >
      {t(state === "busy" ? "Signing out…" : state === "failed" ? "Try sign out again" : "Sign out")}
    </button>
  );
}
