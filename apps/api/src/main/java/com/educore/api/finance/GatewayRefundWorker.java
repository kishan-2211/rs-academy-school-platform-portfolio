package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One bounded approved-refund step. No financial posting, budget release or HTTP inside persistence. */
@Service
public final class GatewayRefundWorker {
    private final GatewayProviderRegistry registry;
    private final GatewayWorkQueue queue;
    private final GatewayRefundDispatch dispatch;
    private final GatewayProviderDiscovery discovery;
    private final GatewayRefundReadStore reads;
    private final GatewayRefundSuccessStore successes;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<GatewayProviderRegistry.Route,PaymentProviderAdapter> providers=new ConcurrentHashMap<>();

    public GatewayRefundWorker(GatewayProviderRegistry registry,GatewayWorkQueue queue,GatewayRefundDispatch dispatch,
            GatewayProviderDiscovery discovery,GatewayRefundReadStore reads,GatewayRefundSuccessStore successes,
            NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager){
        this.registry=Objects.requireNonNull(registry);this.queue=Objects.requireNonNull(queue);this.dispatch=Objects.requireNonNull(dispatch);
        this.discovery=Objects.requireNonNull(discovery);this.reads=Objects.requireNonNull(reads);this.successes=Objects.requireNonNull(successes);
        this.jdbc=Objects.requireNonNull(jdbc);transactions=new TransactionTemplate(Objects.requireNonNull(manager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transactions.setTimeout(15);transactions.setReadOnly(false);
    }

    public GatewayAttemptWorker.Step runNext(String routeKey){
        outsidePersistence();
        if(!registry.workerEnabled())return GatewayAttemptWorker.Step.DISABLED;
        if(Thread.currentThread().isInterrupted())return GatewayAttemptWorker.Step.FENCED_OUT;
        var route=registry.findRoute(routeKey).orElse(null);
        if(route==null)return GatewayAttemptWorker.Step.UNAVAILABLE;
        if(route.environment()==CashfreePaymentProviderAdapter.Environment.LIVE&&!registry.liveEnabled())return GatewayAttemptWorker.Step.DISABLED;
        var binding=new GatewayWorkQueue.ProviderBinding(route.organisationId(),route.schoolId(),route.providerConfigId(),route.providerCode(),route.environment().name(),route.merchantAccountReference(),route.credentialsSecretReference());
        long beforeClaim=System.nanoTime();
        Claimed claimed;
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){
            claimed=Objects.requireNonNull(transactions.execute(status->{
                fence(binding); // Finance before work-row locks, matching admission/retention.
                boolean recovered=queue.recoverExpiredRefundLease(binding)!=null;
                var claim=queue.claimNextRefund(binding);
                return claim==null?new Claimed(null,null,recovered):new Claimed(claim,dispatch.loadForReadRecovery(routeKey,claim),recovered);
            }));
        }catch(RuntimeException unknown){return GatewayAttemptWorker.Step.FENCED_OUT;}
        if(claimed.claim()==null)return claimed.recovered()?GatewayAttemptWorker.Step.RECOVERED:GatewayAttemptWorker.Step.IDLE;
        var claim=claimed.claim();var source=claimed.source();
        if(source==null || claim.kind()!=GatewayWorkQueue.WorkKind.REFUND || !binding.equals(claim.binding())
                || !claim.id().equals(source.approved().expected().refundId()) || !binding.equals(source.approved().expected().binding()))return GatewayAttemptWorker.Step.FENCED_OUT;
        if(!source.mustRecover()&&!registry.refundsEnabled())return finish(routeKey,claim,null,"GATEWAY_REFUND_NEW_DISABLED",false);
        PaymentProviderAdapter provider;
        try{provider=providers.computeIfAbsent(route,registry::provider);}
        catch(RuntimeException unavailable){return finish(routeKey,claim,null,"GATEWAY_REFUND_PROVIDER_UNAVAILABLE",false);}
        if(Thread.currentThread().isInterrupted())return GatewayAttemptWorker.Step.FENCED_OUT;
        if(!source.mustRecover()){
            GatewayRefundDispatch.Command command;long beforeAdmission=System.nanoTime();
            try(var ignored=TenantRequestContext.open(null,binding.organisationId())){
                command=transactions.execute(status->dispatch.admit(routeKey,claim));
            }catch(RuntimeException unknownCommit){
                // A commit may have succeeded despite an unavailable response.
                // Fresh claim validation sees any marker; no POST occurs here.
                return finish(routeKey,claim,null,"GATEWAY_REFUND_ADMISSION_UNAVAILABLE",false);
            }
            if(command==null || !source.approved().equals(command.approved()))return GatewayAttemptWorker.Step.FENCED_OUT;
            Duration budget=GatewayAttemptWorker.available(claim.leaseUntil(),command.dispatchedAt(),beforeAdmission,Duration.ofSeconds(30),Duration.ofSeconds(20));
            boolean returned=budget.isPositive()&&postOnce(provider,command.request(),budget);
            // Mutation replies, including SUCCESS, never establish money. A
            // later lease independently GETs the exact persisted refund UUID.
            return finish(routeKey,claim,null,returned?"GATEWAY_REFUND_REQUIRES_GET":"GATEWAY_REFUND_POST_OUTCOME_UNKNOWN",false);
        }
        Duration budget=GatewayAttemptWorker.available(claim.leaseUntil(),source.checkedAt(),beforeClaim,Duration.ofSeconds(45),Duration.ofSeconds(40));
        if(!budget.isPositive())return finish(routeKey,claim,null,"GATEWAY_REFUND_READ_BUDGET_UNAVAILABLE",false);
        UUID readId=UUID.randomUUID();GatewayProviderDiscovery.RefundRead observed;
        try{observed=discovery.readRefund(provider,source.approved(),readId,budget);}
        catch(RuntimeException unavailable){return finish(routeKey,claim,null,"GATEWAY_REFUND_READ_UNAVAILABLE",false);}
        if(observed==null || !readId.equals(observed.readId()) || !source.approved().equals(observed.prepared()))return GatewayAttemptWorker.Step.FENCED_OUT;
        GatewayRefundReadStore.Read retained;
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){
            // Facts survive lost leases/interruption. Revalidate frozen approved
            // source in the store, not current processing ownership here.
            retained=transactions.execute(status->reads.retain(binding,claim.id(),readId,observed.facts()));
        }catch(RuntimeException unknownCommit){return GatewayAttemptWorker.Step.FENCED_OUT;}
        if(retained==null || !readId.equals(retained.id()) || !source.approved().expected().equals(retained.expected()))return GatewayAttemptWorker.Step.FENCED_OUT;
        return finish(routeKey,claim,retained,null,false);
    }

