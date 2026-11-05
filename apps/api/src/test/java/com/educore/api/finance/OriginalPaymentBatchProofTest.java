package com.educore.api.finance;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Complete synthetic batch proofs with exact per-payment qualification; not PostgreSQL acceptance. */
class OriginalPaymentBatchProofTest {
    private static final LocalDate DATE=LocalDate.of(2026,10,27);
    private static final Instant CREATED=Instant.parse("2026-10-27T06:00:00Z");
    private static final String BATCH_HASH="a".repeat(64),SOURCE_HASH="b".repeat(64);

    @AfterEach void clearTransactionMarkers(){TransactionSynchronizationManager.clear();}

    @Test void bulkBoundaryRemainsReadOnlyPackageScopedAndExpectedProofConflictsDoNotMarkOuterReadRollbackOnly() throws Exception {
        var method=FeeAccountingService.class.getDeclaredMethod("verifyOriginalPaymentPostings",AuthenticatedUser.class,UUID.class,List.class);
        assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isFalse();
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(method.getAnnotation(Transactional.class).noRollbackFor()).contains(FeeAccountingService.HistoricalPostingProofUnavailable.class);
    }

    @Test void sharedCashAndAccrualBatchesProduceOnlyRequestedProofsWithOneWholeBatchReadEach() throws Exception {
        for(String basis:List.of("CASH","ACCRUAL")) {
            var f=new Fixture();var batch=f.batch(basis);var first=batch.payment("100.25","ONLINE_PROVIDER",1);var second=batch.payment("49.75","ONLINE_PROVIDER",1);
            batch.payment("20.00","CASH",1);batch.refresh();f.install();
            try(var ignored=f.human()) {
                var result=f.bulk(List.of(first.id,second.id,first.id));
                assertThat(result.proofs()).containsOnlyKeys(first.id,second.id);assertThat(result.invalidPaymentIds()).isEmpty();
                assertThat(result.proofs().get(first.id).allocations()).singleElement().satisfies(allocation->assertThat(allocation.amount()).isEqualByComparingTo("100.25"));
                assertThat(result.proofs().get(second.id).allocations()).singleElement().satisfies(allocation->assertThat(allocation.amount()).isEqualByComparingTo("49.75"));
                assertThat(result.proofs().get(first.id).accountingBasis()).isEqualTo(basis);
                assertThat(result.proofs().get(first.id).postingBatchId()).isEqualTo(result.proofs().get(second.id).postingBatchId());
            }
            assertThat(f.count("original-payment-proof-targets")).isEqualTo(1);
            assertThat(f.count("original-payment-proof-items")).isEqualTo(1);
            assertThat(f.count("original-payment-proof-allocations")).isEqualTo(1);
            assertThat(f.count("original-payment-proof-configuration")).isEqualTo(1);
            assertThat(f.journalReads).containsExactly(List.of(batch.journal));f.assertNoWrites();
        }
    }

    @Test void sparseMixedBatchesRemainIndependentAndDuplicateRequestedIdsDoNotRepeatTargetOrBatchWork() throws Exception {
        var f=new Fixture();var firstBatch=f.batch("CASH");var a=firstBatch.payment("11.25","ONLINE_PROVIDER",1);
        var b=firstBatch.payment("12.75","ONLINE_PROVIDER",1);var unrequested=firstBatch.payment("1.00","ONLINE_PROVIDER",1);
        var secondBatch=f.batch("ACCRUAL");var c=secondBatch.payment("25.00","ONLINE_PROVIDER",1);
        firstBatch.refresh();secondBatch.refresh();f.install();UUID missing=UUID.randomUUID();
        try(var ignored=f.human()) {
            var result=f.bulk(List.of(a.id,c.id,missing,a.id,b.id));
            assertThat(result.proofs()).containsOnlyKeys(a.id,b.id,c.id);assertThat(result.invalidPaymentIds()).containsExactly(missing);
            assertThat(result.proofs()).doesNotContainKey(unrequested.id);
        }
        assertThat(f.count("original-payment-proof-targets")).isEqualTo(1);assertThat(f.count("original-payment-proof-items")).isEqualTo(2);
        assertThat(f.count("original-payment-proof-allocations")).isEqualTo(2);assertThat(f.journalReads).hasSize(2);f.assertNoWrites();
    }

