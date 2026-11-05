# Production readiness

This portfolio snapshot is source code, not an approved school deployment. Verify each item against the chosen environment and record the accountable owner.

- Confirm school identity, academic rules, fee/payroll policies, roles, signatories and public content.
- Reconcile imported student, staff, balance and result data with approved source records.
- Configure HTTPS, private networking, host-only secure sessions and the [domain contract](PRODUCTION_DOMAIN.md) for `rsacademyschool.in` or the approved replacement domain.
- Use separate migration/runtime database identities, protected encryption keys and least-privilege storage credentials.
- Rehearse privileged MFA enrollment, account recovery, session revocation and independent approval workflows.
- Prove own-child, assigned-teacher, cross-school, export and private-file authorization boundaries.
- Verify current dependency/container findings, application tests and security review for the actual release candidate.
- Configure private object storage and scanning; verify receipt, report-card, certificate and payslip downloads.
- Rehearse encrypted off-host database/object/configuration/key recovery against approved recovery targets.
- Accept payment-provider callbacks, unknown outcomes, refunds and settlement/reconciliation using controlled provider test environments before live operation.
- Exercise attendance, results, admissions and payment load at representative concurrency.
- Complete English/Hindi, mobile, keyboard and accessible-document acceptance with school users.
- Agree on monitoring, support, incident ownership, rollout and rollback procedures.

A build or successful liveness response does not replace these checks. Keep unresolved items visible and obtain the responsible school/operator's approval before importing real records or enabling external services.
