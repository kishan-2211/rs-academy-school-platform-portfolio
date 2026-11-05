# Operator handoff

Deploy immutable, reviewed web/API artifacts and record the configuration used with them. The supplied Compose file is intended for local development/rehearsal and requires review for the selected host.

1. Confirm the release, environment, operating owner and known limitations.
2. Review the [domain contract](PRODUCTION_DOMAIN.md), including the example `rsacademyschool.in` origin, HTTPS and same-origin API routing.
3. Prepare private database, storage and scanner services with separate application and operator credentials.
4. Verify encrypted off-host recovery before migrating an existing installation.
5. Run the separate migration tool with migration credentials, refresh runtime grants, and remove migration credentials from the API environment.
6. Establish the first administrator through the packaged operator path, then use controlled invitations and privileged MFA for staff accounts.
7. Check application readiness, login/CSRF, school/relationship isolation and private document delivery.
8. Reconcile data and complete a controlled pilot with teachers, office/finance staff, parents and students.
9. Record approval, rollback criteria, monitoring and support contacts before cutover.

Do not use development bootstrap in production, seed a browser session or assign roles through ad hoc SQL. Different memberships belonging to the same person do not provide independent approval.

Application rollback must account for forward-only database migrations and writes made after deployment. Never restore an old database over new financial or academic records without an approved reconciliation procedure.

See [database security](DATABASE_RUNTIME_SECURITY.md), [private files](PRIVATE_FILES_RUNBOOK.md), [gateway settings](GATEWAY_CONFIGURATION.md) and [readiness](PRODUCTION_READINESS.md).
