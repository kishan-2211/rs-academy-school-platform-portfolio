import type { Metadata } from "next";

export const metadata: Metadata = { title: "Terms" };

export default function TermsPage() {
  return (
    <main id="main-content" className="policy-page shell">
      <p className="eyebrow"><span /> Terms</p>
      <h1>Website terms placeholder</h1>
      <p className="policy-page__lead">The school must approve final website and portal terms before launch. They will cover acceptable use, account responsibility, intellectual property, service availability, official-record status, and applicable law without weakening statutory rights.</p>
    </main>
  );
}
