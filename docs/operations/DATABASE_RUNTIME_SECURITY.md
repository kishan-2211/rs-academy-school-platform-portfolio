# Database identities and migrations

Use separate migration-owner and API runtime identities. The runtime login must not be a superuser, own application tables, bypass row-level security, create databases/roles or execute schema migrations. The production startup check enforces the relevant database boundary.

The migration process uses `MIGRATION_DATABASE_URL`, `MIGRATION_DATABASE_USER` and `MIGRATION_DATABASE_PASSWORD`, plus `DATABASE_USER` identifying the existing restricted runtime login. Supply credentials through the process environment or an approved secret facility.

The packaged migration entry point is `com.educore.api.config.DatabaseMigrationTool`. It runs forward-only Flyway migrations and then applies `db/runtime-grants.sql` transactionally. It does not start the HTTP API or provision application users.

```sh
java -Dloader.main=com.educore.api.config.DatabaseMigrationTool -cp apps/api/target/educore-api-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Do not put passwords in command arguments. Remove migration credentials from the environment before starting the API with `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD` for the runtime login.

Back up an existing installation before a migration and validate migration history, grants and tenant isolation afterward. Do not edit a migration already applied to a shared environment. Financial, academic publication and audit history must be preserved through upgrades and recovery.

Integration tests require a disposable test database service. Never point `TEST_DATABASE_URL` at an operational school database.
