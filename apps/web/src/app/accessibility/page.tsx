import type { Metadata } from "next";

export const metadata: Metadata = { title: "Accessibility" };

export default function AccessibilityPage() {
  return (
    <main id="main-content" className="policy-page shell">
      <p className="eyebrow"><span /> Accessibility</p>
      <h1>Access belongs to everyone.</h1>
      <p className="policy-page__lead">EduCore is being built toward WCAG 2.2 Level AA across public and authenticated experiences. This statement will publish the verified audit scope, known limitations, and contact route before launch.</p>
      <h2>Our working commitments</h2>
      <ul>
        <li>Keyboard operation, visible focus, meaningful headings and landmarks.</li>
        <li>Text alternatives, sufficient contrast, scalable content, and clear form errors.</li>
        <li>Reduced-motion support and no essential meaning communicated through color or animation alone.</li>
        <li>Testing with automated tools, manual review, and representative assistive technologies.</li>
      </ul>
    </main>
  );
}
