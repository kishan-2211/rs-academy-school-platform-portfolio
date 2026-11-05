"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

export function AppChrome({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const isWorkspace =
    pathname.startsWith("/workspace") ||
    pathname.startsWith("/family") ||
    pathname.startsWith("/student");

  if (isWorkspace) return children;

  return (
    <>
      <SiteHeader />
      {children}
      <SiteFooter />
    </>
  );
}
