package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.ProductionDatabaseSecurity;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real migrated PostgreSQL, isolated synthetic records, non-owner runtime and no provider/network. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayWorkPostgresIT {
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String database = "educore_gateway_work_" + suffix;
    private final String ownerLogin = "synthetic_work_owner_" + suffix, runtimeLogin = "synthetic_work_runtime_" + suffix;
    private final String ownerPassword = secret(), runtimePassword = secret();
    private final Clock clock = Clock.systemUTC();
    private PostgreSQLContainer<?> container;
    private DriverManagerDataSource controlDataSource;
    private JdbcTemplate control, owner, runtime;
    private TransactionTemplate transactions;
    private GatewayReservationService reservations;
    private GatewayWorkQueue queue;
    private ApplicationProperties properties;
    private boolean ownerCreated, runtimeCreated, databaseCreated;
    private Fixture legacyInstantFixture;
    private UUID legacyInstantAttempt, legacyInstantFact, legacyInstantCheckout, legacyInstantCapture;
    private Instant legacyInstantPaid;
    private java.util.Map<String,List<String>> legacyInstantSnapshot;
    private int exactInstantUpgradeCount;
    private int hostedCheckoutUpgradeCount;
    private UUID hostedCheckoutUpgradeFact;
    private java.util.Map<String,List<String>> hostedCheckoutUpgradeSnapshot;

    @BeforeAll void migrateNewDatabaseWithActualNonOwnerRuntime() throws Exception {
        String controlUrl = System.getenv("TEST_DATABASE_URL"), user, password;
        if (controlUrl == null || controlUrl.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:18.4-alpine");
            container.start(); controlUrl = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        } else {
            if (!controlUrl.matches("jdbc:postgresql://[^/]+/educore_integration(?:\\?.*)?")) throw new IllegalStateException("Isolated educore_integration control database required");
            user = required("TEST_DATABASE_USER"); password = required("TEST_DATABASE_PASSWORD");
        }
        controlDataSource = dataSource(controlUrl, user, password); control = new JdbcTemplate(controlDataSource);
        createRole(ownerLogin, ownerPassword, "BYPASSRLS"); ownerCreated = true;
        createRole(runtimeLogin, runtimePassword, "NOBYPASSRLS"); runtimeCreated = true;
        control.execute("CREATE DATABASE " + identifier(database) + " OWNER " + identifier(ownerLogin)); databaseCreated = true;
        String url = controlUrl.replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
        Flyway.configure().dataSource(url, ownerLogin, ownerPassword).locations("classpath:db/migration")
                .target("070").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        owner = new JdbcTemplate(dataSource(url, ownerLogin, ownerPassword));
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='069'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_provider_configs", Integer.class)).isZero();
        try (var connection = DriverManager.getConnection(url, ownerLogin, ownerPassword)) {
            connection.setAutoCommit(false);
            try (var scoped = connection.prepareStatement("SELECT set_config('educore.runtime_role',?,true)")) {
                scoped.setString(1, runtimeLogin); scoped.execute();
            }
            try (var statement = connection.createStatement()) {
                statement.execute(new ClassPathResource("db/runtime-grants.sql").getContentAsString(StandardCharsets.UTF_8));
            }
            connection.commit();
        }
        var runtimeSource = (javax.sql.DataSource) new com.educore.api.tenancy.TenantAwareDataSourceBeanPostProcessor()
                .postProcessAfterInitialization(dataSource(url, runtimeLogin, runtimePassword), "dataSource");
        runtime = new JdbcTemplate(runtimeSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(runtimeSource)); transactions.setTimeout(30);
        ProductionDatabaseSecurity.verify(runtime);
        properties = new ApplicationProperties("https://synthetic-school.example.test", List.of(), Optional.empty(), Optional.empty(),
                new ApplicationProperties.Security("synthetic_session", true, Duration.ofHours(1), Duration.ofHours(2), Duration.ofMinutes(10), null,
                        Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofDays(1), "Synthetic school"),
                new ApplicationProperties.Bootstrap(false, null, null));
        var named = new NamedParameterJdbcTemplate(runtime);
        reservations = new GatewayReservationService(named, new FamilyFinanceService(named),
                new SecurityAuditService(runtime, JsonMapper.builder().build(), clock), new SensitiveValueCipher(properties, new SecureRandom()), properties, clock);
        // Short real leases avoid simulated timestamps, fake database expiry and minute-long test sleeps.
        queue = new GatewayWorkQueue(named, clock, Duration.ofSeconds(3));
        // Commit genuine legacy rows with all V070 constraints/RLS active before
        // adding exact-source columns. Never backfill or disable historical guards.
        seedLegacyInstantEvidence();
        legacyInstantSnapshot = legacyInstantHistory();
        exactInstantUpgradeCount = Flyway.configure().dataSource(url,ownerLogin,ownerPassword)
                .locations("classpath:db/migration").target("071").validateMigrationNaming(true).cleanDisabled(true).load().migrate().migrationsExecuted;
        assertThat(legacyInstantHistory()).isEqualTo(legacyInstantSnapshot);
        ProductionDatabaseSecurity.verify(runtime);
        // Preserve the specifically proven V070->V071 upgrade before applying
        // a different release. A distinct real V071 fact also proves that V072
        // preserves full exact-source fields, not merely NULL legacy companions.
        Instant upgradePaid = databaseInstant().plusNanos(500);
        awaitDatabaseTime(upgradePaid.plusNanos(500));
        var upgradeFact = new PaymentProviderAdapter.PaymentObservation("synthetic_v071_upgrade_order", "synthetic_v071_upgrade_payment",
                new BigDecimal("500.00"), "INR", PaymentProviderAdapter.PaymentStatus.SUCCESS, true, upgradePaid, "upi");
        hostedCheckoutUpgradeFact = background(legacyInstantFixture,
                () -> queue.retainPaymentObservation(legacyInstantFixture.binding(), null, upgradeFact));
        hostedCheckoutUpgradeSnapshot = hostedCheckoutUpgradeHistory();
        hostedCheckoutUpgradeCount = Flyway.configure().dataSource(url, ownerLogin, ownerPassword)
                .locations("classpath:db/migration").target("072").validateMigrationNaming(true).cleanDisabled(true).load().migrate().migrationsExecuted;
        assertThat(hostedCheckoutUpgradeHistory()).isEqualTo(hostedCheckoutUpgradeSnapshot);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts", Integer.class)).isZero();
        // A new table, unlike V071's new columns, needs the operator-owned
        // grants rerun after migration. Never grant privileges to the owner role.
        try (var connection = DriverManager.getConnection(url, ownerLogin, ownerPassword)) {
            connection.setAutoCommit(false);
            try (var scoped = connection.prepareStatement("SELECT set_config('educore.runtime_role',?,true)")) {
                scoped.setString(1, runtimeLogin); scoped.execute();
            }
            try (var statement = connection.createStatement()) {
                statement.execute(new ClassPathResource("db/runtime-grants.sql").getContentAsString(StandardCharsets.UTF_8));
            }
            connection.commit();
        }
        ProductionDatabaseSecurity.verify(runtime);
        // Keep the dedicated populated V070/V071/V072 assertions above, then
        // run normal work cases against the complete current application schema.
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        try(var connection=DriverManager.getConnection(url,ownerLogin,ownerPassword)) {
            connection.setAutoCommit(false);
            try(var scoped=connection.prepareStatement("SELECT set_config('educore.runtime_role',?,true)")) {
                scoped.setString(1,runtimeLogin);scoped.execute();
            }
            try(var statement=connection.createStatement()) {
                statement.execute(new ClassPathResource("db/runtime-grants.sql").getContentAsString(StandardCharsets.UTF_8));
            }
            connection.commit();
        }
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void migrationAddsOnlyScopedDurableMetadataAndRetainsDenyByDefaultRuntime() {
        ProductionDatabaseSecurity.verify(runtime);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_class WHERE relname IN ('fee_gateway_attempt_work','fee_gateway_webhook_inbox','fee_gateway_payment_observations')
                  AND relkind='r' AND relrowsecurity AND relforcerowsecurity
                """, Integer.class)).isEqualTo(3);
        for (String table : List.of("fee_gateway_attempt_work", "fee_gateway_webhook_inbox", "fee_gateway_payment_observations")) {
            assertThat(runtime.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_name IN ('fee_gateway_attempt_work','fee_gateway_webhook_inbox','fee_gateway_payment_observations')
                  AND column_name IN ('raw_body','customer_details','card_number','cvv','upi_pin','client_secret','signature','phone')
                """, Integer.class)).isZero();
    }

    @Test void enqueueJoinsReservationTransactionAndRetryReturnsOneOriginalWorkRow() {
        Fixture f = fixture(); UUID requestId = UUID.randomUUID();
        assertThatThrownBy(() -> as(f, f.parent().userId(), () -> {
            var attempt = reservations.prepare(f.parent(), f.learner(), request(f, requestId));
            queue.enqueueAttempt(f.binding(), attempt.id());
            throw new IllegalStateException("Synthetic rollback after durable enqueue");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_allocations WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE school_id=?", Integer.class, f.school())).isZero();
        UUID attempt = as(f, f.parent().userId(), () -> {
            var planned = reservations.prepare(f.parent(), f.learner(), request(f, requestId));
            assertThat(queue.enqueueAttempt(f.binding(), planned.id())).isEqualTo(planned.id());
            return planned.id();
        });
        assertThat(as(f, f.parent().userId(), () -> queue.enqueueAttempt(f.binding(), attempt))).isEqualTo(attempt);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        assertNoFinancialPosting(f);
    }

    @Test void createDispatchIsOneUseAndUnknownLeaseRecoveryCanNeverResetItsEvidence() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        var first = background(f, () -> queue.claimNextAttempt(f.binding()));
        assertThat(first.id()).isEqualTo(attempt); assertThat(first.createDispatched()).isFalse();
        assertThat(background(f, () -> queue.markCreateDispatched(first))).isTrue();
        assertThat(background(f, () -> queue.markCreateDispatched(first))).isFalse();
        Instant dispatched = owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant();
        assertThat(background(f, () -> queue.complete(first, GatewayWorkQueue.Outcome.RETRY, "GATEWAY_CREATE_OUTCOME_UNKNOWN", Duration.ofSeconds(1)))).isTrue();
        awaitDatabaseTime(owner.queryForObject("SELECT next_attempt_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant());
        var second = background(f, () -> queue.claimNextAttempt(f.binding()));
        assertThat(second.attemptNumber()).isEqualTo(2); assertThat(second.createDispatched()).isTrue();
        assertThat(background(f, () -> queue.markCreateDispatched(second))).isFalse();
        assertThat(background(f, () -> queue.complete(first, GatewayWorkQueue.Outcome.COMPLETE, "GATEWAY_DISCOVERY_COMPLETE", null))).isFalse();
        awaitDatabaseTime(second.leaseUntil());
        assertThat(background(f, () -> queue.complete(second, GatewayWorkQueue.Outcome.COMPLETE, "GATEWAY_DISCOVERY_COMPLETE", null))).isFalse();
        assertThat(background(f, () -> queue.recoverExpiredAttemptLease(f.binding()))).isEqualTo(attempt);
        assertThat(owner.queryForObject("SELECT last_error_code FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("GATEWAY_LEASE_OUTCOME_UNKNOWN");
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant()).isEqualTo(dispatched);
        assertThat(owner.queryForObject("SELECT create_dispatched_by_lease_token FROM fee_gateway_attempt_work WHERE attempt_id=?", UUID.class, attempt)).isEqualTo(first.token());
        assertThat(background(f, () -> queue.claimNextAttempt(f.binding()))).isNull(); // Honest backoff remains due in the future.
        assertNoFinancialPosting(f);
    }

    @Test void concurrentClaimsLeaseOneRowAndStaleTokensCannotCompleteAnotherLease() throws Exception {
        Fixture f = fixture(); UUID attempt = prepareWork(f); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return background(f, () -> queue.claimNextAttempt(f.binding())); });
            var b = workers.submit(() -> { start.await(); return background(f, () -> queue.claimNextAttempt(f.binding())); });
            start.countDown(); var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS);
            assertThat((first == null ? 0 : 1) + (second == null ? 0 : 1)).isEqualTo(1);
            var current = first == null ? second : first;
            var stale = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, attempt, f.binding(), UUID.randomUUID(),
                    current.startedAt(), current.leaseUntil(), current.attemptNumber(), false);
            assertThat(background(f, () -> queue.complete(stale, GatewayWorkQueue.Outcome.COMPLETE, "GATEWAY_DISCOVERY_COMPLETE", null))).isFalse();
            assertThat(background(f, () -> queue.complete(current, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_ROUTE_REVIEW_REQUIRED", null))).isTrue();
            assertThat(background(f, () -> queue.claimNextAttempt(f.binding()))).isNull();
        }
    }

    @Test void minimalWebhookRetriesDeduplicateButChangedEvidenceAndInvalidRoutingDoNotDisappear() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        var event = event(attempt, "a".repeat(64));
        UUID inbox = background(f, () -> queue.retainVerifiedWebhook(f.binding(), event));
        assertThat(background(f, () -> queue.retainVerifiedWebhook(f.binding(), event))).isEqualTo(inbox);
        UUID changed = background(f, () -> queue.retainVerifiedWebhook(f.binding(), event(attempt, "b".repeat(64))));
        assertThat(changed).isNotEqualTo(inbox);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=?", Integer.class, f.school())).isEqualTo(2);
        var conflicting = new PaymentProviderAdapter.VerifiedWebhook("CASHFREE", "a".repeat(64), "PAYMENT_FAILED_WEBHOOK", attempt.toString(),
                "synthetic_order", "synthetic_payment", null);
        assertThatThrownBy(() -> background(f, () -> queue.retainVerifiedWebhook(f.binding(), conflicting))).isInstanceOf(IllegalStateException.class);
        var wrong = new GatewayWorkQueue.ProviderBinding(f.org(), f.school(), f.provider(), "CASHFREE", "TEST", "different_synthetic_merchant", "synthetic:not-enabled");
        assertThatThrownBy(() -> background(f, () -> queue.retainVerifiedWebhook(wrong, event(attempt, "c".repeat(64))))).isInstanceOf(DataAccessException.class);
        assertNoFinancialPosting(f);
    }

    @Test void webhookIntakeLeasesRetainImmutableAuthenticatedFieldsAcrossExpiredRecovery() {
        Fixture f = fixture(); UUID inbox = background(f, () -> queue.retainVerifiedWebhook(f.binding(), event(null, "d".repeat(64))));
        var lease = background(f, () -> queue.claimNextWebhook(f.binding()));
        assertThat(lease.id()).isEqualTo(inbox);
        assertThatThrownBy(() -> background(f, () -> runtime.update("UPDATE fee_gateway_webhook_inbox SET body_sha256=repeat('e',64),row_version=row_version+1,updated_at=? WHERE id=?",
                java.sql.Timestamp.from(clock.instant()), inbox))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("DELETE FROM fee_gateway_webhook_inbox WHERE id=?", inbox)).isInstanceOf(DataAccessException.class);
        awaitDatabaseTime(lease.leaseUntil());
        assertThat(background(f, () -> queue.recoverExpiredWebhookLease(f.binding()))).isEqualTo(inbox);
        assertThat(background(f, () -> queue.complete(lease, GatewayWorkQueue.Outcome.COMPLETE, "GATEWAY_WEBHOOK_PROCESSED", null))).isFalse();
        assertThat(owner.queryForObject("SELECT body_sha256 FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("d".repeat(64));
        assertThat(owner.queryForObject("SELECT payment_reference FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("synthetic_payment");
        assertNoFinancialPosting(f);
    }

    @Test void rawHmacIntakeCommitsOnlyMinimalScopedEvidenceAndAuthenticDelayedRetriesDeduplicate() throws Exception {
        Fixture f = fixture(); UUID attempt = prepareWork(f); var intake = webhookIntake(f.binding());
        byte[] wire = webhookBytes(attempt, "SUCCESS"); byte[] original = wire.clone();
        UUID inbox = intake.accept(RECOVERY_ROUTE, wire, webhookSignature(wire, WEBHOOK_TIME), WEBHOOK_TIME).inboxId();
        String delayed = "1500000000000";
        assertThat(intake.accept(RECOVERY_ROUTE, wire, webhookSignature(wire, delayed), delayed).inboxId()).isEqualTo(inbox);
        assertThat(wire).isEqualTo(original);
        var retained = owner.queryForMap("SELECT * FROM fee_gateway_webhook_inbox WHERE id=?", inbox);
        assertThat(retained.get("organisation_id")).isEqualTo(f.org());
        assertThat(retained.get("school_id")).isEqualTo(f.school());
        assertThat(retained.get("payment_provider_config_id")).isEqualTo(f.provider());
        assertThat(retained.get("body_sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire)));
        assertThat(retained.toString()).doesNotContain(WEBHOOK_SECRET, "synthetic-private-नाम", "customer_details", "raw_body", "signature");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempts WHERE id=?", String.class, attempt)).isEqualTo("RESERVED");
        assertThat(TenantRequestContext.current()).isEmpty(); assertNoFinancialPosting(f);
    }

    @Test void rawHmacIntakeRejectsForgedMutatedUtf8AndDuplicateEventsWithoutInboxOrFinancialWrites() throws Exception {
        Fixture f = fixture(); var intake = webhookIntake(f.binding()); byte[] wire = webhookBytes(null, "SUCCESS");
        String signature = webhookSignature(wire, WEBHOOK_TIME);
        assertIntakeRejected(intake, wire, "A".repeat(43) + "=", WEBHOOK_TIME);
        assertIntakeRejected(intake, wire, signature, "1600000000001");
        assertIntakeRejected(intake, (new String(wire, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8), signature, WEBHOOK_TIME);
        assertIntakeRejected(intake, new String(wire, StandardCharsets.UTF_8).replace("नाम", "name").getBytes(StandardCharsets.UTF_8), signature, WEBHOOK_TIME);
        byte[] duplicate = new String(wire, StandardCharsets.UTF_8).replace("\"type\":", "\"type\":\"PAYMENT_FAILED_WEBHOOK\",\"type\":").getBytes(StandardCharsets.UTF_8);
        assertIntakeRejected(intake, duplicate, webhookSignature(duplicate, WEBHOOK_TIME), WEBHOOK_TIME);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=?", Integer.class, f.school())).isZero();
        assertNoFinancialPosting(f);
    }

    @Test void rawHmacIntakeConcurrentRetriesCommitOneOriginalInboxWithoutResettingItsWork() throws Exception {
        Fixture f = fixture(); var intake = webhookIntake(f.binding()); byte[] wire = webhookBytes(null, "SUCCESS");
        String signature = webhookSignature(wire, WEBHOOK_TIME); var start = new CountDownLatch(1);
        UUID first;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return intake.accept(RECOVERY_ROUTE, wire, signature, WEBHOOK_TIME).inboxId(); });
            var b = workers.submit(() -> { start.await(); return intake.accept(RECOVERY_ROUTE, wire, signature, WEBHOOK_TIME).inboxId(); });
            start.countDown(); first = a.get(15, TimeUnit.SECONDS); assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(first);
        }
        var lease = background(f, () -> queue.claimNextWebhook(f.binding()));
        assertThat(background(f, () -> queue.complete(lease, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_ROUTE_REVIEW_REQUIRED", null))).isTrue();
        var original = owner.queryForMap("SELECT * FROM fee_gateway_webhook_inbox WHERE id=?", first);
        assertThat(intake.accept(RECOVERY_ROUTE, wire, signature, WEBHOOK_TIME).inboxId()).isEqualTo(first);
        assertThat(owner.queryForMap("SELECT * FROM fee_gateway_webhook_inbox WHERE id=?", first)).isEqualTo(original);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        assertNoFinancialPosting(f);
    }

    @Test void rawHmacIntakeAcceptsExactInactiveHistoricalProviderButFailsClosedOnCredentialRotation() throws Exception {
        Fixture f = fixture(); UUID attempt = prepareWork(f); var intake = webhookIntake(f.binding());
        owner.update("UPDATE payment_provider_configs SET status='INACTIVE',row_version=row_version+1,updated_at=now() WHERE id=?", f.provider());
        byte[] wire = webhookBytes(attempt, "SUCCESS"); UUID inbox = intake.accept(RECOVERY_ROUTE, wire, webhookSignature(wire, WEBHOOK_TIME), WEBHOOK_TIME).inboxId();
        owner.update("UPDATE payment_provider_configs SET credentials_secret_reference='synthetic:rotated-not-enabled',row_version=row_version+1,updated_at=now() WHERE id=?", f.provider());
        assertIntakeRejected(intake, wire, webhookSignature(wire, WEBHOOK_TIME), WEBHOOK_TIME);
        assertThat(owner.queryForObject("SELECT id FROM fee_gateway_webhook_inbox WHERE school_id=?", UUID.class, f.school())).isEqualTo(inbox);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempts WHERE id=?", String.class, attempt)).isEqualTo("RESERVED");
        assertNoFinancialPosting(f);
    }

    @Test void rawHmacIntakeRejectsVisibleSiblingCheckoutAndRouteReuseWhileKeepingTenantEvidenceIsolated() throws Exception {
        Fixture f = fixture(), sibling = fixture(f.org()), other = fixture(); UUID attempt = prepareWork(f);
        byte[] linked = webhookBytes(attempt, "SUCCESS"); String signature = webhookSignature(linked, WEBHOOK_TIME);
        assertIntakeRejected(webhookIntake(sibling.binding()), linked, signature, WEBHOOK_TIME);
        var wrongSchool = new GatewayWorkQueue.ProviderBinding(f.org(), sibling.school(), f.provider(), "CASHFREE", "TEST", f.binding().merchantReference(), f.binding().credentialsReference());
        assertIntakeRejected(webhookIntake(wrongSchool), linked, signature, WEBHOOK_TIME);
        byte[] unlinked = webhookBytes(null, "SUCCESS"); String unlinkedSignature = webhookSignature(unlinked, WEBHOOK_TIME);
        UUID own = webhookIntake(f.binding()).accept(RECOVERY_ROUTE, unlinked, unlinkedSignature, WEBHOOK_TIME).inboxId();
        UUID foreign = webhookIntake(other.binding()).accept(RECOVERY_ROUTE, unlinked, unlinkedSignature, WEBHOOK_TIME).inboxId();
        assertThat(foreign).isNotEqualTo(own);
        assertThat(background(f, () -> runtime.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE id=?", Integer.class, foreign))).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE id IN (?,?)", Integer.class, own, foreign)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=?", Integer.class, sibling.school())).isZero();
        assertNoFinancialPosting(f); assertNoFinancialPosting(sibling); assertNoFinancialPosting(other);
    }

    @Test void rawHmacIntakeRetainsFailedDroppedSuccessAndRefundFactsWithoutInventingPaymentOrRefundAuthority() throws Exception {
        Fixture f = fixture(); var intake = webhookIntake(f.binding());
        for (String status : List.of("FAILED", "USER_DROPPED", "SUCCESS")) {
            byte[] wire = webhookBytes(null, status); intake.accept(RECOVERY_ROUTE, wire, webhookSignature(wire, WEBHOOK_TIME), WEBHOOK_TIME);
        }
        for (String refund : List.of("{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{\"refund\":{\"order_id\":\"synthetic_order\",\"cf_payment_id\":\"synthetic_payment\",\"refund_id\":\"synthetic_refund\",\"refund_status\":\"SUCCESS\"}}}",
                "{\"type\":\"AUTO_REFUND_STATUS_WEBHOOK\",\"data\":{\"auto_refund\":{\"cf_payment_id\":\"synthetic_payment\",\"refund_status\":\"SUCCESS\"}}}")) {
            byte[] wire = refund.getBytes(StandardCharsets.UTF_8); intake.accept(RECOVERY_ROUTE, wire, webhookSignature(wire, WEBHOOK_TIME), WEBHOOK_TIME);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=?", Integer.class, f.school())).isEqualTo(5);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE school_id=?", Integer.class, f.school())).isZero();
        assertNoFinancialPosting(f);
    }

    @Test void rawHmacIntakeCannotJoinHumanOrForeignSystemTransactionsOrAcknowledgeRolledBackRouting() throws Exception {
        Fixture f = fixture(), sibling = fixture(f.org()); var intake = webhookIntake(f.binding()); byte[] wire = webhookBytes(null, "SUCCESS");
        String signature = webhookSignature(wire, WEBHOOK_TIME);
        assertThatThrownBy(() -> as(f, f.parent().userId(), () -> intake.accept(RECOVERY_ROUTE, wire, signature, WEBHOOK_TIME))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> background(sibling, () -> intake.accept(RECOVERY_ROUTE, wire, signature, WEBHOOK_TIME))).isInstanceOf(IllegalStateException.class);
        UUID attempt = prepareWork(sibling); byte[] foreign = webhookBytes(attempt, "SUCCESS");
        assertIntakeRejected(intake, foreign, webhookSignature(foreign, WEBHOOK_TIME), WEBHOOK_TIME);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(intake.accept(RECOVERY_ROUTE, wire, signature, WEBHOOK_TIME).inboxId()).isNotNull();
        assertNoFinancialPosting(f); assertNoFinancialPosting(sibling);
    }

    @Test void providerReadFactsPreserveSuccessDuringIncompleteDiscoveryAndOutOfOrderFailure() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        var pending = payment(PaymentProviderAdapter.PaymentStatus.PENDING, false, null, "500.00", "INR");
        UUID pendingId = background(f, () -> queue.retainPaymentObservation(f.binding(), attempt, pending));
        Instant paid = clock.instant().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var success = payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, paid, "500", "INR");
        UUID successId = background(f, () -> queue.retainPaymentObservation(f.binding(), attempt, success));
        assertThat(background(f, () -> queue.retainPaymentObservation(f.binding(), attempt,
                payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, paid, "500.00", "INR")))).isEqualTo(successId);
        UUID failed = background(f, () -> queue.retainPaymentObservation(f.binding(), attempt,
                payment(PaymentProviderAdapter.PaymentStatus.FAILED, false, null, "500", "INR")));
        assertThat(List.of(pendingId, successId, failed).stream().distinct().count()).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT provider_paid_at FROM fee_gateway_payment_observations WHERE id=?", java.sql.Timestamp.class, successId).toInstant()).isEqualTo(paid);
        assertThat(owner.queryForObject("SELECT payment_status FROM fee_gateway_payment_observations WHERE id=?", String.class, successId)).isEqualTo("SUCCESS");
        assertThatThrownBy(() -> background(f, () -> runtime.update("UPDATE fee_gateway_payment_observations SET payment_status='FAILED' WHERE id=?", successId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("DELETE FROM fee_gateway_payment_observations WHERE id=?", successId)).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE school_id=?", Integer.class, f.school())).isZero();
        assertNoFinancialPosting(f);
    }

    @Test void humanCrossTenantAndCrossSchoolProviderScopesCannotOperateGatewayWork() {
        Fixture f = fixture(), siblingSchool = fixture(f.org()), other = fixture(); UUID attempt = prepareWork(f);
        assertThatThrownBy(() -> as(f, f.parent().userId(), () -> queue.claimNextAttempt(f.binding()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> as(f, UUID.randomUUID(), () -> queue.enqueueAttempt(f.binding(), attempt))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> background(other, () -> queue.claimNextAttempt(f.binding()))).isInstanceOf(IllegalStateException.class);
        assertThat(background(other, () -> runtime.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE attempt_id=?", Integer.class, attempt))).isZero();
        var wrongSchool = new GatewayWorkQueue.ProviderBinding(f.org(), UUID.randomUUID(), f.provider(), "CASHFREE", "TEST", f.binding().merchantReference(), "synthetic:not-enabled");
        assertThat(background(f, () -> queue.claimNextAttempt(wrongSchool))).isNull();
        assertThatThrownBy(() -> background(f, () -> queue.retainVerifiedWebhook(wrongSchool, event(null, "f".repeat(64))))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> background(siblingSchool, () -> queue.retainVerifiedWebhook(siblingSchool.binding(), event(attempt, "f".repeat(64)))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> background(other, () -> queue.retainPaymentObservation(other.binding(), attempt,
                payment(PaymentProviderAdapter.PaymentStatus.PENDING, false, null, "500", "INR")))).isInstanceOf(DataAccessException.class);
    }

    @Test void directDatabaseWritesCannotManufactureDispatchSkipVersionsOrEraseOriginalMarkers() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        assertThatThrownBy(() -> background(f, () -> runtime.update("""
                UPDATE fee_gateway_attempt_work SET create_dispatched_at=?,create_dispatched_by_lease_token=?,updated_at=?,row_version=row_version+1 WHERE attempt_id=?
                """, java.sql.Timestamp.from(clock.instant()), UUID.randomUUID(), java.sql.Timestamp.from(clock.instant()), attempt))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> background(f, () -> runtime.update("UPDATE fee_gateway_attempt_work SET row_version=row_version+2 WHERE attempt_id=?", attempt)))
                .isInstanceOf(DataAccessException.class);
        var claim = background(f, () -> queue.claimNextAttempt(f.binding()));
        assertThat(background(f, () -> queue.markCreateDispatched(claim))).isTrue();
        assertThatThrownBy(() -> background(f, () -> runtime.update("""
                UPDATE fee_gateway_attempt_work SET create_dispatched_at=NULL,create_dispatched_by_lease_token=NULL,updated_at=?,row_version=row_version+1 WHERE attempt_id=?
                """, java.sql.Timestamp.from(clock.instant()), attempt))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("DELETE FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt)).isInstanceOf(DataAccessException.class);
        UUID undispatched = prepareWork(f);
        var expired = background(f, () -> queue.claimNextAttempt(f.binding()));
        assertThat(expired.id()).isEqualTo(undispatched);
        awaitDatabaseTime(expired.leaseUntil());
        assertThatThrownBy(() -> background(f, () -> runtime.update("""
                UPDATE fee_gateway_attempt_work SET create_dispatched_at=?,create_dispatched_by_lease_token=?,updated_at=?,row_version=row_version+1 WHERE attempt_id=?
                """,java.sql.Timestamp.from(expired.startedAt()),expired.token(),java.sql.Timestamp.from(expired.startedAt()),undispatched)))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("current unexpired one-use lease");
        java.sql.Timestamp oldUpdated = owner.queryForObject("SELECT updated_at FROM fee_gateway_attempt_work WHERE attempt_id=?",java.sql.Timestamp.class,attempt);
        assertThatThrownBy(() -> background(f, () -> runtime.update("""
                UPDATE fee_gateway_attempt_work SET status='COMPLETE',next_attempt_at=?,completed_at=?,updated_at=?,
                  lease_token=NULL,lease_started_at=NULL,lease_until=NULL,last_error_code='GATEWAY_DISCOVERY_COMPLETE',row_version=row_version+1 WHERE attempt_id=?
                """,oldUpdated,oldUpdated,oldUpdated,attempt)))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("expired lease has an unknown outcome");
        assertThat(background(f, () -> queue.markCreateDispatched(expired))).isFalse();
        assertNoFinancialPosting(f);
    }

    @Test void mismatchedAndOrphanProviderFactsRemainEvidenceWithoutInventingReceiptEligibility() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        UUID mismatch = background(f, () -> queue.retainPaymentObservation(f.binding(), attempt,
                payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, clock.instant().minusSeconds(1), "499", "USD")));
        UUID orphan = background(f, () -> queue.retainPaymentObservation(f.binding(), null,
                payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, clock.instant().minusSeconds(1), "500", "INR")));
        assertThat(owner.queryForObject("SELECT currency_code FROM fee_gateway_payment_observations WHERE id=?", String.class, mismatch)).isEqualTo("USD");
        assertThat(owner.queryForObject("SELECT attempt_id FROM fee_gateway_payment_observations WHERE id=?", UUID.class, orphan)).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE school_id=?", Integer.class, f.school())).isZero();
        assertNoFinancialPosting(f);
    }

    @Test void aLiveLeaseCannotDispatchAnExpiredReservationByBackdatingItsMarker() {
        Fixture f = fixture(); var named = new NamedParameterJdbcTemplate(runtime);
        // Represent a legitimately older synthetic intent; then observe its actual expiry on the database clock.
        Clock olderIntentClock = Clock.offset(clock, Duration.ofMinutes(30).negated().plusSeconds(5));
        var olderReservations = new GatewayReservationService(named, new FamilyFinanceService(named),
                new SecurityAuditService(runtime, JsonMapper.builder().build(), clock), new SensitiveValueCipher(properties, new SecureRandom()), properties, olderIntentClock);
        var longerLeaseQueue = new GatewayWorkQueue(named, clock, Duration.ofSeconds(10));
        var attempt = as(f, f.parent().userId(), () -> {
            var planned = olderReservations.prepare(f.parent(), f.learner(), request(f, UUID.randomUUID()));
            longerLeaseQueue.enqueueAttempt(f.binding(), planned.id()); return planned;
        });
        var liveLease = background(f, () -> longerLeaseQueue.claimNextAttempt(f.binding()));
        assertThat(liveLease.startedAt()).isBefore(attempt.expiresAt());
        assertThat(liveLease.leaseUntil()).isAfter(attempt.expiresAt());
        awaitDatabaseTime(attempt.expiresAt());
        assertThat(background(f, () -> runtime.queryForObject("SELECT clock_timestamp()<?", Boolean.class,
                java.sql.Timestamp.from(liveLease.leaseUntil())))).isTrue();
        assertThatThrownBy(() -> background(f, () -> runtime.update("""
                UPDATE fee_gateway_attempt_work SET create_dispatched_at=?,create_dispatched_by_lease_token=?,updated_at=?,row_version=row_version+1 WHERE attempt_id=?
                """,java.sql.Timestamp.from(liveLease.startedAt()),liveLease.token(),java.sql.Timestamp.from(liveLease.startedAt()),attempt.id())))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("current unexpired one-use lease");
        assertThat(background(f, () -> longerLeaseQueue.markCreateDispatched(liveLease))).isFalse();
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?",java.sql.Timestamp.class,attempt.id())).isNull();
        assertNoFinancialPosting(f);
    }

    @Test void readRecoveryRequiresTrustedWorkerOnlyAndActualRuntimePrivileges() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        var enabled = recoveryDispatch(f.binding(), false, true, properties.publicUrl());
        var creationOnly = recoveryDispatch(f.binding(), true, false, properties.publicUrl());
        var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
        ProductionDatabaseSecurity.verify(runtime);
        assertThatThrownBy(() -> background(f, () -> creationOnly.loadForReadRecovery(RECOVERY_ROUTE, lease)))
                .isInstanceOf(IllegalStateException.class);
        var snapshot = background(f, () -> enabled.loadForReadRecovery(RECOVERY_ROUTE, lease));
        assertThat(snapshot.attemptId()).isEqualTo(attempt);
        assertThat(snapshot.checkoutReference()).isEqualTo(attempt);
        assertThat(snapshot.binding()).isEqualTo(f.binding());
        assertThat(snapshot.amount()).isEqualByComparingTo("500.00");
        assertThat(snapshot.currencyCode()).isEqualTo("INR");
        assertThat(snapshot.attemptStatus()).isEqualTo("RESERVED");
        assertThat(snapshot.returnUri().toASCIIString()).isEqualTo(properties.publicUrl() + "/family/payments/" + attempt);
        assertThat(snapshot.createPreviouslyDispatched()).isFalse();
        assertThat(snapshot.toString()).doesNotContain(attempt.toString(), f.binding().merchantReference(), properties.publicUrl());
        assertNoFinancialPosting(f);
    }

    @Test void readRecoveryDeniesHumanCrossTenantAndSiblingSchoolScopes() {
        Fixture f = fixture(), sibling = fixture(f.org()), other = fixture(); UUID attempt = prepareWork(f);
        var dispatch = recoveryDispatch(f.binding(), false, true, properties.publicUrl());
        var siblingDispatch = recoveryDispatch(sibling.binding(), false, true, properties.publicUrl());
        var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
        assertThatThrownBy(() -> as(f, f.parent().userId(), () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> background(other, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)))
                .isInstanceOf(IllegalStateException.class);
        var siblingClaim = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, attempt, sibling.binding(),
                lease.token(), lease.startedAt(), lease.leaseUntil(), lease.attemptNumber(), false);
        assertThatThrownBy(() -> background(sibling, () -> siblingDispatch.loadForReadRecovery(RECOVERY_ROUTE, siblingClaim)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(background(other, () -> runtime.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE attempt_id=?",
                Integer.class, attempt))).isZero();
        try (var ignored = TenantRequestContext.open(null, f.org())) {
            assertThatThrownBy(() -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)).isInstanceOf(IllegalStateException.class);
            var readOnly = new TransactionTemplate(transactions.getTransactionManager()); readOnly.setReadOnly(true);
            assertThatThrownBy(() -> readOnly.execute(status -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertNoFinancialPosting(f);
    }

    @Test void readRecoveryRejectsStaleTokenCounterAndExpiredLeaseAfterActualLockWait() throws Exception {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        var dispatch = recoveryDispatch(f.binding(), false, true, properties.publicUrl());
        var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
        for (var changed : List.of(
                new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, attempt, f.binding(), UUID.randomUUID(),
                        lease.startedAt(), lease.leaseUntil(), lease.attemptNumber(), false),
                new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, attempt, f.binding(), lease.token(),
                        lease.startedAt().plusNanos(1000), lease.leaseUntil(), lease.attemptNumber(), false),
                new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, attempt, f.binding(), lease.token(),
                        lease.startedAt(), lease.leaseUntil().plusNanos(1000), lease.attemptNumber(), false),
                new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, attempt, f.binding(), lease.token(),
                        lease.startedAt(), lease.leaseUntil(), lease.attemptNumber() + 1, false))) {
            assertThatThrownBy(() -> background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, changed)))
                    .isInstanceOf(IllegalStateException.class);
        }
        // Hold the real work row while the non-owner SELECT waits. Merely
        // evaluating lease time before a lock wait is not sufficient evidence.
        try (var worker = Executors.newSingleThreadExecutor(); var locked = owner.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            try (var statement = locked.prepareStatement("SELECT attempt_id FROM fee_gateway_attempt_work WHERE attempt_id=? FOR UPDATE")) {
                statement.setObject(1, attempt); try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            var recovery = worker.submit(() -> background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)));
            awaitBlockedRecoverySelect();
            awaitDatabaseTime(lease.leaseUntil());
            locked.commit();
            assertThatThrownBy(() -> recovery.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("LEASED");
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt)).isNull();
        assertNoFinancialPosting(f);
    }

    @Test void readRecoveryRejectsInactiveOrChangedFrozenProviderWithoutFallback() {
        Fixture inactive = fixture(); UUID inactiveAttempt = prepareWork(inactive);
        var inactiveDispatch = recoveryDispatch(inactive.binding(), false, true, properties.publicUrl());
        var inactiveLease = background(inactive, () -> queue.claimNextAttempt(inactive.binding()));
        owner.update("UPDATE payment_provider_configs SET status='INACTIVE',updated_at=now(),row_version=row_version+1 WHERE id=?", inactive.provider());
        assertThatThrownBy(() -> background(inactive, () -> inactiveDispatch.loadForReadRecovery(RECOVERY_ROUTE, inactiveLease)))
                .isInstanceOf(IllegalStateException.class);
        Fixture rotated = fixture(); UUID rotatedAttempt = prepareWork(rotated);
        var originalDispatch = recoveryDispatch(rotated.binding(), false, true, properties.publicUrl());
        var rotatedBinding = new GatewayWorkQueue.ProviderBinding(rotated.org(), rotated.school(), rotated.provider(), "CASHFREE", "TEST",
                rotated.binding().merchantReference(), "synthetic:rotated");
        var changedDispatch = recoveryDispatch(rotatedBinding, false, true, properties.publicUrl());
        var lease = background(rotated, () -> queue.claimNextAttempt(rotated.binding()));
        owner.update("UPDATE payment_provider_configs SET credentials_secret_reference='synthetic:rotated',updated_at=now(),row_version=row_version+1 WHERE id=?", rotated.provider());
        assertThatThrownBy(() -> background(rotated, () -> originalDispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)))
                .isInstanceOf(IllegalStateException.class);
        var changedClaim = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, rotatedAttempt, rotatedBinding,
                lease.token(), lease.startedAt(), lease.leaseUntil(), lease.attemptNumber(), false);
        assertThatThrownBy(() -> background(rotated, () -> changedDispatch.loadForReadRecovery(RECOVERY_ROUTE, changedClaim)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> owner.update("UPDATE payment_provider_configs SET merchant_account_reference='synthetic_changed_merchant' WHERE id=?", rotated.provider()))
                .isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT credentials_secret_reference FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, rotatedAttempt))
                .isEqualTo(rotated.binding().credentialsReference());
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, inactiveAttempt)).isEqualTo("LEASED");
        assertNoFinancialPosting(inactive); assertNoFinancialPosting(rotated);
    }

    @Test void readRecoveryPreservesImmutableReturnUriAndDeniesChangedOrigins() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        String originalReturn = owner.queryForObject("SELECT return_uri FROM fee_gateway_attempts WHERE id=?", String.class, attempt);
        var changedOrigin = recoveryDispatch(f.binding(), false, true, "https://changed-synthetic.example.test");
        var original = recoveryDispatch(f.binding(), false, true, properties.publicUrl());
        var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
        assertThatThrownBy(() -> background(f, () -> changedOrigin.loadForReadRecovery(RECOVERY_ROUTE, lease)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> owner.update("UPDATE fee_gateway_attempts SET return_uri=?,row_version=row_version+1,updated_at=now() WHERE id=?",
                "https://changed-synthetic.example.test/family/payments/" + attempt, attempt)).isInstanceOf(DataAccessException.class);
        assertThat(background(f, () -> original.loadForReadRecovery(RECOVERY_ROUTE, lease)).returnUri().toASCIIString()).isEqualTo(originalReturn);
        assertThat(owner.queryForObject("SELECT return_uri FROM fee_gateway_attempts WHERE id=?", String.class, attempt)).isEqualTo(originalReturn);
        assertNoFinancialPosting(f);
    }

    @Test void readRecoveryPreservesOneUseMarkerWithoutFinancialPosting() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        var dispatch = recoveryDispatch(f.binding(), false, true, properties.publicUrl());
        var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
        assertThat(background(f, () -> queue.markCreateDispatched(lease))).isTrue();
        var before = owner.queryForMap("""
                SELECT create_dispatched_at,create_dispatched_by_lease_token,status,attempt_count,
                  lease_token,lease_started_at,lease_until,row_version FROM fee_gateway_attempt_work WHERE attempt_id=?
                """, attempt);
        var snapshot = background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease));
        assertThat(snapshot.createPreviouslyDispatched()).isTrue();
        assertThat(snapshot.attemptStatus()).isEqualTo("RESERVED");
        assertThat(owner.queryForMap("""
                SELECT create_dispatched_at,create_dispatched_by_lease_token,status,attempt_count,
                  lease_token,lease_started_at,lease_until,row_version FROM fee_gateway_attempt_work WHERE attempt_id=?
                """, attempt)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE school_id=?", Integer.class, f.school())).isZero();
        assertNoFinancialPosting(f);
    }

    @Test void newCreateUsesIndependentApprovedCashAndAccrualBooksWithoutPostingFinancialEntries() {
        for (String basis : List.of("CASH", "ACCRUAL")) {
            Fixture f = fixture(); var book = configureGatewayBook(f, basis, "OTHER", true);
            UUID attempt = prepareWork(f); var dispatch = recoveryDispatch(f.binding(), true, true, properties.publicUrl());
            var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
            var admitted = background(f, () -> dispatch.admitCreate(RECOVERY_ROUTE, lease));
            assertThat(admitted.attempt().checkoutReference()).isEqualTo(attempt);
            assertThat(admitted.attempt().amount()).isEqualByComparingTo("500.00");
            assertThat(admitted.book().configurationId()).isEqualTo(book.configuration());
            assertThat(admitted.book().financialYearId()).isEqualTo(book.year());
            assertThat(admitted.book().accountingPeriodId()).isEqualTo(book.period());
            assertThat(owner.queryForObject("SELECT create_dispatched_by_lease_token FROM fee_gateway_attempt_work WHERE attempt_id=?", UUID.class, attempt))
                    .isEqualTo(lease.token());
            assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE school_id=?", Integer.class, f.school())).isZero();
            assertNoFinancialPosting(f);
        }
    }

    @Test void newCreateDeniedReadinessRollsBackMarkerAndKeepsTheOriginalPendingReservation() {
        for (String failure : List.of("MISSING", "SUBMITTED", "BANK", "INACTIVE_INCOME", "NON_DIRECT_CLEARING", "LOCKED_PERIOD")) {
            Fixture f = fixture(); GatewayBook book = "MISSING".equals(failure) ? null
                    : configureGatewayBook(f, "CASH", "BANK".equals(failure) ? "BANK" : "OTHER", !"SUBMITTED".equals(failure));
            if ("INACTIVE_INCOME".equals(failure)) owner.update("UPDATE accounting_accounts SET status='INACTIVE',row_version=row_version+1,updated_at=now() WHERE id=?", book.income());
            if ("NON_DIRECT_CLEARING".equals(failure)) owner.update("UPDATE accounting_accounts SET allow_direct_posting=false,row_version=row_version+1,updated_at=now() WHERE id=?", book.clearing());
            if ("LOCKED_PERIOD".equals(failure)) actor(f, book.reviewer(), () -> accountingService().transitionPeriod(book.reviewer(), book.period(),
                    new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(f.school(), "LOCK", 0, "Synthetic locked period")));
            UUID attempt = prepareWork(f); var dispatch = recoveryDispatch(f.binding(), true, true, properties.publicUrl());
            var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
            assertThatThrownBy(() -> background(f, () -> dispatch.admitCreate(RECOVERY_ROUTE, lease))).isInstanceOf(IllegalStateException.class);
            assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt)).isNull();
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?", String.class, attempt)).isEqualTo("ACTIVE");
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempts WHERE id=?", String.class, attempt)).isEqualTo("RESERVED");
            assertThat(background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)).attemptId()).isEqualTo(attempt);
            assertNoFinancialPosting(f);
        }
    }

    @Test void newCreateRequiresLiveFamilyButProviderReadRecoverySurvivesRevocation() {
        for (String revoked : List.of("MEMBERSHIP", "USER", "GUARDIAN", "RELATIONSHIP", "CAPABILITY", "ACCOUNT")) {
            Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
            UUID attempt = prepareWork(f); var dispatch = recoveryDispatch(f.binding(), true, true, properties.publicUrl());
            var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
            UUID member = f.parent().membershipIds().iterator().next();
            switch (revoked) {
                case "MEMBERSHIP" -> owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=now() WHERE id=?", member);
                case "USER" -> owner.update("UPDATE user_accounts SET status='SUSPENDED' WHERE id=?", f.parent().userId());
                case "GUARDIAN" -> owner.update("UPDATE guardians SET status='INACTIVE',updated_at=now() WHERE user_account_id=?", f.parent().userId());
                case "RELATIONSHIP" -> owner.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED',updated_at=now() WHERE learner_id=?", f.learner());
                case "CAPABILITY" -> owner.update("DELETE FROM membership_roles WHERE membership_id=?", member);
                case "ACCOUNT" -> {
                    var admin = owner.queryForMap("SELECT id,user_id FROM memberships WHERE school_id=? AND user_id<>? LIMIT 1", f.school(), f.parent().userId());
                    owner.update("UPDATE learner_fee_accounts SET status='CLOSED',closed_by_membership_id=?,closed_at=now(),close_reason='Synthetic closed account',row_version=row_version+1 WHERE id=(SELECT learner_fee_account_id FROM learner_fee_charges WHERE id=?)",
                            admin.get("id"), f.charge());
                }
                default -> throw new IllegalArgumentException();
            }
            assertThatThrownBy(() -> background(f, () -> dispatch.admitCreate(RECOVERY_ROUTE, lease))).isInstanceOf(IllegalStateException.class);
            assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt)).isNull();
            assertThat(background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)).attemptId()).isEqualTo(attempt);
            assertNoFinancialPosting(f);
        }
    }

    @Test void newCreateCannotRepeatItsCommittedMarkerOrReuseAnotherSchoolBook() {
        Fixture f = fixture(), sibling = fixture(f.org()); var book = configureGatewayBook(f, "CASH", "OTHER", true);
        configureGatewayBook(sibling, "CASH", "OTHER", true);
        UUID attempt = prepareWork(f); var dispatch = recoveryDispatch(f.binding(), true, true, properties.publicUrl());
        var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
        var first = background(f, () -> dispatch.admitCreate(RECOVERY_ROUTE, lease));
        var marker = owner.queryForMap("SELECT create_dispatched_at,create_dispatched_by_lease_token,row_version FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt);
        assertThatThrownBy(() -> background(f, () -> dispatch.admitCreate(RECOVERY_ROUTE, lease))).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForMap("SELECT create_dispatched_at,create_dispatched_by_lease_token,row_version FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt)).isEqualTo(marker);
        assertThat(background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)).createPreviouslyDispatched()).isTrue();
        assertThat(first.book().configurationId()).isEqualTo(book.configuration());
        assertThatThrownBy(() -> background(sibling, () -> accountingService().validateGatewayCheckoutBook(sibling.org(), sibling.school(),
                LocalDate.now(ZoneId.of("Asia/Kolkata")), book.year(), List.of()))).isInstanceOf(IllegalStateException.class);
        assertNoFinancialPosting(f);
    }

    @Test void newCreateRejectsActualLeaseExpiryWhileWaitingOnAccountingLock() throws Exception {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        UUID attempt = prepareWork(f); var dispatch = recoveryDispatch(f.binding(), true, true, properties.publicUrl());
        var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
        try (var worker = Executors.newSingleThreadExecutor(); var locked = owner.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            try (var statement = locked.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, "accounting:school:" + f.school()); statement.execute();
            }
            var create = worker.submit(() -> background(f, () -> dispatch.admitCreate(RECOVERY_ROUTE, lease)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Boolean.TRUE.equals(control.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=? AND usename=?
                      AND wait_event_type='Lock' AND state='active' AND position('pg_advisory_xact_lock' IN query)>0)
                    """, Boolean.class, database, runtimeLogin))) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Actual non-owner CREATE did not reach the accounting lock");
                Thread.sleep(10);
            }
            awaitDatabaseTime(lease.leaseUntil()); locked.commit();
            assertThatThrownBy(() -> create.get(15, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt)).isNull();
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("LEASED");
        assertNoFinancialPosting(f);
    }

    @Test void newCreateRejectsAnApprovedClosedFeeDayWithoutReopeningItOrBlockingReadRecovery() {
        Fixture f = fixture(); var book = configureGatewayBook(f, "CASH", "OTHER", true);
        UUID attempt = prepareWork(f); var dispatch = recoveryDispatch(f.binding(), true, true, properties.publicUrl());
        var finance = new FinanceService(new NamedParameterJdbcTemplate(runtime),
                new SecurityAuditService(runtime, JsonMapper.builder().build(), clock), JsonMapper.builder().build(), clock);
        var declarations = List.of("CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER").stream()
                .map(method -> new FinanceController.TenderDeclaration(method, BigDecimal.ZERO)).toList();
        var close = actor(f, book.maker(), () -> finance.proposeDayClose(book.maker(), new FinanceController.DayCloseRequest(
                f.school(), LocalDate.now(ZoneId.of("Asia/Kolkata")), declarations, "Synthetic close before gateway dispatch", null)));
        actor(f, book.reviewer(), () -> finance.decideDayClose(book.reviewer(), close.id(), new FinanceController.DayCloseDecisionRequest(
                f.school(), "APPROVED", "Synthetic independent day close", 0)));
        var closedEvidence = owner.queryForMap("SELECT status,decided_at,row_version FROM fee_day_closes WHERE id=?", close.id());
        var lease = background(f, () -> queue.claimNextAttempt(f.binding()));
        assertThatThrownBy(() -> background(f, () -> dispatch.admitCreate(RECOVERY_ROUTE, lease))).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForMap("SELECT status,decided_at,row_version FROM fee_day_closes WHERE id=?", close.id())).isEqualTo(closedEvidence);
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt)).isNull();
        assertThat(background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, lease)).attemptId()).isEqualTo(attempt);
        assertNoFinancialPosting(f);
    }

    @Test void newCreateRechecksOrganisationSuspensionDuringActualAccountingLockWait() throws Exception {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        UUID attempt = prepareWork(f); var dispatch = recoveryDispatch(f.binding(), true, true, properties.publicUrl());
        var longerQueue = new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime), clock, Duration.ofSeconds(15));
        var lease = background(f, () -> longerQueue.claimNextAttempt(f.binding()));
        try (var worker = Executors.newSingleThreadExecutor(); var locked = owner.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            try (var statement = locked.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, "accounting:school:" + f.school()); statement.execute();
            }
            var create = worker.submit(() -> background(f, () -> dispatch.admitCreate(RECOVERY_ROUTE, lease)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Boolean.TRUE.equals(control.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=? AND usename=?
                      AND wait_event_type='Lock' AND state='active' AND position('pg_advisory_xact_lock' IN query)>0)
                    """, Boolean.class, database, runtimeLogin))) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Actual non-owner CREATE did not reach the accounting lock before suspension");
                Thread.sleep(10);
            }
            // This unique fixture organisation is SELECT-only to the runtime.
            // Simulate an owner suspension, without mutating global capabilities
            // or other tests, after the worker's initial active-scope read.
            owner.update("UPDATE organisations SET status='SUSPENDED',updated_at=now() WHERE id=?", f.org());
            assertThat(owner.queryForObject("SELECT clock_timestamp()<?", Boolean.class, java.sql.Timestamp.from(lease.leaseUntil()))).isTrue();
            locked.commit();
            assertThatThrownBy(() -> create.get(15, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt)).isNull();
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?", String.class, attempt)).isEqualTo("ACTIVE");
        assertThat(owner.queryForObject("SELECT status FROM organisations WHERE id=?", String.class, f.org())).isEqualTo("SUSPENDED");
        assertNoFinancialPosting(f);
    }

    @Test void observationFinalizerPostsOneDispatchedExactSystemReceiptWithoutInventingHumanAuthority() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        var finalizer = observationFinalizer(f.binding(), work);
        Instant paid = databaseInstant();
        var evidence = exactFinalizerDiscovery(f, attempt, paid);

        assertThat(finalizer.finalizeObservation(RECOVERY_ROUTE, claim, evidence)).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        ProductionDatabaseSecurity.verify(runtime);
        assertOneFinalizedReceipt(f, attempt);
        UUID paymentId = owner.queryForObject("SELECT fee_payment_id FROM fee_gateway_attempts WHERE id=?", UUID.class, attempt);
        assertThat(owner.queryForObject("SELECT actor_kind FROM fee_payments WHERE id=?", String.class, paymentId)).isEqualTo("SYSTEM");
        assertThat(owner.queryForObject("SELECT received_by_membership_id FROM fee_payments WHERE id=?", UUID.class, paymentId)).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE subject_id=? AND action='finance.gateway.payment.posted' AND actor_user_id IS NULL AND actor_membership_id IS NULL",
                Integer.class, paymentId.toString())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT provider_paid_at FROM fee_gateway_payment_observations WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant()).isEqualTo(paid);
        assertThat(owner.queryForObject("SELECT paid_at FROM fee_gateway_captures WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant()).isEqualTo(paid);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("COMPLETE");
    }

    @Test void observationFinalizerRetainsPartialFactsAndBindsReceiptOnlyToLaterCompleteDiscovery() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        // Test scheduling only: the real queue accepts a bounded one-second retry.
        // Production backoff is unchanged and covered by its separate unit tests.
        var work = finalizerQueue(15, true); UUID attempt = prepareWork(f);
        var first = dispatchedFinalizerClaim(f, attempt, work);
        var finalizer = observationFinalizer(f.binding(), work);
        Instant paid = databaseInstant();
        var payment = finalizerPayment("synthetic_finalized_payment", paid);
        var partial = finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.PAID, "500.00",
                List.of("synthetic_order", "synthetic_other_order"),
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of(payment)),
                        GatewayDiscoveryEvidence.OrderRead.unknown("synthetic_other_order")), databaseInstant());

        assertThat(finalizer.finalizeObservation(RECOVERY_ROUTE, first, partial)).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        UUID incomplete = owner.queryForObject("SELECT id FROM fee_gateway_checkout_observations WHERE attempt_id=?", UUID.class, attempt);
        assertThat(owner.queryForObject("SELECT discovery_complete FROM fee_gateway_checkout_observations WHERE id=?", Boolean.class, incomplete)).isFalse();
        assertThat(owner.queryForObject("SELECT successful_payment_count FROM fee_gateway_checkout_observations WHERE id=?", Integer.class, incomplete)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isZero();
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);

        awaitDatabaseTime(owner.queryForObject("SELECT next_attempt_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant());
        var second = background(f, () -> work.claimNextAttempt(f.binding()));
        assertThat(second.attemptNumber()).isEqualTo(first.attemptNumber() + 1);
        assertThat(second.createDispatched()).isTrue();
        var complete = finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.PAID, "500.00",
                List.of("synthetic_order", "synthetic_other_order"),
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of(payment)),
                        GatewayDiscoveryEvidence.OrderRead.complete("synthetic_other_order", List.of())), databaseInstant());
        assertThat(finalizer.finalizeObservation(RECOVERY_ROUTE, second, complete)).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        assertOneFinalizedReceipt(f, attempt);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(2);
        UUID proof = owner.queryForObject("SELECT checkout_observation_id FROM fee_gateway_captures WHERE attempt_id=?", UUID.class, attempt);
        assertThat(proof).isNotEqualTo(incomplete);
        assertThat(owner.queryForObject("SELECT discovery_complete FROM fee_gateway_checkout_observations WHERE id=?", Boolean.class, proof)).isTrue();
        assertThat(owner.queryForObject("SELECT discovery_complete FROM fee_gateway_checkout_observations WHERE id=?", Boolean.class, incomplete)).isFalse();
    }

    @Test void observationFinalizerWithoutOneUseDispatchRetainsFactsForReviewButCannotIssueCaptureOrReceipt() {
        Fixture f = fixture(); UUID attempt = prepareWork(f);
        var work = finalizerQueue(15, false);
        var claim = background(f, () -> work.claimNextAttempt(f.binding()));

        assertThat(observationFinalizer(f.binding(), work).finalizeObservation(RECOVERY_ROUTE, claim,
                exactFinalizerDiscovery(f, attempt, databaseInstant()))).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isZero();
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("REVIEW_REQUIRED");
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt)).isNull();
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
    }

    @Test void observationFinalizerRejectsEveryForgedClaimFieldAndActuallyExpiredLeaseWithoutPosting() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        var finalizer = observationFinalizer(f.binding(), work);
        var evidence = exactFinalizerDiscovery(f, attempt, databaseInstant());
        var originalWork = owner.queryForMap("SELECT * FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt);
        for (var forged : List.of(
                new GatewayWorkQueue.Claim(claim.kind(), UUID.randomUUID(), f.binding(), claim.token(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), true),
                new GatewayWorkQueue.Claim(claim.kind(), attempt, f.binding(), UUID.randomUUID(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), true),
                new GatewayWorkQueue.Claim(claim.kind(), attempt, f.binding(), claim.token(), claim.startedAt().plusNanos(1000), claim.leaseUntil(), claim.attemptNumber(), true),
                new GatewayWorkQueue.Claim(claim.kind(), attempt, f.binding(), claim.token(), claim.startedAt(), claim.leaseUntil().plusNanos(1000), claim.attemptNumber(), true),
                new GatewayWorkQueue.Claim(claim.kind(), attempt, f.binding(), claim.token(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber() + 1, true),
                new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK, attempt, f.binding(), claim.token(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), false))) {
            assertThatThrownBy(() -> finalizer.finalizeObservation(RECOVERY_ROUTE, forged, evidence)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(owner.queryForMap("SELECT * FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt)).isEqualTo(originalWork);
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);

        Fixture expired = fixture(); configureGatewayBook(expired, "CASH", "OTHER", true);
        var shortWork = finalizerQueue(3, false); UUID expiredAttempt = prepareWork(expired);
        var expiredClaim = dispatchedFinalizerClaim(expired, expiredAttempt, shortWork);
        var expiredEvidence = exactFinalizerDiscovery(expired, expiredAttempt, databaseInstant());
        awaitDatabaseTime(expiredClaim.leaseUntil());
        assertThatThrownBy(() -> observationFinalizer(expired.binding(), shortWork).finalizeObservation(RECOVERY_ROUTE, expiredClaim, expiredEvidence))
                .isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, expiredAttempt)).isEqualTo("LEASED");
        assertNoFinancialPosting(expired); assertOriginalReservation(expired, expiredAttempt);
    }

    @Test void observationFinalizerRechecksRealLeaseAfterFinanceLockWaitAndKeepsIndependentFacts() throws Exception {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        try (var worker = Executors.newSingleThreadExecutor(); var locked = owner.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            var factsCommittedAndFenceHeld = new CountDownLatch(1);
            var registered = new java.util.concurrent.atomic.AtomicBoolean();
            var work = new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime), clock, Duration.ofSeconds(3)) {
                @Override public UUID retainPaymentObservation(ProviderBinding binding, UUID attemptId,
                        PaymentProviderAdapter.PaymentObservation payment) {
                    UUID retained = super.retainPaymentObservation(binding, attemptId, payment);
                    if (registered.compareAndSet(false, true)) {
                        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override public void afterCommit() {
                                        // Acquire a real independent finance lock only AFTER
                                        // the whole facts stage has actually committed.
                                        // Holding it before entry would test the wrong phase.
                                        try (var statement = locked.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                                            statement.setString(1, f.school() + ":finance"); statement.execute();
                                            factsCommittedAndFenceHeld.countDown();
                                        } catch (java.sql.SQLException failure) {
                                            throw new IllegalStateException("Synthetic committed-facts fence acquisition failed");
                                        }
                                    }
                                });
                    }
                    return retained;
                }
            };
            UUID attempt = prepareWork(f);
            var claim = dispatchedFinalizerClaim(f, attempt, work);
            var finalizer = observationFinalizer(f.binding(), work);
            var evidence = exactFinalizerDiscovery(f, attempt, databaseInstant());
            var completion = worker.submit(() -> finalizer.finalizeObservation(RECOVERY_ROUTE, claim, evidence));
            assertThat(factsCommittedAndFenceHeld.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlockedFinalizerFinanceLock();
            // The actual non-owner financial phase is waiting, while its earlier
            // independently committed provider facts are already visible elsewhere.
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
            awaitDatabaseTime(claim.leaseUntil()); locked.commit();
            assertThatThrownBy(() -> completion.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isZero();
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("LEASED");
            assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
        }
    }

    @Test void observationFinalizerConcurrentSameEvidenceRetriesCannotDuplicateCaptureReceiptOrConsumeTwice() throws Exception {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        var finalizer = observationFinalizer(f.binding(), work);
        var evidence = exactFinalizerDiscovery(f, attempt, databaseInstant());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return tryFinalize(finalizer, claim, evidence); });
            var second = workers.submit(() -> { start.await(); return tryFinalize(finalizer, claim, evidence); });
            start.countDown();
            assertThat((first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0)).isGreaterThanOrEqualTo(1);
        }
        assertOneFinalizedReceipt(f, attempt);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=(SELECT id FROM fee_gateway_captures WHERE attempt_id=?) AND resolution_type='POSTED'", Integer.class, attempt)).isEqualTo(1);
    }

    @Test void observationFinalizerApprovedClosedDayPreservesExactHoldAndNeverReopensOrAutomaticallyPosts() {
        Fixture f = fixture(); var book = configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        var finance = finalizerFinance(); Instant paid = databaseInstant().plusNanos(500);
        awaitDatabaseTime(paid.plusNanos(500));
        var evidence = exactFinalizerDiscovery(f, attempt, paid);
        LocalDate original = LocalDate.ofInstant(paid, ZoneId.of("Asia/Kolkata"));
        var declarations = List.of("CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER").stream()
                .map(method -> new FinanceController.TenderDeclaration(method, BigDecimal.ZERO)).toList();
        var close = actor(f, book.maker(), () -> finance.proposeDayClose(book.maker(), new FinanceController.DayCloseRequest(
                f.school(), original, declarations, "Synthetic independent close before delayed observation", null)));
        actor(f, book.reviewer(), () -> finance.decideDayClose(book.reviewer(), close.id(), new FinanceController.DayCloseDecisionRequest(
                f.school(), "APPROVED", "Synthetic approved closed-day preservation", 0)));
        String immutableClose = owner.queryForObject("SELECT row_to_json(close_record)::text FROM fee_day_closes close_record WHERE id=?", String.class, close.id());

        assertThat(observationFinalizer(f.binding(), work).finalizeObservation(RECOVERY_ROUTE, claim, evidence)).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        UUID capture = owner.queryForObject("SELECT id FROM fee_gateway_captures WHERE attempt_id=?", UUID.class, attempt);
        assertThat(owner.queryForObject("SELECT classification FROM fee_gateway_captures WHERE id=?", String.class, capture)).isEqualTo("REVIEW_REQUIRED");
        assertThat(owner.queryForObject("SELECT closed_day_close_id FROM fee_gateway_captures WHERE id=?", UUID.class, capture)).isEqualTo(close.id());
        assertThat(owner.queryForObject("SELECT paid_at FROM fee_gateway_captures WHERE id=?", java.sql.Timestamp.class, capture).toInstant()).isEqualTo(GatewayExactInstant.floor(paid));
        assertThat(owner.queryForObject("SELECT paid_at_exact FROM fee_gateway_captures WHERE id=?", String.class, capture)).isEqualTo(paid.toString());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=? AND resolution_type='HOLD' AND closed_day_close_id=? AND actor_kind='SYSTEM'", Integer.class, capture, close.id())).isEqualTo(1);
        assertThatThrownBy(() -> actor(f, f.parent(), () -> finance.postReviewedGatewayCapture(f.parent(), capture,
                new FinanceController.GatewayCapturePostingRequest(f.school(), original, "Synthetic denied guardian financial review"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        for (LocalDate denied : List.of(original, original.plusDays(1))) {
            assertThatThrownBy(() -> actor(f, book.reviewer(), () -> finance.postReviewedGatewayCapture(book.reviewer(), capture,
                    new FinanceController.GatewayCapturePostingRequest(f.school(), denied, "Synthetic denied closed or future financial date"))))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        // Later-open-date human success is covered by the existing foundation
        // tests; this live-clock fixture must not invent tomorrow's approval.
        assertThat(owner.queryForObject("SELECT row_to_json(close_record)::text FROM fee_day_closes close_record WHERE id=?", String.class, close.id())).isEqualTo(immutableClose);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempts WHERE id=?", String.class, attempt)).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        assertNoFinancialPosting(f);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?", String.class, attempt)).isEqualTo("ACTIVE");
    }

    @Test void observationFinalizerLateConfirmationSurvivesParentRevocationAndActualCheckoutExpiry() {
        Fixture f = fixture();
        // Choose the unique synthetic school's zone BEFORE freezing its intent.
        // Avoid straddling a real school-day boundary with the legitimately old
        // intent; do not backdate the one-use marker or change database time.
        int localHour = databaseInstant().atZone(ZoneId.of("Asia/Kolkata")).getHour();
        if (localHour == 0 || localHour == 23) {
            owner.update("UPDATE schools SET time_zone='UTC',updated_at=now() WHERE id=?", f.school());
        }
        configureGatewayBook(f, "CASH", "OTHER", true);
        var named = new NamedParameterJdbcTemplate(runtime);
        // A legitimately older synthetic intent expires shortly on the actual
        // database clock; no work lease, expiry row or database time is changed.
        var oldClock = Clock.offset(clock, Duration.ofMinutes(30).negated().plusSeconds(5));
        var older = new GatewayReservationService(named, new FamilyFinanceService(named),
                new SecurityAuditService(runtime, JsonMapper.builder().build(), clock), new SensitiveValueCipher(properties, new SecureRandom()), properties, oldClock);
        var work = finalizerQueue(15, false);
        var planned = as(f, f.parent().userId(), () -> {
            var created = older.prepare(f.parent(), f.learner(), request(f, UUID.randomUUID()));
            work.enqueueAttempt(f.binding(), created.id()); return created;
        });
        var claim = dispatchedFinalizerClaim(f, planned.id(), work);
        owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=now(),row_version=row_version+1 WHERE id=?", f.parent().membershipIds().iterator().next());
        owner.update("UPDATE user_accounts SET status='SUSPENDED' WHERE id=?", f.parent().userId());
        awaitDatabaseTime(planned.expiresAt());
        Instant paid = databaseInstant();
        assertThat(paid).isAfter(planned.expiresAt());
        assertThat(observationFinalizer(f.binding(), work).finalizeObservation(RECOVERY_ROUTE, claim,
                exactFinalizerDiscovery(f, planned.id(), paid))).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        assertOneFinalizedReceipt(f, planned.id());
        assertThat(owner.queryForObject("SELECT paid_at FROM fee_gateway_captures WHERE attempt_id=?", java.sql.Timestamp.class, planned.id()).toInstant()).isEqualTo(paid);
        assertThat(owner.queryForObject("SELECT status FROM user_accounts WHERE id=?", String.class, f.parent().userId())).isEqualTo("SUSPENDED");
    }

    @Test void observationFinalizerClosedFeeAccountKeepsExactMoneyForReviewWithoutReceiptOrReservationLoss() {
        Fixture f = fixture(); var book = configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        owner.update("""
                UPDATE learner_fee_accounts SET status='CLOSED',closed_by_membership_id=?,closed_at=now(),
                  close_reason='Synthetic local fee-account closure',row_version=row_version+1
                WHERE id=(SELECT learner_fee_account_id FROM fee_gateway_attempts WHERE id=?)
                """, book.maker().membershipIds().iterator().next(), attempt);

        assertThat(observationFinalizer(f.binding(), work).finalizeObservation(RECOVERY_ROUTE, claim,
                exactFinalizerDiscovery(f, attempt, databaseInstant()))).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=(SELECT id FROM fee_gateway_captures WHERE attempt_id=?) AND resolution_type='HOLD'", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?", String.class, attempt)).isEqualTo("ACTIVE");
        assertThat(owner.queryForObject("SELECT status FROM learner_fee_accounts WHERE id=(SELECT learner_fee_account_id FROM fee_gateway_attempts WHERE id=?)", String.class, attempt)).isEqualTo("CLOSED");
        assertNoFinancialPosting(f);
    }

    @Test void observationFinalizerRejectsAmbientScopeForeignBindingAndCredentialRotationBeforeFinancialWrites() {
        Fixture f = fixture(), sibling = fixture(f.org()), foreign = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        var finalizer = observationFinalizer(f.binding(), work);
        var evidence = exactFinalizerDiscovery(f, attempt, databaseInstant());
        try (var ignored = TenantRequestContext.open(f.parent().userId(), f.org())) {
            assertThatThrownBy(() -> finalizer.finalizeObservation(RECOVERY_ROUTE, claim, evidence)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> background(f, () -> finalizer.finalizeObservation(RECOVERY_ROUTE, claim, evidence))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> finalizer.finalizeObservation("synthetic-untrusted-route", claim, evidence)).isInstanceOf(IllegalStateException.class);
        for (Fixture other : List.of(sibling, foreign)) {
            var wrongBinding = new GatewayWorkQueue.Claim(claim.kind(), attempt, other.binding(), claim.token(),
                    claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), true);
            assertThatThrownBy(() -> finalizer.finalizeObservation(RECOVERY_ROUTE, wrongBinding, evidence)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> finalizer.finalizeObservation(RECOVERY_ROUTE, claim,
                    new GatewayDiscoveryEvidence.Discovery(other.binding(), evidence.checkout(), evidence.orderReads(), evidence.observedAt())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE school_id=?", Integer.class, other.school())).isZero();
            assertNoFinancialPosting(other);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isZero();
        owner.update("UPDATE payment_provider_configs SET credentials_secret_reference='synthetic:rotated',updated_at=now(),row_version=row_version+1 WHERE id=?", f.provider());
        assertThatThrownBy(() -> finalizer.finalizeObservation(RECOVERY_ROUTE, claim, evidence)).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isZero();
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
    }

    @Test void observationFinalizerPriorCapturedSuccessCannotDisappearBehindTerminalZeroOrIncompleteCurrentDiscovery() {
        for (boolean terminal : List.of(true, false)) {
            Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
            var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
            var claim = dispatchedFinalizerClaim(f, attempt, work);
            Instant paid = databaseInstant();
            UUID prior = background(f, () -> work.retainPaymentObservation(f.binding(), attempt,
                    finalizerPayment("synthetic_prior_captured_success", paid)));
            var evidence = terminal
                    ? finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.EXPIRED, "0",
                            List.of("synthetic_order"), List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of())), databaseInstant())
                    : exactFinalizerDiscovery(f, attempt, paid);
            assertThat(observationFinalizer(f.binding(), work).finalizeObservation(RECOVERY_ROUTE, claim, evidence)).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
            assertThat(owner.queryForObject("SELECT payment_status FROM fee_gateway_payment_observations WHERE id=?", String.class, prior)).isEqualTo("SUCCESS");
            assertThat(owner.queryForObject("SELECT captured FROM fee_gateway_payment_observations WHERE id=?", Boolean.class, prior)).isTrue();
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isZero();
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("REVIEW_REQUIRED");
            assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
        }
    }

    @Test void observationFinalizerFailedLastCompletionRollsBackFinanceButKeepsIndependentEvidence() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var ordinary = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, ordinary);
        var evidence = exactFinalizerDiscovery(f, attempt, databaseInstant());
        var sawRealReceiptBeforeFailure = new java.util.concurrent.atomic.AtomicBoolean();
        var failing = new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime), clock, Duration.ofSeconds(15)) {
            @Override public boolean complete(Claim current, Outcome outcome, String safeCode, Duration retryAfter) {
                boolean finished = super.complete(current, outcome, safeCode, retryAfter);
                if (outcome == Outcome.COMPLETE && finished) {
                    sawRealReceiptBeforeFailure.set(runtime.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?", Integer.class, f.school()) == 1);
                    // Deliberate application-level outcome failure after the real
                    // CAS write. Its encompassing transaction must roll back.
                    return false;
                }
                return finished;
            }
        };
        assertThatThrownBy(() -> observationFinalizer(f.binding(), failing).finalizeObservation(RECOVERY_ROUTE, claim, evidence))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sawRealReceiptBeforeFailure).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("LEASED");
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
        assertThat(observationFinalizer(f.binding(), ordinary).finalizeObservation(RECOVERY_ROUTE, claim, evidence)).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        assertOneFinalizedReceipt(f, attempt);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
    }

    @Test void exactGatewayTimestampProjectionDemonstratesRealDriverHalfUpInsteadOfAssumingNanosecondStorage() {
        // Actual PostgreSQL JDBC parameter round-trips, not financial source data
        // or a simulated database clock. PostgreSQL's stored precision is micros.
        var boundaries = List.of(
                new ProjectionBoundary("2026-10-10T18:29:59.123456499Z", "2026-10-10T18:29:59.123456Z", "2026-10-10T18:29:59.123456Z"),
                new ProjectionBoundary("2026-10-10T18:29:59.123456500Z", "2026-10-10T18:29:59.123457Z", "2026-10-10T18:29:59.123456Z"),
                new ProjectionBoundary("2026-10-10T18:29:59.999999500Z", "2026-10-10T18:30:00Z", "2026-10-10T18:29:59.999999Z"));
        ProductionDatabaseSecurity.verify(runtime);
        for (ProjectionBoundary boundary : boundaries) {
            Instant full = Instant.parse(boundary.source());
            Instant implicit = runtime.queryForObject("SELECT CAST(? AS timestamptz)", java.sql.Timestamp.class, java.sql.Timestamp.from(full)).toInstant();
            Instant explicitFloor = runtime.queryForObject("SELECT CAST(? AS timestamptz)",
                    (row,n)->row.getObject(1,java.time.OffsetDateTime.class).toInstant(), GatewayExactInstant.parameter(full));

            assertThat(implicit).as("Actual JDBC half-up conversion %s", boundary.source()).isEqualTo(Instant.parse(boundary.rounded()));
            assertThat(explicitFloor).as("Explicit source derivative %s", boundary.source()).isEqualTo(Instant.parse(boundary.floor()));
            assertThat(GatewayExactInstant.resolve(boundary.source(), explicitFloor)).isEqualTo(full);
        }
    }

    @Test void exactGatewayMidnightReadFactRetainsFullSourceAndFlooredOriginalSchoolDayWithoutInventedDispatch() {
        Fixture f = fixture();
        ZoneId schoolZone = ZoneId.of("Asia/Kolkata");
        LocalDate realPastSchoolDay = LocalDate.ofInstant(databaseInstant(), schoolZone).minusDays(1);
        Instant full = realPastSchoolDay.atTime(23, 59, 59).atZone(schoolZone).toInstant().plusNanos(999999500);
        var fact = finalizerPayment("synthetic_midnight_full_source", full);

        // A true independently read orphan fact is allowed to predate today's
        // setup. It is not a seeded historical CREATE/capture or fee receipt.
        UUID id = background(f, () -> queue.retainPaymentObservation(f.binding(), null, fact));
        assertThat(background(f, () -> queue.retainPaymentObservation(f.binding(), null, fact))).isEqualTo(id);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE id=?", String.class, id)).isEqualTo(full.toString());
        Instant projected = owner.queryForObject("SELECT provider_paid_at FROM fee_gateway_payment_observations WHERE id=?", java.sql.Timestamp.class, id).toInstant();
        assertThat(projected).isEqualTo(full.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(LocalDate.ofInstant(projected, schoolZone)).isEqualTo(realPastSchoolDay);
        LocalDate retainedDay = owner.queryForObject("SELECT (provider_paid_at AT TIME ZONE 'Asia/Kolkata')::date FROM fee_gateway_payment_observations WHERE id=?", LocalDate.class, id);
        assertThat(retainedDay).isEqualTo(realPastSchoolDay);
        Instant implicit = runtime.queryForObject("SELECT CAST(? AS timestamptz)", java.sql.Timestamp.class, java.sql.Timestamp.from(full)).toInstant();
        assertThat(LocalDate.ofInstant(implicit, schoolZone)).isEqualTo(realPastSchoolDay.plusDays(1));
        assertThat(owner.queryForObject("SELECT attempt_id FROM fee_gateway_payment_observations WHERE id=?", UUID.class, id)).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE school_id=?", Integer.class, f.school())).isZero();
        assertNoFinancialPosting(f);
    }

    @Test void exactGatewayFinalizerRetainsFullPaidAndObservedInstantsWithExplicitFloorAndExactRetryIdentity() {
        Fixture f = fixture(); var book = configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        Instant base = databaseInstant();
        Instant paid = base.plusNanos(500), observed = base.plusNanos(800);
        awaitDatabaseTime(base.plusNanos(1000));
        var evidence = finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.PAID, "500.00", List.of("synthetic_order"),
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of(finalizerPayment("synthetic_finalized_payment", paid)))), observed);
        var finalizer = observationFinalizer(f.binding(), work);

        assertThat(finalizer.finalizeObservation(RECOVERY_ROUTE, claim, evidence)).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        assertOneFinalizedReceipt(f, attempt);
        UUID proof = owner.queryForObject("SELECT checkout_observation_id FROM fee_gateway_captures WHERE attempt_id=?", UUID.class, attempt);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE attempt_id=?", String.class, attempt)).isEqualTo(paid.toString());
        assertThat(owner.queryForObject("SELECT paid_at_exact FROM fee_gateway_captures WHERE attempt_id=?", String.class, attempt)).isEqualTo(paid.toString());
        assertThat(owner.queryForObject("SELECT observed_at_exact FROM fee_gateway_checkout_observations WHERE id=?", String.class, proof)).isEqualTo(observed.toString());
        assertThat(owner.queryForObject("SELECT provider_paid_at FROM fee_gateway_payment_observations WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant()).isEqualTo(base);
        assertThat(owner.queryForObject("SELECT paid_at FROM fee_gateway_captures WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant()).isEqualTo(base);
        assertThat(owner.queryForObject("SELECT observed_at FROM fee_gateway_checkout_observations WHERE id=?", java.sql.Timestamp.class, proof).toInstant()).isEqualTo(base);
        assertThat(owner.queryForObject("SELECT original_business_date FROM fee_gateway_captures WHERE attempt_id=?", LocalDate.class, attempt))
                .isEqualTo(LocalDate.ofInstant(paid, ZoneId.of("Asia/Kolkata")));
        var named = new NamedParameterJdbcTemplate(runtime);
        var family = actor(f,f.parent(),()->new FamilyFinanceService(named).fees(f.parent(),f.learner()));
        assertThat(family.receipts()).hasSize(1);
        assertThat(family.receipts().getFirst().timing().actualReceivedAt()).isEqualTo(paid);
        var finance = new FinanceService(named,new SecurityAuditService(runtime,JsonMapper.builder().build(),clock),JsonMapper.builder().build(),clock);
        var staff = actor(f,book.maker(),()->finance.snapshot(book.maker(),f.school()));
        assertThat(staff.accounts().getFirst().payments().getFirst().timing().actualReceivedAt()).isEqualTo(paid);
        var captureQueue = actor(f,book.maker(),()->finance.gatewayCaptures(book.maker(),f.school(),null,"ALL",0,25));
        assertThat(captureQueue.items().getFirst().paidAt()).isEqualTo(paid);
        UUID receiptId = family.receipts().getFirst().paymentId();
        var timing = actor(f,book.maker(),()->new FinanceReceiptDocumentBridge(named).receiptTiming(book.maker().userId(),f.org(),f.school(),receiptId,
                book.maker().membershipIds().iterator().next()));
        assertThat(timing.actualReceivedAt()).isEqualTo(paid);

        // The exact same provider packet may be retained again, but its completed
        // work lease cannot authorize another financial write or proof rebinding.
        assertThatThrownBy(() -> finalizer.finalizeObservation(RECOVERY_ROUTE, claim, evidence)).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT checkout_observation_id FROM fee_gateway_captures WHERE attempt_id=?", UUID.class, attempt)).isEqualTo(proof);
        assertOneFinalizedReceipt(f, attempt);
    }

    @Test void exactGatewayDifferentFullPaidInstantsWithOneMicrosecondProjectionCannotMergeOrPost() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, true); UUID attempt = prepareWork(f);
        var first = dispatchedFinalizerClaim(f, attempt, work);
        Instant base = databaseInstant();
        Instant firstPaid = base.plusNanos(500), secondPaid = base.plusNanos(600);
        awaitDatabaseTime(base.plusNanos(1000));
        var partial = finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.PAID, "500.00",
                List.of("synthetic_order", "synthetic_other_order"),
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of(finalizerPayment("synthetic_finalized_payment", firstPaid))),
                        GatewayDiscoveryEvidence.OrderRead.unknown("synthetic_other_order")), databaseInstant());
        var finalizer = observationFinalizer(f.binding(), work);
        assertThat(finalizer.finalizeObservation(RECOVERY_ROUTE, first, partial)).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        UUID firstFact = owner.queryForObject("SELECT id FROM fee_gateway_payment_observations WHERE attempt_id=?", UUID.class, attempt);
        assertThat(background(f, () -> work.retainPaymentObservation(f.binding(), attempt, finalizerPayment("synthetic_finalized_payment", firstPaid)))).isEqualTo(firstFact);
        awaitDatabaseTime(owner.queryForObject("SELECT next_attempt_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant());
        var second = background(f, () -> work.claimNextAttempt(f.binding()));
        var complete = finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.PAID, "500.00",
                List.of("synthetic_order", "synthetic_other_order"),
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of(finalizerPayment("synthetic_finalized_payment", secondPaid))),
                        GatewayDiscoveryEvidence.OrderRead.complete("synthetic_other_order", List.of())), databaseInstant());

        assertThat(finalizer.finalizeObservation(RECOVERY_ROUTE, second, complete)).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(2);
        assertThat(owner.queryForList("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE attempt_id=? ORDER BY provider_paid_at_exact", String.class, attempt))
                .containsExactly(firstPaid.toString(), secondPaid.toString());
        assertThat(owner.queryForObject("SELECT count(DISTINCT provider_paid_at) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT provider_paid_at FROM fee_gateway_payment_observations WHERE id=?", java.sql.Timestamp.class, firstFact).toInstant()).isEqualTo(base);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isZero();
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
    }

    @Test void providerDiscoveryUsesCommittedSameIdGetOutsideTenantTransactionsAndPostsExactReceipt() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        var snapshot = background(f, () -> finalizerDispatch(finalizerRegistry(f.binding())).loadForReadRecovery(RECOVERY_ROUTE, claim));
        Instant base = databaseInstant(), paid = base.plusNanos(500);
        awaitDatabaseTime(base.plusNanos(1000));
        var checkout = providerDiscoveryCheckout(snapshot, List.of("synthetic_order"));
        var payment = finalizerPayment("synthetic_discovery_payment", paid);
        var provider = org.mockito.Mockito.mock(PaymentProviderAdapter.class);
        org.mockito.Mockito.when(provider.providerCode()).thenReturn("CASHFREE");
        org.mockito.Mockito.when(provider.lookupHostedCheckout(attempt.toString())).thenAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, claim);
            return new PaymentProviderAdapter.HostedCheckoutLookup(checkout, Optional.empty());
        });
        org.mockito.Mockito.when(provider.fetchPayments("synthetic_order")).thenAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, claim);
            return List.of(payment);
        });

        // This is the actual read collector followed by actual non-owner
        // finalization. No browser success callback or webhook is involved.
        var read = new GatewayProviderDiscovery(clock).read(provider, snapshot, Duration.ofSeconds(10));
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(read.hostedCheckout()).isEmpty();
        assertThat(read.evidence().binding()).isEqualTo(f.binding());
        assertThat(read.evidence().checkout()).isEqualTo(checkout);
        assertThat(read.evidence().orderReads()).hasSize(1);
        assertThat(read.evidence().orderReads().getFirst().payments()).contains(List.of(payment));
        assertThat(observationFinalizer(f.binding(), work).finalizeObservation(RECOVERY_ROUTE, claim, read.evidence()))
                .isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        ProductionDatabaseSecurity.verify(runtime);
        assertOneFinalizedReceipt(f, attempt);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE attempt_id=?", String.class, attempt))
                .isEqualTo(paid.toString());
        assertThat(owner.queryForObject("SELECT paid_at_exact FROM fee_gateway_captures WHERE attempt_id=?", String.class, attempt))
                .isEqualTo(paid.toString());
        assertThat(owner.queryForObject("SELECT observed_at_exact FROM fee_gateway_checkout_observations WHERE attempt_id=?", String.class, attempt))
                .isEqualTo(read.evidence().observedAt().toString());
        assertThat(owner.queryForObject("SELECT paid_at FROM fee_gateway_captures WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant())
                .isEqualTo(base);
        org.mockito.Mockito.verify(provider).lookupHostedCheckout(attempt.toString());
        org.mockito.Mockito.verify(provider).fetchPayments("synthetic_order");
        assertProviderDiscoveryNeverCreates(provider);
    }

    @Test void providerDiscoveryFailedOrderRetainsKnownMoneyAndLaterCompleteGetPostsOnce() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(15, true); UUID attempt = prepareWork(f);
        var first = dispatchedFinalizerClaim(f, attempt, work);
        var dispatch = finalizerDispatch(finalizerRegistry(f.binding()));
        var snapshot = background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, first));
        Instant paid = databaseInstant().plusNanos(500);
        awaitDatabaseTime(paid.plusNanos(500));
        var checkout = providerDiscoveryCheckout(snapshot, List.of("synthetic_failed_order", "synthetic_order"));
        var payment = finalizerPayment("synthetic_discovery_payment", paid);
        var provider = org.mockito.Mockito.mock(PaymentProviderAdapter.class);
        org.mockito.Mockito.when(provider.providerCode()).thenReturn("CASHFREE");
        org.mockito.Mockito.when(provider.lookupHostedCheckout(attempt.toString())).thenAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, first);
            return new PaymentProviderAdapter.HostedCheckoutLookup(checkout, Optional.empty());
        });
        org.mockito.Mockito.when(provider.fetchPayments("synthetic_failed_order")).thenAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, first);
            throw new IllegalStateException("Synthetic unknown order read; no provider payload");
        });
        org.mockito.Mockito.when(provider.fetchPayments("synthetic_order")).thenAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, first);
            return List.of(payment);
        });
        var collector = new GatewayProviderDiscovery(clock);
        var partial = collector.read(provider, snapshot, Duration.ofSeconds(10));
        assertThat(partial.evidence().orderReads()).hasSize(2);
        assertThat(partial.evidence().orderReads().getFirst().orderReference()).isEqualTo("synthetic_failed_order");
        assertThat(partial.evidence().orderReads().getFirst().payments()).isEmpty();
        assertThat(partial.evidence().orderReads().get(1).payments()).contains(List.of(payment));
        var finalizer = observationFinalizer(f.binding(), work);
        assertThat(finalizer.finalizeObservation(RECOVERY_ROUTE, first, partial.evidence())).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        UUID originalFact = owner.queryForObject("SELECT id FROM fee_gateway_payment_observations WHERE attempt_id=?", UUID.class, attempt);
        UUID incompleteProof = owner.queryForObject("SELECT id FROM fee_gateway_checkout_observations WHERE attempt_id=?", UUID.class, attempt);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE id=?", String.class, originalFact)).isEqualTo(paid.toString());
        assertThat(owner.queryForObject("SELECT discovery_complete FROM fee_gateway_checkout_observations WHERE id=?", Boolean.class, incompleteProof)).isFalse();
        assertThat(owner.queryForObject("SELECT successful_payment_count FROM fee_gateway_checkout_observations WHERE id=?", Integer.class, incompleteProof)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isZero();
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);

        awaitDatabaseTime(owner.queryForObject("SELECT next_attempt_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant());
        var second = background(f, () -> work.claimNextAttempt(f.binding()));
        assertThat(second.createDispatched()).isTrue();
        var refreshed = background(f, () -> dispatch.loadForReadRecovery(RECOVERY_ROUTE, second));
        org.mockito.Mockito.doAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, second);
            return new PaymentProviderAdapter.HostedCheckoutLookup(checkout, Optional.empty());
        }).when(provider).lookupHostedCheckout(attempt.toString());
        // doAnswer replaces the original throwing stub without executing it
        // during setup. The real collector must obtain a whole successful list.
        org.mockito.Mockito.doAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, second);
            return List.of();
        }).when(provider).fetchPayments("synthetic_failed_order");
        org.mockito.Mockito.doAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, second);
            return List.of(payment);
        }).when(provider).fetchPayments("synthetic_order");
        var complete = collector.read(provider, refreshed, Duration.ofSeconds(10));
        assertThat(complete.evidence().orderReads().getFirst().payments()).contains(List.of());
        assertThat(finalizer.finalizeObservation(RECOVERY_ROUTE, second, complete.evidence())).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        assertOneFinalizedReceipt(f, attempt);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT id FROM fee_gateway_payment_observations WHERE attempt_id=?", UUID.class, attempt)).isEqualTo(originalFact);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT checkout_observation_id FROM fee_gateway_captures WHERE attempt_id=?", UUID.class, attempt)).isNotEqualTo(incompleteProof);
        assertThat(owner.queryForObject("SELECT discovery_complete FROM fee_gateway_checkout_observations WHERE id=?", Boolean.class, incompleteProof)).isFalse();
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(2)).lookupHostedCheckout(attempt.toString());
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(2)).fetchPayments("synthetic_failed_order");
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(2)).fetchPayments("synthetic_order");
        assertProviderDiscoveryNeverCreates(provider);
    }

    @Test void providerDiscoveryActualLeaseExpiryDuringGetRetainsFactsWithoutFinancialPosting() throws Exception {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(5, false); UUID attempt = prepareWork(f);
        var claim = dispatchedFinalizerClaim(f, attempt, work);
        var snapshot = background(f, () -> finalizerDispatch(finalizerRegistry(f.binding())).loadForReadRecovery(RECOVERY_ROUTE, claim));
        Instant paid = databaseInstant().plusNanos(500);
        awaitDatabaseTime(paid.plusNanos(500));
        var checkout = providerDiscoveryCheckout(snapshot, List.of("synthetic_order"));
        var payment = finalizerPayment("synthetic_discovery_payment", paid);
        var enteredPaymentRead = new CountDownLatch(1);
        var releasePaymentRead = new CountDownLatch(1);
        var provider = org.mockito.Mockito.mock(PaymentProviderAdapter.class);
        org.mockito.Mockito.when(provider.providerCode()).thenReturn("CASHFREE");
        org.mockito.Mockito.when(provider.lookupHostedCheckout(attempt.toString())).thenAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, claim);
            return new PaymentProviderAdapter.HostedCheckoutLookup(checkout, Optional.empty());
        });
        org.mockito.Mockito.when(provider.fetchPayments("synthetic_order")).thenAnswer(invocation -> {
            assertProviderDiscoveryOutsidePersistence(f, claim);
            assertThat(databaseInstant()).isBefore(claim.leaseUntil());
            enteredPaymentRead.countDown();
            if (!releasePaymentRead.await(10, TimeUnit.SECONDS)) throw new AssertionError("Synthetic provider read was not released");
            assertProviderDiscoveryOutsidePersistence(f, claim);
            return List.of(payment);
        });
        try (var externalRead = Executors.newSingleThreadExecutor()) {
            var collected = externalRead.submit(() -> new GatewayProviderDiscovery(clock).read(provider, snapshot, Duration.ofSeconds(10)));
            try {
                assertThat(enteredPaymentRead.await(5, TimeUnit.SECONDS)).isTrue();
                // Only real database time advances; do not rewrite the lease,
                // create marker, reservation, guard or transaction boundaries.
                awaitDatabaseTime(claim.leaseUntil());
            } finally {
                releasePaymentRead.countDown();
            }
            var read = collected.get(15, TimeUnit.SECONDS);
            assertThat(read.evidence().orderReads().getFirst().payments()).contains(List.of(payment));
            assertThat(read.evidence().observedAt()).isAfterOrEqualTo(claim.leaseUntil());
            assertThatThrownBy(() -> observationFinalizer(f.binding(), work).finalizeObservation(RECOVERY_ROUTE, claim, read.evidence()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE attempt_id=?", String.class, attempt)).isEqualTo(paid.toString());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isZero();
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, attempt)).isEqualTo("LEASED");
        assertThat(owner.queryForObject("SELECT lease_token FROM fee_gateway_attempt_work WHERE attempt_id=?", UUID.class, attempt)).isEqualTo(claim.token());
        assertThat(owner.queryForObject("SELECT create_dispatched_by_lease_token FROM fee_gateway_attempt_work WHERE attempt_id=?", UUID.class, attempt)).isEqualTo(claim.token());
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
        org.mockito.Mockito.verify(provider).lookupHostedCheckout(attempt.toString());
        org.mockito.Mockito.verify(provider).fetchPayments("synthetic_order");
        assertProviderDiscoveryNeverCreates(provider);
    }

    private static PaymentProviderAdapter.CheckoutObservation providerDiscoveryCheckout(GatewayAttemptDispatch.ReadRecovery snapshot, List<String> orders) {
        return new PaymentProviderAdapter.CheckoutObservation(snapshot.attemptId().toString(), snapshot.attemptId().toString(), snapshot.amount(),
                snapshot.currencyCode(), snapshot.amount(), PaymentProviderAdapter.CheckoutStatus.PAID, snapshot.expiresAt(), orders);
    }
    private void assertProviderDiscoveryOutsidePersistence(Fixture fixture, GatewayWorkQueue.Claim claim) {
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()).isEmpty();
        // Independent owner connection observes only committed admission evidence;
        // it is a test oracle, never the connection used for product mutations.
        assertThat(owner.queryForObject("SELECT create_dispatched_by_lease_token FROM fee_gateway_attempt_work WHERE attempt_id=? AND organisation_id=? AND school_id=?",
                UUID.class, claim.id(), fixture.org(), fixture.school())).isNotNull();
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, claim.id())).isNotNull();
    }
    private static void assertProviderDiscoveryNeverCreates(PaymentProviderAdapter provider) {
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).createHostedCheckout(org.mockito.Mockito.any(PaymentProviderAdapter.CheckoutRequest.class));
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).createHostedCheckout(org.mockito.Mockito.any(UUID.class),
                org.mockito.Mockito.any(BigDecimal.class), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(URI.class));
    }

    private record ProjectionBoundary(String source, String rounded, String floor) {}

    @Test void hostedCheckoutForwardUpgradePreservesExactV071HistoryWithoutBackfillAndIsIdempotent() {
        assertThat(exactInstantUpgradeCount).isEqualTo(1);
        assertThat(hostedCheckoutUpgradeCount).isEqualTo(1);
        assertThat(hostedCheckoutUpgradeHistory()).isEqualTo(hostedCheckoutUpgradeSnapshot);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE id=?",
                String.class, hostedCheckoutUpgradeFact)).isNotNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE school_id=?",
                Integer.class, legacyInstantFixture.school())).isZero();
        assertThat(Flyway.configure().dataSource(owner.getDataSource()).locations("classpath:db/migration")
                .target("072").validateMigrationNaming(true).cleanDisabled(true).load().migrate().migrationsExecuted).isZero();
        assertThat(hostedCheckoutUpgradeHistory()).isEqualTo(hostedCheckoutUpgradeSnapshot);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_class WHERE relname='fee_gateway_hosted_checkouts'
                  AND relkind='r' AND relrowsecurity AND relforcerowsecurity
                """, Integer.class)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts", Integer.class)).isZero();
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void hostedCheckoutEncryptedCreateAndConcurrentGetRetriesPreserveFirstExactSourceWithoutFinancialWrites() throws Exception {
        var test = hostedCase(30); var f = test.fixture(); var hosted = hostedLink(test.attempt(), "synthetic_first");
        Instant observed = databaseInstant().plusNanos(500); awaitDatabaseTime(observed.plusNanos(500));
        var store = hostedStore(f, new SensitiveValueCipher(properties, new SecureRandom()));
        var retained = store.retainCreate(RECOVERY_ROUTE, test.claim(), hosted, observed);
        assertThat(retained.conflicting()).isFalse();
        var first = hostedRow(retained.id());
        assertThat(first.get("url_ciphertext")).isNotEqualTo(hosted.redirectUri().toASCIIString());
        assertThat(first.toString()).doesNotContain(hosted.redirectUri().toASCIIString(), "/links/synthetic_first");
        assertThat(first.get("source_kind")).isEqualTo("CREATE");
        assertThat(first.get("observed_at_exact")).isEqualTo(observed.toString());
        assertThat(((java.sql.Timestamp) first.get("observed_at")).toInstant()).isEqualTo(GatewayExactInstant.floor(observed));
        assertThat(((java.sql.Timestamp) first.get("retained_at")).toInstant()).isAfterOrEqualTo(observed);
        assertThat(first.get("source_lease_token")).isEqualTo(test.claim().token());
        var encrypted = new SensitiveValueCipher.EncryptedValue((String) first.get("url_ciphertext"), (String) first.get("url_nonce"));
        byte[] decoded = new SensitiveValueCipher(properties, new SecureRandom()).decrypt(encrypted,
                GatewayHostedCheckoutStore.associatedData(f.binding(), test.attempt()));
        try { assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(hosted.redirectUri().toASCIIString()); }
        finally { java.util.Arrays.fill(decoded, (byte) 0); }
        assertThatThrownBy(() -> new SensitiveValueCipher(properties, new SecureRandom()).decrypt(encrypted,
                GatewayHostedCheckoutStore.associatedData(f.binding(), UUID.randomUUID()))).isInstanceOf(IllegalStateException.class);
        var read = hostedRead(f, test.attempt(), hosted, databaseInstant());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return store.retainGet(RECOVERY_ROUTE, test.claim(), read).orElseThrow(); });
            var b = workers.submit(() -> { start.await(); return store.retainGet(RECOVERY_ROUTE, test.claim(), read).orElseThrow(); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(retained);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(retained);
        }
        assertThat(hostedRow(retained.id())).isEqualTo(first);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, test.attempt())).isEqualTo(1);
        assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
    }

    @Test void hostedCheckoutChangedValidUriAppendsImmutableStickyConflictWithoutChoosingOrPayingEitherLink() {
        var test = hostedCase(30); var f = test.fixture();
        var store = hostedStore(f, new SensitiveValueCipher(properties, new SecureRandom()));
        var firstLink = hostedLink(test.attempt(), "synthetic_original");
        var first = store.retainCreate(RECOVERY_ROUTE, test.claim(), firstLink, databaseInstant());
        var original = hostedRow(first.id());
        var changed = store.retainGet(RECOVERY_ROUTE, test.claim(), hostedRead(f, test.attempt(),
                hostedLink(test.attempt(), "synthetic_changed"), databaseInstant())).orElseThrow();
        assertThat(changed.id()).isNotEqualTo(first.id()); assertThat(changed.conflicting()).isTrue();
        var retry = store.retainCreate(RECOVERY_ROUTE, test.claim(), firstLink, databaseInstant());
        assertThat(retry.id()).isEqualTo(first.id()); assertThat(retry.conflicting()).isTrue();
        assertThat(hostedRow(first.id())).isEqualTo(original);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, test.attempt())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, test.attempt())).isZero();
        assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
    }

    @Test void hostedCheckoutInvalidMissingDispatchForgedClaimsAndCipherFailuresFailClosedWithoutPrivateErrors() {
        var test = hostedCase(30); var f = test.fixture();
        var store = hostedStore(f, new SensitiveValueCipher(properties, new SecureRandom()));
        var hosted = hostedLink(test.attempt(), "synthetic_valid"); Instant observed = databaseInstant();
        var first = store.retainCreate(RECOVERY_ROUTE, test.claim(), hosted, observed); var original = hostedRow(first.id());
        for (String address : List.of("https://payments.cashfree.com/links/synthetic_wrong_environment",
                "https://payments-test.cashfree.com/links/synthetic?token=synthetic-private-token",
                "https://payments-test.cashfree.com.evil.example/links/synthetic")) {
            assertHostedRejected(() -> store.retainCreate(RECOVERY_ROUTE, test.claim(),
                    new PaymentProviderAdapter.HostedCheckout(test.attempt().toString(), URI.create(address)), observed), address);
        }
        var forged = new GatewayWorkQueue.Claim(test.claim().kind(), test.attempt(), f.binding(), UUID.randomUUID(),
                test.claim().startedAt(), test.claim().leaseUntil(), test.claim().attemptNumber(), true);
        assertHostedRejected(() -> store.retainCreate(RECOVERY_ROUTE, forged, hosted, observed), hosted.redirectUri().toString());
        var counter = new GatewayWorkQueue.Claim(test.claim().kind(), test.attempt(), f.binding(), test.claim().token(),
                test.claim().startedAt(), test.claim().leaseUntil(), test.claim().attemptNumber() + 1, true);
        assertHostedRejected(() -> store.retainCreate(RECOVERY_ROUTE, counter, hosted, observed), hosted.redirectUri().toString());
        var changedBinding = new GatewayWorkQueue.ProviderBinding(f.org(), f.school(), f.provider(), "CASHFREE", "TEST",
                f.binding().merchantReference(), "synthetic:changed-credentials");
        var wrongBinding = new GatewayWorkQueue.Claim(test.claim().kind(), test.attempt(), changedBinding, test.claim().token(),
                test.claim().startedAt(), test.claim().leaseUntil(), test.claim().attemptNumber(), true);
        assertHostedRejected(() -> store.retainCreate(RECOVERY_ROUTE, wrongBinding, hosted, observed), hosted.redirectUri().toString());
        try (var ignored = TenantRequestContext.open(f.parent().userId(), f.org())) {
            assertHostedRejected(() -> store.retainCreate(RECOVERY_ROUTE, test.claim(), hosted, observed), hosted.redirectUri().toString());
        }
        UUID undispatched = prepareWork(f); var unadmitted = background(f, () -> test.work().claimNextAttempt(f.binding()));
        assertThat(unadmitted.id()).isEqualTo(undispatched);
        assertHostedRejected(() -> store.retainCreate(RECOVERY_ROUTE, unadmitted,
                hostedLink(undispatched, "synthetic_not_dispatched"), databaseInstant()), "synthetic_not_dispatched");
        var broken = org.mockito.Mockito.mock(SensitiveValueCipher.class);
        org.mockito.Mockito.when(broken.encrypt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("synthetic-private-key " + hosted.redirectUri()));
        org.mockito.Mockito.when(broken.decrypt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("synthetic-private-key " + hosted.redirectUri()));
        var brokenStore = hostedStore(f, broken);
        assertHostedRejected(() -> brokenStore.retainCreate(RECOVERY_ROUTE, test.claim(), hosted, observed), "synthetic-private-key");
        assertHostedRejected(() -> brokenStore.retainCreate(RECOVERY_ROUTE, test.claim(),
                hostedLink(test.attempt(), "synthetic_new_cipher_failure"), databaseInstant()), "synthetic-private-key");
        assertThat(hostedRow(first.id())).isEqualTo(original);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
    }

    @Test void hostedCheckoutSqlRlsIsInitiatingGuardianOnlyAndRechecksLiveOwnChildAuthorization() {
        var test = hostedCase(30); var f = test.fixture();
        var retained = hostedStore(f, new SensitiveValueCipher(properties, new SecureRandom()))
                .retainCreate(RECOVERY_ROUTE, test.claim(), hostedLink(test.attempt(), "synthetic_private"), databaseInstant());
        UUID otherParent = addHostedGuardian(f, f.learner());
        UUID staff = owner.queryForObject("SELECT user_id FROM memberships WHERE school_id=? AND user_id<>? AND user_id<>? LIMIT 1",
                UUID.class, f.school(), f.parent().userId(), otherParent);
        Fixture sibling = fixture(f.org()), foreign = fixture();
        assertThat(as(f, f.parent().userId(), () -> hostedVisibleCount(retained.id()))).isEqualTo(1);
        assertThat(as(f, otherParent, () -> hostedVisibleCount(retained.id()))).isZero();
        assertThat(as(f, staff, () -> hostedVisibleCount(retained.id()))).isZero();
        assertThat(as(sibling, sibling.parent().userId(), () -> hostedVisibleCount(retained.id()))).isZero();
        assertThat(as(foreign, f.parent().userId(), () -> hostedVisibleCount(retained.id()))).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE id=?", Integer.class, retained.id())).isZero();
        UUID member = f.parent().membershipIds().iterator().next();
        for (String revoked : List.of("MEMBERSHIP", "USER", "GUARDIAN", "RELATIONSHIP", "CAPABILITY", "LEARNER", "SCHOOL", "ORGANISATION")) {
            switch (revoked) {
                case "MEMBERSHIP" -> owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=now() WHERE id=?", member);
                case "USER" -> owner.update("UPDATE user_accounts SET status='SUSPENDED' WHERE id=?", f.parent().userId());
                case "GUARDIAN" -> owner.update("UPDATE guardians SET status='INACTIVE',updated_at=now() WHERE user_account_id=?", f.parent().userId());
                case "RELATIONSHIP" -> owner.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED',updated_at=now() WHERE learner_id=?", f.learner());
                case "CAPABILITY" -> owner.update("DELETE FROM membership_roles WHERE membership_id=?", member);
                case "LEARNER" -> owner.update("UPDATE learners SET status='INACTIVE',updated_at=now() WHERE id=?", f.learner());
                case "SCHOOL" -> owner.update("UPDATE schools SET status='SUSPENDED',updated_at=now() WHERE id=?", f.school());
                case "ORGANISATION" -> owner.update("UPDATE organisations SET status='SUSPENDED',updated_at=now() WHERE id=?", f.org());
                default -> throw new IllegalArgumentException();
            }
            assertThat(as(f, f.parent().userId(), () -> hostedVisibleCount(retained.id()))).as(revoked).isZero();
            switch (revoked) {
                case "MEMBERSHIP" -> owner.update("UPDATE memberships SET status='ACTIVE',updated_at=now() WHERE id=?", member);
                case "USER" -> owner.update("UPDATE user_accounts SET status='ACTIVE' WHERE id=?", f.parent().userId());
                case "GUARDIAN" -> owner.update("UPDATE guardians SET status='ACTIVE',updated_at=now() WHERE user_account_id=?", f.parent().userId());
                case "RELATIONSHIP" -> owner.update("UPDATE learner_guardian_relationships SET portal_access_status='ACTIVE',updated_at=now() WHERE learner_id=?", f.learner());
                case "CAPABILITY" -> owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000006')", member);
                case "LEARNER" -> owner.update("UPDATE learners SET status='ACTIVE',updated_at=now() WHERE id=?", f.learner());
                case "SCHOOL" -> owner.update("UPDATE schools SET status='ACTIVE',updated_at=now() WHERE id=?", f.school());
                case "ORGANISATION" -> owner.update("UPDATE organisations SET status='ACTIVE',updated_at=now() WHERE id=?", f.org());
                default -> throw new IllegalArgumentException();
            }
            assertThat(as(f, f.parent().userId(), () -> hostedVisibleCount(retained.id()))).as(revoked + " restored").isEqualTo(1);
        }
        assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
    }

    @Test void hostedCheckoutActualFinanceLockWaitRejectsExpiredLeaseButStillRetainsIndependentGetMoney() throws Exception {
        var test = hostedCase(5); var f = test.fixture();
        Instant paid = databaseInstant().plusNanos(500); awaitDatabaseTime(paid.plusNanos(500));
        var evidence = exactFinalizerDiscovery(f, test.attempt(), paid);
        var read = new GatewayProviderDiscovery.ReadResult(evidence, Optional.of(hostedLink(test.attempt(), "synthetic_expired_lease")));
        var store = hostedStore(f, new SensitiveValueCipher(properties, new SecureRandom()));
        try (var locker = owner.getDataSource().getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            locker.setAutoCommit(false);
            try (var lock = locker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, f.school() + ":finance"); lock.execute();
            }
            var retention = worker.submit(() -> store.retainGet(RECOVERY_ROUTE, test.claim(), read));
            try { awaitBlockedFinalizerFinanceLock(); awaitDatabaseTime(test.claim().leaseUntil()); }
            finally { locker.rollback(); }
            assertThatThrownBy(() -> retention.get(10, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .satisfies(failure -> assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class).hasNoCause());
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, test.attempt())).isZero();
        assertThatThrownBy(() -> observationFinalizer(f.binding(), test.work()).finalizeObservation(RECOVERY_ROUTE, test.claim(), evidence))
                .isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE attempt_id=?", String.class, test.attempt())).isEqualTo(paid.toString());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=?", Integer.class, test.attempt())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, test.attempt())).isZero();
        assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
    }

    @Test void hostedCheckoutDatabaseGuardsRejectForgedExactSourcesAndProtectHistoryEvenForOwner() {
        var test = hostedCase(30); var f = test.fixture();
        var retained = hostedStore(f, new SensitiveValueCipher(properties, new SecureRandom()))
                .retainCreate(RECOVERY_ROUTE, test.claim(), hostedLink(test.attempt(), "synthetic_guard"), databaseInstant());
        var original = hostedRow(retained.id());
        Instant valid = databaseInstant().plusNanos(500); awaitDatabaseTime(valid.plusNanos(500));
        assertThatThrownBy(() -> rawHostedCopy(f, retained.id(), valid, null, test.claim().token(), "now()"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> rawHostedCopy(f, retained.id(), valid, "synthetic-private-invalid-source", test.claim().token(), "now()"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> rawHostedCopy(f, retained.id(), valid.plusNanos(1000), valid.toString(), test.claim().token(), "now()"))
                .isInstanceOf(DataAccessException.class);
        Instant future = databaseInstant().plusSeconds(60);
        assertThatThrownBy(() -> rawHostedCopy(f, retained.id(), future, future.toString(), test.claim().token(), "now()"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> rawHostedCopy(f, retained.id(), valid, valid.toString(), UUID.randomUUID(), "now()"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> rawHostedCopy(f, retained.id(), test.claim().startedAt(), test.claim().startedAt().toString(), test.claim().token(), "now()"))
                .isInstanceOf(DataAccessException.class);
        UUID dbTimed = rawHostedCopy(f, retained.id(), valid, valid.toString(), test.claim().token(), "'infinity'::timestamptz");
        assertThat(owner.queryForObject("SELECT isfinite(retained_at) AND retained_at<=clock_timestamp() FROM fee_gateway_hosted_checkouts WHERE id=?", Boolean.class, dbTimed)).isTrue();
        assertThatThrownBy(() -> owner.update("UPDATE fee_gateway_hosted_checkouts SET url_sha256=repeat('f',64) WHERE id=?", retained.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("DELETE FROM fee_gateway_hosted_checkouts WHERE id=?", retained.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(f, f.parent().userId(), () -> runtime.update("DELETE FROM fee_gateway_hosted_checkouts WHERE id=?", retained.id())))
                .isInstanceOf(DataAccessException.class);
        assertThat(hostedRow(retained.id())).isEqualTo(original);
        assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
    }

    @Test void gatewayCreateCommandDecryptsOriginalScopedPayerAndCommitsOneUseImmutableTermsWithoutProviderOrFinancialWrites() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(30, false); UUID attempt = prepareWork(f);
        var claim = background(f, () -> work.claimNextAttempt(f.binding()));
        assertThat(claim.id()).isEqualTo(attempt); assertThat(claim.createDispatched()).isFalse();
        var frozen = owner.queryForMap("SELECT * FROM fee_gateway_attempts WHERE id=?", attempt);
        var originalWork = owner.queryForMap("SELECT * FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt);
        assertThat(originalWork.get("create_dispatched_at")).isNull();
        var registry = finalizerRegistry(f.binding());
        var admission = new GatewayCreateCommandAdmission(registry, finalizerDispatch(registry), new NamedParameterJdbcTemplate(runtime),
                new SensitiveValueCipher(properties, new SecureRandom()), transactions.getTransactionManager());

        // Direct call outside any context/transaction: the returned command is
        // observed only after its privately scoped admission transaction commits.
        var command = admission.admit(RECOVERY_ROUTE, claim);
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var request = command.request(); var terms = command.admission().attempt();
        assertThat(request.attemptId()).isEqualTo(attempt);
        assertThat(request.accountId()).isEqualTo(frozen.get("learner_fee_account_id"));
        assertThat(request.amount()).isEqualByComparingTo((BigDecimal) frozen.get("amount"));
        assertThat(request.currencyCode()).isEqualTo(frozen.get("currency_code"));
        assertThat(request.expiresAt()).isEqualTo(((java.sql.Timestamp) frozen.get("expires_at")).toInstant());
        assertThat(request.returnUri().toASCIIString()).isEqualTo(frozen.get("return_uri"));
        assertThat(request.payerPhone()).isEqualTo("0000000000");
        assertThat(terms.binding()).isEqualTo(f.binding());
        assertThat(terms.createPreviouslyDispatched()).isFalse();
        assertThat(command.toString()).doesNotContain(request.payerPhone(), request.returnUri().toASCIIString(), "payer_phone");
        assertThat(request.toString()).doesNotContain(request.payerPhone(), request.returnUri().toASCIIString());
        assertThat(owner.queryForMap("SELECT * FROM fee_gateway_attempts WHERE id=?", attempt)).isEqualTo(frozen);
        var dispatched = owner.queryForMap("SELECT * FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt);
        assertThat(dispatched.get("create_dispatched_by_lease_token")).isEqualTo(claim.token());
        assertThat(((java.sql.Timestamp) dispatched.get("create_dispatched_at")).toInstant()).isEqualTo(command.admission().dispatchedAt());
        assertThat(((Number) dispatched.get("row_version")).longValue()).isEqualTo(((Number) originalWork.get("row_version")).longValue() + 1);
        assertThatThrownBy(() -> admission.admit(RECOVERY_ROUTE, claim)).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(owner.queryForMap("SELECT * FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt)).isEqualTo(dispatched);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, attempt)).isZero();
        assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
    }

    @Test void gatewayCreateCommandRealAesWrongKeyAndWrongAadRollBackTheEntireMarkerAndPreserveOriginalWorkAndAudit() {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(30, false); UUID attempt = prepareWork(f);
        var claim = background(f, () -> work.claimNextAttempt(f.binding()));
        assertThat(claim.id()).isEqualTo(attempt);
        var originalWork = owner.queryForMap("SELECT * FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt);
        var originalAttempt = owner.queryForMap("SELECT * FROM fee_gateway_attempts WHERE id=?", attempt);
        var originalAudit = owner.queryForList("SELECT to_jsonb(source)::text FROM audit_events source WHERE school_id=? ORDER BY to_jsonb(source)::text", String.class, f.school());
        byte[] wrongBytes = new byte[32]; java.util.Arrays.fill(wrongBytes, (byte) 1);
        String wrongKey = Base64.getEncoder().encodeToString(wrongBytes); java.util.Arrays.fill(wrongBytes, (byte) 0);
        var wrongProperties = new ApplicationProperties(properties.publicUrl(), List.of(), Optional.empty(), Optional.empty(),
                new ApplicationProperties.Security("synthetic_session", true, Duration.ofHours(1), Duration.ofHours(2), Duration.ofMinutes(10), null,
                        wrongKey, Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofDays(1), "Synthetic school"),
                new ApplicationProperties.Bootstrap(false, null, null));
        var wrongKeyCipher = new SensitiveValueCipher(wrongProperties, new SecureRandom());
        var wrongAadCipher = new SensitiveValueCipher(properties, new SecureRandom()) {
            @Override public byte[] decrypt(EncryptedValue value, String associatedData) {
                // Real AES/GCM authentication failure, not a mocked exception or
                // mutation of persisted payer ciphertext, nonce or its guards.
                return super.decrypt(value, associatedData + "\nsynthetic_wrong_aad");
            }
        };
        for (var cipher : List.of(wrongKeyCipher, wrongAadCipher)) {
            var registry = finalizerRegistry(f.binding());
            var admission = new GatewayCreateCommandAdmission(registry, finalizerDispatch(registry), new NamedParameterJdbcTemplate(runtime),
                    cipher, transactions.getTransactionManager());
            assertThatThrownBy(() -> admission.admit(RECOVERY_ROUTE, claim)).isInstanceOf(IllegalStateException.class).hasNoCause()
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(wrongKey, "0000000000", "ciphertext", "synthetic_wrong_aad"));
            assertThat(owner.queryForMap("SELECT * FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt)).isEqualTo(originalWork);
            assertThat(owner.queryForMap("SELECT * FROM fee_gateway_attempts WHERE id=?", attempt)).isEqualTo(originalAttempt);
            assertThat(owner.queryForList("SELECT to_jsonb(source)::text FROM audit_events source WHERE school_id=? ORDER BY to_jsonb(source)::text", String.class, f.school())).isEqualTo(originalAudit);
            assertThat(TenantRequestContext.current()).isEmpty();
            assertNoFinancialPosting(f); assertOriginalReservation(f, attempt);
        }
        assertThat(originalWork.get("create_dispatched_at")).isNull();
        assertThat(originalWork.get("create_dispatched_by_lease_token")).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, attempt)).isZero();
    }

    @Test void gatewayWorkerConcurrentCreateCommitsBeforeProviderAndFreshGetPostsOnce() throws Exception {
        var test = workerCase(false); var postEntered = new CountDownLatch(1); var releasePost = new CountDownLatch(1);
        var paid = new java.util.concurrent.atomic.AtomicReference<Instant>();
        org.mockito.Mockito.when(test.provider().createHostedCheckout(org.mockito.ArgumentMatchers.any(PaymentProviderAdapter.CheckoutRequest.class)))
                .thenAnswer(invocation -> {
                    assertWorkerExternalBoundary(); assertWorkerCommittedCreate(test);
                    var request = invocation.getArgument(0, PaymentProviderAdapter.CheckoutRequest.class);
                    assertThat(request.attemptId()).isEqualTo(test.attempt()); assertThat(request.payerPhone()).isEqualTo("0000000000");
                    postEntered.countDown();
                    if (!releasePost.await(10, TimeUnit.SECONDS)) throw new AssertionError("Synthetic worker CREATE was not released");
                    paid.set(workerPaymentInstant()); return hostedLink(test.attempt(), "synthetic_worker_checkout");
                });
        configureWorkerPaidGets(test, paid);
        try (var callers = Executors.newSingleThreadExecutor()) {
            var first = callers.submit(() -> test.worker().runNext(RECOVERY_ROUTE));
            try {
                assertThat(postEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.IDLE);
                assertNoFinancialPosting(test.fixture()); assertOriginalReservation(test.fixture(), test.attempt());
            } finally { releasePost.countDown(); }
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        }
        var firstMarker = workerMarker(test.attempt());
        assertThat(firstMarker.get("create_dispatched_at")).isNotNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, test.attempt())).isEqualTo(1);
        awaitWorkerRetry(test.attempt());
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertOneFinalizedReceipt(test.fixture(), test.attempt());
        assertThat(workerMarker(test.attempt())).isEqualTo(firstMarker);
        assertThat(owner.queryForObject("SELECT paid_at_exact FROM fee_gateway_captures WHERE attempt_id=?", String.class, test.attempt())).isEqualTo(paid.get().toString());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, test.attempt())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT source_kind FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", String.class, test.attempt())).isEqualTo("CREATE");
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.IDLE);
        assertThat(test.factoryCalls().get()).isEqualTo(1);
        assertWorkerSingleCreateAndGet(test, 1, 1);
    }

    @Test void gatewayWorkerUnknownCreateAndMissingReadRecoverSameIdentityWithoutAnotherPost() {
        var test = workerCase(false); var paid = new java.util.concurrent.atomic.AtomicReference<Instant>();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(test.provider().createHostedCheckout(org.mockito.ArgumentMatchers.any(PaymentProviderAdapter.CheckoutRequest.class)))
                .thenAnswer(invocation -> {
                    assertWorkerExternalBoundary(); assertWorkerCommittedCreate(test);
                    assertThat(invocation.getArgument(0, PaymentProviderAdapter.CheckoutRequest.class).attemptId()).isEqualTo(test.attempt());
                    paid.set(workerPaymentInstant());
                    // A network/response failure says nothing about whether the
                    // provider already charged. The committed marker survives.
                    throw new IllegalStateException("Synthetic provider response unavailable after accepted CREATE");
                });
        org.mockito.Mockito.when(test.provider().lookupHostedCheckout(test.attempt().toString())).thenAnswer(invocation -> {
            assertWorkerExternalBoundary(); assertWorkerCommittedCreate(test);
            if (reads.incrementAndGet() == 1) throw new IllegalStateException("Synthetic same-ID GET not found yet");
            return workerPaidLookup(test);
        });
        org.mockito.Mockito.when(test.provider().fetchPayments("synthetic_order")).thenAnswer(invocation -> {
            assertWorkerExternalBoundary(); assertWorkerCommittedCreate(test);
            return List.of(finalizerPayment("synthetic_worker_payment", paid.get()));
        });
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        var marker = workerMarker(test.attempt());
        assertThat(marker.get("create_dispatched_at")).isNotNull();
        assertNoFinancialPosting(test.fixture()); assertOriginalReservation(test.fixture(), test.attempt());
        awaitWorkerRetry(test.attempt());
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(workerMarker(test.attempt())).isEqualTo(marker);
        assertNoFinancialPosting(test.fixture()); assertOriginalReservation(test.fixture(), test.attempt());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, test.attempt())).isZero();
        awaitWorkerRetry(test.attempt());
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(workerMarker(test.attempt())).isEqualTo(marker);
        assertOneFinalizedReceipt(test.fixture(), test.attempt());
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.IDLE);
        assertThat(test.factoryCalls().get()).isEqualTo(1);
        assertWorkerSingleCreateAndGet(test, 2, 1);
    }

    @Test void gatewayWorkerUnavailablePrivateLinkStorageStillPostsVerifiedMoneyThroughExistingFinalizer() {
        var test = workerCase(true); var paid = new java.util.concurrent.atomic.AtomicReference<Instant>();
        org.mockito.Mockito.when(test.provider().createHostedCheckout(org.mockito.ArgumentMatchers.any(PaymentProviderAdapter.CheckoutRequest.class)))
                .thenAnswer(invocation -> {
                    assertWorkerExternalBoundary(); assertWorkerCommittedCreate(test);
                    assertThat(invocation.getArgument(0, PaymentProviderAdapter.CheckoutRequest.class).payerPhone()).isEqualTo("0000000000");
                    paid.set(workerPaymentInstant()); return hostedLink(test.attempt(), "synthetic_worker_checkout");
                });
        configureWorkerPaidGets(test, paid);
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, test.attempt())).isZero();
        assertThat(owner.queryForObject("SELECT last_error_code FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, test.attempt()))
                .isEqualTo("GATEWAY_CREATE_LINK_OUTCOME_UNKNOWN");
        assertNoFinancialPosting(test.fixture()); assertOriginalReservation(test.fixture(), test.attempt());
        awaitWorkerRetry(test.attempt());
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertOneFinalizedReceipt(test.fixture(), test.attempt());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, test.attempt())).isZero();
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE attempt_id=?", String.class, test.attempt()))
                .isEqualTo(paid.get().toString());
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.IDLE);
        assertWorkerSingleCreateAndGet(test, 1, 1);
    }

    @Test void gatewayWorkerLateConfirmationUsesClosedDayReviewedHoldWithOriginalTime() {
        var test = workerCase(false); var f = test.fixture(); var paid = new java.util.concurrent.atomic.AtomicReference<Instant>();
        org.mockito.Mockito.when(test.provider().createHostedCheckout(org.mockito.ArgumentMatchers.any(PaymentProviderAdapter.CheckoutRequest.class)))
                .thenAnswer(invocation -> {
                    assertWorkerExternalBoundary(); assertWorkerCommittedCreate(test);
                    paid.set(workerPaymentInstant()); return hostedLink(test.attempt(), "synthetic_worker_checkout");
                });
        configureWorkerPaidGets(test, paid);
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        var originalMarker = workerMarker(test.attempt()); var finance = finalizerFinance();
        LocalDate original = LocalDate.ofInstant(paid.get(), ZoneId.of("Asia/Kolkata"));
        var declarations = List.of("CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER").stream()
                .map(method -> new FinanceController.TenderDeclaration(method, BigDecimal.ZERO)).toList();
        var close = actor(f, test.book().maker(), () -> finance.proposeDayClose(test.book().maker(), new FinanceController.DayCloseRequest(
                f.school(), original, declarations, "Synthetic day close before delayed worker confirmation", null)));
        actor(f, test.book().reviewer(), () -> finance.decideDayClose(test.book().reviewer(), close.id(), new FinanceController.DayCloseDecisionRequest(
                f.school(), "APPROVED", "Synthetic independent closed-day review", 0)));
        String closedSnapshot = owner.queryForObject("SELECT row_to_json(source)::text FROM fee_day_closes source WHERE id=?", String.class, close.id());
        awaitWorkerRetry(test.attempt());
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        UUID capture = owner.queryForObject("SELECT id FROM fee_gateway_captures WHERE attempt_id=?", UUID.class, test.attempt());
        assertThat(owner.queryForObject("SELECT classification FROM fee_gateway_captures WHERE id=?", String.class, capture)).isEqualTo("REVIEW_REQUIRED");
        assertThat(owner.queryForObject("SELECT closed_day_close_id FROM fee_gateway_captures WHERE id=?", UUID.class, capture)).isEqualTo(close.id());
        assertThat(owner.queryForObject("SELECT paid_at_exact FROM fee_gateway_captures WHERE id=?", String.class, capture)).isEqualTo(paid.get().toString());
        assertThat(owner.queryForObject("SELECT paid_at FROM fee_gateway_captures WHERE id=?", java.sql.Timestamp.class, capture).toInstant()).isEqualTo(GatewayExactInstant.floor(paid.get()));
        assertThat(owner.queryForObject("SELECT original_business_date FROM fee_gateway_captures WHERE id=?", LocalDate.class, capture)).isEqualTo(original);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=? AND resolution_type='HOLD' AND closed_day_close_id=? AND actor_kind='SYSTEM'",
                Integer.class, capture, close.id())).isEqualTo(1);
        assertThatThrownBy(() -> actor(f, test.book().reviewer(), () -> finance.postReviewedGatewayCapture(test.book().reviewer(), capture,
                new FinanceController.GatewayCapturePostingRequest(f.school(), original, "Synthetic closed-date posting refused"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT row_to_json(source)::text FROM fee_day_closes source WHERE id=?", String.class, close.id())).isEqualTo(closedSnapshot);
        assertThat(workerMarker(test.attempt())).isEqualTo(originalMarker);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempts WHERE id=?", String.class, test.attempt())).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?", String.class, test.attempt())).isEqualTo("ACTIVE");
        assertNoFinancialPosting(f);
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.IDLE);
        assertWorkerSingleCreateAndGet(test, 1, 1);
    }

    @Test void inboxWorkerLateGetRecoversTerminalOriginalWorkWithoutResettingItsOneUseCreateOrCompletedHistory() throws Exception {
        for (boolean originallyPosted : List.of(false, true)) {
            var test = inboxCase(originallyPosted, false);
            UUID inbox = signedInbox(test, test.attempt(), "SUCCESS", false);
            String original = test.originalWork();
            assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
            assertOneFinalizedReceipt(test.fixture(), test.attempt());
            assertThat(inboxWorkJson(test.attempt())).isEqualTo(original);
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("COMPLETE");
            assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action='finance.gateway.payment.posted'",
                    Integer.class, test.fixture().school())).isEqualTo(1);
            assertInboxGetOnly(test, 1, 1);
        }
    }

    @Test void inboxWorkerDistinctAndDuplicateConcurrentCallbacksReuseOneExactCaptureAndNumberedReceipt() throws Exception {
        var test = inboxCase(false, false);
        UUID first = signedInbox(test, test.attempt(), "SUCCESS", false);
        assertThat(signedInbox(test, test.attempt(), "SUCCESS", false)).isEqualTo(first);
        UUID second = signedInbox(test, test.attempt(), "SUCCESS", true);
        assertThat(second).isNotEqualTo(first);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return test.worker().runNext(RECOVERY_ROUTE); });
            var b = workers.submit(() -> { start.await(); return test.worker().runNext(RECOVERY_ROUTE); });
            start.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
            assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        }
        assertOneFinalizedReceipt(test.fixture(), test.attempt());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=? AND status='COMPLETE'", Integer.class, test.fixture().school())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=? AND payment_status='SUCCESS'", Integer.class, test.attempt())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=(SELECT id FROM fee_gateway_captures WHERE attempt_id=?) AND resolution_type='POSTED'", Integer.class, test.attempt())).isEqualTo(1);
        assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork());
        assertInboxGetOnly(test, 2, 2);
    }

    @Test void inboxWorkerAuthenticFailedAndDroppedCallbackMetadataCannotEraseFreshGetVerifiedSuccess() throws Exception {
        var test = inboxCase(false, false);
        for (String status : List.of("FAILED", "USER_DROPPED")) {
            UUID inbox = signedInbox(test, test.attempt(), status, false);
            String source = owner.queryForObject("SELECT event_type FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox);
            assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
            assertOneFinalizedReceipt(test.fixture(), test.attempt());
            assertThat(owner.queryForObject("SELECT event_type FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo(source);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=? AND payment_status='SUCCESS' AND captured", Integer.class, test.attempt())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=? AND payment_status IN ('FAILED','USER_DROPPED')", Integer.class, test.attempt())).isZero();
        }
        assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork());
        assertInboxGetOnly(test, 2, 2);
    }

    @Test void inboxWorkerOnlyUnambiguousPreviouslyRetainedOrderHintCanChooseOriginalSameIdFullGet() throws Exception {
        var test = inboxCase(false, true);
        UUID inbox = signedInbox(test, null, "SUCCESS", false);
        assertThat(owner.queryForObject("SELECT checkout_reference FROM fee_gateway_webhook_inbox WHERE id=?", UUID.class, inbox)).isNull();
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertOneFinalizedReceipt(test.fixture(), test.attempt());
        assertThat(owner.queryForObject("SELECT checkout_reference FROM fee_gateway_webhook_inbox WHERE id=?", UUID.class, inbox)).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_checkout_observations WHERE attempt_id=? AND checkout_status='PAID' AND discovery_complete", Integer.class, test.attempt())).isEqualTo(1);
        assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork());
        assertInboxGetOnly(test, 1, 1);
    }

    @Test void inboxWorkerUnknownAndAmbiguousOrderAssociationKeepsUnboundMoneyAndRetriesWithoutGuessingAChild() throws Exception {
        for (boolean ambiguous : List.of(false, true)) {
            var test = inboxCase(false, ambiguous); var f = test.fixture();
            String secondOriginal = null; UUID secondAttempt = null;
            if (ambiguous) {
                secondAttempt = prepareWork(f);
                var secondClaim = dispatchedFinalizerClaim(f, secondAttempt, test.work());
                var zero = inboxTerminalZero(f, secondAttempt, true);
                assertThat(test.finalizer().finalizeObservation(RECOVERY_ROUTE, secondClaim, zero)).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
                secondOriginal = inboxWorkJson(secondAttempt);
            }
            UUID inbox = signedInbox(test, null, "SUCCESS", false);
            assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("RETRY");
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE school_id=? AND attempt_id IS NULL AND payment_status='SUCCESS' AND captured", Integer.class, f.school())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE school_id=?", Integer.class, f.school())).isZero();
            assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
            assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork());
            if (secondAttempt != null) assertThat(inboxWorkJson(secondAttempt)).isEqualTo(secondOriginal);
            assertInboxGetOnly(test, 0, 1);
        }
    }

    @Test void inboxWorkerExactInactiveProviderSchoolAndOrganisationRetainReadFactsForReviewWithoutActivation() throws Exception {
        for (String inactive : List.of("provider", "school", "organisation")) {
            var test = inboxCase(false, false); var f = test.fixture();
            UUID inbox = signedInbox(test, test.attempt(), "SUCCESS", false);
            if (inactive.equals("provider")) owner.update("UPDATE payment_provider_configs SET status='INACTIVE',updated_at=now(),row_version=row_version+1 WHERE id=?", f.provider());
            else if (inactive.equals("school")) owner.update("UPDATE schools SET status='SUSPENDED',updated_at=now() WHERE id=?", f.school());
            else owner.update("UPDATE organisations SET status='SUSPENDED',updated_at=now() WHERE id=?", f.org());
            assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("REVIEW_REQUIRED");
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=? AND payment_status='SUCCESS' AND captured", Integer.class, test.attempt())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, test.attempt())).isZero();
            assertThat(owner.queryForObject("SELECT status FROM payment_provider_configs WHERE id=?", String.class, f.provider())).isEqualTo(inactive.equals("provider") ? "INACTIVE" : "ACTIVE");
            assertThat(owner.queryForObject("SELECT status FROM schools WHERE id=?", String.class, f.school())).isEqualTo(inactive.equals("school") ? "SUSPENDED" : "ACTIVE");
            assertThat(owner.queryForObject("SELECT status FROM organisations WHERE id=?", String.class, f.org())).isEqualTo(inactive.equals("organisation") ? "SUSPENDED" : "ACTIVE");
            assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork());
            assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt()); assertInboxGetOnly(test, 1, 1);
        }
    }

    @Test void inboxRealLeaseExpiryDuringIndependentGetRetainsOriginalMoneyWithoutPostingOrGuessedCompletion() throws Exception {
        var test = inboxCase(false, false); var f = test.fixture();
        UUID inbox = signedInbox(test, test.attempt(), "SUCCESS", false);
        var shortWork = finalizerQueue(5, false);
        var claim = background(f, () -> shortWork.claimNextWebhook(f.binding()));
        var snapshot = background(f, () -> test.dispatch().loadForReadRecovery(RECOVERY_ROUTE, claim)).attempt().orElseThrow();
        var entered = new CountDownLatch(1); var released = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertInboxProviderBoundary(test); entered.countDown();
            if (!released.await(10, TimeUnit.SECONDS)) throw new AssertionError("Synthetic inbox GET was not released");
            assertInboxProviderBoundary(test); return List.of(inboxPayment(test.paid()));
        }).when(test.provider()).fetchPayments("synthetic_order");
        try (var external = Executors.newSingleThreadExecutor()) {
            var read = external.submit(() -> new GatewayProviderDiscovery(clock).read(test.provider(), snapshot, Duration.ofSeconds(10)));
            try { assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); awaitDatabaseTime(claim.leaseUntil()); }
            finally { released.countDown(); }
            var evidence = read.get(15, TimeUnit.SECONDS).evidence();
            assertThat(evidence.orderReads().getFirst().payments()).contains(List.of(inboxPayment(test.paid())));
            assertThatThrownBy(() -> observationFinalizer(f.binding(), shortWork).finalizeInboxObservation(RECOVERY_ROUTE, claim, evidence)).isInstanceOf(IllegalStateException.class);
        }
        assertInboxLostLeaseEvidence(test, inbox, claim); assertInboxGetOnly(test, 1, 1);
    }

    @Test void inboxActualFinanceFenceWaitPastExpiryKeepsCommittedFactsAndRollsBackAllFinancialCompletion() throws Exception {
        var test = inboxCase(false, false); var f = test.fixture();
        UUID inbox = signedInbox(test, test.attempt(), "SUCCESS", false);
        try (var external = Executors.newSingleThreadExecutor(); var locked = owner.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            var factsCommitted = new CountDownLatch(1); var registered = new java.util.concurrent.atomic.AtomicBoolean();
            var shortWork = new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime), clock, Duration.ofSeconds(3)) {
                @Override public UUID retainPaymentObservation(ProviderBinding binding, UUID attemptId, PaymentProviderAdapter.PaymentObservation payment) {
                    UUID retained = super.retainPaymentObservation(binding, attemptId, payment);
                    if (registered.compareAndSet(false, true)) org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override public void afterCommit() {
                                    try (var statement = locked.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                                        statement.setString(1, f.school() + ":finance"); statement.execute(); factsCommitted.countDown();
                                    } catch (java.sql.SQLException failure) { throw new IllegalStateException("Synthetic inbox committed-facts fence failed"); }
                                }
                            });
                    return retained;
                }
            };
            var claim = background(f, () -> shortWork.claimNextWebhook(f.binding()));
            var evidence = inboxPaidEvidence(test);
            var completion = external.submit(() -> observationFinalizer(f.binding(), shortWork).finalizeInboxObservation(RECOVERY_ROUTE, claim, evidence));
            try {
                assertThat(factsCommitted.await(5, TimeUnit.SECONDS)).isTrue(); awaitBlockedFinalizerFinanceLock();
                assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=? AND captured", Integer.class, test.attempt())).isEqualTo(1);
                awaitDatabaseTime(claim.leaseUntil()); locked.commit();
                assertThatThrownBy(() -> completion.get(15, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
            } finally { locked.rollback(); }
            assertInboxLostLeaseEvidence(test, inbox, claim);
        }
    }

    @Test void inboxFullClaimScopeSiblingTenantAndCredentialRotationDenyReceiptWithoutRebindingOriginalEvidence() throws Exception {
        var test = inboxCase(false, false); var f = test.fixture();
        UUID inbox = signedInbox(test, test.attempt(), "SUCCESS", false);
        var claim = background(f, () -> test.work().claimNextWebhook(f.binding())); var evidence = inboxPaidEvidence(test);
        String originalInbox = owner.queryForObject("SELECT row_to_json(source)::text FROM fee_gateway_webhook_inbox source WHERE id=?", String.class, inbox);
        for (var forged : List.of(
                new GatewayWorkQueue.Claim(claim.kind(), inbox, f.binding(), UUID.randomUUID(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), false),
                new GatewayWorkQueue.Claim(claim.kind(), inbox, f.binding(), claim.token(), claim.startedAt().plusNanos(1), claim.leaseUntil(), claim.attemptNumber(), false),
                new GatewayWorkQueue.Claim(claim.kind(), inbox, f.binding(), claim.token(), claim.startedAt(), claim.leaseUntil().plusNanos(1), claim.attemptNumber(), false),
                new GatewayWorkQueue.Claim(claim.kind(), inbox, f.binding(), claim.token(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber()+1, false))) {
            assertThatThrownBy(() -> test.finalizer().finalizeInboxObservation(RECOVERY_ROUTE, forged, evidence)).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
        try (var ignored = TenantRequestContext.open(f.parent().userId(), f.org())) {
            assertThatThrownBy(() -> test.finalizer().finalizeInboxObservation(RECOVERY_ROUTE, claim, evidence)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> background(f, () -> test.finalizer().finalizeInboxObservation(RECOVERY_ROUTE, claim, evidence))).isInstanceOf(IllegalStateException.class);
        for (Fixture other : List.of(fixture(f.org()), fixture())) {
            var wrong = new GatewayWorkQueue.Claim(claim.kind(), inbox, other.binding(), claim.token(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), false);
            assertThatThrownBy(() -> test.finalizer().finalizeInboxObservation(RECOVERY_ROUTE, wrong, evidence)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> observationFinalizer(other.binding(), test.work()).finalizeInboxObservation(RECOVERY_ROUTE, wrong,
                    new GatewayDiscoveryEvidence.Discovery(other.binding(), evidence.checkout(), evidence.orderReads(), evidence.observedAt()))).isInstanceOf(IllegalStateException.class);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE school_id=?", Integer.class, other.school())).isZero();
            assertNoFinancialPosting(other);
        }
        owner.update("UPDATE payment_provider_configs SET credentials_secret_reference='synthetic:rotated',updated_at=now(),row_version=row_version+1 WHERE id=?", f.provider());
        assertThatThrownBy(() -> test.finalizer().finalizeInboxObservation(RECOVERY_ROUTE, claim, evidence)).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT row_to_json(source)::text FROM fee_gateway_webhook_inbox source WHERE id=?", String.class, inbox)).isEqualTo(originalInbox);
        assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork()); assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
    }

    @Test void inboxDelayedVerifiedSuccessAfterClosedOriginalDayPreservesActualTimeAndReviewedHoldWithoutReopening() throws Exception {
        var test = inboxCase(false, false); var f = test.fixture(); var finance = finalizerFinance();
        LocalDate original = test.paid().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        var declarations = List.of("CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER").stream()
                .map(method -> new FinanceController.TenderDeclaration(method, BigDecimal.ZERO)).toList();
        var close = actor(f, test.book().maker(), () -> finance.proposeDayClose(test.book().maker(), new FinanceController.DayCloseRequest(
                f.school(), original, declarations, "Synthetic original day closed before delayed inbox confirmation", null)));
        actor(f, test.book().reviewer(), () -> finance.decideDayClose(test.book().reviewer(), close.id(), new FinanceController.DayCloseDecisionRequest(
                f.school(), "APPROVED", "Synthetic independently approved closed day", 0)));
        String immutableClose = owner.queryForObject("SELECT row_to_json(record)::text FROM fee_day_closes record WHERE id=?", String.class, close.id());
        UUID inbox = signedInbox(test, test.attempt(), "SUCCESS", false);
        assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        UUID capture = owner.queryForObject("SELECT id FROM fee_gateway_captures WHERE attempt_id=?", UUID.class, test.attempt());
        assertThat(owner.queryForObject("SELECT closed_day_close_id FROM fee_gateway_captures WHERE id=?", UUID.class, capture)).isEqualTo(close.id());
        assertThat(owner.queryForObject("SELECT original_business_date FROM fee_gateway_captures WHERE id=?", LocalDate.class, capture)).isEqualTo(original);
        assertThat(owner.queryForObject("SELECT paid_at_exact FROM fee_gateway_captures WHERE id=?", String.class, capture)).isEqualTo(test.paid().toString());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=? AND resolution_type='HOLD' AND closed_day_close_id=? AND actor_kind='SYSTEM'", Integer.class, capture, close.id())).isEqualTo(1);
        assertThatThrownBy(() -> actor(f, f.parent(), () -> finance.postReviewedGatewayCapture(f.parent(), capture,
                new FinanceController.GatewayCapturePostingRequest(f.school(), original, "Synthetic denied guardian posting")))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(() -> actor(f, test.book().reviewer(), () -> finance.postReviewedGatewayCapture(test.book().reviewer(), capture,
                new FinanceController.GatewayCapturePostingRequest(f.school(), original, "Synthetic denied closed-day reviewed posting")))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT row_to_json(record)::text FROM fee_day_closes record WHERE id=?", String.class, close.id())).isEqualTo(immutableClose);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("REVIEW_REQUIRED");
        assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork()); assertNoFinancialPosting(f); assertInboxGetOnly(test, 1, 1);
    }

    @Test void inboxRevokedParentNeverErasesRealMoneyAndClosedAccountRequiresPreservedReviewedHold() throws Exception {
        for (boolean closed : List.of(false, true)) {
            var test = inboxCase(false, false); var f = test.fixture();
            UUID inbox = signedInbox(test, test.attempt(), "SUCCESS", false);
            owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=now(),row_version=row_version+1 WHERE id=?", f.parent().membershipIds().iterator().next());
            owner.update("UPDATE user_accounts SET status='SUSPENDED' WHERE id=?", f.parent().userId());
            owner.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED',updated_at=now() WHERE learner_id=?", f.learner());
            if (closed) owner.update("""
                    UPDATE learner_fee_accounts SET status='CLOSED',closed_by_membership_id=?,closed_at=now(),
                      close_reason='Synthetic closed account after original CREATE',row_version=row_version+1
                    WHERE id=(SELECT learner_fee_account_id FROM fee_gateway_attempts WHERE id=?)
                    """, test.book().maker().membershipIds().iterator().next(), test.attempt());
            assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(closed ? GatewayAttemptWorker.Step.REVIEW_REQUIRED : GatewayAttemptWorker.Step.COMPLETE);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=? AND captured AND payment_status='SUCCESS'", Integer.class, test.attempt())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, test.attempt())).isEqualTo(1);
            if (closed) {
                assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=(SELECT id FROM fee_gateway_captures WHERE attempt_id=?) AND resolution_type='HOLD'", Integer.class, test.attempt())).isEqualTo(1);
                assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?", String.class, test.attempt())).isEqualTo("ACTIVE");
                assertNoFinancialPosting(f);
            } else assertOneFinalizedReceipt(f, test.attempt());
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo(closed ? "REVIEW_REQUIRED" : "COMPLETE");
            assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork()); assertInboxGetOnly(test, 1, 1);
        }
    }

    @Test void inboxLastCompletionFailureRollsBackReceiptCaptureLedgerAndAuditButKeepsCommittedGetFactsForRetry() throws Exception {
        var test = inboxCase(false, false); var f = test.fixture(); UUID inbox = signedInbox(test, test.attempt(), "SUCCESS", false);
        var claim = background(f, () -> test.work().claimNextWebhook(f.binding())); var evidence = inboxPaidEvidence(test);
        var sawRealReceipt = new java.util.concurrent.atomic.AtomicBoolean();
        var failing = new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime), clock, Duration.ofSeconds(90)) {
            @Override public boolean complete(Claim current, Outcome outcome, String safeCode, Duration retryAfter) {
                boolean finished = super.complete(current, outcome, safeCode, retryAfter);
                if (current.kind()==WorkKind.WEBHOOK && outcome==Outcome.COMPLETE && finished) {
                    sawRealReceipt.set(runtime.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?", Integer.class, f.school())==1);
                    return false; // Real last CAS executed; enclosing financial transaction must still roll back.
                }
                return finished;
            }
        };
        assertThatThrownBy(() -> observationFinalizer(f.binding(), failing).finalizeInboxObservation(RECOVERY_ROUTE, claim, evidence)).isInstanceOf(IllegalStateException.class);
        assertThat(sawRealReceipt).isTrue(); assertInboxLostLeaseEvidence(test, inbox, claim);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action='finance.gateway.payment.posted'", Integer.class, f.school())).isZero();
        assertThat(test.finalizer().finalizeInboxObservation(RECOVERY_ROUTE, claim, evidence)).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        assertOneFinalizedReceipt(f, test.attempt()); assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork());
    }

    @Test void inboxRefundCallbackRetainsImmutableRoutingForGovernedReviewWithoutPaymentGetOrRefundApproval() throws Exception {
        var test = inboxCase(false, false); var f = test.fixture();
        // Already-authenticated internal adapter fixtures only. Raw HMAC/HTTP
        // authentication is covered separately above; this is not a browser assertion.
        for (String type : List.of("REFUND_STATUS_WEBHOOK", "AUTO_REFUND_STATUS_WEBHOOK")) {
            String hash = type.equals("REFUND_STATUS_WEBHOOK") ? "d".repeat(64) : "e".repeat(64);
            var event = new PaymentProviderAdapter.VerifiedWebhook("CASHFREE", hash, type, null, "synthetic_order", "synthetic_payment", "synthetic_refund");
            UUID inbox = background(f, () -> test.work().retainVerifiedWebhook(f.binding(), event));
            var original = owner.queryForMap("SELECT event_type,order_reference,payment_reference,refund_reference,body_sha256,received_at FROM fee_gateway_webhook_inbox WHERE id=?", inbox);
            assertThat(test.worker().runNext(RECOVERY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
            assertThat(owner.queryForMap("SELECT event_type,order_reference,payment_reference,refund_reference,body_sha256,received_at FROM fee_gateway_webhook_inbox WHERE id=?", inbox)).isEqualTo(original);
            assertThat(owner.queryForObject("SELECT last_error_code FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("GATEWAY_REFUND_RECONCILIATION_REQUIRED");
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork()); assertNoFinancialPosting(f);
        org.mockito.Mockito.verifyNoInteractions(test.provider());
    }

    private InboxCase inboxCase(boolean originallyPosted, boolean retainedOrderHint) {
        Fixture f = fixture(); var book = configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(90, true); UUID attempt = prepareWork(f);
        var originalClaim = dispatchedFinalizerClaim(f, attempt, work);
        Instant paid = workerPaymentInstant();
        var initial = originallyPosted ? inboxPaidEvidence(f, attempt, paid) : inboxTerminalZero(f, attempt, retainedOrderHint);
        assertThat(observationFinalizer(f.binding(), work).finalizeObservation(RECOVERY_ROUTE, originalClaim, initial))
                .isEqualTo(originallyPosted ? GatewayWorkQueue.Outcome.COMPLETE : GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        String originalWork = inboxWorkJson(attempt);
        var provider = org.mockito.Mockito.mock(PaymentProviderAdapter.class);
        var route = new GatewayProviderRegistry.Route(RECOVERY_ROUTE, f.org(), f.school(), f.provider(), "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.TEST, f.binding().merchantReference(), f.binding().credentialsReference(),
                "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        var registry = new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false, true, false, false, List.of(route)),
                URI.create(properties.publicUrl()), clock,
                name -> { assertWorkerExternalBoundary(); return "synthetic-inbox-only-not-a-real-credential"; },
                (trusted, id, secret, origin, observedClock) -> { assertWorkerExternalBoundary(); assertThat(trusted).isEqualTo(route); return provider; });
        var named = new NamedParameterJdbcTemplate(runtime); var attemptDispatch = finalizerDispatch(registry);
        var inboxDispatch = new GatewayInboxDispatch(named, registry, attemptDispatch);
        var finalizer = new GatewayObservationFinalizer(registry, named, work, attemptDispatch, finalizerFinance(), transactions.getTransactionManager(), inboxDispatch);
        var discovery = new GatewayProviderDiscovery(clock);
        var refundReads = new GatewayRefundReadStore(named);
        var refundRecovery = new GatewayRefundInboxRecovery(named,inboxDispatch,work,refundReads,
                new GatewayRefundSuccessStore(named,refundReads),discovery,transactions.getTransactionManager());
        var worker = new GatewayWebhookWorker(registry, named, work, inboxDispatch, discovery, finalizer, refundRecovery, transactions.getTransactionManager(), clock);
        var test = new InboxCase(f, book, attempt, work, inboxDispatch, finalizer, worker, provider, paid, originalWork);
        org.mockito.Mockito.when(provider.providerCode()).thenAnswer(invocation -> { assertInboxProviderBoundary(test); return "CASHFREE"; });
        org.mockito.Mockito.when(provider.lookupHostedCheckout(attempt.toString())).thenAnswer(invocation -> {
            assertInboxProviderBoundary(test);
            return new PaymentProviderAdapter.HostedCheckoutLookup(inboxPaidEvidence(test).checkout(), Optional.empty());
        });
        org.mockito.Mockito.when(provider.fetchPayments("synthetic_order")).thenAnswer(invocation -> {
            assertInboxProviderBoundary(test); return List.of(inboxPayment(paid));
        });
        return test;
    }
    private GatewayDiscoveryEvidence.Discovery inboxTerminalZero(Fixture f, UUID attempt, boolean orderHint) {
        return finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.EXPIRED, "0",
                orderHint ? List.of("synthetic_order") : List.of(),
                orderHint ? List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of())) : List.of(), databaseInstant());
    }
    private GatewayDiscoveryEvidence.Discovery inboxPaidEvidence(InboxCase test) { return inboxPaidEvidence(test.fixture(), test.attempt(), test.paid()); }
    private GatewayDiscoveryEvidence.Discovery inboxPaidEvidence(Fixture f, UUID attempt, Instant paid) {
        return finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.PAID, "500.00", List.of("synthetic_order"),
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of(inboxPayment(paid)))), databaseInstant());
    }
    private static PaymentProviderAdapter.PaymentObservation inboxPayment(Instant paid) {
        return finalizerPayment("synthetic_payment", paid);
    }
    private UUID signedInbox(InboxCase test, UUID checkout, String status, boolean distinctBody) throws Exception {
        byte[] wire = webhookBytes(checkout, status);
        if (distinctBody) wire = (new String(wire, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        // Real original-byte HMAC verification and real independent non-owner
        // intake transaction. No network call, gateway merchant or real credential.
        return webhookIntake(test.fixture().binding()).accept(RECOVERY_ROUTE, wire, webhookSignature(wire, WEBHOOK_TIME), WEBHOOK_TIME).inboxId();
    }
    private String inboxWorkJson(UUID attempt) {
        return owner.queryForObject("SELECT row_to_json(original)::text FROM fee_gateway_attempt_work original WHERE attempt_id=?", String.class, attempt);
    }
    private void assertInboxProviderBoundary(InboxCase test) {
        assertWorkerExternalBoundary();
        assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork());
        // The independent owner sees only committed admissions. Never use this
        // oracle connection for product gateway or financial mutations.
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_webhook_inbox WHERE school_id=? AND status='LEASED' AND lease_token IS NOT NULL",
                Integer.class, test.fixture().school())).isGreaterThanOrEqualTo(1);
    }
    private void assertInboxLostLeaseEvidence(InboxCase test, UUID inbox, GatewayWorkQueue.Claim claim) {
        var f = test.fixture();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_payment_observations WHERE attempt_id=? AND captured AND payment_status='SUCCESS'", Integer.class, test.attempt())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE attempt_id=? AND captured", String.class, test.attempt())).isEqualTo(test.paid().toString());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, test.attempt())).isZero();
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("LEASED");
        assertThat(owner.queryForObject("SELECT lease_token FROM fee_gateway_webhook_inbox WHERE id=?", UUID.class, inbox)).isEqualTo(claim.token());
        assertThat(inboxWorkJson(test.attempt())).isEqualTo(test.originalWork()); assertNoFinancialPosting(f); assertOriginalReservation(f, test.attempt());
    }
    private static void assertInboxGetOnly(InboxCase test, int lookups, int paymentLists) {
        org.mockito.Mockito.verify(test.provider(), org.mockito.Mockito.times(lookups)).lookupHostedCheckout(test.attempt().toString());
        org.mockito.Mockito.verify(test.provider(), org.mockito.Mockito.times(paymentLists)).fetchPayments("synthetic_order");
        assertProviderDiscoveryNeverCreates(test.provider());
    }
    private record InboxCase(Fixture fixture, GatewayBook book, UUID attempt, GatewayWorkQueue work,
            GatewayInboxDispatch dispatch, GatewayObservationFinalizer finalizer, GatewayWebhookWorker worker,
            PaymentProviderAdapter provider, Instant paid, String originalWork) {}

    private WorkerCase workerCase(boolean unavailableHostedKey) {
        Fixture f = fixture(); var book = configureGatewayBook(f, "CASH", "OTHER", true); UUID attempt = prepareWork(f);
        var provider = org.mockito.Mockito.mock(PaymentProviderAdapter.class);
        org.mockito.Mockito.when(provider.providerCode()).thenAnswer(invocation -> { assertWorkerExternalBoundary(); return "CASHFREE"; });
        var factoryCalls = new java.util.concurrent.atomic.AtomicInteger();
        var route = new GatewayProviderRegistry.Route(RECOVERY_ROUTE, f.org(), f.school(), f.provider(), "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.TEST, f.binding().merchantReference(), f.binding().credentialsReference(),
                "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        var registry = new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(true, true, false, false, List.of(route)),
                URI.create(properties.publicUrl()), clock,
                name -> { assertWorkerExternalBoundary(); return "synthetic-worker-only-not-a-real-credential"; },
                (trusted, id, secret, origin, observedClock) -> {
                    assertWorkerExternalBoundary(); assertThat(trusted).isEqualTo(route); factoryCalls.incrementAndGet(); return provider;
                });
        var named = new NamedParameterJdbcTemplate(runtime); var work = finalizerQueue(90, true);
        var dispatch = finalizerDispatch(registry); var goodCipher = new SensitiveValueCipher(properties, new SecureRandom());
        var commands = new GatewayCreateCommandAdmission(registry, dispatch, named, goodCipher, transactions.getTransactionManager());
        var storeProperties = unavailableHostedKey ? new ApplicationProperties(properties.publicUrl(), List.of(), Optional.empty(), Optional.empty(),
                new ApplicationProperties.Security("synthetic_session", true, Duration.ofHours(1), Duration.ofHours(2), Duration.ofMinutes(10), null,
                        null, Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofDays(1), "Synthetic school"),
                new ApplicationProperties.Bootstrap(false, null, null)) : properties;
        var store = new GatewayHostedCheckoutStore(registry, named, new SensitiveValueCipher(storeProperties, new SecureRandom()), transactions.getTransactionManager());
        var finalizer = new GatewayObservationFinalizer(registry, named, work, dispatch, finalizerFinance(), transactions.getTransactionManager());
        var worker = new GatewayAttemptWorker(registry, named, work, dispatch, commands, new GatewayProviderDiscovery(clock), store,
                finalizer, transactions.getTransactionManager(), clock);
        return new WorkerCase(f, book, attempt, worker, provider, factoryCalls);
    }
    private void configureWorkerPaidGets(WorkerCase test, java.util.concurrent.atomic.AtomicReference<Instant> paid) {
        org.mockito.Mockito.when(test.provider().lookupHostedCheckout(test.attempt().toString())).thenAnswer(invocation -> {
            assertWorkerExternalBoundary(); assertWorkerCommittedCreate(test); return workerPaidLookup(test);
        });
        org.mockito.Mockito.when(test.provider().fetchPayments("synthetic_order")).thenAnswer(invocation -> {
            assertWorkerExternalBoundary(); assertWorkerCommittedCreate(test);
            return List.of(finalizerPayment("synthetic_worker_payment", paid.get()));
        });
    }
    private PaymentProviderAdapter.HostedCheckoutLookup workerPaidLookup(WorkerCase test) {
        var checkout = finalizerDiscovery(test.fixture(), test.attempt(), PaymentProviderAdapter.CheckoutStatus.PAID, "500.00",
                List.of("synthetic_order"), List.of(), databaseInstant()).checkout();
        return new PaymentProviderAdapter.HostedCheckoutLookup(checkout, Optional.of(hostedLink(test.attempt(), "synthetic_worker_checkout")));
    }
    private Instant workerPaymentInstant() {
        Instant paid = databaseInstant().plusNanos(500); awaitDatabaseTime(paid.plusNanos(500)); return paid;
    }
    private void awaitWorkerRetry(UUID attempt) {
        Instant next = owner.queryForObject("SELECT next_attempt_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, attempt).toInstant();
        awaitDatabaseTime(next);
    }
    private java.util.Map<String,Object> workerMarker(UUID attempt) {
        return owner.queryForMap("SELECT create_dispatched_at,create_dispatched_by_lease_token FROM fee_gateway_attempt_work WHERE attempt_id=?", attempt);
    }
    private void assertWorkerCommittedCreate(WorkerCase test) {
        var marker = workerMarker(test.attempt());
        assertThat(marker.get("create_dispatched_at")).isNotNull(); assertThat(marker.get("create_dispatched_by_lease_token")).isNotNull();
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, test.attempt())).isEqualTo("LEASED");
    }
    private static void assertWorkerExternalBoundary() {
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }
    private static void assertWorkerSingleCreateAndGet(WorkerCase test, int lookups, int paymentLists) {
        org.mockito.Mockito.verify(test.provider()).createHostedCheckout(org.mockito.ArgumentMatchers.any(PaymentProviderAdapter.CheckoutRequest.class));
        org.mockito.Mockito.verify(test.provider(), org.mockito.Mockito.times(lookups)).lookupHostedCheckout(test.attempt().toString());
        org.mockito.Mockito.verify(test.provider(), org.mockito.Mockito.times(paymentLists)).fetchPayments("synthetic_order");
        org.mockito.Mockito.verify(test.provider(), org.mockito.Mockito.never()).createHostedCheckout(org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(BigDecimal.class), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(URI.class));
        org.mockito.Mockito.verify(test.provider(), org.mockito.Mockito.never()).cancelCheckout(org.mockito.ArgumentMatchers.any(PaymentProviderAdapter.CancellationRequest.class));
    }
    private record WorkerCase(Fixture fixture, GatewayBook book, UUID attempt, GatewayAttemptWorker worker,
            PaymentProviderAdapter provider, java.util.concurrent.atomic.AtomicInteger factoryCalls) {}

    private HostedCase hostedCase(int seconds) {
        Fixture f = fixture(); configureGatewayBook(f, "CASH", "OTHER", true);
        var work = finalizerQueue(seconds, false); UUID attempt = prepareWork(f);
        return new HostedCase(f, work, attempt, dispatchedFinalizerClaim(f, attempt, work));
    }
    private GatewayHostedCheckoutStore hostedStore(Fixture f, SensitiveValueCipher cipher) {
        return new GatewayHostedCheckoutStore(finalizerRegistry(f.binding()), new NamedParameterJdbcTemplate(runtime), cipher,
                transactions.getTransactionManager());
    }
    private static PaymentProviderAdapter.HostedCheckout hostedLink(UUID attempt, String suffix) {
        return new PaymentProviderAdapter.HostedCheckout(attempt.toString(), URI.create("https://payments-test.cashfree.com/links/" + suffix));
    }
    private GatewayProviderDiscovery.ReadResult hostedRead(Fixture f, UUID attempt, PaymentProviderAdapter.HostedCheckout hosted, Instant observed) {
        return new GatewayProviderDiscovery.ReadResult(finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.CREATED,
                "0.00", List.of(), List.of(), observed), Optional.of(hosted));
    }
    private java.util.Map<String,Object> hostedRow(UUID id) {
        return owner.queryForMap("SELECT * FROM fee_gateway_hosted_checkouts WHERE id=?", id);
    }
    private int hostedVisibleCount(UUID id) {
        return runtime.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE id=?", Integer.class, id);
    }
    private static void assertHostedRejected(java.util.concurrent.Callable<?> work, String privateText) {
        assertThatThrownBy(work::call).isInstanceOf(IllegalStateException.class).hasNoCause()
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(privateText, "synthetic-private-key", "synthetic-private-token"));
    }
    private UUID addHostedGuardian(Fixture f, UUID learner) {
        UUID user = UUID.randomUUID(), member = UUID.randomUUID(), guardian = UUID.randomUUID();
        owner.update("INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status) VALUES(?,?,?,'Synthetic second guardian','not-a-login-hash','ACTIVE')",
                user, "synthetic-" + user + "@example.test", "synthetic-" + user + "@example.test");
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())", member, user, f.org(), f.school());
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000006')", member);
        owner.update("INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,created_at,updated_at,user_account_id) VALUES(?,?,?,'Synthetic second guardian',?,'ACTIVE',now(),now(),?)",
                guardian, f.org(), f.school(), "synthetic-" + user + "@example.test", user);
        owner.update("INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,relationship_code,portal_access_status,created_at,updated_at) VALUES(?,?,?,?,'PARENT_GUARDIAN','ACTIVE',now(),now())", learner, guardian, f.org(), f.school());
        return user;
    }
    private UUID rawHostedCopy(Fixture f, UUID source, Instant projection, String exact, UUID token, String retainedExpression) {
        if (!Set.of("now()", "'infinity'::timestamptz").contains(retainedExpression)) throw new IllegalArgumentException();
        UUID id = UUID.randomUUID();
        background(f, () -> runtime.update("""
                INSERT INTO fee_gateway_hosted_checkouts(id,organisation_id,school_id,attempt_id,learner_fee_account_id,payment_provider_config_id,
                  provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,provider_checkout_reference,
                  url_ciphertext,url_nonce,url_sha256,source_kind,source_lease_token,source_lease_started_at,source_lease_until,source_attempt_count,
                  observed_at,observed_at_exact,retained_at)
                SELECT ?,organisation_id,school_id,attempt_id,learner_fee_account_id,payment_provider_config_id,provider_code,provider_environment,
                  merchant_account_reference,credentials_secret_reference,provider_checkout_reference,url_ciphertext,url_nonce,?,source_kind,?,
                  source_lease_started_at,source_lease_until,source_attempt_count,?,?,
                """ + retainedExpression + " FROM fee_gateway_hosted_checkouts WHERE id=?", id, id.toString().replace("-", "").repeat(2), token,
                GatewayExactInstant.parameter(projection), exact, source));
        return id;
    }
    private java.util.Map<String,List<String>> hostedCheckoutUpgradeHistory() {
        var snapshot = new java.util.LinkedHashMap<String,List<String>>();
        for (var entry : java.util.Map.of("fee_gateway_attempts", legacyInstantAttempt, "fee_gateway_payment_observations", legacyInstantFact,
                "fee_gateway_checkout_observations", legacyInstantCheckout, "fee_gateway_captures", legacyInstantCapture).entrySet()) {
            // The dedicated V071->V072 proof compares every original field;
            // V077's separate tested selection marker is additive metadata.
            snapshot.put(entry.getKey(), owner.queryForList("SELECT (to_jsonb(source)-'account_selection_mode')::text FROM " + entry.getKey() + " source WHERE id=?", String.class, entry.getValue()));
        }
        snapshot.put("exact_v071_fact", owner.queryForList("SELECT to_jsonb(source)::text FROM fee_gateway_payment_observations source WHERE id=?", String.class, hostedCheckoutUpgradeFact));
        for (String table : List.of("fee_payments", "fee_ledger_entries", "fee_receipt_sequences", "audit_events")) {
            snapshot.put(table, owner.queryForList("SELECT to_jsonb(source)::text FROM " + table + " source WHERE school_id=? ORDER BY to_jsonb(source)::text", String.class, legacyInstantFixture.school()));
        }
        return snapshot;
    }
    private record HostedCase(Fixture fixture, GatewayWorkQueue work, UUID attempt, GatewayWorkQueue.Claim claim) {}

    @Test void exactGatewayForwardUpgradePreservesCommittedLegacyProofAndAppendsOnlyANewNamespacedRetry() {
        assertThat(exactInstantUpgradeCount).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='071'",Boolean.class)).isTrue();
        assertLegacyInstantHistory();
        assertThat(background(legacyInstantFixture,()->runtime.queryForObject(
                "SELECT gateway_receipt_evidence_matches(?,?,?)",Boolean.class,
                legacyInstantCapture,legacyInstantFixture.org(),legacyInstantFixture.school()))).isTrue();
        var oldFact = new PaymentProviderAdapter.PaymentObservation("synthetic_legacy_order","synthetic_legacy_payment",
                new BigDecimal("500.00"),"INR",PaymentProviderAdapter.PaymentStatus.SUCCESS,true,legacyInstantPaid,"upi");
        UUID newFact = background(legacyInstantFixture,()->queue.retainPaymentObservation(
                legacyInstantFixture.binding(),legacyInstantAttempt,oldFact));
        assertThat(newFact).isNotEqualTo(legacyInstantFact);
        assertThat(background(legacyInstantFixture,()->queue.retainPaymentObservation(
                legacyInstantFixture.binding(),legacyInstantAttempt,oldFact))).isEqualTo(newFact);
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE id=?",String.class,newFact))
                .isEqualTo(legacyInstantPaid.toString());
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE id=?",String.class,legacyInstantFact)).isNull();
        // Restrict the old snapshot to original IDs: an additive new GET row is not a history rewrite.
        assertLegacyInstantHistory();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE id=?",Integer.class,legacyInstantCapture)).isZero();
        Fixture other = fixture();
        assertThat(background(other,()->runtime.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE id=?",Integer.class,legacyInstantCapture))).isZero();
        assertThatThrownBy(()->background(legacyInstantFixture,()->runtime.update(
                "UPDATE fee_gateway_captures SET paid_at_exact=? WHERE id=?",legacyInstantPaid.toString(),legacyInstantCapture))).isInstanceOf(DataAccessException.class);
    }

    @Test void exactGatewaySqlHelpersRetainNumericNanosecondsValidateCanonicalSourcesAndRunWithoutPrivilegeBypass() {
        ProductionDatabaseSecurity.verify(runtime);
        for (String source : List.of("0001-01-01T00:00:00Z","1969-12-31T23:59:59.999999500Z",
                "2026-10-13T18:29:59.123Z","2026-10-13T18:29:59.123456Z","2026-10-13T18:29:59.999999500Z",
                "9999-12-31T23:59:59.999999999Z")) {
            Instant value = Instant.parse(source);
            BigDecimal expected = BigDecimal.valueOf(value.getEpochSecond()).multiply(new BigDecimal("1000000000"))
                    .add(BigDecimal.valueOf(value.getNano()));
            assertThat(runtime.queryForObject("SELECT gateway_instant_epoch_ns(?)",BigDecimal.class,source)).isEqualByComparingTo(expected);
            Instant fromHelper=runtime.queryForObject("SELECT gateway_instant_floor_us(?)",(row,n)->row.getObject(1,java.time.OffsetDateTime.class).toInstant(),source);
            assertThat(fromHelper).isEqualTo(GatewayExactInstant.floor(value));
            Instant fromParameter=runtime.queryForObject("SELECT CAST(? AS timestamptz)",
                    (row,n)->row.getObject(1,java.time.OffsetDateTime.class).toInstant(),GatewayExactInstant.parameter(value));
            assertThat(fromParameter).isEqualTo(GatewayExactInstant.floor(value));
        }
        for (String source : List.of("2026-02-30T12:00:00Z","2026-10-13T24:00:00Z","2026-10-13T12:00:60Z",
                "2026-10-13T12:00:00.000Z","2026-10-13T12:00:00.123000Z","2026-10-13T12:00:00.123456000Z",
                "2026-10-13T12:00:00.1Z","2026-10-13T17:30:00+05:30","0000-01-01T00:00:00Z","synthetic-private-invalid-payload")) {
            assertThatThrownBy(()->runtime.queryForObject("SELECT gateway_instant_epoch_ns(?)",BigDecimal.class,source))
                    .isInstanceOf(DataAccessException.class).satisfies(failure ->
                        assertThat(((DataAccessException)failure).getMostSpecificCause().getMessage())
                            .contains("gateway instant source is invalid").doesNotContain(source));
        }
        assertThat(runtime.queryForObject("""
                SELECT count(*) FROM pg_proc WHERE oid IN ('gateway_instant_epoch_ns(text)'::regprocedure,
                  'gateway_instant_floor_us(text)'::regprocedure) AND NOT prosecdef AND provolatile='i' AND proisstrict
                  AND has_function_privilege(current_user,oid,'EXECUTE')
                """,Integer.class)).isEqualTo(2);
        Fixture f=fixture();
        for (String original : List.of("0001-01-01T00:00:00.123456500Z","1582-10-04T23:59:59.999999500Z","1582-10-15T00:00:00.000000001Z")) {
            var fact=new PaymentProviderAdapter.PaymentObservation("synthetic_calendar_order","synthetic_calendar_"+UUID.randomUUID().toString().replace("-",""),
                    new BigDecimal("500.00"),"INR",PaymentProviderAdapter.PaymentStatus.SUCCESS,true,Instant.parse(original),"upi");
            UUID id=background(f,()->queue.retainPaymentObservation(f.binding(),null,fact));
            Instant retained=background(f,()->runtime.queryForObject("SELECT provider_paid_at_exact,provider_paid_at FROM fee_gateway_payment_observations WHERE id=?",
                    (row,n)->GatewayExactInstant.read(row,"provider_paid_at_exact","provider_paid_at"),id));
            assertThat(retained).isEqualTo(Instant.parse(original));
        }
        assertNoFinancialPosting(f);
    }

    @Test void exactGatewayInsertGuardsRejectMissingMalformedRoundedFutureAndMixedOriginalSources() {
        Fixture f=fixture(); UUID attempt=prepareWork(f);
        Instant base=databaseInstant();
        Instant observed=base.plusNanos(100), laterPaid=base.plusNanos(200);
        awaitDatabaseTime(base.plusNanos(1000));
        assertThatThrownBy(()->rawExactFact(f,null,base)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->rawExactFact(f,"synthetic-invalid-private-source",base)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->rawExactFact(f,base.plusNanos(500).toString(),base.plusNanos(1000))).isInstanceOf(DataAccessException.class);
        Instant future=databaseInstant().plusSeconds(60);
        assertThatThrownBy(()->rawExactFact(f,future.toString(),future)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->rawExactCheckout(f,attempt,null,base)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->rawExactCheckout(f,attempt,base.plusNanos(500).toString(),base.plusNanos(1000))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->rawExactCheckout(f,attempt,future.toString(),future)).isInstanceOf(DataAccessException.class);
        UUID proof=rawExactCheckout(f,attempt,observed.toString(),base);
        assertThatThrownBy(()->rawExactCapture(f,attempt,proof,null,base)).isInstanceOf(DataAccessException.class);
        // Both project to precisely the same microsecond. Full originals still
        // prove payment was AFTER the observation, and must reject the proof.
        assertThatThrownBy(()->rawExactCapture(f,attempt,proof,laterPaid.toString(),base))
                .isInstanceOf(DataAccessException.class).satisfies(failure ->
                    assertThat(((DataAccessException)failure).getMostSpecificCause().getMessage()).contains("exact ordered original instants"));
        assertThatThrownBy(()->rawExactCapture(legacyInstantFixture,legacyInstantAttempt,legacyInstantCheckout,
                legacyInstantPaid.toString(),legacyInstantPaid)).isInstanceOf(DataAccessException.class).satisfies(failure ->
                    assertThat(((DataAccessException)failure).getMostSpecificCause().getMessage()).contains("mixed legacy evidence"));
        UUID valid=rawExactCapture(f,attempt,proof,observed.toString(),base);
        assertThat(background(f,()->runtime.queryForObject("SELECT gateway_receipt_evidence_matches(?,?,?)",Boolean.class,valid,f.org(),f.school()))).isTrue();
        assertNoFinancialPosting(f); assertOriginalReservation(f,attempt); assertLegacyInstantHistory();
    }

    private UUID rawExactFact(Fixture f,String exact,Instant projection) {
        UUID id=UUID.randomUUID();
        background(f,()->runtime.update("""
                INSERT INTO fee_gateway_payment_observations(id,organisation_id,school_id,payment_provider_config_id,
                  provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,order_reference,payment_reference,
                  amount,currency_code,payment_status,captured,provider_paid_at,provider_paid_at_exact,observed_at,evidence_sha256)
                VALUES(?,?,?,?,'CASHFREE','TEST',?,?,'synthetic_guard_order',?,500.00,'INR','SUCCESS',true,?,?,clock_timestamp(),?)
                """,id,f.org(),f.school(),f.provider(),f.binding().merchantReference(),f.binding().credentialsReference(),
                "synthetic_guard_"+id.toString().replace("-",""),java.sql.Timestamp.from(projection),exact,id.toString().replace("-","").repeat(2)));
        return id;
    }
    private UUID rawExactCheckout(Fixture f,UUID attempt,String exact,Instant projection) {
        UUID id=UUID.randomUUID();
        background(f,()->runtime.update("""
                INSERT INTO fee_gateway_checkout_observations(id,organisation_id,school_id,attempt_id,payment_provider_config_id,
                  provider_checkout_reference,attempt_reference,amount,currency_code,amount_paid,checkout_status,provider_expires_at,
                  order_references,pending_payment_count,successful_payment_count,discovery_complete,evidence_sha256,observed_at,observed_at_exact)
                SELECT ?,?,?,attempt.id,?,attempt.id,attempt.id,500.00,'INR',500.00,'PAID',attempt.expires_at,
                  ARRAY['synthetic_guard_order'],0,1,true,repeat('d',64),?,? FROM fee_gateway_attempts attempt WHERE id=?
                """,id,f.org(),f.school(),f.provider(),java.sql.Timestamp.from(projection),exact,attempt));
        return id;
    }
    private UUID rawExactCapture(Fixture f,UUID attempt,UUID proof,String exact,Instant projection) {
        UUID id=UUID.randomUUID();
        background(f,()->runtime.update("""
                INSERT INTO fee_gateway_captures(id,organisation_id,school_id,attempt_id,payment_provider_config_id,checkout_observation_id,
                  provider_code,provider_environment,merchant_account_reference,checkout_reference,order_reference,payment_reference,
                  amount,currency_code,paid_at,paid_at_exact,detected_at,original_business_date,classification,evidence_sha256)
                VALUES(?,?,?,?,?,?,'CASHFREE','TEST',?,?,'synthetic_guard_order',?,500.00,'INR',?,?,clock_timestamp(),?,'REVIEW_REQUIRED',repeat('e',64))
                """,id,f.org(),f.school(),attempt,f.provider(),proof,f.binding().merchantReference(),attempt.toString(),
                "synthetic_guard_"+id.toString().replace("-",""),java.sql.Timestamp.from(projection),exact,LocalDate.ofInstant(projection,ZoneId.of("Asia/Kolkata"))));
        return id;
    }

    private void seedLegacyInstantEvidence() throws Exception {
        legacyInstantFixture=fixture(); legacyInstantFact=UUID.randomUUID();
        legacyInstantCheckout=UUID.randomUUID(); legacyInstantCapture=UUID.randomUUID();
        Fixture f=legacyInstantFixture;
        UUID account=owner.queryForObject("SELECT id FROM learner_fee_accounts WHERE learner_id=? AND school_id=?",UUID.class,f.learner(),f.school());
        legacyInstantAttempt=as(f,f.parent().userId(),()->LegacyGatewayIntentFixture.seed(runtime,properties,clock,
                f.parent(),f.learner(),account,f.provider(),request(f,UUID.randomUUID())).id());
        background(f,()-> {
            legacyInstantPaid=runtime.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
            String oldHash;
            try {
                // Frozen V070 digest formula, independent of the new V2 implementation.
                String original=String.join("|",f.org().toString(),f.school().toString(),f.provider().toString(),"CASHFREE","TEST",
                        f.binding().merchantReference(),f.binding().credentialsReference(),legacyInstantAttempt.toString(),
                        "synthetic_legacy_order","synthetic_legacy_payment","500.00","INR","SUCCESS","true",legacyInstantPaid.toString(),"upi");
                oldHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception failure) { throw new IllegalStateException("Synthetic legacy hash creation failed"); }
            runtime.update("""
                    INSERT INTO fee_gateway_payment_observations(id,organisation_id,school_id,payment_provider_config_id,
                      provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,attempt_id,
                      order_reference,payment_reference,amount,currency_code,payment_status,captured,provider_paid_at,payment_method,observed_at,evidence_sha256)
                    VALUES(?,?,?,?,'CASHFREE','TEST',?,?,?,'synthetic_legacy_order','synthetic_legacy_payment',500.00,'INR','SUCCESS',true,?,'upi',clock_timestamp(),?)
                    """,legacyInstantFact,f.org(),f.school(),f.provider(),f.binding().merchantReference(),f.binding().credentialsReference(),legacyInstantAttempt,
                    java.sql.Timestamp.from(legacyInstantPaid),oldHash);
            runtime.update("""
                    INSERT INTO fee_gateway_checkout_observations(id,organisation_id,school_id,attempt_id,payment_provider_config_id,
                      provider_checkout_reference,attempt_reference,amount,currency_code,amount_paid,checkout_status,provider_expires_at,
                      order_references,pending_payment_count,successful_payment_count,discovery_complete,evidence_sha256,observed_at)
                    SELECT ?,?,?,attempt.id,?,attempt.id,attempt.id,500.00,'INR',500.00,'PAID',attempt.expires_at,
                      ARRAY['synthetic_legacy_order'],0,1,true,repeat('a',64),clock_timestamp() FROM fee_gateway_attempts attempt WHERE id=?
                    """,legacyInstantCheckout,f.org(),f.school(),f.provider(),legacyInstantAttempt);
            runtime.update("""
                    INSERT INTO fee_gateway_captures(id,organisation_id,school_id,attempt_id,payment_provider_config_id,checkout_observation_id,
                      provider_code,provider_environment,merchant_account_reference,checkout_reference,order_reference,payment_reference,
                      amount,currency_code,paid_at,detected_at,original_business_date,classification,evidence_sha256)
                    VALUES(?,?,?,?,?,?,'CASHFREE','TEST',?,?,'synthetic_legacy_order','synthetic_legacy_payment',500.00,'INR',?,clock_timestamp(),?,'MATCHED',repeat('b',64))
                    """,legacyInstantCapture,f.org(),f.school(),legacyInstantAttempt,f.provider(),legacyInstantCheckout,f.binding().merchantReference(),legacyInstantAttempt.toString(),
                    java.sql.Timestamp.from(legacyInstantPaid),LocalDate.ofInstant(legacyInstantPaid,ZoneId.of("Asia/Kolkata")));
            return true;
        });
    }

    private java.util.Map<String,List<String>> legacyInstantHistory() {
        var snapshot=new java.util.LinkedHashMap<String,List<String>>();
        for (var entry : java.util.Map.of("fee_gateway_attempts",legacyInstantAttempt,"fee_gateway_payment_observations",legacyInstantFact,
                "fee_gateway_checkout_observations",legacyInstantCheckout,"fee_gateway_captures",legacyInstantCapture).entrySet()) {
            snapshot.put(entry.getKey(),owner.queryForList("SELECT (to_jsonb(source)-ARRAY['provider_paid_at_exact','observed_at_exact','paid_at_exact','account_selection_mode'])::text FROM "
                    +entry.getKey()+" source WHERE id=?",String.class,entry.getValue()));
        }
        for (String table : List.of("fee_payments","fee_ledger_entries","fee_receipt_sequences","audit_events")) {
            snapshot.put(table,owner.queryForList("SELECT to_jsonb(source)::text FROM "+table+" source WHERE school_id=? ORDER BY to_jsonb(source)::text",
                    String.class,legacyInstantFixture.school()));
        }
        return snapshot;
    }
    private void assertLegacyInstantHistory() {
        assertThat(legacyInstantHistory()).isEqualTo(legacyInstantSnapshot);
        assertThat(owner.queryForObject("SELECT paid_at_exact FROM fee_gateway_captures WHERE id=?",String.class,legacyInstantCapture)).isNull();
        assertThat(owner.queryForObject("SELECT observed_at_exact FROM fee_gateway_checkout_observations WHERE id=?",String.class,legacyInstantCheckout)).isNull();
        assertThat(owner.queryForObject("SELECT provider_paid_at_exact FROM fee_gateway_payment_observations WHERE id=?",String.class,legacyInstantFact)).isNull();
    }

    private GatewayWorkQueue finalizerQueue(int leaseSeconds, boolean fastRetry) {
        return new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime), clock, Duration.ofSeconds(leaseSeconds)) {
            @Override public boolean complete(Claim claim, Outcome outcome, String safeCode, Duration retryAfter) {
                return super.complete(claim, outcome, safeCode, fastRetry && outcome == Outcome.RETRY ? Duration.ofSeconds(1) : retryAfter);
            }
        };
    }
    private GatewayProviderRegistry finalizerRegistry(GatewayWorkQueue.ProviderBinding binding) {
        var route = new GatewayProviderRegistry.Route(RECOVERY_ROUTE, binding.organisationId(), binding.schoolId(), binding.providerConfigId(),
                binding.providerCode(), CashfreePaymentProviderAdapter.Environment.valueOf(binding.environment()), binding.merchantReference(), binding.credentialsReference(),
                "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        return new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(true, true, false, false, List.of(route)), URI.create(properties.publicUrl()), clock,
                ignored -> { throw new AssertionError("Synthetic finalization must not resolve provider credentials"); },
                (trusted, id, secret, origin, observedClock) -> { throw new AssertionError("Synthetic finalization must not construct a provider or perform network I/O"); });
    }
    private GatewayAttemptDispatch finalizerDispatch(GatewayProviderRegistry registry) {
        var named = new NamedParameterJdbcTemplate(runtime);
        return new GatewayAttemptDispatch(named, registry, properties, new GatewayCheckoutReadiness(named, accountingService()));
    }
    private FinanceService finalizerFinance() {
        return new FinanceService(new NamedParameterJdbcTemplate(runtime), new SecurityAuditService(runtime, JsonMapper.builder().build(), clock), JsonMapper.builder().build(), clock);
    }
    private GatewayObservationFinalizer observationFinalizer(GatewayWorkQueue.ProviderBinding binding, GatewayWorkQueue work) {
        var registry = finalizerRegistry(binding);
        return new GatewayObservationFinalizer(registry, new NamedParameterJdbcTemplate(runtime), work, finalizerDispatch(registry), finalizerFinance(), transactions.getTransactionManager());
    }
    private GatewayWorkQueue.Claim dispatchedFinalizerClaim(Fixture f, UUID attempt, GatewayWorkQueue work) {
        var claim = background(f, () -> work.claimNextAttempt(f.binding()));
        assertThat(claim.id()).isEqualTo(attempt);
        background(f, () -> finalizerDispatch(finalizerRegistry(f.binding())).admitCreate(RECOVERY_ROUTE, claim));
        assertThat(owner.queryForObject("SELECT create_dispatched_by_lease_token FROM fee_gateway_attempt_work WHERE attempt_id=?", UUID.class, attempt)).isEqualTo(claim.token());
        return new GatewayWorkQueue.Claim(claim.kind(), claim.id(), claim.binding(), claim.token(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), true);
    }
    private GatewayDiscoveryEvidence.Discovery exactFinalizerDiscovery(Fixture f, UUID attempt, Instant paid) {
        return finalizerDiscovery(f, attempt, PaymentProviderAdapter.CheckoutStatus.PAID, "500.00", List.of("synthetic_order"),
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_order", List.of(finalizerPayment("synthetic_finalized_payment", paid)))), databaseInstant());
    }
    private GatewayDiscoveryEvidence.Discovery finalizerDiscovery(Fixture f, UUID attempt, PaymentProviderAdapter.CheckoutStatus status,
            String amountPaid, List<String> orders, List<GatewayDiscoveryEvidence.OrderRead> reads, Instant observedAt) {
        var frozen = owner.queryForMap("SELECT amount,expires_at FROM fee_gateway_attempts WHERE id=? AND organisation_id=? AND school_id=?", attempt, f.org(), f.school());
        var checkout = new PaymentProviderAdapter.CheckoutObservation(attempt.toString(), attempt.toString(), (BigDecimal) frozen.get("amount"), "INR",
                new BigDecimal(amountPaid), status, ((java.sql.Timestamp) frozen.get("expires_at")).toInstant(), orders);
        return new GatewayDiscoveryEvidence.Discovery(f.binding(), checkout, reads, observedAt);
    }
    private static PaymentProviderAdapter.PaymentObservation finalizerPayment(String reference, Instant paid) {
        return new PaymentProviderAdapter.PaymentObservation("synthetic_order", reference, new BigDecimal("500.00"), "INR", PaymentProviderAdapter.PaymentStatus.SUCCESS, true, paid, "upi");
    }
    private Instant databaseInstant() {
        return owner.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    private void assertOriginalReservation(Fixture f, UUID attempt) {
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?", String.class, attempt)).isEqualTo("ACTIVE");
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempts WHERE id=?", String.class, attempt)).isEqualTo("RESERVED");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payment_allocations WHERE school_id=?", Integer.class, f.school())).isZero();
    }
    private void assertOneFinalizedReceipt(Fixture f, UUID attempt) {
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT receipt_number FROM fee_payments WHERE school_id=?", String.class, f.school())).isEqualTo("SYN-000001");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='PAYMENT' AND direction='CREDIT' AND amount=500", Integer.class, f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) FROM fee_ledger_entries WHERE school_id=?", BigDecimal.class, f.school())).isEqualByComparingTo("500.00");
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?", Long.class, f.school())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?", String.class, attempt)).isEqualTo("CONSUMED");
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempts WHERE id=?", String.class, attempt)).isEqualTo("POSTED");
    }
    private static boolean tryFinalize(GatewayObservationFinalizer finalizer, GatewayWorkQueue.Claim claim, GatewayDiscoveryEvidence.Discovery evidence) {
        try { return finalizer.finalizeObservation(RECOVERY_ROUTE, claim, evidence) == GatewayWorkQueue.Outcome.COMPLETE; }
        catch (IllegalStateException rejectedDuplicateLease) { return false; }
    }
    private void awaitBlockedFinalizerFinanceLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            if (Boolean.TRUE.equals(control.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=? AND usename=?
                      AND wait_event_type='Lock' AND state='active' AND position('pg_advisory_xact_lock' IN query)>0)
                    """, Boolean.class, database, runtimeLogin))) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Actual non-owner finalizer did not reach the held finance fence");
    }

    private com.educore.api.accounting.AccountingService accountingService() {
        return new com.educore.api.accounting.AccountingService(new NamedParameterJdbcTemplate(runtime),
                new SecurityAuditService(runtime, JsonMapper.builder().build(), clock), clock);
    }
    private <T> T actor(Fixture fixture, AuthenticatedUser user, java.util.function.Supplier<T> work) {
        return as(fixture, user.userId(), work);
    }
    private GatewayBook configureGatewayBook(Fixture f, String basis, String onlineCategory, boolean approve) {
        var maker = owner.queryForMap("SELECT member.id,member.user_id FROM memberships member JOIN membership_roles role ON role.membership_id=member.id WHERE member.school_id=? AND role.role_id='00000000-0000-4000-8000-000000000001' LIMIT 1", f.school());
        var admin = accountingActor(f, (UUID) maker.get("user_id"), (UUID) maker.get("id"));
        UUID reviewerUser = UUID.randomUUID(), reviewerMember = UUID.randomUUID();
        owner.update("INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status) VALUES(?,?,?,'Synthetic independent accounting reviewer','not-a-login-hash','ACTIVE')",
                reviewerUser, "synthetic-" + reviewerUser + "@example.test", "synthetic-" + reviewerUser + "@example.test");
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())", reviewerMember, reviewerUser, f.org(), f.school());
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001')", reviewerMember);
        var reviewer = accountingActor(f, reviewerUser, reviewerMember);
        var accounting = accountingService();
        var feeAccounting = new FeeAccountingService(new NamedParameterJdbcTemplate(runtime), accounting,
                new SecurityAuditService(runtime, JsonMapper.builder().build(), clock), JsonMapper.builder().build(), clock);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        var year = actor(f, admin, () -> accounting.createFinancialYear(admin, new com.educore.api.accounting.AccountingController.FinancialYearRequest(
                f.school(), "SYNTHETIC_GATEWAY", "Synthetic gateway readiness year", today.minusDays(30), today.plusDays(330), basis, "INR", "Synthetic maker prepares book")));
        UUID cash = accountingAccount(f, admin, "CASH", "ASSET", "CASH"), bank = accountingAccount(f, admin, "BANK", "ASSET", "BANK");
        UUID clearing = accountingAccount(f, admin, "CLEARING", "ASSET", onlineCategory), income = accountingAccount(f, admin, "TUITION", "INCOME", "INCOME");
        UUID receivable = "ACCRUAL".equals(basis) ? accountingAccount(f, admin, "RECEIVABLE", "ASSET", "RECEIVABLE") : null;
        UUID concession = "ACCRUAL".equals(basis) ? accountingAccount(f, admin, "CONCESSION", "EXPENSE", "EXPENSE") : null;
        actor(f, reviewer, () -> accounting.openFinancialYear(reviewer, year.id(), new com.educore.api.accounting.AccountingController.VersionedReasonRequest(f.school(), 0, "Synthetic independent year opening")));
        UUID head = owner.queryForObject("SELECT item.fee_head_id FROM learner_fee_charges charge JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id WHERE charge.id=?", UUID.class, f.charge());
        var configuration = actor(f, admin, () -> feeAccounting.createConfiguration(admin, new FeeAccountingController.ConfigurationRequest(
                f.school(), year.id(), null, 1, today.minusDays(30), receivable, concession,
                List.of(new FeeAccountingController.HeadMappingRequest(head, income)),
                List.of(new FeeAccountingController.TenderMappingRequest("CASH", cash), new FeeAccountingController.TenderMappingRequest("BANK_TRANSFER", bank),
                        new FeeAccountingController.TenderMappingRequest("CHEQUE", bank), new FeeAccountingController.TenderMappingRequest("SCHOOL_UPI", bank),
                        new FeeAccountingController.TenderMappingRequest("ONLINE_PROVIDER", clearing)), null, "Synthetic gateway readiness configuration")));
        var submitted = actor(f, admin, () -> feeAccounting.submitConfiguration(admin, configuration.id(),
                new FeeAccountingController.VersionedReasonRequest(f.school(), 0, "Synthetic submit for independent review")));
        assertThatThrownBy(() -> actor(f, admin, () -> feeAccounting.decideConfiguration(admin, configuration.id(),
                new FeeAccountingController.ConfigurationDecisionRequest(f.school(), "APPROVE", submitted.rowVersion(), "Synthetic disallowed self approval"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("different authorized person");
        if (approve) actor(f, reviewer, () -> feeAccounting.decideConfiguration(reviewer, configuration.id(),
                new FeeAccountingController.ConfigurationDecisionRequest(f.school(), "APPROVE", submitted.rowVersion(), "Synthetic independent fee mapping approval")));
        UUID period = owner.queryForObject("SELECT id FROM accounting_periods WHERE financial_year_id=? AND ? BETWEEN starts_on AND ends_on", UUID.class, year.id(), today);
        return new GatewayBook(year.id(), period, configuration.id(), income, clearing, admin, reviewer);
    }
    private AuthenticatedUser accountingActor(Fixture f, UUID user, UUID member) {
        return new AuthenticatedUser(user, UUID.randomUUID(), "synthetic@example.test", null, "Synthetic accounting actor", true,
                f.org(), Set.of(member), Set.of("accounting.read", "accounting.configure", "accounting.close", "accounting.post", "fees.configure", "fees.close.approve"), List.of());
    }
    private UUID accountingAccount(Fixture f, AuthenticatedUser maker, String code, String type, String category) {
        return actor(f, maker, () -> accountingService().createAccount(maker, new com.educore.api.accounting.AccountingController.AccountRequest(
                f.school(), null, code, "Synthetic " + code, null, type, category, true, "ACTIVE", null, "Synthetic gateway readiness mapping"))).id();
    }
    private record GatewayBook(UUID year, UUID period, UUID configuration, UUID income, UUID clearing,
            AuthenticatedUser maker, AuthenticatedUser reviewer) {}

    private static final String RECOVERY_ROUTE = "79f819428fb6492996a750b3e43b30e20ffb9408ad1569be8d19f5a307ca2f86";
    private static final String WEBHOOK_SECRET = "synthetic-webhook-secret-not-live", WEBHOOK_TIME = "1600000000000";
    private GatewayWebhookIntake webhookIntake(GatewayWorkQueue.ProviderBinding binding) {
        var route = new GatewayProviderRegistry.Route(RECOVERY_ROUTE, binding.organisationId(), binding.schoolId(), binding.providerConfigId(),
                "CASHFREE", CashfreePaymentProviderAdapter.Environment.TEST, binding.merchantReference(), binding.credentialsReference(),
                "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        var client = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var registry = new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false, true, false, false, List.of(route)),
                URI.create(properties.publicUrl()), clock, name -> name.endsWith("CLIENT_ID") ? "synthetic-client-not-live" : WEBHOOK_SECRET,
                (trusted, id, secret, origin, observedClock) -> new CashfreePaymentProviderAdapter(trusted.environment(), id, secret, origin, observedClock, client));
        return new GatewayWebhookIntake(registry, new NamedParameterJdbcTemplate(runtime), queue, transactions.getTransactionManager());
    }
    private static byte[] webhookBytes(UUID attempt, String status) {
        String tag = attempt == null ? "" : ",\"order_tags\":{\"link_id\":\"" + attempt + "\"}";
        return ("{\"type\":\"PAYMENT_" + status + "_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"synthetic_order\"" + tag
                + "},\"payment\":{\"cf_payment_id\":\"synthetic_payment\",\"payment_status\":\"" + status
                + "\"},\"customer_details\":{\"customer_name\":\"synthetic-private-नाम\"}}}").getBytes(StandardCharsets.UTF_8);
    }
    private static String webhookSignature(byte[] wire, String timestamp) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(mac.doFinal(wire));
    }
    private static void assertIntakeRejected(GatewayWebhookIntake intake, byte[] wire, String signature, String timestamp) {
        assertThatThrownBy(() -> intake.accept(RECOVERY_ROUTE, wire, signature, timestamp)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Gateway webhook intake requires its trusted route, authentic bounded body and committed scoped inbox").hasNoCause();
    }
    private GatewayAttemptDispatch recoveryDispatch(GatewayWorkQueue.ProviderBinding binding, boolean creation, boolean worker, String origin) {
        var application = new ApplicationProperties(origin, List.of(), Optional.empty(), Optional.empty(), properties.security(), properties.bootstrap());
        var route = new GatewayProviderRegistry.Route(RECOVERY_ROUTE, binding.organisationId(), binding.schoolId(), binding.providerConfigId(),
                binding.providerCode(), CashfreePaymentProviderAdapter.Environment.valueOf(binding.environment()),
                binding.merchantReference(), binding.credentialsReference(), "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID",
                "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        var registry = new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(creation, worker, false, false, List.of(route)),
                java.net.URI.create(origin), clock,
                ignored -> { throw new AssertionError("Synthetic recovery must not resolve credentials"); },
                (trusted, id, secret, returnOrigin, observedClock) -> { throw new AssertionError("Synthetic recovery must not construct a provider or perform network I/O"); });
        var named = new NamedParameterJdbcTemplate(runtime);
        var accounting = new com.educore.api.accounting.AccountingService(named,
                new SecurityAuditService(runtime, JsonMapper.builder().build(), clock), clock);
        return new GatewayAttemptDispatch(named, registry, application, new GatewayCheckoutReadiness(named, accounting));
    }
    private void awaitBlockedRecoverySelect() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            if (Boolean.TRUE.equals(control.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=? AND usename=?
                      AND wait_event_type='Lock' AND state='active'
                      AND position('SELECT attempt.id,attempt.learner_fee_account_id' IN query)>0)
                    """, Boolean.class, database, runtimeLogin))) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Actual non-owner recovery SELECT did not reach the held row lock");
    }

    private UUID prepareWork(Fixture f) {
        return as(f, f.parent().userId(), () -> {
            var attempt = reservations.prepare(f.parent(), f.learner(), request(f, UUID.randomUUID()));
            queue.enqueueAttempt(f.binding(), attempt.id()); return attempt.id();
        });
    }
    private static GatewayReservationService.PrepareRequest request(Fixture f, UUID requestId) {
        return new GatewayReservationService.PrepareRequest(requestId,
                List.of(new GatewayReservationService.ChargeSelection(f.charge(), new BigDecimal("500.00"))), "0000000000");
    }
    private <T> T background(Fixture f, java.util.function.Supplier<T> work) { return as(f, null, work); }
    private <T> T as(Fixture f, UUID user, java.util.function.Supplier<T> work) {
        try (var ignored = TenantRequestContext.open(user, f.org())) { return transactions.execute(status -> work.get()); }
    }
    private void awaitDatabaseTime(Instant deadline) {
        // Observe actual PostgreSQL clock progression, not a mutated row, disabled trigger or fake clock.
        owner.queryForObject("SELECT pg_sleep(greatest(0,extract(epoch FROM (?::timestamptz-clock_timestamp())))+0.03)",
                (r,n)->true,java.sql.Timestamp.from(deadline));
    }
    private void assertNoFinancialPosting(Fixture f) {
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND direction='CREDIT'", Integer.class, f.school())).isZero();
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?", Long.class, f.school())).isEqualTo(1);
    }
    private static PaymentProviderAdapter.VerifiedWebhook event(UUID attempt, String hash) {
        return new PaymentProviderAdapter.VerifiedWebhook("CASHFREE", hash, "PAYMENT_SUCCESS_WEBHOOK", attempt == null ? null : attempt.toString(),
                "synthetic_order", "synthetic_payment", null);
    }
    private static PaymentProviderAdapter.PaymentObservation payment(PaymentProviderAdapter.PaymentStatus status, boolean captured, Instant paid, String amount, String currency) {
        return new PaymentProviderAdapter.PaymentObservation("synthetic_order", "synthetic_payment", new BigDecimal(amount), currency, status, captured, paid, "upi");
    }

    private Fixture fixture() { return fixture(UUID.randomUUID()); }
    private Fixture fixture(UUID org) {
        UUID school=UUID.randomUUID(),campus=UUID.randomUUID(),session=UUID.randomUUID(),learner=UUID.randomUUID(),application=UUID.randomUUID(),enrolment=UUID.randomUUID();
        UUID adminUser=UUID.randomUUID(),adminMember=UUID.randomUUID(),parentUser=UUID.randomUUID(),parentMember=UUID.randomUUID(),guardian=UUID.randomUUID();
        UUID head=UUID.randomUUID(),plan=UUID.randomUUID(),item=UUID.randomUUID(),account=UUID.randomUUID(),assignment=UUID.randomUUID(),charge=UUID.randomUUID(),provider=UUID.randomUUID();
        if (owner.queryForObject("SELECT count(*) FROM organisations WHERE id=?", Integer.class, org)==0)
            owner.update("INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'Synthetic work school','Synthetic work school','ACTIVE',now(),now())",org,"synthetic-"+org);
        owner.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,?,'Synthetic work school','Synthetic work school','ACTIVE',now(),now())",school,org,"synthetic-"+school);
        owner.update("INSERT INTO campuses(id,organisation_id,school_id,code,display_name,status,created_at,updated_at) VALUES(?,?,?,'TEST','Synthetic campus','ACTIVE',now(),now())",campus,org,school);
        for (UUID user : List.of(adminUser,parentUser)) owner.update("INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status) VALUES(?,?,?,'Synthetic work actor','not-a-login-hash','ACTIVE')",user,"synthetic-"+user+"@example.test","synthetic-"+user+"@example.test");
        for (int i=0;i<2;i++) {
            UUID member=i==0?adminMember:parentMember,user=i==0?adminUser:parentUser;
            owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())",member,user,org,school);
            owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?::uuid)",member,i==0?"00000000-0000-4000-8000-000000000001":"00000000-0000-4000-8000-000000000006");
        }
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
        owner.update("INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,'Synthetic session',?,?,'ACTIVE',now(),now())",session,org,school,today.minusDays(30),today.plusDays(330));
        owner.update("""
                INSERT INTO admission_applications(id,organisation_id,school_id,idempotency_key,request_fingerprint,intake_session_label,target_grade_code,applicant_legal_name,birth_date,created_by_membership_id,created_at,updated_at)
                VALUES(?,?,?, ?,repeat('c',64),'Synthetic session','VI','Synthetic applicant',DATE '2015-01-01',?,now(),now())
                """,application,org,school,"synthetic-"+application,adminMember);
        owner.update("INSERT INTO learners(id,organisation_id,school_id,origin_application_id,legal_name,birth_date,gender_code,status,created_at,updated_at) VALUES(?,?,?,?,'Synthetic learner',DATE '2015-01-01','NOT_STATED','ACTIVE',now(),now())",learner,org,school,application);
        owner.update("INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,created_at,updated_at,user_account_id) VALUES(?,?,?,'Synthetic guardian',?,'ACTIVE',now(),now(),?)",guardian,org,school,"synthetic-"+parentUser+"@example.test",parentUser);
        owner.update("INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,relationship_code,portal_access_status,created_at,updated_at) VALUES(?,?,?,?,'PARENT_GUARDIAN','ACTIVE',now(),now())",learner,guardian,org,school);
        owner.update("""
                INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,origin_application_id,grade_code,status,starts_on,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'VI','ACTIVE',?,now(),now())
                """,enrolment,org,school,campus,session,learner,application,today.minusDays(30));
        owner.update("INSERT INTO fee_heads(id,organisation_id,school_id,code,display_name,category,status,created_at,updated_at) VALUES(?,?,?,'TEST','Synthetic tuition','TUITION','ACTIVE',now(),now())",head,org,school);
        owner.update("INSERT INTO fee_plans(id,organisation_id,school_id,academic_session_id,plan_code,display_name,currency_code,receipt_prefix,status,created_by_membership_id,created_at,updated_at) VALUES(?,?,?,?,'TEST','Synthetic fees','INR','SYN','DRAFT',?,now(),now())",plan,org,school,session,adminMember);
        owner.update("INSERT INTO fee_plan_items(id,organisation_id,school_id,fee_plan_id,fee_head_id,sequence_number,label,due_on,amount,allow_partial,created_at,updated_at) VALUES(?,?,?,?,?,1,'Synthetic installment',?,1000,true,now(),now())",item,org,school,plan,head,today);
        owner.update("UPDATE fee_plans SET status='ACTIVE',activated_by_membership_id=?,activated_at=now(),row_version=row_version+1 WHERE id=?",adminMember,plan);
        owner.update("INSERT INTO learner_fee_accounts(id,organisation_id,school_id,academic_session_id,learner_id,enrolment_id,currency_code,opened_by_membership_id,opened_at) VALUES(?,?,?,?,?,?,'INR',?,now())",account,org,school,session,learner,enrolment,adminMember);
        owner.update("INSERT INTO fee_plan_assignments(id,organisation_id,school_id,learner_fee_account_id,fee_plan_id,plan_snapshot,assigned_by_membership_id,assigned_at,reason) VALUES(?,?,?,?,?,'{}',?,now(),'Synthetic fee setup')",assignment,org,school,account,plan,adminMember);
        owner.update("INSERT INTO learner_fee_charges(id,organisation_id,school_id,learner_fee_account_id,fee_plan_assignment_id,fee_plan_item_id,fee_head_code_snapshot,fee_head_name_snapshot,label_snapshot,due_on,amount,allow_partial,created_at) VALUES(?,?,?,?,?,?,'TEST','Synthetic tuition','Synthetic installment',?,1000,true,now())",charge,org,school,account,assignment,item,today);
        owner.update("INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,learner_fee_charge_id,entry_type,direction,amount,effective_on,description,source_type,source_id,created_by_membership_id,created_at) VALUES(?,?,?,?,?,'CHARGE','DEBIT',1000,?,'Synthetic charge','FEE_CHARGE',?,?,now())",UUID.randomUUID(),org,school,account,charge,today,charge,adminMember);
        owner.update("INSERT INTO fee_receipt_sequences(organisation_id,school_id,academic_session_id,receipt_prefix,next_value,updated_at) VALUES(?,?,?,'SYN',1,now())",org,school,session);
        owner.update("INSERT INTO payment_provider_configs(id,organisation_id,school_id,provider_code,environment,credentials_secret_reference,merchant_account_reference,status,configured_by_membership_id,created_at,updated_at) VALUES(?,?,?,'CASHFREE','TEST','synthetic:not-enabled',?,'ACTIVE',?,now(),now())",provider,org,school,"synthetic_merchant_"+provider,adminMember);
        var actor=new AuthenticatedUser(parentUser,UUID.randomUUID(),"synthetic@example.test",null,"Synthetic actor",true,org,Set.of(parentMember),Set.of("portal.guardian"),List.of());
        var binding=new GatewayWorkQueue.ProviderBinding(org,school,provider,"CASHFREE","TEST","synthetic_merchant_"+provider,"synthetic:not-enabled");
        return new Fixture(org,school,learner,charge,provider,actor,binding);
    }

    @AfterAll void removeOnlyGeneratedDatabaseAndRoles() {
        if (control!=null) {
            if (databaseCreated) control.execute("DROP DATABASE "+identifier(database)+" WITH (FORCE)");
            if (runtimeCreated) control.execute("DROP ROLE "+identifier(runtimeLogin));
            if (ownerCreated) control.execute("DROP ROLE "+identifier(ownerLogin));
        }
        if (container!=null) container.stop();
    }
    private void createRole(String role,String password,String bypass) throws Exception {
        try(var connection=controlDataSource.getConnection();var format=connection.prepareStatement("SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER "+bypass+" NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',?,?)")) {
            format.setString(1,role);format.setString(2,password);
            try(var row=format.executeQuery();var command=connection.createStatement()){row.next();command.execute(row.getString(1));}
        }
    }
    private static DriverManagerDataSource dataSource(String url,String user,String password) {
        var result=new DriverManagerDataSource(url,user,password); var settings=new java.util.Properties();
        settings.setProperty("connectTimeout","10");settings.setProperty("socketTimeout","120");settings.setProperty("options","-c statement_timeout=90000");
        result.setConnectionProperties(settings);return result;
    }
    private static String identifier(String value) {
        if(!value.matches("(?:educore_gateway_work_|synthetic_work_(?:owner|runtime)_)[a-f0-9]{32}")) throw new IllegalArgumentException("Generated fixture identity required");
        return '"'+value+'"';
    }
    private static String secret(){byte[] value=new byte[32];new SecureRandom().nextBytes(value);return Base64.getEncoder().encodeToString(value);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
    private record Fixture(UUID org,UUID school,UUID learner,UUID charge,UUID provider,AuthenticatedUser parent,GatewayWorkQueue.ProviderBinding binding){}
}
