package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Orchestration mocks only; actual PostgreSQL constraints, retries and atomicity need integration tests. */
class GatewayRefundInboxRecoveryTest {
    private static final UUID ORG=UUID.fromString("83000000-0000-4000-8000-000000000001");
    private static final UUID SCHOOL=UUID.fromString("83000000-0000-4000-8000-000000000002");
    private static final UUID REFUND=UUID.fromString("83000000-0000-4000-8000-000000000003");
    private static final UUID PAYMENT=UUID.fromString("83000000-0000-4000-8000-000000000004");
    private static final UUID CAPTURE=UUID.fromString("83000000-0000-4000-8000-000000000005");
    private static final UUID APPROVAL=UUID.fromString("83000000-0000-4000-8000-000000000006");
    private static final Instant NOW=Instant.now().minusMillis(10);
    private static final String ROUTE="c".repeat(64),ORDER="synthetic_refund_inbox_order",PAYMENT_REF="synthetic_refund_inbox_payment";
    private final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    private final GatewayInboxDispatch dispatch=mock(GatewayInboxDispatch.class);
    private final GatewayWorkQueue queue=mock(GatewayWorkQueue.class);
    private final GatewayRefundReadStore reads=mock(GatewayRefundReadStore.class);
    private final GatewayRefundSuccessStore successes=mock(GatewayRefundSuccessStore.class);
    private final GatewayProviderDiscovery discovery=mock(GatewayProviderDiscovery.class);
    private final PaymentProviderAdapter provider=mock(PaymentProviderAdapter.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final GatewayWorkQueue.ProviderBinding binding=new GatewayWorkQueue.ProviderBinding(ORG,SCHOOL,UUID.randomUUID(),"CASHFREE","TEST","synthetic_inbox_merchant","SYNTHETIC_INBOX_KEY_LABEL");
    private final GatewayWorkQueue.Claim claim=new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK,UUID.randomUUID(),binding,UUID.randomUUID(),NOW.minusSeconds(1),NOW.plusSeconds(89),1,false);
    private final GatewayInboxDispatch.Admission admission=new GatewayInboxDispatch.Admission(Optional.empty(),"REFUND_STATUS_WEBHOOK",ORDER,PAYMENT_REF,REFUND.toString(),false,NOW);
    private final GatewayRefundVerification.Expected expected=new GatewayRefundVerification.Expected(REFUND,binding,PAYMENT,CAPTURE,ORDER,PAYMENT_REF,new BigDecimal("200.00"),new BigDecimal("50.25"),NOW.minusSeconds(120),NOW.minusSeconds(100),NOW.minusSeconds(60),"Asia/Kolkata");
    private final GatewayRefundReadStore.Prepared prepared=new GatewayRefundReadStore.Prepared(expected,APPROVAL);
    private final List<String> events=new ArrayList<>();
    private final AtomicReference<GatewayRefundReadStore.Read> retained=new AtomicReference<>();
    private final GatewayRefundInboxRecovery service=new GatewayRefundInboxRecovery(jdbc,dispatch,queue,reads,successes,discovery,manager);
    private PaymentProviderAdapter.RefundObservation fact=new PaymentProviderAdapter.RefundObservation(REFUND.toString(),ORDER,PAYMENT_REF,new BigDecimal("50.25"),"INR",PaymentProviderAdapter.RefundStatus.SUCCESS,NOW.minusSeconds(1));
    private boolean completeAllowed=true,priorSuccess,expiredAfterRead;

    @AfterEach void clearOnlySyntheticThreadMarkers(){Thread.interrupted();TransactionSynchronizationManager.clear();assertThat(TenantRequestContext.current()).isEmpty();}

