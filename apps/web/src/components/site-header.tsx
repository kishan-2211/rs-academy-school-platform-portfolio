"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Brand } from "@/components/brand";
import { ArrowUpRight, Menu, User } from "@/components/icons";
import { site } from "@/content/site";
import { ThemeSelector } from "@/components/theme-selector";
import { LanguageSelector } from "@/components/i18n/language-selector";
import { useT } from "@/components/i18n/language-provider";
import { bindMobileNavigation } from "@/lib/mobile-navigation";

export function SiteHeader() {
  const t = useT("navigation");
  const publicText = useT("public");
  const [open, setOpen] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open || !toggleRef.current || !menuRef.current) return;
    const cleanup = bindMobileNavigation(toggleRef.current, menuRef.current,
      Array.from(document.querySelectorAll<HTMLElement>("main, footer")), () => setOpen(false));
    const desktop = window.matchMedia("(min-width: 981px)");
    const onResize = () => { if (desktop.matches) setOpen(false); };
    desktop.addEventListener("change", onResize);
    return () => {
      cleanup();
      desktop.removeEventListener("change", onResize);
    };
  }, [open]);

  return (
    <>
      <a className="skip-link" href="#main-content">
        {t("Skip to content")} </a>
      <div className="announcement">
        <div className="shell announcement__inner">
          <span className="announcement__pulse" aria-hidden="true" />
          <span className="announcement__message">
            <span>{publicText("{label} are welcome", { label: publicText(site.admissionsLabel) })}</span>
            <span>{publicText("Admissions enquiries")}</span>
          </span>
          <Link href="/admissions">
            {publicText("Explore admissions")} <ArrowUpRight width={15} height={15} />
          </Link>
        </div>
      </div>
      <header className="site-header">
        <div className="shell site-header__inner">
          <Brand onNavigate={() => setOpen(false)} />
          <nav className="desktop-nav" aria-label={t("Primary navigation")}>
            {site.nav.map((item) => (
              <Link key={item.href} href={item.href}>
                {t(item.label)}
              </Link>
            ))}
          </nav>
          <div className="site-header__actions">
            <LanguageSelector />
            <ThemeSelector compact />
            <Link className="portal-link" href="/portal" onNavigate={() => setOpen(false)}>
              <User width={17} height={17} />
              {t("Portal")} </Link>
            <Link className="button button--dark button--small desktop-apply" href="/admissions/apply">
              {t("Apply now")} <ArrowUpRight width={16} height={16} />
            </Link>
            <button
              ref={toggleRef}
              className="menu-button"
              type="button"
              aria-expanded={open}
              aria-controls="mobile-menu"
              onClick={() => setOpen((value) => !value)}
            >
              <span className="sr-only">{open ? t("Close menu") : t("Open menu")}</span>
              <Menu />
            </button>
          </div>
        </div>
      </header>
      <div ref={menuRef} id="mobile-menu" inert={!open} className={`mobile-menu${open ? " mobile-menu--open" : ""}`}>
        <nav className="shell mobile-menu__nav" aria-label={t("Mobile navigation")}>
          {site.nav.map((item, index) => (
            <Link key={item.href} href={item.href} onClick={() => setOpen(false)}>
              <span>0{index + 1}</span>
              {t(item.label)}
            </Link>
          ))}
          <Link href="/contact" onClick={() => setOpen(false)}>
            <span>0{site.nav.length + 1}</span>
            {t("Contact")} </Link>
          <Link className="button button--coral" href="/admissions/apply" onClick={() => setOpen(false)}>
            {t("Start an application")} <ArrowUpRight />
          </Link>
        </nav>
      </div>
    </>
  );
}
