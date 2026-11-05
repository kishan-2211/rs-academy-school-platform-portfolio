"use client";

import { useId, useState, type FormEvent } from "react";
import Link from "next/link";
import {
  ArrowRight,
  Check,
  Compass,
  Heart,
  Message,
  Shield,
  Sparkles,
  User,
} from "@/components/icons";
import { MotionIllustration } from "@/components/motion-illustration";
import { useT } from "@/components/i18n/language-provider";
import styles from "./login-password.module.css";

type LoginResponse = {
  status: "authenticated" | "mfa_required";
  challengeToken?: string;
  challengeExpiresAt?: string;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

const personas = [
  {
    id: "student",
    label: "Student",
    detail: "My learning, timetable, work, and results",
    icon: Sparkles,
  },
  {
    id: "guardian",
    label: "Parent or guardian",
    detail: "Children, attendance, fees, consent, and messages",
    icon: Heart,
  },
  {
    id: "teacher",
    label: "Teacher",
    detail: "Classes, attendance, assignments, and assessment",
    icon: User,
  },
  {
    id: "staff",
    label: "Staff and operations",
    detail: "Office, accounts, transport, library, HR, and support",
    icon: Message,
  },
  {
    id: "leadership",
    label: "Principal or director",
    detail: "School oversight, approvals, exceptions, and reports",
    icon: Compass,
  },
  {
    id: "admin",
    label: "School administrator",
    detail: "People, permissions, configuration, and integrations",
    icon: Shield,
  },
] as const;

type PersonaId = (typeof personas)[number]["id"];

export function LoginForm({ notice }: { notice?: string }) {
  const t = useT("authentication");
  const passwordId = useId();
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [persona, setPersona] = useState<PersonaId | null>(null);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [challenge, setChallenge] = useState<{ token: string; expiresAt?: string } | null>(null);

  const selectedPersona = personas.find((item) => item.id === persona);
  const SelectedPersonaIcon = selectedPersona?.icon ?? Shield;

  function validate(form: HTMLFormElement) {
    const invalid = Array.from(form.elements).find((element): element is HTMLInputElement =>
      element instanceof HTMLInputElement && !element.validity.valid);
    if (!invalid) return true;
    setError(invalid.validity.valueMissing ? "Please complete this field." : "Please check this value.");
    invalid.focus();
    return false;
  }

  async function continueAfterAuthentication() {
    if (persona) window.sessionStorage.setItem("educore_login_intent", persona);
    const contextResponse = await fetch(`${apiUrl}/api/v1/context`, { credentials: "include" });
    if (!contextResponse.ok) {
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document after the session changes; do not reuse cached portal state.
      window.location.assign("/workspace");
      return;
    }
    const context = (await contextResponse.json()) as { capabilities: string[] };
    const hasGuardianPortal = context.capabilities.includes("portal.guardian");
    const hasStudentPortal = context.capabilities.includes("portal.student");
    const hasWorkspaceAccess = context.capabilities.some(
      (capability) => !["portal.guardian", "portal.student"].includes(capability),
    );
    const guardianExperience = persona === "guardian" && hasGuardianPortal;
    const studentExperience = persona === "student" && hasStudentPortal;
    if (studentExperience || (hasStudentPortal && !hasGuardianPortal && !hasWorkspaceAccess)) {
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document after the session changes; do not reuse cached portal state.
      window.location.assign("/student");
      return;
    }
    if (guardianExperience || (hasGuardianPortal && !hasWorkspaceAccess)) {
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document after the session changes; do not reuse cached portal state.
      window.location.assign("/family");
      return;
    }
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- Recreate the document after the session changes; do not reuse cached portal state.
    window.location.assign("/workspace");
  }

  async function submitCredentials(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPasswordVisible(false);
    setError("");
    if (!validate(event.currentTarget)) return;
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    const payload = {
      identifier: String(form.get("identifier") ?? ""),
      password: String(form.get("password") ?? ""),
      remember: form.get("remember") === "true",
    };

    try {
      const response = await fetch(`${apiUrl}/api/v1/auth/sessions`, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!response.ok) throw new Error("Invalid credentials");
      const result = (await response.json()) as LoginResponse;
      if (result.status === "mfa_required" && result.challengeToken) {
        setChallenge({ token: result.challengeToken, expiresAt: result.challengeExpiresAt });
        setSubmitting(false);
        return;
      }
      await continueAfterAuthentication();
    } catch {
      setError("We could not sign you in. Check your details, or use account recovery.");
      setSubmitting(false);
    }
  }

  async function submitSecondFactor(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!challenge) return;
    setError("");
    if (!validate(event.currentTarget)) return;
    setSubmitting(true);
    const form = new FormData(event.currentTarget);

    try {
      const response = await fetch(`${apiUrl}/api/v1/auth/mfa/challenges/verify`, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          challengeToken: challenge.token,
          code: String(form.get("code") ?? ""),
        }),
      });
      if (!response.ok) throw new Error("Invalid second factor");
      await continueAfterAuthentication();
    } catch {
      setError("That verification code is invalid or expired. Try a new code or a recovery code.");
      setSubmitting(false);
    }
  }

  if (challenge) {
    return (
      <form className="login-form" onSubmit={submitSecondFactor} noValidate>
        <div className="login-form__heading">
          <span>
            <Shield />
          </span>
          <div>
            <small>{t(selectedPersona?.label ?? "Secure account")} · {t("second step")}</small>
            <h2>{t("Verify it's you")}</h2>
          </div>
        </div>
        <p className="login-form__intro">
          {t("Enter the six-digit code from your authenticator, or one unused recovery code.")} </p>
        <label>
          <span>{t("Verification code")}</span>
          <input
            name="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={40}
            autoFocus
            required
          />
        </label>
        <button className="button button--coral" type="submit" disabled={submitting}>
          {submitting ? t("Verifying…") : t("Verify and continue")} <ArrowRight />
        </button>
        <button
          className="login-form__back"
          type="button"
          onClick={() => {
            setChallenge(null);
            setError("");
          }}
        >
          {t("Use a different account")} </button>
        {error && (
          <p className="form-error" role="alert">
            {t(error)}
          </p>
        )}
        {challenge.expiresAt && (
          <p className="login-form__help">
            {t("For your safety, this challenge expires after a few minutes.")} </p>
        )}
      </form>
    );
  }

  if (!persona) {
    return (
      <section className="login-form login-persona" aria-labelledby="login-persona-title">
        {notice ? (
          <p className="login-form__notice">
            <Check /> {t(notice)}
          </p>
        ) : null}
        <div className="login-form__heading">
          <span>
            <Shield />
          </span>
          <div>
            <small>{t("One secure school application")}</small>
            <h2 id="login-persona-title">{t("How are you joining?")}</h2>
          </div>
        </div>
        <p className="login-form__intro">
          {t("Choose your designation to open the right sign-in journey. Your verified account—not this choice—decides what you can access.")} </p>
        <MotionIllustration
          className="login-persona__motion"
          src="/animations/study-discussion.lottie"
          label={t("Two learners discussing a lesson together")}
        />
        <div className="login-persona__grid">
          {personas.map((item) => {
            const Icon = item.icon;
            return (
              <button type="button" key={item.id} onClick={() => setPersona(item.id)}>
                <span>
                  <Icon />
                </span>
                <strong>{t(item.label)}</strong>
                <small>{t(item.detail)}</small>
                <ArrowRight />
              </button>
            );
          })}
        </div>
        <p className="login-persona__public">
          {t("No school account?")} <Link href="/">{t("Continue on the public website")}</Link>
        </p>
      </section>
    );
  }

  return (
    <form className="login-form" onSubmit={submitCredentials} noValidate>
      {notice ? (
        <p className="login-form__notice">
          <Check /> {t(notice)}
        </p>
      ) : null}
      <div className="login-form__heading">
        <span>
          <SelectedPersonaIcon />
        </span>
        <div>
          <small>{t(selectedPersona?.label ?? "Secure account")} {t("sign in")}</small>
          <h2>{t("Welcome back")}</h2>
        </div>
      </div>
      <button
        className="login-form__designation"
        type="button"
        onClick={() => {
          setPersona(null);
          setPasswordVisible(false);
          setError("");
        }}
      >
        <span>
          {t("Signing in as")} <strong>{t(selectedPersona?.label ?? "Secure account")}</strong>
        </span>
        <em>{t("Change designation")}</em>
      </button>
      <label>
        <span>{t("Email or school sign-in ID")}</span>
        <input
          name="identifier"
          type="text"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          autoComplete="username"
          minLength={3}
          maxLength={254}
          required
        />
      </label>
      <div className={styles.field}>
        <label htmlFor={passwordId}>{t("Password")}</label>
        <div className={styles.control}>
          <input
            id={passwordId}
            name="password"
            type={passwordVisible ? "text" : "password"}
            autoComplete="current-password"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            minLength={8}
            required
          />
          <button
            className={styles.toggle}
            type="button"
            aria-controls={passwordId}
            disabled={submitting}
            onClick={() => setPasswordVisible((visible) => !visible)}
          >
            {t(passwordVisible ? "Hide password" : "Show password")}
          </button>
        </div>
      </div>
      <div className="login-form__options">
        <label>
          <input name="remember" type="checkbox" value="true" /> <span>{t("Remember this device")}</span>
        </label>
        <Link href="/account/recovery">{t("Forgot password?")}</Link>
      </div>
      <button className="button button--coral" type="submit" disabled={submitting}>
        {submitting ? t("Signing in…") : t("Continue to portal")} <ArrowRight />
      </button>
      {error && (
        <p className="form-error" role="alert">
          {t(error)}
        </p>
      )}
      <p className="login-form__help">
        {t("Access is invitation-only. Choosing a designation cannot add permissions; the school controls every account and role.")} </p>
    </form>
  );
}