    @Test void exactCallbackCommitsIndependentReadBeforeClaimFencedHoldAndCompletesWithoutFinancialPosting(){
        ready();assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(events).containsSubsequence("BEGIN","CURRENT","COMMIT","GET","BEGIN","HISTORICAL","RETAIN","COMMIT","BEGIN","CURRENT","HOLD","COMPLETE","COMMIT");
        verify(reads).retain(eq(binding),eq(REFUND),any(),same(fact));verify(successes).retain(eq(binding),eq(REFUND),any());
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.COMPLETE),eq("GATEWAY_REFUND_VERIFIED_AWAITING_POSTING"),any());
        assertNoProviderMutation();
    }

    @Test void pendingOrUnavailableReadsRetryAndCannotCreateHold(){
        for(boolean unavailable:new boolean[]{false,true}){
            reset(reads,successes,queue,dispatch,discovery,manager,jdbc);events.clear();retained.set(null);
            fact=unavailable?null:new PaymentProviderAdapter.RefundObservation(REFUND.toString(),ORDER,PAYMENT_REF,new BigDecimal("50.25"),"INR",PaymentProviderAdapter.RefundStatus.PENDING,null);
            ready();assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);
            verify(successes,never()).retain(any(),any(),any());
            verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.RETRY),eq("GATEWAY_REFUND_CONFIRMATION_PENDING"),any());
        }
        assertNoProviderMutation();
    }

    @Test void providerFailureRequiresReviewAndNeverReleasesBudgetOrMakesASecondRefund(){
        fact=new PaymentProviderAdapter.RefundObservation(REFUND.toString(),ORDER,PAYMENT_REF,new BigDecimal("50.25"),"INR",PaymentProviderAdapter.RefundStatus.FAILED,null);
        ready();assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.REVIEW_REQUIRED),eq("GATEWAY_REFUND_FAILURE_REVIEW_REQUIRED"),any());
        verifyNoInteractions(successes);assertNoProviderMutation();
    }

    @Test void retainedEarlierSuccessOutranksLaterPendingWithoutReplacingItsOriginalAnchor(){
        priorSuccess=true;fact=new PaymentProviderAdapter.RefundObservation(REFUND.toString(),ORDER,PAYMENT_REF,new BigDecimal("50.25"),"INR",PaymentProviderAdapter.RefundStatus.PENDING,null);
        ready();assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        verify(successes,never()).retain(any(),any(),any());
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.COMPLETE),eq("GATEWAY_REFUND_SUCCESS_ALREADY_RETAINED"),any());
    }

    @Test void contradictorySuccessCommitsReadForReviewAndNeverRecognizesNewMoney(){
        fact=new PaymentProviderAdapter.RefundObservation(REFUND.toString(),ORDER,"synthetic_wrong_payment",new BigDecimal("50.25"),"INR",PaymentProviderAdapter.RefundStatus.SUCCESS,NOW.minusSeconds(1));
        ready();assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        verify(reads).retain(eq(binding),eq(REFUND),any(),same(fact));verifyNoInteractions(successes);
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.REVIEW_REQUIRED),eq("GATEWAY_REFUND_EVIDENCE_REVIEW_REQUIRED"),any());
    }

    @Test void leaseLossAfterGetKeepsTheCommittedWitnessButCannotRecognizeHoldOrFinishWork(){
        expiredAfterRead=true;ready();assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(events).containsSubsequence("GET","RETAIN","COMMIT","BEGIN","CURRENT","ROLLBACK");
        verifyNoInteractions(successes);verify(queue,never()).complete(any(),any(),anyString(),any());
    }

    @Test void failedQueueCompletionRollsBackTheHoldTransactionWithoutRepeatingTheCommittedRead(){
        completeAllowed=false;ready();assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(events).containsSubsequence("RETAIN","COMMIT","BEGIN","HOLD","COMPLETE","ROLLBACK");
        verify(reads,times(1)).retain(any(),any(),any(),any());assertNoProviderMutation();
    }

    @Test void conflictingRetainedSuccessUsesAFreshReviewTransactionAfterHoldRollback(){
        ready();when(successes.retain(eq(binding),eq(REFUND),any())).thenThrow(mock(GatewayRefundSuccessStore.ConflictingSuccessEvidenceException.class));
        assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        assertThat(events).containsSubsequence("RETAIN","COMMIT","BEGIN","CURRENT","ROLLBACK","BEGIN","CURRENT","COMPLETE","COMMIT");
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.REVIEW_REQUIRED),eq("GATEWAY_REFUND_SOURCE_REVIEW_REQUIRED"),any());
        verify(reads,times(1)).retain(any(),any(),any(),any());
    }

    @Test void callbackMustMatchTheApprovedOriginalOrderAndPaymentBeforeAnyGet(){
        ready();var other=new GatewayRefundVerification.Expected(REFUND,binding,PAYMENT,CAPTURE,"synthetic_other_order",PAYMENT_REF,
                expected.captureAmount(),expected.refundAmount(),expected.originalPaidAt(),expected.requestedAt(),expected.approvedAt(),expected.schoolTimeZone());
        when(reads.prepare(binding,REFUND)).thenReturn(new GatewayRefundReadStore.Prepared(other,APPROVAL));
        assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);verifyNoInteractions(discovery,successes);
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.REVIEW_REQUIRED),eq("GATEWAY_REFUND_SOURCE_REVIEW_REQUIRED"),any());
    }

    @Test void unassociatedSourceOrAutoRefundCannotStartAnUnrelatedGet(){
        ready();when(reads.prepare(binding,REFUND)).thenThrow(new IllegalStateException("synthetic missing approval"));
        assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);verifyNoInteractions(discovery,successes);
        var auto=new GatewayInboxDispatch.Admission(Optional.empty(),"AUTO_REFUND_STATUS_WEBHOOK",null,PAYMENT_REF,null,false,NOW);
        assertThat(GatewayRefundInboxRecovery.canRecover(auto)).isFalse();
        var external=new GatewayInboxDispatch.Admission(Optional.empty(),"REFUND_STATUS_WEBHOOK",ORDER,PAYMENT_REF,"synthetic_external_refund",false,NOW);
        assertThat(GatewayRefundInboxRecovery.canRecover(external)).isFalse();
    }

    @Test void exhaustedOriginalLeaseBudgetCannotBeResetAfterAdmission(){
        ready();assertThat(service.recover(provider,ROUTE,claim,admission,System.nanoTime()-Duration.ofSeconds(120).toNanos())).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verifyNoInteractions(discovery,successes);verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.RETRY),eq("GATEWAY_REFUND_READ_BUDGET_UNAVAILABLE"),any());
    }

    @Test void transientApprovedSourceFailureRetriesWithoutMisclassifyingOrReadingProviderMoney(){
        ready();when(reads.prepare(binding,REFUND)).thenThrow(new org.springframework.dao.TransientDataAccessResourceException("synthetic temporary database failure"));
        assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(events).containsSubsequence("BEGIN","CURRENT","ROLLBACK","BEGIN","CURRENT","COMPLETE","COMMIT");
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.RETRY),eq("GATEWAY_REFUND_PREPARATION_UNAVAILABLE"),any());
        verifyNoInteractions(discovery,successes);assertNoProviderMutation();
    }

    @Test void redactedAdmissionFailureRemainsRetryableOnlyAfterFreshCurrentClaimValidation(){
        ready();when(dispatch.loadForReadRecovery(ROUTE,claim)).thenThrow(new IllegalStateException("synthetic redacted admission failure")).thenReturn(admission);
        assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(dispatch,times(2)).loadForReadRecovery(ROUTE,claim);
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.RETRY),eq("GATEWAY_REFUND_PREPARATION_UNAVAILABLE"),any());
        verifyNoInteractions(reads,discovery,successes);assertNoProviderMutation();
    }

    @Test void ambientCallerAuthorityAndCancellationAreNotClearedOrUsedForRecovery(){
        ready();try(var ignored=TenantRequestContext.open(UUID.randomUUID(),ORG)){assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);assertThat(TenantRequestContext.current()).isPresent();}
        Thread.currentThread().interrupt();try{assertThat(run()).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();}
        verifyNoInteractions(discovery,successes);verify(manager,never()).getTransaction(any());
    }

    private GatewayAttemptWorker.Step run(){return service.recover(provider,ROUTE,claim,admission,System.nanoTime());}
    @SuppressWarnings("unchecked") private void ready(){
        when(manager.getTransaction(any())).thenAnswer(call->{assertThat(TenantRequestContext.current().orElseThrow().userId()).isNull();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();TransactionSynchronizationManager.setActualTransactionActive(true);events.add("BEGIN");return new SimpleTransactionStatus();});
        doAnswer(call->{events.add("COMMIT");TransactionSynchronizationManager.clear();return null;}).when(manager).commit(any());
        doAnswer(call->{events.add("ROLLBACK");TransactionSynchronizationManager.clear();return null;}).when(manager).rollback(any());
        when(dispatch.loadForReadRecovery(ROUTE,claim)).thenAnswer(call->{events.add("CURRENT");if(expiredAfterRead && retained.get()!=null)throw new IllegalStateException("synthetic expired lease");return admission;});
        when(dispatch.loadHistorical(ROUTE,claim)).thenAnswer(call->{events.add("HISTORICAL");return admission;});
        when(jdbc.queryForObject(anyString(),any(SqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->call.getArgument(0,String.class).contains("fee_gateway_refund_successes")?priorSuccess:true);
        when(jdbc.queryForObject(eq("SELECT clock_timestamp()"),any(SqlParameterSource.class),any(RowMapper.class))).thenReturn(NOW);
        when(reads.prepare(binding,REFUND)).thenReturn(prepared);
        when(discovery.readRefund(eq(provider),same(prepared),any(),any())).thenAnswer(call->{assertThat(TenantRequestContext.current()).isEmpty();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();events.add("GET");return new GatewayProviderDiscovery.RefundRead(prepared,call.getArgument(2),fact);});
        when(reads.retain(eq(binding),eq(REFUND),any(),any())).thenAnswer(call->{events.add("RETAIN");var observation=new GatewayRefundVerification.Observation(binding,call.getArgument(3),NOW);var value=new GatewayRefundReadStore.Read(call.getArgument(2),expected,observation,GatewayRefundVerification.verify(expected,observation));retained.set(value);return value;});
        when(successes.retain(eq(binding),eq(REFUND),any())).thenAnswer(call->{events.add("HOLD");var value=retained.get();var proof=value.verification().success().orElseThrow();return new GatewayRefundSuccessStore.Success(REFUND,value.id(),APPROVAL,PAYMENT,CAPTURE,UUID.randomUUID(),expected.refundAmount(),"INR",proof.processedAtExact(),proof.processedAtProjection(),proof.processedOn(),"Asia/Kolkata",NOW,null,value.verification().evidenceSha256(),1,"HOLD","AWAITING_REVIEWED_POSTING");});
        when(queue.complete(eq(claim),any(),anyString(),any())).thenAnswer(call->{events.add("COMPLETE");return completeAllowed;});
    }
    private void assertNoProviderMutation(){verify(provider,never()).requestRefund(any());verify(provider,never()).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));verify(provider,never()).cancelCheckout(any());verify(provider,never()).fetchPayment(anyString(),anyString());verify(provider,never()).fetchPayments(anyString());}
}
