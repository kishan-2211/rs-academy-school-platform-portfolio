package com.educore.api.finance;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disabled by default; attempt/inbox/refund/report lanes share one bounded pool, with no broker or backlog. */
@Component
@ConditionalOnProperty(name = "app.finance.gateway.worker-enabled", havingValue = "true")
public final class GatewayAttemptScheduler {
    private static final Logger log = LoggerFactory.getLogger(GatewayAttemptScheduler.class);
    private static final int WORKERS = 2;
    private static final int MAX_STEPS = 8;
    private final GatewayProviderRegistry registry;
    private final GatewayAttemptWorker worker;
    private final GatewayWebhookWorker webhookWorker;
    private final GatewayRefundWorker refundWorker;
    private final GatewaySettlementWorker settlementWorker;
    private final List<Lane> lanes;
    private final Executor executor;
    private final AtomicInteger cursor = new AtomicInteger();

    @Autowired
    public GatewayAttemptScheduler(GatewayProviderRegistry registry, GatewayAttemptWorker worker, GatewayWebhookWorker webhookWorker,
            GatewayRefundWorker refundWorker,GatewaySettlementWorker settlementWorker) {
        this(registry,worker,Objects.requireNonNull(webhookWorker),Objects.requireNonNull(refundWorker),Objects.requireNonNull(settlementWorker),boundedExecutor());
    }

    public GatewayAttemptScheduler(GatewayProviderRegistry registry, GatewayAttemptWorker worker, GatewayWebhookWorker webhookWorker,GatewayRefundWorker refundWorker) {
        this(registry,worker,Objects.requireNonNull(webhookWorker),Objects.requireNonNull(refundWorker),null,boundedExecutor());
    }

    private static ExecutorService boundedExecutor() {
        return new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "school-fee-gateway-worker");
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    GatewayAttemptScheduler(GatewayProviderRegistry registry, GatewayAttemptWorker worker, Executor executor) {
        this(registry, worker, null,null, executor);
    }

    GatewayAttemptScheduler(GatewayProviderRegistry registry, GatewayAttemptWorker worker, GatewayWebhookWorker webhookWorker, Executor executor) {
        this(registry,worker,webhookWorker,null,executor);
    }

    GatewayAttemptScheduler(GatewayProviderRegistry registry, GatewayAttemptWorker worker, GatewayWebhookWorker webhookWorker,GatewayRefundWorker refundWorker, Executor executor) {
        this(registry,worker,webhookWorker,refundWorker,null,executor);
    }

    GatewayAttemptScheduler(GatewayProviderRegistry registry, GatewayAttemptWorker worker, GatewayWebhookWorker webhookWorker,
            GatewayRefundWorker refundWorker,GatewaySettlementWorker settlementWorker,Executor executor) {
        this.registry = Objects.requireNonNull(registry); this.worker = Objects.requireNonNull(worker);
        this.webhookWorker = webhookWorker;
        this.refundWorker=refundWorker;
        this.settlementWorker=settlementWorker;
        var available=new ArrayList<Lane>();available.add(Lane.ATTEMPT);
        if(webhookWorker!=null)available.add(Lane.WEBHOOK);
        if(refundWorker!=null)available.add(Lane.REFUND);
        if(settlementWorker!=null)available.add(Lane.SETTLEMENT);
        this.lanes=List.copyOf(available);
        this.executor = Objects.requireNonNull(executor);
    }

    @Scheduled(fixedDelayString = "${app.finance.gateway.poll-delay-ms:5000}",
            initialDelayString = "${app.finance.gateway.initial-delay-ms:30000}")
    public void dispatchDueAttempts() {
        if (!registry.workerEnabled() || Thread.currentThread().isInterrupted()) return;
        var routes = registry.configuredRoutes();
        if (routes.isEmpty()) return;
        // Adjacent lanes for each route keep even one campus serving every kind
        // through the same two slots; no extra executor or backlog is created.
        for (int slot = 0; slot < WORKERS; slot++) {
            int candidate = Math.floorMod(cursor.get(), Math.multiplyExact(routes.size(), lanes.size()));
            var route = routes.get(candidate / lanes.size());
            var lane=lanes.get(candidate%lanes.size());
            try {
                executor.execute(() -> process(route, lane));
                cursor.incrementAndGet(); // Advance only for accepted work, not for a busy slot.
            } catch (RejectedExecutionException busy) {
                // Keep this candidate first for the next poll. Advancing rejected
                // work can starve a route when only one thread is regularly free.
                break; // No waiting, retry loop or executor backlog.
            }
        }
    }

    private void process(GatewayProviderRegistry.Route route,Lane lane) {
        for (int step = 0; step < MAX_STEPS && registry.workerEnabled() && !Thread.currentThread().isInterrupted(); step++) {
            try {
                var result=switch(lane){
                    case ATTEMPT -> worker.runNext(route.routeKey());
                    case WEBHOOK -> webhookWorker.runNext(route.routeKey());
                    case REFUND -> refundWorker.runNext(route.routeKey());
                    case SETTLEMENT -> registry.settlementsAllowed(route)?settlementWorker.runNext(route.routeKey()):GatewayAttemptWorker.Step.DISABLED;
                };
                switch (result) {
                    case IDLE, DISABLED, RECOVERED, UNAVAILABLE, FENCED_OUT -> { return; }
                    case RETRY, COMPLETE, REVIEW_REQUIRED -> { /* Next due same-kind work, within this small batch. */ }
                }
            } catch (RuntimeException unavailable) {
                // Do not log route secrets, private IDs, causes or provider bodies.
                log.warn("Fee gateway batch unavailable; committed evidence is preserved");
                return;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }

    private enum Lane { ATTEMPT, WEBHOOK, REFUND, SETTLEMENT }
}
