export const LANGUAGES = [
  { value: "en", label: "EN", name: "English" },
  { value: "hi", label: "हिंदी", name: "हिंदी" },
] as const;
export type Locale = (typeof LANGUAGES)[number]["value"];
export const LANGUAGE_COOKIE = "educore_language";
export const SESSION_LANGUAGE = "educore_language_choice";

export function isLocale(value: unknown): value is Locale {
  return LANGUAGES.some((language) => language.value === value);
}

export function resolveLocale(explicit: unknown, account: unknown, local: unknown): Locale {
  return [explicit, account, local].find(isLocale) ?? "en";
}

export function languageCookie(locale: Locale, secure: boolean): string {
  return `${LANGUAGE_COOKIE}=${locale}; Path=/; Max-Age=31536000; SameSite=Lax${secure ? "; Secure" : ""}`;
}

export function readSessionLanguage(storage: Pick<Storage, "getItem"> | (() => Pick<Storage, "getItem">)): Locale | null {
  try {
    // Some privacy modes throw when accessing sessionStorage itself.
    const value = (typeof storage === "function" ? storage() : storage).getItem(SESSION_LANGUAGE);
    return isLocale(value) ? value : null;
  } catch { return null; }
}

// Replacing only named placeholders never changes names, IDs, amounts or input.
export function interpolate(message: string, values: Record<string, string | number> = {}): string {
  return message.replace(/\{(\w+)\}/g, (placeholder, key: string) =>
    Object.hasOwn(values, key) ? String(values[key]) : placeholder);
}

// Select already-authored bilingual content, never machine-translate school data.
export function localizedContent(locale: Locale, english: string | null, hindi: string | null) {
  const values: Record<Locale, string | null> = { en: english, hi: hindi };
  const preferred = values[locale];
  const alternative = LANGUAGES.find((item) => item.value !== locale)!.value;
  const primaryLanguage = preferred?.trim() ? locale : alternative;
  const secondaryLanguage = primaryLanguage === locale ? alternative : locale;
  return {
    primary: values[primaryLanguage] ?? "", primaryLanguage,
    secondary: values[secondaryLanguage] ?? "", secondaryLanguage,
  };
}
