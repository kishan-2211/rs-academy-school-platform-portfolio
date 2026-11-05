"use client";

import Link from "next/link";
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type MutableRefObject,
} from "react";
import { useT } from "@/components/i18n/language-provider";
import { documentProblem, loadFamilyDocuments, loadFamilyPdf } from "@/lib/family-documents";
import { issueAttempt, type IssueAttempt } from "@/lib/official-certificates";
import {
  availableReceiptTemplates,
  loadFeeReceiptPage,
  loadStaffReceiptPdf,
  receiptCsrfToken,
  receiptDocumentErrors,
  receiptIssueBody,
  sendReceiptIssue,
  type FeeReceiptPage,
  type ReceiptDocument,
  type ReceiptIssueResult,
} from "@/lib/fee-receipt-documents";
import styles from "./receipt-pdf-actions.module.css";

type Props = { paymentId: string; receiptNumber: string; paymentStatus: string } & (
  | { audience: "staff"; schoolId: string; externallyBusy?: boolean }
  | { audience: "family"; learnerId: string }
);
type HistoryItem = Pick<ReceiptDocument, "id" | "documentNumber" | "status">;
type History = {
  page: number;
  hasMore: boolean;
  documents: HistoryItem[];
  staff: ReceiptDocument[];
  context: FeeReceiptPage | null;
};
const statusCopy = {
  PENDING_APPROVAL: "Awaiting independent approval",
  ISSUED: "Issued PDF",
  REJECTED: "Request rejected",
  CANCELLED: "Cancelled copy",
  SUPERSEDED: "Superseded copy",
} as const;
const downloadable = (status: string) => ["ISSUED", "CANCELLED", "SUPERSEDED"].includes(status);
function safeError(reason: unknown): string {
  if (reason instanceof Error) {
    if (reason.message === documentProblem(401)) return receiptDocumentErrors.session;
    if (reason.message === documentProblem(403)) return receiptDocumentErrors.scope;
    if (
      [
        "The server did not return a PDF. Please try again later.",
        "The PDF is empty. Contact the school for a new copy.",
      ].includes(reason.message)
    )
      return receiptDocumentErrors.invalidPdf;
  }
  return reason instanceof Error &&
    Object.values(receiptDocumentErrors).some((copy) => copy === reason.message)
    ? reason.message
    : receiptDocumentErrors.unavailable;
}

/** Lazy, exact-source lookup: no bulk document requests and no browser-created financial PDFs. */
export function ReceiptPdfActions(props: Props) {
  const scope = props.audience === "staff" ? props.schoolId : props.learnerId;
  return (
    <ScopedReceiptPdfActions key={`${props.audience}:${scope}:${props.paymentId}`} props={props} />
  );
}

function ScopedReceiptPdfActions({ props }: { props: Props }) {
  const t = useT("fees");
  const id = useId();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [resumeUncertain, setResumeUncertain] = useState(false);
  // Remains only in memory across panel collapse; server history is authoritative after navigation.
  const attemptRef = useRef<IssueAttempt | null>(null);
  const scope = props.audience === "staff" ? props.schoolId : props.learnerId;
  return (
    <section
      className={styles.root}
      aria-label={t("PDFs for receipt {receipt}", { receipt: props.receiptNumber })}
    >
      <button
        type="button"
        aria-expanded={open}
        aria-controls={id}
        disabled={busy}
        onClick={() => {
          setResumeUncertain(!!attemptRef.current);
          setOpen((value) => !value);
        }}
      >
        {t(open ? "Hide receipt PDFs" : "Receipt PDFs")}
      </button>
      <div id={id} hidden={!open}>
        {open && (
          <ReceiptPdfPanel
            key={`${props.audience}:${scope}:${props.paymentId}`}
            props={props}
            attemptRef={attemptRef}
            resumeUncertain={resumeUncertain}
            onBusy={setBusy}
          />
        )}
      </div>
    </section>
  );
}

