package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stubbed GET protocol only; no database, merchant, provider POST or financial acceptance. */
class GatewayRefundDiscoveryTest {
    private static final UUID REFUND=UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID READ=UUID.fromString("82000000-0000-4000-8000-000000000002");
    private static final UUID ORG=UUID.fromString("82000000-0000-4000-8000-000000000003");
    private static final Instant NOW=Instant.parse("2026-10-21T07:00:00.987654321Z");
    private static final String ORDER="synthetic_refund_order",PAYMENT="synthetic_refund_payment";
    private static final Duration BUDGET=Duration.ofSeconds(2);
    private final GatewayProviderDiscovery service=new GatewayProviderDiscovery(Clock.fixed(NOW,ZoneOffset.UTC));

    @AfterEach void clearOnlySyntheticMarkers() {
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test void sameIdGetPreservesTheApprovedSourceReadIdentityAndExactOriginalNanos() {
        var provider=provider();var prepared=prepared();var fact=fact(PaymentProviderAdapter.RefundStatus.SUCCESS);
        when(provider.fetchRefund(ORDER,REFUND.toString())).thenAnswer(call->{outsidePersistence();return fact;});
        var read=service.readRefund(provider,prepared,READ,BUDGET);
        assertThat(read.prepared()).isSameAs(prepared);assertThat(read.readId()).isEqualTo(READ);
        assertThat(read.facts()).isSameAs(fact);assertThat(read.facts().processedAt().getNano()).isEqualTo(123456789);
        verify(provider,times(1)).fetchRefund(ORDER,REFUND.toString());assertGetOnly(provider);
    }

    @Test void pendingFailureCancellationAndHoldRemainActualObservationsNotReleasedBudget() {
        for(var state:PaymentProviderAdapter.RefundStatus.values()){
            var provider=provider();var fact=fact(state);when(provider.fetchRefund(ORDER,REFUND.toString())).thenReturn(fact);
            var read=service.readRefund(provider,prepared(),READ,BUDGET);assertThat(read.facts()).isSameAs(fact);
            var classified=GatewayRefundVerification.verify(read.prepared().expected(),new GatewayRefundVerification.Observation(read.prepared().expected().binding(),read.facts(),NOW));
            assertThat(classified.outcome()).isEqualTo(state==PaymentProviderAdapter.RefundStatus.SUCCESS?GatewayRefundVerification.Outcome.VERIFIED_SUCCESS:GatewayRefundVerification.Outcome.NOT_SUCCESSFUL);
            assertGetOnly(provider);
        }
    }

    @Test void contradictorySuccessfulFactsAreRetainedForReviewRatherThanHiddenAsUnknown() {
        var provider=provider();var fact=new PaymentProviderAdapter.RefundObservation(REFUND.toString(),ORDER,"synthetic_other_payment",new BigDecimal("50.25"),"INR",PaymentProviderAdapter.RefundStatus.SUCCESS,NOW.minusSeconds(1));
        when(provider.fetchRefund(ORDER,REFUND.toString())).thenReturn(fact);
        var read=service.readRefund(provider,prepared(),READ,BUDGET);assertThat(read.facts()).isSameAs(fact);
        assertThat(GatewayRefundVerification.verify(read.prepared().expected(),new GatewayRefundVerification.Observation(read.prepared().expected().binding(),read.facts(),NOW)).outcome()).isEqualTo(GatewayRefundVerification.Outcome.REVIEW_REQUIRED);
        assertGetOnly(provider);
    }

    @Test void unavailableOrNullResponseIsUnknownWithoutInternalRetryOrPrivateExceptionLeakage() {
        for(boolean fail:new boolean[]{false,true}){
            var provider=provider();
            if(fail)when(provider.fetchRefund(ORDER,REFUND.toString())).thenThrow(new IllegalStateException("synthetic_private_provider_body"));
            var read=service.readRefund(provider,prepared(),READ,BUDGET);assertThat(read.facts()).isNull();
            assertThat(read.toString()).doesNotContain("synthetic_private_provider_body",ORDER,PAYMENT,REFUND.toString());
            verify(provider,times(1)).fetchRefund(ORDER,REFUND.toString());assertGetOnly(provider);
        }
    }

    @Test void wrongProviderOrFutureApprovalFailsBeforeGetWithoutSensitiveCause() {
        for(String code:Arrays.asList(null,"RAZORPAY","cashfree")){
            var provider=provider();when(provider.providerCode()).thenReturn(code);
            assertThatThrownBy(()->service.readRefund(provider,prepared(),READ,BUDGET)).isInstanceOf(IllegalStateException.class).hasNoCause();
            verify(provider,never()).fetchRefund(anyString(),anyString());assertGetOnly(provider);
        }
        var provider=provider();var future=new GatewayProviderDiscovery(Clock.fixed(NOW.minusSeconds(300),ZoneOffset.UTC));
        assertThatThrownBy(()->future.readRefund(provider,prepared(),READ,BUDGET)).isInstanceOf(IllegalStateException.class).hasNoCause();
        verify(provider,never()).fetchRefund(anyString(),anyString());
    }

    @Test void missingIdentityOrUnboundedBudgetCannotIssueAnyGet() {
        var provider=provider();
        for(Duration budget:Arrays.asList(null,Duration.ZERO,Duration.ofNanos(-1),Duration.ofSeconds(60).plusNanos(1)))
            assertThatThrownBy(()->service.readRefund(provider,prepared(),READ,budget)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.readRefund(null,prepared(),READ,BUDGET)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.readRefund(provider,null,READ,BUDGET)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.readRefund(provider,prepared(),null,BUDGET)).isInstanceOf(IllegalStateException.class);
        verify(provider,never()).fetchRefund(anyString(),anyString());assertGetOnly(provider);
    }

    @Test void humanSystemTransactionSynchronizationAndResourceContextsAreRejectedWithoutClearingThem() {
        var provider=provider();
        for(UUID user:Arrays.asList(null,UUID.randomUUID()))try(var ignored=TenantRequestContext.open(user,ORG)){
            denied(provider);assertThat(TenantRequestContext.current()).isPresent();
        }
        TransactionSynchronizationManager.setActualTransactionActive(true);denied(provider);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization();denied(provider);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();TransactionSynchronizationManager.clearSynchronization();
        Object key=new Object(),value=new Object();TransactionSynchronizationManager.bindResource(key,value);
        try{denied(provider);assertThat(TransactionSynchronizationManager.getResource(key)).isSameAs(value);}
        finally{TransactionSynchronizationManager.unbindResource(key);}
        verify(provider,never()).fetchRefund(anyString(),anyString());assertGetOnly(provider);
    }

    @Test void alreadyInterruptedCallerMakesNoGetAndKeepsTheCancellationSignal() {
        var provider=provider();Thread.currentThread().interrupt();
        try{assertThat(service.readRefund(provider,prepared(),READ,BUDGET).facts()).isNull();assertThat(Thread.currentThread().isInterrupted()).isTrue();}
        finally{Thread.interrupted();}
        verify(provider,never()).fetchRefund(anyString(),anyString());assertGetOnly(provider);
    }

    @Test void timeoutReturnsUnknownAndCancelsTheProviderWithoutWaitingForItsCompletion() throws Exception {
        var provider=provider();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(provider.fetchRefund(ORDER,REFUND.toString())).thenAnswer(call->{outsidePersistence();entered.countDown();try{release.await(5,TimeUnit.SECONDS);return fact(PaymentProviderAdapter.RefundStatus.SUCCESS);}catch(InterruptedException interrupted){cancelled.countDown();Thread.currentThread().interrupt();throw new IllegalStateException("synthetic cancelled provider");}});
        long start=System.nanoTime();
        try{
            var read=service.readRefund(provider,prepared(),READ,Duration.ofMillis(300));
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(read.facts()).isNull();
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            assertThat(cancelled.await(2,TimeUnit.SECONDS)).isTrue();assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }finally{release.countDown();}
        verify(provider,times(1)).fetchRefund(ORDER,REFUND.toString());assertGetOnly(provider);
    }

    @Test void callerInterruptionIsPreservedWithoutAnyAutomaticRetryOrPost() throws Exception {
        var provider=provider();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        var read=new AtomicReference<GatewayProviderDiscovery.RefundRead>();var failure=new AtomicReference<Throwable>();var interruptKept=new AtomicBoolean();
        when(provider.fetchRefund(ORDER,REFUND.toString())).thenAnswer(call->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);return fact(PaymentProviderAdapter.RefundStatus.SUCCESS);}catch(InterruptedException interrupted){cancelled.countDown();Thread.currentThread().interrupt();throw new IllegalStateException("synthetic interrupted provider");}});
        Thread caller=Thread.ofVirtual().unstarted(()->{try{read.set(service.readRefund(provider,prepared(),READ,BUDGET));interruptKept.set(Thread.currentThread().isInterrupted());}catch(Throwable problem){failure.set(problem);}});
        try{caller.start();assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();caller.interrupt();caller.join(Duration.ofSeconds(3));assertThat(caller.isAlive()).isFalse();assertThat(failure.get()).isNull();assertThat(read.get().facts()).isNull();assertThat(interruptKept).isTrue();assertThat(cancelled.await(2,TimeUnit.SECONDS)).isTrue();}
        finally{release.countDown();caller.interrupt();caller.join(Duration.ofSeconds(3));}
        verify(provider,times(1)).fetchRefund(ORDER,REFUND.toString());assertGetOnly(provider);
    }

    private void denied(PaymentProviderAdapter provider){assertThatThrownBy(()->service.readRefund(provider,prepared(),READ,BUDGET)).isInstanceOf(IllegalStateException.class);}
    private static void outsidePersistence(){assertThat(TenantRequestContext.current()).isEmpty();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();}
    private static PaymentProviderAdapter provider(){var provider=mock(PaymentProviderAdapter.class);when(provider.providerCode()).thenReturn("CASHFREE");return provider;}
    private static GatewayRefundReadStore.Prepared prepared(){
        var binding=new GatewayWorkQueue.ProviderBinding(ORG,UUID.randomUUID(),UUID.randomUUID(),"CASHFREE","TEST","synthetic_refund_merchant","SYNTHETIC_SECRET_LABEL");
        return new GatewayRefundReadStore.Prepared(new GatewayRefundVerification.Expected(REFUND,binding,UUID.randomUUID(),UUID.randomUUID(),ORDER,PAYMENT,new BigDecimal("200.00"),new BigDecimal("50.25"),NOW.minusSeconds(180),NOW.minusSeconds(120),NOW.minusSeconds(60),"Asia/Kolkata"),UUID.randomUUID());
    }
    private static PaymentProviderAdapter.RefundObservation fact(PaymentProviderAdapter.RefundStatus state){return new PaymentProviderAdapter.RefundObservation(REFUND.toString(),ORDER,PAYMENT,new BigDecimal("50.25"),"INR",state,Instant.parse("2026-10-21T06:59:59.123456789Z"));}
    private static void assertGetOnly(PaymentProviderAdapter provider){verify(provider,never()).requestRefund(any());verify(provider,never()).cancelCheckout(any());verify(provider,never()).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));verify(provider,never()).lookupHostedCheckout(anyString());verify(provider,never()).fetchPayments(anyString());verify(provider,never()).fetchPayment(anyString(),anyString());verify(provider,never()).verifyWebhook(any(),anyString(),anyString());}
}
