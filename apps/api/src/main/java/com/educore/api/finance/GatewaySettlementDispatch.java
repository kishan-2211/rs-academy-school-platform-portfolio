package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Short report transactions only. A selected page is not a bank receipt, payment or journal. */
@Service
public class GatewaySettlementDispatch {
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayProviderRegistry registry;
    private final GatewaySettlementReadStore reads;
    private final GatewayWorkQueue queue;
    public GatewaySettlementDispatch(NamedParameterJdbcTemplate jdbc,GatewayProviderRegistry registry,GatewaySettlementReadStore reads,GatewayWorkQueue queue){
        this.jdbc=Objects.requireNonNull(jdbc);this.registry=Objects.requireNonNull(registry);this.reads=Objects.requireNonNull(reads);this.queue=Objects.requireNonNull(queue);
    }
    record Admitted(UUID requestId,GatewaySettlementReadStore.Prepared query,Instant checkedAt){
        @Override public String toString(){return "Admitted[private exact bounded report request and claim]";}}
    static final class SourceUnavailable extends IllegalStateException {
        private static final long serialVersionUID=1L;
        SourceUnavailable(){super("Settlement request source requires authorized review");}
    }

    @Transactional
    Admitted prepare(String routeKey,GatewayWorkQueue.Claim claim){
        var p=parameters(routeKey,claim);var source=current(p,claim,true);
        GatewaySettlementReadStore.Prepared prepared;
        if(claim.id().equals(source.requestId())){
            prepared=source.kind().equals("SUMMARY")?reads.prepareSummaries(claim.binding(),claim.id(),source.from(),source.until()):
                    reads.prepareEvents(claim.binding(),claim.id(),source.summaryReadId(),source.reference());
        }else prepared=reads.load(claim.binding(),claim.id());
        requirePrepared(prepared,source,claim);
        // Preparation/source locks may wait. Budget authority uses actual DB time after all of them.
        var checked=current(p,claim,true);
        return new Admitted(source.requestId(),prepared,checked.checkedAt());
    }

