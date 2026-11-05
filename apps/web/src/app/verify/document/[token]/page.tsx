import { DocumentVerification } from "@/components/document-verification";
import "./verification.css";

export const metadata = {
  title: "Verify official document · R.S. Academy",
  robots: { index: false, follow: false },
};

export default async function DocumentVerificationPage({
  params,
}: {
  params: Promise<{ token: string }>;
}) {
  const { token } = await params;
  return <DocumentVerification token={token} />;
}
