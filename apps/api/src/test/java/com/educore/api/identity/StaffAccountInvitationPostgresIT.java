package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.educore.api.identity.StaffAccountInvitationModels.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ProductionDatabaseSecurity;
import com.educore.api.people.PeopleStaffAccountBridge;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Actual isolated PostgreSQL, production runtime grants and non-owner RLS; no school/provider data. */
@SpringBootTest
class StaffAccountInvitationPostgresIT {
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = "educore_staff_invite_" + SUFFIX;
    private static final String OWNER_LOGIN = "synthetic_staff_owner_" + SUFFIX;
    private static final String RUNTIME_LOGIN = "synthetic_staff_runtime_" + SUFFIX;
    private static final String OWNER_PASSWORD = secret(), RUNTIME_PASSWORD = secret();
    private static final String ENCRYPTION_KEY = secret();
    private static PostgreSQLContainer<?> container;
    private static JdbcTemplate control, owner;
    private static DriverManagerDataSource controlDataSource;
    private static boolean ownerCreated, runtimeCreated, databaseCreated;
    private static Fixture historical;
    private static Map<String, List<String>> beforeUpgrade;
    private static boolean preservationVerified;

    @Autowired private JdbcTemplate runtime;
    @Autowired private StaffAccountInvitationService invitations;
    @Autowired private PeopleStaffAccountBridge people;
    @Autowired private UserAccountRepository users;
    @Autowired private UserSessionRepository sessions;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SessionTokenCodec tokens;
    @Autowired private StaffAccountInvitationTokenProtector protector;
    @Autowired private SecurityAuditService audit;
    @Autowired private Validator validator;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void isolatedRealDatabase(DynamicPropertyRegistry registry) throws Exception {
        String controlUrl = System.getenv("TEST_DATABASE_URL"), login, password;
        if (controlUrl == null || controlUrl.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:18.4-alpine");
            container.start();
            controlUrl = container.getJdbcUrl(); login = container.getUsername(); password = container.getPassword();
        } else {
            if (!controlUrl.matches("jdbc:postgresql://[^/]+/educore_integration(?:\\?.*)?")) {
                throw new IllegalStateException("Only the isolated educore_integration control database is allowed");
            }
            login = required("TEST_DATABASE_USER"); password = required("TEST_DATABASE_PASSWORD");
        }
        controlDataSource = new DriverManagerDataSource(controlUrl, login, password);
        control = new JdbcTemplate(controlDataSource);
        createRole(OWNER_LOGIN, OWNER_PASSWORD, "BYPASSRLS"); ownerCreated = true;
        createRole(RUNTIME_LOGIN, RUNTIME_PASSWORD, "NOBYPASSRLS"); runtimeCreated = true;
        control.execute("CREATE DATABASE " + identifier(DATABASE) + " OWNER " + identifier(OWNER_LOGIN)); databaseCreated = true;
        String url = controlUrl.replaceFirst("/[^/?]+(?=\\?|$)", "/" + DATABASE);
        owner = new JdbcTemplate(new DriverManagerDataSource(url, OWNER_LOGIN, OWNER_PASSWORD));
        Flyway.configure().dataSource(url, OWNER_LOGIN, OWNER_PASSWORD).locations("classpath:db/migration")
                .target("084").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        historical = fixture();
        beforeUpgrade = new LinkedHashMap<>();
        for (String table : owner.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            if (!table.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalStateException("Actual generated schema required");
            beforeUpgrade.put(table, owner.queryForList("SELECT row_to_json(snapshot)::text FROM \"" + table
                    + "\" snapshot ORDER BY row_to_json(snapshot)::text", String.class));
        }
        Flyway.configure().dataSource(url, OWNER_LOGIN, OWNER_PASSWORD).locations("classpath:db/migration")
                .validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        beforeUpgrade.forEach((table, rows) -> {
            String prefix = table.equals("flyway_schema_history") ? " WHERE version<>'085' OR version IS NULL" : "";
            assertThat(owner.queryForList("SELECT row_to_json(snapshot)::text FROM \"" + table + "\" snapshot" + prefix
                    + " ORDER BY row_to_json(snapshot)::text", String.class)).as("V085 preserves %s", table).isEqualTo(rows);
        });
        for (String table : List.of("staff_account_invitations", "staff_account_invitation_events")) {
            assertThat(owner.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        preservationVerified = true;
        try (var connection = DriverManager.getConnection(url, OWNER_LOGIN, OWNER_PASSWORD)) {
            connection.setAutoCommit(false);
            try (var setting = connection.prepareStatement("SELECT set_config('educore.runtime_role',?,true)")) {
                setting.setString(1, RUNTIME_LOGIN); setting.execute();
            }
            try (var statement = connection.createStatement()) {
                statement.execute(new ClassPathResource("db/runtime-grants.sql").getContentAsString(StandardCharsets.UTF_8));
            }
            connection.commit();
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> RUNTIME_LOGIN);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout=90000");
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("spring.transaction.default-timeout", () -> "30s");
        registry.add("app.security.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("app.staff-invitations.manual-handoff-enabled", () -> true);
        registry.add("app.public-url", () -> "https://synthetic-school.example.test");
        registry.add("app.files.auto-create-bucket", () -> false);
        registry.add("app.files.endpoint", () -> "http://127.0.0.1:1");
        registry.add("app.files.clamav.host", () -> "127.0.0.1");
        registry.add("app.files.clamav.port", () -> 1);
        for (String property : List.of("app.bootstrap.enabled", "app.guardian-invitations.expiry.enabled",
                "app.calendar.notifications.enabled", "app.ptm.reminders.enabled", "app.parent-requests.notifications.enabled",
                "app.student-leave.notifications.enabled", "app.learning.notifications.enabled", "app.push.registration-enabled",
                "app.push.delivery.enabled", "app.push.delivery.worker-enabled", "app.finance.gateway.creation-enabled",
                "app.finance.gateway.worker-enabled", "app.finance.gateway.live-enabled", "app.finance.gateway.refunds-enabled",
                "app.finance.gateway.settlements-enabled")) registry.add(property, () -> false);
    }

    @Test
    void populatedV084UpgradePreservesEveryExistingRowAndUsesActualRestrictedRuntime() {
        assertThat(preservationVerified).isTrue();
        assertThat(beforeUpgrade).hasSize(235);
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='085'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT user_account_id FROM staff_profiles WHERE id=?", UUID.class, historical.staff())).isNull();
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME_LOGIN);
        assertThat(runtime.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
        ProductionDatabaseSecurity.verify(runtime);
        for (String table : List.of("staff_account_invitations", "staff_account_invitation_events")) {
            assertThat(runtime.queryForObject("SELECT row_security_active(?)", Boolean.class, table)).isTrue();
            assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'DELETE,TRUNCATE')", Boolean.class, table)).isFalse();
        }
    }

    @Test
    void newHolderAcceptanceAtomicallyCreatesExactRoleLinksEmployeeConsumesTokenAndMintsNoSession() {
        var f = fixture(); var issued = issue(f); String token = handoff(f, issued);
        assertThat(as(null, () -> invitations.preview(new PreviewRequest(token))).existingAccount()).isFalse();
        String password = secret();
        var accepted = as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, password, password)));
        assertThat(accepted.status()).isEqualTo("accepted");
        UUID user = owner.queryForObject("SELECT id FROM user_accounts WHERE email_normalized=?", UUID.class, f.email());
        assertThat(owner.queryForObject("SELECT user_account_id FROM staff_profiles WHERE id=?", UUID.class, f.staff())).isEqualTo(user);
        assertThat(owner.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, user))
                .satisfies(hash -> assertThat(passwordEncoder.matches(password, hash)).isTrue());
        assertThat(owner.queryForList("""
                SELECT role.code FROM memberships membership JOIN membership_roles mapping ON mapping.membership_id=membership.id
                JOIN roles role ON role.id=mapping.role_id WHERE membership.user_id=? AND membership.school_id=?
                """, String.class, user, f.school())).containsExactly("finance_officer");
        assertThat(owner.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(owner.queryForObject("SELECT status FROM staff_account_invitations WHERE id=?", String.class, issued.id())).isEqualTo("ACCEPTED");
        assertThat(owner.queryForObject("SELECT count(*) FROM staff_profile_history WHERE staff_profile_id=? AND subject_id=? AND reason='Staff invitation accepted'", Integer.class, f.staff(), user)).isEqualTo(1);
        assertThat(owner.queryForList("SELECT safe_metadata::text FROM audit_events WHERE organisation_id=?", String.class, f.org()))
                .allSatisfy(metadata -> assertThat(metadata).doesNotContain(token, password));
        rejected(() -> as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, password, password))));
        assertThat(owner.queryForObject("SELECT count(*) FROM user_accounts WHERE email_normalized=?", Integer.class, f.email())).isEqualTo(1);
    }

    @Test
    void existingHolderUsesAuthenticatedAcceptancePreservesPasswordAndRevokesPriorSessions() {
        var f = fixture(); UUID user = UUID.randomUUID(), session = UUID.randomUUID(), previousMembership = UUID.randomUUID(); insertAccount(user, f.email());
        owner.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,?,'ACTIVE',now(),now(),now())
                """, previousMembership, user, f.org(), f.otherSchool());
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000003')", previousMembership);
        String previousMembershipRow = owner.queryForObject("SELECT row_to_json(r)::text FROM memberships r WHERE id=?", String.class, previousMembership);
        String passwordHash = owner.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, user);
        owner.update("""
                INSERT INTO user_sessions(id,user_id,token_hash,created_at,last_seen_at,expires_at,idle_expires_at,mfa_verified_at)
                VALUES(?,?,?,now(),now(),now()+interval '1 hour',now()+interval '30 minutes',now())
                """, session, user, tokens.hash(tokens.newRawToken()));
        var issued = issue(f); String token = handoff(f, issued);
        assertThat(as(null, () -> invitations.preview(new PreviewRequest(token))).existingAccount()).isTrue();
        var userContext = actor(user, previousMembership, f.org(), f.email(), session, true);
        var accepted = as(userContext, () -> invitations.acceptExisting(userContext, new ExistingAcceptanceRequest(token)));
        assertThat(accepted.status()).isEqualTo("accepted");
        assertThat(owner.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, user)).isEqualTo(passwordHash);
        assertThat(owner.queryForObject("SELECT revoked_at IS NOT NULL FROM user_sessions WHERE id=?", Boolean.class, session)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id=?", Integer.class, user)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT user_account_id FROM staff_profiles WHERE id=?", UUID.class, f.staff())).isEqualTo(user);
        assertThat(owner.queryForObject("SELECT row_to_json(r)::text FROM memberships r WHERE id=?", String.class, previousMembership)).isEqualTo(previousMembershipRow);
        assertThat(owner.queryForList("SELECT role.code FROM membership_roles mapping JOIN roles role ON role.id=mapping.role_id WHERE mapping.membership_id=?", String.class, previousMembership)).containsExactly("teacher");
        rejected(() -> as(userContext, () -> invitations.acceptExisting(userContext, new ExistingAcceptanceRequest(token))));
    }

    @Test
    void existingAccountCannotBeClaimedThroughPublicPasswordOrDifferentSignedInHolder() {
        var f = fixture(); UUID user = UUID.randomUUID(); insertAccount(user, f.email());
        String hash = owner.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, user);
        var issued = issue(f); String token = handoff(f, issued), password = secret(); String before = invitationRow(issued.id());
        rejected("SIGN_IN_REQUIRED", () -> as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, password, password))));
        rejected("ACCOUNT_MISMATCH", () -> as(f.admin(), () -> invitations.acceptExisting(f.admin(), new ExistingAcceptanceRequest(token))));
        assertThat(invitationRow(issued.id())).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, user)).isEqualTo(hash);
        assertThat(owner.queryForObject("SELECT user_account_id FROM staff_profiles WHERE id=?", UUID.class, f.staff())).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM memberships WHERE user_id=?", Integer.class, user)).isZero();
    }

    @Test
    void existingEnabledMfaRequiresVerifiedPrincipalAndActualStoredSessionBeforeGrant() {
        var f = fixture(); UUID user = UUID.randomUUID(), session = UUID.randomUUID(); insertAccount(user, f.email());
        owner.update("""
                INSERT INTO mfa_totp_factors(id,user_id,label,secret_ciphertext,secret_nonce,status,created_at,verified_at)
                VALUES(?,?,'Synthetic active factor',?,?,'ACTIVE',now(),now())
                """, UUID.randomUUID(), user, secret(), secret());
        owner.update("""
                INSERT INTO user_sessions(id,user_id,token_hash,created_at,last_seen_at,expires_at,idle_expires_at)
                VALUES(?,?,?,now(),now(),now()+interval '1 hour',now()+interval '30 minutes')
                """, session, user, tokens.hash(tokens.newRawToken()));
        var issued = issue(f); String token = handoff(f, issued), before = invitationRow(issued.id());
        var weakPrincipal = actor(user, null, f.org(), f.email(), session, false);
        rejected("MFA_REQUIRED", () -> as(weakPrincipal, () -> invitations.acceptExisting(weakPrincipal, new ExistingAcceptanceRequest(token))));
        var claimedAssurance = actor(user, null, f.org(), f.email(), session, true);
        rejected("MFA_REQUIRED", () -> as(claimedAssurance, () -> invitations.acceptExisting(claimedAssurance, new ExistingAcceptanceRequest(token))));
        assertThat(invitationRow(issued.id())).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT user_account_id FROM staff_profiles WHERE id=?", UUID.class, f.staff())).isNull();
        // Synthetic fixture supplies stored assurance; HTTP challenge acceptance is a separate security gate.
        owner.update("UPDATE user_sessions SET mfa_verified_at=now() WHERE id=?", session);
        assertThat(as(claimedAssurance, () -> invitations.acceptExisting(claimedAssurance, new ExistingAcceptanceRequest(token))).status()).isEqualTo("accepted");
        assertThat(owner.queryForObject("SELECT revoked_at IS NOT NULL FROM user_sessions WHERE id=?", Boolean.class, session)).isTrue();
    }

    @Test
    void handoffIsExplicitDefaultDisabledOneUseAndCryptographicallyPurposeScoped() {
        var f = fixture(); var issued = issue(f); String before = invitationRow(issued.id());
        var disabled = service(Clock.systemUTC(), false, audit);
        var request = new HandoffRequest(UUID.randomUUID(), issued.rowVersion(), "Synthetic verified private handoff");
        rejected("HANDOFF_DISABLED", () -> transactionAs(f.admin(), () -> disabled.revealHandoff(f.admin(), f.school(), f.staff(), issued.id(), request)));
        assertThat(invitationRow(issued.id())).isEqualTo(before);
        var handoff = as(f.admin(), () -> invitations.revealHandoff(f.admin(), f.school(), f.staff(), issued.id(), request));
        assertThat(handoff.setupToken()).matches("[A-Za-z0-9_-]{43}");
        rejected("HANDOFF_ALREADY_REVEALED", () -> as(f.admin(), () -> invitations.revealHandoff(f.admin(), f.school(), f.staff(), issued.id(), request)));
        assertThat(as(f.admin(), () -> invitations.list(f.admin(), f.school(), f.staff())).items().toString()).doesNotContain(handoff.setupToken());
        UUID purpose = UUID.randomUUID(); var protectedToken = protector.protect(handoff.setupToken(), f.org(), f.school(), purpose);
        assertThat(protector.reveal(protectedToken, f.org(), f.school(), purpose)).isEqualTo(handoff.setupToken());
        assertThatThrownBy(() -> protector.reveal(protectedToken, UUID.randomUUID(), f.school(), purpose)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> protector.reveal(protectedToken, f.org(), UUID.randomUUID(), purpose)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> protector.reveal(protectedToken, f.org(), f.school(), UUID.randomUUID())).isInstanceOf(RuntimeException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM user_accounts WHERE email_normalized=?", Integer.class, f.email())).isZero();
    }

    @Test
    void issuanceReplaysExactRequestRejectsChangedTermsAndSupersedesOpenInvitation() {
        var f = fixture(); var request = issueRequest(UUID.randomUUID());
        var first = as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(), request));
        var replay = as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(), request));
        assertThat(replay.id()).isEqualTo(first.id());
        rejected("REQUEST_CONFLICT", () -> as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(),
                new IssueRequest(request.requestId(), 0, "teacher", 24, request.reason()))));
        var replacement = as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(), issueRequest(UUID.randomUUID())));
        assertThat(replacement.id()).isNotEqualTo(first.id());
        assertThat(current(f, first.id()).status()).isEqualTo("REVOKED");
        assertThat(current(f, replacement.id()).status()).isEqualTo("ISSUED");
        assertThat(as(f.admin(), () -> invitations.list(f.admin(), f.school(), f.staff())).items()).hasSize(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM staff_account_invitations WHERE organisation_id=? AND status='ISSUED'", Integer.class, f.org())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM user_accounts WHERE email_normalized=?", Integer.class, f.email())).isZero();
    }

    @Test
    void invalidInvitationTermsAndPasswordConfirmationCannotCreateAccess() {
        var f = fixture();
        for (var request : List.of(new IssueRequest(UUID.randomUUID(), 0, "parent_guardian", 24, "Synthetic invalid role"),
                new IssueRequest(UUID.randomUUID(), 0, "student", 24, "Synthetic invalid role"),
                new IssueRequest(UUID.randomUUID(), 0, "finance_officer", 0, "Synthetic invalid duration"),
                new IssueRequest(UUID.randomUUID(), 0, "finance_officer", 169, "Synthetic invalid duration"),
                new IssueRequest(UUID.randomUUID(), 1, "finance_officer", 24, "Synthetic stale employee version"))) {
            rejected(() -> as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(), request)));
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM staff_account_invitations WHERE organisation_id=?", Integer.class, f.org())).isZero();
        var issued = issue(f); String token = handoff(f, issued), before = invitationRow(issued.id());
        rejected(() -> as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, secret(), secret()))));
        assertThat(invitationRow(issued.id())).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM user_accounts WHERE email_normalized=?", Integer.class, f.email())).isZero();
    }

    @Test
    void schoolTenantAndLiveCapabilityChecksRejectUnpermittedIssuerWithoutWrites() {
        var f = fixture(); var foreign = fixture();
        rejected(() -> as(f.admin(), () -> invitations.issue(f.admin(), f.otherSchool(), f.staff(), issueRequest(UUID.randomUUID()))));
        rejected(() -> as(foreign.admin(), () -> invitations.issue(foreign.admin(), f.school(), f.staff(), issueRequest(UUID.randomUUID()))));
        owner.update("DELETE FROM membership_roles WHERE membership_id=?", f.admin().membershipIds().iterator().next());
        rejected(() -> as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(), issueRequest(UUID.randomUUID()))));
        assertThat(owner.queryForObject("SELECT count(*) FROM staff_account_invitations WHERE organisation_id=?", Integer.class, f.org())).isZero();
    }

    @Test
    void tokenDiscoveryDoesNotExposeUnscopedOrForeignTenantInvitationsThroughRuntimeRls() {
        var f = fixture(); var foreign = fixture(); var issued = issue(f);
        assertThat(runtime.queryForObject("SELECT count(*) FROM staff_account_invitations WHERE id=?", Integer.class, issued.id())).isZero();
        assertThat(as(foreign.admin(), () -> runtime.queryForObject("SELECT count(*) FROM staff_account_invitations WHERE id=?", Integer.class, issued.id()))).isZero();
        assertThat(as(f.admin(), () -> runtime.queryForObject("SELECT count(*) FROM staff_account_invitations WHERE id=?", Integer.class, issued.id()))).isEqualTo(1);
        assertThat(as(foreign.admin(), () -> runtime.update("UPDATE staff_account_invitations SET row_version=row_version+1 WHERE id=?", issued.id()))).isZero();
        assertThat(current(f, issued.id()).status()).isEqualTo("ISSUED");
    }

    @Test
    void databaseConstraintsAndAppendOnlyHistoryRefuseForgedConsumptionAndDestructiveEdits() {
        var f = fixture(); var issued = issue(f); String before = invitationRow(issued.id());
        assertThatThrownBy(() -> owner.update("UPDATE staff_account_invitations SET status='ACCEPTED' WHERE id=?", issued.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(invitationRow(issued.id())).isEqualTo(before);
        var events = owner.queryForList("SELECT row_to_json(r)::text FROM staff_account_invitation_events r WHERE organisation_id=? ORDER BY id", String.class, f.org());
        assertThat(events).isNotEmpty();
        assertThatThrownBy(() -> owner.update("UPDATE staff_account_invitation_events SET id=id WHERE organisation_id=?", f.org()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> owner.update("DELETE FROM staff_account_invitation_events WHERE organisation_id=?", f.org()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> as(f.admin(), () -> runtime.update("DELETE FROM staff_account_invitations WHERE id=?", issued.id())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(owner.queryForList("SELECT row_to_json(r)::text FROM staff_account_invitation_events r WHERE organisation_id=? ORDER BY id", String.class, f.org())).isEqualTo(events);
    }

    @Test
    void revocationAndExpiryPreventAcceptanceAndNeverResetExistingIdentity() {
        var f = fixture(); var issued = issue(f); String token = handoff(f, issued), password = secret();
        var current = current(f, issued.id()); var request = new RevokeRequest(UUID.randomUUID(), current.rowVersion(), "Synthetic staff invitation cancelled");
        var revoked = as(f.admin(), () -> invitations.revoke(f.admin(), f.school(), f.staff(), issued.id(), request));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(as(f.admin(), () -> invitations.revoke(f.admin(), f.school(), f.staff(), issued.id(), request)).id()).isEqualTo(issued.id());
        rejected(() -> as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, password, password))));
        var expiredFixture = fixture(); var expiring = issue(expiredFixture); String expiredToken = handoff(expiredFixture, expiring);
        var later = service(Clock.fixed(expiring.expiresAt().plusSeconds(1), ZoneOffset.UTC), true, audit);
        rejected(() -> transactionAs(null, () -> later.preview(new PreviewRequest(expiredToken))));
        rejected(() -> transactionAs(null, () -> later.acceptNew(new NewAcceptanceRequest(expiredToken, password, password))));
        assertThat(owner.queryForObject("SELECT count(*) FROM user_accounts WHERE email_normalized IN (?,?)", Integer.class, f.email(), expiredFixture.email())).isZero();
    }

    @Test
    void changedEmployeeOrRevokedIssuerAuthorityInvalidatesPendingTokenWithoutGrant() {
        var changed = fixture(); var changedInvitation = issue(changed); String changedToken = handoff(changed, changedInvitation), password = secret();
        owner.update("UPDATE staff_profiles SET display_name='Synthetic changed employee',row_version=row_version+1,updated_at=now() WHERE id=?", changed.staff());
        rejected(() -> as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(changedToken, password, password))));
        var revoked = fixture(); var issued = issue(revoked); String token = handoff(revoked, issued);
        owner.update("UPDATE memberships SET status='SUSPENDED',row_version=row_version+1,updated_at=now() WHERE id=?", revoked.admin().membershipIds().iterator().next());
        rejected(() -> as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, password, password))));
        for (var f : List.of(changed, revoked)) {
            assertThat(owner.queryForObject("SELECT count(*) FROM user_accounts WHERE email_normalized=?", Integer.class, f.email())).isZero();
            assertThat(owner.queryForObject("SELECT user_account_id FROM staff_profiles WHERE id=?", UUID.class, f.staff())).isNull();
        }
    }

    @Test
    void existingTargetSchoolMembershipCannotBeSilentlyReactivatedOrExpanded() {
        for (String status : List.of("ACTIVE", "SUSPENDED", "ENDED")) {
            var f = fixture(); UUID user = UUID.randomUUID(), membership = UUID.randomUUID(), session = UUID.randomUUID(); insertAccount(user, f.email());
            owner.update("""
                    INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,now(),now(),now())
                    """, membership, user, f.org(), f.school(), f.campus(), status);
            owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000003')", membership);
            owner.update("""
                    INSERT INTO user_sessions(id,user_id,token_hash,created_at,last_seen_at,expires_at,idle_expires_at)
                    VALUES(?,?,?,now(),now(),now()+interval '1 hour',now()+interval '30 minutes')
                    """, session, user, tokens.hash(tokens.newRawToken()));
            var issued = issue(f); String token = handoff(f, issued); var userContext = actor(user, membership, f.org(), f.email(), session, false);
            String before = owner.queryForObject("SELECT row_to_json(r)::text FROM memberships r WHERE id=?", String.class, membership);
            rejected("MEMBERSHIP_REVIEW_REQUIRED", () -> as(userContext, () -> invitations.acceptExisting(userContext, new ExistingAcceptanceRequest(token))));
            assertThat(owner.queryForObject("SELECT row_to_json(r)::text FROM memberships r WHERE id=?", String.class, membership)).isEqualTo(before);
            assertThat(owner.queryForObject("SELECT count(*) FROM membership_roles WHERE membership_id=?", Integer.class, membership)).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT user_account_id FROM staff_profiles WHERE id=?", UUID.class, f.staff())).isNull();
        }
    }

    @Test
    void concurrentExactIssuanceAndTokenAcceptanceCannotDuplicateAccountsMembershipsOrHistory() throws Exception {
        var f = fixture(); var request = issueRequest(UUID.randomUUID());
        var issued = parallel(() -> as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(), request)),
                () -> as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(), request)));
        assertThat(issued.get(0).id()).isEqualTo(issued.get(1).id());
        String token = handoff(f, issued.getFirst()), password = secret();
        var outcomes = parallel(() -> attemptAcceptance(token, password), () -> attemptAcceptance(token, password));
        assertThat(outcomes).containsExactlyInAnyOrder("accepted", "REJECTED");
        UUID user = owner.queryForObject("SELECT id FROM user_accounts WHERE email_normalized=?", UUID.class, f.email());
        assertThat(owner.queryForObject("SELECT count(*) FROM memberships WHERE user_id=? AND school_id=?", Integer.class, user, f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM staff_profile_history WHERE staff_profile_id=? AND subject_id=?", Integer.class, f.staff(), user)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id=?", Integer.class, user)).isZero();
    }

    @Test
    void lateAuditFailureRollsBackNewAccountGrantLinkAndTokenConsumption() {
        var f = fixture(); var issued = issue(f); String token = handoff(f, issued), password = secret(), before = invitationRow(issued.id());
        var failedAudit = new SecurityAuditService(runtime, tools.jackson.databind.json.JsonMapper.builder().build(), Clock.systemUTC()) {
            @Override public void recordScoped(AuthenticatedUser actor, UUID membership, UUID school, String action,
                    String subjectType, String subjectId, AuditOutcome outcome, String reason, Map<String, ?> metadata) {
                throw new IllegalStateException("Synthetic audit storage failure");
            }
        };
        var failing = service(Clock.systemUTC(), true, failedAudit);
        assertThatThrownBy(() -> transactionAs(null, () -> failing.acceptNew(new NewAcceptanceRequest(token, password, password))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic audit storage failure");
        assertThat(invitationRow(issued.id())).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM user_accounts WHERE email_normalized=?", Integer.class, f.email())).isZero();
        assertThat(owner.queryForObject("SELECT user_account_id FROM staff_profiles WHERE id=?", UUID.class, f.staff())).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM staff_profile_history WHERE staff_profile_id=?", Integer.class, f.staff())).isZero();
        assertThat(as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, password, password))).status()).isEqualTo("accepted");
    }

    @Test
    void accountCreatedAfterPreviewCannotBeOverwrittenOrAdoptedByPublicAcceptance() {
        var f = fixture(); var issued = issue(f); String token = handoff(f, issued), password = secret();
        assertThat(as(null, () -> invitations.preview(new PreviewRequest(token))).existingAccount()).isFalse();
        UUID user = UUID.randomUUID(); insertAccount(user, f.email());
        String hash = owner.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, user);
        rejected("SIGN_IN_REQUIRED", () -> as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, password, password))));
        assertThat(owner.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, user)).isEqualTo(hash);
        assertThat(owner.queryForObject("SELECT count(*) FROM memberships WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(current(f, issued.id()).status()).isEqualTo("ISSUED");
    }

    private Invitation issue(Fixture f) { return as(f.admin(), () -> invitations.issue(f.admin(), f.school(), f.staff(), issueRequest(UUID.randomUUID()))); }
    private static IssueRequest issueRequest(UUID id) { return new IssueRequest(id, 0, "finance_officer", 24, "Synthetic staff onboarding approval"); }
    private String handoff(Fixture f, Invitation issued) {
        return as(f.admin(), () -> invitations.revealHandoff(f.admin(), f.school(), f.staff(), issued.id(),
                new HandoffRequest(UUID.randomUUID(), issued.rowVersion(), "Synthetic private verified employee handoff"))).setupToken();
    }
    private Invitation current(Fixture f, UUID id) { return as(f.admin(), () -> invitations.list(f.admin(), f.school(), f.staff())).items().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow(); }
    private static String invitationRow(UUID id) { return owner.queryForObject("SELECT row_to_json(r)::text FROM staff_account_invitations r WHERE id=?", String.class, id); }
    private String attemptAcceptance(String token, String password) {
        try { return as(null, () -> invitations.acceptNew(new NewAcceptanceRequest(token, password, password))).status(); }
        catch (ApiException failure) { assertThat(failure.status().is4xxClientError()).isTrue(); return "REJECTED"; }
    }
    private static <T> List<T> parallel(Supplier<T> first, Supplier<T> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic concurrency barrier timed out"); return first.get(); });
            var b = executor.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic concurrency barrier timed out"); return second.get(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(a.get(45, TimeUnit.SECONDS), b.get(45, TimeUnit.SECONDS));
        }
    }

    private StaffAccountInvitationService service(Clock clock, boolean handoff, SecurityAuditService selectedAudit) {
        return new StaffAccountInvitationService(new NamedParameterJdbcTemplate(runtime), people, users, sessions,
                passwordEncoder, tokens, protector, selectedAudit, clock, validator, handoff);
    }

    private <T> T as(AuthenticatedUser user, Supplier<T> work) {
        try (var ignored = TenantRequestContext.open(user == null ? null : user.userId(), user == null ? null : user.activeOrganisationId())) {
            // Exercise the actual proxied service's own transaction attributes.
            return work.get();
        }
    }

    private <T> T transactionAs(AuthenticatedUser user, Supplier<T> work) {
        var transaction = new TransactionTemplate(transactionManager); transaction.setTimeout(30);
        return as(user, () -> transaction.execute(status -> work.get()));
    }

    private static Fixture fixture() {
        UUID org = UUID.randomUUID(), school = UUID.randomUUID(), otherSchool = UUID.randomUUID(), campus = UUID.randomUUID();
        UUID user = UUID.randomUUID(), membership = UUID.randomUUID(), staff = UUID.randomUUID();
        String key = UUID.randomUUID().toString().replace("-", ""), email = "synthetic-staff-" + key + "@example.test";
        owner.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at)
                VALUES(?,?,'Synthetic invitation organisation','Synthetic invitation organisation','ACTIVE',now(),now())
                """, org, "synthetic-" + key);
        for (UUID id : List.of(school, otherSchool)) owner.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at)
                VALUES(?,?,?,'Synthetic invitation school','Synthetic invitation school','ACTIVE',now(),now())
                """, id, org, "synthetic-" + id);
        owner.update("""
                INSERT INTO campuses(id,organisation_id,school_id,code,display_name,status,created_at,updated_at)
                VALUES(?,?,?,'SYNTHETIC','Synthetic invitation campus','ACTIVE',now(),now())
                """, campus, org, school);
        String adminEmail = "synthetic-issuer-" + key + "@example.test";
        insertAccount(user, adminEmail);
        owner.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,?,?,'ACTIVE',now(),now(),now())
                """, membership, user, org, school, campus);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001')", membership);
        owner.update("""
                INSERT INTO staff_profiles(id,organisation_id,school_id,employee_code,legal_name,display_name,primary_language,
                  work_email_normalized,joined_on,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(?,?,?,?,'Synthetic invitation employee','Synthetic invitation employee','EN',?,?,'ACTIVE',?,now(),now(),0)
                """, staff, org, school, "SYNTHETIC-" + key.toUpperCase(java.util.Locale.ROOT), email, LocalDate.now(Clock.systemUTC()).minusDays(1), membership);
        return new Fixture(org, school, otherSchool, campus, staff, email, actor(user, membership, org, adminEmail));
    }

    private static void insertAccount(UUID user, String email) {
        owner.update("""
                INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status,created_at,updated_at)
                VALUES(?,?,?,'Synthetic invitation account',?,'ACTIVE',now(),now())
                """, user, email, email, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(secret()));
    }

    private static AuthenticatedUser actor(UUID user, UUID membership, UUID org, String email) {
        return actor(user, membership, org, email, UUID.randomUUID(), true);
    }

    private static AuthenticatedUser actor(UUID user, UUID membership, UUID org, String email, UUID session, boolean mfaVerified) {
        return new AuthenticatedUser(user, session, email, email, "Synthetic invitation actor", mfaVerified, org,
                membership == null ? Set.of() : Set.of(membership), Set.of("identity.manage", "people.manage", "people.read"), List.of());
    }

    private static void rejected(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.status().is4xxClientError()).isTrue());
    }

    private static void rejected(String suffix, Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.status().is4xxClientError()).isTrue();
            assertThat(failure.code()).isEqualTo("STAFF_INVITATION_" + suffix);
        });
    }

    private static void createRole(String role, String password, String bypass) throws Exception {
        try (var connection = controlDataSource.getConnection(); var format = connection.prepareStatement(
                "SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER " + bypass + " NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',?,?)")) {
            format.setString(1, role); format.setString(2, password);
            try (var row = format.executeQuery(); var command = connection.createStatement()) { row.next(); command.execute(row.getString(1)); }
        }
    }

    @AfterAll
    static void removeOnlyGeneratedFixture() {
        List<Throwable> failures = new ArrayList<>();
        if (control != null) {
            cleanup(failures, () -> { if (databaseCreated) control.execute("DROP DATABASE " + identifier(DATABASE) + " WITH (FORCE)"); });
            cleanup(failures, () -> { if (runtimeCreated) control.execute("DROP ROLE " + identifier(RUNTIME_LOGIN)); });
            cleanup(failures, () -> { if (ownerCreated) control.execute("DROP ROLE " + identifier(OWNER_LOGIN)); });
        }
        cleanup(failures, () -> { if (container != null) container.stop(); });
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Generated staff invitation fixture cleanup failed");
            failures.forEach(failure::addSuppressed); throw failure;
        }
    }

    private static void cleanup(List<Throwable> failures, Runnable work) { try { work.run(); } catch (RuntimeException failure) { failures.add(failure); } }
    private static String identifier(String value) {
        if (!value.matches("(?:educore_staff_invite_|synthetic_staff_(?:owner|runtime)_)[a-f0-9]{32}")) throw new IllegalArgumentException("Generated fixture identity required");
        return '"' + value + '"';
    }
    private static String secret() { return Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32)); }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " required"); return value; }
    private record Fixture(UUID org, UUID school, UUID otherSchool, UUID campus, UUID staff, String email, AuthenticatedUser admin) {}
}
