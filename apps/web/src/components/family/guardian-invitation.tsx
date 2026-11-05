"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { ArrowRight, Check, Shield } from "@/components/icons";
import { MotionIllustration } from "@/components/motion-illustration";
import { useLanguage } from "@/components/i18n/language-provider";
import {
  guardianInvitationMessage,
  guardianInvitationProblemKey,
  type GuardianInvitationErrorKey,
  type GuardianInvitationMessageKey,
} from "@/lib/i18n/guardian-invitation-messages";

type InvitationPreview = {
  schoolName: string;
  maskedDestination: string;
  expiresAt: string;
  existingAccount: boolean;
  status: string;
};

type ApiProblem = { code?: unknown };
const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

class InvitationProblem extends Error {
  constructor(readonly key: GuardianInvitationErrorKey) {
    super(key);
  }
}

async function problemMessage(response: Response, fallback: GuardianInvitationErrorKey) {
  try {
    const problem = (await response.json()) as ApiProblem;
    return guardianInvitationProblemKey(problem?.code, fallback);
  } catch {
    return fallback;
  }
}

function expiryLabel(value: string, locale: string) {
  return new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", {
    day: "numeric",
    month: "long",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value));
}

export function GuardianInvitation({ initialToken }: { initialToken: string }) {
  const { locale } = useLanguage();
  const t = (key: GuardianInvitationMessageKey) => guardianInvitationMessage(locale, key);
  const [preview, setPreview] = useState<InvitationPreview | null>(null);
  const [loading, setLoading] = useState(Boolean(initialToken));
  const [submitting, setSubmitting] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const [error, setError] = useState<GuardianInvitationErrorKey | null>(initialToken ? null : "missing");

  useEffect(() => {
    window.history.replaceState(null, "", "/guardian-invitation");
    if (!initialToken) return;
    let cancelled = false;
    fetch(`${apiUrl}/api/v1/public/guardian-invitations/previews`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: initialToken }),
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new InvitationProblem(
            await problemMessage(
              response,
              "unavailable",
            ),
          );
        }
        const result = (await response.json()) as InvitationPreview;
        if (!cancelled) setPreview(result);
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(reason instanceof InvitationProblem ? reason.key : "unavailable");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [initialToken]);

  async function acceptInvitation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const form = new FormData(event.currentTarget);
    const password = String(form.get("password") ?? "");
    const confirmation = String(form.get("confirmation") ?? "");
    if (!preview?.existingAccount && password !== confirmation) {
      setError("mismatch");
      return;
    }
    setSubmitting(true);
    try {
      const response = await fetch(`${apiUrl}/api/v1/public/guardian-invitations/acceptances`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: initialToken, password }),
      });
      if (!response.ok) {
        throw new InvitationProblem(await problemMessage(response, "acceptUnavailable"));
      }
      setAccepted(true);
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Discard invitation-token and password form state before opening a fresh sign-in document.
      window.setTimeout(() => window.location.assign("/portal?invitation=accepted"), 1500);
    } catch (reason) {
      setError(reason instanceof InvitationProblem ? reason.key : "acceptUnavailable");
      setSubmitting(false);
    }
  }

  return (
    <main id="main-content" className="guardian-invitation-page">
      <section className="guardian-invitation-page__story">
        <span>{t("familyAccess")}</span>
        <h1>
          {t("title")} <em>{t("titleEmphasis")}</em>
        </h1>
        <p>
          {t("intro")}
        </p>
        <ul>
          <li>
            <Check /> {t("oneRelationship")}
          </li>
          <li>
            <Check /> {t("passwordProtected")}
          </li>
          <li>
            <Check /> {t("schoolAudited")}
          </li>
        </ul>
      </section>
      <section className="guardian-invitation-page__action">
        {loading ? (
          <div className="guardian-invitation-card guardian-invitation-card--loading">
            <i />
            <i />
            <p role="status">{t("verifying")}</p>
          </div>
        ) : accepted ? (
          <div className="guardian-invitation-card guardian-invitation-card--accepted">
            <MotionIllustration
              className="guardian-invitation-card__motion"
              src="/animations/successful-target.lottie"
              label={t("successAnimation")}
              loop={false}
            />
            <h2>{t("successTitle")}</h2>
            <p role="status">{t("successBody")}</p>
          </div>
        ) : preview ? (
          <form className="guardian-invitation-card" aria-label={t("formLabel")} onSubmit={acceptInvitation}>
            <div className="guardian-invitation-card__heading">
              <span>
                <Shield />
              </span>
              <div>
                <small>{t("invitationBadge")}</small>
                <h2>{preview.schoolName}</h2>
              </div>
            </div>
            <dl>
              <div>
                <dt>{t("invitedEmail")}</dt>
                <dd>{preview.maskedDestination}</dd>
              </div>
              <div>
                <dt>{t("expires")}</dt>
                <dd>{expiryLabel(preview.expiresAt, locale)}</dd>
              </div>
            </dl>
            <p>
              {preview.existingAccount
                ? t("existingInstruction")
                : t("newInstruction")}
            </p>
            <label>
              <span>{preview.existingAccount ? t("currentPassword") : t("createPassword")}</span>
              <input
                name="password"
                type="password"
                autoComplete={preview.existingAccount ? "current-password" : "new-password"}
                minLength={12}
                maxLength={200}
                required
              />
              {!preview.existingAccount ? (
                <small>{t("passwordHelp")}</small>
              ) : null}
            </label>
            {!preview.existingAccount ? (
              <label>
                <span>{t("confirmPassword")}</span>
                <input
                  name="confirmation"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  maxLength={200}
                  required
                />
              </label>
            ) : null}
            <button className="button button--coral" type="submit" disabled={submitting}>
              {submitting ? t("accepting") : t("acceptContinue")} <ArrowRight />
            </button>
            {error ? (
              <p className="form-error" role="alert">
                {t(error)}
              </p>
            ) : null}
            <small className="guardian-invitation-card__privacy">
              {t("privacy")}
            </small>
          </form>
        ) : (
          <div className="guardian-invitation-card guardian-invitation-card--invalid">
            <Shield />
            <h2>{t("unavailableTitle")}</h2>
            <p role="alert">{t(error ?? "unavailable")}</p>
            <Link className="button button--dark" href="/contact">
              {t("contactSchool")}
            </Link>
          </div>
        )}
      </section>
    </main>
  );
}