    @Transactional
    GatewayAttemptWorker.Step finish(String routeKey,GatewayWorkQueue.Claim claim,GatewaySettlementReadStore.Read observed){
        var p=parameters(routeKey,claim);var source=current(p,claim,true);
        if(observed==null||!claim.id().equals(observed.query().id())||!claim.binding().equals(observed.query().binding()))throw unavailable();
        var retained=reads.read(claim.binding(),observed.id());requirePrepared(retained.query(),source,claim);
        if(!retained.equals(observed)||retained.observedAt().isBefore(claim.startedAt()))throw unavailable();
        if(!retained.complete())return complete(claim,GatewayWorkQueue.Outcome.RETRY,"GATEWAY_SETTLEMENT_READ_UNKNOWN");
        String next=retained.nextCursor();boolean capped=next!=null&&retained.query().pageNumber()>=1000;
        boolean cyclic=next!=null&&Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_settlement_queries WHERE root_query_id=:requestId
                  AND organisation_id=:organisationId AND school_id=:schoolId AND cursor_sha256=:nextHash)
                """,p.addValue("requestId",source.requestId()).addValue("nextHash",GatewaySettlementReadStore.hash(next)),Boolean.class));
        var outcome=capped||cyclic?GatewayWorkQueue.Outcome.REVIEW_REQUIRED:GatewayWorkQueue.Outcome.COMPLETE;
        String code=capped?"GATEWAY_SETTLEMENT_PAGE_LIMIT":cyclic?"GATEWAY_SETTLEMENT_CURSOR_REVIEW":"GATEWAY_SETTLEMENT_PAGE_SELECTED";
        jdbc.update("""
                INSERT INTO fee_gateway_settlement_selections(query_id,organisation_id,school_id,request_id,read_id,
                  lease_token,lease_started_at,lease_until,attempt_number,outcome,reason_code)
                VALUES(:id,:organisationId,:schoolId,:requestId,:readId,:token,:started,:until,:attempt,:outcome,:code)
                """,p.addValue("readId",retained.id()).addValue("outcome",outcome.name()).addValue("code",code));
        if(next!=null&&outcome==GatewayWorkQueue.Outcome.COMPLETE)reads.prepareNext(claim.binding(),UUID.randomUUID(),retained.id());
        // Last write: failure/expired lease rolls back selection and children, not the earlier committed read.
        return complete(claim,outcome,code);
    }

    /** Invalid/revoked source can close as REVIEW without inventing selected data or human authority. */
    @Transactional
    GatewayAttemptWorker.Step review(String routeKey,GatewayWorkQueue.Claim claim){
        var p=parameters(routeKey,claim);current(p,claim,false);
        return complete(claim,GatewayWorkQueue.Outcome.REVIEW_REQUIRED,"GATEWAY_SETTLEMENT_SOURCE_REVIEW");
    }

    private GatewayAttemptWorker.Step complete(GatewayWorkQueue.Claim claim,GatewayWorkQueue.Outcome outcome,String code){
        if(Thread.currentThread().isInterrupted()||!queue.complete(claim,outcome,code,Duration.ofSeconds(GatewayWorkQueue.retryDelay(claim.attemptNumber(),claim.token()))))throw unavailable();
        return switch(outcome){case COMPLETE->GatewayAttemptWorker.Step.COMPLETE;case RETRY->GatewayAttemptWorker.Step.RETRY;case REVIEW_REQUIRED->GatewayAttemptWorker.Step.REVIEW_REQUIRED;};
    }
    private Source current(MapSqlParameterSource p,GatewayWorkQueue.Claim claim,boolean authorize){
        UUID requestId=jdbc.query("""
                SELECT request_id FROM fee_gateway_settlement_work WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentials
                """,p,r->r.next()?r.getObject(1,UUID.class):null);
        if(requestId==null)throw unavailable();p.addValue("requestId",requestId);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:fence,0))) locked",
                p.addValue("fence","gateway:settlement:request:"+requestId),Integer.class);
        var source=jdbc.query("""
                SELECT request.id,request.query_kind,request.from_exact,request.until_exact,request.source_summary_read_id,request.settlement_reference,
                  request.requested_by_user_id,request.requested_by_membership_id
                FROM fee_gateway_settlement_work work JOIN fee_gateway_settlement_requests request ON request.id=work.request_id
                  AND request.organisation_id=work.organisation_id AND request.school_id=work.school_id
                  AND request.payment_provider_config_id=work.payment_provider_config_id AND request.provider_code=work.provider_code
                  AND request.provider_environment=work.provider_environment AND request.merchant_account_reference=work.merchant_account_reference
                  AND request.credentials_secret_reference=work.credentials_secret_reference
                WHERE work.id=:id AND work.request_id=:requestId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode AND work.provider_environment=:environment
                  AND work.merchant_account_reference=:merchant AND work.credentials_secret_reference=:credentials
                  AND work.status='LEASED' AND work.lease_token=:token AND work.lease_started_at=:started AND work.lease_until=:until
                  AND work.attempt_count=:attempt FOR UPDATE OF work
                """,p,r->r.next()?new Source(requestId,r.getString("query_kind"),instant(r.getString("from_exact")),instant(r.getString("until_exact")),
                        r.getObject("source_summary_read_id",UUID.class),r.getString("settlement_reference"),r.getObject("requested_by_user_id",UUID.class),
                        r.getObject("requested_by_membership_id",UUID.class),null):null);
        if(source==null)throw unavailable();
        if(authorize){
            try{jdbc.query("SELECT require_gateway_settlement_actor(:organisationId,:schoolId,:membership,:userId,true)",
                    p.addValue("membership",source.membershipId()).addValue("userId",source.userId()),r->{});}
            catch(DataAccessException failure){if(actorDenied(failure))throw new SourceUnavailable();throw failure;}
            boolean active=Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM payment_provider_configs provider JOIN schools school ON school.id=provider.school_id
                      AND school.organisation_id=provider.organisation_id AND school.status='ACTIVE'
                      JOIN organisations organisation ON organisation.id=provider.organisation_id AND organisation.status='ACTIVE'
                      WHERE provider.id=:providerId AND provider.organisation_id=:organisationId AND provider.school_id=:schoolId
                        AND provider.provider_code=:providerCode AND provider.environment=:environment AND provider.status='ACTIVE'
                        AND provider.merchant_account_reference=:merchant AND provider.credentials_secret_reference=:credentials)
                    """,p,Boolean.class));
            if(!active)throw new SourceUnavailable();
        }
        Instant checked=jdbc.queryForObject("SELECT clock_timestamp()",p,(r,n)->r.getTimestamp(1).toInstant());
        if(checked==null||checked.isBefore(claim.startedAt())||!checked.isBefore(claim.leaseUntil()))throw unavailable();
        return new Source(source.requestId(),source.kind(),source.from(),source.until(),source.summaryReadId(),source.reference(),source.userId(),source.membershipId(),checked);
    }
    private MapSqlParameterSource parameters(String routeKey,GatewayWorkQueue.Claim claim){
        if(claim==null||claim.kind()!=GatewayWorkQueue.WorkKind.SETTLEMENT||claim.createDispatched())throw unavailable();
        var route=registry.findRoute(routeKey).orElseThrow(GatewaySettlementDispatch::unavailable);
        if(!registry.settlementsAllowed(route))throw unavailable();
        var binding=new GatewayWorkQueue.ProviderBinding(route.organisationId(),route.schoolId(),route.providerConfigId(),route.providerCode(),route.environment().name(),route.merchantAccountReference(),route.credentialsSecretReference());
        var context=TenantRequestContext.current().orElseThrow(GatewaySettlementDispatch::unavailable);
        if(!binding.equals(claim.binding())||context.userId()!=null||!binding.organisationId().equals(context.organisationId())
                ||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw unavailable();
        var p=new MapSqlParameterSource().addValue("organisationId",binding.organisationId()).addValue("schoolId",binding.schoolId())
                .addValue("providerId",binding.providerConfigId()).addValue("providerCode",binding.providerCode()).addValue("environment",binding.environment())
                .addValue("merchant",binding.merchantReference()).addValue("credentials",binding.credentialsReference()).addValue("id",claim.id())
                .addValue("token",claim.token()).addValue("started",Timestamp.from(claim.startedAt())).addValue("until",Timestamp.from(claim.leaseUntil())).addValue("attempt",claim.attemptNumber());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL AND current_setting('transaction_isolation')='read committed' AND current_setting('transaction_read_only')='off'",p,Boolean.class)))throw unavailable();return p;
    }
    private static void requirePrepared(GatewaySettlementReadStore.Prepared prepared,Source source,GatewayWorkQueue.Claim claim){
        if(!prepared.id().equals(claim.id())||!prepared.binding().equals(claim.binding())||!prepared.rootQueryId().equals(source.requestId())
                ||!prepared.kind().name().equals(source.kind())||!Objects.equals(prepared.from(),source.from())||!Objects.equals(prepared.until(),source.until())
                ||!Objects.equals(prepared.summaryReadId(),source.summaryReadId())||!Objects.equals(prepared.settlementReference(),source.reference()))throw unavailable();
    }
    private static boolean actorDenied(Throwable failure){while(failure!=null){if(failure instanceof java.sql.SQLException sql&&"P0001".equals(sql.getSQLState())&&sql.getMessage()!=null&&sql.getMessage().startsWith("ERROR: settlement request actor unavailable"))return true;failure=failure.getCause();}return false;}
    private static Instant instant(String value){return value==null?null:Instant.parse(value);}
    private static IllegalStateException unavailable(){return new IllegalStateException("Settlement processing requires exact current trusted background claim");}
    private record Source(UUID requestId,String kind,Instant from,Instant until,UUID summaryReadId,String reference,UUID userId,UUID membershipId,Instant checkedAt){}
}
