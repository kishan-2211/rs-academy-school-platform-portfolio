"use client";

import { LANGUAGES } from "@/lib/i18n/locale";
import { useLanguage, useT } from "./language-provider";

export function LanguageSelector() {
  const { locale, choose } = useLanguage();
  return <div className="language-selector" role="group" aria-label="Language / भाषा">
    {LANGUAGES.map((option) => <button key={option.value} type="button" lang={option.value}
      aria-label={option.name} aria-pressed={locale === option.value} onClick={() => choose(option.value)}>{option.label}</button>)}
  </div>;
}

export function LanguagePreferenceNotice() {
  const { locale, choose, save, needsChoice, saveState } = useLanguage();
  const t = useT("common");
  return <>
    {needsChoice && <section className="language-onboarding" aria-labelledby="language-onboarding-title">
      <h2 id="language-onboarding-title"><span lang="en">Choose your preferred language</span> / <span lang="hi">अपनी पसंदीदा भाषा चुनें</span></h2>
      <div className="language-onboarding__actions">
        {LANGUAGES.map((option) => <button type="button" key={option.value} lang={option.value}
          aria-pressed={locale === option.value} onClick={() => choose(option.value)}>{option.name}</button>)}
        <button type="button" disabled={saveState === "saving"} onClick={save}>{t("Save preference")}</button>
      </div>
    </section>}
    <div className="language-save-status" role="status" aria-live="polite">
      {saveState === "saving" ? t("Saving language…") : saveState === "saved" ? t("Language saved") : ""}
    </div>
    {(saveState === "failed" || saveState === "unavailable") && <div className="language-save-error" role="alert">
      <span>{t(saveState === "failed" ? "Language changed on this device, but could not be saved to your account." : "Language preference could not be loaded. Your device preference is still available.")}</span>
      <button type="button" onClick={save}>{t("Retry")}</button>
    </div>}
  </>;
}
