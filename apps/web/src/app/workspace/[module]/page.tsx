import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowUpRight, Check } from "@/components/icons";
import { workspaceModules } from "@/content/workspace-modules";

type PageProps = { params: Promise<{ module: string }> };

export function generateStaticParams() {
  return Object.keys(workspaceModules).map((module) => ({ module }));
}

export default async function WorkspaceModulePage({ params }: PageProps) {
  const { module } = await params;
  const content = workspaceModules[module];
  if (!content) notFound();
  return (
    <section className="module-page">
      <header className="module-page__hero">
        <div>
          <span>{content.eyebrow}</span>
          <h1>{content.title}</h1>
          <p>{content.description}</p>
        </div>
      </header>
      <div className="module-page__grid">
        <article className="module-foundation">
          <span>Module foundation</span>
          <h2>Built around complete workflows, not disconnected screens.</h2>
          <ul>
            {content.capabilities.map((capability) => (
              <li key={capability}>
                <Check /> {capability}
              </li>
            ))}
          </ul>
          <p>
            This capability is documented but not enabled for live school operations. Forms and
            records will appear only after its permissions, audit trail, privacy boundary, and tests
            are implemented and verified.
          </p>
        </article>
        <article className="module-empty">
          <div>
            <span>—</span>
            <i />
          </div>
          <h2>Planned, not active</h2>
          <p>
            No action on this page creates or changes school data. Follow the verified workflows
            already available in the workspace while this module remains on the controlled roadmap.
          </p>
          <Link href="/workspace/setup">
            Review prerequisites <ArrowUpRight />
          </Link>
        </article>
      </div>
    </section>
  );
}
