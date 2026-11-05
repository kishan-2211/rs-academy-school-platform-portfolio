export const THEME_STORAGE_KEY = "educore-theme";
export const THEME_QUERY = "(prefers-color-scheme: dark)";

export type ThemePreference = "light" | "dark" | "system";
export type ResolvedTheme = "light" | "dark";

export const THEME_OPTIONS: ReadonlyArray<{ value: ThemePreference; label: string }> = [
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
  { value: "system", label: "System" },
];

export const THEME_COLORS: Record<ResolvedTheme, string> = {
  light: "#f7f3ea",
  dark: "#17130f",
};

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === "light" || value === "dark" || value === "system";
}

export function readThemePreference(storage: Pick<Storage, "getItem">): ThemePreference {
  try {
    const value = storage.getItem(THEME_STORAGE_KEY);
    return isThemePreference(value) ? value : "system";
  } catch {
    return "system";
  }
}

export function resolveTheme(preference: ThemePreference, systemDark: boolean): ResolvedTheme {
  return preference === "system" ? (systemDark ? "dark" : "light") : preference;
}

export function themeOptionSemantics(preference: ThemePreference, option: ThemePreference) {
  const selected = preference === option;
  return { checked: selected, ariaChecked: selected, selected } as const;
}

export function applyTheme(
  documentTarget: Pick<Document, "documentElement" | "querySelector">,
  preference: ThemePreference,
  systemDark: boolean,
) {
  const resolved = resolveTheme(preference, systemDark);
  documentTarget.documentElement.dataset.theme = resolved;
  documentTarget.documentElement.dataset.themePreference = preference;
  documentTarget.documentElement.style.colorScheme = resolved;
  documentTarget
    .querySelector<HTMLMetaElement>('meta[name="theme-color"]')
    ?.setAttribute("content", THEME_COLORS[resolved]);
  return resolved;
}

export function persistThemePreference(
  storage: Pick<Storage, "setItem">,
  preference: ThemePreference,
) {
  try {
    storage.setItem(THEME_STORAGE_KEY, preference);
  } catch {
    // A blocked storage API must not make appearance controls unusable.
  }
}

export function watchSystemTheme(
  media: Pick<MediaQueryList, "addEventListener" | "removeEventListener">,
  currentPreference: () => ThemePreference,
  onSystemChange: (systemDark: boolean) => void,
) {
  const listener = (event: MediaQueryListEvent) => {
    if (currentPreference() === "system") onSystemChange(event.matches);
  };
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
}

export const THEME_BOOTSTRAP_SCRIPT = `(()=>{let p='system';try{const v=localStorage.getItem('${THEME_STORAGE_KEY}');if(v==='light'||v==='dark'||v==='system')p=v}catch{}let d=false;try{d=matchMedia('${THEME_QUERY}').matches}catch{}const r=p==='system'?(d?'dark':'light'):p,e=document.documentElement;e.dataset.theme=r;e.dataset.themePreference=p;e.style.colorScheme=r;const m=document.querySelector('meta[name="theme-color"]');if(m)m.setAttribute('content',r==='dark'?'${THEME_COLORS.dark}':'${THEME_COLORS.light}')})();`;
