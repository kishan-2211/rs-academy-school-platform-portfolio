package com.educore.api.communication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PushDeliveryProcessorTest {
    private final UUID org=UUID.randomUUID(),school=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-09-09T09:00:00Z");
    private final PushDeliveryQueue queue=mock(PushDeliveryQueue.class);
    private final PushDeliveryPreparation preparation=mock(PushDeliveryPreparation.class);
    private final PushDeliveryCompletion completion=mock(PushDeliveryCompletion.class);
    private final PushTransport transport=mock(PushTransport.class);
    private final WebPushDeliveryConfiguration configuration=mock(WebPushDeliveryConfiguration.class);
    private final PushDeliveryProcessor processor=new PushDeliveryProcessor(queue,preparation,completion,transport,
            configuration,Clock.fixed(now,ZoneOffset.UTC));
    private final PushDeliveryQueue.Claim claim=new PushDeliveryQueue.Claim(org,school,UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),now,now.plusSeconds(90),now.plusSeconds(3600),1);
    private final PushTransport.Message message=new PushTransport.Message("https://push.example.org/synthetic","synthetic",
            "synthetic","generic","synthetic",false,now.plusSeconds(3600));

    @Test
    void disabledProcessingDoesNotConsumeLeasesAndRequiresScopeOutsideTransactions() {
        assertThatThrownBy(() -> processor.processNext(org,school)).hasMessageContaining("organisation context");
        try(var ignored=TenantRequestContext.open(null,org)) {
            assertThat(processor.processNext(org,school)).isNull();
            verifyNoInteractions(queue,preparation,completion,transport);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> processor.processNext(org,school)).hasMessageContaining("database transaction");
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
    }

    @Test
    void sendsOnlyPreparedContentAndAtomicallyCompletesWithTheObservedDeviceVersion() {
        enable();
        when(preparation.prepare(claim)).thenReturn(ready());
        var result=new PushTransport.Result(PushTransport.Outcome.SUBSCRIPTION_EXPIRED,410,"PUSH_EXPIRED",60);
        when(transport.send(message)).thenReturn(result);
        try(var ignored=TenantRequestContext.open(null,org)) { assertThat(processor.processNext(org,school)).isEqualTo(claim.jobId()); }
        var ordered=inOrder(queue,preparation,transport,completion);
        ordered.verify(queue).claimNext(org,school);
        ordered.verify(preparation).prepare(claim);
        ordered.verify(transport).send(message);
        ordered.verify(completion).complete(claim,result,7);
    }

    @Test
    void cancelledAndDeferredMessagesNeverContactProviders() {
        enable();
        when(preparation.prepare(claim)).thenReturn(new PushDeliveryPreparation.Prepared(
                PushDeliveryPreparation.Action.CANCEL,"PUSH_CONSENT_WITHDRAWN",0,null,-1));
        try(var ignored=TenantRequestContext.open(null,org)) { processor.processNext(org,school); }
        verify(queue).cancel(claim,"PUSH_CONSENT_WITHDRAWN");
        when(preparation.prepare(claim)).thenReturn(new PushDeliveryPreparation.Prepared(
                PushDeliveryPreparation.Action.DEFER,"PUSH_QUIET_HOURS",1800,null,-1));
        try(var ignored=TenantRequestContext.open(null,org)) { processor.processNext(org,school); }
        verify(queue).complete(claim,new PushTransport.Result(PushTransport.Outcome.DISABLED,null,"PUSH_QUIET_HOURS",1800));
        verifyNoInteractions(transport,completion);
    }

    @Test
    void insufficientLeaseHeadroomDefersWithoutSending() {
        enable();
        when(configuration.requestTimeout()).thenReturn(Duration.ofSeconds(85));
        when(preparation.prepare(claim)).thenReturn(ready());
        try(var ignored=TenantRequestContext.open(null,org)) { processor.processNext(org,school); }
        verify(queue).complete(claim,new PushTransport.Result(PushTransport.Outcome.DISABLED,null,"PUSH_LEASE_HEADROOM_INSUFFICIENT",60));
        verifyNoInteractions(transport,completion);
    }

    @Test
    void unexpectedTransportFailureRetainsTheLeaseWithoutLeakingOrFabricatingAnOutcome() {
        enable();
        when(preparation.prepare(claim)).thenReturn(ready());
        when(transport.send(message)).thenThrow(new IllegalStateException("Synthetic sensitive provider data"));
        try(var ignored=TenantRequestContext.open(null,org)) {
            assertThatThrownBy(() -> processor.processNext(org,school))
                    .isInstanceOf(PushDeliveryProcessor.ProcessingException.class).hasNoCause()
                    .hasMessageNotContaining("sensitive");
        }
        verifyNoInteractions(completion);
        verify(queue,never()).complete(any(),any());
        verify(queue,never()).cancel(any(),anyString());
    }

    private void enable() {
        when(configuration.enabled()).thenReturn(true);
        when(configuration.requestTimeout()).thenReturn(Duration.ofSeconds(10));
        when(queue.claimNext(org,school)).thenReturn(claim);
    }
    private PushDeliveryPreparation.Prepared ready() {
        return new PushDeliveryPreparation.Prepared(PushDeliveryPreparation.Action.SEND,"PUSH_READY",0,message,7);
    }
}
