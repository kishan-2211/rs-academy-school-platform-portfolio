import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { WorkspaceShell } from "@/components/workspace/workspace-shell";

export const metadata = { title: "School workspace", robots: { index: false, follow: false } };

export default async function WorkspaceLayout({ children }: { children: ReactNode }) {
  const cookieStore = await cookies();
  if (!cookieStore.has("educore_session")) redirect("/portal");
  return <WorkspaceShell>{children}</WorkspaceShell>;
}
