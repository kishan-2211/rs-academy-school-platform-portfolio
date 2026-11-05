package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.ProductionDatabaseSecurity;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SensitiveValueCipher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** Real PostgreSQL and actual non-owner RLS. No merchant, HTTP server or school data is used. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnlinePaymentFoundationPostgresIT {
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String database = "educore_online_" + suffix;
    private final String ownerLogin = "synthetic_online_owner_" + suffix;
    private final String runtimeLogin = "synthetic_online_runtime_" + suffix;
    private final String ownerPassword = secret(), runtimePassword = secret();
    private final Clock clock = Clock.systemUTC();
    private PostgreSQLContainer<?> container;
    private JdbcTemplate control, owner, runtime;
    private DriverManagerDataSource controlDataSource;
    private javax.sql.DataSource runtimeDataSource;
    private TransactionTemplate transactions;
    private GatewayReservationService reservations;
    private ApplicationProperties properties;
    private FinanceService finance;
    private com.educore.api.accounting.AccountingService accounting;
    private FeeAccountingService feeAccounting;
    private Fixture refundForwardFixture;
    private UUID refundForwardPayment;
    private java.util.Map<String,List<String>> refundForwardFinancialState;
    private Fixture refundReadForwardFixture;
    private UUID refundReadForwardRequest;
    private java.util.Map<String,List<String>> refundReadForwardFinancialState,refundReadForwardIntentState;
    private boolean legacyIntentSchema = true;
    private boolean legacyFinanceSchema = true;
    private RefundReadFixture refundPostingForwardFixture;
    private java.util.Map<String,List<String>> refundPostingForwardState;
    private java.util.Map<String,List<String>> refundRepairForwardState;
    private java.util.Map<String,List<String>> refundDispatchForwardState;
    private Fixture historicalFamilyForwardFixture;
    private UUID historicalFamilyForwardAttempt;
    private java.util.Map<String,List<String>> historicalFamilyForwardMoney;
    private String url;
    private boolean ownerCreated, runtimeCreated, databaseCreated;
    private boolean bankForwardPreserved;
    private boolean settlementPreparationForwardPreserved;

    @BeforeAll void migratedIsolatedDatabaseAndRealRuntime() throws Exception {
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
        url = controlUrl.replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
        Flyway.configure().dataSource(url, ownerLogin, ownerPassword).locations("classpath:db/migration")
                .target("072").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        owner = new JdbcTemplate(dataSource(url, ownerLogin, ownerPassword));
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='066'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='070'", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_provider_configs", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments", Integer.class)).isZero();
        applyRuntimeGrants();
        runtimeDataSource = (javax.sql.DataSource) new com.educore.api.tenancy.TenantAwareDataSourceBeanPostProcessor()
                .postProcessAfterInitialization(dataSource(url, runtimeLogin, runtimePassword), "dataSource");
        runtime = new JdbcTemplate(runtimeDataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));
        transactions.setTimeout(30);
        ProductionDatabaseSecurity.verify(runtime);
        properties = new ApplicationProperties("https://synthetic-school.example.test", List.of(), Optional.empty(), Optional.empty(),
                new ApplicationProperties.Security("synthetic_session", true, Duration.ofHours(1), Duration.ofHours(2), Duration.ofMinutes(10), null,
                        Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofDays(1), "Synthetic school"),
                new ApplicationProperties.Bootstrap(false, null, null));
        var named = new NamedParameterJdbcTemplate(runtime);
        var audit = new SecurityAuditService(runtime, JsonMapper.builder().build(), clock);
        finance = new FinanceService(named, audit, JsonMapper.builder().build(), clock);
        accounting = new com.educore.api.accounting.AccountingService(named, audit, clock);
        feeAccounting = new FeeAccountingService(named, accounting, audit, JsonMapper.builder().build(), clock);
        reservations = new GatewayReservationService(named, new FamilyFinanceService(named), audit,
                new SensitiveValueCipher(properties, new SecureRandom()), properties, clock);
        // Upgrade a genuinely populated V072 database, not only an empty schema.
        refundForwardFixture=fixture(true);
        refundForwardPayment=refundPostedPayment(refundForwardFixture,"23.45").paymentId();
        refundForwardFinancialState=refundFinancialState(refundForwardFixture);
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("073").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        applyRuntimeGrants();
        // Preserve the original V072 fixture above. A second synthetic school
        // proves V074 upgrades real V073 approved intent without rewriting it.
        refundReadForwardFixture=refundFixture();
        UUID readForwardPayment=refundPostedPayment(refundReadForwardFixture,"75.25").paymentId();
        refundReadForwardRequest=refundSubmit(refundReadForwardFixture,refundReadForwardFixture.admin(),readForwardPayment,
                refundSubmit(refundReadForwardFixture,UUID.randomUUID(),"25.25")).id();
        refundDecide(refundReadForwardFixture,refundReadForwardFixture.reviewer(),refundReadForwardRequest,"APPROVE");
        refundReadForwardFinancialState=refundFinancialState(refundReadForwardFixture);
        refundReadForwardIntentState=refundReadIntentState(refundReadForwardFixture);
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("074").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        applyRuntimeGrants();
        // A third independent synthetic school proves V075 preserves a real
        // retained VERIFIED_SUCCESS read and all prior financial/approval data.
        prepareRefundSuccessForwardFixture();
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("075").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        applyRuntimeGrants();
        prepareRefundReopenForwardFixture();
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("076").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        applyRuntimeGrants();
        historicalFamilyForwardFixture=fixture(true);
        refundPostedPayment(historicalFamilyForwardFixture,"87.25");
        historicalFamilyForwardAttempt=prepare(historicalFamilyForwardFixture,request(historicalFamilyForwardFixture,"123.45")).id();
        historicalFamilyForwardMoney=refundFinancialState(historicalFamilyForwardFixture);
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("077").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        legacyIntentSchema=false;
        applyRuntimeGrants();
        // Prior-schema population is explicitly bounded to BeforeAll. The
        // historical helpers refuse V078; every actual test then uses the
        // current production financial and reservation services without fallback.
        refundPostingForwardFixture=refundReopenHeld(false);
        refundPostingOriginalBook(refundPostingForwardFixture,"CASH");
        refundPostingForwardState=refundPostingProtectedState(refundPostingForwardFixture.fixture());
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("078").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        legacyFinanceSchema=false;
        applyRuntimeGrants();
        refundRepairForwardState=refundPostingProtectedState(refundPostingForwardFixture.fixture());
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("079").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        applyRuntimeGrants();
        refundDispatchForwardState=refundReadProtectedState(refundReadForwardFixture);
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("082").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        applyRuntimeGrants();
        // V083 adds evidence/fences to an actually populated V082 database.
        // Snapshot every pre-existing public row, not just the new empty tables.
        var bankForwardState=new java.util.LinkedHashMap<String,List<String>>();
        for(String table:owner.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class)){
            if(!table.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalStateException("Actual generated schema table required");
            bankForwardState.put(table,owner.queryForList("SELECT row_to_json(snapshot)::text FROM \""+table+"\" snapshot ORDER BY row_to_json(snapshot)::text",String.class));
        }
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("083").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        legacyFinanceSchema=false;
        applyRuntimeGrants();
        bankForwardState.forEach((table,rows)->{
            String where=table.equals("flyway_schema_history")?" WHERE version<>'083' OR version IS NULL":"";
            assertThat(owner.queryForList("SELECT row_to_json(snapshot)::text FROM \""+table+"\" snapshot"+where+" ORDER BY row_to_json(snapshot)::text",String.class)).as("V083 preserves %s",table).isEqualTo(rows);
        });
        for(String table:List.of("fee_gateway_settlement_bank_evidence","fee_gateway_settlement_bank_evidence_rejections"))
            assertThat(owner.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        bankForwardPreserved=true;
        // V084 consumes only newly prepared settlement sources. Preserve every
        // populated V083 row and verify that migration itself consumes nothing.
        var preparationForwardState=new java.util.LinkedHashMap<String,List<String>>();
        for(String table:owner.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class)) {
            if(!table.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalStateException("Actual generated schema table required");
            preparationForwardState.put(table,owner.queryForList("SELECT row_to_json(snapshot)::text FROM \""+table+"\" snapshot ORDER BY row_to_json(snapshot)::text",String.class));
        }
        Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .target("084").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='084'",Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE installed_rank>(SELECT installed_rank FROM flyway_schema_history WHERE version='084')",Integer.class)).isZero();
        applyRuntimeGrants();
        preparationForwardState.forEach((table,rows)->{
            String where=table.equals("flyway_schema_history")?" WHERE version<>'084' OR version IS NULL":"";
            assertThat(owner.queryForList("SELECT row_to_json(snapshot)::text FROM \""+table+"\" snapshot"+where+" ORDER BY row_to_json(snapshot)::text",String.class))
                    .as("V084 preserves %s",table).isEqualTo(rows);
        });
        for(String table:List.of("fee_gateway_settlement_roots","fee_gateway_settlement_preparations","fee_gateway_settlement_sources",
                "fee_gateway_settlement_source_rows","fee_gateway_settlement_components","fee_gateway_settlement_abandonments"))
            assertThat(owner.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        settlementPreparationForwardPreserved=true;
        // Keep the populated V083 -> V084 proof bounded to that migration, then
        // verify every old row again while advancing the actual tests to head.
        var latestForwardState=new java.util.LinkedHashMap<String,List<String>>();
        for(String table:owner.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class)) {
            if(!table.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalStateException("Actual generated schema table required");
            latestForwardState.put(table,owner.queryForList("SELECT row_to_json(snapshot)::text FROM \""+table+"\" snapshot ORDER BY row_to_json(snapshot)::text",String.class));
        }
        int latestHistoryBoundary=owner.queryForObject("SELECT max(installed_rank) FROM flyway_schema_history",Integer.class);
        var latest=Flyway.configure().dataSource(url,ownerLogin,ownerPassword).locations("classpath:db/migration")
                .validateMigrationNaming(true).cleanDisabled(true).load();
        int latestMigrated=latest.migrate().migrationsExecuted;
        assertThat(latest.info().pending()).isEmpty();
        latestForwardState.forEach((table,rows)->{
            String prefix=table.equals("flyway_schema_history")?" WHERE installed_rank<="+latestHistoryBoundary:"";
            assertThat(owner.queryForList("SELECT row_to_json(snapshot)::text FROM \""+table+"\" snapshot"+prefix+" ORDER BY row_to_json(snapshot)::text",String.class))
                    .as("Latest migrations preserve populated V084 %s",table).isEqualTo(rows);
        });
        assertThat(owner.queryForObject("SELECT count(*) FROM flyway_schema_history",Integer.class))
                .isEqualTo(latestForwardState.get("flyway_schema_history").size()+latestMigrated);
        assertThat(owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success",Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='085'",Boolean.class)).isTrue();
        for(String table:List.of("staff_account_invitations","staff_account_invitation_events"))
            assertThat(owner.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        applyRuntimeGrants();
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void refundPostingPopulatedV077UpgradePreservesMoneyAndRefusesHistoricalWriters() {
        var held=refundPostingForwardFixture;var f=held.fixture();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='078'",Boolean.class)).isTrue();
        assertThat(refundPostingProtectedState(f)).isEqualTo(refundPostingForwardState);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_postings WHERE school_id=?",Integer.class,f.school())).isZero();
        UUID capture=owner.queryForObject("SELECT gateway_capture_id FROM fee_payments WHERE id=?",UUID.class,held.paymentId());
        assertThatThrownBy(()->familyGatewayBackground(f,()->LegacyGatewayReceiptFixture.seed(runtime,properties,new SecurityAuditService(runtime,JsonMapper.builder().build(),clock),f.org(),f.school(),capture)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->scoped(f,()->LegacyGatewayIntentFixture.seedV077(runtime,properties,clock,f.parent(),f.learner(),f.account(),f.provider(),request(f,"1"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(refundPostingProtectedState(f)).isEqualTo(refundPostingForwardState);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void refundPostingPartialCashAndAccrualRestoreExactDuesOnceWithIndependentCommonJournal() {
        for(String basis:List.of("CASH","ACCRUAL")) {
            var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,basis);
            var originalReceipt=owner.queryForObject("SELECT row_to_json(payment)::text FROM fee_payments payment WHERE id=?",String.class,held.paymentId());
            var command=refundPostingCommand(held,book.year());
            var result=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),command));
            assertThat(result.amount()).isEqualByComparingTo("50.25");assertThat(result.journalStatus()).isEqualTo("SUBMITTED");
            var family=familyFees(f);assertThat(family.balance()).isEqualByComparingTo("850.25");assertThat(family.totalCredits()).isEqualByComparingTo("149.75");
            assertThat(family.charges().getFirst().paidAmount()).isEqualByComparingTo("149.75");
            assertThat(family.refunds()).hasSize(1);assertThat(family.refunds().getFirst().id()).isEqualTo(result.id());
            assertThat(family.receipts().getFirst().amount()).isEqualByComparingTo("200");
            assertThat(owner.queryForObject("SELECT row_to_json(payment)::text FROM fee_payments payment WHERE id=?",String.class,held.paymentId())).isEqualTo(originalReceipt);
            assertThatThrownBy(()->actorScoped(f.admin(),()->accounting.decideJournal(f.admin(),result.journalId(),
                    new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic forbidden same-person approval",1))))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class);
            actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),result.journalId(),
                    new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent refund counter-journal",1)));
            var retry=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),command));
            assertThat(retry.id()).isEqualTo(result.id());assertThat(retry.journalStatus()).isEqualTo("POSTED");
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_postings WHERE refund_request_id=?",Integer.class,held.refundId())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE source_id=? AND entry_type='REFUND'",Integer.class,result.id())).isEqualTo(1);
            assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
            assertThat(JsonMapper.builder().build().writeValueAsString(family)).doesNotContain("credentials","evidenceSha256","captureId","requestedBy");
            var before=refundPostingProtectedState(f);
            assertThatThrownBy(()->actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),
                    new GatewayRefundPostingModels.Command(f.school(),command.clientRequestId(),book.year(),command.postingOn(),"Synthetic changed immutable reason"))))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(()->actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year()))))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThat(refundPostingProtectedState(f)).isEqualTo(before);
        }
    }

    @Test void refundPostingPendingConflictingClosedForeignAndParentAuthorityCannotCreateMoney() {
        var pending=refundReadApproved();var p=pending.fixture();var pendingBook=refundPostingOriginalBook(pending,"CASH");
        var unchanged=refundPostingProtectedState(p);
        assertThatThrownBy(()->actorScoped(p.admin(),()->refundPostingService().post(p.admin(),pending.refundId(),refundPostingCommand(pending,pendingBook.year()))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundPostingProtectedState(p)).isEqualTo(unchanged);
        for(String condition:List.of("conflicting","closed","parent","foreign")) {
            var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
            if(condition.equals("conflicting"))refundSuccessRead(held,124);
            if(condition.equals("closed"))refundReopenClose(f,"Synthetic original account closure before posting");
            var before=refundPostingProtectedState(f);
            var actor=condition.equals("parent")?f.parent():condition.equals("foreign")?fixture(true).admin():f.admin();
            assertThatThrownBy(()->actorScoped(actor,()->refundPostingService().post(actor,held.refundId(),refundPostingCommand(held,book.year()))))
                    .isInstanceOfAny(com.educore.api.common.api.ApiException.class,DataAccessException.class);
            assertThat(refundPostingProtectedState(f)).isEqualTo(before);
        }
    }

    @Test void refundPostingAuditFailureRollsBackDuesCompletionAndSubmittedJournalTogether() {
        var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
        var failing=org.mockito.Mockito.mock(SecurityAuditService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic audit persistence outage")).when(failing).recordScoped(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap());
        var service=new GatewayRefundPostingService(new NamedParameterJdbcTemplate(runtime),finance,feeAccounting,accounting,failing,clock);
        var before=refundPostingProtectedState(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->service.post(f.admin(),held.refundId(),refundPostingCommand(held,book.year()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(refundPostingProtectedState(f)).isEqualTo(before);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("800");
    }

    @Test void refundPostingRestoredHistoricalDuesCanBeCollectedManuallyOrReservedOnlineWithoutAcademicReactivation() {
        for(boolean online:List.of(false,true)) {
            Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"1000").paymentId();
            UUID refund=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"50.25")).id();
            refundDecide(f,f.reviewer(),refund,"APPROVE");var held=new RefundReadFixture(f,payment,refund);
            var read=refundSuccessRead(held,123);refundSuccessRetain(held,read.id());var book=refundPostingOriginalBook(held,"CASH");
            assertThat(familyFees(f).balance()).isEqualByComparingTo("0");
            UUID current=familyGatewayLaterAccount(f);
            actorScoped(f.admin(),()->refundPostingService().post(f.admin(),refund,refundPostingCommand(held,book.year())));
            assertThat(familyFees(f).accountId()).isEqualTo(current);assertThat(familyFees(f).balance()).isEqualByComparingTo("0");
            var history=actorScoped(f.parent(),()->familyFinance().fees(f.parent(),f.learner(),f.account()));
            assertThat(history.balance()).isEqualByComparingTo("50.25");assertThat(history.charges().getFirst().outstandingAmount()).isEqualByComparingTo("50.25");
            if(online) {
                var reservation=scoped(f,()->reservations.prepare(f.parent(),f.learner(),new GatewayReservationService.PrepareRequest(UUID.randomUUID(),
                        List.of(selection(f,"50.25")),"0000000000",f.account())));
                assertThat(reservation.status()).isEqualTo("RESERVED");
                assertThatThrownBy(()->collect(f,"synthetic-refund-reserved-recollection",payment(f,"50.25")))
                        .isInstanceOf(com.educore.api.common.api.ApiException.class);
            } else {
                collect(f,"synthetic-refund-manual-recollection",payment(f,"50.25"));
                assertThat(actorScoped(f.parent(),()->familyFinance().fees(f.parent(),f.learner(),f.account())).balance()).isEqualByComparingTo("0");
                assertThat(owner.queryForObject("SELECT fee_charge_net_paid(?,?,?,NULL::date)",BigDecimal.class,f.org(),f.school(),f.charge())).isEqualByComparingTo("1000");
            }
            assertThat(owner.queryForObject("SELECT status FROM student_enrolments WHERE id=(SELECT enrolment_id FROM learner_fee_accounts WHERE id=?)",String.class,f.account())).isEqualTo("ENDED");
            assertThat(owner.queryForObject("SELECT amount FROM fee_payments WHERE id=?",BigDecimal.class,payment)).isEqualByComparingTo("1000");
        }
    }

    @Test void refundPostingRefundOnlyDayRegisterExportAndSignedIndependentCloseAgree() {
        Fixture f=refundFixture();LocalDate yesterday=schoolToday().minusDays(1);
        Instant originalPaid=yesterday.atTime(10,0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        UUID payment=feeJournalPostedPayment(f,"200",originalPaid).paymentId();
        UUID refund=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"50.25")).id();refundDecide(f,f.reviewer(),refund,"APPROVE");
        var held=new RefundReadFixture(f,payment,refund);var read=refundSuccessRead(held,123);refundSuccessRetain(held,read.id());
        var book=refundPostingOriginalBook(held,"CASH");var posted=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),refund,refundPostingCommand(held,book.year())));
        var report=actorScoped(f.admin(),()->finance.collections(f.admin(),f.school(),schoolToday(),schoolToday(),"INR",null,null,0,25));
        assertThat(report.summary().grossCollections()).isEqualByComparingTo("0");assertThat(report.summary().refundAmount()).isEqualByComparingTo("50.25");
        assertThat(report.summary().refundCount()).isEqualTo(1);assertThat(report.summary().netCollections()).isEqualByComparingTo("-50.25");
        assertThat(report.items()).hasSize(1);assertThat(report.items().getFirst().kind()).isEqualTo("REFUND");assertThat(report.items().getFirst().id()).isEqualTo(posted.id());
        assertThat(report.items().getFirst().amount()).isEqualByComparingTo("50.25");assertThat(report.items().getFirst().timing().originalReceivedOn()).isEqualTo(yesterday);
        var exported=actorScoped(f.admin(),()->finance.exportCollections(f.admin(),f.school(),schoolToday(),schoolToday(),"INR",null,null));
        assertThat(exported.items()).isEqualTo(report.items());assertThat(exported.summary()).isEqualTo(report.summary());
        var old=actorScoped(f.admin(),()->finance.collections(f.admin(),f.school(),yesterday,yesterday,"INR",null,null,0,25));
        assertThat(old.summary().grossCollections()).isEqualByComparingTo("200");assertThat(old.summary().refundAmount()).isEqualByComparingTo("0");
        var declarations=CollectionRegisterQuery.METHODS.stream().map(method->new FinanceController.TenderDeclaration(method,
                new BigDecimal(method.equals("ONLINE_PROVIDER")?"-50.25":"0"))).toList();
        var close=actorScoped(f.admin(),()->finance.proposeDayClose(f.admin(),new FinanceController.DayCloseRequest(f.school(),schoolToday(),declarations,"Synthetic refund-only signed day closure",null)));
        actorScoped(f.reviewer(),()->finance.decideDayClose(f.reviewer(),close.id(),new FinanceController.DayCloseDecisionRequest(f.school(),"APPROVED","Synthetic independent refund-only close",0)));
        assertThat(owner.queryForObject("SELECT net_collections FROM fee_day_closes WHERE id=?",BigDecimal.class,close.id())).isEqualByComparingTo("-50.25");
        assertThat(owner.queryForObject("SELECT refund_amount FROM fee_day_closes WHERE id=?",BigDecimal.class,close.id())).isEqualByComparingTo("50.25");
        assertThat(owner.queryForObject("SELECT status FROM fee_day_closes WHERE id=?",String.class,close.id())).isEqualTo("APPROVED");
        assertThat(owner.queryForObject("SELECT amount FROM fee_payments WHERE id=?",BigDecimal.class,payment)).isEqualByComparingTo("200");
    }

    @Test void refundPostingConcurrentRetriesCannotDuplicateCompletionLedgerOrVoucher() throws Exception {
        for (boolean sameRequest : List.of(true, false)) {
            var held = refundReopenHeld(false); var f = held.fixture(); var book = refundPostingOriginalBook(held, "CASH");
            var first = refundPostingCommand(held, book.year());
            var second = sameRequest ? first : refundPostingCommand(held, book.year());
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var one = workers.submit(() -> { start.await(); return tryRefundPosting(held, first); });
                var two = workers.submit(() -> { start.await(); return tryRefundPosting(held, second); });
                start.countDown();
                var a = one.get(30, TimeUnit.SECONDS); var b = two.get(30, TimeUnit.SECONDS);
                if (sameRequest) { assertThat(a).isNotNull(); assertThat(b).isEqualTo(a); }
                else assertThat(List.of(a == null, b == null)).containsExactlyInAnyOrder(true, false);
            }
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_postings WHERE refund_request_id=?", Integer.class, held.refundId())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='REFUND'", Integer.class, f.school())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_accounting_posting_batches WHERE school_id=? AND event_type='REFUND'", Integer.class, f.school())).isEqualTo(1);
            assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
        }
    }

    private UUID tryRefundPosting(RefundReadFixture held, GatewayRefundPostingModels.Command command) {
        try { return actorScoped(held.fixture().admin(), () -> refundPostingService().post(held.fixture().admin(), held.refundId(), command)).id(); }
        catch (com.educore.api.common.api.ApiException failure) {
            assertThat(failure.code()).isEqualTo("REFUND_ALREADY_POSTED"); return null;
        }
    }

    @Test void refundPostingAccountingRejectionAndRepairPreserveDuesAndPreventOriginalSourceReversal() {
        var held = refundReopenHeld(false); var f = held.fixture(); var book = refundPostingOriginalBook(held, "CASH");
        var original = actorScoped(f.admin(), () -> feeAccounting.verifyOriginalPaymentPosting(f.admin(), f.school(), held.paymentId()));
        var completed = actorScoped(f.admin(), () -> refundPostingService().post(f.admin(), held.refundId(), refundPostingCommand(held, book.year())));
        actorScoped(f.reviewer(), () -> accounting.decideJournal(f.reviewer(), completed.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(), "REJECT", "Synthetic independent accounting rejection", 1)));
        var client = UUID.randomUUID();
        var command = new GatewayRefundPostingModels.JournalRepairCommand(f.school(), client, completed.accountingBatchId(), completed.journalId(),
                book.year(), completed.postingOn(), "Synthetic deliberate accounting-only repair");
        var repaired = actorScoped(f.admin(), () -> refundPostingService().repairJournal(f.admin(), held.refundId(), command));
        assertThat(repaired.journalId()).isNotEqualTo(completed.journalId()); assertThat(repaired.journalStatus()).isEqualTo("SUBMITTED");
        actorScoped(f.reviewer(), () -> accounting.decideJournal(f.reviewer(), repaired.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(), "POST", "Synthetic independent repaired voucher approval", 1)));
        var retry = actorScoped(f.admin(), () -> refundPostingService().repairJournal(f.admin(), held.refundId(), command));
        assertThat(retry.journalId()).isEqualTo(repaired.journalId()); assertThat(retry.journalStatus()).isEqualTo("POSTED");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_postings WHERE refund_request_id=?", Integer.class, held.refundId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='REFUND'", Integer.class, f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_accounting_posting_batches WHERE refund_posting_id=?", Integer.class, completed.id())).isEqualTo(2);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
        assertThatThrownBy(() -> actorScoped(f.admin(), () -> accounting.requestReversal(f.admin(), original.accountingJournalId(),
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(), book.period(), completed.postingOn(),
                        "Synthetic forbidden reversal of a completed refund source"))))
                .isInstanceOfAny(com.educore.api.common.api.ApiException.class, DataAccessException.class);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
        var reversed=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),repaired.journalId(),
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),book.period(),completed.postingOn(),"Synthetic correction of the replacement refund journal")));
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversed.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic independently approved refund-journal reversal",0)));
        var reversedContext=actorScoped(f.admin(),()->refundPostingService().inspect(f.admin(),f.school(),held.refundId()));
        assertThat(reversedContext.canRepairJournal()).isTrue();assertThat(reversedContext.posting().journalStatus()).isEqualTo("REVERSED");
        var again=actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),held.refundId(),
                new GatewayRefundPostingModels.JournalRepairCommand(f.school(),UUID.randomUUID(),repaired.accountingBatchId(),repaired.journalId(),book.year(),completed.postingOn(),"Synthetic deliberate repair after real accounting reversal")));
        assertThatThrownBy(()->actorScoped(f.admin(),()->accounting.decideJournal(f.admin(),again.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic prohibited self approval of repaired journal",1))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),again.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent final replacement posting",1)));
        assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='REFUND'",Integer.class,f.school())).isEqualTo(1);
    }

    @Test void refundJournalRepairPopulatedV078UpgradePreservesOriginalFinancialHistory() {
        var f=refundPostingForwardFixture.fixture();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='079'",Boolean.class)).isTrue();
        assertThat(refundPostingProtectedState(f)).isEqualTo(refundRepairForwardState);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_accounting_posting_batches WHERE school_id=? AND (supersedes_refund_batch_id IS NOT NULL OR supersedes_refund_journal_id IS NOT NULL)",Integer.class,f.school())).isZero();
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void refundJournalRepairClosedOriginalPeriodUsesOpenDateAndExactRetrySurvivesTargetClose() {
        // Real production services with a historical application clock create the
        // approved source; no timestamps, money, triggers or constraints are patched.
        var f=refundFixture();LocalDate originalDate=schoolToday().withDayOfMonth(1).minusDays(1);
        Instant paidAt=originalDate.atTime(9,0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        UUID payment=historicalRefundRepairPayment(f,paidAt);
        Clock historicalClock=Clock.fixed(paidAt.plusSeconds(3600),java.time.ZoneOffset.UTC);
        var historicalRefunds=new GatewayRefundService(new NamedParameterJdbcTemplate(runtime),finance,
                new SecurityAuditService(runtime,JsonMapper.builder().build(),historicalClock),historicalClock);
        UUID refund=actorScoped(f.admin(),()->historicalRefunds.submit(f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"50.25"))).id();
        actorScoped(f.reviewer(),()->historicalRefunds.decide(f.reviewer(),refund,new GatewayRefundModels.DecisionCommand(f.school(),"APPROVE","Synthetic historical independent approval")));
        var held=new RefundReadFixture(f,payment,refund);var read=refundSuccessRead(held,123);refundSuccessRetain(held,read.id());
        var book=refundPostingOriginalBook(held,"ACCRUAL");
        var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),refund,
                new GatewayRefundPostingModels.Command(f.school(),UUID.randomUUID(),book.year(),originalDate,"Synthetic historical reviewed refund recording")));
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"REJECT","Synthetic independent original journal rejection",1)));
        actorScoped(f.reviewer(),()->accounting.transitionPeriod(f.reviewer(),book.period(),
                new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(f.school(),"CLOSE",0,"Synthetic original period closure")));
        String closed=owner.queryForObject("SELECT row_to_json(period)::text FROM accounting_periods period WHERE id=?",String.class,book.period());
        refundReopenClose(f,"Synthetic later closure of original historical fee account");
        assertThat(owner.queryForObject("SELECT status FROM learner_fee_accounts WHERE id=?",String.class,f.account())).isEqualTo("CLOSED");
        var financialBefore=refundEconomicState(f);
        var command=repairCommand(f,completed,book.year(),schoolToday(),UUID.randomUUID());
        var repaired=actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),refund,command));
        assertThat(repaired.postingOn()).isEqualTo(schoolToday());assertThat(repaired.previousJournalId()).isEqualTo(completed.journalId());
        assertThat(repaired.journalStatus()).isEqualTo("SUBMITTED");
        assertThat(refundEconomicState(f)).isEqualTo(financialBefore);
        assertThat(owner.queryForObject("SELECT row_to_json(period)::text FROM accounting_periods period WHERE id=?",String.class,book.period())).isEqualTo(closed);
        var current=actorScoped(f.admin(),()->refundPostingService().inspect(f.admin(),f.school(),refund));
        assertThat(current.posting().postingOn()).isEqualTo(originalDate);assertThat(current.posting().journalPostingOn()).isEqualTo(schoolToday());
        assertThat(current.posting().accountingBatchId()).isEqualTo(repaired.accountingBatchId());
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),repaired.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent open-date correction approval",1)));
        UUID targetPeriod=owner.queryForObject("SELECT accounting_period_id FROM accounting_journals WHERE id=?",UUID.class,repaired.journalId());
        actorScoped(f.reviewer(),()->accounting.transitionPeriod(f.reviewer(),targetPeriod,
                new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(f.school(),"CLOSE",0,"Synthetic accepted target period closure")));
        var beforeRetry=refundPostingProtectedState(f);
        var retry=actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),refund,command));
        assertThat(retry.accountingBatchId()).isEqualTo(repaired.accountingBatchId());assertThat(retry.journalStatus()).isEqualTo("POSTED");
        assertThat(refundPostingProtectedState(f)).isEqualTo(beforeRetry);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='REFUND'",Integer.class,f.school())).isEqualTo(1);
    }

    @Test void refundJournalRepairRejectsChangedTermsStaleSelectionForeignFamilyAndRevokedAuthority() {
        var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
        var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
        var command=repairCommand(f,completed,book.year(),schoolToday(),UUID.randomUUID());
        assertThatThrownBy(()->actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),held.refundId(),command))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"REJECT","Synthetic independent rejection",1)));
        var before=refundPostingProtectedState(f);var foreign=refundFixture();
        assertThatThrownBy(()->actorScoped(f.admin(),()->{
            runtime.queryForObject("SELECT set_config('app.fee_accounting_item_release','approved',true)",String.class);
            return runtime.update("UPDATE fee_accounting_posting_items SET status='RELEASED',released_by_membership_id=?,released_at=clock_timestamp(),release_reason='Synthetic forbidden orphan release',updated_at=clock_timestamp(),row_version=row_version+1 WHERE posting_batch_id=? AND status='ACTIVE'",member(f.admin()),completed.accountingBatchId());
        })).isInstanceOfAny(DataAccessException.class,org.springframework.transaction.TransactionSystemException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        for(var actor:List.of(f.parent(),foreign.admin())) assertThatThrownBy(()->actorScoped(actor,()->refundPostingService().repairJournal(actor,held.refundId(),command))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),held.refundId(),
                new GatewayRefundPostingModels.JournalRepairCommand(f.school(),UUID.randomUUID(),UUID.randomUUID(),completed.journalId(),book.year(),schoolToday(),"Synthetic forged predecessor"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundPostingProtectedState(f)).isEqualTo(before);
        var repaired=actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),held.refundId(),command));
        var after=refundPostingProtectedState(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),held.refundId(),repairCommand(f,completed,book.year(),schoolToday(),UUID.randomUUID()))))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,e->assertThat(e.code()).isEqualTo("FEE_REFUND_JOURNAL_STALE"));
        assertThatThrownBy(()->actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),held.refundId(),
                new GatewayRefundPostingModels.JournalRepairCommand(f.school(),command.clientRequestId(),completed.accountingBatchId(),completed.journalId(),book.year(),schoolToday(),"Synthetic changed exact retry reason"))))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThatThrownBy(()->actorScoped(f.admin(),()->runtime.update("UPDATE fee_accounting_posting_batches SET supersedes_refund_batch_id=NULL WHERE id=?",repaired.accountingBatchId())))
                .isInstanceOf(DataAccessException.class);
        assertThat(refundPostingProtectedState(f)).isEqualTo(after);
        owner.update("UPDATE memberships SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",member(f.admin()));
        assertThatThrownBy(()->actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),held.refundId(),command))).isInstanceOf(com.educore.api.common.api.ApiException.class);
    }

    @Test void refundJournalRepairAuditFailureRollsBackReleaseReplacementAndJournal() {
        var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
        var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"REJECT","Synthetic independent rejection",1)));
        var failedAudit=org.mockito.Mockito.mock(SecurityAuditService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic repair audit outage")).when(failedAudit).recordScoped(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap());
        var brokenAccounting=new FeeAccountingService(new NamedParameterJdbcTemplate(runtime),accounting,failedAudit,JsonMapper.builder().build(),clock);
        var service=new GatewayRefundPostingService(new NamedParameterJdbcTemplate(runtime),finance,brokenAccounting,accounting,failedAudit,clock);
        var before=refundPostingProtectedState(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->service.repairJournal(f.admin(),held.refundId(),repairCommand(f,completed,book.year(),schoolToday(),UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic repair audit outage");
        assertThat(refundPostingProtectedState(f)).isEqualTo(before);
    }

    @Test void refundJournalRepairConcurrentExactAndDifferentCommandsCannotForkOnePredecessor() throws Exception {
        for(boolean same:List.of(true,false)) {
            var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
            var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
            actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                    new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"REJECT","Synthetic independent rejection",1)));
            var first=repairCommand(f,completed,book.year(),schoolToday(),UUID.randomUUID());
            var second=same?first:repairCommand(f,completed,book.year(),schoolToday(),UUID.randomUUID());
            try(var workers=Executors.newFixedThreadPool(2)) {
                var start=new CountDownLatch(1);
                var a=workers.submit(()->{start.await();return repairOutcome(held,first);});
                var b=workers.submit(()->{start.await();return repairOutcome(held,second);});
                start.countDown();UUID one=a.get(30,TimeUnit.SECONDS),two=b.get(30,TimeUnit.SECONDS);
                if(same)assertThat(one).isNotNull().isEqualTo(two);else assertThat((one==null)^(two==null)).isTrue();
            }
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_accounting_posting_batches WHERE supersedes_refund_batch_id=?",Integer.class,completed.accountingBatchId())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='REFUND'",Integer.class,f.school())).isEqualTo(1);
        }
    }

    private UUID repairOutcome(RefundReadFixture held,GatewayRefundPostingModels.JournalRepairCommand command) {
        try{return actorScoped(held.fixture().admin(),()->refundPostingService().repairJournal(held.fixture().admin(),held.refundId(),command)).accountingBatchId();}
        catch(com.educore.api.common.api.ApiException error){assertThat(error.code()).isEqualTo("FEE_REFUND_JOURNAL_STALE");return null;}
    }
    private GatewayRefundPostingModels.JournalRepairCommand repairCommand(Fixture f,GatewayRefundPostingModels.Posting completion,UUID year,LocalDate date,UUID client) {
        return new GatewayRefundPostingModels.JournalRepairCommand(f.school(),client,completion.accountingBatchId(),completion.journalId(),year,date,"Synthetic reviewed accounting-only correction");
    }
    private java.util.Map<String,List<String>> refundEconomicState(Fixture f) {
        var state=new java.util.LinkedHashMap<String,List<String>>(refundFinancialState(f));
        state.keySet().removeIf(name->name.startsWith("accounting_"));return state;
    }

    private UUID historicalRefundRepairPayment(Fixture f,Instant paidAt) {
        var attempt=prepareAt(f,request(f,"200"),Clock.fixed(paidAt.minusSeconds(1),java.time.ZoneOffset.UTC));
        UUID observed=observationAt(f,attempt,"PAID","200",0,1,paidAt.plusSeconds(1)),capture=UUID.randomUUID();
        scoped(f,()->runtime.update("""
                INSERT INTO fee_gateway_captures(id,organisation_id,school_id,attempt_id,payment_provider_config_id,checkout_observation_id,
                  provider_code,provider_environment,merchant_account_reference,checkout_reference,order_reference,payment_reference,
                  amount,currency_code,paid_at,paid_at_exact,detected_at,original_business_date,classification,evidence_sha256)
                VALUES(?,?,?,?,?,?,'CASHFREE','TEST',?,?,'synthetic_order',?,?,'INR',?,?,?,?,'MATCHED',repeat('b',64))
                """,capture,f.org(),f.school(),attempt.id(),f.provider(),observed,"synthetic_merchant_"+f.provider(),attempt.id().toString(),
                "synthetic_historical_repair_"+UUID.randomUUID(),attempt.amount(),GatewayExactInstant.parameter(paidAt),GatewayExactInstant.canonical(paidAt),
                GatewayExactInstant.parameter(paidAt.plusSeconds(2)),LocalDate.ofInstant(paidAt,java.time.ZoneId.of("Asia/Kolkata"))));
        Clock historical=Clock.fixed(paidAt.plusSeconds(3),java.time.ZoneOffset.UTC);
        var service=new FinanceService(new NamedParameterJdbcTemplate(runtime),new SecurityAuditService(runtime,JsonMapper.builder().build(),historical),JsonMapper.builder().build(),historical);
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,f.org())) {
            return transactions.execute(status->service.postVerifiedGatewayCapture(f.org(),f.school(),capture,null,null,null)).paymentId();
        }
    }

    @Test void completedRefundProofReadsExactPostedCashAndAccrualWithoutFinancialWrites() {
        for(String basis:List.of("CASH","ACCRUAL")) {
            var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,basis);
            var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
            actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                    new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted refund proof source",1)));
            var before=feeJournalReadProtectedState(f);
            var proof=completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id()));
            assertThat(proof.refundPostingId()).isEqualTo(completed.id());assertThat(proof.refundId()).isEqualTo(held.refundId());
            assertThat(proof.paymentId()).isEqualTo(held.paymentId());assertThat(proof.postingBatchId()).isEqualTo(completed.accountingBatchId());
            assertThat(proof.accountingJournalId()).isEqualTo(completed.journalId());assertThat(proof.clearingAccountId()).isEqualTo(book.clearing());
            assertThat(proof.accountingBasis()).isEqualTo(basis);assertThat(proof.currencyCode()).isEqualTo("INR");
            assertThat(proof.refundAmount()).isEqualByComparingTo("50.25");
            for(int retry=0;retry<3;retry++)assertThat(completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id()))).isEqualTo(proof);
            assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
            assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='REFUND'",Integer.class,f.school())).isEqualTo(1);
        }
    }

    @Test void applicationMapperReadsBothHistoricalNullSchemasWithoutChangingPostgresFinancialHistory() {
        var mapperHolder=new java.util.concurrent.atomic.AtomicReference<tools.jackson.databind.ObjectMapper>();
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.default-property-inclusion=non_null")
                .run(context->{assertThat(context).hasNotFailed();mapperHolder.set(context.getBean(tools.jackson.databind.ObjectMapper.class));});
        var originalService=feeAccounting;
        var applicationService=new FeeAccountingService(new NamedParameterJdbcTemplate(runtime),accounting,
                new SecurityAuditService(runtime,mapperHolder.get(),clock),mapperHolder.get(),clock);
        try {
            for(String basis:List.of("CASH","ACCRUAL")) for(boolean applicationWriter:List.of(false,true)) {
                feeAccounting=applicationWriter?applicationService:originalService;
                var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,basis);
                var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
                actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                        new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted mapper compatibility source",1)));
                String shape=owner.queryForObject("SELECT source_snapshot::text FROM fee_accounting_posting_items WHERE posting_batch_id=?",String.class,completed.accountingBatchId());
                assertThat(JsonMapper.builder().build().readTree(shape).has("feeHeadId")).isEqualTo(!applicationWriter);
                var before=feeJournalReadProtectedState(f);
                var expectedOriginal=completedRefundProofRead(f,f.admin(),()->originalService.verifyOriginalPaymentPosting(f.admin(),f.school(),held.paymentId()));
                var expectedRefund=completedRefundProofRead(f,f.admin(),()->originalService.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id()));
                for(int retry=0;retry<2;retry++) {
                    assertThat(completedRefundProofRead(f,f.admin(),()->applicationService.verifyOriginalPaymentPosting(f.admin(),f.school(),held.paymentId()))).isEqualTo(expectedOriginal);
                    assertThat(completedRefundProofRead(f,f.admin(),()->applicationService.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id()))).isEqualTo(expectedRefund);
                }
                assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
                assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
            }
        } finally {feeAccounting=originalService;}
    }

    @Test void completedRefundProofFollowsPostedRepairAndDistinguishesPendingFromActualReversal() {
        var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
        var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"REJECT","Synthetic rejected accounting source",1)));
        assertThatThrownBy(()->completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        var repaired=actorScoped(f.admin(),()->refundPostingService().repairJournal(f.admin(),held.refundId(),
                new GatewayRefundPostingModels.JournalRepairCommand(f.school(),UUID.randomUUID(),completed.accountingBatchId(),completed.journalId(),book.year(),completed.postingOn(),"Synthetic deliberately repaired accounting proof source")));
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),repaired.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted repaired refund",1)));
        var proof=completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id()));
        assertThat(proof.postingBatchId()).isEqualTo(repaired.accountingBatchId());assertThat(proof.accountingJournalId()).isEqualTo(repaired.journalId());
        var reversal=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),repaired.journalId(),
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),book.period(),completed.postingOn(),"Synthetic pending refund journal correction")));
        var pending=feeJournalReadProtectedState(f);
        // This historical reader is deliberately NOT absence-of-pending-reversal admission.
        assertThat(completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id()))).isEqualTo(proof);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(pending);
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic independently reversed refund journal",0)));
        var before=feeJournalReadProtectedState(f);
        assertThatThrownBy(()->completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
    }

    @Test void completedRefundProofRetainsClosedRenamedAndInactiveHistory() {
        var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"ACCRUAL");
        var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent historical refund posting",1)));
        var proof=completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id()));
        var periods=owner.queryForList("SELECT id,row_version FROM accounting_periods WHERE financial_year_id=? ORDER BY period_number",book.year());
        actorScoped(f.reviewer(),()->{for(var period:periods)accounting.transitionPeriod(f.reviewer(),(UUID)period.get("id"),
                new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(f.school(),"CLOSE",((Number)period.get("row_version")).longValue(),"Synthetic historical refund period closure"));return null;});
        long version=owner.queryForObject("SELECT row_version FROM accounting_financial_years WHERE id=?",Long.class,book.year());
        actorScoped(f.reviewer(),()->accounting.closeFinancialYear(f.reviewer(),book.year(),
                new com.educore.api.accounting.AccountingController.VersionedReasonRequest(f.school(),version,"Synthetic historical refund year closure")));
        refundReopenClose(f,"Synthetic subsequent original account closure");
        owner.update("UPDATE fee_heads SET display_name='Synthetic later renamed fee head',row_version=row_version+1 WHERE school_id=?",f.school());
        owner.update("UPDATE accounting_accounts SET status='INACTIVE',row_version=row_version+1 WHERE id=?",book.clearing());
        var before=feeJournalReadProtectedState(f);
        for(int retry=0;retry<2;retry++)assertThat(completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id()))).isEqualTo(proof);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT status FROM learner_fee_accounts WHERE id=?",String.class,f.account())).isEqualTo("CLOSED");
        assertThat(owner.queryForObject("SELECT status FROM accounting_financial_years WHERE id=?",String.class,book.year())).isEqualTo("CLOSED");
        assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
    }

    @Test void completedRefundProofDeniesUnpostedForeignFamilyRevokedAndMissingContext() {
        var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
        var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
        var before=feeJournalReadProtectedState(f);
        assertThatThrownBy(()->completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted scoped refund",1)));
        var foreign=fixture(true);var ownBefore=feeJournalReadProtectedState(f);var foreignBefore=feeJournalReadProtectedState(foreign);
        assertThatThrownBy(()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id())).isInstanceOf(com.educore.api.common.api.ApiException.class);
        for(var actor:List.of(f.parent(),foreign.admin()))assertThatThrownBy(()->completedRefundProofRead(f,actor,()->feeAccounting.verifyCompletedRefundPosting(actor,f.school(),completed.id())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),foreign.school(),completed.id())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),UUID.randomUUID())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        UUID role=financeRole(f.org(),List.of("fees.read"));
        owner.update("DELETE FROM membership_roles WHERE membership_id=?",member(f.admin()));
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",member(f.admin()),role);
        assertThatThrownBy(()->completedRefundProofRead(f,f.admin(),()->feeAccounting.verifyCompletedRefundPosting(f.admin(),f.school(),completed.id())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(ownBefore);assertThat(feeJournalReadProtectedState(foreign)).isEqualTo(foreignBefore);
    }

    private <T> T completedRefundProofRead(Fixture f,AuthenticatedUser actor,java.util.function.Supplier<T> action) {
        var read=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));read.setReadOnly(true);read.setTimeout(30);
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(actor.userId(),actor.activeOrganisationId())) {
            return read.execute(status->{assertThat(runtime.queryForObject("SELECT current_setting('transaction_read_only')",String.class)).isEqualTo("on");return action.get();});
        }
    }

    private GatewayRefundPostingService refundPostingService() {
        return new GatewayRefundPostingService(new NamedParameterJdbcTemplate(runtime),finance,feeAccounting,accounting,
                new SecurityAuditService(runtime,JsonMapper.builder().build(),clock),clock);
    }
    private GatewayRefundPostingModels.Command refundPostingCommand(RefundReadFixture held,UUID year) {
        return new GatewayRefundPostingModels.Command(held.fixture().school(),UUID.randomUUID(),year,schoolToday(),"Synthetic reviewed refund financial posting");
    }
    private FeeJournalPgBook refundPostingOriginalBook(RefundReadFixture held,String basis) {
        return refundPostingOriginalBook(held,basis,null);
    }
    private FeeJournalPgBook refundPostingOriginalBook(RefundReadFixture held,String basis,LocalDate endsOn) {
        var f=held.fixture();LocalDate date=feeJournalReceiptDate(f,held.paymentId());
        var book=endsOn==null?feeJournalBook(f,basis,date):feeJournalBook(f,basis,date,endsOn);
        var batch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),"synthetic-original-refund-source-"+UUID.randomUUID(),
                new FeeAccountingController.PostingRequest(f.school(),book.year(),date,"PAYMENT",200,"Synthetic original receipt accounting")));
        UUID journal=feeJournalBatchJournal(f,batch.id());
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),journal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted original receipt",1)));
        return book;
    }
    private java.util.Map<String,List<String>> refundPostingProtectedState(Fixture f) {
        var state=new java.util.LinkedHashMap<String,List<String>>(refundFinancialState(f));
        for(String table:List.of("fee_gateway_refund_requests","fee_gateway_refund_allocations","fee_gateway_refund_decisions","fee_gateway_refund_reads","fee_gateway_refund_successes","fee_accounting_posting_items","fee_accounting_posting_batches","audit_events")) {
            String projected=table.equals("fee_accounting_posting_batches")?"(to_jsonb(snapshot)-'refund_posting_id'-'original_payment_posting_item_id'-'supersedes_refund_batch_id'-'supersedes_refund_journal_id')::text":"row_to_json(snapshot)::text";
            state.put(table,owner.queryForList("SELECT "+projected+" AS protected_row FROM "+table+" snapshot WHERE school_id=? ORDER BY protected_row",String.class,f.school()));
        }
        // A table absent before the genuine V077 upgrade is an empty source, not
        // an invented completion; after upgrade every current row is compared.
        if(!legacyFinanceSchema)state.put("fee_gateway_refund_postings",owner.queryForList("SELECT row_to_json(snapshot)::text FROM fee_gateway_refund_postings snapshot WHERE school_id=? ORDER BY id",String.class,f.school()));
        else state.put("fee_gateway_refund_postings",List.of());
        return state;
    }

    @Test void historicalFamilyPopulatedV076UpgradePreservesLegacyIntentAndMoney() {
        Fixture f=historicalFamilyForwardFixture;
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='077'",Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT account_selection_mode FROM fee_gateway_attempts WHERE id=?",String.class,historicalFamilyForwardAttempt)).isEqualTo("CURRENT_SESSION");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE school_id=? AND account_selection_mode<>'CURRENT_SESSION'",Integer.class,f.school())).isZero();
        assertThat(refundFinancialState(f)).isEqualTo(historicalFamilyForwardMoney);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("912.75");
        assertThat(familyFees(f).receipts()).hasSize(1);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void historicalFamilyCurrentDefaultAndClosedHistoricalStatementsRetainActualBalanceWithoutRefundHold() {
        var held=refundReopenHeld(true);Fixture f=held.fixture();
        UUID current=familyGatewayLaterAccount(f);
        var unchanged=refundReadProtectedState(f);
        var old=actorScoped(f.parent(),()->familyFinance().fees(f.parent(),f.learner(),f.account()));
        assertThat(old.accountId()).isEqualTo(f.account());assertThat(old.balance()).isEqualByComparingTo("800");
        assertThat(old.totalCredits()).isEqualByComparingTo("200");assertThat(old.receipts()).hasSize(1);
        assertThat(familyFees(f).accountId()).isEqualTo(current);assertThat(familyFees(f).balance()).isEqualByComparingTo("0");
        var accounts=actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),f.learner(),0,20));
        assertThat(accounts.currentAccountId()).isEqualTo(current);assertThat(accounts.accounts()).hasSize(2);
        var original=accounts.accounts().stream().filter(a->a.id().equals(f.account())).findFirst().orElseThrow();
        assertThat(original.status()).isEqualTo("CLOSED");assertThat(original.currentSession()).isFalse();assertThat(original.balance()).isEqualByComparingTo("800");
        assertFamilyGatewayPrivateDto(accounts);assertFamilyGatewayPrivateDto(old);
        String json=JsonMapper.builder().build().writeValueAsString(accounts);
        assertThat(json).doesNotContain("HOLD","confirmedUnpostedRefundAmount","originalClosure","closedBy","makerName","reviewerName");
        assertThat(refundReadProtectedState(f)).isEqualTo(unchanged);
        var submitted=refundReopenSubmit(held,f.admin(),refundReopenCommand(held));
        refundReopenDecide(f,f.reviewer(),submitted.id(),"APPROVE");
        assertThat(actorScoped(f.parent(),()->familyFinance().fees(f.parent(),f.learner(),f.account())).balance()).isEqualByComparingTo("800");
        assertThat(owner.queryForObject("SELECT status FROM student_enrolments WHERE id=(SELECT enrolment_id FROM learner_fee_accounts WHERE id=?)",String.class,f.account())).isEqualTo("ENDED");
        assertThat(refundReopenContext(held,f.admin()).confirmedUnpostedRefundAmount()).isEqualByComparingTo("50.25");
    }

    @Test void historicalFamilyAccountPaginationAndLiveOwnChildScopeAreBoundedAndPrivate() {
        Fixture f=fixture(true),foreign=fixture(true);UUID current=familyGatewayLaterAccount(f);
        var before=refundReadProtectedState(f);
        var first=actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),f.learner(),0,1));
        var second=actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),f.learner(),1,1));
        assertThat(first.page()).isZero();assertThat(first.pageSize()).isEqualTo(1);assertThat(first.hasMore()).isTrue();
        assertThat(second.hasMore()).isFalse();assertThat(first.accounts()).hasSize(1);assertThat(second.accounts()).hasSize(1);
        assertThat(java.util.stream.Stream.concat(first.accounts().stream(),second.accounts().stream()).map(a->a.id()).toList()).containsExactlyInAnyOrder(f.account(),current);
        assertThat(actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),f.learner(),2,1)).accounts()).isEmpty();
        for(int[] page:List.of(new int[]{-1,20},new int[]{10001,20},new int[]{0,0},new int[]{0,51}))
            assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),f.learner(),page[0],page[1]))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->familyFinance().feeAccounts(f.parent(),f.learner(),0,20)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->familyFinance().fees(f.parent(),f.learner())).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().feeAccounts(null,f.learner(),0,20))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->familyFinance().feeAccounts(f.parent(),f.learner(),0,20))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->familyFinance().fees(f.parent(),f.learner()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        for(AuthenticatedUser stale:List.of(familyGatewayActor(f.parent(),Set.of()),familyGatewayActor(f.parent(),Set.of(member(f.admin()))))) {
            assertThatThrownBy(()->actorScoped(stale,()->familyFinance().feeAccounts(stale,f.learner(),0,20))).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(()->actorScoped(stale,()->familyFinance().fees(stale,f.learner()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        for(UUID child:List.of(foreign.learner(),familyGatewayUnlinkedLearner(f,false),familyGatewayUnlinkedLearner(f,true))) {
            assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),child,0,20))).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().fees(f.parent(),child,f.account()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().fees(f.parent(),f.learner(),foreign.account()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=now(),row_version=row_version+1 WHERE id=?",member(f.parent()));
        assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),f.learner(),0,20))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().fees(f.parent(),f.learner()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().fees(f.parent(),f.learner(),f.account()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("UPDATE memberships SET status='ACTIVE',updated_at=now(),row_version=row_version+1 WHERE id=?",member(f.parent()));
        owner.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED',updated_at=now(),row_version=row_version+1 WHERE learner_id=?",f.learner());
        assertThatThrownBy(()->actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),f.learner(),0,20))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundReadProtectedState(f)).isEqualTo(before);
        owner.update("UPDATE learner_guardian_relationships SET portal_access_status='ACTIVE',updated_at=now(),row_version=row_version+1 WHERE learner_id=?",f.learner());
        owner.update("UPDATE student_enrolments SET status='ENDED',ends_on=starts_on,updated_at=now(),row_version=row_version+1 WHERE learner_id=? AND status='ACTIVE'",f.learner());
        var historical=actorScoped(f.parent(),()->familyFinance().feeAccounts(f.parent(),f.learner(),0,20));
        assertThat(historical.currentAccountId()).isNull();assertThat(historical.accounts()).hasSize(2).allMatch(a->!a.currentSession());
        assertThat(actorScoped(f.parent(),()->familyFinance().fees(f.parent(),f.learner(),f.account())).accountId()).isEqualTo(f.account());
    }

    @Test void historicalFamilyExplicitOldAccountIntentRetainsModeAndOriginalRetryAfterRollover() {
        Fixture f=fixture(true);var stack=familyGatewayStack(f,true,true,true);
        var command=historicalFamilyRequest(f,UUID.randomUUID(),"100",f.account());
        var original=actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),command));
        assertThat(owner.queryForObject("SELECT account_selection_mode FROM fee_gateway_attempts WHERE id=?",String.class,original.id())).isEqualTo("EXPLICIT_ACCOUNT");
        UUID current=familyGatewayLaterAccount(f);var before=familyGatewayFinancialState(f);
        var disabled=familyGatewayStack(f,false,false,false);
        assertThat(actorScoped(f.parent(),()->disabled.service().initiate(f.parent(),f.learner(),command))).isEqualTo(original);
        assertThat(familyGatewayFinancialState(f)).isEqualTo(before);
        var later=actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),historicalFamilyRequest(f,UUID.randomUUID(),"200",f.account())));
        assertThat(later.accountId()).isEqualTo(f.account()).isNotEqualTo(current);assertThat(later.receipt()).isNull();
        assertThat(owner.queryForObject("SELECT account_selection_mode FROM fee_gateway_attempts WHERE id=?",String.class,later.id())).isEqualTo("EXPLICIT_ACCOUNT");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE school_id=?",Integer.class,f.school())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE learner_fee_account_id=?",Integer.class,current)).isZero();
        assertThat(owner.queryForObject("SELECT status FROM student_enrolments WHERE id=(SELECT enrolment_id FROM learner_fee_accounts WHERE id=?)",String.class,f.account())).isEqualTo("ENDED");
        assertFamilyGatewayNoPosting(f);
    }

    @Test void historicalFamilyChangedModeAccountOrClosedSourceCannotDuplicateCheckout() {
        Fixture f=fixture(true);var stack=familyGatewayStack(f,true,true,true);
        var legacy=familyGatewayRequest(f,UUID.randomUUID(),"100");
        var original=actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),legacy));
        var explicit=historicalFamilyRequest(f,UUID.randomUUID(),"200",f.account());
        var selected=actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),explicit));
        UUID current=familyGatewayLaterAccount(f);var unchanged=familyGatewayFinancialState(f);
        for(var changed:List.of(historicalFamilyRequest(f,legacy.clientRequestId(),"100",f.account()),
                familyGatewayRequest(f,explicit.clientRequestId(),"200"),historicalFamilyRequest(f,explicit.clientRequestId(),"200",current))) {
            assertThatThrownBy(()->actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),changed)))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->assertThat(error.code()).isEqualTo("ONLINE_REQUEST_REUSED"));
        }
        assertThat(familyGatewayFinancialState(f)).isEqualTo(unchanged);
        refundReopenClose(f,"Synthetic closed historical payment account");var closed=familyGatewayFinancialState(f);
        assertThatThrownBy(()->actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),historicalFamilyRequest(f,UUID.randomUUID(),"1",f.account()))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),explicit)).id()).isEqualTo(selected.id());
        assertThat(actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),legacy)).id()).isEqualTo(original.id());
        assertThat(familyGatewayFinancialState(f)).isEqualTo(closed);assertFamilyGatewayNoPosting(f);
    }

    @Test void historicalFamilySqlSelectionModeAndActualLiveActorCannotBeForged() {
        Fixture f=fixture(true);var source=prepare(f,request(f,"10"));
        for(boolean privileged:List.of(false,true)) assertThatThrownBy(()->{
            if(privileged)owner.update("UPDATE fee_gateway_attempts SET account_selection_mode='EXPLICIT_ACCOUNT',row_version=row_version+1,updated_at=now() WHERE id=?",source.id());
            else actorScoped(f.parent(),()->runtime.update("UPDATE fee_gateway_attempts SET account_selection_mode='EXPLICIT_ACCOUNT',row_version=row_version+1,updated_at=now() WHERE id=?",source.id()));
        }).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT account_selection_mode FROM fee_gateway_attempts WHERE id=?",String.class,source.id())).isEqualTo("CURRENT_SESSION");
        assertThatThrownBy(()->actorScoped(f.admin(),()->historicalFamilyRawCopy(f,source.id(),"EXPLICIT_ACCOUNT"))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->familyGatewayBackground(f,()->historicalFamilyRawCopy(f,source.id(),"EXPLICIT_ACCOUNT"))).isInstanceOf(DataAccessException.class);
        familyGatewayLaterAccount(f);
        assertThatThrownBy(()->actorScoped(f.parent(),()->historicalFamilyRawCopy(f,source.id(),"CURRENT_SESSION"))).isInstanceOf(DataAccessException.class);
        var explicit=actorScoped(f.parent(),()->historicalFamilyRawCopy(f,source.id(),"EXPLICIT_ACCOUNT"));
        assertThat(owner.queryForObject("SELECT account_selection_mode FROM fee_gateway_attempts WHERE id=?",String.class,explicit)).isEqualTo("EXPLICIT_ACCOUNT");
        int count=owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE school_id=?",Integer.class,f.school());
        owner.update("UPDATE memberships SET valid_until=clock_timestamp()-interval '1 second',updated_at=now(),row_version=row_version+1 WHERE id=?",member(f.parent()));
        assertThatThrownBy(()->actorScoped(f.parent(),()->historicalFamilyRawCopy(f,source.id(),"EXPLICIT_ACCOUNT"))).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE school_id=?",Integer.class,f.school())).isEqualTo(count);
        assertFamilyGatewayNoPosting(f);ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void historicalFamilyExplicitDispatchUsesCurrentApprovedBookWithoutReactivatingOldAcademics() {
        for(String kind:List.of("explicit","legacy","closedBook")) {
            Fixture f=fixture(true);var stack=familyGatewayStack(f,true,true,true);var book=feeJournalBook(f,"CASH",schoolToday());
            var request=kind.equals("legacy")?familyGatewayRequest(f,UUID.randomUUID(),"100"):historicalFamilyRequest(f,UUID.randomUUID(),"100",f.account());
            var payment=actorScoped(f.parent(),()->stack.service().initiate(f.parent(),f.learner(),request));
            familyGatewayLaterAccount(f);
            if(kind.equals("closedBook")) actorScoped(f.reviewer(),()->accounting.transitionPeriod(f.reviewer(),book.period(),
                    new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(f.school(),"CLOSE",0,"Synthetic current accounting period closure")));
            var claim=familyGatewayBackground(f,()->stack.work().claimNextAttempt(familyGatewayBinding(f)));
            var named=new NamedParameterJdbcTemplate(runtime);var dispatch=new GatewayAttemptDispatch(named,stack.registry(),properties,new GatewayCheckoutReadiness(named,accounting));
            if(kind.equals("explicit")) {
                var admitted=familyGatewayBackground(f,()->dispatch.admitCreate(FAMILY_GATEWAY_ROUTE,claim));
                assertThat(admitted.attempt().accountId()).isEqualTo(f.account());assertThat(admitted.book().financialYearId()).isEqualTo(book.year());
                assertThat(owner.queryForObject("SELECT create_dispatched_at IS NOT NULL FROM fee_gateway_attempt_work WHERE attempt_id=?",Boolean.class,payment.id())).isTrue();
            } else {
                assertThatThrownBy(()->familyGatewayBackground(f,()->dispatch.admitCreate(FAMILY_GATEWAY_ROUTE,claim))).isInstanceOf(IllegalStateException.class);
                assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?",java.sql.Timestamp.class,payment.id())).isNull();
            }
            assertThat(owner.queryForObject("SELECT status FROM student_enrolments WHERE id=(SELECT enrolment_id FROM learner_fee_accounts WHERE id=?)",String.class,f.account())).isEqualTo("ENDED");
            assertFamilyGatewayNoPosting(f);
        }
    }

    private FamilyFinanceService familyFinance(){return new FamilyFinanceService(new NamedParameterJdbcTemplate(runtime));}
    private static FamilyGatewayPaymentModels.InitiateRequest historicalFamilyRequest(Fixture f,UUID key,String amount,UUID account) {
        return new FamilyGatewayPaymentModels.InitiateRequest(key,List.of(new FamilyGatewayPaymentModels.ChargeSelection(f.charge(),new BigDecimal(amount))),"0000000000",account);
    }
    /** Direct runtime SQL exercises database enforcement; never bypasses financial posting. */
    private UUID historicalFamilyRawCopy(Fixture f,UUID source,String mode) {
        UUID id=UUID.randomUUID();
        runtime.update("""
                INSERT INTO fee_gateway_attempts(id,organisation_id,school_id,learner_fee_account_id,payment_provider_config_id,
                  initiated_by_user_id,initiated_by_membership_id,client_request_id,request_fingerprint,checkout_reference,
                  amount,currency_code,payer_phone_ciphertext,payer_phone_nonce,return_uri,provider_code,provider_environment,
                  merchant_account_reference,school_time_zone,expires_at,original_business_date,status,created_at,updated_at,row_version,account_selection_mode)
                SELECT ?,organisation_id,school_id,learner_fee_account_id,payment_provider_config_id,
                  initiated_by_user_id,initiated_by_membership_id,?,request_fingerprint,?,amount,currency_code,
                  payer_phone_ciphertext,payer_phone_nonce,?,provider_code,provider_environment,merchant_account_reference,
                  school_time_zone,expires_at,original_business_date,'RESERVED',now(),now(),0,?
                FROM fee_gateway_attempts WHERE id=? AND organisation_id=? AND school_id=?
                """,id,UUID.randomUUID(),id,properties.publicUrl()+"/family/payments/"+id,mode,source,f.org(),f.school());
        runtime.update("""
                INSERT INTO fee_gateway_allocations(id,attempt_id,learner_fee_account_id,learner_fee_charge_id,organisation_id,school_id,
                  amount,status,created_at,updated_at,row_version)
                SELECT ?,?,learner_fee_account_id,learner_fee_charge_id,organisation_id,school_id,amount,'ACTIVE',now(),now(),0
                FROM fee_gateway_allocations WHERE attempt_id=?
                """,UUID.randomUUID(),id,source);
        return id;
    }

    private void applyRuntimeGrants() throws Exception {
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
    }

    private RefundReadFixture refundReopenForwardFixture;
    private java.util.Map<String,List<String>> refundReopenForwardState;
    private String refundReopenForwardSuccess;
    private void prepareRefundReopenForwardFixture() {
        refundReopenForwardFixture=refundReopenHeld(true);
        refundReopenForwardState=refundReadProtectedState(refundReopenForwardFixture.fixture());
        refundReopenForwardSuccess=refundSuccessState(refundReopenForwardFixture);
    }

    @Test void refundReopenPopulatedV075UpgradeRetainsClosedAccountHoldAndAllFinancialHistory() {
        var test=refundReopenForwardFixture;var f=test.fixture();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='076'",Boolean.class)).isTrue();
        assertThat(refundReadProtectedState(f)).isEqualTo(refundReopenForwardState);
        assertThat(refundSuccessState(test)).isEqualTo(refundReopenForwardSuccess);
        for(String table:REOPEN_TABLES) {
            assertThat(owner.queryForObject("SELECT count(*) FROM "+table+" WHERE school_id=?",Integer.class,f.school())).isZero();
            assertThat(owner.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname=?",Boolean.class,table)).isTrue();
        }
        var context=refundReopenContext(test,f.admin());
        assertThat(context.sourceState()).isEqualTo("HOLD");assertThat(context.accountState()).isEqualTo("CLOSED");
        assertThat(context.canRequest()).isTrue();assertThat(context.refundPosted()).isFalse();
        assertThat(context.currentBalance()).isEqualByComparingTo("800.00");
        assertThat(context.confirmedUnpostedRefundAmount()).isEqualByComparingTo("50.25");
        assertThat(context.currentClosure().reason()).isEqualTo("Synthetic original session closure");
        assertThat(refundReadProtectedState(f)).isEqualTo(refundReopenForwardState);
    }

    @Test void refundReopenIndependentApprovalPreservesOriginalClosureAndExactRetryNeverReappliesAfterReclosure() {
        var test=refundReopenHeld(true);var f=test.fixture();var context=refundReopenContext(test,f.admin());
        LocalDate processedOn=owner.queryForObject("SELECT processed_on FROM fee_gateway_refund_successes WHERE refund_request_id=?",LocalDate.class,test.refundId());
        UUID financialClose=refundSuccessApproveClose(f,processedOn);String closedFinancialHistory=refundSuccessCloseState(financialClose);
        var before=refundReopenMoneyState(f);var command=new RefundAccountReopenModels.Submit(f.school(),UUID.randomUUID(),context.accountVersion(),"Synthetic reviewed original account");
        var request=refundReopenSubmit(test,f.admin(),command);
        assertThat(request.state()).isEqualTo("SUBMITTED");assertThat(request.accountReopened()).isFalse();assertThat(request.refundPosted()).isFalse();
        var once=refundReopenAllState(f);
        long epochBeforeRetry=owner.queryForObject("SELECT epoch FROM fee_refund_account_reopen_fences WHERE learner_fee_account_id=?",Long.class,f.account());
        assertThat(refundReopenSubmit(test,f.admin(),command)).isEqualTo(request);assertThat(refundReopenAllState(f)).isEqualTo(once);
        assertThat(owner.queryForObject("SELECT epoch FROM fee_refund_account_reopen_fences WHERE learner_fee_account_id=?",Long.class,f.account())).isGreaterThan(epochBeforeRetry);
        assertThatThrownBy(()->refundReopenSubmit(test,f.admin(),new RefundAccountReopenModels.Submit(f.school(),command.clientRequestId(),context.accountVersion(),"Synthetic changed reason")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundReopenDecide(f,f.admin(),request.id(),"APPROVE")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var approved=refundReopenDecide(f,f.reviewer(),request.id(),"APPROVE");
        assertThat(approved.state()).isEqualTo("APPROVED");assertThat(approved.accountReopened()).isTrue();assertThat(approved.refundPosted()).isFalse();
        var reopened=refundReopenContext(test,f.admin());
        assertThat(reopened.accountId()).isEqualTo(f.account());assertThat(reopened.accountState()).isEqualTo("ACTIVE");
        assertThat(reopened.accountVersion()).isEqualTo(context.accountVersion()+1);assertThat(reopened.currentClosure()).isNull();
        var history=reopened.requests().getFirst();assertThat(history.originalClosure()).isEqualTo(context.currentClosure());
        assertThat(history.closedAccountVersion()).isEqualTo(context.accountVersion());assertThat(history.decision().decision()).isEqualTo("APPROVE");
        assertThat(refundReopenMoneyState(f)).isEqualTo(before);
        assertThat(refundSuccessCloseState(financialClose)).isEqualTo(closedFinancialHistory);
        refundReopenClose(f,"Synthetic later independent account closure");var reclosed=refundReopenAllState(f);
        assertThat(refundReopenDecide(f,f.reviewer(),request.id(),"APPROVE")).isEqualTo(approved);
        assertThat(refundReopenAllState(f)).isEqualTo(reclosed);
        assertThat(refundReopenContext(test,f.admin()).accountState()).isEqualTo("CLOSED");
        assertThat(refundReopenContext(test,f.admin()).requests().getFirst().originalClosure()).isEqualTo(context.currentClosure());
        assertThat(refundReopenMoneyState(f)).isEqualTo(before);
    }

    @Test void refundReopenRequiresApprovedRetainedNonconflictingHoldAndClosedOriginalAccount() {
        var active=refundReopenHeld(false);var a=active.fixture();
        assertThat(refundReopenContext(active,a.admin()).canRequest()).isFalse();
        assertThatThrownBy(()->refundReopenSubmit(active,a.admin(),refundReopenCommand(active))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var missing=refundReadApproved();refundReopenClose(missing.fixture(),"Synthetic closed without retained success");
        var noHold=refundReopenContext(missing,missing.fixture().admin());
        assertThat(noHold.sourceState()).isEqualTo("NO_HOLD");assertThat(noHold.confirmedUnpostedRefundAmount()).isNull();assertThat(noHold.canRequest()).isFalse();
        assertThatThrownBy(()->refundReopenSubmit(missing,missing.fixture().admin(),refundReopenCommand(missing))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var conflicting=refundReopenHeld(true);var f=conflicting.fixture();refundSuccessRead(conflicting,124);
        var state=refundReopenAllState(f);var blocked=refundReopenContext(conflicting,f.admin());
        assertThat(blocked.sourceState()).isEqualTo("CONFLICTING");assertThat(blocked.canRequest()).isFalse();assertThat(blocked.confirmedUnpostedRefundAmount()).isNull();
        assertThatThrownBy(()->refundReopenSubmit(conflicting,f.admin(),refundReopenCommand(conflicting))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundReopenAllState(f)).isEqualTo(state);
        var retained=refundReopenHeld(true);var expected=refundReadPrepare(retained).expected();
        refundReadRetain(retained,UUID.randomUUID(),refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.PENDING,null));
        assertThat(refundReopenContext(retained,retained.fixture().admin()).sourceState()).isEqualTo("HOLD");
    }

    @Test void refundReopenStaleReviewCannotApproveButIndependentRejectAndMakerCancellationPreserveHistory() {
        var test=refundReopenHeld(true);var f=test.fixture();var request=refundReopenSubmit(test,f.admin(),refundReopenCommand(test));
        // A legitimate changed account version makes this pending snapshot stale.
        owner.update("UPDATE learner_fee_accounts SET row_version=row_version+1 WHERE id=?",f.account());
        var before=refundReopenMoneyState(f);var pending=refundReopenContext(test,f.reviewer()).requests().getFirst();
        assertThat(pending.canApprove()).isFalse();assertThat(pending.canReject()).isTrue();assertThat(pending.canCancel()).isFalse();
        assertThatThrownBy(()->refundReopenDecide(f,f.reviewer(),request.id(),"APPROVE")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundReopenDecide(f,f.reviewer(),request.id(),"REJECT").state()).isEqualTo("REJECTED");
        assertThat(refundReopenMoneyState(f)).isEqualTo(before);
        var next=refundReopenSubmit(test,f.admin(),refundReopenCommand(test));
        assertThatThrownBy(()->refundReopenDecide(f,f.reviewer(),next.id(),"CANCEL")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundReopenDecide(f,f.admin(),next.id(),"CANCEL").state()).isEqualTo("CANCELLED");
        assertThat(refundReopenContext(test,f.admin()).requests()).hasSize(2);
        assertThat(refundReopenMoneyState(f)).isEqualTo(before);
        var conflict=refundReopenHeld(true);var c=conflict.fixture();var cr=refundReopenSubmit(conflict,c.admin(),refundReopenCommand(conflict));
        refundSuccessRead(conflict,124);
        assertThat(refundReopenContext(conflict,c.reviewer()).requests().getFirst().canApprove()).isFalse();
        assertThatThrownBy(()->refundReopenDecide(c,c.reviewer(),cr.id(),"APPROVE")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundReopenDecide(c,c.reviewer(),cr.id(),"REJECT").state()).isEqualTo("REJECTED");
    }

    @Test void refundReopenScopesLiveHumanAuthorityQueueAndPrivateHistoryWithoutChangingMoney() throws Exception {
        var test=refundReopenHeld(true);var f=test.fixture();var foreign=refundReopenHeld(true);var command=refundReopenCommand(test);
        var request=refundReopenSubmit(test,f.admin(),command);var before=refundReopenAllState(f);
        assertThatThrownBy(()->refundReopenContext(test,f.parent())).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundReopenSubmit(test,f.parent(),command)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(foreign.fixture().admin(),()->refundReopenService().context(foreign.fixture().admin(),f.school(),test.refundId())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.parent(),()->refundReopenService().context(f.admin(),f.school(),test.refundId())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundReopenService().context(f.admin(),f.school(),test.refundId())).isInstanceOf(com.educore.api.common.api.ApiException.class);
        UUID siblingSchool=UUID.randomUUID(),siblingMember=UUID.randomUUID();
        owner.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'synthetic-reopen-sibling','Synthetic sibling school','Synthetic sibling school','ACTIVE',now(),now())",siblingSchool,f.org());
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())",siblingMember,f.parent().userId(),f.org(),siblingSchool);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001'::uuid)",siblingMember);
        var sibling=actor(f.parent().userId(),siblingMember,f.org());
        assertThat(actorScoped(sibling,()->refundReopenService().queue(sibling,siblingSchool,"ALL",null,0,25)).items()).isEmpty();
        assertThatThrownBy(()->actorScoped(sibling,()->refundReopenService().context(sibling,f.school(),test.refundId()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(sibling,()->refundReopenService().context(sibling,siblingSchool,test.refundId()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(sibling,()->refundReopenService().submit(sibling,test.refundId(),new RefundAccountReopenModels.Submit(siblingSchool,UUID.randomUUID(),command.expectedAccountVersion(),command.reason())))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(sibling,()->refundReopenService().decide(sibling,request.id(),new RefundAccountReopenModels.DecisionCommand(siblingSchool,"APPROVE","Synthetic wrong school reviewer")))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var queue=actorScoped(f.reviewer(),()->refundReopenService().queue(f.reviewer(),f.school(),"SUBMITTED",null,0,1));
        assertThat(queue.total()).isEqualTo(1);assertThat(queue.items().getFirst().id()).isEqualTo(request.id());assertThat(queue.refundPosted()).isFalse();
        var literal=actorScoped(f.reviewer(),()->refundReopenService().queue(f.reviewer(),f.school(),"ALL","%_",0,25));assertThat(literal.items()).isEmpty();
        var empty=actorScoped(f.reviewer(),()->refundReopenService().queue(f.reviewer(),f.school(),"ALL",null,1,1));assertThat(empty.items()).isEmpty();
        for(String table:REOPEN_TABLES)assertThat(actorScoped(foreign.fixture().admin(),()->runtime.queryForObject("SELECT count(*) FROM "+table+" WHERE school_id=?",Integer.class,f.school()))).isZero();
        owner.update("UPDATE memberships SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",member(f.reviewer()));
        assertThatThrownBy(()->refundReopenDecide(f,f.reviewer(),request.id(),"APPROVE")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundReopenAllState(f)).isEqualTo(before);
        for(boolean expires:List.of(true,false)) {
            var waiting=refundReopenHeld(true);var w=waiting.fixture();var made=refundReopenSubmit(waiting,w.admin(),refundReopenCommand(waiting));var unchanged=refundReopenAllState(w);
            try(var workers=Executors.newSingleThreadExecutor();var locked=owner.getDataSource().getConnection()) {
                locked.setAutoCommit(false);
                try(var statement=locked.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){statement.setString(1,w.school()+":finance");statement.execute();}
                if(expires)owner.update("UPDATE memberships SET valid_until=clock_timestamp()+interval '2 seconds',row_version=row_version+1 WHERE id=?",member(w.reviewer()));
                var decision=workers.submit(()->refundReopenDecide(w,w.reviewer(),made.id(),"APPROVE"));
                try {
                    awaitFamilyGatewayFinanceWait();
                    if(expires)awaitFamilyGatewayTime(owner.queryForObject("SELECT valid_until FROM memberships WHERE id=?",(r,n)->r.getObject(1,java.time.OffsetDateTime.class).toInstant(),member(w.reviewer())));
                    else owner.update("DELETE FROM membership_roles WHERE membership_id=?",member(w.reviewer()));
                    locked.commit();
                    assertThatThrownBy(()->decision.get(20,TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(com.educore.api.common.api.ApiException.class);
                } finally {locked.rollback();}
            }
            assertThat(refundReopenAllState(w)).isEqualTo(unchanged);assertThat(refundReopenContext(waiting,w.admin()).accountState()).isEqualTo("CLOSED");
        }
    }

    @Test void refundReopenAuditFailureRollsBackRequestDecisionAndAccountTransitionTogether() {
        var test=refundReopenHeld(true);var f=test.fixture();var command=refundReopenCommand(test);
        var audit=org.mockito.Mockito.mock(SecurityAuditService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic account review audit failure")).when(audit).recordScoped(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap());
        var broken=new RefundAccountReopenService(new NamedParameterJdbcTemplate(runtime),finance,audit);
        var before=refundReopenAllState(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->broken.submit(f.admin(),test.refundId(),command))).isInstanceOf(IllegalStateException.class);
        assertThat(refundReopenAllState(f)).isEqualTo(before);
        var made=refundReopenSubmit(test,f.admin(),command);var requested=refundReopenAllState(f);
        assertThatThrownBy(()->actorScoped(f.reviewer(),()->broken.decide(f.reviewer(),made.id(),new RefundAccountReopenModels.DecisionCommand(f.school(),"APPROVE","Synthetic account reopening decision"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(refundReopenAllState(f)).isEqualTo(requested);
        assertThat(refundReopenContext(test,f.admin()).accountState()).isEqualTo("CLOSED");
    }

    @Test void refundReopenDirectSqlCannotForgeReviewMutateEvidenceOrReplayAConsumedTransition() {
        var test=refundReopenHeld(true);var f=test.fixture();long closedVersion=refundReopenContext(test,f.admin()).accountVersion();
        var before=refundReopenAllState(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->runtime.update("UPDATE learner_fee_accounts SET status='ACTIVE',closed_by_membership_id=NULL,closed_at=NULL,close_reason=NULL,row_version=row_version+1 WHERE id=?",f.account()))).isInstanceOf(DataAccessException.class);
        for(String change:List.of("row_version=row_version-1","close_reason='Synthetic silently replaced closure'","currency_code='USD'","opened_at=opened_at+interval '1 second'"))
            assertThatThrownBy(()->owner.update("UPDATE learner_fee_accounts SET "+change+" WHERE id=?",f.account())).isInstanceOf(DataAccessException.class);
        assertThat(refundReopenAllState(f)).isEqualTo(before);
        var made=refundReopenSubmit(test,f.admin(),refundReopenCommand(test));
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefundReopenDecision(f,made.id(),"APPROVE",closedVersion))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->actorScoped(f.reviewer(),()->rawRefundReopenDecision(f,made.id(),"CANCEL",closedVersion))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->actorScoped(f.reviewer(),()->rawRefundReopenDecision(f,made.id(),"APPROVE",closedVersion+1))).isInstanceOf(DataAccessException.class);
        assertThat(refundReopenContext(test,f.admin()).accountState()).isEqualTo("CLOSED");
        refundReopenDecide(f,f.reviewer(),made.id(),"REJECT");
        for(var changes:List.of(java.util.Map.of("learner_fee_account_id","'"+UUID.randomUUID()+"'::uuid"),
                java.util.Map.of("account_row_version","account_row_version+1"),java.util.Map.of("account_close_reason","'Synthetic forged closure'"),
                java.util.Map.of("success_evidence_sha256","repeat('f',64)")))
            assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefundReopenCopy(made.id(),changes))).isInstanceOf(DataAccessException.class);
        var next=refundReopenSubmit(test,f.admin(),refundReopenCommand(test));
        // Actual non-owner SQL approval must atomically apply the exact transition,
        // not merely create a decision that could later be used as a bearer token.
        actorScoped(f.reviewer(),()->rawRefundReopenDecision(f,next.id(),"APPROVE",closedVersion));
        assertThat(refundReopenContext(test,f.admin()).accountState()).isEqualTo("ACTIVE");
        for(String table:List.of(REOPEN_TABLES.get(0),REOPEN_TABLES.get(1))) {
            assertThat(actorScoped(f.admin(),()->runtime.update("UPDATE "+table+" SET reason='Synthetic tamper' WHERE school_id=?",f.school()))).isZero();
            assertThatThrownBy(()->owner.update("UPDATE "+table+" SET reason='Synthetic tamper' WHERE school_id=?",f.school())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(()->owner.update("DELETE FROM "+table+" WHERE school_id=?",f.school())).isInstanceOf(DataAccessException.class);
        }
        refundReopenClose(f,"Synthetic closure after consumed approval");
        assertThatThrownBy(()->actorScoped(f.reviewer(),()->runtime.update("UPDATE learner_fee_accounts SET status='ACTIVE',closed_by_membership_id=NULL,closed_at=NULL,close_reason=NULL,row_version=row_version+1 WHERE id=?",f.account()))).isInstanceOf(DataAccessException.class);
        assertThat(refundReopenContext(test,f.admin()).accountState()).isEqualTo("CLOSED");
    }

    @Test void refundReopenConcurrentRequestsCannotDuplicatePendingReviewUnderReadCommittedRepeatableOrSerializable() throws Exception {
        for(int isolation:List.of(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED,
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,
                org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            var test=refundReopenHeld(true);var f=test.fixture();var money=refundReopenMoneyState(f);
            var command=refundReopenCommand(test);var other=new RefundAccountReopenModels.Submit(f.school(),UUID.randomUUID(),command.expectedAccountVersion(),command.reason());
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var first=executor.submit(()->concurrentRefundReopen(test,command,isolation,ready,start));
                var second=executor.submit(()->concurrentRefundReopen(test,other,isolation,ready,start));
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
                assertThat((first.get(20,TimeUnit.SECONDS)?1:0)+(second.get(20,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            }
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_refund_account_reopen_requests WHERE learner_fee_account_id=?",Integer.class,f.account())).isEqualTo(1);
            assertThat(refundReopenContext(test,f.admin()).accountState()).isEqualTo("CLOSED");assertThat(refundReopenMoneyState(f)).isEqualTo(money);
        }
    }

    @Test void refundReopenStaleApprovalCannotMissContradictorySuccessAtRepeatableOrSerializableSnapshots() throws Exception {
        for(int isolation:List.of(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,
                org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            var test=refundReopenHeld(true);var f=test.fixture();var made=refundReopenSubmit(test,f.admin(),refundReopenCommand(test));
            var ready=new CountDownLatch(1);var start=new CountDownLatch(1);
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var stale=executor.submit(()->staleRefundReopenApproval(test,made.id(),isolation,ready,start));
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();refundSuccessRead(test,124);start.countDown();
                assertThat(stale.get(20,TimeUnit.SECONDS)).isEqualTo("40001");
            }
            assertThat(refundReopenContext(test,f.admin()).accountState()).isEqualTo("CLOSED");
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_refund_account_reopen_decisions WHERE reopen_request_id=?",Integer.class,made.id())).isZero();
            assertThatThrownBy(()->refundReopenDecide(f,f.reviewer(),made.id(),"APPROVE")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
    }

    private int rawRefundReopenDecision(Fixture f,UUID request,String decision,long version) {
        return runtime.update("""
                INSERT INTO fee_refund_account_reopen_decisions(id,organisation_id,school_id,reopen_request_id,learner_fee_account_id,
                    closed_account_version,decision,reason,decided_by_user_id,decided_by_membership_id,applied_account_version)
                VALUES(?,?,?,?,?,?,?,'Synthetic SQL original account decision',?,?,?)
                """,UUID.randomUUID(),f.org(),f.school(),request,f.account(),version,decision,
                com.educore.api.tenancy.TenantRequestContext.current().orElseThrow().userId(),
                runtime.queryForObject("SELECT id FROM memberships WHERE organisation_id=? AND school_id=? AND user_id=?",UUID.class,f.org(),f.school(),com.educore.api.tenancy.TenantRequestContext.current().orElseThrow().userId()),
                decision.equals("APPROVE")?version+1:null);
    }
    private int rawRefundReopenCopy(UUID source,java.util.Map<String,String> overrides) {
        var columns=List.of("id","organisation_id","school_id","refund_request_id","fee_payment_id","learner_fee_account_id","verified_read_id","success_evidence_sha256","success_source_version",
                "account_academic_session_id","account_learner_id","account_enrolment_id","account_currency_code","account_opened_by_membership_id","account_opened_at","account_closed_by_membership_id",
                "account_closed_at","account_close_reason","closed_by_name_snapshot","account_row_version","client_request_id","request_fingerprint","reason","requested_by_user_id","requested_by_membership_id","requested_at");
        if(!columns.containsAll(overrides.keySet())||overrides.containsKey("id")||overrides.containsKey("client_request_id"))throw new IllegalArgumentException("Only fixed synthetic source columns may change");
        var selected=columns.stream().map(column->column.equals("id")||column.equals("client_request_id")?"?":overrides.getOrDefault(column,column)).collect(java.util.stream.Collectors.joining(","));
        return runtime.update("INSERT INTO fee_refund_account_reopen_requests("+String.join(",",columns)+") SELECT "+selected+" FROM fee_refund_account_reopen_requests WHERE id=?",UUID.randomUUID(),UUID.randomUUID(),source);
    }
    private boolean concurrentRefundReopen(RefundReadFixture test,RefundAccountReopenModels.Submit command,int isolation,CountDownLatch ready,CountDownLatch start) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));tx.setTimeout(20);tx.setIsolationLevel(isolation);var f=test.fixture();
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(f.admin().userId(),f.org())) {
            try{return tx.execute(status->{runtime.queryForObject("SELECT count(*) FROM fee_refund_account_reopen_requests WHERE school_id=?",Integer.class,f.school());ready.countDown();refundSuccessAwait(start);refundReopenService().submit(f.admin(),test.refundId(),command);return true;});}
            catch(com.educore.api.common.api.ApiException|DataAccessException denied){return false;}
        }
    }
    private String staleRefundReopenApproval(RefundReadFixture test,UUID request,int isolation,CountDownLatch ready,CountDownLatch start) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));tx.setTimeout(20);tx.setIsolationLevel(isolation);var f=test.fixture();
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(f.reviewer().userId(),f.org())) {
            try {tx.execute(status->{runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId());ready.countDown();refundSuccessAwait(start);
                refundReopenService().decide(f.reviewer(),request,new RefundAccountReopenModels.DecisionCommand(f.school(),"APPROVE","Synthetic stale account review"));return true;});return "UNEXPECTED_SUCCESS";}
            catch(DataAccessException rejected){for(Throwable cause=rejected;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql)return sql.getSQLState();throw rejected;}
        }
    }

    private static final List<String> REOPEN_TABLES=List.of("fee_refund_account_reopen_requests","fee_refund_account_reopen_decisions","fee_refund_account_reopen_fences");
    private RefundReadFixture refundReopenHeld(boolean closed) {
        var test=refundReadApproved();var read=refundSuccessRead(test,123);refundSuccessRetain(test,read.id());
        if(closed)refundReopenClose(test.fixture(),"Synthetic original session closure");return test;
    }
    private void refundReopenClose(Fixture f,String reason) {
        owner.update("UPDATE learner_fee_accounts SET status='CLOSED',closed_by_membership_id=?,closed_at=clock_timestamp(),close_reason=?,row_version=row_version+1 WHERE id=? AND status='ACTIVE'",member(f.admin()),reason,f.account());
    }
    private RefundAccountReopenService refundReopenService(){return new RefundAccountReopenService(new NamedParameterJdbcTemplate(runtime),finance,new SecurityAuditService(runtime,JsonMapper.builder().build(),clock));}
    private RefundAccountReopenModels.Context refundReopenContext(RefundReadFixture test,AuthenticatedUser actor){return actorScoped(actor,()->refundReopenService().context(actor,test.fixture().school(),test.refundId()));}
    private RefundAccountReopenModels.Submit refundReopenCommand(RefundReadFixture test){return new RefundAccountReopenModels.Submit(test.fixture().school(),UUID.randomUUID(),refundReopenContext(test,test.fixture().admin()).accountVersion(),"Synthetic reviewed original account");}
    private RefundAccountReopenModels.Result refundReopenSubmit(RefundReadFixture test,AuthenticatedUser actor,RefundAccountReopenModels.Submit command){return actorScoped(actor,()->refundReopenService().submit(actor,test.refundId(),command));}
    private RefundAccountReopenModels.Result refundReopenDecide(Fixture f,AuthenticatedUser actor,UUID request,String decision){return actorScoped(actor,()->refundReopenService().decide(actor,request,new RefundAccountReopenModels.DecisionCommand(f.school(),decision,"Synthetic account reopening decision")));}
    private java.util.Map<String,List<String>> refundReopenMoneyState(Fixture f){var result=new java.util.LinkedHashMap<String,List<String>>(refundFinancialState(f));result.remove("learner_fee_accounts");return result;}
    private java.util.Map<String,List<String>> refundReopenAllState(Fixture f){var result=new java.util.LinkedHashMap<String,List<String>>(refundReadProtectedState(f));for(String table:List.of(REOPEN_TABLES.get(0),REOPEN_TABLES.get(1)))result.put(table,owner.queryForList("SELECT row_to_json(e)::text FROM "+table+" e WHERE school_id=? ORDER BY row_to_json(e)::text",String.class,f.school()));return result;}

    @Test void refundProcessingReadTracksRealWorkerEvidenceAndRestoredDuesWithoutChangingFinancialHistory() throws Exception {
        var test=refundWorkerDriver();var f=test.source().refund().fixture();var service=refundProcessingService(test,List.of(test.route()),true);
        var money=refundFinancialState(f);var queued=refundProcessingQueue(f,service);
        assertThat(queued.executionEnabled()).isTrue();assertThat(queued.items()).hasSize(1);
        assertThat(queued.items().getFirst().processing().state()).isEqualTo("AWAITING_PROCESSING");
        assertThat(queued.items().getFirst().processing().lastCheckedAt()).isNull();
        assertThat(refundProcessingQueue(f,service)).isEqualTo(queued);assertThat(refundFinancialState(f)).isEqualTo(money);
        assertThat(test.worker().runNext(FAMILY_GATEWAY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(refundProcessingQueue(f,service).items().getFirst().processing().state()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(refundFinancialState(f)).isEqualTo(money);refundWorkerWaitDue(test);
        assertThat(test.worker().runNext(FAMILY_GATEWAY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var held=refundProcessingQueue(f,service).items().getFirst();
        assertThat(held.status()).isEqualTo("APPROVED");assertThat(held.processing().state()).isEqualTo("VERIFIED_AWAITING_POSTING");
        assertThat(held.processing().lastCheckedAt()).isNotNull();assertThat(held.processing().nextCheckAt()).isNull();
        assertThat(held.processing().requiresReview()).isFalse();assertThat(refundFinancialState(f)).isEqualTo(money);
        var posting=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),test.refundId(),refundPostingCommand(test.source().refund(),test.source().book().year())));
        var recorded=refundProcessingQueue(f,service).items().getFirst();var postedMoney=refundFinancialState(f);
        assertThat(recorded.status()).isEqualTo("APPROVED");assertThat(recorded.processing().state()).isEqualTo("DUES_RESTORED");
        assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
        assertThat(owner.queryForObject("SELECT status FROM accounting_journals WHERE id=?",String.class,posting.journalId())).isEqualTo("SUBMITTED");
        assertThat(refundProcessingQueue(f,service).items().getFirst()).isEqualTo(recorded);assertThat(refundFinancialState(f)).isEqualTo(postedMoney);
        var json=JsonMapper.builder().build().valueToTree(recorded).toString();
        assertThat(json).doesNotContain("credentials","merchant","leaseToken","providerConfigId","orderReference","paymentReference","evidenceSha256");
        assertThat(test.posts().get()).isEqualTo(1);assertThat(test.gets().get()).isEqualTo(1);
    }

    @Test void refundProcessingReadKeepsScopeConfigurationAndIndependentReviewDenialsWithoutProviderIo() {
        var test=refundWorkerDriver();var f=test.source().refund().fixture();var foreign=refundFixture();var service=refundProcessingService(test,List.of(test.route()),true);
        var money=refundFinancialState(f);
        assertThat(refundProcessingQueue(f,service).executionEnabled()).isTrue();
        assertThat(refundProcessingQueue(f,refundProcessingService(test,List.of(test.route()),false)).executionEnabled()).isFalse();
        var route=test.route();var mismatched=new GatewayProviderRegistry.Route(route.routeKey(),route.organisationId(),route.schoolId(),route.providerConfigId(),route.providerCode(),route.environment(),route.merchantAccountReference()+"_other",route.credentialsSecretReference(),route.clientIdEnvironmentReference(),route.clientSecretEnvironmentReference());
        assertThat(refundProcessingQueue(f,refundProcessingService(test,List.of(mismatched),true)).executionEnabled()).isFalse();
        var foreignRoute=new GatewayProviderRegistry.Route(route.routeKey(),foreign.org(),foreign.school(),foreign.provider(),route.providerCode(),route.environment(),route.merchantAccountReference(),route.credentialsSecretReference(),route.clientIdEnvironmentReference(),route.clientSecretEnvironmentReference());
        assertThat(refundProcessingQueue(f,refundProcessingService(test,List.of(foreignRoute),true)).executionEnabled()).isFalse();
        assertThat(actorScoped(f.admin(),()->service.queue(f.admin(),f.school(),"ALL","%",0,25)).items()).isEmpty();
        assertThat(actorScoped(f.admin(),()->service.queue(f.admin(),f.school(),"ALL",null,100,25)).total()).isEqualTo(1);
        assertThatThrownBy(()->actorScoped(f.admin(),()->service.queue(f.admin(),f.school(),"PAID",null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.parent(),()->service.queue(f.parent(),f.school(),"ALL",null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(foreign.admin(),()->service.queue(foreign.admin(),f.school(),"ALL",null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(foreign.admin(),()->service.queue(f.admin(),f.school(),"ALL",null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->service.decide(f.admin(),test.refundId(),new GatewayRefundModels.DecisionCommand(f.school(),"APPROVE","Synthetic attempt to replace independent approval")))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("DELETE FROM membership_roles WHERE membership_id=?",member(f.admin()));
        assertThatThrownBy(()->refundProcessingQueue(f,service)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundFinancialState(f)).isEqualTo(money);assertThat(test.posts().get()+test.gets().get()).isZero();
    }

    private GatewayRefundService refundProcessingService(RefundWorkerPg test,List<GatewayProviderRegistry.Route> routes,boolean enabled){
        return new GatewayRefundService(new NamedParameterJdbcTemplate(runtime),finance,new SecurityAuditService(runtime,JsonMapper.builder().build(),clock),clock,
                refundWorkerRegistry(test.provider(),new GatewayProviderRegistry.Settings(false,true,false,false,routes,enabled)));
    }
    private GatewayRefundModels.Queue refundProcessingQueue(Fixture f,GatewayRefundService service){return actorScoped(f.admin(),()->service.queue(f.admin(),f.school(),"ALL",null,0,25));}

    @Test void refundWorkerPostsOnceThenIndependentlyConfirmsAndConnectsReviewedDuesAndAccounting() throws Exception {
        var test=refundWorkerDriver();var f=test.source().refund().fixture();var money=refundReadProtectedState(f);var financialBefore=refundFinancialState(f);
        assertThat(test.worker().runNext(FAMILY_GATEWAY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(test.posts().get()).isEqualTo(1);assertThat(test.gets().get()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_dispatches WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(1);
        assertThat(refundSuccessCount(test.source().refund())).isZero();assertThat(refundWorkerReadCount(test)).isZero();assertThat(refundReadProtectedState(f)).isEqualTo(money);
        refundWorkerWaitDue(test);
        assertThat(test.worker().runNext(FAMILY_GATEWAY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(test.posts().get()).isEqualTo(1);assertThat(test.gets().get()).isEqualTo(1);assertThat(refundWorkerReadCount(test)).isEqualTo(1);
        assertThat(refundSuccessCount(test.source().refund())).isEqualTo(1);assertThat(refundFinancialState(f)).isEqualTo(financialBefore);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("800");
        var result=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),test.refundId(),refundPostingCommand(test.source().refund(),test.source().book().year())));
        assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),result.journalId(),new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent worker refund accounting approval",1)));
        assertThat(owner.queryForObject("SELECT status FROM accounting_journals WHERE id=?",String.class,result.journalId())).isEqualTo("POSTED");
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?",String.class,test.source().refund().paymentId())).isEqualTo("POSTED");
        assertThat(test.worker().runNext(FAMILY_GATEWAY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.IDLE);assertThat(test.posts().get()).isEqualTo(1);
    }

    @Test void refundWorkerPriorProviderWitnessUsesGetOnlyEvenWithNewRefundsDisabled() {
        var test=refundWorkerDriver();var f=test.source().refund().fixture();
        refundReadRetain(test.source().refund(),UUID.randomUUID(),null);
        var recoveryOnly=new GatewayProviderRegistry.Settings(false,true,false,false,List.of(test.route()),false);
        var worker=refundWorkerInstance(test.source(),test.provider(),recoveryOnly);
        var money=refundFinancialState(f);
        assertThat(worker.runNext(FAMILY_GATEWAY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(test.posts().get()).isZero();assertThat(test.gets().get()).isEqualTo(1);assertThat(refundWorkerReadCount(test)).isEqualTo(2);
        assertThat(refundSuccessCount(test.source().refund())).isEqualTo(1);assertThat(refundFinancialState(f)).isEqualTo(money);
    }

    @Test void refundWorkerProcessLossAfterCommittedAdmissionRecoversOnlyTheSameProviderIdentity() throws Exception {
        var test=refundWorkerDriver();var f=test.source().refund().fixture();var binding=familyGatewayBinding(f);var queue=new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime),clock);
        var claim=familyGatewayBackground(f,()->queue.claimNextRefund(binding));
        var dispatch=new GatewayRefundDispatch(new NamedParameterJdbcTemplate(runtime),refundWorkerRegistry(test.provider(),new GatewayProviderRegistry.Settings(false,true,false,false,List.of(test.route()),true)),refundReadStore(),feeAccounting);
        familyGatewayBackground(f,()->dispatch.admit(FAMILY_GATEWAY_ROUTE,claim));
        // The process is lost before any acknowledgement; only metadata recovery
        // is arranged through the real queue, never by resetting a marker/clock.
        familyGatewayBackground(f,()->queue.complete(claim,GatewayWorkQueue.Outcome.RETRY,"GATEWAY_REFUND_POST_OUTCOME_UNKNOWN",Duration.ofSeconds(1)));
        refundWorkerWaitDue(test);var money=refundFinancialState(f);
        assertThat(test.worker().runNext(FAMILY_GATEWAY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(test.posts().get()).isZero();assertThat(test.gets().get()).isEqualTo(1);assertThat(refundSuccessCount(test.source().refund())).isEqualTo(1);
        assertThat(refundFinancialState(f)).isEqualTo(money);
    }

    @Test void refundWorkerLostClaimDuringGetKeepsCommittedWitnessButCannotRecognizeHold() throws Exception {
        var test=refundWorkerDriver();var f=test.source().refund().fixture();refundReadRetain(test.source().refund(),UUID.randomUUID(),null);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var facts=refundWorkerFacts(test.source());
        org.mockito.Mockito.doAnswer(call->{refundInboxOutsidePersistence();entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return facts;}).when(test.provider()).fetchRefund(facts.orderReference(),facts.refundReference());
        var money=refundFinancialState(f);
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var result=executor.submit(()->test.worker().runNext(FAMILY_GATEWAY_ROUTE));
            try{
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var claim=refundWorkerCurrentClaim(test);
                assertThat(familyGatewayBackground(f,()->new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime),clock).complete(claim,GatewayWorkQueue.Outcome.REVIEW_REQUIRED,"GATEWAY_SYNTHETIC_CLAIM_REVOKED",null))).isTrue();
            }finally{release.countDown();}
            assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        }
        assertThat(refundWorkerReadCount(test)).isEqualTo(2);assertThat(refundSuccessCount(test.source().refund())).isZero();assertThat(refundFinancialState(f)).isEqualTo(money);
    }

    @Test void refundWorkerFailedFinalCompletionRollsBackHoldButNotPreviouslyCommittedGet() {
        var test=refundWorkerDriver();var f=test.source().refund().fixture();refundReadRetain(test.source().refund(),UUID.randomUUID(),null);
        String trigger="refund_worker_fail_"+UUID.randomUUID().toString().replace("-","");
        if(!trigger.matches("[a-z][a-z0-9_]{1,62}"))throw new IllegalStateException("Bounded synthetic trigger identifier required");
        owner.execute("CREATE FUNCTION "+trigger+"() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF NEW.refund_request_id='"+test.refundId()+"'::uuid AND NEW.status='COMPLETE' THEN RAISE EXCEPTION 'synthetic refund work completion failure'; END IF; RETURN NEW; END; $$");
        owner.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON fee_gateway_refund_work FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
        var money=refundFinancialState(f);
        try{assertThat(test.worker().runNext(FAMILY_GATEWAY_ROUTE)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);}
        finally{owner.execute("DROP TRIGGER "+trigger+" ON fee_gateway_refund_work");owner.execute("DROP FUNCTION "+trigger+"()");}
        assertThat(refundWorkerReadCount(test)).isEqualTo(2);assertThat(refundSuccessCount(test.source().refund())).isZero();assertThat(refundFinancialState(f)).isEqualTo(money);
    }

    @Test void refundWorkerConcurrentReplicasCannotDispatchTheSameApprovedRefundTwice() throws Exception {
        var test=refundWorkerDriver();var f=test.source().refund().fixture();var start=new CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->{refundSuccessAwait(start);return test.worker().runNext(FAMILY_GATEWAY_ROUTE);});
            var second=executor.submit(()->{refundSuccessAwait(start);return refundWorkerInstance(test.source(),test.provider(),new GatewayProviderRegistry.Settings(false,true,false,false,List.of(test.route()),true)).runNext(FAMILY_GATEWAY_ROUTE);});
            start.countDown();var outcomes=List.of(first.get(25,TimeUnit.SECONDS),second.get(25,TimeUnit.SECONDS));
            assertThat(outcomes).contains(GatewayAttemptWorker.Step.RETRY);assertThat(test.posts().get()).isEqualTo(1);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_dispatches WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(1);
        assertThat(refundSuccessCount(test.source().refund())).isZero();assertThat(familyFees(f).balance()).isEqualByComparingTo("800");
    }

    private RefundWorkerPg refundWorkerDriver(){
        var source=gatewayOriginalJournalFixture(false);var f=source.refund().fixture();var provider=org.mockito.Mockito.mock(PaymentProviderAdapter.class);
        var posts=new java.util.concurrent.atomic.AtomicInteger();var gets=new java.util.concurrent.atomic.AtomicInteger();var facts=refundWorkerFacts(source);
        org.mockito.Mockito.when(provider.providerCode()).thenReturn("CASHFREE");
        org.mockito.Mockito.when(provider.requestRefund(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{refundInboxOutsidePersistence();
            var request=call.getArgument(0,PaymentProviderAdapter.RefundRequest.class);assertThat(request.refundId()).isEqualTo(source.refund().refundId());
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_dispatches WHERE refund_request_id=?",Integer.class,request.refundId())).isEqualTo(1);
            assertThat(request.orderReference()).isEqualTo(facts.orderReference());assertThat(request.paymentReference()).isEqualTo(facts.paymentReference());assertThat(request.amount()).isEqualByComparingTo(facts.amount());posts.incrementAndGet();return facts;});
        org.mockito.Mockito.when(provider.fetchRefund(facts.orderReference(),facts.refundReference())).thenAnswer(call->{refundInboxOutsidePersistence();gets.incrementAndGet();return facts;});
        var binding=familyGatewayBinding(f);var route=new GatewayProviderRegistry.Route(FAMILY_GATEWAY_ROUTE,f.org(),f.school(),f.provider(),"CASHFREE",CashfreePaymentProviderAdapter.Environment.TEST,binding.merchantReference(),binding.credentialsReference(),"env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        var worker=refundWorkerInstance(source,provider,new GatewayProviderRegistry.Settings(false,true,false,false,List.of(route),true));
        return new RefundWorkerPg(source,provider,posts,gets,route,worker);
    }
    private PaymentProviderAdapter.RefundObservation refundWorkerFacts(GatewayOriginalJournalPg source){var expected=refundReadPrepare(source.refund()).expected();return refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123));}
    private GatewayProviderRegistry refundWorkerRegistry(PaymentProviderAdapter provider,GatewayProviderRegistry.Settings settings){return new GatewayProviderRegistry(settings,java.net.URI.create(properties.publicUrl()),clock,name->{refundInboxOutsidePersistence();return "synthetic-only-not-a-real-credential";},(r,id,secret,origin,c)->{refundInboxOutsidePersistence();return provider;});}
    private GatewayRefundWorker refundWorkerInstance(GatewayOriginalJournalPg source,PaymentProviderAdapter provider,GatewayProviderRegistry.Settings settings){
        var jdbc=new NamedParameterJdbcTemplate(runtime);var registry=refundWorkerRegistry(provider,settings);var reads=refundReadStore();
        return new GatewayRefundWorker(registry,new GatewayWorkQueue(jdbc,clock),new GatewayRefundDispatch(jdbc,registry,reads,feeAccounting),new GatewayProviderDiscovery(clock),reads,new GatewayRefundSuccessStore(jdbc,reads),jdbc,transactions.getTransactionManager());
    }
    private int refundWorkerReadCount(RefundWorkerPg test){return owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId());}
    private void refundWorkerWaitDue(RefundWorkerPg test) throws InterruptedException {
        Instant due=owner.queryForObject("SELECT next_attempt_at FROM fee_gateway_refund_work WHERE refund_request_id=?",java.sql.Timestamp.class,test.refundId()).toInstant();
        Instant actual=owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();long wait=Math.max(0,Duration.between(actual,due).toMillis())+100;
        if(wait>2000)throw new IllegalStateException("Only known one-second synthetic retry cadence may be awaited here");Thread.sleep(wait);
    }
    private GatewayWorkQueue.Claim refundWorkerCurrentClaim(RefundWorkerPg test){return owner.queryForObject("SELECT lease_token,lease_started_at,lease_until,attempt_count,EXISTS(SELECT 1 FROM fee_gateway_refund_dispatches WHERE refund_request_id=work.refund_request_id) AS dispatched FROM fee_gateway_refund_work work WHERE refund_request_id=?",(row,n)->new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.REFUND,test.refundId(),familyGatewayBinding(test.source().refund().fixture()),row.getObject("lease_token",UUID.class),row.getTimestamp("lease_started_at").toInstant(),row.getTimestamp("lease_until").toInstant(),row.getInt("attempt_count"),row.getBoolean("dispatched")),test.refundId());}
    private record RefundWorkerPg(GatewayOriginalJournalPg source,PaymentProviderAdapter provider,java.util.concurrent.atomic.AtomicInteger posts,java.util.concurrent.atomic.AtomicInteger gets,GatewayProviderRegistry.Route route,GatewayRefundWorker worker){UUID refundId(){return source.refund().refundId();}}

    @Test void refundDispatchPopulatedV079UpgradePreservesHistoryWithoutBackfillOrProviderActivation() {
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='080'",Boolean.class)).isTrue();
        assertThat(refundReadProtectedState(refundReadForwardFixture)).isEqualTo(refundDispatchForwardState);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_work WHERE school_id=?",Integer.class,refundReadForwardFixture.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_dispatches WHERE school_id=?",Integer.class,refundReadForwardFixture.school())).isZero();
        var queue=new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime),clock);
        var source=new RefundReadFixture(refundReadForwardFixture,refundReadForwardPayment(),refundReadForwardRequest);
        assertThat(familyGatewayBackground(source.fixture(),()->queue.enqueueRefund(familyGatewayBinding(source.fixture()),source.refundId()))).isEqualTo(source.refundId());
        assertThat(familyGatewayBackground(source.fixture(),()->queue.enqueueRefund(familyGatewayBinding(source.fixture()),source.refundId()))).isEqualTo(source.refundId());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_work WHERE school_id=?",Integer.class,source.fixture().school())).isEqualTo(1);
        assertThat(refundReadProtectedState(refundReadForwardFixture)).isEqualTo(refundDispatchForwardState);
    }

    private UUID refundReadForwardPayment(){return owner.queryForObject("SELECT fee_payment_id FROM fee_gateway_refund_requests WHERE id=?",UUID.class,refundReadForwardRequest);}

    @Test void refundDispatchApprovalQueuesOnceAndAdmitsExactClosedAccountSourceWithoutChangingMoney() {
        var test=refundDispatchFixture(true,Duration.ofSeconds(90));var f=test.source().refund().fixture();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_work WHERE refund_request_id=?",Integer.class,test.claim().id())).isEqualTo(1);
        var money=refundReadProtectedState(f);var command=refundDispatchAdmit(test);
        assertThat(command.request().refundId()).isEqualTo(test.claim().id());assertThat(command.request().amount()).isEqualByComparingTo("50.25");
        assertThat(refundDispatchCount(test)).isEqualTo(1);
        assertThatThrownBy(()->refundDispatchAdmit(test)).isInstanceOf(IllegalStateException.class);
        assertThat(familyGatewayBackground(f,()->test.dispatch().loadForReadRecovery(FAMILY_GATEWAY_ROUTE,test.claim())).previouslyDispatched()).isTrue();
        assertThat(owner.queryForObject("SELECT original_payment_journal_id FROM fee_gateway_refund_dispatches WHERE refund_request_id=?",UUID.class,test.claim().id())).isEqualTo(test.source().journal());
        assertThat(refundReadProtectedState(f)).isEqualTo(money);
        assertThat(owner.queryForObject("SELECT status FROM learner_fee_accounts WHERE id=?",String.class,f.account())).isEqualTo("CLOSED");
        assertThat(familyFees(f).balance()).isEqualByComparingTo("800");
    }

    @Test void refundDispatchRollbackDoesNotConsumeMarkerOrAnchorOriginalSource() {
        var test=refundDispatchFixture(false,Duration.ofSeconds(90));var f=test.source().refund().fixture();var money=refundReadProtectedState(f);
        assertThatThrownBy(()->familyGatewayBackground(f,()->{test.dispatch().admit(FAMILY_GATEWAY_ROUTE,test.claim());throw new IllegalStateException("Synthetic transaction failure after marker");})).isInstanceOf(IllegalStateException.class);
        assertThat(refundDispatchCount(test)).isZero();assertThat(refundReadProtectedState(f)).isEqualTo(money);
        assertThat(gatewayOriginalJournalRequestReversal(test.source()).id()).isNotNull();
        assertThatThrownBy(()->refundDispatchAdmit(test)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundDispatchCount(test)).isZero();
    }

    @Test void refundDispatchConcurrentSameClaimConsumesOneMarkerAndPreventsLaterSourceReversal() throws Exception {
        var test=refundDispatchFixture(false,Duration.ofSeconds(90));var f=test.source().refund().fixture();var money=refundReadProtectedState(f);
        var start=new CountDownLatch(1);var jobs=new java.util.ArrayList<java.util.concurrent.Future<Object>>();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            for(int n=0;n<2;n++)jobs.add(executor.submit(()->{refundSuccessAwait(start);try{return refundDispatchAdmit(test);}catch(RuntimeException failure){return failure;}}));
            start.countDown();var outcomes=new java.util.ArrayList<Object>();for(var job:jobs)outcomes.add(job.get(20,TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(GatewayRefundDispatch.Command.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(RuntimeException.class::isInstance).count()).isEqualTo(1);
        }
        assertThat(refundDispatchCount(test)).isEqualTo(1);
        assertThatThrownBy(()->gatewayOriginalJournalRequestReversal(test.source())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(refundReadProtectedState(f)).isEqualTo(money);
    }

    @Test void refundDispatchRejectsHumanForeignStolenAndChangedClaimsWithoutConsumingAnything() {
        var test=refundDispatchFixture(false,Duration.ofSeconds(90));var f=test.source().refund().fixture();var other=fixture(true);var before=refundReadProtectedState(f);
        assertThatThrownBy(()->test.dispatch().admit(FAMILY_GATEWAY_ROUTE,test.claim())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->test.dispatch().admit(FAMILY_GATEWAY_ROUTE,test.claim()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->familyGatewayBackground(other,()->test.dispatch().admit(FAMILY_GATEWAY_ROUTE,test.claim()))).isInstanceOf(IllegalStateException.class);
        for(var changed:List.of(
                new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.REFUND,test.claim().id(),test.claim().binding(),UUID.randomUUID(),test.claim().startedAt(),test.claim().leaseUntil(),test.claim().attemptNumber(),false),
                new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.REFUND,test.claim().id(),test.claim().binding(),test.claim().token(),test.claim().startedAt(),test.claim().leaseUntil(),test.claim().attemptNumber()+1,false))){
            assertThatThrownBy(()->familyGatewayBackground(f,()->test.dispatch().admit(FAMILY_GATEWAY_ROUTE,changed))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(refundDispatchCount(test)).isZero();assertThat(refundReadProtectedState(f)).isEqualTo(before);
    }

    @Test void refundDispatchEarlierIndependentReadPreventsPostButKeepsSameIdRecoveryAvailable() {
        var test=refundDispatchFixture(false,Duration.ofSeconds(90));var f=test.source().refund().fixture();
        refundReadRetain(test.source().refund(),UUID.randomUUID(),null);var before=refundReadProtectedState(f);
        assertThatThrownBy(()->refundDispatchAdmit(test)).isInstanceOf(IllegalStateException.class);
        assertThat(refundDispatchCount(test)).isZero();
        assertThat(familyGatewayBackground(f,()->test.dispatch().loadForReadRecovery(FAMILY_GATEWAY_ROUTE,test.claim())).approved().expected().refundId()).isEqualTo(test.claim().id());
        assertThat(refundReadProtectedState(f)).isEqualTo(before);
    }

    @Test void refundDispatchAccountingWaitRechecksCommittedPendingReversalBeforeMarker() throws Exception {
        var test=refundDispatchFixture(false,Duration.ofSeconds(90));var f=test.source().refund().fixture();var before=refundReadProtectedState(f);
        var result=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Object>>();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            actorScoped(f.admin(),()->{
                runtime.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(row,n)->true,"accounting:school:"+f.school());
                result.set(executor.submit(()->{try{return refundDispatchAdmit(test);}catch(RuntimeException failure){return failure;}}));
                gatewayOriginalJournalAwaitFence();
                return accounting.requestReversal(f.admin(),test.source().journal(),new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),test.source().book().period(),feeJournalReceiptDate(f,test.source().refund().paymentId()),"Synthetic source reversal before POST admission"));
            });
            assertThat(result.get().get(20,TimeUnit.SECONDS)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        assertThat(refundDispatchCount(test)).isZero();
        var after=refundReadProtectedState(f);
        before.forEach((table,rows)->{if(!table.equals("audit_events"))assertThat(after.get(table)).as("Unchanged refund source table %s",table).isEqualTo(rows);});
        assertThat(after.get("audit_events")).containsAll(before.get("audit_events")).hasSize(before.get("audit_events").size()+1);
        var extra=after.get("audit_events").stream().filter(row->!before.get("audit_events").contains(row)).toList();
        assertThat(extra).singleElement().satisfies(row->{
            var event=JsonMapper.builder().build().readTree(row);
            assertThat(event.get("action").stringValue()).isEqualTo("accounting.reversal.requested");
            assertThat(event.get("actor_user_id").stringValue()).isEqualTo(f.admin().userId().toString());
        });
    }

    @Test void refundDispatchLeaseExpiryDuringActualAccountingWaitCannotReturnPostPermission() throws Exception {
        var test=refundDispatchFixture(false,Duration.ofSeconds(8));var f=test.source().refund().fixture();var before=refundReadProtectedState(f);
        var result=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Object>>();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            actorScoped(f.admin(),()->{
                runtime.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(row,n)->true,"accounting:school:"+f.school());
                result.set(executor.submit(()->{try{return refundDispatchAdmit(test);}catch(RuntimeException failure){return failure;}}));
                gatewayOriginalJournalAwaitFence();
                refundDispatchWaitUntilExpired(test);return null;
            });
            assertThat(result.get().get(20,TimeUnit.SECONDS)).isInstanceOf(RuntimeException.class);
        }
        assertThat(refundDispatchCount(test)).isZero();assertThat(refundReadProtectedState(f)).isEqualTo(before);
        assertThat(familyGatewayBackground(f,()->test.queue().recoverExpiredRefundLease(test.claim().binding()))).isEqualTo(test.claim().id());
    }

    @Test void refundDispatchRecoveryCannotResetTheMarkerOrReturnAnotherCreateClaim() throws Exception {
        var test=refundDispatchFixture(false,Duration.ofSeconds(90));var f=test.source().refund().fixture();refundDispatchAdmit(test);
        assertThat(familyGatewayBackground(f,()->test.queue().complete(test.claim(),GatewayWorkQueue.Outcome.RETRY,"GATEWAY_REFUND_POST_OUTCOME_UNKNOWN",Duration.ofSeconds(1)))).isTrue();
        Thread.sleep(1100);
        var recovered=familyGatewayBackground(f,()->test.queue().claimNextRefund(test.claim().binding()));assertThat(recovered).isNotNull();
        assertThat(recovered.createDispatched()).isTrue();assertThat(recovered.attemptNumber()).isEqualTo(2);
        assertThatThrownBy(()->familyGatewayBackground(f,()->test.dispatch().admit(FAMILY_GATEWAY_ROUTE,recovered))).isInstanceOf(IllegalStateException.class);
        assertThat(familyGatewayBackground(f,()->test.dispatch().loadForReadRecovery(FAMILY_GATEWAY_ROUTE,recovered)).previouslyDispatched()).isTrue();
        assertThat(refundDispatchCount(test)).isEqualTo(1);
        assertThat(familyGatewayBackground(f,()->test.queue().complete(test.claim(),GatewayWorkQueue.Outcome.COMPLETE,"GATEWAY_REFUND_OLD_CLAIM",null))).isFalse();
    }

    @Test void refundDispatchDatabaseGuardsRejectForgedSourceAndImmutableMarkerChanges() {
        var test=refundDispatchFixture(false,Duration.ofSeconds(90));var f=test.source().refund().fixture();var proof=originalPaymentProof(f,f.admin(),test.source().refund().paymentId());
        var before=refundReadProtectedState(f);
        assertThatThrownBy(()->familyGatewayBackground(f,()->refundDispatchRaw(test,proof,"0".repeat(64)))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(refundDispatchCount(test)).isZero();refundDispatchAdmit(test);var exact=owner.queryForObject("SELECT row_to_json(d)::text FROM fee_gateway_refund_dispatches d WHERE refund_request_id=?",String.class,test.claim().id());
        assertThat(familyGatewayBackground(f,()->runtime.update("UPDATE fee_gateway_refund_dispatches SET admitted_at=admitted_at WHERE refund_request_id=?",test.claim().id()))).isZero();
        assertThatThrownBy(()->owner.update("UPDATE fee_gateway_refund_dispatches SET admitted_at=admitted_at WHERE refund_request_id=?",test.claim().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(owner.queryForObject("SELECT row_to_json(d)::text FROM fee_gateway_refund_dispatches d WHERE refund_request_id=?",String.class,test.claim().id())).isEqualTo(exact);
        assertThat(refundReadProtectedState(f)).isEqualTo(before);
    }

    @Test void refundDispatchCommittedMarkerSurvivesActualExpiredLeaseRecoveryAfterProcessLoss() {
        var test=refundDispatchFixture(false,Duration.ofSeconds(8));var f=test.source().refund().fixture();var before=refundReadProtectedState(f);
        refundDispatchAdmit(test);var marker=owner.queryForObject("SELECT row_to_json(d)::text FROM fee_gateway_refund_dispatches d WHERE refund_request_id=?",String.class,test.claim().id());
        // No provider call or synthetic completion: model a process disappearing
        // immediately after the admission transaction commits.
        refundDispatchWaitUntilExpired(test);
        assertThat(familyGatewayBackground(f,()->test.queue().recoverExpiredRefundLease(test.claim().binding()))).isEqualTo(test.claim().id());
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_refund_work WHERE refund_request_id=?",String.class,test.claim().id())).isEqualTo("RETRY");
        assertThat(owner.queryForObject("SELECT last_error_code FROM fee_gateway_refund_work WHERE refund_request_id=?",String.class,test.claim().id())).isEqualTo("GATEWAY_LEASE_OUTCOME_UNKNOWN");
        assertThat(owner.queryForObject("SELECT row_to_json(d)::text FROM fee_gateway_refund_dispatches d WHERE refund_request_id=?",String.class,test.claim().id())).isEqualTo(marker);
        assertThatThrownBy(()->refundDispatchAdmit(test)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->gatewayOriginalJournalRequestReversal(test.source())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(refundReadProtectedState(f)).isEqualTo(before);
    }

    @Test void refundDispatchProviderRowWaitMustRecheckRealLeaseTimeAfterEverySourceLock() throws Exception {
        var test=refundDispatchFixture(false,Duration.ofSeconds(12));var f=test.source().refund().fixture();var before=refundReadProtectedState(f);
        var outcome=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Object>>();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            actorScoped(f.admin(),()->{
                runtime.queryForObject("SELECT id FROM payment_provider_configs WHERE id=? FOR UPDATE",UUID.class,f.provider());
                int holder=runtime.queryForObject("SELECT pg_backend_pid()",Integer.class);
                outcome.set(executor.submit(()->{try{return refundDispatchAdmit(test);}catch(RuntimeException failure){return failure;}}));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);boolean waiting=false;
                do{
                    waiting=Boolean.TRUE.equals(control.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=? AND usename=? AND wait_event_type='Lock' AND state='active' AND position('INSERT INTO fee_gateway_refund_dispatches' IN query)>0 AND ?=ANY(pg_blocking_pids(pid)))",Boolean.class,database,runtimeLogin,holder));
                    if(!waiting)try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic provider wait observation interrupted",interrupted);}
                }while(!waiting&&System.nanoTime()<until);
                assertThat(waiting).as("Actual dispatch marker INSERT blocked on the held provider row").isTrue();
                refundDispatchWaitUntilExpired(test);return null;
            });
            assertThat(outcome.get().get(20,TimeUnit.SECONDS)).as("No POST command may survive the provider-row wait past its lease").isInstanceOf(RuntimeException.class);
        }
        assertThat(refundDispatchCount(test)).isZero();assertThat(refundReadProtectedState(f)).isEqualTo(before);
    }

    private void refundDispatchWaitUntilExpired(RefundDispatchPg test){
        Instant actual=owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
        long wait=Math.max(0,Duration.between(actual,test.claim().leaseUntil()).toMillis())+200;
        if(wait>13000)throw new IllegalStateException("Bounded real synthetic refund lease expiry required");
        try{Thread.sleep(wait);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic actual lease wait interrupted",interrupted);}
    }

    private RefundDispatchPg refundDispatchFixture(boolean closed,Duration lease){
        var source=gatewayOriginalJournalFixture(false);var f=source.refund().fixture();
        if(closed)refundReopenClose(f,"Synthetic closed original account before any refund provider read");
        var binding=familyGatewayBinding(f);var named=new NamedParameterJdbcTemplate(runtime);
        var route=new GatewayProviderRegistry.Route(FAMILY_GATEWAY_ROUTE,f.org(),f.school(),f.provider(),"CASHFREE",CashfreePaymentProviderAdapter.Environment.TEST,binding.merchantReference(),binding.credentialsReference(),"env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        var registry=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,List.of(route),true),java.net.URI.create(properties.publicUrl()),clock,
                name->{throw new AssertionError("Admission must not resolve a provider credential");},(r,id,secret,origin,c)->{throw new AssertionError("Admission must not create a provider");});
        var queue=new GatewayWorkQueue(named,clock,lease);var dispatch=new GatewayRefundDispatch(named,registry,refundReadStore(),feeAccounting);
        var claim=familyGatewayBackground(f,()->queue.claimNextRefund(binding));assertThat(claim).isNotNull();assertThat(claim.createDispatched()).isFalse();
        return new RefundDispatchPg(source,queue,dispatch,claim);
    }
    private GatewayRefundDispatch.Command refundDispatchAdmit(RefundDispatchPg test){return familyGatewayBackground(test.source().refund().fixture(),()->test.dispatch().admit(FAMILY_GATEWAY_ROUTE,test.claim()));}
    private int refundDispatchCount(RefundDispatchPg test){return owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_dispatches WHERE refund_request_id=?",Integer.class,test.claim().id());}
    private int refundDispatchRaw(RefundDispatchPg test,FeeAccountingService.OriginalPaymentPostingProof proof,String hash){
        var f=test.source().refund().fixture();var approval=runtime.queryForObject("SELECT approval_decision_id FROM fee_gateway_refund_work WHERE refund_request_id=?",UUID.class,test.claim().id());
        return runtime.update("INSERT INTO fee_gateway_refund_dispatches(refund_request_id,organisation_id,school_id,approval_decision_id,lease_token,lease_started_at,lease_until,attempt_number,original_payment_posting_item_id,original_payment_posting_batch_id,original_payment_journal_id,original_configuration_id,source_sha256,batch_evidence_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",test.claim().id(),f.org(),f.school(),approval,test.claim().token(),java.sql.Timestamp.from(test.claim().startedAt()),java.sql.Timestamp.from(test.claim().leaseUntil()),test.claim().attemptNumber(),proof.postingItemId(),proof.postingBatchId(),proof.accountingJournalId(),proof.configurationId(),hash,proof.batchEvidenceSha256());
    }
    private record RefundDispatchPg(GatewayOriginalJournalPg source,GatewayWorkQueue queue,GatewayRefundDispatch dispatch,GatewayWorkQueue.Claim claim){}

    @Test void gatewayOriginalJournalSystemReadsExactClosedHistoryWithoutFinancialMutation() {
        var test=gatewayOriginalJournalFixture(true);var f=test.refund().fixture();
        var periods=owner.queryForList("SELECT id,row_version FROM accounting_periods WHERE financial_year_id=? ORDER BY period_number",test.book().year());
        actorScoped(f.reviewer(),()->{
            for(var period:periods)accounting.transitionPeriod(f.reviewer(),(UUID)period.get("id"),
                    new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(f.school(),"CLOSE",((Number)period.get("row_version")).longValue(),"Synthetic close of original historical book"));
            return null;
        });
        long yearVersion=owner.queryForObject("SELECT row_version FROM accounting_financial_years WHERE id=?",Long.class,test.book().year());
        actorScoped(f.reviewer(),()->accounting.closeFinancialYear(f.reviewer(),test.book().year(),
                new com.educore.api.accounting.AccountingController.VersionedReasonRequest(f.school(),yearVersion,"Synthetic original historical year closure")));
        var expected=actorScoped(f.admin(),()->accounting.feeJournalEvidence(f.admin(),f.school(),List.of(test.journal()))).get(test.journal());
        var sourceExpected=originalPaymentProof(f,f.admin(),test.refund().paymentId());
        var before=feeJournalReadProtectedState(f);
        for(int retry=0;retry<3;retry++)assertThat(gatewayOriginalJournalRead(test)).isEqualTo(expected);
        for(int retry=0;retry<3;retry++)assertThat(gatewayOriginalSourceProof(f,test.refund().paymentId())).isEqualTo(sourceExpected);
        assertThat(expected.financialYearStatus()).isEqualTo("CLOSED");assertThat(expected.accountingPeriodStatus()).isEqualTo("CLOSED");
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT status FROM learner_fee_accounts WHERE id=?",String.class,f.account())).isEqualTo("CLOSED");
        assertThat(familyFees(f).balance()).isEqualByComparingTo("800");
    }

    @Test void gatewayOriginalJournalDeniesHumanForeignMissingAndActualManualPaymentSources() {
        var test=gatewayOriginalJournalFixture(false);var f=test.refund().fixture();var foreign=fixture(true);
        var manual=collect(f,"synthetic-system-journal-manual-"+UUID.randomUUID(),manualPayment(f,"CASH",null,"10",manualInstant(),null,null,null));
        LocalDate manualOn=feeJournalReceiptDate(f,manual.id());
        var manualBatch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),"synthetic-system-manual-book-"+UUID.randomUUID(),
                new FeeAccountingController.PostingRequest(f.school(),test.book().year(),manualOn,"PAYMENT",200,"Synthetic manual source must never be a gateway source")));
        UUID manualJournal=feeJournalBatchJournal(f,manualBatch.id());
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),manualJournal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent manual source posting",1)));
        var before=feeJournalReadProtectedState(f);var foreignBefore=feeJournalReadProtectedState(foreign);
        assertThatThrownBy(()->accounting.gatewayOriginalPaymentJournalEvidence(f.org(),f.school(),test.refund().paymentId(),test.batch(),test.journal())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->accounting.gatewayOriginalPaymentJournalEvidence(f.org(),f.school(),test.refund().paymentId(),test.batch(),test.journal()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->familyGatewayBackground(foreign,()->accounting.gatewayOriginalPaymentJournalEvidence(foreign.org(),foreign.school(),test.refund().paymentId(),test.batch(),test.journal()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->familyGatewayBackground(foreign,()->accounting.gatewayOriginalPaymentJournalEvidence(f.org(),f.school(),test.refund().paymentId(),test.batch(),test.journal()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->familyGatewayBackground(f,()->accounting.gatewayOriginalPaymentJournalEvidence(f.org(),f.school(),UUID.randomUUID(),test.batch(),test.journal()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->familyGatewayBackground(f,()->accounting.gatewayOriginalPaymentJournalEvidence(f.org(),f.school(),manual.id(),manualBatch.id(),manualJournal))).isInstanceOf(IllegalStateException.class);
        assertOriginalProofDenied(()->feeAccounting.verifyGatewayOriginalPaymentPosting(f.org(),f.school(),test.refund().paymentId()),"FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED");
        assertOriginalProofDenied(()->actorScoped(f.admin(),()->feeAccounting.verifyGatewayOriginalPaymentPosting(f.org(),f.school(),test.refund().paymentId())),"FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED");
        assertOriginalProofDenied(()->familyGatewayBackground(foreign,()->feeAccounting.verifyGatewayOriginalPaymentPosting(f.org(),f.school(),test.refund().paymentId())),"FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED");
        assertOriginalProofDenied(()->gatewayOriginalSourceProof(foreign,test.refund().paymentId()),"FEE_ORIGINAL_PAYMENT_PROOF_INVALID");
        assertOriginalProofDenied(()->familyGatewayBackground(f,()->feeAccounting.verifyGatewayOriginalPaymentPosting(f.org(),foreign.school(),test.refund().paymentId())),"FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED");
        assertOriginalProofDenied(()->gatewayOriginalSourceProof(f,UUID.randomUUID()),"FEE_ORIGINAL_PAYMENT_PROOF_INVALID");
        assertOriginalProofDenied(()->gatewayOriginalSourceProof(f,manual.id()),"FEE_ORIGINAL_PAYMENT_PROOF_INVALID");
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);assertThat(feeJournalReadProtectedState(foreign)).isEqualTo(foreignBefore);
    }

    @Test void gatewayOriginalJournalRefusesPendingAndActualReversalWithoutChangingTheFeeReceipt() {
        var test=gatewayOriginalJournalFixture(false);var f=test.refund().fixture();
        var feeMoney=new java.util.LinkedHashMap<>(refundFinancialState(f));
        // The deliberately approved common-journal reversal must change its own
        // journal/history/lines, never the independent learner/provider records.
        feeMoney.keySet().removeAll(Set.of("accounting_journals","accounting_journal_lines","accounting_journal_history"));
        var request=gatewayOriginalJournalRequestReversal(test);var pending=feeJournalReadProtectedState(f);
        assertThatThrownBy(()->gatewayOriginalJournalRead(test)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->gatewayOriginalSourceProof(f,test.refund().paymentId())).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(pending);
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),request.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"REJECT","Synthetic independent rejection keeps original history eligible",0)));
        var rejected=feeJournalReadProtectedState(f);
        assertThat(gatewayOriginalJournalRead(test).id()).isEqualTo(test.journal());
        assertThat(gatewayOriginalSourceProof(f,test.refund().paymentId()).accountingJournalId()).isEqualTo(test.journal());
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(rejected);
        var approved=gatewayOriginalJournalRequestReversal(test);
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),approved.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic independent actual journal reversal",0)));
        var reversed=feeJournalReadProtectedState(f);
        assertThatThrownBy(()->gatewayOriginalJournalRead(test)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->gatewayOriginalSourceProof(f,test.refund().paymentId())).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(reversed);
        var after=refundFinancialState(f);feeMoney.forEach((table,rows)->assertThat(after.get(table)).as("Unchanged original fee/provider table %s",table).isEqualTo(rows));
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?",String.class,test.refund().paymentId())).isEqualTo("POSTED");
        assertThat(familyFees(f).balance()).isEqualByComparingTo("800");
    }

    @Test void gatewayOriginalJournalWaitsForAccountingFenceAndRechecksCommittedReversal() throws Exception {
        var test=gatewayOriginalJournalFixture(false);var f=test.refund().fixture();var money=refundFinancialState(f);
        var outcome=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Object>>();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            actorScoped(f.admin(),()->{
                runtime.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(row,index)->true,"accounting:school:"+f.school());
                outcome.set(executor.submit(()->{try{return gatewayOriginalJournalRead(test);}catch(RuntimeException failure){return failure;}}));
                gatewayOriginalJournalAwaitFence();
                return accounting.requestReversal(f.admin(),test.journal(),new com.educore.api.accounting.AccountingController.ReversalRequest(
                        f.school(),test.book().period(),feeJournalReceiptDate(f,test.refund().paymentId()),"Synthetic reversal committed before queued background evidence"));
            });
            assertThat(outcome.get().get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                    error->assertThat(error.code()).isEqualTo("FEE_REFUND_ACCOUNTING_UNAVAILABLE"));
        }
        assertThat(refundFinancialState(f)).isEqualTo(money);
    }

    @Test void gatewayOriginalJournalRejectsARealStaleRepeatableReadSnapshotAfterReversalCommit() throws Exception {
        var test=gatewayOriginalJournalFixture(false);var f=test.refund().fixture();var money=refundFinancialState(f);
        var snapshotReady=new java.util.concurrent.CountDownLatch(1);var proceed=new java.util.concurrent.CountDownLatch(1);
        var staleCount=new java.util.concurrent.atomic.AtomicInteger(-1);
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var result=executor.submit(()->{
                try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,f.org())){
                    var stale=new TransactionTemplate(transactions.getTransactionManager());
                    stale.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);stale.setTimeout(20);
                    try{return stale.execute(status->{
                        assertThat(runtime.queryForObject("SELECT count(*) FROM accounting_reversal_requests WHERE accounting_journal_id=? AND status='PENDING'",Integer.class,test.journal())).isZero();
                        snapshotReady.countDown();
                        try{assertThat(proceed.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic stale-snapshot test interrupted",interrupted);}
                        staleCount.set(runtime.queryForObject("SELECT count(*) FROM accounting_reversal_requests WHERE accounting_journal_id=? AND status='PENDING'",Integer.class,test.journal()));
                        return accounting.gatewayOriginalPaymentJournalEvidence(f.org(),f.school(),test.refund().paymentId(),test.batch(),test.journal());
                    });}catch(RuntimeException failure){return failure;}
                }
            });
            try{assertThat(snapshotReady.await(5,TimeUnit.SECONDS)).isTrue();gatewayOriginalJournalRequestReversal(test);}
            finally{proceed.countDown();}
            assertThat(result.get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(IllegalStateException.class,
                    error->assertThat(error.getMessage()).isEqualTo("Exact original payment journal requires scoped background verification"));
        }
        assertThat(staleCount.get()).isZero();assertThat(owner.queryForObject("SELECT count(*) FROM accounting_reversal_requests WHERE accounting_journal_id=? AND status='PENDING'",Integer.class,test.journal())).isEqualTo(1);
        assertThat(refundFinancialState(f)).isEqualTo(money);
    }

    @Test void settlementSourceReviewIdentifiesActualHistoricalReceiptWithoutMovingMoneyOrReopeningAccount() {
        var original=gatewayOriginalJournalFixture(true);var f=original.refund().fixture();
        var payment=sourceReviewEvent(f,original.refund().paymentId(),"synthetic_source_payment","PAYMENT","CREDIT",null);
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(payment))));
        var before=feeJournalReadProtectedState(f);
        var review=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25));
        assertThat(review.reviewStage()).isEqualTo("SOURCE_IDENTIFICATION_ONLY");
        assertThat(review.pagesRead()).isEqualTo(1);assertThat(review.totalRows()).isEqualTo(1);
        assertThat(review.sourceIdentifiedRows()).isEqualTo(1);assertThat(review.reviewRequiredRows()).isZero();
        assertThat(review.items()).singleElement().satisfies(row->{
            assertThat(row.status()).isEqualTo("SOURCE_IDENTIFIED");assertThat(row.reasons()).isEmpty();
            assertThat(row.rowIndex()).isZero();assertThat(row.sourceReadId()).isNotNull();
            assertThat(row.source().paymentId()).isEqualTo(original.refund().paymentId());
            assertThat(row.source().accountingJournalId()).isEqualTo(original.journal());
            assertThat(row.source().clearingAccountId()).isEqualTo(original.book().clearing());
            assertThat(row.source().clearingAccountCategory()).isEqualTo("OTHER");
            assertThat(row.source().grossAmount()).isEqualTo(payment.reportedAmount().setScale(2).toPlainString());
        });
        assertThat(owner.queryForObject("SELECT status FROM learner_fee_accounts WHERE id=?",String.class,f.account())).isEqualTo("CLOSED");
        assertThat(actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25))).isEqualTo(review);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
    }

    @Test void settlementSourceReviewFindsDuplicateEconomicSourcesAcrossAllPagesBeforeResponsePagination() {
        var original=gatewayOriginalJournalFixture(false);var f=original.refund().fixture();
        var first=sourceReviewEvent(f,original.refund().paymentId(),"synthetic_source_first","PAYMENT","CREDIT",null);
        var second=sourceReviewEvent(f,original.refund().paymentId(),"synthetic_source_second","PAYMENT","CREDIT",null);
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage("synthetic_source_next",List.of(first)),
                new GatewaySettlementProvider.SettlementEventsPage(null,List.of(second))));
        var before=feeJournalReadProtectedState(f);
        var page=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,1));
        var next=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),1,1));
        assertThat(page.pagesRead()).isEqualTo(2);assertThat(page.totalRows()).isEqualTo(2);
        assertThat(page.sourceIdentifiedRows()).isZero();assertThat(page.reviewRequiredRows()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();assertThat(next.hasMore()).isFalse();
        for(var view:List.of(page,next))assertThat(view.items()).singleElement().satisfies(row->{
            assertThat(row.status()).isEqualTo("REVIEW_REQUIRED");assertThat(row.reasons()).contains("DUPLICATE_PAYMENT_SOURCE");
            assertThat(row.source()).isNull();
        });
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
    }

    @Test void settlementSourceReviewPreservesUnsupportedRefundAndFractionalPaiseEvidenceWithoutGuessingSources() {
        var original=gatewayOriginalJournalFixture(false);var f=original.refund().fixture();
        var fractional=sourceReviewEvent(f,original.refund().paymentId(),"synthetic_source_fractional","PAYMENT","CREDIT",new BigDecimal("1.00000001"));
        var refund=sourceReviewEvent(f,original.refund().paymentId(),"synthetic_source_refund","REFUND","DEBIT",null);
        var dispute=sourceReviewEvent(f,original.refund().paymentId(),"synthetic_source_fractional","DISPUTE","DEBIT",null);
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(fractional,refund,dispute))));
        var before=feeJournalReadProtectedState(f);
        var review=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25));
        assertThat(review.sourceIdentifiedRows()).isZero();assertThat(review.reviewRequiredRows()).isEqualTo(3);
        assertThat(review.items().get(0).reasons()).contains("FRACTIONAL_PAISE");
        assertThat(review.items().get(1).reasons()).contains("REFUND_ASSOCIATION_REVIEW");
        assertThat(review.items().get(2).reasons()).contains("UNSUPPORTED_EVENT_TYPE");
        assertThat(review.items()).allSatisfy(row->assertThat(row.source()).isNull());
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
    }

    @Test void settlementSourceReviewRejectsForeignGuardianRevokedAndIncompleteSourcesWithoutFinancialWrites() {
        var original=gatewayOriginalJournalFixture(false);var f=original.refund().fixture();var foreign=fixture(true);
        var event=sourceReviewEvent(f,original.refund().paymentId(),"synthetic_source_scope","PAYMENT","CREDIT",null);
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage("synthetic_pending_page",List.of(event))),false);
        var before=feeJournalReadProtectedState(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.parent(),()->flow.reviews().review(f.parent(),f.school(),flow.request(),0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(foreign.admin(),()->flow.reviews().review(foreign.admin(),f.school(),flow.request(),0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->flow.reviews().review(f.admin(),foreign.school(),flow.request(),0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=clock_timestamp(),row_version=row_version+1 WHERE id=?",f.admin().membershipIds().iterator().next());
        assertThatThrownBy(()->actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
    }

    @Test void settlementSourceReviewRejectsActuallyReversedOriginalJournalButRetainsReceiptAndDues() {
        var original=gatewayOriginalJournalFixture(false);var f=original.refund().fixture();
        var event=sourceReviewEvent(f,original.refund().paymentId(),"synthetic_source_reversed","PAYMENT","CREDIT",null);
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(event))));
        var reversal=gatewayOriginalJournalRequestReversal(original);
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic source-review original reversal",0)));
        var before=feeJournalReadProtectedState(f);
        var review=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25));
        assertThat(review.items()).singleElement().satisfies(row->{
            assertThat(row.status()).isEqualTo("REVIEW_REQUIRED");assertThat(row.reasons()).contains("ORIGINAL_POSTING_INVALID");
            assertThat(row.source()).isNull();
        });
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?",String.class,original.refund().paymentId())).isEqualTo("POSTED");
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
    }

    @Test void settlementRefundCandidatesReadExactPostedHistoryWithoutAssociationOrFinancialWrites() {
        for(String basis:List.of("CASH","ACCRUAL")) {
            var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,basis);
            var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
            actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                    new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted candidate source",1)));
            refundReopenClose(f,"Synthetic historical refund account closure");
            var event=refundCandidateEvent(held,"synthetic_refund_candidate",List.of(held.refundId().toString(),"synthetic_unassociated_reference"));
            var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(event))));
            var row=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25)).items().getFirst();
            var before=feeJournalReadProtectedState(f);
            var result=refundCandidates(f,f.admin(),flow,row.sourceReadId(),0,0,25);
            assertThat(result.reviewStage()).isEqualTo("HISTORICAL_REFUND_CANDIDATES_ONLY");
            assertThat(result.state()).isEqualTo("CANDIDATE_CONTEXT");assertThat(result.reviewReasons()).isEmpty();
            assertThat(result.receiptNumber()).isNotBlank();assertThat(result.hasMore()).isFalse();
            assertThat(result.items()).singleElement().satisfies(item->{
                assertThat(item.refundPostingId()).isEqualTo(completed.id());assertThat(item.refundId()).isEqualTo(held.refundId());
                assertThat(item.amount()).isEqualTo("50.25");assertThat(item.currencyCode()).isEqualTo("INR");
                assertThat(item.accountingState()).isEqualTo("POSTED_SOURCE_PROVEN");assertThat(item.accountingJournalId()).isEqualTo(completed.journalId());
                assertThat(item.referenceMention()).isEqualTo("MENTIONED");
                assertThat(item.processedAt()).isEqualTo(owner.queryForObject("SELECT processed_at_exact FROM fee_gateway_refund_successes WHERE refund_request_id=?",String.class,held.refundId()));
            });
            assertThat(refundCandidates(f,f.admin(),flow,row.sourceReadId(),0,0,25)).isEqualTo(result);
            assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
            assertThat(owner.queryForObject("SELECT status FROM learner_fee_accounts WHERE id=?",String.class,f.account())).isEqualTo("CLOSED");
            assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");
        }
    }

    @Test void settlementSourceReviewExpectedProofConflictSurvivesActualSpringTransactionProxy() {
        var held=refundReadApproved();var f=held.fixture();
        var event=sourceReviewEvent(f,held.paymentId(),"synthetic_unposted_original","PAYMENT","CREDIT",null);
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(event))));
        var service=new GatewaySettlementSourceReviewService(flow.settlements(),new NamedParameterJdbcTemplate(runtime),transactionalFeeProofs(),accounting);
        var before=feeJournalReadProtectedState(f);
        var result=completedRefundProofRead(f,f.admin(),()->service.review(f.admin(),f.school(),flow.request(),0,25));
        assertThat(result.items()).singleElement().satisfies(row->{assertThat(row.status()).isEqualTo("REVIEW_REQUIRED");assertThat(row.reasons()).containsExactly("ORIGINAL_POSTING_INVALID");assertThat(row.source()).isNull();});
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        // An uncaught proof failure in a mutating transaction must still roll its writes back.
        UUID head=originalProofHead(f);
        String original=owner.queryForObject("SELECT display_name FROM fee_heads WHERE id=?",String.class,head);
        assertThatThrownBy(()->actorScoped(f.admin(),()->{
            runtime.update("UPDATE fee_heads SET display_name='Synthetic must roll back' WHERE id=?",head);
            return transactionalFeeProofs().verifyOriginalPaymentPosting(f.admin(),f.school(),held.paymentId());
        })).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT display_name FROM fee_heads WHERE id=?",String.class,head)).isEqualTo(original);
    }

    @Test void settlementRefundCandidatesKeepUnpostedAndActuallyReversedAccountingVisibleForReview() {
        var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
        var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(refundCandidateEvent(held,"synthetic_refund_review",List.of())))));
        var row=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25)).items().getFirst();
        var before=feeJournalReadProtectedState(f);
        var unposted=refundCandidates(f,f.admin(),flow,row.sourceReadId(),0,0,25);
        assertThat(unposted.items()).singleElement().satisfies(item->{assertThat(item.accountingState()).isEqualTo("ACCOUNTING_REVIEW_REQUIRED");assertThat(item.accountingJournalId()).isNull();assertThat(item.referenceMention()).isEqualTo("NOT_REPORTED");});
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted review candidate",1)));
        var reversal=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),completed.journalId(),
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),book.period(),completed.postingOn(),"Synthetic candidate journal reversal")));
        // Historical proof is deliberately not pending-reversal admission for future settlement posting.
        assertThat(refundCandidates(f,f.admin(),flow,row.sourceReadId(),0,0,25).items().getFirst().accountingState()).isEqualTo("POSTED_SOURCE_PROVEN");
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic independent candidate reversal",0)));
        var reversed=feeJournalReadProtectedState(f);
        assertThat(refundCandidates(f,f.admin(),flow,row.sourceReadId(),0,0,25).items()).singleElement().satisfies(item->{assertThat(item.refundPostingId()).isEqualTo(completed.id());assertThat(item.accountingState()).isEqualTo("ACCOUNTING_REVIEW_REQUIRED");assertThat(item.accountingJournalId()).isNull();});
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(reversed);
    }

    @Test void settlementRefundCandidatesPaginateAmbiguousEqualAmountsAndKeepPhysicalReportRowsDistinct() {
        var held=refundReopenHeld(false);var f=held.fixture();var book=refundPostingOriginalBook(held,"CASH");
        var first=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,book.year())));
        UUID refund=refundSubmit(f,f.admin(),held.paymentId(),refundSubmit(f,UUID.randomUUID(),"50.25")).id();
        refundDecide(f,f.reviewer(),refund,"APPROVE");var secondHeld=new RefundReadFixture(f,held.paymentId(),refund);
        var success=refundSuccessRead(secondHeld,456);refundSuccessRetain(secondHeld,success.id());
        var second=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),refund,refundPostingCommand(secondHeld,book.year())));
        var event=refundCandidateEvent(held,"synthetic_repeated_event",List.of(held.refundId().toString()));
        var payment=sourceReviewEvent(f,held.paymentId(),"synthetic_repeated_event","PAYMENT","CREDIT",null);
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(payment,event))));
        var rows=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25)).items();var source=rows.getLast().sourceReadId();
        var before=feeJournalReadProtectedState(f);
        var unsupported=refundCandidates(f,f.admin(),flow,source,0,0,1);
        assertThat(unsupported.state()).isEqualTo("REVIEW_REQUIRED");assertThat(unsupported.reviewReasons()).contains("REFUND_EVENT_REQUIRED");assertThat(unsupported.items()).isEmpty();
        var page=refundCandidates(f,f.admin(),flow,source,1,0,1);var next=refundCandidates(f,f.admin(),flow,source,1,1,1);
        assertThat(page.items()).hasSize(1);assertThat(next.items()).hasSize(1);assertThat(page.hasMore()).isTrue();assertThat(next.hasMore()).isFalse();
        assertThat(java.util.stream.Stream.concat(page.items().stream(),next.items().stream()).map(GatewaySettlementRefundCandidateModels.Candidate::refundPostingId).toList()).containsExactlyInAnyOrder(first.id(),second.id());
        assertThat(java.util.stream.Stream.concat(page.items().stream(),next.items().stream()).map(GatewaySettlementRefundCandidateModels.Candidate::referenceMention).toList()).containsExactlyInAnyOrder("MENTIONED","NOT_MENTIONED");
        assertThat(refundCandidates(f,f.admin(),flow,source,1,2,1).items()).isEmpty();
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
    }

    @Test void settlementRefundCandidatesDenyForeignFamilyRevokedAndSubstitutedEvidenceWithoutFinancialWrites() {
        var held=refundReopenHeld(false);var f=held.fixture();var foreign=fixture(true);
        var event=refundCandidateEvent(held,"synthetic_private_refund_candidates",List.of());
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(event))));
        var row=actorScoped(f.admin(),()->flow.reviews().review(f.admin(),f.school(),flow.request(),0,25)).items().getFirst();
        var incomplete=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage("synthetic_missing_next",List.of(event))),false);
        var before=feeJournalReadProtectedState(f);
        for(var actor:List.of(f.parent(),foreign.admin()))assertThatThrownBy(()->refundCandidates(f,actor,flow,row.sourceReadId(),0,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundCandidates(f,f.admin(),flow,UUID.randomUUID(),0,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundCandidates(f,f.admin(),flow,row.sourceReadId(),1,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundCandidates(f,f.admin(),incomplete,row.sourceReadId(),0,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var empty=refundCandidates(f,f.admin(),flow,row.sourceReadId(),0,0,25);assertThat(empty.state()).isEqualTo("CANDIDATE_CONTEXT");assertThat(empty.items()).isEmpty();
        owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=clock_timestamp(),row_version=row_version+1 WHERE id=?",member(f.admin()));
        assertThatThrownBy(()->refundCandidates(f,f.admin(),flow,row.sourceReadId(),0,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
    }

    private GatewaySettlementProvider.SettlementEvent refundCandidateEvent(RefundReadFixture held,String eventId,List<String> references){
        var event=sourceReviewEvent(held.fixture(),held.paymentId(),eventId,"REFUND","DEBIT",new BigDecimal("50.25"));
        return new GatewaySettlementProvider.SettlementEvent(event.settlementReference(),event.eventReference(),event.reportedType(),event.reportedDirection(),event.reportedStatus(),event.currencyCode(),event.reportedAmount(),event.reportedServiceCharge(),event.reportedServiceTax(),event.reportedSettlementAmount(),event.occurredAt(),event.orderReference(),event.paymentReference(),references,event.chargesCurrencyCode());
    }
    private GatewaySettlementRefundCandidateModels.Review refundCandidates(Fixture f,AuthenticatedUser actor,SourceReviewPg flow,UUID read,int row,int page,int size){
        var service=new GatewaySettlementRefundCandidateService(flow.settlements(),new NamedParameterJdbcTemplate(runtime),transactionalFeeProofs());
        return completedRefundProofRead(f,actor,()->service.candidates(actor,f.school(),flow.request(),read,row,page,size));
    }
    private FeeAccountingService transactionalFeeProofs(){
        var proxy=new org.springframework.aop.framework.ProxyFactory(feeAccounting);proxy.setProxyTargetClass(true);
        var advice=new org.springframework.transaction.interceptor.TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(runtimeDataSource));
        advice.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource(false));
        proxy.addAdvice(advice);return (FeeAccountingService)proxy.getProxy();
    }

    private GatewaySettlementProvider.SettlementEvent sourceReviewEvent(Fixture f,UUID payment,String eventId,String type,String direction,BigDecimal amount){
        var source=owner.queryForMap("SELECT capture.order_reference,capture.payment_reference,capture.amount,capture.paid_at FROM fee_gateway_captures capture JOIN fee_payments payment ON payment.gateway_capture_id=capture.id WHERE payment.id=? AND payment.organisation_id=? AND payment.school_id=?",payment,f.org(),f.school());
        var occurred=((java.sql.Timestamp)source.get("paid_at")).toInstant();
        return new GatewaySettlementProvider.SettlementEvent("synthetic_source_statement",eventId,type,direction,"SUCCESS","INR",
                amount==null?(BigDecimal)source.get("amount"):amount,null,null,null,occurred,
                (String)source.get("order_reference"),(String)source.get("payment_reference"),
                type.equals("REFUND")?List.of("synthetic_refund_candidate_A","synthetic_refund_candidate_B"):List.of(),null);
    }
    private SourceReviewPg sourceReviewFlow(Fixture f,List<GatewaySettlementProvider.SettlementEventsPage> pages){return sourceReviewFlow(f,pages,true);}
    private SourceReviewPg sourceReviewFlow(Fixture f,List<GatewaySettlementProvider.SettlementEventsPage> pages,boolean complete){return sourceReviewFlow(f,pages,complete,false);}
    private SourceReviewPg sourceReviewFlow(Fixture f,List<GatewaySettlementProvider.SettlementEventsPage> pages,boolean complete,boolean summaryReview){
        return sourceReviewFlow(f,pages,complete,summaryReview,null);
    }
    private SourceReviewPg sourceReviewFlow(Fixture f,List<GatewaySettlementProvider.SettlementEventsPage> pages,boolean complete,boolean summaryReview,
            GatewaySettlementProvider.SettlementSummary suppliedSummary){
        Instant until=Instant.now().minusSeconds(1);
        return sourceReviewFlow(f,pages,complete,summaryReview,suppliedSummary,until.minus(Duration.ofDays(7)),until);
    }
    private SourceReviewPg sourceReviewFlow(Fixture f,List<GatewaySettlementProvider.SettlementEventsPage> pages,boolean complete,boolean summaryReview,
            GatewaySettlementProvider.SettlementSummary suppliedSummary,Instant from,Instant until){
        var named=new NamedParameterJdbcTemplate(runtime);var audit=new SecurityAuditService(runtime,JsonMapper.builder().build(),clock);
        var store=new GatewaySettlementReadStore(named,new SensitiveValueCipher(properties,new SecureRandom()));
        var adapter=org.mockito.Mockito.mock(PaymentProviderAdapter.class,org.mockito.Mockito.withSettings().extraInterfaces(GatewaySettlementProvider.class));
        org.mockito.Mockito.when(adapter.providerCode()).thenReturn("CASHFREE");
        var reports=(GatewaySettlementProvider)adapter;

        var summary=suppliedSummary==null?new GatewaySettlementProvider.SettlementSummary("synthetic_source_statement","SUCCESS","synthetic_source_utr",
                from,until,null,null,null,null,null,null,null,null,null,null,null,null,null,null):suppliedSummary;
        org.mockito.Mockito.when(reports.fetchSettlements(org.mockito.ArgumentMatchers.any())).thenReturn(new GatewaySettlementProvider.SettlementPage(summaryReview?"synthetic_summary_cycle":null,List.of(summary)));
        org.mockito.Mockito.when(reports.fetchSettlementEvents(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            var query=call.getArgument(0,GatewaySettlementProvider.SettlementEventsQuery.class);
            return query.cursor()==null?pages.getFirst():pages.get(1);
        });
        String key=f.provider().toString().replace("-","")+"a".repeat(32);
        var route=new GatewayProviderRegistry.Route(key,f.org(),f.school(),f.provider(),"CASHFREE",CashfreePaymentProviderAdapter.Environment.TEST,
                "synthetic_merchant_"+f.provider(),"synthetic:not-enabled","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET",true);
        var registry=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,List.of(route),false,true),
                java.net.URI.create("https://synthetic-school.example.test"),clock,name->"synthetic-in-process-only",(r,id,secret,origin,now)->adapter);
        var queue=new GatewayWorkQueue(named,clock);var dispatch=new GatewaySettlementDispatch(named,registry,store,queue);
        var worker=new GatewaySettlementWorker(registry,queue,dispatch,store,new DataSourceTransactionManager(runtimeDataSource));
        var settlements=new GatewaySettlementService(named,registry,audit);
        var summaryRequest=actorScoped(f.admin(),()->settlements.submit(f.admin(),new GatewaySettlementModels.Submit(f.school(),UUID.randomUUID(),
                "Asia/Kolkata","SUMMARY",from,until,null,null)));
        assertThat(worker.runNext(key)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        if(summaryReview)assertThat(worker.runNext(key)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        var selected=actorScoped(f.admin(),()->settlements.report(f.admin(),f.school(),summaryRequest.id(),null,0,25)).items().getLast();
        var eventsRequest=actorScoped(f.admin(),()->settlements.submit(f.admin(),new GatewaySettlementModels.Submit(f.school(),UUID.randomUUID(),
                "Asia/Kolkata","EVENTS",null,null,selected.sourceReadId(),"synthetic_source_statement")));
        assertThat(worker.runNext(key)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        if(complete)for(int i=1;i<pages.size();i++)assertThat(worker.runNext(key)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        return new SourceReviewPg(eventsRequest.id(),new GatewaySettlementSourceReviewService(settlements,named,feeAccounting,accounting),settlements);
    }
    private record SourceReviewPg(UUID request,GatewaySettlementSourceReviewService reviews,GatewaySettlementService settlements){}

    @Test void settlementPreparationPopulatedV083UpgradePreservesHistoryAndAddsNoConsumption(){
        assertThat(settlementPreparationForwardPreserved).isTrue();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='084'",Boolean.class)).isTrue();
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void settlementPreparationPostsExactCommonJournalOnceWithoutChangingStudentMoney(){
        for(String charge:List.of("0.00","10.00")){
            var test=settlementPreparationFixture("CASH",0,new BigDecimal(charge),false);var f=test.fixture();
            var command=settlementPreparationCommand(test,null);var before=settlementStudentMoney(f);
            var prepared=settlementPrepare(test,f.admin(),command);
            assertThat(prepared.status()).isEqualTo("SUBMITTED");assertThat(prepared.revision()).isEqualTo(1);assertThat(prepared.sourceCount()).isEqualTo(1);
            assertThat(prepared.paymentTotal()).isEqualTo("200.00");assertThat(prepared.refundTotal()).isEqualTo("0.00");
            assertThat(prepared.bankAmount()).isEqualTo(test.bankEvidence().creditedAmount());assertThat(prepared.componentDebitTotal()).isEqualTo(charge);
            assertThat(settlementPrepare(test,f.admin(),command)).isEqualTo(prepared);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_roots WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_preparations WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_components WHERE preparation_id=?",Integer.class,prepared.id())).isEqualTo(8);
            assertThat(owner.queryForObject("SELECT evidence_sha256=encode(sha256(convert_to(evidence_snapshot::text,'UTF8')),'hex') FROM fee_gateway_settlement_preparations WHERE id=?",Boolean.class,prepared.id())).isTrue();
            assertThatThrownBy(()->settlementDecide(test,f.admin(),prepared,"POST")).isInstanceOf(com.educore.api.common.api.ApiException.class);
            settlementDecide(test,f.reviewer(),prepared,"POST");
            var posted=completedRefundProofRead(f,f.admin(),()->settlementPreparationService(test).detail(f.admin(),f.school(),prepared.id()));
            assertThat(posted.preparation().status()).isEqualTo("POSTED");assertThat(posted.preparation().voucherNumber()).isNotBlank();
            assertThat(posted.sources()).singleElement().satisfies(source->{assertThat(source.paymentId()).isEqualTo(test.payment());assertThat(source.amount()).isEqualTo("200.00");});
            assertThat(settlementPrepare(test,f.admin(),command).id()).isEqualTo(prepared.id());
            assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action='finance.gateway.settlement.prepared'",Integer.class,f.school())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT COALESCE(sum(debit_amount),0) FROM accounting_journal_lines WHERE accounting_journal_id=? AND account_id=?",BigDecimal.class,prepared.journalId(),test.bank()))
                    .isEqualByComparingTo(test.bankEvidence().creditedAmount());
            assertThat(owner.queryForObject("SELECT COALESCE(sum(credit_amount),0) FROM accounting_journal_lines WHERE accounting_journal_id=? AND account_id=?",BigDecimal.class,prepared.journalId(),test.book().clearing())).isEqualByComparingTo("200");
            assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journal_lines WHERE accounting_journal_id=? AND account_id=?",Integer.class,prepared.journalId(),test.book().income())).isZero();
            assertThat(settlementStudentMoney(f)).isEqualTo(before);
        }
    }

    @Test void settlementPreparationCashAndAccrualRefundNetsPreserveGrossSides(){
        for(String basis:List.of("CASH","ACCRUAL")){
            var test=settlementPreparationFixture(basis,1,BigDecimal.ZERO,false);var f=test.fixture();var before=settlementStudentMoney(f);
            var prepared=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,null));
            assertThat(prepared.paymentTotal()).isEqualTo("200.00");assertThat(prepared.refundTotal()).isEqualTo("50.25");assertThat(prepared.bankAmount()).isEqualTo("149.75");
            assertThat(prepared.sourceCount()).isEqualTo(2);
            var gross=owner.queryForMap("SELECT sum(debit_amount) debit,sum(credit_amount) credit FROM accounting_journal_lines WHERE accounting_journal_id=? AND account_id=?",prepared.journalId(),test.book().clearing());
            assertThat((BigDecimal)gross.get("debit")).isEqualByComparingTo("50.25");assertThat((BigDecimal)gross.get("credit")).isEqualByComparingTo("200");
            settlementDecide(test,f.reviewer(),prepared,"POST");assertThat(settlementStudentMoney(f)).isEqualTo(before);
            assertThat(familyFees(f).balance()).isEqualByComparingTo("850.25");assertThat(familyFees(f).receipts()).hasSize(1);
        }
    }

    @Test void settlementPreparationCrossFinancialYearRefundUsesClosedOriginalConfigurationAndCurrentCompatibleBook(){
        for(String basis:List.of("CASH","ACCRUAL")){
            var f=refundFixture();LocalDate receivedOn=schoolToday().withDayOfMonth(1).minusMonths(1);
            LocalDate originalEnds=schoolToday().withDayOfMonth(1).minusDays(1);
            // A genuine prior-month receipt in a bounded original book keeps the
            // fixture inside its academic session and does not rewrite history.
            UUID payment=historicalRefundRepairPayment(f,receivedOn.atTime(12,0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
            assertThat(feeJournalReceiptDate(f,payment)).isEqualTo(receivedOn);
            UUID refund=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"50.25")).id();refundDecide(f,f.reviewer(),refund,"APPROVE");
            var held=new RefundReadFixture(f,payment,refund);var read=refundSuccessRead(held,123);refundSuccessRetain(held,read.id());
            var original=refundPostingOriginalBook(held,basis,originalEnds);
            settlementCloseAccountingYear(f,original.year());UUID current=settlementCompatibleCurrentYear(f,basis,original.year());
            var retained=new java.util.LinkedHashMap<>(feeJournalReadProtectedState(f));
            // New vouchers advance the current sequence and append audits. All
            // previously retained financial/source rows must remain byte-exact.
            retained.keySet().removeAll(Set.of("accounting_journal_sequences","audit_events"));
            var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),held.refundId(),refundPostingCommand(held,current)));
            actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),new com.educore.api.accounting.AccountingController.DecisionRequest(
                    f.school(),"POST","Synthetic independently posted cross-year completed refund",1)));
            var zero=BigDecimal.ZERO;var summary=new GatewaySettlementProvider.SettlementSummary("synthetic_source_statement","SUCCESS","synthetic_source_utr",
                    null,null,null,null,new BigDecimal("200.00"),zero,zero,zero,zero,zero,zero,zero,zero,new BigDecimal("149.75"),null,null);
            var events=List.of(sourceReviewEvent(f,held.paymentId(),"synthetic_cross_year_payment","PAYMENT","CREDIT",null),
                    refundCandidateEvent(held,"synthetic_cross_year_refund",List.of(held.refundId().toString())));
            var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,events)),true,false,summary);
            UUID bank=owner.queryForObject("SELECT id FROM accounting_accounts WHERE school_id=? AND code='PROOF_BANK'",UUID.class,f.school());
            UUID expense=account(f,"SYN_CROSS_YEAR_EXPENSE","EXPENSE","EXPENSE");
            var evidence=actorScoped(f.admin(),()->bankService(new SettlementBankPg(f,flow,bank)).record(f.admin(),flow.request(),new GatewaySettlementBankEvidenceModels.Command(
                    f.school(),UUID.randomUUID(),bank,"INR","149.75",Instant.now().minusMillis(1),"BANK_STATEMENT_CREDIT","synthetic_source_utr",true,"Synthetic actual cross-year settlement bank credit")));
            var book=new FeeJournalPgBook(current,settlementCorrectionPeriod(f,current,schoolToday()),original.clearing(),original.income(),original.receivable());
            var test=new SettlementPreparationPg(f,flow,book,bank,expense,held.paymentId(),List.of(completed.id()),evidence,zero,false);
            var before=settlementStudentMoney(f);var prepared=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,null));
            assertThat(owner.queryForObject("SELECT financial_year_id FROM fee_gateway_settlement_preparations WHERE id=?",UUID.class,prepared.id())).isEqualTo(current);
            assertThat(prepared.sourceCount()).isEqualTo(2);
            assertThat(owner.queryForObject("""
                    SELECT batch.financial_year_id=? AND config.financial_year_id=? AND batch.financial_year_id<>config.financial_year_id
                    FROM fee_gateway_settlement_sources source
                    JOIN fee_accounting_posting_batches batch ON batch.id=source.posting_batch_id
                      AND batch.organisation_id=source.organisation_id AND batch.school_id=source.school_id
                    JOIN fee_accounting_configurations config ON config.id=source.configuration_id
                      AND config.organisation_id=source.organisation_id AND config.school_id=source.school_id
                    WHERE source.root_id=? AND source.source_kind='REFUND' AND source.refund_posting_id=?
                    """,Boolean.class,current,original.year(),prepared.rootId(),completed.id())).isTrue();
            assertThatThrownBy(()->settlementDecide(test,f.admin(),prepared,"POST")).isInstanceOf(com.educore.api.common.api.ApiException.class);
            settlementDecide(test,f.reviewer(),prepared,"POST");
            assertThat(owner.queryForObject("SELECT status FROM accounting_financial_years WHERE id=?",String.class,original.year())).isEqualTo("CLOSED");
            assertThat(owner.queryForObject("SELECT status FROM accounting_financial_years WHERE id=?",String.class,current)).isEqualTo("OPEN");
            var actual=feeJournalReadProtectedState(f);retained.forEach((table,rows)->assertThat(actual.get(table)).as("Preserved historical %s in %s cross-year settlement",table,basis).containsAll(rows));
            assertThat(settlementStudentMoney(f)).isEqualTo(before);
        }
    }

    private UUID settlementCompatibleCurrentYear(Fixture f,String basis,UUID originalYear){
        LocalDate starts=settlementNextYearStart(f,originalYear);
        var year=actorScoped(f.admin(),()->accounting.createFinancialYear(f.admin(),new com.educore.api.accounting.AccountingController.FinancialYearRequest(
                f.school(),"SYN_CURRENT_REFUND","Synthetic compatible current refund book",starts,starts.plusYears(1).minusDays(1),basis,"INR","Synthetic cross-year historical refund acceptance")));
        actorScoped(f.reviewer(),()->accounting.openFinancialYear(f.reviewer(),year.id(),new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                f.school(),0,"Synthetic independent compatible current year opening")));return year.id();
    }

    private LocalDate settlementNextYearStart(Fixture f,UUID originalYear){
        LocalDate starts=owner.queryForObject("SELECT ends_on+1 FROM accounting_financial_years WHERE id=? AND organisation_id=? AND school_id=?",
                LocalDate.class,originalYear,f.org(),f.school());
        assertThat(starts).isBeforeOrEqualTo(schoolToday());return starts;
    }

    @Test void settlementPreparationEqualPartialRefundsRequireDistinctPhysicalChoices(){
        var test=settlementPreparationFixture("CASH",2,BigDecimal.ZERO,false);var f=test.fixture();var command=settlementPreparationCommand(test,null);
        assertThat(command.refundAssociations()).hasSize(2);var first=command.refundAssociations().getFirst();var second=command.refundAssociations().getLast();
        var duplicate=settlementCommandCopy(command,command.clientRequestId(),command.bankEvidenceId(),command.contextFingerprint(),command.postingOn(),command.componentReviews(),
                List.of(first,new GatewaySettlementPreparationModels.RefundAssociation(second.sourceReadId(),second.rowIndex(),first.refundPostingId(),second.reason())),null);
        var before=settlementStudentMoney(f);
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),duplicate)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_preparations WHERE school_id=?",Integer.class,f.school())).isZero();
        var made=settlementPrepare(test,f.admin(),command);assertThat(made.bankAmount()).isEqualTo("99.50");assertThat(made.sourceCount()).isEqualTo(3);
        assertThat(owner.queryForList("SELECT refund_posting_id FROM fee_gateway_settlement_sources WHERE root_id=? AND source_kind='REFUND'",UUID.class,made.rootId()))
                .containsExactlyInAnyOrderElementsOf(test.refunds());
        assertThat(owner.queryForObject("SELECT count(DISTINCT row_index) FROM fee_gateway_settlement_source_rows WHERE preparation_id=?",Integer.class,made.id())).isEqualTo(3);
        settlementDecide(test,f.reviewer(),made,"POST");assertThat(settlementStudentMoney(f)).isEqualTo(before);
    }

    @Test void settlementPreparationRequiresDifferentActualHumanEvenAcrossMemberships(){
        var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var f=test.fixture();var made=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,null));
        UUID extra=UUID.randomUUID(),campus=owner.queryForObject("SELECT id FROM campuses WHERE school_id=?",UUID.class,f.school());
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())",extra,f.admin().userId(),f.org(),f.school(),campus);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001')",extra);
        var sameHuman=actor(f.admin().userId(),extra,f.org());var before=settlementStudentMoney(f);
        for(String decision:List.of("POST","REJECT"))assertThatThrownBy(()->settlementDecide(test,sameHuman,made,decision)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(sameHuman,()->runtime.queryForObject("SELECT assert_gateway_settlement_journal_decision(?,?,?)",(row,n)->true,made.journalId(),extra,"POST"))).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT status FROM accounting_journals WHERE id=?",String.class,made.journalId())).isEqualTo("SUBMITTED");
        settlementDecide(test,f.reviewer(),made,"POST");assertThat(settlementStudentMoney(f)).isEqualTo(before);
    }

    @Test void settlementReversalRequiresDifferentActualHumanEvenAcrossMemberships(){
        var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var f=test.fixture();
        var made=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,null));settlementDecide(test,f.reviewer(),made,"POST");
        var reversal=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),made.journalId(),
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),test.book().period(),schoolToday(),"Synthetic settlement reversal requiring another person")));
        UUID extra=UUID.randomUUID(),campus=owner.queryForObject("SELECT id FROM campuses WHERE school_id=?",UUID.class,f.school());
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())",extra,f.admin().userId(),f.org(),f.school(),campus);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001')",extra);
        var sameHuman=actor(f.admin().userId(),extra,f.org());var before=settlementStudentMoney(f);
        for(String decision:List.of("APPROVE","REJECT")) {
            assertThatThrownBy(()->actorScoped(sameHuman,()->accounting.decideReversal(sameHuman,reversal.id(),
                    new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),decision,"Synthetic second membership cannot decide own reversal",0))))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_REVERSAL_DECISION_CONFLICT"));
            assertThatThrownBy(()->actorScoped(sameHuman,()->runtime.queryForObject("SELECT assert_gateway_settlement_reversal_decision(?,?,?)",(row,n)->true,reversal.id(),extra,decision)))
                    .isInstanceOfSatisfying(DataAccessException.class,error->assertThat(settlementSqlConstraint(error)).isEqualTo("gateway_settlement_independent_reversal_reviewer_required"));
        }
        assertThatThrownBy(()->actorScoped(sameHuman,()->runtime.update("""
                UPDATE accounting_reversal_requests SET status='REJECTED',decided_by_membership_id=?,decided_at=clock_timestamp(),
                    decision_reason='Synthetic direct second-membership decision denied',updated_at=clock_timestamp(),row_version=row_version+1
                WHERE id=?
                """,extra,reversal.id())))
                .isInstanceOfSatisfying(DataAccessException.class,error->assertThat(settlementSqlConstraint(error)).isEqualTo("gateway_settlement_independent_reversal_reviewer_required"));
        assertThat(owner.queryForObject("SELECT status FROM accounting_reversal_requests WHERE id=?",String.class,reversal.id())).isEqualTo("PENDING");
        assertThat(owner.queryForObject("SELECT status FROM accounting_journals WHERE id=?",String.class,made.journalId())).isEqualTo("POSTED");
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE reversal_of_journal_id=?",Integer.class,made.journalId())).isZero();
        assertThat(settlementStudentMoney(f)).isEqualTo(before);
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic independent settlement reversal approval",0)));
        assertThat(owner.queryForObject("SELECT status FROM accounting_reversal_requests WHERE id=?",String.class,reversal.id())).isEqualTo("APPROVED");
        assertThat(owner.queryForObject("SELECT status FROM accounting_journals WHERE id=?",String.class,made.journalId())).isEqualTo("REVERSED");
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE reversal_of_journal_id=? AND status='POSTED'",Integer.class,made.journalId())).isEqualTo(1);
        assertThat(settlementStudentMoney(f)).isEqualTo(before);
    }

    @Test void settlementReversalRejectsIneligibleDatesAndBooksButKeepsIndependentRejectionAvailable(){
        for(String invalid:List.of("BEFORE_ORIGINAL","FUTURE","CLOSED_DAY","BASIS","CURRENCY","CLOSED_PERIOD")){
            boolean incompatible=Set.of("BASIS","CURRENCY").contains(invalid);
            // The original book is born with truthful ended bounds. Closing an
            // overlapping year must never make an invalid target fixture legal.
            var test=incompatible?settlementPastPreparationFixture(schoolToday().withDayOfMonth(1).minusMonths(3),
                    schoolToday().withDayOfMonth(1).minusMonths(2).minusDays(1)):settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);
            var f=test.fixture();var command=settlementPreparationCommand(test,null);
            if(incompatible)command=settlementCommandCopy(command,command.clientRequestId(),command.bankEvidenceId(),command.contextFingerprint(),
                    test.bankEvidence().creditedOn(),command.componentReviews(),command.refundAssociations(),null);
            var made=settlementPrepare(test,f.admin(),command);settlementDecide(test,f.reviewer(),made,"POST");
            UUID year=test.book().year();LocalDate correctionOn=schoolToday();
            if(incompatible){
                settlementCloseAccountingYear(f,year);
                // All four books are born with non-overlapping bounds and are
                // opened/closed sequentially. This past target is otherwise
                // eligible; only its basis or currency differs from the source.
                LocalDate starts=settlementNextYearStart(f,year),ends=schoolToday().withDayOfMonth(1).minusDays(1);
                var target=actorScoped(f.admin(),()->accounting.createFinancialYear(f.admin(),new com.educore.api.accounting.AccountingController.FinancialYearRequest(
                        f.school(),"SYN_SETTLEMENT_CORRECTION","Synthetic incompatible settlement correction book",starts,ends,
                        invalid.equals("BASIS")?"ACCRUAL":"CASH",invalid.equals("CURRENCY")?"USD":"INR","Synthetic scoped reversal book compatibility test")));
                actorScoped(f.reviewer(),()->accounting.openFinancialYear(f.reviewer(),target.id(),new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                        f.school(),0,"Synthetic independent target book opening")));year=target.id();correctionOn=starts;
            }
            LocalDate date=invalid.equals("FUTURE")?schoolToday().plusDays(1):invalid.equals("BEFORE_ORIGINAL")?schoolToday().minusDays(1):correctionOn;
            UUID period=settlementCorrectionPeriod(f,year,date);
            if(incompatible){
                assertThat(date).isBeforeOrEqualTo(schoolToday()).isAfterOrEqualTo(test.bankEvidence().creditedOn());
                assertThat(owner.queryForObject("""
                        SELECT target.status='OPEN' AND period.status='OPEN' AND ? BETWEEN target.starts_on AND target.ends_on
                          AND ? BETWEEN period.starts_on AND period.ends_on
                          AND target.accounting_basis=? AND target.currency_code=?
                          AND NOT EXISTS(SELECT 1 FROM fee_day_closes closed WHERE closed.organisation_id=target.organisation_id
                            AND closed.school_id=target.school_id AND closed.status='APPROVED' AND closed.business_date>=?)
                        FROM accounting_financial_years target JOIN accounting_periods period ON period.financial_year_id=target.id
                        WHERE target.id=? AND period.id=? AND target.organisation_id=? AND target.school_id=?
                        """,Boolean.class,date,date,invalid.equals("BASIS")?"ACCRUAL":"CASH",invalid.equals("CURRENCY")?"USD":"INR",
                        date,year,period,f.org(),f.school())).isTrue();
            }
            var reversal=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),made.journalId(),
                    new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),period,date,"Synthetic retained request with ineligible settlement correction target")));
            UUID close=invalid.equals("CLOSED_DAY")?refundSuccessApproveClose(f,date):null;
            String retainedClose=close==null?null:refundSuccessCloseState(close);
            if(invalid.equals("CLOSED_PERIOD")){
                long version=owner.queryForObject("SELECT row_version FROM accounting_periods WHERE id=?",Long.class,period);
                actorScoped(f.reviewer(),()->accounting.transitionPeriod(f.reviewer(),period,new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(
                        f.school(),"CLOSE",version,"Synthetic target period closed after reversal request")));
            }
            var before=settlementStudentMoney(f);
            assertThatThrownBy(()->actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),
                    new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic ineligible settlement reversal must not post",0))))
                    .as("Scoped settlement correction refuses %s",invalid).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error->assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_REVERSAL_DECISION_CONFLICT"));
            assertThatThrownBy(()->actorScoped(f.reviewer(),()->runtime.queryForObject("SELECT assert_gateway_settlement_reversal_decision(?,?,?)",(row,n)->true,
                    reversal.id(),member(f.reviewer()),"APPROVE")))
                    .isInstanceOfSatisfying(DataAccessException.class,error->assertThat(settlementSqlConstraint(error)).isEqualTo("gateway_settlement_reversal_target_ineligible"));
            assertThat(owner.queryForObject("SELECT status FROM accounting_reversal_requests WHERE id=?",String.class,reversal.id())).isEqualTo("PENDING");
            assertThat(owner.queryForObject("SELECT status FROM accounting_journals WHERE id=?",String.class,made.journalId())).isEqualTo("POSTED");
            assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE reversal_of_journal_id=?",Integer.class,made.journalId())).isZero();
            actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),
                    new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"REJECT","Synthetic independent rejection remains available after target becomes ineligible",0)));
            assertThat(owner.queryForObject("SELECT status FROM accounting_reversal_requests WHERE id=?",String.class,reversal.id())).isEqualTo("REJECTED");
            assertThat(settlementStudentMoney(f)).isEqualTo(before);
            if(close!=null)assertThat(refundSuccessCloseState(close)).isEqualTo(retainedClose);
        }
    }

    @Test void settlementReversalRechecksTheFinancialCloseCommittedWhileWaitingForAccountingFence()throws Exception{
        var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var f=test.fixture();
        var made=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,null));settlementDecide(test,f.reviewer(),made,"POST");
        var reversal=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),made.journalId(),
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),test.book().period(),schoolToday(),"Synthetic settlement reversal queued behind financial close")));
        var declarations=register(f,schoolToday(),schoolToday(),null,null,0,25).tenderTotals().stream()
                .map(tender->new FinanceController.TenderDeclaration(tender.paymentMethod(),tender.summary().netCollections())).toList();
        var close=actorScoped(f.admin(),()->finance.proposeDayClose(f.admin(),new FinanceController.DayCloseRequest(
                f.school(),schoolToday(),declarations,"Synthetic financial close races settlement correction",null)));
        var outcome=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Object>>();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            actorScoped(f.reviewer(),()->{
                finance.decideDayClose(f.reviewer(),close.id(),new FinanceController.DayCloseDecisionRequest(
                        f.school(),"APPROVED","Synthetic independently committed close before queued correction",0));
                outcome.set(executor.submit(()->{try{return actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),
                        new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic queued correction must see newly closed date",0)));}
                    catch(RuntimeException failure){return failure;}}));
                awaitBankFinanceFence();return null;
            });
            assertThat(outcome.get().get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                    error->assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_REVERSAL_DECISION_CONFLICT"));
        }
        String retainedClose=refundSuccessCloseState(close.id());var before=settlementStudentMoney(f);
        assertThat(owner.queryForObject("SELECT status FROM fee_day_closes WHERE id=?",String.class,close.id())).isEqualTo("APPROVED");
        assertThat(owner.queryForObject("SELECT status FROM accounting_reversal_requests WHERE id=?",String.class,reversal.id())).isEqualTo("PENDING");
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE reversal_of_journal_id=?",Integer.class,made.journalId())).isZero();
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),new com.educore.api.accounting.AccountingController.DecisionRequest(
                f.school(),"REJECT","Synthetic independent rejection after committed close",0)));
        assertThat(refundSuccessCloseState(close.id())).isEqualTo(retainedClose);assertThat(settlementStudentMoney(f)).isEqualTo(before);
    }

    @Test void settlementPreparationRepairCannotPredateItsApprovedPostedCounterVoucher(){
        var test=settlementPastPreparationFixture();var f=test.fixture();LocalDate originalDate=test.bankEvidence().creditedOn();
        var initial=settlementPreparationCommand(test,null);
        var first=settlementPrepare(test,f.admin(),settlementCommandCopy(initial,initial.clientRequestId(),initial.bankEvidenceId(),initial.contextFingerprint(),
                originalDate,initial.componentReviews(),initial.refundAssociations(),null));settlementDecide(test,f.reviewer(),first,"POST");
        UUID period=settlementCorrectionPeriod(f,test.book().year(),schoolToday());
        var reversal=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),first.journalId(),
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),period,schoolToday(),"Synthetic later-date independent settlement correction")));
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),new com.educore.api.accounting.AccountingController.DecisionRequest(
                f.school(),"APPROVE","Synthetic independently approved later-date counter-voucher",0)));
        var terms=settlementPreparationCommand(test,first.id());var before=settlementStudentMoney(f);
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),settlementCommandCopy(terms,terms.clientRequestId(),terms.bankEvidenceId(),terms.contextFingerprint(),
                originalDate,terms.componentReviews(),terms.refundAssociations(),first.id())))
                .isInstanceOfSatisfying(DataAccessException.class,error->assertThat(settlementSqlConstraint(error)).isEqualTo("gateway_settlement_repair_before_counter"));
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_preparations WHERE root_id=?",Integer.class,first.rootId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE school_id=? AND source_module='GATEWAY_SETTLEMENT'",Integer.class,f.school())).isEqualTo(1);
        assertThat(settlementStudentMoney(f)).isEqualTo(before);
        var repaired=settlementPrepare(test,f.admin(),terms);
        assertThat(repaired.rootId()).isEqualTo(first.rootId());assertThat(repaired.previousPreparationId()).isEqualTo(first.id());
        assertThat(repaired.postingOn()).isEqualTo(schoolToday());assertThat(repaired.status()).isEqualTo("SUBMITTED");
        assertThat(settlementStudentMoney(f)).isEqualTo(before);
    }

    @Test void settlementPreparationRejectsForgedScopeReviewPaiseAndUnbalancedTerms(){
        var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,true);var f=test.fixture();var command=settlementPreparationCommand(test,null);var foreign=fixture(true);
        assertThat(settlementContext(test,f.admin()).componentFacts().stream().filter(fact->fact.key().equals("SERVICE_CHARGE")).findFirst().orElseThrow().summaryAmount()).isNull();
        var before=settlementStudentMoney(f);var reported=new java.util.ArrayList<>(command.componentReviews());var review=reported.getFirst();
        reported.set(0,new GatewaySettlementPreparationModels.ComponentReview(review.key(),"REPORTED",review.direction(),null,"0.00",null,"Synthetic missing is not reported zero"));
        var bad=settlementCommandCopy(command,UUID.randomUUID(),command.bankEvidenceId(),command.contextFingerprint(),command.postingOn(),reported,command.refundAssociations(),null);
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),bad)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        for(var denied:List.of(f.parent(),foreign.admin()))assertThatThrownBy(()->settlementPrepare(test,denied,command)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),settlementCommandCopy(command,UUID.randomUUID(),command.bankEvidenceId(),"f".repeat(64),command.postingOn(),command.componentReviews(),command.refundAssociations(),null))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),settlementCommandCopy(command,UUID.randomUUID(),UUID.randomUUID(),command.contextFingerprint(),command.postingOn(),command.componentReviews(),command.refundAssociations(),null))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var fractional=new java.util.ArrayList<>(command.componentReviews());fractional.set(0,new GatewaySettlementPreparationModels.ComponentReview(review.key(),"CONFIRMED_ZERO","DEBIT",null,"0.001",null,"Synthetic invalid fractional paise"));
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),settlementCommandCopy(command,UUID.randomUUID(),command.bankEvidenceId(),command.contextFingerprint(),command.postingOn(),fractional,command.refundAssociations(),null))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var unbalanced=new java.util.ArrayList<>(command.componentReviews());unbalanced.set(0,new GatewaySettlementPreparationModels.ComponentReview(review.key(),"EXTERNAL_REVIEW","DEBIT",test.expense(),"1.00","SYN-COMP-EXTERNAL","Synthetic real document cannot manufacture net equality"));
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),settlementCommandCopy(command,UUID.randomUUID(),command.bankEvidenceId(),command.contextFingerprint(),command.postingOn(),unbalanced,command.refundAssociations(),null))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_roots WHERE school_id=?",Integer.class,f.school())).isZero();assertThat(settlementStudentMoney(f)).isEqualTo(before);
        var made=settlementPrepare(test,f.admin(),command);
        assertThat(owner.queryForObject("SELECT raw_field_facts->>'summaryAmount' IS NULL AND raw_field_facts->>'mode'='CONFIRMED_ZERO' FROM fee_gateway_settlement_components WHERE preparation_id=? AND component_key='SERVICE_CHARGE'",Boolean.class,made.id())).isTrue();
    }

    @Test void settlementPreparationRefusesPendingAndActuallyReversedSources(){
        for(boolean actuallyReversed:List.of(false,true)){
            var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var f=test.fixture();var command=settlementPreparationCommand(test,null);
            UUID original=owner.queryForObject("SELECT accounting_journal_id FROM fee_accounting_posting_batches WHERE school_id=? AND event_type='PAYMENT'",UUID.class,f.school());
            var reversal=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),original,new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),test.book().period(),schoolToday(),"Synthetic source reversal preceding settlement")));
            if(actuallyReversed)actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic actual source correction",0)));
            var before=settlementStudentMoney(f);assertThatThrownBy(()->settlementPrepare(test,f.admin(),command)).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_preparations WHERE school_id=?",Integer.class,f.school())).isZero();assertThat(settlementStudentMoney(f)).isEqualTo(before);
        }
    }

    @Test void settlementPreparationProtectsConsumedBankAndSourceReversals(){
        var test=settlementPreparationFixture("CASH",1,BigDecimal.ZERO,false);var f=test.fixture();var made=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,null));var before=settlementStudentMoney(f);
        var retainedBank=completedRefundProofRead(f,f.reviewer(),()->bankService(new SettlementBankPg(f,test.flow(),test.bank())).context(f.reviewer(),f.school(),test.flow().request()));
        assertThat(retainedBank.evidence()).singleElement().satisfies(value->assertThat(value.canReject()).isFalse());
        assertThatThrownBy(()->actorScoped(f.reviewer(),()->bankService(new SettlementBankPg(f,test.flow(),test.bank())).reject(f.reviewer(),test.flow().request(),test.bankEvidence().id(),
                new GatewaySettlementBankEvidenceModels.RejectionCommand(f.school(),UUID.randomUUID(),"Synthetic consumed bank cannot release",true)))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        for(UUID journal:owner.queryForList("SELECT source_journal_id FROM fee_gateway_settlement_sources WHERE root_id=?",UUID.class,made.rootId()))
            assertThatThrownBy(()->actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),journal,new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),test.book().period(),schoolToday(),"Synthetic consumed source reversal denied")))).isInstanceOf(RuntimeException.class);
        UUID refundJournal=owner.queryForObject("SELECT source_journal_id FROM fee_gateway_settlement_sources WHERE root_id=? AND source_kind='REFUND'",UUID.class,made.rootId());
        assertThatThrownBy(()->actorScoped(f.admin(),()->runtime.update("""
                INSERT INTO accounting_reversal_requests(id,organisation_id,school_id,accounting_journal_id,requested_accounting_period_id,
                    requested_posting_date,request_reason,status,requested_by_membership_id,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'Synthetic direct consumed refund reversal','PENDING',?,clock_timestamp(),clock_timestamp())
                """,UUID.randomUUID(),f.org(),f.school(),refundJournal,test.book().period(),schoolToday(),member(f.admin()))))
                .isInstanceOfSatisfying(DataAccessException.class,error->assertThat(settlementSqlConstraint(error)).isEqualTo("gateway_settlement_source_anchored"));
        assertThatThrownBy(()->actorScoped(f.admin(),()->finance.reversePayment(f.admin(),test.payment(),new FinanceController.PaymentReversalRequest(f.school(),0,"Synthetic settled receipt cannot reverse")))).isInstanceOf(RuntimeException.class);
        assertThat(settlementStudentMoney(f)).isEqualTo(before);
        var unused=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var u=unused.fixture();var command=settlementPreparationCommand(unused,null);
        actorScoped(u.reviewer(),()->bankService(new SettlementBankPg(u,unused.flow(),unused.bank())).reject(u.reviewer(),unused.flow().request(),unused.bankEvidence().id(),
                new GatewaySettlementBankEvidenceModels.RejectionCommand(u.school(),UUID.randomUUID(),"Synthetic evidence rejected before preparation",true)));
        assertThatThrownBy(()->settlementPrepare(unused,u.admin(),command)).isInstanceOf(com.educore.api.common.api.ApiException.class);
    }

    @Test void settlementPreparationRepairsRejectedAndReversedJournalsWithoutReleasingPostedMoney(){
        for(boolean postedBefore:List.of(false,true)){
            var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var f=test.fixture();var first=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,null));
            settlementDecide(test,f.reviewer(),first,postedBefore?"POST":"REJECT");
            if(postedBefore){var reversal=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),first.journalId(),new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),test.book().period(),schoolToday(),"Synthetic settlement accounting correction")));
                actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),reversal.id(),new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic independent settlement reversal",0)));}
            var before=settlementStudentMoney(f);var repair=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,first.id()));
            assertThat(repair.rootId()).isEqualTo(first.rootId());assertThat(repair.previousPreparationId()).isEqualTo(first.id());assertThat(repair.revision()).isEqualTo(2);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_sources WHERE root_id=?",Integer.class,first.rootId())).isEqualTo(1);
            settlementDecide(test,f.reviewer(),repair,"REJECT");
            var release=new GatewaySettlementPreparationModels.AbandonmentCommand(f.school(),UUID.randomUUID(),"Synthetic rejected root release",true);
            if(postedBefore)assertThatThrownBy(()->actorScoped(f.admin(),()->settlementPreparationService(test).abandon(f.admin(),repair.id(),release))).isInstanceOf(com.educore.api.common.api.ApiException.class);
            else {
                var abandoned=actorScoped(f.admin(),()->settlementPreparationService(test).abandon(f.admin(),repair.id(),release));assertThat(abandoned.preparation().status()).isEqualTo("ABANDONED");
                assertThat(actorScoped(f.admin(),()->settlementPreparationService(test).abandon(f.admin(),repair.id(),release))).isEqualTo(abandoned);
                var fresh=settlementPrepare(test,f.admin(),settlementPreparationCommand(test,null));assertThat(fresh.rootId()).isNotEqualTo(first.rootId());
            }
            assertThat(settlementStudentMoney(f)).isEqualTo(before);
        }
    }

    @Test void settlementPreparationRejectsClosedBooksRevokedAuthorityAndStaleIsolation(){
        var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var f=test.fixture();var command=settlementPreparationCommand(test,null);
        var before=settlementStudentMoney(f);
        var stale=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));stale.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(f.admin().userId(),f.org())){
            assertThatThrownBy(()->stale.execute(status->settlementPreparationService(test).prepare(f.admin(),test.flow().request(),command))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        long periodVersion=owner.queryForObject("SELECT row_version FROM accounting_periods WHERE id=?",Long.class,test.book().period());
        actorScoped(f.reviewer(),()->accounting.transitionPeriod(f.reviewer(),test.book().period(),new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(f.school(),"CLOSE",periodVersion,"Synthetic closed settlement target period")));
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),command)).isInstanceOf(com.educore.api.common.api.ApiException.class);assertThat(settlementStudentMoney(f)).isEqualTo(before);
        var revoked=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var r=revoked.fixture();var cached=settlementPreparationCommand(revoked,null);
        owner.update("UPDATE memberships SET status='SUSPENDED' WHERE id=?",member(r.admin()));var unchanged=settlementStudentMoney(r);
        assertThatThrownBy(()->settlementPrepare(revoked,r.admin(),cached)).isInstanceOf(com.educore.api.common.api.ApiException.class);assertThat(settlementStudentMoney(r)).isEqualTo(unchanged);
        for(boolean preparedBeforeClose:List.of(false,true)){
            var closed=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var c=closed.fixture();var terms=settlementPreparationCommand(closed,null);
            var pending=preparedBeforeClose?settlementPrepare(closed,c.admin(),terms):null;
            UUID close=refundSuccessApproveClose(c,schoolToday());String retained=refundSuccessCloseState(close);var money=settlementStudentMoney(c);
            if(preparedBeforeClose){assertThatThrownBy(()->settlementDecide(closed,c.reviewer(),pending,"POST")).isInstanceOf(RuntimeException.class);
                assertThat(owner.queryForObject("SELECT status FROM accounting_journals WHERE id=?",String.class,pending.journalId())).isEqualTo("SUBMITTED");}
            else assertThatThrownBy(()->settlementPrepare(closed,c.admin(),terms)).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_POSTING_DAY_CLOSED"));
            assertThat(refundSuccessCloseState(close)).isEqualTo(retained);assertThat(settlementStudentMoney(c)).isEqualTo(money);
        }
    }

    @Test void settlementPreparationRollsBackAuditAndDatabaseCouplingFailures(){
        var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var f=test.fixture();var command=settlementPreparationCommand(test,null);var before=settlementStudentMoney(f);
        var brokenAudit=org.mockito.Mockito.mock(SecurityAuditService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic settlement audit unavailable")).when(brokenAudit).recordScoped(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap());
        assertThatThrownBy(()->actorScoped(f.admin(),()->settlementPreparationService(test,brokenAudit).prepare(f.admin(),test.flow().request(),command))).isInstanceOf(IllegalStateException.class);
        for(String table:List.of("fee_gateway_settlement_roots","fee_gateway_settlement_preparations","fee_gateway_settlement_sources","fee_gateway_settlement_source_rows","fee_gateway_settlement_components"))
            assertThat(owner.queryForObject("SELECT count(*) FROM "+table+" WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE school_id=? AND source_module='GATEWAY_SETTLEMENT'",Integer.class,f.school())).isZero();
        assertThat(settlementStudentMoney(f)).isEqualTo(before);
        // An internal bridge call cannot commit a balanced orphan journal whose
        // immutable settlement preparation and source rows do not exist.
        assertThatThrownBy(()->actorScoped(f.admin(),()->accounting.prepareSettlementJournal(f.admin(),
                new com.educore.api.accounting.AccountingModuleBridge.SettlementJournalRequest(f.school(),test.book().year(),test.book().period(),schoolToday(),
                        "SYN-ORPHAN","Synthetic balanced orphan must roll back",UUID.randomUUID(),"synthetic-settlement-orphan-"+UUID.randomUUID(),"Synthetic deliberate coupling rejection",
                        List.of(new com.educore.api.accounting.AccountingModuleBridge.SettlementJournalLine(test.bank(),new BigDecimal("200.00"),BigDecimal.ZERO,"Synthetic bank debit"),
                                new com.educore.api.accounting.AccountingModuleBridge.SettlementJournalLine(test.book().clearing(),BigDecimal.ZERO,new BigDecimal("200.00"),"Synthetic clearing credit"))))))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("settlement preparation must couple");
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE school_id=? AND source_module='GATEWAY_SETTLEMENT'",Integer.class,f.school())).isZero();
        var made=settlementPrepare(test,f.admin(),command);
        String immutablePreparation=owner.queryForObject("SELECT to_jsonb(preparation)::text FROM fee_gateway_settlement_preparations preparation WHERE id=?",String.class,made.id());
        // Deny-by-default UPDATE RLS filters the row before its trigger runs.
        // Verify the protected state, rather than requiring an exception for a
        // statement that correctly changes no rows.
        assertThat(actorScoped(f.admin(),()->runtime.update("UPDATE fee_gateway_settlement_preparations SET bank_amount=bank_amount+1 WHERE id=?",made.id()))).isZero();
        assertThat(owner.queryForObject("SELECT to_jsonb(preparation)::text FROM fee_gateway_settlement_preparations preparation WHERE id=?",String.class,made.id())).isEqualTo(immutablePreparation);
        // The append-only trigger independently protects the same evidence when
        // this isolated test's migration owner bypasses the runtime row policy.
        assertThatThrownBy(()->owner.update("UPDATE fee_gateway_settlement_preparations SET bank_amount=bank_amount+1 WHERE id=?",made.id())).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT to_jsonb(preparation)::text FROM fee_gateway_settlement_preparations preparation WHERE id=?",String.class,made.id())).isEqualTo(immutablePreparation);
        assertThatThrownBy(()->actorScoped(f.admin(),()->runtime.update("DELETE FROM fee_gateway_settlement_sources WHERE root_id=?",made.rootId()))).isInstanceOf(DataAccessException.class);
        String root=owner.queryForObject("SELECT to_jsonb(root)::text FROM fee_gateway_settlement_roots root WHERE id=?",String.class,made.rootId());
        assertThatThrownBy(()->actorScoped(f.admin(),()->runtime.update("INSERT INTO fee_gateway_settlement_roots SELECT(jsonb_populate_record(NULL::fee_gateway_settlement_roots,?::jsonb||jsonb_build_object('id',?::uuid))).*",root,UUID.randomUUID()))).isInstanceOf(DataAccessException.class);
        settlementDecide(test,f.reviewer(),made,"REJECT");
        assertThatThrownBy(()->rawSettlementSnapshotRepair(test,made)).isInstanceOfSatisfying(RuntimeException.class,
                error->assertThat(settlementSqlConstraint(error)).isEqualTo("gateway_settlement_snapshot_source_mismatch"));
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_preparations WHERE root_id=?",Integer.class,made.rootId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE school_id=? AND source_module='GATEWAY_SETTLEMENT'",Integer.class,f.school())).isEqualTo(1);
        assertThat(settlementStudentMoney(f)).isEqualTo(before);
    }

    @Test void settlementPreparationConcurrentRetriesAndCompetingRequestsCannotDuplicateSources()throws Exception{
        var test=settlementPreparationFixture("CASH",0,BigDecimal.ZERO,false);var f=test.fixture();var command=settlementPreparationCommand(test,null);var before=settlementStudentMoney(f);
        var contender=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<GatewaySettlementPreparationModels.Preparation>>();
        GatewaySettlementPreparationModels.Preparation first;
        try(var executor=Executors.newSingleThreadExecutor()){
            first=actorScoped(f.admin(),()->{
                runtime.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(row,n)->true,f.school()+":finance");
                contender.set(executor.submit(()->settlementPrepare(test,f.admin(),command)));awaitBankFinanceFence();
                return settlementPreparationService(test).prepare(f.admin(),test.flow().request(),command);
            });
            assertThat(contender.get().get(30,TimeUnit.SECONDS).id()).isEqualTo(first.id());
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_roots WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
        var duplicate=settlementCommandCopy(command,UUID.randomUUID(),command.bankEvidenceId(),command.contextFingerprint(),command.postingOn(),command.componentReviews(),command.refundAssociations(),null);
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),duplicate)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var secondBank=actorScoped(f.admin(),()->bankService(new SettlementBankPg(f,test.flow(),test.bank())).record(f.admin(),test.flow().request(),
                new GatewaySettlementBankEvidenceModels.Command(f.school(),UUID.randomUUID(),test.bank(),"INR","200.00",Instant.now().minusSeconds(90),"BANK_STATEMENT_CREDIT","SYN-PREP-COMPETING",true,"Synthetic competing bank attestation")));
        var competing=settlementCommandCopy(command,UUID.randomUUID(),secondBank.id(),command.contextFingerprint(),command.postingOn(),command.componentReviews(),command.refundAssociations(),null);
        assertThatThrownBy(()->settlementPrepare(test,f.admin(),competing)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_preparations WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);assertThat(settlementStudentMoney(f)).isEqualTo(before);
    }

    private UUID settlementCorrectionPeriod(Fixture f,UUID year,LocalDate date){
        return owner.queryForObject("SELECT id FROM accounting_periods WHERE financial_year_id=? AND organisation_id=? AND school_id=? AND ? BETWEEN starts_on AND ends_on",
                UUID.class,year,f.org(),f.school(),date);
    }
    private void settlementCloseAccountingYear(Fixture f,UUID year){
        var periods=owner.queryForList("SELECT id,row_version FROM accounting_periods WHERE financial_year_id=? AND school_id=? ORDER BY period_number",year,f.school());
        actorScoped(f.reviewer(),()->{
            for(var period:periods)accounting.transitionPeriod(f.reviewer(),(UUID)period.get("id"),new com.educore.api.accounting.AccountingController.PeriodTransitionRequest(
                    f.school(),"CLOSE",((Number)period.get("row_version")).longValue(),"Synthetic original settlement period closed for correction-book review"));
            return null;
        });
        long version=owner.queryForObject("SELECT row_version FROM accounting_financial_years WHERE id=?",Long.class,year);
        actorScoped(f.reviewer(),()->accounting.closeFinancialYear(f.reviewer(),year,new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                f.school(),version,"Synthetic historical settlement year closure")));
    }
    private SettlementPreparationPg settlementPastPreparationFixture(){
        return settlementPastPreparationFixture(schoolToday().minusDays(2),null);
    }
    private SettlementPreparationPg settlementPastPreparationFixture(LocalDate receivedOn,LocalDate endsOn){
        // Produce genuine past receipts/book/bank evidence. Never edit immutable
        // timestamps or posted book bounds to make a fixture appear historical.
        var f=fixture(true);var zone=java.time.ZoneId.of("Asia/Kolkata");
        Instant paidAt=receivedOn.atTime(12,0).atZone(zone).toInstant();
        var payment=feeJournalPostedPayment(f,"200.00",paidAt);LocalDate received=feeJournalReceiptDate(f,payment.paymentId());
        assertThat(received).isEqualTo(receivedOn);
        var book=endsOn==null?feeJournalBook(f,"CASH",received):feeJournalBook(f,"CASH",received,endsOn);
        var batch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),"synthetic-settlement-past-source-"+UUID.randomUUID(),
                new FeeAccountingController.PostingRequest(f.school(),book.year(),received,"PAYMENT",200,"Synthetic genuine past receipt accounting")));
        UUID journal=feeJournalBatchJournal(f,batch.id());
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),journal,new com.educore.api.accounting.AccountingController.DecisionRequest(
                f.school(),"POST","Synthetic independently posted historical settlement source",1)));
        if(endsOn!=null)settlementCloseAccountingYear(f,book.year());
        LocalDate creditedOn=endsOn==null?receivedOn.plusDays(1):endsOn.plusDays(2);
        var zero=BigDecimal.ZERO;var summary=new GatewaySettlementProvider.SettlementSummary("synthetic_source_statement","SUCCESS","synthetic_source_utr",
                null,null,null,null,new BigDecimal("200.00"),zero,zero,zero,zero,zero,zero,zero,zero,new BigDecimal("200.00"),null,null);
        var event=sourceReviewEvent(f,payment.paymentId(),"synthetic_past_prepared_payment","PAYMENT","CREDIT",null);
        var pages=List.of(new GatewaySettlementProvider.SettlementEventsPage(null,List.of(event)));
        var flow=endsOn==null?sourceReviewFlow(f,pages,true,false,summary):sourceReviewFlow(f,pages,true,false,summary,
                creditedOn.minusDays(1).atStartOfDay(zone).toInstant(),creditedOn.plusDays(2).atStartOfDay(zone).toInstant());
        UUID bank=owner.queryForObject("SELECT id FROM accounting_accounts WHERE school_id=? AND code='PROOF_BANK'",UUID.class,f.school());
        UUID expense=account(f,"SYN_SETTLEMENT_EXPENSE","EXPENSE","EXPENSE");UUID currentBankYear=null;
        if(endsOn!=null){
            // One OPEN year is allowed. Close the historical fee book before
            // opening the real current INR context required by V083 bank guards.
            LocalDate starts=schoolToday().withDayOfMonth(1);
            assertThat(endsOn).isBefore(starts.minusMonths(2));
            var current=actorScoped(f.admin(),()->accounting.createFinancialYear(f.admin(),new com.educore.api.accounting.AccountingController.FinancialYearRequest(
                    f.school(),"SYN_CURRENT_BANK","Synthetic current INR bank identity book",starts,starts.plusYears(1).minusDays(1),
                    "CASH","INR","Synthetic actual current bank-evidence accounting context")));
            actorScoped(f.reviewer(),()->accounting.openFinancialYear(f.reviewer(),current.id(),new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                    f.school(),0,"Synthetic independent current bank book opening")));currentBankYear=current.id();
        }
        var evidence=actorScoped(f.admin(),()->bankService(new SettlementBankPg(f,flow,bank)).record(f.admin(),flow.request(),new GatewaySettlementBankEvidenceModels.Command(
                f.school(),UUID.randomUUID(),bank,"INR","200.00",creditedOn.atTime(12,0).atZone(zone).toInstant(),"BANK_STATEMENT_CREDIT",
                "synthetic_source_utr",true,"Synthetic genuine prior-day settlement bank credit")));
        FeeJournalPgBook settlementBook=book;
        if(endsOn!=null){
            // Bank attestation is not a voucher, so the empty current book can
            // close normally. A distinct compatible historical settlement book
            // follows, sourcing the now-closed original fee book without reopening.
            settlementCloseAccountingYear(f,currentBankYear);
            LocalDate starts=endsOn.plusDays(1),ends=schoolToday().withDayOfMonth(1).minusMonths(1).minusDays(1);
            assertThat(creditedOn).isAfterOrEqualTo(starts).isBeforeOrEqualTo(ends);
            var settlementYear=actorScoped(f.admin(),()->accounting.createFinancialYear(f.admin(),new com.educore.api.accounting.AccountingController.FinancialYearRequest(
                    f.school(),"SYN_PAST_SETTLEMENT","Synthetic compatible historical settlement book",starts,ends,
                    "CASH","INR","Synthetic historical settlement accounting after retained bank attestation")));
            actorScoped(f.reviewer(),()->accounting.openFinancialYear(f.reviewer(),settlementYear.id(),new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                    f.school(),0,"Synthetic independent historical settlement book opening")));
            settlementBook=new FeeJournalPgBook(settlementYear.id(),settlementCorrectionPeriod(f,settlementYear.id(),creditedOn),book.clearing(),book.income(),book.receivable());
        }
        return new SettlementPreparationPg(f,flow,settlementBook,bank,expense,payment.paymentId(),List.of(),evidence,zero,false);
    }
    private SettlementPreparationPg settlementPreparationFixture(String basis,int refundCount,BigDecimal serviceCharge,boolean unknownCharge){
        var held=refundCount>0?refundReopenHeld(false):refundReadApproved();var f=held.fixture();var book=refundPostingOriginalBook(held,basis);
        var completions=new java.util.ArrayList<UUID>();var events=new java.util.ArrayList<GatewaySettlementProvider.SettlementEvent>();
        events.add(sourceReviewEvent(f,held.paymentId(),"synthetic_prepared_payment","PAYMENT","CREDIT",null));
        for(int index=0;index<refundCount;index++){
            var selected=held;
            if(index>0){UUID refund=refundSubmit(f,f.admin(),held.paymentId(),refundSubmit(f,UUID.randomUUID(),"50.25")).id();refundDecide(f,f.reviewer(),refund,"APPROVE");
                selected=new RefundReadFixture(f,held.paymentId(),refund);var read=refundSuccessRead(selected,456+index);refundSuccessRetain(selected,read.id());}
            var current=selected;var completed=actorScoped(f.admin(),()->refundPostingService().post(f.admin(),current.refundId(),refundPostingCommand(current,book.year())));
            actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),completed.journalId(),new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted refund settlement source",1)));
            completions.add(completed.id());events.add(refundCandidateEvent(current,"synthetic_prepared_refund_"+index,List.of(current.refundId().toString())));
        }
        BigDecimal net=new BigDecimal("200.00").subtract(new BigDecimal("50.25").multiply(BigDecimal.valueOf(refundCount))).subtract(serviceCharge);
        var zero=BigDecimal.ZERO;var summary=new GatewaySettlementProvider.SettlementSummary("synthetic_source_statement","SUCCESS","synthetic_source_utr",
                null,null,null,null,new BigDecimal("200.00"),unknownCharge?null:serviceCharge,zero,zero,zero,zero,zero,zero,zero,net,null,null);
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(null,events)),true,false,summary);
        UUID bank=owner.queryForObject("SELECT id FROM accounting_accounts WHERE school_id=? AND code='PROOF_BANK'",UUID.class,f.school());
        UUID expense=account(f,"SYN_SETTLEMENT_EXPENSE","EXPENSE","EXPENSE");
        var evidence=actorScoped(f.admin(),()->bankService(new SettlementBankPg(f,flow,bank)).record(f.admin(),flow.request(),new GatewaySettlementBankEvidenceModels.Command(
                f.school(),UUID.randomUUID(),bank,"INR",net.setScale(2).toPlainString(),Instant.now().minusMillis(1),"BANK_STATEMENT_CREDIT","synthetic_source_utr",true,"Synthetic actual settlement bank credit")));
        return new SettlementPreparationPg(f,flow,book,bank,expense,held.paymentId(),List.copyOf(completions),evidence,serviceCharge,unknownCharge);
    }
    private GatewaySettlementPreparationService settlementPreparationService(SettlementPreparationPg test){return settlementPreparationService(test,new SecurityAuditService(runtime,JsonMapper.builder().build(),clock));}
    private GatewaySettlementPreparationService settlementPreparationService(SettlementPreparationPg test,SecurityAuditService audit){
        var named=new NamedParameterJdbcTemplate(runtime);var proofs=transactionalFeeProofs();
        return new GatewaySettlementPreparationService(test.flow().settlements(),new GatewaySettlementSourceReviewService(test.flow().settlements(),named,proofs,accounting),
                new GatewaySettlementRefundCandidateService(test.flow().settlements(),named,proofs),bankService(new SettlementBankPg(test.fixture(),test.flow(),test.bank())),proofs,accounting,named,audit,clock);
    }
    private GatewaySettlementPreparationModels.Context settlementContext(SettlementPreparationPg test,AuthenticatedUser actor){return completedRefundProofRead(test.fixture(),actor,()->settlementPreparationService(test).context(actor,test.fixture().school(),test.flow().request(),0,25));}
    private GatewaySettlementPreparationModels.Command settlementPreparationCommand(SettlementPreparationPg test,UUID previous){
        var context=settlementContext(test,test.fixture().admin());var components=new java.util.ArrayList<GatewaySettlementPreparationModels.ComponentReview>();
        for(String key:GatewaySettlementPreparationTerms.KEYS){boolean service=key.equals("SERVICE_CHARGE");BigDecimal amount=service?test.serviceCharge():BigDecimal.ZERO;
            components.add(new GatewaySettlementPreparationModels.ComponentReview(key,service&&test.unknownCharge()?"CONFIRMED_ZERO":"REPORTED","DEBIT",amount.signum()>0?test.expense():null,
                    amount.setScale(2).toPlainString(),amount.signum()>0?"SYN-REPORTED-COMPONENT":null,"Synthetic explicitly reviewed component "+key));}
        var choices=new java.util.ArrayList<GatewaySettlementPreparationModels.RefundAssociation>();int index=0;
        for(var row:context.rows())if(row.event().reportedType().equals("REFUND"))choices.add(new GatewaySettlementPreparationModels.RefundAssociation(row.sourceReadId(),row.rowIndex(),test.refunds().get(index++),"Synthetic explicit independently reviewed refund choice"));
        return new GatewaySettlementPreparationModels.Command(test.fixture().school(),UUID.randomUUID(),context.contextFingerprint(),test.bankEvidence().id(),test.book().year(),schoolToday(),"INR",true,"SUMMARY",components,choices,previous,true,"Synthetic reviewed complete settlement");
    }
    private static GatewaySettlementPreparationModels.Command settlementCommandCopy(GatewaySettlementPreparationModels.Command command,UUID client,UUID bank,String fingerprint,LocalDate date,
            List<GatewaySettlementPreparationModels.ComponentReview> components,List<GatewaySettlementPreparationModels.RefundAssociation> refunds,UUID previous){
        return new GatewaySettlementPreparationModels.Command(command.schoolId(),client,fingerprint,bank,command.financialYearId(),date,command.summaryCurrencyCode(),command.confirmedSummaryCurrency(),command.componentBasis(),components,refunds,previous,command.confirmedReviewed(),command.reason());
    }
    private GatewaySettlementPreparationModels.Preparation settlementPrepare(SettlementPreparationPg test,AuthenticatedUser actor,GatewaySettlementPreparationModels.Command command){return actorScoped(actor,()->settlementPreparationService(test).prepare(actor,test.flow().request(),command));}
    private com.educore.api.accounting.AccountingMutationResponse settlementDecide(SettlementPreparationPg test,AuthenticatedUser actor,GatewaySettlementPreparationModels.Preparation preparation,String decision){return actorScoped(actor,()->accounting.decideJournal(actor,preparation.journalId(),new com.educore.api.accounting.AccountingController.DecisionRequest(test.fixture().school(),decision,"Synthetic independent settlement journal decision",preparation.rowVersion())));}
    private java.util.Map<String,List<String>> settlementStudentMoney(Fixture f){
        var result=new java.util.LinkedHashMap<>(refundFinancialState(f));result.remove("accounting_journals");result.remove("accounting_journal_lines");result.remove("accounting_journal_history");
        for(String table:List.of("fee_gateway_refund_requests","fee_gateway_refund_allocations","fee_gateway_refund_successes","fee_gateway_refund_postings","fee_accounting_posting_items","fee_accounting_posting_batches"))
            result.put(table,owner.queryForList("SELECT row_to_json(snapshot)::text FROM "+table+" snapshot WHERE school_id=? ORDER BY row_to_json(snapshot)::text",String.class,f.school()));
        result.put("original_fee_journals",owner.queryForList("SELECT row_to_json(journal)::text FROM accounting_journals journal WHERE school_id=? AND source_module='FEES' ORDER BY id",String.class,f.school()));
        return result;
    }
    private static String settlementSqlConstraint(Throwable error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql&&"23514".equals(sql.getSQLState())
                &&cause.getClass().getName().equals("org.postgresql.util.PSQLException"))try{
            Object server=cause.getClass().getMethod("getServerErrorMessage").invoke(cause);return server==null?null:(String)server.getClass().getMethod("getConstraint").invoke(server);
        }catch(ReflectiveOperationException unavailable){return null;}
        return null;
    }
    private void rawSettlementSnapshotRepair(SettlementPreparationPg test,GatewaySettlementPreparationModels.Preparation previous){
        var f=test.fixture();String record=owner.queryForObject("SELECT to_jsonb(preparation)::text FROM fee_gateway_settlement_preparations preparation WHERE id=?",String.class,previous.id());
        actorScoped(f.admin(),()->{
            UUID id=UUID.randomUUID(),client=UUID.randomUUID();
            var journal=accounting.prepareSettlementJournal(f.admin(),new com.educore.api.accounting.AccountingModuleBridge.SettlementJournalRequest(
                    f.school(),test.book().year(),test.book().period(),schoolToday(),"SYN-SNAPSHOT","Synthetic deliberate mismatching snapshot",id,
                    "synthetic-snapshot-forgery-"+id,"Synthetic negative coupling verification",List.of(
                        new com.educore.api.accounting.AccountingModuleBridge.SettlementJournalLine(test.bank(),new BigDecimal("200.00"),BigDecimal.ZERO,"Synthetic actual bank"),
                        new com.educore.api.accounting.AccountingModuleBridge.SettlementJournalLine(test.book().clearing(),BigDecimal.ZERO,new BigDecimal("200.00"),"Synthetic actual source"))));
            runtime.update("""
                    WITH old AS(SELECT ?::jsonb original), changed AS(
                      SELECT original,jsonb_set(original->'evidence_snapshot','{sources,0,amount}','201.00'::jsonb)
                        ||jsonb_build_object('command',(original->'evidence_snapshot'->'command')||jsonb_build_object('previousPreparationId',?::text,'clientRequestId',?::text)) snapshot FROM old)
                    INSERT INTO fee_gateway_settlement_preparations SELECT(jsonb_populate_record(NULL::fee_gateway_settlement_preparations,
                      original||jsonb_build_object('id',?::uuid,'revision',2,'previous_preparation_id',?::uuid,'previous_journal_id',?::uuid,
                        'journal_id',?::uuid,'client_request_id',?::uuid,'evidence_snapshot',snapshot,
                        'evidence_sha256',encode(sha256(convert_to(snapshot::text,'UTF8')),'hex')))).* FROM changed
                    """,record,previous.id().toString(),client.toString(),id,previous.id(),previous.journalId(),journal.id(),client);
            runtime.update("""
                    INSERT INTO fee_gateway_settlement_source_rows(id,organisation_id,school_id,preparation_id,source_read_id,row_index,source_id,association_reason)
                    SELECT gen_random_uuid(),organisation_id,school_id,?,source_read_id,row_index,source_id,association_reason
                    FROM fee_gateway_settlement_source_rows WHERE preparation_id=? AND organisation_id=? AND school_id=?
                    """,id,previous.id(),f.org(),f.school());
            runtime.update("""
                    INSERT INTO fee_gateway_settlement_components(id,organisation_id,school_id,preparation_id,component_key,component_kind,component_basis,direction,
                        account_id,amount,currency_code,evidence_reference,reason,raw_field_facts)
                    SELECT gen_random_uuid(),organisation_id,school_id,?,component_key,component_kind,component_basis,direction,
                        account_id,amount,currency_code,evidence_reference,reason,raw_field_facts
                    FROM fee_gateway_settlement_components WHERE preparation_id=? AND organisation_id=? AND school_id=?
                    """,id,previous.id(),f.org(),f.school());
            // Commit must reject the recomputed-digest snapshot even though all
            // actual lines, sources and totals independently remain balanced.
            return null;
        });
    }
    private record SettlementPreparationPg(Fixture fixture,SourceReviewPg flow,FeeJournalPgBook book,UUID bank,UUID expense,UUID payment,List<UUID> refunds,
            GatewaySettlementBankEvidenceModels.Evidence bankEvidence,BigDecimal serviceCharge,boolean unknownCharge){}

    @Test void settlementBankPopulatedV082UpgradePreservesAllExistingRowsAndAddsNoAttestation(){
        assertThat(bankForwardPreserved).isTrue();
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='083'",Boolean.class)).isTrue();
        ProductionDatabaseSecurity.verify(runtime);
    }
    @Test void settlementBankRecordsExactEvidenceOnceWithoutMovingMoneyAndRetainsReviewedSummary(){
        for(boolean summaryReview:List.of(false,true)){
            var test=settlementBankFixture(summaryReview,true);var f=test.fixture();var before=settlementBankProtectedState(f);
            var command=settlementBankCommand(test,"syn - bank/ 001");
            var result=bankRecord(test,f.admin(),command);
            assertThat(result.status()).isEqualTo("BANK_EVIDENCE_RECORDED");assertThat(result.canReject()).isFalse();
            assertThat(result.actualCreditedAt()).isEqualTo(command.actualCreditedAt().toString());
            assertThat(result.creditedOn()).isEqualTo(LocalDate.ofInstant(command.actualCreditedAt(),java.time.ZoneId.of("Asia/Kolkata")));
            assertThat(result.reviewReasons()).contains("NOT_RECONCILED","SUMMARY_CURRENCY_UNCONFIRMED","SOURCE_RECONCILIATION_PENDING","SUMMARY_AMOUNT_UNKNOWN");
            assertThat(bankRecord(test,f.admin(),command)).isEqualTo(result);
            var context=actorScoped(f.reviewer(),()->bankService(test).context(f.reviewer(),f.school(),test.flow().request()));
            assertThat(context.evidence()).hasSize(1);assertThat(context.evidence().getFirst().canReject()).isTrue();
            assertThat(context.bankAccounts()).extracting(GatewaySettlementBankEvidenceModels.BankAccount::id).contains(test.bank());
            assertThat(owner.queryForObject("SELECT actual_credited_at FROM fee_gateway_settlement_bank_evidence WHERE id=?",java.time.OffsetDateTime.class,result.id()).toInstant()).isEqualTo(GatewayExactInstant.floor(command.actualCreditedAt()));
            assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action='finance.gateway.settlement.bank-evidence-recorded'",Integer.class,f.school())).isEqualTo(1);
            var changed=new GatewaySettlementBankEvidenceModels.Command(f.school(),command.clientRequestId(),test.bank(),"INR","12.35",command.actualCreditedAt(),command.verificationKind(),command.bankCreditReference(),true,command.reason());
            assertThatThrownBy(()->bankRecord(test,f.admin(),changed)).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_BANK_REQUEST_REUSED"));
            assertThat(settlementBankProtectedState(f)).isEqualTo(before);
        }
    }
    @Test void settlementBankHistoricalReplaySurvivesInactiveBankAndChangedSchoolZoneButNewCaptureDoesNot(){
        var test=settlementBankFixture(false,true);var f=test.fixture();var command=settlementBankCommand(test,"SYN-HISTORIC");
        var saved=bankRecord(test,f.admin(),command);var money=refundFinancialState(f);
        owner.update("UPDATE accounting_accounts SET status='INACTIVE' WHERE id=?",test.bank());
        owner.update("UPDATE schools SET time_zone='UTC' WHERE id=?",f.school());
        assertThat(bankRecord(test,f.admin(),command)).isEqualTo(saved);
        var context=actorScoped(f.admin(),()->bankService(test).context(f.admin(),f.school(),test.flow().request()));
        assertThat(context.canRecord()).isFalse();assertThat(context.bankAccounts()).isEmpty();assertThat(context.schoolTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(context.evidence()).containsExactly(saved);
        assertThatThrownBy(()->bankRecord(test,f.admin(),settlementBankCommand(test,"SYN-HISTORIC-NEW"))).hasMessageContaining("timezone changed");
        assertThat(refundFinancialState(f)).isEqualTo(money);
    }
    @Test void settlementBankIndependentRejectionRetainsHistoryReleasesOnlyItsReservationAndCannotReactivate(){
        var test=settlementBankFixture(false,true);var f=test.fixture();var command=settlementBankCommand(test,"SYN-MISTAKEN");
        var saved=bankRecord(test,f.admin(),command);var raw=owner.queryForObject("SELECT row_to_json(e)::text FROM fee_gateway_settlement_bank_evidence e WHERE id=?",String.class,saved.id());
        var before=settlementBankProtectedState(f);var rejection=new GatewaySettlementBankEvidenceModels.RejectionCommand(f.school(),UUID.randomUUID(),"Synthetic independently reviewed mistaken credit",true);
        assertThatThrownBy(()->actorScoped(f.admin(),()->bankService(test).reject(f.admin(),test.flow().request(),saved.id(),rejection))).hasMessageContaining("different authorized person");
        // A second membership is not a different human approver.
        UUID extra=UUID.randomUUID(),campus=owner.queryForObject("SELECT id FROM campuses WHERE school_id=?",UUID.class,f.school());
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())",extra,f.admin().userId(),f.org(),f.school(),campus);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001')",extra);
        var sameHuman=actor(f.admin().userId(),extra,f.org());
        assertThatThrownBy(()->actorScoped(sameHuman,()->bankService(test).reject(sameHuman,test.flow().request(),saved.id(),rejection)))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_BANK_INDEPENDENT_REJECTION_REQUIRED"));
        var rejected=actorScoped(f.reviewer(),()->bankService(test).reject(f.reviewer(),test.flow().request(),saved.id(),rejection));
        assertThat(rejected.status()).isEqualTo("REJECTED");assertThat(rejected.canReject()).isFalse();assertThat(rejected.rejection()).isNotNull();
        assertThat(actorScoped(f.reviewer(),()->bankService(test).reject(f.reviewer(),test.flow().request(),saved.id(),rejection))).isEqualTo(rejected);
        assertThat(bankRecord(test,f.admin(),command).status()).isEqualTo("REJECTED");
        assertThat(owner.queryForObject("SELECT row_to_json(e)::text FROM fee_gateway_settlement_bank_evidence e WHERE id=?",String.class,saved.id())).isEqualTo(raw);
        var corrected=bankRecord(test,f.admin(),settlementBankCommand(test,"syn - mistaken"));assertThat(corrected.id()).isNotEqualTo(saved.id());
        assertThat(settlementBankProtectedState(f)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_bank_evidence_rejections WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
    }
    @Test void settlementBankAndManualUpiBankShareReferenceHistoryIncludingReversedReceipts(){
        var test=settlementBankFixture(false,true);var f=test.fixture();var command=settlementBankCommand(test,"SYN-RESERVED");
        var evidence=bankRecord(test,f.admin(),command);var before=refundFinancialState(f);
        for(String method:List.of("BANK_TRANSFER","SCHOOL_UPI"))assertThatThrownBy(()->collect(f,"synthetic-reserved-"+method,
                manualPayment(f,method,"syn - reserved","10",manualInstant(),null,new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"syn - reserved"),null)))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->assertThat(error.code()).isEqualTo("MANUAL_BANK_CREDIT_RESERVED_FOR_SETTLEMENT"));
        assertThat(refundFinancialState(f)).isEqualTo(before);
        actorScoped(f.reviewer(),()->bankService(test).reject(f.reviewer(),test.flow().request(),evidence.id(),new GatewaySettlementBankEvidenceModels.RejectionCommand(f.school(),UUID.randomUUID(),"Synthetic wrong bank entry independently confirmed",true)));
        var receipt=collect(f,"synthetic-released-credit",manualPayment(f,"BANK_TRANSFER","SYN-RESERVED","10",manualInstant(),null,new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"SYN-RESERVED"),null));
        actorScoped(f.admin(),()->finance.reversePayment(f.admin(),receipt.id(),new FinanceController.PaymentReversalRequest(f.school(),0,"Synthetic controlled receipt reversal")));
        assertThatThrownBy(()->bankRecord(test,f.admin(),settlementBankCommand(test,"syn - reserved"))).hasMessageContaining("including reversed");
        assertThat(bankRecord(test,f.admin(),command).status()).isEqualTo("REJECTED");
    }
    @Test void settlementBankRejectsForeignGuardianRevokedIncompleteAndInvalidEvidence(){
        var test=settlementBankFixture(false,true);var f=test.fixture();var foreign=fixture(true);var command=settlementBankCommand(test,"SYN-PRIVATE");var before=feeJournalReadProtectedState(f);
        for(var actor:List.of(f.parent(),foreign.admin())){
            assertThatThrownBy(()->bankRecord(test,actor,command)).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(()->actorScoped(actor,()->bankService(test).context(actor,f.school(),test.flow().request()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        var incomplete=settlementBankFixture(false,false);
        assertThatThrownBy(()->bankRecord(incomplete,incomplete.fixture().admin(),settlementBankCommand(incomplete,"SYN-INCOMPLETE"))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->bankRecord(test,f.admin(),new GatewaySettlementBankEvidenceModels.Command(f.school(),UUID.randomUUID(),test.bank(),"INR","1.001",manualInstant(),"BANK_APP_CREDIT","SYN-INVALID",true,"Synthetic invalid paise"))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->bankRecord(test,f.admin(),new GatewaySettlementBankEvidenceModels.Command(f.school(),UUID.randomUUID(),test.bank(),"INR","1.00",Instant.now().plusSeconds(3600),"BANK_APP_CREDIT","SYN-FUTURE",true,"Synthetic future evidence"))).hasMessageContaining("future");
        owner.update("UPDATE memberships SET status='SUSPENDED' WHERE id=?",member(f.admin()));
        assertThatThrownBy(()->bankRecord(test,f.admin(),command)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
    }
    @Test void settlementBankDatabaseProtectsScopedExactPaiseAndImmutableEvidence(){
        var test=settlementBankFixture(false,true);var f=test.fixture();var saved=bankRecord(test,f.admin(),settlementBankCommand(test,"SYN-DB"));
        for(String changes:List.of("{\"credited_amount\":1.001}","{\"source_summary_row_index\":1}","{\"school_time_zone\":\"UTC\"}","{\"confirmed_received\":false}","{\"actual_credited_at_exact\":\"2026-01-01T00:00:00Z\"}"))
            assertThatThrownBy(()->actorScoped(f.admin(),()->rawBankClone(saved.id(),changes))).isInstanceOf(DataAccessException.class);
        assertThat(actorScoped(f.admin(),()->runtime.update("UPDATE fee_gateway_settlement_bank_evidence SET reason='Synthetic forbidden edit' WHERE id=?",saved.id()))).isZero();
        assertThatThrownBy(()->actorScoped(f.admin(),()->runtime.update("DELETE FROM fee_gateway_settlement_bank_evidence WHERE id=?",saved.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("UPDATE fee_gateway_settlement_bank_evidence SET reason='Synthetic forbidden privileged edit' WHERE id=?",saved.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("DELETE FROM fee_gateway_settlement_bank_evidence WHERE id=?",saved.id())).isInstanceOf(DataAccessException.class);
        var foreign=fixture(true);assertThat(actorScoped(foreign.admin(),()->runtime.queryForObject("SELECT count(*) FROM fee_gateway_settlement_bank_evidence WHERE id=?",Integer.class,saved.id()))).isZero();
        assertThatThrownBy(()->actorScoped(foreign.admin(),()->rawBankClone(saved.id(),"{}"))).isInstanceOf(DataAccessException.class);
        var stale=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));stale.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(f.admin().userId(),f.org())){
            assertThatThrownBy(()->stale.execute(status->bankService(test).record(f.admin(),test.flow().request(),settlementBankCommand(test,"SYN-STALE")))).hasMessageContaining("read-committed");
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_bank_evidence WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
    }
    @Test void settlementBankOppositeOrderLockWaitsPreventDoubleAdmissionWithManualCollection() throws Exception{
        for(boolean bankFirst:List.of(true,false)){
            var test=settlementBankFixture(false,true);var f=test.fixture();var command=settlementBankCommand(test,"SYN-RACE");
            var manual=manualPayment(f,"SCHOOL_UPI","SYN-RACE","10",manualInstant(),null,new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"SYN-RACE"),null);
            var contender=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
            try(var executor=Executors.newSingleThreadExecutor()){
                actorScoped(f.admin(),()->{
                    runtime.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?::text||':finance',0))) locked",Integer.class,f.school().toString());
                    if(bankFirst)bankService(test).record(f.admin(),test.flow().request(),command);else finance.recordPayment(f.admin(),"synthetic-manual-winner",manual);
                    contender.set(executor.submit(()->{
                        try{if(bankFirst)collect(f,"synthetic-manual-loser",manual);else bankRecord(test,f.admin(),command);return true;}
                        catch(com.educore.api.common.api.ApiException denied){return false;}
                    }));
                    awaitBankFinanceFence();assertThat(contender.get().isDone()).isFalse();return null;
                });
                assertThat(contender.get().get(20,TimeUnit.SECONDS)).isFalse();
            }
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_bank_evidence WHERE school_id=?",Integer.class,f.school())).isEqualTo(bankFirst?1:0);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?",Integer.class,f.school())).isEqualTo(bankFirst?0:1);
        }
    }
    private SettlementBankPg settlementBankFixture(boolean summaryReview,boolean complete){
        var f=fixture(true);feeJournalBook(f,"CASH",schoolToday());
        UUID bank=owner.queryForObject("SELECT id FROM accounting_accounts WHERE school_id=? AND code='PROOF_BANK'",UUID.class,f.school());
        var flow=sourceReviewFlow(f,List.of(new GatewaySettlementProvider.SettlementEventsPage(complete?null:"synthetic_incomplete",List.of())),complete,summaryReview);
        return new SettlementBankPg(f,flow,bank);
    }
    private GatewaySettlementBankEvidenceService bankService(SettlementBankPg test){return new GatewaySettlementBankEvidenceService(test.flow().settlements(),new NamedParameterJdbcTemplate(runtime),accounting,new SecurityAuditService(runtime,JsonMapper.builder().build(),clock));}
    private java.util.Map<String,List<String>> settlementBankProtectedState(Fixture f){
        var state=new java.util.LinkedHashMap<>(feeJournalReadProtectedState(f));
        // Bank evidence must append its own audit events. All other existing audit
        // history and every financial/source table must remain byte-for-byte intact.
        state.put("audit_events",owner.queryForList("SELECT row_to_json(snapshot)::text FROM audit_events snapshot WHERE school_id=? AND action NOT IN ('finance.gateway.settlement.bank-evidence-recorded','finance.gateway.settlement.bank-evidence-rejected') ORDER BY row_to_json(snapshot)::text",String.class,f.school()));
        return state;
    }
    private GatewaySettlementBankEvidenceModels.Command settlementBankCommand(SettlementBankPg test,String reference){return new GatewaySettlementBankEvidenceModels.Command(test.fixture().school(),UUID.randomUUID(),test.bank(),"INR","12.34",Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(999999999),"BANK_APP_CREDIT",reference,true,"Synthetic actual credited bank entry");}
    private GatewaySettlementBankEvidenceModels.Evidence bankRecord(SettlementBankPg test,AuthenticatedUser actor,GatewaySettlementBankEvidenceModels.Command command){return actorScoped(actor,()->bankService(test).record(actor,test.flow().request(),command));}
    private int rawBankClone(UUID id,String changes){
        String original=owner.queryForObject("SELECT to_jsonb(evidence)::text FROM fee_gateway_settlement_bank_evidence evidence WHERE id=?",String.class,id);
        return runtime.update("""
            INSERT INTO fee_gateway_settlement_bank_evidence SELECT (jsonb_populate_record(NULL::fee_gateway_settlement_bank_evidence,
              ?::jsonb||jsonb_build_object('id',?::uuid,'client_request_id',?::uuid,'bank_credit_reference',?::text,'normalized_bank_reference',?::text)||?::jsonb)).*
            """,original,UUID.randomUUID(),UUID.randomUUID(),"SYN-RAW-NEW","SYN-RAW-NEW",changes);}
    private void awaitBankFinanceFence(){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        do{
            if(Boolean.TRUE.equals(control.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=? AND usename=? AND wait_event_type='Lock' AND state='active' AND query LIKE '%pg_advisory_xact_lock%')",Boolean.class,database,runtimeLogin)))return;
            try{Thread.sleep(10);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic finance lock wait interrupted",failure);}
        }while(System.nanoTime()<deadline);
        throw new IllegalStateException("Actual contender did not reach shared finance fence");
    }
    private record SettlementBankPg(Fixture fixture,SourceReviewPg flow,UUID bank){}

    private GatewayOriginalJournalPg gatewayOriginalJournalFixture(boolean closed){
        var refund=closed?refundReopenHeld(true):refundReadApproved();var f=refund.fixture();var book=refundPostingOriginalBook(refund,"CASH");
        var proof=originalPaymentProof(f,f.admin(),refund.paymentId());
        return new GatewayOriginalJournalPg(refund,book,proof.postingBatchId(),proof.accountingJournalId());
    }
    private com.educore.api.accounting.AccountingModuleBridge.FeeJournalEvidence gatewayOriginalJournalRead(GatewayOriginalJournalPg test){
        var f=test.refund().fixture();return familyGatewayBackground(f,()->accounting.gatewayOriginalPaymentJournalEvidence(f.org(),f.school(),test.refund().paymentId(),test.batch(),test.journal()));
    }
    private com.educore.api.accounting.AccountingMutationResponse gatewayOriginalJournalRequestReversal(GatewayOriginalJournalPg test){
        var f=test.refund().fixture();return actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),test.journal(),
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),test.book().period(),feeJournalReceiptDate(f,test.refund().paymentId()),"Synthetic pending original-journal reversal")));
    }
    private void gatewayOriginalJournalAwaitFence(){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{
            if(Boolean.TRUE.equals(control.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=? AND usename=? AND wait_event_type='Lock' AND state='active' AND position('gateway-original-journal-fence' IN query)>0)",Boolean.class,database,runtimeLogin)))return;
            try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic accounting fence observation interrupted",interrupted);}
        }while(System.nanoTime()<until);
        throw new IllegalStateException("Actual background original-journal read did not reach the accounting fence");
    }
    private record GatewayOriginalJournalPg(RefundReadFixture refund,FeeJournalPgBook book,UUID batch,UUID journal){}

    @Test void gatewayOriginalSourceWaitsForAccountingFenceThenRefusesTheCommittedPendingReversal() throws Exception {
        var test=gatewayOriginalJournalFixture(false);var f=test.refund().fixture();var money=refundFinancialState(f);
        var outcome=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Object>>();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            actorScoped(f.admin(),()->{
                runtime.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(row,index)->true,"accounting:school:"+f.school());
                outcome.set(executor.submit(()->{try{return gatewayOriginalSourceProof(f,test.refund().paymentId());}catch(RuntimeException failure){return failure;}}));
                gatewayOriginalJournalAwaitFence();
                return accounting.requestReversal(f.admin(),test.journal(),new com.educore.api.accounting.AccountingController.ReversalRequest(
                        f.school(),test.book().period(),feeJournalReceiptDate(f,test.refund().paymentId()),"Synthetic reversal before complete source proof"));
            });
            assertThat(outcome.get().get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                    error->assertThat(error.code()).isEqualTo("FEE_REFUND_ACCOUNTING_UNAVAILABLE"));
        }
        assertThat(refundFinancialState(f)).isEqualTo(money);
    }

    @Test void gatewayOriginalSourceRefusesAnActualStaleRepeatableReadSnapshotBeforeReadingMoney() throws Exception {
        var test=gatewayOriginalJournalFixture(false);var f=test.refund().fixture();var money=refundFinancialState(f);
        var snapshotReady=new java.util.concurrent.CountDownLatch(1);var proceed=new java.util.concurrent.CountDownLatch(1);
        var staleCount=new java.util.concurrent.atomic.AtomicInteger(-1);
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var result=executor.submit(()->{
                try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,f.org())){
                    var stale=new TransactionTemplate(transactions.getTransactionManager());
                    stale.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);stale.setTimeout(20);
                    try{return stale.execute(status->{
                        assertThat(runtime.queryForObject("SELECT count(*) FROM accounting_reversal_requests WHERE accounting_journal_id=? AND status='PENDING'",Integer.class,test.journal())).isZero();
                        snapshotReady.countDown();
                        try{assertThat(proceed.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic source snapshot test interrupted",interrupted);}
                        staleCount.set(runtime.queryForObject("SELECT count(*) FROM accounting_reversal_requests WHERE accounting_journal_id=? AND status='PENDING'",Integer.class,test.journal()));
                        return feeAccounting.verifyGatewayOriginalPaymentPosting(f.org(),f.school(),test.refund().paymentId());
                    });}catch(RuntimeException failure){return failure;}
                }
            });
            try{assertThat(snapshotReady.await(5,TimeUnit.SECONDS)).isTrue();gatewayOriginalJournalRequestReversal(test);}
            finally{proceed.countDown();}
            assertThat(result.get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                    error->assertThat(error.code()).isEqualTo("FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED"));
        }
        assertThat(staleCount.get()).isZero();assertThat(owner.queryForObject("SELECT count(*) FROM accounting_reversal_requests WHERE accounting_journal_id=? AND status='PENDING'",Integer.class,test.journal())).isEqualTo(1);
        assertThat(refundFinancialState(f)).isEqualTo(money);
    }

    private FeeAccountingService.OriginalPaymentPostingProof gatewayOriginalSourceProof(Fixture f,UUID payment){
        return familyGatewayBackground(f,()->feeAccounting.verifyGatewayOriginalPaymentPosting(f.org(),f.school(),payment));
    }

    @Test void originalPaymentProofRequiresTheWholeIndependentlyPostedCashBatchWithoutChangingMoney() {
        Fixture f=fixture(true),foreign=fixture(true);
        Instant paidAt=familyGatewayPastPaymentTime();
        var first=feeJournalPostedPayment(f,"111.11",paidAt);
        var second=feeJournalPostedPayment(f,"222.22",paidAt);
        LocalDate date=feeJournalReceiptDate(f,first.paymentId());
        var manual=collect(f,"synthetic-original-batch-manual-"+UUID.randomUUID(),new FinanceController.PaymentRequest(
                f.school(),f.account(),"CASH",null,new BigDecimal("50.00"),date,"Synthetic original-batch manual receipt",
                List.of(new FinanceController.PaymentAllocation(f.charge(),new BigDecimal("50.00")))));
        var book=feeJournalBook(f,"CASH",date);
        var batch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),"synthetic-original-cash-"+UUID.randomUUID(),
                new FeeAccountingController.PostingRequest(f.school(),book.year(),date,"PAYMENT",200,"Synthetic complete original receipt batch")));
        UUID journal=feeJournalBatchJournal(f,batch.id());
        var submittedState=feeJournalReadProtectedState(f);
        assertOriginalProofDenied(()->originalPaymentProof(f,f.admin(),first.paymentId()),"FEE_ORIGINAL_PAYMENT_PROOF_INVALID");
        assertThatThrownBy(()->gatewayOriginalSourceProof(f,first.paymentId())).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(submittedState);
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),journal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independently posted original batch",1)));
        assertThat(owner.queryForObject("SELECT source_count FROM fee_accounting_posting_batches WHERE id=?",Integer.class,batch.id())).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT source_amount FROM fee_accounting_posting_batches WHERE id=?",BigDecimal.class,batch.id())).isEqualByComparingTo("383.33");
        // A later operational reversal does not rewrite this
        // receipt's original contribution to the already posted PAYMENT journal.
        actorScoped(f.admin(),()->finance.reversePayment(f.admin(),manual.id(),
                new FinanceController.PaymentReversalRequest(f.school(),0,"Synthetic later manual receipt reversal")));
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?",String.class,manual.id())).isEqualTo("REVERSED");

        var before=feeJournalReadProtectedState(f);
        var proof=originalPaymentProof(f,f.admin(),first.paymentId());
        var other=originalPaymentProof(f,f.admin(),second.paymentId());
        assertThat(gatewayOriginalSourceProof(f,first.paymentId())).isEqualTo(proof);
        assertThat(gatewayOriginalSourceProof(f,second.paymentId())).isEqualTo(other);
        assertThat(originalPaymentProof(f,f.admin(),first.paymentId())).isEqualTo(proof);
        assertThat(proof.paymentId()).isEqualTo(first.paymentId());
        assertThat(proof.postingBatchId()).isEqualTo(batch.id());
        assertThat(proof.accountingJournalId()).isEqualTo(journal);
        assertThat(proof.originalFinancialYearId()).isEqualTo(book.year());
        assertThat(proof.originalAccountingPeriodId()).isEqualTo(book.period());
        assertThat(proof.originalPostingDate()).isEqualTo(date);
        assertThat(proof.accountingBasis()).isEqualTo("CASH");
        assertThat(proof.currencyCode()).isEqualTo("INR");
        assertThat(proof.depositAccountId()).isEqualTo(book.clearing());
        assertThat(proof.receivableAccountId()).isNull();
        assertThat(proof.incomeAccounts()).containsOnlyKeys(originalProofHead(f));
        assertThat(proof.incomeAccounts().get(originalProofHead(f))).isEqualTo(book.income());
        assertOriginalProofAllocation(f,proof,"111.11");
        assertOriginalProofAllocation(f,other,"222.22");
        assertThat(other.postingBatchId()).isEqualTo(proof.postingBatchId());
        assertThat(other.batchEvidenceSha256()).isEqualTo(proof.batchEvidenceSha256());
        assertThat(other.postingItemId()).isNotEqualTo(proof.postingItemId());
        assertThat(proof.sourceSha256()).isEqualTo(owner.queryForObject("SELECT source_sha256 FROM fee_accounting_posting_items WHERE id=?",String.class,proof.postingItemId()));
        assertThat(proof.batchEvidenceSha256()).isEqualTo(owner.queryForObject("SELECT evidence_sha256 FROM fee_accounting_posting_batches WHERE id=?",String.class,batch.id()));
        assertOriginalProofDenied(()->originalPaymentProof(f,f.parent(),first.paymentId()),"FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED");
        assertOriginalProofDenied(()->actorScoped(foreign.admin(),()->feeAccounting.verifyOriginalPaymentPosting(foreign.admin(),f.school(),first.paymentId())),"FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED");
        assertOriginalProofDenied(()->originalPaymentProof(f,f.admin(),UUID.randomUUID()),"FEE_ORIGINAL_PAYMENT_PROOF_INVALID");
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("666.67");
    }

    @Test void originalPaymentProofPreservesAccrualSourceMappingAfterIndependentSupersession() {
        Fixture f=fixture(true);
        Instant paidAt=familyGatewayPastPaymentTime();
        var payment=feeJournalPostedPayment(f,"125.50",paidAt);
        LocalDate date=feeJournalReceiptDate(f,payment.paymentId());
        var book=feeJournalBook(f,"ACCRUAL",date);
        var batch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),"synthetic-original-accrual-"+UUID.randomUUID(),
                new FeeAccountingController.PostingRequest(f.school(),book.year(),date,"PAYMENT",200,"Synthetic original accrual receipt batch")));
        UUID journal=feeJournalBatchJournal(f,batch.id());
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),journal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent original accrual posting",1)));
        var original=originalPaymentProof(f,f.admin(),payment.paymentId());
        assertThat(original.accountingBasis()).isEqualTo("ACCRUAL");
        assertThat(original.receivableAccountId()).isEqualTo(book.receivable());
        assertOriginalProofAllocation(f,original,"125.50");

        UUID replacementIncome=account(f,"PROOF_NEW_INCOME","INCOME","INCOME"),replacementClearing=account(f,"PROOF_NEW_CLEARING","ASSET","OTHER");
        UUID cash=account(f,"PROOF_NEW_CASH","ASSET","CASH"),bank=account(f,"PROOF_NEW_BANK","ASSET","BANK");
        UUID receivable=account(f,"PROOF_NEW_RECEIVABLE","ASSET","RECEIVABLE"),concession=account(f,"PROOF_NEW_CONCESSION","EXPENSE","EXPENSE");
        var replacement=actorScoped(f.admin(),()->feeAccounting.createConfiguration(f.admin(),new FeeAccountingController.ConfigurationRequest(
                f.school(),book.year(),original.configurationId(),2,date.plusDays(1),receivable,concession,
                List.of(new FeeAccountingController.HeadMappingRequest(originalProofHead(f),replacementIncome)),
                List.of(new FeeAccountingController.TenderMappingRequest("CASH",cash),new FeeAccountingController.TenderMappingRequest("BANK_TRANSFER",bank),
                        new FeeAccountingController.TenderMappingRequest("CHEQUE",bank),new FeeAccountingController.TenderMappingRequest("SCHOOL_UPI",bank),
                        new FeeAccountingController.TenderMappingRequest("ONLINE_PROVIDER",replacementClearing)),null,"Synthetic later original-mapping preservation")));
        var submitted=actorScoped(f.admin(),()->feeAccounting.submitConfiguration(f.admin(),replacement.id(),
                new FeeAccountingController.VersionedReasonRequest(f.school(),0,"Synthetic replacement submission")));
        actorScoped(f.reviewer(),()->feeAccounting.decideConfiguration(f.reviewer(),replacement.id(),
                new FeeAccountingController.ConfigurationDecisionRequest(f.school(),"APPROVE",submitted.rowVersion(),"Synthetic independent replacement approval")));
        assertThat(owner.queryForObject("SELECT status FROM fee_accounting_configurations WHERE id=?",String.class,original.configurationId())).isEqualTo("SUPERSEDED");

        var before=feeJournalReadProtectedState(f);
        var retained=originalPaymentProof(f,f.admin(),payment.paymentId());
        assertThat(gatewayOriginalSourceProof(f,payment.paymentId())).isEqualTo(retained);
        assertThat(retained).isEqualTo(original);
        assertThat(retained.depositAccountId()).isEqualTo(book.clearing()).isNotEqualTo(replacementClearing);
        assertThat(retained.receivableAccountId()).isEqualTo(book.receivable()).isNotEqualTo(receivable);
        assertThat(retained.incomeAccounts().get(originalProofHead(f))).isEqualTo(book.income()).isNotEqualTo(replacementIncome);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("874.50");
    }

    @Test void originalPaymentProofRejectsActualCommonJournalReversalWithoutReversingTheFeeReceipt() {
        Fixture f=fixture(true);
        var payment=feeJournalPostedPayment(f,"84.25",familyGatewayPastPaymentTime());
        var unprepared=feeJournalReadProtectedState(f);
        assertOriginalProofDenied(()->originalPaymentProof(f,f.admin(),payment.paymentId()),"FEE_ORIGINAL_PAYMENT_PROOF_INVALID");
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(unprepared);
        LocalDate date=feeJournalReceiptDate(f,payment.paymentId());
        var book=feeJournalBook(f,"CASH",date);
        var batch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),"synthetic-original-reversal-"+UUID.randomUUID(),
                new FeeAccountingController.PostingRequest(f.school(),book.year(),date,"PAYMENT",200,"Synthetic source for original reversal refusal")));
        UUID journal=feeJournalBatchJournal(f,batch.id());
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),journal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent source posting",1)));
        assertThat(originalPaymentProof(f,f.admin(),payment.paymentId()).accountingJournalId()).isEqualTo(journal);
        var requested=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),journal,
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),book.period(),date,"Synthetic common-journal correction, not provider money")));
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),requested.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic independent common-journal reversal",0)));
        var reversed=feeJournalReadProtectedState(f);
        assertOriginalProofDenied(()->originalPaymentProof(f,f.admin(),payment.paymentId()),"FEE_ORIGINAL_PAYMENT_PROOF_INVALID");
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(reversed);
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?",String.class,payment.paymentId())).isEqualTo("POSTED");
        assertThat(familyFees(f).balance()).isEqualByComparingTo("915.75");
    }

    private FeeAccountingService.OriginalPaymentPostingProof originalPaymentProof(Fixture f,AuthenticatedUser actor,UUID payment) {
        return actorScoped(actor,()->feeAccounting.verifyOriginalPaymentPosting(actor,f.school(),payment));
    }
    private static void assertOriginalProofDenied(java.util.function.Supplier<?> action,String code) {
        assertThatThrownBy(action::get).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                error->assertThat(error.code()).isEqualTo(code));
    }
    private UUID originalProofHead(Fixture f) {
        return owner.queryForObject("SELECT item.fee_head_id FROM learner_fee_charges charge JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id AND item.organisation_id=charge.organisation_id AND item.school_id=charge.school_id WHERE charge.id=? AND charge.organisation_id=? AND charge.school_id=?",UUID.class,f.charge(),f.org(),f.school());
    }
    private void assertOriginalProofAllocation(Fixture f,FeeAccountingService.OriginalPaymentPostingProof proof,String amount) {
        assertThat(proof.allocations()).hasSize(1);
        var allocation=proof.allocations().getFirst();
        assertThat(allocation.chargeId()).isEqualTo(f.charge());
        assertThat(allocation.feeHeadId()).isEqualTo(originalProofHead(f));
        assertThat(allocation.amount()).isEqualByComparingTo(amount);
        assertThat(allocation.allocationId()).isEqualTo(owner.queryForObject("SELECT id FROM fee_payment_allocations WHERE fee_payment_id=? AND learner_fee_charge_id=?",UUID.class,proof.paymentId(),f.charge()));
        assertThat(proof.feeLedgerEntryId()).isEqualTo(owner.queryForObject("SELECT ledger_entry_id FROM fee_payments WHERE id=?",UUID.class,proof.paymentId()));
    }

    @Test void feeJournalCashWholePaymentBatchPreservesTwoReceiptsAcrossRealIndependentPosting() {
        Fixture f=fixture(true);
        Instant paidAt=familyGatewayPastPaymentTime();
        var first=feeJournalPostedPayment(f,"111.11",paidAt);
        var second=feeJournalPostedPayment(f,"222.22",paidAt);
        LocalDate businessDate=feeJournalReceiptDate(f,first.paymentId());
        assertThat(feeJournalReceiptDate(f,second.paymentId())).isEqualTo(businessDate);
        var book=feeJournalBook(f,"CASH",businessDate);
        var batch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),
                "synthetic-fee-read-cash-"+UUID.randomUUID(),new FeeAccountingController.PostingRequest(
                        f.school(),book.year(),businessDate,"PAYMENT",200,"Synthetic complete cash-basis receipt batch")));
        UUID journal=feeJournalBatchJournal(f,batch.id());
        assertThat(owner.queryForObject("SELECT source_count FROM fee_accounting_posting_batches WHERE id=?",Integer.class,batch.id())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT source_amount FROM fee_accounting_posting_batches WHERE id=?",BigDecimal.class,batch.id())).isEqualByComparingTo("333.33");
        assertThat(owner.queryForList("SELECT (source_snapshot->>'sourceId')::uuid FROM fee_accounting_posting_items WHERE posting_batch_id=? AND status='ACTIVE'",UUID.class,batch.id()))
                .containsExactlyInAnyOrder(first.paymentId(),second.paymentId());

        var beforeSubmittedRead=feeJournalReadProtectedState(f);
        var submitted=actorScoped(f.admin(),()->accounting.feeJournalEvidence(f.admin(),f.school(),List.of(journal))).get(journal);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(beforeSubmittedRead);
        assertThat(submitted.id()).isEqualTo(journal);
        assertThat(submitted.sourceId()).isEqualTo(batch.id()).isNotIn(first.paymentId(),second.paymentId());
        assertThat(submitted.financialYearId()).isEqualTo(book.year());
        assertThat(submitted.accountingPeriodId()).isEqualTo(book.period());
        assertThat(submitted.postingDate()).isEqualTo(businessDate);
        assertThat(submitted.accountingBasis()).isEqualTo("CASH");
        assertThat(submitted.currencyCode()).isEqualTo("INR");
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.financialYearStatus()).isEqualTo("OPEN");
        assertThat(submitted.accountingPeriodStatus()).isEqualTo("OPEN");
        assertThat(submitted.reversalId()).isNull();
        assertThat(submitted.reversalStatus()).isNull();
        assertThat(submitted.reversalLines()).isEmpty();
        assertThat(submitted.lines()).hasSize(2);
        feeJournalAssertLine(submitted.lines(),book.clearing(),null,"333.33","0.00","ASSET","OTHER");
        feeJournalAssertLine(submitted.lines(),book.income(),null,"0.00","333.33","INCOME","INCOME");

        // The normal maker/checker transition, not an owner UPDATE of journal status.
        assertThatThrownBy(()->actorScoped(f.admin(),()->accounting.decideJournal(f.admin(),journal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic prohibited self-posting",1))))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->
                        assertThat(error.code()).isEqualTo("INDEPENDENT_JOURNAL_DECISION_REQUIRED"));
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),journal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent complete-batch posting",1)));
        var beforePostedRead=feeJournalReadProtectedState(f);
        var posted=actorScoped(f.admin(),()->accounting.feeJournalEvidence(f.admin(),f.school(),List.of(journal))).get(journal);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(beforePostedRead);
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.sourceId()).isEqualTo(batch.id());
        assertThat(posted.lines()).isEqualTo(submitted.lines());
        assertThat(familyFees(f).balance()).isEqualByComparingTo("666.67");
    }

    @Test void feeJournalAccrualPreservesLearnerDimensionsAndCurrentAccountClassification() {
        Fixture f=fixture(true);
        Instant paidAt=familyGatewayPastPaymentTime();
        var first=feeJournalPostedPayment(f,"200.00",paidAt);
        var second=feeJournalPostedPayment(f,"125.50",paidAt);
        LocalDate businessDate=feeJournalReceiptDate(f,first.paymentId());
        assertThat(feeJournalReceiptDate(f,second.paymentId())).isEqualTo(businessDate);
        var book=feeJournalBook(f,"ACCRUAL",businessDate);
        var batch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),
                "synthetic-fee-read-accrual-"+UUID.randomUUID(),new FeeAccountingController.PostingRequest(
                        f.school(),book.year(),businessDate,"PAYMENT",200,"Synthetic complete accrual receipt batch")));
        UUID journal=feeJournalBatchJournal(f,batch.id());
        assertThat(owner.queryForObject("SELECT source_count FROM fee_accounting_posting_batches WHERE id=?",Integer.class,batch.id())).isEqualTo(2);
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),journal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent accrual receipt posting",1)));

        var before=feeJournalReadProtectedState(f);
        var evidence=actorScoped(f.admin(),()->accounting.feeJournalEvidence(f.admin(),f.school(),List.of(journal))).get(journal);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(before);
        assertThat(evidence.sourceId()).isEqualTo(batch.id());
        assertThat(evidence.accountingBasis()).isEqualTo("ACCRUAL");
        assertThat(evidence.status()).isEqualTo("POSTED");
        assertThat(evidence.lines()).hasSize(2);
        feeJournalAssertLine(evidence.lines(),book.clearing(),null,"325.50","0.00","ASSET","OTHER");
        feeJournalAssertLine(evidence.lines(),book.receivable(),f.account(),"0.00","325.50","ASSET","RECEIVABLE");
        assertThat(evidence.lines()).noneMatch(line->line.accountId().equals(book.income()));
        assertThat(evidence.reversalLines()).isEmpty();
        assertThat(familyFees(f).balance()).isEqualByComparingTo("674.50");
    }

    @Test void feeJournalScopedEvidenceIncludesRealReversalAndOmitsManualForeignAndReversalIds() {
        Fixture f=fixture(true),foreign=fixture(true);
        Instant paidAt=familyGatewayPastPaymentTime();
        var payment=feeJournalPostedPayment(f,"84.25",paidAt);
        LocalDate businessDate=feeJournalReceiptDate(f,payment.paymentId());
        var book=feeJournalBook(f,"CASH",businessDate);
        var batch=actorScoped(f.admin(),()->feeAccounting.preparePosting(f.admin(),
                "synthetic-fee-read-reversal-"+UUID.randomUUID(),new FeeAccountingController.PostingRequest(
                        f.school(),book.year(),businessDate,"PAYMENT",200,"Synthetic source for common reversal evidence")));
        UUID journal=feeJournalBatchJournal(f,batch.id());
        actorScoped(f.reviewer(),()->accounting.decideJournal(f.reviewer(),journal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"POST","Synthetic independent source posting",1)));
        var manual=actorScoped(f.admin(),()->accounting.createJournal(f.admin(),"synthetic-fee-read-manual-"+UUID.randomUUID(),
                new com.educore.api.accounting.AccountingController.JournalRequest(f.school(),book.year(),book.period(),"JOURNAL",businessDate,
                        "SYNTHETIC-MANUAL","Synthetic non-fee source exclusion",List.of(
                                new com.educore.api.accounting.AccountingController.JournalLineRequest(book.clearing(),null,null,new BigDecimal("7.00"),new BigDecimal("0.00"),"Synthetic manual debit"),
                                new com.educore.api.accounting.AccountingController.JournalLineRequest(book.income(),null,null,new BigDecimal("0.00"),new BigDecimal("7.00"),"Synthetic manual credit")))));

        var foreignPayment=feeJournalPostedPayment(foreign,"18.75",paidAt);
        LocalDate foreignDate=feeJournalReceiptDate(foreign,foreignPayment.paymentId());
        var foreignBook=feeJournalBook(foreign,"CASH",foreignDate);
        var foreignBatch=actorScoped(foreign.admin(),()->feeAccounting.preparePosting(foreign.admin(),
                "synthetic-fee-read-foreign-"+UUID.randomUUID(),new FeeAccountingController.PostingRequest(
                        foreign.school(),foreignBook.year(),foreignDate,"PAYMENT",200,"Synthetic foreign school batch")));
        UUID foreignJournal=feeJournalBatchJournal(foreign,foreignBatch.id());

        var requested=actorScoped(f.admin(),()->accounting.requestReversal(f.admin(),journal,
                new com.educore.api.accounting.AccountingController.ReversalRequest(f.school(),book.period(),businessDate,
                        "Synthetic common-ledger correction, not a fee refund")));
        var beforePendingRead=feeJournalReadProtectedState(f);
        var pending=actorScoped(f.admin(),()->accounting.feeJournalEvidence(f.admin(),f.school(),List.of(journal))).get(journal);
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(beforePendingRead);
        assertThat(pending.status()).isEqualTo("POSTED");
        assertThat(pending.reversalId()).isNull();
        assertThat(pending.reversalLines()).isEmpty();
        assertThatThrownBy(()->actorScoped(f.admin(),()->accounting.decideReversal(f.admin(),requested.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic prohibited self-approval",0))))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->
                        assertThat(error.code()).isEqualTo("INDEPENDENT_REVERSAL_DECISION_REQUIRED"));
        actorScoped(f.reviewer(),()->accounting.decideReversal(f.reviewer(),requested.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(f.school(),"APPROVE","Synthetic independent common-ledger reversal",0)));
        UUID reversal=owner.queryForObject("SELECT reversal_journal_id FROM accounting_reversal_requests WHERE id=?",UUID.class,requested.id());

        var ownBefore=feeJournalReadProtectedState(f);
        var foreignBefore=feeJournalReadProtectedState(foreign);
        var evidence=actorScoped(f.admin(),()->accounting.feeJournalEvidence(f.admin(),f.school(),
                List.of(journal,journal,manual.id(),foreignJournal,reversal,UUID.randomUUID())));
        assertThat(evidence).containsOnlyKeys(journal);
        var original=evidence.get(journal);
        assertThat(original.sourceId()).isEqualTo(batch.id());
        assertThat(original.status()).isEqualTo("REVERSED");
        assertThat(original.reversalId()).isEqualTo(reversal);
        assertThat(original.reversalStatus()).isEqualTo("POSTED");
        assertThat(original.lines()).isEqualTo(pending.lines());
        assertThat(original.lines()).hasSize(2);
        assertThat(original.reversalLines()).hasSize(2);
        feeJournalAssertLine(original.lines(),book.clearing(),null,"84.25","0.00","ASSET","OTHER");
        feeJournalAssertLine(original.lines(),book.income(),null,"0.00","84.25","INCOME","INCOME");
        feeJournalAssertLine(original.reversalLines(),book.clearing(),null,"0.00","84.25","ASSET","OTHER");
        feeJournalAssertLine(original.reversalLines(),book.income(),null,"84.25","0.00","INCOME","INCOME");
        assertThat(actorScoped(f.admin(),()->accounting.feeJournalEvidence(f.admin(),f.school(),List.of(manual.id(),foreignJournal,reversal)))).isEmpty();
        assertThat(actorScoped(f.admin(),()->runtime.queryForObject("SELECT count(*) FROM accounting_journals WHERE id=?",Integer.class,foreignJournal))).isZero();
        assertThatThrownBy(()->actorScoped(f.parent(),()->accounting.feeJournalEvidence(f.parent(),f.school(),List.of(journal))))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->
                        assertThat(error.code()).isEqualTo("ACCOUNTING_SCOPE_DENIED"));
        assertThatThrownBy(()->actorScoped(foreign.admin(),()->accounting.feeJournalEvidence(foreign.admin(),f.school(),List.of(journal))))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,error->
                        assertThat(error.code()).isEqualTo("ACCOUNTING_SCOPE_DENIED"));
        assertThat(feeJournalReadProtectedState(f)).isEqualTo(ownBefore);
        assertThat(feeJournalReadProtectedState(foreign)).isEqualTo(foreignBefore);
        // A GL correction is not a provider refund and must not alter this receipt or dues.
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?",String.class,payment.paymentId())).isEqualTo("POSTED");
        assertThat(familyFees(f).balance()).isEqualByComparingTo("915.75");
    }

    private FinanceService.GatewayPostingResult feeJournalPostedPayment(Fixture f,String amount,Instant paidAt) {
        // Freeze a truthful past provider instant for the whole batch so its
        // receipts cannot land on different school days if a test spans midnight.
        // Use the real reservation/capture/common-receipt lifecycle, not status edits.
        var attempt=prepareAt(f,request(f,amount),Clock.fixed(paidAt.minusSeconds(1),java.time.ZoneOffset.UTC));
        UUID capture=timedCapture(f,attempt,paidAt,"MATCHED",null,"synthetic_fee_journal_"+UUID.randomUUID());
        var result=post(f,capture,null,null);
        assertThat(result.status()).isEqualTo("POSTED");
        return result;
    }

    private LocalDate feeJournalReceiptDate(Fixture f,UUID payment) {
        return owner.queryForObject("""
                SELECT ledger.effective_on FROM fee_payments payment
                JOIN fee_ledger_entries ledger ON ledger.id=payment.ledger_entry_id
                  AND ledger.organisation_id=payment.organisation_id AND ledger.school_id=payment.school_id
                WHERE payment.id=? AND payment.organisation_id=? AND payment.school_id=?
                """,(row,index)->row.getObject(1,LocalDate.class),payment,f.org(),f.school());
    }

    private FeeJournalPgBook feeJournalBook(Fixture f,String basis,LocalDate businessDate) {
        LocalDate startsOn=businessDate.minusMonths(1).withDayOfMonth(1);
        return feeJournalBook(f,basis,businessDate,startsOn.plusYears(1).minusDays(1));
    }
    private FeeJournalPgBook feeJournalBook(Fixture f,String basis,LocalDate businessDate,LocalDate endsOn) {
        LocalDate startsOn=businessDate.minusMonths(1).withDayOfMonth(1);
        var year=actorScoped(f.admin(),()->accounting.createFinancialYear(f.admin(),
                new com.educore.api.accounting.AccountingController.FinancialYearRequest(f.school(),"FEE_READ_PROOF",
                        "Synthetic fee journal evidence year",startsOn,endsOn,basis,"INR","Synthetic reader proof setup")));
        UUID cash=account(f,"PROOF_CASH","ASSET","CASH"),bank=account(f,"PROOF_BANK","ASSET","BANK"),
                clearing=account(f,"PROOF_CLEARING","ASSET","OTHER"),income=account(f,"PROOF_INCOME","INCOME","INCOME");
        UUID receivable="ACCRUAL".equals(basis)?account(f,"PROOF_RECEIVABLE","ASSET","RECEIVABLE"):null;
        UUID concession="ACCRUAL".equals(basis)?account(f,"PROOF_CONCESSION","EXPENSE","EXPENSE"):null;
        actorScoped(f.reviewer(),()->accounting.openFinancialYear(f.reviewer(),year.id(),
                new com.educore.api.accounting.AccountingController.VersionedReasonRequest(f.school(),0,"Synthetic independent year opening")));
        UUID head=owner.queryForObject("SELECT fee_head_id FROM fee_plan_items WHERE id=(SELECT fee_plan_item_id FROM learner_fee_charges WHERE id=?)",UUID.class,f.charge());
        var mapping=actorScoped(f.admin(),()->feeAccounting.createConfiguration(f.admin(),new FeeAccountingController.ConfigurationRequest(
                f.school(),year.id(),null,1,startsOn,receivable,concession,List.of(new FeeAccountingController.HeadMappingRequest(head,income)),
                List.of(new FeeAccountingController.TenderMappingRequest("CASH",cash),new FeeAccountingController.TenderMappingRequest("BANK_TRANSFER",bank),
                        new FeeAccountingController.TenderMappingRequest("CHEQUE",bank),new FeeAccountingController.TenderMappingRequest("SCHOOL_UPI",bank),
                        new FeeAccountingController.TenderMappingRequest("ONLINE_PROVIDER",clearing)),null,"Synthetic real fee mapping for reader proof")));
        var submitted=actorScoped(f.admin(),()->feeAccounting.submitConfiguration(f.admin(),mapping.id(),
                new FeeAccountingController.VersionedReasonRequest(f.school(),0,"Synthetic mapping submission")));
        actorScoped(f.reviewer(),()->feeAccounting.decideConfiguration(f.reviewer(),mapping.id(),
                new FeeAccountingController.ConfigurationDecisionRequest(f.school(),"APPROVE",submitted.rowVersion(),"Synthetic independent fee mapping approval")));
        UUID period=owner.queryForObject("SELECT id FROM accounting_periods WHERE financial_year_id=? AND ? BETWEEN starts_on AND ends_on",
                UUID.class,year.id(),businessDate);
        return new FeeJournalPgBook(year.id(),period,clearing,income,receivable);
    }

    private UUID feeJournalBatchJournal(Fixture f,UUID batch) {
        return owner.queryForObject("SELECT accounting_journal_id FROM fee_accounting_posting_batches WHERE id=? AND organisation_id=? AND school_id=?",
                UUID.class,batch,f.org(),f.school());
    }

    private static void feeJournalAssertLine(List<com.educore.api.accounting.AccountingModuleBridge.FeeJournalLineEvidence> lines,
            UUID account,UUID learner,String debit,String credit,String type,String category) {
        var matching=lines.stream().filter(line->line.accountId().equals(account)&&java.util.Objects.equals(line.learnerFeeAccountId(),learner)).toList();
        assertThat(matching).hasSize(1);
        var line=matching.getFirst();
        assertThat(line.vendorId()).isNull();
        assertThat(line.debitAmount()).isEqualByComparingTo(debit);
        assertThat(line.creditAmount()).isEqualByComparingTo(credit);
        assertThat(line.accountType()).isEqualTo(type);
        assertThat(line.accountCategory()).isEqualTo(category);
        assertThat(line.accountStatus()).isEqualTo("ACTIVE");
        assertThat(line.allowDirectPosting()).isTrue();
    }

    private java.util.Map<String,List<String>> feeJournalReadProtectedState(Fixture f) {
        var state=new java.util.LinkedHashMap<String,List<String>>(refundReadProtectedState(f));
        for(String table:List.of("accounting_financial_years","accounting_periods","accounting_accounts","accounting_vendors",
                "accounting_journal_sequences","accounting_reversal_requests","fee_accounting_configurations","fee_accounting_head_mappings",
                "fee_accounting_tender_mappings","fee_accounting_posting_batches","fee_accounting_posting_items"))
            state.put(table,owner.queryForList("SELECT row_to_json(snapshot)::text FROM "+table+" snapshot WHERE school_id=? ORDER BY row_to_json(snapshot)::text",String.class,f.school()));
        return state;
    }

    private record FeeJournalPgBook(UUID year,UUID period,UUID clearing,UUID income,UUID receivable) {}

    @Test void collectionRegisterRetainsHistoricalCollectionsAndDatesReversalsSeparately() {
        Fixture f=fixture(true);
        Instant received=manualInstant().minus(Duration.ofDays(1));
        LocalDate original=LocalDate.ofInstant(received,java.time.ZoneId.of("Asia/Kolkata")),today=schoolToday();
        var cash=collect(f,"synthetic-register-cash",manualPayment(f,"CASH",null,"100",received,null,null,null));
        var upi=collect(f,"synthetic-register-upi",manualPayment(f,"SCHOOL_UPI","SYN-REGISTER-UPI","50",manualInstant(),null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"SYN-REGISTER-UPI"),null));
        var before=register(f,original,original,null,null,0,25);
        assertThat(before.summary().grossCollections()).isEqualByComparingTo("100");
        actorScoped(f.admin(),()->finance.reversePayment(f.admin(),cash.id(),new FinanceController.PaymentReversalRequest(f.school(),0,"Synthetic later-day reversal")));
        var historical=register(f,original,original,null,null,0,25);
        assertThat(historical.summary()).isEqualTo(before.summary());
        assertThat(historical.items()).hasSize(1);
        assertThat(historical.items().getFirst().receiptStatus()).isEqualTo("REVERSED");
        assertThat(historical.items().getFirst().timing().actualReceivedAt()).isEqualTo(received);
        var current=register(f,today,today,null,null,0,25);
        assertThat(current.total()).isEqualTo(2);
        assertThat(current.summary().grossCollections()).isEqualByComparingTo("50");
        assertThat(current.summary().reversalAmount()).isEqualByComparingTo("100");
        assertThat(current.summary().netCollections()).isEqualByComparingTo("-50");
        assertThat(current.items()).extracting(CollectionRegisterResponse.Movement::paymentId).containsExactlyInAnyOrder(cash.id(),upi.id());
        var reversal=current.items().stream().filter(item->item.kind().equals("REVERSAL")).findFirst().orElseThrow();
        assertThat(reversal.postingOn()).isEqualTo(today);
        assertThat(reversal.timing().originalReceivedOn()).isEqualTo(original);
        var cashOnly=register(f,today,today,"CASH",null,0,25);
        assertThat(cashOnly.summary().netCollections()).isEqualByComparingTo("-100");
        assertThat(cashOnly.summary().collectionCount()).isZero();
        assertThat(cashOnly.tenderTotals()).hasSize(5);
        assertThat(register(f,original,today,null,cash.receiptNumber(),0,25).total()).isEqualTo(2);
    }

    @Test void collectionRegisterScopesFiltersPaginationCurrencyAndRetriesWithoutWritingMoney() {
        Fixture f=fixture(true),other=fixture(true); LocalDate today=schoolToday();
        var request=payment(f,"10.25");
        var first=collect(f,"synthetic-register-retry",request);
        assertThat(collect(f,"synthetic-register-retry",request)).isEqualTo(first);
        collect(f,"synthetic-register-second",payment(f,"11.50"));
        collect(f,"synthetic-register-third",payment(f,"12.75"));
        String before=owner.queryForObject("SELECT md5(string_agg(row_to_json(item)::text,',' ORDER BY id)) FROM fee_payments item WHERE school_id=?",String.class,f.school());
        var all=register(f,today,today,null,null,0,25);
        assertThat(all.total()).isEqualTo(3); assertThat(all.summary().grossCollections()).isEqualByComparingTo("34.50");
        assertThat(all.items()).allSatisfy(item->{assertThat(item.timing().actualReceivedAt()).isNull();assertThat(item.timing().receivedTimePrecision()).isEqualTo("DATE_ONLY");});
        var pages=new java.util.ArrayList<UUID>();
        for(int page=0;page<3;page++) {
            var next=register(f,today,today,null,null,page,1);
            assertThat(next.summary()).isEqualTo(all.summary());assertThat(next.total()).isEqualTo(3);
            assertThat(next.hasMore()).isEqualTo(page<2);pages.add(next.items().getFirst().id());
        }
        assertThat(pages).containsExactlyElementsOf(all.items().stream().map(CollectionRegisterResponse.Movement::id).toList());
        var emptyPage=register(f,today,today,null,null,10,1);
        assertThat(emptyPage.items()).isEmpty(); assertThat(emptyPage.summary()).isEqualTo(all.summary());assertThat(emptyPage.hasMore()).isFalse();
        for(String literal:List.of("%","_","' OR true--")) assertThat(register(f,today,today,null,literal,0,25).total()).isZero();
        var otherCurrency=actorScoped(f.admin(),()->finance.collections(f.admin(),f.school(),today,today,"USD",null,null,0,25));
        assertThat(otherCurrency.currencyCode()).isEqualTo("USD");assertThat(otherCurrency.total()).isZero();assertThat(otherCurrency.summary().grossCollections()).isZero();
        assertThatThrownBy(()->actorScoped(f.parent(),()->finance.collections(f.parent(),f.school(),today,today,null,null,null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(other.admin(),()->finance.collections(other.admin(),f.school(),today,today,null,null,null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->register(f,today,today.plusDays(366),null,null,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT md5(string_agg(row_to_json(item)::text,',' ORDER BY id)) FROM fee_payments item WHERE school_id=?",String.class,f.school())).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='PAYMENT'",Integer.class,f.school())).isEqualTo(3);
        owner.update("UPDATE memberships SET valid_until=now()-interval '1 hour',row_version=row_version+1 WHERE id=?",member(f.admin()));
        assertThatThrownBy(()->register(f,today,today,null,null,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
    }

    @Test void collectionRegisterIncludesOnlyPostedGatewayReceiptsWithExactReceivedEvidence() {
        Fixture f=fixture(true); LocalDate today=schoolToday();
        var attempt=prepare(f,request(f,"200"));
        Instant actual=familyGatewayPastPaymentTime();
        UUID capture=timedCapture(f,attempt,actual,"MATCHED",null,"synthetic_register_posted");
        var posted=post(f,capture,null,null);
        assertThat(posted.status()).isEqualTo("POSTED");
        var heldAttempt=prepare(f,request(f,"100"));
        UUID held=timedCapture(f,heldAttempt,familyGatewayPastPaymentTime(),"REVIEW_REQUIRED",null,"synthetic_register_unposted");
        assertThat(post(f,held,null,null).status()).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        var report=register(f,today,today,"ONLINE_PROVIDER",null,0,25);
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.summary().grossCollections()).isEqualByComparingTo("200");
        assertThat(report.items().getFirst().paymentId()).isEqualTo(posted.paymentId());
        assertThat(report.items().getFirst().timing().actualReceivedAt()).isEqualTo(actual);
        assertThat(report.items().getFirst().timing().schoolTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(JsonMapper.builder().build().writeValueAsString(report)).doesNotContain("credentials","checkoutUrl","payerPhone","synthetic_register_posted","merchant_account_reference");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
    }

    @Test void collectionExportReturnsEveryFilteredMovementInOneReadAndAuditsWithoutPersonalSearchText() {
        Fixture f=fixture(true);LocalDate today=schoolToday();
        for(int index=0;index<26;index++)collect(f,"synthetic-export-"+index,payment(f,"1.25"));
        var firstPage=register(f,today,today,null,null,0,25);
        assertThat(firstPage.items()).hasSize(25);assertThat(firstPage.hasMore()).isTrue();
        var complete=actorScoped(f.admin(),()->finance.exportCollections(f.admin(),f.school(),today,today,null,null,null));
        assertThat(complete.total()).isEqualTo(26);assertThat(complete.items()).hasSize(26);
        assertThat(complete.summary()).isEqualTo(firstPage.summary());assertThat(complete.tenderTotals()).isEqualTo(firstPage.tenderTotals());
        assertThat(complete.summary().grossCollections()).isEqualByComparingTo("32.50");
        assertThat(complete.items().subList(0,25)).isEqualTo(firstPage.items());
        assertThat(complete.items()).allSatisfy(item->assertThat(item.timing().actualReceivedAt()).isNull());
        String receipt=complete.items().getLast().receiptNumber();
        var filtered=actorScoped(f.admin(),()->finance.exportCollections(f.admin(),f.school(),today,today,"INR","CASH",receipt));
        assertThat(filtered.items()).hasSize(1);assertThat(filtered.items().getFirst().receiptNumber()).isEqualTo(receipt);
        var retry=actorScoped(f.admin(),()->finance.exportCollections(f.admin(),f.school(),today,today,"INR","CASH",receipt));
        assertThat(retry.items()).isEqualTo(filtered.items());assertThat(retry.summary()).isEqualTo(filtered.summary());
        var empty=actorScoped(f.admin(),()->finance.exportCollections(f.admin(),f.school(),today,today,"USD",null,null));
        assertThat(empty.items()).isEmpty();assertThat(empty.total()).isZero();assertThat(empty.summary().netCollections()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action='FEES_COLLECTION_REGISTER_EXPORTED'",Integer.class,f.school())).isEqualTo(4);
        assertThat(owner.queryForList("SELECT safe_metadata::text FROM audit_events WHERE school_id=? AND action='FEES_COLLECTION_REGISTER_EXPORTED'",String.class,f.school()))
                .allSatisfy(metadata->{assertThat(metadata).doesNotContain(receipt,"learnerName","query");assertThat(metadata).contains("movements","searchApplied");});
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?",Integer.class,f.school())).isEqualTo(26);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='PAYMENT'",Integer.class,f.school())).isEqualTo(26);
    }

    @Test void collectionExportDeniesForeignRevokedAndGuardianAuthorityAndAuditFailureCannotServeAReport() {
        Fixture f=fixture(true),other=fixture(true);LocalDate today=schoolToday();
        collect(f,"synthetic-export-isolation",payment(f,"29.29"));
        for(var actor:List.of(f.parent(),other.admin())) {
            assertThatThrownBy(()->actorScoped(actor,()->finance.exportCollections(actor,f.school(),today,today,null,null,null)))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        var failingAudit=org.mockito.Mockito.mock(SecurityAuditService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic audit unavailable")).when(failingAudit).recordScoped(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyMap());
        var failClosed=new FinanceService(new NamedParameterJdbcTemplate(runtime),failingAudit,JsonMapper.builder().build(),clock);
        assertThatThrownBy(()->actorScoped(f.admin(),()->failClosed.exportCollections(f.admin(),f.school(),today,today,null,null,null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic audit unavailable");
        owner.update("UPDATE memberships SET valid_until=now()-interval '1 hour',row_version=row_version+1 WHERE id=?",member(f.admin()));
        assertThatThrownBy(()->actorScoped(f.admin(),()->finance.exportCollections(f.admin(),f.school(),today,today,null,null,null)))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action='FEES_COLLECTION_REGISTER_EXPORTED'",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT sum(amount) FROM fee_payments WHERE school_id=?",BigDecimal.class,f.school())).isEqualByComparingTo("29.29");
    }

    private CollectionRegisterResponse register(Fixture f,LocalDate from,LocalDate to,String method,String query,int page,int size) {
        return actorScoped(f.admin(),()->finance.collections(f.admin(),f.school(),from,to,null,method,query,page,size));
    }

    @Test void refundMigrationForwardFromPopulatedV072PreservesReceiptsBalancesAndFinancialHistory() {
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='073'",Boolean.class)).isTrue();
        assertThat(refundFinancialState(refundForwardFixture)).isEqualTo(refundForwardFinancialState);
        assertThat(familyFees(refundForwardFixture).balance()).isEqualByComparingTo("976.55");
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?",String.class,refundForwardPayment)).isEqualTo("POSTED");
        for(String table:REFUND_EVIDENCE_TABLES) {
            assertThat(owner.queryForObject("SELECT count(*) FROM "+table+" WHERE school_id=?",Integer.class,refundForwardFixture.school())).isZero();
            assertThat(runtime.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        }
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void refundPartialIntentAndIndependentApprovalPreserveMoneyAndExactRetriesWithoutClaimingExecution() {
        Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();
        var staffReceipt=actorScoped(f.admin(),()->finance.snapshot(f.admin(),f.school())).accounts().stream()
                .filter(account->account.id().equals(f.account())).findFirst().orElseThrow().payments().stream()
                .filter(receipt->receipt.id().equals(payment)).findFirst().orElseThrow();
        var staffReceiptJson=JsonMapper.builder().build().valueToTree(staffReceipt);
        assertThat(staffReceipt.gatewayRefundSource()).isTrue();assertThat(staffReceiptJson.get("gatewayRefundSource").booleanValue()).isTrue();
        String privateCapture=owner.queryForObject("SELECT gateway_capture_id FROM fee_payments WHERE id=?",UUID.class,payment).toString();
        assertThat(staffReceiptJson.toString()).doesNotContain("gatewayCaptureId","captureId",privateCapture);
        assertThat(JsonMapper.builder().build().writeValueAsString(familyFees(f))).doesNotContain("gatewayRefundSource","gatewayCaptureId","captureId",privateCapture);
        var before=refundFinancialState(f);var command=refundSubmit(f,UUID.randomUUID(),"50.25");
        var made=refundSubmit(f,f.admin(),payment,command);
        assertThat(made.status()).isEqualTo("SUBMITTED");
        assertThat(refundSubmit(f,f.admin(),payment,command)).isEqualTo(made);
        var source=refundPayment(f,f.admin(),payment);
        assertThat(source.executionEnabled()).isFalse();assertThat(source.available()).isEqualByComparingTo("149.75");
        assertThat(source.reserved()).isEqualByComparingTo("50.25");assertThat(source.allocations()).hasSize(1);
        assertThat(source.allocations().getFirst().available()).isEqualByComparingTo("149.75");
        var makerQueue=refundQueue(f,f.admin(),"SUBMITTED",null,0,1);
        assertThat(makerQueue.executionEnabled()).isFalse();assertThat(makerQueue.total()).isEqualTo(1);
        assertThat(makerQueue.items().getFirst().isMaker()).isTrue();assertThat(makerQueue.items().getFirst().canReview()).isFalse();
        assertThat(makerQueue.items().getFirst().canCancel()).isTrue();
        var checkerQueue=refundQueue(f,f.reviewer(),"ALL",source.receiptNumber(),0,25);
        assertThat(checkerQueue.items().getFirst().canReview()).isTrue();assertThat(checkerQueue.items().getFirst().canCancel()).isFalse();
        var approved=refundDecide(f,f.reviewer(),made.id(),"APPROVE");assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(refundSubmit(f,f.admin(),payment,command)).isEqualTo(approved);
        assertThat(refundPayment(f,f.admin(),payment).reserved()).isEqualByComparingTo("50.25");
        assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,refundSubmit(f,command.clientRequestId(),"50.26")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundFinancialState(f)).isEqualTo(before);assertThat(familyFees(f).balance()).isEqualByComparingTo("800");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_requests WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_decisions WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
        assertThat(JsonMapper.builder().build().writeValueAsString(checkerQueue)).doesNotContain("credentials_secret_reference","synthetic:not-enabled","merchant_account_reference","payerPhone","payment_reference","order_reference");
    }

    @Test void refundCumulativeBudgetsReserveApprovedIntentAndReleaseOnlyRejectedOrMakerCancelledIntent() {
        Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();var before=refundFinancialState(f);
        var first=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"120"));
        assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"80.01")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        refundDecide(f,f.reviewer(),first.id(),"APPROVE");
        assertThatThrownBy(()->refundDecide(f,f.admin(),first.id(),"CANCEL")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var rejected=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"60"));
        assertThat(refundDecide(f,f.reviewer(),rejected.id(),"REJECT").status()).isEqualTo("REJECTED");
        var cancelled=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"80"));
        assertThat(refundDecide(f,f.admin(),cancelled.id(),"CANCEL").status()).isEqualTo("CANCELLED");
        refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"80"));
        assertThat(refundPayment(f,f.admin(),payment).available()).isZero();
        assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"0.01")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundFinancialState(f)).isEqualTo(before);
    }

    @Test void refundConcurrentRequestsCannotOversubscribeCaptureOrOriginalAllocationUnderReadCommittedOrRepeatableRead() throws Exception {
        for(boolean repeatable:List.of(false,true)) {
            Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();var before=refundFinancialState(f);
            CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            try(var workers=Executors.newFixedThreadPool(2)) {
                var a=workers.submit(()->tryConcurrentRefund(f,payment,repeatable,ready,start));
                var b=workers.submit(()->tryConcurrentRefund(f,payment,repeatable,ready,start));
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
                assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            }
            assertThat(refundPayment(f,f.admin(),payment).reserved()).isEqualByComparingTo("120");
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_requests WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
            assertThat(refundFinancialState(f)).isEqualTo(before);
        }
    }

    @Test void refundReviewRequiresDifferentHumanAndRechecksConfiguredRefundPolicy() {
        Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();var before=refundFinancialState(f);
        var made=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"20"));
        for(String decision:List.of("APPROVE","REJECT")) assertThatThrownBy(()->refundDecide(f,f.admin(),made.id(),decision))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundDecide(f,f.reviewer(),made.id(),"CANCEL")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("UPDATE fee_heads SET refundable=false,row_version=row_version+1 WHERE school_id=?",f.school());
        assertThatThrownBy(()->refundDecide(f,f.reviewer(),made.id(),"APPROVE")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"1")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundDecide(f,f.reviewer(),made.id(),"REJECT").status()).isEqualTo("REJECTED");
        assertThat(refundPayment(f,f.admin(),payment).reserved()).isZero();
        assertThat(refundFinancialState(f)).isEqualTo(before);
    }

    @Test void refundScopeRejectsGuardianForeignWrongActorRevokedGrantAndInvalidInputs() {
        Fixture f=refundFixture(),foreign=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();var before=refundFinancialState(f);
        for(var actor:List.of(f.parent(),foreign.admin())) {
            assertThatThrownBy(()->refundPayment(f,actor,payment)).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(()->refundQueue(f,actor,"ALL",null,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(()->refundSubmit(f,actor,payment,refundSubmit(f,UUID.randomUUID(),"1"))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        assertThatThrownBy(()->actorScoped(f.parent(),()->refundService().submit(f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"1"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        for(String amount:List.of("0","-1","1.001","201")) assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),amount)))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        for(var command:List.of(
                new GatewayRefundModels.Submit(f.school(),UUID.randomUUID(),List.of(),"Synthetic empty selection"),
                new GatewayRefundModels.Submit(f.school(),UUID.randomUUID(),List.of(new GatewayRefundModels.Selection(UUID.randomUUID(),BigDecimal.ONE)),"Synthetic unrelated charge"),
                new GatewayRefundModels.Submit(foreign.school(),UUID.randomUUID(),List.of(new GatewayRefundModels.Selection(f.charge(),BigDecimal.ONE)),"Synthetic wrong school")))
            assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,command)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        UUID siblingSchool=UUID.randomUUID();
        owner.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'synthetic-refund-sibling','Synthetic sibling school','Synthetic sibling school','ACTIVE',now(),now())",siblingSchool,f.org());
        assertThatThrownBy(()->actorScoped(f.admin(),()->refundService().payment(f.admin(),siblingSchool,payment)))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,new GatewayRefundModels.Submit(siblingSchool,UUID.randomUUID(),List.of(new GatewayRefundModels.Selection(f.charge(),BigDecimal.ONE)),"Synthetic same organisation wrong school")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        UUID readOnlyRole=financeRole(f.org(),List.of("fees.read"));
        owner.update("DELETE FROM membership_roles WHERE membership_id=?",member(f.reviewer()));
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",member(f.reviewer()),readOnlyRole);
        assertThat(refundPayment(f,f.reviewer(),payment).canRequest()).isFalse();
        assertThat(refundQueue(f,f.reviewer(),"ALL",null,0,25).executionEnabled()).isFalse();
        assertThatThrownBy(()->refundSubmit(f,f.reviewer(),payment,refundSubmit(f,UUID.randomUUID(),"1")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        for(int page:List.of(-1,10001)) assertThatThrownBy(()->refundQueue(f,f.admin(),"ALL",null,page,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundQueue(f,f.admin(),"SUCCESS",null,0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->refundQueue(f,f.admin(),"ALL","x".repeat(101),0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("DELETE FROM membership_roles WHERE membership_id=?",member(f.admin()));
        assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"1"))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(refundFinancialState(f)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_requests WHERE school_id=?",Integer.class,f.school())).isZero();
    }

    @Test void refundAuditFailureAndTransactionRollbackLeaveNoPartialRequestsOrDecisions() {
        Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();var before=refundFinancialState(f);
        var failingAudit=org.mockito.Mockito.mock(SecurityAuditService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic refund audit unavailable")).when(failingAudit).recordScoped(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap());
        var failClosed=new GatewayRefundService(new NamedParameterJdbcTemplate(runtime),finance,failingAudit,clock);
        assertThatThrownBy(()->actorScoped(f.admin(),()->failClosed.submit(f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"20"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic refund audit unavailable");
        assertThatThrownBy(()->actorScoped(f.admin(),()->{
            refundService().submit(f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"30"));
            throw new IllegalStateException("Synthetic outer rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Synthetic outer rollback");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_requests WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_allocations WHERE school_id=?",Integer.class,f.school())).isZero();
        var made=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"40"));
        var auditBefore=owner.queryForList("SELECT row_to_json(a)::text FROM audit_events a WHERE school_id=? ORDER BY id",String.class,f.school());
        assertThatThrownBy(()->actorScoped(f.reviewer(),()->failClosed.decide(f.reviewer(),made.id(),new GatewayRefundModels.DecisionCommand(f.school(),"APPROVE","Synthetic approval rollback"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic refund audit unavailable");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_decisions WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForList("SELECT row_to_json(a)::text FROM audit_events a WHERE school_id=? ORDER BY id",String.class,f.school())).isEqualTo(auditBefore);
        assertThat(refundPayment(f,f.admin(),payment).reserved()).isEqualByComparingTo("40");
        assertThat(refundFinancialState(f)).isEqualTo(before);
    }

    @Test void refundOriginalPostedReceiptRemainsEligibleAfterAccountClosureAndLaterExtraCapture() {
        Fixture f=refundFixture();var attempt=prepare(f,request(f,"200"));
        UUID capture=timedCapture(f,attempt,familyGatewayPastPaymentTime(),"MATCHED",null,"synthetic_refund_historical");
        UUID payment=post(f,capture,null,null).paymentId();
        timedCapture(f,attempt,familyGatewayPastPaymentTime(),"REVIEW_REQUIRED",null,"synthetic_refund_later_extra");
        owner.update("UPDATE learner_fee_accounts SET status='CLOSED',closed_by_membership_id=?,closed_at=now(),close_reason='Synthetic historical refund source',row_version=row_version+1 WHERE id=?",member(f.admin()),f.account());
        var before=refundFinancialState(f);
        assertThat(refundPayment(f,f.admin(),payment).canRequest()).isTrue();
        var made=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"200"));
        assertThat(refundDecide(f,f.reviewer(),made.id(),"APPROVE").status()).isEqualTo("APPROVED");
        assertThat(refundFinancialState(f)).isEqualTo(before);
    }

    @Test void refundActualFinanceLockWaitRechecksExpiredMembershipAndRevokedAuthority() throws Exception {
        for(boolean expires:List.of(true,false)) {
            Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();var before=refundFinancialState(f);
            try(var workers=Executors.newSingleThreadExecutor();var locked=owner.getDataSource().getConnection()) {
                locked.setAutoCommit(false);
                try(var statement=locked.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    statement.setString(1,f.school()+":finance");statement.execute();
                }
                if(expires) owner.update("UPDATE memberships SET valid_until=clock_timestamp()+interval '2 seconds',row_version=row_version+1 WHERE id=?",member(f.admin()));
                var submitted=workers.submit(()->refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"1")));
                try {
                    awaitFamilyGatewayFinanceWait();
                    if(expires) awaitFamilyGatewayTime(owner.queryForObject("SELECT valid_until FROM memberships WHERE id=?",(r,n)->r.getObject(1,java.time.OffsetDateTime.class).toInstant(),member(f.admin())));
                    else owner.update("DELETE FROM membership_roles WHERE membership_id=?",member(f.admin()));
                    locked.commit();
                    assertThatThrownBy(()->submitted.get(20,TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class)
                            .hasCauseInstanceOf(com.educore.api.common.api.ApiException.class);
                } finally {locked.rollback();}
            }
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_requests WHERE school_id=?",Integer.class,f.school())).isZero();
            assertThat(refundFinancialState(f)).isEqualTo(before);
        }
    }

    @Test void refundDirectWritesCannotForgeSourceOmitAllocationsExceedDeferredBudgetsOrMutateReviewedEvidence() {
        Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();var before=refundFinancialState(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefund(f,payment,"20",null,false)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefund(f,payment,"20","19",false)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefund(f,payment,"20","20",true)))
                .isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_requests WHERE school_id=?",Integer.class,f.school())).isZero();
        var made=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"120"));
        for(String decision:List.of("APPROVE","REJECT")) assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefundDecision(f,f.admin(),made.id(),decision)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->actorScoped(f.reviewer(),()->rawRefundDecision(f,f.reviewer(),made.id(),"CANCEL")))
                .isInstanceOf(DataAccessException.class);
        // Each raw request is individually below the captured amount. The actual
        // deferred cross-request budget must reject their cumulative 201 total.
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefund(f,payment,"81","81",false)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        refundDecide(f,f.reviewer(),made.id(),"APPROVE");
        var foreign=refundFixture().admin();
        var evidence=new java.util.LinkedHashMap<String,List<String>>();
        for(String table:REFUND_EVIDENCE_TABLES) {
            evidence.put(table,owner.queryForList("SELECT row_to_json(e)::text FROM "+table+" e WHERE school_id=? ORDER BY id",String.class,f.school()));
            assertThatThrownBy(()->owner.update("UPDATE "+table+" SET id=id WHERE school_id=?",f.school())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(()->owner.update("DELETE FROM "+table+" WHERE school_id=?",f.school())).isInstanceOf(DataAccessException.class);
            assertThat(actorScoped(foreign,()->runtime.queryForObject("SELECT count(*) FROM "+table+" WHERE school_id=?",Integer.class,f.school()))).isZero();
        }
        assertThatThrownBy(()->refundDecide(f,f.reviewer(),made.id(),"REJECT")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        for(String table:REFUND_EVIDENCE_TABLES) assertThat(owner.queryForList("SELECT row_to_json(e)::text FROM "+table+" e WHERE school_id=? ORDER BY id",String.class,f.school())).isEqualTo(evidence.get(table));
        assertThat(refundFinancialState(f)).isEqualTo(before);
    }

    @Test void refundPerAllocationBudgetCannotBorrowAnotherPaidChargeEvenWhenCaptureHasRemainingPrincipal() {
        Fixture f=refundFixture(true);
        UUID second=owner.queryForObject("SELECT id FROM learner_fee_charges WHERE learner_fee_account_id=? AND id<>?",UUID.class,f.account(),f.charge());
        var attempt=prepare(f,new GatewayReservationService.PrepareRequest(UUID.randomUUID(),List.of(selection(f,"100"),new GatewayReservationService.ChargeSelection(second,new BigDecimal("100"))),"0000000000"));
        UUID capture=timedCapture(f,attempt,familyGatewayPastPaymentTime(),"MATCHED",null,"synthetic_refund_split_capture");
        UUID payment=post(f,capture,null,null).paymentId();var before=refundFinancialState(f);
        refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"80"));
        assertThatThrownBy(()->refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"20.01")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefund(f,payment,"21","21",false)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        var source=refundPayment(f,f.admin(),payment);
        assertThat(source.available()).isEqualByComparingTo("120");
        assertThat(source.allocations().stream().filter(item->item.chargeId().equals(f.charge())).findFirst().orElseThrow().available()).isEqualByComparingTo("20");
        refundSubmit(f,f.admin(),payment,new GatewayRefundModels.Submit(f.school(),UUID.randomUUID(),List.of(new GatewayRefundModels.Selection(second,new BigDecimal("100"))),"Synthetic second paid allocation"));
        assertThat(refundFinancialState(f)).isEqualTo(before);
    }

    @Test void refundReadPopulatedV073UpgradePreservesApprovalAndRequiresExactWritableBackgroundScope() {
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='074'",Boolean.class)).isTrue();
        assertThat(refundFinancialState(refundReadForwardFixture)).isEqualTo(refundReadForwardFinancialState);
        assertThat(refundReadIntentState(refundReadForwardFixture)).isEqualTo(refundReadForwardIntentState);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE school_id=?",Integer.class,refundReadForwardFixture.school())).isZero();
        var ready=familyGatewayBackground(refundReadForwardFixture,()->refundReadStore().prepare(familyGatewayBinding(refundReadForwardFixture),refundReadForwardRequest));
        assertThat(ready.expected().refundId()).isEqualTo(refundReadForwardRequest);
        Fixture f=refundFixture();UUID payment=refundPostedPayment(f,"200").paymentId();
        var pending=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"20"));
        assertThatThrownBy(()->familyGatewayBackground(f,()->refundReadStore().prepare(familyGatewayBinding(f),pending.id()))).isInstanceOf(IllegalStateException.class);
        refundDecide(f,f.reviewer(),pending.id(),"REJECT");
        assertThatThrownBy(()->familyGatewayBackground(f,()->refundReadStore().prepare(familyGatewayBinding(f),pending.id()))).isInstanceOf(IllegalStateException.class);
        var approved=refundReadApproved();var a=approved.fixture();var binding=familyGatewayBinding(a);
        assertThatThrownBy(()->actorScoped(a.admin(),()->refundReadStore().prepare(binding,approved.refundId()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->refundReadStore().prepare(binding,approved.refundId())).isInstanceOf(IllegalStateException.class);
        Fixture foreign=refundFixture();
        assertThatThrownBy(()->familyGatewayBackground(foreign,()->refundReadStore().prepare(binding,approved.refundId()))).isInstanceOf(IllegalStateException.class);
        for(var wrong:List.of(
                new GatewayWorkQueue.ProviderBinding(a.org(),foreign.school(),a.provider(),"CASHFREE","TEST",binding.merchantReference(),binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(a.org(),a.school(),UUID.randomUUID(),"CASHFREE","TEST",binding.merchantReference(),binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(a.org(),a.school(),a.provider(),"CASHFREE","LIVE",binding.merchantReference(),binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(a.org(),a.school(),a.provider(),"CASHFREE","TEST","synthetic_wrong_merchant",binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(a.org(),a.school(),a.provider(),"CASHFREE","TEST",binding.merchantReference(),"synthetic:wrong-binding")))
            assertThatThrownBy(()->familyGatewayBackground(a,()->refundReadStore().prepare(wrong,approved.refundId()))).isInstanceOf(IllegalStateException.class);
        var readOnly=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));readOnly.setReadOnly(true);
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,a.org())) {
            assertThatThrownBy(()->readOnly.execute(status->refundReadStore().prepare(binding,approved.refundId()))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE school_id IN (?,?)",Integer.class,f.school(),a.school())).isZero();
    }

    @Test void refundReadSuccessPreservesOriginalNanosecondsFrozenApprovalFloorAndSchoolDayWithoutPosting() {
        var test=refundReadApproved();Fixture f=test.fixture();var prepared=refundReadPrepare(test);var expected=prepared.expected();
        assertThat(expected.originalPaidAt().getNano()%1000).isEqualTo(999);
        assertThat(expected.originalPaidAt()).isEqualTo(GatewayExactInstant.parseCanonical(owner.queryForObject("SELECT paid_at_exact FROM fee_gateway_captures WHERE id=?",String.class,expected.captureId())));
        assertThat(prepared.approvalDecisionId()).isEqualTo(owner.queryForObject("SELECT id FROM fee_gateway_refund_decisions WHERE refund_request_id=?",UUID.class,test.refundId()));
        assertThat(expected.paymentId()).isEqualTo(test.paymentId());assertThat(expected.refundAmount()).isEqualByComparingTo("50.25");
        owner.update("UPDATE schools SET time_zone='UTC',updated_at=now() WHERE id=?",f.school());
        owner.update("UPDATE payment_provider_configs SET credentials_secret_reference='synthetic:rotated',updated_at=now(),row_version=row_version+1 WHERE id=?",f.provider());
        assertThat(refundReadPrepare(test)).isEqualTo(prepared);
        var before=refundReadProtectedState(f);Instant processed=expected.approvedAt().plusNanos(123);
        var facts=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,processed);
        var retained=refundReadRetain(test,UUID.randomUUID(),facts);var proof=retained.verification().success().orElseThrow();
        assertThat(retained.expected()).isEqualTo(expected);assertThat(retained.observation().refund()).isEqualTo(facts);
        assertThat(retained.verification().outcome()).isEqualTo(GatewayRefundVerification.Outcome.VERIFIED_SUCCESS);
        assertThat(proof.processedAtExact()).isEqualTo(GatewayExactInstant.canonical(processed));
        assertThat(proof.processedAtProjection()).isEqualTo(GatewayExactInstant.floor(processed));
        assertThat(proof.processedOn()).isEqualTo(processed.atZone(java.time.ZoneId.of(expected.schoolTimeZone())).toLocalDate());
        assertThat(owner.queryForObject("SELECT processed_at_exact FROM fee_gateway_refund_reads WHERE id=?",String.class,retained.id())).isEqualTo(GatewayExactInstant.canonical(processed));
        Instant storedProjection=owner.queryForObject("SELECT processed_at FROM fee_gateway_refund_reads WHERE id=?",(row,n)->row.getObject(1,java.time.OffsetDateTime.class).toInstant(),retained.id());
        assertThat(storedProjection).isEqualTo(GatewayExactInstant.floor(processed));
        assertThat(owner.queryForObject("SELECT processed_on FROM fee_gateway_refund_reads WHERE id=?",LocalDate.class,retained.id())).isEqualTo(proof.processedOn());
        assertThat(refundReadProtectedState(f)).isEqualTo(before);assertThat(refundPayment(f,f.admin(),test.paymentId()).reserved()).isEqualByComparingTo("50.25");
    }

    @Test void refundReadExactIdentityReplaysOriginalTimeWhileFreshReadsRemainIndependentAndConflictingFactsFail() {
        var test=refundReadApproved();var expected=refundReadPrepare(test).expected();var before=refundReadProtectedState(test.fixture());
        var facts=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123));UUID id=UUID.randomUUID();
        var first=refundReadRetain(test,id,facts);
        awaitFamilyGatewayTime(first.observation().observedAt().plusMillis(5));
        var retry=refundReadRetain(test,id,facts);assertThat(retry).isEqualTo(first);
        var fresh=refundReadRetain(test,UUID.randomUUID(),facts);
        assertThat(fresh.id()).isNotEqualTo(first.id());assertThat(fresh.observation().observedAt()).isAfter(first.observation().observedAt());
        assertThat(fresh.verification().evidenceSha256()).isEqualTo(first.verification().evidenceSha256());
        var conflicting=new PaymentProviderAdapter.RefundObservation(facts.refundReference(),facts.orderReference(),facts.paymentReference(),facts.amount().add(new BigDecimal("0.01")),facts.currencyCode(),facts.status(),facts.processedAt());
        assertThatThrownBy(()->refundReadRetain(test,id,conflicting)).isInstanceOf(IllegalStateException.class);
        var foreign=refundReadApproved();assertThatThrownBy(()->refundReadRetain(foreign,id,refundReadFacts(refundReadPrepare(foreign).expected(),PaymentProviderAdapter.RefundStatus.PENDING,null))).isInstanceOfAny(IllegalStateException.class,DataAccessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,foreign.refundId())).isZero();
        assertThat(refundReadProtectedState(test.fixture())).isEqualTo(before);
    }

    @Test void refundReadPendingFailedHeldCancelledAndAbsentFactsRemainTruthfulWithoutBudgetOrFinancialMutation() {
        var test=refundReadApproved();var expected=refundReadPrepare(test).expected();var before=refundReadProtectedState(test.fixture());
        for(var status:List.of(PaymentProviderAdapter.RefundStatus.PENDING,PaymentProviderAdapter.RefundStatus.FAILED,PaymentProviderAdapter.RefundStatus.CANCELLED,PaymentProviderAdapter.RefundStatus.ON_HOLD)) {
            var retained=refundReadRetain(test,UUID.randomUUID(),refundReadFacts(expected,status,null));
            assertThat(retained.verification().outcome()).isEqualTo(GatewayRefundVerification.Outcome.NOT_SUCCESSFUL);assertThat(retained.verification().success()).isEmpty();
            assertThat(retained.observation().refund().status()).isEqualTo(status);
            assertThat(owner.queryForObject("SELECT processed_at_exact IS NULL AND processed_at IS NULL AND processed_on IS NULL FROM fee_gateway_refund_reads WHERE id=?",Boolean.class,retained.id())).isTrue();
        }
        var absent=refundReadRetain(test,UUID.randomUUID(),null);assertThat(absent.verification().outcome()).isEqualTo(GatewayRefundVerification.Outcome.UNKNOWN);
        assertThat(absent.observation().refund()).isNull();assertThat(absent.verification().success()).isEmpty();
        assertThat(owner.queryForObject("SELECT refund_reference IS NULL AND amount IS NULL AND refund_status IS NULL FROM fee_gateway_refund_reads WHERE id=?",Boolean.class,absent.id())).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(5);
        assertThat(refundReadProtectedState(test.fixture())).isEqualTo(before);assertThat(refundPayment(test.fixture(),test.fixture().admin(),test.paymentId()).reserved()).isEqualByComparingTo("50.25");
    }

    @Test void refundReadFuturePreApprovalWrongReferenceAndAmountMismatchRemainReviewWithoutSuccess() {
        var test=refundReadApproved();var expected=refundReadPrepare(test).expected();var before=refundReadProtectedState(test.fixture());
        Instant valid=expected.approvedAt().plusNanos(123),future=familyGatewayNow().plus(Duration.ofDays(1));
        var source=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,valid);
        var cases=List.of(refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,future),
                refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().minusNanos(1)),
                new PaymentProviderAdapter.RefundObservation("synthetic_wrong_refund",source.orderReference(),source.paymentReference(),source.amount(),"INR",source.status(),valid),
                new PaymentProviderAdapter.RefundObservation(source.refundReference(),"synthetic_wrong_order",source.paymentReference(),source.amount(),"INR",source.status(),valid),
                new PaymentProviderAdapter.RefundObservation(source.refundReference(),source.orderReference(),"synthetic_wrong_payment",source.amount(),"INR",source.status(),valid),
                new PaymentProviderAdapter.RefundObservation(source.refundReference(),source.orderReference(),source.paymentReference(),source.amount().add(new BigDecimal("0.01")),"INR",source.status(),valid),
                new PaymentProviderAdapter.RefundObservation(source.refundReference(),source.orderReference(),source.paymentReference(),source.amount(),"USD",source.status(),valid),
                refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,null));
        for(var facts:cases) {var retained=refundReadRetain(test,UUID.randomUUID(),facts);
            assertThat(retained.verification().outcome()).isEqualTo(GatewayRefundVerification.Outcome.REVIEW_REQUIRED);assertThat(retained.verification().success()).isEmpty();
            assertThat(retained.observation().refund()).isEqualTo(facts);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=? AND outcome='REVIEW_REQUIRED' AND processed_on IS NULL",Integer.class,test.refundId())).isEqualTo(cases.size());
        assertThat(refundReadProtectedState(test.fixture())).isEqualTo(before);
    }

    @Test void refundReadDirectRuntimeScopeForeignApprovalAndAppendOnlyGuardsPreservePersistedWitness() {
        var test=refundReadApproved();var f=test.fixture();var expected=refundReadPrepare(test).expected();
        var retained=refundReadRetain(test,UUID.randomUUID(),refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123)));
        String original=owner.queryForObject("SELECT row_to_json(witness)::text FROM fee_gateway_refund_reads witness WHERE id=?",String.class,retained.id());
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads",Integer.class)).isZero();
        assertThat(familyGatewayBackground(f,()->runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE id=?",Integer.class,retained.id()))).isEqualTo(1);
        var foreign=refundReadApproved();
        assertThat(familyGatewayBackground(foreign.fixture(),()->runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE id=?",Integer.class,retained.id()))).isZero();
        assertThat(familyGatewayBackground(f,()->runtime.update("UPDATE fee_gateway_refund_reads SET outcome=outcome WHERE id=?",retained.id()))).isZero();
        assertThatThrownBy(()->familyGatewayBackground(f,()->runtime.update("DELETE FROM fee_gateway_refund_reads WHERE id=?",retained.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("UPDATE fee_gateway_refund_reads SET outcome=outcome WHERE id=?",retained.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("DELETE FROM fee_gateway_refund_reads WHERE id=?",retained.id())).isInstanceOf(DataAccessException.class);
        UUID foreignApproval=refundReadPrepare(foreign).approvalDecisionId();
        assertThatThrownBy(()->familyGatewayBackground(f,()->rawRefundReadCopy(retained.id(),UUID.randomUUID(),java.util.Map.of("approval_decision_id","'"+foreignApproval+"'::uuid")))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefundReadCopy(retained.id(),UUID.randomUUID(),java.util.Map.of()))).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='fee_gateway_refund_reads'::regclass AND confrelid='fee_gateway_refund_decisions'::regclass AND contype='f'",Integer.class)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT row_to_json(witness)::text FROM fee_gateway_refund_reads witness WHERE id=?",String.class,retained.id())).isEqualTo(original);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(1);
    }

    @Test void refundReadDirectMalformedExactFloorSchoolDateHashAndOutcomeProofCannotForgeSuccess() {
        var test=refundReadApproved();var expected=refundReadPrepare(test).expected();var before=refundReadProtectedState(test.fixture());
        var retained=refundReadRetain(test,UUID.randomUUID(),refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123)));
        for(var overrides:List.of(java.util.Map.of("processed_at_exact","'not-canonical'"),java.util.Map.of("processed_at","processed_at + interval '1 microsecond'"),
                java.util.Map.of("processed_on","processed_on + 1"),java.util.Map.of("evidence_sha256","'not-a-hash'"),java.util.Map.of("outcome","'UNKNOWN'"),
                java.util.Map.of("processed_at","NULL","processed_at_exact","NULL"),java.util.Map.of("observed_at","'1900-01-01T00:00:00Z'::timestamptz"),
                java.util.Map.of("refund_reference","'synthetic_forged_success'")))
            assertThatThrownBy(()->familyGatewayBackground(test.fixture(),()->rawRefundReadCopy(retained.id(),UUID.randomUUID(),overrides))).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(1);
        assertThat(refundReadProtectedState(test.fixture())).isEqualTo(before);
    }

    @Test void refundReadWholeTransactionRollbackAndMalformedTypedFactsLeaveNoWitnessOrFinancialPartialState() {
        var test=refundReadApproved();var expected=refundReadPrepare(test).expected();var before=refundReadProtectedState(test.fixture());UUID id=UUID.randomUUID();
        var source=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123));
        assertThatThrownBy(()->familyGatewayBackground(test.fixture(),()->{refundReadStore().retain(familyGatewayBinding(test.fixture()),test.refundId(),id,source);throw new IllegalStateException("Synthetic refund read rollback");}))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic refund read rollback");
        for(var malformed:List.of(new PaymentProviderAdapter.RefundObservation("bad reference",source.orderReference(),source.paymentReference(),source.amount(),"INR",source.status(),source.processedAt()),
                new PaymentProviderAdapter.RefundObservation(source.refundReference(),source.orderReference(),source.paymentReference(),new BigDecimal("1.001"),"INR",source.status(),source.processedAt()),
                new PaymentProviderAdapter.RefundObservation(source.refundReference(),source.orderReference(),source.paymentReference(),source.amount(),"INR",null,source.processedAt())))
            assertThatThrownBy(()->refundReadRetain(test,UUID.randomUUID(),malformed)).isInstanceOf(IllegalArgumentException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isZero();
        assertThat(refundReadProtectedState(test.fixture())).isEqualTo(before);assertThat(refundPayment(test.fixture(),test.fixture().admin(),test.paymentId()).reserved()).isEqualByComparingTo("50.25");
    }

    @Test void refundReadSchoolDateUsesJavaIsoOffsetSemanticsAndNamedZones() {
        var instants=List.of(Instant.parse("2026-10-17T23:59:59.999999999Z"),Instant.parse("2026-10-18T00:00:00.000000001Z"),
                Instant.parse("2026-10-17T18:29:59.999999999Z"),Instant.parse("2026-10-17T18:30:00.000000001Z"),
                Instant.parse("2026-10-17T05:29:59.999999999Z"),Instant.parse("2026-10-17T05:30:00.000000001Z"),
                Instant.parse("2026-10-17T18:29:44.999999999Z"),Instant.parse("2026-10-17T18:29:45.000000001Z"));
        for(String zone:List.of("Asia/Kolkata","UTC","UTC+05:30","GMT-05:30","UT+05:30","+05:30","-18:00","Z","+05:30:45","+05:30:15","UTC+05:30:15","Etc/GMT+5"))
            for(Instant source:instants) {
                LocalDate expected=source.atZone(java.time.ZoneId.of(zone)).toLocalDate();
                LocalDate actual=familyGatewayBackground(refundReadForwardFixture,()->runtime.queryForObject(
                        "SELECT gateway_refund_school_date(?,?)",LocalDate.class,GatewayExactInstant.canonical(source),zone));
                assertThat(actual).as("Java school-date semantics for %s at %s",zone,source).isEqualTo(expected);
            }
    }

    private RefundReadFixture refundSuccessForwardFixture;
    private GatewayRefundReadStore.Read refundSuccessForwardRead;
    private java.util.Map<String,List<String>> refundSuccessForwardProtectedState;
    private String refundSuccessForwardReadState;

    private void prepareRefundSuccessForwardFixture() {
        refundSuccessForwardFixture=refundReadApproved();
        refundSuccessForwardRead=refundSuccessRead(refundSuccessForwardFixture,123);
        refundSuccessForwardProtectedState=refundReadProtectedState(refundSuccessForwardFixture.fixture());
        refundSuccessForwardReadState=refundSuccessReadState(refundSuccessForwardRead.id());
    }

    @Test void refundSuccessPopulatedV074UpgradePreservesReadAndFinancialHistory() {
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='075'",Boolean.class)).isTrue();
        var test=refundSuccessForwardFixture;
        assertThat(refundReadProtectedState(test.fixture())).isEqualTo(refundSuccessForwardProtectedState);
        assertThat(refundSuccessReadState(refundSuccessForwardRead.id())).isEqualTo(refundSuccessForwardReadState);
        assertThat(refundSuccessCount(test)).isZero();
        var retained=refundSuccessRetain(test,refundSuccessForwardRead.id());
        assertThat(retained.verifiedReadId()).isEqualTo(refundSuccessForwardRead.id());
        assertThat(retained.status()).isEqualTo("HOLD");
        assertThat(refundReadProtectedState(test.fixture())).isEqualTo(refundSuccessForwardProtectedState);
        assertThat(refundSuccessReadState(refundSuccessForwardRead.id())).isEqualTo(refundSuccessForwardReadState);
    }

    @Test void refundSuccessHoldRetainsExactApprovedSourceWithoutChangingDuesOrAccounting() {
        var test=refundReadApproved();var f=test.fixture();var prepared=refundReadPrepare(test);var expected=prepared.expected();
        var read=refundSuccessRead(test,123);var proof=read.verification().success().orElseThrow();
        // Append-only witnesses intentionally have no UPDATE policy. A row-locking
        // SELECT therefore hides them; recognition must not expand write access.
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_policy WHERE polrelid='fee_gateway_refund_reads'::regclass AND polcmd IN ('w','*')",Integer.class)).isZero();
        var visible=familyGatewayBackground(f,()->runtime.queryForList("SELECT id FROM fee_gateway_refund_reads WHERE id=?",UUID.class,read.id()));
        var rowLocked=familyGatewayBackground(f,()->runtime.queryForList("SELECT id FROM fee_gateway_refund_reads WHERE id=? FOR SHARE",UUID.class,read.id()));
        assertThat(visible).containsExactly(read.id());assertThat(rowLocked).isEmpty();
        owner.update("UPDATE schools SET time_zone='UTC',updated_at=now() WHERE id=?",f.school());
        owner.update("UPDATE payment_provider_configs SET credentials_secret_reference='synthetic:rotated',updated_at=now(),row_version=row_version+1 WHERE id=?",f.provider());
        var before=refundReadProtectedState(f);var fees=familyFees(f);String readState=refundSuccessReadState(read.id());
        Instant beforeRecognized=familyGatewayNow();var retained=refundSuccessRetain(test,read.id());Instant afterRecognized=familyGatewayNow();
        assertThat(retained.refundId()).isEqualTo(test.refundId());assertThat(retained.verifiedReadId()).isEqualTo(read.id());
        assertThat(retained.approvalDecisionId()).isEqualTo(prepared.approvalDecisionId());
        assertThat(retained.paymentId()).isEqualTo(test.paymentId());assertThat(retained.captureId()).isEqualTo(expected.captureId());
        assertThat(retained.accountId()).isEqualTo(f.account());assertThat(retained.amount()).isEqualByComparingTo("50.25");
        assertThat(retained.currencyCode()).isEqualTo("INR");assertThat(retained.schoolTimeZone()).isEqualTo(expected.schoolTimeZone());
        assertThat(retained.processedAtExact()).isEqualTo(proof.processedAtExact());
        assertThat(retained.processedAtProjection()).isEqualTo(proof.processedAtProjection());assertThat(retained.processedOn()).isEqualTo(proof.processedOn());
        assertThat(retained.evidenceSha256()).isEqualTo(read.verification().evidenceSha256());assertThat(retained.sourceVersion()).isEqualTo(1);
        assertThat(retained.recognizedAt()).isBetween(beforeRecognized,afterRecognized);
        assertThat(retained.status()).isEqualTo("HOLD");assertThat(retained.holdReason()).isEqualTo("AWAITING_REVIEWED_POSTING");assertThat(retained.closedDayCloseId()).isNull();
        assertThat(retained.toString()).doesNotContain(test.refundId().toString(),test.paymentId().toString(),"50.25",retained.evidenceSha256());
        assertThat(owner.queryForObject("SELECT status FROM fee_payments WHERE id=?",String.class,test.paymentId())).isEqualTo("POSTED");
        assertThat(owner.queryForObject("SELECT amount FROM fee_payments WHERE id=?",BigDecimal.class,test.paymentId())).isEqualByComparingTo("200");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND entry_type='REFUND'",Integer.class,f.school())).isZero();
        assertThat(refundPayment(f,f.admin(),test.paymentId()).reserved()).isEqualByComparingTo("50.25");
        assertThat(familyFees(f)).isEqualTo(fees);assertThat(refundReadProtectedState(f)).isEqualTo(before);
        assertThat(refundSuccessReadState(read.id())).isEqualTo(readState);assertThat(refundSuccessCount(test)).isEqualTo(1);
    }

    @Test void refundSuccessClosedOriginalDayPreservesItsApprovedCloseAndNeverReopensHistory() {
        var test=refundReadApproved();var f=test.fixture();var read=refundSuccessRead(test,123);
        LocalDate processedOn=read.verification().success().orElseThrow().processedOn();UUID close=refundSuccessApproveClose(f,processedOn);
        String closeState=refundSuccessCloseState(close);var before=refundReadProtectedState(f);
        var retained=refundSuccessRetain(test,read.id());
        assertThat(retained.closedDayCloseId()).isEqualTo(close);assertThat(retained.holdReason()).isEqualTo("ORIGINAL_FINANCIAL_DAY_CLOSED");
        assertThat(retained.processedOn()).isEqualTo(processedOn);assertThat(retained.status()).isEqualTo("HOLD");
        assertThat(refundSuccessCloseState(close)).isEqualTo(closeState);assertThat(refundReadProtectedState(f)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT status FROM fee_day_closes WHERE id=?",String.class,close)).isEqualTo("APPROVED");
    }

    @Test void refundSuccessExactFreshReplayKeepsFirstReadRecognitionTimeAndClose() {
        var test=refundReadApproved();var f=test.fixture();var expected=refundReadPrepare(test).expected();var read=refundSuccessRead(test,123);
        var first=refundSuccessRetain(test,read.id());String firstState=refundSuccessState(test);assertThat(first.closedDayCloseId()).isNull();
        awaitFamilyGatewayTime(first.recognizedAt().plusMillis(5));UUID close=refundSuccessApproveClose(f,first.processedOn());
        var fresh=refundReadRetain(test,UUID.randomUUID(),read.observation().refund());
        assertThat(fresh.id()).isNotEqualTo(read.id());assertThat(fresh.observation().observedAt()).isAfter(read.observation().observedAt());
        assertThat(refundSuccessRetain(test,read.id())).isEqualTo(first);assertThat(refundSuccessRetain(test,fresh.id())).isEqualTo(first);
        refundReadRetain(test,UUID.randomUUID(),null);
        refundReadRetain(test,UUID.randomUUID(),refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.PENDING,null));
        assertThat(refundSuccessRetain(test,read.id())).isEqualTo(first);
        assertThat(refundSuccessState(test)).isEqualTo(firstState);assertThat(refundSuccessCount(test)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM fee_day_closes WHERE id=?",String.class,close)).isEqualTo("APPROVED");
    }

    @Test void refundSuccessUnknownPendingFailedReviewAndUnauthorizedSourcesCannotClaimHold() {
        var test=refundReadApproved();var f=test.fixture();var expected=refundReadPrepare(test).expected();var before=refundReadProtectedState(f);
        var invalidReads=new java.util.ArrayList<GatewayRefundReadStore.Read>();invalidReads.add(refundReadRetain(test,UUID.randomUUID(),null));
        for(var status:List.of(PaymentProviderAdapter.RefundStatus.PENDING,PaymentProviderAdapter.RefundStatus.FAILED,PaymentProviderAdapter.RefundStatus.CANCELLED,PaymentProviderAdapter.RefundStatus.ON_HOLD))
            invalidReads.add(refundReadRetain(test,UUID.randomUUID(),refundReadFacts(expected,status,null)));
        var mismatched=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123));
        invalidReads.add(refundReadRetain(test,UUID.randomUUID(),new PaymentProviderAdapter.RefundObservation(mismatched.refundReference(),mismatched.orderReference(),mismatched.paymentReference(),new BigDecimal("50.26"),"INR",mismatched.status(),mismatched.processedAt())));
        for(var read:invalidReads)assertThatThrownBy(()->refundSuccessRetain(test,read.id())).isInstanceOf(IllegalStateException.class);
        var valid=refundSuccessRead(test,123);var binding=familyGatewayBinding(f);
        assertThatThrownBy(()->refundSuccessRetain(test,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->refundSuccessStore().retain(binding,test.refundId(),valid.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->refundSuccessStore().retain(binding,test.refundId(),valid.id()))).isInstanceOf(IllegalStateException.class);
        var foreign=refundReadApproved();var foreignRead=refundSuccessRead(foreign,123);
        assertThatThrownBy(()->familyGatewayBackground(foreign.fixture(),()->refundSuccessStore().retain(binding,test.refundId(),valid.id()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->refundSuccessRetain(test,foreignRead.id())).isInstanceOf(IllegalStateException.class);
        var wrong=new GatewayWorkQueue.ProviderBinding(f.org(),f.school(),f.provider(),"CASHFREE","TEST",binding.merchantReference(),"synthetic:wrong");
        assertThatThrownBy(()->familyGatewayBackground(f,()->refundSuccessStore().retain(wrong,test.refundId(),valid.id()))).isInstanceOf(IllegalStateException.class);
        var readOnly=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));readOnly.setReadOnly(true);
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,f.org())) {
            assertThatThrownBy(()->readOnly.execute(status->refundSuccessStore().retain(binding,test.refundId(),valid.id()))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(refundSuccessCount(test)).isZero();assertThat(refundSuccessCount(foreign)).isZero();assertThat(refundReadProtectedState(f)).isEqualTo(before);
    }

    @Test void refundSuccessConflictingSuccessfulReadsRemainDurableAndBlockRecognitionAndReplay() {
        for(boolean anchored:List.of(false,true)) {
            var test=refundReadApproved();var f=test.fixture();var first=refundSuccessRead(test,123);String anchor=null;
            if(anchored){refundSuccessRetain(test,first.id());anchor=refundSuccessState(test);}
            var before=refundReadProtectedState(f);var second=refundSuccessRead(test,124);
            assertThat(second.verification().outcome()).isEqualTo(GatewayRefundVerification.Outcome.VERIFIED_SUCCESS);
            assertThat(second.verification().evidenceSha256()).isNotEqualTo(first.verification().evidenceSha256());
            assertThat(second.verification().success().orElseThrow().processedAtProjection()).isEqualTo(first.verification().success().orElseThrow().processedAtProjection());
            assertThatThrownBy(()->refundSuccessRetain(test,second.id())).isInstanceOf(GatewayRefundSuccessStore.ConflictingSuccessEvidenceException.class);
            assertThatThrownBy(()->refundSuccessRetain(test,first.id())).isInstanceOf(GatewayRefundSuccessStore.ConflictingSuccessEvidenceException.class);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=? AND outcome='VERIFIED_SUCCESS'",Integer.class,test.refundId())).isEqualTo(2);
            assertThat(refundSuccessCount(test)).isEqualTo(anchored?1:0);if(anchored)assertThat(refundSuccessState(test)).isEqualTo(anchor);
            assertThat(refundReadProtectedState(f)).isEqualTo(before);
        }
    }

    @Test void refundSuccessDirectSqlWrongSourceAndAppendOnlyGuardsCannotForgeOrReplaceHold() {
        var test=refundReadApproved();var f=test.fixture();var read=refundSuccessRead(test,123);refundSuccessRetain(test,read.id());
        String original=refundSuccessState(test);var before=refundReadProtectedState(f);var foreign=refundReadApproved();var foreignRead=refundSuccessRead(foreign,123);
        assertThat(familyGatewayBackground(f,()->rawRefundSuccessCopy(test.refundId(),java.util.Map.of()))).isZero();
        for(var changes:List.of(java.util.Map.of("amount","amount+0.01"),java.util.Map.of("currency_code","'USD'"),
                java.util.Map.of("processed_at_exact","'not-canonical'"),java.util.Map.of("processed_at","processed_at+interval '1 microsecond'"),
                java.util.Map.of("processed_on","processed_on+1"),java.util.Map.of("source_version","2"),java.util.Map.of("status","'POSTED'"),
                java.util.Map.of("evidence_sha256","repeat('d',64)"),java.util.Map.of("school_time_zone","'UTC'"),
                java.util.Map.of("payment_reference","'synthetic_forged_payment'"),java.util.Map.of("credentials_secret_reference","'synthetic:wrong'"),
                java.util.Map.of("verified_read_id","'"+foreignRead.id()+"'::uuid"),
                java.util.Map.of("approval_decision_id","'"+refundReadPrepare(foreign).approvalDecisionId()+"'::uuid"),
                java.util.Map.of("fee_payment_id","'"+foreign.paymentId()+"'::uuid")))
            assertThatThrownBy(()->familyGatewayBackground(f,()->rawRefundSuccessCopy(test.refundId(),changes))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawRefundSuccessCopy(test.refundId(),java.util.Map.of()))).isInstanceOf(DataAccessException.class);
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_successes",Integer.class)).isZero();
        assertThat(familyGatewayBackground(foreign.fixture(),()->runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_successes WHERE refund_request_id=?",Integer.class,test.refundId()))).isZero();
        assertThat(familyGatewayBackground(f,()->runtime.update("UPDATE fee_gateway_refund_successes SET status=status WHERE refund_request_id=?",test.refundId()))).isZero();
        assertThatThrownBy(()->familyGatewayBackground(f,()->runtime.update("DELETE FROM fee_gateway_refund_successes WHERE refund_request_id=?",test.refundId()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("UPDATE fee_gateway_refund_successes SET status=status WHERE refund_request_id=?",test.refundId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("DELETE FROM fee_gateway_refund_successes WHERE refund_request_id=?",test.refundId())).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='fee_gateway_refund_successes'::regclass",Boolean.class)).isTrue();
        assertThat(refundSuccessState(test)).isEqualTo(original);assertThat(refundReadProtectedState(f)).isEqualTo(before);
    }

    @Test void refundSuccessRollbackLeavesNoAnchorOrFinancialPartialState() {
        var test=refundReadApproved();var f=test.fixture();var read=refundSuccessRead(test,123);var before=refundReadProtectedState(f);
        String witness=refundSuccessReadState(read.id());
        assertThatThrownBy(()->familyGatewayBackground(f,()->{refundSuccessStore().retain(familyGatewayBinding(f),test.refundId(),read.id());throw new IllegalStateException("Synthetic refund success rollback");}))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic refund success rollback");
        assertThat(refundSuccessCount(test)).isZero();assertThat(refundSuccessReadState(read.id())).isEqualTo(witness);
        assertThat(refundReadProtectedState(f)).isEqualTo(before);assertThat(refundSuccessRetain(test,read.id()).status()).isEqualTo("HOLD");
        assertThat(refundSuccessCount(test)).isEqualTo(1);assertThat(refundReadProtectedState(f)).isEqualTo(before);
    }

    @Test void refundSuccessConcurrentRecognitionsAndStaleSnapshotsCannotDuplicateOrMissConflictingReads() throws Exception {
        var test=refundReadApproved();var read=refundSuccessRead(test,123);var before=refundReadProtectedState(test.fixture());
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var one=workers.submit(()->concurrentRefundSuccess(test,read.id(),ready,start));
            var two=workers.submit(()->concurrentRefundSuccess(test,read.id(),ready,start));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(one.get(20,TimeUnit.SECONDS)).isEqualTo(two.get(20,TimeUnit.SECONDS));
        }
        assertThat(refundSuccessCount(test)).isEqualTo(1);assertThat(refundReadProtectedState(test.fixture())).isEqualTo(before);
        for(int isolation:List.of(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,
                org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE))for(boolean directSql:List.of(false,true)) {
            var stale=refundReadApproved();var first=refundSuccessRead(stale,123);refundSuccessRetain(stale,first.id());
            String original=refundSuccessState(stale);var protectedState=refundReadProtectedState(stale.fixture());
            var snapshot=new CountDownLatch(1);var proceed=new CountDownLatch(1);
            try(var worker=Executors.newSingleThreadExecutor()) {
                var attempted=worker.submit(()->staleRefundSuccess(stale,first.id(),isolation,directSql,snapshot,proceed));
                assertThat(snapshot.await(10,TimeUnit.SECONDS)).isTrue();var contradictory=refundSuccessRead(stale,124);
                assertThat(contradictory.verification().outcome()).isEqualTo(GatewayRefundVerification.Outcome.VERIFIED_SUCCESS);
                proceed.countDown();assertThat(attempted.get(20,TimeUnit.SECONDS)).isEqualTo("40001");
            }
            assertThatThrownBy(()->refundSuccessRetain(stale,first.id())).isInstanceOf(GatewayRefundSuccessStore.ConflictingSuccessEvidenceException.class);
            assertThatThrownBy(()->familyGatewayBackground(stale.fixture(),()->rawRefundSuccessCopy(stale.refundId(),java.util.Map.of()))).isInstanceOf(DataAccessException.class);
            assertThat(refundSuccessState(stale)).isEqualTo(original);assertThat(refundSuccessCount(stale)).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,stale.refundId())).isEqualTo(2);
            assertThat(refundReadProtectedState(stale.fixture())).isEqualTo(protectedState);
        }
        // A close is school-wide and deliberately does not touch each capture
        // epoch. Its commit after an old MVCC snapshot must nevertheless never
        // produce an anchor claiming that the original day was still open.
        for(int isolation:List.of(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,
                org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            var stale=refundReadApproved();var first=refundSuccessRead(stale,123);var snapshot=new CountDownLatch(1);var proceed=new CountDownLatch(1);
            String witness=refundSuccessReadState(first.id());UUID close;
            try(var worker=Executors.newSingleThreadExecutor()) {
                var attempted=worker.submit(()->staleRefundSuccess(stale,first.id(),isolation,false,snapshot,proceed));
                assertThat(snapshot.await(10,TimeUnit.SECONDS)).isTrue();
                close=refundSuccessApproveClose(stale.fixture(),first.verification().success().orElseThrow().processedOn());
                proceed.countDown();assertThat(attempted.get(20,TimeUnit.SECONDS)).isEqualTo("40001");
            }
            assertThat(refundSuccessCount(stale)).isZero();String closeState=refundSuccessCloseState(close);
            var protectedState=refundReadProtectedState(stale.fixture());var fresh=refundSuccessRetain(stale,first.id());
            assertThat(fresh.closedDayCloseId()).isEqualTo(close);assertThat(fresh.holdReason()).isEqualTo("ORIGINAL_FINANCIAL_DAY_CLOSED");
            assertThat(refundSuccessCount(stale)).isEqualTo(1);assertThat(refundSuccessCloseState(close)).isEqualTo(closeState);
            assertThat(refundSuccessReadState(first.id())).isEqualTo(witness);assertThat(refundReadProtectedState(stale.fixture())).isEqualTo(protectedState);
        }
    }

    private GatewayRefundSuccessStore refundSuccessStore() {return new GatewayRefundSuccessStore(new NamedParameterJdbcTemplate(runtime),refundReadStore());}
    private GatewayRefundReadStore.Read refundSuccessRead(RefundReadFixture test,int nanos) {
        var expected=refundReadPrepare(test).expected();return refundReadRetain(test,UUID.randomUUID(),refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(nanos)));
    }
    private GatewayRefundSuccessStore.Success refundSuccessRetain(RefundReadFixture test,UUID readId) {
        return familyGatewayBackground(test.fixture(),()->refundSuccessStore().retain(familyGatewayBinding(test.fixture()),test.refundId(),readId));
    }
    private int refundSuccessCount(RefundReadFixture test) {return owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_successes WHERE refund_request_id=?",Integer.class,test.refundId());}
    private String refundSuccessState(RefundReadFixture test) {return owner.queryForObject("SELECT row_to_json(success)::text FROM fee_gateway_refund_successes success WHERE refund_request_id=?",String.class,test.refundId());}
    private String refundSuccessReadState(UUID readId) {return owner.queryForObject("SELECT row_to_json(witness)::text FROM fee_gateway_refund_reads witness WHERE id=?",String.class,readId);}
    private String refundSuccessCloseState(UUID closeId) {return owner.queryForObject("SELECT row_to_json(close_record)::text FROM fee_day_closes close_record WHERE id=?",String.class,closeId);}
    private UUID refundSuccessApproveClose(Fixture f,LocalDate date) {
        var declarations=register(f,date,date,null,null,0,25).tenderTotals().stream().map(tender->new FinanceController.TenderDeclaration(tender.paymentMethod(),tender.summary().netCollections())).toList();
        var close=actorScoped(f.admin(),()->finance.proposeDayClose(f.admin(),new FinanceController.DayCloseRequest(f.school(),date,declarations,"Synthetic refund recognition close",null)));
        actorScoped(f.reviewer(),()->finance.decideDayClose(f.reviewer(),close.id(),new FinanceController.DayCloseDecisionRequest(f.school(),"APPROVED","Synthetic independent refund recognition close",0)));return close.id();
    }
    /** ON CONFLICT is essential: invalid copies must fail source guards, not a duplicate key. */
    private int rawRefundSuccessCopy(UUID source,java.util.Map<String,String> overrides) {
        var columns=List.of("refund_request_id","organisation_id","school_id","verified_read_id","approval_decision_id","fee_payment_id","capture_id","learner_fee_account_id",
                "payment_provider_config_id","provider_code","provider_environment","merchant_account_reference","credentials_secret_reference","refund_reference","order_reference","payment_reference",
                "amount","currency_code","school_time_zone","processed_at","processed_at_exact","processed_on","source_version","evidence_sha256","recognized_at","closed_day_close_id","status","hold_reason");
        if(!columns.containsAll(overrides.keySet()))throw new IllegalArgumentException("Only fixed synthetic success columns may be overridden");
        var selected=columns.stream().map(column->overrides.getOrDefault(column,column)).collect(java.util.stream.Collectors.joining(","));
        return runtime.update("INSERT INTO fee_gateway_refund_successes("+String.join(",",columns)+") SELECT "+selected+" FROM fee_gateway_refund_successes WHERE refund_request_id=? ON CONFLICT DO NOTHING",source);
    }
    private GatewayRefundSuccessStore.Success concurrentRefundSuccess(RefundReadFixture test,UUID readId,CountDownLatch ready,CountDownLatch start) {
        return familyGatewayBackground(test.fixture(),()->{
            assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_successes WHERE refund_request_id=?",Integer.class,test.refundId())).isZero();
            ready.countDown();refundSuccessAwait(start);return refundSuccessStore().retain(familyGatewayBinding(test.fixture()),test.refundId(),readId);
        });
    }
    private String staleRefundSuccess(RefundReadFixture test,UUID readId,int isolation,boolean directSql,CountDownLatch ready,CountDownLatch start) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));tx.setTimeout(20);tx.setIsolationLevel(isolation);
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,test.fixture().org())) {
            try {
                tx.execute(status->{
                    assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(1);
                    ready.countDown();refundSuccessAwait(start);
                    if(directSql)rawRefundSuccessCopy(test.refundId(),java.util.Map.of());
                    else refundSuccessStore().retain(familyGatewayBinding(test.fixture()),test.refundId(),readId);
                    return true;
                });return "UNEXPECTED_SUCCESS";
            }catch(DataAccessException rejected){
                for(Throwable cause=rejected;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql)return sql.getSQLState();
                throw rejected;
            }
        }
    }
    private static void refundSuccessAwait(CountDownLatch start) {
        try{if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic refund recognition barrier timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }


    @Test void migrationPreservesRuntimeIsolationAndDoesNotActivateOrPostAnything() {
        ProductionDatabaseSecurity.verify(runtime);
        assertThat(owner.queryForList("""
                SELECT relname FROM pg_class WHERE relname LIKE 'fee_gateway_%' AND relkind='r'
                  AND relrowsecurity
                """, String.class)).containsExactlyInAnyOrder("fee_gateway_attempts", "fee_gateway_allocations",
                "fee_gateway_checkout_observations", "fee_gateway_captures", "fee_gateway_capture_resolutions",
                "fee_gateway_attempt_work", "fee_gateway_webhook_inbox", "fee_gateway_payment_observations",
                "fee_gateway_hosted_checkouts", "fee_gateway_refund_requests", "fee_gateway_refund_allocations",
                "fee_gateway_refund_decisions", "fee_gateway_refund_fences", "fee_gateway_refund_reads", "fee_gateway_refund_successes", "fee_gateway_refund_postings", "fee_gateway_refund_work", "fee_gateway_refund_dispatches",
                "fee_gateway_settlement_queries", "fee_gateway_settlement_reads", "fee_gateway_settlement_requests",
                "fee_gateway_settlement_work", "fee_gateway_settlement_selections", "fee_gateway_settlement_bank_evidence",
                "fee_gateway_settlement_bank_evidence_rejections", "fee_gateway_settlement_roots", "fee_gateway_settlement_preparations",
                "fee_gateway_settlement_sources", "fee_gateway_settlement_source_rows", "fee_gateway_settlement_components",
                "fee_gateway_settlement_abandonments");
        // Existing tables use the verified separate non-owner/no-BYPASSRLS runtime.
        // New gateway/refund/settlement migrations additionally force RLS on their tables; never infer that
        // historical migrations already set this distinct owner-only flag.
        assertThat(owner.queryForList("""
                SELECT relname FROM pg_class WHERE relname IN ('fee_gateway_attempt_work',
                  'fee_gateway_webhook_inbox','fee_gateway_payment_observations',
                  'fee_gateway_hosted_checkouts','fee_gateway_refund_requests','fee_gateway_refund_allocations',
                  'fee_gateway_refund_decisions','fee_gateway_refund_fences','fee_gateway_refund_reads','fee_gateway_refund_successes','fee_gateway_refund_postings','fee_gateway_refund_work','fee_gateway_refund_dispatches',
                  'fee_gateway_settlement_queries','fee_gateway_settlement_reads','fee_gateway_settlement_requests',
                  'fee_gateway_settlement_work','fee_gateway_settlement_selections','fee_gateway_settlement_bank_evidence',
                  'fee_gateway_settlement_bank_evidence_rejections','fee_gateway_settlement_roots','fee_gateway_settlement_preparations',
                  'fee_gateway_settlement_sources','fee_gateway_settlement_source_rows','fee_gateway_settlement_components',
                  'fee_gateway_settlement_abandonments') AND relkind='r'
                  AND relrowsecurity AND relforcerowsecurity
                """, String.class)).containsExactlyInAnyOrder("fee_gateway_attempt_work",
                "fee_gateway_webhook_inbox", "fee_gateway_payment_observations", "fee_gateway_hosted_checkouts",
                "fee_gateway_refund_requests", "fee_gateway_refund_allocations", "fee_gateway_refund_decisions", "fee_gateway_refund_fences", "fee_gateway_refund_reads", "fee_gateway_refund_successes", "fee_gateway_refund_postings", "fee_gateway_refund_work", "fee_gateway_refund_dispatches",
                "fee_gateway_settlement_queries", "fee_gateway_settlement_reads", "fee_gateway_settlement_requests",
                "fee_gateway_settlement_work", "fee_gateway_settlement_selections", "fee_gateway_settlement_bank_evidence",
                "fee_gateway_settlement_bank_evidence_rejections", "fee_gateway_settlement_roots", "fee_gateway_settlement_preparations",
                "fee_gateway_settlement_sources", "fee_gateway_settlement_source_rows", "fee_gateway_settlement_components",
                "fee_gateway_settlement_abandonments");
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_attempts", Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_postings", Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_work",Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_dispatches",Integer.class)).isZero();
    }

    @Test void manualAccountLookupUsesOnlyEffectiveScopedPlacementAndPreservesTheLedger() {
        Fixture f = fixture(true), other = fixture(true);
        var before = actorScoped(f.admin(), () -> finance.snapshot(f.admin(), f.school())).accounts().getFirst();
        assertThat(before.rollNumber()).isNull();
        assertThat(before.sectionCode()).isNull();
        place(f, LocalDate.now(clock).minusDays(1), false);
        var snapshot = actorScoped(f.admin(), () -> finance.snapshot(f.admin(), f.school()));
        assertThat(snapshot.accounts()).hasSize(1);
        var found = snapshot.accounts().getFirst();
        assertThat(found.id()).isEqualTo(f.account());
        assertThat(found.rollNumber()).isEqualTo("017A");
        assertThat(found.sectionCode()).isEqualTo("B");
        assertThat(found.balance()).isEqualByComparingTo(before.balance());
        assertThat(found.totalCharges()).isEqualByComparingTo(before.totalCharges());
        assertThat(found.totalCredits()).isEqualByComparingTo(before.totalCredits());
        assertThat(found.charges()).isEqualTo(before.charges());
        assertThat(found.ledger()).isEqualTo(before.ledger());
        assertThat(found.payments()).isEqualTo(before.payments());
        assertThatThrownBy(() -> actorScoped(f.admin(), () -> finance.snapshot(f.admin(), other.school())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(() -> actorScoped(f.parent(), () -> finance.snapshot(f.parent(), f.school())))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(runtime.queryForObject("SELECT count(*) FROM learner_fee_accounts", Integer.class)).isZero();
        // The lookup itself cannot allocate money, post a receipt or change the existing charge ledger.
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?", Integer.class, f.account())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE learner_fee_account_id=?", Integer.class, f.account())).isEqualTo(1);
    }

    @Test void manualAccountLookupDoesNotBorrowAnEndedFutureOrDifferentSessionRoll() {
        Fixture f = fixture(true);
        LocalDate schoolToday = actorScoped(f.admin(), () -> finance.snapshot(f.admin(), f.school())).schoolToday();
        UUID future = place(f, schoolToday.plusDays(1), false);
        var futureSnapshot = actorScoped(f.admin(), () -> finance.snapshot(f.admin(), f.school())).accounts().getFirst();
        assertThat(futureSnapshot.rollNumber()).isNull();
        assertThat(futureSnapshot.sectionCode()).isNull();
        owner.update("UPDATE student_section_placements SET status='ENDED',ends_on=starts_on,ended_by_membership_id=?,end_reason='Synthetic ended placement',updated_at=now(),row_version=row_version+1 WHERE id=?",
                f.admin().membershipIds().iterator().next(), future);
        assertThat(actorScoped(f.admin(), () -> finance.snapshot(f.admin(), f.school())).accounts().getFirst().rollNumber()).isNull();
        place(f, schoolToday.minusDays(1), true);
        var differentSession = actorScoped(f.admin(), () -> finance.snapshot(f.admin(), f.school())).accounts().getFirst();
        assertThat(differentSession.rollNumber()).isNull();
        assertThat(differentSession.sectionCode()).isNull();
        assertThat(differentSession.balance()).isEqualByComparingTo("1000.00");
    }

    @Test void familyFeeStatementsRejectStaleUserSchoolAndOrganisationAuthority() {
        Fixture f = fixture(true);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("1000.00");
        owner.update("UPDATE user_accounts SET status='SUSPENDED' WHERE id=?", f.parent().userId());
        assertThatThrownBy(() -> familyFees(f)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("UPDATE user_accounts SET status='ACTIVE' WHERE id=?", f.parent().userId());
        assertThat(familyFees(f).balance()).isEqualByComparingTo("1000.00");
        owner.update("UPDATE schools SET status='SUSPENDED' WHERE id=?", f.school());
        assertThatThrownBy(() -> familyFees(f)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("UPDATE schools SET status='ACTIVE' WHERE id=?", f.school());
        assertThat(familyFees(f).balance()).isEqualByComparingTo("1000.00");
        owner.update("UPDATE organisations SET status='SUSPENDED' WHERE id=?", f.org());
        assertThatThrownBy(() -> familyFees(f)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("UPDATE organisations SET status='ACTIVE' WHERE id=?", f.org());
        assertThat(familyFees(f).balance()).isEqualByComparingTo("1000.00");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE learner_fee_account_id=?", Integer.class, f.account())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?", Integer.class, f.account())).isZero();
    }

    @Test void exactIntentRetryDoesNotDuplicateReserveOrPretendTheFeeIsPaid() {
        Fixture f = fixture(true);
        var request = request(f, "600.00");
        var first = prepare(f, request);
        assertThat(prepare(f, request)).isEqualTo(first);
        assertThat(first.status()).isEqualTo("RESERVED");
        assertThat(first.amount()).isEqualByComparingTo("600.00");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE learner_fee_account_id=?", Integer.class, f.account())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?", Integer.class, f.account())).isZero();
        assertThat(owner.queryForObject("SELECT sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) FROM fee_ledger_entries WHERE learner_fee_account_id=?", BigDecimal.class, f.account())).isEqualByComparingTo("1000.00");
        assertThat(owner.queryForObject("SELECT payer_phone_ciphertext FROM fee_gateway_attempts WHERE id=?", String.class, first.id())).doesNotContain("0000000000");
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE subject_id=? AND action='finance.gateway.intent.reserved'", Integer.class, first.id().toString())).isEqualTo(1);
        assertThatThrownBy(() -> prepare(f, new GatewayReservationService.PrepareRequest(request.clientRequestId(), List.of(selection(f, "500")), "0000000000"))).hasMessageContaining("different payment terms");
        assertThatThrownBy(() -> prepare(f, new GatewayReservationService.PrepareRequest(request.clientRequestId(), request.allocations(), "0000000001"))).hasMessageContaining("different payment terms");
    }

    @Test void newWholeSecondExpiryRetainsCreationPrecisionAndRetryIdentityWithoutWeakeningProviderOrFamilyChecks() {
        Fixture f=fixture(true),other=fixture(true);
        Instant created=owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(1).plusNanos(123456000);
        var intentRequest=request(f,"500.00");
        // Only metadata deadline selection uses a controlled near-current clock;
        // no lease, CREATE dispatch, payment, financial date or historical event is fabricated.
        var first=prepareAt(f,intentRequest,Clock.fixed(created,java.time.ZoneOffset.UTC));
        assertThat(first.expiresAt()).isEqualTo(created.plusSeconds(1800).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(owner.queryForObject("SELECT created_at FROM fee_gateway_attempts WHERE id=?",java.sql.Timestamp.class,first.id()).toInstant()).isEqualTo(created);
        String before=owner.queryForObject("SELECT row_to_json(attempt)::text FROM fee_gateway_attempts attempt WHERE id=?",String.class,first.id());
        assertThat(prepare(f,intentRequest)).isEqualTo(first);
        assertThat(owner.queryForObject("SELECT row_to_json(attempt)::text FROM fee_gateway_attempts attempt WHERE id=?",String.class,first.id())).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE subject_id=? AND action='finance.gateway.intent.reserved'",Integer.class,first.id().toString())).isEqualTo(1);
        assertFamilyPaymentMissing(()->scoped(other,()->reservations.prepare(other.parent(),f.learner(),intentRequest)));
        Instant observed=owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
        assertThatThrownBy(()->scoped(f,()->runtime.update("""
                INSERT INTO fee_gateway_checkout_observations(id,organisation_id,school_id,attempt_id,payment_provider_config_id,
                  provider_checkout_reference,attempt_reference,amount,currency_code,amount_paid,checkout_status,provider_expires_at,
                  order_references,pending_payment_count,successful_payment_count,discovery_complete,evidence_sha256,observed_at,observed_at_exact)
                VALUES(?,?,?,?,?,?,?,?,'INR',0,'CREATED',?,ARRAY[]::varchar[],0,0,true,repeat('e',64),?,?)
                """,UUID.randomUUID(),f.org(),f.school(),first.id(),f.provider(),first.id(),first.id(),first.amount(),
                GatewayExactInstant.parameter(first.expiresAt().plusNanos(1000)),GatewayExactInstant.parameter(observed),GatewayExactInstant.canonical(observed))))
                .isInstanceOf(DataAccessException.class).satisfies(failure->assertThat(((DataAccessException)failure).getMostSpecificCause().getMessage())
                    .contains("gateway observation must match immutable checkout"));
        observation(f,first,"CREATED","0",0,0);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?",String.class,first.id())).isEqualTo("ACTIVE");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isZero();
        assertThat(owner.queryForObject("SELECT row_to_json(attempt)::text FROM fee_gateway_attempts attempt WHERE id=?",String.class,first.id())).isEqualTo(before);
    }

    @Test void ownChildLiveRelationshipAndUnreservedAmountsAreRequired() {
        Fixture f = fixture(true), other = fixture(true);
        assertFamilyPaymentMissing(() -> scoped(f, () -> reservations.prepare(f.parent(), other.learner(), request(other, "10"))));
        prepare(f, request(f, "800"));
        assertThatThrownBy(() -> prepare(f, request(f, "201"))).hasMessageContaining("already paid or reserved");
        owner.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE learner_id=?", f.learner());
        assertFamilyPaymentMissing(() -> prepare(f, request(f, "1")));
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_attempts", Integer.class)).isZero();
        assertThat(scoped(other, () -> runtime.queryForObject("SELECT count(*) FROM fee_gateway_attempts", Integer.class))).isZero();
    }

    @Test void partialRulesAndImmutableIdentitySurviveDirectDatabaseWrites() {
        Fixture f = fixture(false);
        assertThatThrownBy(() -> prepare(f, request(f, "500"))).hasMessageContaining("paid in full");
        var attempt = prepare(f, request(f, "1000"));
        assertThatThrownBy(() -> scoped(f, () -> runtime.update("UPDATE fee_gateway_attempts SET amount=999 WHERE id=?", attempt.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> scoped(f, () -> runtime.update("UPDATE fee_gateway_attempts SET original_business_date=original_business_date+1 WHERE id=?", attempt.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> scoped(f, () -> runtime.update("DELETE FROM fee_gateway_attempts WHERE id=?", attempt.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> scoped(f, () -> runtime.update("UPDATE fee_gateway_allocations SET status='RELEASED',row_version=row_version+1 WHERE attempt_id=?", attempt.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> scoped(f, () -> runtime.update("UPDATE payment_provider_configs SET merchant_account_reference='other_merchant' WHERE id=?", f.provider()))).isInstanceOf(DataAccessException.class);
    }

    @Test void manualCollectionsAndConcessionApprovalCannotSpendActiveOnlineReservations() {
        Fixture f = fixture(true);
        var concession = scoped(f, () -> finance.requestConcession(f.admin(), new FinanceController.ConcessionRequest(f.school(), f.account(), f.charge(), "OTHER", new BigDecimal("500"), "Synthetic independent request")));
        prepare(f, request(f, "800"));
        assertThatThrownBy(() -> actorScoped(f.admin(), () -> finance.recordPayment(f.admin(), "synthetic-blocked-collection", payment(f, "201")))).hasMessageContaining("current account balance");
        var cash = actorScoped(f.admin(), () -> finance.recordPayment(f.admin(), "synthetic-open-collection", payment(f, "200")));
        assertThat(cash.receiptNumber()).isNotBlank();
        assertThat(actorScoped(f.admin(), () -> finance.recordPayment(f.admin(), "synthetic-open-collection", payment(f, "200"))).id()).isEqualTo(cash.id());
        assertThatThrownBy(() -> scoped(f, () -> finance.decideConcession(f.reviewer(), concession.id(), new FinanceController.ConcessionDecisionRequest(f.school(), "APPROVED", "Synthetic independent approval", 0))))
                .hasMessageContaining("exceeds its outstanding");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?", Integer.class, f.account())).isEqualTo(1);
    }

    @Test void concurrentDifferentRequestsSerializeAndCannotReserveTheSameMoneyTwice() throws Exception {
        Fixture f = fixture(true);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { start.await(); return tryPrepare(f, request(f, "700")); });
            var two = executor.submit(() -> { start.await(); return tryPrepare(f, request(f, "700")); });
            start.countDown();
            assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(owner.queryForObject("SELECT sum(amount) FROM fee_gateway_allocations WHERE learner_fee_account_id=? AND status='ACTIVE'", BigDecimal.class, f.account())).isEqualByComparingTo("700");
    }

    @Test void closedDaySuccessRetainsEvidenceAndRequiresReviewWithoutChangingTheClosedDay() {
        Fixture f = fixture(true);
        var attempt = prepare(f, request(f, "500"));
        UUID observation = observation(f, attempt, "PAID", "500", 0, 1);
        var declarations = List.of("CASH","BANK_TRANSFER","CHEQUE","SCHOOL_UPI","ONLINE_PROVIDER").stream()
                .map(method -> new FinanceController.TenderDeclaration(method, BigDecimal.ZERO)).toList();
        var close = scoped(f, () -> finance.proposeDayClose(f.admin(), new FinanceController.DayCloseRequest(f.school(), attempt.originalBusinessDate(), declarations, "Synthetic reviewed day close", null)));
        scoped(f, () -> finance.decideDayClose(f.reviewer(), close.id(), new FinanceController.DayCloseDecisionRequest(f.school(), "APPROVED", "Synthetic independent close", 0)));
        String before = owner.queryForObject("SELECT evidence_snapshot::text FROM fee_day_closes WHERE id=?", String.class, close.id());
        assertThatThrownBy(() -> capture(f, attempt, observation, "MATCHED", null, "synthetic_closed_payment")).isInstanceOf(DataAccessException.class);
        UUID capture = capture(f, attempt, observation, "REVIEW_REQUIRED", close.id(), "synthetic_closed_payment");
        scoped(f, () -> runtime.update("UPDATE fee_gateway_attempts SET status='CAPTURED_REVIEW_REQUIRED',updated_at=now(),row_version=row_version+1 WHERE id=?", attempt.id()));
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_attempts WHERE id=?", String.class, attempt.id())).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        assertThat(owner.queryForObject("SELECT closed_day_close_id FROM fee_gateway_captures WHERE id=?", UUID.class, capture)).isEqualTo(close.id());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?", Integer.class, f.account())).isZero();
        assertThat(owner.queryForObject("SELECT evidence_snapshot::text FROM fee_day_closes WHERE id=?", String.class, close.id())).isEqualTo(before);
        assertThatThrownBy(() -> scoped(f, () -> runtime.update("UPDATE fee_gateway_captures SET paid_at=paid_at+interval '1 hour' WHERE id=?", capture))).isInstanceOf(DataAccessException.class);
    }

    @Test void extraVerifiedCapturesAreRetainedForReviewButExactRetriesCannotDuplicateThem() {
        Fixture f = fixture(true);
        var attempt = prepare(f, request(f, "500"));
        UUID observed = observation(f, attempt, "PAID", "500", 0, 1);
        UUID first = capture(f, attempt, observed, "MATCHED", null, "synthetic_capture_a");
        assertThatThrownBy(() -> capture(f, attempt, observed, "MATCHED", null, "synthetic_capture_b")).isInstanceOf(DataAccessException.class);
        UUID second = capture(f, attempt, observed, "REVIEW_REQUIRED", null, "synthetic_capture_b");
        assertThat(first).isNotEqualTo(second);
        assertThatThrownBy(() -> capture(f, attempt, observed, "REVIEW_REQUIRED", null, "synthetic_capture_a")).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE attempt_id=?", Integer.class, attempt.id())).isEqualTo(2);
    }

    @Test void gatewayClearingUsesTheCommonApprovedBookWithoutWeakeningManualBankMappings() {
        Fixture f=fixture(true);
        var year=scoped(f,()->accounting.createFinancialYear(f.admin(),new com.educore.api.accounting.AccountingController.FinancialYearRequest(
                f.school(),"ONLINE_CLEARING","Synthetic gateway year",LocalDate.of(2026,4,1),LocalDate.of(2027,3,31),"CASH","INR","Synthetic book setup")));
        UUID cash=account(f,"CASH","ASSET","CASH"),bank=account(f,"BANK","ASSET","BANK"),
                clearing=account(f,"GATEWAY_CLEARING","ASSET","OTHER"),income=account(f,"FEE_INCOME","INCOME","INCOME");
        scoped(f,()->accounting.openFinancialYear(f.reviewer(),year.id(),new com.educore.api.accounting.AccountingController.VersionedReasonRequest(f.school(),0,"Synthetic independent opening")));
        UUID head=owner.queryForObject("SELECT fee_head_id FROM fee_plan_items WHERE id=(SELECT fee_plan_item_id FROM learner_fee_charges WHERE id=?)",UUID.class,f.charge());
        var tenders=List.of(new FeeAccountingController.TenderMappingRequest("CASH",cash),new FeeAccountingController.TenderMappingRequest("BANK_TRANSFER",bank),
                new FeeAccountingController.TenderMappingRequest("CHEQUE",bank),new FeeAccountingController.TenderMappingRequest("SCHOOL_UPI",bank),new FeeAccountingController.TenderMappingRequest("ONLINE_PROVIDER",clearing));
        var request=new FeeAccountingController.ConfigurationRequest(f.school(),year.id(),null,1,LocalDate.of(2026,4,1),null,null,
                List.of(new FeeAccountingController.HeadMappingRequest(head,income)),tenders,null,"Synthetic gateway-clearing mapping");
        var mapping=scoped(f,()->feeAccounting.createConfiguration(f.admin(),request));
        assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history WHERE version='067'",Boolean.class)).isTrue();
        assertThatThrownBy(()->scoped(f,()->runtime.update("UPDATE fee_accounting_tender_mappings SET deposit_account_id=? WHERE configuration_id=? AND payment_method='BANK_TRANSFER'",clearing,mapping.id()))).isInstanceOf(DataAccessException.class);
        Fixture other=fixture(true);
        UUID otherClearing=account(other,"OTHER_CLEARING","ASSET","OTHER");
        assertThatThrownBy(()->scoped(f,()->runtime.update("UPDATE fee_accounting_tender_mappings SET deposit_account_id=? WHERE configuration_id=? AND payment_method='ONLINE_PROVIDER'",otherClearing,mapping.id()))).isInstanceOf(DataAccessException.class);
        // Draft-only reconfiguration still supports legacy bank destinations.
        scoped(f,()->runtime.update("UPDATE fee_accounting_tender_mappings SET deposit_account_id=? WHERE configuration_id=? AND payment_method='ONLINE_PROVIDER'",bank,mapping.id()));
        scoped(f,()->runtime.update("UPDATE fee_accounting_tender_mappings SET deposit_account_id=? WHERE configuration_id=? AND payment_method='ONLINE_PROVIDER'",clearing,mapping.id()));
        var submitted=scoped(f,()->feeAccounting.submitConfiguration(f.admin(),mapping.id(),new FeeAccountingController.VersionedReasonRequest(f.school(),0,"Synthetic mapping review")));
        scoped(f,()->feeAccounting.decideConfiguration(f.reviewer(),mapping.id(),new FeeAccountingController.ConfigurationDecisionRequest(f.school(),"APPROVE",submitted.rowVersion(),"Synthetic independent mapping approval")));
        assertThat(owner.queryForObject("SELECT status FROM fee_accounting_configurations WHERE id=?",String.class,mapping.id())).isEqualTo("APPROVED");
        assertThat(owner.queryForObject("SELECT deposit_account_id FROM fee_accounting_tender_mappings WHERE configuration_id=? AND payment_method='ONLINE_PROVIDER'",UUID.class,mapping.id())).isEqualTo(clearing);
        assertThatThrownBy(()->scoped(f,()->runtime.update("UPDATE fee_accounting_tender_mappings SET deposit_account_id=? WHERE configuration_id=? AND payment_method='ONLINE_PROVIDER'",bank,mapping.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->scoped(f,()->runtime.update("DELETE FROM fee_accounting_tender_mappings WHERE configuration_id=?",mapping.id()))).isInstanceOf(DataAccessException.class);
        var invalid=tenders.stream().map(t->"BANK_TRANSFER".equals(t.paymentMethod())?new FeeAccountingController.TenderMappingRequest("BANK_TRANSFER",clearing):t).toList();
        assertThatThrownBy(()->scoped(f,()->feeAccounting.createConfiguration(f.admin(),new FeeAccountingController.ConfigurationRequest(f.school(),year.id(),null,1,LocalDate.of(2026,4,1),null,null,
                request.headMappings(),invalid,null,"Synthetic invalid bank mapping")))).hasMessageContaining("required type");
        assertThatThrownBy(()->scoped(f,()->feeAccounting.createConfiguration(f.parent(),request))).hasMessageContaining("unavailable");
    }

    private UUID account(Fixture f,String code,String type,String category) {
        return scoped(f,()->accounting.createAccount(f.admin(),new com.educore.api.accounting.AccountingController.AccountRequest(f.school(),null,code,
                "Synthetic "+code,null,type,category,true,"ACTIVE",null,"Synthetic gateway accounting setup"))).id();
    }

    @Test void verifiedCapturePostsOneCommonNumberedReceiptWithHonestSystemProvenance() {
        Fixture f=fixture(true);
        var attempt=prepare(f,request(f,"500"));
        Instant paid=Instant.now(clock);
        UUID capture=timedCapture(f,attempt,paid,"MATCHED",null,"synthetic_receipt");
        var posted=post(f,capture,null,null);
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.receiptNumber()).isEqualTo("SYN-000001");
        assertThat(post(f,capture,null,null)).isEqualTo(posted);
        assertThat(owner.queryForObject("SELECT actor_kind FROM fee_payments WHERE id=?",String.class,posted.paymentId())).isEqualTo("SYSTEM");
        assertThat(owner.queryForObject("SELECT received_by_membership_id FROM fee_payments WHERE id=?",UUID.class,posted.paymentId())).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE learner_fee_account_id=? AND entry_type='PAYMENT' AND actor_kind='SYSTEM' AND created_by_membership_id IS NULL",Integer.class,f.account())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_allocations WHERE attempt_id=? AND status='CONSUMED'",Integer.class,attempt.id())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) FROM fee_ledger_entries WHERE learner_fee_account_id=?",BigDecimal.class,f.account())).isEqualByComparingTo("500");
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE subject_id=? AND action='finance.gateway.payment.posted' AND actor_user_id IS NULL AND actor_membership_id IS NULL",Integer.class,posted.paymentId().toString())).isEqualTo(1);
        assertThatThrownBy(()->scoped(f,()->runtime.update("UPDATE fee_payments SET actor_kind='HUMAN',received_by_membership_id=? WHERE id=?",f.admin().membershipIds().iterator().next(),posted.paymentId()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->scoped(f,()->finance.reversePayment(f.admin(),posted.paymentId(),new FinanceController.PaymentReversalRequest(f.school(),0,"Synthetic forbidden reversal")))).hasMessageContaining("verified provider refund");
    }

    @Test void delayedClosedDayCaptureIsHeldThenReviewedOnAnOpenDateWithoutChangingHistory() {
        Fixture f=fixture(true);
        Instant created=Instant.now(clock).minus(Duration.ofDays(1)).minusSeconds(20);
        var attempt=prepareAt(f,request(f,"500"),Clock.fixed(created,java.time.ZoneOffset.UTC));
        LocalDate original=attempt.originalBusinessDate();
        var declarations=List.of("CASH","BANK_TRANSFER","CHEQUE","SCHOOL_UPI","ONLINE_PROVIDER").stream().map(method->new FinanceController.TenderDeclaration(method,BigDecimal.ZERO)).toList();
        var close=scoped(f,()->finance.proposeDayClose(f.admin(),new FinanceController.DayCloseRequest(f.school(),original,declarations,"Synthetic original day closure",null)));
        scoped(f,()->finance.decideDayClose(f.reviewer(),close.id(),new FinanceController.DayCloseDecisionRequest(f.school(),"APPROVED","Synthetic independent closure",0)));
        String closedEvidence=owner.queryForObject("SELECT evidence_snapshot::text FROM fee_day_closes WHERE id=?",String.class,close.id());
        UUID capture=timedCapture(f,attempt,created.plusSeconds(10),"REVIEW_REQUIRED",close.id(),"synthetic_late_receipt");
        assertThat(post(f,capture,null,null).status()).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        assertThat(post(f,capture,null,null).status()).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=? AND resolution_type='HOLD' AND closed_day_close_id=?",Integer.class,capture,close.id())).isEqualTo(1);
        assertThatThrownBy(()->post(f,capture,f.parent(),LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->post(f,capture,f.reviewer(),original)).hasMessageContaining("eligible open financial date");
        var posted=post(f,capture,f.reviewer(),LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(owner.queryForObject("SELECT received_by_membership_id FROM fee_payments WHERE id=?",UUID.class,posted.paymentId())).isEqualTo(f.reviewer().membershipIds().iterator().next());
        assertThat(owner.queryForObject("SELECT paid_at FROM fee_gateway_captures WHERE id=?",java.sql.Timestamp.class,capture).toInstant()).isEqualTo(created.plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(owner.queryForObject("SELECT evidence_snapshot::text FROM fee_day_closes WHERE id=?",String.class,close.id())).isEqualTo(closedEvidence);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE action='finance.gateway.capture.reviewed-posted' AND subject_id=?",Integer.class,capture.toString())).isEqualTo(1);
    }

    @Test void concurrentCaptureRetriesIssueOnlyOneReceiptAndCrossSchoolPostingIsDenied() throws Exception {
        Fixture f=fixture(true),other=fixture(true);
        var attempt=prepare(f,request(f,"500"));
        UUID capture=timedCapture(f,attempt,Instant.now(clock),"MATCHED",null,"synthetic_concurrent_receipt");
        assertThatThrownBy(()->post(other,capture,null,null)).hasMessageContaining("scoped gateway capture");
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->{start.await();return post(f,capture,null,null);});
            var two=executor.submit(()->{start.await();return post(f,capture,null,null);});
            start.countDown();assertThat(one.get(30,TimeUnit.SECONDS)).isEqualTo(two.get(30,TimeUnit.SECONDS));
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?",Long.class,f.school())).isEqualTo(2);
    }

    @Test void interruptedPostingRollsBackReceiptSequenceLedgerAndReservationDisposition() {
        Fixture f=fixture(true);
        var attempt=prepare(f,request(f,"500"));
        UUID capture=timedCapture(f,attempt,Instant.now(clock),"MATCHED",null,"synthetic_rollback_receipt");
        assertThatThrownBy(()->{
            try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,f.org())) {
                transactions.execute(status->{finance.postVerifiedGatewayCapture(f.org(),f.school(),capture,null,null,null);throw new IllegalStateException("Synthetic post-write failure");});
            }
        }).hasMessageContaining("Synthetic post-write failure");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isZero();
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?",Long.class,f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?",String.class,attempt.id())).isEqualTo("ACTIVE");
        assertThat(post(f,capture,null,null).status()).isEqualTo("POSTED");
    }

    @Test void nullableSystemActorsCannotForgeHumanDispositionOrCommitAnUnpairedFeeCredit() {
        Fixture f=fixture(true);
        var attempt=prepare(f,request(f,"500"));
        UUID capture=timedCapture(f,attempt,Instant.now(clock),"MATCHED",null,"synthetic_provenance_guard");
        assertThatThrownBy(()->scoped(f,()->runtime.update("""
                INSERT INTO fee_gateway_capture_resolutions(id,organisation_id,school_id,capture_id,resolution_type,reason,
                  reviewed_by_user_id,reviewed_by_membership_id,reviewed_at,actor_kind,system_component)
                VALUES(?,?,?,?,'HOLD','Synthetic forged system actor',NULL,NULL,clock_timestamp(),'SYSTEM','GATEWAY_VERIFICATION')
                """,UUID.randomUUID(),f.org(),f.school(),capture))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->{
            try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,f.org())) {
                transactions.execute(status->runtime.update("""
                        INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,entry_type,direction,amount,
                          effective_on,description,source_type,source_id,created_by_membership_id,created_at,actor_kind,gateway_capture_id)
                        VALUES(?,?,?,?,'PAYMENT','CREDIT',500,CURRENT_DATE,'Synthetic incomplete writer','FEE_PAYMENT',?,NULL,now(),'SYSTEM',?)
                        """,UUID.randomUUID(),f.org(),f.school(),f.account(),UUID.randomUUID(),capture));
            }
        }).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE learner_fee_account_id=? AND entry_type='PAYMENT'",Integer.class,f.account())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=?",Integer.class,capture)).isZero();
        assertThat(post(f,capture,null,null).status()).isEqualTo("POSTED");
    }

    @Test void staffCaptureQueueIsScopedSearchablePagedAndContainsOnlyMinimalLearnerEvidence() {
        Fixture f=fixture(true);
        owner.update("UPDATE learners SET legal_name='Synthetic queue learner' WHERE id=?",f.learner());
        var heldAttempt=prepare(f,request(f,"500"));
        UUID held=timedCapture(f,heldAttempt,Instant.now(clock),"REVIEW_REQUIRED",null,"synthetic_queue_hold");
        assertThat(post(f,held,null,null).status()).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        var paidAttempt=prepare(f,request(f,"300"));
        UUID paid=timedCapture(f,paidAttempt,Instant.now(clock),"MATCHED",null,"synthetic_queue_posted");
        var receipt=post(f,paid,null,null);
        var unresolvedAttempt=prepare(f,request(f,"200"));
        UUID unresolved=timedCapture(f,unresolvedAttempt,Instant.now(clock),"REVIEW_REQUIRED",null,"synthetic_queue_unresolved");

        var defaults=queue(f,f.admin(),null,null,0,25);
        assertThat(defaults.schoolId()).isEqualTo(f.school());
        assertThat(defaults.schoolToday()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        assertThat(defaults.latestApprovedCloseDate()).isNull();
        assertThat(defaults.total()).isEqualTo(1);
        assertThat(defaults.items()).hasSize(1);
        var item=defaults.items().getFirst();
        assertThat(item.id()).isEqualTo(held);
        assertThat(item.attemptId()).isEqualTo(heldAttempt.id());
        assertThat(item.learnerId()).isEqualTo(f.learner());
        assertThat(item.learnerName()).isEqualTo("Synthetic queue learner");
        assertThat(item.amount()).isEqualByComparingTo("500");
        assertThat(item.currencyCode()).isEqualTo("INR");
        assertThat(item.classification()).isEqualTo("REVIEW_REQUIRED");
        assertThat(item.attemptStatus()).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        assertThat(item.status()).isEqualTo("HOLD");
        assertThat(item.holdReason()).isNotBlank();
        assertThat(item.canReviewedPost()).isTrue();
        assertThat(item.earliestOpenDate()).isEqualTo(heldAttempt.originalBusinessDate());
        assertThat(item.paymentId()).isNull();
        assertThat(item.receiptNumber()).isNull();
        assertThat(queue(f,f.admin(),"queue learner","ALL",0,25).total()).isEqualTo(3);
        assertThat(queue(f,f.admin(),"synthetic_missing_search","ALL",0,25).items()).isEmpty();
        assertThat(queue(f,f.admin(),null,"UNRESOLVED",0,25).items())
                .extracting(GatewayCaptureQueueResponse.CaptureItem::id).containsExactly(unresolved);
        var posted=queue(f,f.admin(),null,"POSTED",0,25).items().getFirst();
        assertThat(posted.id()).isEqualTo(paid);
        assertThat(posted.paymentId()).isEqualTo(receipt.paymentId());
        assertThat(posted.receiptNumber()).isEqualTo(receipt.receiptNumber());
        assertThat(posted.postedOn()).isEqualTo(paidAttempt.originalBusinessDate());
        assertThat(posted.canReviewedPost()).isFalse();
        assertThat(queue(f,f.admin(),receipt.receiptNumber(),"ALL",0,25).items())
                .extracting(GatewayCaptureQueueResponse.CaptureItem::id).containsExactly(paid);
        var firstPage=queue(f,f.admin(),null,"ALL",0,1);
        var secondPage=queue(f,f.admin(),null,"ALL",1,1);
        var lastPage=queue(f,f.admin(),null,"ALL",2,1);
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.pageSize()).isEqualTo(1);
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(secondPage.hasMore()).isTrue();
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(List.of(firstPage.items().getFirst().id(),secondPage.items().getFirst().id(),lastPage.items().getFirst().id()))
                .containsExactlyInAnyOrder(held,paid,unresolved);
        assertThat(queue(f,f.admin(),null,"ALL",3,1).items()).isEmpty();
        String json=JsonMapper.builder().build().writeValueAsString(defaults);
        assertThat(json).doesNotContain("payerPhone","payer_phone","ciphertext","email","birthDate","birth_date",
                "synthetic_queue_hold","synthetic_merchant_","providerCheckoutReference","0000000000");
    }

    @Test void captureQueueAndReviewedPostingRejectUnauthorizedAndCrossScopeActors() {
        Fixture f=fixture(true),other=fixture(true);
        var attempt=prepare(f,request(f,"500"));
        UUID capture=timedCapture(f,attempt,Instant.now(clock),"REVIEW_REQUIRED",null,"synthetic_scope_review");
        post(f,capture,null,null);
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        assertThatThrownBy(()->queue(f,f.parent(),null,"ALL",0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->reviewedPost(f,f.parent(),capture,today,"Synthetic denied guardian review"))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(queue(other,other.admin(),null,"ALL",0,25).items()).isEmpty();
        assertThatThrownBy(()->actorScoped(other.admin(),()->finance.gatewayCaptures(other.admin(),f.school(),null,"ALL",0,25)))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->reviewedPost(other,other.admin(),capture,today,"Synthetic denied cross-organisation review"))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        UUID otherSchool=UUID.randomUUID();
        owner.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'synthetic-other','Synthetic other school','Synthetic other school','ACTIVE',now(),now())",otherSchool,f.org());
        assertThatThrownBy(()->actorScoped(f.admin(),()->finance.gatewayCaptures(f.admin(),otherSchool,null,"ALL",0,25)))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->finance.postReviewedGatewayCapture(f.admin(),capture,
                new FinanceController.GatewayCapturePostingRequest(otherSchool,today,"Synthetic denied other-school review"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isZero();
    }

    @Test void queueEligibilityRefusesMismatchedExtraCaptureAndInactiveAccountEvidence() {
        Fixture exact=fixture(true);
        var exactAttempt=prepare(exact,request(exact,"500"));
        UUID exactCapture=timedCapture(exact,exactAttempt,Instant.now(clock),"REVIEW_REQUIRED",null,"synthetic_exact_review");
        post(exact,exactCapture,null,null);
        assertThat(queue(exact,exact.admin(),null,"HOLD",0,25).items().getFirst().canReviewedPost()).isTrue();
        var declarations=List.of("CASH","BANK_TRANSFER","CHEQUE","SCHOOL_UPI","ONLINE_PROVIDER").stream()
                .map(method->new FinanceController.TenderDeclaration(method,BigDecimal.ZERO)).toList();
        var dayClose=scoped(exact,()->finance.proposeDayClose(exact.admin(),new FinanceController.DayCloseRequest(exact.school(),exactAttempt.originalBusinessDate(),declarations,"Synthetic no-open-date closure",null)));
        scoped(exact,()->finance.decideDayClose(exact.reviewer(),dayClose.id(),new FinanceController.DayCloseDecisionRequest(exact.school(),"APPROVED","Synthetic independent no-open-date close",0)));
        var withoutOpenDate=queue(exact,exact.admin(),null,"HOLD",0,25).items().getFirst();
        assertThat(withoutOpenDate.earliestOpenDate()).isEqualTo(exactAttempt.originalBusinessDate().plusDays(1));
        assertThat(withoutOpenDate.canReviewedPost()).isFalse();
        actorScoped(exact.admin(),()->runtime.update("""
                INSERT INTO fee_gateway_capture_resolutions(id,organisation_id,school_id,capture_id,resolution_type,reason,
                  reviewed_by_user_id,reviewed_by_membership_id,reviewed_at,actor_kind)
                VALUES(?,?,?,?,'REFUND_REQUIRED','Synthetic reconciliation required',?,?,clock_timestamp(),'HUMAN')
                """,UUID.randomUUID(),exact.org(),exact.school(),exactCapture,exact.admin().userId(),exact.admin().membershipIds().iterator().next()));
        assertThat(queue(exact,exact.admin(),null,"HOLD",0,25).items()).isEmpty();
        assertThat(queue(exact,exact.admin(),null,"REFUND_REQUIRED",0,25).items()).hasSize(1)
                .allSatisfy(item->assertThat(item.canReviewedPost()).isFalse());

        Fixture mismatch=fixture(true);
        var mismatchAttempt=prepare(mismatch,request(mismatch,"500"));
        UUID observation=observation(mismatch,mismatchAttempt,"PAID","499",0,1);
        UUID mismatchCapture=capture(mismatch,mismatchAttempt,observation,"REVIEW_REQUIRED",null,"synthetic_mismatch_review");
        post(mismatch,mismatchCapture,null,null);
        assertThat(queue(mismatch,mismatch.admin(),null,"HOLD",0,25).items().getFirst().canReviewedPost()).isFalse();
        assertThatThrownBy(()->reviewedPost(mismatch,mismatch.admin(),mismatchCapture,LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")),"Synthetic mismatch review"))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);

        Fixture extra=fixture(true);
        var extraAttempt=prepare(extra,request(extra,"500"));
        UUID observed=observation(extra,extraAttempt,"PAID","500",0,1);
        UUID first=capture(extra,extraAttempt,observed,"REVIEW_REQUIRED",null,"synthetic_extra_review_a");
        UUID second=capture(extra,extraAttempt,observed,"REVIEW_REQUIRED",null,"synthetic_extra_review_b");
        post(extra,first,null,null);post(extra,second,null,null);
        assertThat(queue(extra,extra.admin(),null,"HOLD",0,25).items()).hasSize(2)
                .allSatisfy(item->assertThat(item.canReviewedPost()).isFalse());

        Fixture closed=fixture(true);
        var closedAttempt=prepare(closed,request(closed,"500"));
        UUID closedCapture=timedCapture(closed,closedAttempt,Instant.now(clock),"REVIEW_REQUIRED",null,"synthetic_closed_account_review");
        post(closed,closedCapture,null,null);
        owner.update("UPDATE learner_fee_accounts SET status='CLOSED',closed_by_membership_id=?,closed_at=now(),close_reason='Synthetic closed account',row_version=row_version+1 WHERE id=?",closed.admin().membershipIds().iterator().next(),closed.account());
        assertThat(queue(closed,closed.admin(),null,"HOLD",0,25).items().getFirst().canReviewedPost()).isFalse();
        assertThatThrownBy(()->reviewedPost(closed,closed.admin(),closedCapture,LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")),"Synthetic inactive account review"))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
    }

    @Test void queueAndPostingRecheckLiveActorOrganisationSchoolMembershipAndRoleOwnership() {
        Fixture f=fixture(true),other=fixture(true);
        var attempt=prepare(f,request(f,"500"));
        UUID capture=timedCapture(f,attempt,Instant.now(clock),"REVIEW_REQUIRED",null,"synthetic_live_scope_review");
        post(f,capture,null,null);
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        UUID member=f.admin().membershipIds().iterator().next();
        assertThat(queue(f,f.admin(),null,"HOLD",0,25).items().getFirst().canReviewedPost()).isTrue();
        owner.update("UPDATE user_accounts SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",f.admin().userId());
        assertReviewDenied(f,capture,today);
        owner.update("UPDATE user_accounts SET status='ACTIVE',row_version=row_version+1 WHERE id=?",f.admin().userId());
        owner.update("UPDATE organisations SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",f.org());
        assertReviewDenied(f,capture,today);
        owner.update("UPDATE organisations SET status='ACTIVE',row_version=row_version+1 WHERE id=?",f.org());
        owner.update("UPDATE schools SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",f.school());
        assertReviewDenied(f,capture,today);
        owner.update("UPDATE schools SET status='ACTIVE',row_version=row_version+1 WHERE id=?",f.school());
        owner.update("UPDATE memberships SET valid_until=now()-interval '1 hour',row_version=row_version+1 WHERE id=?",member);
        assertReviewDenied(f,capture,today);
        owner.update("UPDATE memberships SET valid_until=NULL,row_version=row_version+1 WHERE id=?",member);
        UUID foreignRole=financeRole(other.org(),List.of("fees.read","fees.collect"));
        owner.update("DELETE FROM membership_roles WHERE membership_id=?",member);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",member,foreignRole);
        assertReviewDenied(f,capture,today);
        UUID readRole=financeRole(f.org(),List.of("fees.read"));
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",member,readRole);
        assertThat(queue(f,f.admin(),null,"HOLD",0,25).items().getFirst().canReviewedPost()).isFalse();
        assertThatThrownBy(()->reviewedPost(f,f.admin(),capture,today,"Synthetic read-only reviewer denial"))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isZero();
    }

    @Test void reviewedPostingApiUsesSafeOpenDateAndRetriesOneHumanReceiptWithoutChangingClosedHistory() {
        Fixture f=fixture(true);
        Instant created=Instant.now(clock).minus(Duration.ofDays(1)).minusSeconds(20);
        var attempt=prepareAt(f,request(f,"500"),Clock.fixed(created,java.time.ZoneOffset.UTC));
        LocalDate original=attempt.originalBusinessDate(),today=LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        var declarations=List.of("CASH","BANK_TRANSFER","CHEQUE","SCHOOL_UPI","ONLINE_PROVIDER").stream()
                .map(method->new FinanceController.TenderDeclaration(method,BigDecimal.ZERO)).toList();
        var close=scoped(f,()->finance.proposeDayClose(f.admin(),new FinanceController.DayCloseRequest(f.school(),original,declarations,"Synthetic queue original close",null)));
        scoped(f,()->finance.decideDayClose(f.reviewer(),close.id(),new FinanceController.DayCloseDecisionRequest(f.school(),"APPROVED","Synthetic independent queue close",0)));
        String closedEvidence=owner.queryForObject("SELECT evidence_snapshot::text FROM fee_day_closes WHERE id=?",String.class,close.id());
        UUID capture=timedCapture(f,attempt,created.plusSeconds(10),"REVIEW_REQUIRED",close.id(),"synthetic_review_api_receipt");
        post(f,capture,null,null);
        var before=queue(f,f.reviewer(),null,"HOLD",0,25);
        var held=before.items().getFirst();
        assertThat(before.latestApprovedCloseDate()).isEqualTo(original);
        assertThat(held.closedDayCloseId()).isEqualTo(close.id());
        assertThat(held.originalBusinessDate()).isEqualTo(original);
        assertThat(held.earliestOpenDate()).isEqualTo(original.plusDays(1));
        assertThat(held.canReviewedPost()).isTrue();
        for(LocalDate invalid:List.of(original.minusDays(1),original,today.plusDays(1))) {
            assertThatThrownBy(()->reviewedPost(f,f.reviewer(),capture,invalid,"Synthetic invalid reviewed financial date"))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        var posted=reviewedPost(f,f.reviewer(),capture,today,"Synthetic reviewed queue allocation");
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(reviewedPost(f,f.reviewer(),capture,today,"Synthetic same capture retry")).isEqualTo(posted);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT actor_kind FROM fee_payments WHERE id=?",String.class,posted.paymentId())).isEqualTo("HUMAN");
        assertThat(owner.queryForObject("SELECT received_by_membership_id FROM fee_payments WHERE id=?",UUID.class,posted.paymentId())).isEqualTo(f.reviewer().membershipIds().iterator().next());
        assertThat(owner.queryForObject("SELECT received_on FROM fee_payments WHERE id=?",LocalDate.class,posted.paymentId())).isEqualTo(today);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=? AND resolution_type='POSTED' AND actor_kind='HUMAN'",Integer.class,capture)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE action='finance.gateway.capture.reviewed-posted' AND subject_id=? AND actor_user_id=? AND actor_membership_id=?",Integer.class,capture.toString(),f.reviewer().userId(),f.reviewer().membershipIds().iterator().next())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT evidence_snapshot::text FROM fee_day_closes WHERE id=?",String.class,close.id())).isEqualTo(closedEvidence);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE gateway_capture_id=? AND entry_type='PAYMENT' AND actor_kind='HUMAN'",Integer.class,capture)).isEqualTo(1);
        var after=queue(f,f.reviewer(),null,"POSTED",0,25).items().getFirst();
        assertThat(after.id()).isEqualTo(capture);
        assertThat(after.paymentId()).isEqualTo(posted.paymentId());
        assertThat(after.receiptNumber()).isEqualTo(posted.receiptNumber());
        assertThat(after.postedOn()).isEqualTo(today);
        assertThat(after.canReviewedPost()).isFalse();
        owner.update("UPDATE memberships SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",f.reviewer().membershipIds().iterator().next());
        assertThatThrownBy(()->reviewedPost(f,f.reviewer(),capture,today,"Synthetic revoked reviewer retry"))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
    }

    @Test void captureQueueRejectsUnboundedOrInvalidFilterInputs() {
        Fixture f=fixture(true);
        for(int[] bounds:List.of(new int[]{-1,25},new int[]{100001,25},new int[]{0,0},new int[]{0,101})) {
            assertThatThrownBy(()->queue(f,f.admin(),null,"ALL",bounds[0],bounds[1]))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        assertThatThrownBy(()->queue(f,f.admin(),"x".repeat(101),"ALL",0,25))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->queue(f,f.admin(),null,"NOT_A_STATUS",0,25))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
    }

    @Test void manualDateOnlyCashPreservesHonestPrecisionAndTheCommonFamilyReceipt() {
        Fixture f=fixture(true);
        var request=payment(f,"125.50");
        var receipt=collect(f,"synthetic-date-only",request);
        assertThat(collect(f,"synthetic-date-only",request)).isEqualTo(receipt);
        assertThat(receipt.receiptNumber()).isEqualTo("SYN-000001");
        assertThat(owner.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname='fee_manual_collection_evidence'",Boolean.class)).isTrue();
        var evidence=owner.queryForMap("SELECT * FROM fee_manual_collection_evidence WHERE fee_payment_id=?",receipt.id());
        assertThat(evidence.get("actual_received_at")).isNull();
        assertThat(evidence.get("received_time_precision")).isEqualTo("DATE_ONLY");
        assertThat(evidence.get("verification_kind")).isEqualTo("CASH_COUNTED");
        assertThat(evidence.get("bank_credit_confirmed")).isEqualTo(false);
        assertThat(evidence.get("bank_credit_reference")).isNull();
        assertThat(evidence.get("verified_by_membership_id")).isEqualTo(member(f.admin()));
        assertThat(owner.queryForObject("SELECT original_received_on FROM fee_manual_collection_evidence WHERE fee_payment_id=?",LocalDate.class,receipt.id())).isEqualTo(request.receivedOn());
        assertThat(owner.queryForObject("SELECT posting_on FROM fee_manual_collection_evidence WHERE fee_payment_id=?",LocalDate.class,receipt.id())).isEqualTo(request.receivedOn());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE action='finance.manual.collection.verified' AND subject_id=? AND actor_user_id=? AND actor_membership_id=?",Integer.class,receipt.id().toString(),f.admin().userId(),member(f.admin()))).isEqualTo(1);
        var staffReceipt=actorScoped(f.admin(),()->finance.snapshot(f.admin(),f.school())).accounts().stream()
                .filter(account->account.id().equals(f.account())).findFirst().orElseThrow().payments().stream()
                .filter(item->item.id().equals(receipt.id())).findFirst().orElseThrow();
        var staffReceiptJson=JsonMapper.builder().build().valueToTree(staffReceipt);
        assertThat(staffReceipt.gatewayRefundSource()).isFalse();assertThat(staffReceiptJson.get("gatewayRefundSource").booleanValue()).isFalse();
        assertThat(staffReceiptJson.toString()).doesNotContain("gatewayCaptureId","captureId");
        var family=familyFees(f);
        assertThat(JsonMapper.builder().build().writeValueAsString(family)).doesNotContain("gatewayRefundSource","gatewayCaptureId","captureId");
        assertThat(family.balance()).isEqualByComparingTo("874.50");
        assertThat(family.receipts()).hasSize(1);
        assertThat(family.receipts().getFirst().paymentId()).isEqualTo(receipt.id());
        assertThat(family.receipts().getFirst().receiptNumber()).isEqualTo(receipt.receiptNumber());
        assertThat(family.receipts().getFirst().timing().originalReceivedOn()).isEqualTo(request.receivedOn());
        assertThat(family.receipts().getFirst().timing().actualReceivedAt()).isNull();
        assertThat(family.receipts().getFirst().timing().receivedTimePrecision()).isEqualTo("DATE_ONLY");
        assertThat(family.receipts().getFirst().timing().schoolTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(family.statement().stream().filter(entry->"PAYMENT".equals(entry.entryType())).toList()).hasSize(1);
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?",Long.class,f.school())).isEqualTo(2);
    }

    @Test void manualVerifiedCashUpiAndBankShareExactAllocationsOneLedgerAndSequentialReceipts() {
        Fixture f=fixture(true);
        Instant actual=manualInstant();
        var cash=collect(f,"synthetic-mixed-cash",manualPayment(f,"CASH",null,"175.25",actual,null,null,null));
        var upi=collect(f,"synthetic-mixed-upi",manualPayment(f,"SCHOOL_UPI","SYN-UPI-001","224.75",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"SYN-UPI-001"),null));
        var bank=collect(f,"synthetic-mixed-bank",manualPayment(f,"BANK_TRANSFER",null,"100",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_STATEMENT_CREDIT",true,"SYN-BANK-002"),null));
        assertThat(List.of(cash.receiptNumber(),upi.receiptNumber(),bank.receiptNumber())).containsExactly("SYN-000001","SYN-000002","SYN-000003");
        assertThat(owner.queryForList("SELECT received_time_precision FROM fee_manual_collection_evidence WHERE school_id=?",String.class,f.school())).containsOnly("INSTANT").hasSize(3);
        assertThat(owner.queryForObject("SELECT external_reference FROM fee_payments WHERE id=?",String.class,bank.id())).isEqualTo("SYN-BANK-002");
        for(var receipt:List.of(cash,upi,bank)) {
            assertThat(owner.queryForObject("SELECT actual_received_at FROM fee_manual_collection_evidence WHERE fee_payment_id=?",java.sql.Timestamp.class,receipt.id()).toInstant()).isEqualTo(actual);
            assertThat(owner.queryForObject("""
                    SELECT count(*) FROM fee_payments payment JOIN fee_ledger_entries ledger ON ledger.id=payment.ledger_entry_id
                    WHERE payment.id=? AND payment.actor_kind='HUMAN' AND ledger.actor_kind='HUMAN'
                      AND ledger.created_by_membership_id=payment.received_by_membership_id
                      AND ledger.source_id=payment.id AND ledger.source_type='FEE_PAYMENT'
                      AND ledger.amount=payment.amount AND ledger.effective_on=payment.received_on
                      AND (SELECT sum(amount) FROM fee_payment_allocations WHERE fee_payment_id=payment.id)=payment.amount
                    """,Integer.class,receipt.id())).isEqualTo(1);
        }
        var family=familyFees(f);
        assertThat(family.balance()).isEqualByComparingTo("500");
        assertThat(family.receipts()).hasSize(3);
        assertThat(family.receipts()).allSatisfy(receipt->{
            assertThat(receipt.timing().actualReceivedAt()).isEqualTo(actual);
            assertThat(receipt.timing().receivedTimePrecision()).isEqualTo("INSTANT");
        });
        assertThat(family.charges().getFirst().paidAmount()).isEqualByComparingTo("500");
        assertThat(JsonMapper.builder().build().writeValueAsString(family)).doesNotContain("SYN-UPI-001","SYN-BANK-002",
                "BANK_APP_CREDIT","BANK_STATEMENT_CREDIT","verifiedByMembershipId","bankCreditReference",member(f.admin()).toString());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE learner_fee_account_id=? AND entry_type='PAYMENT'",Integer.class,f.account())).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_captures WHERE school_id=?",Integer.class,f.school())).isZero();
    }

    @Test void manualDigitalReferenceOrScreenshotWithoutActualBankCreditCannotPost() {
        Fixture f=fixture(true);
        Instant actual=manualInstant();
        var absent=manualPayment(f,"SCHOOL_UPI","SYN-UNVERIFIED","10",actual,null,null,null);
        var declined=manualPayment(f,"BANK_TRANSFER","SYN-UNVERIFIED","10",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",false,"SYN-UNVERIFIED"),null);
        var screenshot=manualPayment(f,"SCHOOL_UPI","SYN-UNVERIFIED","10",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("PAYER_SCREENSHOT",true,"SYN-UNVERIFIED"),null);
        var url=manualPayment(f,"SCHOOL_UPI","https://synthetic.example.test/proof","10",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"https://synthetic.example.test/proof"),null);
        var missingTime=new FinanceController.PaymentRequest(f.school(),f.account(),"SCHOOL_UPI","SYN-UNVERIFIED",new BigDecimal("10"),schoolToday(),
                "Synthetic missing received instant",List.of(new FinanceController.PaymentAllocation(f.charge(),new BigDecimal("10"))),null,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_STATEMENT_CREDIT",true,"SYN-UNVERIFIED"),null);
        for(var denied:List.of(absent,declined,screenshot,url,missingTime)) {
            assertThatThrownBy(()->collect(f,"synthetic-unverified-"+UUID.randomUUID(),denied))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("actual received funds");
        }
        assertManualAccountUntouched(f);
    }

    @Test void manualBankCreditIdentitySurvivesCrossMethodRetriesConcurrencyAndReversal() throws Exception {
        Fixture f=fixture(true);
        Instant actual=manualInstant();
        var request=manualPayment(f,"SCHOOL_UPI","syn - utr/ 001","200",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"syn - utr/ 001"),null);
        var receipt=collect(f,"synthetic-credit-once",request);
        assertThat(collect(f,"synthetic-credit-once",request)).isEqualTo(receipt);
        var normalized=manualPayment(f,"BANK_TRANSFER","SYN-UTR/001","200",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_STATEMENT_CREDIT",true,"SYN-UTR/001"),null);
        assertThatThrownBy(()->collect(f,"synthetic-other-key",normalized)).hasMessageContaining("already appears");
        assertThatThrownBy(()->collect(f,"synthetic-credit-once",manualPayment(f,"SCHOOL_UPI","syn - utr/ 001","201",actual,null,
                request.bankCreditVerification(),null))).hasMessageContaining("different collection");
        String evidence=owner.queryForObject("SELECT row_to_json(evidence)::text FROM fee_manual_collection_evidence evidence WHERE fee_payment_id=?",String.class,receipt.id());
        actorScoped(f.admin(),()->finance.reversePayment(f.admin(),receipt.id(),new FinanceController.PaymentReversalRequest(f.school(),0,"Synthetic controlled reversal")));
        assertThatThrownBy(()->collect(f,"synthetic-reversed-credit-reuse",normalized)).hasMessageContaining("including reversed receipts");
        assertThat(owner.queryForObject("SELECT row_to_json(evidence)::text FROM fee_manual_collection_evidence evidence WHERE fee_payment_id=?",String.class,receipt.id())).isEqualTo(evidence);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE learner_fee_account_id=? AND entry_type='PAYMENT_REVERSAL'",Integer.class,f.account())).isEqualTo(1);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("1000");

        Fixture concurrent=fixture(true);
        var one=manualPayment(concurrent,"SCHOOL_UPI","syn - concurrent/ 002","100",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"syn - concurrent/ 002"),null);
        var two=manualPayment(concurrent,"BANK_TRANSFER","SYN-CONCURRENT/002","100",actual,null,
                new ManualCollectionEvidence.BankCreditVerification("BANK_STATEMENT_CREDIT",true,"SYN-CONCURRENT/002"),null);
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{start.await();return tryCollect(concurrent,"synthetic-concurrent-one",one);});
            var second=executor.submit(()->{start.await();return tryCollect(concurrent,"synthetic-concurrent-two",two);});
            start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,concurrent.account())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence WHERE school_id=?",Integer.class,concurrent.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?",Long.class,concurrent.school())).isEqualTo(2);
    }

    @Test void manualCollectionsRespectOnlineReservationsPartialRulesAndRejectExcess() {
        Fixture f=fixture(true);
        var reserved=prepare(f,request(f,"700"));
        assertThatThrownBy(()->collect(f,"synthetic-reserved-excess",payment(f,"300.01"))).hasMessageContaining("current account balance");
        var cash=collect(f,"synthetic-unreserved-cash",payment(f,"300"));
        assertThat(cash.receiptNumber()).isEqualTo("SYN-000001");
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_allocations WHERE attempt_id=?",String.class,reserved.id())).isEqualTo("ACTIVE");
        var family=familyFees(f);
        assertThat(family.balance()).isEqualByComparingTo("700");
        assertThat(family.charges().getFirst().reservedAmount()).isEqualByComparingTo("700");
        assertThat(family.charges().getFirst().paidAmount()).isEqualByComparingTo("300");
        assertThatThrownBy(()->collect(f,"synthetic-no-unreserved-dues",payment(f,"0.01"))).hasMessageContaining("current account balance");
        Fixture full=fixture(false);
        assertThatThrownBy(()->collect(full,"synthetic-forbidden-partial",payment(full,"500"))).hasMessageContaining("collected in full");
        assertManualAccountUntouched(full);
        assertThat(collect(full,"synthetic-full-payment",payment(full,"1000")).receiptNumber()).isEqualTo("SYN-000001");
        assertThat(familyFees(full).balance()).isEqualByComparingTo("0");
        Fixture excess=fixture(true);
        assertThatThrownBy(()->collect(excess,"synthetic-unapproved-advance",payment(excess,"1000.01"))).hasMessageContaining("current account balance");
        assertManualAccountUntouched(excess);
    }

    @Test void manualClosedOriginalDayRequiresReviewedOpenPostingAndPreservesActualTimeAndClose() {
        Fixture f=fixture(true);
        LocalDate today=schoolToday(),original=today.minusDays(1);
        Instant actual=original.atTime(11,30).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        var declarations=List.of("CASH","BANK_TRANSFER","CHEQUE","SCHOOL_UPI","ONLINE_PROVIDER").stream()
                .map(method->new FinanceController.TenderDeclaration(method,BigDecimal.ZERO)).toList();
        var close=actorScoped(f.admin(),()->finance.proposeDayClose(f.admin(),new FinanceController.DayCloseRequest(f.school(),original,declarations,"Synthetic manual original close",null)));
        actorScoped(f.reviewer(),()->finance.decideDayClose(f.reviewer(),close.id(),new FinanceController.DayCloseDecisionRequest(f.school(),"APPROVED","Synthetic independent manual close",0)));
        String closed=owner.queryForObject("SELECT row_to_json(close_record)::text FROM fee_day_closes close_record WHERE id=?",String.class,close.id());
        var credit=new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT",true,"SYN-LATE-CREDIT");
        assertThatThrownBy(()->collect(f,"synthetic-closed-original",manualPayment(f,"SCHOOL_UPI","SYN-LATE-CREDIT","250",actual,null,credit,null)))
                .hasMessageContaining("eligible open posting date");
        assertThatThrownBy(()->collect(f,"synthetic-unreviewed-late",manualPayment(f,"SCHOOL_UPI","SYN-LATE-CREDIT","250",actual,today,credit,null)))
                .hasMessageContaining("actual received funds");
        assertThatThrownBy(()->collect(f,"synthetic-future-posting",manualPayment(f,"SCHOOL_UPI","SYN-LATE-CREDIT","250",actual,today.plusDays(1),credit,"Synthetic future date denied")))
                .hasMessageContaining("cannot be in the future");
        var request=manualPayment(f,"SCHOOL_UPI","SYN-LATE-CREDIT","250",actual,today,credit,"Synthetic accountant reviewed late credit");
        var receipt=collect(f,"synthetic-reviewed-manual",request);
        assertThat(collect(f,"synthetic-reviewed-manual",request)).isEqualTo(receipt);
        assertThat(owner.queryForObject("SELECT actual_received_at FROM fee_manual_collection_evidence WHERE fee_payment_id=?",java.sql.Timestamp.class,receipt.id()).toInstant()).isEqualTo(actual);
        assertThat(owner.queryForObject("SELECT original_received_on FROM fee_manual_collection_evidence WHERE fee_payment_id=?",LocalDate.class,receipt.id())).isEqualTo(original);
        assertThat(owner.queryForObject("SELECT posting_on FROM fee_manual_collection_evidence WHERE fee_payment_id=?",LocalDate.class,receipt.id())).isEqualTo(today);
        assertThat(owner.queryForObject("SELECT closed_day_close_id FROM fee_manual_collection_evidence WHERE fee_payment_id=?",UUID.class,receipt.id())).isEqualTo(close.id());
        assertThat(owner.queryForObject("SELECT received_on FROM fee_payments WHERE id=?",LocalDate.class,receipt.id())).isEqualTo(today);
        assertThat(owner.queryForObject("SELECT effective_on FROM fee_ledger_entries WHERE source_type='FEE_PAYMENT' AND source_id=?",LocalDate.class,receipt.id())).isEqualTo(today);
        assertThat(owner.queryForObject("SELECT row_to_json(close_record)::text FROM fee_day_closes close_record WHERE id=?",String.class,close.id())).isEqualTo(closed);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE subject_id=? AND action='finance.manual.collection.verified' AND reason='Synthetic accountant reviewed late credit'",Integer.class,receipt.id().toString())).isEqualTo(1);
        var family=familyFees(f);
        assertThat(family.receipts().getFirst().receivedOn()).isEqualTo(today);
        assertThat(family.receipts().getFirst().timing().originalReceivedOn()).isEqualTo(original);
        assertThat(family.receipts().getFirst().timing().actualReceivedAt()).isEqualTo(actual);
        assertThat(JsonMapper.builder().build().writeValueAsString(family)).doesNotContain("SYN-LATE-CREDIT",
                "Synthetic accountant reviewed late credit",close.id().toString(),member(f.admin()).toString());
    }

    @Test void manualCollectionsDenyWrongHumanCrossTenantAndRevokedLiveAuthority() {
        Fixture f=fixture(true),other=fixture(true);
        assertThatThrownBy(()->actorScoped(f.parent(),()->finance.recordPayment(f.parent(),"synthetic-parent-denied",payment(f,"10"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.parent(),()->finance.recordPayment(f.admin(),"synthetic-wrong-human",payment(f,"10"))))
                .hasMessageContaining("live authorized financial actor");
        assertThatThrownBy(()->actorScoped(other.admin(),()->finance.recordPayment(other.admin(),"synthetic-cross-tenant",payment(f,"10"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->actorScoped(f.admin(),()->finance.recordPayment(f.admin(),"synthetic-cross-account",
                new FinanceController.PaymentRequest(f.school(),other.account(),"CASH",null,BigDecimal.TEN,schoolToday(),"Synthetic denied other learner",List.of()))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertFamilyPaymentMissing(()->actorScoped(f.parent(),()->new FamilyFinanceService(new NamedParameterJdbcTemplate(runtime)).fees(f.parent(),other.learner())));
        UUID member=member(f.admin());
        owner.update("UPDATE user_accounts SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",f.admin().userId());
        assertManualDenied(f);
        owner.update("UPDATE user_accounts SET status='ACTIVE',row_version=row_version+1 WHERE id=?",f.admin().userId());
        owner.update("UPDATE memberships SET valid_until=now()-interval '1 hour',row_version=row_version+1 WHERE id=?",member);
        assertManualDenied(f);
        owner.update("UPDATE memberships SET valid_until=NULL,row_version=row_version+1 WHERE id=?",member);
        UUID foreign=financeRole(other.org(),List.of("fees.read","fees.collect"));
        owner.update("DELETE FROM membership_roles WHERE membership_id=?",member);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",member,foreign);
        assertManualDenied(f);
        UUID local=financeRole(f.org(),List.of("fees.read","fees.collect"));
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",member,local);
        owner.update("DELETE FROM role_capabilities WHERE role_id=? AND capability_code='fees.collect'",local);
        assertManualDenied(f);
        assertManualAccountUntouched(f);
        var own=collect(other,"synthetic-allowed-other-tenant",payment(other,"10"));
        assertThat(actorScoped(f.parent(),()->runtime.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence WHERE fee_payment_id=?",Integer.class,own.id()))).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence",Integer.class)).isZero();
    }

    @Test void manualCollectionRollbackAndDeferredProvenanceProtectReceiptsEvidenceAllocationsAndSequence() {
        Fixture f=fixture(true);
        assertThatThrownBy(()->actorScoped(f.admin(),()->{
            finance.recordPayment(f.admin(),"synthetic-interrupted-manual",payment(f,"200"));
            runtime.execute("SET CONSTRAINTS ALL IMMEDIATE");
            throw new IllegalStateException("Synthetic failure after verified common writer");
        })).hasMessageContaining("Synthetic failure after verified common writer");
        assertManualAccountUntouched(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawManualReceipt(f,"200","200","200",false)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertManualAccountUntouched(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawManualReceipt(f,"200","199","200",true)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertManualAccountUntouched(f);
        assertThatThrownBy(()->actorScoped(f.admin(),()->rawManualReceipt(f,"200","200","199",true)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertManualAccountUntouched(f);
        var receipt=collect(f,"synthetic-after-rollback",payment(f,"100"));
        assertThat(receipt.receiptNumber()).isEqualTo("SYN-000001");
        String evidence=owner.queryForObject("SELECT row_to_json(evidence)::text FROM fee_manual_collection_evidence evidence WHERE fee_payment_id=?",String.class,receipt.id());
        // INSERT/SELECT-only RLS hides runtime UPDATE targets; runtime grants also
        // deny DELETE. The immutable trigger rejects even a privileged owner.
        assertThat(actorScoped(f.admin(),()->runtime.update("UPDATE fee_manual_collection_evidence SET original_received_on=original_received_on-1 WHERE fee_payment_id=?",receipt.id())))
                .isZero();
        assertThatThrownBy(()->actorScoped(f.admin(),()->runtime.update("DELETE FROM fee_manual_collection_evidence WHERE fee_payment_id=?",receipt.id())))
                .isInstanceOf(DataAccessException.class).hasRootCauseMessage("ERROR: permission denied for table fee_manual_collection_evidence");
        assertThatThrownBy(()->owner.update("UPDATE fee_manual_collection_evidence SET original_received_on=original_received_on-1 WHERE fee_payment_id=?",receipt.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("DELETE FROM fee_manual_collection_evidence WHERE fee_payment_id=?",receipt.id()))
                .isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT row_to_json(evidence)::text FROM fee_manual_collection_evidence evidence WHERE fee_payment_id=?",String.class,receipt.id())).isEqualTo(evidence);
        assertThat(familyFees(f).balance()).isEqualByComparingTo("900");
    }

    @Test void familyGatewayOwnChildHistoricalHistoryAndStatusRemainMinimalWithoutRequiringCurrentFeeAccount() throws Exception {
        Fixture f = fixture(true); var stack = familyGatewayStack(f, true, true, true);
        var originalRequest = request(f, "500");
        var attempt = prepareAt(f, originalRequest, Clock.offset(clock, Duration.ofHours(-2)));
        actorScoped(f.parent(), () -> stack.work().enqueueAttempt(familyGatewayBinding(f), attempt.id()));
        owner.update("UPDATE learner_fee_accounts SET status='CLOSED',closed_by_membership_id=?,closed_at=now(),close_reason='Synthetic historical account',row_version=row_version+1 WHERE id=?", member(f.admin()), f.account());
        owner.update("UPDATE academic_sessions SET status='CLOSED',updated_at=now() WHERE id=(SELECT academic_session_id FROM learner_fee_accounts WHERE id=?)", f.account());
        UUID currentAccount = familyGatewayLaterAccount(f);
        assertThat(familyFees(f).accountId()).isEqualTo(currentAccount).isNotEqualTo(f.account());
        var before = familyGatewayFinancialState(f);
        int auditBefore = owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=?", Integer.class, f.school());
        var history = actorScoped(f.parent(), () -> stack.service().history(f.parent(), f.learner(), 0));
        assertThat(history.payments()).hasSize(1); assertThat(history.payments().getFirst().id()).isEqualTo(attempt.id());
        assertThat(history.payments().getFirst().sessionName()).isEqualTo("Synthetic current session");
        assertThat(history.payments().getFirst().status()).isEqualTo(FamilyGatewayPaymentModels.Status.EXPIRED);
        assertThat(history.payments().getFirst().reservationActive()).isTrue();
        assertThat(history.payments().getFirst().canContinue()).isFalse();
        assertThat(actorScoped(f.parent(), () -> stack.service().payment(f.parent(), attempt.id()))).isEqualTo(history.payments().getFirst());
        var retry = familyGatewayRequest(f, originalRequest.clientRequestId(), "500");
        assertThat(actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), retry))).isEqualTo(history.payments().getFirst());
        for (var changed : List.of(familyGatewayRequest(f, retry.clientRequestId(), "501"),
                new FamilyGatewayPaymentModels.InitiateRequest(retry.clientRequestId(), retry.allocations(), "0000000001"))) {
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), changed))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE learner_fee_account_id=?", Integer.class, currentAccount)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=?", Integer.class, f.school())).isEqualTo(auditBefore);
        assertFamilyGatewayPrivateDto(history); assertThat(familyGatewayFinancialState(f)).isEqualTo(before);
        assertFamilyGatewayNoPosting(f);
        // Explicit internal verified-capture fixtures, never a browser payment
        // success or merchant acceptance. Real common posting holds this closed
        // historical account, and the new family DTO must preserve that state.
        UUID heldCapture = timedCapture(f, attempt, familyGatewayNow(), "MATCHED", null, "synthetic_historical_capture");
        assertThat(post(f, heldCapture, null, null).status()).isEqualTo("CAPTURED_REVIEW_REQUIRED");
        var held = actorScoped(f.parent(), () -> stack.service().payment(f.parent(), attempt.id()));
        assertThat(held.status()).isEqualTo(FamilyGatewayPaymentModels.Status.REVIEW_REQUIRED);
        assertThat(held.receipt()).isNull(); assertThat(held.canContinue()).isFalse(); assertFamilyGatewayPrivateDto(held);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_capture_resolutions WHERE capture_id=? AND resolution_type='HOLD'", Integer.class, heldCapture)).isEqualTo(1);
        assertFamilyGatewayNoPosting(f);
    }

    @Test void familyGatewayCrossChildSiblingSchoolAndOtherOrganisationAreDeniedWithoutExposingPaymentIdentifiers() {
        Fixture f = fixture(true), other = fixture(true); var stack = familyGatewayStack(f, true, true, true);
        var attempt = prepare(f, request(f, "500")); var otherAttempt = prepare(other, request(other, "500"));
        for (UUID child : List.of(other.learner(), familyGatewayUnlinkedLearner(f, false), familyGatewayUnlinkedLearner(f, true))) {
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().history(f.parent(), child, 0))).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), child, familyGatewayRequest(f, UUID.randomUUID(), "1")))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().payment(f.parent(), otherAttempt.id()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(() -> actorScoped(other.parent(), () -> stack.service().payment(other.parent(), attempt.id()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().continueCheckout(f.parent(), other.learner(), otherAttempt.id()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        assertFamilyGatewayNoPosting(f); assertFamilyGatewayNoPosting(other);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void familyGatewayCachedCapabilityCannotReplaceLiveSessionMembershipRelationshipOrActorAuthority() {
        Fixture f = fixture(true); var stack = familyGatewayStack(f, true, true, true); var attempt = prepare(f, request(f, "500"));
        for (AuthenticatedUser stale : List.of(familyGatewayActor(f.parent(), Set.of()), familyGatewayActor(f.parent(), Set.of(member(f.admin()))))) {
            assertThatThrownBy(() -> actorScoped(stale, () -> stack.service().history(stale, f.learner(), 0))).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(() -> actorScoped(stale, () -> stack.service().payment(stale, attempt.id()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        for (String kind : List.of("ended", "future", "relationship", "actor", "role")) {
            if (kind.equals("ended")) owner.update("UPDATE memberships SET valid_until=now()-interval '1 second',updated_at=now(),row_version=row_version+1 WHERE id=?", member(f.parent()));
            else if (kind.equals("future")) owner.update("UPDATE memberships SET valid_from=now()+interval '1 day',updated_at=now(),row_version=row_version+1 WHERE id=?", member(f.parent()));
            else if (kind.equals("relationship")) owner.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED',updated_at=now(),row_version=row_version+1 WHERE learner_id=?", f.learner());
            else if (kind.equals("actor")) owner.update("UPDATE user_accounts SET status='SUSPENDED' WHERE id=?", f.parent().userId());
            else owner.update("DELETE FROM membership_roles WHERE membership_id=?", member(f.parent()));
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().history(f.parent(), f.learner(), 0))).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().payment(f.parent(), attempt.id()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
            owner.update("UPDATE memberships SET valid_from=now()-interval '1 day',valid_until=NULL,updated_at=now(),row_version=row_version+1 WHERE id=?", member(f.parent()));
            owner.update("UPDATE learner_guardian_relationships SET portal_access_status='ACTIVE',updated_at=now(),row_version=row_version+1 WHERE learner_id=?", f.learner());
            owner.update("UPDATE user_accounts SET status='ACTIVE' WHERE id=?", f.parent().userId());
        }
        assertFamilyGatewayNoPosting(f);
    }

    @Test void familyGatewayNewInitiationNeedsEnabledWorkerCreationAndExactConfiguredServerRouteWithoutAnyWrites() {
        for (var configuration : List.of(new boolean[]{false,false,true}, new boolean[]{true,false,true}, new boolean[]{false,true,true}, new boolean[]{true,true,false})) {
            Fixture f = fixture(true); var stack = familyGatewayStack(f, configuration[0], configuration[1], configuration[2]);
            assertThat(actorScoped(f.parent(), () -> stack.service().history(f.parent(), f.learner(), 0)).availability())
                    .isNotEqualTo(FamilyGatewayPaymentModels.Availability.AVAILABLE);
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), familyGatewayRequest(f, UUID.randomUUID(), "500"))))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE school_id=?", Integer.class, f.school())).isZero();
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE school_id=?", Integer.class, f.school())).isZero();
            assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=?", Integer.class, f.school())).isZero();
            assertFamilyGatewayNoPosting(f);
        }
        Fixture mismatch = fixture(true); var mismatchStack = familyGatewayStack(mismatch, true, true, true);
        owner.update("UPDATE payment_provider_configs SET credentials_secret_reference='synthetic:rotated',updated_at=now(),row_version=row_version+1 WHERE id=?", mismatch.provider());
        assertThatThrownBy(() -> actorScoped(mismatch.parent(), () -> mismatchStack.service().initiate(mismatch.parent(), mismatch.learner(), familyGatewayRequest(mismatch, UUID.randomUUID(), "500"))))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempts WHERE school_id=?", Integer.class, mismatch.school())).isZero();
        assertFamilyGatewayNoPosting(mismatch);
    }

    @Test void familyGatewayInitiationCommitsExactParentReservationAndQueueAtomicallyAndNeverMarksBrowserIntentPaid() throws Exception {
        Fixture f = fixture(true); var stack = familyGatewayStack(f, true, true, true);
        var request = familyGatewayRequest(f, UUID.randomUUID(), "500");
        var payment = actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), request));
        assertThat(payment.status()).isEqualTo(FamilyGatewayPaymentModels.Status.PENDING); assertThat(payment.receipt()).isNull();
        assertThat(payment.canContinue()).isFalse(); assertThat(payment.reservationActive()).isTrue(); assertThat(payment.initiatedByYou()).isTrue();
        assertThat(payment.amount()).isEqualByComparingTo("500.00"); assertThat(payment.selectedCharges()).hasSize(1);
        assertThat(owner.queryForObject("SELECT initiated_by_user_id FROM fee_gateway_attempts WHERE id=?", UUID.class, payment.id())).isEqualTo(f.parent().userId());
        assertThat(owner.queryForObject("SELECT initiated_by_membership_id FROM fee_gateway_attempts WHERE id=?", UUID.class, payment.id())).isEqualTo(member(f.parent()));
        assertThat(owner.queryForObject("SELECT payment_provider_config_id FROM fee_gateway_attempt_work WHERE attempt_id=?", UUID.class, payment.id())).isEqualTo(f.provider());
        assertThat(owner.queryForObject("SELECT credentials_secret_reference FROM fee_gateway_attempt_work WHERE attempt_id=?", String.class, payment.id())).isEqualTo("synthetic:not-enabled");
        assertThat(owner.queryForObject("SELECT create_dispatched_at FROM fee_gateway_attempt_work WHERE attempt_id=?", java.sql.Timestamp.class, payment.id())).isNull();
        var before = familyGatewayFinancialState(f);
        assertThat(actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), request))).isEqualTo(payment);
        var disabled = familyGatewayStack(f, false, false, false);
        assertThat(actorScoped(f.parent(), () -> disabled.service().initiate(f.parent(), f.learner(), request))).isEqualTo(payment);
        for (var changed : List.of(familyGatewayRequest(f, request.clientRequestId(), "501"),
                new FamilyGatewayPaymentModels.InitiateRequest(request.clientRequestId(), request.allocations(), "0000000001"))) {
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), changed))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        }
        assertThat(familyGatewayFinancialState(f)).isEqualTo(before); assertFamilyGatewayPrivateDto(payment); assertFamilyGatewayNoPosting(f);
    }

    @Test void familyGatewayConcurrentSameRequestReturnsOneWorkAndDifferentRequestsCannotReserveObligationsTwice() throws Exception {
        Fixture f = fixture(true); var stack = familyGatewayStack(f, true, true, true); var request = familyGatewayRequest(f, UUID.randomUUID(), "500");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), request)); });
            var b = workers.submit(() -> { start.await(); return actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), request)); });
            start.countDown(); assertThat(a.get(30, TimeUnit.SECONDS).id()).isEqualTo(b.get(30, TimeUnit.SECONDS).id());
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        Fixture g = fixture(true); var other = familyGatewayStack(g, true, true, true); var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { release.await(); return tryFamilyGatewayInitiate(g, other, familyGatewayRequest(g, UUID.randomUUID(), "800")); });
            var b = workers.submit(() -> { release.await(); return tryFamilyGatewayInitiate(g, other, familyGatewayRequest(g, UUID.randomUUID(), "800")); });
            release.countDown(); assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(owner.queryForObject("SELECT sum(amount) FROM fee_gateway_allocations WHERE school_id=? AND status='ACTIVE'", BigDecimal.class, g.school())).isEqualByComparingTo("800.00");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_attempt_work WHERE school_id=?", Integer.class, g.school())).isEqualTo(1);
        assertFamilyGatewayNoPosting(f); assertFamilyGatewayNoPosting(g);
    }

    @Test void familyGatewayFailedEnqueueRollsBackPreparedIntentAllocationsAndAuditRatherThanAcknowledgingPartialSuccess() {
        Fixture f = fixture(true);
        var failing = new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime), clock, Duration.ofSeconds(90)) {
            @Override public UUID enqueueAttempt(ProviderBinding binding, UUID attemptId) {
                super.enqueueAttempt(binding, attemptId);
                throw new IllegalStateException("Synthetic transaction outcome failure after real durable enqueue");
            }
        };
        var stack = familyGatewayStack(f, true, true, true, failing, new SensitiveValueCipher(properties, new SecureRandom()));
        assertThatThrownBy(() -> actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), familyGatewayRequest(f, UUID.randomUUID(), "500"))))
                .isInstanceOf(RuntimeException.class);
        for (String table : List.of("fee_gateway_attempts", "fee_gateway_allocations", "fee_gateway_attempt_work", "audit_events"))
            assertThat(owner.queryForObject("SELECT count(*) FROM " + table + " WHERE school_id=?", Integer.class, f.school())).isZero();
        assertFamilyGatewayNoPosting(f);
    }

    @Test void familyGatewayPrivateContinuationRequiresInitiatingGuardianWhileCoGuardianMayReadMinimalHistory() throws Exception {
        var test = familyGatewayHosted(false, null); var f = test.fixture(); var stack = test.stack();
        var coGuardian = familyGatewayCoGuardian(f); var before = familyGatewayFinancialState(f);
        var read = actorScoped(coGuardian, () -> stack.service().payment(coGuardian, test.attempt().id()));
        assertThat(read.initiatedByYou()).isFalse(); assertThat(read.canContinue()).isFalse(); assertFamilyGatewayPrivateDto(read);
        assertThatThrownBy(() -> actorScoped(coGuardian, () -> stack.service().continueCheckout(coGuardian, f.learner(), test.attempt().id()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var own = actorScoped(f.parent(), () -> stack.service().payment(f.parent(), test.attempt().id()));
        assertThat(own.status()).isEqualTo(FamilyGatewayPaymentModels.Status.READY); assertThat(own.canContinue()).isTrue(); assertThat(own.receipt()).isNull();
        assertFamilyGatewayPrivateDto(own);
        int priorAudit = owner.queryForObject("SELECT count(*) FROM audit_events WHERE subject_id=? AND actor_user_id=?", Integer.class, test.attempt().id().toString(), f.parent().userId());
        var continuation = actorScoped(f.parent(), () -> stack.service().continueCheckout(f.parent(), f.learner(), test.attempt().id()));
        assertThat(continuation.attemptId()).isEqualTo(test.attempt().id()); assertThat(continuation.checkoutUrl()).isEqualTo(test.url());
        assertThat(continuation.expiresAt()).isEqualTo(test.attempt().expiresAt()); assertThat(continuation.toString()).doesNotContain(test.url().toString());
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE subject_id=? AND actor_user_id=? AND actor_membership_id=?", Integer.class,
                test.attempt().id().toString(), f.parent().userId(), member(f.parent()))).isEqualTo(priorAudit+1);
        assertThat(familyGatewayFinancialState(f)).isEqualTo(before); assertFamilyGatewayNoPosting(f);
    }

    @Test void familyGatewayKnownSuccessAndConflictingPrivateLinksBlockContinuationWithoutRewritingEvidenceOrPosting() throws Exception {
        for (String kind : List.of("bound", "unbound", "conflict")) {
            var test = familyGatewayHosted(false, null); var f = test.fixture();
            UUID unboundFact = null;
            if (kind.equals("conflict")) {
                var otherUrl = java.net.URI.create("https://payments-test.cashfree.com/links/synthetic_family_conflict");
                test.store().retainCreate(FAMILY_GATEWAY_ROUTE, test.claim(), new PaymentProviderAdapter.HostedCheckout(test.attempt().id().toString(), otherUrl), familyGatewayNow());
                assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_hosted_checkouts WHERE attempt_id=?", Integer.class, test.attempt().id())).isEqualTo(2);
            } else {
                if (kind.equals("unbound")) observation(f, test.attempt(), "CREATED", "0", 0, 0);
                UUID association = kind.equals("unbound") ? null : test.attempt().id();
                Instant paidAt = familyGatewayPastPaymentTime();
                unboundFact = familyGatewayBackground(f, () -> test.stack().work().retainPaymentObservation(familyGatewayBinding(f), association,
                        new PaymentProviderAdapter.PaymentObservation("synthetic_order", "synthetic_family_payment", new BigDecimal("500.00"), "INR", PaymentProviderAdapter.PaymentStatus.SUCCESS, true, paidAt, "upi")));
            }
            var before = familyGatewayFinancialState(f);
            var status = actorScoped(f.parent(), () -> test.stack().service().payment(f.parent(), test.attempt().id()));
            assertThat(status.canContinue()).isFalse();
            if (!kind.equals("conflict")) assertThat(status.status()).isEqualTo(FamilyGatewayPaymentModels.Status.REVIEW_REQUIRED);
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> test.stack().service().continueCheckout(f.parent(), f.learner(), test.attempt().id()))).isInstanceOf(com.educore.api.common.api.ApiException.class);
            assertThat(familyGatewayFinancialState(f)).isEqualTo(before); assertFamilyGatewayNoPosting(f);
            if (kind.equals("unbound")) assertThat(owner.queryForObject("SELECT attempt_id FROM fee_gateway_payment_observations WHERE id=?", UUID.class, unboundFact)).isNull();
        }
        var paidTest = familyGatewayHosted(false, null); var paidFixture = paidTest.fixture();
        // Existing verified internal-capture fixtures exercise real receipt
        // posting; the family browser endpoint itself never asserts payment.
        UUID capture = timedCapture(paidFixture, paidTest.attempt(), familyGatewayPastPaymentTime(), "MATCHED", null, "synthetic_family_verified_receipt");
        var posted = post(paidFixture, capture, null, null);
        var paid = actorScoped(paidFixture.parent(), () -> paidTest.stack().service().payment(paidFixture.parent(), paidTest.attempt().id()));
        assertThat(paid.status()).isEqualTo(FamilyGatewayPaymentModels.Status.PAID); assertThat(paid.canContinue()).isFalse();
        assertThat(paid.receipt()).isNotNull(); assertThat(paid.receipt().paymentId()).isEqualTo(posted.paymentId());
        assertThat(paid.receipt().receiptNumber()).isEqualTo("SYN-000001"); assertFamilyGatewayPrivateDto(paid);
        Instant orphanPaidAt = familyGatewayPastPaymentTime();
        UUID orphan = familyGatewayBackground(paidFixture, () -> paidTest.stack().work().retainPaymentObservation(familyGatewayBinding(paidFixture), null,
                new PaymentProviderAdapter.PaymentObservation("synthetic_order", "synthetic_additional_unbound_payment", new BigDecimal("500.00"), "INR", PaymentProviderAdapter.PaymentStatus.SUCCESS, true, orphanPaidAt, "upi")));
        var reviewed = actorScoped(paidFixture.parent(), () -> paidTest.stack().service().payment(paidFixture.parent(), paidTest.attempt().id()));
        assertThat(reviewed.status()).isEqualTo(FamilyGatewayPaymentModels.Status.REVIEW_REQUIRED); assertThat(reviewed.canContinue()).isFalse();
        assertThat(reviewed.receipt()).isEqualTo(paid.receipt());
        assertThat(owner.queryForObject("SELECT attempt_id FROM fee_gateway_payment_observations WHERE id=?", UUID.class, orphan)).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?", Integer.class, paidFixture.school())).isEqualTo(1);

        var pending = familyGatewayHosted(false, null); var pendingFixture = pending.fixture();
        UUID uncertain = observation(pendingFixture, pending.attempt(), "PENDING", "0", 1, 0);
        UUID pendingFact = familyGatewayBackground(pendingFixture, () -> pending.stack().work().retainPaymentObservation(familyGatewayBinding(pendingFixture), pending.attempt().id(),
                new PaymentProviderAdapter.PaymentObservation("synthetic_order", "synthetic_pending_payment", new BigDecimal("500.00"), "INR", PaymentProviderAdapter.PaymentStatus.PENDING, false, null, "upi")));
        assertThat(actorScoped(pendingFixture.parent(), () -> pending.stack().service().payment(pendingFixture.parent(), pending.attempt().id())).canContinue()).isFalse();
        String beforeResolution = owner.queryForObject("SELECT row_to_json(observation)::text FROM fee_gateway_checkout_observations observation WHERE id=?", String.class, uncertain);
        var originalPending = owner.queryForMap("SELECT * FROM fee_gateway_payment_observations WHERE id=?", pendingFact);
        // A strictly later, complete, pending-free ALL-order zero observation
        // supersedes uncertainty only. No success/captured fact is erased.
        awaitFamilyGatewayTime(familyGatewayNow().plusMillis(1));
        UUID resolved = observation(pendingFixture, pending.attempt(), "CREATED", "0", 0, 0);
        assertThat(owner.queryForObject("SELECT observed_at FROM fee_gateway_checkout_observations WHERE id=?", java.sql.Timestamp.class, resolved).toInstant())
                .isAfter(owner.queryForObject("SELECT observed_at FROM fee_gateway_checkout_observations WHERE id=?", java.sql.Timestamp.class, uncertain).toInstant());
        assertThat(actorScoped(pendingFixture.parent(), () -> pending.stack().service().payment(pendingFixture.parent(), pending.attempt().id())).canContinue()).isTrue();
        assertThat(actorScoped(pendingFixture.parent(), () -> pending.stack().service().continueCheckout(pendingFixture.parent(), pendingFixture.learner(), pending.attempt().id())).checkoutUrl()).isEqualTo(pending.url());
        assertThat(owner.queryForObject("SELECT row_to_json(observation)::text FROM fee_gateway_checkout_observations observation WHERE id=?", String.class, uncertain)).isEqualTo(beforeResolution);
        assertThat(owner.queryForMap("SELECT * FROM fee_gateway_payment_observations WHERE id=?", pendingFact)).isEqualTo(originalPending);
        assertFamilyGatewayNoPosting(pendingFixture);
    }

    @Test void familyGatewayPrivateCipherKeyAssociatedDataAndUrlHashFailuresDenyDisclosureWithoutSensitiveCauses() throws Exception {
        for (String fault : List.of("key", "aad", "hash")) {
            SensitiveValueCipher retainedCipher = null;
            if (!fault.equals("key")) retainedCipher = new SensitiveValueCipher(properties, new SecureRandom()) {
                @Override public EncryptedValue encrypt(byte[] plaintext, String aad) {
                    // Legitimate AES with a deliberately defective local writer
                    // proves reader checks; never edit immutable SQL rows/guards.
                    return fault.equals("aad") ? super.encrypt(plaintext, aad+"\nsynthetic-wrong-context")
                            : super.encrypt("https://payments-test.cashfree.com/links/synthetic_wrong_payload".getBytes(StandardCharsets.UTF_8), aad);
                }
            };
            var test = familyGatewayHosted(false, retainedCipher); var f = test.fixture(); var stack = test.stack();
            if (fault.equals("key")) {
                var badProperties = new ApplicationProperties(properties.publicUrl(), List.of(), Optional.empty(), Optional.empty(),
                        new ApplicationProperties.Security("synthetic_session", true, Duration.ofHours(1), Duration.ofHours(2), Duration.ofMinutes(10), null,
                                Base64.getEncoder().encodeToString(new byte[32]).replaceFirst("A", "B"), Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofDays(1), "Synthetic school"),
                        new ApplicationProperties.Bootstrap(false, null, null));
                stack = familyGatewayStack(f, true, true, true, test.stack().work(), new SensitiveValueCipher(badProperties, new SecureRandom()));
            }
            var checkedStack = stack; var before = familyGatewayFinancialState(f);
            assertThatThrownBy(() -> actorScoped(f.parent(), () -> checkedStack.service().continueCheckout(f.parent(), f.learner(), test.attempt().id())))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class).hasNoCause().hasMessageNotContaining(test.url().toString()).hasMessageNotContaining("synthetic_wrong_payload");
            assertFamilyGatewayPrivateDto(actorScoped(f.parent(), () -> checkedStack.service().payment(f.parent(), test.attempt().id())));
            assertThat(familyGatewayFinancialState(f)).isEqualTo(before); assertFamilyGatewayNoPosting(f);
        }
    }

    @Test void familyGatewayActualFinanceLockWaitRechecksRealExpiryAndRevokedGrantBeforeReturningAnyPrivateUrl() throws Exception {
        for (boolean expires : List.of(true, false)) {
            var test = familyGatewayHosted(expires, null); var f = test.fixture();
            var before = familyGatewayFinancialState(f);
            try (var workers = Executors.newSingleThreadExecutor(); var locked = owner.getDataSource().getConnection()) {
                locked.setAutoCommit(false);
                try (var statement = locked.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    statement.setString(1, f.school()+":finance"); statement.execute();
                }
                var continued = workers.submit(() -> actorScoped(f.parent(), () -> test.stack().service().continueCheckout(f.parent(), f.learner(), test.attempt().id())));
                try {
                    awaitFamilyGatewayFinanceWait();
                    if (expires) awaitFamilyGatewayTime(test.attempt().expiresAt());
                    else owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=now(),row_version=row_version+1 WHERE id=?", member(f.parent()));
                    locked.commit();
                    assertThatThrownBy(() -> continued.get(20, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(com.educore.api.common.api.ApiException.class);
                } finally { locked.rollback(); }
            }
            assertThat(familyGatewayFinancialState(f)).isEqualTo(before); assertFamilyGatewayNoPosting(f);
        }
    }

    private static final String FAMILY_GATEWAY_ROUTE = "52dccd73b03c4e2dad0953734f737898c27a1f1bc4d3c4a939bcf7109267f08e";
    private FamilyGatewayStack familyGatewayStack(Fixture f, boolean create, boolean worker, boolean configured) {
        return familyGatewayStack(f, create, worker, configured, new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime), clock, Duration.ofSeconds(90)),
                new SensitiveValueCipher(properties, new SecureRandom()));
    }
    private FamilyGatewayStack familyGatewayStack(Fixture f, boolean create, boolean worker, boolean configured, GatewayWorkQueue work, SensitiveValueCipher cipher) {
        var binding = familyGatewayBinding(f);
        var route = new GatewayProviderRegistry.Route(FAMILY_GATEWAY_ROUTE, f.org(), f.school(), f.provider(), "CASHFREE", CashfreePaymentProviderAdapter.Environment.TEST,
                binding.merchantReference(), binding.credentialsReference(), "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        var registry = new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(create, worker, false, false, configured ? List.of(route) : List.of()),
                java.net.URI.create(properties.publicUrl()), clock,
                name -> { throw new AssertionError("Family fee browser actions cannot resolve gateway credentials"); },
                (trusted,id,secret,origin,observed) -> { throw new AssertionError("Family fee browser actions cannot construct or call a gateway provider"); });
        var named = new NamedParameterJdbcTemplate(runtime); var access = new FamilyGatewayAccess(named);
        var checkout = new FamilyGatewayCheckoutService(named, access, registry, cipher, new SecurityAuditService(runtime, JsonMapper.builder().build(), clock));
        return new FamilyGatewayStack(new FamilyGatewayPaymentService(named, access, reservations, work, registry, checkout), checkout, registry, work);
    }
    private FamilyGatewayHosted familyGatewayHosted(boolean expiresSoon, SensitiveValueCipher retainedCipher) {
        Fixture f = fixture(true); var stack = familyGatewayStack(f, true, true, true);
        if (expiresSoon && familyGatewayNow().atZone(java.time.ZoneId.of("Asia/Kolkata")).getHour()==0)
            owner.update("UPDATE schools SET time_zone='UTC',updated_at=now() WHERE id=?", f.school());
        GatewayReservationService.PlannedAttempt attempt;
        if (expiresSoon) {
            var named = new NamedParameterJdbcTemplate(runtime);
            var older = new GatewayReservationService(named, new FamilyFinanceService(named), new SecurityAuditService(runtime, JsonMapper.builder().build(), clock),
                    new SensitiveValueCipher(properties, new SecureRandom()), properties, Clock.offset(clock, Duration.ofMinutes(-30).plusSeconds(12)));
            attempt = actorScoped(f.parent(), () -> { var prepared=older.prepare(f.parent(),f.learner(),request(f,"500")); stack.work().enqueueAttempt(familyGatewayBinding(f),prepared.id()); return prepared; });
        } else {
            var payment = actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), familyGatewayRequest(f, UUID.randomUUID(), "500")));
            attempt = new GatewayReservationService.PlannedAttempt(payment.id(), "RESERVED", payment.amount(), payment.currencyCode(), payment.originalBusinessDate(), payment.expiresAt(), 0);
        }
        var claim = familyGatewayBackground(f, () -> stack.work().claimNextAttempt(familyGatewayBinding(f)));
        // Real low-level one-use SYS dispatch metadata solely for a private-link
        // reader fixture. This is not merchant/approved CREATE acceptance; that
        // independent admission is covered by GatewayWorkPostgresIT.
        assertThat(familyGatewayBackground(f, () -> stack.work().markCreateDispatched(claim))).isTrue();
        var store = new GatewayHostedCheckoutStore(stack.registry(), new NamedParameterJdbcTemplate(runtime), retainedCipher==null ? new SensitiveValueCipher(properties,new SecureRandom()) : retainedCipher,
                transactions.getTransactionManager());
        var url = java.net.URI.create("https://payments-test.cashfree.com/links/synthetic_family_link");
        store.retainCreate(FAMILY_GATEWAY_ROUTE, claim, new PaymentProviderAdapter.HostedCheckout(attempt.id().toString(), url), familyGatewayNow());
        return new FamilyGatewayHosted(f, stack, attempt, claim, store, url);
    }
    private static FamilyGatewayPaymentModels.InitiateRequest familyGatewayRequest(Fixture f, UUID requestId, String amount) {
        return new FamilyGatewayPaymentModels.InitiateRequest(requestId, List.of(new FamilyGatewayPaymentModels.ChargeSelection(f.charge(), new BigDecimal(amount))), "0000000000");
    }
    private static GatewayWorkQueue.ProviderBinding familyGatewayBinding(Fixture f) {
        return new GatewayWorkQueue.ProviderBinding(f.org(), f.school(), f.provider(), "CASHFREE", "TEST", "synthetic_merchant_"+f.provider(), "synthetic:not-enabled");
    }
    private <T> T familyGatewayBackground(Fixture f, java.util.function.Supplier<T> work) {
        try (var ignored=com.educore.api.tenancy.TenantRequestContext.open(null,f.org())) { return transactions.execute(status->work.get()); }
    }
    private boolean tryFamilyGatewayInitiate(Fixture f, FamilyGatewayStack stack, FamilyGatewayPaymentModels.InitiateRequest request) {
        try { actorScoped(f.parent(), () -> stack.service().initiate(f.parent(), f.learner(), request)); return true; }
        catch (com.educore.api.common.api.ApiException expectedConflict) { return false; }
    }
    private UUID familyGatewayUnlinkedLearner(Fixture f, boolean differentSchool) {
        UUID school=f.school(),adminMember=member(f.admin()),application=UUID.randomUUID(),learner=UUID.randomUUID();
        if (differentSchool) {
            school=UUID.randomUUID(); adminMember=UUID.randomUUID();
            owner.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,?,'Synthetic sibling school','Synthetic sibling school','ACTIVE',now(),now())", school, f.org(), "synthetic-"+school);
            owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())", adminMember, f.admin().userId(), f.org(), school);
            owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001')", adminMember);
        }
        owner.update("""
                INSERT INTO admission_applications(id,organisation_id,school_id,idempotency_key,request_fingerprint,intake_session_label,target_grade_code,applicant_legal_name,birth_date,created_by_membership_id,created_at,updated_at)
                VALUES(?,?,?, ?,repeat('c',64),'Synthetic session','VI','Synthetic unlinked applicant',DATE '2015-01-01',?,now(),now())
                """, application, f.org(), school, "synthetic-"+application, adminMember);
        owner.update("INSERT INTO learners(id,organisation_id,school_id,origin_application_id,legal_name,birth_date,gender_code,status,created_at,updated_at) VALUES(?,?,?,?,'Synthetic unlinked learner',DATE '2015-01-01','NOT_STATED','ACTIVE',now(),now())", learner, f.org(), school, application);
        return learner;
    }
    private UUID familyGatewayLaterAccount(Fixture f) {
        UUID session=UUID.randomUUID(),application=UUID.randomUUID(),enrolment=UUID.randomUUID(),account=UUID.randomUUID();
        UUID campus=owner.queryForObject("SELECT campus_id FROM student_enrolments WHERE id=(SELECT enrolment_id FROM learner_fee_accounts WHERE id=?)",UUID.class,f.account());
        // A relationally real later-session/current-account fixture; this does
        // not claim acceptance of the separate academic-rollover workflow.
        owner.update("UPDATE student_enrolments SET status='ENDED',ends_on=?,updated_at=now(),row_version=row_version+1 WHERE id=(SELECT enrolment_id FROM learner_fee_accounts WHERE id=?)",schoolToday().minusDays(1),f.account());
        owner.update("UPDATE academic_sessions SET status='CLOSED',updated_at=now(),row_version=row_version+1 WHERE id=(SELECT academic_session_id FROM learner_fee_accounts WHERE id=?) AND status='ACTIVE'",f.account());
        owner.update("INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,'Synthetic later current session',?,?,'ACTIVE',now(),now())",session,f.org(),f.school(),schoolToday(),schoolToday().plusDays(330));
        owner.update("""
                INSERT INTO admission_applications(id,organisation_id,school_id,idempotency_key,request_fingerprint,intake_session_label,target_grade_code,applicant_legal_name,birth_date,created_by_membership_id,created_at,updated_at)
                VALUES(?,?,?, ?,repeat('c',64),'Synthetic later session','VII','Synthetic continuing applicant',DATE '2015-01-01',?,now(),now())
                """,application,f.org(),f.school(),"synthetic-"+application,member(f.admin()));
        owner.update("INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,origin_application_id,grade_code,status,starts_on,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'VII','ACTIVE',?,now(),now())",enrolment,f.org(),f.school(),campus,session,f.learner(),application,schoolToday());
        owner.update("INSERT INTO learner_fee_accounts(id,organisation_id,school_id,academic_session_id,learner_id,enrolment_id,currency_code,opened_by_membership_id,opened_at) VALUES(?,?,?,?,?,?,'INR',?,now())",account,f.org(),f.school(),session,f.learner(),enrolment,member(f.admin()));
        return account;
    }
    private AuthenticatedUser familyGatewayCoGuardian(Fixture f) {
        UUID user=UUID.randomUUID(),member=UUID.randomUUID(),guardian=UUID.randomUUID();
        owner.update("INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status) VALUES(?,?,?,'Synthetic co-guardian','not-a-login-hash','ACTIVE')", user,"synthetic-"+user+"@example.test","synthetic-"+user+"@example.test");
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())", member,user,f.org(),f.school());
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000006')",member);
        owner.update("INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,created_at,updated_at,user_account_id) VALUES(?,?,?,'Synthetic co-guardian',?,'ACTIVE',now(),now(),?)",guardian,f.org(),f.school(),"synthetic-"+user+"@example.test",user);
        owner.update("INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,relationship_code,portal_access_status,created_at,updated_at) VALUES(?,?,?,?,'PARENT_GUARDIAN','ACTIVE',now(),now())",f.learner(),guardian,f.org(),f.school());
        return actor(user,member,f.org());
    }
    private static AuthenticatedUser familyGatewayActor(AuthenticatedUser original, Set<UUID> membershipIds) {
        return new AuthenticatedUser(original.userId(),original.sessionId(),original.loginIdentifier(),null,"Synthetic detached session",true,
                original.activeOrganisationId(),membershipIds,Set.of("portal.guardian"),List.of());
    }
    private java.util.Map<String,List<String>> familyGatewayFinancialState(Fixture f) {
        var state = new java.util.LinkedHashMap<String,List<String>>();
        for (String table : List.of("fee_gateway_attempts","fee_gateway_allocations","fee_gateway_attempt_work","fee_gateway_hosted_checkouts","fee_gateway_captures",
                "fee_gateway_checkout_observations","fee_gateway_payment_observations","fee_gateway_webhook_inbox",
                "fee_gateway_capture_resolutions","fee_payments","fee_ledger_entries","fee_receipt_sequences","accounting_journals"))
            state.put(table,owner.queryForList("SELECT row_to_json(snapshot)::text FROM "+table+" snapshot WHERE school_id=? ORDER BY row_to_json(snapshot)::text",String.class,f.school()));
        return state;
    }
    private void assertFamilyGatewayNoPosting(Fixture f) {
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE school_id=? AND direction='CREDIT'",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?",Long.class,f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM accounting_journals WHERE school_id=?",Integer.class,f.school())).isZero();
    }
    private static void assertFamilyGatewayPrivateDto(Object response) {
        String json=JsonMapper.builder().build().writeValueAsString(response);
        assertThat(json).doesNotContain("checkoutUrl","synthetic_family_link","payerPhone","payer_phone","ciphertext","nonce","routeKey","credentials","merchant",
                "leaseToken","lease_token","body_sha256","synthetic:not-enabled",FAMILY_GATEWAY_ROUTE);
    }
    private Instant familyGatewayNow() {
        return owner.queryForObject("SELECT clock_timestamp()", (r,n)->r.getObject(1,java.time.OffsetDateTime.class).toInstant());
    }
    private Instant familyGatewayPastPaymentTime() {
        // Synthetic provider payment time must precede both clocks: Windows'
        // application clock may lag PostgreSQL's higher-resolution clock.
        Instant applicationNow = clock.instant();
        Instant databaseNow = familyGatewayNow();
        return (applicationNow.isBefore(databaseNow) ? applicationNow : databaseNow).minusMillis(1);
    }
    private void awaitFamilyGatewayTime(Instant deadline) {
        owner.queryForObject("SELECT pg_sleep(greatest(0,extract(epoch FROM (?::timestamptz-clock_timestamp())))+0.03)", (r,n)->true, GatewayExactInstant.parameter(deadline));
    }
    private void awaitFamilyGatewayFinanceWait() throws InterruptedException {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do {
            if (Boolean.TRUE.equals(control.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=? AND usename=? AND wait_event_type='Lock' AND state='active' AND position('pg_advisory_xact_lock' IN query)>0)",Boolean.class,database,runtimeLogin))) return;
            Thread.sleep(10);
        } while(System.nanoTime()<limit);
        throw new IllegalStateException("Actual non-owner family continuation did not reach its held finance lock");
    }
    private record FamilyGatewayStack(FamilyGatewayPaymentService service,FamilyGatewayCheckoutService checkout,GatewayProviderRegistry registry,GatewayWorkQueue work) {}
    private record FamilyGatewayHosted(Fixture fixture,FamilyGatewayStack stack,GatewayReservationService.PlannedAttempt attempt,GatewayWorkQueue.Claim claim,
            GatewayHostedCheckoutStore store,java.net.URI url) {}

    private static final List<String> REFUND_EVIDENCE_TABLES=List.of("fee_gateway_refund_requests","fee_gateway_refund_allocations","fee_gateway_refund_decisions");
    @Test void refundInboxRecoversApprovedSuccessThroughIndependentGetWithoutChangingEconomicRecords() {
        var test=refundReadApproved();var f=test.fixture();var driver=refundInboxDriver(test);
        var money=refundFinancialState(f);var expected=refundReadPrepare(test).expected();
        var facts=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123));
        org.mockito.Mockito.when(driver.provider().fetchRefund(expected.orderReference(),test.refundId().toString())).thenAnswer(call->{refundInboxOutsidePersistence();return facts;});
        UUID inbox=refundInboxEnqueue(driver,test.refundId().toString(),expected.orderReference(),expected.paymentReference());
        String sameBody=owner.queryForObject("SELECT body_sha256 FROM fee_gateway_webhook_inbox WHERE id=?",String.class,inbox);
        assertThat(refundInboxEnqueue(driver,test.refundId().toString(),expected.orderReference(),expected.paymentReference(),sameBody)).isEqualTo(inbox);
        assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?",String.class,inbox)).isEqualTo("COMPLETE");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_successes WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_postings WHERE refund_request_id=?",Integer.class,test.refundId())).isZero();
        var anchor=refundSuccessState(test);assertThat(refundFinancialState(f)).isEqualTo(money);
        assertThat(refundInboxEnqueue(driver,test.refundId().toString(),expected.orderReference(),expected.paymentReference(),sameBody)).isEqualTo(inbox);
        assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.IDLE);
        refundInboxEnqueue(driver,test.refundId().toString(),expected.orderReference(),expected.paymentReference());
        assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(refundSuccessState(test)).isEqualTo(anchor);assertThat(refundFinancialState(f)).isEqualTo(money);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(2);
        org.mockito.Mockito.verify(driver.provider(),org.mockito.Mockito.times(2)).fetchRefund(expected.orderReference(),test.refundId().toString());
        refundInboxNoMutation(driver.provider());
    }

    @Test void refundInboxRetainsContradictionForReviewWithoutReplacingAnEarlierVerifiedAnchor() {
        var test=refundReadApproved();var f=test.fixture();var driver=refundInboxDriver(test);var expected=refundReadPrepare(test).expected();
        var first=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123));
        org.mockito.Mockito.when(driver.provider().fetchRefund(expected.orderReference(),test.refundId().toString())).thenReturn(first);
        refundInboxEnqueue(driver,test.refundId().toString(),expected.orderReference(),expected.paymentReference());
        assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var anchor=refundSuccessState(test);var money=refundFinancialState(f);
        var pending=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.PENDING,null);
        org.mockito.Mockito.when(driver.provider().fetchRefund(expected.orderReference(),test.refundId().toString())).thenReturn(pending);
        refundInboxEnqueue(driver,test.refundId().toString(),expected.orderReference(),expected.paymentReference());
        assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(refundSuccessState(test)).isEqualTo(anchor);assertThat(refundFinancialState(f)).isEqualTo(money);
        var contradictory=new PaymentProviderAdapter.RefundObservation(first.refundReference(),first.orderReference(),first.paymentReference(),new BigDecimal("51.25"),"INR",first.status(),first.processedAt());
        org.mockito.Mockito.when(driver.provider().fetchRefund(expected.orderReference(),test.refundId().toString())).thenReturn(contradictory);
        UUID inbox=refundInboxEnqueue(driver,test.refundId().toString(),expected.orderReference(),expected.paymentReference());
        assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?",String.class,inbox)).isEqualTo("REVIEW_REQUIRED");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=? AND outcome='REVIEW_REQUIRED'",Integer.class,test.refundId())).isEqualTo(1);
        assertThat(refundSuccessState(test)).isEqualTo(anchor);assertThat(refundFinancialState(f)).isEqualTo(money);refundInboxNoMutation(driver.provider());
    }

    @Test void refundInboxCannotAssociateForeignOrUnapprovedIntentOrReadUnrelatedProviderMoney() {
        var test=refundReadApproved();var f=test.fixture();var driver=refundInboxDriver(test);var expected=refundReadPrepare(test).expected();
        var foreign=refundReadApproved();var foreignBefore=refundReadProtectedState(foreign.fixture());var money=refundFinancialState(f);
        UUID first=refundInboxEnqueue(driver,foreign.refundId().toString(),expected.orderReference(),expected.paymentReference());
        assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?",String.class,first)).isEqualTo("REVIEW_REQUIRED");
        var pending=refundSubmit(f,f.admin(),test.paymentId(),refundSubmit(f,UUID.randomUUID(),"10.00"));
        UUID second=refundInboxEnqueue(driver,pending.id().toString(),expected.orderReference(),expected.paymentReference());
        assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?",String.class,second)).isEqualTo("REVIEW_REQUIRED");
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(refundReadProtectedState(foreign.fixture())).isEqualTo(foreignBefore);assertThat(refundFinancialState(f)).isEqualTo(money);
        org.mockito.Mockito.verify(driver.provider(),org.mockito.Mockito.never()).fetchRefund(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());refundInboxNoMutation(driver.provider());
    }

    @Test void refundInboxFailedCompletionRollsBackNewHoldButPreservesTheAlreadyCommittedRead() {
        var test=refundReadApproved();var f=test.fixture();var driver=refundInboxDriver(test);var expected=refundReadPrepare(test).expected();
        var facts=refundReadFacts(expected,PaymentProviderAdapter.RefundStatus.SUCCESS,expected.approvedAt().plusNanos(123));
        org.mockito.Mockito.when(driver.provider().fetchRefund(expected.orderReference(),test.refundId().toString())).thenReturn(facts);
        UUID inbox=refundInboxEnqueue(driver,test.refundId().toString(),expected.orderReference(),expected.paymentReference());
        var money=refundFinancialState(f);String trigger=refundInboxTriggerIdentifier();
        owner.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic refund inbox completion rejection'; END; $$");
        owner.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON fee_gateway_webhook_inbox FOR EACH ROW WHEN (NEW.id='"+inbox+"'::uuid AND NEW.status='COMPLETE') EXECUTE FUNCTION "+trigger+"()");
        try{
            assertThat(driver.worker().runNext(driver.routeKey())).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
            assertThat(owner.queryForObject("SELECT status FROM fee_gateway_webhook_inbox WHERE id=?",String.class,inbox)).isEqualTo("LEASED");
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_reads WHERE refund_request_id=?",Integer.class,test.refundId())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_refund_successes WHERE refund_request_id=?",Integer.class,test.refundId())).isZero();
            assertThat(refundFinancialState(f)).isEqualTo(money);refundInboxNoMutation(driver.provider());
        }finally{owner.execute("DROP TRIGGER "+trigger+" ON fee_gateway_webhook_inbox");owner.execute("DROP FUNCTION "+trigger+"()");}
    }

    private static String refundInboxTriggerIdentifier(){
        String generated="synthetic_refund_finish_"+UUID.randomUUID().toString().replace("-","");
        if(!generated.matches("synthetic_refund_finish_[a-f0-9]{32}"))throw new IllegalArgumentException("Generated synthetic refund trigger required");
        return '"'+generated+'"';
    }

    private RefundInboxPg refundInboxDriver(RefundReadFixture test){
        var f=test.fixture();var binding=familyGatewayBinding(f);var named=new NamedParameterJdbcTemplate(runtime);
        String key="82".repeat(32);var provider=org.mockito.Mockito.mock(PaymentProviderAdapter.class);
        org.mockito.Mockito.when(provider.providerCode()).thenAnswer(call->{refundInboxOutsidePersistence();return "CASHFREE";});
        var route=new GatewayProviderRegistry.Route(key,f.org(),f.school(),f.provider(),"CASHFREE",CashfreePaymentProviderAdapter.Environment.TEST,binding.merchantReference(),binding.credentialsReference(),"env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        var registry=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,List.of(route)),java.net.URI.create("https://synthetic-school.example.test"),clock,
                name->{refundInboxOutsidePersistence();return "synthetic-only-not-a-real-credential";},(selected,id,secret,origin,observedClock)->{refundInboxOutsidePersistence();assertThat(selected).isEqualTo(route);return provider;});
        var work=new GatewayWorkQueue(named,clock);var attemptDispatch=org.mockito.Mockito.mock(GatewayAttemptDispatch.class);
        var inboxDispatch=new GatewayInboxDispatch(named,registry,attemptDispatch);var discovery=new GatewayProviderDiscovery(clock);
        var readStore=new GatewayRefundReadStore(named);var successStore=new GatewayRefundSuccessStore(named,readStore);
        var recovery=new GatewayRefundInboxRecovery(named,inboxDispatch,work,readStore,successStore,discovery,transactions.getTransactionManager());
        var finalizer=org.mockito.Mockito.mock(GatewayObservationFinalizer.class);
        return new RefundInboxPg(test,key,work,new GatewayWebhookWorker(registry,named,work,inboxDispatch,discovery,finalizer,recovery,transactions.getTransactionManager(),clock),provider);
    }
    private UUID refundInboxEnqueue(RefundInboxPg driver,String refund,String order,String payment){
        return refundInboxEnqueue(driver,refund,order,payment,UUID.randomUUID().toString().replace("-","").repeat(2));
    }
    private UUID refundInboxEnqueue(RefundInboxPg driver,String refund,String order,String payment,String bodyHash){
        var event=new PaymentProviderAdapter.VerifiedWebhook("CASHFREE",bodyHash,"REFUND_STATUS_WEBHOOK",null,order,payment,refund);
        return familyGatewayBackground(driver.test().fixture(),()->driver.work().retainVerifiedWebhook(familyGatewayBinding(driver.test().fixture()),event));
    }
    private static void refundInboxOutsidePersistence(){assertThat(com.educore.api.tenancy.TenantRequestContext.current()).isEmpty();assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()).isEmpty();}
    private static void refundInboxNoMutation(PaymentProviderAdapter provider){org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).requestRefund(org.mockito.ArgumentMatchers.any());org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).createHostedCheckout(org.mockito.ArgumentMatchers.any(PaymentProviderAdapter.CheckoutRequest.class));org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).cancelCheckout(org.mockito.ArgumentMatchers.any());org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).fetchPayments(org.mockito.ArgumentMatchers.anyString());}
    private record RefundInboxPg(RefundReadFixture test,String routeKey,GatewayWorkQueue work,GatewayWebhookWorker worker,PaymentProviderAdapter provider){}

    private record RefundReadFixture(Fixture fixture,UUID paymentId,UUID refundId) {}
    private GatewayRefundReadStore refundReadStore() {return new GatewayRefundReadStore(new NamedParameterJdbcTemplate(runtime));}
    private RefundReadFixture refundReadApproved() {
        Fixture f=refundFixture();var attempt=prepare(f,request(f,"200"));
        // Explicit sub-microsecond original, floored rather than rounded by JDBC.
        Instant originalPaid=GatewayExactInstant.floor(familyGatewayPastPaymentTime()).minusNanos(1);
        UUID capture=timedCapture(f,attempt,originalPaid,"MATCHED",null,"synthetic_refund_read_source_"+UUID.randomUUID());
        UUID payment=post(f,capture,null,null).paymentId();
        UUID request=refundSubmit(f,f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"50.25")).id();
        refundDecide(f,f.reviewer(),request,"APPROVE");return new RefundReadFixture(f,payment,request);
    }
    private GatewayRefundReadStore.Prepared refundReadPrepare(RefundReadFixture test) {
        return familyGatewayBackground(test.fixture(),()->refundReadStore().prepare(familyGatewayBinding(test.fixture()),test.refundId()));
    }
    private GatewayRefundReadStore.Read refundReadRetain(RefundReadFixture test,UUID id,PaymentProviderAdapter.RefundObservation facts) {
        return familyGatewayBackground(test.fixture(),()->refundReadStore().retain(familyGatewayBinding(test.fixture()),test.refundId(),id,facts));
    }
    private static PaymentProviderAdapter.RefundObservation refundReadFacts(GatewayRefundVerification.Expected expected,PaymentProviderAdapter.RefundStatus status,Instant processed) {
        return new PaymentProviderAdapter.RefundObservation(expected.refundId().toString(),expected.orderReference(),expected.paymentReference(),expected.refundAmount(),"INR",status,processed);
    }
    private java.util.Map<String,List<String>> refundReadIntentState(Fixture f) {
        var state=new java.util.LinkedHashMap<String,List<String>>();
        for(String table:REFUND_EVIDENCE_TABLES)
            state.put(table,owner.queryForList("SELECT row_to_json(snapshot)::text FROM "+table+" snapshot WHERE school_id=? ORDER BY row_to_json(snapshot)::text",String.class,f.school()));
        return state;
    }
    private java.util.Map<String,List<String>> refundReadProtectedState(Fixture f) {
        var state=new java.util.LinkedHashMap<String,List<String>>(refundFinancialState(f));state.putAll(refundReadIntentState(f));
        for(String table:List.of("payment_provider_configs","fee_gateway_attempt_work","fee_gateway_webhook_inbox","fee_gateway_payment_observations","fee_gateway_hosted_checkouts","audit_events"))
            state.put(table,owner.queryForList("SELECT row_to_json(snapshot)::text FROM "+table+" snapshot WHERE school_id=? ORDER BY row_to_json(snapshot)::text",String.class,f.school()));
        return state;
    }
    /** Internal constant SQL expressions deliberately try to bypass Java and prove PostgreSQL's own guards. */
    private int rawRefundReadCopy(UUID source,UUID copy,java.util.Map<String,String> overrides) {
        var columns=List.of("id","organisation_id","school_id","refund_request_id","approval_decision_id","payment_provider_config_id","provider_code","provider_environment",
                "merchant_account_reference","credentials_secret_reference","observed_at","evidence_sha256","refund_reference","order_reference","payment_reference","amount","currency_code",
                "refund_status","processed_at","processed_at_exact","outcome","processed_on");
        if(!columns.containsAll(overrides.keySet()) || overrides.containsKey("id"))throw new IllegalArgumentException("Only fixed synthetic witness columns may be overridden");
        var selected=columns.stream().map(column->column.equals("id")?"?":overrides.getOrDefault(column,column)).collect(java.util.stream.Collectors.joining(","));
        return runtime.update("INSERT INTO fee_gateway_refund_reads("+String.join(",",columns)+") SELECT "+selected+" FROM fee_gateway_refund_reads WHERE id=?",copy,source);
    }
    private Fixture refundFixture() {return refundFixture(false);}
    private Fixture refundFixture(boolean split) {
        Fixture f=fixture(true,split);
        // Only these isolated synthetic fee heads have an explicit refundable policy.
        owner.update("UPDATE fee_heads SET refundable=true,row_version=row_version+1 WHERE school_id=?",f.school());
        return f;
    }
    private GatewayRefundService refundService() {
        return new GatewayRefundService(new NamedParameterJdbcTemplate(runtime),finance,new SecurityAuditService(runtime,JsonMapper.builder().build(),clock),clock);
    }
    private FinanceService.GatewayPostingResult refundPostedPayment(Fixture f,String amount) {
        var attempt=prepare(f,request(f,amount));
        UUID capture=timedCapture(f,attempt,familyGatewayPastPaymentTime(),"MATCHED",null,"synthetic_refund_source_"+UUID.randomUUID());
        var result=post(f,capture,null,null);assertThat(result.status()).isEqualTo("POSTED");return result;
    }
    private static GatewayRefundModels.Submit refundSubmit(Fixture f,UUID key,String amount) {
        return new GatewayRefundModels.Submit(f.school(),key,List.of(new GatewayRefundModels.Selection(f.charge(),new BigDecimal(amount))),"Synthetic reviewed refund intent only");
    }
    private GatewayRefundModels.Result refundSubmit(Fixture f,AuthenticatedUser actor,UUID payment,GatewayRefundModels.Submit command) {
        return actorScoped(actor,()->refundService().submit(actor,payment,command));
    }
    private GatewayRefundModels.Result refundDecide(Fixture f,AuthenticatedUser actor,UUID request,String decision) {
        return actorScoped(actor,()->refundService().decide(actor,request,new GatewayRefundModels.DecisionCommand(f.school(),decision,"Synthetic independent refund decision")));
    }
    private GatewayRefundModels.Payment refundPayment(Fixture f,AuthenticatedUser actor,UUID payment) {
        return actorScoped(actor,()->refundService().payment(actor,f.school(),payment));
    }
    private GatewayRefundModels.Queue refundQueue(Fixture f,AuthenticatedUser actor,String status,String query,int page,int size) {
        return actorScoped(actor,()->refundService().queue(actor,f.school(),status,query,page,size));
    }
    private java.util.Map<String,List<String>> refundFinancialState(Fixture f) {
        var state=new java.util.LinkedHashMap<String,List<String>>();
        for(String table:List.of("fee_gateway_attempts","fee_gateway_allocations","fee_gateway_captures","fee_gateway_capture_resolutions",
                "fee_payments","fee_payment_allocations","fee_ledger_entries","fee_receipt_sequences","fee_manual_collection_evidence",
                "learner_fee_accounts","learner_fee_charges","fee_concession_requests","fee_day_closes",
                "accounting_journals","accounting_journal_lines","accounting_journal_history"))
            // Normalize only the documented additive default in a pre-V077
            // snapshot; preserve every old field and all financial evidence.
            state.put(table,owner.queryForList("SELECT "+(table.equals("fee_gateway_attempts")
                    ? "(to_jsonb(snapshot)||jsonb_build_object('account_selection_mode',COALESCE(to_jsonb(snapshot)->>'account_selection_mode','CURRENT_SESSION')))::text"
                    : table.equals("fee_day_closes")
                    ? "(to_jsonb(snapshot)||jsonb_build_object('refund_count',COALESCE(to_jsonb(snapshot)->>'refund_count','0')::int,'refund_amount',COALESCE(to_jsonb(snapshot)->>'refund_amount','0')::numeric(14,2)))::text"
                    : "row_to_json(snapshot)::text")+" FROM "+table+" snapshot WHERE school_id=? ORDER BY row_to_json(snapshot)::text",String.class,f.school()));
        return state;
    }
    private boolean tryConcurrentRefund(Fixture f,UUID payment,boolean repeatable,CountDownLatch ready,CountDownLatch start) throws InterruptedException {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));tx.setTimeout(20);
        if(repeatable) tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(f.admin().userId(),f.org())) {
            try {
                return tx.execute(status->{
                    // Both RR transactions have an established old MVCC snapshot
                    // before either attempts the real finance/capture write fence.
                    assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_refund_requests WHERE school_id=?",Integer.class,f.school())).isZero();
                    ready.countDown();
                    try {if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic refund start barrier timed out");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                    refundService().submit(f.admin(),payment,refundSubmit(f,UUID.randomUUID(),"120"));return true;
                });
            } catch(com.educore.api.common.api.ApiException | DataAccessException denied) {return false;}
        }
    }
    /** Intentionally bypasses the Java service to exercise PostgreSQL's own proof and deferred budget guards. */
    private UUID rawRefund(Fixture f,UUID payment,String amount,String allocationAmount,boolean forgedPaymentReference) {
        UUID id=UUID.randomUUID();
        int inserted=runtime.update("""
                INSERT INTO fee_gateway_refund_requests(id,organisation_id,school_id,fee_payment_id,learner_fee_account_id,capture_id,
                  payment_provider_config_id,provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,
                  order_reference,payment_reference,currency_code,school_time_zone,amount,client_request_id,request_fingerprint,
                  request_reason,requested_by_user_id,requested_by_membership_id,requested_at)
                SELECT ?,payment.organisation_id,payment.school_id,payment.id,payment.learner_fee_account_id,capture.id,
                  provider.id,capture.provider_code,capture.provider_environment,capture.merchant_account_reference,provider.credentials_secret_reference,
                  capture.order_reference,CASE WHEN ? THEN 'synthetic_forged_payment' ELSE capture.payment_reference END,
                  capture.currency_code,attempt.school_time_zone,?,?,repeat('e',64),'Synthetic raw guarded intent',?,?,clock_timestamp()
                FROM fee_payments payment JOIN fee_gateway_captures capture ON capture.id=payment.gateway_capture_id
                JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
                JOIN payment_provider_configs provider ON provider.id=capture.payment_provider_config_id
                WHERE payment.id=? AND payment.organisation_id=? AND payment.school_id=?
                """,id,forgedPaymentReference,new BigDecimal(amount),UUID.randomUUID(),f.admin().userId(),member(f.admin()),payment,f.org(),f.school());
        assertThat(inserted).isEqualTo(1);
        if(allocationAmount!=null) {
            int allocated=runtime.update("""
                    INSERT INTO fee_gateway_refund_allocations(id,organisation_id,school_id,refund_request_id,fee_payment_id,learner_fee_account_id,
                      original_payment_allocation_id,learner_fee_charge_id,fee_head_id,amount,policy_refundable)
                    SELECT ?,source.organisation_id,source.school_id,?,source.fee_payment_id,charge.learner_fee_account_id,
                      source.id,charge.id,item.fee_head_id,?,true
                    FROM fee_payment_allocations source JOIN learner_fee_charges charge ON charge.id=source.learner_fee_charge_id
                    JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id
                    WHERE source.fee_payment_id=? AND source.learner_fee_charge_id=? AND source.school_id=?
                    """,UUID.randomUUID(),id,new BigDecimal(allocationAmount),payment,f.charge(),f.school());
            assertThat(allocated).isEqualTo(1);
        }
        return id;
    }
    private UUID rawRefundDecision(Fixture f,AuthenticatedUser actor,UUID request,String decision) {
        UUID id=UUID.randomUUID();
        runtime.update("""
                INSERT INTO fee_gateway_refund_decisions(id,organisation_id,school_id,refund_request_id,decision,reason,
                  decided_by_user_id,decided_by_membership_id,decided_at)
                VALUES(?,?,?,?,?,'Synthetic direct guarded decision',?,?,clock_timestamp())
                """,id,f.org(),f.school(),request,decision,actor.userId(),member(actor));
        return id;
    }

    private FinanceMutationResponse collect(Fixture f,String key,FinanceController.PaymentRequest request) {
        return actorScoped(f.admin(),()->finance.recordPayment(f.admin(),key,request));
    }
    private boolean tryCollect(Fixture f,String key,FinanceController.PaymentRequest request) {
        try { collect(f,key,request);return true; }
        catch(com.educore.api.common.api.ApiException denied) { return false; }
    }
    private FamilyFeesResponse familyFees(Fixture f) {
        return actorScoped(f.parent(),()->new FamilyFinanceService(new NamedParameterJdbcTemplate(runtime)).fees(f.parent(),f.learner()));
    }
    private static UUID member(AuthenticatedUser actor) { return actor.membershipIds().iterator().next(); }
    private static LocalDate schoolToday() { return LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")); }
    private static Instant manualInstant() { return Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private static FinanceController.PaymentRequest manualPayment(Fixture f,String method,String reference,String amount,
            Instant actual,LocalDate posting,ManualCollectionEvidence.BankCreditVerification credit,String reason) {
        return new FinanceController.PaymentRequest(f.school(),f.account(),method,reference,new BigDecimal(amount),
                LocalDate.ofInstant(actual,java.time.ZoneId.of("Asia/Kolkata")),"Synthetic verified received funds",
                List.of(new FinanceController.PaymentAllocation(f.charge(),new BigDecimal(amount))),actual,posting,credit,reason);
    }
    private void assertManualDenied(Fixture f) {
        assertThatThrownBy(()->collect(f,"synthetic-live-denial-"+UUID.randomUUID(),payment(f,"10")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
    }
    private void assertManualAccountUntouched(Fixture f) {
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payments WHERE learner_fee_account_id=?",Integer.class,f.account())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_payment_allocations WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE learner_fee_account_id=? AND entry_type='PAYMENT'",Integer.class,f.account())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT next_value FROM fee_receipt_sequences WHERE school_id=?",Long.class,f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action LIKE 'finance.manual.collection.%'",Integer.class,f.school())).isZero();
    }
    /** Deliberately incomplete/inconsistent direct runtime writes must fail real deferred PostgreSQL guards. */
    private UUID rawManualReceipt(Fixture f,String amount,String allocationAmount,String ledgerAmount,boolean withEvidence) {
        UUID payment=UUID.randomUUID(),ledger=UUID.randomUUID();
        var evidence=new ManualCollectionEvidence(new NamedParameterJdbcTemplate(runtime));
        var scope=new ManualCollectionEvidence.ValueScope(f.org(),f.school(),member(f.admin()),"Asia/Kolkata");
        var prepared=evidence.prepare(scope,"CASH",null,new ManualCollectionEvidence.EvidenceInput(null,schoolToday(),null,null,null));
        evidence.assertEligible(scope,prepared);
        runtime.update("""
                INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,entry_type,direction,amount,
                  effective_on,description,source_type,source_id,created_by_membership_id,created_at,actor_kind)
                VALUES(?,?,?,?,'PAYMENT','CREDIT',?,?,'Synthetic incomplete manual writer','FEE_PAYMENT',?,?,now(),'HUMAN')
                """,ledger,f.org(),f.school(),f.account(),new BigDecimal(ledgerAmount),schoolToday(),payment,member(f.admin()));
        runtime.update("""
                INSERT INTO fee_payments(id,organisation_id,school_id,learner_fee_account_id,receipt_number,idempotency_key,
                  request_fingerprint,payment_method,amount,received_on,status,ledger_entry_id,received_by_membership_id,
                  created_at,updated_at,actor_kind)
                VALUES(?,?,?, ?,?,?,repeat('d',64),'CASH',?,?,'POSTED',?,?,now(),now(),'HUMAN')
                """,payment,f.org(),f.school(),f.account(),"SYN-DIRECT-"+payment,"synthetic-direct-"+payment,new BigDecimal(amount),schoolToday(),ledger,member(f.admin()));
        runtime.update("INSERT INTO fee_payment_allocations(id,organisation_id,school_id,fee_payment_id,learner_fee_charge_id,amount,created_at) VALUES(?,?,?,?,?,?,now())",
                UUID.randomUUID(),f.org(),f.school(),payment,f.charge(),new BigDecimal(allocationAmount));
        if(withEvidence) evidence.record(scope,payment,prepared);
        return payment;
    }

    private GatewayCaptureQueueResponse queue(Fixture f,AuthenticatedUser user,String query,String state,int page,int size) {
        return actorScoped(user,()->finance.gatewayCaptures(user,f.school(),query,state,page,size));
    }
    private FinanceService.GatewayPostingResult reviewedPost(Fixture f,AuthenticatedUser user,UUID capture,LocalDate date,String reason) {
        return actorScoped(user,()->finance.postReviewedGatewayCapture(user,capture,new FinanceController.GatewayCapturePostingRequest(f.school(),date,reason)));
    }
    private <T> T actorScoped(AuthenticatedUser actor,java.util.function.Supplier<T> work) {
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(actor.userId(),actor.activeOrganisationId())) {
            return transactions.execute(status->work.get());
        }
    }
    private void assertReviewDenied(Fixture f,UUID capture,LocalDate date) {
        assertThatThrownBy(()->queue(f,f.admin(),null,"ALL",0,25)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->reviewedPost(f,f.admin(),capture,date,"Synthetic stale financial actor denial"))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
    }
    private UUID financeRole(UUID organisation,List<String> capabilities) {
        UUID id=UUID.randomUUID();
        owner.update("INSERT INTO roles(id,organisation_id,code,display_name,system_role,created_at,updated_at) VALUES(?,?,'SYNTHETIC_REVIEW','Synthetic review role',false,now(),now())",id,organisation);
        for(String capability:capabilities) owner.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES(?,?)",id,capability);
        return id;
    }

    private FinanceService.GatewayPostingResult post(Fixture f,UUID capture,AuthenticatedUser reviewer,LocalDate date) {
        try(var ignored=com.educore.api.tenancy.TenantRequestContext.open(reviewer==null?null:reviewer.userId(),f.org())) {
            if(legacyFinanceSchema) {
                if(reviewer!=null||date!=null) throw new IllegalStateException("Prior-schema receipt population supports only original-date SYSTEM fixtures");
                return transactions.execute(status->LegacyGatewayReceiptFixture.seed(runtime,properties,
                        new SecurityAuditService(runtime,JsonMapper.builder().build(),clock),f.org(),f.school(),capture));
            }
            return transactions.execute(status->finance.postVerifiedGatewayCapture(f.org(),f.school(),capture,reviewer,date,reviewer==null?null:"Synthetic accountant reviewed posting"));
        }
    }
    private GatewayReservationService.PlannedAttempt prepareAt(Fixture f,GatewayReservationService.PrepareRequest request,Clock testClock) {
        var named=new NamedParameterJdbcTemplate(runtime);
        var service=new GatewayReservationService(named,new FamilyFinanceService(named),new SecurityAuditService(runtime,JsonMapper.builder().build(),clock),
                new SensitiveValueCipher(properties,new SecureRandom()),properties,testClock);
        return scoped(f,()->service.prepare(f.parent(),f.learner(),request));
    }
    private UUID timedCapture(Fixture f,GatewayReservationService.PlannedAttempt attempt,Instant paid,String classification,UUID close,String payment) {
        UUID observed=observation(f,attempt,"PAID",attempt.amount().toPlainString(),0,1),id=UUID.randomUUID();
        scoped(f,()->runtime.update("""
                INSERT INTO fee_gateway_captures(id,organisation_id,school_id,attempt_id,payment_provider_config_id,checkout_observation_id,
                  provider_code,provider_environment,merchant_account_reference,checkout_reference,order_reference,payment_reference,
                  amount,currency_code,paid_at,paid_at_exact,detected_at,original_business_date,closed_day_close_id,classification,evidence_sha256)
                VALUES(?,?,?,?,?,?,'CASHFREE','TEST',?,?,'synthetic_order',?,?,'INR',?,?,now(),?,?,?,repeat('b',64))
                """,id,f.org(),f.school(),attempt.id(),f.provider(),observed,"synthetic_merchant_"+f.provider(),attempt.id().toString(),payment,attempt.amount(),
                GatewayExactInstant.parameter(paid),GatewayExactInstant.canonical(paid),LocalDate.ofInstant(paid,java.time.ZoneId.of("Asia/Kolkata")),close,classification));
        return id;
    }

    private GatewayReservationService.PlannedAttempt prepare(Fixture f, GatewayReservationService.PrepareRequest request) {
        if(legacyFinanceSchema) return scoped(f,()->legacyIntentSchema
                ? LegacyGatewayIntentFixture.seed(runtime,properties,clock,f.parent(),f.learner(),f.account(),f.provider(),request)
                : LegacyGatewayIntentFixture.seedV077(runtime,properties,clock,f.parent(),f.learner(),f.account(),f.provider(),request));
        return scoped(f, () -> reservations.prepare(f.parent(), f.learner(), request));
    }
    private boolean tryPrepare(Fixture f, GatewayReservationService.PrepareRequest request) {
        try { prepare(f, request); return true; } catch (com.educore.api.common.api.ApiException exception) { return false; }
    }
    private <T> T scoped(Fixture f, java.util.function.Supplier<T> work) {
        try (var ignored = com.educore.api.tenancy.TenantRequestContext.open(f.parent().userId(), f.org())) {
            return transactions.execute(status -> work.get());
        }
    }
    private static GatewayReservationService.PrepareRequest request(Fixture f, String amount) {
        return new GatewayReservationService.PrepareRequest(UUID.randomUUID(), List.of(selection(f, amount)), "0000000000");
    }
    private static GatewayReservationService.ChargeSelection selection(Fixture f, String amount) { return new GatewayReservationService.ChargeSelection(f.charge(), new BigDecimal(amount)); }
    private static FinanceController.PaymentRequest payment(Fixture f, String amount) {
        return new FinanceController.PaymentRequest(f.school(), f.account(), "CASH", null, new BigDecimal(amount), schoolToday(), "Synthetic manual collection",
                List.of(new FinanceController.PaymentAllocation(f.charge(), new BigDecimal(amount))));
    }
    private UUID observation(Fixture f, GatewayReservationService.PlannedAttempt attempt, String state, String paid, int pending, int successful) {
        Instant observedAt = owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
        return observationAt(f,attempt,state,paid,pending,successful,observedAt);
    }
    private UUID observationAt(Fixture f,GatewayReservationService.PlannedAttempt attempt,String state,String paid,int pending,int successful,Instant observedAt) {
        UUID id = UUID.randomUUID();
        scoped(f, () -> runtime.update("""
                INSERT INTO fee_gateway_checkout_observations(id,organisation_id,school_id,attempt_id,payment_provider_config_id,
                  provider_checkout_reference,attempt_reference,amount,currency_code,amount_paid,checkout_status,provider_expires_at,
                  order_references,pending_payment_count,successful_payment_count,discovery_complete,evidence_sha256,observed_at,observed_at_exact)
                VALUES(?,?,?,?,?,?,?,?,'INR',?,?,?,ARRAY['synthetic_order'],?,?,true,repeat('a',64),?,?)
                """, id,f.org(),f.school(),attempt.id(),f.provider(),attempt.id(),attempt.id(),attempt.amount(),new BigDecimal(paid),state,
                java.sql.Timestamp.from(attempt.expiresAt()),pending,successful,GatewayExactInstant.parameter(observedAt),GatewayExactInstant.canonical(observedAt)));
        return id;
    }
    private UUID capture(Fixture f, GatewayReservationService.PlannedAttempt attempt, UUID observed, String classification, UUID close, String payment) {
        UUID id = UUID.randomUUID();
        // Synthetic explicit event time comes from this fixture's immutable observation,
        // not a later SQL now() which would violate paid<=observed and mask other guards.
        Instant paid = GatewayExactInstant.parseCanonical(owner.queryForObject(
                "SELECT observed_at_exact FROM fee_gateway_checkout_observations WHERE id=?",String.class,observed));
        scoped(f, () -> runtime.update("""
                INSERT INTO fee_gateway_captures(id,organisation_id,school_id,attempt_id,payment_provider_config_id,checkout_observation_id,
                  provider_code,provider_environment,merchant_account_reference,checkout_reference,order_reference,payment_reference,
                  amount,currency_code,paid_at,paid_at_exact,detected_at,original_business_date,closed_day_close_id,classification,evidence_sha256)
                VALUES(?,?,?,?,?,?,'CASHFREE','TEST',?,?, 'synthetic_order',?,?,'INR',?,?,now(),?,?,?,repeat('b',64))
                """, id,f.org(),f.school(),attempt.id(),f.provider(),observed,"synthetic_merchant_"+f.provider(),attempt.id().toString(),payment,attempt.amount(),
                GatewayExactInstant.parameter(paid),GatewayExactInstant.canonical(paid),LocalDate.ofInstant(paid,java.time.ZoneId.of("Asia/Kolkata")),close,classification));
        return id;
    }

    private Fixture fixture(boolean partial) {return fixture(partial,false);}
    private Fixture fixture(boolean partial,boolean split) {
        UUID org=UUID.randomUUID(),school=UUID.randomUUID(),campus=UUID.randomUUID(),session=UUID.randomUUID(),learner=UUID.randomUUID(),application=UUID.randomUUID(),enrolment=UUID.randomUUID();
        UUID adminUser=UUID.randomUUID(),adminMember=UUID.randomUUID(),reviewUser=UUID.randomUUID(),reviewMember=UUID.randomUUID(),parentUser=UUID.randomUUID(),parentMember=UUID.randomUUID(),guardian=UUID.randomUUID();
        UUID head=UUID.randomUUID(),plan=UUID.randomUUID(),item=UUID.randomUUID(),account=UUID.randomUUID(),assignment=UUID.randomUUID(),charge=UUID.randomUUID(),provider=UUID.randomUUID();
        UUID secondItem=UUID.randomUUID(),secondCharge=UUID.randomUUID();
        owner.update("INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'Synthetic school','Synthetic school','ACTIVE',now(),now())",org,"synthetic-"+org);
        owner.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'synthetic','Synthetic school','Synthetic school','ACTIVE',now(),now())",school,org);
        owner.update("INSERT INTO campuses(id,organisation_id,school_id,code,display_name,status,created_at,updated_at) VALUES(?,?,?,'TEST','Synthetic campus','ACTIVE',now(),now())",campus,org,school);
        for (UUID user : List.of(adminUser,reviewUser,parentUser)) owner.update("INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status) VALUES(?,?,?,'Synthetic payment actor','not-a-login-hash','ACTIVE')",user,"synthetic-"+user+"@example.test","synthetic-"+user+"@example.test");
        List<UUID> users=List.of(adminUser,reviewUser,parentUser),members=List.of(adminMember,reviewMember,parentMember);
        for(int i=0;i<3;i++) {
            owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())",members.get(i),users.get(i),org,school);
            owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?::uuid)",members.get(i),i==2?"00000000-0000-4000-8000-000000000006":"00000000-0000-4000-8000-000000000001");
        }
        owner.update("INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,'Synthetic current session',DATE '2026-04-01',DATE '2027-03-31','ACTIVE',now(),now())",session,org,school);
        owner.update("""
                INSERT INTO admission_applications(id,organisation_id,school_id,idempotency_key,request_fingerprint,intake_session_label,target_grade_code,applicant_legal_name,birth_date,created_by_membership_id,created_at,updated_at)
                VALUES(?,?,?, ?,repeat('c',64),'Synthetic session','VI','Synthetic applicant',DATE '2015-01-01',?,now(),now())
                """,application,org,school,"synthetic-"+application,adminMember);
        owner.update("INSERT INTO learners(id,organisation_id,school_id,origin_application_id,legal_name,birth_date,gender_code,status,created_at,updated_at) VALUES(?,?,?,?,'Synthetic learner',DATE '2015-01-01','NOT_STATED','ACTIVE',now(),now())",learner,org,school,application);
        owner.update("INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,created_at,updated_at,user_account_id) VALUES(?,?,?,'Synthetic guardian',?,'ACTIVE',now(),now(),?)",guardian,org,school,"synthetic-"+parentUser+"@example.test",parentUser);
        owner.update("INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,relationship_code,portal_access_status,created_at,updated_at) VALUES(?,?,?,?,'PARENT_GUARDIAN','ACTIVE',now(),now())",learner,guardian,org,school);
        owner.update("""
                INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,origin_application_id,grade_code,status,starts_on,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'VI','ACTIVE',DATE '2026-04-01',now(),now())
                """,enrolment,org,school,campus,session,learner,application);
        owner.update("INSERT INTO fee_heads(id,organisation_id,school_id,code,display_name,category,status,created_at,updated_at) VALUES(?,?,?,'TEST','Synthetic tuition','TUITION','ACTIVE',now(),now())",head,org,school);
        owner.update("INSERT INTO fee_plans(id,organisation_id,school_id,academic_session_id,plan_code,display_name,currency_code,receipt_prefix,status,created_by_membership_id,created_at,updated_at) VALUES(?,?,?,?,'TEST','Synthetic fees','INR','SYN','DRAFT',?,now(),now())",plan,org,school,session,adminMember);
        owner.update("INSERT INTO fee_plan_items(id,organisation_id,school_id,fee_plan_id,fee_head_id,sequence_number,label,due_on,amount,allow_partial,created_at,updated_at) VALUES(?,?,?,?,?,1,'Synthetic installment',DATE '2026-10-01',1000,?,now(),now())",item,org,school,plan,head,partial);
        if(split) owner.update("INSERT INTO fee_plan_items(id,organisation_id,school_id,fee_plan_id,fee_head_id,sequence_number,label,due_on,amount,allow_partial,created_at,updated_at) VALUES(?,?,?,?,?,2,'Synthetic separate installment',DATE '2026-10-02',1000,?,now(),now())",secondItem,org,school,plan,head,partial);
        owner.update("UPDATE fee_plans SET status='ACTIVE',activated_by_membership_id=?,activated_at=now(),row_version=row_version+1 WHERE id=?",adminMember,plan);
        owner.update("INSERT INTO learner_fee_accounts(id,organisation_id,school_id,academic_session_id,learner_id,enrolment_id,currency_code,opened_by_membership_id,opened_at) VALUES(?,?,?,?,?,?,'INR',?,now())",account,org,school,session,learner,enrolment,adminMember);
        owner.update("INSERT INTO fee_plan_assignments(id,organisation_id,school_id,learner_fee_account_id,fee_plan_id,plan_snapshot,assigned_by_membership_id,assigned_at,reason) VALUES(?,?,?,?,?,'{}',?,now(),'Synthetic fee setup')",assignment,org,school,account,plan,adminMember);
        owner.update("INSERT INTO learner_fee_charges(id,organisation_id,school_id,learner_fee_account_id,fee_plan_assignment_id,fee_plan_item_id,fee_head_code_snapshot,fee_head_name_snapshot,label_snapshot,due_on,amount,allow_partial,created_at) VALUES(?,?,?,?,?,?,'TEST','Synthetic tuition','Synthetic installment',DATE '2026-10-01',1000,?,now())",charge,org,school,account,assignment,item,partial);
        owner.update("INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,learner_fee_charge_id,entry_type,direction,amount,effective_on,description,source_type,source_id,created_by_membership_id,created_at) VALUES(?,?,?,?,?,'CHARGE','DEBIT',1000,DATE '2026-10-01','Synthetic charge','FEE_CHARGE',?,?,now())",UUID.randomUUID(),org,school,account,charge,charge,adminMember);
        if(split) {
            owner.update("INSERT INTO learner_fee_charges(id,organisation_id,school_id,learner_fee_account_id,fee_plan_assignment_id,fee_plan_item_id,fee_head_code_snapshot,fee_head_name_snapshot,label_snapshot,due_on,amount,allow_partial,created_at) VALUES(?,?,?,?,?,?,'TEST','Synthetic tuition','Synthetic separate installment',DATE '2026-10-02',1000,?,now())",secondCharge,org,school,account,assignment,secondItem,partial);
            owner.update("INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,learner_fee_charge_id,entry_type,direction,amount,effective_on,description,source_type,source_id,created_by_membership_id,created_at) VALUES(?,?,?,?,?,'CHARGE','DEBIT',1000,DATE '2026-10-02','Synthetic separate charge','FEE_CHARGE',?,?,now())",UUID.randomUUID(),org,school,account,secondCharge,secondCharge,adminMember);
        }
        owner.update("INSERT INTO fee_receipt_sequences(organisation_id,school_id,academic_session_id,receipt_prefix,next_value,updated_at) VALUES(?,?,?,'SYN',1,now())",org,school,session);
        owner.update("INSERT INTO payment_provider_configs(id,organisation_id,school_id,provider_code,environment,credentials_secret_reference,merchant_account_reference,status,configured_by_membership_id,created_at,updated_at) VALUES(?,?,?,'CASHFREE','TEST','synthetic:not-enabled',?,'ACTIVE',?,now(),now())",provider,org,school,"synthetic_merchant_"+provider,adminMember);
        return new Fixture(org,school,learner,account,charge,provider,actor(parentUser,parentMember,org),actor(adminUser,adminMember,org),actor(reviewUser,reviewMember,org));
    }
    private UUID place(Fixture f, LocalDate startsOn, boolean differentSession) {
        UUID enrolment = owner.queryForObject("SELECT enrolment_id FROM learner_fee_accounts WHERE id=?", UUID.class, f.account());
        UUID campus = owner.queryForObject("SELECT campus_id FROM student_enrolments WHERE id=?", UUID.class, enrolment);
        UUID session = owner.queryForObject("SELECT academic_session_id FROM learner_fee_accounts WHERE id=?", UUID.class, f.account());
        if (differentSession) {
            session = UUID.randomUUID();
            owner.update("INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,'Synthetic different session',DATE '2027-04-01',DATE '2028-03-31','PLANNED',now(),now())",
                    session, f.org(), f.school());
        }
        UUID section = UUID.randomUUID(), placement = UUID.randomUUID();
        owner.update("INSERT INTO class_sections(id,organisation_id,school_id,campus_id,academic_session_id,grade_code,code,display_name,status,created_at,updated_at) VALUES(?,?,?,?,?,'VI','B','Synthetic section B','ACTIVE',now(),now())",
                section, f.org(), f.school(), campus, session);
        owner.update("INSERT INTO student_section_placements(id,organisation_id,school_id,enrolment_id,learner_id,class_section_id,roll_number,starts_on,status,placed_by_membership_id,placement_reason,created_at,updated_at) VALUES(?,?,?,?,?,?,'017A',?,'ACTIVE',?,'Synthetic search placement',now(),now())",
                placement, f.org(), f.school(), enrolment, f.learner(), section, startsOn, f.admin().membershipIds().iterator().next());
        return placement;
    }

    private static AuthenticatedUser actor(UUID user,UUID member,UUID org) {
        return new AuthenticatedUser(user,UUID.randomUUID(),"synthetic@example.test",null,"Synthetic actor",true,org,Set.of(member),Set.of("portal.guardian","fees.read","fees.collect","fees.approve"),List.of());
    }
    private static DriverManagerDataSource dataSource(String url,String login,String password) {
        var result=new DriverManagerDataSource(url,login,password);
        var settings=new java.util.Properties(); settings.setProperty("connectTimeout","10"); settings.setProperty("socketTimeout","120");
        settings.setProperty("options","-c statement_timeout=90000"); result.setConnectionProperties(settings); return result;
    }
    private void createRole(String role,String password,String bypass) throws Exception {
        try(var connection=controlDataSource.getConnection();var format=connection.prepareStatement("SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER "+bypass+" NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',?,?)")) {
            format.setString(1,role);format.setString(2,password);
            try(var row=format.executeQuery();var command=connection.createStatement()){row.next();command.execute(row.getString(1));}
        }
    }
    @AfterAll void removeOnlyGeneratedDatabaseAndRoles() {
        if(control!=null) {
            if(databaseCreated) control.execute("DROP DATABASE "+identifier(database)+" WITH (FORCE)");
            if(runtimeCreated) control.execute("DROP ROLE "+identifier(runtimeLogin));
            if(ownerCreated) control.execute("DROP ROLE "+identifier(ownerLogin));
        }
        if(container!=null) container.stop();
    }
    private static String identifier(String value) {
        if(!value.matches("(?:educore_online_|synthetic_online_(?:owner|runtime)_)[a-f0-9]{32}")) throw new IllegalArgumentException("Generated fixture identity required");
        return '"'+value+'"';
    }
    private static void assertFamilyPaymentMissing(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class, failure -> {
            assertThat(failure.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
            assertThat(failure.code()).isEqualTo("FAMILY_PAYMENT_NOT_FOUND");
        });
    }
    private static String secret(){byte[] value=new byte[32];new SecureRandom().nextBytes(value);return Base64.getEncoder().encodeToString(value);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
    private record Fixture(UUID org,UUID school,UUID learner,UUID account,UUID charge,UUID provider,AuthenticatedUser parent,AuthenticatedUser admin,AuthenticatedUser reviewer){}
}
