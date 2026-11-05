package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ProductionDatabaseSecurity;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantAwareDataSourceBeanPostProcessor;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
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

/** Actual V069 -> V070 upgrade with committed legacy receipts, not a post-upgrade backfill. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualCollectionMigrationPostgresIT {
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String database = "educore_manual_upgrade_" + suffix;
    private final String ownerLogin = "synthetic_manual_owner_" + suffix;
    private final String runtimeLogin = "synthetic_manual_runtime_" + suffix;
    private final String ownerPassword = secret(), runtimePassword = secret();
    private final Clock clock = Clock.systemUTC();
    private PostgreSQLContainer<?> container;
    private DriverManagerDataSource controlDataSource;
    private JdbcTemplate control, owner, runtime;
    private TransactionTemplate transactions;
    private FinanceService finance;
    private SecurityAuditService audit;
    private Fixture fixture, other;
    private List<LegacyReceipt> history;
    private Map<String, List<String>> beforeUpgrade;
    private boolean ownerCreated, runtimeCreated, databaseCreated;
    private int upgradeCount;

    @BeforeAll void createLegacyHistoryThenApplyTheUnchangedForwardMigration() throws Exception {
        String controlUrl = System.getenv("TEST_DATABASE_URL"), login, password;
        if (controlUrl == null || controlUrl.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:18.4-alpine");
            container.start();
            controlUrl = container.getJdbcUrl(); login = container.getUsername(); password = container.getPassword();
        } else {
            if (!controlUrl.matches("jdbc:postgresql://[^/]+/educore_integration(?:\\?.*)?")) {
                throw new IllegalStateException("The generated upgrade gate requires the isolated educore_integration control database");
            }
            login = required("TEST_DATABASE_USER"); password = required("TEST_DATABASE_PASSWORD");
        }
        controlDataSource = dataSource(controlUrl, login, password);
        control = new JdbcTemplate(controlDataSource);
        createRole(ownerLogin, ownerPassword, "BYPASSRLS"); ownerCreated = true;
        createRole(runtimeLogin, runtimePassword, "NOBYPASSRLS"); runtimeCreated = true;
        control.execute("CREATE DATABASE " + identifier(database) + " OWNER " + identifier(ownerLogin)); databaseCreated = true;
        String url = controlUrl.replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
        Flyway legacy = Flyway.configure().dataSource(url, ownerLogin, ownerPassword).locations("classpath:db/migration")
                .target("069").validateMigrationNaming(true).cleanDisabled(true).load();
        legacy.migrate();
        owner = new JdbcTemplate(dataSource(url, ownerLogin, ownerPassword));
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='069'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT to_regclass('fee_manual_collection_evidence') IS NULL", Boolean.class)).isTrue();
        applyRuntimeGrants(url);
        DataSource runtimeDataSource = (DataSource) new TenantAwareDataSourceBeanPostProcessor()
                .postProcessAfterInitialization(dataSource(url, runtimeLogin, runtimePassword), "dataSource");
        runtime = new JdbcTemplate(runtimeDataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));
        transactions.setTimeout(30);
        var mapper = JsonMapper.builder().build();
        audit = new SecurityAuditService(runtime, mapper, clock);
        finance = new FinanceService(new NamedParameterJdbcTemplate(runtime), audit, mapper, clock);
        fixture = fixture(); other = fixture();
        // All three receipts really commit under V069, with its existing constraints/RLS enabled.
        // The third is reversed through the unchanged production reversal service BEFORE migration.
        history = List.of(
                legacyReceipt(fixture, "BANK_TRANSFER", "Synthetic Legacy/DUP-01", "100.00"),
                legacyReceipt(fixture, "SCHOOL_UPI", "synthetic legacy/dup-01", "125.00"),
                legacyReceipt(fixture, "BANK_TRANSFER", "Synthetic-Legacy/REVERSED-02", "75.00"));
        LegacyReceipt reversed = history.getLast();
        scoped(fixture.collector(), () -> finance.reversePayment(fixture.collector(), reversed.id(),
                new FinanceController.PaymentReversalRequest(fixture.school(), 0, "Synthetic controlled legacy reversal")));
        beforeUpgrade = fullHistorySnapshot(true);
        upgradeCount = Flyway.configure().dataSource(url, ownerLogin, ownerPassword).locations("classpath:db/migration")
                .target("070").validateMigrationNaming(true).cleanDisabled(true).load().migrate().migrationsExecuted;
        // Verify the actual complete old rows, timestamps, reversals, audit, allocations and sequence now,
        // before any current-version test is allowed to insert additional data.
        assertThat(fullHistorySnapshot(true)).isEqualTo(beforeUpgrade);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence", Integer.class)).isZero();
        // The V069->V070 preservation assertion above remains specific to that upgrade.
        // Current service readers require the additive V071 columns; apply it only
        // after proving V070 did not backfill or alter the committed legacy rows.
        Flyway.configure().dataSource(url, ownerLogin, ownerPassword).locations("classpath:db/migration")
                .validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        assertThat(fullHistorySnapshot(true)).isEqualTo(beforeUpgrade);
        applyRuntimeGrants(url);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void realForwardMigrationPreservesDuplicateAndReversedHistoryWithoutBackfill() {
        assertThat(upgradeCount).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='070'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM fee_payments WHERE organisation_id=? AND school_id=?
                  AND normalize_manual_bank_reference(external_reference)='SYNTHETICLEGACY/DUP-01'
                """, Integer.class, fixture.org(), fixture.school())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?", String.class, history.getLast().id())).isEqualTo("REVERSED");
        assertLegacyRowsUnchanged();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM fee_manual_collection_evidence
                WHERE fee_payment_id IN (?,?,?)
                """, Integer.class, history.get(0).id(), history.get(1).id(), history.get(2).id())).isZero();
        assertThat(owner.queryForObject("SELECT indisunique FROM pg_index WHERE indexrelid='fee_payments_manual_bank_reference_lookup'::regclass", Boolean.class)).isFalse();
    }

    @Test void exactOldDigitalRequestsRetryOriginalReceiptsWithoutNewAttestationOrWrites() {
        Map<String, List<String>> before = currentSchoolSnapshot();
        for (LegacyReceipt receipt : history) {
            FinanceMutationResponse retried = scoped(fixture.collector(), () -> finance.recordPayment(
                    fixture.collector(), receipt.key(), receipt.request()));
            assertThat(retried.id()).isEqualTo(receipt.id());
            assertThat(retried.receiptNumber()).isEqualTo(receipt.receiptNumber());
            assertThat(retried.rowVersion()).isEqualTo(receipt.equals(history.getLast()) ? 1 : 0);
        }
        assertThat(currentSchoolSnapshot()).isEqualTo(before);
        assertLegacyRowsUnchanged();
    }

    @Test void changedOldRetryAndNewCrossMethodReuseCannotOverwriteHistoricalCredits() {
        Map<String, List<String>> before = currentSchoolSnapshot();
        LegacyReceipt old = history.getFirst();
        var changed = oldRequest(fixture, old.request().paymentMethod(), old.request().externalReference(), "99.00");
        assertThatThrownBy(() -> scoped(fixture.collector(), () -> finance.recordPayment(fixture.collector(), old.key(), changed)))
                .isInstanceOf(ApiException.class).hasMessageContaining("different collection");
        for (String method : List.of("SCHOOL_UPI", "BANK_TRANSFER")) {
            for (String reference : List.of(" synthetic LEGACY/dup-01 ", "synthetic-legacy/reversed-02")) {
                assertThatThrownBy(() -> scoped(fixture.collector(), () -> finance.recordPayment(fixture.collector(),
                        "synthetic-new-reuse-" + UUID.randomUUID(), verifiedRequest(fixture, method, reference))))
                        .isInstanceOf(ApiException.class).hasMessageContaining("receipt history, including reversed receipts");
            }
        }
        // A brand-new legacy-shaped request is NOT an attestation bypass just because exact retries are preserved.
        assertThatThrownBy(() -> scoped(fixture.collector(), () -> finance.recordPayment(fixture.collector(),
                "synthetic-new-without-verification", old.request())))
                .isInstanceOf(ApiException.class).hasMessageContaining("actual received funds");
        assertThat(currentSchoolSnapshot()).isEqualTo(before);
        assertLegacyRowsUnchanged();
    }

    @Test void historicalRowsRemainNonOwnerTenantScopedAndImmutableAfterUpgrade() {
        ProductionDatabaseSecurity.verify(runtime);
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_payments", Integer.class)).isZero();
        assertThat(scoped(other.collector(), () -> runtime.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?", Integer.class, fixture.school()))).isZero();
        assertThatThrownBy(() -> scoped(other.collector(), () -> finance.recordPayment(other.collector(), history.getFirst().key(), history.getFirst().request())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> scoped(fixture.collector(), () -> runtime.update("UPDATE fee_payments SET amount=amount+1 WHERE id=?", history.getFirst().id())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> scoped(fixture.collector(), () -> runtime.update("DELETE FROM fee_payments WHERE id=?", history.getFirst().id())))
                .isInstanceOf(DataAccessException.class);
        assertLegacyRowsUnchanged();
    }

    @Test void newVerifiedCreditContinuesTheExistingSequenceWithoutInventingHistoricalEvidence() {
        long next = owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?", Long.class, fixture.school());
        String key = "synthetic-fresh-credit-" + suffix;
        var request = verifiedRequest(fixture, "SCHOOL_UPI", "SYNTHETIC-FRESH/" + suffix);
        var first = scoped(fixture.collector(), () -> finance.recordPayment(fixture.collector(), key, request));
        assertThat(first.receiptNumber()).isEqualTo("SYN-" + String.format(java.util.Locale.ROOT, "%06d", next));
        assertThat(scoped(fixture.collector(), () -> finance.recordPayment(fixture.collector(), key, request))).isEqualTo(first);
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?", Long.class, fixture.school())).isEqualTo(next + 1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence WHERE fee_payment_id=?", Integer.class, first.id())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE source_type='FEE_PAYMENT' AND source_id=?", Integer.class, first.id())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT sum(amount) FROM fee_payment_allocations WHERE fee_payment_id=?", BigDecimal.class, first.id())).isEqualByComparingTo("10.00");
        assertLegacyRowsUnchanged();
    }

    private LegacyReceipt legacyReceipt(Fixture f, String method, String reference, String value) {
        var request = oldRequest(f, method, reference, value);
        String key = "synthetic-pre-v070-" + UUID.randomUUID();
        UUID id = UUID.randomUUID(), ledgerId = UUID.randomUUID();
        // This narrow fixture reproduces the pre-V070 common SQL writer's committed receipt/ledger/
        // allocation contract. It does not call, mock or disable the current received-funds service.
        // There is deliberately no post-upgrade insertion/backfill or invented actual_received_at.
        return scoped(f.collector(), () -> {
            runtime.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) locked", Integer.class, f.school() + ":finance");
            long number = runtime.queryForObject("""
                    UPDATE fee_receipt_sequences SET next_value=next_value+1,updated_at=clock_timestamp()
                    WHERE organisation_id=? AND school_id=? AND academic_session_id=? RETURNING next_value-1
                    """, Long.class, f.org(), f.school(), f.session());
            String receiptNumber = "SYN-" + String.format(java.util.Locale.ROOT, "%06d", number);
            runtime.update("""
                    INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,entry_type,direction,
                      amount,effective_on,description,source_type,source_id,created_by_membership_id,created_at,actor_kind)
                    VALUES(?,?,?,?,'PAYMENT','CREDIT',?,?,'Synthetic legacy fee receipt','FEE_PAYMENT',?,?,clock_timestamp(),'HUMAN')
                    """, ledgerId, f.org(), f.school(), f.account(), request.amount(), request.receivedOn(), id, member(f.collector()));
            runtime.update("""
                    INSERT INTO fee_payments(id,organisation_id,school_id,learner_fee_account_id,receipt_number,idempotency_key,
                      request_fingerprint,payment_method,external_reference,amount,received_on,notes,status,ledger_entry_id,
                      received_by_membership_id,created_at,updated_at,row_version,actor_kind)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'POSTED',?,?,clock_timestamp(),clock_timestamp(),0,'HUMAN')
                    """, id, f.org(), f.school(), f.account(), receiptNumber, key, historicalFingerprint(request), method,
                    reference, request.amount(), request.receivedOn(), request.notes(), ledgerId, member(f.collector()));
            runtime.update("""
                    INSERT INTO fee_payment_allocations(id,organisation_id,school_id,fee_payment_id,learner_fee_charge_id,amount,created_at)
                    VALUES(?,?,?,?,?,?,clock_timestamp())
                    """, UUID.randomUUID(), f.org(), f.school(), id, f.charge(), request.amount());
            audit.recordScoped(f.collector(), member(f.collector()), f.school(), "finance.payment.recorded",
                    "FEE_PAYMENT", id.toString(), AuditOutcome.SUCCEEDED, null, Map.of());
            return new LegacyReceipt(id, key, receiptNumber, request);
        });
    }

    /** Frozen original digest, independent of the implementation under test's additive fingerprint branch. */
    private static String historicalFingerprint(FinanceController.PaymentRequest request) {
        String allocations = request.allocations().stream()
                .map(value -> value.chargeId() + ":" + value.amount().setScale(2).toPlainString()).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String material = String.join("|", request.accountId().toString(), request.paymentMethod(), request.externalReference().strip(),
                request.amount().setScale(2).toPlainString(), request.receivedOn().toString(), request.notes().strip(), allocations);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static FinanceController.PaymentRequest oldRequest(Fixture f, String method, String reference, String value) {
        var amount = new BigDecimal(value).setScale(2);
        return new FinanceController.PaymentRequest(f.school(), f.account(), method, reference, amount, schoolToday().minusDays(2),
                "Synthetic preserved legacy request", List.of(new FinanceController.PaymentAllocation(f.charge(), amount)));
    }

    private static FinanceController.PaymentRequest verifiedRequest(Fixture f, String method, String reference) {
        Instant actual = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new FinanceController.PaymentRequest(f.school(), f.account(), method, reference, new BigDecimal("10.00"),
                actual.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(), "Synthetic new verified credit",
                List.of(new FinanceController.PaymentAllocation(f.charge(), new BigDecimal("10.00"))), actual, null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT", true, reference), null);
    }

    private Map<String, List<String>> fullHistorySnapshot(boolean sequence) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("fee_payments", "fee_payment_allocations", "fee_ledger_entries")) {
            result.put(table, owner.queryForList("SELECT to_jsonb(record)::text FROM " + table + " record WHERE school_id=? ORDER BY id", String.class, fixture.school()));
        }
        result.put("audit_events", owner.queryForList("SELECT to_jsonb(record)::text FROM audit_events record WHERE school_id=? ORDER BY id", String.class, fixture.school()));
        if (sequence) result.put("fee_receipt_sequences", owner.queryForList("SELECT to_jsonb(record)::text FROM fee_receipt_sequences record WHERE school_id=?", String.class, fixture.school()));
        return result;
    }

    private void assertLegacyRowsUnchanged() {
        for (String table : List.of("fee_payments", "fee_payment_allocations", "fee_ledger_entries", "audit_events")) {
            List<String> now = owner.queryForList("SELECT to_jsonb(record)::text FROM " + table + " record WHERE school_id=? ORDER BY id", String.class, fixture.school());
            assertThat(now).as("All original %s rows including exact timestamps remain untouched", table).containsAll(beforeUpgrade.get(table));
        }
    }

    private Map<String, List<String>> currentSchoolSnapshot() {
        var result = fullHistorySnapshot(true);
        result.put("fee_manual_collection_evidence", owner.queryForList("SELECT to_jsonb(record)::text FROM fee_manual_collection_evidence record WHERE school_id=? ORDER BY fee_payment_id", String.class, fixture.school()));
        return result;
    }

    private Fixture fixture() {
        UUID org = UUID.randomUUID(), school = UUID.randomUUID(), campus = UUID.randomUUID(), session = UUID.randomUUID();
        UUID user = UUID.randomUUID(), membership = UUID.randomUUID(), application = UUID.randomUUID(), learner = UUID.randomUUID(), enrolment = UUID.randomUUID();
        UUID head = UUID.randomUUID(), plan = UUID.randomUUID(), item = UUID.randomUUID(), account = UUID.randomUUID(), assignment = UUID.randomUUID(), charge = UUID.randomUUID();
        owner.update("INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'Synthetic migration school','Synthetic migration school','ACTIVE',now(),now())", org, "synthetic-" + org);
        owner.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'synthetic','Synthetic migration school','Synthetic migration school','ACTIVE',now(),now())", school, org);
        owner.update("INSERT INTO campuses(id,organisation_id,school_id,code,display_name,status,created_at,updated_at) VALUES(?,?,?,'TEST','Synthetic campus','ACTIVE',now(),now())", campus, org, school);
        owner.update("INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status) VALUES(?,?,?,'Synthetic migration collector','not-a-login-hash','ACTIVE')", user, "synthetic-" + user + "@example.test", "synthetic-" + user + "@example.test");
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())", membership, user, org, school);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001')", membership);
        owner.update("INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,'Synthetic upgrade session',DATE '2026-04-01',DATE '2027-03-31','ACTIVE',now(),now())", session, org, school);
        owner.update("""
                INSERT INTO admission_applications(id,organisation_id,school_id,idempotency_key,request_fingerprint,intake_session_label,
                  target_grade_code,applicant_legal_name,birth_date,created_by_membership_id,created_at,updated_at)
                VALUES(?,?,?, ?,repeat('c',64),'Synthetic session','VI','Synthetic applicant',DATE '2015-01-01',?,now(),now())
                """, application, org, school, "synthetic-" + application, membership);
        owner.update("INSERT INTO learners(id,organisation_id,school_id,origin_application_id,legal_name,birth_date,gender_code,status,created_at,updated_at) VALUES(?,?,?,?,'Synthetic learner',DATE '2015-01-01','NOT_STATED','ACTIVE',now(),now())", learner, org, school, application);
        owner.update("""
                INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,origin_application_id,
                  grade_code,status,starts_on,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'VI','ACTIVE',DATE '2026-04-01',now(),now())
                """, enrolment, org, school, campus, session, learner, application);
        owner.update("INSERT INTO fee_heads(id,organisation_id,school_id,code,display_name,category,status,created_at,updated_at) VALUES(?,?,?,'TEST','Synthetic tuition','TUITION','ACTIVE',now(),now())", head, org, school);
        owner.update("INSERT INTO fee_plans(id,organisation_id,school_id,academic_session_id,plan_code,display_name,currency_code,receipt_prefix,status,created_by_membership_id,created_at,updated_at) VALUES(?,?,?,?,'TEST','Synthetic legacy fees','INR','SYN','DRAFT',?,now(),now())", plan, org, school, session, membership);
        owner.update("INSERT INTO fee_plan_items(id,organisation_id,school_id,fee_plan_id,fee_head_id,sequence_number,label,due_on,amount,allow_partial,created_at,updated_at) VALUES(?,?,?,?,?,1,'Synthetic installment',?,1000,true,now(),now())", item, org, school, plan, head, schoolToday().minusDays(3));
        owner.update("UPDATE fee_plans SET status='ACTIVE',activated_by_membership_id=?,activated_at=now(),row_version=row_version+1 WHERE id=?", membership, plan);
        owner.update("INSERT INTO learner_fee_accounts(id,organisation_id,school_id,academic_session_id,learner_id,enrolment_id,currency_code,opened_by_membership_id,opened_at) VALUES(?,?,?,?,?,?,'INR',?,now())", account, org, school, session, learner, enrolment, membership);
        owner.update("INSERT INTO fee_plan_assignments(id,organisation_id,school_id,learner_fee_account_id,fee_plan_id,plan_snapshot,assigned_by_membership_id,assigned_at,reason) VALUES(?,?,?,?,?,'{}',?,now(),'Synthetic migration setup')", assignment, org, school, account, plan, membership);
        owner.update("INSERT INTO learner_fee_charges(id,organisation_id,school_id,learner_fee_account_id,fee_plan_assignment_id,fee_plan_item_id,fee_head_code_snapshot,fee_head_name_snapshot,label_snapshot,due_on,amount,allow_partial,created_at) VALUES(?,?,?,?,?,?,'TEST','Synthetic tuition','Synthetic installment',?,1000,true,now())", charge, org, school, account, assignment, item, schoolToday().minusDays(3));
        owner.update("INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,learner_fee_charge_id,entry_type,direction,amount,effective_on,description,source_type,source_id,created_by_membership_id,created_at) VALUES(?,?,?,?,?,'CHARGE','DEBIT',1000,?,'Synthetic charge','FEE_CHARGE',?,?,now())", UUID.randomUUID(), org, school, account, charge, schoolToday().minusDays(3), charge, membership);
        owner.update("INSERT INTO fee_receipt_sequences(organisation_id,school_id,academic_session_id,receipt_prefix,next_value,updated_at) VALUES(?,?,?,'SYN',1,now())", org, school, session);
        var collector = new AuthenticatedUser(user, UUID.randomUUID(), "synthetic@example.test", null, "Synthetic collector", true, org,
                Set.of(membership), Set.of("fees.collect", "fees.refund", "fees.read"), List.of());
        return new Fixture(org, school, session, account, charge, collector);
    }

    private <T> T scoped(AuthenticatedUser actor, Supplier<T> work) {
        try (var ignored = TenantRequestContext.open(actor.userId(), actor.activeOrganisationId())) {
            return transactions.execute(status -> work.get());
        }
    }

    private void applyRuntimeGrants(String url) throws Exception {
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
    }

    private void createRole(String role, String password, String bypass) throws Exception {
        try (var connection = controlDataSource.getConnection();
                var format = connection.prepareStatement("SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER " + bypass + " NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',?,?)")) {
            format.setString(1, role); format.setString(2, password);
            try (var row = format.executeQuery(); var command = connection.createStatement()) {
                row.next(); command.execute(row.getString(1));
            }
        }
    }

    @AfterAll void removeOnlyGeneratedDatabaseAndRoles() {
        if (control != null) {
            if (databaseCreated) control.execute("DROP DATABASE " + identifier(database) + " WITH (FORCE)");
            if (runtimeCreated) control.execute("DROP ROLE " + identifier(runtimeLogin));
            if (ownerCreated) control.execute("DROP ROLE " + identifier(ownerLogin));
        }
        if (container != null) container.stop();
    }

    private static DriverManagerDataSource dataSource(String url, String login, String password) {
        var result = new DriverManagerDataSource(url, login, password);
        var settings = new java.util.Properties();
        settings.setProperty("connectTimeout", "10"); settings.setProperty("socketTimeout", "120");
        settings.setProperty("options", "-c statement_timeout=90000"); result.setConnectionProperties(settings);
        return result;
    }
    private static String identifier(String value) {
        if (!value.matches("(?:educore_manual_upgrade_|synthetic_manual_(?:owner|runtime)_)[a-f0-9]{32}")) {
            throw new IllegalArgumentException("The exact generated upgrade identity is required");
        }
        return '"' + value + '"';
    }
    private static UUID member(AuthenticatedUser actor) { return actor.membershipIds().iterator().next(); }
    private static LocalDate schoolToday() { return LocalDate.now(ZoneId.of("Asia/Kolkata")); }
    private static String secret() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return Base64.getEncoder().encodeToString(value); }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " required"); return value; }
    private record Fixture(UUID org, UUID school, UUID session, UUID account, UUID charge, AuthenticatedUser collector) {}
    private record LegacyReceipt(UUID id, String key, String receiptNumber, FinanceController.PaymentRequest request) {}
}
