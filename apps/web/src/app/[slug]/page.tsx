import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { PublicInnerPage } from "@/components/public-inner-page";
import { PublicStaffProfiles } from "@/components/public-staff-profiles";
import { publicPages } from "@/content/public-pages";

type PageProps = { params: Promise<{ slug: string }> };

export function generateStaticParams() {
  return Object.keys(publicPages).map((slug) => ({ slug }));
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { slug } = await params;
  const page = publicPages[slug];
  return page ? { title: page.eyebrow, description: page.introduction } : {};
}

export default async function PublicPageRoute({ params }: PageProps) {
  const { slug } = await params;
  const page = publicPages[slug];
  if (!page) notFound();
  return <PublicInnerPage page={page} afterSections={slug === "about" ? <PublicStaffProfiles /> : undefined} />;
}
