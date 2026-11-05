# Tenant isolation implementation

Every organisation-owned request must pass three independent checks:

1. The session identifies an active user.
2. The requested school/campus context resolves to an active membership for that user.
3. The use case authorizes the capability and object relationship before reading or changing a record.

PostgreSQL row-level security is a second boundary for organisation-owned tables. Membership resolution sets transaction-local `app.current_user_id` and `app.current_organisation_id`; the existing tenant-aware data source also applies verified request/job scope on each checked-out connection and clears it before pool return. This protects authorized reads outside explicit transactions as well. Policies default to no rows when the setting is absent.

## Database roles

Production uses separate credentials:

- Migration owner: creates schemas, tables, functions, policies, and grants; never used by the running API.
- Application runtime: receives only required table/sequence/function privileges, does not own protected tables, is not a superuser, and does not have `BYPASSRLS`.
- Backup/restore and reporting roles: separately controlled, monitored, and tested against their explicit purpose.

Local development may use one database owner for convenience, so application-level authorization tests remain mandatory. Staging and continuous integration must exercise the real non-owner runtime role.

Production enforces the actual limited login and active RLS before startup; migration
credentials belong to a separate short-lived process, not the API pool. See the
[operator workflow and exact test boundary](../operations/DATABASE_RUNTIME_SECURITY.md).

## Rules for new tables

- Add `organisation_id` and applicable `school_id`/`campus_id` columns with foreign keys.
- Include scope in unique constraints to prevent false global uniqueness.
- Enable RLS and add both `USING` and `WITH CHECK` policies in the same migration.
- Never expose an unscoped repository method for tenant data.
- Test list, detail, search, file, export, batch, webhook, and indirect-reference paths across two organisations.
- Shared reference data must be explicitly documented; nullable organisation scope is not an accidental shortcut.

RLS reduces accidental cross-tenant queries. It does not replace parameterized SQL, input validation, least-privilege credentials, object authorization, or protection against SQL injection.
