"use client";

import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { browserSupportsPush, pushCsrfToken, pushDeviceActions, waitForPushRegistration } from "@/lib/push-notification-client";
import { Message } from "@/components/icons";
import { useT } from "@/components/i18n/language-provider";
import styles from "./push-notification-control.module.css";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const subscribeSupport = () => () => {};
const serverSupport = () => false;

type PushStatus = {
  schoolId: string;
  registrationEnabled: boolean;
  applicationServerKey: string | null;
  allowPush: boolean;
  activeDeviceCount: number;
  devices: Array<{
    id: string;
    label: string | null;
    createdAt: string;
    lastConfirmedAt: string;
  }>;
};

type ApiProblem = { detail?: string };

async function problemMessage(response: Response, fallback: string) {
  try {
    const problem = (await response.json()) as ApiProblem;
    return problem.detail || fallback;
  } catch {
    return fallback;
  }
}

function urlBase64ToUint8Array(value: string) {
  const padding = "=".repeat((4 - (value.length % 4)) % 4);
  const base64 = (value + padding).replaceAll("-", "+").replaceAll("_", "/");
  const raw = window.atob(base64);
  return Uint8Array.from(raw, (character) => character.charCodeAt(0));
}

function bytesToUrlBase64(value: ArrayBuffer | null) {
  if (!value) return "";
  const bytes = new Uint8Array(value);
  let raw = "";
  bytes.forEach((byte) => { raw += String.fromCharCode(byte); });
  return window.btoa(raw).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

async function fetchPushStatus(schoolId: string) {
  const response = await fetch(
    `${apiUrl}/api/v1/notifications/push?schoolId=${encodeURIComponent(schoolId)}`,
    { credentials: "include", cache: "no-store" },
  );
  if (!response.ok) {
    throw new Error(await problemMessage(response, "Phone notification settings are unavailable."));
  }
  return (await response.json()) as PushStatus;
}

export function PushNotificationControl({ schoolId }: { schoolId: string }) {
  const t = useT("notifications");
  const [status, setStatus] = useState<PushStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const supported = useSyncExternalStore(subscribeSupport, browserSupportsPush, serverSupport);
  const deviceActions = pushDeviceActions(status, supported, busy || loading);

  const refresh = useCallback(async () => {
    setStatus(await fetchPushStatus(schoolId));
  }, [schoolId]);

  useEffect(() => {
    let cancelled = false;
    fetchPushStatus(schoolId).then((next) => {
      if (!cancelled) setStatus(next);
    }).catch((reason: Error) => {
      if (!cancelled) setError(reason.message);
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [schoolId]);

  async function enable() {
    setBusy(true);
    setError("");
    setNotice("");
    let created = false;
    let subscription: PushSubscription | null = null;
    try {
      if (!supported) throw new Error("This browser does not support secure phone notifications. Your in-app inbox still works.");
      if (!status?.registrationEnabled || !status.applicationServerKey) {
        throw new Error("Phone notifications are awaiting production provider setup. Your in-app inbox remains active.");
      }
      const permission = await Notification.requestPermission();
      if (permission !== "granted") {
        throw new Error("Notifications were not allowed. You can change this website permission in your browser settings.");
      }
      const registration = await waitForPushRegistration(navigator.serviceWorker.ready);
      subscription = await registration.pushManager.getSubscription();
      if (!subscription) {
        subscription = await registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: urlBase64ToUint8Array(status.applicationServerKey),
        });
        created = true;
      }
      const response = await fetch(`${apiUrl}/api/v1/notifications/push/subscriptions`, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await pushCsrfToken() },
        body: JSON.stringify({
          schoolId,
          endpoint: subscription.endpoint,
          p256dh: bytesToUrlBase64(subscription.getKey("p256dh")),
          auth: bytesToUrlBase64(subscription.getKey("auth")),
          deviceLabel: null,
        }),
      });
      if (!response.ok) throw new Error(await problemMessage(response, "This phone could not be registered."));
      setStatus((await response.json()) as PushStatus);
      setNotice("This browser is registered. Verified school alerts can appear after provider delivery is activated.");
    } catch (reason) {
      if (created && subscription) await subscription.unsubscribe().catch(() => false);
      setError(reason instanceof Error ? reason.message : "This phone could not be registered.");
    } finally {
      setBusy(false);
    }
  }

  async function disableThisDevice() {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      if (!supported) throw new Error("This browser cannot manage a push subscription.");
      const registration = await waitForPushRegistration(navigator.serviceWorker.ready);
      const subscription = await registration.pushManager.getSubscription();
      if (!subscription) {
        setNotice("This browser is not registered for phone notifications.");
        await refresh();
        return;
      }
      const response = await fetch(`${apiUrl}/api/v1/notifications/push/subscriptions/revoke`, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await pushCsrfToken() },
        body: JSON.stringify({ schoolId, endpoint: subscription.endpoint }),
      });
      if (!response.ok) throw new Error(await problemMessage(response, "This phone could not be removed."));
      const next = (await response.json()) as PushStatus;
      const unsubscribed = await subscription.unsubscribe();
      setStatus(next);
      setNotice(unsubscribed
        ? "Phone notifications are off for this browser. Verified in-app alerts remain available."
        : "The school registration was removed, but the browser subscription could not be cleared. Check browser site settings.");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "This phone could not be removed.");
    } finally {
      setBusy(false);
    }
  }

  const active = Boolean(status?.allowPush && status.activeDeviceCount > 0);
  const summary = loading
    ? "Checking secure notification status…"
    : !supported
      ? "This browser supports only the verified in-app inbox."
      : !status?.registrationEnabled
        ? "Secure registration is prepared; production provider setup is still required."
        : active
          ? t(status.activeDeviceCount === 1 ? "{count} phone or browser is registered." : "{count} phone or browser are registered.", { count: status.activeDeviceCount })
          : "No phone or browser is registered yet.";

  return (
    <section className={styles.card} aria-labelledby={`push-title-${schoolId}`}>
      <div className={styles.copy}>
        <span className={styles.icon}><Message /></span>
        <div>
          <h2 id={`push-title-${schoolId}`}>{t("Phone notifications")}</h2>
          <p>{t("Opt in to verified holiday and emergency alerts. Permission is requested only when you choose to enable it; private portal pages are never cached for offline use.")}</p>
          <span className={styles.status} aria-live="polite">
            <i className={`${styles.dot} ${active ? styles.dotActive : ""}`} /> {t(summary)}
          </span>
        </div>
      </div>
      <div className={styles.actions}>
          <button className={styles.button} type="button" disabled={!deviceActions.canEnable} onClick={enable}>
            {busy ? t("Working…") : t("Enable on this device")}
          </button>
          <button className={`${styles.button} ${styles.buttonSecondary}`} type="button" disabled={!deviceActions.canDisable} onClick={disableThisDevice}>
            {busy ? t("Working…") : t("Turn off this device")}
          </button>
      </div>
      {error ? <p className={`${styles.notice} ${styles.error}`} role="alert">{t(error)}</p> : null}
      {!error && notice ? <p className={styles.notice} role="status">{t(notice)}</p> : null}
    </section>
  );
}
