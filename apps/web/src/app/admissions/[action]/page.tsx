import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ContactForm } from "@/components/contact-form";

const actionContent = {
  apply: {
    title: "Start an application",
    intro: "Tell us how to reach you and which entry point you are considering. The full, saveable application workspace will open after the admissions platform is configured for the school.",
    intent: "application" as const,
  },
  visit: {
    title: "Book a school visit",
    intro: "Share your preferred contact details and questions. The admissions team will confirm an available visit time rather than promising an unverified slot.",
    intent: "visit" as const,
  },
};

type PageProps = { params: Promise<{ action: string }> };

export function generateStaticParams() {
  return Object.keys(actionContent).map((action) => ({ action }));
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { action } = await params;
  const content = actionContent[action as keyof typeof actionContent];
  return content ? { title: content.title, description: content.intro } : {};
}

export default async function AdmissionActionPage({ params }: PageProps) {
  const { action } = await params;
  const content = actionContent[action as keyof typeof actionContent];
  if (!content) notFound();

  return (
    <main id="main-content" className="action-page">
      <section className="action-page__intro">
        <div className="shell">
          <p className="eyebrow"><span /> Admissions</p>
          <h1>{content.title}</h1>
          <p>{content.intro}</p>
        </div>
      </section>
      <section className="action-page__form">
        <div className="shell"><ContactForm intent={content.intent} /></div>
      </section>
    </main>
  );
}
