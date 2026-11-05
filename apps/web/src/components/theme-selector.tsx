"use client";

import { useEffect, useId, useRef, useState } from "react";
import { useT } from "@/components/i18n/language-provider";
import {
  applyTheme,
  persistThemePreference,
  readThemePreference,
  THEME_OPTIONS,
  THEME_QUERY,
  THEME_STORAGE_KEY,
  themeOptionSemantics,
  type ThemePreference,
  watchSystemTheme,
} from "@/lib/theme";

function ThemeGlyph({ theme }: { theme: ThemePreference }) {
  if (theme === "light") {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3.5" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></svg>;
  }
  if (theme === "dark") {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 15.2A8.5 8.5 0 0 1 8.8 4 8.5 8.5 0 1 0 20 15.2Z" /></svg>;
  }
  return <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="13" rx="2" /><path d="M8 21h8M12 17v4" /></svg>;
}

export function ThemeSelector({ compact = false }: { compact?: boolean }) {
  const t = useT("common");
  const groupName = useId();
  const [preference, setPreference] = useState<ThemePreference>("system");
  const preferenceRef = useRef<ThemePreference>("system");

  useEffect(() => {
    const media = window.matchMedia(THEME_QUERY);
    const saved = readThemePreference(window.localStorage);
    preferenceRef.current = saved;
    applyTheme(document, saved, media.matches);
    const initialStateFrame = window.requestAnimationFrame(() => setPreference(saved));
    const stopSystem = watchSystemTheme(media, () => preferenceRef.current, (dark) => {
      applyTheme(document, "system", dark);
    });
    const syncPreference = (event: StorageEvent) => {
      if (event.key !== null && event.key !== THEME_STORAGE_KEY) return;
      const next = readThemePreference(window.localStorage);
      preferenceRef.current = next;
      setPreference(next);
      applyTheme(document, next, media.matches);
    };
    window.addEventListener("storage", syncPreference);
    return () => {
      window.cancelAnimationFrame(initialStateFrame);
      stopSystem();
      window.removeEventListener("storage", syncPreference);
    };
  }, []);

  function choose(next: ThemePreference) {
    const media = window.matchMedia(THEME_QUERY);
    preferenceRef.current = next;
    setPreference(next);
    persistThemePreference(window.localStorage, next);
    applyTheme(document, next, media.matches);
  }

  return (
    <fieldset className={`theme-selector${compact ? " theme-selector--compact" : ""}`}>
      <legend className="sr-only">{t("Color theme")}</legend>
      <div className="theme-selector__options" role="radiogroup" aria-label={t("Color theme")}>
        {THEME_OPTIONS.map((option) => {
          const state = themeOptionSemantics(preference, option.value);
          return (
            <label key={option.value} data-selected={state.selected || undefined}>
              <input
                type="radio"
                name={groupName}
                value={option.value}
                checked={state.checked}
                aria-label={t(`${option.label} theme`)}
                onChange={() => choose(option.value)}
              />
              <ThemeGlyph theme={option.value} />
              <span>{t(option.label)}</span>
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
