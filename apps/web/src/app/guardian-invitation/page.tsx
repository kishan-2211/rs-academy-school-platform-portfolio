import type { Metadata } from "next";
import { GuardianInvitation } from "@/components/family/guardian-invitation";

export const metadata: Metadata = {
  title: "Guardian invitation",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default async function GuardianInvitationPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string }>;
}) {
  const { token } = await searchParams;
  return <GuardianInvitation initialToken={token ?? ""} />;
}
