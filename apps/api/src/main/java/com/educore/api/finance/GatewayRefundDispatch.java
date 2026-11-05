package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional approved-refund admission only: no credentials or provider I/O.
 * A successful return must commit before a single POST. An ambiguous commit,
 * timeout, crash or existing marker permits same-ID GET recovery only.
 */
@Service
public class GatewayRefundDispatch {
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayProviderRegistry registry;
    private final GatewayRefundReadStore reads;
    private final FeeAccountingService accounting;

    public GatewayRefundDispatch(NamedParameterJdbcTemplate jdbc,GatewayProviderRegistry registry,
            GatewayRefundReadStore reads,FeeAccountingService accounting) {
        this.jdbc=Objects.requireNonNull(jdbc);this.registry=Objects.requireNonNull(registry);
        this.reads=Objects.requireNonNull(reads);this.accounting=Objects.requireNonNull(accounting);
    }

    record Recovery(GatewayRefundReadStore.Prepared approved,Instant checkedAt,boolean previouslyDispatched,boolean providerEvidenceRetained) {
        boolean mustRecover(){return previouslyDispatched || providerEvidenceRetained;}
        @Override public String toString(){return "Recovery[private approved same-ID refund read source]";}
    }
    record Command(GatewayRefundReadStore.Prepared approved,Instant dispatchedAt) {
        PaymentProviderAdapter.RefundRequest request(){
            var source=approved.expected();
            return new PaymentProviderAdapter.RefundRequest(source.refundId(),source.orderReference(),source.paymentReference(),source.refundAmount());
        }
        @Override public String toString(){return "Command[private one-use approved refund admission]";}
    }

    /** Recovery remains possible when NEW refunds are disabled or original books have closed. */
    @Transactional
    Recovery loadForReadRecovery(String routeKey,GatewayWorkQueue.Claim claim) {
        var p=parameters(routeKey,claim,false);
        var approved=reads.prepare(claim.binding(),claim.id());
        if(approved==null || !claim.id().equals(approved.expected().refundId())
                || !claim.binding().equals(approved.expected().binding())) throw unavailable();
        p.addValue("approvalId",approved.approvalDecisionId());
        RecoveryState state=jdbc.query("""
                /* refund-dispatch-current-claim */
                SELECT EXISTS(SELECT 1 FROM fee_gateway_refund_dispatches dispatch WHERE dispatch.refund_request_id=work.refund_request_id
                  AND dispatch.organisation_id=work.organisation_id AND dispatch.school_id=work.school_id) AS dispatched,
                  (EXISTS(SELECT 1 FROM fee_gateway_refund_reads witness WHERE witness.refund_request_id=work.refund_request_id
                    AND witness.organisation_id=work.organisation_id AND witness.school_id=work.school_id)
                   OR EXISTS(SELECT 1 FROM fee_gateway_refund_successes success WHERE success.refund_request_id=work.refund_request_id
                    AND success.organisation_id=work.organisation_id AND success.school_id=work.school_id)) AS observed
                FROM fee_gateway_refund_work work
                WHERE work.refund_request_id=:refundId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.approval_decision_id=:approvalId AND work.payment_provider_config_id=:providerId
                  AND work.provider_code=:providerCode AND work.provider_environment=:environment
                  AND work.merchant_account_reference=:merchant AND work.credentials_secret_reference=:credentials
                  AND work.status='LEASED' AND work.lease_token=:token AND work.lease_started_at=:started
                  AND work.lease_until=:until AND work.attempt_count=:attempt
                FOR SHARE OF work
                """,p,row->{if(!row.next())return null;var value=new RecoveryState(row.getBoolean("dispatched"),row.getBoolean("observed"));if(row.next())throw unavailable();return value;});
        Instant checked=jdbc.queryForObject("SELECT clock_timestamp()",p,(row,n)->row.getTimestamp(1).toInstant());
        if(state==null || checked==null || checked.isBefore(claim.startedAt()) || !checked.isBefore(claim.leaseUntil())) throw unavailable();
        return new Recovery(approved,checked,state.dispatched(),state.observed());
    }

