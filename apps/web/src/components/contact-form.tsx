"use client";

import { useState, type FormEvent } from "react";
import { ArrowRight, Check } from "@/components/icons";

type FormState = "idle" | "submitting" | "success" | "error";

export function ContactForm({ intent = "general" }: { intent?: "general" | "visit" | "application" }) {
  const [state, setState] = useState<FormState>("idle");
  const [message, setMessage] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState("submitting");
    setMessage("");
    const form = event.currentTarget;
    const payload = Object.fromEntries(new FormData(form).entries());

    try {
      const response = await fetch(
        `${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"}/api/v1/public/enquiries`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
          body: JSON.stringify({ ...payload, intent }),
        },
      );
      if (!response.ok) throw new Error("Request failed");
      form.reset();
      setState("success");
      setMessage("Thank you. We received your message and will follow up using the details provided.");
    } catch {
      setState("error");
      setMessage("We could not send this yet. Please try again, or contact the school by phone or email.");
    }
  }

  if (state === "success") {
    return (
      <div className="form-success" role="status">
        <span><Check /></span>
        <h2>Message received</h2>
        <p>{message}</p>
        <button className="text-link" type="button" onClick={() => setState("idle")}>Send another message</button>
      </div>
    );
  }

  return (
    <form className="contact-form" onSubmit={submit}>
      <div className="field-grid">
        <label>
          <span>Your name</span>
          <input name="name" autoComplete="name" minLength={2} maxLength={100} required />
        </label>
        <label>
          <span>Email address</span>
          <input name="email" type="email" autoComplete="email" maxLength={254} required />
        </label>
      </div>
      <div className="field-grid">
        <label>
          <span>Phone number <small>Optional</small></span>
          <input name="phone" type="tel" autoComplete="tel" maxLength={30} />
        </label>
        <label>
          <span>I am interested in</span>
          <select name="topic" defaultValue="">
            <option value="" disabled>Select a topic</option>
            <option value="admissions">Admissions</option>
            <option value="school-visit">A school visit</option>
            <option value="academics">Learning and academics</option>
            <option value="careers">Careers</option>
            <option value="other">Something else</option>
          </select>
        </label>
      </div>
      <label>
        <span>How can we help?</span>
        <textarea name="message" rows={5} minLength={10} maxLength={2000} required />
      </label>
      <label className="consent-field">
        <input name="privacyAccepted" type="checkbox" value="true" required />
        <span>I have read the privacy notice and agree that the school may use these details to respond to this enquiry.</span>
      </label>
      <div className="form-actions">
        <button className="button button--dark" type="submit" disabled={state === "submitting"}>
          {state === "submitting" ? "Sending…" : "Send enquiry"} <ArrowRight />
        </button>
        <p>Required fields are marked by their browser validation. We do not ask for sensitive student documents here.</p>
      </div>
      {state === "error" && <p className="form-error" role="alert">{message}</p>}
    </form>
  );
}
