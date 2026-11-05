package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Boundary units only; real one-use constraints, RLS and reversal races are separate PostgreSQL tests. */
class GatewayRefundDispatchTest {
    private static final Instant NOW=Instant.parse("2026-10-22T06:00:00Z");
    @AfterEach void clearMarkers(){TransactionSynchronizationManager.clear();assertThat(TenantRequestContext.current()).isEmpty();}

    @Test void commandUsesOnlyApprovedTermsAfterCompleteProofAndOneSuccessfulMarkerInsert() throws Exception {
        var f=new Fixture();try(var ignored=f.system()){
            var result=f.service.admit(f.key,f.claim);var command=result.request();
            assertThat(result.approved()).isSameAs(f.approved);assertThat(result.dispatchedAt()).isEqualTo(NOW);
            assertThat(command.refundId()).isEqualTo(f.claim.id());assertThat(command.amount()).isEqualByComparingTo("25.25");
            assertThat(command.orderReference()).isEqualTo("synthetic_order");assertThat(command.paymentReference()).isEqualTo("synthetic_payment");
            assertThat(command.toString()).doesNotContain("25.25","synthetic_order","synthetic_payment",f.claim.id().toString());
            assertThat(result.toString()).doesNotContain(f.claim.id().toString(),f.key);
        }
        assertThat(f.events).containsExactly("context","context","current","clock","proof","insert");
        assertThat(f.insert.getValues()).containsEntry("itemId",f.proof.postingItemId()).containsEntry("batchHash",f.proof.batchEvidenceSha256());
        verify(f.registry,never()).provider(any());f.noFinancialDml();
    }

    @Test void persistedMarkerNeverBecomesAnotherPostEvenWithAStaleUndispatchedClaim() throws Exception {
        var f=new Fixture();f.dispatched=true;
        try(var ignored=f.system()){assertThat(f.service.loadForReadRecovery(f.key,f.claim).previouslyDispatched()).isTrue();denied(()->f.service.admit(f.key,f.claim));}
        assertThat(f.events).doesNotContain("proof","insert");verifyNoInteractions(f.accounting);f.noFinancialDml();
    }

    @Test void earlierProviderEvidenceRequiresRecoveryEvenWithoutADispatchMarker() throws Exception {
        var f=new Fixture();f.observed=true;
        try(var ignored=f.system()){
            var source=f.service.loadForReadRecovery(f.key,f.claim);
            assertThat(source.previouslyDispatched()).isFalse();assertThat(source.providerEvidenceRetained()).isTrue();assertThat(source.mustRecover()).isTrue();
            denied(()->f.service.admit(f.key,f.claim));
        }
        assertThat(f.events).doesNotContain("proof","insert");verifyNoInteractions(f.accounting);
    }

    @Test void uncertainOrDuplicateInsertDoesNotReturnAProviderCommand() throws Exception {
        var f=new Fixture();f.insertPresent=false;
        try(var ignored=f.system()){denied(()->f.service.admit(f.key,f.claim));}
        assertThat(f.events).contains("proof","insert");verify(f.registry,never()).provider(any());
    }

    @Test void disabledNewRefundFlagStillPermitsApprovedSameIdRecovery() throws Exception {
        var f=new Fixture();when(f.registry.refundsEnabled()).thenReturn(false);
        try(var ignored=f.system()){
            assertThat(f.service.loadForReadRecovery(f.key,f.claim).approved()).isEqualTo(f.approved);
            denied(()->f.service.admit(f.key,f.claim));
        }
        assertThat(f.events).doesNotContain("proof","insert");f.noFinancialDml();
    }

