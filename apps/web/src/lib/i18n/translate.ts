import { hindi, type Domain } from "./messages";
import { interpolate, type Locale } from "./locale";

const catalogs: Record<Locale, Partial<Record<Domain, Record<string, string>>>> = { en: {}, hi: hindi };

export function translate(locale: Locale, domain: Domain, source: string, values?: Record<string, string | number>) {
  const resource = catalogs[locale];
  return interpolate(resource[domain]?.[source] ?? resource.common?.[source] ?? source, values);
}
