"use client";

import { useId, useState, type FormHTMLAttributes } from "react";
import { useT } from "./language-provider";

// Preserve existing submission handlers and payloads, while presenting browser
// validation in the application's language instead of the browser's UI language.
export function LocalizedForm({ children, onSubmit, ...props }: FormHTMLAttributes<HTMLFormElement>) {
  const t = useT("common");
  const errorId = useId();
  const [error, setError] = useState("");
  return <form {...props} noValidate aria-describedby={error ? errorId : props["aria-describedby"]}
    onSubmit={(event) => {
      const invalid = Array.from(event.currentTarget.elements).find((field) =>
        (field instanceof HTMLInputElement || field instanceof HTMLSelectElement || field instanceof HTMLTextAreaElement)
        && field.willValidate && !field.validity.valid);
      if (invalid instanceof HTMLInputElement || invalid instanceof HTMLSelectElement || invalid instanceof HTMLTextAreaElement) {
        event.preventDefault();
        setError(invalid.validity.valueMissing ? "Please complete this field." : "Please check this value.");
        invalid.focus();
        return;
      }
      setError("");
      onSubmit?.(event);
    }}>
    {children}
    {error && <p id={errorId} role="alert">{t(error)}</p>}
  </form>;
}
