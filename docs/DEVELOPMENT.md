# Development setup

## Prerequisites

- Node.js 22 or 24, pnpm 11.19 and Maven 3.9+
- Java 21 or a supported newer JDK compiling with release 21
- PostgreSQL; use a separate disposable service for integration tests
- Optional Docker for the supplied container checks

Install JavaScript dependencies with `pnpm install --frozen-lockfile`. Keep the lockfile and pinned security overrides together.

## Environment

Use `.env.example` as a list of configuration keys. Supply database credentials and encryption keys through the process environment or a local secret facility. Never commit an actual `.env` file or reuse demonstration passwords in a deployed environment.

For local development, `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD` identify the development database. The web uses `NEXT_PUBLIC_API_URL=http://localhost:8080`; the public application URL is `http://localhost:3000`. These public values are embedded when the web is built.

The API's local startup applies Flyway migrations. Production uses the separate migration entry point and restricted runtime grants described in [database operations](operations/DATABASE_RUNTIME_SECURITY.md).

## First local account

For a new disposable local database, development bootstrap can create an administrator when `APP_BOOTSTRAP_ENABLED=true`. Supply `APP_BOOTSTRAP_ADMIN_EMAIL` and a strong, unique `APP_BOOTSTRAP_ADMIN_PASSWORD` through the local process environment. Bootstrap is forbidden in the production profile. Disable it after the account exists.

Other roles use the implemented invitation and relationship/consent workflows. Login-screen role selection guides navigation; it does not create accounts or grant access. This repository intentionally has no shared sign-in password or authenticated demonstration session.

## Services

Start the web with `pnpm --filter @educore/web dev` and the API with `mvn -f apps/api/pom.xml spring-boot:run`. Inspect the supplied Compose configuration before starting it; it has separate requirements for database, storage and scanner services.

Keep notification delivery and live payment controls disabled until the corresponding provider has been configured and accepted. Missing storage/scanner readiness should be fixed through service configuration, not by bypassing file checks.

## Checks

Run the commands listed in the README. The web has TypeScript, lint, Node test and production-build checks. The API has unit and real PostgreSQL integration suites. `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD` must point to a disposable test service rather than a school database.

Docker-based private-file, image and recovery checks are separate from the ordinary unit suite. A skipped or unavailable integration is an outstanding verification item.

Generated local reports belong under `.build-tmp/`, which is excluded from Git and Docker contexts. Keep databases, uploads, backups, keys, screenshots containing private data and dependency caches outside source control.
