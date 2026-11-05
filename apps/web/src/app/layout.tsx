import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import { LanguageProvider } from "@/components/i18n/language-provider";
import { LanguagePreferenceNotice } from "@/components/i18n/language-selector";
import { LANGUAGE_COOKIE, resolveLocale } from "@/lib/i18n/locale";
import { AppChrome } from "@/components/app-chrome";
import { PwaRegistration } from "@/components/pwa-registration";
import { site } from "@/content/site";
import { THEME_BOOTSTRAP_SCRIPT, THEME_COLORS } from "@/lib/theme";
import "@fontsource-variable/manrope/wght.css";
import "@fontsource-variable/newsreader/wght.css";
import "@fontsource-variable/newsreader/wght-italic.css";
import "./globals.css";
import "@/components/i18n/language.css";

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_APP_URL ?? "http://localhost:3000"),
  title: {
    default: `${site.schoolName} — ${site.motto}`,
    template: `%s — ${site.schoolName}`,
  },
  description: site.description,
  openGraph: {
    title: site.schoolName,
    description: site.description,
    type: "website",
    locale: "en_IN",
    images: [{ url: site.logo, alt: `${site.schoolName} emblem` }],
  },
  icons: { icon: site.logo, apple: site.logo },
  manifest: "/manifest.webmanifest",
  robots: { index: true, follow: true },
};

export const viewport: Viewport = {
  colorScheme: "light dark",
};

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  const locale = resolveLocale(null, null, (await cookies()).get(LANGUAGE_COOKIE)?.value);
  const schoolSchema = {
    "@context": "https://schema.org",
    "@type": "School",
    name: site.schoolName,
    description: site.description,
    logo: site.logo,
    email: site.email,
    telephone: site.phoneHref,
    address: {
      "@type": "PostalAddress",
      streetAddress: "R.S. Academy, Suaari, Danganj",
      addressLocality: "Varanasi",
      addressRegion: "Uttar Pradesh",
      addressCountry: "IN",
    },
    areaServed: ["Suaari", "Danganj", "Varanasi"],
  };
  return (
    <html
      lang={locale}
      data-theme="light"
      data-theme-preference="system"
      suppressHydrationWarning
    >
      <head>
        <meta name="theme-color" content={THEME_COLORS.light} />
        <script
          id="theme-bootstrap"
          dangerouslySetInnerHTML={{ __html: THEME_BOOTSTRAP_SCRIPT }}
        />
      </head>
      <body>
        <script
          type="application/ld+json"
          dangerouslySetInnerHTML={{ __html: JSON.stringify(schoolSchema).replaceAll("<", "\\u003c") }}
        />
        <noscript>
          <style>{`.reveal{opacity:1!important;transform:none!important}`}</style>
        </noscript>
        <PwaRegistration />
        <LanguageProvider initialLocale={locale}>
          <AppChrome>{children}</AppChrome>
          <LanguagePreferenceNotice />
        </LanguageProvider>
      </body>
    </html>
  );
}
