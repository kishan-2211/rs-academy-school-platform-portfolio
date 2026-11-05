"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { useLanguage, useT } from "@/components/i18n/language-provider";
import { FamilyFeeAccountAccessError, familyFeeAccountError, loadFamilyFeeAccounts, signedFeePaise, type FamilyFeeAccount, type FamilyFeeAccounts } from "@/lib/family-fee-accounts";
import styles from "./fee-account-selector.module.css";

type Props = { learnerId: string; selectedAccount: FamilyFeeAccount | null; disabled: boolean; onSelect: (account: FamilyFeeAccount | null) => void; onCurrentAccount: (account: FamilyFeeAccount | null) => void; onAccountsRead: (accounts: FamilyFeeAccount[]) => void; onAccessLost: () => void };
/** This changes only the financial account view, never the child's academic context. */
export function FamilyFeeAccountSelector(props: Props) {
  return <ScopedFeeAccountSelector key={props.learnerId} {...props} />;
}
function ScopedFeeAccountSelector({ learnerId, selectedAccount, disabled, onSelect, onCurrentAccount, onAccountsRead, onAccessLost }: Props) {
  const t = useT("parent"), { locale } = useLanguage(), id = useId();
  const [data, setData] = useState<FamilyFeeAccounts | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const request = useRef<AbortController | null>(null), scope = useRef(learnerId), callbacks = useRef({ onAccessLost, onCurrentAccount, onAccountsRead });
  useEffect(() => { callbacks.current = { onAccessLost, onCurrentAccount, onAccountsRead }; }, [onAccessLost, onCurrentAccount, onAccountsRead]);
  const load = useCallback(async (page = 0) => {
    if (request.current) return;
    const controller = new AbortController(); request.current = controller; setBusy(true); setError("");
    try {
      const next = await loadFamilyFeeAccounts(learnerId, page, controller.signal);
      if (controller.signal.aborted || scope.current !== learnerId) return;
      setData(previous => page && previous ? { ...next, accounts: [...previous.accounts, ...next.accounts.filter(account => !previous.accounts.some(old => old.id === account.id))] } : next);
      callbacks.current.onAccountsRead(next.accounts);
      if (page === 0 || next.accounts.some(account => account.id === next.currentAccountId)) callbacks.current.onCurrentAccount(next.accounts.find(account => account.id === next.currentAccountId) ?? null);
    } catch (reason) {
      if (controller.signal.aborted || scope.current !== learnerId) return;
      setError(familyFeeAccountError(reason));
      if (reason instanceof FamilyFeeAccountAccessError) { setData(null); callbacks.current.onCurrentAccount(null); callbacks.current.onAccessLost(); }
    } finally { if (request.current === controller) request.current = null; if (!controller.signal.aborted && scope.current === learnerId) setBusy(false); }
  }, [learnerId]);
  useEffect(() => {
    scope.current = learnerId;
    void Promise.resolve().then(() => { if (scope.current === learnerId) void load(); });
    return () => { scope.current = ""; request.current?.abort(); request.current = null; };
  }, [learnerId, load]);
  function choose(value: string) {
    if (disabled || busy || scope.current !== learnerId) return;
    if (!value) { onSelect(null); return; }
    const account = data?.accounts.find(item => item.id === value);
    if (account) onSelect(account);
  }
  const options = data?.accounts ?? [];
  const selected = selectedAccount && !options.some(account => account.id === selectedAccount.id) ? [selectedAccount, ...options] : options;
  const money = (account: FamilyFeeAccount) => new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", { style: "currency", currency: account.currencyCode }).format(signedFeePaise(account.balance) / 100);
  return <section className={styles.root} aria-labelledby={`${id}-title`} aria-busy={busy}>
    <h3 id={`${id}-title`}>{t("Fee account and session")}</h3>
    <p id={`${id}-help`}>{t("View current or earlier fee accounts. This does not change classes, enrollment or academic access. Only posted ledger entries affect the balance; refund holds are not restored dues.")}</p>
    <label htmlFor={`${id}-account`}>{t("Choose a fee account")}</label>
    <select id={`${id}-account`} value={selectedAccount?.id ?? ""} disabled={disabled || busy || !data} onChange={event => choose(event.target.value)} aria-describedby={`${id}-help ${id}-status`}>
      <option value="">{t("Current academic session (default)")}</option>
      {selected.map(account => <option key={account.id} value={account.id}>{account.sessionName} · {t(account.status === "CLOSED" ? "Closed — view only" : "Active fee account")}{account.currentSession ? ` · ${t("Current session")}` : ""} · {money(account)}</option>)}
    </select>
    <p id={`${id}-status`} role="status">{t(busy ? "Opening fee account history…" : !data ? "Fee accounts have not been loaded." : data.accounts.length ? "Balances shown are actual signed ledger balances, including any credit." : "No fee account has been assigned to this child yet.")}</p>
    {selectedAccount?.status === "CLOSED" && <p className={styles.warning}>{t("This fee account is closed and view-only. Existing statements, receipts and payment history remain available; no new payment can be started.")}</p>}
    {disabled && <p className={styles.warning}>{t("A payment request is unresolved. Keep this child and fee account selected until its exact request and refreshed records are confirmed.")}</p>}
    {error && <p className={styles.error} role="alert">{t(error)}</p>}
    <div className={styles.actions}><button type="button" aria-disabled={busy || disabled} onClick={() => { if (!disabled && !busy) void load(); }}>{t("Refresh fee accounts")}</button>
      {data && (data.hasMore || data.page > 0) && <button type="button" aria-disabled={busy || disabled || !data.hasMore || data.page >= 10000} onClick={() => { if (!disabled && !busy && data.hasMore && data.page < 10000) void load(data.page + 1); }}>{t(data.page >= 10000 ? "Fee account page limit reached" : data.hasMore ? "Load earlier fee accounts" : "All fee accounts loaded")}</button>}
    </div>
  </section>;
}
