import type { Metadata } from "next";
import { LoginForm } from "@/components/login-form";
import { Check } from "@/components/icons";
import { Text } from "@/components/i18n/language-provider";

export const metadata: Metadata = {
  title: "School portal",
  robots: { index: false, follow: false },
};

export default async function PortalPage({
  searchParams,
}: {
  searchParams: Promise<{ invitation?: string }>;
}) {
  const { invitation } = await searchParams;
  return (
    <main id="main-content" className="portal-page">
      <section className="portal-page__story">
        <div className="portal-page__rings" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <div>
          <p className="eyebrow eyebrow--light">
            <span /> <Text domain="authentication">One application · every school role</Text>
          </p>
          <h1>
            <Text domain="authentication">One doorway,</Text>
            <br />
            <em><Text domain="authentication">the right experience.</Text></em>
          </h1>
          <ul>
            <li>
              <Check /> <Text domain="authentication">Choose your designation before entering credentials</Text>
            </li>
            <li>
              <Check /> <Text domain="authentication">Switch only between roles your account is authorized to use</Text>
            </li>
            <li>
              <Check /> <Text domain="authentication">Access shaped by role, school, class, and relationship</Text>
            </li>
          </ul>
        </div>
      </section>
      <section className="portal-page__login">
        <LoginForm
          notice={
            invitation === "accepted"
              ? "Family access is active. Sign in as Parent or guardian to continue."
              : undefined
          }
        />
      </section>
    </main>
  );
}
