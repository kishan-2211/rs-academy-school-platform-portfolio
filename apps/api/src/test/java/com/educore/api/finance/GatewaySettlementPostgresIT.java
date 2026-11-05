package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.ProductionDatabaseSecurity;
import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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

/** Actual migrated, non-owner PostgreSQL. No merchant calls, real people, credentials or school data. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewaySettlementPostgresIT {
    private final String suffix=UUID.randomUUID().toString().replace("-","");
    private final String database="educore_settlement_"+suffix,ownerLogin="synthetic_settlement_owner_"+suffix,runtimeLogin="synthetic_settlement_runtime_"+suffix;
    private final String ownerPassword=secret(),runtimePassword=secret();
    private JdbcTemplate control,owner,runtime;
    private DriverManagerDataSource controlSource;
    private TransactionTemplate transactions;
    private DataSourceTransactionManager transactionManager;
    private GatewaySettlementReadStore store;
    private SensitiveValueCipher cipher;
    private PostgreSQLContainer<?> container;
    private boolean databaseCreated,ownerCreated,runtimeCreated;
    private Fixture legacy;
    private List<String> legacySnapshot;
    private List<String> connectedMigrationHistory;
    private int migrated,connectedMigrated,latestMigrated;
    private static final Instant FROM=Instant.parse("2026-01-01T00:00:00.123456789Z"),UNTIL=Instant.parse("2026-01-15T00:00:00.987654321Z");

    @BeforeAll void migrateWithActualRestrictedRuntime() throws Exception {
        String url=System.getenv("TEST_DATABASE_URL"),user,password;
        if(url==null||url.isBlank()){
            container=new PostgreSQLContainer<>("postgres:18.4-alpine");container.start();url=container.getJdbcUrl();user=container.getUsername();password=container.getPassword();
        }else{
            if(!url.matches("jdbc:postgresql://[^/]+/educore_integration(?:\\?.*)?"))throw new IllegalStateException("Isolated educore_integration control database required");
            user=required("TEST_DATABASE_USER");password=required("TEST_DATABASE_PASSWORD");
        }
        controlSource=dataSource(url,user,password);control=new JdbcTemplate(controlSource);
        createRole(ownerLogin,ownerPassword,"BYPASSRLS");ownerCreated=true;createRole(runtimeLogin,runtimePassword,"NOBYPASSRLS");runtimeCreated=true;
        control.execute("CREATE DATABASE "+identifier(database)+" OWNER "+identifier(ownerLogin));databaseCreated=true;
        String testUrl=url.replaceFirst("/[^/?]+(?=\\?|$)","/"+database);
        Flyway.configure().dataSource(testUrl,ownerLogin,ownerPassword).locations("classpath:db/migration").target("080").cleanDisabled(true).load().migrate();
        owner=new JdbcTemplate(dataSource(testUrl,ownerLogin,ownerPassword));legacy=fixture(UUID.randomUUID());legacySnapshot=snapshot(legacy);
        var legacyMigrationHistory=migrationHistory();
        migrated=Flyway.configure().dataSource(testUrl,ownerLogin,ownerPassword).locations("classpath:db/migration").target("081").validateMigrationNaming(true).cleanDisabled(true).load().migrate().migrationsExecuted;
        connectedMigrated=Flyway.configure().dataSource(testUrl,ownerLogin,ownerPassword).locations("classpath:db/migration").target("082").validateMigrationNaming(true).cleanDisabled(true).load().migrate().migrationsExecuted;
        connectedMigrationHistory=migrationHistory();
        assertThat(connectedMigrationHistory).startsWith(legacyMigrationHistory.toArray(String[]::new))
                .hasSize(legacyMigrationHistory.size()+migrated+connectedMigrated);
        assertThat(owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(82);
        assertThat(snapshot(legacy)).isEqualTo(legacySnapshot);
        // The V081/V082 assertions above describe those exact historical stages.
        // All behavioral tests still run on every migration resolved at this head.
        var latest=Flyway.configure().dataSource(testUrl,ownerLogin,ownerPassword).locations("classpath:db/migration").validateMigrationNaming(true).cleanDisabled(true).load();
        latestMigrated=latest.migrate().migrationsExecuted;
        assertThat(latest.info().pending()).isEmpty();
        try(var connection=DriverManager.getConnection(testUrl,ownerLogin,ownerPassword)){
            connection.setAutoCommit(false);
            try(var scope=connection.prepareStatement("SELECT set_config('educore.runtime_role',?,true)")){scope.setString(1,runtimeLogin);scope.execute();}
            try(var command=connection.createStatement()){command.execute(new ClassPathResource("db/runtime-grants.sql").getContentAsString(StandardCharsets.UTF_8));}connection.commit();
        }
        var source=(javax.sql.DataSource)new com.educore.api.tenancy.TenantAwareDataSourceBeanPostProcessor().postProcessAfterInitialization(dataSource(testUrl,runtimeLogin,runtimePassword),"dataSource");
        runtime=new JdbcTemplate(source);transactionManager=new DataSourceTransactionManager(source);transactions=new TransactionTemplate(transactionManager);transactions.setTimeout(30);
        var properties=new ApplicationProperties("https://synthetic-school.example.test",List.of(),Optional.empty(),Optional.empty(),
                new ApplicationProperties.Security("synthetic_session",true,Duration.ofHours(1),Duration.ofHours(2),Duration.ofMinutes(10),null,
                        Base64.getEncoder().encodeToString(new byte[32]),Duration.ofMinutes(5),Duration.ofMinutes(5),Duration.ofDays(1),"Synthetic school"),
                new ApplicationProperties.Bootstrap(false,null,null));
        cipher=new SensitiveValueCipher(properties,new SecureRandom());store=new GatewaySettlementReadStore(new NamedParameterJdbcTemplate(runtime),cipher);
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void migrationPreservesExistingProviderAndRequiresDenyByDefaultNonOwnerScope(){
        assertThat(migrated).isEqualTo(1);assertThat(connectedMigrated).isEqualTo(1);assertThat(snapshot(legacy)).isEqualTo(legacySnapshot);
        assertThat(migrationHistory()).startsWith(connectedMigrationHistory.toArray(String[]::new))
                .hasSize(connectedMigrationHistory.size()+latestMigrated);
        assertThat(owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success",Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('fee_gateway_settlement_queries','fee_gateway_settlement_reads') AND relrowsecurity AND relforcerowsecurity",Integer.class)).isEqualTo(2);
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_settlement_queries",Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fee_gateway_settlement_reads",Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('fee_gateway_settlement_queries','fee_gateway_settlement_reads') AND column_name IN ('raw_body','customer_details','client_secret','card_number','bank_account','phone')",Integer.class)).isZero();
        ProductionDatabaseSecurity.verify(runtime);
    }

    @Test void exactSummaryPreparationAndReadRetryPreservePrecisionAndDoNotMoveMoney(){
        var f=fixture();UUID queryId=UUID.randomUUID(),readId=UUID.randomUUID();var q=background(f,()->store.prepareSummaries(f.binding(),queryId,FROM,UNTIL));
        assertThat(background(f,()->store.prepareSummaries(f.binding(),queryId,FROM,UNTIL))).isEqualTo(q);
        var page=summaryPage("SUCCESS",null);var read=background(f,()->store.retainSummaries(f.binding(),q.id(),readId,page));
        assertThat(background(f,()->store.retainSummaries(f.binding(),q.id(),readId,page))).isEqualTo(read);
        assertThat(read.summary().items().getFirst().reportedAmountSettled()).isEqualTo(new BigDecimal("98.12345678"));
        assertThat(read.summary().items().getFirst().processedAt()).isEqualTo(Instant.parse("2026-01-12T01:02:03.123456789Z"));
        assertThatThrownBy(()->background(f,()->store.prepareSummaries(f.binding(),queryId,FROM.plusNanos(1),UNTIL))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->background(f,()->store.retainSummaries(f.binding(),q.id(),readId,summaryPage("PENDING_WITH_BANK",null)))).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_reads WHERE query_id=?",Integer.class,q.id())).isEqualTo(1);assertNoMoney(f);
    }

    @Test void unknownAndEmptyCompleteAreDistinctAndNeverInventContinuationOrStatement(){
        var f=fixture();var q=prepare(f);var unknown=background(f,()->store.retainUnknown(f.binding(),q.id(),UUID.randomUUID()));
        assertThat(unknown.complete()).isFalse();assertThat(unknown.summary()).isNull();
        assertThatThrownBy(()->background(f,()->store.prepareNext(f.binding(),UUID.randomUUID(),unknown.id()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->background(f,()->store.prepareEvents(f.binding(),UUID.randomUUID(),unknown.id(),"synthetic_statement"))).isInstanceOf(IllegalStateException.class);
        var empty=background(f,()->store.retainSummaries(f.binding(),q.id(),UUID.randomUUID(),new GatewaySettlementProvider.SettlementPage(null,List.of())));
        assertThat(empty.complete()).isTrue();assertThat(empty.summary().items()).isEmpty();
        assertThatThrownBy(()->background(f,()->store.prepareNext(f.binding(),UUID.randomUUID(),empty.id()))).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_reads WHERE query_id=?",Integer.class,q.id())).isEqualTo(2);assertNoMoney(f);
    }

    @Test void eventReadsRequireActualScopedSummaryAndRetainRepeatedIdsAcrossKindsAndUnexpectedCurrencies(){
        var f=fixture();var summary=retain(f,prepare(f),"SUCCESS",null);
        assertThatThrownBy(()->background(f,()->store.prepareEvents(f.binding(),UUID.randomUUID(),summary.id(),"synthetic_unobserved"))).isInstanceOf(IllegalStateException.class);
        var q=background(f,()->store.prepareEvents(f.binding(),UUID.randomUUID(),summary.id(),"synthetic_statement"));
        var page=new GatewaySettlementProvider.SettlementEventsPage(null,List.of(event("PAYMENT","INR"),event("DISPUTE","USD")));
        var saved=background(f,()->store.retainEvents(f.binding(),q.id(),UUID.randomUUID(),page));
        assertThat(saved.events().items()).isEqualTo(page.items());assertThat(saved.events().items().getFirst().eventReference()).isEqualTo(saved.events().items().getLast().eventReference());
        assertThatThrownBy(()->background(f,()->store.retainSummaries(f.binding(),q.id(),UUID.randomUUID(),summaryPage("SUCCESS",null)))).isInstanceOf(IllegalStateException.class);
        assertNoMoney(f);
    }

    @Test void continuationIsEncryptedExactRetryableAndLongCursorCyclesRemainExplicit(){
        var f=fixture();var first=retain(f,prepare(f),"SUCCESS","synthetic_cursor_A");UUID nextId=UUID.randomUUID();
        var second=background(f,()->store.prepareNext(f.binding(),nextId,first.id()));
        assertThat(second.cursor()).isEqualTo("synthetic_cursor_A");assertThat(second.pageNumber()).isEqualTo(2);
        assertThat(background(f,()->store.prepareNext(f.binding(),nextId,first.id()))).isEqualTo(second);
        var secondRead=retain(f,second,"PENDING_WITH_BANK","synthetic_cursor_B");
        var third=background(f,()->store.prepareNext(f.binding(),UUID.randomUUID(),secondRead.id()));
        var thirdRead=retain(f,third,"SUCCESS","synthetic_cursor_A");
        assertThatThrownBy(()->background(f,()->store.prepareNext(f.binding(),UUID.randomUUID(),thirdRead.id()))).isInstanceOf(DataAccessException.class);
        assertThat(background(f,()->store.read(f.binding(),thirdRead.id()))).isEqualTo(thirdRead);
        assertThat(owner.queryForList("SELECT row_to_json(q)::text FROM fee_gateway_settlement_queries q WHERE organisation_id=?",String.class,f.org()).toString()).doesNotContain("synthetic_cursor_A","synthetic_cursor_B");
        assertThat(owner.queryForList("SELECT row_to_json(r)::text FROM fee_gateway_settlement_reads r WHERE organisation_id=?",String.class,f.org()).toString()).doesNotContain("synthetic_cursor_A","synthetic_cursor_B");
        assertNoMoney(f);
    }

    @Test void preparedHistoricalWitnessSurvivesProviderDisableAndContradictoryLaterReads(){
        var f=fixture();var q=prepare(f);owner.update("UPDATE payment_provider_configs SET status='INACTIVE',updated_at=clock_timestamp(),row_version=row_version+1 WHERE id=?",f.binding().providerConfigId());
        var success=retain(f,q,"SUCCESS",null);var pending=retain(f,q,"PENDING_WITH_BANK",null);
        assertThat(success.id()).isNotEqualTo(pending.id());assertThat(background(f,()->store.read(f.binding(),success.id())).summary().items().getFirst().reportedStatus()).isEqualTo("SUCCESS");
        assertThat(background(f,()->store.prepareSummaries(f.binding(),q.id(),FROM,UNTIL))).isEqualTo(q);
        assertThatThrownBy(()->background(f,()->store.prepareSummaries(f.binding(),UUID.randomUUID(),FROM,UNTIL))).isInstanceOf(DataAccessException.class);assertNoMoney(f);
    }

    @Test void evidenceIsIndependentOfLaterProcessingRollbackAndFailedRetentionNeverOverwrites(){
        var f=fixture();var q=prepare(f);var saved=retain(f,q,"SUCCESS",null);
        assertThatThrownBy(()->background(f,()->{store.prepareNext(f.binding(),UUID.randomUUID(),saved.id());return null;})).isInstanceOf(IllegalStateException.class);
        assertThat(background(f,()->store.read(f.binding(),saved.id()))).isEqualTo(saved);
        UUID rolledBack=UUID.randomUUID();assertThatThrownBy(()->background(f,()->{store.prepareSummaries(f.binding(),rolledBack,FROM,UNTIL);throw new IllegalStateException("Synthetic later processing failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_queries WHERE id=?",Integer.class,rolledBack)).isZero();
        assertThat(background(f,()->store.read(f.binding(),saved.id()))).isEqualTo(saved);assertNoMoney(f);
    }

    @Test void missingHumanForeignTenantAndSameOrganisationForeignSchoolCannotUseRetainedSources(){
        var f=fixture();var q=prepare(f);var read=retain(f,q,"SUCCESS",null);var sibling=fixture(f.org());var foreign=fixture();
        assertThatThrownBy(()->store.load(f.binding(),q.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->as(f,f.user(),()->store.load(f.binding(),q.id()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->background(foreign,()->store.load(f.binding(),q.id()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->background(sibling,()->store.read(sibling.binding(),read.id()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->background(sibling,()->store.prepareEvents(sibling.binding(),UUID.randomUUID(),read.id(),"synthetic_statement"))).isInstanceOf(IllegalStateException.class);
        assertThat(background(foreign,()->runtime.queryForObject("SELECT count(*) FROM fee_gateway_settlement_reads WHERE id=?",Integer.class,read.id()))).isZero();
        assertNoMoney(f);assertNoMoney(sibling);assertNoMoney(foreign);
    }

    @Test void databaseRejectsNonMinimalFutureForeignAndManufacturedUnknownFacts(){
        var f=fixture();var q=prepare(f);String valid=GatewaySettlementFacts.summaries(summaryPage("SUCCESS",null).items());
        for(String invalid:List.of(valid.replace("\"reportedStatus\":\"SUCCESS\"","\"reportedStatus\":\"SUCCESS\",\"customer_details\":{}"),
                valid.replace("\"reportedStatus\":\"SUCCESS\"","\"reportedStatus\":\"synthetic_private_extra\",\"reportedStatus\":\"SUCCESS\""),
                valid.replace("2026-01-12T01:02:03.123456789Z","2099-01-12T01:02:03Z"),valid.replace("98.12345678","98.123456789"))){
            assertThatThrownBy(()->raw(f,q,"COMPLETE",invalid)).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(()->raw(f,q,"UNKNOWN","[]")).isInstanceOf(DataAccessException.class);
        var summary=retain(f,q,"SUCCESS",null);var events=background(f,()->store.prepareEvents(f.binding(),UUID.randomUUID(),summary.id(),"synthetic_statement"));
        String wrong=GatewaySettlementFacts.events(List.of(event("PAYMENT","INR"))).replace("synthetic_statement","synthetic_foreign_statement");
        assertThatThrownBy(()->raw(f,events,"COMPLETE",wrong)).isInstanceOf(DataAccessException.class);assertNoMoney(f);
    }

    @Test void queryAndReadHistoryCannotBeUpdatedDeletedOrReboundEvenByMigrationOwner(){
        var f=fixture();var q=prepare(f);var read=retain(f,q,"SUCCESS",null);
        for(String sql:List.of("UPDATE fee_gateway_settlement_reads SET outcome='UNKNOWN' WHERE id=?","DELETE FROM fee_gateway_settlement_reads WHERE id=?"))
            assertThatThrownBy(()->owner.update(sql,read.id())).isInstanceOf(DataAccessException.class);
        for(String sql:List.of("UPDATE fee_gateway_settlement_queries SET page_number=2 WHERE id=?","DELETE FROM fee_gateway_settlement_queries WHERE id=?"))
            assertThatThrownBy(()->owner.update(sql,q.id())).isInstanceOf(DataAccessException.class);
        assertThat(background(f,()->store.read(f.binding(),read.id()))).isEqualTo(read);assertNoMoney(f);
    }

    @Test void privateCursorCannotBeReusedUnderAnotherReadIdentity(){
        var f=fixture();var q=prepare(f);UUID readId=UUID.randomUUID();String cursor="synthetic_private_cursor";
        var wrong=cipher.encrypt(cursor.getBytes(StandardCharsets.UTF_8),"synthetic_wrong_associated_identity");
        String facts=GatewaySettlementFacts.summaries(summaryPage("SUCCESS",null).items());String fingerprint=GatewaySettlementReadStore.fingerprint(q,readId,facts,cursor);
        background(f,()->{runtime.update("INSERT INTO fee_gateway_settlement_reads(id,organisation_id,school_id,query_id,outcome,facts_canonical,evidence_sha256,next_cursor_sha256,next_cursor_ciphertext,next_cursor_nonce) VALUES(?,?,?,?,'COMPLETE',?,?,?,?,?)",
                readId,f.org(),f.school(),q.id(),facts,fingerprint,GatewaySettlementReadStore.hash(cursor),wrong.ciphertext(),wrong.nonce());return null;});
        assertThatThrownBy(()->background(f,()->store.read(f.binding(),readId))).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(cursor);assertNoMoney(f);
    }

    @Test void connectedHumanRequestCommitsOneAuditAndWorkAndExactReplayEvenAfterProviderDisable(){
        var f=fixture();var flow=flow(f,true);var command=command(f);var result=submit(f,flow,command);
        assertThat(human(f,()->flow.service().queue(actor(f),f.school(),"ALL","ALL",null,0,25)).items()).singleElement().satisfies(r->{
            assertThat(r.id()).isEqualTo(result.id());assertThat(r.status()).isEqualTo("REQUESTED");assertThat(r.pagesPending()).isEqualTo(1);});
        owner.update("UPDATE payment_provider_configs SET status='INACTIVE',updated_at=clock_timestamp(),row_version=row_version+1 WHERE id=?",f.binding().providerConfigId());
        assertThat(submit(f,flow,command)).isEqualTo(result);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_work WHERE request_id=?",Integer.class,result.id())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action='finance.gateway.settlement.requested'",Integer.class,f.school())).isEqualTo(1);
        assertNoMoney(f);
    }

    @Test void connectedHumanRejectsChangedReplayOverlapFutureTimezoneAndDisabledRouteWithoutLeavingWork(){
        var f=fixture();var flow=flow(f,true);var command=command(f);submit(f,flow,command);
        assertThatThrownBy(()->submit(f,flow,new GatewaySettlementModels.Submit(f.school(),command.clientRequestId(),"Asia/Kolkata","SUMMARY",FROM,UNTIL.minusSeconds(1),null,null))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->submit(f,flow,command(f))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var other=fixture();var enabled=flow(other,true);
        assertThatThrownBy(()->submit(other,enabled,new GatewaySettlementModels.Submit(other.school(),UUID.randomUUID(),"UTC","SUMMARY",FROM,UNTIL,null,null))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->submit(other,enabled,new GatewaySettlementModels.Submit(other.school(),UUID.randomUUID(),"Asia/Kolkata","SUMMARY",Instant.now().plusSeconds(1),Instant.now().plusSeconds(60),null,null))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var disabled=flow(other,false);assertThatThrownBy(()->submit(other,disabled,command(other))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(human(other,()->disabled.service().queue(actor(other),other.school(),"ALL","ALL","",0,25)).canSync()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_requests WHERE school_id=?",Integer.class,other.school())).isZero();assertNoMoney(f);assertNoMoney(other);
    }

    @Test void connectedRequestAndAuditRollbackTogetherAndBackgroundCannotInventHumanRequest(){
        var f=fixture();var flow=flow(f,true);
        assertThatThrownBy(()->human(f,()->{flow.service().submit(actor(f),command(f));throw new IllegalStateException("Synthetic later rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_requests WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_work WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThatThrownBy(()->background(f,()->flow.service().submit(actor(f),command(f)))).isInstanceOf(com.educore.api.common.api.ApiException.class);assertNoMoney(f);
    }

    @Test void connectedWorkerCompletesRealSelectedSummaryAndProjectionWithoutAnyMoneyOrCredentialLeak(){
        var f=fixture();var flow=flow(f,true);var result=submit(f,flow,command(f));
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var view=human(f,()->flow.service().report(actor(f),f.school(),result.id(),null,0,25));
        assertThat(view.request().status()).isEqualTo("COMPLETE");assertThat(view.request().lastCheckedAt()).isNotNull();
        assertThat(view.items()).singleElement().satisfies(row->{assertThat(row.rowIndex()).isZero();assertThat(row.event()).isNull();assertThat(row.summary().reportedAmountSettled()).isEqualTo("98.12345678");});
        assertThat(view.toString()).doesNotContain("synthetic:not-enabled","synthetic_merchant_","synthetic_cursor");
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.IDLE);assertNoMoney(f);
    }

    @Test void connectedUnknownReadIsRetryingNotEmptyCompleteAndLastAttemptIsVisible(){
        var f=fixture();var flow=flow(f,true);submit(f,flow,command(f));when(((GatewaySettlementProvider)flow.adapter()).fetchSettlements(any())).thenThrow(new IllegalStateException("Synthetic unavailable"));
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        var queue=human(f,()->flow.service().queue(actor(f),f.school(),"RETRYING","SUMMARY",null,0,25));
        assertThat(queue.items()).singleElement().satisfies(r->{assertThat(r.pagesCompleted()).isZero();assertThat(r.lastCheckedAt()).isNotNull();assertThat(r.nextCheckAt()).isNotNull();});
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_reads WHERE school_id=? AND outcome='UNKNOWN'",Integer.class,f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_selections WHERE school_id=?",Integer.class,f.school())).isZero();assertNoMoney(f);
    }

    @Test void connectedCursorChainIsAtomicAndOnlyTerminalChainBecomesComplete(){
        var f=fixture();var flow=flow(f,true);var result=submit(f,flow,command(f));
        when(((GatewaySettlementProvider)flow.adapter()).fetchSettlements(any())).thenAnswer(invocation->{
            outsideProvider();var q=invocation.getArgument(0,GatewaySettlementProvider.SettlementQuery.class);
            return summaryPage("SUCCESS",q.cursor()==null?"synthetic_cursor_A":null);});
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var pending=human(f,()->flow.service().queue(actor(f),f.school(),"ALL","ALL",null,0,25)).items().getFirst();
        assertThat(pending.status()).isEqualTo("SYNCING");assertThat(pending.pagesCompleted()).isEqualTo(1);assertThat(pending.pagesPending()).isEqualTo(1);
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var finalReport=human(f,()->flow.service().report(actor(f),f.school(),result.id(),null,0,1));
        assertThat(finalReport.request().status()).isEqualTo("COMPLETE");assertThat(finalReport.total()).isEqualTo(2);assertThat(finalReport.hasMore()).isTrue();
        assertThat(human(f,()->flow.service().report(actor(f),f.school(),result.id(),"synthetic_statement",1,1)).items()).hasSize(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_work WHERE request_id=?",Integer.class,result.id())).isEqualTo(2);assertNoMoney(f);
    }

    @Test void connectedOnDemandEventsRequireSelectedActualSummaryAndDoNotAutomaticallyFanOut(){
        var f=fixture();var flow=flow(f,true);var result=submit(f,flow,command(f));assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var source=human(f,()->flow.service().report(actor(f),f.school(),result.id(),null,0,25)).items().getFirst().sourceReadId();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_requests WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
        assertThatThrownBy(()->submit(f,flow,new GatewaySettlementModels.Submit(f.school(),UUID.randomUUID(),"Asia/Kolkata","EVENTS",null,null,source,"synthetic_unobserved"))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        var events=submit(f,flow,new GatewaySettlementModels.Submit(f.school(),UUID.randomUUID(),"Asia/Kolkata","EVENTS",null,null,source,"synthetic_statement"));
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var report=human(f,()->flow.service().report(actor(f),f.school(),events.id(),null,0,25));
        assertThat(report.items()).singleElement().satisfies(row->{assertThat(row.summary()).isNull();assertThat(row.event().currencyCode()).isEqualTo("USD");assertThat(row.event().reportedAmount()).isEqualTo("100.12345678");});assertNoMoney(f);
    }

    @Test void connectedProjectionAndRequestsDenyForeignSchoolRevokedMembershipAndWrongActor(){
        var f=fixture();var flow=flow(f,true);var result=submit(f,flow,command(f));var sibling=fixture(f.org());var foreign=fixture();
        assertThatThrownBy(()->human(sibling,()->flow.service().report(actor(sibling),f.school(),result.id(),null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->human(foreign,()->flow.service().report(actor(f),f.school(),result.id(),null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThatThrownBy(()->as(f,sibling.user(),()->flow.service().submit(actor(f),command(f)))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=clock_timestamp() WHERE id=?",membership(f));
        assertThatThrownBy(()->human(f,()->flow.service().queue(actor(f),f.school(),"ALL","ALL",null,0,25))).isInstanceOf(com.educore.api.common.api.ApiException.class);assertNoMoney(f);
    }

    @Test void connectedWorkerRechecksRevokedRequesterBeforeProviderIoAndClosesForReview(){
        var f=fixture();var flow=flow(f,true);submit(f,flow,command(f));owner.update("UPDATE memberships SET status='SUSPENDED',updated_at=clock_timestamp() WHERE id=?",membership(f));
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        verify((GatewaySettlementProvider)flow.adapter(),never()).fetchSettlements(any());
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_reads WHERE school_id=?",Integer.class,f.school())).isZero();assertNoMoney(f);
    }

    @Test void connectedFinalizationRollbackPreservesObservedFactsButNotSelectionOrContinuation(){
        var f=fixture();var flow=flow(f,true);submit(f,flow,command(f));var claim=background(f,()->flow.queue().claimNextSettlement(f.binding()));
        var admission=background(f,()->flow.dispatch().prepare(flow.key(),claim));var read=retain(f,admission.query(),"SUCCESS","synthetic_cursor_A");
        assertThatThrownBy(()->background(f,()->{flow.dispatch().finish(flow.key(),claim,read);throw new IllegalStateException("Synthetic finalization rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(background(f,()->store.read(f.binding(),read.id()))).isEqualTo(read);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_selections WHERE school_id=?",Integer.class,f.school())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_work WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
        assertThat(background(f,()->flow.dispatch().finish(flow.key(),claim,read))).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThatThrownBy(()->background(f,()->flow.dispatch().finish(flow.key(),claim,read))).isInstanceOf(IllegalStateException.class);assertNoMoney(f);
    }

    @Test void connectedDatabaseRejectsForgedClaimOrOrphanCompletionAndProtectsHistory(){
        var f=fixture();var flow=flow(f,true);var request=submit(f,flow,command(f));var claim=background(f,()->flow.queue().claimNextSettlement(f.binding()));
        var admission=background(f,()->flow.dispatch().prepare(flow.key(),claim));var read=retain(f,admission.query(),"SUCCESS",null);
        var forged=new GatewayWorkQueue.Claim(claim.kind(),claim.id(),claim.binding(),UUID.randomUUID(),claim.startedAt(),claim.leaseUntil(),claim.attemptNumber(),false);
        assertThatThrownBy(()->background(f,()->flow.dispatch().finish(flow.key(),forged,read))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->background(f,()->flow.queue().complete(claim,GatewayWorkQueue.Outcome.COMPLETE,"GATEWAY_SETTLEMENT_FORGED",Duration.ofSeconds(1))))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class)
                .hasStackTraceContaining("completed settlement work requires its selected complete report witness");
        assertThat(background(f,()->flow.dispatch().finish(flow.key(),claim,read))).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThatThrownBy(()->owner.update("DELETE FROM fee_gateway_settlement_requests WHERE id=?",request.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("UPDATE fee_gateway_settlement_selections SET reason_code='GATEWAY_SETTLEMENT_CHANGED' WHERE read_id=?",read.id())).isInstanceOf(DataAccessException.class);assertNoMoney(f);
    }

    @Test void connectedExpiredLeaseCannotSelectAndRecoveryNeverLosesIndependentRead() throws Exception{
        var f=fixture();var flow=flow(f,true);submit(f,flow,command(f));var shortQueue=new GatewayWorkQueue(new NamedParameterJdbcTemplate(runtime),Clock.systemUTC(),Duration.ofSeconds(1));
        var claim=background(f,()->shortQueue.claimNextSettlement(f.binding()));var admission=background(f,()->flow.dispatch().prepare(flow.key(),claim));var read=retain(f,admission.query(),"SUCCESS",null);
        Thread.sleep(1100);
        assertThatThrownBy(()->background(f,()->flow.dispatch().finish(flow.key(),claim,read))).isInstanceOf(IllegalStateException.class);
        assertThat(background(f,()->shortQueue.recoverExpiredSettlementLease(f.binding()))).isEqualTo(claim.id());
        assertThat(background(f,()->store.read(f.binding(),read.id()))).isEqualTo(read);assertNoMoney(f);
    }

    @Test void connectedConcurrentExactHumanRetriesCommitOneRequestOutboxAndAudit() throws Exception{
        var f=fixture();var flow=flow(f,true);var command=command(f);var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            java.util.concurrent.Callable<GatewaySettlementModels.Result> call=()->{start.await();return submit(f,flow,command);};
            var first=executor.submit(call);var second=executor.submit(call);start.countDown();
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        for(String table:List.of("fee_gateway_settlement_requests","fee_gateway_settlement_work"))assertThat(owner.queryForObject("SELECT count(*) FROM "+table+" WHERE school_id=?",Integer.class,f.school())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_events WHERE school_id=? AND action='finance.gateway.settlement.requested'",Integer.class,f.school())).isEqualTo(1);assertNoMoney(f);
    }

    @Test void connectedCursorCycleRetainsEvidenceAndStopsAsReviewRatherThanInventingCompleteScan(){
        var f=fixture();var flow=flow(f,true);var result=submit(f,flow,command(f));
        when(((GatewaySettlementProvider)flow.adapter()).fetchSettlements(any())).thenAnswer(invocation->{outsideProvider();return summaryPage("SUCCESS","synthetic_cycle");});
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(flow.worker().runNext(flow.key())).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        var report=human(f,()->flow.service().report(actor(f),f.school(),result.id(),null,0,25));
        assertThat(report.request().status()).isEqualTo("REVIEW_REQUIRED");assertThat(report.request().pagesReview()).isEqualTo(1);assertThat(report.request().pagesPending()).isZero();
        assertThat(report.items()).hasSize(2);assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_work WHERE request_id=?",Integer.class,result.id())).isEqualTo(2);assertNoMoney(f);
    }

    @Test void connectedProviderDisableBetweenObservedReadAndFinishRequiresReviewAndKeepsPrivateFacts(){
        var f=fixture();var flow=flow(f,true);submit(f,flow,command(f));var claim=background(f,()->flow.queue().claimNextSettlement(f.binding()));
        var admission=background(f,()->flow.dispatch().prepare(flow.key(),claim));var read=retain(f,admission.query(),"SUCCESS",null);
        owner.update("UPDATE payment_provider_configs SET status='INACTIVE',updated_at=clock_timestamp(),row_version=row_version+1 WHERE id=?",f.binding().providerConfigId());
        assertThatThrownBy(()->background(f,()->flow.dispatch().finish(flow.key(),claim,read))).isInstanceOf(GatewaySettlementDispatch.SourceUnavailable.class);
        assertThat(background(f,()->flow.dispatch().review(flow.key(),claim))).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        assertThat(background(f,()->store.read(f.binding(),read.id()))).isEqualTo(read);
        assertThat(owner.queryForObject("SELECT count(*) FROM fee_gateway_settlement_selections WHERE school_id=?",Integer.class,f.school())).isZero();assertNoMoney(f);
    }

    private Flow flow(Fixture f,boolean enabled){
        var adapter=mock(PaymentProviderAdapter.class,withSettings().extraInterfaces(GatewaySettlementProvider.class));when(adapter.providerCode()).thenReturn("CASHFREE");
        when(((GatewaySettlementProvider)adapter).fetchSettlements(any())).thenAnswer(invocation->{outsideProvider();return summaryPage("SUCCESS",null);});
        when(((GatewaySettlementProvider)adapter).fetchSettlementEvents(any())).thenAnswer(invocation->{outsideProvider();return new GatewaySettlementProvider.SettlementEventsPage(null,List.of(event("PAYMENT","USD")));});
        String key=f.binding().providerConfigId().toString().replace("-","")+"a".repeat(32);
        var route=new GatewayProviderRegistry.Route(key,f.org(),f.school(),f.binding().providerConfigId(),"CASHFREE",CashfreePaymentProviderAdapter.Environment.TEST,
                f.binding().merchantReference(),f.binding().credentialsReference(),"env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET",true);
        var registry=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,List.of(route),false,enabled),java.net.URI.create("https://synthetic-school.example.test"),Clock.systemUTC(),
                name->"synthetic-not-live-test-value",(r,id,secret,origin,clock)->{outsideProvider();return adapter;});
        var named=new NamedParameterJdbcTemplate(runtime);var queue=new GatewayWorkQueue(named,Clock.systemUTC());var dispatch=new GatewaySettlementDispatch(named,registry,store,queue);
        var audit=new SecurityAuditService(runtime,tools.jackson.databind.json.JsonMapper.builder().build(),Clock.systemUTC());
        return new Flow(key,adapter,queue,dispatch,new GatewaySettlementWorker(registry,queue,dispatch,store,transactionManager),new GatewaySettlementService(named,registry,audit));
    }
    private static void outsideProvider(){assertThat(TenantRequestContext.current()).isEmpty();assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()).isEmpty();}
    private UUID membership(Fixture f){return owner.queryForObject("SELECT id FROM memberships WHERE user_id=? AND school_id=?",UUID.class,f.user(),f.school());}
    private AuthenticatedUser actor(Fixture f){return new AuthenticatedUser(f.user(),UUID.randomUUID(),"synthetic@example.test",null,"Synthetic settlement actor",true,f.org(),Set.of(membership(f)),Set.of(),List.of());}
    private static GatewaySettlementModels.Submit command(Fixture f){return new GatewaySettlementModels.Submit(f.school(),UUID.randomUUID(),"Asia/Kolkata","SUMMARY",FROM,UNTIL,null,null);}
    private GatewaySettlementModels.Result submit(Fixture f,Flow flow,GatewaySettlementModels.Submit command){return human(f,()->flow.service().submit(actor(f),command));}
    private <T> T human(Fixture f,Supplier<T> operation){return as(f,f.user(),operation);}
    private record Flow(String key,PaymentProviderAdapter adapter,GatewayWorkQueue queue,GatewaySettlementDispatch dispatch,GatewaySettlementWorker worker,GatewaySettlementService service){}

    private GatewaySettlementReadStore.Prepared prepare(Fixture f){return background(f,()->store.prepareSummaries(f.binding(),UUID.randomUUID(),FROM,UNTIL));}
    private GatewaySettlementReadStore.Read retain(Fixture f,GatewaySettlementReadStore.Prepared q,String status,String cursor){return background(f,()->store.retainSummaries(f.binding(),q.id(),UUID.randomUUID(),summaryPage(status,cursor)));}
    private void raw(Fixture f,GatewaySettlementReadStore.Prepared q,String outcome,String facts){background(f,()->{runtime.update("INSERT INTO fee_gateway_settlement_reads(id,organisation_id,school_id,query_id,outcome,facts_canonical,evidence_sha256) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),f.org(),f.school(),q.id(),outcome,facts,"a".repeat(64));return null;});}
    private static GatewaySettlementProvider.SettlementPage summaryPage(String status,String cursor){return new GatewaySettlementProvider.SettlementPage(cursor,List.of(new GatewaySettlementProvider.SettlementSummary(
            "synthetic_statement",status,"synthetic_utr",Instant.parse("2026-01-10T00:00:00Z"),Instant.parse("2026-01-11T00:00:00Z"),
            Instant.parse("2026-01-12T00:00:00Z"),Instant.parse("2026-01-12T01:02:03.123456789Z"),new BigDecimal("100.00000000"),new BigDecimal("1.50"),null,
            new BigDecimal("-0.12345678"),null,null,null,null,null,new BigDecimal("98.12345678"),"PREPAID","STANDARD")));}
    private static GatewaySettlementProvider.SettlementEvent event(String kind,String currency){return new GatewaySettlementProvider.SettlementEvent("synthetic_statement","synthetic_reused_event",kind,"CREDIT","SUCCESS",currency,new BigDecimal("100.12345678"),null,null,new BigDecimal("98.12345678"),Instant.parse("2026-01-11T01:02:03.987654321Z"),"synthetic_order","synthetic_payment",List.of("synthetic_refund"),null);}
    private <T> T background(Fixture f,Supplier<T> operation){return as(f,null,operation);}
    private <T> T as(Fixture f,UUID user,Supplier<T> operation){try(var ignored=TenantRequestContext.open(user,f.org())){return transactions.execute(status->operation.get());}}
    private void assertNoMoney(Fixture f){for(String table:List.of("fee_gateway_captures","fee_payments","fee_ledger_entries","accounting_journals"))assertThat(owner.queryForObject("SELECT count(*) FROM "+table+" WHERE school_id=?",Integer.class,f.school())).isZero();}
    private List<String> migrationHistory(){return owner.queryForList("SELECT row_to_json(history)::text FROM flyway_schema_history history ORDER BY installed_rank",String.class);}
    private List<String> snapshot(Fixture f){return owner.queryForList("SELECT row_to_json(provider)::text FROM payment_provider_configs provider WHERE id=?",String.class,f.binding().providerConfigId());}
    private Fixture fixture(){return fixture(UUID.randomUUID());}
    private Fixture fixture(UUID org){
        UUID school=UUID.randomUUID(),user=UUID.randomUUID(),membership=UUID.randomUUID(),provider=UUID.randomUUID();
        if(owner.queryForObject("SELECT count(*) FROM organisations WHERE id=?",Integer.class,org)==0)owner.update("INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'Synthetic settlement school','Synthetic settlement school','ACTIVE',now(),now())",org,"synthetic-"+org);
        owner.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,?,'Synthetic settlement school','Synthetic settlement school','ACTIVE',now(),now())",school,org,"synthetic-"+school);
        owner.update("INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status) VALUES(?,?,?,'Synthetic settlement actor','not-a-login-hash','ACTIVE')",user,"synthetic-"+user+"@example.test","synthetic-"+user+"@example.test");
        owner.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',now()-interval '1 day',now(),now())",membership,user,org,school);
        owner.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,'00000000-0000-4000-8000-000000000001'::uuid)",membership);
        String merchant="synthetic_merchant_"+provider;
        owner.update("INSERT INTO payment_provider_configs(id,organisation_id,school_id,provider_code,environment,credentials_secret_reference,merchant_account_reference,status,configured_by_membership_id,created_at,updated_at) VALUES(?,?,?,'CASHFREE','TEST','synthetic:not-enabled',?,'ACTIVE',?,now(),now())",provider,org,school,merchant,membership);
        return new Fixture(org,school,user,new GatewayWorkQueue.ProviderBinding(org,school,provider,"CASHFREE","TEST",merchant,"synthetic:not-enabled"));
    }
    @AfterAll void removeOnlyGeneratedDatabaseAndRoles(){if(control!=null){if(databaseCreated)control.execute("DROP DATABASE "+identifier(database)+" WITH (FORCE)");if(runtimeCreated)control.execute("DROP ROLE "+identifier(runtimeLogin));if(ownerCreated)control.execute("DROP ROLE "+identifier(ownerLogin));}if(container!=null)container.stop();}
    private void createRole(String role,String password,String bypass) throws Exception {try(var connection=controlSource.getConnection();var format=connection.prepareStatement("SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER "+bypass+" NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',?,?)")){format.setString(1,role);format.setString(2,password);try(var row=format.executeQuery();var command=connection.createStatement()){row.next();command.execute(row.getString(1));}}}
    private static DriverManagerDataSource dataSource(String url,String user,String password){var result=new DriverManagerDataSource(url,user,password);var settings=new java.util.Properties();settings.setProperty("connectTimeout","10");settings.setProperty("socketTimeout","120");settings.setProperty("options","-c statement_timeout=90000");result.setConnectionProperties(settings);return result;}
    private static String identifier(String value){if(!value.matches("(?:educore_settlement_|synthetic_settlement_(?:owner|runtime)_)[a-f0-9]{32}"))throw new IllegalArgumentException("Generated settlement fixture required");return '"'+value+'"';}
    private static String secret(){byte[] value=new byte[32];new SecureRandom().nextBytes(value);return Base64.getEncoder().encodeToString(value);}
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Synthetic database configuration missing");return value;}
    private record Fixture(UUID org,UUID school,UUID user,GatewayWorkQueue.ProviderBinding binding){}
}
