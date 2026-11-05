import { notFound } from "next/navigation";
import { FamilyPaymentStatusView } from "@/components/family/payment-status-view";

export const metadata = { title: "Payment status · Family portal", robots: { index: false, follow: false } };

export default async function FamilyPaymentPage({ params }: { params: Promise<{ attemptId: string }> }) {
  const { attemptId } = await params;
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(attemptId)) notFound();
  // No private payment data is fetched or cached in SSR/RSC. The client reads
  // the actual session-scoped record; provider return query parameters are ignored.
  return <FamilyPaymentStatusView attemptId={attemptId} />;
}
