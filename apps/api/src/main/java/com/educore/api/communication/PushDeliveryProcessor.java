package com.educore.api.communication;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One bounded, school-scoped attempt. A scheduler must not wrap this network call in a transaction. */
@Service
public class PushDeliveryProcessor {
    private final PushDeliveryQueue queue;
    private final PushDeliveryPreparation preparation;
    private final PushDeliveryCompletion completion;
    private final PushTransport transport;
    private final WebPushDeliveryConfiguration configuration;
    private final Clock clock;

    public PushDeliveryProcessor(PushDeliveryQueue queue, PushDeliveryPreparation preparation,
            PushDeliveryCompletion completion, PushTransport transport, WebPushDeliveryConfiguration configuration, Clock clock) {
        this.queue=queue;
        this.preparation=preparation;
        this.completion=completion;
        this.transport=transport;
        this.configuration=configuration;
        this.clock=clock;
    }

    public UUID processNext(UUID organisationId, UUID schoolId) {
        if (!TenantRequestContext.current().map(scope -> organisationId.equals(scope.organisationId())).orElse(false))
            throw new IllegalStateException("Push processing requires a matching organisation context");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Push network processing cannot run inside a database transaction");
        if (!configuration.enabled()) return null;
        queue.recoverExpiredLease(organisationId,schoolId);
        queue.expirePending(organisationId,schoolId);
        var claim=queue.claimNext(organisationId,schoolId);
        if (claim==null) return null;
        try {
            var prepared=preparation.prepare(claim);
            switch(prepared.action()) {
                case CANCEL -> queue.cancel(claim,prepared.safeCode());
                case DEFER -> queue.complete(claim,new PushTransport.Result(PushTransport.Outcome.DISABLED,
                        null,prepared.safeCode(),prepared.retryAfterSeconds()));
                case SEND -> {
                    // Leave headroom for fenced result persistence after the bounded provider call.
                    if (!clock.instant().plus(configuration.requestTimeout()).plusSeconds(5).isBefore(claim.leaseUntil())) {
                        queue.complete(claim,new PushTransport.Result(PushTransport.Outcome.DISABLED,
                                null,"PUSH_LEASE_HEADROOM_INSUFFICIENT",60));
                    } else {
                        var result=transport.send(prepared.message());
                        completion.complete(claim,result,prepared.subscriptionVersion());
                    }
                }
            }
            return claim.jobId();
        } catch (RuntimeException unknownOutcome) {
            // Leave the lease for explicit unknown-outcome recovery; never invent provider failure.
            // The original cause can contain a URL, payload or key and must not escape to scheduler logs.
            throw new ProcessingException(claim.jobId());
        }
    }

    public static final class ProcessingException extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final UUID jobId;
        ProcessingException(UUID jobId) {
            super("Push processing interrupted; fenced lease recovery is required");
            this.jobId=jobId;
        }
        public UUID jobId() { return jobId; }
    }
}
