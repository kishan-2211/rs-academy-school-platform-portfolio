import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { StudentShell } from "@/components/student/student-shell";

export const metadata = {
  title: "Student portal",
  robots: { index: false, follow: false },
};

export default async function StudentLayout({ children }: { children: ReactNode }) {
  const cookieStore = await cookies();
  if (!cookieStore.has("educore_session")) redirect("/portal");
  return <StudentShell>{children}</StudentShell>;
}
