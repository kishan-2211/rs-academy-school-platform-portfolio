package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Standard authenticated refund callbacks wake independent same-ID GETs only.
 * Read retention commits before claim-fenced HOLD recognition. No provider POST,
 * fee/account/receipt/GL mutation, budget release or additional scheduler exists.
 */
@Service
public final class GatewayRefundInboxRecovery {
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayInboxDispatch dispatch;
    private final GatewayWorkQueue queue;
    private final GatewayRefundReadStore reads;
    private final GatewayRefundSuccessStore successes;
    private final GatewayProviderDiscovery discovery;
    private final TransactionTemplate transactions;

    public GatewayRefundInboxRecovery(NamedParameterJdbcTemplate jdbc, GatewayInboxDispatch dispatch,
            GatewayWorkQueue queue, GatewayRefundReadStore reads, GatewayRefundSuccessStore successes,
            GatewayProviderDiscovery discovery, PlatformTransactionManager manager) {
        this.jdbc=Objects.requireNonNull(jdbc);this.dispatch=Objects.requireNonNull(dispatch);
        this.queue=Objects.requireNonNull(queue);this.reads=Objects.requireNonNull(reads);
        this.successes=Objects.requireNonNull(successes);this.discovery=Objects.requireNonNull(discovery);
        transactions=new TransactionTemplate(Objects.requireNonNull(manager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15);transactions.setReadOnly(false);
    }

    /** Routing hint only. Actual persisted lease/source/approval are revalidated in prepare. */
    static boolean canRecover(GatewayInboxDispatch.Admission admission) {
        if(admission==null || !"REFUND_STATUS_WEBHOOK".equals(admission.eventType())
                || admission.orderReference()==null || admission.refundReference()==null) return false;
        try{return UUID.fromString(admission.refundReference()).toString().equals(admission.refundReference());}
        catch(IllegalArgumentException invalid){return false;}
    }

    GatewayAttemptWorker.Step recover(PaymentProviderAdapter provider, String routeKey, GatewayWorkQueue.Claim claim,
            GatewayInboxDispatch.Admission original, long nanoBeforeAdmission) {
        requireOutsidePersistence();
        if(provider==null || claim==null || claim.kind()!=GatewayWorkQueue.WorkKind.WEBHOOK || !canRecover(original)) throw unavailable();
        if(Thread.currentThread().isInterrupted()) return GatewayAttemptWorker.Step.FENCED_OUT;
        UUID refundId=UUID.fromString(original.refundReference());
        GatewayRefundReadStore.Prepared prepared;
        try(var ignored=TenantRequestContext.open(null,claim.binding().organisationId())) {
            prepared=transactions.execute(status->{
                current(routeKey,claim,original);
                GatewayRefundReadStore.Prepared approved;
                try{approved=reads.prepare(claim.binding(),refundId);}
                catch(IllegalStateException invalidSource){throw new SourceReviewRequired();}
                requireAssociation(approved,claim,original);
                return approved;
            });
        }catch(SourceReviewRequired invalidSource){
            return finish(routeKey,claim,original,null,"GATEWAY_REFUND_SOURCE_REVIEW_REQUIRED");
        }catch(RuntimeException unavailable){
            // Admission may deliberately redact an underlying database failure.
            // Unknown infrastructure is retryable, not proof of invalid terms.
            return finish(routeKey,claim,original,null,"GATEWAY_REFUND_PREPARATION_UNAVAILABLE");
        }
        if(prepared==null) return GatewayAttemptWorker.Step.FENCED_OUT;
        Duration budget=GatewayAttemptWorker.available(claim.leaseUntil(),original.checkedAt(),nanoBeforeAdmission,
                Duration.ofSeconds(45),Duration.ofSeconds(40));
        if(!budget.isPositive()) return finish(routeKey,claim,original,null,"GATEWAY_REFUND_READ_BUDGET_UNAVAILABLE");
        UUID readId=UUID.randomUUID();
        GatewayProviderDiscovery.RefundRead observed;
        try{observed=discovery.readRefund(provider,prepared,readId,budget);}
        catch(RuntimeException unknown){return finish(routeKey,claim,original,null,"GATEWAY_REFUND_READ_OUTCOME_UNKNOWN");}
        if(observed==null || observed.prepared()!=prepared || !readId.equals(observed.readId())) return GatewayAttemptWorker.Step.FENCED_OUT;

        GatewayRefundReadStore.Read retained;
        try(var ignored=TenantRequestContext.open(null,claim.binding().organisationId())) {
            // Do not require the current lease here: genuinely observed money
            // must survive expiry/shutdown. Historical routing still must match.
            retained=transactions.execute(status->{
                var historical=dispatch.loadHistorical(routeKey,claim);
                requireSameRouting(original,historical);
                return reads.retain(claim.binding(),refundId,readId,observed.facts());
            });
        }catch(RuntimeException unknownCommit){return GatewayAttemptWorker.Step.FENCED_OUT;}
        if(retained==null || !readId.equals(retained.id()) || !prepared.expected().equals(retained.expected())) return GatewayAttemptWorker.Step.FENCED_OUT;
        // Retention is already committed, so a stale claim or failed HOLD cannot
        // erase the independent provider read. Completion is a separate TX.
        return finish(routeKey,claim,original,retained,null);
    }

    private GatewayAttemptWorker.Step finish(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayInboxDispatch.Admission original, GatewayRefundReadStore.Read retained, String issue) {
        if(Thread.currentThread().isInterrupted()) return GatewayAttemptWorker.Step.FENCED_OUT;
        try(var ignored=TenantRequestContext.open(null,claim.binding().organisationId())) {
            return Objects.requireNonNull(transactions.execute(status->{
                current(routeKey,claim,original);
                GatewayWorkQueue.Outcome outcome;
                String code;
                if(retained==null){
                    boolean sourceReview="GATEWAY_REFUND_SOURCE_REVIEW_REQUIRED".equals(issue);
                    outcome=sourceReview?GatewayWorkQueue.Outcome.REVIEW_REQUIRED:GatewayWorkQueue.Outcome.RETRY;
                    code=issue;
                }else{
                    var verification=retained.verification().outcome();
                    if(verification==GatewayRefundVerification.Outcome.REVIEW_REQUIRED){
                        outcome=GatewayWorkQueue.Outcome.REVIEW_REQUIRED;code="GATEWAY_REFUND_EVIDENCE_REVIEW_REQUIRED";
                    }else if(verification==GatewayRefundVerification.Outcome.VERIFIED_SUCCESS){
                        var success=Objects.requireNonNull(successes.retain(claim.binding(),retained.expected().refundId(),retained.id()));
                        if(!success.refundId().equals(retained.expected().refundId())
                                || !success.approvalDecisionId().equals(preparedApproval(claim,retained))
                                || !success.paymentId().equals(retained.expected().paymentId()) || !success.captureId().equals(retained.expected().captureId())
                                || success.amount().compareTo(retained.expected().refundAmount())!=0 || !"INR".equals(success.currencyCode())
                                || !"HOLD".equals(success.status()) || !success.evidenceSha256().equals(retained.verification().evidenceSha256())) throw unavailable();
                        outcome=GatewayWorkQueue.Outcome.COMPLETE;code="GATEWAY_REFUND_VERIFIED_AWAITING_POSTING";
                    }else if(hasRetainedSuccess(claim,retained.expected().refundId())){
                        // Earlier immutable success outranks later pending,
                        // unsuccessful or unavailable GETs; never erase it.
                        outcome=GatewayWorkQueue.Outcome.COMPLETE;code="GATEWAY_REFUND_SUCCESS_ALREADY_RETAINED";
                    }else{
                        var fact=retained.observation().refund();
                        boolean terminal=fact!=null && (fact.status()==PaymentProviderAdapter.RefundStatus.FAILED
                                || fact.status()==PaymentProviderAdapter.RefundStatus.CANCELLED);
                        outcome=terminal?GatewayWorkQueue.Outcome.REVIEW_REQUIRED:GatewayWorkQueue.Outcome.RETRY;
                        code=terminal?"GATEWAY_REFUND_FAILURE_REVIEW_REQUIRED":"GATEWAY_REFUND_CONFIRMATION_PENDING";
                    }
                }
                if(Thread.currentThread().isInterrupted() || !queue.complete(claim,outcome,code,
                        Duration.ofSeconds(GatewayWorkQueue.retryDelay(claim.attemptNumber(),claim.token())))) throw unavailable();
                return step(outcome); // Last write; failed completion rolls back a new HOLD.
            }));
        }catch(GatewayRefundSuccessStore.ConflictingSuccessEvidenceException conflict){
            // The nested HOLD transaction may be rollback-only. Record review
            // only in a fresh, independently revalidated transaction.
            return finish(routeKey,claim,original,null,"GATEWAY_REFUND_SOURCE_REVIEW_REQUIRED");
        }catch(RuntimeException expiredOrChanged){return GatewayAttemptWorker.Step.FENCED_OUT;}
    }

    private GatewayInboxDispatch.Admission current(String routeKey,GatewayWorkQueue.Claim claim,
            GatewayInboxDispatch.Admission original){
        var current=dispatch.loadForReadRecovery(routeKey,claim);
        requireSameRouting(original,current);
        var p=params(claim);
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL",p,Boolean.class))) throw unavailable();
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",p,(row,n)->row.getObject(1,OffsetDateTime.class).toInstant());
        if(now==null || now.isBefore(claim.startedAt()) || !now.isBefore(claim.leaseUntil())) throw unavailable();
        return current;
    }

    private boolean hasRetainedSuccess(GatewayWorkQueue.Claim claim,UUID refundId){
        var b=claim.binding();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_refund_successes
                  WHERE refund_request_id=:refundId AND organisation_id=:organisationId AND school_id=:schoolId
                    AND payment_provider_config_id=:providerId AND provider_code=:providerCode
                    AND provider_environment=:environment AND merchant_account_reference=:merchant
                    AND credentials_secret_reference=:credentials AND status='HOLD')
                """,params(claim).addValue("refundId",refundId).addValue("providerId",b.providerConfigId())
                .addValue("providerCode",b.providerCode()).addValue("environment",b.environment())
                .addValue("merchant",b.merchantReference()).addValue("credentials",b.credentialsReference()),Boolean.class));
    }

    private UUID preparedApproval(GatewayWorkQueue.Claim claim,GatewayRefundReadStore.Read retained){
        var approved=reads.prepare(claim.binding(),retained.expected().refundId());
        if(!retained.expected().equals(approved.expected())) throw unavailable();
        return approved.approvalDecisionId();
    }

    private static void requireAssociation(GatewayRefundReadStore.Prepared prepared,GatewayWorkQueue.Claim claim,GatewayInboxDispatch.Admission admission){
        if(prepared==null || !claim.binding().equals(prepared.expected().binding())
                || !prepared.expected().refundId().toString().equals(admission.refundReference())
                || !prepared.expected().orderReference().equals(admission.orderReference())
                || !prepared.expected().paymentReference().equals(admission.paymentReference())) throw new SourceReviewRequired();
    }
    private static void requireSameRouting(GatewayInboxDispatch.Admission expected,GatewayInboxDispatch.Admission actual){
        if(!canRecover(actual) || !expected.eventType().equals(actual.eventType())
                || !expected.refundReference().equals(actual.refundReference()) || !expected.orderReference().equals(actual.orderReference())
                || !expected.paymentReference().equals(actual.paymentReference()) || actual.attempt().isPresent() || actual.financiallyEnabled()) throw unavailable();
    }
    private static MapSqlParameterSource params(GatewayWorkQueue.Claim claim){return new MapSqlParameterSource().addValue("organisationId",claim.binding().organisationId()).addValue("schoolId",claim.binding().schoolId());}
    private static GatewayAttemptWorker.Step step(GatewayWorkQueue.Outcome outcome){return switch(outcome){case COMPLETE->GatewayAttemptWorker.Step.COMPLETE;case REVIEW_REQUIRED->GatewayAttemptWorker.Step.REVIEW_REQUIRED;case RETRY->GatewayAttemptWorker.Step.RETRY;};}
    private static void requireOutsidePersistence(){if(TenantRequestContext.current().isPresent() || TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();}
    private static final class SourceReviewRequired extends RuntimeException {
        SourceReviewRequired(){super("Approved refund source requires review");}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("Refund inbox recovery requires exact approved source and current background claim");}
}
