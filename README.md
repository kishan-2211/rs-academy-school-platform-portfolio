# R.S. Academy School Platform

A school website and management application built with Next.js, Spring Boot and PostgreSQL. It brings public information, admissions, classroom workflows and school operations into one application, with separate access for staff, teachers, parents and students.

## Main workflows

- **Public website:** responsive school pages, admissions enquiries, notices, fee information, English/Hindi content and light/dark themes.
- **Admissions and records:** applications, enrolment, guardian relationships, class placement and controlled student imports.
- **Academics:** academic sessions, subjects, teaching allocations, timetable publication, attendance, leave, homework, submissions, marks and report cards.
- **Finance:** fee plans, partial collections, numbered receipts, payment verification, reviewed refunds, settlement reconciliation and double-entry accounting.
- **Payroll:** salary configuration, monthly runs, payslips, advances, recovery schedules and accounting entries.
- **People and communication:** staff records, account invitations, notices, inboxes, parent requests and parent–teacher appointments.
- **Role portals:** school administration, leadership, teaching, office/finance, family and student views share the same API and access rules.

The code also includes a transport foundation. Live vehicle tracking and native mobile applications are outside the current browser application.

## Stack

| Layer | Technology |
| --- | --- |
| Web | Next.js 16.4.1, React 19, TypeScript, App Router |
| API | Java 21, Spring Boot 4.1, Spring Security, JDBC/JPA |
| Data | PostgreSQL, Flyway migrations, row-level security |
| Documents | PDFBox, private S3-compatible storage, malware-scanning boundary |
| Interfaces | OpenAPI, JSON APIs, HttpOnly browser sessions |
| Delivery | Docker, GitHub Actions, dependency and container checks |

## Repository layout

```text
apps/web/       Public website and browser portals
apps/api/       Domain services, API endpoints and database migrations
packages/      API contract and shared design configuration
infra/         Containers, service configuration and storage policies
scripts/       Verification, packaging and recovery utilities
docs/          Architecture and operational documentation
```

## Design decisions

The backend is a modular monolith: each domain owns its persistence and exposes services for other domains to call. This keeps deployment small while maintaining boundaries between admissions, academics, finance and other school functions.

Access depends on an active school membership, capability and the specific record being requested. A parent can access linked children; a teacher is limited by teaching assignments. Selecting a role on the login screen does not grant permissions.

Financial entries, published results and audit history are retained. Corrections use reversals or superseding records. Payment and other important commands carry retry/concurrency controls so an uncertain network response does not justify repeating an operation with a new identity.

See the [architecture overview](docs/ARCHITECTURE.md) and [security policy](SECURITY.md).

## Run locally

Requirements: Node.js 22 or 24, pnpm 11.19, Java 21+, Maven 3.9+ and PostgreSQL. Docker is useful for isolated database and private-file integration checks.

```sh
pnpm install --frozen-lockfile
pnpm --filter @educore/web dev
```

In a second terminal, configure the local database using [.env.example](.env.example), then start the API:

```sh
mvn -f apps/api/pom.xml spring-boot:run
```

Spring reads process environment variables; copying an environment file does not automatically export its values to Maven. The web defaults to `http://localhost:3000`, and the API to `http://localhost:8080`.

Local account setup is documented in [development setup](docs/DEVELOPMENT.md). No working account credentials, database export or real school records are included in this repository.

## Verification

```sh
pnpm check:openapi
pnpm --filter @educore/web check
pnpm --filter @educore/web lint
pnpm --filter @educore/web test
pnpm --filter @educore/web build
mvn -B -ntp -f apps/api/pom.xml verify
```

The integration suites use disposable PostgreSQL schemas/databases. Configure `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD` for an isolated test service. Check the reports for skipped suites; compilation alone does not establish database or private-storage acceptance.

## Status

This is a development-stage portfolio snapshot. The source implements the workflows above, but it is not a claim of a live school deployment. Production operation still requires configured providers, private storage/scanning, a verified restore procedure, school configuration and end-to-end acceptance on the deployed environment. Example domains and configurations do not establish that a service is live.

## Credits and use

Project copyright: Kishan Singh. Original code and school identity assets remain subject to [NOTICE.md](NOTICE.md). Public visibility does not grant an open-source licence.

Third-party assets and libraries retain their licences; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The bundled school identity is not offered as a reusable brand kit.
