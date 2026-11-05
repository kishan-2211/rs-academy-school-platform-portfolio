import type { Metadata } from "next";
import { ContactForm } from "@/components/contact-form";
import { site } from "@/content/site";

export const metadata: Metadata = {
  title: "Contact",
  description: "Contact the school or ask the admissions team a question.",
};

export default function ContactPage() {
  return (
    <main id="main-content" className="contact-page">
      <section className="contact-page__intro">
        <div className="shell contact-page__grid">
          <div>
            <p className="eyebrow"><span /> Contact</p>
            <h1>Let&apos;s start with<br /><em>a conversation.</em></h1>
            <p>Ask a question, arrange a visit, or tell us what would help your family understand the school.</p>
          </div>
          <dl>
            <div><dt>Email</dt><dd><a href={`mailto:${site.email}`}>{site.email}</a></dd></div>
            <div><dt>Principal</dt><dd>{site.principal}</dd></div>
            <div><dt>Telephone</dt><dd><a href={`tel:${site.phoneHref}`}>{site.phone}</a></dd></div>
            <div><dt>Campus</dt><dd>{site.address}</dd></div>
            <div><dt>Office hours</dt><dd>To be confirmed by the school</dd></div>
          </dl>
        </div>
      </section>
      <section className="contact-page__form">
        <div className="shell"><ContactForm /></div>
      </section>
    </main>
  );
}