    private GatewayAttemptWorker.Step finish(String routeKey,GatewayWorkQueue.Claim claim,GatewayRefundReadStore.Read retained,String issue,boolean review){
        if(Thread.currentThread().isInterrupted())return GatewayAttemptWorker.Step.FENCED_OUT;
        try(var ignored=TenantRequestContext.open(null,claim.binding().organisationId())){
            return Objects.requireNonNull(transactions.execute(status->{
                var current=dispatch.loadForReadRecovery(routeKey,claim); // Full identity and actual post-wait clock.
                GatewayWorkQueue.Outcome outcome=review?GatewayWorkQueue.Outcome.REVIEW_REQUIRED:GatewayWorkQueue.Outcome.RETRY;
                String code=issue;
                if(retained!=null){
                    if(!current.approved().expected().equals(retained.expected()))throw unavailable();
                    var verified=retained.verification().outcome();
                    if(verified==GatewayRefundVerification.Outcome.REVIEW_REQUIRED){outcome=GatewayWorkQueue.Outcome.REVIEW_REQUIRED;code="GATEWAY_REFUND_EVIDENCE_REVIEW_REQUIRED";}
                    else if(verified==GatewayRefundVerification.Outcome.VERIFIED_SUCCESS){
                        var success=Objects.requireNonNull(successes.retain(claim.binding(),claim.id(),retained.id()));
                        var expected=current.approved().expected();
                        if(!success.refundId().equals(claim.id()) || !success.approvalDecisionId().equals(current.approved().approvalDecisionId())
                                || !success.paymentId().equals(expected.paymentId()) || !success.captureId().equals(expected.captureId())
                                || success.amount().compareTo(expected.refundAmount())!=0 || !"INR".equals(success.currencyCode())
                                || !"HOLD".equals(success.status()) || !success.evidenceSha256().equals(retained.verification().evidenceSha256()))throw unavailable();
                        outcome=GatewayWorkQueue.Outcome.COMPLETE;code="GATEWAY_REFUND_VERIFIED_AWAITING_POSTING";
                    }else if(hasRetainedSuccess(claim)){outcome=GatewayWorkQueue.Outcome.COMPLETE;code="GATEWAY_REFUND_SUCCESS_ALREADY_RETAINED";}
                    else{
                        var fact=retained.observation().refund();
                        boolean terminal=fact!=null&&(fact.status()==PaymentProviderAdapter.RefundStatus.FAILED||fact.status()==PaymentProviderAdapter.RefundStatus.CANCELLED);
                        outcome=terminal?GatewayWorkQueue.Outcome.REVIEW_REQUIRED:GatewayWorkQueue.Outcome.RETRY;
                        code=terminal?"GATEWAY_REFUND_FAILURE_REVIEW_REQUIRED":"GATEWAY_REFUND_CONFIRMATION_PENDING";
                    }
                }
                Duration delay="GATEWAY_REFUND_REQUIRES_GET".equals(code)?Duration.ofSeconds(1):Duration.ofSeconds(GatewayWorkQueue.retryDelay(claim.attemptNumber(),claim.token()));
                if(Thread.currentThread().isInterrupted() || !queue.complete(claim,outcome,code,delay))throw unavailable();
                return switch(outcome){case COMPLETE->GatewayAttemptWorker.Step.COMPLETE;case RETRY->GatewayAttemptWorker.Step.RETRY;case REVIEW_REQUIRED->GatewayAttemptWorker.Step.REVIEW_REQUIRED;};
            }));
        }catch(GatewayRefundSuccessStore.ConflictingSuccessEvidenceException conflict){
            return finish(routeKey,claim,null,"GATEWAY_REFUND_EVIDENCE_REVIEW_REQUIRED",true);
        }catch(RuntimeException unknownCommit){return GatewayAttemptWorker.Step.FENCED_OUT;}
    }