function ReceiptPdfPanel({
  props,
  attemptRef,
  resumeUncertain,
  onBusy,
}: {
  props: Props;
  attemptRef: MutableRefObject<IssueAttempt | null>;
  resumeUncertain: boolean;
  onBusy: (busy: boolean) => void;
}) {
  const t = useT("fees");
  const id = useId();
  const [history, setHistory] = useState<History | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [templateId, setTemplateId] = useState("");
  const [reason, setReason] = useState("");
  const [uncertain, setUncertain] = useState(resumeUncertain);
  const [acknowledged, setAcknowledged] = useState<ReceiptIssueResult | null>(null);
  const request = useRef<AbortController | null>(null);
  const urls = useRef(new Set<string>());
  const handleFailure = useCallback((problem: unknown) => {
    const copy = safeError(problem);
    if (copy === receiptDocumentErrors.session || copy === receiptDocumentErrors.scope) {
      setHistory(null);
      setAcknowledged(null);
      setTemplateId("");
      setReason("");
      setMessage("");
      urls.current.forEach((url) => URL.revokeObjectURL(url));
      urls.current.clear();
      // Do not erase an unacknowledged immutable attempt: a retry must never become a new issue.
    }
    setError(copy);
  }, []);
  async function readHistory(page: number, signal: AbortSignal): Promise<History> {
    if (props.audience === "staff") {
      const result = await loadFeeReceiptPage(props.schoolId, props.paymentId, page, signal);
      if (result.receiptNumber !== props.receiptNumber)
        throw new Error(receiptDocumentErrors.unavailable);
      return {
        page,
        hasMore: result.hasMore,
        documents: result.documents,
        staff: result.documents,
        context: result,
      };
    }
    const result = await loadFamilyDocuments(props.learnerId, page, signal, props.paymentId);
    return {
      page,
      hasMore: result.hasMore,
      documents: result.documents.map((item) => ({
        id: item.id,
        documentNumber: item.documentNumber,
        status: "ISSUED",
      })),
      staff: [],
      context: null,
    };
  }
  // A mounted panel is keyed to its exact school/child and payment. Closing/changing child aborts it.
  const initialRead = useRef(readHistory);
  useEffect(() => {
    const controller = new AbortController();
    request.current = controller;
    void initialRead
      .current(0, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setHistory(value);
      })
      .catch((problem: unknown) => {
        if (!controller.signal.aborted) handleFailure(problem);
      })
      .finally(() => {
        if (request.current === controller) request.current = null;
      });
    const activeUrls = urls.current;
    return () => {
      controller.abort();
      request.current?.abort();
      activeUrls.forEach((url) => URL.revokeObjectURL(url));
      activeUrls.clear();
    };
  }, [handleFailure]);

  async function refresh(append = false) {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    onBusy(true);
    setError("");
    try {
      const next = await readHistory(append && history ? history.page + 1 : 0, controller.signal);
      if (controller.signal.aborted) return;
      setHistory((previous) =>
        append && previous
          ? {
              ...next,
              documents: [
                ...previous.documents,
                ...next.documents.filter(
                  (item) => !previous.documents.some((old) => old.id === item.id),
                ),
              ],
              staff: [
                ...previous.staff,
                ...next.staff.filter((item) => !previous.staff.some((old) => old.id === item.id)),
              ],
            }
          : next,
      );
    } catch (problem: unknown) {
      if (!controller.signal.aborted) handleFailure(problem);
    } finally {
      if (request.current === controller) request.current = null;
      if (!controller.signal.aborted) {
        setBusy(false);
        onBusy(false);
      }
    }
  }
  async function issue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (request.current || props.audience !== "staff" || props.externallyBusy || acknowledged)
      return;
    const candidates = history?.context
      ? availableReceiptTemplates(history.context, history.staff)
      : [];
    if (
      !attemptRef.current &&
      (props.paymentStatus !== "POSTED" ||
        !history ||
        error ||
        !candidates.some((item) => item.id === templateId))
    )
      return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    onBusy(true);
    setError("");
    setMessage("");
    let dispatched = false;
    try {
      const csrf = await receiptCsrfToken(controller.signal);
      if (controller.signal.aborted) return;
      if (!attemptRef.current)
        attemptRef.current = issueAttempt(
          null,
          receiptIssueBody(props.schoolId, props.paymentId, templateId, reason),
          () => crypto.randomUUID(),
        );
      dispatched = true;
      const result = await sendReceiptIssue(attemptRef.current, csrf, controller.signal);
      if (controller.signal.aborted) return;
      setAcknowledged(result);
      setUncertain(false);
      attemptRef.current = null;
      setMessage(
        "The PDF request was recorded. Refresh receipt history to see its current status.",
      );
    } catch (problem: unknown) {
      if (!controller.signal.aborted) {
        handleFailure(problem);
        if (dispatched || uncertain) {
          setUncertain(true);
          setError(
            "The PDF request is uncertain. Refresh history and retry only this unchanged request; do not create a new request.",
          );
        }
      }
    } finally {
      if (request.current === controller) request.current = null;
      if (!controller.signal.aborted) {
        setBusy(false);
        onBusy(false);
      }
    }
  }
  async function download(item: HistoryItem) {
    if (request.current || !downloadable(item.status)) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    onBusy(true);
    setError("");
    setMessage("");
    try {
      const blob =
        props.audience === "staff"
          ? await loadStaffReceiptPdf(props.schoolId, item.id, controller.signal)
          : await loadFamilyPdf(props.learnerId, item.id, controller.signal);
      if (controller.signal.aborted) return;
      const url = URL.createObjectURL(blob);
      urls.current.add(url);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `fee-receipt-${item.id}.pdf`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => {
        URL.revokeObjectURL(url);
        urls.current.delete(url);
      }, 30000);
      setMessage(
        "Download requested. Check your browser’s downloads and keep the PDF private on shared devices.",
      );
    } catch (problem: unknown) {
      if (!controller.signal.aborted) handleFailure(problem);
    } finally {
      if (request.current === controller) request.current = null;
      if (!controller.signal.aborted) {
        setBusy(false);
        onBusy(false);
      }
    }
  }
  const candidates =
    history?.context && !error && props.paymentStatus === "POSTED"
      ? availableReceiptTemplates(history.context, history.staff)
      : [];
  const selected = candidates.find((item) => item.id === templateId);
  const canRequest = props.audience === "staff" && !props.externallyBusy && !acknowledged;
  return (
    <div className={styles.panel} aria-busy={busy || (!history && !error)}>
      <p>
        {t(
          "PDF copies use the existing school template and approval process. They never collect money again.",
        )}
      </p>
      {error && (
        <p className={styles.error} role="alert">
          {t(error)}
        </p>
      )}
      {message && <p role="status">{t(message)}</p>}
      {!history && !error && <p role="status">{t("Checking receipt PDF history…")}</p>}
      {history?.documents.length === 0 && (
        <p>
          {t("No issued receipt PDF is available yet. The numbered fee payment remains recorded.")}
        </p>
      )}
      {history?.documents.map((item) => (
        <div className={styles.document} key={item.id}>
          <strong>{item.documentNumber}</strong>
          <span data-status={item.status}>{t(statusCopy[item.status])}</span>
          {downloadable(item.status) && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void download(item)}
              aria-label={t("Download PDF {number}", { number: item.documentNumber })}
            >
              {t("Download PDF")}
            </button>
          )}
          {["CANCELLED", "SUPERSEDED"].includes(item.status) && (
            <small>{t("Historical copy only; not a current valid document.")}</small>
          )}
        </div>
      ))}
      <div className={styles.actions}>
        <button
          type="button"
          disabled={busy || (!history && !error)}
          onClick={() => void refresh()}
        >
          {t("Refresh receipt PDF history")}
        </button>
        {history?.hasMore && (
          <button
            type="button"
            disabled={busy || history.page >= 10000}
            onClick={() => void refresh(true)}
          >
            {t("Load more receipt PDF history")}
          </button>
        )}
      </div>
      {acknowledged && (
        <p role="status">
          {acknowledged.documentNumber} · {t(statusCopy[acknowledged.status])}
        </p>
      )}
      {props.audience === "staff" && (
        <>
          {history?.hasMore && (
            <p>
              {t(
                "Load the complete history before requesting a new copy. Existing or cancelled copies are not duplicated.",
              )}
            </p>
          )}
          {history && !history.hasMore && !candidates.length && !uncertain && (
            <p>
              {t(
                "No unused published receipt template is available. Review templates or existing copies in school documents.",
              )}
            </p>
          )}
          {(candidates.length > 0 || uncertain) && !acknowledged && (
            <form
              onSubmit={(event) => void issue(event)}
              aria-label={t("Request receipt PDF for {receipt}", { receipt: props.receiptNumber })}
            >
              <fieldset disabled={busy || !canRequest || uncertain}>
                <legend>{t("School-approved PDF copy")}</legend>
                <label htmlFor={`${id}-template`}>{t("Receipt template")}</label>
                <select
                  id={`${id}-template`}
                  value={templateId}
                  onChange={(event) => setTemplateId(event.target.value)}
                  required
                >
                  <option value="">{t("Choose a published template")}</option>
                  {candidates.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.displayName} · {item.locale === "hi-IN" ? "हिन्दी" : "English"}
                    </option>
                  ))}
                </select>
                <label htmlFor={`${id}-reason`}>{t("Reason for the PDF copy")}</label>
                <input
                  id={`${id}-reason`}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  minLength={3}
                  maxLength={240}
                  required
                />
              </fieldset>
              {selected?.approvalRequired && (
                <p>
                  {t("A different authorized staff member must approve this copy before download.")}
                </p>
              )}
              <button type="submit" disabled={busy || !canRequest} formNoValidate={uncertain}>
                {t(uncertain ? "Retry the unchanged PDF request" : "Request school receipt PDF")}
              </button>
              {uncertain && (
                <p>
                  {t(
                    "Leaving or reloading loses this in-memory retry key. Review the exact receipt history before requesting another copy.",
                  )}
                </p>
              )}
            </form>
          )}
          <Link href="/workspace/documents">{t("Open school documents and approvals")}</Link>
        </>
      )}
      {props.audience === "family" && (
        <Link href="/family/documents">{t("Open my child’s school documents")}</Link>
      )}
    </div>
  );
}
