package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Simulated orchestration/commit units, not actual PostgreSQL, HTTP merchant or financial acceptance. */
class GatewayRefundWorkerTest {
    private static final Instant NOW=Instant.parse("2026-10-22T08:00:00Z");
    @AfterEach void cleanup(){Thread.interrupted();TransactionSynchronizationManager.clear();assertThat(TenantRequestContext.current()).isEmpty();}

    @Test void disabledAndUnknownRoutesNeverAccessPersistenceOrProvider(){
        var f=new Fixture();when(f.registry.workerEnabled()).thenReturn(false);assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.DISABLED);
        when(f.registry.workerEnabled()).thenReturn(true);assertThat(f.worker.runNext("missing")).isEqualTo(GatewayAttemptWorker.Step.UNAVAILABLE);
        verifyNoInteractions(f.manager,f.queue,f.dispatch,f.provider,f.reads,f.successes);
    }

    @Test void callerHumanAndTransactionContextsCannotStartBackgroundProcessing(){
        var f=new Fixture();try(var ignored=TenantRequestContext.open(UUID.randomUUID(),f.binding.organisationId())){assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);}
        TransactionSynchronizationManager.setActualTransactionActive(true);assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(f.manager,f.queue,f.provider);
    }

    @Test void emptyAndRecoveredExpiredQueuesDoNotResolveCredentials(){
        var f=new Fixture();f.idle=true;assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.IDLE);f.expiredRecovered=true;
        assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RECOVERED);verify(f.registry,never()).provider(any());
    }

    @Test void newPostOccursOnlyAfterCommittedMarkerAndNeverTreatsItsSuccessReplyAsMoney(){
        var f=new Fixture();assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(f.events).containsSubsequence("ADMIT","COMMIT","POST");assertThat(f.marker).isTrue();assertThat(f.witnesses).isEmpty();assertThat(f.anchor).isFalse();
        assertThat(f.posts).isEqualTo(1);assertThat(f.gets).isZero();verifyNoInteractions(f.successes);verify(f.queue).complete(any(),eq(GatewayWorkQueue.Outcome.RETRY),eq("GATEWAY_REFUND_REQUIRES_GET"),eq(Duration.ofSeconds(1)));
    }

    @Test void nextLeaseReadsOnlySameIdAndRecognizesVerifiedHoldAfterWitnessCommit(){
        var f=new Fixture();assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(f.posts).isEqualTo(1);assertThat(f.gets).isEqualTo(1);assertThat(f.witnesses).hasSize(1);assertThat(f.anchor).isTrue();
        assertThat(f.events).containsSubsequence("GET","RETAIN","COMMIT","HOLD","COMPLETE");
        verify(f.registry,times(1)).provider(f.route);
    }

    @Test void opaquePostFailureAndRetryCanNeverSendAnotherPost(){
        var f=new Fixture();f.postFailure=true;assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);assertThat(f.posts).isEqualTo(1);assertThat(f.gets).isEqualTo(1);
    }

    @Test void unavailableCredentialsDoNotConsumeMarkerOrPoisonUnreadSource(){
        var f=new Fixture();when(f.registry.provider(f.route)).thenThrow(new IllegalStateException("Synthetic unavailable"));
        assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);assertThat(f.marker).isFalse();assertThat(f.witnesses).isEmpty();assertThat(f.posts).isZero();verify(f.dispatch,never()).admit(anyString(),any());
    }

    @Test void failedOrAmbiguousAdmissionCommitDoesNotPostAndRecoversItsActualMarker(){
        var f=new Fixture();f.unknownAdmissionCommit=true;assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(f.marker).isTrue();assertThat(f.posts).isZero();assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(f.posts).isZero();assertThat(f.gets).isEqualTo(1);
    }

    @Test void missingOriginalAccountingRetriesWithoutGetOrNewEvidence(){
        var f=new Fixture();when(f.dispatch.admit(anyString(),any())).thenThrow(new IllegalStateException("Synthetic accounting dependency"));
        assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);assertThat(f.witnesses).isEmpty();assertThat(f.marker).isFalse();assertThat(f.posts+f.gets).isZero();
    }

    @Test void disabledNewRefundsDoNotPostButAlreadyDispatchedAndAlreadyObservedRefundsStillRecover(){
        var f=new Fixture();when(f.registry.refundsEnabled()).thenReturn(false);
        assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);assertThat(f.posts+f.gets).isZero();
        f.priorObservation=true;assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);assertThat(f.posts).isZero();assertThat(f.gets).isEqualTo(1);
        var marked=new Fixture();marked.marker=true;when(marked.registry.refundsEnabled()).thenReturn(false);assertThat(marked.run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);assertThat(marked.posts).isZero();
    }

    @Test void unknownPendingFailedAndContradictoryGetsNeverCreateFinancialSuccessOrRepeatPost(){
        for(String state:List.of("UNKNOWN","PENDING","FAILED","CONTRADICTORY")){
            var f=new Fixture();f.marker=true;f.state=state;
            var expected=List.of("FAILED","CONTRADICTORY").contains(state)?GatewayAttemptWorker.Step.REVIEW_REQUIRED:GatewayAttemptWorker.Step.RETRY;
            assertThat(f.run()).isEqualTo(expected);assertThat(f.posts).isZero();assertThat(f.anchor).isFalse();assertThat(f.witnesses).hasSize(1);
        }
    }

    @Test void olderVerifiedAnchorIsNotErasedByLaterUnknownOrFailure(){
        for(String state:List.of("UNKNOWN","PENDING","FAILED")){
            var f=new Fixture();f.marker=true;f.anchor=true;f.state=state;assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
            assertThat(f.anchor).isTrue();assertThat(f.posts).isZero();verifyNoInteractions(f.successes);
        }
    }

    @Test void failedClaimCompletionRollsBackNewHoldButKeepsAlreadyCommittedWitness(){
        var f=new Fixture();f.marker=true;f.completeAllowed=false;assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(f.witnesses).hasSize(1);assertThat(f.anchor).isFalse();assertThat(f.pendingHold).isFalse();assertThat(f.events).containsSubsequence("RETAIN","COMMIT","HOLD","ROLLBACK");
    }

    @Test void lostLeaseAfterProviderReadStillRetainsTheIndependentRead(){
        var f=new Fixture();f.marker=true;f.loseLeaseAfterRead=true;assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(f.witnesses).hasSize(1);assertThat(f.anchor).isFalse();verifyNoInteractions(f.successes);
    }

    @Test void boundedPostTimeoutConsumesMarkerAndAllowsOnlyGetOnLaterLease() throws Exception {
        var f=new Fixture();f.leaseNanos=Duration.ofSeconds(30).plusMillis(180).toNanos();var release=new CountDownLatch(1);
        doAnswer(call->{f.external();f.posts++;try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}return null;}).when(f.provider).requestRefund(any());
        try{assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);}finally{release.countDown();}
        assertThat(f.marker).isTrue();f.leaseNanos=Duration.ofSeconds(89).toNanos();assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);assertThat(f.posts).isEqualTo(1);assertThat(f.gets).isEqualTo(1);
    }

    @Test void forgedQueueKindOrDatabaseScopeCannotReachProvider(){
        var f=new Fixture();f.scopeValid=false;assertThat(f.run()).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);verify(f.registry,never()).provider(any());
        var wrong=new Fixture();wrong.wrongKind=true;assertThat(wrong.run()).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);verify(wrong.registry,never()).provider(any());
    }

    private static final class Fixture {
        final GatewayProviderRegistry registry=mock(GatewayProviderRegistry.class);final GatewayWorkQueue queue=mock(GatewayWorkQueue.class);
        final GatewayRefundDispatch dispatch=mock(GatewayRefundDispatch.class);final GatewayRefundReadStore reads=mock(GatewayRefundReadStore.class);
        final GatewayRefundSuccessStore successes=mock(GatewayRefundSuccessStore.class);final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
        final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);final PaymentProviderAdapter provider=mock(PaymentProviderAdapter.class);
        final String key="c".repeat(64);final GatewayWorkQueue.ProviderBinding binding=new GatewayWorkQueue.ProviderBinding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CASHFREE","TEST","synthetic_merchant","synthetic:credential-reference");
        final GatewayProviderRegistry.Route route=new GatewayProviderRegistry.Route(key,binding.organisationId(),binding.schoolId(),binding.providerConfigId(),"CASHFREE",CashfreePaymentProviderAdapter.Environment.TEST,binding.merchantReference(),binding.credentialsReference(),"env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
        final UUID refund=UUID.randomUUID(),payment=UUID.randomUUID(),capture=UUID.randomUUID();
        final GatewayRefundReadStore.Prepared approved=new GatewayRefundReadStore.Prepared(new GatewayRefundVerification.Expected(refund,binding,payment,capture,"synthetic_order","synthetic_payment",new BigDecimal("200.00"),new BigDecimal("50.25"),NOW.minusSeconds(100),NOW.minusSeconds(90),NOW.minusSeconds(80),"Asia/Kolkata"),UUID.randomUUID());
        final List<String> events=new CopyOnWriteArrayList<>();final List<GatewayRefundReadStore.Read> witnesses=new ArrayList<>();
        final GatewayRefundWorker worker=new GatewayRefundWorker(registry,queue,dispatch,new GatewayProviderDiscovery(Clock.fixed(NOW,ZoneOffset.UTC)),reads,successes,jdbc,manager);
        boolean marker,pendingMarker,priorObservation,anchor,pendingHold,unknownAdmissionCommit,postFailure,idle,expiredRecovered,loseLeaseAfterRead,wrongKind;
        boolean completeAllowed=true,scopeValid=true;int posts,gets,attempt;long leaseNanos=Duration.ofSeconds(89).toNanos();String state="SUCCESS";GatewayRefundReadStore.Read pendingRead;
        Fixture(){
            when(registry.workerEnabled()).thenReturn(true);when(registry.refundsEnabled()).thenReturn(true);when(registry.findRoute(key)).thenReturn(Optional.of(route));
            when(registry.provider(route)).thenAnswer(call->{external();events.add("PROVIDER");return provider;});when(provider.providerCode()).thenReturn("CASHFREE");
            when(manager.getTransaction(any())).thenAnswer(call->{assertThat(TenantRequestContext.current().orElseThrow().userId()).isNull();assertThat(call.<TransactionDefinition>getArgument(0).getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);TransactionSynchronizationManager.setActualTransactionActive(true);events.add("BEGIN");return new SimpleTransactionStatus();});
            doAnswer(call->{boolean admitted=pendingMarker;if(pendingMarker){marker=true;pendingMarker=false;}if(pendingRead!=null){witnesses.add(pendingRead);pendingRead=null;}if(pendingHold){anchor=true;pendingHold=false;}events.add("COMMIT");TransactionSynchronizationManager.clear();if(admitted&&unknownAdmissionCommit){unknownAdmissionCommit=false;throw new IllegalStateException("Synthetic lost admission commit response");}return null;}).when(manager).commit(any());
            doAnswer(call->{pendingHold=false;pendingMarker=false;pendingRead=null;events.add("ROLLBACK");TransactionSynchronizationManager.clear();return null;}).when(manager).rollback(any());
            when(jdbc.queryForObject(anyString(),any(SqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{tx();return call.<String>getArgument(0).contains("fee_gateway_refund_successes")?anchor:scopeValid;});
            when(jdbc.queryForObject(anyString(),any(SqlParameterSource.class),eq(Integer.class))).thenAnswer(call->{tx();events.add("FENCE");return 1;});
            when(queue.recoverExpiredRefundLease(binding)).thenAnswer(call->{tx();assertThat(events).contains("FENCE");return expiredRecovered?refund:null;});
            when(queue.claimNextRefund(binding)).thenAnswer(call->{tx();return idle?null:new GatewayWorkQueue.Claim(wrongKind?GatewayWorkQueue.WorkKind.ATTEMPT:GatewayWorkQueue.WorkKind.REFUND,refund,binding,UUID.randomUUID(),NOW.minusSeconds(1),NOW.plusNanos(leaseNanos),++attempt,marker);});
            when(dispatch.loadForReadRecovery(eq(key),any())).thenAnswer(call->{tx();if(loseLeaseAfterRead&&!witnesses.isEmpty())throw new IllegalStateException("Synthetic lost lease");return new GatewayRefundDispatch.Recovery(approved,NOW,marker,priorObservation||!witnesses.isEmpty()||anchor);});
            when(dispatch.admit(eq(key),any())).thenAnswer(call->{tx();assertThat(marker).isFalse();pendingMarker=true;events.add("ADMIT");return new GatewayRefundDispatch.Command(approved,NOW);});
            when(provider.requestRefund(any())).thenAnswer(call->{external();assertThat(marker).isTrue();posts++;events.add("POST");if(postFailure)throw new IllegalStateException("Synthetic opaque mutation failure");return fact("SUCCESS");});
            when(provider.fetchRefund("synthetic_order",refund.toString())).thenAnswer(call->{external();gets++;events.add("GET");return state.equals("UNKNOWN")?null:fact(state);});
            when(reads.retain(eq(binding),eq(refund),any(),any())).thenAnswer(call->{tx();events.add("RETAIN");var observation=new GatewayRefundVerification.Observation(binding,call.getArgument(3),NOW);pendingRead=new GatewayRefundReadStore.Read(call.getArgument(2),approved.expected(),observation,GatewayRefundVerification.verify(approved.expected(),observation));return pendingRead;});
            when(successes.retain(eq(binding),eq(refund),any())).thenAnswer(call->{tx();assertThat(witnesses).isNotEmpty();events.add("HOLD");pendingHold=true;var read=witnesses.getLast();var proof=read.verification().success().orElseThrow();return new GatewayRefundSuccessStore.Success(refund,read.id(),approved.approvalDecisionId(),payment,capture,UUID.randomUUID(),approved.expected().refundAmount(),"INR",proof.processedAtExact(),proof.processedAtProjection(),proof.processedOn(),"Asia/Kolkata",NOW,null,read.verification().evidenceSha256(),1,"HOLD","AWAITING_REVIEWED_POSTING");});
            when(queue.complete(any(),any(),anyString(),any())).thenAnswer(call->{tx();events.add(call.getArgument(1).toString());return completeAllowed;});
        }
        GatewayAttemptWorker.Step run(){return worker.runNext(key);}
        PaymentProviderAdapter.RefundObservation fact(String type){return new PaymentProviderAdapter.RefundObservation(refund.toString(),"synthetic_order","synthetic_payment",new BigDecimal(type.equals("CONTRADICTORY")?"50.26":"50.25"),"INR",type.equals("FAILED")?PaymentProviderAdapter.RefundStatus.FAILED:type.equals("PENDING")?PaymentProviderAdapter.RefundStatus.PENDING:PaymentProviderAdapter.RefundStatus.SUCCESS,type.equals("PENDING")?null:NOW.minusSeconds(10));}
        void tx(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(TenantRequestContext.current().orElseThrow().organisationId()).isEqualTo(binding.organisationId());}
        void external(){assertThat(TenantRequestContext.current()).isEmpty();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();}
    }
}
