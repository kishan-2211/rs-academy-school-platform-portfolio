package com.educore.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.identity.SessionAuthenticationFilter;
import com.educore.api.identity.SessionService;
import com.educore.api.tenancy.TenantRequestContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.flywaydb.core.Flyway;

/** Real production-profile HTTP/API context whose pooled login is NOT the migration owner. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ProductionRuntimePostgresIT {
    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SCHOOL = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID CAMPUS = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String RUNTIME = "synthetic_runtime_" + SUFFIX;
    private static final String RUNTIME_DATABASE = "educore_runtime_" + SUFFIX;
    private static final String SYNTHETIC_PASSWORD = UUID.randomUUID() + "-SyntheticOnly";
    private static final UUID FIRST_USER = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();
    private static final UUID OWN_CHILD = UUID.randomUUID();
    private static final UUID OTHER_CHILD = UUID.randomUUID();
    private static final UUID HISTORY_USER = UUID.randomUUID();
    private static final UUID HISTORY_PENDING_FACTOR = UUID.randomUUID();
    private static final UUID HISTORY_ACTIVE_FACTOR = UUID.randomUUID();
    private static final UUID HISTORY_REVOKED_FACTOR = UUID.randomUUID();
    private static PostgreSQLContainer<?> container;
    private static JdbcTemplate owner;
    private static Map<String, String> migrationEnvironment;

    @Autowired private JdbcTemplate runtime;
    @Autowired private ApplicationContext applicationContext;
    @LocalServerPort private int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        String ownerUser;
        String ownerPassword;
        if (url == null || url.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:18.4-alpine");
            container.start();
            url = container.getJdbcUrl();
            ownerUser = container.getUsername();
            ownerPassword = container.getPassword();
        } else {
            if (!url.matches("jdbc:postgresql://[^/]+/educore_integration(?:\\?.*)?")) {
                throw new IllegalStateException("Runtime-role gate requires the isolated educore_integration database");
            }
            ownerUser = required("TEST_DATABASE_USER");
            ownerPassword = required("TEST_DATABASE_PASSWORD");
        }
        owner = new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
        // Committed HTTP fixtures and role mutations must never share the core
        // integration suite's database or its single-active-session constraints.
        owner.execute("CREATE DATABASE " + RUNTIME_DATABASE);
        url = url.replaceFirst("/[^/?]+(?=\\?|$)", "/" + RUNTIME_DATABASE);
        owner = new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
        // Password exists only for this isolated generated role, never as a committed credential.
        try (var connection = DriverManager.getConnection(url, ownerUser, ownerPassword);
                var format = connection.prepareStatement("SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', ?, ?)")) {
            format.setString(1, RUNTIME);
            format.setString(2, SYNTHETIC_PASSWORD);
            try (var result = format.executeQuery(); var statement = connection.createStatement()) {
                result.next();
                statement.execute(result.getString(1));
            }
        }
        migrationEnvironment = Map.of("MIGRATION_DATABASE_URL", url, "MIGRATION_DATABASE_USER", ownerUser,
                "MIGRATION_DATABASE_PASSWORD", ownerPassword, "DATABASE_USER", RUNTIME);
        Flyway.configure().dataSource(url, ownerUser, ownerPassword).locations("classpath:db/migration")
                .target("064").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        seedMfaHistoryBeforeUpgrade();
        DatabaseMigrationTool.migrate(migrationEnvironment);
        fixtures();
        String runtimeUrl = url;
        registry.add("spring.datasource.url", () -> runtimeUrl);
        registry.add("spring.datasource.username", () -> RUNTIME);
        registry.add("spring.datasource.password", () -> SYNTHETIC_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("app.public-url", () -> "https://synthetic.example.test");
        registry.add("app.allowed-origins", () -> "https://synthetic.example.test");
        registry.add("app.security.encryption-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.files.access-key", () -> "synthetic-private-storage");
        registry.add("app.files.secret-key", () -> "synthetic-service-not-used-for-network-access");
        registry.add("app.files.clamav.host", () -> "synthetic-private-scanner.invalid");
        for (String property : new String[]{"app.guardian-invitations.expiry.enabled", "app.calendar.notifications.enabled",
                "app.ptm.reminders.enabled", "app.parent-requests.notifications.enabled", "app.student-leave.notifications.enabled",
                "app.learning.notifications.enabled"}) {
            registry.add(property, () -> false);
        }
    }

    private static void seedMfaHistoryBeforeUpgrade() {
        owner.update("""
                INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status,created_at,updated_at)
                VALUES(?,?,?,'Synthetic migration history',?,'DISABLED',now(),now())
                """, HISTORY_USER, "synthetic-history-" + SUFFIX + "@example.test", "synthetic-history-" + SUFFIX + "@example.test",
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(SYNTHETIC_PASSWORD));
        owner.update("""
                INSERT INTO mfa_totp_factors(id,user_id,label,secret_ciphertext,secret_nonce,status,created_at,verified_at)
                VALUES(?,?,'Synthetic pending history','synthetic-ciphertext','synthetic-nonce','PENDING',TIMESTAMPTZ '2026-01-01 00:00:00+00',NULL),
                      (?,?,'Synthetic active history','synthetic-ciphertext','synthetic-nonce','ACTIVE',TIMESTAMPTZ '2026-01-01 00:00:00+00',TIMESTAMPTZ '2026-01-01 00:01:00+00'),
                      (?,?,'Synthetic revoked history','synthetic-ciphertext','synthetic-nonce','REVOKED',TIMESTAMPTZ '2026-01-01 00:00:00+00',TIMESTAMPTZ '2026-01-01 00:02:00+00')
                """, HISTORY_PENDING_FACTOR, HISTORY_USER, HISTORY_ACTIVE_FACTOR, HISTORY_USER, HISTORY_REVOKED_FACTOR, HISTORY_USER);
        owner.update("""
                INSERT INTO mfa_recovery_codes(id,user_id,code_hash,created_at,used_at)
                VALUES(?,?,?,TIMESTAMPTZ '2026-01-01 00:00:00+00',NULL),
                      (?,?,?,TIMESTAMPTZ '2026-01-01 00:00:00+00',TIMESTAMPTZ '2026-01-01 00:03:00+00')
                """, UUID.randomUUID(), HISTORY_USER, java.util.HexFormat.of().formatHex(java.security.SecureRandom.getSeed(32)),
                UUID.randomUUID(), HISTORY_USER, java.util.HexFormat.of().formatHex(java.security.SecureRandom.getSeed(32)));
    }

    @Test
    void realProductionMfaHistoryMigrationPreservesRecordsAndConstraints() {
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='065'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_totp_factors WHERE user_id=?", Integer.class, HISTORY_USER)).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT status='PENDING' AND verified_at IS NULL FROM mfa_totp_factors WHERE id=?", Boolean.class, HISTORY_PENDING_FACTOR)).isTrue();
        assertThat(owner.queryForObject("SELECT status='ACTIVE' AND verified_at=TIMESTAMPTZ '2026-01-01 00:01:00+00' FROM mfa_totp_factors WHERE id=?", Boolean.class, HISTORY_ACTIVE_FACTOR)).isTrue();
        assertThat(owner.queryForObject("SELECT status='REVOKED' AND verified_at=TIMESTAMPTZ '2026-01-01 00:02:00+00' FROM mfa_totp_factors WHERE id=?", Boolean.class, HISTORY_REVOKED_FACTOR)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=? AND revoked_at IS NULL", Integer.class, HISTORY_USER)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=? AND used_at=TIMESTAMPTZ '2026-01-01 00:03:00+00'", Integer.class, HISTORY_USER)).isEqualTo(1);
        assertThatThrownBy(() -> owner.update("UPDATE mfa_totp_factors SET verified_at=NULL WHERE id=?", HISTORY_ACTIVE_FACTOR))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> owner.update("UPDATE mfa_totp_factors SET status='ACTIVE' WHERE id=?", HISTORY_PENDING_FACTOR))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> owner.update("UPDATE mfa_totp_factors SET verified_at=now() WHERE id=?", HISTORY_PENDING_FACTOR))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(owner.update("UPDATE mfa_totp_factors SET status='REVOKED' WHERE id=?", HISTORY_PENDING_FACTOR)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status='REVOKED' AND verified_at IS NULL FROM mfa_totp_factors WHERE id=?", Boolean.class, HISTORY_PENDING_FACTOR)).isTrue();
        for (String table : List.of("mfa_totp_factors", "mfa_recovery_codes", "mfa_login_challenges")) {
            assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'DELETE,TRUNCATE')", Boolean.class, table)).isFalse();
        }
        ProductionDatabaseSecurity.verify(runtime);
    }

    private static void fixtures() {
        UUID session = UUID.randomUUID();
        owner.update("""
                INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at)
                VALUES (?, ?, ?, 'Synthetic runtime-role acceptance',DATE '2026-04-01',DATE '2027-03-31','ACTIVE',now(),now())
                """, session, ORGANISATION, SCHOOL);
        insertFamily(FIRST_USER, OWN_CHILD, session, "first");
        insertFamily(OTHER_USER, OTHER_CHILD, session, "other");
    }

    private static void insertFamily(UUID user, UUID child, UUID session, String key) {
        UUID membership = UUID.randomUUID();
        UUID guardian = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        owner.update("""
                INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status,created_at,updated_at)
                VALUES (?,?,?,'Synthetic runtime parent',?,'ACTIVE',now(),now())
                """, user, login(key), login(key), Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(SYNTHETIC_PASSWORD));
        owner.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                VALUES (?,?,?,?,?,'ACTIVE',now(),now(),now())
                """, membership, user, ORGANISATION, SCHOOL, CAMPUS);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, '00000000-0000-4000-8000-000000000006')", membership);
        owner.update("""
                INSERT INTO admission_applications(id,organisation_id,school_id,idempotency_key,request_fingerprint,
                  intake_session_label,target_grade_code,applicant_legal_name,birth_date,gender_code,status,created_by_membership_id,created_at,updated_at)
                VALUES (?,?,?, ?,repeat('1',64),'Synthetic runtime-role acceptance','VI','Synthetic runtime learner',
                  DATE '2015-01-01','NOT_STATED','DRAFT',?,now(),now())
                """, application, ORGANISATION, SCHOOL, "synthetic-runtime-" + key + "-" + SUFFIX, membership);
        owner.update("""
                INSERT INTO learners(id,organisation_id,school_id,origin_application_id,legal_name,birth_date,gender_code,status,created_at,updated_at)
                VALUES (?,?,?,?,'Synthetic runtime learner',DATE '2015-01-01','NOT_STATED','ACTIVE',now(),now())
                """, child, ORGANISATION, SCHOOL, application);
        owner.update("""
                INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,
                  origin_application_id,grade_code,status,placement_status,starts_on,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'VI','ACTIVE','PENDING',DATE '2026-04-01',now(),now())
                """, UUID.randomUUID(), ORGANISATION, SCHOOL, CAMPUS, session, child, application);
        owner.update("""
                INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,user_account_id,created_at,updated_at)
                VALUES (?,?,?,'Synthetic runtime guardian',?,'ACTIVE',?,now(),now())
                """, guardian, ORGANISATION, SCHOOL, login(key), user);
        owner.update("""
                INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,relationship_code,
                  primary_contact,pickup_authorized,portal_access_status,created_at,updated_at)
                VALUES (?,?,?,?,'PARENT_GUARDIAN',true,false,'ACTIVE',now(),now())
                """, child, guardian, ORGANISATION, SCHOOL);
    }

    @Test
    void realProductionRuntimeLogsInAndKeepsFamilyReadsObjectScoped() throws Exception {
        String first = authenticate("first");
        String other = authenticate("other");
        var children = get("/api/v1/family/children", first);
        assertThat(children.statusCode()).isEqualTo(200);
        assertThat(children.body()).contains(OWN_CHILD.toString()).doesNotContain(OTHER_CHILD.toString());
        var own = get("/api/v1/family/children/" + OWN_CHILD + "/schedule", first);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.body()).contains(OWN_CHILD.toString(), "\"entries\":[]");
        assertThat(get("/api/v1/family/children/" + OWN_CHILD + "/schedule", other).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/family/children/" + UUID.randomUUID() + "/schedule", first).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/family/children", "").statusCode()).isEqualTo(401);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    void realProductionRuntimeHasNoFallbackUserAndRejectsBasicOrFormLogin() throws Exception {
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
        assertThat(applicationContext.getBean("apiSecurity")).isInstanceOf(SecurityFilterChain.class);
        assertThat(applicationContext.getBean(SessionAuthenticationFilter.class)).isNotNull();
        assertThat(applicationContext.getBean(SessionService.class)).isNotNull();
        assertThat(applicationContext.getBean(PasswordEncoder.class)).isInstanceOf(Argon2PasswordEncoder.class);

        // Even valid synthetic database credentials must not bypass the session API.
        String basic = java.util.Base64.getEncoder().encodeToString(
                (login("first") + ":" + SYNTHETIC_PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var basicResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/family/children"))
                        .timeout(Duration.ofSeconds(15)).header("Authorization", "Basic " + basic).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(basicResponse.statusCode()).isEqualTo(401);
        assertThat(basicResponse.headers().allValues("WWW-Authenticate")).isEmpty();

        var formResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                        .timeout(Duration.ofSeconds(15)).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(formResponse.statusCode()).isEqualTo(401);
        assertThat(formResponse.headers().allValues("Location")).isEmpty();
        assertThat(formResponse.body()).doesNotContain("<form");
    }

    @Test
    @SuppressWarnings("try")
    void realRuntimePoolClearsScopeAndDeniesUnscopedForeignAndDdlAccess() {
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME);
        try (var ignored = TenantRequestContext.open(FIRST_USER, ORGANISATION)) {
            assertThat(runtime.queryForObject("SELECT count(*) FROM learners WHERE id=?", Integer.class, OWN_CHILD)).isEqualTo(1);
            assertThat(runtime.queryForObject("SELECT row_security_active('learners')", Boolean.class)).isTrue();
        }
        assertThat(runtime.queryForObject("SELECT current_app_organisation_id() IS NULL AND current_app_user_id() IS NULL", Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT count(*) FROM learners WHERE id=?", Integer.class, OWN_CHILD)).isZero();
        try (var ignored = TenantRequestContext.open(OTHER_USER, UUID.randomUUID())) {
            assertThat(runtime.queryForObject("SELECT count(*) FROM learners WHERE id=?", Integer.class, OWN_CHILD)).isZero();
        }
        assertThatThrownBy(() -> runtime.execute("CREATE TABLE public.synthetic_forbidden_runtime_table(id integer)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> runtime.execute("TRUNCATE public.learners"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> runtime.update("UPDATE flyway_schema_history SET success=false WHERE false"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> runtime.update("DELETE FROM audit_events WHERE false"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    void realRoleGuardRejectsOwnerInheritedOwnerBypassAndDdlGrants() {
        assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(owner)).isInstanceOf(IllegalStateException.class);
        for (String privilege : new String[]{"BYPASSRLS", "CREATEDB", "CREATEROLE", "REPLICATION"}) {
            owner.execute("ALTER ROLE " + RUNTIME + " " + privilege);
            try {
                assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(runtime)).isInstanceOf(IllegalStateException.class);
            } finally { owner.execute("ALTER ROLE " + RUNTIME + " NO" + privilege); }
        }
        owner.execute("GRANT CREATE ON SCHEMA public TO " + RUNTIME);
        try { assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(runtime)).isInstanceOf(IllegalStateException.class); }
        finally { owner.execute("REVOKE CREATE ON SCHEMA public FROM " + RUNTIME); }
        String group = "synthetic_owner_group_" + SUFFIX;
        owner.execute("CREATE ROLE " + group + " NOLOGIN");
        owner.execute("CREATE TABLE public." + group + "(id integer)");
        owner.execute("ALTER TABLE public." + group + " OWNER TO " + group);
        owner.execute("GRANT " + group + " TO " + RUNTIME + " WITH INHERIT FALSE");
        try { assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(runtime)).isInstanceOf(IllegalStateException.class); }
        finally {
            owner.execute("REVOKE " + group + " FROM " + RUNTIME);
            // This uniquely generated, empty privilege-probe table is not school history.
            owner.execute("DROP TABLE public." + group);
            owner.execute("DROP ROLE " + group);
        }
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    void realMigrationToolRetriesWithoutNewMigrationsAndRejectsOwnerOrMissingRuntime() throws Exception {
        int count = owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        DatabaseMigrationTool.migrate(migrationEnvironment);
        DatabaseMigrationTool.migrate(migrationEnvironment);
        assertThat(owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(count);
        var invalid = new HashMap<>(migrationEnvironment);
        invalid.put("DATABASE_USER", migrationEnvironment.get("MIGRATION_DATABASE_USER"));
        assertThatThrownBy(() -> DatabaseMigrationTool.migrate(invalid)).isInstanceOf(IllegalArgumentException.class);
        invalid.put("DATABASE_USER", "synthetic_missing_runtime_" + SUFFIX);
        assertThatThrownBy(() -> DatabaseMigrationTool.migrate(invalid)).isInstanceOf(IllegalStateException.class);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    void realRoleGuardRejectsDisabledRlsTruncateAndMigrationHistoryPrivileges() {
        owner.execute("ALTER TABLE learners DISABLE ROW LEVEL SECURITY");
        try { assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(runtime)).isInstanceOf(IllegalStateException.class); }
        finally { owner.execute("ALTER TABLE learners ENABLE ROW LEVEL SECURITY"); }
        for (String grant : new String[]{"TRUNCATE ON learners", "UPDATE ON flyway_schema_history", "DELETE ON audit_events"}) {
            owner.execute("GRANT " + grant + " TO " + RUNTIME);
            try { assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(runtime)).isInstanceOf(IllegalStateException.class); }
            finally { owner.execute("REVOKE " + grant + " FROM " + RUNTIME); }
        }
        runtime.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var sameConnection = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            try {
                sameConnection.execute("SET row_security=off");
                assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(sameConnection)).isInstanceOf(IllegalStateException.class);
            } finally { sameConnection.execute("SET row_security=on"); }
            return null;
        });
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    @SuppressWarnings("try")
    void realRuntimeReadsOnlyInvokerNotificationProofViewsWithoutWritePrivileges() {
        var views=List.of("parent_request_notification_sources","student_leave_notification_sources",
                "student_leave_submission_notification_sources","student_leave_submission_notification_reviewers",
                "learning_publication_notification_sources","learning_publication_notification_recipients");
        try(var ignored=TenantRequestContext.open(FIRST_USER,ORGANISATION)) {
            for(String view:views) {
                assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT')",Boolean.class,view)).isTrue();
                assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE')",Boolean.class,view)).isFalse();
                assertThat(runtime.queryForObject("SELECT reloptions @> ARRAY['security_invoker=true'] FROM pg_class WHERE oid=?::regclass",Boolean.class,view)).isTrue();
                assertThat(runtime.queryForObject("SELECT count(*) FROM "+view,Integer.class)).isZero();
            }
        }
        for(String view:views) assertThat(runtime.queryForObject("SELECT count(*) FROM "+view,Integer.class)).isZero();
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    void realProductionRestrictedMfaGatePreservesExactSelfServiceAndCsrfBoundaries() throws Exception {
        var staff = restrictedRuntimeStaff("self-service", true);
        var browser = staffPasswordLogin(staff);
        assertContextMfa(browser, true, ORGANISATION);
        assertMfaDenied(get("/api/v1/people?schoolId=" + SCHOOL, browser.cookie()));
        assertThat(get("/api/v1/people?schoolId=" + SCHOOL, "").statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/auth/mfa", "").statusCode()).isEqualTo(401);
        var mfa = responseJson(get("/api/v1/auth/mfa", browser.cookie()));
        assertThat(mfa.has("sessionVerified")).isTrue();
        assertThat(mfa.get("enabled").booleanValue()).isFalse();
        assertThat(mfa.get("sessionVerified").booleanValue()).isFalse();
        assertThat(responseJson(get("/api/v1/auth/sessions/current", browser.cookie())).get("mfaVerified").booleanValue()).isFalse();

        assertThat(staffRequest("/api/v1/context/organisation", "PUT", browser,
                Map.of("organisationId", ORGANISATION), false).statusCode()).isEqualTo(403);
        assertThat(staffRequest("/api/v1/context/organisation", "PUT", browser,
                Map.of("organisationId", ORGANISATION), true).statusCode()).isEqualTo(200);
        assertThat(staffRequest("/api/v1/context/organisation", "PUT", browser,
                Map.of("organisationId", UUID.randomUUID()), true).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/me/preferences", browser.cookie()).statusCode()).isEqualTo(200);
        assertThat(staffRequest("/api/v1/me/preferences", "PUT", browser,
                Map.of("preferredLanguage", "hi"), false).statusCode()).isEqualTo(403);
        var preferences = staffRequest("/api/v1/me/preferences", "PUT", browser,
                Map.of("preferredLanguage", "hi"), true);
        assertThat(preferences.statusCode()).isEqualTo(200);
        assertThat(responseJson(preferences).get("preferredLanguage").stringValue()).isEqualTo("hi");

        // Exact method/path exceptions must not become prefix-wide bypasses.
        assertMfaDenied(staffRequest("/api/v1/context", "POST", browser, Map.of(), true));
        assertMfaDenied(get("/api/v1/context/organisation", browser.cookie()));
        assertMfaDenied(get("/api/v1/auth/mfa/totp/enrollments", browser.cookie()));
        assertMfaDenied(staffRequest("/api/v1/auth/mfa/totp/enrollments/not-a-factor/verify", "POST", browser,
                Map.of("code", "AAAAAAAAAAAAAAAAAAAA"), true));
        String slug = owner.queryForObject("SELECT slug FROM schools WHERE id=?", String.class, SCHOOL);
        assertThat(get("/api/v1/public/schools/" + slug + "/staff", browser.cookie()).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", browser.cookie()).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/system/status", browser.cookie()).statusCode()).isEqualTo(200);

        var secondOwned = staffPasswordLogin(staff);
        UUID ownedSession = staffCurrentSessionId(secondOwned);
        assertThat(staffRequest("/api/v1/auth/sessions/" + ownedSession, "DELETE", browser, null, false).statusCode()).isEqualTo(403);
        assertThat(staffRequest("/api/v1/auth/sessions/" + ownedSession, "DELETE", browser, null, true).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/auth/sessions/current", secondOwned.cookie()).statusCode()).isEqualTo(401);
        var unrelated = staffPasswordLogin(restrictedRuntimeStaff("unrelated-session", false));
        assertThat(staffRequest("/api/v1/auth/sessions/" + staffCurrentSessionId(unrelated), "DELETE", browser, null, true).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/auth/sessions/current", unrelated.cookie()).statusCode()).isEqualTo(200);
        assertThat(staffRequest("/api/v1/auth/sessions/current", "DELETE", browser, null, false).statusCode()).isEqualTo(403);
        assertThat(staffRequest("/api/v1/auth/sessions/current", "DELETE", browser, null, true).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/auth/sessions/current", browser.cookie()).statusCode()).isEqualTo(401);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE actor_user_id=? AND action='identity.mfa.privileged_access.denied'", Integer.class, staff.userId())).isGreaterThanOrEqualTo(5);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    void realProductionMfaEnrollmentDoesNotElevateOldSessionsAndRequiresVerifiedFactorChanges() throws Exception {
        var staff = restrictedRuntimeStaff("factor-setup", true);
        var firstOld = staffPasswordLogin(staff);
        var secondOld = staffPasswordLogin(staff);
        assertThat(staffRequest("/api/v1/auth/mfa/totp/enrollments", "POST", firstOld,
                Map.of("label", "Synthetic authenticator"), false).statusCode()).isEqualTo(403);
        assertThat(staffFactorCount(staff.userId(), "PENDING")).isZero();
        var replaced = staffEnrollment(firstOld);
        var enrollment = staffEnrollment(secondOld);
        assertThat(staffFactorCount(staff.userId(), "PENDING")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status='REVOKED' AND verified_at IS NULL FROM mfa_totp_factors WHERE id=?", Boolean.class, replaced.id())).isTrue();
        assertThat(staffRequest("/api/v1/auth/mfa/totp/enrollments/" + replaced.id() + "/verify", "POST", firstOld,
                Map.of("code", staffTotpCode(replaced.secret(), 0)), true).statusCode()).isEqualTo(410);
        assertThat(staffRequest("/api/v1/auth/mfa/totp/enrollments/" + enrollment.id() + "/verify", "POST", secondOld,
                Map.of("code", "AAAAAAAAAAAAAAAAAAAA"), true).statusCode()).isEqualTo(401);
        var activated = staffRequest("/api/v1/auth/mfa/totp/enrollments/" + enrollment.id() + "/verify", "POST", secondOld,
                Map.of("code", staffTotpCode(enrollment.secret(), 0)), true);
        assertThat(activated.statusCode()).isEqualTo(200);
        var codes = responseJson(activated).get("recoveryCodes");
        assertThat(codes.size()).isEqualTo(10);
        assertThat(activated.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("educore_session="))).isTrue();
        assertThat(staffFactorCount(staff.userId(), "ACTIVE")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id=? AND mfa_verified_at IS NOT NULL", Integer.class, staff.userId())).isZero();
        assertThat(owner.queryForObject("SELECT secret_ciphertext <> ? AND secret_nonce IS NOT NULL FROM mfa_totp_factors WHERE id=?", Boolean.class, enrollment.secret(), enrollment.id())).isTrue();
        for (var old : List.of(firstOld, secondOld)) {
            assertContextMfa(old, true, ORGANISATION);
            assertThat(responseJson(get("/api/v1/auth/mfa", old.cookie())).get("sessionVerified").booleanValue()).isFalse();
            assertThat(responseJson(get("/api/v1/auth/sessions/current", old.cookie())).get("mfaVerified").booleanValue()).isFalse();
            assertMfaDenied(get("/api/v1/people?schoolId=" + SCHOOL, old.cookie()));
            assertMfaDenied(staffRequest("/api/v1/auth/mfa/totp/enrollments", "POST", old,
                    Map.of("label", "Forbidden weak-session factor"), true));
            assertMfaDenied(staffRequest("/api/v1/auth/mfa/totp/enrollments/" + enrollment.id() + "/verify", "POST", old,
                    Map.of("code", staffTotpCode(enrollment.secret(), 0)), true));
        }
        assertThat(staffFactorCount(staff.userId(), "ACTIVE")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=? AND used_at IS NULL AND revoked_at IS NULL", Integer.class, staff.userId())).isEqualTo(10);

        var challenge = staffLoginChallenge(staff);
        assertThat(staffChallengeVerification(challenge, "AAAAAAAAAAAAAAAAAAAA").statusCode()).isEqualTo(401);
        var completed = staffChallengeVerification(challenge, codes.get(0).stringValue());
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(responseJson(completed).get("session").get("mfaVerified").booleanValue()).isTrue();
        var verified = staffBrowser(completed);
        assertContextMfa(verified, false, ORGANISATION);
        assertThat(responseJson(get("/api/v1/auth/mfa", verified.cookie())).get("sessionVerified").booleanValue()).isTrue();
        assertThat(get("/api/v1/people?schoolId=" + SCHOOL, verified.cookie()).statusCode()).isEqualTo(200);
        assertThat(staffChallengeVerification(challenge, codes.get(1).stringValue()).statusCode()).isEqualTo(401);
        var recoveryRetryChallenge = staffLoginChallenge(staff);
        assertThat(staffChallengeVerification(recoveryRetryChallenge, codes.get(0).stringValue()).statusCode()).isEqualTo(401);
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=? AND used_at IS NULL AND revoked_at IS NULL", Integer.class, staff.userId())).isEqualTo(9);

        var additional = staffEnrollment(verified);
        var unrelated = staffPasswordLogin(restrictedRuntimeStaff("factor-owner-guard", true));
        assertThat(staffRequest("/api/v1/auth/mfa/totp/enrollments/" + additional.id() + "/verify", "POST", unrelated,
                Map.of("code", staffTotpCode(additional.secret(), 0)), true).statusCode()).isEqualTo(404);
        assertMfaDenied(staffRequest("/api/v1/auth/mfa/totp/enrollments", "POST", firstOld,
                Map.of("label", "Forbidden pending replacement"), true));
        assertMfaDenied(staffRequest("/api/v1/auth/mfa/totp/enrollments/" + additional.id() + "/verify", "POST", firstOld,
                Map.of("code", staffTotpCode(additional.secret(), 0)), true));
        assertThat(staffFactorCount(staff.userId(), "PENDING")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM mfa_totp_factors WHERE id=?", String.class, additional.id())).isEqualTo("PENDING");
        assertThat(staffRequest("/api/v1/auth/mfa/totp/enrollments/" + additional.id() + "/verify", "POST", verified,
                Map.of("code", staffTotpCode(additional.secret(), 0)), true).statusCode()).isEqualTo(200);
        assertThat(staffFactorCount(staff.userId(), "ACTIVE")).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=?", Integer.class, staff.userId())).isEqualTo(20);
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=? AND used_at IS NOT NULL AND revoked_at IS NULL", Integer.class, staff.userId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=? AND used_at IS NULL AND revoked_at IS NOT NULL", Integer.class, staff.userId())).isEqualTo(9);
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=? AND used_at IS NULL AND revoked_at IS NULL", Integer.class, staff.userId())).isEqualTo(10);
        assertThat(responseJson(get("/api/v1/auth/mfa", verified.cookie())).get("unusedRecoveryCodes").intValue()).isEqualTo(10);
        assertThat(staffChallengeVerification(staffLoginChallenge(staff), codes.get(1).stringValue()).statusCode()).isEqualTo(401);
        // Use the next accepted skew-window step, not a sleep or a bypass of the actual TOTP verifier.
        var totpCompleted = staffChallengeVerification(staffLoginChallenge(staff), staffTotpCode(additional.secret(), 1));
        assertThat(totpCompleted.statusCode()).isEqualTo(200);
        assertThat(responseJson(totpCompleted).get("session").get("mfaVerified").booleanValue()).isTrue();
        assertThat(get("/api/v1/people?schoolId=" + SCHOOL, staffBrowser(totpCompleted).cookie()).statusCode()).isEqualTo(200);
        assertThat(owner.queryForObject("SELECT mfa_verified_at IS NULL FROM user_sessions WHERE id=?", Boolean.class, staffCurrentSessionId(firstOld))).isTrue();
        // Retained expired challenges do not count toward the five-live-challenge limit or require DELETE.
        for (int index = 0; index < 5; index++) owner.update("""
                INSERT INTO mfa_login_challenges(id,user_id,token_hash,created_at,expires_at)
                VALUES(?,?,?,now()-interval '10 minutes',now()-interval '5 minutes')
                """, UUID.randomUUID(), staff.userId(), java.util.HexFormat.of().formatHex(java.security.SecureRandom.getSeed(32)));
        assertThat(staffLoginChallenge(staff)).isNotBlank();
        assertThat(owner.queryForObject("SELECT count(*) FROM mfa_login_challenges WHERE user_id=? AND expires_at<now() AND consumed_at IS NULL", Integer.class, staff.userId())).isEqualTo(5);
        for (String table : List.of("mfa_totp_factors", "mfa_recovery_codes", "mfa_login_challenges")) {
            assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'DELETE,TRUNCATE')", Boolean.class, table)).isFalse();
        }
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    void realProductionMfaGateUsesLiveGrantsSelectedOrganisationAndSameOrganisationRoles() throws Exception {
        var staff = restrictedRuntimeStaff("live-grants", false);
        var browser = staffPasswordLogin(staff);
        assertContextMfa(browser, false, ORGANISATION);
        assertThat(get("/api/v1/people?schoolId=" + SCHOOL, browser.cookie()).statusCode()).isEqualTo(200);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", staff.membershipId(), staff.restrictedRoleId());
        assertContextMfa(browser, true, ORGANISATION);
        assertMfaDenied(get("/api/v1/people?schoolId=" + SCHOOL, browser.cookie()));
        owner.update("DELETE FROM membership_roles WHERE membership_id=? AND role_id=?", staff.membershipId(), staff.restrictedRoleId());
        assertContextMfa(browser, false, ORGANISATION);
        assertThat(get("/api/v1/people?schoolId=" + SCHOOL, browser.cookie()).statusCode()).isEqualTo(200);

        UUID restrictedMembership = UUID.randomUUID();
        owner.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,?,'ACTIVE',now()+interval '1 day',now(),now())
                """, restrictedMembership, staff.userId(), ORGANISATION, SCHOOL);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", restrictedMembership, staff.restrictedRoleId());
        assertContextMfa(browser, false, ORGANISATION);
        owner.update("UPDATE memberships SET valid_from=now()-interval '1 day' WHERE id=?", restrictedMembership);
        assertContextMfa(browser, true, ORGANISATION);
        owner.update("UPDATE memberships SET valid_until=now()-interval '1 minute' WHERE id=?", restrictedMembership);
        assertContextMfa(browser, false, ORGANISATION);
        owner.update("UPDATE memberships SET valid_until=NULL,status='SUSPENDED' WHERE id=?", restrictedMembership);
        assertContextMfa(browser, false, ORGANISATION);

        UUID foreignOrganisation = UUID.randomUUID(); UUID foreignSchool = UUID.randomUUID(); UUID foreignCampus = UUID.randomUUID();
        owner.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at)
                VALUES(?,?,'Synthetic MFA other organisation','Synthetic MFA other organisation','ACTIVE',now(),now())
                """, foreignOrganisation, "synthetic-mfa-" + foreignOrganisation);
        owner.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at)
                VALUES(?,?,?,'Synthetic MFA other school','Synthetic MFA other school','ACTIVE',now(),now())
                """, foreignSchool, foreignOrganisation, "synthetic-mfa-" + foreignSchool);
        owner.update("""
                INSERT INTO campuses(id,organisation_id,school_id,code,display_name,status,created_at,updated_at)
                VALUES(?,?,?,'SYN','Synthetic MFA other campus','ACTIVE',now(),now())
                """, foreignCampus, foreignOrganisation, foreignSchool);
        UUID foreignRestricted = staffRole(foreignOrganisation, "people.manage");
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", staff.membershipId(), foreignRestricted);
        assertContextMfa(browser, false, ORGANISATION);
        var ownContext = responseJson(get("/api/v1/context", browser.cookie()));
        assertThat(ownContext.get("capabilities").toString()).doesNotContain("people.manage");
        assertThat(get("/api/v1/people?schoolId=" + SCHOOL, browser.cookie()).statusCode()).isEqualTo(200);
        UUID foreignMembership = UUID.randomUUID();
        owner.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,?,?,'ACTIVE',now(),now(),now())
                """, foreignMembership, staff.userId(), foreignOrganisation, foreignSchool, foreignCampus);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", foreignMembership, staffRole(foreignOrganisation, "people.read"));
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", staff.membershipId(), staff.restrictedRoleId());
        assertContextMfa(browser, true, ORGANISATION);
        var selectedForeign = staffRequest("/api/v1/context/organisation", "PUT", browser, Map.of("organisationId", foreignOrganisation), true);
        assertThat(selectedForeign.statusCode()).isEqualTo(200);
        assertThat(responseJson(selectedForeign).get("mfaRequired").booleanValue()).isFalse();
        assertContextMfa(browser, false, foreignOrganisation);
        assertThat(get("/api/v1/people?schoolId=" + foreignSchool, browser.cookie()).statusCode()).isEqualTo(200);
        var selectedOwn = staffRequest("/api/v1/context/organisation", "PUT", browser, Map.of("organisationId", ORGANISATION), true);
        assertThat(selectedOwn.statusCode()).isEqualTo(200);
        assertThat(responseJson(selectedOwn).get("mfaRequired").booleanValue()).isTrue();
        assertContextMfa(browser, true, ORGANISATION);
        assertMfaDenied(get("/api/v1/people?schoolId=" + SCHOOL, browser.cookie()));
        owner.update("DELETE FROM membership_roles WHERE membership_id=? AND role_id=?", staff.membershipId(), staff.restrictedRoleId());
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,'00000000-0000-4000-8000-000000000001')", staff.membershipId());
        assertContextMfa(browser, true, ORGANISATION); // Valid global roles also count.
        assertMfaDenied(get("/api/v1/people?schoolId=" + SCHOOL, browser.cookie()));
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test
    void realProductionEnrollmentWaitsForAccountLockAndRechecksConcurrentActivation() throws Exception {
        var staff = restrictedRuntimeStaff("account-lock", true);
        var browser = staffPasswordLogin(staff);
        var pending = staffEnrollment(browser);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<HttpResponse<String>> blocked = null;
        try (var winner = owner.getDataSource().getConnection()) {
            winner.setAutoCommit(false);
            try {
                try (var lock = winner.prepareStatement("SELECT id FROM user_accounts WHERE id=? FOR UPDATE")) {
                    lock.setObject(1, staff.userId());
                    try (var result = lock.executeQuery()) { assertThat(result.next()).isTrue(); }
                }
                blocked = executor.submit(() -> staffRequest("/api/v1/auth/mfa/totp/enrollments", "POST", browser,
                        Map.of("label", "Must not replace concurrent setup"), true));
                boolean observedLock = false;
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && !blocked.isDone()) {
                    observedLock = Boolean.TRUE.equals(owner.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                              WHERE usename=? AND datname=? AND wait_event_type='Lock'
                                AND query ILIKE '%user_accounts%' AND query ILIKE '%for%update%')
                            """, Boolean.class, RUNTIME, RUNTIME_DATABASE));
                    if (observedLock) break;
                    Thread.sleep(20);
                }
                assertThat(observedLock).as("Actual runtime enrollment waits for the account row, not just the one-connection pool").isTrue();
                // Explicit fixture simulates the winning transaction's activation under the SAME account lock.
                // This is not a claim that two application instances ran enrollment simultaneously.
                try (var activate = winner.prepareStatement("UPDATE mfa_totp_factors SET status='ACTIVE',verified_at=now(),last_used_time_step=0 WHERE id=? AND user_id=? AND status='PENDING'")) {
                    activate.setObject(1, pending.id()); activate.setObject(2, staff.userId());
                    assertThat(activate.executeUpdate()).isEqualTo(1);
                }
                winner.commit();
            } finally { winner.rollback(); }
            assertMfaDenied(blocked.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(staffFactorCount(staff.userId(), "ACTIVE")).isEqualTo(1);
            assertThat(staffFactorCount(staff.userId(), "PENDING")).isZero();
            assertThat(owner.queryForObject("SELECT count(*) FROM mfa_totp_factors WHERE id=?", Integer.class, pending.id())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id=?", Integer.class, staff.userId())).isZero();
        } finally {
            if (blocked != null && !blocked.isDone()) blocked.cancel(true);
            executor.shutdownNow();
        }
        ProductionDatabaseSecurity.verify(runtime);
    }

    private record RestrictedRuntimeStaff(UUID userId, UUID membershipId, String identifier, String password, UUID restrictedRoleId) {}
    private record RuntimeBrowser(String cookie, String csrfToken) {}
    private record RuntimeEnrollment(UUID id, String secret) {}

    private RestrictedRuntimeStaff restrictedRuntimeStaff(String key, boolean restricted) {
        UUID user = UUID.randomUUID(); UUID membership = UUID.randomUUID();
        String identifier = "synthetic-runtime-mfa-" + key + "-" + user + "@example.test";
        String password = UUID.randomUUID() + "-SyntheticOnly";
        owner.update("""
                INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status,created_at,updated_at)
                VALUES(?,?,?,'Synthetic runtime MFA staff',?,'ACTIVE',now(),now())
                """, user, identifier, identifier, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(password));
        owner.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,?,?,'ACTIVE',now(),now(),now())
                """, membership, user, ORGANISATION, SCHOOL, CAMPUS);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", membership, staffRole(ORGANISATION, "people.read"));
        UUID restrictedRole = staffRole(ORGANISATION, "people.manage");
        if (restricted) owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", membership, restrictedRole);
        return new RestrictedRuntimeStaff(user, membership, identifier, password, restrictedRole);
    }

    private UUID staffRole(UUID organisation, String capability) {
        UUID role = UUID.randomUUID();
        owner.update("""
                INSERT INTO roles(id,organisation_id,code,display_name,system_role,created_at,updated_at)
                VALUES(?, ?, ?, 'Synthetic runtime MFA role',false,now(),now())
                """, role, organisation, "SYN_MFA_" + role.toString().replace("-", ""));
        owner.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?,?)", role, capability);
        return role;
    }

    private HttpResponse<String> staffRawLogin(RestrictedRuntimeStaff staff) throws Exception {
        return staffRequest("/api/v1/auth/sessions", "POST", null,
                Map.of("identifier", staff.identifier(), "password", staff.password(), "remember", false), false);
    }

    private RuntimeBrowser staffPasswordLogin(RestrictedRuntimeStaff staff) throws Exception {
        var response = staffRawLogin(staff);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(responseJson(response).get("status").stringValue()).isEqualTo("authenticated");
        assertThat(responseJson(response).get("session").get("mfaVerified").booleanValue()).isFalse();
        return staffBrowser(response);
    }

    private RuntimeBrowser staffBrowser(HttpResponse<String> loginResponse) throws Exception {
        String session = loginResponse.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("educore_session=")).findFirst().orElseThrow();
        assertThat(session.contains("Secure") && session.contains("HttpOnly")).isTrue();
        var current = get("/api/v1/auth/sessions/current", session.split(";", 2)[0]);
        assertThat(current.statusCode()).isEqualTo(200);
        String csrf = current.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("educore_csrf=")).findFirst().orElseThrow().split(";", 2)[0];
        return new RuntimeBrowser(session.split(";", 2)[0] + "; " + csrf, csrf.substring("educore_csrf=".length()));
    }

    private HttpResponse<String> staffRequest(String path, String method, RuntimeBrowser browser,
            Object body, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (browser != null) builder.header("Cookie", browser.cookie());
        if (csrf && browser != null) builder.header("X-CSRF-TOKEN", browser.csrfToken());
        return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(applicationContext.getBean(tools.jackson.databind.ObjectMapper.class).writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private tools.jackson.databind.JsonNode responseJson(HttpResponse<String> response) {
        return applicationContext.getBean(tools.jackson.databind.ObjectMapper.class).readTree(response.body());
    }

    private void assertContextMfa(RuntimeBrowser browser, boolean required, UUID organisation) throws Exception {
        var response = get("/api/v1/context", browser.cookie());
        assertThat(response.statusCode()).isEqualTo(200);
        var json = responseJson(response);
        assertThat(json.has("mfaRequired")).isTrue();
        assertThat(json.get("mfaRequired").booleanValue()).isEqualTo(required);
        assertThat(json.get("activeOrganisationId").stringValue()).isEqualTo(organisation.toString());
    }

    private void assertMfaDenied(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(responseJson(response).get("code").stringValue()).isEqualTo("MFA_VERIFICATION_REQUIRED");
        // Filter denials use problem+json; the existing controller exception handler serializes ApiProblem as JSON.
        assertThat(response.headers().firstValue("Content-Type").orElse("")).isIn("application/problem+json", "application/json");
    }

    private UUID staffCurrentSessionId(RuntimeBrowser browser) throws Exception {
        var response = get("/api/v1/auth/sessions", browser.cookie());
        assertThat(response.statusCode()).isEqualTo(200);
        for (var session : responseJson(response)) if (session.get("current").booleanValue()) return UUID.fromString(session.get("id").stringValue());
        throw new AssertionError("The synthetic current session was not listed.");
    }

    private RuntimeEnrollment staffEnrollment(RuntimeBrowser browser) throws Exception {
        var response = staffRequest("/api/v1/auth/mfa/totp/enrollments", "POST", browser, Map.of("label", "Synthetic authenticator"), true);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = responseJson(response);
        return new RuntimeEnrollment(UUID.fromString(json.get("factorId").stringValue()), json.get("secret").stringValue());
    }

    private int staffFactorCount(UUID user, String status) {
        return owner.queryForObject("SELECT count(*) FROM mfa_totp_factors WHERE user_id=? AND status=?", Integer.class, user, status);
    }

    private String staffLoginChallenge(RestrictedRuntimeStaff staff) throws Exception {
        var response = staffRawLogin(staff);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(responseJson(response).get("status").stringValue()).isEqualTo("mfa_required");
        assertThat(response.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("educore_session="))).isTrue();
        return responseJson(response).get("challengeToken").stringValue();
    }

    private HttpResponse<String> staffChallengeVerification(String challenge, String code) throws Exception {
        return staffRequest("/api/v1/auth/mfa/challenges/verify", "POST", null, Map.of("challengeToken", challenge, "code", code), false);
    }

    private String staffTotpCode(String encodedSecret, int stepOffset) {
        var decoded = new java.io.ByteArrayOutputStream(); int buffer = 0; int bits = 0;
        for (char character : encodedSecret.toCharArray()) {
            int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(character);
            if (value < 0) throw new AssertionError("Synthetic enrollment returned an invalid Base32 secret.");
            buffer = (buffer << 5) | value; bits += 5;
            if (bits >= 8) { bits -= 8; decoded.write((buffer >> bits) & 255); }
        }
        long step = Math.floorDiv(java.time.Instant.now(applicationContext.getBean(java.time.Clock.class)).getEpochSecond(), 30) + stepOffset;
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                applicationContext.getBean(com.educore.api.identity.TotpService.class), "code", decoded.toByteArray(), step);
    }

    private String authenticate(String key) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/sessions"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"identifier\":\"" + login(key) + "\",\"password\":\"" + SYNTHETIC_PASSWORD + "\",\"remember\":false}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("educore_session=")).findFirst().orElseThrow();
        assertThat(cookie.contains("Secure") && cookie.contains("HttpOnly")).isTrue();
        return cookie.split(";", 2)[0];
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String login(String key) { return "synthetic-runtime-" + key + "-" + SUFFIX + "@example.test"; }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    @AfterAll
    static void cleanup() {
        // The explicitly synthetic test cluster is discarded by its harness.
        // No DROP OWNED or deletion of historical school/financial/audit records.
        if (container != null) container.stop();
    }
}
