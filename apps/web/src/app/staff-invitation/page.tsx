import type { Metadata } from "next";
import { StaffInvitation } from "@/components/staff-invitation";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Private staff invitation", robots: { index: false, follow: false }, referrer: "no-referrer" };

export default function StaffInvitationPage() { return <StaffInvitation />; }