    @Test void provenManualOrUnqualifiedGatewaySiblingsNeverAcquireAnotherTargetsProof() throws Exception {
        for(String defect:List.of("MANUAL","CAPTURE_ABSENT","RESOLUTION_ABSENT","REVERSED")) {
            var f=new Fixture();var batch=f.batch("CASH");var valid=batch.payment("10.00","ONLINE_PROVIDER",1);
            var sibling=batch.payment("20.00","MANUAL".equals(defect)?"CASH":"ONLINE_PROVIDER",1);batch.refresh();
            // Each variant represents a missing independently gateway-qualified target;
            // the historical batch item/receipt can still legitimately be present.
            sibling.qualified=false;f.install();
            try(var ignored=f.human()) {
                var result=f.bulk(List.of(valid.id,sibling.id));
                assertThat(result.proofs()).containsOnlyKeys(valid.id);assertThat(result.invalidPaymentIds()).containsExactly(sibling.id);
            }
            assertThat(f.count("original-payment-proof-items")).isEqualTo(1);assertThat(f.journalReads).hasSize(1);f.assertNoWrites();
        }
    }

    @Test void eachReturnedTargetStillNeedsItsOwnPostedOnlineReceiptAmountAccountItemLedgerAndHash() throws Exception {
        for(String defect:List.of("REVERSED","MANUAL","AMOUNT","ACCOUNT","ITEM","LEDGER","HASH")) {
            var f=new Fixture();var batch=f.batch("CASH");var first=batch.payment("10.00","ONLINE_PROVIDER",1);var second=batch.payment("20.00","ONLINE_PROVIDER",1);batch.refresh();
            switch(defect) {
                case "REVERSED" -> second.target.put("receipt_status","REVERSED");
                case "MANUAL" -> second.target.put("payment_method","CASH");
                case "AMOUNT" -> second.target.put("receipt_amount",new BigDecimal("20.01"));
                case "ACCOUNT" -> second.target.put("receipt_account_id",UUID.randomUUID());
                case "ITEM" -> second.target.put("posting_item_id",first.item);
                case "LEDGER" -> second.target.put("fee_ledger_entry_id",first.ledger);
                case "HASH" -> second.target.put("source_sha256","c".repeat(64));
                default -> throw new AssertionError(defect);
            }
            f.install();try(var ignored=f.human()) {
                var result=f.bulk(List.of(first.id,second.id));
                assertThat(result.proofs()).containsOnlyKeys(first.id);assertThat(result.invalidPaymentIds()).containsExactly(second.id);
            }
            assertThat(f.count("original-payment-proof-items")).isEqualTo(1);f.assertNoWrites();
        }
    }

    @Test void aTargetSpecificAllocationLimitDoesNotInvalidateOtherTargetsOrLegitimateThousandAllocationManualSources() throws Exception {
        var f=new Fixture();var batch=f.batch("ACCRUAL");var small=batch.payment("10.00","ONLINE_PROVIDER",1);
        var tooMany=batch.payment("101.00","ONLINE_PROVIDER",101);var manual=batch.payment("1000.00","CASH",1000);batch.refresh();f.install();
        try(var ignored=f.human()) {
            var result=f.bulk(List.of(tooMany.id,small.id));
            assertThat(result.proofs()).containsOnlyKeys(small.id);assertThat(result.invalidPaymentIds()).containsExactly(tooMany.id);
            assertThat(result.proofs().get(small.id).allocations()).hasSize(1);
            assertThat(result.proofs()).doesNotContainKey(manual.id);
        }
        assertThat(f.count("original-payment-proof-allocations")).isEqualTo(1);f.assertNoWrites();
    }