    /**
     * Exact one-use marker is the last write; it and source protection commit
     * together. No caller may retry POST after an uncertain admission commit.
     */
    @Transactional
    Command admit(String routeKey,GatewayWorkQueue.Claim claim) {
        var p=parameters(routeKey,claim,true);
        var recovered=loadForReadRecovery(routeKey,claim);
        if(recovered.mustRecover() || claim.createDispatched()) throw unavailable();
        var approved=recovered.approved();
        var proof=accounting.verifyGatewayOriginalPaymentPosting(claim.binding().organisationId(),claim.binding().schoolId(),approved.expected().paymentId());
        p.addValue("approvalId",approved.approvalDecisionId()).addValue("itemId",proof.postingItemId())
                .addValue("batchId",proof.postingBatchId()).addValue("journalId",proof.accountingJournalId())
                .addValue("configurationId",proof.configurationId()).addValue("sourceHash",proof.sourceSha256())
                .addValue("batchHash",proof.batchEvidenceSha256());
        Instant admitted=jdbc.query("""
                INSERT INTO fee_gateway_refund_dispatches(refund_request_id,organisation_id,school_id,approval_decision_id,
                  lease_token,lease_started_at,lease_until,attempt_number,original_payment_posting_item_id,
                  original_payment_posting_batch_id,original_payment_journal_id,original_configuration_id,source_sha256,batch_evidence_sha256)
                VALUES(:refundId,:organisationId,:schoolId,:approvalId,:token,:started,:until,:attempt,:itemId,:batchId,:journalId,:configurationId,:sourceHash,:batchHash)
                ON CONFLICT(refund_request_id) DO NOTHING RETURNING admitted_at
                """,p,row->{if(!row.next())return null;Instant value=row.getTimestamp(1).toInstant();if(row.next())throw unavailable();return value;});
        if(admitted==null || admitted.isBefore(claim.startedAt()) || !admitted.isBefore(claim.leaseUntil())) throw unavailable();
        return new Command(approved,admitted);
    }

    private MapSqlParameterSource parameters(String routeKey,GatewayWorkQueue.Claim claim,boolean mutation) {
        if(!registry.workerEnabled() || claim==null || claim.kind()!=GatewayWorkQueue.WorkKind.REFUND
                || (mutation && (!registry.refundsEnabled() || claim.createDispatched()))) throw unavailable();
        var route=registry.findRoute(routeKey).orElseThrow(GatewayRefundDispatch::unavailable);
        if(route.environment()==CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) throw unavailable();
        var binding=new GatewayWorkQueue.ProviderBinding(route.organisationId(),route.schoolId(),route.providerConfigId(),route.providerCode(),
                route.environment().name(),route.merchantAccountReference(),route.credentialsSecretReference());
        var context=TenantRequestContext.current().orElse(null);
        if(!binding.equals(claim.binding()) || context==null || context.userId()!=null || !binding.organisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
        var p=new MapSqlParameterSource().addValue("organisationId",binding.organisationId()).addValue("schoolId",binding.schoolId())
                .addValue("refundId",claim.id()).addValue("providerId",binding.providerConfigId()).addValue("providerCode",binding.providerCode())
                .addValue("environment",binding.environment()).addValue("merchant",binding.merchantReference()).addValue("credentials",binding.credentialsReference())
                .addValue("token",claim.token()).addValue("started",Timestamp.from(claim.startedAt())).addValue("until",Timestamp.from(claim.leaseUntil()))
                .addValue("attempt",claim.attemptNumber());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                  AND current_setting('transaction_isolation')='read committed' AND current_setting('transaction_read_only')='off'
                """,p,Boolean.class))) throw unavailable();
        return p;
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("Refund dispatch requires exact trusted approved source and unused current background claim");}
    private record RecoveryState(boolean dispatched,boolean observed){}
}
