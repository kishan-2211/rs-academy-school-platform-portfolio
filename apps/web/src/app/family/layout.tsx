import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { FamilyShell } from "@/components/family/family-shell";

export const metadata = { title: "Family portal", robots: { index: false, follow: false } };

export default async function FamilyLayout({ children }: { children: ReactNode }) {
  const cookieStore = await cookies();
  if (!cookieStore.has("educore_session")) redirect("/portal");
  return <FamilyShell>{children}</FamilyShell>;
}