    private boolean hasRetainedSuccess(GatewayWorkQueue.Claim claim){
        var b=claim.binding();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_refund_successes WHERE refund_request_id=:refundId
                  AND organisation_id=:organisationId AND school_id=:schoolId AND payment_provider_config_id=:providerId
                  AND provider_code=:providerCode AND provider_environment=:environment AND merchant_account_reference=:merchant
                  AND credentials_secret_reference=:credentials AND status='HOLD')
                """,new MapSqlParameterSource().addValue("refundId",claim.id()).addValue("organisationId",b.organisationId()).addValue("schoolId",b.schoolId())
                .addValue("providerId",b.providerConfigId()).addValue("providerCode",b.providerCode()).addValue("environment",b.environment()).addValue("merchant",b.merchantReference()).addValue("credentials",b.credentialsReference()),Boolean.class));
    }

    private void fence(GatewayWorkQueue.ProviderBinding binding){
        var p=new MapSqlParameterSource().addValue("organisationId",binding.organisationId()).addValue("lockKey",binding.schoolId()+":finance");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL",p,Boolean.class)))throw unavailable();
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked",p,Integer.class);
    }

    private static boolean postOnce(PaymentProviderAdapter provider,PaymentProviderAdapter.RefundRequest request,Duration budget){
        if(Thread.currentThread().isInterrupted())return false;
        long until=System.nanoTime()+budget.toNanos();
        var task=new FutureTask<Boolean>(()->{outsidePersistence();provider.requestRefund(request);return true;});
        Thread.ofVirtual().name("school-gateway-refund-post").start(task);
        try{long left=until-System.nanoTime();return left>0&&task.get(left,TimeUnit.NANOSECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        catch(ExecutionException|TimeoutException unknown){return false;}
        finally{if(!task.isDone())task.cancel(true);}
    }
    private static void outsidePersistence(){if(TenantRequestContext.current().isPresent()||TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw unavailable();}
    private static IllegalStateException unavailable(){return new IllegalStateException("Refund processing requires exact approved background work outside caller persistence");}
    private record Claimed(GatewayWorkQueue.Claim claim,GatewayRefundDispatch.Recovery source,boolean recovered){}
}