    @Test void tamperedWholeBatchInvalidatesOnlyItsRequestedPaymentsAndNeverPublishesPartialProofs() throws Exception {
        for(String defect:List.of("ITEM","ALLOCATION","BATCH","JOURNAL")) {
            var f=new Fixture();var invalidBatch=f.batch("CASH");var a=invalidBatch.payment("10.00","ONLINE_PROVIDER",1);var b=invalidBatch.payment("20.00","ONLINE_PROVIDER",1);
            var goodBatch=f.batch("CASH");var good=goodBatch.payment("5.00","ONLINE_PROVIDER",1);invalidBatch.refresh();goodBatch.refresh();
            switch(defect) {
                case "ITEM" -> {var changed=new LinkedHashMap<>(b.snapshot);changed.put("amount",new BigDecimal("20.000000000000000001"));b.row.put("source_snapshot",json(changed));}
                case "ALLOCATION" -> b.allocations.getFirst().put("amount",new BigDecimal("19.99"));
                case "BATCH" -> {var changed=new LinkedHashMap<>(invalidBatch.snapshot);changed.put("unexpected",true);for(var payment:invalidBatch.payments)payment.target.put("evidence_snapshot",json(changed));}
                case "JOURNAL" -> invalidBatch.journalStatus="REVERSED";
                default -> throw new AssertionError(defect);
            }
            f.install();try(var ignored=f.human()) {
                var result=f.bulk(List.of(a.id,good.id,b.id));
                assertThat(result.proofs()).containsOnlyKeys(good.id);assertThat(result.invalidPaymentIds()).containsExactlyInAnyOrder(a.id,b.id);
            }
            assertThat(f.count("original-payment-proof-items")).isEqualTo(2);f.assertNoWrites();
        }
    }

    @Test void exactSiblingBatchHeadersMustMatchTheVerifiedRepresentative() throws Exception {
        for(String defect:List.of("CONFIGURATION","JOURNAL","YEAR","PERIOD","DATE","COUNT","TOTAL","DIGEST","SNAPSHOT")) {
            var f=new Fixture();var batch=f.batch("CASH");var first=batch.payment("10.00","ONLINE_PROVIDER",1);var second=batch.payment("20.00","ONLINE_PROVIDER",1);batch.refresh();
            switch(defect) {
                case "CONFIGURATION" -> second.target.put("configuration_id",UUID.randomUUID());
                case "JOURNAL" -> second.target.put("accounting_journal_id",UUID.randomUUID());
                case "YEAR" -> second.target.put("financial_year_id",UUID.randomUUID());
                case "PERIOD" -> second.target.put("accounting_period_id",UUID.randomUUID());
                case "DATE" -> second.target.put("business_date",DATE.minusDays(1));
                case "COUNT" -> second.target.put("source_count",3);
                case "TOTAL" -> second.target.put("source_amount",new BigDecimal("30.01"));
                case "DIGEST" -> second.target.put("evidence_sha256","d".repeat(64));
                case "SNAPSHOT" -> {var changed=new LinkedHashMap<>(batch.snapshot);changed.put("unexpected",null);second.target.put("evidence_snapshot",json(changed));}
                default -> throw new AssertionError(defect);
            }
            f.install();try(var ignored=f.human()) {
                var result=f.bulk(List.of(first.id,second.id));assertThat(result.proofs()).containsOnlyKeys(first.id);assertThat(result.invalidPaymentIds()).containsExactly(second.id);
            }
            f.assertNoWrites();
        }
    }

    @Test void ambiguousTargetsAreNotDeduplicatedIntoAnApparentlyValidGatewayReceipt() throws Exception {
        var f=new Fixture();var batch=f.batch("CASH");var a=batch.payment("10.00","ONLINE_PROVIDER",1);var b=batch.payment("20.00","ONLINE_PROVIDER",1);batch.refresh();
        f.extraTargets.add(new HashMap<>(a.target));f.install();
        try(var ignored=f.human()) {var result=f.bulk(List.of(a.id,b.id));assertThat(result.proofs()).containsOnlyKeys(b.id);assertThat(result.invalidPaymentIds()).containsExactly(a.id);}
        f.assertNoWrites();
    }

