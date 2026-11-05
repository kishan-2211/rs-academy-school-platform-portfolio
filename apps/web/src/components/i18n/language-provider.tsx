"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { isLocale, languageCookie, readSessionLanguage, resolveLocale, SESSION_LANGUAGE, type Locale } from "@/lib/i18n/locale";
import { translate } from "@/lib/i18n/translate";
import type { Domain } from "@/lib/i18n/messages";
import { translateCode } from "@/lib/i18n/codes";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
type SaveState = "idle" | "saving" | "saved" | "failed" | "unavailable";
type LanguageContextValue = {
  locale: Locale; choose: (locale: Locale) => void; save: () => void;
  needsChoice: boolean; saveState: SaveState;
};
const LanguageContext = createContext<LanguageContextValue | null>(null);

export function LanguageProvider({ initialLocale, children }: { initialLocale: Locale; children: ReactNode }) {
  const [locale, setLocale] = useState(initialLocale);
  const [needsChoice, setNeedsChoice] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const current = useRef(initialLocale);
  const explicit = useRef<Locale | null>(null);
  const authenticated = useRef(false);
  const revision = useRef(0);
  const writes = useRef<Promise<void>>(Promise.resolve());

  const apply = useCallback((next: Locale) => {
    current.current = next;
    setLocale(next);
    document.documentElement.lang = next;
    // This cookie contains only en/hi; authentication remains in HttpOnly cookies.
    try { document.cookie = languageCookie(next, window.location.protocol === "https:"); } catch { /* In-memory switching remains available. */ }
  }, []);

  const persist = useCallback((next: Locale) => {
    const requestRevision = ++revision.current;
    setSaveState("saving");
    // Serialize writes so an older response cannot overwrite a newer selection.
    writes.current = writes.current.catch(() => {}).then(async () => {
      try {
        let csrf = document.cookie.split("; ").find((item) => item.startsWith("educore_csrf="))?.slice(13);
        if (!csrf) {
          const tokenResponse = await fetch(`${apiUrl}/api/v1/me/preferences`, { credentials: "include", cache: "no-store" });
          if (!tokenResponse.ok) throw new Error("Preferences unavailable");
          csrf = document.cookie.split("; ").find((item) => item.startsWith("educore_csrf="))?.slice(13);
        }
        if (!csrf) throw new Error("CSRF token unavailable");
        const response = await fetch(`${apiUrl}/api/v1/me/preferences`, {
          method: "PUT", credentials: "include", cache: "no-store",
          headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": decodeURIComponent(csrf) },
          body: JSON.stringify({ preferredLanguage: next }),
        });
        if (!response.ok) throw new Error("Preference not saved");
        if (requestRevision === revision.current) { setSaveState("saved"); setNeedsChoice(false); }
      } catch {
        if (requestRevision === revision.current) setSaveState("failed");
      }
    });
  }, []);

  useEffect(() => {
    let active = true;
    explicit.current = readSessionLanguage(() => window.sessionStorage);
    Promise.resolve().then(async () => {
      if (!active) return;
      if (explicit.current) apply(explicit.current);
      try {
        const response = await fetch(`${apiUrl}/api/v1/me/preferences`, { credentials: "include", cache: "no-store" });
        if (!active || response.status === 401) return;
        if (!response.ok) throw new Error("Preference not available");
        const result = await response.json() as { preferredLanguage?: unknown };
        if (!active) return;
        authenticated.current = true;
        const saved = isLocale(result.preferredLanguage) ? result.preferredLanguage : null;
        apply(resolveLocale(explicit.current, saved, initialLocale));
        setNeedsChoice(saved === null);
        // An existing account can adopt a deliberate pre-login session choice.
        // New accounts still get the bilingual confirmation, never silent onboarding.
        if (saved && explicit.current && explicit.current !== saved) persist(explicit.current);
      } catch { if (active) setSaveState("unavailable"); }
    });
    return () => { active = false; };
  }, [apply, initialLocale, persist]);

  const choose = useCallback((next: Locale) => {
    explicit.current = next;
    try { window.sessionStorage.setItem(SESSION_LANGUAGE, next); } catch { /* Cookie still works. */ }
    apply(next);
    if (authenticated.current) persist(next);
  }, [apply, persist]);

  const save = useCallback(() => {
    if (authenticated.current) persist(current.current);
    else {
      // Retry identity discovery without reloading forms or discarding user input.
      setSaveState("saving");
      void fetch(`${apiUrl}/api/v1/me/preferences`, { credentials: "include", cache: "no-store" })
        .then(async (response) => {
          if (response.status === 401) {
            authenticated.current = false;
            setNeedsChoice(false);
            setSaveState("idle");
            return;
          }
          if (!response.ok) throw new Error("Preference not available");
          const result = await response.json() as { preferredLanguage?: unknown };
          authenticated.current = true;
          const next = resolveLocale(explicit.current, result.preferredLanguage, current.current);
          apply(next);
          setNeedsChoice(!isLocale(result.preferredLanguage));
          setSaveState("idle");
          if (explicit.current) persist(explicit.current);
        }).catch(() => setSaveState("unavailable"));
    }
  }, [apply, persist]);

  return <LanguageContext.Provider value={{ locale, choose, save, needsChoice, saveState }}>{children}</LanguageContext.Provider>;
}

export function useLanguage() {
  const context = useContext(LanguageContext);
  if (!context) throw new Error("LanguageProvider is required");
  return context;
}

export function useT(domain: Domain) {
  const { locale } = useLanguage();
  return useCallback((source: string, values?: Record<string, string | number>) => translate(locale, domain, source, values), [locale, domain]);
}

export function useCodeLabel<T extends string | null | undefined>(fallback: (value: T) => string) {
  const { locale } = useLanguage();
  return useCallback((value: T) => translateCode(locale, value, fallback), [locale, fallback]);
}

// Client leaf: server-rendered pages can opt individual copy into live switching
// without becoming client pages or refetching private data.
export function Text({ domain = "common", children, values }: { domain?: Domain; children: string; values?: Record<string, string | number> }) {
  const t = useT(domain);
  return <>{t(children, values)}</>;
}
