package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.config.CoreConfiguration;
import com.educore.api.config.ProductionDatabaseSecurity;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL only; every fixture is cloned into a new, identity-empty synthetic database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FirstAdministratorPostgresIT {
    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SCHOOL = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID CAMPUS = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID ADMIN_ROLE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String ACTION = "identity.first_administrator.created";
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String migrationLogin = "synthetic_first_owner_" + suffix;
    private final String runtimeLogin = "synthetic_first_runtime_" + suffix;
    private final String ownerPassword = generatedSecret();
    private final String runtimePassword = generatedSecret();
    private final String template = "educore_first_template_" + suffix;
    private final List<String> generatedDatabases = new ArrayList<>();
    private PostgreSQLContainer<?> container;
    private String controlUrl;
    private String controlLogin;
    private String controlPassword;
    private DriverManagerDataSource controlDataSource;
    private JdbcTemplate control;
    private boolean ownerCreated;
    private boolean runtimeCreated;

    @BeforeAll
    void createIsolatedMigratedTemplate() throws Exception {
        controlUrl = System.getenv("TEST_DATABASE_URL");
        if (controlUrl == null || controlUrl.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:18.4-alpine");
            container.start();
            // Testcontainers adds loggerLevel=OFF; it is not an operator-approved TLS URL property.
            controlUrl = container.getJdbcUrl().replaceFirst("\\?.*$", "");
            controlLogin = container.getUsername();
            controlPassword = container.getPassword();
        } else {
            if (!controlUrl.matches("jdbc:postgresql://[^/]+/educore_integration(?:\\?.*)?")) {
                throw new IllegalStateException("Initial-administrator verification requires the isolated educore_integration database");
            }
            controlLogin = required("TEST_DATABASE_USER");
            controlPassword = required("TEST_DATABASE_PASSWORD");
        }
        // Windows filesystem cleanup of many migrated tables can exceed a normal application read timeout.
        // This longer, still bounded limit belongs only to the disposable-fixture control login/DDL.
        controlDataSource = new DriverManagerDataSource(controlUrl, controlLogin, controlPassword);
        Properties controlProperties = new Properties();
        controlProperties.setProperty("connectTimeout", "10");
        controlProperties.setProperty("socketTimeout", "120");
        controlProperties.setProperty("options", "-c statement_timeout=90000");
        controlDataSource.setConnectionProperties(controlProperties);
        control = new JdbcTemplate(controlDataSource);
        control.setQueryTimeout(90);
        // The harness administrator creates only generated roles/databases. The actual tool never uses this login.
        assertThat(control.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
        createRole(migrationLogin, ownerPassword, "BYPASSRLS");
        ownerCreated = true;
        createRole(runtimeLogin, runtimePassword, "NOBYPASSRLS");
        runtimeCreated = true;
        createDatabase(template, null);
        String templateUrl = databaseUrl(template);
        Flyway.configure().dataSource(templateUrl, migrationLogin, ownerPassword)
                .locations("classpath:db/migration").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        try (var connection = connect(templateUrl, migrationLogin, ownerPassword)) {
            connection.setAutoCommit(false);
            JdbcTemplate owner = transactionJdbc(connection);
            owner.queryForObject("SELECT set_config('educore.runtime_role',?,true)", String.class, runtimeLogin);
            owner.execute(new ClassPathResource("db/runtime-grants.sql").getContentAsString(StandardCharsets.UTF_8));
            connection.commit();
        }
        JdbcTemplate owner = jdbc(templateUrl, migrationLogin, ownerPassword);
        assertIdentityEmpty(owner);
        assertThat(owner.queryForObject("SELECT bool_and(success) FROM flyway_schema_history", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='032'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='065'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("""
                SELECT rolcanlogin AND rolbypassrls AND NOT rolsuper AND NOT rolcreatedb
                  AND NOT rolcreaterole AND NOT rolreplication FROM pg_roles WHERE rolname=current_user
                """, Boolean.class)).isTrue();
    }

    @Test
    void realFirstAdministratorCreatesScopedIdentityWithHonestImmutableAudit() throws Exception {
        Fixture fixture = fresh();
        char[] password = generatedSecret().toCharArray();
        try {
            var identity = identity();
            FirstAdministratorTool.provision(fixture.settings(), identity, password);
            Map<String, Object> user = fixture.owner.queryForMap("SELECT * FROM user_accounts");
            UUID userId = (UUID) user.get("id");
            String hash = (String) user.get("password_hash");
            assertThat(hash).startsWith("$argon2id$");
            assertThat(CoreConfiguration.newPasswordEncoder().matches(CharBuffer.wrap(password), hash)).isTrue();
            assertThat(user).containsEntry("email_normalized", identity.email())
                    .containsEntry("login_identifier_normalized", identity.email())
                    .containsEntry("preferred_language", "hi").containsEntry("status", "ACTIVE")
                    .containsEntry("failed_login_count", 0).containsEntry("last_login_at", null);
            assertThat(fixture.owner.queryForObject("""
                    SELECT count(*) FROM memberships WHERE user_id=? AND organisation_id=? AND school_id=?
                      AND campus_id=? AND status='ACTIVE' AND valid_until IS NULL
                    """, Integer.class, userId, ORGANISATION, SCHOOL, CAMPUS)).isEqualTo(1);
            assertThat(fixture.owner.queryForList("SELECT role_id FROM membership_roles", UUID.class)).containsExactly(ADMIN_ROLE);
            assertThat(fixture.owner.queryForObject("""
                    SELECT count(*) FROM role_capabilities WHERE role_id=?
                      AND (capability_code LIKE 'health.%' OR capability_code LIKE 'safeguarding.%')
                    """, Integer.class, ADMIN_ROLE)).isZero();
            assertNoSessionsOrMfa(fixture.owner);
            Map<String, Object> audit = fixture.owner.queryForMap("SELECT * FROM audit_events WHERE action=?", ACTION);
            assertThat(audit).containsEntry("organisation_id", ORGANISATION).containsEntry("school_id", SCHOOL)
                    .containsEntry("actor_user_id", null).containsEntry("actor_membership_id", null)
                    .containsEntry("subject_type", "USER_ACCOUNT").containsEntry("subject_id", userId.toString())
                    .containsEntry("outcome", "SUCCEEDED").containsEntry("previous_hash", null);
            assertThat((String) audit.get("request_id")).startsWith("operator-");
            assertThat((String) audit.get("event_hash")).matches("[0-9a-f]{64}");
            var metadata = JsonMapper.builder().build().readTree(audit.get("safe_metadata").toString());
            assertThat(metadata.get("operatorType").stringValue()).isEqualTo("MIGRATION_DATABASE_LOGIN");
            assertThat(metadata.get("databaseLogin").stringValue()).isEqualTo(migrationLogin);
            assertThat(metadata.get("approvalReference").stringValue()).isEqualTo(fixture.reference);
            assertThat(metadata.get("membershipId").stringValue()).isEqualTo(
                    fixture.owner.queryForObject("SELECT id::text FROM memberships WHERE user_id=?", String.class, userId));
            assertThat(metadata.size()).isEqualTo(4);
            assertOperatorAuditHash(audit, fixture);
            assertImmutableAudit(fixture.owner, (UUID) audit.get("id"));

            // The setup owner remains forbidden for production HTTP; the distinct runtime remains safe and tenant scoped.
            assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(fixture.owner)).isInstanceOf(IllegalStateException.class);
            ProductionDatabaseSecurity.verify(fixture.runtime);
            assertThat(fixture.runtime.queryForObject("SELECT count(*) FROM memberships", Integer.class)).isZero();
            assertThat(fixture.runtime.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
            try (var connection = connect(fixture.url, runtimeLogin, runtimePassword)) {
                connection.setAutoCommit(false);
                JdbcTemplate runtime = transactionJdbc(connection);
                runtime.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, UUID.randomUUID().toString());
                assertThat(runtime.queryForObject("SELECT count(*) FROM memberships", Integer.class)).isZero();
                assertThat(runtime.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
                runtime.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, ORGANISATION.toString());
                assertThat(runtime.queryForObject("SELECT count(*) FROM memberships", Integer.class)).isEqualTo(1);
                assertThat(runtime.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isEqualTo(1);
                connection.rollback();
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void realFirstAdministratorRefusesExistingIdentitiesAndNeverResetsRetry() throws Exception {
        Fixture disabled = fresh();
        UUID disabledUser = insertOrdinary(disabled.owner, "DISABLED");
        String before = disabled.owner.queryForObject("SELECT to_jsonb(u)::text FROM user_accounts u WHERE id=?", String.class, disabledUser);
        assertRefused(disabled.settings());
        assertThat(disabled.owner.queryForObject("SELECT to_jsonb(u)::text FROM user_accounts u WHERE id=?", String.class, disabledUser)).isEqualTo(before);
        assertThat(disabled.owner.queryForObject("SELECT count(*) FROM memberships", Integer.class)).isZero();
        assertThat(disabled.owner.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();

        Fixture foreign = fresh();
        UUID foreignOrganisation = UUID.randomUUID();
        foreign.owner.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at)
                VALUES(?,?,'Synthetic foreign organisation','Synthetic foreign organisation',now(),now())
                """, foreignOrganisation, "synthetic-" + UUID.randomUUID());
        UUID foreignUser = insertOrdinary(foreign.owner, "ACTIVE");
        foreign.owner.update("""
                INSERT INTO memberships(id,user_id,organisation_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,'ACTIVE',now(),now(),now())
                """, UUID.randomUUID(), foreignUser, foreignOrganisation);
        before = foreign.owner.queryForObject("SELECT to_jsonb(u)::text FROM user_accounts u WHERE id=?", String.class, foreignUser);
        assertRefused(foreign.settings());
        assertThat(foreign.owner.queryForObject("SELECT to_jsonb(u)::text FROM user_accounts u WHERE id=?", String.class, foreignUser)).isEqualTo(before);
        assertThat(foreign.owner.queryForObject("SELECT count(*) FROM memberships WHERE organisation_id=?", Integer.class, ORGANISATION)).isZero();
        assertThat(foreign.owner.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();

        Fixture retry = fresh();
        provision(retry.settings());
        String identityBefore = retry.owner.queryForObject("SELECT to_jsonb(u)::text FROM user_accounts u", String.class);
        String auditBefore = retry.owner.queryForObject("SELECT to_jsonb(a)::text FROM audit_events a", String.class);
        assertRefused(retry.settings());
        assertThat(retry.owner.queryForObject("SELECT count(*) FROM user_accounts", Integer.class)).isEqualTo(1);
        assertThat(retry.owner.queryForObject("SELECT to_jsonb(u)::text FROM user_accounts u", String.class)).isEqualTo(identityBefore);
        assertThat(retry.owner.queryForObject("SELECT to_jsonb(a)::text FROM audit_events a", String.class)).isEqualTo(auditBefore);
        assertNoSessionsOrMfa(retry.owner);

        Fixture history = fresh();
        history.owner.update("""
                INSERT INTO audit_events(id,occurred_at,organisation_id,school_id,request_id,action,subject_type,subject_id,
                  outcome,reason,safe_metadata,event_hash)
                VALUES(?,now()-INTERVAL '1 day',?,?,'synthetic-historical-setup',?,'USER_ACCOUNT',?,
                  'SUCCEEDED','Synthetic prior first-use evidence','{}'::jsonb,?)
                """, UUID.randomUUID(), ORGANISATION, SCHOOL, ACTION, UUID.randomUUID().toString(), "2".repeat(64));
        String historyBefore = history.owner.queryForObject("SELECT to_jsonb(a)::text FROM audit_events a", String.class);
        assertRefused(history.settings());
        for (String table : List.of("user_accounts", "memberships", "membership_roles")) {
            assertThat(history.owner.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(history.owner.queryForObject("SELECT to_jsonb(a)::text FROM audit_events a", String.class)).isEqualTo(historyBefore);
        assertNoSessionsOrMfa(history.owner);
    }

    @Test
    void realFirstAdministratorRejectsWrongScopeRoleOrOperator() throws Exception {
        for (String scope : List.of("FIRST_ADMIN_ORGANISATION_ID", "FIRST_ADMIN_SCHOOL_ID", "FIRST_ADMIN_CAMPUS_ID")) {
            Fixture fixture = fresh();
            assertRefused(fixture.settings(Map.of(scope, UUID.randomUUID().toString())));
            assertIdentityEmpty(fixture.owner);
        }
        Fixture mismatched = fresh();
        UUID foreignOrganisation = UUID.randomUUID();
        UUID foreignSchool = UUID.randomUUID();
        UUID foreignCampus = UUID.randomUUID();
        mismatched.owner.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at)
                VALUES(?,?,'Synthetic distinct organisation','Synthetic distinct organisation',now(),now())
                """, foreignOrganisation, "synthetic-" + UUID.randomUUID());
        mismatched.owner.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,created_at,updated_at)
                VALUES(?,?,?,'Synthetic distinct school','Synthetic distinct school',now(),now())
                """, foreignSchool, foreignOrganisation, "synthetic-" + UUID.randomUUID());
        mismatched.owner.update("""
                INSERT INTO campuses(id,organisation_id,school_id,code,display_name,created_at,updated_at)
                VALUES(?,?,?,'SYNTHETIC','Synthetic distinct campus',now(),now())
                """, foreignCampus, foreignOrganisation, foreignSchool);
        // Every ID exists and is active, but the campus is not in the confirmed school/organisation.
        assertRefused(mismatched.settings(Map.of("FIRST_ADMIN_CAMPUS_ID", foreignCampus.toString())));
        assertIdentityEmpty(mismatched.owner);
        Fixture inactive = fresh();
        inactive.owner.update("UPDATE campuses SET status='INACTIVE' WHERE id=?", CAMPUS);
        assertRefused(inactive.settings());
        assertIdentityEmpty(inactive.owner);

        Fixture role = fresh();
        role.owner.update("DELETE FROM role_capabilities WHERE role_id=? AND capability_code='identity.manage'", ADMIN_ROLE);
        assertRefused(role.settings());
        assertIdentityEmpty(role.owner);
        Fixture health = fresh();
        health.owner.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES(?,'health.read')", ADMIN_ROLE);
        assertRefused(health.settings());
        assertIdentityEmpty(health.owner);
        Fixture renamed = fresh();
        renamed.owner.update("UPDATE roles SET code='synthetic_wrong_role' WHERE id=?", ADMIN_ROLE);
        assertRefused(renamed.settings());
        assertIdentityEmpty(renamed.owner);

        Fixture operator = fresh();
        assertRefused(operator.settings(Map.of("MIGRATION_DATABASE_USER", runtimeLogin,
                "MIGRATION_DATABASE_PASSWORD", runtimePassword, "DATABASE_USER", migrationLogin)));
        assertRefused(operator.settings(Map.of("MIGRATION_DATABASE_USER", controlLogin,
                "MIGRATION_DATABASE_PASSWORD", controlPassword)));
        try (var connection = connect(operator.url, migrationLogin, ownerPassword)) {
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> FirstAdministratorTool.requireOperator(connection, "synthetic_wrong_database", migrationLogin))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> FirstAdministratorTool.requireOperator(connection, operator.database, runtimeLogin))
                    .isInstanceOf(IllegalStateException.class);
            connection.rollback();
        }
        for (String powerfulRole : List.of("pg_execute_server_program", "pg_read_all_data", "pg_write_all_data", "pg_maintain")) {
            control.execute("GRANT " + identifier(powerfulRole) + " TO " + identifier(migrationLogin));
            try {
                assertRefused(operator.settings());
                assertIdentityEmpty(operator.owner);
            } finally {
                control.execute("REVOKE " + identifier(powerfulRole) + " FROM " + identifier(migrationLogin));
            }
        }
        control.execute("ALTER ROLE " + identifier(runtimeLogin) + " BYPASSRLS");
        try {
            assertRefused(operator.settings());
        } finally {
            control.execute("ALTER ROLE " + identifier(runtimeLogin) + " NOBYPASSRLS");
        }
        control.execute("GRANT pg_read_all_data TO " + identifier(runtimeLogin));
        try {
            assertRefused(operator.settings());
        } finally {
            control.execute("REVOKE pg_read_all_data FROM " + identifier(runtimeLogin));
        }
        assertIdentityEmpty(operator.owner);
        ProductionDatabaseSecurity.verify(operator.runtime);
    }

    @Test
    void realFirstAdministratorRequiresExactFullyMigratedRelease() throws Exception {
        Fixture missing = fresh();
        missing.owner.update("DELETE FROM flyway_schema_history WHERE version='065'");
        int retainedMigrations = missing.owner.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class);
        assertRefused(missing.settings(), FlywayException.class);
        assertIdentityEmpty(missing.owner);
        assertThat(missing.owner.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class)).isEqualTo(retainedMigrations);
        assertThat(missing.owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='065'", Integer.class)).isZero();

        Fixture changed = fresh();
        changed.owner.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='001'");
        Integer changedChecksum = changed.owner.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='001'", Integer.class);
        assertRefused(changed.settings(), FlywayException.class);
        assertIdentityEmpty(changed.owner);
        assertThat(changed.owner.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='001'", Integer.class)).isEqualTo(changedChecksum);

        Fixture future = fresh();
        future.owner.update("""
                INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
                SELECT max(installed_rank)+1,'999','Synthetic unbundled release','SQL','V999__synthetic_unbundled.sql',1,current_user,0,true
                FROM flyway_schema_history
                """);
        assertRefused(future.settings(), FlywayException.class);
        assertIdentityEmpty(future.owner);
        assertThat(future.owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='999'", Integer.class)).isEqualTo(1);
    }

    @Test
    void realFirstAdministratorRollsBackEveryWriteWhenAuditFails() throws Exception {
        Fixture fixture = fresh();
        UUID previousEvent = UUID.randomUUID();
        String previousHash = "1".repeat(64);
        fixture.owner.update("""
                INSERT INTO audit_events(id,occurred_at,organisation_id,school_id,request_id,action,subject_type,subject_id,
                  outcome,reason,safe_metadata,event_hash)
                VALUES(?,now()-INTERVAL '1 day',?,?,'synthetic-fixture','synthetic.operator.reference','SCHOOL',?,
                  'SUCCEEDED','Synthetic historical operator fixture','{}'::jsonb,?)
                """, previousEvent, ORGANISATION, SCHOOL, SCHOOL.toString(), previousHash);
        String previousSnapshot = fixture.owner.queryForObject("SELECT to_jsonb(a)::text FROM audit_events a WHERE id=?", String.class, previousEvent);
        fixture.owner.execute("""
                CREATE FUNCTION synthetic_reject_initial_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic atomicity verification'; END $$
                """);
        fixture.owner.execute("""
                CREATE TRIGGER synthetic_reject_initial_audit BEFORE INSERT ON audit_events
                FOR EACH ROW WHEN (NEW.action='identity.first_administrator.created')
                EXECUTE FUNCTION synthetic_reject_initial_audit()
                """);
        assertThatThrownBy(() -> provision(fixture.settings())).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("Synthetic atomicity verification");
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM user_accounts", Integer.class)).isZero();
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM memberships", Integer.class)).isZero();
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM membership_roles", Integer.class)).isZero();
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isEqualTo(1);
        assertThat(fixture.owner.queryForObject("SELECT to_jsonb(a)::text FROM audit_events a WHERE id=?", String.class, previousEvent)).isEqualTo(previousSnapshot);
        fixture.owner.execute("DROP TRIGGER synthetic_reject_initial_audit ON audit_events");
        fixture.owner.execute("DROP FUNCTION synthetic_reject_initial_audit()");
        provision(fixture.settings());
        Map<String, Object> initial = fixture.owner.queryForMap("SELECT * FROM audit_events WHERE action=?", ACTION);
        assertThat(initial).containsEntry("previous_hash", previousHash).containsEntry("actor_user_id", null)
                .containsEntry("actor_membership_id", null);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM user_accounts", Integer.class)).isEqualTo(1);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM memberships", Integer.class)).isEqualTo(1);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM membership_roles", Integer.class)).isEqualTo(1);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isEqualTo(2);
        assertThat(fixture.owner.queryForObject("SELECT to_jsonb(a)::text FROM audit_events a WHERE id=?", String.class, previousEvent)).isEqualTo(previousSnapshot);
        assertImmutableAudit(fixture.owner, previousEvent);
        assertImmutableAudit(fixture.owner, (UUID) initial.get("id"));
        assertOperatorAuditHash(initial, fixture);
        assertNoSessionsOrMfa(fixture.owner);
    }

    @Test
    void realFirstAdministratorSerializesCompetingToolsAndOrdinaryInserts() throws Exception {
        // A deployment-role default must not establish a stale pre-lock snapshot in the one-use tool.
        control.execute("ALTER ROLE " + identifier(migrationLogin) + " SET default_transaction_isolation='repeatable read'");
        try {
            exerciseCompetingToolsAndOrdinaryInserts();
        } finally {
            control.execute("ALTER ROLE " + identifier(migrationLogin) + " RESET default_transaction_isolation");
        }
    }

    private void exerciseCompetingToolsAndOrdinaryInserts() throws Exception {
        Fixture ordinary = fresh();
        assertThat(ordinary.owner.queryForObject("SHOW default_transaction_isolation", String.class)).isEqualTo("repeatable read");
        try (var executor = Executors.newSingleThreadExecutor();
                var connection = connect(ordinary.url, migrationLogin, ownerPassword)) {
            connection.setAutoCommit(false);
            UUID ordinaryUser = insertOrdinary(transactionJdbc(connection), "ACTIVE");
            int blockerPid = transactionJdbc(connection).queryForObject("SELECT pg_backend_pid()", Integer.class);
            var attempt = executor.submit(() -> attempt(ordinary.settings()));
            await(() -> waitingTools(ordinary, blockerPid) == 1);
            assertThat(attempt.isDone()).isFalse();
            connection.commit();
            Attempt refused = attempt.get(15, TimeUnit.SECONDS);
            assertThat(refused.success).isFalse();
            assertThat(refused.failure).isInstanceOf(IllegalStateException.class);
            assertThat(ordinary.owner.queryForList("SELECT id FROM user_accounts", UUID.class)).containsExactly(ordinaryUser);
            assertThat(ordinary.owner.queryForObject("SELECT count(*) FROM memberships", Integer.class)).isZero();
            assertThat(ordinary.owner.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
        }

        Fixture competing = fresh();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2);
                var blocker = connect(competing.url, migrationLogin, ownerPassword)) {
            blocker.setAutoCommit(false);
            JdbcTemplate blocking = transactionJdbc(blocker);
            blocking.execute("LOCK TABLE public.user_accounts IN SHARE MODE");
            int blockerPid = blocking.queryForObject("SELECT pg_backend_pid()", Integer.class);
            var first = executor.submit(() -> { start.await(); return attempt(competing.settings()); });
            var second = executor.submit(() -> { start.await(); return attempt(competing.settings()); });
            start.countDown();
            await(() -> waitingTools(competing, blockerPid) == 2);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            blocker.commit();
            List<Attempt> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Attempt::success).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> !result.success).map(Attempt::failure).toList())
                    .singleElement().isInstanceOf(IllegalStateException.class);
            assertThat(competing.owner.queryForObject("SELECT count(*) FROM user_accounts", Integer.class)).isEqualTo(1);
            assertThat(competing.owner.queryForObject("SELECT count(*) FROM memberships", Integer.class)).isEqualTo(1);
            assertThat(competing.owner.queryForObject("SELECT count(*) FROM membership_roles", Integer.class)).isEqualTo(1);
            assertThat(competing.owner.queryForObject("SELECT count(*) FROM audit_events WHERE action=?", Integer.class, ACTION)).isEqualTo(1);
            assertNoSessionsOrMfa(competing.owner);
        }
    }

    private Fixture fresh() throws Exception {
        String database = "educore_first_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(database, template);
        Fixture fixture = new Fixture(database);
        assertIdentityEmpty(fixture.owner);
        return fixture;
    }

    private void createRole(String role, String password, String bypass) throws Exception {
        try (var connection = controlDataSource.getConnection();
                var query = connection.prepareStatement("SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER " + bypass
                        + " NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',?,?)")) {
            query.setString(1, role);
            query.setString(2, password);
            try (var result = query.executeQuery(); var command = connection.createStatement()) {
                assertThat(result.next()).isTrue();
                command.execute(result.getString(1));
            }
        }
    }

    private void createDatabase(String database, String source) {
        String sql = "CREATE DATABASE " + identifier(database) + " OWNER " + identifier(migrationLogin);
        if (source != null) sql += " TEMPLATE " + identifier(source);
        control.execute(sql);
        generatedDatabases.add(database);
    }

    private String databaseUrl(String database) {
        return controlUrl.replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
    }

    private static JdbcTemplate jdbc(String url, String login, String password) {
        var dataSource = new DriverManagerDataSource(url, login, password);
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        dataSource.setConnectionProperties(properties);
        return new JdbcTemplate(dataSource);
    }

    private static Connection connect(String url, String login, String password) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", login);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        try {
            return DriverManager.getConnection(url, properties);
        } finally {
            properties.clear();
        }
    }

    private static JdbcTemplate transactionJdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    private static String generatedSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static FirstAdministratorTool.AdminIdentity identity() {
        return FirstAdministratorTool.identity(" SYNTHETIC-FIRST-" + UUID.randomUUID() + "@EXAMPLE.TEST ",
                "Synthetic initial administrator", "hi");
    }

    private static void provision(FirstAdministratorTool.Settings settings) throws Exception {
        char[] password = generatedSecret().toCharArray();
        try {
            FirstAdministratorTool.provision(settings, identity(), password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void assertRefused(FirstAdministratorTool.Settings settings) {
        assertRefused(settings, IllegalStateException.class);
    }

    private static void assertRefused(FirstAdministratorTool.Settings settings, Class<? extends Exception> expected) {
        assertThatThrownBy(() -> provision(settings)).isInstanceOf(expected);
    }

    private static Attempt attempt(FirstAdministratorTool.Settings settings) {
        try {
            provision(settings);
            return new Attempt(true, null);
        } catch (Exception failure) {
            return new Attempt(false, failure);
        }
    }

    private static UUID insertOrdinary(JdbcTemplate jdbc, String status) {
        UUID user = UUID.randomUUID();
        String login = "synthetic-ordinary-" + UUID.randomUUID() + "@example.test";
        char[] password = generatedSecret().toCharArray();
        try {
            String hash = CoreConfiguration.newPasswordEncoder().encode(CharBuffer.wrap(password));
            jdbc.update("""
                    INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status)
                    VALUES(?,?,?,'Synthetic pre-existing account',?,?)
                    """, user, login, login, hash, status);
            return user;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void assertIdentityEmpty(JdbcTemplate jdbc) {
        for (String table : List.of("user_accounts", "memberships", "membership_roles", "audit_events")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        assertNoSessionsOrMfa(jdbc);
    }

    private static void assertNoSessionsOrMfa(JdbcTemplate jdbc) {
        for (String table : List.of("user_sessions", "mfa_totp_factors", "mfa_recovery_codes", "mfa_login_challenges")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }

    private static void assertImmutableAudit(JdbcTemplate jdbc, UUID event) {
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET reason='Synthetic forbidden edit' WHERE id=?", event))
                .rootCause().hasMessageContaining("audit_events is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE id=?", event))
                .rootCause().hasMessageContaining("audit_events is append-only");
    }

    private static void assertOperatorAuditHash(Map<String, Object> audit, Fixture fixture) throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        var returnedMetadata = mapper.readTree(audit.get("safe_metadata").toString());
        String metadata = mapper.writeValueAsString(new TreeMap<>(Map.of(
                "operatorType", "MIGRATION_DATABASE_LOGIN", "databaseLogin", fixture.owner.queryForObject("SELECT current_user", String.class),
                "approvalReference", fixture.reference, "membershipId", returnedMetadata.get("membershipId").stringValue())));
        assertThat(returnedMetadata).isEqualTo(mapper.readTree(metadata));
        Object storedTime = audit.get("occurred_at");
        Instant instant;
        if (storedTime instanceof Timestamp timestamp) instant = timestamp.toInstant();
        else if (storedTime instanceof OffsetDateTime dateTime) instant = dateTime.toInstant();
        else throw new AssertionError("Unexpected PostgreSQL audit timestamp representation");
        String input = String.join("|", audit.get("id").toString(), instant.toString(),
                audit.get("organisation_id").toString(), audit.get("school_id").toString(), "", "",
                audit.get("request_id").toString(), audit.get("action").toString(), audit.get("subject_type").toString(),
                audit.get("subject_id").toString(), audit.get("outcome").toString(), audit.get("reason").toString(),
                metadata, audit.get("previous_hash") == null ? "" : audit.get("previous_hash").toString());
        String reconstructed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        assertThat(audit.get("event_hash")).isEqualTo(reconstructed);
    }

    private int waitingTools(Fixture fixture, int blockerPid) {
        return fixture.owner.queryForObject("""
                SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                WHERE a.datname=current_database() AND a.application_name='educore-first-administrator'
                  AND l.relation='public.user_accounts'::regclass AND l.mode='ShareRowExclusiveLock' AND NOT l.granted
                  AND ?=ANY(pg_blocking_pids(a.pid))
                """, Integer.class, blockerPid);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected real PostgreSQL lock wait was not observed before its bounded deadline");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String identifier(String value) {
        if (!value.matches("[a-z][a-z0-9_]{2,62}")) throw new IllegalArgumentException("Only generated test identifiers are accepted");
        return "\"" + value + "\"";
    }

    @AfterAll
    void removeOnlyGeneratedFixtures() {
        try {
            if (control != null) {
                for (int index = generatedDatabases.size() - 1; index >= 0; index--) {
                    String database = generatedDatabases.get(index);
                    if (!database.equals(template) && !database.matches("educore_first_[0-9a-f]{32}")) {
                        throw new IllegalStateException("Refusing cleanup outside this suite's generated database inventory");
                    }
                    control.execute("DROP DATABASE " + identifier(database) + " WITH (FORCE)");
                }
                if (runtimeCreated) control.execute("DROP ROLE " + identifier(runtimeLogin));
                if (ownerCreated) control.execute("DROP ROLE " + identifier(migrationLogin));
            }
        } finally {
            if (container != null) container.stop();
        }
    }

    private final class Fixture {
        final String database;
        final String url;
        final String reference = "SYNTHETIC-APPROVAL-" + UUID.randomUUID();
        final JdbcTemplate owner;
        final JdbcTemplate runtime;

        Fixture(String database) {
            this.database = database;
            this.url = databaseUrl(database);
            this.owner = jdbc(url, migrationLogin, ownerPassword);
            this.runtime = jdbc(url, runtimeLogin, runtimePassword);
        }

        FirstAdministratorTool.Settings settings() { return settings(Map.of()); }

        FirstAdministratorTool.Settings settings(Map<String, String> overrides) {
            Map<String, String> environment = new HashMap<>();
            environment.put("MIGRATION_DATABASE_URL", url);
            environment.put("MIGRATION_DATABASE_USER", migrationLogin);
            environment.put("MIGRATION_DATABASE_PASSWORD", ownerPassword);
            environment.put("DATABASE_USER", runtimeLogin);
            environment.put("FIRST_ADMIN_DATABASE_NAME", database);
            environment.put("FIRST_ADMIN_ORGANISATION_ID", ORGANISATION.toString());
            environment.put("FIRST_ADMIN_SCHOOL_ID", SCHOOL.toString());
            environment.put("FIRST_ADMIN_CAMPUS_ID", CAMPUS.toString());
            environment.put("FIRST_ADMIN_APPROVAL_REFERENCE", reference);
            environment.putAll(overrides);
            return FirstAdministratorTool.fromEnvironment(environment);
        }
    }

    private record Attempt(boolean success, Exception failure) {}
}