    @Test void oneBoundedTargetQueryRetainsEveryCaptureAttemptResolutionAndReceiptScopeCondition() throws Exception {
        var f=new Fixture();var batch=f.batch("CASH");var a=batch.payment("10.00","ONLINE_PROVIDER",1);batch.refresh();f.install();
        try(var ignored=f.human()){assertThat(f.bulk(List.of(a.id,a.id)).proofs()).containsOnlyKeys(a.id);}
        assertThat(f.reads.stream().filter(read->read.sql().contains("original-payment-proof-targets")).toList()).singleElement().satisfies(read->{
            assertThat(read.sql()).contains("payment.id IN (:paymentIds)","payment.organisation_id=:organisationId","payment.school_id=:schoolId",
                    "ledger.gateway_capture_id=payment.gateway_capture_id","capture.amount=payment.amount","capture.currency_code=account.currency_code",
                    "attempt.learner_fee_account_id=account.id","attempt.fee_payment_id=payment.id","posting_item.status='ACTIVE'",
                    "payment.status='POSTED'","payment.payment_method='ONLINE_PROVIDER'","resolution.resolution_type='POSTED'",
                    "resolution.fee_payment_id=payment.id","resolution.open_date=payment.received_on","PARTITION BY payment_id","target_rank<=2","LIMIT :targetLimit");
            assertThat(read.parameters()).containsEntry("organisationId",f.org).containsEntry("schoolId",f.school)
                    .containsEntry("paymentIds",Set.of(a.id)).containsEntry("targetLimit",3);
        });f.assertNoWrites();
    }

    @Test void resultCollectionsAreImmutablePrivateAndContainNoUnrequestedManualOrAllocationData() throws Exception {
        var f=new Fixture();var batch=f.batch("CASH");var a=batch.payment("10.00","ONLINE_PROVIDER",1);var manual=batch.payment("20.00","CASH",1);batch.refresh();f.install();
        try(var ignored=f.human()) {
            var result=f.bulk(List.of(a.id));assertThat(result.proofs()).containsOnlyKeys(a.id);assertThat(result.invalidPaymentIds()).isEmpty();
            assertThatThrownBy(result.proofs()::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(result.invalidPaymentIds()::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThat(result.toString()).doesNotContain(a.id.toString(),manual.id.toString(),a.account.toString(),"10.00",SOURCE_HASH,BATCH_HASH);
            assertThat(result.proofs().get(a.id).allocations().stream().map(FeeAccountingService.OriginalPaymentAllocation::chargeId).toList())
                    .containsExactly((UUID)a.allocations.getFirst().get("learner_fee_charge_id"));
        }
        f.assertNoWrites();
    }

    @Test void authorityOutagesAndUnrelatedRuleFailuresAreNotTurnedIntoInvalidFinancialSources() throws Exception {
        for(RuntimeException failure:List.of(new DataAccessResourceFailureException("Synthetic unavailable storage"),
                new ApiException(HttpStatus.CONFLICT,"SYNTHETIC_OTHER_RULE","Synthetic unrelated failure"))) {
            var f=new Fixture();var batch=f.batch("CASH");var a=batch.payment("10.00","ONLINE_PROVIDER",1);batch.refresh();f.journalFailure=failure;f.install();
            try(var ignored=f.human()){assertThatThrownBy(()->f.bulk(List.of(a.id))).isSameAs(failure);}f.assertNoWrites();
        }
        var f=new Fixture();var batch=f.batch("CASH");var a=batch.payment("10.00","ONLINE_PROVIDER",1);batch.refresh();
        f.journalFailure=new ApiException(HttpStatus.FORBIDDEN,"ACCOUNTING_SCOPE_DENIED","Synthetic revoked grant");f.install();
        try(var ignored=f.human()){assertThatThrownBy(()->f.bulk(List.of(a.id))).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.code()).isEqualTo("FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED"));}f.assertNoWrites();
    }

    @Test void missingContextForeignSchoolRevokedScopeAndUnboundedRequestsCannotReadBatches() throws Exception {
        for(String defect:List.of("NO_CONTEXT","WRONG_USER","DATABASE_CONTEXT","SCOPE","FOREIGN_SCHOOL","NULL_IDS","NULL_ID","TOO_MANY")) {
            var f=new Fixture();var batch=f.batch("CASH");var a=batch.payment("10.00","ONLINE_PROVIDER",1);batch.refresh();
            f.databaseContext=!"DATABASE_CONTEXT".equals(defect);f.scopeAllowed=!"SCOPE".equals(defect);f.install();
            List<UUID> requested=switch(defect) {
                case "NULL_IDS" -> null;
                case "NULL_ID" -> java.util.Arrays.asList(a.id,null);
                case "TOO_MANY" -> java.util.Collections.nCopies(1001,a.id);
                default -> List.of(a.id);
            };
            if("NO_CONTEXT".equals(defect))assertThatThrownBy(()->f.bulk(requested)).isInstanceOf(ApiException.class);
            else try(var ignored=TenantRequestContext.open("WRONG_USER".equals(defect)?UUID.randomUUID():f.userId,f.org)) {
                TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
                assertThatThrownBy(()->f.service.verifyOriginalPaymentPostings(f.user,"FOREIGN_SCHOOL".equals(defect)?UUID.randomUUID():f.school,requested)).isInstanceOf(ApiException.class);
            }
            assertThat(f.count("original-payment-proof-targets")).isZero();assertThat(f.count("original-payment-proof-items")).isZero();
            verifyNoInteractions(f.accounting);f.assertNoWrites();
        }
    }