    @Test void absentHumanForeignReadonlyAndMissingTransactionContextsCannotBorrowSystemAuthority() throws Exception {
        var f=new Fixture();denied(()->f.service.admit(f.key,f.claim));
        try(var ignored=TenantRequestContext.open(UUID.randomUUID(),f.binding.organisationId())){denied(()->f.service.admit(f.key,f.claim));}
        try(var ignored=TenantRequestContext.open(null,UUID.randomUUID())){denied(()->f.service.admit(f.key,f.claim));}
        try(var ignored=f.system()){
            TransactionSynchronizationManager.setActualTransactionActive(false);denied(()->f.service.admit(f.key,f.claim));
            TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);denied(()->f.service.admit(f.key,f.claim));
        }
        verifyNoInteractions(f.jdbc,f.reads,f.accounting);
    }

    @Test void workerRouteKindLiveAndDispatchedClaimAreIndependentDenialsBeforeSourceRead() throws Exception {
        var f=new Fixture();try(var ignored=f.system()){
            when(f.registry.workerEnabled()).thenReturn(false);denied(()->f.service.admit(f.key,f.claim));when(f.registry.workerEnabled()).thenReturn(true);
            denied(()->f.service.admit("unknown",f.claim));
            var wrong=new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT,f.claim.id(),f.binding,f.claim.token(),f.claim.startedAt(),f.claim.leaseUntil(),1,false);
            denied(()->f.service.admit(f.key,wrong));
            var spent=new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.REFUND,f.claim.id(),f.binding,f.claim.token(),f.claim.startedAt(),f.claim.leaseUntil(),1,true);
            denied(()->f.service.admit(f.key,spent));
            when(f.registry.findRoute(f.key)).thenReturn(Optional.of(new GatewayProviderRegistry.Route(f.key,f.binding.organisationId(),f.binding.schoolId(),f.binding.providerConfigId(),"CASHFREE",CashfreePaymentProviderAdapter.Environment.LIVE,"synthetic_merchant","secretstore:synthetic","env:APP_FINANCE_GATEWAY_CASHFREE_LIVE_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_LIVE_CLIENT_SECRET")));
            denied(()->f.service.admit(f.key,f.claim));
        }
        verifyNoInteractions(f.jdbc,f.reads,f.accounting);
    }

    @Test void actualDatabaseMismatchStopsBeforeApprovedSourceOrClaimQueries() throws Exception {
        var f=new Fixture();f.databaseContext=false;try(var ignored=f.system()){denied(()->f.service.admit(f.key,f.claim));}
        assertThat(f.events).containsExactly("context");verifyNoInteractions(f.reads,f.accounting);
    }

    @Test void exactLeaseAndActualPostWaitDatabaseTimeAreRequiredEvenWithValidApprovedTerms() throws Exception {
        for(String problem:List.of("MISSING","TOO_EARLY","EXPIRED")){
            var f=new Fixture();if(problem.equals("MISSING"))f.currentPresent=false;
            if(problem.equals("TOO_EARLY"))f.checked=f.claim.startedAt().minusNanos(1);
            if(problem.equals("EXPIRED"))f.checked=f.claim.leaseUntil();
            try(var ignored=f.system()){denied(()->f.service.admit(f.key,f.claim));}
            assertThat(f.currentSql).contains("work.lease_token=:token","work.lease_started_at=:started","work.lease_until=:until","work.attempt_count=:attempt","FOR SHARE OF work");
            assertThat(f.events).doesNotContain("proof","insert");verifyNoInteractions(f.accounting);
        }
    }

    @Test void completeProofFailureCannotConsumeAOneUseMarkerOrReturnAPostCommand() throws Exception {
        var f=new Fixture();var failure=new IllegalStateException("Synthetic original accounting unavailable");
        when(f.accounting.verifyGatewayOriginalPaymentPosting(f.binding.organisationId(),f.binding.schoolId(),f.payment)).thenThrow(failure);
        try(var ignored=f.system()){assertThatThrownBy(()->f.service.admit(f.key,f.claim)).isSameAs(failure);}
        assertThat(f.events).doesNotContain("insert");verify(f.registry,never()).provider(any());
    }

    @Test void databaseMarkerFailureRollsOutWithoutInventingSuccessOrASecondCommand() throws Exception {
        var f=new Fixture();f.insertFailure=new org.springframework.dao.DataIntegrityViolationException("Synthetic exact marker refusal");
        try(var ignored=f.system()){assertThatThrownBy(()->f.service.admit(f.key,f.claim)).isSameAs(f.insertFailure);}
        verify(f.registry,never()).provider(any());f.noFinancialDml();
    }

    private static void denied(Runnable action){assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class);}
    private static final class Fixture {
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
        final GatewayProviderRegistry registry=mock(GatewayProviderRegistry.class);
        final GatewayRefundReadStore reads=mock(GatewayRefundReadStore.class);
        final FeeAccountingService accounting=mock(FeeAccountingService.class);
        final String key="a".repeat(64);
        final GatewayWorkQueue.ProviderBinding binding=new GatewayWorkQueue.ProviderBinding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CASHFREE","TEST","synthetic_merchant","secretstore:synthetic");
        final GatewayWorkQueue.Claim claim=new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.REFUND,UUID.randomUUID(),binding,UUID.randomUUID(),NOW.minusSeconds(1),NOW.plusSeconds(89),1,false);
        final UUID payment=UUID.randomUUID();
        final GatewayRefundReadStore.Prepared approved=new GatewayRefundReadStore.Prepared(new GatewayRefundVerification.Expected(claim.id(),binding,payment,UUID.randomUUID(),"synthetic_order","synthetic_payment",new BigDecimal("200.00"),new BigDecimal("25.25"),NOW.minusSeconds(100),NOW.minusSeconds(90),NOW.minusSeconds(80),"Asia/Kolkata"),UUID.randomUUID());
        final FeeAccountingService.OriginalPaymentPostingProof proof=new FeeAccountingService.OriginalPaymentPostingProof(payment,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),java.time.LocalDate.of(2026,10,21),"CASH","INR",UUID.randomUUID(),null,Map.of(),List.of(),"b".repeat(64),"c".repeat(64));
        final GatewayRefundDispatch service=new GatewayRefundDispatch(jdbc,registry,reads,accounting);
        final List<String> events=new ArrayList<>();
        boolean dispatched,observed,currentPresent=true,insertPresent=true,databaseContext=true;
        Instant checked=NOW;String currentSql;MapSqlParameterSource insert;RuntimeException insertFailure;
        @SuppressWarnings({"rawtypes","unchecked"}) Fixture() throws Exception {
            when(registry.workerEnabled()).thenReturn(true);when(registry.refundsEnabled()).thenReturn(true);
            var route=new GatewayProviderRegistry.Route(key,binding.organisationId(),binding.schoolId(),binding.providerConfigId(),"CASHFREE",CashfreePaymentProviderAdapter.Environment.TEST,binding.merchantReference(),binding.credentialsReference(),"env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
            when(registry.findRoute(key)).thenReturn(Optional.of(route));
            when(reads.prepare(binding,claim.id())).thenReturn(approved);
            when(accounting.verifyGatewayOriginalPaymentPosting(binding.organisationId(),binding.schoolId(),payment)).thenAnswer(call->{events.add("proof");return proof;});
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{events.add("context");return databaseContext;});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(org.springframework.jdbc.core.RowMapper.class))).thenAnswer(call->{events.add("clock");var row=mock(ResultSet.class);when(row.getTimestamp(1)).thenReturn(Timestamp.from(checked));return ((org.springframework.jdbc.core.RowMapper)call.getArgument(2)).mapRow(row,0);});
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),any(org.springframework.jdbc.core.RowMapper.class))).thenAnswer(call->{events.add("clock");var row=mock(ResultSet.class);when(row.getTimestamp(1)).thenReturn(Timestamp.from(checked));return ((org.springframework.jdbc.core.RowMapper)call.getArgument(2)).mapRow(row,0);});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(ResultSetExtractor.class))).thenAnswer(call->{
                String sql=call.getArgument(0);boolean present;var row=mock(ResultSet.class);int[] counter={0};
                if(sql.contains("refund-dispatch-current-claim")){events.add("current");currentSql=sql;present=currentPresent;when(row.getBoolean("dispatched")).thenReturn(dispatched);when(row.getBoolean("observed")).thenReturn(observed);}
                else if(sql.contains("INSERT INTO fee_gateway_refund_dispatches")){events.add("insert");insert=call.getArgument(1);if(insertFailure!=null)throw insertFailure;present=insertPresent;when(row.getTimestamp(1)).thenReturn(Timestamp.from(checked));}
                else throw new AssertionError("Only named current claim and one-use marker SQL allowed");
                when(row.next()).thenAnswer(read->++counter[0]==1&&present);return ((ResultSetExtractor)call.getArgument(2)).extractData(row);
            });
        }
        TenantRequestContext.Scope system(){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);return TenantRequestContext.open(null,binding.organisationId());}
        void noFinancialDml(){verify(jdbc,never()).update(anyString(),any(MapSqlParameterSource.class));verify(jdbc,never()).batchUpdate(anyString(),any(org.springframework.jdbc.core.namedparam.SqlParameterSource[].class));}
    }
}
