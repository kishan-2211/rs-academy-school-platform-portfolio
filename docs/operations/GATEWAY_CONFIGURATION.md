# Payment-provider configuration

Payment processing is controlled by the configured provider registry, application switches and current financial permissions. A callback or browser return is evidence to verify, not proof that a fee has been paid or refunded.

Follow the [domain contract](PRODUCTION_DOMAIN.md) when using the example `rsacademyschool.in` origin. Keep application return origins, hosted-checkout links and provider callback endpoints consistent with the accepted deployment.

- Store provider credentials outside source, browser storage, logs and public environment values.
- Keep creation, worker and live controls disabled until the selected environment has passed provider acceptance.
- Verify signed raw callback bytes and independently read provider state through the configured adapter.
- Preserve stable operation identities across retries and uncertain outcomes.
- Reconcile exact amount, currency, source payment, timing and scope before posting money.
- Retain late/duplicate/contradictory observations for review rather than inventing a zero-money result.
- Use independent review and the existing financial ledger for refunds and settlements.
- Verify provider failures, timeouts, duplicate callbacks, closed-day handling and reconciliation in a test merchant environment.

No merchant account, active credential, live checkout or completed provider acceptance is included in this repository. The original fee/accounting history must remain intact when providers are enabled or changed.