    @Test void repeatedCallsReproveFreshEvidenceRatherThanReusingAServiceOrThreadCache() throws Exception {
        var f=new Fixture();var batch=f.batch("CASH");var a=batch.payment("10.00","ONLINE_PROVIDER",1);batch.refresh();f.install();
        try(var ignored=f.human()) {
            assertThat(f.bulk(List.of(a.id)).proofs()).containsOnlyKeys(a.id);
            batch.journalStatus="REVERSED";
            var second=f.bulk(List.of(a.id));assertThat(second.proofs()).isEmpty();assertThat(second.invalidPaymentIds()).containsExactly(a.id);
        }
        assertThat(f.count("original-payment-proof-targets")).isEqualTo(2);assertThat(f.count("original-payment-proof-items")).isEqualTo(2);
        assertThat(f.journalReads).hasSize(2);f.assertNoWrites();
    }

    @Test void anEmptyAuthorizedRequestReturnsNoProofsWithoutReadingAnyBatch() throws Exception {
        var f=new Fixture();f.install();try(var ignored=f.human()){var result=f.bulk(List.of());assertThat(result.proofs()).isEmpty();assertThat(result.invalidPaymentIds()).isEmpty();}
        assertThat(f.count("original-payment-proof-targets")).isZero();verifyNoInteractions(f.accounting);f.assertNoWrites();
    }

    private static String json(Object value){return JsonMapper.builder().build().writeValueAsString(value);}
    private record Read(String sql,Map<String,Object> parameters) {}

