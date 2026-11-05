import type { Metadata } from "next";

export const metadata: Metadata = { title: "Privacy notice" };

export default function PrivacyPage() {
  return (
    <main id="main-content" className="policy-page shell">
      <p className="eyebrow"><span /> Privacy</p>
      <h1>Privacy notice placeholder</h1>
      <p className="policy-page__lead">This page must be replaced with a notice approved for the real school, jurisdiction, data practices, providers, and launch date before personal information is collected in production.</p>
      <h2>What the final notice will explain</h2>
      <ul>
        <li>Who controls personal information and how to contact the responsible person.</li>
        <li>What information is collected, why it is needed, and the applicable basis or consent.</li>
        <li>Who receives it, where providers process it, and how long each category is retained.</li>
        <li>How a person can request access, correction, withdrawal, export, restriction, or deletion where applicable.</li>
        <li>How to raise a concern and how the school responds to a data incident.</li>
      </ul>
    </main>
  );
}
