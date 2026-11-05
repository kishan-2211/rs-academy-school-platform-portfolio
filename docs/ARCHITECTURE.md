# Architecture

The application has one Next.js web frontend, one Spring Boot API and a PostgreSQL database. The browser uses the API rather than connecting to the database or private object store.

## Domain boundaries

The API packages separate identity, school configuration, admissions, students, academics, attendance, learning, assessment, finance, accounting, payroll, people and communication. Services exchange scoped requests or domain events. A domain must not read another domain's repository directly.

The OpenAPI contract is maintained in `packages/contracts/openapi.yaml`. Migrations are additive, versioned SQL files under `apps/api/src/main/resources/db/migration`.

## Request and authorization flow

1. The API validates the HttpOnly session and resolves active memberships.
2. The selected organisation determines the database tenant context.
3. The endpoint checks capabilities and the domain checks the requested school and record.
4. Relationship and assignment checks narrow parent, student and teacher access.
5. PostgreSQL row-level security provides an additional isolation boundary.

Production uses separate migration and runtime database identities. The runtime identity must not own application tables, bypass row-level security or run migrations.

## Changes and history

Mutable forms use version checks. Important commands retain an idempotency identity for safe retries. Published academic versions, financial events and audit records are retained; corrections append a reversal or a replacement version.

Cross-domain notifications use an outbox. Background work records claim and retry state, and rechecks current authority and source state before delivery. External sending remains subject to explicit provider and deployment configuration.

## Files and documents

PostgreSQL holds file metadata, ownership and issuance records. Private storage holds bytes. Upload processing applies type/size limits and scanning. Downloads recheck current authorization and content integrity; an object-store URL is not a substitute for application authorization.

For implementation details, see [tenant isolation](architecture/TENANT_ISOLATION.md), [private file boundaries](architecture/PRIVATE_FILE_SECURITY_BOUNDARY.md) and the domain packages in `apps/api/src/main/java/com/educore/api`.

## Deployment

The web and API have separate container builds. A production edge should provide HTTPS, route `/api/v1` to the private API, preserve session/CSRF headers and avoid caching authenticated responses. PostgreSQL, object storage and the scanner belong on private networks. The Compose configuration is a local/rehearsal configuration and needs environment-specific review before deployment.