    private static final class Fixture {
        final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),userId=UUID.randomUUID(),membership=UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);final AccountingModuleBridge accounting=mock(AccountingModuleBridge.class);
        final SecurityAuditService audit=mock(SecurityAuditService.class);final FeeAccountingService service=new FeeAccountingService(jdbc,accounting,audit,JsonMapper.builder().build(),Clock.fixed(CREATED,ZoneOffset.UTC));
        final AuthenticatedUser user=new AuthenticatedUser(userId,UUID.randomUUID(),"synthetic@example.test",null,"Synthetic source reviewer",true,org,
                Set.of(membership),Set.of("fees.read","accounting.read"),List.of());
        final List<Batch> batches=new ArrayList<>();final List<Read> reads=new ArrayList<>();final List<List<UUID>> journalReads=new ArrayList<>();
        final List<Map<String,Object>> extraTargets=new ArrayList<>();boolean databaseContext=true,scopeAllowed=true;RuntimeException journalFailure;
        Batch batch(String basis){var batch=new Batch(this,basis);batches.add(batch);return batch;}
        FeeAccountingService.OriginalPaymentProofBatch bulk(List<UUID> ids){return service.verifyOriginalPaymentPostings(user,school,ids);}
        TenantRequestContext.Scope human(){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);return TenantRequestContext.open(userId,org);}
        long count(String marker){return reads.stream().filter(read->read.sql().contains(marker)).count();}
        void assertNoWrites(){verify(jdbc,never()).update(anyString(),any(MapSqlParameterSource.class));verifyNoInteractions(audit);
            assertThat(reads).noneMatch(read->read.sql().matches("(?is).*\\b(INSERT|UPDATE|DELETE|pg_advisory_xact_lock)\\b.*"));}

        @SuppressWarnings({"unchecked","rawtypes"}) void install()throws Exception {
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{read(call.getArgument(0),call.getArgument(1));return databaseContext;});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenAnswer(call->{
                String sql=call.getArgument(0);var parameters=call.<MapSqlParameterSource>getArgument(1);read(sql,parameters);List<Map<String,Object>> selected;
                if(sql.contains("FROM memberships membership"))selected=scopeAllowed&&school.equals(parameters.getValue("schoolId"))?
                        List.of(new HashMap<>(Map.of("school_id",school,"display_name","Synthetic school","membership_id",membership,"time_zone","Asia/Kolkata"))):List.of();
                else if(sql.contains("original-payment-proof-targets")) {
                    Set<UUID> requested=(Set<UUID>)parameters.getValue("paymentIds");selected=new ArrayList<>();
                    for(var batch:batches)for(var payment:batch.payments)if(payment.qualified&&requested.contains(payment.id))selected.add(payment.target);
                    for(var target:extraTargets)if(requested.contains(target.get("payment_id")))selected.add(target);
                } else if(sql.contains("original-payment-proof-items"))selected=findBatch((UUID)parameters.getValue("batchId")).payments.stream().map(payment->payment.row).toList();
                else if(sql.contains("original-payment-proof-allocations")) {
                    Set<UUID> ids=(Set<UUID>)parameters.getValue("paymentIds");selected=new ArrayList<>();
                    for(var batch:batches)for(var payment:batch.payments)if(ids.contains(payment.id))selected.addAll(payment.allocations);
                } else if(sql.contains("original-payment-proof-configuration"))selected=List.of(findConfiguration((UUID)parameters.getValue("id")).configurationRow);
                else throw new AssertionError("Unexpected original proof mapper query");
                RowMapper mapper=call.getArgument(2);var result=new ArrayList<>();for(var value:selected)result.add(mapper.mapRow(row(value),result.size()));return result;
            });
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(ResultSetExtractor.class))).thenAnswer(call->{
                String sql=call.getArgument(0);var parameters=call.<MapSqlParameterSource>getArgument(1);read(sql,parameters);
                var batch=findConfiguration((UUID)parameters.getValue("id"));
                List<Map<String,Object>> mappings;
                if(sql.contains("fee_accounting_head_mappings"))mappings=List.of(new HashMap<>(Map.of("fee_head_id",batch.head,"income_account_id",batch.income)));
                else if(sql.contains("fee_accounting_tender_mappings"))mappings=List.of(new HashMap<>(Map.of("payment_method","ONLINE_PROVIDER","deposit_account_id",batch.clearing)),
                        new HashMap<>(Map.of("payment_method","CASH","deposit_account_id",batch.cash)));
                else throw new AssertionError("Unexpected original proof configuration mapping query");
                return call.<ResultSetExtractor>getArgument(2).extractData(cursor(mappings));
            });
            when(accounting.feeJournalEvidence(eq(user),eq(school),anyList())).thenAnswer(call->{
                List<UUID> ids=List.copyOf(call.<List<UUID>>getArgument(2));journalReads.add(ids);if(journalFailure!=null)throw journalFailure;
                Map<UUID,FeeJournalEvidence> result=new HashMap<>();for(var batch:batches)if(ids.contains(batch.journal))result.put(batch.journal,batch.evidence());return result;
            });
        }
        Batch findBatch(UUID id){return batches.stream().filter(batch->batch.id.equals(id)).findFirst().orElseThrow();}
        Batch findConfiguration(UUID id){return batches.stream().filter(batch->batch.configuration.equals(id)).findFirst().orElseThrow();}
        void read(String sql,MapSqlParameterSource parameters){reads.add(new Read(sql,new LinkedHashMap<>(parameters.getValues())));}
    }

    private static final class Batch {
        final Fixture owner;final String basis;final UUID id=UUID.randomUUID(),configuration=UUID.randomUUID(),journal=UUID.randomUUID(),year=UUID.randomUUID(),period=UUID.randomUUID();
        final UUID head=UUID.randomUUID(),income=UUID.randomUUID(),clearing=UUID.randomUUID(),cash=UUID.randomUUID(),receivable=UUID.randomUUID(),concession=UUID.randomUUID();
        final List<Payment> payments=new ArrayList<>();final Map<String,Object> snapshot=new LinkedHashMap<>(),configurationRow=new HashMap<>();String journalStatus="POSTED";
        Batch(Fixture owner,String basis){this.owner=owner;this.basis=basis;}
        Payment payment(String amount,String method,int count){var payment=new Payment(this,new BigDecimal(amount),method,count);payments.add(payment);return payment;}
        void refresh(){
            BigDecimal total=payments.stream().map(payment->payment.amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            snapshot.put("schemaVersion",1);snapshot.put("configurationId",configuration);snapshot.put("financialYearId",year);snapshot.put("accountingPeriodId",period);
            snapshot.put("accountingBasis",basis);snapshot.put("currencyCode","INR");snapshot.put("businessDate",DATE);snapshot.put("eventType","PAYMENT");
            snapshot.put("sourceCount",payments.size());snapshot.put("sourceAmount",total);snapshot.put("sources",payments.stream().map(payment->payment.snapshot).toList());
            configurationRow.put("id",configuration);configurationRow.put("financial_year_id",year);configurationRow.put("supersedes_configuration_id",null);
            configurationRow.put("revision_number",1);configurationRow.put("effective_from",DATE.minusMonths(6));configurationRow.put("accounting_basis_snapshot",basis);
            configurationRow.put("currency_code","INR");configurationRow.put("receivable_account_id","ACCRUAL".equals(basis)?receivable:null);
            configurationRow.put("concession_account_id","ACCRUAL".equals(basis)?concession:null);configurationRow.put("status","APPROVED");
            configurationRow.put("created_by_membership_id",owner.membership);configurationRow.put("row_version",2L);
            for(int index=0;index<payments.size();index++) {
                var payment=payments.get(index);payment.row.put("sequence_number",index+1);
                payment.target.put("payment_id",payment.id);payment.target.put("fee_ledger_entry_id",payment.ledger);payment.target.put("posting_item_id",payment.item);
                payment.target.put("posting_batch_id",id);payment.target.put("configuration_id",configuration);payment.target.put("accounting_journal_id",journal);
                payment.target.put("financial_year_id",year);payment.target.put("accounting_period_id",period);payment.target.put("business_date",DATE);
                payment.target.put("event_type","PAYMENT");payment.target.put("disposition","JOURNAL");payment.target.put("source_count",payments.size());payment.target.put("source_amount",total);
                payment.target.put("evidence_snapshot",json(snapshot));payment.target.put("evidence_sha256",BATCH_HASH);payment.target.put("source_sha256",SOURCE_HASH);
                payment.target.put("receipt_status","POSTED");payment.target.put("payment_method",payment.method);payment.target.put("receipt_amount",payment.amount);
                payment.target.put("receipt_account_id",payment.account);payment.target.put("receipt_currency","INR");
            }
        }
        FeeJournalEvidence evidence(){
            var lines=new ArrayList<FeeJournalLineEvidence>();Map<UUID,BigDecimal> deposits=new LinkedHashMap<>();
            for(var payment:payments)deposits.merge("CASH".equals(payment.method)?cash:clearing,payment.amount,BigDecimal::add);
            deposits.forEach((account,amount)->lines.add(line(account,null,amount,BigDecimal.ZERO,"ASSET",account.equals(cash)?"CASH":"OTHER")));
            if("CASH".equals(basis))lines.add(line(income,null,BigDecimal.ZERO,payments.stream().map(payment->payment.amount).reduce(BigDecimal.ZERO,BigDecimal::add),"INCOME","INCOME"));
            else for(var payment:payments)lines.add(line(receivable,payment.account,BigDecimal.ZERO,payment.amount,"ASSET","RECEIVABLE"));
            return new FeeJournalEvidence(journal,id,year,period,DATE,basis,"INR",journalStatus,"CLOSED","CLOSED",null,null,lines,List.of());
        }
        FeeJournalLineEvidence line(UUID account,UUID learner,BigDecimal debit,BigDecimal credit,String type,String category){return new FeeJournalLineEvidence(account,null,learner,
                debit.setScale(2),credit.setScale(2),type,category,"ACTIVE",true);}
    }

    private static final class Payment {
        final UUID id=UUID.randomUUID(),ledger=UUID.randomUUID(),item=UUID.randomUUID(),account=UUID.randomUUID();final BigDecimal amount;final String method;
        final Map<String,Object> snapshot=new LinkedHashMap<>(),row=new HashMap<>(),target=new HashMap<>();final List<Map<String,Object>> allocations=new ArrayList<>();boolean qualified;
        Payment(Batch batch,BigDecimal amount,String method,int count){
            this.amount=amount;this.method=method;qualified="ONLINE_PROVIDER".equals(method);String receipt="SYN-"+id;
            var frozen=new ArrayList<Map<String,Object>>();BigDecimal part=count==1?amount:BigDecimal.ONE;
            if(count>1&&amount.compareTo(new BigDecimal(count))!=0)throw new IllegalArgumentException("Synthetic multi-allocation fixture must have exact one-unit rows");
            for(int index=0;index<count;index++) {
                UUID charge=UUID.randomUUID();allocations.add(new HashMap<>(Map.of("allocation_id",UUID.randomUUID(),"fee_payment_id",id,"learner_fee_charge_id",charge,
                        "fee_head_id",batch.head,"learner_fee_account_id",account,"amount",part)));
                frozen.add(Map.of("learnerFeeChargeId",charge,"feeHeadId",batch.head,"feeHeadCode","FROZEN_TUITION","feeHeadName","Synthetic tuition","amount",part));
            }
            snapshot.put("feeLedgerEntryId",ledger);snapshot.put("learnerFeeAccountId",account);snapshot.put("learnerFeeChargeId",null);snapshot.put("eventType","PAYMENT");
            snapshot.put("direction","CREDIT");snapshot.put("amount",amount);snapshot.put("effectiveOn",DATE);snapshot.put("description","Synthetic captured receipt");
            snapshot.put("sourceType","FEE_PAYMENT");snapshot.put("sourceId",id);snapshot.put("reversesEntryId",null);snapshot.put("createdAt",CREATED);snapshot.put("currencyCode","INR");
            snapshot.put("feeHeadId",null);snapshot.put("feeHeadCode",null);snapshot.put("feeHeadName",null);snapshot.put("paymentMethod",method);snapshot.put("receiptNumber",receipt);snapshot.put("allocations",frozen);
            row.put("posting_item_id",item);row.put("item_status","ACTIVE");row.put("source_snapshot",json(snapshot));row.put("source_sha256",SOURCE_HASH);
            row.put("id",ledger);row.put("learner_fee_account_id",account);row.put("entry_type","PAYMENT");row.put("direction","CREDIT");row.put("amount",amount);
            row.put("effective_on",DATE);row.put("description","Synthetic captured receipt");row.put("source_type","FEE_PAYMENT");row.put("source_id",id);row.put("created_at",CREATED);
            row.put("currency_code","INR");row.put("payment_method",method);row.put("receipt_number",receipt);
        }
    }

    private static ResultSet row(Map<String,Object> values)throws Exception{return rowAt(List.of(values),new int[]{0});}
    private static ResultSet cursor(List<Map<String,Object>> values)throws Exception{return rowAt(values,new int[]{-1});}
    private static ResultSet rowAt(List<Map<String,Object>> values,int[] index)throws Exception{
        ResultSet result=mock(ResultSet.class);when(result.next()).thenAnswer(call->++index[0]<values.size());
        when(result.getObject(anyString(),any(Class.class))).thenAnswer(call->values.get(index[0]).get(call.<String>getArgument(0)));
        when(result.getObject(anyString())).thenAnswer(call->values.get(index[0]).get(call.<String>getArgument(0)));
        when(result.getString(anyString())).thenAnswer(call->{Object value=values.get(index[0]).get(call.<String>getArgument(0));return value==null?null:value.toString();});
        when(result.getBigDecimal(anyString())).thenAnswer(call->(BigDecimal)values.get(index[0]).get(call.<String>getArgument(0)));
        when(result.getBoolean(anyString())).thenAnswer(call->Boolean.TRUE.equals(values.get(index[0]).get(call.<String>getArgument(0))));
        when(result.getInt(anyString())).thenAnswer(call->{Object value=values.get(index[0]).get(call.<String>getArgument(0));return value==null?0:((Number)value).intValue();});
        when(result.getLong(anyString())).thenAnswer(call->{Object value=values.get(index[0]).get(call.<String>getArgument(0));return value==null?0L:((Number)value).longValue();});
        when(result.getTimestamp(anyString())).thenAnswer(call->{Instant value=(Instant)values.get(index[0]).get(call.<String>getArgument(0));return value==null?null:Timestamp.from(value);});
        return result;
    }
}
