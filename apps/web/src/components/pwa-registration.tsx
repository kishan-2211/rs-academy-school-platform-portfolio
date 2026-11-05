"use client";

import { useEffect } from "react";

export function PwaRegistration() {
  useEffect(() => {
    if (!("serviceWorker" in navigator) || !window.isSecureContext) return;
    void navigator.serviceWorker.register("/sw.js", { scope: "/" }).then((registration) => {
      void registration.update();
    }).catch(() => {
      // Push controls expose actionable setup errors; global browsing must continue normally.
    });
  }, []);

  return null;
}
