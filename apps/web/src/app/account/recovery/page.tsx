import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, Shield } from "@/components/icons";

export const metadata: Metadata = {
  title: "Account recovery",
  robots: { index: false, follow: false },
};

export default function AccountRecoveryPage() {
  return (
    <main id="main-content" className="simple-page simple-page--center">
      <span className="recovery-icon"><Shield /></span>
      <p className="eyebrow"><span /> Account recovery</p>
      <h1>Recover access safely.</h1>
      <p>
        Self-service recovery will be enabled after the school confirms its identity-verification and
        support policy. Until then, contact an authorized school administrator—no one should ask you
        to share a password or one-time code.
      </p>
      <Link className="button button--dark" href="/contact">Contact the school <ArrowRight /></Link>
    </main>
  );
}
