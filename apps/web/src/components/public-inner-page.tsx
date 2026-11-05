import Link from "next/link";
import type { ReactNode } from "react";
import { ArrowRight, ArrowUpRight } from "@/components/icons";
import { Reveal } from "@/components/reveal";
import { Text } from "@/components/i18n/language-provider";
import type { PublicPage } from "@/content/public-pages";

export function PublicInnerPage({ page, afterSections }: { page: PublicPage; afterSections?: ReactNode }) {
  return (
    <main id="main-content">
      <section className={`inner-hero inner-hero--${page.theme}`}>
        <div className="inner-hero__grid" aria-hidden="true" />
        <div className="inner-hero__shape" aria-hidden="true"><span /><span /><span /></div>
        <div className="shell inner-hero__inner">
          <p className="eyebrow"><span /> <Text domain="public">{page.eyebrow}</Text></p>
          <h1><Text domain="public">{page.title}</Text><br /><em><Text domain="public">{page.accent}</Text></em></h1>
          <p><Text domain="public">{page.introduction}</Text></p>
        </div>
      </section>

      <section className="inner-sections">
        <div className="shell inner-sections__grid">
          {page.sections.map((section, index) => (
            <Reveal key={section.title} delay={index * 80} className="inner-section-card">
              <span><Text domain="public">{section.label}</Text></span>
              <h2><Text domain="public">{section.title}</Text></h2>
              <p><Text domain="public">{section.body}</Text></p>
              <span className="inner-section-card__arrow"><ArrowUpRight /></span>
            </Reveal>
          ))}
        </div>
      </section>

      {afterSections}

      <section className="inner-next">
        <div className="shell inner-next__inner">
          <div>
            <p className="eyebrow eyebrow--light"><span /> <Text domain="public">Your next step</Text></p>
            <h2><Text domain="public">See the school for yourself.</Text></h2>
          </div>
          <div>
            <p><Text domain="public">Come with questions. Leave with a clearer sense of the people, place, and possibilities.</Text></p>
            <div>
              <Link className="button button--coral" href="/admissions/visit"><Text domain="public">Book a visit</Text> <ArrowUpRight /></Link>
              <Link className="text-link text-link--light" href="/contact"><Text domain="public">Ask a question</Text> <ArrowRight /></Link>
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}
