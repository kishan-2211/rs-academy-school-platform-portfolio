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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One bounded read-only report step in the existing two-slot scheduler. No money or bank actions. */
@Service
public final class GatewaySettlementWorker {
    private final GatewayProviderRegistry registry;
    private final GatewayWorkQueue queue;
    private final GatewaySettlementDispatch dispatch;
    private final GatewaySettlementReadStore reads;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<GatewayProviderRegistry.Route,PaymentProviderAdapter> providers=new ConcurrentHashMap<>();
    public GatewaySettlementWorker(GatewayProviderRegistry registry,GatewayWorkQueue queue,GatewaySettlementDispatch dispatch,
            GatewaySettlementReadStore reads,PlatformTransactionManager manager){
        this.registry=Objects.requireNonNull(registry);this.queue=Objects.requireNonNull(queue);this.dispatch=Objects.requireNonNull(dispatch);this.reads=Objects.requireNonNull(reads);
        transactions=new TransactionTemplate(Objects.requireNonNull(manager));transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15);transactions.setReadOnly(false);
    }
    public GatewayAttemptWorker.Step runNext(String routeKey){
        outside();var route=registry.findRoute(routeKey).orElse(null);
        if(route==null)return GatewayAttemptWorker.Step.UNAVAILABLE;
        if(!registry.settlementsAllowed(route))return GatewayAttemptWorker.Step.DISABLED;
        if(Thread.currentThread().isInterrupted())return GatewayAttemptWorker.Step.FENCED_OUT;
        var binding=new GatewayWorkQueue.ProviderBinding(route.organisationId(),route.schoolId(),route.providerConfigId(),route.providerCode(),route.environment().name(),route.merchantAccountReference(),route.credentialsSecretReference());
        Claimed claimed;
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){
            claimed=Objects.requireNonNull(transactions.execute(status->{
                boolean recovered=queue.recoverExpiredSettlementLease(binding)!=null;return new Claimed(queue.claimNextSettlement(binding),recovered);
            }));
        }catch(RuntimeException failed){return GatewayAttemptWorker.Step.FENCED_OUT;}
        if(claimed.claim()==null)return claimed.recovered()?GatewayAttemptWorker.Step.RECOVERED:GatewayAttemptWorker.Step.IDLE;
        var claim=claimed.claim();GatewaySettlementDispatch.Admitted admitted;long beforeAdmission=System.nanoTime();
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){admitted=transactions.execute(status->dispatch.prepare(routeKey,claim));}
        catch(GatewaySettlementDispatch.SourceUnavailable denied){return review(routeKey,claim);}
        catch(RuntimeException failed){return GatewayAttemptWorker.Step.FENCED_OUT;}
        if(admitted==null||!claim.id().equals(admitted.query().id())||!binding.equals(admitted.query().binding()))return GatewayAttemptWorker.Step.FENCED_OUT;
        UUID readId=UUID.randomUUID();Object page=null;
        Duration budget=GatewayAttemptWorker.available(claim.leaseUntil(),admitted.checkedAt(),beforeAdmission,Duration.ofSeconds(45),Duration.ofSeconds(30));
        if(Thread.currentThread().isInterrupted())return GatewayAttemptWorker.Step.FENCED_OUT;
        if(budget.isPositive()){
            try{
                var provider=providers.computeIfAbsent(route,registry::provider);
                // Provider construction consumes the same lease budget; it must not reset the I/O deadline.
                budget=GatewayAttemptWorker.available(claim.leaseUntil(),admitted.checkedAt(),beforeAdmission,Duration.ofSeconds(45),Duration.ofSeconds(30));
                if(!budget.isPositive())return GatewayAttemptWorker.Step.FENCED_OUT;
                if(provider instanceof GatewaySettlementProvider reports)page=read(reports,admitted.query(),readId,budget);
            }catch(RuntimeException unavailable){/* A missing provider/credential/result is UNKNOWN, never an empty complete page. */}
        }
        GatewaySettlementReadStore.Read retained;
        final Object observed=page;
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){
            retained=transactions.execute(status->{
                if(observed instanceof GatewaySettlementProvider.SettlementPage summary)return reads.retainSummaries(binding,claim.id(),readId,summary);
                if(observed instanceof GatewaySettlementProvider.SettlementEventsPage events)return reads.retainEvents(binding,claim.id(),readId,events);
                return reads.retainUnknown(binding,claim.id(),readId);
            });
        }catch(RuntimeException failed){return GatewayAttemptWorker.Step.FENCED_OUT;}
        if(retained==null||!readId.equals(retained.id()))return GatewayAttemptWorker.Step.FENCED_OUT;
        if(Thread.currentThread().isInterrupted())return GatewayAttemptWorker.Step.FENCED_OUT;
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){return Objects.requireNonNull(transactions.execute(status->dispatch.finish(routeKey,claim,retained)));}
        catch(GatewaySettlementDispatch.SourceUnavailable denied){return review(routeKey,claim);}
        catch(RuntimeException failed){return GatewayAttemptWorker.Step.FENCED_OUT;}
    }
    private GatewayAttemptWorker.Step review(String routeKey,GatewayWorkQueue.Claim claim){
        if(Thread.currentThread().isInterrupted())return GatewayAttemptWorker.Step.FENCED_OUT;
        try(var ignored=TenantRequestContext.open(null,claim.binding().organisationId())){return Objects.requireNonNull(transactions.execute(status->dispatch.review(routeKey,claim)));}
        catch(RuntimeException failed){return GatewayAttemptWorker.Step.FENCED_OUT;}
    }
    private static Object read(GatewaySettlementProvider provider,GatewaySettlementReadStore.Prepared prepared,UUID readId,Duration budget){
        outside();long deadline=System.nanoTime()+budget.toNanos();var task=new FutureTask<Object>(()->{outside();
            return prepared.kind()==GatewaySettlementReadStore.Kind.SUMMARY?provider.fetchSettlements(prepared.summaryQuery(readId)):provider.fetchSettlementEvents(prepared.eventsQuery(readId));});
        Thread.ofVirtual().name("school-gateway-settlement-read").start(task);
        try{long left=deadline-System.nanoTime();return left>0?task.get(left,TimeUnit.NANOSECONDS):null;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
        catch(ExecutionException|TimeoutException unavailable){return null;}
        finally{if(!task.isDone())task.cancel(true);}
    }
    private static void outside(){if(TenantRequestContext.current().isPresent()||TransactionSynchronizationManager.isActualTransactionActive()
            ||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw new IllegalStateException("Settlement I/O requires trusted caller outside persistence");}
    private record Claimed(GatewayWorkQueue.Claim claim,boolean recovered){}
}
